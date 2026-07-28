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
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.webapp.WebAppUtil;

/**
 * HealthCheckServlet.java - Liveness and readiness probes for load-balancer target-group checks.
 *
 * <p>Two machine-readable paths are served, mapped by a webapp deployment descriptor as
 * {@code /health/live} and {@code /health/ready}. Registered in the webtools webapp they are
 * reachable as {@code /webtools/health/live} and {@code /webtools/health/ready}.
 *
 * <p>Status-code contract:
 *
 * <ul>
 * <li>{@code /health/live} (liveness) - always {@code 200 OK} once the servlet container is up.
 *     No database access, no delegator lookup and no session access are performed, so it still
 *     answers while the datasource is unavailable. A load balancer uses it only to decide whether an
 *     instance has to be restarted or replaced.</li>
 * <li>{@code /health/ready} (readiness) - {@code 200 OK} when the {@code SequenceValueItem} entity
 *     can be counted and the count is non-zero, otherwise {@code 503 SERVICE_UNAVAILABLE}: no
 *     delegator is available, the count throws, or the count comes back zero. A load
 *     balancer uses it to decide whether to route traffic to an instance. The count mirrors the
 *     {@code ping} service of {@code org.apache.ofbiz.common.CommonServices}, which treats both a
 *     failed count and a zero count as a datasource failure, so a reachable but uninitialised schema
 *     reads as "not ready" and is reported in the log. At most two readiness probes query the
 *     datasource at a time; a probe beyond that bound is shed with the same {@code 503} instead of
 *     being parked on an exhausted connection pool.</li>
 * <li>Any other path - {@code 404 NOT_FOUND}. A mis-configured probe has to fail visibly instead of
 *     reporting false health.</li>
 * <li>Any method other than {@code GET} or {@code HEAD} - {@code 405 METHOD_NOT_ALLOWED} with an
 *     {@code Allow} header.</li>
 * <li>Any request carrying an entity body - {@code 400 BAD_REQUEST}, decided from the headers
 *     alone so that not one byte of the body is ever read.</li>
 * </ul>
 *
 * <p>Every readiness dependency failure - a delegator lookup that throws, a null delegator, a
 * {@code GenericEntityException} from the count, or an unchecked failure such as an exhausted
 * connection pool - is converted inside a single protected block into the same fixed {@code 503}
 * document. Nothing propagates out of the probe, so the container can never render an error page or
 * an exception report on these unauthenticated paths.
 *
 * <p>Each response body is a small fixed JSON document ({@code application/json}, UTF-8) that never
 * carries diagnostic detail; stack traces, SQL, connection strings and credentials go to the OFBiz
 * log only. Responses are marked non-cacheable so that no intermediary can serve a stale verdict.
 *
 * <h2>Why this class is both a servlet and a filter</h2>
 *
 * <p>The endpoints are unauthenticated because a load-balancer target group polls them
 * continuously, which makes them the most exposed surface of the whole webapp. They therefore must
 * not be reachable through the ordinary webapp filter chain: {@code ControlFilter} and
 * {@code ContextFilter} both call {@code getSession()} unconditionally, so every single probe would
 * mint an {@code HttpSession} and a {@code JSESSIONID} that a load balancer never returns, and
 * {@code ContextFilter} additionally hands the request to
 * {@code WebAppUtil.setAttributesFromRequestBody}, which materialises an
 * {@code application/json} body of any size into a String and then a Map before any servlet method
 * is dispatched. The pre-existing {@code /ping.txt} entry in that chain's {@code allowedPaths} shows
 * what allow-listing alone would buy: passage without a login, but still with the session and the
 * body handling that happen before the list is consulted.
 *
 * <p>Implementing {@link Filter} alongside {@link HttpServlet} lets the same class be mapped as the
 * <em>first</em> filter in the descriptor on exactly the two probe paths. It answers the probe and
 * does not call {@link FilterChain#doFilter}, so the rest of the chain - and with it the session
 * creation and the body parser - is never entered. A request whose path is not exactly one of the
 * two probe paths is passed straight through untouched, so the class is safe even if it is ever
 * mapped more widely than it is here.
 *
 * <p>Consequently the endpoints really are session-free and body-free: no login, no permission
 * check, no session access, no service-engine invocation and no read of the request body take
 * place. The class is strictly read-only with respect to application state, and every field is a
 * private constant or a thread-safe counter, so it is safe to serve concurrently.
 *
 * <p>The class is inert until a webapp deployment descriptor maps it. The {@code webapp} component
 * declares no webapp of its own, so simply adding this class changes no existing behaviour.
 */
@SuppressWarnings("serial")
public class HealthCheckServlet extends HttpServlet implements Filter {

    private static final String MODULE = HealthCheckServlet.class.getName();

    // The two probe paths, matched EXACTLY. An exact comparison rather than a prefix or a suffix
    // test is what confines the anonymous surface to these two resources: no /health prefix is
    // reserved, so every other spelling - /healthz, /health, /health/live/, /health/liveness - falls
    // through to the ordinary authenticated chain instead of being answered here.
    private static final String PROBE_LIVE = "/health/live";
    private static final String PROBE_READY = "/health/ready";

    // The only methods a target-group probe needs. Everything else, TRACE and OPTIONS included, is
    // refused with 405 rather than reaching HttpServlet's defaults, which would either echo request
    // headers back (doTrace) or answer through sendError and its error-page machinery. TRACE is
    // additionally refused one layer earlier by the connector, whose allowTrace defaults to false, so
    // in a default deployment it never arrives here; the check below is kept as defence in depth for
    // the case where a connector is configured to pass it through.
    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "HEAD");
    private static final String ALLOW_HEADER = "Allow";
    private static final String ALLOW_VALUE = "GET, HEAD";

    // Headers that announce an entity body. Both are inspected instead of the body itself, so a
    // request that carries one is rejected without a single byte being read: the container then
    // discards up to its own maxSwallowSize and closes the connection.
    private static final String TRANSFER_ENCODING_HEADER = "Transfer-Encoding";
    private static final String CHUNKED_ENCODING = "chunked";

    // Framework-tier entity of the default "org.apache.ofbiz" group. It is present in every
    // deployment regardless of which application components are loaded, which is why the readiness
    // probe counts it rather than any application entity.
    private static final String READINESS_ENTITY = "SequenceValueItem";

    // Fixed response bodies. Hand-built literals only: no JSON library is pulled in, and no
    // internal detail can ever leak into a body that has no variable part. Every rejection - an
    // unknown path, a refused method, a refused body - shares BODY_UNKNOWN, because a probe client
    // acts on the status code and telling an anonymous caller which rule it broke serves no purpose.
    private static final String BODY_LIVE_UP = "{\"status\":\"UP\"}";
    private static final String BODY_UNKNOWN = "{\"status\":\"DOWN\"}";
    private static final String BODY_READY_UP = "{\"status\":\"UP\",\"database\":\"UP\"}";
    private static final String BODY_READY_DOWN = "{\"status\":\"DOWN\",\"database\":\"DOWN\"}";

    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String CHARACTER_ENCODING = "UTF-8";
    private static final String CACHE_CONTROL_HEADER = "Cache-Control";
    private static final String CACHE_CONTROL_VALUE = "no-cache, no-store, must-revalidate";

    // Safe response headers. Answering before the chain means a probe response never reaches
    // RequestHandler, which is where OFBiz normally applies
    // UtilHttp.setResponseBrowserDefaultSecurityHeaders. That helper is deliberately not reused
    // here: it resolves Content-Security-Policy and Strict-Transport-Security through
    // EntityUtilProperties, so it would pull a delegator lookup and a property cache read into every
    // probe - and would attempt them while an unreachable datasource is the very condition being
    // reported. The headers that actually protect a fixed JSON document of a few dozen bytes are set
    // directly from constants instead, with no allocation and no engine involvement. The values
    // match the framework defaults in UtilHttp so a probe response is consistent with the rest of
    // the application. X-XSS-Protection is omitted on purpose because it is deprecated and ignored
    // by current browsers, and Strict-Transport-Security is omitted because a probe is polled over
    // plain HTTP inside the load balancer's own network where the header carries no meaning, while
    // browser traffic still receives it from the ordinary chain.
    private static final String CONTENT_TYPE_OPTIONS_HEADER = "X-Content-Type-Options";
    private static final String CONTENT_TYPE_OPTIONS_VALUE = "nosniff";
    private static final String FRAME_OPTIONS_HEADER = "X-Frame-Options";
    private static final String FRAME_OPTIONS_VALUE = "sameorigin";
    private static final String REFERRER_POLICY_HEADER = "Referrer-Policy";
    private static final String REFERRER_POLICY_VALUE = "no-referrer-when-downgrade";

    // Stable event code for an unavailable datasource. A log consumer keys its alert off this token
    // rather than off wording or off an exception message, so the line is safe to rely on and
    // carries no internal detail of its own.
    private static final String EVENT_READINESS_UNAVAILABLE = "HEALTH-READINESS-DATASOURCE-UNAVAILABLE";

    // Rate limit for that event. An outage makes every probe of every load-balancer target fail at
    // the polling interval, so an unthrottled line per failure turns the readiness endpoint into a
    // log amplifier exactly when the log matters most. One line per minute per JVM is emitted and
    // the suppressed occurrences are counted into the next one, so nothing is silently lost.
    //
    // The interval is shared by both readiness event codes, but each code owns its own window and
    // its own suppressed count. That separation is the point of having two codes: a shed probe must
    // not consume the window an unavailable datasource needs - the two coincide precisely, since an
    // exhausted pool is what makes probes slow enough to overlap - and neither code may report the
    // other's occurrences as its own.
    private static final long READINESS_LOG_INTERVAL_MILLIS = 60000L;
    private static final AtomicLong READINESS_LOG_LAST_AT = new AtomicLong(0L);
    private static final AtomicLong READINESS_LOG_SUPPRESSED = new AtomicLong(0L);

    // Stable event code for a readiness probe that was shed rather than served. Distinguishing it
    // from an unavailable datasource matters to an operator: the datasource may be perfectly healthy
    // and simply busier than the bound below allows.
    private static final String EVENT_READINESS_SHED = "HEALTH-READINESS-PROBE-SHED";

    // That code's own rate-limit window and suppressed count - see READINESS_LOG_INTERVAL_MILLIS.
    private static final AtomicLong READINESS_SHED_LOG_LAST_AT = new AtomicLong(0L);
    private static final AtomicLong READINESS_SHED_LOG_SUPPRESSED = new AtomicLong(0L);

    // Upper bound on readiness probes that may query the datasource at the same time, and the count
    // of those currently in flight.
    //
    // A readiness query is not guaranteed to be quick. It borrows a pooled connection, and when the
    // pool is exhausted DBCP blocks the caller for up to the datasource's pool-sleeptime - which the
    // committed datasource definitions set to 300000 milliseconds. Readiness is polled continuously
    // by every load-balancer target, and probes arrive whether or not the previous one answered, so
    // without a bound an exhausted pool parks one container thread per probe for five minutes each
    // and the instance runs out of request threads for real traffic - having been asked nothing more
    // than "are you ready". The bound is deliberately small: two concurrent probes are enough to
    // answer a load balancer and a human at the same time, and everything beyond that is shed
    // immediately with the same 503 the datasource failure itself would produce, which is the answer
    // a load balancer acts on anyway.
    //
    // The bound is on CONCURRENCY rather than on the duration of a single probe. Interrupting a
    // blocked borrow, or moving the query onto a worker thread, would need per-JVM executor state in
    // this class; the class is deliberately stateless - it is instantiated twice by the container,
    // once as a servlet and once as a filter - so the shedding counter is the whole mechanism.
    private static final long MAX_CONCURRENT_READINESS_PROBES = 2L;
    private static final AtomicLong READINESS_PROBES_IN_FLIGHT = new AtomicLong(0L);

    // Sanitising of the diagnostic that is emitted only under verbose diagnostics. Control
    // characters are folded away so a crafted message cannot forge log lines, anything that looks
    // like a JDBC URL or a credential assignment is redacted, and the result is truncated so a
    // pathologically long driver message cannot fill the log either.
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cntrl}]+");
    // The lookbehind rejects a letter but not an underscore, so api_key=... is redacted while an
    // innocent word ending in one of the keywords, such as monkey=..., is left alone.
    private static final Pattern SENSITIVE_FRAGMENTS = Pattern.compile(
            "(?i)jdbc:[^\\s\"']*|(?<![A-Za-z])(?:password|passwd|pwd|secret|token|credential|key)\\s*[=:]\\s*[^\\s,;\"']*");
    private static final String REDACTED = "***";
    private static final int MAX_DIAGNOSTIC_LENGTH = 200;
    private static final String NO_DIAGNOSTIC = "none";

    /*
     * Servlet entry point for every HTTP method.
     *
     * service() is overridden in place of doGet/doHead so that this class, and not HttpServlet,
     * decides what happens to every other method. HttpServlet's defaults are unusable here: doTrace
     * echoes the received request headers back to an anonymous caller, and doPost, doPut and the
     * rest answer through sendError, which hands the response to the container error-page machinery
     * and would replace the compact JSON document with an HTML page.
     */
    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleProbe(request, response, resolveProbePath(request));
    }

    /*
     * Filter entry point - see the class comment for why this class is mapped as a filter as well.
     *
     * The chain is terminated for a probe path and left completely untouched for anything else. That
     * asymmetry is deliberate and fail-closed in both directions: a probe never reaches the filters
     * that would create a session and parse its body, while a request this filter was not meant to
     * see is handed on unchanged rather than being answered from here or rejected outright. A
     * non-HTTP request cannot be a probe, so it is passed on as well instead of being cast.
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String path = resolveProbePath(httpRequest);
        if (!PROBE_LIVE.equals(path) && !PROBE_READY.equals(path)) {
            chain.doFilter(request, response);
            return;
        }
        handleProbe(httpRequest, (HttpServletResponse) response, path);
    }

    /*
     * Answers one probe request. Shared by the servlet and the filter entry points so that both
     * enforce exactly the same contract, and reached with the path already resolved so the request
     * is interrogated once.
     *
     * It is static because it needs nothing from either instance, and neither branch touches the
     * session; liveness in addition resolves no delegator and issues no query, so it stays
     * answerable while the datasource is unavailable.
     *
     * The two guards run before the routing because they are what keeps this anonymous surface
     * cheap. Neither of them reads the request body: the method is a header, and so are
     * Content-Length and Transfer-Encoding, so an oversized or chunked body is refused for the cost
     * of a header lookup and is then discarded by the container rather than by this JVM's heap.
     */
    private static void handleProbe(HttpServletRequest request, HttpServletResponse response, String path) throws IOException {
        if (!ALLOWED_METHODS.contains(request.getMethod())) {
            // 405 has to carry Allow per the HTTP specification, and it also tells a mis-configured
            // target group what to switch to.
            response.setHeader(ALLOW_HEADER, ALLOW_VALUE);
            writeResponse(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, BODY_UNKNOWN);
            return;
        }
        if (carriesEntityBody(request)) {
            writeResponse(response, HttpServletResponse.SC_BAD_REQUEST, BODY_UNKNOWN);
            return;
        }
        if (PROBE_LIVE.equals(path)) {
            // Liveness: reaching this line is the whole assertion - no I/O beyond the response.
            writeResponse(response, HttpServletResponse.SC_OK, BODY_LIVE_UP);
        } else if (PROBE_READY.equals(path)) {
            if (isDatabaseReachable(request)) {
                writeResponse(response, HttpServletResponse.SC_OK, BODY_READY_UP);
            } else {
                writeResponse(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, BODY_READY_DOWN);
            }
        } else {
            // An unmapped path fails visibly instead of reporting a false 200, which would let a
            // load balancer keep a broken instance in service.
            writeResponse(response, HttpServletResponse.SC_NOT_FOUND, BODY_UNKNOWN);
        }
    }

    /*
     * Reports whether the request announces an entity body, from the headers only.
     *
     * A declared length settles it. A chunked transfer coding declares a body of unknown length,
     * which is precisely the shape that makes an unbounded read dangerous, so it is refused too. A
     * request with neither header has no body a servlet container will deliver.
     */
    private static boolean carriesEntityBody(HttpServletRequest request) {
        if (request.getContentLengthLong() > 0L) {
            return true;
        }
        String transferEncoding = request.getHeader(TRANSFER_ENCODING_HEADER);
        return transferEncoding != null && transferEncoding.toLowerCase(Locale.ROOT).contains(CHUNKED_ENCODING);
    }

    /*
     * Resolves the requested path within the webapp as servletPath followed by pathInfo.
     *
     * An exact url-pattern such as /health/live reports the whole mapped path through
     * getServletPath() and leaves getPathInfo() null, whereas a prefix pattern such as /health/*
     * splits the same path across the two. Concatenating them therefore yields the same path under
     * either mapping style, without this class having to know which one the deployment descriptor
     * uses, and it yields it independently of the context path the webapp is mounted at. Both
     * accessors are null-tolerant here, so a container that reports neither simply yields an empty
     * path, which is treated as an unknown path.
     *
     * The container has already normalised and decoded the URI by the time a filter or a servlet
     * sees it, and it has stripped any path parameters, so no ../ traversal, %2e escape or
     * ;jsessionid suffix can reach the exact comparison in handleProbe.
     */
    private static String resolveProbePath(HttpServletRequest request) {
        StringBuilder path = new StringBuilder();
        String servletPath = request.getServletPath();
        if (servletPath != null) {
            path.append(servletPath);
        }
        String pathInfo = request.getPathInfo();
        if (pathInfo != null) {
            path.append(pathInfo);
        }
        return path.toString();
    }

    /*
     * Answers whether this instance can reach its database.
     *
     * The query is the one the "ping" service uses (CommonServices.ping in framework/common, lines
     * 479-501): count SequenceValueItem, a framework-tier entity of the default org.apache.ofbiz
     * group that exists in every deployment. The query is reused rather than the service itself, so
     * no dispatcher, service engine or localisation is dragged into what has to stay a cheap probe.
     *
     * BOTH of ping's failure rules are adopted, not only its query, so that the two implementations
     * agree on what a healthy datasource is: a count that throws is ping's
     * CommonPingDatasourceCannotConnect case, and a count that returns zero is its
     * CommonPingDatasourceInvalidCount case (line 495, "if (count != 0L)"). Each maps to "not ready"
     * here. A completed count proves the delegator, the datasource, the connection pool, the
     * SequenceValueItem table and the whole query path are usable, but a count of zero additionally
     * says the schema holds no sequence rows at all - it has not been through the gated schema-init
     * execution and its data load - so the instance cannot serve requests yet. Reporting 200 in that
     * state would attach an instance backed by an unpopulated schema to the load-balancer target
     * group and turn a deployment-ordering mistake into user-visible failures instead of a visibly
     * unhealthy target that never receives traffic. SequenceValueItem is written by the sequencer, so
     * any data load populates it: embedded H2 holds rows after "gradlew loadAll", and a managed
     * database does after the one-shot schema-init execution and its data load.
     * Readiness is therefore false in exactly three cases: no delegator can be obtained, the query
     * throws - which is what a genuinely unreachable or unmigrated database does - or the count is
     * zero.
     *
     * The probe is strictly read-only: no DDL, no writes, no cache mutation and no explicit
     * transaction management, which is what allows a serving instance to run without DDL
     * privileges. The delegator is resolved per request rather than in init(), so a datasource that
     * only becomes reachable later flips readiness to 200 - and one that later fails flips it to
     * 503 - with no restart.
     *
     * The method is FAIL-CLOSED: every step, the servlet-context access and the delegator lookup
     * included, runs inside the try below. WebAppUtil.getDelegator loads and initialises the
     * delegator, so it can itself throw unchecked - a broken datasource definition, an absent JDBC
     * driver, a pool that cannot be created. Resolving it outside the block would let such a failure
     * escape to the container, which would answer 500 with an HTML error page instead of the 503 JSON
     * document this contract promises, and Tomcat's default ErrorReportValve can render an exception
     * report on what is deliberately an unauthenticated path. Inside the block every failure mode
     * collapses into the same fixed 503 body and the detail goes to the log only, so a load balancer
     * sees a clean "not ready" rather than an unexpected status.
     *
     * The context is taken from the request rather than from getServletContext(). That is the same
     * object for a request dispatched into this webapp, and it is the only one available when this
     * class runs as a filter, where no ServletConfig exists. Reading it from the request therefore
     * keeps one implementation for both entry points and leaves this class entirely free of
     * per-instance state.
     */
    private static boolean isDatabaseReachable(HttpServletRequest request) {
        if (READINESS_PROBES_IN_FLIGHT.incrementAndGet() > MAX_CONCURRENT_READINESS_PROBES) {
            // Shed before the datasource is touched, so a probe can never wait on a pooled
            // connection that the bound already says is not available to it.
            READINESS_PROBES_IN_FLIGHT.decrementAndGet();
            logReadinessShed();
            return false;
        }
        try {
            Delegator delegator = WebAppUtil.getDelegator(request.getServletContext());
            if (delegator == null) {
                // WebAppUtil.getDelegator only logs and returns null when the delegator factory fails,
                // so a null result has to be handled here rather than assumed away.
                logReadinessUnavailable("no delegator is available for this webapp");
                return false;
            }
            // The count itself decides the verdict, exactly as CommonServices.ping decides it.
            long rows = EntityQuery.use(delegator).from(READINESS_ENTITY).queryCount();
            if (rows == 0L) {
                // Reachable but empty. The ping service reports exactly this as an invalid count, and
                // an instance whose schema has not been initialised or seeded must not be handed
                // traffic - so it is reported rather than silently answered as "not ready". It goes
                // through the same rate limit as every other unavailability, because a target group
                // polls this endpoint every few seconds and the condition persists until the schema
                // is initialised.
                logReadinessUnavailable(READINESS_ENTITY + " is readable but holds no rows, so the schema"
                        + " has not been initialised or the seed data has not been loaded");
                return false;
            }
            if (Debug.verboseOn()) {
                // A successful probe is otherwise completely silent, so this is the only record that
                // the count really executed and of what it returned.
                Debug.logVerbose("Readiness probe: " + READINESS_ENTITY + " counted " + rows + " row(s)", MODULE);
            }
            return true;
        } catch (GenericEntityException e) {
            logReadinessUnavailable(e.getMessage());
            return false;
        } catch (RuntimeException e) {
            // An unchecked failure - an exhausted connection pool, a delegator that cannot be built,
            // a missing entity definition, or a servlet context that is not available yet - degrades
            // to 503 instead of escaping and letting the container render an error page. The
            // exception class is reported alongside the message because unchecked exceptions
            // frequently carry a null message; neither of them ever reaches the response body.
            logReadinessUnavailable(e.getClass().getName() + ": " + e.getMessage());
            return false;
        } finally {
            READINESS_PROBES_IN_FLIGHT.decrementAndGet();
        }
    }

    /*
     * Reports a shed readiness probe, through the same rate limit as an unavailable datasource.
     *
     * Shedding happens exactly when probes are arriving faster than the datasource answers them, so
     * an unthrottled line here would amplify the log for the same reason a failure line would. The
     * number of probes that were in flight is deliberately not reported at normal level: the line
     * carries the stable code and the count of occurrences suppressed since the previous one, with
     * the rest left to the verbose diagnostic.
     */
    private static void logReadinessShed() {
        logRateLimitedWarning(EVENT_READINESS_SHED, READINESS_SHED_LOG_LAST_AT, READINESS_SHED_LOG_SUPPRESSED);
        if (Debug.verboseOn()) {
            Debug.logVerbose(EVENT_READINESS_SHED + " detail: more than " + MAX_CONCURRENT_READINESS_PROBES
                    + " readiness probes were already querying the datasource", MODULE);
        }
    }

    /*
     * Reports an unavailable datasource to the log.
     *
     * The endpoint is anonymous and polled continuously, which shapes everything about this method.
     * The line that is always emitted carries the stable EVENT_READINESS_UNAVAILABLE code and
     * nothing else: an entity-engine or JDBC failure message routinely names the connection URI, the
     * datasource, the pooled driver and the failing SQL, and none of that may be handed to an
     * unauthenticated caller's ability to trigger log writes. It is emitted at warning level rather
     * than as an error with a stack trace, because a readiness dip during start-up or a rolling
     * deployment is expected and must not read as a fault.
     *
     * That line is rate limited to one per minute per JVM, with the suppressed occurrences counted
     * into the next one so an outage is still quantified rather than silently dropped. The
     * compare-and-set makes the rate limit correct when several probe threads fail at once: exactly
     * one of them wins the window and the losers are counted.
     *
     * The detail itself is available to an operator who has switched verbose diagnostics on, and
     * even then it is sanitised first - see sanitizeDiagnostic.
     */
    private static void logReadinessUnavailable(String diagnostic) {
        logRateLimitedWarning(EVENT_READINESS_UNAVAILABLE, READINESS_LOG_LAST_AT, READINESS_LOG_SUPPRESSED);
        if (Debug.verboseOn()) {
            Debug.logVerbose(EVENT_READINESS_UNAVAILABLE + " detail: " + sanitizeDiagnostic(diagnostic), MODULE);
        }
    }

    /*
     * Emits one warning for the given event code per READINESS_LOG_INTERVAL_MILLIS, and appends the
     * number of occurrences suppressed since the previous line so an outage - or a saturated probe
     * path - is quantified rather than silently dropped.
     *
     * The window and the counter are passed in rather than read from a single pair of fields, so
     * every event code is rate limited independently of the others: one code can neither silence
     * another nor claim its suppressed occurrences. The compare-and-set makes this correct when
     * several probe threads reach the same code at once - exactly one wins the window and the losers
     * are counted - and the count is drained by the winner, so no occurrence is counted twice.
     */
    private static void logRateLimitedWarning(String eventCode, AtomicLong window, AtomicLong suppressed) {
        long now = System.currentTimeMillis();
        long previous = window.get();
        if (now - previous >= READINESS_LOG_INTERVAL_MILLIS && window.compareAndSet(previous, now)) {
            long missed = suppressed.getAndSet(0L);
            Debug.logWarning(missed == 0L ? eventCode
                    : eventCode + " (" + missed + " further occurrences suppressed)", MODULE);
        } else {
            suppressed.incrementAndGet();
        }
    }

    /*
     * Makes an internal diagnostic safe to write to the log.
     *
     * Three things are done, in order. Control characters are folded into a single space, so a
     * message that contains a newline cannot forge an additional log record. Anything shaped like a
     * JDBC URL or a credential assignment is redacted, which is what keeps a connection string and
     * an embedded password out of the log even when the driver puts them in its message. The result
     * is truncated, so a pathologically long message cannot be used to inflate the log either.
     */
    private static String sanitizeDiagnostic(String diagnostic) {
        if (diagnostic == null || diagnostic.isEmpty()) {
            return NO_DIAGNOSTIC;
        }
        String flattened = CONTROL_CHARACTERS.matcher(diagnostic).replaceAll(" ");
        String redacted = SENSITIVE_FRAGMENTS.matcher(flattened).replaceAll(REDACTED);
        return redacted.length() <= MAX_DIAGNOSTIC_LENGTH
                ? redacted
                : redacted.substring(0, MAX_DIAGNOSTIC_LENGTH) + "...";
    }

    /*
     * Writes the probe verdict.
     *
     * setStatus is used deliberately in place of sendError: sendError hands the response to the
     * container's error-page machinery, which would replace this compact JSON document with an HTML
     * page and would also be intercepted by any error-page element added to a deployment descriptor
     * later. The character encoding and content type are set before the writer is obtained, as the
     * servlet contract requires.
     *
     * Every response - a verdict as much as a rejection - carries the same safe headers, so a
     * refused method or a refused body is protected exactly like a healthy verdict.
     */
    private static void writeResponse(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType(CONTENT_TYPE_JSON);
        response.setCharacterEncoding(CHARACTER_ENCODING);
        response.setHeader(CACHE_CONTROL_HEADER, CACHE_CONTROL_VALUE);
        response.setHeader(CONTENT_TYPE_OPTIONS_HEADER, CONTENT_TYPE_OPTIONS_VALUE);
        response.setHeader(FRAME_OPTIONS_HEADER, FRAME_OPTIONS_VALUE);
        response.setHeader(REFERRER_POLICY_HEADER, REFERRER_POLICY_VALUE);
        response.getWriter().print(body);
    }
}
