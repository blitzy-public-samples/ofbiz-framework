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
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Asserts the database least-privilege posture of Objective 4 against a real PostgreSQL server: the
 * account a serving instance authenticates as can move rows but cannot change the schema, while the
 * account the one-shot schema-initialization execution authenticates as can.
 *
 * <p>This closes a gap that configuration alone cannot close. {@code SchemaInitGatingTests} and
 * {@code SchemaInitEntryPointTests} establish that the managed datasources are rendered with
 * {@code check-on-start="false"} and {@code add-missing-on-start="false"}, and that an init execution
 * renders a different database identity than a serving one. Both are statements about what the fleet
 * <em>issues</em>. Neither is a statement about what the fleet <em>may</em> issue: a serving instance
 * that authenticates as an account holding DDL privileges is one SQL statement away from altering the
 * schema, whether or not the engine chooses to issue that statement, and anything that reaches the
 * instance or reads its environment inherits the privilege. The only way to establish that the
 * privilege is actually absent is to ask a real server to refuse it.</p>
 *
 * <p>What is exercised is the grant sequence the project documents, not an invention of this test. The
 * statements applied by {@link Provisioning} are the ones written out under <i>Database roles and least
 * privilege</i> in {@code DOCKER.adoc}, and
 * {@link #theDocumentedGrantSequenceIsTheOneThisTestApplies()} asserts that correspondence directly, so
 * a change that weakens the documented grants fails this test rather than quietly diverging from it.
 * The grants are applied over JDBC, through the driver this refactor bundles, so the test needs neither
 * a shell nor a {@code psql} client and cannot be skipped for want of one.</p>
 *
 * <p><b>The two live checks below carry the {@code external-services} JUnit tag</b>, so they are excluded
 * from the offline {@code test} task and are run by {@code testExternalServices}, where a missing setting
 * FAILS instead of skipping. That separation replaces what this class used to do: sit in the ordinary unit
 * task and skip itself whenever no server was configured, which meant the whole suite reported success while
 * the one contract only a server can establish had been verified by nothing at all. The documentation check
 * carries no tag and stays in the unit tier, because it needs no server.</p>
 *
 * <pre>
 * ./gradlew testExternalServices --tests '*DatabaseLeastPrivilegeTests*' \
 *     -Dofbiz.test.postgres.host=127.0.0.1 \
 *     -Dofbiz.test.postgres.password="the superuser password"
 * </pre>
 *
 * <p>Every setting may also arrive as an environment variable ({@code OFBIZ_TEST_POSTGRES_HOST},
 * {@code OFBIZ_TEST_POSTGRES_PORT}, {@code OFBIZ_TEST_POSTGRES_SUPERUSER},
 * {@code OFBIZ_TEST_POSTGRES_PASSWORD}, {@code OFBIZ_TEST_POSTGRES_SSLMODE}). The account supplied
 * must be able to create roles and databases. Every object this test creates carries a per-run random
 * suffix and is dropped again, so it neither collides with a concurrent run nor leaves anything
 * behind, and it never touches a database it did not create.</p>
 */
public class DatabaseLeastPrivilegeTests {

    /**
     * The JUnit tag that moves a check out of the offline unit task and into {@code testExternalServices}.
     *
     * <p>Named here as a constant rather than written as a literal at each use so that a rename cannot leave
     * one of the two live checks behind in the unit tier, where it would once again skip itself.
     */
    private static final String EXTERNAL_SERVICES = "external-services";

    /** Located by walking up from the working directory, so the suite runs from any module. */
    private static final String DEPENDENCY_MANIFEST = "dependencies.gradle";

    /** The document whose <i>Database roles and least privilege</i> section this test holds itself to. */
    private static final String OPERATOR_DOCUMENTATION = "DOCKER.adoc";

    /** Injected configuration. Absent host or password fails the live checks; it never skips them. */
    private static final String HOST_KEY = "ofbiz.test.postgres.host";
    private static final String PORT_KEY = "ofbiz.test.postgres.port";
    private static final String SUPERUSER_KEY = "ofbiz.test.postgres.superuser";
    private static final String PASSWORD_KEY = "ofbiz.test.postgres.password";
    private static final String SSLMODE_KEY = "ofbiz.test.postgres.sslmode";

    private static final String DEFAULT_PORT = "5432";
    private static final String DEFAULT_SUPERUSER = "postgres";

    /**
     * The transport mode used to reach the test server. Defaults to {@code disable} because the
     * server this runs against is a local, throwaway one that has no TLS listener, and because a
     * silent fallback would be worse than an explicit choice. It has nothing to do with the deployed
     * default, which is {@code verify-full} and is asserted by {@code SchemaInitEntryPointTests}.
     */
    private static final String DEFAULT_SSLMODE = "disable";

    /** The maintenance database the roles and databases are created from and dropped from. */
    private static final String MAINTENANCE_DATABASE = "postgres";

    /** PostgreSQL's {@code insufficient_privilege}. Every refusal asserted below must be exactly this. */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    /** The driver is on the test runtime classpath through {@code runtimeOnly} and is loaded by name. */
    private static final String DRIVER_CLASS = "org.postgresql.Driver";

    /**
     * The example identifiers the documented grant sequence is written with, and the placeholder the
     * documented passwords carry. {@link Provisioning} substitutes its own throwaway names for these,
     * which is also what lets {@link #theDocumentedGrantSequenceIsTheOneThisTestApplies()} compare the
     * two texts.
     */
    private static final String DOCUMENTED_INIT_ROLE = "ofbiz_init";
    private static final String DOCUMENTED_SERVING_ROLE = "ofbiz_app";
    private static final String DOCUMENTED_DATABASE = "ofbizmaindb";

    /**
     * The grant sequence exactly as <i>Database roles and least privilege</i> in {@code DOCKER.adoc}
     * writes it out, split into the statements a JDBC connection issues one at a time.
     *
     * <p>Held here as data rather than embedded in the provisioning code so that one list is both what
     * gets applied to the server and what gets compared with the documentation. The two
     * {@code CREATE ROLE} statements are omitted because the documented text carries a literal password
     * placeholder rather than a value; the roles are still created with exactly the documented
     * {@code LOGIN PASSWORD} shape, which {@link Provisioning#createRoles} does.
     *
     * <p>The first group runs against the maintenance database, because the target database does not
     * exist yet; the second runs inside the database it configures, which is what the documented
     * {@code \connect} metacommand does for a {@code psql} reader.
     */
    private static final List<String> CLUSTER_GRANTS = List.of(
            "CREATE DATABASE " + DOCUMENTED_DATABASE + " OWNER " + DOCUMENTED_INIT_ROLE,
            "REVOKE ALL ON DATABASE " + DOCUMENTED_DATABASE + " FROM PUBLIC",
            "GRANT CONNECT, TEMPORARY ON DATABASE " + DOCUMENTED_DATABASE + " TO " + DOCUMENTED_INIT_ROLE,
            "GRANT CONNECT, TEMPORARY ON DATABASE " + DOCUMENTED_DATABASE + " TO " + DOCUMENTED_SERVING_ROLE);

    private static final List<String> SCHEMA_GRANTS = List.of(
            "ALTER SCHEMA public OWNER TO " + DOCUMENTED_INIT_ROLE,
            "REVOKE ALL ON SCHEMA public FROM PUBLIC",
            "GRANT USAGE ON SCHEMA public TO " + DOCUMENTED_SERVING_ROLE,
            "ALTER DEFAULT PRIVILEGES FOR ROLE " + DOCUMENTED_INIT_ROLE + " IN SCHEMA public"
                    + " GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + DOCUMENTED_SERVING_ROLE,
            "ALTER DEFAULT PRIVILEGES FOR ROLE " + DOCUMENTED_INIT_ROLE + " IN SCHEMA public"
                    + " GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO " + DOCUMENTED_SERVING_ROLE,
            "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + DOCUMENTED_SERVING_ROLE,
            "GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO " + DOCUMENTED_SERVING_ROLE);

    /** Both halves of the sequence, in the order they are applied and documented. */
    private static final List<String> DOCUMENTED_GRANT_SEQUENCE = documentedGrantSequence();

    private static List<String> documentedGrantSequence() {
        List<String> sequence = new ArrayList<>(CLUSTER_GRANTS);
        sequence.addAll(SCHEMA_GRANTS);
        return List.copyOf(sequence);
    }

    /**
     * Two entity-model-shaped tables, created by the initialization role exactly as the init execution
     * would create them, and then used to establish what the serving role can and cannot do to them.
     * {@code SequenceValueItem} is the table OFBiz writes to on almost every insert, so a serving role
     * that cannot update it cannot serve at all.
     */
    private static final List<String> ENTITY_MODEL_DDL = List.of(
            "CREATE TABLE \"SequenceValueItem\" (\"seqName\" VARCHAR(60) NOT NULL, \"seqId\" NUMERIC(20,0),"
                    + " CONSTRAINT \"pk_sequence_value_item\" PRIMARY KEY (\"seqName\"))",
            "CREATE TABLE \"Party\" (\"partyId\" VARCHAR(20) NOT NULL, \"partyTypeId\" VARCHAR(20),"
                    + " CONSTRAINT \"pk_party\" PRIMARY KEY (\"partyId\"))");

    /**
     * Everything a serving instance must still be able to do. If any of these were refused the fleet
     * would be unable to serve traffic, which would make the privilege separation useless rather than
     * safe - so these are asserted with the same weight as the refusals.
     */
    private static final Map<String, String> PERMITTED_STATEMENTS = permittedStatements();

    /**
     * Every way a serving instance might change the schema, including the two ways of working around a
     * naive restriction: creating a schema of its own, and assuming the initialization role. All of
     * them must be refused with {@code insufficient_privilege}.
     */
    private static final Map<String, String> REFUSED_STATEMENTS = refusedStatements();

    private static Map<String, String> permittedStatements() {
        Map<String, String> statements = new LinkedHashMap<>();
        statements.put("INSERT", "INSERT INTO \"SequenceValueItem\" VALUES ('Party', 10000)");
        statements.put("SELECT", "SELECT \"seqId\" FROM \"SequenceValueItem\" WHERE \"seqName\" = 'Party'");
        statements.put("UPDATE", "UPDATE \"SequenceValueItem\" SET \"seqId\" = 10010 WHERE \"seqName\" = 'Party'");
        statements.put("INSERT into a second table", "INSERT INTO \"Party\" VALUES ('DEMO1', 'PERSON')");
        statements.put("DELETE", "DELETE FROM \"SequenceValueItem\" WHERE \"seqName\" = 'Party'");
        statements.put("CREATE TEMP TABLE", "CREATE TEMP TABLE probe_session_local (i INT)");
        return statements;
    }

    private static Map<String, String> refusedStatements() {
        Map<String, String> statements = new LinkedHashMap<>();
        statements.put("CREATE TABLE", "CREATE TABLE \"Injected\" (i INT)");
        statements.put("DROP TABLE", "DROP TABLE \"Party\"");
        statements.put("ALTER TABLE ... ADD COLUMN", "ALTER TABLE \"Party\" ADD COLUMN \"injected\" VARCHAR(10)");
        statements.put("ALTER TABLE ... DROP COLUMN", "ALTER TABLE \"Party\" DROP COLUMN \"partyTypeId\"");
        statements.put("TRUNCATE", "TRUNCATE \"Party\"");
        statements.put("CREATE INDEX", "CREATE INDEX \"injected_ix\" ON \"Party\" (\"partyTypeId\")");
        statements.put("CREATE VIEW", "CREATE VIEW \"InjectedView\" AS SELECT 1");
        statements.put("CREATE SCHEMA (working around the schema grant)", "CREATE SCHEMA \"injected\"");
        return statements;
    }

    /*
     * The privilege assertions
     */

    /**
     * The serving role moves rows and is refused every schema change; the initialization role makes
     * the schema in the first place.
     *
     * <p>The order matters and is the point of the test. The tables are created by the initialization
     * role <em>after</em> the documented grants have been applied, which is the real sequence - the grants
     * applied to an empty database and the schema arrives later, from the init execution. The serving
     * role's row privileges on those tables therefore cannot have come from a grant naming them; they
     * come from {@code ALTER DEFAULT PRIVILEGES}, which is what makes the arrangement survive the next
     * schema-affecting release without a follow-up grant. If that statement were dropped from the
     * documented sequence, the permitted half of this test would fail rather than the refused half.</p>
     *
     * @throws Exception if the server could not be reached or a documented grant was refused, either of
     *         which fails the test rather than being reported as a pass
     */
    @Test
    @Tag(EXTERNAL_SERVICES)
    public void theServingRoleMovesRowsAndIsRefusedEverySchemaChange() throws Exception {
        requireConfigured();
        Provisioning provisioning = new Provisioning();
        try {
            provisioning.run();

            // The init execution's identity: it must be able to create the entity-model schema.
            try (Connection initialization = provisioning.connectAsInitialization(Group.OFBIZ)) {
                for (String ddl : ENTITY_MODEL_DDL) {
                    assertAllowed(initialization, "the initialization role: " + ddl, ddl);
                }
            }

            // The serving identity: the only one any instance that answers a request ever holds.
            try (Connection serving = provisioning.connectAsServing(Group.OFBIZ)) {
                for (Map.Entry<String, String> permitted : PERMITTED_STATEMENTS.entrySet()) {
                    assertAllowed(serving, "the serving role must still be able to " + permitted.getKey(),
                            permitted.getValue());
                }
                for (Map.Entry<String, String> refused : REFUSED_STATEMENTS.entrySet()) {
                    assertRefused(serving, refused.getKey(), refused.getValue());
                }

                // Assuming the privileged role is the one escalation a grant review tends to miss, so
                // it is asserted separately, naming the role the separation exists to protect.
                assertRefused(serving, "SET ROLE to the initialization role",
                        "SET ROLE " + quoteIdentifier(provisioning.initializationUser(Group.OFBIZ)));
            }

            // The other two databases are provisioned by the same loop, so the same must hold there.
            // Asserted narrowly - one refusal and one grant each - because what is being checked is
            // that the loop did not skip a group, not the grant model a second and third time.
            for (Group group : List.of(Group.OLAP, Group.TENANT)) {
                try (Connection initialization = provisioning.connectAsInitialization(group)) {
                    assertAllowed(initialization, "the initialization role of " + group,
                            "CREATE TABLE \"Probe\" (i INT)");
                }
                try (Connection serving = provisioning.connectAsServing(group)) {
                    assertAllowed(serving, "the serving role of " + group, "INSERT INTO \"Probe\" VALUES (1)");
                    assertRefused(serving, "CREATE TABLE in " + group, "CREATE TABLE \"Injected\" (i INT)");
                }
            }
        } finally {
            provisioning.drop();
        }
    }

    /**
     * The serving role cannot see the initialization role's password, and cannot become it by any of
     * the routes a role is normally escalated through.
     *
     * <p>Separating the identities only helps while the serving credential cannot be turned into the
     * privileged one. PostgreSQL hands out a surprising amount through {@code pg_authid} and role
     * membership, so the three routes are asserted directly: reading the password hash, being a member
     * of the privileged role, and holding the attributes that would make the restriction moot.</p>
     *
     * @throws Exception if the server could not be reached or a documented grant was refused
     */
    @Test
    @Tag(EXTERNAL_SERVICES)
    public void theServingRoleCannotBecomeTheInitializationRole() throws Exception {
        requireConfigured();
        Provisioning provisioning = new Provisioning();
        try {
            provisioning.run();
            try (Connection serving = provisioning.connectAsServing(Group.OFBIZ)) {
                assertRefused(serving, "read the stored password hashes",
                        "SELECT rolpassword FROM pg_authid WHERE rolname = 'postgres'");

                assertFalse(booleanQuery(serving, "SELECT pg_has_role(current_user, "
                                + literal(provisioning.initializationUser(Group.OFBIZ)) + ", 'USAGE')"),
                        "the serving role must not be a member of the initialization role, or SET ROLE would"
                                + " hand it the DDL the separation removes");

                assertFalse(booleanQuery(serving, "SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolbypassrls"
                                + " FROM pg_roles WHERE rolname = current_user"),
                        "the serving role must hold none of the role attributes that would make the grants moot");

                assertFalse(booleanQuery(serving, "SELECT has_database_privilege(current_user, current_database(),"
                                + " 'CREATE')"),
                        "the serving role must not be able to create a schema of its own to work around the"
                                + " restriction on the public schema");
                assertFalse(booleanQuery(serving, "SELECT has_schema_privilege(current_user, 'public', 'CREATE')"),
                        "the serving role must hold USAGE without CREATE on the schema OFBiz uses");
                assertTrue(booleanQuery(serving, "SELECT has_schema_privilege(current_user, 'public', 'USAGE')"),
                        "the serving role must still be able to reach the objects in the schema");
            }
        } finally {
            provisioning.drop();
        }
    }

    /**
     * The grant sequence this test applies is the sequence {@code DOCKER.adoc} tells an operator to
     * apply, statement for statement.
     *
     * <p>This is what keeps the privilege assertions above honest. They prove that <em>some</em> pair of
     * roles behaves correctly; only this test proves that the pair they prove it for is the pair the
     * documentation asks an operator to create. Without it, the documented grants could be weakened -
     * a {@code GRANT CREATE ON SCHEMA public}, a forgotten {@code REVOKE} - while the assertions above
     * went on passing against a stricter set that no deployment actually applies.</p>
     *
     * <p>It needs no server and no configuration, so it is not opt-in: the correspondence between the
     * test and the documentation is checked on every unit run.</p>
     *
     * @throws IOException if the operator documentation cannot be read
     */
    @Test
    public void theDocumentedGrantSequenceIsTheOneThisTestApplies() throws IOException {
        String documented = normaliseSql(Files.readString(repositoryRoot().resolve(OPERATOR_DOCUMENTATION),
                StandardCharsets.UTF_8));
        for (String statement : DOCUMENTED_GRANT_SEQUENCE) {
            assertTrue(documented.contains(normaliseSql(statement)),
                    OPERATOR_DOCUMENTATION + " must document the statement this test applies, and no longer"
                            + " declares [" + statement + "]. Either the documented grants were weakened, in"
                            + " which case restore them, or they were deliberately changed, in which case change"
                            + " this test and its privilege assertions with them.");
        }
    }

    /*
     * Opt-in gating
     */

    private static void requireConfigured() {
        List<String> missing = new ArrayList<>();
        if (setting(HOST_KEY, null) == null) {
            missing.add(HOST_KEY + " (or OFBIZ_TEST_POSTGRES_HOST), the host of a PostgreSQL server this test"
                    + " may create and drop databases and roles on");
        }
        if (setting(PASSWORD_KEY, null) == null) {
            missing.add(PASSWORD_KEY + " (or OFBIZ_TEST_POSTGRES_PASSWORD), the password of an account that may"
                    + " create roles and databases");
        }
        if (!missing.isEmpty()) {
            fail("the least-privilege checks need a real PostgreSQL server and these settings were not"
                    + " supplied: " + missing + ". Supply each as -D<name>=<value>, as -P<name>=<value>, or as"
                    + " the matching OFBIZ_TEST_POSTGRES_* environment variable, and run ./gradlew"
                    + " testExternalServices. This is a failure rather than a skip on purpose: only a server can"
                    + " establish that a serving role is REFUSED a schema change, so a skip here would report"
                    + " success for the one privilege claim nothing else in this repository can verify.");
        }
        assertTrue(isDriverAvailable(), DRIVER_CLASS + " must be on the test runtime classpath: it is bundled by "
                + DEPENDENCY_MANIFEST + ", so its absence is a build problem rather than a configuration one");
    }

    /**
     * Reads one injected setting, preferring a system property so that a value can be supplied on the
     * Gradle command line without restarting the daemon, and falling back to the equivalent
     * environment variable.
     *
     * @param key the system property name
     * @param fallback the value to use when neither source supplies one; may be null
     * @return the configured value, or {@code fallback}
     */
    private static String setting(String key, String fallback) {
        String property = System.getProperty(key);
        if (property != null && !property.isEmpty()) {
            return property;
        }
        String variable = "OFBIZ_TEST_" + key.substring("ofbiz.test.".length())
                .replace('.', '_').toUpperCase(Locale.ROOT);
        String environment = System.getenv(variable);
        if (environment != null && !environment.isEmpty()) {
            return environment;
        }
        return fallback;
    }

    private static boolean isDriverAvailable() {
        try {
            Class.forName(DRIVER_CLASS);
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }

    /*
     * Assertions
     */

    private static void assertAllowed(Connection connection, String label, String sql) {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException refused) {
            throw new AssertionError(label + " must be permitted but the server refused it with SQLSTATE "
                    + refused.getSQLState() + ": " + refused.getMessage(), refused);
        }
    }

    private static void assertRefused(Connection connection, String label, String sql) {
        SQLException refusal = assertThrows(SQLException.class, () -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }, "the serving role must not be able to " + label + ", but the server allowed [" + sql + "]");
        assertEquals(INSUFFICIENT_PRIVILEGE, refusal.getSQLState(),
                label + " must be refused as insufficient_privilege rather than failing for some other"
                        + " reason, which would make this a test of nothing; the server said: "
                        + refusal.getMessage());
        // Nothing has to be rolled back: the connection is left in JDBC's default autocommit mode, so a
        // refused statement is its own aborted transaction and the next assertion runs on a clean one.
        // Wrapping these in one explicit transaction would make the first refusal poison all the rest.
    }

    private static boolean booleanQuery(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet results = statement.executeQuery(sql)) {
            assertTrue(results.next(), "[" + sql + "] returned no row");
            boolean value = results.getBoolean(1);
            return !results.wasNull() && value;
        }
    }

    /*
     * Provisioning through the documented grant sequence
     */

    /** The three entity groups the documented procedure and the entry point both iterate over. */
    private enum Group {
        OFBIZ, OLAP, TENANT
    }

    /**
     * Creates, connects to and drops the throwaway databases and roles of one test by applying
     * {@link #DOCUMENTED_GRANT_SEQUENCE} over JDBC, so that what is proven is the documented grants.
     */
    private static final class Provisioning {
        private final String suffix = "lp" + Long.toHexString(new SecureRandom().nextLong() & 0xFFFFFFFFL);

        private String database(Group group) {
            return suffix + "_" + group.name().toLowerCase(Locale.ROOT) + "_db";
        }

        private String servingUser(Group group) {
            return suffix + "_" + group.name().toLowerCase(Locale.ROOT) + "_app";
        }

        private String initializationUser(Group group) {
            return suffix + "_" + group.name().toLowerCase(Locale.ROOT) + "_init";
        }

        private String servingPassword(Group group) {
            return "Serving-" + group.name() + "-" + suffix;
        }

        private String initializationPassword(Group group) {
            return "Initialize-" + group.name() + "-" + suffix;
        }

        /**
         * Applies the documented grant sequence to all three databases, exactly as an operator would.
         *
         * <p>The two roles are created first, then the cluster-level statements against the maintenance
         * database, then the schema-level statements from inside each database - which is the order the
         * documentation prescribes and the order the grants require, since a schema cannot be configured
         * before the database that holds it exists.
         *
         * @throws SQLException if the server refused a statement the documented procedure relies on,
         *     which fails the test rather than being reported as a pass
         */
        private void run() throws SQLException {
            try (Connection superuser = connectAsSuperuser()) {
                createRoles(superuser);
                for (Group group : Group.values()) {
                    for (String statement : CLUSTER_GRANTS) {
                        execute(superuser, substituted(statement, group));
                    }
                }
            }
            for (Group group : Group.values()) {
                try (Connection inDatabase = connect(database(group), setting(SUPERUSER_KEY, DEFAULT_SUPERUSER),
                        setting(PASSWORD_KEY, null))) {
                    for (String statement : SCHEMA_GRANTS) {
                        execute(inDatabase, substituted(statement, group));
                    }
                }
            }
        }

        /**
         * Creates the two roles of every group with the {@code LOGIN PASSWORD} shape the documentation
         * writes out, quoting the generated password as a literal.
         *
         * @param superuser the maintenance-database connection to create them through
         * @throws SQLException if a role cannot be created
         */
        private void createRoles(Connection superuser) throws SQLException {
            for (Group group : Group.values()) {
                execute(superuser, "CREATE ROLE " + quoteIdentifier(initializationUser(group))
                        + " LOGIN PASSWORD " + literal(initializationPassword(group)));
                execute(superuser, "CREATE ROLE " + quoteIdentifier(servingUser(group))
                        + " LOGIN PASSWORD " + literal(servingPassword(group)));
            }
        }

        /**
         * Rewrites one documented statement with this run's throwaway identifiers.
         *
         * <p>Substitution rather than a parameterised statement, because these are identifiers and no
         * JDBC placeholder may stand for one. The generated names are hexadecimal and underscore only,
         * and they are still quoted, so the statement cannot be steered by them.
         *
         * @param statement the documented statement
         * @param group the entity group it is being applied for
         * @return the statement to issue
         */
        private String substituted(String statement, Group group) {
            return statement
                    .replace(DOCUMENTED_DATABASE, quoteIdentifier(database(group)))
                    .replace(DOCUMENTED_INIT_ROLE, quoteIdentifier(initializationUser(group)))
                    .replace(DOCUMENTED_SERVING_ROLE, quoteIdentifier(servingUser(group)));
        }

        private void execute(Connection connection, String sql) throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }

        private Connection connectAsServing(Group group) throws SQLException {
            return connect(database(group), servingUser(group), servingPassword(group));
        }

        private Connection connectAsInitialization(Group group) throws SQLException {
            return connect(database(group), initializationUser(group), initializationPassword(group));
        }

        private Connection connectAsSuperuser() throws SQLException {
            return connect(MAINTENANCE_DATABASE, setting(SUPERUSER_KEY, DEFAULT_SUPERUSER),
                    setting(PASSWORD_KEY, null));
        }

        private Connection connect(String database, String user, String password) throws SQLException {
            String url = "jdbc:postgresql://" + setting(HOST_KEY, null) + ":" + setting(PORT_KEY, DEFAULT_PORT)
                    + "/" + database + "?sslmode=" + setting(SSLMODE_KEY, DEFAULT_SSLMODE);
            Connection connection = DriverManager.getConnection(url, user, password);
            assertNotNull(connection, "could not connect to " + database + " as " + user);
            return connection;
        }

        private boolean databaseExists(Group group) throws SQLException {
            try (Connection superuser = connectAsSuperuser()) {
                return booleanQuery(superuser, "SELECT EXISTS (SELECT 1 FROM pg_database WHERE datname = "
                        + literal(database(group)) + ")");
            }
        }

        /**
         * Removes everything this test created, whether or not the test passed, and reports what it
         * could not remove instead of failing silently - a leaked role would make the next run fail for
         * a reason that has nothing to do with the code under test.
         */
        private void drop() {
            List<String> leaked = new ArrayList<>();
            try (Connection superuser = connectAsSuperuser()) {
                for (Group group : Group.values()) {
                    // WITH (FORCE) so a connection this test failed to close cannot block the cleanup.
                    dropQuietly(superuser, leaked, "DROP DATABASE IF EXISTS "
                            + quoteIdentifier(database(group)) + " WITH (FORCE)");
                }
                for (Group group : Group.values()) {
                    dropQuietly(superuser, leaked, "DROP ROLE IF EXISTS "
                            + quoteIdentifier(servingUser(group)));
                    dropQuietly(superuser, leaked, "DROP ROLE IF EXISTS "
                            + quoteIdentifier(initializationUser(group)));
                }
            } catch (SQLException unreachable) {
                leaked.add("could not connect to clean up: " + unreachable.getMessage());
            }
            assertTrue(leaked.isEmpty(), "the test left objects behind on the server: " + leaked);
        }

        private void dropQuietly(Connection connection, List<String> leaked, String sql) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            } catch (SQLException failure) {
                leaked.add(sql + " -> " + failure.getMessage());
            }
        }
    }

    /*
     * Helpers
     */

    /**
     * Quotes an identifier the way PostgreSQL requires, doubling any embedded quote. The identifiers
     * this test builds are generated and safe, but a statement assembled by concatenation should quote
     * regardless - the alternative is a pattern that is only safe until it is copied.
     *
     * @param identifier the identifier to quote
     * @return the identifier, double-quoted
     */
    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static String literal(String value) {
        return '\'' + value.replace("'", "''") + '\'';
    }

    /**
     * Collapses every run of whitespace to one space, so that the documentation's line wrapping cannot
     * make a statement it does declare look absent.
     *
     * <p>Only whitespace is normalised. Role names, privilege lists, object classes and the direction of
     * each grant are compared verbatim, which is the whole point: a {@code GRANT} that gained
     * {@code CREATE}, or a {@code REVOKE} that was dropped, changes the compared text.
     *
     * @param sql the text to normalise
     * @return the text with each whitespace run replaced by one space
     */
    private static String normaliseSql(String sql) {
        return sql.replaceAll("\\s+", " ");
    }

    private static Path repositoryRoot() {
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve(DEPENDENCY_MANIFEST))) {
            candidate = candidate.getParent();
        }
        assertNotNull(candidate, "could not locate the repository root from " + Paths.get("").toAbsolutePath());
        return candidate;
    }
}
