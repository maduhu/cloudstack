//Licensed to the Apache Software Foundation (ASF) under one
//or more contributor license agreements.  See the NOTICE file
//distributed with this work for additional information
//regarding copyright ownership.  The ASF licenses this file
//to you under the Apache License, Version 2.0 (the
//"License"); you may not use this file except in compliance
//with the License.  You may obtain a copy of the License at
//
//http://www.apache.org/licenses/LICENSE-2.0
//
//Unless required by applicable law or agreed to in writing,
//software distributed under the License is distributed on an
//"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
//KIND, either express or implied.  See the License for the
//specific language governing permissions and limitations
//under the License.
package org.apache.cloudstack.api.command;

import com.cloud.user.Account;

import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseListCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.DomainResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.QuotaResponseBuilder;
import org.apache.cloudstack.api.response.QuotaSummaryResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.log4j.Logger;

import java.util.List;

import javax.inject.Inject;

@APICommand(name = "quotaSummary", responseObject = QuotaSummaryResponse.class, description = "Lists balance and quota usage for all accounts", since = "4.6.0", requestHasSensitiveInfo = false, responseHasSensitiveInfo = false)
public class QuotaSummaryCmd extends BaseListCmd {
    public static final Logger s_logger = Logger.getLogger(QuotaSummaryCmd.class);
    private static final String s_name = "quotasummaryresponse";

    @Parameter(name = ApiConstants.ACCOUNT, type = CommandType.STRING, required = false, description = "Optional, Account Id for which statement needs to be generated")
    private String accountName;

    @Parameter(name = ApiConstants.DOMAIN_ID, type = CommandType.UUID, required = false, entityType = DomainResponse.class, description = "Optional, If domain Id is given and the caller is domain admin then the statement is generated for domain.")
    private Long domainId;

    @Inject
    QuotaResponseBuilder _responseBuilder;

    public QuotaSummaryCmd() {
        super();
    }

    @Override
    public void execute() {
        Account caller = CallContext.current().getCallingAccount();
        List<QuotaSummaryResponse> responses;
        if (caller.getAccountId() == 2) { //non root admin
            if (getAccountName() != null && getDomainId() != null)
                responses = _responseBuilder.createQuotaSummaryResponse(caller.getAccountName(), caller.getDomainId());
            else
                responses = _responseBuilder.createQuotaSummaryResponse();
        } else {
            responses = _responseBuilder.createQuotaSummaryResponse(caller.getAccountName(), caller.getDomainId());
        }
        final ListResponse<QuotaSummaryResponse> response = new ListResponse<QuotaSummaryResponse>();
        response.setResponses(responses);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public Long getDomainId() {
        return domainId;
    }

    public void setDomainId(Long domainId) {
        this.domainId = domainId;
    }

    @Override
    public String getCommandName() {
        return s_name;
    }

    @Override
    public long getEntityOwnerId() {
        return Account.ACCOUNT_ID_SYSTEM;
    }

}
