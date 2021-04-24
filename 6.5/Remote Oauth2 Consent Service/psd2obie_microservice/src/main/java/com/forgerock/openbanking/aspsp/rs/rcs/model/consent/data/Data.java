/***************************************************************************
 *  Copyright 2019 ForgeRock
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 ***************************************************************************/
package com.forgerock.openbanking.aspsp.rs.rcs.model.consent.data;

import java.io.Serializable;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.forgerock.openbanking.aspsp.rs.rcs.model.consent.data.charges.Charges;
import com.forgerock.openbanking.aspsp.rs.rcs.model.consent.data.initiation.Initiation;
import com.google.gson.annotations.SerializedName;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class Data implements Serializable {
	/**
	 * 
	 */
	private static final long serialVersionUID = 9072947979517229769L;
	
	@SerializedName("ConsnetId")
	private String consnetId;
	
	@SerializedName("CreationDateTime")
	private String creationDateTime;
	
	@SerializedName("Status")
	private String Status;	
	
	@SerializedName("StatusUpdateDateTime")
	private String statusUpdateDateTime;
	
	@SerializedName("CutOffDateTime")
	private String cutOffDateTime;
	
	@SerializedName("ExpectedExecutionDateTime")
	private String expectedExecutionDateTime;
	
	@SerializedName("ExpectedSettlementDateTime")
	private String expectedSettlementDateTime;	
	
	@JsonUnwrapped
	private Charges Charges;
	
	@JsonUnwrapped
	private Initiation Initiation;
	
	@JsonUnwrapped
	private Authorisation Authorisation;	
	
	@SerializedName("Permissions")
	private List<String> Permissions;
	
	@SerializedName("Account")
	private List<Account> Account;
	
}

