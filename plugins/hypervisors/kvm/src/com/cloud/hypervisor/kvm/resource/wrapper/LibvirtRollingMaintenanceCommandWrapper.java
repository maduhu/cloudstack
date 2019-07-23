//
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
//
package com.cloud.hypervisor.kvm.resource.wrapper;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.RollingMaintenanceAnswer;
import com.cloud.agent.api.RollingMaintenanceCommand;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.resource.RollingMaintenanceService;
import org.apache.cloudstack.rolling.maintenance.RollingMaintenanceHelper;
import org.apache.log4j.Logger;

import javax.inject.Inject;

@ResourceWrapper(handles =  RollingMaintenanceCommand.class)
public class LibvirtRollingMaintenanceCommandWrapper extends CommandWrapper<RollingMaintenanceCommand, Answer, LibvirtComputingResource> {

    private static final Logger s_logger = Logger.getLogger(LibvirtRollingMaintenanceCommandWrapper.class);

    private static final String ROLLING_MAINTENANCE_HOOKS_DIR = "/etc/cloudstack/agent/rolling.d";
    private static final String EXECUTOR = ROLLING_MAINTENANCE_HOOKS_DIR + "/rolling-maintenance.py";
    private static final String EXEC_FILE = ROLLING_MAINTENANCE_HOOKS_DIR + "/exec";
    private static final String DETAILS_FILE = ROLLING_MAINTENANCE_HOOKS_DIR + "/details";

    @Inject
    RollingMaintenanceHelper helper;

    private String getParameter(RollingMaintenanceService.Stage stage) {
        if (stage == RollingMaintenanceService.Stage.PreFlight) {
            return "preflight";
        } else if (stage == RollingMaintenanceService.Stage.PreMaintenance) {
            return "premaintenance";
        } else if (stage == RollingMaintenanceService.Stage.Maintenance) {
            return "maintenance";
        } else {
            return null;
        }
    }

    @Override
    public Answer execute(RollingMaintenanceCommand command, LibvirtComputingResource serverResource) {
        String payload = command.getCommand();
        String type = command.getType();
        RollingMaintenanceService.Stage stage = command.getStage();

        helper.startStage(getParameter(stage));

        return new RollingMaintenanceAnswer();
    }
}
