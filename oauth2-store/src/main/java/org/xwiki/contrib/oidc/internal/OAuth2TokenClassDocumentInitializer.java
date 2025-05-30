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
package org.xwiki.contrib.oidc.internal;

import javax.inject.Named;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;

import com.xpn.xwiki.doc.AbstractMandatoryClassInitializer;
import com.xpn.xwiki.objects.classes.BaseClass;

/**
 * Document initializer for the OAuth2 Token configuration class.
 *
 * @version $Id$
 * @since 2.15.0
 */
@Component
@Named(NimbusOAuth2Token.CLASS_FULLNAME)
@Singleton
public class OAuth2TokenClassDocumentInitializer extends AbstractMandatoryClassInitializer
{
    /**
     * Builds a new {@link OAuth2TokenClassDocumentInitializer}.
     */
    public OAuth2TokenClassDocumentInitializer()
    {
        super(NimbusOAuth2Token.CLASS_REFERENCE, "OAuth2 Token Class");
    }

    @Override
    protected void createClass(BaseClass xclass)
    {
        xclass.addTextField(NimbusOAuth2Token.FIELD_CLIENT_CONFIGURATION_NAME, "Client configuration name", 255);
        xclass.addPasswordField(NimbusOAuth2Token.FIELD_ACCESS_TOKEN, "Access token", 255);
        xclass.addPasswordField(NimbusOAuth2Token.FIELD_REFRESH_TOKEN, "Refresh token", 255);
        xclass.addTextField(NimbusOAuth2Token.FIELD_TYPE, "Type", 255);
        xclass.addStaticListField(NimbusOAuth2Token.FIELD_SCOPE, "Scope", 10, true, "");
        xclass.addNumberField(NimbusOAuth2Token.FIELD_EXPIRES_AT, "Expires at", 255, "long");
    }
}
