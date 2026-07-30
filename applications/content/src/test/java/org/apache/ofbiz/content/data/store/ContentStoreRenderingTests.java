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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.ofbiz.base.util.UtilProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Executable contract of the entry point's object-store renderer - the step that turns the ten
 * advertised {@code OFBIZ_CONTENT_STORE_PROVIDER} / {@code OFBIZ_S3_*} environment variables into the
 * {@code content.store.*} properties that {@link ContentStoreFactory} and {@link S3ContentStore} read.
 *
 * <p>WHY THIS EXISTS. Those variables were advertised as the configuration surface of the object
 * store while nothing whatsoever consumed them. The classes in this package read these properties of the
 * {@code content} resource; the container entry point rendered none of them and removed none of the
 * variables from the environment. The result was worse than an unimplemented feature: a deployment that
 * supplied a bucket, an endpoint and a secret access key silently kept database storage - so the
 * deployment was not doing what its own manifest said, and nothing reported the difference - while the
 * secret access key stayed in the container's environment for the life of the instance, readable through
 * {@code /proc/<pid>/environ} by anything sharing the PID namespace and inherited by every hook and child
 * process.
 *
 * <p>HOW IT IS TESTED. The real {@code docker/docker-entrypoint.sh} is sourced as a library with its
 * trailing {@code _main "$@"} line removed, so {@code render_content_store_configuration} can be driven
 * as a black box without starting OFBiz or contacting an object store, against a throwaway sandbox that
 * stands in for {@code /ofbiz}. Nothing is reimplemented and nothing is stubbed: every assertion reads
 * the file the production script wrote, parsed by the same {@link Properties} the application uses, and
 * every rejection case asserts on the script's own refusal.
 *
 * <p>WHAT IS DELIBERATELY NOT ASSERTED. No test here contacts an object store or constructs an
 * {@code S3Client}: the round trip against a real S3-compatible store is a deployment gate, and the
 * client's own behaviour is covered by {@link S3ContentStoreTests}. This suite covers only the boundary
 * between the container's environment and the property file - which is exactly where the defect was.
 *
 * @see ContentStoreFactory
 * @see S3ContentStore
 * @see ContentStorePropertiesContractTests
 */
public final class ContentStoreRenderingTests {

    private static final String ENTRY_POINT = "docker/docker-entrypoint.sh";
    private static final String DEPENDENCY_MANIFEST = "dependencies.gradle";

    /** The OFBiz property resource these keys belong to, as {@code UtilProperties} names it. */
    private static final String CONTENT_RESOURCE = "content";

    /** The pristine committed resource the renderer substitutes into. */
    private static final String CONTENT_SOURCE = "applications/content/config/content.properties";

    /**
     * Where the render lands. Unlike {@code start.properties} the {@code content} resource is not package
     * qualified - {@code UtilProperties} looks up the bare name {@code content.properties} - so the flat
     * {@code config/} path is what shadows the copy inside {@code ofbiz.jar}.
     */
    private static final String CONTENT_OVERRIDE = "config/content.properties";

    private static final String PROVIDER_PROPERTY = "content.store.provider";
    private static final String BUCKET_PROPERTY = "content.store.s3.bucket";
    private static final String REGION_PROPERTY = "content.store.s3.region";
    private static final String ENDPOINT_PROPERTY = "content.store.s3.endpoint";
    private static final String ALLOWLIST_PROPERTY = "content.store.s3.endpoint.allowlist";
    private static final String PLAINTEXT_PROPERTY = "content.store.s3.allow.plaintext.endpoint";
    private static final String CREDENTIALS_MODE_PROPERTY = "content.store.s3.credentials.provider";
    private static final String ACCESS_KEY_PROPERTY = "content.store.s3.access.key.id";
    private static final String SECRET_KEY_PROPERTY = "content.store.s3.secret.access.key";
    private static final String PATH_STYLE_PROPERTY = "content.store.s3.path.style";
    private static final String KEY_PREFIX_PROPERTY = "content.store.s3.key.prefix";

    private static final String PROVIDER_VARIABLE = "OFBIZ_CONTENT_STORE_PROVIDER";
    private static final String BUCKET_VARIABLE = "OFBIZ_S3_BUCKET";
    private static final String REGION_VARIABLE = "OFBIZ_S3_REGION";
    private static final String ENDPOINT_VARIABLE = "OFBIZ_S3_ENDPOINT";
    private static final String ALLOWLIST_VARIABLE = "OFBIZ_S3_ENDPOINT_ALLOWLIST";
    private static final String PLAINTEXT_VARIABLE = "OFBIZ_S3_ALLOW_PLAINTEXT_ENDPOINT";
    private static final String CREDENTIALS_MODE_VARIABLE = "OFBIZ_S3_CREDENTIALS_PROVIDER";
    private static final String ACCESS_KEY_VARIABLE = "OFBIZ_S3_ACCESS_KEY_ID";
    private static final String SECRET_KEY_VARIABLE = "OFBIZ_S3_SECRET_ACCESS_KEY";
    private static final String PATH_STYLE_VARIABLE = "OFBIZ_S3_PATH_STYLE";
    private static final String KEY_PREFIX_VARIABLE = "OFBIZ_S3_KEY_PREFIX";

    /** The mode that authenticates with the two credential properties. */
    private static final String CREDENTIALS_STATIC = "static";

    /** The mode that authenticates with the ambient AWS credential chain and forbids a credential. */
    private static final String CREDENTIALS_DEFAULT_CHAIN = "default-chain";

    /** The host every test that configures an endpoint declares in the allowlist. */
    private static final String ENDPOINT_HOST = "objects.example.internal";

    /** The endpoint every test that configures one uses; its host is {@link #ENDPOINT_HOST}. */
    private static final String ENDPOINT = "https://" + ENDPOINT_HOST + ":9000";

    /**
     * The eleven variables the renderer reads, paired with the eleven properties it writes, in order.
     *
     * <p>These are the entry point's own {@code CONTENT_STORE_VARIABLES} and
     * {@code CONTENT_STORE_PROPERTIES} arrays, which it indexes together, so the two lists here are kept
     * in the same order and at the same length. Leaving one out would silently narrow every loop below -
     * the anchor census, the documented-and-withdrawn census and the no-duplicate-declaration census
     * would all stop covering it, which is exactly how {@code content.store.s3.key.prefix} went
     * unasserted after it was added.
     */
    private static final List<String> STORE_VARIABLES = List.of(PROVIDER_VARIABLE, BUCKET_VARIABLE,
            REGION_VARIABLE, ENDPOINT_VARIABLE, ALLOWLIST_VARIABLE, PLAINTEXT_VARIABLE,
            CREDENTIALS_MODE_VARIABLE, ACCESS_KEY_VARIABLE, SECRET_KEY_VARIABLE, PATH_STYLE_VARIABLE,
            KEY_PREFIX_VARIABLE);
    private static final List<String> STORE_PROPERTIES = List.of(PROVIDER_PROPERTY, BUCKET_PROPERTY,
            REGION_PROPERTY, ENDPOINT_PROPERTY, ALLOWLIST_PROPERTY, PLAINTEXT_PROPERTY,
            CREDENTIALS_MODE_PROPERTY, ACCESS_KEY_PROPERTY, SECRET_KEY_PROPERTY, PATH_STYLE_PROPERTY,
            KEY_PREFIX_PROPERTY);

    /**
     * The committed source must still declare each rendered property exactly once.
     *
     * <p>This is the anchor contract the whole render depends on. Each substitution replaces the remainder
     * of one {@code ^key=} line, so a renamed anchor would leave the property absent from the render and a
     * duplicated one would let {@link Properties} use the declaration the substitution did not reach. The
     * script's own read-back refuses both at container start; asserting them here reports the cause at
     * build time, next to the file that has to be corrected.
     *
     * @throws IOException if the committed resource cannot be read, which fails the test
     */
    @Test
    public void theCommittedResourceDeclaresEachRenderedPropertyExactlyOnce() throws IOException {
        List<String> shipped = Files.readAllLines(repositoryRoot().resolve(CONTENT_SOURCE), StandardCharsets.UTF_8);

        for (String property : STORE_PROPERTIES) {
            assertEquals(1, shipped.stream().filter(line -> line.startsWith(property + "=")).count(),
                    CONTENT_SOURCE + " must declare " + property + " exactly once: the renderer replaces that "
                            + "line, and a duplicate would let java.util.Properties use the one it did not "
                            + "replace");
        }
    }

    /**
     * Every advertised variable is documented in the entry point and removed from the environment it exec's.
     *
     * <p>Both halves are what the defect was. The variables were advertised in the specification while
     * the entry point neither rendered nor removed a single one of them, so a deployment that supplied a
     * secret access key got database storage AND a live credential left in {@code /proc/<pid>/environ} for
     * the life of the instance. This is a static check of the script's own text - the behaviour of the unset
     * block is exercised end to end by {@code SchemaInitEntryPointTests} - and it exists so that adding an
     * further variable without documenting it, or without withdrawing it from the environment, fails the
     * build next to the file that has to be corrected.
     *
     * @throws IOException if the entry point cannot be read, which fails the test
     */
    @Test
    public void everyAdvertisedVariableIsDocumentedAndWithdrawnFromTheEnvironmentBeforeTheServerStarts()
            throws IOException {
        String entryPoint = Files.readString(repositoryRoot().resolve(ENTRY_POINT), StandardCharsets.UTF_8);

        assertEquals(STORE_VARIABLES.size(), STORE_PROPERTIES.size(),
                "the variables and the properties they render are paired one to one; this suite indexes them "
                        + "together, so the two lists must stay the same length");
        for (String variable : STORE_VARIABLES) {
            assertTrue(entryPoint.contains(System.lineSeparator() + "# " + variable + System.lineSeparator())
                            || entryPoint.contains("\n# " + variable + "\n"),
                    variable + " must be documented in the environment-variable header of " + ENTRY_POINT
                            + ": an undocumented variable is one an operator cannot know to supply");
            assertTrue(entryPoint.contains("unset " + variable + System.lineSeparator())
                            || entryPoint.contains("unset " + variable + "\n"),
                    variable + " must be removed from the environment before OFBiz is exec'd. It has already "
                            + "been rendered into a mode 0600 file, and an environment variable is readable "
                            + "through /proc/<pid>/environ for the whole life of the instance");
        }
    }

    /**
     * With no object-store variable set at all, nothing is rendered.
     *
     * <p>That is the whole backward-compatibility guarantee of this change: an unconfigured container has no
     * override file in play and therefore uses the committed {@code content.properties}, which selects
     * database storage. Writing an override unconditionally would be the failure mode to avoid - a
     * container that had never been told anything about object storage would then be running on a file
     * generated by this script rather than on the one shipped in the distribution.
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void anUnconfiguredContainerGetsNoOverrideSoTheCommittedDatabaseDefaultStands(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        RendererRun run = render(tempDir, sandbox, Map.of());

        assertEquals(0, run.exitCode(),
                "supplying no object-store variable is the default, not an error. Output:\n" + run.output());
        assertFalse(Files.exists(sandbox.resolve(CONTENT_OVERRIDE)),
                "with nothing configured there is nothing to override, so no " + CONTENT_OVERRIDE
                        + " may be written: the container must use the content.properties it shipped with");
    }

    /**
     * A full object-store configuration round trips through {@link Properties} exactly as it was supplied.
     *
     * <p>Every value is deliberately awkward rather than merely plausible. The secret access key carries a
     * character that is special to each grammar it passes through - the sed replacement ({@code &}, {@code |},
     * {@code \}), the {@link Properties} value ({@code \}, {@code :}, {@code =}) and the shell ({@code $}) -
     * so an escaping mistake in any one of the three shows up here as a failed round trip rather than in
     * production as a credential the object store rejects.
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void aCompleteObjectStoreConfigurationIsReadBackExactlyAsItWasSupplied(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        String secretKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY=\\:$aw&|x";
        Map<String, String> environment = staticCredentialEnvironment();
        environment.put(REGION_VARIABLE, "eu-west-2");
        environment.put(ENDPOINT_VARIABLE, ENDPOINT);
        environment.put(ALLOWLIST_VARIABLE, ENDPOINT_HOST);
        environment.put(SECRET_KEY_VARIABLE, secretKey);
        environment.put(PATH_STYLE_VARIABLE, "TRUE");

        RendererRun run = render(tempDir, sandbox, environment);

        assertEquals(0, run.exitCode(), "a complete configuration must render. Output:\n" + run.output());
        Properties rendered = loadProperties(sandbox.resolve(CONTENT_OVERRIDE));
        assertEquals("s3", rendered.getProperty(PROVIDER_PROPERTY), "the provider selects the backend");
        assertEquals("ofbiz-content", rendered.getProperty(BUCKET_PROPERTY), "the bucket must round trip");
        assertEquals("eu-west-2", rendered.getProperty(REGION_PROPERTY), "the region must round trip");
        assertEquals(ENDPOINT, rendered.getProperty(ENDPOINT_PROPERTY), "the endpoint must round trip");
        assertEquals(ENDPOINT_HOST, rendered.getProperty(ALLOWLIST_PROPERTY),
                "the endpoint allowlist must round trip, because the provider checks the endpoint against it "
                        + "a second time");
        assertEquals("false", rendered.getProperty(PLAINTEXT_PROPERTY),
                "the plaintext escape must render as the literal false when it was never asked for");
        assertEquals(CREDENTIALS_STATIC, rendered.getProperty(CREDENTIALS_MODE_PROPERTY),
                "the credentials mode must round trip: it is what decides which identity the deployment "
                        + "authenticates as, and the provider refuses to infer it");
        assertEquals("AKIAIOSFODNN7EXAMPLE", rendered.getProperty(ACCESS_KEY_PROPERTY),
                "the access key id must round trip");
        assertEquals(secretKey, rendered.getProperty(SECRET_KEY_PROPERTY),
                "the secret access key must round trip through sed, the properties grammar and the shell "
                        + "byte for byte, or the object store rejects every request the deployment makes");
        assertEquals("true", rendered.getProperty(PATH_STYLE_PROPERTY),
                "the addressing style must be normalised to the literal the provider parses");
    }

    /**
     * The render replaces the whole resource rather than emitting a fragment of the keys it owns.
     *
     * <p>An OFBiz property override shadows a resource; it is not merged into it key by key. A file holding
     * only the {@code content.store.*} keys would therefore take away every other property of
     * {@code content.properties} - the upload path prefix, the maximum upload count, the allowed file paths,
     * the whole-read ceiling - from the running application. The rendered file must have the same shape as
     * the one it shadows.
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void theRenderShadowsTheWholeResourceRatherThanReplacingItWithTheKeysItOwns(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        RendererRun run = render(tempDir, sandbox, Map.of(PROVIDER_VARIABLE, "filesystem"));
        assertEquals(0, run.exitCode(), "selecting filesystem storage must render. Output:\n" + run.output());

        Properties shipped = loadProperties(sandbox.resolve(CONTENT_SOURCE));
        Properties rendered = loadProperties(sandbox.resolve(CONTENT_OVERRIDE));
        assertEquals(shipped.stringPropertyNames(), rendered.stringPropertyNames(),
                "the rendered override must declare exactly the properties the committed resource declares: "
                        + "an override shadows a resource rather than being merged into it, so a missing key "
                        + "is a key the application loses");
        for (String property : shipped.stringPropertyNames()) {
            if (!STORE_PROPERTIES.contains(property)) {
                assertEquals(shipped.getProperty(property), rendered.getProperty(property),
                        "a property this renderer does not own must be carried across unchanged: " + property);
            }
        }
        assertEquals("filesystem", rendered.getProperty(PROVIDER_PROPERTY), "the provider must be the one asked for");
    }

    /**
     * The rendered file is readable only by its owner, and its content never reaches the log.
     *
     * <p>The secret access key is a live credential for the bucket holding the deployment's content, so the
     * file it lands in is treated exactly as {@code config/security.properties} is: created with mode
     * {@code 0600} before anything is written into it, substituted from a temporary file rather than from a
     * command line so it never appears in the process table, and never echoed.
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void theRenderedFileIsOwnerOnlyAndTheCredentialIsNeverEchoed(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        String secretKey = "aVeryDistinctiveSecretAccessKeyValue0123456789";
        Map<String, String> environment = staticCredentialEnvironment();
        environment.put(SECRET_KEY_VARIABLE, secretKey);
        // Tracing on, because a trace of the render is the most likely way for the value to escape.
        environment.put("OFBIZ_TRACE", "1");

        RendererRun run = render(tempDir, sandbox, environment);

        assertEquals(0, run.exitCode(), "the render must succeed with tracing on. Output:\n" + run.output());
        Path rendered = sandbox.resolve(CONTENT_OVERRIDE);
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(rendered);
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), permissions,
                "the rendered file holds the object-store credential, so only its owner may read it. It has "
                        + permissions);
        assertFalse(run.output().contains(secretKey),
                "the secret access key must never reach the container log, not even with OFBIZ_TRACE set. "
                        + "Output:\n" + run.output());
    }

    /**
     * Re-rendering replaces the previous values rather than accumulating declarations.
     *
     * <p>Every render starts from the pristine committed resource, not from the previous render, which is
     * what makes rotating a bucket or a credential a restart rather than a rebuild. A renderer that
     * substituted into its own output would double each declaration on the second start, and
     * {@link Properties} would then use whichever one happened to be last.
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void aRotatedBucketAndCredentialTakeEffectOnRestartWithoutDuplicatingAnyDeclaration(
            @TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        Map<String, String> first = staticCredentialEnvironment();
        first.put(BUCKET_VARIABLE, "first-bucket");
        first.put(ACCESS_KEY_VARIABLE, "AKIAFIRSTKEYEXAMPLE");
        first.put(SECRET_KEY_VARIABLE, "firstSecretAccessKeyValue0123456789");
        assertEquals(0, render(tempDir, sandbox, first).exitCode(), "the first render must succeed");

        Map<String, String> second = new LinkedHashMap<>(first);
        second.put(BUCKET_VARIABLE, "second-bucket");
        second.put(REGION_VARIABLE, "eu-west-2");
        second.put(ACCESS_KEY_VARIABLE, "AKIASECONDKEYEXAMPLE");
        second.put(SECRET_KEY_VARIABLE, "secondSecretAccessKeyValue0123456789");
        RendererRun run = render(tempDir, sandbox, second);

        assertEquals(0, run.exitCode(), "the second render must succeed. Output:\n" + run.output());
        Properties rendered = loadProperties(sandbox.resolve(CONTENT_OVERRIDE));
        assertEquals("second-bucket", rendered.getProperty(BUCKET_PROPERTY), "the rotated bucket must take effect");
        assertEquals("eu-west-2", rendered.getProperty(REGION_PROPERTY), "the rotated region must take effect");
        assertEquals("AKIASECONDKEYEXAMPLE", rendered.getProperty(ACCESS_KEY_PROPERTY),
                "the rotated credential must take effect");

        List<String> lines = Files.readAllLines(sandbox.resolve(CONTENT_OVERRIDE), StandardCharsets.UTF_8);
        for (String property : STORE_PROPERTIES) {
            assertEquals(1, lines.stream().filter(line -> line.startsWith(property + "=")).count(),
                    "re-rendering must not add a second declaration of " + property);
        }
        assertFalse(Files.readString(sandbox.resolve(CONTENT_OVERRIDE), StandardCharsets.UTF_8)
                .contains("first-bucket"),
                "no trace of the retired configuration may survive the re-render");
    }

    /**
     * Selecting a backend the factory does not implement aborts the start.
     *
     * <p>The alternative is a container that quietly stores content somewhere other than where its manifest
     * says. A misspelling is the realistic case - {@code S3}, {@code aws}, {@code minio} - and each of them
     * would have selected database storage while the deployment believed otherwise.
     *
     * @param unsupported a provider name that is not one of the three implemented backends
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @ParameterizedTest
    @ValueSource(strings = {"azure", "minio", "S3", "aws", "Database", "file", "gcs", " s3"})
    public void aBackendThatIsNotImplementedAbortsTheStartInsteadOfSilentlyStoringElsewhere(String unsupported,
            @TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        RendererRun run = render(tempDir, sandbox, Map.of(PROVIDER_VARIABLE, unsupported));

        assertRefused(run, sandbox, PROVIDER_VARIABLE,
                "an unimplemented backend name must abort the start: [" + unsupported + "]");
    }

    /**
     * Selecting {@code s3} without a bucket or without a region aborts the start.
     *
     * <p>Both are mandatory for the client to address anything at all, and neither has a defensible default.
     * Without this the deployment starts, serves every page, and fails at the first upload an end user
     * attempts - which is the worst possible moment to discover an incomplete manifest.
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void selectingTheObjectStoreWithoutABucketOrARegionAbortsTheStartRatherThanTheFirstUpload(
            @TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");

        Path noBucket = prepareSandbox(Files.createTempDirectory(tempDir, "no-bucket"));
        assertRefused(render(tempDir, noBucket, Map.of(PROVIDER_VARIABLE, "s3", REGION_VARIABLE, "us-east-1")),
                noBucket, BUCKET_VARIABLE, "s3 storage without a bucket must abort the start");

        Path noRegion = prepareSandbox(Files.createTempDirectory(tempDir, "no-region"));
        assertRefused(render(tempDir, noRegion, Map.of(PROVIDER_VARIABLE, "s3", BUCKET_VARIABLE, "ofbiz-content")),
                noRegion, REGION_VARIABLE, "s3 storage without a region must abort the start");
    }

    /**
     * A bucket name the object store would reject is refused at start up.
     *
     * <p>Every case here is a name S3-compatible stores refuse: too short, upper case, an underscore, a
     * leading or trailing punctuation character, adjacent dots, a dot beside a hyphen, an IPv4 shape, and a
     * leading blank - which {@link Properties} would silently discard, so it would also be validated as one
     * value and read back as another.
     *
     * @param invalid a bucket name no S3-compatible store accepts
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @ParameterizedTest
    @ValueSource(strings = {"ab", "OfbizContent", "ofbiz_content", "-ofbiz-content", "ofbiz-content-",
        "ofbiz..content", "ofbiz.-content", "ofbiz-.content", "192.168.10.20", " ofbiz-content",
        "ofbiz content", "ofbiz/content"})
    public void aBucketNameTheStoreWouldRejectIsRefusedAtStartUp(String invalid, @TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        RendererRun run = render(tempDir, sandbox, Map.of(PROVIDER_VARIABLE, "s3",
                REGION_VARIABLE, "us-east-1", BUCKET_VARIABLE, invalid));

        assertRefused(run, sandbox, BUCKET_VARIABLE,
                "a bucket name the object store rejects must be refused here: [" + invalid + "]");
    }

    /**
     * A 63 character bucket name is accepted, so the length rule is a bound and not an off-by-one.
     *
     * <p>Without this the refusals above could be satisfied by a rule that is simply too strict, which would
     * turn away a legitimate bucket - and a validator that rejects valid configuration is as much of an
     * outage as one that accepts invalid configuration.
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void aBucketNameAtEachEndOfTheAllowedLengthIsAccepted(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");

        String longest = "a".repeat(62) + "z";
        for (String valid : List.of("abc", "a-b", "a.b", longest, "ofbiz-content.2024")) {
            Path sandbox = prepareSandbox(Files.createTempDirectory(tempDir, "bucket"));
            Map<String, String> environment = staticCredentialEnvironment();
            environment.put(BUCKET_VARIABLE, valid);
            RendererRun run = render(tempDir, sandbox, environment);

            assertEquals(0, run.exitCode(),
                    "a bucket name the object store accepts must render: [" + valid + "]. Output:\n"
                            + run.output());
            assertEquals(valid, loadProperties(sandbox.resolve(CONTENT_OVERRIDE)).getProperty(BUCKET_PROPERTY),
                    "the accepted bucket name must round trip: [" + valid + "]");
        }
    }

    /**
     * An endpoint that is not a plain absolute http or https URI with a host is refused.
     *
     * <p>Each case is refused for its own reason. A bare host name or an unknown scheme fails deep inside the
     * SDK's URI parsing, in a message that names neither the variable nor the file. Embedded user information
     * puts a credential into a value that is treated - and logged - as ordinary configuration. A query string
     * or a fragment is not part of an object-store endpoint and would be silently carried into every request.
     *
     * @param invalid an endpoint value the renderer must refuse
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @ParameterizedTest
    @ValueSource(strings = {"objects.example.internal", "objects.example.internal:9000",
        "ftp://objects.example.internal", "file:///var/objects", "HTTPS://objects.example.internal",
        "https://key:secret@objects.example.internal", "https://objects.example.internal/?prefix=a",
        "https://objects.example.internal/#fragment", "https:///bucket", "https://",
        "https://objects.example.internal /x", " https://objects.example.internal"})
    public void anEndpointThatIsNotAPlainAbsoluteUriWithAHostIsRefused(String invalid, @TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        RendererRun run = render(tempDir, sandbox, Map.of(PROVIDER_VARIABLE, "s3",
                REGION_VARIABLE, "us-east-1", BUCKET_VARIABLE, "ofbiz-content", ENDPOINT_VARIABLE, invalid));

        assertRefused(run, sandbox, ENDPOINT_VARIABLE,
                "an endpoint that is not a plain absolute URI with a host must be refused: [" + invalid + "]");
    }

    /**
     * An endpoint pointing at a cloud instance metadata service is refused in EVERY profile.
     *
     * <p>This is the one refusal with no override, and it is not about transport security. The provider sends
     * the deployment's own credentials to whatever it is told is the object store, and the response from one
     * of these addresses is a set of role credentials for the whole instance. There is no development
     * scenario that needs an object store at a link-local metadata address, so no profile accepts one.
     *
     * @param metadataEndpoint an endpoint addressing a metadata or container credential service
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @ParameterizedTest
    @ValueSource(strings = {"https://169.254.169.254/latest/meta-data/", "https://169.254.169.254",
        "https://[fd00:ec2::254]/latest", "https://169.254.170.2/v2/credentials",
        "https://metadata.google.internal/computeMetadata/v1/"})
    public void anEndpointAddressingTheInstanceMetadataServiceIsRefusedInEveryProfile(String metadataEndpoint,
            @TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");

        for (String profile : List.of("dev", "prod")) {
            Path sandbox = prepareSandbox(Files.createTempDirectory(tempDir, "metadata"));
            Map<String, String> environment = new LinkedHashMap<>();
            environment.put("OFBIZ_PROFILE", profile);
            environment.put(PROVIDER_VARIABLE, "s3");
            environment.put(REGION_VARIABLE, "us-east-1");
            environment.put(BUCKET_VARIABLE, "ofbiz-content");
            environment.put(ENDPOINT_VARIABLE, metadataEndpoint);

            RendererRun run = render(tempDir, sandbox, environment);

            assertRefused(run, sandbox, ENDPOINT_VARIABLE, "the " + profile + " profile must refuse a metadata "
                    + "service endpoint: [" + metadataEndpoint + "]");
            assertTrue(run.output().contains("every profile"),
                    "the refusal must say that it applies to every profile and has no override. Output:\n"
                            + run.output());
        }
    }

    /**
     * An endpoint whose host is not in the allowlist is refused, and so is an endpoint with no allowlist.
     *
     * <p>The endpoint decides where every byte of this deployment's content goes and which host receives
     * requests signed with its credential, so it is not enough for it to be well formed. Requiring the host
     * to be named a second time is what turns "wherever this one variable happens to point" into "one of the
     * hosts this deployment declared": changing the endpoint alone then cannot redirect content traffic,
     * because the allowlist has to be changed with it.
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void anEndpointOutsideTheDeclaredAllowlistIsRefusedAndSoIsAnEndpointWithoutOne(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");

        Path noAllowlist = prepareSandbox(Files.createTempDirectory(tempDir, "no-allowlist"));
        Map<String, String> missing = staticCredentialEnvironment();
        missing.put(ENDPOINT_VARIABLE, ENDPOINT);
        assertRefused(render(tempDir, noAllowlist, missing), noAllowlist, ALLOWLIST_VARIABLE,
                "an endpoint override with no allowlist at all must abort the start");

        Path elsewhere = prepareSandbox(Files.createTempDirectory(tempDir, "not-listed"));
        Map<String, String> unlisted = staticCredentialEnvironment();
        unlisted.put(ENDPOINT_VARIABLE, ENDPOINT);
        unlisted.put(ALLOWLIST_VARIABLE, "someone.else.example.internal,third.example.internal");
        assertRefused(render(tempDir, elsewhere, unlisted), elsewhere, ALLOWLIST_VARIABLE,
                "an endpoint whose host nobody declared must abort the start");
    }

    /**
     * The allowlist matches whatever spelling of the host the operator used, and only whole entries.
     *
     * <p>An allowlist that failed to match a host the operator did declare would be as much of an outage as
     * one that matched too much, so the tolerated spellings are pinned: spacing after a comma, a different
     * case, a position other than first in the list, and an IPv6 literal written in either case - which
     * matters because a URI parser hands the host back bracketed while the allowlist entry is compared
     * unbracketed. A host that is merely a substring of an entry, or of which an entry is a substring, must
     * not match: that is the difference between an allowlist and a pattern.
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void theAllowlistMatchesWholeHostsAndToleratesSpacingAndCase(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");

        Map<String, String> accepted = new LinkedHashMap<>();
        accepted.put(ENDPOINT_HOST, ENDPOINT);
        accepted.put("first.example.internal ,  " + ENDPOINT_HOST, ENDPOINT);
        accepted.put(ENDPOINT_HOST.toUpperCase(Locale.ROOT), ENDPOINT);
        accepted.put("[fd00:abcd::1]", "https://[fd00:abcd::1]:9000");
        accepted.put("[FD00:ABCD::1]", "https://[fd00:abcd::1]:9000");
        accepted.put("10.20.30.40", "https://10.20.30.40:9000");
        for (Map.Entry<String, String> entry : accepted.entrySet()) {
            Path sandbox = prepareSandbox(Files.createTempDirectory(tempDir, "allowed"));
            Map<String, String> environment = staticCredentialEnvironment();
            environment.put(ENDPOINT_VARIABLE, entry.getValue());
            environment.put(ALLOWLIST_VARIABLE, entry.getKey());
            RendererRun run = render(tempDir, sandbox, environment);
            assertEquals(0, run.exitCode(), "the allowlist entry [" + entry.getKey() + "] must permit the "
                    + "endpoint [" + entry.getValue() + "]. Output:\n" + run.output());
        }

        Map<String, String> refused = new LinkedHashMap<>();
        refused.put("example.internal", ENDPOINT);
        refused.put("s." + ENDPOINT_HOST, ENDPOINT);
        refused.put(ENDPOINT_HOST + ".attacker.example", ENDPOINT);
        for (Map.Entry<String, String> entry : refused.entrySet()) {
            Path sandbox = prepareSandbox(Files.createTempDirectory(tempDir, "refused"));
            Map<String, String> environment = staticCredentialEnvironment();
            environment.put(ENDPOINT_VARIABLE, entry.getValue());
            environment.put(ALLOWLIST_VARIABLE, entry.getKey());
            assertRefused(render(tempDir, sandbox, environment), sandbox, ALLOWLIST_VARIABLE,
                    "the allowlist entry [" + entry.getKey() + "] must not permit the endpoint ["
                            + entry.getValue() + "]: an allowlist matches whole hosts, not substrings");
        }
    }

    /**
     * An allowlist entry carrying a scheme, a port or a path is refused rather than silently never matching.
     *
     * <p>An entry like {@code https://objects.example.internal:9000} looks to the operator who wrote it as
     * though it constrained the scheme and the port as well. It constrains neither, and it would not even
     * match the host - so the endpoint would be refused for a reason that had nothing to do with the mistake.
     * Refusing the entry names the real problem.
     *
     * @param invalid an allowlist value the renderer must refuse outright
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @ParameterizedTest
    @ValueSource(strings = {"https://objects.example.internal", "objects.example.internal:9000",
        "objects.example.internal/bucket", "objects.example.internal,", ",objects.example.internal",
        "objects.example.internal;other.example.internal", "-objects.example.internal",
        " objects.example.internal", "fd00:abcd::1", "*.example.internal"})
    public void anAllowlistEntryThatIsNotABareHostIsRefused(String invalid, @TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        Map<String, String> environment = staticCredentialEnvironment();
        environment.put(ENDPOINT_VARIABLE, ENDPOINT);
        environment.put(ALLOWLIST_VARIABLE, invalid);

        assertRefused(render(tempDir, sandbox, environment), sandbox, ALLOWLIST_VARIABLE,
                "an allowlist entry that is not a bare host must be refused: [" + invalid + "]");
    }

    /**
     * A plaintext endpoint needs the deployed profile to be off <em>and</em> the escape to be asked for.
     *
     * <p>Every object write carries the store credential and every read returns content that may not be
     * public, so an {@code http} endpoint exposes both to anything on the network path. A local MinIO
     * container normally has no certificate, so the development profile still has to work - but it has to be
     * asked for by name, because an {@code https} that was mistyped is otherwise indistinguishable from an
     * {@code http} that was chosen. The deployed profile refuses it whatever the escape says.
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void aPlaintextEndpointNeedsTheDevelopmentProfileAndAnExplicitEscape(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");

        Map<String, String> base = staticCredentialEnvironment();
        base.put(ENDPOINT_VARIABLE, "http://127.0.0.1:9000");
        base.put(ALLOWLIST_VARIABLE, "127.0.0.1");

        Path deployed = prepareSandbox(Files.createTempDirectory(tempDir, "prod"));
        Map<String, String> prod = new LinkedHashMap<>(base);
        prod.put("OFBIZ_PROFILE", "prod");
        prod.put(PLAINTEXT_VARIABLE, "true");
        assertRefused(render(tempDir, deployed, prod), deployed, ENDPOINT_VARIABLE,
                "the deployed profile must refuse a plaintext object-store endpoint even when the escape is set");

        Path unasked = prepareSandbox(Files.createTempDirectory(tempDir, "dev-unasked"));
        Map<String, String> silent = new LinkedHashMap<>(base);
        silent.put("OFBIZ_PROFILE", "dev");
        assertRefused(render(tempDir, unasked, silent), unasked, PLAINTEXT_VARIABLE,
                "plaintext must be asked for by name, so that a mistyped scheme is not accepted as a choice");

        Path development = prepareSandbox(Files.createTempDirectory(tempDir, "dev"));
        Map<String, String> dev = new LinkedHashMap<>(base);
        dev.put("OFBIZ_PROFILE", "dev");
        dev.put(PLAINTEXT_VARIABLE, "yes");
        RendererRun run = render(tempDir, development, dev);

        assertEquals(0, run.exitCode(),
                "the development profile must still work against a local store without a certificate. Output:\n"
                        + run.output());
        assertTrue(run.output().contains("WARNING") && run.output().contains(ENDPOINT_VARIABLE),
                "accepting a plaintext endpoint must be reported rather than silent. Output:\n" + run.output());
        Properties rendered = loadProperties(development.resolve(CONTENT_OVERRIDE));
        assertEquals("http://127.0.0.1:9000", rendered.getProperty(ENDPOINT_PROPERTY),
                "the endpoint must still be rendered in the development profile");
        assertEquals("true", rendered.getProperty(PLAINTEXT_PROPERTY),
                "the escape must be normalised to the literal the provider parses, or the provider - which "
                        + "applies the same rule - would refuse the endpoint the entry point just accepted");
    }

    /**
     * The identity the deployment authenticates as has to be named, and never inferred.
     *
     * <p>This is the defect, stated as a test. {@link S3ContentStore} used to select a static credential when
     * both credential properties happened to be present and the ambient AWS credential chain otherwise, so a
     * typo in one variable name, or a secret store that injected only one of the two, moved the deployment
     * onto a different identity - with different permissions, against a different account - and reported
     * nothing at all. Both an absent mode and an unrecognised one are refused, so the choice cannot be made
     * by accident.
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void theIdentityToAuthenticateAsMustBeNamedRatherThanInferred(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");

        Path absent = prepareSandbox(Files.createTempDirectory(tempDir, "no-mode"));
        Map<String, String> unnamed = staticCredentialEnvironment();
        unnamed.remove(CREDENTIALS_MODE_VARIABLE);
        assertRefused(render(tempDir, absent, unnamed), absent, CREDENTIALS_MODE_VARIABLE,
                "selecting the object store without naming an identity source must abort the start");

        for (String unrecognised : List.of("Static", "default_chain", "instance-role", "iam", "chain", "true")) {
            Path sandbox = prepareSandbox(Files.createTempDirectory(tempDir, "mode"));
            Map<String, String> environment = staticCredentialEnvironment();
            environment.put(CREDENTIALS_MODE_VARIABLE, unrecognised);
            assertRefused(render(tempDir, sandbox, environment), sandbox, CREDENTIALS_MODE_VARIABLE,
                    "an unrecognised identity source must abort the start: [" + unrecognised + "]");
        }
    }

    /**
     * A credential set that contradicts the named identity source is refused, in either direction.
     *
     * <p>Half a static credential used to select the ambient chain silently; a credential left behind after
     * switching to an instance role used to be ignored silently. Both are the same class of defect - the
     * deployment runs as an identity nobody chose - so both abort the start, and the refusal names the
     * variable that has to change.
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void aCredentialSetThatContradictsTheNamedIdentityIsRefused(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");

        Path keyOnly = prepareSandbox(Files.createTempDirectory(tempDir, "key-only"));
        Map<String, String> withoutSecret = staticCredentialEnvironment();
        withoutSecret.remove(SECRET_KEY_VARIABLE);
        assertRefused(render(tempDir, keyOnly, withoutSecret), keyOnly, SECRET_KEY_VARIABLE,
                "static credentials without the secret access key must abort the start");

        Path secretOnly = prepareSandbox(Files.createTempDirectory(tempDir, "secret-only"));
        Map<String, String> withoutKeyId = staticCredentialEnvironment();
        withoutKeyId.remove(ACCESS_KEY_VARIABLE);
        assertRefused(render(tempDir, secretOnly, withoutKeyId), secretOnly, ACCESS_KEY_VARIABLE,
                "static credentials without the access key id must abort the start");

        Path chainWithKey = prepareSandbox(Files.createTempDirectory(tempDir, "chain-with-key"));
        Map<String, String> contradiction = staticCredentialEnvironment();
        contradiction.put(CREDENTIALS_MODE_VARIABLE, CREDENTIALS_DEFAULT_CHAIN);
        contradiction.remove(SECRET_KEY_VARIABLE);
        assertRefused(render(tempDir, chainWithKey, contradiction), chainWithKey, ACCESS_KEY_VARIABLE,
                "the ambient chain with an access key id left over must abort the start: the credential would "
                        + "be ignored and the deployment would run as a different identity");

        Path chainWithSecret = prepareSandbox(Files.createTempDirectory(tempDir, "chain-with-secret"));
        Map<String, String> leftOver = staticCredentialEnvironment();
        leftOver.put(CREDENTIALS_MODE_VARIABLE, CREDENTIALS_DEFAULT_CHAIN);
        leftOver.remove(ACCESS_KEY_VARIABLE);
        assertRefused(render(tempDir, chainWithSecret, leftOver), chainWithSecret, SECRET_KEY_VARIABLE,
                "the ambient chain with a secret access key left over must abort the start");
    }

    /**
     * Asking for the ambient credential chain by name is a supported deployment, with no credential at all.
     *
     * <p>The refusals above must not become "credentials are mandatory": an instance role or a mounted
     * credential file is the recommended way to run this on a cloud platform, and both leave the credential
     * variables unset. Naming {@code default-chain} is what makes that a decision rather than a fallback, and
     * both credential properties then render blank.
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void theAmbientCredentialChainIsSupportedWhenItIsAskedForByName(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        Map<String, String> environment = staticCredentialEnvironment();
        environment.put(CREDENTIALS_MODE_VARIABLE, CREDENTIALS_DEFAULT_CHAIN);
        environment.remove(ACCESS_KEY_VARIABLE);
        environment.remove(SECRET_KEY_VARIABLE);

        RendererRun run = render(tempDir, sandbox, environment);

        assertEquals(0, run.exitCode(),
                "asking for the ambient chain by name is a supported deployment. Output:\n" + run.output());
        Properties rendered = loadProperties(sandbox.resolve(CONTENT_OVERRIDE));
        assertEquals(CREDENTIALS_DEFAULT_CHAIN, rendered.getProperty(CREDENTIALS_MODE_PROPERTY),
                "the mode must be rendered, because the provider requires it too and refuses to infer it");
        assertEquals("", rendered.getProperty(ACCESS_KEY_PROPERTY, ""),
                "the access key id must be left blank so the provider uses the ambient chain");
        assertEquals("", rendered.getProperty(SECRET_KEY_PROPERTY, ""),
                "the secret access key must be left blank so the provider uses the ambient chain");
    }

    /**
     * An addressing style that is not clearly a boolean aborts the start.
     *
     * <p>Path-style addressing is what most S3-compatible stores require and what Amazon S3 does not, so
     * getting it wrong makes every request fail. A value the shell cannot read as a boolean would previously
     * have been rendered verbatim for the provider to parse as {@code false}, quietly selecting the opposite
     * of what the manifest says whenever the manifest says something like {@code yes} or {@code on}.
     *
     * @param invalid a value that is not recognisably a boolean
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @ParameterizedTest
    @ValueSource(strings = {"maybe", "2", "TRUE!", "y", "enabled", "-"})
    public void anAddressingStyleThatIsNotABooleanAbortsTheStart(String invalid, @TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        Map<String, String> environment = staticCredentialEnvironment();
        environment.put(PATH_STYLE_VARIABLE, invalid);
        RendererRun run = render(tempDir, sandbox, environment);

        assertRefused(run, sandbox, PATH_STYLE_VARIABLE,
                "an addressing style that is not a boolean must abort the start: [" + invalid + "]");
    }

    /**
     * Recognisable booleans are normalised to the literal the provider parses.
     *
     * <p>{@code yes}, {@code 1} and {@code TRUE} all clearly mean the same thing to the operator who wrote
     * them, and none of them is what {@code Boolean.parseBoolean} reads as true. Normalising here is what
     * makes the manifest and the running behaviour agree.
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void aRecognisableBooleanIsNormalisedToWhatTheProviderParses(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");

        Map<String, String> expectations = new LinkedHashMap<>();
        expectations.put("true", "true");
        expectations.put("TRUE", "true");
        expectations.put("yes", "true");
        expectations.put("1", "true");
        expectations.put("false", "false");
        expectations.put("no", "false");
        expectations.put("0", "false");

        for (Map.Entry<String, String> expectation : expectations.entrySet()) {
            Path sandbox = prepareSandbox(Files.createTempDirectory(tempDir, "boolean"));
            Map<String, String> environment = staticCredentialEnvironment();
            environment.put(PATH_STYLE_VARIABLE, expectation.getKey());
            RendererRun run = render(tempDir, sandbox, environment);

            assertEquals(0, run.exitCode(), "[" + expectation.getKey() + "] must be accepted. Output:\n"
                    + run.output());
            assertEquals(expectation.getValue(),
                    loadProperties(sandbox.resolve(CONTENT_OVERRIDE)).getProperty(PATH_STYLE_PROPERTY),
                    "[" + expectation.getKey() + "] must be normalised to what the provider parses");
        }
    }

    /**
     * An object-store setting supplied while the provider is not {@code s3} aborts the deployed start.
     *
     * <p>The setting would have no effect at all, so content would go to the backend the provider names
     * while the manifest plainly asks for an object store, and nothing would report the difference. Which
     * half of the contradiction is the mistake cannot be guessed from inside the container, so the deployed
     * profile refuses it. The development profile renders the values - they are inert - and warns, because a
     * developer switching a local container between backends does this deliberately.
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void anInertObjectStoreSettingAbortsTheDeployedStartAndOnlyWarnsInDevelopment(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");

        for (String provider : List.of("database", "filesystem")) {
            Path deployed = prepareSandbox(Files.createTempDirectory(tempDir, "inert-prod"));
            Map<String, String> prod = new LinkedHashMap<>();
            prod.put("OFBIZ_PROFILE", "prod");
            prod.put(PROVIDER_VARIABLE, provider);
            prod.put(BUCKET_VARIABLE, "ofbiz-content");
            assertRefused(render(tempDir, deployed, prod), deployed, BUCKET_VARIABLE,
                    "an inert bucket must abort a deployed start that selected " + provider);
        }

        Path development = prepareSandbox(Files.createTempDirectory(tempDir, "inert-dev"));
        Map<String, String> dev = new LinkedHashMap<>();
        dev.put("OFBIZ_PROFILE", "dev");
        dev.put(PROVIDER_VARIABLE, "database");
        dev.put(BUCKET_VARIABLE, "ofbiz-content");
        RendererRun run = render(tempDir, development, dev);

        assertEquals(0, run.exitCode(),
                "a developer switching a local container between backends must not be blocked. Output:\n"
                        + run.output());
        assertTrue(run.output().contains("WARNING") && run.output().contains("have no effect"),
                "the inert setting must be reported rather than ignored. Output:\n" + run.output());
        assertEquals("database", loadProperties(development.resolve(CONTENT_OVERRIDE))
                .getProperty(PROVIDER_PROPERTY), "the provider the operator named must be the one rendered");
    }

    /**
     * A value carrying a newline is refused, so it cannot smuggle a second property into the render.
     *
     * <p>Each substitution replaces the remainder of one anchored line, so the only way a value could
     * introduce a declaration of its own is by embedding a line break. A bucket of
     * {@code ofbiz\ncontent.store.provider=database} would otherwise turn the object store off from inside
     * the value that was supposed to configure it.
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void aValueCarryingALineBreakCannotSmuggleASecondPropertyIntoTheRender(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");

        Map<String, String> injections = new LinkedHashMap<>();
        injections.put(BUCKET_VARIABLE, "ofbiz-content\n" + PROVIDER_PROPERTY + "=database");
        injections.put(REGION_VARIABLE, "us-east-1\n" + PROVIDER_PROPERTY + "=database");
        injections.put(ENDPOINT_VARIABLE, ENDPOINT + "\n" + PROVIDER_PROPERTY + "=database");
        injections.put(ALLOWLIST_VARIABLE, ENDPOINT_HOST + "\n" + PROVIDER_PROPERTY + "=database");
        injections.put(CREDENTIALS_MODE_VARIABLE, CREDENTIALS_STATIC + "\n" + PROVIDER_PROPERTY + "=database");
        injections.put(ACCESS_KEY_VARIABLE, "AKIAIOSFODNN7EXAMPLE\n" + PROVIDER_PROPERTY + "=database");
        injections.put(SECRET_KEY_VARIABLE, "aSecret\n" + PROVIDER_PROPERTY + "=database");

        for (Map.Entry<String, String> injection : injections.entrySet()) {
            Path sandbox = prepareSandbox(Files.createTempDirectory(tempDir, "injection"));
            Map<String, String> environment = staticCredentialEnvironment();
            environment.put(ENDPOINT_VARIABLE, ENDPOINT);
            environment.put(ALLOWLIST_VARIABLE, ENDPOINT_HOST);
            environment.put(injection.getKey(), injection.getValue());

            assertRefused(render(tempDir, sandbox, environment), sandbox, injection.getKey(),
                    "a value carrying a line break must be refused: " + injection.getKey());
        }
    }

    /**
     * A renamed or duplicated anchor in the committed resource aborts the start.
     *
     * <p>This is the read-back, and it exists because validation happens on the shell's copy of a value
     * while the application reads whatever {@link Properties} makes of the rendered line. A renamed anchor
     * leaves the property absent, so the factory falls back to database storage; a duplicated one lets
     * {@link Properties} use the declaration the substitution never reached. Both send content somewhere
     * other than where the manifest says, and neither produces any other symptom.
     *
     * <p>Both halves also assert that nothing survives the refusal. These two checks are the only ones in
     * the suite that fire <em>after</em> the file has been written, so they are the only ones that can leave
     * a rendered override on disk - and that override carries the object store's secret access key. An
     * operator who saw the refusal would reasonably believe the configuration was never accepted, while
     * {@code /ofbiz/config} takes class path precedence and the two renderers with an early return (this one
     * and the content URL prefix) write nothing on a later start that configures nothing: the leftover would
     * then be the effective configuration. So the credential is supplied here as a recognisable sentinel and
     * asserted absent from the whole sandbox and from the output.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void aRenamedOrDuplicatedAnchorInTheCommittedResourceAbortsTheStart(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");

        String sentinel = "refusalSentinel7c1dNeverAccepted";
        Map<String, String> environment = staticCredentialEnvironment();
        environment.put(SECRET_KEY_VARIABLE, sentinel);

        Path renamed = prepareSandbox(Files.createTempDirectory(tempDir, "renamed"));
        Path renamedSource = renamed.resolve(CONTENT_SOURCE);
        Files.writeString(renamedSource, Files.readString(renamedSource, StandardCharsets.UTF_8)
                .replace(BUCKET_PROPERTY + "=", "content.store.s3.bucketname="), StandardCharsets.UTF_8);
        RendererRun renamedRun = render(tempDir, renamed, environment);
        assertFalse(renamedRun.exitCode() == 0,
                "a renamed anchor must abort the start rather than render the property away. Output:\n"
                        + renamedRun.output());
        assertTrue(renamedRun.output().contains(CONTENT_SOURCE),
                "the refusal must name the resource whose anchor is missing. Output:\n" + renamedRun.output());
        assertNothingSurvivedTheRefusal(renamedRun, renamed, sentinel, "a renamed anchor");

        Path duplicated = prepareSandbox(Files.createTempDirectory(tempDir, "duplicated"));
        Path duplicatedSource = duplicated.resolve(CONTENT_SOURCE);
        Files.writeString(duplicatedSource, Files.readString(duplicatedSource, StandardCharsets.UTF_8)
                + System.lineSeparator() + BUCKET_PROPERTY + "=left-over-bucket" + System.lineSeparator(),
                StandardCharsets.UTF_8);
        RendererRun duplicatedRun = render(tempDir, duplicated, environment);
        assertFalse(duplicatedRun.exitCode() == 0,
                "a duplicated anchor must abort the start, because java.util.Properties uses the last "
                        + "declaration. Output:\n" + duplicatedRun.output());
        assertTrue(duplicatedRun.output().contains("exactly one declaration"),
                "the refusal must explain that exactly one declaration is required. Output:\n"
                        + duplicatedRun.output());
        assertNothingSurvivedTheRefusal(duplicatedRun, duplicated, sentinel, "a duplicated anchor");
    }

    /**
     * The whole chain, end to end: an environment variable becomes the provider the factory hands out.
     *
     * <p>Every other test in this suite stops at the rendered file, and every test in
     * {@link ContentStoreProviderSelectionTests} starts from a property already in memory. This one joins
     * them, because the defect lived precisely in the join: the properties were read and the variables were
     * advertised, and nothing connected the two. For each of the three backends the shell renders the file,
     * the values it wrote are published through the same {@code UtilProperties} lookup the providers read,
     * and the production {@link ContentStoreFactory} is asked what that configuration selects.
     *
     * <p>{@code database} is represented by {@code null}, which is what keeps the pre-existing
     * {@code DataResource} storage path in use unchanged - so the assertion for it is the absence of a
     * provider, not a provider that happens to write to the database.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void aSuppliedEnvironmentReachesTheProviderTheFactoryActuallyHandsOut(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");

        Map<String, String> snapshot = new LinkedHashMap<>();
        ContentStoreFactory.clearCache();
        try {
            assertSelectedProvider(tempDir, snapshot, "database", null);
            assertSelectedProvider(tempDir, snapshot, "filesystem", FileSystemContentStore.class);
            ContentStore selected = assertSelectedProvider(tempDir, snapshot, "s3", S3ContentStore.class);

            // The provider was constructed from the rendered values, so those values must be what the
            // production lookup its constructor uses actually returns. Asserting the lookup rather than the
            // object's private state keeps this a test of the chain and not of the class's field names.
            assertNotNull(selected, "the s3 case must have produced a provider");
            assertEquals("ofbiz-content", UtilProperties.getPropertyValue(CONTENT_RESOURCE, BUCKET_PROPERTY),
                    "the bucket the environment supplied must be the bucket the provider reads");
            assertEquals("eu-west-2", UtilProperties.getPropertyValue(CONTENT_RESOURCE, REGION_PROPERTY),
                    "the region the environment supplied must be the region the provider reads");
            assertEquals(ENDPOINT, UtilProperties.getPropertyValue(CONTENT_RESOURCE, ENDPOINT_PROPERTY),
                    "the endpoint the environment supplied must be the endpoint the provider reads");
            assertEquals(ENDPOINT_HOST, UtilProperties.getPropertyValue(CONTENT_RESOURCE, ALLOWLIST_PROPERTY),
                    "the allowlist the environment supplied must be the one the provider checks against");
            assertEquals(CREDENTIALS_STATIC,
                    UtilProperties.getPropertyValue(CONTENT_RESOURCE, CREDENTIALS_MODE_PROPERTY),
                    "the identity source the environment named must be the one the provider authenticates with");
            assertEquals("AKIAIOSFODNN7EXAMPLE",
                    UtilProperties.getPropertyValue(CONTENT_RESOURCE, ACCESS_KEY_PROPERTY),
                    "the access key id the environment supplied must be the one the provider reads");
            assertTrue(UtilProperties.getPropertyAsBoolean(CONTENT_RESOURCE, PATH_STYLE_PROPERTY, false),
                    "the addressing style the environment supplied must be the one the provider reads");
        } finally {
            snapshot.forEach((name, previous)
                    -> UtilProperties.setPropertyValueInMemory(CONTENT_RESOURCE, name, previous));
            ContentStoreFactory.clearCache();
        }
    }

    /**
     * Renders one provider's configuration through the shell, publishes what it wrote, and asks the factory.
     *
     * @param tempDir a per-test sandbox
     * @param snapshot collects the previous value of every property overridden, so the caller can restore it
     * @param provider the backend to configure
     * @param expected the provider class the factory must hand out, or {@code null} for database storage
     * @return whatever the factory resolved, so the caller can assert further on it
     * @throws Exception if the shell could not be run at all
     */
    private static ContentStore assertSelectedProvider(Path tempDir, Map<String, String> snapshot,
            String provider, Class<? extends ContentStore> expected) throws Exception {
        Path sandbox = prepareSandbox(Files.createTempDirectory(tempDir, "selected-" + provider));
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put(PROVIDER_VARIABLE, provider);
        if ("s3".equals(provider)) {
            environment.putAll(staticCredentialEnvironment());
            environment.put(REGION_VARIABLE, "eu-west-2");
            environment.put(ENDPOINT_VARIABLE, ENDPOINT);
            environment.put(ALLOWLIST_VARIABLE, ENDPOINT_HOST);
            environment.put(PATH_STYLE_VARIABLE, "yes");
        }

        RendererRun run = render(tempDir, sandbox, environment);
        assertEquals(0, run.exitCode(), "the " + provider + " configuration must render. Output:\n"
                + run.output());

        // Publish exactly what the shell wrote - no more - through the lookup the providers read from.
        Properties rendered = loadProperties(sandbox.resolve(CONTENT_OVERRIDE));
        for (String property : STORE_PROPERTIES) {
            snapshot.computeIfAbsent(property,
                    key -> UtilProperties.getPropertyValue(CONTENT_RESOURCE, property));
            UtilProperties.setPropertyValueInMemory(CONTENT_RESOURCE, property,
                    rendered.getProperty(property, ""));
        }

        ContentStoreFactory.clearCache();
        ContentStore selected = ContentStoreFactory.getContentStore();
        if (expected == null) {
            assertNull(selected, "OFBIZ_CONTENT_STORE_PROVIDER=" + provider + " must leave the pre-existing "
                    + "DataResource database storage in place, which the factory signals with null");
        } else {
            assertNotNull(selected, "OFBIZ_CONTENT_STORE_PROVIDER=" + provider
                    + " must reach a provider once the entry point has rendered it");
            assertEquals(expected, selected.getClass(), "OFBIZ_CONTENT_STORE_PROVIDER=" + provider
                    + " must select " + expected.getSimpleName());
        }
        return selected;
    }

    /**
     * Asserts that a run was refused, named the variable at fault, and left no override behind.
     *
     * <p>The third assertion is the one that is easy to forget and the one that matters most: a validator
     * that reports a problem after writing the file would leave the container with a configuration nobody
     * approved, which a supervisor restarting the container would then run on.
     *
     * @param run the completed render
     * @param sandbox the sandbox the render was driven against
     * @param variable the environment variable the refusal must name
     * @param reason what this case is demonstrating, reported when it fails
     * @throws IOException if the sandbox cannot be inspected
     */
    private static void assertRefused(RendererRun run, Path sandbox, String variable, String reason)
            throws IOException {
        assertFalse(run.exitCode() == 0, reason + ". Output:\n" + run.output());
        assertTrue(run.output().contains(variable),
                "the refusal must name " + variable + " so the operator knows what to correct. Output:\n"
                        + run.output());
        assertFalse(Files.exists(sandbox.resolve(CONTENT_OVERRIDE)),
                "a refused configuration must leave no rendered override behind, or a restart would run on "
                        + "it. Output:\n" + run.output());
    }

    /**
     * Asserts that a refusal raised <em>after</em> the file was written left neither the override nor the
     * credential it carried anywhere behind.
     *
     * <p>The read-back checks are the only ones in the render that run once the destination exists, so they
     * are the only ones that can leave one. A leftover would be a complete, loadable, mode 0600 file holding
     * a live object-store secret, sitting in the directory that takes class path precedence - and because the
     * renderer writes nothing at all when nothing is configured, the next start that configures nothing would
     * run on it. The whole sandbox and the output are searched, not just the override path, so a copy left
     * under another name or echoed into a log is caught too.</p>
     *
     * @param run the completed render
     * @param sandbox the sandbox the render was driven against
     * @param credential the secret access key that was supplied, which must appear nowhere
     * @param cause what was wrong with the source, reported when the assertion fails
     * @throws IOException if the sandbox cannot be walked
     */
    private static void assertNothingSurvivedTheRefusal(RendererRun run, Path sandbox, String credential,
            String cause) throws IOException {
        assertFalse(Files.exists(sandbox.resolve(CONTENT_OVERRIDE)),
                cause + " must leave no rendered override behind: it carries the object store's secret access "
                        + "key, and a later start that configures nothing renders nothing over it. Output:\n"
                        + run.output());
        assertFalse(run.output().contains(credential),
                cause + " must be reported without echoing the credential. Output:\n" + run.output());

        List<String> carrying = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(sandbox)) {
            for (Path candidate : tree.filter(Files::isRegularFile).collect(Collectors.toList())) {
                // Decoded with replacement rather than Files.readString, which throws on a byte sequence
                // that is not UTF-8 - the search must not depend on what else happens to be in the sandbox.
                String content = new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
                if (content.contains(credential)) {
                    carrying.add(sandbox.relativize(candidate).toString());
                }
            }
        }
        assertTrue(carrying.isEmpty(),
                cause + " must leave the credential in no file at all, but it survived in " + carrying);
    }

    /**
     * Returns the smallest object-store environment the renderer accepts, using a static credential.
     *
     * <p>Every mandatory variable is present and nothing else is: the provider, a bucket, a region, the
     * credentials mode and the credential pair that mode requires. No endpoint, so no allowlist is needed.
     * Tests that exercise one rule put their own value on top of this, which keeps each test showing only
     * the thing it is about.
     *
     * @return a mutable environment a test may add to
     */
    private static Map<String, String> staticCredentialEnvironment() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put(PROVIDER_VARIABLE, "s3");
        environment.put(BUCKET_VARIABLE, "ofbiz-content");
        environment.put(REGION_VARIABLE, "us-east-1");
        environment.put(CREDENTIALS_MODE_VARIABLE, CREDENTIALS_STATIC);
        environment.put(ACCESS_KEY_VARIABLE, "AKIAIOSFODNN7EXAMPLE");
        environment.put(SECRET_KEY_VARIABLE, "aSecretAccessKeyValue0123456789");
        return environment;
    }

    /**
     * Builds the minimum of the container's {@code /ofbiz} layout this renderer needs: the pristine
     * committed resource it substitutes into.
     *
     * @param base the directory to build the sandbox under
     * @return the sandbox directory that stands in for {@code /ofbiz}
     * @throws IOException if the sandbox cannot be created
     */
    private static Path prepareSandbox(Path base) throws IOException {
        Path sandbox = Files.createDirectories(base.resolve("ofbiz"));
        Path shipped = sandbox.resolve(CONTENT_SOURCE);
        Files.createDirectories(shipped.getParent());
        Files.copy(repositoryRoot().resolve(CONTENT_SOURCE), shipped);
        return sandbox;
    }

    /**
     * Runs the real entry point's object-store renderer as a black box against a sandbox.
     *
     * <p>Every inherited {@code OFBIZ_*} variable is removed from the child environment first, so a value
     * exported into the build cannot decide a case, and {@code OFBIZ_PROFILE} defaults to {@code dev} exactly
     * as the cases that do not care about the profile need it to.
     *
     * @param workDir a per-test temporary directory for the generated driver scripts
     * @param sandbox the directory the renderer treats as the OFBiz home
     * @param environment the variables to supply
     * @return the exit code and the combined output of the run
     * @throws Exception if the shell could not be run at all
     */
    private static RendererRun render(Path workDir, Path sandbox, Map<String, String> environment)
            throws Exception {
        Path driver = Files.createTempFile(workDir, "entrypoint-driver", ".sh");
        Files.writeString(driver, "#!/usr/bin/env bash\n"
                + ". " + shellQuote(entryPointLibrary(workDir)) + "\n"
                + "OFBIZ_PROFILE=\"${OFBIZ_PROFILE:-dev}\"\n"
                + "cd " + shellQuote(sandbox) + " || exit 1\n"
                + "render_content_store_configuration\n", StandardCharsets.UTF_8);

        ProcessBuilder builder = new ProcessBuilder("bash", driver.toString());
        builder.directory(sandbox.toFile());
        builder.redirectErrorStream(true);
        Map<String, String> processEnvironment = builder.environment();
        processEnvironment.keySet().removeIf(name -> name.startsWith("OFBIZ_"));
        processEnvironment.putAll(environment);

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the entry point renderer did not terminate");
        return new RendererRun(process.exitValue(), output);
    }

    /**
     * Writes the entry point out once per test as a sourceable library, with its trailing {@code _main} call
     * removed so that sourcing it defines the functions instead of running a container start.
     *
     * @param workDir where the library is written
     * @return the path of the generated library
     * @throws IOException if the entry point cannot be read or the library cannot be written
     */
    private static Path entryPointLibrary(Path workDir) throws IOException {
        Path library = workDir.resolve("entrypoint-library.sh");
        if (Files.exists(library)) {
            return library;
        }
        List<String> sourced = new ArrayList<>();
        for (String line : Files.readAllLines(repositoryRoot().resolve(ENTRY_POINT), StandardCharsets.UTF_8)) {
            if (!"_main \"$@\"".equals(line)) {
                sourced.add(line);
            }
        }
        Files.write(library, sourced, StandardCharsets.UTF_8);
        return library;
    }

    /** Single-quotes a path for safe interpolation into the generated driver. */
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

    /** What one black-box execution of the entry point's object-store renderer produced. */
    private record RendererRun(int exitCode, String output) { }
}
