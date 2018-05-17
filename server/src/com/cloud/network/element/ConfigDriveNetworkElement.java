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
package com.cloud.network.element;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPoint;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPointSelector;
import org.apache.cloudstack.storage.configdrive.ConfigDrive;
import org.apache.cloudstack.storage.configdrive.ConfigDriveBuilder;
import org.apache.cloudstack.storage.to.TemplateObjectTO;
import org.apache.log4j.Logger;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.HandleConfigDriveIsoCommand;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.configuration.ConfigurationManager;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.UnsupportedServiceException;
import com.cloud.host.Host;
import com.cloud.host.dao.HostDao;
import com.cloud.network.Network;
import com.cloud.network.Network.Capability;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkMigrationResponder;
import com.cloud.network.NetworkModel;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.PhysicalNetworkServiceProvider;
import com.cloud.offering.NetworkOffering;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.Storage;
import com.cloud.storage.Volume;
import com.cloud.storage.dao.GuestOSCategoryDao;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.StateListener;
import com.cloud.utils.fsm.StateMachine2;
import com.cloud.vm.Nic;
import com.cloud.vm.NicProfile;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.UserVmDetailVO;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.UserVmDetailsDao;

public class ConfigDriveNetworkElement extends AdapterBase implements NetworkElement, UserDataServiceProvider,
        StateListener<VirtualMachine.State, VirtualMachine.Event, VirtualMachine>, NetworkMigrationResponder {
    private static final Logger LOG = Logger.getLogger(ConfigDriveNetworkElement.class);

    private static final Map<Service, Map<Capability, String>> capabilities = setCapabilities();

    @Inject
    NetworkModel _networkMgr;
    @Inject
    UserVmDao _userVmDao;
    @Inject
    UserVmDetailsDao _userVmDetailsDao;
    @Inject
    ConfigurationManager _configMgr;
    @Inject
    DataCenterDao _dcDao;
    @Inject
    ServiceOfferingDao _serviceOfferingDao;
    @Inject
    NetworkModel _networkModel;
    @Inject
    GuestOSCategoryDao _guestOSCategoryDao;
    @Inject
    GuestOSDao _guestOSDao;
    @Inject
    HostDao _hostDao;
    @Inject
    DataStoreManager _dataStoreMgr;
    @Inject
    EndPointSelector _ep;

    private final static Integer CONFIGDRIVEDISKSEQ = 4;

    private boolean canHandle(TrafficType trafficType) {
        return trafficType.equals(TrafficType.Guest);
    }

    @Override
    public boolean start() {
        VirtualMachine.State.getStateMachine().registerListener(this);
        return super.start();
    }

    @Override
    public boolean implement(Network network, NetworkOffering offering, DeployDestination dest, ReservationContext context) throws ResourceUnavailableException, ConcurrentOperationException,
            InsufficientCapacityException {
        return canHandle(offering.getTrafficType());
    }

    @Override
    public boolean prepare(Network network, NicProfile nic, VirtualMachineProfile vmProfile, DeployDestination dest, ReservationContext context) throws ConcurrentOperationException,
            InsufficientCapacityException, ResourceUnavailableException {
        return true;
    }

    @Override
    public boolean release(Network network, NicProfile nic, VirtualMachineProfile vm, ReservationContext context) {
        if (!nic.isDefaultNic()) {
            return true;
        }

        // Remove from secondary storage
        DataStore secondaryStore = _dataStoreMgr.getImageStore(network.getDataCenterId());

        String isoFile =  "/" + ConfigDrive.CONFIGDRIVEDIR + "/" + vm.getInstanceName()+ "/" + ConfigDrive.CONFIGDRIVEFILENAME;
        HandleConfigDriveIsoCommand deleteCommand = new HandleConfigDriveIsoCommand(vm.getVmData(),
                vm.getConfigDriveLabel(), secondaryStore.getTO(), isoFile, false);
        // Delete the ISO on the secondary store
        EndPoint endpoint = _ep.select(secondaryStore);
        if (endpoint == null) {
            LOG.error(String.format("Secondary store: %s not available", secondaryStore.getName()));
            return false;
        }
        Answer answer = endpoint.sendMessage(deleteCommand);
        return answer.getResult();
    }

    @Override
    public boolean shutdown(Network network, ReservationContext context, boolean cleanup) throws ConcurrentOperationException, ResourceUnavailableException {
        return true; // assume that the agent will remove userdata etc
    }

    @Override
    public boolean destroy(Network config, ReservationContext context) throws ConcurrentOperationException, ResourceUnavailableException {
        return true; // assume that the agent will remove userdata etc
    }

    @Override
    public Provider getProvider() {
        return Provider.ConfigDrive;
    }

    @Override
    public Map<Service, Map<Capability, String>> getCapabilities() {
        return capabilities;
    }

    private static Map<Service, Map<Capability, String>> setCapabilities() {
        Map<Service, Map<Capability, String>> capabilities = new HashMap<>();
        capabilities.put(Service.UserData, null);
        return capabilities;
    }

    @Override
    public boolean isReady(PhysicalNetworkServiceProvider provider) {
        return true;
    }

    @Override
    public boolean shutdownProviderInstances(PhysicalNetworkServiceProvider provider, ReservationContext context) throws ConcurrentOperationException, ResourceUnavailableException {
        return true;
    }

    @Override
    public boolean canEnableIndividualServices() {
        return false;
    }

    private String getSshKey(VirtualMachineProfile profile) {
        final UserVmDetailVO vmDetailSshKey = _userVmDetailsDao.findDetail(profile.getId(), "SSH.PublicKey");
        return (vmDetailSshKey!=null ? vmDetailSshKey.getValue() : null);
    }

    @Override
    public boolean addPasswordAndUserdata(Network network, NicProfile nic, VirtualMachineProfile profile, DeployDestination dest, ReservationContext context)
            throws ConcurrentOperationException, InsufficientCapacityException, ResourceUnavailableException {
        return (canHandle(network.getTrafficType())
                && configureConfigDriveData(profile, nic))
                && createConfigDriveIso(network, profile, dest.getHost());
    }

    @Override
    public boolean savePassword(final Network network, final NicProfile nic, final VirtualMachineProfile vm) throws ResourceUnavailableException {
        // savePassword is called by resetPasswordForVirtualMachine API which requires VM to be shutdown
        // Upper layers should save password in db, we do not need to update/create config drive iso at this point
        // Config drive will be created with updated password when VM starts in future
        if (vm != null && vm.getVirtualMachine().getState().equals(VirtualMachine.State.Running)) {
            throw new CloudRuntimeException("VM should to stopped to reset password");
        }
        return canHandle(network.getTrafficType());
    }

    @Override
    public boolean saveSSHKey(final Network network, final NicProfile nic, final VirtualMachineProfile vm, final String sshPublicKey) throws ResourceUnavailableException {
        // saveSSHKey is called by resetSSHKeyForVirtualMachine API which requires VM to be shutdown
        // Upper layers should save ssh public key in db, we do not need to update/create config drive iso at this point
        // Config drive will be created with updated password when VM starts in future
        if (vm != null && vm.getVirtualMachine().getState().equals(VirtualMachine.State.Running)) {
            throw new CloudRuntimeException("VM should to stopped to reset password");
        }
        return canHandle(network.getTrafficType());
    }

    @Override
    public boolean saveUserData(final Network network, final NicProfile nic, final VirtualMachineProfile vm) throws ResourceUnavailableException {
        // saveUserData is called by updateVirtualMachine API which requires VM to be shutdown
        // Upper layers should save userdata in db, we do not need to update/create config drive iso at this point
        // Config drive will be created with updated password when VM starts in future
        if (vm != null && vm.getVirtualMachine().getState().equals(VirtualMachine.State.Running)) {
            throw new CloudRuntimeException("VM should to stopped to reset password");
        }
        return canHandle(network.getTrafficType());
    }

    @Override
    public boolean verifyServicesCombination(Set<Service> services) {
        return true;
    }

    @Override
    public boolean preStateTransitionEvent(VirtualMachine.State oldState, VirtualMachine.Event event, VirtualMachine.State newState, VirtualMachine vo, boolean status, Object opaque) {
        return true;
    }

    @Override
    public boolean postStateTransitionEvent(StateMachine2.Transition<VirtualMachine.State, VirtualMachine.Event> transition, VirtualMachine vo, boolean status, Object opaque) {
        if (transition.getToState().equals(VirtualMachine.State.Expunging) && transition.getEvent().equals(VirtualMachine.Event.ExpungeOperation)) {
            Nic nic = _networkModel.getDefaultNic(vo.getId());
            try {
                if (nic != null) {
                    final Network network = _networkMgr.getNetwork(nic.getNetworkId());
                    final UserDataServiceProvider userDataUpdateProvider = _networkModel.getUserDataUpdateProvider(network);
                    final Provider provider = userDataUpdateProvider.getProvider();
                    if (provider.equals(Provider.ConfigDrive)) {
                        // Delete config drive ISO on destroy
                        DataStore secondaryStore = _dataStoreMgr.getImageStore(vo.getDataCenterId());
                        String isoFile = "/" + ConfigDrive.CONFIGDRIVEDIR + "/" + vo.getInstanceName() + "/" + ConfigDrive.CONFIGDRIVEFILENAME;
                        HandleConfigDriveIsoCommand deleteCommand = new HandleConfigDriveIsoCommand(null,
                                null, secondaryStore.getTO(), isoFile, false);
                        EndPoint endpoint = _ep.select(secondaryStore);
                        if (endpoint == null) {
                            LOG.error(String.format("Secondary store: %s not available", secondaryStore.getName()));
                            return false;
                        }
                        Answer answer = endpoint.sendMessage(deleteCommand);
                        if (!answer.getResult()) {
                            LOG.error(String.format("Update ISO failed, details: %s", answer.getDetails()));
                            return false;
                        }
                    }
                }
            } catch (UnsupportedServiceException usse) {}
        }
        return true;
    }

    @Override
    public boolean prepareMigration(NicProfile nic, Network network, VirtualMachineProfile vm, DeployDestination dest, ReservationContext context) {
        if (nic.isDefaultNic() && _networkModel.getUserDataUpdateProvider(network).getProvider().equals(Provider.ConfigDrive)) {
            LOG.trace(String.format("[prepareMigration] for vm: %s", vm.getInstanceName()));
            DataStore secondaryStore = _dataStoreMgr.getImageStore(network.getDataCenterId());
            addConfigDriveDisk(vm, secondaryStore);
            return false;
        }
        else return  true;
    }

    @Override
    public void rollbackMigration(NicProfile nic, Network network, VirtualMachineProfile vm, ReservationContext src, ReservationContext dst) {
    }

    @Override
    public void commitMigration(NicProfile nic, Network network, VirtualMachineProfile vm, ReservationContext src, ReservationContext dst) {
    }

    private boolean createConfigDriveIso(Network network, VirtualMachineProfile profile, Host host) throws ResourceUnavailableException {
        Long hostId;
        if (host == null) {
            hostId = (profile.getVirtualMachine().getHostId() == null ? profile.getVirtualMachine().getLastHostId(): profile.getVirtualMachine().getHostId());
        } else {
            hostId = host.getId();
        }

        DataStore secondaryStore = _dataStoreMgr.getImageStore(network.getDataCenterId());
        if (profile.getVirtualMachine().getState().equals(VirtualMachine.State.Running)) {
            throw new CloudRuntimeException("VM should not be in running state while creating config drive");
        }

        // Create/Update the iso on the secondary store
        LOG.debug(String.format("Creating config drive ISO for  vm %s in host %s",
                profile.getInstanceName(), _hostDao.findById(hostId).getName()));
        EndPoint endpoint = _ep.select(secondaryStore);
        if (endpoint == null) {
            throw new ResourceUnavailableException("Config drive creation failed, secondary store not available",
                    secondaryStore.getClass(), secondaryStore.getId());
        }
        String isoPath = ConfigDrive.CONFIGDRIVEDIR + "/" + profile.getInstanceName() + "/"  + ConfigDrive.CONFIGDRIVEFILENAME;

        final String isoData = ConfigDriveBuilder.buildConfigDrive(profile.getVmData(), ConfigDrive.CONFIGDRIVEFILENAME, profile.getConfigDriveLabel());

        HandleConfigDriveIsoCommand configDriveIsoCommand = new HandleConfigDriveIsoCommand(isoPath, isoData, secondaryStore.getTO(), true);
        Answer createIsoAnswer = endpoint.sendMessage(configDriveIsoCommand);
        if (!createIsoAnswer.getResult()) {
            throw new ResourceUnavailableException(String.format("Config drive iso creation failed, details: %s",
                    createIsoAnswer.getDetails()), ConfigDriveNetworkElement.class, 0L);
        }
        addConfigDriveDisk(profile, secondaryStore);

        return true;
    }

    private void addConfigDriveDisk(VirtualMachineProfile profile, DataStore secondaryStore) {
        boolean isoAvailable = false;
        String isoPath = ConfigDrive.CONFIGDRIVEDIR + "/" + profile.getInstanceName() + "/"  + ConfigDrive.CONFIGDRIVEFILENAME;
        for (DiskTO dataTo : profile.getDisks()) {
            if (dataTo.getPath().equals(isoPath)) {
                isoAvailable = true;
                break;
            }
        }
        if (!isoAvailable) {
            TemplateObjectTO dataTO = new TemplateObjectTO();
            dataTO.setDataStore(secondaryStore.getTO());
            dataTO.setUuid(profile.getUuid());
            dataTO.setPath(isoPath);
            dataTO.setFormat(Storage.ImageFormat.ISO);

            profile.addDisk(new DiskTO(dataTO, CONFIGDRIVEDISKSEQ.longValue(), isoPath, Volume.Type.ISO));
        } else {
            LOG.warn("An ISO is already in VM profile, unable to configure a config drive disk object in the VM profile.");
        }
    }

    private boolean configureConfigDriveData(final VirtualMachineProfile profile, final NicProfile nic) {
        final UserVmVO vm = _userVmDao.findById(profile.getId());
        if (vm.getType() != VirtualMachine.Type.User) {
            return false;
        }
        final Nic defaultNic = _networkModel.getDefaultNic(vm.getId());
        if (defaultNic != null) {
            final String sshPublicKey = getSshKey(profile);
            final String serviceOffering = _serviceOfferingDao.findByIdIncludingRemoved(vm.getId(), vm.getServiceOfferingId()).getDisplayText();
            boolean isWindows = _guestOSCategoryDao.findById(_guestOSDao.findById(vm.getGuestOSId()).getCategoryId()).getName().equalsIgnoreCase("Windows");

            final List<String[]> vmData = _networkModel.generateVmData(vm.getUserData(), serviceOffering, vm.getDataCenterId(), vm.getInstanceName(), vm.getHostName(), vm.getId(),
                    vm.getUuid(), nic.getIPv4Address(), sshPublicKey, (String) profile.getParameter(VirtualMachineProfile.Param.VmPassword), isWindows);
            profile.setVmData(vmData);
            profile.setConfigDriveLabel(VirtualMachineManager.VmConfigDriveLabel.value());
        }
        return true;
    }

}
