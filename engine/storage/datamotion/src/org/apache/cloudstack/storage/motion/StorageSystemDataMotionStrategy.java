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
package org.apache.cloudstack.storage.motion;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.Set;

import javax.inject.Inject;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.MigrateAnswer;
import com.cloud.agent.api.MigrateCommand;
import com.cloud.agent.api.ModifyTargetsAnswer;
import com.cloud.agent.api.ModifyTargetsCommand;
import com.cloud.agent.api.PrepareForMigrationCommand;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;

import com.cloud.storage.StorageManager;
import com.cloud.storage.Volume;
import com.cloud.storage.dao.GuestOSCategoryDao;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;
import org.apache.cloudstack.engine.subsystem.api.storage.ChapInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.CopyCommandResult;
import org.apache.cloudstack.engine.subsystem.api.storage.DataMotionStrategy;
import org.apache.cloudstack.engine.subsystem.api.storage.DataObject;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreCapabilities;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine.Event;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreDriver;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.StrategyPriority;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService.VolumeApiResult;
import org.apache.cloudstack.framework.async.AsyncCallFuture;
import org.apache.cloudstack.framework.async.AsyncCompletionCallback;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.storage.command.CopyCmdAnswer;
import org.apache.cloudstack.storage.command.CopyCommand;
import org.apache.cloudstack.storage.command.ResignatureAnswer;
import org.apache.cloudstack.storage.command.ResignatureCommand;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.configuration.Config;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.server.ManagementService;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.VolumeDetailVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.SnapshotDetailsDao;
import com.cloud.storage.dao.SnapshotDetailsVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachineManager;

import com.google.common.base.Preconditions;

@Component
public class StorageSystemDataMotionStrategy implements DataMotionStrategy {
    private static final Logger LOGGER = Logger.getLogger(StorageSystemDataMotionStrategy.class);
    private static final Random RANDOM = new Random(System.nanoTime());

    @Inject private AgentManager _agentMgr;
    @Inject private ConfigurationDao _configDao;
    @Inject private DataStoreManager dataStoreMgr;
    @Inject private DiskOfferingDao _diskOfferingDao;
    @Inject private GuestOSCategoryDao _guestOsCategoryDao;
    @Inject private GuestOSDao _guestOsDao;
    @Inject private ClusterDao clusterDao;
    @Inject private HostDao _hostDao;
    @Inject private HostDetailsDao hostDetailsDao;
    @Inject private ManagementService _mgr;
    @Inject private PrimaryDataStoreDao _storagePoolDao;
    @Inject private SnapshotDao _snapshotDao;
    @Inject private SnapshotDataStoreDao _snapshotDataStoreDao;
    @Inject private SnapshotDetailsDao _snapshotDetailsDao;
    @Inject private VMInstanceDao _vmDao;
    @Inject private VolumeDao _volumeDao;
    @Inject private VolumeDataFactory _volumeDataFactory;
    @Inject private VolumeDetailsDao volumeDetailsDao;
    @Inject private VolumeService _volumeService;

    @Override
    public StrategyPriority canHandle(DataObject srcData, DataObject destData) {
        if (srcData instanceof SnapshotInfo) {
            if (canHandle(srcData) || canHandle(destData)) {
                return StrategyPriority.HIGHEST;
            }
        }

        if (srcData instanceof TemplateInfo && destData instanceof VolumeInfo &&
                (srcData.getDataStore().getId() == destData.getDataStore().getId()) &&
                (canHandle(srcData) || canHandle(destData))) {
            // Both source and dest are on the same storage, so just clone them.
            return StrategyPriority.HIGHEST;
        }

        return StrategyPriority.CANT_HANDLE;
    }

    private boolean canHandle(DataObject dataObject) {
        Preconditions.checkArgument(dataObject != null, "Passing 'null' to dataObject of canHandle(DataObject) is not supported.");

        DataStore dataStore = dataObject.getDataStore();

        if (dataStore.getRole() == DataStoreRole.Primary) {
            Map<String, String> mapCapabilities = dataStore.getDriver().getCapabilities();

            if (mapCapabilities == null) {
                return false;
            }

            if (dataObject instanceof VolumeInfo || dataObject instanceof  SnapshotInfo) {
                String value = mapCapabilities.get(DataStoreCapabilities.STORAGE_SYSTEM_SNAPSHOT.toString());
                Boolean supportsStorageSystemSnapshots = Boolean.valueOf(value);

                if (supportsStorageSystemSnapshots) {
                    LOGGER.info("Using 'StorageSystemDataMotionStrategy' (dataObject is a volume or snapshot and the storage system supports snapshots)");

                    return true;
                }
            } else if (dataObject instanceof TemplateInfo) {
                // If the storage system can clone volumes, we can cache templates on it.
                String value = mapCapabilities.get(DataStoreCapabilities.CAN_CREATE_VOLUME_FROM_VOLUME.toString());
                Boolean canCloneVolume = Boolean.valueOf(value);

                if (canCloneVolume) {
                    LOGGER.info("Using 'StorageSystemDataMotionStrategy' (dataObject is a template and the storage system can create a volume from a volume)");

                    return true;
                }

            }
        }

        return false;
    }

    @Override
    public StrategyPriority canHandle(Map<VolumeInfo, DataStore> volumeMap, Host srcHost, Host destHost) {
        if (HypervisorType.KVM.equals(srcHost.getHypervisorType())) {
            Set<VolumeInfo> volumeInfoSet = volumeMap.keySet();
            for (VolumeInfo volumeInfo : volumeInfoSet) {
                StoragePoolVO storagePoolVO = _storagePoolDao.findById(volumeInfo.getPoolId());
                if (storagePoolVO.isManaged()) {
                    return StrategyPriority.HIGHEST;
                }
            }
            Collection<DataStore> dataStores = volumeMap.values();
            for (DataStore dataStore : dataStores) {
                StoragePoolVO storagePoolVO = _storagePoolDao.findById(dataStore.getId());
                if (storagePoolVO.isManaged()) {
                    return StrategyPriority.HIGHEST;
                }
            }
            // Allow KVM live storage migration for non managed storage
            return StrategyPriority.HYPERVISOR;
        }

        return StrategyPriority.CANT_HANDLE;
    }

    @Override
    public void copyAsync(DataObject srcData, DataObject destData, Host destHost, AsyncCompletionCallback<CopyCommandResult> callback) {
        if (srcData instanceof SnapshotInfo) {
            SnapshotInfo snapshotInfo = (SnapshotInfo)srcData;

            validate(snapshotInfo);

            boolean canHandleSrc = canHandle(srcData);

            if (canHandleSrc && destData instanceof TemplateInfo &&
                    (destData.getDataStore().getRole() == DataStoreRole.Image || destData.getDataStore().getRole() == DataStoreRole.ImageCache)) {
                handleCreateTemplateFromSnapshot(snapshotInfo, (TemplateInfo)destData, callback);

                return;
            }

            if (destData instanceof VolumeInfo) {
                VolumeInfo volumeInfo = (VolumeInfo)destData;

                boolean canHandleDest = canHandle(destData);

                if (canHandleSrc && canHandleDest) {
                    if (snapshotInfo.getDataStore().getId() == volumeInfo.getDataStore().getId()) {
                        handleCreateVolumeFromSnapshotBothOnStorageSystem(snapshotInfo, volumeInfo, callback);
                        return;
                    }
                    else {
                        String errMsg = "This operation is not supported (DataStoreCapabilities.STORAGE_SYSTEM_SNAPSHOT " +
                                "not supported by source or destination storage plug-in). " + getSrcDestDataStoreMsg(srcData, destData);

                        LOGGER.warn(errMsg);

                        throw new UnsupportedOperationException(errMsg);
                    }
                }

                if (canHandleSrc) {
                    String errMsg = "This operation is not supported (DataStoreCapabilities.STORAGE_SYSTEM_SNAPSHOT " +
                            "not supported by destination storage plug-in). " + getDestDataStoreMsg(destData);

                    LOGGER.warn(errMsg);

                    throw new UnsupportedOperationException(errMsg);
                }

                if (canHandleDest) {
                    String errMsg = "This operation is not supported (DataStoreCapabilities.STORAGE_SYSTEM_SNAPSHOT " +
                            "not supported by source storage plug-in). " + getSrcDataStoreMsg(srcData);

                    LOGGER.warn(errMsg);

                    throw new UnsupportedOperationException(errMsg);
                }
            }
        } else if (srcData instanceof TemplateInfo && destData instanceof VolumeInfo) {
            boolean canHandleSrc = canHandle(srcData);

            if (!canHandleSrc) {
                String errMsg = "This operation is not supported (DataStoreCapabilities.STORAGE_CAN_CREATE_VOLUME_FROM_VOLUME " +
                        "not supported by destination storage plug-in). " + getDestDataStoreMsg(destData);

                LOGGER.warn(errMsg);

                throw new UnsupportedOperationException(errMsg);
            }

            handleCreateVolumeFromTemplateBothOnStorageSystem((TemplateInfo)srcData, (VolumeInfo)destData, callback);

            return;
        }

        throw new UnsupportedOperationException("This operation is not supported.");
    }

    private String getSrcDestDataStoreMsg(DataObject srcData, DataObject destData) {
        Preconditions.checkArgument(srcData != null, "Passing 'null' to srcData of getSrcDestDataStoreMsg(DataObject, DataObject) is not supported.");
        Preconditions.checkArgument(destData != null, "Passing 'null' to destData of getSrcDestDataStoreMsg(DataObject, DataObject) is not supported.");

        return "Source data store = " + srcData.getDataStore().getName() + "; " + "Destination data store = " + destData.getDataStore().getName() + ".";
    }

    private String getSrcDataStoreMsg(DataObject srcData) {
        Preconditions.checkArgument(srcData != null, "Passing 'null' to srcData of getSrcDataStoreMsg(DataObject) is not supported.");

        return "Source data store = " + srcData.getDataStore().getName() + ".";
    }

    private String getDestDataStoreMsg(DataObject destData) {
        Preconditions.checkArgument(destData != null, "Passing 'null' to destData of getDestDataStoreMsg(DataObject) is not supported.");

        return "Destination data store = " + destData.getDataStore().getName() + ".";
    }

    private void validate(SnapshotInfo snapshotInfo) {
        long volumeId = snapshotInfo.getVolumeId();

        VolumeVO volumeVO = _volumeDao.findByIdIncludingRemoved(volumeId);

        if (volumeVO.getFormat() != ImageFormat.VHD) {
            throw new CloudRuntimeException("Only the " + ImageFormat.VHD.toString() + " image type is currently supported.");
        }
    }

    private boolean usingBackendSnapshotFor(SnapshotInfo snapshotInfo) {
        String property = getProperty(snapshotInfo.getId(), "takeSnapshot");

        return Boolean.parseBoolean(property);
    }

    private void handleCreateTemplateFromSnapshot(SnapshotInfo snapshotInfo, TemplateInfo templateInfo, AsyncCompletionCallback<CopyCommandResult> callback) {
        try {
            snapshotInfo.processEvent(Event.CopyingRequested);
        }
        catch (Exception ex) {
            throw new CloudRuntimeException("This snapshot is not currently in a state where it can be used to create a template.");
        }

        HostVO hostVO = getHost(snapshotInfo);

        boolean usingBackendSnapshot = usingBackendSnapshotFor(snapshotInfo);
        boolean computeClusterSupportsResign = clusterDao.getSupportsResigning(hostVO.getClusterId());

        if (usingBackendSnapshot && !computeClusterSupportsResign) {
            String noSupportForResignErrMsg = "Unable to locate an applicable host with which to perform a resignature operation : Cluster ID = " + hostVO.getClusterId();

            LOGGER.warn(noSupportForResignErrMsg);

            throw new CloudRuntimeException(noSupportForResignErrMsg);
        }

        try {
            if (usingBackendSnapshot) {
                createVolumeFromSnapshot(hostVO, snapshotInfo, true);
            }

            DataStore srcDataStore = snapshotInfo.getDataStore();

            String value = _configDao.getValue(Config.PrimaryStorageDownloadWait.toString());
            int primaryStorageDownloadWait = NumbersUtil.parseInt(value, Integer.parseInt(Config.PrimaryStorageDownloadWait.getDefaultValue()));
            CopyCommand copyCommand = new CopyCommand(snapshotInfo.getTO(), templateInfo.getTO(), primaryStorageDownloadWait, VirtualMachineManager.ExecuteInSequence.value());

            String errMsg = null;

            CopyCmdAnswer copyCmdAnswer = null;

            try {
                // If we are using a back-end snapshot, then we should still have access to it from the hosts in the cluster that hostVO is in
                // (because we passed in true as the third parameter to createVolumeFromSnapshot above).
                if (usingBackendSnapshot == false) {
                    _volumeService.grantAccess(snapshotInfo, hostVO, srcDataStore);
                }

                Map<String, String> srcDetails = getSnapshotDetails(snapshotInfo);

                copyCommand.setOptions(srcDetails);

                copyCmdAnswer = (CopyCmdAnswer)_agentMgr.send(hostVO.getId(), copyCommand);
            }
            catch (CloudRuntimeException | AgentUnavailableException | OperationTimedoutException ex) {
                String msg = "Failed to create template from snapshot (Snapshot ID = " + snapshotInfo.getId() + ") : ";

                LOGGER.warn(msg, ex);

                throw new CloudRuntimeException(msg + ex.getMessage());
            }
            finally {
                try {
                    _volumeService.revokeAccess(snapshotInfo, hostVO, srcDataStore);
                }
                catch (Exception ex) {
                    LOGGER.warn("Error revoking access to snapshot (Snapshot ID = " + snapshotInfo.getId() + "): " + ex.getMessage(), ex);
                }

                if (copyCmdAnswer == null || !copyCmdAnswer.getResult()) {
                    if (copyCmdAnswer != null && !StringUtils.isEmpty(copyCmdAnswer.getDetails())) {
                        errMsg = copyCmdAnswer.getDetails();
                    }
                    else {
                        errMsg = "Unable to create template from snapshot";
                    }
                }

                try {
                    if (StringUtils.isEmpty(errMsg)) {
                        snapshotInfo.processEvent(Event.OperationSuccessed);
                    }
                    else {
                        snapshotInfo.processEvent(Event.OperationFailed);
                    }
                }
                catch (Exception ex) {
                    LOGGER.warn("Error processing snapshot event: " + ex.getMessage(), ex);
                }
            }

            CopyCommandResult result = new CopyCommandResult(null, copyCmdAnswer);

            result.setResult(errMsg);

            callback.complete(result);
        }
        finally {
            if (usingBackendSnapshot) {
                deleteVolumeFromSnapshot(snapshotInfo);
            }
        }
    }

    /**
     * Clones a template present on the storage to a new volume and resignatures it.
     *
     * @param templateInfo   source template
     * @param volumeInfo  destination ROOT volume
     * @param callback  for async
     */
    private void handleCreateVolumeFromTemplateBothOnStorageSystem(TemplateInfo templateInfo, VolumeInfo volumeInfo, AsyncCompletionCallback<CopyCommandResult> callback) {
        Preconditions.checkArgument(templateInfo != null, "Passing 'null' to templateInfo of handleCreateVolumeFromTemplateBothOnStorageSystem is not supported.");
        Preconditions.checkArgument(volumeInfo != null, "Passing 'null' to volumeInfo of handleCreateVolumeFromTemplateBothOnStorageSystem is not supported.");

        CopyCmdAnswer copyCmdAnswer = null;
        String errMsg = null;

        HostVO hostVO = getHost(volumeInfo.getDataCenterId(), true);

        if (hostVO == null) {
            throw new CloudRuntimeException("Unable to locate a host capable of resigning in the zone with the following ID: " + volumeInfo.getDataCenterId());
        }

        boolean computeClusterSupportsResign = clusterDao.getSupportsResigning(hostVO.getClusterId());

        if (!computeClusterSupportsResign) {
            String noSupportForResignErrMsg = "Unable to locate an applicable host with which to perform a resignature operation : Cluster ID = " + hostVO.getClusterId();

            LOGGER.warn(noSupportForResignErrMsg);

            throw new CloudRuntimeException(noSupportForResignErrMsg);
        }

        try {
            VolumeDetailVO volumeDetail = new VolumeDetailVO(volumeInfo.getId(),
                    "cloneOfTemplate",
                    String.valueOf(templateInfo.getId()),
                    false);

            volumeDetail = volumeDetailsDao.persist(volumeDetail);

            AsyncCallFuture<VolumeApiResult> future = _volumeService.createVolumeAsync(volumeInfo, volumeInfo.getDataStore());
            VolumeApiResult result = future.get();

            if (volumeDetail != null) {
                volumeDetailsDao.remove(volumeDetail.getId());
            }

            if (result.isFailed()) {
                LOGGER.warn("Failed to create a volume: " + result.getResult());

                throw new CloudRuntimeException(result.getResult());
            }

            volumeInfo = _volumeDataFactory.getVolume(volumeInfo.getId(), volumeInfo.getDataStore());

            volumeInfo.processEvent(Event.MigrationRequested);

            volumeInfo = _volumeDataFactory.getVolume(volumeInfo.getId(), volumeInfo.getDataStore());

            copyCmdAnswer = performResignature(volumeInfo, hostVO);

            if (copyCmdAnswer == null || !copyCmdAnswer.getResult()) {
                if (copyCmdAnswer != null && !StringUtils.isEmpty(copyCmdAnswer.getDetails())) {
                    throw new CloudRuntimeException(copyCmdAnswer.getDetails());
                }
                else {
                    throw new CloudRuntimeException("Unable to create a volume from a template");
                }
            }
        } catch (InterruptedException | ExecutionException ex) {
            volumeInfo.getDataStore().getDriver().deleteAsync(volumeInfo.getDataStore(), volumeInfo, null);

            throw new CloudRuntimeException("Create volume from template (ID = " + templateInfo.getId() + ") failed " + ex.getMessage());
        }

        CopyCommandResult result = new CopyCommandResult(null, copyCmdAnswer);

        result.setResult(errMsg);

        callback.complete(result);
    }

    private void handleCreateVolumeFromSnapshotBothOnStorageSystem(SnapshotInfo snapshotInfo, VolumeInfo volumeInfo, AsyncCompletionCallback<CopyCommandResult> callback) {
        CopyCmdAnswer copyCmdAnswer = null;
        String errMsg = null;

        try {
            HostVO hostVO = getHost(snapshotInfo);

            boolean usingBackendSnapshot = usingBackendSnapshotFor(snapshotInfo);
            boolean computeClusterSupportsResign = clusterDao.getSupportsResigning(hostVO.getClusterId());

            if (usingBackendSnapshot && !computeClusterSupportsResign) {
                String noSupportForResignErrMsg = "Unable to locate an applicable host with which to perform a resignature operation : Cluster ID = " + hostVO.getClusterId();

                LOGGER.warn(noSupportForResignErrMsg);

                throw new CloudRuntimeException(noSupportForResignErrMsg);
            }

            boolean canStorageSystemCreateVolumeFromVolume = canStorageSystemCreateVolumeFromVolume(snapshotInfo);
            boolean useCloning = usingBackendSnapshot || (canStorageSystemCreateVolumeFromVolume && computeClusterSupportsResign);

            VolumeDetailVO volumeDetail = null;

            if (useCloning) {
                volumeDetail = new VolumeDetailVO(volumeInfo.getId(),
                    "cloneOfSnapshot",
                    String.valueOf(snapshotInfo.getId()),
                    false);

                volumeDetail = volumeDetailsDao.persist(volumeDetail);
            }

            // at this point, the snapshotInfo and volumeInfo should have the same disk offering ID (so either one should be OK to get a DiskOfferingVO instance)
            DiskOfferingVO diskOffering = _diskOfferingDao.findByIdIncludingRemoved(volumeInfo.getDiskOfferingId());
            SnapshotVO snapshot = _snapshotDao.findById(snapshotInfo.getId());

            // update the volume's hv_ss_reserve (hypervisor snapshot reserve) from a disk offering (used for managed storage)
            _volumeService.updateHypervisorSnapshotReserveForVolume(diskOffering, volumeInfo.getId(), snapshot.getHypervisorType());

            AsyncCallFuture<VolumeApiResult> future = _volumeService.createVolumeAsync(volumeInfo, volumeInfo.getDataStore());

            VolumeApiResult result = future.get();

            if (volumeDetail != null) {
                volumeDetailsDao.remove(volumeDetail.getId());
            }

            if (result.isFailed()) {
                LOGGER.warn("Failed to create a volume: " + result.getResult());

                throw new CloudRuntimeException(result.getResult());
            }

            volumeInfo = _volumeDataFactory.getVolume(volumeInfo.getId(), volumeInfo.getDataStore());

            volumeInfo.processEvent(Event.MigrationRequested);

            volumeInfo = _volumeDataFactory.getVolume(volumeInfo.getId(), volumeInfo.getDataStore());

            if (useCloning) {
                copyCmdAnswer = performResignature(volumeInfo, hostVO);
            }
            else {
                // asking for a XenServer host here so we don't always prefer to use XenServer hosts that support resigning
                // even when we don't need those hosts to do this kind of copy work
                hostVO = getHost(snapshotInfo.getDataCenterId(), false);

                copyCmdAnswer = performCopyOfVdi(volumeInfo, snapshotInfo, hostVO);
            }

            if (copyCmdAnswer == null || !copyCmdAnswer.getResult()) {
                if (copyCmdAnswer != null && !StringUtils.isEmpty(copyCmdAnswer.getDetails())) {
                    errMsg = copyCmdAnswer.getDetails();
                }
                else {
                    errMsg = "Unable to create volume from snapshot";
                }
            }
        }
        catch (Exception ex) {
            errMsg = ex.getMessage() != null ? ex.getMessage() : "Copy operation failed in 'StorageSystemDataMotionStrategy.handleCreateVolumeFromSnapshotBothOnStorageSystem'";
        }

        CopyCommandResult result = new CopyCommandResult(null, copyCmdAnswer);

        result.setResult(errMsg);

        callback.complete(result);
    }

    /**
     * If the underlying storage system is making use of read-only snapshots, this gives the storage system the opportunity to
     * create a volume from the snapshot so that we can copy the VHD file that should be inside of the snapshot to secondary storage.
     *
     * The resultant volume must be writable because we need to resign the SR and the VDI that should be inside of it before we copy
     * the VHD file to secondary storage.
     *
     * If the storage system is using writable snapshots, then nothing need be done by that storage system here because we can just
     * resign the SR and the VDI that should be inside of the snapshot before copying the VHD file to secondary storage.
     */
    private void createVolumeFromSnapshot(HostVO hostVO, SnapshotInfo snapshotInfo, boolean keepGrantedAccess) {
        SnapshotDetailsVO snapshotDetails = handleSnapshotDetails(snapshotInfo.getId(), "tempVolume", "create");

        try {
            snapshotInfo.getDataStore().getDriver().createAsync(snapshotInfo.getDataStore(), snapshotInfo, null);
        }
        finally {
            _snapshotDetailsDao.remove(snapshotDetails.getId());
        }

        CopyCmdAnswer copyCmdAnswer = performResignature(snapshotInfo, hostVO, keepGrantedAccess);

        if (copyCmdAnswer == null || !copyCmdAnswer.getResult()) {
            if (copyCmdAnswer != null && !StringUtils.isEmpty(copyCmdAnswer.getDetails())) {
                throw new CloudRuntimeException(copyCmdAnswer.getDetails());
            }
            else {
                throw new CloudRuntimeException("Unable to create volume from snapshot");
            }
        }
    }

    /**
     * If the underlying storage system needed to create a volume from a snapshot for createVolumeFromSnapshot(HostVO, SnapshotInfo), then
     * this is its opportunity to delete that temporary volume and restore properties in snapshot_details to the way they were before the
     * invocation of createVolumeFromSnapshot(HostVO, SnapshotInfo).
     */
    private void deleteVolumeFromSnapshot(SnapshotInfo snapshotInfo) {
        SnapshotDetailsVO snapshotDetails = handleSnapshotDetails(snapshotInfo.getId(), "tempVolume", "delete");

        try {
            snapshotInfo.getDataStore().getDriver().createAsync(snapshotInfo.getDataStore(), snapshotInfo, null);
        }
        finally {
            _snapshotDetailsDao.remove(snapshotDetails.getId());
        }
    }

    private SnapshotDetailsVO handleSnapshotDetails(long csSnapshotId, String name, String value) {
        _snapshotDetailsDao.removeDetail(csSnapshotId, name);

        SnapshotDetailsVO snapshotDetails = new SnapshotDetailsVO(csSnapshotId, name, value, false);

        return _snapshotDetailsDao.persist(snapshotDetails);
    }

    private boolean canStorageSystemCreateVolumeFromVolume(SnapshotInfo snapshotInfo) {
        boolean supportsCloningVolumeFromVolume = false;

        DataStore dataStore = dataStoreMgr.getDataStore(snapshotInfo.getDataStore().getId(), DataStoreRole.Primary);

        Map<String, String> mapCapabilities = dataStore.getDriver().getCapabilities();

        if (mapCapabilities != null) {
            String value = mapCapabilities.get(DataStoreCapabilities.CAN_CREATE_VOLUME_FROM_VOLUME.toString());

            supportsCloningVolumeFromVolume = Boolean.valueOf(value);
        }

        return supportsCloningVolumeFromVolume;
    }

    private String getProperty(long snapshotId, String property) {
        SnapshotDetailsVO snapshotDetails = _snapshotDetailsDao.findDetail(snapshotId, property);

        if (snapshotDetails != null) {
            return snapshotDetails.getValue();
        }

        return null;
    }

    private Map<String, String> getVolumeDetails(VolumeInfo volumeInfo) {
        Map<String, String> volumeDetails = new HashMap<String, String>();

        VolumeVO volumeVO = _volumeDao.findById(volumeInfo.getId());

        long storagePoolId = volumeVO.getPoolId();
        StoragePoolVO storagePoolVO = _storagePoolDao.findById(storagePoolId);

        volumeDetails.put(DiskTO.STORAGE_HOST, storagePoolVO.getHostAddress());
        volumeDetails.put(DiskTO.STORAGE_PORT, String.valueOf(storagePoolVO.getPort()));
        volumeDetails.put(DiskTO.IQN, volumeVO.get_iScsiName());

        ChapInfo chapInfo = _volumeService.getChapInfo(volumeInfo, volumeInfo.getDataStore());

        if (chapInfo != null) {
            volumeDetails.put(DiskTO.CHAP_INITIATOR_USERNAME, chapInfo.getInitiatorUsername());
            volumeDetails.put(DiskTO.CHAP_INITIATOR_SECRET, chapInfo.getInitiatorSecret());
            volumeDetails.put(DiskTO.CHAP_TARGET_USERNAME, chapInfo.getTargetUsername());
            volumeDetails.put(DiskTO.CHAP_TARGET_SECRET, chapInfo.getTargetSecret());
        }

        return volumeDetails;
    }

    private Map<String, String> getSnapshotDetails(SnapshotInfo snapshotInfo) {
        Map<String, String> snapshotDetails = new HashMap<String, String>();

        long storagePoolId = snapshotInfo.getDataStore().getId();
        StoragePoolVO storagePoolVO = _storagePoolDao.findById(storagePoolId);

        snapshotDetails.put(DiskTO.STORAGE_HOST, storagePoolVO.getHostAddress());
        snapshotDetails.put(DiskTO.STORAGE_PORT, String.valueOf(storagePoolVO.getPort()));

        long snapshotId = snapshotInfo.getId();

        snapshotDetails.put(DiskTO.IQN, getProperty(snapshotId, DiskTO.IQN));

        snapshotDetails.put(DiskTO.CHAP_INITIATOR_USERNAME, getProperty(snapshotId, DiskTO.CHAP_INITIATOR_USERNAME));
        snapshotDetails.put(DiskTO.CHAP_INITIATOR_SECRET, getProperty(snapshotId, DiskTO.CHAP_INITIATOR_SECRET));
        snapshotDetails.put(DiskTO.CHAP_TARGET_USERNAME, getProperty(snapshotId, DiskTO.CHAP_TARGET_USERNAME));
        snapshotDetails.put(DiskTO.CHAP_TARGET_SECRET, getProperty(snapshotId, DiskTO.CHAP_TARGET_SECRET));

        return snapshotDetails;
    }

    private HostVO getHost(SnapshotInfo snapshotInfo) {
        HostVO hostVO = getHost(snapshotInfo.getDataCenterId(), true);

        if (hostVO == null) {
            hostVO = getHost(snapshotInfo.getDataCenterId(), false);

            if (hostVO == null) {
                throw new CloudRuntimeException("Unable to locate an applicable host in data center with ID = " + snapshotInfo.getDataCenterId());
            }
        }

        return hostVO;
    }

    private HostVO getHost(Long zoneId, boolean computeClusterMustSupportResign) {
        Preconditions.checkArgument(zoneId != null, "Zone ID cannot be null.");

        List<HostVO> hosts = _hostDao.listByDataCenterIdAndHypervisorType(zoneId, HypervisorType.XenServer);

        if (hosts == null) {
            return null;
        }

        List<Long> clustersToSkip = new ArrayList<>();

        Collections.shuffle(hosts, RANDOM);

        for (HostVO host : hosts) {
            if (computeClusterMustSupportResign) {
                long clusterId = host.getClusterId();

                if (clustersToSkip.contains(clusterId)) {
                    continue;
                }

                if (clusterDao.getSupportsResigning(clusterId)) {
                    return host;
                }
                else {
                    clustersToSkip.add(clusterId);
                }
            }
            else {
                return host;
            }
        }

        return null;
    }

    /**
     * For each disk to migrate:
     *   Create a volume on the target storage system.
     *   Make the newly created volume accessible to the target KVM host.
     *   Send a command to the target KVM host to connect to the newly created volume.
     * Send a command to the source KVM host to migrate the VM and its storage.
     */
    @Override
    public void copyAsync(Map<VolumeInfo, DataStore> volumeDataStoreMap, VirtualMachineTO vmTO, Host srcHost, Host destHost, AsyncCompletionCallback<CopyCommandResult> callback) {
        String errMsg = null;

        try {
            if (srcHost.getHypervisorType() != HypervisorType.KVM) {
                throw new CloudRuntimeException("Invalid hypervisor type (only KVM supported for this operation at the time being)");
            }

            verifyLiveMigrationMapForKVM(volumeDataStoreMap);

            Map<String, MigrateCommand.MigrateDiskInfo> migrateStorage = new HashMap<>();
            Map<VolumeInfo, VolumeInfo> srcVolumeInfoToDestVolumeInfo = new HashMap<>();

            for (Map.Entry<VolumeInfo, DataStore> entry : volumeDataStoreMap.entrySet()) {
                VolumeInfo srcVolumeInfo = entry.getKey();
                DataStore destDataStore = entry.getValue();

                VolumeVO srcVolume = _volumeDao.findById(srcVolumeInfo.getId());
                StoragePoolVO destStoragePool = _storagePoolDao.findById(destDataStore.getId());

                VolumeVO destVolume = duplicateVolumeOnAnotherStorage(srcVolume, destStoragePool);
                VolumeInfo destVolumeInfo = _volumeDataFactory.getVolume(destVolume.getId(), destDataStore);

                // move the volume from Allocated to Creating
                destVolumeInfo.processEvent(Event.MigrationCopyRequested);
                // move the volume from Creating to Ready
                destVolumeInfo.processEvent(Event.MigrationCopySucceeded);
                // move the volume from Ready to Migrating
                destVolumeInfo.processEvent(Event.MigrationRequested);

                // create a volume on the destination storage
                destDataStore.getDriver().createAsync(destDataStore, destVolumeInfo, null);

                destVolume = _volumeDao.findById(destVolume.getId());

                destVolume.setPath(destVolume.get_iScsiName());

                _volumeDao.update(destVolume.getId(), destVolume);

                destVolumeInfo = _volumeDataFactory.getVolume(destVolume.getId(), destDataStore);

                handleQualityOfServiceForVolumeMigration(destVolumeInfo, PrimaryDataStoreDriver.QualityOfServiceState.MIGRATION);

                _volumeService.grantAccess(destVolumeInfo, destHost, destDataStore);

                String connectedPath = connectHostToVolume(destHost, destVolumeInfo.getPoolId(), destVolumeInfo.get_iScsiName());

                MigrateCommand.MigrateDiskInfo migrateDiskInfo = new MigrateCommand.MigrateDiskInfo(srcVolumeInfo.getPath(),
                        MigrateCommand.MigrateDiskInfo.DiskType.BLOCK,
                        MigrateCommand.MigrateDiskInfo.DriverType.RAW,
                        MigrateCommand.MigrateDiskInfo.Source.DEV,
                        connectedPath);

                migrateStorage.put(srcVolumeInfo.getPath(), migrateDiskInfo);

                srcVolumeInfoToDestVolumeInfo.put(srcVolumeInfo, destVolumeInfo);
            }

            PrepareForMigrationCommand pfmc = new PrepareForMigrationCommand(vmTO);

            try {
                Answer pfma = _agentMgr.send(destHost.getId(), pfmc);

                if (pfma == null || !pfma.getResult()) {
                    String details = pfma != null ? pfma.getDetails() : "null answer returned";
                    String msg = "Unable to prepare for migration due to the following: " + details;

                    throw new AgentUnavailableException(msg, destHost.getId());
                }
            }
            catch (final OperationTimedoutException e) {
                throw new AgentUnavailableException("Operation timed out", destHost.getId());
            }

            VMInstanceVO vm = _vmDao.findById(vmTO.getId());
            boolean isWindows = _guestOsCategoryDao.findById(_guestOsDao.findById(vm.getGuestOSId()).getCategoryId()).getName().equalsIgnoreCase("Windows");

            MigrateCommand migrateCommand = new MigrateCommand(vmTO.getName(), destHost.getPrivateIpAddress(), isWindows, vmTO, true);

            migrateCommand.setWait(StorageManager.KvmStorageOnlineMigrationWait.value());

            migrateCommand.setMigrateStorage(migrateStorage);

            String autoConvergence = _configDao.getValue(Config.KvmAutoConvergence.toString());
            boolean kvmAutoConvergence = Boolean.parseBoolean(autoConvergence);

            migrateCommand.setAutoConvergence(kvmAutoConvergence);

            MigrateAnswer migrateAnswer = (MigrateAnswer)_agentMgr.send(srcHost.getId(), migrateCommand);

            boolean success = migrateAnswer != null && migrateAnswer.getResult();

            handlePostMigration(success, srcVolumeInfoToDestVolumeInfo, vmTO, destHost);

            if (migrateAnswer == null) {
                throw new CloudRuntimeException("Unable to get an answer to the migrate command");
            }

            if (!migrateAnswer.getResult()) {
                errMsg = migrateAnswer.getDetails();

                throw new CloudRuntimeException(errMsg);
            }
        }
        catch (Exception ex) {
            errMsg = "Copy operation failed in 'StorageSystemDataMotionStrategy.copyAsync': " + ex.getMessage();

            throw new CloudRuntimeException(errMsg);
        }
        finally {
            CopyCmdAnswer copyCmdAnswer = new CopyCmdAnswer(errMsg);

            CopyCommandResult result = new CopyCommandResult(null, copyCmdAnswer);

            result.setResult(errMsg);

            callback.complete(result);
        }
    }

    private void handlePostMigration(boolean success, Map<VolumeInfo, VolumeInfo> srcVolumeInfoToDestVolumeInfo, VirtualMachineTO vmTO, Host destHost) {
        if (!success) {
            try {
                PrepareForMigrationCommand pfmc = new PrepareForMigrationCommand(vmTO);

                pfmc.setRollback(true);

                Answer pfma = _agentMgr.send(destHost.getId(), pfmc);

                if (pfma == null || !pfma.getResult()) {
                    String details = pfma != null ? pfma.getDetails() : "null answer returned";
                    String msg = "Unable to rollback prepare for migration due to the following: " + details;

                    throw new AgentUnavailableException(msg, destHost.getId());
                }
            }
            catch (Exception e) {
                LOGGER.debug("Failed to disconnect one or more (original) dest volumes", e);
            }
        }

        for (Map.Entry<VolumeInfo, VolumeInfo> entry : srcVolumeInfoToDestVolumeInfo.entrySet()) {
            VolumeInfo srcVolumeInfo = entry.getKey();
            VolumeInfo destVolumeInfo = entry.getValue();

            handleQualityOfServiceForVolumeMigration(destVolumeInfo, PrimaryDataStoreDriver.QualityOfServiceState.NO_MIGRATION);

            if (success) {
                srcVolumeInfo.processEvent(Event.OperationSuccessed);
                destVolumeInfo.processEvent(Event.OperationSuccessed);

                _volumeDao.updateUuid(srcVolumeInfo.getId(), destVolumeInfo.getId());

                VolumeVO volumeVO = _volumeDao.findById(destVolumeInfo.getId());

                volumeVO.setFormat(ImageFormat.QCOW2);

                _volumeDao.update(volumeVO.getId(), volumeVO);

                try {
                    _volumeService.destroyVolume(srcVolumeInfo.getId());

                    srcVolumeInfo = _volumeDataFactory.getVolume(srcVolumeInfo.getId());

                    AsyncCallFuture<VolumeApiResult> destroyFuture = _volumeService.expungeVolumeAsync(srcVolumeInfo);

                    if (destroyFuture.get().isFailed()) {
                        LOGGER.debug("Failed to clean up source volume on storage");
                    }
                } catch (Exception e) {
                    LOGGER.debug("Failed to clean up source volume on storage", e);
                }

                // Update the volume ID for snapshots on secondary storage
                if (!_snapshotDao.listByVolumeId(srcVolumeInfo.getId()).isEmpty()) {
                    _snapshotDao.updateVolumeIds(srcVolumeInfo.getId(), destVolumeInfo.getId());
                    _snapshotDataStoreDao.updateVolumeIds(srcVolumeInfo.getId(), destVolumeInfo.getId());
                }
            }
            else {
                try {
                    disconnectHostFromVolume(destHost, destVolumeInfo.getPoolId(), destVolumeInfo.get_iScsiName());
                }
                catch (Exception e) {
                    LOGGER.debug("Failed to disconnect (new) dest volume", e);
                }

                try {
                    _volumeService.revokeAccess(destVolumeInfo, destHost, destVolumeInfo.getDataStore());
                }
                catch (Exception e) {
                    LOGGER.debug("Failed to revoke access from dest volume", e);
                }

                destVolumeInfo.processEvent(Event.OperationFailed);
                srcVolumeInfo.processEvent(Event.OperationFailed);

                try {
                    _volumeService.destroyVolume(destVolumeInfo.getId());

                    destVolumeInfo = _volumeDataFactory.getVolume(destVolumeInfo.getId());

                    AsyncCallFuture<VolumeApiResult> destroyFuture = _volumeService.expungeVolumeAsync(destVolumeInfo);

                    if (destroyFuture.get().isFailed()) {
                        LOGGER.debug("Failed to clean up dest volume on storage");
                    }
                } catch (Exception e) {
                    LOGGER.debug("Failed to clean up dest volume on storage", e);
                }
            }
        }
    }

    private void disconnectHostFromVolume(Host host, long storagePoolId, String iqn) {
        ModifyTargetsCommand modifyTargetsCommand = getModifyTargetsCommand(storagePoolId, iqn, false);

        sendModifyTargetsCommand(modifyTargetsCommand, host.getId());
    }

    private ModifyTargetsCommand getModifyTargetsCommand(long storagePoolId, String iqn, boolean add) {
        StoragePoolVO storagePool = _storagePoolDao.findById(storagePoolId);

        Map<String, String> details = new HashMap<>();

        details.put(ModifyTargetsCommand.IQN, iqn);
        details.put(ModifyTargetsCommand.STORAGE_TYPE, storagePool.getPoolType().name());
        details.put(ModifyTargetsCommand.STORAGE_UUID, storagePool.getUuid());
        details.put(ModifyTargetsCommand.STORAGE_HOST, storagePool.getHostAddress());
        details.put(ModifyTargetsCommand.STORAGE_PORT, String.valueOf(storagePool.getPort()));

        ModifyTargetsCommand cmd = new ModifyTargetsCommand();

        List<Map<String, String>> targets = new ArrayList<>();

        targets.add(details);

        cmd.setTargets(targets);
        cmd.setApplyToAllHostsInCluster(true);
        cmd.setAdd(add);
        cmd.setTargetTypeToRemove(ModifyTargetsCommand.TargetTypeToRemove.DYNAMIC);

        return cmd;
    }

    private List<String> sendModifyTargetsCommand(ModifyTargetsCommand cmd, long hostId) {
        ModifyTargetsAnswer modifyTargetsAnswer = (ModifyTargetsAnswer)_agentMgr.easySend(hostId, cmd);

        if (modifyTargetsAnswer == null) {
            throw new CloudRuntimeException("Unable to get an answer to the modify targets command");
        }

        if (!modifyTargetsAnswer.getResult()) {
            String msg = "Unable to modify targets on the following host: " + hostId;

            throw new CloudRuntimeException(msg);
        }

        return modifyTargetsAnswer.getConnectedPaths();
    }

    private String connectHostToVolume(Host host, long storagePoolId, String iqn) {
        ModifyTargetsCommand modifyTargetsCommand = getModifyTargetsCommand(storagePoolId, iqn, true);

        return sendModifyTargetsCommand(modifyTargetsCommand, host.getId()).get(0);
    }

    private void handleQualityOfServiceForVolumeMigration(VolumeInfo volumeInfo, PrimaryDataStoreDriver.QualityOfServiceState qualityOfServiceState) {
        try {
            ((PrimaryDataStoreDriver)volumeInfo.getDataStore().getDriver()).handleQualityOfServiceForVolumeMigration(volumeInfo, qualityOfServiceState);
        }
        catch (Exception ex) {
            LOGGER.warn(ex);
        }
    }

    private VolumeVO duplicateVolumeOnAnotherStorage(Volume volume, StoragePoolVO storagePoolVO) {
        Long lastPoolId = volume.getPoolId();

        VolumeVO newVol = new VolumeVO(volume);

        newVol.setInstanceId(null);
        newVol.setChainInfo(null);
        newVol.setPath(null);
        newVol.setFolder(null);
        newVol.setPodId(storagePoolVO.getPodId());
        newVol.setPoolId(storagePoolVO.getId());
        newVol.setLastPoolId(lastPoolId);

        return _volumeDao.persist(newVol);
    }

    /*
     * At a high level: The source storage cannot be managed and the destination storage can be managed or not managed.
     */
    private void verifyLiveMigrationMapForKVM(Map<VolumeInfo, DataStore> volumeDataStoreMap) {
        for (Map.Entry<VolumeInfo, DataStore> entry : volumeDataStoreMap.entrySet()) {
            VolumeInfo volumeInfo = entry.getKey();

            Long storagePoolId = volumeInfo.getPoolId();
            StoragePoolVO srcStoragePoolVO = _storagePoolDao.findById(storagePoolId);

            if (srcStoragePoolVO == null) {
                throw new CloudRuntimeException("Volume with ID " + volumeInfo.getId() + " is not associated with a storage pool.");
            }

            if (srcStoragePoolVO.isManaged()) {
                throw new CloudRuntimeException("Migrating a volume online with KVM from managed storage is not currently supported.");
            }

            DataStore dataStore = entry.getValue();
            StoragePoolVO destStoragePoolVO = _storagePoolDao.findById(dataStore.getId());

            if (destStoragePoolVO == null) {
                throw new CloudRuntimeException("Destination storage pool with ID " + dataStore.getId() + " was not located.");
            }
        }
    }

    private Map<String, String> getDetails(DataObject dataObj) {
        if (dataObj instanceof VolumeInfo) {
            return getVolumeDetails((VolumeInfo)dataObj);
        }
        else if (dataObj instanceof SnapshotInfo) {
            return getSnapshotDetails((SnapshotInfo)dataObj);
        }

        throw new CloudRuntimeException("'dataObj' must be of type 'VolumeInfo' or 'SnapshotInfo'.");
    }

    private CopyCmdAnswer performResignature(DataObject dataObj, HostVO hostVO) {
        return performResignature(dataObj, hostVO, false);
    }

    private CopyCmdAnswer performResignature(DataObject dataObj, HostVO hostVO, boolean keepGrantedAccess) {
        long storagePoolId = dataObj.getDataStore().getId();
        DataStore dataStore = dataStoreMgr.getDataStore(storagePoolId, DataStoreRole.Primary);

        Map<String, String> details = getDetails(dataObj);

        ResignatureCommand command = new ResignatureCommand(details);

        ResignatureAnswer answer = null;

        try {
            _volumeService.grantAccess(dataObj, hostVO, dataStore);

            answer = (ResignatureAnswer)_agentMgr.send(hostVO.getId(), command);
        }
        catch (CloudRuntimeException | AgentUnavailableException | OperationTimedoutException ex) {
            keepGrantedAccess = false;

            String msg = "Failed to resign the DataObject with the following ID: " + dataObj.getId();

            LOGGER.warn(msg, ex);

            throw new CloudRuntimeException(msg + ex.getMessage());
        }
        finally {
            if (keepGrantedAccess == false) {
                _volumeService.revokeAccess(dataObj, hostVO, dataStore);
            }
        }

        if (answer == null || !answer.getResult()) {
            final String errMsg;

            if (answer != null && answer.getDetails() != null && !answer.getDetails().isEmpty()) {
                errMsg = answer.getDetails();
            }
            else {
                errMsg = "Unable to perform resignature operation in 'StorageSystemDataMotionStrategy.performResignature'";
            }

            throw new CloudRuntimeException(errMsg);
        }

        VolumeObjectTO newVolume = new VolumeObjectTO();

        newVolume.setSize(answer.getSize());
        newVolume.setPath(answer.getPath());
        newVolume.setFormat(answer.getFormat());

        return new CopyCmdAnswer(newVolume);
    }

    private CopyCmdAnswer performCopyOfVdi(VolumeInfo volumeInfo, SnapshotInfo snapshotInfo, HostVO hostVO) {
        String value = _configDao.getValue(Config.PrimaryStorageDownloadWait.toString());
        int primaryStorageDownloadWait = NumbersUtil.parseInt(value, Integer.parseInt(Config.PrimaryStorageDownloadWait.getDefaultValue()));
        CopyCommand copyCommand = new CopyCommand(snapshotInfo.getTO(), volumeInfo.getTO(), primaryStorageDownloadWait, VirtualMachineManager.ExecuteInSequence.value());

        CopyCmdAnswer copyCmdAnswer = null;

        try {
            _volumeService.grantAccess(snapshotInfo, hostVO, snapshotInfo.getDataStore());
            _volumeService.grantAccess(volumeInfo, hostVO, volumeInfo.getDataStore());

            Map<String, String> srcDetails = getSnapshotDetails(snapshotInfo);

            copyCommand.setOptions(srcDetails);

            Map<String, String> destDetails = getVolumeDetails(volumeInfo);

            copyCommand.setOptions2(destDetails);

            copyCmdAnswer = (CopyCmdAnswer)_agentMgr.send(hostVO.getId(), copyCommand);
        }
        catch (CloudRuntimeException | AgentUnavailableException | OperationTimedoutException ex) {
            String msg = "Failed to perform VDI copy : ";

            LOGGER.warn(msg, ex);

            throw new CloudRuntimeException(msg + ex.getMessage());
        }
        finally {
            _volumeService.revokeAccess(snapshotInfo, hostVO, snapshotInfo.getDataStore());
            _volumeService.revokeAccess(volumeInfo, hostVO, volumeInfo.getDataStore());
        }

        return copyCmdAnswer;
    }
}
