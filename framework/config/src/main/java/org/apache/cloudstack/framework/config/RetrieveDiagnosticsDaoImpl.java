/*
 * // Licensed to the Apache Software Foundation (ASF) under one
 * // or more contributor license agreements.  See the NOTICE file
 * // distributed with this work for additional information
 * // regarding copyright ownership.  The ASF licenses this file
 * // to you under the Apache License, Version 2.0 (the
 * // "License"); you may not use this file except in compliance
 * // with the License.  You may obtain a copy of the License at
 * //
 * //   http://www.apache.org/licenses/LICENSE-2.0
 * //
 * // Unless required by applicable law or agreed to in writing,
 * // software distributed under the License is distributed on an
 * // "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * // KIND, either express or implied.  See the License for the
 * // specific language governing permissions and limitations
 * // under the License.
 */

package org.apache.cloudstack.framework.config;

import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.TransactionLegacy;
import org.apache.cloudstack.framework.config.impl.RetrieveDiagnosticsDao;
import org.apache.cloudstack.framework.config.impl.RetrieveDiagnosticsVO;
import org.springframework.stereotype.Component;
import org.apache.log4j.Logger;

import java.sql.PreparedStatement;
import java.util.List;

@Component
public class RetrieveDiagnosticsDaoImpl extends GenericDaoBase<RetrieveDiagnosticsVO, String> implements RetrieveDiagnosticsDao
{
    private static final Logger LOGGER = Logger.getLogger(RetrieveDiagnosticsDaoImpl.class);
    private final SearchBuilder<RetrieveDiagnosticsVO> DiagnosticsSearchByTypeAndRole;
    public static final String UPDATE_RETRIEVEDIAGNOSTICS_SQL = "INSERT INTO diagnosticsdata VALUES (?, ?, ?)";

    public RetrieveDiagnosticsDaoImpl() {
        super();
        DiagnosticsSearchByTypeAndRole = createSearchBuilder();
        DiagnosticsSearchByTypeAndRole.and("class", DiagnosticsSearchByTypeAndRole.entity().getType(), SearchCriteria.Op.EQ);
        DiagnosticsSearchByTypeAndRole.and("role", DiagnosticsSearchByTypeAndRole.entity().getRole(), SearchCriteria.Op.EQ);
        DiagnosticsSearchByTypeAndRole.done();
    }

    @Override public List<RetrieveDiagnosticsVO> findByEntityType(String diagnosticsType) {
        SearchCriteria<RetrieveDiagnosticsVO> sc = createSearchCriteria();
        sc.addAnd("class", SearchCriteria.Op.EQ, diagnosticsType);
        return listBy(sc);
    }

    @Override public List<RetrieveDiagnosticsVO> findByEntity(String diagnosticsType, String role) {
        SearchCriteria<RetrieveDiagnosticsVO> sc = createSearchCriteria();
        sc.addAnd("class", SearchCriteria.Op.EQ, diagnosticsType);
        sc.addAnd("role", SearchCriteria.Op.EQ, role);
        return listBy(sc, null);
    }

    @Override
    public List<RetrieveDiagnosticsVO> retrieveAllDiagnosticsData() {
        SearchCriteria<RetrieveDiagnosticsVO> sc = createSearchCriteria();
        sc.addAnd("class", SearchCriteria.Op.IN, "ConsoleProxy, SecondaryStorageVm, VirtualRouter");
        return listBy(sc, null);
    }

    @Override
    public boolean update(String role, String clazz, String value) {
        TransactionLegacy txn = TransactionLegacy.currentTxn();
        try (PreparedStatement stmt = txn.prepareStatement(UPDATE_RETRIEVEDIAGNOSTICS_SQL);){
            stmt.setString(1, role);
            stmt.setString(2, clazz);
            stmt.setString(3, value);
            stmt.executeUpdate();
            return true;
        } catch (Exception e) {
            LOGGER.warn("Unable to update Diagnosticsdata Table", e);
        }
        return false;
    }

}


