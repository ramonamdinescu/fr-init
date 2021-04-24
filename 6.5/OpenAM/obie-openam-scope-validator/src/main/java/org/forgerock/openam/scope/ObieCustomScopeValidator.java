/***************************************************************************
 *  Copyright 2019 ForgeRock AS
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

package org.forgerock.openam.scope;

import static java.util.Collections.emptySet;
import static org.forgerock.json.JsonValue.field;
import static org.forgerock.json.JsonValue.fieldIfNotNull;
import static org.forgerock.json.JsonValue.json;
import static org.forgerock.json.JsonValue.object;
import static org.forgerock.oauth2.core.Utils.splitScope;
import static org.forgerock.oauth2.core.Utils.stringToList;
import static org.forgerock.openam.oauth2.OAuth2Constants.JWTTokenParams.FORGEROCK_CLAIMS;
import static org.forgerock.openam.oauth2.OAuth2Constants.Params.OPENID;
import static org.forgerock.openam.oauth2.OAuth2Constants.TokenEndpoint.CLIENT_CREDENTIALS_GRANT_TYPE;
import static org.forgerock.openam.scripting.ScriptConstants.EMPTY_SCRIPT_SELECTION;
import static org.forgerock.openam.scripting.ScriptConstants.OIDC_CLAIMS_NAME;

import java.security.AccessController;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.script.Bindings;
import javax.script.ScriptException;
import javax.script.SimpleBindings;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

import org.forgerock.http.client.RestletHttpClient;
import org.forgerock.json.JsonValue;
import org.forgerock.oauth2.core.AccessToken;
import org.forgerock.oauth2.core.ClientRegistration;
import org.forgerock.oauth2.core.OAuth2ProviderSettings;
import org.forgerock.oauth2.core.OAuth2ProviderSettingsFactory;
import org.forgerock.oauth2.core.OAuth2Request;
import org.forgerock.oauth2.core.ScopeValidator;
import org.forgerock.oauth2.core.Token;
import org.forgerock.oauth2.core.UserInfoClaims;
import org.forgerock.oauth2.core.exceptions.InvalidClientException;
import org.forgerock.oauth2.core.exceptions.InvalidRequestException;
import org.forgerock.oauth2.core.exceptions.InvalidScopeException;
import org.forgerock.oauth2.core.exceptions.NoUserExistsException;
import org.forgerock.oauth2.core.exceptions.NotFoundException;
import org.forgerock.oauth2.core.exceptions.ServerException;
import org.forgerock.oauth2.core.exceptions.UnauthorizedClientException;
import org.forgerock.openam.agent.TokenRestrictionResolver;
import org.forgerock.openam.oauth2.AgentClientRegistration;
import org.forgerock.openam.oauth2.IdentityManager;
import org.forgerock.openam.oauth2.OAuth2Constants;
import org.forgerock.openam.scripting.ScriptEvaluator;
import org.forgerock.openam.scripting.ScriptObject;
import org.forgerock.openam.scripting.SupportedScriptingLanguage;
import org.forgerock.openam.scripting.factories.ScriptHttpClientFactory;
import org.forgerock.openam.scripting.service.ScriptConfiguration;
import org.forgerock.openam.scripting.service.ScriptingServiceFactory;
import org.forgerock.openam.utils.CollectionUtils;
import org.forgerock.openam.utils.OpenAMSettings;
import org.forgerock.openam.utils.OpenAMSettingsImpl;
import org.forgerock.openidconnect.Claim;
import org.forgerock.openidconnect.Claims;
import org.forgerock.openidconnect.OpenIDTokenIssuer;
import org.forgerock.openidconnect.OpenIdConnectClientRegistration;
import org.json.JSONException;
import org.restlet.Request;
import org.restlet.ext.servlet.ServletUtils;

import com.iplanet.am.sdk.AMHashMap;
import com.iplanet.am.util.SystemProperties;
import com.iplanet.dpro.session.SessionException;
import com.iplanet.dpro.session.TokenRestriction;
import com.iplanet.dpro.session.service.SessionService;
import com.iplanet.sso.SSOException;
import com.iplanet.sso.SSOToken;
import com.iplanet.sso.SSOTokenManager;
import com.sun.identity.authentication.util.ISAuthConstants;
import com.sun.identity.idm.AMIdentity;
import com.sun.identity.idm.AMIdentityRepository;
import com.sun.identity.idm.IdRepoException;
import com.sun.identity.idm.IdSearchControl;
import com.sun.identity.idm.IdSearchResults;
import com.sun.identity.idm.IdType;
import com.sun.identity.security.AdminTokenAction;
import com.sun.identity.shared.Constants;
import com.sun.identity.shared.datastruct.CollectionHelper;
import com.sun.identity.shared.debug.Debug;
import com.sun.identity.sm.DNMapper;
import com.sun.identity.sm.SMSException;

/**
 * Provided as extension points to allow the OpenAM OAuth2 provider to customise
 * the requested scope of authorize, access token and refresh token requests and
 * to allow the OAuth2 provider to return additional data from these endpoints
 * as well.
 *
 * @since 12.0.0
 */
@Singleton
public class ObieCustomScopeValidator implements ScopeValidator {

	private static final String MULTI_ATTRIBUTE_SEPARATOR = ",";
	private static final Long DEFAULT_TIMESTAMP = 0L;
	private static final DateFormat TIMESTAMP_DATE_FORMAT = new SimpleDateFormat("yyyyMMddhhmmss");
	private static final String OPEN_BANKING_INTENT_ID = "openbanking_intent_id";
	private static final String HTTP_CLIENT_IDENTIFIER = "httpClient";
	private final OAuth2ProviderSettingsFactory providerSettingsFactory;
	private final Debug logger = Debug.getInstance("OAuth2Provider");
	private final IdentityManager identityManager;
	private final OpenIDTokenIssuer openIDTokenIssuer;
	private final OpenAMSettings openAMSettings;
	private final ScriptEvaluator scriptEvaluator;
	private final ScriptingServiceFactory scriptingServiceFactory;
	private final TokenRestrictionResolver agentValidator;
	private final SessionService sessionService;
	private final ScriptHttpClientFactory httpClientFactory;

	/**
	 * Constructs a new OpenAMScopeValidator.
	 *
	 * @param identityManager         An instance of the IdentityManager.
	 * @param openIDTokenIssuer       An instance of the OpenIDTokenIssuer.
	 * @param providerSettingsFactory An instance of the CTSPersistentStore.
	 * @param openAMSettings          An instance of the OpenAMSettings.
	 * @param scriptEvaluator         An instance of the OIDC Claims
	 *                                ScriptEvaluator.
	 * @param scriptingServiceFactory An instance of the ScriptingServiceFactory.
	 * @param agentValidator          An instance of {@code LDAPAgentValidator} used
	 *                                to retrieve the token restriction.
	 * @param sessionService          An instance of {@code SessionService}.
	 * @param httpClientFactory       The singleton instance of the
	 *                                ScriptHttpClientFactory, used to obtain an
	 *                                httpClient
	 */
	@Inject
	public ObieCustomScopeValidator(IdentityManager identityManager, OpenIDTokenIssuer openIDTokenIssuer,
			OAuth2ProviderSettingsFactory providerSettingsFactory, OpenAMSettings openAMSettings,
			@Named(OIDC_CLAIMS_NAME) ScriptEvaluator scriptEvaluator, ScriptingServiceFactory scriptingServiceFactory,
			TokenRestrictionResolver agentValidator, SessionService sessionService,
			ScriptHttpClientFactory httpClientFactory) {
		this.identityManager = identityManager;
		this.openIDTokenIssuer = openIDTokenIssuer;
		this.providerSettingsFactory = providerSettingsFactory;
		this.openAMSettings = openAMSettings;
		this.scriptEvaluator = scriptEvaluator;
		this.scriptingServiceFactory = scriptingServiceFactory;
		this.agentValidator = agentValidator;
		this.sessionService = sessionService;
		this.httpClientFactory = httpClientFactory;
	}

	/**
	 * {@inheritDoc}
	 */
	public Set<String> validateAuthorizationScope(ClientRegistration client, Set<String> scope, OAuth2Request request)
			throws InvalidScopeException, ServerException {
		return validateScopes(scope, client.getDefaultScopes(), client.getAllowedScopes(), request);
	}

	/**
	 * {@inheritDoc}
	 */
	public Set<String> validateAccessTokenScope(ClientRegistration client, Set<String> scope, OAuth2Request request)
			throws InvalidScopeException, ServerException {
		return validateScopes(scope, client.getDefaultScopes(), client.getAllowedScopes(), request);
	}

	/**
	 * {@inheritDoc}
	 */
	public Set<String> validateRefreshTokenScope(ClientRegistration clientRegistration, Set<String> requestedScope,
			Set<String> tokenScope, OAuth2Request request) throws InvalidScopeException {
		return validateScopes(requestedScope, emptySet(), tokenScope, request);
	}

	private Set<String> validateScopes(Set<String> requestedScopes, Set<String> defaultScopes,
			Set<String> allowedScopes, OAuth2Request request) throws InvalidScopeException {
		Set<String> scopes;

		if (requestedScopes == null || requestedScopes.isEmpty()) {
			scopes = defaultScopes;
		} else {
			scopes = new HashSet<>(allowedScopes);
			scopes.retainAll(requestedScopes);
			if (requestedScopes.size() > scopes.size()) {
				Set<String> invalidScopes = new HashSet<>(requestedScopes);
				invalidScopes.removeAll(allowedScopes);
				throw InvalidScopeException.create("Unknown/invalid scope(s): " + invalidScopes.toString(), request);
			}
		}

		if (scopes == null || scopes.isEmpty()) {
			throw InvalidScopeException.create("No scope requested and no default scope configured", request);
		}

		return scopes;
	}

	/**
	 * {@inheritDoc}
	 */
	public UserInfoClaims getUserInfo(ClientRegistration clientRegistration, AccessToken token, OAuth2Request request)
			throws UnauthorizedClientException, NotFoundException, ServerException, InvalidRequestException {

		List<Claim> providedClaims = new ArrayList<>();
		Bindings scriptVariables = new SimpleBindings();
		SSOToken ssoToken = getUsersSession(request);
		Set<String> scopes;
		String realm;
		AMIdentity id;
		OAuth2ProviderSettings providerSettings = providerSettingsFactory.get(request);
		List<Claim> requestedClaimsValues = gatherRequestedClaims(providerSettings, request, token);

		try {
			if (token != null) {
				realm = token.getRealm(); // data comes from token when we have one
				scopes = token.getScope();
				id = getUsersIdentity(token.getResourceOwnerId(), realm, request);
				addSubToResponseIfOpenIdConnect(clientRegistration, token, providedClaims, providerSettings);
				providedClaims.add(new Claim(OAuth2Constants.JWTTokenParams.UPDATED_AT,
						getUpdatedAt(token.getResourceOwnerId(), token.getRealm(), request)));
			} else {
				// otherwise we're simply reading claims into the id_token, so grab it from the
				// request/ssoToken
				realm = DNMapper.orgNameToRealmName(ssoToken.getProperty(ISAuthConstants.ORGANIZATION));
				id = getUsersIdentity(ssoToken.getProperty(ISAuthConstants.USER_ID), realm, request);
				String scopeStr = request.getParameter(OAuth2Constants.Params.SCOPE);
				scopes = splitScope(scopeStr);
			}

			scriptVariables.put(OAuth2Constants.ScriptParams.SCOPES, getScriptFriendlyScopes(scopes));
			scriptVariables.put(OAuth2Constants.ScriptParams.IDENTITY, id);
			scriptVariables.put(OAuth2Constants.ScriptParams.LOGGER, logger);
			scriptVariables.put(OAuth2Constants.ScriptParams.CLAIMS_LEGACY,
					providedClaimsToLegacyFormat(providedClaims));
			scriptVariables.put(OAuth2Constants.ScriptParams.CLAIMS, providedClaims);
			scriptVariables.put(OAuth2Constants.ScriptParams.SESSION, ssoToken);
			scriptVariables.put(OAuth2Constants.ScriptParams.REQUESTED_CLAIMS_LEGACY,
					requestedClaimsToLegacyFormat(requestedClaimsValues));
			scriptVariables.put(OAuth2Constants.ScriptParams.REQUESTED_CLAIMS, requestedClaimsValues);
			scriptVariables.put(OAuth2Constants.ScriptParams.CLAIMS_LOCALES,
					getScriptFriendlyClaimLocales(stringToList(request.getParameter("claims_locales"))));
			scriptVariables.put(HTTP_CLIENT_IDENTIFIER, getHttpClient(realm));

			ScriptObject script = getOIDCClaimsExtensionScript(realm);
			try {
				final UserInfoClaims userInfoClaims = scriptEvaluator.evaluateScript(script, scriptVariables);
				if (requestedClaimsValues != null) {
					for (Claim claim : requestedClaimsValues) {
						if (claim.toString().contains("openbanking_intent_id")) {
							addIntentIdToResponseIfOpenIdConnect(providedClaims, (String) claim.getValues().get(0));
						}
					}
				}
				return isAgentRequest(clientRegistration)
						? addRestrictedSSOTokenToUserInfoClaims(userInfoClaims, clientRegistration, realm, ssoToken)
						: userInfoClaims;
			} catch (ScriptException e) {
				InvalidRequestException oAuth2Exception = unwrapInvalidRequestException(e);
				if (oAuth2Exception != null) {
					logger.message("Error running OIDC claims script, (invalid request)", oAuth2Exception);
					throw oAuth2Exception;
				}
				logger.message("Error running OIDC claims script", e);
				throw new ServerException("Error running OIDC claims script", e);
			}
		} catch (SSOException e) {
			throw new NotFoundException(e);
		}
	}

	private void addIntentIdToResponseIfOpenIdConnect(List<Claim> response, String intentId) {
		if (intentId != null) {
			response.add(new Claim(OPEN_BANKING_INTENT_ID, intentId));
		} else {
			response.add(new Claim(OPEN_BANKING_INTENT_ID, ""));
		}
	}

	private AMIdentity getUsersIdentity(String resourceOwnerId, String realm, OAuth2Request request)
			throws SSOException, UnauthorizedClientException {
		try {
			return identityManager.getResourceOwnerOrClientIdentity(request, resourceOwnerId, realm);
		} catch (NoUserExistsException e) {
			logger.message("No user exists for {} in realm {}", resourceOwnerId, realm);
			if (identityManager.isIgnoredProfile(realm)) {
				logger.message("User profile set to ignore, 'no user' result is valid.");
				return null;
			}

			throw e;
		}
	}

	private InvalidRequestException unwrapInvalidRequestException(ScriptException e) {
		Throwable exception = e;
		while (exception.getCause() != null) {
			if (exception.getCause() instanceof InvalidRequestException) {
				return (InvalidRequestException) exception.getCause();
			} else {
				exception = exception.getCause();
			}
		}
		return null;
	}

	private Map<String, Object> providedClaimsToLegacyFormat(List<Claim> providedClaims) {
		Map<String, Object> claims = new HashMap<>();
		for (Claim providedClaim : providedClaims) {
			if (providedClaim.getValues().isEmpty()) {
				claims.put(providedClaim.getName(), null);
			} else {
				claims.put(providedClaim.getName(), providedClaim.getValues().iterator().next());
			}
		}
		return claims;
	}

	private Map<String, Set<Object>> requestedClaimsToLegacyFormat(List<Claim> requestedClaims) {
		Map<String, Set<Object>> claims = new HashMap<>();
		for (Claim requestedClaim : requestedClaims) {
			claims.put(requestedClaim.getName(), new HashSet<>(requestedClaim.getValues()));
		}
		return claims;
	}

	private void addSubToResponseIfOpenIdConnect(ClientRegistration clientRegistration, AccessToken token,
			List<Claim> response, OAuth2ProviderSettings providerSettings) {
		if (clientRegistration instanceof OpenIdConnectClientRegistration) {
			final String subId = ((OpenIdConnectClientRegistration) clientRegistration)
					.getSubValue(token.getResourceOwnerId(), providerSettings);
			response.add(new Claim(OAuth2Constants.JWTTokenParams.SUB, subId));
		}
	}

	private boolean isAgentRequest(ClientRegistration clientRegistration) {
		return clientRegistration instanceof AgentClientRegistration;
	}

	private UserInfoClaims addRestrictedSSOTokenToUserInfoClaims(UserInfoClaims userInfoClaims,
			ClientRegistration clientRegistration, String realm, SSOToken ssoToken) throws ServerException {
		String restrictedTokenId = getRestrictedTokenId(clientRegistration, realm, ssoToken);
		String sessionUid = getSessionUid(ssoToken);
		final JsonValue values = json(object(field(FORGEROCK_CLAIMS,
				object(field(OAuth2Constants.JWTTokenParams.SSO_TOKEN, restrictedTokenId),
						field(OAuth2Constants.JWTTokenParams.SESSION_UID, sessionUid),
						fieldIfNotNull(OAuth2Constants.JWTTokenParams.TRANSACTION_ID,
								userInfoClaims.getValues().get(OAuth2Constants.JWTTokenParams.TRANSACTION_ID))))));
		return new UserInfoClaims(values.asMap(), userInfoClaims.getCompositeScopes());
	}

	private Set<String> getScriptFriendlyScopes(Set<String> scopes) {
		return scopes == null ? new HashSet<>() : new HashSet<>(scopes);
	}

	private List<String> getScriptFriendlyClaimLocales(List<String> claimLocales) {
		return claimLocales == null ? new ArrayList<>() : new ArrayList<>(claimLocales);
	}

	private String getSessionUid(SSOToken ssoToken) throws ServerException {
		try {
			return ssoToken.getProperty(Constants.AM_CTX_ID);
		} catch (SSOException e) {
			logger.warning("Failed to get {}", OAuth2Constants.JWTTokenParams.SESSION_UID, e);
			throw new ServerException("Failed to get " + OAuth2Constants.JWTTokenParams.SESSION_UID, e);
		}
	}

	private String getRestrictedTokenId(ClientRegistration clientRegistration, String realm, SSOToken ssoToken)
			throws ServerException {
		if (!SystemProperties.getAsBoolean(Constants.IS_ENABLE_UNIQUE_COOKIE)) {
			return ssoToken.getTokenID().toString();
		}

		try {
			TokenRestriction tokenRes = agentValidator.resolve(clientRegistration.getClientId(), realm,
					AccessController.doPrivileged(AdminTokenAction.getInstance()));

			return sessionService.getRestrictedTokenId(ssoToken.getTokenID().toString(), tokenRes);
		} catch (SSOException | IdRepoException | SMSException | SessionException e) {
			logger.warning("Failed to get restricted session token", e);
			throw new ServerException("Failed to get restricted session token", e);
		}
	}

	private List<Claim> gatherRequestedClaims(OAuth2ProviderSettings providerSettings, OAuth2Request request,
			AccessToken token) {
		Request req = request.getRequest();
		if (token != null) { // claims are in the extra data in the AccessToken
			String claimsJson = token.getClaims();
			if (req.getResourceRef().getLastSegment().equals(OAuth2Constants.UserinfoEndpoint.USERINFO)) {
				List<Claim> list = gatherRequestedClaims(providerSettings, claimsJson,
						OAuth2Constants.UserinfoEndpoint.USERINFO);
				if (list == null || (list != null && list.size() <= 0)) {
					return gatherRequestedClaims(providerSettings, claimsJson, OAuth2Constants.JWTTokenParams.ID_TOKEN);
				}
				return gatherRequestedClaims(providerSettings, claimsJson, OAuth2Constants.UserinfoEndpoint.USERINFO);
			} else {
				return gatherRequestedClaims(providerSettings, claimsJson, OAuth2Constants.JWTTokenParams.ID_TOKEN);
			}
		} else {
			String json = request.getParameter(OAuth2Constants.Custom.CLAIMS);
			List<Claim> requestedClaims = new ArrayList();
			requestedClaims
					.addAll(gatherRequestedClaims(providerSettings, json, OAuth2Constants.JWTTokenParams.ID_TOKEN));
			requestedClaims
					.addAll(gatherRequestedClaims(providerSettings, json, OAuth2Constants.UserinfoEndpoint.USERINFO));
			return requestedClaims;
		}
	}

	/**
	 * Generates a map for the claims specifically requested as per Section 5.5 of
	 * the spec. Ends up mapping requested claims against a set of their optional
	 * values (empty if claim is requested but no suggested/required values given).
	 */
	private List<Claim> gatherRequestedClaims(OAuth2ProviderSettings providerSettings, String claimsJson,
			String objectName) {
		try {
			if (providerSettings.getClaimsParameterSupported() && claimsJson != null) {
				try {
					final Claims claims = Claims.parse(claimsJson);
					switch (objectName) {
					case OAuth2Constants.JWTTokenParams.ID_TOKEN:
						return new ArrayList<>(claims.getIdTokenClaims().values());
					case OAuth2Constants.UserinfoEndpoint.USERINFO:
						return new ArrayList<>(claims.getUserInfoClaims().values());
					default:
						throw new IllegalArgumentException("Invalid claim type");
					}
				} catch (JSONException e) {
					// ignorable
				}
			}
		} catch (ServerException e) {
			logger.message("Requested Claims Supported not set.");
		}

		return Collections.emptyList();
	}

	/**
	 * Attempts to get the user's session, which can either be set on the
	 * OAuth2Request explicitly or found as a cookie on the http request.
	 *
	 * @param request The OAuth2Request.
	 * @return The user's SSOToken or {@code null} if no session was found.
	 */
	private SSOToken getUsersSession(OAuth2Request request) {
		String sessionId = request.getSession();
		if (sessionId == null) {
			final HttpServletRequest req = ServletUtils.getRequest(request.getRequest());
			if (req.getCookies() != null) {
				final String cookieName = openAMSettings.getSSOCookieName();
				for (final Cookie cookie : req.getCookies()) {
					if (cookie.getName().equals(cookieName)) {
						sessionId = cookie.getValue();
					}
				}
			}
		}
		SSOToken ssoToken = null;
		if (sessionId != null) {
			try {
				ssoToken = SSOTokenManager.getInstance().createSSOToken(sessionId);
			} catch (SSOException e) {
				logger.message("Session Id is not valid");
			}
		}
		return ssoToken;
	}

	private ScriptObject getOIDCClaimsExtensionScript(String realm) throws ServerException {

		OpenAMSettingsImpl settings = new OpenAMSettingsImpl(OAuth2Constants.OAuth2ProviderService.NAME,
				OAuth2Constants.OAuth2ProviderService.VERSION);
		try {
			String scriptId = settings.getStringSetting(realm,
					OAuth2Constants.OAuth2ProviderService.OIDC_CLAIMS_EXTENSION_SCRIPT);
			if (EMPTY_SCRIPT_SELECTION.equals(scriptId)) {
				return new ScriptObject("oidc-claims-script", "", SupportedScriptingLanguage.JAVASCRIPT);
			}
			ScriptConfiguration config = getScriptConfiguration(realm, scriptId);
			return new ScriptObject(config.getName(), config.getScript(), config.getLanguage());
		} catch (org.forgerock.openam.scripting.ScriptException | SSOException | SMSException e) {
			logger.message("Error running OIDC claims script", e);
			throw new ServerException("Error running OIDC claims script: " + e.getMessage(), e);
		}
	}

	/**
	 * Retrieve the httpClient that can be provided through to the OIDC Claims
	 * script.
	 * 
	 * @param realm Name of the realm
	 * @return RestletHttpClient The http client
	 * @throws ServerException
	 */
	private RestletHttpClient getHttpClient(String realm) throws ServerException {

		OpenAMSettingsImpl settings = new OpenAMSettingsImpl(OAuth2Constants.OAuth2ProviderService.NAME,
				OAuth2Constants.OAuth2ProviderService.VERSION);
		try {
			String scriptId = settings.getStringSetting(realm,
					OAuth2Constants.OAuth2ProviderService.OIDC_CLAIMS_EXTENSION_SCRIPT);
			if (EMPTY_SCRIPT_SELECTION.equals(scriptId)) {
				return httpClientFactory.getScriptHttpClient(SupportedScriptingLanguage.JAVASCRIPT);
			}
			ScriptConfiguration config = getScriptConfiguration(realm, scriptId);
			return httpClientFactory.getScriptHttpClient(config.getLanguage());
		} catch (org.forgerock.openam.scripting.ScriptException | SSOException | SMSException e) {
			logger.message("Error obtaining httpClient", e);
			throw new ServerException("Error obtaining httpClient: " + e.getMessage(), e);
		}
	}

	private ScriptConfiguration getScriptConfiguration(String realm, String scriptId)
			throws org.forgerock.openam.scripting.ScriptException {

		return scriptingServiceFactory.create(realm).get(scriptId);
	}

	/**
	 * {@inheritDoc}
	 */
	public Map<String, Object> evaluateScope(AccessToken accessToken) {
		final Map<String, Object> map = new HashMap<>();
		final Set<String> scopes = accessToken.getScope();

		if (scopes.isEmpty()) {
			return map;
		}

		final String resourceOwner = accessToken.getResourceOwnerId();
		final String clientId = accessToken.getClientId();
		final String realm = accessToken.getRealm();

		AMIdentity id = null;
		try {
			if (clientId != null && CLIENT_CREDENTIALS_GRANT_TYPE.equals(accessToken.getGrantType())) {
				id = identityManager.getClientIdentity(clientId, realm);
			} else if (resourceOwner != null) {
				id = identityManager.getResourceOwnerIdentity(resourceOwner, realm);
			}
		} catch (Exception e) {
			logger.error("Unable to get user identity", e);
		}
		if (id != null) {
			for (String scope : scopes) {
				try {
					Set<String> attributes = id.getAttribute(scope);
					StringBuilder builder = new StringBuilder();
					if (CollectionUtils.isNotEmpty(attributes)) {
						Iterator<String> iter = attributes.iterator();
						while (iter.hasNext()) {
							builder.append(iter.next());
							if (iter.hasNext()) {
								builder.append(MULTI_ATTRIBUTE_SEPARATOR);
							}
						}
					}
					map.put(scope, builder.toString());
				} catch (Exception e) {
					logger.error("Unable to get attribute", e);
				}
			}
		}

		return map;
	}

	/**
	 * {@inheritDoc}
	 */
	public Map<String, String> additionalDataToReturnFromAuthorizeEndpoint(Map<String, Token> tokens,
			OAuth2Request request) {
		return Collections.emptyMap();
	}

	/**
	 * {@inheritDoc}
	 */
	public void additionalDataToReturnFromTokenEndpoint(AccessToken accessToken, OAuth2Request request)
			throws ServerException, InvalidClientException, NotFoundException {
		final Set<String> scope = accessToken.getScope();
		if (scope != null && scope.contains(OPENID)) {
			final Map.Entry<String, Supplier<String>> tokenEntry = openIDTokenIssuer.issueToken(accessToken, request);
			if (tokenEntry != null) {
				accessToken.addExtraData(tokenEntry.getKey(), tokenEntry.getValue());
			}
		}
	}

	private Long getUpdatedAt(String username, String realm, OAuth2Request request) throws NotFoundException {
		try {
			final OAuth2ProviderSettings providerSettings = providerSettingsFactory.get(request);
			String modifyTimestampAttributeName;
			String createdTimestampAttributeName;
			try {
				modifyTimestampAttributeName = providerSettings.getModifiedTimestampAttributeName();
				createdTimestampAttributeName = providerSettings.getCreatedTimestampAttributeName();
			} catch (ServerException e) {
				logger.error("Unable to read last modified attribute from datastore", e);
				return DEFAULT_TIMESTAMP;
			}

			if (modifyTimestampAttributeName == null && createdTimestampAttributeName == null) {
				return null;
			}

			final AMHashMap timestamps = getTimestamps(username, realm, modifyTimestampAttributeName,
					createdTimestampAttributeName);
			final String modifyTimestamp = CollectionHelper.getMapAttr(timestamps, modifyTimestampAttributeName);

			if (modifyTimestamp != null) {
				synchronized (TIMESTAMP_DATE_FORMAT) {
					return TIMESTAMP_DATE_FORMAT.parse(modifyTimestamp).getTime() / 1000;
				}
			} else {
				final String createTimestamp = CollectionHelper.getMapAttr(timestamps, createdTimestampAttributeName);

				if (createTimestamp != null) {
					synchronized (TIMESTAMP_DATE_FORMAT) {
						return TIMESTAMP_DATE_FORMAT.parse(createTimestamp).getTime() / 1000;
					}
				} else {
					return DEFAULT_TIMESTAMP;
				}
			}
		} catch (IdRepoException e) {
			if (logger.errorEnabled()) {
				logger.error("ScopeValidatorImpl" + ".getUpdatedAt: " + "error searching Identities with username : "
						+ username, e);
			}
		} catch (SSOException | ParseException e) {
			logger.warning("Error getting updatedAt attribute", e);
		}

		return 0L;
	}

	private AMHashMap getTimestamps(String username, String realm, String modifyTimestamp, String createTimestamp)
			throws IdRepoException, SSOException {
		final SSOToken token = AccessController.doPrivileged(AdminTokenAction.getInstance());
		final AMIdentityRepository amIdRepo = new AMIdentityRepository(token, realm);
		final IdSearchControl searchConfig = new IdSearchControl();
		searchConfig.setReturnAttributes(new HashSet<>(Arrays.asList(modifyTimestamp, createTimestamp)));
		searchConfig.setMaxResults(0);
		final IdSearchResults searchResults = amIdRepo.searchIdentities(IdType.USER, username, searchConfig);

		final Iterator searchResultsItr = searchResults.getResultAttributes().values().iterator();

		if (searchResultsItr.hasNext()) {
			return (AMHashMap) searchResultsItr.next();
		} else {
			logger.warning("Error retrieving timestamps from datastore");
			throw new IdRepoException();
		}
	}
}
