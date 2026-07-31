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
package org.apache.ofbiz.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Source anchors for the two externalized secrets in {@code framework/security/config/security.properties}.
 *
 * <p>{@code login.secret_key_string} (forgot-password encryption) and {@code security.token.key} (JWT
 * signature) are supplied at runtime from {@code OFBIZ_LOGIN_SECRET_KEY} and {@code OFBIZ_JWT_TOKEN_KEY}.
 * The repository copy must therefore stay DECLARED BUT BLANK: declared, because the container entry
 * point substitutes over the {@code <key>=} anchor and a missing anchor silently produces a file with
 * no secret; blank, because a committed value is a security regression and also makes the
 * {@code generateSecretKeys} task skip the property instead of filling it in.
 *
 * <p>Every assertion here opens the production file by REPOSITORY PATH on purpose. The security
 * component ships a populated shadow copy at {@code framework/security/src/test/resources/security.properties}
 * which precedes the production copy on the test runtime classpath, so anything that resolved
 * {@code security.properties} through the classloader - {@code UtilProperties} included - would read the
 * populated test fixture and pass no matter what the repository actually contains. One of the tests
 * below pins that shadowing relationship explicitly so the hazard cannot be forgotten.
 *
 * <p>The second half of this suite pins the INTEGRATION TIER's secret path, which is a different
 * problem with the same cause. {@code gradlew ofbiz --test} builds its classpath as
 * {@code sourceSets.main.runtimeClasspath} followed by {@code sourceSets.test.runtimeClasspath}, and every
 * component {@code config} directory is a MAIN resource, so {@code build/resources/main/security.properties}
 * - the blank production copy - preceded the populated fixture and {@code JWTManager.getJWTKey} rejected
 * the empty {@code security.token.key} with "The JWT secret key is too short.". The fix prepends a
 * generated, git-ignored override to that one classpath; the tests below fail if the generator task, the
 * ordering, the git-ignored location, or the verbatim-copy contract is broken.
 */
public final class SecurityPropertiesAnchorTests {

    private static final String LOGIN_SECRET_KEY = "login.secret_key_string";
    private static final String JWT_TOKEN_KEY = "security.token.key";
    private static final String PRODUCTION_FILE = "framework/security/config/security.properties";
    private static final String SHADOW_TEST_FILE = "framework/security/src/test/resources/security.properties";
    private static final String CLASSPATH_RESOURCE = "security.properties";
    private static final String DOCKERFILE = "Dockerfile";
    private static final String ENTRY_POINT = "docker/docker-entrypoint.sh";
    /** generateSecretKeys emits 48 SecureRandom bytes Base64 encoded, i.e. exactly 64 characters. */
    private static final int GENERATED_KEY_LENGTH = 64;

    private static final String BUILD_SCRIPT = "build.gradle";
    private static final String GIT_IGNORE = ".gitignore";
    private static final String OVERRIDE_TASK = "generateIntegrationTestSecurityOverride";
    private static final String KEY_GENERATOR_TASK = "generateSecretKeys";
    private static final String PACKAGING_GUARD_TASK = "verifySigningKeyAnchorsAreBlank";
    private static final String OVERRIDE_DIRECTORY_ACCESSOR = "integrationTestSecurityOverrideDirectory()";
    private static final String OVERRIDE_DIRECTORY_NAME = "integration-test-config";
    private static final String OVERRIDE_FILE = "build/" + OVERRIDE_DIRECTORY_NAME + "/" + CLASSPATH_RESOURCE;
    private static final String MAIN_RUNTIME_CLASSPATH = "sourceSets.main.runtimeClasspath";
    private static final String TEST_RUNTIME_CLASSPATH = "sourceSets.test.runtimeClasspath";
    private static final String OFBIZ_TEST_BRANCH = "if (taskName ==~ /^ofbiz.*(--test|-t).*/) {";
    /** JWTManager.getJWTKey throws when security.token.key is shorter than this (HMAC512, OFBIZ-12724). */
    private static final int JWT_KEY_MIN_LENGTH = 64;

    @ParameterizedTest(name = "{0} is declared blank")
    @ValueSource(strings = {LOGIN_SECRET_KEY, JWT_TOKEN_KEY})
    public void secretIsDeclaredWithAnEmptyValue(String key) throws IOException {
        Properties production = loadProperties(repositoryRoot().resolve(PRODUCTION_FILE));

        // Declared, so the entry point's "s/<key>=.*/<key>=$VALUE/" substitution has an anchor to hit,
        // and empty, so no credential resides in the source tree or in the container image.
        assertNotNull(production.getProperty(key),
                key + " must stay DECLARED: the deploy-time substitution anchors on it");
        assertEquals("", production.getProperty(key),
                key + " must ship BLANK: a committed value is a security regression");
    }

    @ParameterizedTest(name = "{0} is declared exactly once with nothing after the '='")
    @ValueSource(strings = {LOGIN_SECRET_KEY, JWT_TOKEN_KEY})
    public void secretIsDeclaredExactlyOnceAndCarriesNoValueOrPlaceholder(String key) throws IOException {
        List<String> declarations = declarationsOf(key, repositoryRoot().resolve(PRODUCTION_FILE));

        // Exactly one declaration: a second one would win at load time and could hide a committed key
        // behind an apparently blank first line. And the value must be nothing at all - that single
        // equality simultaneously rejects a live key, a generated 64-char key, a ${...} expression, an
        // @TOKEN@ marker and an "example" value. UtilProperties performs no environment expansion, so
        // any placeholder would be read back verbatim as the secret.
        assertEquals(List.of(key + "="), declarations,
                key + " must be declared exactly once, with an empty value and no placeholder");
    }

    @Test
    public void productionFileContainsNoValueThatLooksLikeAGeneratedKey() throws IOException {
        Path production = repositoryRoot().resolve(PRODUCTION_FILE);
        List<String> suspicious = new ArrayList<>();
        for (String line : Files.readAllLines(production, StandardCharsets.UTF_8)) {
            if (line.startsWith("#") || !line.contains("=")) {
                continue;
            }
            String value = line.substring(line.indexOf('=') + 1).trim();
            if (value.length() >= GENERATED_KEY_LENGTH && value.matches("[A-Za-z0-9+/=]+")) {
                suspicious.add(line);
            }
        }

        // Catches a key pasted onto some other property, or a renamed secret, which the two anchored
        // assertions above would not see.
        assertEquals(List.of(), suspicious, "these lines look like committed Base64 secrets");
    }

    @Test
    public void productionFileDoesNotReuseTheShadowFixtureKeys() throws IOException {
        String production = Files.readString(repositoryRoot().resolve(PRODUCTION_FILE), StandardCharsets.UTF_8);
        Properties shadow = loadProperties(repositoryRoot().resolve(SHADOW_TEST_FILE));

        for (String key : List.of(LOGIN_SECRET_KEY, JWT_TOKEN_KEY)) {
            String fixtureValue = shadow.getProperty(key);
            assertNotNull(fixtureValue, "the shadow fixture should declare " + key);
            // Copying the fixture value into the production file would be the easiest way to
            // accidentally "fix" a blank-key problem while committing a live-looking secret.
            assertFalse(production.contains(fixtureValue),
                    "the production file must not carry the test fixture value of " + key);
        }
    }

    @Test
    public void shadowTestFixtureKeepsItsOwnPopulatedKeys() throws IOException {
        Properties shadow = loadProperties(repositoryRoot().resolve(SHADOW_TEST_FILE));

        // The existing security unit tests need real key material; that is exactly why the fixture is
        // populated and why the production file can stay blank without breaking them.
        for (String key : List.of(LOGIN_SECRET_KEY, JWT_TOKEN_KEY)) {
            String value = shadow.getProperty(key);
            assertNotNull(value, "the shadow fixture must declare " + key);
            assertEquals(GENERATED_KEY_LENGTH, value.length(), "the shadow fixture value for " + key + " should be 64 chars");
            assertNotEquals("", value, "the shadow fixture value for " + key + " must not be blank");
        }
    }

    @Test
    public void classpathSecurityPropertiesResolvesToTheShadowFixtureNotTheProductionFile() throws IOException {
        URL resource = getClass().getClassLoader().getResource(CLASSPATH_RESOURCE);
        assertNotNull(resource, "security.properties should be resolvable on the test classpath");
        String fromClasspath;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(CLASSPATH_RESOURCE)) {
            assertNotNull(stream, "security.properties should be readable from the test classpath");
            fromClasspath = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        String shadow = Files.readString(repositoryRoot().resolve(SHADOW_TEST_FILE), StandardCharsets.UTF_8);
        String production = Files.readString(repositoryRoot().resolve(PRODUCTION_FILE), StandardCharsets.UTF_8);

        // THIS is why every assertion above reads the file by repository path. On the test runtime
        // classpath the populated fixture shadows the production copy, so a classpath or UtilProperties
        // based check would inspect key material that is not the shipped file and would pass while a
        // real secret sat in the repository.
        assertEquals(normalize(shadow), normalize(fromClasspath),
                "the classpath copy of security.properties is expected to be the populated test fixture");
        assertNotEquals(normalize(production), normalize(fromClasspath),
                "the classpath copy must not be mistaken for the shipped production file");
        assertTrue(loadProperties(repositoryRoot().resolve(SHADOW_TEST_FILE)).getProperty(LOGIN_SECRET_KEY).length() > 0,
                "the shadowing fixture is only a hazard because it is populated");
    }

    @Test
    public void containerImageBuildNeverGeneratesTheSigningKeysIntoTheImage() throws IOException {
        String dockerfile = Files.readString(repositoryRoot().resolve(DOCKERFILE), StandardCharsets.UTF_8);

        // generateSecretKeys rewrites the production file IN PLACE. Running it as part of the image build
        // wrote a live signing key into the distribution tarball and into a layer of every image built
        // from it, where a runtime override cannot erase it and anyone able to pull the image can read
        // it. The build step must therefore assemble the distribution and nothing else.
        assertFalse(dockerfile.contains("\"generateSecretKeys\","),
                "the image build must not invoke generateSecretKeys: it bakes a live signing key into a layer");
        assertTrue(dockerfile.contains("[\"./gradlew\", \"--console\", \"plain\", \"distTar\"]"),
                "the image build must still assemble the distribution");
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
    public void entryPointSuppliesBothSigningKeysAtRuntimeWithoutExposingThem() throws IOException {
        String entryPoint = Files.readString(repositoryRoot().resolve(ENTRY_POINT), StandardCharsets.UTF_8);

        // Removing the build-time generation is only safe because the keys now arrive at run time; a
        // blank security.token.key makes JWTManager throw, so the injection is not optional.
        for (String variable : List.of("OFBIZ_LOGIN_SECRET_KEY", "OFBIZ_JWT_TOKEN_KEY")) {
            assertTrue(entryPoint.contains(variable),
                    "the entry point must resolve " + variable + " now that the build generates no key");
            assertTrue(withdrawnBeforeExec(entryPoint, variable),
                    variable + " must be withdrawn before OFBiz is executed, so /proc/<pid>/environ cannot "
                            + "expose it");
        }
        for (String key : List.of(LOGIN_SECRET_KEY, JWT_TOKEN_KEY)) {
            assertTrue(entryPoint.contains(key + "=%s"),
                    "the entry point must substitute a runtime value for " + key);
        }
        // A secret passed as a sed command line argument would be visible in the process table, so the
        // substitutions must be delivered through a sed program file instead.
        assertTrue(entryPoint.contains("--file=\"$sedScript\""),
                "secret substitutions must be delivered through a sed program file, not --expression");
    }

    @Test
    public void integrationTestClasspathPrependsTheSyntheticSecurityOverrideBeforeTheMainResources() throws IOException {
        String testBranch = integrationTestClasspathBranch();

        // Without the dependency the directory would be empty on a clean checkout and the blank
        // production copy would win again, silently, with no build failure to point at.
        assertTrue(testBranch.contains("dependsOn '" + OVERRIDE_TASK + "'"),
                "the 'ofbiz --test' task must build the synthetic security override before it runs");

        int overrideAt = testBranch.indexOf("files(" + OVERRIDE_DIRECTORY_ACCESSOR + ")");
        int mainAt = testBranch.indexOf(MAIN_RUNTIME_CLASSPATH);
        int testAt = testBranch.indexOf(TEST_RUNTIME_CLASSPATH);
        assertTrue(overrideAt >= 0, "the 'ofbiz --test' classpath must include " + OVERRIDE_DIRECTORY_ACCESSOR);
        assertTrue(mainAt >= 0, "the 'ofbiz --test' classpath must still include " + MAIN_RUNTIME_CLASSPATH);
        assertTrue(testAt >= 0, "the 'ofbiz --test' classpath must still include " + TEST_RUNTIME_CLASSPATH);
        // ORDER is the whole point: ClassLoader.getResource returns the FIRST match and UtilProperties
        // never merges, so the override has to precede build/resources/main.
        assertTrue(overrideAt < mainAt,
                "the synthetic security override must PRECEDE " + MAIN_RUNTIME_CLASSPATH + " or the blank key wins again");
        assertTrue(mainAt < testAt, "the main runtime classpath must still precede the test runtime classpath");
    }

    @Test
    public void everyOtherOfbizInvocationAndTheDistributionKeepTheUntouchedClasspath() throws IOException {
        String plainBranch = plainOfbizClasspathBranch();
        String buildScript = Files.readString(repositoryRoot().resolve(BUILD_SCRIPT), StandardCharsets.UTF_8);

        // A plain 'gradlew ofbiz' run, and therefore anything a developer or an image serves from, must
        // never see synthetic key material; it keeps resolving whatever the deployment supplied.
        assertTrue(plainBranch.contains("classpath = " + MAIN_RUNTIME_CLASSPATH),
                "a plain 'ofbiz' run must keep the untouched main runtime classpath");
        assertFalse(plainBranch.contains(OVERRIDE_DIRECTORY_ACCESSOR),
                "a plain 'ofbiz' run must not see the synthetic security override");
        assertFalse(plainBranch.contains(OVERRIDE_TASK),
                "a plain 'ofbiz' run must not depend on the synthetic security override generator");
        // distributions.main copies source trees only, so a build directory artefact can never be
        // packaged into the tarball the container image is built from.
        assertTrue(buildScript.contains("include 'framework/**', 'applications/**', 'themes/**', 'plugins/**'"),
                "the distribution must keep copying source trees only");
    }

    @Test
    public void theSyntheticOverrideIsGeneratedOnlyIntoTheGitIgnoredBuildDirectory() throws IOException {
        String buildScript = Files.readString(repositoryRoot().resolve(BUILD_SCRIPT), StandardCharsets.UTF_8);
        List<String> ignored = Files.readAllLines(repositoryRoot().resolve(GIT_IGNORE), StandardCharsets.UTF_8);

        assertTrue(buildScript.contains("task " + OVERRIDE_TASK),
                BUILD_SCRIPT + " must declare the " + OVERRIDE_TASK + " task");
        assertTrue(buildScript.contains("layout.buildDirectory.dir('" + OVERRIDE_DIRECTORY_NAME + "')"),
                "the override must be generated under the build directory, not into the source tree");
        assertTrue(ignored.contains("build/"),
                "the build directory must stay git-ignored so the synthetic key can never be committed");
        // A world-readable file would leak the value to any other account on the build host; the entry
        // point renders its own overrides at 0600 for the same reason.
        assertTrue(buildScript.contains("PosixFilePermissions.fromString('rw-------')"),
                "the generated override must be written with owner-only permissions");
        assertTrue(buildScript.contains("outputs.file overrideFile"),
                "the generated override must be declared as a task output so Gradle regenerates it when stale");
        assertTrue(buildScript.contains("inputs.file productionSecurityProperties"),
                "the production file must be declared as a task input so an edit to it regenerates the override");
    }

    @Test
    public void theSyntheticSigningKeysClearTheHmac512FloorAndCannotBeMistakenForRealSecrets() throws IOException {
        Map<String, String> synthetic = syntheticIntegrationTestKeys();
        String production = Files.readString(repositoryRoot().resolve(PRODUCTION_FILE), StandardCharsets.UTF_8);
        Properties shadow = loadProperties(repositoryRoot().resolve(SHADOW_TEST_FILE));

        assertEquals(2, synthetic.size(), "both signing keys must have a synthetic integration test value");
        for (Map.Entry<String, String> entry : synthetic.entrySet()) {
            String value = entry.getValue();
            assertTrue(value.length() >= JWT_KEY_MIN_LENGTH,
                    entry.getKey() + " must be at least " + JWT_KEY_MIN_LENGTH + " chars or JWTManager rejects it");
            // Restricting the alphabet keeps the value free of java.util.Properties metacharacters, so the
            // generated line never needs escaping and cannot be mangled into a shorter key.
            assertTrue(value.matches("[A-Za-z0-9]+"),
                    entry.getKey() + " must avoid java.util.Properties metacharacters: " + value);
            assertTrue(value.contains("Synthetic") && value.contains("NotARealSecret"),
                    entry.getKey() + " must describe itself as synthetic so it is never mistaken for a credential");
            assertFalse(production.contains(value),
                    "the synthetic value for " + entry.getKey() + " must never appear in the tracked production file");
            assertNotEquals(shadow.getProperty(entry.getKey()), value,
                    "the synthetic value for " + entry.getKey() + " must be distinguishable from the shadow fixture value");
        }
        assertNotEquals(synthetic.get(LOGIN_SECRET_KEY), synthetic.get(JWT_TOKEN_KEY),
                "the two synthetic keys must differ, exactly as two real keys would");
    }

    @Test
    public void theGeneratedOverrideKeepsEveryProductionSecurityPropertyAndFillsOnlyTheTwoSecrets() throws IOException {
        Path productionPath = repositoryRoot().resolve(PRODUCTION_FILE);
        Properties production = loadProperties(productionPath);
        Map<String, String> synthetic = syntheticIntegrationTestKeys();

        Properties override = loadPropertiesFrom(
                applyOverrideSubstitutions(Files.readString(productionPath, StandardCharsets.UTF_8), synthetic));

        // THIS is why the override is a verbatim copy rather than a small fixture. UtilProperties resolves
        // security.properties to ONE classpath URL and never merges, so an override that declared only the
        // two secrets would delete every other production security property - allow lists, CSRF strategy,
        // password hashing, host headers, upload extension filters - from the whole integration run.
        assertEquals(production.stringPropertyNames(), override.stringPropertyNames(),
                "the override must declare exactly the production property set, no more and no fewer");
        List<String> changed = new ArrayList<>();
        for (String name : production.stringPropertyNames()) {
            if (!production.getProperty(name).equals(override.getProperty(name))) {
                changed.add(name);
            }
        }
        assertEquals(List.of(LOGIN_SECRET_KEY, JWT_TOKEN_KEY), changed.stream().sorted().toList(),
                "only the two signing keys may differ between the production file and the override");
        for (String name : List.of(LOGIN_SECRET_KEY, JWT_TOKEN_KEY)) {
            assertEquals(synthetic.get(name), override.getProperty(name), name + " must carry the synthetic value");
        }
    }

    @Test
    public void theGeneratedOverrideOnDiskMatchesTheDocumentedContract() throws IOException {
        Path overridePath = repositoryRoot().resolve(OVERRIDE_FILE);
        // The unit test task depends on the generator, so the artefact is asserted directly rather than
        // through a re-implementation: a divergence between this contract and the real Groovy task fails here.
        assertTrue(Files.isRegularFile(overridePath),
                OVERRIDE_FILE + " must exist; the 'test' task must depend on " + OVERRIDE_TASK);
        String expected = applyOverrideSubstitutions(
                Files.readString(repositoryRoot().resolve(PRODUCTION_FILE), StandardCharsets.UTF_8),
                syntheticIntegrationTestKeys());

        assertEquals(normalize(expected), normalize(Files.readString(overridePath, StandardCharsets.UTF_8)),
                "the generated override must be the production file with only the two signing keys filled");
        assertTrue(loadPropertiesFrom(Files.readString(overridePath, StandardCharsets.UTF_8))
                        .getProperty(JWT_TOKEN_KEY).length() >= JWT_KEY_MIN_LENGTH,
                "the generated security.token.key must clear the JWTManager floor");
        if (overridePath.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(overridePath),
                    "the generated override must be readable only by its owner");
        }
    }

    @Test
    public void theOverrideGeneratorOverwritesAnAlreadyPopulatedKeyInsteadOfSkippingIt() throws IOException {
        Map<String, String> synthetic = syntheticIntegrationTestKeys();
        String leakedKey = "A".repeat(GENERATED_KEY_LENGTH);
        // A developer may still invoke generateSecretKeys by hand, and it fills the TRACKED production file
        // in place. If the generator skipped an already populated line - the way generateSecretKeys itself
        // does - that real key would be copied into the build directory and the integration tier would be
        // back to depending on a mutated tracked file.
        String populated = applyOverrideSubstitutions(
                Files.readString(repositoryRoot().resolve(PRODUCTION_FILE), StandardCharsets.UTF_8),
                Map.of(LOGIN_SECRET_KEY, leakedKey, JWT_TOKEN_KEY, leakedKey));
        assertTrue(populated.contains(JWT_TOKEN_KEY + "=" + leakedKey), "the populated fixture should carry the leaked key");

        String override = applyOverrideSubstitutions(populated, synthetic);

        assertFalse(override.contains(leakedKey), "a key already present in the production file must not survive into the override");
        for (String name : List.of(LOGIN_SECRET_KEY, JWT_TOKEN_KEY)) {
            assertEquals(synthetic.get(name), loadPropertiesFrom(override).getProperty(name),
                    name + " must be overwritten with the synthetic value, not skipped");
        }
        String buildScript = Files.readString(repositoryRoot().resolve(BUILD_SCRIPT), StandardCharsets.UTF_8);
        assertFalse(integrationTestOverrideTask(buildScript).contains("skipping"),
                "the override generator must not adopt generateSecretKeys' skip-if-already-set behaviour");
    }

    @Test
    public void theOverrideGeneratorFailsLoudlyIfAProductionAnchorDisappears() throws IOException {
        String generator = integrationTestOverrideTask(
                Files.readString(repositoryRoot().resolve(BUILD_SCRIPT), StandardCharsets.UTF_8));

        // Renaming or deleting an anchor would otherwise produce an override that silently lacks the
        // secret, which looks exactly like the bug this whole mechanism exists to fix.
        assertTrue(generator.contains("throw new GradleException("),
                "the override generator must fail the build when a production anchor is missing");
        for (String key : List.of(LOGIN_SECRET_KEY, JWT_TOKEN_KEY)) {
            String anchorless = Files.readString(repositoryRoot().resolve(PRODUCTION_FILE), StandardCharsets.UTF_8)
                    .replaceAll("(?m)^#?" + key.replace(".", "\\.") + "=.*$", "");
            assertFalse(Pattern.compile("(?m)^#?" + key.replace(".", "\\.") + "=.*$").matcher(anchorless).find(),
                    "removing the " + key + " anchor must leave nothing for the generator to substitute");
        }
    }

    @Test
    public void theIntegrationTierDoesNotRelyOnGenerateSecretKeysRewritingTheTrackedFile() throws IOException {
        String buildScript = Files.readString(repositoryRoot().resolve(BUILD_SCRIPT), StandardCharsets.UTF_8);

        // testIntegration is 'ofbiz --test' and nothing else, so the synthetic override on that task's
        // classpath is the only thing that supplies key material. generateSecretKeys survives untouched as
        // a developer convenience, but the integration tier must pass with the tracked file left blank.
        assertFalse(integrationTestClasspathBranch().contains("generateSecretKeys"),
                "the integration test classpath must not depend on generateSecretKeys rewriting the tracked file");
        assertTrue(buildScript.contains("task testIntegration(group: ofbizServer) {\n    dependsOn 'ofbiz --test'"),
                "testIntegration must still be exactly 'ofbiz --test'");
        assertTrue(buildScript.contains("def propertiesFile = file('framework/security/config/security.properties')"),
                "generateSecretKeys must keep writing only the tracked production file it always wrote");
        assertTrue(buildScript.contains("dependsOn '" + OVERRIDE_TASK + "'"),
                "the unit and integration tiers must both build the override from the task, never by hand");
    }

    @Test
    public void theRequiredDataLoadWorkflowDoesNotWriteASigningKeyIntoTheTrackedFile() throws IOException {
        String buildScript = Files.readString(repositoryRoot().resolve(BUILD_SCRIPT), StandardCharsets.UTF_8);

        // 'gradlew loadAll' is the documented, REQUIRED step before running or testing OFBiz, so having it
        // depend on generateSecretKeys meant the ordinary workflow populated the tracked file - and the
        // 'config' resource srcDir then carried that value into build/resources/main, into ofbiz.jar and
        // into ofbiz.tar, i.e. into an image layer from which it can never be withdrawn.
        String loadAll = declarationOf("task loadAll(group: ofbizServer)", buildScript);
        assertTrue(loadAll.contains("dependsOn 'ofbiz --load-data'"), "loadAll must still run the data loader");
        assertEquals(1, loadAll.split("dependsOn", -1).length - 1,
                "loadAll must declare exactly one dependsOn, on the data loader alone: " + loadAll);
        assertFalse(loadAll.contains(KEY_GENERATOR_TASK),
                "loadAll must not run " + KEY_GENERATOR_TASK + ": loading data needs no signing key");
        // Any other wiring would reintroduce the same defect through a different route, so no task at all
        // may depend on the generator.
        assertFalse(buildScript.contains("dependsOn '" + KEY_GENERATOR_TASK + "'"),
                KEY_GENERATOR_TASK + " must stay a manual task; nothing may depend on it");
        assertFalse(buildScript.contains("\"" + KEY_GENERATOR_TASK + "\","),
                KEY_GENERATOR_TASK + " must not be listed in a task argument array");
    }

    @Test
    public void everyPackagingTaskRefusesToRunWhileASigningKeyAnchorIsPopulated() throws IOException {
        String buildScript = Files.readString(repositoryRoot().resolve(BUILD_SCRIPT), StandardCharsets.UTF_8);
        String guard = declarationOf("task " + PACKAGING_GUARD_TASK, buildScript);

        assertTrue(buildScript.contains("task " + PACKAGING_GUARD_TASK),
                BUILD_SCRIPT + " must declare the " + PACKAGING_GUARD_TASK + " guard");
        // Both packaging inputs: the tracked source, which distributions.main copies into ofbiz.tar, and
        // the processed copy under build/resources/main, which 'jar' seals into ofbiz.jar. A stale
        // populated copy left behind by an earlier build must not survive into the archive either.
        assertTrue(guard.contains("framework/security/config/security.properties"),
                "the guard must read the tracked security.properties");
        assertTrue(guard.contains("resources/main/security.properties"),
                "the guard must also read the processed copy that ends up inside ofbiz.jar");
        assertTrue(guard.contains("mustRunAfter 'processResources'"),
                "the guard must run after processResources so it sees the final processed copy");
        // java.util.Properties applies the same canonicalization the running application does, so a value
        // hidden behind escaping or a continuation line still counts as populated.
        assertTrue(guard.contains("new Properties()"),
                "the guard must parse the files with java.util.Properties, not with a regular expression");
        assertTrue(guard.contains("throw new GradleException("),
                "the guard must fail the build rather than warn");
        assertTrue(guard.contains("outputs.upToDateWhen { false }"),
                "the guard must never be skipped as up to date");
        assertTrue(buildScript.contains("def packagedSigningKeyAnchors = ['" + LOGIN_SECRET_KEY + "', '" + JWT_TOKEN_KEY + "']"),
                "the guard must cover both signing keys");

        // Wiring: everything that turns this tree into a distributable artefact.
        for (String wiring : List.of("tasks.named('jar') { dependsOn '" + PACKAGING_GUARD_TASK + "' }",
                "tasks.named('installDist') { dependsOn '" + PACKAGING_GUARD_TASK + "' }",
                "tasks.withType(Tar).configureEach { dependsOn '" + PACKAGING_GUARD_TASK + "' }",
                "tasks.withType(Zip).configureEach { dependsOn '" + PACKAGING_GUARD_TASK + "' }")) {
            assertTrue(buildScript.contains(wiring), BUILD_SCRIPT + " must contain: " + wiring);
        }
    }

    @Test
    public void theProcessedResourceCopyThatIsSealedIntoTheJarCarriesBlankAnchors() throws IOException {
        // The artefact itself, not the build script: 'test' depends on 'classes', so processResources has
        // already produced this copy by the time this assertion runs. It is the exact byte stream 'jar'
        // packages, so a populated value here would be a populated value inside ofbiz.jar.
        Path processed = repositoryRoot().resolve("build/resources/main/" + CLASSPATH_RESOURCE);
        assertTrue(Files.isRegularFile(processed),
                processed + " must exist; the unit test tier depends on processResources");

        Properties packaged = loadProperties(processed);
        for (String key : List.of(LOGIN_SECRET_KEY, JWT_TOKEN_KEY)) {
            assertNotNull(packaged.getProperty(key), key + " must stay declared in the packaged copy");
            assertEquals("", packaged.getProperty(key),
                    key + " must be blank in the packaged copy or the value is readable inside ofbiz.jar");
        }
    }

    /** The declaration that starts with {@code header}, up to its closing brace at column zero. */
    private static String declarationOf(String header, String buildScript) {
        int start = buildScript.indexOf(header);
        assertTrue(start > 0, BUILD_SCRIPT + " must declare " + header);
        int end = buildScript.indexOf("\n}\n", start);
        assertTrue(end > start, header + " must be closed");
        return buildScript.substring(start, end);
    }

    /** The body of the {@code --test} branch of {@code createOfbizCommandTask}: the integration test classpath. */
    private static String integrationTestClasspathBranch() throws IOException {
        String buildScript = Files.readString(repositoryRoot().resolve(BUILD_SCRIPT), StandardCharsets.UTF_8);
        int branch = buildScript.indexOf(OFBIZ_TEST_BRANCH);
        assertTrue(branch > 0, BUILD_SCRIPT + " must still branch the 'ofbiz --test' classpath on " + OFBIZ_TEST_BRANCH);
        int elseBranch = buildScript.indexOf("} else {", branch);
        assertTrue(elseBranch > branch, "the '--test' branch must still be followed by the plain 'ofbiz' branch");
        return buildScript.substring(branch, elseBranch);
    }

    /** The body of the {@code else} branch of {@code createOfbizCommandTask}: every non-test invocation. */
    private static String plainOfbizClasspathBranch() throws IOException {
        String buildScript = Files.readString(repositoryRoot().resolve(BUILD_SCRIPT), StandardCharsets.UTF_8);
        int elseBranch = buildScript.indexOf("} else {", buildScript.indexOf(OFBIZ_TEST_BRANCH));
        assertTrue(elseBranch > 0, "the plain 'ofbiz' classpath branch must still exist");
        int end = buildScript.indexOf("mainClass = application.mainClass", elseBranch);
        assertTrue(end > elseBranch, "createOfbizCommandTask must still configure the main class after the branches");
        return buildScript.substring(elseBranch, end);
    }

    /** The declaration of the {@code generateIntegrationTestSecurityOverride} task. */
    private static String integrationTestOverrideTask(String buildScript) {
        int start = buildScript.indexOf("task " + OVERRIDE_TASK);
        assertTrue(start > 0, BUILD_SCRIPT + " must declare the " + OVERRIDE_TASK + " task");
        int end = buildScript.indexOf("\n}\n", start);
        assertTrue(end > start, "the " + OVERRIDE_TASK + " task declaration must be closed");
        return buildScript.substring(start, end);
    }

    /** The synthetic signing keys declared in {@code build.gradle}, read from the build script itself. */
    private static Map<String, String> syntheticIntegrationTestKeys() throws IOException {
        String buildScript = Files.readString(repositoryRoot().resolve(BUILD_SCRIPT), StandardCharsets.UTF_8);
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put(LOGIN_SECRET_KEY, declaredGroovyString(buildScript, "integrationTestLoginSecretKey"));
        keys.put(JWT_TOKEN_KEY, declaredGroovyString(buildScript, "integrationTestJwtTokenKey"));
        return keys;
    }

    private static String declaredGroovyString(String buildScript, String variable) {
        Matcher matcher = Pattern.compile("(?m)^def " + variable + " = '([^']*)'$").matcher(buildScript);
        assertTrue(matcher.find(), BUILD_SCRIPT + " must declare " + variable);
        return matcher.group(1);
    }

    /**
     * The substitution the {@code generateIntegrationTestSecurityOverride} task performs: rewrite each named
     * declaration in place, whether it was blank, populated or commented out, and change nothing else.
     */
    private static String applyOverrideSubstitutions(String content, Map<String, String> replacements) {
        String result = content;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            String declaration = "(?m)^#?" + entry.getKey().replace(".", "\\.") + "=.*$";
            assertTrue(Pattern.compile(declaration).matcher(result).find(),
                    "no declaration of " + entry.getKey() + " to substitute");
            result = result.replaceAll(declaration,
                    Matcher.quoteReplacement(entry.getKey() + "=" + entry.getValue()));
        }
        return result;
    }

    private static Properties loadPropertiesFrom(String content) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = new StringReader(content)) {
            properties.load(reader);
        }
        return properties;
    }

    private static String normalize(String content) {
        return content.replace("\r\n", "\n");
    }

    /** Every line that assigns {@code key}, so duplicates are visible rather than silently overridden. */
    private static List<String> declarationsOf(String key, Path file) throws IOException {
        List<String> declarations = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.startsWith(key + "=")) {
                declarations.add(line);
            }
        }
        return declarations;
    }

    private static Properties loadProperties(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static Path repositoryRoot() {
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("dependencies.gradle"))) {
            candidate = candidate.getParent();
        }
        assertNotNull(candidate, "could not locate the repository root from " + Paths.get("").toAbsolutePath());
        return candidate;
    }
}
