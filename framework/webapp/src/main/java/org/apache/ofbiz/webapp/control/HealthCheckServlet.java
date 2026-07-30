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
import java.util.Map;
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
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.jms.GenericMessageListener;
import org.apache.ofbiz.service.jms.JmsListenerFactory;
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
 * <li>{@code /health/ready} - {@code 200 OK} when every readiness dimension this instance owes the
 *     fleet is satisfied, otherwise {@code 503 SERVICE_UNAVAILABLE}. Two dimensions are measured, in
 *     this order:
 *     <ol>
 *     <li><em>Datasource.</em> The {@code SequenceValueItem} count must complete and be non-zero.
 *         {@code 503} therefore covers no delegator being available, the count failing, and the count
 *         coming back zero. The count and both of its failure rules mirror the {@code ping} service of
 *         {@code org.apache.ofbiz.common.CommonServices}, which treats a failed count and a zero count
 *         alike as a datasource failure.</li>
 *     <li><em>Cache coherence.</em> When - and only when - this instance's delegator has distributed
 *         cache clear enabled, the entity-cache invalidation transport must have a connected
 *         subscriber. A delegator with the flag off makes this dimension inert, which is why a
 *         single-node deployment is unaffected by it. See {@link #isCacheTransportReady} for why an
 *         instance without that transport must be taken out of service rather than left serving.</li>
 *     </ol>
 *     A load balancer uses the endpoint to decide whether to route traffic to an instance. Exactly one
 *     readiness check runs at a time and its verdict is shared: overlapping probes are answered from
 *     that check or from the most recent verdict, so probe concurrency never manufactures a
 *     {@code DOWN} answer for a healthy instance - see the verdict cache below. The check itself is
 *     bounded by its own deadline, so no probe can occupy a container request thread for as long as a
 *     connection borrow or a socket read might take.</li>
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
 * {@code GenericEntityException} from the count, an unchecked failure such as an exhausted connection
 * pool, or a cache-invalidation transport with no connected subscriber - is converted inside the
 * check's single protected block into the same fixed {@code 503} document. Nothing propagates out of
 * the probe, so the container can never render an error page or an exception report on these
 * unauthenticated paths. Each response body is a small fixed JSON document
 * ({@code application/json}, UTF-8) with no variable part, marked non-cacheable so that no
 * intermediary can serve a stale verdict. The {@code 503} document names no dimension, because a
 * probe client acts on the status code and which dimension failed is an internal detail that belongs
 * in the log - under its own event code - and not in an answer to an anonymous caller.
 *
 * <p>Because the paths are anonymous and polled continuously, the probe writes no internal detail
 * anywhere: no throwable, no stack, no SQL, no connection string and no row count reach the log.
 * What is logged is a stable event code and the number of occurrences suppressed since the previous
 * line, at most one line per code per minute; a count left outstanding when the events stop is
 * written by the next readiness probe once that minute has elapsed, so nothing is lost.
 *
 * <h2>How the probes are reached anonymously</h2>
 *
 * <p>This is a plain servlet. A deployment descriptor maps it on the two probe paths and adds
 * {@code /health} to the {@code allowedPaths} of {@code ControlFilter}, which is the same mechanism
 * the pre-existing {@code /ping.txt} entry uses; {@code ControlFilter} matches that list with
 * {@code startsWith}, so one entry covers both probes. Without it a probe would be redirected to
 * {@code /control/main} and a load-balancer target group would read the {@code 302} as an unhealthy
 * target. The probes stay off {@code /control/*}, so no base permission applies to them, and this
 * class performs no login, no permission check, no session access and no service-engine invocation
 * of its own.
 *
 * <p>Being a servlet, a probe traverses the webapp's filter chain. Two consequences are worth
 * knowing, and neither is introduced by this class: {@code ControlFilter} and {@code ContextFilter}
 * both call {@code getSession()} unconditionally, so a probe mints an {@code HttpSession} that a
 * load balancer never returns, and {@code ContextFilter} hands the request to
 * {@code WebAppUtil.setAttributesFromRequestBody} before a servlet method is dispatched. This class
 * limits its own exposure to the second: it refuses any request that merely announces a body, from
 * the {@code Content-Length} and {@code Transfer-Encoding} headers, without reading a byte of it.
 *
 * <p>Every field is a private constant or a thread-safe counter, so the single instance the
 * container creates is safe to serve concurrently.
 *
 * <h2>Timekeeping</h2>
 *
 * <p>Every interval this class measures - the verdict's age, the wait for a running check, that
 * check's own deadline and each event's rate-limit window - is measured with {@link System#nanoTime()}
 * and compared as a difference of two readings. Nothing here uses the wall clock, because nothing
 * here needs to know what time it is; it needs to know how much time has passed, and the wall clock
 * does not answer that question reliably. A time daemon stepping the clock, a leap-second smear or a
 * virtual machine resuming from a snapshot moves {@code System.currentTimeMillis()} in either
 * direction, and it does so most readily during the kind of incident readiness exists to report:
 * backwards, every window looks freshly claimed and every verdict looks new, so failures go
 * unreported and a stale verdict is honoured indefinitely; forwards, every bound expires at once.
 *
 * <p>The class is inert until a webapp deployment descriptor maps it - no thread, no delegator and no
 * datasource access happens before the first probe is served. The {@code webapp} component declares
 * no webapp of its own, so simply adding this class changes no existing behaviour.
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

    // The two probe paths, matched EXACTLY. ControlFilter's allowedPaths entry is necessarily a
    // prefix, because that filter matches its list with startsWith, but the exact comparison here is
    // what confines what this class actually answers to these two resources. Any near miss -
    // /healthz, /health, /health/live/, /health/liveness - matches no url-pattern of this servlet, so
    // the container answers it from its own default servlet with 404 and it never reaches a probe
    // handler; the prefix in the allow-list exposes no other resource, since nothing else is mapped
    // under /health.
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

    // Framework-tier entity of the default "org.apache.ofbiz" group, declared by the entity
    // component itself (framework/entity/entitydef/entitymodel.xml) rather than by any application
    // component. It is therefore present in every deployment regardless of which application
    // components are loaded, which is why the readiness probe counts it rather than any application
    // entity.
    private static final String READINESS_ENTITY = "SequenceValueItem";

    // ServletContext attribute the delegator is published under. ContextFilter.init() populates it
    // when the webapp is deployed and WebAppUtil.getDelegator both reads and refreshes it, so
    // reading it first lets a probe observe an already-built delegator instead of asking for one.
    private static final String DELEGATOR_ATTRIBUTE = "delegator";

    // ServletContext attribute the service dispatcher is published under, by the same
    // ContextFilter.init() call - WebAppUtil.getDispatcher sets it. Unlike the delegator attribute
    // there is deliberately NO fallback lookup for this one: WebAppUtil.getDispatcher BUILDS a
    // dispatcher when the attribute is absent, which starts a service engine, and a probe must
    // observe readiness rather than construct the machinery it reports on. An absent attribute is
    // read as "this webapp has not finished coming up", which for a readiness probe is the truth.
    private static final String DISPATCHER_ATTRIBUTE = "dispatcher";

    // Fixed response bodies. Hand-built literals only: no JSON library is pulled in, and no
    // internal detail can ever leak into a body that has no variable part. Every rejection - an
    // unknown path, a refused method, a refused body - shares BODY_UNKNOWN, because a probe client
    // acts on the status code and telling an anonymous caller which rule it broke serves no purpose.
    //
    // The not-ready document carries the status and nothing else, for the same reason. Readiness is
    // measured across more than one dimension - the datasource and, when the delegator requires it,
    // the entity-cache invalidation transport - so a body that named the datasource would be an
    // outright FALSE claim on every 503 raised by the other dimension. It is spelled separately from
    // BODY_UNKNOWN even though the two documents are identical, because they answer different
    // questions: this one reports a measured verdict, that one reports a refused request, and neither
    // may start tracking the other's wording by accident.
    private static final String BODY_LIVE_UP = "{\"status\":\"UP\"}";
    private static final String BODY_UNKNOWN = "{\"status\":\"DOWN\"}";
    private static final String BODY_READY_UP = "{\"status\":\"UP\",\"database\":\"UP\"}";
    private static final String BODY_READY_DOWN = "{\"status\":\"DOWN\"}";

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

    // Stable event codes. Each readiness verdict that is not 200 reports itself as one of these
    // tokens, so a log consumer keys its alert off the token rather than off wording or an exception
    // message. No qualifier is ever appended - not an SQL state, not an exception type, not a driver
    // message - and the only thing that joins a token on the line is the suppressed-occurrence count
    // from the rate limit below. The six causes are kept apart because they call for different
    // operator action - restore the datasource, complete the schema-init execution and its data
    // load, find out what is making the readiness check slow, find out what is sending far more
    // simultaneous probes than a target group does, or restore the cache-invalidation transport.
    private static final String EVENT_READINESS_UNAVAILABLE = "HEALTH-READINESS-DATASOURCE-UNAVAILABLE";
    private static final String EVENT_READINESS_SCHEMA_EMPTY = "HEALTH-READINESS-SCHEMA-EMPTY";
    private static final String EVENT_READINESS_SHED = "HEALTH-READINESS-PROBE-SHED";
    private static final String EVENT_READINESS_WAITERS_FULL = "HEALTH-READINESS-WAITERS-FULL";
    // A check that did not finish inside its own deadline. Kept apart from the three above because it
    // is the only one that says nothing at all about the datasource's answer - it says the answer did
    // not arrive in time - and because the operator action it calls for is to find what is making the
    // measurement slow, typically an exhausted connection pool or a stalled network path, rather than
    // to restore a datasource that has reported a failure.
    private static final String EVENT_READINESS_CHECK_TIMEOUT = "HEALTH-READINESS-CHECK-TIMEOUT";
    // An instance whose delegator requires distributed cache clear but which has no connected
    // subscriber on the invalidation transport. Kept apart from all of the above because it is the one
    // code that says the DATASOURCE is fine: the count completed and was non-zero, and it is the
    // fleet-coherence dependency that is missing. The operator action is correspondingly different -
    // restore the message broker, or supply the transport configuration and the broker client jar the
    // entry point requires when OFBIZ_DISTRIBUTED_CACHE_CLEAR is enabled - and it is emitted while the
    // instance is being held out of service, which is the state it describes.
    private static final String EVENT_READINESS_CACHE_TRANSPORT_UNAVAILABLE = "HEALTH-READINESS-CACHE-TRANSPORT-UNAVAILABLE";

    // Rate limit for those events. An outage makes every probe of every load-balancer target fail at
    // the polling interval, so an unthrottled line per failure turns the readiness endpoint into a
    // log amplifier exactly when the log matters most. One line per minute per JVM is emitted per
    // code, and the occurrences suppressed in between are counted onto the next line for that code -
    // or, if the condition stops occurring, flushed by the next readiness probe once the window has
    // elapsed (see flushSuppressedEvents), so the tail of a burst is quantified rather than lost.
    //
    // The interval is shared, but each code owns its own window and its own suppressed count. That
    // separation is the point of having separate codes: a probe that could not obtain a verdict must
    // not consume the window an unavailable datasource needs - the two coincide precisely, since a
    // datasource that has stopped answering is what makes a check slow enough to be waited on - and no
    // code may report another's occurrences as its own.
    //
    // Every window below holds a System.nanoTime() reading rather than an epoch millisecond, because
    // it is only ever used to measure an ELAPSED interval, and nanoTime is the only clock in the
    // platform guaranteed not to jump: a wall-clock step from a time daemon, a leap-second smear or a
    // virtual machine resuming from a snapshot can move currentTimeMillis in either direction, which
    // would either silence these events for as long as the step or defeat the rate limit entirely.
    // nanoTime's origin is arbitrary and may be negative, so an "unclaimed" window cannot be spelled
    // as zero; it is seeded a whole interval in the past instead - see unclaimedWindow.
    private static final long READINESS_LOG_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1L);
    private static final AtomicLong READINESS_LOG_LAST_AT = new AtomicLong(unclaimedWindow());
    private static final AtomicLong READINESS_LOG_SUPPRESSED = new AtomicLong(0L);
    private static final AtomicLong READINESS_EMPTY_LOG_LAST_AT = new AtomicLong(unclaimedWindow());
    private static final AtomicLong READINESS_EMPTY_LOG_SUPPRESSED = new AtomicLong(0L);
    private static final AtomicLong READINESS_SHED_LOG_LAST_AT = new AtomicLong(unclaimedWindow());
    private static final AtomicLong READINESS_SHED_LOG_SUPPRESSED = new AtomicLong(0L);
    private static final AtomicLong READINESS_WAITERS_LOG_LAST_AT = new AtomicLong(unclaimedWindow());
    private static final AtomicLong READINESS_WAITERS_LOG_SUPPRESSED = new AtomicLong(0L);
    private static final AtomicLong READINESS_TIMEOUT_LOG_LAST_AT = new AtomicLong(unclaimedWindow());
    private static final AtomicLong READINESS_TIMEOUT_LOG_SUPPRESSED = new AtomicLong(0L);
    private static final AtomicLong READINESS_TRANSPORT_LOG_LAST_AT = new AtomicLong(unclaimedWindow());
    private static final AtomicLong READINESS_TRANSPORT_LOG_SUPPRESSED = new AtomicLong(0L);

    // Window that throttles how often a probe may ask the delegator factory for a delegator, using
    // the same interval as the log above. It is claimed only when the ServletContext holds no
    // delegator yet, and it is reopened immediately by a lookup that succeeds, so it holds back
    // nothing but a failing lookup - see resolveDelegator.
    private static final AtomicLong DELEGATOR_LOOKUP_LAST_AT = new AtomicLong(unclaimedWindow());

    // The most recent readiness verdict and the permit that lets exactly one probe establish it.
    //
    // WHY THE VERDICT IS SHARED. A readiness query is not guaranteed to be quick: it borrows a pooled
    // connection, and when the pool is exhausted DBCP blocks the caller for up to the datasource's
    // pool-sleeptime, which DBCPConnectionFactory passes to setMaxWaitMillis and which the engine's
    // own default leaves at five minutes. Readiness is polled continuously by every load-balancer
    // target, and probes arrive whether or not the previous one answered, so letting every probe issue
    // its own query lets an exhausted pool park one container thread per probe for that whole stretch
    // until the instance has no request threads left for real traffic - having been asked nothing more
    // than "are you ready". The managed-datasource definitions shorten that borrow wait, but this
    // class does not rely on them doing so: the check is separately bounded here, see
    // READINESS_CHECK_DEADLINE_MILLIS.
    //
    // Bounding the number of probes that may answer, which is what an admission counter does, cures
    // that by manufacturing a failure verdict for the probes it refuses. That is the wrong trade: a
    // multi-Availability-Zone target group is probed by one node per zone simultaneously, so from the
    // third simultaneous prober onwards a healthy instance answers some probes DOWN and is drained.
    // Sharing the verdict cures it without ever fabricating one, because the answer a shed probe
    // needs is already being computed by the probe that got through.
    //
    // WHY SHARING THE VERDICT IS NOT ENOUGH ON ITS OWN. Coalescing the database work bounds how many
    // probes touch the datasource - one - but on its own it does not bound how many CONTAINER THREADS
    // readiness may occupy. A probe that arrives with no verdict to stand on waits for the running
    // check (path 4 in isInstanceReady), and while the wait is short and holds no pooled
    // connection, an unlimited number of probes may be inside it at once: an anonymous flood of a few
    // thousand concurrent requests to /health/ready is then enough to hold every connector thread for
    // the duration of the wait and starve real traffic, without a single database query being issued.
    // That is the availability defect READINESS_MAX_WAITERS closes.
    //
    // READINESS_WAITERS bounds the waiters and nothing else. Only the path that has nothing to answer
    // from is bounded, so the admission-counter trade above is not reintroduced wholesale: a probe over
    // the bound re-reads the shared verdict, without blocking, and answers with it when one exists, so
    // an instance that has completed a check within the grace window cannot be reported not-ready by
    // any number of simultaneous probes. Only a probe that finds no verdict at all is answered
    // not-ready, under its own event code. Readiness therefore holds at most READINESS_MAX_WAITERS
    // threads for at most READINESS_CHECK_WAIT_NANOS each however hard the endpoint is flooded, and
    // every probe beyond that is answered in constant time with no wait, no permit and no datasource
    // access.
    //
    // The residue of the trade is real and is what sizes the bound: a probe refused a slot while NO
    // verdict exists is answered not-ready without the datasource having said so. That state is a
    // JVM's first probes and the interval between a verdict ageing out and the next check publishing,
    // so the bound has to be far enough above legitimate probe concurrency that such a burst never
    // reaches it - see READINESS_MAX_WAITERS, where that requirement, not the thread pool, is what
    // fixes the number.
    private static final AtomicLong READINESS_WAITERS = new AtomicLong(0L);

    // How many probes may wait for a running check at the same time.
    //
    // SIZING IS A TRADE, AND ITS LOWER BOUND IS THE BINDING ONE. A probe refused a slot is answered
    // from the shared verdict whenever one exists, so the bound is invisible to an instance that has
    // completed a check within the grace window. The one state in which it is visible is a probe that
    // arrives with NO verdict at all: at a JVM's very first probes, and in the brief interval between a
    // verdict ageing out and the next check publishing. In that state the probes that fit in the waiter
    // set are answered by the check they waited for, and any beyond it are answered not-ready - which
    // is a verdict probe concurrency produced rather than the datasource, exactly the failure mode the
    // shared verdict exists to prevent. The bound must therefore stay above the largest burst that can
    // legitimately coincide with that state, or a cold start under normal polling would report a
    // perfectly healthy instance not ready.
    //
    // WHAT THAT MAKES IT. A target group polls with one request per health-check node per interval, and
    // a load balancer uses several nodes per Availability Zone, so a multi-zone deployment produces
    // something of the order of ten simultaneous probes; a container orchestrator's own liveness and
    // readiness probes and an operator's curl add a few more. Thirty-two leaves roughly threefold
    // headroom over that, so no burst normal operation produces can reach it - and a cold start, which
    // is precisely when several target groups and the orchestrator all probe an instance that has no
    // verdict yet, is answered entirely by the check they share.
    //
    // AND WHY IT IS STILL A BOUND WORTH HAVING. Tomcat's maxThreads defaults to 200. Thirty-two waiters
    // holding a thread for at most READINESS_CHECK_WAIT_NANOS is around a sixth of that pool for half
    // a second, against an unbounded flood's ability to hold the whole pool for half a second per probe
    // indefinitely. It is the ratio that matters, not the absolute number: whatever rate probes arrive
    // at, readiness occupies at most this many threads, so real traffic always has the rest.
    private static final int READINESS_MAX_WAITERS = 32;
    //
    // HOW IT IS SHARED. READINESS_CHECK_RUNNING is a one-permit gate: the probe that wins it has the
    // count issued while the others do not touch the datasource at all. That is a STRICTER bound than
    // an admission counter of two - at most one count can be inside the entity engine at any instant -
    // and the verdict it publishes then answers every probe that asked. See isInstanceReady for
    // the ways a probe is answered.
    //
    // The permit is released by the CHECK, in its own finally, and never by the probe that started it.
    // That is what keeps the bound honest once the check itself is bounded: a probe that stops waiting
    // for a slow check must not thereby licence the next probe to start a second concurrent count
    // against a datasource that is evidently already struggling. The check therefore owns the permit
    // for as long as it runs, however long that turns out to be, and the abandoning probe answers from
    // the fallbacks instead - see runBoundedReadinessCheck.
    //
    // READINESS_VERDICT holds the verdict and the reading it was established at as ONE immutable
    // value behind ONE atomic reference, so the two can never be read torn and no lock is needed. A
    // null reference means no verdict has been established yet. It is a reference rather than a packed
    // word because the instant is now a System.nanoTime() reading, whose origin is arbitrary and may
    // be negative, so neither the sign nor zero is available to carry anything else.
    private static final AtomicReference<Verdict> READINESS_VERDICT = new AtomicReference<>(null);
    private static final AtomicLong READINESS_CHECK_RUNNING = new AtomicLong(0L);

    // How long a verdict answers a probe on its own, and how long it still answers one that could not
    // run a check of its own.
    //
    // FRESH is deliberately shorter than any load-balancer health-check interval, so a probe schedule
    // of the usual shape still measures the datasource on every round and readiness stays a live
    // signal rather than a cached one. It only takes effect for probes that overlap - exactly the
    // case that used to be shed - where it collapses a burst onto one query.
    //
    // GRACE is the window in which a verdict still stands for a probe that arrived while another
    // probe's check was already running. It bounds how long a not-yet-refuted verdict may be repeated:
    // a check that fails publishes its own verdict within milliseconds, so GRACE is only reached when
    // a check is unusually slow or blocked on a borrow, and readiness then turns not-ready once GRACE
    // past the last established verdict has elapsed - well inside the two-to-three consecutive
    // failures a target group needs before it drains a target.
    private static final long READINESS_VERDICT_FRESH_NANOS = TimeUnit.SECONDS.toNanos(1L);
    private static final long READINESS_VERDICT_GRACE_NANOS = TimeUnit.SECONDS.toNanos(5L);

    // How long a probe waits for a check that is already running, and how often it looks.
    //
    // This is the cold-start path: the very first probes of a JVM, and any probe arriving after GRACE
    // has elapsed, have no verdict to fall back on, so they wait for the running check instead of
    // guessing. The wait is bounded and the waiting probe holds no pooled connection and issues no
    // query, so an unreachable datasource can occupy a probe thread for at most this long - three
    // orders of magnitude below the five-minute borrow the bound above was introduced to prevent.
    private static final long READINESS_CHECK_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(500L);
    private static final long READINESS_CHECK_POLL_MILLIS = 5L;

    // How long the probe that won the permit waits for the count itself.
    //
    // WHY THIS EXISTS AT ALL. Everything above bounds how long a probe waits for SOMEBODY ELSE's
    // check. It does not bound the check, and the check is the part that can block: it borrows a
    // pooled connection, and an exhausted pool parks the borrower for the datasource's configured
    // borrow wait, while the socket underneath it has only whatever deadlines the JDBC URI carries.
    // Left unbounded, the winning probe holds a container request thread for that entire stretch, so
    // the load balancer's own probe timeout expires first and readiness is decided by the balancer
    // giving up rather than by anything this instance reported. The managed-datasource definitions
    // shorten the borrow wait and add explicit socket and connect deadlines, but a probe must not
    // depend on datasource configuration to stay bounded, so it applies its own deadline as well.
    //
    // HOW IT IS APPLIED. The count runs on a small dedicated executor and the probe waits on the
    // Future for at most this long - the platform's own cancellable, bounded wait. The count is NOT
    // interrupted when the wait expires: interrupting a thread inside a JDBC borrow or a socket read
    // rarely stops it and can leave a pooled connection in an indeterminate state, and the reading it
    // eventually produces is a real measurement worth publishing. It keeps the permit until it
    // finishes, so nothing starts a second count, and the probe that stopped waiting answers from the
    // fallbacks instead.
    //
    // WHY IT IS THIS LONG. It has to be shorter than the load balancer's probe timeout for the
    // deadline to be this instance's rather than the balancer's; two seconds sits comfortably under
    // the two-to-six seconds those timeouts are usually set to, is two orders of magnitude below the
    // borrow wait it is guarding against, and is far longer than a healthy count needs.
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
     * Method gate. Everything this endpoint serves is a GET or a HEAD, and every other method is
     * refused here before HttpServlet's dispatch can reach one of its own defaults, which are all
     * unusable for a probe endpoint: doTrace echoes the received request headers straight back to an
     * anonymous caller, doPost, doPut and the rest answer through sendError, which hands the response
     * to the container error-page machinery and would replace the compact JSON document with an HTML
     * page, and a method HttpServlet does not dispatch at all - PATCH and PROPFIND among them - would
     * otherwise be answered 501 by that same error-page machinery rather than 405.
     *
     * Refusing here rather than in each doXxx override keeps one rule in one place and makes the
     * refusal uniform for every method, named or not. The request is then handed to super.service(),
     * which performs the ordinary dispatch to doGet and doHead below, so those two remain the entry
     * points that serve a probe.
     */
    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        if (!ALLOWED_METHODS.contains(request.getMethod())) {
            // 405 has to carry Allow per the HTTP specification, and it also tells a mis-configured
            // target group what to switch to.
            response.setHeader(ALLOW_HEADER, ALLOW_VALUE);
            writeResponse(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, BODY_UNKNOWN);
            return;
        }
        super.service(request, response);
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
     * Shared by both entry points, so each enforces one contract from one implementation; static
     * because it needs nothing from the instance.
     *
     * The body guard runs before the routing and does not read the request body: Content-Length and
     * Transfer-Encoding are headers, so an oversized or chunked body is refused for the cost of a
     * header lookup and is then discarded by the container rather than by this JVM's heap. Neither
     * branch touches the session, and liveness in addition resolves no delegator and issues no query,
     * so it stays answerable while the datasource is unavailable.
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
            // Written after the verdict, so accounting for what the rate limit held back never delays
            // the answer a load balancer is waiting for - see flushSuppressedEvents.
            flushSuppressedEvents();
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
     * A probe is answered in one of four ways, in this order:
     *
     *   1. from a verdict younger than READINESS_VERDICT_FRESH_NANOS, touching nothing at all;
     *   2. by having the check run, if it wins the one permit, and publishing what it found - within its
     *      own bounded deadline, see runBoundedReadinessCheck;
     *   3. from a verdict younger than READINESS_VERDICT_GRACE_NANOS, when a check is already running or
     *      is still running past its deadline, and has not refuted that verdict yet;
     *   4. by waiting a bounded time for the running check to publish, if it is one of at most
     *      READINESS_MAX_WAITERS probes doing so - see awaitRunningCheck;
     *   5. immediately, without waiting at all, once that bound is reached - see answerWithoutWaiting.
     *
     * The first four answer with a verdict a completed check actually produced. Probe concurrency alone
     * can therefore never turn a healthy instance into a not-ready one, which is what an admission
     * counter that answered "not ready" for the probes it refused did: a load-balancer target group
     * probed by one node per Availability Zone routinely has three or more probes in flight at once,
     * so the refusals landed in normal operation and drained healthy targets.
     *
     * The fifth is what bounds thread occupancy, and it is deliberately NOT such an admission counter:
     * it still answers from the shared verdict whenever one exists, and answers not-ready only when no
     * verdict exists to answer from - a state in which nothing has established that the instance can
     * serve. Reaching it at all needs several times the simultaneous probes a target group and a
     * container orchestrator produce together; see READINESS_MAX_WAITERS for why that headroom is what
     * keeps this path from behaving like the admission counter, and why the bound is sized by it.
     *
     * Exactly three answers are not backed by a completed check - a check that outran its own deadline
     * with no verdict standing, the fourth waiting in vain, and the fifth finding no verdict to answer
     * from - and each is reported under its own event code, so an operator can tell all three apart
     * from a datasource that reported a failure, and from one another.
     *
     * EVERY path out of this method is bounded, which is the property that matters for a probe: the
     * fresh and grace paths return without touching anything, the wait is bounded by
     * READINESS_CHECK_WAIT_NANOS, and the check itself is bounded by READINESS_CHECK_DEADLINE_MILLIS.
     * No path can park a container request thread on a connection borrow or a socket read.
     *
     * A verdict is at most READINESS_VERDICT_FRESH_NANOS old on a path that has a check available and
     * at most READINESS_VERDICT_GRACE_NANOS old on one that does not, so readiness remains a live
     * signal in both directions and for every dimension: a datasource or a cache-invalidation
     * transport that fails is reported not-ready on the next check, and one that recovers is reported
     * ready again just as quickly, with no restart. That is what makes this a fail-closed state an
     * instance leaves by itself once the dependency comes back, rather than a latch an operator has to
     * reset.
     */
    private static boolean isInstanceReady(HttpServletRequest request) {
        // 1. A verdict this recent answers on its own. Nothing is touched: no permit, no delegator, no
        //    datasource. This is what a burst of overlapping probes normally lands on.
        Boolean fresh = establishedVerdict(READINESS_VERDICT_FRESH_NANOS);
        if (fresh != null) {
            return fresh;
        }
        // 2. Otherwise one probe - and only one - has the check run and publishes what it found. The
        //    context is read HERE, on the request thread, because the check runs on another thread and
        //    a request object must not be touched from one: the ServletContext is the long-lived,
        //    thread-safe object the check actually needs.
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
        // 3. A check is already running and this probe has a verdict that has not been refuted yet, so
        //    it repeats it rather than inventing a failure the datasource has not reported.
        Boolean established = establishedVerdict(READINESS_VERDICT_GRACE_NANOS);
        if (established != null) {
            return established;
        }
        // 4. Nothing to stand on - the running check is the only answer there is, so wait for it, but
        //    only as one of a bounded set of waiters. The slot is released in a finally so that a
        //    thread the container interrupts or an exception unwinding through here cannot leak one and
        //    shrink the set for the life of the JVM.
        if (claimWaiterSlot()) {
            try {
                return awaitRunningCheck();
            } finally {
                READINESS_WAITERS.decrementAndGet();
            }
        }
        // 5. The waiter set is full, so this probe is answered in constant time instead of occupying a
        //    container thread. See READINESS_WAITERS for why this bound exists and why it does not
        //    reintroduce the admission counter the shared verdict replaced.
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
     * path from behaving like the admission counter the shared verdict was introduced to replace - a
     * flood arriving at a healthy instance is answered with that instance's real, recent verdict.
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
     * OWNERSHIP OF THE PERMIT. The submitted task releases the permit in its own finally, so the permit
     * tracks the CHECK rather than the probe that started it. That is the whole point: when this method
     * returns null the count is still in flight, and the next probe must not be allowed to start a
     * second one against a datasource that is already failing to answer the first. The permit is
     * released here only when the task never started - a submission the executor refuses - because
     * then there is no check to release it later, and leaving it held would wedge readiness on the
     * fallbacks for the life of the JVM.
     *
     * NOT CANCELLED ON TIMEOUT. cancel(true) would interrupt a thread that is almost certainly inside
     * a connection borrow or a socket read, where interruption is usually ineffective and can leave a
     * pooled connection unusable, and it would discard a genuine measurement that is about to arrive.
     * The task is therefore left to finish and publish; the executor's single thread means at most one
     * such task can ever be outstanding, so nothing accumulates.
     *
     * FAIL-CLOSED. A refused submission and a task that threw are both answered not-ready under the
     * datasource event code, so no failure of this mechanism can report a healthy instance.
     *
     * WHAT A PERMANENTLY WEDGED DATASOURCE LOOKS LIKE. If a count never returns, the permit is never
     * released and no further count is ever started. Readiness then answers from the grace window
     * until it expires and not-ready afterwards, which is the correct report for an instance whose
     * datasource has stopped answering - and it costs no request thread beyond the bounded wait, which
     * is the property the whole arrangement exists to guarantee. The managed-datasource definitions
     * carry an explicit socket deadline so that such a count does eventually return and readiness can
     * recover without a restart; this class stays bounded and truthful either way.
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
     * Measures this instance's readiness dimensions and reports what it found. Run for one probe at a
     * time, the one holding the READINESS_CHECK_RUNNING permit; its verdict is what isInstanceReady
     * then shares.
     *
     * It runs on the dedicated check thread rather than on the request thread, which is what lets the
     * probe put a deadline on it - see runBoundedReadinessCheck. Everything it needs is therefore
     * passed in as a long-lived, thread-safe object: the ServletContext, never the request.
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
     * THE SECOND DIMENSION is cache coherence, and it is measured only after the datasource has
     * answered - both because the delegator the first dimension resolves is what says whether the
     * second applies at all, and because ordering them this way keeps the more urgent operator signal
     * first: an instance that cannot reach its datasource has a bigger problem than one that cannot
     * reach a message broker, and it should not be reported under the broker's event code. See
     * isCacheTransportReady.
     *
     * HOW IT IS ISSUED. The count goes through the entity helper that owns the entity's group rather
     * than through EntityQuery. Both end in the same GenericDAO.selectCountByCondition, so the same
     * SELECT COUNT runs against the same entity, but EntityQuery routes through
     * GenericDelegator.findCountByCondition, which logs the throwable together with its stack trace
     * before rethrowing, marks the transaction for rollback and runs three ECA phases. On a path an
     * anonymous caller polls every few seconds that logging is unbounded and happens below this
     * class, outside the rate limit here. GenericHelperDAO passes straight through to GenericDAO,
     * which logs at verbose level only and never logs a throwable, so a failing check produces
     * exactly the one rate-limited event code this method emits. ModelReader.getModelEntity is used
     * in place of Delegator.getModelEntity for the same reason: the latter logs a throwable when the
     * model cannot be read, the former throws it.
     *
     * The check is strictly read-only: no DDL, no writes, no cache mutation and no explicit
     * transaction management, which is what allows a serving instance to run without DDL
     * privileges. The delegator is resolved per check rather than in init(), so a datasource that
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
     * object for a request dispatched into this webapp, and reading it from the request rather than
     * from the ServletConfig keeps the check static and this class entirely free of per-instance
     * state, so no initialisation order has to be relied on and both entry points share one
     * implementation.
     */
    private static boolean runReadinessCheck(ServletContext context) {
        try {
            Delegator delegator = resolveDelegator(context);
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
            // Second dimension. The datasource has answered, so what remains is whether this instance
            // is fit to be one member of a coherent fleet - see isCacheTransportReady, which is inert
            // unless this delegator requires distributed cache clear.
            if (!isCacheTransportReady(context, delegator)) {
                logRateLimitedWarning(EVENT_READINESS_CACHE_TRANSPORT_UNAVAILABLE, READINESS_TRANSPORT_LOG_LAST_AT,
                        READINESS_TRANSPORT_LOG_SUPPRESSED);
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
        }
    }

    /*
     * Reports whether this instance is fit to serve as one member of a cache-coherent fleet.
     *
     * WHY READINESS OWNS THIS AT ALL. When a delegator has distributed cache clear enabled, every
     * entity write it performs calls EntityCacheServices, which dispatches the distributedClearCacheLine
     * services asynchronously. Those services are declared engine="jms" location="serviceMessenger"
     * and none of them declares require-new-transaction, so ServiceDispatcher.runAsync does not
     * suspend the caller's transaction. If no jms-service named serviceMessenger is active,
     * JmsServiceEngine.run dereferences a null service element; runAsync catches the throwable and
     * calls TransactionUtil.rollback, which - because it did not begin the transaction - marks the
     * CALLER's transaction rollback-only. EntityCacheServices catches only GenericServiceException
     * and logs it, and cannot undo that mark. So an instance in that state does not merely serve
     * stale caches: it rolls back the very writes it was asked to perform, once per write, with a
     * stack trace each time. Nothing in the write path fails loudly enough to stop it, which is why
     * the load balancer has to be the thing that stops it - by being told this instance is not ready.
     *
     * INERT UNLESS THE FLEET NEEDS IT. Delegator.useDistributedCacheClear() is the per-delegator
     * distributed-cache-clear-enabled attribute, read straight off the already-parsed delegator
     * configuration: no I/O, no logging and nothing to throw. It is false in the committed
     * configuration, so a single-node or local H2 deployment never enters the rest of this method and
     * its readiness verdict is exactly what it was before this dimension existed.
     *
     * WHAT IS OBSERVED, AND WHY IT IS FREE. The dispatcher is read from the ServletContext attribute
     * ContextFilter.init() publishes, never through WebAppUtil.getDispatcher, which BUILDS one when the
     * attribute is absent. Its JmsListenerFactory - a plain field read on ServiceDispatcher, null when
     * service.properties disables JMS - holds the subscribers, and getJMSListeners() hands back a copy
     * of that map, so iterating it costs no lock and no network call. isConnected() is a plain field
     * read on each listener. The whole dimension is therefore an in-process observation: it adds no
     * socket, no broker round trip and no measurable time to a check that is already bounded, which is
     * what makes it safe to run on a path a load balancer polls every few seconds.
     *
     * WHY THAT SIGNAL IS THE RIGHT ONE. It is strictly stronger than a TCP probe of the broker.
     * JmsTopicListener.load() reports connected only after the JNDI initial context is built, the
     * connection factory and the topic are looked up, a connection and a session are created, a
     * subscriber is registered and the connection is started - so a connected subscriber proves the
     * broker is reachable, the credentials are accepted and the topic exists, which is exactly what
     * the SENDING half needs too. It is also self-healing in both directions and needs no state of its
     * own: JmsListenerFactory registers each listener in its map BEFORE calling load(), so a listener
     * that could not connect is present and reports false; AbstractJmsListener.onException sets the
     * flag false the moment the broker drops and then retries refresh() every ten seconds, so the flag
     * returns to true by itself when the broker comes back and the instance rejoins the target group
     * with no restart and no operator action.
     *
     * FAIL-CLOSED, INCLUDING DURING START-UP. An absent dispatcher attribute, a null listener factory,
     * an empty subscriber map and a single disconnected subscriber all report not ready. The empty map
     * is deliberate and covers two states that must both hold traffic off: the listener factory's
     * loader thread has not completed its first pass yet, and the configuration declares no
     * jms-service with listen="true" at all. Neither is a fleet member that can be trusted with a
     * write, and reporting 200 in either would be the silent failure this exists to prevent. Every
     * one of them is transient by construction - the loader retries, and the entry point refuses to
     * start an instance with the flag set and no transport configured - so this cannot wedge a
     * correctly configured deployment.
     *
     * The result is not logged here; the caller emits the one rate-limited event code, so a broker
     * outage across a whole fleet costs one line per minute per JVM rather than one per probe.
     */
    private static boolean isCacheTransportReady(ServletContext context, Delegator delegator) {
        if (!delegator.useDistributedCacheClear()) {
            return true;
        }
        Object published = context.getAttribute(DISPATCHER_ATTRIBUTE);
        if (!(published instanceof LocalDispatcher)) {
            return false;
        }
        JmsListenerFactory listenerFactory = ((LocalDispatcher) published).getJMSListeneFactory();
        if (listenerFactory == null) {
            return false;
        }
        Map<String, GenericMessageListener> subscribers = listenerFactory.getJMSListeners();
        if (subscribers == null || subscribers.isEmpty()) {
            return false;
        }
        for (GenericMessageListener subscriber : subscribers.values()) {
            if (subscriber == null || !subscriber.isConnected()) {
                return false;
            }
        }
        return true;
    }

    /*
     * Reports the established verdict when it is no older than the given window, and null when there
     * is none that recent - "no answer available", which is not the same as a not-ready answer and is
     * why the return type is a boxed Boolean.
     *
     * The age is a difference of two System.nanoTime() readings, so it measures elapsed time and
     * nothing else: it cannot be distorted by a wall-clock step, and the subtraction stays correct
     * across the counter's wraparound. A negative age is therefore unreachable from a monotonic
     * source; it is still refused, because a verdict of unknowable age must not be honoured and one
     * extra check is the entire cost of saying so.
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
     * indivisible: the previous encoding packed both into one long and could do so only because an
     * epoch millisecond is always positive, leaving the sign free to carry the verdict. A
     * System.nanoTime() reading has an arbitrary origin and may be negative or zero, so neither the
     * sign nor zero is available and the pair has to be a value of its own.
     */
    private record Verdict(boolean ready, long establishedAtNanos) {
    }

    /*
     * Waits for the check another probe is running and answers with its verdict.
     *
     * Only a probe with nothing to fall back on gets here, and only while holding one of the
     * READINESS_MAX_WAITERS slots its caller claimed: a JVM's first probes, and any probe that arrives
     * once the grace window past the last verdict has elapsed. It holds no connection and issues no
     * query - it is waiting on a check that is already in flight - and it gives up after a wait bounded
     * on the MONOTONIC clock, so a datasource that has stopped answering costs a probe thread that wait
     * and nothing more, whatever the wall clock does meanwhile. Because the number of threads that may
     * be in here at once is bounded as well, the total thread occupancy readiness can reach is
     * READINESS_MAX_WAITERS multiplied by READINESS_CHECK_WAIT_NANOS, whatever rate probes arrive at.
     *
     * Giving up is the one case that answers not-ready without the datasource having said so, and it
     * is reported under its own event code precisely so that it stays distinguishable from a
     * datasource that failed: it means a readiness check was still running and no verdict was recent
     * enough to repeat, which calls for looking at what is making the check slow.
     */
    private static boolean awaitRunningCheck() {
        // The bound is measured on the MONOTONIC clock rather than on the wall clock, and is expressed
        // as "elapsed since start" rather than as an instant to compare against, because that form
        // stays correct across the nanoTime counter's wraparound.
        //
        // This is the only thing that limits how long a probe thread stays in this method, and
        // System.currentTimeMillis is a reading of the wall clock rather than a measure of elapsed
        // time: a time daemon steps it in either direction - an NTP correction, a container resuming
        // on a host whose clock has moved on, an operator setting the date - and a step falsifies the
        // bound both ways. Forwards, the wait ends immediately and a cold-start probe reports
        // not-ready without having given the running check its 500 milliseconds. Backwards, and this
        // is the damaging one, the bound recedes and the loop keeps polling until the wall clock
        // catches up, so a step of a minute occupies a probe thread for a minute - exactly the
        // unbounded occupancy the bound exists to prevent, multiplied by every probe in flight.
        //
        // System.nanoTime bears no relation to wall-clock time and is monotonic, so the wait stays
        // 500 milliseconds whatever happens to the clock.
        //
        // The verdict a check publishes is timed on that same clock: Verdict carries a nanoTime
        // reading in establishedAtNanos rather than an epoch millisecond, and establishedVerdict
        // discards a verdict whose computed age comes out negative, so no part of this path depends
        // on the wall clock at all.
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
            DELEGATOR_LOOKUP_LAST_AT.set(unclaimedWindow());
        }
        return delegator;
    }

    /*
     * Emits one warning for the given event code per READINESS_LOG_INTERVAL_NANOS, and appends the
     * number of occurrences suppressed since the previous line so an outage - or a stretch in which no
     * verdict could be obtained - is quantified rather than silently dropped. Occurrences suppressed
     * after the last line of a burst are written by flushSuppressedEvents instead.
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
     * is polled continuously, which is what makes calling this from a readiness probe enough: the
     * outstanding count reaches the log within one probe interval of the window elapsing, whether or
     * not the condition that produced it ever occurs again, and without this class owning a timer, a
     * background thread or any per-instance state.
     *
     * The rate limit is not weakened. Each code still claims its own window through the same
     * compare-and-set, so at most one line per code per interval is written, and a code with nothing
     * suppressed writes nothing - a healthy probe stays silent, which is what keeps continuous polling
     * out of the log entirely.
     */
    private static void flushSuppressedEvents() {
        flushSuppressed(EVENT_READINESS_UNAVAILABLE, READINESS_LOG_LAST_AT, READINESS_LOG_SUPPRESSED);
        flushSuppressed(EVENT_READINESS_SCHEMA_EMPTY, READINESS_EMPTY_LOG_LAST_AT, READINESS_EMPTY_LOG_SUPPRESSED);
        flushSuppressed(EVENT_READINESS_SHED, READINESS_SHED_LOG_LAST_AT, READINESS_SHED_LOG_SUPPRESSED);
        flushSuppressed(EVENT_READINESS_WAITERS_FULL, READINESS_WAITERS_LOG_LAST_AT, READINESS_WAITERS_LOG_SUPPRESSED);
        flushSuppressed(EVENT_READINESS_CHECK_TIMEOUT, READINESS_TIMEOUT_LOG_LAST_AT, READINESS_TIMEOUT_LOG_SUPPRESSED);
        flushSuppressed(EVENT_READINESS_CACHE_TRANSPORT_UNAVAILABLE, READINESS_TRANSPORT_LOG_LAST_AT,
                READINESS_TRANSPORT_LOG_SUPPRESSED);
    }

    /*
     * Flushes one code's suppressed count. The counter is read before the window is claimed so a code
     * with nothing outstanding never consumes its own window, and the count is re-tested after the
     * claim because a concurrent occurrence may have taken it in between.
     */
    private static void flushSuppressed(String eventCode, AtomicLong window, AtomicLong suppressed) {
        if (suppressed.get() > 0L && claimWindow(window)) {
            long missed = suppressed.getAndSet(0L);
            if (missed > 0L) {
                Debug.logWarning(suppressedLine(eventCode, missed), MODULE);
            }
        }
    }

    /*
     * The one form in which a suppressed count is reported, so a log consumer parses a single shape no
     * matter whether the count arrived on a later occurrence or on a flush.
     */
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
     * The readings are monotonic, so the interval this enforces is a real elapsed minute. On the wall
     * clock it was not: a backwards step made every window look freshly claimed and silenced these
     * events for the length of the step, and a forwards step reopened all of them at once - both
     * during exactly the kind of incident that also disturbs a host's clock.
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
     * hook this class can hang one on - it is mapped by a deployment descriptor and owns no
     * per-instance state - and a single daemon thread costs nothing to leave to the JVM's exit.
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
