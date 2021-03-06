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
package com.forgerock.openbanking.aspsp.rs.rcs.service.keygenerator;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;

@Service
public class RSAGenerateKey implements IGenerateKey {

	private static KeyPair keyPairStatic;
	
	public KeyPair generateKeyPair() throws NoSuchAlgorithmException {
		if(keyPairStatic==null) {
			KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
	    	gen.initialize(2048);
	    	keyPairStatic = gen.generateKeyPair();
    	}
			return keyPairStatic;
	}
	
	@Override
	public String generateKey() throws NoSuchAlgorithmException {
		// Generate the RSA key pair
		generateKeyPair();
    	// Convert to JWK format
    	JWK jwk = new RSAKey.Builder((RSAPublicKey)keyPairStatic.getPublic())
    	    //.privateKey((RSAPrivateKey)keyPair.getPrivate())
    	    .keyUse(KeyUse.SIGNATURE)
    	    .keyID(UUID.randomUUID().toString())
    	    .build();
    	
    	
        return jwk.toJSONString();
	}

}
