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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.ofbiz.entity.config.model.Datasource;
import org.apache.ofbiz.entity.config.model.DelegatorElement;
import org.apache.ofbiz.entity.config.model.EntityConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guards the schema-lifecycle and multi-instance posture that makes this suite safe to run as a
 * stateless, load-balanced fleet.
 *
 * <p>Two configuration objectives are covered:</p>
 *
 * <ul>
 * <li><b>Objective 4 - gated single schema initialization.</b> The managed-RDBMS datasources
 * ({@code localpostgres}, {@code localpostgresolap}, {@code localpostgrestenant}) must resolve to a
 * <i>run mode</i> that issues no start up DDL, so a serving instance needs no DDL privilege and a
 * scaled-out fleet cannot race on schema changes. Schema changes are applied exclusively by a
 * separate one-shot init execution, for which the container entry point renders the very same two
 * attributes as {@code true}. Both halves are asserted here - the run mode on the committed model, the
 * init mode by executing the entry point - and so is the one-shot execution as a whole, including the
 * {@code readers=none} load, the verdicts that judge it and the restoration of the run mode before the
 * execution exits. No committed constant is used as a stand-in for the init mode: a constant cannot
 * fail when the machinery that would produce it is deleted, so crediting one as init-mode coverage
 * would report protection that does not exist.</li>
 * <li><b>Objective 5 - multi-instance coherence.</b> Distributed cache invalidation is
 * configuration-driven and must remain disabled in the committed configuration, so an unconfigured
 * checkout keeps the pre-existing single-node cache behaviour exactly.</li>
 * </ul>
 *
 * <p>Just as importantly, these tests pin the <i>backward-compatible local run</i>: the
 * {@code test} delegator stays bound to H2 so {@code gradlew loadAll} and {@code gradlew
 * testIntegration} do not target the managed datasources, and the embedded H2 datasources keep both
 * start up DDL flags enabled so a bare checkout still self-provisions. Those embedded flags are pinned
 * on both artifacts, because they can regress on either one independently:
 * {@link #embeddedDatasourcesKeepTheirStartupDdlInTheCommittedConfiguration()} reads the committed
 * model, which is what a bare checkout and the unit and integration tiers run on, and
 * {@link #schemaModeChangesReRenderTheManagedDdlFlagsOnAReusedStateVolume(Path)} reads the file the
 * deployed profile actually runs on, which is generated at every container start and therefore cannot
 * be reviewed once and trusted afterwards.</p>
 *
 * <p>PostgreSQL becomes the default only for the deployed profile, which the container entry point
 * renders from {@code docker/templates/postgres-entityengine.xml} when the database environment
 * variables are present - never in the committed source. No assertion here may therefore expect the
 * {@code default} delegator to reference a managed datasource.</p>
 *
 * <p>What each kind of assertion observes:</p>
 *
 * <ul>
 * <li><b>Configuration assertions</b> read the <em>parsed</em> entity-engine configuration model
 * through {@link EntityConfig}, which is the same model the running engine consumes, so what they
 * pin is the behaviour the engine will exhibit rather than the text of a file.</li>
 * <li><b>Lifecycle assertions</b> execute one of {@code docker/docker-entrypoint.sh}'s functions
 * against a sandbox that stands in for the container's {@code /ofbiz} home, with the data loader
 * replaced by a stub. What they inspect is the artefact the execution rendered, the order in which
 * its steps ran, how the stub's output was handled, and whether the serving command was reached.
 * They execute no Entity Engine DDL and connect to no PostgreSQL server, so a rendered attribute
 * pair is evidence of what a deployment would read and not of a schema that exists.</li>
 * <li><b>Text assertions</b> read the script without executing it, because what is being pinned is
 * the literal an author would edit in the file they would edit it in.</li>
 * </ul>
 *
 * <p>The whole class stays hermetic: it opens no database connection, performs no network access,
 * writes nothing of its own outside the per-test temporary directory JUnit supplies and deletes,
 * touches no file of the repository other than by reading it, and mutates no engine state. The one
 * exception is not this class's to make: the entry point creates its own short-lived substitution
 * script with {@code mktemp} under the system temporary directory and deletes it itself. The schema
 * location {@link EntityConfig} resolves is remapped to the local {@code entity-config.xsd} the
 * build places on {@code sourceSets.main.resources} rather than fetched, so the class runs offline.
 * An assertion that needs a shell is skipped rather than failed where none exists. Schema truth
 * remains the entity model: no migration framework, schema-version table or hand-written DDL is
 * involved.</p>
 */
public final class SchemaInitGatingTests {

    /** The value {@code ofbiz.home} held before this class overwrote it, {@code null} if it held none. */
    private String ofbizHomeSnapshot;
    /**
     * The container entry point, relative to the repository root. It is the file that reads the datasource
     * signatures these tests pin.
     *
     * <p>The methods below read it as TEXT on purpose: the DDL posture this class asserts against the parsed
     * configuration model is only worth as much as the container flow that keeps a serving instance pointed at a
     * run-mode configuration, and that flow lives in shell rather than in anything a parser can be pointed at.</p>
     */
    private static final String ENTRY_POINT = "docker/docker-entrypoint.sh";

    /** Path of the class that logs the start up database check the one-shot init mode depends on. */
    private static final String DELEGATOR_SOURCE =
            "framework/entity/src/main/java/org/apache/ofbiz/entity/GenericDelegator.java";

    /** Path of the class that logs the abort messages the one-shot init mode must refuse. */
    private static final String DATABASE_UTIL_SOURCE =
            "framework/entity/src/main/java/org/apache/ofbiz/entity/jdbc/DatabaseUtil.java";

    /** How far from the logged literal the emitting call and the concatenated flag must stay. */
    private static final int EMISSION_CONTEXT = 160;

    /** How long a sourced entry-point verdict may take before it is treated as hung. */
    private static final int VERDICT_TIMEOUT_SECONDS = 120;

    /**
     * How many messages ending {@code ", aborting."} the entity engine emits today.
     *
     * <p>The reverse census below requires the engine source to still yield at least this many, which is
     * what stops the census becoming vacuous. A message <em>added</em> upstream is caught by the coverage
     * loop, so only a shrink trips this floor - and a shrink is exactly the dangerous drift: it means one
     * of the entry point's abort signatures now names text the engine no longer logs, so whatever replaced
     * it is uncovered.</p>
     */
    private static final int ENGINE_ABORT_MESSAGES = 3;

    /** How many {@code "Could not create/add ..."} DDL-failure messages the entity engine emits today. */
    private static final int ENGINE_DDL_FAILURE_MESSAGES = 7;
    /** The deployed-profile template the entry point renders, at its path in the repository. */
    private static final String POSTGRES_TEMPLATE = "docker/templates/postgres-entityengine.xml";
    /** Where the image puts that template inside {@code /ofbiz}, and therefore where the sandbox puts it. */
    private static final String TEMPLATE_IN_HOME = "templates/postgres-entityengine.xml";
    /**
     * What the entry point renders, relative to the OFBiz home. {@code /ofbiz/config} precedes
     * {@code ofbiz.jar} on the runtime class path, so this file is the configuration the engine reads.
     */
    private static final String RENDERED_CONFIGURATION = "config/entityengine.xml";

    /** Stands for "do not write this attribute at all", which is a distinct posture from writing it false. */
    private static final String ABSENT_ATTRIBUTE = "absent";

    /** Printed after the serving-mode DDL gate returns, so an allowed start can be told from a refused one. */
    private static final String GATE_ACCEPTED = "SERVING-DDL-POSTURE-ACCEPTED";
    /** The three managed-RDBMS datasources the deployed profile binds the frozen entity groups to. */
    private static final List<String> MANAGED_DATASOURCES =
            List.of("localpostgres", "localpostgresolap", "localpostgrestenant");
    /** The embedded datasources, which stay on start up DDL because H2 is single-node dev and test only. */
    private static final List<String> EMBEDDED_DATASOURCES = List.of("localh2", "localh2olap", "localh2tenant");
    /**
     * The complete residual-token grammar: any at-sign delimited run of upper-case letters, digits and
     * underscores. This is character for character the expression the entry point's own post-render
     * guard rejects a rendered configuration on, and the same character set the template token
     * inventory is taken with, so closure is asserted here against exactly the grammar that is enforced
     * at run time. Digits are part of it deliberately — a pattern that omitted them would silently
     * ignore every token whose name contains one and so could not assert closure at all.
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("@[A-Z0-9_]+@");
    /** The field the mode-aware database state marker records the resolved schema mode under. */
    private static final String SCHEMA_MODE_FIELD = "schema-init=";
    /** The mode-aware database state marker, relative to the container state directory. */
    private static final String STATE_MARKER = "db_config_applied";
    /** The load-skipping markers a previous container leaves in the state directory. */
    private static final List<String> LOAD_MARKERS = List.of("data_loaded", "admin_loaded");
    /** What the entry point reports when a reused state volume turns from serving into initialising. */
    private static final String RUN_TO_INIT_TRANSITION =
            "previously ran with startup DDL disabled and is now an initialisation job";
    /**
     * What it reports for the reverse transition. A successful initialisation reaches this itself, before it
     * exits, so the message may not claim the container has become a serving instance.
     */
    private static final String INIT_TO_RUN_TRANSITION =
            "previously rendered with startup DDL enabled for an initialisation job and have now been"
            + " re-rendered with it disabled";
    /** What the init job prints once it has handed the configuration volume back in serving mode. */
    private static final String SERVING_MODE_RESTORED =
            "so the configuration volume this job leaves behind is safe for a serving instance to read";
    /** The entity group only {@code plugins/bi} declares, and so the one a deployment can legitimately lack. */
    private static final String OLAP_ENTITY_GROUP = "org.apache.ofbiz.olap";
    /** The component that declares {@link #OLAP_ENTITY_GROUP}, left out to model a BI-disabled deployment. */
    private static final String BUSINESS_INTELLIGENCE_COMPONENT = "plugins/bi";
    /** Where a component declares which entity group each of its entities belongs to. */
    private static final String ENTITY_GROUP_DESCRIPTOR = "entitydef/entitygroup.xml";
    /** The component roots the image lays down, and therefore where entity-group descriptors are found. */
    private static final List<String> COMPONENT_ROOTS = List.of("framework", "applications", "plugins", "themes");
    /** How deep below a component root an entity-group descriptor lies: {@code <component>/entitydef/<file>}. */
    private static final int COMPONENT_DESCRIPTOR_DEPTH = 3;
    /** What makes a directory a component the engine loads, and therefore whose entity groups it reads. */
    private static final String COMPONENT_DESCRIPTOR = "ofbiz-component.xml";
    /** The flag whose acceptance message the transport validation prints, used to locate that message. */
    private static final String CACHE_CLEAR_ENABLED = "OFBIZ_DISTRIBUTED_CACHE_CLEAR=true";
    /**
     * Phrasings that would describe the transport preconditions as proof of coherence. Held as data rather than as
     * separate assertions because the defect is the class and not any one wording: each of these has at some point
     * been the natural way to summarise a passing check, and none is true of what is actually checked.
     */
    private static final List<String> COHERENCE_OVERCLAIMS = List.of(
            "coherent",
            "transport to travel on",
            "invalidations will",
            "is propagat",
            "are propagat");
    /**
     * The group a delegator carrying no {@code default-group-name} defaults to. Both the schema of the
     * configuration and {@code DelegatorElement} apply this same literal, and neither the committed
     * configuration nor the rendered template states it, so it is the effective default of every delegator here.
     */
    private static final String DEFAULT_ENTITY_GROUP = "org.apache.ofbiz";
    /** Path of the container that performs the schema-only load, and upserts Component metadata while doing it. */
    private static final String DATA_LOAD_CONTAINER_SOURCE =
            "framework/entityext/src/main/java/org/apache/ofbiz/entityext/data/EntityDataLoadContainer.java";
    /**
     * Resolves the mode flags from the environment and configures the database, which is exactly the
     * pair of steps {@code _main} performs on a container start. {@code configure_database} reconciles
     * the state marker, renders the configuration and then refreshes the marker, so driving this pair is
     * what makes a mode <i>transition</i> observable.
     */
    private static final String RESOLVE_AND_CONFIGURE = "resolve_entity_engine_flags\nconfigure_database\n";
    /**
     * Structurally valid, deliberately fictitious managed-database settings. None of the passwords is
     * one of the published defaults the entry point refuses, and no value here reaches a network: the
     * renderer only substitutes them into a template and re-reads the result.
     */
    private static final Map<String, String> MANAGED_DATABASE_ENVIRONMENT = Map.ofEntries(
            Map.entry("OFBIZ_POSTGRES_HOST", "managed-db.invalid"),
            Map.entry("OFBIZ_POSTGRES_PORT", "6432"),
            Map.entry("OFBIZ_POSTGRES_OFBIZ_DB", "ofbizmain"),
            Map.entry("OFBIZ_POSTGRES_OFBIZ_USER", "ofbizmainuser"),
            Map.entry("OFBIZ_POSTGRES_OFBIZ_PASSWORD", "render-only-main-9Xq2"),
            Map.entry("OFBIZ_POSTGRES_OLAP_DB", "ofbizolap"),
            Map.entry("OFBIZ_POSTGRES_OLAP_USER", "ofbizolapuser"),
            Map.entry("OFBIZ_POSTGRES_OLAP_PASSWORD", "render-only-olap-4Kt7"),
            Map.entry("OFBIZ_POSTGRES_TENANT_DB", "ofbiztenant"),
            Map.entry("OFBIZ_POSTGRES_TENANT_USER", "ofbiztenantuser"),
            Map.entry("OFBIZ_POSTGRES_TENANT_PASSWORD", "render-only-tenant-8Bv3"));

    /**
     * Resolves {@code ofbiz.home} the way the other unit tests in this package do, so that local
     * resource and XSD lookup succeeds before the configuration model is touched, remembering
     * whatever the property held first.
     *
     * <p>{@link EntityConfig} builds its singleton in a static initializer, so every reference to
     * it below is deliberately confined to a test-method body: referencing it from a field or
     * static initializer of this class would load it before this method has run.</p>
     *
     * <p>That system property is the only piece of global state this class touches, and it is
     * snapshotted here and put back in {@link #restoreOfbizHome()} rather than left behind. The
     * whole unit tier shares one JVM, so a property this class overwrites outlives it and is
     * observable by every suite that runs afterwards in the same JVM. It happens to write the same
     * {@code user.dir} value several sibling suites also write, but a test may not rest on a
     * coincidence of values it does not control, and the suites that do need a different value —
     * {@code SecurityUtilTest} and the content-store provider suites — are exactly the ones that
     * would be affected if this class ran between one of their
     * assertions. Restoring symmetrically removes the question altogether. Nothing else global is
     * changed: no file is written, no connection is opened and no engine state is mutated.</p>
     */
    @BeforeEach
    public void initialize() {
        ofbizHomeSnapshot = System.getProperty("ofbiz.home");
        System.setProperty("ofbiz.home", System.getProperty("user.dir"));
    }

    /**
     * Puts {@code ofbiz.home} back exactly as it was found, clearing it when it was previously
     * unset, so that no suite running later in this shared JVM inherits a value from this one.
     */
    @AfterEach
    public void restoreOfbizHome() {
        if (ofbizHomeSnapshot == null) {
            System.clearProperty("ofbiz.home");
        } else {
            System.setProperty("ofbiz.home", ofbizHomeSnapshot);
        }
    }

    /**
     * AAP Objective 4, <b>run mode</b>: asserts the committed managed-RDBMS run-mode DDL flags resolve false.
     *
     * <p>Both committed managed-RDBMS run-mode attributes must resolve {@code false}. Because both flags resolve
     * to {@code false} on all three managed-RDBMS datasources, no instance attempts to check or
     * amend the schema while booting, so none of them needs a DDL privilege and none of them can
     * collide with a peer over a schema change.</p>
     *
     * <p>The <em>resolved</em> flags are asserted rather than the attribute text, and that matters
     * here in a way it does not elsewhere, because the two attributes are parsed asymmetrically:
     * {@code check-on-start} is read as {@code !"false".equals(value)} and so defaults to
     * <b>true</b> when absent, whereas {@code add-missing-on-start} is read as
     * {@code "true".equals(value)} and so defaults to <b>false</b>. Deleting {@code check-on-start}
     * from a managed datasource would therefore silently re-enable start up DDL on every serving
     * instance, and this method catches that removal directly.</p>
     *
     * <p>The committed configuration is the one asserted here;
     * {@link #schemaModeChangesReRenderTheManagedDdlFlagsOnAReusedStateVolume(Path)} asserts the same
     * posture on the file the deployed profile really reads, which is generated at every container
     * start and therefore cannot be reviewed once and trusted afterwards.</p>
     */
    @Test
    public void managedRdbmsRunModeHasDdlDisabled() {
        for (String datasourceName : MANAGED_DATASOURCES) {
            Datasource managed = EntityConfig.getDatasource(datasourceName);
            assertNotNull(managed, "the managed-RDBMS datasource " + datasourceName
                    + " must stay declared: the deployed profile's group-maps resolve to it");
            assertFalse(managed.getCheckOnStart(), datasourceName
                    + " must not check the schema on start up, so a serving instance issues no DDL");
            assertFalse(managed.getAddMissingOnStart(), datasourceName
                    + " must not add missing schema objects on start up: DDL belongs to the one-shot init only");
        }
    }

    /**
     * The other half of AAP Objective 4, and of the backward-compatible local run it must not break: the
     * embedded H2 datasources keep both start up DDL flags ENABLED in the committed configuration.
     *
     * <p>This is the assertion that stops the run-mode change above from being applied too widely. The
     * managed datasources issue no DDL because a fleet shares one database; an embedded H2 database is a
     * file belonging to the single JVM that opens it, is never part of a fleet, and is what makes a bare
     * checkout self-provision - {@code gradlew loadAll} and {@code gradlew testIntegration} both depend
     * on the engine applying the entity model to it on start up. Turning these flags off to match the
     * managed ones would leave a fresh checkout with no schema at all, which is exactly the regression
     * AAP 0.6.1 calls out when it requires the H2 development and test path to be preserved.</p>
     *
     * <p>The <em>resolved</em> flags are asserted, for the same asymmetry
     * {@link #managedRdbmsRunModeHasDdlDisabled()} describes: here it is {@code add-missing-on-start}
     * whose literal is load-bearing, since it resolves to {@code false} the moment the attribute is
     * absent or misspelled, and a schema check that may not add what it finds missing would leave a bare
     * checkout unprovisioned while the configuration still looked correct.</p>
     *
     * <p>The committed configuration is the one asserted here;
     * {@link #schemaModeChangesReRenderTheManagedDdlFlagsOnAReusedStateVolume(Path)} asserts the same
     * posture on every configuration the container renders, whichever mode it renders the managed
     * datasources in.</p>
     */
    @Test
    public void embeddedDatasourcesKeepTheirStartupDdlInTheCommittedConfiguration() {
        for (String datasourceName : EMBEDDED_DATASOURCES) {
            Datasource embedded = EntityConfig.getDatasource(datasourceName);
            assertNotNull(embedded, "the embedded datasource " + datasourceName + " must stay declared: the"
                    + " committed delegators and the test delegator resolve to it");
            assertTrue(embedded.getCheckOnStart(), datasourceName + " must keep checking the schema on start"
                    + " up: it is what makes a bare checkout self-provision, and H2 never joins a fleet");
            assertTrue(embedded.getAddMissingOnStart(), datasourceName + " must keep adding missing schema"
                    + " objects on start up, so gradlew loadAll and testIntegration need no schema step");
        }
    }

    /**
     * AAP Objective 4 end to end, on the artifact the deployment really reads: asserts the gate
     * <i>discriminates</i>, that it is re-evaluated on every start, and that a schema-mode change on a
     * reused state volume is detected rather than silently ignored.
     *
     * <p>Three starts are driven against one sandbox and one container state directory, through the
     * entry point's own {@code resolve_entity_engine_flags} and {@code configure_database} — the same
     * pair {@code _main} performs — so what is exercised is the whole path from the environment
     * variable to the rendered attribute. The middle start is the init job <i>in full</i>, because the
     * {@code false} → {@code true} → {@code false} transition happens INSIDE one init execution: the
     * flag renders the DDL-enabled configuration, and {@code restore_serving_mode_after_schema_init}
     * renders it back before that same execution exits. Driving the return as a separate start would
     * model a lifecycle this tree does not have and would leave the real restoration untested:</p>
     * <ol>
     * <li><b>unset</b>, which is what a deployment that never heard of the flag gets and therefore the
     * case that matters most: the managed datasources must render {@code false}, and the marker must
     * record that mode, because a mode that is not recorded cannot be seen to change;</li>
     * <li><b>{@code true}</b> on that same volume, run to its end: the managed datasources must render
     * {@code true} <i>while the initialisation is running</i> — asserted on a copy taken at that
     * moment, which is the only point at which the DDL-enabled configuration exists — and the volume
     * must be handed back rendering {@code false}, with the marker refreshed and both transitions
     * reported by the one execution. {@code RESOLVED_SCHEMA_INIT} must still be {@code true}
     * afterwards, because {@code _main} decides from that same flag whether to exit instead of serving.
     * The two load-skipping markers a previous container left behind must survive, because the host has
     * not changed and that data is still in that database;</li>
     * <li><b>unset</b> again, which is the redeploy that follows a successful init job: the run mode
     * must still be in place and <em>no</em> transition may be reported, because the init job restored
     * it itself. A report here would send an operator looking for a mode change that did not happen.</li>
     * </ol>
     *
     * <p>Because one renderer produces {@code true} or {@code false} on the same three datasources
     * according to one environment variable, neither result can be a constant. That is what
     * {@link #managedRdbmsRunModeHasDdlDisabled()} cannot show on its own, and it is what makes the
     * one-shot init execution the <em>only</em> thing that applies the entity-model DDL.</p>
     *
     * <p>What is asserted is the attribute <i>literal</i> rather than a parsed boolean, because
     * {@code Datasource} parses the two flags asymmetrically: {@code check-on-start} is read as
     * {@code !"false".equals(value)} and so resolves to <b>true</b> when the attribute is absent or
     * misspelled, whereas {@code add-missing-on-start} is read as {@code "true".equals(value)} and so
     * resolves to <b>false</b>. Only the exact token {@code true} enables both halves of the DDL, and
     * only the exact token {@code false} disables the first half. That is also why the template carries
     * a placeholder per attribute — {@code @CHECK_ON_START@} and {@code @ADD_MISSING_ON_START@} — and
     * why every leg additionally asserts the render left no placeholder of the complete token grammar
     * behind: a surviving {@code @CHECK_ON_START@} is not the string {@code "false"}, so it would
     * re-enable schema checking on a fleet instance while the template still looked correct.</p>
     *
     * @param tempDir a JUnit-managed sandbox; nothing is written outside it
     * @throws Exception if the entry point could not be executed or its output could not be read or
     *         parsed, any of which fails the test rather than being handled
     */
    @Test
    public void schemaModeChangesReRenderTheManagedDdlFlagsOnAReusedStateVolume(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the container entry point");

        // Non-vacuity: the template really does carry placeholders of the grammar the closure
        // assertions below use, so an empty result from them means substitution actually happened.
        String template = Files.readString(repositoryRoot().resolve(POSTGRES_TEMPLATE), StandardCharsets.UTF_8);
        assertTrue(PLACEHOLDER.matcher(template).find(), POSTGRES_TEMPLATE + " carries no placeholder of the"
                + " grammar " + PLACEHOLDER.pattern() + ", so asserting closure against it would prove nothing");

        Path home = prepareEntryPointHome(tempDir);
        Path stateDir = tempDir.resolve("container_state");

        String firstStart = configureDatabase(tempDir, home, stateDir, null);
        assertRenderedSchemaMode(home, "false", "with OFBIZ_SCHEMA_INIT unset");
        assertEquals("false", recordedSchemaMode(stateDir),
                "the state marker must record the resolved schema mode, or no later change can be detected");
        assertFalse(firstStart.contains("Schema mode changed"),
                "a first start has no previous mode to have changed from, output was:\n" + firstStart);

        // The markers a previous container leaves behind on the same volume. They are assertions about a
        // specific database, so a mode change against the same host must not discard them.
        for (String loadMarker : LOAD_MARKERS) {
            Files.createFile(stateDir.resolve(loadMarker));
        }

        Path midInitialisation = tempDir.resolve("rendered-mid-initialisation.xml");
        String initJob = initialiseAndRestoreServingMode(tempDir, home, stateDir, midInitialisation);
        assertSchemaModeOfRenderedFile(midInitialisation, "true", "while the initialisation was running");
        assertRenderedSchemaMode(home, "false", "once the initialisation had restored serving mode");
        assertEquals("false", recordedSchemaMode(stateDir),
                "the marker must record the mode the volume was handed back in, not the one it was asked for");
        assertTrue(initJob.contains(RUN_TO_INIT_TRANSITION),
                "the run-to-init transition must be reported on a reused volume, output was:\n" + initJob);
        assertTrue(initJob.contains(INIT_TO_RUN_TRANSITION), "the init job must report the return to run mode it"
                + " performs itself, output was:\n" + initJob);
        assertTrue(initJob.contains(SERVING_MODE_RESTORED), "the init job must say it left the configuration"
                + " volume safe for a serving instance, output was:\n" + initJob);
        assertTrue(initJob.contains("RESOLVED_SCHEMA_INIT=true"), "restoring serving mode must leave the requested"
                + " mode alone: _main decides from that same flag whether to exit instead of serving, so an init"
                + " job whose flag was cleared here would go on to serve traffic. Output was:\n" + initJob);
        for (String loadMarker : LOAD_MARKERS) {
            assertTrue(Files.exists(stateDir.resolve(loadMarker)), "the " + loadMarker + " marker must survive a"
                    + " schema-mode change against the same host: that database still holds the data");
        }

        String servingAgain = configureDatabase(tempDir, home, stateDir, null);
        assertRenderedSchemaMode(home, "false", "on the serving start that followed the initialisation");
        assertEquals("false", recordedSchemaMode(stateDir),
                "the state marker must still record the serving mode");
        assertFalse(servingAgain.contains("Schema mode changed"), "a serving start after a successful"
                + " initialisation has no transition to report, because that job handed the volume back in"
                + " serving mode itself, output was:\n" + servingAgain);
    }

    /**
     * AAP Objective 4, <b>helper postcondition</b>: the per-group evidence the one-shot init job's exit
     * status depends on accepts the default component set <i>and</i> a business-intelligence-disabled one,
     * against the PostgreSQL configuration the deployed profile really renders.
     *
     * <p>What is at stake here is a false REFUSAL, which on this path costs as much as a false acceptance:
     * the verdict is handed to {@code config_fatal}, so a postcondition that a correct run cannot satisfy
     * turns a completed initialisation into a failed job and blocks a deployment that has nothing wrong
     * with it.</p>
     *
     * <p>{@code GenericDelegator.initializeOneGenericHelper} is called once per group returned by
     * {@code ModelGroupReader.getGroupNames}, which is the delegator's own default group plus the groups
     * declared by the {@code entitydef/entitygroup.xml} of the components that were <b>loaded</b> — not the
     * groups the delegator maps. {@code org.apache.ofbiz.olap} is declared by exactly one component in
     * either repository, {@code plugins/bi}, so a deployment without that plugin initialises two of the
     * three mapped groups and has no third group of database objects to create. Confirmed by execution
     * against this checkout: {@code bin/ofbiz --load-data readers=none} logs three helper initialisations,
     * and the same command with {@code plugins/bi/ofbiz-component.xml} withdrawn logs two and still exits
     * {@code 0}.</p>
     *
     * <p>Both component sets are derived from the repository rather than written down here, and the group
     * maps are read from the <i>rendered</i> configuration rather than the committed one, so what is
     * exercised is the component and group resolution a deployment actually performs against PostgreSQL.
     * The difference between the two sets is asserted before either verdict is taken: were they equal, the
     * second case would be the first one twice and would add no coverage.</p>
     *
     * @param tempDir a JUnit-managed sandbox; nothing is written outside it
     * @throws Exception if the entry point could not be executed or its render could not be read, either of
     *         which fails the test rather than being handled
     */
    @Test
    public void theHelperPostconditionAcceptsTheDefaultAndABusinessIntelligenceDisabledComponentSet(
            @TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the container entry point");

        Path home = prepareEntryPointHome(tempDir);
        configureDatabase(tempDir, home, tempDir.resolve("container_state"), "true");
        assertRenderedSchemaMode(home, "true", "with OFBIZ_SCHEMA_INIT=true");

        Map<String, String> groupMaps = renderedDefaultDelegatorGroupMaps(home);
        assertEquals(MANAGED_DATASOURCES.size(), groupMaps.size(), "the rendered 'default' delegator must map"
                + " every frozen entity group, or this case would be judging a configuration no fleet runs");
        assertTrue(groupMaps.values().containsAll(MANAGED_DATASOURCES), "the rendered group-maps must resolve to"
                + " the managed datasources " + MANAGED_DATASOURCES + ", but resolve to " + groupMaps.values()
                + ": this case has to be taken against PostgreSQL, not against the embedded profile");

        Set<String> everyComponent = consideredEntityGroups(home, Set.of());
        Set<String> withoutBusinessIntelligence = consideredEntityGroups(home, Set.of(BUSINESS_INTELLIGENCE_COMPONENT));
        assumeTrue(everyComponent.contains(OLAP_ENTITY_GROUP), OLAP_ENTITY_GROUP + " is declared by no component"
                + " in this checkout, so the difference this case rests on cannot be constructed");
        assertFalse(withoutBusinessIntelligence.contains(OLAP_ENTITY_GROUP), "withdrawing "
                + BUSINESS_INTELLIGENCE_COMPONENT + " must leave " + OLAP_ENTITY_GROUP + " undeclared, otherwise"
                + " some other component declares it and the BI-disabled case is not the case it claims to be");

        assertEquals("", helperVerdict(tempDir, home, loaderLogFor(groupMaps, everyComponent)),
                "the default component set initialises every mapped group through the mapped datasource, which"
                        + " is the outcome this postcondition exists to accept");
        assertEquals("", helperVerdict(tempDir, home, loaderLogFor(groupMaps, withoutBusinessIntelligence)),
                "a deployment without " + BUSINESS_INTELLIGENCE_COMPONENT + " has no " + OLAP_ENTITY_GROUP
                        + " entity for the delegator to reach, so the absence of that group's helper line must"
                        + " not fail an initialisation that did everything there was to do");
    }

    /**
     * The same postcondition still <em>discriminates</em>: a group initialised against the wrong database, a
     * group the delegator refused, and a load that initialised nothing are each refused, and the refusal names
     * what went wrong.
     *
     * <p>This is the other half of the case above and cannot be separated from it. Accepting a mapped group
     * that produced no line is only correct because a group that produced the <i>wrong</i> line is still
     * refused; without these three cases, a postcondition that accepts everything would look identical to one
     * that accepts exactly what the engine reports.</p>
     *
     * <p>The diverted case is the one an aggregate check cannot see: every mapped group is initialised, the
     * count is right, and one of them went to the embedded database instead of the managed one — so the init
     * job would report a schema that the fleet's database does not have.</p>
     *
     * @param tempDir a JUnit-managed sandbox; nothing is written outside it
     * @throws Exception if the entry point could not be executed or its render could not be read
     */
    @Test
    public void theHelperPostconditionRefusesADivertedGroupARefusedGroupAndASilentLoad(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the container entry point");

        Path home = prepareEntryPointHome(tempDir);
        configureDatabase(tempDir, home, tempDir.resolve("container_state"), "true");
        Map<String, String> groupMaps = renderedDefaultDelegatorGroupMaps(home);
        Set<String> considered = consideredEntityGroups(home, Set.of());

        // 1. Every mapped group initialised, one of them against a database this configuration does not name.
        String divertedGroup = groupMaps.keySet().iterator().next();
        String elsewhere = EMBEDDED_DATASOURCES.get(0);
        Map<String, String> diverted = new LinkedHashMap<>(groupMaps);
        diverted.put(divertedGroup, elsewhere);
        String verdict = helperVerdict(tempDir, home, loaderLogFor(diverted, considered));
        assertTrue(verdict.contains(divertedGroup) && verdict.contains(elsewhere), "a group initialised through"
                + " a datasource the configuration does not map it to must be refused, and the refusal must name"
                + " both the group and the datasource that was used instead -- verdict was: " + verdict);

        // 2. A mapped group the delegator refused to associate, which is what a group-map the engine never
        // read looks like from outside: the configuration says one thing and the running delegator another.
        Map<String, String> withoutOlap = new LinkedHashMap<>(groupMaps);
        withoutOlap.remove(OLAP_ENTITY_GROUP);
        verdict = helperVerdict(tempDir, home,
                loaderLogFor(withoutOlap, considered) + refusalLine(OLAP_ENTITY_GROUP));
        assertTrue(verdict.contains(OLAP_ENTITY_GROUP), "a mapped group the delegator reports as not associated"
                + " must be refused, and the refusal must name it -- verdict was: " + verdict);

        // 3. A load that reached the end without initialising anything at all. The engine logs a per-helper
        // failure as a warning and leaves the exit status at 0, so silence here must not read as success.
        verdict = helperVerdict(tempDir, home, "Finished the data load with 0 rows changed.");
        assertFalse(verdict.isEmpty(), "a load whose log shows no helper initialisation at all must be refused:"
                + " nothing in it shows the schema was applied");
    }

    /**
     * The lifecycle claim the schema-only load is documented with: it loads no business reader data, and it is
     * <em>not</em> a run that writes nothing.
     *
     * <p>{@code EntityDataLoadContainer} upserts one {@code Component} row per loaded component before it
     * resolves any reader, unconditionally — so {@code readers=none} performs DML even though it reports
     * "Finished the data load with 0 rows changed", because that counter is the number of rows the
     * <i>readers</i> loaded. Confirmed by execution against this checkout: a {@code readers=none} run left 51
     * {@code Component} rows carrying its own timestamp. Documenting the run as "zero rows changed" would
     * therefore be wrong in a way that matters operationally, because the identity the init job connects as
     * needs INSERT and UPDATE on the group that holds {@code Component} — {@code org.apache.ofbiz.tenant} —
     * and not DDL rights alone.</p>
     *
     * <p>The engine is read as source rather than executed, because the write cannot be observed without a
     * database, and the fact being pinned is that the call is <em>unconditional and first</em>: it is asserted
     * at method-body indentation, which is what rules out its having been moved under a reader test, and
     * before the reader load, which is what makes it happen even when there is no reader. The entry point's
     * prose is asserted alongside it, because the defect this guards against was a comment that claimed the
     * opposite.</p>
     *
     * @throws Exception if the engine source, the group descriptor or the entry point cannot be read
     */
    @Test
    public void theSchemaOnlyLoadUpsertsComponentMetadataAndLoadsNoBusinessReaderData() throws Exception {
        String container = repositoryText(DATA_LOAD_CONTAINER_SOURCE);
        String upsert = "\n        createOrUpdateComponentEntities(baseDelegator, allComponents);\n";
        String readerLoad = "\n        loadData(delegator, baseDelegator, allComponents, helperInfo, loadDataProps);\n";
        assertTrue(container.contains(upsert), DATA_LOAD_CONTAINER_SOURCE + " no longer upserts the component"
                + " metadata as an unconditional statement of the load method, so the entry point's account of"
                + " what a readers=none run writes is out of date");
        assertTrue(container.contains(readerLoad), DATA_LOAD_CONTAINER_SOURCE + " no longer calls loadData the"
                + " way this census reads it, so the ordering below cannot be established");
        assertTrue(container.indexOf(upsert) < container.indexOf(readerLoad), "the component metadata must be"
                + " upserted BEFORE the reader load, which is what makes it happen when there is no reader");

        assertTrue(container.contains("makeValue(\"Component\")"), DATA_LOAD_CONTAINER_SOURCE + " no longer"
                + " writes the Component entity, so the privilege this run needs is no longer what is documented");
        String upsertBody = container.substring(container.indexOf("private static void createOrUpdateComponentEntities"));
        upsertBody = upsertBody.substring(0, upsertBody.indexOf("private static void dropDbConstraints"));
        assertTrue(upsertBody.contains(".create()") && upsertBody.contains(".store()"),
                "the component metadata must still be created when absent and stored when present");
        assertFalse(upsertBody.contains("totalRowsChanged"), "the component upsert must stay outside the row"
                + " counter the loader reports, which is why \"0 rows changed\" does not count it");

        assertTrue(repositoryText("framework/entity/" + ENTITY_GROUP_DESCRIPTOR)
                        .contains("<entity-group group=\"org.apache.ofbiz.tenant\" entity=\"Component\"/>"),
                "the Component entity must stay in the org.apache.ofbiz.tenant group: that is the group whose"
                        + " datasource the init job writes to, and the privilege the entry point documents");

        String entryPoint = repositoryText(ENTRY_POINT);
        assertFalse(entryPoint.contains("not one row is loaded"), ENTRY_POINT + " claims a readers=none run"
                + " loads not one row. It performs no reader load, but it does upsert the Component metadata,"
                + " so the run must be described as loading no business-reader data instead");
        assertTrue(entryPoint.contains("upserts one Component row per loaded component"), ENTRY_POINT + " must"
                + " state that the schema-only load upserts the component metadata, so an operator granting the"
                + " init identity its privileges is not told the run writes nothing");
    }

    /**
     * AAP validation gate "Test delegator on H2": asserts the frozen {@code test} delegator still
     * resolves all three immutable entity groups to the embedded H2 datasources.
     *
     * <p>{@code gradlew testIntegration} runs against this delegator, so it must never be
     * repointed at a managed database — that would make the integration tier require external
     * credentials and DDL privileges.</p>
     *
     * @throws GenericEntityConfException if the entity-engine configuration could not be loaded at
     *         all, which fails the test rather than being handled: a suite that cannot read the
     *         configuration cannot make any claim about it
     */
    @Test
    public void testDelegatorRemainsBoundToH2() throws GenericEntityConfException {
        DelegatorElement testDelegator = EntityConfig.getInstance().getDelegator("test");
        assertNotNull(testDelegator, "the test delegator name is frozen and must stay declared");
        assertEquals("localh2", testDelegator.getGroupDataSource("org.apache.ofbiz"),
                "the test delegator must keep the org.apache.ofbiz group on embedded H2");
        assertEquals("localh2olap", testDelegator.getGroupDataSource("org.apache.ofbiz.olap"),
                "the test delegator must keep the org.apache.ofbiz.olap group on embedded H2");
        assertEquals("localh2tenant", testDelegator.getGroupDataSource("org.apache.ofbiz.tenant"),
                "the test delegator must keep the org.apache.ofbiz.tenant group on embedded H2");
    }

    /**
     * AAP Objective 5 default-off posture: asserts distributed cache invalidation stays disabled in
     * the committed configuration, so an unconfigured checkout keeps single-node cache behaviour.
     *
     * <p>The capability is configuration-driven, not removed: the container entry point sets these
     * attributes to {@code true} when {@code OFBIZ_DISTRIBUTED_CACHE_CLEAR} asks for it, reusing
     * the existing {@code DistributedCacheClear} plumbing. Leaving the committed value at
     * {@code false} is what keeps the change backward compatible, and it also avoids enabling a
     * sender that has no message transport in a stock checkout.</p>
     *
     * <p>What this method pins is the <i>resolved posture</i> — what the engine will actually do —
     * rather than the presence of the attribute, and deliberately so: {@code DelegatorElement} reads
     * the flag as {@code "true".equalsIgnoreCase(value)}, so an absent attribute also resolves to
     * {@code false} and cannot be told apart here from the committed literal. The
     * <i>explicitness</i> of that literal still matters, because it is the anchor the entry point
     * rewrites, and it is enforced where the substitution happens rather than here: the entry point's
     * {@code require_rendered_cache_clear_mode} re-reads its own rendered file and refuses the start up
     * when the attribute is missing from either default delegator - "the anchor the entry point
     * substitutes has been removed or reformatted" - or carries any value other than the one this start
     * requested, and it refuses a render that put the attribute on the {@code test} delegator at all.
     * Deleting the committed literal therefore breaks the container start rather than passing
     * unnoticed, and
     * {@link #containerEntryPointRendersTheCacheTransportBeforeItValidatesItAndOnEveryStart()} holds
     * that renderer to running on every start. Contrast
     * {@code check-on-start}, whose absence flips the meaning to {@code true}: there the literal is
     * load-bearing for the resolved posture itself, which is why
     * {@link #managedRdbmsRunModeHasDdlDisabled()} catches its removal directly.</p>
     *
     * @throws GenericEntityConfException if the entity-engine configuration could not be loaded at
     *         all, which fails the test rather than being handled: a suite that cannot read the
     *         configuration cannot make any claim about it
     */
    @Test
    public void distributedCacheClearDefaultsToDisabled() throws GenericEntityConfException {
        EntityConfig entityConfig = EntityConfig.getInstance();

        DelegatorElement defaultDelegator = entityConfig.getDelegator("default");
        assertNotNull(defaultDelegator, "the default delegator name is frozen and must stay declared");
        assertFalse(defaultDelegator.getDistributedCacheClearEnabled(),
                "the default delegator must keep single-node caching until it is explicitly configured");

        DelegatorElement noEcaDelegator = entityConfig.getDelegator("default-no-eca");
        assertNotNull(noEcaDelegator, "the default-no-eca delegator name is frozen and must stay declared");
        assertFalse(noEcaDelegator.getDistributedCacheClearEnabled(),
                "the default-no-eca delegator must keep single-node caching until it is explicitly configured");
    }

    /**
     * AAP Objective 4, <b>one-shot init exit status</b>: pins every engine log message the container
     * entry point reads to decide whether the schema was really applied, against the two classes that
     * emit them.
     *
     * <p>The entry point cannot use the loader's exit status as evidence. A
     * {@code bin/ofbiz --load-data readers=none} execution — which is how the one-shot init job asks
     * the engine to apply the entity-model DDL and load no business data — has no data file to read, so
     * it reports "Finished the data load with 0 rows changed", a count of the rows its READERS loaded,
     * and exits {@code 0} <b>even when the start up database check that precedes it failed
     * outright</b>. Verified against a live PostgreSQL with
     * one wrong password: {@link org.apache.ofbiz.entity.jdbc.DatabaseUtil} logged that it could not
     * connect and aborted, the JVM still exited {@code 0}, and no table existed afterwards. Since the
     * only reason the init mode exists is that its exit status means "the schema is ready", the entry
     * point additionally asserts on this log output, and these literals are what make that assertion
     * true.</p>
     *
     * <p>Five groups of literals are pinned, one per question the entry point has to answer: the
     * database check <i>ran in init mode</i> ({@code SCHEMA_INIT_DDL_SIGNATURE}), the verifying pass
     * <i>ran in run mode</i> ({@code SCHEMA_INIT_VERIFY_SIGNATURE}), the check <i>gave up</i>
     * ({@code SCHEMA_INIT_ABORT_SIGNATURES}), part of the DDL <i>was refused</i>
     * ({@code SCHEMA_INIT_DDL_FAILURE_SIGNATURES}), and the database <i>is still missing something</i>
     * ({@code SCHEMA_INIT_RESIDUAL_SIGNATURES}). Whether those enumerations are <em>complete</em> is a
     * separate question, asserted by {@link #theEntryPointCoversEveryFailureTheEngineCanReport}.</p>
     *
     * <p>Reading another component's log text is a coupling, so it is made <i>visible</i> here rather
     * than left implicit in a shell script: this test fails the build the moment any of these messages
     * is reworded, moved out of a logging call, or dropped from the entry point — which is the whole
     * point, because the alternative failure mode is a container that reports success in production
     * having created nothing. It also keeps the constants load-bearing: the checks that consume
     * them must still be present in {@code initialise_schema}.</p>
     *
     * @throws Exception if either engine source or the entry point cannot be read, which fails the
     *         test rather than being handled: a suite that cannot read them cannot make any claim
     *         about them
     */
    @Test
    @DisplayName("the entry point's schema-init log signatures match the engine classes that emit them")
    public void schemaInitLogSignaturesMatchTheEmittingEngineSources() throws Exception {
        String entryPoint = repositoryText(ENTRY_POINT);
        String delegatorSource = repositoryText(DELEGATOR_SOURCE);
        String databaseUtilSource = repositoryText(DATABASE_UTIL_SOURCE);

        // Positive evidence. The engine logs the flag pair it resolved, so the entry point requires the
        // "true" form: seeing it means the check ran and that this execution really got init mode.
        String ddlSignature = shellConstant(entryPoint, "SCHEMA_INIT_DDL_SIGNATURE");
        assertTrue(ddlSignature.endsWith("true"), "the DDL signature must end with the add-missing value"
                + " the init mode renders, so run-mode flags cannot satisfy it: " + ddlSignature);
        String loggedLiteral = ddlSignature.substring(0, ddlSignature.length() - "true".length());
        int emitted = delegatorSource.indexOf('"' + loggedLiteral + '"');
        assertTrue(emitted > 0, DELEGATOR_SOURCE + " must still log exactly \"" + loggedLiteral
                + "\": the container entry point requires that line before it records a schema as applied");
        int loggingCall = delegatorSource.lastIndexOf("Debug.logInfo(", emitted);
        assertTrue(loggingCall >= 0 && loggingCall > emitted - EMISSION_CONTEXT,
                "the database-check message must stay an info-level log call, which is the level the"
                        + " shipped container configuration emits");
        int concatenatedFlag = delegatorSource.indexOf("getAddMissingOnStart()", emitted);
        assertTrue(concatenatedFlag >= 0 && concatenatedFlag < emitted + EMISSION_CONTEXT,
                "the add-missing flag must stay concatenated onto that message, which is what makes"
                        + " the \"true\" the entry point looks for the init-mode marker");

        // The verifying pass is the same message with the other flag value, which is what makes the two
        // passes distinguishable in one log format and countable against each other.
        String verifySignature = shellConstant(entryPoint, "SCHEMA_INIT_VERIFY_SIGNATURE");
        assertEquals(loggedLiteral + "false", verifySignature,
                "the verification signature must be the identical engine message with addMissing=false, because"
                        + " both are the same concatenation of the same literal and the same flag");

        // Negative evidence. Every one of these means the check ran and then gave up, or ran and could not
        // carry out part of what it found, so the schema is missing or partial however the loader exited.
        for (String group : List.of("SCHEMA_INIT_ABORT_SIGNATURES", "SCHEMA_INIT_DDL_FAILURE_SIGNATURES",
                "SCHEMA_INIT_RESIDUAL_SIGNATURES")) {
            List<String> signatures = shellArray(entryPoint, group);
            assertFalse(signatures.isEmpty(), group + " must not be empty");
            for (String signature : signatures) {
                assertTrue(databaseUtilSource.contains('"' + signature),
                        DATABASE_UTIL_SOURCE + " must still begin a logged message with \"" + signature
                                + "\": the container entry point refuses to record a schema as applied when it"
                                + " appears in the init log");
            }
        }

        // The two mismatch groups are pinned differently, and they have to be. A residual message BEGINS
        // with its signature, so the check above can require a quotation mark in front of it; a mismatch
        // message is a concatenation whose stable part sits in the MIDDLE - the engine builds
        // "Column [x] of table [y] ... is of type [a] in the database, but is defined as type [b]" - so what
        // is required here is that the fragment still appears inside a string literal of the emitting class.
        // Without that the entry point could be grepping for text the engine no longer writes, and the whole
        // verification would pass by finding nothing.
        for (String group : List.of("SCHEMA_INIT_MISMATCH_SIGNATURES", "SCHEMA_INIT_UNPROVEN_SIGNATURES")) {
            List<String> signatures = shellArray(entryPoint, group);
            assertFalse(signatures.isEmpty(), group + " must not be empty");
            for (String signature : signatures) {
                assertTrue(databaseUtilSource.contains(signature),
                        DATABASE_UTIL_SOURCE + " must still emit the fragment \"" + signature + "\": the container"
                                + " entry point refuses to record a schema as applied when the verifying pass"
                                + " reports it, so a rewording upstream would silently retire that refusal");
            }
            assertTrue(entryPoint.contains("\"${" + group + "[@]}\""),
                    "the entry point must still consult " + group);
        }

        // The constants are only worth pinning while the entry point still consults them.
        assertTrue(entryPoint.contains("grep --quiet --fixed-strings \"$SCHEMA_INIT_DDL_SIGNATURE\""),
                "the entry point must still require the database-check message before recording success");
        assertTrue(entryPoint.contains("\"$SCHEMA_INIT_VERIFY_SIGNATURE\""),
                "the entry point must still count the verification pass's database-check message");
        for (String group : List.of("SCHEMA_INIT_ABORT_SIGNATURES", "SCHEMA_INIT_DDL_FAILURE_SIGNATURES",
                "SCHEMA_INIT_RESIDUAL_SIGNATURES")) {
            assertTrue(entryPoint.contains("\"${" + group + "[@]}\""),
                    "the entry point must still consult " + group);
        }
    }

    /**
     * AAP Objective 4: asserts the entry point's failure enumeration covers <em>every</em> failure the
     * entity engine can report, not merely the ones somebody happened to think of.
     *
     * <p>A test that fixed the number of messages the entry point names would turn an incomplete list into a
     * pinned requirement and would keep passing while the enumeration had a hole. The census below runs the
     * other way round: it reads every message the engine emits for the two failure classes out of the engine's
     * own source and requires the entry point to name each one, so a message the engine gains is a failure here
     * rather than a silent gap.</p>
     *
     * <p>Two classes are censused, because they fail in different ways and the second is the dangerous
     * one:</p>
     * <ul>
     * <li><b>Abort.</b> A message ending {@code ", aborting."} is a {@code return} out of
     * {@code checkDb} before any entity is examined, so nothing at all was created.</li>
     * <li><b>DDL failure.</b> A {@code "Could not create ..."} or {@code "Could not add column ..."}
     * message is logged at error level and then the engine <em>continues with the next entity</em>. The
     * run finishes, the data loader exits {@code 0} because it had no reader row to load, no abort
     * message is ever printed, and the database is left with some tables and not others. An enumeration that omits
     * one of these lets the init job record a partial schema as applied.</li>
     * </ul>
     *
     * @throws Exception if the engine source or the entry point cannot be read
     */
    @Test
    @DisplayName("the entry point covers every abort and DDL-failure message DatabaseUtil can emit")
    public void theEntryPointCoversEveryFailureTheEngineCanReport() throws Exception {
        String entryPoint = repositoryText(ENTRY_POINT);
        String databaseUtilSource = repositoryText(DATABASE_UTIL_SOURCE);

        assertCoversEveryLiteral(databaseUtilSource, "\"([^\"]*, aborting\\.)\"",
                shellArray(entryPoint, "SCHEMA_INIT_ABORT_SIGNATURES"), ENGINE_ABORT_MESSAGES, "abort");
        assertCoversEveryLiteral(databaseUtilSource, "\"(Could not (?:create|add)[^\"]*)\"",
                shellArray(entryPoint, "SCHEMA_INIT_DDL_FAILURE_SIGNATURES"), ENGINE_DDL_FAILURE_MESSAGES,
                "DDL-failure");
    }

    /**
     * AAP Objective 4: executes the entry point's two schema-init verdicts against synthetic engine logs
     * and asserts that neither an aborted, a partial nor an incomplete outcome can be recorded as applied.
     *
     * <p>The verdict functions are pure - they read a log file and print an explanation, and change
     * nothing else - which is precisely so that they can be exercised here. That matters more for this
     * gate than for anything else in the container entry point, because the outcome it exists to catch is
     * the one that cannot be produced on demand in a test: a database that accepted some of the DDL and
     * refused the rest. Every log below is the shape the engine really produces; the literals are the
     * same ones {@link #theEntryPointCoversEveryFailureTheEngineCanReport} pins to the engine source.</p>
     *
     * <p>The two passes are asymmetric on purpose, and both directions are asserted. The residual
     * messages - {@code "] has no table in the database"} and {@code "] is missing its corresponding "} -
     * are emitted by the engine <em>before</em> it decides whether to create anything, so in the applying
     * pass they are the normal running commentary of a fresh database and must be ignored, while in the
     * verifying pass, where no DDL can be issued, either one is the database stating that the applying
     * pass did not finish.</p>
     *
     * @param tempDir a per-test temporary directory for the sourced library, the driver and the logs
     * @throws Exception if the entry point cannot be read or the shell cannot be run
     */
    @Test
    @DisplayName("the schema-init verdicts reject every aborted, partial and incomplete outcome")
    public void theSchemaInitVerdictsRejectEveryIncompleteOutcome(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point's verdicts");
        String entryPoint = repositoryText(ENTRY_POINT);
        String applyLine = shellConstant(entryPoint, "SCHEMA_INIT_DDL_SIGNATURE");
        String verifyLine = shellConstant(entryPoint, "SCHEMA_INIT_VERIFY_SIGNATURE");

        // Two entity groups, one created table, and the residual commentary that always accompanies a
        // fresh database. This is what a wholly successful applying pass looks like.
        String cleanApply = String.join("\n", applyLine, "Entity [Party] has no table in the database",
                "Created table [PARTY]", applyLine, "Finished the data load with 0 rows changed");
        String cleanVerification = String.join("\n", verifyLine, verifyLine,
                "Finished the data load with 0 rows changed");

        assertVerdict(tempDir, "apply", 0, cleanApply, 0, true,
                "a clean applying pass, residual commentary and all, must be accepted");
        assertVerdict(tempDir, "apply", 1, cleanApply, 0, false,
                "a non-zero loader status must be refused");
        assertVerdict(tempDir, "apply", 0, "Finished the data load with 0 rows changed", 0, false,
                "an execution that never performed the database check must be refused");
        assertVerdict(tempDir, "apply", 0, String.join("\n", verifyLine,
                "Finished the data load with 0 rows changed"), 0, false,
                "run-mode flags rendered into an init execution must be refused");
        for (String signature : shellArray(entryPoint, "SCHEMA_INIT_ABORT_SIGNATURES")) {
            assertVerdict(tempDir, "apply", 0, cleanApply + "\n" + signature + " for helperName [localpostgres]",
                    0, false, "an applying pass reporting '" + signature + "' must be refused");
        }
        for (String signature : shellArray(entryPoint, "SCHEMA_INIT_DDL_FAILURE_SIGNATURES")) {
            assertVerdict(tempDir, "apply", 0, cleanApply + "\n" + signature + "PARTY]: permission denied",
                    0, false, "an applying pass reporting '" + signature + "' leaves a PARTIAL schema and must"
                            + " be refused even though the loader exited 0");
        }

        assertVerdict(tempDir, "verify", 0, cleanVerification, 2, true,
                "a verification that re-read both groups and found nothing missing must be accepted");
        assertVerdict(tempDir, "verify", 1, cleanVerification, 2, false,
                "a non-zero verification status must be refused");
        assertVerdict(tempDir, "verify", 0, cleanVerification, 0, false,
                "a zero applied-group count must be refused rather than compared against itself");
        assertVerdict(tempDir, "verify", 0, cleanVerification, 3, false,
                "a verification that re-read fewer groups than were written to must be refused");
        assertVerdict(tempDir, "verify", 0, "Finished the data load with 0 rows changed", 2, false,
                "a verification that performed no database check at all must be refused");
        for (String signature : shellArray(entryPoint, "SCHEMA_INIT_RESIDUAL_SIGNATURES")) {
            assertVerdict(tempDir, "verify", 0, cleanVerification + "\nEntity [Party" + signature, 2, false,
                    "a verification still reporting '" + signature + "' must be refused");
            assertVerdict(tempDir, "apply", 0, cleanApply + "\nEntity [Party" + signature, 0, true,
                    "the same message in the APPLYING pass is normal commentary and must be accepted");
        }

        // PRESENT IS NOT CORRECT. Every comparison the engine makes between the model and a column it
        // FOUND is a refusal in the verifying pass, and none of them is waivable: a column of the wrong
        // type, width, scale or key membership is wrong in every deployment, and re-running the init job
        // cannot repair it because the DDL is additive and never alters an existing column. Each of these
        // outcomes used to be recorded as a successfully applied schema.
        for (String signature : shellArray(entryPoint, "SCHEMA_INIT_MISMATCH_SIGNATURES")) {
            assertVerdict(tempDir, "verify", 0, cleanVerification + "\nColumn [PARTY_ID] of table [PARTY] of"
                    + " entity [Party] " + signature + "numeric] in the entity definition.", 2, false,
                    "a verifying pass reporting '" + signature + "' has proved the schema does not match the"
                            + " entity model and must be refused");
            assertVerdict(tempDir, "apply", 0, cleanApply + "\nColumn [PARTY_ID] of table [PARTY] of entity"
                    + " [Party] " + signature + "numeric] in the entity definition.", 0, true,
                    "the applying pass is judged on what it could not DO; the mismatch is the verifying"
                            + " pass's question, and duplicating it here would refuse a run that then"
                            + " reported the same fact conclusively");
        }

        // Present, not missing, not mismatched, and NOT DECIDABLE: a column the model has no field for is
        // harmless when it is nullable or defaulted and breaks every insert into its table when it is NOT
        // NULL without a default, and the engine reads that nullability without ever reporting it. Refused
        // by default; accepted only when the operator says the difference is deliberate.
        for (String signature : shellArray(entryPoint, "SCHEMA_INIT_UNPROVEN_SIGNATURES")) {
            assertVerdict(tempDir, "verify", 0, cleanVerification + "\nColumn [OLD_FIELD] of table [PARTY] of"
                    + " entity [Party" + signature + "PARTY] has 13 columns.", 2, false,
                    "a verifying pass reporting '" + signature + "' cannot prove the schema is usable and must"
                            + " be refused unless the operator accepts it");
            assertVerdictWith(tempDir, "verify", 0, cleanVerification + "\nColumn [OLD_FIELD] of table [PARTY]"
                    + " of entity [Party" + signature + "PARTY] has 13 columns.", 2,
                    "RESOLVED_SCHEMA_INIT_ACCEPT_EXISTING=true", true,
                    "OFBIZ_SCHEMA_INIT_ACCEPT_EXISTING=true must waive '" + signature + "', which is the"
                            + " documented way to re-initialise a schema an upgrade has changed");
        }

        // An extra TABLE stays acceptable, and so do the foreign key and index residuals. The engine names
        // every table it uses, so a table no entity describes cannot affect a statement it issues; and the
        // two residuals are produced by check-*-on-start flags that ship disabled and by a comparison that
        // reads nothing at all on PostgreSQL - a live initialisation of this image against PostgreSQL 13.23
        // created 1970 foreign key constraints and 4600 indexes while the same run reported every one of
        // them missing, so treating either as evidence would fail every correct initialisation.
        assertVerdict(tempDir, "verify", 0, String.join("\n", cleanVerification,
                "Table named [OLD_THING] exists in the database but has no corresponding entity",
                "No Foreign Key Constraint [PARTY_CB] found for entity [Party]",
                "No Index [PARTY_TXCRTD] found for entity [Party]"), 2, true,
                "an extra table and the disabled-by-default foreign key and index residuals must not be"
                        + " mistaken for an incomplete or incompatible schema");
    }

    /**
     * AAP Objective 4: pins the three engine comparisons that are deliberately NOT treated as evidence, and
     * the measurement that is the reason.
     *
     * <p>Two of them - the foreign key and index residuals - have always been excluded. The third is the
     * primary key comparison, and excluding it is counter-intuitive enough that it has to be a checked
     * decision rather than an omission somebody might helpfully "fix": {@code check-pks-on-start} defaults
     * to {@code true} in {@code entity-config.xsd}, so {@code DatabaseUtil} emits those messages with no
     * configuration at all, and a reader who found them missing from the entry point's enumeration would
     * reasonably add them.</p>
     *
     * <p>They are excluded because on PostgreSQL the comparison reads nothing. A full initialisation of this
     * image against PostgreSQL 13.23 with pgJDBC 42.7.13 logged
     * {@code "Reviewed 0 primary key fields from database."} for each of its three databases - after asking
     * {@code getPrimaryKeys} with a {@code "%"} table name and then falling back to asking for each of the 852
     * tables individually - and then reported {@code "IS NOT a primary key in the database, but IS a primary
     * key in the entity definition"} 1691 times, once for very nearly every primary key column in the model,
     * while the catalogue held 852 primary key constraints over 1674 columns that the very same run had
     * created. The foreign key and index comparisons behave identically: the same run reported
     * {@code "There are 0 indices in the database"} and 1970 missing foreign keys against a database holding
     * 1970 foreign key constraints and 4600 indexes. Treating any of the three as evidence would refuse every
     * correct initialisation of this image, which is a far worse outcome than the gap it would close - and the
     * gap is closed instead by
     * {@link #theFreshnessVerdictRefusesASchemaItCannotProveItCreated}, which proves the schema was created
     * from the entity model by the run that is releasing it.</p>
     *
     * @param tempDir a per-test temporary directory for the sourced library, the driver and the logs
     * @throws Exception if the entry point cannot be read or the shell cannot be run
     */
    @Test
    @DisplayName("the comparisons that read nothing on PostgreSQL are excluded, and stay excluded")
    public void theEngineComparisonsThatReadNothingOnPostgresAreNotTreatedAsEvidence(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point's verdicts");
        String entryPoint = repositoryText(ENTRY_POINT);
        String verifyLine = shellConstant(entryPoint, "SCHEMA_INIT_VERIFY_SIGNATURE");
        String cleanVerification = String.join("\n", verifyLine, verifyLine,
                "Finished the data load with 0 rows changed");

        // Both directions of the primary key comparison, as the engine writes them, on a verifying pass that
        // is otherwise clean. This is the shape of EVERY correct PostgreSQL initialisation of this image.
        String pkDrift = String.join("\n", cleanVerification,
                "Reviewed 0 primary key fields from database.",
                "Column [PARTY_ID] of table [public.PARTY] of entity [Party] IS NOT a primary key in the"
                        + " database, but IS a primary key in the entity definition. The primary key for this"
                        + " table needs to be re-created or modified to add this column to the primary key.",
                "Column [OLD_ID] of table [public.PARTY] of entity [Party] IS a primary key in the database,"
                        + " but IS NOT a primary key in the entity definition. The primary key for this table"
                        + " needs to be re-created or modified so that this column is NOT part of the primary"
                        + " key.");
        assertVerdict(tempDir, "verify", 0, pkDrift, 2, true,
                "the primary key comparison reads nothing on PostgreSQL - the same run that created 852"
                        + " primary keys reported 1691 of them missing - so treating it as evidence would"
                        + " refuse every correct initialisation");

        // And the exclusion is stated where it can be found, next to the enumeration it is an exception to,
        // with the measurement rather than an assertion of belief.
        for (String required : List.of("Reviewed 0 primary key fields from database.",
                "IS NOT a primary key in the database, but IS a primary key in the entity definition",
                "There are 0 indices in the database")) {
            assertTrue(entryPoint.contains(required),
                    "the entry point must record the measurement that justifies excluding an engine"
                            + " comparison, including \"" + required + "\"");
        }
        assertFalse(shellArray(entryPoint, "SCHEMA_INIT_MISMATCH_SIGNATURES").stream()
                        .anyMatch(signature -> signature.contains("primary key")),
                "no primary key comparison may be enumerated as evidence while it reads nothing on"
                        + " PostgreSQL: every correct initialisation of this image would be refused");
    }

    /**
     * AAP Objective 4: executes the entry point's freshness verdict and asserts that a schema this run did
     * not create in full cannot be released without the operator saying so.
     *
     * <p>This is the dimension no comparison can reach. {@code DatabaseUtil} reads the nullability of every
     * column and neither compares nor logs it, and its foreign key and index comparison reads nothing on
     * PostgreSQL - measured, and recorded beside the residual signatures in the entry point - so for a table
     * this run did not create, three properties are simply unavailable: the nullability and defaults of its
     * columns, and the presence of its declared foreign keys and indexes. For a table this run DID create
     * they need no checking at all, because the engine emitted their DDL from the entity model in this very
     * execution. Proving authorship is therefore strictly stronger than any comparison available here, and
     * it is proved from the engine's own numbers: ModelReader's {@code #Entities=N}, one
     * {@code has no table in the database} per entity, and one {@code Created table [} per creation.</p>
     *
     * <p>The numbers below are the shape of a real run: a live PostgreSQL 13.23 initialisation of this image
     * logged {@code #Entities=865} with 865 of each line.</p>
     *
     * @param tempDir a per-test temporary directory for the sourced library, the driver and the logs
     * @throws Exception if the entry point cannot be read or the shell cannot be run
     */
    @Test
    @DisplayName("a schema this run did not create in full is not released without an explicit acceptance")
    public void theFreshnessVerdictRefusesASchemaItCannotProveItCreated(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point's verdicts");
        String entryPoint = repositoryText(ENTRY_POINT);
        String applyLine = shellConstant(entryPoint, "SCHEMA_INIT_DDL_SIGNATURE");
        String model = "Finished loading entities; #Entities=3 #ViewEntities=1 #Fields=9 #Relationships=4";

        String fresh = String.join("\n", applyLine, model,
                "Entity [Party] has no table in the database", "Entity [Person] has no table in the database",
                "Entity [PartyRole] has no table in the database",
                "Created table [public.PARTY]", "Created table [public.PERSON]",
                "Created table [public.PARTY_ROLE]", "Finished the data load with 0 rows changed");
        assertFreshness(tempDir, fresh, "", true,
                "a run that found every entity without a table and created every one of them has created the"
                        + " whole schema from the model and needs no further proof");

        assertFreshness(tempDir, String.join("\n", applyLine, model,
                "Entity [Party] has no table in the database", "Created table [public.PARTY]",
                "Finished the data load with 0 rows changed"), "", false,
                "a run that created one of three tables is adding to a schema something else created, whose"
                        + " nullability, defaults, foreign keys and indexes it cannot see");

        assertFreshness(tempDir, String.join("\n", applyLine, model,
                "Finished the data load with 0 rows changed"), "", false,
                "a re-run against a complete schema creates nothing and proves nothing about it");

        assertFreshness(tempDir, String.join("\n", applyLine,
                "Entity [Party] has no table in the database", "Created table [public.PARTY]"), "", false,
                "a log with no #Entities line gives nothing to compare the counts against, and a verdict that"
                        + " cannot read its evidence must refuse rather than pass");

        assertFreshness(tempDir, String.join("\n", applyLine, model,
                "Entity [Party] has no table in the database", "Entity [Person] has no table in the database",
                "Entity [PartyRole] has no table in the database", "Created table [public.PARTY]",
                "Created table [public.PERSON]"), "", false,
                "a run that found three tables missing and created two has left one missing and must not be"
                        + " read as having created the schema");

        // The refusal names the numbers rather than only the conclusion, because the operator's next action
        // depends entirely on which of the two situations it is: a deliberate re-initialisation, or a
        // database that should have been empty and is not.
        String refusal = freshnessVerdict(tempDir, String.join("\n", applyLine, model,
                "Entity [Party] has no table in the database", "Created table [public.PARTY]"), "");
        assertTrue(refusal.contains("declares 3 entities") && refusal.contains("found 1 of them")
                        && refusal.contains("created 1 tables"),
                "the refusal must state the three counts it compared, was: " + refusal);
        assertTrue(refusal.contains("OFBIZ_SCHEMA_INIT_ACCEPT_EXISTING=true"),
                "the refusal must name the way to accept a deliberate re-initialisation, was: " + refusal);
    }

    /**
     * Asserts that a set of entry-point signatures covers every matching string literal in an engine
     * source, so the enumeration cannot fall behind the code it enumerates.
     *
     * <p>"Covers" means some signature is a prefix of the literal, which is the relationship the entry
     * point's {@code grep --fixed-strings} actually tests: the engine concatenates entity and column
     * names onto every one of these messages, so only the stable leading text can be matched.</p>
     *
     * @param engineSource the Java source to census
     * @param literalPattern a regular expression whose first group is the literal to cover
     * @param signatures the entry point's signatures
     * @param minimumCensus how many literals this pattern must still find, so the census cannot pass by
     *        matching nothing
     * @param description what the signatures are, for the failure message
     */
    private static void assertCoversEveryLiteral(String engineSource, String literalPattern,
            List<String> signatures, int minimumCensus, String description) {
        Matcher literals = Pattern.compile(literalPattern).matcher(engineSource);
        int censused = 0;
        while (literals.find()) {
            String literal = literals.group(1);
            censused++;
            assertTrue(signatures.stream().anyMatch(literal::startsWith),
                    DATABASE_UTIL_SOURCE + " logs \"" + literal + "\" but no " + description + " signature in the"
                            + " container entry point covers it. The engine logs that message and continues, so an"
                            + " init job would record the resulting schema as applied. Add a covering literal to"
                            + " the corresponding SCHEMA_INIT_* array in " + ENTRY_POINT + ".");
        }
        assertTrue(censused >= minimumCensus, "the " + description + " census found only " + censused
                + " literals in " + DATABASE_UTIL_SOURCE + " where it expected at least " + minimumCensus
                + ": either a message the container entry point still names has been reworded out of this"
                + " shape - in which case whatever replaced it is now uncovered - or the census has stopped"
                + " reading the engine at all, which would make this gate vacuous");
    }

    /**
     * Runs one of the entry point's two schema-init verdicts over a synthetic log and asserts the outcome.
     *
     * <p>The entry point is sourced with its trailing {@code _main "$@"} line removed - the one line
     * that would otherwise run the whole start up - so one function can be invoked as a black box
     * without starting OFBiz. The verdicts print an explanation when they refuse and print nothing when they accept, so
     * "accepted" is simply empty output.</p>
     *
     * @param workDir a per-test temporary directory
     * @param pass {@code "apply"} for the applying verdict, {@code "verify"} for the verifying one
     * @param status the exit status to present as the data-load child's
     * @param log the synthetic engine log the verdict must judge
     * @param appliedChecks how many database-check lines the applying pass produced, for the verifying
     *        verdict only
     * @param expectedAccepted whether the verdict must accept this outcome
     * @param because what the assertion is proving
     * @throws Exception if the entry point cannot be read or the shell cannot be run
     */
    private static void assertVerdict(Path workDir, String pass, int status, String log, int appliedChecks,
            boolean expectedAccepted, String because) throws Exception {
        assertVerdictWith(workDir, pass, status, log, appliedChecks, "", expectedAccepted, because);
    }

    /**
     * {@link #assertVerdict} with shell run before the verdict, so a case can present the verdict with the
     * configuration an operator would have supplied.
     *
     * <p>The preamble assigns the RESOLVED variable rather than the {@code OFBIZ_} one, because that is what
     * the verdict reads: {@code resolve_entity_engine_flags} normalises the environment variable into it long
     * before either pass runs, and a case that set the environment variable alone would be asserting that the
     * verdict duplicates that normalisation, which it deliberately does not.</p>
     *
     * @param workDir a per-test temporary directory
     * @param pass {@code "apply"} for the applying verdict, {@code "verify"} for the verifying one
     * @param status the exit status to present as the data-load child's
     * @param log the synthetic engine log the verdict must judge
     * @param appliedChecks how many database-check lines the applying pass produced
     * @param preamble shell to run after the entry point is sourced and before the verdict is called
     * @param expectedAccepted whether the verdict must accept this outcome
     * @param because what the assertion is proving
     * @throws Exception if the entry point cannot be read or the shell cannot be run
     */
    private static void assertVerdictWith(Path workDir, String pass, int status, String log, int appliedChecks,
            String preamble, boolean expectedAccepted, String because) throws Exception {
        String invocation = "apply".equals(pass)
                ? "schema_init_apply_verdict " + status + " @LOG@"
                : "schema_init_verification_verdict " + status + " @LOG@ " + appliedChecks;
        String verdict = runVerdictFunction(workDir, preamble, log, invocation, pass);
        assertEquals(expectedAccepted, verdict.isEmpty(), because + " -- verdict was: " + verdict);
    }

    /**
     * Asserts the outcome of the entry point's freshness verdict over a synthetic applying-pass log.
     *
     * @param workDir a per-test temporary directory
     * @param log the synthetic applying-pass log
     * @param preamble shell to run after the entry point is sourced and before the verdict is called
     * @param expectedAccepted whether the verdict must accept this outcome
     * @param because what the assertion is proving
     * @throws Exception if the entry point cannot be read or the shell cannot be run
     */
    private static void assertFreshness(Path workDir, String log, String preamble, boolean expectedAccepted,
            String because) throws Exception {
        String verdict = freshnessVerdict(workDir, log, preamble);
        assertEquals(expectedAccepted, verdict.isEmpty(), because + " -- verdict was: " + verdict);
    }

    /**
     * The freshness verdict's own words, for a case that has to assert on what the refusal says.
     *
     * @param workDir a per-test temporary directory
     * @param log the synthetic applying-pass log
     * @param preamble shell to run after the entry point is sourced and before the verdict is called
     * @return the refusal, or the empty string when the schema was proved fresh
     * @throws Exception if the entry point cannot be read or the shell cannot be run
     */
    private static String freshnessVerdict(Path workDir, String log, String preamble) throws Exception {
        return runVerdictFunction(workDir, preamble, log, "schema_init_freshness_verdict @LOG@", "freshness");
    }

    /**
     * Sources the real entry point and calls one of its pure verdict functions over a synthetic log.
     *
     * <p>The entry point is sourced with its trailing {@code _main "$@"} line removed - the one line
     * that would otherwise run the whole start up - so one function can be invoked as a black box
     * without starting OFBiz. Every verdict prints an explanation when it refuses and prints nothing when it accepts, so
     * "accepted" is simply empty output - which is also why no verdict may print anything else.</p>
     *
     * @param workDir a per-test temporary directory
     * @param preamble shell to run after the entry point is sourced and before the verdict is called
     * @param log the synthetic engine log to judge
     * @param invocation the call to make, with {@code @LOG@} where the log path belongs
     * @param described what is being run, for the failure messages
     * @return the combined output of the call
     * @throws Exception if the entry point cannot be read or the shell cannot be run
     */
    private static String runVerdictFunction(Path workDir, String preamble, String log, String invocation,
            String described) throws Exception {
        Path library = workDir.resolve("entrypoint-library.sh");
        if (!Files.exists(library)) {
            List<String> sourced = new ArrayList<>();
            for (String line : Files.readAllLines(repositoryRoot().resolve(ENTRY_POINT), StandardCharsets.UTF_8)) {
                if (!"_main \"$@\"".equals(line)) {
                    sourced.add(line);
                }
            }
            Files.write(library, sourced, StandardCharsets.UTF_8);
        }
        Path logFile = Files.createTempFile(workDir, "engine-", ".log");
        Files.writeString(logFile, log + "\n", StandardCharsets.UTF_8);
        Path driver = Files.createTempFile(workDir, "verdict-", ".sh");
        Files.writeString(driver, "#!/usr/bin/env bash\n. " + shellQuote(library) + "\n"
                + (preamble.isEmpty() ? "" : preamble + "\n")
                + invocation.replace("@LOG@", shellQuote(logFile)) + "\n", StandardCharsets.UTF_8);

        // Run through the shared driver, which waits on the process BEFORE collecting its output. Reading
        // the output first, as this used to, makes the deadline unreachable: the read blocks until the child
        // closes its stream, so a child that never exits is waited on for ever and never destroyed.
        ShellDriver.Run run = ShellDriver.run(driver, workDir, Map.of(), VERDICT_TIMEOUT_SECONDS);
        assertFalse(run.timedOut(), "the " + described + " verdict did not terminate, output was:\n"
                + run.output());
        assertEquals(0, run.exitCode(), "the " + described + " verdict must not fail as a shell function");
        return run.output();
    }

    /** A shell-quoted path, so a temporary directory containing a space or a quote cannot break a driver. */
    private static String shellQuote(Path path) {
        return shellQuote(path.toString());
    }

    /** Single-quotes arbitrary text for safe interpolation into a generated driver script. */
    private static String shellQuote(String text) {
        return "'" + text.replace("'", "'\\''") + "'";
    }

    /** Whether a POSIX shell can be executed, so the shell-driven assertions can be skipped if not. */
    /**
     * Whether a POSIX shell can be executed, so the shell-driven assertions can be skipped if not.
     *
     * <p>Delegated to the shared driver rather than repeated: this probe has to start a process, wait
     * for it and close its output, and every copy of it was one more place to get that wrong.
     *
     * @return true when {@code bash} can be run
     */
    private static boolean isBashAvailable() {
        return ShellDriver.isBashAvailable();
    }

    /**
     * A repository file read as text.
     *
     * <p>Read from source rather than from a compiled artefact because what is being pinned is the
     * literal an author would edit, in the file they would edit it in.</p>
     *
     * @param relativePath path relative to the repository root
     * @return the file's contents
     * @throws Exception if the file cannot be read
     */
    private static String repositoryText(String relativePath) throws Exception {
        Path file = repositoryRoot().resolve(relativePath);
        assertTrue(Files.isRegularFile(file), "missing " + file);
        return Files.readString(file);
    }

    /**
     * The value of a single top-level scalar assignment in a shell script.
     *
     * <p>Matched at column one with either quoting style and nothing but the value between the
     * quotes, so a constant that grows a substitution or a concatenation is reported as missing
     * rather than silently compared against a fragment of itself.</p>
     *
     * @param script the script text
     * @param name the constant's name
     * @return the assigned literal
     */
    private static String shellConstant(String script, String name) {
        Matcher matcher = Pattern.compile("^" + Pattern.quote(name) + "=(?:\"([^\"]*)\"|'([^']*)')$",
                Pattern.MULTILINE).matcher(script);
        assertTrue(matcher.find(), "the entry point must declare the constant " + name
                + " as a single quoted literal at column one");
        String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        assertFalse(matcher.find(), name + " must be assigned exactly once");
        return value;
    }

    /**
     * The elements of a single top-level array assignment in a shell script, one quoted literal per
     * line.
     *
     * <p>Every content line of the array must yield exactly one element, so an element that loses its
     * quoting — and with it its spaces — fails here instead of being skipped silently.</p>
     *
     * @param script the script text
     * @param name the array's name
     * @return the assigned literals, in declaration order
     */
    private static List<String> shellArray(String script, String name) {
        Matcher block = Pattern.compile("^" + Pattern.quote(name) + "=\\(\\R(.*?)^\\)$",
                Pattern.MULTILINE | Pattern.DOTALL).matcher(script);
        assertTrue(block.find(), "the entry point must declare the array " + name
                + " opening at column one and closing with a bare ')'");
        String body = block.group(1);
        assertFalse(block.find(), name + " must be assigned exactly once");

        List<String> elements = new ArrayList<>();
        Matcher element = Pattern.compile("'([^']*)'|\"([^\"]*)\"").matcher(body);
        while (element.find()) {
            elements.add(element.group(1) != null ? element.group(1) : element.group(2));
        }
        long contentLines = body.lines().filter(line -> !line.isBlank())
                .filter(line -> !line.stripLeading().startsWith("#")).count();
        assertEquals(contentLines, elements.size(),
                name + " must hold exactly one quoted literal per line");
        return elements;
    }

    /** A file that exists only at the repository root, used to locate it from whatever directory the tests run in. */
    private static final String REPOSITORY_MARKER = "dependencies.gradle";

    /**
     * AAP Objective 4 cross-artifact contract: asserts the entry point's serving-mode DDL guard covers exactly the
     * datasources {@link #managedRdbmsRunModeHasDdlDisabled()} pins, and nothing else.
     *
     * <p>The guard cannot simply count {@code field-type-name="postgres"} elements, because the committed
     * configuration also declares {@code localp6spy} — a debugging datasource no delegator maps to, which keeps the
     * engine's DDL defaults — so it names the three managed datasources explicitly. That makes this pairing
     * load-bearing: rename a managed datasource here and the shell guard would silently stop covering it while every
     * other assertion in this class kept passing. Both halves are therefore asserted together.</p>
     */
    @Test
    public void containerEntryPointGuardsExactlyTheManagedDatasourcesThisClassPins() {
        String entryPoint = entryPointText();

        assertTrue(entryPoint.contains("MANAGED_DATASOURCE_NAMES=(localpostgres localpostgresolap localpostgrestenant)"),
                ENTRY_POINT + " must guard exactly the three managed datasources this class asserts on;"
                        + " a guard over a different set would leave a serving instance unchecked");

        for (String datasourceName : new String[] {"localpostgres", "localpostgresolap", "localpostgrestenant"}) {
            Datasource managed = EntityConfig.getDatasource(datasourceName);
            assertNotNull(managed, datasourceName + " is named by the entry point's DDL guard, so it must stay declared");
            assertFalse(managed.getCheckOnStart(), datasourceName
                    + " is guarded as a run-mode datasource, so the committed configuration must agree");
            assertFalse(managed.getAddMissingOnStart(), datasourceName
                    + " is guarded as a run-mode datasource, so the committed configuration must agree");
        }
    }

    /**
     * AAP Objective 4, <b>init/serve isolation</b>: asserts the render and the serving-mode DDL check both
     * precede the {@code OFBIZ_SKIP_INIT} branch, so that path cannot bypass them.
     *
     * <p>{@code /ofbiz/config} is a declared volume, so a {@code config/entityengine.xml} written by an
     * {@code OFBIZ_SCHEMA_INIT=true} run — which carries both flags {@code true} on purpose — outlives the container
     * that wrote it. When the whole configuration group sat behind the skip branch, a serving instance started with
     * {@code OFBIZ_SKIP_INIT} rendered nothing and validated nothing, and so could read that file and issue
     * {@code CREATE} and {@code ALTER} statements against the managed database.</p>
     *
     * <p>The ordering is what fixes it, so the ordering is what is asserted. Everything that decides what the
     * instance will read must precede the branch — the runtime directories, the datasource configuration, the
     * configuration render itself, and the DDL-posture check over the file OFBiz will actually open — while the data
     * load and the admin user, the two steps the flag is documented to skip, must remain inside it. Asserting
     * positions rather than mere presence is deliberate: a call that exists but sits inside the branch would satisfy
     * a {@code contains} check while leaving the hazard exactly as it was.</p>
     *
     * <p>{@code apply_configuration} is asserted on the safety side of the branch rather than inside it, which is
     * the stricter of the two placements for the same reason the volume creates the hazard in the first place. A
     * render that only happened on the initialising path could not REMOVE an override an earlier start wrote, so a
     * setting the operator has since withdrawn would go on applying to every skip-init restart; running it on every
     * path is what makes a withdrawn setting stop applying. It also has to precede
     * {@code require_serving_mode_ddl_safety}'s subject matter in the other direction — that guard inspects the
     * rendered result — which is why {@code configure_database} is additionally asserted to precede the guard.</p>
     *
     * <p>The branch is matched on {@code RESOLVED_SKIP_INIT} rather than on {@code OFBIZ_SKIP_INIT} directly,
     * because the raw variable is parsed once, up front, into a resolved {@code true}/{@code false}: reading the
     * raw value directly would treat any non-empty value as "skip", so {@code OFBIZ_SKIP_INIT=false} would skip
     * the initialisation.
     * The branch reads the parsed verdict, and this assertion follows it there.</p>
     */
    @Test
    public void containerEntryPointCannotLetSkipInitBypassTheServingModeDdlCheck() {
        List<String> mainBody = containerMainBody();
        int skipBranch = indexOfLine(mainBody, "if [ \"$RESOLVED_SKIP_INIT\" = \"true\" ]; then");

        for (String safetyCall : new String[] {"ofbiz_setup_env", "create_ofbiz_runtime_directories",
                "configure_database", "require_serving_mode_ddl_safety", "apply_configuration"}) {
            int position = indexOfLine(mainBody, safetyCall);
            assertTrue(position < skipBranch, safetyCall + " must run before the OFBIZ_SKIP_INIT branch in "
                    + ENTRY_POINT + ": a serving instance must never inherit an unvalidated DDL posture");
        }

        assertTrue(indexOfLine(mainBody, "configure_database")
                        < indexOfLine(mainBody, "require_serving_mode_ddl_safety"),
                "require_serving_mode_ddl_safety inspects the configuration configure_database may have just written,"
                        + " so it must run after it in " + ENTRY_POINT);

        for (String initialisationStep : new String[] {"load_data", "load_admin_user"}) {
            int position = indexOfLine(mainBody, initialisationStep);
            assertTrue(position > skipBranch, initialisationStep + " must stay inside the OFBIZ_SKIP_INIT branch in "
                    + ENTRY_POINT + ": that flag is documented to skip initialisation, not safety");
        }
    }

    /**
     * AAP Objective 4 state separation: asserts schema-init completion is tracked apart from the data-load marker.
     *
     * <p>The two record different facts. {@code data_loaded} says rows were loaded into some database at some point
     * in the state volume's life; it survives that volume being repointed at a fresh managed database, which has no
     * tables at all. Reusing it to decide whether the schema exists let an explicit init run exit {@code 0} without
     * applying any DDL, and an orchestrator reads that status as permission to start the fleet.</p>
     *
     * <p>The receipt is also asserted to be tested only in its <i>negated</i> form. A positive existence test is the
     * shape that skips work, and the whole point of this file is that it may refuse a success it cannot evidence —
     * never suppress the work that would earn one.</p>
     */
    @Test
    public void containerEntryPointSeparatesSchemaInitCompletionFromTheDataLoadMarker() {
        String entryPoint = entryPointText();

        assertTrue(entryPoint.contains("CONTAINER_SCHEMA_INITIALISED=\"$CONTAINER_STATE_DIR/schema_initialised\""),
                ENTRY_POINT + " must track schema-init completion in its own state file");
        assertTrue(entryPoint.contains("CONTAINER_DATA_LOADED=\"$CONTAINER_STATE_DIR/data_loaded\""),
                ENTRY_POINT + " must keep the data-load marker as a separate state file");

        assertFalse(entryPoint.contains("[ -f \"$CONTAINER_SCHEMA_INITIALISED\" ]"),
                ENTRY_POINT + " must not test the schema-init receipt in a form that skips work;"
                        + " it may only refuse a success it cannot evidence");
        assertTrue(entryPoint.contains("[ ! -f \"$CONTAINER_SCHEMA_INITIALISED\" ]"),
                ENTRY_POINT + " must refuse to report success when no receipt was written");
    }

    /**
     * AAP Objective 4 success criterion: asserts the init-mode exit is gated on evidence produced by that same run.
     *
     * <p>{@code require_schema_init_completed} compares the receipt against a token unique to the execution, so a
     * receipt left by an earlier run cannot stand in for work this run did not do. It must be reached before the
     * successful exit, and the DDL pass it evidences must remain reachable when the data-load marker is already
     * present — that combination is exactly the case a check placed after the marker
     * would let report success having applied nothing.</p>
     */
    @Test
    public void containerEntryPointRequiresEvidenceFromThisRunBeforeReportingSchemaInitSuccess() {
        List<String> mainBody = containerMainBody();
        int evidenceCheck = indexOfLine(mainBody, "require_schema_init_completed");
        int servingModeRestore = indexOfLine(mainBody, "restore_serving_mode_after_schema_init");
        int successfulExit = lastIndexOfLine(mainBody, "exit 0");
        int applicationBackstop = indexOfLine(mainBody, "if [ \"$SCHEMA_INIT_APPLIED\" != \"true\" ]; then");

        // Pins WHICH exit was measured. _main also exits 0 from the --write-initial-container-state
        // sub-command dispatch at its very top, which is build-time bookkeeping and not the init-mode success
        // this contract is about, so the LAST exit 0 is taken and then checked to be the one the
        // SCHEMA_INIT_APPLIED backstop guards.
        assertTrue(applicationBackstop < successfulExit, ENTRY_POINT
                + " must guard its last exit 0 with the SCHEMA_INIT_APPLIED backstop, so that the exit measured"
                + " here is the init-mode success rather than the build-time sub-command's");
        assertTrue(evidenceCheck < successfulExit, ENTRY_POINT
                + " must verify the schema-init receipt before exiting successfully");
        assertTrue(servingModeRestore < successfulExit, ENTRY_POINT
                + " must hand the configuration volume back in serving mode before exiting successfully");

        String entryPoint = entryPointText();
        assertTrue(entryPoint.contains("if [ \"$recordedToken\" != \"$SCHEMA_INIT_RUN_TOKEN\" ]; then"),
                ENTRY_POINT + " must compare the receipt against this run's token, not merely its existence");
        assertTrue(entryPoint.contains("elif [ \"$schemaInit\" = \"true\" ]; then"),
                ENTRY_POINT + " must still apply the schema when the data-load marker already exists");
    }

    /**
     * AAP Objective 5 cross-artifact contract: asserts the delegator's cache flag is refused unless a
     * transport is configured, in <em>every</em> profile the entry point recognises.
     *
     * <p>{@link #distributedCacheClearDefaultsToDisabled()} pins the committed default; the entry point is the only
     * thing that turns the flag on, so the entry point is where the pairing has to hold. It matters because the
     * failure it prevents is silent and destructive rather than merely degraded: the five
     * {@code distributedClearCacheLine} services are declared {@code engine="jms" location="serviceMessenger"} and
     * none declares {@code require-new-transaction}, so with no active {@code jms-service} the JMS engine dereferences
     * null, {@code ServiceDispatcher.runAsync} catches it and marks the <em>caller's</em> transaction rollback-only,
     * and {@code EntityCacheServices} can only log. Every entity write that triggers an invalidation is rolled back.</p>
     *
     * <p>Two things are therefore asserted. The flag resolution must invoke the transport check, so the two can never
     * drift apart; and the check must refuse unconditionally — no {@code OFBIZ_PROFILE} test may appear in it. A
     * profile-gated refusal would let a development instance continue into that rollback behind a warning,
     * and a warning in a start up log is not a defence against a write that silently fails.</p>
     */
    @Test
    public void containerEntryPointRefusesDistributedCacheClearWithoutATransportInEveryProfile() {
        List<String> flagResolution = entryPointFunctionBody("resolve_entity_engine_flags");
        assertTrue(flagResolution.stream().anyMatch(line -> "validate_distributed_cache_transport".equals(line.trim())),
                ENTRY_POINT + " must check the cache-invalidation transport wherever it resolves"
                        + " OFBIZ_DISTRIBUTED_CACHE_CLEAR, so the flag and the transport requirement cannot drift apart");

        List<String> transportCheck = entryPointFunctionBody("validate_distributed_cache_transport");
        assertTrue(transportCheck.stream().anyMatch(line -> line.contains("config_fatal")),
                ENTRY_POINT + " must refuse to start an instance whose cache flag has no transport");
        for (String line : transportCheck) {
            assertFalse(line.contains("OFBIZ_PROFILE") || line.contains("RESOLVED_PROFILE"),
                    ENTRY_POINT + " must not gate the transport refusal on the profile; a development"
                            + " instance rolls back its writes exactly as a production one does: " + line.trim());
        }
    }

    /**
     * AAP Objective 5 / B2-JMS-RES-01: asserts a claimed transport is backed by start up evidence rather than by
     * its mere presence in a file - and, just as importantly, that the evidence is not described as more than it
     * is.
     *
     * <p>Three necessary preconditions are checked, each through its own helper so that each can fail with its own
     * message: the element must declare {@code listen}, or this instance
     * publishes invalidations while ignoring its peers' — the worst outcome available, because nothing fails and the
     * data is simply wrong on one instance; the broker client must be loadable, or the listener cannot be constructed;
     * and at least one broker endpoint must answer within a bounded deadline.</p>
     *
     * <p><b>These are conditions on the start up, and this test asserts only those.</b> None of the three creates
     * a JNDI context, looks a connection factory or topic up in one, authenticates credentials, constructs a
     * subscriber or publisher, or publishes anything. A start up cannot: {@code dependencies.gradle} bundles only
     * the JMS API - the Agent Action Plan authorises exactly two dependency additions, neither a broker client nor
     * an embedded broker - and the provider's client library is deployment specific and mounted by the operator, so
     * there is nothing available to perform a lookup with. What the three do establish is that this instance is
     * configured to subscribe, that a client library is on the class path, and that something answers on the
     * broker's port; the companion test below holds the start up log to exactly that boundary.</p>
     *
     * <p><b>That an invalidation reaches a peer is not this refactor's mechanism to prove.</b> The
     * publisher, the consumer and the service definitions that route between them are OFBiz's own
     * {@code DistributedCacheClear} and {@code EntityCacheServices}, unchanged here and covered by the
     * framework's own tiers; what this refactor adds is the per-delegator flag and the start up conditions
     * asserted above, and those are what this test holds. The hop itself depends on a specific broker,
     * client library, credentials and topic, none of which exist in this tree - only the JMS API is
     * bundled - so it is verified against a deployment's own broker, for which {@code DOCKER.adoc}
     * carries the two-container procedure and the log lines to watch.</p>
     */
    @Test
    public void containerEntryPointRequiresASubscriberAndStartupConnectivityEvidenceForTheTransport() {
        List<String> transportCheck = entryPointFunctionBody("validate_distributed_cache_transport");
        for (String evidenceCall : new String[] {"require_transport_listener_enabled",
                "require_transport_client_available", "require_transport_reachable"}) {
            assertTrue(transportCheck.stream().anyMatch(line -> evidenceCall.equals(line.trim())),
                    ENTRY_POINT + " must obtain start up evidence through " + evidenceCall
                            + "; the presence of a configuration element is not evidence that a broker answers");
        }
    }

    /**
     * The transport validation may not describe its own evidence as cache coherence, and its acceptance message
     * must say so in as many words.
     *
     * <p>This guards a defect class rather than a line of code. Every check the start up performs is a necessary
     * precondition, and the temptation in writing the log line that follows them is to summarise them as success:
     * "the fleet is coherent", "invalidations have a transport to travel on". An operator who reads that stops
     * looking, and the failure that matters most is exactly the one that leaves every precondition satisfied - a
     * topic two instances do not physically share, or a subscriber that never registers, publishes nothing to
     * nobody while the configuration and the network both look correct.</p>
     *
     * <p>The acceptance message is therefore required to carry the boundary as well as the finding, so that it
     * travels with the claim into the container log where it is actually read, and so that a future edit which
     * re-inflates the claim fails here rather than reaching an operator.</p>
     */
    @Test
    public void theTransportValidationNeverReportsItsPreconditionsAsCacheCoherence() {
        List<String> transportCheck = entryPointFunctionBody("validate_distributed_cache_transport");
        String accepted = transportCheck.stream()
                .filter(line -> line.contains("printf") && line.contains(CACHE_CLEAR_ENABLED))
                .findFirst()
                .orElse("");
        assertFalse(accepted.isEmpty(), ENTRY_POINT + " must report the transport it accepted, so a start up log"
                + " records which configuration the instance is running with");

        for (String overclaim : COHERENCE_OVERCLAIMS) {
            assertFalse(accepted.toLowerCase(Locale.ROOT).contains(overclaim), ENTRY_POINT + " reports the accepted"
                    + " transport with the phrase [" + overclaim + "], which states more than the three checks"
                    + " establish: none of them looks anything up, authenticates, subscribes or publishes. Report"
                    + " what was checked and say what it does not prove. The message was: " + accepted);
        }
        assertTrue(accepted.contains("not evidence that an invalidation reaches another instance"), ENTRY_POINT
                + " must state, in the message an operator actually reads, that the accepted preconditions are not"
                + " evidence of propagation; a boundary recorded only in a comment is not read during an incident."
                + " The message was: " + accepted);
        assertTrue(accepted.contains("propagation must be confirmed against a second instance"), ENTRY_POINT
                + " must tell the operator what would establish coherence, because a disclaimer that leaves no next"
                + " step is ignored. The message was: " + accepted);

        // The reachability clause has to be READ OUT of the probe's own finding rather than written into this
        // line, because a line that states the finding in its own words can state one that did not happen.
        assertTrue(accepted.contains("$RESOLVED_TRANSPORT_PROBE_RESULT"), ENTRY_POINT + " must compose the"
                + " reachability clause of its acceptance message from RESOLVED_TRANSPORT_PROBE_RESULT, which the"
                + " probe publishes, so the line cannot claim a probe that did not happen. The message was: "
                + accepted);
        assertFalse(accepted.contains("accepted a TCP connection"), ENTRY_POINT + " states in its acceptance"
                + " message that an endpoint accepted a TCP connection. Written here it is unconditional, and a"
                + " provider URL naming no host and port reaches this line having probed nothing at all - so the"
                + " one line an operator reads would contradict the warning printed moments earlier. Report the"
                + " probe's own finding instead. The message was: " + accepted);
    }

    /**
     * AAP Objective 5 / B2-JMS-RES-02: asserts the reachability probe reports what it established and refuses a
     * provider URL through which no client could connect at all.
     *
     * <p>Three outcomes are possible and they must be three, not two. An endpoint that <em>answers</em> is the
     * finding the acceptance message may quote. A URL that names no TCP endpoint - {@code vm://localhost} is an
     * in-JVM broker, {@code discovery:(multicast://default)} finds its own - is well formed and legitimately
     * unprobeable, so the start proceeds, but nothing has confirmed that a broker answers and the log must say
     * exactly that. A URL such as {@code tcp://:61616} is neither: no client could connect through it, so the
     * instance would roll back the entity write behind every invalidation, and it used to be indistinguishable
     * from the unprobeable case - the start warned that it could not probe and then reported that an endpoint had
     * accepted a connection.</p>
     *
     * <p>The reachable leg binds a real listening socket rather than assuming any address answers, so the
     * acceptance clause is asserted against a probe that genuinely succeeded.</p>
     *
     * @param tempDir a JUnit-managed sandbox; nothing is written outside it
     * @throws Exception if the entry point could not be executed, which fails the test rather than being handled
     */
    @Test
    public void theReachabilityProbeReportsWhatItProbedAndRefusesAUrlNoClientCouldConnectThrough(
            @TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the container entry point");
        Path home = prepareEntryPointHome(tempDir);

        for (String unusable : List.of("tcp://:61616", "tcp://", "tcp://fd00::1:61616",
                "failover:(tcp://,tcp://b.example:61616)")) {
            ProbeRun refused = probeReachability(tempDir, home, unusable);

            assertNotEquals(0, refused.exitCode(), "the provider URL [" + unusable + "] names nothing a client"
                    + " could connect to, so it must be refused rather than reported as unprobeable. Output was:\n"
                    + refused.output());
            assertTrue(refused.output().contains("OFBIZ_JMS_PROVIDER_URL"), "the refusal must name the variable"
                    + " the operator has to correct. Output was:\n" + refused.output());
            assertFalse(refused.output().contains("accepted a TCP connection"), "a refused URL must not be"
                    + " reported as an endpoint that answered. Output was:\n" + refused.output());
        }

        for (String unprobeable : List.of("vm://localhost", "discovery:(multicast://default)")) {
            ProbeRun accepted = probeReachability(tempDir, home, unprobeable);

            assertEquals(0, accepted.exitCode(), "the provider URL [" + unprobeable + "] is well formed and names"
                    + " no TCP endpoint, which is a legitimate transport and must not stop the start. Output"
                    + " was:\n" + accepted.output());
            assertTrue(accepted.output().contains("NOTHING here has confirmed that a broker answers"),
                    "an unprobed transport must publish a finding that says so, or the acceptance message would"
                    + " read as a successful probe. Output was:\n" + accepted.output());
            assertFalse(accepted.output().contains("accepted a TCP connection"), "nothing was probed, so nothing"
                    + " may be reported as having accepted a connection. Output was:\n" + accepted.output());
        }

        // A socket that really is listening, so the accepting leg is asserted against a real probe.
        try (java.net.ServerSocket listener = new java.net.ServerSocket(0, 1,
                java.net.InetAddress.getLoopbackAddress())) {
            ProbeRun reachable = probeReachability(tempDir, home,
                    "tcp://127.0.0.1:" + listener.getLocalPort());

            assertEquals(0, reachable.exitCode(), "a listening endpoint must be accepted. Output was:\n"
                    + reachable.output());
            assertTrue(reachable.output().contains("accepted a TCP connection"),
                    "an endpoint that answered must be reported as having answered. Output was:\n"
                            + reachable.output());
            assertTrue(reachable.output().contains("PROBE_RESULT=[names the broker endpoint [127.0.0.1:"
                    + listener.getLocalPort() + "], which accepted a TCP connection"),
                    "the published finding must name the endpoint that answered, because that is the clause the"
                            + " acceptance message quotes. Output was:\n" + reachable.output());
        }
    }

    /**
     * AAP portability contract: asserts an operator-authored {@code /ofbiz/config/entityengine.xml} is neither
     * deleted nor rendered over, while the entry point's own render still is.
     *
     * <p>{@code docker/templates/postgres-entityengine.xml} documents that route in as many words: a deployment
     * needing a JDBC parameter, a datasource or a pool implementation this image does not template states it in
     * its own override and leaves {@code OFBIZ_POSTGRES_HOST} unset, so the mounted file stays first on the
     * class path. The entry point contradicted that - it removed {@code config/entityengine.xml}
     * unconditionally whenever nothing was configured, so withdrawing the managed database destroyed the
     * operator's configuration and the instance silently fell back to the committed embedded H2 datasource,
     * which is the one outcome a portability guarantee exists to prevent.</p>
     *
     * <p>Both directions are required, and the second is what makes the first safe. Preserving an unmarked file
     * is worthless if a later start renders over it, and rendering over it would be exactly as destructive as
     * deleting it - so a start that WOULD render is refused instead, naming the file. The generated leg is
     * asserted too, because a marker that stopped this script from removing its OWN output would leave a stale
     * managed configuration - the old host, the old credentials, possibly DDL still enabled - in force forever.</p>
     *
     * @param tempDir a JUnit-managed sandbox; nothing is written outside it
     * @throws Exception if the entry point could not be executed, which fails the test rather than being handled
     */
    @Test
    public void anOperatorAuthoredEntityEngineOverrideIsNeitherRemovedNorRenderedOver(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the container entry point");
        Path home = prepareEntryPointHome(tempDir);
        Path stateDir = tempDir.resolve("container_state");
        Path override = home.resolve(RENDERED_CONFIGURATION);
        String authored = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n"
                + "<entity-config>\n"
                + "    <!-- an operator's own datasource, carrying a JDBC parameter this image does not"
                + " template -->\n"
                + "</entity-config>\n";
        Files.writeString(override, authored, StandardCharsets.UTF_8);

        ProbeRun preserved = runEntryPointAllowingRefusal(tempDir, home,
                containerStatePreamble(stateDir) + RESOLVE_AND_CONFIGURE, Map.of());
        assertEquals(0, preserved.exitCode(), "a start that configures no database must succeed with an"
                + " operator-authored override present. Output was:\n" + preserved.output());
        assertEquals(authored, Files.readString(override, StandardCharsets.UTF_8),
                "an override this script did not generate must be left exactly as it is: it is the documented"
                        + " way to state configuration the template does not carry, and deleting it drops the"
                        + " deployment onto the committed embedded database");
        assertTrue(preserved.output().contains("OFBIZ-CONTAINER-GENERATED-ENTITY-ENGINE-CONFIGURATION")
                        && preserved.output().contains("is being kept"), "the start must report that it left the"
                + " file alone, and name the marker whose absence made it the operator's file, so an operator can"
                + " see which configuration is in force. Output was:\n" + preserved.output());

        ProbeRun refusedManaged = runEntryPointAllowingRefusal(tempDir, home,
                containerStatePreamble(stateDir) + RESOLVE_AND_CONFIGURE, MANAGED_DATABASE_ENVIRONMENT);
        assertNotEquals(0, refusedManaged.exitCode(), "a start that would RENDER over an operator-authored"
                + " override must be refused: rendering destroys it exactly as deleting it would. Output was:\n"
                + refusedManaged.output());
        assertEquals(authored, Files.readString(override, StandardCharsets.UTF_8),
                "the refusal must leave the file byte for byte as it was");
        assertTrue(refusedManaged.output().contains(RENDERED_CONFIGURATION),
                "the refusal must name the file. Output was:\n" + refusedManaged.output());

        // The embedded render is refused on the same grounds, driven directly because resolving the flag would
        // also invoke the transport validation - a different refusal, which would prove nothing here.
        ProbeRun refusedEmbedded = runEntryPointAllowingRefusal(tempDir, home,
                containerStatePreamble(stateDir) + "RESOLVED_DISTRIBUTED_CACHE_CLEAR=true\nconfigure_database\n",
                Map.of());
        assertNotEquals(0, refusedEmbedded.exitCode(), "the embedded render must be refused over an"
                + " operator-authored override too. Output was:\n" + refusedEmbedded.output());
        assertEquals(authored, Files.readString(override, StandardCharsets.UTF_8),
                "the embedded refusal must leave the file byte for byte as it was");

        // The other direction: this script's OWN render is still withdrawn when nothing is configured.
        Path generatedHome = prepareEntryPointHome(tempDir);
        Path generatedState = tempDir.resolve("generated_state");
        String generated = configureDatabase(tempDir, generatedHome, generatedState, null);
        Path generatedOverride = generatedHome.resolve(RENDERED_CONFIGURATION);
        assertTrue(Files.exists(generatedOverride), "the managed render must have produced an override. Output"
                + " was:\n" + generated);
        assertTrue(Files.readString(generatedOverride, StandardCharsets.UTF_8)
                        .contains("GENERATED BY docker-entrypoint.sh"),
                "every override this script renders must be stamped, or it cannot be told from an"
                        + " operator-authored one");

        ProbeRun withdrawn = runEntryPointAllowingRefusal(tempDir, generatedHome,
                containerStatePreamble(generatedState) + RESOLVE_AND_CONFIGURE, Map.of());
        assertEquals(0, withdrawn.exitCode(), "withdrawing the managed database must succeed. Output was:\n"
                + withdrawn.output());
        assertFalse(Files.exists(generatedOverride), "a stamped override is this script's own previous render"
                + " and must be removed when its configuration is withdrawn, or the instance keeps using the old"
                + " host and credentials - and, after an init-mode start, the startup DDL as well. Output was:\n"
                + withdrawn.output());
    }

    /**
     * Drives {@code require_transport_reachable} against one provider URL, tolerating a refusal.
     *
     * <p>{@link #runEntryPoint} asserts a zero exit, because every start it drives is one the entry point must
     * accept; this case is about the ones it must not, so the status is returned instead. The published finding
     * is printed after the call so that a run which proceeded can be asserted on as well as one that stopped.</p>
     *
     * @param workDir a per-test temporary directory for the generated library and driver
     * @param home the directory the entry point runs in
     * @param providerUrl the provider URL the resolved transport names
     * @return the exit status and combined output of the run
     * @throws Exception if the driver could not be written or executed
     */
    private static ProbeRun probeReachability(Path workDir, Path home, String providerUrl) throws Exception {
        return runEntryPointAllowingRefusal(workDir, home,
                "RESOLVED_TRANSPORT_CONFIGURATION_FILE='config/serviceengine.xml'\n"
                        + "RESOLVED_TRANSPORT_PROVIDER_URL=" + shellQuote(providerUrl) + "\n"
                        + "RESOLVED_JMS_CONNECT_TIMEOUT=2\n"
                        + "require_transport_reachable\n"
                        + "printf 'PROBE_RESULT=[%s]\\n' \"$RESOLVED_TRANSPORT_PROBE_RESULT\"\n",
                Map.of());
    }

    /**
     * Runs a fragment of shell against the real entry point and returns its status as well as its output.
     *
     * <p>{@link #runEntryPoint} asserts a zero exit, because every start it drives is one the entry point must
     * accept. The cases that assert a REFUSAL need the status instead, and they need the run not to fail the
     * test merely by being refused - which is what this returns.</p>
     *
     * @param workDir a per-test temporary directory for the generated library and driver
     * @param home the directory the entry point runs in, which it treats as the OFBiz home
     * @param body the shell to run once the entry point has been sourced
     * @param environment the variables to supply; every inherited {@code OFBIZ_} variable is removed first
     * @return the exit status and combined output of the run
     * @throws Exception if the driver could not be written or executed
     */
    private static ProbeRun runEntryPointAllowingRefusal(Path workDir, Path home, String body,
            Map<String, String> environment) throws Exception {
        Path library = workDir.resolve("entrypoint-library.sh");
        if (!Files.exists(library)) {
            List<String> sourced = new ArrayList<>();
            for (String line : Files.readAllLines(repositoryRoot().resolve(ENTRY_POINT), StandardCharsets.UTF_8)) {
                if (!"_main \"$@\"".equals(line)) {
                    sourced.add(line);
                }
            }
            Files.write(library, sourced, StandardCharsets.UTF_8);
        }
        Path driver = Files.createTempFile(workDir, "refusable", ".sh");
        Files.writeString(driver, "#!/usr/bin/env bash\n"
                + ". " + shellQuote(library) + "\n"
                + "cd " + shellQuote(home) + " || exit 1\n"
                + body, StandardCharsets.UTF_8);

        ShellDriver.Run run = ShellDriver.run(driver, home, environment, VERDICT_TIMEOUT_SECONDS);
        assertFalse(run.timedOut(), "the entry point did not terminate, output was:\n" + run.output());
        return new ProbeRun(run.exitCode(), run.output());
    }

    /** What one black-box execution of the reachability probe produced. */
    private record ProbeRun(int exitCode, String output) { }

    /**
     * AAP Objective 5 ordering contract: asserts the transport is rendered before it is judged, and on every start.
     *
     * <p>Both halves are positional, and both are the kind of regression a presence check would miss. The render must
     * precede {@code resolve_entity_engine_flags}, because that function is what invokes the transport check: rendering
     * afterwards would validate the <em>previous</em> start's configuration and could pass an instance whose current
     * settings were never checked. And the render must precede the {@code OFBIZ_SKIP_INIT} branch, because
     * {@code /ofbiz/config} is a declared volume — a transport override written by an earlier start survives into this
     * one, so withdrawing the configuration has to be able to remove it, which a render that never runs cannot do.</p>
     *
     * <p>The branch is located by its {@code RESOLVED_SKIP_INIT} test rather than by {@code OFBIZ_SKIP_INIT} directly,
     * for the reason given in
     * {@link #containerEntryPointCannotLetSkipInitBypassTheServingModeDdlCheck()}: the raw variable is normalised by
     * {@code resolve_skip_init} first, so that {@code OFBIZ_SKIP_INIT=false} means what it says.</p>
     */
    @Test
    public void containerEntryPointRendersTheCacheTransportBeforeItValidatesItAndOnEveryStart() {
        List<String> mainBody = containerMainBody();
        int resolveTransport = indexOfLine(mainBody, "resolve_cache_transport_configuration");
        int renderTransport = indexOfLine(mainBody, "render_cache_transport_configuration");
        int flagResolution = indexOfLine(mainBody, "resolve_entity_engine_flags");
        int skipBranch = indexOfLine(mainBody, "if [ \"$RESOLVED_SKIP_INIT\" = \"true\" ]; then");

        assertTrue(resolveTransport < renderTransport, ENTRY_POINT
                + " must resolve the transport settings before rendering them");
        assertTrue(renderTransport < flagResolution, ENTRY_POINT
                + " must render the transport before resolve_entity_engine_flags validates it, or the check judges"
                + " the previous start's configuration");
        assertTrue(renderTransport < skipBranch, ENTRY_POINT
                + " must render the transport on every start, including with OFBIZ_SKIP_INIT set: the configuration"
                + " volume outlives the container, so a stale override has to be corrected or removed");
    }

    /**
     * AAP section 0.2.1 boundary: asserts no write to the reference-only service-engine configuration appears in the entry point.
     *
     * <p>{@code framework/service/config/serviceengine.xml} is listed in the plan as a reference model that must not be
     * modified, and its commented {@code serviceMessenger} example is documentation the render is required to leave
     * intact. The transport is therefore rendered into {@code config/}, which the runtime class path places ahead of
     * {@code ofbiz.jar}, and the committed file is never a render destination.</p>
     */
    @Test
    public void containerEntryPointNeverWritesTheReferenceOnlyServiceEngineConfiguration() {
        String entryPoint = entryPointText();

        assertTrue(entryPoint.contains("SERVICE_ENGINE_SOURCE=\"framework/service/config/serviceengine.xml\""),
                ENTRY_POINT + " must keep reading the committed service-engine configuration as its source");
        assertTrue(entryPoint.contains("render_config_from \"$SERVICE_ENGINE_OVERRIDE\" \"$SERVICE_ENGINE_SOURCE\""),
                ENTRY_POINT + " must render the transport into the config/ override, from the pristine source");

        for (String line : entryPointLines()) {
            String statement = line.trim();
            if (statement.startsWith("#")) {
                continue;
            }
            assertFalse(statement.startsWith("render_config_from \"$SERVICE_ENGINE_SOURCE\"")
                    || statement.contains(">\"$SERVICE_ENGINE_SOURCE\"") || statement.contains("> \"$SERVICE_ENGINE_SOURCE\""),
                    ENTRY_POINT + " must never write the reference-only service-engine configuration: " + statement);
        }
    }

    /** The distribution's JNDI server catalogue, which the entry point reads as the source of its rendered override. */
    private static final String DISTRIBUTION_JNDI_SERVERS = "framework/base/config/jndiservers.xml";

    /** The distribution's JNDI client settings, which carry the process-wide JNDI default the render must preserve. */
    private static final String DISTRIBUTION_JNDI_PROPERTIES = "framework/base/config/jndi.properties";

    /** The name of the jndi-server the entry point renders for the cache-invalidation transport, and nothing else. */
    private static final String DEDICATED_JNDI_SERVER = "ofbizCacheTransport";

    /** The name of the parameterless jndi-server the distribution ships, which resolves through the JVM-wide default. */
    private static final String FALLBACK_JNDI_SERVER = "default";

    /**
     * AAP Objective 5 isolation contract: asserts the cache transport is scoped to a jndi-server of its own rather than
     * to the JNDI settings the rest of the process resolves through.
     *
     * <p>{@code JNDIContextFactory} has two branches. A jndi-server that carries a {@code context-provider-url} gets
     * {@code new InitialContext(environment)}, scoped to that server; one that carries none gets the bare
     * {@code new InitialContext()}, whose settings come from the merged {@code jndi.properties} on the class path. The
     * distribution's {@code default} server is deliberately the second kind, and it is not the only user of that bare
     * constructor: {@code CatalinaContainer} builds Tomcat's global naming context from one and treats a
     * {@code NamingException} as fatal, and {@code RmiServiceContainer} rebinds and looks up through one. Pointing the
     * process-wide settings at a message broker therefore does not merely reroute cache invalidation — it repurposes
     * unrelated infrastructure, and with a broker client that cannot satisfy those callers the instance does not start
     * at all.</p>
     *
     * <p>So the rendered {@code jms-service} must name a server of the first kind, created for this purpose, and that
     * name must not be {@code default}. Three further facts are asserted because each is load-bearing for the
     * arrangement: the rendered element must carry both scoping attributes, since it is the <em>presence</em> of
     * {@code context-provider-url} that selects the per-server branch; the anchor the element is inserted before must
     * be verified in the committed source, because a substitution that matched nothing would produce a file that looks
     * rendered and configures no transport; and the committed source must not already declare the dedicated name,
     * because {@code JNDIConfigUtil} loads servers with {@code putIfAbsent} and would keep whichever it read first.</p>
     */
    @Test
    public void containerEntryPointScopesTheCacheTransportToItsOwnJndiServer() {
        String entryPoint = entryPointText();

        assertTrue(entryPoint.contains("JNDI_SERVER_NAME='" + DEDICATED_JNDI_SERVER + "'"),
                ENTRY_POINT + " must render a jndi-server created for the cache transport alone");
        assertFalse(entryPoint.contains("JNDI_SERVER_NAME='" + FALLBACK_JNDI_SERVER + "'"),
                ENTRY_POINT + " must not render the transport onto the " + FALLBACK_JNDI_SERVER
                        + " jndi-server: that entry carries no parameters on purpose, so it resolves through the same"
                        + " JVM-wide settings Tomcat's global naming context and the RMI service container use");
        assertTrue(entryPoint.contains("JNDI_SERVER_DEFAULT_NAME='" + FALLBACK_JNDI_SERVER + "'"),
                ENTRY_POINT + " must keep naming the parameterless jndi-server separately, so that the"
                        + " render can prove it survived rather than merely intend to leave it alone");

        List<String> renderBody = entryPointFunctionBody("render_cache_transport_configuration");
        assertTrue(renderBody.stream().anyMatch(line -> line.contains("<server jndi-server-name=")
                && line.contains("$JNDI_SERVER_NAME")),
                ENTRY_POINT + " must point the rendered jms-service at the dedicated jndi-server");
        assertTrue(renderBody.stream().anyMatch(line -> line.contains("<jndi-server name=")
                && line.contains("$JNDI_SERVER_NAME")),
                ENTRY_POINT + " must declare the dedicated jndi-server it names");
        for (String scopingAttribute : new String[] {"context-provider-url=", "initial-context-factory="}) {
            assertTrue(renderBody.stream().anyMatch(line -> line.contains(scopingAttribute)),
                    ENTRY_POINT + " must write " + scopingAttribute + " onto the dedicated jndi-server;"
                            + " without context-provider-url JNDIContextFactory falls back to the bare context and"
                            + " resolves the broker from the process-wide settings after all");
        }

        // Each of these must match the COUNTING grep rather than merely the text of the message it guards: the
        // config_fatal calls in that function quote both the anchor and the file they are about, so an assertion that
        // did not insist on the grep would be satisfied by the error message of a check that no longer runs.
        List<String> anchorCheck = entryPointFunctionBody("require_transport_source_anchors");
        assertTrue(anchorCheck.stream().anyMatch(line -> line.contains("grep --count")
                && line.contains("'</jndi-config>'") && line.contains("$JNDI_SERVERS_SOURCE")),
                ENTRY_POINT + " must verify the insertion anchor in the committed jndi-server catalogue;"
                        + " a substitution that matched nothing would install a file that configures no transport");
        assertTrue(anchorCheck.stream().anyMatch(line -> line.contains("grep --count")
                && line.contains("<jndi-server") && line.contains("$JNDI_SERVER_NAME")
                && line.contains("$JNDI_SERVERS_SOURCE")),
                ENTRY_POINT + " must refuse a source that already declares the dedicated name, because"
                        + " JNDIConfigUtil loads servers with putIfAbsent and would keep whichever it read first");

        String distribution = repositoryFileText(DISTRIBUTION_JNDI_SERVERS);
        assertEquals("", jndiServerElement(distribution, DEDICATED_JNDI_SERVER), DISTRIBUTION_JNDI_SERVERS
                + " must not declare " + DEDICATED_JNDI_SERVER + ": the entry point adds that server, and a"
                + " duplicate would be resolved by load order rather than by configuration");
    }

    /**
     * AAP section 0.7.1 functional parity: asserts the transport render leaves the process-wide JNDI default exactly as
     * the distribution ships it.
     *
     * <p>{@code /ofbiz/config} is first on the runtime class path and the JDK merges {@code jndi.properties}
     * first-entry-wins, so a rendered {@code config/jndi.properties} that declares {@code java.naming.factory.initial}
     * or {@code java.naming.provider.url} does not add a setting — it replaces the setting for the whole JVM. Every
     * bare {@code new InitialContext()} in the process then resolves through a message broker's client, which is a
     * change to Tomcat's global naming context and to RMI service export, neither of which was configured and neither
     * of which is cache invalidation.</p>
     *
     * <p>The file is still rendered, because {@code JmsTopicListener} looks its connection factory and its topic up by
     * name and only the broker's own factory can resolve those, so the destination mapping has to reach it. What makes
     * that safe is that the render is <em>append-only</em>, and both halves of that claim are asserted here: no
     * substitution anywhere in the script may name {@code java.naming.factory.initial}, and the one substitution that
     * matches the provider URL line must re-emit it unchanged — the {@code &} in a {@code sed} replacement — so the
     * appended lines land among the active declarations without rewriting the anchor. It is then asserted that the
     * result is <em>verified</em> rather than merely intended, by requiring the post-render check on both keys; a
     * render is only append-only if a render that was not is discarded.</p>
     */
    @Test
    public void containerEntryPointLeavesTheProcessWideJndiDefaultsExactlyAsShipped() {
        String entryPoint = entryPointText();
        assertTrue(entryPoint.contains("JNDI_PROPERTIES_SOURCE=\"" + DISTRIBUTION_JNDI_PROPERTIES + "\""),
                ENTRY_POINT + " must keep reading the distribution's JNDI client settings as its source");
        assertTrue(entryPoint.contains("JNDI_PROPERTIES_OVERRIDE=\"config/jndi.properties\""),
                ENTRY_POINT + " must render the destination mapping into the config/ override");

        for (String line : entryPointLines()) {
            String statement = withoutShellEscapes(line.trim());
            if (statement.startsWith("#")) {
                continue;
            }
            assertFalse(statement.contains("s|") && statement.contains("java.naming.factory.initial"),
                    ENTRY_POINT + " must never substitute java.naming.factory.initial: it is the JVM-wide"
                            + " JNDI default, so the transport belongs on its own jndi-server: " + statement);
        }

        List<String> renderBody = entryPointFunctionBody("render_cache_transport_configuration");
        assertTrue(renderBody.stream()
                .map(SchemaInitGatingTests::withoutShellEscapes)
                .anyMatch(line -> line.contains("s|^java.naming.provider.url=.*|&")),
                ENTRY_POINT + " must append after the shipped provider URL rather than replace it; the '&'"
                        + " in the replacement is what re-emits that line unchanged");

        List<String> renderedCheck = entryPointFunctionBody("require_rendered_transport_declarations");
        for (String property : new String[] {"java.naming.factory.initial", "java.naming.provider.url"}) {
            assertTrue(renderedCheck.stream()
                    .map(SchemaInitGatingTests::withoutShellEscapes)
                    .anyMatch(line -> line.contains("require_rendered_declaration_unchanged")
                            && line.contains("'" + property + "'")),
                    ENTRY_POINT + " must verify that the render left " + property + " exactly as the source"
                            + " declares it, and discard the render otherwise; an unchecked intention is not a"
                            + " guarantee");
        }

        List<String> shipped = repositoryFileLines(DISTRIBUTION_JNDI_PROPERTIES);
        for (String property : new String[] {"java.naming.factory.initial=", "java.naming.provider.url="}) {
            assertEquals(1L, shipped.stream().filter(line -> line.startsWith(property)).count(),
                    DISTRIBUTION_JNDI_PROPERTIES + " must declare " + property + " exactly once: it is both the anchor"
                            + " the destination mapping is appended after and the value the render must preserve, and"
                            + " neither is decidable if it is declared twice or not at all");
        }
    }

    /**
     * AAP Objective 5 lifecycle contract: asserts the rendered jndi-server catalogue is managed across restarts like
     * every other generated override.
     *
     * <p>{@code JNDIConfigUtil} reads {@code jndiservers.xml} as a flat class path resource, so the override does not
     * extend the committed catalogue — it replaces it. Three consequences are asserted. It must be rendered from the
     * pristine committed file on every start, so that the servers the distribution ships are reproduced rather than
     * lost, and so that a changed broker takes effect on a restart instead of a stale render surviving. It must be
     * removable, because {@code /ofbiz/config} is a declared volume: withdrawing the transport variables has to
     * withdraw the transport, which a file left behind would not. And a file this script did not generate must stop
     * the render rather than be overwritten, because an operator-authored catalogue and the {@code OFBIZ_JMS_*}
     * variables are two sources of truth for one setting and silently preferring either is worse than refusing.</p>
     *
     * <p>Finally the post-render verification must actually run on it. {@code JNDIContextFactory} refuses to build a
     * context for a name it cannot find, so a {@code jms-service} naming a server the catalogue does not declare is a
     * start-up failure rather than a degraded mode.</p>
     */
    @Test
    public void containerEntryPointManagesTheRenderedJndiServerOverrideAcrossRestarts() {
        String entryPoint = entryPointText();

        assertTrue(entryPoint.contains("JNDI_SERVERS_SOURCE=\"" + DISTRIBUTION_JNDI_SERVERS + "\""),
                ENTRY_POINT + " must keep reading the distribution's jndi-server catalogue as its source");
        assertTrue(entryPoint.contains("JNDI_SERVERS_OVERRIDE=\"config/jndiservers.xml\""),
                ENTRY_POINT + " must render the dedicated jndi-server into the config/ override");
        assertTrue(entryPoint.contains("render_config_from \"$JNDI_SERVERS_OVERRIDE\" \"$JNDI_SERVERS_SOURCE\""),
                ENTRY_POINT + " must render the override from the pristine committed catalogue, never from"
                        + " the previous render: the override replaces that catalogue, so every shipped server has to"
                        + " be reproduced and a rotated setting has to take effect on a restart");

        for (String lifecycleGuard : new String[] {"remove_generated_transport_overrides",
                "require_no_unmanaged_transport_override"}) {
            assertTrue(entryPointFunctionBody(lifecycleGuard).stream().anyMatch(line -> line.contains("for file in")
                    && line.contains("$JNDI_SERVERS_OVERRIDE")),
                    ENTRY_POINT + " must include the rendered jndi-server catalogue in " + lifecycleGuard
                            + ": /ofbiz/config outlives the container, so an override left there keeps configuring a"
                            + " broker nobody asked for");
        }

        assertTrue(entryPointFunctionBody("require_rendered_transport_declarations").stream()
                .anyMatch(line -> "require_rendered_jndi_server".equals(line.trim())),
                ENTRY_POINT + " must verify the rendered jndi-server catalogue before accepting it");

        String distribution = repositoryFileText(DISTRIBUTION_JNDI_SERVERS);
        String fallback = jndiServerElement(distribution, FALLBACK_JNDI_SERVER);
        assertFalse(fallback.isEmpty(), DISTRIBUTION_JNDI_SERVERS + " must keep declaring the jndi-server named "
                + FALLBACK_JNDI_SERVER + ", which entityengine.xml's documented JNDI transaction factory names and"
                + " which the render is required to reproduce");
        assertFalse(fallback.contains("context-provider-url"), DISTRIBUTION_JNDI_SERVERS + " must keep the "
                + FALLBACK_JNDI_SERVER + " jndi-server parameterless, because that is what makes it resolve through"
                + " the bare InitialContext constructor; the render's guarantee is stated in those terms");
    }

    /**
     * AAP section 0.2.2 boundary: asserts no write to the distribution's JNDI configuration appears in the entry point.
     *
     * <p>Neither {@code framework/base/config/jndiservers.xml} nor {@code framework/base/config/jndi.properties}
     * appears in the plan's change surface, so both are out of scope for modification, and both are shared by the
     * whole distribution: they are read by the framework's unit tests, by a local {@code gradlew ofbiz} run and by
     * every other deployment of this checkout. The transport is therefore rendered into {@code config/}, which the
     * generated start script places ahead of {@code ofbiz.jar} on the class path, and the committed files stay
     * sources.</p>
     */
    @Test
    public void containerEntryPointNeverWritesTheDistributionJndiConfiguration() {
        for (String line : entryPointLines()) {
            String statement = line.trim();
            if (statement.startsWith("#")) {
                continue;
            }
            for (String source : new String[] {"$JNDI_SERVERS_SOURCE", "$JNDI_PROPERTIES_SOURCE"}) {
                boolean renderedOver = statement.startsWith("render_config_from \"" + source + "\"");
                boolean redirectedInto = statement.contains(">\"" + source + "\"")
                        || statement.contains("> \"" + source + "\"");
                boolean editedInPlace = statement.contains("--in-place") && statement.contains(source);
                assertFalse(renderedOver || redirectedInto || editedInPlace, ENTRY_POINT
                        + " must never write the distribution's JNDI configuration " + source
                        + ", which is shared by every deployment of this checkout: " + statement);
            }
        }
    }

    private static String entryPointText() {
        return repositoryFileText(ENTRY_POINT);
    }

    private static List<String> entryPointLines() {
        return repositoryFileLines(ENTRY_POINT);
    }

    private static String repositoryFileText(String relativePath) {
        return String.join("\n", repositoryFileLines(relativePath));
    }

    /**
     * Reads a committed file, addressed relative to the repository root, as lines.
     *
     * <p>The file is required to exist, because every assertion that reads one is a cross-artifact pairing: a file
     * that has been renamed or removed must fail here loudly rather than let a check that can no longer be made pass
     * by default.</p>
     *
     * @param relativePath the path of the file relative to the repository root
     * @return every line of the file, in order
     */
    private static List<String> repositoryFileLines(String relativePath) {
        Path file = repositoryRoot().resolve(relativePath);
        assertTrue(Files.isRegularFile(file), "missing " + file);
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read " + file, e);
        }
    }

    /**
     * Removes every backslash from a line of shell, so that a quoted regular expression or {@code sed} program can be
     * matched by what it means rather than by how many levels of quoting it happened to be written through.
     *
     * <p>{@code 'java\.naming\.factory\.initial'} and {@code "s|^java\\.naming\\.provider\\.url=.*|&|"} are the same
     * property name expressed at two different quoting depths; both reduce to plain dots here. This is deliberately a
     * blunt transformation rather than a shell parser: it is used only to recognise a property name and a substitution
     * shape, and over-simplifying can only make a match easier to obtain, never a violation easier to hide.</p>
     *
     * @param line the line of shell to reduce
     * @return the line with all backslashes removed
     */
    private static String withoutShellEscapes(String line) {
        return line.replace("\\", "");
    }

    /**
     * Extracts the start tag of the {@code <jndi-server>} element with the given name from an XML document, collapsed
     * onto one line, or the empty string when no such element is declared.
     *
     * <p>This mirrors the entry point's own {@code jndi_server_element} helper, and for the same two reasons: the
     * shipped elements spread their attributes over as many as six lines, so any attribute lookup has to see the start
     * tag as a single string; and a commented-out element must not be found, because the engine does not read one.</p>
     *
     * @param xml the document text
     * @param name the {@code name} attribute to look for
     * @return the collapsed start tag, or an empty string
     */
    private static String jndiServerElement(String xml, String name) {
        Matcher elements = Pattern.compile("<jndi-server[^>]*>", Pattern.DOTALL)
                .matcher(xml.replaceAll("(?s)<!--.*?-->", " "));
        while (elements.find()) {
            String tag = elements.group().replaceAll("\\s+", " ");
            if (tag.contains(" name=\"" + name + "\"")) {
                return tag;
            }
        }
        return "";
    }

    /**
     * Extracts the body of the entry point's {@code _main} function.
     *
     * <p>Only {@code _main} is considered, because the assertions above are about the ORDER in which the container
     * flow performs its steps. Matching the whole file would let a mention inside a comment or another function
     * satisfy a position check.</p>
     *
     * @return the lines of {@code _main}, from its declaration to its closing brace
     */
    private static List<String> containerMainBody() {
        return entryPointFunctionBody("_main");
    }

    /**
     * Extracts the body of one named shell function of the entry point.
     *
     * <p>Confining a search to a single function is what makes an assertion about it trustworthy. Matching the whole
     * file would let a mention inside a comment, inside the prose of another function or inside an unrelated helper
     * satisfy the check — and for the profile assertions below, a single stray match anywhere else in a 4000-line
     * script would make the test either vacuous or permanently red.</p>
     *
     * <p>The declaration is matched at column zero and the body ends at the first closing brace at column zero, which
     * is the layout every function in this script uses; a reformat that broke it would fail here loudly rather than
     * silently returning the wrong range.</p>
     *
     * @param name the shell function name, without parentheses
     * @return the lines of that function, from its declaration to its closing brace
     */
    private static List<String> entryPointFunctionBody(String name) {
        List<String> lines = entryPointLines();
        String declaration = name + "() {";
        int start = lines.indexOf(declaration);
        assertTrue(start >= 0, ENTRY_POINT + " must declare a " + name + " function at column zero");

        int end = -1;
        for (int index = start + 1; index < lines.size(); index++) {
            if ("}".equals(lines.get(index))) {
                end = index;
                break;
            }
        }
        assertTrue(end > start, ENTRY_POINT + " must close its " + name + " function");
        return lines.subList(start, end);
    }

    /**
     * Finds the LAST line of {@code _main} that is exactly the given call, ignoring indentation.
     *
     * <p>Exact matching after trimming is deliberate: a substring search for {@code load_data} would also match
     * {@code load_data_something}, and a search that ignored comments would match the prose above each step.</p>
     *
     * @param lines the lines to search
     * @param statement the statement to locate
     * @return the zero-based index of the LAST such statement
     */
    private static int lastIndexOfLine(List<String> lines, String statement) {
        for (int index = lines.size() - 1; index >= 0; index--) {
            if (statement.equals(lines.get(index).trim())) {
                return index;
            }
        }
        throw new AssertionError(ENTRY_POINT + " _main does not contain the statement: " + statement);
    }

    private static int indexOfLine(List<String> lines, String statement) {
        for (int index = 0; index < lines.size(); index++) {
            if (statement.equals(lines.get(index).trim())) {
                return index;
            }
        }
        throw new AssertionError(ENTRY_POINT + " _main does not contain the statement: " + statement);
    }

    /**
     * Locates the repository root by walking up from the working directory until the dependency manifest is found.
     *
     * @return the repository root
     */
    private static Path repositoryRoot() {
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve(REPOSITORY_MARKER))) {
            candidate = candidate.getParent();
        }
        assertNotNull(candidate, "could not locate the repository root from " + Paths.get("").toAbsolutePath());
        return candidate;
    }

    // Helpers of the entry-point driven schema-mode assertion above. They execute the real
    // docker/docker-entrypoint.sh as a library inside a sandbox that mimics the container's /ofbiz
    // layout, so what they observe is the rendered artefact a deployment reads rather than a
    // restatement of the committed file.
    /**
     * Runs one container start's worth of database configuration: the entry point resolves the mode
     * flags from the environment and then configures the database, which reconciles the state marker
     * against the previous start, renders the deployed profile and records the mode it rendered.
     *
     * <p>The container state directory is redirected into the sandbox, along with the three paths
     * derived from it, so the marker files this start reads and writes are the sandbox's own and no
     * state on the build host is involved.</p>
     * @param workDir a per-test temporary directory
     * @param home the sandbox that stands in for the container's {@code /ofbiz}
     * @param stateDir the sandbox's container state directory, created if absent
     * @param schemaInitValue the value of {@code OFBIZ_SCHEMA_INIT}, or {@code null} to leave it unset
     * @return the combined output of the run
     * @throws Exception if the entry point could not be executed
     */
    private static String configureDatabase(Path workDir, Path home, Path stateDir, String schemaInitValue)
            throws Exception {
        Map<String, String> environment = new LinkedHashMap<>(MANAGED_DATABASE_ENVIRONMENT);
        if (schemaInitValue != null) {
            environment.put("OFBIZ_SCHEMA_INIT", schemaInitValue);
        }
        return runEntryPoint(workDir, home, containerStatePreamble(stateDir) + RESOLVE_AND_CONFIGURE, environment);
    }

    /**
     * Redirects the container state directory, and the three markers derived from it, into the sandbox. Every
     * start driven here shares this preamble, so no state on the build host is read or written.
     * @param stateDir the sandbox's container state directory, created by the preamble if absent
     * @return the shell that redirects the state, ready to be followed by the steps of a start
     */
    private static String containerStatePreamble(Path stateDir) {
        return "CONTAINER_STATE_DIR=" + shellQuote(stateDir) + "\n"
                + "CONTAINER_DATA_LOADED=\"$CONTAINER_STATE_DIR/" + LOAD_MARKERS.get(0) + "\"\n"
                + "CONTAINER_ADMIN_LOADED=\"$CONTAINER_STATE_DIR/" + LOAD_MARKERS.get(1) + "\"\n"
                + "CONTAINER_DB_CONFIG_APPLIED=\"$CONTAINER_STATE_DIR/" + STATE_MARKER + "\"\n"
                + "mkdir --parents \"$CONTAINER_STATE_DIR\"\n";
    }

    /**
     * Runs a whole one-shot initialisation execution's worth of configuration: the entry point resolves
     * {@code OFBIZ_SCHEMA_INIT=true}, renders the DDL-enabled configuration, and then hands the configuration
     * volume back in serving mode exactly as {@code _main} does once the schema has been applied.
     *
     * <p>The rendered configuration is copied aside between those two steps, because the DDL-enabled file
     * exists only for the length of the execution that applies the schema: a test that looked at it afterwards
     * would find the restored one and could not tell the two modes apart. The requested mode is printed at the
     * end so the caller can check the restoration left it alone.</p>
     * @param workDir a per-test temporary directory
     * @param home the sandbox that stands in for the container's {@code /ofbiz}
     * @param stateDir the sandbox's container state directory
     * @param midInitialisation where to copy the configuration the initialisation itself ran with
     * @return the combined output of the run
     * @throws Exception if the entry point could not be executed
     */
    private static String initialiseAndRestoreServingMode(Path workDir, Path home, Path stateDir,
            Path midInitialisation) throws Exception {
        String body = containerStatePreamble(stateDir)
                + RESOLVE_AND_CONFIGURE
                + "cp " + shellQuote(home.resolve(RENDERED_CONFIGURATION)) + " "
                + shellQuote(midInitialisation) + "\n"
                + "restore_serving_mode_after_schema_init\n"
                + "printf 'RESOLVED_SCHEMA_INIT=%s\\n' \"$RESOLVED_SCHEMA_INIT\"\n";

        Map<String, String> environment = new LinkedHashMap<>(MANAGED_DATABASE_ENVIRONMENT);
        environment.put("OFBIZ_SCHEMA_INIT", "true");
        return runEntryPoint(workDir, home, body, environment);
    }

    /**
     * The entity groups the rendered {@code default} delegator maps, each with the datasource it maps it to.
     *
     * <p>Read from the render rather than from the committed configuration because the render is what a
     * deployed instance loads, and because the deployed profile's group-maps are the whole point of the
     * postcondition these feed: taking them from the committed file would judge the embedded profile.</p>
     * @param home the sandbox the entry point rendered into
     * @return group name to datasource name, in document order
     * @throws Exception if the rendered configuration could not be read or parsed
     */
    private static Map<String, String> renderedDefaultDelegatorGroupMaps(Path home) throws Exception {
        Element delegator = declaration(parse(home.resolve(RENDERED_CONFIGURATION)), "delegator", "default");
        Map<String, String> groupMaps = new LinkedHashMap<>();
        NodeList maps = delegator.getElementsByTagName("group-map");
        for (int index = 0; index < maps.getLength(); index++) {
            Element map = (Element) maps.item(index);
            groupMaps.put(map.getAttribute("group-name"), map.getAttribute("datasource-name"));
        }
        assertFalse(groupMaps.isEmpty(), "the rendered 'default' delegator declares no group-map at all, so"
                + " there is nothing for the helper postcondition to be taken against");
        return groupMaps;
    }

    /**
     * The entity groups {@code ModelGroupReader.getGroupNames} would return for the rendered {@code default}
     * delegator: the groups the loaded components declare, plus that delegator's own default group.
     *
     * <p>This is the set that decides which helper initialisations the engine performs, and it is emphatically
     * not the set of groups the delegator <i>maps</i> — which is the assumption a postcondition reading the delegator's map
     * would make. A component is counted when it has the descriptor that makes it loadable and that
     * descriptor registers a group reader, because that registration is what {@code ModelGroupReader} follows;
     * naming a component in {@code excludedComponents} withdraws exactly that descriptor, which is the same
     * way a deployment leaves a plugin out.</p>
     * @param home the sandbox the entry point rendered into, whose render supplies the default group
     * @param excludedComponents component paths relative to the repository root, as {@code plugins/bi}
     * @return the group names, sorted so a failure message reads the same way twice
     * @throws Exception if the render, a component descriptor or a group descriptor could not be read
     */
    private static Set<String> consideredEntityGroups(Path home, Set<String> excludedComponents) throws Exception {
        Element delegator = declaration(parse(home.resolve(RENDERED_CONFIGURATION)), "delegator", "default");
        String declaredDefault = delegator.getAttribute("default-group-name");
        Set<String> considered = new TreeSet<>();
        considered.add(declaredDefault.isEmpty() ? DEFAULT_ENTITY_GROUP : declaredDefault);

        Pattern declaration = Pattern.compile("group=\"([^\"]+)\"");
        for (String root : COMPONENT_ROOTS) {
            Path start = repositoryRoot().resolve(root);
            if (!Files.isDirectory(start)) {
                continue;
            }
            List<Path> descriptors = new ArrayList<>();
            try (Stream<Path> tree = Files.walk(start, COMPONENT_DESCRIPTOR_DEPTH)) {
                tree.filter(path -> path.endsWith(ENTITY_GROUP_DESCRIPTOR)).forEach(descriptors::add);
            }
            for (Path descriptor : descriptors) {
                Path component = descriptor.getParent().getParent();
                if (excludedComponents.contains(root + "/" + component.getFileName())
                        || !Files.isRegularFile(component.resolve(COMPONENT_DESCRIPTOR))
                        || !Files.readString(component.resolve(COMPONENT_DESCRIPTOR), StandardCharsets.UTF_8)
                                .contains("type=\"group\"")) {
                    continue;
                }
                Matcher declared = declaration.matcher(Files.readString(descriptor, StandardCharsets.UTF_8));
                while (declared.find()) {
                    considered.add(declared.group(1));
                }
            }
        }
        return considered;
    }

    /**
     * The loader log a run against these group-maps produces when it initialises every group it has an entity
     * for, and nothing else.
     *
     * <p>The per-group line is reproduced character for character as {@code GenericDelegator} logs it, verified
     * against a captured {@code --load-data readers=none} run of this checkout. A group the delegator maps but
     * no loaded component declares produces no line at all — neither an initialisation nor a refusal — which is
     * the observation the postcondition has to tolerate and is why such groups are skipped here.</p>
     * @param groupMaps the group-maps the configuration declares
     * @param consideredGroups the groups the loaded components bring into play
     * @return the log text
     */
    private static String loaderLogFor(Map<String, String> groupMaps, Set<String> consideredGroups) {
        StringBuilder log = new StringBuilder("Loading data using delegator 'default'\n");
        for (Map.Entry<String, String> groupMap : groupMaps.entrySet()) {
            if (consideredGroups.contains(groupMap.getKey())) {
                log.append("Delegator \"default\" initializing helper \"").append(groupMap.getValue())
                        .append("\" for entity group \"").append(groupMap.getKey()).append("\".\n");
            }
        }
        return log.append("=-=-=-=-=-=-= Finished the data load with 0 rows changed.\n").toString();
    }

    /**
     * The line the delegator logs when it will not associate a group with itself, character for character as
     * {@code GenericDelegator} emits it. This is the one outcome that is positive evidence of a group the
     * configuration maps having had nothing done for it.
     * @param group the entity group the delegator refused
     * @return the log line, newline terminated
     */
    private static String refusalLine(String group) {
        return "Delegator \"default\" NOT initializing helper for entity group \"" + group
                + "\" because the group is not associated to this delegator.\n";
    }

    /**
     * Drives the entry point's real helper postcondition against a captured loader log and returns its verdict:
     * empty when the log carries the completion evidence it requires, otherwise the refusal the initialisation would fail on.
     *
     * <p>Nothing tells the function where the configuration is: {@code ENTITY_ENGINE_OVERRIDE} is the relative
     * path the entry point itself sets and every run here executes from the sandbox home, so what the verdict
     * is taken against is the render a deployed instance would read, resolved the way the entry point resolves
     * it. The verdict is printed between sentinels because it is prose that may itself contain punctuation.</p>
     * @param workDir a per-test temporary directory
     * @param home the sandbox that stands in for the container's {@code /ofbiz}, already rendered into
     * @param log the loader log to take the verdict against
     * @return the verdict, trimmed, empty when the postcondition is satisfied
     * @throws Exception if the entry point could not be executed
     */
    private static String helperVerdict(Path workDir, Path home, String log) throws Exception {
        Path capture = Files.createTempFile(workDir, "loader", ".log");
        Files.writeString(capture, log, StandardCharsets.UTF_8);
        String body = "printf 'VERDICT-BEGIN\\n%s\\nVERDICT-END\\n'"
                + " \"$(schema_init_helper_verdict " + shellQuote(capture) + ")\"\n";
        String output = runEntryPoint(workDir, home, body, Map.of());

        int begin = output.indexOf("VERDICT-BEGIN\n");
        int end = output.lastIndexOf("\nVERDICT-END");
        assertTrue(begin >= 0 && end > begin, "the helper postcondition printed no delimited verdict, so it"
                + " could not be executed at all. Its output was:\n" + output);
        return output.substring(begin + "VERDICT-BEGIN\n".length(), end).trim();
    }

    /**
     * Asserts the schema mode the rendered configuration resolves to, and that it resolves to
     * anything at all: the file must exist, must carry no placeholder of the complete token grammar,
     * must give both DDL attributes of all three managed datasources the expected literal, and must
     * leave the embedded H2 datasources on their start up DDL whatever the managed mode is.
     * @param home the sandbox the entry point rendered into
     * @param expectedMode the literal both DDL attributes of the managed datasources must carry
     * @param context how this render was requested, for the failure messages
     * @throws Exception if the rendered configuration could not be read or parsed
     */
    private static void assertRenderedSchemaMode(Path home, String expectedMode, String context) throws Exception {
        assertSchemaModeOfRenderedFile(home.resolve(RENDERED_CONFIGURATION), expectedMode, context);
    }

    /**
     * The same assertion against a named file rather than against the sandbox's current render, so the
     * configuration an execution ran with can be asserted after that execution has replaced it.
     * @param renderedFile the file to read
     * @param expectedMode the literal both DDL attributes of the managed datasources must carry
     * @param context how this render was requested, for the failure messages
     * @throws Exception if the rendered configuration could not be read or parsed
     */
    private static void assertSchemaModeOfRenderedFile(Path renderedFile, String expectedMode, String context)
            throws Exception {
        assertTrue(Files.isRegularFile(renderedFile),
                "the entry point rendered no " + renderedFile.getFileName() + " " + context);
        assertEquals(List.of(), placeholdersIn(Files.readString(renderedFile, StandardCharsets.UTF_8)),
                "the configuration rendered " + context + " still carries unsubstituted placeholders");

        Document rendered = parse(renderedFile);
        for (String datasourceName : MANAGED_DATASOURCES) {
            Element managed = declaration(rendered, "datasource", datasourceName);
            assertEquals(expectedMode, managed.getAttribute("check-on-start"), datasourceName + " must resolve"
                    + " check-on-start to the literal " + expectedMode + " " + context);
            assertEquals(expectedMode, managed.getAttribute("add-missing-on-start"), datasourceName + " must"
                    + " resolve add-missing-on-start to the literal " + expectedMode + " " + context);
        }
        for (String datasourceName : EMBEDDED_DATASOURCES) {
            Element embedded = declaration(rendered, "datasource", datasourceName);
            assertEquals("true", embedded.getAttribute("check-on-start"), datasourceName + " must keep checking"
                    + " the schema on start up " + context + ": H2 is dev and test only and never joins the fleet");
            assertEquals("true", embedded.getAttribute("add-missing-on-start"), datasourceName + " must keep"
                    + " adding missing schema objects " + context + ", so the H2 path stays zero-configuration");
        }
    }

    /**
     * The schema mode the database state marker records, which is what makes a mode change detectable
     * on a reused state volume. A marker that records no mode at all fails rather than being read as
     * an absent transition.
     * @param stateDir the sandbox's container state directory
     * @return the recorded value of the mode field
     * @throws IOException if the marker could not be read
     */
    private static String recordedSchemaMode(Path stateDir) throws IOException {
        Path marker = stateDir.resolve(STATE_MARKER);
        assertTrue(Files.isRegularFile(marker), "the entry point recorded no database state marker at " + marker);

        String recorded = null;
        for (String line : Files.readAllLines(marker, StandardCharsets.UTF_8)) {
            if (line.startsWith(SCHEMA_MODE_FIELD)) {
                recorded = line.substring(SCHEMA_MODE_FIELD.length());
            }
        }
        assertNotNull(recorded, "the database state marker " + marker + " records no '" + SCHEMA_MODE_FIELD
                + "' field, so a schema-mode change on a reused volume could not be detected");
        return recorded;
    }

    /**
     * Builds a sandbox with the same relative layout the image lays down under {@code /ofbiz}: the
     * template where {@code COPY docker/templates templates} puts it, and the generated-configuration
     * directory the renderer writes into.
     * @param workDir a per-test temporary directory
     * @return the directory the entry point will treat as the OFBiz home
     * @throws IOException if the sandbox could not be created
     */
    private static Path prepareEntryPointHome(Path workDir) throws IOException {
        Path home = Files.createTempDirectory(workDir, "ofbiz-home");
        Path template = home.resolve(TEMPLATE_IN_HOME);
        Files.createDirectories(template.getParent());
        Files.copy(repositoryRoot().resolve(POSTGRES_TEMPLATE), template);
        Files.createDirectories(home.resolve("config"));
        return home;
    }

    /**
     * AAP 0.6.4, for a configuration this container did not render: an operator-owned external datasource must
     * state BOTH startup DDL flags as the exact literal {@code false}, or the start is refused.
     *
     * <p>{@code /ofbiz/config} is a declared volume, and the documented way to run against MySQL, Oracle or a
     * PostgreSQL this image does not parameterise is to mount an {@code entityengine.xml} there and leave the
     * managed-database variables unset. Nothing renders that file, so nothing else has checked its DDL posture -
     * which makes this gate the only thing standing between a mounted configuration and a whole fleet issuing
     * {@code CREATE} and {@code ALTER} against one shared database.
     *
     * <p>All nine combinations are driven, because the two flags fail in different ways and only one of the nine
     * is the posture the plan names:
     *
     * <ul>
     * <li>An ABSENT {@code check-on-start} reads as ENABLED - {@code Datasource.java} parses it as
     * {@code !"false".equals(value)} - so every instance reads the whole schema on every boot and needs metadata
     * privileges the fleet is not meant to hold.</li>
     * <li>An {@code add-missing-on-start} of {@code "true"} under a {@code check-on-start} of {@code "false"}
     * issues nothing today, only because the engine consults the second flag solely when the first is enabled.
     * It is a declared intent to create objects, one edit away from a fleet that does.</li>
     * </ul>
     *
     * <p>So "would any DDL happen right now" is deliberately NOT the test. Both flags must say so.
     *
     * @param workDir a per-test temporary directory
     * @throws Exception if the entry point could not be executed
     */
    @Test
    public void anOperatorOwnedExternalDatasourceMustStateBothDdlFlagsFalse(@TempDir Path workDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "the entry point is shell, so these checks need bash");
        Path home = prepareEntryPointHome(workDir);

        for (String checkOnStart : List.of("false", "true", ABSENT_ATTRIBUTE)) {
            for (String addMissing : List.of("false", "true", ABSENT_ATTRIBUTE)) {
                writeExternalDatasourceConfiguration(home, "jdbc:postgresql://db.internal:5432/ofbiz",
                        checkOnStart, addMissing);
                ProbeRun run = servingDdlPostureVerdict(workDir, home, false);
                String posture = "check-on-start=" + checkOnStart + " add-missing-on-start=" + addMissing;

                if ("false".equals(checkOnStart) && "false".equals(addMissing)) {
                    assertEquals(0, run.exitCode(), "the one posture AAP 0.6.4 names for run mode must be"
                            + " accepted [" + posture + "]. Output was:\n" + run.output());
                    assertTrue(run.output().contains(GATE_ACCEPTED), "an accepted posture must let the start"
                            + " continue [" + posture + "]. Output was:\n" + run.output());
                    continue;
                }

                assertNotEquals(0, run.exitCode(), "a serving instance must not start from a non-embedded"
                        + " datasource whose DDL posture is [" + posture + "], because nothing else has"
                        + " checked it. Output was:\n" + run.output());
                assertFalse(run.output().contains(GATE_ACCEPTED), "a refused start must not continue ["
                        + posture + "]. Output was:\n" + run.output());
                // The refusal has to name the flag that is wrong, or an operator cannot act on it. The
                // severest case - the schema check on AND missing objects to be added - is reported as DDL
                // being enabled rather than as one attribute being unset.
                String expected;
                if (!"false".equals(checkOnStart) && "true".equals(addMissing)) {
                    expected = "leaves startup DDL enabled";
                } else if (!"false".equals(checkOnStart)) {
                    expected = "does not set check-on-start=\"false\"";
                } else {
                    expected = "does not set add-missing-on-start=\"false\"";
                }
                assertTrue(run.output().contains(expected), "the refusal for [" + posture + "] must say ["
                        + expected + "]. Output was:\n" + run.output());
                assertTrue(run.output().contains("supplied by this deployment"), "the refusal must name whose"
                        + " file it is, because a mounted file is corrected by its author and a render is"
                        + " corrected by re-rendering. Output was:\n" + run.output());
            }
        }
    }

    /**
     * The DDL posture is judged for a SHARED database and for nothing else: an embedded datasource keeps its
     * startup DDL, and an H2 reached over the network does not count as embedded.
     *
     * <p>This is the shape of the guarantee rather than an exception to it. An embedded database is a file on
     * one container's own volume: it is single-instance by construction, it never joins a fleet, and applying
     * the entity model to it on every boot is exactly what makes an unconfigured checkout run with nothing
     * configured (AAP 0.6.4, 0.7.1). The hazard is a database several instances would issue {@code CREATE} and
     * {@code ALTER} against, which is every non-embedded one - so {@code jdbc:h2:tcp://} is judged even though
     * {@code jdbc:h2:} alone is not.
     *
     * @param workDir a per-test temporary directory
     * @throws Exception if the entry point could not be executed
     */
    @Test
    public void onlyASharedDatabaseIsJudgedAndAnEmbeddedOneKeepsItsStartupDdl(@TempDir Path workDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "the entry point is shell, so these checks need bash");
        Path home = prepareEntryPointHome(workDir);

        writeExternalDatasourceConfiguration(home, "jdbc:h2:./runtime/data/h2/ofbiz", "true", "true");
        ProbeRun embedded = servingDdlPostureVerdict(workDir, home, false);
        assertEquals(0, embedded.exitCode(), "an embedded datasource is single-instance by construction and"
                + " keeps its startup DDL, which is what makes a zero-configuration run work. Output was:\n"
                + embedded.output());

        writeExternalDatasourceConfiguration(home, "jdbc:h2:tcp://h2.internal:9092/ofbiz", "true", "true");
        ProbeRun remote = servingDdlPostureVerdict(workDir, home, false);
        assertNotEquals(0, remote.exitCode(), "an H2 reached over the network is a database several instances"
                + " share, so it must be judged exactly like PostgreSQL. Output was:\n" + remote.output());

        writeExternalDatasourceConfiguration(home, "jdbc:h2:tcp://h2.internal:9092/ofbiz", "false", "false");
        ProbeRun stated = servingDdlPostureVerdict(workDir, home, false);
        assertEquals(0, stated.exitCode(), "and it must be accepted once it states the run-mode posture, or"
                + " the refusal above would be about the dialect rather than about the posture. Output was:\n"
                + stated.output());
    }

    /**
     * Init mode is the one execution that may carry the DDL posture the serving gate refuses.
     *
     * <p>{@code OFBIZ_SCHEMA_INIT=true} exists to apply the entity model exactly once, so it renders both flags
     * true on purpose and cannot be judged by the gate that refuses them. The pair below is what makes the
     * exemption meaningful: the SAME configuration is accepted in init mode and refused in serving mode, so the
     * exemption is the mode and not the file.
     *
     * @param workDir a per-test temporary directory
     * @throws Exception if the entry point could not be executed
     */
    @Test
    public void theServingDdlGateExemptsInitModeAndOnlyInitMode(@TempDir Path workDir) throws Exception {
        assumeTrue(isBashAvailable(), "the entry point is shell, so these checks need bash");
        Path home = prepareEntryPointHome(workDir);
        writeExternalDatasourceConfiguration(home, "jdbc:postgresql://db.internal:5432/ofbiz", "true", "true");

        ProbeRun initialising = servingDdlPostureVerdict(workDir, home, true);
        assertEquals(0, initialising.exitCode(), "init mode is the execution that applies the schema, so the"
                + " DDL posture it needs must not be refused. Output was:\n" + initialising.output());

        ProbeRun serving = servingDdlPostureVerdict(workDir, home, false);
        assertNotEquals(0, serving.exitCode(), "the same configuration must be refused when the instance is"
                + " about to serve, or the exemption would be about the file rather than the mode. Output"
                + " was:\n" + serving.output());
    }

    /**
     * Writes an operator-owned {@code config/entityengine.xml}: no generated marker, serving delegators mapped
     * to one datasource of the caller's choosing, and exactly the DDL attributes asked for.
     *
     * <p>Unmarked on purpose. The marker is what tells the entry point a file is its own render, so a file
     * without one is the deployment's own configuration - the case that nothing else in this script has checked
     * and the case this gate exists for.
     *
     * @param home the sandbox that stands in for the container's {@code /ofbiz}
     * @param jdbcUri the connection URI, which decides whether the datasource counts as embedded
     * @param checkOnStart the {@code check-on-start} literal, or {@link #ABSENT_ATTRIBUTE} to omit it
     * @param addMissingOnStart the {@code add-missing-on-start} literal, or {@link #ABSENT_ATTRIBUTE} to omit it
     * @throws IOException if the configuration could not be written
     */
    private static void writeExternalDatasourceConfiguration(Path home, String jdbcUri, String checkOnStart,
            String addMissingOnStart) throws IOException {
        StringBuilder attributes = new StringBuilder();
        if (!ABSENT_ATTRIBUTE.equals(checkOnStart)) {
            attributes.append("\n            check-on-start=\"").append(checkOnStart).append('"');
        }
        if (!ABSENT_ATTRIBUTE.equals(addMissingOnStart)) {
            attributes.append("\n            add-missing-on-start=\"").append(addMissingOnStart).append('"');
        }
        String configuration = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<entity-config>\n"
                + "    <delegator name=\"default\" entity-model-reader=\"main\" entity-group-reader=\"main\""
                + " entity-eca-reader=\"main\">\n"
                + "        <group-map group-name=\"org.apache.ofbiz\" datasource-name=\"deploymentdb\"/>\n"
                + "    </delegator>\n"
                + "    <delegator name=\"default-no-eca\" entity-model-reader=\"main\""
                + " entity-group-reader=\"main\" entity-eca-enabled=\"false\">\n"
                + "        <group-map group-name=\"org.apache.ofbiz\" datasource-name=\"deploymentdb\"/>\n"
                + "    </delegator>\n"
                + "    <datasource name=\"deploymentdb\""
                + "\n            helper-class=\"org.apache.ofbiz.entity.datasource.GenericHelperDAO\""
                + "\n            field-type-name=\"postgres\"" + attributes + ">\n"
                + "        <inline-jdbc jdbc-driver=\"org.postgresql.Driver\" jdbc-uri=\"" + jdbcUri + "\""
                + " jdbc-username=\"deployment\" jdbc-password=\"deployment\"/>\n"
                + "    </datasource>\n"
                + "</entity-config>\n";
        Path override = home.resolve(RENDERED_CONFIGURATION);
        Files.createDirectories(override.getParent());
        Files.writeString(override, configuration, StandardCharsets.UTF_8);
    }

    /**
     * Drives the entry point's real serving-mode DDL gate against whatever configuration the sandbox holds.
     *
     * <p>The two variables the function reads are set the way {@code _main} sets them, and nothing else is
     * stubbed: the group maps, the datasource lookup and the embedded-URI classification all run for real. A
     * sentinel is printed after the call so that a run which was ALLOWED to continue can be told from one that
     * was refused, without inferring it from the exit status alone.
     *
     * @param workDir a per-test temporary directory
     * @param home the sandbox the configuration was written into
     * @param initialising whether the run is an {@code OFBIZ_SCHEMA_INIT=true} execution
     * @return the exit status and combined output of the run
     * @throws Exception if the entry point could not be executed
     */
    private static ProbeRun servingDdlPostureVerdict(Path workDir, Path home, boolean initialising)
            throws Exception {
        return runEntryPointAllowingRefusal(workDir, home,
                "ENTITY_ENGINE_SOURCE=" + shellQuote("framework/entity/config/entityengine.xml") + "\n"
                        + "ENTITY_ENGINE_OVERRIDE=" + shellQuote(RENDERED_CONFIGURATION) + "\n"
                        + "RESOLVED_SCHEMA_INIT=" + (initialising ? "true" : "false") + "\n"
                        + "require_serving_mode_ddl_safety\n"
                        + "printf '%s\\n' " + shellQuote(GATE_ACCEPTED) + "\n",
                Map.of());
    }

    /**
     * Runs a fragment of shell against the real entry point, sourced as a library, and returns what it
     * printed. A non-zero exit fails the test with that output, because every start driven here is one
     * the entry point must accept.
     *
     * <p>The entry point is copied with its trailing {@code _main "$@"} line removed so that an
     * individual function can be invoked without starting OFBiz. Every inherited {@code OFBIZ_}
     * variable is removed first, so a test observes only what it sets and cannot be influenced by the
     * environment the build happens to run in.</p>
     * @param workDir a per-test temporary directory for the generated library and driver
     * @param home the directory the entry point runs in, which it treats as the OFBiz home
     * @param body the shell to run once the entry point has been sourced
     * @param environment the variables to supply
     * @return the combined standard output and standard error of the run
     * @throws Exception if the driver could not be written or executed
     */
    private static String runEntryPoint(Path workDir, Path home, String body, Map<String, String> environment)
            throws Exception {
        Path library = workDir.resolve("entrypoint-library.sh");
        if (!Files.exists(library)) {
            List<String> sourced = new ArrayList<>();
            for (String line : Files.readAllLines(repositoryRoot().resolve(ENTRY_POINT), StandardCharsets.UTF_8)) {
                if (!"_main \"$@\"".equals(line)) {
                    sourced.add(line);
                }
            }
            Files.write(library, sourced, StandardCharsets.UTF_8);
        }
        Path driver = Files.createTempFile(workDir, "entry-point", ".sh");
        Files.writeString(driver, "#!/usr/bin/env bash\n"
                + ". " + shellQuote(library) + "\n"
                + "cd " + shellQuote(home) + " || exit 1\n"
                + body, StandardCharsets.UTF_8);

        ShellDriver.Run run = ShellDriver.run(driver, home, environment, VERDICT_TIMEOUT_SECONDS);
        assertFalse(run.timedOut(), "the entry point did not terminate, output was:\n" + run.output());
        assertEquals(0, run.exitCode(), "the entry point refused this start, output was:\n" + run.output());
        return run.output();
    }

    /**
     * Parses a rendered configuration without validating it and without resolving anything external, so
     * the parse is offline and cannot be influenced by anything outside the file under test.
     * @param file the file to parse
     * @return the parsed document
     * @throws Exception if the parser could not be configured or the file could not be parsed
     */
    private static Document parse(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setValidating(false);
        factory.setNamespaceAware(false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return factory.newDocumentBuilder().parse(file.toFile());
    }

    /**
     * Finds the one element of a kind that carries a given {@code name} attribute, failing the test
     * when the declaration has been removed rather than returning null for a later dereference.
     * @param document the parsed configuration
     * @param tagName the element name, {@code datasource} or {@code delegator}
     * @param name the value of its {@code name} attribute
     * @return the element
     */
    private static Element declaration(Document document, String tagName, String name) {
        Element found = null;
        NodeList candidates = document.getElementsByTagName(tagName);
        for (int index = 0; index < candidates.getLength(); index++) {
            Element candidate = (Element) candidates.item(index);
            if (name.equals(candidate.getAttribute("name"))) {
                found = candidate;
            }
        }
        assertNotNull(found, "the rendered configuration declares no <" + tagName + " name=\"" + name + "\">");
        return found;
    }

    /**
     * Every {@code @TOKEN@} placeholder left in a rendered file, so a failure names them.
     * @param rendered the rendered text
     * @return the placeholders found, in order
     */
    private static List<String> placeholdersIn(String rendered) {
        return PLACEHOLDER.matcher(rendered).results().map(result -> result.group()).toList();
    }

    /**
     * Runs a shell script from this suite, safely, and reports what it did.
     *
     * <p>Nested here rather than shared from another component's test tree, because the schema-init gating
     * test is the only authorised new test in this module (plan section 0.2.1) and a shared test fixture
     * would be a second, unauthorised file. The content-store suite carries its own copy for the same
     * reason; the two are independent by design.
     *
     * <p>Two properties matter and are easy to get wrong:
     *
     * <ul>
     * <li><strong>Draining before waiting makes a deadline ineffective.</strong> Reading the child's output
     * to its end before {@code waitFor(timeout, unit)} means the READ blocks, and a read has no deadline: a
     * child that writes nothing and never exits would hang the build. The output is therefore drained on a
     * thread of its own and the bounded wait comes first.</li>
     * <li><strong>A child that outruns its deadline has to be killed.</strong> {@code waitFor} returning
     * {@code false} leaves the process running and nothing in a test JVM will reap it, so it is destroyed
     * forcibly and only then is its output collected.</li>
     * </ul>
     *
     * <p>Every run replaces, rather than adds to, the {@code OFBIZ_*} part of the environment, so a value
     * exported into the build agent's own environment cannot steer a case that did not ask for it.
     * {@code SHELLOPTS}/{@code BASHOPTS} go with them: bash reads {@code SHELLOPTS} at start up, so an
     * inherited {@code xtrace} would fill the merged output these cases assert on with trace lines.
     */
    private static final class ShellDriver {

        private ShellDriver() { }

        /**
         * What one run of a script produced.
         *
         * @param exitCode the exit status, or {@code -1} when the run was killed for outrunning its deadline
         * @param output everything the run wrote to stdout and stderr, interleaved
         * @param timedOut whether the run was killed rather than allowed to finish
         */
        private record Run(int exitCode, String output, boolean timedOut) {

            /**
             * Reports whether the run finished successfully.
             *
             * @return true when it exited zero within its deadline
             */
            private boolean succeeded() {
                return !timedOut && exitCode == 0;
            }
        }

        /**
         * Reports whether a POSIX shell is available to drive a script at all.
         *
         * @return true when {@code bash} can be started
         */
        private static boolean isBashAvailable() {
            try {
                Process probe = new ProcessBuilder("bash", "-c", "exit 0").redirectErrorStream(true).start();
                probe.getInputStream().close();
                return probe.waitFor(10L, TimeUnit.SECONDS) && probe.exitValue() == 0;
            } catch (IOException unavailable) {
                return false;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        /**
         * Runs a script, waits for it, kills it if it outruns its deadline, and collects its output.
         *
         * @param script the script to run
         * @param workingDirectory the directory to run it in
         * @param environment the variables to run it with; every inherited {@code OFBIZ_} variable is
         *     removed first
         * @param timeoutSeconds how long it may take
         * @return what the run produced
         * @throws IOException if the process cannot be started
         */
        private static Run run(Path script, Path workingDirectory, Map<String, String> environment,
                long timeoutSeconds) throws IOException {
            ProcessBuilder builder = new ProcessBuilder("bash", script.toString());
            builder.directory(workingDirectory.toFile());
            builder.redirectErrorStream(true);
            builder.environment().keySet().removeIf(name -> name.startsWith("OFBIZ_")
                    || "SHELLOPTS".equals(name) || "BASHOPTS".equals(name));
            builder.environment().putAll(environment);

            Process process = builder.start();
            StringBuilder collected = new StringBuilder();
            Thread drain = new Thread(() -> {
                try (InputStream output = process.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    for (int read = output.read(buffer); read >= 0; read = output.read(buffer)) {
                        synchronized (collected) {
                            collected.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                        }
                    }
                } catch (IOException closed) {
                    // The stream closes when the process ends or is destroyed; nothing left to read.
                    synchronized (collected) {
                        collected.append("[output stream closed: ").append(closed.getMessage()).append(']');
                    }
                }
            }, "schema-init-shell-drain");
            drain.setDaemon(true);
            drain.start();

            boolean finished;
            try {
                finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                }
                // Bounded, because a drain still blocked in a read the kill has not yet unblocked must not
                // become a second unbounded wait; whatever it collected by then is what the failure reports.
                drain.join(TimeUnit.SECONDS.toMillis(5L));
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new UncheckedIOException(new IOException("the shell run was interrupted", interrupted));
            }
            synchronized (collected) {
                return new Run(finished ? process.exitValue() : -1, collected.toString(), !finished);
            }
        }
    }
}
