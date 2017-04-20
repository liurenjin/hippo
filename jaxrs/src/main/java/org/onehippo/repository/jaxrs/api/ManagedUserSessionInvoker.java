/*
 * Copyright 2017 Hippo B.V. (http://www.onehippo.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onehippo.repository.jaxrs.api;

import java.util.Locale;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.core.Response;

import org.apache.cxf.jaxrs.JAXRSInvoker;
import org.apache.cxf.message.Exchange;
import org.apache.cxf.message.MessageContentsList;
import org.apache.cxf.transport.http.AbstractHTTPDestination;
import org.onehippo.cms7.services.cmscontext.CmsSessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides and manages a JCR session authenticated for the current logged in user.
 * The name of the logged in user is read from the HTTP session attribute 'hippo:username'.
 * Returns a 403 forbidden error when invoked while no user is logged in.
 */
public class ManagedUserSessionInvoker extends JAXRSInvoker implements SessionDataProvider {

    private static final Logger log = LoggerFactory.getLogger(ManagedUserSessionInvoker.class);

    private static final String ATTRIBUTE_SESSION = ManagedUserSessionInvoker.class.getName() + ".UserSession";
    private static final String ATTRIBUTE_LOCALE  = ManagedUserSessionInvoker.class.getName() + ".Locale";
    private static final String ATTRIBUTE_FARTHEST_REQUEST_HOST = ManagedUserSessionInvoker.class.getName() + ".FarthesRequestHost";;
    private static final MessageContentsList FORBIDDEN = new MessageContentsList(Response.status(Response.Status.FORBIDDEN).build());

    private final Session systemSession;

    public ManagedUserSessionInvoker(final Session systemSession) {
        this.systemSession = systemSession;
    }

    @Override
    public Session getJcrSession(final HttpServletRequest servletRequest) {
        return (Session)servletRequest.getAttribute(ATTRIBUTE_SESSION);
    }

    @Override
    public Locale getLocale(final HttpServletRequest servletRequest) {
        return (Locale)servletRequest.getAttribute(ATTRIBUTE_LOCALE);
    }

    public String getFarthestRequestHost(final HttpServletRequest servletRequest) {
        return (String)servletRequest.getAttribute(ATTRIBUTE_FARTHEST_REQUEST_HOST);
    }

    @Override
    public Object invoke(Exchange exchange, Object requestParams) {
        final HttpServletRequest servletRequest
                = (HttpServletRequest) exchange.getInMessage().get(AbstractHTTPDestination.HTTP_REQUEST);
        final HttpSession httpSession = servletRequest.getSession(false);

        if (httpSession == null) {
            return FORBIDDEN;
        }

        final CmsSessionContext cmsSessionContext = CmsSessionContext.getContext(httpSession);
        if (cmsSessionContext == null) {
            return FORBIDDEN;
        }

        final SimpleCredentials credentials = cmsSessionContext.getRepositoryCredentials();
        try {
            final Session userSession = systemSession.getRepository().login(credentials);
            try {
                servletRequest.setAttribute(ATTRIBUTE_SESSION, userSession);
                servletRequest.setAttribute(ATTRIBUTE_LOCALE, getLocale(cmsSessionContext));
                servletRequest.setAttribute(ATTRIBUTE_FARTHEST_REQUEST_HOST, getFarthestRequestHostInternal(servletRequest));
                return invokeSuper(exchange, requestParams);
            } finally {
                userSession.logout();
            }
        } catch (RepositoryException e) {
            log.warn("Failed to create user session for '{}'", credentials.getUserID(), e);
            return FORBIDDEN;
        }
    }

    // extracted call to super for better testability
    protected Object invokeSuper(Exchange exchange, Object requestParams) {
        return super.invoke(exchange, requestParams);
    }

    protected Locale getLocale(final CmsSessionContext context) {
        Locale locale = context.getLocale();
        if (locale == null) {
            locale = new Locale("en");
        }
        return locale;
    }

    /**
     * Returns the original host information requested by the client
     * @param request
     * @return the farthest request host
     */
    private static String getFarthestRequestHostInternal(HttpServletRequest request) {
        String host = request.getHeader("X-Forwarded-Host");

        if (host != null) {
            String [] hosts = host.split(",");
            return hosts[0].trim();
        }

        host = request.getHeader("Host");

        if (host != null && !"".equals(host)) {
            return host;
        }

        // fallback to request server name for HTTP/1.0 clients.
        // e.g., HTTP/1.0 based browser clients or load balancer not   providing 'Host' header.

        int serverPort = request.getServerPort();

        if (serverPort == 80 || serverPort == 443 || serverPort <= 0) {
            host = request.getServerName();
        } else {
            host = request.getServerName() + ":" + serverPort;
        }

        return host;
    }
}