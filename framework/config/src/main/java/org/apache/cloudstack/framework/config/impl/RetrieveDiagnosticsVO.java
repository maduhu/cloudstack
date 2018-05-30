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

package org.apache.cloudstack.framework.config.impl;

import com.cloud.utils.crypt.DBEncryptionUtil;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "diagnosticsdata")
public class RetrieveDiagnosticsVO implements RetrieveDiagnostics {

    @Column(name = "role")
    private String role;

    @Column(name = "class")
    private String className;

    @Id
    @Column(name = "role_id")
    private String roleId;

    @Column(name = "value", length = 8191)
    private String value;


    protected RetrieveDiagnosticsVO() {
    }

    public RetrieveDiagnosticsVO(String role, String className, String value) {
        this.role = role;
        this.className = className;
        setValue(value);
    }

    public RetrieveDiagnosticsVO(String component, DiagnosticsKey key) {
        this(key.key(), key.getDiagnosticsClassType(), key.getDetail());
    }

/*    public RetrieveDiagnosticsVO(String roleId, String role, String className, String value) {
        this.role = role;
        this.className = className;
        this.value = value;
        setValue(value);
    }*/

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public void setDiagnosticsType(String className) {
        this.className = className;
    }

    public String getDefaultValue() {
        return value;
    }

    public void setDefaultValue(String className) {
        this.className = className;
    }

    public boolean isEncrypted() {
        return "Hidden".equals(getRole()) || "Secure".equals(getRole());
    }

    public String getDiagnosticsType() {
        return className;
    }

    @Override
    public String getValue() {
        if(isEncrypted()) {
            return DBEncryptionUtil.decrypt(value);
        } else {
            return value;
        }
    }

    public void setValue(String value) {
        if(isEncrypted()) {
            this.value = DBEncryptionUtil.encrypt(value);
        } else {
            this.value = value;
        }
    }


}
