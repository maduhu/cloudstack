/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.cloud.storage;

import java.io.Serializable;

public class MigrationOptions implements Serializable {

    private String srcPoolUuid;
    private Storage.StoragePoolType srcPoolType;
    private Type type;
    private String srcBackingFilePath;
    private boolean copySrcTemplate;
    private String snapshotName;
    private String srcVolumeUuid;

    public enum Type {
        LinkedClone, FullClone
    }

    public MigrationOptions() {
    }

    public MigrationOptions(String srcPoolUuid, Storage.StoragePoolType srcPoolType, String srcBackingFilePath, boolean copySrcTemplate) {
        this.srcPoolUuid = srcPoolUuid;
        this.srcPoolType = srcPoolType;
        this.type = Type.LinkedClone;
        this.srcBackingFilePath = srcBackingFilePath;
        this.copySrcTemplate = copySrcTemplate;
    }

    public MigrationOptions(String srcPoolUuid, Storage.StoragePoolType srcPoolType, String snapshotName, String srcVolumeUuid) {
        this.srcPoolUuid = srcPoolUuid;
        this.srcPoolType = srcPoolType;
        this.type = Type.FullClone;
        this.snapshotName = snapshotName;
        this.srcVolumeUuid = srcVolumeUuid;
    }

    public String getSrcPoolUuid() {
        return srcPoolUuid;
    }

    public Storage.StoragePoolType getSrcPoolType() {
        return srcPoolType;
    }

    public String getSrcBackingFilePath() {
        return srcBackingFilePath;
    }

    public boolean isCopySrcTemplate() {
        return copySrcTemplate;
    }

    public String getSnapshotName() {
        return snapshotName;
    }

    public String getSrcVolumeUuid() {
        return srcVolumeUuid;
    }

    public Type getType() {
        return type;
    }
}