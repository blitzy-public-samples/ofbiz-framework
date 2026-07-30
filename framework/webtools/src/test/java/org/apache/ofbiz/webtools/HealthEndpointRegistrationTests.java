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
 * <p>The registration is a plain servlet mapping plus a {@code /health} entry in
 * {@code ControlFilter}'s allow-list, which is the same mechanism the pre-existing {@code /ping.txt}
 * entry uses. Three properties carry the weight and are pinned here: the class is registered as a
 * servlet and never as a filter, so the descriptor cannot declare it twice and it stays out of the
 * chain; the legacy filter chain and the rest of the allow-list are untouched; and because
 * {@code ControlFilter} matches its allow-list with {@code startsWith}, the {@code /health} entry
 * lets other {@code /health*} spellings past the gate, so the two exact servlet url-patterns are
 * what confine the anonymous surface - anything else under the prefix reaches no mapping and is
 * answered by the container.
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

    /**
     * The allow-list the descriptor must declare: the eight legacy entries in their original order,
     * with the single {@code /health} entry appended. One entry covers both probes because
     * {@code ControlFilter} matches with {@code startsWith}, and appending rather than inserting keeps
     * every pre-existing entry at the position it already had.
     */
    private static final List<String> EXPECTED_ALLOWED_PATHS = List.of(
            "/ping.txt", "/error", "/control", "/select", "/index.html", "/index.jsp",
            "/default.html", "/default.jsp", "/health");

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

    @Test
    public void theProbesAreCarriedByASingleServletMappingWithNoWildcard() throws Exception {
        List<Element> healthMappings = servletMappingsFor(HEALTH_SERVLET_NAME);

        // One mapping element carrying both url-patterns, which Servlet 2.5 made legal and this 4.0
        // descriptor may therefore use. Two mapping elements would work identically but would let the
        // two probes drift apart.
        assertEquals(1, healthMappings.size(), "number of servlet-mapping elements for " + HEALTH_SERVLET_NAME);
        for (String pattern : PROBE_PATHS) {
            // A wildcard such as /health/* would map every spelling the allow-list prefix now lets
            // past ControlFilter onto the unauthenticated handler; exact patterns keep the anonymous
            // surface at exactly these two resources.
            assertTrue(!pattern.contains("*"), "probe url-pattern must be an exact path, found " + pattern);
        }
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * The class is registered as a servlet and nowhere else
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void theHealthClassIsRegisteredAsAServletAndNeverAsAFilter() throws Exception {
        for (Element filter : childElements(webApp(), "filter")) {
            String declaredName = childText(filter, "filter-name");
            assertNotEquals(HEALTH_FILTER_NAME, declaredName, "no filter may be named " + HEALTH_FILTER_NAME);
            // The class itself is what matters, not the name it is declared under: registering it as a
            // filter under any name would put it back in the chain, where it could answer a request
            // the container never routed to it.
            assertNotEquals(HEALTH_SERVLET_CLASS, childText(filter, "filter-class"),
                    "filter " + declaredName + " must not be backed by " + HEALTH_SERVLET_CLASS);
        }
        for (String mappedFilter : filterMappingOrder()) {
            assertNotEquals(HEALTH_FILTER_NAME, mappedFilter, "no filter-mapping may reference " + HEALTH_FILTER_NAME);
        }
        // And it is registered exactly once, as a servlet.
        assertEquals(1, servletsByName(HEALTH_SERVLET_NAME).size(), "servlet declarations");
    }

    @Test
    public void theLegacyFilterChainIsUnchanged() throws Exception {
        // The probe work added no filter and reordered none: the four pre-existing filters keep their
        // classes, their /* pattern and their chain order, so every other request is processed exactly
        // as before.
        assertEquals(List.of(CONTROL_FILTER_NAME, "CacheFilter", "ContextFilter", "SameSiteFilter"),
                filterMappingOrder(), "filter-mapping order");
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
    public void allowListAppendsTheHealthEntryAndRetainsEveryLegacyEntryInOrder() throws Exception {
        List<String> declared = declaredAllowedPaths();

        assertEquals(EXPECTED_ALLOWED_PATHS, declared, "ControlFilter allowedPaths");
        assertEquals("/control/main", initParameter(CONTROL_FILTER_NAME, "redirectPath"), "ControlFilter redirectPath");
        // Stated independently of the literal above: the legacy prefix is intact and exactly one new
        // entry was added, so the probes cost no pre-existing path its anonymous or gated status.
        assertEquals(LEGACY_ALLOWED_PATHS, declared.subList(0, LEGACY_ALLOWED_PATHS.size()), "legacy allowedPaths prefix");
        assertEquals(LEGACY_ALLOWED_PATHS.size() + 1, declared.size(), "exactly one entry may be added, found " + declared);
        assertEquals("/health", declared.get(declared.size() - 1), "the appended entry");
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

        // The long-standing anonymous endpoint is exercised to prove that appending the /health entry
        // disturbed none of the paths that were already allow-listed.
        verify(chain).doFilter(request, response);
        verify(response, never()).sendRedirect(anyString());
        verify(response, never()).sendError(anyInt(), anyString());
        verify(response, never()).sendError(anyInt());
    }

    @ParameterizedTest(name = "{0} reaches the chain anonymously")
    @ValueSource(strings = {"/webtools/health/live", "/webtools/health/ready"})
    public void bothProbePathsReachTheChainWithoutAuthentication(String requestUri) throws Exception {
        ControlFilter filter = controlFilterFromDescriptor();
        when(request.getRequestURI()).thenReturn(requestUri);

        filter.doFilter(request, response, chain);

        // This is the mechanism the registration depends on, asserted on a real ControlFilter built
        // from the descriptor's own allowedPaths value: without the /health entry a probe would be
        // redirected and a load-balancer target group would read the 302 as an unhealthy target.
        verify(chain).doFilter(request, response);
        verify(response, never()).sendRedirect(anyString());
        verify(response, never()).sendError(anyInt(), anyString());
        verify(response, never()).sendError(anyInt());
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

    @ParameterizedTest(name = "{0} clears the gate but maps to no probe")
    @ValueSource(strings = {"/webtools/health", "/webtools/healthz/live", "/webtools/health/live/",
        "/webtools/health/liveness", "/webtools/health/ready2"})
    public void aSpellingUnderTheHealthPrefixClearsTheGateButReachesNoProbe(String requestUri) throws Exception {
        ControlFilter filter = controlFilterFromDescriptor();
        when(request.getRequestURI()).thenReturn(requestUri);

        filter.doFilter(request, response, chain);

        // The consequence of matching with startsWith, pinned rather than glossed over: these do clear
        // the authentication gate. What confines the anonymous surface is the mapping, not the gate -
        // none of them matches either exact url-pattern, so the container answers them from its default
        // servlet and no probe handler is entered. Nothing else is mapped under /health, so the prefix
        // exposes no other resource.
        verify(chain).doFilter(request, response);
        String uri = requestUri.substring(CONTEXT_PATH.length());
        for (String probePath : urlPatternsFor(HEALTH_SERVLET_NAME)) {
            assertNotEquals(probePath, uri, "must not be one of the mapped probe paths");
        }
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
     * The probe servlet, driven with the descriptor's own url-patterns
     * ---------------------------------------------------------------------------------------------
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

            // The real class, driven with the descriptor's own patterns: each mapped path is answered
            // by a probe handler - liveness 200, readiness 503 with no delegator available in a unit
            // test - and the handler itself creates no session, reads no body and sets no cookie of its
            // own. The chain that runs ahead of it is the pre-existing one, unchanged by this work.
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
    public void aPathTheAllowListLetsPastButNoPatternMapsFailsVisiblyIfItEverReachesTheServlet() throws Exception {
        when(request.getServletPath()).thenReturn("/health");
        when(request.getPathInfo()).thenReturn("/liveness");
        when(request.getMethod()).thenReturn("GET");
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        new HealthCheckServlet().service(request, response);

        // The container answers such a path from its default servlet under the two exact patterns, so
        // this is the fail-closed direction should the mapping ever be widened to a /health/* prefix:
        // 404 rather than a misleading 200 that would keep a broken instance in a target group.
        verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
        verify(request, never()).getSession();
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
