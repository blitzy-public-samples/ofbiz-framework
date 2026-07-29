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

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
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
 * <li>{@code /health/live} - always {@code 200 OK} once the servlet container is up. No delegator
 *     lookup, no database access and no session access, so it still answers while the datasource is
 *     unavailable. A load balancer uses it to decide whether an instance has to be replaced.</li>
 * <li>{@code /health/ready} - {@code 200 OK} when the {@code SequenceValueItem} count completes and
 *     is non-zero, otherwise {@code 503 SERVICE_UNAVAILABLE}: no delegator is available, the count
 *     fails, or the count comes back zero. The count and both of its failure rules mirror the
 *     {@code ping} service of {@code org.apache.ofbiz.common.CommonServices}, which treats a failed
 *     count and a zero count alike as a datasource failure. A load balancer uses it to decide
 *     whether to route traffic to an instance. At most two readiness probes reach the datasource at
 *     a time; a probe beyond that bound is shed with the same {@code 503} instead of being parked on
 *     an exhausted connection pool.</li>
 * <li>Any other path - {@code 404 NOT_FOUND}, so a mis-configured probe fails visibly instead of
 *     reporting false health. Under the two exact url-patterns this class is mapped with, the
 *     container answers an unknown path before the request reaches here; the branch applies when it
 *     is mapped with a prefix pattern such as {@code /health/*}.</li>
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
 * an exception report on these unauthenticated paths. Each response body is a small fixed JSON
 * document ({@code application/json}, UTF-8) with no variable part, marked non-cacheable so that no
 * intermediary can serve a stale verdict.
 *
 * <p>Because the paths are anonymous and polled continuously, the probe writes no internal detail
 * anywhere: no throwable, no stack, no SQL, no connection string and no row count reach the log.
 * What is logged is a stable event code and the number of occurrences suppressed since the previous
 * line, at most one line per code per minute.
 *
 * <h2>Why this class is both a servlet and a filter</h2>
 *
 * <p>{@code ControlFilter} and {@code ContextFilter} both call {@code getSession()}
 * unconditionally, so a probe routed through the ordinary chain would mint an {@code HttpSession}
 * and a {@code JSESSIONID} that a load balancer never returns, and {@code ContextFilter}
 * additionally hands the request to {@code WebAppUtil.setAttributesFromRequestBody}, which
 * materialises a JSON body of any size into a String and then a Map before any servlet method is
 * dispatched. Allow-listing alone - what the pre-existing {@code /ping.txt} entry in that chain's
 * {@code allowedPaths} buys - would grant passage without a login but keep both, because they happen
 * before the list is consulted.
 *
 * <p>Implementing {@link Filter} alongside {@link HttpServlet} therefore lets the same class be
 * mapped as the <em>first</em> filter on exactly the two probe paths: it answers the probe without
 * calling {@link FilterChain#doFilter}, so neither the session creation nor the body parser is ever
 * entered, and a path that is not exactly one of the two is passed straight through untouched. No
 * login, permission check, session access, service-engine invocation or read of the request body
 * takes place. Every field is a private constant or a thread-safe counter, so the two instances the
 * container creates - one servlet, one filter - are safe to serve concurrently.
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

    // Methods this class answers. HEAD takes the GET path and produces the same status, headers and
    // document; the container installs a void output filter for HEAD, so the body is discarded on the
    // way out rather than being suppressed here. Everything else, TRACE and OPTIONS included, is
    // refused with 405 rather than reaching HttpServlet's defaults, which would either echo request
    // headers back (doTrace) or answer through sendError and its error-page machinery. TRACE is also
    // refused a layer earlier by the connector, whose allowTrace defaults to false, so the check
    // below is defence in depth for a connector configured to pass it through.
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

    // ServletContext attribute the delegator is published under. ContextFilter.init() populates it
    // when the webapp is deployed and WebAppUtil.getDelegator both reads and refreshes it, so
    // reading it first lets a probe observe an already-built delegator instead of asking for one.
    private static final String DELEGATOR_ATTRIBUTE = "delegator";

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
    // RequestHandler, where OFBiz normally applies UtilHttp.setResponseBrowserDefaultSecurityHeaders.
    // That helper is deliberately not reused: it resolves Content-Security-Policy and
    // Strict-Transport-Security through EntityUtilProperties, so it would pull a delegator lookup and
    // a property cache read into every probe - and would attempt them while an unreachable datasource
    // is the very condition being reported. The values below are set from constants instead and match
    // the framework defaults in UtilHttp, so a probe response stays consistent with the rest of the
    // application. X-XSS-Protection is omitted because it is deprecated and ignored by current
    // browsers; Strict-Transport-Security is omitted because a probe is polled over plain HTTP inside
    // the load balancer's own network, while browser traffic still receives it from the ordinary
    // chain.
    private static final String CONTENT_TYPE_OPTIONS_HEADER = "X-Content-Type-Options";
    private static final String CONTENT_TYPE_OPTIONS_VALUE = "nosniff";
    private static final String FRAME_OPTIONS_HEADER = "X-Frame-Options";
    private static final String FRAME_OPTIONS_VALUE = "sameorigin";
    private static final String REFERRER_POLICY_HEADER = "Referrer-Policy";
    private static final String REFERRER_POLICY_VALUE = "no-referrer-when-downgrade";

    // Stable event codes. Each readiness verdict that is not 200 reports itself as one of these
    // tokens, so a log consumer keys its alert off the token rather than off wording or an exception
    // message. No qualifier is ever appended - not an SQL state, not an exception type, not a driver
    // message - and the only thing that joins a token on the line is the suppressed-occurrence count
    // from the rate limit below. The three causes are kept apart because they call for different
    // operator action - restore the datasource, complete the schema-init execution and its data load,
    // or raise capacity.
    private static final String EVENT_READINESS_UNAVAILABLE = "HEALTH-READINESS-DATASOURCE-UNAVAILABLE";
    private static final String EVENT_READINESS_SCHEMA_EMPTY = "HEALTH-READINESS-SCHEMA-EMPTY";
    private static final String EVENT_READINESS_SHED = "HEALTH-READINESS-PROBE-SHED";

    // Rate limit for those events. An outage makes every probe of every load-balancer target fail at
    // the polling interval, so an unthrottled line per failure turns the readiness endpoint into a
    // log amplifier exactly when the log matters most. One line per minute per JVM is emitted per
    // code and the suppressed occurrences are counted into the next one, so nothing is silently lost.
    //
    // The interval is shared, but each code owns its own window and its own suppressed count. That
    // separation is the point of having separate codes: a shed probe must not consume the window an
    // unavailable datasource needs - the two coincide precisely, since an exhausted pool is what
    // makes probes slow enough to overlap - and no code may report another's occurrences as its own.
    private static final long READINESS_LOG_INTERVAL_MILLIS = 60000L;
    private static final AtomicLong READINESS_LOG_LAST_AT = new AtomicLong(0L);
    private static final AtomicLong READINESS_LOG_SUPPRESSED = new AtomicLong(0L);
    private static final AtomicLong READINESS_EMPTY_LOG_LAST_AT = new AtomicLong(0L);
    private static final AtomicLong READINESS_EMPTY_LOG_SUPPRESSED = new AtomicLong(0L);
    private static final AtomicLong READINESS_SHED_LOG_LAST_AT = new AtomicLong(0L);
    private static final AtomicLong READINESS_SHED_LOG_SUPPRESSED = new AtomicLong(0L);

    // Window that throttles how often a probe may ask the delegator factory for a delegator, using
    // the same interval as the log above. It is claimed only when the ServletContext holds no
    // delegator yet, and it is reopened immediately by a lookup that succeeds, so it holds back
    // nothing but a failing lookup - see resolveDelegator.
    private static final AtomicLong DELEGATOR_LOOKUP_LAST_AT = new AtomicLong(0L);

    // Upper bound on readiness probes that may query the datasource at the same time, and the count
    // of those currently in flight.
    //
    // A readiness query is not guaranteed to be quick: it borrows a pooled connection, and when the
    // pool is exhausted DBCP blocks the caller for up to the datasource's pool-sleeptime, which the
    // committed datasource definitions set to 300000 milliseconds. Readiness is polled continuously
    // by every load-balancer target and probes arrive whether or not the previous one answered, so
    // without a bound an exhausted pool parks one container thread per probe for five minutes each
    // and the instance runs out of request threads for real traffic - having been asked nothing more
    // than "are you ready". Two concurrent probes are enough to answer a load balancer and a human at
    // the same time; everything beyond that is shed immediately with the same 503 the datasource
    // failure itself would produce, which is the answer a load balancer acts on anyway.
    //
    // The bound is on CONCURRENCY rather than on the duration of a single probe: interrupting a
    // blocked borrow, or moving the query onto a worker thread, would need per-JVM executor state in
    // a class that is deliberately stateless - the container instantiates it twice, once as a servlet
    // and once as a filter - so the shedding counter is the whole mechanism.
    private static final long MAX_CONCURRENT_READINESS_PROBES = 2L;
    private static final AtomicLong READINESS_PROBES_IN_FLIGHT = new AtomicLong(0L);

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
     * Exactly the two probe paths are answered here and the chain is not continued, which is what
     * keeps a probe away from the session creation and the body parsing further down it. Every other
     * request, including a non-HTTP one that cannot be a probe, is handed on unchanged rather than
     * answered or rejected from here.
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
     * Shared by the servlet and the filter entry points, so both enforce one contract from one
     * implementation; static because it needs nothing from either instance.
     *
     * The method and body guards run before the routing, and neither reads the request body: the
     * method is a header, and so are Content-Length and Transfer-Encoding, so an oversized or chunked
     * body is refused for the cost of a header lookup and is then discarded by the container rather
     * than by this JVM's heap. Neither branch touches the session, and liveness in addition resolves
     * no delegator and issues no query, so it stays answerable while the datasource is unavailable.
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
     * WHAT IS CHECKED. The check the "ping" service performs (CommonServices.ping in
     * framework/common, lines 479-501): count SequenceValueItem, a framework-tier entity of the
     * default org.apache.ofbiz group present in every deployment. The service itself is not invoked,
     * so no dispatcher, service engine or localisation is dragged into what has to stay a cheap probe.
     *
     * BOTH of ping's failure rules are adopted, not only its query, because the Agent Action Plan
     * specifies this endpoint as "mirroring the ping service's SequenceValueItem count check" (AAP
     * section 0.4.1): a count that fails is ping's CommonPingDatasourceCannotConnect case, and a
     * count that returns zero is its CommonPingDatasourceInvalidCount case - "if (count != 0L)" at
     * CommonServices.java line 495. Both mean "not ready" here. A non-zero count proves the delegator,
     * the datasource, the connection pool, the SequenceValueItem table and the whole query path
     * usable; a zero count says the schema holds no sequence rows at all, so the instance has not been
     * through the schema-init execution and its data load. Reporting 200 in that state would attach an
     * instance backed by an unpopulated schema to the load-balancer target group and turn a
     * deployment-ordering mistake into user-visible failures instead of a visibly unhealthy target
     * that never receives traffic. Any data load populates the entity, since the sequencer writes it.
     *
     * HOW IT IS ISSUED. The count goes through the entity helper that owns the entity's group rather
     * than through EntityQuery. Both end in the same GenericDAO.selectCountByCondition, so the same
     * SELECT COUNT runs against the same entity, but EntityQuery routes through
     * GenericDelegator.findCountByCondition, which logs the throwable together with its stack trace
     * before rethrowing, marks the transaction for rollback and runs three ECA phases. On a path an
     * anonymous caller polls every few seconds that logging is unbounded and happens below this
     * class, outside the rate limit here. GenericHelperDAO passes straight through to GenericDAO,
     * which logs at verbose level only and never logs a throwable, so a failing probe produces
     * exactly the one rate-limited event code this method emits. ModelReader.getModelEntity is used
     * in place of Delegator.getModelEntity for the same reason: the latter logs a throwable when the
     * model cannot be read, the former throws it.
     *
     * The probe is strictly read-only: no DDL, no writes, no cache mutation and no explicit
     * transaction management, which is what allows a serving instance to run without DDL
     * privileges. The delegator is resolved per request rather than in init(), so a datasource that
     * only becomes reachable later flips readiness to 200 - and one that later fails flips it to
     * 503 - with no restart.
     *
     * The method is FAIL-CLOSED: every step, the servlet-context access and the delegator lookup
     * included, runs inside the try below. Resolving the delegator outside the block would let a
     * broken datasource definition, an absent JDBC driver or a pool that cannot be created escape to
     * the container, which would answer 500 with an HTML error page instead of the 503 JSON document
     * this contract promises, and Tomcat's default ErrorReportValve can render an exception report on
     * what is deliberately an unauthenticated path.
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
            logRateLimitedWarning(EVENT_READINESS_SHED, READINESS_SHED_LOG_LAST_AT, READINESS_SHED_LOG_SUPPRESSED);
            return false;
        }
        try {
            Delegator delegator = resolveDelegator(request.getServletContext());
            if (delegator == null) {
                logRateLimitedWarning(EVENT_READINESS_UNAVAILABLE, READINESS_LOG_LAST_AT, READINESS_LOG_SUPPRESSED);
                return false;
            }
            // The count itself decides the verdict, exactly as CommonServices.ping decides it.
            long rows = delegator.getEntityHelper(READINESS_ENTITY).findCountByCondition(delegator,
                    delegator.getModelReader().getModelEntity(READINESS_ENTITY), null, null, null);
            if (rows == 0L) {
                // Reachable but empty: reported under its own code because the operator action it
                // calls for - complete the schema-init execution and its data load - differs from
                // the one an unreachable datasource calls for.
                logRateLimitedWarning(EVENT_READINESS_SCHEMA_EMPTY, READINESS_EMPTY_LOG_LAST_AT, READINESS_EMPTY_LOG_SUPPRESSED);
                return false;
            }
            return true;
        } catch (GenericEntityException | RuntimeException failure) {
            // Every remaining failure mode - an unreachable datasource, an exhausted connection pool,
            // a delegator that cannot be built, an absent entity definition, a servlet context that
            // is not available - collapses into the same verdict under the same code instead of
            // escaping and letting the container render an error page. The throwable is deliberately
            // neither logged nor inspected: an entity-engine or JDBC message routinely names the
            // connection URI, the datasource, the pooled driver and the failing SQL, and none of that
            // may be reachable through an unauthenticated caller's ability to trigger log writes.
            logRateLimitedWarning(EVENT_READINESS_UNAVAILABLE, READINESS_LOG_LAST_AT, READINESS_LOG_SUPPRESSED);
            return false;
        } finally {
            READINESS_PROBES_IN_FLIGHT.decrementAndGet();
        }
    }

    /*
     * Resolves the delegator for the probe, preferring the one the webapp has already published.
     *
     * ContextFilter.init() calls WebAppUtil.getDelegator when the webapp is deployed, and that method
     * publishes the result on the ServletContext, so in a running deployment the attribute is set
     * before the first probe arrives and reading it costs a map lookup with no logging and no
     * delegator construction. A probe is meant to observe readiness, not to create the machinery it
     * reports on.
     *
     * The lookup is still available as a fallback, so this class keeps working in a webapp that does
     * not declare ContextFilter, but it is throttled to once per rate-limit window. That is what
     * bounds the logging below this class: DelegatorFactory.getDelegator logs the throwable and its
     * stack when construction fails, and it caches the failed Future, so every later call re-throws
     * and re-logs - an anonymous poller would otherwise amplify one broken datasource definition into
     * an unbounded stream of stack traces. Nothing is lost by throttling, precisely because the
     * cached Future means a failed construction can never succeed later in the same JVM; a lookup
     * that does succeed reopens the window at once so only a failing one is ever held back.
     */
    private static Delegator resolveDelegator(ServletContext context) {
        Object published = context.getAttribute(DELEGATOR_ATTRIBUTE);
        if (published instanceof Delegator) {
            return (Delegator) published;
        }
        if (!claimWindow(DELEGATOR_LOOKUP_LAST_AT)) {
            return null;
        }
        Delegator delegator = WebAppUtil.getDelegator(context);
        if (delegator != null) {
            DELEGATOR_LOOKUP_LAST_AT.set(0L);
        }
        return delegator;
    }

    /*
     * Emits one warning for the given event code per READINESS_LOG_INTERVAL_MILLIS, and appends the
     * number of occurrences suppressed since the previous line so an outage - or a saturated probe
     * path - is quantified rather than silently dropped.
     *
     * The line carries the stable code and that count, and nothing else. It is emitted at warning
     * level rather than as an error with a stack trace, because a readiness dip during start-up or a
     * rolling deployment is expected and must not read as a fault.
     *
     * The window and the counter are passed in rather than read from a single pair of fields, so
     * every event code is rate limited independently of the others: one code can neither silence
     * another nor claim its suppressed occurrences.
     */
    private static void logRateLimitedWarning(String eventCode, AtomicLong window, AtomicLong suppressed) {
        if (claimWindow(window)) {
            long missed = suppressed.getAndSet(0L);
            Debug.logWarning(missed == 0L ? eventCode
                    : eventCode + " (" + missed + " further occurrences suppressed)", MODULE);
        } else {
            suppressed.incrementAndGet();
        }
    }

    /*
     * Claims the given rate-limit window for the caller, reporting whether it was won.
     *
     * The compare-and-set is what makes this correct when several probe threads arrive at once:
     * exactly one of them wins the window and the losers are told so, which is why the caller can
     * treat a false result as "another thread is already handling this interval".
     */
    private static boolean claimWindow(AtomicLong window) {
        long now = System.currentTimeMillis();
        long previous = window.get();
        return now - previous >= READINESS_LOG_INTERVAL_MILLIS && window.compareAndSet(previous, now);
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
