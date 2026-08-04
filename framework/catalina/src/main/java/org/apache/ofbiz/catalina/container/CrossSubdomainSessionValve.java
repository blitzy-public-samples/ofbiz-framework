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
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.MimeHeaders;

public class CrossSubdomainSessionValve extends ValveBase {

    private static final String MODULE = CrossSubdomainSessionValve.class.getName();

    CrossSubdomainSessionValve() {
        super();
    }

    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {

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
