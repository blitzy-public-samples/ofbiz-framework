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
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.jms.JmsListenerFactory;
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
 *   <li>{@code /health/ready} - can this instance serve a request? 200 with
 *       {@code {"status":"UP","database":"UP"}} when a query against the base delegator RUNS, and 503 with
 *       {@code {"status":"DOWN","database":"DOWN"}} when it does not - which covers an unreachable
 *       database, a datasource whose credentials are refused, and a schema that has not been created,
 *       because the query's own entity is then absent. It signals only whether this instance is currently
 *       fit to receive a request, so a schema that exists but has sequenced nothing yet is ready.</li>
 * </ul>
 *
 * <p><strong>Readiness reports on every dependency this deployment has CONFIGURED, not only the
 * database.</strong> A serving instance depends on whatever it has been pointed at, and the two things
 * this deployment can be pointed at are an external content store and a message bus:
 *
 * <ul>
 *   <li>{@code contentStore} - reported only when {@code content.store.provider} names a store rather
 *       than the default {@code database}. An instance whose object store or shared mount cannot be
 *       reached serves a 500 for every file-backed content read and cannot durably accept an upload, so
 *       it is not fit to receive that request - and until this was reported, such an instance stayed in
 *       rotation answering 200 to its probe seven milliseconds before failing a content read.</li>
 *   <li>{@code messaging} - reported only when the delegator has distributed cache clear enabled, which
 *       is the configuration that puts the message bus on the WRITE path: with it on, an entity write
 *       publishes an invalidation, and a broker that has gone makes that write roll back after its
 *       timeout. With it off a broker is nobody's dependency and nothing is reported.</li>
 * </ul>
 *
 * <p>A key appears in the body only when its dependency is configured, so the readiness contract of a
 * database-only deployment - the zero-configuration local run and any deployment storing content in the
 * database without a bus - is byte for byte what it always was. The body carries {@code UP} or
 * {@code DOWN} per dependency and never the REASON: this endpoint is unauthenticated, and a reason names
 * buckets, endpoints, mount points and broker addresses. The reason goes to the log, rate limited per
 * dependency.
 *
 * <p>What a probe result is used for is the caller's policy, not this class's: a load balancer decides
 * target health and routing from it, and an orchestrator may decide replacement from it.
 *
 * <p><strong>A probe is cheap, and bounded.</strong> The readiness query mirrors the {@code ping} service
 * in {@code org.apache.ofbiz.common.CommonServices} - it counts rows in {@code SequenceValueItem}, a seed
 * entity every deployment has, so the check exercises the connection pool, the JDBC driver, the datasource
 * credentials and the schema without touching business data or writing anything; each configured
 * dependency is asked the cheapest question that distinguishes a dependency it can use from one it cannot,
 * which transfers nothing and needs nothing to exist. Two limits are applied on top of every one of them,
 * because a probe runs every few seconds on every instance forever:
 *
 * <ul>
 *   <li>The result is CACHED for {@value #READINESS_CACHE_MILLIS} ms, so a probe interval shorter than
 *       that cannot multiply into database load. The cache is deliberately far shorter than any
 *       target-group interval, so the verdict is still current.</li>
 *   <li>Every question is run with a DEADLINE - {@value #READINESS_TIMEOUT_MILLIS} ms for the database,
 *       {@value #DEPENDENCY_TIMEOUT_MILLIS} ms for each configured dependency, so the whole readiness
 *       evaluation stays inside a typical 5 s target-group timeout even when everything it depends on has
 *       stopped answering. No client bounds a probe usefully on its own - the datasource's socket timeout
 *       is 60 s and its pool wait 20 s, the object store's API call timeout 45 s - so a degraded
 *       dependency would otherwise make probes HANG rather than fail, and a hung probe is
 *       indistinguishable from a lost one. Passing the deadline is reported as not ready.</li>
 *   <li>At most ONE question of each kind is ever outstanding. A dependency check that passed its deadline
 *       is left running rather than abandoned and re-submitted, and the next probe waits on the same
 *       one - so an outage cannot accumulate a queue of probe work or a thread per probe interval.</li>
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
 * <p>Answering ahead of the chain means the chain's response headers are not applied either, so this class
 * sets them itself: every response it writes carries {@code Cache-Control: no-store} plus
 * {@code Strict-Transport-Security}, {@code X-Frame-Options}, {@code X-Content-Type-Options},
 * {@code X-XSS-Protection} and {@code Referrer-Policy}, at the same values an ordinary response in this
 * deployment gets. A probe response is therefore not the one response in the deployment that differs in a
 * header scan - and it stays free, because the headers are literals applied in
 * {@link #setCommonHeaders} rather than obtained by running the session-creating chain.
 * {@code Content-Security-Policy} is the deliberate exception: reading the configured policy needs the
 * delegator-backed property lookup a liveness probe must not depend on, and the directive governs how a
 * BROWSER renders a document, of which a 15-byte JSON literal with no markup, script, link or user data
 * contains nothing.
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

    private static final String STATUS_KEY = "status";
    private static final String DATABASE_KEY = "database";
    private static final String CONTENT_STORE_KEY = "contentStore";
    private static final String MESSAGING_KEY = "messaging";
    private static final String UP = "UP";
    private static final String DOWN = "DOWN";

    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String CHARACTER_ENCODING = "UTF-8";
    private static final String CACHE_CONTROL_HEADER = "Cache-Control";
    private static final String CACHE_CONTROL_VALUE = "no-store";
    private static final String ALLOW_HEADER = "Allow";
    private static final String ALLOWED_METHODS = "GET, HEAD";
    private static final String METHOD_GET = "GET";
    private static final String METHOD_HEAD = "HEAD";

    /**
     * The security headers every other response in the deployment carries, and their framework defaults.
     *
     * <p>The values are the ones {@code UtilHttp.setResponseBrowserDefaultSecurityHeaders} applies to an
     * ordinary response when no view overrides them, so a probe response is not the odd one out in a header
     * scan. They are literals HERE rather than a call to that method because it resolves its settings
     * through {@code EntityUtilProperties} and ends by calling {@code SameSiteFilter}: a database lookup and
     * a cookie decision, neither of which a liveness probe may depend on. What a probe answers has to stay
     * answerable while the database is unreachable, which is the whole point of the endpoint.
     *
     * <p>{@code Content-Security-Policy} is deliberately NOT among them. Its configured value is only
     * obtainable through the same delegator-backed lookup, and it instructs a browser about a document -
     * there is no markup, script, style, image or frame in a 15-byte JSON literal for it to govern.
     */
    private static final String[][] SECURITY_HEADERS = {
        {"x-frame-options", "sameorigin"},
        {"x-content-type-options", "nosniff"},
        {"X-XSS-Protection", "1; mode=block"},
        {"Referrer-Policy", "no-referrer-when-downgrade"},
    };

    private static final String HSTS_HEADER = "strict-transport-security";
    private static final String HSTS_VALUE = "max-age=31536000; includeSubDomains";
    private static final String HSTS_RESOURCE = "requestHandler";
    private static final String HSTS_PROPERTY = "strict-transport-security";

    /** How long a readiness verdict is reused before the database is asked again. */
    private static final long READINESS_CACHE_MILLIS = 2000L;
    /** How long a probe waits for the readiness query before reporting not ready. */
    private static final long READINESS_TIMEOUT_MILLIS = 2000L;
    /**
     * How long a probe waits for one configured dependency before reporting it not ready.
     *
     * <p>Shorter than the database's deadline, deliberately. The database is the dependency without which
     * nothing at all works, so it is worth waiting the longer time for; the sum of every deadline is what a
     * target group's own timeout has to accommodate, and this keeps that sum - {@value
     * #READINESS_TIMEOUT_MILLIS} ms plus one of these per configured dependency - under the 5 s a target
     * group is typically given. It is also two orders of magnitude above what a reachable dependency
     * actually takes: a {@code HeadBucket} against a store in the same network answers in single-digit
     * milliseconds, and the message-bus question is a field read.
     */
    private static final long DEPENDENCY_TIMEOUT_MILLIS = 1000L;
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
     * Runs the configured dependencies' readiness questions away from the request thread, for the same
     * reason the database's runs away from it, and away from the database's thread as well.
     *
     * <p>Its own executor so that one dependency which has stopped answering cannot delay another, nor the
     * database check - a store whose API call timeout is 45 s would otherwise sit in front of everything
     * behind it on a single thread. The pool grows on demand and is bounded not by a thread limit but by
     * the checks themselves: {@link DependencyCheck} keeps at most one question of its kind outstanding, so
     * at most one thread per configured dependency is ever in use, and each is reclaimed after 60 s idle.
     * Daemon threads, so none of them keeps the JVM alive.
     */
    private static final ExecutorService DEPENDENCY_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread worker = new Thread(runnable, "ofbiz-readiness-dependency");
        worker.setDaemon(true);
        return worker;
    });

    /**
     * The content store's readiness hook, resolved reflectively, or null when this deployment has no content
     * component.
     *
     * <p><strong>Reflection, deliberately.</strong> {@code ContentStoreFactory} lives in the content
     * APPLICATION component and this class lives in the framework: the one-way dependency direction from
     * applications to framework is part of the architecture, and an import here would invert it. What is
     * needed of it is one static method returning one string, so the reflective boundary is a single
     * {@link Method} resolved once at class load and invoked with no arguments - not a per-probe lookup.
     *
     * <p>Null when the class or the method is absent, which is how a deployment whose component set does not
     * include the content component reports nothing about content storage rather than failing its probe.
     */
    private static final Method CONTENT_STORE_READINESS = resolveContentStoreReadiness();

    /** The configured content store, asked whether it can be reached at all. */
    private static final DependencyCheck CONTENT_STORE = new DependencyCheck("the configured content store",
            HealthCheckServlet::askContentStore);

    /** The message bus carrying entity-cache invalidations, asked whether its listeners are connected. */
    private static final DependencyCheck MESSAGING = new DependencyCheck(
            "the message bus carrying entity-cache invalidations", JmsListenerFactory::readinessFailure);

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

    /**
     * When the empty-sequencer note was last logged.
     *
     * <p>Kept apart from {@link #FAILURE_LAST_LOGGED} on purpose. That state is not a failure - the
     * instance is reported ready - so it must neither be logged as an error nor consume the window that
     * rates real database failures, or a freshly provisioned instance could silently swallow the one line
     * saying its database had become unreachable.
     */
    private static final AtomicLong SEQUENCER_EMPTY_LAST_LOGGED = new AtomicLong();

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
        setCommonHeaders(response);
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
        setCommonHeaders(response);
    }

    private static boolean isProbePath(String path) {
        return PROBE_LIVE.equals(path) || PROBE_READY.equals(path);
    }

    private void handleProbe(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = pathWithinWebapp(request);
        if (PROBE_LIVE.equals(path)) {
            writeResponse(response, HttpServletResponse.SC_OK, BODY_LIVE_UP);
        } else if (PROBE_READY.equals(path)) {
            Readiness readiness = assessReadiness();
            writeResponse(response, readiness.up() ? HttpServletResponse.SC_OK
                    : HttpServletResponse.SC_SERVICE_UNAVAILABLE, readiness.body());
        } else {
            // Not a probe path. Answered without a body, and never with a 200, so a mistyped probe URL
            // cannot report health this class did not establish.
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            setCommonHeaders(response);
        }
    }

    /**
     * Assesses everything this instance's readiness depends on and renders the answer.
     *
     * <p>The database always; each of the two possible external dependencies only when this deployment has
     * been configured with it. The instance is ready when every dependency that WAS asked answered - a
     * dependency that is not configured is not a dependency, and contributes neither a key to the body nor a
     * vote to the verdict.
     *
     * <p>All of them are assessed even once one has already failed, rather than short-circuiting on the
     * first: the body is what an operator reads to find out WHICH dependency took the instance out, and one
     * that stopped at the first failure would name only the first. Each has its own deadline and its own
     * cached verdict, so assessing all of them costs no more than the slowest.
     *
     * @return the verdict and the JSON body reporting it
     */
    private Readiness assessReadiness() {
        ServletContext context = servletContext();
        Delegator delegator = context == null ? null : WebAppUtil.getDelegator(context);
        if (delegator == null) {
            reportFailure("Readiness probe found no delegator for this webapp", null, FAILURE_LAST_LOGGED);
            return new Readiness(false, render(false, null, null));
        }
        boolean databaseUp = isDatabaseReachable(delegator);
        String store = CONTENT_STORE.evaluate();
        // Asked only when the delegator publishes entity-cache invalidations, because that is the
        // configuration under which an ordinary WRITE waits for the broker and rolls back without it. With
        // distributed cache clear off, a broker outage costs this instance nothing and must not remove it
        // from the load balancer's rotation.
        String messaging = usesDistributedCacheClear(delegator) ? MESSAGING.evaluate() : null;
        boolean up = databaseUp && answered(store) && answered(messaging);
        return new Readiness(up, render(databaseUp, store, messaging));
    }

    /**
     * Reports whether a dependency check's outcome means the dependency is fit to serve.
     *
     * @param outcome null when the dependency is not configured, the empty string when it answered,
     *     otherwise the reason it did not
     * @return true when nothing is wrong with it, which includes it not being configured at all
     */
    private static boolean answered(String outcome) {
        return outcome == null || outcome.isEmpty();
    }

    /**
     * Reports whether this delegator publishes entity-cache invalidations over the message bus.
     *
     * @param delegator the delegator this webapp uses, never null
     * @return true when distributed cache clear is enabled for it
     */
    private static boolean usesDistributedCacheClear(Delegator delegator) {
        try {
            return delegator.useDistributedCacheClear();
        } catch (RuntimeException unavailable) {
            // A readiness probe answers; it never propagates. Read as "not configured", so that a delegator
            // which cannot say leaves the body exactly as a deployment without a bus.
            reportFailure("Readiness probe could not establish whether distributed cache clear is enabled",
                    unavailable, FAILURE_LAST_LOGGED);
            return false;
        }
    }

    /**
     * Renders the readiness body: the overall status, the database, and a key per CONFIGURED dependency.
     *
     * <p>Assembled rather than answered from a constant, because which keys belong in it is a property of
     * the deployment. The order is fixed and the values are {@code UP} or {@code DOWN} alone - never a
     * reason, because this endpoint is unauthenticated and a reason names infrastructure. A deployment with
     * neither dependency configured renders exactly the two-key body this endpoint has always answered.
     *
     * @param databaseUp whether the database answered
     * @param store the content store's outcome, null when content is stored in the database
     * @param messaging the message bus's outcome, null when no bus is on the write path
     * @return the JSON body
     */
    static String render(boolean databaseUp, String store, String messaging) {
        boolean up = databaseUp && answered(store) && answered(messaging);
        StringBuilder body = new StringBuilder(96);
        body.append('{');
        append(body, STATUS_KEY, up ? UP : DOWN);
        body.append(',');
        append(body, DATABASE_KEY, databaseUp ? UP : DOWN);
        if (store != null) {
            body.append(',');
            append(body, CONTENT_STORE_KEY, answered(store) ? UP : DOWN);
        }
        if (messaging != null) {
            body.append(',');
            append(body, MESSAGING_KEY, answered(messaging) ? UP : DOWN);
        }
        return body.append('}').toString();
    }

    private static void append(StringBuilder body, String key, String value) {
        body.append('"').append(key).append("\":\"").append(value).append('"');
    }

    /**
     * Reports whether this instance can reach its database, reusing a recent verdict.
     *
     * <p>The delegator is resolved before the cache is consulted, so that the verdict reused is one about
     * THIS webapp's database and a webapp whose delegator changes is not answered from the previous one's
     * verdict.
     *
     * @param delegator the delegator this webapp uses, never null
     * @return true when the readiness query succeeded and found the seed entity populated
     */
    private boolean isDatabaseReachable(Delegator delegator) {
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
     * Invokes the content store's readiness hook, when this deployment has one.
     *
     * @return null when there is no content component or content is stored in the database, the empty string
     *     when the configured store is usable, otherwise the reason it is not
     * @throws ReflectiveOperationException if the hook cannot be invoked
     */
    private static String askContentStore() throws ReflectiveOperationException {
        if (CONTENT_STORE_READINESS == null) {
            return null;
        }
        return (String) CONTENT_STORE_READINESS.invoke(null);
    }

    /**
     * Resolves the content store's readiness hook once, at class load.
     *
     * @return the hook, or null when this deployment has no content component
     */
    private static Method resolveContentStoreReadiness() {
        try {
            return Class.forName("org.apache.ofbiz.content.data.store.ContentStoreFactory")
                    .getMethod("readinessFailure");
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError absent) {
            Debug.logInfo("No content-store readiness hook is available in this deployment (" + absent
                    + "), so readiness reports on the database alone unless a message bus is configured.",
                    MODULE);
            return null;
        }
    }

    /**
     * Runs the readiness query under a deadline.
     *
     * <p>Both a checked {@code GenericEntityException} and an unchecked failure from the pool or the
     * driver are reported as not ready, as is passing the deadline: a readiness probe answers a question,
     * so it never propagates. An abandoned query is cancelled with an interrupt, so a driver that honours
     * one gives up its connection rather than holding it for the socket timeout.
     *
     * <p><strong>What counts as success, and why it is not a row count.</strong> The query has to RUN, not
     * to find anything. It reads a seed entity every deployment has, so running it exercises the connection
     * pool, the driver, the datasource credentials and the schema - and on a database whose schema has not
     * been created the entity's table does not exist, so the query FAILS and the instance is correctly
     * reported not ready. Requiring a row on top of that is what a load balancer cannot survive: nothing
     * writes to {@value #READINESS_ENTITY} until some request causes an identifier to be sequenced, so a
     * newly provisioned deployment carrying only seed data has an empty sequencer, and demanding a row makes
     * readiness answer 503 until a request arrives while the target group sends no request until readiness
     * answers 200. Measured on a fresh seed-only PostgreSQL database: the sequencer stayed empty
     * indefinitely and readiness stayed 503, and one ordinary request - which only a direct caller
     * bypassing the load balancer could make - allocated two identifiers and flipped it to 200. An instance
     * in that state is fit to serve, so it is reported ready; an empty sequencer is still noted in the log,
     * because it is worth knowing during a rollout.
     *
     * @param delegator the delegator to query, never null
     * @return true when the readiness query ran successfully within the deadline
     */
    private boolean evaluateReadiness(Delegator delegator) {
        Callable<Boolean> statement = () -> EntityQuery.use(delegator).from(READINESS_ENTITY).queryCount() > 0;
        Future<Boolean> query = PROBE_EXECUTOR.submit(statement);
        try {
            if (Boolean.TRUE.equals(query.get(READINESS_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))) {
                return true;
            }
            // The query RAN and found no row. Ready, and noted at info level on its own rated window: an
            // operator watching a rollout should be able to tell this state - a schema that exists but has
            // sequenced nothing yet - from an unreachable database, and the two are otherwise
            // indistinguishable from the outside.
            noteReadyState("Readiness probe reached the database and found no allocated identifier in "
                    + READINESS_ENTITY + " yet, which is the expected state of a schema that has just been"
                    + " created or loaded with seed data only. This instance is reported READY, because it"
                    + " can serve: the first request that sequences an identifier will populate it",
                    SEQUENCER_EMPTY_LAST_LOGGED);
            return true;
        } catch (TimeoutException slow) {
            query.cancel(true);
            reportFailure("Readiness probe gave up after " + READINESS_TIMEOUT_MILLIS
                    + " ms waiting for the database", null, FAILURE_LAST_LOGGED);
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            query.cancel(true);
            return false;
        } catch (RuntimeException | ExecutionException failed) {
            reportFailure("Readiness probe could not reach the database", failed.getCause() == null
                    ? failed : failed.getCause(), FAILURE_LAST_LOGGED);
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
     * <p>The window is passed in rather than shared, so that each dependency rates its own log independently:
     * one shared window would let a database outage suppress the one line saying the object store had gone
     * too, which is the line an operator needs to stop looking at the database.
     *
     * @param message what happened
     * @param cause the failure, or null when there is no exception to report
     * @param window when this kind of failure was last logged
     */
    private static void reportFailure(String message, Throwable cause, AtomicLong window) {
        long now = System.currentTimeMillis();
        long last = window.get();
        if (now - last < FAILURE_LOG_INTERVAL_MILLIS || !window.compareAndSet(last, now)) {
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
     * Logs a noteworthy but healthy readiness state at most once per {@value #FAILURE_LOG_INTERVAL_MILLIS} ms.
     *
     * <p>The counterpart to {@link #reportFailure}: same rating, information level, and always a window of
     * its own. A state that leaves the instance ready is not an error, and an operator scanning the log for
     * errors during a rollout should not have to decide which of them mean the instance is refusing traffic.
     *
     * @param message what was observed
     * @param window when this state was last logged
     */
    private static void noteReadyState(String message, AtomicLong window) {
        long now = System.currentTimeMillis();
        long last = window.get();
        if (now - last < FAILURE_LOG_INTERVAL_MILLIS || !window.compareAndSet(last, now)) {
            return;
        }
        Debug.logInfo(message + ". This note is logged at most once every " + FAILURE_LOG_INTERVAL_MILLIS
                + " ms.", MODULE);
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
        setCommonHeaders(response);
        response.getWriter().write(body);
    }

    /**
     * Applies the headers every response this class writes carries.
     *
     * <p>{@code Cache-Control: no-store}, so that no intermediary keeps a verdict, and the same security
     * headers an ordinary response in this deployment receives from the filter chain. They are set HERE, in
     * one place, rather than by letting the chain run: running the chain for a probe is what would mint an
     * {@code HttpSession} every few seconds forever, which is the reason this class answers ahead of it. One
     * set for all five answers - liveness, readiness up, readiness down, the 405 and both 404 refusals - so
     * that no spelling of a probe response differs from another in a header scan.
     *
     * <p>Nothing here reads the database or touches a cookie, so a probe stays answerable exactly as long as
     * the JVM is running.
     *
     * @param response the response being written
     */
    private static void setCommonHeaders(HttpServletResponse response) {
        response.setHeader(CACHE_CONTROL_HEADER, CACHE_CONTROL_VALUE);
        for (String[] header : SECURITY_HEADERS) {
            response.setHeader(header[0], header[1]);
        }
        // Read from the property FILE, deliberately. The framework resolves the same switch through
        // EntityUtilProperties, which consults the database; a probe must not. An operator who turns HSTS
        // off in requestHandler.properties turns it off for the probes too, while a SystemProperty row
        // overriding it does not reach them - stated here because that is the one behavioural difference
        // between this header set and the chain's.
        if (UtilProperties.getPropertyAsBoolean(HSTS_RESOURCE, HSTS_PROPERTY, true)) {
            response.setHeader(HSTS_HEADER, HSTS_VALUE);
        }
    }

    /**
     * One readiness verdict and the instant it was reached.
     *
     * @param at the epoch millisecond the verdict was reached
     * @param up whether the database answered
     */
    private record Verdict(long at, boolean up) { }

    /**
     * One readiness answer: the verdict a target group acts on, and the body reporting it.
     *
     * @param up whether every configured dependency answered
     * @param body the JSON body to write
     */
    private record Readiness(boolean up, String body) { }

    /**
     * One configured dependency's readiness question, with the cache, the deadline and the rated log that
     * make it safe to ask on every probe.
     *
     * <p>The question answers in the three-state encoding both hooks share - null when the dependency is not
     * configured, the empty string when it answered, otherwise the reason it did not - and this class adds
     * nothing to that answer except the ability to ask it cheaply:
     *
     * <ul>
     *   <li><strong>Cached</strong> for {@value #READINESS_CACHE_MILLIS} ms, so a probe interval shorter than
     *       that cannot multiply into requests against the dependency.</li>
     *   <li><strong>Bounded</strong> at {@value #DEPENDENCY_TIMEOUT_MILLIS} ms, because a dependency's own
     *       client bounds a probe far too generously - 45 s for the object store - and a probe that hung
     *       would be read by a target group as a lost one.</li>
     *   <li><strong>At most one outstanding.</strong> A question that passed its deadline is deliberately NOT
     *       cancelled and NOT re-submitted: the next probe waits on the same one. Cancelling would not stop a
     *       socket read that is already in progress, and submitting another every probe interval during an
     *       outage is how a probe turns a dependency outage into a thread leak.</li>
     * </ul>
     *
     * <p>One instance per dependency, held statically, so the servlet registration and the filter
     * registration of this class share one cache and one outstanding question rather than each keeping their
     * own.
     *
     * <p>Package private, along with {@link #evaluate}, so that the cache, the deadline and the
     * one-outstanding-question rule can be asserted directly against a question the test controls. None of
     * the three is observable from outside this class otherwise, and each of them is a property a probe's
     * correctness rests on.
     */
    static final class DependencyCheck {

        private final String label;
        private final Callable<String> question;
        private final AtomicReference<Future<String>> outstanding = new AtomicReference<>();
        private final AtomicLong lastLogged = new AtomicLong();
        private volatile Answer held;

        DependencyCheck(String label, Callable<String> question) {
            this.label = label;
            this.question = question;
        }

        /**
         * Answers what this dependency's state is, from a recent answer when there is one.
         *
         * @return null when the dependency is not configured, the empty string when it answered, otherwise
         *     the reason it did not
         */
        String evaluate() {
            long now = System.currentTimeMillis();
            Answer recent = held;
            if (recent != null && now - recent.at() < READINESS_CACHE_MILLIS) {
                return recent.outcome();
            }
            Future<String> running = outstanding.get();
            if (running == null || running.isDone()) {
                Future<String> submitted = DEPENDENCY_EXECUTOR.submit(question);
                if (outstanding.compareAndSet(running, submitted)) {
                    running = submitted;
                } else {
                    // Another probe submitted first. Cancelling one that has not started removes it from the
                    // queue, so two concurrent probes still cost one question.
                    submitted.cancel(true);
                    running = outstanding.get();
                }
            }
            if (running == null) {
                return "";
            }
            try {
                // An answer that arrives from a question submitted during an outage may be seconds old by
                // now, and is cached as though it were current for one cache window. That is deliberate: it
                // is the outage's own answer, it says DOWN, and the probe after it asks again.
                String outcome = running.get(DEPENDENCY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                held = new Answer(System.currentTimeMillis(), outcome);
                if (!answered(outcome)) {
                    reportFailure("Readiness probe reports " + label + " unusable: " + outcome, null,
                            lastLogged);
                }
                return outcome;
            } catch (TimeoutException slow) {
                // Not cached, and not cancelled: the next probe waits on this same question rather than
                // starting another, and the moment it completes a probe reports its answer.
                String reason = label + " did not answer within " + DEPENDENCY_TIMEOUT_MILLIS + " ms";
                reportFailure("Readiness probe gave up waiting for " + reason, null, lastLogged);
                return reason;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return label + " could not be asked: the probe was interrupted";
            } catch (RuntimeException | ExecutionException failed) {
                Throwable cause = failed.getCause() == null ? failed : failed.getCause();
                String reason = label + " could not be asked: " + cause;
                held = new Answer(System.currentTimeMillis(), reason);
                reportFailure("Readiness probe could not ask about " + label, cause, lastLogged);
                return reason;
            }
        }

        /**
         * One dependency answer and the instant it was reached.
         *
         * @param at the epoch millisecond the answer was reached
         * @param outcome null when not configured, the empty string when usable, otherwise the reason
         */
        private record Answer(long at, String outcome) { }
    }
}
