/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.apache.ofbiz.catalina.container;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;

import org.apache.catalina.Context;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.util.SessionConfig;
import org.apache.catalina.valves.ValveBase;
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.util.EntityUtilProperties;
import org.apache.ofbiz.webapp.control.HealthCheckServlet;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.MimeHeaders;

public class CrossSubdomainSessionValve extends ValveBase {

    private static final String MODULE = CrossSubdomainSessionValve.class.getName();

    CrossSubdomainSessionValve() {
        super();
    }

    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {

        // The load-balancer health probes are exempt from everything this valve does.
        //
        // What the exemption is worth, stated exactly. This valve is installed at ENGINE scope, so it
        // runs before any webapp's filter chain, and getSession(true) below would create a session for
        // every request it sees. The probe endpoint is a servlet reached through the webtools chain, and
        // ControlFilter in that chain calls getSession() unconditionally, so a probe answered there is
        // preceded by a session whether this valve runs or not - the exemption does not make a probe
        // session-free, and nothing here should be read as claiming it does.
        //
        // Two things it does buy, both of them narrow and both of them real. A probe path can be
        // addressed to ANY context - a mis-pointed target group, or a proxy rewriting the prefix - and a
        // context whose chain neither allow-lists nor maps the path would otherwise have a session minted
        // for it here, at engine scope, for a request that is going to be refused anyway. And this valve
        // rewrites the JSESSIONID cookie onto a wider domain, which is a cross-subdomain cookie on a
        // response that a probe client never reads and never returns.
        //
        // Skipping straight to the next valve is the whole exemption. Nothing is lost by it: a probe has
        // no session to share across subdomains, which is the only thing this valve exists to arrange.
        if (isHealthProbe(request)) {
            getNext().invoke(request, response);
            return;
        }

        // this will cause Request.doGetSession to create the session cookie if necessary
        request.getSession(true);

        // replace any Tomcat-generated session cookies with our own
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (SessionConfig.getSessionCookieName(null).equals(cookie.getName())) {
                    replaceCookie(request, response, cookie);
                }
            }
        }

        // process the next valve
        getNext().invoke(request, response);
    }

    /**
     * Reports whether the request addresses one of the load-balancer health probe paths.
     *
     * <p>The two paths are not restated here. {@link HealthCheckServlet#isProbePath(String)} owns them,
     * so this valve and the endpoint itself can never disagree about what a probe is - a second copy of
     * the literals would be a copy that drifts, and a valve exempting a stale spelling would quietly
     * resume creating a session at engine scope for every probe.
     *
     * <p>The mapped path is tried first, using the same two accessors {@code HealthCheckServlet} uses,
     * so both arrive at the identical string for either mapping style. They are populated even at engine
     * scope: Tomcat's {@code CoyoteAdapter} runs the mapper in {@code postParseRequest}, before it
     * invokes the engine pipeline, and both values are decoded and normalised by then - so no
     * {@code ../}, {@code %2e} or {@code ;jsessionid} spelling reaches the comparison.
     *
     * <p>The decoded request URI is then tried as a fallback, with the context path removed, so the
     * exemption does not depend on the probe servlet being mapped in the context that received the
     * request. Only {@code webtools} declares the probe mapping, yet a probe path can be addressed to
     * any context - a mis-pointed target group, or a proxy rewriting the prefix - and such a request must
     * not have a session minted for it here, at engine scope, when the context it lands in is going to
     * refuse it anyway. Exempting anything spelt like a probe is the safe direction: the worst it costs
     * is one cross-subdomain cookie that a probe client never wanted.
     *
     * @param request the request being processed
     * @return {@code true} if this request is a health probe and must be passed straight through
     */
    private static boolean isHealthProbe(Request request) {
        StringBuilder mapped = new StringBuilder();
        String servletPath = request.getServletPath();
        if (servletPath != null) {
            mapped.append(servletPath);
        }
        String pathInfo = request.getPathInfo();
        if (pathInfo != null) {
            mapped.append(pathInfo);
        }
        if (HealthCheckServlet.isProbePath(mapped.toString())) {
            return true;
        }
        Context context = request.getContext();
        String decodedUri = request.getDecodedRequestURI();
        if (context == null || decodedUri == null) {
            return false;
        }
        String contextPath = context.getPath() == null ? "" : context.getPath();
        if (!decodedUri.startsWith(contextPath)) {
            return false;
        }
        return HealthCheckServlet.isProbePath(decodedUri.substring(contextPath.length()));
    }

    /**
     * Replace cookie.
     * @param request the request
     * @param response the response
     * @param cookie the cookie
     */
    private void replaceCookie(Request request, Response response, Cookie cookie) {

        // Copy the existing session cookie, but use a different domain (only if domain is valid).
        //
        // The delegator is published as a request attribute by ContextFilter. This valve runs at ENGINE scope,
        // above every context, so that filter has NOT run yet and the attribute is normally absent. Handing the
        // resulting null to the entity-aware lookup reaches EntityQuery.use(null), and the NullPointerException
        // that follows is not a GenericEntityException, so the catch inside EntityUtilProperties does not stop
        // it. Thrown from the outermost valve - above ErrorReportValve and StandardHostValve - it escapes to
        // CoyoteAdapter, which answers a bare HTTP 500 with no body and logs nothing. Because this method runs
        // only when the request already carries a session cookie, the visible effect was that every returning
        // client received an unexplained 500 on every path once cross-subdomain sessions were enabled.
        //
        // Falling back to the file-based lookup keeps the documented meaning of the property - 'cookie.domain'
        // in url.properties - and is exactly the source EntityUtilProperties itself consults when the property
        // has no SystemProperty row. The entity-aware lookup, which additionally honours such a row, is still
        // used whenever a delegator really is available.
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        String cookieDomain = delegator == null
                ? UtilProperties.getPropertyValue("url", "cookie.domain", "")
                : EntityUtilProperties.getPropertyValue("url", "cookie.domain", "", delegator);

        if (UtilValidate.isEmpty(cookieDomain)) {
            String serverName = request.getServerName();
            String[] domainArray = serverName.split("\\.");
            // check that the domain isn't an IP address
            if (domainArray.length == 4) {
                boolean isIpAddress = true;
                for (String domainSection : domainArray) {
                    if (!UtilValidate.isIntegerInRange(domainSection, 0, 255)) {
                        isIpAddress = false;
                        break;
                    }
                }
                if (isIpAddress) {
                    return;
                }
            }
            if (domainArray.length > 2) {
                cookieDomain = "." + domainArray[domainArray.length - 2] + "." + domainArray[domainArray.length - 1];
            }
        }

        if (UtilValidate.isNotEmpty(cookieDomain)) {
            Cookie newCookie = new Cookie(cookie.getName(), cookie.getValue());
            if (cookie.getPath() != null) {
                newCookie.setPath(cookie.getPath());
            }
            newCookie.setDomain(cookieDomain);
            newCookie.setMaxAge(cookie.getMaxAge());
            newCookie.setSecure(cookie.getSecure());
            newCookie.setHttpOnly(cookie.isHttpOnly());

            // if the response has already been committed, our replacement strategy will have no effect
            if (response.isCommitted()) {
                Debug.logError("CrossSubdomainSessionValve: response was already committed!", MODULE);
            }

            // find the Set-Cookie header for the existing cookie and replace its value with new cookie
            MimeHeaders mimeHeaders = request.getCoyoteRequest().getMimeHeaders();
            for (int i = 0, size = mimeHeaders.size(); i < size; i++) {
                if (mimeHeaders.getName(i).equals("Set-Cookie")) {
                    MessageBytes value = mimeHeaders.getValue(i);
                    if (value.indexOf(cookie.getName()) >= 0) {
                        String newCookieValue = request.getContext().getCookieProcessor().generateHeader(newCookie, request);
                        if (Debug.verboseOn()) {
                            Debug.logVerbose("CrossSubdomainSessionValve: old Set-Cookie value: " + value.toString(), MODULE);
                        }
                        if (Debug.verboseOn()) {
                            Debug.logVerbose("CrossSubdomainSessionValve: new Set-Cookie value: " + newCookieValue, MODULE);
                        }
                        value.setString(newCookieValue);
                    }
                }
            }
        }
    }
}
