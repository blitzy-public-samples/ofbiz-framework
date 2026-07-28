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
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

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

    @Test
    public void entryPointSuppliesBothSigningKeysAtRuntimeWithoutExposingThem() throws IOException {
        String entryPoint = Files.readString(repositoryRoot().resolve(ENTRY_POINT), StandardCharsets.UTF_8);

        // Removing the build-time generation is only safe because the keys now arrive at run time; a
        // blank security.token.key makes JWTManager throw, so the injection is not optional.
        for (String variable : List.of("OFBIZ_LOGIN_SECRET_KEY", "OFBIZ_JWT_TOKEN_KEY")) {
            assertTrue(entryPoint.contains(variable),
                    "the entry point must resolve " + variable + " now that the build generates no key");
            assertTrue(entryPoint.contains("unset " + variable),
                    variable + " must be unset before OFBiz is executed, so /proc/<pid>/environ cannot expose it");
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
