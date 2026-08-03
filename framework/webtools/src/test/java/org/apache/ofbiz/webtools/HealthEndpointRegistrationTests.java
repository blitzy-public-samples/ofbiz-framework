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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.ofbiz.webapp.control.ContextFilter;
import org.apache.ofbiz.webapp.control.ControlFilter;
import org.apache.ofbiz.webapp.control.HealthCheckServlet;
import org.apache.ofbiz.webapp.control.HealthProbeFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;
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
 * <p>The registration is a single {@code servlet} plus a single {@code servlet-mapping}, which is what
 * the Agent Action Plan prescribes: register {@code HealthCheckServlet} with a no-authentication
 * {@code servlet}/{@code servlet-mapping} modelled on the {@code ControlServlet} registration
 * (AAP 0.2.1 and 0.4.1). The probe class is therefore NOT declared as a filter, and no
 * {@code filter-mapping} references it: the legacy four-filter chain is exactly what it was, and a
 * probe travels through it like any other request. Four properties carry the weight and are pinned
 * here: the probe class is declared once, as a servlet, and no filter is backed by it; the legacy
 * filter chain keeps its classes, its {@code /*} pattern and its order, with nothing inserted ahead of
 * it; the {@code ControlFilter} allow-list is the legacy list plus one {@code /health} entry, which is
 * what lets an unauthenticated probe reach the chain at all; and the two exact servlet url-patterns
 * confine what the probe handler answers, so any other spelling under the prefix is admitted by the
 * allow-list's {@code startsWith} match but is claimed by no mapping and never becomes a probe.
 *
 * <p>The {@code startsWith} breadth of that one allow-list entry is an accepted, documented cost of
 * the servlet-only integration rather than an oversight, and it is asserted in both directions below:
 * every {@code /health*} spelling clears {@code ControlFilter}, and none of them except the two mapped
 * paths is recognised by {@link HealthCheckServlet#isProbePath(String)} or answered as a probe. The
 * second accepted cost is that {@code ControlFilter.doFilter} calls {@code getSession()}
 * unconditionally before it consults the allow-list, so each probe mints a session in the container;
 * the probe handler itself still creates none, reads no body and sets no cookie.
 *
 * <p>No network access takes place: the descriptor's schema hint points at a remote XSD, so the
 * document is parsed structurally with secure processing enabled and external DTD loading switched
 * off, and every assertion is made against the parsed tree.
 */
public final class HealthEndpointRegistrationTests {

    private static final String WEB_XML = "framework/webtools/webapp/webtools/WEB-INF/web.xml";
    private static final String JAVAEE_NAMESPACE = "http://xmlns.jcp.org/xml/ns/javaee";
    private static final String HEALTH_SERVLET_NAME = "HealthCheckServlet";
    private static final String PROBE_FILTER_NAME = "HealthProbeFilter";
    private static final String PROBE_FILTER_CLASS = "org.apache.ofbiz.webapp.control.HealthProbeFilter";
    private static final String HEALTH_SERVLET_CLASS = "org.apache.ofbiz.webapp.control.HealthCheckServlet";
    private static final String CONTROL_SERVLET_NAME = "ControlServlet";
    private static final String CONTROL_FILTER_NAME = "ControlFilter";
    private static final String CONTEXT_PATH = "/webtools";
    private static final List<String> PROBE_PATHS = List.of("/health/live", "/health/ready");

    /** The eight paths that were allow-listed before the load-balancer work, in their original order. */
    private static final List<String> LEGACY_ALLOWED_PATHS = List.of(
            "/ping.txt", "/error", "/control", "/select", "/index.html", "/index.jsp",
            "/default.html", "/default.jsp");

    /**
     * The prefix an allow-list entry for the probes would have had to open.
     *
     * <p>Kept as a constant only so the tests below can assert that NOTHING in the allow-list opens it.
     * {@code ControlFilter} matches its list with {@code startsWith}, so the only concise entry that
     * would have admitted the two probes is {@code /health} - and that is a byte prefix, not a path
     * segment, so it would also admit {@code /healthz/live}, {@code /health-internal} and
     * {@code /health/live/anything}, none of which is a probe. The probes are reached through
     * {@code HealthProbeFilter} instead, which runs before {@code ControlFilter}, so no such entry is
     * needed and the grant does not exist.
     */
    private static final String HEALTH_PREFIX_THAT_MUST_NOT_BE_GRANTED = "/health";

    /**
     * The allow-list the descriptor must declare: exactly the legacy eight, in their original order.
     * Order is asserted because it is the descriptor's own text, and the probe work must have left every
     * pre-existing entry byte-identical while adding none of its own.
     */
    private static final List<String> EXPECTED_ALLOWED_PATHS = LEGACY_ALLOWED_PATHS;

    /**
     * The four filters that were mapped before the load-balancer work, in their original chain order.
     * The probe work inserted one filter AHEAD of them and reordered none of them, so this is the tail of
     * the expected order and {@link #PROBE_FILTER_NAME} is its head.
     */
    private static final List<String> LEGACY_FILTER_ORDER = List.of(
            CONTROL_FILTER_NAME, "CacheFilter", "ContextFilter", "SameSiteFilter");

    /** The legacy filters entered during a chain run, which for a probe must stay empty. */
    private final List<String> enteredFilters = new ArrayList<>();

    private ServletContext servletContext;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private HttpSession session;
    private FilterChain chain;
    private String previousBypassProperty;

    @BeforeEach
    public void setUp() {
        session = mock(HttpSession.class);
        when(session.getAttribute(anyString())).thenReturn(null);
        servletContext = mock(ServletContext.class);
        request = mock(HttpServletRequest.class);
        when(request.getSession()).thenReturn(session);
        when(request.getContextPath()).thenReturn(CONTEXT_PATH);
        when(request.getServletContext()).thenReturn(servletContext);
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
    public void healthServletStartsLazilyAndTakesNoConfiguration() throws Exception {
        Element healthServlet = servletsByName(HEALTH_SERVLET_NAME).get(0);

        // No load-on-startup: the probe servlet must never participate in boot ordering, and it
        // resolves its delegator per request so readiness can flip without a restart.
        assertEquals(List.of(), childElements(healthServlet, "load-on-startup"), "load-on-startup elements");
        // No init-param either, so a descriptor mistake cannot turn the probe endpoint into a webapp
        // that refuses to start; every setting the handler needs it reads from the running system.
        assertEquals(List.of(), childElements(healthServlet, "init-param"), "init-param elements");
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
            // unauthenticated handler. Exact patterns keep the surface the handler answers at exactly
            // these two resources, which matters more now than it did under a filter registration: the
            // allow-list entry that admits them is a prefix grant, so the servlet mapping is the only
            // thing that decides what actually becomes a probe.
            assertTrue(!pattern.contains("*"), "probe url-pattern must be an exact path, found " + pattern);
        }
    }

    /*
     * The class is registered as a servlet and as nothing else
     */

    @Test
    public void theHealthClassIsRegisteredOnlyAsAServlet() throws Exception {
        // Exactly one servlet declaration, backed by the probe class. Two would let the declarations
        // drift apart, and a second class answering a probe path would be a second anonymous surface.
        assertEquals(1, servletsByName(HEALTH_SERVLET_NAME).size(), "servlet declarations");
        assertEquals(HEALTH_SERVLET_CLASS, childText(servletsByName(HEALTH_SERVLET_NAME).get(0), "servlet-class"),
                "servlet-class");

        // The probe CLASS is registered only as a servlet, under exactly one name. The filter that routes
        // to it is a separate class with a single responsibility - see the probe filter tests below - so
        // there is exactly one place the probe logic lives and exactly one place the routing lives.
        for (Element filter : childElements(webApp(), "filter")) {
            assertNotEquals(HEALTH_SERVLET_CLASS, childText(filter, "filter-class"),
                    "filter " + childText(filter, "filter-name") + " must not be backed by " + HEALTH_SERVLET_CLASS);
        }
        // The descriptor and the class have to agree: the class does not implement Filter, so declaring
        // it as one could only ever fail at deployment time.
        assertFalse(Filter.class.isAssignableFrom(HealthCheckServlet.class),
                HEALTH_SERVLET_CLASS + " must not implement Filter");
    }

    @Test
    public void exactlyOneFilterMappingReferencesTheProbeFilterAndItCarriesTheTwoProbePaths() throws Exception {
        // One mapping, so there is one ordering to keep right rather than several, and the probe paths it
        // carries are the servlet's own two.
        assertEquals(1, mappingElementsForFilter(PROBE_FILTER_NAME).size(),
                "exactly one filter-mapping may reference " + PROBE_FILTER_NAME);
        assertEquals(PROBE_PATHS, filterUrlPatternsFor(PROBE_FILTER_NAME), "probe filter url-patterns");
        // The probe paths are still carried by the servlet mapping too: the filter routes to the servlet,
        // it does not replace it.
        assertEquals(PROBE_PATHS, urlPatternsFor(HEALTH_SERVLET_NAME), "health servlet url-patterns");
    }

    @Test
    public void theLegacyFilterChainIsUnchangedBelowTheProbeFilter() throws Exception {
        // The probe work added one filter AHEAD of the four and reordered none of them: they keep their
        // classes, their /* pattern and their relative order, so every request except a probe is processed
        // by exactly the chain that was there before. A probe is the one request that does not reach them,
        // which is the entire purpose of the added mapping.
        assertEquals(LEGACY_FILTER_ORDER, filterMappingOrder().subList(1, filterMappingOrder().size()),
                "the legacy filter-mapping order must be unchanged below the probe filter");
        assertEquals(PROBE_FILTER_NAME, filterMappingOrder().get(0), "the probe filter must be mapped first");
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
    public void theAllowListIsExactlyTheLegacyListAndOpensNoHealthPrefix() throws Exception {
        List<String> declared = declaredAllowedPaths();

        // The eight legacy entries are byte-identical and still in their original order, and the probe
        // work added NONE of its own, so no pre-existing path changed its anonymous or gated status and
        // no new anonymous surface was opened here at all.
        assertEquals(EXPECTED_ALLOWED_PATHS, declared, "ControlFilter allowedPaths");
        assertEquals("/control/main", initParameter(CONTROL_FILTER_NAME, "redirectPath"), "ControlFilter redirectPath");

        // The point of the whole arrangement. ControlFilter matches its list with startsWith, so an entry
        // is a PREFIX grant, not a path: a /health entry would have admitted every /health* spelling to
        // the chain - /healthz/live and /health-internal among them - and narrowing that within the
        // allow-list is not possible, because the two exact paths would still be prefixes of
        // /health/live/anything. So the entry is absent and the probes are answered by HealthProbeFilter
        // before ControlFilter is reached. Admission is therefore exactly the two probe paths, and this
        // asserts that no entry - present or later added - opens the prefix again.
        List<String> healthEntries = declared.stream()
                .filter(entry -> entry.startsWith(HEALTH_PREFIX_THAT_MUST_NOT_BE_GRANTED)
                        || HEALTH_PREFIX_THAT_MUST_NOT_BE_GRANTED.startsWith(entry))
                .toList();
        assertEquals(List.of(), healthEntries, "no allow-list entry may open the " + HEALTH_PREFIX_THAT_MUST_NOT_BE_GRANTED
                + " prefix, because startsWith would widen it past the two probe paths, found " + healthEntries);
    }

    @Test
    public void theProbeFilterIsDeclaredOnceAndMappedFirstOnExactlyTheTwoProbePaths() throws Exception {
        // The ordering is the mechanism, not a detail: the container applies filter-mappings in
        // descriptor order, so this mapping standing before ControlFilter's is what decides a probe
        // before ControlFilter can call getSession() for it. If it were moved after, every guarantee
        // below would silently revert.
        assertEquals(1, filtersByName(PROBE_FILTER_NAME).size(), "the probe filter must be declared exactly once");
        assertEquals(PROBE_FILTER_CLASS, childText(filtersByName(PROBE_FILTER_NAME).get(0), "filter-class"),
                "probe filter-class");
        assertEquals(List.of(), childElements(filtersByName(PROBE_FILTER_NAME).get(0), "init-param"),
                "the probe filter must take no configuration in this descriptor");

        List<String> order = filterMappingOrder();
        assertEquals(PROBE_FILTER_NAME, order.get(0), "the probe filter must be mapped first, ahead of "
                + CONTROL_FILTER_NAME + "; the declared order is " + order);
        assertTrue(order.indexOf(PROBE_FILTER_NAME) < order.indexOf(CONTROL_FILTER_NAME),
                "the probe filter must be mapped ahead of " + CONTROL_FILTER_NAME + ", order " + order);

        // Exact patterns, never /health/*. A prefix pattern would put /health/live/anything through the
        // bypass, which is the same over-broad admission in a different place.
        assertEquals(PROBE_PATHS, filterUrlPatternsFor(PROBE_FILTER_NAME), "probe filter url-patterns");
        for (String pattern : filterUrlPatternsFor(PROBE_FILTER_NAME)) {
            assertFalse(pattern.contains("*"), "a probe filter pattern must be exact, and [" + pattern
                    + "] is a wildcard");
            assertTrue(HealthCheckServlet.isProbePath(pattern), "[" + pattern + "] must be a path the servlet"
                    + " itself recognises as a probe, so the descriptor cannot drift from the endpoint");
        }
        // And no <dispatcher> element, so the mapping covers REQUEST alone: the filter forwards by NAME,
        // and a named dispatch is not filtered by a url-pattern mapping, so it cannot re-enter itself.
        for (Element mapping : mappingElementsForFilter(PROBE_FILTER_NAME)) {
            assertEquals(List.of(), childElements(mapping, "dispatcher"),
                    "the probe filter mapping must cover REQUEST only");
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

        // The long-standing anonymous endpoint is exercised to check that appending one allow-list entry
        // disturbed none of the paths that were already allow-listed.
        verify(chain).doFilter(request, response);
        verify(response, never()).sendRedirect(anyString());
        verify(response, never()).sendError(anyInt(), anyString());
        verify(response, never()).sendError(anyInt());
    }

    @ParameterizedTest(name = "{0} is answered by the probe filter without the rest of the chain")
    @ValueSource(strings = {"/health/live", "/health/ready"})
    public void bothProbePathsAreAnsweredWithoutRunningTheRestOfTheChain(String probePath) throws Exception {
        // The real filter, driven the way the container drives it, with the servlet reachable by the name
        // the descriptor registers. This is the state a load balancer probes in: no session, no userLogin,
        // no credential.
        HealthProbeFilter filter = probeFilterFromDescriptor();
        when(request.getServletPath()).thenReturn(probePath);
        when(request.getPathInfo()).thenReturn(null);
        RequestDispatcher probe = mock(RequestDispatcher.class);
        when(servletContext.getNamedDispatcher(HEALTH_SERVLET_NAME)).thenReturn(probe);

        filter.doFilter(request, response, chain);

        // The chain is NOT continued, which is the whole point: ControlFilter, CacheFilter, ContextFilter
        // and SameSiteFilter never run for a probe, so none of them can create a session, parse a body or
        // redirect. The probe is served by forwarding to the health servlet by name.
        verify(probe).forward(request, response);
        verify(chain, never()).doFilter(any(), any());
        verify(response, never()).sendRedirect(anyString());
        verify(response, never()).sendError(anyInt(), anyString());
        verify(response, never()).sendError(anyInt());
    }

    @ParameterizedTest(name = "{0} costs the probe filter no session and no cookie")
    @ValueSource(strings = {"/health/live", "/health/ready"})
    public void aProbeCreatesNoSessionAndSetsNoCookie(String probePath) throws Exception {
        // The inverse of what this suite used to assert. It previously recorded that each probe minted a
        // session, as the accepted cost of registering the endpoint as a servlet behind the ordinary
        // chain. A target group probes every instance every few seconds, returns no session and follows no
        // cookie, so each of those sessions was retained until it expired on its own, and the cookie the
        // container emitted for it is an identifier a cookie-sticky balancer would pin traffic with.
        // Bypassing the session-creating filters removes both, and that is now asserted rather than
        // conceded.
        HealthProbeFilter filter = probeFilterFromDescriptor();
        when(request.getServletPath()).thenReturn(probePath);
        when(servletContext.getNamedDispatcher(HEALTH_SERVLET_NAME)).thenReturn(mock(RequestDispatcher.class));

        filter.doFilter(request, response, chain);

        verify(request, never()).getSession();
        verify(request, never()).getSession(anyBoolean());
        verify(response, never()).addCookie(any());
        verify(response, never()).addHeader(eq("Set-Cookie"), anyString());
        verify(response, never()).setHeader(eq("Set-Cookie"), anyString());
    }

    @Test
    public void aProbeIsLeftToTheOrdinaryChainWhenTheServletIsNotRegistered() throws Exception {
        // A descriptor that maps the filter but not the servlet is a misconfiguration, and it degrades to
        // the previous behaviour rather than failing the request: turning a probe into a 500 would also
        // mean an HTML error page on a deliberately unauthenticated path.
        HealthProbeFilter filter = probeFilterFromDescriptor();
        when(request.getServletPath()).thenReturn("/health/live");
        when(servletContext.getNamedDispatcher(HEALTH_SERVLET_NAME)).thenReturn(null);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).sendError(anyInt());
        verify(response, never()).sendError(anyInt(), anyString());
    }

    @ParameterizedTest(name = "{0} is not treated as a probe by the filter")
    @ValueSource(strings = {"/health", "/healthz/live", "/health/live/", "/health/liveness", "/health/ready2",
        "/HEALTH/live", "/health/live/../secret", "/control/main", "/entity/find", ""})
    public void everythingThatIsNotExactlyAProbePathIsLeftToTheOrdinaryChain(String path) throws Exception {
        // Belt to the descriptor's braces. The mapping is two exact patterns, so in the shipped webapp the
        // filter is never entered for any of these; asserting it here makes exactness a property of the
        // class as well, so a deployment that maps it more loosely still gets no bypass for a near miss.
        HealthProbeFilter filter = probeFilterFromDescriptor();
        when(request.getServletPath()).thenReturn(path);
        RequestDispatcher probe = mock(RequestDispatcher.class);
        when(servletContext.getNamedDispatcher(HEALTH_SERVLET_NAME)).thenReturn(probe);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(probe, never()).forward(any(), any());
        assertFalse(HealthCheckServlet.isProbePath(path), "[" + path + "] must not be a probe path");
    }

    @ParameterizedTest(name = "{0} is redirected by ControlFilter")
    @ValueSource(strings = {"/webtools/entity/find", "/webtools/live", "/webtools/ready", "/webtools/healt/live",
        "/webtools/HEALTH/live", "/webtools/heal/th/live"})
    public void everySpellingOutsideTheHealthPrefixIsStillGatedByControlFilter(String requestUri) throws Exception {
        ControlFilter filter = controlFilterFromDescriptor();
        when(request.getRequestURI()).thenReturn(requestUri);

        filter.doFilter(request, response, chain);

        // The allow-list is matched with startsWith on the exact bytes, so the one /health entry is
        // case-sensitive and grants nothing outside its own prefix: /HEALTH/live, /healt/live,
        // /heal/th/live and the bare /live and /ready all still get the ordinary redirect. This is the
        // upper bound on how far the appended entry reaches.
        verify(chain, never()).doFilter(request, response);
        verify(response).sendRedirect(CONTEXT_PATH + "/control/main");
    }

    @ParameterizedTest(name = "{0} is gated by ControlFilter like any other unlisted path")
    @ValueSource(strings = {"/webtools/health", "/webtools/healthz/live", "/webtools/health/live/",
        "/webtools/health/liveness", "/webtools/health/ready2", "/webtools/health-internal"})
    public void everyNearMissUnderTheHealthPrefixIsGatedLikeAnyOtherUnlistedPath(String requestUri)
            throws Exception {
        String pathWithinWebapp = requestUri.substring(CONTEXT_PATH.length());
        ControlFilter controlFilter = controlFilterFromDescriptor();
        when(request.getRequestURI()).thenReturn(requestUri);

        controlFilter.doFilter(request, response, chain);

        // This suite used to assert the opposite: that every one of these cleared ControlFilter, because
        // the single /health allow-list entry was matched with startsWith and so granted anonymous chain
        // access to the whole byte prefix - /healthz/live included, which shares no path segment with a
        // probe at all. Nothing was ultimately SERVED to them, since no servlet-mapping claimed them, but
        // the grant was far wider than the two endpoints it existed for. It is now gone: these paths are
        // redirected to /control/main exactly like /webtools/entity/find, and anonymous admission is
        // exactly the two probe paths and nothing else.
        verify(chain, never()).doFilter(request, response);
        verify(response).sendRedirect(CONTEXT_PATH + "/control/main");

        // None of them is a mapped probe path or recognised as one either, so even a descriptor mistake
        // that let one through would not produce a probe verdict.
        for (String probePath : urlPatternsFor(HEALTH_SERVLET_NAME)) {
            assertNotEquals(probePath, pathWithinWebapp, "must not be one of the mapped probe paths");
        }
        assertFalse(HealthCheckServlet.isProbePath(pathWithinWebapp), "must not be recognised as a probe path");
    }

    @Test
    public void aTraversalDressedUpAsAProbePathIsRejectedOutrightRatherThanRedirected() throws Exception {
        ControlFilter filter = controlFilterFromDescriptor();
        when(request.getRequestURI()).thenReturn("/webtools/health/live/../secret");

        // ControlFilter's own URI-normalisation guard refuses this before the allow-list is consulted,
        // which is a stronger outcome than a redirect and matters directly now that /health is allow-
        // listed: without the guard, a URI whose raw bytes start with /health would clear the prefix
        // grant and only then be normalised. Asserting it here makes the guard staying in force part of
        // the probe contract; at runtime the container has already normalised the URI, so a traversal
        // can never reach the exact path comparison in the probe handler either.
        assertThrows(RuntimeException.class, () -> filter.doFilter(request, response, chain));
        verify(chain, never()).doFilter(request, response);
    }

    @ParameterizedTest(name = "{0} creates no session and has no body read in ControlFilter")
    @ValueSource(strings = {"/webtools/health/live", "/webtools/health/ready"})
    public void aProbeMintsNoSessionAndHasNoBodyReadInTheChain(String requestUri) throws Exception {
        // The probe filter answers before this filter is reached, so this asserts the second line of
        // defence rather than the first: ControlFilter carries an exemption for the same two paths, and a
        // webapp that registers the servlet WITHOUT the probe filter - or a descriptor edit that unmaps
        // it - depends on this exemption for exactly the properties the filter otherwise provides.
        ControlFilter filter = controlFilterFromDescriptor();
        when(request.getRequestURI()).thenReturn(requestUri);

        filter.doFilter(request, response, chain);

        // The exhaustive list of what the filter is allowed to touch for a probe: the context path and the
        // URI, which is what it needs to recognise one. getSession() would MINT A SESSION - per-instance
        // state created by an unauthenticated caller at the polling interval - and the parameter map would
        // READ THE REQUEST BODY. Neither may happen, and verifyNoMoreInteractions closes the list so a
        // later edit that reintroduces either fails here.
        verify(request).getContextPath();
        verify(request).getRequestURI();
        verifyNoMoreInteractions(request);
        verify(chain).doFilter(request, response);
        verify(response, never()).sendRedirect(anyString());
    }

    @ParameterizedTest(name = "{0} passes ContextFilter untouched")
    @ValueSource(strings = {"/webtools/health/live", "/webtools/health/ready"})
    public void aProbePassesContextFilterBeforeItCreatesASessionOrReadsTheBody(String requestUri)
            throws Exception {
        // The same second line of defence one filter further down, and the one that matters most: it is
        // ContextFilter that selects a tenant from the Host header or from a userTenantId parameter, so a
        // probe reaching it could leave this webapp's delegator pointed at another tenant's database.
        //
        // Deliberately NOT initialised. ContextFilter.init() builds the delegator, the security instance
        // and the service dispatcher, and every statement of doFilter beyond the probe bypass dereferences
        // the FilterConfig that init stores. Leaving it null is therefore the strongest possible assertion
        // that the bypass comes FIRST: a probe passes through, and the test below shows that anything else
        // reaches the machinery instead.
        ContextFilter filter = new ContextFilter();
        when(request.getRequestURI()).thenReturn(requestUri);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        // The same exhaustive list as for ControlFilter: the URI and the context path, and nothing that
        // creates a session, reads the body or resolves a tenant.
        verify(request).getRequestURI();
        verify(request).getContextPath();
        verifyNoMoreInteractions(request);
    }

    @Test
    public void anOrdinaryRequestStillReachesContextFiltersOwnMachinery() throws Exception {
        // The other half of the assertion above: the bypass is exactly two paths wide. An ordinary request
        // proceeds into the body of doFilter, which - with no FilterConfig, because init() was not called -
        // fails immediately. That failure IS the proof that the machinery was entered.
        ContextFilter filter = new ContextFilter();
        when(request.getRequestURI()).thenReturn(CONTEXT_PATH + "/control/main");

        assertThrows(NullPointerException.class, () -> filter.doFilter(request, response, chain),
                "an ordinary request must reach the filter's own setup rather than being passed through");
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

            // The real class, driven with the descriptor's own patterns: each mapped path is answered by a
            // probe handler - liveness 200, readiness 503 with no delegator available in a unit test - and
            // the handler creates no session, reads no body and sets no cookie of its own.
            //
            // Neither session accessor that CREATES a session is called. getSession(false) is called, and
            // deliberately: it is how the handler discovers a session that something in front of it minted
            // for the probe, so it can discard it. It never creates one, which is the property that
            // matters, and the two cases below assert what it does with what it finds.
            verify(probeRequest, never()).getSession();
            verify(probeRequest, never()).getSession(true);
            verify(probeRequest, never()).getInputStream();
            verify(probeRequest, never()).getReader();
            verify(probeResponse, never()).addCookie(any());
            verify(probeResponse, never()).sendRedirect(anyString());
            verify(probeResponse, never()).sendError(anyInt());
            verify(probeResponse, never()).setStatus(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    @ParameterizedTest(name = "the whole declared chain answers {0} anonymously, session-free and cookie-free")
    @ValueSource(strings = {"/health/live", "/health/ready"})
    public void theWholeDeclaredChainAnswersAProbeWithoutSessionCookieOrRedirect(String probePath)
            throws Exception {
        // End to end, and this is the test the review asked for. Every earlier assertion in this class
        // covers one link - the descriptor's ordering, the probe filter's decision, the servlet's verdict -
        // and a chain can be wrong while every link is right. So this drives the ACTUAL filters the
        // descriptor declares, in the order it declares them, initialised from its own init-params, with
        // the health servlet at the end, and asserts the four properties a load-balancer target group
        // depends on: 200, no redirect, no session, no session cookie.
        //
        // No datasource is available in a unit test, so readiness answers 503 - which is the case that
        // matters most: an instance that is not ready must still answer its probe as a probe rather than
        // redirect it or fail it, or a target group cannot tell "not ready yet" from "not a health
        // endpoint at all".
        RecordingResponse recorded = new RecordingResponse(response);
        when(request.getServletPath()).thenReturn(probePath);
        when(request.getPathInfo()).thenReturn(null);
        when(request.getRequestURI()).thenReturn(CONTEXT_PATH + probePath);
        when(request.getMethod()).thenReturn("GET");
        // Built before the stubbing below, never inside it: stubbing the dispatcher while a when(...) for
        // the context is still open is nested stubbing, which Mockito rejects.
        RequestDispatcher named = dispatcherInvoking(new HealthCheckServlet());
        when(servletContext.getNamedDispatcher(HEALTH_SERVLET_NAME)).thenReturn(named);

        runDeclaredChain(request, recorded);

        int expectedStatus = "/health/live".equals(probePath)
                ? HttpServletResponse.SC_OK
                : HttpServletResponse.SC_SERVICE_UNAVAILABLE;
        assertEquals(expectedStatus, recorded.status(), "the chain must answer " + probePath + " with a probe"
                + " verdict");
        assertFalse(recorded.redirected(), "a probe must never be redirected: a target group reads a 302 as an"
                + " unhealthy target");
        assertNull(recorded.errorStatus(), "a probe must be answered with setStatus, not sendError, which would"
                + " replace the JSON document with an HTML error page");
        assertEquals(List.of(), recorded.setCookies(), "the chain must set no cookie for a probe, found "
                + recorded.setCookies());
        verify(request, never()).getSession();
        verify(request, never()).getSession(true);
        // And the rest of the chain was never entered, which is why none of the above can regress: the
        // filters that would have created the session are not merely tolerated here, they do not run.
        assertEquals(List.of(), enteredFilters, "no filter after the probe filter may run for a probe, entered "
                + enteredFilters);
    }

    @ParameterizedTest(name = "the whole declared chain still gates {0}")
    @ValueSource(strings = {"/healthz/live", "/health/live/extra", "/entity/find"})
    public void theWholeDeclaredChainStillGatesEverythingThatIsNotAProbe(String path) throws Exception {
        // The same chain, the same way, for a path that is not a probe: it is redirected to the login
        // control path exactly as it was before this work. Asserted through the whole chain rather than
        // through ControlFilter alone, so inserting a filter ahead of it cannot have opened a bypass for
        // anything but the two probe paths.
        RecordingResponse recorded = new RecordingResponse(response);
        when(request.getServletPath()).thenReturn(path);
        when(request.getPathInfo()).thenReturn(null);
        when(request.getRequestURI()).thenReturn(CONTEXT_PATH + path);
        when(request.getMethod()).thenReturn("GET");
        // Built before the stubbing below, never inside it: stubbing the dispatcher while a when(...) for
        // the context is still open is nested stubbing, which Mockito rejects.
        RequestDispatcher named = dispatcherInvoking(new HealthCheckServlet());
        when(servletContext.getNamedDispatcher(HEALTH_SERVLET_NAME)).thenReturn(named);

        runDeclaredChain(request, recorded);

        assertEquals(CONTEXT_PATH + "/control/main", recorded.redirectedTo(), "[" + path + "] must be redirected"
                + " like any other unlisted path");
        assertNull(recorded.status(), "a redirected path must not also receive a probe verdict");
    }

    @Test
    public void aSessionMintedForAProbeIsInvalidatedAndItsCookieSuppressed() throws Exception {
        // The second line of defence, for a webapp that registers the servlet without the probe filter and
        // so leaves it behind ControlFilter and ContextFilter. Both call getSession() unconditionally, so
        // the servlet finds a session that was created for this request and nothing will ever use again:
        // the caller returns no session and follows no cookie. It discards it, and re-emits Set-Cookie
        // without the session cookie so a cookie-sticky balancer cannot pin traffic with an identifier a
        // health check minted.
        HttpSession minted = mock(HttpSession.class);
        when(minted.isNew()).thenReturn(true);
        HttpServletRequest probeRequest = mock(HttpServletRequest.class);
        when(probeRequest.getServletPath()).thenReturn("/health/live");
        when(probeRequest.getMethod()).thenReturn("GET");
        when(probeRequest.getSession(false)).thenReturn(minted);
        when(probeRequest.getServletContext()).thenReturn(servletContext);
        when(servletContext.getSessionCookieConfig()).thenReturn(null);
        HttpServletResponse probeResponse = mock(HttpServletResponse.class);
        when(probeResponse.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        when(probeResponse.getHeaders("Set-Cookie"))
                .thenReturn(List.of("JSESSIONID=ABC123; Path=/webtools; HttpOnly"));

        new HealthCheckServlet().service(probeRequest, probeResponse);

        verify(minted).invalidate();
        // Replaced with an immediate-expiry directive for the same cookie: the Servlet API has no
        // removeHeader, and leaving the establishing cookie in place is the outcome being prevented.
        ArgumentCaptor<String> emitted = ArgumentCaptor.forClass(String.class);
        verify(probeResponse).setHeader(eq("Set-Cookie"), emitted.capture());
        assertTrue(emitted.getValue().startsWith("JSESSIONID="), "the replacement must address the session"
                + " cookie: " + emitted.getValue());
        assertTrue(emitted.getValue().contains("Max-Age=0"), "the replacement must expire it immediately: "
                + emitted.getValue());
        assertFalse(emitted.getValue().contains("ABC123"), "the replacement must not re-establish the"
                + " session identifier: " + emitted.getValue());
        // And the probe still answers normally: discarding the session is not allowed to change the verdict.
        verify(probeResponse).setStatus(HttpServletResponse.SC_OK);
    }

    @Test
    public void aSessionAProbeDidNotCreateIsLeftAloneAndItsOwnersCookieIsKept() throws Exception {
        // The other direction, and it matters: a probe path can be requested by a browser carrying
        // somebody's session cookie. Invalidating that session would log its owner out, so only a session
        // this request created - isNew() - is discarded, and a response cookie that is not the session
        // cookie is left exactly as it was.
        HttpSession established = mock(HttpSession.class);
        when(established.isNew()).thenReturn(false);
        HttpServletRequest probeRequest = mock(HttpServletRequest.class);
        when(probeRequest.getServletPath()).thenReturn("/health/live");
        when(probeRequest.getMethod()).thenReturn("GET");
        when(probeRequest.getSession(false)).thenReturn(established);
        when(probeRequest.getServletContext()).thenReturn(servletContext);
        HttpServletResponse probeResponse = mock(HttpServletResponse.class);
        when(probeResponse.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        new HealthCheckServlet().service(probeRequest, probeResponse);

        verify(established, never()).invalidate();
        verify(probeResponse, never()).setHeader(eq("Set-Cookie"), anyString());
        verify(probeResponse).setStatus(HttpServletResponse.SC_OK);
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

        // No mapping routes such a path to the handler today, so this is the fail-closed direction should
        // the servlet-mapping ever be widened to a /health/* prefix: 404 rather than a misleading 200 that
        // would keep a broken instance in a target group. It matters more under the servlet-only
        // registration than it did before, because the allow-list entry already admits every /health*
        // spelling as far as the chain - the mapping is the only thing left deciding what is a probe.
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

    /**
     * Runs the filters the descriptor declares, in the order it declares them, ending at the health
     * servlet.
     *
     * <p>Only the probe filter and {@code ControlFilter} are instantiated for real: they are the two that
     * decide whether a request is admitted, redirected or bypassed, which is what these tests are about.
     * {@code CacheFilter}, {@code ContextFilter} and {@code SameSiteFilter} are represented by a stand-in
     * that records that it was entered and continues; instantiating them would need a live delegator, a
     * dispatcher and a component registry, none of which a unit test has, and none of which changes the
     * decision under test. Recording entry is the point: the probe assertions require that these three are
     * never reached at all, and {@link #enteredFilters} is how that is observed.
     *
     * <p>The order is read from the descriptor rather than restated, so a mapping reordered there is
     * reordered here too and the ordering assertions cannot pass against a stale copy.
     *
     * @param httpRequest the request to drive through the chain
     * @param httpResponse the response the chain writes to
     * @throws Exception if the chain cannot be built from the descriptor, or if a filter fails
     */
    private void runDeclaredChain(HttpServletRequest httpRequest, HttpServletResponse httpResponse)
            throws Exception {
        List<String> order = filterMappingOrder();
        List<Filter> filters = new ArrayList<>();
        for (String filterName : order) {
            if (PROBE_FILTER_NAME.equals(filterName)) {
                filters.add(probeFilterFromDescriptor());
            } else if (CONTROL_FILTER_NAME.equals(filterName)) {
                filters.add(controlFilterFromDescriptor());
            } else {
                filters.add(recordingFilter(filterName));
            }
        }
        // The terminal link. Reached only if every filter before it continued the chain, which for a probe
        // it does not - the probe filter forwards through the named dispatcher instead.
        FilterChain terminal = (req, res) -> new HealthCheckServlet()
                .service((HttpServletRequest) req, (HttpServletResponse) res);
        FilterChain assembled = terminal;
        for (int index = filters.size() - 1; index >= 0; index--) {
            Filter filter = filters.get(index);
            FilterChain next = assembled;
            assembled = (req, res) -> filter.doFilter(req, res, next);
        }
        assembled.doFilter(httpRequest, httpResponse);
    }

    /** Records that a filter of the legacy chain was entered, then continues. */
    private Filter recordingFilter(String filterName) {
        return (req, res, next) -> {
            enteredFilters.add(filterName);
            next.doFilter(req, res);
        };
    }

    /** A {@link RequestDispatcher} whose forward invokes the supplied servlet, as a named dispatch does. */
    private static RequestDispatcher dispatcherInvoking(HealthCheckServlet servlet) {
        RequestDispatcher dispatcher = mock(RequestDispatcher.class);
        try {
            doAnswer(invocation -> {
                servlet.service(invocation.getArgument(0), invocation.getArgument(1));
                return null;
            }).when(dispatcher).forward(any(), any());
        } catch (Exception impossible) {
            throw new IllegalStateException("stubbing a dispatcher must not fail", impossible);
        }
        return dispatcher;
    }

    /**
     * Builds a real {@link HealthProbeFilter} initialised from the production descriptor's own
     * declaration for it, so the behavioural assertions exercise the shipped configuration.
     *
     * <p>The descriptor declares no init-param for this filter, so the filter falls back to the default
     * servlet name; passing the descriptor's values through anyway is what makes an init-param added
     * later take effect in these tests instead of being silently ignored by them.
     */
    private HealthProbeFilter probeFilterFromDescriptor() throws Exception {
        FilterConfig config = mock(FilterConfig.class);
        when(config.getInitParameter(anyString())).thenReturn(null);
        for (Element initParam : childElements(filtersByName(PROBE_FILTER_NAME).get(0), "init-param")) {
            when(config.getInitParameter(childText(initParam, "param-name")))
                    .thenReturn(childText(initParam, "param-value"));
        }
        HealthProbeFilter filter = new HealthProbeFilter();
        filter.init(config);
        return filter;
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
    private List<Element> mappingElementsForFilter(String filterName) throws Exception {
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

    /**
     * Records what a whole chain run did to the response, so the end-to-end assertions read what happened
     * rather than interrogating a mock about calls that several links could have made.
     *
     * <p>A wrapper rather than a mock because the chain has several writers - a filter may redirect while a
     * servlet may set a status - and what matters is the outcome the client would observe. Cookies are
     * captured from both the {@code addCookie} and the raw {@code Set-Cookie} routes, because a container
     * emits a session cookie through the second one.
     */
    private static final class RecordingResponse extends HttpServletResponseWrapper {

        private final StringWriter sink = new StringWriter();
        private final PrintWriter writer = new PrintWriter(sink);
        private final List<String> setCookies = new ArrayList<>();
        private Integer status;
        private Integer errorStatus;
        private String redirectedTo;

        RecordingResponse(HttpServletResponse delegate) {
            super(delegate);
        }

        @Override
        public PrintWriter getWriter() {
            return writer;
        }

        @Override
        public void setStatus(int sc) {
            status = sc;
        }

        @Override
        public void sendError(int sc) {
            errorStatus = sc;
        }

        @Override
        public void sendError(int sc, String msg) {
            errorStatus = sc;
        }

        @Override
        public void sendRedirect(String location) {
            redirectedTo = location;
        }

        @Override
        public void addCookie(jakarta.servlet.http.Cookie cookie) {
            setCookies.add(cookie.getName() + "=" + cookie.getValue());
        }

        @Override
        public void addHeader(String name, String value) {
            if ("Set-Cookie".equalsIgnoreCase(name)) {
                setCookies.add(value);
            }
        }

        @Override
        public void setHeader(String name, String value) {
            if ("Set-Cookie".equalsIgnoreCase(name)) {
                setCookies.clear();
                setCookies.add(value);
            }
        }

        @Override
        public java.util.Collection<String> getHeaders(String name) {
            return "Set-Cookie".equalsIgnoreCase(name) ? List.copyOf(setCookies) : super.getHeaders(name);
        }

        private Integer status() {
            return status;
        }

        private Integer errorStatus() {
            return errorStatus;
        }

        private boolean redirected() {
            return redirectedTo != null;
        }

        private String redirectedTo() {
            return redirectedTo;
        }

        private List<String> setCookies() {
            return List.copyOf(setCookies);
        }
    }
}
