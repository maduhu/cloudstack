/*
 * Copyright 2016 ShapeBlue Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cloud.containercluster;

import javax.persistence.Column;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;

@Entity
@Table(name = "sb_ccs_container_cluster_vm_map")
public class ContainerClusterVmMapVO implements ContainerClusterVmMap {

    @Override
    public long getId() {
        return id;
    }

    @Override
    public long getClusterId() {
        return clusterId;

    }

    public void setClusterId(long clusterId) {

        this.clusterId = clusterId;
    }

    @Override
    public long getVmId() {
        return vmId;
    }

    public void setVmId(long vmId) {

        this.vmId = vmId;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    long id;

    @Column(name = "cluster_id")
    long clusterId;

    @Column(name = "vm_id")
    long vmId;

    public ContainerClusterVmMapVO() {

    }

    public ContainerClusterVmMapVO(long clusterId, long vmId) {
        this.vmId = vmId;
        this.clusterId = clusterId;
    }
}
