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

import org.apache.ofbiz.entity.config.model.Datasource;
import org.apache.ofbiz.entity.config.model.DelegatorElement;
import org.apache.ofbiz.entity.config.model.EntityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the schema-lifecycle and multi-instance posture that makes this suite safe to run as a
 * stateless, load-balanced fleet. Every assertion here reads the <em>parsed</em> entity-engine
 * configuration model through {@link EntityConfig}, which is the same model the running engine
 * consumes, so what these tests pin is the behaviour the engine will actually exhibit rather than
 * the text of a file.
 *
 * <p>Two configuration objectives are covered:</p>
 * <ul>
 * <li><b>Objective 4 — gated single schema initialization.</b> The managed-RDBMS datasources
 * ({@code localpostgres}, {@code localpostgresolap}, {@code localpostgrestenant}) must resolve to
 * a <i>run mode</i> that issues no start up DDL, so a serving instance needs no DDL privilege and a
 * scaled-out fleet cannot race on schema changes. Schema changes are applied exclusively by a
 * separate one-shot init execution, for which the container entry point renders the very same two
 * attributes as {@code true}; the embedded H2 datasources carry that <i>init mode</i> flag pair in
 * the committed configuration and therefore stand in for it here.</li>
 * <li><b>Objective 5 — multi-instance coherence.</b> Distributed cache invalidation is
 * configuration-driven and must remain disabled in the committed configuration, so an unconfigured
 * checkout keeps the pre-existing single-node cache behaviour exactly.</li>
 * </ul>
 *
 * <p>Just as importantly, these tests pin the <i>backward-compatible local run</i>: the committed
 * configuration still boots on embedded H2 with no environment variables and no secrets, and the
 * {@code test} delegator stays bound to H2 so {@code gradlew loadAll} and {@code gradlew
 * testIntegration} are unaffected. PostgreSQL becomes the default only for the deployed profile,
 * which the container entry point renders from {@code docker/templates/postgres-entityengine.xml}
 * when the database environment variables are present — never in the committed source. No
 * assertion here may therefore expect the {@code default} delegator to reference a managed
 * datasource.</p>
 *
 * <p>The whole class is a pure unit test: it opens no database connection, performs no network
 * access, writes no files and mutates no engine state. The only reading it does is of two class
 * path resources — {@link EntityConfig} resolves {@code entityengine.xml} and validates it against
 * {@code entity-config.xsd}, both of which the build places on
 * {@code sourceSets.main.resources}, and the schema location is remapped to that local copy rather
 * than fetched, so the class runs offline. Schema truth remains the entity model — no migration
 * framework, schema-version table or hand-written DDL is involved.</p>
 */
public final class SchemaInitGatingTests {

    /**
     * Resolves {@code ofbiz.home} the way the other unit tests in this package do, so that local
     * resource and XSD lookup succeeds before the configuration model is touched.
     *
     * <p>{@link EntityConfig} builds its singleton in a static initializer, so every reference to
     * it below is deliberately confined to a test-method body: referencing it from a field or
     * static initializer of this class would load it before this method has run. Nothing else
     * global is changed, which is why this class needs no teardown.</p>
     */
    @BeforeEach
    public void initialize() {
        System.setProperty("ofbiz.home", System.getProperty("user.dir"));
    }

    /**
     * AAP Objective 4, <b>run mode</b>: proves a serving instance issues no start up DDL.
     *
     * <p>This is the assertion that makes the fleet safe to scale out. Because both flags resolve
     * to {@code false} on all three managed-RDBMS datasources, no instance attempts to check or
     * amend the schema while booting, so none of them needs a DDL privilege and none of them can
     * collide with a peer over a schema change.</p>
     */
    @Test
    public void managedRdbmsRunModeHasDdlDisabled() {
        // The three managed-RDBMS datasources that the deployed profile binds the default and
        // default-no-eca delegators to, one per immutable entity group.
        String[] managedDatasourceNames = {"localpostgres", "localpostgresolap", "localpostgrestenant"};
        for (String datasourceName : managedDatasourceNames) {
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
     * AAP Objective 4, <b>init mode</b>: proves the enabled flag pair that the one-shot schema-init
     * execution relies on is a real, reachable state of the configuration model, and that the two
     * flags asserted in {@link #managedRdbmsRunModeHasDdlDisabled()} genuinely discriminate.
     *
     * <p>The init-mode configuration itself is rendered at container start up rather than
     * committed, and it cannot be parsed from an in-test XML fixture either: the
     * {@code org.w3c.dom.Element} constructor of {@code Datasource} is package-private to
     * {@code org.apache.ofbiz.entity.config.model}, so this test — which lives in
     * {@code org.apache.ofbiz.entity} — cannot invoke it. {@code localh2} is used as the init-mode
     * analogue instead, because it carries in the committed configuration exactly the
     * {@code check-on-start="true"} / {@code add-missing-on-start="true"} pair that the entry point
     * renders onto the managed datasources when {@code OFBIZ_SCHEMA_INIT=true}.</p>
     *
     * <p>The two flags are also parsed <i>asymmetrically</i>, which is why the run mode must spell
     * {@code false} out as a literal instead of dropping the attribute: {@code check-on-start} is
     * read as {@code !"false".equals(value)} and so defaults to <b>true</b> when absent, whereas
     * {@code add-missing-on-start} is read as {@code "true".equals(value)} and so defaults to
     * <b>false</b>. Deleting {@code check-on-start} would therefore leave start up DDL enabled.
     * Every datasource in the committed configuration states both attributes explicitly, so the
     * absent-attribute defaults cannot be observed from it; the discriminating contrast below
     * proves what is observable, namely that these accessors report the declared literal.</p>
     */
    @Test
    public void initModeEnablesDdl() {
        Datasource initModeAnalogue = EntityConfig.getDatasource("localh2");
        assertNotNull(initModeAnalogue, "localh2 must stay declared: it is the init-mode flag-pair analogue"
                + " and the zero-configuration development datasource");
        assertTrue(initModeAnalogue.getCheckOnStart(),
                "init mode must check the schema on start up so the entity-model DDL is applied");
        assertTrue(initModeAnalogue.getAddMissingOnStart(),
                "init mode must add missing schema objects, additively: it creates, it never drops");

        // Discrimination proof: the same two accessors report the opposite state for a run-mode
        // datasource, so the gating above is a genuine configuration difference and not a constant.
        Datasource runMode = EntityConfig.getDatasource("localpostgres");
        assertNotNull(runMode, "localpostgres must stay declared to contrast run mode against init mode");
        assertFalse(runMode.getCheckOnStart(), "run mode and init mode must not resolve check-on-start alike");
        assertFalse(runMode.getAddMissingOnStart(),
                "run mode and init mode must not resolve add-missing-on-start alike");
    }

    /**
     * AAP Objective 4 guard rail: proves the embedded H2 development path was not collaterally
     * disabled when the managed-RDBMS run mode was gated.
     *
     * <p>H2 is single-node development and test only and is never part of a multi-instance fleet,
     * so it deliberately keeps both flags enabled. That is what lets a bare checkout boot with no
     * environment variables, no secrets and no external database.</p>
     */
    @Test
    public void embeddedH2RetainsStartupDdl() {
        Datasource embeddedH2 = EntityConfig.getDatasource("localh2");
        assertNotNull(embeddedH2, "the embedded H2 datasource must stay declared for local development");
        assertTrue(embeddedH2.getCheckOnStart(),
                "localh2 must keep checking the schema on start up so a bare checkout is zero-configuration");
        assertTrue(embeddedH2.getAddMissingOnStart(),
                "localh2 must keep adding missing schema objects on start up so a bare checkout self-provisions");
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
}
