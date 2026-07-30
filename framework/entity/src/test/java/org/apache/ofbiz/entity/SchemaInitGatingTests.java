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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guards the schema-lifecycle and multi-instance posture that makes this suite safe to run as a
 * stateless, load-balanced fleet.
 *
 * <p>Two configuration objectives are covered:</p>
 *
 * <p>The end-to-end scenarios that drive the container entry point through a whole start - the
 * render and its schema validity, init mode applying then verifying the DDL before exiting, a
 * loader that logs a failure but exits successfully, a loader that never reports completion, and
 * the refusal to combine schema initialisation with skipping initialisation - are pinned by
 * {@code SchemaInitEntryPointTests} rather than duplicated here. That suite's harness models the
 * lifecycle this tree actually implements: the database configuration is re-rendered on
 * <i>every</i> start, including an {@code OFBIZ_SKIP_INIT} start, and the state record beside it
 * is a field-named fingerprint rather than a {@code host|mode} pair. A fixture that installs an
 * override and expects it to be inherited untouched would therefore be asserting a lifecycle that
 * was deliberately replaced; the guarantee it was reaching for - that no serving instance issues
 * startup DDL - is pinned here by {@link #managedRdbmsRunModeHasDdlDisabled()} and
 * {@link #containerEntryPointCannotLetSkipInitBypassTheServingModeDdlCheck()}, which read the
 * authoritative file whoever wrote it.</p>
 *
 * <ul>
 * <li><b>Objective 4 - gated single schema initialization.</b> The managed-RDBMS datasources
 * ({@code localpostgres}, {@code localpostgresolap}, {@code localpostgrestenant}) must resolve to a
 * <i>run mode</i> that issues no start up DDL, so a serving instance needs no DDL privilege and a
 * scaled-out fleet cannot race on schema changes. Schema changes are applied exclusively by a
 * separate one-shot init execution, for which the container entry point renders the very same two
 * attributes as {@code true}. Both halves are established here - the run mode on the committed
 * model, the init mode by executing the entry point - and the one-shot execution as a whole,
 * including the {@code readers=none} load and the exit before any traffic is served, additionally in
 * {@code SchemaInitEntryPointTests}. No committed constant is used as a stand-in for the init mode:
 * a constant cannot fail when the machinery that would produce it is deleted, so crediting one as
 * init-mode coverage would report protection that does not exist.</li>
 * <li><b>Objective 5 - multi-instance coherence.</b> Distributed cache invalidation is
 * configuration-driven and must remain disabled in the committed configuration, so an unconfigured
 * checkout keeps the pre-existing single-node cache behaviour exactly.</li>
 * </ul>
 *
 * <p>Just as importantly, these tests pin the <i>backward-compatible local run</i>: the
 * {@code test} delegator stays bound to H2 so {@code gradlew loadAll} and {@code gradlew
 * testIntegration} are unaffected, and the embedded H2 datasources keep both start up DDL flags
 * enabled so a bare checkout still self-provisions. Those embedded flags are pinned here at the one
 * moment this class can see them change:
 * {@link #schemaModeChangesReRenderTheManagedDdlFlagsOnAReusedStateVolume(Path)} reads the file the
 * deployed profile actually runs on, which is generated at every container start and therefore
 * cannot be reviewed once and trusted afterwards. The committed half is deliberately not restated
 * here, because {@code EntityEngineConfigContractTests} already holds it more strongly than this
 * class could: it asserts an exact dialect, DDL-flag and driver tuple for {@code localh2},
 * {@code localh2olap} and {@code localh2tenant} <i>together</i> - not just the first of the three -
 * and then proves the parse asymmetry by removing the attributes from a cloned element and
 * re-parsing. A partial restatement here would be weaker coverage in a second place to maintain, so
 * this class states the posture the rendered file must have and that suite states the committed
 * grammar it rests on.</p>
 *
 * <p>PostgreSQL becomes the default only for the deployed profile, which the container entry point
 * renders from {@code docker/templates/postgres-entityengine.xml} when the database environment
 * variables are present - never in the committed source. No assertion here may therefore expect the
 * {@code default} delegator to reference a managed datasource.</p>
 *
 * <p>Most assertions read the <em>parsed</em> entity-engine configuration model through
 * {@link EntityConfig}, which is the same model the running engine consumes, so what they pin is the
 * behaviour the engine will exhibit rather than the text of a file. The remainder read
 * {@code docker/docker-entrypoint.sh}, and two of those execute it:
 * {@link #theSchemaInitVerdictsRejectEveryIncompleteOutcome(Path)} sources it in a POSIX shell and
 * calls its two schema-init verdicts over synthetic engine logs, and
 * {@link #schemaModeChangesReRenderTheManagedDdlFlagsOnAReusedStateVolume(Path)} drives its own mode
 * resolution and database configuration step three times against one state volume and reads the
 * attributes that came out, which is the only way to observe a <i>transition</i> between the two
 * modes. Where an assertion is a claim about the text of the script rather than about its behaviour
 * it is made by reading the script, because what is being pinned is the literal an author would edit
 * in the file they would edit it in.</p>
 *
 * <p>The whole class stays hermetic: it opens no database connection, performs no network access,
 * writes nothing of its own outside the per-test temporary directory JUnit supplies and deletes,
 * touches no file of the repository other than by reading it, and mutates no engine state. The one
 * exception is not this class's to make: the entry point creates its own short-lived substitution
 * script with {@code mktemp} under the system temporary directory and deletes it itself, exactly as
 * it does in the container. {@link EntityConfig} resolves {@code entityengine.xml} and validates it
 * against {@code entity-config.xsd}, both of which the build places on
 * {@code sourceSets.main.resources}, and the schema location is remapped to that local copy rather
 * than fetched, so the class runs offline. The entry-point assertions copy the template into a
 * sandbox that stands in for the container's {@code /ofbiz} home - the same relative
 * {@code templates/} and {@code config/} layout the image lays down - source the entry point with
 * its trailing {@code _main "$@"} line removed so an individual function can be invoked, and
 * redirect the container state directory into that sandbox so nothing on this host is read or
 * written. An assertion that needs a shell is skipped rather than failed where none exists. Schema
 * truth remains the entity model: no migration framework, schema-version table or hand-written DDL
 * is involved.</p>
 */
public final class SchemaInitGatingTests {

    /** The value {@code ofbiz.home} held before this class overwrote it, {@code null} if it held none. */
    private String ofbizHomeSnapshot;
    /**
     * The container entry point, relative to the repository root. It is the file that reads the datasource
     * signatures these tests pin.
     *
     * <p>The methods below read it as TEXT on purpose: the DDL guarantee this class asserts against the parsed
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
     * ignore every token whose name contains one and so could not prove closure at all.
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
    /** What it reports for the reverse transition, when an init job becomes a serving instance again. */
    private static final String INIT_TO_RUN_TRANSITION =
            "previously ran as an initialisation job and is now a serving instance";
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
     * {@code SecurityUtilTest}, {@code AdminKeyConfigTests} and the content-store provider suites —
     * are exactly the ones that would be affected if this class ran between one of their
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
     * AAP Objective 4, <b>run mode</b>: proves a serving instance issues no start up DDL.
     *
     * <p>This is the assertion that makes the fleet safe to scale out. Because both flags resolve
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
     * AAP Objective 4 end to end, on the artifact the deployment really reads: proves the gate
     * <i>discriminates</i>, that it is re-evaluated on every start, and that a schema-mode change on a
     * reused state volume is detected rather than silently ignored.
     *
     * <p>Three starts are driven against one sandbox and one container state directory, through the
     * entry point's own {@code resolve_entity_engine_flags} and {@code configure_database} — the same
     * pair {@code _main} performs — so what is exercised is the whole path from the environment
     * variable to the rendered attribute:</p>
     * <ol>
     * <li><b>unset</b>, which is what a deployment that never heard of the flag gets and therefore the
     * case that matters most: the managed datasources must render {@code false}, and the marker must
     * record that mode, because a mode that is not recorded cannot be seen to change;</li>
     * <li><b>{@code true}</b> on that same volume: the managed datasources must now render
     * {@code true}, the marker must be refreshed, and the run-to-init transition must be reported. The
     * two load-skipping markers a previous container left behind must survive, because the host has not
     * changed and that data is still in that database;</li>
     * <li><b>{@code false}</b> again: the run mode must come back and the reverse transition must be
     * reported. A state volume that had recorded an init run must not leave a serving instance
     * configured to issue DDL.</li>
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
     * why every leg additionally proves the render left no placeholder of the complete token grammar
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

        // 1. A first start with the flag unset: the mode every serving instance in the fleet runs.
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

        // 2. That same state volume, now asked for a one-shot initialisation run.
        String initStart = configureDatabase(tempDir, home, stateDir, "true");
        assertRenderedSchemaMode(home, "true", "with OFBIZ_SCHEMA_INIT=true");
        assertEquals("true", recordedSchemaMode(stateDir),
                "the state marker must be refreshed with the schema mode this start resolved");
        assertTrue(initStart.contains(RUN_TO_INIT_TRANSITION),
                "the run-to-init transition must be reported on a reused volume, output was:\n" + initStart);
        for (String loadMarker : LOAD_MARKERS) {
            assertTrue(Files.exists(stateDir.resolve(loadMarker)), "the " + loadMarker + " marker must survive a"
                    + " schema-mode change against the same host: that database still holds the data");
        }

        // 3. Back to serving on that same volume, which is what a redeploy after an init job does.
        String servingAgain = configureDatabase(tempDir, home, stateDir, "false");
        assertRenderedSchemaMode(home, "false", "with OFBIZ_SCHEMA_INIT=false after an init run");
        assertEquals("false", recordedSchemaMode(stateDir),
                "the state marker must record the return to the serving mode");
        assertTrue(servingAgain.contains(INIT_TO_RUN_TRANSITION),
                "the init-to-run transition must be reported on a reused volume, output was:\n" + servingAgain);
    }

    /**
     * AAP validation gate "Test delegator on H2": proves the frozen {@code test} delegator still
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
     * AAP Objective 5 default-off posture: proves distributed cache invalidation stays disabled in
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
     * rewrites, and it is pinned by the purpose-built sibling contract test:
     * {@code EntityEngineConfigContractTests} (in {@code org.apache.ofbiz.entity.config.model},
     * whose package-private DOM constructors it can reach) compares the raw attribute text of every
     * delegator in the authoritative file, so deleting the attribute fails
     * {@code delegatorCacheAndEcaAttributesAreExactlyTheCommittedLiterals}. Contrast
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
     * the engine to apply the entity-model DDL and load nothing — has no data files to read, so it
     * reports "Finished the data load with 0 rows changed" and exits {@code 0} <b>even when the start
     * up database check that precedes it failed outright</b>. Verified against a live PostgreSQL with
     * one wrong password: {@link org.apache.ofbiz.entity.jdbc.DatabaseUtil} logged that it could not
     * connect and aborted, the JVM still exited {@code 0}, and no table existed afterwards. Since the
     * only reason the init mode exists is that its exit status means "the schema is ready", the entry
     * point additionally asserts on this log output, and these literals are what make that assertion
     * true.</p>
     *
     * <p>Four groups of literals are pinned, one per question the entry point has to answer: the
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
     * having created nothing. It also proves the constants are load-bearing: the checks that consume
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
        // "true" form: seeing it proves the check ran AND that this execution really got init mode.
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
     * AAP Objective 4: proves the entry point's failure enumeration covers <em>every</em> failure the
     * entity engine can report, not merely the ones somebody happened to think of.
     *
     * <p>This is the assertion that would have caught the defect this test class previously encoded. The
     * entry point used to name two of {@link org.apache.ofbiz.entity.jdbc.DatabaseUtil}'s messages and
     * this test asserted that there were exactly two of them, which turned an incomplete list into a
     * pinned requirement. The census below runs the other way round: it reads every message the engine
     * emits for the two failure classes out of the engine's own source and requires the entry point to
     * cover each one, so a message added upstream fails the build instead of silently becoming a hole.</p>
     *
     * <p>Two classes are censused, because they fail in different ways and the second is the dangerous
     * one:</p>
     * <ul>
     * <li><b>Abort.</b> A message ending {@code ", aborting."} is a {@code return} out of
     * {@code checkDb} before any entity is examined, so nothing at all was created.</li>
     * <li><b>DDL failure.</b> A {@code "Could not create ..."} or {@code "Could not add column ..."}
     * message is logged at error level and then the engine <em>continues with the next entity</em>. The
     * run finishes, the data loader exits {@code 0} because it had no rows to load, no abort message is
     * ever printed, and the database is left with some tables and not others. An enumeration that omits
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
     * and proves that neither an aborted, a partial nor an incomplete outcome can be recorded as applied.
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

        // The engine's foreign key and index residuals, its extra-table notice and its column-count notice
        // are deliberately not completeness evidence: the three check-*-on-start flags that produce the
        // first two ship disabled, DatabaseUtil's own source records that its foreign key comparison does
        // not work on PostgreSQL, and an extra table or column is not a missing one.
        assertVerdict(tempDir, "verify", 0, String.join("\n", cleanVerification,
                "Table named [OLD_THING] exists in the database but has no corresponding entity",
                "Entity [Party] has 12 fields but table [PARTY] has 13 columns.",
                "No Foreign Key Constraint [PARTY_CB] found for entity [Party]",
                "No Index [PARTY_TXCRTD] found for entity [Party]"), 2, true,
                "extra objects, a column-count difference and the disabled-by-default foreign key and index"
                        + " residuals must not be mistaken for an incomplete schema");
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
     * <p>The entry point is sourced with its trailing {@code _main "$@"} line removed, exactly as
     * {@code AdminKeyConfigTests} does, so one function can be invoked as a black box without starting
     * OFBiz. The verdicts print an explanation when they refuse and print nothing when they accept, so
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
        String invocation = "apply".equals(pass)
                ? "schema_init_apply_verdict " + status + " " + shellQuote(logFile)
                : "schema_init_verification_verdict " + status + " " + shellQuote(logFile) + " " + appliedChecks;
        Path driver = Files.createTempFile(workDir, "verdict-", ".sh");
        Files.writeString(driver, "#!/usr/bin/env bash\n. " + shellQuote(library) + "\n" + invocation + "\n",
                StandardCharsets.UTF_8);

        ProcessBuilder builder = new ProcessBuilder("bash", driver.toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String verdict = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(VERDICT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "the " + pass + " verdict did not terminate");
        assertEquals(0, process.exitValue(), "the " + pass + " verdict must not fail as a shell function");
        assertEquals(expectedAccepted, verdict.isEmpty(), because + " -- verdict was: " + verdict);
    }

    /** A shell-quoted path, so a temporary directory containing a space or a quote cannot break a driver. */
    private static String shellQuote(Path path) {
        return "'" + path.toString().replace("'", "'\\''") + "'";
    }

    /** Whether a POSIX shell can be executed, so the shell-driven assertions can be skipped if not. */
    private static boolean isBashAvailable() {
        try {
            Process process = new ProcessBuilder("bash", "-c", "exit 0").start();
            return process.waitFor(VERDICT_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            // Restored rather than swallowed: this runs on a JUnit worker thread that outlives the
            // method, and a thread whose interrupt flag was cleared silently ignores a cancellation.
            Thread.currentThread().interrupt();
            return false;
        }
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
     * AAP Objective 4 cross-artifact contract: proves the entry point's serving-mode DDL guard covers exactly the
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
     * AAP Objective 4, <b>init/serve isolation</b>: proves {@code OFBIZ_SKIP_INIT} cannot bypass the DDL posture.
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
     * because the raw variable is parsed once, up front, into a resolved {@code true}/{@code false}: an earlier
     * release treated any non-empty value as "skip", so {@code OFBIZ_SKIP_INIT=false} skipped the initialisation.
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
     * AAP Objective 4 state separation: proves schema-init completion is tracked apart from the data-load marker.
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
     * AAP Objective 4 success criterion: proves the init-mode exit is gated on evidence produced by that same run.
     *
     * <p>{@code require_schema_init_completed} compares the receipt against a token unique to the execution, so a
     * receipt left by an earlier run cannot stand in for work this run did not do. It must be reached before the
     * successful exit, and the DDL pass it evidences must remain reachable when the data-load marker is already
     * present — that combination is exactly the case that used to report success having applied nothing.</p>
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
        // this contract is about, so the LAST exit 0 is taken and then proved to be the one the
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
     * AAP Objective 5 cross-artifact contract: proves the delegator's cache flag can never be enabled without a
     * transport, in <em>any</em> profile.
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
     * profile-gated refusal is what used to let a development instance continue into that rollback behind a warning,
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
     * AAP Objective 5 / B2-JMS-RES-01: proves a claimed transport is backed by start up evidence, not by its mere
     * presence in a file.
     *
     * <p>Three distinct facts have to hold before an instance may claim membership of a coherent fleet, and each is a
     * separate helper so that each can fail with its own message: the element must {@code listen}, or this instance
     * publishes invalidations while ignoring its peers' — the worst outcome available, because nothing fails and the
     * data is simply wrong on one instance; the broker client must be loadable, or the listener cannot be constructed;
     * and at least one broker endpoint must answer within a bounded deadline.</p>
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
     * AAP Objective 5 ordering contract: proves the transport is rendered before it is judged, and on every start.
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
     * AAP section 0.2.1 boundary: proves the reference-only service-engine configuration is only ever read.
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
     * AAP Objective 5 isolation contract: proves the cache transport is scoped to a jndi-server of its own rather than
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
     * AAP section 0.7.1 functional parity: proves the transport render leaves the process-wide JNDI default exactly as
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
     * AAP Objective 5 lifecycle contract: proves the rendered jndi-server catalogue is managed across restarts like
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
     * AAP section 0.2.2 boundary: proves the distribution's JNDI configuration is only ever read.
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

    /**
     * Reads the container entry point as one string.
     *
     * @return the whole script, newline joined
     */
    private static String entryPointText() {
        return repositoryFileText(ENTRY_POINT);
    }

    /**
     * Reads the container entry point as lines.
     *
     * @return every line of the script, in order
     */
    private static List<String> entryPointLines() {
        return repositoryFileLines(ENTRY_POINT);
    }

    /**
     * Reads a committed file, addressed relative to the repository root, as one string.
     *
     * @param relativePath the path of the file relative to the repository root
     * @return the whole file, newline joined
     */
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
     * Finds the first line of {@code _main} that is exactly the given call, ignoring indentation.
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

    /**
     * The zero-based index of the first line that is exactly the given statement once trimmed.
     *
     * @param lines the lines to search
     * @param statement the statement to locate
     * @return the zero-based index of the statement
     */
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

    // ---------------------------------------------------------------------------------------------
    // Helpers of the entry-point driven schema-mode assertion above. They execute the real
    // docker/docker-entrypoint.sh as a library inside a sandbox that mimics the container's /ofbiz
    // layout, so what they observe is the rendered artefact a deployment reads rather than a
    // restatement of the committed file.
    // ---------------------------------------------------------------------------------------------
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
        String body = "CONTAINER_STATE_DIR=" + shellQuote(stateDir) + "\n"
                + "CONTAINER_DATA_LOADED=\"$CONTAINER_STATE_DIR/" + LOAD_MARKERS.get(0) + "\"\n"
                + "CONTAINER_ADMIN_LOADED=\"$CONTAINER_STATE_DIR/" + LOAD_MARKERS.get(1) + "\"\n"
                + "CONTAINER_DB_CONFIG_APPLIED=\"$CONTAINER_STATE_DIR/" + STATE_MARKER + "\"\n"
                + "mkdir --parents \"$CONTAINER_STATE_DIR\"\n"
                + RESOLVE_AND_CONFIGURE;

        Map<String, String> environment = new LinkedHashMap<>(MANAGED_DATABASE_ENVIRONMENT);
        if (schemaInitValue != null) {
            environment.put("OFBIZ_SCHEMA_INIT", schemaInitValue);
        }
        return runEntryPoint(workDir, home, body, environment);
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
        Path renderedFile = home.resolve(RENDERED_CONFIGURATION);
        assertTrue(Files.isRegularFile(renderedFile),
                "the entry point rendered no " + RENDERED_CONFIGURATION + " " + context);
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

        ProcessBuilder builder = new ProcessBuilder("bash", driver.toString());
        builder.directory(home.toFile());
        builder.redirectErrorStream(true);
        Map<String, String> processEnvironment = builder.environment();
        for (String name : new ArrayList<>(processEnvironment.keySet())) {
            if (name.startsWith("OFBIZ_")) {
                processEnvironment.remove(name);
            }
        }
        processEnvironment.putAll(environment);

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(VERDICT_TIMEOUT_SECONDS, TimeUnit.SECONDS), "the entry point did not terminate");
        assertEquals(0, process.exitValue(), "the entry point refused this start, output was:\n" + output);
        return output;
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
}
