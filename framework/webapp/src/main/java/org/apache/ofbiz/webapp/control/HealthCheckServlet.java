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
package org.apache.ofbiz.webapp.control;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.DelegatorFactory;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.util.EntityQuery;

/**
 * The load-balancer liveness and readiness endpoints.
 *
 * <p>Two paths, both answered without authentication so that a target-group health check can reach
 * them, and both returning a plain status code with a small JSON document:
 *
 * <ul>
 *   <li>{@code /health/live} - <strong>200</strong> always. It reports that this JVM is running and can
 *       serve a request. It resolves no delegator and issues no query, so it keeps answering while the
 *       database is unavailable, which is what stops a database outage from making a load balancer kill
 *       and replace every instance in the fleet.</li>
 *   <li>{@code /health/ready} - <strong>200</strong> when this instance can serve traffic,
 *       <strong>503</strong> when it cannot. Readiness is database connectivity, measured exactly as the
 *       framework's own {@code ping} service measures it
 *       ({@code org.apache.ofbiz.common.CommonServices#ping}): the {@code SequenceValueItem} rows are
 *       counted, and a count of zero is <em>not</em> ready, just as {@code ping} reports failure for it.
 *       A zero count means the schema has not been initialised - the sequencer has issued nothing - and
 *       an instance whose schema is not in place must not be sent traffic.</li>
 * </ul>
 *
 * <p>The check is strictly read-only: no DDL, no writes and no explicit transaction management, so a
 * serving instance needs no DDL privilege. The delegator is resolved per check rather than in
 * {@code init}, so a datasource that only becomes reachable later flips readiness to 200, and one that
 * later fails flips it to 503, with no restart.
 *
 * <p><strong>A probe leaves nothing behind.</strong> The servlet is registered behind a webapp's
 * ordinary filter chain, and OFBiz's own filters call {@code getSession()} unconditionally, so a session
 * exists by the time this class runs. A prober keeps no cookie jar and never returns a session, so that
 * session would be held until it expired on its own and its cookie could pin a sticky load balancer to
 * an instance. This class therefore invalidates a session that was created <em>for this request</em>
 * ({@code isNew()}) and suppresses its cookie. A probe that arrives carrying somebody else's session
 * leaves that session untouched.
 *
 * <p><strong>Only the two paths are probes.</strong> The servlet is mapped on the {@code /health/}
 * prefix so that it, rather than the container's default servlet, answers a near miss such as
 * {@code /health/live-x} - which lets it discard that request's session too - and it answers 404 for
 * anything that is not exactly one of the two paths. {@code isProbePath} is the single definition of
 * what a probe path is. Only GET and HEAD are accepted; anything else is 405 with an {@code Allow}
 * header.
 *
 * <p><strong>Bounded cost.</strong> A verdict is reused for {@link #VERDICT_FRESH_NANOS}, so continuous
 * polling does not put one query per probe on the datasource, and a not-ready warning is written at most
 * once per {@link #WARNING_INTERVAL_NANOS} so that an unauthenticated caller cannot drive log volume.
 * Failures are logged under a stable event code without the throwable, because an entity-engine or JDBC
 * message routinely names the connection URI, the datasource and the failing SQL.
 *
 * <p>Thread safe: it holds no instance state.
 */
public final class HealthCheckServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final String MODULE = HealthCheckServlet.class.getName();

    /** The liveness path, relative to the webapp's context path. */
    private static final String PROBE_LIVE = "/health/live";

    /** The readiness path, relative to the webapp's context path. */
    private static final String PROBE_READY = "/health/ready";

    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "HEAD");
    private static final String ALLOW_HEADER = "Allow";
    private static final String ALLOW_VALUE = "GET, HEAD";

    /**
     * The entity readiness is measured against, and the one the {@code ping} service uses.
     *
     * <p>It is part of the framework's own seed data and is written by the sequencer in every
     * deployment, so a non-zero count is a fact about a working instance rather than about a particular
     * application's data.
     */
    private static final String READINESS_ENTITY = "SequenceValueItem";

    /** The webapp context parameter naming the delegator this instance serves from. */
    private static final String DELEGATOR_NAME_PARAMETER = "entityDelegatorName";

    private static final String BODY_LIVE_UP = "{\"status\":\"UP\"}";
    private static final String BODY_READY_UP = "{\"status\":\"UP\",\"database\":\"UP\"}";
    private static final String BODY_DOWN = "{\"status\":\"DOWN\"}";

    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String CHARACTER_ENCODING = "UTF-8";
    private static final String CACHE_CONTROL_HEADER = "Cache-Control";
    private static final String CACHE_CONTROL_VALUE = "no-cache, no-store, must-revalidate";
    private static final String CONTENT_TYPE_OPTIONS_HEADER = "X-Content-Type-Options";
    private static final String CONTENT_TYPE_OPTIONS_VALUE = "nosniff";

    private static final String SET_COOKIE_HEADER = "Set-Cookie";
    private static final String DEFAULT_SESSION_COOKIE_NAME = "JSESSIONID";

    /** How long a readiness verdict is reused before the datasource is asked again. */
    private static final long VERDICT_FRESH_NANOS = TimeUnit.SECONDS.toNanos(1L);

    /** How often a not-ready verdict may be written to the log. */
    private static final long WARNING_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1L);

    /** The stable event code an operator greps for. */
    private static final String EVENT_NOT_READY = "HEALTH-READINESS-NOT-READY";

    private static final AtomicReference<Verdict> VERDICT = new AtomicReference<>();
    private static final AtomicLong LAST_WARNING_AT = new AtomicLong(System.nanoTime() - WARNING_INTERVAL_NANOS);

    /**
     * Gates the accepted methods, then hands over to the ordinary GET and HEAD dispatch.
     *
     * <p>The session created for this request is discarded first, so it is discarded whatever the
     * outcome - a probe, a near miss answered with 404, or a refused method.
     *
     * @param request the probe request
     * @param response the response to write
     * @throws ServletException if the container's own dispatch fails
     * @throws IOException if the response cannot be written
     */
    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        discardAnySessionMintedForThisProbe(request, response);
        String method = request.getMethod();
        if (method == null || !ALLOWED_METHODS.contains(method)) {
            response.setHeader(ALLOW_HEADER, ALLOW_VALUE);
            writeResponse(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, BODY_DOWN);
            return;
        }
        super.service(request, response);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleProbe(request, response);
    }

    /**
     * Answers HEAD with exactly the status and headers GET would answer, and no body.
     *
     * <p>Written here rather than delegated to {@link HttpServlet#doHead}, whose default wraps the
     * response in a body-swallowing decorator and calls {@code doGet} only to compute a Content-Length -
     * work a health probe has no use for.
     *
     * @param request the probe request
     * @param response the response to write
     * @throws IOException if the response cannot be written
     */
    @Override
    protected void doHead(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleProbe(request, response);
    }

    /**
     * Reports whether a path within a webapp is one of the two probe paths this class answers.
     *
     * @param pathWithinWebapp the requested path relative to the webapp's context path; may be null
     * @return true if the path is exactly the liveness or the readiness path
     */
    private static boolean isProbePath(String pathWithinWebapp) {
        return PROBE_LIVE.equals(pathWithinWebapp) || PROBE_READY.equals(pathWithinWebapp);
    }

    /**
     * Serves one probe.
     *
     * @param request the probe request
     * @param response the response to write
     * @throws IOException if the response cannot be written
     */
    private static void handleProbe(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = pathWithinWebapp(request);
        if (!isProbePath(path)) {
            // A path this class does not serve fails visibly rather than reporting a false 200, which
            // would let a load balancer keep a broken instance in service.
            writeResponse(response, HttpServletResponse.SC_NOT_FOUND, BODY_DOWN);
        } else if (PROBE_LIVE.equals(path)) {
            writeResponse(response, HttpServletResponse.SC_OK, BODY_LIVE_UP);
        } else if (isInstanceReady(request.getServletContext())) {
            writeResponse(response, HttpServletResponse.SC_OK, BODY_READY_UP);
        } else {
            writeResponse(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, BODY_DOWN);
        }
    }

    /**
     * Returns the requested path with the context path removed.
     *
     * <p>Assembled from the container's own decoded, normalised parts rather than from the raw URI, so a
     * percent-escaped or dot-segment spelling of a probe path cannot be mistaken for something else. A
     * prefix mapping such as {@code /health/*} puts {@code /health} in {@code getServletPath} and the
     * remainder in {@code getPathInfo}.
     *
     * @param request the probe request
     * @return the path within the webapp
     */
    private static String pathWithinWebapp(HttpServletRequest request) {
        String servletPath = request.getServletPath() == null ? "" : request.getServletPath();
        String pathInfo = request.getPathInfo() == null ? "" : request.getPathInfo();
        return servletPath + pathInfo;
    }

    /**
     * Answers whether this instance is ready, reusing a verdict that is still fresh.
     *
     * @param context the webapp context the delegator name is declared in
     * @return true when this instance can serve traffic
     */
    private static boolean isInstanceReady(ServletContext context) {
        Verdict established = VERDICT.get();
        long now = System.nanoTime();
        if (established != null && now - established.atNanos() < VERDICT_FRESH_NANOS) {
            return established.ready();
        }
        boolean ready = runReadinessCheck(context);
        VERDICT.set(new Verdict(ready, System.nanoTime()));
        return ready;
    }

    /**
     * Measures readiness: the datasource answers and the sequencer has issued something.
     *
     * <p>This is {@code CommonServices.ping}'s check, and deliberately the same one - the count of
     * {@code SequenceValueItem} rows, with zero treated as not ready. A database that cannot answer, or
     * that has no such relation because the schema was never initialised, fails the statement and is
     * reported the same way.
     *
     * <p>Fail-closed: every step, the delegator lookup included, runs inside the try, so a dependency
     * failure becomes the 503 this contract promises rather than escaping to the container, which would
     * answer 500 with an HTML error page on a deliberately unauthenticated path.
     *
     * @param context the webapp context the delegator name is declared in
     * @return true when this instance can serve traffic
     */
    private static boolean runReadinessCheck(ServletContext context) {
        try {
            Delegator delegator = baseDelegator(context);
            if (delegator == null) {
                reportNotReady("no delegator is available to this webapp");
                return false;
            }
            if (EntityQuery.use(delegator).from(READINESS_ENTITY).queryCount() == 0L) {
                reportNotReady("the datasource answered but holds no " + READINESS_ENTITY
                        + " row, so its schema is not initialised");
                return false;
            }
            return true;
        } catch (GenericEntityException | RuntimeException failure) {
            // Every remaining failure mode - an unreachable datasource, an exhausted connection pool, an
            // absent entity definition, a servlet context that is not available - collapses into the same
            // verdict under the same code. The throwable is deliberately not logged: its message names
            // the connection URI, the datasource and the failing SQL, none of which may be reachable
            // through an unauthenticated caller's ability to trigger log writes.
            reportNotReady("the datasource could not be reached: " + failure.getClass().getName());
            return false;
        }
    }

    /**
     * Resolves the delegator this instance serves from, reduced to its base tenancy.
     *
     * <p>Taken from the webapp's own {@code entityDelegatorName} context parameter rather than from the
     * {@code ServletContext} attribute a tenant request rewrites, so a probe always reports on this
     * instance's own datasource. A tenanted name such as {@code default#DEMO1} is reduced to its base,
     * because a probe reports on the instance rather than on one tenancy of it.
     *
     * @param context the webapp context
     * @return the delegator, or null when none is available
     */
    private static Delegator baseDelegator(ServletContext context) {
        String declared = context == null ? null : context.getInitParameter(DELEGATOR_NAME_PARAMETER);
        if (UtilValidate.isEmpty(declared)) {
            return null;
        }
        Delegator delegator = DelegatorFactory.getDelegator(declared);
        if (delegator == null) {
            return null;
        }
        String base = delegator.getDelegatorBaseName();
        if (base == null || base.equals(delegator.getDelegatorName())) {
            return delegator;
        }
        Delegator resolved = DelegatorFactory.getDelegator(base);
        return resolved == null ? delegator : resolved;
    }

    /**
     * Writes a not-ready verdict to the log at most once per {@link #WARNING_INTERVAL_NANOS}.
     *
     * @param reason why this instance is not ready
     */
    private static void reportNotReady(String reason) {
        long now = System.nanoTime();
        long last = LAST_WARNING_AT.get();
        if (now - last >= WARNING_INTERVAL_NANOS && LAST_WARNING_AT.compareAndSet(last, now)) {
            Debug.logWarning(EVENT_NOT_READY + ": " + reason, MODULE);
        }
    }

    /**
     * Invalidates the session created for this request, and suppresses its cookie.
     *
     * <p>Only a session this request created is discarded, so a probe that arrives carrying somebody's
     * session cookie leaves that session alone: invalidating it would log its owner out. Done before
     * anything is written, because a committed response cannot have its headers changed.
     *
     * @param request the probe request
     * @param response the response the session cookie would be written on
     */
    private static void discardAnySessionMintedForThisProbe(HttpServletRequest request,
            HttpServletResponse response) {
        try {
            HttpSession session = request.getSession(false);
            if (session == null || !session.isNew()) {
                return;
            }
            String cookieName = sessionCookieName(request);
            session.invalidate();
            suppressSessionCookie(response, cookieName);
        } catch (RuntimeException unavailable) {
            // Absorbed for the reason every other dependency failure here is absorbed: a container that
            // will not answer a question about its own session must not turn a probe into a 500 with an
            // HTML error page. The probe's verdict does not depend on this.
            Debug.logWarning("A health probe could not discard the session created for it: "
                    + unavailable.getClass().getName(), MODULE);
        }
    }

    /**
     * Returns the name the container uses for its session cookie.
     *
     * <p>Read from the container rather than assumed to be {@code JSESSIONID}, because a deployment may
     * rename it in {@code web.xml} and a hard-coded name would then suppress nothing.
     *
     * @param request the probe request
     * @return the configured cookie name, or the specification's default
     */
    private static String sessionCookieName(HttpServletRequest request) {
        try {
            SessionCookieConfig config = request.getServletContext().getSessionCookieConfig();
            String configured = config == null ? null : config.getName();
            return configured == null || configured.isBlank() ? DEFAULT_SESSION_COOKIE_NAME : configured;
        } catch (RuntimeException unavailable) {
            return DEFAULT_SESSION_COOKIE_NAME;
        }
    }

    /**
     * Re-emits the response's {@code Set-Cookie} headers without the session cookie.
     *
     * <p>{@code setHeader} replaces every existing value of a header name and {@code addHeader} appends,
     * so writing the first kept value with the former and the rest with the latter leaves exactly the
     * kept values. When the session cookie is the only one there is nothing to keep, and the header is
     * replaced with a directive that expires that same cookie: the Servlet API has no
     * {@code removeHeader}, and a cookie left in place would be stored by the client, which is the
     * outcome being prevented.
     *
     * @param response the response to rewrite
     * @param cookieName the session cookie's name
     */
    private static void suppressSessionCookie(HttpServletResponse response, String cookieName) {
        List<String> kept = new ArrayList<>();
        boolean found = false;
        for (String cookie : response.getHeaders(SET_COOKIE_HEADER)) {
            if (cookie != null && cookie.regionMatches(true, 0, cookieName + "=", 0, cookieName.length() + 1)) {
                found = true;
            } else {
                kept.add(cookie);
            }
        }
        if (!found) {
            return;
        }
        if (kept.isEmpty()) {
            response.setHeader(SET_COOKIE_HEADER, cookieName + "=; Max-Age=0; Path=/; HttpOnly");
            return;
        }
        response.setHeader(SET_COOKIE_HEADER, kept.get(0));
        for (int index = 1; index < kept.size(); index++) {
            response.addHeader(SET_COOKIE_HEADER, kept.get(index));
        }
    }

    /**
     * Writes the status and the document, with caching and sniffing suppressed.
     *
     * <p>The body is written for HEAD as well as for GET. The container discards it for HEAD, which is
     * what keeps the two verdicts identical without a second code path.
     *
     * @param response the response to write
     * @param status the status code
     * @param body the JSON document
     * @throws IOException if the response cannot be written
     */
    private static void writeResponse(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType(CONTENT_TYPE_JSON);
        response.setCharacterEncoding(CHARACTER_ENCODING);
        response.setHeader(CACHE_CONTROL_HEADER, CACHE_CONTROL_VALUE);
        response.setHeader(CONTENT_TYPE_OPTIONS_HEADER, CONTENT_TYPE_OPTIONS_VALUE);
        byte[] document = body.getBytes(StandardCharsets.UTF_8);
        response.setContentLength(document.length);
        response.getOutputStream().write(document);
    }

    /**
     * A readiness verdict and when it was established.
     *
     * @param ready whether this instance could serve traffic
     * @param atNanos the {@link System#nanoTime()} reading it was established at
     */
    private record Verdict(boolean ready, long atNanos) {
    }
}
