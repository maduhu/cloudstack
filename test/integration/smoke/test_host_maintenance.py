# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
""" BVT tests for Hosts Maintenance
"""

# Import Local Modules
from marvin.cloudstackTestCase import *
from marvin.lib.utils import *
from marvin.lib.base import *
from marvin.lib.common import (get_zone, get_pod, get_template, list_ssvms)
from nose.plugins.attrib import attr
from marvin.lib.decoratorGenerators import skipTestIf
from distutils.util import strtobool
from marvin.sshClient import SshClient

_multiprocess_shared_ = False
MIN_VMS_FOR_TEST = 3

class TestHostMaintenanceBase(cloudstackTestCase):
    def get_ssh_client(self, ip, username, password, retries=10):
        """ Setup ssh client connection and return connection """
        try:
            ssh_client = SshClient(ip, 22, username, password, retries)
        except Exception as e:
            raise unittest.SkipTest("Unable to create ssh connection: " % e)

        self.assertIsNotNone(
            ssh_client, "Failed to setup ssh connection to ip=%s" % ip)

        return ssh_client

    def wait_until_host_is_in_state(self, hostid, resourcestate, interval=3, retries=20):
        def check_resource_state():
            response = Host.list(
                self.apiclient,
                id=hostid
            )
            if isinstance(response, list):
                if response[0].resourcestate == resourcestate:
                    self.logger.debug('Host with id %s is in resource state = %s' % (hostid, resourcestate))
                    return True, None
                else:
                    self.logger.debug("Waiting for host " + hostid +
                                      " to reach state " + resourcestate +
                                      ", with current state " + response[0].resourcestate)
            return False, None

        done, _ = wait_until(interval, retries, check_resource_state)
        if not done:
            raise Exception("Failed to wait for host %s to be on resource state %s" % (hostid, resourcestate))
        return True

    def prepare_host_for_maintenance(self, hostid):
        self.logger.debug("Sending Host with id %s to prepareHostForMaintenance" % hostid)
        cmd = prepareHostForMaintenance.prepareHostForMaintenanceCmd()
        cmd.id = hostid
        response = self.apiclient.prepareHostForMaintenance(cmd)
        self.logger.debug("Host with id %s is in prepareHostForMaintenance" % hostid)
        self.logger.debug(response)
        return response

    def cancel_host_maintenance(self, hostid):
        self.logger.debug("Canceling Host with id %s from maintain" % hostid)
        cmd = cancelHostMaintenance.cancelHostMaintenanceCmd()
        cmd.id = hostid
        res = self.apiclient.cancelHostMaintenance(cmd)
        self.logger.debug("Host with id %s is cancelling maintenance" % hostid)
        return res


class TestHostMaintenance(TestHostMaintenanceBase):

    def setUp(self):
        self.logger = logging.getLogger('TestHM')
        self.stream_handler = logging.StreamHandler()
        self.logger.setLevel(logging.DEBUG)
        self.logger.addHandler(self.stream_handler)
        self.apiclient = self.testClient.getApiClient()
        self.hypervisor = self.testClient.getHypervisorInfo()
        self.dbclient = self.testClient.getDbConnection()
        self.services = self.testClient.getParsedTestDataConfig()
        self.zone = get_zone(self.apiclient, self.testClient.getZoneForTests())
        self.pod = get_pod(self.apiclient, self.zone.id)
        self.cleanup = []
        self.ssh_client = None
        self.needs_unblock_iptables = False
        self.hostIdToCancelMaintenance = None
        self.hostConfig = self.config.__dict__["zones"][0].__dict__["pods"][0].__dict__["clusters"][0].__dict__["hosts"][0].__dict__


    def tearDown(self):
        try:
            if self.ssh_client is not None and self.needs_unblock_iptables:
                self.ssh_client.execute("iptables -D OUTPUT -j REJECT -m state --state NEW -m tcp -p tcp --dport 49152:49215 -m comment --comment 'test block migrations'")
                self.ssh_client.execute("iptables -D OUTPUT -j REJECT -m state --state NEW -m tcp -p tcp --dport 16509 -m comment --comment 'test block migrations'")
            try:
                if self.hostIdToCancelMaintenance is not None:
                    self.cancel_host_maintenance(self.hostIdToCancelMaintenance)
            except Exception as e:
                self.logger.debug("Attempted host maintenance cancel but it threw exception. Skipping.")

            self.hostIdToCancelMaintenance = None

            # Clean up, terminate the created templates
            cleanup_resources(self.apiclient, self.cleanup)

        except Exception as e:
            raise Exception("Warning: Exception during cleanup : %s" % e)

        return
    
    def createVMs(self, hostId, number):
        
        self.template = get_template(
            self.apiclient,
            self.zone.id,
            self.hypervisor
        )
            
        if self.template == FAILED:
            assert False, "get_template() failed to return template"
            
        self.logger.debug("Using template %s " % self.template.id)
                
        self.service_offering = ServiceOffering.create(
            self.apiclient,
            self.services["service_offerings"]["tiny"]
        )
        self.logger.debug("Using service offering %s " % self.service_offering.id)
        self.network_offering = NetworkOffering.create(
            self.apiclient,
            self.services["l2-network_offering"],
        )
        self.network_offering.update(self.apiclient, state='Enabled')
        self.services["network"]["networkoffering"] = self.network_offering.id
        self.l2_network = Network.create(
            self.apiclient,
            self.services["l2-network"],
            zoneid=self.zone.id,
            networkofferingid=self.network_offering.id
        )

        vms=[]
        for i in range(0, number):
            self.services["virtual_machine"]["zoneid"] = self.zone.id
            self.services["virtual_machine"]["template"] = self.template.id
            self.services["virtual_machine"]["displayname"] = 'vm' + str(i)
            self.services["virtual_machine"]["hypervisor"] = self.hypervisor
            vm = VirtualMachine.create(
                self.apiclient,
                self.services["virtual_machine"],
                serviceofferingid=self.service_offering.id,
                networkids=self.l2_network.id,
                hostid=hostId
            )
            vms.append(vm)
            self.cleanup.append(vm)
            self.logger.debug("VM create = {}".format(vm.id))
        self.cleanup.append(self.l2_network)
        self.cleanup.append(self.network_offering)
        self.cleanup.append(self.service_offering)
        return vms

    def checkAllVmsRunningOnHost(self, hostId):
        listVms1 = VirtualMachine.list(
            self.apiclient,
            hostid=hostId
        )

        if (listVms1 is not None):
            self.logger.debug('Vms found to test all running = {} '.format(len(listVms1)))
            for vm in listVms1:
                if (vm.state != "Running"):
                    self.logger.debug('VirtualMachine on Host with id = {} is in {}'.format(vm.id, vm.state))
                    return (False, None)

        response = list_ssvms(
            self.apiclient,
            hostid=hostId
        )
        if isinstance(response, list):
            for systemvm in response:
                if systemvm.state != 'Running':
                    self.logger.debug("Found not running VM {}".format(systemvm.name))
                    return (False, None)

        return (True, None)

    def checkVmMigratingOnHost(self, hostId):
        vm_migrating=False
        listVms1 = VirtualMachine.list(
                                   self.apiclient, 
                                   hostid=hostId
                                   )

        if (listVms1 is not None):
            self.logger.debug('Vms found = {} '.format(len(listVms1)))
            for vm in listVms1:
                if (vm.state == "Migrating"):
                    self.logger.debug('VirtualMachine on Host with id = {} is in {}'.format(vm.id, vm.state))
                    vm_migrating=True
                    break

        return (vm_migrating, None)
    
    def migrationsFinished(self, hostId):
        migrations_finished=True
        listVms1 = VirtualMachine.list(
                                   self.apiclient, 
                                   hostid=hostId
                                   )

        if (listVms1 is not None):
            numVms = len(listVms1)
            migrations_finished = (numVms == 0)

        return (migrations_finished, None)

    def noOfVMsOnHost(self, hostId):
        listVms = VirtualMachine.list(
                                       self.apiclient, 
                                       hostid=hostId
                                       )
        no_of_vms=0
        self.logger.debug("Counting VMs on host " + hostId)
        if (listVms is not None):
            for vm in listVms:
                self.logger.debug("VirtualMachine on Host " + hostId + " = " + vm.id)
                no_of_vms=no_of_vms+1
        self.logger.debug("Found VMs on host " + str(no_of_vms))
        return no_of_vms

    def hostPrepareAndCancelMaintenance(self, target_host_id, other_host_id):
        # Wait for all VMs to complete any pending migrations.
        if not wait_until(3, 100, self.checkAllVmsRunningOnHost, target_host_id) or \
                not wait_until(3, 100, self.checkAllVmsRunningOnHost, other_host_id):
            raise Exception("Failed to wait for all VMs to reach running state to execute test")

        expected_vm_count_after_maintenance = self.noOfVMsOnHost(target_host_id) + self.noOfVMsOnHost(other_host_id)

        self.prepare_host_for_maintenance(target_host_id)
        migrations_finished = wait_until(5, 200, self.migrationsFinished, target_host_id)

        self.wait_until_host_is_in_state(target_host_id, "Maintenance", 5, 200)
        self.hostIdToCancelMaintenance = target_host_id

        vm_count_after_maintenance = self.noOfVMsOnHost(target_host_id)

        self.cancel_host_maintenance(target_host_id)
        host_reached_enabled = self.wait_until_host_is_in_state(target_host_id, "Enabled", 5, 200)
        if host_reached_enabled:
            self.hostIdToCancelMaintenance = None

        if vm_count_after_maintenance != 0:
            self.fail("Host to put to maintenance still has VMs running")

        return migrations_finished

    @attr(
        tags=[
            "advanced",
            "advancedns",
            "smoke",
            "basic",
            "eip",
            "sg"],
        required_hardware="true")
    def test_01_cancel_host_maintenace_with_no_migration_jobs(self):
        listHost = Host.list(
            self.apiclient,
            type='Routing',
            zoneid=self.zone.id,
            podid=self.pod.id,
        )
        for host in listHost:
            self.logger.debug('1 Hypervisor = {}'.format(host.id))


        if (len(listHost) < 2):
            raise unittest.SkipTest("Cancel host maintenance when VMs are migrating should be tested for 2 or more hosts")

        migrations_finished = True

        try:

            migrations_finished = self.hostPrepareAndCancelMaintenance(listHost[0].id, listHost[1].id)

            if migrations_finished:
                migrations_finished = self.hostPrepareAndCancelMaintenance(listHost[1].id, listHost[0].id)

        except Exception as e:
            self.logger.debug("Exception {}".format(e))
            self.fail("Cancel host maintenance failed {}".format(e[0]))


        if not migrations_finished:
            raise unittest.SkipTest("VMs are still migrating and the test will not be able to check the conditions the test is intended for")


    @attr(
        tags=[
            "advanced",
            "advancedns",
            "smoke",
            "basic",
            "eip",
            "sg"],
        required_hardware="true")
    def test_02_cancel_host_maintenace_with_migration_jobs(self):

        listHost = Host.list(
            self.apiclient,
            type='Routing',
            zoneid=self.zone.id,
            podid=self.pod.id,
        )
        for host in listHost:
            self.logger.debug('2 Hypervisor = {}'.format(host.id))

        if (len(listHost) < 2):
            raise unittest.SkipTest("Cancel host maintenance when VMs are migrating can only be tested with 2 hosts")

        no_of_vms = self.noOfVMsOnHost(listHost[0].id)

        no_of_vms = no_of_vms + self.noOfVMsOnHost(listHost[1].id)

        if no_of_vms < MIN_VMS_FOR_TEST:
            self.logger.debug("Create VMs as there are not enough vms to check host maintenance")
            no_vm_req = MIN_VMS_FOR_TEST - no_of_vms
            if (no_vm_req > 0):
                self.logger.debug("Creating vms = {}".format(no_vm_req))
                self.vmlist = self.createVMs(listHost[0].id, no_vm_req)

        migrations_finished = True

        try:
            migrations_finished = self.hostPrepareAndCancelMaintenance(listHost[0].id, listHost[1].id)

            if migrations_finished:
                migrations_finished = self.hostPrepareAndCancelMaintenance(listHost[1].id, listHost[0].id)

        except Exception as e:
            self.logger.debug("Exception {}".format(e))
            self.fail("Cancel host maintenance failed {}".format(e[0]))


        if (migrations_finished == False):
            raise unittest.SkipTest("VMs are still migrating and the test will not be able to check the conditions the test is intended for")

    @attr(
        tags=[
            "advanced",
            "advancedns",
            "smoke",
            "basic",
            "eip",
            "sg"],
        required_hardware="true")
    def test_03_cancel_host_maintenace_with_migration_jobs_ports_blocked(self):

        listHost = Host.list(
            self.apiclient,
            type='Routing',
            zoneid=self.zone.id,
            podid=self.pod.id,
        )
        for host in listHost:
            self.logger.debug('2 Hypervisor = {}'.format(host.id))

        if (len(listHost) < 2):
            raise unittest.SkipTest("Cancel host maintenance when VMs are migrating can only be tested with 2 hosts");
            return

        target_host_id = listHost[0].id
        other_host_id = listHost[1].id

        no_of_vms = self.noOfVMsOnHost(target_host_id)

        # Need only 2 VMs for this case.
        if no_of_vms < 2:
            self.logger.debug("Create VMs as there are not enough vms to check host maintenance")
            no_vm_req = 2 - no_of_vms
            if (no_vm_req > 0):
                self.logger.debug("Creating vms = {}".format(no_vm_req))
                self.vmlist = self.createVMs(listHost[0].id, no_vm_req)

        self.ssh_client = self.get_ssh_client(listHost[0].ipaddress, self.hostConfig["username"], self.hostConfig["password"])
        self.ssh_client.execute("iptables -I OUTPUT -j REJECT -m state --state NEW -m tcp -p tcp --dport 49152:49215 -m comment --comment 'test block migrations'")
        self.ssh_client.execute("iptables -I OUTPUT -j REJECT -m state --state NEW -m tcp -p tcp --dport 16509 -m comment --comment 'test block migrations'")

        self.needs_unblock_iptables = True

        # Attempt putting host in maintenance and check if ErrorInMaintenance state is reached
        self.prepare_host_for_maintenance(target_host_id)
        error_in_maintenance_reached = self.wait_until_host_is_in_state(target_host_id, "ErrorInMaintenance", 5, 200)
        self.hostIdToCancelMaintenance = target_host_id

        self.cancel_host_maintenance(target_host_id)
        host_reached_enabled = self.wait_until_host_is_in_state(target_host_id, "Enabled", 5, 200)
        if host_reached_enabled:
            self.hostIdToCancelMaintenance = None

        if error_in_maintenance_reached == False:
            self.fail("Error in maintenance state should have reached after ports block")


class TestHostMaintenanceAgents(TestHostMaintenanceBase):

    @classmethod
    def setUpClass(cls):
        cls.testClient = super(TestHostMaintenanceAgents, cls).getClsTestClient()
        cls.apiclient = cls.testClient.getApiClient()
        cls.hypervisor = cls.testClient.getHypervisorInfo()
        cls.dbclient = cls.testClient.getDbConnection()
        cls.zone = get_zone(cls.apiclient, cls.testClient.getZoneForTests())
        cls.pod = get_pod(cls.apiclient, cls.zone.id)
        cls.services = cls.testClient.getParsedTestDataConfig()

        cls.logger = logging.getLogger('TestHMAgents')
        cls.stream_handler = logging.StreamHandler()
        cls.logger.setLevel(logging.DEBUG)
        cls.logger.addHandler(cls.stream_handler)

        cls._cleanup = []
        cls.hypervisorNotSupported = False
        if cls.hypervisor.lower() not in ['kvm', 'lxc']:
            cls.hypervisorNotSupported = True

        if not cls.hypervisorNotSupported:
            cls.initialsshvalue = cls.is_ssh_enabled()
            cls.template = get_template(
                cls.apiclient,
                cls.zone.id,
                cls.hypervisor
            )
            cls.services["virtual_machine"]["zoneid"] = cls.zone.id
            cls.services["virtual_machine"]["template"] = cls.template.id
            cls.services["virtual_machine"]["hypervisor"] = cls.hypervisor
            cls.service_offering = ServiceOffering.create(
                cls.apiclient,
                cls.services["service_offerings"]["tiny"]
            )
            cls._cleanup.append(cls.service_offering)
            cls.network_offering = NetworkOffering.create(
                cls.apiclient,
                cls.services["l2-network_offering"],
            )
            cls.network_offering.update(cls.apiclient, state='Enabled')
            cls.services["network"]["networkoffering"] = cls.network_offering.id
            cls.l2_network = Network.create(
                cls.apiclient,
                cls.services["l2-network"],
                zoneid=cls.zone.id,
                networkofferingid=cls.network_offering.id
            )
            cls._cleanup.append(cls.l2_network)
            cls._cleanup.append(cls.network_offering)

        cls.hostConfig = cls.config.__dict__["zones"][0].__dict__["pods"][0].__dict__["clusters"][0].__dict__["hosts"][0].__dict__


    @classmethod
    def tearDownClass(cls):
        try:
            if not cls.hypervisorNotSupported:
                # Revert setting value to the original
                cls.set_ssh_enabled(cls.initialsshvalue)
            cleanup_resources(cls.apiclient, cls._cleanup)
        except Exception as e:
            raise Exception("Warning: Exception during cleanup : %s" % e)

    def setUp(self):
        if not self.hypervisorNotSupported:
            self.host = self.get_enabled_host_connected_agent()
        self.cleanup = []

    def tearDown(self):
        try:
            cleanup_resources(self.apiclient, self.cleanup)
        except Exception as e:
            raise Exception("Warning: Exception during cleanup : %s" % e)


    @classmethod
    def is_ssh_enabled(cls):
        conf = Configurations.list(cls.apiclient, name="kvm.ssh.to.agent")
        if not conf:
            return False
        else:
            return bool(strtobool(conf[0].value)) if conf[0].value else False

    @classmethod
    def updateConfiguration(self, name, value):
        cmd = updateConfiguration.updateConfigurationCmd()
        cmd.name = name
        cmd.value = value
        self.apiclient.updateConfiguration(cmd)

    @classmethod
    def set_ssh_enabled(cls, on):
        value = "true" if on else "false"
        cls.updateConfiguration('kvm.ssh.to.agent', value)

    def wait_until_agent_is_in_state(self, hostid, state, interval=3, retries=20):
        def check_agent_state():
            response = Host.list(
                self.apiclient,
                id=hostid
            )
            if isinstance(response, list):
                if response[0].state == state:
                    self.logger.debug('Host agent with id %s is in state = %s' % (hostid, state))
                    return True, None
            return False, None

        done, _ = wait_until(interval, retries, check_agent_state)
        if not done:
            raise Exception("Failed to wait for host agent %s to be on state %s" % (hostid, state))
        return True

    def get_enabled_host_connected_agent(self):
        hosts = Host.list(
            self.apiclient,
            type='Routing',
            zoneid=self.zone.id,
            podid=self.pod.id,
            hypervisor=self.hypervisor,
            resourcestate='Enabled',
            state='Up'
        )
        if len(hosts) < 2:
            raise unittest.SkipTest("Cancel host maintenance must be tested for 2 or more hosts")
        return hosts[0]

    def deploy_vm_on_host(self, hostid):
        return VirtualMachine.create(
            self.apiclient,
            self.services["virtual_machine"],
            serviceofferingid=self.service_offering.id,
            networkids=self.l2_network.id,
            hostid=hostid
        )

    def assert_host_is_functional_after_cancelling_maintenance(self, hostid):
        self.wait_until_agent_is_in_state(hostid, "Up")
        self.logger.debug('Deploying VM on host %s' % hostid)
        vm = self.deploy_vm_on_host(hostid)
        self.assertEqual(
            vm.state,
            "Running",
            "Check VM is running on the host"
        )
        self.cleanup.append(vm)

    def revert_host_state_on_failure(self, host):
        cmd = updateHost.updateHostCmd()
        cmd.id = host.id
        cmd.allocationstate = "Enable"
        response = self.apiclient.updateHost(cmd)
        self.assertEqual(response.resourcestate, "Enabled")

    @skipTestIf("hypervisorNotSupported")
    @attr(tags=["advanced", "advancedns", "smoke", "basic", "eip", "sg"], required_hardware="true")
    def test_01_cancel_host_maintenance_ssh_enabled_agent_connected(self):
        """
        Test cancel maintenance when: 'kvm.ssh.to.agent' = true, agent state = 'Up'

        1) Put host on Maintenance
        2) Cancel maintenance on host
        4) Assert agent is still connected after cancelling maintenance
        3) Deploy VM on the host after cancelling maintenance
        """

        if not self.is_ssh_enabled():
            self.set_ssh_enabled(True)

        try:
            self.prepare_host_for_maintenance(self.host.id)
            self.wait_until_host_is_in_state(self.host.id, "Maintenance")
            self.cancel_host_maintenance(self.host.id)
            self.wait_until_host_is_in_state(self.host.id, "Enabled")
            self.assert_host_is_functional_after_cancelling_maintenance(self.host.id)
        except Exception as e:
            self.revert_host_state_on_failure(self.host)
            self.fail(e)

    @skipTestIf("hypervisorNotSupported")
    @attr(tags=["boris", "advancedns", "smoke", "basic", "eip", "sg"], required_hardware="true")
    def test_02_cancel_host_maintenance_ssh_enabled_agent_disconnected(self):
        """
        Test cancel maintenance when: 'kvm.ssh.to.agent' = true, agent state != 'Up'

        1) Put host on maintenance
        2) SSH into host and stop cloudstack-agent service - host gets Disconnected
        3) Cancel maintenance on host
        4) Assert agent is connected after cancelling maintenance
        5) Deploy VM on the host
        """

        if not self.is_ssh_enabled():
            self.set_ssh_enabled(True)
        # username, password = self.get_host_credentials(self.host.id)
        username = self.hostConfig["username"]
        password = self.hostConfig["password"]

        try:
            self.prepare_host_for_maintenance(self.host.id)
            self.wait_until_host_is_in_state(self.host.id, "Maintenance")

            ssh_client = self.get_ssh_client(self.host.ipaddress, self.hostConfig["username"],
                  self.hostConfig["password"])
            ssh_client.execute("service cloudstack-agent stop")
            self.wait_until_agent_is_in_state(self.host.id, "Disconnected")

            self.cancel_host_maintenance(self.host.id)
            self.wait_until_host_is_in_state(self.host.id, "Enabled")

            self.assert_host_is_functional_after_cancelling_maintenance(self.host.id)
        except Exception as e:
            self.revert_host_state_on_failure(self.host)
            self.fail(e)

    @skipTestIf("hypervisorNotSupported")
    @attr(tags=["advanced", "advancedns", "smoke", "basic", "eip", "sg"], required_hardware="true")
    def test_03_cancel_host_maintenance_ssh_disabled_agent_connected(self):
        """
        Test cancel maintenance when: 'kvm.ssh.to.agent' = false, agent state = 'Up'

        1) Put host on Maintenance
        2) Cancel maintenance on host
        4) Assert agent is still connected after cancelling maintenance
        3) Deploy VM on the host after cancelling maintenance
        """

        if self.is_ssh_enabled():
            self.set_ssh_enabled(False)

        try:
            self.prepare_host_for_maintenance(self.host.id)
            self.wait_until_host_is_in_state(self.host.id, "Maintenance")
            self.cancel_host_maintenance(self.host.id)
            self.wait_until_host_is_in_state(self.host.id, "Enabled")
            self.assert_host_is_functional_after_cancelling_maintenance(self.host.id)
        except Exception as e:
            self.revert_host_state_on_failure(self.host)
            self.fail(e)

    @skipTestIf("hypervisorNotSupported")
    @attr(tags=["advanced", "advancedns", "smoke", "basic", "eip", "sg"], required_hardware="true")
    def test_04_cancel_host_maintenance_ssh_disabled_agent_disconnected(self):
        """
        Test cancel maintenance when: 'kvm.ssh.to.agent' = false, agent state != 'Up'

        1) Put host on maintenance
        2) SSH into host (if possible) and stop cloudstack-agent service - host gets Disconnected.
           Skip test if not possible to SSH into host
        3) Cancel maintenance on host - assert cannot cancel maintenance on disconnected host (exception thwown)
        4( SSH into host and start cloudstack-agent service - host gets connected
        5) Cancel maintenance on host
        4) Assert agent is connected after cancelling maintenance
        5) Deploy VM on the host
        """

        if self.is_ssh_enabled():
            self.set_ssh_enabled(False)

        try:
            self.prepare_host_for_maintenance(self.host.id)
            self.wait_until_host_is_in_state(self.host.id, "Maintenance")

            ssh_client = self.get_ssh_client(self.host.ipaddress, self.hostConfig["username"],
                  self.hostConfig["password"])
            ssh_client.execute("service cloudstack-agent stop")
            self.wait_until_agent_is_in_state(self.host.id, "Disconnected")
        except Exception as e:
            self.revert_host_state_on_failure(self.host)
            self.fail(e)

        self.assertRaises(Exception, self.cancel_host_maintenance, self.host.id)

        try:
            ssh_client = self.get_ssh_client(self.host.ipaddress, self.hostConfig["username"],
                  self.hostConfig["password"])
            ssh_client.execute("service cloudstack-agent start")
            self.wait_until_agent_is_in_state(self.host.id, "Up")

            self.cancel_host_maintenance(self.host.id)
            self.wait_until_host_is_in_state(self.host.id, "Enabled")
            self.assert_host_is_functional_after_cancelling_maintenance(self.host.id)
        except Exception as e:
            self.revert_host_state_on_failure(self.host)
            self.fail(e)
