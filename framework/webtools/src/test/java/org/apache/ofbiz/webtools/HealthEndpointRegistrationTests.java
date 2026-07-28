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

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
 * <p>Two registration properties carry the security weight and are pinned here. The probe filter's
 * mapping is declared before every other filter mapping, which is what stops a probe from reaching
 * the filters that create a session and parse the request body. And no {@code /health} prefix is
 * allow-listed in {@code ControlFilter}, which matches its allow-list with {@code startsWith}: a
 * bare prefix there would grant anonymous passage to every {@code /health*} spelling rather than to
 * the two probe paths.
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

    /**
     * The eight paths that were allow-listed before the load-balancer work, in their original order.
     * The probes add nothing to this list: they are answered by the probe filter before
     * {@code ControlFilter} is reached, so they need no allow-list entry, and giving them one would
     * mean reserving an open {@code /health} prefix for every spelling.
     */
    private static final List<String> EXPECTED_ALLOWED_PATHS = List.of(
            "/ping.txt", "/error", "/control", "/select", "/index.html", "/index.jsp",
            "/default.html", "/default.jsp");

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
     * ---------------------------------------------------------------------------------------------
     * Descriptor contract
     * ---------------------------------------------------------------------------------------------
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
        assertEquals(List.of(), childElements(healthServlet, "init-param"), "init-param elements");
    }

    @Test
    public void healthServletMapsExactlyTheTwoProbePaths() throws Exception {
        assertEquals(PROBE_PATHS, urlPatternsFor(HEALTH_SERVLET_NAME), "health url-patterns");
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * The probe filter: same component, declared first, on exactly the two probe paths
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void healthProbesAreAlsoRegisteredAsAFilterBackedByTheSameClass() throws Exception {
        List<Element> healthFilters = filtersByName(HEALTH_FILTER_NAME);

        assertEquals(1, healthFilters.size(), "number of " + HEALTH_FILTER_NAME + " declarations");
        // Registering the very same class in both roles is what lets it answer the probe from the
        // filter position while remaining the servlet the container resolves those paths to.
        assertEquals(HEALTH_SERVLET_CLASS, childText(healthFilters.get(0), "filter-class"), "filter-class");
        assertEquals(List.of(), childElements(healthFilters.get(0), "init-param"), "init-param elements");
    }

    @Test
    public void healthFilterMapsExactlyTheTwoProbePathsWithNoWildcard() throws Exception {
        List<String> patterns = filterUrlPatternsFor(HEALTH_FILTER_NAME);

        assertEquals(PROBE_PATHS, patterns, "health filter url-patterns");
        for (String pattern : patterns) {
            // A wildcard here would hand every /health* spelling to the unauthenticated handler and
            // reintroduce exactly the open prefix that was removed from the ControlFilter allow-list.
            assertTrue(!pattern.contains("*"), "probe filter url-pattern must be an exact path, found " + pattern);
        }
        // The filter and the servlet must cover the same two resources; a drift between them would
        // leave one path going through the ordinary chain.
        assertEquals(urlPatternsFor(HEALTH_SERVLET_NAME), patterns, "filter and servlet url-patterns must agree");
    }

    @Test
    public void healthFilterMappingIsDeclaredBeforeEveryOtherFilterMapping() throws Exception {
        List<String> order = filterMappingOrder();

        // The servlet specification builds the chain in the order the url-pattern mappings appear in
        // the descriptor. Being first is therefore the whole mechanism: it is what makes the probe
        // terminate the chain before ControlFilter and ContextFilter can create a session, and before
        // ContextFilter can hand the request body to the JSON parser.
        assertEquals(HEALTH_FILTER_NAME, order.get(0), "first filter-mapping in " + order);
        assertEquals(List.of(HEALTH_FILTER_NAME, CONTROL_FILTER_NAME, "CacheFilter", "ContextFilter", "SameSiteFilter"),
                order, "filter-mapping order");
    }

    @Test
    public void theLegacyFilterChainIsOtherwiseUnchanged() throws Exception {
        // Everything the probe work touched in the chain is the one prepended mapping. The four
        // pre-existing filters keep their classes and their /* pattern.
        assertEquals("org.apache.ofbiz.webapp.control.ControlFilter", filterClass(CONTROL_FILTER_NAME), "ControlFilter class");
        assertEquals("org.apache.ofbiz.base.util.CacheFilter", filterClass("CacheFilter"), "CacheFilter class");
        assertEquals("org.apache.ofbiz.webapp.control.ContextFilter", filterClass("ContextFilter"), "ContextFilter class");
        assertEquals("org.apache.ofbiz.webapp.control.SameSiteFilter", filterClass("SameSiteFilter"), "SameSiteFilter class");
        for (String legacyFilter : List.of(CONTROL_FILTER_NAME, "CacheFilter", "ContextFilter", "SameSiteFilter")) {
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
    public void allowListRetainsEveryLegacyEntryInOrderAndReservesNoHealthPrefix() throws Exception {
        assertEquals(EXPECTED_ALLOWED_PATHS, declaredAllowedPaths(), "ControlFilter allowedPaths");
        assertEquals("/control/main", initParameter(CONTROL_FILTER_NAME, "redirectPath"), "ControlFilter redirectPath");
        for (String allowed : declaredAllowedPaths()) {
            // ControlFilter matches its allow-list with startsWith, so any entry under /health would
            // clear the authentication gate for every /health* spelling, not just for the two probes.
            assertTrue(!allowed.startsWith("/health"),
                    "no /health prefix may be allow-listed, found " + allowed);
        }
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Behaviour of the real filter driven by the real descriptor values
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void theLegacyAllowListedPathStillReachesTheChainWithoutAuthentication() throws Exception {
        ControlFilter filter = controlFilterFromDescriptor();
        when(request.getRequestURI()).thenReturn("/webtools/ping.txt");

        filter.doFilter(request, response, chain);

        // The long-standing anonymous endpoint is exercised to prove that removing the /health entry
        // disturbed none of the paths that were already allow-listed.
        verify(chain).doFilter(request, response);
        verify(response, never()).sendRedirect(anyString());
        verify(response, never()).sendError(anyInt(), anyString());
        verify(response, never()).sendError(anyInt());
    }

    @ParameterizedTest(name = "{0} is redirected by ControlFilter")
    @ValueSource(strings = {"/webtools/entity/find", "/webtools/live", "/webtools/ready", "/webtools/healt/live",
        "/webtools/HEALTH/live", "/webtools/health", "/webtools/healthz/live", "/webtools/health/live/",
        "/webtools/health/live", "/webtools/health/ready"})
    public void everyHealthSpellingIncludingTheProbesIsGatedByControlFilter(String requestUri) throws Exception {
        ControlFilter filter = controlFilterFromDescriptor();
        when(request.getRequestURI()).thenReturn(requestUri);

        filter.doFilter(request, response, chain);

        // Nothing under /health clears the authentication gate any more, the two probe paths included:
        // they never arrive here, because the probe filter answers them first. Should that mapping ever
        // be removed, this is what the probes would fall back to - a redirect - rather than open
        // anonymous passage, which is the fail-closed direction.
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
     * ---------------------------------------------------------------------------------------------
     * The probe filter, driven with the descriptor's own url-patterns
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void aProbeIsAnsweredWithoutASessionAndWithoutContinuingTheChain() throws Exception {
        for (String probePath : filterUrlPatternsFor(HEALTH_FILTER_NAME)) {
            HttpServletRequest probeRequest = mock(HttpServletRequest.class);
            when(probeRequest.getServletPath()).thenReturn(probePath);
            when(probeRequest.getMethod()).thenReturn("GET");
            HttpServletResponse probeResponse = mock(HttpServletResponse.class);
            when(probeResponse.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
            FilterChain probeChain = mock(FilterChain.class);

            new HealthCheckServlet().doFilter(probeRequest, probeResponse, probeChain);

            // The two findings this registration exists to close, asserted on the real class driven
            // with the descriptor's own patterns: the chain stops here, so ControlFilter's and
            // ContextFilter's unconditional getSession() is never reached and neither is the JSON body
            // parser ContextFilter hands the request to.
            verify(probeChain, never()).doFilter(probeRequest, probeResponse);
            verify(probeRequest, never()).getSession();
            verify(probeRequest, never()).getSession(anyBoolean());
            verify(probeRequest, never()).getInputStream();
            verify(probeRequest, never()).getReader();
            verify(probeResponse, never()).addCookie(any());
        }
    }

    @Test
    public void aNonProbeRequestPassesThroughTheProbeFilterIntoTheOrdinaryChain() throws Exception {
        when(request.getServletPath()).thenReturn("/control/main");
        when(request.getMethod()).thenReturn("GET");

        new HealthCheckServlet().doFilter(request, response, chain);

        // Being first in the chain must cost the rest of the webapp nothing: a request that is not a
        // probe is handed on untouched, with no status and no body written.
        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(anyInt());
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Helpers - descriptor access
     * ---------------------------------------------------------------------------------------------
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
        for (Element mapping : childElements(webApp(), "servlet-mapping")) {
            if (servletName.equals(childText(mapping, "servlet-name"))) {
                for (Element pattern : childElements(mapping, "url-pattern")) {
                    patterns.add(text(pattern));
                }
            }
        }
        return patterns;
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
}
