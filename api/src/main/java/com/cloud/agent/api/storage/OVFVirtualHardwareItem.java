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
package com.cloud.agent.api.storage;

// From: https://schemas.dmtf.org/wbem/wscim/1/cim-schema/2/CIM_ResourceAllocationSettingData.xsd
public class OVFVirtualHardwareItem {

    public enum HardwareResourceType {
        Other, ComputerSystem, Processor, Memory, IDEController, ParallelSCSIHBA, FC_HBA,
        iSCSI_HBA, IB_HCA, EthernetAdapter, OtherNetworkAdapter, IO_Slot, IO_Device,
        FloppyDrive, CDDrive, DVDdrive, DiskDrive, TapeDrive, StorageExtent, OtherStorageDevice, SerialPort,
        ParallelPort, USBController, GraphicsController, IEEE_1394_Controller, PartitionableUnit,
        BasePartitionableUnit, PowerSupply, CoolingDevice, PS2Controller, SIOController, Keyboard, PointingDevice,
        PCIController, DMTF_reserved, VendorReserved;
    }

    public enum CustomerVisibility {
         Unknown, PassedThrough, Virtualized, NotRepresented, DMTFReserved, VendorReserved;
    }

    public enum MappingBehavior {
        Unknown, NotSupported, Dedicated, SoftAffinity, HardAffinity, DMTFReserved, VendorReserved;
    }

    private String address;
    private String addressOnParent;
    private String allocationUnits;
    private boolean automaticAllocation;
    private boolean automaticDeallocation;
    private String caption;
    private String changeableType;
    private String componentSetting;
    private String configurationName;
    private String connection;
    private CustomerVisibility customerVisibility;
    private String description;
    private String elementName;
    private Long generation;
    private String hostResource;
    private String instanceId;
    private Long limit;
    private MappingBehavior mappingBehavior;
    private String otherResourceType;
    private String parent;
    private String poolId;
    private Long reservation;
    private String resourceSubtype;
    private HardwareResourceType resourceType;
    private String soId;
    private String soOrgId;
    private Long virtualQuantity;
    private String virtualQuantityUnits;
    private int weight;

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getAddressOnParent() {
        return addressOnParent;
    }

    public void setAddressOnParent(String addressOnParent) {
        this.addressOnParent = addressOnParent;
    }

    public String getAllocationUnits() {
        return allocationUnits;
    }

    public void setAllocationUnits(String allocationUnits) {
        this.allocationUnits = allocationUnits;
    }

    public boolean isAutomaticAllocation() {
        return automaticAllocation;
    }

    public void setAutomaticAllocation(boolean automaticAllocation) {
        this.automaticAllocation = automaticAllocation;
    }

    public boolean isAutomaticDeallocation() {
        return automaticDeallocation;
    }

    public void setAutomaticDeallocation(boolean automaticDeallocation) {
        this.automaticDeallocation = automaticDeallocation;
    }

    public String getCaption() {
        return caption;
    }

    public void setCaption(String caption) {
        this.caption = caption;
    }

    public String getChangeableType() {
        return changeableType;
    }

    public void setChangeableType(String changeableType) {
        this.changeableType = changeableType;
    }

    public String getComponentSetting() {
        return componentSetting;
    }

    public void setComponentSetting(String componentSetting) {
        this.componentSetting = componentSetting;
    }

    public String getConfigurationName() {
        return configurationName;
    }

    public void setConfigurationName(String configurationName) {
        this.configurationName = configurationName;
    }

    public String getConnection() {
        return connection;
    }

    public void setConnection(String connection) {
        this.connection = connection;
    }

    public CustomerVisibility getCustomerVisibility() {
        return customerVisibility;
    }

    public void setCustomerVisibility(CustomerVisibility customerVisibility) {
        this.customerVisibility = customerVisibility;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getElementName() {
        return elementName;
    }

    public void setElementName(String elementName) {
        this.elementName = elementName;
    }

    public Long getGeneration() {
        return generation;
    }

    public void setGeneration(Long generation) {
        this.generation = generation;
    }

    public String getHostResource() {
        return hostResource;
    }

    public void setHostResource(String hostResource) {
        this.hostResource = hostResource;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public Long getLimit() {
        return limit;
    }

    public void setLimit(Long limit) {
        this.limit = limit;
    }

    public MappingBehavior getMappingBehavior() {
        return mappingBehavior;
    }

    public void setMappingBehavior(MappingBehavior mappingBehavior) {
        this.mappingBehavior = mappingBehavior;
    }

    public String getOtherResourceType() {
        return otherResourceType;
    }

    public void setOtherResourceType(String otherResourceType) {
        this.otherResourceType = otherResourceType;
    }

    public String getParent() {
        return parent;
    }

    public void setParent(String parent) {
        this.parent = parent;
    }

    public String getPoolId() {
        return poolId;
    }

    public void setPoolId(String poolId) {
        this.poolId = poolId;
    }

    public Long getReservation() {
        return reservation;
    }

    public void setReservation(Long reservation) {
        this.reservation = reservation;
    }

    public String getResourceSubtype() {
        return resourceSubtype;
    }

    public void setResourceSubtype(String resourceSubtype) {
        this.resourceSubtype = resourceSubtype;
    }

    public HardwareResourceType getResourceType() {
        return resourceType;
    }

    public void setResourceType(HardwareResourceType resourceType) {
        this.resourceType = resourceType;
    }

    public String getSoId() {
        return soId;
    }

    public void setSoId(String soId) {
        this.soId = soId;
    }

    public String getSoOrgId() {
        return soOrgId;
    }

    public void setSoOrgId(String soOrgId) {
        this.soOrgId = soOrgId;
    }

    public Long getVirtualQuantity() {
        return virtualQuantity;
    }

    public void setVirtualQuantity(Long virtualQuantity) {
        this.virtualQuantity = virtualQuantity;
    }

    public String getVirtualQuantityUnits() {
        return virtualQuantityUnits;
    }

    public void setVirtualQuantityUnits(String virtualQuantityUnits) {
        this.virtualQuantityUnits = virtualQuantityUnits;
    }

    public int getWeight() {
        return weight;
    }

    public void setWeight(int weight) {
        this.weight = weight;
    }
}
