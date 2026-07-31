/*
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
 */
package org.apache.ofbiz.webapp.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.datasource.GenericHelper;
import org.apache.ofbiz.entity.model.ModelEntity;
import org.apache.ofbiz.entity.model.ModelReader;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.jms.GenericMessageListener;
import org.apache.ofbiz.service.jms.JmsListenerFactory;
import org.apache.ofbiz.webapp.WebAppUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

/**
 * Behavioural contract of {@link HealthCheckServlet}, the unauthenticated liveness and readiness
 * probe served at {@code /health/live} and {@code /health/ready}.
 *
 * <p>Every test invokes a production entry point - the {@code service} gate, the {@code doFilter}
 * short-circuit, or {@code doGet} and {@code doHead} where a container would dispatch to them
 * directly - and asserts the observable response: status code, exact JSON document, content type,
 * character encoding, cache header and, where it matters, the
 * {@code Allow} header. Mocks only supply the collaborators; no assertion is ever made against a
 * value a mock was configured to return. The static seams the class depends on,
 * {@link WebAppUtil#getDelegator} and {@link Debug}, are replaced with scoped static mocks inside
 * try-with-resources so nothing leaks into another test, and no test opens a database connection,
 * reads configuration or touches the network.
 *
 * <p>Four properties are pinned here that the probes' exposure depends on, since they answer
 * anonymous callers: a probe is answered from the first position in the filter chain without the
 * chain being called at all, the request body is never read, no method other than GET or HEAD is
 * served, and a readiness failure never writes an internal detail to the log.
 */
public final class HealthCheckServletTests {

    private static final String LIVE_UP = "{\"status\":\"UP\"}";
    private static final String READY_UP = "{\"status\":\"UP\",\"database\":\"UP\"}";
    /**
     * The not-ready document, which names no dimension. Readiness is measured across more than the
     * datasource - the entity-cache invalidation transport is the second dimension - so a body that
     * asserted {@code "database":"DOWN"} would be an outright false claim on every 503 the other
     * dimension raises. Which dimension failed belongs in the log, under its own event code, and not
     * in an answer to an anonymous caller.
     */
    private static final String READY_DOWN = "{\"status\":\"DOWN\"}";
    private static final String UNKNOWN = "{\"status\":\"DOWN\"}";

    private static final String CONTENT_TYPE = "application/json";
    private static final String ENCODING = "UTF-8";
    private static final String CACHE_HEADER = "Cache-Control";
    private static final String CACHE_VALUE = "no-cache, no-store, must-revalidate";
    private static final String ALLOW_HEADER = "Allow";
    private static final String ALLOW_VALUE = "GET, HEAD";

    // The safe response headers every verdict must carry. The probes are mapped outside /control/*,
    // so a probe response never reaches RequestHandler, which is where UtilHttp applies these for an
    // ordinary view, and the servlet has to apply them itself. The values mirror the UtilHttp
    // defaults, keeping a probe response consistent with the rest of the application.
    private static final String CONTENT_TYPE_OPTIONS_HEADER = "X-Content-Type-Options";
    private static final String CONTENT_TYPE_OPTIONS_VALUE = "nosniff";
    private static final String FRAME_OPTIONS_HEADER = "X-Frame-Options";
    private static final String FRAME_OPTIONS_VALUE = "sameorigin";
    private static final String REFERRER_POLICY_HEADER = "Referrer-Policy";
    private static final String REFERRER_POLICY_VALUE = "no-referrer-when-downgrade";

    /** The stable event code a readiness failure must log, in place of any internal detail. */
    private static final String EVENT_CODE = "HEALTH-READINESS-DATASOURCE-UNAVAILABLE";

    /** The stable event code a reachable but unpopulated schema must log - deliberately its own. */
    private static final String EMPTY_EVENT_CODE = "HEALTH-READINESS-SCHEMA-EMPTY";

    /**
     * The stable event code a probe must log when it could obtain no verdict at all - deliberately not
     * one of the two above, because the operator action differs and because a probe that repeats an
     * established verdict is a normal answer that logs nothing.
     */
    private static final String SHED_EVENT_CODE = "HEALTH-READINESS-PROBE-SHED";

    /**
     * The stable event code a probe must log when it arrived once the waiter set was already full and
     * found no verdict to answer from - again deliberately its own, because the operator action is
     * different again: find out what is sending far more simultaneous probes than a target group does,
     * and why a readiness check has not completed.
     */
    private static final String WAITERS_FULL_EVENT_CODE = "HEALTH-READINESS-WAITERS-FULL";

    /**
     * The stable event code a check that outran its deadline must log - deliberately its own, because
     * it is the only one of the five that says nothing about the datasource's answer: it says the
     * answer did not arrive in time, which calls for looking at what is making the check slow rather
     * than at the database itself.
     */
    private static final String TIMEOUT_EVENT_CODE = "HEALTH-READINESS-CHECK-TIMEOUT";

    /**
     * The stable event code an instance must log when its delegator requires distributed cache clear
     * but the invalidation transport has no connected subscriber - deliberately its own, because it is
     * the only code that says the datasource is fine and the fleet-coherence dependency is what is
     * missing, which calls for restoring the broker rather than the database.
     */
    private static final String TRANSPORT_EVENT_CODE = "HEALTH-READINESS-CACHE-TRANSPORT-UNAVAILABLE";

    /** The ServletContext attribute a deployed webapp publishes its service dispatcher under. */
    private static final String DISPATCHER_ATTRIBUTE = "dispatcher";

    /** The framework-tier entity the readiness probe counts, mirroring {@code CommonServices.ping}. */
    private static final String READINESS_ENTITY = "SequenceValueItem";

    /** The name of the dedicated thread the readiness check is required to run on. */
    private static final String CHECK_THREAD_NAME = "OFBiz-health-readiness-check";

    /** The system property the readiness check's deadline is read from. */
    private static final String DEADLINE_PROPERTY = "ofbiz.health.readiness.deadline.millis";

    /**
     * Field types that concurrent probes on the one container-managed instance may share unguarded.
     *
     * <p>{@link AtomicReference} joins {@link AtomicLong} in the accepted set because the shared
     * verdict is a pair - a readiness answer and the monotonic reading it was established at - and a
     * monotonic reading may be negative or zero, so neither the sign nor zero is free to carry the
     * answer in a single long.
     */
    private static final List<Class<?>> IMMUTABLE_FIELD_TYPES =
            List.of(String.class, java.util.Set.class, int.class, long.class);

    /**
     * Ceiling on how long a worker thread started by this class may take to answer its probe, and on
     * how long the pool it ran in may take to terminate afterwards.
     *
     * <p>It is deliberately generous, because it is not measuring the servlet: the individual timing
     * expectations are asserted against the servlet's own constants. This is the bound that turns the
     * failure mode an unbounded {@code Thread.join()} hides - a probe that never returns - into a
     * reported test failure rather than a build that hangs until the job is killed.
     */
    private static final long WORKER_TIMEOUT_SECONDS = 30L;

    /**
     * The value every one of the servlet's counters is poisoned with before a reset is exercised. Any
     * non-zero value would do; a recognisable one makes a leak obvious if it is ever read back.
     */
    private static final long POISONED_COUNTER = 987654321L;

    /** Divisor for turning a {@code System.nanoTime()} difference into milliseconds. */
    private static final long NANOS_PER_MILLI = 1000000L;

    /**
     * How often the waiter count is sampled while a flood runs. Fine-grained by a wide margin: a waiter
     * holds its slot for the whole of the servlet's wait window, which is orders of magnitude longer.
     */
    private static final long SAMPLE_INTERVAL_MILLIS = 1L;

    /**
     * How long a stubbed readiness count occupies the thread that runs it, standing in for a real first
     * check: building the connection pool and reading the entity model is not instantaneous. Comfortably
     * inside the servlet's wait window, so a probe that waits for this check is answered by it - which
     * is what the overlapping-probe cases are about - and long enough that the other probes of a burst
     * genuinely reach the waiting path rather than reading an already published verdict.
     */
    private static final long SLOW_CHECK_MILLIS = 150L;

    /**
     * Allowance subtracted from a wall-clock expectation. The servlet's wait loop compares
     * {@code System.currentTimeMillis()} readings, whose resolution is coarser than a millisecond on
     * some platforms, so a wait can legitimately measure a few milliseconds short of its own window.
     */
    private static final long CLOCK_GRANULARITY_MILLIS = 25L;

    /**
     * Multiple of the servlet's own wait window that a bounded wait may not exceed. It is deliberately
     * a wide multiple: the assertion is that the wait is bounded at all - which is what makes waiting
     * safer than issuing a second query - not that it is precise on a machine running other builds.
     */
    private static final long BOUNDED_WAIT_TOLERANCE = 20L;

    private HttpServletRequest request;
    private HttpServletResponse response;
    private StringWriter responseBody;
    private ServletContext servletContext;
    private HealthCheckServlet servlet;

    @BeforeEach
    public void setUp() throws Exception {
        request = mock(HttpServletRequest.class);
        // A probe arrives as a GET with no body unless a test says otherwise. getContentLengthLong
        // returns 0 on an unstubbed mock, which is exactly what "no body declared" looks like.
        when(request.getMethod()).thenReturn("GET");
        response = mock(HttpServletResponse.class);
        responseBody = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseBody));
        servletContext = mock(ServletContext.class);
        when(request.getServletContext()).thenReturn(servletContext);
        ServletConfig servletConfig = mock(ServletConfig.class);
        when(servletConfig.getServletContext()).thenReturn(servletContext);
        // The servlet is registered without load-on-startup, so the container initialises it lazily.
        servlet = new HealthCheckServlet();
        servlet.init(servletConfig);
        // The readiness log is rate limited through static counters, so they are reset before every
        // test; otherwise one test's failure would suppress the next test's expected log line.
        resetReadinessLogState();
        // The readiness check runs on a dedicated thread, which is what lets the probe put a deadline
        // on it. Most tests are about what the check decides rather than about where it runs, and the
        // static seams a probe depends on are replaced per thread, so the check is run inline for them.
        // The tests that are about the boundary itself install their own executor - or restore the
        // production one - and say so.
        HealthCheckServlet.installCheckExecutorForTesting(ProbeCheckExecutor.inline());
    }

    /**
     * Puts the servlet's static bookkeeping back into its fresh-JVM state on the way out as well as on
     * the way in, through the same reset method, so the two hooks can never drift apart, and takes back
     * out whatever check executor the test installed.
     *
     * <p>Resetting only on the way in is not enough. Every counter the servlet keeps is
     * {@code static}, so it outlives this instance and is shared with every other test class that Gradle
     * runs in the same worker JVM. Several tests here deliberately finish with the one-permit gate still
     * held or with a verdict published, because that is the state they exercise - and whatever the last
     * test of this class leaves behind would otherwise still be there when the worker starts the next
     * class. A held permit makes a later readiness probe take the waiting path, and a published verdict
     * answers one without the datasource being consulted at all, so the leak does not merely add noise:
     * it changes what another class's probe does.
     *
     * <p>The injected check executor is undone here for the same reason and unconditionally: nothing
     * installed by a test may outlive it, whether it passed or not, and a test that installed its own
     * executor - or restored the production one - would otherwise decide where every later class's
     * readiness check runs.
     */
    @AfterEach
    public void tearDown() throws Exception {
        HealthCheckServlet.installCheckExecutorForTesting(null);
        resetReadinessLogState();
    }

    /*
     * Liveness
     */

    @Test
    public void liveGetReturnsUpWithoutAnyDatabaseAccess() throws Exception {
        givenProbePath("/health/live", null);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            servlet.service(request, response);

            // Liveness must stay answerable while the datasource is unavailable, so neither the
            // delegator lookup nor the entity engine may be reached at all.
            webAppUtil.verifyNoInteractions();
        }
        assertProbeResponse(HttpServletResponse.SC_OK, LIVE_UP);
    }

    @Test
    public void liveGetResolvesPathUnderAPrefixMappingToo() throws Exception {
        // A prefix url-pattern such as /health/* splits the path across servletPath and pathInfo.
        givenProbePath("/health", "/live");

        servlet.service(request, response);

        assertProbeResponse(HttpServletResponse.SC_OK, LIVE_UP);
    }

    @Test
    public void liveGetTouchesNothingButTheRequestPathTheMethodAndTheBodyHeaders() throws Exception {
        givenProbePath("/health/live", null);

        servlet.service(request, response);

        // The exhaustive list of what a liveness probe is allowed to look at: the path, the method
        // and the two headers that announce a body. No session, no principal, no role, no attribute,
        // no parameter, no input stream. Any future login, permission or session access fails here,
        // because verifyNoMoreInteractions closes the list.
        verify(request).getServletPath();
        verify(request).getPathInfo();
        // Twice, and only because the gate consults the method and HttpServlet.service consults it
        // again to choose the dispatch. Delegating to that dispatch rather than reimplementing it is
        // what keeps this a plain servlet, and re-reading a request field costs nothing.
        verify(request, times(2)).getMethod();
        verify(request).getContentLengthLong();
        verify(request).getHeader("Transfer-Encoding");
        verifyNoMoreInteractions(request);
    }

    @Test
    public void liveHeadSetsTheSameStatusAndHeadersAsLiveGet() throws Exception {
        givenProbePath("/health/live", null);
        givenMethod("HEAD");

        servlet.service(request, response);

        assertProbeResponse(HttpServletResponse.SC_OK, LIVE_UP);
    }

    @Test
    public void headBodyIsSuppressedWhileStatusAndHeadersStillReachTheClient() throws Exception {
        givenProbePath("/health/live", null);
        // Tomcat installs a void output filter for HEAD, so the entity body is discarded while the
        // status line and the headers are sent. This wrapper reproduces that boundary faithfully:
        // the writer the servlet obtains is not the client writer.
        BodySuppressingResponse headResponse = new BodySuppressingResponse(response);
        givenMethod("HEAD");

        servlet.service(request, headResponse);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        verify(response).setContentType(CONTENT_TYPE);
        verify(response).setCharacterEncoding(ENCODING);
        verify(response).setHeader(CACHE_HEADER, CACHE_VALUE);
        verify(response).setHeader(CONTENT_TYPE_OPTIONS_HEADER, CONTENT_TYPE_OPTIONS_VALUE);
        verify(response).setHeader(FRAME_OPTIONS_HEADER, FRAME_OPTIONS_VALUE);
        verify(response).setHeader(REFERRER_POLICY_HEADER, REFERRER_POLICY_VALUE);
        // Nothing was written to the client: the servlet's body went to the suppressing wrapper.
        verify(response, never()).getWriter();
        assertEquals(LIVE_UP, headResponse.suppressedBody(), "HEAD must produce the same document a GET would");
        assertEquals("", responseBody.toString(), "no body may reach the client on a HEAD request");
    }

    /*
     * Readiness
     */

    @Test
    public void readyGetReturnsUpWhenTheEntityCountIsNotZero() throws Exception {
        givenProbePath("/health/ready", null);
        Delegator delegator = mock(Delegator.class);
        ModelReader modelReader = mock(ModelReader.class);
        ModelEntity modelEntity = mock(ModelEntity.class);
        GenericHelper helper = mock(GenericHelper.class);
        when(delegator.getModelReader()).thenReturn(modelReader);
        when(modelReader.getModelEntity(READINESS_ENTITY)).thenReturn(modelEntity);
        when(delegator.getEntityHelper(READINESS_ENTITY)).thenReturn(helper);
        when(helper.findCountByCondition(delegator, modelEntity, null, null, null)).thenReturn(42L);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(delegator);

            servlet.service(request, response);

            // The probe must count the framework-tier entity the ping service uses, nothing else.
            verify(modelReader).getModelEntity(READINESS_ENTITY);
            verify(delegator).getEntityHelper(READINESS_ENTITY);
            verify(helper).findCountByCondition(delegator, modelEntity, null, null, null);
        }
        assertProbeResponse(HttpServletResponse.SC_OK, READY_UP);
    }

    @Test
    public void theCountBypassesTheDelegatorsOwnCountApiSoNoLowerLayerLogsTheThrowable() throws Exception {
        // EntityQuery.queryCount() routes through GenericDelegator.findCountByCondition, whose catch
        // block logs the throwable and its stack trace before rethrowing and marks the transaction for
        // rollback. That happens below this class and therefore outside its rate limit, so an anonymous
        // poller could turn one datasource outage into an unbounded stream of stack traces. The probe
        // must reach GenericDAO through the entity helper instead, which logs no throwable at all.
        givenProbePath("/health/ready", null);
        Delegator delegator = delegatorFailingWith(new GenericEntityException("datasource unreachable"));

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(delegator);

            servlet.service(request, response);

            verify(delegator, never()).findCountByCondition(anyString(), any(), any(), any());
            verify(delegator, never()).findCountByCondition(anyString(), any(), any(), any(), any());
        }
        assertProbeResponse(HttpServletResponse.SC_SERVICE_UNAVAILABLE, READY_DOWN);
    }

    @Test
    public void readyHeadReturnsUpWhenTheEntityCountIsNotZero() throws Exception {
        givenProbePath("/health", "/ready");
        givenMethod("HEAD");
        Delegator delegator = delegatorCountingRows(1L);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(delegator);

            servlet.service(request, response);
        }
        assertProbeResponse(HttpServletResponse.SC_OK, READY_UP);
    }

    @Test
    public void readyGetReturnsDownWhenTheReachableDatabaseReportsZeroRows() throws Exception {
        givenProbePath("/health/ready", null);
        Delegator delegator = delegatorCountingRows(0L);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(delegator);

            servlet.service(request, response);
        }
        // A reachable but unpopulated schema is "not ready", exactly as the ping service reports
        // CommonPingDatasourceInvalidCount for a zero count.
        assertProbeResponse(HttpServletResponse.SC_SERVICE_UNAVAILABLE, READY_DOWN);
    }

    @Test
    public void readyGetReturnsDownOnGenericEntityException() throws Exception {
        givenProbePath("/health/ready", null);
        Delegator delegator = delegatorFailingWith(new GenericEntityException("datasource unreachable"));

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(delegator);

            servlet.service(request, response);
        }
        assertProbeResponse(HttpServletResponse.SC_SERVICE_UNAVAILABLE, READY_DOWN);
    }

    @Test
    public void readyGetReturnsDownOnUncheckedQueryFailure() throws Exception {
        givenProbePath("/health/ready", null);
        // An exhausted connection pool surfaces as an unchecked exception; it must not escape.
        Delegator delegator = delegatorFailingWith(new IllegalStateException("pool exhausted"));

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(delegator);

            servlet.service(request, response);
        }
        assertProbeResponse(HttpServletResponse.SC_SERVICE_UNAVAILABLE, READY_DOWN);
    }

    @Test
    public void readyGetReturnsDownWhenTheDelegatorLookupYieldsNull() throws Exception {
        givenProbePath("/health/ready", null);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            // WebAppUtil.getDelegator only logs and returns null when the delegator factory fails.
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(null);

            servlet.service(request, response);

            // No count may be attempted against a null delegator.
            webAppUtil.verify(() -> WebAppUtil.getDelegator(servletContext));
            webAppUtil.verifyNoMoreInteractions();
        }
        assertProbeResponse(HttpServletResponse.SC_SERVICE_UNAVAILABLE, READY_DOWN);
    }

    @Test
    public void readyGetReturnsDownWhenTheDelegatorLookupThrows() throws Exception {
        givenProbePath("/health/ready", null);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(any(ServletContext.class)))
                    .thenThrow(new IllegalStateException("delegator factory failure"));

            servlet.service(request, response);
        }
        // The lookup lives inside the fail-closed boundary, so this is a 503 and never a container 500.
        assertProbeResponse(HttpServletResponse.SC_SERVICE_UNAVAILABLE, READY_DOWN);
    }

    @Test
    public void readyGetReturnsDownWhenTheRequestCannotSupplyAServletContext() throws Exception {
        givenProbePath("/health/ready", null);
        // The context is read from the request, which is the only source available when this class
        // runs as a filter. A container that cannot supply one has to be absorbed by the readiness
        // contract rather than escaping to the container as a 500.
        when(request.getServletContext()).thenThrow(new IllegalStateException("no servlet context"));

        servlet.service(request, response);

        assertProbeResponse(HttpServletResponse.SC_SERVICE_UNAVAILABLE, READY_DOWN);
    }

    @Test
    public void readinessIsAnsweredWithoutTheServletHavingBeenInitialised() throws Exception {
        givenProbePath("/health/ready", null);
        Delegator delegator = delegatorCountingRows(1L);
        // No init(ServletConfig): this is the state the instance is in when the container drives it
        // through the filter lifecycle, where there is no ServletConfig at all. Readiness must still
        // work, which is exactly why the context comes from the request.
        HealthCheckServlet uninitialised = new HealthCheckServlet();

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(delegator);

            uninitialised.service(request, response);
        }
        assertProbeResponse(HttpServletResponse.SC_OK, READY_UP);
    }

    /*
     * Readiness under overlapping probes: one check, one shared verdict, and no DOWN invented
     *
     * A load-balancer target group is probed by one node per Availability Zone, so several readiness
     * probes are in flight at once in normal operation. Answering a healthy instance "not ready"
     * because probes overlapped drains it, which is why every case below asserts that overlapping
     * probes do not change a verdict that exists, and that a verdict is only ever one the datasource
     * produced. The answers the servlet does own - a shed, a full waiter set with nothing published,
     * an expired check deadline - each carry an event code of their own and are pinned separately.
     */

    @Test
    public void overlappingProbesOnAHealthyDatasourceAreAllAnsweredUp() throws Exception {
        // The regression this class exists to prevent: with an admission bound, the third and every
        // later simultaneous probe was answered 503 DOWN while the datasource was demonstrably up.
        int probes = 16;

        BurstOutcome burst = coldStartBurst(probes, SLOW_CHECK_MILLIS, 7L);

        for (int probe = 0; probe < probes; probe++) {
            verify(burst.responses().get(probe)).setStatus(HttpServletResponse.SC_OK);
            verify(burst.responses().get(probe), never()).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            assertEquals(READY_UP, burst.bodies().get(probe).toString(),
                    "probe " + probe + " answered from a shared verdict");
        }
        // One check answered all sixteen: what is bounded is what the datasource sees, not what a probe
        // is allowed to be told, which is the difference between coalescing and an admission bound.
        assertEquals(1L, burst.datasourceChecks(), "overlapping probes must share one check, not queue or shed");
    }

    @Test
    public void aColdStartBurstFillingTheWholeWaiterSetIsStillAnsweredUpByTheOneCheckItShares() throws Exception {
        // The boundary of the same rule, expressed against the bound the servlet declares rather
        // than against a number chosen here, so retuning the bound retunes this case with it. One probe
        // runs the check and every other probe of the burst fits in the waiter set, which is the largest
        // burst that can arrive with no verdict at all and still be answered entirely by the datasource.
        //
        // Sizing the bound is what makes this reachable: a bound at or below the burst a cold start
        // legitimately produces - several health-check nodes per Availability Zone, an orchestrator's
        // own liveness and readiness probes, an operator's curl - would answer the overflow 503 while
        // the datasource was demonstrably up, which is the admission-counter failure in another guise.
        int probes = (int) readinessConstant("READINESS_MAX_WAITERS") + 1;

        BurstOutcome burst = coldStartBurst(probes, SLOW_CHECK_MILLIS, 7L);

        for (int probe = 0; probe < probes; probe++) {
            verify(burst.responses().get(probe)).setStatus(HttpServletResponse.SC_OK);
            verify(burst.responses().get(probe), never()).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            assertEquals(READY_UP, burst.bodies().get(probe).toString(),
                    "probe " + probe + " of a burst that fills the waiter set was answered by the check it waited for");
        }
        assertEquals(1L, burst.datasourceChecks(), "the whole burst must share one check");
        assertWindowStillOpen("READINESS_WAITERS_LOG_LAST_AT",
                "no probe of a burst this size may be refused a waiter slot");
        assertWindowStillOpen("READINESS_SHED_LOG_LAST_AT",
                "and none may give up on the check either, the check having published inside the wait");
        assertEquals(0L, readinessLogCounter("READINESS_WAITERS").get(), "every waiter must release its slot");
    }

    @Test
    public void anEstablishedVerdictAnswersAnOverlappingProbeWithoutTouchingTheDatasource() throws Exception {
        givenProbePath("/health/ready", null);
        // What a burst lands on: a verdict another probe established a few hundred milliseconds ago.
        givenEstablishedVerdict(true, TimeUnit.MILLISECONDS.toNanos(200L));

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class);
                MockedStatic<Debug> debug = mockStatic(Debug.class)) {
            servlet.service(request, response);

            webAppUtil.verifyNoInteractions();
            // Not even the published-delegator lookup runs: the verdict is the whole answer.
            verify(servletContext, never()).getAttribute(anyString());
            debug.verify(() -> Debug.logWarning(anyString(), anyString()), never());
        }
        assertProbeResponse(HttpServletResponse.SC_OK, READY_UP);
    }

    @Test
    public void anEstablishedNotReadyVerdictIsRepeatedWithoutRepeatingItsLogLine() throws Exception {
        givenProbePath("/health/ready", null);
        // A failing check publishes its verdict too, so an outage is reported to every overlapping
        // probe - without the check, and without the event code being written once per probe.
        givenEstablishedVerdict(false, TimeUnit.MILLISECONDS.toNanos(200L));

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class);
                MockedStatic<Debug> debug = mockStatic(Debug.class)) {
            servlet.service(request, response);

            webAppUtil.verifyNoInteractions();
            debug.verify(() -> Debug.logWarning(anyString(), anyString()), never());
        }
        assertProbeResponse(HttpServletResponse.SC_SERVICE_UNAVAILABLE, READY_DOWN);
    }

    @Test
    public void aVerdictThatHasAgedOutIsMeasuredAgainSoReadinessStaysALiveSignal() throws Exception {
        givenProbePath("/health/ready", null);
        Delegator delegator = mock(Delegator.class);
        ModelEntity modelEntity = givenReadinessModel(delegator);
        GenericHelper helper = delegator.getEntityHelper(READINESS_ENTITY);
        when(helper.findCountByCondition(delegator, modelEntity, null, null, null)).thenReturn(7L);
        // Older than the window a verdict answers on its own, which is the state every probe of a
        // normal load-balancer polling interval arrives in.
        givenEstablishedVerdict(true, readinessConstant("READINESS_VERDICT_FRESH_NANOS")
                + TimeUnit.MILLISECONDS.toNanos(50L));

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(delegator);

            servlet.service(request, response);

            // The datasource was measured again rather than the stale verdict being repeated: a
            // verdict that could outlive its window would stop readiness being a live signal.
            verify(helper).findCountByCondition(delegator, modelEntity, null, null, null);
        }
        assertProbeResponse(HttpServletResponse.SC_OK, READY_UP);
    }

    @Test
    public void aProbeArrivingWhileACheckRunsRepeatsTheVerdictThatHasNotBeenRefuted() throws Exception {
        givenProbePath("/health/ready", null);
        // A check is in flight and this probe's verdict is older than the fresh window but still inside
        // the grace window, so it stands: the datasource has not said otherwise.
        givenReadinessCheckRunning();
        givenEstablishedVerdict(true, readinessConstant("READINESS_VERDICT_FRESH_NANOS")
                + TimeUnit.MILLISECONDS.toNanos(100L));

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class);
                MockedStatic<Debug> debug = mockStatic(Debug.class)) {
            servlet.service(request, response);

            // No second query while one is already running, and no event code: this is a normal answer.
            webAppUtil.verifyNoInteractions();
            verify(servletContext, never()).getAttribute(anyString());
            debug.verify(() -> Debug.logWarning(anyString(), anyString()), never());
        }
        assertProbeResponse(HttpServletResponse.SC_OK, READY_UP);
    }

    @Test
    public void aVerdictOlderThanTheGraceWindowIsNotRepeated() throws Exception {
        givenProbePath("/health/ready", null);
        // Nothing recent enough to stand on: a check that has been running longer than the grace
        // window has to be reported as not ready rather than answered with a verdict from before it.
        givenReadinessCheckRunning();
        givenEstablishedVerdict(true, readinessConstant("READINESS_VERDICT_GRACE_NANOS")
                + TimeUnit.SECONDS.toNanos(1L));

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class);
                MockedStatic<Debug> debug = mockStatic(Debug.class)) {
            servlet.service(request, response);

            ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
            debug.verify(() -> Debug.logWarning(lines.capture(), anyString()), times(1));
            assertEquals(SHED_EVENT_CODE, lines.getValue(), "a verdict too old to repeat needs its own code");
            webAppUtil.verifyNoInteractions();
        }
        assertProbeResponse(HttpServletResponse.SC_SERVICE_UNAVAILABLE, READY_DOWN);
    }

    /*
     * A bounded check, a stepped clock, an interrupted wait, and the bounds of the wait itself
     *
     * The permit admits one check at a time, which bounds what the datasource sees but says nothing
     * about how long that one check may take. A connection borrow waits on the pool - the engine's own
     * default leaves that at five minutes - and a query waits on the socket, so without a deadline of
     * its own the check can be outstanding indefinitely, holding the permit and leaving every probe on
     * a stale verdict, a grace answer or a shed. The cases below assert the opposite: the check is
     * bounded, the permit always ends up back where it belongs, and no probe is ever parked on it.
     *
     * The age of a verdict and every rate-limit window are differences of two System.nanoTime()
     * readings, and the wait for a running check is a bounded loop over a third. Each has edge cases
     * no ordinary probe reaches: on the wall clock a backwards step made every window look freshly
     * claimed - silencing the events and honouring a stale verdict for the length of the step - and a
     * forwards step expired every bound at once, both during exactly the sort of incident that also
     * disturbs a host's clock; and a container shutting an instance down interrupts a probe thread
     * mid-wait. The cases below drive each of those, and the last of them pin down that the wait is a
     * real wait and a bounded one, so neither the loop nor the constants that size it can be removed
     * unnoticed.
     */

    @Test
    public void aVerdictDatedInTheFutureIsMeasuredAgainInsteadOfBeingTrustedUntilTheClockCatchesUp() throws Exception {
        givenProbePath("/health/ready", null);
        // What a stepped clock leaves behind. A verdict carries the instant it was established, so a
        // time daemon that moves the clock backwards after one was published leaves that instant in the
        // future. Its age is then negative, and treating a negative age as "recent" would honour the
        // verdict for the whole length of the step - which no window here bounds.
        long step = readinessConstant("READINESS_VERDICT_GRACE_NANOS") * 10L;
        givenEstablishedVerdict(true, -step);
        // Meanwhile the datasource has stopped being ready.
        Delegator delegator = mock(Delegator.class);
        ModelEntity modelEntity = givenReadinessModel(delegator);
        GenericHelper helper = delegator.getEntityHelper(READINESS_ENTITY);
        when(helper.findCountByCondition(delegator, modelEntity, null, null, null)).thenReturn(0L);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class);
                MockedStatic<Debug> debug = mockStatic(Debug.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(delegator);

            servlet.service(request, response);

            // The future-dated verdict was discarded and the datasource measured instead, which is the
            // whole difference: repeating it would have reported this instance ready for as long as the
            // clock step lasted, and a load balancer would have kept sending it traffic.
            verify(helper).findCountByCondition(delegator, modelEntity, null, null, null);
            debug.verify(() -> Debug.logWarning(eq(EMPTY_EVENT_CODE), anyString()));
        }
        assertProbeResponse(HttpServletResponse.SC_SERVICE_UNAVAILABLE, READY_DOWN);
        // And what it was replaced with is dated now, not in the future, so one step cannot poison the
        // verdict for every probe that follows.
        Object published = readinessVerdict().get();
        assertTrue(published != null, "a check that measured the datasource must publish a verdict");
        assertTrue(verdictEstablishedAtNanos(published) - System.nanoTime() <= 0L,
                "the verdict a check publishes must be dated at the instant the datasource was observed");
    }

    @Test
    public void aNotReadyVerdictDatedInTheFutureCannotStrandAHealthyInstanceOutOfService() throws Exception {
        givenProbePath("/health/ready", null);
        // The same clock step, the other way round, and the more damaging direction: a not-ready verdict
        // dated in the future would keep answering 503 long after the datasource recovered, and an
        // instance drained by a clock adjustment does not come back without a restart.
        long step = readinessConstant("READINESS_VERDICT_GRACE_NANOS") * 10L;
        givenEstablishedVerdict(false, -step);
        Delegator delegator = delegatorCountingRows(7L);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class);
                MockedStatic<Debug> debug = mockStatic(Debug.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(delegator);

            servlet.service(request, response);

            // Nothing is logged: measuring the datasource again is the normal answer, not an event.
            debug.verify(() -> Debug.logWarning(anyString(), anyString()), never());
        }
        assertProbeResponse(HttpServletResponse.SC_OK, READY_UP);
        assertTrue(verdictReady(readinessVerdict().get()),
                "the instance measured itself ready again, so the published verdict must say so");
    }

    @Test
    public void aWaitInterruptedByTheContainerRestoresTheFlagAndAnswersFailClosedAtOnce() throws Exception {
        givenProbePath("/health/ready", null);
        // The one path that waits: a check is already running and there is no verdict at all to repeat,
        // which is where a probe arriving during start-up or a rolling deployment lands.
        givenReadinessCheckRunning();
        givenNoEstablishedVerdict();
        long waitMillis = readinessConstant("READINESS_CHECK_WAIT_NANOS") / NANOS_PER_MILLI;

        // Run on a worker thread precisely so it can be interrupted the way the container interrupts a
        // request thread it is taking away. The probe-shed line is written by the real Debug here
        // rather than to a static mock, because Mockito's static mocking is confined to the thread that
        // opened it and would not see anything the worker does - which is why the assertions below read
        // the servlet's own rate-limit counters instead.
        WorkerProbe observed = readinessProbeOnAWorkerThread(true);

        // Thread.sleep CLEARS the interrupt flag when it throws, so the flag still being set when the
        // servlet returned means the servlet put it back. A probe that swallowed the interruption
        // would hand the thread back looking as though it had never been asked to stop, and nothing
        // above it - the container's own shutdown included - would learn otherwise.
        assertTrue(observed.interruptFlagRestored(),
                "an interrupted wait must restore the interrupt flag instead of consuming the interruption");
        // It also stopped waiting there and then rather than serving out the rest of the window: the
        // interruption is the container reclaiming the thread, so continuing to poll would hold it past
        // its notice. Had the interruption been ignored, this would have taken the full wait.
        assertTrue(observed.elapsedMillis() < waitMillis,
                "an interrupted wait returned after " + observed.elapsedMillis() + "ms, no sooner than the "
                        + waitMillis + "ms it would have waited had the interruption been ignored");
        // Fail-closed, and reported under the code that says no verdict could be obtained - not under
        // the one that says the datasource failed, because the datasource was never asked.
        assertProbeResponse(HttpServletResponse.SC_SERVICE_UNAVAILABLE, READY_DOWN);
        assertWindowClaimed("READINESS_SHED_LOG_LAST_AT",
                "a probe that gave up without a verdict must report itself under the probe-shed code");
        assertWindowStillOpen("READINESS_LOG_LAST_AT",
                "it must not consume the window an unavailable datasource needs, having never consulted one");
        assertWindowStillOpen("READINESS_EMPTY_LOG_LAST_AT",
                "nor the window an unpopulated schema needs");
        verify(request, never()).getServletContext();
        verify(servletContext, never()).getAttribute(anyString());
    }

    @Test
    public void aProbeWithNothingToStandOnWaitsTheWholeBoundedWindowForTheRunningCheck() throws Exception {
        givenProbePath("/health/ready", null);
        givenReadinessCheckRunning();
        givenNoEstablishedVerdict();
        long waitMillis = readinessConstant("READINESS_CHECK_WAIT_NANOS") / NANOS_PER_MILLI;

        long startedAt = System.nanoTime();
        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class);
                MockedStatic<Debug> debug = mockStatic(Debug.class)) {
            servlet.service(request, response);

            webAppUtil.verifyNoInteractions();
            debug.verify(() -> Debug.logError(anyString(), anyString()), never());
        }
        long elapsedMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLI;

        // The wait is a real wait. A cold-start probe gives the running check the whole window before
        // it gives up, so a check that is merely slow still answers the probe instead of the probe
        // manufacturing a failure the datasource never reported. A wait loop that was removed - or a
        // window shortened to nothing - returns here in about a millisecond and fails this bound.
        assertTrue(elapsedMillis >= waitMillis - CLOCK_GRANULARITY_MILLIS,
                "a probe with no verdict waited " + elapsedMillis + "ms, less than the " + waitMillis
                        + "ms the running check is entitled to");
        // And it is a bounded one. That bound is the reason waiting is safe at all: an unreachable
        // datasource costs a probe thread this window and nothing like the pool-sleeptime a query of
        // its own could block for. The ceiling is loose because a shared build machine deschedules
        // threads - it is asserting boundedness, not precision.
        assertTrue(elapsedMillis <= waitMillis * BOUNDED_WAIT_TOLERANCE,
                "a probe with no verdict waited " + elapsedMillis + "ms, far beyond the " + waitMillis + "ms bound");
        assertProbeResponse(HttpServletResponse.SC_SERVICE_UNAVAILABLE, READY_DOWN);
    }

    /*
     * The waiter bound: how many container threads readiness may occupy
     *
     * Coalescing the database work bounds what the datasource sees - one check - but on its own it
     * bounds nothing about thread occupancy: every probe with no verdict to stand on waits for the
     * running check, so an anonymous flood could hold a connector thread per probe for the length of
     * that wait and starve real traffic without issuing a single query. The cases below pin the bound
     * that closes it, and pin just as firmly that the bound is NOT an admission counter: a probe over
     * the bound is answered from the shared verdict whenever there is one, so probe concurrency alone
     * cannot drain an instance that has established a verdict. With no verdict to stand on, a probe over
     * the bound is answered 503 under its own event code, which the cases below pin too.
     */

    @Test
    public void aFloodCannotOccupyMoreContainerThreadsThanTheWaiterBoundAllows() throws Exception {
        // Nobody can run a check - the permit is held - and there is no verdict to repeat, so every
        // probe below is on the one path that waits. This is the shape of the defect: without a bound,
        // all of them wait, and with a few thousand of them that is every connector thread.
        givenReadinessCheckRunning();
        givenNoEstablishedVerdict();
        long maxWaiters = readinessConstant("READINESS_MAX_WAITERS");
        long waitMillis = readinessConstant("READINESS_CHECK_WAIT_NANOS") / NANOS_PER_MILLI;
        // Comfortably more probes than the bound admits, so the refused path is genuinely exercised.
        int probes = (int) maxWaiters * 3;
        List<HttpServletResponse> responses = new ArrayList<>();
        List<StringWriter> bodies = new ArrayList<>();
        List<Callable<Long>> probeCalls = new ArrayList<>();
        CyclicBarrier released = new CyclicBarrier(probes);

        for (int probe = 0; probe < probes; probe++) {
            HttpServletRequest concurrentRequest = mock(HttpServletRequest.class);
            when(concurrentRequest.getMethod()).thenReturn("GET");
            when(concurrentRequest.getServletPath()).thenReturn("/health/ready");
            when(concurrentRequest.getServletContext()).thenReturn(servletContext);
            HttpServletResponse concurrentResponse = mock(HttpServletResponse.class);
            StringWriter body = new StringWriter();
            when(concurrentResponse.getWriter()).thenReturn(new PrintWriter(body));
            responses.add(concurrentResponse);
            bodies.add(body);
            probeCalls.add(() -> {
                // The barrier is what makes the arrival simultaneous: no probe proceeds until every
                // one of them is already running and waiting here, so the bound is exercised by a
                // genuine burst rather than by threads trickling in as the pool starts them.
                released.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                long startedAt = System.nanoTime();
                servlet.service(concurrentRequest, concurrentResponse);
                return (System.nanoTime() - startedAt) / NANOS_PER_MILLI;
            });
        }

        List<Long> elapsedPerProbe = new ArrayList<>();
        long peakWaiters;
        ExecutorService pool = Executors.newFixedThreadPool(probes, HealthCheckServletTests::daemonWorker);
        boolean workersOutlivedTheTest;
        try {
            // Sampled from outside, because the invariant being asserted is about the counter itself:
            // it may never be observed above the bound at any instant, whatever the arrival pattern.
            WaiterSampler sampler = startWaiterSampler();
            try {
                List<Future<Long>> answered = new ArrayList<>();
                for (Callable<Long> probeCall : probeCalls) {
                    answered.add(pool.submit(probeCall));
                }
                for (Future<Long> probe : answered) {
                    elapsedPerProbe.add(probe.get(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                }
            } finally {
                peakWaiters = sampler.stopAndReadPeak();
            }
        } finally {
            pool.shutdownNow();
            workersOutlivedTheTest = !pool.awaitTermination(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        assertFalse(workersOutlivedTheTest, "every probe thread must have terminated before the test ends");

        // The bound is a property of the state, not of the arrival pattern: the counter is never above
        // it, even though three times as many probes tried to claim a slot at the same instant.
        assertTrue(peakWaiters <= maxWaiters, "the waiter count reached " + peakWaiters + ", above the bound of "
                + maxWaiters + ", so a flood can still occupy an unbounded number of container threads");
        // And it was actually reached, so what follows is measuring the refused path rather than a
        // burst that happened to fit inside the bound.
        assertEquals(maxWaiters, peakWaiters, "the burst was expected to fill the waiter set");

        long occupied = elapsedPerProbe.stream().filter(elapsed -> elapsed >= waitMillis - CLOCK_GRANULARITY_MILLIS).count();
        assertTrue(occupied <= maxWaiters, occupied + " of " + probes + " probes held a thread for the whole "
                + waitMillis + "ms wait, more than the " + maxWaiters + " the bound admits");
        assertTrue(occupied > 0L, "no probe waited at all, so this measured something other than the waiting path");
        // Every slot was handed back, so the bound is restored for the probes that follow rather than
        // shrinking by one per flood until readiness never waits again.
        assertEquals(0L, readinessLogCounter("READINESS_WAITERS").get(),
                "every waiter must release its slot, otherwise the bound shrinks for the life of the JVM");

        for (int probe = 0; probe < probes; probe++) {
            // Fail-closed for all of them, which is the honest answer here: this JVM has never
            // completed a readiness check, so nothing has established that it can serve.
            verify(responses.get(probe)).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            assertEquals(READY_DOWN, bodies.get(probe).toString(), "probe " + probe + " answered fail-closed");
        }
        // Not one of them touched the datasource: waiting costs no pooled connection, and being
        // refused a slot costs not even that.
        verify(servletContext, never()).getAttribute(anyString());
        // Both conditions are reported, each under its own code, so an operator can tell the probes
        // that waited out a slow check from the probes there was no room to wait for it.
        assertWindowClaimed("READINESS_SHED_LOG_LAST_AT",
                "the probes that waited and timed out must be reported under the probe-shed code");
        assertWindowClaimed("READINESS_WAITERS_LOG_LAST_AT",
                "the probes refused a slot must be reported under the waiters-full code");
        assertWindowStillOpen("READINESS_LOG_LAST_AT",
                "neither may be reported as a datasource failure, the datasource having never been consulted");
    }

    @Test
    public void aProbeRefusedAWaiterSlotIsAnsweredInConstantTimeInsteadOfOccupyingAThread() throws Exception {
        givenProbePath("/health/ready", null);
        givenWaiterSetFull();
        givenReadinessCheckRunning();
        givenNoEstablishedVerdict();
        long waitMillis = readinessConstant("READINESS_CHECK_WAIT_NANOS") / NANOS_PER_MILLI;

        long startedAt = System.nanoTime();
        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class);
                MockedStatic<Debug> debug = mockStatic(Debug.class)) {
            servlet.service(request, response);

            webAppUtil.verifyNoInteractions();
            // Debug is mocked for two reasons, and this states the second one. The first is that the log
            // write happens inside the interval being measured, so a real appender would be timed along
            // with the probe. The second is that being refused a slot is a capacity condition rather than
            // a fault: it is reported as a warning under its own code, never as an error.
            debug.verify(() -> Debug.logError(anyString(), anyString()), never());
        }
        long elapsedMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLI;

        // The discriminating comparison against the waiting path, which the test above measures at no
        // less than the whole window: this probe must not wait at all. Answering in constant time is
        // what bounds occupancy, since a refused probe is precisely the one there is no room for.
        assertTrue(elapsedMillis < waitMillis, "a probe refused a slot took " + elapsedMillis
                + "ms, as long as the " + waitMillis + "ms wait it was supposed to skip");
        assertProbeResponse(HttpServletResponse.SC_SERVICE_UNAVAILABLE, READY_DOWN);
        // It neither claimed a slot nor released one it never held. A stray decrement here would let
        // each refused probe raise the effective bound, which is the bug that turns a bound into none.
        assertEquals(readinessConstant("READINESS_MAX_WAITERS"), readinessLogCounter("READINESS_WAITERS").get(),
                "a refused probe must leave the waiter count exactly as it found it");
    }

    @ParameterizedTest(name = "a full waiter set repeats an established {0} verdict")
    @CsvSource({"ready, 200", "not-ready, 503"})
    public void aFullWaiterSetNeverChangesAnAnswerTheDatasourceAlreadyProduced(String verdict, int expectedStatus)
            throws Exception {
        // The trade this bound must not reintroduce. An admission counter answered the probes it
        // refused with a manufactured DOWN, and since a multi-Availability-Zone target group has
        // several probes in flight at once, those refusals landed in normal operation and drained
        // healthy instances. So a full waiter set may change how a probe is answered - without waiting -
        // but never what it is answered with, as long as a verdict exists at all.
        givenProbePath("/health/ready", null);
        givenWaiterSetFull();
        givenReadinessCheckRunning();
        boolean ready = "ready".equals(verdict);
        // Older than the fresh window and inside the grace window: the state a probe lands in while a
        // check it is not allowed to wait for is refreshing a verdict that has not been refuted.
        givenEstablishedVerdict(ready, readinessConstant("READINESS_VERDICT_FRESH_NANOS") * 2L);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class);
                MockedStatic<Debug> debug = mockStatic(Debug.class)) {
            servlet.service(request, response);

            webAppUtil.verifyNoInteractions();
            verify(servletContext, never()).getAttribute(anyString());
            // Repeating a verdict is a normal answer, so nothing is logged - a full waiter set is not
            // by itself an event, and reporting it as one would turn a flood into log amplification.
            debug.verify(() -> Debug.logWarning(anyString(), anyString()), never());
        }
        assertProbeResponse(expectedStatus, ready ? READY_UP : READY_DOWN);
        assertWindowStillOpen("READINESS_WAITERS_LOG_LAST_AT",
                "a probe answered from a real verdict must not be reported as one there was no room for");
    }

    @Test
    public void aProbeRefusedASlotWithNoVerdictAtAllIsReportedUnderItsOwnCode() throws Exception {
        givenProbePath("/health/ready", null);
        // The only state in which the bound answers not-ready: the waiter set is full, a check is
        // running, and this JVM has never completed one - so nothing has established that it can serve.
        givenWaiterSetFull();
        givenReadinessCheckRunning();
        givenNoEstablishedVerdict();

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class);
                MockedStatic<Debug> debug = mockStatic(Debug.class)) {
            servlet.service(request, response);

            ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
            debug.verify(() -> Debug.logWarning(lines.capture(), anyString()), times(1));
            assertEquals(WAITERS_FULL_EVENT_CODE, lines.getValue(),
                    "a probe refused a waiter slot needs its own code: the operator action is to find out what is"
                            + " sending that many simultaneous probes, not to look at the database");
            debug.verify(() -> Debug.logError(anyString(), anyString()), never());
            webAppUtil.verify(() -> WebAppUtil.getDelegator(servletContext), never());
            verify(servletContext, never()).getAttribute(anyString());
        }
        assertProbeResponse(HttpServletResponse.SC_SERVICE_UNAVAILABLE, READY_DOWN);
    }

    @Test
    public void theRefusedProbeCodeKeepsItsOwnWindowAndSuppressedCountAndIsFlushedLikeTheOthers() throws Exception {
        givenProbePath("/health/ready", null);
        givenWaiterSetFull();
        givenReadinessCheckRunning();
        Delegator healthy = delegatorCountingRows(7L);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class);
                MockedStatic<Debug> debug = mockStatic(Debug.class)) {
            for (int probe = 0; probe < 3; probe++) {
                givenNoEstablishedVerdict();
                servlet.service(request, response);
            }
            assertEquals(2L, readinessLogCounter("READINESS_WAITERS_LOG_SUPPRESSED").get(),
                    "two of the three refusals must have been held back by this code's own rate limit");
            assertEquals(0L, readinessLogCounter("READINESS_SHED_LOG_SUPPRESSED").get(),
                    "none of them may be counted onto the probe-shed line, no probe having waited");
            assertEquals(0L, readinessLogCounter("READINESS_LOG_SUPPRESSED").get(),
                    "nor onto the datasource line, the datasource having never been consulted");
            assertEquals(0L, readinessLogCounter("READINESS_EMPTY_LOG_SUPPRESSED").get(), "nor onto the empty-schema line");

            // The flood stops and the instance is healthy again, so no further refusal will ever carry
            // the count. A later probe still has to account for it once the window has elapsed.
            readinessLogCounter("READINESS_WAITERS").set(0L);
            readinessLogCounter("READINESS_CHECK_RUNNING").set(0L);
            reopenWindow("READINESS_WAITERS_LOG_LAST_AT");
            when(servletContext.getAttribute("delegator")).thenReturn(healthy);
            givenNoEstablishedVerdict();
            servlet.service(request, response);

            ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
            debug.verify(() -> Debug.logWarning(lines.capture(), anyString()), times(2));
            assertEquals(WAITERS_FULL_EVENT_CODE, lines.getAllValues().get(0), "the first line of a flood");
            assertEquals(WAITERS_FULL_EVENT_CODE + " (2 further occurrences suppressed)", lines.getAllValues().get(1),
                    "the recovered probe must write out what this code's limit held back");
            // States why WebAppUtil is mocked here: the three refused probes resolve no delegator at all, and
            // the recovered one takes the delegator the servlet context publishes, so the lookup that would
            // touch the component container is never reached on any of the four.
            webAppUtil.verify(() -> WebAppUtil.getDelegator(servletContext), never());
        }
        assertEquals(0L, readinessLogCounter("READINESS_WAITERS_LOG_SUPPRESSED").get(),
                "the flushed count must be cleared, so it can never be reported twice");
        verify(response).setStatus(HttpServletResponse.SC_OK);
    }

    @Test
    public void aWaiterSlotIsReleasedWhenTheContainerInterruptsTheWaitSoTheBoundCannotShrink() throws Exception {
        givenProbePath("/health/ready", null);
        givenReadinessCheckRunning();
        givenNoEstablishedVerdict();

        // Interrupted the way the container interrupts a request thread it is reclaiming - the one case
        // in which the waiting probe leaves by a path other than returning normally.
        WorkerProbe observed = readinessProbeOnAWorkerThread(true);

        assertTrue(observed.interruptFlagRestored(),
                "an interrupted wait must restore the interrupt flag instead of consuming the interruption");
        // The slot is given back through a finally, so a thread taken away mid-wait cannot leak one.
        // Leaking even one per interrupted probe would lower the bound irreversibly, and a rolling
        // deployment interrupts request threads on every instance it replaces.
        assertEquals(0L, readinessLogCounter("READINESS_WAITERS").get(),
                "an interrupted waiter must still release its slot, otherwise the bound decays over the JVM's life");
        assertProbeResponse(HttpServletResponse.SC_SERVICE_UNAVAILABLE, READY_DOWN);
    }

    @Test
    public void theCheckRunsOnItsOwnThreadSoNoRequestThreadIsParkedOnTheDatasource() throws Exception {
        // The production executor, not the inline one the other tests install: what is asserted here is
        // the boundary itself, which is what makes a deadline possible at all.
        HealthCheckServlet.installCheckExecutorForTesting(null);
        givenProbePath("/health/ready", null);
        Delegator delegator = mock(Delegator.class);
        ModelEntity modelEntity = givenReadinessModel(delegator);
        AtomicReference<String> countingThread = new AtomicReference<>(null);
        when(delegator.getEntityHelper(READINESS_ENTITY).findCountByCondition(delegator, modelEntity, null, null, null))
                .thenAnswer(invocation -> {
                    countingThread.set(Thread.currentThread().getName());
                    return 7L;
                });
        // Published on the ServletContext the way ContextFilter.init() publishes it, so the check needs
        // no per-thread seam - which a check running on another thread could not see in any case.
        when(servletContext.getAttribute("delegator")).thenReturn(delegator);
        String probeThread = Thread.currentThread().getName();

        servlet.service(request, response);

        assertEquals(CHECK_THREAD_NAME, countingThread.get(),
                "the count must run on the dedicated check thread, which is what the deadline is applied to");
        assertNotEquals(probeThread, countingThread.get(),
                "a count on the request thread could not be bounded: that is the whole point of the boundary");
        assertProbeResponse(HttpServletResponse.SC_OK, READY_UP);
    }

    @Test
    public void aCheckThatOutrunsItsDeadlineReleasesTheProbeInsteadOfParkingIt() throws Exception {
        // A datasource that has stopped answering: the borrow or the socket read never returns, so the
        // check stays outstanding. What is measured here is the probe, which must come back after the
        // deadline and not after the pool's five-minute borrow wait.
        HealthCheckServlet.installCheckExecutorForTesting(null);
        givenProbePath("/health/ready", null);
        Delegator delegator = mock(Delegator.class);
        ModelEntity modelEntity = givenReadinessModel(delegator);
        CountDownLatch wedged = new CountDownLatch(1);
        CountDownLatch counting = new CountDownLatch(1);
        when(delegator.getEntityHelper(READINESS_ENTITY).findCountByCondition(delegator, modelEntity, null, null, null))
                .thenAnswer(invocation -> {
                    counting.countDown();
                    wedged.await();
                    return 7L;
                });
        when(servletContext.getAttribute("delegator")).thenReturn(delegator);
        long deadlineMillis = readinessConstant("READINESS_CHECK_DEADLINE_MILLIS");

        long startedAt = System.nanoTime();
        try {
            servlet.service(request, response);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertTrue(counting.await(5L, TimeUnit.SECONDS), "the check must have reached the datasource");
            // Bounded above: the probe returned on the deadline rather than on the datasource. The
            // allowance is generous because a shared build host schedules threads as it pleases; the
            // claim being made is that the probe is not waiting on the count, and the count is still
            // running when the probe answers.
            assertTrue(elapsedMillis < deadlineMillis * 4L,
                    "the probe must return on its own deadline, not on the datasource: " + elapsedMillis + "ms");
            // Bounded below: the deadline is a real interval and was not abandoned instantly, which
            // would make the fallbacks the normal path for every healthy but unhurried check.
            assertTrue(elapsedMillis >= deadlineMillis,
                    "the probe must wait the deadline it declares: " + elapsedMillis + "ms");
            // The check keeps the permit while it is still outstanding, so no second count is started
            // against a datasource that is already failing to answer the first.
            assertTrue(readinessCheckPermitHeld(), "an outstanding check must keep the permit it holds");
        } finally {
            wedged.countDown();
        }
        assertProbeResponse(HttpServletResponse.SC_SERVICE_UNAVAILABLE, READY_DOWN);
    }

    @Test
    public void aCheckThatOutrunsItsDeadlineIsReportedUnderItsOwnCode() throws Exception {
        HealthCheckServlet.installCheckExecutorForTesting(ProbeCheckExecutor.neverCompleting());
        givenProbePath("/health/ready", null);
        // No verdict at all to fall back on, which is the state the first probes after a start-up
        // against an unreachable datasource arrive in.
        givenNoEstablishedVerdict();

        try (MockedStatic<Debug> debug = mockStatic(Debug.class)) {
            servlet.service(request, response);

            ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
            debug.verify(() -> Debug.logWarning(lines.capture(), anyString()), times(1));
            assertEquals(TIMEOUT_EVENT_CODE, lines.getValue(),
                    "a check that did not answer in time must be reported under its own code: an alert can"
                            + " then tell a slow check from a database that needs attention");
            debug.verify(() -> Debug.logError(anyString(), anyString()), never());
        }
        assertProbeResponse(HttpServletResponse.SC_SERVICE_UNAVAILABLE, READY_DOWN);
        assertWindowStillOpen("READINESS_LOG_LAST_AT",
                "a timeout must not consume the rate-limit window an unavailable datasource needs");
        assertWindowStillOpen("READINESS_SHED_LOG_LAST_AT", "nor the one a shed probe needs");
    }

    @Test
    public void aTimedOutCheckKeepsItsOwnWindowAndItsOwnSuppressedCount() throws Exception {
        HealthCheckServlet.installCheckExecutorForTesting(ProbeCheckExecutor.neverCompleting());
        givenProbePath("/health/ready", null);

        try (MockedStatic<Debug> debug = mockStatic(Debug.class)) {
            givenNoEstablishedVerdict();
            readinessLogCounter("READINESS_CHECK_RUNNING").set(0L);
            servlet.service(request, response);
            givenNoEstablishedVerdict();
            readinessLogCounter("READINESS_CHECK_RUNNING").set(0L);
            servlet.service(request, response);

            debug.verify(() -> Debug.logWarning(anyString(), anyString()), times(1));
        }
        assertEquals(1L, readinessLogCounter("READINESS_TIMEOUT_LOG_SUPPRESSED").get(),
                "the second timeout must be counted onto its own code");
        assertEquals(0L, readinessLogCounter("READINESS_LOG_SUPPRESSED").get(),
                "it must never be reported on the datasource line as if the database had failed");
        assertEquals(0L, readinessLogCounter("READINESS_SHED_LOG_SUPPRESSED").get(),
                "nor on the line for a probe that had no check of its own to wait for");
    }

    @Test
    public void aTimedOutCheckStillHonoursAVerdictThatHasNotBeenRefuted() throws Exception {
        HealthCheckServlet.installCheckExecutorForTesting(ProbeCheckExecutor.neverCompleting());
        givenProbePath("/health/ready", null);
        // A slow check is not evidence that the datasource is down. While the last verdict is inside its
        // grace window it still stands, and the timeout is a non-event an operator must not be paged for.
        givenEstablishedVerdict(true, TimeUnit.SECONDS.toNanos(2L));

        try (MockedStatic<Debug> debug = mockStatic(Debug.class)) {
            servlet.service(request, response);

            debug.verify(() -> Debug.logWarning(anyString(), anyString()), never());
        }
        assertProbeResponse(HttpServletResponse.SC_OK, READY_UP);
    }

    @Test
    public void aCheckThatFinishesReleasesThePermitItselfSoTheNextProbeCanMeasureAgain() throws Exception {
        givenProbePath("/health/ready", null);
        Delegator delegator = delegatorCountingRows(7L);
        when(servletContext.getAttribute("delegator")).thenReturn(delegator);

        servlet.service(request, response);

        // The permit tracks the CHECK, not the probe that started it, and a check that finished has to
        // hand it back - otherwise the first probe of a JVM would be the last one ever to measure.
        assertFalse(readinessCheckPermitHeld(), "a finished check must release the permit it holds");
        assertProbeResponse(HttpServletResponse.SC_OK, READY_UP);
    }

    @Test
    public void aFailingCheckAlsoReleasesThePermitSoAnOutageCanBeObservedToEnd() throws Exception {
        givenProbePath("/health/ready", null);
        Delegator delegator = delegatorFailingWith(new GenericEntityException("datasource unreachable"));
        when(servletContext.getAttribute("delegator")).thenReturn(delegator);

        servlet.service(request, response);

        // A check that failed has still finished. Holding the permit here would freeze readiness on the
        // failure for the life of the JVM, so a datasource that recovers could never be reported ready.
        assertFalse(readinessCheckPermitHeld(), "a failed check must release the permit just as a healthy one does");
        assertProbeResponse(HttpServletResponse.SC_SERVICE_UNAVAILABLE, READY_DOWN);
    }

    @Test
    public void aRefusedSubmissionReleasesThePermitAndIsReportedNotReady() throws Exception {
        HealthCheckServlet.installCheckExecutorForTesting(ProbeCheckExecutor.refusing());
        givenProbePath("/health/ready", null);

        try (MockedStatic<Debug> debug = mockStatic(Debug.class)) {
            servlet.service(request, response);

            ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
            debug.verify(() -> Debug.logWarning(lines.capture(), anyString()), times(1));
            assertEquals(EVENT_CODE, lines.getValue(), "a check that could not be started at all is an outage");
        }
        // No task exists to hand the permit back, so the probe has to; leaving it held would wedge
        // readiness on its fallbacks for the life of the JVM.
        assertFalse(readinessCheckPermitHeld(), "a submission that was refused must not leave the permit held");
        assertProbeResponse(HttpServletResponse.SC_SERVICE_UNAVAILABLE, READY_DOWN);
    }

    @Test
    public void aCheckThatDiedIsReportedNotReadyRatherThanEscapingToTheContainer() throws Exception {
        // An Error escaping the check, or a failure in publishing its verdict: the probe has to absorb
        // it into the 503 its contract promises rather than let the container render an error page on
        // what is deliberately an unauthenticated path.
        HealthCheckServlet.installCheckExecutorForTesting(ProbeCheckExecutor.dying());
        givenProbePath("/health/ready", null);

        try (MockedStatic<Debug> debug = mockStatic(Debug.class)) {
            servlet.service(request, response);

            ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
            debug.verify(() -> Debug.logWarning(lines.capture(), anyString()), times(1));
            assertEquals(EVENT_CODE, lines.getValue(), "a check that died is reported as the datasource being unavailable");
            // The throwable itself is never written: an entity-engine or JDBC message names the
            // connection URI and the failing SQL, and this path is reachable by an anonymous caller.
            debug.verify(() -> Debug.logError(anyString(), anyString()), never());
            debug.verify(() -> Debug.logError(any(Throwable.class), anyString()), never());
            debug.verify(() -> Debug.logWarning(any(Throwable.class), anyString()), never());
        }
        assertProbeResponse(HttpServletResponse.SC_SERVICE_UNAVAILABLE, READY_DOWN);
    }

    @Test
    public void aVerdictsAgeIsMeasuredOnTheMonotonicClockAndNotOnTheWallClock() throws Exception {
        givenProbePath("/health/ready", null);
        // A verdict established at this instant, recorded the way the servlet records it: as the
        // System.nanoTime() reading it was established at. The two clocks have unrelated origins, so an
        // implementation that aged this against System.currentTimeMillis() would compute an age of
        // decades, discard a verdict that is microseconds old and measure the datasource again.
        givenEstablishedVerdict(true, 0L);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            servlet.service(request, response);

            webAppUtil.verifyNoInteractions();
            verify(servletContext, never()).getAttribute(anyString());
        }
        assertProbeResponse(HttpServletResponse.SC_OK, READY_UP);
    }

    @Test
    public void aRateLimitWindowIsAgedOnTheMonotonicClockAndNotOnTheWallClock() throws Exception {
        givenProbePath("/health/ready", null);
        Delegator delegator = delegatorFailingWith(new GenericEntityException("datasource unreachable"));
        when(servletContext.getAttribute("delegator")).thenReturn(delegator);
        // A window claimed at this instant, recorded the way the servlet records it. An implementation
        // that aged it against the wall clock would see a whole epoch elapse, reopen the window and
        // write a line per probe - which is the amplification the rate limit exists to prevent.
        readinessLogCounter("READINESS_LOG_LAST_AT").set(System.nanoTime());

        try (MockedStatic<Debug> debug = mockStatic(Debug.class)) {
            servlet.service(request, response);

            debug.verify(() -> Debug.logWarning(anyString(), anyString()), never());
        }
        assertEquals(1L, readinessLogCounter("READINESS_LOG_SUPPRESSED").get(),
                "the occurrence must have been suppressed and counted, not written");
        assertProbeResponse(HttpServletResponse.SC_SERVICE_UNAVAILABLE, READY_DOWN);
    }

    @Test
    public void aClaimedWindowIsRecognisableAsClaimedAndAnUnclaimedOneAsOpen() throws Exception {
        // The two states the whole rate limit turns on, asserted directly so that the helpers the tests
        // above rely on are themselves pinned: a freshly started JVM has every window open, and one
        // occurrence closes exactly the window that reported it.
        givenProbePath("/health/ready", null);
        Delegator delegator = delegatorFailingWith(new GenericEntityException("datasource unreachable"));
        when(servletContext.getAttribute("delegator")).thenReturn(delegator);
        assertWindowStillOpen("READINESS_LOG_LAST_AT", "a freshly reset window must be open");

        servlet.service(request, response);

        assertWindowClaimed("READINESS_LOG_LAST_AT", "the reported occurrence must have claimed its window");
        assertWindowStillOpen("READINESS_EMPTY_LOG_LAST_AT", "and must not have claimed another code's window");
    }

    @ParameterizedTest(name = "deadline property [{0}] resolves to {1}")
    @CsvSource(nullValues = "NULL", value = {
        "NULL, 2000",
        "'', 2000",
        "'   ', 2000",
        "1500, 1500",
        "' 1500 ', 1500",
        "100, 100",
        "10000, 10000",
        "99, 2000",
        "10001, 2000",
        "0, 2000",
        "-1, 2000",
        "abc, 2000",
        "1500ms, 2000",
        "9999999999999999999999, 2000" })
    public void anUnusableCheckDeadlineIsRefusedInFavourOfTheDefault(String configured, long expected) throws Exception {
        // Honouring an out-of-range deadline silently fails in both directions: a few milliseconds
        // abandons every healthy check and makes the fallbacks the normal path, while minutes stop the
        // deadline bounding anything at all, which is the only reason it exists.
        String previous = System.getProperty(DEADLINE_PROPERTY);
        try (MockedStatic<Debug> debug = mockStatic(Debug.class)) {
            if (configured == null) {
                System.clearProperty(DEADLINE_PROPERTY);
            } else {
                System.setProperty(DEADLINE_PROPERTY, configured);
            }

            assertEquals(expected, resolveConfiguredDeadline(), "the deadline resolved from [" + configured + "]");

            // A refusal is never silent: an operator who set a value has to be told it was not taken.
            debug.verify(() -> Debug.logWarning(anyString(), anyString()),
                    times(expected == 2000L && configured != null && !configured.isBlank() ? 1 : 0));
        } finally {
            if (previous == null) {
                System.clearProperty(DEADLINE_PROPERTY);
            } else {
                System.setProperty(DEADLINE_PROPERTY, previous);
            }
        }
    }

    @Test
    public void theDeadlineInForceIsWithinTheRangeTheClassAccepts() throws Exception {
        long deadline = readinessConstant("READINESS_CHECK_DEADLINE_MILLIS");

        assertTrue(deadline >= readinessConstant("READINESS_CHECK_DEADLINE_MIN_MILLIS")
                && deadline <= readinessConstant("READINESS_CHECK_DEADLINE_MAX_MILLIS"),
                "the deadline actually in force must be inside the accepted range: " + deadline + "ms");
        // The deadline has to be shorter than the window a verdict is honoured for, otherwise a probe
        // could still be waiting on a check when the last verdict it could fall back on has expired.
        assertTrue(TimeUnit.MILLISECONDS.toNanos(deadline) < readinessConstant("READINESS_VERDICT_GRACE_NANOS"),
                "the deadline must be shorter than the grace window it falls back on");
    }

    /*
     * Unmapped, empty, malformed and look-alike sub-paths
     */

    @ParameterizedTest(name = "servletPath={0} pathInfo={1} -> 404")
    @CsvSource(nullValues = "NULL", value = {
        "NULL, NULL",
        "'', NULL",
        "/health, NULL",
        "/health/, NULL",
        "/health/live/, NULL",
        "/health/liveness, NULL",
        "/health/livex, NULL",
        "/health/ready2, NULL",
        "/health/readiness, NULL",
        "/HEALTH/LIVE, NULL",
        "/healthz/live, NULL",
        "/health//live, NULL",
        "/health/bogus, NULL",
        "/control/main, NULL",
        "/health, /bogus" })
    public void unmappedSubPathReturnsNotFoundAndNeverTouchesTheDatabase(String servletPath, String pathInfo) throws Exception {
        givenProbePath(servletPath, pathInfo);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            servlet.service(request, response);

            webAppUtil.verifyNoInteractions();
        }
        // A mis-configured probe must fail visibly instead of reporting a false 200, which would
        // keep a broken instance in a load-balancer target group.
        assertProbeResponse(HttpServletResponse.SC_NOT_FOUND, UNKNOWN);
    }

    /*
     * Filter role: a probe is answered ahead of the webapp's filter chain
     *
     * The same class is registered twice - as a Filter on the two exact probe paths, mapped FIRST, and
     * as a servlet on those same two paths. The filter registration is what keeps a probe out of the
     * ordinary chain, and the two things that chain would otherwise do to every probe of every instance
     * are why it matters: ControlFilter and ContextFilter both call getSession() unconditionally, so
     * each anonymous probe minted a session that lived until it expired, and ContextFilter calls
     * WebAppUtil.setAttributesFromRequestBody, which reads a declared application/json body of any size
     * into a String and then into a Map before any servlet is reached.
     *
     * These tests therefore assert the two halves of the short-circuit: a probe is answered here and
     * chain.doFilter is NOT called, and everything else is passed down the chain untouched. The probe
     * paths are deliberately NOT in ControlFilter's allowedPaths - that list is matched with startsWith,
     * so a /health entry would grant passage to every /health* spelling - which HealthEndpointRegistration
     * Tests asserts from the descriptor itself.
     */

    @ParameterizedTest(name = "{0} is answered by the filter without the chain being called")
    @ValueSource(strings = {"/health/live", "/health/ready"})
    public void aProbeIsAnsweredFromTheFilterWithoutTheChainEverBeingCalled(String probePath) throws Exception {
        givenProbePath(probePath, null);
        // A verdict inside the fresh window, so readiness answers without a datasource and this measures
        // the short-circuit alone.
        givenEstablishedVerdict(true, 0L);
        FilterChain chain = mock(FilterChain.class);

        servlet.doFilter(request, response, chain);

        // The whole point of the filter registration: nothing downstream of it runs for a probe.
        verifyNoInteractions(chain);
        assertProbeResponse(HttpServletResponse.SC_OK, "/health/live".equals(probePath) ? LIVE_UP : READY_UP);
    }

    @Test
    public void aProbeAnsweredFromTheFilterTouchesNothingButThePathTheMethodAndTheBodyHeaders() throws Exception {
        givenProbePath("/health/live", null);
        FilterChain chain = mock(FilterChain.class);

        servlet.doFilter(request, response, chain);

        // The exhaustive list of what the filter role is allowed to look at, closed by
        // verifyNoMoreInteractions. A session, a principal, a role, an attribute, a parameter or an
        // input stream appearing here later fails this test - and a session is exactly what the chain
        // this filter replaces was creating for every anonymous probe.
        verify(request).getServletPath();
        verify(request).getPathInfo();
        // Once, not twice: the filter role calls the shared handler directly, so HttpServlet.service
        // never runs and never re-reads the method to choose a dispatch.
        verify(request).getMethod();
        verify(request).getContentLengthLong();
        verify(request).getHeader("Transfer-Encoding");
        verifyNoMoreInteractions(request);
        verifyNoInteractions(chain);
        // No cookie either. A probe client returns nothing, so anything set here would be minted afresh
        // on every poll of every target-group health-check node.
        verify(response, never()).addCookie(any());
        assertProbeResponse(HttpServletResponse.SC_OK, LIVE_UP);
    }

    @Test
    public void anOrdinaryProbeAnsweredFromTheFilterWritesNothingToTheLog() throws Exception {
        givenProbePath("/health/live", null);
        FilterChain chain = mock(FilterChain.class);

        try (MockedStatic<Debug> debug = mockStatic(Debug.class)) {
            servlet.doFilter(request, response, chain);

            // A continuously polled endpoint that logged a line per probe would be the log amplifier,
            // and a probe traversing the chain reaches the exception ControlFilter raises for a path
            // no request map knows. Answering here means there is nothing to log.
            debug.verifyNoInteractions();
        }
        verifyNoInteractions(chain);
        assertProbeResponse(HttpServletResponse.SC_OK, LIVE_UP);
    }

    @ParameterizedTest(name = "{0} is passed down the chain rather than answered")
    @CsvSource(nullValues = "NULL", value = {
        "/health, NULL",
        "/health/, NULL",
        "/health/live/, NULL",
        "/health/liveness, NULL",
        "/healthz/live, NULL",
        "/health//live, NULL",
        "/HEALTH/LIVE, NULL",
        "/control/main, NULL",
        "/health, /bogus" })
    public void aRequestThatIsNotAProbeIsPassedStraightDownTheChainUntouched(String servletPath, String pathInfo)
            throws Exception {
        givenProbePath(servletPath, pathInfo);
        FilterChain chain = mock(FilterChain.class);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            servlet.doFilter(request, response, chain);

            webAppUtil.verifyNoInteractions();
        }
        // Passed on unchanged, so a path this filter's mapping happens to cover but that is not one of
        // the two probes is served by whatever the descriptor says should serve it.
        verify(chain).doFilter(request, response);
        verifyNoMoreInteractions(chain);
        // Nothing is written, so the filter cannot commit a response the rest of the chain then tries to
        // add to, and the decision is taken from the path alone - the method is not even consulted.
        verifyNoInteractions(response);
        verify(request, never()).getMethod();
        assertEquals("", responseBody.toString(), "a request the filter passes on must have no body written by it");
    }

    @Test
    public void anExchangeThatIsNotHttpIsPassedDownTheChainUntouched() throws Exception {
        // A filter is declared against a url-pattern, not against a protocol, so the container may drive
        // it with a plain ServletRequest. This class has nothing to say about one, and casting blindly
        // would turn it into a ClassCastException on a path that is not even a probe.
        ServletRequest plainRequest = mock(ServletRequest.class);
        ServletResponse plainResponse = mock(ServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        servlet.doFilter(plainRequest, plainResponse, chain);

        verify(chain).doFilter(plainRequest, plainResponse);
        verifyNoMoreInteractions(chain);
        verifyNoInteractions(plainRequest);
        verifyNoInteractions(plainResponse);
    }

    @ParameterizedTest(name = "{0} /health/live -> 405 from the filter, chain untouched")
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE", "CONNECT", "get", "Head", "PROPFIND"})
    public void theMethodGateIsEnforcedInTheFilterRoleToo(String method) throws Exception {
        givenProbePath("/health/live", null);
        givenMethod(method);
        FilterChain chain = mock(FilterChain.class);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            servlet.doFilter(request, response, chain);

            webAppUtil.verifyNoInteractions();
        }
        // The gate is the shared one, so the filter role cannot answer a method the servlet role refuses -
        // and refusing it here rather than passing it on keeps a POST to a probe path out of the chain
        // too, which is where the unbounded body read lives.
        verifyNoInteractions(chain);
        verify(response).setHeader(ALLOW_HEADER, ALLOW_VALUE);
        assertProbeResponse(HttpServletResponse.SC_METHOD_NOT_ALLOWED, UNKNOWN);
    }

    @Test
    public void aBodyBearingProbeIsRefusedByTheFilterFromItsHeadersAndNeverPassedOn() throws Exception {
        givenProbePath("/health/ready", null);
        when(request.getContentLengthLong()).thenReturn(1L);
        FilterChain chain = mock(FilterChain.class);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            servlet.doFilter(request, response, chain);

            // Refused before the datasource is reached, from a header lookup, and not handed to the
            // chain - where setAttributesFromRequestBody would have read it into the heap in full.
            webAppUtil.verifyNoInteractions();
        }
        verifyNoInteractions(chain);
        assertProbeResponse(HttpServletResponse.SC_BAD_REQUEST, UNKNOWN);
    }

    @ParameterizedTest(name = "the filter and the servlet answer {0} identically")
    @ValueSource(strings = {"/health/live", "/health/ready"})
    public void theTwoRegistrationsAnswerTheSameProbeIdentically(String probePath) throws Exception {
        givenProbePath(probePath, null);
        givenEstablishedVerdict(true, 0L);

        // The filter role first, against the shared response mock, so assertProbeResponse's full set of
        // status, document, header and never-touched assertions applies to it.
        servlet.doFilter(request, response, mock(FilterChain.class));
        assertProbeResponse(HttpServletResponse.SC_OK, "/health/live".equals(probePath) ? LIVE_UP : READY_UP);

        // Then the servlet role, against its own response, and the two answers are compared rather than
        // restated: a divergence between the registrations fails here whichever way it goes.
        HttpServletResponse second = mock(HttpServletResponse.class);
        StringWriter secondBody = new StringWriter();
        when(second.getWriter()).thenReturn(new PrintWriter(secondBody));
        givenEstablishedVerdict(true, 0L);

        servlet.service(request, second);

        ArgumentCaptor<Integer> filterStatus = ArgumentCaptor.forClass(Integer.class);
        verify(response).setStatus(filterStatus.capture());
        ArgumentCaptor<Integer> servletStatus = ArgumentCaptor.forClass(Integer.class);
        verify(second).setStatus(servletStatus.capture());
        assertEquals(filterStatus.getValue(), servletStatus.getValue(),
                "the filter and the servlet must answer " + probePath + " with the same status");
        assertEquals(responseBody.toString(), secondBody.toString(),
                "the filter and the servlet must answer " + probePath + " with the same document");
    }

    /*
     * The published probe-path predicate
     *
     * Machinery that runs BEFORE this class - a Tomcat engine valve, which executes ahead of every
     * webapp's filter chain - has to be able to recognise a probe in order to leave it alone. The
     * cross-subdomain session valve does exactly that: without an exemption it calls getSession(true)
     * on every request, and since a probe client never returns a cookie, each anonymous probe created a
     * session that lived until it expired. The predicate is published so that the valve does not carry
     * a second copy of the two literals, because a copy can drift out of step - and a valve exempting a
     * stale spelling would silently resume doing to probes exactly what the exemption prevents.
     */

    @ParameterizedTest(name = "isProbePath({0}) agrees with what the probe serves")
    @CsvSource(nullValues = "NULL", value = {
        "/health/live",
        "/health/ready",
        "NULL",
        "''",
        "/health",
        "/health/",
        "/health/live/",
        "/health/ready/",
        "/HEALTH/LIVE",
        "/Health/Ready",
        "/healthz/live",
        "/health//live",
        "/health/liveness",
        "/health/ready2",
        "/health/live%20",
        "/health/ready;jsessionid=0123456789ABCDEF",
        "/webtools/health/live",
        "/control/main" })
    public void theProbePathPredicateAgreesWithWhatTheProbeItselfServes(String path) throws Exception {
        // The predicate is not asserted against a restated list of literals; it is asserted against
        // what the servlet DOES with the same path. That is what keeps the two from drifting: a path
        // the servlet starts serving and the predicate still rejects fails here, and so does the
        // reverse - which is the direction that would leave a probe unexempted in the valve.
        givenProbePath(path, null);
        // A verdict inside the fresh window, so the readiness path answers without a datasource and
        // this measures path recognition alone.
        givenEstablishedVerdict(true, 0L);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            servlet.service(request, response);

            webAppUtil.verifyNoInteractions();
        }

        ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
        verify(response).setStatus(status.capture());
        boolean servedAsAProbe = status.getValue() != HttpServletResponse.SC_NOT_FOUND;
        assertEquals(servedAsAProbe, HealthCheckServlet.isProbePath(path),
                "the published predicate and the servlet disagree about " + path + ", so a valve trusting the"
                        + " predicate would exempt the wrong set of paths");
    }

    @Test
    public void theProbePathPredicateIsPublishedAndStatelessSoAValveCanUseItBeforeAnyWebappRuns() throws Exception {
        Method predicate = HealthCheckServlet.class.getMethod("isProbePath", String.class);

        // A valve is constructed by the catalina container, not by a webapp, so the predicate has to be
        // reachable without an instance and without this class having been initialised by a container.
        assertTrue(Modifier.isPublic(predicate.getModifiers()) && Modifier.isStatic(predicate.getModifiers()),
                "the predicate must be public and static for machinery outside this webapp to consult it");
        assertEquals(boolean.class, predicate.getReturnType(), "the predicate must answer a plain boolean");

        // And it must accept exactly the two paths the servlet's own constants name. Reading them from
        // the class rather than restating them is the point: renaming a probe path without teaching the
        // predicate about it fails here, which is the drift the valve exemption depends on not happening.
        assertTrue(HealthCheckServlet.isProbePath(probePathConstant("PROBE_LIVE")),
                "the liveness path the servlet declares must be recognised as a probe path");
        assertTrue(HealthCheckServlet.isProbePath(probePathConstant("PROBE_READY")),
                "the readiness path the servlet declares must be recognised as a probe path");
    }

    /*
     * Method allow-list: only GET and HEAD are served
     */

    @ParameterizedTest(name = "{0} /health/live -> 405")
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE", "CONNECT", "get", "Head", "PROPFIND"})
    public void anyMethodOtherThanGetOrHeadIsRefusedWithAllow(String method) throws Exception {
        givenProbePath("/health/live", null);
        givenMethod(method);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            servlet.service(request, response);

            // A refused method must cost nothing at all: no delegator, no query, no body read.
            webAppUtil.verifyNoInteractions();
        }
        // The method comparison is case-sensitive on purpose: HTTP method tokens are case-sensitive,
        // so "get" is not GET and must not be served either.
        verify(response).setHeader(ALLOW_HEADER, ALLOW_VALUE);
        assertProbeResponse(HttpServletResponse.SC_METHOD_NOT_ALLOWED, UNKNOWN);
    }

    @Test
    public void aRefusedMethodIsRejectedBeforeThePathIsEvenConsidered() throws Exception {
        // Readiness would otherwise reach the database; the method gate has to come first so that an
        // anonymous caller cannot drive a query with a method the endpoint does not serve.
        givenProbePath("/health/ready", null);
        givenMethod("POST");

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            servlet.service(request, response);

            webAppUtil.verifyNoInteractions();
        }
        assertProbeResponse(HttpServletResponse.SC_METHOD_NOT_ALLOWED, UNKNOWN);
    }

    @Test
    public void anAllowedMethodCarriesNoAllowHeader() throws Exception {
        givenProbePath("/health/live", null);

        servlet.service(request, response);

        verify(response, never()).setHeader(eq(ALLOW_HEADER), anyString());
        assertProbeResponse(HttpServletResponse.SC_OK, LIVE_UP);
    }

    /*
     * Request bodies are refused from the headers, never read
     */

    @ParameterizedTest(name = "Content-Length {0} -> 400")
    @ValueSource(longs = {1L, 1024L, 5L * 1024 * 1024, Long.MAX_VALUE})
    public void aDeclaredBodyIsRefusedWithoutBeingRead(long contentLength) throws Exception {
        givenProbePath("/health/ready", null);
        when(request.getContentLengthLong()).thenReturn(contentLength);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            servlet.service(request, response);

            webAppUtil.verifyNoInteractions();
        }
        assertProbeResponse(HttpServletResponse.SC_BAD_REQUEST, UNKNOWN);
    }

    @ParameterizedTest(name = "Transfer-Encoding {0} -> 400")
    @ValueSource(strings = {"chunked", "Chunked", "CHUNKED", "gzip, chunked"})
    public void aChunkedBodyOfUnknownLengthIsRefusedWithoutBeingRead(String transferEncoding) throws Exception {
        givenProbePath("/health/live", null);
        // A chunked request declares no length at all, which is the shape that makes an unbounded
        // read dangerous, so it must be refused on the header alone.
        when(request.getContentLengthLong()).thenReturn(-1L);
        when(request.getHeader("Transfer-Encoding")).thenReturn(transferEncoding);

        servlet.service(request, response);

        assertProbeResponse(HttpServletResponse.SC_BAD_REQUEST, UNKNOWN);
    }

    @ParameterizedTest(name = "Content-Length {0} is not a body")
    @ValueSource(longs = {-1L, 0L})
    public void aRequestWithNoDeclaredBodyIsServedNormally(long contentLength) throws Exception {
        givenProbePath("/health/live", null);
        when(request.getContentLengthLong()).thenReturn(contentLength);

        servlet.service(request, response);

        assertProbeResponse(HttpServletResponse.SC_OK, LIVE_UP);
    }

    @Test
    public void anIdentityTransferEncodingIsNotTreatedAsABody() throws Exception {
        givenProbePath("/health/live", null);
        when(request.getHeader("Transfer-Encoding")).thenReturn("identity");

        servlet.service(request, response);

        assertProbeResponse(HttpServletResponse.SC_OK, LIVE_UP);
    }

    /*
     * Safe response headers are retained even though the chain is never entered
     */

    @ParameterizedTest(name = "a {1} verdict on {0} still carries the safe response headers")
    @CsvSource({
        "/health/live, GET",
        "/health/ready, GET",
        "/health/live, HEAD",
    })
    public void everyServedVerdictCarriesTheSafeResponseHeaders(String probePath, String method) throws Exception {
        givenProbePath(probePath, null);
        givenMethod(method);
        // The readiness row has to be given a reachable datasource, otherwise it would reach the real
        // entity engine, which both slows the test down and makes it write to the shared log.
        Delegator delegator = delegatorCountingRows(42L);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(delegator);

            servlet.service(request, response);
        }

        verify(response).setStatus(HttpServletResponse.SC_OK);
        verify(response).setHeader(CONTENT_TYPE_OPTIONS_HEADER, CONTENT_TYPE_OPTIONS_VALUE);
        verify(response).setHeader(FRAME_OPTIONS_HEADER, FRAME_OPTIONS_VALUE);
        verify(response).setHeader(REFERRER_POLICY_HEADER, REFERRER_POLICY_VALUE);
    }

    @ParameterizedTest(name = "a rejection with status {0} still carries the safe response headers")
    @CsvSource(nullValues = "NULL", value = {
        "405, POST, 0, NULL",
        "400, GET, 7, NULL",
        "400, GET, -1, chunked",
        "404, GET, 0, NULL",
    })
    public void everyRejectionAlsoCarriesTheSafeResponseHeaders(int expectedStatus, String method, long contentLength,
            String transferEncoding) throws Exception {
        // A rejection is the response an anonymous caller is most likely to provoke, so it has to be
        // protected exactly like a healthy verdict rather than falling back to a bare document.
        givenProbePath(expectedStatus == 404 ? "/health/other" : "/health/live", null);
        givenMethod(method);
        when(request.getContentLengthLong()).thenReturn(contentLength);
        when(request.getHeader("Transfer-Encoding")).thenReturn(transferEncoding);

        servlet.service(request, response);

        verify(response).setStatus(expectedStatus);
        verify(response).setHeader(CONTENT_TYPE_OPTIONS_HEADER, CONTENT_TYPE_OPTIONS_VALUE);
        verify(response).setHeader(FRAME_OPTIONS_HEADER, FRAME_OPTIONS_VALUE);
        verify(response).setHeader(REFERRER_POLICY_HEADER, REFERRER_POLICY_VALUE);
    }

    @Test
    public void theProbeSetsNoHeaderThatWouldNeedTheEntityEngineOrIsDeprecated() throws Exception {
        // Strict-Transport-Security and Content-Security-Policy are resolved through
        // EntityUtilProperties in UtilHttp, so emitting them here would pull a delegator lookup and a
        // property read into every probe - and would attempt them while an unreachable datasource is
        // the very condition being reported. X-XSS-Protection is deprecated and ignored by current
        // browsers. All three are deliberately absent; this test pins that decision so a later change
        // cannot quietly reintroduce the coupling. Readiness is the path under test because it is the
        // one that legitimately reaches the entity engine, so it is where the coupling would appear.
        givenProbePath("/health/ready", null);
        Delegator delegator = delegatorCountingRows(42L);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(delegator);

            servlet.service(request, response);
        }

        assertProbeResponse(HttpServletResponse.SC_OK, READY_UP);
        for (String forbidden : List.of("Strict-Transport-Security", "strict-transport-security",
                "Content-Security-Policy", "Content-Security-Policy-Report-Only", "X-XSS-Protection")) {
            verify(response, never()).setHeader(eq(forbidden), anyString());
            verify(response, never()).addHeader(eq(forbidden), anyString());
        }
    }


    /*
     * Servlet dispatch: the method gate, then doGet or doHead
     */

    @ParameterizedTest(name = "GET {0} is dispatched to doGet")
    @ValueSource(strings = {"/health/live", "/health/ready"})
    public void aGetProbeIsDispatchedToDoGetAndServedThere(String probePath) throws Exception {
        DispatchRecordingServlet recorder = givenDispatchRecordingServlet();
        givenProbePath(probePath, null);
        Delegator delegator = delegatorCountingRows(1L);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(delegator);

            recorder.service(request, response);
        }
        // The gate delegates to HttpServlet's own dispatch rather than answering in its place, so
        // doGet stays the entry point that serves a GET probe.
        assertEquals(List.of("doGet"), recorder.dispatched(), "dispatch of a GET probe");
        assertProbeResponse(HttpServletResponse.SC_OK, "/health/live".equals(probePath) ? LIVE_UP : READY_UP);
    }

    @ParameterizedTest(name = "HEAD {0} is dispatched to doHead only")
    @ValueSource(strings = {"/health/live", "/health/ready"})
    public void aHeadProbeIsDispatchedToDoHeadWithoutAlsoRunningDoGet(String probePath) throws Exception {
        DispatchRecordingServlet recorder = givenDispatchRecordingServlet();
        givenProbePath(probePath, null);
        givenMethod("HEAD");
        Delegator delegator = delegatorCountingRows(1L);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(delegator);

            recorder.service(request, response);
        }
        // doHead deliberately does not delegate to HttpServlet's default, which would wrap the
        // response and call doGet purely to measure Content-Length - for readiness that means running
        // the datasource check to size a body the client never receives.
        assertEquals(List.of("doHead"), recorder.dispatched(), "dispatch of a HEAD probe");
        assertProbeResponse(HttpServletResponse.SC_OK, "/health/live".equals(probePath) ? LIVE_UP : READY_UP);
    }

    @ParameterizedTest(name = "{0} never reaches a dispatch method")
    @ValueSource(strings = {"POST", "TRACE", "OPTIONS", "PATCH", "PROPFIND", "get"})
    public void aRefusedMethodNeverReachesADispatchMethodAtAll(String method) throws Exception {
        DispatchRecordingServlet recorder = givenDispatchRecordingServlet();
        givenProbePath("/health/live", null);
        givenMethod(method);

        recorder.service(request, response);

        // The gate answers before super.service() runs, which is what keeps HttpServlet's own
        // defaults - doTrace echoing request headers, doOptions advertising the method set, an
        // unrecognised method answered 501 through sendError - out of reach on an anonymous path.
        assertTrue(recorder.dispatched().isEmpty(), "a refused method dispatched " + recorder.dispatched());
        verify(response).setHeader(ALLOW_HEADER, ALLOW_VALUE);
        assertProbeResponse(HttpServletResponse.SC_METHOD_NOT_ALLOWED, UNKNOWN);
    }

    @Test
    public void doGetServesAProbeWhenTheContainerDispatchesToItDirectly() throws Exception {
        givenProbePath("/health/live", null);

        servlet.doGet(request, response);

        // A container is free to dispatch straight to doGet, so the handler must be complete on its
        // own rather than relying on anything the gate did first.
        assertProbeResponse(HttpServletResponse.SC_OK, LIVE_UP);
    }

    @Test
    public void doHeadServesTheSameDocumentAsDoGetWhenDispatchedDirectly() throws Exception {
        givenProbePath("/health/ready", null);
        Delegator delegator = delegatorCountingRows(1L);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(delegator);

            servlet.doHead(request, response);
        }
        assertProbeResponse(HttpServletResponse.SC_OK, READY_UP);
    }

    /*
     * Readiness failures are logged as a stable code, rate limited and sanitised
     */

    @Test
    public void aReadinessFailureLogsTheStableEventCodeAndNoInternalDetail() throws Exception {
        String leak = "SQL Exception while executing: SELECT COUNT(*) FROM SEQUENCE_VALUE_ITEM"
                + " (jdbc:postgresql://db.internal:5432/ofbizmaindb?sslmode=verify-full user=ofbiz password=Sup3rS3cret)";

        List<String> normalLevelLines = readinessFailureLog(new GenericEntityException(leak), false);

        assertEquals(List.of(EVENT_CODE), normalLevelLines, "the normal-level log of a readiness failure");
        assertProbeResponse(HttpServletResponse.SC_SERVICE_UNAVAILABLE, READY_DOWN);
    }

    @Test
    public void anUncheckedFailureAlsoLogsOnlyTheStableEventCode() throws Exception {
        List<String> normalLevelLines =
                readinessFailureLog(new IllegalStateException("Cannot get a connection, pool error /var/lib/ofbiz"), false);

        assertEquals(List.of(EVENT_CODE), normalLevelLines, "the normal-level log of an unchecked failure");
    }

    @Test
    public void noDriverDetailIsWrittenEvenWithVerboseDiagnosticsSwitchedOn() throws Exception {
        // Sanitising a driver message is not enough: an entity-engine or JDBC message also names the
        // entity, the datasource, the pooled driver class and the failing SQL, and none of that may be
        // reachable through an anonymous caller's ability to trigger log writes. So the probe writes
        // no diagnostic at all, at any level - not a redacted one.
        String leak = "connect failed\nat org.postgresql.Driver.connect\r\n"
                + "url=jdbc:postgresql://db.internal:5432/ofbizmaindb?sslmode=verify-full password=Sup3rS3cret";

        List<String> verboseLines = readinessFailureLog(new GenericEntityException(leak), true);

        assertEquals(List.of(), verboseLines, "no diagnostic may be written under verbose diagnostics either");
    }

    @Test
    public void aPathologicallyLongDriverMessageCannotInflateTheLogAtAll() throws Exception {
        List<String> verboseLines = readinessFailureLog(new GenericEntityException("x".repeat(5000)), true);

        // Nothing derived from the driver message is written, so a 5000-character message cannot be
        // used to inflate the log whether or not a length cap is in place.
        assertEquals(List.of(), verboseLines, "a driver message must not reach the log in any form");
    }

    @Test
    public void repeatedFailuresAreRateLimitedIntoASingleCountedLine() throws Exception {
        givenProbePath("/health/ready", null);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class);
                MockedStatic<Debug> debug = mockStatic(Debug.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(null);

            for (int probe = 0; probe < 25; probe++) {
                // Ageing the verdict out between probes is what the polling interval does at runtime,
                // so all 25 probes genuinely run a check and each one has a failure to report.
                givenNoEstablishedVerdict();
                servlet.service(request, response);
            }

            // An outage makes every probe of every target fail at the polling interval. Exactly one
            // line per window may be written, otherwise the readiness endpoint amplifies the outage
            // into the log it is being diagnosed with.
            debug.verify(() -> Debug.logWarning(anyString(), anyString()), times(1));
            debug.verify(() -> Debug.logError(anyString(), anyString()), never());
        }
    }

    @Test
    public void theSuppressedOccurrencesAreCountedIntoTheNextLine() throws Exception {
        givenProbePath("/health/ready", null);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class);
                MockedStatic<Debug> debug = mockStatic(Debug.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(null);

            // Three probe rounds of an outage - the verdict ages out between them, as the polling
            // interval makes it - so three checks fail and two of them are held back by the limit.
            givenNoEstablishedVerdict();
            servlet.service(request, response);
            givenNoEstablishedVerdict();
            servlet.service(request, response);
            givenNoEstablishedVerdict();
            servlet.service(request, response);
            // Re-opening the window is what a later probe does once the rate-limit interval elapses.
            reopenReadinessLogWindow();
            givenNoEstablishedVerdict();
            servlet.service(request, response);

            ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
            debug.verify(() -> Debug.logWarning(lines.capture(), anyString()), times(2));
            assertEquals(EVENT_CODE, lines.getAllValues().get(0), "the first line of an outage");
            assertTrue(lines.getAllValues().get(1).startsWith(EVENT_CODE + " (2 further occurrences suppressed)"),
                    "the second line must account for what was suppressed: " + lines.getAllValues().get(1));
        }
    }

    @Test
    public void suppressedOccurrencesAreWrittenOutOnceTheConditionStops() throws Exception {
        // A counter carried only by the NEXT occurrence of the same code would leave everything
        // suppressed after the last one - the tail of every burst, and the whole of a burst that ends
        // inside its own window - out of the log entirely. A later probe has to write it out.
        givenProbePath("/health/ready", null);
        Delegator healthy = delegatorCountingRows(7L);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class);
                MockedStatic<Debug> debug = mockStatic(Debug.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(null);

            givenNoEstablishedVerdict();
            servlet.service(request, response);
            givenNoEstablishedVerdict();
            servlet.service(request, response);
            givenNoEstablishedVerdict();
            servlet.service(request, response);
            assertEquals(2L, readinessLogCounter("READINESS_LOG_SUPPRESSED").get(),
                    "two of the three failures must have been held back by the rate limit");

            // The datasource recovers, so no further occurrence will ever carry the count. The next
            // probe still has to account for it once the window has elapsed. The recovered delegator
            // arrives on the ServletContext, which is where a deployed webapp publishes it.
            when(servletContext.getAttribute("delegator")).thenReturn(healthy);
            reopenReadinessLogWindow();
            givenNoEstablishedVerdict();
            servlet.service(request, response);

            ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
            debug.verify(() -> Debug.logWarning(lines.capture(), anyString()), times(2));
            assertEquals(EVENT_CODE, lines.getAllValues().get(0), "the first line of the outage");
            assertEquals(EVENT_CODE + " (2 further occurrences suppressed)", lines.getAllValues().get(1),
                    "the recovered probe must write out what the limit held back");
        }
        assertEquals(0L, readinessLogCounter("READINESS_LOG_SUPPRESSED").get(),
                "the flushed count must be cleared, so it can never be reported twice");
        // The probe that flushed it is a healthy one and answered as such.
        verify(response).setStatus(HttpServletResponse.SC_OK);
    }

    @Test
    public void aHealthyProbeWithNothingOutstandingStillWritesNothing() throws Exception {
        // The flush above may not turn continuous polling into log traffic: with no suppressed
        // occurrences there is nothing to write, and a healthy probe has to stay completely silent.
        givenProbePath("/health/ready", null);
        Delegator delegator = delegatorCountingRows(7L);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class);
                MockedStatic<Debug> debug = mockStatic(Debug.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(delegator);

            for (int probe = 0; probe < 25; probe++) {
                givenNoEstablishedVerdict();
                servlet.service(request, response);
            }

            debug.verify(() -> Debug.logWarning(anyString(), anyString()), never());
            debug.verify(() -> Debug.logError(anyString(), anyString()), never());
        }
        assertWindowStillOpen("READINESS_LOG_LAST_AT",
                "a healthy probe must not consume a rate-limit window either");
    }

    @Test
    public void aProbeWithNoVerdictToObtainIsReportedUnderItsOwnCodeAndNeverTouchesTheDatasource() throws Exception {
        givenProbePath("/health/ready", null);
        // The one state that answers not-ready without the datasource having said so: a check has been
        // running longer than the bounded wait and there is no verdict at all to stand on.
        givenReadinessCheckRunning();
        givenNoEstablishedVerdict();

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class);
                MockedStatic<Debug> debug = mockStatic(Debug.class)) {
            servlet.service(request, response);

            ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
            debug.verify(() -> Debug.logWarning(lines.capture(), anyString()), times(1));
            assertEquals(SHED_EVENT_CODE, lines.getValue(),
                    "a probe that could obtain no verdict must be reported under its own code, so an alert"
                            + " can tell a check that is not completing from a database that needs attention");
            debug.verify(() -> Debug.logError(anyString(), anyString()), never());
            // A second query is never issued while one is already running - that is what bounds what
            // the datasource sees, and it is why waiting costs no pooled connection.
            webAppUtil.verify(() -> WebAppUtil.getDelegator(servletContext), never());
            verify(servletContext, never()).getAttribute(anyString());
        }

        assertProbeResponse(HttpServletResponse.SC_SERVICE_UNAVAILABLE, READY_DOWN);
        assertEquals(0L, readinessLogCounter("READINESS_LOG_SUPPRESSED").get(),
                "a probe without a verdict must not be counted as a datasource failure");
        assertWindowStillOpen("READINESS_LOG_LAST_AT",
                "it must not consume the rate-limit window an unavailable datasource needs");
    }

    @Test
    public void everyEventCodeKeepsItsOwnSuppressedCountAndItsOwnWindow() throws Exception {
        givenProbePath("/health/ready", null);
        givenReadinessCheckRunning();

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class);
                MockedStatic<Debug> debug = mockStatic(Debug.class)) {
            givenNoEstablishedVerdict();
            servlet.service(request, response);
            givenNoEstablishedVerdict();
            servlet.service(request, response);

            ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
            debug.verify(() -> Debug.logWarning(lines.capture(), anyString()), times(1));
            assertEquals(SHED_EVENT_CODE, lines.getValue(), "the first line of a check that is not completing");
            webAppUtil.verify(() -> WebAppUtil.getDelegator(servletContext), never());
        }

        assertEquals(1L, readinessLogCounter("READINESS_SHED_LOG_SUPPRESSED").get(),
                "the second occurrence must be counted onto its own code");
        assertEquals(0L, readinessLogCounter("READINESS_LOG_SUPPRESSED").get(),
                "it must never be reported on the datasource line as if it were a failure");
        assertEquals(0L, readinessLogCounter("READINESS_EMPTY_LOG_SUPPRESSED").get(),
                "nor on the empty-schema line");
    }

    @Test
    public void aSuccessfulReadinessProbeLogsNothingAtAll() throws Exception {
        givenProbePath("/health/ready", null);
        Delegator delegator = delegatorCountingRows(7L);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class);
                MockedStatic<Debug> debug = mockStatic(Debug.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(delegator);
            // Verbose diagnostics on is the demanding case: the row count of a healthy schema is
            // itself information about the deployment and must not be written per anonymous probe.
            debug.when(Debug::verboseOn).thenReturn(true);

            servlet.service(request, response);

            debug.verify(() -> Debug.logWarning(anyString(), anyString()), never());
            debug.verify(() -> Debug.logError(anyString(), anyString()), never());
            debug.verify(() -> Debug.logInfo(anyString(), anyString()), never());
            debug.verify(() -> Debug.logVerbose(anyString(), anyString()), never());
        }
        assertProbeResponse(HttpServletResponse.SC_OK, READY_UP);
    }

    @Test
    public void anUnpopulatedSchemaIsReportedUnderItsOwnCode() throws Exception {
        givenProbePath("/health/ready", null);
        Delegator delegator = delegatorCountingRows(0L);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class);
                MockedStatic<Debug> debug = mockStatic(Debug.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(delegator);

            servlet.service(request, response);

            ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
            debug.verify(() -> Debug.logWarning(lines.capture(), anyString()), times(1));
            // The operator action differs from an outage - finish the schema-init execution and its
            // data load - so the condition may not be reported as a datasource failure.
            assertEquals(EMPTY_EVENT_CODE, lines.getValue(), "a reachable but empty schema needs its own code");
        }
        assertWindowStillOpen("READINESS_LOG_LAST_AT",
                "an empty schema must not consume the rate-limit window an unavailable datasource needs");
        assertProbeResponse(HttpServletResponse.SC_SERVICE_UNAVAILABLE, READY_DOWN);
    }

    @Test
    public void anAlreadyPublishedDelegatorIsUsedWithoutAskingTheFactoryForOne() throws Exception {
        // ContextFilter.init() publishes the delegator on the ServletContext when the webapp is
        // deployed. Observing it there is what keeps a probe from asking DelegatorFactory for a
        // delegator, which is the call that logs a throwable and its stack when construction fails and
        // then re-logs it on every later call because the failed Future is cached.
        givenProbePath("/health/ready", null);
        Delegator delegator = delegatorCountingRows(3L);
        when(servletContext.getAttribute("delegator")).thenReturn(delegator);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            servlet.service(request, response);

            webAppUtil.verifyNoInteractions();
        }
        assertProbeResponse(HttpServletResponse.SC_OK, READY_UP);
    }

    @Test
    public void aFailingDelegatorLookupIsNotRetriedOnEveryProbe() throws Exception {
        // DelegatorFactory caches the failed Future, so a construction that failed can never succeed
        // later in the same JVM - but it re-throws and re-logs on every call. Retrying it per probe
        // would let an anonymous poller amplify one broken datasource definition into an unbounded
        // stream of stack traces from below this class, outside its rate limit.
        givenProbePath("/health/ready", null);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(null);

            for (int probe = 0; probe < 25; probe++) {
                // Ageing the verdict out between probes makes all 25 run a check of their own, so it
                // is the lookup throttle being asserted here rather than the shared verdict.
                givenNoEstablishedVerdict();
                servlet.service(request, response);
            }

            webAppUtil.verify(() -> WebAppUtil.getDelegator(servletContext), times(1));
        }
        // Every one of the 25 probes still got the same fail-closed verdict.
        verify(response, times(25)).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    /*
     * Cache-coherence dimension
     *
     * A delegator with distributed cache clear enabled dispatches the distributedClearCacheLine
     * services on every entity write, through engine="jms" location="serviceMessenger". None of them
     * declares require-new-transaction, so ServiceDispatcher.runAsync does not suspend the caller's
     * transaction; with no active jms-service the JMS engine dereferences null, runAsync catches it and
     * marks the CALLER's transaction rollback-only, and EntityCacheServices can only log. An instance
     * in that state rolls back the writes it is asked to perform. Nothing in the write path stops it,
     * so readiness has to: the instance must be reported not ready and taken out of the target group.
     */

    @Test
    public void readyReturnsUpWithNoTransportAtAllWhenTheDelegatorDoesNotRequireCoherence() throws Exception {
        // The committed configuration has distributed cache clear off, so a single-node or local H2
        // deployment must be completely unaffected by this dimension - the parity requirement.
        givenProbePath("/health/ready", null);
        Delegator delegator = delegatorCountingRows(7L);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(delegator);

            servlet.service(request, response);

            // Inertness is not merely "answers UP": nothing about the transport is even looked at.
            verify(servletContext, never()).getAttribute(DISPATCHER_ATTRIBUTE);
        }
        assertProbeResponse(HttpServletResponse.SC_OK, READY_UP);
    }

    @Test
    public void readyReturnsDownWhenCoherenceIsRequiredButNoDispatcherIsPublished() throws Exception {
        // An absent dispatcher attribute means this webapp has not finished coming up. For a readiness
        // probe that is the truth, and holding traffic off is the correct answer.
        givenProbePath("/health/ready", null);
        Delegator delegator = delegatorRequiringCoherence(7L);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(delegator);

            servlet.service(request, response);
        }
        assertProbeResponse(HttpServletResponse.SC_SERVICE_UNAVAILABLE, READY_DOWN);
    }

    @Test
    public void theProbeNeverBuildsADispatcherToAnswerAReadinessCheck() throws Exception {
        // WebAppUtil.getDispatcher CONSTRUCTS a dispatcher when the attribute is absent, which starts a
        // service engine and, with JMS enabled, a listener factory thread. A probe must observe
        // readiness, not create the machinery it reports on - the same rule resolveDelegator states.
        givenProbePath("/health/ready", null);
        Delegator delegator = delegatorRequiringCoherence(7L);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(delegator);

            servlet.service(request, response);

            webAppUtil.verify(() -> WebAppUtil.getDispatcher(servletContext), never());
            webAppUtil.verify(() -> WebAppUtil.makeWebappDispatcher(any(), any()), never());
        }
        assertProbeResponse(HttpServletResponse.SC_SERVICE_UNAVAILABLE, READY_DOWN);
    }

    @Test
    public void readyReturnsDownWhenTheDispatcherHasNoListenerFactory() throws Exception {
        // ServiceDispatcher leaves its listener factory null when service.properties disables JMS, so
        // a dispatcher can exist while no invalidation transport does.
        givenProbePath("/health/ready", null);
        Delegator delegator = delegatorRequiringCoherence(7L);
        LocalDispatcher dispatcher = mock(LocalDispatcher.class);
        when(dispatcher.getJMSListeneFactory()).thenReturn(null);
        when(servletContext.getAttribute(DISPATCHER_ATTRIBUTE)).thenReturn(dispatcher);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(delegator);

            servlet.service(request, response);
        }
        assertProbeResponse(HttpServletResponse.SC_SERVICE_UNAVAILABLE, READY_DOWN);
    }

    @Test
    public void readyReturnsDownWhenNoSubscriberHasBeenRegisteredYet() throws Exception {
        // An empty map covers two states that must both hold traffic off: the loader thread has not
        // completed its first pass, and the configuration declares no jms-service with listen="true".
        // Neither is a fleet member that can be trusted with a write.
        givenProbePath("/health/ready", null);
        Delegator delegator = delegatorRequiringCoherence(7L);
        // Built before the stubbing below rather than inside it: the helper stubs its own mocks, and a
        // stubbing started while another is unfinished is a Mockito misuse.
        LocalDispatcher dispatcher = dispatcherWithSubscribers(Map.of());
        when(servletContext.getAttribute(DISPATCHER_ATTRIBUTE)).thenReturn(dispatcher);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(delegator);

            servlet.service(request, response);
        }
        assertProbeResponse(HttpServletResponse.SC_SERVICE_UNAVAILABLE, READY_DOWN);
    }

    @Test
    public void readyReturnsDownWhenASingleSubscriberIsDisconnected() throws Exception {
        // JmsListenerFactory registers a listener in its map BEFORE calling load(), so a listener that
        // could not reach the broker is present and reports false - and AbstractJmsListener.onException
        // sets the flag false the moment an established connection drops. One disconnected subscriber
        // is enough: the invalidation this instance publishes would not reach the whole fleet.
        givenProbePath("/health/ready", null);
        Delegator delegator = delegatorRequiringCoherence(7L);
        LocalDispatcher dispatcher = dispatcherWithSubscribers(Map.of("first", subscriber(true), "second", subscriber(false)));
        when(servletContext.getAttribute(DISPATCHER_ATTRIBUTE)).thenReturn(dispatcher);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(delegator);

            servlet.service(request, response);
        }
        assertProbeResponse(HttpServletResponse.SC_SERVICE_UNAVAILABLE, READY_DOWN);
    }

    @Test
    public void readyReturnsUpWhenEverySubscriberIsConnected() throws Exception {
        // JmsTopicListener.load() reports connected only after the JNDI context, the connection factory
        // and topic lookups, the connection, the session, the subscriber registration and the start all
        // succeed - so this is strictly stronger evidence than a TCP probe of the broker.
        givenProbePath("/health/ready", null);
        Delegator delegator = delegatorRequiringCoherence(7L);
        LocalDispatcher dispatcher = dispatcherWithSubscribers(Map.of("first", subscriber(true), "second", subscriber(true)));
        when(servletContext.getAttribute(DISPATCHER_ATTRIBUTE)).thenReturn(dispatcher);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(delegator);

            servlet.service(request, response);
        }
        assertProbeResponse(HttpServletResponse.SC_OK, READY_UP);
    }

    @Test
    public void theCoherenceDimensionOnlyObservesAndCostsNoBrokerRoundTrip() throws Exception {
        // The dimension must add no socket, no broker call and no state change to a path a load
        // balancer polls every few seconds. Pinning the exact interactions is what says so: a map copy
        // and a field read, and nothing that loads, refreshes or closes a listener.
        givenProbePath("/health/ready", null);
        Delegator delegator = delegatorRequiringCoherence(7L);
        LocalDispatcher dispatcher = mock(LocalDispatcher.class);
        JmsListenerFactory listenerFactory = mock(JmsListenerFactory.class);
        GenericMessageListener listener = mock(GenericMessageListener.class);
        when(listener.isConnected()).thenReturn(true);
        when(listenerFactory.getJMSListeners()).thenReturn(Map.of("only", listener));
        when(dispatcher.getJMSListeneFactory()).thenReturn(listenerFactory);
        when(servletContext.getAttribute(DISPATCHER_ATTRIBUTE)).thenReturn(dispatcher);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(delegator);

            servlet.service(request, response);
        }
        verify(dispatcher).getJMSListeneFactory();
        verify(listenerFactory).getJMSListeners();
        verify(listener).isConnected();
        verifyNoMoreInteractions(dispatcher, listenerFactory, listener);
        assertProbeResponse(HttpServletResponse.SC_OK, READY_UP);
    }

    @Test
    public void anAbsentTransportIsReportedUnderItsOwnStableEventCodeAndNothingElse() throws Exception {
        // The datasource is fine here - the count completed and was non-zero - so reporting this under
        // the datasource code would send an operator to the wrong system. Nothing beyond the code may
        // be written either: the path is anonymous and polled.
        givenProbePath("/health/ready", null);
        Delegator delegator = delegatorRequiringCoherence(7L);
        LocalDispatcher dispatcher = dispatcherWithSubscribers(Map.of("only", subscriber(false)));
        when(servletContext.getAttribute(DISPATCHER_ATTRIBUTE)).thenReturn(dispatcher);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class);
                MockedStatic<Debug> debug = mockStatic(Debug.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(delegator);

            servlet.service(request, response);

            ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
            debug.verify(() -> Debug.logWarning(lines.capture(), anyString()));
            assertEquals(List.of(TRANSPORT_EVENT_CODE), lines.getAllValues(),
                    "an absent invalidation transport has its own code and carries no other detail");
            debug.verify(() -> Debug.logError(anyString(), anyString()), never());
            debug.verify(() -> Debug.logError(any(Throwable.class), anyString()), never());
            debug.verify(() -> Debug.logWarning(any(Throwable.class), anyString()), never());
        }
        assertProbeResponse(HttpServletResponse.SC_SERVICE_UNAVAILABLE, READY_DOWN);
    }

    @Test
    public void theDatasourceDimensionIsReportedBeforeTheCoherenceDimension() throws Exception {
        // Both dimensions are failing. An instance that cannot reach its datasource has the bigger
        // problem and must not be reported under the broker's code, which would send an operator to
        // restore a broker that is not the cause.
        givenProbePath("/health/ready", null);
        Delegator delegator = delegatorFailingWith(new GenericEntityException("datasource unreachable"));
        when(delegator.useDistributedCacheClear()).thenReturn(true);
        LocalDispatcher dispatcher = dispatcherWithSubscribers(Map.of());
        when(servletContext.getAttribute(DISPATCHER_ATTRIBUTE)).thenReturn(dispatcher);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class);
                MockedStatic<Debug> debug = mockStatic(Debug.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(delegator);

            servlet.service(request, response);

            ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
            debug.verify(() -> Debug.logWarning(lines.capture(), anyString()));
            assertEquals(List.of(EVENT_CODE), lines.getAllValues(),
                    "a failing datasource is reported as such, not as a missing transport");
        }
        assertProbeResponse(HttpServletResponse.SC_SERVICE_UNAVAILABLE, READY_DOWN);
    }

    @Test
    public void anUnpopulatedSchemaIsReportedBeforeTheCoherenceDimension() throws Exception {
        givenProbePath("/health/ready", null);
        Delegator delegator = delegatorRequiringCoherence(0L);
        LocalDispatcher dispatcher = dispatcherWithSubscribers(Map.of());
        when(servletContext.getAttribute(DISPATCHER_ATTRIBUTE)).thenReturn(dispatcher);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class);
                MockedStatic<Debug> debug = mockStatic(Debug.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(delegator);

            servlet.service(request, response);

            ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
            debug.verify(() -> Debug.logWarning(lines.capture(), anyString()));
            assertEquals(List.of(EMPTY_EVENT_CODE), lines.getAllValues(),
                    "an unpopulated schema is reported as such, not as a missing transport");
        }
        assertProbeResponse(HttpServletResponse.SC_SERVICE_UNAVAILABLE, READY_DOWN);
    }

    @Test
    public void aBrokerOutageAcrossTheFleetIsRateLimitedToOneCountedLine() throws Exception {
        // A broker outage makes every probe of every target fail at the polling interval, exactly as a
        // datasource outage does, so the same limit has to hold - and it has to hold on this code's own
        // window, not on one it borrows from another code.
        givenProbePath("/health/ready", null);
        Delegator delegator = delegatorRequiringCoherence(7L);
        LocalDispatcher dispatcher = dispatcherWithSubscribers(Map.of("only", subscriber(false)));
        when(servletContext.getAttribute(DISPATCHER_ATTRIBUTE)).thenReturn(dispatcher);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class);
                MockedStatic<Debug> debug = mockStatic(Debug.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(delegator);

            for (int probe = 0; probe < 20; probe++) {
                // Ageing the verdict out between probes is what the polling interval does at runtime.
                givenNoEstablishedVerdict();
                servlet.service(request, response);
            }

            debug.verify(() -> Debug.logWarning(anyString(), anyString()), times(1));
            assertEquals(19L, readinessLogCounter("READINESS_TRANSPORT_LOG_SUPPRESSED").get(),
                    "the occurrences the limit held back must be counted, not discarded");
            // No other code's bookkeeping may be touched by this one.
            assertEquals(0L, readinessLogCounter("READINESS_LOG_SUPPRESSED").get(),
                    "a missing transport must not claim the datasource code's suppressed occurrences");
            assertWindowStillOpen("READINESS_LOG_LAST_AT",
                    "a missing transport must not consume the window an unavailable datasource needs");
        }
        verify(response, times(20)).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    @Test
    public void theSuppressedTransportOccurrencesAreWrittenOutOnceTheBrokerReturns() throws Exception {
        // The count is otherwise carried only by the NEXT occurrence of the same code, so recovery -
        // which is precisely when the occurrences stop - would lose the tail of the burst. This is also
        // what tells a code wired into the flush apart from one wired only into the warning path.
        givenProbePath("/health/ready", null);
        Delegator delegator = delegatorRequiringCoherence(7L);
        GenericMessageListener listener = mock(GenericMessageListener.class);
        when(listener.isConnected()).thenReturn(false);
        LocalDispatcher dispatcher = dispatcherWithSubscribers(Map.of("only", listener));
        when(servletContext.getAttribute(DISPATCHER_ATTRIBUTE)).thenReturn(dispatcher);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class);
                MockedStatic<Debug> debug = mockStatic(Debug.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(delegator);

            for (int probe = 0; probe < 3; probe++) {
                givenNoEstablishedVerdict();
                servlet.service(request, response);
            }
            assertEquals(2L, readinessLogCounter("READINESS_TRANSPORT_LOG_SUPPRESSED").get(),
                    "two of the three outage probes must have been held back by the limit");

            // AbstractJmsListener.onException retries refresh() until the broker answers, so the flag
            // returns to true by itself - no restart and no operator action.
            when(listener.isConnected()).thenReturn(true);
            reopenWindow("READINESS_TRANSPORT_LOG_LAST_AT");
            givenNoEstablishedVerdict();
            servlet.service(request, response);

            ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
            debug.verify(() -> Debug.logWarning(lines.capture(), anyString()), times(2));
            assertEquals(TRANSPORT_EVENT_CODE, lines.getAllValues().get(0), "the first line of the outage");
            assertEquals(TRANSPORT_EVENT_CODE + " (2 further occurrences suppressed)", lines.getAllValues().get(1),
                    "the recovered probe must write out what the limit held back");
        }
        assertEquals(0L, readinessLogCounter("READINESS_TRANSPORT_LOG_SUPPRESSED").get(),
                "the flushed count must be cleared, so it can never be reported twice");
        // The recovered instance rejoins the target group by itself: this is a fail-closed state an
        // instance leaves on its own, not a latch an operator has to reset.
        verify(response).setStatus(HttpServletResponse.SC_OK);
        verify(response, times(3)).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    /*
     * Structural constraints
     */

    @Test
    public void everyFieldIsAPrivateConstantOrAThreadSafeCounter() {
        // One container-managed instance serves every probe of every load-balancer target
        // concurrently, so no per-instance state is permitted. The only mutable state allowed is the
        // rate-limit bookkeeping, which has to be atomic.
        for (Field field : HealthCheckServlet.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            assertTrue(Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers) && Modifier.isPrivate(modifiers),
                    "field " + field.getName() + " must be a private static final constant");
            Class<?> type = field.getType();
            assertTrue(IMMUTABLE_FIELD_TYPES.contains(type) || AtomicLong.class.equals(type)
                    || AtomicReference.class.equals(type),
                    "field " + field.getName() + " of type " + type.getName()
                            + " is neither immutable nor a thread-safe container");
        }
    }

    @Test
    public void theClassIsBothTheServletAndTheFilterThatShortCircuitsTheChain() throws Exception {
        // Both roles are required, and by the same class, because a probe has to be answered BEFORE the
        // webapp's ordinary filters run - see the filter-role section above for what ControlFilter and
        // ContextFilter would otherwise do to every probe of every instance - while the servlet mapping
        // is what makes the container resolve these paths to this component at all.
        assertTrue(jakarta.servlet.http.HttpServlet.class.isAssignableFrom(HealthCheckServlet.class),
                "the probe must be registrable as a servlet");
        assertTrue(Filter.class.isAssignableFrom(HealthCheckServlet.class),
                "the probe must be registrable as a filter, or it cannot short-circuit the chain");

        // Declared on this class rather than inherited: Filter.doFilter has no default, so a class that
        // merely implemented the interface without overriding it would not compile - but a doFilter that
        // was moved to a superclass or replaced by a differently-shaped helper would still satisfy
        // isAssignableFrom above while no longer being the chain entry point the container calls.
        Method doFilter = HealthCheckServlet.class.getDeclaredMethod("doFilter",
                ServletRequest.class, ServletResponse.class, FilterChain.class);
        assertTrue(Modifier.isPublic(doFilter.getModifiers()),
                "the container calls doFilter through the Filter interface, so it must be public");
        assertFalse(Modifier.isStatic(doFilter.getModifiers()),
                "the container calls doFilter on the instance it created");

        // The two roles must not be able to answer the same request differently, which is why the method
        // gate and the handler are shared rather than restated. Both are private and static, so neither
        // role can be given its own copy without that showing up here.
        for (String shared : List.of("methodRefused", "handleProbe")) {
            List<Method> declared = new ArrayList<>();
            for (Method method : HealthCheckServlet.class.getDeclaredMethods()) {
                if (shared.equals(method.getName())) {
                    declared.add(method);
                }
            }
            assertEquals(1, declared.size(),
                    shared + " must exist exactly once, so the servlet role and the filter role cannot diverge");
            int modifiers = declared.get(0).getModifiers();
            assertTrue(Modifier.isPrivate(modifiers) && Modifier.isStatic(modifiers),
                    shared + " must be a private static helper shared by both roles");
        }
    }

    @Test
    public void everyTuningConstantStaysInsideTheBandTheContractNeeds() throws Exception {
        // Every value is read from the servlet, so what is asserted here is the BAND each one has to
        // sit in, never the value itself: retuning stays free, while deleting a constant, zeroing it or
        // inflating it by orders of magnitude fails here. The timing tests above assert that the code
        // actually honours the values; this asserts that the values are still worth honouring.
        // The windows are held as nanoseconds, so each is converted to the millisecond band it has to
        // sit in; the poll interval and the waiter bound are plain numbers and are read as they are.
        long freshMillis = readinessConstant("READINESS_VERDICT_FRESH_NANOS") / NANOS_PER_MILLI;
        long graceMillis = readinessConstant("READINESS_VERDICT_GRACE_NANOS") / NANOS_PER_MILLI;
        long waitMillis = readinessConstant("READINESS_CHECK_WAIT_NANOS") / NANOS_PER_MILLI;
        long pollMillis = readinessConstant("READINESS_CHECK_POLL_MILLIS");
        long logIntervalMillis = readinessConstant("READINESS_LOG_INTERVAL_NANOS") / NANOS_PER_MILLI;
        long maxWaiters = readinessConstant("READINESS_MAX_WAITERS");

        // A verdict has to stand long enough to collapse a burst of overlapping probes onto one query,
        // and briefly enough that a load-balancer polling interval of the usual shape still measures the
        // datasource every round - otherwise readiness is a cached signal rather than a live one.
        assertTrue(freshMillis >= 100L && freshMillis <= 5000L,
                "the fresh window is " + freshMillis + "ms, which no longer both coalesces a burst and keeps"
                        + " readiness a live signal");
        // Grace only ever applies to a probe that found a check already running, so it has to outlast
        // the fresh window, and it has to expire well inside the two or three consecutive failures a
        // target group needs before it drains a target.
        assertTrue(graceMillis > freshMillis && graceMillis <= 30000L,
                "the grace window is " + graceMillis + "ms, which does not sit between the fresh window and the"
                        + " point at which a target group would already have drained the target");
        // The wait is what a probe with nothing to stand on uses instead of guessing. It must be long
        // enough for a merely slow check to finish inside it, and short enough that an unreachable
        // datasource cannot park a container thread anywhere near the pool-sleeptime a query could block
        // for - which is the whole reason the verdict is shared rather than measured per probe.
        assertTrue(waitMillis >= 100L && waitMillis <= 5000L,
                "the bounded wait is " + waitMillis + "ms, which is either too short to catch a slow check or long"
                        + " enough to hold a container thread");
        // The poll has to be positive, because Thread.sleep(0) is a spin, and fine enough that the wait
        // is a wait rather than a single sleep of its whole length.
        assertTrue(pollMillis > 0L && pollMillis <= waitMillis / 10L,
                "the poll interval is " + pollMillis + "ms, which is not a fine-grained poll of a " + waitMillis
                        + "ms wait");
        // One line per code per interval: often enough to see an outage, sparsely enough that continuous
        // polling by every load-balancer target cannot turn this endpoint into the log amplifier.
        assertTrue(logIntervalMillis >= 10000L && logIntervalMillis > waitMillis,
                "the rate-limit interval is " + logIntervalMillis + "ms, which no longer throttles a continuously"
                        + " polled endpoint");
        // The waiter bound has to sit well above legitimate probe concurrency and far below the
        // connector's thread pool, and the LOWER end is the binding one. A probe only waits when it has
        // no verdict to stand on, and the probes beyond the bound in that state are answered not-ready,
        // so a bound inside the burst a cold start produces would report a healthy instance not ready
        // for no reason other than that its probes overlapped. A load balancer uses several health-check
        // nodes per Availability Zone, and an orchestrator adds its own liveness and readiness probes,
        // so that burst is of the order of ten. Too high and it stops being a bound at all: Tomcat's
        // maxThreads defaults to 200.
        assertTrue(maxWaiters >= 24L && maxWaiters <= 64L,
                "the waiter bound is " + maxWaiters + ", which is either close enough to the burst a cold start"
                        + " produces to answer a healthy instance not ready, or large enough to occupy a"
                        + " connector's thread pool");
        // A bounded set of waiters each holding a thread for the wait window is the whole occupancy
        // readiness can reach, and it has to stay small enough to be irrelevant to a connector.
        assertTrue(maxWaiters * waitMillis <= 30000L,
                "a full waiter set can occupy threads for " + maxWaiters * waitMillis
                        + "ms in total, which is no longer a bound worth having");
    }

    @Test
    public void theStaticBookkeepingIsResetByBothLifecycleHooksSoNothingLeaksIntoAnotherClass() throws Exception {
        // Everything the servlet keeps is static, so it outlives this instance and is shared with every
        // other test class Gradle runs in the same worker JVM. The counters are enumerated from the
        // servlet rather than listed here, so one added to it later is covered the day it appears: it
        // gets poisoned, a reset that does not know about it leaves it poisoned, and this says so.
        Map<String, AtomicLong> counters = mutableReadinessCounters();
        assertFalse(counters.isEmpty(),
                "the servlet is expected to keep its verdict and its rate-limit bookkeeping in AtomicLong counters");
        // Both branches below have to be reachable, or one of the two kinds of bookkeeping would be going
        // unasserted while this still reported a pass.
        assertTrue(counters.keySet().stream().anyMatch(HealthCheckServletTests::isRateLimitWindow),
                "at least one rate-limit window is expected among " + counters.keySet());
        assertTrue(counters.keySet().stream().anyMatch(name -> !isRateLimitWindow(name)),
                "at least one occurrence count is expected among " + counters.keySet());

        // Two kinds of bookkeeping live in these fields and a fresh JVM leaves them in DIFFERENT states, so
        // each kind is poisoned and asserted in its own vocabulary. An occurrence count is fresh at zero. A
        // rate-limit window is a System.nanoTime() reading, and a monotonic origin is arbitrary, so zero is an
        // ordinary reading rather than an absent one: fresh is a full interval already elapsed, and poison is
        // therefore a reading taken NOW - which is exactly what a just-claimed window holds. Poisoning a
        // window with an ordinary number instead would leave it reading as never claimed, and a reset that
        // did nothing at all would pass.
        for (Class<? extends Annotation> hook : List.of(BeforeEach.class, AfterEach.class)) {
            Method lifecycleHook = lifecycleHook(hook);
            for (Map.Entry<String, AtomicLong> counter : counters.entrySet()) {
                counter.getValue().set(isRateLimitWindow(counter.getKey()) ? System.nanoTime() : POISONED_COUNTER);
            }

            lifecycleHook.invoke(this);

            for (Map.Entry<String, AtomicLong> counter : counters.entrySet()) {
                if (isRateLimitWindow(counter.getKey())) {
                    assertWindowStillOpen(counter.getKey(), counter.getKey() + " is still claimed after @"
                            + hook.getSimpleName() + ", so it would suppress the first line whatever the worker"
                            + " JVM runs next writes under that code");
                } else {
                    assertEquals(0L, counter.getValue().get(), counter.getKey() + " is still set after @"
                            + hook.getSimpleName() + ", so it would be read by whatever the worker JVM runs next");
                }
            }
        }
    }

    /*
     * Helpers
     */

    private void givenProbePath(String servletPath, String pathInfo) {
        when(request.getServletPath()).thenReturn(servletPath);
        when(request.getPathInfo()).thenReturn(pathInfo);
    }

    private void givenMethod(String method) {
        when(request.getMethod()).thenReturn(method);
    }

    /**
     * Builds a delegator whose {@code SequenceValueItem} count answers with the given number of rows.
     *
     * <p>The stubbed path is the one the probe takes: the entity helper that owns the entity's group,
     * reached through the model reader. That path ends in {@code GenericDAO.selectCountByCondition}
     * without {@code GenericDelegator} logging the throwable and its stack first, which is why the
     * probe uses it in place of {@code EntityQuery}.
     */
    private static Delegator delegatorCountingRows(long rows) throws Exception {
        Delegator delegator = mock(Delegator.class);
        ModelEntity modelEntity = givenReadinessModel(delegator);
        when(delegator.getEntityHelper(READINESS_ENTITY).findCountByCondition(delegator, modelEntity, null, null, null))
                .thenReturn(rows);
        return delegator;
    }

    /** Builds a delegator whose {@code SequenceValueItem} count fails with the given throwable. */
    private static Delegator delegatorFailingWith(Throwable failure) throws Exception {
        Delegator delegator = mock(Delegator.class);
        ModelEntity modelEntity = givenReadinessModel(delegator);
        when(delegator.getEntityHelper(READINESS_ENTITY).findCountByCondition(delegator, modelEntity, null, null, null))
                .thenThrow(failure);
        return delegator;
    }

    /**
     * Builds a delegator that counts the given number of rows AND declares distributed cache clear
     * enabled, which is what makes the cache-coherence dimension apply. A plain mock reports the flag
     * as false, which is why every other test in this class is unaffected by that dimension - and which
     * mirrors the committed configuration, where the flag is off.
     */
    private static Delegator delegatorRequiringCoherence(long rows) throws Exception {
        Delegator delegator = delegatorCountingRows(rows);
        when(delegator.useDistributedCacheClear()).thenReturn(true);
        return delegator;
    }

    /**
     * Builds the dispatcher a deployed webapp publishes, holding the given invalidation subscribers.
     *
     * <p>{@code JmsListenerFactory} is mocked rather than constructed: its constructor starts the
     * loader thread, and a unit test must neither start it nor reach a broker. The production code only
     * ever reads {@code getJMSListeners()} off it, which is a copy of its map.
     */
    private static LocalDispatcher dispatcherWithSubscribers(Map<String, GenericMessageListener> subscribers) {
        LocalDispatcher dispatcher = mock(LocalDispatcher.class);
        JmsListenerFactory listenerFactory = mock(JmsListenerFactory.class);
        when(listenerFactory.getJMSListeners()).thenReturn(subscribers);
        when(dispatcher.getJMSListeneFactory()).thenReturn(listenerFactory);
        return dispatcher;
    }

    /** Builds one invalidation subscriber reporting the given connection state. */
    private static GenericMessageListener subscriber(boolean connected) {
        GenericMessageListener listener = mock(GenericMessageListener.class);
        when(listener.isConnected()).thenReturn(connected);
        return listener;
    }

    /** Wires the model reader and the entity helper the readiness count resolves through. */
    private static ModelEntity givenReadinessModel(Delegator delegator) throws Exception {
        ModelReader modelReader = mock(ModelReader.class);
        ModelEntity modelEntity = mock(ModelEntity.class);
        when(delegator.getModelReader()).thenReturn(modelReader);
        when(modelReader.getModelEntity(READINESS_ENTITY)).thenReturn(modelEntity);
        when(delegator.getEntityHelper(READINESS_ENTITY)).thenReturn(mock(GenericHelper.class));
        return modelEntity;
    }

    /**
     * Drives one failing readiness probe with {@link Debug} statically mocked and returns the lines
     * the servlet wrote: the normal-level lines when {@code verbose} is false, the verbose
     * diagnostics when it is true. Returning the captured lines rather than asserting inside keeps
     * every expectation in the test that owns it.
     */
    private List<String> readinessFailureLog(Throwable failure, boolean verbose) throws Exception {
        givenProbePath("/health/ready", null);
        Delegator delegator = delegatorFailingWith(failure);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class);
                MockedStatic<Debug> debug = mockStatic(Debug.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(delegator);
            debug.when(Debug::verboseOn).thenReturn(verbose);

            servlet.service(request, response);

            ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
            if (verbose) {
                // Nothing derived from the failure may be written at any level, verbose included, so
                // there is nothing to capture and the returned list is expected to stay empty.
                debug.verify(() -> Debug.logVerbose(anyString(), anyString()), never());
                debug.verify(() -> Debug.logVerbose(any(Throwable.class), anyString()), never());
            } else {
                debug.verify(() -> Debug.logWarning(lines.capture(), anyString()));
                // A readiness failure is reported as the stable event code only. Logging the
                // exception itself - through any Throwable overload or at error level - is what would
                // put a connection URI, the failing SQL and a stack trace into the log.
                debug.verify(() -> Debug.logError(anyString(), anyString()), never());
                debug.verify(() -> Debug.logError(any(Throwable.class), anyString()), never());
                debug.verify(() -> Debug.logWarning(any(Throwable.class), anyString()), never());
                debug.verify(() -> Debug.logVerbose(anyString(), anyString()), never());
            }
            return lines.getAllValues();
        }
    }

    /**
     * Resets the servlet's static bookkeeping so each test starts in the state a freshly started JVM
     * is in. The fields are private constants holding atomic containers, so only their contents are
     * touched - nothing is made accessible for writing.
     */
    private static void resetReadinessLogState() throws Exception {
        reopenWindow("READINESS_LOG_LAST_AT");
        readinessLogCounter("READINESS_LOG_SUPPRESSED").set(0L);
        // Each event code owns its window and its suppressed count, so each has to be reset: an empty
        // schema must not leak into the next test any more than an unavailable datasource may.
        reopenWindow("READINESS_EMPTY_LOG_LAST_AT");
        readinessLogCounter("READINESS_EMPTY_LOG_SUPPRESSED").set(0L);
        // The bookkeeping for a probe that could obtain no verdict is separate state, and it must not
        // leak from one test into the next any more than a suppressed log line may.
        reopenWindow("READINESS_SHED_LOG_LAST_AT");
        readinessLogCounter("READINESS_SHED_LOG_SUPPRESSED").set(0L);
        // So is the bookkeeping for a probe refused a waiter slot, for the same reason.
        reopenWindow("READINESS_WAITERS_LOG_LAST_AT");
        readinessLogCounter("READINESS_WAITERS_LOG_SUPPRESSED").set(0L);
        // The waiter count is the one counter whose leak would change what a probe DOES rather than
        // what it logs: a test that left it at the bound would make the next test's probe take the
        // refused path, and one that left it negative would raise the bound for the rest of the JVM.
        readinessLogCounter("READINESS_WAITERS").set(0L);
        // So is the bookkeeping for a check that outran its deadline.
        reopenWindow("READINESS_TIMEOUT_LOG_LAST_AT");
        readinessLogCounter("READINESS_TIMEOUT_LOG_SUPPRESSED").set(0L);
        // And so is the bookkeeping for an absent cache-invalidation transport, which is the one code
        // that reports a healthy datasource and a missing fleet-coherence dependency.
        reopenWindow("READINESS_TRANSPORT_LOG_LAST_AT");
        readinessLogCounter("READINESS_TRANSPORT_LOG_SUPPRESSED").set(0L);
        // The shared verdict is what lets overlapping probes answer without each querying the
        // datasource. A fresh JVM holds none, and one test's verdict must not answer the next test's
        // probe, so it is cleared along with the permit that says a check is running.
        givenNoEstablishedVerdict();
        readinessLogCounter("READINESS_CHECK_RUNNING").set(0L);
        // The delegator lookup is throttled through its own window, which a fresh JVM has open.
        reopenWindow("DELEGATOR_LOOKUP_LAST_AT");
    }

    /** Moves the rate-limit window into the past, which is what the passage of time does at runtime. */
    private static void reopenReadinessLogWindow() throws Exception {
        reopenWindow("READINESS_LOG_LAST_AT");
    }

    /**
     * Puts the named rate-limit window in the state a freshly started JVM has it in: never claimed.
     *
     * <p>Zero cannot express that, which is the whole reason this helper exists. The servlet measures
     * every interval as a difference of two {@link System#nanoTime()} readings, and a monotonic origin
     * is arbitrary - zero is an ordinary reading, not an absent one - so "never claimed" has to be
     * said as a full interval already elapsed, exactly as the servlet's own seed does.
     */
    private static void reopenWindow(String name) throws Exception {
        readinessLogCounter(name).set(System.nanoTime() - readinessConstant("READINESS_LOG_INTERVAL_NANOS"));
    }

    /**
     * Whether the named static counter holds a rate-limit window rather than an occurrence count.
     *
     * <p>Decided from the name because that is what the servlet's own naming states: every window is the
     * instant its code was last written, and is named {@code ..._LAST_AT}. The distinction matters because the
     * two are fresh in different states - a count at zero, a window a full interval in the past - so a test
     * that enumerates the fields reflectively has to know which it is holding.</p>
     *
     * @param name the declared field name
     * @return {@code true} if the field is a rate-limit window
     */
    private static boolean isRateLimitWindow(String name) {
        return name.endsWith("_LAST_AT");
    }

    /** Asserts the named rate-limit window is still open, so the next occurrence of its code is written. */
    private static void assertWindowStillOpen(String name, String message) throws Exception {
        long window = readinessLogCounter(name).get();
        assertTrue(System.nanoTime() - window >= readinessConstant("READINESS_LOG_INTERVAL_NANOS"), message);
    }

    /** Asserts the named rate-limit window has been claimed, so further occurrences are suppressed. */
    private static void assertWindowClaimed(String name, String message) throws Exception {
        long window = readinessLogCounter(name).get();
        assertTrue(System.nanoTime() - window < readinessConstant("READINESS_LOG_INTERVAL_NANOS"), message);
    }

    /**
     * Puts the servlet in the state a first probe after start-up finds: no verdict has been
     * established, so the probe has to measure the datasource itself.
     */
    private static void givenNoEstablishedVerdict() throws Exception {
        readinessVerdict().set(null);
    }

    /**
     * Publishes a verdict of the given readiness aged by the given number of nanoseconds, which is
     * what an earlier probe leaves behind.
     *
     * <p>The age is given on the monotonic clock because that is the only clock the servlet measures
     * a verdict's age on: the pair it stores is the answer together with the {@code System.nanoTime()}
     * reading it was established at, so an age is expressed here by subtracting from a reading taken
     * now. Deriving it from the wall clock instead - which the previous encoding allowed, since an
     * epoch millisecond is always positive and left the sign free to carry the answer - would produce
     * a verdict of an unrelated age, because the two clocks have unrelated origins.
     */
    private static void givenEstablishedVerdict(boolean ready, long ageNanos) throws Exception {
        readinessVerdict().set(verdict(ready, System.nanoTime() - ageNanos));
    }

    /**
     * Builds the servlet's own verdict pair. The record is private to the servlet, as it should be -
     * nothing outside needs it - so it is instantiated reflectively rather than by widening its
     * visibility for a test's convenience.
     */
    private static Object verdict(boolean ready, long establishedAtNanos) throws Exception {
        Class<?> type = Class.forName(HealthCheckServlet.class.getName() + "$Verdict");
        Constructor<?> constructor = type.getDeclaredConstructor(boolean.class, long.class);
        constructor.setAccessible(true);
        return constructor.newInstance(ready, establishedAtNanos);
    }

    /**
     * Reads the verdict a check published. The record is private to the servlet, so its component
     * accessors are reached reflectively for the same reason its constructor is.
     */
    private static boolean verdictReady(Object publishedVerdict) throws Exception {
        Method component = publishedVerdict.getClass().getDeclaredMethod("ready");
        component.setAccessible(true);
        return (boolean) component.invoke(publishedVerdict);
    }

    /** Reads the {@code System.nanoTime()} reading a published verdict was established at. */
    private static long verdictEstablishedAtNanos(Object publishedVerdict) throws Exception {
        Method component = publishedVerdict.getClass().getDeclaredMethod("establishedAtNanos");
        component.setAccessible(true);
        return (long) component.invoke(publishedVerdict);
    }

    /** Marks a readiness check as already running, which is what an overlapping probe arrives into. */
    private static void givenReadinessCheckRunning() throws Exception {
        readinessLogCounter("READINESS_CHECK_RUNNING").set(1L);
    }

    /** Reports whether the permit that admits one check at a time is currently held. */
    private static boolean readinessCheckPermitHeld() throws Exception {
        return readinessLogCounter("READINESS_CHECK_RUNNING").get() != 0L;
    }

    /**
     * Occupies every waiter slot, which is the state a probe arriving into a flood finds.
     *
     * <p>The bound is read from the servlet rather than restated, so retuning it does not have to be
     * mirrored here, and the count is set to exactly the bound - not above it - because that is the
     * only value the servlet's own compare-and-set loop can ever produce.
     */
    private static void givenWaiterSetFull() throws Exception {
        readinessLogCounter("READINESS_WAITERS").set(readinessConstant("READINESS_MAX_WAITERS"));
    }

    /**
     * Reads one of the servlet's tuning constants, so a test can express an age relative to the
     * window it is exercising instead of restating a number the servlet owns.
     */
    private static long readinessConstant(String name) throws Exception {
        Field field = HealthCheckServlet.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(null);
    }

    /**
     * Reads one of the servlet's declared probe paths, so a test can assert against the path the class
     * itself names instead of restating the literal a second time.
     */
    private static String probePathConstant(String name) throws Exception {
        Field field = HealthCheckServlet.class.getDeclaredField(name);
        field.setAccessible(true);
        return (String) field.get(null);
    }

    private static AtomicLong readinessLogCounter(String name) throws Exception {
        Field field = HealthCheckServlet.class.getDeclaredField(name);
        field.setAccessible(true);
        return (AtomicLong) field.get(null);
    }

    /**
     * Every counter the servlet keeps, by field name and in declaration order, discovered from the
     * class rather than listed, so a counter added to it later is covered by whatever uses this without
     * that list having to be maintained in step.
     */
    private static Map<String, AtomicLong> mutableReadinessCounters() throws Exception {
        Map<String, AtomicLong> counters = new LinkedHashMap<>();
        for (Field field : HealthCheckServlet.class.getDeclaredFields()) {
            if (AtomicLong.class.equals(field.getType())) {
                field.setAccessible(true);
                counters.put(field.getName(), (AtomicLong) field.get(null));
            }
        }
        return counters;
    }

    /**
     * The single lifecycle hook of this class carrying the given annotation.
     *
     * <p>Requiring exactly one is part of the contract: two hooks of the same kind could drift apart,
     * and none means the state this class mutates is never put back.
     */
    private static Method lifecycleHook(Class<? extends Annotation> annotation) {
        List<Method> hooks = new ArrayList<>();
        for (Method method : HealthCheckServletTests.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(annotation)) {
                hooks.add(method);
            }
        }
        assertEquals(1, hooks.size(), "exactly one @" + annotation.getSimpleName()
                + " hook is expected, calling the one reset both hooks share");
        return hooks.get(0);
    }

    /**
     * What a probe run on a worker thread reports back: how long the servlet call took, and whether the
     * thread's interrupt flag was still set when it returned.
     */
    private record WorkerProbe(long elapsedMillis, boolean interruptFlagRestored) { }

    /**
     * Runs one readiness probe on a worker thread and reports what that thread observed, optionally
     * interrupting it the way a container interrupts a request thread it is reclaiming.
     *
     * <p>The thread is a daemon and the answer is collected through a {@link Future} with a strict
     * timeout, so a probe that never returns fails this test instead of hanging the build, and anything
     * thrown inside the worker - an assertion error included - is rethrown on the JUnit thread rather
     * than reported to a thread group nothing is watching. The pool is always shut down and awaited.
     *
     * <p>The interruption is issued as soon as the worker signals that it has started, so it is either
     * already pending when the wait loop makes its first blocking call or it arrives during one; either
     * way the loop observes it. Mockito static mocking cannot be used to observe a worker thread, since
     * it is confined to the thread that opened it, so a caller asserts through the servlet's own
     * counters and through the response mocks instead.
     */
    private WorkerProbe readinessProbeOnAWorkerThread(boolean interruptTheWait) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        ExecutorService pool = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = daemonWorker(runnable);
            worker.set(thread);
            return thread;
        });
        WorkerProbe observed;
        boolean workerOutlivedTheTest;
        try {
            // Declared as a Callable so submit(Callable) is selected without relying on overload
            // resolution, and so the observation is carried back by the Future rather than by a field.
            Callable<WorkerProbe> probe = () -> {
                started.countDown();
                long startedAt = System.nanoTime();
                servlet.service(request, response);
                return new WorkerProbe((System.nanoTime() - startedAt) / NANOS_PER_MILLI,
                        Thread.currentThread().isInterrupted());
            };
            Future<WorkerProbe> answered = pool.submit(probe);
            if (interruptTheWait) {
                assertTrue(started.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS), "the probe thread never started");
                worker.get().interrupt();
            }
            observed = answered.get(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            workerOutlivedTheTest = !pool.awaitTermination(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        // Asserted after the block so a genuine probe failure above propagates unmasked; the shutdown
        // itself has already happened either way.
        assertFalse(workerOutlivedTheTest, "the probe thread must have terminated before the test ends");
        return observed;
    }

    /** What one simultaneous burst of readiness probes produced: the responses, the bodies, the checks. */
    private record BurstOutcome(List<HttpServletResponse> responses, List<StringWriter> bodies, long datasourceChecks) { }

    /**
     * Drives the given number of readiness probes simultaneously against a datasource whose count takes
     * the given time and returns the given row count, starting from a JVM state with no verdict at all -
     * a cold start - and reports what every probe was answered.
     *
     * <p>The probes are released by a barrier with one party per probe, so none of them proceeds until
     * every one is already running: the burst is genuinely simultaneous rather than threads trickling in
     * as the pool starts them, which is what makes a bound on concurrent waiters exercisable at all. The
     * delegator is published on the {@link ServletContext} the way {@code ContextFilter.init()} publishes
     * it, because a Mockito static mock is confined to the thread that opened it and would intercept
     * nothing a worker thread does. Every worker is a daemon and every answer is collected through a
     * {@link Future} with a strict timeout, so a probe that deadlocks fails the test instead of hanging
     * the build, and an assertion error inside a worker is rethrown on the JUnit thread.
     *
     * <p>The check occupies real time on purpose: it is the interval a check spends in flight that the
     * other probes of the burst have to survive, and an instantaneous stub would let them all read a
     * published verdict without any of them ever taking the waiting path.
     */
    private BurstOutcome coldStartBurst(int probes, long checkMillis, long rows) throws Exception {
        Delegator delegator = mock(Delegator.class);
        ModelEntity modelEntity = givenReadinessModel(delegator);
        AtomicLong datasourceChecks = new AtomicLong(0L);
        when(delegator.getEntityHelper(READINESS_ENTITY).findCountByCondition(delegator, modelEntity, null, null, null))
                .thenAnswer(invocation -> {
                    datasourceChecks.incrementAndGet();
                    Thread.sleep(checkMillis);
                    return rows;
                });
        when(servletContext.getAttribute("delegator")).thenReturn(delegator);
        List<HttpServletResponse> responses = new ArrayList<>();
        List<StringWriter> bodies = new ArrayList<>();
        List<Callable<Void>> probeCalls = new ArrayList<>();
        CyclicBarrier released = new CyclicBarrier(probes);

        for (int probe = 0; probe < probes; probe++) {
            HttpServletRequest concurrentRequest = mock(HttpServletRequest.class);
            when(concurrentRequest.getMethod()).thenReturn("GET");
            when(concurrentRequest.getServletPath()).thenReturn("/health/ready");
            when(concurrentRequest.getServletContext()).thenReturn(servletContext);
            HttpServletResponse concurrentResponse = mock(HttpServletResponse.class);
            StringWriter body = new StringWriter();
            when(concurrentResponse.getWriter()).thenReturn(new PrintWriter(body));
            responses.add(concurrentResponse);
            bodies.add(body);
            // Declared as a Callable rather than passed inline, so submit(Callable) is selected without
            // relying on overload resolution, and so a failure inside it is carried to the calling
            // thread by the Future instead of being thrown where JUnit cannot see it.
            probeCalls.add(() -> {
                // Bounded, so a worker that never arrives breaks the barrier for the others - which
                // reports itself immediately - instead of parking the whole burst forever.
                released.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                servlet.service(concurrentRequest, concurrentResponse);
                return null;
            });
        }

        // The pool must have at least as many threads as the barrier has parties, or the barrier can
        // never be tripped; a fixed pool of exactly the probe count makes that explicit.
        ExecutorService pool = Executors.newFixedThreadPool(probes, HealthCheckServletTests::daemonWorker);
        boolean workersOutlivedTheTest;
        try {
            List<Future<Void>> answered = new ArrayList<>();
            for (Callable<Void> probeCall : probeCalls) {
                answered.add(pool.submit(probeCall));
            }
            for (Future<Void> probe : answered) {
                probe.get(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            workersOutlivedTheTest = !pool.awaitTermination(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        // Asserted after the block rather than inside it, so a genuine probe failure above propagates
        // unmasked; the shutdown itself has already happened either way.
        assertFalse(workersOutlivedTheTest, "every probe thread must have terminated before the test ends");
        return new BurstOutcome(responses, bodies, datasourceChecks.get());
    }

    /** Starts a sampler watching the servlet's waiter count. The caller must stop it in a finally. */
    private static WaiterSampler startWaiterSampler() throws Exception {
        WaiterSampler sampler = new WaiterSampler(readinessLogCounter("READINESS_WAITERS"));
        sampler.start();
        return sampler;
    }

    /**
     * Watches the servlet's waiter count from another thread while a flood runs, recording the highest
     * value it ever observed.
     *
     * <p>The bound is a property of the counter itself - it may never be above the bound at any instant,
     * whatever order probes arrive in - so it is observed directly rather than inferred from response
     * timings. Inferring it would also be weaker: timings can only show that few threads waited, not
     * that many could never have.
     */
    private static final class WaiterSampler {

        private final AtomicLong waiters;
        private final AtomicLong peak = new AtomicLong(0L);
        private final Thread thread;
        private volatile boolean sampling = true;

        private WaiterSampler(AtomicLong observed) {
            waiters = observed;
            thread = daemonWorker(this::sample);
        }

        private void start() {
            thread.start();
        }

        private void sample() {
            while (sampling) {
                recordPeak();
                try {
                    Thread.sleep(SAMPLE_INTERVAL_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            // Once more after the loop, so a peak reached between the last poll and the stop request is
            // still accounted for rather than silently weakening the assertion.
            recordPeak();
        }

        private void recordPeak() {
            peak.accumulateAndGet(waiters.get(), Math::max);
        }

        private long stopAndReadPeak() throws InterruptedException {
            sampling = false;
            thread.join(TimeUnit.SECONDS.toMillis(WORKER_TIMEOUT_SECONDS));
            assertFalse(thread.isAlive(), "the waiter sampler must have terminated before the test ends");
            return peak.get();
        }
    }

    /**
     * A worker thread for the pools above. Daemon status is what keeps a probe that somehow
     * never returns cannot from holding the JVM open once the build has finished with it.
     */
    private static Thread daemonWorker(Runnable runnable) {
        Thread worker = new Thread(runnable, "health-probe-under-test");
        worker.setDaemon(true);
        return worker;
    }

    /**
     * The holder of the shared verdict. Typed as {@code AtomicReference<Object>} because the value it
     * holds is private to the servlet; erasure makes the cast harmless and the alternative would be to
     * publish the servlet's verdict type for a test's benefit.
     */
    @SuppressWarnings("unchecked")
    private static AtomicReference<Object> readinessVerdict() throws Exception {
        Field field = HealthCheckServlet.class.getDeclaredField("READINESS_VERDICT");
        field.setAccessible(true);
        return (AtomicReference<Object>) field.get(null);
    }

    /** Invokes the servlet's deadline validation, which runs once at class initialisation in production. */
    private static long resolveConfiguredDeadline() throws Exception {
        Method method = HealthCheckServlet.class.getDeclaredMethod("configuredCheckDeadlineMillis");
        method.setAccessible(true);
        return (long) method.invoke(null);
    }

    /**
     * Asserts the whole response contract for one verdict: status, exact body, media type, encoding,
     * cache directive and safe headers, that the encoding was chosen before the writer was obtained,
     * that the container error-page machinery was never engaged, and that no session was created or
     * read.
     */
    private void assertProbeResponse(int expectedStatus, String expectedBody) throws Exception {
        verify(response).setStatus(expectedStatus);
        verify(response).setContentType(CONTENT_TYPE);
        verify(response).setCharacterEncoding(ENCODING);
        verify(response).setHeader(CACHE_HEADER, CACHE_VALUE);
        verify(response).setHeader(CONTENT_TYPE_OPTIONS_HEADER, CONTENT_TYPE_OPTIONS_VALUE);
        verify(response).setHeader(FRAME_OPTIONS_HEADER, FRAME_OPTIONS_VALUE);
        verify(response).setHeader(REFERRER_POLICY_HEADER, REFERRER_POLICY_VALUE);
        assertEquals(expectedBody, responseBody.toString(), "probe body");

        InOrder order = inOrder(response);
        order.verify(response).setContentType(CONTENT_TYPE);
        order.verify(response).setCharacterEncoding(ENCODING);
        order.verify(response).getWriter();

        // setStatus, never sendError: sendError would replace this document with an HTML error page.
        verify(response, never()).sendError(anyInt());
        verify(response, never()).sendError(anyInt(), anyString());
        verify(response, never()).sendRedirect(anyString());

        // The endpoint is polled continuously, so it must stay session-free and anonymous.
        verify(request, never()).getSession();
        verify(request, never()).getSession(anyBoolean());
        verify(request, never()).getUserPrincipal();
        verify(request, never()).getRemoteUser();
        verify(request, never()).isUserInRole(anyString());

        // No verdict, not even a rejection, may materialise the request body: that is the whole
        // reason an anonymous caller cannot turn these paths into a heap or CPU sink.
        verify(request, never()).getInputStream();
        verify(request, never()).getReader();
        verify(request, never()).getParameterMap();
        verify(request, never()).getParameter(anyString());
    }

    /**
     * The executor the readiness check is run on in a test, in each of the four states the servlet has
     * to cope with.
     *
     * <p>{@code INLINE} runs the task on the calling thread, so a test can observe what the check did
     * through the per-thread seams it already replaces, while the production code path - the permit,
     * the deadline, the verdict and the event codes - runs exactly as written.
     * {@link AbstractExecutorService} supplies {@code submit}, which wraps the task in a future and
     * hands it to {@link #execute}; running it there means the future is already complete when the
     * servlet asks for its value, so a deadline that is honoured produces no wait at all.
     *
     * <p>The other three make branches reachable that a real datasource could only be raced into:
     * {@code REFUSE} is a shut-down or saturated executor, {@code PENDING} is a check that has not
     * finished, and {@code DEAD} is one that died in a way it does not handle itself. {@code PENDING}
     * reports the timeout at once rather than waiting the deadline out, because whether the deadline
     * itself is honoured is a different claim and is measured by its own test.
     */
    private static final class ProbeCheckExecutor extends AbstractExecutorService {

        private enum Behaviour { INLINE, REFUSE, PENDING, DEAD }

        private final Behaviour behaviour;
        private volatile boolean stopped;

        private ProbeCheckExecutor(Behaviour behaviour) {
            this.behaviour = behaviour;
        }

        static ProbeCheckExecutor inline() {
            return new ProbeCheckExecutor(Behaviour.INLINE);
        }

        static ProbeCheckExecutor refusing() {
            return new ProbeCheckExecutor(Behaviour.REFUSE);
        }

        static ProbeCheckExecutor neverCompleting() {
            return new ProbeCheckExecutor(Behaviour.PENDING);
        }

        static ProbeCheckExecutor dying() {
            return new ProbeCheckExecutor(Behaviour.DEAD);
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            switch (behaviour) {
            case PENDING:
                // Deliberately never run: it is the state of "still running" that is under test, and a
                // task that never runs is indistinguishable from one that has not finished.
                return new PendingFuture<>();
            case DEAD:
                return new DeadFuture<>();
            default:
                return super.submit(task);
            }
        }

        @Override
        public void execute(Runnable command) {
            if (behaviour == Behaviour.REFUSE) {
                throw new RejectedExecutionException("executor refused the readiness check");
            }
            command.run();
        }

        @Override
        public void shutdown() {
            stopped = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            stopped = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return stopped;
        }

        @Override
        public boolean isTerminated() {
            return stopped;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return stopped;
        }
    }

    /** A future that never has a value: every bounded get reports the timeout at once. */
    private static final class PendingFuture<T> implements Future<T> {

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public T get() throws InterruptedException {
            throw new InterruptedException("no value will ever arrive");
        }

        @Override
        public T get(long timeout, TimeUnit unit) throws TimeoutException {
            throw new TimeoutException("the readiness check is still running");
        }
    }

    /** A future whose task died, which the probe must translate into a fail-closed verdict. */
    private static final class DeadFuture<T> implements Future<T> {

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public T get() throws ExecutionException {
            throw new ExecutionException(new StackOverflowError("the readiness check died"));
        }

        @Override
        public T get(long timeout, TimeUnit unit) throws ExecutionException {
            throw new ExecutionException(new StackOverflowError("the readiness check died"));
        }
    }

    /**
     * Builds and initialises a servlet that records which dispatch method a request reaches, so a
     * test can pin the dispatch itself and not only the response it produces.
     */
    private DispatchRecordingServlet givenDispatchRecordingServlet() throws Exception {
        ServletConfig servletConfig = mock(ServletConfig.class);
        when(servletConfig.getServletContext()).thenReturn(servletContext);
        DispatchRecordingServlet recorder = new DispatchRecordingServlet();
        recorder.init(servletConfig);
        return recorder;
    }

    /**
     * Records the dispatch method the {@code service} gate hands a request to, then serves it exactly
     * as the production class does. Overriding only records; every response the tests assert is still
     * written by {@link HealthCheckServlet} itself.
     */
    @SuppressWarnings("serial")
    private static final class DispatchRecordingServlet extends HealthCheckServlet {

        private final List<String> dispatches = new ArrayList<>();

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            dispatches.add("doGet");
            super.doGet(request, response);
        }

        @Override
        protected void doHead(HttpServletRequest request, HttpServletResponse response) throws IOException {
            dispatches.add("doHead");
            super.doHead(request, response);
        }

        private List<String> dispatched() {
            return dispatches;
        }
    }

    /**
     * Reproduces the container's HTTP HEAD body suppression: the status line and the headers are
     * delegated to the real response while the entity body is written to a sink that never reaches
     * the client.
     */
    private static final class BodySuppressingResponse extends HttpServletResponseWrapper {

        private final StringWriter sink = new StringWriter();
        private final PrintWriter writer = new PrintWriter(sink);

        BodySuppressingResponse(HttpServletResponse delegate) {
            super(delegate);
        }

        @Override
        public PrintWriter getWriter() {
            return writer;
        }

        private String suppressedBody() {
            writer.flush();
            return sink.toString();
        }
    }
}
