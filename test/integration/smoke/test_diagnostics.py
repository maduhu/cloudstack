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
""" BVT tests for remote diagnostics of system VMs
"""
# Import Local Modules
from marvin.codes import FAILED
from marvin.cloudstackTestCase import cloudstackTestCase
from marvin.cloudstackAPI import executeDiagnostics
from marvin.lib.utils import (cleanup_resources)
from marvin.lib.base import (Account,
                             ServiceOffering,
                             VirtualMachine)
from marvin.lib.common import (get_domain,
                               get_zone,
                               get_test_template,
                               list_ssvms,
                               list_routers)

from nose.plugins.attrib import attr


class TestRemoteDiagnostics(cloudstackTestCase):
    """
    Test remote diagnostics with system VMs and VR as root admin
    """

    @classmethod
    def setUpClass(cls):

        testClient = super(TestRemoteDiagnostics, cls).getClsTestClient()
        cls.apiclient = testClient.getApiClient()
        cls.services = testClient.getParsedTestDataConfig()

        # Get Zone, Domain and templates
        cls.domain = get_domain(cls.apiclient)
        cls.zone = get_zone(cls.apiclient, testClient.getZoneForTests())
        cls.hypervisor = testClient.getHypervisorInfo()
        cls.services['mode'] = cls.zone.networktype
        template = get_test_template(
            cls.apiclient,
            cls.zone.id,
            cls.hypervisor
        )
        if template == FAILED:
            cls.fail("get_test_template() failed to return template")

        cls.services["virtual_machine"]["zoneid"] = cls.zone.id

        # Create an account, network, VM and IP addresses
        cls.account = Account.create(
            cls.apiclient,
            cls.services["account"],
            domainid=cls.domain.id
        )
        cls.service_offering = ServiceOffering.create(
            cls.apiclient,
            cls.services["service_offerings"]["tiny"]
        )
        cls.vm_1 = VirtualMachine.create(
            cls.apiclient,
            cls.services["virtual_machine"],
            templateid=template.id,
            accountid=cls.account.name,
            domainid=cls.account.domainid,
            serviceofferingid=cls.service_offering.id
        )
        cls.cleanup = [
            cls.account,
            cls.service_offering
        ]

    @classmethod
    def tearDownClass(cls):
        try:
            cls.apiclient = super(
                TestRemoteDiagnostics,
                cls
            ).getClsTestClient().getApiClient()
            # Clean up, terminate the created templates
            cleanup_resources(cls.apiclient, cls.cleanup)

        except Exception as e:
            raise Exception("Warning: Exception during cleanup : %s" % e)

    def setUp(self):
        self.apiclient = self.testClient.getApiClient()
        self.hypervisor = self.testClient.getHypervisorInfo()

    @attr(tags=["advanced", "advancedns", "ssh", "smoke"], required_hardware="true")
    def test_01_ping_in_vr(self):
        '''
        Test Ping command execution in VR
        '''

        # Validate the following:
        # 1. Ping command is executed remotely on VR

        list_router_response = list_routers(
            self.apiclient
        )
        self.assertEqual(
            isinstance(list_router_response, list),
            True,
            "Check list response returns a valid list"
        )
        router = list_router_response[0]
        self.debug('Starting the router with ID: %s' % router.id)

        cmd = executeDiagnostics.executeDiagnosticsCmd()
        cmd.id = router.id
        cmd.ipaddress = '8.8.8.8'
        cmd.type = 'ping'
        cmd_response = self.apiclient.executeDiagnostics(cmd)

        self.assertEqual(
            '0',
            cmd_response.EXITCODE,
            'Failed to execute remote Ping in VR')

        # Validate Ping command execution with a non-existent/pingable IP address
        cmd.ipaddress = '9999.9999.9999.9999.9999'
        cmd_response = self.apiclient.executeDiagnostics(cmd)

        self.assertNotEqual(
            '0',
            cmd_response.EXITCODE,
            'Check diagnostics command returns a non-zero exit code')

    @attr(tags=["advanced", "advancedns", "ssh", "smoke"], required_hardware="true")
    def test_02_ping_in_ssvm(self):
        '''
        Test Ping command execution in SSVM
        '''

        # Validate the following:
        # 1. Ping command is executed remotely on SSVM

        list_ssvm_response = list_ssvms(
            self.apiclient,
            systemvmtype='secondarystoragevm',
            state='Running',
        )

        self.assertEqual(
            isinstance(list_ssvm_response, list),
            True,
            'Check list response returns a valid list'
        )
        ssvm = list_ssvm_response[0]

        self.debug('Setting up SSVM with ID %s' % ssvm.id)

        cmd = executeDiagnostics.executeDiagnosticsCmd()
        cmd.id = ssvm.id
        cmd.ipaddress = '8.8.8.8'
        cmd.type = 'ping'
        cmd_response = self.apiclient.executeDiagnostics(cmd)

        self.assertEqual(
            '0',
            cmd_response.EXITCODE,
            'Failed to execute remote Ping in SSVM'
        )

        # Validate Ping command execution with a non-existent/pingable IP address
        cmd.ipaddress = '9999.9999.9999.9999.9999'
        cmd_response = self.apiclient.executeDiagnostics(cmd)

        self.assertNotEqual(
            '0',
            cmd_response.EXITCODE,
            'Check diagnostics command returns a non-zero exit code'
        )

    @attr(tags=["advanced", "advancedns", "ssh", "smoke"], required_hardware="true")
    def test_03_ping_in_cpvm(self):
        '''
        Test Ping command execution in CPVM
        '''

        # Validate the following:
        # 1. Ping command is executed remotely on CPVM

        list_ssvm_response = list_ssvms(
            self.apiclient,
            systemvmtype='consoleproxy',
            state='Running',
        )

        self.assertEqual(
            isinstance(list_ssvm_response, list),
            True,
            'Check list response returns a valid list'
        )
        cpvm = list_ssvm_response[0]

        self.debug('Setting up CPVM with ID %s' % cpvm.id)

        cmd = executeDiagnostics.executeDiagnosticsCmd()
        cmd.id = cpvm.id
        cmd.ipaddress = '8.8.8.8'
        cmd.type = 'ping'
        cmd_response = self.apiclient.executeDiagnostics(cmd)

        self.assertEqual(
            '0',
            cmd_response.EXITCODE,
            'Failed to execute remote Ping in CPVM'
        )

        # Validate Ping command execution with a non-existent/pingable IP address
        cmd.ipaddress = '9999.9999.9999.9999.9999'
        cmd_response = self.apiclient.executeDiagnostics(cmd)

        self.assertNotEqual(
            '0',
            cmd_response.EXITCODE,
            'Check diagnostics command returns a non-zero exit code'
        )

    @attr(tags=["advanced", "advancedns", "ssh", "smoke"], required_hardware="true")
    def test_04_arping_in_vr(self):
        '''
        Test Arping command execution in VR
        '''

        # Validate the following:
        # 1. Arping command is executed remotely on VR

        list_router_response = list_routers(
            self.apiclient
        )
        self.assertEqual(
            isinstance(list_router_response, list),
            True,
            "Check list response returns a valid list"
        )
        router = list_router_response[0]
        self.debug('Starting the router with ID: %s' % router.id)

        cmd = executeDiagnostics.executeDiagnosticsCmd()
        cmd.id = router.id
        cmd.ipaddress = router.gateway
        cmd.type = 'arping'
        cmd.params = "-I eth2"
        cmd_response = self.apiclient.executeDiagnostics(cmd)

        self.assertEqual(
            '0',
            cmd_response.EXITCODE,
            'Failed to execute remote Arping in VR')

        # Validate Arping command execution with a non-existent/pingable IP address
        cmd.ipaddress = '8.8.8.8'
        cmd_response = self.apiclient.executeDiagnostics(cmd)

        self.assertNotEqual(
            '0',
            cmd_response.EXITCODE,
            'Check diagnostics command returns a non-zero exit code')

    @attr(tags=["advanced", "advancedns", "ssh", "smoke"], required_hardware="true")
    def test_05_arping_in_ssvm(self):
        '''
        Test Arping command execution in SSVM
        '''

        # Validate the following:
        # 1. Arping command is executed remotely on SSVM

        list_ssvm_response = list_ssvms(
            self.apiclient,
            systemvmtype='secondarystoragevm',
            state='Running',
        )

        self.assertEqual(
            isinstance(list_ssvm_response, list),
            True,
            'Check list response returns a valid list'
        )
        ssvm = list_ssvm_response[0]

        self.debug('Setting up SSVM with ID %s' % ssvm.id)

        cmd = executeDiagnostics.executeDiagnosticsCmd()
        cmd.id = ssvm.id
        cmd.ipaddress = ssvm.gateway
        cmd.type = 'arping'
        cmd.params = '-I eth2'
        cmd_response = self.apiclient.executeDiagnostics(cmd)

        self.assertEqual(
            '0',
            cmd_response.EXITCODE,
            'Failed to execute remote Arping in SSVM'
        )

        # Validate Arping command execution with a non-existent/pingable IP address
        cmd.ipaddress = '8.8.8.8'
        cmd_response = self.apiclient.executeDiagnostics(cmd)

        self.assertNotEqual(
            '0',
            cmd_response.EXITCODE,
            'Check diagnostics command returns a non-zero exit code'
        )

    @attr(tags=["advanced", "advancedns", "ssh", "smoke"], required_hardware="true")
    def test_06_arping_in_cpvm(self):
        '''
        Test Arping command execution in CPVM
        '''

        # Validate the following:
        # 1. Arping command is executed remotely on CPVM

        list_cpvm_response = list_ssvms(
            self.apiclient,
            systemvmtype='secondarystoragevm',
            state='Running',
        )

        self.assertEqual(
            isinstance(list_cpvm_response, list),
            True,
            'Check list response returns a valid list'
        )
        cpvm = list_cpvm_response[0]

        self.debug('Setting up CPVM with ID %s' % cpvm.id)

        cmd = executeDiagnostics.executeDiagnosticsCmd()
        cmd.id = cpvm.id
        cmd.ipaddress = cpvm.gateway
        cmd.type = 'arping'
        cmd.params = '-I eth2'
        cmd_response = self.apiclient.executeDiagnostics(cmd)

        self.assertEqual(
            '0',
            cmd_response.EXITCODE,
            'Failed to execute remote Arping in CPVM'
        )

        # Validate Arping command execution with a non-existent/pingable IP address
        cmd.ipaddress = '8.8.8.8'
        cmd_response = self.apiclient.executeDiagnostics(cmd)

        self.assertNotEqual(
            '0',
            cmd_response.EXITCODE,
            'Check diagnostics command returns a non-zero exit code'
        )

    @attr(tags=["advanced", "advancedns", "ssh", "smoke"], required_hardware="true")
    def test_07_traceroute_in_vr(self):
        '''
        Test Arping command execution in VR
        '''

        # Validate the following:
        # 1. Arping command is executed remotely on VR

        list_router_response = list_routers(
            self.apiclient
        )
        self.assertEqual(
            isinstance(list_router_response, list),
            True,
            "Check list response returns a valid list"
        )
        router = list_router_response[0]
        self.debug('Starting the router with ID: %s' % router.id)

        cmd = executeDiagnostics.executeDiagnosticsCmd()
        cmd.id = router.id
        cmd.ipaddress = '8.8.4.4'
        cmd.type = 'traceroute'
        cmd.params = "-m 10"
        cmd_response = self.apiclient.executeDiagnostics(cmd)

        self.assertEqual(
            '0',
            cmd_response.EXITCODE,
            'Failed to execute remote Arping in VR')

        # Validate Arping command execution with a non-existent/pingable IP address
        cmd.ipaddress = '9999.99999.99999.9999'
        cmd_response = self.apiclient.executeDiagnostics(cmd)

        self.assertNotEqual(
            '0',
            cmd_response.EXITCODE,
            'Check diagnostics command returns a non-zero exit code'
        )

    @attr(tags=["advanced", "advancedns", "ssh", "smoke"], required_hardware="true")
    def test_08_traceroute_in_ssvm(self):
        '''
        Test Traceroute command execution in SSVM
        '''

        # Validate the following:
        # 1. Traceroute command is executed remotely on SSVM

        list_ssvm_response = list_ssvms(
            self.apiclient,
            systemvmtype='secondarystoragevm',
            state='Running',
        )

        self.assertEqual(
            isinstance(list_ssvm_response, list),
            True,
            'Check list response returns a valid list'
        )
        ssvm = list_ssvm_response[0]

        self.debug('Setting up SSVM with ID %s' % ssvm.id)

        cmd = executeDiagnostics.executeDiagnosticsCmd()
        cmd.id = ssvm.id
        cmd.ipaddress = '8.8.4.4'
        cmd.type = 'traceroute'
        cmd.params = '-m 10'
        cmd_response = self.apiclient.executeDiagnostics(cmd)

        self.assertEqual(
            '0',
            cmd_response.EXITCODE,
            'Failed to execute remote Traceroute in SSVM'
        )

        # Validate Traceroute command execution with a non-existent/pingable IP address
        cmd.ipaddress = '999.9999.9999.9999.9999'
        cmd_response = self.apiclient.executeDiagnostics(cmd)

        self.assertNotEqual(
            '0',
            cmd_response.EXITCODE,
            'Check diagnostics command returns a non-zero exit code'
        )

    @attr(tags=["advanced", "advancedns", "ssh", "smoke"], required_hardware="true")
    def test_09_traceroute_in_cpvm(self):
        '''
        Test Traceroute command execution in CPVMM
        '''

        # Validate the following:
        # 1. Traceroute command is executed remotely on CPVM

        list_cpvm_response = list_ssvms(
            self.apiclient,
            systemvmtype='consoleproxy',
            state='Running',
        )

        self.assertEqual(
            isinstance(list_cpvm_response, list),
            True,
            'Check list response returns a valid list'
        )
        cpvm = list_cpvm_response[0]

        self.debug('Setting up CPVMM with ID %s' % cpvm.id)

        cmd = executeDiagnostics.executeDiagnosticsCmd()
        cmd.id = cpvm.id
        cmd.ipaddress = '8.8.4.4'
        cmd.type = 'traceroute'
        cmd.params = '-m 10'
        cmd_response = self.apiclient.executeDiagnostics(cmd)

        self.assertEqual(
            '0',
            cmd_response.EXITCODE,
            'Failed to execute remote Traceroute in CPVM'
        )

        # Validate Traceroute command execution with a non-existent/pingable IP address
        cmd.ipaddress = '999.9999.9999.9999.9999'
        cmd_response = self.apiclient.executeDiagnostics(cmd)

        self.assertNotEqual(
            '0',
            cmd_response.EXITCODE,
            'Check diagnostics command returns a non-zero exit code'
        )