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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import org.apache.ofbiz.base.container.ContainerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.SAXParseException;

/**
 * Configuration contract of the load-balancer readiness properties in {@code framework/catalina/ofbiz-component.xml}.
 *
 * <p>Three engine properties make an instance safe to place behind a TLS-terminating load balancer:
 * {@code jvm-route} (sticky-session route id), {@code ssl-accelerator-port} (marks forwarded plain HTTP
 * as secure so absolute URLs stay {@code https://}) and {@code enable-cross-subdomain-sessions}. Each is
 * declared with a default that reproduces the pre-refactor behaviour exactly, and the container entry
 * point rewrites the declared value in place - so the DECLARATION itself is a deployment contract, not
 * an implementation detail: remove the {@code ssl-accelerator-port} line and the substitution silently
 * produces a file with no such property at all.
 *
 * <p>Two things are asserted separately and deliberately:
 * <ul>
 * <li>the declarations exist with the exact default values, read from the DOM of the real descriptor;</li>
 * <li>what {@link ContainerConfig} actually resolves them to, obtained by feeding the real element to the
 * production {@code Configuration} parser - because {@code getPropertyValue} treats an EMPTY value as
 * ABSENT, which is precisely how {@code ssl-accelerator-port=""} keeps the {@code SslAcceleratorValve}
 * uninstalled while still providing a substitution anchor.</li>
 * </ul>
 *
 * <p>The descriptor declares two containers whose property trees are near identical. Everything here
 * selects them STRUCTURALLY, by {@code name} plus {@code loaders} among the {@code <container>} children
 * of the root, never by a line number or a substring scan that could match the wrong one.
 */
public final class CatalinaContainerDescriptorTests {

    private static final String DESCRIPTOR = "framework/catalina/ofbiz-component.xml";
    private static final String COMPONENT_SCHEMA = "framework/base/dtd/ofbiz-component.xsd";
    private static final String PRODUCTION_CONTAINER = "catalina-container";
    private static final String TEST_CONTAINER = "catalina-container-test";
    private static final String CONTAINER_CLASS = "org.apache.ofbiz.catalina.container.CatalinaContainer";
    private static final String ENGINE_PROPERTY = "default-server";
    private static final String JVM_ROUTE = "jvm-route";
    private static final String SSL_ACCELERATOR_PORT = "ssl-accelerator-port";
    private static final String CROSS_SUBDOMAIN_SESSIONS = "enable-cross-subdomain-sessions";
    private static final String HTTP_CONNECTOR = "http-connector";

    /** The descriptor is parsed once per test so that element identities can be compared. */
    private Element descriptorRoot;

    @BeforeEach
    public void parseDescriptor() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        Path descriptor = repositoryRoot().resolve(DESCRIPTOR);
        assertTrue(Files.isRegularFile(descriptor), "missing authoritative descriptor " + descriptor);
        Document document = factory.newDocumentBuilder().parse(descriptor.toFile());
        descriptorRoot = document.getDocumentElement();
        // The descriptor carries no XML namespace - only an xsi:noNamespaceSchemaLocation hint - so the
        // parser is namespace aware and every lookup below still goes through getLocalName().
        assertEquals("ofbiz-component", descriptorRoot.getLocalName(), "descriptor root element");
        assertNull(descriptorRoot.getNamespaceURI(), "the component descriptor grammar has no target namespace");
        assertEquals("catalina", descriptorRoot.getAttribute("name"), "component name");
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Structure: exactly two containers, told apart by name AND loaders
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void descriptorDeclaresExactlyTheProductionAndTestContainers() {
        Map<String, Element> containers = containersByName();

        assertEquals(List.of(PRODUCTION_CONTAINER, TEST_CONTAINER), new ArrayList<>(containers.keySet()),
                "the <container> children of the descriptor, in document order");
        assertEquals("main", containers.get(PRODUCTION_CONTAINER).getAttribute("loaders"),
                "the production container is the one loaded by the \"main\" resource loader");
        assertEquals("test", containers.get(TEST_CONTAINER).getAttribute("loaders"),
                "the test container is the one loaded by the \"test\" resource loader");
        for (Map.Entry<String, Element> container : containers.entrySet()) {
            assertEquals(CONTAINER_CLASS, container.getValue().getAttribute("class"),
                    "implementation class of " + container.getKey());
        }
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * The three load-balancer declarations on the PRODUCTION engine
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void productionEngineDeclaresTheStickySessionRoute() {
        Element declaration = enginePropertyOf(PRODUCTION_CONTAINER, JVM_ROUTE);

        // A concrete default is required, not an empty one: ContainerConfig treats an empty value as
        // absent, which would leave the engine with no JVM route at all instead of the pre-refactor
        // "jvm1". Behind a sticky-session balancer each instance is given a distinct value from here.
        assertEquals("jvm1", declaration.getAttribute("value"), JVM_ROUTE + " default");
    }

    @Test
    public void productionEngineDeclaresTheSslAcceleratorPortAnchorEmpty() {
        Element declaration = enginePropertyOf(PRODUCTION_CONTAINER, SSL_ACCELERATOR_PORT);

        // Declared, so the entry point has an anchor to substitute into, and EMPTY, so the
        // SslAcceleratorValve is not installed out of the box - identical behaviour to the commented-out
        // declaration this replaced.
        assertEquals("", declaration.getAttribute("value"), SSL_ACCELERATOR_PORT + " default");
    }

    @Test
    public void productionEngineDeclaresCrossSubdomainSessionsDisabled() {
        Element declaration = enginePropertyOf(PRODUCTION_CONTAINER, CROSS_SUBDOMAIN_SESSIONS);

        // "false" keeps the CrossSubdomainSessionValve out of the pipeline; the valve calls
        // getSession(true) for every request, so enabling it by default would be a behaviour change.
        assertEquals("false", declaration.getAttribute("value"), CROSS_SUBDOMAIN_SESSIONS + " default");
    }

    @Test
    public void loadBalancerDeclarationsStaySingleSelfContainedElements() {
        for (String property : List.of(JVM_ROUTE, SSL_ACCELERATOR_PORT, CROSS_SUBDOMAIN_SESSIONS)) {
            Element declaration = enginePropertyOf(PRODUCTION_CONTAINER, property);

            // The entry point rewrites each of these with a single
            // s|<property name="NAME" value="[^"]*"/>|...| substitution, so none of them may grow a
            // <property-value> child or nested properties: the value must live in the attribute.
            assertEquals(List.of(), childElements(declaration), property + " must have no child elements");
            assertTrue(declaration.hasAttribute("value"), property + " must carry a value attribute");
        }
    }

    @Test
    public void theFirstJvmRouteInTheDocumentIsTheProductionOne() {
        List<Element> routes = allPropertyDeclarations(JVM_ROUTE);

        // Both containers declare jvm-route identically, and the entry point substitutes only the FIRST
        // occurrence. If the containers were ever reordered, that substitution would start rewriting the
        // test container and leave production untouched - silently.
        assertEquals(2, routes.size(), "jvm-route declarations in the whole descriptor");
        assertSame(enginePropertyOf(PRODUCTION_CONTAINER, JVM_ROUTE), routes.get(0),
                "the first jvm-route in document order must belong to the production container");
    }

    @Test
    public void sslAcceleratorPortMatchesThePlainHttpConnectorItIsCompared() {
        Element httpPort = propertyChild(containerProperty(PRODUCTION_CONTAINER, HTTP_CONNECTOR), "port");

        // SslAcceleratorValve compares the configured port with request.getLocalPort(), so the value an
        // operator injects is this LOCAL plain-HTTP port - never the balancer's public HTTPS port. Pinning
        // it here keeps the documentation on the ssl-accelerator-port declaration honest.
        assertEquals("8080", httpPort.getAttribute("value"), "the local plain-http connector port");
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * What the production parser resolves those declarations to
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void containerConfigResolvesTheProductionEngineToPreRefactorBehaviour() {
        // The real production parser, fed the real element. Configuration's public constructor is used
        // rather than ContainerConfig.getConfigurations() on purpose: the latter also registers the
        // result in a static map, and a unit test must not leave global state behind.
        ContainerConfig.Configuration.Property engine =
                new ContainerConfig.Configuration(containersByName().get(PRODUCTION_CONTAINER)).getProperty(ENGINE_PROPERTY);
        assertNotNull(engine, "the production container must declare the " + ENGINE_PROPERTY + " engine block");

        assertEquals("jvm1", ContainerConfig.getPropertyValue(engine, JVM_ROUTE, null),
                "the engine must still be given the pre-refactor JVM route");
        // Declared but empty resolves to the default, which is exactly why the valve stays uninstalled.
        assertNotNull(engine.getProperty(SSL_ACCELERATOR_PORT), SSL_ACCELERATOR_PORT + " must be declared");
        assertNull(ContainerConfig.getPropertyValue(engine, SSL_ACCELERATOR_PORT, null),
                "an empty " + SSL_ACCELERATOR_PORT + " must resolve as absent so SslAcceleratorValve is not installed");
        assertNotNull(engine.getProperty(CROSS_SUBDOMAIN_SESSIONS), CROSS_SUBDOMAIN_SESSIONS + " must be declared");
        assertFalse(ContainerConfig.getPropertyValue(engine, CROSS_SUBDOMAIN_SESSIONS, false),
                "CrossSubdomainSessionValve must stay out of the pipeline by default");
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * The test container must be untouched
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void testContainerKeepsItsOwnRouteAndGainsNoLoadBalancerProperties() {
        ContainerConfig.Configuration.Property engine =
                new ContainerConfig.Configuration(containersByName().get(TEST_CONTAINER)).getProperty(ENGINE_PROPERTY);
        assertNotNull(engine, "the test container must declare the " + ENGINE_PROPERTY + " engine block");

        // The integration-test container is not part of a load-balanced fleet, so this refactor left it
        // alone. Asserting the ABSENCE of the two new properties here is what proves the change was
        // applied to the production container only.
        assertEquals("jvm1", ContainerConfig.getPropertyValue(engine, JVM_ROUTE, null),
                "the test container keeps its own unchanged jvm-route");
        assertNull(engine.getProperty(SSL_ACCELERATOR_PORT),
                TEST_CONTAINER + " must not declare " + SSL_ACCELERATOR_PORT);
        assertNull(engine.getProperty(CROSS_SUBDOMAIN_SESSIONS),
                TEST_CONTAINER + " must not declare " + CROSS_SUBDOMAIN_SESSIONS);
    }

    @Test
    public void neitherLoadBalancerPropertyIsDeclaredMoreThanOnce() {
        assertEquals(1, allPropertyDeclarations(SSL_ACCELERATOR_PORT).size(),
                SSL_ACCELERATOR_PORT + " declarations in the whole descriptor");
        assertEquals(1, allPropertyDeclarations(CROSS_SUBDOMAIN_SESSIONS).size(),
                CROSS_SUBDOMAIN_SESSIONS + " declarations in the whole descriptor");
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * The trust boundary the valve itself cannot enforce
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void theAcceleratorDeclarationStatesTheTrustBoundaryItCannotEnforce() throws Exception {
        String guidance = acceleratorGuidance();

        // SslAcceleratorValve.invoke() marks a request secure when request.getLocalPort() equals the
        // configured port and checks nothing else - no source address, no X-Forwarded-Proto, no Forwarded
        // header. The port number is therefore not the trust decision, and an operator who reads only
        // "set this to the local port" can enable TLS semantics on a directly reachable connector. The
        // guidance has to say so, because there is no code path here that can refuse that mistake: the
        // network restriction lives outside the JVM. Asserting on the wording is the only way to keep it
        // from being trimmed back to the port-only instruction it replaced.
        assertTrue(guidance.contains("TRUST BOUNDARY"),
                "the " + SSL_ACCELERATOR_PORT + " guidance must carry an explicit trust-boundary warning");
        assertTrue(guidance.contains("the NETWORK is"),
                "the guidance must say that the network, not the port number, is the trust decision");
        assertTrue(guidance.contains("unreachable except"),
                "the guidance must require the forwarded-traffic connector to be unreachable except through the proxy");
        assertTrue(guidance.contains("internalProxies / trustedProxies"),
                "the guidance must name the RemoteIpValve contract that authenticates the forwarded hop");
        assertTrue(guidance.contains("strip client supplied"),
                "the guidance must require the proxy to strip and re-set client supplied forwarded headers");
        assertTrue(guidance.contains("bound to 0.0.0.0"),
                "the guidance must name the unsafe bind address it warns against, which is the shipped default");
    }

    @Test
    public void theAcceleratorGuidanceNoLongerPromisesAnUnvalidatedRewrite() throws Exception {
        String guidance = acceleratorGuidance();

        // The entry point validates the value as an integer in 1..65535 and refuses one that matches no
        // connector port declared in this file, instead of handing an arbitrary string to
        // Integer.valueOf deep inside container loading. The declaration has to describe that contract,
        // not the older unconditional "rewrites this value in place".
        assertTrue(guidance.contains("between 1 and 65535"),
                "the guidance must state the range the entry point enforces");
        assertTrue(guidance.contains("does not match a connector port"),
                "the guidance must state that a port matching no connector is refused");
        assertTrue(guidance.contains("NumberFormatException"),
                "the guidance must explain what the validation prevents");
    }

    /**
     * The comment that immediately precedes the {@code ssl-accelerator-port} declaration.
     *
     * <p>Read from the raw file rather than from the parsed document because the assertions above are
     * about the text an operator reads at the point of configuration, and a comment node's position
     * relative to its declaration is exactly what makes it that text.</p>
     */
    private String acceleratorGuidance() throws Exception {
        String descriptor = Files.readString(repositoryRoot().resolve(DESCRIPTOR));
        int declaration = descriptor.indexOf("<property name=\"" + SSL_ACCELERATOR_PORT + "\" value=");
        assertTrue(declaration > 0, SSL_ACCELERATOR_PORT + " must be declared in " + DESCRIPTOR);
        int commentEnd = descriptor.lastIndexOf("-->", declaration);
        int commentStart = descriptor.lastIndexOf("<!--", commentEnd);
        assertTrue(commentStart > 0 && commentEnd > commentStart,
                SSL_ACCELERATOR_PORT + " must be preceded by an explanatory comment");
        return descriptor.substring(commentStart, commentEnd);
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Schema validity
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void descriptorRemainsValidAgainstTheComponentSchema() throws Exception {
        Path descriptor = repositoryRoot().resolve(DESCRIPTOR);
        Path schemaFile = repositoryRoot().resolve(COMPONENT_SCHEMA);
        assertTrue(Files.isRegularFile(schemaFile), "missing local schema " + schemaFile);

        SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        schemaFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        // The descriptor's xsi:noNamespaceSchemaLocation points at ofbiz.apache.org. The LOCAL copy is
        // supplied as the grammar so validation stays hermetic, and external access is switched off on
        // top of that so this test can never become a web request.
        forbidExternalAccess(schemaFactory);
        Schema schema = schemaFactory.newSchema(schemaFile.toFile());
        Validator validator = schema.newValidator();
        forbidExternalAccess(validator);
        List<String> problems = new ArrayList<>();
        validator.setErrorHandler(new ErrorHandler() {
            @Override
            public void warning(SAXParseException e) {
                problems.add("warning: " + e.getMessage());
            }

            @Override
            public void error(SAXParseException e) {
                problems.add("error: " + e.getMessage());
            }

            @Override
            public void fatalError(SAXParseException e) {
                problems.add("fatal: " + e.getMessage());
            }
        });

        validator.validate(new StreamSource(descriptor.toFile()));

        // The three new declarations use the generic <property name=".." value=".."/> grammar, so schema
        // validity is what proves no bespoke element was smuggled into a shared component descriptor.
        assertEquals(List.of(), problems, "schema validation problems");
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Helpers
     * ---------------------------------------------------------------------------------------------
     */

    /** The {@code <container>} children of the root, keyed by name, in document order. */
    private Map<String, Element> containersByName() {
        Map<String, Element> containers = new LinkedHashMap<>();
        for (Element container : childElements(descriptorRoot, "container")) {
            String name = container.getAttribute("name");
            assertNull(containers.put(name, container), "duplicate container name " + name);
        }
        return containers;
    }

    /** A {@code <property>} of a named container's engine block, which must exist exactly once. */
    private Element enginePropertyOf(String containerName, String propertyName) {
        return propertyChild(containerProperty(containerName, ENGINE_PROPERTY), propertyName);
    }

    private Element containerProperty(String containerName, String propertyName) {
        Element container = containersByName().get(containerName);
        assertNotNull(container, "no container named " + containerName);
        return propertyChild(container, propertyName);
    }

    private static Element propertyChild(Element parent, String propertyName) {
        List<Element> matches = new ArrayList<>();
        for (Element property : childElements(parent, "property")) {
            if (propertyName.equals(property.getAttribute("name"))) {
                matches.add(property);
            }
        }
        assertEquals(1, matches.size(), "expected exactly one <property name=\"" + propertyName + "\"> child");
        return matches.get(0);
    }

    /** Every {@code <property>} with the given name anywhere in the descriptor, in document order. */
    private List<Element> allPropertyDeclarations(String propertyName) {
        List<Element> matches = new ArrayList<>();
        NodeList properties = descriptorRoot.getOwnerDocument().getElementsByTagName("property");
        for (int i = 0; i < properties.getLength(); i++) {
            Element property = (Element) properties.item(i);
            if (propertyName.equals(property.getAttribute("name"))) {
                matches.add(property);
            }
        }
        return matches;
    }

    /**
     * Forbids the loading of anything external, so validation can never turn into a web request for the
     * {@code https://ofbiz.apache.org} schema hint the descriptor carries. Both the JDK and the Xerces
     * build on the compile classpath may provide the {@code SchemaFactory}, and they do not agree on
     * which of these JAXP properties they recognise, so an unsupported one is tolerated: the explicit
     * local grammar already keeps validation self-contained, and this is belt and braces on top.
     */
    private static void forbidExternalAccess(SchemaFactory factory) {
        for (String property : List.of(XMLConstants.ACCESS_EXTERNAL_DTD, XMLConstants.ACCESS_EXTERNAL_SCHEMA)) {
            try {
                factory.setProperty(property, "");
            } catch (SAXNotRecognizedException | SAXNotSupportedException unsupported) {
                // This implementation does not expose the property; the explicit grammar still applies.
                continue;
            }
        }
    }

    /** @see #forbidExternalAccess(SchemaFactory) */
    private static void forbidExternalAccess(Validator validator) {
        for (String property : List.of(XMLConstants.ACCESS_EXTERNAL_DTD, XMLConstants.ACCESS_EXTERNAL_SCHEMA)) {
            try {
                validator.setProperty(property, "");
            } catch (SAXNotRecognizedException | SAXNotSupportedException unsupported) {
                // This implementation does not expose the property; the explicit grammar still applies.
                continue;
            }
        }
    }

    private static List<Element> childElements(Element parent) {
        return childElements(parent, null);
    }

    private static List<Element> childElements(Element parent, String localName) {
        List<Element> children = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && (localName == null || localName.equals(node.getLocalName()))) {
                children.add((Element) node);
            }
        }
        return children;
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
