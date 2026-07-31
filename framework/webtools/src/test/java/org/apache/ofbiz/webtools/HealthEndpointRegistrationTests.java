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
package org.apache.ofbiz.webtools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.ofbiz.webapp.control.ControlFilter;
import org.apache.ofbiz.webapp.control.HealthCheckServlet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import jakarta.servlet.http.HttpSession;

/**
 * Route-registration contract of the load-balancer health probes in the webtools webapp.
 *
 * <p>The authoritative deployment descriptor {@code framework/webtools/webapp/webtools/WEB-INF/web.xml}
 * is parsed namespace-aware from the repository, and the values read out of it - not copied literals -
 * are what the behavioural half of this class feeds into a real {@link ControlFilter} and a real
 * {@link HealthCheckServlet}. That is what makes the tests fail if a url-pattern, the filter
 * ordering or the allow-list is ever changed.
 *
 * <p>The registration is the same class twice: as {@code HealthCheckFilter} on the two exact probe
 * paths, whose {@code filter-mapping} is declared FIRST, and as {@code HealthCheckServlet} on those
 * same two paths. Four properties carry the weight and are pinned here: the health
 * {@code filter-mapping} is the first in the descriptor, so a probe is answered ahead of every filter
 * that would create a session or parse the request body; the legacy filter chain is otherwise
 * untouched, keeping its classes, its {@code /*} pattern and its relative order; the
 * {@code ControlFilter} allow-list is exactly the list it was before this work, with no
 * {@code /health} entry added, because that list is matched with {@code startsWith} and a bare
 * {@code /health} entry would grant anonymous passage to every {@code /health*} spelling rather than
 * to the two probes; and the two exact url-patterns confine the anonymous surface, so any other
 * spelling under the prefix is answered by neither role.
 *
 * <p>No network access takes place: the descriptor's schema hint points at a remote XSD, so the
 * document is parsed structurally with secure processing enabled and external DTD loading switched
 * off, and every assertion is made against the parsed tree.
 */
public final class HealthEndpointRegistrationTests {

    private static final String WEB_XML = "framework/webtools/webapp/webtools/WEB-INF/web.xml";
    private static final String JAVAEE_NAMESPACE = "http://xmlns.jcp.org/xml/ns/javaee";
    private static final String HEALTH_SERVLET_NAME = "HealthCheckServlet";
    private static final String HEALTH_FILTER_NAME = "HealthCheckFilter";
    private static final String HEALTH_SERVLET_CLASS = "org.apache.ofbiz.webapp.control.HealthCheckServlet";
    private static final String CONTROL_SERVLET_NAME = "ControlServlet";
    private static final String CONTROL_FILTER_NAME = "ControlFilter";
    private static final String CONTEXT_PATH = "/webtools";
    private static final List<String> PROBE_PATHS = List.of("/health/live", "/health/ready");

    /** The eight paths that were allow-listed before the load-balancer work, in their original order. */
    private static final List<String> LEGACY_ALLOWED_PATHS = List.of(
            "/ping.txt", "/error", "/control", "/select", "/index.html", "/index.jsp",
            "/default.html", "/default.jsp");

    /** The filter names the descriptor must map, in chain order: the health probe first, then the legacy four. */
    private static final List<String> EXPECTED_FILTER_ORDER = List.of(
            HEALTH_FILTER_NAME, CONTROL_FILTER_NAME, "CacheFilter", "ContextFilter", "SameSiteFilter");

    /** The legacy filters, in the relative order they had before the probe work added one ahead of them. */
    private static final List<String> LEGACY_FILTER_ORDER = List.of(
            CONTROL_FILTER_NAME, "CacheFilter", "ContextFilter", "SameSiteFilter");

    private HttpServletRequest request;
    private HttpServletResponse response;
    private HttpSession session;
    private FilterChain chain;
    private String previousBypassProperty;

    @BeforeEach
    public void setUp() {
        session = mock(HttpSession.class);
        when(session.getAttribute(anyString())).thenReturn(null);
        request = mock(HttpServletRequest.class);
        when(request.getSession()).thenReturn(session);
        when(request.getContextPath()).thenReturn(CONTEXT_PATH);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);
        // ControlFilter.isControlFilterTests() gates a block that needs a live ServletContext; the
        // existing ControlFilterTests uses the very same production hook. Snapshot and restore it so
        // no global state leaks out of this class.
        previousBypassProperty = System.getProperty("ControlFilterTests");
        System.setProperty("ControlFilterTests", "bypassPreventsStreamExploitation");
    }

    @AfterEach
    public void tearDown() {
        if (previousBypassProperty == null) {
            System.clearProperty("ControlFilterTests");
        } else {
            System.setProperty("ControlFilterTests", previousBypassProperty);
        }
    }

    /*
     * Descriptor contract
     */

    @Test
    public void descriptorDeclaresTheJakartaEe40SchemaItIsWrittenAgainst() throws Exception {
        Element webApp = webApp();

        assertEquals(JAVAEE_NAMESPACE, webApp.getNamespaceURI(), "web-app namespace");
        assertEquals("4.0", webApp.getAttribute("version"), "web-app version");
    }

    @Test
    public void healthServletIsDeclaredExactlyOnceWithTheExactImplementationClass() throws Exception {
        List<Element> healthServlets = servletsByName(HEALTH_SERVLET_NAME);

        assertEquals(1, healthServlets.size(), "number of " + HEALTH_SERVLET_NAME + " declarations");
        // A typo here would only surface as a runtime ClassNotFoundException, never as a compile error.
        assertEquals(HEALTH_SERVLET_CLASS, childText(healthServlets.get(0), "servlet-class"), "servlet-class");
    }

    @Test
    public void healthServletStartsLazilyAndNeitherRoleTakesConfiguration() throws Exception {
        Element healthServlet = servletsByName(HEALTH_SERVLET_NAME).get(0);

        // No load-on-startup: the probe servlet must never participate in boot ordering, and it
        // resolves its delegator per request so readiness can flip without a restart.
        assertEquals(List.of(), childElements(healthServlet, "load-on-startup"), "load-on-startup elements");
        assertEquals(List.of(), childElements(healthServlet, "init-param"), "init-param elements");
        // The filter role takes no configuration either. It is the first thing in the chain, so a
        // required init-param would turn a descriptor mistake into a webapp that refuses to start.
        assertEquals(List.of(), childElements(filtersByName(HEALTH_FILTER_NAME).get(0), "init-param"),
                "filter init-param elements");
    }

    @Test
    public void healthServletMapsExactlyTheTwoProbePaths() throws Exception {
        assertEquals(PROBE_PATHS, urlPatternsFor(HEALTH_SERVLET_NAME), "health url-patterns");
    }

    @Test
    public void theProbesAreCarriedByASingleServletMappingWithNoWildcard() throws Exception {
        List<Element> healthMappings = servletMappingsFor(HEALTH_SERVLET_NAME);

        // One mapping element carrying both url-patterns, which Servlet 2.5 made legal and this 4.0
        // descriptor may therefore use. Two mapping elements would work identically but would let the
        // two probes drift apart.
        assertEquals(1, healthMappings.size(), "number of servlet-mapping elements for " + HEALTH_SERVLET_NAME);
        for (String pattern : PROBE_PATHS) {
            // A wildcard such as /health/* would map every spelling under the prefix onto the
            // unauthenticated handler; exact patterns keep the anonymous surface at exactly these two
            // resources, and the same is asserted of the filter mapping.
            assertTrue(!pattern.contains("*"), "probe url-pattern must be an exact path, found " + pattern);
        }
        for (String pattern : filterUrlPatternsFor(HEALTH_FILTER_NAME)) {
            assertTrue(!pattern.contains("*"), "probe filter url-pattern must be an exact path, found " + pattern);
        }
    }

    /*
     * The class is registered twice, and the filter registration is first in the chain
     */

    @Test
    public void theHealthClassIsRegisteredOnceAsAFilterAndOnceAsAServlet() throws Exception {
        // Exactly one declaration of each role, both backed by the same class. Two declarations of
        // either would let the two drift apart, and a second class answering a probe path would be a
        // second anonymous surface.
        assertEquals(1, filtersByName(HEALTH_FILTER_NAME).size(), "filter declarations");
        assertEquals(HEALTH_SERVLET_CLASS, filterClass(HEALTH_FILTER_NAME), "filter-class");
        assertEquals(1, servletsByName(HEALTH_SERVLET_NAME).size(), "servlet declarations");
        assertEquals(HEALTH_SERVLET_CLASS, childText(servletsByName(HEALTH_SERVLET_NAME).get(0), "servlet-class"),
                "servlet-class");

        // And no OTHER filter is backed by the probe class, which would put a second copy of it in the
        // chain under a name nothing here checks.
        for (Element filter : childElements(webApp(), "filter")) {
            String declaredName = childText(filter, "filter-name");
            if (!HEALTH_FILTER_NAME.equals(declaredName)) {
                assertNotEquals(HEALTH_SERVLET_CLASS, childText(filter, "filter-class"),
                        "filter " + declaredName + " must not be backed by " + HEALTH_SERVLET_CLASS);
            }
        }
    }

    @Test
    public void theHealthFilterIsMappedFirstOnExactlyTheTwoProbePaths() throws Exception {
        List<String> order = filterMappingOrder();

        // FIRST is the whole point. The servlet specification builds the chain in the order the
        // url-pattern mappings appear in this descriptor, so a health mapping declared anywhere else
        // would let ControlFilter and ContextFilter run ahead of it - which is exactly the session
        // creation and the unbounded body read the short-circuit exists to prevent.
        assertEquals(HEALTH_FILTER_NAME, order.get(0),
                "the health filter must be the first mapping in the chain, found " + order);
        // Exact paths, and only those two: a /health/* pattern would put every spelling under the
        // prefix through the probe handler.
        assertEquals(PROBE_PATHS, filterUrlPatternsFor(HEALTH_FILTER_NAME), "health filter url-patterns");
        assertEquals(1, mappingsForFilter(HEALTH_FILTER_NAME).size(),
                "one filter-mapping element must carry both probe paths, so they cannot drift apart");
        // And the two roles must cover the same set. A path the servlet mapping resolved but the filter
        // mapping did not would be served through the ordinary chain - a session per probe again - while
        // the reverse would answer a path the container never routed to this component.
        assertEquals(urlPatternsFor(HEALTH_SERVLET_NAME), filterUrlPatternsFor(HEALTH_FILTER_NAME),
                "the filter and the servlet must be mapped to exactly the same paths");
    }

    @Test
    public void theLegacyFilterChainIsUnchangedBehindTheProbeFilter() throws Exception {
        // The probe work added one filter, ahead of the others, and reordered none: the four
        // pre-existing filters keep their classes, their /* pattern and their relative order, so every
        // request that is not one of the two probes is processed exactly as before.
        assertEquals(EXPECTED_FILTER_ORDER, filterMappingOrder(), "filter-mapping order");
        assertEquals(LEGACY_FILTER_ORDER, filterMappingOrder().subList(1, filterMappingOrder().size()),
                "the legacy filters must keep their relative order behind the probe filter");
        assertEquals("org.apache.ofbiz.webapp.control.ControlFilter", filterClass(CONTROL_FILTER_NAME), "ControlFilter class");
        assertEquals("org.apache.ofbiz.base.util.CacheFilter", filterClass("CacheFilter"), "CacheFilter class");
        assertEquals("org.apache.ofbiz.webapp.control.ContextFilter", filterClass("ContextFilter"), "ContextFilter class");
        assertEquals("org.apache.ofbiz.webapp.control.SameSiteFilter", filterClass("SameSiteFilter"), "SameSiteFilter class");
        for (String legacyFilter : LEGACY_FILTER_ORDER) {
            assertEquals(List.of("/*"), filterUrlPatternsFor(legacyFilter), legacyFilter + " url-patterns");
        }
    }

    @Test
    public void noServletUrlPatternIsDeclaredTwice() throws Exception {
        List<String> allPatterns = new ArrayList<>();
        for (Element mapping : childElements(webApp(), "servlet-mapping")) {
            for (Element pattern : childElements(mapping, "url-pattern")) {
                allPatterns.add(text(pattern));
            }
        }
        Set<String> distinct = new LinkedHashSet<>(allPatterns);

        assertEquals(allPatterns.size(), distinct.size(), "duplicate servlet url-pattern in " + allPatterns);
    }

    @Test
    public void existingControlServletRegistrationIsUntouched() throws Exception {
        Element controlServlet = servletsByName(CONTROL_SERVLET_NAME).get(0);

        assertEquals("org.apache.ofbiz.webapp.control.ControlServlet", childText(controlServlet, "servlet-class"), "servlet-class");
        assertEquals("1", childText(controlServlet, "load-on-startup"), "load-on-startup");
        assertEquals(List.of("/control/*"), urlPatternsFor(CONTROL_SERVLET_NAME), "control url-patterns");
    }

    @Test
    public void theAllowListIsExactlyTheLegacyListAndReservesNoHealthPrefix() throws Exception {
        List<String> declared = declaredAllowedPaths();

        // Unchanged, entry for entry and in order: the probes are not allow-listed at all, so they cost
        // no pre-existing path its anonymous or gated status and add none of their own.
        assertEquals(LEGACY_ALLOWED_PATHS, declared, "ControlFilter allowedPaths");
        assertEquals("/control/main", initParameter(CONTROL_FILTER_NAME, "redirectPath"), "ControlFilter redirectPath");
        // ControlFilter matches its list with startsWith, so an allow-list entry is a PREFIX grant. A
        // /health entry would therefore hand anonymous passage to every /health* spelling - including
        // ones no url-pattern maps - rather than to the two probes, and none is needed: the probe filter
        // terminates the chain before ControlFilter is reached at all.
        for (String entry : declared) {
            assertFalse(entry.startsWith("/health"),
                    "the allow-list must reserve no /health prefix, found " + entry);
        }
    }

    /*
     * Behaviour of the real filter driven by the real descriptor values
     */

    @Test
    public void theLegacyAllowListedPathStillReachesTheChainWithoutAuthentication() throws Exception {
        ControlFilter filter = controlFilterFromDescriptor();
        when(request.getRequestURI()).thenReturn("/webtools/ping.txt");

        filter.doFilter(request, response, chain);

        // The long-standing anonymous endpoint is exercised to check that adding a filter ahead of
        // ControlFilter disturbed none of the paths that were already allow-listed.
        verify(chain).doFilter(request, response);
        verify(response, never()).sendRedirect(anyString());
        verify(response, never()).sendError(anyInt(), anyString());
        verify(response, never()).sendError(anyInt());
    }

    @ParameterizedTest(name = "{0} is answered by the first filter and never reaches ControlFilter")
    @ValueSource(strings = {"/health/live", "/health/ready"})
    public void bothProbePathsAreAnsweredByTheFirstFilterAndNeverReachControlFilter(String probePath) throws Exception {
        // The chain is assembled in the descriptor's own order from real instances of the descriptor's
        // own classes: the health filter, then ControlFilter configured with the descriptor's own
        // allowedPaths, then a mock standing in for everything behind them.
        Filter healthFilter = healthFilterFromDescriptor();
        ControlFilter controlFilter = controlFilterFromDescriptor();
        AtomicBoolean controlFilterEntered = new AtomicBoolean(false);
        FilterChain behindTheProbeFilter = (downstreamRequest, downstreamResponse) -> {
            controlFilterEntered.set(true);
            controlFilter.doFilter(downstreamRequest, downstreamResponse, chain);
        };
        HttpServletRequest probeRequest = mock(HttpServletRequest.class);
        when(probeRequest.getServletPath()).thenReturn(probePath);
        when(probeRequest.getMethod()).thenReturn("GET");
        when(probeRequest.getRequestURI()).thenReturn(CONTEXT_PATH + probePath);
        when(probeRequest.getContextPath()).thenReturn(CONTEXT_PATH);
        HttpServletResponse probeResponse = mock(HttpServletResponse.class);
        StringWriter probeBody = new StringWriter();
        when(probeResponse.getWriter()).thenReturn(new PrintWriter(probeBody));

        healthFilter.doFilter(probeRequest, probeResponse, behindTheProbeFilter);

        // The probe is answered where it arrives. Nothing behind the first mapping runs, which is what
        // keeps a probe out of ControlFilter and ContextFilter - and out of the getSession() call and
        // the request-body parser they contain.
        assertFalse(controlFilterEntered.get(),
                "a probe must be answered before ControlFilter is reached, or every probe mints a session");
        verify(chain, never()).doFilter(any(), any());
        verify(probeResponse, never()).sendRedirect(anyString());
        verify(probeResponse, never()).sendError(anyInt(), anyString());
        verify(probeResponse, never()).sendError(anyInt());
        verify(probeRequest, never()).getSession();
        verify(probeRequest, never()).getSession(anyBoolean());
        verify(probeRequest, never()).getInputStream();
        verify(probeRequest, never()).getReader();
        verify(probeResponse, never()).addCookie(any());
        // Answered as a probe, with the endpoint's own document: liveness 200, readiness 503 because no
        // delegator is reachable from a unit test. What matters here is that a verdict was written at
        // all, and that it is not the 404 an unmapped path would get.
        verify(probeResponse).setStatus(anyInt());
        verify(probeResponse, never()).setStatus(HttpServletResponse.SC_NOT_FOUND);
        assertTrue(probeBody.toString().startsWith("{\"status\":"),
                "the probe filter must write the endpoint's own document, found " + probeBody);
    }

    @ParameterizedTest(name = "{0} would be redirected if it ever reached ControlFilter")
    @ValueSource(strings = {"/webtools/health/live", "/webtools/health/ready"})
    public void aProbePathReachingControlFilterWouldBeRedirectedWhichIsWhyTheProbeFilterIsFirst(String requestUri)
            throws Exception {
        ControlFilter filter = controlFilterFromDescriptor();
        when(request.getRequestURI()).thenReturn(requestUri);

        filter.doFilter(request, response, chain);

        // Stated as the consequence it is, rather than left implicit: because the probes are not
        // allow-listed, a probe that did reach ControlFilter would be redirected to /control/main, and a
        // target group would read the 302 as an unhealthy target. The ordering asserted above - the
        // health filter-mapping first - is the only thing standing between a probe and this outcome, so
        // moving that mapping breaks readiness rather than merely making it slower.
        verify(chain, never()).doFilter(request, response);
        verify(response).sendRedirect(CONTEXT_PATH + "/control/main");
    }

    @ParameterizedTest(name = "{0} is redirected by ControlFilter")
    @ValueSource(strings = {"/webtools/entity/find", "/webtools/live", "/webtools/ready", "/webtools/healt/live",
        "/webtools/HEALTH/live", "/webtools/heal/th/live"})
    public void everySpellingOutsideTheHealthPrefixIsStillGatedByControlFilter(String requestUri) throws Exception {
        ControlFilter filter = controlFilterFromDescriptor();
        when(request.getRequestURI()).thenReturn(requestUri);

        filter.doFilter(request, response, chain);

        // The allow-list is matched with startsWith on the exact bytes, so it is case-sensitive and a
        // near miss stays gated: /HEALTH/live and /healt/live get the ordinary redirect.
        verify(chain, never()).doFilter(request, response);
        verify(response).sendRedirect(CONTEXT_PATH + "/control/main");
    }

    @ParameterizedTest(name = "{0} is passed on by the probe filter and then gated by ControlFilter")
    @ValueSource(strings = {"/webtools/health", "/webtools/healthz/live", "/webtools/health/live/",
        "/webtools/health/liveness", "/webtools/health/ready2"})
    public void everyNearMissUnderTheHealthPrefixIsPassedOnAndThenGatedByControlFilter(String requestUri)
            throws Exception {
        String pathWithinWebapp = requestUri.substring(CONTEXT_PATH.length());
        // First the probe filter, which compares the path exactly: a near miss is passed on rather than
        // answered, so nothing under the prefix becomes a probe by looking like one.
        Filter healthFilter = healthFilterFromDescriptor();
        HttpServletRequest nearMiss = mock(HttpServletRequest.class);
        when(nearMiss.getServletPath()).thenReturn(pathWithinWebapp);
        FilterChain behindTheProbeFilter = mock(FilterChain.class);

        healthFilter.doFilter(nearMiss, response, behindTheProbeFilter);

        verify(behindTheProbeFilter).doFilter(nearMiss, response);
        for (String probePath : urlPatternsFor(HEALTH_SERVLET_NAME)) {
            assertNotEquals(probePath, pathWithinWebapp, "must not be one of the mapped probe paths");
        }
        assertFalse(HealthCheckServlet.isProbePath(pathWithinWebapp), "must not be recognised as a probe path");

        // Then ControlFilter, which is what it reaches next - and because no /health prefix is
        // allow-listed, it is gated exactly like any other unauthenticated path. This is the direction
        // the earlier /health allow-list entry got wrong: with it, every spelling here cleared the
        // authentication gate for nothing.
        ControlFilter controlFilter = controlFilterFromDescriptor();
        when(request.getRequestURI()).thenReturn(requestUri);

        controlFilter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response).sendRedirect(CONTEXT_PATH + "/control/main");
    }

    @Test
    public void aTraversalDressedUpAsAProbePathIsRejectedOutrightRatherThanRedirected() throws Exception {
        ControlFilter filter = controlFilterFromDescriptor();
        when(request.getRequestURI()).thenReturn("/webtools/health/live/../secret");

        // ControlFilter's own URI-normalisation guard refuses this before the allow-list is consulted,
        // which is a stronger outcome than a redirect. It is asserted here so that the guard staying in
        // force is part of the probe contract: at runtime the container has already normalised the URI,
        // so a traversal can never reach the exact path comparison in the probe handler either.
        assertThrows(RuntimeException.class, () -> filter.doFilter(request, response, chain));
        verify(chain, never()).doFilter(request, response);
    }

    /*
     * The probe servlet, driven with the descriptor's own url-patterns
     */

    @Test
    public void everyMappedProbePathIsAnsweredWithoutASessionOfItsOwn() throws Exception {
        List<String> mappedPaths = urlPatternsFor(HEALTH_SERVLET_NAME);

        assertEquals(PROBE_PATHS, mappedPaths, "the descriptor must map exactly the two probe paths");
        for (String probePath : mappedPaths) {
            HttpServletRequest probeRequest = mock(HttpServletRequest.class);
            when(probeRequest.getServletPath()).thenReturn(probePath);
            when(probeRequest.getMethod()).thenReturn("GET");
            HttpServletResponse probeResponse = mock(HttpServletResponse.class);
            when(probeResponse.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

            new HealthCheckServlet().service(probeRequest, probeResponse);

            // The real class in its servlet role, driven with the descriptor's own patterns: each mapped
            // path is answered by a probe handler - liveness 200, readiness 503 with no delegator
            // available in a unit test - and the handler itself creates no session, reads no body and
            // sets no cookie of its own. In a deployment the filter role answers first, so this is what the
            // servlet mapping does on its own, for a webapp that declares only the servlet.
            verify(probeRequest, never()).getSession();
            verify(probeRequest, never()).getSession(anyBoolean());
            verify(probeRequest, never()).getInputStream();
            verify(probeRequest, never()).getReader();
            verify(probeResponse, never()).addCookie(any());
            verify(probeResponse, never()).sendRedirect(anyString());
            verify(probeResponse, never()).sendError(anyInt());
            verify(probeResponse, never()).setStatus(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    @Test
    public void everyMappedProbePathAnswersHeadWithTheStatusAndHeadersButNoBody() throws Exception {
        for (String probePath : urlPatternsFor(HEALTH_SERVLET_NAME)) {
            HttpServletRequest probeRequest = mock(HttpServletRequest.class);
            when(probeRequest.getServletPath()).thenReturn(probePath);
            when(probeRequest.getMethod()).thenReturn("HEAD");
            HttpServletResponse clientResponse = mock(HttpServletResponse.class);
            StringWriter clientBody = new StringWriter();
            when(clientResponse.getWriter()).thenReturn(new PrintWriter(clientBody));
            // Tomcat installs a void output filter for HEAD, so the entity body is discarded while the
            // status line and the headers are sent. This wrapper reproduces that container boundary:
            // the writer the servlet obtains is not the client writer.
            BodySuppressingResponse headResponse = new BodySuppressingResponse(clientResponse);

            new HealthCheckServlet().service(probeRequest, headResponse);

            // A load balancer configured to probe with HEAD has to get the same verdict a GET yields,
            // which means the status and the headers must reach it while the body must not.
            verify(clientResponse).setStatus(anyInt());
            verify(clientResponse).setContentType("application/json");
            verify(clientResponse).setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            verify(clientResponse, never()).getWriter();
            assertEquals("", clientBody.toString(), "no body may reach the client on HEAD " + probePath);
            assertTrue(headResponse.suppressedBody().startsWith("{\"status\":"),
                    "HEAD must still produce the document a GET would, found " + headResponse.suppressedBody());
        }
    }

    @Test
    public void aPathNoPatternMapsFailsVisiblyIfItEverReachesTheServlet() throws Exception {
        when(request.getServletPath()).thenReturn("/health");
        when(request.getPathInfo()).thenReturn("/liveness");
        when(request.getMethod()).thenReturn("GET");
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        new HealthCheckServlet().service(request, response);

        // Neither role is mapped to such a path today, so this is the fail-closed direction should either
        // mapping ever be widened to a /health/* prefix: 404 rather than a misleading 200 that would keep
        // a broken instance in a target group.
        verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
        verify(request, never()).getSession();
    }

    /*
     * Helpers - descriptor access
     */

    /**
     * Builds a real {@link ControlFilter} configured with the values read out of the production
     * descriptor, so the behavioural assertions exercise the shipped configuration rather than a copy.
     */
    private ControlFilter controlFilterFromDescriptor() throws Exception {
        FilterConfig config = mock(FilterConfig.class);
        when(config.getInitParameter(anyString())).thenReturn(null);
        when(config.getInitParameter("allowedPaths")).thenReturn(initParameter(CONTROL_FILTER_NAME, "allowedPaths"));
        when(config.getInitParameter("redirectPath")).thenReturn(initParameter(CONTROL_FILTER_NAME, "redirectPath"));
        ControlFilter filter = new ControlFilter();
        filter.init(config);
        return filter;
    }

    /**
     * Instantiates the probe filter by the {@code filter-class} the descriptor itself carries and takes
     * it through the {@link Filter} lifecycle a container uses, so the behavioural assertions exercise
     * the shipped declaration rather than a hard-coded class reference.
     */
    private Filter healthFilterFromDescriptor() throws Exception {
        String declaredClass = filterClass(HEALTH_FILTER_NAME);
        Object instance = Class.forName(declaredClass).getDeclaredConstructor().newInstance();
        assertTrue(instance instanceof Filter, declaredClass + " is declared as a filter but does not implement Filter");
        Filter filter = (Filter) instance;
        FilterConfig config = mock(FilterConfig.class);
        when(config.getInitParameter(anyString())).thenReturn(null);
        // The descriptor declares no init-param for this filter, which the lazy-initialisation test
        // asserts for the servlet role; init is still called because that is what a container does.
        filter.init(config);
        return filter;
    }

    private List<String> declaredAllowedPaths() throws Exception {
        return Arrays.asList(initParameter(CONTROL_FILTER_NAME, "allowedPaths").split(":"));
    }

    private String initParameter(String filterName, String parameterName) throws Exception {
        for (Element filter : childElements(webApp(), "filter")) {
            if (filterName.equals(childText(filter, "filter-name"))) {
                for (Element initParam : childElements(filter, "init-param")) {
                    if (parameterName.equals(childText(initParam, "param-name"))) {
                        return childText(initParam, "param-value");
                    }
                }
            }
        }
        throw new AssertionError("no init-param " + parameterName + " declared for filter " + filterName);
    }

    private List<Element> filtersByName(String filterName) throws Exception {
        List<Element> matches = new ArrayList<>();
        for (Element filter : childElements(webApp(), "filter")) {
            if (filterName.equals(childText(filter, "filter-name"))) {
                matches.add(filter);
            }
        }
        assertTrue(!matches.isEmpty(), "no filter named " + filterName + " is declared");
        return matches;
    }

    private String filterClass(String filterName) throws Exception {
        return childText(filtersByName(filterName).get(0), "filter-class");
    }

    private List<String> filterUrlPatternsFor(String filterName) throws Exception {
        List<String> patterns = new ArrayList<>();
        for (Element mapping : childElements(webApp(), "filter-mapping")) {
            if (filterName.equals(childText(mapping, "filter-name"))) {
                for (Element pattern : childElements(mapping, "url-pattern")) {
                    patterns.add(text(pattern));
                }
            }
        }
        assertTrue(!patterns.isEmpty(), "no filter-mapping declared for " + filterName);
        return patterns;
    }

    /** The {@code filter-mapping} elements that reference {@code filterName}, in descriptor order. */
    private List<Element> mappingsForFilter(String filterName) throws Exception {
        List<Element> matches = new ArrayList<>();
        for (Element mapping : childElements(webApp(), "filter-mapping")) {
            if (filterName.equals(childText(mapping, "filter-name"))) {
                matches.add(mapping);
            }
        }
        return matches;
    }

    /** The filter names in the order their {@code filter-mapping} elements appear, which is chain order. */
    private List<String> filterMappingOrder() throws Exception {
        List<String> names = new ArrayList<>();
        for (Element mapping : childElements(webApp(), "filter-mapping")) {
            names.add(childText(mapping, "filter-name"));
        }
        return names;
    }

    private List<Element> servletsByName(String servletName) throws Exception {
        List<Element> matches = new ArrayList<>();
        for (Element servlet : childElements(webApp(), "servlet")) {
            if (servletName.equals(childText(servlet, "servlet-name"))) {
                matches.add(servlet);
            }
        }
        assertTrue(!matches.isEmpty(), "no servlet named " + servletName + " is declared");
        return matches;
    }

    private List<String> urlPatternsFor(String servletName) throws Exception {
        List<String> patterns = new ArrayList<>();
        for (Element mapping : servletMappingsFor(servletName)) {
            for (Element pattern : childElements(mapping, "url-pattern")) {
                patterns.add(text(pattern));
            }
        }
        return patterns;
    }

    /** The {@code servlet-mapping} elements that reference {@code servletName}, in descriptor order. */
    private List<Element> servletMappingsFor(String servletName) throws Exception {
        List<Element> matches = new ArrayList<>();
        for (Element mapping : childElements(webApp(), "servlet-mapping")) {
            if (servletName.equals(childText(mapping, "servlet-name"))) {
                matches.add(mapping);
            }
        }
        return matches;
    }

    private Element webApp() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        Path descriptor = repositoryRoot().resolve(WEB_XML);
        assertTrue(Files.isRegularFile(descriptor), "missing authoritative descriptor " + descriptor);
        Document document = factory.newDocumentBuilder().parse(descriptor.toFile());
        Element root = document.getDocumentElement();
        assertEquals("web-app", root.getLocalName(), "descriptor root element");
        return root;
    }

    private static List<Element> childElements(Element parent, String localName) {
        List<Element> children = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && localName.equals(node.getLocalName())) {
                children.add((Element) node);
            }
        }
        return children;
    }

    private static String childText(Element parent, String localName) {
        List<Element> children = childElements(parent, localName);
        assertEquals(1, children.size(), "expected exactly one <" + localName + "> in <" + parent.getLocalName() + ">");
        return text(children.get(0));
    }

    private static String text(Element element) {
        String content = element.getTextContent();
        return content == null ? null : content.trim();
    }

    private static Path repositoryRoot() {
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("dependencies.gradle"))) {
            candidate = candidate.getParent();
        }
        assertNotNull(candidate, "could not locate the repository root from " + Paths.get("").toAbsolutePath());
        return candidate;
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
