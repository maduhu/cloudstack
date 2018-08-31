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
package org.apache.cloudstack.diagnostics;

import org.apache.cloudstack.api.command.admin.diagnostics.GetDiagnosticsDataCmd;
import org.apache.cloudstack.api.command.admin.diagnostics.RunDiagnosticsCmd;

import java.util.Map;

public interface DiagnosticsService {

    /**
     * network utility method to execute ICMP commands in system vms
     * @params to method acquired from API command
     *
     * @TODO method is too long, need to refactor a lot of code to smaller methods or helper class
     */
    Map<String, String> runDiagnosticsCommand(RunDiagnosticsCmd cmd);


    /**
     * method to retrieve diagnostics data files from system vms
     *
     * @params to method are passed by the API command
     *
     * @TODO split the implementation to distinct method
     * method needs to do one thing, and one thing only
     */
    String getDiagnosticsDataCommand(GetDiagnosticsDataCmd cmd);

}