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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.description;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.ofbiz.entity.Delegator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The liveness and readiness endpoints a load balancer probes.
 *
 * <p><strong>Hermetic.</strong> No container is started, no socket is opened and no database is reached:
 * the servlet is exercised in its FILTER role against mocked servlet objects, and the delegator is a mock
 * whose count method is stubbed, so the readiness query runs entirely in memory.
 *
 * <p>What is asserted here is the contract a target group depends on. A probe endpoint that answers 200
 * when the instance cannot serve keeps a broken instance in rotation; one that answers 503 when the
 * instance is healthy takes a working instance out. Both are asserted, along with the three properties
 * that are easy to regress and invisible in production until they matter: that a probe never allocates an
 * {@code HttpSession}, that readiness is not re-queried on every probe, and that a method other than GET
 * or HEAD is refused with the header saying what is allowed.
 *
 * <p>The reserved aliases are asserted for the same reason: {@code /control/health/live} answered 200 with a
 * rendered error page is a health check that can never report ill health, so the refusal, its absent body and
 * its absent session are pinned here, along with the fact that no OTHER spelling under the control prefix is
 * touched.
 */
public final class HealthCheckServletTests {

    private static final String LIVE = "/health/live";
    private static final String READY = "/health/ready";

    private HealthCheckServlet probe;
    private ServletContext context;
    private Delegator delegator;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;
    private StringWriter body;

    @BeforeEach
    public void setUp(TestInfo about) throws Exception {
        delegator = mock(Delegator.class);
        // The readiness verdict is cached PER DELEGATOR and the cache is static, so each test names its own
        // delegator: that is what keeps these tests independent of each other and of their order, without
        // reaching into the class to clear anything.
        when(delegator.getDelegatorName()).thenReturn(about.getDisplayName());
        // EntityQuery.use() asks the delegator for itself, so a bare mock would hand back null and every
        // readiness evaluation would fail for the wrong reason.
        when(delegator.getDelegator()).thenReturn(delegator);
        context = mock(ServletContext.class);
        when(context.getAttribute("delegator")).thenReturn(delegator);
        FilterConfig config = mock(FilterConfig.class);
        when(config.getServletContext()).thenReturn(context);
        probe = new HealthCheckServlet();
        probe.init(config);

        request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        response = mock(HttpServletResponse.class);
        body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));
        chain = mock(FilterChain.class);
    }

    @Test
    public void livenessAnswersUpWithoutConsultingTheDatabase() throws Exception {
        at(LIVE);

        probe.doFilter(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        assertEquals("{\"status\":\"UP\"}", body.toString(), "liveness must answer a constant body");
        // Liveness says "this JVM is running", so it must not depend on anything outside the JVM. A
        // liveness probe that consulted the database would have the whole fleet restarted by the
        // orchestrator during a database outage, turning a recoverable fault into an outage of everything.
        verify(context, never()).getAttribute(anyString());
    }

    @Test
    public void readinessAnswersUpWhenTheDatabaseAnswers() throws Exception {
        databaseAnswers(1L);
        at(READY);

        probe.doFilter(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        assertEquals("{\"status\":\"UP\",\"database\":\"UP\"}", body.toString(),
                "readiness must report the database it checked");
    }

    @Test
    public void readinessAnswersUnavailableWhenTheDatabaseCannotBeReached() throws Exception {
        databaseFails();
        at(READY);

        probe.doFilter(request, response, chain);

        // 503, and not an exception and not a 200: the load balancer has to be able to take this instance
        // out of rotation, and a probe that propagated the failure would answer 500 through the container's
        // error machinery instead.
        verify(response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        assertEquals("{\"status\":\"DOWN\",\"database\":\"DOWN\"}", body.toString(),
                "readiness must report which dependency was down");
    }

    @Test
    public void anEmptySequencerIsStillReady() throws Exception {
        databaseAnswers(0L);
        at(READY);

        probe.doFilter(request, response, chain);

        // A newly provisioned deployment carrying only seed data has an empty sequencer: nothing writes to
        // SequenceValueItem until a request causes an identifier to be sequenced. The instance is nonetheless
        // able to serve, and reporting it unready deadlocks it behind a load balancer - the target group sends
        // no request until readiness answers 200, and readiness would not answer 200 until a request arrived.
        // Measured on a fresh seed-only database: 503 indefinitely, then 200 after one direct request. What the
        // probe asks is whether the query RUNS; a schema that has not been created fails it, because the
        // entity's own table is absent, and that case is the test above.
        verify(response).setStatus(HttpServletResponse.SC_OK);
        assertEquals("{\"status\":\"UP\",\"database\":\"UP\"}", body.toString(),
                "an instance whose schema exists but has sequenced nothing yet must be reported ready");
    }

    @Test
    public void aProbeNeverAllocatesASession() throws Exception {
        databaseAnswers(1L);

        at(LIVE);
        probe.doFilter(request, response, chain);
        at(READY);
        probe.doFilter(request, response, chain);

        // The reason this class is registered as a filter ahead of ControlFilter, which calls getSession()
        // unconditionally. One session per probe, at the rate a target group probes, is a slow leak whose
        // expiry work costs a transaction and a query each.
        verify(request, never()).getSession();
        verify(request, never()).getSession(true);
        // And the chain is not continued for a probe, which is what keeps the rest of it from doing so.
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    public void aReadinessVerdictIsReusedRatherThanRequeriedOnEveryProbe() throws Exception {
        databaseAnswers(1L);

        for (int attempt = 0; attempt < 5; attempt++) {
            at(READY);
            body.getBuffer().setLength(0);
            probe.doFilter(request, response, chain);
        }

        // Five probes, one query. A target group probes every few seconds from every instance it fronts,
        // so an unbounded probe would add a database round trip per probe per instance for no information.
        verify(delegator, times(1)).findCountByCondition(eq("SequenceValueItem"), any(), any(), any(), any());
        verify(response, times(5)).setStatus(HttpServletResponse.SC_OK);
    }

    @Test
    public void readinessReportsOnlyTheDependenciesThisDeploymentIsConfiguredWith() {
        // The key that is ABSENT is the assertion. A deployment storing content in the database and
        // publishing no cache invalidations has exactly one dependency, and its readiness body is the two-key
        // one this endpoint has always answered - which is what a target group, a monitoring check or an
        // operator's eye has been configured against.
        assertEquals("{\"status\":\"UP\",\"database\":\"UP\"}", HealthCheckServlet.render(true, null, null),
                "a database-only deployment must report on the database alone");
        assertEquals("{\"status\":\"UP\",\"database\":\"UP\",\"contentStore\":\"UP\"}",
                HealthCheckServlet.render(true, "", null),
                "a deployment with an external content store must report on it");
        assertEquals("{\"status\":\"UP\",\"database\":\"UP\",\"messaging\":\"UP\"}",
                HealthCheckServlet.render(true, null, ""),
                "a deployment publishing cache invalidations must report on the bus carrying them");
        assertEquals("{\"status\":\"UP\",\"database\":\"UP\",\"contentStore\":\"UP\",\"messaging\":\"UP\"}",
                HealthCheckServlet.render(true, "", ""), "and one with both must report both");
    }

    @Test
    public void aConfiguredDependencyThatCannotBeUsedTakesTheInstanceOutOfRotation() {
        // The finding this closes: an instance whose object store had gone answered its probe 200/UP seven
        // milliseconds before failing a content read, so a load balancer kept sending it content requests.
        assertEquals("{\"status\":\"DOWN\",\"database\":\"UP\",\"contentStore\":\"DOWN\"}",
                HealthCheckServlet.render(true, "the bucket did not answer", null),
                "a store that cannot be used must make the instance not ready even though the database is up");
        assertEquals("{\"status\":\"DOWN\",\"database\":\"UP\",\"messaging\":\"DOWN\"}",
                HealthCheckServlet.render(true, null, "the listeners are not connected"),
                "and so must a message bus that cannot carry invalidations, because writes then roll back");
        assertEquals("{\"status\":\"DOWN\",\"database\":\"DOWN\",\"contentStore\":\"UP\"}",
                HealthCheckServlet.render(false, "", null),
                "each dependency reports its own state, so the body says which one is at fault");
    }

    @Test
    public void theBodyNamesTheDependencyButNeverTheInfrastructureBehindIt() {
        String body = HealthCheckServlet.render(true,
                "The content store answered [403] when asked about the bucket [acme-customer-content]", null);

        // This endpoint is unauthenticated by design - a target group presents no credential - so the body is
        // a status and nothing else. The reason names buckets, endpoints, mount points and broker addresses,
        // and belongs in the log.
        assertEquals("{\"status\":\"DOWN\",\"database\":\"UP\",\"contentStore\":\"DOWN\"}", body,
                "the readiness body must carry the verdict alone");
        assertFalse(body.contains("acme-customer-content"), "a bucket name must not reach an anonymous caller");
        assertFalse(body.contains("403"), "nor the store's own answer");
    }

    @Test
    public void readinessSaysNothingAboutAMessageBusThisDeploymentDoesNotDeclare() throws Exception {
        databaseAnswers(1L);
        // Distributed cache clear ON, but this configuration declares no listening JMS server - the
        // serviceMessenger example in serviceengine.xml is commented out and the entrypoint renders a real one
        // only when a broker is configured. A dependency that does not exist is not reported and cannot make
        // an instance unready.
        when(delegator.useDistributedCacheClear()).thenReturn(true);
        at(READY);

        probe.doFilter(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        assertEquals("{\"status\":\"UP\",\"database\":\"UP\"}", body.toString(),
                "an undeclared message bus must add no key and no verdict");
    }

    @Test
    public void aDependencyVerdictIsReusedRatherThanReaskedOnEveryProbe() {
        AtomicInteger asked = new AtomicInteger();
        HealthCheckServlet.DependencyCheck check =
                new HealthCheckServlet.DependencyCheck("the test dependency", () -> {
                    asked.incrementAndGet();
                    return "";
                });

        for (int attempt = 0; attempt < 5; attempt++) {
            assertEquals("", check.evaluate(), "a reachable dependency must be reported usable");
        }

        // Five probes, one question. A probe runs every few seconds from every instance, and a dependency
        // asked once per probe per instance is load the dependency did not ask for.
        assertEquals(1, asked.get(), "the verdict must be cached rather than re-asked on every probe");
    }

    @Test
    public void aDependencyThatStopsAnsweringIsReportedDownWithoutQueueingMoreWork() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        CountDownLatch hang = new CountDownLatch(1);
        HealthCheckServlet.DependencyCheck check =
                new HealthCheckServlet.DependencyCheck("the test dependency", () -> {
                    asked.incrementAndGet();
                    hang.await();
                    return "";
                });

        try {
            long startedAt = System.nanoTime();
            String first = check.evaluate();
            long waitedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
            String second = check.evaluate();

            // Bounded: no client bounds a probe usefully on its own - the object store's own API call timeout
            // is 45 s - and a probe that hung would be read by a target group as a lost one rather than as an
            // unready instance.
            assertFalse(first.isEmpty(), "a dependency that did not answer must be reported down");
            assertFalse(second.isEmpty(), "and must go on being reported down while it does not answer");
            assertTrue(waitedMillis < 5000L, "the probe must not wait past its deadline, waited " + waitedMillis);
            // And it is asked ONCE: re-submitting every probe interval during an outage is how a probe turns a
            // dependency outage into a thread per interval, forever.
            assertEquals(1, asked.get(), "a question that has not come back must not have another queued behind it");
        } finally {
            hang.countDown();
        }
    }

    @Test
    public void everyMethodOtherThanGetAndHeadIsRefusedWithTheAllowedOnes() throws Exception {
        for (String method : new String[] {"POST", "PUT", "DELETE", "OPTIONS", "TRACE", "PATCH", "PROPFIND"}) {
            HttpServletResponse refused = mock(HttpServletResponse.class);
            when(refused.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
            at(LIVE);
            when(request.getMethod()).thenReturn(method);

            probe.doFilter(request, refused, chain);

            verify(refused, times(1)).setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            verify(refused).setHeader("Allow", "GET, HEAD");
            verify(refused, never()).setStatus(HttpServletResponse.SC_OK);
        }
    }

    @Test
    public void headAnswersExactlyWhatGetWouldWithoutTheChain() throws Exception {
        at(LIVE);
        when(request.getMethod()).thenReturn("HEAD");

        probe.doFilter(request, response, chain);

        // The container discards the body of a HEAD response itself, so the handler writes the same bytes
        // and the status and headers are identical to GET's - which is what a probe configured for HEAD
        // relies on.
        verify(response).setStatus(HttpServletResponse.SC_OK);
        verify(response).setHeader("Cache-Control", "no-store");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    public void aPathThatIsNotAProbeIsPassedOnUntouched() throws Exception {
        at("/control/main");

        probe.doFilter(request, response, chain);

        // Registered on two exact patterns, so this cannot normally happen - and if the mapping were ever
        // widened by mistake, the request must go on to the rest of the chain rather than be answered here.
        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(HttpServletResponse.SC_OK);
    }

    @Test
    public void everyProbeResponseForbidsCaching() throws Exception {
        databaseAnswers(1L);
        at(READY);

        probe.doFilter(request, response, chain);

        // A cached verdict is a stale verdict: an intermediary that held a 200 would keep answering it for
        // an instance that had since failed.
        verify(response).setHeader("Cache-Control", "no-store");
    }

    @Test
    public void everyResponseCarriesTheSameSecurityHeadersAsAnOrdinaryOne() throws Exception {
        databaseAnswers(1L);

        // All five answers this class can give, each on its own response so the header set can be asserted
        // per answer: a scan that found one spelling of a probe response without the headers would report
        // exactly the inconsistency these headers exist to remove.
        assertHeadersOn(LIVE, "GET", "liveness");
        assertHeadersOn(READY, "GET", "readiness, database up");
        assertHeadersOn(LIVE, "POST", "the 405 for an unsupported method");
        assertHeadersOn("/control" + LIVE, "GET", "the 404 refusing the control-prefixed alias");
        databaseFails();
        assertHeadersOn(READY, "GET", "readiness, database down");
    }

    @Test
    public void theSecurityHeadersCostNoSessionAndNoDatabaseLookup() throws Exception {
        at(LIVE);

        probe.doFilter(request, response, chain);

        // The headers are literals applied in the servlet, NOT obtained by running the filter chain, which
        // is what keeps a probe from allocating a session - and keeps liveness answerable while the database
        // is unreachable. Liveness must still consult nothing outside the JVM.
        verify(response).setHeader("x-frame-options", "sameorigin");
        verify(request, never()).getSession();
        verify(request, never()).getSession(true);
        verify(chain, never()).doFilter(any(), any());
        verify(context, never()).getAttribute(anyString());
    }

    /**
     * Asserts that one answer of this servlet carries the full security-header set.
     *
     * @param path the path within the webapp to request
     * @param method the HTTP method to request it with
     * @param answer what that combination answers, for the assertion message
     * @throws Exception if the probe cannot be run
     */
    private void assertHeadersOn(String path, String method, String answer) throws Exception {
        HttpServletResponse answered = mock(HttpServletResponse.class);
        when(answered.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        at(path);
        when(request.getMethod()).thenReturn(method);

        probe.doFilter(request, answered, chain);

        verify(answered, description(answer + " must forbid caching")).setHeader("Cache-Control", "no-store");
        verify(answered, description(answer + " must carry x-frame-options"))
                .setHeader("x-frame-options", "sameorigin");
        verify(answered, description(answer + " must carry x-content-type-options"))
                .setHeader("x-content-type-options", "nosniff");
        verify(answered, description(answer + " must carry X-XSS-Protection"))
                .setHeader("X-XSS-Protection", "1; mode=block");
        verify(answered, description(answer + " must carry Referrer-Policy"))
                .setHeader("Referrer-Policy", "no-referrer-when-downgrade");
        verify(answered, description(answer + " must carry strict-transport-security"))
                .setHeader("strict-transport-security", "max-age=31536000; includeSubDomains");
    }

    @Test
    public void twoWebappsWithDifferentDelegatorsDoNotShareAVerdict() throws Exception {
        databaseAnswers(1L);
        at(READY);
        probe.doFilter(request, response, chain);
        verify(response).setStatus(HttpServletResponse.SC_OK);

        // A second webapp in the same JVM, registered from its own web.xml against its own delegator whose
        // database is down. The verdict cache is static and shared, so it must be keyed by the delegator it
        // is a verdict about - otherwise this webapp would be reported ready on the strength of the first
        // one's database.
        Delegator other = mock(Delegator.class);
        when(other.getDelegator()).thenReturn(other);
        when(other.getDelegatorName()).thenReturn("a-different-delegator");
        when(other.findCountByCondition(anyString(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("that database is unreachable"));
        ServletContext otherContext = mock(ServletContext.class);
        when(otherContext.getAttribute("delegator")).thenReturn(other);
        FilterConfig otherConfig = mock(FilterConfig.class);
        when(otherConfig.getServletContext()).thenReturn(otherContext);
        HealthCheckServlet otherProbe = new HealthCheckServlet();
        otherProbe.init(otherConfig);
        HttpServletResponse otherResponse = mock(HttpServletResponse.class);
        when(otherResponse.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        otherProbe.doFilter(request, otherResponse, chain);

        verify(otherResponse).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        verify(otherResponse, never()).setStatus(HttpServletResponse.SC_OK);
    }

    @Test
    public void aProbePathSpelledBehindTheControlServletIsRefused() throws Exception {
        at("/control" + LIVE);

        probe.doFilter(request, response, chain);

        // Left to the chain this reaches a controller with no request-map for it, and the rendered error view
        // carries the status the response already had - 200 - which a target group checking for 200 reads as a
        // healthy instance for as long as it is pointed there. Refused with a status a balancer cannot mistake,
        // and without entering the chain at all.
        verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
        verify(response, never()).setStatus(HttpServletResponse.SC_OK);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    public void theReadinessAliasIsRefusedWithNoBodyNoCacheAndNoSession() throws Exception {
        at("/control" + READY);

        probe.doFilter(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
        // No body at all, and no intermediary may keep the refusal: it is a fact about how this deployment is
        // mapped, so a cached copy would survive the descriptor being corrected.
        verify(response).setContentLength(0);
        verify(response).setHeader("Cache-Control", "no-store");
        // The whole reason the refusal happens here rather than in the chain: the filters ahead of the control
        // servlet call getSession() unconditionally, so every misdirected probe would otherwise mint a session
        // and emit a cookie for a caller that keeps neither.
        verify(request, never()).getSession();
        verify(request, never()).getSession(true);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    public void aNearMissUnderTheControlPrefixKeepsOrdinaryRouting() throws Exception {
        at("/control/health/livez");

        probe.doFilter(request, response, chain);

        // Only the two exact aliases are reserved. Nothing else under /control/health is treated as one, so
        // every other spelling keeps the routing it has always had.
        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(HttpServletResponse.SC_NOT_FOUND);
    }

    /**
     * Points the request at a path within the webapp.
     *
     * @param path the path the container would report, with the context path already removed
     */
    private void at(String path) {
        when(request.getServletPath()).thenReturn(path);
        when(request.getPathInfo()).thenReturn(null);
    }

    /**
     * Makes the readiness query succeed with the given row count.
     *
     * @param rows the count the delegator answers
     * @throws Exception if the stub cannot be installed
     */
    private void databaseAnswers(long rows) throws Exception {
        // Plain any() rather than any(Type.class): the fields-to-select and having arguments are null for
        // this query, and a typed matcher does not match null.
        when(delegator.findCountByCondition(anyString(), any(), any(), any(), any())).thenReturn(rows);
    }

    /**
     * Makes the readiness query fail the way an unreachable database does.
     *
     * @throws Exception if the stub cannot be installed
     */
    private void databaseFails() throws Exception {
        when(delegator.findCountByCondition(anyString(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("the connection pool is exhausted"));
    }
}
