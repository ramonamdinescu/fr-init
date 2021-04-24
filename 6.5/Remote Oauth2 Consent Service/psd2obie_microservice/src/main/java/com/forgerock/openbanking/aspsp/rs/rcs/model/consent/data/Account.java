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
import com.google.gson.annotations.SerializedName;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@Builder
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class Account implements Serializable {
	@SerializedName("AccountId")
	private String AccountId;
	@SerializedName("Currency")
	private String Currency;
	@SerializedName("AccountType")
	private String AccountType;
	@SerializedName("AccountSubType")
	private String AccountSubType;
	@SerializedName("Nickname")
	private String Nickname;	
	
	@SerializedName("SchemeName")
	private String SchemeName;
	@SerializedName("Identification")
	private String Identification;
	@SerializedName("Name")
	private String Name;
	@SerializedName("SecondaryIdentification")
	private String SecondaryIdentification;
	
	@SerializedName("Account")
	private List<Account> Account;

	
	
	
	

}
