/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.contrib.oidc.provider.internal.endpoint;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.script.ScriptContext;

import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.oidc.provider.internal.OIDCManager;
import org.xwiki.contrib.oidc.provider.internal.OIDCResourceReference;
import org.xwiki.contrib.oidc.provider.internal.session.ProviderOIDCSessions;
import org.xwiki.contrib.oidc.provider.internal.store.BaseObjectOIDCConsent;
import org.xwiki.contrib.oidc.provider.internal.store.OIDCStore;
import org.xwiki.csrf.CSRFToken;
import org.xwiki.script.ScriptContextManager;

import com.nimbusds.oauth2.sdk.AuthorizationCode;
import com.nimbusds.oauth2.sdk.AuthorizationRequest;
import com.nimbusds.oauth2.sdk.AuthorizationSuccessResponse;
import com.nimbusds.oauth2.sdk.OAuth2Error;
import com.nimbusds.oauth2.sdk.Response;
import com.nimbusds.oauth2.sdk.http.HTTPRequest;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.openid.connect.sdk.AuthenticationErrorResponse;
import com.nimbusds.openid.connect.sdk.AuthenticationRequest;
import com.nimbusds.openid.connect.sdk.AuthenticationSuccessResponse;
import com.nimbusds.openid.connect.sdk.Nonce;
import com.nimbusds.openid.connect.sdk.OIDCClaimsRequest;
import com.nimbusds.openid.connect.sdk.OIDCError;
import com.nimbusds.openid.connect.sdk.OIDCScopeValue;
import com.nimbusds.openid.connect.sdk.Prompt;
import com.nimbusds.openid.connect.sdk.claims.IDTokenClaimsSet;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.user.api.XWikiUser;

/**
 * Authorization endpoint for OpenID Connect.
 * 
 * @version $Id$
 */
@Component
@Named(AuthorizationOIDCEndpoint.HINT)
@Singleton
public class AuthorizationOIDCEndpoint implements OIDCEndpoint
{
    /**
     * The endpoint name.
     */
    public static final String HINT = "authorization";

    @Inject
    private Provider<XWikiContext> xcontextProvider;

    @Inject
    private OIDCStore oidcStore;

    @Inject
    private OIDCManager manager;

    @Inject
    private CSRFToken csrf;

    @Inject
    private ScriptContextManager scripts;

    @Inject
    private ProviderOIDCSessions sessions;

    @Inject
    private Logger logger;

    @Override
    public Response handle(HTTPRequest httpRequest, OIDCResourceReference reference) throws Exception
    {
        this.logger.debug("OIDC: Entering [authorization] endpoint");

        // Parse the request
        AuthorizationRequest request = AuthorizationRequest.parse(httpRequest);

        if (request.getScope() != null && request.getScope().contains(OIDCScopeValue.OPENID)) {
            this.logger.debug("OIDC: OpenID client");

            // OpenID Connect
            request = AuthenticationRequest.parse(httpRequest);
        } else {
            this.logger.debug("OIDC: Not OpenID Connect client, assuming OAuth2");
        }

        XWikiContext xcontext = this.xcontextProvider.get();

        ///////////////////////////////////////////////////////
        // Authentication
        ///////////////////////////////////////////////////////

        // Authenticate
        XWikiUser user = xcontext.getWiki().checkAuth(xcontext);
        if (user == null) {
            if (prompt(request, Prompt.Type.NONE, false)) {
                // Interactive login is disabled but the user was not automatically authenticated
                return new AuthenticationErrorResponse(request.getRedirectionURI(), OIDCError.INTERACTION_REQUIRED,
                    request.getState(), null);
            }

            xcontext.getWiki().getAuthService().showLogin(xcontext);

            return null;
        } else if (prompt(request, Prompt.Type.LOGIN, false)) {
            // Login is forced by the client
            xcontext.getWiki().getAuthService().showLogin(xcontext);

            return null;
        }

        this.logger.debug("OIDC: Current user: [{}]", user);

        // Set context user
        xcontext.setUser(user.getUser());

        ///////////////////////////////////////////////////////
        // Consent
        ///////////////////////////////////////////////////////

        // Required to look up the client in the provider's database
        ClientID clientID = request.getClientID();

        this.logger.debug("OIDC: Client id: [{}]", user);

        // Get current consent for provided client id
        BaseObjectOIDCConsent consent =
            this.oidcStore.getConsent(clientID, request.getRedirectionURI(), xcontext.getUserReference());

        this.logger.debug("OIDC: Existing consent: [{}]", consent);

        // TODO: Check if claims are the same
        if (consent == null || prompt(request, Prompt.Type.CONSENT, false)) {
            // Impossible to validate consent without user interaction
            if (prompt(request, Prompt.Type.NONE, false)) {
                return new AuthenticationErrorResponse(request.getRedirectionURI(), OIDCError.CONSENT_REQUIRED,
                    request.getState(), null);
            }

            // Resolve claims
            OIDCClaimsRequest resolvedClaims = null;
            if (request instanceof AuthenticationRequest) {
                resolvedClaims = OIDCClaimsRequest.resolve(request.getResponseType(), request.getScope())
                    .add(((AuthenticationRequest) request).getOIDCClaims());
            }

            // Ask user for consent
            Boolean consentAnswer = getConsent(httpRequest);
            if (consentAnswer == null) {
                return askConsent(request, httpRequest, resolvedClaims);
            } else if (!consentAnswer) {
                return new AuthenticationErrorResponse(request.getRedirectionURI(), OAuth2Error.UNAUTHORIZED_CLIENT,
                    request.getState(), null);
            }

            // Create new consent
            consent = this.oidcStore.createCurrentUserConsent();

            consent.setClientID(clientID);
            consent.setRedirectURI(request.getRedirectionURI());

            // Convert scope into individual claims
            if (resolvedClaims != null) {
                consent.setClaims(resolvedClaims.getUserInfoClaimsRequest());
            }

            // Set access token if needed
            if (request.getResponseType().impliesImplicitFlow()) {
                this.oidcStore.createAccessToken(consent);
            }

            // Save consent
            this.oidcStore.saveConsent(consent, "Add new OIDC consent");

            this.logger.debug("OIDC: New consent: [{}]", consent);
        } else if (request.getResponseType().impliesImplicitFlow()) {
            // We have to set a new token in case of implicit flow since we don't know the clear version of the stored
            // one
            this.oidcStore.createAccessToken(consent);
            this.oidcStore.saveConsent(consent, "Update token");
        }

        Nonce nonce = request instanceof AuthenticationRequest ? ((AuthenticationRequest) request).getNonce() : null;

        // Generate authorization code or tokens depending on the response type
        IDTokenClaimsSet idToken = null;
        AuthorizationCode authorizationCode = null;
        if (request.getResponseType().impliesCodeFlow()) {
            authorizationCode = new AuthorizationCode();
        } else if (request.getResponseType().impliesImplicitFlow()) {
            if (request instanceof AuthenticationRequest) {
                idToken = this.manager.createdIdToken(clientID, consent.getUserReference(), nonce,
                    ((AuthenticationRequest) request).getOIDCClaims().getIDTokenClaimsRequest());
            }
        }

        // Remember authorization code
        this.oidcStore.setAuthorizationCode(authorizationCode, consent.getDocumentReference(), nonce);

        // Remember the user
        this.sessions.addSession(this.oidcStore.getSubject(consent.getUserReference()), clientID);

        // Create response
        if (request.getResponseType().impliesCodeFlow()) {
            if (request instanceof AuthenticationRequest) {
                // OpenID Connect
                return new AuthenticationSuccessResponse(request.getRedirectionURI(), authorizationCode, null, null,
                    request.getState(), null, this.manager.getIssuer(), null);
            } else {
                // OAuth2
                return new AuthorizationSuccessResponse(request.getRedirectionURI(), authorizationCode, null,
                    request.getState(), this.manager.getIssuer(), null);
            }
        } else {
            if (request instanceof AuthenticationRequest) {
                // OpenID Connect
                return new AuthenticationSuccessResponse(request.getRedirectionURI(), null,
                    this.manager.signToken(idToken), consent.getAccessToken(), request.getState(), null,
                    this.manager.getIssuer(), null);
            } else {
                // OAuth2
                return new AuthorizationSuccessResponse(request.getRedirectionURI(), null, consent.getAccessToken(),
                    request.getState(), this.manager.getIssuer(), null);
            }
        }
    }

    private boolean prompt(AuthorizationRequest request, Prompt.Type type, boolean def)
    {
        if (request instanceof AuthenticationRequest) {
            // OpenID Connect
            if (((AuthenticationRequest) request).getPrompt() != null) {
                return ((AuthenticationRequest) request).getPrompt().contains(type);
            }
        } else {
            // OAuth2
            return def;
        }

        return false;
    }

    private Boolean getConsent(HTTPRequest httpRequest)
    {
        Map<String, List<String>> parameters = httpRequest.getQueryStringParameters();

        // The user explicitly refused access to the client
        if (parameters.get("consent_refuse") != null) {
            return false;
        }

        // Check if user explicitly gave consent to the client
        if (parameters.get("consent_accept") != null) {
            List<String> tokens = parameters.get("form_token");
            if (this.csrf.isTokenValid(tokens.get(0))) {
                return true;
            } else {
                // Looks like some client tried to hack consent
                // TODO: log something ? ban the client ?
            }
        }

        // Ask for user consent
        return null;
    }

    private Response askConsent(AuthorizationRequest request, HTTPRequest httpRequest, OIDCClaimsRequest resolvedClaims)
        throws Exception
    {
        // Set various information in the script context
        Map<String, Object> oidc = new HashMap<>();
        oidc.put("request", request);
        oidc.put("httprequest", httpRequest);
        oidc.put("resolvedClaims", resolvedClaims);
        this.scripts.getScriptContext().setAttribute("oidc", oidc, ScriptContext.ENGINE_SCOPE);

        return this.manager.executeTemplate("oidc/provider/consent.vm");
    }
}
