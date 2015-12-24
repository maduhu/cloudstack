// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.engine.orchestration;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.cloudstack.acl.ControlledEntity.ACLType;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.cloud.entity.api.db.VMNetworkMapVO;
import org.apache.cloudstack.engine.cloud.entity.api.db.dao.VMNetworkMapDao;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.framework.config.ConfigDepot;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.ConfigKey.Scope;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.messagebus.MessageBus;
import org.apache.cloudstack.framework.messagebus.PublishScope;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.cloudstack.region.PortableIpDao;
import org.apache.log4j.Logger;

import com.cloud.agent.AgentManager;
import com.cloud.agent.Listener;
import com.cloud.agent.api.AgentControlAnswer;
import com.cloud.agent.api.AgentControlCommand;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckNetworkAnswer;
import com.cloud.agent.api.CheckNetworkCommand;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.StartupRoutingCommand;
import com.cloud.agent.api.to.NicTO;
import com.cloud.alert.AlertManager;
import com.cloud.configuration.ConfigurationManager;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.DataCenterVnetVO;
import com.cloud.dc.PodVlanMapVO;
import com.cloud.dc.Vlan;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.DataCenterVnetDao;
import com.cloud.dc.dao.PodVlanMapDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.deploy.DeployDestination;
import com.cloud.deploy.DeploymentPlan;
import com.cloud.domain.Domain;
import com.cloud.event.dao.UsageEventDao;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.ConnectionException;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InsufficientVirtualNetworkCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.UnsupportedServiceException;
import com.cloud.host.Host;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.IpAddress;
import com.cloud.network.IpAddressManager;
import com.cloud.network.Network;
import com.cloud.network.Network.Capability;
import com.cloud.network.Network.Event;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkMigrationResponder;
import com.cloud.network.NetworkModel;
import com.cloud.network.NetworkProfile;
import com.cloud.network.NetworkStateListener;
import com.cloud.network.Networks;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.PhysicalNetwork;
import com.cloud.network.PhysicalNetworkSetupInfo;
import com.cloud.network.RemoteAccessVpn;
import com.cloud.network.addr.PublicIp;
import com.cloud.network.dao.AccountGuestVlanMapDao;
import com.cloud.network.dao.AccountGuestVlanMapVO;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkAccountDao;
import com.cloud.network.dao.NetworkAccountVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkDomainDao;
import com.cloud.network.dao.NetworkDomainVO;
import com.cloud.network.dao.NetworkServiceMapDao;
import com.cloud.network.dao.NetworkServiceMapVO;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkServiceProviderDao;
import com.cloud.network.dao.PhysicalNetworkTrafficTypeDao;
import com.cloud.network.dao.PhysicalNetworkTrafficTypeVO;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.network.element.AggregatedCommandExecutor;
import com.cloud.network.element.DhcpServiceProvider;
import com.cloud.network.element.IpDeployer;
import com.cloud.network.element.LoadBalancingServiceProvider;
import com.cloud.network.element.NetworkElement;
import com.cloud.network.element.StaticNatServiceProvider;
import com.cloud.network.element.UserDataServiceProvider;
import com.cloud.network.guru.NetworkGuru;
import com.cloud.network.guru.NetworkGuruAdditionalFunctions;
import com.cloud.network.lb.LoadBalancingRulesManager;
import com.cloud.network.rules.FirewallManager;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.FirewallRule.Purpose;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.network.rules.LoadBalancerContainer.Scheme;
import com.cloud.network.rules.PortForwardingRuleVO;
import com.cloud.network.rules.RulesManager;
import com.cloud.network.rules.StaticNatRule;
import com.cloud.network.rules.StaticNatRuleImpl;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.network.vpc.NetworkACLManager;
import com.cloud.network.vpc.Vpc;
import com.cloud.network.vpc.VpcManager;
import com.cloud.network.vpc.dao.PrivateIpDao;
import com.cloud.network.vpc.dao.VpcOfferingServiceMapDao;
import com.cloud.network.vpn.RemoteAccessVpnService;
import com.cloud.offering.NetworkOffering;
import com.cloud.offering.NetworkOffering.Availability;
import com.cloud.offerings.NetworkOfferingServiceMapVO;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.offerings.dao.NetworkOfferingDetailsDao;
import com.cloud.offerings.dao.NetworkOfferingServiceMapDao;
import com.cloud.user.Account;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.User;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.UuidUtils;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.JoinBuilder.JoinType;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionCallbackWithExceptionNoReturn;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.utils.fsm.StateMachine2;
import com.cloud.utils.net.NetUtils;
import com.cloud.utils.net.cidr.BadCIDRException;
import com.cloud.utils.net.cidr.CIDR;
import com.cloud.utils.net.cidr.CIDRFactory;
import com.cloud.vm.Nic;
import com.cloud.vm.Nic.ReservationStrategy;
import com.cloud.vm.NicIpAlias;
import com.cloud.vm.NicProfile;
import com.cloud.vm.NicVO;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.ReservationContextImpl;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.Type;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.NicIpAliasDao;
import com.cloud.vm.dao.NicIpAliasVO;
import com.cloud.vm.dao.NicSecondaryIpDao;
import com.cloud.vm.dao.NicSecondaryIpVO;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;

/**
 * NetworkManagerImpl implements NetworkManager.
 */
public class NetworkOrchestrator extends ManagerBase implements NetworkOrchestrationService, Listener, Configurable {
    static final Logger s_logger = Logger.getLogger(NetworkOrchestrator.class);

    @Inject
    EntityManager _entityMgr;
    @Inject
    DataCenterDao _dcDao = null;
    @Inject
    VlanDao _vlanDao = null;
    @Inject
    IPAddressDao _ipAddressDao = null;
    @Inject
    AccountDao _accountDao = null;
    @Inject
    ConfigurationDao _configDao;
    @Inject
    UserVmDao _userVmDao = null;
    @Inject
    AlertManager _alertMgr;
    @Inject
    ConfigurationManager _configMgr;
    @Inject
    NetworkOfferingDao _networkOfferingDao = null;
    @Inject
    NetworkDao _networksDao = null;
    @Inject
    NicDao _nicDao = null;
    @Inject
    RulesManager _rulesMgr;
    @Inject
    LoadBalancingRulesManager _lbMgr;
    @Inject
    RemoteAccessVpnService _vpnMgr;
    @Inject
    PodVlanMapDao _podVlanMapDao;
    @Inject
    NetworkOfferingDetailsDao _ntwkOffDetailsDao;
    @Inject
    AccountGuestVlanMapDao _accountGuestVlanMapDao;
    @Inject
    DataCenterVnetDao _datacenterVnetDao;
    @Inject
    NetworkAccountDao _networkAccountDao;
    @Inject
    protected NicIpAliasDao _nicIpAliasDao;
    @Inject
    protected IPAddressDao _publicIpAddressDao;
    @Inject
    protected IpAddressManager _ipAddrMgr;
    @Inject
    MessageBus _messageBus;
    @Inject
    VMNetworkMapDao _vmNetworkMapDao;

    List<NetworkGuru> networkGurus;

    public List<NetworkGuru> getNetworkGurus() {
        return networkGurus;
    }

    public void setNetworkGurus(final List<NetworkGuru> networkGurus) {
        this.networkGurus = networkGurus;
    }

    List<NetworkElement> networkElements;

    public List<NetworkElement> getNetworkElements() {
        return networkElements;
    }

    public void setNetworkElements(final List<NetworkElement> networkElements) {
        this.networkElements = networkElements;
    }

    @Inject
    NetworkDomainDao _networkDomainDao;

    List<IpDeployer> ipDeployers;

    public List<IpDeployer> getIpDeployers() {
        return ipDeployers;
    }

    public void setIpDeployers(final List<IpDeployer> ipDeployers) {
        this.ipDeployers = ipDeployers;
    }

    List<DhcpServiceProvider> _dhcpProviders;

    public List<DhcpServiceProvider> getDhcpProviders() {
        return _dhcpProviders;
    }

    public void setDhcpProviders(final List<DhcpServiceProvider> dhcpProviders) {
        _dhcpProviders = dhcpProviders;
    }

    @Inject
    VMInstanceDao _vmDao;
    @Inject
    FirewallManager _firewallMgr;
    @Inject
    FirewallRulesDao _firewallDao;
    @Inject
    ResourceLimitService _resourceLimitMgr;

    @Inject
    NetworkOfferingServiceMapDao _ntwkOfferingSrvcDao;
    @Inject
    PhysicalNetworkDao _physicalNetworkDao;
    @Inject
    PhysicalNetworkServiceProviderDao _pNSPDao;
    @Inject
    PortForwardingRulesDao _portForwardingRulesDao;
    @Inject
    PhysicalNetworkTrafficTypeDao _pNTrafficTypeDao;
    @Inject
    AgentManager _agentMgr;
    @Inject
    HostDao _hostDao;
    @Inject
    NetworkServiceMapDao _ntwkSrvcDao;
    @Inject
    VpcManager _vpcMgr;
    @Inject
    VpcOfferingServiceMapDao _vpcOffServiceDao;
    @Inject
    PrivateIpDao _privateIpDao;
    @Inject
    NetworkACLManager _networkACLMgr;
    @Inject
    UsageEventDao _usageEventDao;
    @Inject
    NetworkModel _networkModel;
    @Inject
    NicSecondaryIpDao _nicSecondaryIpDao;
    @Inject
    PortableIpDao _portableIpDao;
    @Inject
    ConfigDepot _configDepot;

    protected StateMachine2<Network.State, Network.Event, Network> _stateMachine;
    ScheduledExecutorService _executor;

    SearchBuilder<IPAddressVO> AssignIpAddressSearch;
    SearchBuilder<IPAddressVO> AssignIpAddressFromPodVlanSearch;

    HashMap<Long, Long> _lastNetworkIdsToFree = new HashMap<Long, Long>();

    @Override
    @DB
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        // populate providers
        final Map<Network.Service, Set<Network.Provider>> defaultSharedNetworkOfferingProviders = new HashMap<Network.Service, Set<Network.Provider>>();
        final Set<Network.Provider> defaultProviders = new HashSet<Network.Provider>();

        defaultProviders.add(Network.Provider.VirtualRouter);
        defaultSharedNetworkOfferingProviders.put(Service.Dhcp, defaultProviders);
        defaultSharedNetworkOfferingProviders.put(Service.Dns, defaultProviders);
        defaultSharedNetworkOfferingProviders.put(Service.UserData, defaultProviders);

        final Map<Network.Service, Set<Network.Provider>> defaultIsolatedNetworkOfferingProviders = defaultSharedNetworkOfferingProviders;
        defaultIsolatedNetworkOfferingProviders.put(Service.Dhcp, defaultProviders);
        defaultIsolatedNetworkOfferingProviders.put(Service.Dns, defaultProviders);
        defaultIsolatedNetworkOfferingProviders.put(Service.UserData, defaultProviders);
        defaultIsolatedNetworkOfferingProviders.put(Service.Firewall, defaultProviders);
        defaultIsolatedNetworkOfferingProviders.put(Service.Gateway, defaultProviders);
        defaultIsolatedNetworkOfferingProviders.put(Service.Lb, defaultProviders);
        defaultIsolatedNetworkOfferingProviders.put(Service.StaticNat, defaultProviders);
        defaultIsolatedNetworkOfferingProviders.put(Service.PortForwarding, defaultProviders);
        defaultIsolatedNetworkOfferingProviders.put(Service.Vpn, defaultProviders);

        final Map<Network.Service, Set<Network.Provider>> defaultSharedSGEnabledNetworkOfferingProviders = new HashMap<Network.Service, Set<Network.Provider>>();
        defaultSharedSGEnabledNetworkOfferingProviders.put(Service.Dhcp, defaultProviders);
        defaultSharedSGEnabledNetworkOfferingProviders.put(Service.Dns, defaultProviders);
        defaultSharedSGEnabledNetworkOfferingProviders.put(Service.UserData, defaultProviders);
        final Set<Provider> sgProviders = new HashSet<Provider>();
        sgProviders.add(Provider.SecurityGroupProvider);
        defaultSharedSGEnabledNetworkOfferingProviders.put(Service.SecurityGroup, sgProviders);

        final Map<Network.Service, Set<Network.Provider>> defaultIsolatedSourceNatEnabledNetworkOfferingProviders = new HashMap<Network.Service, Set<Network.Provider>>();
        defaultProviders.clear();
        defaultProviders.add(Network.Provider.VirtualRouter);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.Dhcp, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.Dns, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.UserData, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.Firewall, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.Gateway, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.Lb, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.SourceNat, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.StaticNat, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.PortForwarding, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.Vpn, defaultProviders);

        final Map<Network.Service, Set<Network.Provider>> defaultVPCOffProviders = new HashMap<Network.Service, Set<Network.Provider>>();
        defaultProviders.clear();
        defaultProviders.add(Network.Provider.VPCVirtualRouter);
        defaultVPCOffProviders.put(Service.Dhcp, defaultProviders);
        defaultVPCOffProviders.put(Service.Dns, defaultProviders);
        defaultVPCOffProviders.put(Service.UserData, defaultProviders);
        defaultVPCOffProviders.put(Service.NetworkACL, defaultProviders);
        defaultVPCOffProviders.put(Service.Gateway, defaultProviders);
        defaultVPCOffProviders.put(Service.Lb, defaultProviders);
        defaultVPCOffProviders.put(Service.SourceNat, defaultProviders);
        defaultVPCOffProviders.put(Service.StaticNat, defaultProviders);
        defaultVPCOffProviders.put(Service.PortForwarding, defaultProviders);
        defaultVPCOffProviders.put(Service.Vpn, defaultProviders);

        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(final TransactionStatus status) {
                NetworkOfferingVO offering = null;
                //#1 - quick cloud network offering
                if (_networkOfferingDao.findByUniqueName(NetworkOffering.QuickCloudNoServices) == null) {
                    offering = _configMgr.createNetworkOffering(NetworkOffering.QuickCloudNoServices, "Offering for QuickCloud with no services", TrafficType.Guest, null, true,
                            Availability.Optional, null, new HashMap<Network.Service, Set<Network.Provider>>(), true, Network.GuestType.Shared, false, null, true, null, true,
                            false, null, false, null, true);
                    offering.setState(NetworkOffering.State.Enabled);
                    _networkOfferingDao.update(offering.getId(), offering);
                }

                //#2 - SG enabled network offering
                if (_networkOfferingDao.findByUniqueName(NetworkOffering.DefaultSharedNetworkOfferingWithSGService) == null) {
                    offering = _configMgr.createNetworkOffering(NetworkOffering.DefaultSharedNetworkOfferingWithSGService, "Offering for Shared Security group enabled networks",
                            TrafficType.Guest, null, true, Availability.Optional, null, defaultSharedNetworkOfferingProviders, true, Network.GuestType.Shared, false, null, true,
                            null, true, false, null, false, null, true);
                    offering.setState(NetworkOffering.State.Enabled);
                    _networkOfferingDao.update(offering.getId(), offering);
                }

                //#3 - shared network offering with no SG service
                if (_networkOfferingDao.findByUniqueName(NetworkOffering.DefaultSharedNetworkOffering) == null) {
                    offering = _configMgr.createNetworkOffering(NetworkOffering.DefaultSharedNetworkOffering, "Offering for Shared networks", TrafficType.Guest, null, true,
                            Availability.Optional, null, defaultSharedNetworkOfferingProviders, true, Network.GuestType.Shared, false, null, true, null, true, false, null, false,
                            null, true);
                    offering.setState(NetworkOffering.State.Enabled);
                    _networkOfferingDao.update(offering.getId(), offering);
                }

                //#4 - default isolated offering with Source nat service
                if (_networkOfferingDao.findByUniqueName(NetworkOffering.DefaultIsolatedNetworkOfferingWithSourceNatService) == null) {
                    offering = _configMgr.createNetworkOffering(NetworkOffering.DefaultIsolatedNetworkOfferingWithSourceNatService,
                            "Offering for Isolated networks with Source Nat service enabled", TrafficType.Guest, null, false, Availability.Required, null,
                            defaultIsolatedSourceNatEnabledNetworkOfferingProviders, true, Network.GuestType.Isolated, false, null, true, null, false, false, null, false, null,
                            true);

                    offering.setState(NetworkOffering.State.Enabled);
                    _networkOfferingDao.update(offering.getId(), offering);
                }

                //#5 - default vpc offering with LB service
                if (_networkOfferingDao.findByUniqueName(NetworkOffering.DefaultIsolatedNetworkOfferingForVpcNetworks) == null) {
                    offering = _configMgr.createNetworkOffering(NetworkOffering.DefaultIsolatedNetworkOfferingForVpcNetworks,
                            "Offering for Isolated VPC networks with Source Nat service enabled", TrafficType.Guest, null, false, Availability.Optional, null,
                            defaultVPCOffProviders, true, Network.GuestType.Isolated, false, null, false, null, false, false, null, false, null, true);
                    offering.setState(NetworkOffering.State.Enabled);
                    _networkOfferingDao.update(offering.getId(), offering);
                }

                //#6 - default vpc offering with Dynamic Routing and LB service
                if (_networkOfferingDao.findByUniqueName(NetworkOffering.DefaultIsolatedDynamicRoutedNetworkOfferingForVpcNetworks) == null) {
                    defaultVPCOffProviders.put(Service.VPCDynamicRouting, defaultProviders);
                    offering = _configMgr.createNetworkOffering(NetworkOffering.DefaultIsolatedDynamicRoutedNetworkOfferingForVpcNetworks,
                            "Offering for Isolated VPC networks with Source Nat and Dynamic Routing service enabled", TrafficType.Guest, null, false, Availability.Optional, null,
                            defaultVPCOffProviders, true, Network.GuestType.Isolated, false, null, false, null, false, false, null, false, null, true);
                    offering.setState(NetworkOffering.State.Enabled);
                    _networkOfferingDao.update(offering.getId(), offering);
                }
                //#7 - default vpc offering with no LB service
                if (_networkOfferingDao.findByUniqueName(NetworkOffering.DefaultIsolatedNetworkOfferingForVpcNetworksNoLB) == null) {
                    //remove LB service
                    defaultVPCOffProviders.remove(Service.Lb);
                    offering = _configMgr.createNetworkOffering(NetworkOffering.DefaultIsolatedNetworkOfferingForVpcNetworksNoLB,
                            "Offering for Isolated VPC networks with Source Nat service enabled and LB service disabled", TrafficType.Guest, null, false, Availability.Optional,
                            null, defaultVPCOffProviders, true, Network.GuestType.Isolated, false, null, false, null, false, false, null, false, null, true);
                    offering.setState(NetworkOffering.State.Enabled);
                    _networkOfferingDao.update(offering.getId(), offering);
                }

                //#8 - isolated offering with source nat disabled
                if (_networkOfferingDao.findByUniqueName(NetworkOffering.DefaultIsolatedNetworkOffering) == null) {
                    offering = _configMgr.createNetworkOffering(NetworkOffering.DefaultIsolatedNetworkOffering, "Offering for Isolated networks with no Source Nat service",
                            TrafficType.Guest, null, true, Availability.Optional, null, defaultIsolatedNetworkOfferingProviders, true, Network.GuestType.Isolated, false, null,
                            true, null, true, false, null, false, null, true);
                    offering.setState(NetworkOffering.State.Enabled);
                    _networkOfferingDao.update(offering.getId(), offering);
                }

                //#8 - network offering with internal lb service
                final Map<Network.Service, Set<Network.Provider>> internalLbOffProviders = new HashMap<Network.Service, Set<Network.Provider>>();
                final Set<Network.Provider> defaultVpcProvider = new HashSet<Network.Provider>();
                defaultVpcProvider.add(Network.Provider.VPCVirtualRouter);

                final Set<Network.Provider> defaultInternalLbProvider = new HashSet<Network.Provider>();
                defaultInternalLbProvider.add(Network.Provider.InternalLbVm);

                internalLbOffProviders.put(Service.Dhcp, defaultVpcProvider);
                internalLbOffProviders.put(Service.Dns, defaultVpcProvider);
                internalLbOffProviders.put(Service.UserData, defaultVpcProvider);
                internalLbOffProviders.put(Service.NetworkACL, defaultVpcProvider);
                internalLbOffProviders.put(Service.Gateway, defaultVpcProvider);
                internalLbOffProviders.put(Service.Lb, defaultInternalLbProvider);
                internalLbOffProviders.put(Service.SourceNat, defaultVpcProvider);
                internalLbOffProviders.put(Service.VPCDynamicRouting, defaultProviders);

                if (_networkOfferingDao.findByUniqueName(NetworkOffering.DefaultIsolatedNetworkOfferingForVpcNetworksWithInternalLB) == null) {
                    offering = _configMgr.createNetworkOffering(NetworkOffering.DefaultIsolatedNetworkOfferingForVpcNetworksWithInternalLB,
                            "Offering for Isolated VPC networks with Internal Lb support", TrafficType.Guest, null, false, Availability.Optional, null, internalLbOffProviders,
                            true, Network.GuestType.Isolated, false, null, false, null, false, false, null, false, null, true);
                    offering.setState(NetworkOffering.State.Enabled);
                    offering.setInternalLb(true);
                    offering.setPublicLb(false);
                    _networkOfferingDao.update(offering.getId(), offering);
                }

                final Map<Network.Service, Set<Network.Provider>> netscalerServiceProviders = new HashMap<Network.Service, Set<Network.Provider>>();
                final Set<Network.Provider> vrProvider = new HashSet<Network.Provider>();
                vrProvider.add(Provider.VirtualRouter);
                final Set<Network.Provider> sgProvider = new HashSet<Network.Provider>();
                sgProvider.add(Provider.SecurityGroupProvider);
                final Set<Network.Provider> nsProvider = new HashSet<Network.Provider>();
                nsProvider.add(Provider.Netscaler);
                netscalerServiceProviders.put(Service.Dhcp, vrProvider);
                netscalerServiceProviders.put(Service.Dns, vrProvider);
                netscalerServiceProviders.put(Service.UserData, vrProvider);
                netscalerServiceProviders.put(Service.SecurityGroup, sgProvider);
                netscalerServiceProviders.put(Service.StaticNat, nsProvider);
                netscalerServiceProviders.put(Service.Lb, nsProvider);

                final Map<Service, Map<Capability, String>> serviceCapabilityMap = new HashMap<Service, Map<Capability, String>>();
                final Map<Capability, String> elb = new HashMap<Capability, String>();
                elb.put(Capability.ElasticLb, "true");
                final Map<Capability, String> eip = new HashMap<Capability, String>();
                eip.put(Capability.ElasticIp, "true");
                serviceCapabilityMap.put(Service.Lb, elb);
                serviceCapabilityMap.put(Service.StaticNat, eip);

                if (_networkOfferingDao.findByUniqueName(NetworkOffering.DefaultSharedEIPandELBNetworkOffering) == null) {
                    offering = _configMgr.createNetworkOffering(NetworkOffering.DefaultSharedEIPandELBNetworkOffering,
                            "Offering for Shared networks with Elastic IP and Elastic LB capabilities", TrafficType.Guest, null, true, Availability.Optional, null,
                            netscalerServiceProviders, true, Network.GuestType.Shared, false, null, true, serviceCapabilityMap, true, false, null, false, null, true);
                    offering.setState(NetworkOffering.State.Enabled);
                    offering.setDedicatedLB(false);
                    _networkOfferingDao.update(offering.getId(), offering);
                }
            }
        });

        AssignIpAddressSearch = _ipAddressDao.createSearchBuilder();
        AssignIpAddressSearch.and("dc", AssignIpAddressSearch.entity().getDataCenterId(), Op.EQ);
        AssignIpAddressSearch.and("allocated", AssignIpAddressSearch.entity().getAllocatedTime(), Op.NULL);
        AssignIpAddressSearch.and("vlanId", AssignIpAddressSearch.entity().getVlanId(), Op.IN);
        final SearchBuilder<VlanVO> vlanSearch = _vlanDao.createSearchBuilder();
        vlanSearch.and("type", vlanSearch.entity().getVlanType(), Op.EQ);
        vlanSearch.and("networkId", vlanSearch.entity().getNetworkId(), Op.EQ);
        AssignIpAddressSearch.join("vlan", vlanSearch, vlanSearch.entity().getId(), AssignIpAddressSearch.entity().getVlanId(), JoinType.INNER);
        AssignIpAddressSearch.done();

        AssignIpAddressFromPodVlanSearch = _ipAddressDao.createSearchBuilder();
        AssignIpAddressFromPodVlanSearch.and("dc", AssignIpAddressFromPodVlanSearch.entity().getDataCenterId(), Op.EQ);
        AssignIpAddressFromPodVlanSearch.and("allocated", AssignIpAddressFromPodVlanSearch.entity().getAllocatedTime(), Op.NULL);
        AssignIpAddressFromPodVlanSearch.and("vlanId", AssignIpAddressFromPodVlanSearch.entity().getVlanId(), Op.IN);

        final SearchBuilder<VlanVO> podVlanSearch = _vlanDao.createSearchBuilder();
        podVlanSearch.and("type", podVlanSearch.entity().getVlanType(), Op.EQ);
        podVlanSearch.and("networkId", podVlanSearch.entity().getNetworkId(), Op.EQ);
        final SearchBuilder<PodVlanMapVO> podVlanMapSB = _podVlanMapDao.createSearchBuilder();
        podVlanMapSB.and("podId", podVlanMapSB.entity().getPodId(), Op.EQ);
        AssignIpAddressFromPodVlanSearch.join("podVlanMapSB", podVlanMapSB, podVlanMapSB.entity().getVlanDbId(), AssignIpAddressFromPodVlanSearch.entity().getVlanId(),
                JoinType.INNER);
        AssignIpAddressFromPodVlanSearch.join("vlan", podVlanSearch, podVlanSearch.entity().getId(), AssignIpAddressFromPodVlanSearch.entity().getVlanId(), JoinType.INNER);

        AssignIpAddressFromPodVlanSearch.done();

        _executor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("Network-Scavenger"));

        _agentMgr.registerForHostEvents(this, true, false, true);

        Network.State.getStateMachine().registerListener(new NetworkStateListener(_configDao));

        s_logger.info("Network Manager is configured.");

        return true;
    }

    @Override
    public boolean start() {
        final int netGcInterval = NumbersUtil.parseInt(_configDao.getValue(NetworkGcInterval.key()), 60);
        s_logger.info("Network Manager will run the NetworkGarbageCollector every '" + netGcInterval + "' seconds.");

        _executor.scheduleWithFixedDelay(new NetworkGarbageCollector(), netGcInterval, netGcInterval, TimeUnit.SECONDS);
        return true;
    }

    @Override
    public boolean stop() {
        return true;
    }

    protected NetworkOrchestrator() {
        setStateMachine();
    }

    @Override
    public List<? extends Network> setupNetwork(final Account owner, final NetworkOffering offering, final DeploymentPlan plan, final String name, final String displayText,
            final boolean isDefault) throws ConcurrentOperationException {
        return setupNetwork(owner, offering, null, plan, name, displayText, false, null, null, null, null, true);
    }

    @Override
    @DB
    public List<? extends Network> setupNetwork(final Account owner, final NetworkOffering offering, final Network predefined, final DeploymentPlan plan, final String name,
            final String displayText, final boolean errorIfAlreadySetup, final Long domainId, final ACLType aclType, final Boolean subdomainAccess, final Long vpcId,
            final Boolean isDisplayNetworkEnabled) throws ConcurrentOperationException {

        final Account locked = _accountDao.acquireInLockTable(owner.getId());
        if (locked == null) {
            throw new ConcurrentOperationException("Unable to acquire lock on " + owner);
        }

        try {
            if (predefined == null || offering.getTrafficType() != TrafficType.Guest && predefined.getCidr() == null && predefined.getBroadcastUri() == null
                    && !(predefined.getBroadcastDomainType() == BroadcastDomainType.Vlan || predefined.getBroadcastDomainType() == BroadcastDomainType.Lswitch
                            || predefined.getBroadcastDomainType() == BroadcastDomainType.Vxlan)) {
                final List<NetworkVO> configs = _networksDao.listBy(owner.getId(), offering.getId(), plan.getDataCenterId());

                if (configs.size() > 0) {
                    if (s_logger.isDebugEnabled()) {
                        s_logger.debug("Found existing network configuration for offering " + offering + ": " + configs.get(0));
                    }

                    if (errorIfAlreadySetup) {
                        final InvalidParameterValueException ex = new InvalidParameterValueException(
                                "Found existing network configuration (with specified id) for offering (with specified id)");
                        ex.addProxyObject(offering.getUuid(), "offeringId");
                        ex.addProxyObject(configs.get(0).getUuid(), "networkConfigId");
                        throw ex;
                    } else {
                        return configs;
                    }
                }
            }

            final List<NetworkVO> networks = new ArrayList<NetworkVO>();

            long related = -1;

            for (final NetworkGuru guru : networkGurus) {
                final Network network = guru.design(offering, plan, predefined, owner);
                if (network == null) {
                    continue;
                }

                if (network.getId() != -1) {
                    if (network instanceof NetworkVO) {
                        networks.add((NetworkVO)network);
                    } else {
                        networks.add(_networksDao.findById(network.getId()));
                    }
                    continue;
                }

                final long id = _networksDao.getNextInSequence(Long.class, "id");
                if (related == -1) {
                    related = id;
                }

                final long relatedFile = related;
                Transaction.execute(new TransactionCallbackNoReturn() {
                    @Override
                    public void doInTransactionWithoutResult(final TransactionStatus status) {
                        final NetworkVO vo = new NetworkVO(id, network, offering.getId(), guru.getName(), owner.getDomainId(), owner.getId(), relatedFile, name, displayText,
                                predefined.getNetworkDomain(), offering.getGuestType(), plan.getDataCenterId(), plan.getPhysicalNetworkId(), aclType, offering.getSpecifyIpRanges(),

                                vpcId, offering.getRedundantRouter());
                        vo.setDisplayNetwork(isDisplayNetworkEnabled == null ? true : isDisplayNetworkEnabled);
                        vo.setStrechedL2Network(offering.getSupportsStrechedL2());
                        final NetworkVO networkPersisted = _networksDao.persist(vo, vo.getGuestType() == Network.GuestType.Isolated,
                                finalizeServicesAndProvidersForNetwork(offering, plan.getPhysicalNetworkId()));
                        networks.add(networkPersisted);

                        if (predefined instanceof NetworkVO && guru instanceof NetworkGuruAdditionalFunctions) {
                            final NetworkGuruAdditionalFunctions functions = (NetworkGuruAdditionalFunctions)guru;
                            functions.finalizeNetworkDesign(networkPersisted.getId(), ((NetworkVO)predefined).getVlanIdAsUUID());
                        }

                        if (domainId != null && aclType == ACLType.Domain) {
                            _networksDao.addDomainToNetwork(id, domainId, subdomainAccess == null ? true : subdomainAccess);
                        }
                    }
                });
            }

            if (networks.size() < 1) {
                // see networkOfferingVO.java
                final CloudRuntimeException ex = new CloudRuntimeException("Unable to convert network offering with specified id to network profile");
                ex.addProxyObject(offering.getUuid(), "offeringId");
                throw ex;
            }

            return networks;
        } finally {
            s_logger.debug("Releasing lock for " + locked);
            _accountDao.releaseFromLockTable(locked.getId());
        }
    }

    @Override
    @DB
    public void allocate(final VirtualMachineProfile vm, final LinkedHashMap<? extends Network, List<? extends NicProfile>> networks)
            throws InsufficientCapacityException, ConcurrentOperationException {

        Transaction.execute(new TransactionCallbackWithExceptionNoReturn<InsufficientCapacityException>() {
            @Override
            public void doInTransactionWithoutResult(final TransactionStatus status) throws InsufficientCapacityException {
                int deviceId = 0;
                int size = 0;
                for (final Network ntwk : networks.keySet()) {
                    final List<? extends NicProfile> profiles = networks.get(ntwk);
                    if (profiles != null && !profiles.isEmpty()) {
                        size = size + profiles.size();
                    } else {
                        size = size + 1;
                    }
                }

                final boolean[] deviceIds = new boolean[size];
                Arrays.fill(deviceIds, false);

                final List<NicProfile> nics = new ArrayList<NicProfile>(size);
                NicProfile defaultNic = null;

                for (final Map.Entry<? extends Network, List<? extends NicProfile>> network : networks.entrySet()) {
                    final Network config = network.getKey();
                    List<? extends NicProfile> requestedProfiles = network.getValue();
                    if (requestedProfiles == null) {
                        requestedProfiles = new ArrayList<NicProfile>();
                    }
                    if (requestedProfiles.isEmpty()) {
                        requestedProfiles.add(null);
                    }

                    for (final NicProfile requested : requestedProfiles) {
                        Boolean isDefaultNic = false;
                        if (vm != null && requested != null && requested.isDefaultNic()) {
                            isDefaultNic = true;
                        }

                        while (deviceIds[deviceId] && deviceId < deviceIds.length) {
                            deviceId++;
                        }

                        final Pair<NicProfile, Integer> vmNicPair = allocateNic(requested, config, isDefaultNic, deviceId, vm);
                        NicProfile vmNic = null;
                        if (vmNicPair != null) {
                            vmNic = vmNicPair.first();
                            if (vmNic == null) {
                                continue;
                            }
                            deviceId = vmNicPair.second();
                        }

                        final int devId = vmNic.getDeviceId();
                        if (devId > deviceIds.length) {
                            throw new IllegalArgumentException("Device id for nic is too large: " + vmNic);
                        }
                        if (deviceIds[devId]) {
                            throw new IllegalArgumentException("Conflicting device id for two different nics: " + vmNic);
                        }

                        deviceIds[devId] = true;

                        if (vmNic.isDefaultNic()) {
                            if (defaultNic != null) {
                                throw new IllegalArgumentException("You cannot specify two nics as default nics: nic 1 = " + defaultNic + "; nic 2 = " + vmNic);
                            }
                            defaultNic = vmNic;
                        }

                        nics.add(vmNic);
                        vm.addNic(vmNic);
                    }
                }
                if (nics.size() != size) {
                    s_logger.warn("Number of nics " + nics.size() + " doesn't match number of requested nics " + size);
                    throw new CloudRuntimeException("Number of nics " + nics.size() + " doesn't match number of requested networks " + size);
                }

                if (nics.size() == 1) {
                    nics.get(0).setDefaultNic(true);
                }
            }
        });
    }

    @DB
    @Override
    public Pair<NicProfile, Integer> allocateNic(final NicProfile requested, final Network network, final Boolean isDefaultNic, int deviceId, final VirtualMachineProfile vm)
            throws InsufficientVirtualNetworkCapacityException, InsufficientAddressCapacityException, ConcurrentOperationException {

        final NetworkVO ntwkVO = _networksDao.findById(network.getId());
        s_logger.debug("Allocating nic for vm " + vm.getVirtualMachine() + " in network " + network + " with requested profile " + requested);
        final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, ntwkVO.getGuruName());

        if (requested != null && requested.getMode() == null) {
            requested.setMode(network.getMode());
        }
        final NicProfile profile = guru.allocate(network, requested, vm);
        if (profile == null) {
            return null;
        }

        if (isDefaultNic != null) {
            profile.setDefaultNic(isDefaultNic);
        }

        if (requested != null && requested.getMode() == null) {
            profile.setMode(requested.getMode());
        } else {
            profile.setMode(network.getMode());
        }

        NicVO vo = new NicVO(guru.getName(), vm.getId(), network.getId(), vm.getType());

        deviceId = applyProfileToNic(vo, profile, deviceId);

        vo = _nicDao.persist(vo);

        final Integer networkRate = _networkModel.getNetworkRate(network.getId(), vm.getId());
        final NicProfile vmNic = new NicProfile(vo, network, vo.getBroadcastUri(), vo.getIsolationUri(), networkRate, _networkModel.isSecurityGroupSupportedInNetwork(network),
                _networkModel.getNetworkTag(vm.getHypervisorType(), network));

        return new Pair<NicProfile, Integer>(vmNic, Integer.valueOf(deviceId));
    }

    protected Integer applyProfileToNic(final NicVO vo, final NicProfile profile, Integer deviceId) {
        if (profile.getDeviceId() != null) {
            vo.setDeviceId(profile.getDeviceId());
        } else if (deviceId != null) {
            vo.setDeviceId(deviceId++);
        }

        if (profile.getReservationStrategy() != null) {
            vo.setReservationStrategy(profile.getReservationStrategy());
        }

        vo.setDefaultNic(profile.isDefaultNic());

        vo.setIPv4Address(profile.getIPv4Address());
        vo.setAddressFormat(profile.getFormat());

        if (profile.getMacAddress() != null) {
            vo.setMacAddress(profile.getMacAddress());
        }

        vo.setMode(profile.getMode());
        vo.setIPv4Netmask(profile.getIPv4Netmask());
        vo.setIPv4Gateway(profile.getIPv4Gateway());

        if (profile.getBroadCastUri() != null) {
            vo.setBroadcastUri(profile.getBroadCastUri());
        }

        if (profile.getIsolationUri() != null) {
            vo.setIsolationUri(profile.getIsolationUri());
        }

        vo.setState(Nic.State.Allocated);

        vo.setIPv6Address(profile.getIPv6Address());
        vo.setIPv6Gateway(profile.getIPv6Gateway());
        vo.setIPv6Cidr(profile.getIPv6Cidr());

        return deviceId;
    }

    protected void applyProfileToNicForRelease(final NicVO vo, final NicProfile profile) {
        vo.setIPv4Gateway(profile.getIPv4Gateway());
        vo.setAddressFormat(profile.getFormat());
        vo.setIPv4Address(profile.getIPv4Address());
        vo.setIPv6Address(profile.getIPv6Address());
        vo.setMacAddress(profile.getMacAddress());
        if (profile.getReservationStrategy() != null) {
            vo.setReservationStrategy(profile.getReservationStrategy());
        }
        vo.setBroadcastUri(profile.getBroadCastUri());
        vo.setIsolationUri(profile.getIsolationUri());
        vo.setIPv4Netmask(profile.getIPv4Netmask());
    }

    protected void applyProfileToNetwork(final NetworkVO network, final NetworkProfile profile) {
        network.setBroadcastUri(profile.getBroadcastUri());
        network.setDns1(profile.getDns1());
        network.setDns2(profile.getDns2());
        network.setPhysicalNetworkId(profile.getPhysicalNetworkId());
    }

    protected NicTO toNicTO(final NicVO nic, final NicProfile profile, final NetworkVO config) {
        final NicTO to = new NicTO();
        to.setDeviceId(nic.getDeviceId());
        to.setBroadcastType(config.getBroadcastDomainType());
        to.setType(config.getTrafficType());
        to.setIp(nic.getIPv4Address());
        to.setNetmask(nic.getIPv4Netmask());
        to.setMac(nic.getMacAddress());
        to.setDns1(profile.getIPv4Dns1());
        to.setDns2(profile.getIPv4Dns2());
        if (nic.getIPv4Gateway() != null) {
            to.setGateway(nic.getIPv4Gateway());
        } else {
            to.setGateway(config.getGateway());
        }
        if (nic.getVmType() != VirtualMachine.Type.User) {
            to.setPxeDisable(true);
        }
        to.setDefaultNic(nic.isDefaultNic());
        to.setBroadcastUri(nic.getBroadcastUri());
        to.setIsolationuri(nic.getIsolationUri());
        if (profile != null) {
            to.setDns1(profile.getIPv4Dns1());
            to.setDns2(profile.getIPv4Dns2());
        }

        final Integer networkRate = _networkModel.getNetworkRate(config.getId(), null);
        to.setNetworkRateMbps(networkRate);

        to.setUuid(config.getUuid());

        return to;
    }

    boolean isNetworkImplemented(final NetworkVO network) {
        final Network.State state = network.getState();
        if (state == Network.State.Implemented) {
            return true;
        } else if (state == Network.State.Setup) {
            final DataCenterVO zone = _dcDao.findById(network.getDataCenterId());
            if (!isSharedNetworkOfferingWithServices(network.getNetworkOfferingId()) || zone.getNetworkType() == NetworkType.Basic) {
                return true;
            }
        }
        return false;
    }

    Pair<NetworkGuru, NetworkVO> implementNetwork(final long networkId, final DeployDestination dest, final ReservationContext context, final boolean isRouter)
            throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {

        Pair<NetworkGuru, NetworkVO> implemented = null;
        if (!isRouter) {
            implemented = implementNetwork(networkId, dest, context);
        } else {
            // At the time of implementing network (using implementNetwork() method), if the VR needs to be deployed then
            // it follows the same path of regular VM deployment. This leads to a nested call to implementNetwork() while
            // preparing VR nics. This flow creates issues in dealing with network state transitions. The original call
            // puts network in "Implementing" state and then the nested call again tries to put it into same state resulting
            // in issues. In order to avoid it, implementNetwork() call for VR is replaced with below code.
            final NetworkVO network = _networksDao.findById(networkId);
            final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, network.getGuruName());
            implemented = new Pair<NetworkGuru, NetworkVO>(guru, network);
        }
        return implemented;
    }

    @Override
    @DB
    public Pair<NetworkGuru, NetworkVO> implementNetwork(final long networkId, final DeployDestination dest, final ReservationContext context)
            throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {
        final Pair<NetworkGuru, NetworkVO> implemented = new Pair<NetworkGuru, NetworkVO>(null, null);

        NetworkVO network = _networksDao.findById(networkId);
        final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, network.getGuruName());
        if (isNetworkImplemented(network)) {
            s_logger.debug("Network id=" + networkId + " is already implemented");
            implemented.set(guru, network);
            return implemented;
        }

        // Acquire lock only when network needs to be implemented
        network = _networksDao.acquireInLockTable(networkId, NetworkLockTimeout.value());
        if (network == null) {
            // see NetworkVO.java
            final ConcurrentOperationException ex = new ConcurrentOperationException("Unable to acquire network configuration");
            ex.addProxyObject(_entityMgr.findById(Network.class, networkId).getUuid());
            throw ex;
        }

        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Lock is acquired for network id " + networkId + " as a part of network implement");
        }

        try {
            if (isNetworkImplemented(network)) {
                s_logger.debug("Network id=" + networkId + " is already implemented");
                implemented.set(guru, network);
                return implemented;
            }

            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Asking " + guru.getName() + " to implement " + network);
            }

            final NetworkOfferingVO offering = _networkOfferingDao.findById(network.getNetworkOfferingId());

            network.setReservationId(context.getReservationId());
            if (isSharedNetworkWithServices(network)) {
                network.setState(Network.State.Implementing);
            } else {
                stateTransitTo(network, Event.ImplementNetwork);
            }

            final Network result = guru.implement(network, offering, dest, context);
            network.setCidr(result.getCidr());
            network.setBroadcastUri(result.getBroadcastUri());
            network.setGateway(result.getGateway());
            network.setMode(result.getMode());
            network.setPhysicalNetworkId(result.getPhysicalNetworkId());
            _networksDao.update(networkId, network);

            // implement network elements and re-apply all the network rules
            implementNetworkElementsAndResources(dest, context, network, offering);

            if (isSharedNetworkWithServices(network)) {
                network.setState(Network.State.Implemented);
            } else {
                stateTransitTo(network, Event.OperationSucceeded);
            }

            network.setRestartRequired(false);
            _networksDao.update(network.getId(), network);
            implemented.set(guru, network);
            return implemented;
        } catch (final NoTransitionException e) {
            s_logger.error(e.getMessage());
            return null;
        } catch (final CloudRuntimeException e) {
            s_logger.error("Caught exception: " + e.getMessage());
            return null;
        } finally {
            if (implemented.first() == null) {
                s_logger.debug("Cleaning up because we're unable to implement the network " + network);
                try {
                    if (isSharedNetworkWithServices(network)) {
                        network.setState(Network.State.Shutdown);
                        _networksDao.update(networkId, network);
                    } else {
                        stateTransitTo(network, Event.OperationFailed);
                    }
                } catch (final NoTransitionException e) {
                    s_logger.error(e.getMessage());
                }

                try {
                    shutdownNetwork(networkId, context, false);
                } catch (final Exception e) {
                    // Don't throw this exception as it would hide the original thrown exception, just log
                    s_logger.error("Exception caught while shutting down a network as part of a failed implementation", e);
                }
            }

            _networksDao.releaseFromLockTable(networkId);
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Lock is released for network id " + networkId + " as a part of network implement");
            }
        }
    }

    @Override
    public void implementNetworkElementsAndResources(final DeployDestination dest, final ReservationContext context, final Network network, final NetworkOffering offering)
            throws ConcurrentOperationException, InsufficientAddressCapacityException, ResourceUnavailableException, InsufficientCapacityException {

        // Associate a source NAT IP (if one isn't already associated with the network) if this is a
        //     1) 'Isolated' or 'Shared' guest virtual network in the advance zone
        //     2) network has sourceNat service
        //     3) network offering does not support a shared source NAT rule

        final boolean sharedSourceNat = offering.getSharedSourceNat();
        final DataCenter zone = _dcDao.findById(network.getDataCenterId());

        if (!sharedSourceNat && _networkModel.areServicesSupportedInNetwork(network.getId(), Service.SourceNat)
                && (network.getGuestType() == Network.GuestType.Isolated || network.getGuestType() == Network.GuestType.Shared && zone.getNetworkType() == NetworkType.Advanced)) {

            List<IPAddressVO> ips = null;
            final Account owner = _entityMgr.findById(Account.class, network.getAccountId());
            if (network.getVpcId() != null) {
                ips = _ipAddressDao.listByAssociatedVpc(network.getVpcId(), true);
                if (ips.isEmpty()) {
                    final Vpc vpc = _vpcMgr.getActiveVpc(network.getVpcId());
                    s_logger.debug("Creating a source nat ip for vpc " + vpc);
                    _vpcMgr.assignSourceNatIpAddressToVpc(owner, vpc);
                }
            } else {
                ips = _ipAddressDao.listByAssociatedNetwork(network.getId(), true);
                if (ips.isEmpty()) {
                    s_logger.debug("Creating a source nat ip for network " + network);
                    _ipAddrMgr.assignSourceNatIpAddressToGuestNetwork(owner, network);
                }
            }
        }
        // get providers to implement
        final List<Provider> providersToImplement = getNetworkProviders(network.getId());
        for (final NetworkElement element : networkElements) {
            if (providersToImplement.contains(element.getProvider())) {
                if (!_networkModel.isProviderEnabledInPhysicalNetwork(_networkModel.getPhysicalNetworkId(network), element.getProvider().getName())) {
                    // The physicalNetworkId will not get translated into a uuid by the reponse serializer,
                    // because the serializer would look up the NetworkVO class's table and retrieve the
                    // network id instead of the physical network id.
                    // So just throw this exception as is. We may need to TBD by changing the serializer.
                    throw new CloudRuntimeException("Service provider " + element.getProvider().getName() + " either doesn't exist or is not enabled in physical network id: "
                            + network.getPhysicalNetworkId());
                }

                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Asking " + element.getName() + " to implemenet " + network);
                }

                if (!element.implement(network, offering, dest, context)) {
                    final CloudRuntimeException ex = new CloudRuntimeException(
                            "Failed to implement provider " + element.getProvider().getName() + " for network with specified id");
                    ex.addProxyObject(network.getUuid(), "networkId");
                    throw ex;
                }
            }
        }

        for (final NetworkElement element : networkElements) {
            if (element instanceof AggregatedCommandExecutor && providersToImplement.contains(element.getProvider())) {
                ((AggregatedCommandExecutor)element).prepareAggregatedExecution(network, dest);
            }
        }

        try {
            // reapply all the firewall/staticNat/lb rules
            s_logger.debug("Reprogramming network " + network + " as a part of network implement");
            if (!reprogramNetworkRules(network.getId(), CallContext.current().getCallingAccount(), network)) {
                s_logger.warn("Failed to re-program the network as a part of network " + network + " implement");
                // see DataCenterVO.java
                final ResourceUnavailableException ex = new ResourceUnavailableException("Unable to apply network rules as a part of network " + network + " implement",
                        DataCenter.class, network.getDataCenterId());
                ex.addProxyObject(_entityMgr.findById(DataCenter.class, network.getDataCenterId()).getUuid());
                throw ex;
            }
            for (final NetworkElement element : networkElements) {
                if (element instanceof AggregatedCommandExecutor && providersToImplement.contains(element.getProvider())) {
                    if (!((AggregatedCommandExecutor)element).completeAggregatedExecution(network, dest)) {
                        s_logger.warn("Failed to re-program the network as a part of network " + network + " implement due to aggregated commands execution failure!");
                        // see DataCenterVO.java
                        final ResourceUnavailableException ex = new ResourceUnavailableException("Unable to apply network rules as a part of network " + network + " implement",
                                DataCenter.class, network.getDataCenterId());

                        ex.addProxyObject(_entityMgr.findById(DataCenter.class, network.getDataCenterId()).getUuid());
                        throw ex;
                    }
                }
            }
        } finally {
            for (final NetworkElement element : networkElements) {
                if (element instanceof AggregatedCommandExecutor && providersToImplement.contains(element.getProvider())) {
                    ((AggregatedCommandExecutor)element).cleanupAggregatedExecution(network, dest);
                }
            }
        }
    }

    // This method re-programs the rules/ips for existing network
    protected boolean reprogramNetworkRules(final long networkId, final Account caller, final Network network) throws ResourceUnavailableException {
        boolean success = true;

        //Apply egress rules first to effect the egress policy early on the guest traffic
        final List<FirewallRuleVO> firewallEgressRulesToApply = _firewallDao.listByNetworkPurposeTrafficType(networkId, Purpose.Firewall, FirewallRule.TrafficType.Egress);
        final NetworkOfferingVO offering = _networkOfferingDao.findById(network.getNetworkOfferingId());
        final DataCenter zone = _dcDao.findById(network.getDataCenterId());
        if (_networkModel.areServicesSupportedInNetwork(network.getId(), Service.Firewall) && _networkModel.areServicesSupportedInNetwork(network.getId(), Service.Firewall)
                && (network.getGuestType() == Network.GuestType.Isolated || network.getGuestType() == Network.GuestType.Shared && zone.getNetworkType() == NetworkType.Advanced)) {

            // add default egress rule to accept the traffic
            _firewallMgr.applyDefaultEgressFirewallRule(network.getId(), offering.getEgressDefaultPolicy(), true);
        }
        if (!_firewallMgr.applyFirewallRules(firewallEgressRulesToApply, false, caller)) {
            s_logger.warn("Failed to reapply firewall Egress rule(s) as a part of network id=" + networkId + " restart");
            success = false;
        }

        // associate all ip addresses
        if (!_ipAddrMgr.applyIpAssociations(network, false)) {
            s_logger.warn("Failed to apply ip addresses as a part of network id" + networkId + " restart");
            success = false;
        }

        // apply static nat
        if (!_rulesMgr.applyStaticNatsForNetwork(networkId, false, caller)) {
            s_logger.warn("Failed to apply static nats a part of network id" + networkId + " restart");
            success = false;
        }

        // apply firewall rules
        final List<FirewallRuleVO> firewallIngressRulesToApply = _firewallDao.listByNetworkPurposeTrafficType(networkId, Purpose.Firewall, FirewallRule.TrafficType.Ingress);
        if (!_firewallMgr.applyFirewallRules(firewallIngressRulesToApply, false, caller)) {
            s_logger.warn("Failed to reapply Ingress firewall rule(s) as a part of network id=" + networkId + " restart");
            success = false;
        }

        // apply port forwarding rules
        if (!_rulesMgr.applyPortForwardingRulesForNetwork(networkId, false, caller)) {
            s_logger.warn("Failed to reapply port forwarding rule(s) as a part of network id=" + networkId + " restart");
            success = false;
        }

        // apply static nat rules
        if (!_rulesMgr.applyStaticNatRulesForNetwork(networkId, false, caller)) {
            s_logger.warn("Failed to reapply static nat rule(s) as a part of network id=" + networkId + " restart");
            success = false;
        }

        // apply public load balancer rules
        if (!_lbMgr.applyLoadBalancersForNetwork(networkId, Scheme.Public)) {
            s_logger.warn("Failed to reapply Public load balancer rules as a part of network id=" + networkId + " restart");
            success = false;
        }

        // apply internal load balancer rules
        if (!_lbMgr.applyLoadBalancersForNetwork(networkId, Scheme.Internal)) {
            s_logger.warn("Failed to reapply internal load balancer rules as a part of network id=" + networkId + " restart");
            success = false;
        }

        // apply vpn rules
        final List<? extends RemoteAccessVpn> vpnsToReapply = _vpnMgr.listRemoteAccessVpns(networkId);
        if (vpnsToReapply != null) {
            for (final RemoteAccessVpn vpn : vpnsToReapply) {
                // Start remote access vpn per ip
                if (_vpnMgr.startRemoteAccessVpn(vpn.getServerAddressId(), false) == null) {
                    s_logger.warn("Failed to reapply vpn rules as a part of network id=" + networkId + " restart");
                    success = false;
                }
            }
        }

        //apply network ACLs
        if (!_networkACLMgr.applyACLToNetwork(networkId)) {
            s_logger.warn("Failed to reapply network ACLs as a part of  of network id=" + networkId + " restart");
            success = false;
        }

        return success;
    }

    protected boolean prepareElement(final NetworkElement element, final Network network, final NicProfile profile, final VirtualMachineProfile vmProfile,
            final DeployDestination dest, final ReservationContext context) throws InsufficientCapacityException, ConcurrentOperationException, ResourceUnavailableException {
        element.prepare(network, profile, vmProfile, dest, context);
        if (vmProfile.getType() == Type.User && element.getProvider() != null) {
            if (_networkModel.areServicesSupportedInNetwork(network.getId(), Service.Dhcp)
                    && _networkModel.isProviderSupportServiceInNetwork(network.getId(), Service.Dhcp, element.getProvider()) && element instanceof DhcpServiceProvider) {
                final DhcpServiceProvider sp = (DhcpServiceProvider)element;
                final Map<Capability, String> dhcpCapabilities = element.getCapabilities().get(Service.Dhcp);
                final String supportsMultipleSubnets = dhcpCapabilities.get(Capability.DhcpAccrossMultipleSubnets);
                if (supportsMultipleSubnets != null && Boolean.valueOf(supportsMultipleSubnets) && profile.getIPv6Address() == null) {
                    if (!sp.configDhcpSupportForSubnet(network, profile, vmProfile, dest, context)) {
                        return false;
                    }
                }
                if (!sp.addDhcpEntry(network, profile, vmProfile, dest, context)) {
                    return false;
                }
            }
            if (_networkModel.areServicesSupportedInNetwork(network.getId(), Service.UserData)
                    && _networkModel.isProviderSupportServiceInNetwork(network.getId(), Service.UserData, element.getProvider()) && element instanceof UserDataServiceProvider) {
                final UserDataServiceProvider sp = (UserDataServiceProvider)element;
                if (!sp.addPasswordAndUserdata(network, profile, vmProfile, dest, context)) {

                    return false;
                }
            }
        }
        return true;
    }

    @DB
    protected void updateNic(final NicVO nic, final long networkId, final int count) {
        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(final TransactionStatus status) {
                _nicDao.update(nic.getId(), nic);

                if (nic.getVmType() == VirtualMachine.Type.User) {
                    s_logger.debug("Changing active number of nics for network id=" + networkId + " on " + count);
                    _networksDao.changeActiveNicsBy(networkId, count);
                }

                if (nic.getVmType() == VirtualMachine.Type.User
                        || nic.getVmType() == VirtualMachine.Type.DomainRouter && _networksDao.findById(networkId).getTrafficType() == TrafficType.Guest) {
                    _networksDao.setCheckForGc(networkId);
                }
            }
        });
    }

    @Override
    public void prepare(final VirtualMachineProfile vmProfile, final DeployDestination dest, final ReservationContext context)
            throws InsufficientCapacityException, ConcurrentOperationException, ResourceUnavailableException {
        final List<NicVO> nics = _nicDao.listByVmId(vmProfile.getId());

        // we have to implement default nics first - to ensure that default network elements start up first in multiple
        //nics case
        // (need for setting DNS on Dhcp to domR's Ip4 address)
        Collections.sort(nics, new Comparator<NicVO>() {

            @Override
            public int compare(final NicVO nic1, final NicVO nic2) {
                final boolean isDefault1 = nic1.isDefaultNic();
                final boolean isDefault2 = nic2.isDefaultNic();

                return isDefault1 ^ isDefault2 ? isDefault1 ^ true ? 1 : -1 : 0;
            }
        });

        for (final NicVO nic : nics) {
            final Pair<NetworkGuru, NetworkVO> implemented = implementNetwork(nic.getNetworkId(), dest, context, vmProfile.getVirtualMachine().getType() == Type.DomainRouter);
            if (implemented == null || implemented.first() == null) {
                s_logger.warn("Failed to implement network id=" + nic.getNetworkId() + " as a part of preparing nic id=" + nic.getId());
                throw new CloudRuntimeException("Failed to implement network id=" + nic.getNetworkId() + " as a part preparing nic id=" + nic.getId());
            }

            final NetworkVO network = implemented.second();
            final NicProfile profile = prepareNic(vmProfile, dest, context, nic.getId(), network);
            vmProfile.addNic(profile);
        }
    }

    @Override
    public NicProfile prepareNic(final VirtualMachineProfile vmProfile, final DeployDestination dest, final ReservationContext context, final long nicId, final Network network)
            throws InsufficientVirtualNetworkCapacityException, InsufficientAddressCapacityException, ConcurrentOperationException, InsufficientCapacityException,
            ResourceUnavailableException {

        final Integer networkRate = _networkModel.getNetworkRate(network.getId(), vmProfile.getId());
        final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, network.getGuruName());
        final NicVO nic = _nicDao.findById(nicId);

        NicProfile profile = null;
        if (nic.getReservationStrategy() == Nic.ReservationStrategy.Start) {
            nic.setState(Nic.State.Reserving);
            nic.setReservationId(context.getReservationId());
            _nicDao.update(nic.getId(), nic);
            URI broadcastUri = nic.getBroadcastUri();
            if (broadcastUri == null) {
                broadcastUri = network.getBroadcastUri();
            }

            final URI isolationUri = nic.getIsolationUri();

            profile = new NicProfile(nic, network, broadcastUri, isolationUri,

                    networkRate, _networkModel.isSecurityGroupSupportedInNetwork(network), _networkModel.getNetworkTag(vmProfile.getHypervisorType(), network));
            guru.reserve(profile, network, vmProfile, dest, context);
            nic.setIPv4Address(profile.getIPv4Address());
            nic.setAddressFormat(profile.getFormat());
            nic.setIPv6Address(profile.getIPv6Address());
            nic.setMacAddress(profile.getMacAddress());
            nic.setIsolationUri(profile.getIsolationUri());
            nic.setBroadcastUri(profile.getBroadCastUri());
            nic.setReserver(guru.getName());
            nic.setState(Nic.State.Reserved);
            nic.setIPv4Netmask(profile.getIPv4Netmask());
            nic.setIPv4Gateway(profile.getIPv4Gateway());

            if (profile.getReservationStrategy() != null) {
                nic.setReservationStrategy(profile.getReservationStrategy());
            }

            updateNic(nic, network.getId(), 1);
        } else {
            profile = new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), networkRate, _networkModel.isSecurityGroupSupportedInNetwork(network),
                    _networkModel.getNetworkTag(vmProfile.getHypervisorType(), network));
            guru.updateNicProfile(profile, network);
            nic.setState(Nic.State.Reserved);
            updateNic(nic, network.getId(), 1);
        }

        final List<Provider> providersToImplement = getNetworkProviders(network.getId());
        for (final NetworkElement element : networkElements) {
            if (providersToImplement.contains(element.getProvider())) {
                if (!_networkModel.isProviderEnabledInPhysicalNetwork(_networkModel.getPhysicalNetworkId(network), element.getProvider().getName())) {
                    throw new CloudRuntimeException("Service provider " + element.getProvider().getName() + " either doesn't exist or is not enabled in physical network id: "
                            + network.getPhysicalNetworkId());
                }
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Asking " + element.getName() + " to prepare for " + nic);
                }
                if (!prepareElement(element, network, profile, vmProfile, dest, context)) {
                    throw new InsufficientAddressCapacityException("unable to configure the dhcp service, due to insufficiant address capacity", Network.class, network.getId());
                }
            }
        }

        profile.setSecurityGroupEnabled(_networkModel.isSecurityGroupSupportedInNetwork(network));
        guru.updateNicProfile(profile, network);
        return profile;
    }

    @Override
    public void prepareNicForMigration(final VirtualMachineProfile vm, final DeployDestination dest) {
        if (vm.getType().equals(VirtualMachine.Type.DomainRouter) && (vm.getHypervisorType().equals(HypervisorType.KVM) || vm.getHypervisorType().equals(HypervisorType.VMware))) {

            //Include nics hot plugged and not stored in DB
            prepareAllNicsForMigration(vm, dest);
            return;
        }
        final List<NicVO> nics = _nicDao.listByVmId(vm.getId());
        final ReservationContext context = new ReservationContextImpl(UUID.randomUUID().toString(), null, null);
        for (final NicVO nic : nics) {
            final NetworkVO network = _networksDao.findById(nic.getNetworkId());
            final Integer networkRate = _networkModel.getNetworkRate(network.getId(), vm.getId());

            final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, network.getGuruName());
            final NicProfile profile = new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), networkRate,
                    _networkModel.isSecurityGroupSupportedInNetwork(network), _networkModel.getNetworkTag(vm.getHypervisorType(), network));
            if (guru instanceof NetworkMigrationResponder) {
                if (!((NetworkMigrationResponder)guru).prepareMigration(profile, network, vm, dest, context)) {
                    s_logger.error("NetworkGuru " + guru + " prepareForMigration failed."); // XXX: Transaction error
                }
            }
            final List<Provider> providersToImplement = getNetworkProviders(network.getId());
            for (final NetworkElement element : networkElements) {
                if (providersToImplement.contains(element.getProvider())) {
                    if (!_networkModel.isProviderEnabledInPhysicalNetwork(_networkModel.getPhysicalNetworkId(network), element.getProvider().getName())) {
                        throw new CloudRuntimeException("Service provider " + element.getProvider().getName() + " either doesn't exist or is not enabled in physical network id: "
                                + network.getPhysicalNetworkId());
                    }
                    if (element instanceof NetworkMigrationResponder) {
                        if (!((NetworkMigrationResponder)element).prepareMigration(profile, network, vm, dest, context)) {
                            s_logger.error("NetworkElement " + element + " prepareForMigration failed."); // XXX: Transaction error
                        }
                    }
                }
            }
            guru.updateNicProfile(profile, network);
            vm.addNic(profile);
        }
    }

    /*
    Prepare All Nics for migration including the nics dynamically created and not stored in DB
    This is a temporary workaround work KVM migration
    Once clean fix is added by stored dynamically nics is DB, this workaround won't be needed
     */
    @Override
    public void prepareAllNicsForMigration(final VirtualMachineProfile vm, final DeployDestination dest) {
        final List<NicVO> nics = _nicDao.listByVmId(vm.getId());
        final ReservationContext context = new ReservationContextImpl(UUID.randomUUID().toString(), null, null);
        Long guestNetworkId = null;
        for (final NicVO nic : nics) {
            final NetworkVO network = _networksDao.findById(nic.getNetworkId());
            if (network.getTrafficType().equals(TrafficType.Guest) && network.getGuestType().equals(GuestType.Isolated)) {

                guestNetworkId = network.getId();
            }
            final Integer networkRate = _networkModel.getNetworkRate(network.getId(), vm.getId());

            final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, network.getGuruName());
            final NicProfile profile = new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), networkRate,
                    _networkModel.isSecurityGroupSupportedInNetwork(network), _networkModel.getNetworkTag(vm.getHypervisorType(), network));
            if (guru instanceof NetworkMigrationResponder) {
                if (!((NetworkMigrationResponder)guru).prepareMigration(profile, network, vm, dest, context)) {
                    s_logger.error("NetworkGuru " + guru + " prepareForMigration failed."); // XXX: Transaction error

                }
            }
            final List<Provider> providersToImplement = getNetworkProviders(network.getId());
            for (final NetworkElement element : networkElements) {
                if (providersToImplement.contains(element.getProvider())) {
                    if (!_networkModel.isProviderEnabledInPhysicalNetwork(_networkModel.getPhysicalNetworkId(network), element.getProvider().getName())) {
                        throw new CloudRuntimeException("Service provider " + element.getProvider().getName() + " either doesn't exist or is not enabled in physical network id: "
                                + network.getPhysicalNetworkId());
                    }
                    if (element instanceof NetworkMigrationResponder) {
                        if (!((NetworkMigrationResponder)element).prepareMigration(profile, network, vm, dest, context)) {
                            s_logger.error("NetworkElement " + element + " prepareForMigration failed."); // XXX: Transaction error
                        }
                    }
                }
            }
            guru.updateNicProfile(profile, network);
            vm.addNic(profile);
        }

        final List<String> addedURIs = new ArrayList<String>();
        if (guestNetworkId != null) {
            final List<IPAddressVO> publicIps = _ipAddressDao.listByAssociatedNetwork(guestNetworkId, null);
            for (final IPAddressVO userIp : publicIps) {
                final PublicIp publicIp = PublicIp.createFromAddrAndVlan(userIp, _vlanDao.findById(userIp.getVlanId()));
                final URI broadcastUri = BroadcastDomainType.Vlan.toUri(publicIp.getVlanTag());
                final long ntwkId = publicIp.getNetworkId();
                final Nic nic = _nicDao.findByNetworkIdInstanceIdAndBroadcastUri(ntwkId, vm.getId(), broadcastUri.toString());
                if (nic == null && !addedURIs.contains(broadcastUri.toString())) {
                    //Nic details are not available in DB
                    //Create nic profile for migration
                    s_logger.debug("Creating nic profile for migration. BroadcastUri: " + broadcastUri.toString() + " NetworkId: " + ntwkId + " Vm: " + vm.getId());
                    final NetworkVO network = _networksDao.findById(ntwkId);
                    _networkModel.getNetworkRate(network.getId(), vm.getId());
                    final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, network.getGuruName());
                    final NicProfile profile = new NicProfile();

                    profile.setDeviceId(255); //dummyId
                    profile.setIPv4Address(userIp.getAddress().toString());
                    profile.setIPv4Netmask(publicIp.getNetmask());
                    profile.setIPv4Gateway(publicIp.getGateway());
                    profile.setMacAddress(publicIp.getMacAddress());
                    profile.setBroadcastType(network.getBroadcastDomainType());
                    profile.setTrafficType(network.getTrafficType());
                    profile.setBroadcastUri(broadcastUri);
                    profile.setIsolationUri(Networks.IsolationType.Vlan.toUri(publicIp.getVlanTag()));
                    profile.setSecurityGroupEnabled(_networkModel.isSecurityGroupSupportedInNetwork(network));
                    profile.setName(_networkModel.getNetworkTag(vm.getHypervisorType(), network));
                    profile.setNetworId(network.getId());

                    guru.updateNicProfile(profile, network);
                    vm.addNic(profile);
                    addedURIs.add(broadcastUri.toString());
                }
            }
        }
    }

    private NicProfile findNicProfileById(final VirtualMachineProfile vm, final long id) {
        for (final NicProfile nic : vm.getNics()) {
            if (nic.getId() == id) {
                return nic;
            }
        }
        return null;
    }

    @Override
    public void commitNicForMigration(final VirtualMachineProfile src, final VirtualMachineProfile dst) {
        for (final NicProfile nicSrc : src.getNics()) {
            final NetworkVO network = _networksDao.findById(nicSrc.getNetworkId());
            final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, network.getGuruName());
            final NicProfile nicDst = findNicProfileById(dst, nicSrc.getId());
            final ReservationContext src_context = new ReservationContextImpl(nicSrc.getReservationId(), null, null);
            final ReservationContext dst_context = new ReservationContextImpl(nicDst.getReservationId(), null, null);

            if (guru instanceof NetworkMigrationResponder) {
                ((NetworkMigrationResponder)guru).commitMigration(nicSrc, network, src, src_context, dst_context);
            }
            final List<Provider> providersToImplement = getNetworkProviders(network.getId());
            for (final NetworkElement element : networkElements) {
                if (providersToImplement.contains(element.getProvider())) {
                    if (!_networkModel.isProviderEnabledInPhysicalNetwork(_networkModel.getPhysicalNetworkId(network), element.getProvider().getName())) {
                        throw new CloudRuntimeException("Service provider " + element.getProvider().getName() + " either doesn't exist or is not enabled in physical network id: "
                                + network.getPhysicalNetworkId());
                    }
                    if (element instanceof NetworkMigrationResponder) {
                        ((NetworkMigrationResponder)element).commitMigration(nicSrc, network, src, src_context, dst_context);
                    }
                }
            }
            // update the reservation id
            final NicVO nicVo = _nicDao.findById(nicDst.getId());
            nicVo.setReservationId(nicDst.getReservationId());
            _nicDao.persist(nicVo);
        }
    }

    @Override
    public void rollbackNicForMigration(final VirtualMachineProfile src, final VirtualMachineProfile dst) {
        for (final NicProfile nicDst : dst.getNics()) {
            final NetworkVO network = _networksDao.findById(nicDst.getNetworkId());
            final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, network.getGuruName());
            final NicProfile nicSrc = findNicProfileById(src, nicDst.getId());
            final ReservationContext src_context = new ReservationContextImpl(nicSrc.getReservationId(), null, null);
            final ReservationContext dst_context = new ReservationContextImpl(nicDst.getReservationId(), null, null);

            if (guru instanceof NetworkMigrationResponder) {
                ((NetworkMigrationResponder)guru).rollbackMigration(nicDst, network, dst, src_context, dst_context);
            }
            final List<Provider> providersToImplement = getNetworkProviders(network.getId());
            for (final NetworkElement element : networkElements) {
                if (providersToImplement.contains(element.getProvider())) {
                    if (!_networkModel.isProviderEnabledInPhysicalNetwork(_networkModel.getPhysicalNetworkId(network), element.getProvider().getName())) {
                        throw new CloudRuntimeException("Service provider " + element.getProvider().getName() + " either doesn't exist or is not enabled in physical network id: "
                                + network.getPhysicalNetworkId());
                    }
                    if (element instanceof NetworkMigrationResponder) {
                        ((NetworkMigrationResponder)element).rollbackMigration(nicDst, network, dst, src_context, dst_context);
                    }
                }
            }
        }
    }

    @Override
    @DB
    public void release(final VirtualMachineProfile vmProfile, final boolean forced) throws ConcurrentOperationException, ResourceUnavailableException {
        final List<NicVO> nics = _nicDao.listByVmId(vmProfile.getId());
        for (final NicVO nic : nics) {
            releaseNic(vmProfile, nic.getId());
        }
    }

    @Override
    @DB
    public void releaseNic(final VirtualMachineProfile vmProfile, final Nic nic) throws ConcurrentOperationException, ResourceUnavailableException {
        releaseNic(vmProfile, nic.getId());
    }

    @DB
    protected void releaseNic(final VirtualMachineProfile vmProfile, final long nicId) throws ConcurrentOperationException, ResourceUnavailableException {
        final Pair<Network, NicProfile> networkToRelease = Transaction.execute(new TransactionCallback<Pair<Network, NicProfile>>() {
            @Override
            public Pair<Network, NicProfile> doInTransaction(final TransactionStatus status) {
                final NicVO nic = _nicDao.lockRow(nicId, true);
                if (nic == null) {
                    throw new ConcurrentOperationException("Unable to acquire lock on nic " + nic);
                }

                final Nic.State originalState = nic.getState();
                final NetworkVO network = _networksDao.findById(nic.getNetworkId());
                if (originalState == Nic.State.Reserved || originalState == Nic.State.Reserving) {
                    if (nic.getReservationStrategy() == Nic.ReservationStrategy.Start) {
                        final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, network.getGuruName());
                        nic.setState(Nic.State.Releasing);
                        _nicDao.update(nic.getId(), nic);
                        final NicProfile profile = new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), null,
                                _networkModel.isSecurityGroupSupportedInNetwork(network), _networkModel.getNetworkTag(vmProfile.getHypervisorType(), network));

                        if (guru.release(profile, vmProfile, nic.getReservationId())) {
                            applyProfileToNicForRelease(nic, profile);
                            nic.setState(Nic.State.Allocated);
                            if (originalState == Nic.State.Reserved) {
                                updateNic(nic, network.getId(), -1);
                            } else {
                                _nicDao.update(nic.getId(), nic);
                            }
                        }
                        // Perform release on network elements
                        return new Pair<Network, NicProfile>(network, profile);
                    } else {
                        nic.setState(Nic.State.Allocated);
                        updateNic(nic, network.getId(), -1);
                    }
                }

                return null;
            }
        });

        // cleanup the entry in vm_network_map
        if (vmProfile.getType().equals(VirtualMachine.Type.User)) {
            final NicVO nic = _nicDao.findById(nicId);
            if (nic != null) {
                final NetworkVO vmNetwork = _networksDao.findById(nic.getNetworkId());
                final VMNetworkMapVO vno = _vmNetworkMapDao.findByVmAndNetworkId(vmProfile.getVirtualMachine().getId(), vmNetwork.getId());
                if (vno != null) {
                    _vmNetworkMapDao.remove(vno.getId());
                }
            }
        }

        if (networkToRelease != null) {
            final Network network = networkToRelease.first();
            final NicProfile profile = networkToRelease.second();
            final List<Provider> providersToImplement = getNetworkProviders(network.getId());
            for (final NetworkElement element : networkElements) {
                if (providersToImplement.contains(element.getProvider())) {
                    if (s_logger.isDebugEnabled()) {
                        s_logger.debug("Asking " + element.getName() + " to release " + profile);
                    }
                    //NOTE: Context appear to never be used in release method
                    //implementations. Consider removing it from interface Element
                    element.release(network, profile, vmProfile, null);
                }
            }
        }
    }

    @Override
    public void cleanupNics(final VirtualMachineProfile vm) {
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Cleaning network for vm: " + vm.getId());
        }

        final List<NicVO> nics = _nicDao.listByVmId(vm.getId());
        for (final NicVO nic : nics) {
            removeNic(vm, nic);
        }
    }

    @Override
    public void removeNic(final VirtualMachineProfile vm, final Nic nic) {
        removeNic(vm, _nicDao.findById(nic.getId()));
    }

    protected void removeNic(final VirtualMachineProfile vm, final NicVO nic) {

        if (nic.getReservationStrategy() == Nic.ReservationStrategy.Start && nic.getState() != Nic.State.Allocated) {
            // Nics with reservation strategy 'Start' should go through release phase in the Nic life cycle.
            // Ensure that release is performed before Nic is to be removed to avoid resource leaks.
            try {
                releaseNic(vm, nic.getId());
            } catch (final Exception ex) {
                s_logger.warn("Failed to release nic: " + nic.toString() + " as part of remove operation due to", ex);
            }
        }

        nic.setState(Nic.State.Deallocating);
        _nicDao.update(nic.getId(), nic);
        final NetworkVO network = _networksDao.findById(nic.getNetworkId());
        final NicProfile profile = new NicProfile(nic, network, null, null, null, _networkModel.isSecurityGroupSupportedInNetwork(network),
                _networkModel.getNetworkTag(vm.getHypervisorType(), network));

        /*
         * We need to release the nics with a Create ReservationStrategy here
         * because the nic is now being removed.
         */
        if (nic.getReservationStrategy() == Nic.ReservationStrategy.Create) {
            final List<Provider> providersToImplement = getNetworkProviders(network.getId());
            for (final NetworkElement element : networkElements) {
                if (providersToImplement.contains(element.getProvider())) {
                    if (s_logger.isDebugEnabled()) {
                        s_logger.debug("Asking " + element.getName() + " to release " + nic);
                    }
                    try {
                        element.release(network, profile, vm, null);
                    } catch (final ConcurrentOperationException ex) {
                        s_logger.warn("release failed during the nic " + nic.toString() + " removeNic due to ", ex);
                    } catch (final ResourceUnavailableException ex) {
                        s_logger.warn("release failed during the nic " + nic.toString() + " removeNic due to ", ex);
                    }
                }
            }
        }

        if (vm.getType() == Type.User && _networkModel.areServicesSupportedInNetwork(network.getId(), Service.Dhcp) && network.getTrafficType() == TrafficType.Guest
                && network.getGuestType() == GuestType.Shared && isLastNicInSubnet(nic)) {
            // remove the dhcpservice ip if this is the last nic in subnet.
            final DhcpServiceProvider dhcpServiceProvider = getDhcpServiceProvider(network);
            if (dhcpServiceProvider != null && isDhcpAccrossMultipleSubnetsSupported(dhcpServiceProvider)) {

                removeDhcpServiceInSubnet(nic);
            }
        }

        final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, network.getGuruName());
        guru.deallocate(network, profile, vm);
        _nicDao.remove(nic.getId());

        s_logger.debug("Removed nic id=" + nic.getId());
        //remove the secondary ip addresses corresponding to to this nic
        if (!removeVmSecondaryIpsOfNic(nic.getId())) {
            s_logger.debug("Removing nic " + nic.getId() + " secondary ip addreses failed");
        }
    }

    public boolean isDhcpAccrossMultipleSubnetsSupported(final DhcpServiceProvider dhcpServiceProvider) {

        final Map<Network.Capability, String> capabilities = dhcpServiceProvider.getCapabilities().get(Network.Service.Dhcp);
        final String supportsMultipleSubnets = capabilities.get(Network.Capability.DhcpAccrossMultipleSubnets);
        if (supportsMultipleSubnets != null && Boolean.valueOf(supportsMultipleSubnets)) {
            return true;
        }
        return false;
    }

    private boolean isLastNicInSubnet(final NicVO nic) {
        if (_nicDao.listByNetworkIdTypeAndGatewayAndBroadcastUri(nic.getNetworkId(), VirtualMachine.Type.User, nic.getIPv4Gateway(), nic.getBroadcastUri()).size() > 1) {
            return false;
        }
        return true;
    }

    @DB
    @Override
    public void removeDhcpServiceInSubnet(final Nic nic) {
        final Network network = _networksDao.findById(nic.getNetworkId());
        final DhcpServiceProvider dhcpServiceProvider = getDhcpServiceProvider(network);
        try {
            final NicIpAliasVO ipAlias = _nicIpAliasDao.findByGatewayAndNetworkIdAndState(nic.getIPv4Gateway(), network.getId(), NicIpAlias.State.active);
            if (ipAlias != null) {
                ipAlias.setState(NicIpAlias.State.revoked);
                Transaction.execute(new TransactionCallbackNoReturn() {
                    @Override
                    public void doInTransactionWithoutResult(final TransactionStatus status) {
                        _nicIpAliasDao.update(ipAlias.getId(), ipAlias);
                        final IPAddressVO aliasIpaddressVo = _publicIpAddressDao.findByIpAndSourceNetworkId(ipAlias.getNetworkId(), ipAlias.getIp4Address());
                        _publicIpAddressDao.unassignIpAddress(aliasIpaddressVo.getId());
                    }
                });
                if (!dhcpServiceProvider.removeDhcpSupportForSubnet(network)) {
                    s_logger.warn("Failed to remove the ip alias on the router, marking it as removed in db and freed the allocated ip " + ipAlias.getIp4Address());
                }
            }
        } catch (final ResourceUnavailableException e) {
            //failed to remove the dhcpconfig on the router.
            s_logger.info("Unable to delete the ip alias due to unable to contact the virtualrouter.");
        }

    }

    @Override
    public void expungeNics(final VirtualMachineProfile vm) {
        final List<NicVO> nics = _nicDao.listByVmIdIncludingRemoved(vm.getId());
        for (final NicVO nic : nics) {
            _nicDao.expunge(nic.getId());
        }
    }

    @Override
    @DB
    public Network createGuestNetwork(final long networkOfferingId, final String name, final String displayText, final String gateway, final String cidr, String vlanId,
            String networkDomain, final Account owner, final Long domainId, final PhysicalNetwork pNtwk, final long zoneId, final ACLType aclType, Boolean subdomainAccess,
            final Long vpcId, final String ip6Gateway, final String ip6Cidr, final Boolean isDisplayNetworkEnabled, final String isolatedPvlan)
                    throws ConcurrentOperationException, InsufficientCapacityException, ResourceAllocationException {

        final NetworkOfferingVO ntwkOff = _networkOfferingDao.findById(networkOfferingId);
        // this method supports only guest network creation
        if (ntwkOff.getTrafficType() != TrafficType.Guest) {
            s_logger.warn("Only guest networks can be created using this method");
            return null;
        }

        final boolean updateResourceCount = resourceCountNeedsUpdate(ntwkOff, aclType);
        //check resource limits
        if (updateResourceCount) {
            _resourceLimitMgr.checkResourceLimit(owner, ResourceType.network, isDisplayNetworkEnabled);
        }

        // Validate network offering
        if (ntwkOff.getState() != NetworkOffering.State.Enabled) {
            // see NetworkOfferingVO
            final InvalidParameterValueException ex = new InvalidParameterValueException(
                    "Can't use specified network offering id as its stat is not " + NetworkOffering.State.Enabled);
            ex.addProxyObject(ntwkOff.getUuid(), "networkOfferingId");
            throw ex;
        }

        // Validate physical network
        if (pNtwk.getState() != PhysicalNetwork.State.Enabled) {
            // see PhysicalNetworkVO.java
            final InvalidParameterValueException ex = new InvalidParameterValueException("Specified physical network id is" + " in incorrect state:" + pNtwk.getState());
            ex.addProxyObject(pNtwk.getUuid(), "physicalNetworkId");
            throw ex;
        }

        boolean ipv6 = false;

        if (ip6Gateway != null && ip6Cidr != null) {
            ipv6 = true;
        }
        // Validate zone
        final DataCenterVO zone = _dcDao.findById(zoneId);
        if (zone.getNetworkType() == NetworkType.Basic) {
            if (ipv6) {
                throw new InvalidParameterValueException("IPv6 is not supported in Basic zone");
            }

            // In Basic zone the network should have aclType=Domain, domainId=1, subdomainAccess=true
            if (aclType == null || aclType != ACLType.Domain) {
                throw new InvalidParameterValueException("Only AclType=Domain can be specified for network creation in Basic zone");
            }

            // Only one guest network is supported in Basic zone
            final List<NetworkVO> guestNetworks = _networksDao.listByZoneAndTrafficType(zone.getId(), TrafficType.Guest);
            if (!guestNetworks.isEmpty()) {
                throw new InvalidParameterValueException("Can't have more than one Guest network in zone with network type " + NetworkType.Basic);
            }

            // if zone is basic, only Shared network offerings w/o source nat service are allowed
            if (!(ntwkOff.getGuestType() == GuestType.Shared && !_networkModel.areServicesSupportedByNetworkOffering(ntwkOff.getId(), Service.SourceNat))) {
                throw new InvalidParameterValueException("For zone of type " + NetworkType.Basic + " only offerings of " + "guestType " + GuestType.Shared + " with disabled "
                        + Service.SourceNat.getName() + " service are allowed");
            }

            if (domainId == null || domainId != Domain.ROOT_DOMAIN) {
                throw new InvalidParameterValueException("Guest network in Basic zone should be dedicated to ROOT domain");
            }

            if (subdomainAccess == null) {
                subdomainAccess = true;
            } else if (!subdomainAccess) {
                throw new InvalidParameterValueException("Subdomain access should be set to true for the" + " guest network in the Basic zone");
            }

            if (vlanId == null) {
                vlanId = Vlan.UNTAGGED;
            } else {
                if (!vlanId.equalsIgnoreCase(Vlan.UNTAGGED)) {
                    throw new InvalidParameterValueException("Only vlan " + Vlan.UNTAGGED + " can be created in " + "the zone of type " + NetworkType.Basic);
                }
            }

        } else if (zone.getNetworkType() == NetworkType.Advanced) {
            if (zone.isSecurityGroupEnabled()) {
                if (ipv6) {
                    throw new InvalidParameterValueException("IPv6 is not supported with security group!");
                }
                if (isolatedPvlan != null) {
                    throw new InvalidParameterValueException("Isolated Private VLAN is not supported with security group!");
                }
                // Only Account specific Isolated network with sourceNat service disabled are allowed in security group
                // enabled zone
                if (ntwkOff.getGuestType() != GuestType.Shared) {
                    throw new InvalidParameterValueException("Only shared guest network can be created in security group enabled zone");
                }
                if (_networkModel.areServicesSupportedByNetworkOffering(ntwkOff.getId(), Service.SourceNat)) {
                    throw new InvalidParameterValueException("Service SourceNat is not allowed in security group enabled zone");
                }
                if (!_networkModel.areServicesSupportedByNetworkOffering(ntwkOff.getId(), Service.SecurityGroup)) {
                    throw new InvalidParameterValueException("network must have SecurityGroup provider in security group enabled zone");
                }
            }

            //don't allow eip/elb networks in Advance zone
            if (ntwkOff.getElasticIp() || ntwkOff.getElasticLb()) {
                throw new InvalidParameterValueException("Elastic IP and Elastic LB services are supported in zone of type " + NetworkType.Basic);
            }
        }

        //TODO(VXLAN): Support VNI specified
        // VlanId can be specified only when network offering supports it
        final boolean vlanSpecified = vlanId != null;
        if (vlanSpecified != ntwkOff.getSpecifyVlan()) {
            if (vlanSpecified) {
                throw new InvalidParameterValueException("Can't specify vlan; corresponding offering says specifyVlan=false");
            } else {
                throw new InvalidParameterValueException("Vlan has to be specified; corresponding offering says specifyVlan=true");
            }
        }

        if (vlanSpecified) {
            //don't allow to specify vlan tag used by physical network for dynamic vlan allocation
            if (_dcDao.findVnet(zoneId, pNtwk.getId(), vlanId).size() > 0) {
                throw new InvalidParameterValueException(
                        "The VLAN tag " + vlanId + " is already being used for dynamic vlan allocation for the guest network in zone " + zone.getName());
            }
            if (!UuidUtils.validateUUID(vlanId)) {
                final String uri = BroadcastDomainType.fromString(vlanId).toString();
                // For Isolated networks, don't allow to create network with vlan that already exists in the zone
                if (ntwkOff.getGuestType() == GuestType.Isolated) {
                    if (_networksDao.countByZoneAndUri(zoneId, uri) > 0) {
                        throw new InvalidParameterValueException("Network with vlan " + vlanId + " already exists in zone " + zoneId);
                    } else {
                        final List<DataCenterVnetVO> dcVnets = _datacenterVnetDao.findVnet(zoneId, vlanId.toString());
                        //for the network that is created as part of private gateway,
                        //the vnet is not coming from the data center vnet table, so the list can be empty
                        if (!dcVnets.isEmpty()) {
                            final DataCenterVnetVO dcVnet = dcVnets.get(0);
                            // Fail network creation if specified vlan is dedicated to a different account
                            if (dcVnet.getAccountGuestVlanMapId() != null) {
                                final Long accountGuestVlanMapId = dcVnet.getAccountGuestVlanMapId();
                                final AccountGuestVlanMapVO map = _accountGuestVlanMapDao.findById(accountGuestVlanMapId);
                                if (map.getAccountId() != owner.getAccountId()) {
                                    throw new InvalidParameterValueException("Vlan " + vlanId + " is dedicated to a different account");
                                }
                                // Fail network creation if owner has a dedicated range of vlans but the specified vlan belongs to the system pool
                            } else {
                                final List<AccountGuestVlanMapVO> maps = _accountGuestVlanMapDao.listAccountGuestVlanMapsByAccount(owner.getAccountId());
                                if (maps != null && !maps.isEmpty()) {
                                    final int vnetsAllocatedToAccount = _datacenterVnetDao.countVnetsAllocatedToAccount(zoneId, owner.getAccountId());
                                    final int vnetsDedicatedToAccount = _datacenterVnetDao.countVnetsDedicatedToAccount(zoneId, owner.getAccountId());
                                    if (vnetsAllocatedToAccount < vnetsDedicatedToAccount) {
                                        throw new InvalidParameterValueException(
                                                "Specified vlan " + vlanId + " doesn't belong" + " to the vlan range dedicated to the owner " + owner.getAccountName());
                                    }

                                }
                            }
                        }
                    }
                } else {
                    // don't allow to creating shared network with given Vlan ID, if there already exists a isolated network or
                    // shared network with same Vlan ID in the zone
                    if (_networksDao.countByZoneUriAndGuestType(zoneId, uri, GuestType.Isolated) > 0) {
                        throw new InvalidParameterValueException("There is a isolated/shared network with vlan id: " + vlanId + " already exists " + "in zone " + zoneId);
                    }

                }
            }

        }

        // If networkDomain is not specified, take it from the global configuration
        if (_networkModel.areServicesSupportedByNetworkOffering(networkOfferingId, Service.Dns)) {
            final Map<Network.Capability, String> dnsCapabilities = _networkModel
                    .getNetworkOfferingServiceCapabilities(_entityMgr.findById(NetworkOffering.class, networkOfferingId), Service.Dns);
            final String isUpdateDnsSupported = dnsCapabilities.get(Capability.AllowDnsSuffixModification);
            if (isUpdateDnsSupported == null || !Boolean.valueOf(isUpdateDnsSupported)) {
                if (networkDomain != null) {
                    // TBD: NetworkOfferingId and zoneId. Send uuids instead.
                    throw new InvalidParameterValueException("Domain name change is not supported by network offering id=" + networkOfferingId + " in zone id=" + zoneId);
                }
            } else {
                if (networkDomain == null) {
                    // 1) Get networkDomain from the corresponding account/domain/zone
                    if (aclType == ACLType.Domain) {
                        networkDomain = _networkModel.getDomainNetworkDomain(domainId, zoneId);
                    } else if (aclType == ACLType.Account) {
                        networkDomain = _networkModel.getAccountNetworkDomain(owner.getId(), zoneId);
                    }

                    // 2) If null, generate networkDomain using domain suffix from the global config variables
                    if (networkDomain == null) {
                        networkDomain = "cs" + Long.toHexString(owner.getId()) + GuestDomainSuffix.valueIn(zoneId);
                    }

                } else {
                    // validate network domain
                    if (!NetUtils.verifyDomainName(networkDomain)) {
                        throw new InvalidParameterValueException("Invalid network domain. Total length shouldn't exceed 190 chars. Each domain "
                                + "label must be between 1 and 63 characters long, can contain ASCII letters 'a' through 'z', the digits '0' through '9', "
                                + "and the hyphen ('-'); can't start or end with \"-\"");
                    }
                }
            }
        }

        // In Advance zone Cidr for Shared networks and Isolated networks w/o source nat service can't be NULL - 2.2.x
        // limitation, remove after we introduce support for multiple ip ranges
        // with different Cidrs for the same Shared network
        final boolean cidrRequired = zone.getNetworkType() == NetworkType.Advanced && ntwkOff.getTrafficType() == TrafficType.Guest && (ntwkOff.getGuestType() == GuestType.Shared
                || ntwkOff.getGuestType() == GuestType.Isolated && !_networkModel.areServicesSupportedByNetworkOffering(ntwkOff.getId(), Service.SourceNat));

        if (cidr == null && ip6Cidr == null && cidrRequired) {
            throw new InvalidParameterValueException("StartIp/endIp/gateway/netmask are required when create network of" + " type " + Network.GuestType.Shared
                    + " and network of type " + GuestType.Isolated + " with service " + Service.SourceNat.getName() + " disabled");
        }

        // No cidr can be specified in Basic zone
        if (zone.getNetworkType() == NetworkType.Basic && cidr != null) {
            throw new InvalidParameterValueException("StartIp/endIp/gateway/netmask can't be specified for zone of type " + NetworkType.Basic);
        }

        // Check if cidr is RFC1918 compliant if the network is Guest Isolated for IPv4
        if (cidr != null && ntwkOff.getGuestType() == Network.GuestType.Isolated && ntwkOff.getTrafficType() == TrafficType.Guest) {
            //check if the vpc supports dynamic routing, this network may not participate in dynamic routing but still the cidr has to be ospf compliant
            final Vpc vpc = _vpcMgr.getActiveVpc(vpcId);
            if (_vpcOffServiceDao.areServicesSupportedByNetworkOffering(vpc.getVpcOfferingId(), Service.VPCDynamicRouting)) {
                _dcDao.loadDetails(zone);
                try {
                    final CIDR[] superCidr = _vpcMgr.getZoneSuperCidrList(zoneId);
                    final CIDR ncidr = CIDRFactory.getCIDR(cidr);
                    if (!NetUtils.validateGuestCidrForOSPF(ncidr, superCidr)) {
                        throw new InvalidParameterValueException("Virtual Guest Cidr " + cidr + " is not compliant for this dynamically routed network.");
                    }
                }
                catch (BadCIDRException ex){
                    throw new InvalidParameterValueException("Virtual Guest Cidr " + cidr + " is not compliant for this dynamically routed network.");
                }
            }
            else {
                if (!NetUtils.validateGuestCidr(cidr)) {
                    throw new InvalidParameterValueException("Virtual Guest Cidr " + cidr + " is not RFC1918 compliant");
                }
            }
        }

        final String networkDomainFinal = networkDomain;
        final String vlanIdFinal = vlanId;
        final Boolean subdomainAccessFinal = subdomainAccess;
        final Network network = Transaction.execute(new TransactionCallback<Network>() {
            @Override
            public Network doInTransaction(final TransactionStatus status) {
                Long physicalNetworkId = null;
                if (pNtwk != null) {
                    physicalNetworkId = pNtwk.getId();
                }
                final DataCenterDeployment plan = new DataCenterDeployment(zoneId, null, null, null, null, physicalNetworkId);
                final NetworkVO userNetwork = new NetworkVO();
                userNetwork.setNetworkDomain(networkDomainFinal);

                if (cidr != null && gateway != null) {
                    userNetwork.setCidr(cidr);
                    userNetwork.setGateway(gateway);
                }

                if (ip6Cidr != null && ip6Gateway != null) {
                    userNetwork.setIp6Cidr(ip6Cidr);
                    userNetwork.setIp6Gateway(ip6Gateway);
                }

                if (vlanIdFinal != null) {
                    if (isolatedPvlan == null) {
                        URI uri = null;
                        if (UuidUtils.validateUUID(vlanIdFinal)) {
                            //Logical router's UUID provided as VLAN_ID
                            userNetwork.setVlanIdAsUUID(vlanIdFinal); //Set transient field
                        } else {
                            uri = BroadcastDomainType.fromString(vlanIdFinal);
                        }
                        userNetwork.setBroadcastUri(uri);
                        if (!vlanIdFinal.equalsIgnoreCase(Vlan.UNTAGGED)) {
                            userNetwork.setBroadcastDomainType(BroadcastDomainType.Vlan);
                        } else {
                            userNetwork.setBroadcastDomainType(BroadcastDomainType.Native);
                        }
                    } else {
                        if (vlanIdFinal.equalsIgnoreCase(Vlan.UNTAGGED)) {
                            throw new InvalidParameterValueException("Cannot support pvlan with untagged primary vlan!");
                        }
                        userNetwork.setBroadcastUri(NetUtils.generateUriForPvlan(vlanIdFinal, isolatedPvlan));
                        userNetwork.setBroadcastDomainType(BroadcastDomainType.Pvlan);
                    }
                }

                final List<? extends Network> networks = setupNetwork(owner, ntwkOff, userNetwork, plan, name, displayText, true, domainId, aclType, subdomainAccessFinal, vpcId,
                        isDisplayNetworkEnabled);

                Network network = null;
                if (networks == null || networks.isEmpty()) {
                    throw new CloudRuntimeException("Fail to create a network");
                } else {
                    if (networks.size() > 0 && networks.get(0).getGuestType() == Network.GuestType.Isolated && networks.get(0).getTrafficType() == TrafficType.Guest) {
                        Network defaultGuestNetwork = networks.get(0);
                        for (final Network nw : networks) {
                            if (nw.getCidr() != null && nw.getCidr().equals(zone.getGuestNetworkCidr())) {
                                defaultGuestNetwork = nw;
                            }
                        }
                        network = defaultGuestNetwork;
                    } else {
                        // For shared network
                        network = networks.get(0);
                    }
                }

                if (updateResourceCount) {
                    _resourceLimitMgr.incrementResourceCount(owner.getId(), ResourceType.network, isDisplayNetworkEnabled);
                }

                return network;
            }
        });

        CallContext.current().setEventDetails("Network Id: " + network.getId());
        CallContext.current().putContextParameter(Network.class, network.getUuid());
        return network;
    }

    @Override
    @DB
    public boolean shutdownNetwork(final long networkId, final ReservationContext context, final boolean cleanupElements) {
        NetworkVO network = _networksDao.findById(networkId);
        if (network.getState() == Network.State.Allocated) {
            s_logger.debug("Network is already shutdown: " + network);
            return true;
        }

        if (network.getState() != Network.State.Implemented && network.getState() != Network.State.Shutdown) {
            s_logger.debug("Network is not implemented: " + network);
            return false;
        }

        try {
            //do global lock for the network
            network = _networksDao.acquireInLockTable(networkId, NetworkLockTimeout.value());
            if (network == null) {
                s_logger.warn("Unable to acquire lock for the network " + network + " as a part of network shutdown");
                return false;
            }
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Lock is acquired for network " + network + " as a part of network shutdown");
            }

            if (network.getState() == Network.State.Allocated) {
                s_logger.debug("Network is already shutdown: " + network);
                return true;
            }

            if (network.getState() != Network.State.Implemented && network.getState() != Network.State.Shutdown) {
                s_logger.debug("Network is not implemented: " + network);
                return false;
            }

            if (isSharedNetworkWithServices(network)) {
                network.setState(Network.State.Shutdown);
                _networksDao.update(network.getId(), network);
            } else {
                try {
                    stateTransitTo(network, Event.DestroyNetwork);
                } catch (final NoTransitionException e) {
                    network.setState(Network.State.Shutdown);
                    _networksDao.update(network.getId(), network);
                }
            }

            final boolean success = shutdownNetworkElementsAndResources(context, cleanupElements, network);

            final NetworkVO networkFinal = network;
            final boolean result = Transaction.execute(new TransactionCallback<Boolean>() {
                @Override
                public Boolean doInTransaction(final TransactionStatus status) {
                    boolean result = false;

                    if (success) {
                        if (s_logger.isDebugEnabled()) {
                            s_logger.debug("Network id=" + networkId + " is shutdown successfully, cleaning up corresponding resources now.");
                        }
                        final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, networkFinal.getGuruName());
                        final NetworkProfile profile = convertNetworkToNetworkProfile(networkFinal.getId());
                        guru.shutdown(profile, _networkOfferingDao.findById(networkFinal.getNetworkOfferingId()));

                        applyProfileToNetwork(networkFinal, profile);
                        final DataCenterVO zone = _dcDao.findById(networkFinal.getDataCenterId());
                        if (isSharedNetworkOfferingWithServices(networkFinal.getNetworkOfferingId()) && zone.getNetworkType() == NetworkType.Advanced) {
                            networkFinal.setState(Network.State.Setup);
                        } else {
                            try {
                                stateTransitTo(networkFinal, Event.OperationSucceeded);
                            } catch (final NoTransitionException e) {
                                networkFinal.setState(Network.State.Allocated);
                                networkFinal.setRestartRequired(false);
                            }
                        }
                        _networksDao.update(networkFinal.getId(), networkFinal);
                        _networksDao.clearCheckForGc(networkId);
                        result = true;
                    } else {
                        try {
                            stateTransitTo(networkFinal, Event.OperationFailed);
                        } catch (final NoTransitionException e) {
                            networkFinal.setState(Network.State.Implemented);
                            _networksDao.update(networkFinal.getId(), networkFinal);
                        }
                        result = false;
                    }

                    return result;
                }
            });

            return result;
        } finally {
            if (network != null) {
                _networksDao.releaseFromLockTable(network.getId());
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Lock is released for network " + network + " as a part of network shutdown");
                }
            }
        }
    }

    @Override
    public boolean shutdownNetworkElementsAndResources(final ReservationContext context, final boolean cleanupElements, final Network network) {

        // get providers to shutdown
        final List<Provider> providersToShutdown = getNetworkProviders(network.getId());

        // 1) Cleanup all the rules for the network. If it fails, just log the failure and proceed with shutting down
        // the elements
        boolean cleanupResult = true;
        boolean cleanupNeeded = false;
        try {
            for (final Provider provider : providersToShutdown) {
                if (provider.cleanupNeededOnShutdown()) {
                    cleanupNeeded = true;
                    break;
                }
            }
            if (cleanupNeeded) {
                cleanupResult = shutdownNetworkResources(network.getId(), context.getAccount(), context.getCaller().getId());
            }
        } catch (final Exception ex) {
            s_logger.warn("shutdownNetworkRules failed during the network " + network + " shutdown due to ", ex);
        } finally {
            // just warn the administrator that the network elements failed to shutdown
            if (!cleanupResult) {
                s_logger.warn("Failed to cleanup network id=" + network.getId() + " resources as a part of shutdownNetwork");
            }
        }

        // 2) Shutdown all the network elements
        boolean success = true;
        for (final NetworkElement element : networkElements) {
            if (providersToShutdown.contains(element.getProvider())) {
                try {
                    if (s_logger.isDebugEnabled()) {
                        s_logger.debug("Sending network shutdown to " + element.getName());
                    }
                    if (!element.shutdown(network, context, cleanupElements)) {
                        s_logger.warn("Unable to complete shutdown of the network elements due to element: " + element.getName());
                        success = false;
                    }
                } catch (final ResourceUnavailableException e) {
                    s_logger.warn("Unable to complete shutdown of the network elements due to element: " + element.getName(), e);
                    success = false;
                } catch (final ConcurrentOperationException e) {
                    s_logger.warn("Unable to complete shutdown of the network elements due to element: " + element.getName(), e);
                    success = false;
                } catch (final Exception e) {
                    s_logger.warn("Unable to complete shutdown of the network elements due to element: " + element.getName(), e);
                    success = false;
                }
            }
        }
        return success;
    }

    @Override
    @DB
    public boolean destroyNetwork(final long networkId, final ReservationContext context, final boolean forced) {
        final Account callerAccount = context.getAccount();

        NetworkVO network = _networksDao.findById(networkId);
        if (network == null) {
            s_logger.debug("Unable to find network with id: " + networkId);
            return false;
        }

        // Make sure that there are no user vms in the network that are not Expunged/Error
        final List<UserVmVO> userVms = _userVmDao.listByNetworkIdAndStates(networkId);

        for (final UserVmVO vm : userVms) {
            if (!(vm.getState() == VirtualMachine.State.Expunging && vm.getRemoved() != null)) {
                s_logger.warn("Can't delete the network, not all user vms are expunged. Vm " + vm + " is in " + vm.getState() + " state");
                return false;
            }
        }

        // Don't allow to delete network via api call when it has vms assigned to it
        final int nicCount = getActiveNicsInNetwork(networkId);
        if (nicCount > 0) {
            s_logger.debug("The network id=" + networkId + " has active Nics, but shouldn't.");
            // at this point we have already determined that there are no active user vms in network
            // if the op_networks table shows active nics, it's a bug in releasing nics updating op_networks
            _networksDao.changeActiveNicsBy(networkId, -1 * nicCount);
        }

        //In Basic zone, make sure that there are no non-removed console proxies and SSVMs using the network
        final DataCenter zone = _entityMgr.findById(DataCenter.class, network.getDataCenterId());
        if (zone.getNetworkType() == NetworkType.Basic) {
            final List<VMInstanceVO> systemVms = _vmDao.listNonRemovedVmsByTypeAndNetwork(network.getId(), Type.ConsoleProxy, Type.SecondaryStorageVm);
            if (systemVms != null && !systemVms.isEmpty()) {
                s_logger.warn("Can't delete the network, not all consoleProxy/secondaryStorage vms are expunged");
                return false;
            }
        }

        // Shutdown network first
        shutdownNetwork(networkId, context, false);

        // get updated state for the network
        network = _networksDao.findById(networkId);
        if (network.getState() != Network.State.Allocated && network.getState() != Network.State.Setup && !forced) {
            s_logger.debug("Network is not not in the correct state to be destroyed: " + network.getState());
            return false;
        }

        boolean success = true;
        if (!cleanupNetworkResources(networkId, callerAccount, context.getCaller().getId())) {
            s_logger.warn("Unable to delete network id=" + networkId + ": failed to cleanup network resources");
            return false;
        }

        // get providers to destroy
        final List<Provider> providersToDestroy = getNetworkProviders(network.getId());
        for (final NetworkElement element : networkElements) {
            if (providersToDestroy.contains(element.getProvider())) {
                try {
                    if (s_logger.isDebugEnabled()) {
                        s_logger.debug("Sending destroy to " + element);
                    }

                    if (!element.destroy(network, context)) {
                        success = false;
                        s_logger.warn("Unable to complete destroy of the network: failed to destroy network element " + element.getName());
                    }
                } catch (final ResourceUnavailableException e) {
                    s_logger.warn("Unable to complete destroy of the network due to element: " + element.getName(), e);
                    success = false;
                } catch (final ConcurrentOperationException e) {
                    s_logger.warn("Unable to complete destroy of the network due to element: " + element.getName(), e);
                    success = false;
                } catch (final Exception e) {
                    s_logger.warn("Unable to complete destroy of the network due to element: " + element.getName(), e);
                    success = false;
                }
            }
        }

        if (success) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Network id=" + networkId + " is destroyed successfully, cleaning up corresponding resources now.");
            }

            final NetworkVO networkFinal = network;
            try {
                Transaction.execute(new TransactionCallbackNoReturn() {
                    @Override
                    public void doInTransactionWithoutResult(final TransactionStatus status) {
                        final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, networkFinal.getGuruName());

                        guru.trash(networkFinal, _networkOfferingDao.findById(networkFinal.getNetworkOfferingId()));

                        if (!deleteVlansInNetwork(networkFinal.getId(), context.getCaller().getId(), callerAccount)) {
                            s_logger.warn("Failed to delete network " + networkFinal + "; was unable to cleanup corresponding ip ranges");
                            throw new CloudRuntimeException("Failed to delete network " + networkFinal + "; was unable to cleanup corresponding ip ranges");
                        } else {
                            // commit transaction only when ips and vlans for the network are released successfully
                            try {
                                stateTransitTo(networkFinal, Event.DestroyNetwork);
                            } catch (final NoTransitionException e) {
                                s_logger.debug(e.getMessage());
                            }
                            if (_networksDao.remove(networkFinal.getId())) {
                                final NetworkDomainVO networkDomain = _networkDomainDao.getDomainNetworkMapByNetworkId(networkFinal.getId());
                                if (networkDomain != null) {
                                    _networkDomainDao.remove(networkDomain.getId());
                                }

                                final NetworkAccountVO networkAccount = _networkAccountDao.getAccountNetworkMapByNetworkId(networkFinal.getId());
                                if (networkAccount != null) {
                                    _networkAccountDao.remove(networkAccount.getId());
                                }
                            }

                            final NetworkOffering ntwkOff = _entityMgr.findById(NetworkOffering.class, networkFinal.getNetworkOfferingId());
                            final boolean updateResourceCount = resourceCountNeedsUpdate(ntwkOff, networkFinal.getAclType());
                            if (updateResourceCount) {
                                _resourceLimitMgr.decrementResourceCount(networkFinal.getAccountId(), ResourceType.network, networkFinal.getDisplayNetwork());
                            }
                        }
                    }
                });
                if (_networksDao.findById(network.getId()) == null) {
                    // remove its related ACL permission
                    final Pair<Class<?>, Long> networkMsg = new Pair<Class<?>, Long>(Network.class, networkFinal.getId());
                    _messageBus.publish(_name, EntityManager.MESSAGE_REMOVE_ENTITY_EVENT, PublishScope.LOCAL, networkMsg);
                }
                return true;
            } catch (final CloudRuntimeException e) {
                s_logger.error("Failed to delete network", e);
                return false;
            }
        }

        return success;
    }

    @Override
    public boolean resourceCountNeedsUpdate(final NetworkOffering ntwkOff, final ACLType aclType) {
        //Update resource count only for Isolated account specific non-system networks
        final boolean updateResourceCount = ntwkOff.getGuestType() == GuestType.Isolated && !ntwkOff.isSystemOnly() && aclType == ACLType.Account;
        return updateResourceCount;
    }

    protected boolean deleteVlansInNetwork(final long networkId, final long userId, final Account callerAccount) {

        //cleanup Public vlans
        final List<VlanVO> publicVlans = _vlanDao.listVlansByNetworkId(networkId);
        boolean result = true;
        for (final VlanVO vlan : publicVlans) {
            if (!_configMgr.deleteVlanAndPublicIpRange(userId, vlan.getId(), callerAccount)) {
                s_logger.warn("Failed to delete vlan " + vlan.getId() + ");");
                result = false;
            }
        }

        //cleanup private vlans
        final int privateIpAllocCount = _privateIpDao.countAllocatedByNetworkId(networkId);
        if (privateIpAllocCount > 0) {
            s_logger.warn("Can't delete Private ip range for network " + networkId + " as it has allocated ip addresses");
            result = false;
        } else {
            _privateIpDao.deleteByNetworkId(networkId);
            s_logger.debug("Deleted ip range for private network id=" + networkId);
        }
        return result;
    }

    public class NetworkGarbageCollector extends ManagedContextRunnable {
        @Override
        protected void runInContext() {
            final GlobalLock gcLock = GlobalLock.getInternLock("Network.GC.Lock");
            try {
                if (gcLock.lock(3)) {
                    try {
                        reallyRun();
                    } finally {
                        gcLock.unlock();
                    }
                }
            } finally {
                gcLock.releaseRef();
            }
        }

        public void reallyRun() {
            try {
                final List<Long> shutdownList = new ArrayList<Long>();
                final long currentTime = System.currentTimeMillis() / 1000;
                final HashMap<Long, Long> stillFree = new HashMap<Long, Long>();

                final List<Long> networkIds = _networksDao.findNetworksToGarbageCollect();
                final int netGcWait = NumbersUtil.parseInt(_configDao.getValue(NetworkGcWait.key()), 60);
                s_logger.info("NetworkGarbageCollector uses '" + netGcWait + "' seconds for GC interval.");

                for (final Long networkId : networkIds) {

                    if (!_networkModel.isNetworkReadyForGc(networkId)) {
                        continue;
                    }

                    final Long time = _lastNetworkIdsToFree.remove(networkId);
                    if (time == null) {
                        if (s_logger.isDebugEnabled()) {
                            s_logger.debug("We found network " + networkId + " to be free for the first time.  Adding it to the list: " + currentTime);
                        }
                        stillFree.put(networkId, currentTime);
                    } else if (time > currentTime - netGcWait) {
                        if (s_logger.isDebugEnabled()) {
                            s_logger.debug("Network " + networkId + " is still free but it's not time to shutdown yet: " + time);
                        }
                        stillFree.put(networkId, time);
                    } else {
                        shutdownList.add(networkId);
                    }
                }

                _lastNetworkIdsToFree = stillFree;

                final CallContext cctx = CallContext.current();

                for (final Long networkId : shutdownList) {

                    // If network is removed, unset gc flag for it
                    if (_networksDao.findById(networkId) == null) {
                        s_logger.debug("Network id=" + networkId + " is removed, so clearing up corresponding gc check");
                        _networksDao.clearCheckForGc(networkId);
                    } else {
                        try {

                            final User caller = cctx.getCallingUser();
                            final Account owner = cctx.getCallingAccount();

                            final ReservationContext context = new ReservationContextImpl(null, null, caller, owner);

                            shutdownNetwork(networkId, context, false);
                        } catch (final Exception e) {
                            s_logger.warn("Unable to shutdown network: " + networkId);
                        }
                    }
                }
            } catch (final Exception e) {
                s_logger.warn("Caught exception while running network gc: ", e);
            }
        }
    }

    @Override
    public boolean startNetwork(final long networkId, final DeployDestination dest, final ReservationContext context)
            throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {

        // Check if network exists
        final NetworkVO network = _networksDao.findById(networkId);
        if (network == null) {
            final InvalidParameterValueException ex = new InvalidParameterValueException("Network with specified id doesn't exist");
            ex.addProxyObject(String.valueOf(networkId), "networkId");
            throw ex;
        }

        // implement the network
        s_logger.debug("Starting network " + network + "...");
        final Pair<NetworkGuru, NetworkVO> implementedNetwork = implementNetwork(networkId, dest, context);
        if (implementedNetwork == null || implementedNetwork.first() == null) {

            s_logger.warn("Failed to start the network " + network);
            return false;
        } else {
            return true;
        }
    }

    @Override
    public boolean restartNetwork(final Long networkId, final Account callerAccount, final User callerUser, final boolean cleanup)
            throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {

        final NetworkVO network = _networksDao.findById(networkId);

        s_logger.debug("Restarting network " + networkId + "...");

        final ReservationContext context = new ReservationContextImpl(null, null, callerUser, callerAccount);

        if (cleanup) {
            // shutdown the network
            s_logger.debug("Shutting down the network id=" + networkId + " as a part of network restart");

            if (!shutdownNetworkElementsAndResources(context, true, network)) {
                s_logger.debug("Failed to shutdown the network elements and resources as a part of network restart: " + network.getState());
                setRestartRequired(network, true);
                return false;
            }
        } else {
            s_logger.debug("Skip the shutting down of network id=" + networkId);
        }

        // implement the network elements and rules again
        final DeployDestination dest = new DeployDestination(_dcDao.findById(network.getDataCenterId()), null, null, null);

        s_logger.debug("Implementing the network " + network + " elements and resources as a part of network restart");
        final NetworkOfferingVO offering = _networkOfferingDao.findById(network.getNetworkOfferingId());

        try {
            implementNetworkElementsAndResources(dest, context, network, offering);
            setRestartRequired(network, true);
            return true;
        } catch (final Exception ex) {
            s_logger.warn("Failed to implement network " + network + " elements and resources as a part of network restart due to ", ex);
            return false;
        }
    }

    private void setRestartRequired(final NetworkVO network, final boolean restartRequired) {
        s_logger.debug("Marking network " + network + " with restartRequired=" + restartRequired);
        network.setRestartRequired(restartRequired);
        _networksDao.update(network.getId(), network);
    }

    protected int getActiveNicsInNetwork(final long networkId) {
        return _networksDao.getActiveNicsIn(networkId);
    }

    @Override
    public NetworkProfile convertNetworkToNetworkProfile(final long networkId) {
        final NetworkVO network = _networksDao.findById(networkId);
        final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, network.getGuruName());
        final NetworkProfile profile = new NetworkProfile(network);
        guru.updateNetworkProfile(profile);

        return profile;
    }

    @Override
    public UserDataServiceProvider getPasswordResetProvider(final Network network) {
        final String passwordProvider = _ntwkSrvcDao.getProviderForServiceInNetwork(network.getId(), Service.UserData);

        if (passwordProvider == null) {
            s_logger.debug("Network " + network + " doesn't support service " + Service.UserData.getName());
            return null;
        }

        return (UserDataServiceProvider)_networkModel.getElementImplementingProvider(passwordProvider);
    }

    @Override
    public UserDataServiceProvider getSSHKeyResetProvider(final Network network) {
        final String SSHKeyProvider = _ntwkSrvcDao.getProviderForServiceInNetwork(network.getId(), Service.UserData);

        if (SSHKeyProvider == null) {
            s_logger.debug("Network " + network + " doesn't support service " + Service.UserData.getName());
            return null;
        }

        return (UserDataServiceProvider)_networkModel.getElementImplementingProvider(SSHKeyProvider);
    }

    @Override
    public DhcpServiceProvider getDhcpServiceProvider(final Network network) {
        final String DhcpProvider = _ntwkSrvcDao.getProviderForServiceInNetwork(network.getId(), Service.Dhcp);

        if (DhcpProvider == null) {
            s_logger.debug("Network " + network + " doesn't support service " + Service.Dhcp.getName());
            return null;
        }

        final NetworkElement element = _networkModel.getElementImplementingProvider(DhcpProvider);
        if (element instanceof DhcpServiceProvider) {
            return (DhcpServiceProvider)element;
        } else {
            return null;
        }
    }

    protected boolean isSharedNetworkWithServices(final Network network) {
        assert network != null;
        final DataCenter zone = _entityMgr.findById(DataCenter.class, network.getDataCenterId());
        if (network.getGuestType() == Network.GuestType.Shared && zone.getNetworkType() == NetworkType.Advanced
                && isSharedNetworkOfferingWithServices(network.getNetworkOfferingId())) {
            return true;
        }
        return false;
    }

    protected boolean isSharedNetworkOfferingWithServices(final long networkOfferingId) {
        final NetworkOfferingVO networkOffering = _networkOfferingDao.findById(networkOfferingId);
        if (networkOffering.getGuestType() == Network.GuestType.Shared && (_networkModel.areServicesSupportedByNetworkOffering(networkOfferingId, Service.SourceNat)
                || _networkModel.areServicesSupportedByNetworkOffering(networkOfferingId, Service.StaticNat)
                || _networkModel.areServicesSupportedByNetworkOffering(networkOfferingId, Service.Firewall)
                || _networkModel.areServicesSupportedByNetworkOffering(networkOfferingId, Service.PortForwarding)
                || _networkModel.areServicesSupportedByNetworkOffering(networkOfferingId, Service.Lb))) {

            return true;
        }
        return false;
    }

    @Override
    public List<? extends Nic> listVmNics(final long vmId, final Long nicId, final Long networkId) {
        List<NicVO> result = null;
        if (nicId == null && networkId == null) {
            result = _nicDao.listByVmId(vmId);
        } else {
            result = _nicDao.listByVmIdAndNicIdAndNtwkId(vmId, nicId, networkId);
        }

        for (final NicVO nic : result) {
            if (_networkModel.isProviderForNetwork(Provider.NiciraNvp, nic.getNetworkId())) {
                //For NSX Based networks, add nsxlogicalswitch, nsxlogicalswitchport to each result
                s_logger.info("Listing NSX logical switch and logical switch por for each nic");
                final NetworkVO network = _networksDao.findById(nic.getNetworkId());
                final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, network.getGuruName());
                final NetworkGuruAdditionalFunctions guruFunctions = (NetworkGuruAdditionalFunctions)guru;

                final Map<String, ? extends Object> nsxParams = guruFunctions.listAdditionalNicParams(nic.getUuid());
                if (nsxParams != null) {
                    final String lswitchUuuid = nsxParams.containsKey(NetworkGuruAdditionalFunctions.NSX_LSWITCH_UUID)
                            ? (String)nsxParams.get(NetworkGuruAdditionalFunctions.NSX_LSWITCH_UUID) : null;
                    final String lswitchPortUuuid = nsxParams.containsKey(NetworkGuruAdditionalFunctions.NSX_LSWITCHPORT_UUID)
                            ? (String)nsxParams.get(NetworkGuruAdditionalFunctions.NSX_LSWITCHPORT_UUID) : null;
                    nic.setNsxLogicalSwitchUuid(lswitchUuuid);
                    nic.setNsxLogicalSwitchPortUuid(lswitchPortUuuid);
                }
            }
        }

        return result;
    }

    @DB
    @Override
    public boolean reallocate(final VirtualMachineProfile vm, final DataCenterDeployment dest) throws InsufficientCapacityException, ConcurrentOperationException {
        final VMInstanceVO vmInstance = _vmDao.findById(vm.getId());
        final DataCenterVO dc = _dcDao.findById(vmInstance.getDataCenterId());
        if (dc.getNetworkType() == NetworkType.Basic) {
            final List<NicVO> nics = _nicDao.listByVmId(vmInstance.getId());
            final NetworkVO network = _networksDao.findById(nics.get(0).getNetworkId());
            final LinkedHashMap<Network, List<? extends NicProfile>> profiles = new LinkedHashMap<Network, List<? extends NicProfile>>();
            profiles.put(network, new ArrayList<NicProfile>());

            Transaction.execute(new TransactionCallbackWithExceptionNoReturn<InsufficientCapacityException>() {
                @Override
                public void doInTransactionWithoutResult(final TransactionStatus status) throws InsufficientCapacityException {
                    cleanupNics(vm);
                    allocate(vm, profiles);
                }
            });
        }
        return true;
    }

    private boolean cleanupNetworkResources(final long networkId, final Account caller, final long callerUserId) {
        boolean success = true;
        final Network network = _networksDao.findById(networkId);

        //remove all PF/Static Nat rules for the network
        try {
            if (_rulesMgr.revokeAllPFStaticNatRulesForNetwork(networkId, callerUserId, caller)) {
                s_logger.debug("Successfully cleaned up portForwarding/staticNat rules for network id=" + networkId);
            } else {
                success = false;
                s_logger.warn("Failed to release portForwarding/StaticNat rules as a part of network id=" + networkId + " cleanup");
            }
        } catch (final ResourceUnavailableException ex) {
            success = false;
            // shouldn't even come here as network is being cleaned up after all network elements are shutdown
            s_logger.warn("Failed to release portForwarding/StaticNat rules as a part of network id=" + networkId + " cleanup due to resourceUnavailable ", ex);
        }

        //remove all LB rules for the network
        if (_lbMgr.removeAllLoadBalanacersForNetwork(networkId, caller, callerUserId)) {
            s_logger.debug("Successfully cleaned up load balancing rules for network id=" + networkId);
        } else {
            // shouldn't even come here as network is being cleaned up after all network elements are shutdown
            success = false;
            s_logger.warn("Failed to cleanup LB rules as a part of network id=" + networkId + " cleanup");
        }

        //revoke all firewall rules for the network
        try {
            if (_firewallMgr.revokeAllFirewallRulesForNetwork(networkId, callerUserId, caller)) {
                s_logger.debug("Successfully cleaned up firewallRules rules for network id=" + networkId);
            } else {
                success = false;
                s_logger.warn("Failed to cleanup Firewall rules as a part of network id=" + networkId + " cleanup");
            }
        } catch (final ResourceUnavailableException ex) {
            success = false;
            // shouldn't even come here as network is being cleaned up after all network elements are shutdown
            s_logger.warn("Failed to cleanup Firewall rules as a part of network id=" + networkId + " cleanup due to resourceUnavailable ", ex);
        }

        //revoke all network ACLs for network
        try {
            if (_networkACLMgr.revokeACLItemsForNetwork(networkId)) {
                s_logger.debug("Successfully cleaned up NetworkACLs for network id=" + networkId);
            } else {
                success = false;
                s_logger.warn("Failed to cleanup NetworkACLs as a part of network id=" + networkId + " cleanup");
            }
        } catch (final ResourceUnavailableException ex) {
            success = false;
            s_logger.warn("Failed to cleanup Network ACLs as a part of network id=" + networkId + " cleanup due to resourceUnavailable ", ex);
        }

        //release all ip addresses
        final List<IPAddressVO> ipsToRelease = _ipAddressDao.listByAssociatedNetwork(networkId, null);
        for (final IPAddressVO ipToRelease : ipsToRelease) {
            if (ipToRelease.getVpcId() == null) {
                if (!ipToRelease.isPortable()) {
                    final IPAddressVO ip = _ipAddrMgr.markIpAsUnavailable(ipToRelease.getId());
                    assert ip != null : "Unable to mark the ip address id=" + ipToRelease.getId() + " as unavailable.";
                } else {
                    // portable IP address are associated with owner, until explicitly requested to be disassociated
                    // so as part of network clean up just break IP association with guest network
                    ipToRelease.setAssociatedWithNetworkId(null);
                    _ipAddressDao.update(ipToRelease.getId(), ipToRelease);
                    s_logger.debug("Portable IP address " + ipToRelease + " is no longer associated with any network");
                }
            } else {
                _vpcMgr.unassignIPFromVpcNetwork(ipToRelease.getId(), network.getId());
            }
        }

        try {
            if (!_ipAddrMgr.applyIpAssociations(network, true)) {
                s_logger.warn("Unable to apply ip address associations for " + network);
                success = false;
            }
        } catch (final ResourceUnavailableException e) {
            throw new CloudRuntimeException("We should never get to here because we used true when applyIpAssociations", e);
        }

        return success;
    }

    private boolean shutdownNetworkResources(final long networkId, final Account caller, final long callerUserId) {
        // This method cleans up network rules on the backend w/o touching them in the DB
        boolean success = true;
        final Network network = _networksDao.findById(networkId);

        // Mark all PF rules as revoked and apply them on the backend (not in the DB)
        final List<PortForwardingRuleVO> pfRules = _portForwardingRulesDao.listByNetwork(networkId);
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Releasing " + pfRules.size() + " port forwarding rules for network id=" + networkId + " as a part of shutdownNetworkRules");
        }

        for (final PortForwardingRuleVO pfRule : pfRules) {
            s_logger.trace("Marking pf rule " + pfRule + " with Revoke state");
            pfRule.setState(FirewallRule.State.Revoke);
        }

        try {
            if (!_firewallMgr.applyRules(pfRules, true, false)) {
                s_logger.warn("Failed to cleanup pf rules as a part of shutdownNetworkRules");
                success = false;
            }
        } catch (final ResourceUnavailableException ex) {
            s_logger.warn("Failed to cleanup pf rules as a part of shutdownNetworkRules due to ", ex);
            success = false;
        }

        // Mark all static rules as revoked and apply them on the backend (not in the DB)
        final List<FirewallRuleVO> firewallStaticNatRules = _firewallDao.listByNetworkAndPurpose(networkId, Purpose.StaticNat);
        final List<StaticNatRule> staticNatRules = new ArrayList<StaticNatRule>();
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Releasing " + firewallStaticNatRules.size() + " static nat rules for network id=" + networkId + " as a part of shutdownNetworkRules");
        }

        for (final FirewallRuleVO firewallStaticNatRule : firewallStaticNatRules) {
            s_logger.trace("Marking static nat rule " + firewallStaticNatRule + " with Revoke state");
            final IpAddress ip = _ipAddressDao.findById(firewallStaticNatRule.getSourceIpAddressId());
            final FirewallRuleVO ruleVO = _firewallDao.findById(firewallStaticNatRule.getId());

            if (ip == null || !ip.isOneToOneNat() || ip.getAssociatedWithVmId() == null) {
                throw new InvalidParameterValueException("Source ip address of the rule id=" + firewallStaticNatRule.getId() + " is not static nat enabled");
            }

            //String dstIp = _networkModel.getIpInNetwork(ip.getAssociatedWithVmId(), firewallStaticNatRule.getNetworkId());
            ruleVO.setState(FirewallRule.State.Revoke);
            staticNatRules.add(new StaticNatRuleImpl(ruleVO, ip.getVmIp()));
        }

        try {
            if (!_firewallMgr.applyRules(staticNatRules, true, false)) {
                s_logger.warn("Failed to cleanup static nat rules as a part of shutdownNetworkRules");
                success = false;
            }
        } catch (final ResourceUnavailableException ex) {
            s_logger.warn("Failed to cleanup static nat rules as a part of shutdownNetworkRules due to ", ex);
            success = false;
        }

        try {
            if (!_lbMgr.revokeLoadBalancersForNetwork(networkId, Scheme.Public)) {
                s_logger.warn("Failed to cleanup public lb rules as a part of shutdownNetworkRules");
                success = false;
            }
        } catch (final ResourceUnavailableException ex) {
            s_logger.warn("Failed to cleanup public lb rules as a part of shutdownNetworkRules due to ", ex);
            success = false;
        }

        try {
            if (!_lbMgr.revokeLoadBalancersForNetwork(networkId, Scheme.Internal)) {
                s_logger.warn("Failed to cleanup internal lb rules as a part of shutdownNetworkRules");
                success = false;
            }
        } catch (final ResourceUnavailableException ex) {
            s_logger.warn("Failed to cleanup public lb rules as a part of shutdownNetworkRules due to ", ex);
            success = false;
        }

        // revoke all firewall rules for the network w/o applying them on the DB
        final List<FirewallRuleVO> firewallRules = _firewallDao.listByNetworkPurposeTrafficType(networkId, Purpose.Firewall, FirewallRule.TrafficType.Ingress);
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Releasing " + firewallRules.size() + " firewall ingress rules for network id=" + networkId + " as a part of shutdownNetworkRules");
        }

        for (final FirewallRuleVO firewallRule : firewallRules) {
            s_logger.trace("Marking firewall ingress rule " + firewallRule + " with Revoke state");
            firewallRule.setState(FirewallRule.State.Revoke);
        }

        try {
            if (!_firewallMgr.applyRules(firewallRules, true, false)) {
                s_logger.warn("Failed to cleanup firewall ingress rules as a part of shutdownNetworkRules");
                success = false;
            }
        } catch (final ResourceUnavailableException ex) {
            s_logger.warn("Failed to cleanup firewall ingress rules as a part of shutdownNetworkRules due to ", ex);
            success = false;
        }

        final List<FirewallRuleVO> firewallEgressRules = _firewallDao.listByNetworkPurposeTrafficType(networkId, Purpose.Firewall, FirewallRule.TrafficType.Egress);
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Releasing " + firewallEgressRules.size() + " firewall egress rules for network id=" + networkId + " as a part of shutdownNetworkRules");
        }

        try {
            // delete default egress rule
            final DataCenter zone = _dcDao.findById(network.getDataCenterId());
            if (_networkModel.areServicesSupportedInNetwork(network.getId(), Service.Firewall) && (network.getGuestType() == Network.GuestType.Isolated
                    || network.getGuestType() == Network.GuestType.Shared && zone.getNetworkType() == NetworkType.Advanced)) {

                // add default egress rule to accept the traffic
                _firewallMgr.applyDefaultEgressFirewallRule(network.getId(), _networkModel.getNetworkEgressDefaultPolicy(networkId), false);
            }

        } catch (final ResourceUnavailableException ex) {
            s_logger.warn("Failed to cleanup firewall default egress rule as a part of shutdownNetworkRules due to ", ex);
            success = false;
        }

        for (final FirewallRuleVO firewallRule : firewallEgressRules) {
            s_logger.trace("Marking firewall egress rule " + firewallRule + " with Revoke state");
            firewallRule.setState(FirewallRule.State.Revoke);
        }

        try {
            if (!_firewallMgr.applyRules(firewallEgressRules, true, false)) {
                s_logger.warn("Failed to cleanup firewall egress rules as a part of shutdownNetworkRules");
                success = false;
            }
        } catch (final ResourceUnavailableException ex) {
            s_logger.warn("Failed to cleanup firewall egress rules as a part of shutdownNetworkRules due to ", ex);
            success = false;
        }

        if (network.getVpcId() != null) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Releasing Network ACL Items for network id=" + networkId + " as a part of shutdownNetworkRules");
            }

            try {
                //revoke all Network ACLs for the network w/o applying them in the DB
                if (!_networkACLMgr.revokeACLItemsForNetwork(networkId)) {
                    s_logger.warn("Failed to cleanup network ACLs as a part of shutdownNetworkRules");
                    success = false;
                }
            } catch (final ResourceUnavailableException ex) {
                s_logger.warn("Failed to cleanup network ACLs as a part of shutdownNetworkRules due to ", ex);
                success = false;
            }

        }

        //release all static nats for the network
        if (!_rulesMgr.applyStaticNatForNetwork(networkId, false, caller, true)) {
            s_logger.warn("Failed to disable static nats as part of shutdownNetworkRules for network id " + networkId);
            success = false;
        }

        // Get all ip addresses, mark as releasing and release them on the backend
        final List<IPAddressVO> userIps = _ipAddressDao.listByAssociatedNetwork(networkId, null);
        final List<PublicIp> publicIpsToRelease = new ArrayList<PublicIp>();
        if (userIps != null && !userIps.isEmpty()) {
            for (final IPAddressVO userIp : userIps) {
                userIp.setState(IpAddress.State.Releasing);
                final PublicIp publicIp = PublicIp.createFromAddrAndVlan(userIp, _vlanDao.findById(userIp.getVlanId()));
                publicIpsToRelease.add(publicIp);
            }
        }

        try {
            if (!_ipAddrMgr.applyIpAssociations(network, true, true, publicIpsToRelease)) {
                s_logger.warn("Unable to apply ip address associations for " + network + " as a part of shutdownNetworkRules");
                success = false;
            }
        } catch (final ResourceUnavailableException e) {
            throw new CloudRuntimeException("We should never get to here because we used true when applyIpAssociations", e);
        }

        return success;
    }

    @Override
    public boolean processAnswers(final long agentId, final long seq, final Answer[] answers) {
        return false;
    }

    @Override
    public boolean processCommands(final long agentId, final long seq, final Command[] commands) {
        return false;
    }

    @Override
    public AgentControlAnswer processControlCommand(final long agentId, final AgentControlCommand cmd) {
        return null;
    }

    @Override
    public void processConnect(final Host host, final StartupCommand cmd, final boolean forRebalance) throws ConnectionException {
        if (!(cmd instanceof StartupRoutingCommand)) {
            return;
        }
        final long hostId = host.getId();
        final StartupRoutingCommand startup = (StartupRoutingCommand)cmd;

        final String dataCenter = startup.getDataCenter();

        long dcId = -1;
        DataCenterVO dc = _dcDao.findByName(dataCenter);
        if (dc == null) {
            try {
                dcId = Long.parseLong(dataCenter);
                dc = _dcDao.findById(dcId);
            } catch (final NumberFormatException e) {
            }
        }
        if (dc == null) {
            throw new IllegalArgumentException("Host " + startup.getPrivateIpAddress() + " sent incorrect data center: " + dataCenter);
        }
        dcId = dc.getId();
        final HypervisorType hypervisorType = startup.getHypervisorType();

        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Host's hypervisorType is: " + hypervisorType);
        }

        final List<PhysicalNetworkSetupInfo> networkInfoList = new ArrayList<PhysicalNetworkSetupInfo>();

        // list all physicalnetworks in the zone & for each get the network names
        final List<PhysicalNetworkVO> physicalNtwkList = _physicalNetworkDao.listByZone(dcId);
        for (final PhysicalNetworkVO pNtwk : physicalNtwkList) {
            final String publicName = _pNTrafficTypeDao.getNetworkTag(pNtwk.getId(), TrafficType.Public, hypervisorType);
            final String privateName = _pNTrafficTypeDao.getNetworkTag(pNtwk.getId(), TrafficType.Management, hypervisorType);
            final String guestName = _pNTrafficTypeDao.getNetworkTag(pNtwk.getId(), TrafficType.Guest, hypervisorType);
            final String storageName = _pNTrafficTypeDao.getNetworkTag(pNtwk.getId(), TrafficType.Storage, hypervisorType);
            // String controlName = _pNTrafficTypeDao._networkModel.getNetworkTag(pNtwk.getId(), TrafficType.Control, hypervisorType);
            final PhysicalNetworkSetupInfo info = new PhysicalNetworkSetupInfo();
            info.setPhysicalNetworkId(pNtwk.getId());
            info.setGuestNetworkName(guestName);
            info.setPrivateNetworkName(privateName);
            info.setPublicNetworkName(publicName);
            info.setStorageNetworkName(storageName);
            final PhysicalNetworkTrafficTypeVO mgmtTraffic = _pNTrafficTypeDao.findBy(pNtwk.getId(), TrafficType.Management);
            if (mgmtTraffic != null) {
                final String vlan = mgmtTraffic.getVlan();
                info.setMgmtVlan(vlan);
            }
            networkInfoList.add(info);
        }

        // send the names to the agent
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Sending CheckNetworkCommand to check the Network is setup correctly on Agent");
        }
        final CheckNetworkCommand nwCmd = new CheckNetworkCommand(networkInfoList);

        final CheckNetworkAnswer answer = (CheckNetworkAnswer)_agentMgr.easySend(hostId, nwCmd);

        if (answer == null) {
            s_logger.warn("Unable to get an answer to the CheckNetworkCommand from agent:" + host.getId());
            throw new ConnectionException(true, "Unable to get an answer to the CheckNetworkCommand from agent: " + host.getId());
        }

        if (!answer.getResult()) {
            s_logger.warn("Unable to setup agent " + hostId + " due to " + answer.getDetails());
            final String msg = "Incorrect Network setup on agent, Reinitialize agent after network names are setup, details : " + answer.getDetails();
            _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_HOST, dcId, host.getPodId(), msg, msg);
            throw new ConnectionException(true, msg);
        } else {
            if (answer.needReconnect()) {
                throw new ConnectionException(false, "Reinitialize agent after network setup.");
            }
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Network setup is correct on Agent");
            }
            return;
        }
    }

    @Override
    public boolean processDisconnect(final long agentId, final Status state) {
        return false;
    }

    @Override
    public boolean isRecurring() {
        return false;
    }

    @Override
    public int getTimeout() {
        return 0;
    }

    @Override
    public boolean processTimeout(final long agentId, final long seq) {
        return false;
    }

    @Override
    public Map<String, String> finalizeServicesAndProvidersForNetwork(final NetworkOffering offering, final Long physicalNetworkId) {
        final Map<String, String> svcProviders = new HashMap<String, String>();
        final Map<String, List<String>> providerSvcs = new HashMap<String, List<String>>();
        final List<NetworkOfferingServiceMapVO> servicesMap = _ntwkOfferingSrvcDao.listByNetworkOfferingId(offering.getId());

        final boolean checkPhysicalNetwork = physicalNetworkId != null ? true : false;

        for (final NetworkOfferingServiceMapVO serviceMap : servicesMap) {
            if (svcProviders.containsKey(serviceMap.getService())) {
                // FIXME - right now we pick up the first provider from the list, need to add more logic based on
                // provider load, etc
                continue;
            }

            final String service = serviceMap.getService();
            String provider = serviceMap.getProvider();

            if (provider == null) {
                provider = _networkModel.getDefaultUniqueProviderForService(service).getName();
            }

            // check that provider is supported
            if (checkPhysicalNetwork) {
                if (!_pNSPDao.isServiceProviderEnabled(physicalNetworkId, provider, service)) {
                    throw new UnsupportedServiceException(
                            "Provider " + provider + " is either not enabled or doesn't " + "support service " + service + " in physical network id=" + physicalNetworkId);
                }
            }

            svcProviders.put(service, provider);
            List<String> l = providerSvcs.get(provider);
            if (l == null) {
                providerSvcs.put(provider, l = new ArrayList<String>());
            }
            l.add(service);
        }

        return svcProviders;
    }

    private List<Provider> getNetworkProviders(final long networkId) {
        final List<String> providerNames = _ntwkSrvcDao.getDistinctProviders(networkId);
        final List<Provider> providers = new ArrayList<Provider>();
        for (final String providerName : providerNames) {
            providers.add(Network.Provider.getProvider(providerName));
        }

        return providers;
    }

    @Override
    public boolean setupDns(final Network network, final Provider provider) {
        final boolean dnsProvided = _networkModel.isProviderSupportServiceInNetwork(network.getId(), Service.Dns, provider);
        final boolean dhcpProvided = _networkModel.isProviderSupportServiceInNetwork(network.getId(), Service.Dhcp, provider);

        final boolean setupDns = dnsProvided || dhcpProvided;
        return setupDns;
    }

    protected NicProfile getNicProfileForVm(final Network network, final NicProfile requested, final VirtualMachine vm) {
        NicProfile nic = null;
        if (requested != null && requested.getBroadCastUri() != null) {
            final String broadcastUri = requested.getBroadCastUri().toString();
            final String ipAddress = requested.getIPv4Address();
            final NicVO nicVO = _nicDao.findByNetworkIdInstanceIdAndBroadcastUri(network.getId(), vm.getId(), broadcastUri);
            if (nicVO != null) {
                if (ipAddress == null || nicVO.getIPv4Address().equals(ipAddress)) {
                    nic = _networkModel.getNicProfile(vm, network.getId(), broadcastUri);
                }
            }
        } else {
            final NicVO nicVO = _nicDao.findByNtwkIdAndInstanceId(network.getId(), vm.getId());
            if (nicVO != null) {
                nic = _networkModel.getNicProfile(vm, network.getId(), null);
            }
        }
        return nic;
    }

    @Override
    public NicProfile createNicForVm(final Network network, final NicProfile requested, final ReservationContext context, final VirtualMachineProfile vmProfile,
            final boolean prepare) throws InsufficientVirtualNetworkCapacityException, InsufficientAddressCapacityException, ConcurrentOperationException,
                    InsufficientCapacityException, ResourceUnavailableException {

        final VirtualMachine vm = vmProfile.getVirtualMachine();
        final DataCenter dc = _entityMgr.findById(DataCenter.class, network.getDataCenterId());
        final Host host = _hostDao.findById(vm.getHostId());
        final DeployDestination dest = new DeployDestination(dc, null, null, host);

        NicProfile nic = getNicProfileForVm(network, requested, vm);

        //1) allocate nic (if needed) Always allocate if it is a user vm
        if (nic == null || vmProfile.getType() == VirtualMachine.Type.User) {
            final int deviceId = _nicDao.getFreeDeviceId(vm.getId());

            nic = allocateNic(requested, network, false, deviceId, vmProfile).first();

            if (nic == null) {
                throw new CloudRuntimeException("Failed to allocate nic for vm " + vm + " in network " + network);
            }

            //Update vm_network_map table
            if (vmProfile.getType() == VirtualMachine.Type.User) {
                final VMNetworkMapVO vno = new VMNetworkMapVO(vm.getId(), network.getId());
                _vmNetworkMapDao.persist(vno);
            }
            s_logger.debug("Nic is allocated successfully for vm " + vm + " in network " + network);
        }

        //2) prepare nic
        if (prepare) {
            final Pair<NetworkGuru, NetworkVO> implemented = implementNetwork(nic.getNetworkId(), dest, context, vmProfile.getVirtualMachine().getType() == Type.DomainRouter);
            if (implemented == null || implemented.first() == null) {
                s_logger.warn("Failed to implement network id=" + nic.getNetworkId() + " as a part of preparing nic id=" + nic.getId());
                throw new CloudRuntimeException("Failed to implement network id=" + nic.getNetworkId() + " as a part preparing nic id=" + nic.getId());
            }
            nic = prepareNic(vmProfile, dest, context, nic.getId(), implemented.second());
            s_logger.debug("Nic is prepared successfully for vm " + vm + " in network " + network);
        }

        return nic;
    }

    @Override
    public List<NicProfile> getNicProfiles(final VirtualMachine vm) {
        final List<NicVO> nics = _nicDao.listByVmId(vm.getId());
        final List<NicProfile> profiles = new ArrayList<NicProfile>();

        if (nics != null) {
            for (final Nic nic : nics) {
                final NetworkVO network = _networksDao.findById(nic.getNetworkId());
                final Integer networkRate = _networkModel.getNetworkRate(network.getId(), vm.getId());

                final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, network.getGuruName());
                final NicProfile profile = new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), networkRate,
                        _networkModel.isSecurityGroupSupportedInNetwork(network), _networkModel.getNetworkTag(vm.getHypervisorType(), network));
                guru.updateNicProfile(profile, network);
                profiles.add(profile);
            }
        }
        return profiles;
    }

    protected boolean stateTransitTo(final NetworkVO network, final Network.Event e) throws NoTransitionException {
        return _stateMachine.transitTo(network, e, null, _networksDao);
    }

    private void setStateMachine() {
        _stateMachine = Network.State.getStateMachine();
    }

    private Map<Service, Set<Provider>> getServiceProvidersMap(final long networkId) {
        final Map<Service, Set<Provider>> map = new HashMap<Service, Set<Provider>>();
        final List<NetworkServiceMapVO> nsms = _ntwkSrvcDao.getServicesInNetwork(networkId);
        for (final NetworkServiceMapVO nsm : nsms) {
            Set<Provider> providers = map.get(Service.getService(nsm.getService()));
            if (providers == null) {
                providers = new HashSet<Provider>();
            }
            providers.add(Provider.getProvider(nsm.getProvider()));
            map.put(Service.getService(nsm.getService()), providers);
        }
        return map;
    }

    @Override
    public List<Provider> getProvidersForServiceInNetwork(final Network network, final Service service) {
        final Map<Service, Set<Provider>> service2ProviderMap = getServiceProvidersMap(network.getId());
        if (service2ProviderMap.get(service) != null) {
            final List<Provider> providers = new ArrayList<Provider>(service2ProviderMap.get(service));
            return providers;
        }
        return null;
    }

    protected List<NetworkElement> getElementForServiceInNetwork(final Network network, final Service service) {
        final List<NetworkElement> elements = new ArrayList<NetworkElement>();
        final List<Provider> providers = getProvidersForServiceInNetwork(network, service);
        //Only support one provider now
        if (providers == null) {
            s_logger.error("Cannot find " + service.getName() + " provider for network " + network.getId());
            return null;
        }
        if (providers.size() != 1 && service != Service.Lb) {
            //support more than one LB providers only
            s_logger.error("Found " + providers.size() + " " + service.getName() + " providers for network!" + network.getId());
            return null;
        }

        for (final Provider provider : providers) {
            final NetworkElement element = _networkModel.getElementImplementingProvider(provider.getName());
            s_logger.info("Let " + element.getName() + " handle " + service.getName() + " in network " + network.getId());
            elements.add(element);
        }
        return elements;
    }

    @Override
    public StaticNatServiceProvider getStaticNatProviderForNetwork(final Network network) {
        //only one provider per Static nat service is supoprted
        final NetworkElement element = getElementForServiceInNetwork(network, Service.StaticNat).get(0);
        assert element instanceof StaticNatServiceProvider;
        return (StaticNatServiceProvider)element;
    }

    @Override
    public LoadBalancingServiceProvider getLoadBalancingProviderForNetwork(final Network network, final Scheme lbScheme) {
        final List<NetworkElement> lbElements = getElementForServiceInNetwork(network, Service.Lb);
        NetworkElement lbElement = null;
        if (lbElements.size() > 1) {
            String providerName = null;
            //get network offering details
            final NetworkOffering off = _entityMgr.findById(NetworkOffering.class, network.getNetworkOfferingId());
            if (lbScheme == Scheme.Public) {
                providerName = _ntwkOffDetailsDao.getDetail(off.getId(), NetworkOffering.Detail.PublicLbProvider);
            } else {
                providerName = _ntwkOffDetailsDao.getDetail(off.getId(), NetworkOffering.Detail.InternalLbProvider);
            }
            if (providerName == null) {
                throw new InvalidParameterValueException("Can't find Lb provider supporting scheme " + lbScheme.toString() + " in network " + network);
            }
            lbElement = _networkModel.getElementImplementingProvider(providerName);
        } else if (lbElements.size() == 1) {
            lbElement = lbElements.get(0);
        }

        assert lbElement != null;
        assert lbElement instanceof LoadBalancingServiceProvider;
        return (LoadBalancingServiceProvider)lbElement;
    }

    @Override
    public boolean isNetworkInlineMode(final Network network) {
        final NetworkOfferingVO offering = _networkOfferingDao.findById(network.getNetworkOfferingId());
        return offering.isInline();
    }

    @Override
    public boolean isSecondaryIpSetForNic(final long nicId) {
        final NicVO nic = _nicDao.findById(nicId);
        return nic.getSecondaryIp();
    }

    private boolean removeVmSecondaryIpsOfNic(final long nicId) {
        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(final TransactionStatus status) {
                final List<NicSecondaryIpVO> ipList = _nicSecondaryIpDao.listByNicId(nicId);
                if (ipList != null) {
                    for (final NicSecondaryIpVO ip : ipList) {
                        _nicSecondaryIpDao.remove(ip.getId());
                    }
                    s_logger.debug("Revoving nic secondary ip entry ...");
                }
            }
        });

        return true;
    }

    @Override
    public NicVO savePlaceholderNic(final Network network, final String ip4Address, final String ip6Address, final Type vmType) {
        final NicVO nic = new NicVO(null, null, network.getId(), null);
        nic.setIPv4Address(ip4Address);
        nic.setIPv6Address(ip6Address);
        nic.setReservationStrategy(ReservationStrategy.PlaceHolder);
        nic.setState(Nic.State.Reserved);
        nic.setVmType(vmType);
        return _nicDao.persist(nic);
    }

    @Override
    public String getConfigComponentName() {
        return NetworkOrchestrationService.class.getSimpleName();
    }

    public static final ConfigKey<Integer> NetworkGcWait = new ConfigKey<Integer>(Integer.class, "network.gc.wait", "Advanced", "600",
            "Time (in seconds) to wait before shutting down a network that's not in used", false, Scope.Global, null);
    public static final ConfigKey<Integer> NetworkGcInterval = new ConfigKey<Integer>(Integer.class, "network.gc.interval", "Advanced", "600",
            "Seconds to wait before checking for networks to shutdown", true, Scope.Global, null);

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {NetworkGcWait, NetworkGcInterval, NetworkLockTimeout, GuestDomainSuffix, NetworkThrottlingRate, MinVRVersion};
    }
}
