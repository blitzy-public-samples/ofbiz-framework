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
package org.apache.ofbiz.content.data.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.cache.UtilCache;
import org.junit.jupiter.api.Test;

/**
 * Configuration contract of the committed {@code applications/content/config/content.properties}, and of the
 * object-storage client those keys configure.
 *
 * <p>These seven {@code content.store.*} keys are the switch that decides whether content bytes live in the
 * database, on local disk, or in an S3-compatible object store. Two properties of the committed state matter
 * enough to be pinned:
 * <ul>
 * <li>{@code content.store.provider=database}, so an unconfigured checkout keeps the pre-existing
 *     {@code DataResource} database-storage path and the object-storage code - client library included - stays
 *     completely inert;</li>
 * <li>every S3 bucket, region, endpoint and credential key is DECLARED but BLANK, so the keys exist as
 *     substitution anchors for the container entry point while no credential is committed to the repository or
 *     baked into an image.</li>
 * </ul>
 *
 * <p>A key that is silently renamed is as damaging as one with a wrong value: {@code ContentStoreFactory} reads
 * these names, and a rename would make the factory fall back to its default while the operator's environment
 * variable is quietly ignored. So all seven are compared as one complete structured map, and each is also
 * checked to be declared exactly once - a duplicated key would leave the effective value depending on file
 * order.
 *
 * <p>The file is read DIRECTLY from the repository by path. It is deliberately not resolved through
 * {@code UtilProperties}, whose lookup goes via the classpath and a global cache, and not copied into a test
 * resource - either route could keep passing while the real file regressed. One test does additionally exercise
 * the blank-value fallback semantics the file's own comment claims, and that test clears the global properties
 * cache before and after so it neither depends on nor leaves behind cached state.
 */
public final class ContentStorePropertiesContractTests {

    private static final String CONTENT_PROPERTIES = "applications/content/config/content.properties";
    private static final String DEPENDENCY_MANIFEST = "dependencies.gradle";

    private static final String PROVIDER = "content.store.provider";
    private static final String BUCKET = "content.store.s3.bucket";
    private static final String REGION = "content.store.s3.region";
    private static final String ENDPOINT = "content.store.s3.endpoint";
    private static final String ACCESS_KEY_ID = "content.store.s3.access.key.id";
    private static final String SECRET_ACCESS_KEY = "content.store.s3.secret.access.key";
    private static final String PATH_STYLE = "content.store.s3.path.style";

    private static final String UPLOAD_PREFIX = "content.upload.path.prefix";

    private static final String S3_CLIENT = "software.amazon.awssdk.services.s3.S3Client";
    private static final String S3_CLIENT_BUILDER = "software.amazon.awssdk.services.s3.S3ClientBuilder";
    private static final String S3_COORDINATE = "software.amazon.awssdk:s3:2.49.1";
    private static final String IMPLEMENTATION = "implementation";

    /** The complete committed state of the storage-provider configuration. */
    private static final Map<String, String> EXPECTED_STORE_KEYS = Map.of(
            PROVIDER, "database",
            BUCKET, "",
            REGION, "",
            ENDPOINT, "",
            ACCESS_KEY_ID, "",
            SECRET_ACCESS_KEY, "",
            PATH_STYLE, "false");

    /** The five keys that must never carry a committed value. */
    private static final List<String> DEPLOYMENT_SUPPLIED_KEYS = List.of(BUCKET, REGION, ENDPOINT, ACCESS_KEY_ID, SECRET_ACCESS_KEY);

    /** Matches {@code implementation 'a:b:c'} and {@code implementation('a:b:c') {} } alike. */
    private static final Pattern DEPENDENCY_DECLARATION = Pattern.compile("^\\s*(\\w+)\\s*\\(?\\s*'([^']+)'");

    /** The {@code UtilProperties} resource cache, cleared around the one test that reads through it. */
    private static final String PROPERTIES_URL_CACHE = "properties.UtilPropertiesUrlCache";

    /*
     * ---------------------------------------------------------------------------------------------
     * The seven keys, as one complete structured comparison
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void allSevenStorageKeysAreDeclaredWithTheirCommittedDefaults() {
        Map<String, String> declared = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> assignment : assignments().entrySet()) {
            if (assignment.getKey().startsWith("content.store.")) {
                declared.put(assignment.getKey(), assignment.getValue().get(0));
            }
        }

        // One assertion for the whole key set, so a renamed key, an extra key, a removed key and a changed
        // default are all caught by the same comparison. database + blank S3 settings is the state that keeps an
        // unconfigured checkout on the pre-existing DataResource database path with the S3 code path never invoked.
        assertEquals(EXPECTED_STORE_KEYS, declared, "the complete content.store.* configuration");
    }

    @Test
    public void everyStorageKeyIsDeclaredExactlyOnce() {
        Map<String, List<String>> assignments = assignments();

        // A duplicated key makes the effective value depend on file order, which is exactly the kind of drift an
        // environment-substituted copy of this file could introduce unnoticed.
        for (String key : EXPECTED_STORE_KEYS.keySet()) {
            assertEquals(1, assignments.getOrDefault(key, List.of()).size(), key + " must be declared exactly once");
        }
    }

    @Test
    public void noCredentialEndpointOrBucketIsCommitted() {
        Map<String, List<String>> assignments = assignments();

        for (String key : DEPLOYMENT_SUPPLIED_KEYS) {
            String value = assignments.get(key).get(0);
            assertEquals("", value, key + " must be blank: its value comes from the deployment environment");
            // A blank value is the only acceptable form. A placeholder would be worse than useless here, because
            // nothing in a .properties file expands one - ContentStoreFactory would receive the literal text.
            assertFalse(value.contains("${"), key + " must not carry a ${...} placeholder: properties files do not expand them");
            assertFalse(value.contains("@"), key + " must not carry an @TOKEN@ placeholder");
        }

        // Belt and braces over the whole file: no assignment anywhere may look like an AWS access key id or a
        // long opaque secret, whichever key it happens to be attached to.
        for (Map.Entry<String, List<String>> assignment : assignments().entrySet()) {
            for (String value : assignment.getValue()) {
                assertFalse(value.matches("(?i)(AKIA|ASIA)[0-9A-Z]{12,}"),
                        assignment.getKey() + " looks like a committed AWS access key id");
                assertFalse(value.matches("[A-Za-z0-9+/=]{40,}"),
                        assignment.getKey() + " looks like a committed opaque secret");
            }
        }
    }

    @Test
    public void theFilesystemProviderRootTheseKeysBuildOnIsUnchanged() {
        // The filesystem provider is documented as rooted at content.upload.path.prefix, so promoting a storage
        // provider selector must not have disturbed the historical upload location that provider inherits.
        assertEquals("runtime/uploads", assignments().get(UPLOAD_PREFIX).get(0), UPLOAD_PREFIX + " must keep its historical value");
    }

    @Test
    public void aBlankValueSelfDefaultsThroughTheProductionPropertyLookup() {
        // The file's own comment claims "an empty value self-defaults: UtilProperties returns the caller's default
        // when a value is blank". That is the mechanism by which the committed blank S3 keys leave the provider
        // unconfigured, so it is verified through the real lookup rather than taken on trust.
        // The global resource cache is cleared before AND after, so this test neither depends on nor leaves
        // behind cached state - the file itself is only ever read, never modified.
        UtilCache.clearCache(PROPERTIES_URL_CACHE);
        try {
            String fallback = "unconfigured-sentinel";
            assertEquals(fallback, UtilProperties.getPropertyValue("content", BUCKET, fallback),
                    "a blank " + BUCKET + " must fall back to the caller's default");
            assertEquals(fallback, UtilProperties.getPropertyValue("content", SECRET_ACCESS_KEY, fallback),
                    "a blank " + SECRET_ACCESS_KEY + " must fall back to the caller's default");
            // ...while a declared, non-blank value is returned as written rather than being defaulted away.
            assertEquals("database", UtilProperties.getPropertyValue("content", PROVIDER, fallback),
                    PROVIDER + " must resolve to the committed value, not to the default");
        } finally {
            UtilCache.clearCache(PROPERTIES_URL_CACHE);
        }
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * The object-storage client those keys configure must actually be supplied
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void theS3ClientTypeIsSuppliedOnTheClasspathWithoutBuildingAClient() throws Exception {
        // initialize=false: the types are resolved but no static initialiser runs, no client is built, no region
        // or endpoint is resolved and no credential provider chain is consulted. Nothing here touches a network.
        Class<?> client = Class.forName(S3_CLIENT, false, getClass().getClassLoader());
        Class<?> builder = Class.forName(S3_CLIENT_BUILDER, false, getClass().getClassLoader());

        assertTrue(client.isInterface(), S3_CLIENT + " is expected to be the SDK v2 client interface");
        // The builder is reached only as a TYPE. Its presence is what makes endpointOverride and forcePathStyle -
        // the two settings that let one client target any S3-compatible store - available to the provider.
        assertTrue(builder.isInterface(), S3_CLIENT_BUILDER + " is expected to be an interface");
        assertEquals(builder, client.getMethod("builder").getReturnType(), "S3Client.builder() must return " + S3_CLIENT_BUILDER);
        for (String setting : List.of("endpointOverride", "forcePathStyle")) {
            assertTrue(hasMethodNamed(builder, setting),
                    S3_CLIENT_BUILDER + " must expose " + setting + ", which is what makes a non-AWS S3-compatible store reachable");
        }
    }

    @Test
    public void theS3ModuleIsDeclaredExactlyOnceAsAnImplementationDependency() {
        Map<String, List<String>> declarations = dependencyDeclarations();

        // Only the s3 module is wanted, not the whole SDK bundle, and it belongs on the compile classpath because
        // the provider will import it directly. Scope and version are both asserted: a mis-scoped or duplicated
        // module still resolves locally while changing what ships.
        assertEquals(List.of(IMPLEMENTATION), declarations.get(S3_COORDINATE),
                S3_COORDINATE + " must be declared exactly once, as " + IMPLEMENTATION);
        assertEquals(List.of(S3_COORDINATE), coordinatesFor(declarations, "software.amazon.awssdk:s3"),
                "exactly one version of the AWS SDK s3 module may be declared");
        // No other AWS SDK artifact may creep in: the footprint is deliberately one module.
        assertEquals(List.of(S3_COORDINATE), coordinatesFor(declarations, "software.amazon.awssdk"),
                "only the s3 module of the AWS SDK may be declared");
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Helpers
     * ---------------------------------------------------------------------------------------------
     */

    private static boolean hasMethodNamed(Class<?> type, String name) {
        return Arrays.stream(type.getMethods()).anyMatch(method -> name.equals(method.getName()));
    }

    /**
     * Every {@code key=value} assignment in the production properties file, as key to the list of values it is
     * assigned - a list, so that a duplicated key is visible rather than being silently collapsed the way
     * {@link java.util.Properties} would collapse it.
     */
    private static Map<String, List<String>> assignments() {
        Map<String, List<String>> byKey = new LinkedHashMap<>();
        for (String line : linesOf(repositoryRoot().resolve(CONTENT_PROPERTIES))) {
            String trimmed = line.strip();
            int separator = trimmed.indexOf('=');
            if (trimmed.startsWith("#") || trimmed.startsWith("!") || separator <= 0) {
                continue;
            }
            byKey.computeIfAbsent(trimmed.substring(0, separator).strip(), key -> new ArrayList<>())
                    .add(trimmed.substring(separator + 1).strip());
        }
        // Sanity check on the parse itself, so a silently empty or broken map can never make the assertions above
        // vacuous: an unrelated pre-existing key of the same file must have come through with its own value.
        assertEquals(List.of("10"), byKey.get("viewSize"), "the parse must pick up the file's unrelated pre-existing keys too");
        assertTrue(byKey.size() > EXPECTED_STORE_KEYS.size(), "the properties file must have parsed into more than the store keys alone");
        return byKey;
    }

    /** Every dependency declaration in the manifest, as coordinate to the list of scopes it is declared under. */
    private static Map<String, List<String>> dependencyDeclarations() {
        Map<String, List<String>> byCoordinate = new LinkedHashMap<>();
        for (String line : linesOf(repositoryRoot().resolve(DEPENDENCY_MANIFEST))) {
            Matcher matcher = DEPENDENCY_DECLARATION.matcher(line);
            if (matcher.find()) {
                byCoordinate.computeIfAbsent(matcher.group(2), key -> new ArrayList<>()).add(matcher.group(1));
            }
        }
        return byCoordinate;
    }

    private static List<String> coordinatesFor(Map<String, List<String>> declarations, String prefix) {
        return declarations.keySet().stream().filter(coordinate -> coordinate.startsWith(prefix + ":")).sorted().toList();
    }

    private static List<String> linesOf(Path file) {
        assertTrue(Files.isRegularFile(file), "missing authoritative file " + file);
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read " + file, e);
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
}
