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

        // A LOAD BALANCER PROBE IS PASSED STRAIGHT DOWN THE PIPELINE, TOUCHING NEITHER THE SESSION NOR THE
        // COOKIE.
        //
        // This valve is installed at ENGINE scope, above every context, and the next statement calls
        // getSession(true) on every request it sees. A health probe arrives several times a minute per
        // instance, from a caller that keeps no cookie jar and never returns a session, so each one would
        // create a session that is used once and then held until it expired on its own - and would have a
        // domain-widened JSESSIONID cookie written onto a response the prober does not read (CWE-400).
        // Enabling cross-subdomain sessions would therefore have changed what being polled costs, which is
        // not something a cookie-scope setting should decide.
        //
        // It is also the ONE layer that can cover a probe path addressed to a context that neither maps nor
        // allow-lists it - a mis-pointed target group, or a proxy that rewrites the prefix - because this
        // valve runs before any context sees the request, while the endpoint's own discard runs only where
        // the endpoint is mapped.
        //
        // The paths are HealthCheckServlet.isProbePath, its own published contract, rather than literals
        // copied here, so the exemption cannot drift from what the endpoint actually serves. The match is
        // EXACT: a near miss such as /health/live-x is not exempt and is handled by the pipeline as any
        // other request, which is what keeps this from becoming a prefix-shaped bypass of a valve the
        // deployment asked for.
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
     * Reports whether this request is one of the load-balancer health probes.
     *
     * <p>The path is taken from the DECODED request URI and the context path is removed, so what is
     * compared is the path within the webapp - which is the form {@link HealthCheckServlet#isProbePath}
     * defines and the form the endpoint's own {@code servlet-mapping} matches. Decoded rather than raw
     * because a raw URI can spell the same path with percent escapes, and the comparison is an exact
     * equality: nothing else may take this branch.
     *
     * <p>Every failure answers false, which is the safe direction: a request this method cannot classify
     * is treated as an ordinary request and handled by the valve exactly as it was before, rather than
     * being exempted from it.
     *
     * @param request the request being handled
     * @return true only when the path within its webapp is exactly a probe path
     */
    private static boolean isHealthProbe(Request request) {
        try {
            String uri = request.getDecodedRequestURI();
            if (uri == null) {
                return false;
            }
            String contextPath = request.getContextPath();
            if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
                uri = uri.substring(contextPath.length());
            }
            return HealthCheckServlet.isProbePath(uri);
        } catch (RuntimeException unavailable) {
            // Absorbed deliberately, and NOT logged: this runs on every request, so a fault here would
            // otherwise write a line per request, and the consequence of answering false is only that the
            // valve behaves as it did before this exemption existed.
            return false;
        }
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
        // The delegator is published as a request attribute by ContextFilter, and this valve is installed at
        // ENGINE scope - above every context - so that filter has not run yet and the attribute is normally
        // absent. Handing the resulting null to the entity-aware lookup reaches EntityQuery.use(null), whose
        // NullPointerException is not a GenericEntityException and so is not stopped by the catch inside
        // EntityUtilProperties. Thrown from the outermost valve, above ErrorReportValve and StandardHostValve,
        // it escapes to CoyoteAdapter, which answers a bare HTTP 500 with no body. Because this method runs
        // only when the request already carries a session cookie, the visible effect was that every returning
        // client received an unexplained 500 on every path as soon as cross-subdomain sessions were enabled.
        //
        // The file-based lookup keeps the documented meaning of 'cookie.domain' in url.properties and is
        // exactly the source EntityUtilProperties itself consults when no SystemProperty row exists; the
        // entity-aware lookup, which additionally honours such a row, is still used whenever a delegator
        // really is available. This is the minimum change that makes the configurable
        // enable-cross-subdomain-sessions setting usable at all.
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
                        // The cookie NAME and the domain being applied, never either Set-Cookie VALUE.
                        // Both of those carry the session identifier, and this branch runs on every
                        // response that rewrites the session cookie, so logging them put a usable session
                        // token into the container log, into 'docker logs' and into every aggregator
                        // collecting it - anybody who can read a line can replay the session it names
                        // (CWE-532). What an operator needs from this line is that the rewrite happened
                        // and which domain it applied, and that is what is recorded.
                        if (Debug.verboseOn()) {
                            Debug.logVerbose("CrossSubdomainSessionValve: rewrote the [" + cookie.getName()
                                    + "] Set-Cookie header with domain [" + cookieDomain
                                    + "]; neither cookie value is logged, because both carry the session id",
                                    MODULE);
                        }
                        value.setString(newCookieValue);
                    }
                }
            }
        }
    }
}
