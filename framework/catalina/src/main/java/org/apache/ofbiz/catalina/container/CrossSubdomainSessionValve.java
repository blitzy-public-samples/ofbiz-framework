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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import jakarta.servlet.ServletException;

import org.apache.catalina.Context;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.DelegatorFactory;
import org.apache.ofbiz.entity.util.EntityUtilProperties;

/**
 * Shares the HTTP session cookie across the sub-domains of one parent domain, by issuing it for the domain
 * {@code cookie.domain} names.
 *
 * <p>Installed on the Tomcat ENGINE pipeline by {@code CatalinaContainer} when the catalina container's
 * {@code enable-cross-subdomain-sessions} property is true - set from
 * {@code OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS} by {@code docker/docker-entrypoint.sh}. It is off by default,
 * and an unconfigured deployment behaves as if this class did not exist.
 *
 * <p><strong>What it does.</strong> It applies the configured domain to the webapp's session-cookie
 * configuration - {@code Context.setSessionCookieDomain} - and leaves the cookie itself to Tomcat. That is
 * what makes the widening complete and safe. Tomcat builds the whole cookie, so the {@code Path},
 * {@code Secure} and {@code HttpOnly} attributes it would have carried are untouched and the
 * {@code SameSite} attribute {@code SameSiteFilter} appends afterwards still lands on it. And it covers
 * EVERY session cookie the webapp issues, including the one issued when the session id is rotated at login
 * to prevent session fixation - which is the cookie that actually matters here, because it is the one the
 * authenticated session travels in. Rewriting a generated {@code Set-Cookie} header in this valve could
 * reach neither: rebuilding the cookie from its parts drops attributes, and the rotation replaces the
 * header this valve would have written, downstream, after the response has usually been committed.
 *
 * <p><strong>Where the domain comes from, and why only from there.</strong> From {@code cookie.domain} in
 * {@code url.properties} ({@code OFBIZ_COOKIE_DOMAIN} in a container) - the deployment's own statement of
 * the parent domain its clients use. It is deliberately NOT derived from the host a request was addressed
 * to. A webapp has ONE session-cookie configuration, shared by every client, while a request host is one
 * client's view: deriving the domain from a request pins whatever host arrived first onto the whole webapp,
 * and every client that does not sit under that parent domain then receives a cookie RFC 6265 requires it to
 * discard - a silent session outage, one stray {@code Host} header wide. So the domain is a deployment
 * decision, read from deployment configuration, and identical for every request; applying it is therefore
 * idempotent and free of any race between concurrent requests.
 *
 * <p>With {@code cookie.domain} unset there is nothing this valve can safely do, so it reports that once per
 * webapp and leaves session cookies host-only. The application serves normally either way: enabling the
 * setting can slow nothing down, break nothing and hide nothing.
 *
 * <p><strong>The domain is normalised and validated before it is applied.</strong> A leading dot is removed:
 * it is the pre-RFC-6265 spelling, {@code Rfc6265CookieProcessor} REFUSES it, and RFC 6265 already treats a
 * {@code Domain} as covering its sub-domains without one. A value that is not a valid domain is refused here
 * rather than handed to Tomcat, because the cookie processor's refusal surfaces as an
 * {@code IllegalArgumentException} out of {@code getSession()} - inside the webapp, for every session created
 * afterwards, where nothing contains it.
 *
 * <p><strong>Why nothing here is allowed to throw.</strong> An engine valve runs ahead of the host's
 * {@code ErrorReportValve}, so an exception escaping it is not turned into an error page: it reaches
 * Tomcat's adapter, which answers an empty {@code 500} that no OFBiz log records, and it does so for every
 * request. Sharing a cookie across sub-domains is a convenience, so a failure is logged and the request is
 * served with the cookie Tomcat would have issued anyway. This is not defensive decoration: enabling this
 * setting used to fail every session-bearing request exactly that way, because the property lookup below was
 * handed the {@code delegator} request attribute, which an engine valve runs too early to see.
 */
public class CrossSubdomainSessionValve extends ValveBase {

    private static final String MODULE = CrossSubdomainSessionValve.class.getName();

    /**
     * The RFC 6265 {@code domain-value} grammar, as {@code Rfc6265CookieProcessor} enforces it: dot-separated
     * labels of letters, digits and hyphens, each beginning and ending with a letter or a digit.
     */
    private static final Pattern DOMAIN = Pattern.compile(
            "[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*");

    /** What has already been reported, so a lasting condition is one log line rather than one per request. */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    CrossSubdomainSessionValve() {
        super();
    }

    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {
        try {
            shareSessionCookie(request.getContext(), (Delegator) request.getAttribute("delegator"));
        } catch (RuntimeException failure) {
            // Contained rather than propagated - see the class comment. The request carries on with the
            // cookie Tomcat would have issued anyway, and the reason is in the log instead of being an
            // empty 500 with no explanation anywhere.
            Debug.logError(failure, "The session cookie could not be shared across sub-domains, so it is"
                    + " issued for this host alone", MODULE);
        }

        // process the next valve
        getNext().invoke(request, response);
    }

    /**
     * Makes the webapp issue its session cookies for the configured domain.
     *
     * @param context the webapp the request was mapped to, null when it was mapped to none
     * @param requestDelegator the delegator on the request, which an engine valve runs too early to have
     */
    private void shareSessionCookie(Context context, Delegator requestDelegator) {
        if (context == null) {
            // No webapp was matched, so there is no session, no cookie, and nothing to widen.
            return;
        }

        String domain = cookieDomain(requestDelegator);
        if (UtilValidate.isEmpty(domain)) {
            report("unconfigured " + context.getName(), "Session cookies of [" + context.getName() + "] cannot be"
                    + " shared across sub-domains: enable-cross-subdomain-sessions is on, but cookie.domain in"
                    + " url.properties names no domain to issue them for (OFBIZ_COOKIE_DOMAIN in a container)."
                    + " They are issued for the requested host alone until it is set. It is not derived from the"
                    + " request, deliberately: one webapp has one session-cookie configuration, and a domain"
                    + " taken from whichever host arrived first would be sent to every other client too."
                    + " Reported once per webapp.");
            return;
        }

        if (!domain.equals(context.getSessionCookieDomain())) {
            // Idempotent: every request resolves the same configured value, so concurrent requests cannot
            // disagree about it and a steady state costs one string comparison.
            context.setSessionCookieDomain(domain);
            Debug.logInfo("Session cookies of [" + context.getName() + "] are issued for the domain [" + domain
                    + "], so they are shared across its sub-domains", MODULE);
        }
    }

    /**
     * Returns the domain this deployment's session cookies belong to.
     *
     * @param requestDelegator the delegator on the request, or null
     * @return the configured domain in the form a cookie may carry it, or an empty string when none is
     *     configured or the configured value is not usable
     */
    private static String cookieDomain(Delegator requestDelegator) {
        String configured = configuredCookieDomain(requestDelegator);
        if (UtilValidate.isEmpty(configured)) {
            return "";
        }
        String domain = normalise(configured);
        if (!DOMAIN.matcher(domain).matches()) {
            report("invalid " + domain, "Session cookies cannot be issued for the domain [" + domain + "], taken"
                    + " from cookie.domain in url.properties: it is not a domain a cookie may name. RFC 6265"
                    + " allows letters, digits and hyphens in dot-separated labels, each starting and ending"
                    + " with a letter or a digit - no scheme, no port, no path and no wildcard. Session cookies"
                    + " are issued for the requested host alone until it is corrected. Reported once per value.");
            return "";
        }
        return domain;
    }

    /**
     * Reads {@code cookie.domain}, through the delegator when one can be resolved so that a
     * {@code SystemProperty} override is honoured as it is everywhere else.
     *
     * @param requestDelegator the delegator on the request, or null
     * @return the configured value, or an empty string when none is set
     */
    private static String configuredCookieDomain(Delegator requestDelegator) {
        Delegator delegator = requestDelegator;
        if (delegator == null) {
            // An engine valve runs before any webapp filter, so nothing has put a delegator on the request
            // yet - it is null here on every request, always was, and must not be passed on: the property
            // lookup builds an EntityQuery from it and a null delegator throws out of this valve.
            delegator = DelegatorFactory.getDelegator(null);
        }
        if (delegator == null) {
            // Genuinely unavailable. The file-backed value is then the honest answer, because a
            // SystemProperty override cannot be read without a delegator to read it with.
            return UtilProperties.getPropertyValue("url", "cookie.domain", "");
        }
        return EntityUtilProperties.getPropertyValue("url", "cookie.domain", "", delegator);
    }

    /**
     * Strips the leading dot of the pre-RFC-6265 spelling, and trims, so that a value written the old way
     * works instead of being refused.
     *
     * @param domain the configured domain
     * @return the domain in the form a cookie may carry it
     */
    private static String normalise(String domain) {
        String trimmed = domain.trim();
        int start = 0;
        while (start < trimmed.length() && trimmed.charAt(start) == '.') {
            start++;
        }
        return trimmed.substring(start);
    }

    /**
     * Logs a lasting condition once, so that a misconfiguration is a line an operator can find rather than a
     * line per request.
     *
     * @param key what is being reported, for the once-only test
     * @param message the message to log
     */
    private static void report(String key, String message) {
        if (REPORTED.add(key)) {
            Debug.logError(message, MODULE);
        }
    }
}
