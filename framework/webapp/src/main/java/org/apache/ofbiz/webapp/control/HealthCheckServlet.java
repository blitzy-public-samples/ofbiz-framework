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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import org.apache.ofbiz.entity.condition.EntityCondition;

/**
 * HealthCheckServlet.java - Liveness and readiness probes for load-balancer target-group checks.
 *
 * <p>Two machine-readable paths are served, mapped by a webapp deployment descriptor as
 * {@code /health/live} and {@code /health/ready}; in the webtools webapp they are reachable as
 * {@code /webtools/health/live} and {@code /webtools/health/ready}.
 *
 * <p>Status-code contract:
 *
 * <ul>
 * <li>{@code /health/live} - {@code 200 OK} once the servlet container is up. No delegator lookup, no
 *     database access and no session access, so it still answers while the datasource is
 *     unavailable. A load balancer uses it to decide whether an instance has to be replaced.</li>
 * <li>{@code /health/ready} - {@code 200 OK} when every readiness dimension is satisfied, otherwise
 *     {@code 503 SERVICE_UNAVAILABLE}. A load balancer uses it to decide whether to route traffic to
 *     an instance.</li>
 * <li>Any other path - {@code 404 NOT_FOUND}, so a mis-configured probe fails visibly instead of
 *     reporting false health.</li>
 * <li>Any method other than {@code GET} or {@code HEAD} - {@code 405 METHOD_NOT_ALLOWED} with an
 *     {@code Allow} header.</li>
 * <li>Any request carrying an entity body - {@code 400 BAD_REQUEST}, decided from the headers alone,
 *     so this class reads no part of the body.</li>
 * </ul>
 *
 * <p>Readiness measures exactly one dimension - the one the Agent Action Plan specifies for this
 * endpoint, delegator and datasource connectivity:
 *
 * <ul>
 * <li><em>Datasource.</em> The {@code SequenceValueItem} count must COMPLETE. Its result is not
 *     examined: completing the statement is what proves the datasource is reachable, the pool can
 *     hand out a connection, the credentials are accepted and the table is present and readable, and
 *     a count of zero proves all of that just as well as any other value - a freshly initialised
 *     database legitimately holds no sequence row until the first identifier is allocated. This is
 *     the query of the {@code ping} service of {@code org.apache.ofbiz.common.CommonServices} but
 *     deliberately not its rule, because {@code ping} reports
 *     {@code CommonPingDatasourceInvalidCount} for a zero count and applying that here would hold a
 *     correctly provisioned fleet out of service. Only a lookup or query that FAILS is not-ready;
 *     see {@link #runReadinessCheck}.</li>
 * </ul>
 *
 * <p><strong>Nothing else is measured, deliberately.</strong> Readiness is the delegator and its
 * datasource, and nothing about the entity-cache invalidation transport takes part in it. Observing
 * that transport would mean reading {@code AbstractJmsListener.isConnected}, a plain non-volatile
 * field whose writes carry no publication guarantee this class could establish from the read side - so
 * a probe could report a disconnected instance as ready indefinitely, which is a worse answer than not
 * asking the question. Making that field visible would mean changing the service engine's JMS
 * internals, which are frozen. Cache coherence is instead configured and verified where it belongs: the
 * container entry point refuses to start an instance whose delegator has distributed cache clear
 * enabled without a transport configured for it, so the state this probe would have reported cannot be
 * reached by a running instance in the first place.
 *
 * <p>Exactly one readiness check runs at a time and its verdict is shared: an overlapping probe is
 * answered from that check or from the most recent verdict rather than by starting a second one. A
 * probe's wait for a running check is bounded, so it does not occupy a container request thread for
 * as long as a connection borrow or a socket read might take; a probe that exceeds the bound, or that
 * arrives with the waiter slots full, is answered {@code 503} under its own event code.
 *
 * <p>Every readiness dependency failure is converted inside the check's single protected block into
 * the same fixed {@code 503} document, which is what keeps a dependency failure from reaching the
 * container's error-page machinery on these unauthenticated paths. Each response body is a small
 * fixed JSON document ({@code application/json}, UTF-8) with no variable part, marked non-cacheable
 * so that an intermediary does not serve a stale verdict, and names no dimension: a probe client acts
 * on the status code, and which dimension failed belongs in the log. For the same reason no
 * throwable, stack, SQL, connection string or row count is passed to {@link Debug} - what is logged
 * is a stable event code and the number of occurrences suppressed since the previous line for that
 * code.
 *
 * <h2>How the probes are reached anonymously</h2>
 *
 * <p>This class is registered ONCE, as a servlet mapped to the two exact probe paths, and the two paths
 * are added to {@code ControlFilter}'s {@code allowedPaths} so that the filter admits them without a
 * login - the same two-edit registration the webtools descriptor already uses for {@code /ping.txt}.
 * That mapping is the contract: it is what makes the container resolve those two paths to this component
 * rather than to its own default servlet, and it keeps the probes off {@code /control/*} and therefore
 * outside the OFBTOOLS and WEBTOOLS base permissions. This class performs no login, no permission check
 * and no service-engine invocation.
 *
 * <p><strong>Why the delegator is resolved here rather than read from the context.</strong>
 * {@code ContextFilter}, when {@code general.properties} enables multitenant mode, selects a tenant from
 * the {@code Host} header or from a {@code userTenantId} request parameter and then replaces the
 * {@code ServletContext} delegator, security and dispatcher attributes with that tenant's - one request
 * mutating state every later request in the webapp inherits. A readiness verdict read from those
 * attributes would therefore describe whichever tenancy asked last rather than this instance. Resolving
 * the delegator from the webapp's declared {@code entityDelegatorName} context-param removes that
 * misreport: a context-param is deployment-descriptor configuration and no request can change it.
 *
 * <p>A spelling under {@code /health/} that is not exactly one of the two probe paths is answered
 * {@code 404} by this servlet. {@link #isProbePath} is the single definition of what a probe path is, and
 * it is the ROUTE: the {@code servlet-mapping} is deliberately the {@code /health/} prefix so that this
 * class - rather than the container's default servlet - is the one that answers a near miss, because only
 * this class discards the session the filter chain minted for it. See point 3 below.
 *
 * <p><strong>A probe runs the webapp's ordinary filter chain, exactly as {@code /ping.txt} does.</strong>
 * That is the registration the Agent Action Plan prescribes - a {@code servlet}/{@code servlet-mapping}
 * pair plus an {@code allowedPaths} entry - and it leaves the webapp's filter chain and its ordering
 * completely unchanged. Three consequences follow, and each is handled here rather than by adding a
 * filter of this component's own:
 *
 * <ol>
 * <li><strong>A session may be minted by the chain.</strong> {@code ControlFilter} and
 * {@code ContextFilter} both call {@code getSession()} unconditionally, {@code ControlFilter} before it
 * consults its allow-list, which is equally true of every anonymous path the descriptor already admits.
 * {@link #discardAnySessionMintedForThisProbe} therefore invalidates a session this request created and
 * suppresses its cookie, so a probe leaves no session behind however this servlet is wired up. It runs
 * from {@link #service} as the FIRST thing this class does, before the method gate and before the route,
 * so it covers a refused method and a near-miss path as well as a probe - each of which arrives with a
 * session already created.</li>
 * <li><strong>The chain may parse a request body before this class refuses one.</strong>
 * {@code ControlFilter} calls {@code UtilHttp.getParameterMap} and {@code ContextFilter} calls
 * {@code WebAppUtil.setAttributesFromRequestBody}. The header-only {@code 400} below is still this
 * class's own decision and reads nothing itself; bounding a probe body at the load balancer and at the
 * connector remains worthwhile, as the container documentation says.</li>
 * <li><strong>The {@code allowedPaths} entries are the two EXACT probe paths</strong> -
 * {@code /health/live} and {@code /health/ready} - and never the {@code /health} prefix.
 * {@code allowedPaths} is matched with {@code startsWith}, so a single {@code /health} entry would admit
 * every {@code /health*} spelling to the chain anonymously. With the exact entries, the only near misses
 * admitted are LONGER SPELLINGS of those two paths - {@code /health/live-x},
 * {@code /health/ready/anything} - and those are exactly what the {@code /health/} prefix mapping brings
 * here. Letting the container's default servlet answer them {@code 404} instead left the session
 * {@code ControlFilter} had already created in place until it expired on its own, so any client that
 * could reach the webapp could mint unbounded sessions through {@code /health/live-<nonce>}
 * (CWE-400). Answering them here discards that session and still replies {@code 404}: nothing is
 * reachable that was not reachable before, and nothing accumulates behind it.</li>
 * </ol>
 *
 * <p>Every field is a private constant or a thread-safe counter, so the single instance the container
 * creates is safe to serve concurrently, and the class is inert until a webapp deployment descriptor
 * maps it - no thread, no delegator and no datasource access happens before the first probe is
 * served.
 *
 * <p>One optional JVM system property is honoured, read once at class initialisation and validated
 * against a range: {@code ofbiz.health.readiness.deadline.millis} sets how long a probe waits for the
 * readiness check it started, defaulting to 2000. It is read from the system properties rather than
 * from a component configuration file so that a probe never performs a property-cache read or a
 * delegator lookup - least of all while an unreachable datasource is the condition being reported.
 */
@SuppressWarnings("serial")
public class HealthCheckServlet extends HttpServlet {

    private static final String MODULE = HealthCheckServlet.class.getName();

    // The two probe paths, matched EXACTLY, so no near miss - /healthz, /health, /health/live/,
    // /health/liveness, /health/LIVE - is ever answered as probe health.
    private static final String PROBE_LIVE = "/health/live";
    private static final String PROBE_READY = "/health/ready";

    // Methods this class answers, compared case-sensitively as HTTP defines method tokens, so a
    // lower-case "get" is a different method and is refused. HEAD takes the GET path and produces the
    // same status, headers and document; the container installs a void output filter for HEAD, so the
    // body is discarded on the way out rather than being suppressed here.
    //
    // Everything else is refused with 405 by the service() gate below, before HttpServlet's own
    // dispatch is reached. That placement is deliberate: left to HttpServlet, TRACE would echo the
    // request headers back to an anonymous caller, OPTIONS would advertise the introspected method
    // set, and an unrecognised method such as PATCH would be answered 501 through sendError and its
    // error-page machinery. TRACE is also refused a layer earlier by the connector, whose allowTrace
    // defaults to false, so the gate is defence in depth for a connector configured to pass it on.
    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "HEAD");
    private static final String ALLOW_HEADER = "Allow";
    private static final String ALLOW_VALUE = "GET, HEAD";

    // Headers that announce an entity body. Both are inspected instead of the body itself, so a
    // request that carries one is rejected without a single byte being read: the container then
    // discards up to its own maxSwallowSize and closes the connection.
    private static final String TRANSFER_ENCODING_HEADER = "Transfer-Encoding";
    private static final String CHUNKED_ENCODING = "chunked";

    // Framework-tier entity of the default "org.apache.ofbiz" group, declared by the entity component
    // itself (framework/entity/entitydef/entitymodel.xml) rather than by any application component. It
    // is therefore present in every deployment regardless of which application components are loaded,
    // which is why the readiness probe uses it to decide WHICH datasource to prove reachable.
    //
    // It is used for that and for nothing else: the probe no longer reads or counts any row of it. See
    // runReadinessCheck for why counting rows was both the wrong question and an unbounded one.
    private static final String READINESS_ENTITY = "SequenceValueItem";

    // The webapp's declared delegator name, read from its own context-param.
    //
    // NOT the ServletContext "delegator" ATTRIBUTE, which is what this class used to read. That
    // attribute is mutable: ContextFilter replaces all three of delegator, security and dispatcher on
    // the ServletContext whenever a multitenant request selects a tenant from the Host header or from a
    // userTenantId parameter, so an object read from it belongs to whichever tenancy asked last and an
    // anonymous caller could steer a probe at a tenant database of its choosing. A context-param is
    // deployment-descriptor configuration and no request can change it, so resolving from the declared
    // NAME binds every probe to the same base delegator - this instance's own datasource - for the life
    // of the deployment. See baseDelegator.
    private static final String DELEGATOR_NAME_PARAMETER = "entityDelegatorName";

    // The predicate that makes the connectivity query bounded. It is a raw where clause rather than a
    // field comparison because it must not name a column: the entity's own definition is free to change,
    // and a readiness probe that broke when a field was renamed would take a whole fleet out of its
    // target group over a schema detail it has no interest in. "1=0" is the same in every SQL dialect
    // OFBiz supports, needs no index and no parameter, and every database answers it in constant time
    // whatever the table holds - which is the entire point, since the question is whether the datasource
    // answers, not how much data it has.
    private static final EntityCondition READINESS_BOUND = EntityCondition.makeConditionWhere("1=0");

    // Fixed response bodies. Hand-built literals only: no JSON library is pulled in, and no
    // internal detail can ever leak into a body that has no variable part. Every rejection - an
    // unknown path, a refused method, a refused body - shares BODY_UNKNOWN, because a probe client
    // acts on the status code and telling an anonymous caller which rule it broke serves no purpose.
    //
    // The not-ready document carries the status and nothing else, for the same reason. A 503 is raised
    // by the datasource dimension AND by the probe's own shedding and deadline paths, which say nothing
    // about the datasource at all, so a body that named the datasource would be an outright false claim
    // on some of them. It is spelled separately from BODY_UNKNOWN even though the two documents are
    // identical, because they answer different questions: this one reports a measured verdict, that one
    // reports a refused request, and neither may start tracking the other's wording by accident.
    private static final String BODY_LIVE_UP = "{\"status\":\"UP\"}";
    private static final String BODY_UNKNOWN = "{\"status\":\"DOWN\"}";
    private static final String BODY_READY_UP = "{\"status\":\"UP\",\"database\":\"UP\"}";
    private static final String BODY_READY_DOWN = "{\"status\":\"DOWN\"}";

    /** The response header a container emits a session cookie through. */
    private static final String SET_COOKIE_HEADER = "Set-Cookie";

    /**
     * The session cookie name the servlet specification defines, used when the container does not
     * report a configured one.
     */
    private static final String DEFAULT_SESSION_COOKIE_NAME = "JSESSIONID";

    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String CHARACTER_ENCODING = "UTF-8";
    private static final String CACHE_CONTROL_HEADER = "Cache-Control";
    private static final String CACHE_CONTROL_VALUE = "no-cache, no-store, must-revalidate";

    // Safe response headers. The probes are mapped outside /control/*, so a probe response never
    // reaches RequestHandler, where OFBiz normally applies
    // UtilHttp.setResponseBrowserDefaultSecurityHeaders.
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

    // Stable event codes, one per readiness cause, so a log consumer keys its alert off the token
    // rather than off wording, an exception message or any other internal detail. Every one of them
    // accompanies a 503; they exist to tell the causes apart, not to grade them.
    private static final String EVENT_READINESS_UNAVAILABLE = "HEALTH-READINESS-DATASOURCE-UNAVAILABLE";
    private static final String EVENT_READINESS_SHED = "HEALTH-READINESS-PROBE-SHED";
    private static final String EVENT_READINESS_WAITERS_FULL = "HEALTH-READINESS-WAITERS-FULL";
    // A check that did not finish inside its own deadline. Kept apart from the three above because it
    // is the only one that says nothing at all about the datasource's answer - it says the answer did
    // not arrive in time - and because the operator action it calls for is to find what is making the
    // measurement slow, typically an exhausted connection pool or a stalled network path, rather than
    // to restore a datasource that has reported a failure.
    private static final String EVENT_READINESS_CHECK_TIMEOUT = "HEALTH-READINESS-CHECK-TIMEOUT";

    // Rate limit for those events. An outage makes every probe of every load-balancer target fail at
    // the polling interval, so one line per minute per JVM is emitted per code, and the occurrences
    // suppressed in between are counted onto the next line for that code - or, if the condition stops
    // occurring, flushed by the next readiness probe once the window has elapsed (see
    // flushSuppressedEvents), so the tail of a burst is quantified rather than lost.
    //
    // The interval is shared, but each code owns its own window and its own suppressed count, so no
    // code reports another's occurrences as its own.
    //
    // Every window below holds a System.nanoTime() reading rather than an epoch millisecond, because
    // it is only ever used to measure an ELAPSED interval and a wall-clock step would either silence
    // these events for as long as the step or defeat the rate limit entirely. A nanoTime origin is
    // arbitrary and may be negative, so an "unclaimed" window cannot be spelled as zero; it is seeded
    // a whole interval earlier instead - see unclaimedWindow.
    private static final long READINESS_LOG_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1L);
    private static final AtomicLong READINESS_LOG_LAST_AT = new AtomicLong(unclaimedWindow());
    private static final AtomicLong READINESS_LOG_SUPPRESSED = new AtomicLong(0L);
    private static final AtomicLong READINESS_SHED_LOG_LAST_AT = new AtomicLong(unclaimedWindow());
    private static final AtomicLong READINESS_SHED_LOG_SUPPRESSED = new AtomicLong(0L);
    private static final AtomicLong READINESS_WAITERS_LOG_LAST_AT = new AtomicLong(unclaimedWindow());
    private static final AtomicLong READINESS_WAITERS_LOG_SUPPRESSED = new AtomicLong(0L);
    private static final AtomicLong READINESS_TIMEOUT_LOG_LAST_AT = new AtomicLong(unclaimedWindow());
    private static final AtomicLong READINESS_TIMEOUT_LOG_SUPPRESSED = new AtomicLong(0L);

    // Window that throttles how often a probe may ask the delegator factory for a delegator, using
    // the same interval as the log above. It is reopened immediately by a resolution that succeeds, so
    // it holds back nothing but a failing one - see baseDelegator.
    private static final AtomicLong DELEGATOR_LOOKUP_LAST_AT = new AtomicLong(unclaimedWindow());

    // The most recent readiness verdict and the permit that lets exactly one probe establish it.
    //
    // A readiness query is not quick in the case that matters: it borrows a pooled connection, and an
    // exhausted pool blocks the borrower for the datasource's pool-sleeptime, which
    // DBCPConnectionFactory passes to setMaxWaitMillis and which the engine's own default leaves at
    // five minutes. Readiness is polled continuously by every load-balancer target, and probes arrive
    // whether or not the previous one answered, so a query per probe would park one container thread
    // per probe for that stretch. The verdict is therefore shared: at most one check runs at a time,
    // and every other probe is answered from that check or from the most recent verdict rather than
    // being refused and answered not-ready.
    //
    // READINESS_WAITERS bounds one thing only - how many probes may WAIT for a running check. Sharing
    // the verdict coalesces the database work to one count, but on its own it does not bound how many
    // container threads probes with no verdict to stand on may occupy inside that wait. A probe over
    // the bound re-reads the shared verdict without blocking and answers with it when one exists; only
    // a probe that finds no verdict at all is answered not-ready, under its own event code. Readiness
    // therefore holds at most READINESS_MAX_WAITERS threads for at most READINESS_CHECK_WAIT_NANOS
    // each however hard the endpoint is flooded, and every probe beyond that is answered in constant
    // time with no wait, no permit and no datasource access.
    private static final AtomicLong READINESS_WAITERS = new AtomicLong(0L);

    // How many probes may wait for a running check at the same time. The bound is visible only to a
    // probe that arrives with NO verdict - a JVM's very first probes, and the brief interval between a
    // verdict ageing out and the next check publishing - because every other probe is answered from
    // the shared verdict. It has to stay above the largest burst that can legitimately coincide with
    // that state, or a cold start under normal polling would answer a healthy instance not-ready: a
    // multi-zone target group polled by several health-check nodes per zone, plus a container
    // orchestrator's own liveness and readiness probes and an operator's curl, is of the order of ten
    // simultaneous probes, so thirty-two leaves roughly threefold headroom. Against Tomcat's default
    // maxThreads of 200, thirty-two waiters holding a thread for at most READINESS_CHECK_WAIT_NANOS is
    // a bounded fraction of the pool whatever rate probes arrive at.
    private static final int READINESS_MAX_WAITERS = 32;

    // READINESS_CHECK_RUNNING is the one-permit gate: the probe that wins it has the count issued, the
    // others do not touch the datasource at all, and the verdict it publishes answers them. The permit
    // is released by the CHECK, in its own finally, and never by the probe that started it - a probe
    // that stops waiting for a slow check must not thereby licence the next probe to start a second
    // concurrent count against a datasource that is evidently already struggling. The check owns the
    // permit for as long as it runs and the abandoning probe answers from the fallbacks instead - see
    // runBoundedReadinessCheck.
    //
    // READINESS_VERDICT holds the verdict and the reading it was established at as ONE immutable value
    // behind ONE atomic reference, so the two cannot be read torn and no lock is needed. A null
    // reference means no verdict has been established yet. It is a reference rather than a packed word
    // because the instant is a System.nanoTime() reading, whose origin is arbitrary and may be
    // negative, so neither the sign nor zero is available to carry anything else.
    private static final AtomicReference<Verdict> READINESS_VERDICT = new AtomicReference<>(null);
    private static final AtomicLong READINESS_CHECK_RUNNING = new AtomicLong(0L);

    // How long a verdict answers a probe on its own, and how long it still answers one that could not
    // run a check of its own.
    //
    // FRESH is one second: a probe that finds a verdict younger than this answers from it, so probes
    // that overlap collapse onto one count rather than each issuing their own.
    //
    // GRACE is five seconds: past FRESH, a verdict still answers a probe that arrived while another
    // probe's check was already running, which bounds how long a not-yet-refuted verdict may be
    // repeated. A check that fails publishes its own verdict within milliseconds, so GRACE is reached
    // only when a check is unusually slow or blocked on a borrow, and readiness turns not-ready once
    // GRACE past the last established verdict has elapsed.
    private static final long READINESS_VERDICT_FRESH_NANOS = TimeUnit.SECONDS.toNanos(1L);
    private static final long READINESS_VERDICT_GRACE_NANOS = TimeUnit.SECONDS.toNanos(5L);

    // How long a probe waits for a check that is already running, and how often it looks. A probe with
    // no verdict to fall back on - a JVM's first probes, and any probe arriving after GRACE has
    // elapsed - waits for the running check rather than starting one of its own. The wait is bounded
    // and the waiting probe holds no pooled connection and issues no query.
    private static final long READINESS_CHECK_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(500L);
    private static final long READINESS_CHECK_POLL_MILLIS = 5L;

    // How long the probe that won the permit waits for the count itself. Everything above bounds how
    // long a probe waits for SOMEBODY ELSE's check; this bounds the check, which is the part that can
    // block: it borrows a pooled connection, and an exhausted pool parks the borrower for the
    // datasource's configured borrow wait, while the socket underneath it carries only whatever
    // deadlines the JDBC URI does. Left unbounded, the winning probe holds a container request thread
    // for that entire stretch, so the load balancer's own probe timeout expires first and readiness is
    // decided by the balancer giving up rather than by anything this instance reported. The
    // managed-datasource definitions shorten the borrow wait and add explicit socket and connect
    // deadlines, but a probe applies its own deadline rather than depending on datasource
    // configuration to stay bounded.
    //
    // The count runs on a small dedicated executor and the probe waits on the Future for at most this
    // long. The count is NOT interrupted when the wait expires: interrupting a thread inside a JDBC
    // borrow or a socket read rarely stops it and can leave a pooled connection in an indeterminate
    // state, and the reading it eventually produces is a real measurement worth publishing. It keeps
    // the permit until it finishes, so nothing starts a second count, and the probe that stopped
    // waiting answers from the fallbacks instead.
    //
    // Two seconds is shorter than the probe timeouts load balancers are usually configured with, so
    // the deadline is this instance's rather than the balancer's; it is two orders of magnitude below
    // the borrow wait it guards against and far longer than a healthy count needs.
    private static final long READINESS_CHECK_DEADLINE_DEFAULT_MILLIS = 2000L;
    // The narrowest and widest deadline accepted from configuration. Below the floor a healthy count
    // on a loaded instance would be abandoned routinely, turning the fallbacks into the normal path.
    // The ceiling is what keeps a configured value health-appropriate: ten seconds is still two orders
    // of magnitude below the borrow wait this deadline guards against and far above any healthy count,
    // while a value beyond it would let a probe hold a container request thread long enough to be the
    // capacity problem the bound exists to prevent.
    private static final long READINESS_CHECK_DEADLINE_MIN_MILLIS = 100L;
    private static final long READINESS_CHECK_DEADLINE_MAX_MILLIS = 10000L;
    // Read once, at class initialisation, from a JVM system property - deliberately not through
    // UtilProperties or EntityUtilProperties, for the same reason the response headers above are
    // constants: a probe must not perform a property-cache read or a delegator lookup, least of all
    // while an unreachable datasource is the very condition being reported.
    private static final String READINESS_CHECK_DEADLINE_PROPERTY = "ofbiz.health.readiness.deadline.millis";
    private static final long READINESS_CHECK_DEADLINE_MILLIS = configuredCheckDeadlineMillis();

    // Name of the thread the count runs on, fixed so it is identifiable in a thread dump - which is
    // exactly what an operator reaches for when the timeout event starts appearing.
    private static final String CHECK_THREAD_NAME = "OFBiz-health-readiness-check";

    /*
     * The executor a test installs in place of the dedicated check thread - null in every deployment,
     * so a deployed instance always runs on the thread described above. See
     * installCheckExecutorForTesting for why the seam exists at all.
     */
    private static final AtomicReference<ExecutorService> CHECK_EXECUTOR_OVERRIDE = new AtomicReference<>(null);

    /*
     * The base delegator a test installs in place of resolving one from the webapp's declared name - null
     * in every deployment, so a deployed instance always resolves as baseDelegator documents. See
     * installBaseDelegatorForTesting for why the seam exists at all.
     */
    private static final AtomicReference<Delegator> BASE_DELEGATOR_OVERRIDE = new AtomicReference<>(null);

    /*
     * The servlet entry point: the method gate, then HttpServlet's ordinary dispatch to doGet and
     * doHead below. Gating here rather than in each doXxx override makes the refusal uniform for every
     * method, named or not - see methodRefused for why none of HttpServlet's own defaults may be
     * reached.
     */
    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        // FIRST, before the method gate and before any routing, and before anything is written to the
        // response - a committed response cannot have its headers changed.
        //
        // Every request that reaches this class arrives with a session already created for it, because
        // ControlFilter calls getSession() before it consults its allow-list. That is true of a probe, of
        // a refused method and of a near-miss path under the /health/ prefix this servlet is mapped to
        // alike, so the discard belongs here rather than on the one path that serves a probe: putting it
        // after the method gate left 'POST /health/live-x' with a session nothing removed.
        discardAnySessionMintedForThisProbe(request, response);
        if (methodRefused(request, response)) {
            return;
        }
        super.service(request, response);
    }


    /*
     * The method gate, applied once for every method rather than per doXxx override.
     *
     * Everything this endpoint serves is a GET or a HEAD. Refusing every other method here keeps
     * HttpServlet's own defaults out of reach, and they are all unusable for a probe endpoint: doTrace
     * echoes the received request headers straight back to an anonymous caller, doPost, doPut and the
     * rest answer through sendError, which hands the response to the container error-page machinery and
     * would replace the compact JSON document with an HTML page, and a method HttpServlet does not
     * dispatch at all - PATCH and PROPFIND among them - would otherwise be answered 501 by that same
     * machinery rather than 405.
     */
    private static boolean methodRefused(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (ALLOWED_METHODS.contains(request.getMethod())) {
            return false;
        }
        // 405 has to carry Allow per the HTTP specification, and it also tells a mis-configured
        // target group what to switch to.
        response.setHeader(ALLOW_HEADER, ALLOW_VALUE);
        writeResponse(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, BODY_UNKNOWN);
        return true;
    }

    /*
     * Serves a probe. This is the endpoint's only behaviour, and doHead below answers with exactly the
     * same status, headers and document.
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleProbe(request, response, resolveProbePath(request));
    }

    /*
     * Serves a probe for HEAD, deliberately writing the same document doGet writes rather than
     * delegating to HttpServlet's default doHead.
     *
     * The default wraps the response in a body-swallowing decorator and calls doGet only in order to
     * measure Content-Length, which for a probe means running the readiness check purely to size a
     * body nobody receives. Answering directly keeps the status and the headers identical while the
     * container discards the body, which is what the servlet contract requires of a HEAD response and
     * what Tomcat's void output filter does.
     */
    @Override
    protected void doHead(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleProbe(request, response, resolveProbePath(request));
    }

    /*
     * Shared by the GET and the HEAD dispatch, so each enforces one contract from one implementation;
     * static because it needs nothing from the instance.
     *
     * The body guard runs before the routing and does not read the request body: Content-Length and
     * Transfer-Encoding are headers, so an oversized or chunked body is refused for the cost of a
     * header lookup and is then discarded by the container rather than by this JVM's heap. Neither
     * branch touches the session - service above has already discarded any session that was minted for
     * the request - and liveness in addition resolves no delegator and issues no query, so it stays
     * answerable while the datasource is unavailable.
     */
    private static void handleProbe(HttpServletRequest request, HttpServletResponse response, String path) throws IOException {
        if (carriesEntityBody(request)) {
            writeResponse(response, HttpServletResponse.SC_BAD_REQUEST, BODY_UNKNOWN);
            return;
        }
        if (PROBE_LIVE.equals(path)) {
            writeResponse(response, HttpServletResponse.SC_OK, BODY_LIVE_UP);
        } else if (PROBE_READY.equals(path)) {
            if (isInstanceReady(request)) {
                writeResponse(response, HttpServletResponse.SC_OK, BODY_READY_UP);
            } else {
                writeResponse(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, BODY_READY_DOWN);
            }
            flushSuppressedEvents();
        } else {
            // An unmapped path fails visibly instead of reporting a false 200, which would let a
            // load balancer keep a broken instance in service.
            writeResponse(response, HttpServletResponse.SC_NOT_FOUND, BODY_UNKNOWN);
        }
    }

    /*
     * Leaves no session behind, whatever ran in front of this servlet.
     *
     * A probe runs the webapp's ordinary filter chain - that is the registration the Agent Action Plan
     * prescribes, and it leaves the chain and its ordering untouched - so ControlFilter and ContextFilter
     * both reach it, and both call getSession() unconditionally, ControlFilter before it even consults
     * its allow-list. A probe arrives several times a minute per instance from a caller that returns no
     * session and follows no cookie, so a session created for one is never reused: it would be retained
     * until it expired on its own, and it would make the container emit a session cookie that a
     * cookie-sticky load balancer would happily pin traffic with. Neither belongs on a health endpoint,
     * so this endpoint discards the one minted for it rather than adding a filter of its own to the
     * chain - which is what keeps the guarantee independent of how the servlet is wired up.
     *
     * Only a session this request created is discarded - isNew() - so a probe that arrives carrying
     * somebody's session cookie, which a browser or a misdirected client can do, leaves that session
     * untouched. Invalidating a session that was not made for this request would log out its owner.
     *
     * The session cookie is suppressed on the same condition. The Servlet API cannot remove a response
     * header, but setHeader replaces every value of one, so the Set-Cookie values are re-emitted without
     * the session cookie. Done before anything is written, because a committed response cannot have its
     * headers changed.
     */
    private static void discardAnySessionMintedForThisProbe(HttpServletRequest request,
            HttpServletResponse response) {
        try {
            HttpSession session = request.getSession(false);
            if (session == null || !session.isNew()) {
                return;
            }
            String sessionCookieName = sessionCookieName(request);
            session.invalidate();
            suppressSessionCookie(response, sessionCookieName);
        } catch (RuntimeException unavailable) {
            // Absorbed for the reason every other dependency failure here is absorbed: this runs on a
            // deliberately unauthenticated path, and a container that will not answer a question about
            // its own session must not turn a probe into a 500 with an HTML error page. The probe's
            // verdict does not depend on this, so reporting it and carrying on is the correct outcome.
            Debug.logWarning("A health probe could not discard the session created for it: "
                    + unavailable.getClass().getName(), MODULE);
        }
    }

    /*
     * The name the container uses for its session cookie, which is configurable per webapp.
     *
     * Read from the container rather than assumed to be JSESSIONID, because a deployment may rename it
     * in web.xml and a hard-coded name would then suppress nothing. Falls back to the specification's
     * default when the container does not report one.
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

    /*
     * Re-emits the response's Set-Cookie headers without the session cookie.
     *
     * setHeader replaces every existing value of a header name, and addHeader appends, so writing the
     * first kept value with setHeader and the rest with addHeader leaves exactly the kept values. When
     * the session cookie is the only one, there is nothing to keep and the header is replaced with an
     * expiry directive for that same cookie instead: the Servlet API has no removeHeader, and an
     * already-emitted cookie that is left alone would be stored by the client, which is the outcome
     * being prevented. A directive that expires it immediately is what a client can actually act on.
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
     * The container has already normalised and decoded the URI by the time a servlet sees it, and it
     * has stripped any path parameters, so no ../ traversal, %2e escape or ;jsessionid suffix can
     * reach the exact comparison in handleProbe.
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

    /**
     * Reports whether a path within a webapp is one of the two probe paths this class answers.
     *
     * <p>Published so that machinery running <em>before</em> this class - a Tomcat engine valve, which
     * executes ahead of every webapp's filter chain - can recognise a probe without restating the two
     * literals. The paths are part of this class's contract, and a second copy of them elsewhere is a
     * copy that can drift: a valve that exempted a stale spelling would silently resume doing to probes
     * exactly what the exemption was added to prevent. The comparison is the same exact one
     * {@code handleProbe} performs, for the same reason - no {@code /health} prefix is reserved, so no
     * other spelling is treated as a probe.
     *
     * <p>The argument is the path <em>within the webapp</em>, with the context path already removed. A
     * caller that only has a whole request URI has to strip the context path itself, because the same
     * two paths are probes in whichever context the health webapp is mounted at.
     *
     * @param pathWithinWebapp the requested path relative to the webapp's context path; may be null
     * @return {@code true} if the path is exactly the liveness or the readiness probe path
     */
    public static boolean isProbePath(String pathWithinWebapp) {
        return PROBE_LIVE.equals(pathWithinWebapp) || PROBE_READY.equals(pathWithinWebapp);
    }

    /*
     * Answers whether this instance is ready to receive traffic - every readiness dimension it owes
     * the fleet satisfied - from a verdict that exactly one probe at a time establishes. See
     * runReadinessCheck for what is measured and READINESS_VERDICT for why the verdict is shared
     * rather than measured per probe.
     *
     * The order below is: a verdict younger than READINESS_VERDICT_FRESH_NANOS, then the one permitted
     * check, then a verdict still within READINESS_VERDICT_GRACE_NANOS, then a bounded wait for the
     * running check, then a constant-time answer once the waiter bound is reached. Every path is
     * bounded - the verdict paths touch nothing, the wait by READINESS_CHECK_WAIT_NANOS, the check by
     * READINESS_CHECK_DEADLINE_MILLIS - so no path parks a container request thread on a connection
     * borrow or a socket read.
     *
     * Three of those answers are not backed by a completed check: a check that outran its deadline with
     * no verdict standing, a wait that ended without one being published, and a probe that found no
     * verdict once the waiter bound was reached. Each is reported under its own event code, so an
     * operator can tell them apart from a datasource that reported a failure, and from one another.
     *
     * A verdict is at most READINESS_VERDICT_FRESH_NANOS old on a path that has a check available and
     * at most READINESS_VERDICT_GRACE_NANOS old on one that does not, so a dependency that fails is
     * reported not-ready on the next check and one that recovers is reported ready again as quickly,
     * with no restart: an instance leaves this state by itself rather than needing an operator to reset
     * a latch.
     */
    private static boolean isInstanceReady(HttpServletRequest request) {
        Boolean fresh = establishedVerdict(READINESS_VERDICT_FRESH_NANOS);
        if (fresh != null) {
            return fresh;
        }
        // One probe - and only one - has the check run and publishes what it found. The context is read
        // HERE, on the request thread, because the check runs on another thread and a request object
        // must not be touched from one; the ServletContext is the long-lived, thread-safe object the
        // check actually needs.
        if (READINESS_CHECK_RUNNING.compareAndSet(0L, 1L)) {
            ServletContext context = probeContext(request);
            if (context == null) {
                // No context, so there is no check to run and none to release the permit later; it is
                // released here, and the condition is reported as the datasource being unavailable,
                // which is what it amounts to from a load balancer's point of view.
                READINESS_CHECK_RUNNING.set(0L);
                logRateLimitedWarning(EVENT_READINESS_UNAVAILABLE, READINESS_LOG_LAST_AT, READINESS_LOG_SUPPRESSED);
                return false;
            }
            Boolean measured = runBoundedReadinessCheck(context);
            if (measured != null) {
                return measured;
            }
            // The check outran its deadline. It keeps running, keeps the permit and will publish the
            // reading it eventually gets; this probe answers from a verdict that has not been refuted
            // yet, and reports the timeout under its own code when there is none.
            Boolean standing = establishedVerdict(READINESS_VERDICT_GRACE_NANOS);
            if (standing != null) {
                return standing;
            }
            logRateLimitedWarning(EVENT_READINESS_CHECK_TIMEOUT, READINESS_TIMEOUT_LOG_LAST_AT,
                    READINESS_TIMEOUT_LOG_SUPPRESSED);
            return false;
        }
        // A check is already running: repeat a verdict it has not refuted yet rather than inventing a
        // failure the datasource has not reported.
        Boolean established = establishedVerdict(READINESS_VERDICT_GRACE_NANOS);
        if (established != null) {
            return established;
        }
        // Nothing to stand on - the running check is the only answer there is, so wait for it as one of
        // a bounded set of waiters. The slot is released in a finally so that a thread the container
        // interrupts, or an exception unwinding through here, cannot leak one and shrink the set for
        // the life of the JVM.
        if (claimWaiterSlot()) {
            try {
                return awaitRunningCheck();
            } finally {
                READINESS_WAITERS.decrementAndGet();
            }
        }
        // The waiter set is full, so this probe is answered in constant time instead of occupying a
        // container thread. See READINESS_WAITERS for why the bound exists and why a probe over it is
        // still answered from the shared verdict whenever one exists.
        return answerWithoutWaiting();
    }

    /*
     * Claims one of the READINESS_MAX_WAITERS waiter slots, reporting whether it was won.
     *
     * A compare-and-set loop rather than an unconditional incrementAndGet followed by a test: an
     * increment-then-check would let the counter climb without limit under a flood, and a burst that
     * arrived while several threads were between their increment and their decrement could then read a
     * value far above the bound and refuse waiters that should have been admitted. Retrying the
     * compare-and-set keeps the counter itself bounded by READINESS_MAX_WAITERS at every instant, which
     * is what makes the bound a property of the state rather than of the arrival pattern.
     *
     * The loop cannot spin indefinitely: every iteration either observes the set full and returns, or
     * loses a race to another thread that made progress.
     */
    private static boolean claimWaiterSlot() {
        while (true) {
            long waiting = READINESS_WAITERS.get();
            if (waiting >= READINESS_MAX_WAITERS) {
                return false;
            }
            if (READINESS_WAITERS.compareAndSet(waiting, waiting + 1L)) {
                return true;
            }
        }
    }

    /*
     * Answers a probe that arrived once the waiter set was already full, without waiting.
     *
     * The shared verdict is re-read first, and over the grace window rather than the fresh one, for two
     * reasons. It is genuinely a new read: the running check may have published in the interval between
     * the grace-window read in isInstanceReady and this point, and a verdict that exists is always a
     * better answer than one this method would have to invent. And answering from it is what keeps this
     * path from behaving like an admission counter, which the shared verdict exists to avoid - a flood
     * arriving at a healthy instance is answered with that instance's real, recent verdict.
     *
     * Only a probe that finds no verdict at all is answered not-ready, and that is the honest answer
     * rather than a fabricated one: no verdict means no readiness check has completed in this JVM
     * within the grace window, so nothing has yet established that the instance can serve. It is
     * reported under its own event code, because the operator action it calls for - look at what is
     * sending this many simultaneous probes, and at why the check has not completed - is different from
     * both a datasource failure and a slow check that a waiter timed out on.
     */
    private static boolean answerWithoutWaiting() {
        Boolean established = establishedVerdict(READINESS_VERDICT_GRACE_NANOS);
        if (established != null) {
            return established;
        }
        logRateLimitedWarning(EVENT_READINESS_WAITERS_FULL, READINESS_WAITERS_LOG_LAST_AT,
                READINESS_WAITERS_LOG_SUPPRESSED);
        return false;
    }

    /*
     * The ServletContext the check needs, read on the request thread, and null when the container
     * cannot supply one.
     *
     * It has to be read here rather than inside the check, because the check runs on another thread
     * and a request object must not be touched from one - the ServletContext is the long-lived,
     * thread-safe object it actually needs. Moving that read out of the check would move it out of the
     * check's fail-closed boundary as well, which is what this method restores: a container that
     * cannot supply a context is absorbed into a 503 verdict here instead of escaping to the container
     * as a 500 with an HTML error page on what is deliberately an unauthenticated path.
     */
    private static ServletContext probeContext(HttpServletRequest request) {
        try {
            return request.getServletContext();
        } catch (RuntimeException unavailable) {
            // Not logged or inspected here: the caller reports it under the datasource event code, and
            // for the same reason the check gives - a container message is not something an anonymous
            // caller may put into the log.
            return null;
        }
    }

    /*
     * Has the readiness check run, and waits at most READINESS_CHECK_DEADLINE_MILLIS for its verdict.
     *
     * Called only by the probe holding the READINESS_CHECK_RUNNING permit. Returns the verdict when
     * the check produced one in time, and null when it did not - "still running", which is not a
     * verdict and is why the return type is a boxed Boolean.
     *
     * The submitted task releases the permit in its own finally, so the permit tracks the CHECK rather
     * than the probe that started it: when this method returns null the count is still in flight, and
     * the next probe must not start a second one against a datasource that is already failing to
     * answer the first. The permit is released here only when the task never started - a submission the
     * executor refuses - because then there is no check to release it later, and leaving it held would
     * wedge readiness on the fallbacks for the life of the JVM.
     *
     * The task is not cancelled when the wait expires. cancel(true) would interrupt a thread that is
     * almost certainly inside a connection borrow or a socket read, where interruption is usually
     * ineffective and can leave a pooled connection unusable, and it would discard a genuine
     * measurement that is about to arrive. The task is left to finish and publish; the executor's
     * single thread means at most one such task is ever outstanding.
     *
     * A refused submission and a task that threw are both answered not-ready under the datasource
     * event code, so a failure of this mechanism does not report a healthy instance.
     *
     * If a count never returns, the permit is never released and no further count is started. Readiness
     * then answers from the grace window until it expires and not-ready afterwards, which is the
     * correct report for an instance whose datasource has stopped answering, and it costs no request
     * thread beyond the bounded wait. The managed-datasource definitions carry an explicit socket
     * deadline so that such a count does eventually return and readiness can recover without a
     * restart.
     */
    private static Boolean runBoundedReadinessCheck(ServletContext context) {
        Future<Boolean> pending;
        try {
            pending = checkExecutor().submit(() -> {
                try {
                    return publishVerdict(runReadinessCheck(context));
                } finally {
                    READINESS_CHECK_RUNNING.set(0L);
                }
            });
        } catch (RuntimeException rejected) {
            // A refused submission arrives as RejectedExecutionException, which is itself a
            // RuntimeException; the broader catch is deliberate, because every way a submission can
            // fail leaves the same problem behind. No task exists to release the permit, so it is
            // released here - leaving it held would wedge readiness on the fallbacks for good.
            READINESS_CHECK_RUNNING.set(0L);
            logRateLimitedWarning(EVENT_READINESS_UNAVAILABLE, READINESS_LOG_LAST_AT, READINESS_LOG_SUPPRESSED);
            return Boolean.FALSE;
        }
        try {
            return pending.get(READINESS_CHECK_DEADLINE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timedOut) {
            // Deliberately not logged here: the caller decides, because a verdict still inside its
            // grace window makes this a non-event rather than something an operator has to see.
            return null;
        } catch (InterruptedException interrupted) {
            // The container is shutting this thread down. Restore the flag it cleared, and treat the
            // check as unfinished - which it is - rather than swallowing the interruption.
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException failed) {
            // runReadinessCheck converts every checked and unchecked failure itself, so reaching here
            // means the task died in a way it does not handle - an Error, or a failure inside
            // publishVerdict. The throwable is not logged, for the same reason it is not logged there:
            // an entity-engine or JDBC message routinely names the connection URI and the failing SQL,
            // and this path is reachable by an anonymous caller.
            logRateLimitedWarning(EVENT_READINESS_UNAVAILABLE, READINESS_LOG_LAST_AT, READINESS_LOG_SUPPRESSED);
            return Boolean.FALSE;
        }
    }

    /*
     * Measures this instance's readiness dimensions and reports what it found. One probe at a time
     * runs it - the one holding the READINESS_CHECK_RUNNING permit - and its verdict is what
     * isInstanceReady then shares. It runs on the dedicated check thread rather than on the request
     * thread, which is what lets the probe put a deadline on it, so everything it needs is passed in
     * as a long-lived, thread-safe object: the ServletContext, never the request.
     *
     * The datasource dimension asks the one question a readiness probe needs answered: does the
     * datasource behind this instance's entity engine answer. It issues one bounded statement against
     * the readiness entity and treats ANY successful execution as connectivity - see datasourceAnswers
     * for the statement and for what each part of it is chosen to prove and to avoid. Nothing about the
     * contents of the database is inspected, and the cost per probe does not grow with the data.
     *
     * There is no second dimension. See the class documentation for why the entity-cache invalidation
     * transport is deliberately not observed here, and where that property is established instead.
     *
     * The check is strictly read-only - no DDL, no writes, no cache mutation and no explicit
     * transaction management - which is what allows a serving instance to run without DDL privileges,
     * and the delegator is resolved per check rather than in init(), so a datasource that only becomes
     * reachable later flips readiness to 200 and one that later fails flips it to 503 with no restart.
     *
     * The method is FAIL-CLOSED: every step, the servlet-context access and the delegator lookup
     * included, runs inside the try below, so every dependency failure becomes the 503 JSON document
     * this contract promises instead of escaping to the container, which would answer 500 with an HTML
     * error page on what is deliberately an unauthenticated path. The context is taken from the
     * request rather than from getServletContext(); that is the same object for a request dispatched
     * into this webapp, and reading it from the request keeps the check static and free of instance
     * state, so the GET and the HEAD dispatch share one implementation.
     */
    private static boolean runReadinessCheck(ServletContext context) {
        try {
            Delegator delegator = baseDelegator(context);
            if (delegator == null) {
                logRateLimitedWarning(EVENT_READINESS_UNAVAILABLE, READINESS_LOG_LAST_AT, READINESS_LOG_SUPPRESSED);
                return false;
            }
            // Throws, or the datasource answered. There is no third outcome and therefore no verdict to
            // read off the result: any successful execution IS connectivity, which is the semantic the
            // whole dimension turns on. Failure lands in the catch below, under one event code.
            datasourceAnswers(delegator);
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
        }
    }

    /*
     * Reports whether the datasource behind this instance's entity engine answers.
     *
     * The query is SELECT COUNT(*) FROM <the readiness entity> WHERE 1=0. Every part of that is
     * deliberate:
     *
     *   - It is BOUNDED. The predicate cannot match, so the database answers in constant time however
     *     many rows the table holds. The check this replaced counted the WHOLE table, which is O(rows)
     *     work on every probe against a table that grows for the life of the deployment - so the cost of
     *     being polled rose with uptime, on a path an unauthenticated caller polls every few seconds.
     *   - ANY SUCCESSFUL EXECUTION IS CONNECTIVITY. The number is not looked at. The check this replaced
     *     required a NON-ZERO count, which made a perfectly healthy database report 503 whenever it
     *     legitimately held no sequence rows - a freshly initialised schema, a seed-only data load, a
     *     deployment whose sequence banks have not been touched yet - which is precisely the moment a
     *     fleet is being brought up and most needs its instances to become ready. Connectivity and data
     *     content are different questions and only the first belongs in a readiness probe.
     *   - IT STILL DETECTS AN UNAPPLIED SCHEMA, because the entity is still in the FROM clause: a
     *     database that has no such relation fails the statement, which is a genuine not-ready state for
     *     an instance that is about to be sent traffic.
     *   - IT NAMES NO COLUMN, so the probe does not acquire an opinion about the entity's fields.
     *
     * It goes through the entity helper that owns the entity's group rather than through EntityQuery.
     * Both end in the same GenericDAO.selectCountByCondition against the same entity, but EntityQuery
     * routes through GenericDelegator.findCountByCondition, which logs the throwable together with its
     * stack trace before rethrowing, marks the transaction for rollback and runs three ECA phases -
     * unbounded logging below this class, outside the rate limit here, on a path an anonymous caller
     * polls. ModelReader.getModelEntity is used in place of Delegator.getModelEntity for the same
     * reason: the latter logs a throwable when the model cannot be read, the former throws it.
     *
     * The check is strictly read-only - no DDL, no writes, no cache mutation and no explicit transaction
     * management - which is what allows a serving instance to run without DDL privileges.
     */
    private static void datasourceAnswers(Delegator delegator) throws GenericEntityException {
        delegator.getEntityHelper(READINESS_ENTITY).findCountByCondition(delegator,
                delegator.getModelReader().getModelEntity(READINESS_ENTITY), READINESS_BOUND, null, null);
    }

    /*
     * Reports the established verdict when it is no older than the given window, and null when there
     * is none that recent - "no answer available", which is not the same as a not-ready answer and is
     * why the return type is a boxed Boolean.
     *
     * The age is a difference of two System.nanoTime() readings, so it measures elapsed time and stays
     * correct across the counter's wraparound. A negative age is unreachable from a monotonic source
     * and is refused anyway, because a verdict of unknowable age must not be honoured.
     */
    private static Boolean establishedVerdict(long windowNanos) {
        Verdict verdict = READINESS_VERDICT.get();
        if (verdict == null) {
            return null;
        }
        long age = System.nanoTime() - verdict.establishedAtNanos();
        if (age < 0L || age > windowNanos) {
            return null;
        }
        return verdict.ready();
    }

    /*
     * Publishes the verdict a check established and passes it back to the caller unchanged.
     *
     * The reading is taken after the check rather than before it, so the window a verdict is honoured
     * for starts when the datasource was actually observed. Verdict and reading are stored as one
     * immutable value behind one atomic reference, so a probe can never observe one without the other.
     */
    private static boolean publishVerdict(boolean ready) {
        READINESS_VERDICT.set(new Verdict(ready, System.nanoTime()));
        return ready;
    }

    /*
     * A readiness verdict together with the monotonic reading at which it was established.
     *
     * Immutable and published through a single AtomicReference, which is what makes the pair
     * indivisible. It is a value of its own rather than two halves packed into one long because a
     * System.nanoTime() reading has an arbitrary origin and may be negative or zero, so neither the
     * sign nor zero is available to carry the verdict.
     */
    private record Verdict(boolean ready, long establishedAtNanos) {
    }

    /*
     * Waits for the check another probe is running and answers with its verdict.
     *
     * Only a probe with nothing to fall back on gets here, and only while holding one of the
     * READINESS_MAX_WAITERS slots its caller claimed. It holds no connection and issues no query - it
     * is waiting on a check already in flight - and it gives up after a wait bounded on the MONOTONIC
     * clock, so readiness occupies at most READINESS_MAX_WAITERS threads for at most
     * READINESS_CHECK_WAIT_NANOS each, whatever rate probes arrive at.
     *
     * Giving up is the one case that answers not-ready without the datasource having said so, and it is
     * reported under its own event code so that it stays distinguishable from a datasource that failed:
     * it means a check was still running and no verdict was recent enough to repeat.
     */
    private static boolean awaitRunningCheck() {
        // The bound is measured on the MONOTONIC clock rather than on the wall clock, and is expressed
        // as "elapsed since start" rather than as an instant to compare against, because that form
        // stays correct across the nanoTime counter's wraparound. This is the only thing that limits
        // how long a probe thread stays in this method, and a wall-clock step would falsify the bound
        // both ways: forwards, the wait would end at once and a cold-start probe would report not-ready
        // without having given the running check its 500 milliseconds; backwards, the bound would
        // recede and the loop would keep polling until the clock caught up, occupying a probe thread
        // for the length of the step and doing so for every probe in flight.
        //
        // The verdict a check publishes is timed on that same clock: Verdict carries a nanoTime reading
        // in establishedAtNanos rather than an epoch millisecond, and establishedVerdict discards a
        // verdict whose computed age comes out negative, so no part of this path depends on the wall
        // clock.
        long startedAt = System.nanoTime();
        while (true) {
            Boolean published = establishedVerdict(READINESS_VERDICT_FRESH_NANOS);
            if (published != null) {
                return published;
            }
            if (System.nanoTime() - startedAt >= READINESS_CHECK_WAIT_NANOS) {
                break;
            }
            try {
                Thread.sleep(READINESS_CHECK_POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                // The container is shutting the thread down; restore the flag it cleared and answer
                // with what is known instead of swallowing the interruption.
                Thread.currentThread().interrupt();
                break;
            }
        }
        Boolean established = establishedVerdict(READINESS_VERDICT_GRACE_NANOS);
        if (established != null) {
            return established;
        }
        logRateLimitedWarning(EVENT_READINESS_SHED, READINESS_SHED_LOG_LAST_AT, READINESS_SHED_LOG_SUPPRESSED);
        return false;
    }

    /*
     * Resolves the delegator the probe reports on: the webapp's BASE delegator, never a tenant's.
     *
     * WHY NOT THE ServletContext ATTRIBUTE, which is what this method used to read. ContextFilter,
     * when general.properties enables multitenant mode, selects a tenant from the Host header or from
     * a userTenantId request parameter and then REPLACES the ServletContext delegator, security and
     * dispatcher attributes with that tenant's - a global mutation performed on behalf of one request.
     * A probe that read those attributes would therefore report on whichever tenancy happened to ask
     * last: an anonymous caller could steer readiness at a tenant database of its choosing, and a
     * readiness verdict would stop meaning "this instance can serve" and start meaning "this instance
     * could reach some tenant's database once". Neither is what a load-balancer target group is
     * deciding with.
     *
     * WHAT IS READ INSTEAD. The webapp's own entityDelegatorName context-param - deployment
     * descriptor configuration, which no request can change - resolved through DelegatorFactory and
     * then reduced to its base name, so even a delegator name that carries a tenant suffix yields the
     * base. The result is the instance's own datasource, identically for every probe, for the life of
     * the deployment.
     *
     * The resolution is throttled to once per rate-limit window when it does not already have an
     * answer, and that is what bounds the logging below this class: DelegatorFactory.getDelegator logs
     * the throwable and its stack when construction fails, and it caches the failed Future, so every
     * later call re-throws and re-logs - an anonymous poller would otherwise amplify one broken
     * datasource definition into an unbounded stream of stack traces. Nothing is lost by throttling,
     * precisely because the cached Future means a failed construction can never succeed later in the
     * same JVM; a resolution that succeeds reopens the window at once, so only a failing one is ever
     * held back. In a running deployment the first call is a cache hit anyway: ContextFilter.init()
     * built this same base delegator when the webapp was deployed.
     */
    private static Delegator baseDelegator(ServletContext context) {
        Delegator installed = BASE_DELEGATOR_OVERRIDE.get();
        if (installed != null) {
            return installed;
        }
        String declared = context.getInitParameter(DELEGATOR_NAME_PARAMETER);
        if (UtilValidate.isEmpty(declared)) {
            return null;
        }
        if (!claimWindow(DELEGATOR_LOOKUP_LAST_AT)) {
            return null;
        }
        Delegator delegator = DelegatorFactory.getDelegator(declared);
        if (delegator == null) {
            return null;
        }
        DELEGATOR_LOOKUP_LAST_AT.set(unclaimedWindow());
        // Reduced to the base even though the declared name is configuration: a deployment is free to
        // write "default#DEMO1" there, and a probe must report on the instance rather than on one
        // tenancy of it. getDelegatorBaseName strips the suffix, and for an untenanted name it returns
        // the name itself, so the ordinary case costs one string comparison.
        String base = delegator.getDelegatorBaseName();
        if (base == null || base.equals(delegator.getDelegatorName())) {
            return delegator;
        }
        Delegator resolved = DelegatorFactory.getDelegator(base);
        return resolved == null ? delegator : resolved;
    }

    /*
     * Emits one warning for the given event code per READINESS_LOG_INTERVAL_NANOS. Occurrences inside
     * that interval are counted instead, and the count is appended to the next line for the same code;
     * occurrences left outstanding after the last line of a burst are written by flushSuppressedEvents.
     *
     * The window and the counter are passed in rather than read from a single pair of fields, so every
     * event code is rate limited independently of the others: one code can neither silence another nor
     * claim its suppressed occurrences. The line carries the stable code and that count and nothing
     * else, at warning level rather than as an error with a stack trace, because a readiness dip during
     * start-up or a rolling deployment is expected and must not read as a fault.
     */
    private static void logRateLimitedWarning(String eventCode, AtomicLong window, AtomicLong suppressed) {
        if (claimWindow(window)) {
            long missed = suppressed.getAndSet(0L);
            Debug.logWarning(missed == 0L ? eventCode : suppressedLine(eventCode, missed), MODULE);
        } else {
            suppressed.incrementAndGet();
        }
    }

    /*
     * Writes out occurrences the rate limit held back, for every event code that has any, once their
     * window has elapsed.
     *
     * Without this the suppressed count is only ever carried by the NEXT occurrence of the same code,
     * so the moment the events stop - which is what recovery looks like - everything suppressed since
     * the last line is lost, and the tail of a burst is exactly the part an operator counts. Readiness
     * is polled continuously, which is what makes calling this from a readiness probe the cheapest place
     * to do it: a later readiness probe flushes the outstanding count once the window has elapsed,
     * whether or not the condition that produced it ever occurs again. The flush is therefore driven by
     * the next probe rather than by a clock - if probing stops, the outstanding count stays unwritten -
     * which is the trade for this class owning no timer and no background thread. The windows and
     * counters it does own are static and JVM-local; it holds no instance state.
     *
     * The rate limit is not weakened. Each code still claims its own window through the same
     * compare-and-set, so at most one line per code per interval is written, and a code with nothing
     * suppressed writes nothing - a healthy probe stays silent, which is what keeps continuous polling
     * out of the log entirely.
     */
    private static void flushSuppressedEvents() {
        flushSuppressed(EVENT_READINESS_UNAVAILABLE, READINESS_LOG_LAST_AT, READINESS_LOG_SUPPRESSED);
        flushSuppressed(EVENT_READINESS_SHED, READINESS_SHED_LOG_LAST_AT, READINESS_SHED_LOG_SUPPRESSED);
        flushSuppressed(EVENT_READINESS_WAITERS_FULL, READINESS_WAITERS_LOG_LAST_AT, READINESS_WAITERS_LOG_SUPPRESSED);
        flushSuppressed(EVENT_READINESS_CHECK_TIMEOUT, READINESS_TIMEOUT_LOG_LAST_AT, READINESS_TIMEOUT_LOG_SUPPRESSED);
    }

    private static void flushSuppressed(String eventCode, AtomicLong window, AtomicLong suppressed) {
        if (suppressed.get() > 0L && claimWindow(window)) {
            long missed = suppressed.getAndSet(0L);
            if (missed > 0L) {
                Debug.logWarning(suppressedLine(eventCode, missed), MODULE);
            }
        }
    }

    private static String suppressedLine(String eventCode, long missed) {
        return eventCode + " (" + missed + " further occurrences suppressed)";
    }

    /*
     * Claims the given rate-limit window for the caller, reporting whether it was won.
     *
     * The compare-and-set is what makes this correct when several probe threads arrive at once:
     * exactly one of them wins the window and the losers are told so, which is why the caller can
     * treat a false result as "another thread is already handling this interval".
     *
     * The readings are monotonic, so the interval this enforces is a real elapsed minute. A wall-clock
     * reading would not be: a backwards step would make every window look freshly claimed and silence
     * these events for the length of the step, and a forwards step would reopen all of them at once -
     * both during exactly the kind of incident that also disturbs a host's clock.
     */
    private static boolean claimWindow(AtomicLong window) {
        long now = System.nanoTime();
        long previous = window.get();
        return now - previous >= READINESS_LOG_INTERVAL_NANOS && window.compareAndSet(previous, now);
    }

    /*
     * The reading that spells "this window has never been claimed", used to seed each window and to
     * reopen one.
     *
     * A System.nanoTime() origin is arbitrary and may be negative, so zero is a perfectly ordinary
     * reading and cannot mean "unset" the way an epoch millisecond of zero could. Seeding a whole
     * interval into the past says the same thing in the only vocabulary a monotonic clock has, and it
     * says it without any special case in claimWindow: the first claim simply finds a full interval
     * elapsed. The subtraction is allowed to wrap - claimWindow compares differences, which stay
     * correct across a wrap.
     */
    private static long unclaimedWindow() {
        return System.nanoTime() - READINESS_LOG_INTERVAL_NANOS;
    }

    /*
     * Resolves the deadline the readiness check is bounded by, once, at class initialisation.
     *
     * A JVM system property is read rather than a component configuration file, because this class
     * must not perform a property-cache read or a delegator lookup on a probe path - see the response
     * headers above for the same reasoning. An absent property leaves the default in place, which is
     * why an unconfigured deployment behaves identically to a configured one that agrees with it.
     *
     * A value that is not a number, or is outside the accepted range, is REFUSED and the default is
     * used, with one warning naming what was rejected. Silently honouring an out-of-range deadline is
     * the failure this guards against in both directions: a value of a few milliseconds abandons every
     * healthy check and makes the fallbacks the normal path, while a value of minutes stops the
     * deadline bounding anything at all, which is the whole reason it exists.
     */
    private static long configuredCheckDeadlineMillis() {
        String configured = System.getProperty(READINESS_CHECK_DEADLINE_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return READINESS_CHECK_DEADLINE_DEFAULT_MILLIS;
        }
        long millis;
        try {
            millis = Long.parseLong(configured.trim());
        } catch (NumberFormatException notANumber) {
            Debug.logWarning("Ignoring [" + READINESS_CHECK_DEADLINE_PROPERTY + "]: not a whole number of"
                    + " milliseconds; using " + READINESS_CHECK_DEADLINE_DEFAULT_MILLIS, MODULE);
            return READINESS_CHECK_DEADLINE_DEFAULT_MILLIS;
        }
        if (millis < READINESS_CHECK_DEADLINE_MIN_MILLIS || millis > READINESS_CHECK_DEADLINE_MAX_MILLIS) {
            Debug.logWarning("Ignoring [" + READINESS_CHECK_DEADLINE_PROPERTY + "]=" + millis + ": outside ["
                    + READINESS_CHECK_DEADLINE_MIN_MILLIS + ".." + READINESS_CHECK_DEADLINE_MAX_MILLIS
                    + "] milliseconds; using " + READINESS_CHECK_DEADLINE_DEFAULT_MILLIS, MODULE);
            return READINESS_CHECK_DEADLINE_DEFAULT_MILLIS;
        }
        return millis;
    }

    /*
     * The executor the readiness check runs on, created on first use.
     *
     * ONE thread, deliberately. The permit already admits one check at a time, so a second thread
     * could only ever hold a queued task, and a pool that could grow would let a stalled datasource
     * accumulate threads - the very failure mode the permit exists to prevent, moved one layer down.
     *
     * A DAEMON thread, so a check still blocked inside a connection borrow can never hold the JVM
     * open at shutdown; the executor is intentionally never shut down, because there is no lifecycle
     * hook this class can hang one on - it is mapped by a deployment descriptor and holds no instance
     * state - and a single daemon thread costs nothing to leave to the JVM's exit.
     *
     * Created LAZILY, through the holder idiom rather than a lock, so that the class stays inert until
     * a readiness probe is actually served: the class comment promises that merely adding this class
     * changes no existing behaviour, and a thread started at class initialisation would break that
     * promise for every deployment that never maps it.
     */
    private static ExecutorService checkExecutor() {
        ExecutorService installed = CHECK_EXECUTOR_OVERRIDE.get();
        return installed != null ? installed : CheckExecutorHolder.INSTANCE;
    }

    /*
     * Installs the executor the readiness check runs on, for a test; null restores the dedicated
     * check thread and is the value every deployment runs with.
     *
     * The check deliberately runs off the request thread, which is precisely what makes it observable
     * only from that thread: the per-thread static seams a probe depends on cannot be replaced on a
     * thread the test did not create, and a deadline cannot be exercised without either sleeping or
     * controlling when the task completes. This seam lets a test drive the production code path
     * unchanged - the permit, the deadline, the verdict, the event codes - while choosing where the
     * task runs, and it lets the timeout and refused-submission branches be reached deterministically
     * rather than by racing a real datasource. It is package-private, so nothing outside this package
     * can reach it, and the field it writes is a final atomic reference, so installing an executor is
     * as thread-safe as reading one.
     */
    static void installCheckExecutorForTesting(ExecutorService executor) {
        CHECK_EXECUTOR_OVERRIDE.set(executor);
    }

    /*
     * Installs the base delegator the readiness check resolves, for a test; null restores the resolution
     * from the webapp's declared entityDelegatorName and is the value every deployment runs with.
     *
     * The same reason as the executor seam above, and it is the same reason stated there: the check runs
     * off the request thread, and the per-thread static seams a test replaces - DelegatorFactory among
     * them - cannot be replaced on a thread the test did not create. The tests that exercise the
     * threading boundary itself, the deadline and the shared verdict therefore have no way to make a
     * stand-in delegator resolvable from the check thread, and the alternative - resolving the delegator
     * on the request thread and handing it to the check - would move a construction that can block onto
     * the thread the whole bounded check exists to keep unblocked.
     *
     * It is package-private, so nothing outside this package can reach it, and the field it writes is a
     * final atomic reference, so installing a delegator is as thread-safe as reading one. It is null in
     * every deployment: baseDelegator consults it first and falls through to the declared name, so a
     * deployed instance resolves exactly as documented.
     */
    static void installBaseDelegatorForTesting(Delegator delegator) {
        BASE_DELEGATOR_OVERRIDE.set(delegator);
    }

    /* Holder for the lazily created check executor - initialised on first access, by the JVM, once. */
    private static final class CheckExecutorHolder {
        private static final ExecutorService INSTANCE = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, CHECK_THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });

        private CheckExecutorHolder() {
        }
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
