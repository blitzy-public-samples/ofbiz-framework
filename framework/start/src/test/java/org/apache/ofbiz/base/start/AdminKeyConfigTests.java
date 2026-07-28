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
     * ---------------------------------------------------------------------------------------------
     * The shipped properties file
     * ---------------------------------------------------------------------------------------------
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
     * ---------------------------------------------------------------------------------------------
     * Config resolution
     * ---------------------------------------------------------------------------------------------
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
     * ---------------------------------------------------------------------------------------------
     * The stop script must land on the same effective key
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void stopScriptSendsExactlyTheKeyAndPortConfigResolvesForTheShippedFile(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the stop script");
        System.clearProperty(ADMIN_KEY_PROPERTY);
        System.clearProperty(ADMIN_PORT_PROPERTY);
        Config config = new Config(Collections.emptyList());

        StopScriptRun run = runStopScript(tempDir, repositoryRoot().resolve(START_PROPERTIES), null);

        // The regression this whole class exists for: the script used to derive an EMPTY key from the
        // commented anchor while the server was listening for "NA", so AdminServerContainer rejected
        // every SHUTDOWN and the container never stopped cleanly.
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
        assertTrue(executable.contains("unset OFBIZ_ADMIN_KEY"),
                "the injected key must be removed from the environment OFBiz inherits");
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

        // Stability matters: the key used to be generated once per image, so regenerating it on every
        // restart would be a behaviour change that breaks an in-flight shutdown request.
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
        }
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Helpers
     * ---------------------------------------------------------------------------------------------
     */

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
     * Runs the real entry point's admin key renderer as a black box against a sandbox.
     *
     * <p>The entry point is sourced with its trailing {@code _main "$@"} line removed so that the single
     * function under test can be invoked without starting OFBiz, and the generated-secret store is
     * redirected into the sandbox because the script otherwise keeps it under the absolute
     * {@code /ofbiz/runtime} path.
     * @param workDir a per-test temporary directory for the generated driver scripts
     * @param sandbox the directory the renderer treats as the OFBiz home
     * @param environment the OFBIZ_* variables to supply; every other OFBIZ_* secret is removed
     * @return the exit code and the combined output of the run
     */
    private static EntryPointRun renderAdminKeyConfiguration(Path workDir, Path sandbox, Map<String, String> environment)
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
        Path driver = Files.createTempFile(workDir, "render-admin-key", ".sh");
        Files.writeString(driver, "#!/usr/bin/env bash\n"
                + ". " + shellQuote(library) + "\n"
                + "CONTAINER_GENERATED_SECRETS_DIR=" + shellQuote(sandbox.resolve("secrets")) + "\n"
                + "cd " + shellQuote(sandbox) + " || exit 1\n"
                + "render_admin_key_configuration\n", StandardCharsets.UTF_8);

        ProcessBuilder builder = new ProcessBuilder("bash", driver.toString());
        builder.directory(sandbox.toFile());
        builder.redirectErrorStream(true);
        Map<String, String> processEnvironment = builder.environment();
        for (String name : List.of("OFBIZ_TRACE", "OFBIZ_PROFILE", "OFBIZ_ADMIN_KEY",
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
