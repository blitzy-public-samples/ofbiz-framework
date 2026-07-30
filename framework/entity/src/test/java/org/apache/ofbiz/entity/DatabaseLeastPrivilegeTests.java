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
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Proves the database least-privilege posture of Objective 4 against a real PostgreSQL server: the
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
 * <p>What is exercised is the artefact the project ships, not a copy of it. The two accounts are
 * provisioned by running {@code docker/examples/postgres-demo/postgres-initdb.d/10-init-user-db.sh}
 * - the script an operator's PostgreSQL service actually runs - so a change that weakens the grants
 * in that script fails this test. The grants it applies are written out for a single database, with
 * the reasoning behind each one, under <i>Database roles and least privilege</i> in
 * {@code DOCKER.adoc}.</p>
 *
 * <p><b>This test is opt-in and is skipped by default</b>, because it needs a PostgreSQL server that
 * it may create and drop databases and roles on, which a unit tier cannot assume. Supply the
 * connection details to run it, either as Gradle command-line system properties:</p>
 *
 * <pre>
 * ./gradlew test --tests '*DatabaseLeastPrivilegeTests*' \
 *     -Dofbiz.test.postgres.host=127.0.0.1 \
 *     -Dofbiz.test.postgres.password="the superuser password"
 * </pre>
 *
 * <p>or as environment variables ({@code OFBIZ_TEST_POSTGRES_HOST},
 * {@code OFBIZ_TEST_POSTGRES_PORT}, {@code OFBIZ_TEST_POSTGRES_SUPERUSER},
 * {@code OFBIZ_TEST_POSTGRES_PASSWORD}, {@code OFBIZ_TEST_POSTGRES_SSLMODE}). The account supplied
 * must be able to create roles and databases. Every object this test creates carries a per-run random
 * suffix and is dropped again, so it neither collides with a concurrent run nor leaves anything
 * behind, and it never touches a database it did not create.</p>
 */
public class DatabaseLeastPrivilegeTests {

    /** Located by walking up from the working directory, so the suite runs from any module. */
    private static final String DEPENDENCY_MANIFEST = "dependencies.gradle";

    /** The provisioning script this test exercises rather than re-implements. */
    private static final String PROVISIONING_SCRIPT =
            "docker/examples/postgres-demo/postgres-initdb.d/10-init-user-db.sh";

    /** Opt-in configuration. Absent host or password means the whole class is skipped. */
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

    /** Long enough that a slow provisioning run does not fail, short enough that a hang is a failure. */
    private static final long SCRIPT_TIMEOUT_SECONDS = 180;

    /**
     * Two entity-model-shaped tables, created by the initialization role exactly as the init execution
     * would create them, and then used to prove what the serving role can and cannot do to them.
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
     * ---------------------------------------------------------------------------------------------
     * The proof
     * ---------------------------------------------------------------------------------------------
     */

    /**
     * The serving role moves rows and is refused every schema change; the initialization role makes
     * the schema in the first place.
     *
     * <p>The order matters and is the point of the test. The tables are created by the initialization
     * role <em>after</em> the provisioning script has run, which is the real sequence - the grants are
     * applied to an empty database and the schema arrives later, from the init execution. The serving
     * role's row privileges on those tables therefore cannot have come from a grant naming them; they
     * come from {@code ALTER DEFAULT PRIVILEGES}, which is what makes the arrangement survive the next
     * schema-affecting release without a follow-up grant. If that statement were dropped from the
     * provisioning script, the permitted half of this test would fail rather than the refused half.</p>
     *
     * @throws Exception if the server could not be reached or the provisioning script could not be
     *         run, either of which fails the test rather than being reported as a pass
     */
    @Test
    public void theServingRoleMovesRowsAndIsRefusedEverySchemaChange() throws Exception {
        assumeConfigured();
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
     * @throws Exception if the server could not be reached or the provisioning script could not be run
     */
    @Test
    public void theServingRoleCannotBecomeTheInitializationRole() throws Exception {
        assumeConfigured();
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
     * The provisioning script refuses to create a pair of accounts that is not really a pair, and
     * refuses an incomplete one, rather than provisioning databases in a shape the container would
     * later reject.
     *
     * <p>Both refusals are asserted against the first group the script handles, so the assertion that
     * nothing was created is meaningful: a script that failed on the third group would already have
     * provisioned the first two.</p>
     *
     * @throws Exception if the server could not be reached or the provisioning script could not be run
     */
    @Test
    public void theProvisioningScriptRefusesAnIdentityThatIsNotSeparate() throws Exception {
        assumeConfigured();
        Provisioning provisioning = new Provisioning();
        try {
            Map<String, String> notSeparate = provisioning.environment();
            notSeparate.put("OFBIZ_POSTGRES_OFBIZ_INIT_USER", provisioning.servingUser(Group.OFBIZ));
            ScriptRun reused = provisioning.runExpectingFailure(notSeparate);
            assertTrue(reused.output().contains("OFBIZ_POSTGRES_OFBIZ_INIT_USER")
                            && reused.output().contains("name the same role"),
                    "the refusal must say which two variables collided, output was:\n" + reused.output());
            assertFalse(provisioning.databaseExists(Group.OFBIZ),
                    "nothing may be provisioned when the identities are not separate");

            Map<String, String> incomplete = provisioning.environment();
            incomplete.remove("OFBIZ_POSTGRES_OFBIZ_INIT_PASSWORD");
            ScriptRun missing = provisioning.runExpectingFailure(incomplete);
            assertTrue(missing.output().contains("OFBIZ_POSTGRES_OFBIZ_INIT_PASSWORD"),
                    "the refusal must name the missing variable, output was:\n" + missing.output());
            assertFalse(provisioning.databaseExists(Group.OFBIZ),
                    "a role must never be created without the password it was supposed to be given");
        } finally {
            provisioning.drop();
        }
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Opt-in gating
     * ---------------------------------------------------------------------------------------------
     */

    private static void assumeConfigured() {
        assumeTrue(setting(HOST_KEY, null) != null,
                "set -D" + HOST_KEY + " (or OFBIZ_TEST_POSTGRES_HOST) to run the least-privilege test against"
                        + " a PostgreSQL server");
        assumeTrue(setting(PASSWORD_KEY, null) != null,
                "set -D" + PASSWORD_KEY + " (or OFBIZ_TEST_POSTGRES_PASSWORD) to the password of a PostgreSQL"
                        + " account that may create roles and databases");
        assumeTrue(isCommandAvailable("bash", "-c", "exit 0"),
                "a POSIX shell is required to run the provisioning script this test exercises");
        assumeTrue(isCommandAvailable("psql", "--version"),
                "the psql client is required: the provisioning script this test exercises uses it");
        assumeTrue(isDriverAvailable(), DRIVER_CLASS + " must be on the test runtime classpath");
    }

    /**
     * Reads one opt-in setting, preferring a system property so that a value can be supplied on the
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

    private static boolean isCommandAvailable(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            // Drained before waiting, and closed, so neither a probe that prints more than the pipe
            // buffer holds nor a repeated availability check can leave the JVM holding a pipe open.
            try (InputStream output = process.getInputStream()) {
                output.readAllBytes();
            }
            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (IOException unavailable) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Assertions
     * ---------------------------------------------------------------------------------------------
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
     * ---------------------------------------------------------------------------------------------
     * Provisioning through the shipped script
     * ---------------------------------------------------------------------------------------------
     */

    /** The three entity groups the provisioning script and the entry point both iterate over. */
    private enum Group {
        OFBIZ, OLAP, TENANT
    }

    /** The outcome of one run of the provisioning script. */
    private static final class ScriptRun {
        private final int exitCode;
        private final String output;

        private ScriptRun(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        private int exitCode() {
            return exitCode;
        }

        private String output() {
            return output;
        }
    }

    /**
     * Creates, connects to and drops the throwaway databases and roles of one test, driving the shipped
     * provisioning script for the creation half so that what is proven is the shipped grants.
     */
    private static final class Provisioning {
        private final String suffix = "lp" + Long.toHexString(new SecureRandom().nextLong() & 0xFFFFFFFFL);
        private final Path script = repositoryRoot().resolve(PROVISIONING_SCRIPT);

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
         * The environment the compose example gives the PostgreSQL service, with this run's throwaway
         * names. Returned mutable so a test can break exactly one value and assert the refusal.
         *
         * @return the fifteen provisioning variables plus the psql connection settings
         */
        private Map<String, String> environment() {
            Map<String, String> environment = new LinkedHashMap<>();
            for (Group group : Group.values()) {
                environment.put("OFBIZ_POSTGRES_" + group + "_DB", database(group));
                environment.put("OFBIZ_POSTGRES_" + group + "_USER", servingUser(group));
                environment.put("OFBIZ_POSTGRES_" + group + "_PASSWORD", servingPassword(group));
                environment.put("OFBIZ_POSTGRES_" + group + "_INIT_USER", initializationUser(group));
                environment.put("OFBIZ_POSTGRES_" + group + "_INIT_PASSWORD", initializationPassword(group));
            }
            // psql reads these, so the script needs no modification to reach a server that is not local.
            environment.put("PGHOST", setting(HOST_KEY, null));
            environment.put("PGPORT", setting(PORT_KEY, DEFAULT_PORT));
            environment.put("PGPASSWORD", setting(PASSWORD_KEY, null));
            environment.put("PGSSLMODE", setting(SSLMODE_KEY, DEFAULT_SSLMODE));
            return environment;
        }

        private void run() throws Exception {
            ScriptRun run = execute(environment());
            assertEquals(0, run.exitCode(), "the shipped provisioning script must succeed, output was:\n"
                    + run.output());
            for (Group group : Group.values()) {
                assertTrue(run.output().contains(database(group)),
                        "the script must report what it provisioned for " + group + ", output was:\n"
                                + run.output());
            }
        }

        private ScriptRun runExpectingFailure(Map<String, String> environment) throws Exception {
            ScriptRun run = execute(environment);
            assertNotEquals(0, run.exitCode(),
                    "the provisioning script must refuse this configuration, output was:\n" + run.output());
            return run;
        }

        private ScriptRun execute(Map<String, String> environment) throws Exception {
            assertTrue(Files.isRegularFile(script), "the provisioning script must exist at " + script);
            ProcessBuilder builder = new ProcessBuilder("bash", script.toString());
            builder.directory(repositoryRoot().toFile());
            builder.redirectErrorStream(true);
            // Replaced rather than added to: the ambient environment of a build agent may already carry
            // OFBIZ_POSTGRES_* values, and inheriting one would make this test's outcome depend on them.
            builder.environment().keySet().removeIf(name -> name.startsWith("OFBIZ_POSTGRES_")
                    || name.startsWith("PG"));
            builder.environment().putAll(environment);

            Process process = builder.start();
            String output;
            try (InputStream stream = process.getInputStream()) {
                output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            assertTrue(process.waitFor(SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "the provisioning script did not finish within " + SCRIPT_TIMEOUT_SECONDS
                            + " seconds, output so far was:\n" + output);
            return new ScriptRun(process.exitValue(), output);
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
     * ---------------------------------------------------------------------------------------------
     * Helpers
     * ---------------------------------------------------------------------------------------------
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

    private static Path repositoryRoot() {
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve(DEPENDENCY_MANIFEST))) {
            candidate = candidate.getParent();
        }
        assertNotNull(candidate, "could not locate the repository root from " + Paths.get("").toAbsolutePath());
        return candidate;
    }
}
