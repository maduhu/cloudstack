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

import java.util.List;

import com.cloud.agent.api.routing.NetworkElementCommand;

public class PrepareFilesCommand extends NetworkElementCommand {
    List<String> filesToRetrieveList;
    String url;

    public PrepareFilesCommand(List<String> filesToRetrieve, String url) {
        this.filesToRetrieveList = filesToRetrieve;
        this.url = url;
    }

    public String getUrl() {
        return url;
    }

    public List<String> getFilesToRetrieveList() {
        return filesToRetrieveList;
    }

    @Override
    public boolean isQuery() {
        return true;
    }

}
