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
 * <p>The {@code content.store.*} keys are the switch that decides whether content bytes live in the database,
 * on local disk, or in an S3-compatible object store, together with the bounds and deadlines under which the
 * chosen provider operates. Four properties of the committed state matter enough to be pinned:
 * <ul>
 * <li>{@code content.store.provider=database}, so an unconfigured checkout keeps the pre-existing
 *     {@code DataResource} database-storage path and the object-storage code - client library included - stays
 *     completely inert;</li>
 * <li>every S3 bucket, region, endpoint, endpoint allowlist, credentials-mode and credential key is DECLARED
 *     but BLANK, so the keys exist as substitution anchors for the container entry point while no credential is
 *     committed to the repository or baked into an image;</li>
 * <li>the two keys that would weaken the object-storage posture if they drifted - the plaintext-endpoint escape
 *     and the whole-content read ceiling - carry their safe committed values, so the escape is off and the
 *     ceiling is in force in an unmodified checkout;</li>
 * <li>every bound and deadline the providers enforce - the in-memory ceilings, the upload-directory scan limit,
 *     the connect, read, call and attempt deadlines, the attempt count and the breaker thresholds - is declared
 *     with a committed default, so an operator who configures nothing still gets bounded behaviour instead of a
 *     provider that can block or allocate without limit.</li>
 * </ul>
 *
 * <p>A key that is silently renamed is as damaging as one with a wrong value: {@code ContentStoreFactory},
 * {@code S3ContentStore} and {@code ContentStoreUtil} read these names, and a rename would leave the reader on
 * its own default while the operator's environment variable was quietly ignored - which for the
 * credentials-mode and allowlist keys means a refusal rather than a silent switch, and for the read ceiling
 * means an unbounded read. So the whole set is compared as one complete structured map, and each key is also
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
    private static final String ENTRY_POINT = "docker/docker-entrypoint.sh";

    /** The entry-point function {@code _main} runs before it chooses a database mode, so it runs on every start. */
    private static final String UNCONDITIONAL_SETUP = "ofbiz_setup_env";

    /** The entry-point function reached only for a managed datasource, so these defaults must not live there. */
    private static final String MANAGED_ONLY_RENDER = "render_database_configuration";

    private static final String PROVIDER = "content.store.provider";
    private static final String BUCKET = "content.store.s3.bucket";
    private static final String REGION = "content.store.s3.region";
    private static final String ENDPOINT = "content.store.s3.endpoint";
    private static final String ENDPOINT_ALLOWLIST = "content.store.s3.endpoint.allowlist";
    private static final String ALLOW_PLAINTEXT_ENDPOINT = "content.store.s3.allow.plaintext.endpoint";
    private static final String CREDENTIALS_PROVIDER = "content.store.s3.credentials.provider";
    private static final String ACCESS_KEY_ID = "content.store.s3.access.key.id";
    private static final String SECRET_ACCESS_KEY = "content.store.s3.secret.access.key";
    private static final String PATH_STYLE = "content.store.s3.path.style";
    private static final String MAX_GET_BYTES = "content.store.max.get.bytes";

    private static final String MAX_MEMORY_BYTES = "content.store.max.memory.bytes";
    private static final String ALLOWED_ROOTS = "content.store.filesystem.allowed.roots";
    private static final String MAX_UPLOAD_DIRECTORIES = "content.store.filesystem.max.upload.directories";
    private static final String KEY_PREFIX = "content.store.s3.key.prefix";
    private static final String CONNECT_TIMEOUT = "content.store.s3.connect.timeout.millis";
    private static final String READ_TIMEOUT = "content.store.s3.read.timeout.millis";
    private static final String CALL_TIMEOUT = "content.store.s3.call.timeout.millis";
    private static final String ATTEMPT_TIMEOUT = "content.store.s3.call.attempt.timeout.millis";
    private static final String MAX_ATTEMPTS = "content.store.s3.max.attempts";
    private static final String BREAKER_THRESHOLD = "content.store.s3.breaker.failure.threshold";
    private static final String BREAKER_RESET = "content.store.s3.breaker.reset.millis";

    private static final String UPLOAD_PREFIX = "content.upload.path.prefix";

    private static final String S3_CLIENT = "software.amazon.awssdk.services.s3.S3Client";
    private static final String S3_CLIENT_BUILDER = "software.amazon.awssdk.services.s3.S3ClientBuilder";
    private static final String S3_COORDINATE = "software.amazon.awssdk:s3:2.49.1";
    private static final String IMPLEMENTATION = "implementation";

    /**
     * The complete committed state of the storage-provider configuration.
     *
     * <p>{@code Map.ofEntries} rather than {@code Map.of}, which caps at ten pairs.
     */
    private static final Map<String, String> EXPECTED_STORE_KEYS = Map.ofEntries(
            Map.entry(PROVIDER, "database"),
            Map.entry(MAX_MEMORY_BYTES, "20971520"),
            Map.entry(ALLOWED_ROOTS, ""),
            Map.entry(MAX_UPLOAD_DIRECTORIES, "5000"),
            Map.entry(BUCKET, ""),
            Map.entry(REGION, ""),
            Map.entry(ENDPOINT, ""),
            Map.entry(ENDPOINT_ALLOWLIST, ""),
            Map.entry(ALLOW_PLAINTEXT_ENDPOINT, "false"),
            Map.entry(CREDENTIALS_PROVIDER, ""),
            Map.entry(ACCESS_KEY_ID, ""),
            Map.entry(SECRET_ACCESS_KEY, ""),
            Map.entry(PATH_STYLE, "false"),
            Map.entry(MAX_GET_BYTES, "33554432"),
            Map.entry(KEY_PREFIX, "content/uploads"),
            Map.entry(CONNECT_TIMEOUT, "5000"),
            Map.entry(READ_TIMEOUT, "30000"),
            Map.entry(CALL_TIMEOUT, "60000"),
            Map.entry(ATTEMPT_TIMEOUT, "20000"),
            Map.entry(MAX_ATTEMPTS, "3"),
            Map.entry(BREAKER_THRESHOLD, "5"),
            Map.entry(BREAKER_RESET, "30000"));

    /**
     * The keys that must never carry a committed value, because their value belongs to the deployment.
     *
     * <p>The credentials mode and the endpoint allowlist are on this list for a different reason from the
     * credentials themselves: they are not secret, but committing either one would supply an answer the
     * deployment is required to state for itself, and a supplied answer is exactly what the fail-closed
     * behaviour of both keys exists to prevent.
     */
    private static final List<String> DEPLOYMENT_SUPPLIED_KEYS = List.of(BUCKET, REGION, ENDPOINT,
            ENDPOINT_ALLOWLIST, CREDENTIALS_PROVIDER, ACCESS_KEY_ID, SECRET_ACCESS_KEY, ALLOWED_ROOTS);

    /**
     * Every bound or deadline whose committed default must be a plain positive integer, so that an operator who
     * configures nothing still runs under a finite limit rather than under an unparseable value the provider
     * would have to fall back from.
     */
    private static final List<String> BOUNDED_NUMERIC_KEYS = List.of(MAX_MEMORY_BYTES, MAX_GET_BYTES,
            MAX_UPLOAD_DIRECTORIES, CONNECT_TIMEOUT, READ_TIMEOUT, CALL_TIMEOUT, ATTEMPT_TIMEOUT, MAX_ATTEMPTS,
            BREAKER_THRESHOLD, BREAKER_RESET);

    /** The two store keys whose value the entry point has to default, mapped to the environment variable holding it. */
    private static final Map<String, String> DEFAULTED_KEY_VARIABLES = Map.of(
            PROVIDER, "OFBIZ_CONTENT_STORE_PROVIDER",
            PATH_STYLE, "OFBIZ_S3_PATH_STYLE");

    /**
     * Every storage key whose value belongs to the deployment rather than to the repository, mapped to the
     * environment variable the container entry point takes it from.
     *
     * <p>The tuning keys - the in-memory ceilings, the upload-directory scan limit, the four deadlines, the
     * attempt count and the two breaker settings - are deliberately absent: their committed defaults are the
     * deployment default, and bridging them would add environment surface without adding a capability.
     */
    private static final Map<String, String> ENTRY_POINT_BRIDGE = Map.ofEntries(
            Map.entry(PROVIDER, "OFBIZ_CONTENT_STORE_PROVIDER"),
            Map.entry(BUCKET, "OFBIZ_S3_BUCKET"),
            Map.entry(REGION, "OFBIZ_S3_REGION"),
            Map.entry(ENDPOINT, "OFBIZ_S3_ENDPOINT"),
            Map.entry(ENDPOINT_ALLOWLIST, "OFBIZ_S3_ENDPOINT_ALLOWLIST"),
            Map.entry(ALLOW_PLAINTEXT_ENDPOINT, "OFBIZ_S3_ALLOW_PLAINTEXT_ENDPOINT"),
            Map.entry(CREDENTIALS_PROVIDER, "OFBIZ_S3_CREDENTIALS_PROVIDER"),
            Map.entry(ACCESS_KEY_ID, "OFBIZ_S3_ACCESS_KEY_ID"),
            Map.entry(SECRET_ACCESS_KEY, "OFBIZ_S3_SECRET_ACCESS_KEY"),
            Map.entry(PATH_STYLE, "OFBIZ_S3_PATH_STYLE"),
            Map.entry(KEY_PREFIX, "OFBIZ_S3_KEY_PREFIX"));

    /** The container entry point, which is the whole of the bridge from a deployment environment to these keys. */
    private static final String CONTAINER_ENTRY_POINT = "docker/docker-entrypoint.sh";

    /** Matches {@code implementation 'a:b:c'} and {@code implementation('a:b:c') {} } alike. */
    private static final Pattern DEPENDENCY_DECLARATION = Pattern.compile("^\\s*(\\w+)\\s*\\(?\\s*'([^']+)'");

    /** The {@code UtilProperties} resource cache, cleared around the one test that reads through it. */
    private static final String PROPERTIES_URL_CACHE = "properties.UtilPropertiesUrlCache";

    /*
     * ---------------------------------------------------------------------------------------------
     * The whole key set, as one complete structured comparison
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void everyStorageKeyIsDeclaredWithItsCommittedDefault() {
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
    public void theSevenKeysStayInTheExactFormTheContainerRenderSubstitutes() {
        // This file is not only read by UtilProperties - docker/docker-entrypoint.sh renders
        // config/content.properties from it with sed, matching each key as "^<name>=". That anchor is stricter than
        // java.util.Properties, and stricter than assignments() above, both of which happily accept a leading
        // indent or spaces around the separator. A well-meant reformat to " content.store.provider = database"
        // would therefore keep every other test in this class green while making the container substitution match
        // nothing at all: the rendered override would silently shadow the shipped file with the committed default,
        // and an operator's OFBIZ_S3_BUCKET would go nowhere. The entry point does verify each key after rendering
        // and refuses to start, but a build-time failure names the cause, where a start-up failure only names the
        // symptom.
        Map<String, Integer> anchored = new LinkedHashMap<>();
        Map<String, Integer> expected = new LinkedHashMap<>();
        for (String key : EXPECTED_STORE_KEYS.keySet()) {
            anchored.put(key, 0);
            expected.put(key, 1);
        }
        for (String line : linesOf(repositoryRoot().resolve(CONTENT_PROPERTIES))) {
            for (String key : EXPECTED_STORE_KEYS.keySet()) {
                if (line.startsWith(key + "=")) {
                    anchored.merge(key, 1, Integer::sum);
                }
            }
        }

        // One comparison for the whole set, so a renamed, indented, commented-out, spaced-out or duplicated key is
        // all the same failure: the substitution anchor the container depends on is no longer there exactly once.
        assertEquals(expected, anchored, "each store key must appear exactly once as an unindented '<name>=' line");
    }

    @Test
    public void theContainerDefaultsTheseKeysWhereEveryStartReachesIt() {
        // A container that is given no storage configuration at all must still render this file and keep the
        // database-storage path, because that is every embedded-H2 run of the image. The entry point achieves that
        // by defaulting the two variables that have a committed value, and WHERE it defaults them is the whole
        // correctness argument: ofbiz_setup_env runs on every start, before _main branches on the database mode,
        // whereas render_database_configuration is reached only when a managed PostgreSQL datasource is configured.
        // Defaulting them there instead left a plain 'docker run' aborting with "OFBIZ_CONTENT_STORE_PROVIDER has
        // an unsupported value" - configuration that had never been supplied being rejected as invalid. The
        // defaults are also pinned to the committed property values, so the two can never drift apart and leave a
        // rendered override that contradicts the file it was rendered from.
        String script = textOf(repositoryRoot().resolve(ENTRY_POINT));
        String setup = shellFunction(script, UNCONDITIONAL_SETUP);
        String managedRender = shellFunction(script, MANAGED_ONLY_RENDER);
        String main = shellFunction(script, "_main");

        for (Map.Entry<String, String> defaulted : DEFAULTED_KEY_VARIABLES.entrySet()) {
            String variable = defaulted.getValue();
            String committed = EXPECTED_STORE_KEYS.get(defaulted.getKey());
            String declaration = variable + "=${" + variable + ":-" + committed + "}";
            assertTrue(setup.contains(declaration),
                    UNCONDITIONAL_SETUP + " must default " + variable + " to the committed "
                            + defaulted.getKey() + " value with: " + declaration);
            assertFalse(managedRender.contains(variable + "=${" + variable + ":-"),
                    MANAGED_ONLY_RENDER + " runs for a managed datasource only, so it must not be where "
                            + variable + " is defaulted");
        }

        int setupCall = main.indexOf("\n  " + UNCONDITIONAL_SETUP + "\n");
        int firstBranch = main.indexOf("\n  if ");
        assertTrue(setupCall >= 0, "_main must call " + UNCONDITIONAL_SETUP);
        assertTrue(firstBranch >= 0, "_main must branch on the initialisation mode");
        assertTrue(setupCall < firstBranch,
                "_main must call " + UNCONDITIONAL_SETUP + " before its first branch, so the defaults are always applied");
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
    public void everyBoundAndDeadlineCarriesAFinitePositiveDefault() {
        Map<String, List<String>> assignments = assignments();

        // The providers clamp whatever they read, but a committed default that is blank, zero, negative or
        // non-numeric would mean the shipped configuration relies on that clamping instead of stating the bound.
        for (String key : BOUNDED_NUMERIC_KEYS) {
            String value = assignments.get(key).get(0);
            assertTrue(value.matches("[0-9]+"), key + " must carry a plain numeric default, not [" + value + "]");
            assertTrue(Long.parseLong(value) > 0L, key + " must carry a positive default, not " + value);
        }
        // The attempt deadline has to leave room for more than one attempt inside the overall call deadline,
        // otherwise the configured attempt count could never be spent and a retry would be dead configuration.
        long call = Long.parseLong(assignments.get(CALL_TIMEOUT).get(0));
        long attempt = Long.parseLong(assignments.get(ATTEMPT_TIMEOUT).get(0));
        assertTrue(attempt < call, ATTEMPT_TIMEOUT + " must be shorter than " + CALL_TIMEOUT + " for a retry to be reachable");
    }

    @Test
    public void plaintextEndpointsAreRefusedByTheCommittedDefault() {
        // Static credentials travel with every request, so the shipped default must not permit a plain http
        // endpoint; enabling it is an explicit, auditable operator decision rather than a silent fallback.
        assertEquals("false", assignments().get(ALLOW_PLAINTEXT_ENDPOINT).get(0),
                ALLOW_PLAINTEXT_ENDPOINT + " must ship disabled so no credential can be sent in clear text by default");
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

    @Test
    public void theTwoSafetySettingsAreCommittedInTheirSafeState() {
        Map<String, List<String>> assignments = assignments();

        // These two are the only content.store.* keys whose committed value is neither blank nor a provider
        // name, and both are load-bearing. The plaintext escape being false is what makes an http endpoint
        // override a refusal rather than a warning, so a deployment cannot end up sending signed requests and
        // content over an unencrypted network because a properties file drifted.
        assertEquals("false", assignments.get(ALLOW_PLAINTEXT_ENDPOINT).get(0),
                ALLOW_PLAINTEXT_ENDPOINT + " must be committed false: it is a development-only escape from requiring https");

        // And the read ceiling must be committed as a positive number rather than blank, because a blank value
        // is silently a 32 MiB default: pinning a real number is what makes the ceiling visible to the operator
        // who has to raise it, instead of a constant buried in ContentStoreUtil.
        String ceiling = assignments.get(MAX_GET_BYTES).get(0);
        assertTrue(ceiling.matches("[0-9]+"), MAX_GET_BYTES + " must be committed as a plain number, but was [" + ceiling + "]");
        assertTrue(Long.parseLong(ceiling) > 0, MAX_GET_BYTES + " must be positive; a non-positive value would disable the ceiling");
    }

    @Test
    public void theReadCeilingIsInForceThroughTheProductionLookupWithoutAnyConfiguration() {
        // ContentStoreUtil.maxWholeReadBytes reads this key through UtilProperties, so the committed
        // declaration has to survive the real lookup: a value that parsed here but not there would leave every
        // provider on its internal default while the file appeared to configure it.
        UtilCache.clearCache(PROPERTIES_URL_CACHE);
        try {
            long committed = Long.parseLong(assignments().get(MAX_GET_BYTES).get(0));
            assertEquals(committed, UtilProperties.getPropertyAsLong("content", MAX_GET_BYTES, -1L),
                    MAX_GET_BYTES + " must resolve through UtilProperties to exactly the committed value");
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
     * The container entry point is the only bridge from a deployment environment to these keys
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void theContainerEntryPointSubstitutesEveryDeploymentOwnedStorageKey() {
        String entryPoint = String.join("\n", linesOf(repositoryRoot().resolve(CONTAINER_ENTRY_POINT)));

        // The providers read configuration only - deliberately, so that provider code has one configuration
        // source and is testable without an environment - so this file is the ONLY thing that can turn a
        // deployment's variables into an active object store. A key present here but not substituted there is a
        // key an operator cannot set at all, which is how a fully configured looking deployment ends up silently
        // storing content in the database.
        //
        // The bridge is asserted against the entry point's two parallel arrays rather than key by key, because
        // the PAIRING is the part a per-key search cannot see: a transposed pair would render the bucket into
        // the region property and satisfy every containment check that looked at one key on its own. Map
        // equality is order-independent, so the arrays may be reordered; only the pairing and the membership
        // are fixed.
        List<String> variables = shellArray(entryPoint, "CONTENT_STORE_VARIABLES");
        List<String> properties = shellArray(entryPoint, "CONTENT_STORE_PROPERTIES");
        assertEquals(properties.size(), variables.size(), CONTAINER_ENTRY_POINT
                + " must keep CONTENT_STORE_VARIABLES and CONTENT_STORE_PROPERTIES the same length: they are"
                + " indexed together, so a variable added to one and not the other renders the wrong value");

        Map<String, String> bridge = new LinkedHashMap<>();
        for (int index = 0; index < properties.size(); index++) {
            assertEquals(null, bridge.put(properties.get(index), variables.get(index)),
                    CONTAINER_ENTRY_POINT + " must name " + properties.get(index) + " once, not twice");
        }
        assertEquals(ENTRY_POINT_BRIDGE, bridge, CONTAINER_ENTRY_POINT
                + " must bridge exactly the deployment-owned storage keys this file declares, each from the"
                + " variable named here; an extra pair is environment surface content.properties has no anchor"
                + " for, and a missing pair is a key an operator cannot set at all");

        // And the pair has to be acted on twice: once to substitute the value into the render, and once to read
        // the rendered file back and confirm what java.util.Properties will make of it. Both arms are asserted
        // because the entry point's own comment gives them different jobs, and a key present in the arrays but
        // missing from either 'case' would leave the value unsubstituted or unverified.
        for (Map.Entry<String, String> bridged : ENTRY_POINT_BRIDGE.entrySet()) {
            for (String assignment : List.of("value", "expected")) {
                Pattern arm = Pattern.compile("^\\s*" + Pattern.quote(bridged.getKey()) + "\\) " + assignment
                        + "=\"\\$\\w+\" ;;$", Pattern.MULTILINE);
                assertEquals(1, (int) arm.matcher(entryPoint).results().count(),
                        CONTAINER_ENTRY_POINT + " must substitute " + bridged.getKey() + " exactly once as "
                                + assignment + ", otherwise " + bridged.getValue()
                                + " cannot reach the provider configuration");
            }
            assertTrue(entryPoint.contains(bridged.getValue()),
                    CONTAINER_ENTRY_POINT + " must read " + bridged.getValue());
        }
    }

    @Test
    public void theContainerEntryPointAcceptsExactlyTheProvidersTheFactoryRecognises() {
        String entryPoint = String.join("\n", linesOf(repositoryRoot().resolve(CONTAINER_ENTRY_POINT)));

        // The accepted set must be the factory's own set. A wider set would let the entry point render a value the
        // factory then fails closed on, turning a typo into a start-up failure at a confusing place; a narrower one
        // would refuse a backend the factory supports.
        assertTrue(entryPoint.contains("CONTENT_STORE_PROVIDERS=(database filesystem s3)"),
                CONTAINER_ENTRY_POINT + " must accept exactly the providers ContentStoreFactory recognises");
        assertTrue(entryPoint.contains("CONTENT_STORE_DEFAULT_PROVIDER='database'"),
                CONTAINER_ENTRY_POINT + " must treat database as the unconfigured default, matching this file");
    }

    @Test
    public void theContainerEntryPointRemovesEveryObjectStoreVariableFromTheServingEnvironment() {
        List<String> lines = linesOf(repositoryRoot().resolve(CONTAINER_ENTRY_POINT));

        // An environment variable is readable through /proc/<pid>/environ, and any child process, crash handler or
        // diagnostic dump that reports the environment republishes it. The values have already been written to the
        // mode 0600 rendered configuration by the time the serving command is executed, so nothing needs them.
        for (String variable : ENTRY_POINT_BRIDGE.values()) {
            assertTrue(lines.stream().anyMatch(line -> line.strip().equals("unset " + variable)),
                    CONTAINER_ENTRY_POINT + " must unset " + variable + " before executing the serving command");
        }
    }

    @Test
    public void theContainerEntryPointCommitsNoObjectStoreCredential() {
        List<String> lines = linesOf(repositoryRoot().resolve(CONTAINER_ENTRY_POINT));

        // Every credential-bearing variable must be READ from the environment and never assigned a literal, so no
        // object-store credential can reach the repository or an image layer through this file. A default of the
        // empty string is the only assignment that carries nothing.
        for (String variable : List.of("OFBIZ_S3_ACCESS_KEY_ID", "OFBIZ_S3_SECRET_ACCESS_KEY", "OFBIZ_S3_ENDPOINT")) {
            for (String line : lines) {
                String stripped = line.strip();
                assertFalse(stripped.startsWith(variable + "=") && !stripped.equals(variable + "=") && !stripped.equals(variable + "=''"),
                        CONTAINER_ENTRY_POINT + " assigns a literal to " + variable + ": " + stripped);
            }
        }
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

    /**
     * The body of one shell function of the entry point, from its opening line to the closing brace in column one.
     * Asserted non-empty, so a renamed or reshaped function fails as a missing function rather than as a vacuously
     * passing search through an empty string.
     */
    private static String shellFunction(String script, String name) {
        Matcher matcher = Pattern.compile("^" + Pattern.quote(name) + "\\(\\) \\{$(.*?)^\\}$",
                Pattern.MULTILINE | Pattern.DOTALL).matcher(script);
        assertTrue(matcher.find(), "the entry point must declare the shell function " + name);
        String body = matcher.group(1);
        assertFalse(body.isBlank(), "the shell function " + name + " must not be empty");
        return body;
    }

    /**
     * The elements of one multi-line shell array literal of the entry point, in declaration order.
     *
     * <p>Matched on the {@code NAME=(} ... {@code )} shape the entry point uses for its parallel arrays, with one
     * element per line. Asserted non-empty for the reason {@link #shellFunction(String, String)} is: a renamed
     * array must fail as a missing array rather than as a vacuously satisfied comparison against nothing.</p>
     *
     * @param script the whole entry-point text
     * @param name the array's name
     * @return the array's elements, in declaration order
     */
    private static List<String> shellArray(String script, String name) {
        Matcher matcher = Pattern.compile("^" + Pattern.quote(name) + "=\\((.*?)^\\)$",
                Pattern.MULTILINE | Pattern.DOTALL).matcher(script);
        assertTrue(matcher.find(), "the entry point must declare the shell array " + name);
        List<String> elements = new ArrayList<>();
        for (String line : matcher.group(1).split("\n")) {
            String element = line.strip();
            if (!element.isEmpty() && !element.startsWith("#")) {
                elements.add(element);
            }
        }
        assertFalse(elements.isEmpty(), "the shell array " + name + " must not be empty");
        return elements;
    }

    private static String textOf(Path file) {
        assertTrue(Files.isRegularFile(file), "missing authoritative file " + file);
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read " + file, e);
        }
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
