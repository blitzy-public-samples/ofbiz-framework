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
package org.apache.ofbiz.base.start;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Regression coverage for the admin shared secret: the deployed instance and the shutdown client must
 * always agree on the same effective key.
 *
 * <p>{@code ofbiz.admin.key} ships COMMENTED OUT so that no credential lives in the source tree or in
 * the container image, which means an unconfigured instance runs on {@link Config}'s {@code "NA"}
 * default. {@code AdminServerContainer} compares the key it receives with {@code String.equals}, so
 * any client that resolves the key differently silently loses the ability to shut the server down -
 * exactly what happened while {@code docker/send_ofbiz_stop_signal.sh} parsed the file for an active
 * assignment and derived an empty key.
 *
 * <p>The Java half of this class asserts the shipped file and {@link Config} directly. The shell half
 * runs the real stop script as a black box with a stubbed {@code curl} on {@code PATH} and a
 * controlled properties file, and asserts the exact bytes it puts on the wire. Nothing here contacts
 * a network, and no production file is written.
 */
public final class AdminKeyConfigTests {

    private static final String ADMIN_KEY_PROPERTY = "ofbiz.admin.key";
    private static final String ADMIN_PORT_PROPERTY = "ofbiz.admin.port";
    /** The default {@link Config} applies when the property is not declared at all. */
    private static final String ADMIN_KEY_DEFAULT = "NA";
    /** The port the shipped file declares; it stays an ACTIVE assignment on purpose. */
    private static final String SHIPPED_ADMIN_PORT = "10523";
    private static final String SHUTDOWN_SUFFIX = ":SHUTDOWN";
    private static final String ANCHOR_LINE = "#" + ADMIN_KEY_PROPERTY + "=";
    private static final String START_PROPERTIES =
            "framework/start/src/main/resources/org/apache/ofbiz/base/start/start.properties";
    private static final String STOP_SCRIPT = "docker/send_ofbiz_stop_signal.sh";
    private static final String ENTRY_POINT = "docker/docker-entrypoint.sh";
    /**
     * The ONLY path that shadows the shipped file. {@link Config} reads the resource package qualified,
     * so a flat {@code config/start.properties} overrides nothing.
     */
    private static final String ADMIN_KEY_OVERRIDE = "config/org/apache/ofbiz/base/start/start.properties";
    private static final String INJECTED_KEY = "injected/Key+With$pecials";
    /** 48 SecureRandom bytes Base64 encoded, which is what the entry point generates in the dev profile. */
    private static final int GENERATED_KEY_LENGTH = 64;
    /** The security.properties copy the skipped rendering would have written the signing keys into. */
    private static final String SECURITY_PROPERTIES_OVERRIDE = "config/security.properties";
    /** An admin key that satisfies every rule the rendering path enforces: 16 characters, no ':', mixed. */
    private static final String USABLE_ADMIN_KEY = "Xk7Qm2Rv9Tz4Lp8B";
    /** A signing key that satisfies every rule the rendering path enforces: 64 characters, mixed. */
    private static final String USABLE_SIGNING_KEY = "Hs92Kf47Qz10Bv63Nw85Yr21Ct40Jm79Px38Dl56Gt17Vb94Zq62Ke83Rn05Ao1X";

    /**
     * Every system property {@link Config}'s constructor writes, plus the one these tests set. The
     * constructor is not a pure function - it also replaces the default {@link Locale} and
     * {@link TimeZone} - so all of that global state is snapshotted and restored around each test.
     */
    private static final List<String> MUTATED_SYSTEM_PROPERTIES =
            List.of("ofbiz.home", "java.awt.headless", "user.language", ADMIN_KEY_PROPERTY, ADMIN_PORT_PROPERTY);

    private final Map<String, String> systemPropertySnapshot = new HashMap<>();
    private Locale localeSnapshot;
    private TimeZone timeZoneSnapshot;

    @BeforeEach
    public void snapshotGlobalState() {
        systemPropertySnapshot.clear();
        for (String key : MUTATED_SYSTEM_PROPERTIES) {
            systemPropertySnapshot.put(key, System.getProperty(key));
        }
        localeSnapshot = Locale.getDefault();
        timeZoneSnapshot = TimeZone.getDefault();
    }

    @AfterEach
    public void restoreGlobalState() {
        for (Map.Entry<String, String> entry : systemPropertySnapshot.entrySet()) {
            if (entry.getValue() == null) {
                System.clearProperty(entry.getKey());
            } else {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }
        Locale.setDefault(localeSnapshot);
        TimeZone.setDefault(timeZoneSnapshot);
    }

    /*
     * The shipped properties file
     */

    @Test
    public void shippedStartPropertiesDeclaresNoAdminKeyAtAll() throws IOException {
        Properties shipped = loadProperties(repositoryRoot().resolve(START_PROPERTIES));

        // Loading with java.util.Properties - the very class Config uses - is what makes this
        // airtight: it catches an assignment in ANY accepted form ("key=", "key:", "key value",
        // leading whitespace, continuation lines), not just the one spelling a grep would find.
        assertNull(shipped.getProperty(ADMIN_KEY_PROPERTY),
                "ofbiz.admin.key must not be declared: a live secret must never reside in the source tree");
        assertEquals(ADMIN_KEY_DEFAULT, shipped.getProperty(ADMIN_KEY_PROPERTY, ADMIN_KEY_DEFAULT),
                "an absent property must resolve to Config's shared default");
    }

    @Test
    public void shippedStartPropertiesKeepsTheCommentedAnchorAndNoPlaceholder() throws IOException {
        List<String> anchors = new ArrayList<>();
        for (String line : Files.readAllLines(repositoryRoot().resolve(START_PROPERTIES), StandardCharsets.UTF_8)) {
            if (line.startsWith("#" + ADMIN_KEY_PROPERTY)) {
                anchors.add(line);
            }
        }

        // Exactly the bare anchor, nothing after the '=': that single equality simultaneously rejects
        // a re-introduced literal, a ${...} expression, an @TOKEN@ marker and a generated example key.
        // OFBiz performs no environment expansion on this file, so any of those would be taken
        // verbatim as the key. Prose lines in the comment block are indented after their '#' and are
        // therefore not matched here.
        assertEquals(List.of(ANCHOR_LINE), anchors, "the commented ofbiz.admin.key anchor must survive verbatim");
    }

    @Test
    public void shippedStartPropertiesKeepsTheAdminPortAnchorActive() throws IOException {
        Properties shipped = loadProperties(repositoryRoot().resolve(START_PROPERTIES));

        // The port is NOT a secret and deliberately stays an active assignment: both Config and the
        // stop script read it from here, so commenting it out would break shutdown just as surely as
        // mis-resolving the key.
        assertEquals(SHIPPED_ADMIN_PORT, shipped.getProperty(ADMIN_PORT_PROPERTY), "ofbiz.admin.port");
    }

    /*
     * Config resolution
     */

    @Test
    public void configResolvesTheAbsentAdminKeyToTheSharedDefault() throws StartupException {
        System.clearProperty(ADMIN_KEY_PROPERTY);
        System.clearProperty(ADMIN_PORT_PROPERTY);

        Config config = new Config(Collections.emptyList());

        assertEquals(ADMIN_KEY_DEFAULT, config.getAdminKey(),
                "with no injected value the server must listen for the shared \"NA\" default");
        assertEquals(Integer.parseInt(SHIPPED_ADMIN_PORT), config.getAdminPort(), "admin port with no port offset");
    }

    @Test
    public void systemPropertyOverridesTheFileForTheAdminKey() throws StartupException {
        System.setProperty(ADMIN_KEY_PROPERTY, INJECTED_KEY);

        Config config = new Config(Collections.emptyList());

        // This is the deploy-time injection route: -Dofbiz.admin.key wins over the file with no file
        // rewriting at all, because Config.getProperty consults System.getProperty first.
        assertEquals(INJECTED_KEY, config.getAdminKey(), "an injected key must override the file");
        assertNotEquals(ADMIN_KEY_DEFAULT, config.getAdminKey(), "the default must not mask an injected key");
    }

    /*
     * The stop script must land on the same effective key
     */

    @Test
    public void stopScriptSendsExactlyTheKeyAndPortConfigResolvesForTheShippedFile(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the stop script");
        System.clearProperty(ADMIN_KEY_PROPERTY);
        System.clearProperty(ADMIN_PORT_PROPERTY);
        Config config = new Config(Collections.emptyList());

        StopScriptRun run = runStopScript(tempDir, repositoryRoot().resolve(START_PROPERTIES), null);

        // The regression this whole class exists for: deriving the key from the commented anchor yields an
        // EMPTY key while the server listens for "NA", so AdminServerContainer would reject every SHUTDOWN
        // and the container would never stop cleanly.
        assertEquals(0, run.getExitCode(), "stop script exit code, output was:\n" + run.getOutput());
        assertEquals(config.getAdminKey() + SHUTDOWN_SUFFIX, run.getPayload(), "bytes sent to the admin socket");
        assertEquals("telnet://localhost:" + config.getAdminPort(), run.getCurlArguments(), "admin socket address");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("propertiesFileCases")
    public void stopScriptMirrorsPropertiesSemanticsForEveryDeclarationForm(String label, String content,
            String documentedKey, @TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the stop script");
        Path properties = Files.writeString(tempDir.resolve("start.properties"), content, StandardCharsets.UTF_8);
        // The rule Config applies when nothing is injected, expressed with the same library call.
        String expected = loadProperties(properties).getProperty(ADMIN_KEY_PROPERTY, ADMIN_KEY_DEFAULT);
        assertEquals(documentedKey, expected, "the documented expectation must match java.util.Properties for " + label);

        StopScriptRun run = runStopScript(tempDir, properties, null);

        assertEquals(0, run.getExitCode(), "stop script exit code, output was:\n" + run.getOutput());
        assertEquals(expected + SHUTDOWN_SUFFIX, run.getPayload(), "bytes sent to the admin socket for " + label);
    }

    private static Stream<Arguments> propertiesFileCases() {
        return Stream.of(
                // A commented anchor is "not declared", so the default applies - the fixed behaviour.
                Arguments.of("commented anchor falls back to NA",
                        ADMIN_PORT_PROPERTY + "=" + SHIPPED_ADMIN_PORT + "\n" + ANCHOR_LINE + "\n", ADMIN_KEY_DEFAULT),
                // A DECLARED empty value is not absent: Properties returns "" and no default applies,
                // so the script must send "" rather than silently substituting NA.
                Arguments.of("declared empty value stays empty",
                        ADMIN_PORT_PROPERTY + "=" + SHIPPED_ADMIN_PORT + "\n" + ADMIN_KEY_PROPERTY + "=\n", ""),
                // A key may legitimately contain characters that break naive sed substitution.
                Arguments.of("declared value is used verbatim",
                        ADMIN_PORT_PROPERTY + "=" + SHIPPED_ADMIN_PORT + "\n" + ADMIN_KEY_PROPERTY + "=a/b+c=d\n", "a/b+c=d"),
                // No property at all: the same fallback as a commented anchor.
                Arguments.of("no declaration at all falls back to NA",
                        ADMIN_PORT_PROPERTY + "=" + SHIPPED_ADMIN_PORT + "\n", ADMIN_KEY_DEFAULT));
    }

    @Test
    public void stopScriptPrefersTheInjectedEnvironmentValueOverTheFile(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the stop script");
        String content = ADMIN_PORT_PROPERTY + "=" + SHIPPED_ADMIN_PORT + "\n" + ADMIN_KEY_PROPERTY + "=fromTheFile\n";
        Path properties = Files.writeString(tempDir.resolve("start.properties"), content, StandardCharsets.UTF_8);

        StopScriptRun run = runStopScript(tempDir, properties, INJECTED_KEY);

        // Mirrors Config consulting System.getProperty() before the file: whatever was injected at
        // deploy time wins, so the client agrees with the server even when the file is stale.
        assertEquals(0, run.getExitCode(), "stop script exit code, output was:\n" + run.getOutput());
        assertEquals(INJECTED_KEY + SHUTDOWN_SUFFIX, run.getPayload(), "bytes sent to the admin socket");
    }

    @Test
    public void stopScriptNeverWritesTheSecretToItsOutput(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the stop script");
        String content = ADMIN_PORT_PROPERTY + "=" + SHIPPED_ADMIN_PORT + "\n";
        Path properties = Files.writeString(tempDir.resolve("start.properties"), content, StandardCharsets.UTF_8);

        StopScriptRun run = runStopScript(tempDir, properties, INJECTED_KEY);

        // docker-entrypoint.sh calls this script from its SIGTERM trap, so anything the script echoes
        // ends up in the container logs. The entry point keeps shell tracing off by default and off
        // around secrets, but this script must not rely on that: it may be run directly, and the
        // operator may enable tracing. Only the provenance may be reported, never the key itself.
        assertFalse(run.getOutput().contains(INJECTED_KEY), "the secret leaked into the output:\n" + run.getOutput());
        assertTrue(run.getOutput().contains("Admin key source:"), "the key provenance should still be reported");
        assertEquals(INJECTED_KEY + SHUTDOWN_SUFFIX, run.getPayload(), "the secret must still reach the admin socket");
    }

    @Test
    public void stopScriptFailsLoudlyWhenNoStartPropertiesIsReadable(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the stop script");

        StopScriptRun run = runStopScript(tempDir, tempDir.resolve("absent.properties"), null);

        // Failing fast beats sending a guessed key to the admin socket and reporting success.
        assertNotEquals(0, run.getExitCode(), "a missing properties file must fail the script");
        assertTrue(run.getOutput().contains("Unable to read start.properties"),
                "the failure should name the problem, output was:\n" + run.getOutput());
        assertNull(run.getPayload(), "nothing may be sent to the admin socket when resolution failed");
    }

    /**
     * The loop the entry point's withdrawal is driven by, in place of a hand written {@code unset} per name.
     */
    private static final String WITHDRAWAL_LOOP =
            "for variableName in \"${RUNTIME_APPLIED_VARIABLES[@]}\" \"${CONTAINER_CONTROL_VARIABLES[@]}\"; do";

    /**
     * Whether the entry point withdraws {@code variable} from the environment before it execs the server.
     *
     * <p>The withdrawal is driven by the two declared inventories rather than by one hand written
     * {@code unset} per name, so what has to be present is the variable's DECLARATION plus the loop that
     * consumes both arrays. Asserting on a literal {@code unset OFBIZ_X} would hold only while the list
     * stayed hand written, which is the drift the loop exists to remove - two names were once missed by
     * exactly that list. The behaviour itself is exercised end to end by
     * {@code SchemaInitEntryPointTests}, which plants every declared name and drives the real function.</p>
     *
     * @param entryPoint the entry point's text
     * @param variable the environment variable that must not survive into the served JVM
     * @return true when the variable is declared by an inventory the withdrawal loop consumes
     */
    private static boolean withdrawnBeforeExec(String entryPoint, String variable) {
        boolean declared = false;
        for (String inventory : List.of("RUNTIME_APPLIED_VARIABLES=(", "CONTAINER_CONTROL_VARIABLES=(")) {
            int at = entryPoint.indexOf(inventory);
            int end = at < 0 ? -1 : entryPoint.indexOf("\n)", at);
            if (end < 0) {
                continue;
            }
            declared = declared || List.of(entryPoint.substring(at, end).split("\\s+")).contains(variable);
        }
        return declared && entryPoint.contains(WITHDRAWAL_LOOP) && entryPoint.contains("unset \"$variableName\"");
    }

    @Test
    public void entryPointInjectsTheKeyIntoAProtectedFileAndNeverOntoACommandLine() throws IOException {
        String executable = executableLinesOf(repositoryRoot().resolve(ENTRY_POINT));

        // Config would honour -Dofbiz.admin.key, but a command line argument is visible in the process
        // table and in /proc/<pid>/cmdline to every process in the namespace and cannot be withdrawn
        // once observed, so the key must never travel that way.
        assertFalse(executable.contains("Dofbiz.admin.key"),
                "the entry point must not pass the admin key as a JVM system property");
        assertTrue(executable.contains(ADMIN_KEY_OVERRIDE),
                "the entry point must write the package qualified override so that Config resolves it");
        assertFalse(executable.contains("config/start.properties"),
                "a flat config/start.properties shadows nothing and must not be written");
        assertTrue(withdrawnBeforeExec(executable, "OFBIZ_ADMIN_KEY"),
                "the injected key must be removed from the environment OFBiz inherits");
        assertTrue(executable.contains("capture_secret_environment"),
                "the injected key must be moved out of the exported environment before any child process runs");
    }

    @Test
    public void entryPointRemovesTheAdminKeyFromTheEnvironmentBeforeAnyChildProcessRuns(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareEntryPointSandbox(tempDir);
        Path childEnvironment = sandbox.resolve("child-environment");

        EntryPointRun run = renderAdminKeyConfiguration(tempDir, sandbox,
                Map.of("OFBIZ_PROFILE", "prod", "OFBIZ_ADMIN_KEY", INJECTED_KEY),
                "env >" + shellQuote(childEnvironment) + "\n");

        assertEquals(0, run.getExitCode(), "the render must succeed, output was:\n" + run.getOutput());
        // 'env' is an ordinary child process, so what it reports is exactly what an initialisation JVM, an
        // executable hook or a crash handler would inherit - and would republish through
        // /proc/<pid>/environ, a heap dump or an hs_err file. Removing the variable before any of them
        // exists is what makes the key unobservable to them, rather than observable then withdrawn.
        String observed = Files.readString(childEnvironment, StandardCharsets.UTF_8);
        assertFalse(observed.contains("OFBIZ_ADMIN_KEY"),
                "the variable name must not reach a child process, environment was:\n" + observed);
        assertFalse(observed.contains(INJECTED_KEY), "the secret value must not reach a child process");
        assertFalse(observed.contains("CAPTURED_"),
                "the shell copy the entry point keeps must not be exported, environment was:\n" + observed);
        // The removal would be worthless if the key had not reached the file the JVM actually reads.
        assertEquals(INJECTED_KEY, loadProperties(sandbox.resolve(ADMIN_KEY_OVERRIDE)).getProperty(ADMIN_KEY_PROPERTY),
                "the key must still be rendered into the package qualified override");
    }

    @Test
    public void entryPointRefusesToStartWithoutAnAdminKeyInTheProdProfile(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareEntryPointSandbox(tempDir);

        EntryPointRun run = renderAdminKeyConfiguration(tempDir, sandbox, Map.of("OFBIZ_PROFILE", "prod"));

        // Falling back to the published "NA" default in production would leave the admin port guarded by
        // a value anyone can read in this repository, so the container must refuse to start instead.
        assertNotEquals(0, run.getExitCode(), "prod must fail fast, output was:\n" + run.getOutput());
        assertTrue(run.getOutput().contains("OFBIZ_ADMIN_KEY"),
                "the failure must name the missing variable, output was:\n" + run.getOutput());
        assertFalse(Files.exists(sandbox.resolve(ADMIN_KEY_OVERRIDE)),
                "no configuration may be written when a required secret is absent");
    }

    @Test
    public void entryPointWritesASuppliedAdminKeyIntoAnOverrideOnlyTheOfbizUserCanRead(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareEntryPointSandbox(tempDir);

        EntryPointRun run = renderAdminKeyConfiguration(tempDir, sandbox,
                Map.of("OFBIZ_PROFILE", "prod", "OFBIZ_ADMIN_KEY", INJECTED_KEY));

        assertEquals(0, run.getExitCode(), "the render must succeed, output was:\n" + run.getOutput());
        Path override = sandbox.resolve(ADMIN_KEY_OVERRIDE);
        Properties rendered = loadProperties(override);
        assertEquals(INJECTED_KEY, rendered.getProperty(ADMIN_KEY_PROPERTY),
                "the injected key must round-trip through java.util.Properties unchanged");
        assertEquals(SHIPPED_ADMIN_PORT, rendered.getProperty(ADMIN_PORT_PROPERTY),
                "the active ofbiz.admin.port assignment must survive the substitution");
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(override),
                "a file holding a secret must not be readable by other accounts in the container");
        assertFalse(run.getOutput().contains(INJECTED_KEY), "the key leaked into the output:\n" + run.getOutput());
        assertFalse(Files.exists(sandbox.resolve("config/start.properties")),
                "a flat config/start.properties must not be written");
    }

    @Test
    public void entryPointGeneratesAStableAdminKeyInTheDevProfile(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareEntryPointSandbox(tempDir);
        Path override = sandbox.resolve(ADMIN_KEY_OVERRIDE);

        EntryPointRun first = renderAdminKeyConfiguration(tempDir, sandbox, Map.of());
        assertEquals(0, first.getExitCode(), "the dev render must succeed, output was:\n" + first.getOutput());
        String generated = loadProperties(override).getProperty(ADMIN_KEY_PROPERTY);

        assertNotNull(generated, "dev must still produce a usable key with no configuration at all");
        assertNotEquals(ADMIN_KEY_DEFAULT, generated, "dev must not run on the publicly known default");
        assertEquals(GENERATED_KEY_LENGTH, generated.length(), "the generated key should be 64 Base64 characters");
        assertFalse(generated.contains(":"),
                "AdminServerContainer splits on the first ':', so a key containing one could never authenticate");
        assertFalse(first.getOutput().contains(generated), "the generated key leaked into the output");

        // Stability matters: a key regenerated on every restart would be a behaviour change that breaks an
        // in-flight shutdown request.
        renderAdminKeyConfiguration(tempDir, sandbox, Map.of());
        assertEquals(generated, loadProperties(override).getProperty(ADMIN_KEY_PROPERTY),
                "a generated key must stay stable for the life of the container state directory");
    }

    @Test
    public void entryPointRejectsAnAdminKeyThatCouldNeverAuthenticate(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Map<String, String> unusable = new LinkedHashMap<>();
        unusable.put("key:with:colons-value", "contains the ':' request delimiter");
        unusable.put("short1", "is shorter than 16 characters");
        unusable.put(ADMIN_KEY_DEFAULT, "is the publicly known NA default");
        unusable.put("aaaaaaaaaaaaaaaaaaaa", "repeats a single character");
        unusable.put("abababababababababab", "uses only two distinct characters");
        unusable.put("key\nwith-a-newline-01", "contains a control character");

        for (Map.Entry<String, String> unusableKey : unusable.entrySet()) {
            Path sandbox = prepareEntryPointSandbox(Files.createTempDirectory(tempDir, "case"));
            EntryPointRun run =
                    renderAdminKeyConfiguration(tempDir, sandbox, Map.of("OFBIZ_ADMIN_KEY", unusableKey.getKey()));

            assertNotEquals(0, run.getExitCode(),
                    "a key that " + unusableKey.getValue() + " must be rejected, output was:\n" + run.getOutput());
            assertFalse(Files.exists(sandbox.resolve(ADMIN_KEY_OVERRIDE)),
                    "a rejected key must leave no configuration behind (" + unusableKey.getValue() + ")");
            // A rejected secret is still a secret. The natural way to write this diagnostic is to quote
            // the offending value, which would put an operator's mistyped production key into the
            // container log - a place it cannot be redacted from afterwards. The script therefore reports
            // the variable name and the reason only, and that has to be asserted per case rather than
            // once, because each rejection reason is produced by a different branch.
            assertFalse(run.getOutput().contains(unusableKey.getKey()),
                    "the rejected key leaked into the output (" + unusableKey.getValue() + "):\n" + run.getOutput());
            assertTrue(run.getOutput().contains("OFBIZ_ADMIN_KEY"),
                    "the failure must name the variable to act on (" + unusableKey.getValue() + "):\n"
                            + run.getOutput());
        }
    }

    /**
     * An admin key that begins with whitespace is refused rather than silently shortened, and the rendered
     * override is read back so it cannot hand {@code Config} a value that was never validated.
     *
     * <p>Every check the entry point applies runs on the shell's copy of the value, but {@code Config} reads
     * whatever {@code java.util.Properties} makes of the rendered line - and {@code Properties} discards blanks
     * between the {@code =} and the first non-blank character. A 16 character key with two leading spaces
     * therefore clears the length floor in the shell and reaches {@code AdminServerContainer} as 14 characters.
     * Nothing fails visibly: the key simply is not the key the operator set, and the first symptom is a shutdown
     * request that is refused. A leading space is what a YAML block scalar or a copied secret-manager value
     * produces, so this is an ordinary configuration mistake and has to be reported at start up.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void entryPointRefusesAnAdminKeyPropertiesWouldShortenAndVerifiesWhatItRendered(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");

        for (String blank : List.of(" ", "  ", "\t")) {
            Path sandbox = prepareEntryPointSandbox(Files.createTempDirectory(tempDir, "blank"));
            String padded = blank + INJECTED_KEY;

            EntryPointRun run = renderAdminKeyConfiguration(tempDir, sandbox,
                    Map.of("OFBIZ_PROFILE", "prod", "OFBIZ_ADMIN_KEY", padded));

            assertNotEquals(0, run.getExitCode(),
                    "an admin key with leading whitespace must be refused, output was:\n" + run.getOutput());
            assertTrue(run.getOutput().contains("OFBIZ_ADMIN_KEY"),
                    "the failure must name the variable, output was:\n" + run.getOutput());
            assertFalse(Files.exists(sandbox.resolve(ADMIN_KEY_OVERRIDE)),
                    "a refused key must leave no configuration behind");
            assertFalse(run.getOutput().contains(INJECTED_KEY),
                    "the refused key leaked into the output:\n" + run.getOutput());
        }

        // A duplicated anchor is the other half of the same hazard: Properties takes the LAST declaration, so a
        // source file carrying the anchor twice - a merge, a patch applied twice - would decide the effective
        // key. The render must read its own output back rather than trusting that it substituted the right line.
        Path duplicated = prepareEntryPointSandbox(Files.createTempDirectory(tempDir, "duplicate"));
        Path source = duplicated.resolve(START_PROPERTIES);
        Files.writeString(source, Files.readString(source, StandardCharsets.UTF_8)
                + "\n" + ADMIN_KEY_PROPERTY + "=" + ADMIN_KEY_DEFAULT + "\n", StandardCharsets.UTF_8);

        EntryPointRun run = renderAdminKeyConfiguration(tempDir, duplicated,
                Map.of("OFBIZ_PROFILE", "prod", "OFBIZ_ADMIN_KEY", INJECTED_KEY));

        assertNotEquals(0, run.getExitCode(),
                "a duplicated admin key anchor must be refused, output was:\n" + run.getOutput());
        assertTrue(run.getOutput().contains("exactly one declaration")
                || run.getOutput().contains("does not read back"),
                "the failure must say why the rendered file is unusable, output was:\n" + run.getOutput());
        assertFalse(run.getOutput().contains(INJECTED_KEY),
                "the key must not be echoed while the render is being refused:\n" + run.getOutput());
    }

    /**
     * A configuration one of the entry point's verifiers has just declared untrustworthy is DISCARDED when
     * it is an override the entry point owns, and LEFT ALONE when it is the file the distribution ships.
     *
     * <p>Discarding an override is part of the check rather than tidiness. Every override the entry point
     * writes lands under {@code config/}, which takes class path precedence over the distribution, so an
     * artefact that failed validation must not survive on a persistent {@code /ofbiz/config} volume for a
     * later start to load in preference to the committed defaults - a start that supplies no configuration
     * at all would otherwise run on a value that has already been refused.</p>
     *
     * <p>That reasoning stops at {@code start.properties} itself, which is the one file verified here that
     * the entry point does not own. It is rendered from ITSELF, because it is what {@code bin/ofbiz} reads
     * to authenticate a shutdown request, and it is also the anchor every one of these renders is built
     * from. Deleting it would destroy that anchor: the next start would abort on a missing source file
     * rather than on the real problem, and recovery would mean restoring a distribution file instead of
     * correcting the environment. Nothing reads the rejected value in the meantime, because every caller
     * aborts the start immediately afterwards - so the removal buys nothing here and costs the anchor.</p>
     *
     * <p>The two verifiers are driven directly rather than through {@code render_admin_key_configuration}.
     * The renderer writes both destinations from a single sed program and checks the override first, so the
     * two files always carry the same content and can only be told apart by naming the one under test.</p>
     *
     * @param tempDir a per-test temporary directory
     * @throws Exception if the entry point cannot be read or the shell cannot be run
     */
    @Test
    public void aRefusedRenderIsDiscardedOnlyWhenItIsAnOverrideTheEntryPointOwns(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");

        String missingValue = "require_rendered_declaration %s 'ofbiz\\.admin\\.key' \"$START_PROPERTIES_SOURCE\"";
        String wrongValue = "require_rendered_property_value %s 'ofbiz\\.admin\\.key' OFBIZ_ADMIN_KEY "
                + "'" + USABLE_ADMIN_KEY + "'";

        RefusalOutcome overrideWithoutAValue = verifyRenderedFile(tempDir, "declaration-override", ANCHOR_LINE,
                String.format(missingValue, "\"$ADMIN_KEY_OVERRIDE\""));
        assertNotEquals(0, overrideWithoutAValue.getRun().getExitCode(),
                "a rendered override that declares no value must be refused, output was:\n"
                        + overrideWithoutAValue.getRun().getOutput());
        assertFalse(overrideWithoutAValue.isOverridePresent(),
                "the refused override must not be left in config/ for a later start to load");
        assertTrue(overrideWithoutAValue.isShippedFilePresent(),
                "refusing the override must not touch the shipped file it was rendered from");

        RefusalOutcome shippedWithoutAValue = verifyRenderedFile(tempDir, "declaration-shipped", ANCHOR_LINE,
                String.format(missingValue, "\"$START_PROPERTIES_SOURCE\""));
        assertNotEquals(0, shippedWithoutAValue.getRun().getExitCode(),
                "a shipped file that declares no value must still be refused, output was:\n"
                        + shippedWithoutAValue.getRun().getOutput());
        assertTrue(shippedWithoutAValue.isShippedFilePresent(),
                "the shipped start.properties is the anchor every render is built from and must survive a refusal");

        RefusalOutcome overrideWithTheWrongValue = verifyRenderedFile(tempDir, "value-override",
                ADMIN_KEY_PROPERTY + "=" + ADMIN_KEY_DEFAULT,
                String.format(wrongValue, "\"$ADMIN_KEY_OVERRIDE\""));
        assertNotEquals(0, overrideWithTheWrongValue.getRun().getExitCode(),
                "a rendered override that reads back a different value must be refused, output was:\n"
                        + overrideWithTheWrongValue.getRun().getOutput());
        assertFalse(overrideWithTheWrongValue.isOverridePresent(),
                "an override carrying an unintended value must not be left in config/");
        assertTrue(overrideWithTheWrongValue.isShippedFilePresent(),
                "refusing the override must not touch the shipped file it was rendered from");

        RefusalOutcome shippedWithTheWrongValue = verifyRenderedFile(tempDir, "value-shipped",
                ADMIN_KEY_PROPERTY + "=" + ADMIN_KEY_DEFAULT,
                String.format(wrongValue, "\"$START_PROPERTIES_SOURCE\""));
        assertNotEquals(0, shippedWithTheWrongValue.getRun().getExitCode(),
                "a shipped file that reads back a different value must still be refused, output was:\n"
                        + shippedWithTheWrongValue.getRun().getOutput());
        assertTrue(shippedWithTheWrongValue.isShippedFilePresent(),
                "the shipped start.properties must survive a refusal so that the next start can render from it");
    }

    /**
     * Optional shell tracing must never publish a supplied secret, not even while merely counting which
     * variables the operator set.
     *
     * <p>{@code record_supplied_variables} exists to tell the operator which supplied variables an
     * {@code OFBIZ_SKIP_INIT} container is ignoring, so it needs only their NAMES. It obtains them with
     * {@code [ -n "${!variableName:-}" ]}, and an indirect expansion expands the VALUE before the test
     * runs: under {@code set -x} bash echoes the expanded command, so with {@code OFBIZ_TRACE} enabled
     * every supplied secret was written to the container log as {@code + [ -n <the secret> ]}. Tracing is
     * an operator-facing diagnostic, so it must be safe to switch on in the environment where diagnostics
     * are actually needed.</p>
     *
     * <p>The whole {@code _main} prologue is executed, not the one function, because that is what exercises
     * the rule end to end: the snapshot, the defaulting, and the advisory that prints the collected
     * names all run with tracing on, and the assertion is that the names appear and the values do not.</p>
     *
     * @param tempDir a per-test temporary directory
     * @throws Exception if the entry point cannot be read or the shell cannot be run
     */
    @Test
    public void entryPointNeverTracesASuppliedSecretWhileRecordingWhatWasSupplied(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSkipInitSandbox(Files.createTempDirectory(tempDir, "trace"),
                USABLE_ADMIN_KEY, USABLE_SIGNING_KEY, USABLE_SIGNING_KEY);
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("OFBIZ_TRACE", "1");
        environment.put("OFBIZ_PROFILE", "prod");
        environment.put("OFBIZ_SKIP_INIT", "1");
        environment.put("OFBIZ_ADMIN_KEY", "traced-admin-key-8Kq2Vz");
        environment.put("OFBIZ_LOGIN_SECRET_KEY", "traced-login-key-4Rm9Tb");
        environment.put("OFBIZ_JWT_TOKEN_KEY", "traced-jwt-key-7Yc3Nd");
        environment.put("OFBIZ_POSTGRES_OFBIZ_PASSWORD", "traced-db-password-2Xw6Ph");
        environment.put("OFBIZ_S3_SECRET_ACCESS_KEY", "traced-s3-secret-5Lg1Ju");

        EntryPointRun run = runEntryPoint(tempDir, sandbox, environment,
                "record_supplied_variables\nofbiz_setup_env\nrequire_preprovisioned_runtime_configuration");

        assertEquals(0, run.getExitCode(),
                "a fully pre-provisioned prod container must still start, output was:\n" + run.getOutput());
        for (Map.Entry<String, String> supplied : environment.entrySet()) {
            if (!supplied.getKey().endsWith("KEY") && !supplied.getKey().endsWith("PASSWORD")) {
                continue;
            }
            assertFalse(run.getOutput().contains(supplied.getValue()),
                    supplied.getKey() + " leaked its VALUE into the traced output, which in a container is the log"
                            + " stream. Tracing must be suspended around every expansion of a secret, including the"
                            + " indirect expansion that only wants to know whether it is empty. Output was:\n"
                            + run.getOutput());
        }
        assertTrue(run.getOutput().contains("OFBIZ_ADMIN_KEY"),
                "the NAMES are the point of the snapshot and must still be reported, output was:\n" + run.getOutput());
    }

    /**
     * Neither secret validator may publish a secret while deciding whether it was supplied.
     *
     * <p>{@code validate_required_secrets} and {@code validate_externally_provisioned_secrets} report the
     * whole missing set in one message, so both walk the supplied secrets testing only whether each is
     * empty - {@code [ -z "${!name}" ]} and {@code [ -z "$OFBIZ_ADMIN_KEY" ]}. Neither reads a value, but
     * {@code set -x} echoes a command with its arguments ALREADY expanded, so under {@code OFBIZ_TRACE}
     * the emptiness test itself wrote the admin key, both signing keys and all three managed database
     * passwords to the container log. Presence testing is therefore not exempt from the secret regions:
     * the expansion is the disclosure, whatever the test does with the result.</p>
     *
     * <p>Both validators run in one case, under a supplied set that satisfies both, so the assertion is
     * that they complete without publishing anything. The traced-output control matters as much as the
     * secret assertions: without it a case in which tracing silently failed to switch on would pass while
     * proving nothing, so the run is required to have traced at least one command.</p>
     *
     * @param tempDir a per-test temporary directory
     * @throws Exception if the entry point cannot be read or the shell cannot be run
     */
    @Test
    public void entryPointNeverTracesASecretWhileValidatingThatItWasSupplied(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSkipInitSandbox(Files.createTempDirectory(tempDir, "validate"),
                USABLE_ADMIN_KEY, USABLE_SIGNING_KEY, USABLE_SIGNING_KEY);
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("OFBIZ_TRACE", "1");
        environment.put("OFBIZ_PROFILE", "prod");
        // Set so that the managed database passwords are required, and therefore tested, in this run.
        environment.put("OFBIZ_POSTGRES_HOST", "db.example.invalid");
        environment.put("OFBIZ_ADMIN_KEY", "validated-admin-key-3Hs8Wp");
        environment.put("OFBIZ_LOGIN_SECRET_KEY", "validated-login-key-6Bn4Kz");
        environment.put("OFBIZ_JWT_TOKEN_KEY", "validated-jwt-key-9Fd2Qm");
        environment.put("OFBIZ_POSTGRES_OFBIZ_PASSWORD", "validated-main-password-5Tv7Rj");
        environment.put("OFBIZ_POSTGRES_OLAP_PASSWORD", "validated-olap-password-8Cy1Ln");
        environment.put("OFBIZ_POSTGRES_TENANT_PASSWORD", "validated-tenant-password-2Gk6Xa");

        EntryPointRun run = runEntryPoint(tempDir, sandbox, environment,
                "validate_required_secrets\nvalidate_externally_provisioned_secrets");

        assertEquals(0, run.getExitCode(),
                "a fully supplied prod secret set must satisfy both validators, output was:\n" + run.getOutput());
        assertTrue(run.getOutput().contains("+ "),
                "the case did not trace anything, so it cannot prove that tracing is safe; output was:\n"
                        + run.getOutput());
        for (Map.Entry<String, String> supplied : environment.entrySet()) {
            if (!supplied.getKey().endsWith("KEY") && !supplied.getKey().endsWith("PASSWORD")) {
                continue;
            }
            assertFalse(run.getOutput().contains(supplied.getValue()),
                    supplied.getKey() + " leaked its VALUE into the traced output, which in a container is the log"
                            + " stream. A presence test expands the secret before it runs, so both validators have to"
                            + " suspend tracing around it. Output was:\n" + run.getOutput());
        }
    }

    /**
     * {@code OFBIZ_SKIP_INIT} must require a pre-provisioned secret to be USABLE, not merely present.
     *
     * <p>Skipping the rendering skips the validation that goes with it, so the pre-flight has to apply
     * the same test to what the operator provisioned. Checking only that something follows the {@code =}
     * accepted {@code ofbiz.admin.key=NA} - the published {@link Config} default, identical on every
     * instance of an image and readable by anyone who can pull it - along with one-character login keys
     * and JWT keys shorter than the 64 characters {@code JWTManager.getJWTKey} requires.</p>
     *
     * <p>Each case below is rejected for a different reason, and the reason must reach the operator while
     * the value must not. The last two cases are the ones a presence check cannot distinguish at all: a
     * value that is long enough but is a repeated pattern, and a second declaration of the same property
     * that overrides a good first one - {@code java.util.Properties} resolves a duplicate key to the LAST
     * declaration, so that is the one the pre-flight has to judge.</p>
     *
     * @param tempDir a per-test temporary directory
     * @throws Exception if the entry point cannot be read or the shell cannot be run
     */
    @Test
    public void entryPointRefusesAnUnusablePreprovisionedSecretWhenInitialisationIsSkipped(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Map<String, String> environment = Map.of("OFBIZ_PROFILE", "prod", "OFBIZ_SKIP_INIT", "1");

        // A fully provisioned prod container is the control: the pre-flight must accept it.
        Path provisioned = prepareSkipInitSandbox(Files.createTempDirectory(tempDir, "usable"),
                USABLE_ADMIN_KEY, USABLE_SIGNING_KEY, USABLE_SIGNING_KEY);
        EntryPointRun accepted = runEntryPoint(tempDir, provisioned, environment,
                "ofbiz_setup_env\nrequire_preprovisioned_runtime_configuration");
        assertEquals(0, accepted.getExitCode(),
                "usable pre-provisioned secrets must be accepted, output was:\n" + accepted.getOutput());

        Map<String, String[]> unusable = new LinkedHashMap<>();
        unusable.put("the publicly known NA default",
                new String[] {ADMIN_KEY_DEFAULT, USABLE_SIGNING_KEY, USABLE_SIGNING_KEY, "placeholder"});
        unusable.put("a one-character admin key",
                new String[] {"x", USABLE_SIGNING_KEY, USABLE_SIGNING_KEY, "at least 16 characters"});
        unusable.put("an admin key containing the request delimiter",
                new String[] {"Xk7Qm2Rv9Tz4Lp8:", USABLE_SIGNING_KEY, USABLE_SIGNING_KEY, "must not contain"});
        unusable.put("an admin key containing a control character",
                new String[] {"Xk7Qm2Rv\t9Tz4Lp8B", USABLE_SIGNING_KEY, USABLE_SIGNING_KEY, "control character"});
        unusable.put("a login key shorter than HMAC512 requires",
                new String[] {USABLE_ADMIN_KEY, "Hs92Kf47Qz10Bv63Nw85Yr21Ct40Jm7", USABLE_SIGNING_KEY,
                    "at least 64 characters"});
        unusable.put("a JWT key that is long but is a repeated pattern",
                new String[] {USABLE_ADMIN_KEY, USABLE_SIGNING_KEY,
                    "abababababababababababababababababababababababababababababababab", "entropy"});

        for (Map.Entry<String, String[]> unusableCase : unusable.entrySet()) {
            String[] values = unusableCase.getValue();
            Path sandbox = prepareSkipInitSandbox(Files.createTempDirectory(tempDir, "case"),
                    values[0], values[1], values[2]);
            EntryPointRun run = runEntryPoint(tempDir, sandbox, environment,
                    "ofbiz_setup_env\nrequire_preprovisioned_runtime_configuration");

            assertNotEquals(0, run.getExitCode(), "OFBIZ_SKIP_INIT with " + unusableCase.getKey()
                    + " must fail fast rather than serve production traffic, output was:\n" + run.getOutput());
            assertTrue(run.getOutput().contains(values[3]), "the operator must be told that the pre-provisioned"
                    + " value is unusable and why - expected \"" + values[3] + "\" for " + unusableCase.getKey()
                    + ", output was:\n" + run.getOutput());
            // Asserted only for values that are actually secret-like. The published "NA" placeholder is
            // named in the guidance on purpose - telling the operator which value is rejected is the
            // point of it - and a one-character value cannot be distinguished from ordinary prose, so
            // neither would be evidence of a leak.
            for (String value : List.of(values[0], values[1], values[2])) {
                if (!isDistinctiveSecret(value)) {
                    continue;
                }
                assertFalse(run.getOutput().contains(value), "the pre-flight named the VALUE of a provisioned"
                        + " secret (" + unusableCase.getKey() + "). It may name the property, the file and the"
                        + " variable only. Output was:\n" + run.getOutput());
            }
        }

        // A later declaration overrides an earlier one in java.util.Properties, so a good value followed
        // by the NA default is an unusable file however good the first line looks.
        Path shadowed = prepareSkipInitSandbox(Files.createTempDirectory(tempDir, "shadowed"),
                USABLE_ADMIN_KEY, USABLE_SIGNING_KEY, USABLE_SIGNING_KEY);
        Files.writeString(shadowed.resolve(ADMIN_KEY_OVERRIDE),
                Files.readString(shadowed.resolve(ADMIN_KEY_OVERRIDE), StandardCharsets.UTF_8)
                        + ADMIN_KEY_PROPERTY + "=" + ADMIN_KEY_DEFAULT + "\n", StandardCharsets.UTF_8);
        EntryPointRun overridden = runEntryPoint(tempDir, shadowed, environment,
                "ofbiz_setup_env\nrequire_preprovisioned_runtime_configuration");
        assertNotEquals(0, overridden.getExitCode(), "the LAST declaration of a duplicated property is the one"
                + " OFBiz loads, so it is the one that must be judged, output was:\n" + overridden.getOutput());
    }

    /*
     * Helpers
     */

    /**
     * Whether finding this value in the output would really be evidence that a secret was printed.
     *
     * <p>Excludes the published {@code "NA"} default, which the guidance names deliberately, and anything
     * too short to be told apart from ordinary words in a diagnostic message.</p>
     *
     * @param value a provisioned value
     * @return {@code true} when the value is long enough and private enough for the check to mean something
     */
    private static boolean isDistinctiveSecret(String value) {
        return value.length() >= "12345678".length() && !ADMIN_KEY_DEFAULT.equals(value);
    }

    /**
     * Builds a container layout whose {@code config/} overrides are already provisioned, which is the
     * state an {@code OFBIZ_SKIP_INIT} container is started in.
     *
     * @param base the directory to build the layout under
     * @param adminKey the value to declare for {@code ofbiz.admin.key}
     * @param loginKey the value to declare for {@code login.secret_key_string}
     * @param jwtKey the value to declare for {@code security.token.key}
     * @return the sandbox directory the entry point should treat as the OFBiz home
     * @throws IOException if the layout cannot be written
     */
    private static Path prepareSkipInitSandbox(Path base, String adminKey, String loginKey, String jwtKey)
            throws IOException {
        Path sandbox = prepareEntryPointSandbox(base);
        Path adminOverride = sandbox.resolve(ADMIN_KEY_OVERRIDE);
        Files.createDirectories(adminOverride.getParent());
        Files.writeString(adminOverride, ADMIN_KEY_PROPERTY + "=" + adminKey + "\n"
                + ADMIN_PORT_PROPERTY + "=" + SHIPPED_ADMIN_PORT + "\n", StandardCharsets.UTF_8);
        Path securityOverride = sandbox.resolve(SECURITY_PROPERTIES_OVERRIDE);
        Files.createDirectories(securityOverride.getParent());
        Files.writeString(securityOverride, "login.secret_key_string=" + loginKey + "\n"
                + "security.token.key=" + jwtKey + "\n"
                + "host-headers-allowed=localhost\n", StandardCharsets.UTF_8);
        return sandbox;
    }

    /** The file's lines with every whole-line comment removed, so documentation cannot satisfy an assertion. */
    private static String executableLinesOf(Path file) throws IOException {
        List<String> executable = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.stripLeading().startsWith("#")) {
                executable.add(line);
            }
        }
        return String.join("\n", executable);
    }

    /** Builds a minimal copy of the container's /ofbiz layout: just the pristine start.properties. */
    private static Path prepareEntryPointSandbox(Path base) throws IOException {
        Path sandbox = Files.createDirectories(base.resolve("ofbiz"));
        Path shipped = sandbox.resolve(START_PROPERTIES);
        Files.createDirectories(shipped.getParent());
        Files.copy(repositoryRoot().resolve(START_PROPERTIES), shipped);
        Files.createDirectories(sandbox.resolve("secrets"));
        return sandbox;
    }

    /**
     * Plants {@code declaration} in place of the shipped commented anchor in BOTH the pristine
     * {@code start.properties} and the package qualified override, runs {@code invocation} against the
     * sandbox, and reports which of the two files survived.
     *
     * <p>Both files are written with the same content on purpose: that is what the renderer produces, so a
     * difference in the outcome can only come from which path the verifier was pointed at.</p>
     *
     * @param tempDir a per-test temporary directory for the sandbox and the generated driver scripts
     * @param prefix a name fragment that keeps each leg's sandbox separate and legible
     * @param declaration the {@code ofbiz.admin.key} line both files carry
     * @param invocation the verifier call to execute once the entry point has been sourced
     * @return the run and what it left on disk
     * @throws Exception if the entry point cannot be read or the shell cannot be run
     */
    private static RefusalOutcome verifyRenderedFile(Path tempDir, String prefix, String declaration,
            String invocation) throws Exception {
        Path sandbox = prepareEntryPointSandbox(Files.createTempDirectory(tempDir, prefix));
        Path shipped = sandbox.resolve(START_PROPERTIES);
        String content = Files.readString(shipped, StandardCharsets.UTF_8);
        assertTrue(content.contains(ANCHOR_LINE), "the sandbox copy must carry the shipped anchor to rewrite");
        content = content.replace(ANCHOR_LINE, declaration);
        Files.writeString(shipped, content, StandardCharsets.UTF_8);
        Path override = sandbox.resolve(ADMIN_KEY_OVERRIDE);
        Files.createDirectories(override.getParent());
        Files.writeString(override, content, StandardCharsets.UTF_8);

        EntryPointRun run = runEntryPoint(tempDir, sandbox, Map.of(), invocation);
        return new RefusalOutcome(run, Files.exists(shipped), Files.exists(override));
    }

    /**
     * Runs the real entry point's admin key renderer as a black box against a sandbox.
     *
     * <p>The entry point is sourced with its trailing {@code _main "$@"} line removed so that the single
     * function under test can be invoked without starting OFBiz, and the generated-secret store is
     * redirected into the sandbox because the script otherwise keeps it under the absolute
     * {@code /ofbiz/runtime} path.
     *
     * <p>{@code capture_secret_environment} is invoked first because that is exactly what {@code _main}
     * does: it is the entry point's first statement, and it moves every injected secret out of the
     * exported environment - where any child process could read it - into non exported shell state that
     * the renderer then consumes. Driving the renderer without it would exercise a sequence the container
     * never performs, and the renderer would correctly see no key at all.
     * @param workDir a per-test temporary directory for the generated driver scripts
     * @param sandbox the directory the renderer treats as the OFBiz home
     * @param environment the OFBIZ_* variables to supply; every other OFBIZ_* secret is removed
     * @return the exit code and the combined output of the run
     */
    private static EntryPointRun renderAdminKeyConfiguration(Path workDir, Path sandbox, Map<String, String> environment)
            throws Exception {
        return renderAdminKeyConfiguration(workDir, sandbox, environment, "");
    }

    /**
     * As above, with extra shell lines appended after the render so that a test can observe the state the
     * entry point leaves behind - most usefully the environment a child process would inherit at the
     * point where the data loader, an executable hook or a crash handler would run.
     *
     * @param workDir a per-test temporary directory for the generated driver scripts
     * @param sandbox the directory the renderer treats as the OFBiz home
     * @param environment the OFBIZ_* variables to supply; every other OFBIZ_* secret is removed
     * @param trailingScript shell lines appended after the render, or an empty string for none
     * @return the exit code and the combined output of the run
     * @throws Exception if the entry point cannot be read or the shell cannot be run
     */
    private static EntryPointRun renderAdminKeyConfiguration(Path workDir, Path sandbox, Map<String, String> environment,
            String trailingScript) throws Exception {
        return runEntryPoint(workDir, sandbox, environment,
                "capture_secret_environment\nrender_admin_key_configuration\n" + trailingScript);
    }

    /**
     * Runs an arbitrary sequence of the real entry point's functions as a black box against a sandbox.
     *
     * <p>Extracted from {@link #renderAdminKeyConfiguration} so that the pre-flight and the supplied
     * variable snapshot can be exercised the same way. The entry point is sourced with its trailing
     * {@code _main "$@"} line removed so that the functions under test can be invoked without starting
     * OFBiz, and the generated-secret store is redirected into the sandbox because the script otherwise
     * keeps it under the absolute {@code /ofbiz/runtime} path.</p>
     *
     * @param workDir a per-test temporary directory for the generated driver scripts
     * @param sandbox the directory the functions treat as the OFBiz home
     * @param environment the OFBIZ_* variables to supply; every other OFBIZ_* secret is removed
     * @param invocation the shell statements to execute once the entry point has been sourced
     * @return the exit code and the combined output of the run
     * @throws Exception if the entry point cannot be read or the shell cannot be run
     */
    private static EntryPointRun runEntryPoint(Path workDir, Path sandbox, Map<String, String> environment,
            String invocation) throws Exception {
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
        Path driver = Files.createTempFile(workDir, "entry-point-", ".sh");
        Files.writeString(driver, "#!/usr/bin/env bash\n"
                + ". " + shellQuote(library) + "\n"
                + "CONTAINER_GENERATED_SECRETS_DIR=" + shellQuote(sandbox.resolve("secrets")) + "\n"
                + "cd " + shellQuote(sandbox) + " || exit 1\n"
                + invocation + "\n", StandardCharsets.UTF_8);

        ProcessBuilder builder = new ProcessBuilder("bash", driver.toString());
        builder.directory(sandbox.toFile());
        builder.redirectErrorStream(true);
        Map<String, String> processEnvironment = builder.environment();
        // Both routes to tracing are scrubbed, because stderr is merged into the stream these tests
        // assert against and a value exported into the build's own environment must not be able to turn
        // tracing on inside a case that did not ask for it. SHELLOPTS is read by bash at start up, so an
        // inherited value containing xtrace would switch tracing on before the entry point is even
        // sourced; OFBIZ_TRACE is the entry point's own switch. A case that WANTS tracing puts it back
        // through the environment map below.
        for (String name : List.of("SHELLOPTS", "OFBIZ_TRACE", "OFBIZ_PROFILE", "OFBIZ_ADMIN_KEY",
                "OFBIZ_LOGIN_SECRET_KEY", "OFBIZ_JWT_TOKEN_KEY", "OFBIZ_HOST")) {
            processEnvironment.remove(name);
        }
        processEnvironment.putAll(environment);

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the entry point renderer did not terminate");
        return new EntryPointRun(process.exitValue(), output);
    }

    /**
     * Runs the real stop script with a stubbed {@code curl} first on {@code PATH}, so the payload it
     * would put on the admin socket is captured instead of sent.
     * @param workDir a per-test temporary directory for the stub and its captures
     * @param startProperties the file the script must read
     * @param injectedKey the value of {@code OFBIZ_ADMIN_KEY}, or {@code null} to leave it unset
     * @return what the script printed, what it sent, and how it invoked curl
     */
    private StopScriptRun runStopScript(Path workDir, Path startProperties, String injectedKey) throws Exception {
        Path binDir = Files.createDirectories(workDir.resolve("bin"));
        Path payloadFile = workDir.resolve("curl-stdin.txt");
        Path argumentsFile = workDir.resolve("curl-args.txt");
        Path stub = binDir.resolve("curl");
        Files.writeString(stub, "#!/usr/bin/env bash\n"
                + "printf '%s' \"$*\" > " + shellQuote(argumentsFile) + "\n"
                + "cat > " + shellQuote(payloadFile) + "\n", StandardCharsets.UTF_8);
        assertTrue(stub.toFile().setExecutable(true), "could not make the curl stub executable");

        ProcessBuilder builder = new ProcessBuilder("bash", repositoryRoot().resolve(STOP_SCRIPT).toString());
        builder.directory(repositoryRoot().toFile());
        builder.redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.put("PATH", binDir + java.io.File.pathSeparator + System.getenv("PATH"));
        environment.put("OFBIZ_START_PROPERTIES", startProperties.toString());
        if (injectedKey == null) {
            environment.remove("OFBIZ_ADMIN_KEY");
        } else {
            environment.put("OFBIZ_ADMIN_KEY", injectedKey);
        }

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the stop script did not terminate");
        String payload = Files.exists(payloadFile)
                ? Files.readString(payloadFile, StandardCharsets.UTF_8).stripTrailing()
                : null;
        String arguments = Files.exists(argumentsFile) ? Files.readString(argumentsFile, StandardCharsets.UTF_8) : null;
        return new StopScriptRun(process.exitValue(), output, payload, arguments);
    }

    /** Single-quotes a path for safe interpolation into the generated stub. */
    private static String shellQuote(Path path) {
        return "'" + path.toString().replace("'", "'\\''") + "'";
    }

    private static Properties loadProperties(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static boolean isBashAvailable() {
        try {
            Process process = new ProcessBuilder("bash", "-c", "exit 0").start();
            return process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Path repositoryRoot() {
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("dependencies.gradle"))) {
            candidate = candidate.getParent();
        }
        assertNotNull(candidate, "could not locate the repository root from " + Paths.get("").toAbsolutePath());
        return candidate;
    }

    /** What one black-box execution of an entry point rendering function produced. */
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

    /** What one refused verification left on disk, together with the run that refused it. */
    private static final class RefusalOutcome {
        private final EntryPointRun run;
        private final boolean shippedFilePresent;
        private final boolean overridePresent;

        RefusalOutcome(EntryPointRun run, boolean shippedFilePresent, boolean overridePresent) {
            this.run = run;
            this.shippedFilePresent = shippedFilePresent;
            this.overridePresent = overridePresent;
        }

        EntryPointRun getRun() {
            return run;
        }

        boolean isShippedFilePresent() {
            return shippedFilePresent;
        }

        boolean isOverridePresent() {
            return overridePresent;
        }
    }

    /** What one black-box execution of the stop script produced. */
    private static final class StopScriptRun {
        private final int exitCode;
        private final String output;
        private final String payload;
        private final String curlArguments;

        StopScriptRun(int exitCode, String output, String payload, String curlArguments) {
            this.exitCode = exitCode;
            this.output = output;
            this.payload = payload;
            this.curlArguments = curlArguments;
        }

        int getExitCode() {
            return exitCode;
        }

        String getOutput() {
            return output;
        }

        String getPayload() {
            return payload;
        }

        String getCurlArguments() {
            return curlArguments;
        }
    }
}
