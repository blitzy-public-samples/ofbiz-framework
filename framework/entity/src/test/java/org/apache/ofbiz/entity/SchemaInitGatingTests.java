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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
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
            "framework/service/config/serviceengine.xml",
            "framework/base/config/jndiservers.xml");

    private static final String MANAGED_HOST = "database.test.invalid";
    /** A 64-character value, the shortest a signing key may be, because HMAC512 creates the token. */
    private static final String LONG_KEY = "K".repeat(64);
    private static final String ADMIN_PASSWORD = "Adm1n-Passw0rd-Long-Enough";
    private static final String ADMIN_KEY = "Adm1n-Key-Long-Enough";
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
        requireShellAvailable();

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
        requireShellAvailable();

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
        requireShellAvailable();
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
     * An initialisation whose LOADER EXITED 0 while a datasource failed is a FAILURE, and says so.
     *
     * <p>This is the one that mattered in production. The data loader returns 0 whenever the JVM completed,
     * and the Entity Engine's start-up schema check is deliberately non-fatal - it logs "Unable to establish
     * a connection with the database" and carries on - so an initialisation given a wrong password for one
     * of the three groups exited 0 and printed its success banner while that group's schema was never
     * created. An orchestrator reads that as a completed migration and rolls a fleet out onto an incomplete
     * schema, which is why the outcome is aggregated from the engine's own output rather than inferred from
     * the exit status.
     *
     * @throws Exception if the entry point cannot be run or its output cannot be parsed
     */
    @Test
    public void anInitWhoseDatasourceFailedIsAFailureEvenWhenTheLoaderExitsZero() throws Exception {
        requireShellAvailable();
        stubExitStatus(0);
        stubFailureLine("Unable to establish a connection with the database for helperName"
                + " [localpostgrestenant]... Error was: FATAL: password authentication failed");

        int status = runEntryPoint(Map.of("OFBIZ_POSTGRES_HOST", MANAGED_HOST, "OFBIZ_SCHEMA_INIT", "true"),
                "ofbiz_setup_env", "create_ofbiz_runtime_directories", "run_schema_init",
                "echo REACHED-THE-SERVING-COMMAND");

        assertNotEquals(0, status, "an initialisation that could not reach a datasource must fail the job");
        assertFalse(output().contains("Schema initialisation is complete"),
                "a failed initialisation must print no success banner: " + output());
        assertTrue(output().contains("schema initialisation FAILED"),
                "the failure must be reported so an orchestrator and an operator both see it: " + output());
        assertFalse(output().contains("REACHED-THE-SERVING-COMMAND"),
                "a failed initialisation must not go on to serve traffic");
        assertManagedDdl("false");
    }

    /**
     * An initialisation that never checked one of its entity groups is a FAILURE.
     *
     * <p>The complement of the test above: a group whose datasource is missing from the run at all - the
     * shape a DML-only role produced, where nothing was created in any of the three databases - leaves no
     * failure line of its own to match, so the run is also required to show that EVERY group the rendered
     * configuration points at PostgreSQL was actually checked.
     *
     * @throws Exception if the entry point cannot be run or its output cannot be parsed
     */
    @Test
    public void anInitThatSkippedAnEntityGroupIsAFailure() throws Exception {
        requireShellAvailable();
        stubExitStatus(0);
        stubGroupChecks(ENTITY_GROUPS.size() - 1);

        int status = runEntryPoint(Map.of("OFBIZ_POSTGRES_HOST", MANAGED_HOST, "OFBIZ_SCHEMA_INIT", "true"),
                "ofbiz_setup_env", "create_ofbiz_runtime_directories", "run_schema_init");

        assertNotEquals(0, status, "an initialisation that skipped a group must fail the job");
        assertFalse(output().contains("Schema initialisation is complete"),
                "a failed initialisation must print no success banner: " + output());
        assertManagedDdl("false");
    }

    /**
     * A COMPLETED initialisation reports the group count it verified, so the success banner says what was
     * actually checked rather than merely that the process ended.
     *
     * @throws Exception if the entry point cannot be run or its output cannot be parsed
     */
    @Test
    public void aCompletedInitReportsEveryGroupItChecked() throws Exception {
        requireShellAvailable();

        int status = runEntryPoint(Map.of("OFBIZ_POSTGRES_HOST", MANAGED_HOST, "OFBIZ_SCHEMA_INIT", "true"),
                "ofbiz_setup_env", "create_ofbiz_runtime_directories", "run_schema_init");

        assertEquals(0, status, "a completed initialisation must exit successfully");
        assertTrue(output().contains("all " + ENTITY_GROUPS.size() + " configured entity group(s) were checked"),
                "the banner must state how many groups were verified: " + output());
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
        requireShellAvailable();

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
     * A marker is written {@code rw-------} and its digest never reaches the container's output.
     *
     * <p>A marker is container state on a PERSISTED volume, so both halves matter. World-readable state on a
     * volume another container may mount is state anything can read and, with a writable mount, forge - and a
     * forged data marker makes the next start skip the data load. Tracing it repeats it into {@code docker
     * logs} and from there into every log collector reading that stream, which is the same CWE-532 exposure
     * the rendered secrets are kept out of. Suppressing tracing INSIDE the writer cannot achieve the second
     * half, because a shell prints an invocation before the function body runs, so the digest must not be an
     * argument at all - which is why the writer takes the digest KIND and computes the digest itself.
     *
     * @throws Exception if the entry point cannot be run
     */
    @Test
    public void aMarkerIsPrivateAndItsDigestIsNeverTraced() throws Exception {
        requireShellAvailable();

        int status = runEntryPoint(Map.of("OFBIZ_POSTGRES_HOST", MANAGED_HOST),
                "ofbiz_setup_env", "create_ofbiz_runtime_directories", "configure_database");

        assertEquals(0, status, "the database configuration must be applied");
        Path marker = marker("db_config_applied");
        String digest = Files.readString(marker, StandardCharsets.UTF_8);
        assertEquals(64, digest.length(), "the marker must hold a sha256 digest: " + digest);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(marker)),
                "the marker must be readable and writable by the OFBiz user alone");
        assertFalse(output().contains(digest),
                "the digest must not appear in the container's output, which docker logs keeps: " + output());
    }

    /**
     * Rotating a database PASSWORD leaves the marker digest untouched, because no credential is in the
     * pre-image it is computed from.
     *
     * <p>A digest is not a one-way function of a secret when the secret is the only unknown in the
     * pre-image. Every other field the database marker covers is a container setting an operator already
     * knows or can read out of {@code docker inspect}, so a pre-image containing the three database
     * passwords would be a persisted, offline-guessable commitment to them - on a volume that outlives the
     * container that wrote it. Passwords rarely carry the entropy that makes such a commitment safe.
     *
     * <p>Nothing is lost by leaving them out: this marker records which configuration was applied and, in
     * particular, the startup-DDL mode it was rendered with, and a rotated password changes neither. The
     * markers that are actually READ - data and admin - are keyed on database IDENTITY and were already
     * credential-free.
     *
     * @throws Exception if the entry point cannot be run
     */
    @Test
    public void rotatingADatabasePasswordDoesNotChangeTheMarkerDigest() throws Exception {
        requireShellAvailable();
        Map<String, String> before = new LinkedHashMap<>(Map.of("OFBIZ_POSTGRES_HOST", MANAGED_HOST,
                "OFBIZ_POSTGRES_OFBIZ_PASSWORD", "first-ofbiz-password",
                "OFBIZ_POSTGRES_OLAP_PASSWORD", "first-olap-password",
                "OFBIZ_POSTGRES_TENANT_PASSWORD", "first-tenant-password"));
        assertEquals(0, runEntryPoint(before, "ofbiz_setup_env", "create_ofbiz_runtime_directories",
                "configure_database"), "the first start must succeed");
        String first = Files.readString(marker("db_config_applied"), StandardCharsets.UTF_8);

        Map<String, String> after = new LinkedHashMap<>(Map.of("OFBIZ_POSTGRES_HOST", MANAGED_HOST,
                "OFBIZ_POSTGRES_OFBIZ_PASSWORD", "second-ofbiz-password",
                "OFBIZ_POSTGRES_OLAP_PASSWORD", "second-olap-password",
                "OFBIZ_POSTGRES_TENANT_PASSWORD", "second-tenant-password"));
        assertEquals(0, runEntryPoint(after, "ofbiz_setup_env", "create_ofbiz_runtime_directories",
                "configure_database"), "the start after a password rotation must succeed");

        assertEquals(first, Files.readString(marker("db_config_applied"), StandardCharsets.UTF_8),
                "a rotated password must not be observable in the marker, which means it must not be in the"
                        + " pre-image the digest is taken over");
        assertFalse(output().contains("second-ofbiz-password"),
                "a password must never reach the container's output: " + output());
    }

    /**
     * A volume carrying the EMPTY markers an image bakes in cannot vouch for an external database, so a
     * container pointed at one loads its data instead of trusting them.
     *
     * @throws Exception if the entry point cannot be run
     */
    @Test
    public void aLegacyMarkerDoesNotVouchForAnExternalDatabase() throws Exception {
        requireShellAvailable();
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
        requireShellAvailable();
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

    // =============================================================================================
    // Goal 2 - the secret pipeline.
    //
    // Driven through the real entry point in the throw-away root, so what is asserted is what a
    // container would actually do: which values are demanded, which are refused, where they are
    // written, and what is NEVER written.
    // =============================================================================================

    /**
     * The prod profile refuses to start when a required secret is missing, and names every one of them.
     *
     * @throws Exception if the entry point cannot be run
     */
    @Test
    public void theProdProfileRefusesToStartWithoutItsSecrets() throws Exception {
        requireShellAvailable();

        int status = runEntryPoint(Map.of("OFBIZ_PROFILE", "prod"), "ofbiz_setup_env");

        assertNotEquals(0, status, "a prod deployment with no secrets must fail fast, not start");
        for (String required : List.of("OFBIZ_ADMIN_PASSWORD", "OFBIZ_ADMIN_KEY", "OFBIZ_LOGIN_SECRET_KEY",
                "OFBIZ_JWT_TOKEN_KEY")) {
            assertTrue(output().contains(required),
                    "the refusal must name " + required + " so the operator knows what to supply: " + output());
        }
    }

    /**
     * The dev profile tolerates every secret being absent, so an unconfigured container still boots - and
     * generates an admin key for itself, because without one a clean shutdown would be impossible.
     *
     * @throws Exception if the entry point cannot be run
     */
    @Test
    public void theDevProfileBootsWithNoSecretsAndStillGetsAnAdminKey() throws Exception {
        requireShellAvailable();

        int status = runEntryPoint(Map.of(), "ofbiz_setup_env", "create_ofbiz_runtime_directories", "apply_configuration");

        assertEquals(0, status, "an unconfigured container must still boot in the dev profile");
        Path override = containerRoot.resolve("config/org/apache/ofbiz/base/start/start.properties");
        assertTrue(Files.exists(override), "the admin key must be rendered into the class-path override");
        String rendered = Files.readString(override, StandardCharsets.UTF_8);
        assertTrue(rendered.lines().anyMatch(line -> line.startsWith("ofbiz.admin.key=")
                        && line.length() > "ofbiz.admin.key=".length()),
                "the dev profile must generate a key rather than leave the property empty");
    }

    /**
     * A signing key shorter than the 64 characters HMAC512 needs is refused rather than rendered: a short
     * key would be written into the configuration and then rejected by JWTManager at the first use.
     *
     * @throws Exception if the entry point cannot be run
     */
    @Test
    public void aSigningKeyTooShortToBeUsableIsRefused() throws Exception {
        requireShellAvailable();
        Map<String, String> environment = new LinkedHashMap<>(prodSecrets());
        environment.put("OFBIZ_JWT_TOKEN_KEY", "far-too-short");

        int status = runEntryPoint(environment, "ofbiz_setup_env");

        assertNotEquals(0, status, "a signing key that cannot be used must be refused at start up");
        assertTrue(output().contains("OFBIZ_JWT_TOKEN_KEY"), "the refusal must name the key: " + output());
    }

    /**
     * An admin key holding a colon is refused: the admin protocol reads a request as key:command and
     * compares everything before the FIRST colon, so such a key could never match and would make a clean
     * shutdown impossible.
     *
     * @throws Exception if the entry point cannot be run
     */
    @Test
    public void anAdminKeyThatCouldNeverAuthenticateIsRefused() throws Exception {
        requireShellAvailable();
        Map<String, String> environment = new LinkedHashMap<>(prodSecrets());
        environment.put("OFBIZ_ADMIN_KEY", "key:with:colons");

        int status = runEntryPoint(environment, "ofbiz_setup_env");

        assertNotEquals(0, status, "an admin key containing a colon must be refused");
        assertTrue(output().contains("OFBIZ_ADMIN_KEY"), "the refusal must name the key: " + output());
    }

    /**
     * A credential published in this repository's own example environment file is refused in the prod
     * profile: it is public, so it is not a secret, however strong it looks.
     *
     * @throws Exception if the entry point cannot be run
     */
    @Test
    public void aPubliclyPublishedExampleCredentialIsRefused() throws Exception {
        requireShellAvailable();
        Map<String, String> environment = new LinkedHashMap<>(prodSecrets());
        environment.put("OFBIZ_POSTGRES_HOST", MANAGED_HOST);
        environment.put("OFBIZ_POSTGRES_OFBIZ_PASSWORD", publishedExampleCredential());

        int status = runEntryPoint(environment, "ofbiz_setup_env");

        assertNotEquals(0, status, "a credential published in the repository must never be accepted in prod");
        assertTrue(output().contains("OFBIZ_POSTGRES_OFBIZ_PASSWORD"),
                "the refusal must name the variable: " + output());
    }

    /**
     * The secrets are rendered into the class-path override alone, readable by the owner alone, and the
     * copies packaged in the image are left exactly as they were built.
     *
     * @throws Exception if the entry point cannot be run
     */
    @Test
    public void secretsAreRenderedIntoTheOverrideAndNeverIntoThePackagedCopy() throws Exception {
        requireShellAvailable();
        String packagedBefore = Files.readString(containerRoot.resolve(
                "framework/security/config/security.properties"), StandardCharsets.UTF_8);

        int status = runEntryPoint(prodSecrets(), "ofbiz_setup_env", "create_ofbiz_runtime_directories", "apply_configuration");

        assertEquals(0, status, "a fully configured prod deployment must start");
        Path rendered = containerRoot.resolve("config/security.properties");
        String content = Files.readString(rendered, StandardCharsets.UTF_8);
        assertTrue(content.contains("login.secret_key_string=" + LONG_KEY),
                "the forgot-password key must reach the rendered configuration");
        assertTrue(content.contains("security.token.key=" + LONG_KEY),
                "the JWT signing key must reach the rendered configuration");
        assertEquals(packagedBefore, Files.readString(containerRoot.resolve(
                        "framework/security/config/security.properties"), StandardCharsets.UTF_8),
                "the packaged copy must never be written to: a secret there would outlive the container");
        assertEquals("rw-------", ownerOnly(rendered),
                "a file holding signing keys must be readable by the ofbiz user alone");
    }

    /**
     * No secret is written to the container log, even with shell tracing on for the whole run - which is
     * what "docker logs" and every collector behind it would keep for ever.
     *
     * @throws Exception if the entry point cannot be run
     */
    @Test
    public void noSecretIsEverWrittenToTheContainerLog() throws Exception {
        requireShellAvailable();
        Map<String, String> environment = prodDatabaseSecrets();
        environment.put("OFBIZ_POSTGRES_SSLMODE", "require");

        int status = runEntryPoint(environment, "ofbiz_setup_env", "create_ofbiz_runtime_directories", "apply_configuration", "configure_database");

        assertEquals(0, status, "the configured deployment must start");
        for (String secret : List.of(ADMIN_PASSWORD, ADMIN_KEY, LONG_KEY, "Un1que-Db-Passw0rd-Ofbiz",
                "Un1que-Db-Passw0rd-Olap", "Un1que-Db-Passw0rd-Tenant")) {
            assertFalse(output().contains(secret), "a secret reached the container log");
        }
    }

    /**
     * The prod profile refuses a managed database whose passwords were not supplied. The values the
     * datasource definitions carry are local-development placeholders published in this repository, so
     * starting with them would be starting with a public password.
     *
     * @throws Exception if the entry point cannot be run
     */
    @Test
    public void theProdProfileRefusesAManagedDatabaseWithNoPasswords() throws Exception {
        requireShellAvailable();
        Map<String, String> environment = new LinkedHashMap<>(prodSecrets());
        environment.put("OFBIZ_POSTGRES_HOST", MANAGED_HOST);

        int status = runEntryPoint(environment, "ofbiz_setup_env");

        assertNotEquals(0, status, "a prod database with no password must be refused");
        assertTrue(output().contains("OFBIZ_POSTGRES_OFBIZ_PASSWORD"),
                "the refusal must name the credential that is missing: " + output());
    }

    /**
     * The prod profile's own TLS default is enforced rather than merely documented: it verifies the server
     * certificate, and pgJDBC does not consult the JVM trust store, so a start with no certificate
     * authority named would silently be a start that cannot verify anything.
     *
     * @throws Exception if the entry point cannot be run
     */
    @Test
    public void verifyingTlsWithoutACertificateAuthorityIsRefused() throws Exception {
        requireShellAvailable();
        Map<String, String> environment = prodDatabaseSecrets();

        int status = runEntryPoint(environment, "ofbiz_setup_env");

        assertNotEquals(0, status, "verify-full with no CA file must be refused");
        assertTrue(output().contains("OFBIZ_POSTGRES_SSLROOTCERT"),
                "the refusal must name the variable that is missing: " + output());
    }

    /**
     * With a readable certificate authority supplied, the verifying TLS mode reaches every managed
     * datasource's connection URI - so a rendered deployment encrypts and authenticates by default.
     *
     * @throws Exception if the entry point cannot be run
     */
    @Test
    public void aSuppliedCertificateAuthorityReachesEveryManagedConnection() throws Exception {
        requireShellAvailable();
        Path authority = containerRoot.resolve("ca.pem");
        Files.writeString(authority, "-----BEGIN CERTIFICATE-----\n", StandardCharsets.UTF_8);
        Map<String, String> environment = prodDatabaseSecrets();
        environment.put("OFBIZ_POSTGRES_SSLROOTCERT", authority.toString());

        int status = runEntryPoint(environment, "ofbiz_setup_env", "create_ofbiz_runtime_directories",
                "configure_database");

        assertEquals(0, status, "a verifying deployment with a CA file must start");
        Document rendered = parse(renderedConfiguration());
        NodeList jdbc = rendered.getElementsByTagName("inline-jdbc");
        int verified = 0;
        for (int index = 0; index < jdbc.getLength(); index++) {
            String uri = ((Element) jdbc.item(index)).getAttribute("jdbc-uri");
            if (uri.startsWith("jdbc:postgresql:")) {
                assertTrue(uri.contains("sslmode=verify-full"),
                        "every managed connection must verify the server: " + uri);
                assertTrue(uri.contains("sslrootcert=" + authority),
                        "every managed connection must name the certificate authority: " + uri);
                verified++;
            }
        }
        assertEquals(MANAGED_DATASOURCES.size(), verified,
                "every managed datasource must carry the transport-security settings");
    }

    /**
     * No managed PostgreSQL pool validates with a QUERY, in either file, while all three keep idle validation.
     *
     * <p>DBCP validates with the configured {@code pool-jdbc-test-stmt} when there is one and with JDBC
     * {@code Connection.isValid} when there is not. Only the second is safe on this pool: OFBiz returns managed
     * connections with {@code autoCommitOnReturn} and {@code rollbackOnReturn} both false, so a SELECT issued by
     * the eviction thread opens a transaction that passivation never ends - the backend then sits idle in
     * transaction holding {@code backend_xmin}, and so holding back vacuum, until a later borrower finishes it.
     * The embedded H2 datasources keep their own query, because they are single-node development and test
     * datasources and H2 has no equivalent behaviour; asserting that too is what keeps this a targeted change.
     *
     * @throws Exception if either committed file cannot be parsed
     */
    @Test
    public void theManagedPoolsValidateWithoutOpeningATransaction() throws Exception {
        for (String file : List.of("framework/entity/config/entityengine.xml", TEMPLATE)) {
            Document committed = parse(repository().resolve(file));
            for (String name : MANAGED_DATASOURCES) {
                Element pool = inlineJdbc(datasource(committed, name));
                assertEquals("", pool.getAttribute("pool-jdbc-test-stmt"),
                        name + " in " + file + " must configure NO validation query, so DBCP validates with"
                                + " Connection.isValid and leaves no backend idle in transaction");
                assertEquals("true", pool.getAttribute("test-while-idle"),
                        name + " in " + file + " must still validate while idle, or a connection killed by a"
                                + " failover would be handed to a request");
            }
            for (String name : EMBEDDED_DATASOURCES) {
                assertEquals("SELECT 1", inlineJdbc(datasource(committed, name)).getAttribute("pool-jdbc-test-stmt"),
                        name + " in " + file + " must keep the validation query it has always had");
            }
        }
    }

    // =============================================================================================
    // Goal 5 - load-balancer readiness and multi-instance coherence.
    // =============================================================================================

    /**
     * The three Catalina settings are applied to the PRODUCTION container only; the test loader's own
     * container keeps the route the descriptor ships with, so gradlew testIntegration is unaffected.
     *
     * @throws Exception if the entry point cannot be run
     */
    @Test
    public void theLoadBalancerSettingsReachTheProductionContainerOnly() throws Exception {
        requireShellAvailable();

        int status = runEntryPoint(Map.of("OFBIZ_JVM_ROUTE", "instance-a",
                "OFBIZ_SSL_ACCELERATOR_PORT", "8080",
                "OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS", "true",
                "OFBIZ_COOKIE_DOMAIN", "example.com"),
                "ofbiz_setup_env", "create_ofbiz_runtime_directories", "apply_configuration");

        assertEquals(0, status, "the load-balancer settings must be applied");
        List<String> routes = catalinaValues("jvm-route");
        assertEquals(2, routes.size(), "the descriptor must still declare exactly two containers");
        assertEquals("instance-a", routes.get(0), "the production route must be the configured one");
        assertEquals("jvm1", routes.get(1), "the test loader's route must be left alone");
        assertEquals(List.of("8080"), catalinaValues("ssl-accelerator-port"),
                "the TLS-offload port must be applied");
        assertEquals(List.of("true"), catalinaValues("enable-cross-subdomain-sessions"),
                "cross-subdomain sessions must be applied");
        assertTrue(Files.readAllLines(containerRoot.resolve("config/url.properties"), StandardCharsets.UTF_8)
                        .contains("cookie.domain=example.com"),
                "the domain the session cookie is widened to must be rendered with the flag, because the valve"
                        + " reads it from url.properties and does nothing without it");
    }

    /**
     * Cross-subdomain sessions WITHOUT a domain to widen to is refused rather than started.
     *
     * <p>The valve takes the domain from {@code cookie.domain} alone - never from the request host, because
     * one webapp has one session-cookie configuration and a host-derived domain would pin whichever host
     * arrived first onto every later client. So the flag on its own installs a valve that has nothing to
     * apply: the operator would see the setting accepted, the sub-domains would each keep their own session,
     * and nothing would say why. Refusing at start up is the only outcome that cannot be mistaken for
     * working.
     *
     * @throws Exception if the entry point cannot be run
     */
    @Test
    public void crossSubdomainSessionsWithoutADomainToWidenToIsRefused() throws Exception {
        requireShellAvailable();

        int status = runEntryPoint(Map.of("OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS", "true"), "ofbiz_setup_env");

        assertNotEquals(0, status, "the flag without a domain must be refused");
        assertTrue(output().contains("OFBIZ_COOKIE_DOMAIN"),
                "the refusal must name the setting that is missing: " + output());
    }

    /**
     * A domain that is not a domain is refused before it reaches Tomcat, which would otherwise reject it per
     * request: RFC 6265 admits no scheme, no port, no path, no wildcard and no empty label.
     *
     * @throws Exception if the entry point cannot be run
     */
    @Test
    public void aCookieDomainThatBreaksTheGrammarIsRefused() throws Exception {
        requireShellAvailable();

        for (String hostile : List.of("https://example.com", "example.com:8443", "*.example.com",
                "example.com/path", "-example.com", "example..com")) {
            assertNotEquals(0, runEntryPoint(Map.of("OFBIZ_COOKIE_DOMAIN", hostile), "ofbiz_setup_env"),
                    "[" + hostile + "] is not a cookie domain and must be refused");
            assertTrue(output().contains("OFBIZ_COOKIE_DOMAIN"),
                    "the refusal must name it for [" + hostile + "]: " + output());
        }
    }

    /**
     * A TLS-offload port matching no connector this image listens on is refused: the valve would be
     * installed, mark nothing secure, and look configured while doing nothing.
     *
     * @throws Exception if the entry point cannot be run
     */
    @Test
    public void aTlsOffloadPortMatchingNoConnectorIsRefused() throws Exception {
        requireShellAvailable();

        int status = runEntryPoint(Map.of("OFBIZ_SSL_ACCELERATOR_PORT", "9999"), "ofbiz_setup_env");

        assertNotEquals(0, status, "a port no connector serves must be refused");
        assertTrue(output().contains("OFBIZ_SSL_ACCELERATOR_PORT"), "the refusal must name it: " + output());
    }

    /**
     * Withdrawing the settings REMOVES the overrides rendered on an earlier start rather than leaving them
     * behind, so going back to the shipped defaults really does go back to them.
     *
     * @throws Exception if the entry point cannot be run
     */
    @Test
    public void withdrawingASettingRestoresTheShippedDefault() throws Exception {
        requireShellAvailable();
        assertEquals(0, runEntryPoint(Map.of("OFBIZ_JVM_ROUTE", "instance-a",
                "OFBIZ_SSL_ACCELERATOR_PORT", "8080",
                "OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS", "true",
                "OFBIZ_COOKIE_DOMAIN", "example.com"),
                "ofbiz_setup_env", "create_ofbiz_runtime_directories", "apply_configuration"));

        int status = runEntryPoint(Map.of(), "ofbiz_setup_env", "create_ofbiz_runtime_directories", "apply_configuration");

        assertEquals(0, status, "withdrawing the settings must start cleanly");
        assertEquals("jvm1", catalinaValues("jvm-route").get(0), "the shipped route must be restored");
        assertEquals(List.of(""), catalinaValues("ssl-accelerator-port"),
                "an empty value is treated as absent, so no valve is installed");
        assertEquals(List.of("false"), catalinaValues("enable-cross-subdomain-sessions"),
                "cross-subdomain sessions must be off again");
        assertFalse(Files.exists(containerRoot.resolve("config/url.properties")),
                "the url.properties override rendered by the earlier start must be gone, or a withdrawn"
                        + " cookie domain would keep widening cookies with nothing declaring that it does");
    }

    /**
     * Distributed cache invalidation cannot be switched on without a transport to carry it. With the flag
     * on and no broker configured the invalidations would be undeliverable and each failure would mark the
     * calling transaction rollback-only, losing the write that triggered it - so the start is refused.
     *
     * @throws Exception if the entry point cannot be run
     */
    @Test
    public void cacheInvalidationWithoutATransportIsRefused() throws Exception {
        requireShellAvailable();

        int status = runEntryPoint(Map.of("OFBIZ_POSTGRES_HOST", MANAGED_HOST,
                "OFBIZ_DISTRIBUTED_CACHE_CLEAR", "true"), "ofbiz_setup_env");

        assertNotEquals(0, status, "cache invalidation with no transport must be refused");
        assertTrue(output().contains("OFBIZ_DISTRIBUTED_CACHE_CLEAR"),
                "the refusal must name the flag: " + output());
    }

    /**
     * With a broker configured, the JMS transport is rendered as class-path overrides and the delegators
     * are rendered with cache invalidation enabled - the whole Goal-5 coherence path in one assertion set.
     *
     * @throws Exception if the entry point cannot be run
     */
    @Test
    public void aConfiguredBrokerRendersTheTransportAndEnablesInvalidation() throws Exception {
        requireShellAvailable();
        Files.createDirectories(containerRoot.resolve("lib-extra"));
        Files.writeString(containerRoot.resolve("lib-extra/broker-client.jar"), "", StandardCharsets.UTF_8);

        int status = runEntryPoint(Map.of("OFBIZ_POSTGRES_HOST", MANAGED_HOST,
                "OFBIZ_DISTRIBUTED_CACHE_CLEAR", "true",
                "OFBIZ_JMS_PROVIDER_URL", "tcp://broker.test.invalid:61616",
                "OFBIZ_JMS_INITIAL_CONTEXT_FACTORY", "org.example.BrokerContextFactory"),
                "ofbiz_setup_env", "create_ofbiz_runtime_directories", "apply_configuration", "configure_database");

        assertEquals(0, status, "a configured broker must let the instance start");
        String serviceEngine = Files.readString(containerRoot.resolve("config/serviceengine.xml"),
                StandardCharsets.UTF_8);
        assertTrue(serviceEngine.contains("<jms-service name=\"serviceMessenger\""),
                "the transport the distributedClear services dispatch through must be rendered live");
        assertTrue(serviceEngine.contains("listen=\"true\""),
                "an instance must SUBSCRIBE as well as publish, or a fleet would ignore what it sends");
        assertTrue(Files.readString(containerRoot.resolve("config/jndiservers.xml"), StandardCharsets.UTF_8)
                        .contains("tcp://broker.test.invalid:61616"),
                "the broker the transport reaches must be rendered");
        Document rendered = parse(renderedConfiguration());
        for (String delegatorName : List.of("default", "default-no-eca")) {
            assertEquals("true", delegator(rendered, delegatorName)
                            .getAttribute("distributed-cache-clear-enabled"),
                    "the " + delegatorName + " delegator must have cache invalidation enabled");
        }
    }

    /**
     * The configuration is rendered on EVERY start, not only the first. A recreated container arrives with
     * a fresh component tree and a populated config volume; if a marker could vouch for work that had to be
     * redone, a "production" instance would boot without its load-balancer settings.
     *
     * @throws Exception if the entry point cannot be run
     */
    @Test
    public void theConfigurationIsRenderedOnEveryStartNotOnlyTheFirst() throws Exception {
        requireShellAvailable();
        Map<String, String> environment = Map.of("OFBIZ_JVM_ROUTE", "instance-a",
                "OFBIZ_SSL_ACCELERATOR_PORT", "8080");
        assertEquals(0, runEntryPoint(environment, "ofbiz_setup_env", "create_ofbiz_runtime_directories", "apply_configuration"));

        // Exactly what recreating the container does: the image's component tree is pristine again while
        // the config volume persists.
        assembleContainerRoot();

        assertEquals(0, runEntryPoint(environment, "ofbiz_setup_env", "create_ofbiz_runtime_directories", "apply_configuration"),
                "the second start must succeed");
        assertEquals("instance-a", catalinaValues("jvm-route").get(0),
                "a recreated container must be rendered again, not vouched for by a marker");
        assertEquals(List.of("8080"), catalinaValues("ssl-accelerator-port"),
                "a recreated container must keep its TLS-offload setting");
    }

    /**
     * A fleet member that keeps its entity caches to itself is flagged at start up. The default cannot be
     * flipped - an unconfigured container has to boot with no broker - so the only thing left is to say so,
     * and only when the environment has declared fleet membership by setting a route.
     *
     * @throws Exception if the entry point cannot be run
     */
    @Test
    public void aSuppliedRouteWithoutCacheInvalidationIsFlagged() throws Exception {
        requireShellAvailable();

        int status = runEntryPoint(Map.of("OFBIZ_JVM_ROUTE", "instance-a"), "ofbiz_setup_env");

        assertEquals(0, status, "the advisory must never refuse a start");
        assertTrue(output().contains("OFBIZ_JVM_ROUTE is set")
                        && output().contains("OFBIZ_DISTRIBUTED_CACHE_CLEAR"),
                "the advisory must name both variables: " + output());
    }

    /**
     * The zero-configuration container stays SILENT. The route carries a shipped default, so an advisory
     * that read the value rather than whether it was supplied would fire on every single start - including
     * the single-node development run the plan requires to be unchanged.
     *
     * @throws Exception if the entry point cannot be run
     */
    @Test
    public void anUnconfiguredContainerIsNotToldAboutCacheInvalidation() throws Exception {
        requireShellAvailable();

        assertEquals(0, runEntryPoint(Map.of(), "ofbiz_setup_env"), "an unconfigured start must succeed");

        assertFalse(output().contains("OFBIZ_JVM_ROUTE is set"),
                "a container that never declared fleet membership must not be warned: " + output());
    }

    // =============================================================================================
    // Content URL prefix.
    // =============================================================================================

    /**
     * A trailing separator is removed before the prefix reaches url.properties. OFBiz appends the separator
     * itself, so a prefix ending in '/' emits a double slash in EVERY content URL of every page - which most
     * origins normalise and answer, and a stricter one does not.
     *
     * @throws Exception if the entry point cannot be run
     */
    @Test
    public void aTrailingSeparatorOnTheContentPrefixIsRemovedBeforeItIsRendered() throws Exception {
        requireShellAvailable();

        int status = runEntryPoint(Map.of("OFBIZ_CONTENT_URL_PREFIX", "https://cdn.example.test//"),
                "ofbiz_setup_env", "create_ofbiz_runtime_directories", "apply_configuration");

        assertEquals(0, status, "a trailing separator must be normalised, not refused");
        List<String> rendered = Files.readAllLines(containerRoot.resolve("config/url.properties"),
                StandardCharsets.UTF_8);
        assertTrue(rendered.contains("content.url.prefix.secure=https://cdn.example.test"),
                "the rendered secure prefix must carry no trailing separator: " + rendered);
        assertTrue(rendered.contains("content.url.prefix.standard=https://cdn.example.test"),
                "the rendered standard prefix must carry no trailing separator: " + rendered);
        assertTrue(output().contains("OFBIZ_CONTENT_URL_PREFIX ended with"),
                "a rendered value that differs from the supplied one must never be silent: " + output());
    }

    /**
     * A prefix that normalises away is refused rather than rendered empty, because an empty prefix silently
     * reinstates the relative URLs the committed url.properties emits - which looks like it worked.
     *
     * @throws Exception if the entry point cannot be run
     */
    @Test
    public void aContentPrefixOfNothingButSeparatorsIsRefused() throws Exception {
        requireShellAvailable();

        int status = runEntryPoint(Map.of("OFBIZ_CONTENT_URL_PREFIX", "https:///"), "ofbiz_setup_env");

        assertNotEquals(0, status, "a prefix that normalises away must be refused");
        assertTrue(output().contains("OFBIZ_CONTENT_URL_PREFIX"), "the refusal must name it: " + output());
    }

    // =============================================================================================
    // Database portability.
    // =============================================================================================

    /**
     * Every dialect the committed configuration offers is still there. PostgreSQL becoming the deployed
     * default must not remove a datasource definition or a field-type mapping, because that is what
     * portability to another database consists of.
     *
     * @throws Exception if the committed configuration cannot be parsed
     */
    @Test
    public void everyDialectRemainsSelectableInTheCommittedConfiguration() throws Exception {
        Document committed = parse(repository().resolve("framework/entity/config/entityengine.xml"));

        List<String> datasources = new ArrayList<>();
        NodeList declared = committed.getElementsByTagName("datasource");
        for (int index = 0; index < declared.getLength(); index++) {
            datasources.add(((Element) declared.item(index)).getAttribute("name"));
        }
        assertTrue(datasources.containsAll(EMBEDDED_DATASOURCES), "the embedded datasources must remain");
        assertTrue(datasources.containsAll(MANAGED_DATASOURCES), "the managed datasources must remain");
        // The embedded default here is H2, not Derby: any reference to Derby is historical.
        for (String portable : List.of("localmysql", "localmysqlolap", "localmysqltenant", "localoracle",
                "localmssql", "localsybase", "localfirebird", "localhsql", "DB2")) {
            assertTrue(datasources.contains(portable),
                    "the " + portable + " datasource must remain selectable: " + datasources);
        }
        assertTrue(datasources.size() >= 23,
                "no datasource definition may be dropped; found " + datasources.size());
        assertTrue(committed.getElementsByTagName("field-type").getLength() >= 12,
                "no per-dialect field-type mapping may be dropped");
    }

    /**
     * A complete, valid set of prod-profile secrets, for a test to vary one of.
     *
     * @return the environment a fully configured prod deployment would be given
     */
    private static Map<String, String> prodSecrets() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("OFBIZ_PROFILE", "prod");
        environment.put("OFBIZ_ADMIN_PASSWORD", ADMIN_PASSWORD);
        environment.put("OFBIZ_ADMIN_KEY", ADMIN_KEY);
        environment.put("OFBIZ_LOGIN_SECRET_KEY", LONG_KEY);
        environment.put("OFBIZ_JWT_TOKEN_KEY", LONG_KEY);
        return environment;
    }

    /**
     * A complete, valid set of prod-profile secrets INCLUDING the managed-database credentials.
     *
     * <p>The prod profile requires a password for each of the three entity groups whenever a managed
     * database is configured, because the values the definitions carry for local development are public.
     *
     * @return the environment a fully configured prod deployment with a database would be given
     */
    private static Map<String, String> prodDatabaseSecrets() {
        Map<String, String> environment = new LinkedHashMap<>(prodSecrets());
        environment.put("OFBIZ_POSTGRES_HOST", MANAGED_HOST);
        environment.put("OFBIZ_POSTGRES_OFBIZ_PASSWORD", "Un1que-Db-Passw0rd-Ofbiz");
        environment.put("OFBIZ_POSTGRES_OLAP_PASSWORD", "Un1que-Db-Passw0rd-Olap");
        environment.put("OFBIZ_POSTGRES_TENANT_PASSWORD", "Un1que-Db-Passw0rd-Tenant");
        return environment;
    }

    /**
     * Reads one credential out of the entry point's own denylist of values this repository publishes.
     *
     * <p>Taken from the script rather than copied here, so that the test cannot drift from the list it is
     * asserting on: adding a credential to the denylist keeps this test meaningful automatically.
     *
     * @return a credential the entry point must refuse in the prod profile
     * @throws IOException if the entry point cannot be read
     */
    private static String publishedExampleCredential() throws IOException {
        String declaration = Files.readAllLines(repository().resolve(ENTRY_POINT), StandardCharsets.UTF_8).stream()
                .filter(line -> line.startsWith("PUBLISHED_CREDENTIALS="))
                .findFirst()
                .orElseThrow(() -> new IOException("the entry point must declare PUBLISHED_CREDENTIALS"));
        String values = declaration.substring(declaration.indexOf('\'') + 1, declaration.lastIndexOf('\''));
        assertFalse(values.isBlank(), "the published-credential denylist must not be empty");
        return values.split(" ")[0];
    }

    /**
     * Answers the values of one Catalina property, in document order, from the patched descriptor.
     *
     * @param property the property name
     * @return its values, one per declaration
     * @throws IOException if the descriptor cannot be read
     */
    private List<String> catalinaValues(String property) throws IOException {
        List<String> values = new ArrayList<>();
        String marker = "<property name=\"" + property + "\" value=\"";
        for (String line : Files.readAllLines(containerRoot.resolve("framework/catalina/ofbiz-component.xml"),
                StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            // Commented declarations are skipped: the descriptor ships several as documentation.
            if (trimmed.startsWith(marker)) {
                values.add(trimmed.substring(marker.length(), trimmed.indexOf('"', marker.length())));
            }
        }
        return values;
    }

    /**
     * Answers a file's POSIX permissions as a string, or the expected owner-only value where the
     * filesystem does not support them.
     *
     * @param file the file to inspect
     * @return the permission string, such as {@code rw-------}
     * @throws IOException if the file cannot be inspected
     */
    private static String ownerOnly(Path file) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix == null) {
            return "rw-------";
        }
        return PosixFilePermissions.toString(posix.readAttributes().permissions());
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
        // The stub STANDS IN FOR THE ENGINE'S OUTPUT as well as for its exit status, because the schema
        // initialisation is judged on what the engine LOGGED and not only on how it exited: the data
        // loader returns 0 whether or not a datasource could be reached, so the entry point counts the
        // per-group checks the Entity Engine reports and scans for the failure lines DatabaseUtil emits.
        // STUB_OFBIZ_CHECKS says how many groups reported a check - three is a complete run - and
        // STUB_OFBIZ_FAILURE injects one of those failure lines, which is what a wrong credential or a
        // DML-only role produces in a real run.
        Files.writeString(stub, "#!/bin/sh\n"
                + "echo \"$*\" >> \"$(dirname \"$0\")/../invocations\"\n"
                + "checks=${STUB_OFBIZ_CHECKS:-0}\n"
                + "while [ \"$checks\" -gt 0 ]; do\n"
                + "  echo 'Doing database check as requested in entityengine.xml with addMissing=true'\n"
                + "  checks=$((checks - 1))\n"
                + "done\n"
                + "if [ -n \"${STUB_OFBIZ_FAILURE:-}\" ]; then echo \"$STUB_OFBIZ_FAILURE\"; fi\n"
                + "exit \"${STUB_OFBIZ_EXIT:-0}\"\n", StandardCharsets.UTF_8);
        assertTrue(stub.toFile().setExecutable(true), "the stub launcher must be executable");
        stubExitStatus(0);
        stubGroupChecks(ENTITY_GROUPS.size());
        stubFailureLine("");
    }

    private void stubExitStatus(int status) throws IOException {
        Files.writeString(containerRoot.resolve("stub-exit"), Integer.toString(status), StandardCharsets.UTF_8);
    }

    private void stubGroupChecks(int groups) throws IOException {
        Files.writeString(containerRoot.resolve("stub-checks"), Integer.toString(groups), StandardCharsets.UTF_8);
    }

    private void stubFailureLine(String line) throws IOException {
        Files.writeString(containerRoot.resolve("stub-failure"), line, StandardCharsets.UTF_8);
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
        builder.environment().put("STUB_OFBIZ_CHECKS",
                Files.readString(containerRoot.resolve("stub-checks"), StandardCharsets.UTF_8).trim());
        builder.environment().put("STUB_OFBIZ_FAILURE",
                Files.readString(containerRoot.resolve("stub-failure"), StandardCharsets.UTF_8));
        builder.environment().putAll(environment);
        builder.redirectErrorStream(true);
        builder.redirectOutput(containerRoot.resolve("output").toFile());
        Process shell = builder.start();
        assertTrue(shell.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "the entry point must finish within " + SHELL_TIMEOUT_SECONDS + " seconds");
        return shell.exitValue();
    }

    /**
     * Requires the shell the entry point needs, and FAILS when it is missing.
     *
     * <p>A precondition, not an assumption. An assumption would let these tests be silently SKIPPED on a
     * host that cannot run them while the {@code test} task stayed green - so the gate they exist to be
     * would report success having verified nothing. If the tools are genuinely absent the right outcome is
     * a red build that names what to install.
     *
     * <p>Only what the exercised paths actually use is required: {@code bash}, GNU {@code sed} (the
     * {@code --quiet} long option distinguishes it from the BSD one) and {@code sha256sum}. {@code xsltproc}
     * is deliberately NOT required: the entry point invokes it at exactly one place - disabling components
     * on a first run - which no test here reaches, so requiring it would fail or skip these tests for a
     * tool none of them needs.
     */
    private void requireShellAvailable() {
        boolean available;
        String detail;
        try {
            ProcessBuilder builder = new ProcessBuilder("bash", "-c",
                    "command -v sed sha256sum >/dev/null && sed --quiet '' /dev/null"
                            + " && printf x | sha256sum >/dev/null");
            builder.redirectErrorStream(true);
            builder.redirectOutput(containerRoot.resolve("probe").toFile());
            Process probe = builder.start();
            available = probe.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS) && probe.exitValue() == 0;
            detail = "the probe exited " + (probe.isAlive() ? "not at all" : String.valueOf(probe.exitValue()));
        } catch (IOException | InterruptedException unsupported) {
            available = false;
            detail = unsupported.getClass().getSimpleName() + ": " + unsupported.getMessage();
        }
        assertTrue(available, "bash with GNU sed and sha256sum is required to verify the schema-init gate;"
                + " these tests must not be skipped, because the gate would then pass without verifying"
                + " anything (" + detail + ")");
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

    /**
     * Answers a datasource's inline-jdbc element.
     *
     * @param datasource the datasource element
     * @return its inline-jdbc child
     */
    private static Element inlineJdbc(Element datasource) {
        NodeList declared = datasource.getElementsByTagName("inline-jdbc");
        if (declared.getLength() == 0) {
            throw new AssertionError("the datasource named " + datasource.getAttribute("name")
                    + " declares no inline-jdbc");
        }
        return (Element) declared.item(0);
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

    /**
     * Pairs the three entity groups with the datasources they are expected to resolve to.
     *
     * @param datasources the expected datasource of each entity group, in the order the groups are declared
     * @return the expected group-to-datasource bindings
     */
    private static Map<String, String> groups(List<String> datasources) {
        // Asserted rather than left to fail on the index: zipping two lists of different lengths would
        // throw IndexOutOfBoundsException from inside a helper, which says nothing about what diverged.
        assertEquals(ENTITY_GROUPS.size(), datasources.size(),
                "one expected datasource per entity group is required: " + ENTITY_GROUPS + " against "
                        + datasources);
        Map<String, String> expected = new LinkedHashMap<>();
        for (int index = 0; index < ENTITY_GROUPS.size(); index++) {
            expected.put(ENTITY_GROUPS.get(index), datasources.get(index));
        }
        return expected;
    }
}
