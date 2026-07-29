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
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.datasource.GenericHelper;
import org.apache.ofbiz.entity.model.ModelEntity;
import org.apache.ofbiz.entity.model.ModelReader;
import org.apache.ofbiz.webapp.WebAppUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

/**
 * Behavioural contract of {@link HealthCheckServlet}, the unauthenticated liveness and readiness
 * probe served at {@code /health/live} and {@code /health/ready}.
 *
 * <p>Every test invokes a production entry point - {@code service} for the servlet role,
 * {@code doFilter} for the filter role - and asserts the observable response: status code, exact
 * JSON document, content type, character encoding, cache header and, where it matters, the
 * {@code Allow} header. Mocks only supply the collaborators; no assertion is ever made against a
 * value a mock was configured to return. The static seams the class depends on,
 * {@link WebAppUtil#getDelegator} and {@link Debug}, are replaced with scoped static mocks inside
 * try-with-resources so nothing leaks into another test, and no test opens a database connection,
 * reads configuration or touches the network.
 *
 * <p>Three properties are pinned here that the probes' exposure depends on, since they answer
 * anonymous callers: the request body is never read, no method other than GET or HEAD is served,
 * and a readiness failure never writes an internal detail to the log.
 */
public final class HealthCheckServletTests {

    private static final String LIVE_UP = "{\"status\":\"UP\"}";
    private static final String READY_UP = "{\"status\":\"UP\",\"database\":\"UP\"}";
    private static final String READY_DOWN = "{\"status\":\"DOWN\",\"database\":\"DOWN\"}";
    private static final String UNKNOWN = "{\"status\":\"DOWN\"}";

    private static final String CONTENT_TYPE = "application/json";
    private static final String ENCODING = "UTF-8";
    private static final String CACHE_HEADER = "Cache-Control";
    private static final String CACHE_VALUE = "no-cache, no-store, must-revalidate";
    private static final String ALLOW_HEADER = "Allow";
    private static final String ALLOW_VALUE = "GET, HEAD";

    // The safe response headers every verdict must carry. Answering before the chain means the
    // probe response never reaches RequestHandler, which is where UtilHttp applies these for an
    // ordinary view, so the servlet has to apply them itself. The values mirror the UtilHttp
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

    /** The framework-tier entity the readiness probe counts, mirroring {@code CommonServices.ping}. */
    private static final String READINESS_ENTITY = "SequenceValueItem";

    /** Field types that are safe to share between the servlet and the filter instance unguarded. */
    private static final List<Class<?>> IMMUTABLE_FIELD_TYPES =
            List.of(String.class, java.util.Set.class, int.class, long.class);

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
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Liveness
     * ---------------------------------------------------------------------------------------------
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
        // no parameter, no input stream. Any future login, permission or session access fails here.
        verify(request).getServletPath();
        verify(request).getPathInfo();
        verify(request).getMethod();
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
     * ---------------------------------------------------------------------------------------------
     * Readiness
     * ---------------------------------------------------------------------------------------------
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
     * ---------------------------------------------------------------------------------------------
     * Readiness under overlapping probes: one check, one shared verdict, never a manufactured DOWN
     *
     * A load-balancer target group is probed by one node per Availability Zone, so several readiness
     * probes are in flight at once in normal operation. Answering a healthy instance "not ready"
     * because probes overlapped drains it, which is why every case below asserts that concurrency
     * alone can never change a verdict, and that a verdict is only ever one the datasource produced.
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void overlappingProbesOnAHealthyDatasourceAreAllAnsweredUp() throws Exception {
        // The regression this class exists to prevent: with an admission bound, the third and every
        // later simultaneous probe was answered 503 DOWN while the datasource was demonstrably up.
        Delegator delegator = mock(Delegator.class);
        ModelEntity modelEntity = givenReadinessModel(delegator);
        AtomicLong datasourceChecks = new AtomicLong(0L);
        // A real count is not instantaneous, and it is the time a check spends in flight that the
        // overlapping probes have to survive, so the stub occupies that time deliberately.
        when(delegator.getEntityHelper(READINESS_ENTITY).findCountByCondition(delegator, modelEntity, null, null, null))
                .thenAnswer(invocation -> {
                    datasourceChecks.incrementAndGet();
                    Thread.sleep(150L);
                    return 7L;
                });
        // Published on the ServletContext, the way ContextFilter.init() publishes it, so the probe
        // needs no static seam and this test can run genuinely concurrent threads.
        when(servletContext.getAttribute("delegator")).thenReturn(delegator);
        int probes = 16;
        List<HttpServletResponse> responses = new ArrayList<>();
        List<StringWriter> bodies = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
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
            threads.add(new Thread(() -> {
                try {
                    released.await();
                    servlet.service(concurrentRequest, concurrentResponse);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }));
        }
        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }

        for (int probe = 0; probe < probes; probe++) {
            verify(responses.get(probe)).setStatus(HttpServletResponse.SC_OK);
            verify(responses.get(probe), never()).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            assertEquals(READY_UP, bodies.get(probe).toString(), "probe " + probe + " answered from a shared verdict");
        }
        // One check answered all sixteen: what is bounded is what the datasource sees, not what a probe
        // is allowed to be told, which is the difference between coalescing and an admission bound.
        assertEquals(1L, datasourceChecks.get(), "overlapping probes must share one check, not queue or shed");
    }

    @Test
    public void anEstablishedVerdictAnswersAnOverlappingProbeWithoutTouchingTheDatasource() throws Exception {
        givenProbePath("/health/ready", null);
        // What a burst lands on: a verdict another probe established a few hundred milliseconds ago.
        givenEstablishedVerdict(true, 200L);

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
        givenEstablishedVerdict(false, 200L);

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
        givenEstablishedVerdict(true, readinessConstant("READINESS_VERDICT_FRESH_MILLIS") + 50L);

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
        givenEstablishedVerdict(true, readinessConstant("READINESS_VERDICT_FRESH_MILLIS") + 100L);

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
        givenEstablishedVerdict(true, readinessConstant("READINESS_VERDICT_GRACE_MILLIS") + 1000L);

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
     * ---------------------------------------------------------------------------------------------
     * Unmapped, empty, malformed and look-alike sub-paths
     * ---------------------------------------------------------------------------------------------
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
     * ---------------------------------------------------------------------------------------------
     * Method allow-list: only GET and HEAD are served
     * ---------------------------------------------------------------------------------------------
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
     * ---------------------------------------------------------------------------------------------
     * Request bodies are refused from the headers, never read
     * ---------------------------------------------------------------------------------------------
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
     * ---------------------------------------------------------------------------------------------
     * Safe response headers are retained even though the chain is never entered
     * ---------------------------------------------------------------------------------------------
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
     * ---------------------------------------------------------------------------------------------
     * Filter role: the probe never reaches the rest of the chain
     * ---------------------------------------------------------------------------------------------
     */

    @ParameterizedTest(name = "{0} is answered by the filter and terminates the chain")
    @ValueSource(strings = {"/health/live", "/health/ready"})
    public void aProbePathIsAnsweredByTheFilterWithoutContinuingTheChain(String probePath) throws Exception {
        givenProbePath(probePath, null);
        FilterChain chain = mock(FilterChain.class);
        Delegator delegator = delegatorCountingRows(1L);

        try (MockedStatic<WebAppUtil> webAppUtil = mockStatic(WebAppUtil.class)) {
            webAppUtil.when(() -> WebAppUtil.getDelegator(servletContext)).thenReturn(delegator);

            servlet.doFilter(request, response, chain);
        }
        // THE point of the filter role: ControlFilter, CacheFilter, ContextFilter and SameSiteFilter
        // are downstream of this call, so not continuing the chain is what keeps the probe free of a
        // session, of a JSESSIONID and of the JSON body parser.
        verify(chain, never()).doFilter(any(), any());
        assertProbeResponse(HttpServletResponse.SC_OK, "/health/live".equals(probePath) ? LIVE_UP : READY_UP);
    }

    @ParameterizedTest(name = "{0} is passed through the filter untouched")
    @ValueSource(strings = {"/control/main", "/health", "/healthz/live", "/health/live/", "/health/liveness", "/ping.txt"})
    public void aNonProbePathIsPassedThroughTheFilterUntouched(String path) throws Exception {
        givenProbePath(path, null);
        FilterChain chain = mock(FilterChain.class);

        servlet.doFilter(request, response, chain);

        // Anything that is not exactly a probe path stays the responsibility of the ordinary chain,
        // so the filter is safe even if it is ever mapped more widely than the two exact patterns.
        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(anyInt());
        verify(response, never()).getWriter();
        assertEquals("", responseBody.toString(), "a pass-through must not write a body");
    }

    @Test
    public void theFilterEnforcesTheSameMethodAndBodyRulesAsTheServlet() throws Exception {
        givenProbePath("/health/live", null);
        givenMethod("POST");
        FilterChain chain = mock(FilterChain.class);

        servlet.doFilter(request, response, chain);

        // A refused probe is still a probe: it must be answered here and never handed downstream.
        verify(chain, never()).doFilter(any(), any());
        verify(response).setHeader(ALLOW_HEADER, ALLOW_VALUE);
        assertProbeResponse(HttpServletResponse.SC_METHOD_NOT_ALLOWED, UNKNOWN);
    }

    @Test
    public void aNonHttpRequestIsPassedThroughInsteadOfBeingCast() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        jakarta.servlet.ServletRequest plainRequest = mock(jakarta.servlet.ServletRequest.class);
        jakarta.servlet.ServletResponse plainResponse = mock(jakarta.servlet.ServletResponse.class);

        servlet.doFilter(plainRequest, plainResponse, chain);

        verify(chain).doFilter(plainRequest, plainResponse);
        verifyNoMoreInteractions(plainRequest);
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Readiness failures are logged as a stable code, rate limited and sanitised
     * ---------------------------------------------------------------------------------------------
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
        // The counter used to be carried only by the NEXT occurrence of the same code, so everything
        // suppressed after the last one - the tail of every burst, and the whole of a burst that ends
        // inside its own window - never reached the log at all. A later probe has to write it out.
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
        assertEquals(0L, readinessLogCounter("READINESS_LOG_LAST_AT").get(),
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
        assertEquals(0L, readinessLogCounter("READINESS_LOG_LAST_AT").get(),
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
        assertEquals(0L, readinessLogCounter("READINESS_LOG_LAST_AT").get(),
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
     * ---------------------------------------------------------------------------------------------
     * Structural guarantees
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void everyFieldIsAPrivateConstantOrAThreadSafeCounter() {
        // The class is instantiated twice by the container - once as a servlet, once as a filter -
        // and both instances serve concurrently, so no per-instance state is permitted. The only
        // mutable state allowed is the rate-limit bookkeeping, which has to be atomic.
        for (Field field : HealthCheckServlet.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            assertTrue(Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers) && Modifier.isPrivate(modifiers),
                    "field " + field.getName() + " must be a private static final constant");
            Class<?> type = field.getType();
            assertTrue(IMMUTABLE_FIELD_TYPES.contains(type) || AtomicLong.class.equals(type),
                    "field " + field.getName() + " of type " + type.getName()
                            + " is neither immutable nor a thread-safe counter");
        }
    }

    @Test
    public void theClassIsBothAServletAndAFilterSoItCanTerminateTheChain() {
        assertTrue(jakarta.servlet.http.HttpServlet.class.isAssignableFrom(HealthCheckServlet.class),
                "the probe must remain registrable as a servlet");
        assertTrue(jakarta.servlet.Filter.class.isAssignableFrom(HealthCheckServlet.class),
                "the probe must be registrable as a filter, which is how it terminates the chain");
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Helpers
     * ---------------------------------------------------------------------------------------------
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
     * Resets the servlet's static rate-limit bookkeeping so each test starts in the state a freshly
     * started JVM is in. The fields are private constants holding {@link AtomicLong} counters, so
     * only their contents are touched - nothing is made accessible for writing.
     */
    private static void resetReadinessLogState() throws Exception {
        readinessLogCounter("READINESS_LOG_LAST_AT").set(0L);
        readinessLogCounter("READINESS_LOG_SUPPRESSED").set(0L);
        // Each event code owns its window and its suppressed count, so each has to be reset: an empty
        // schema must not leak into the next test any more than an unavailable datasource may.
        readinessLogCounter("READINESS_EMPTY_LOG_LAST_AT").set(0L);
        readinessLogCounter("READINESS_EMPTY_LOG_SUPPRESSED").set(0L);
        // The bookkeeping for a probe that could obtain no verdict is separate state, and it must not
        // leak from one test into the next any more than a suppressed log line may.
        readinessLogCounter("READINESS_SHED_LOG_LAST_AT").set(0L);
        readinessLogCounter("READINESS_SHED_LOG_SUPPRESSED").set(0L);
        // The shared verdict is what lets overlapping probes answer without each querying the
        // datasource. A fresh JVM holds none, and one test's verdict must not answer the next test's
        // probe, so it is cleared along with the flag that says a check is running.
        readinessLogCounter("READINESS_VERDICT").set(0L);
        readinessLogCounter("READINESS_CHECK_RUNNING").set(0L);
        // The delegator lookup is throttled through its own window, which a fresh JVM has open.
        readinessLogCounter("DELEGATOR_LOOKUP_LAST_AT").set(0L);
    }

    /** Moves the rate-limit window into the past, which is what the passage of time does at runtime. */
    private static void reopenReadinessLogWindow() throws Exception {
        readinessLogCounter("READINESS_LOG_LAST_AT").set(0L);
    }

    /**
     * Puts the servlet in the state a first probe after start-up finds: no verdict has been
     * established, so the probe has to measure the datasource itself.
     */
    private static void givenNoEstablishedVerdict() throws Exception {
        readinessLogCounter("READINESS_VERDICT").set(0L);
    }

    /**
     * Publishes a verdict of the given readiness aged by the given number of milliseconds, which is
     * what an earlier probe leaves behind. The encoding is the servlet's own: the sign carries the
     * verdict and the magnitude carries the instant it was established.
     */
    private static void givenEstablishedVerdict(boolean ready, long ageMillis) throws Exception {
        long establishedAt = System.currentTimeMillis() - ageMillis;
        readinessLogCounter("READINESS_VERDICT").set(ready ? establishedAt : -establishedAt);
    }

    /** Marks a readiness check as already running, which is what an overlapping probe arrives into. */
    private static void givenReadinessCheckRunning() throws Exception {
        readinessLogCounter("READINESS_CHECK_RUNNING").set(1L);
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

    private static AtomicLong readinessLogCounter(String name) throws Exception {
        Field field = HealthCheckServlet.class.getDeclaredField(name);
        field.setAccessible(true);
        return (AtomicLong) field.get(null);
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
