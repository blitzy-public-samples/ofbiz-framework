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

import static java.util.Map.entry;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.SAXParseException;

/**
 * Executable coverage of the schema-init half of AAP Objective 4, and of the entity-engine rendering the
 * container entry point performs for the deployed profile.
 *
 * <p>{@link SchemaInitGatingTests} pins the RUN mode by reading the committed configuration: the managed
 * datasources resolve to "issue no DDL". That is only half of Objective 4, and it is the half a committed
 * constant can express. The other half is machinery - {@code OFBIZ_SCHEMA_INIT}, the
 * {@code @CHECK_ON_START@} and {@code @ADD_MISSING_ON_START@} substitutions over
 * {@code docker/templates/postgres-entityengine.xml}, the {@code readers=none} load that
 * creates the delegator so the DDL is applied, and the one-shot exit that keeps an init run from becoming a
 * serving instance - and none of it lives in a file whose text can be asserted. Deleting any of it would leave
 * every static assertion in this repository green while the deployed fleet either issued DDL on every boot or
 * never had a schema created at all. This suite therefore RUNS the real
 * {@code docker/docker-entrypoint.sh}.</p>
 *
 * <p>How it runs it, and why that is faithful rather than a re-implementation:</p>
 * <ul>
 * <li>The shipped script is read from the repository and copied verbatim with exactly one line removed - its
 * trailing {@code _main "$@"} invocation - so that its functions can be called individually instead of
 * starting OFBiz. Not one character of logic is rewritten, so a change to any function under test changes what
 * these tests execute.</li>
 * <li>Every absolute container path the script writes to ({@code /ofbiz/runtime/container_state},
 * {@code /ofbiz/lib-extra}, the catalina descriptor) is reassigned into a JUnit {@code @TempDir}, and the
 * driver {@code cd}s into that directory so the script's relative paths - the committed
 * {@code entityengine.xml}, the render template and the generated {@code config/} override - resolve inside the
 * sandbox. Nothing in the repository working tree is written, no container is started, no database is
 * contacted and no network is used.</li>
 * <li>{@code OFBIZ_PROFILE} is supplied by the driver as {@code dev} unless a case names it, because the
 * functions under test read it and are normally reached after {@code require_profile} has resolved it. The
 * script resolves the same default itself, announcing it on stderr; the cases that exercise the script's own
 * resolution - rather than the driver's - use {@code runWithoutProfileDefault}, which omits the driver's
 * assignment entirely.</li>
 * <li>Where a run has to establish that a command WOULD have been invoked - the data loader, the serving
 * command - a shell function of the same name records its arguments instead of executing. No data loader and
 * no Entity Engine therefore run: what a case observes is the invocation that would have happened, not its
 * effect on a database. Bash resolves a function whose name contains a slash ahead of the path, which is what
 * lets {@code /ofbiz/bin/ofbiz} be intercepted without creating anything outside the sandbox.</li>
 * <li>Every {@code OFBIZ_*} variable is removed from the child environment before each run, so a value set on
 * the developer's machine or the build agent cannot decide the outcome of a case.</li>
 * </ul>
 *
 * <p>The values substituted here are synthetic: an unroutable example host and passwords invented for this
 * file. Nothing in it is a credential of any system, and the suite asserts that none of those values reaches
 * the process output, because the container log is the one place a rendered secret would be published.</p>
 *
 * <p>Deliberately NOT restated here: the static shape of the committed configuration and of the render
 * template. {@code EntityEngineConfigContractTests} already compares complete structured maps of both files,
 * including the template's placeholder inventory, its TLS placeholder on every managed URI and its schema
 * validity after an in-Java substitution. What this suite adds is the dimension that one cannot reach - that
 * the real shell, run as the container runs it, produces those artefacts and refuses the ones it must
 * refuse.</p>
 */
public final class SchemaInitEntryPointTests {

    /** The entry point under test, read from the repository and executed as a library. */
    private static final String ENTRY_POINT = "docker/docker-entrypoint.sh";

    /** The pristine committed configuration, which is the render source for the embedded profile. */
    private static final String ENTITY_ENGINE_SOURCE = "framework/entity/config/entityengine.xml";

    /** The deployed-profile render template, which the entry point resolves relative to its working directory. */
    private static final String ENTITY_ENGINE_TEMPLATE = "templates/postgres-entityengine.xml";

    /** Where the template lives in the repository; the sandbox receives a copy at {@link #ENTITY_ENGINE_TEMPLATE}. */
    private static final String REPOSITORY_TEMPLATE = "docker/templates/postgres-entityengine.xml";

    /** The administrator data template {@code load_admin_user} populates, at the same path in both trees. */
    private static final String ADMIN_DATA_TEMPLATE = "framework/resources/templates/AdminUserLoginData.xml";

    /** The generated override. {@code /ofbiz/config} precedes {@code ofbiz.jar}, so this is what OFBiz reads. */
    private static final String RENDERED_OVERRIDE = "config/entityengine.xml";

    /** The local grammar the rendered configuration is validated against, so validation stays offline. */
    private static final String ENTITY_CONFIG_SCHEMA = "framework/entity/dtd/entity-config.xsd";

    /** Marks the repository root when walking up from the working directory. */
    private static final String DEPENDENCY_MANIFEST = "dependencies.gradle";

    /** Any at-sign delimited render placeholder. */
    private static final Pattern PLACEHOLDER = Pattern.compile("@[A-Z_0-9]+@");

    /**
     * Every placeholder {@code render_database_configuration} writes a substitution for, in the order the
     * function emits them. Compared against the placeholders the committed template actually carries, so a
     * template that gains a twenty-first token - which would render unsubstituted - fails here.
     *
     * <p>The startup-DDL mode occupies TWO placeholders rather than one. {@code Datasource} reads
     * {@code check-on-start} as {@code !"false".equals(value)} and {@code add-missing-on-start} as
     * {@code "true".equals(value)}, so the two attributes are not interchangeable and the verification pass
     * of the one-shot initialisation needs the pair set to different literals at the same time - which a
     * single substituted token cannot express.</p>
     */
    private static final List<String> SUBSTITUTED_TOKENS = List.of(
            "@HOST@", "@PORT@", "@OFBIZ_DB@", "@OFBIZ_USERNAME@", "@OFBIZ_PASSWORD@",
            "@OLAP_DB@", "@OLAP_USERNAME@", "@OLAP_PASSWORD@",
            "@TENANT_DB@", "@TENANT_USERNAME@", "@TENANT_PASSWORD@",
            "@SSL_PARAMS@", "@JDBC_PARAMS@",
            "@DB_POOL_MIN@", "@DB_POOL_MAX@", "@DB_POOL_WAIT@", "@DB_POOL_TEST_ON_BORROW@",
            "@CHECK_ON_START@", "@ADD_MISSING_ON_START@", "@DISTRIBUTED_CACHE_CLEAR@");

    /** The managed-RDBMS datasources the deployed profile binds the two default delegators to. */
    private static final List<String> MANAGED_DATASOURCES =
            List.of("localpostgres", "localpostgresolap", "localpostgrestenant");

    /** The embedded datasources that must keep provisioning themselves, in every render, in every mode. */
    private static final List<String> EMBEDDED_DATASOURCES = List.of("localh2", "localh2olap", "localh2tenant");

    /** The two delegators whose cache-clear attribute the entry point rewrites. {@code test} is never one. */
    private static final List<String> CACHE_CLEAR_DELEGATORS = List.of("default", "default-no-eca");

    /** The frozen entity groups, and the embedded datasource the {@code test} delegator must keep each one on. */
    private static final Map<String, String> TEST_DELEGATOR_MAPPING = Map.of(
            "org.apache.ofbiz", "localh2",
            "org.apache.ofbiz.olap", "localh2olap",
            "org.apache.ofbiz.tenant", "localh2tenant");

    /**
     * The frozen entity groups, and the managed datasource the deployed profile must resolve each one to. This
     * is the whole of "PostgreSQL becomes the default for the deployed profile": the group and delegator names
     * are immutable interfaces, so the only thing the render may change is which datasource each one points at.
     */
    private static final Map<String, String> MANAGED_DELEGATOR_MAPPING = Map.of(
            "org.apache.ofbiz", "localpostgres",
            "org.apache.ofbiz.olap", "localpostgresolap",
            "org.apache.ofbiz.tenant", "localpostgrestenant");

    private static final String CHECK_ON_START = "check-on-start";
    private static final String ADD_MISSING_ON_START = "add-missing-on-start";
    private static final String CACHE_CLEAR_ENABLED = "distributed-cache-clear-enabled";

    /** An unroutable documentation host: this suite never opens a connection, and must not be able to. */
    private static final String DATABASE_HOST = "db.example.internal";
    private static final String DATABASE_PORT = "65432";
    private static final String OFBIZ_DATABASE = "ofbizmaindb";
    private static final String OLAP_DATABASE = "ofbizolapdb";
    private static final String TENANT_DATABASE = "ofbiztenantdb";
    private static final String OFBIZ_USER = "ofbizmainuser";
    private static final String OLAP_USER = "ofbizolapuser";
    private static final String TENANT_USER = "ofbiztenantuser";

    /**
     * Synthetic passwords, invented for this file and used nowhere else. They are long enough and varied
     * enough to clear the prod-profile floor, and none of them is one of the published values the entry point
     * refuses outright, so a case that fails does so for the reason it is testing.
     */
    /**
     * Deployment secrets for the cases that assert a start SUCCEEDS in the prod profile, which requires every
     * one of them to be present. Invented for this file, used nowhere else, and long and varied enough to
     * clear the prod-profile strength floors, so a case that fails does so for the reason it is testing.
     */
    private static final String PROFILE_ADMIN_KEY = "Profile-Admin-Key-6mR3qd";
    private static final String PROFILE_LOGIN_SECRET_KEY = "Profile-Login-Secret-2vN9hb";
    private static final String PROFILE_JWT_TOKEN_KEY = "Profile-Jwt-Token-Key-4cF7ls";
    private static final String PROFILE_ADMIN_PASSWORD = "Profile-Admin-Password-8tG5xn";

    private static final String OFBIZ_PASSWORD = "Main-Password-7yQ2xw";
    private static final String OLAP_PASSWORD = "Olap-Password-3zX8kt";
    private static final String TENANT_PASSWORD = "Tenant-Password-5wK4vp";

    /**
     * The schema-initialisation database identity: the roles that may create and alter the schema, which only
     * the one-shot init run ever authenticates as. Every value differs from every serving value, because that
     * is what the entry point requires - a shared role name or a shared password is no separation at all.
     */
    private static final String OFBIZ_INIT_USER = "ofbizmainddl";
    private static final String OLAP_INIT_USER = "ofbizolapddl";
    private static final String TENANT_INIT_USER = "ofbiztenantddl";
    private static final String OFBIZ_INIT_PASSWORD = "Main-Init-Password-2vB6hn";
    private static final String OLAP_INIT_PASSWORD = "Olap-Init-Password-9qD4jr";
    private static final String TENANT_INIT_PASSWORD = "Tenant-Init-Password-4mF7ly";

    /** Printed by the rendered-identity validator driver once the validator has returned. */
    private static final String IDENTITY_VERIFIED = "IDENTITY_VERIFIED";

    /**
     * Synthetic deployment secrets, invented for this file and used nowhere else. Each clears the floor its
     * own variable is checked against - 16 characters for the admin password and the admin shared key, 64 for
     * the two signing keys - so a case that fails does so for the reason it is testing. None of them is a
     * credential of any system, and this suite asserts that none of them reaches the process output.
     */
    private static final String ADMIN_PASSWORD = "Admin-Password-6pR3nq";
    private static final String ADMIN_KEY = "Admin-Shared-Key-4tB9zm";
    private static final String LOGIN_KEY =
            "SyntheticLoginSigningKeyForTheEntryPointHookTests-0123456789abcdef";
    private static final String JWT_KEY =
            "SyntheticJwtTokenSigningKeyForTheEntryPointHookTests-9876543210zyxw";

    /** The only TLS mode that both encrypts the connection and authenticates the server on the other end. */
    private static final String SSL_MODE = "verify-full";
    private static final String SSL_ROOT_CERTIFICATE = "/etc/ssl/certs/pg-root.crt";

    /**
     * The connection deadlines {@code @JDBC_PARAMS@} resolves to when none of the deadline variables is
     * supplied, in the order the shell appends them.
     *
     * <p>Asserted rather than ignored because an unbounded socket read is what the parameters exist to
     * prevent: pgJDBC blocks forever on a read from a database that stopped answering, and the same block
     * strands the start-up entity check and the readiness probe. {@code queryTimeout} is absent on purpose -
     * it defaults to zero, which is pgJDBC's own "no statement deadline", and the shell omits the parameter
     * altogether so the rendered URI states only the deadlines that are really in force.</p>
     */
    private static final String JDBC_DEADLINE_QUERY =
            "&connectTimeout=10&socketTimeout=60&loginTimeout=30&cancelSignalTimeout=10&tcpKeepAlive=true";
    private static final String POOL_MIN = "7";
    private static final String POOL_MAX = "77";

    /** The desired-state record the render writes, relative to the sandbox. */
    private static final String DB_CONFIG_APPLIED_MARKER = "state/db_config_applied";

    /** The first line of a record written before the fields the current format carries existed. */
    private static final String EARLIER_RECORD_VERSION = "version=1";

    /**
     * One alternative value for every non-secret input the managed render substitutes.
     *
     * <p>Each is valid - the render has to succeed for the case to mean anything - and each differs from what
     * {@link #managedDatabaseEnvironment} supplies, so applying it produces a configuration that genuinely
     * differs from the baseline. That is what makes the coverage assertion non-vacuous: a field the record
     * omits shows up as a rendered difference the record failed to notice, which is exactly the defect this
     * table exists to catch.</p>
     */
    private static final Map<String, String> NON_SECRET_RENDER_INPUTS = Map.ofEntries(
            entry("OFBIZ_POSTGRES_HOST", "other-db.example.internal"),
            entry("OFBIZ_POSTGRES_PORT", "65431"),
            entry("OFBIZ_POSTGRES_OFBIZ_DB", OFBIZ_DATABASE + "two"),
            entry("OFBIZ_POSTGRES_OLAP_DB", OLAP_DATABASE + "two"),
            entry("OFBIZ_POSTGRES_TENANT_DB", TENANT_DATABASE + "two"),
            entry("OFBIZ_POSTGRES_OFBIZ_USER", OFBIZ_USER + "two"),
            entry("OFBIZ_POSTGRES_OLAP_USER", OLAP_USER + "two"),
            entry("OFBIZ_POSTGRES_TENANT_USER", TENANT_USER + "two"),
            entry("OFBIZ_POSTGRES_SSLMODE", "require"),
            entry("OFBIZ_POSTGRES_SSLROOTCERT", "/etc/ssl/certs/pg-other-root.crt"),
            entry("OFBIZ_POSTGRES_CONNECT_TIMEOUT", "9"),
            entry("OFBIZ_POSTGRES_SOCKET_TIMEOUT", "61"),
            entry("OFBIZ_POSTGRES_LOGIN_TIMEOUT", "31"),
            entry("OFBIZ_POSTGRES_CANCEL_TIMEOUT", "11"),
            entry("OFBIZ_POSTGRES_QUERY_TIMEOUT", "45"),
            entry("OFBIZ_POSTGRES_TCP_KEEPALIVE", "false"),
            entry("OFBIZ_DB_POOL_MIN", "8"),
            entry("OFBIZ_DB_POOL_MAX", "78"),
            entry("OFBIZ_DB_POOL_WAIT", "20001"),
            entry("OFBIZ_DB_POOL_TEST_ON_BORROW", "true"),
            entry("OFBIZ_SCHEMA_INIT", "true"),
            entry("OFBIZ_DISTRIBUTED_CACHE_CLEAR", "true"),
            entry("OFBIZ_PROFILE", "prod"));

    /**
     * Every count the entry point's prose states about a list it declares, and which members that count.
     *
     * <p>A comment that says "the six variables" is a claim about an array a few hundred lines away, and a
     * shell comment cannot compute it. Registering the claim here is what makes the restatement a CHECKED
     * property instead of a promise: the count is derived from the array and compared with the word the prose
     * uses, so changing the array without the comment fails, and rewording the comment without registering the
     * new wording fails too.</p>
     *
     * <p>The value is the declaring array, optionally followed by {@code #} and a pattern the members that are
     * being counted must match - which is how "the nine OFBIZ_S3_* variables" is checked against the nine
     * object-store names inside a ten-name array.</p>
     */
    private static final Map<String, String> STATED_LIST_SIZES = Map.ofEntries(
            entry("These six variables are what actually remove the privilege from the serving path.",
                    "POSTGRES_INIT_IDENTITY_VARIABLES"),
            entry("The six variables that carry the schema-initialisation database identity.",
                    "POSTGRES_INIT_IDENTITY_VARIABLES"),
            entry("The six init variables are supplied together or not at all.",
                    "POSTGRES_INIT_IDENTITY_VARIABLES"),
            entry("The ten variables that carry the object-store configuration",
                    "CONTENT_STORE_VARIABLES"),
            entry("The ten OFBIZ_CONTENT_STORE_PROVIDER / OFBIZ_S3_* variables are what Objective 3 advertises",
                    "CONTENT_STORE_VARIABLES"),
            entry("This and the nine OFBIZ_S3_* variables below",
                    "CONTENT_STORE_VARIABLES#OFBIZ_S3_.*"),
            entry("all ten are then removed from the environment before the OFBiz JVM is exec'd",
                    "CONTENT_STORE_VARIABLES"),
            entry("the six database passwords",
                    "SECRET_ENVIRONMENT_VARIABLES#OFBIZ_POSTGRES_.*_PASSWORD"),
            entry("the object store's two credentials",
                    "SECRET_ENVIRONMENT_VARIABLES#OFBIZ_S3_.*"),
            entry("the two signing keys",
                    "SECRET_ENVIRONMENT_VARIABLES#OFBIZ_(LOGIN_SECRET|JWT_TOKEN)_KEY"));

    /**
     * The one place the entry point counts variables without describing an inventory.
     *
     * <p>It counts two shell globals declared beside each other, which the reader can see in the same comment.
     * Named explicitly rather than pattern-excluded, so it cannot become a way to leave a real inventory claim
     * unchecked, and asserted to be present, so it cannot quietly rot into a stale exclusion.</p>
     */
    private static final String LOCAL_VARIABLE_COUNT =
            "Two variables rather than one because they are consulted at different points";

    /** The claim about the SDK's own variables, whose count is of names the same sentence lists. */
    private static final String SDK_VARIABLES_CLAIM = "SIX AWS_ PREFIXED NAMES ARE DELIBERATELY NOT TOUCHED";

    /** How far past that claim its enumeration reaches. The prose holds no other {@code AWS_} name. */
    private static final int SDK_CLAIM_ENUMERATION_LENGTH = 200;

    /** The number words the entry point's prose uses, which is as far as any count in it goes. */
    private static final Map<String, Integer> NUMBER_WORDS = Map.ofEntries(
            entry("two", 2), entry("three", 3), entry("four", 4), entry("five", 5), entry("six", 6),
            entry("seven", 7), entry("eight", 8), entry("nine", 9), entry("ten", 10), entry("eleven", 11),
            entry("twelve", 12), entry("thirteen", 13), entry("fourteen", 14));

    /** Where the loader stub records what it was asked to load, relative to the sandbox. */
    private static final String LOADER_INVOCATIONS = "loader-invocations.txt";

    /** The schema-only load: a reader no component declares, so a delegator is created and no reader row is read. */
    private static final String SCHEMA_ONLY_LOAD = "--load-data readers=none";

    /** The two data-load selections that read rows, exactly as {@code load_data} invokes them. */
    private static final String SEED_LOAD = "--load-data readers=seed,seed-initial";
    private static final String DEMO_LOAD = "--load-data";

    /** The container state markers, relative to the sandbox the driver points the state directory at. */
    private static final String DATA_LOADED_MARKER = "state/data_loaded";
    private static final String ADMIN_LOADED_MARKER = "state/admin_loaded";

    /**
     * Resolves the three mode flags through the real shell and reports what they normalised to, in the order
     * {@code _main} resolves them - {@code resolve_skip_init} first, because the schema-init conflict check
     * compares the parsed skip-init boolean rather than the mere presence of the variable.
     */
    private static final String RESOLVE_FLAGS_BODY =
            "resolve_skip_init\n"
            + "resolve_entity_engine_flags\n"
            + "printf 'SKIP=%s\\n' \"$RESOLVED_SKIP_INIT\"\n"
            + "printf 'SCHEMA_INIT=%s\\n' \"$RESOLVED_SCHEMA_INIT\"\n"
            + "printf 'CACHE_CLEAR=%s\\n' \"$RESOLVED_DISTRIBUTED_CACHE_CLEAR\"\n";

    /** Resolves the flags and then renders, which is the order {@code _main} uses. */
    private static final String CONFIGURE_DATABASE_BODY =
            RESOLVE_FLAGS_BODY
            + "configure_database\n"
            + "printf 'CONFIGURE_DATABASE_RETURNED\\n'\n";

    /**
     * Reports the {@code OFBIZ_} names in the environment either side of the real withdrawal.
     *
     * <p>{@code env} is a child process, so what it lists is what the entry point would hand to the server it
     * execs. Only names are printed; a value is never echoed, so the fixture cannot itself republish one.</p>
     *
     * <p>Tracing is turned off first because {@code OFBIZ_TRACE} has to be PRESENT for its own withdrawal to be
     * exercised, and any non-empty value turns tracing on as the script is sourced - which would interleave the
     * trace of these very commands with the two name listings they produce. What tracing does and does not
     * publish is asserted by the cases that are about tracing; this one is about the environment.</p>
     */
    private static final String WITHDRAWAL_BODY =
            "set +x\n"
            + "printf 'BEFORE-BEGIN\\n'\n"
            + "env | sed --quiet 's/^\\(OFBIZ_[A-Z0-9_]*\\)=.*/\\1/p' | sort\n"
            + "printf 'BEFORE-END\\n'\n"
            + "withdraw_container_configuration\n"
            + "printf 'AFTER-BEGIN\\n'\n"
            + "env | sed --quiet 's/^\\(OFBIZ_[A-Z0-9_]*\\)=.*/\\1/p' | sort\n"
            + "printf 'AFTER-END\\n'\n"
            + "printf 'ENVIRONMENT-BEGIN\\n'\n"
            + "env\n"
            + "printf 'ENVIRONMENT-END\\n'\n";

    /** Marks a run that reached the end of its driver, so a silent early exit is distinguishable from success. */
    private static final String COMPLETED = "CONFIGURE_DATABASE_RETURNED";

    /**
     * The flag resolution and the render, with the cache-invalidation TRANSPORT requirement stubbed out.
     *
     * <p>{@code resolve_entity_engine_flags} refuses {@code OFBIZ_DISTRIBUTED_CACHE_CLEAR=true} in EVERY
     * profile unless an active {@code serviceMessenger} is configured with a loadable client and a reachable
     * broker - which is asserted on its own by
     * {@link #askingForCrossInstanceInvalidationWithoutATransportIsRefusedInEveryProfile}. The cases that use
     * the bodies below are about something else entirely: the boolean normalisation, and what the render puts
     * in the cache-clear attribute. Replacing the one check that would need a message broker is what isolates
     * them, and it is the same technique the stage recorders further down use for the same reason - no unit
     * test may depend on a broker being reachable from the machine it runs on.</p>
     */
    private static final String NO_TRANSPORT_CHECK = "validate_distributed_cache_transport() { :; }\n";

    /** {@link #RESOLVE_FLAGS_BODY} with the transport requirement stubbed; see {@link #NO_TRANSPORT_CHECK}. */
    private static final String RESOLVE_FLAGS_WITHOUT_TRANSPORT_BODY = NO_TRANSPORT_CHECK + RESOLVE_FLAGS_BODY;

    /** {@link #CONFIGURE_DATABASE_BODY} with the transport requirement stubbed; see {@link #NO_TRANSPORT_CHECK}. */
    private static final String CONFIGURE_DATABASE_WITHOUT_TRANSPORT_BODY =
            NO_TRANSPORT_CHECK + CONFIGURE_DATABASE_BODY;

    /*
     * resolve_entity_engine_flags: the decision that changes what the whole container does
     */

    /**
     * Objective 4 default-off posture, executed: a container started with no configuration at all resolves to
     * the safe mode on both axes - no startup DDL, no cross-instance invalidation.
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void bothModeFlagsDefaultToTheSafeSettingWhenNothingIsConfigured(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        EntryPointRun run = runInSandbox(tempDir, sandbox, RESOLVE_FLAGS_BODY, Map.of());

        assertEquals(0, run.getExitCode(), "an unconfigured start must resolve, output was:\n" + run.getOutput());
        assertTrue(run.getOutput().contains("SCHEMA_INIT=false"),
                "an unset OFBIZ_SCHEMA_INIT must resolve to run mode, output was:\n" + run.getOutput());
        assertTrue(run.getOutput().contains("CACHE_CLEAR=false"),
                "an unset OFBIZ_DISTRIBUTED_CACHE_CLEAR must resolve to single-node caching, output was:\n"
                        + run.getOutput());
    }

    /**
     * Every spelling the entry point accepts is normalised onto one of the two literals the rest of the script
     * compares against, for both mode flags.
     *
     * <p>This matters because both consumers are literal comparisons: {@code _main} exits early only on the
     * exact string {@code true}, and the rendered {@code check-on-start} is read by {@code Datasource} as
     * {@code !"false".equals(value)}. A spelling that reached either of them un-normalised would select the
     * permissive setting while looking configured.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void everyBooleanSpellingTheEntryPointAcceptsNormalisesToOneOfTwoLiterals(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("true", "true");
        expected.put("TRUE", "true");
        expected.put("True", "true");
        expected.put("yes", "true");
        expected.put("YES", "true");
        expected.put("1", "true");
        expected.put("false", "false");
        expected.put("FALSE", "false");
        expected.put("no", "false");
        expected.put("0", "false");
        // An empty value is not a spelling of anything: ${VAR:-false} replaces it before parsing, so an
        // exported but unset variable must behave exactly like an absent one rather than being refused.
        expected.put("", "false");

        for (Map.Entry<String, String> spelling : expected.entrySet()) {
            EntryPointRun schemaInit = runInSandbox(tempDir, sandbox, RESOLVE_FLAGS_BODY,
                    Map.of("OFBIZ_SCHEMA_INIT", spelling.getKey()));
            assertEquals(0, schemaInit.getExitCode(), "OFBIZ_SCHEMA_INIT=[" + spelling.getKey()
                    + "] must be accepted, output was:\n" + schemaInit.getOutput());
            assertTrue(schemaInit.getOutput().contains("SCHEMA_INIT=" + spelling.getValue()),
                    "OFBIZ_SCHEMA_INIT=[" + spelling.getKey() + "] must normalise to " + spelling.getValue()
                            + ", output was:\n" + schemaInit.getOutput());

            EntryPointRun cacheClear = runInSandbox(tempDir, sandbox, RESOLVE_FLAGS_WITHOUT_TRANSPORT_BODY,
                    Map.of("OFBIZ_DISTRIBUTED_CACHE_CLEAR", spelling.getKey(), "OFBIZ_PROFILE", "dev"));
            assertEquals(0, cacheClear.getExitCode(), "OFBIZ_DISTRIBUTED_CACHE_CLEAR=[" + spelling.getKey()
                    + "] must be accepted, output was:\n" + cacheClear.getOutput());
            assertTrue(cacheClear.getOutput().contains("CACHE_CLEAR=" + spelling.getValue()),
                    "OFBIZ_DISTRIBUTED_CACHE_CLEAR=[" + spelling.getKey() + "] must normalise to "
                            + spelling.getValue() + ", output was:\n" + cacheClear.getOutput());
        }
    }

    /**
     * An unparseable mode flag stops the container instead of quietly selecting the permissive setting.
     *
     * <p>The failure path is subtle enough to be worth executing: {@code require_boolean} aborts inside a
     * command substitution, where {@code exit} ends only the subshell, so without the explicit status check in
     * {@code resolve_entity_engine_flags} the captured variable would simply be empty - which every later
     * comparison reads as "not true". A deployment that asked for init mode and mistyped the value would then
     * be handed a silently serving instance and no schema. Each case therefore also asserts that resolution
     * never completed.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void anUnparseableModeFlagStopsTheContainerInsteadOfSelectingThePermissiveSetting(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);
        // "true " is included on purpose: nothing trims the value, so a trailing space must be refused rather
        // than tolerated, which is what stops a stray space in a compose file from disabling init mode.
        List<String> unparseable = List.of("maybe", "2", "-1", "on", "off", "t", "true ", "enabled");

        for (String variable : List.of("OFBIZ_SCHEMA_INIT", "OFBIZ_DISTRIBUTED_CACHE_CLEAR")) {
            for (String value : unparseable) {
                EntryPointRun run = runInSandbox(tempDir, sandbox, RESOLVE_FLAGS_BODY, Map.of(variable, value));

                assertNotEquals(0, run.getExitCode(), variable + "=[" + value
                        + "] must be refused, output was:\n" + run.getOutput());
                assertTrue(run.getOutput().contains(variable), "the failure must name " + variable
                        + ", output was:\n" + run.getOutput());
                assertFalse(run.getOutput().contains("SCHEMA_INIT="), variable + "=[" + value
                        + "] must abort before either flag is published, output was:\n" + run.getOutput());
            }
        }
    }

    /**
     * Init mode and skip-init are refused together, because the combination would report success without having
     * created a single table.
     *
     * <p>{@code OFBIZ_SKIP_INIT} skips the data load, and the data load is what applies the entity-model schema -
     * the Entity Engine has no standalone DDL command, so the DDL happens when the loader creates a delegator.
     * The one-shot exit would still return 0, so an orchestrator gating the fleet on that exit status would
     * conclude the schema was ready. The opposite combination is asserted too: skip-init with the flag off is an
     * ordinary supported start and must not be refused by an over-broad check.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void schemaInitCannotBeCombinedWithSkipInitBecauseTheJobWouldReportSuccessWithNoSchema(
            @TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        EntryPointRun conflict = runInSandbox(tempDir, sandbox, RESOLVE_FLAGS_BODY,
                Map.of("OFBIZ_SCHEMA_INIT", "true", "OFBIZ_SKIP_INIT", "1"));
        assertNotEquals(0, conflict.getExitCode(),
                "init mode with skip-init must fail fast, output was:\n" + conflict.getOutput());
        assertTrue(conflict.getOutput().contains("OFBIZ_SCHEMA_INIT")
                && conflict.getOutput().contains("OFBIZ_SKIP_INIT"),
                "the failure must name both variables, output was:\n" + conflict.getOutput());

        EntryPointRun permitted = runInSandbox(tempDir, sandbox, RESOLVE_FLAGS_BODY,
                Map.of("OFBIZ_SCHEMA_INIT", "false", "OFBIZ_SKIP_INIT", "1"));
        assertEquals(0, permitted.getExitCode(),
                "skip-init without init mode is an ordinary start, output was:\n" + permitted.getOutput());
        assertTrue(permitted.getOutput().contains("SCHEMA_INIT=false"),
                "skip-init must still resolve run mode, output was:\n" + permitted.getOutput());
    }

    /**
     * {@code OFBIZ_SKIP_INIT} is parsed as a boolean, so a value that SAYS "do not skip" cannot skip.
     *
     * <p>Testing the flag with {@code [ -z "$OFBIZ_SKIP_INIT" ]} would make every non-empty value truthy:
     * {@code OFBIZ_SKIP_INIT=false}, {@code no} and {@code 0} would all skip the initialisation, the exact
     * opposite of what the value says, and silently. Both halves are asserted here - the negative spellings
     * resolve to "do not skip", and a value that is not a boolean at all stops the container rather than being
     * interpreted as consent - because a deployment cannot tell the difference from the outside.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void skipInitIsParsedAsABooleanSoAValueThatSaysDoNotSkipCannotSkip(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("true", "true");
        expected.put("TRUE", "true");
        expected.put("yes", "true");
        expected.put("1", "true");
        expected.put("false", "false");
        expected.put("FALSE", "false");
        expected.put("no", "false");
        expected.put("0", "false");
        expected.put("", "false");
        for (Map.Entry<String, String> spelling : expected.entrySet()) {
            EntryPointRun run = runInSandbox(tempDir, sandbox, RESOLVE_FLAGS_BODY,
                    Map.of("OFBIZ_SKIP_INIT", spelling.getKey()));
            assertEquals(0, run.getExitCode(), "OFBIZ_SKIP_INIT=[" + spelling.getKey()
                    + "] must be accepted, output was:\n" + run.getOutput());
            assertTrue(run.getOutput().contains("SKIP=" + spelling.getValue()),
                    "OFBIZ_SKIP_INIT=[" + spelling.getKey() + "] must normalise to " + spelling.getValue()
                            + ", output was:\n" + run.getOutput());
        }

        for (String value : List.of("maybe", "2", "-1", "on", "off", "true ", "please")) {
            EntryPointRun run = runInSandbox(tempDir, sandbox, RESOLVE_FLAGS_BODY,
                    Map.of("OFBIZ_SKIP_INIT", value));
            assertNotEquals(0, run.getExitCode(),
                    "OFBIZ_SKIP_INIT=[" + value + "] must be refused, output was:\n" + run.getOutput());
            assertTrue(run.getOutput().contains("OFBIZ_SKIP_INIT"),
                    "the failure must name the variable, output was:\n" + run.getOutput());
            assertFalse(run.getOutput().contains("SKIP="),
                    "OFBIZ_SKIP_INIT=[" + value + "] must abort before the flag is published, output was:\n"
                            + run.getOutput());
        }
    }

    /**
     * Skipping the initialisation skips the DATA population only. Everything security relevant still runs.
     *
     * <p>If {@code OFBIZ_SKIP_INIT} wrapped the whole of {@code _main}'s body, a restarted container - the case
     * the flag exists for - would skip the secret resolution and every configuration render along with the data
     * load, and would then serve traffic on whatever happened to be left in {@code /ofbiz/config}: the
     * committed placeholder configuration with no key material at all on a fresh volume, or a previous image's
     * rendered keys on a reused one, with no credential validated in either case. The render is idempotent and
     * always derives from the pristine source, so running it on this path is both safe and the only way a
     * rotated secret can take effect on restart.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void skippingInitialisationStillValidatesTheProfileAndRendersEveryConfigurationFile(
            @TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        EntryPointRun skipping = runInSandbox(tempDir, sandbox, MAIN_BODY,
                Map.of("OFBIZ_SKIP_INIT", "true"), List.of("/bin/echo", SERVING_MARKER));
        assertEquals(0, skipping.getExitCode(),
                "skipping the data initialisation is a supported start, output was:\n" + skipping.getOutput());
        for (String stage : List.of("STAGE ofbiz_setup_env", "STAGE create_ofbiz_runtime_directories",
                "STAGE configure_database", "STAGE apply_configuration")) {
            assertTrue(skipping.getOutput().contains(stage),
                    stage + " must still run when the data initialisation is skipped, output was:\n"
                            + skipping.getOutput());
        }
        assertFalse(skipping.getOutput().contains("STAGE load_data"),
                "the data load must be skipped, output was:\n" + skipping.getOutput());
        assertFalse(skipping.getOutput().contains("STAGE load_admin_user"),
                "the admin user load must be skipped, output was:\n" + skipping.getOutput());
        assertTrue(skipping.getOutput().contains(SERVING_MARKER),
                "the container must still serve, output was:\n" + skipping.getOutput());

        // The same run without the flag, so the difference is exactly the two data stages and nothing else.
        EntryPointRun initialising = runInSandbox(tempDir, sandbox, MAIN_BODY, Map.of(),
                List.of("/bin/echo", SERVING_MARKER));
        assertEquals(0, initialising.getExitCode(),
                "the ordinary start must succeed, output was:\n" + initialising.getOutput());
        assertTrue(initialising.getOutput().contains("STAGE load_data")
                && initialising.getOutput().contains("STAGE load_admin_user"),
                "an ordinary start must perform the data initialisation, output was:\n"
                        + initialising.getOutput());
    }

    /*
     * require_profile: the decision every other decision in the script depends on
     */

    /**
     * A container that does not name its deployment profile starts in the development profile and says so.
     *
     * <p>The zero-configuration start is a requirement rather than a convenience: an unmodified checkout given
     * no environment at all has to boot on the embedded database exactly as it did before this work, so that a
     * developer can still build and run locally with no secrets configured and no external service. An entry
     * point that refused to start until a profile was named removed that path entirely, so {@code
     * require_profile} resolves an absent {@code OFBIZ_PROFILE} - or an empty one, which is what an unresolved
     * template expansion leaves behind - to {@code dev} and continues.</p>
     *
     * <p>What must not come back with that default is silence. The concern that motivated requiring the
     * variable is real: whether an absent secret aborts the start or is replaced by a generated value, whether
     * the published demo admin password is accepted, and whether a non-verifying database TLS mode is allowed
     * all follow from the profile, so a deployment manifest that never mentioned it, or that lost it to a
     * templating mistake, must not receive the permissive setting quietly. The resolution is a notice on
     * stderr that names the profile it assumed, states what that profile permits, and points at {@code prod}.
     * Both halves are asserted here: the start SUCCEEDS, and the notice is emitted.</p>
     *
     * <p>Every value that is neither empty nor one of the two spellings is still refused, whitespace included,
     * because a profile the script cannot recognise cannot be resolved to either policy.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void aContainerThatDoesNotNameItsProfileStartsInDevelopmentAndSaysSo(
            @TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        // Unset and empty are the same case: both resolve to dev, both announce it, and both go on to start.
        for (Map<String, String> unprofiled : List.of(Map.<String, String>of(), Map.of("OFBIZ_PROFILE", ""))) {
            EntryPointRun absent = runWithoutProfileDefault(tempDir, sandbox, MAIN_BODY, unprofiled,
                    List.of("/bin/echo", SERVING_MARKER));
            assertEquals(0, absent.getExitCode(),
                    "an unprofiled container must start, output was:\n" + absent.getOutput());
            assertTrue(absent.getOutput().contains(SERVING_MARKER),
                    "an unprofiled container must reach the serving command, output was:\n" + absent.getOutput());
            assertTrue(absent.getOutput().contains("NOTICE:"),
                    "the assumed profile must be announced, output was:\n" + absent.getOutput());
            assertTrue(absent.getOutput().contains("OFBIZ_PROFILE"),
                    "the notice must name the variable, output was:\n" + absent.getOutput());
            assertTrue(absent.getOutput().contains("'dev'"),
                    "the notice must name the profile it assumed, output was:\n" + absent.getOutput());
            assertTrue(absent.getOutput().contains("OFBIZ_PROFILE=prod"),
                    "the notice must point at the deployed profile, output was:\n" + absent.getOutput());
        }

        for (String value : List.of(" ", "development", "production", "PROD", "Dev", "test", "staging")) {
            EntryPointRun run = runWithoutProfileDefault(tempDir, sandbox, MAIN_BODY,
                    Map.of("OFBIZ_PROFILE", value), List.of("/bin/echo", SERVING_MARKER));
            assertNotEquals(0, run.getExitCode(),
                    "OFBIZ_PROFILE=[" + value + "] must be refused, output was:\n" + run.getOutput());
            assertFalse(run.getOutput().contains(SERVING_MARKER), "OFBIZ_PROFILE=[" + value
                    + "] must not reach the serving command, output was:\n" + run.getOutput());
        }

        // The two accepted spellings. prod is given the secrets it requires as well as the profile, because
        // requiring them is the point of naming the profile: an accepted profile that then aborted on a
        // missing secret would make this loop assert the opposite of what it says. That the requirement
        // exists at all is asserted by AdminKeyConfigTests and SigningKeyRenderingTests; asserted here is
        // only that 'dev' and 'prod' are the two spellings that start.
        for (String value : List.of("dev", "prod")) {
            Map<String, String> environment = new LinkedHashMap<>();
            environment.put("OFBIZ_PROFILE", value);
            environment.put("OFBIZ_ADMIN_KEY", PROFILE_ADMIN_KEY);
            environment.put("OFBIZ_LOGIN_SECRET_KEY", PROFILE_LOGIN_SECRET_KEY);
            environment.put("OFBIZ_JWT_TOKEN_KEY", PROFILE_JWT_TOKEN_KEY);
            environment.put("OFBIZ_ADMIN_PASSWORD", PROFILE_ADMIN_PASSWORD);

            EntryPointRun run = runWithoutProfileDefault(tempDir, sandbox, MAIN_BODY, environment,
                    List.of("/bin/echo", SERVING_MARKER));
            assertEquals(0, run.getExitCode(),
                    "OFBIZ_PROFILE=" + value + " must be accepted, output was:\n" + run.getOutput());
            assertTrue(run.getOutput().contains(SERVING_MARKER),
                    "OFBIZ_PROFILE=" + value + " must start normally, output was:\n" + run.getOutput());
        }
    }

    /**
     * Enabling cross-instance invalidation without a message transport is refused in EVERY profile.
     *
     * <p>This check lives in {@code resolve_entity_engine_flags} because the delegator flag does not supply a
     * transport. All of the distributed cache-clear services are declared {@code engine="jms"
     * location="serviceMessenger"}, and the only {@code jms-service} of that name is commented out by
     * default, so with the flag on and nothing else configured {@code JmsServiceEngine.run} dereferences a
     * null service inside {@code ServiceDispatcher.runAsync} - which catches {@code Throwable}, marks the
     * CALLER's transaction rollback-only and re-throws. The entity write that triggered the invalidation is
     * therefore rolled back too, so a bulk load aborts a long way from the cause.</p>
     *
     * <p>There is deliberately no development-profile exemption. The flag with no transport is not a degraded
     * fleet that a warning could describe: it is an instance that rolls back its own writes, and a line in a
     * start-up log is not a defence against that. A developer who wants a local cache sets the flag to
     * {@code false}, which is also what an unset variable resolves to. The sandbox contains no
     * {@code serviceengine.xml}, which is exactly the state a deployment that set the variable and supplied
     * nothing else is in.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void askingForCrossInstanceInvalidationWithoutATransportIsRefusedInEveryProfile(
            @TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        for (String profile : List.of("dev", "prod")) {
            EntryPointRun run = runInSandbox(tempDir, sandbox, RESOLVE_FLAGS_BODY,
                    Map.of("OFBIZ_DISTRIBUTED_CACHE_CLEAR", "true", "OFBIZ_PROFILE", profile));
            assertNotEquals(0, run.getExitCode(), "OFBIZ_PROFILE=" + profile
                    + " must refuse a cache-coherent claim it cannot keep, output was:\n" + run.getOutput());
            assertTrue(run.getOutput().contains("OFBIZ_DISTRIBUTED_CACHE_CLEAR"),
                    "the failure must name the variable, output was:\n" + run.getOutput());
            assertTrue(run.getOutput().contains("serviceMessenger"),
                    "the failure must name the transport that is missing, output was:\n" + run.getOutput());
            assertTrue(publishedCacheClearFlags(run).isEmpty(), "the resolution must abort rather than publish "
                    + "a flag it cannot honour, output was:\n" + run.getOutput());
        }

        // The other half of the same decision: the flag OFF needs no transport at all, so the check must not
        // be reachable from the default posture. Without this, a check that refused every start would pass
        // the cases above.
        EntryPointRun singleNode = runInSandbox(tempDir, sandbox, RESOLVE_FLAGS_BODY,
                Map.of("OFBIZ_DISTRIBUTED_CACHE_CLEAR", "false", "OFBIZ_PROFILE", "prod"));
        assertEquals(0, singleNode.getExitCode(),
                "single-node caching must need no broker, output was:\n" + singleNode.getOutput());
        assertEquals(List.of("CACHE_CLEAR=false"), publishedCacheClearFlags(singleNode),
                "the flag must still resolve, output was:\n" + singleNode.getOutput());
    }

    /*
     * render_database_configuration: the deployed-profile configuration the fleet actually reads
     */

    /**
     * Every placeholder the render template carries is substituted by the real shell, and the set of
     * placeholders it carries is exactly the set the shell writes a substitution for.
     *
     * <p>The two halves are asserted together on purpose. A template that gained a seventeenth placeholder
     * would render it unsubstituted into the deployed configuration - {@code pool-maxsize="@DB_POOL_CAP@"} is a
     * number the pool cannot parse - and a substitution the shell writes for a placeholder the template no
     * longer has is dead code that hides a lost setting. Both are caught by comparing the two inventories and
     * then proving the render leaves nothing behind.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run or the render could not be parsed, either of which fails
     *         the test rather than being handled
     */
    @Test
    public void everyPlaceholderOfTheRenderTemplateIsSubstitutedByTheRealShell(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        Set<String> declared = placeholdersIn(Files.readString(sandbox.resolve(ENTITY_ENGINE_TEMPLATE),
                StandardCharsets.UTF_8));
        assertEquals(new LinkedHashSet<>(SUBSTITUTED_TOKENS), declared,
                "the render template's placeholder inventory and the entry point's substitutions must agree");

        Map<String, String> environment = managedDatabaseEnvironment();
        // Asked for explicitly so that the cache-clear placeholder is substituted with the value that is NOT
        // the resolver's default, which is what tells a substituted value apart from one merely left alone.
        environment.put("OFBIZ_DISTRIBUTED_CACHE_CLEAR", "true");

        EntryPointRun run = runInSandbox(tempDir, sandbox, CONFIGURE_DATABASE_WITHOUT_TRANSPORT_BODY, environment);
        assertEquals(0, run.getExitCode(), "the render must succeed, output was:\n" + run.getOutput());
        assertTrue(run.getOutput().contains(COMPLETED), "the render must return, output was:\n" + run.getOutput());

        String rendered = Files.readString(sandbox.resolve(RENDERED_OVERRIDE), StandardCharsets.UTF_8);
        assertEquals(Set.of(), placeholdersIn(rendered), "no placeholder may survive the render");

        // Asserted as whole attributes rather than as substrings, so a value landing in the wrong attribute -
        // or a URI assembled with the port in the path - cannot satisfy the assertion.
        Element root = parseXml(rendered);
        Map<String, String> expectedUris = Map.of(
                "localpostgres", jdbcUri(OFBIZ_DATABASE),
                "localpostgresolap", jdbcUri(OLAP_DATABASE),
                "localpostgrestenant", jdbcUri(TENANT_DATABASE));
        Map<String, String> expectedUsers = Map.of(
                "localpostgres", OFBIZ_USER, "localpostgresolap", OLAP_USER, "localpostgrestenant", TENANT_USER);
        Map<String, String> expectedPasswords = Map.of(
                "localpostgres", OFBIZ_PASSWORD,
                "localpostgresolap", OLAP_PASSWORD,
                "localpostgrestenant", TENANT_PASSWORD);
        for (String datasourceName : MANAGED_DATASOURCES) {
            Element inlineJdbc = inlineJdbcOf(root, datasourceName);
            assertEquals(expectedUris.get(datasourceName), inlineJdbc.getAttribute("jdbc-uri"),
                    datasourceName + " must carry the host, port, database and TLS parameters that were supplied");
            assertEquals(expectedUsers.get(datasourceName), inlineJdbc.getAttribute("jdbc-username"),
                    datasourceName + " must carry the user name that was supplied");
            assertEquals(expectedPasswords.get(datasourceName), inlineJdbc.getAttribute("jdbc-password"),
                    datasourceName + " must carry the password that was supplied");
            assertEquals(POOL_MIN, inlineJdbc.getAttribute("pool-minsize"),
                    datasourceName + " must carry the configured pool floor");
            assertEquals(POOL_MAX, inlineJdbc.getAttribute("pool-maxsize"),
                    datasourceName + " must carry the configured pool ceiling");
        }

        for (String delegatorName : CACHE_CLEAR_DELEGATORS) {
            assertEquals("true", delegator(root, delegatorName).getAttribute(CACHE_CLEAR_ENABLED),
                    delegatorName + " must carry the requested cross-instance invalidation setting");
        }
        assertFalse(delegator(root, "test").hasAttribute(CACHE_CLEAR_ENABLED),
                "the single-JVM test delegator must never be given a cache-clear attribute by a render");
    }

    /**
     * A value containing the metacharacters of every grammar it passes through survives the render intact
     * instead of rewriting the configuration around it.
     *
     * <p>A database password is untrusted input that traverses three grammars on its way into the file: the
     * {@code sed} replacement text, where an unescaped {@code &} would insert the matched placeholder and a
     * {@code |} would terminate the s-command; the XML attribute, where a {@code "} would close it early and a
     * {@code <} would open an element; and the file as a whole. Rather than asserting the escaped text - which
     * would only restate the escaping rules - the rendered document is PARSED and the attribute compared with
     * the original value, so the assertion holds for any correct escaping and fails for any incorrect one.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run or the render could not be parsed, either of which fails
     *         the test rather than being handled
     */
    @Test
    public void aValueFullOfMetacharactersSurvivesTheRenderInsteadOfRewritingTheConfiguration(
            @TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);
        String hostilePassword = "Ampersand&Angle<7y>Quote\"Apostrophe'Pipe|Backslash\\9";
        String hostileUser = "main&user<x>";
        Map<String, String> environment = managedDatabaseEnvironment();
        environment.put("OFBIZ_POSTGRES_OFBIZ_PASSWORD", hostilePassword);
        environment.put("OFBIZ_POSTGRES_OFBIZ_USER", hostileUser);

        EntryPointRun run = runInSandbox(tempDir, sandbox, CONFIGURE_DATABASE_BODY, environment);

        assertEquals(0, run.getExitCode(), "a single-line value must be accepted, output was:\n" + run.getOutput());
        String rendered = Files.readString(sandbox.resolve(RENDERED_OVERRIDE), StandardCharsets.UTF_8);
        Element inlineJdbc = inlineJdbcOf(parseXml(rendered), "localpostgres");
        assertEquals(hostilePassword, inlineJdbc.getAttribute("jdbc-password"),
                "the password must round-trip through sed and the XML attribute unchanged");
        assertEquals(hostileUser, inlineJdbc.getAttribute("jdbc-username"),
                "the user name must round-trip through sed and the XML attribute unchanged");
        // The document parsed, so nothing escaped its attribute; this additionally shows the escaping did not
        // corrupt the structure around it, which is the failure a hostile value is aiming for.
        assertEquals(List.of(), validateAgainstEntityConfigSchema(rendered),
                "the rendered configuration must remain valid against the entity-config grammar");
    }

    /**
     * The rendered configuration is valid against the engine's own grammar and readable only by the account
     * OFBiz runs as.
     *
     * <p>Both properties are about the artefact rather than the substitution. A render that is well formed but
     * schema-invalid fails at start up with an XSD error instead of a configuration error, and a render that is
     * world readable publishes three database passwords to every account and every mounted-volume consumer in
     * the container. The grammar is supplied from the local copy in the repository, so validation is offline
     * even though the file's {@code xsi:noNamespaceSchemaLocation} names a URL.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run or the render could not be validated, either of which
     *         fails the test rather than being handled
     */
    @Test
    public void theRenderedConfigurationIsSchemaValidAndReadableOnlyByTheAccountOfbizRunsAs(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        EntryPointRun run = runInSandbox(tempDir, sandbox, CONFIGURE_DATABASE_BODY, managedDatabaseEnvironment());

        assertEquals(0, run.getExitCode(), "the render must succeed, output was:\n" + run.getOutput());
        Path override = sandbox.resolve(RENDERED_OVERRIDE);
        assertEquals(List.of(), validateAgainstEntityConfigSchema(Files.readString(override, StandardCharsets.UTF_8)),
                "the rendered configuration must be valid against " + ENTITY_CONFIG_SCHEMA);
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(override),
                "a configuration file holding three database passwords must not be readable by other accounts");
    }

    /**
     * No database password reaches the process output, which in the container is the log.
     *
     * <p>The rendering path expands passwords into variable assignments and into a {@code sed} program, either
     * of which a traced shell would echo, so the script suspends tracing around the whole function and passes
     * the program through a mode 0600 file rather than the command line. This asserts the outcome from the
     * outside - and asserts, in the same breath, that the values really were rendered, so the test cannot pass
     * because nothing happened.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void noDatabasePasswordReachesTheContainerLog(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);
        Map<String, String> environment = managedDatabaseEnvironment();
        // Tracing is what a support request asks for first, so the leak has to be absent with it switched on.
        environment.put("OFBIZ_TRACE", "1");

        EntryPointRun run = runInSandbox(tempDir, sandbox, CONFIGURE_DATABASE_BODY, environment);

        assertEquals(0, run.getExitCode(), "the render must succeed, output was:\n" + run.getOutput());
        String rendered = Files.readString(sandbox.resolve(RENDERED_OVERRIDE), StandardCharsets.UTF_8);
        for (String password : List.of(OFBIZ_PASSWORD, OLAP_PASSWORD, TENANT_PASSWORD)) {
            assertTrue(rendered.contains(password), "the password must actually have been rendered, "
                    + "otherwise its absence from the output would prove nothing");
            assertFalse(run.getOutput().contains(password), "a database password reached the output");
        }
    }

    /**
     * Objective 4, both halves, executed end to end: the startup-DDL mode of every managed datasource follows
     * {@code OFBIZ_SCHEMA_INIT}, and nothing else in the file moves with it.
     *
     * <p>This is the assertion that no static test can make. Run mode renders both flags as the literal
     * {@code false} on all three managed datasources, so a serving instance issues no DDL and needs no DDL
     * privilege; init mode renders both as {@code true} on those same three, which is what applies the entity
     * model. The embedded H2 datasources keep both flags {@code true} in either mode - a bare checkout must go
     * on provisioning itself - and the {@code test} delegator keeps all three frozen entity groups on them, so
     * the integration tier never needs a managed database. Removing the flag, the placeholder, or the
     * substitution collapses one of these three cases.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run or a render could not be parsed, either of which fails
     *         the test rather than being handled
     */
    @Test
    public void theStartupDdlModeOfEveryManagedDatasourceFollowsTheSchemaInitFlag(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Map<String, String> requestedMode = new LinkedHashMap<>();
        requestedMode.put("", "false");
        requestedMode.put("false", "false");
        requestedMode.put("true", "true");

        for (Map.Entry<String, String> mode : requestedMode.entrySet()) {
            Path sandbox = prepareSandbox(Files.createTempDirectory(tempDir, "mode"));
            Map<String, String> environment = managedDatabaseEnvironment();
            environment.put("OFBIZ_SCHEMA_INIT", mode.getKey());

            EntryPointRun run = runInSandbox(tempDir, sandbox, CONFIGURE_DATABASE_BODY, environment);
            assertEquals(0, run.getExitCode(), "OFBIZ_SCHEMA_INIT=[" + mode.getKey()
                    + "] must render, output was:\n" + run.getOutput());

            Element root = parseXml(Files.readString(sandbox.resolve(RENDERED_OVERRIDE), StandardCharsets.UTF_8));
            Map<String, String> expectedManaged = new LinkedHashMap<>();
            Map<String, String> actualManaged = new LinkedHashMap<>();
            for (String datasourceName : MANAGED_DATASOURCES) {
                expectedManaged.put(datasourceName, mode.getValue() + "/" + mode.getValue());
                actualManaged.put(datasourceName, ddlFlagsOf(root, datasourceName));
            }
            assertEquals(expectedManaged, actualManaged, "OFBIZ_SCHEMA_INIT=[" + mode.getKey()
                    + "] must set check-on-start and add-missing-on-start together on every managed datasource");

            Map<String, String> expectedEmbedded = new LinkedHashMap<>();
            Map<String, String> actualEmbedded = new LinkedHashMap<>();
            for (String datasourceName : EMBEDDED_DATASOURCES) {
                expectedEmbedded.put(datasourceName, "true/true");
                actualEmbedded.put(datasourceName, ddlFlagsOf(root, datasourceName));
            }
            assertEquals(expectedEmbedded, actualEmbedded, "OFBIZ_SCHEMA_INIT=[" + mode.getKey()
                    + "] must leave the embedded datasources provisioning themselves");

            // The deployed profile's reason for existing: both default delegators resolve all three frozen
            // entity groups to the managed datasources, in every mode, while test resolves none of them.
            for (String delegatorName : CACHE_CLEAR_DELEGATORS) {
                assertEquals(MANAGED_DELEGATOR_MAPPING, delegatorMapping(root, delegatorName),
                        "OFBIZ_SCHEMA_INIT=[" + mode.getKey() + "] must leave the " + delegatorName
                                + " delegator resolving every entity group to a managed datasource");
            }
            assertEquals(TEST_DELEGATOR_MAPPING, delegatorMapping(root, "test"), "OFBIZ_SCHEMA_INIT=["
                    + mode.getKey() + "] must leave the test delegator on the embedded datasources");
        }
    }

    /*
     * The post-render validators: the fail-closed half of every claim above
     */

    /**
     * Every post-render validator accepts what the real shell has just rendered.
     *
     * <p>The positive case is what makes the four negative cases below meaningful: without it, a validator that
     * rejected everything - including a correct render - would pass them all. They are run against a render
     * produced in the normal way, in one pass, exactly as {@code render_database_configuration} runs them.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void everyPostRenderValidatorAcceptsWhatTheRealShellJustRendered(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);
        assertEquals(0, runInSandbox(tempDir, sandbox, CONFIGURE_DATABASE_BODY, managedDatabaseEnvironment())
                .getExitCode(), "the fixture render must succeed before the validators can be exercised");

        EntryPointRun run = runInSandbox(tempDir, sandbox, allValidatorsBody("false", "false"), Map.of());

        assertEquals(0, run.getExitCode(),
                "a correct render must satisfy every validator, output was:\n" + run.getOutput());
        assertTrue(Files.exists(sandbox.resolve(RENDERED_OVERRIDE)),
                "an accepted render must be left in place");
    }

    /**
     * Every post-render validator is actually WIRED INTO the render, not merely defined next to it.
     *
     * <p>The cases below reach the validators the only way the container does: through
     * {@code configure_database}. Each one edits the render TEMPLATE the way a hand-maintained copy or a file
     * restored from an older image would be wrong - one URI without the TLS placeholder, a DDL literal the
     * engine reads as "enabled", a delegator that lost its cache-clear anchor, a {@code test} delegator
     * repointed at a managed datasource - and then asserts the render refuses it. Without this, deleting a
     * validator's call site from {@code render_database_configuration} would leave every other test in this
     * suite green while the deployed fleet was handed exactly the configuration those validators exist to
     * refuse.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void everyPostRenderValidatorIsWiredIntoTheRenderAndNotMerelyDefined(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Map<String, String> tampering = new LinkedHashMap<>();
        tampering.put("/@OLAP_DB@@SSL_PARAMS@", "/@OLAP_DB@");
        tampering.put(CHECK_ON_START + "=\"@CHECK_ON_START@\"", CHECK_ON_START + "=\"FALSE\"");
        tampering.put(" " + CACHE_CLEAR_ENABLED + "=\"@DISTRIBUTED_CACHE_CLEAR@\">", ">");
        tampering.put("<group-map group-name=\"org.apache.ofbiz\" datasource-name=\"localh2\"/>",
                "<group-map group-name=\"org.apache.ofbiz\" datasource-name=\"localpostgres\"/>");

        for (Map.Entry<String, String> edit : tampering.entrySet()) {
            Path sandbox = prepareSandbox(Files.createTempDirectory(tempDir, "wired"));
            rewriteFirst(sandbox.resolve(ENTITY_ENGINE_TEMPLATE), edit.getKey(), edit.getValue());

            EntryPointRun run = runInSandbox(tempDir, sandbox, CONFIGURE_DATABASE_BODY, managedDatabaseEnvironment());

            assertNotEquals(0, run.getExitCode(), "a template edited at [" + edit.getKey()
                    + "] must be refused by the render itself, output was:\n" + run.getOutput());
            assertFalse(run.getOutput().contains(COMPLETED), "configure_database must abort rather than return "
                    + "after the edit at [" + edit.getKey() + "], output was:\n" + run.getOutput());
            assertFalse(Files.exists(sandbox.resolve(RENDERED_OVERRIDE)),
                    "the refused render must not be left behind after the edit at [" + edit.getKey() + "]");
        }
    }

    /**
     * A render whose URIs have lost the TLS query string is refused, and the file is deleted rather than left
     * where OFBiz would read it.
     *
     * <p>Deleting matters as much as refusing. The render is written atomically, so a rejected one is a
     * complete, loadable file sitting in {@code /ofbiz/config}, which takes class path precedence - an operator
     * who restarted the container past the check would be served exactly the unverified configuration the
     * check exists to prevent.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void aRenderThatLosesTheTlsQueryStringIsRefusedAndDeleted(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = renderedFixture(tempDir);
        // Exactly one URI is stripped, which is the shape a template missing @SSL_PARAMS@ on one datasource
        // produces, and the shape a whole-file "contains sslmode" check would miss.
        tamperRenderedConfiguration(sandbox, "/" + OLAP_DATABASE + renderedSslQuery(), "/" + OLAP_DATABASE);

        EntryPointRun run = runInSandbox(tempDir, sandbox,
                "require_rendered_transport_security " + RENDERED_OVERRIDE + " " + SSL_MODE + "\n", Map.of());

        assertNotEquals(0, run.getExitCode(),
                "a URI without the resolved sslmode must be refused, output was:\n" + run.getOutput());
        assertTrue(run.getOutput().contains(SSL_MODE),
                "the failure must name the mode that is missing, output was:\n" + run.getOutput());
        assertFalse(Files.exists(sandbox.resolve(RENDERED_OVERRIDE)),
                "a refused render must not be left where the class path would find it");
    }

    /**
     * A startup-DDL literal the Entity Engine would not recognise is refused, and so is a render that carries
     * the opposite mode from the one that was requested.
     *
     * <p>{@code Datasource} resolves {@code check-on-start} as {@code !"false".equals(value)}, so
     * {@code "FALSE"} - a value any reader would take for "disabled" - leaves the schema check ON. Counting the
     * exact literal is what tells that case apart from a correct render, and it is why the validator cannot be
     * written as a boolean comparison.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void aStartupDdlLiteralTheEngineWouldNotRecogniseIsRefusedAndDeleted(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        String body = "require_rendered_schema_ddl_mode " + RENDERED_OVERRIDE + " false\n";

        Path uppercase = renderedFixture(tempDir);
        tamperRenderedConfiguration(uppercase, CHECK_ON_START + "=\"false\"", CHECK_ON_START + "=\"FALSE\"");
        EntryPointRun rejected = runInSandbox(tempDir, uppercase, body, Map.of());
        assertNotEquals(0, rejected.getExitCode(),
                "a literal the engine reads as enabled must be refused, output was:\n" + rejected.getOutput());
        assertFalse(Files.exists(uppercase.resolve(RENDERED_OVERRIDE)),
                "a refused render must not be left where the class path would find it");

        // The same correct render, checked against the other mode: init mode must not silently accept a run-mode
        // file, or an init job would exit 0 having created nothing.
        Path mismatched = renderedFixture(tempDir);
        EntryPointRun wrongMode = runInSandbox(tempDir, mismatched,
                "require_rendered_schema_ddl_mode " + RENDERED_OVERRIDE + " true\n", Map.of());
        assertNotEquals(0, wrongMode.getExitCode(),
                "a run-mode render must not satisfy an init-mode check, output was:\n" + wrongMode.getOutput());
        assertFalse(Files.exists(mismatched.resolve(RENDERED_OVERRIDE)),
                "a refused render must not be left where the class path would find it");
    }

    /**
     * A managed datasource left on startup DDL is refused even when the file's <em>totals</em> are still
     * correct, which is the one shape a count of the literals cannot see.
     *
     * <p>An aggregate check answers "how many datasources carry the requested literal", and that question has
     * the same answer whether the attribute is where it belongs or has moved: lose
     * {@code add-missing-on-start="false"} from a managed datasource and gain it on an embedded one and the
     * total is unchanged. The tamper below does exactly that, and it is not a contrived edit - it is what a
     * template whose {@code @ADD_MISSING_ON_START@} placeholder was dropped from one datasource while another
     * was hard-coded looks like after rendering.</p>
     *
     * <p>What that costs in production is the whole point of Objective 4: {@code localpostgres} is the
     * datasource the business data model lives in, and {@code Datasource} reads the attribute as
     * {@code "true".equals(value)}, so every instance of the fleet would issue CREATE and ALTER against the
     * shared database on every start - the DDL the serving fleet is required not to perform, and the privilege
     * it is required not to hold.</p>
     *
     * <p>The totals are asserted to still agree <em>before</em> the validator is run. Without that assertion
     * this case would not distinguish a per-datasource check from a counting one, because a tamper that also
     * broke the totals would be refused by either.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void aManagedDatasourceLeftOnStartupDdlIsRefusedEvenWhenTheFileTotalsStillAgree(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = renderedFixture(tempDir);
        Path rendered = sandbox.resolve(RENDERED_OVERRIDE);
        String disabled = ADD_MISSING_ON_START + "=\"false\"";
        String enabled = ADD_MISSING_ON_START + "=\"true\"";

        // The embedded datasource gains the disabled literal first, then the managed one loses it. In this order
        // each rewrite lands on the datasource it is meant for: the first embedded declaration is the earliest
        // holder of the enabled literal, and the first managed one is then the earliest holder of the disabled
        // one, because the template declares all three managed datasources before any embedded datasource.
        tamperRenderedConfiguration(sandbox, enabled, disabled);
        tamperRenderedConfiguration(sandbox, disabled, enabled);

        String text = Files.readString(rendered, StandardCharsets.UTF_8);
        int managed = occurrences(text, "field-type-name=\"postgres\"");
        assertEquals(MANAGED_DATASOURCES.size(), managed, "the fixture must declare every managed datasource");
        assertEquals(managed, occurrences(text, CHECK_ON_START + "=\"false\""),
                "the tamper must leave the count of disabled schema checks agreeing with the managed count");
        assertEquals(managed, occurrences(text, disabled), "the tamper must leave the count of disabled"
                + " add-missing flags agreeing with the managed count: if it did not, a validator that merely"
                + " counted the literals would refuse this file too and this case would prove nothing");
        assertTrue(managedDatasourceAttributes(text, MANAGED_DATASOURCES.get(0)).contains(enabled),
                "the tamper must have left " + MANAGED_DATASOURCES.get(0) + " on startup DDL, or the file this"
                        + " case hands the validator is not defective at all");

        EntryPointRun run = runInSandbox(tempDir, sandbox,
                "require_rendered_schema_ddl_mode " + RENDERED_OVERRIDE + " false\n", Map.of());

        assertNotEquals(0, run.getExitCode(), "a managed datasource left on startup DDL must be refused however"
                + " the rest of the file counts up, output was:\n" + run.getOutput());
        assertTrue(run.getOutput().contains(MANAGED_DATASOURCES.get(0)), "the failure must name the datasource"
                + " that is wrong, because that is what an operator has to correct, output was:\n"
                + run.getOutput());
        assertTrue(run.getOutput().contains(ADD_MISSING_ON_START), "the failure must name the attribute that is"
                + " wrong, output was:\n" + run.getOutput());
        assertFalse(Files.exists(rendered),
                "a refused render must not be left where the class path would find it");
    }

    /**
     * A render that loses the cache-clear anchor, carries the wrong value, or extends the attribute to the
     * {@code test} delegator is refused, and the file is deleted.
     *
     * <p>All three failures are silent in production. {@code DelegatorElement} reads the attribute as
     * {@code "true".equalsIgnoreCase(value)}, so a lost anchor or an unrecognised spelling simply keeps
     * single-node caching - which behind a load balancer is stale reads rather than an error - and a
     * {@code test} delegator that joined the invalidation topic would publish integration-test writes to the
     * serving instances.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void aMissingMisvaluedOrOverreachingCacheClearAttributeIsRefusedAndDeleted(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        String body = "require_rendered_cache_clear_mode " + RENDERED_OVERRIDE + " false\n";

        Path removed = renderedFixture(tempDir);
        tamperRenderedConfiguration(removed, " " + CACHE_CLEAR_ENABLED + "=\"false\">", ">");
        EntryPointRun lostAnchor = runInSandbox(tempDir, removed, body, Map.of());
        assertNotEquals(0, lostAnchor.getExitCode(),
                "a lost anchor must be refused, output was:\n" + lostAnchor.getOutput());
        assertTrue(lostAnchor.getOutput().contains("default"),
                "the failure must name the delegator, output was:\n" + lostAnchor.getOutput());
        assertFalse(Files.exists(removed.resolve(RENDERED_OVERRIDE)), "a refused render must be deleted");

        Path mismatched = renderedFixture(tempDir);
        EntryPointRun wrongValue = runInSandbox(tempDir, mismatched,
                "require_rendered_cache_clear_mode " + RENDERED_OVERRIDE + " true\n", Map.of());
        assertNotEquals(0, wrongValue.getExitCode(),
                "a render in the other cache state must be refused, output was:\n" + wrongValue.getOutput());
        assertFalse(Files.exists(mismatched.resolve(RENDERED_OVERRIDE)), "a refused render must be deleted");

        Path overreaching = renderedFixture(tempDir);
        tamperRenderedConfiguration(overreaching, "<delegator name=\"test\" ",
                "<delegator name=\"test\" " + CACHE_CLEAR_ENABLED + "=\"true\" ");
        EntryPointRun testDelegator = runInSandbox(tempDir, overreaching, body, Map.of());
        assertNotEquals(0, testDelegator.getExitCode(),
                "a test delegator on the invalidation topic must be refused, output was:\n"
                        + testDelegator.getOutput());
        assertTrue(testDelegator.getOutput().contains("test"),
                "the failure must name the delegator, output was:\n" + testDelegator.getOutput());
        assertFalse(Files.exists(overreaching.resolve(RENDERED_OVERRIDE)), "a refused render must be deleted");
    }

    /**
     * A render that points the {@code test} delegator at a managed database is refused, and the file is deleted.
     *
     * <p>This is the regression that is invisible until it has already done damage: the test delegator's
     * readers include {@code ext-test}, and the integration suite writes to and deletes from it freely, so a
     * template edit that repointed it would aim that suite at the production database and nothing would report
     * an error.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void aTestDelegatorPointedAtAManagedDatabaseIsRefusedAndDeleted(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = renderedFixture(tempDir);
        // One group of the three, because that is what a partial template edit produces and it is the case a
        // check that merely looked for "localh2 somewhere in the block" would let through.
        tamperRenderedConfiguration(sandbox,
                "<group-map group-name=\"org.apache.ofbiz\" datasource-name=\"localh2\"/>",
                "<group-map group-name=\"org.apache.ofbiz\" datasource-name=\"localpostgres\"/>");

        EntryPointRun run = runInSandbox(tempDir, sandbox,
                "require_rendered_test_delegator_isolation " + RENDERED_OVERRIDE + "\n", Map.of());

        assertNotEquals(0, run.getExitCode(),
                "an integration delegator on a managed database must be refused, output was:\n" + run.getOutput());
        assertTrue(run.getOutput().contains("localpostgres"),
                "the failure must report what it was pointed at, output was:\n" + run.getOutput());
        assertFalse(Files.exists(sandbox.resolve(RENDERED_OVERRIDE)),
                "a refused render must not be left where the class path would find it");
    }

    /**
     * A template that names a password placeholder in its prose is refused before anything is written.
     *
     * <p>{@code sed} rewrites a comment as readily as an attribute, so a placeholder mentioned in a token
     * inventory or a worked example is substituted there too and the rendered configuration repeats the
     * database passwords in clear text outside the one attribute meant to hold each of them. The check reads
     * only the template, so it involves no secret, and it runs before the {@code sed} program is created - which
     * is what this asserts, by showing that no override and no temporary file are left behind.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void aTemplateThatNamesAPasswordPlaceholderInProseIsRefusedBeforeAnythingIsWritten(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);
        Path template = sandbox.resolve(ENTITY_ENGINE_TEMPLATE);
        Files.writeString(template, Files.readString(template, StandardCharsets.UTF_8)
                + "<!-- worked example: jdbc-password=@OFBIZ_PASSWORD@ -->\n", StandardCharsets.UTF_8);

        EntryPointRun run = runInSandbox(tempDir, sandbox, CONFIGURE_DATABASE_BODY, managedDatabaseEnvironment());

        assertNotEquals(0, run.getExitCode(),
                "a placeholder outside its attribute must be refused, output was:\n" + run.getOutput());
        assertTrue(run.getOutput().contains(ENTITY_ENGINE_TEMPLATE),
                "the failure must name the template, output was:\n" + run.getOutput());
        assertFalse(Files.exists(sandbox.resolve(RENDERED_OVERRIDE)),
                "nothing may be rendered from a template that would republish the passwords");
    }

    /**
     * A supplied value that itself contains a render placeholder is refused before it is substituted.
     *
     * <p>The render is a chain of sequential {@code sed} substitutions over one file, so text a value
     * introduces is visible to every substitution that follows it. A database name of
     * {@code main@OFBIZ_PASSWORD@} therefore renders as the database name followed by the OFBiz database
     * PASSWORD, in an attribute that is not a password attribute - the credential is spliced into a field
     * anything reading the configuration can see, and the render still succeeds because no placeholder is left
     * over. Refusing any {@code @WORD@} substring in any substituted value closes the whole class, and it is
     * asserted for every field the render substitutes rather than only for the first one, because the exposure
     * depends on the ORDER of the substitutions and that order is not visible from a call site.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void aSuppliedValueCarryingARenderPlaceholderIsRefusedSoItCannotSpliceAnotherField(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        // Every variable whose value the render substitutes into the template, with a sentinel-bearing value
        // that is otherwise valid for that field's grammar, so the refusal can only come from the sentinel.
        Map<String, String> hostile = new LinkedHashMap<>();
        hostile.put("OFBIZ_POSTGRES_OFBIZ_DB", "maindb@OFBIZ_PASSWORD@");
        hostile.put("OFBIZ_POSTGRES_OLAP_DB", "olapdb@OLAP_PASSWORD@");
        hostile.put("OFBIZ_POSTGRES_TENANT_DB", "tenantdb@TENANT_PASSWORD@");
        hostile.put("OFBIZ_POSTGRES_OFBIZ_USER", "mainuser@OFBIZ_PASSWORD@");
        hostile.put("OFBIZ_POSTGRES_OLAP_USER", "olapuser@OLAP_PASSWORD@");
        hostile.put("OFBIZ_POSTGRES_TENANT_USER", "tenantuser@TENANT_PASSWORD@");
        hostile.put("OFBIZ_POSTGRES_HOST", "db.example.internal@HOST@");
        hostile.put("OFBIZ_POSTGRES_OFBIZ_PASSWORD", "Main-Password-@OLAP_PASSWORD@");
        hostile.put("OFBIZ_POSTGRES_OLAP_PASSWORD", "Olap-Password-@TENANT_PASSWORD@");
        hostile.put("OFBIZ_POSTGRES_TENANT_PASSWORD", "Tenant-Password-@OFBIZ_PASSWORD@");
        hostile.put("OFBIZ_POSTGRES_SSLROOTCERT", "/etc/ssl/certs/@OFBIZ_USERNAME@.crt");

        for (Map.Entry<String, String> field : hostile.entrySet()) {
            Path caseSandbox = prepareSandbox(Files.createTempDirectory(tempDir, "sentinel"));
            Map<String, String> environment = managedDatabaseEnvironment();
            environment.put(field.getKey(), field.getValue());

            EntryPointRun run = runInSandbox(tempDir, caseSandbox, CONFIGURE_DATABASE_BODY, environment);

            assertNotEquals(0, run.getExitCode(), field.getKey()
                    + " carrying a render placeholder must be refused, output was:\n" + run.getOutput());
            assertTrue(run.getOutput().contains(field.getKey()),
                    "the failure must name " + field.getKey() + ", output was:\n" + run.getOutput());
            assertFalse(Files.exists(caseSandbox.resolve(RENDERED_OVERRIDE)),
                    "nothing may be rendered from a value that would splice another field");
        }

        // A bare at-sign is not a placeholder and must still be accepted, so the check cannot be satisfied by
        // simply banning '@' - which would make every e-mail-shaped user name unusable.
        Path permitted = prepareSandbox(Files.createTempDirectory(tempDir, "at-sign"));
        Map<String, String> environment = managedDatabaseEnvironment();
        environment.put("OFBIZ_POSTGRES_OFBIZ_USER", "ofbiz@main");
        EntryPointRun run = runInSandbox(tempDir, permitted, CONFIGURE_DATABASE_BODY, environment);
        assertEquals(0, run.getExitCode(),
                "a bare at-sign must remain usable, output was:\n" + run.getOutput());
        assertEquals("ofbiz@main", inlineJdbcOf(parseXml(Files.readString(permitted.resolve(RENDERED_OVERRIDE),
                StandardCharsets.UTF_8)), "localpostgres").getAttribute("jdbc-username"),
                "the at-sign bearing user name must be rendered verbatim");
    }

    /*
     * require_postgres_ssl_parameters: what the deployed profile will accept as a verified connection
     */

    /**
     * The deployed profile requires the one TLS mode that authenticates the server it is talking to.
     *
     * <p>{@code verify-ca} is refused, because it is the mode that looks safe and is not: pgJDBC
     * checks that the certificate chains to a trusted root but does NOT check that the certificate belongs to
     * the host that was asked for. A managed cloud database service issues every tenant a certificate from one
     * shared certification authority, so under {@code verify-ca} any other tenant's endpoint satisfies the
     * check - an attacker who can influence DNS or routing needs only a certificate of their own from that CA
     * to receive the datasource credentials and every row that follows. The dev profile keeps the full driver
     * vocabulary, which is asserted too, because a developer pointing the container at a local database with no
     * TLS at all must not be blocked.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void theDeployedProfileAcceptsOnlyTheTlsModeThatAlsoVerifiesTheHostname(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");

        for (String weaker : List.of("disable", "allow", "prefer", "require", "verify-ca")) {
            Path sandbox = prepareSandbox(Files.createTempDirectory(tempDir, "prod-tls"));
            Map<String, String> environment = managedDatabaseEnvironment();
            environment.put("OFBIZ_PROFILE", "prod");
            environment.put("OFBIZ_POSTGRES_SSLMODE", weaker);

            EntryPointRun run = runInSandbox(tempDir, sandbox, CONFIGURE_DATABASE_BODY, environment);

            assertNotEquals(0, run.getExitCode(),
                    "sslmode=" + weaker + " must be refused in prod, output was:\n" + run.getOutput());
            assertTrue(run.getOutput().contains("OFBIZ_POSTGRES_SSLMODE"),
                    "the failure must name the variable, output was:\n" + run.getOutput());
            assertTrue(run.getOutput().contains("verify-full"),
                    "the failure must say what prod does accept, output was:\n" + run.getOutput());
            assertFalse(Files.exists(sandbox.resolve(RENDERED_OVERRIDE)),
                    "no configuration may be rendered for a connection prod will not accept");
        }

        Path accepted = prepareSandbox(Files.createTempDirectory(tempDir, "prod-verify-full"));
        Map<String, String> production = managedDatabaseEnvironment();
        production.put("OFBIZ_PROFILE", "prod");
        production.put("OFBIZ_POSTGRES_SSLMODE", "verify-full");
        EntryPointRun productionRun = runInSandbox(tempDir, accepted, CONFIGURE_DATABASE_BODY, production);
        assertEquals(0, productionRun.getExitCode(),
                "verify-full must be accepted in prod, output was:\n" + productionRun.getOutput());

        for (String mode : List.of("disable", "allow", "prefer", "require", "verify-ca", "verify-full")) {
            Path sandbox = prepareSandbox(Files.createTempDirectory(tempDir, "dev-tls"));
            Map<String, String> environment = managedDatabaseEnvironment();
            environment.put("OFBIZ_POSTGRES_SSLMODE", mode);
            // The rendered-TLS validator compares the mode the render was asked for, so it is told the same one.
            EntryPointRun run = runInSandbox(tempDir, sandbox, RESOLVE_FLAGS_BODY
                    + "configure_database\n"
                    + "require_rendered_transport_security " + RENDERED_OVERRIDE + " " + mode + "\n"
                    + "printf '" + COMPLETED + "\\n'\n", environment);
            assertEquals(0, run.getExitCode(),
                    "sslmode=" + mode + " must remain usable in dev, output was:\n" + run.getOutput());
            assertTrue(run.getOutput().contains(COMPLETED),
                    "the dev render must complete for sslmode=" + mode + ", output was:\n" + run.getOutput());
        }
    }

    /*
     * require_admin_password: the credential that holds every OFBiz permission
     */

    /**
     * The deployed profile will not create the fully privileged admin user with a password it was not given, or
     * with one that is published in this repository, or with one that is trivially guessable.
     *
     * <p>A default of {@code ofbiz} in EVERY profile would have two consequences a deployment could not see
     * from the outside: a manifest that simply never set it would create an account holding every OFBiz
     * permission whose password is printed in this repository's own documentation, and a manifest copied from
     * the demo instructions - {@code OFBIZ_ADMIN_PASSWORD=ofbiz} - would be accepted verbatim in production.
     * Both are refused. The dev default is asserted as well, because it is what keeps the demo image usable
     * with no configuration at all and removing it would be a functional regression.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void theDeployedProfileRefusesAnAbsentPublishedOrGuessableAdminPassword(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        EntryPointRun absent = runInSandbox(tempDir, sandbox, SETUP_ENV_BODY, Map.of("OFBIZ_PROFILE", "prod"));
        assertNotEquals(0, absent.getExitCode(),
                "prod must not invent an admin password, output was:\n" + absent.getOutput());
        assertTrue(absent.getOutput().contains("OFBIZ_ADMIN_PASSWORD"),
                "the failure must name the variable, output was:\n" + absent.getOutput());
        assertFalse(absent.getOutput().contains(COMPLETED),
                "the environment must not finish resolving, output was:\n" + absent.getOutput());

        // The published demo password, the values it is most often confused with, and values that are long
        // enough but are patterns rather than secrets.
        for (String refused : List.of("ofbiz", "admin", "password", "ofbizdemo",
                "short", "aaaaaaaaaaaaaaaaaaaa", "abababababababababab", "NA")) {
            EntryPointRun run = runInSandbox(tempDir, sandbox, SETUP_ENV_BODY,
                    Map.of("OFBIZ_PROFILE", "prod", "OFBIZ_ADMIN_PASSWORD", refused));
            assertNotEquals(0, run.getExitCode(),
                    "prod must refuse the admin password [" + refused + "], output was:\n" + run.getOutput());
            assertTrue(run.getOutput().contains("OFBIZ_ADMIN_PASSWORD"),
                    "the failure must name the variable, output was:\n" + run.getOutput());
        }

        EntryPointRun supplied = runInSandbox(tempDir, sandbox, SETUP_ENV_BODY,
                Map.of("OFBIZ_PROFILE", "prod", "OFBIZ_ADMIN_PASSWORD", ADMIN_PASSWORD));
        assertEquals(0, supplied.getExitCode(),
                "a private, strong admin password must be accepted, output was:\n" + supplied.getOutput());
        assertTrue(supplied.getOutput().contains("ADMIN_PASSWORD_LENGTH=" + ADMIN_PASSWORD.length()),
                "the supplied password must be the one that is used, output was:\n" + supplied.getOutput());
        assertFalse(supplied.getOutput().contains(ADMIN_PASSWORD),
                "the admin password must never be echoed, output was:\n" + supplied.getOutput());

        EntryPointRun development = runInSandbox(tempDir, sandbox, SETUP_ENV_BODY, Map.of("OFBIZ_PROFILE", "dev"));
        assertEquals(0, development.getExitCode(),
                "the demo image must still start unconfigured, output was:\n" + development.getOutput());
        assertTrue(development.getOutput().contains("ADMIN_PASSWORD_IS_DEMO_DEFAULT=true"),
                "dev must keep the published demo default, output was:\n" + development.getOutput());
        // A short password is a development convenience, not a production risk, so dev must not apply the floor.
        EntryPointRun shortInDevelopment = runInSandbox(tempDir, sandbox, SETUP_ENV_BODY,
                Map.of("OFBIZ_PROFILE", "dev", "OFBIZ_ADMIN_PASSWORD", "short"));
        assertEquals(0, shortInDevelopment.getExitCode(),
                "dev must accept a short password, output was:\n" + shortInDevelopment.getOutput());
    }

    /*
     * run_init_hooks: operator supplied code, running while every secret is still in the environment
     */

    /**
     * An initialisation hook cannot see a deployment secret, and cannot turn shell tracing on for the rest of
     * the start up.
     *
     * <p>A hook is arbitrary operator-supplied code that this script cannot vet, and running it with every
     * secret exported and with this shell's tracing in force would leak in two ways. A SOURCED hook runs in
     * this shell, so with {@code OFBIZ_TRACE} set its own commands would be traced by this shell and any
     * expansion of a secret inside it written verbatim to the container log. And a hook may enable tracing
     * ITSELF - the example hook shipped in {@code docker/examples/postgres-demo/after-config-applied.d}
     * literally runs {@code set -x} - which, because it is sourced, would survive its return and trace this
     * script's own secret handling from that point on.</p>
     *
     * <p>The hook used here is deliberately hostile in exactly those two ways: it enables tracing and then
     * prints every secret variable it can see. Both the executable and the sourced form are exercised, because
     * they leak by different routes, and each form is run twice - once with tracing off, where a hook that
     * switched it on must not leave it on, and once with the operator's own {@code OFBIZ_TRACE} set, where the
     * setting must be honoured and yet still not disclose anything. The allowlist is exercised too, since a
     * hook that legitimately needs a value must be able to receive it - explicitly.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void anInitialisationHookSeesNoSecretAndCannotLeaveTracingSwitchedOn(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);
        Path hook = sandbox.resolve("hook.sh");
        Files.writeString(hook, "#!/usr/bin/env bash\n"
                // A hostile hook does what the shipped example does - and then reports what it can read.
                + "set -x\n"
                + "for name in OFBIZ_ADMIN_PASSWORD OFBIZ_ADMIN_KEY OFBIZ_LOGIN_SECRET_KEY OFBIZ_JWT_TOKEN_KEY \\\n"
                + "  OFBIZ_POSTGRES_OFBIZ_PASSWORD OFBIZ_POSTGRES_OLAP_PASSWORD OFBIZ_POSTGRES_TENANT_PASSWORD; do\n"
                + "  printf 'HOOK_SAW %s=[%s]\\n' \"$name\" \"${!name-}\"\n"
                + "done\n", StandardCharsets.UTF_8);

        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("OFBIZ_PROFILE", "dev");
        secrets.put("OFBIZ_ADMIN_PASSWORD", ADMIN_PASSWORD);
        secrets.put("OFBIZ_ADMIN_KEY", ADMIN_KEY);
        secrets.put("OFBIZ_LOGIN_SECRET_KEY", LOGIN_KEY);
        secrets.put("OFBIZ_JWT_TOKEN_KEY", JWT_KEY);
        secrets.put("OFBIZ_POSTGRES_OFBIZ_PASSWORD", OFBIZ_PASSWORD);
        secrets.put("OFBIZ_POSTGRES_OLAP_PASSWORD", OLAP_PASSWORD);
        secrets.put("OFBIZ_POSTGRES_TENANT_PASSWORD", TENANT_PASSWORD);
        List<String> everySecret = List.of(ADMIN_PASSWORD, ADMIN_KEY, LOGIN_KEY, JWT_KEY,
                OFBIZ_PASSWORD, OLAP_PASSWORD, TENANT_PASSWORD);
        List<String> scrubbed = List.of("OFBIZ_ADMIN_PASSWORD", "OFBIZ_ADMIN_KEY", "OFBIZ_LOGIN_SECRET_KEY",
                "OFBIZ_JWT_TOKEN_KEY", "OFBIZ_POSTGRES_OFBIZ_PASSWORD", "OFBIZ_POSTGRES_OLAP_PASSWORD",
                "OFBIZ_POSTGRES_TENANT_PASSWORD");

        for (boolean executable : List.of(true, false)) {
            assertTrue(hook.toFile().setExecutable(executable), "could not set the hook's execute bit");
            String form = executable ? "an executable" : "a sourced";

            for (String tracing : List.of("off", "on")) {
                Map<String, String> environment = new LinkedHashMap<>(secrets);
                if ("on".equals(tracing)) {
                    environment.put("OFBIZ_TRACE", "1");
                }
                EntryPointRun run = runInSandbox(tempDir, sandbox, HOOK_BODY, environment);

                assertEquals(0, run.getExitCode(),
                        form + " hook must run, output was:\n" + run.getOutput());
                for (String name : scrubbed) {
                    assertTrue(run.getOutput().contains("HOOK_SAW " + name + "=[]"),
                            form + " hook must see no value for " + name + ", output was:\n" + run.getOutput());
                }
                for (String secret : everySecret) {
                    assertFalse(run.getOutput().contains(secret), "a secret reached the output of " + form
                            + " hook run with tracing " + tracing + ", output was:\n" + run.getOutput());
                }
                // Restored, with their exported status, for the code that runs after the hook.
                assertTrue(run.getOutput().contains("AFTER_HOOK_ADMIN_PASSWORD_LENGTH=" + ADMIN_PASSWORD.length()),
                        "the secrets must be restored after " + form + " hook, output was:\n" + run.getOutput());
                assertTrue(run.getOutput().contains("AFTER_HOOK_TRACING=" + tracing),
                        "after " + form + " hook, tracing must be exactly what the operator asked for ("
                                + tracing + "), output was:\n" + run.getOutput());
            }
        }

        // Explicitly re-admitted, which is the only way a hook may see a value.
        assertTrue(hook.toFile().setExecutable(true), "could not set the hook's execute bit");
        Map<String, String> allowlisted = new LinkedHashMap<>(secrets);
        allowlisted.put("OFBIZ_HOOK_SECRET_ALLOWLIST", "OFBIZ_POSTGRES_OFBIZ_PASSWORD");
        EntryPointRun permitted = runInSandbox(tempDir, sandbox, HOOK_BODY, allowlisted);
        assertEquals(0, permitted.getExitCode(),
                "an allowlisted hook must run, output was:\n" + permitted.getOutput());
        assertTrue(permitted.getOutput().contains("HOOK_SAW OFBIZ_POSTGRES_OFBIZ_PASSWORD=[" + OFBIZ_PASSWORD + "]"),
                "an allowlisted value must reach the hook, output was:\n" + permitted.getOutput());
        assertTrue(permitted.getOutput().contains("HOOK_SAW OFBIZ_POSTGRES_OLAP_PASSWORD=[]"),
                "only the allowlisted value may reach the hook, output was:\n" + permitted.getOutput());

        // A misspelled name is a fatal misconfiguration rather than a silent no-op, because as written the hook
        // would simply receive nothing and the operator would have no way to tell.
        Map<String, String> misspelled = new LinkedHashMap<>(secrets);
        misspelled.put("OFBIZ_HOOK_SECRET_ALLOWLIST", "OFBIZ_POSTGRES_PASSWORD");
        EntryPointRun refused = runInSandbox(tempDir, sandbox, SETUP_ENV_BODY, misspelled);
        assertNotEquals(0, refused.getExitCode(),
                "a misspelled allowlist entry must be refused, output was:\n" + refused.getOutput());
        assertTrue(refused.getOutput().contains("OFBIZ_HOOK_SECRET_ALLOWLIST"),
                "the failure must name the variable, output was:\n" + refused.getOutput());
    }

    /*
     * configure_database: which source is rendered, and how often
     */

    /**
     * The database configuration is re-rendered on every container start, so a rotated password or a changed
     * setting takes effect on a restart instead of being pinned by a marker file.
     *
     * <p>The marker file is written for the state directory to keep documenting what was applied, and it
     * records the host - but it must not gate the render. This starts with the marker already present and
     * naming a different host, which is the state a restart is always in, and then changes a setting twice to
     * show that each render comes from the pristine template rather than from the previous output.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void theDatabaseConfigurationIsReRenderedOnEveryStartSoARotatedSettingTakesEffect(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);
        Path marker = sandbox.resolve("state/db_config_applied");
        Files.writeString(marker, "a-previous.host\n", StandardCharsets.UTF_8);

        Map<String, String> first = managedDatabaseEnvironment();
        first.put("OFBIZ_DB_POOL_MAX", "99");
        assertEquals(0, runInSandbox(tempDir, sandbox, CONFIGURE_DATABASE_BODY, first).getExitCode(),
                "an existing marker file must not prevent the render");
        Element afterFirst = parseXml(Files.readString(sandbox.resolve(RENDERED_OVERRIDE), StandardCharsets.UTF_8));
        assertEquals("99", inlineJdbcOf(afterFirst, "localpostgres").getAttribute("pool-maxsize"),
                "the first render must apply the setting it was given");
        assertTrue(Files.readString(marker, StandardCharsets.UTF_8).contains("host=" + DATABASE_HOST),
                "the marker must record the host the configuration was rendered for");

        Map<String, String> second = managedDatabaseEnvironment();
        second.put("OFBIZ_DB_POOL_MAX", "111");
        assertEquals(0, runInSandbox(tempDir, sandbox, CONFIGURE_DATABASE_BODY, second).getExitCode(),
                "a restart must render again rather than keeping the previous configuration");
        Element afterSecond = parseXml(Files.readString(sandbox.resolve(RENDERED_OVERRIDE), StandardCharsets.UTF_8));
        assertEquals("111", inlineJdbcOf(afterSecond, "localpostgres").getAttribute("pool-maxsize"),
                "the changed setting must reach the configuration on the next start");
    }

    /**
     * Every non-secret input the managed render substitutes changes the recorded desired state, so a start that
     * applies a different configuration is reported as applying a different one.
     *
     * <p>The record exists to tell an operator that the container is not connected to what the previous start
     * was connected to. A field the record omits defeats that silently and in the worst possible way: the two
     * starts rendered genuinely different artefacts and the container reported no change at all. The omission
     * cannot be caught by reading the fingerprint function, because what has to be compared is that function
     * against the render - so this drives the REAL render for each input in turn and requires the record to
     * move. Both halves of the promise are asserted: the same configuration twice records the same state, and
     * every single input records a different one.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void everyNonSecretRenderInputMovesTheRecordedDesiredState(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");

        String baseline = recordedDesiredState(renderedWith(tempDir, managedDatabaseEnvironment()));
        assertEquals(declaredRecordVersion(), baseline.lines().findFirst().orElse(""),
                "the record must open with the format version the entry point declares");
        assertEquals(baseline, recordedDesiredState(renderedWith(tempDir, managedDatabaseEnvironment())),
                "two starts applying the same configuration must record the same state");

        // Every input is tried before anything is asserted, so a failure reports the COMPLETE set of inputs
        // the record does not notice rather than only the first one - which is what makes the difference
        // between "one field is missing" and "a whole family of them is" visible from the failure alone.
        List<String> unnoticed = new ArrayList<>();
        for (Map.Entry<String, String> input : NON_SECRET_RENDER_INPUTS.entrySet()) {
            Map<String, String> perturbed = managedDatabaseEnvironment();
            perturbed.put(input.getKey(), input.getValue());
            if (baseline.equals(recordedDesiredState(renderedWith(tempDir, perturbed)))) {
                unnoticed.add(input.getKey());
            }
        }
        assertEquals(List.of(), unnoticed,
                "each of these changes what is rendered, so each must change the recorded desired state");
    }

    /**
     * The record names the database identity the render actually used, so an initialisation run and a serving
     * run against the same host are not recorded as the same target.
     *
     * <p>An initialisation run renders the privileged roles into the configuration and a serving run renders
     * the serving roles, and the two are required to be different roles. Recording the serving names in both
     * cases would therefore give two starts with genuinely different rendered artefacts an identical record -
     * which is the one thing the record must never do. The rendered file is read as well as the record, so the
     * case checks the record agrees with the artefact rather than merely differing from its sibling.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run or the render could not be parsed, either of which fails
     *         the test rather than being handled
     */
    @Test
    public void theRecordedDesiredStateNamesTheDatabaseIdentityTheRenderActuallyUsed(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");

        Map<String, String> serving = managedDatabaseEnvironment();
        serving.put("OFBIZ_SCHEMA_INIT", "true");
        Map<String, String> privileged = new LinkedHashMap<>(serving);
        privileged.putAll(initIdentityEnvironment());

        Path servingSandbox = renderedWith(tempDir, serving);
        Path privilegedSandbox = renderedWith(tempDir, privileged);
        String servingRecord = recordedDesiredState(servingSandbox);
        String privilegedRecord = recordedDesiredState(privilegedSandbox);

        assertNotEquals(servingRecord, privilegedRecord,
                "an initialisation identity renders different roles, so it must record a different state");
        assertTrue(servingRecord.contains("database-identity=serving"),
                "the serving run must record the serving identity, record was:\n" + servingRecord);
        assertTrue(servingRecord.contains("ofbiz-username=" + OFBIZ_USER),
                "the serving run must record the role it rendered, record was:\n" + servingRecord);
        assertTrue(privilegedRecord.contains("database-identity=schema-init"),
                "the init run must record the initialisation identity, record was:\n" + privilegedRecord);
        assertTrue(privilegedRecord.contains("ofbiz-username=" + OFBIZ_INIT_USER),
                "the init run must record the role it rendered, record was:\n" + privilegedRecord);
        assertEquals(OFBIZ_INIT_USER, renderedManagedUsers(privilegedSandbox).get("localpostgres"),
                "the recorded role must be the one the configuration really authenticates as");
    }

    /**
     * A record written in an earlier format is discarded rather than compared field by field, so an existing
     * state volume upgrades instead of reporting a change that did not happen.
     *
     * <p>The fields a record carries have grown, and a record written before they existed cannot be compared
     * with one written after: every added field would show up as a change of target. The version on the first
     * line is what makes that decidable, and this asserts the consequence an operator sees - the run reports
     * that there was no comparable record, does NOT report a difference, and leaves a record in the current
     * format behind.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void aDesiredStateRecordInAnEarlierFormatIsDiscardedRatherThanCompared(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        assertNotEquals(EARLIER_RECORD_VERSION, declaredRecordVersion(),
                "the format this case plants must be an EARLIER one than the entry point writes");

        Path sandbox = prepareSandbox(tempDir);
        Path record = sandbox.resolve(DB_CONFIG_APPLIED_MARKER);
        Files.createDirectories(record.getParent());
        Files.writeString(record, EARLIER_RECORD_VERSION + "\n"
                + "profile=dev\n"
                + "database-mode=managed\n"
                + "schema-init=false\n"
                + "distributed-cache-clear=false\n"
                + "postgres-host=" + DATABASE_HOST + "\n", StandardCharsets.UTF_8);

        EntryPointRun run = runInSandbox(tempDir, sandbox, CONFIGURE_DATABASE_BODY, managedDatabaseEnvironment());
        assertEquals(0, run.getExitCode(), "an earlier record must not fail the render, output was:\n"
                + run.getOutput());
        assertTrue(run.getOutput().contains("No comparable record from an earlier start was present"),
                "the run must report the earlier record as not comparable, output was:\n" + run.getOutput());
        assertFalse(run.getOutput().contains("differs from the previous start"),
                "an incomparable record must not be reported as a difference, output was:\n" + run.getOutput());
        assertEquals(declaredRecordVersion(),
                Files.readString(record, StandardCharsets.UTF_8).lines().findFirst().orElse(""),
                "the run must leave a record in the format it writes");
    }

    /**
     * A rotated password changes nothing in the record, and no password appears in it.
     *
     * <p>Two things at once, and both are load bearing. The record is written into the container's state
     * directory, so a secret in it would outlive the process that had the secret in its environment; and the
     * configuration is re-rendered unconditionally on every start, so a rotated password takes effect whatever
     * the record says and reporting it as a change of target would be misleading.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void aRotatedDatabasePasswordLeavesTheRecordedDesiredStateUnchanged(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");

        String baseline = recordedDesiredState(renderedWith(tempDir, managedDatabaseEnvironment()));

        Map<String, String> rotated = managedDatabaseEnvironment();
        rotated.put("OFBIZ_POSTGRES_OFBIZ_PASSWORD", OFBIZ_PASSWORD + "-rotated");
        rotated.put("OFBIZ_POSTGRES_OLAP_PASSWORD", OLAP_PASSWORD + "-rotated");
        rotated.put("OFBIZ_POSTGRES_TENANT_PASSWORD", TENANT_PASSWORD + "-rotated");
        String afterRotation = recordedDesiredState(renderedWith(tempDir, rotated));

        assertEquals(baseline, afterRotation, "a rotated password must not be reported as a changed target");
        for (String secret : List.of(OFBIZ_PASSWORD, OLAP_PASSWORD, TENANT_PASSWORD)) {
            assertFalse(baseline.contains(secret), "no password may reach the record, record was:\n" + baseline);
        }
    }

    /**
     * The embedded profile generates no override at all until cross-instance invalidation is asked for, and
     * when it is, the flag is rewritten on exactly the two default delegators.
     *
     * <p>This is what keeps the change backward compatible: an unconfigured checkout must read the committed
     * {@code entityengine.xml} byte for byte, so {@code /ofbiz/config} has to gain no
     * {@code entityengine.xml} whatsoever. When the flag is set without a managed database the committed file
     * becomes the render source instead of the template, and the {@code test} delegator - which is not in the
     * substitution list and declares no such attribute - has to come through untouched and still on H2.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run or the render could not be parsed, either of which fails
     *         the test rather than being handled
     */
    @Test
    public void theEmbeddedProfileGeneratesNoOverrideUntilCrossInstanceInvalidationIsAskedFor(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");

        Path untouched = prepareSandbox(Files.createTempDirectory(tempDir, "embedded"));
        EntryPointRun unconfigured = runInSandbox(tempDir, untouched, CONFIGURE_DATABASE_BODY, Map.of());
        assertEquals(0, unconfigured.getExitCode(),
                "an unconfigured start must succeed, output was:\n" + unconfigured.getOutput());
        assertTrue(unconfigured.getOutput().contains(COMPLETED),
                "configure_database must return, output was:\n" + unconfigured.getOutput());
        assertFalse(Files.exists(untouched.resolve(RENDERED_OVERRIDE)),
                "an unconfigured container must keep reading the committed configuration, with no override");

        Path coherent = prepareSandbox(Files.createTempDirectory(tempDir, "embedded-cache"));
        EntryPointRun configured = runInSandbox(tempDir, coherent, CONFIGURE_DATABASE_WITHOUT_TRANSPORT_BODY,
                Map.of("OFBIZ_DISTRIBUTED_CACHE_CLEAR", "true", "OFBIZ_PROFILE", "dev"));
        assertEquals(0, configured.getExitCode(),
                "the embedded render must succeed, output was:\n" + configured.getOutput());
        Element root = parseXml(Files.readString(coherent.resolve(RENDERED_OVERRIDE), StandardCharsets.UTF_8));
        for (String delegatorName : CACHE_CLEAR_DELEGATORS) {
            assertEquals("true", delegator(root, delegatorName).getAttribute(CACHE_CLEAR_ENABLED),
                    delegatorName + " must have the flag rewritten by the embedded render");
        }
        assertFalse(delegator(root, "test").hasAttribute(CACHE_CLEAR_ENABLED),
                "the test delegator must not be given a cache-clear attribute by the embedded render");
        assertEquals(TEST_DELEGATOR_MAPPING, delegatorMapping(root, "test"),
                "the embedded render must leave the test delegator on the embedded datasources");
        // The committed development default is what the embedded source declares, so it has to survive.
        assertEquals("localh2", delegatorMapping(root, "default").get("org.apache.ofbiz"),
                "the embedded render must not repoint the default delegator at anything");
    }

    /**
     * Asking for schema initialisation without a managed database says what the run will do instead of
     * appearing to do nothing.
     *
     * <p>The startup-DDL flags this mode sets apply to the managed datasources only - embedded H2 always
     * creates its own schema - so the render has nothing to change and the run's only visible effect is that it
     * exits without serving. Reporting that is the difference between an understood no-op and an operator
     * concluding that init mode is broken.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void schemaInitWithoutAManagedDatabaseSaysWhatItWillDoInsteadOfAppearingToDoNothing(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        EntryPointRun run = runInSandbox(tempDir, sandbox, CONFIGURE_DATABASE_BODY,
                Map.of("OFBIZ_SCHEMA_INIT", "true"));

        assertEquals(0, run.getExitCode(), "the run must not fail, output was:\n" + run.getOutput());
        assertTrue(run.getOutput().contains("OFBIZ_SCHEMA_INIT=true with no OFBIZ_POSTGRES_HOST"),
                "the run must report the embedded case explicitly, output was:\n" + run.getOutput());
        assertFalse(Files.exists(sandbox.resolve(RENDERED_OVERRIDE)),
                "there is nothing to render for the embedded profile in this mode");
    }

    /*
     * load_data and _main: what init mode DOES, and that it never serves
     */

    /**
     * The schema-only data load runs exactly once in init mode and never in a serving start.
     *
     * <p>This is the step that actually applies the DDL, and the reason it is not obvious: the Entity Engine
     * has no standalone DDL command, so the schema is created when a delegator is created, which the data
     * loader does before reading anything. {@code readers=none} names a reader no component declares, so
     * {@code EntityDataLoader} resolves an empty URL list and loads no business data while the delegator - and
     * with it the {@code check-on-start}/{@code add-missing-on-start} DDL - still happens. It is not a run that
     * writes nothing: {@code EntityDataLoadContainer} upserts the framework's own {@code Component} metadata
     * before it resolves a reader, which is asserted in {@code SchemaInitGatingTests}. Deleting the invocation
     * would leave init mode rendering a DDL-enabled configuration that nothing ever opens.</p>
     *
     * <p>The loader is intercepted by a shell function named for its absolute path, which bash resolves ahead
     * of the file system, so nothing outside the sandbox is created or executed. The recorded invocations are
     * matched exactly rather than counted loosely, because the run also performs the additional-data load that
     * the shipped script has always attempted when {@code /docker-entrypoint-hooks/additional-data.d} is absent
     * - upstream behaviour this refactor deliberately leaves alone.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void theSchemaOnlyDataLoadRunsExactlyOnceInInitModeAndNeverInAServingStart(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");

        Path initialising = prepareSandbox(Files.createTempDirectory(tempDir, "init"));
        EntryPointRun initRun = runInSandbox(tempDir, initialising, LOAD_DATA_BODY,
                Map.of("OFBIZ_SCHEMA_INIT", "true", "OFBIZ_DATA_LOAD", "none"));
        assertEquals(0, initRun.getExitCode(), "the load must succeed, output was:\n" + initRun.getOutput());
        assertEquals(1, countLoaderInvocations(initialising, SCHEMA_ONLY_LOAD),
                "init mode must create the delegator exactly once so the entity model is applied");

        Path serving = prepareSandbox(Files.createTempDirectory(tempDir, "serving"));
        EntryPointRun servingRun = runInSandbox(tempDir, serving, LOAD_DATA_BODY, Map.of("OFBIZ_DATA_LOAD", "none"));
        assertEquals(0, servingRun.getExitCode(), "the load must succeed, output was:\n" + servingRun.getOutput());
        assertEquals(0, countLoaderInvocations(serving, SCHEMA_ONLY_LOAD),
                "a serving start must not open a delegator for the sake of DDL");
    }

    /**
     * Init mode exits successfully before the serving command is ever executed, while a normal start execs
     * exactly the command it was given.
     *
     * <p>The pair is the whole of the one-shot contract. Because the init run never becomes a long-lived
     * process, it is safe to grant DDL privileges to and it can be run as an init container or a job that must
     * complete before the fleet starts; because a normal start still reaches {@code exec "$@"}, adding the
     * mode changed nothing for the serving path. The stages before the decision are replaced with recorders, so
     * this test isolates the decision itself and does not re-assert the rendering the tests above cover.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void initModeExitsInsteadOfServingWhileANormalStartExecsTheCommandItWasGiven(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        EntryPointRun initialising = runInSandbox(tempDir, sandbox, MAIN_BODY,
                Map.of("OFBIZ_SCHEMA_INIT", "true"), List.of("/bin/echo", SERVING_MARKER));
        assertEquals(0, initialising.getExitCode(),
                "an init run must report success, output was:\n" + initialising.getOutput());
        assertTrue(initialising.getOutput().contains("schema initialisation complete"),
                "an init run must say why it is stopping, output was:\n" + initialising.getOutput());
        assertFalse(initialising.getOutput().contains(SERVING_MARKER),
                "an init run must never exec the serving command, output was:\n" + initialising.getOutput());
        // The exit must come AFTER the work, not instead of it: an init run that skipped the rendering and the
        // schema application would exit 0 having created nothing, which is the failure the skip-init conflict
        // also guards against. The stage named here is initialise_schema rather than load_data because init
        // mode takes NEITHER of the generic data stages: those are gated on their own markers, and running
        // them in this mode would make an init job load seed data, provision a login, and - on a reused state
        // volume - report success without having applied anything.
        assertTrue(initialising.getOutput().contains("STAGE configure_database")
                && initialising.getOutput().contains("STAGE initialise_schema"),
                "an init run must reach the rendering and schema-application stages before exiting, output "
                        + "was:\n" + initialising.getOutput());
        assertFalse(initialising.getOutput().contains("STAGE load_data")
                || initialising.getOutput().contains("STAGE load_admin_user"),
                "an init run must take neither generic data stage, output was:\n" + initialising.getOutput());

        EntryPointRun serving = runInSandbox(tempDir, sandbox, MAIN_BODY, Map.of(),
                List.of("/bin/echo", SERVING_MARKER));
        assertEquals(0, serving.getExitCode(), "a normal start must succeed, output was:\n" + serving.getOutput());
        assertTrue(serving.getOutput().contains(SERVING_MARKER),
                "a normal start must exec the command it was given, output was:\n" + serving.getOutput());
        assertFalse(serving.getOutput().contains("schema initialisation complete"),
                "a normal start must not take the one-shot exit, output was:\n" + serving.getOutput());
    }

    /**
     * Every container variable the entry point declares is withdrawn before it execs, and the withdrawal is
     * driven by those declarations rather than by a hand written list.
     *
     * <p>The sibling case below plants a realistic environment and starts the whole script, which is what
     * exercises the withdrawal on the real serving path. It cannot plant EVERY name, because most of them
     * have a grammar the script validates before it will start at all. This case takes the other half: it
     * drives the withdrawal on its own, with every declared name present, so the rule is asserted over
     * the complete set instead of over a representative sample of it.</p>
     *
     * <p>The names come from the script's own two arrays, never from a list restated here, and the entry point
     * is required to contain no hand written {@code unset OFBIZ_} statement at all. Together those two make
     * the agreement structural: a variable added to either array is withdrawn without anything else being
     * edited, and a future change that replaced the loop with statements again would have to reintroduce the
     * drift this case exists to prevent - which it cannot do unnoticed.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void everyContainerVariableTheEntryPointDeclaresIsWithdrawnBeforeItExecs(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        Set<String> rendered = declaredVariableNames("RUNTIME_APPLIED_VARIABLES");
        Set<String> control = declaredVariableNames("CONTAINER_CONTROL_VARIABLES");
        assertEquals(Set.of(), intersection(rendered, control),
                "a variable is either rendered into a file or it steers the script, never declared as both");

        Set<String> declared = new LinkedHashSet<>(rendered);
        declared.addAll(control);
        String marker = "WITHDRAWN7f42ce18";
        Map<String, String> everything = new LinkedHashMap<>();
        for (String variable : declared) {
            everything.put(variable, marker + variable);
        }

        EntryPointRun run = runInSandbox(tempDir, sandbox, WITHDRAWAL_BODY, everything);
        assertEquals(0, run.getExitCode(), "the withdrawal must succeed, output was:\n" + run.getOutput());

        Set<String> before = reportedVariableNames(run.getOutput(), "BEFORE");
        assertEquals(declared, before,
                "every declared name must be present before the withdrawal, or this case proves nothing");
        assertEquals(Set.of(), reportedVariableNames(run.getOutput(), "AFTER"),
                "no OFBIZ_ variable may survive the withdrawal, output was:\n" + run.getOutput());
        // Against the complete environment the exec'd process would receive, not against the whole run: with
        // OFBIZ_TRACE planted, sourcing the script traces its own reading of that variable, so the value
        // appears in the run's output before the withdrawal has even been reached. What a value may not do is
        // survive INTO the environment - including under some other name it was copied to, which a listing of
        // names alone would not reveal. Whether tracing itself may publish a secret is asserted by the cases
        // that are about tracing.
        String survived = section(run.getOutput(), "ENVIRONMENT");
        assertFalse(survived.contains(marker),
                "no value the container was configured with may survive under any name, environment was:\n"
                        + survived);

        String script = Files.readString(repositoryRoot().resolve(ENTRY_POINT), StandardCharsets.UTF_8);
        assertFalse(Pattern.compile("^\\s*unset OFBIZ_", Pattern.MULTILINE).matcher(script).find(),
                "the withdrawal must be driven by the declared arrays, not by hand written unset statements");
    }

    /**
     * Every container variable the entry point reads is declared in one of its two inventories, so none can be
     * consumed without also being withdrawn.
     *
     * <p>The case above asserts that what the inventories declare is withdrawn. This one closes the other
     * direction: a variable the script reads but has forgotten to declare would be consumed, would decide
     * something, and would then be handed to the served JVM - which is precisely the drift that left
     * {@code OFBIZ_DISABLE_COMPONENTS} and {@code OFBIZ_POSTGRES_HOST} visible to it, and precisely what the
     * inventories' own claim to account for every name would deny.</p>
     *
     * <p>Only literal expansions are counted, so a name that merely appears in a message is not mistaken for
     * one that is read. The nine per-group credentials are built at runtime from a group name and can never
     * appear literally, which is why the requirement is one-directional: every name read must be declared, not
     * every name declared must be read literally.</p>
     *
     * @throws IOException if the entry point could not be read, which fails the test
     */
    @Test
    public void everyContainerVariableTheEntryPointReadsIsAccountedForByAnInventory() throws IOException {
        String script = Files.readString(repositoryRoot().resolve(ENTRY_POINT), StandardCharsets.UTF_8);

        Set<String> declared = new LinkedHashSet<>(declaredVariableNames("RUNTIME_APPLIED_VARIABLES"));
        declared.addAll(declaredVariableNames("CONTAINER_CONTROL_VARIABLES"));

        // The script's own variables are not container configuration and are deliberately excluded. They are
        // identified by the script assigning them at top level, rather than by name, so the exclusion cannot
        // become a way to leave a real container variable out: OFBIZ_PROFILES is the list of profile names this
        // script accepts, which reads like a container variable and is not one.
        Set<String> ownVariables = new LinkedHashSet<>();
        Matcher assignment = Pattern.compile("^(OFBIZ_[A-Z0-9_]+)=", Pattern.MULTILINE).matcher(script);
        while (assignment.find()) {
            ownVariables.add(assignment.group(1));
        }

        Set<String> unaccounted = new LinkedHashSet<>();
        Matcher expansion = Pattern.compile("\\$\\{?(OFBIZ_[A-Z0-9_]+)").matcher(script);
        while (expansion.find()) {
            String name = expansion.group(1);
            if (!declared.contains(name) && !ownVariables.contains(name)) {
                unaccounted.add(name);
            }
        }
        assertEquals(Set.of(), unaccounted,
                "each of these is read by the entry point but declared by neither inventory, so it would be "
                        + "consumed and then handed to the served JVM");
    }

    /**
     * Every count the entry point states about a list agrees with the list, and no count goes unchecked.
     *
     * <p>These comments are what an operator reads to know whether a set of variables is complete - "supplied
     * together or not at all" is only actionable if the reader knows how many "all" is - and they are the first
     * thing to go stale when a variable is added or removed. Two directions are required. Every registered
     * claim must still be in the prose, so a reworded comment cannot leave the count unchecked; and every
     * "&lt;number&gt; variables" the prose contains must be registered, so a NEW count cannot arrive unchecked.</p>
     *
     * <p>Comment text is flattened before it is matched, because these claims wrap across lines and a fragment
     * compared against the raw file would never be found - which would make every assertion here pass
     * vacuously.</p>
     *
     * @throws IOException if the entry point could not be read, which fails the test
     */
    @Test
    public void everyVariableCountTheEntryPointStatesAgreesWithTheListItDescribes() throws IOException {
        String script = Files.readString(repositoryRoot().resolve(ENTRY_POINT), StandardCharsets.UTF_8);
        String prose = flattenedComments(script);

        List<String> disagreeing = new ArrayList<>();
        for (Map.Entry<String, String> claim : STATED_LIST_SIZES.entrySet()) {
            String fragment = claim.getKey();
            assertTrue(prose.contains(fragment),
                    "this claim is registered here but is no longer in the entry point, so the count it states "
                            + "is checked against nothing: " + fragment);
            String[] selector = claim.getValue().split("#", 2);
            String member = selector.length == 2 ? selector[1] : ".*";
            long declares = declaredVariableNames(selector[0]).stream()
                    .filter(name -> name.matches(member))
                    .count();
            int states = statedNumber(fragment);
            if (states != declares) {
                disagreeing.add(selector[0] + " declares " + declares + " of " + member + ", the prose states "
                        + states + ": " + fragment);
            }
        }
        // Collected rather than asserted one at a time, so a change that invalidates a family of counts reports
        // the whole family instead of stopping at whichever happens to be registered first.
        assertEquals(List.of(), disagreeing,
                "each of these counts disagrees with the list the comment describes");

        assertTrue(prose.contains(LOCAL_VARIABLE_COUNT),
                "the one deliberately unregistered count is no longer present, so the exclusion below is stale");
        List<String> spans = new ArrayList<>(STATED_LIST_SIZES.keySet());
        spans.add(LOCAL_VARIABLE_COUNT);
        List<String> unregistered = new ArrayList<>();
        Matcher counted = Pattern.compile("\\b(" + String.join("|", NUMBER_WORDS.keySet())
                + ")\\s+(?:[A-Za-z0-9_*/ ]{0,60}?\\s)?variables\\b", Pattern.CASE_INSENSITIVE).matcher(prose);
        while (counted.find()) {
            String site = prose.substring(counted.start(), counted.end());
            boolean registered = spans.stream()
                    .anyMatch(fragment -> containsAt(prose, fragment, counted.start(), counted.end()));
            if (!registered) {
                unregistered.add(site + " (at offset " + counted.start() + ")");
            }
        }
        assertEquals(List.of(), unregistered,
                "each of these counts variables somewhere no test can check it; register it in STATED_LIST_SIZES "
                        + "against the array it describes");

        // The one count of names the prose itself enumerates, so the sentence is checked against itself.
        int claimAt = prose.indexOf(SDK_VARIABLES_CLAIM);
        assertTrue(claimAt >= 0, "the claim about the SDK's own variables is no longer present");
        String enumeration = prose.substring(claimAt,
                Math.min(prose.length(), claimAt + SDK_VARIABLES_CLAIM.length() + SDK_CLAIM_ENUMERATION_LENGTH));
        Set<String> listed = new LinkedHashSet<>();
        Matcher sdkName = Pattern.compile("AWS_[A-Z_]+").matcher(enumeration);
        while (sdkName.find()) {
            listed.add(sdkName.group());
        }
        assertEquals(statedNumber(SDK_VARIABLES_CLAIM), listed.size(),
                "the claim states a number of SDK variables that its own list does not hold: " + listed);
    }

    /**
     * Nothing the container was configured with survives into the environment of the process it exec's.
     *
     * <p>Every value in this list has already been written into a mode {@code 0600} file under
     * {@code /ofbiz/config}, so keeping it in the environment adds nothing but exposure: an environment
     * variable is readable through {@code /proc/<pid>/environ} by anything sharing the PID namespace, is
     * inherited by every child process the JVM starts, and is reported by any diagnostic that dumps the
     * environment. The list deliberately covers more than the secrets - the schema-init flag, the profile,
     * the object-store settings - because a serving JVM has no use for any of them and because a value left
     * behind is a value some later change can start depending on.
     *
     * <p>The exec'd command is {@code env}, so what this test reads is literally the environment the OFBiz
     * process would have been started with. The initialisation stages are replaced with recorders, which is
     * what isolates the withdrawal from the rendering the other tests cover.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void noInjectedSecretOrSettingSurvivesIntoTheEnvironmentOfTheServerItStarts(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        // Values chosen so that a survivor is identifiable by its VALUE as well as by its name. The prefix is
        // deliberately implausible as anything else in an environment, because a short value such as 'true' or
        // 'us-east-1' occurs in unrelated variables on a real build machine and would make the value assertion
        // report a false survivor.
        String unique = "WITHDRAWN0ba9c17d";
        Map<String, String> distinctive = new LinkedHashMap<>();
        for (String variable : List.of("OFBIZ_ADMIN_PASSWORD", "OFBIZ_ADMIN_KEY", "OFBIZ_LOGIN_SECRET_KEY",
                "OFBIZ_JWT_TOKEN_KEY", "OFBIZ_POSTGRES_OFBIZ_PASSWORD", "OFBIZ_POSTGRES_OLAP_PASSWORD",
                "OFBIZ_POSTGRES_TENANT_PASSWORD", "OFBIZ_POSTGRES_OFBIZ_INIT_PASSWORD",
                "OFBIZ_POSTGRES_OLAP_INIT_PASSWORD", "OFBIZ_POSTGRES_TENANT_INIT_PASSWORD",
                "OFBIZ_POSTGRES_OFBIZ_INIT_USER", "OFBIZ_POSTGRES_OLAP_INIT_USER",
                "OFBIZ_POSTGRES_TENANT_INIT_USER", "OFBIZ_POSTGRES_OFBIZ_USER", "OFBIZ_POSTGRES_OLAP_USER",
                "OFBIZ_POSTGRES_TENANT_USER", "OFBIZ_POSTGRES_HOST", "OFBIZ_S3_ACCESS_KEY_ID",
                "OFBIZ_S3_SECRET_ACCESS_KEY",
                "OFBIZ_ADMIN_USER", "OFBIZ_HOST", "OFBIZ_CONTENT_URL_PREFIX", "OFBIZ_JVM_ROUTE")) {
            distinctive.put(variable, unique + variable);
        }

        // The bucket, the region and the endpoint have a grammar of their own that the entry point validates
        // before it renders them, so they cannot carry the same shape as the values above: a bucket name is
        // lower case, a region is lower case and hyphenated, and an endpoint is an absolute URI. They still
        // have to be identifiable by VALUE, so they carry a lower-case marker of their own, asserted with the
        // other one below. Refusing the malformed ones is asserted by ContentStoreRenderingTests; this case is
        // about what survives into the environment, so it supplies values the validators accept.
        String lowerCaseUnique = "withdrawn0ba9c17d";
        distinctive.put("OFBIZ_S3_BUCKET", lowerCaseUnique + "-bucket");
        distinctive.put("OFBIZ_S3_REGION", lowerCaseUnique + "-region");
        distinctive.put("OFBIZ_S3_ENDPOINT", "https://" + lowerCaseUnique + ".objects.invalid");
        // The endpoint's host has to be declared a second time. require_object_store_endpoint refuses an
        // endpoint whose host is not allowlisted, and it does so on the resolve path as well as on the render,
        // so that one mis-set variable cannot redirect content - and the credential every object request
        // carries - to a host nobody listed. This start reaches only the resolve, because apply_configuration
        // is one of the stages this fixture replaces with a recorder, which is exactly why the allowlist has
        // to be supplied here: a rule enforced on only one of the two paths would leave the path that runs on
        // every start unguarded.
        distinctive.put("OFBIZ_S3_ENDPOINT_ALLOWLIST", lowerCaseUnique + ".objects.invalid");

        // The remainder have a fixed vocabulary, so only the NAME can be asserted on: _main itself parses the
        // profile and the skip-init flag before any stage runs, and a made-up value would abort the start.
        Map<String, String> withdrawn = new LinkedHashMap<>(distinctive);
        withdrawn.put("OFBIZ_CONTENT_STORE_PROVIDER", "s3");
        withdrawn.put("OFBIZ_S3_PATH_STYLE", "true");
        withdrawn.put("OFBIZ_SCHEMA_INIT", "false");
        withdrawn.put("OFBIZ_DISTRIBUTED_CACHE_CLEAR", "false");
        withdrawn.put("OFBIZ_HOOK_SECRET_ALLOWLIST", "OFBIZ_ADMIN_KEY");
        withdrawn.put("OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS", "false");
        withdrawn.put("OFBIZ_SSL_ACCELERATOR_PORT", "8080");
        withdrawn.put("OFBIZ_DATA_LOAD", "seed");
        withdrawn.put("OFBIZ_SKIP_INIT", "false");
        withdrawn.put("OFBIZ_PROFILE", "dev");

        EntryPointRun serving = runInSandbox(tempDir, sandbox, MAIN_BODY, withdrawn, List.of("/usr/bin/env"));

        assertEquals(0, serving.getExitCode(),
                "the start must succeed so that what it exec'd can be inspected, output was:\n"
                        + serving.getOutput());
        for (String variable : withdrawn.keySet()) {
            assertFalse(serving.getOutput().contains(variable + "="),
                    variable + " must not be present in the environment of the process the entry point "
                            + "starts: it has been rendered into a mode 0600 file, and /proc/<pid>/environ is "
                            + "readable for the whole life of the instance. Environment was:\n"
                            + serving.getOutput());
        }
        // One check for every distinctive value at once, so a value that survived under a DIFFERENT name -
        // copied into an exported variable somewhere in the script - is caught as well.
        assertFalse(serving.getOutput().contains(unique),
                "no value the container was configured with may survive into the environment under any name. "
                        + "Environment was:\n" + serving.getOutput());
        assertFalse(serving.getOutput().contains(lowerCaseUnique),
                "and neither may an object-store setting, whose value has to be spelt the way its own grammar "
                        + "requires. Environment was:\n" + serving.getOutput());
    }

    /*
     * Container state markers: what may be believed, and what may not
     */

    /**
     * A marker file that is merely PRESENT does not suppress the work it names.
     *
     * <p>A marker whose existence was the whole of the question asked could be created by anything running as
     * the {@code ofbiz} user - a volume prepared elsewhere, a hook, a half-copied state directory - and the
     * container would then skip the seed load and, in init mode, exit successfully having created no schema at
     * all. An empty marker is the wrong shape, so it is ignored and the work is done.</p>
     *
     * <p>The empty file written here is exactly what a bare {@code touch} produces, so this also pins the
     * upgrade behaviour: a container restarted on a volume written by an older image reloads rather than
     * trusting state it cannot verify.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void anEmptyMarkerFileCanNoLongerSuppressTheDataLoad(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);
        Files.writeString(sandbox.resolve(DATA_LOADED_MARKER), "", StandardCharsets.UTF_8);

        EntryPointRun run = runInSandbox(tempDir, sandbox, LOAD_DATA_BODY, Map.of("OFBIZ_DATA_LOAD", "seed"));
        assertEquals(0, run.getExitCode(), "the load must succeed, output was:\n" + run.getOutput());
        assertEquals(1, countLoaderInvocations(sandbox, SEED_LOAD),
                "an empty marker must not be believed, so the seed load must run");
        assertTrue(run.getOutput().contains("not a marker this version writes"),
                "the run must say why the marker was ignored, output was:\n" + run.getOutput());
    }

    /**
     * A marker written for one database says nothing about another.
     *
     * <p>Without this, a state volume reattached to a container pointed at a different server reports that
     * server's schema as loaded. The demo image made the same mistake in the opposite direction: it shipped an
     * empty {@code data_loaded} beside its baked embedded database, which also suppressed the load when the
     * image was pointed at an external PostgreSQL server, leaving the container serving an empty schema. Both
     * directions are covered here - a marker written with no managed database is not believed by a run that
     * has one, and a marker written for one host is not believed by a run against another.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void aMarkerWrittenForAnotherDatabaseIsNotBelieved(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");

        Path embedded = prepareSandbox(Files.createTempDirectory(tempDir, "embedded"));
        writeMarker(tempDir, embedded, "CONTAINER_DATA_LOADED", "load=seed", Map.of());
        EntryPointRun rejected = runInSandbox(tempDir, embedded, LOAD_DATA_BODY, loadEnvironment("seed",
                managedDatabaseEnvironment()));
        assertEquals(0, rejected.getExitCode(), "the load must succeed, output was:\n" + rejected.getOutput());
        assertEquals(1, countLoaderInvocations(embedded, SEED_LOAD),
                "a marker written with no managed database must not be believed by a run that has one");
        assertTrue(rejected.getOutput().contains("written for a different deployment"),
                "the run must say why the marker was ignored, output was:\n" + rejected.getOutput());

        Path managed = prepareSandbox(Files.createTempDirectory(tempDir, "managed"));
        writeMarker(tempDir, managed, "CONTAINER_DATA_LOADED", "load=seed", managedDatabaseEnvironment());
        assertEquals(0, runInSandbox(tempDir, managed, LOAD_DATA_BODY,
                loadEnvironment("seed", managedDatabaseEnvironment())).getExitCode(), "the run must succeed");
        assertEquals(0, countLoaderInvocations(managed, SEED_LOAD),
                "the same deployment must still be able to skip a load it really performed");

        Map<String, String> otherHost = loadEnvironment("seed", managedDatabaseEnvironment());
        otherHost.put("OFBIZ_POSTGRES_HOST", "other-db.example.internal");
        assertEquals(0, runInSandbox(tempDir, managed, LOAD_DATA_BODY, otherHost).getExitCode(),
                "the run must succeed");
        assertEquals(1, countLoaderInvocations(managed, SEED_LOAD),
                "a marker written for one host must not be believed by a run against another");
    }

    /**
     * A marker that has been altered, or that was only partly written, is not believed.
     *
     * <p>The body carries its own SHA-256, so a hand edit, a truncated write and a half-copied file are all
     * refused for the same reason and lead to the work being redone. Redoing it is always the safe direction:
     * the entity loader stores rows by primary key, so the cost is one loader run, whereas believing a damaged
     * marker means silently skipping work that never happened.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void aTamperedOrTruncatedMarkerIsNotBelieved(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");

        Path tampered = prepareSandbox(Files.createTempDirectory(tempDir, "tampered"));
        writeMarker(tempDir, tampered, "CONTAINER_DATA_LOADED", "load=demo", Map.of());
        Path marker = tampered.resolve(DATA_LOADED_MARKER);
        List<String> lines = Files.readAllLines(marker, StandardCharsets.UTF_8);
        assertEquals(2, lines.size(), "a marker must be a body and its checksum");
        Files.write(marker, List.of(lines.get(0).replace("load=demo", "load=seed"), lines.get(1)),
                StandardCharsets.UTF_8);
        EntryPointRun edited = runInSandbox(tempDir, tampered, LOAD_DATA_BODY, Map.of("OFBIZ_DATA_LOAD", "seed"));
        assertEquals(0, edited.getExitCode(), "the load must succeed, output was:\n" + edited.getOutput());
        assertEquals(1, countLoaderInvocations(tampered, SEED_LOAD),
                "an edited marker must not be believed, so the load must run");
        assertTrue(edited.getOutput().contains("does not match its own checksum"),
                "the run must say why the marker was ignored, output was:\n" + edited.getOutput());

        Path truncated = prepareSandbox(Files.createTempDirectory(tempDir, "truncated"));
        writeMarker(tempDir, truncated, "CONTAINER_DATA_LOADED", "load=seed", Map.of());
        Path shortened = truncated.resolve(DATA_LOADED_MARKER);
        Files.write(shortened, List.of(Files.readAllLines(shortened, StandardCharsets.UTF_8).get(0)),
                StandardCharsets.UTF_8);
        EntryPointRun cut = runInSandbox(tempDir, truncated, LOAD_DATA_BODY, Map.of("OFBIZ_DATA_LOAD", "seed"));
        assertEquals(0, cut.getExitCode(), "the load must succeed, output was:\n" + cut.getOutput());
        assertEquals(1, countLoaderInvocations(truncated, SEED_LOAD),
                "a marker missing its checksum line must not be believed");
    }

    /**
     * Work that was started and did not finish is performed again rather than reported as done.
     *
     * <p>A container killed part way through a long seed load is the ordinary case, not the exotic one. The
     * marker is removed and an in-progress sibling created before the work starts, so the state left behind
     * says "this was attempted and did not finish" instead of continuing to say "this is done" - and once the
     * work completes, the sibling is gone and the marker is believed again.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void aLoadThatDidNotFinishIsPerformedAgain(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);
        writeMarker(tempDir, sandbox, "CONTAINER_DATA_LOADED", "load=seed", Map.of());
        Files.writeString(sandbox.resolve(DATA_LOADED_MARKER + ".inprogress"), "", StandardCharsets.UTF_8);

        EntryPointRun interrupted = runInSandbox(tempDir, sandbox, LOAD_DATA_BODY, Map.of("OFBIZ_DATA_LOAD", "seed"));
        assertEquals(0, interrupted.getExitCode(), "the load must succeed, output was:\n" + interrupted.getOutput());
        assertEquals(1, countLoaderInvocations(sandbox, SEED_LOAD),
                "an unfinished load must be performed again");
        assertTrue(interrupted.getOutput().contains("did not finish"),
                "the run must say why the marker was ignored, output was:\n" + interrupted.getOutput());
        assertFalse(Files.exists(sandbox.resolve(DATA_LOADED_MARKER + ".inprogress")),
                "a completed load must clear the in-progress sibling it created");

        Files.delete(sandbox.resolve(LOADER_INVOCATIONS));
        assertEquals(0, runInSandbox(tempDir, sandbox, LOAD_DATA_BODY, Map.of("OFBIZ_DATA_LOAD", "seed"))
                .getExitCode(), "the next start must succeed");
        assertEquals(0, countLoaderInvocations(sandbox, SEED_LOAD),
                "the marker the completed load wrote must be believed on the next start");
    }

    /**
     * Schema-init mode does not consult a marker at all, however well formed it is.
     *
     * <p>The Entity Engine has no standalone DDL command - the schema is applied when the data loader creates a
     * delegator - so this load IS the schema application. A marker that skipped it would produce a job that
     * exits 0 having created nothing, and an orchestrator reading that status would start the fleet against an
     * empty database. The marker written here is entirely valid, which is the point: init mode ignores it.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void schemaInitModeIgnoresEvenAValidMarkerBecauseTheLoadIsWhatCreatesTheSchema(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);
        writeMarker(tempDir, sandbox, "CONTAINER_DATA_LOADED", "load=none", Map.of("OFBIZ_DATA_LOAD", "none"));

        EntryPointRun serving = runInSandbox(tempDir, sandbox, LOAD_DATA_BODY, Map.of("OFBIZ_DATA_LOAD", "none"));
        assertEquals(0, serving.getExitCode(), "the run must succeed, output was:\n" + serving.getOutput());
        assertEquals(0, countLoaderInvocations(sandbox, SCHEMA_ONLY_LOAD),
                "a serving start must honour a marker it wrote itself");

        EntryPointRun initialising = runInSandbox(tempDir, sandbox, LOAD_DATA_BODY,
                Map.of("OFBIZ_DATA_LOAD", "none", "OFBIZ_SCHEMA_INIT", "true"));
        assertEquals(0, initialising.getExitCode(),
                "the run must succeed, output was:\n" + initialising.getOutput());
        assertEquals(1, countLoaderInvocations(sandbox, SCHEMA_ONLY_LOAD),
                "init mode must apply the schema regardless of any marker");
        assertTrue(initialising.getOutput().contains("regardless of any container state marker"),
                "the run must say that the marker is not consulted, output was:\n" + initialising.getOutput());
    }

    /**
     * A marker written for one data-load selection does not cover a different one.
     *
     * <p>Restarting a container that loaded seed data with {@code OFBIZ_DATA_LOAD=demo} must not leave the
     * database as it was: a marker whose existence alone answered the question would be answering one it had
     * never been asked. The selection is part of the marker, so the new request is honoured.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void aChangedDataLoadSelectionIsNotCoveredByThePreviousMarker(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);
        writeMarker(tempDir, sandbox, "CONTAINER_DATA_LOADED", "load=seed", Map.of("OFBIZ_DATA_LOAD", "seed"));

        EntryPointRun run = runInSandbox(tempDir, sandbox, LOAD_DATA_BODY, Map.of("OFBIZ_DATA_LOAD", "demo"));
        assertEquals(0, run.getExitCode(), "the load must succeed, output was:\n" + run.getOutput());
        assertEquals(1, countLoaderInvocations(sandbox, DEMO_LOAD),
                "a request for demo data must not be answered by the marker of a seed load");
    }

    /**
     * A rotated administrator password reaches the database instead of being suppressed by the marker of the
     * previous one, and a demo load does not discard a supplied password.
     *
     * <p>Two hazards with the same cause. A marker whose mere existence skipped the load would let a rotated
     * password never take effect, with nothing saying it had been ignored; and a demo branch that touched that
     * marker unconditionally would leave the account on the password the demo data ships with whenever demo
     * data was loaded. The marker is bound to the credential the load would apply, and the demo branch records
     * the demo data's own account, which can never equal the digest of a real credential.</p>
     *
     * <p>No password is asserted on directly - the loader stub records only that it was invoked - because this
     * suite must not print one. What the load produced is covered by the tests of the hash itself.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void aRotatedAdminPasswordIsAppliedInsteadOfBeingSuppressedByItsMarker(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareAdminSandbox(tempDir);

        Map<String, String> first = adminEnvironment(ADMIN_PASSWORD);
        assertEquals(0, runInSandbox(tempDir, sandbox, LOAD_ADMIN_BODY, first).getExitCode(),
                "the first admin load must succeed");
        assertEquals(1, countAdminLoads(sandbox), "the admin user must be loaded on the first start");

        assertEquals(0, runInSandbox(tempDir, sandbox, LOAD_ADMIN_BODY, first).getExitCode(),
                "a restart with the same credential must succeed");
        assertEquals(1, countAdminLoads(sandbox),
                "an unchanged credential must be recognised, so the load must not run again");

        Map<String, String> rotated = adminEnvironment("Rotated-Admin-Password-8kL2vt");
        assertEquals(0, runInSandbox(tempDir, sandbox, LOAD_ADMIN_BODY, rotated).getExitCode(),
                "a rotated credential must succeed");
        assertEquals(2, countAdminLoads(sandbox), "a rotated password must actually reach the database");

        Map<String, String> renamed = adminEnvironment("Rotated-Admin-Password-8kL2vt");
        renamed.put("OFBIZ_ADMIN_USER", "operator");
        assertEquals(0, runInSandbox(tempDir, sandbox, LOAD_ADMIN_BODY, renamed).getExitCode(),
                "a renamed administrator must succeed");
        assertEquals(3, countAdminLoads(sandbox), "a renamed administrator must also be loaded");

        Path demo = prepareAdminSandbox(Files.createTempDirectory(tempDir, "demo"));
        Map<String, String> demoLoad = adminEnvironment(ADMIN_PASSWORD);
        demoLoad.put("OFBIZ_DATA_LOAD", "demo");
        EntryPointRun demoRun = runInSandbox(tempDir, demo, LOAD_DATA_THEN_ADMIN_BODY, demoLoad);
        assertEquals(0, demoRun.getExitCode(), "the demo load must succeed, output was:\n" + demoRun.getOutput());
        assertEquals(1, countLoaderInvocations(demo, DEMO_LOAD), "the demo data must be loaded");
        assertEquals(1, countAdminLoads(demo),
                "a supplied admin password must still be applied after a demo load");
    }

    /**
     * An init run that did not apply a schema refuses to report success.
     *
     * <p>The exit status of the init job is the only thing an orchestrator has to decide whether the fleet may
     * start, so it must mean "the schema has been applied to this database" and nothing weaker. Two independent
     * facts are required: the schema-applying loader invocation really ran in this process, and the load
     * reached its end. Neither can be supplied from outside the run - the first is set only on the lines that
     * execute the loader, and the second is the completed marker - so a marker planted on a mounted volume
     * cannot buy a zero exit status from a job that created nothing.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void anInitRunThatAppliedNoSchemaRefusesToReportSuccess(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");

        Path ineffective = prepareSandbox(Files.createTempDirectory(tempDir, "ineffective"));
        // A marker planted exactly as an attacker or a stale volume would, for the load this run requests.
        writeMarker(tempDir, ineffective, "CONTAINER_DATA_LOADED", "load=seed", Map.of("OFBIZ_DATA_LOAD", "seed"));
        EntryPointRun nothingRan = runInSandbox(tempDir, ineffective, MAIN_BODY_WITH_INEFFECTIVE_LOAD,
                Map.of("OFBIZ_SCHEMA_INIT", "true", "OFBIZ_DATA_LOAD", "seed"),
                List.of("/bin/echo", SERVING_MARKER));
        assertNotEquals(0, nothingRan.getExitCode(),
                "a job that created no schema must not exit successfully, output was:\n" + nothingRan.getOutput());
        assertFalse(nothingRan.getOutput().contains("schema initialisation complete"),
                "it must not claim completion, output was:\n" + nothingRan.getOutput());
        assertFalse(nothingRan.getOutput().contains(SERVING_MARKER),
                "and it must still never serve, output was:\n" + nothingRan.getOutput());

        Path interrupted = prepareSandbox(Files.createTempDirectory(tempDir, "interrupted"));
        EntryPointRun cutShort = runInSandbox(tempDir, interrupted, MAIN_BODY_WITH_INTERRUPTED_LOAD,
                Map.of("OFBIZ_SCHEMA_INIT", "true", "OFBIZ_DATA_LOAD", "seed"),
                List.of("/bin/echo", SERVING_MARKER));
        assertNotEquals(0, cutShort.getExitCode(),
                "a load that did not reach its end must not exit successfully, output was:\n"
                        + cutShort.getOutput());
        assertFalse(cutShort.getOutput().contains("schema initialisation complete"),
                "it must not claim completion, output was:\n" + cutShort.getOutput());
    }

    /**
     * The build-time state writer records the load the image build performed, and refuses to be used for
     * anything else.
     *
     * <p>The demo image loads the whole demo data set during {@code docker build} and has to say so, or every
     * container from it would load it again. It cannot say so by running the normal start-up: that resolves the
     * deployment secrets and, in the dev profile, GENERATES the ones it was not given, which would bake key
     * material into a public image layer. So this entry point writes the two markers and exits, resolving no
     * profile and no secret - asserted here by the absence of the admin marker salt, which the real credential
     * digest would have created - and refuses a managed database, which at build time there is none of.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void theBuildTimeStateWriterRecordsTheEmbeddedLoadAndNothingElse(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        EntryPointRun written = runWithoutProfileDefault(tempDir, sandbox, "_main \"$@\"\n", Map.of(),
                List.of("--write-initial-container-state"));
        assertEquals(0, written.getExitCode(),
                "the build-time writer must succeed without a profile, output was:\n" + written.getOutput());
        assertTrue(Files.exists(sandbox.resolve(DATA_LOADED_MARKER)), "it must record the data load");
        assertTrue(Files.exists(sandbox.resolve(ADMIN_LOADED_MARKER)), "it must record the demo administrator");
        assertFalse(Files.exists(sandbox.resolve("state/admin_marker_salt")),
                "it must not create the credential salt, because it resolves no credential");
        assertFalse(Files.exists(sandbox.resolve("state/generated_secrets")),
                "and it must generate no secret into the image");

        EntryPointRun skipped = runInSandbox(tempDir, sandbox, LOAD_DATA_BODY, Map.of("OFBIZ_DATA_LOAD", "demo"));
        assertEquals(0, skipped.getExitCode(), "the container start must succeed, output was:\n"
                + skipped.getOutput());
        assertEquals(0, countLoaderInvocations(sandbox, DEMO_LOAD),
                "a container on the image's own embedded database must not load the demo data again");

        EntryPointRun refused = runWithoutProfileDefault(tempDir, sandbox, "_main \"$@\"\n",
                Map.of("OFBIZ_POSTGRES_HOST", DATABASE_HOST), List.of("--write-initial-container-state"));
        assertNotEquals(0, refused.getExitCode(),
                "it must refuse a managed database, output was:\n" + refused.getOutput());
        assertTrue(refused.getOutput().contains("OFBIZ_POSTGRES_HOST"),
                "the refusal must name the variable, output was:\n" + refused.getOutput());
    }

    /*
     * Database least privilege: which identity each mode authenticates as
     */

    /**
     * An init run authenticates as the privileged roles and a serving run as the roles that need no schema
     * privileges at all.
     *
     * <p>Turning off {@code check-on-start} and {@code add-missing-on-start} stops a serving instance from
     * ISSUING startup DDL, which is a control on behaviour, not on privilege: if every instance connects as the
     * role that owns the schema, one injection defect or one leaked connection string is still enough to drop a
     * table. The privilege therefore lives only with the one-shot job that needs it - short-lived, listening on
     * nothing - and is absent from the long-lived processes that face the network. This asserts the mechanism
     * that makes that true: the same three {@code jdbc-username} attributes carry different roles in the two
     * modes, and each managed datasource carries exactly the role resolved for its own entity group.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run or the render could not be parsed, either of which fails
     */
    @Test
    public void anInitRunAuthenticatesAsThePrivilegedRoleWhileAServingRunDoesNot(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");

        Path initialising = prepareSandbox(Files.createTempDirectory(tempDir, "init-identity"));
        Map<String, String> initEnvironment = managedDatabaseEnvironment();
        initEnvironment.putAll(initIdentityEnvironment());
        initEnvironment.put("OFBIZ_SCHEMA_INIT", "true");
        EntryPointRun initRun = runInSandbox(tempDir, initialising, CONFIGURE_DATABASE_BODY, initEnvironment);
        assertEquals(0, initRun.getExitCode(), "the init render must succeed, output was:\n" + initRun.getOutput());
        assertEquals(Map.of("localpostgres", OFBIZ_INIT_USER, "localpostgresolap", OLAP_INIT_USER,
                "localpostgrestenant", TENANT_INIT_USER), renderedManagedUsers(initialising),
                "an init run must authenticate as the roles that may alter the schema");
        assertTrue(initRun.getOutput().contains("schema-initialisation database identity"),
                "the run must say which identity it used, output was:\n" + initRun.getOutput());

        // Exactly the same environment, minus the one flag: the fleet's own start, from the same env file.
        Path serving = prepareSandbox(Files.createTempDirectory(tempDir, "serving-identity"));
        Map<String, String> servingEnvironment = managedDatabaseEnvironment();
        servingEnvironment.putAll(initIdentityEnvironment());
        EntryPointRun servingRun = runInSandbox(tempDir, serving, CONFIGURE_DATABASE_BODY, servingEnvironment);
        assertEquals(0, servingRun.getExitCode(),
                "the serving render must succeed in dev, output was:\n" + servingRun.getOutput());
        assertEquals(Map.of("localpostgres", OFBIZ_USER, "localpostgresolap", OLAP_USER,
                "localpostgrestenant", TENANT_USER), renderedManagedUsers(serving),
                "a serving instance must never be handed the identity that can alter the schema");
        assertTrue(servingRun.getOutput().contains("not performing schema initialisation"),
                "the run must warn that a DDL credential was supplied to it, output was:\n"
                        + servingRun.getOutput());

        // And with no init identity at all, both modes fall back to the serving roles, so an unconfigured
        // local container still creates its own schema.
        Path fallback = prepareSandbox(Files.createTempDirectory(tempDir, "fallback-identity"));
        Map<String, String> fallbackEnvironment = managedDatabaseEnvironment();
        fallbackEnvironment.put("OFBIZ_SCHEMA_INIT", "true");
        EntryPointRun fallbackRun = runInSandbox(tempDir, fallback, CONFIGURE_DATABASE_BODY, fallbackEnvironment);
        assertEquals(0, fallbackRun.getExitCode(),
                "a dev init run without a separate identity must still work, output was:\n"
                        + fallbackRun.getOutput());
        assertEquals(Map.of("localpostgres", OFBIZ_USER, "localpostgresolap", OLAP_USER,
                "localpostgrestenant", TENANT_USER), renderedManagedUsers(fallback),
                "the fallback must be the serving roles");
        assertTrue(fallbackRun.getOutput().contains("hold DDL privileges"),
                "the fallback must say what it costs, output was:\n" + fallbackRun.getOutput());
    }

    /**
     * The deployed profile requires the separate identity for an init run and refuses it on a serving one.
     *
     * <p>Required, because a prod init run that created the schema as the serving role would show that role
     * holds DDL, which is the whole thing being prevented. Refused on a serving instance, because a credential
     * that can alter the schema, present in a serving instance's environment, is readable through the
     * orchestrator's configuration and through {@code /proc} - so it is a privilege the fleet holds even though
     * the entry point removes the variable before starting OFBiz. The two rules together force the deployment
     * to keep the initialisation credential in a file only the init job is given.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void theDeployedProfileSeparatesTheInitialisationCredentialFromTheFleet(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");

        Path missing = prepareSandbox(Files.createTempDirectory(tempDir, "prod-init-missing"));
        Map<String, String> withoutIdentity = managedDatabaseEnvironment();
        withoutIdentity.put("OFBIZ_PROFILE", "prod");
        withoutIdentity.put("OFBIZ_SCHEMA_INIT", "true");
        EntryPointRun refusedInit = runInSandbox(tempDir, missing, CONFIGURE_DATABASE_BODY, withoutIdentity);
        assertNotEquals(0, refusedInit.getExitCode(),
                "a prod init run without the separate identity must be refused, output was:\n"
                        + refusedInit.getOutput());
        assertTrue(refusedInit.getOutput().contains("OFBIZ_POSTGRES_OFBIZ_INIT_USER"),
                "the refusal must name the variables to supply, output was:\n" + refusedInit.getOutput());
        assertFalse(Files.exists(missing.resolve(RENDERED_OVERRIDE)),
                "nothing may be rendered for a run that would create the schema as the serving role");

        Path present = prepareSandbox(Files.createTempDirectory(tempDir, "prod-serving-holds-ddl"));
        Map<String, String> servingWithIdentity = managedDatabaseEnvironment();
        servingWithIdentity.putAll(initIdentityEnvironment());
        servingWithIdentity.put("OFBIZ_PROFILE", "prod");
        EntryPointRun refusedServing = runInSandbox(tempDir, present, CONFIGURE_DATABASE_BODY, servingWithIdentity);
        assertNotEquals(0, refusedServing.getExitCode(),
                "a prod serving instance holding the initialisation credential must be refused, output was:\n"
                        + refusedServing.getOutput());
        assertTrue(refusedServing.getOutput().contains("OFBIZ_SCHEMA_INIT"),
                "the refusal must explain the combination, output was:\n" + refusedServing.getOutput());
        assertFalse(Files.exists(present.resolve(RENDERED_OVERRIDE)),
                "and it must render nothing");

        Path accepted = prepareSandbox(Files.createTempDirectory(tempDir, "prod-init-supplied"));
        Map<String, String> production = managedDatabaseEnvironment();
        production.putAll(initIdentityEnvironment());
        production.put("OFBIZ_PROFILE", "prod");
        production.put("OFBIZ_SCHEMA_INIT", "true");
        EntryPointRun productionInit = runInSandbox(tempDir, accepted, CONFIGURE_DATABASE_BODY, production);
        assertEquals(0, productionInit.getExitCode(),
                "a prod init run with the separate identity must succeed, output was:\n"
                        + productionInit.getOutput());
        assertEquals(Map.of("localpostgres", OFBIZ_INIT_USER, "localpostgresolap", OLAP_INIT_USER,
                "localpostgrestenant", TENANT_INIT_USER), renderedManagedUsers(accepted),
                "and it must authenticate as the privileged roles");

        Path fleet = prepareSandbox(Files.createTempDirectory(tempDir, "prod-fleet"));
        Map<String, String> fleetEnvironment = managedDatabaseEnvironment();
        fleetEnvironment.put("OFBIZ_PROFILE", "prod");
        EntryPointRun fleetRun = runInSandbox(tempDir, fleet, CONFIGURE_DATABASE_BODY, fleetEnvironment);
        assertEquals(0, fleetRun.getExitCode(),
                "the fleet must start with the serving credential alone, output was:\n" + fleetRun.getOutput());
        assertEquals(Map.of("localpostgres", OFBIZ_USER, "localpostgresolap", OLAP_USER,
                "localpostgrestenant", TENANT_USER), renderedManagedUsers(fleet),
                "and it must authenticate as the roles that need no schema privileges");
    }

    /**
     * An initialisation identity that is not actually separate from the serving one is refused, and so is a
     * partial one.
     *
     * <p>Three ways the separation can be nominal rather than real. A shared role name gives some serving role
     * DDL over some group's schema. A shared password is worse than it looks: distinct names authenticated by
     * the same secret are not separated at all, because whoever learns the serving password can connect as the
     * privileged role - which is exactly the escalation the review described. And a partial set would leave one
     * of the three groups' schemas created by, and therefore owned by, its serving role, since in init mode all
     * three managed datasources issue DDL.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void anInitialisationIdentityThatIsNotReallySeparateIsRefused(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");

        // A role name reused from another group, which the per-group check alone would not catch.
        Map<String, String> sharedRole = initIdentityEnvironment();
        sharedRole.put("OFBIZ_POSTGRES_OLAP_INIT_USER", OFBIZ_USER);
        assertInitIdentityRefused(tempDir, "shared-role", sharedRole, "same database role");

        Map<String, String> sharedPassword = initIdentityEnvironment();
        sharedPassword.put("OFBIZ_POSTGRES_TENANT_INIT_PASSWORD", OFBIZ_PASSWORD);
        assertInitIdentityRefused(tempDir, "shared-password", sharedPassword, "same value as");

        for (String omitted : initIdentityEnvironment().keySet()) {
            Map<String, String> partial = initIdentityEnvironment();
            partial.remove(omitted);
            assertInitIdentityRefused(tempDir, "partial", partial, "incomplete");
        }
    }

    /**
     * A rendered configuration that does not carry the resolved role is refused, rather than being started
     * with whatever identity the template happened to name.
     *
     * <p>The identity reaches the file through the three {@code @*_USERNAME@} placeholders, so a template that
     * predates one of them - an operator-supplied copy, or one restored from an older image - would leave a
     * serving instance connecting as the schema owner, or an init run connecting as a role with no DDL grant.
     * Neither is visible at a glance and neither fails at start up: the first is a privilege that should not
     * exist, the second an error part way through creating the schema. The check is made on the rendered
     * artefact for the same reason the TLS and DDL checks are, and the artefact is deleted when it fails so a
     * later start cannot be served by a configuration that was refused.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void aRenderThatDoesNotCarryTheResolvedRoleIsRefused(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");

        Path correct = renderedFixture(tempDir);
        EntryPointRun accepted = runInSandbox(tempDir, correct, renderedIdentityBody("serving"),
                managedDatabaseEnvironment());
        assertEquals(0, accepted.getExitCode(),
                "a correct render must be accepted, output was:\n" + accepted.getOutput());
        assertTrue(accepted.getOutput().contains(IDENTITY_VERIFIED),
                "the validator must run to completion, output was:\n" + accepted.getOutput());

        Path hardcoded = renderedFixture(tempDir);
        tamperRenderedConfiguration(hardcoded, "jdbc-username=\"" + OLAP_USER + "\"",
                "jdbc-username=\"" + OFBIZ_USER + "\"");
        EntryPointRun wrongRole = runInSandbox(tempDir, hardcoded, renderedIdentityBody("serving"),
                managedDatabaseEnvironment());
        assertNotEquals(0, wrongRole.getExitCode(),
                "a datasource carrying another group's role must be refused, output was:\n"
                        + wrongRole.getOutput());
        assertTrue(wrongRole.getOutput().contains("localpostgresolap"),
                "the failure must name the datasource, output was:\n" + wrongRole.getOutput());
        assertFalse(Files.exists(hardcoded.resolve(RENDERED_OVERRIDE)),
                "a refused render must be deleted so a later start cannot be served by it");

        Path doubled = renderedFixture(tempDir);
        tamperRenderedConfiguration(doubled, "jdbc-username=\"" + TENANT_USER + "\"",
                "jdbc-username=\"" + TENANT_USER + "\" jdbc-username=\"" + OFBIZ_USER + "\"");
        EntryPointRun twoRoles = runInSandbox(tempDir, doubled, renderedIdentityBody("serving"),
                managedDatabaseEnvironment());
        assertNotEquals(0, twoRoles.getExitCode(),
                "a datasource declaring the attribute twice must be refused, output was:\n"
                        + twoRoles.getOutput());

        Path unsubstituted = renderedFixture(tempDir);
        tamperRenderedConfiguration(unsubstituted, "<datasource name=\"localh2\"",
                "<datasource name=\"localh2\" schema-name=\"@OFBIZ_USERNAME@\"");
        EntryPointRun leftover = runInSandbox(tempDir, unsubstituted, renderedIdentityBody("serving"),
                managedDatabaseEnvironment());
        assertNotEquals(0, leftover.getExitCode(),
                "a leftover identity placeholder must be refused, output was:\n" + leftover.getOutput());
        assertTrue(leftover.getOutput().contains("unsubstituted database identity placeholder"),
                "the failure must say what it found, output was:\n" + leftover.getOutput());
    }

    /*
     * Harness
     */

    /** Records what the intercepted data loader was asked to do, one invocation per line. */
    private static final String LOAD_DATA_BODY =
            "run_init_hooks() { :; }\n"
            + "/ofbiz/bin/ofbiz() { printf '%s\\n' \"$*\" >>\"$PWD/" + LOADER_INVOCATIONS + "\"; }\n"
            + "resolve_skip_init\n"
            + "resolve_entity_engine_flags\n"
            + "load_data\n";

    /** Drives the administrator load alone, with the loader intercepted so nothing is executed. */
    private static final String LOAD_ADMIN_BODY =
            "run_init_hooks() { :; }\n"
            + "/ofbiz/bin/ofbiz() { printf '%s\\n' \"$*\" >>\"$PWD/" + LOADER_INVOCATIONS + "\"; }\n"
            + "load_admin_user\n";

    /** Drives the data load and then the administrator load, in the order {@code _main} runs them. */
    private static final String LOAD_DATA_THEN_ADMIN_BODY =
            "run_init_hooks() { :; }\n"
            + "/ofbiz/bin/ofbiz() { printf '%s\\n' \"$*\" >>\"$PWD/" + LOADER_INVOCATIONS + "\"; }\n"
            + "resolve_skip_init\n"
            + "resolve_entity_engine_flags\n"
            + "load_data\n"
            + "load_admin_user\n";

    /** Printed by the command a serving start is expected to exec, and by nothing else. */
    private static final String SERVING_MARKER = "REACHED_THE_SERVING_COMMAND";

    /**
     * Resolves the container environment through the real shell and reports what the admin credential became.
     *
     * <p>The password itself is never printed - its LENGTH is, which is enough to tell the supplied value from
     * the demo default, and whether it IS the demo default is reported as a boolean. Both are safe: the demo
     * default is published in this repository already, and a length discloses nothing about a private value.</p>
     */
    private static final String SETUP_ENV_BODY =
            "ofbiz_setup_env\n"
            + "printf 'ADMIN_PASSWORD_LENGTH=%s\\n' \"${#OFBIZ_ADMIN_PASSWORD}\"\n"
            + "if [ \"$OFBIZ_ADMIN_PASSWORD\" = 'ofbiz' ]; then\n"
            + "  printf 'ADMIN_PASSWORD_IS_DEMO_DEFAULT=true\\n'\n"
            + "else\n"
            + "  printf 'ADMIN_PASSWORD_IS_DEMO_DEFAULT=false\\n'\n"
            + "fi\n"
            + "printf '" + COMPLETED + "\\n'\n";

    /**
     * Runs the sandbox's {@code hook.sh} through the real {@code run_init_hooks}, then reports what survived:
     * the admin password's length, which reports whether the scrubbed variables were restored, and whether shell tracing
     * is still in force, which reports whether a hook's own {@code set -x} did not escape it.
     */
    private static final String HOOK_BODY =
            "ofbiz_setup_env\n"
            + "run_init_hooks test-stage \"$PWD/hook.sh\"\n"
            + "printf 'AFTER_HOOK_ADMIN_PASSWORD_LENGTH=%s\\n' \"${#OFBIZ_ADMIN_PASSWORD}\"\n"
            + "case \"$-\" in\n"
            + "*x*) printf 'AFTER_HOOK_TRACING=on\\n' ;;\n"
            + "*) printf 'AFTER_HOOK_TRACING=off\\n' ;;\n"
            + "esac\n";

    /**
     * Replaces every initialisation stage with a recorder so that {@code _main}'s own control flow - the
     * one-shot exit versus {@code exec "$@"} - is what the run demonstrates.
     */
    private static final String MAIN_STAGE_RECORDERS =
            "ofbiz_setup_env() { printf 'STAGE ofbiz_setup_env\\n'; }\n"
            + "create_ofbiz_runtime_directories() { printf 'STAGE create_ofbiz_runtime_directories\\n'; }\n"
            + "configure_database() { printf 'STAGE configure_database\\n'; }\n"
            + "apply_configuration() { printf 'STAGE apply_configuration\\n'; }\n"
            + "load_admin_user() { printf 'STAGE load_admin_user\\n'; }\n";

    /**
     * A recorder for the one-shot schema initialisation that reports what a successful one reports.
     *
     * <p>Init mode takes neither {@code load_data} nor {@code load_admin_user}: it applies the entity-model
     * DDL in a child of its own and then verifies the result against the database with the startup DDL turned
     * off, so the stage that has to be replaced for these cases is {@code initialise_schema}. It publishes the
     * three facts {@code _main} verifies before an init run may exit successfully - the in-process flag that
     * says the schema-applying loader invocation happened, the second flag the exit epilogue reads
     * independently of the evidence gate, and the atomically written receipt carrying THIS run's token, which
     * is what stops a receipt left on a reused state volume from answering for a later run. The real receipt
     * writer is called rather than imitated, so the record's format cannot drift away from the reader's.</p>
     */
    private static final String SUCCESSFUL_SCHEMA_INIT =
            "initialise_schema() { printf 'STAGE initialise_schema\\n'\n"
            + "  mkdir --parents \"$CONTAINER_STATE_DIR\"\n"
            + "  SCHEMA_APPLYING_LOAD_RAN=\"true\"\n"
            + "  SCHEMA_INIT_APPLIED=\"true\"\n"
            + "  record_schema_init_receipt 'version=1'\n"
            + "}\n";

    /**
     * Stage recorders in which the data load reports what a real successful load reports - the in-process flag
     * that says a schema-applying loader invocation happened, and the completed container state marker that
     * says the load reached its end - and in which the one-shot schema initialisation reports what a
     * successful one reports. Both are present because {@code _main} chooses between them from
     * {@code OFBIZ_SCHEMA_INIT}, and the same body has to serve a normal start and an init run.
     */
    private static final String MAIN_BODY =
            MAIN_STAGE_RECORDERS
            + "load_data() { printf 'STAGE load_data\\n'\n"
            + "  SCHEMA_APPLYING_LOAD_RAN=\"true\"\n"
            + "  complete_container_marker \"$CONTAINER_DATA_LOADED\" \"load=$OFBIZ_DATA_LOAD\"\n"
            + "}\n"
            + SUCCESSFUL_SCHEMA_INIT
            + "_main \"$@\"\n";


    /**
     * Stage recorders in which neither the data load nor the schema initialisation does anything at all, as a
     * run whose loader child failed would leave them.
     */
    private static final String MAIN_BODY_WITH_INEFFECTIVE_LOAD =
            MAIN_STAGE_RECORDERS
            + "load_data() { printf 'STAGE load_data\\n'; }\n"
            + "initialise_schema() { printf 'STAGE initialise_schema\\n'; }\n"
            + "_main \"$@\"\n";

    /**
     * Stage recorders in which the loader ran but the work did not reach its end, so the flags are set and no
     * record was written. A container killed part way through a long load leaves exactly this state: the
     * receipt is written atomically and only after the verification pass came back clean, so an interruption
     * anywhere before that leaves the flags set and no record at all.
     */
    private static final String MAIN_BODY_WITH_INTERRUPTED_LOAD =
            MAIN_STAGE_RECORDERS
            + "load_data() { printf 'STAGE load_data\\n'\n"
            + "  SCHEMA_APPLYING_LOAD_RAN=\"true\"\n"
            + "  begin_container_marker \"$CONTAINER_DATA_LOADED\"\n"
            + "}\n"
            + "initialise_schema() { printf 'STAGE initialise_schema\\n'\n"
            + "  SCHEMA_APPLYING_LOAD_RAN=\"true\"\n"
            + "  SCHEMA_INIT_APPLIED=\"true\"\n"
            + "}\n"
            + "_main \"$@\"\n";

    /** Runs all four post-render validators in the order {@code render_database_configuration} runs them. */
    private static String allValidatorsBody(String schemaMode, String cacheMode) {
        return "require_rendered_transport_security " + RENDERED_OVERRIDE + " " + SSL_MODE + "\n"
                + "require_rendered_schema_ddl_mode " + RENDERED_OVERRIDE + " " + schemaMode + "\n"
                + "require_rendered_cache_clear_mode " + RENDERED_OVERRIDE + " " + cacheMode + "\n"
                + "require_rendered_test_delegator_isolation " + RENDERED_OVERRIDE + "\n";
    }

    /** A sandbox that already holds a correct render, for the validators to be exercised against. */
    private static Path renderedFixture(Path tempDir) throws Exception {
        Path sandbox = prepareSandbox(Files.createTempDirectory(tempDir, "render"));
        EntryPointRun run = runInSandbox(tempDir, sandbox, CONFIGURE_DATABASE_BODY, managedDatabaseEnvironment());
        assertEquals(0, run.getExitCode(), "the fixture render must succeed, output was:\n" + run.getOutput());
        return sandbox;
    }

    /** Rewrites the rendered configuration, so a validator can be exercised on an artefact it must refuse. */
    private static void tamperRenderedConfiguration(Path sandbox, String find, String replacement)
            throws IOException {
        rewriteFirst(sandbox.resolve(RENDERED_OVERRIDE), find, replacement);
    }

    /**
     * Rewrites the first occurrence of {@code find} in a file, failing if it is absent so that an edit which no
     * longer applies cannot quietly turn a negative case into a vacuous pass.
     */
    private static void rewriteFirst(Path file, String find, String replacement) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(text.contains(find),
                file.getFileName() + " no longer contains [" + find + "], so this case tests nothing");
        Files.writeString(file, text.replaceFirst(Pattern.quote(find), Matcher.quoteReplacement(replacement)),
                StandardCharsets.UTF_8);
    }

    /** How many times a literal occurs in a text, counted without overlap. */
    private static int occurrences(String text, String literal) {
        int found = 0;
        for (int at = text.indexOf(literal); at >= 0; at = text.indexOf(literal, at + literal.length())) {
            found++;
        }
        return found;
    }

    /**
     * The attributes of one named {@code <datasource>} element, as one string. A start tag spans many lines in
     * these files, so an attribute cannot be attributed to a datasource line by line - which is the whole
     * reason a per-datasource check is possible at all only if the element is isolated first.
     * @param text the rendered configuration
     * @param name the datasource name, matched with both quotes so {@code localpostgres} does not also match
     *        {@code localpostgresolap}
     * @return everything from the start tag's opening to its closing angle bracket
     */
    private static String managedDatasourceAttributes(String text, String name) {
        int start = text.indexOf("<datasource name=\"" + name + "\"");
        assertTrue(start >= 0, "the rendered configuration declares no datasource named " + name);
        int end = text.indexOf('>', start);
        assertTrue(end > start, "the start tag of the datasource " + name + " is not terminated");
        return text.substring(start, end);
    }

    /** How many recorded loader invocations were exactly {@code expected}. */
    private static int countLoaderInvocations(Path sandbox, String expected) throws IOException {
        Path recorded = sandbox.resolve(LOADER_INVOCATIONS);
        if (!Files.exists(recorded)) {
            return 0;
        }
        int matches = 0;
        for (String line : Files.readAllLines(recorded, StandardCharsets.UTF_8)) {
            if (expected.equals(line.strip())) {
                matches++;
            }
        }
        return matches;
    }

    /**
     * How many times the administrator data file was loaded. Matched on the prefix rather than exactly,
     * because the file is a temporary path chosen by the run.
     */
    private static int countAdminLoads(Path sandbox) throws IOException {
        Path recorded = sandbox.resolve(LOADER_INVOCATIONS);
        if (!Files.exists(recorded)) {
            return 0;
        }
        int matches = 0;
        for (String line : Files.readAllLines(recorded, StandardCharsets.UTF_8)) {
            if (line.strip().startsWith("--load-data file=")) {
                matches++;
            }
        }
        return matches;
    }

    /**
     * Writes a container state marker through the real shell, so the fixture is byte for byte what the script
     * would have written for that deployment - which is the only kind of fixture the integrity check can be
     * meaningfully exercised against.
     *
     * @param workDir where the generated library and driver scripts are written
     * @param sandbox the directory the script treats as the OFBiz home
     * @param markerVariable the name of the entry point variable naming the marker to write
     * @param payload the marker payload, as the calling function would compute it
     * @param environment the {@code OFBIZ_*} variables that make up the deployment the marker belongs to
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    private static void writeMarker(Path workDir, Path sandbox, String markerVariable, String payload,
            Map<String, String> environment) throws Exception {
        EntryPointRun run = runInSandbox(workDir, sandbox,
                "complete_container_marker \"$" + markerVariable + "\" " + shellQuote(payload) + "\n", environment);
        assertEquals(0, run.getExitCode(), "the marker fixture must be written, output was:\n" + run.getOutput());
    }

    /** The managed-database environment plus a data-load selection, as one map the caller may modify. */
    private static Map<String, String> loadEnvironment(String dataLoad, Map<String, String> database) {
        Map<String, String> environment = new LinkedHashMap<>(database);
        environment.put("OFBIZ_DATA_LOAD", dataLoad);
        return environment;
    }

    /** The six variables that carry the schema-initialisation database identity, as a modifiable map. */
    private static Map<String, String> initIdentityEnvironment() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("OFBIZ_POSTGRES_OFBIZ_INIT_USER", OFBIZ_INIT_USER);
        environment.put("OFBIZ_POSTGRES_OFBIZ_INIT_PASSWORD", OFBIZ_INIT_PASSWORD);
        environment.put("OFBIZ_POSTGRES_OLAP_INIT_USER", OLAP_INIT_USER);
        environment.put("OFBIZ_POSTGRES_OLAP_INIT_PASSWORD", OLAP_INIT_PASSWORD);
        environment.put("OFBIZ_POSTGRES_TENANT_INIT_USER", TENANT_INIT_USER);
        environment.put("OFBIZ_POSTGRES_TENANT_INIT_PASSWORD", TENANT_INIT_PASSWORD);
        return environment;
    }

    /**
     * Asserts that an init run with the given initialisation identity is refused and renders nothing.
     *
     * @param tempDir the per-test sandbox root
     * @param label a directory-name fragment naming the case, so a failure is traceable to it
     * @param identity the six initialisation variables, as the case wants them
     * @param expectedReason a fragment the refusal must contain, so the run failed for the reason under test
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    private static void assertInitIdentityRefused(Path tempDir, String label, Map<String, String> identity,
            String expectedReason) throws Exception {
        Path sandbox = prepareSandbox(Files.createTempDirectory(tempDir, label));
        Map<String, String> environment = managedDatabaseEnvironment();
        environment.putAll(identity);
        environment.put("OFBIZ_SCHEMA_INIT", "true");

        EntryPointRun run = runInSandbox(tempDir, sandbox, CONFIGURE_DATABASE_BODY, environment);
        assertNotEquals(0, run.getExitCode(), "the run must be refused, output was:\n" + run.getOutput());
        assertTrue(run.getOutput().contains(expectedReason),
                "the refusal must say '" + expectedReason + "', output was:\n" + run.getOutput());
        assertFalse(Files.exists(sandbox.resolve(RENDERED_OVERRIDE)),
                "a refused identity must render no configuration at all");
    }

    /** Drives the rendered-identity validator alone, with the resolved roles supplied explicitly. */
    private static String renderedIdentityBody(String identity) {
        return "RESOLVED_DATABASE_IDENTITY=" + shellQuote(identity) + "\n"
                + "RESOLVED_POSTGRES_OFBIZ_USER=" + shellQuote(OFBIZ_USER) + "\n"
                + "RESOLVED_POSTGRES_OLAP_USER=" + shellQuote(OLAP_USER) + "\n"
                + "RESOLVED_POSTGRES_TENANT_USER=" + shellQuote(TENANT_USER) + "\n"
                + "require_rendered_database_identity " + RENDERED_OVERRIDE + "\n"
                + "printf '" + IDENTITY_VERIFIED + "\\n'\n";
    }

    /**
     * The role each managed datasource in a rendered configuration authenticates as.
     *
     * @param sandbox the sandbox holding the render
     * @return datasource name to {@code jdbc-username}, for the three managed datasources only
     * @throws Exception if the render could not be read or parsed, which fails the test
     */
    private static Map<String, String> renderedManagedUsers(Path sandbox) throws Exception {
        Element rendered = parseXml(Files.readString(sandbox.resolve(RENDERED_OVERRIDE), StandardCharsets.UTF_8));
        Map<String, String> users = new LinkedHashMap<>();
        for (String datasourceName : MANAGED_DATASOURCES) {
            users.put(datasourceName, inlineJdbcOf(rendered, datasourceName).getAttribute("jdbc-username"));
        }
        return users;
    }

    /**
     * Renders the managed configuration in a sandbox of its own and returns that sandbox.
     *
     * <p>The transport requirement is stubbed so a case may ask for cross-instance invalidation without a
     * message broker; nothing it stubs takes part in the render or in the record.</p>
     *
     * @param tempDir the per-test sandbox root
     * @param environment the variables the start is given
     * @return the sandbox, holding both the rendered configuration and the desired-state record
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    private static Path renderedWith(Path tempDir, Map<String, String> environment) throws Exception {
        Path sandbox = prepareSandbox(Files.createTempDirectory(tempDir, "state"));
        EntryPointRun run =
                runInSandbox(tempDir, sandbox, CONFIGURE_DATABASE_WITHOUT_TRANSPORT_BODY, environment);
        assertEquals(0, run.getExitCode(), "the render must succeed, output was:\n" + run.getOutput());
        return sandbox;
    }

    /** The desired-state record a render left behind. */
    private static String recordedDesiredState(Path sandbox) throws IOException {
        return Files.readString(sandbox.resolve(DB_CONFIG_APPLIED_MARKER), StandardCharsets.UTF_8);
    }

    /**
     * The record format version the entry point declares, read from it rather than restated here.
     *
     * <p>Restating it would make every assertion about the version agree with this file instead of with the
     * script, which is the drift the single declaration exists to prevent.</p>
     *
     * @return the first line every desired-state record must carry
     * @throws IOException if the entry point could not be read, which fails the test
     */
    private static String declaredRecordVersion() throws IOException {
        String script = Files.readString(repositoryRoot().resolve(ENTRY_POINT), StandardCharsets.UTF_8);
        Matcher declaration = Pattern.compile("^DESIRED_STATE_RECORD_VERSION='([^']+)'$", Pattern.MULTILINE)
                .matcher(script);
        assertTrue(declaration.find(), "the entry point must declare the desired-state record version once");
        return declaration.group(1);
    }

    /**
     * The variable names one of the entry point's own arrays declares.
     *
     * <p>Read from the script so that a case asserting something about the set cannot drift away from it; a
     * list restated here would agree with this file rather than with the entry point.</p>
     *
     * @param arrayName the array to read, for example {@code RUNTIME_APPLIED_VARIABLES}
     * @return the declared names, in declaration order
     * @throws IOException if the entry point could not be read, which fails the test
     */
    /**
     * Join every comment line in the entry point into one string, the way a reader takes in a comment block.
     *
     * <p>Without this, a claim that wraps across lines is present in the file and absent from any search for
     * it, which turns a prose assertion into one that passes because it found nothing to check.</p>
     *
     * @param script the entry point's text
     * @return every comment line, stripped of its marker and indentation, joined by single spaces
     */
    private static String flattenedComments(String script) {
        StringBuilder prose = new StringBuilder();
        for (String line : script.split("\\n", -1)) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("#")) {
                continue;
            }
            if (prose.length() > 0) {
                prose.append(' ');
            }
            prose.append(trimmed.substring(1).trim());
        }
        return prose.toString();
    }

    /**
     * The single number word a registered claim states.
     *
     * @param fragment the claim
     * @return what the word means as an integer
     */
    private static int statedNumber(String fragment) {
        Matcher word = Pattern.compile("\\b(" + String.join("|", NUMBER_WORDS.keySet()) + ")\\b",
                Pattern.CASE_INSENSITIVE).matcher(fragment);
        List<String> stated = new ArrayList<>();
        while (word.find()) {
            stated.add(word.group(1).toLowerCase(Locale.ROOT));
        }
        assertEquals(1, stated.size(),
                "a registered claim has to state exactly one number for the count to be unambiguous: " + fragment);
        return NUMBER_WORDS.get(stated.get(0));
    }

    /**
     * Whether an occurrence of {@code fragment} in {@code prose} encloses the range {@code from..to}.
     *
     * @param prose the flattened comment text
     * @param fragment the registered claim
     * @param from start of the range, inclusive
     * @param to end of the range, exclusive
     * @return true when some occurrence of the fragment contains the whole range
     */
    private static boolean containsAt(String prose, String fragment, int from, int to) {
        int at = prose.indexOf(fragment);
        while (at >= 0) {
            if (at <= from && to <= at + fragment.length()) {
                return true;
            }
            at = prose.indexOf(fragment, at + 1);
        }
        return false;
    }

    private static Set<String> declaredVariableNames(String arrayName) throws IOException {
        String script = Files.readString(repositoryRoot().resolve(ENTRY_POINT), StandardCharsets.UTF_8);
        Matcher declaration = Pattern.compile("^" + Pattern.quote(arrayName) + "=\\((.*?)^\\)$",
                Pattern.MULTILINE | Pattern.DOTALL).matcher(script);
        assertTrue(declaration.find(), arrayName + " must be declared as a multi-line array in the entry point");
        Set<String> names = new LinkedHashSet<>();
        Matcher name = Pattern.compile("OFBIZ_[A-Z0-9_]+").matcher(declaration.group(1));
        while (name.find()) {
            names.add(name.group());
        }
        assertFalse(names.isEmpty(), arrayName + " must declare at least one variable");
        return names;
    }

    /** One delimited section of a run's output, failing if the run did not produce it. */
    private static String section(String output, String name) {
        Matcher block = Pattern.compile(name + "-BEGIN\\n(.*?)" + name + "-END", Pattern.DOTALL)
                .matcher(output);
        assertTrue(block.find(), "the run must report its " + name + " environment, output was:\n" + output);
        return block.group(1);
    }

    /** The names a withdrawal run reported inside the named section of its output. */
    private static Set<String> reportedVariableNames(String output, String sectionName) {
        Set<String> names = new LinkedHashSet<>();
        for (String line : section(output, sectionName).split("\\n")) {
            if (!line.isBlank()) {
                names.add(line.trim());
            }
        }
        return names;
    }

    /** The names two sets have in common, so a disjointness requirement can name what broke it. */
    private static Set<String> intersection(Set<String> first, Set<String> second) {
        Set<String> shared = new LinkedHashSet<>(first);
        shared.retainAll(second);
        return shared;
    }

    /** The administrator credential, as one map the caller may modify. */
    private static Map<String, String> adminEnvironment(String password) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("OFBIZ_PROFILE", "dev");
        environment.put("OFBIZ_ADMIN_USER", "admin");
        environment.put("OFBIZ_ADMIN_PASSWORD", password);
        return environment;
    }

    /**
     * A complete, valid managed-database environment, as a fresh mutable map so a case can change one entry.
     * Every value is synthetic and the host is unroutable, so a case that somehow reached a driver could still
     * not contact anything.
     */
    private static Map<String, String> managedDatabaseEnvironment() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("OFBIZ_POSTGRES_HOST", DATABASE_HOST);
        environment.put("OFBIZ_POSTGRES_PORT", DATABASE_PORT);
        environment.put("OFBIZ_POSTGRES_OFBIZ_DB", OFBIZ_DATABASE);
        environment.put("OFBIZ_POSTGRES_OLAP_DB", OLAP_DATABASE);
        environment.put("OFBIZ_POSTGRES_TENANT_DB", TENANT_DATABASE);
        environment.put("OFBIZ_POSTGRES_OFBIZ_USER", OFBIZ_USER);
        environment.put("OFBIZ_POSTGRES_OLAP_USER", OLAP_USER);
        environment.put("OFBIZ_POSTGRES_TENANT_USER", TENANT_USER);
        environment.put("OFBIZ_POSTGRES_OFBIZ_PASSWORD", OFBIZ_PASSWORD);
        environment.put("OFBIZ_POSTGRES_OLAP_PASSWORD", OLAP_PASSWORD);
        environment.put("OFBIZ_POSTGRES_TENANT_PASSWORD", TENANT_PASSWORD);
        environment.put("OFBIZ_POSTGRES_SSLMODE", SSL_MODE);
        environment.put("OFBIZ_POSTGRES_SSLROOTCERT", SSL_ROOT_CERTIFICATE);
        environment.put("OFBIZ_DB_POOL_MIN", POOL_MIN);
        environment.put("OFBIZ_DB_POOL_MAX", POOL_MAX);
        // OFBIZ_DISTRIBUTED_CACHE_CLEAR is deliberately absent, so the baseline render is the default
        // single-node one and a case that wants the other state has to ask for it explicitly.
        environment.put("OFBIZ_PROFILE", "dev");
        return environment;
    }

    /** The TLS query string as the entry point resolves it, before XML escaping. */
    private static String sslQuery() {
        return "?sslmode=" + SSL_MODE + "&sslrootcert=" + SSL_ROOT_CERTIFICATE;
    }

    /** The same query string as it appears in the rendered file, where {@code &} is an XML entity. */
    private static String renderedSslQuery() {
        return sslQuery().replace("&", "&amp;");
    }

    /** The complete JDBC URI the render must produce for a given database name. */
    private static String jdbcUri(String databaseName) {
        return "jdbc:postgresql://" + DATABASE_HOST + ":" + DATABASE_PORT + "/" + databaseName
                + sslQuery() + JDBC_DEADLINE_QUERY;
    }

    /** The two startup-DDL flags of a datasource, as {@code check-on-start/add-missing-on-start}. */
    private static String ddlFlagsOf(Element root, String datasourceName) {
        Element datasource = named(root, "datasource", datasourceName);
        return datasource.getAttribute(CHECK_ON_START) + "/" + datasource.getAttribute(ADD_MISSING_ON_START);
    }

    /** Entity group to datasource name, for every {@code group-map} of a delegator. */
    private static Map<String, String> delegatorMapping(Element root, String delegatorName) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (Element groupMap : childElements(named(root, "delegator", delegatorName), "group-map")) {
            mapping.put(groupMap.getAttribute("group-name"), groupMap.getAttribute("datasource-name"));
        }
        return mapping;
    }

    private static Element delegator(Element root, String delegatorName) {
        return named(root, "delegator", delegatorName);
    }

    /** The single {@code inline-jdbc} child of a named datasource. */
    private static Element inlineJdbcOf(Element root, String datasourceName) {
        List<Element> inlineJdbc = childElements(named(root, "datasource", datasourceName), "inline-jdbc");
        assertEquals(1, inlineJdbc.size(), datasourceName + " must have exactly one <inline-jdbc> child");
        return inlineJdbc.get(0);
    }

    /** The single child element of the given local name whose {@code name} attribute matches. */
    private static Element named(Element root, String localName, String name) {
        for (Element candidate : childElements(root, localName)) {
            if (name.equals(candidate.getAttribute("name"))) {
                return candidate;
            }
        }
        throw new AssertionError("no <" + localName + " name=\"" + name + "\"> in the rendered configuration");
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

    /**
     * The cache-clear flag lines {@link #RESOLVE_FLAGS_BODY} publishes, and only those.
     *
     * <p>Matched as a whole line rather than as a substring on purpose. The refusal message names the variable
     * together with the value it was given - {@code OFBIZ_DISTRIBUTED_CACHE_CLEAR=true} - so a substring test
     * for {@code CACHE_CLEAR=true} matches the very message it is meant to be distinguished from, and the
     * assertion would then fail whichever way the shell behaved.</p>
     *
     * @param run a completed entry-point run
     * @return the {@code CACHE_CLEAR=<value>} lines the run printed, in order, empty if it printed none
     */
    private static List<String> publishedCacheClearFlags(EntryPointRun run) {
        List<String> published = new ArrayList<>();
        for (String line : run.getOutput().split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("CACHE_CLEAR=")) {
                published.add(trimmed);
            }
        }
        return published;
    }

    /** Every distinct render placeholder in a document, in the order they first appear. */
    private static Set<String> placeholdersIn(String text) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    /** Parses a document with external entity resolution switched off, so parsing stays offline. */
    private static Element parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return factory.newDocumentBuilder()
                .parse(new InputSource(new StringReader(xml)))
                .getDocumentElement();
    }

    /**
     * Validates a document against the LOCAL {@code entity-config.xsd}, returning the problems found. The
     * {@code xsi:noNamespaceSchemaLocation} hint the rendered file inherits points at ofbiz.apache.org, so the
     * grammar is supplied explicitly and external access is switched off on top of that.
     */
    private static List<String> validateAgainstEntityConfigSchema(String xml) throws Exception {
        Path schemaFile = repositoryRoot().resolve(ENTITY_CONFIG_SCHEMA);
        assertTrue(Files.isRegularFile(schemaFile), "missing local schema " + schemaFile);
        SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        schemaFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        forbidExternalAccess(schemaFactory);
        Validator validator = schemaFactory.newSchema(schemaFile.toFile()).newValidator();
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
        validator.validate(new StreamSource(new StringReader(xml)));
        return problems;
    }

    private static void forbidExternalAccess(SchemaFactory factory) {
        for (String property : List.of(XMLConstants.ACCESS_EXTERNAL_DTD, XMLConstants.ACCESS_EXTERNAL_SCHEMA)) {
            try {
                factory.setProperty(property, "");
            } catch (SAXNotRecognizedException | SAXNotSupportedException unsupported) {
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
                continue;
            }
        }
    }

    /**
     * A minimal copy of the container's {@code /ofbiz} layout: the two render sources the entry point reads,
     * an empty state directory for its marker files, and an empty {@code lib-extra} so the stale-driver guard
     * has something to look at. The generated {@code config/} directory is deliberately absent, because
     * whether it comes into existence is itself an assertion.
     */
    private static Path prepareSandbox(Path base) throws IOException {
        Path sandbox = Files.createDirectories(base.resolve("ofbiz"));
        copyInto(sandbox.resolve(ENTITY_ENGINE_TEMPLATE), repositoryRoot().resolve(REPOSITORY_TEMPLATE));
        copyInto(sandbox.resolve(ENTITY_ENGINE_SOURCE), repositoryRoot().resolve(ENTITY_ENGINE_SOURCE));
        Files.createDirectories(sandbox.resolve("state"));
        Files.createDirectories(sandbox.resolve("lib-extra"));
        return sandbox;
    }

    /** A sandbox that also holds the administrator data template, which {@code load_admin_user} populates. */
    private static Path prepareAdminSandbox(Path base) throws IOException {
        Path sandbox = prepareSandbox(base);
        copyInto(sandbox.resolve(ADMIN_DATA_TEMPLATE), repositoryRoot().resolve(ADMIN_DATA_TEMPLATE));
        return sandbox;
    }

    private static void copyInto(Path destination, Path source) throws IOException {
        Files.createDirectories(destination.getParent());
        Files.copy(source, destination);
    }

    /**
     * The shipped entry point, copied verbatim except for its trailing {@code _main "$@"} invocation, so that
     * it can be sourced as a library and its functions called individually. Created once per working directory.
     */
    private static Path entryPointLibrary(Path workDir) throws IOException {
        Path library = workDir.resolve("entrypoint-library.sh");
        if (!Files.exists(library)) {
            List<String> sourced = new ArrayList<>();
            for (String line : Files.readAllLines(repositoryRoot().resolve(ENTRY_POINT), StandardCharsets.UTF_8)) {
                if (!"_main \"$@\"".equals(line)) {
                    sourced.add(line);
                }
            }
            assertEquals(Files.readAllLines(repositoryRoot().resolve(ENTRY_POINT), StandardCharsets.UTF_8).size() - 1,
                    sourced.size(), "the entry point must end with its _main invocation for this harness to work");
            Files.write(library, sourced, StandardCharsets.UTF_8);
        }
        return library;
    }

    /** @see #runInSandbox(Path, Path, String, Map, List) */
    private static EntryPointRun runInSandbox(Path workDir, Path sandbox, String body,
            Map<String, String> environment) throws Exception {
        return runInSandbox(workDir, sandbox, body, environment, List.of());
    }

    /** @see #runInSandbox(Path, Path, String, Map, List, boolean) */
    private static EntryPointRun runInSandbox(Path workDir, Path sandbox, String body,
            Map<String, String> environment, List<String> arguments) throws Exception {
        return runInSandbox(workDir, sandbox, body, environment, arguments, true);
    }

    /**
     * Runs a body WITHOUT the harness supplying a profile, so that the script's own handling of an unnamed
     * profile is what the run demonstrates - the announced {@code dev} default for an absent or empty value,
     * and the refusal of a value that is neither profile. Every other harness overload defaults the profile,
     * because the functions they exercise are normally reached after it has been resolved.
     *
     * @param workDir where the generated library and driver scripts are written
     * @param sandbox the directory the script treats as the OFBiz home
     * @param body the shell to run once the library has been sourced
     * @param environment the {@code OFBIZ_*} variables to supply, which may omit {@code OFBIZ_PROFILE}
     * @param arguments positional arguments for the driver, which {@code _main} would exec
     * @return the exit code and combined output of the run
     * @throws Exception if the shell could not be run at all
     */
    private static EntryPointRun runWithoutProfileDefault(Path workDir, Path sandbox, String body,
            Map<String, String> environment, List<String> arguments) throws Exception {
        return runInSandbox(workDir, sandbox, body, environment, arguments, false);
    }

    /**
     * Runs {@code body} with the real entry point sourced, inside {@code sandbox}, as a black box.
     *
     * <p>The driver reassigns every absolute container path the script would otherwise write to, and defaults
     * {@code OFBIZ_PROFILE} exactly as {@code ofbiz_setup_env} does, because the functions under test read it
     * and are normally reached after it. Every inherited {@code OFBIZ_*} variable is removed first, so a value
     * present on the machine running the build cannot decide a case.
     *
     * @param workDir where the generated library and driver scripts are written
     * @param sandbox the directory the script treats as the OFBiz home
     * @param body the shell to run once the library has been sourced
     * @param environment the {@code OFBIZ_*} variables to supply
     * @param arguments positional arguments for the driver, which {@code _main} would exec
     * @param defaultProfile whether the driver supplies {@code dev} when no profile was given; {@code false}
     *        leaves the profile unresolved so the script's own handling of it can be observed
     * @return the exit code and combined output of the run
     */
    private static EntryPointRun runInSandbox(Path workDir, Path sandbox, String body,
            Map<String, String> environment, List<String> arguments, boolean defaultProfile) throws Exception {
        Path driver = Files.createTempFile(workDir, "entrypoint-driver", ".sh");
        Files.writeString(driver, "#!/usr/bin/env bash\n"
                + ". " + shellQuote(entryPointLibrary(workDir)) + "\n"
                + "CONTAINER_STATE_DIR=" + shellQuote(sandbox.resolve("state")) + "\n"
                + "CONTAINER_DATA_LOADED=\"$CONTAINER_STATE_DIR/data_loaded\"\n"
                + "CONTAINER_ADMIN_LOADED=\"$CONTAINER_STATE_DIR/admin_loaded\"\n"
                + "CONTAINER_CONFIG_APPLIED=\"$CONTAINER_STATE_DIR/config_applied\"\n"
                + "CONTAINER_DB_CONFIG_APPLIED=\"$CONTAINER_STATE_DIR/db_config_applied\"\n"
                // The schema-init receipt. Redirected into the sandbox with the rest, because the evidence
                // gate reads it to decide whether an init run may exit successfully - a run that wrote it to
                // the image path would both escape the sandbox and let one case's receipt answer for another.
                + "CONTAINER_SCHEMA_INITIALISED=\"$CONTAINER_STATE_DIR/schema_initialised\"\n"
                + "CONTAINER_ADMIN_MARKER_SALT=\"$CONTAINER_STATE_DIR/admin_marker_salt\"\n"
                + "CONTAINER_GENERATED_SECRETS_DIR=\"$CONTAINER_STATE_DIR/generated_secrets\"\n"
                + "LIB_EXTRA_DIR=" + shellQuote(sandbox.resolve("lib-extra")) + "\n"
                + "CATALINA_COMPONENT_DESCRIPTOR="
                + shellQuote(sandbox.resolve("framework/catalina/ofbiz-component.xml")) + "\n"
                + (defaultProfile ? "OFBIZ_PROFILE=${OFBIZ_PROFILE:-dev}\n" : "")
                + "cd " + shellQuote(sandbox) + " || exit 1\n"
                + body, StandardCharsets.UTF_8);

        List<String> command = new ArrayList<>(List.of("bash", driver.toString()));
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(sandbox.toFile());
        builder.redirectErrorStream(true);
        Map<String, String> processEnvironment = builder.environment();
        processEnvironment.keySet().removeIf(name -> name.startsWith("OFBIZ_"));
        processEnvironment.putAll(environment);

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the entry point driver did not terminate");
        return new EntryPointRun(process.exitValue(), output);
    }

    /** Single-quotes a path for safe interpolation into the generated driver. */
    private static String shellQuote(Path path) {
        return shellQuote(path.toString());
    }

    /** Single-quotes a value for safe interpolation into the generated driver. */
    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static boolean isBashAvailable() {
        try {
            Process process = new ProcessBuilder("bash", "-c", "exit 0").start();
            return process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException unavailable) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Path repositoryRoot() {
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve(DEPENDENCY_MANIFEST))) {
            candidate = candidate.getParent();
        }
        assertNotNull(candidate, "could not locate the repository root from " + Paths.get("").toAbsolutePath());
        return candidate;
    }

    /** What one black-box run of the entry point produced. */
    private static final class EntryPointRun {
        private final int exitCode;
        private final String output;

        EntryPointRun(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        int getExitCode() {
            return exitCode;
        }

        String getOutput() {
            return output;
        }
    }

}
