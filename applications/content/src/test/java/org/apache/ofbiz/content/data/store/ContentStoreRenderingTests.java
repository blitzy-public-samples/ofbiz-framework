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
package org.apache.ofbiz.content.data.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Executable contract of the container's object-store configuration renderer: the step that turns the nine
 * {@code OFBIZ_CONTENT_STORE_PROVIDER} / {@code OFBIZ_S3_*} variables into
 * {@code /ofbiz/config/content.properties}, which is the resource {@link ContentStoreFactory} and
 * {@link S3ContentStore} actually read.
 *
 * <p>Everything else in this package tests the Java half: given a property, the factory selects a provider and
 * the provider talks to a store. That leaves the join between the two halves untested, and the join is where
 * Objective 3 either works or silently does not. A deployment supplies a bucket, an endpoint and a secret
 * access key; if the entry point does not render them into the resource on the class path, the application
 * reads the committed file instead - {@code content.store.provider=database}, every S3 key blank - and stores
 * content in the database while the manifest plainly asks for the object store. Nothing fails, nothing logs,
 * and the objective is simply not met. No amount of Java-side testing can see that, because the Java side is
 * given the property it expects by the test itself.
 *
 * <p>So these tests execute the shipped {@code docker/docker-entrypoint.sh} - sourced as a library with its
 * {@code _main "$@"} invocation removed, against a throwaway sandbox that stands in for {@code /ofbiz} - and
 * then read the file it wrote with the same {@link java.util.Properties} the application uses. Nothing is
 * reimplemented, nothing is stubbed and no network or container is involved: the render is a pure function of
 * the environment and the committed source file.
 *
 * <p>What is established, in order: that the committed source still carries every anchor the substitution
 * needs; that all nine variables reach the runtime resource and round trip through the three grammars they
 * pass through - the sed replacement, the properties value and the shell - byte for byte, including values made
 * of nothing but characters special to those grammars; that the rendered file is readable only by the user that
 * wrote it; that a later start replaces every value an earlier one wrote; that withdrawing the variables removes
 * the override rather than leaving a stale one to shadow the committed file forever; that the committed source
 * is never modified; that neither credential is printed, even under shell tracing, and that no temporary file
 * keeps a copy of one; and that every variable is withdrawn from the environment the JVM inherits.
 *
 * @see ContentStoreFactory
 * @see S3ContentStore
 */
public final class ContentStoreRenderingTests {

    /** The shipped script under test. */
    private static final String ENTRY_POINT = "docker/docker-entrypoint.sh";

    /** The pristine committed resource the render derives from. */
    private static final String SOURCE = "applications/content/config/content.properties";

    /** Where the render publishes, first on the generated start script's class path. */
    private static final String OVERRIDE = "config/content.properties";

    /**
     * How many lines above a declaration the environment marker for it may sit. Five is the largest distance the
     * committed resource actually uses - the endpoint's marker heads a four line note - and eight leaves room for
     * a note to grow without making the check meaningless.
     */
    private static final int MARKER_WINDOW = 8;

    /** The Java package the rendered properties are read by, used for the cross-layer census. */
    private static final String STORE_PACKAGE =
            "applications/content/src/main/java/org/apache/ofbiz/content/data/store/";

    /**
     * The nine variables and the nine properties they are rendered into, in the order the script pairs them.
     *
     * <p>Written here as one list rather than two so a case cannot assert a variable against the wrong
     * property, and so "every variable is covered" is a property of the table instead of a claim in a comment.
     */
    private static final List<Setting> SETTINGS = List.of(
            new Setting("OFBIZ_CONTENT_STORE_PROVIDER", "content.store.provider"),
            new Setting("OFBIZ_S3_BUCKET", "content.store.s3.bucket"),
            new Setting("OFBIZ_S3_REGION", "content.store.s3.region"),
            new Setting("OFBIZ_S3_ENDPOINT", "content.store.s3.endpoint"),
            new Setting("OFBIZ_S3_ACCESS_KEY_ID", "content.store.s3.access.key.id"),
            new Setting("OFBIZ_S3_SECRET_ACCESS_KEY", "content.store.s3.secret.access.key"),
            new Setting("OFBIZ_S3_PATH_STYLE", "content.store.s3.path.style"),
            new Setting("OFBIZ_S3_SSE", "content.store.s3.sse"),
            new Setting("OFBIZ_S3_SSE_KMS_KEY_ID", "content.store.s3.sse.kms.key.id"));

    /** The KMS key the fully configured fixture names, quoted in the rotation case as a withdrawn value. */
    private static final String KMS_KEY_FIXTURE = "arn:aws:kms:eu-west-1:111122223333:key/render-fixture";

    /** A complete object-store configuration, used wherever the case is about the render and not the values. */
    private static final Map<String, String> FULLY_CONFIGURED = Map.of(
            "OFBIZ_CONTENT_STORE_PROVIDER", "s3",
            "OFBIZ_S3_BUCKET", "ofbiz-content-fixture",
            "OFBIZ_S3_REGION", "eu-west-1",
            "OFBIZ_S3_ENDPOINT", "https://objects.example.internal:9000",
            "OFBIZ_S3_ACCESS_KEY_ID", "RENDER-FIXTURE-ACCESS-KEY",
            "OFBIZ_S3_SECRET_ACCESS_KEY", "RENDER-FIXTURE-SECRET-KEY",
            "OFBIZ_S3_PATH_STYLE", "true",
            // KMS rather than AES256, because it is the mode with a second value to get right: the key has
            // to be rendered too, and the colons in both the mode and the key ARN pass through the sed
            // replacement and the properties grammar that the escaping cases below exercise deliberately.
            "OFBIZ_S3_SSE", "aws:kms",
            "OFBIZ_S3_SSE_KMS_KEY_ID", KMS_KEY_FIXTURE);


    @BeforeAll
    public static void requireAShell() {
        assumeTrue(ShellDriver.isBashAvailable(), "a POSIX shell is required to drive the container entry point");
    }

    /**
     * The substitution is anchored on {@code ^<property>=}, so the committed source has to declare each of the
     * nine exactly once, at column one. A renamed anchor leaves the value unsubstituted; a duplicated one lets
     * {@link java.util.Properties} honour the declaration the substitution did not touch. Both are silent, and
     * both change where durable content is written, so the census is asserted before anything is rendered.
     *
     * @throws IOException if the committed resource cannot be read
     */
    @Test
    public void theCommittedSourceDeclaresEveryAnchorTheRenderSubstitutesExactlyOnce() throws IOException {
        List<String> committed = Files.readAllLines(ShellDriver.repositoryRoot().resolve(SOURCE),
                StandardCharsets.UTF_8);

        for (Setting setting : SETTINGS) {
            long declarations = committed.stream().filter(line -> line.startsWith(setting.property() + "=")).count();
            assertEquals(1L, declarations, SOURCE + " must declare " + setting.property() + " exactly once at column"
                    + " one: that line is the anchor the render replaces for " + setting.variable() + ", and a"
                    + " duplicate would let java.util.Properties use the declaration the render did not touch");
        }
    }

    /**
     * The whole of CR-03 in one case: every one of the nine variables reaches the resource the application
     * reads, and reaches it unchanged.
     *
     * <p>Read back with {@link java.util.Properties} rather than with a regular expression, because the
     * application reads it that way and the two differ - {@code Properties} interprets backslash escapes,
     * discards blanks after the {@code =} and honours the last of duplicated keys. Asserting on the raw line
     * would pass for a render that {@code Properties} then reads differently, which is precisely the failure
     * this case exists to catch.
     *
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the render cannot be driven
     */
    @Test
    public void everyObjectStoreVariableReachesTheRuntimeResourceTheApplicationReads(@TempDir Path tempDir)
            throws Exception {
        Path sandbox = prepareSandbox(tempDir);

        RendererRun run = render(tempDir, sandbox, FULLY_CONFIGURED);

        assertTrue(run.succeeded(), "a complete object-store configuration must render. Output:\n" + run.output());
        Properties rendered = loadProperties(sandbox.resolve(OVERRIDE));
        for (Setting setting : SETTINGS) {
            assertEquals(FULLY_CONFIGURED.get(setting.variable()), rendered.getProperty(setting.property()),
                    setting.variable() + " must arrive at " + setting.property() + " in " + OVERRIDE
                            + ": the application reads the property, never the variable. Output:\n" + run.output());
        }
    }

    /**
     * The committed resource annotates each of the nine declarations with the variable that supplies it - the
     * {@code <- OFBIZ_...} markers - and until now those markers were prose: nothing established that the
     * variable named beside a property is the variable the container really renders into it. A marker that names
     * the wrong variable is worse than no marker, because an operator configures what it says and gets something
     * else. Here each one is checked against the script's own pairing.
     *
     * @throws IOException if the committed resource or the script cannot be read
     */
    @Test
    public void everyEnvironmentMarkerCommentNamesTheVariableThatIsReallyRenderedIntoItsProperty()
            throws IOException {
        List<String> committed = Files.readAllLines(ShellDriver.repositoryRoot().resolve(SOURCE),
                StandardCharsets.UTF_8);

        for (Setting setting : SETTINGS) {
            int declaration = -1;
            for (int index = 0; index < committed.size(); index++) {
                if (committed.get(index).startsWith(setting.property() + "=")) {
                    declaration = index;
                    break;
                }
            }
            assertTrue(declaration >= 0, SOURCE + " must declare " + setting.property());
            // A window rather than the line immediately above, because one comment legitimately governs the two
            // credentials - they are all or nothing - and because the endpoint's marker heads a four line note.
            boolean marked = false;
            for (int index = Math.max(0, declaration - MARKER_WINDOW); index < declaration; index++) {
                String line = committed.get(index).trim();
                if (line.startsWith("#") && line.contains(setting.variable())) {
                    marked = true;
                    break;
                }
            }
            assertTrue(marked, SOURCE + " must name " + setting.variable() + " in the comments immediately above "
                    + setting.property() + ": that pairing is what the container actually renders, and a marker"
                    + " naming a different variable would have an operator configure the wrong one");
        }
    }

    /**
     * The join between the two halves, asserted as a census rather than through one render: every property the
     * shell substitutes has to be a property this Java package actually reads.
     *
     * <p>Neither half can see this on its own. A property renamed on the Java side leaves the shell rendering a
     * key nothing consumes - the configuration is applied to a resource that no longer declares what reads it -
     * and a property renamed in the script leaves the Java side reading a key the container never writes. Both
     * failures are silent and both mean the same thing: the deployment's object-store configuration has no
     * effect. The script's own array is parsed rather than restated, so the table above cannot drift from it
     * either.
     *
     * @throws IOException if the script or the Java sources cannot be read
     */
    @Test
    public void everyPropertyTheShellRendersIsAPropertyTheJavaProviderReads() throws IOException {
        List<String> declared = shellArray("CONTENT_STORE_PROPERTIES");
        assertEquals(SETTINGS.stream().map(Setting::property).toList(), declared, ENTRY_POINT + " renders a"
                + " different set of properties than this suite asserts against, so the table above no longer"
                + " describes the script");
        assertEquals(SETTINGS.stream().map(Setting::variable).toList(), shellArray("CONTENT_STORE_VARIABLES"),
                ENTRY_POINT + " censuses a different set of variables than this suite supplies, and the script"
                        + " pairs the two arrays by index - so a difference in either would pair a variable with"
                        + " the wrong property");

        String factory = Files.readString(ShellDriver.repositoryRoot().resolve(STORE_PACKAGE
                + "ContentStoreFactory.java"), StandardCharsets.UTF_8);
        String provider = Files.readString(ShellDriver.repositoryRoot().resolve(STORE_PACKAGE
                + "S3ContentStore.java"), StandardCharsets.UTF_8);
        for (String property : declared) {
            assertTrue(factory.contains('"' + property + '"') || provider.contains('"' + property + '"'),
                    "the container renders [" + property + "] into " + OVERRIDE + ", but neither"
                            + " ContentStoreFactory nor S3ContentStore reads a property of that name - so the"
                            + " value a deployment supplies for it would have no effect at all");
        }
    }

    /**
     * A render that changes nothing about the backend still has to produce the whole resource, because an
     * override in OFBiz is resolved per resource and not merged per key: a fragment holding only these nine
     * keys would lose the upload path prefix, the read bound and everything else the file declares.
     *
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the render cannot be driven
     */
    @Test
    public void theRenderPublishesTheWholeResourceAndNotOnlyTheKeysItSubstitutes(@TempDir Path tempDir)
            throws Exception {
        Path sandbox = prepareSandbox(tempDir);

        RendererRun run = render(tempDir, sandbox, FULLY_CONFIGURED);

        assertTrue(run.succeeded(), "the render must succeed. Output:\n" + run.output());
        Properties committed = loadProperties(sandbox.resolve(SOURCE));
        Properties rendered = loadProperties(sandbox.resolve(OVERRIDE));
        Set<String> substituted = SETTINGS.stream().map(Setting::property).collect(Collectors.toSet());
        for (String name : committed.stringPropertyNames()) {
            assertTrue(rendered.containsKey(name), OVERRIDE + " must declare every property the committed resource"
                    + " declares, and is missing [" + name + "]: OFBiz resolves a properties override per"
                    + " resource, so a key absent from the override is absent from the application's view");
            if (!substituted.contains(name)) {
                assertEquals(committed.getProperty(name), rendered.getProperty(name), "the render must leave ["
                        + name + "] exactly as the committed resource declares it: it carries no variable");
            }
        }
    }

    /**
     * The rendered file holds the object store's secret access key, and it lands on a declared volume that
     * outlives the container. Mode 0600 is what keeps it from being readable by anything else that mounts the
     * volume or shares the image's uid space.
     *
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the render cannot be driven
     */
    @Test
    public void theRenderedArtefactIsReadableOnlyByTheUserThatWroteIt(@TempDir Path tempDir) throws Exception {
        Path sandbox = prepareSandbox(tempDir);

        RendererRun run = render(tempDir, sandbox, FULLY_CONFIGURED);

        assertTrue(run.succeeded(), "the render must succeed. Output:\n" + run.output());
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(sandbox.resolve(OVERRIDE));
        assertEquals("rw-------", PosixFilePermissions.toString(permissions), OVERRIDE + " carries the object"
                + " store's secret access key and is written onto a declared volume, so it must be readable only"
                + " by the container user");
    }

    /**
     * Hostile values, and the reason there is a case for them at all: each value below passes through three
     * grammars in turn - the {@code sed} replacement that substitutes it, the {@code java.util.Properties} value
     * that is read back from it, and the shell that carries it - and each grammar assigns a meaning to a
     * different set of characters. An escaping mistake in any one of them does not fail loudly; it delivers a
     * different credential, bucket or endpoint than the one the deployment supplied, and the first symptom is
     * an authentication failure at the first content read with nothing to point at the cause.
     *
     * <p>The values are applied to the two credentials, which are the only settings whose grammar is
     * unrestricted - a bucket, a region and an endpoint each have a character set of their own, checked
     * elsewhere in this suite's companion - so these are the values that can actually contain such characters
     * in a real deployment. A generated secret access key routinely contains {@code /} and {@code +}.
     *
     * @param hostile a value made of characters special to the grammars it passes through
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the render cannot be driven
     */
    @ParameterizedTest(name = "[{0}] round trips unchanged")
    @MethodSource("hostileValues")
    public void aHostileValueRoundTripsThroughEveryGrammarUnchanged(String hostile, @TempDir Path tempDir)
            throws Exception {
        Path sandbox = prepareSandbox(Files.createTempDirectory(tempDir, "hostile"));

        Map<String, String> environment = new LinkedHashMap<>(FULLY_CONFIGURED);
        environment.put("OFBIZ_S3_ACCESS_KEY_ID", hostile);
        environment.put("OFBIZ_S3_SECRET_ACCESS_KEY", hostile + "-secret");
        RendererRun run = render(tempDir, sandbox, environment);

        assertTrue(run.succeeded(), "[" + hostile + "] must be rendered rather than refused: it contains no"
                + " control character and no leading blank, which are the only two things a credential may not"
                + " contain. Output:\n" + run.output());
        Properties rendered = loadProperties(sandbox.resolve(OVERRIDE));
        assertEquals(hostile, rendered.getProperty("content.store.s3.access.key.id"),
                "the access key id must read back exactly as supplied: [" + hostile + "]");
        assertEquals(hostile + "-secret", rendered.getProperty("content.store.s3.secret.access.key"),
                "the secret access key must read back exactly as supplied: [" + hostile + "-secret]");
    }

    /**
     * A credential whose edge whitespace would be trimmed away is refused rather than silently shortened.
     *
     * <p>The counterpart of the round trip above, and the reason a trailing blank is not one of its values.
     * {@code UtilProperties.getPropertyValue} returns {@code value.trim()}, so a credential ending in a space,
     * a tab or a form feed reaches the provider SHORTER than the value the render validated - the store then
     * refuses the request, and a deployment cannot tell that from a wrong key. A leading blank is worse still,
     * because {@code java.util.Properties} discards it before the accessor is even reached. Both ends are
     * therefore refused at the one place that can still name the variable.
     *
     * @param edge a value whose first or last character would not survive to the provider
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the render cannot be driven
     */
    @ParameterizedTest(name = "a credential [{0}] is refused rather than trimmed")
    @ValueSource(strings = {"trailing-blank ", "trailing-tab\t", "\ttab-leading", " blank-leading"})
    public void aCredentialWhoseEdgeWhitespaceWouldBeTrimmedAwayIsRefusedRatherThanSilentlyShortened(String edge,
            @TempDir Path tempDir) throws Exception {
        Path sandbox = prepareSandbox(Files.createDirectories(tempDir.resolve("edge-whitespace")));

        Map<String, String> environment = new LinkedHashMap<>(FULLY_CONFIGURED);
        environment.put("OFBIZ_S3_SECRET_ACCESS_KEY", edge);

        RendererRun run = render(tempDir, sandbox, environment);

        assertFalse(run.succeeded(), "a credential the accessor would trim must be refused rather than rendered:"
                + " the provider would receive a different secret from the one that was checked. Output:\n"
                + run.output());
        assertTrue(run.output().contains("OFBIZ_S3_SECRET_ACCESS_KEY"), "the refusal must name the variable to"
                + " correct. Output:\n" + run.output());
        assertFalse(run.output().contains(edge.trim()), "the refusal must not print the credential itself."
                + " Output:\n" + run.output());
    }

    /**
     * Values whose every character means something to at least one of the three grammars the render passes a
     * value through.
     *
     * @return the hostile values
     */
    private static Stream<String> hostileValues() {
        return Stream.of(
                // The sed replacement grammar: '&' is the whole match, '\1' a group, '|' the delimiter used here.
                "a&b|c\\1d",
                // A trailing backslash, which is the escape that swallows the newline the script writes after it.
                "trailing-backslash\\",
                // The java.util.Properties value grammar: ':' and '=' separate, '#' and '!' comment, '\' escapes.
                "colon:equals=hash#bang!backslash\\u0041",
                // The shell: expansion, command substitution, backtick substitution, quoting, globbing.
                "$HOME ${HOME} $(id) `id` \"quoted\" 'single' *?[]",
                // What a generated secret access key actually looks like: base64 with '/' and '+'.
                "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
                // Every ASCII punctuation character at once, so no single-character omission can hide.
                "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~",
                // A value that looks like a second declaration, which anchoring on '^key=' is what prevents.
                "x\\ncontent.store.provider=filesystem",
                // Inner spaces, which Properties preserves and which must survive verbatim rather than be
                // caught by a blanket "no whitespace" rule. Neither a TRAILING blank nor a tab is here: the
                // accessor every consumer uses returns value.trim(), so a credential with either would reach
                // the store shorter than the one that was checked, and both are refused - see
                // aCredentialWhoseEdgeWhitespaceWouldBeTrimmedAwayIsRefusedRatherThanSilentlyShortened.
                "inner blank between words");
    }

    /**
     * Rotation. A credential is replaced by restarting the container with a new one, so the render has to
     * overwrite the previous value rather than add to it - and the previous value must not survive anywhere in
     * the file. The check is byte-level and not property-level: a stale duplicate line further down would be
     * shadowed by {@code Properties} and so invisible to a property read, yet it would still be a copy of a
     * withdrawn credential sitting on the volume.
     *
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if either render cannot be driven
     */
    @Test
    public void aLaterStartReplacesEveryValueAnEarlierOneWroteAndLeavesNoTraceOfIt(@TempDir Path tempDir)
            throws Exception {
        Path sandbox = prepareSandbox(tempDir);

        RendererRun first = render(tempDir, sandbox, FULLY_CONFIGURED);
        assertTrue(first.succeeded(), "the first render must succeed. Output:\n" + first.output());

        Map<String, String> rotated = new LinkedHashMap<>(FULLY_CONFIGURED);
        rotated.put("OFBIZ_S3_BUCKET", "ofbiz-content-rotated");
        rotated.put("OFBIZ_S3_REGION", "us-east-2");
        rotated.put("OFBIZ_S3_ENDPOINT", "https://rotated.example.internal:9443");
        rotated.put("OFBIZ_S3_ACCESS_KEY_ID", "ROTATED-ACCESS-KEY");
        rotated.put("OFBIZ_S3_SECRET_ACCESS_KEY", "ROTATED-SECRET-KEY");
        rotated.put("OFBIZ_S3_PATH_STYLE", "false");
        // The encryption mode rotates with the rest, and rotates to the mode that must NOT carry a key: a
        // render that kept the withdrawn KMS key would be refused by require_object_store_encryption, and one
        // that rendered it anyway would leave the deployment naming a key it no longer encrypts with.
        rotated.put("OFBIZ_S3_SSE", "AES256");
        rotated.put("OFBIZ_S3_SSE_KMS_KEY_ID", "");
        RendererRun second = render(tempDir, sandbox, rotated);

        assertTrue(second.succeeded(), "the rotated render must succeed. Output:\n" + second.output());
        Properties rendered = loadProperties(sandbox.resolve(OVERRIDE));
        for (Setting setting : SETTINGS) {
            assertEquals(rotated.get(setting.variable()), rendered.getProperty(setting.property()),
                    setting.property() + " must hold the rotated value, not the one the earlier start wrote");
        }
        String bytes = Files.readString(sandbox.resolve(OVERRIDE), StandardCharsets.UTF_8);
        for (String withdrawn : List.of("RENDER-FIXTURE-ACCESS-KEY", "RENDER-FIXTURE-SECRET-KEY",
                "ofbiz-content-fixture", "objects.example.internal", KMS_KEY_FIXTURE)) {
            assertFalse(bytes.contains(withdrawn), "no trace of the superseded value [" + withdrawn + "] may"
                    + " survive anywhere in " + OVERRIDE + ": a shadowed duplicate is still a readable copy of a"
                    + " credential this deployment has withdrawn");
        }
        assertTrue(second.output().contains("already existed with different content"),
                "replacing a different file must be reported once, so an operator's discarded hand edit is not"
                        + " silent. Output:\n" + second.output());
    }

    /**
     * Environment withdrawal, half one: withdrawing the variables must remove the override, not leave it.
     *
     * <p>This is the case that is easy to get wrong by doing nothing at all. {@code /ofbiz/config} is a declared
     * volume that takes class path precedence, so a render left behind by a start that configured the object
     * store keeps selecting the object store on every later start - including one that deliberately configures
     * none. "Unset the variable" would then not switch the backend back, and the only symptom would be content
     * going to a store the manifest no longer mentions.
     *
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if either render cannot be driven
     */
    @Test
    public void withdrawingEveryVariableRemovesTheStaleOverrideSoTheCommittedResourceAppliesAgain(
            @TempDir Path tempDir) throws Exception {
        Path sandbox = prepareSandbox(tempDir);

        assertTrue(render(tempDir, sandbox, FULLY_CONFIGURED).succeeded(), "the configuring render must succeed");
        assertTrue(Files.exists(sandbox.resolve(OVERRIDE)), "the configuring render must have written an override");

        RendererRun withdrawn = render(tempDir, sandbox, Map.of());

        assertTrue(withdrawn.succeeded(), "withdrawing every object-store variable is not an error: it is how a"
                + " deployment returns to database storage. Output:\n" + withdrawn.output());
        assertFalse(Files.exists(sandbox.resolve(OVERRIDE)), "the stale " + OVERRIDE + " must be removed, because"
                + " it takes class path precedence and would otherwise keep selecting a backend this deployment"
                + " no longer asks for. Output:\n" + withdrawn.output());
        assertTrue(withdrawn.output().contains("removed the stale"), "the removal must be reported, so an"
                + " operator can see that the previous configuration is gone. Output:\n" + withdrawn.output());
    }

    /**
     * The same withdrawal on a container that never configured the object store writes nothing and says
     * nothing. Without this the removal above could be implemented as an unconditional {@code rm} whose notice
     * appeared on every zero-configuration start, and the quiet default this refactor must preserve would be
     * neither quiet nor obviously intact.
     *
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the render cannot be driven
     */
    @Test
    public void aContainerThatConfiguresNoObjectStoreRendersNothingAtAll(@TempDir Path tempDir) throws Exception {
        Path sandbox = prepareSandbox(tempDir);

        RendererRun run = render(tempDir, sandbox, Map.of());

        assertTrue(run.succeeded(), "configuring no object store must not be an error. Output:\n" + run.output());
        assertFalse(Files.exists(sandbox.resolve(OVERRIDE)), "with nothing configured there is nothing to"
                + " override, and an override written anyway would shadow the committed resource for no reason");
        assertFalse(run.output().contains("removed the stale"), "nothing was stale, so nothing may be reported"
                + " as removed. Output:\n" + run.output());
    }

    /**
     * A stale override that cannot be removed stops the start rather than being left in place.
     *
     * <p>This is the failure mode the removal exists for, taken one step further. If the removal is attempted
     * and does not succeed, the override is still on the class path ahead of the committed resource - so the
     * container would serve on a storage backend the deployment no longer asks for, and the only signal would be
     * a message about a file it could not delete. It is refused instead.
     *
     * <p>Driven by putting a non-empty DIRECTORY where the override belongs, which is the one way to make
     * {@code rm --force} fail that does not depend on file ownership - the test may be running as a user for
     * whom no permission bits deny anything. It is also a state a real volume can be in: a container runtime
     * asked to bind-mount a file that does not exist on the host creates a directory in its place.
     *
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the render cannot be driven
     */
    @Test
    public void aStaleOverrideThatCannotBeRemovedStopsTheStartInsteadOfBeingServed(@TempDir Path tempDir)
            throws Exception {
        Path sandbox = prepareSandbox(tempDir);
        Files.createDirectories(sandbox.resolve(OVERRIDE).resolve("not-empty"));

        RendererRun run = render(tempDir, sandbox, Map.of());

        assertFalse(run.succeeded(), "an override that cannot be removed must stop the start. Output:\n"
                + run.output());
        assertTrue(run.output().contains("could not be removed"), "the refusal must say what it could not do."
                + " Output:\n" + run.output());
        assertTrue(run.output().contains("a storage backend this deployment no longer asks for"),
                "the refusal must say why a surviving override matters. Output:\n" + run.output());
    }

    /**
     * Source immutability. The render reads the pristine committed resource and writes elsewhere, every start.
     * If it ever wrote back into the source tree, the second start would render from the first start's output:
     * a withdrawn credential would survive a restart that no longer supplies it, and a rotated one would be
     * rendered from a file that already held the old value.
     *
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if either render cannot be driven
     */
    @Test
    public void neitherRenderEverModifiesTheCommittedResourceItRendersFrom(@TempDir Path tempDir) throws Exception {
        Path sandbox = prepareSandbox(tempDir);
        Path source = sandbox.resolve(SOURCE);
        byte[] pristine = Files.readAllBytes(ShellDriver.repositoryRoot().resolve(SOURCE));

        assertTrue(render(tempDir, sandbox, FULLY_CONFIGURED).succeeded(), "the configuring render must succeed");
        assertArrayEqualsWithReason(pristine, Files.readAllBytes(source), "a configuring render");

        assertTrue(render(tempDir, sandbox, Map.of()).succeeded(), "the withdrawing render must succeed");
        assertArrayEqualsWithReason(pristine, Files.readAllBytes(source), "a withdrawing render");
    }

    /**
     * Secrecy, and the case that a review of the code alone cannot settle. Under {@code OFBIZ_TRACE} the shell
     * echoes each command with its arguments already expanded, and the render's arguments are the credentials.
     * The script brackets the whole render in {@code hide_secrets}/{@code restore_trace} for exactly this
     * reason; here the trace is switched on around the real function and the output is searched for both
     * credentials, so the bracketing is asserted rather than assumed.
     *
     * <p>The census the render begins with is inside the bracket too, which is easy to get wrong by one line:
     * the census tests the credential variables for emptiness, and a traced {@code [ -n ... ]} publishes the
     * value it is testing even though the loop only ever looks at whether it is empty.
     *
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the render cannot be driven
     */
    @Test
    public void neitherCredentialIsEverPrintedNotEvenUnderShellTracing(@TempDir Path tempDir) throws Exception {
        Path sandbox = prepareSandbox(tempDir);

        RendererRun run = renderWithTracing(tempDir, sandbox, FULLY_CONFIGURED);

        assertTrue(run.succeeded(), "tracing must not change the outcome of the render. Output:\n" + run.output());
        assertTrue(run.output().contains("+ render_content_store_configuration"), "the trace must actually be on,"
                + " or this case would pass against a run that printed nothing. Output:\n" + run.output());
        assertFalse(run.output().contains(FULLY_CONFIGURED.get("OFBIZ_S3_SECRET_ACCESS_KEY")),
                "the secret access key must not appear in a traced run's output. Output:\n" + run.output());
        assertFalse(run.output().contains(FULLY_CONFIGURED.get("OFBIZ_S3_ACCESS_KEY_ID")),
                "the access key id must not appear in a traced run's output either: it names the identity the"
                        + " deployment authenticates as. Output:\n" + run.output());
        Properties rendered = loadProperties(sandbox.resolve(OVERRIDE));
        assertEquals(FULLY_CONFIGURED.get("OFBIZ_S3_SECRET_ACCESS_KEY"),
                rendered.getProperty("content.store.s3.secret.access.key"),
                "the credential must still have been rendered - withheld from the log, not from the artefact");
    }

    /**
     * Secrecy, the untraced half. The summary line the render prints reports what the instance will do, and
     * deliberately not what it was given: the bucket and the region describe the deployment's storage topology,
     * the endpoint may itself carry a credential, and both credentials obviously may not be printed. What may
     * be said is the provider, whether an endpoint override is in force, the addressing style and which
     * credential source was selected.
     *
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the render cannot be driven
     */
    @Test
    public void theSummaryReportsWhatTheInstanceWillDoAndNotWhatItWasGiven(@TempDir Path tempDir) throws Exception {
        Path sandbox = prepareSandbox(tempDir);

        RendererRun run = render(tempDir, sandbox, FULLY_CONFIGURED);

        assertTrue(run.succeeded(), "the render must succeed. Output:\n" + run.output());
        for (String withheld : List.of("OFBIZ_S3_BUCKET", "OFBIZ_S3_REGION", "OFBIZ_S3_ENDPOINT",
                "OFBIZ_S3_ACCESS_KEY_ID", "OFBIZ_S3_SECRET_ACCESS_KEY")) {
            assertFalse(run.output().contains(FULLY_CONFIGURED.get(withheld)), "the value supplied in " + withheld
                    + " must not be printed. Output:\n" + run.output());
        }
        assertTrue(run.output().contains("Content storage provider: s3"), "the provider must be reported, because"
                + " which backend durable content goes to is the one thing an operator must be able to read off"
                + " the log. Output:\n" + run.output());
        assertTrue(run.output().contains("credentials [configured-properties]"), "which credential source was"
                + " selected must be reported, so an unintended fall back to the AWS default chain is visible."
                + " Output:\n" + run.output());
    }

    /**
     * Secrecy, the temporary-file half. The credential is substituted through a {@code sed} script written to a
     * file rather than through {@code --expression=}, because an expression would put the secret in this
     * script's own command line, where every process on the host can read it. That file, and the staging file
     * the render writes before publishing, must both be gone afterwards - and while they exist they must be
     * mode 0600, because they are on the same volume as the artefact.
     *
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the render cannot be driven
     */
    @Test
    public void noTemporaryFileKeepsACopyOfTheCredentialAfterTheRender(@TempDir Path tempDir) throws Exception {
        Path sandbox = prepareSandbox(tempDir);
        Path temporaries = Files.createDirectories(sandbox.resolve("tmp"));

        // Non-vacuity first, because "the directory is empty afterwards" is also what a render that never put
        // anything there would produce - and this case would then assert nothing at all. Pointing TMPDIR at a
        // directory that does not exist must make the render FAIL, which establishes that it really does create
        // its sed script under TMPDIR and therefore that the emptiness asserted below was earned.
        RendererRun withoutATemporaryDirectory = render(tempDir, sandbox, FULLY_CONFIGURED,
                Map.of("TMPDIR", sandbox.resolve("absent").toAbsolutePath().toString()));
        assertFalse(withoutATemporaryDirectory.succeeded(), "the render must create a temporary file under TMPDIR;"
                + " if it succeeds with TMPDIR pointing nowhere then it writes the credential somewhere this case"
                + " never looks. Output:\n" + withoutATemporaryDirectory.output());

        RendererRun run = render(tempDir, sandbox, FULLY_CONFIGURED,
                Map.of("TMPDIR", temporaries.toAbsolutePath().toString()));

        assertTrue(run.succeeded(), "the render must succeed. Output:\n" + run.output());
        List<Path> survivors = new ArrayList<>();
        try (Stream<Path> entries = Files.list(temporaries)) {
            entries.forEach(survivors::add);
        }
        assertTrue(survivors.isEmpty(), "the sed script carrying the secret access key must be removed by the"
                + " render: " + survivors + ". Output:\n" + run.output());
        try (Stream<Path> entries = Files.list(sandbox.resolve("config"))) {
            List<String> published = entries.map(entry -> entry.getFileName().toString()).sorted().toList();
            assertEquals(List.of("content.properties"), published, "only the published artefact may remain in the"
                    + " configuration directory: a staging file left beside it would be a second readable copy");
        }
    }

    /**
     * Environment withdrawal, half two: the variables are removed from the environment the OFBiz JVM inherits.
     *
     * <p>An environment variable is readable through {@code /proc/<pid>/environ} for the life of the process and
     * is inherited by every child, hook and crash handler, so leaving the two credentials there would publish
     * them to anything sharing the PID namespace - for the whole life of the instance, and long after the render
     * has already written them into a mode 0600 file the application reads instead. The five non-secret settings
     * go with them so that nothing downstream can read one and reach a different conclusion about which backend
     * this instance uses than the rendered artefact states.
     *
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the withdrawal cannot be driven
     */
    @Test
    public void everyObjectStoreVariableIsWithdrawnFromTheEnvironmentTheApplicationInherits(@TempDir Path tempDir)
            throws Exception {
        Path library = ShellDriver.sourceableLibrary(tempDir, ENTRY_POINT);
        StringBuilder body = new StringBuilder("withdraw_container_configuration\n");
        for (Setting setting : SETTINGS) {
            body.append("if [ -n \"${").append(setting.variable()).append(":-}\" ]; then printf '%s\\n' 'SURVIVED ")
                    .append(setting.variable()).append("'; fi\n");
        }
        body.append("printf '%s\\n' WITHDRAWN\n");
        Path driver = ShellDriver.driver(tempDir, library, body.toString());

        ShellDriver.Run run = ShellDriver.run(driver, tempDir, new LinkedHashMap<>(FULLY_CONFIGURED));

        assertTrue(run.succeeded(), "the withdrawal must succeed. Output:\n" + run.output());
        assertTrue(run.output().contains("WITHDRAWN"), "the driver must have run to completion. Output:\n"
                + run.output());
        for (Setting setting : SETTINGS) {
            assertFalse(run.output().contains("SURVIVED " + setting.variable()), setting.variable() + " must be"
                    + " withdrawn before the JVM is exec'd: the render has already written it into a mode 0600"
                    + " file, and a variable left in the environment is readable through /proc/<pid>/environ by"
                    + " anything sharing the PID namespace. Output:\n" + run.output());
        }
    }

    /**
     * A blank value is meaningful for eight of the nine properties, and the render has to preserve that rather
     * than treat blank as absent. Blank credentials are what select the AWS default provider chain - an
     * instance role, an ECS task role or an EKS service account - which is the recommended production
     * configuration, so a render that omitted the declarations would shadow the committed resource with the
     * properties missing and the chain would be selected by accident rather than by configuration.
     *
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the render cannot be driven
     */
    @Test
    public void aBlankCredentialPairIsDeclaredBlankSoTheAwsDefaultChainIsSelectedDeliberately(@TempDir Path tempDir)
            throws Exception {
        Path sandbox = prepareSandbox(tempDir);

        RendererRun run = render(tempDir, sandbox, ShellDriver.environment(
                "OFBIZ_CONTENT_STORE_PROVIDER", "s3",
                "OFBIZ_S3_BUCKET", "ofbiz-content-fixture",
                "OFBIZ_S3_REGION", "us-east-1"));

        assertTrue(run.succeeded(), "an s3 configuration with no credentials must render: it selects the AWS"
                + " default credential chain. Output:\n" + run.output());
        List<String> lines = Files.readAllLines(sandbox.resolve(OVERRIDE), StandardCharsets.UTF_8);
        for (String blank : List.of("content.store.s3.endpoint", "content.store.s3.access.key.id",
                "content.store.s3.secret.access.key")) {
            assertTrue(lines.contains(blank + "="), OVERRIDE + " must declare [" + blank + "] with an empty value"
                    + " rather than omit it: a property missing from an override reads back as empty too, so"
                    + " omitting it would make a renamed anchor indistinguishable from a deliberate blank");
        }
        assertTrue(run.output().contains("credentials [aws-default-provider-chain]"), "the summary must report"
                + " that the default chain was selected. Output:\n" + run.output());
    }

    /**
     * The unconfigured default, asserted from the artefact. Supplying only the provider is the smallest thing a
     * deployment can do that makes the render run at all, and what it must then render is the committed
     * default: database storage, every object-store key blank.
     *
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the render cannot be driven
     */
    @Test
    public void selectingTheDatabaseBackendRendersItWithEveryObjectStoreKeyLeftBlank(@TempDir Path tempDir)
            throws Exception {
        Path sandbox = prepareSandbox(tempDir);

        RendererRun run = render(tempDir, sandbox, Map.of("OFBIZ_CONTENT_STORE_PROVIDER", "database"));

        assertTrue(run.succeeded(), "selecting the database backend must render. Output:\n" + run.output());
        Properties rendered = loadProperties(sandbox.resolve(OVERRIDE));
        assertEquals("database", rendered.getProperty("content.store.provider"),
                "the database backend must be what the artefact selects");
        for (Setting setting : SETTINGS) {
            // Filtered by NAME rather than by position, so that adding a setting to the table cannot silently
            // move which ones this case asserts about. The two exclusions are the selector itself, asserted
            // just above, and the addressing style, which ofbiz_setup_env defaults to false on every start and
            // which is therefore rendered with a value rather than blank whatever the backend.
            if ("content.store.provider".equals(setting.property())
                    || "content.store.s3.path.style".equals(setting.property())) {
                continue;
            }
            assertEquals("", rendered.getProperty(setting.property()), setting.property() + " must be blank when"
                    + " no object store is configured, so nothing selects one by accident");
        }
        assertTrue(run.output().contains("No object store is contacted"), "the summary must say that no object"
                + " store is involved. Output:\n" + run.output());
    }

    /**
     * A renamed anchor in the committed resource makes the substitution match nothing, and the property would
     * then be missing from an override that shadows the file it was renamed in - so the application would read
     * no value at all for it. That is refused, and, because the destination has by then been written, the
     * refusal has to take the override with it: a leftover in {@code /ofbiz/config} takes class path precedence
     * and would become the effective configuration of the next start.
     *
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the render cannot be driven
     */
    @Test
    public void aRenamedAnchorIsRefusedAndTakesTheHalfRenderedOverrideWithIt(@TempDir Path tempDir)
            throws Exception {
        Path sandbox = prepareSandbox(tempDir);
        Path source = sandbox.resolve(SOURCE);
        Files.writeString(source, Files.readString(source, StandardCharsets.UTF_8)
                .replace("content.store.s3.bucket=", "content.store.s3.renamed="), StandardCharsets.UTF_8);

        RendererRun run = render(tempDir, sandbox, FULLY_CONFIGURED);

        assertFalse(run.succeeded(), "a renamed anchor must be refused rather than rendered as an absent"
                + " property. Output:\n" + run.output());
        assertTrue(run.output().contains("content\\.store\\.s3\\.bucket") || run.output().contains(SOURCE),
                "the refusal must name the property or the source whose anchor is missing. Output:\n"
                        + run.output());
        assertFalse(Files.exists(sandbox.resolve(OVERRIDE)), "a refused render must leave no override behind:"
                + " config/ takes class path precedence, so it would become the next start's configuration");
    }

    /**
     * A duplicated anchor is the shape in which a NON-substituted leftover silently wins, because
     * {@link java.util.Properties} honours the last declaration of a key. The render counts the declarations in
     * its own output and refuses when there is not exactly one.
     *
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the render cannot be driven
     */
    @Test
    public void aDuplicatedAnchorIsRefusedBecausePropertiesWouldHonourTheOtherDeclaration(@TempDir Path tempDir)
            throws Exception {
        Path sandbox = prepareSandbox(tempDir);
        Path source = sandbox.resolve(SOURCE);
        Files.writeString(source, Files.readString(source, StandardCharsets.UTF_8)
                + System.lineSeparator() + "content.store.provider=filesystem" + System.lineSeparator(),
                StandardCharsets.UTF_8);

        RendererRun run = render(tempDir, sandbox, FULLY_CONFIGURED);

        assertFalse(run.succeeded(), "a duplicated anchor must be refused: java.util.Properties would honour the"
                + " last declaration, so the selected backend would not be the validated one. Output:\n"
                + run.output());
        assertTrue(run.output().contains("2 times"), "the refusal must report how many declarations were found."
                + " Output:\n" + run.output());
        assertFalse(Files.exists(sandbox.resolve(OVERRIDE)), "a refused render must leave no override behind");
    }

    /**
     * The two independently written validators of the same environment - the early resolver that runs before the
     * database is touched, and this render - must agree about the backend, because a disagreement is a
     * disagreement about where durable content is written. Driven by setting the resolver's published answer to
     * something the render will not reach, which is the only way the disagreement can be produced at all.
     *
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the render cannot be driven
     */
    @Test
    public void aRenderThatDisagreesWithTheStartUpResolverIsRefusedAndDiscarded(@TempDir Path tempDir)
            throws Exception {
        Path sandbox = prepareSandbox(tempDir);
        Path library = ShellDriver.sourceableLibrary(tempDir, ENTRY_POINT);
        Path driver = ShellDriver.driver(tempDir, library, "cd " + ShellDriver.quote(sandbox) + " || exit 1\n"
                + "RESOLVED_CONTENT_STORE_PROVIDER=filesystem\n"
                + "render_content_store_configuration\n");

        ShellDriver.Run run = ShellDriver.run(driver, sandbox, new LinkedHashMap<>(FULLY_CONFIGURED));

        assertFalse(run.succeeded(), "two validations of one environment that disagree about the storage backend"
                + " must refuse the start. Output:\n" + run.output());
        assertTrue(run.output().contains("disagree"), "the refusal must say that the two validations disagree."
                + " Output:\n" + run.output());
        assertFalse(Files.exists(sandbox.resolve(OVERRIDE)), "the render the refusal distrusts must be discarded,"
                + " not left in config/ where it takes class path precedence");
    }

    /**
     * The two read-back guards on the rendered override are defence in depth behind an identical earlier check,
     * and this states that rather than leaving it to be inferred from their absence from the cases above.
     *
     * <p>{@code require_rendered_content_store_declarations} counts the declarations of
     * {@code content.store.provider} in the render and checks that the selected provider is the one that was
     * validated. Both conditions are already refused, for the same file and with the same effect, by the
     * per-property read-back that runs before it - which is why
     * {@link #aDuplicatedAnchorIsRefusedBecausePropertiesWouldHonourTheOtherDeclaration()} reports "2 times"
     * from the earlier check and never reaches this one. They are kept because they are written independently
     * of it and because the provider is the one property whose value decides where durable content is written,
     * but no environment can reach them while the earlier check is in place. What CAN be asserted is that they
     * remain wired into the render rather than being defined and never called - the failure that would turn
     * defence in depth into no defence at all.
     *
     * @throws IOException if the script cannot be read
     */
    @Test
    public void theProviderReadBackGuardsAreWiredIntoTheRenderEvenThoughAnEarlierCheckShadowsThem()
            throws IOException {
        List<String> script = Files.readAllLines(ShellDriver.repositoryRoot().resolve(ENTRY_POINT),
                StandardCharsets.UTF_8);

        long callSites = script.stream()
                .filter(line -> !line.stripLeading().startsWith("#"))
                .filter(line -> line.contains("require_rendered_content_store_declarations "))
                .count();
        assertEquals(1L, callSites, "require_rendered_content_store_declarations must be called exactly once by"
                + " the render: defined and never called, it would be no defence at all, and called twice it"
                + " would report the same condition twice");
    }

    /**
     * The resolver, which is the other half of what CR-03 names. It runs early - before the database is
     * touched - so that a deployment whose object-store configuration cannot work is refused in seconds rather
     * than after a data load, and it publishes the answer the render is later checked against.
     *
     * <p>Driven as a table because the interesting behaviour is the normalisation: the value is trimmed, folded
     * to lower case and matched against the recognised backends, and an unrecognised one falls back with a
     * warning rather than refusing to serve. An earlier revision had the resolver accept {@code S3}
     * case-insensitively while the render refused it, so the operator's first diagnostic named
     * {@code OFBIZ_S3_BUCKET} for a run that could never have started; that is what a shared normaliser and
     * this table prevent.
     *
     * @param supplied what {@code OFBIZ_CONTENT_STORE_PROVIDER} holds, or {@code <unset>}
     * @param expected the backend the resolver must publish
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the resolver cannot be driven
     */
    @ParameterizedTest(name = "provider [{0}] resolves to [{1}]")
    @MethodSource("providerSpellings")
    public void theResolverNormalisesTheBackendItPublishesForTheRenderToBeCheckedAgainst(String supplied,
            String expected, boolean refused, @TempDir Path tempDir) throws Exception {
        Map<String, String> environment = new LinkedHashMap<>();
        if (supplied != null) {
            environment.put("OFBIZ_CONTENT_STORE_PROVIDER", supplied);
        }
        // A bucket and a region, so that resolving 's3' gets past the settings the s3 backend requires and the
        // case is about the provider value alone.
        environment.put("OFBIZ_S3_BUCKET", "ofbiz-content-fixture");
        environment.put("OFBIZ_S3_REGION", "us-east-1");

        ShellDriver.Run run = resolve(tempDir, environment);

        assertTrue(run.succeeded(), "[" + supplied + "] is a value the resolver recognises and must not refuse."
                + " Output:\n" + run.output());
        assertTrue(run.output().contains("RESOLVED_CONTENT_STORE_PROVIDER=[" + expected + "]"),
                "[" + supplied + "] must resolve to [" + expected + "]. Output:\n" + run.output());
        // Asserted for every row, because for the rows whose normalised value equals what an unrecognised
        // value would have produced - 'DATABASE' among them - the resolved provider alone cannot distinguish
        // "recognised after trimming and folding case" from "not recognised at all". The absence of the
        // refusal wording can, and a value that reached the refusal would not have resolved at all.
        assertFalse(run.output().contains("not a storage backend this image recognises"),
                "[" + supplied + "] is a recognised backend, so nothing may report it as unrecognised - that"
                        + " wording here means the value was not recognised and something else answered by"
                        + " accident. Output:\n" + run.output());
    }

    /**
     * Every spelling of the backend selector, paired with what it must resolve to and whether the fallback
     * warning must be emitted.
     *
     * <p>A {@link MethodSource} rather than a CSV table on purpose: two of the rows differ from another row only
     * by a leading or trailing tab, and a CSV table does not carry a tab unambiguously.
     *
     * @return the rows
     */
    private static Stream<Arguments> providerSpellings() {
        return Stream.of(
                // Not configured at all, and the two ways a value can be present but say nothing.
                arguments(null, "", false),
                arguments("", "", false),
                arguments("   ", "", false),
                // Each recognised backend, and each in a case the operator might actually type.
                arguments("database", "database", false),
                arguments("DATABASE", "database", false),
                arguments("Database", "database", false),
                arguments("filesystem", "filesystem", false),
                arguments("FileSystem", "filesystem", false),
                arguments("s3", "s3", false),
                arguments("S3", "s3", false),
                // Surrounding blanks, which a value injected from a file or a secret manager routinely carries.
                arguments("  s3  ", "s3", false),
                arguments("\tS3\t", "s3", false),
                // A value that is not a backend at all is REFUSED rather than normalised, so it is not a row
                // of this table: see anUnrecognisedBackendStopsTheStartRatherThanBecomingDatabaseStorage.
                arguments("\ns3\n", "s3", false));
    }

    /**
     * A backend this image does not recognise stops the container instead of quietly becoming database storage.
     *
     * <p>Which backend holds durable content is not a decision an instance may make on an operator's behalf.
     * Content written to the wrong one is destroyed when the container is replaced, and neither layer would
     * report the substitution, so a mistyped value has to be refused where it can still be corrected - here,
     * before anything is rendered. {@code ContentStoreFactory} refuses the same value, so a container that
     * started anyway would fail at the first content read instead of at the start.
     *
     * @param provider a value that is not one of the three backends
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the resolver cannot be driven
     */
    @ParameterizedTest(name = "the backend \"{0}\" stops the start rather than becoming database storage")
    @ValueSource(strings = {"objectstore", "s3 bucket", "postgres", "none", "s3://ofbiz-content"})
    public void anUnrecognisedBackendStopsTheStartRatherThanBecomingDatabaseStorage(String provider,
            @TempDir Path tempDir) throws Exception {
        ShellDriver.Run run = resolve(tempDir, Map.of("OFBIZ_CONTENT_STORE_PROVIDER", provider));

        assertFalse(run.succeeded(), "an unrecognised backend must stop the start rather than be read as"
                + " database storage. Output:\n" + run.output());
        assertTrue(run.output().contains("not a storage backend this image recognises"),
                "the refusal must say the value was not recognised. Output:\n" + run.output());
        assertTrue(run.output().contains("OFBIZ_CONTENT_STORE_PROVIDER"),
                "the refusal must name the variable the operator has to correct. Output:\n" + run.output());
        for (String recognised : List.of("database", "filesystem", "s3")) {
            assertTrue(run.output().contains(recognised), "the refusal must list the accepted backend '"
                    + recognised + "' so the value can be corrected without reading the script. Output:\n"
                    + run.output());
        }
    }


    /**
     * Supplying a bucket, a region and a credential and then leaving the backend at its default is a
     * configuration that looks complete and stores nothing in the object store. It is reported by the resolver,
     * naming each setting that will have no effect - because the alternative is content going to the database
     * while the manifest plainly describes an object store, with nothing saying so.
     *
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the resolver cannot be driven
     */
    @Test
    public void objectStoreSettingsSuppliedWithoutSelectingTheObjectStoreAreReportedAsIneffective(
            @TempDir Path tempDir) throws Exception {
        Map<String, String> environment = new LinkedHashMap<>(FULLY_CONFIGURED);
        environment.remove("OFBIZ_CONTENT_STORE_PROVIDER");

        ShellDriver.Run run = resolve(tempDir, environment);

        assertTrue(run.succeeded(), "an unselected object store is not an error in itself. Output:\n"
                + run.output());
        assertTrue(run.output().contains("will have no effect"), "the resolver must report that the settings are"
                + " ineffective. Output:\n" + run.output());
        for (String ineffective : List.of("OFBIZ_S3_BUCKET", "OFBIZ_S3_REGION", "OFBIZ_S3_ENDPOINT",
                "OFBIZ_S3_ACCESS_KEY_ID", "OFBIZ_S3_SECRET_ACCESS_KEY")) {
            assertTrue(run.output().contains(ineffective), "the report must name " + ineffective + ", so the"
                    + " operator can see exactly which settings are being ignored. Output:\n" + run.output());
        }
        for (String withheld : List.of("OFBIZ_S3_ACCESS_KEY_ID", "OFBIZ_S3_SECRET_ACCESS_KEY")) {
            assertFalse(run.output().contains(FULLY_CONFIGURED.get(withheld)), "the report names the variable and"
                    + " must not print what it holds: " + withheld + ". Output:\n" + run.output());
        }
    }

    /**
     * Half a static credential pair is refused rather than completed from the ambient AWS chain. Accepting one
     * half would let a typo in a variable name, or a secret whose injection silently failed, move the deployment
     * from the credential the operator supplied to whatever instance role happened to be available - a
     * different principal, with different permissions on potentially different data, and no error at all.
     *
     * @param present the half that was supplied
     * @param absent the half that was not
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the resolver cannot be driven
     */
    @ParameterizedTest(name = "{0} without {1} is refused")
    @CsvSource({
        "OFBIZ_S3_ACCESS_KEY_ID,OFBIZ_S3_SECRET_ACCESS_KEY",
        "OFBIZ_S3_SECRET_ACCESS_KEY,OFBIZ_S3_ACCESS_KEY_ID",
    })

    public void halfAStaticCredentialPairIsRefusedInsteadOfFallingBackToAnAmbientIdentity(String present,
            String absent, @TempDir Path tempDir) throws Exception {
        Map<String, String> environment = new LinkedHashMap<>(FULLY_CONFIGURED);
        environment.remove(absent);

        ShellDriver.Run run = resolve(tempDir, environment);

        assertFalse(run.succeeded(), present + " without " + absent + " must be refused. Output:\n" + run.output());
        assertTrue(run.output().contains(present + " is set but " + absent + " is not"), "the refusal must name"
                + " both halves so the operator knows which one to supply. Output:\n" + run.output());
        assertFalse(run.output().contains(FULLY_CONFIGURED.get(present)), "the refusal must not print the half"
                + " that was supplied. Output:\n" + run.output());
    }

    /**
     * The two validators run in the order the container runs them, against one environment, and produce one
     * consistent artefact. This is the case that establishes the pair actually works together rather than only
     * that a disagreement is detected: the resolver publishes its answer, the render checks itself against it,
     * and what the application then reads is what both of them validated.
     *
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the pair cannot be driven
     */
    @Test
    public void theResolverAndTheRenderRunTogetherAndProduceOneConsistentArtefact(@TempDir Path tempDir)
            throws Exception {
        Path sandbox = prepareSandbox(tempDir);
        Path library = ShellDriver.sourceableLibrary(tempDir, ENTRY_POINT);
        Path driver = ShellDriver.driver(tempDir, library, "cd " + ShellDriver.quote(sandbox) + " || exit 1\n"
                + "resolve_content_store_configuration\n"
                + "render_content_store_configuration\n"
                + "printf 'CREDENTIAL_SOURCE=[%s]\\n' \"$RESOLVED_S3_CREDENTIAL_SOURCE\"\n");

        ShellDriver.Run run = ShellDriver.run(driver, sandbox, new LinkedHashMap<>(FULLY_CONFIGURED));

        assertTrue(run.succeeded(), "the resolver and the render must agree on a configuration they both accept."
                + " Output:\n" + run.output());
        assertTrue(run.output().contains("CREDENTIAL_SOURCE=[configured-properties]"), "the resolver must record"
                + " that static credentials were supplied. Output:\n" + run.output());
        Properties rendered = loadProperties(sandbox.resolve(OVERRIDE));
        for (Setting setting : SETTINGS) {
            assertEquals(FULLY_CONFIGURED.get(setting.variable()), rendered.getProperty(setting.property()),
                    "after both validators have run, " + setting.property() + " must hold what "
                            + setting.variable() + " supplied");
        }
    }

    /**
     * The one property the render DERIVES rather than substitutes: whether the provider may dial a plaintext
     * endpoint.
     *
     * <p>It has no variable of its own because the profile already decides it, and it is rendered rather than
     * left to a default so that the two layers agree: without it a developer pointing at
     * {@code http://minio.test:9000} would clear every check in the entry point and then be refused by the
     * provider on the first content read, with the container having reported success. Three properties are
     * asserted - that a development plaintext endpoint is permitted, that the permission is WITHDRAWN again
     * when the endpoint moves to https on the same volume, and that the deployed profile refuses plaintext
     * outright rather than permitting it.
     *
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the render cannot be driven
     */
    @Test
    public void thePlaintextEndpointPermissionFollowsTheProfileAndIsWithdrawnAgain(@TempDir Path tempDir)
            throws Exception {
        String permission = "content.store.s3.insecure.endpoint.allowed";
        Path development = prepareSandbox(Files.createDirectories(tempDir.resolve("dev-http")));

        RendererRun devHttp = render(tempDir, development, objectStoreOn("dev", "http://minio.test:9000"));

        assertTrue(devHttp.succeeded(), "a development container may use a plaintext store. Output:\n"
                + devHttp.output());
        assertEquals("true", loadProperties(development.resolve(OVERRIDE)).getProperty(permission),
                "a development http endpoint must be permitted in the rendered configuration, or the provider"
                        + " would refuse it on the first content read while this start reported success");

        // The same sandbox, which is the same volume: the permission must be withdrawn, never inherited.
        RendererRun devHttps = render(tempDir, development, objectStoreOn("dev", "https://minio.test:9000"));

        assertTrue(devHttps.succeeded(), "moving to https must render. Output:\n" + devHttps.output());
        assertEquals("false", loadProperties(development.resolve(OVERRIDE)).getProperty(permission),
                "an https endpoint needs no permission, so the value the previous start left must be replaced"
                        + " rather than kept");

        Path production = prepareSandbox(Files.createDirectories(tempDir.resolve("prod-http")));

        RendererRun prodHttp = render(tempDir, production, objectStoreOn("prod", "http://minio.test:9000"));

        assertFalse(prodHttp.succeeded(), "the deployed profile must refuse a plaintext endpoint outright rather"
                + " than permit it. Output:\n" + prodHttp.output());
        assertTrue(prodHttp.output().contains("OFBIZ_S3_ENDPOINT"), "the refusal must name the variable."
                + " Output:\n" + prodHttp.output());
        assertFalse(Files.exists(production.resolve(OVERRIDE)), "a refused endpoint must leave no rendered"
                + " override behind: config/ takes class-path precedence, so a leftover would become the"
                + " effective configuration on a later start");
    }

    /**
     * A complete object-store configuration on one profile and one endpoint.
     *
     * @param profile the {@code OFBIZ_PROFILE} to run under
     * @param endpoint the endpoint to configure, empty for none
     * @return the environment to supply
     */
    private static Map<String, String> objectStoreOn(String profile, String endpoint) {
        Map<String, String> environment = new LinkedHashMap<>(FULLY_CONFIGURED);
        environment.put("OFBIZ_PROFILE", profile);
        environment.put("OFBIZ_S3_ENDPOINT", endpoint);
        return environment;
    }

    /**
     * Drives the shipped resolver and prints the answer it publishes.
     *
     * @param workDir a per-test directory for the generated driver scripts
     * @param environment the {@code OFBIZ_*} variables to supply
     * @return what the run produced
     * @throws IOException if the script cannot be driven
     */
    private static ShellDriver.Run resolve(Path workDir, Map<String, String> environment) throws IOException {
        Path library = ShellDriver.sourceableLibrary(workDir, ENTRY_POINT);
        Path driver = ShellDriver.driver(workDir, library, "resolve_content_store_configuration\n"
                + "printf 'RESOLVED_CONTENT_STORE_PROVIDER=[%s]\\n' \"$RESOLVED_CONTENT_STORE_PROVIDER\"\n"
                + "printf 'RESOLVED_S3_BUCKET=[%s]\\n' \"$RESOLVED_S3_BUCKET\"\n"
                + "printf 'RESOLVED_S3_REGION=[%s]\\n' \"$RESOLVED_S3_REGION\"\n"
                + "printf 'RESOLVED_S3_CREDENTIAL_SOURCE=[%s]\\n' \"$RESOLVED_S3_CREDENTIAL_SOURCE\"\n");
        Map<String, String> variables = new LinkedHashMap<>(environment);
        ShellDriver.Run run = ShellDriver.run(driver, workDir, variables);
        assertFalse(run.timedOut(), "the resolver did not terminate, output was:\n" + run.output());
        return run;
    }

    /**
     * Asserts two byte arrays are equal, reporting which render was being checked rather than a byte offset.
     *
     * @param pristine the bytes the committed resource had before the render
     * @param actual the bytes it has after it
     * @param what the render that was performed, named in the failure
     */
    private static void assertArrayEqualsWithReason(byte[] pristine, byte[] actual, String what) {
        assertEquals(new String(pristine, StandardCharsets.UTF_8), new String(actual, StandardCharsets.UTF_8),
                what + " must leave " + SOURCE + " byte for byte as it was: the render reads the pristine"
                        + " committed resource on every start, so a write back into the source tree would make"
                        + " the next start render from the previous start's output - and a withdrawn credential"
                        + " would survive a restart that no longer supplies it");
    }

    /**
     * Builds the minimum of the container's {@code /ofbiz} layout the render needs: the pristine committed
     * resource it substitutes into.
     *
     * @param base the directory to build the sandbox under
     * @return the sandbox that stands in for {@code /ofbiz}
     * @throws IOException if the sandbox cannot be built
     */
    private static Path prepareSandbox(Path base) throws IOException {
        Path sandbox = Files.createDirectories(base.resolve("ofbiz"));
        Path source = sandbox.resolve(SOURCE);
        Files.createDirectories(source.getParent());
        Files.copy(ShellDriver.repositoryRoot().resolve(SOURCE), source);
        return sandbox;
    }

    /**
     * Runs the shipped renderer as a black box against a sandbox.
     *
     * @param workDir a per-test directory for the generated driver scripts
     * @param sandbox the directory the renderer treats as the OFBiz home
     * @param environment the {@code OFBIZ_*} variables to supply
     * @return the exit code and the combined output
     * @throws IOException if the script cannot be driven
     */
    private static RendererRun render(Path workDir, Path sandbox, Map<String, String> environment)
            throws IOException {
        return render(workDir, sandbox, environment, Map.of());
    }

    /**
     * Runs the shipped renderer with additional non-{@code OFBIZ_} variables, so a case can redirect
     * {@code TMPDIR} and inspect what the render left there.
     *
     * @param workDir a per-test directory for the generated driver scripts
     * @param sandbox the directory the renderer treats as the OFBiz home
     * @param environment the {@code OFBIZ_*} variables to supply
     * @param extra variables to add to the run's environment
     * @return the exit code and the combined output
     * @throws IOException if the script cannot be driven
     */
    private static RendererRun render(Path workDir, Path sandbox, Map<String, String> environment,
            Map<String, String> extra) throws IOException {
        return execute(workDir, sandbox, environment, extra, "");
    }

    /**
     * Runs the shipped renderer with shell tracing switched on around it, exactly as {@code OFBIZ_TRACE} does.
     *
     * <p>Driven by setting the script's own {@code TRACE_ENABLED} flag and {@code set -x} rather than by
     * exporting {@code OFBIZ_TRACE}, because the variable is consumed by {@code _main} - which the sourceable
     * library has had removed - so exporting it would leave the trace off and the case would pass vacuously.
     *
     * @param workDir a per-test directory for the generated driver scripts
     * @param sandbox the directory the renderer treats as the OFBiz home
     * @param environment the {@code OFBIZ_*} variables to supply
     * @return the exit code and the combined output
     * @throws IOException if the script cannot be driven
     */
    private static RendererRun renderWithTracing(Path workDir, Path sandbox, Map<String, String> environment)
            throws IOException {
        return execute(workDir, sandbox, environment, Map.of(), "TRACE_ENABLED=true\nset -x\n");
    }

    /**
     * Generates and runs a driver that sources the shipped script and calls the renderer.
     *
     * @param workDir a per-test directory for the generated driver scripts
     * @param sandbox the directory the renderer treats as the OFBiz home
     * @param environment the {@code OFBIZ_*} variables to supply
     * @param extra variables to add to the run's environment
     * @param preamble shell to run after sourcing and before the render
     * @return the exit code and the combined output
     * @throws IOException if the script cannot be driven
     */
    private static RendererRun execute(Path workDir, Path sandbox, Map<String, String> environment,
            Map<String, String> extra, String preamble) throws IOException {
        Path library = ShellDriver.sourceableLibrary(workDir, ENTRY_POINT);
        Path driver = ShellDriver.driver(workDir, library, "cd " + ShellDriver.quote(sandbox) + " || exit 1\n"
                + preamble + "render_content_store_configuration\n");
        Map<String, String> variables = new LinkedHashMap<>(environment);
        variables.putAll(extra);
        ShellDriver.Run run = ShellDriver.run(driver, sandbox, variables);
        assertFalse(run.timedOut(), "the renderer did not terminate, output was:\n" + run.output());
        return new RendererRun(run.exitCode(), run.output());
    }

    /**
     * Reads a properties file exactly the way the application reads it.
     *
     * <p>{@code load(InputStream)} and not {@code load(Reader)}, deliberately.
     * {@code UtilProperties.ExtendedProperties} - the class every {@code UtilProperties.getPropertyValue} call
     * on this resource ends up in - loads the URL through {@code load(InputStream)}, which is byte oriented.
     * Reading it here through a character reader would let a case pass on a value the application decodes
     * differently, which is the whole failure mode these tests exist to close.
     *
     * @param file the file to read
     * @return what the application will make of it
     * @throws IOException if the file cannot be read
     */
    private static Properties loadProperties(Path file) throws IOException {
        Properties properties = new Properties();
        try (InputStream bytes = Files.newInputStream(file)) {
            properties.load(bytes);
        }
        return properties;
    }

    /**
     * Parses a flat one-name-per-line bash array out of the shipped script, so a census can be taken from the
     * script itself rather than from a copy of it that would have to be kept in step by hand.
     *
     * @param name the array to read
     * @return the names it declares, in declaration order
     * @throws IOException if the script cannot be read
     */
    private static List<String> shellArray(String name) throws IOException {
        List<String> values = new ArrayList<>();
        boolean inside = false;
        for (String line : Files.readAllLines(ShellDriver.repositoryRoot().resolve(ENTRY_POINT),
                StandardCharsets.UTF_8)) {
            if (!inside) {
                inside = line.equals(name + "=(");
                continue;
            }
            if (line.startsWith(")")) {
                return values;
            }
            values.add(line.trim());
        }
        throw new IllegalStateException(ENTRY_POINT + " does not declare the array " + name);
    }

    /** One environment variable and the property the render pairs it with. */
    private record Setting(String variable, String property) {
        @Override
        public String toString() {
            return variable.toLowerCase(Locale.ROOT);
        }
    }

    /** What one black-box execution of the shipped renderer produced. */
    private record RendererRun(int exitCode, String output) {
        boolean succeeded() {
            return exitCode == 0;
        }
    }
}
