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
package org.apache.ofbiz.catalina.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.catalina.Context;
import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.ofbiz.webapp.control.HealthCheckServlet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import jakarta.servlet.http.Cookie;

/**
 * Behavioural contract of the health-probe exemption in {@link CrossSubdomainSessionValve}.
 *
 * <p>This valve is installed at ENGINE scope by {@code CatalinaContainer.prepareTomcatEngineValves} when
 * {@code enable-cross-subdomain-sessions} is switched on, which means it runs <em>before</em> any webapp's
 * filter chain and before the servlet that answers a probe without ever touching a session - that endpoint
 * is a servlet and nothing else. Its whole purpose is to force a session into existence so the session
 * cookie can be reissued for a parent domain, and without an exemption it did that to health probes too:
 * every anonymous probe minted an {@code HttpSession} and a {@code JSESSIONID}, a probe client never returns
 * a cookie, so each probe produced a session that lived until it expired. A target group polls every few
 * seconds, per instance, indefinitely, so that is an unbounded number of sessions - with their listeners,
 * their expiry bookkeeping and their heap - created by an unauthenticated caller, on the one endpoint
 * documented as creating none.
 *
 * <p>Every test drives the production entry point, {@code invoke}, and asserts the observable effects:
 * whether a session was requested, whether the response was touched at all, and that the request always
 * continues down the pipeline. The exemption is also asserted to be <em>equivalent</em> to
 * {@link HealthCheckServlet#isProbePath(String)} rather than to a list of literals restated here, because
 * a second copy of the probe paths is the failure this design exists to prevent: a valve exempting a
 * stale spelling would quietly resume creating a session for every probe.
 */
public final class CrossSubdomainSessionValveTests {

    private static final String PROBE_LIVE = "/health/live";
    private static final String PROBE_READY = "/health/ready";

    /** The context a probe arrives in, mirroring the webapp that declares the probe servlet mapping. */
    private static final String CONTEXT_PATH = "/webtools";

    /**
     * Paths that are not probes and must therefore keep the valve's normal behaviour. Near misses are
     * deliberate: no {@code /health} prefix is reserved, so only the two exact paths may be exempt.
     */
    private static final List<String> NON_PROBE_PATHS = List.of(
            "/control/main",
            "/health",
            "/health/",
            "/health/live/",
            "/health/ready/",
            "/health/liveness",
            "/health/readiness",
            "/HEALTH/LIVE",
            "/Health/Ready",
            "/healthz/live",
            "/health//live",
            "/health/live2",
            "/webtools/health/live",
            "");

    private Request request;
    private Response response;
    private Context context;
    private Valve next;
    private CrossSubdomainSessionValve valve;

    @BeforeEach
    public void setUp() {
        request = mock(Request.class);
        response = mock(Response.class);
        context = mock(Context.class);
        when(request.getContext()).thenReturn(context);
        when(context.getPath()).thenReturn(CONTEXT_PATH);
        next = mock(Valve.class);
        valve = new CrossSubdomainSessionValve();
        valve.setNext(next);
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * The exemption
     * ---------------------------------------------------------------------------------------------
     */

    @ParameterizedTest(name = "{0} is passed through without a session")
    @ValueSource(strings = {PROBE_LIVE, PROBE_READY})
    public void aProbeIsPassedStraightThroughWithoutASessionBeingCreated(String path) throws Exception {
        givenMappedPath(path, null);

        valve.invoke(request, response);

        // getSession(true) is the whole mechanism this valve exists to trigger, and it is what created a
        // session per probe. Neither overload may be called: getSession() delegates to getSession(true).
        verify(request, never()).getSession(anyBoolean());
        verify(request, never()).getSession();
        // Nothing is written to the response either, so the probe answer carries no Set-Cookie header
        // from this valve - which is what the endpoint's documented "no session, no cookie" rests on.
        verifyNoInteractions(response);
        // And the request still reaches the rest of the pipeline: exempt means untouched, not dropped.
        verify(next).invoke(request, response);
    }

    @ParameterizedTest(name = "servletPath={0} pathInfo={1} is passed through without a session")
    @CsvSource({"/health, /live", "/health, /ready"})
    public void aProbeReachingTheValveUnderAPrefixMappingIsExemptToo(String servletPath, String pathInfo)
            throws Exception {
        // A prefix url-pattern such as /health/* splits the same path across the two accessors, so the
        // exemption has to concatenate them exactly as HealthCheckServlet does, or a deployment that
        // maps the probes by prefix would lose the exemption without any visible change.
        givenMappedPath(servletPath, pathInfo);

        valve.invoke(request, response);

        verify(request, never()).getSession(anyBoolean());
        verifyNoInteractions(response);
        verify(next).invoke(request, response);
    }

    @ParameterizedTest(name = "{0} recognised from the request URI alone is exempt")
    @ValueSource(strings = {PROBE_LIVE, PROBE_READY})
    public void aProbeRecognisableOnlyFromTheRequestUriIsStillExempt(String path) throws Exception {
        // A probe path can reach a context that does not map it - a mis-pointed target group, or a proxy
        // rewriting the prefix - and in that case the mapper has no servlet path to report. The decoded
        // request URI, with the context path removed, is the fallback that keeps such a request exempt
        // too, because it must not mint a session either. It is decoded and normalised by CoyoteAdapter
        // before the engine pipeline runs, so no ../, %2e or ;jsessionid spelling can reach the
        // comparison.
        givenMappedPath(null, null);
        when(request.getDecodedRequestURI()).thenReturn(CONTEXT_PATH + path);

        valve.invoke(request, response);

        verify(request, never()).getSession(anyBoolean());
        verifyNoInteractions(response);
        verify(next).invoke(request, response);
    }

    @ParameterizedTest(name = "{0} in the root context is exempt")
    @ValueSource(strings = {PROBE_LIVE, PROBE_READY})
    public void aProbeInTheRootContextIsExempt(String path) throws Exception {
        // The root context reports an empty context path, so the substring arithmetic has to leave the
        // path unchanged rather than trimming a character off it.
        when(context.getPath()).thenReturn("");
        givenMappedPath(null, null);
        when(request.getDecodedRequestURI()).thenReturn(path);

        valve.invoke(request, response);

        verify(request, never()).getSession(anyBoolean());
        verify(next).invoke(request, response);
    }

    @Test
    public void aProbeCarryingASessionCookieIsStillExemptAndItsCookieIsLeftAlone() throws Exception {
        // A probe would not normally carry a cookie, but a browser or a misconfigured monitor can. The
        // exemption must be unconditional: rewriting the cookie domain for a probe response would put a
        // Set-Cookie header on an endpoint documented as carrying none.
        givenMappedPath(PROBE_READY, null);
        when(request.getCookies()).thenReturn(new Cookie[] {new Cookie("JSESSIONID", "0123456789ABCDEF") });

        valve.invoke(request, response);

        verify(request, never()).getSession(anyBoolean());
        verify(request, never()).getCookies();
        verifyNoInteractions(response);
        verify(next).invoke(request, response);
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Everything else keeps the behaviour the valve was installed for
     * ---------------------------------------------------------------------------------------------
     */

    @ParameterizedTest(name = "{0} still gets its session")
    @ValueSource(strings = {
        "/control/main",
        "/health",
        "/health/",
        "/health/live/",
        "/health/liveness",
        "/healthz/live",
        "/HEALTH/LIVE",
        "/health//live",
        "/webtools/health/live" })
    public void anOrdinaryRequestStillHasItsSessionForcedIntoExistence(String path) throws Exception {
        // The exemption may not become a hole in the feature. Every path that is not exactly a probe
        // path - the near misses above included, since no /health prefix is reserved - must still be
        // given a session, which is the only reason this valve is installed.
        givenMappedPath(path, null);

        valve.invoke(request, response);

        verify(request).getSession(true);
        verify(next).invoke(request, response);
    }

    @Test
    public void aRequestWhoseUriLiesOutsideItsOwnContextIsNotTreatedAsAProbe() throws Exception {
        // The fallback strips the context path by prefix, so a URI that does not begin with it must be
        // refused outright rather than having a fixed number of characters removed and then compared.
        givenMappedPath(null, null);
        when(request.getDecodedRequestURI()).thenReturn("/elsewhere" + PROBE_LIVE);

        valve.invoke(request, response);

        verify(request).getSession(true);
        verify(next).invoke(request, response);
    }

    @Test
    public void aRequestWithNoContextAndNoUriIsHandledWithoutFailing() throws Exception {
        // An engine-scope valve can see a request the mapper resolved to no context at all - a request
        // for an unknown host or an unmapped context. It must not be mistaken for a probe, and it must
        // not fail here either: this valve is not the place a routing error is reported.
        givenMappedPath(null, null);
        when(request.getContext()).thenReturn(null);
        when(request.getDecodedRequestURI()).thenReturn(null);

        valve.invoke(request, response);

        verify(request).getSession(true);
        verify(next).invoke(request, response);
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * The cookie-rewriting path runs at engine scope, where no delegator has been published
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void aReturningClientIsServedEvenThoughNoDelegatorHasBeenPublishedYet() throws Exception {
        // The cookie-rewriting path runs only when the request already carries a session cookie, so it is
        // reached by every RETURNING client and by none of the first ones - which is what made its failure
        // so easy to miss. It asks for the delegator through the request attribute that ContextFilter
        // publishes, and at ENGINE scope that filter has not run, so the attribute is absent. Handing the
        // resulting null to the entity-aware property lookup reached EntityQuery.use(null), whose
        // NullPointerException is not a GenericEntityException and so was not caught; thrown from the
        // outermost valve, above ErrorReportValve, it left Coyote to answer a bare HTTP 500 with no body
        // and nothing logged. Enabling cross-subdomain sessions therefore turned every second request from
        // every client into a silent 500, on every path.
        givenMappedPath("/control/main", null);
        givenReturningClient("localhost");

        valve.invoke(request, response);

        // The assertion is that the pipeline was reached at all: before the fix nothing below this valve
        // ran, because invoke threw on the way in.
        verify(request).getSession(true);
        verify(next).invoke(request, response);
    }

    @ParameterizedTest(name = "a returning client on host {0} is served")
    @ValueSource(strings = {
        "localhost",
        "shop.example.com",
        "a.b.shop.example.com",
        "127.0.0.1",
        "example.com" })
    public void aReturningClientIsServedWhateverHostItNamed(String serverName) throws Exception {
        // Exercised across the host shapes the domain-widening step branches on - a single label with no
        // parent domain, a sub-domain, a deeper sub-domain, a dotted-quad address and a bare two-label
        // domain - because the property lookup that used to fail happens BEFORE any of them is examined.
        // Every shape must be served, and none may reach the pipeline by throwing.
        givenMappedPath("/control/main", null);
        givenReturningClient(serverName);

        valve.invoke(request, response);

        verify(next).invoke(request, response);
    }

    @Test
    public void aProbeFromAReturningClientNeverReachesTheCookieRewritingPathAtAll() throws Exception {
        // The two fixes are independent and must stay that way: the exemption returns before the cookie
        // path, so a probe is unaffected by it, and the cookie path is safe on its own for everything
        // else. Asserting both here pins the ORDER - were the exemption ever moved below the rewrite, a
        // probe would start being given a session again and this test would fail.
        givenMappedPath(PROBE_LIVE, null);
        givenReturningClient("shop.example.com");

        valve.invoke(request, response);

        verify(request, never()).getSession(anyBoolean());
        verify(request, never()).getCookies();
        verify(request, never()).getAttribute("delegator");
        verifyNoInteractions(response);
        verify(next).invoke(request, response);
    }

    /**
     * Makes the request look like one from a client that already holds a session cookie, which is the only
     * condition under which the cookie-rewriting path runs. The delegator attribute is deliberately left
     * unstubbed so it answers {@code null}, exactly as it does at engine scope.
     *
     * @param serverName the host the client addressed, which the domain-widening step derives from
     */
    private void givenReturningClient(String serverName) {
        when(request.getCookies()).thenReturn(new Cookie[] {new Cookie("JSESSIONID", "0123456789ABCDEF") });
        when(request.getServerName()).thenReturn(serverName);
        // A real Coyote request, because its header collection is final and cannot be mocked. Empty, so
        // the header-rewriting loop simply finds nothing to replace.
        when(request.getCoyoteRequest()).thenReturn(new org.apache.coyote.Request());
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * The exemption and the endpoint cannot drift apart
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void theExemptionMatchesTheEndpointsOwnPredicateForEveryPathTried() throws Exception {
        // Asserted as an equivalence against HealthCheckServlet.isProbePath rather than against a list
        // of literals, because the two agreeing is the property that matters. Restating the paths here
        // would make this test pass while the valve and the endpoint disagreed - which is exactly the
        // drift that would silently restore a session per probe.
        List<String> paths = new ArrayList<>(NON_PROBE_PATHS);
        paths.add(PROBE_LIVE);
        paths.add(PROBE_READY);

        for (String path : paths) {
            Request probe = mock(Request.class);
            Response probeResponse = mock(Response.class);
            Context probeContext = mock(Context.class);
            when(probe.getContext()).thenReturn(probeContext);
            when(probeContext.getPath()).thenReturn(CONTEXT_PATH);
            when(probe.getServletPath()).thenReturn(path);
            // Recorded through the stub rather than verified afterwards, so the assertion below can name
            // the path that disagreed instead of reporting an unlabelled verification failure.
            AtomicBoolean sessionForced = new AtomicBoolean(false);
            when(probe.getSession(true)).thenAnswer(invocation -> {
                sessionForced.set(true);
                return null;
            });
            Valve probeNext = mock(Valve.class);
            CrossSubdomainSessionValve underTest = new CrossSubdomainSessionValve();
            underTest.setNext(probeNext);

            underTest.invoke(probe, probeResponse);

            // A session was forced into existence exactly when the endpoint would NOT have treated the
            // path as a probe, and was not when it would.
            assertEquals(!HealthCheckServlet.isProbePath(path), sessionForced.get(),
                    "the valve and the health endpoint disagree about " + path + ", so the exemption covers a"
                            + " different set of paths from the one the endpoint serves");
            // Either way the request continues down the pipeline.
            verify(probeNext).invoke(probe, probeResponse);
        }
    }

    private void givenMappedPath(String servletPath, String pathInfo) {
        when(request.getServletPath()).thenReturn(servletPath);
        when(request.getPathInfo()).thenReturn(pathInfo);
    }
}
