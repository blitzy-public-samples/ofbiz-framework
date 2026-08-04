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
package org.apache.ofbiz.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.ofbiz.entity.config.model.Datasource;
import org.apache.ofbiz.entity.config.model.DelegatorElement;
import org.apache.ofbiz.entity.config.model.EntityConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The schema-initialisation gate: a serving instance issues no start-up DDL, and the one-shot
 * initialisation run is the only thing that does.
 *
 * <p>Three artifacts carry that contract and each is asserted here: the committed
 * {@code framework/entity/config/entityengine.xml}, which still boots a bare checkout on embedded H2; the
 * deployed-profile template {@code docker/templates/postgres-entityengine.xml}, which leaves the two DDL
 * attributes to be decided per render; and {@code docker/docker-entrypoint.sh}, which is what decides them.
 *
 * <p>The class is hermetic: it opens no database connection, performs no network access and writes no file.
 * The only global state it touches is {@code ofbiz.home}, which is snapshotted and restored, because the
 * unit tier shares one JVM with every other suite.
 */
public final class SchemaInitGatingTests {

    /** The managed-RDBMS datasources the deployed profile binds the three frozen entity groups to. */
    private static final List<String> MANAGED_DATASOURCES =
            List.of("localpostgres", "localpostgresolap", "localpostgrestenant");

    /** The embedded datasources, which keep their start-up DDL because H2 is single-node dev and test only. */
    private static final List<String> EMBEDDED_DATASOURCES = List.of("localh2", "localh2olap", "localh2tenant");

    /** The frozen entity group names every delegator maps. */
    private static final List<String> ENTITY_GROUPS =
            List.of("org.apache.ofbiz", "org.apache.ofbiz.olap", "org.apache.ofbiz.tenant");

    /** The deployed-profile template the container entry point renders. */
    private static final String TEMPLATE = "docker/templates/postgres-entityengine.xml";

    /** The container entry point, which is the only thing that decides the rendered DDL flags. */
    private static final String ENTRY_POINT = "docker/docker-entrypoint.sh";

    /** A shell function declaration, used to attribute a call in the entry point to its function. */
    private static final Pattern FUNCTION = Pattern.compile("^([a-z_][a-z0-9_]*)\\(\\) \\{$");

    /** A call to the entity-engine renderer, capturing the two DDL arguments it is given. */
    private static final Pattern RENDER_CALL = Pattern.compile("^render_entity_engine (true|false) (true|false)$");

    /** The value {@code ofbiz.home} held before this class overwrote it, {@code null} if it held none. */
    private String ofbizHomeSnapshot;

    /**
     * Resolves {@code ofbiz.home} the way the other unit tests in this package do, so that the field-type
     * resource lookup {@code EntityConfig} performs while building its singleton succeeds.
     */
    @BeforeEach
    public void initialize() {
        ofbizHomeSnapshot = System.getProperty("ofbiz.home");
        System.setProperty("ofbiz.home", System.getProperty("user.dir"));
    }

    /** Puts {@code ofbiz.home} back exactly as it was found, clearing it when it was previously unset. */
    @AfterEach
    public void restoreOfbizHome() {
        if (ofbizHomeSnapshot == null) {
            System.clearProperty("ofbiz.home");
        } else {
            System.setProperty("ofbiz.home", ofbizHomeSnapshot);
        }
    }

    /**
     * Run mode: the managed-RDBMS datasources resolve both start-up DDL flags to false, so a serving
     * instance issues no DDL and needs no DDL privilege.
     *
     * <p>The resolved flags are asserted rather than the attribute text because the two are parsed
     * asymmetrically: {@code check-on-start} is read as {@code !"false".equals(value)} and so defaults to
     * true when the attribute is absent, whereas {@code add-missing-on-start} is read as
     * {@code "true".equals(value)}. Deleting {@code check-on-start} would therefore re-enable start-up DDL.
     */
    @Test
    public void managedRdbmsRunModeHasDdlDisabled() {
        for (String name : MANAGED_DATASOURCES) {
            Datasource managed = EntityConfig.getDatasource(name);
            assertNotNull(managed, "the managed-RDBMS datasource " + name
                    + " must stay declared: the deployed profile's group-maps resolve to it");
            assertFalse(managed.getCheckOnStart(), name
                    + " must not check the schema on start up, so a serving instance issues no DDL");
            assertFalse(managed.getAddMissingOnStart(), name
                    + " must not add missing schema objects on start up: DDL belongs to the one-shot init only");
        }
    }

    /**
     * The other half of the gate: the embedded datasources keep both start-up DDL flags enabled, which is
     * what lets a bare checkout, {@code gradlew loadAll} and {@code gradlew testIntegration} provision
     * themselves. An H2 database file belongs to the single JVM that opens it and never joins a fleet.
     */
    @Test
    public void theEmbeddedDatasourcesKeepTheirStartupDdl() {
        for (String name : EMBEDDED_DATASOURCES) {
            Datasource embedded = EntityConfig.getDatasource(name);
            assertNotNull(embedded, "the embedded datasource " + name + " must stay declared: the committed"
                    + " delegators resolve to it");
            assertTrue(embedded.getCheckOnStart(), name + " must keep checking the schema on start up: it is"
                    + " what makes a bare checkout self-provision");
            assertTrue(embedded.getAddMissingOnStart(), name + " must keep adding missing schema objects on"
                    + " start up, so loadAll and testIntegration need no schema step");
        }
    }

    /**
     * The committed configuration is unchanged in substance: all three delegators still resolve every
     * entity group to embedded H2, so a checkout with no environment variables boots exactly as before and
     * the test delegator never leaves H2.
     *
     * @throws GenericEntityConfException if the committed configuration cannot be read
     */
    @Test
    public void everyCommittedDelegatorStaysOnTheEmbeddedDatabase() throws GenericEntityConfException {
        for (String delegatorName : List.of("default", "default-no-eca", "test")) {
            DelegatorElement delegator = EntityConfig.getInstance().getDelegator(delegatorName);
            assertNotNull(delegator, "the " + delegatorName + " delegator must stay declared");
            for (int group = 0; group < ENTITY_GROUPS.size(); group++) {
                assertEquals(EMBEDDED_DATASOURCES.get(group),
                        delegator.getGroupDataSource(ENTITY_GROUPS.get(group)),
                        "the committed " + delegatorName + " delegator must resolve "
                                + ENTITY_GROUPS.get(group) + " to embedded H2");
            }
        }
    }

    /**
     * The deployed profile: the template binds the two serving delegators to the managed PostgreSQL
     * datasources and keeps the test delegator on embedded H2.
     *
     * @throws Exception if the template cannot be read or parsed
     */
    @Test
    public void theDeployedTemplateServesFromPostgreSqlAndKeepsTestOnH2() throws Exception {
        Document template = parse(TEMPLATE);
        for (String delegatorName : List.of("default", "default-no-eca")) {
            assertEquals(groups(MANAGED_DATASOURCES), groupMaps(delegator(template, delegatorName)),
                    "the deployed " + delegatorName + " delegator must resolve every group to PostgreSQL");
        }
        assertEquals(groups(EMBEDDED_DATASOURCES), groupMaps(delegator(template, "test")),
                "the test delegator must stay on embedded H2, so testIntegration never touches the"
                        + " deployment's own database");
    }

    /**
     * The template decides nothing about DDL: the managed datasources carry a placeholder per attribute, so
     * the value comes from the render, while the embedded ones carry the literal {@code true} they have
     * always carried.
     *
     * @throws Exception if the template cannot be read or parsed
     */
    @Test
    public void theTemplateLeavesTheManagedDdlFlagsToTheGate() throws Exception {
        Document template = parse(TEMPLATE);
        for (String name : MANAGED_DATASOURCES) {
            Element managed = datasource(template, name);
            assertEquals("@CHECK_ON_START@", managed.getAttribute("check-on-start"),
                    name + " must leave check-on-start to the render");
            assertEquals("@ADD_MISSING_ON_START@", managed.getAttribute("add-missing-on-start"),
                    name + " must leave add-missing-on-start to the render");
        }
        for (String name : EMBEDDED_DATASOURCES) {
            Element embedded = datasource(template, name);
            assertEquals("true", embedded.getAttribute("check-on-start"),
                    name + " must keep checking the schema on start up even in a container");
            assertEquals("true", embedded.getAttribute("add-missing-on-start"),
                    name + " must keep adding missing schema objects on start up even in a container");
        }
    }

    /**
     * Init mode: the one-shot initialisation is the only thing in the entry point that renders the managed
     * datasources with start-up DDL enabled, it creates the schema from the entity model, it puts the run
     * mode back before it exits, and it does nothing at all unless it was asked for.
     *
     * <p>Because one renderer produces {@code true} or {@code false} on the same three datasources from one
     * environment variable, neither result is a constant: every serving start renders {@code false false}.
     *
     * @throws IOException if the entry point cannot be read
     */
    @Test
    public void onlyTheOneShotInitRendersTheManagedDdlEnabled() throws IOException {
        List<String> lines = Files.readAllLines(repository().resolve(ENTRY_POINT));

        Map<String, List<String>> calls = renderCalls(lines);
        assertEquals(Set.of("configure_database", "run_schema_init"), calls.keySet(),
                "only the database configuration and the one-shot init may render the entity engine");
        assertEquals(List.of("false false"), calls.get("configure_database"),
                "a serving start must render the managed datasources with start-up DDL disabled");
        assertEquals(List.of("true true", "false false"), calls.get("run_schema_init"),
                "the init run must enable start-up DDL, then put the run mode back before it exits");

        String init = functionBody(lines, "run_schema_init");
        assertTrue(init.contains("if [ \"$OFBIZ_SCHEMA_INIT\" != \"true\" ]; then"),
                "the init run must be gated on OFBIZ_SCHEMA_INIT");
        assertTrue(init.indexOf("render_entity_engine true true") < init.indexOf("--load-data readers=none")
                        && init.indexOf("--load-data readers=none") < init.indexOf("render_entity_engine false false"),
                "the schema must be created by the engine from the entity model between the two renders");
        assertTrue(init.contains("exit 0"), "the init run must exit instead of serving traffic");

        String renderer = functionBody(lines, "render_entity_engine");
        assertTrue(renderer.contains("s|@CHECK_ON_START@|$checkOnStart|g"),
                "the renderer must substitute the check-on-start placeholder");
        assertTrue(renderer.contains("s|@ADD_MISSING_ON_START@|$addMissingOnStart|g"),
                "the renderer must substitute the add-missing-on-start placeholder");
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    /**
     * The repository root, which is the working directory of the unit tier.
     *
     * @return the repository root
     */
    private static Path repository() {
        return Path.of(System.getProperty("user.dir"));
    }

    /**
     * Parses a repository file into a DOM, without validation, so that no schema is fetched.
     *
     * @param relativePath the file, relative to the repository root
     * @return the parsed document
     * @throws Exception if the file cannot be read or parsed
     */
    private static Document parse(String relativePath) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        return factory.newDocumentBuilder().parse(repository().resolve(relativePath).toFile());
    }

    /**
     * Finds a named element of a given kind.
     *
     * @param document the document to search
     * @param tag the element name
     * @param name the value of the element's name attribute
     * @return the element
     */
    private static Element named(Document document, String tag, String name) {
        NodeList candidates = document.getElementsByTagName(tag);
        for (int i = 0; i < candidates.getLength(); i++) {
            Element candidate = (Element) candidates.item(i);
            if (name.equals(candidate.getAttribute("name"))) {
                return candidate;
            }
        }
        throw new AssertionError("no " + tag + " named [" + name + "] in the template");
    }

    /**
     * Finds a delegator.
     *
     * @param document the document to search
     * @param name the delegator name
     * @return the delegator element
     */
    private static Element delegator(Document document, String name) {
        return named(document, "delegator", name);
    }

    /**
     * Finds a datasource.
     *
     * @param document the document to search
     * @param name the datasource name
     * @return the datasource element
     */
    private static Element datasource(Document document, String name) {
        return named(document, "datasource", name);
    }

    /**
     * The datasource a delegator resolves each entity group to.
     *
     * @param delegator the delegator element
     * @return group name to datasource name
     */
    private static Map<String, String> groupMaps(Element delegator) {
        Map<String, String> resolved = new LinkedHashMap<>();
        NodeList maps = delegator.getElementsByTagName("group-map");
        for (int i = 0; i < maps.getLength(); i++) {
            Element map = (Element) maps.item(i);
            resolved.put(map.getAttribute("group-name"), map.getAttribute("datasource-name"));
        }
        return resolved;
    }

    /**
     * The expected group-map resolution for a set of datasources, in entity-group order.
     *
     * @param datasources the datasources, one per entity group
     * @return group name to datasource name
     */
    private static Map<String, String> groups(List<String> datasources) {
        Map<String, String> expected = new LinkedHashMap<>();
        for (int i = 0; i < ENTITY_GROUPS.size(); i++) {
            expected.put(ENTITY_GROUPS.get(i), datasources.get(i));
        }
        return expected;
    }

    /**
     * Every call to the entity-engine renderer in the entry point, with the DDL arguments it is given,
     * grouped by the shell function the call sits in.
     *
     * @param lines the entry point, line by line
     * @return function name to the arguments of each call it makes, in source order
     */
    private static Map<String, List<String>> renderCalls(List<String> lines) {
        Map<String, List<String>> calls = new LinkedHashMap<>();
        String function = "";
        for (String line : lines) {
            Matcher declaration = FUNCTION.matcher(line);
            if (declaration.matches()) {
                function = declaration.group(1);
            }
            Matcher call = RENDER_CALL.matcher(line.strip());
            if (call.matches()) {
                calls.computeIfAbsent(function, name -> new ArrayList<>())
                        .add(call.group(1) + " " + call.group(2));
            }
        }
        return calls;
    }

    /**
     * The body of a shell function, from its declaration to its closing brace.
     *
     * @param lines the entry point, line by line
     * @param name the function name
     * @return the body, newline separated
     */
    private static String functionBody(List<String> lines, String name) {
        int start = lines.indexOf(name + "() {");
        assertTrue(start >= 0, "the entry point must declare " + name + "()");
        StringBuilder body = new StringBuilder();
        for (int i = start + 1; i < lines.size() && !"}".equals(lines.get(i)); i++) {
            body.append(lines.get(i)).append('\n');
        }
        return body.toString();
    }
}
