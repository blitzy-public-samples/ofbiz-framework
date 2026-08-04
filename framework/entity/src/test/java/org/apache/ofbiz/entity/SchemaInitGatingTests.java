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
package org.apache.ofbiz.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.ofbiz.entity.config.model.Datasource;
import org.apache.ofbiz.entity.config.model.DelegatorElement;
import org.apache.ofbiz.entity.config.model.EntityConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The schema-initialisation gate: in the DEPLOYED PostgreSQL profile a serving instance issues no
 * start-up DDL, and the one-shot initialisation run is the only execution that enables it. The embedded
 * H2 development and test path is deliberately outside that gate - its datasources keep their start-up
 * DDL so that a bare checkout, {@code gradlew loadAll} and {@code gradlew testIntegration} provision
 * themselves - and this class asserts that too.
 *
 * <p>Three artifacts carry the contract and each is asserted here: the committed
 * {@code framework/entity/config/entityengine.xml}, which still boots a bare checkout on embedded H2; the
 * deployed-profile template {@code docker/templates/postgres-entityengine.xml}, which leaves the two DDL
 * attributes to be decided per render; and {@code docker/docker-entrypoint.sh}, which is what decides them.
 *
 * <p><strong>What the executing tests cover, and what they do not.</strong> The entry point is exercised by
 * RUNNING its shell functions rather than by reading its text: they are sourced into a throw-away container
 * root built under a JUnit temporary directory, alongside a stub {@code bin/ofbiz} that records how it was
 * called and returns a configurable status. What is asserted is therefore the SEQUENCING and the RENDERING -
 * the DDL attributes in the rendered {@code config/entityengine.xml}, the container-state markers, and the
 * commands the launcher was asked to run. No database is reached and no schema is created, so these tests
 * establish the gate's control flow and configuration output, not the DDL a real engine would emit. That is
 * the right boundary for the failure this gate exists to prevent - a serving instance inheriting start-up
 * DDL from an initialisation run that did not finish - because it is a sequence of executions rather than a
 * line of shell or a property of a database.
 *
 * <p>The class is hermetic: it opens no database connection, performs no network access, and writes only
 * inside the temporary directory JUnit gives it. The only global state it touches is {@code ofbiz.home},
 * which is snapshotted and restored, because the unit tier shares one JVM with every other suite. The
 * executing tests are skipped rather than failed where the shell utilities the entry point uses are
 * unavailable.
 */
public final class SchemaInitGatingTests {

    private static final List<String> MANAGED_DATASOURCES =
            List.of("localpostgres", "localpostgresolap", "localpostgrestenant");
    private static final List<String> EMBEDDED_DATASOURCES = List.of("localh2", "localh2olap", "localh2tenant");
    private static final List<String> ENTITY_GROUPS =
            List.of("org.apache.ofbiz", "org.apache.ofbiz.olap", "org.apache.ofbiz.tenant");
    private static final String TEMPLATE = "docker/templates/postgres-entityengine.xml";
    private static final String ENTRY_POINT = "docker/docker-entrypoint.sh";
    private static final List<String> ENTRY_POINT_SOURCES = List.of(
            "framework/security/config/security.properties",
            "applications/content/config/content.properties",
            "framework/webapp/config/url.properties",
            "framework/start/src/main/resources/org/apache/ofbiz/base/start/start.properties",
            "framework/catalina/ofbiz-component.xml",
            "framework/service/config/serviceengine.xml");

    private static final String MANAGED_HOST = "database.test.invalid";
    private static final long SHELL_TIMEOUT_SECONDS = 120L;

    @TempDir
    private Path containerRoot;

    private String ofbizHomeSnapshot;

    @BeforeEach
    public void initialize() throws IOException {
        ofbizHomeSnapshot = System.getProperty("ofbiz.home");
        System.setProperty("ofbiz.home", System.getProperty("user.dir"));
        assembleContainerRoot();
    }

    @AfterEach
    public void restoreOfbizHome() {
        if (ofbizHomeSnapshot == null) {
            System.clearProperty("ofbiz.home");
        } else {
            System.setProperty("ofbiz.home", ofbizHomeSnapshot);
        }
    }

    /**
     * Run mode: the managed-RDBMS datasources resolve both start-up DDL flags to false, so an instance
     * serving from the deployed PostgreSQL profile issues no DDL and needs no DDL privilege.
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
     * Distributed cache invalidation is off in the committed configuration, so an unconfigured checkout
     * keeps single-node cache behaviour and needs no message broker.
     *
     * @throws GenericEntityConfException if the committed configuration cannot be read
     */
    @Test
    public void distributedCacheClearIsDisabledInTheCommittedConfiguration() throws GenericEntityConfException {
        for (String delegatorName : List.of("default", "default-no-eca")) {
            DelegatorElement delegator = EntityConfig.getInstance().getDelegator(delegatorName);
            assertNotNull(delegator, "the " + delegatorName + " delegator must stay declared");
            assertFalse(delegator.getDistributedCacheClearEnabled(), "the committed " + delegatorName
                    + " delegator must keep distributed cache clear disabled: a checkout has no broker");
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
        Document template = parse(repository().resolve(TEMPLATE));
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
     * the value comes from the render, while the embedded ones carry the literal {@code true} the committed
     * configuration declares for them.
     *
     * @throws Exception if the template cannot be read or parsed
     */
    @Test
    public void theTemplateLeavesTheManagedDdlFlagsToTheGate() throws Exception {
        Document template = parse(repository().resolve(TEMPLATE));
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
     * A serving start renders the managed datasources with start-up DDL disabled, leaves the embedded ones
     * alone, and asks for no cache invalidation it has not been configured for.
     *
     * @throws Exception if the entry point cannot be run or its output cannot be parsed
     */
    @Test
    public void aServingStartRendersTheManagedDdlDisabled() throws Exception {
        assumeShellAvailable();

        int status = runEntryPoint(Map.of("OFBIZ_POSTGRES_HOST", MANAGED_HOST),
                "ofbiz_setup_env", "create_ofbiz_runtime_directories", "configure_database");

        assertEquals(0, status, "a serving start must succeed");
        assertManagedDdl("false");
        assertEmbeddedDdlUntouched();
        Document rendered = parse(renderedConfiguration());
        for (String delegatorName : List.of("default", "default-no-eca")) {
            assertEquals("false", delegator(rendered, delegatorName)
                            .getAttribute("distributed-cache-clear-enabled"),
                    "the rendered " + delegatorName + " delegator must default to single-node caching");
        }
        assertTrue(Files.exists(marker("db_config_applied")), "the render must record what it applied");
    }

    /**
     * The one-shot initialisation is the only execution that renders start-up DDL enabled: it enables it,
     * invokes the launcher that would apply the entity model, puts the run mode back, and exits instead of
     * serving.
     *
     * <p>The launcher here is the stub, so what is asserted is that the initialisation command was issued
     * and in what order the renders happened - not that a schema was created; no database is involved.
     *
     * @throws Exception if the entry point cannot be run or its output cannot be parsed
     */
    @Test
    public void theOneShotInitEnablesDdlIssuesTheInitCommandThenRestoresTheRunMode() throws Exception {
        assumeShellAvailable();

        int status = runEntryPoint(Map.of("OFBIZ_POSTGRES_HOST", MANAGED_HOST, "OFBIZ_SCHEMA_INIT", "true"),
                "ofbiz_setup_env", "create_ofbiz_runtime_directories", "run_schema_init",
                "echo REACHED-THE-SERVING-COMMAND");

        assertEquals(0, status, "a completed initialisation must exit successfully");
        assertTrue(invocations().contains("--load-data readers=none"),
                "the initialisation must start the engine, which is what applies the entity model");
        assertFalse(output().contains("REACHED-THE-SERVING-COMMAND"),
                "the initialisation run must exit instead of going on to serve traffic");
        assertManagedDdl("false");
    }

    /**
     * A FAILED initialisation puts the run mode back before it gives up, so the volume it leaves behind
     * cannot start a serving instance with start-up DDL enabled.
     *
     * @throws Exception if the entry point cannot be run or its output cannot be parsed
     */
    @Test
    public void aFailedInitRestoresTheRunModeAndDoesNotServe() throws Exception {
        assumeShellAvailable();
        stubExitStatus(3);

        int status = runEntryPoint(Map.of("OFBIZ_POSTGRES_HOST", MANAGED_HOST, "OFBIZ_SCHEMA_INIT", "true"),
                "ofbiz_setup_env", "create_ofbiz_runtime_directories", "run_schema_init",
                "echo REACHED-THE-SERVING-COMMAND");

        assertNotEquals(0, status, "a failed initialisation must fail the container");
        assertFalse(output().contains("REACHED-THE-SERVING-COMMAND"),
                "a failed initialisation must not go on to serve traffic");
        assertManagedDdl("false");
    }

    /**
     * An INTERRUPTED initialisation - killed after the DDL-enabled render and before anything could put the
     * run mode back - is repaired by the next serving start, because the marker records the mode that was
     * rendered rather than merely that something was.
     *
     * <p>This is the failure this gate exists for. A marker that only recorded "configured" would be
     * matched by the serving start, the render would be skipped, and the instance would come up with
     * start-up DDL enabled on a persisted volume.
     *
     * @throws Exception if the entry point cannot be run or its output cannot be parsed
     */
    @Test
    public void anInterruptedInitIsRepairedByTheNextServingStart() throws Exception {
        assumeShellAvailable();

        int killed = runEntryPoint(Map.of("OFBIZ_POSTGRES_HOST", MANAGED_HOST),
                "ofbiz_setup_env", "create_ofbiz_runtime_directories", "render_entity_engine true true");
        assertEquals(0, killed, "the render an interrupted initialisation leaves behind must itself succeed");
        assertManagedDdl("true");
        String leaked = Files.readString(marker("db_config_applied"), StandardCharsets.UTF_8);

        int serving = runEntryPoint(Map.of("OFBIZ_POSTGRES_HOST", MANAGED_HOST),
                "ofbiz_setup_env", "create_ofbiz_runtime_directories", "configure_database");

        assertEquals(0, serving, "the serving start must succeed");
        assertManagedDdl("false");
        assertNotEquals(leaked, Files.readString(marker("db_config_applied"), StandardCharsets.UTF_8),
                "the serving start must record the mode it rendered, not the one it found");
    }

    /**
     * A volume carrying the EMPTY markers an image bakes in cannot vouch for an external database, so a
     * container pointed at one loads its data instead of trusting them.
     *
     * @throws Exception if the entry point cannot be run
     */
    @Test
    public void aLegacyMarkerDoesNotVouchForAnExternalDatabase() throws Exception {
        assumeShellAvailable();
        Files.createDirectories(containerRoot.resolve("runtime/container_state"));
        for (String name : List.of("data_loaded", "admin_loaded", "db_config_applied")) {
            Files.writeString(marker(name), "", StandardCharsets.UTF_8);
        }

        int status = runEntryPoint(Map.of("OFBIZ_POSTGRES_HOST", MANAGED_HOST),
                "ofbiz_setup_env", "create_ofbiz_runtime_directories", "configure_database", "load_data");

        assertEquals(0, status, "the start must succeed");
        assertTrue(invocations().contains("--load-data readers=seed,seed-initial"),
                "an empty marker must not stop the data being loaded into a database it knows nothing about");
        assertManagedDdl("false");
        assertFalse(Files.readString(marker("data_loaded"), StandardCharsets.UTF_8).isBlank(),
                "the marker must be rewritten with the configuration it now vouches for");
    }

    /**
     * The same volume WITHOUT an external database keeps the meaning the baked markers do have: the
     * embedded database shipped beside them is loaded, so nothing is loaded again.
     *
     * @throws Exception if the entry point cannot be run
     */
    @Test
    public void aLegacyMarkerStillVouchesForTheEmbeddedDatabase() throws Exception {
        assumeShellAvailable();
        Files.createDirectories(containerRoot.resolve("runtime/container_state"));
        for (String name : List.of("data_loaded", "admin_loaded", "db_config_applied")) {
            Files.writeString(marker(name), "", StandardCharsets.UTF_8);
        }

        int status = runEntryPoint(Map.of(),
                "ofbiz_setup_env", "create_ofbiz_runtime_directories", "configure_database", "load_data");

        assertEquals(0, status, "the start must succeed");
        assertFalse(invocations().contains("--load-data readers=seed,seed-initial"),
                "an image that baked its data must not reload it on every start");
        assertFalse(Files.exists(renderedConfiguration()),
                "with no managed database configured nothing may be rendered over the committed H2 profile");
    }

    private static Path repository() {
        return Path.of(System.getProperty("user.dir"));
    }

    private void assembleContainerRoot() throws IOException {
        Files.copy(repository().resolve(ENTRY_POINT), containerRoot.resolve("docker-entrypoint.sh"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.createDirectories(containerRoot.resolve("templates"));
        Files.copy(repository().resolve(TEMPLATE),
                containerRoot.resolve("templates/postgres-entityengine.xml"),
                StandardCopyOption.REPLACE_EXISTING);
        for (String source : ENTRY_POINT_SOURCES) {
            Path destination = containerRoot.resolve(source);
            Files.createDirectories(destination.getParent());
            Files.copy(repository().resolve(source), destination, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.createDirectories(containerRoot.resolve("config"));
        Files.createDirectories(containerRoot.resolve("bin"));
        Path stub = containerRoot.resolve("bin/ofbiz");
        Files.writeString(stub, "#!/bin/sh\n"
                + "echo \"$*\" >> \"$(dirname \"$0\")/../invocations\"\n"
                + "exit \"${STUB_OFBIZ_EXIT:-0}\"\n", StandardCharsets.UTF_8);
        assertTrue(stub.toFile().setExecutable(true), "the stub launcher must be executable");
        stubExitStatus(0);
    }

    private void stubExitStatus(int status) throws IOException {
        Files.writeString(containerRoot.resolve("stub-exit"), Integer.toString(status), StandardCharsets.UTF_8);
    }

    /**
     * Sources the entry point into a shell running in the throw-away root and runs the given fragments.
     *
     * <p>Only the functions named are run: the script's own bottom-of-file guard keeps it from running
     * anything when it is sourced rather than executed, which is what makes this possible.
     *
     * @param environment the environment variables to supply
     * @param fragments the shell fragments to run after sourcing, in order
     * @return the exit status of the shell
     * @throws IOException if the shell cannot be started
     * @throws InterruptedException if waiting for the shell is interrupted
     */
    private int runEntryPoint(Map<String, String> environment, String... fragments)
            throws IOException, InterruptedException {
        StringBuilder script = new StringBuilder(". ./docker-entrypoint.sh\n");
        for (String fragment : fragments) {
            script.append(fragment).append('\n');
        }
        ProcessBuilder builder = new ProcessBuilder("bash", "-c", script.toString());
        builder.directory(containerRoot.toFile());
        builder.environment().put("OFBIZ_CONTAINER_ROOT", containerRoot.toString());
        builder.environment().put("STUB_OFBIZ_EXIT",
                Files.readString(containerRoot.resolve("stub-exit"), StandardCharsets.UTF_8).trim());
        builder.environment().putAll(environment);
        builder.redirectErrorStream(true);
        builder.redirectOutput(containerRoot.resolve("output").toFile());
        Process shell = builder.start();
        assertTrue(shell.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "the entry point must finish within " + SHELL_TIMEOUT_SECONDS + " seconds");
        return shell.exitValue();
    }

    private void assumeShellAvailable() {
        boolean available;
        try {
            ProcessBuilder builder = new ProcessBuilder("bash", "-c",
                    "command -v sed sha256sum xsltproc >/dev/null && sed --quiet '' /dev/null"
                            + " && printf x | sha256sum >/dev/null");
            builder.redirectErrorStream(true);
            builder.redirectOutput(containerRoot.resolve("probe").toFile());
            Process probe = builder.start();
            available = probe.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS) && probe.exitValue() == 0;
        } catch (IOException | InterruptedException unsupported) {
            available = false;
        }
        assumeTrue(available, "bash with GNU sed, sha256sum and xsltproc is required to run the entry point");
    }

    private Path renderedConfiguration() {
        return containerRoot.resolve("config/entityengine.xml");
    }

    private Path marker(String name) {
        return containerRoot.resolve("runtime/container_state").resolve(name);
    }

    private String invocations() throws IOException {
        Path record = containerRoot.resolve("invocations");
        return Files.exists(record) ? Files.readString(record, StandardCharsets.UTF_8) : "";
    }

    private String output() throws IOException {
        Path captured = containerRoot.resolve("output");
        return Files.exists(captured) ? Files.readString(captured, StandardCharsets.UTF_8) : "";
    }

    private void assertManagedDdl(String expected) throws Exception {
        Document rendered = parse(renderedConfiguration());
        for (String name : MANAGED_DATASOURCES) {
            Element managed = datasource(rendered, name);
            assertEquals(expected, managed.getAttribute("check-on-start"),
                    "the rendered " + name + " must carry check-on-start=" + expected);
            assertEquals(expected, managed.getAttribute("add-missing-on-start"),
                    "the rendered " + name + " must carry add-missing-on-start=" + expected);
        }
    }

    private void assertEmbeddedDdlUntouched() throws Exception {
        Document rendered = parse(renderedConfiguration());
        for (String name : EMBEDDED_DATASOURCES) {
            Element embedded = datasource(rendered, name);
            assertEquals("true", embedded.getAttribute("check-on-start"),
                    "the rendered " + name + " must keep checking the schema on start up");
            assertEquals("true", embedded.getAttribute("add-missing-on-start"),
                    "the rendered " + name + " must keep adding missing schema objects on start up");
        }
    }

    /**
     * Parses an XML file with a parser that resolves no external entities.
     *
     * @param path the file to parse
     * @return the parsed document
     * @throws Exception if the file cannot be read or parsed
     */
    private static Document parse(Path path) throws Exception {
        assertTrue(Files.exists(path), path + " must exist");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setNamespaceAware(false);
        return factory.newDocumentBuilder().parse(path.toFile());
    }

    private static Element named(Document document, String tag, String name) {
        NodeList candidates = document.getElementsByTagName(tag);
        for (int index = 0; index < candidates.getLength(); index++) {
            Element candidate = (Element) candidates.item(index);
            if (name.equals(candidate.getAttribute("name"))) {
                return candidate;
            }
        }
        throw new AssertionError("no " + tag + " named " + name + " was found");
    }

    private static Element delegator(Document document, String name) {
        return named(document, "delegator", name);
    }

    private static Element datasource(Document document, String name) {
        return named(document, "datasource", name);
    }

    private static Map<String, String> groupMaps(Element delegator) {
        Map<String, String> bindings = new LinkedHashMap<>();
        NodeList maps = delegator.getElementsByTagName("group-map");
        for (int index = 0; index < maps.getLength(); index++) {
            Element map = (Element) maps.item(index);
            bindings.put(map.getAttribute("group-name"), map.getAttribute("datasource-name"));
        }
        return bindings;
    }

    private static Map<String, String> groups(List<String> datasources) {
        Map<String, String> expected = new LinkedHashMap<>();
        for (int index = 0; index < ENTITY_GROUPS.size(); index++) {
            expected.put(ENTITY_GROUPS.get(index), datasources.get(index));
        }
        return expected;
    }
}
