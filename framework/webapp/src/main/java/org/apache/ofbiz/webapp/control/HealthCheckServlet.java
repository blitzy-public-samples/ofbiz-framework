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
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.webapp.WebAppUtil;

/**
 * Liveness and readiness endpoints for a load balancer's target-group health check.
 *
 * <p>Two paths are served, and each answers a different question:
 *
 * <ul>
 *   <li>{@code /health/live} - is this JVM answering HTTP at all? Always 200 with
 *       {@code {"status":"UP"}}. It signals only that the servlet container is serving, and deliberately
 *       depends on nothing outside it: an instance whose database is briefly unreachable still answers
 *       200 here.</li>
 *   <li>{@code /health/ready} - can this instance serve a request that touches the database? 200 with
 *       {@code {"status":"UP","database":"UP"}} when a query against the base delegator succeeds, and
 *       503 with {@code {"status":"DOWN","database":"DOWN"}} when it does not. It signals only whether
 *       this instance is currently fit to receive a request.</li>
 * </ul>
 *
 * <p>What a probe result is used for is the caller's policy, not this class's: a load balancer decides
 * target health and routing from it, and an orchestrator may decide replacement from it.
 *
 * <p><strong>A probe is cheap, and bounded.</strong> The readiness query mirrors the {@code ping} service
 * in {@code org.apache.ofbiz.common.CommonServices} - it counts rows in {@code SequenceValueItem}, a seed
 * entity every deployment has, so the check exercises the connection pool, the JDBC driver, the datasource
 * credentials and the schema without touching business data or writing anything - but two limits are
 * applied on top of it, because a probe runs every few seconds on every instance forever:
 *
 * <ul>
 *   <li>The result is CACHED for {@value #READINESS_CACHE_MILLIS} ms, so a probe interval shorter than
 *       that cannot multiply into database load. The cache is deliberately far shorter than any
 *       target-group interval, so the verdict is still current.</li>
 *   <li>The query is run with a DEADLINE of {@value #READINESS_TIMEOUT_MILLIS} ms. Neither the driver nor
 *       the pool bounds a probe usefully - the datasource's socket timeout is 60 s and its pool wait 20 s,
 *       both far above a typical 5 s target-group timeout - so a degraded database would otherwise make
 *       probes HANG rather than fail, and a hung probe is indistinguishable from a lost one. Passing the
 *       deadline is reported as not ready.</li>
 * </ul>
 *
 * <p><strong>It is registered as a FILTER as well as a servlet</strong>, mapped to the same two exact
 * paths and declared ahead of the other filters. That is what keeps a probe from minting an
 * {@code HttpSession}: {@code ControlFilter} calls {@code getSession()} unconditionally, before it
 * examines the path, so a probe that reached it would allocate a session - and, when that session later
 * expired, a transaction and a query to finalise a visit that never happened. Answering in the filter
 * means the rest of the chain never runs for a probe. The servlet registration is kept as the declared
 * endpoint, so the two paths still resolve if the filter mapping is ever removed. Neither role ever calls
 * {@code getSession()} itself.
 *
 * <p>One consequence of answering ahead of the chain is worth recording, because it looks like an omission
 * in a scan: a probe response carries only {@code Cache-Control}, {@code Content-Type} and
 * {@code Content-Length}, and NOT the security headers the filter chain adds to an ordinary response
 * ({@code Strict-Transport-Security}, {@code X-Frame-Options}, {@code Content-Security-Policy} and the
 * rest). Those headers instruct a BROWSER about a document; the two responses here are constant JSON with
 * no markup, no script, no link and no user data, read by a load balancer rather than rendered, so there
 * is nothing for them to protect. Running the chain to obtain them is precisely what would mint the
 * session this class exists to avoid.
 *
 * <p>The class holds no per-request state: the delegator is resolved from the servlet context on every
 * readiness evaluation, so a probe reports what the instance can do NOW rather than what it could do when
 * the class was first loaded. It never authenticates: it is mapped outside {@code /control/*}, so it
 * carries no base permission, and its two paths are listed in {@code ControlFilter}'s
 * {@code allowedPaths} so a probe still reaches it anonymously if the chain does run.
 *
 * <p>Any other path that reaches this class - only possible through an internal dispatch, since the
 * mappings are exact - is answered 404 rather than a false 200, so a misconfigured probe cannot keep a
 * broken instance in service. Any method other than GET and HEAD is answered 405.
 *
 * <p><strong>One near miss is refused rather than routed.</strong> The filter role is also mapped on
 * {@code /control/health/live} and {@code /control/health/ready} - the two probe paths spelled behind the
 * control servlet, which is the spelling an operator copying a documented {@code /control/...} URL is most
 * likely to configure. Those are NOT probe paths and are not answered as such; they are refused 404. Left
 * to the chain they would reach a controller with no request-map for them, and the rendered error view
 * would carry the status the response already had - 200 - which a target group checking for 200 reads as a
 * healthy instance. See {@link #isReservedProbeAlias}. Every other spelling under {@code /health} keeps
 * ordinary routing.
 */
public final class HealthCheckServlet extends HttpServlet implements Filter {

    private static final long serialVersionUID = 1L;

    private static final String MODULE = HealthCheckServlet.class.getName();

    private static final String PROBE_LIVE = "/health/live";
    private static final String PROBE_READY = "/health/ready";

    /**
     * The mount point every OFBiz webapp gives {@code ControlServlet}, and therefore the prefix a probe path
     * acquires when an operator copies it from one of the documented {@code /control/...} URLs. See
     * {@link #isReservedProbeAlias} for why that one near miss cannot be left to ordinary routing.
     */
    private static final String CONTROL_MOUNT = "/control";

    private static final String READINESS_ENTITY = "SequenceValueItem";

    private static final String BODY_LIVE_UP = "{\"status\":\"UP\"}";
    private static final String BODY_READY_UP = "{\"status\":\"UP\",\"database\":\"UP\"}";
    private static final String BODY_READY_DOWN = "{\"status\":\"DOWN\",\"database\":\"DOWN\"}";

    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String CHARACTER_ENCODING = "UTF-8";
    private static final String CACHE_CONTROL_HEADER = "Cache-Control";
    private static final String CACHE_CONTROL_VALUE = "no-store";
    private static final String ALLOW_HEADER = "Allow";
    private static final String ALLOWED_METHODS = "GET, HEAD";
    private static final String METHOD_GET = "GET";
    private static final String METHOD_HEAD = "HEAD";

    /** How long a readiness verdict is reused before the database is asked again. */
    private static final long READINESS_CACHE_MILLIS = 2000L;
    /** How long a probe waits for the readiness query before reporting not ready. */
    private static final long READINESS_TIMEOUT_MILLIS = 2000L;
    /** The shortest interval between two logged readiness failures. */
    private static final long FAILURE_LOG_INTERVAL_MILLIS = 60000L;

    /**
     * Runs the readiness query away from the request thread, so that a database which has stopped
     * answering cannot hold a probe open.
     *
     * <p>One daemon thread is enough: the result cache means at most one query per
     * {@value #READINESS_CACHE_MILLIS} ms is ever submitted, and a submission that outlives its deadline
     * is abandoned rather than waited for. A daemon thread so that it never keeps the JVM alive.
     */
    private static final ExecutorService PROBE_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread worker = new Thread(runnable, "ofbiz-readiness-probe");
        worker.setDaemon(true);
        return worker;
    });

    /**
     * The last verdict for each delegator, and when it was reached.
     *
     * <p>Static, so that the two instances of this class one webapp has - the servlet registration and the
     * filter registration - share one verdict instead of each keeping its own and doubling the queries.
     *
     * <p>Keyed by DELEGATOR NAME, because that is what a readiness verdict is actually about. The class is
     * meant to be registerable from any webapp's web.xml, and two webapps in one JVM can be configured
     * with different delegators through the entityDelegatorName context parameter; a single shared verdict
     * would then let one webapp's database answer for another's, reporting an instance ready on the
     * strength of a database it does not use. The map holds one entry per delegator a probe has asked
     * about, which is one or two in any realistic deployment.
     */
    private static final ConcurrentMap<String, Verdict> VERDICTS = new ConcurrentHashMap<>();

    /** When a readiness failure was last logged, so an outage cannot flood the log. */
    private static final AtomicLong FAILURE_LAST_LOGGED = new AtomicLong();

    /** The servlet context, when this instance is running as a filter rather than as a servlet. */
    private transient ServletContext filterContext;

    /**
     * Records the servlet context of the webapp this filter belongs to.
     *
     * @param filterConfig the filter configuration the container supplies
     */
    @Override
    public void init(FilterConfig filterConfig) {
        this.filterContext = filterConfig.getServletContext();
    }

    /**
     * Answers a probe without running the rest of the filter chain, refuses the one near miss that would
     * otherwise report false health, and passes everything else through.
     *
     * <p>The chain is deliberately NOT continued for a probe path: continuing it is what would mint a
     * session. Every request that is neither a probe nor one of the two reserved aliases is passed on
     * untouched, so this mapping cannot affect anything else even if it is widened by mistake.
     *
     * @param request the request
     * @param response the response
     * @param chain the rest of the chain
     * @throws IOException if the response cannot be written
     * @throws ServletException if the rest of the chain fails
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest probe && response instanceof HttpServletResponse answer) {
            String path = pathWithinWebapp(probe);
            if (isProbePath(path)) {
                // service() rather than handleProbe(), so that method handling - GET and HEAD answered, every
                // other method refused with 405 and an Allow header - is decided in exactly one place for
                // both roles. See the service() override below.
                service(probe, answer);
                return;
            }
            if (isReservedProbeAlias(path)) {
                refuseAlias(answer);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * Reports whether a path within a webapp is a probe path spelled behind the control servlet, which is the
     * one near miss that must never be answered by the ordinary request handler.
     *
     * <p>Every OFBiz webapp mounts {@code ControlServlet} at {@code /control/*}, and almost every documented URL
     * in the product carries that prefix, so {@code /webtools/control/health/live} is the spelling an operator
     * configuring a target group is most likely to reach for. It is not a probe path: the controller has no
     * request-map for it, so the request handler fails and the control servlet serves its error view - and it
     * serves that view with the status the response already had, which is {@code 200}. A load balancer that
     * expects {@code 200} therefore reads a rendered error page as a healthy instance, indefinitely, and the one
     * thing a health check exists to do is the one thing it then cannot do. The filters ahead of that servlet
     * also call {@code getSession()} unconditionally, so every such request mints a session and emits a cookie
     * for a caller that keeps neither.
     *
     * <p>Derived from the same two literals as {@link #isProbePath}, so an alias cannot drift from the path it
     * shadows: renaming a probe path renames its alias in the same edit. The match is exact after the prefix,
     * for the same reason {@link #isProbePath} is exact - no {@code /control/health} space is reserved, so every
     * other spelling keeps the routing it has always had.
     *
     * @param pathWithinWebapp the requested path relative to the webapp's context path; may be null
     * @return true if the path is a probe path prefixed with the control servlet's mount point
     */
    public static boolean isReservedProbeAlias(String pathWithinWebapp) {
        return pathWithinWebapp != null && pathWithinWebapp.startsWith(CONTROL_MOUNT)
                && isProbePath(pathWithinWebapp.substring(CONTROL_MOUNT.length()));
    }

    /**
     * Refuses a probe path spelled behind the control servlet, with a status a load balancer cannot read as
     * health.
     *
     * <p><strong>Why this is refused rather than answered.</strong> Answering it would make a second spelling of
     * every probe path real, so a target group could be configured against either and the two would have to keep
     * agreeing forever, and it would place a probe inside the space a webapp's request handler owns - which is
     * the space answering in the filter exists to keep probes out of. Refusing states plainly that the path is
     * not an endpoint, and one look at the target group's health history says so.
     *
     * <p>{@code 404} is set with {@code setStatus} rather than {@code sendError}, deliberately:
     * {@code sendError} hands the response to the container's error-page machinery, which would answer an
     * anonymous caller with an HTML page and, in a webapp that declares an {@code error-page}, could route the
     * refusal somewhere that builds a session of its own. What is written instead is a status, the same
     * no-store directive a real probe answer carries so no intermediary keeps the refusal, and no body at all.
     *
     * @param response the response to refuse on
     */
    private static void refuseAlias(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setHeader(CACHE_CONTROL_HEADER, CACHE_CONTROL_VALUE);
        response.setContentLength(0);
    }

    /**
     * Dispatches a probe request: GET and HEAD are answered, every other method is refused with 405.
     *
     * <p>Method handling is decided HERE, in one place, rather than in the {@code doXxx} hooks, because
     * {@code HttpServlet}'s own dispatch does not implement this contract: its {@code doPost},
     * {@code doPut} and {@code doDelete} answer 405 with no {@code Allow} header, its {@code doOptions}
     * answers 200 and advertises TRACE, its {@code doTrace} echoes the request back, and a method it does
     * not recognise at all - PATCH, say - gets 501. A probe endpoint should give one answer to everything
     * it does not serve, with the header that says what it does serve, so the dispatch is replaced rather
     * than patched hook by hook. It also means the filter role and the servlet role cannot diverge: both
     * arrive here.
     *
     * <p>HEAD is answered by exactly the code that answers GET, so the status and the headers - including
     * Content-Length - are identical; the container discards the body of a HEAD response itself.
     *
     * <p>TRACE normally never reaches this method: Tomcat's {@code allowTrace} defaults to false and the
     * Catalina descriptor does not set it, so the connector refuses TRACE with its own 405 and its own
     * {@code Allow} header before any webapp is consulted. The branch below covers a deployment that turns
     * {@code allowTrace} on, which would otherwise let {@code HttpServlet.doTrace} echo a probe request
     * back to its sender.
     *
     * @param request the request
     * @param response the response to write
     * @throws IOException if the response cannot be written
     */
    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String method = request.getMethod();
        if (METHOD_GET.equals(method) || METHOD_HEAD.equals(method)) {
            handleProbe(request, response);
        } else {
            refuseMethod(response);
        }
    }

    private static void refuseMethod(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        response.setHeader(ALLOW_HEADER, ALLOWED_METHODS);
        response.setHeader(CACHE_CONTROL_HEADER, CACHE_CONTROL_VALUE);
    }

    private static boolean isProbePath(String path) {
        return PROBE_LIVE.equals(path) || PROBE_READY.equals(path);
    }

    private void handleProbe(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = pathWithinWebapp(request);
        if (PROBE_LIVE.equals(path)) {
            writeResponse(response, HttpServletResponse.SC_OK, BODY_LIVE_UP);
        } else if (PROBE_READY.equals(path)) {
            if (isDatabaseReachable()) {
                writeResponse(response, HttpServletResponse.SC_OK, BODY_READY_UP);
            } else {
                writeResponse(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, BODY_READY_DOWN);
            }
        } else {
            // Not a probe path. Answered without a body, and never with a 200, so a mistyped probe URL
            // cannot report health this class did not establish.
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setHeader(CACHE_CONTROL_HEADER, CACHE_CONTROL_VALUE);
        }
    }

    /**
     * Reports whether this instance can reach its database, reusing a recent verdict.
     *
     * <p>The delegator is resolved on every probe, before the cache is consulted, so that the verdict
     * reused is one about THIS webapp's database and a webapp whose delegator changes is not answered from
     * the previous one's verdict. Resolving it is a servlet-context attribute lookup, not a connection.
     *
     * @return true when the readiness query succeeded and found the seed entity populated
     */
    private boolean isDatabaseReachable() {
        ServletContext context = servletContext();
        Delegator delegator = context == null ? null : WebAppUtil.getDelegator(context);
        if (delegator == null) {
            reportFailure("Readiness probe found no delegator for this webapp", null);
            return false;
        }
        // A delegator with no name would be unusual; keyed under the empty string rather than risking a
        // null key, so that an odd configuration cannot turn a probe into an exception.
        String scope = Objects.requireNonNullElse(delegator.getDelegatorName(), "");
        long now = System.currentTimeMillis();
        Verdict held = VERDICTS.get(scope);
        if (held != null && now - held.at() < READINESS_CACHE_MILLIS) {
            return held.up();
        }
        boolean up = evaluateReadiness(delegator);
        VERDICTS.put(scope, new Verdict(System.currentTimeMillis(), up));
        return up;
    }

    /**
     * Runs the readiness query under a deadline.
     *
     * <p>Both a checked {@code GenericEntityException} and an unchecked failure from the pool or the
     * driver are reported as not ready, as is passing the deadline: a readiness probe answers a question,
     * so it never propagates. An abandoned query is cancelled with an interrupt, so a driver that honours
     * one gives up its connection rather than holding it for the socket timeout.
     *
     * @param delegator the delegator to query, never null
     * @return true when the query succeeded within the deadline and found the seed entity populated
     */
    private boolean evaluateReadiness(Delegator delegator) {
        Callable<Boolean> statement = () -> EntityQuery.use(delegator).from(READINESS_ENTITY).queryCount() > 0;
        Future<Boolean> query = PROBE_EXECUTOR.submit(statement);
        try {
            if (Boolean.TRUE.equals(query.get(READINESS_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))) {
                return true;
            }
            // The query SUCCEEDED and found nothing. Reported, because otherwise this is the one way to
            // answer 503 with nothing in the log to say why, and an operator watching a rollout could not
            // tell an empty schema from an unreachable database. It is the expected state of a database
            // whose schema has just been created: no identifier has been allocated from the sequencer yet.
            reportFailure("Readiness probe reached the database but found no allocated identifier in "
                    + READINESS_ENTITY + ", so this instance cannot serve yet. Load the seed data, or wait"
                    + " for the first sequenced record to be written", null);
            return false;
        } catch (TimeoutException slow) {
            query.cancel(true);
            reportFailure("Readiness probe gave up after " + READINESS_TIMEOUT_MILLIS
                    + " ms waiting for the database", null);
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            query.cancel(true);
            return false;
        } catch (RuntimeException | java.util.concurrent.ExecutionException failed) {
            reportFailure("Readiness probe could not reach the database", failed.getCause() == null
                    ? failed : failed.getCause());
            return false;
        }
    }

    /**
     * Logs a readiness failure at most once per {@value #FAILURE_LOG_INTERVAL_MILLIS} ms.
     *
     * <p>A probe runs every few seconds on every instance, so an unrated log line would turn a database
     * outage - exactly when the log has to stay readable - into unbounded log volume. The interval is
     * enough to keep the outage visible while a single instance contributes at most one line a minute.
     *
     * @param message what happened
     * @param cause the failure, or null when there is no exception to report
     */
    private static void reportFailure(String message, Throwable cause) {
        long now = System.currentTimeMillis();
        long last = FAILURE_LAST_LOGGED.get();
        if (now - last < FAILURE_LOG_INTERVAL_MILLIS || !FAILURE_LAST_LOGGED.compareAndSet(last, now)) {
            return;
        }
        if (cause == null) {
            Debug.logError(message + ". Further readiness failures are logged at most once every "
                    + FAILURE_LOG_INTERVAL_MILLIS + " ms.", MODULE);
        } else {
            // The exception object, not just its message: getMessage() can be null, which logged the
            // literal "null" and told an operator nothing at all.
            Debug.logError(cause, message + ". Further readiness failures are logged at most once every "
                    + FAILURE_LOG_INTERVAL_MILLIS + " ms.", MODULE);
        }
    }

    /**
     * Returns the servlet context of the webapp this instance belongs to, in either role.
     *
     * @return the context, or null when neither role has been initialised
     */
    private ServletContext servletContext() {
        if (filterContext != null) {
            return filterContext;
        }
        return getServletConfig() == null ? null : getServletContext();
    }

    /**
     * Returns the requested path with the context path removed.
     *
     * <p>Assembled from the container's own decoded, normalised parts rather than from the raw URI, so
     * that a percent-escaped or dot-segment spelling cannot be mistaken for a probe path. With the exact
     * mappings this class is registered under, the whole path is in the servlet path and there is no
     * path info; an internal dispatch can produce either.
     *
     * @param request the probe request
     * @return the path within the webapp, never null
     */
    private static String pathWithinWebapp(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        String pathInfo = request.getPathInfo();
        return (servletPath == null ? "" : servletPath) + (pathInfo == null ? "" : pathInfo);
    }

    /**
     * Writes one probe response: status, JSON body, and headers that stop anything caching a verdict.
     *
     * <p>{@code setStatus} rather than {@code sendError}, because {@code sendError} hands the response to
     * the container's error page machinery, which would replace this body with an HTML error page and
     * could run an error-page dispatch on a path that requires the database this probe just reported
     * unreachable.
     *
     * @param response the response to write
     * @param status the HTTP status to report
     * @param body the JSON body to write
     * @throws IOException if the response cannot be written
     */
    private static void writeResponse(HttpServletResponse response, int status, String body)
            throws IOException {
        response.setStatus(status);
        response.setContentType(CONTENT_TYPE_JSON);
        response.setCharacterEncoding(CHARACTER_ENCODING);
        response.setHeader(CACHE_CONTROL_HEADER, CACHE_CONTROL_VALUE);
        response.getWriter().write(body);
    }

    /**
     * One readiness verdict and the instant it was reached.
     *
     * @param at the epoch millisecond the verdict was reached
     * @param up whether the database answered
     */
    private record Verdict(long at, boolean up) { }
}
