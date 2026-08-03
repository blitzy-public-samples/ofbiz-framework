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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.Reader;
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
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.apache.ofbiz.base.test.ShellDriver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Executable contract of the entry point's signing-key path - the renderer that puts the login and
 * JWT signing keys into {@code /ofbiz/config/security.properties} at container start.
 *
 * <p>Both keys used to be generated into the source tree while the distribution was
 * being built, which baked a live signing key into the distribution tarball and into a layer of every
 * published image. They are now resolved from the environment on each start and written only into a
 * mode 0600 file on a container-local volume, with the repository shipping both anchors blank. That
 * moves the entire behaviour into {@code render_security_configuration} and the helpers it calls, so
 * asserting the shape of the shell source establishes nothing: the behaviour that matters is what the
 * script actually does when a key is absent, unusable, or supplied.
 *
 * <p>The real {@code docker/docker-entrypoint.sh} is sourced as a library with its
 * trailing {@code _main "$@"} line removed, so the one function under test can be driven as a black
 * box without starting OFBiz, against a throwaway sandbox that stands in for {@code /ofbiz}. Nothing
 * is reimplemented and nothing is stubbed: the assertions below read the file the production script
 * wrote, its POSIX mode, and everything the script printed.
 *
 * <p>Three properties are asserted throughout: that a production deployment cannot start without a
 * usable key (fail-closed); that a key which could never sign is refused rather than accepted
 * (validation); and that no key value - supplied, generated or rejected - is ever echoed to the
 * container log or written to any file other than the mode 0600 configuration (non-disclosure).
 *
 * @see <a href="https://ofbiz.apache.org/">Apache OFBiz</a>
 */
public final class SigningKeyRenderingTests {

    private static final String ENTRY_POINT = "docker/docker-entrypoint.sh";
    private static final String SECURITY_SOURCE = "framework/security/config/security.properties";
    private static final String SECURITY_OVERRIDE = "config/security.properties";

    private static final String LOGIN_KEY_PROPERTY = "login.secret_key_string";
    private static final String JWT_KEY_PROPERTY = "security.token.key";
    private static final String HOST_HEADERS_PROPERTY = "host-headers-allowed";

    private static final String LOGIN_KEY_VARIABLE = "OFBIZ_LOGIN_SECRET_KEY";
    private static final String JWT_KEY_VARIABLE = "OFBIZ_JWT_TOKEN_KEY";

    /**
     * Every {@code OFBIZ_*} variable the renderer reads. All of them are removed from the environment
     * of each run before the case's own values are applied, so a value exported into the build's
     * environment can neither satisfy nor break a case.
     */
    private static final List<String> RENDERER_VARIABLES = List.of("OFBIZ_TRACE", "OFBIZ_PROFILE",
            LOGIN_KEY_VARIABLE, JWT_KEY_VARIABLE, "OFBIZ_HOST");

    /**
     * Keys that are deliberately awkward rather than merely long: each carries a character that is
     * special to one of the three grammars the value passes through - the sed replacement, the
     * {@code java.util.Properties} value, and the shell - so a substitution that escapes any of them
     * incorrectly fails the verbatim round trip below instead of silently corrupting a key.
     */
    private static final String LOGIN_KEY = "Login/Signing\\Key&With|Pipe#Hash\"Quote'Apos%Pct$Dollar:Colon=Eq01";
    private static final String JWT_KEY = "Jwt/Token\\Signing&Key|With#Every\"Awkward'Character%Here$Too:Now=02";
    private static final String ROTATED_LOGIN_KEY =
            "RotatedLoginSigningKeyForTheSecondRenderOfTheSameSandbox-0123456789";
    private static final String ROTATED_JWT_KEY =
            "RotatedJwtTokenSigningKeyForTheSecondRenderOfTheSameSandbox-9876543";

    /** OFBiz signs with HMAC512, so a key shorter than 64 characters cannot be used at all. */
    private static final int SIGNING_KEY_MIN_LENGTH = 64;

    /** The entropy floor the script enforces, which is what stops a long but trivial key. */
    private static final int MIN_DISTINCT_CHARACTERS = 8;

    private static final String ALLOWED_HOST = "ofbiz.internal.example";

    @Test
    public void theShippedSourceStillCarriesBothAnchorsBlankSoNothingIsInheritedFromTheRepository() throws IOException {
        // The premise of every case below: what the renderer starts from holds no key at all. If this
        // ever stopped being true, a "rendered" key could be one the repository published.
        Properties shipped = loadProperties(repositoryRoot().resolve(SECURITY_SOURCE));
        assertEquals("", shipped.getProperty(LOGIN_KEY_PROPERTY), LOGIN_KEY_PROPERTY + " must ship blank");
        assertEquals("", shipped.getProperty(JWT_KEY_PROPERTY), JWT_KEY_PROPERTY + " must ship blank");
    }

    /*
     * Fail-closed: production may not start on a key it does not have
     */

    @ParameterizedTest(name = "prod without {0} must not start")
    @ValueSource(strings = {LOGIN_KEY_VARIABLE, JWT_KEY_VARIABLE})
    public void productionRefusesToStartWhenEitherSigningKeyIsAbsent(String absent, @TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);
        // Exactly one key is withheld, so the failure can only be attributed to the missing one.
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("OFBIZ_PROFILE", "prod");
        environment.put(LOGIN_KEY_VARIABLE, LOGIN_KEY);
        environment.put(JWT_KEY_VARIABLE, JWT_KEY);
        environment.remove(absent);

        RendererRun run = renderSecurityConfiguration(tempDir, sandbox, environment);

        // Generating a key in production would be worse than refusing to start: it would sign tokens
        // with a value no other instance of the fleet holds, so a token issued by one instance would be
        // rejected by the next, and forgot-password material encrypted by one would be undecryptable.
        assertNotEquals(0, run.exitCode(), "prod must fail fast, output was:\n" + run.output());
        assertTrue(run.output().contains(absent),
                "the failure must name the missing variable, output was:\n" + run.output());
        assertFalse(Files.exists(sandbox.resolve(SECURITY_OVERRIDE)),
                "no configuration may be written when a required secret is absent");
        // The key that WAS supplied must not be echoed while reporting the absence of the other one.
        assertNoKeyValueLeaked(run, sandbox);
    }

    @Test
    public void productionRefusesToStartWhenNeitherSigningKeyIsSupplied(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        RendererRun run = renderSecurityConfiguration(tempDir, sandbox, Map.of("OFBIZ_PROFILE", "prod"));

        assertNotEquals(0, run.exitCode(), "prod must fail fast, output was:\n" + run.output());
        // The first key resolved is the one reported, and it has to be reported by name so an operator
        // can act on the message without the message quoting anything secret.
        assertTrue(run.output().contains(LOGIN_KEY_VARIABLE),
                "the failure must name a missing variable, output was:\n" + run.output());
        assertFalse(Files.exists(sandbox.resolve(SECURITY_OVERRIDE)),
                "no configuration may be written when a required secret is absent");
        assertFalse(Files.exists(sandbox.resolve("secrets").resolve(LOGIN_KEY_VARIABLE)),
                "prod must not fall back to generating and storing a key");
    }

    /*
     * Validation: a key that could never sign is refused, and never echoed
     */

    @ParameterizedTest(name = "a signing key that {0} is rejected")
    @MethodSource("unusableSigningKeys")
    public void aSigningKeyThatCouldNeverSignIsRejectedWithoutBeingEchoedAnywhere(String reason, String unusable,
            String diagnostic, @TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        // Both variables are checked, because a validation rule applied to one key and not the other
        // would leave half the signing surface unguarded.
        for (String variable : List.of(LOGIN_KEY_VARIABLE, JWT_KEY_VARIABLE)) {
            Path sandbox = prepareSandbox(Files.createTempDirectory(tempDir, "case"));
            Map<String, String> environment = new LinkedHashMap<>();
            environment.put("OFBIZ_PROFILE", "prod");
            environment.put(LOGIN_KEY_VARIABLE, LOGIN_KEY);
            environment.put(JWT_KEY_VARIABLE, JWT_KEY);
            environment.put(variable, unusable);
            // Compared as a before-and-after rather than an empty set, because a short rejected value can
            // legitimately occur inside the shipped file's own prose and only what the render ADDS matters.
            Set<String> before = filesContaining(sandbox, unusable);

            RendererRun run = renderSecurityConfiguration(tempDir, sandbox, environment);

            assertNotEquals(0, run.exitCode(),
                    variable + " that " + reason + " must be rejected, output was:\n" + run.output());
            assertTrue(run.output().contains(variable),
                    "the failure must name the offending variable, output was:\n" + run.output());
            // Which rule rejected the key, not merely that one did. An operator who left the shipped
            // placeholder in place and is told "must be at least 64 characters long" will lengthen the
            // placeholder and be rejected again; the diagnostic has to name the defect it found, so each
            // rule has to be observed through its own message rather than through the exit code that
            // every rule shares.
            assertTrue(run.output().contains(diagnostic),
                    "a key that " + reason + " must be diagnosed as '" + diagnostic + "', output was:\n"
                            + run.output());
            assertFalse(Files.exists(sandbox.resolve(SECURITY_OVERRIDE)),
                    "a rejected key must leave no configuration behind (" + reason + ")");
            // The whole point of reporting a reason rather than the value: a rejected secret is still a
            // secret, and a container log is not a place to put one.
            assertFalse(run.output().contains(unusable),
                    "the rejected " + variable + " leaked into the output:\n" + run.output());
            assertEquals(before, filesContaining(sandbox, unusable),
                    "the rejected " + variable + " was written into the sandbox");
            assertFalse(Files.exists(sandbox.resolve("secrets").resolve(variable)),
                    "a rejected key must not be stored for reuse on the next start");
        }
    }

    private static Stream<Arguments> unusableSigningKeys() {
        // Every rule secret_is_usable enforces, expressed as the deployment mistake it prevents, together
        // with the diagnostic that mistake has to produce. Each value is long enough to reach the rule it
        // is meant to exercise, so no case is decided by an earlier rule than the one it names - except
        // the placeholder, which is short by definition and is therefore the one case where asserting the
        // diagnostic is the only way to tell the rules apart at all.
        String entropy = "has insufficient entropy - it uses fewer than " + MIN_DISTINCT_CHARACTERS
                + " distinct characters";
        return Stream.of(
                Arguments.of("is one character short of the HMAC512 floor",
                        "OneCharacterShortOfTheHmac512FloorForASigningKey-0123456789abcd",
                        "must be at least " + SIGNING_KEY_MIN_LENGTH + " characters long"),
                Arguments.of("is the publicly known NA placeholder", "NA",
                        "must not be the publicly known placeholder value shipped as the code default"),
                Arguments.of("repeats a single character", "z".repeat(SIGNING_KEY_MIN_LENGTH), entropy),
                Arguments.of("uses only two distinct characters", "qp".repeat(SIGNING_KEY_MIN_LENGTH / 2), entropy),
                Arguments.of("smuggles a second property in on a line break",
                        "SigningKeyThatSmugglesALineBreakIntoThePropertiesFile\nrogue=1-abcd",
                        "must not contain a control character"));
    }

    /*
     * Supplied keys: rendered verbatim, into one protected file and nowhere else
     */

    @Test
    public void suppliedSigningKeysAreRenderedVerbatimIntoAFileOnlyTheOfbizUserCanRead(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        RendererRun run = renderSecurityConfiguration(tempDir, sandbox, Map.of(
                "OFBIZ_PROFILE", "prod",
                LOGIN_KEY_VARIABLE, LOGIN_KEY,
                JWT_KEY_VARIABLE, JWT_KEY));

        assertEquals(0, run.exitCode(), "the render must succeed, output was:\n" + run.output());
        Path override = sandbox.resolve(SECURITY_OVERRIDE);
        Properties rendered = loadProperties(override);
        // Verbatim matters literally: a key that survives the render altered signs with a value the
        // operator did not configure, so every instance of the fleet would have to be altered the same
        // way for tokens to verify. The awkward characters in these values are what make that visible.
        assertEquals(LOGIN_KEY, rendered.getProperty(LOGIN_KEY_PROPERTY),
                "the login signing key must round-trip through sed and java.util.Properties unchanged");
        assertEquals(JWT_KEY, rendered.getProperty(JWT_KEY_PROPERTY),
                "the JWT signing key must round-trip through sed and java.util.Properties unchanged");
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(override),
                "a file holding a signing key must not be readable by other accounts in the container");
        assertNoKeyValueLeaked(run, sandbox);
    }

    @Test
    public void aSuppliedKeyReachesTheRenderedConfigurationAndNoOtherFile(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        RendererRun run = renderSecurityConfiguration(tempDir, sandbox, Map.of(
                "OFBIZ_PROFILE", "prod",
                LOGIN_KEY_VARIABLE, LOGIN_KEY,
                JWT_KEY_VARIABLE, JWT_KEY));

        assertEquals(0, run.exitCode(), "the render must succeed, output was:\n" + run.output());
        // Exactly one file, and it is the mode 0600 override. In particular the pristine source in the
        // image's copy of the tree must be untouched, because that file is world readable and is what
        // a distribution tarball and an image layer carry.
        assertEquals(Set.of(SECURITY_OVERRIDE), filesContaining(sandbox, LOGIN_KEY),
                "the login signing key must exist in exactly one file");
        assertEquals(Set.of(SECURITY_OVERRIDE), filesContaining(sandbox, JWT_KEY),
                "the JWT signing key must exist in exactly one file");
        assertEquals("", loadProperties(sandbox.resolve(SECURITY_SOURCE)).getProperty(LOGIN_KEY_PROPERTY),
                "the render must not write back into the source file the image ships");
    }

    @Test
    public void aRotatedKeyPairTakesEffectOnTheNextRenderRatherThanBeingIgnored(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);
        Path override = sandbox.resolve(SECURITY_OVERRIDE);

        RendererRun first = renderSecurityConfiguration(tempDir, sandbox, Map.of(
                "OFBIZ_PROFILE", "prod", LOGIN_KEY_VARIABLE, LOGIN_KEY, JWT_KEY_VARIABLE, JWT_KEY));
        assertEquals(0, first.exitCode(), "the first render must succeed, output was:\n" + first.output());

        RendererRun second = renderSecurityConfiguration(tempDir, sandbox, Map.of(
                "OFBIZ_PROFILE", "prod", LOGIN_KEY_VARIABLE, ROTATED_LOGIN_KEY, JWT_KEY_VARIABLE, ROTATED_JWT_KEY));

        assertEquals(0, second.exitCode(), "the second render must succeed, output was:\n" + second.output());
        // Each render starts from the pristine source rather than from the previous render, which is
        // what makes a key rotation take effect on a restart instead of being masked by the old value.
        Properties rendered = loadProperties(override);
        assertEquals(ROTATED_LOGIN_KEY, rendered.getProperty(LOGIN_KEY_PROPERTY),
                "a rotated login key must replace the previous render");
        assertEquals(ROTATED_JWT_KEY, rendered.getProperty(JWT_KEY_PROPERTY),
                "a rotated JWT key must replace the previous render");
        assertEquals(Set.of(), filesContaining(sandbox, LOGIN_KEY),
                "the superseded login key must not survive anywhere in the container");
    }

    @Test
    public void theRenderChangesNothingButTheTwoSigningKeys(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        RendererRun run = renderSecurityConfiguration(tempDir, sandbox, Map.of(
                "OFBIZ_PROFILE", "prod", LOGIN_KEY_VARIABLE, LOGIN_KEY, JWT_KEY_VARIABLE, JWT_KEY));

        assertEquals(0, run.exitCode(), "the render must succeed, output was:\n" + run.output());
        Properties shipped = loadProperties(repositoryRoot().resolve(SECURITY_SOURCE));
        Properties rendered = loadProperties(sandbox.resolve(SECURITY_OVERRIDE));
        // The override takes class path precedence over the copy inside ofbiz.jar, and nothing merges
        // the two: whatever this file omits is simply absent at runtime. So the render has to carry
        // every other security property through - the CSRF strategy, the allow lists, the password
        // hash type, the shell-injection token lists - and change only the two keys.
        assertEquals(new TreeSet<>(shipped.stringPropertyNames()), new TreeSet<>(rendered.stringPropertyNames()),
                "the render must neither drop nor add a security property");
        List<String> altered = new ArrayList<>();
        for (String name : new TreeSet<>(shipped.stringPropertyNames())) {
            if (!shipped.getProperty(name).equals(rendered.getProperty(name))) {
                altered.add(name);
            }
        }
        assertEquals(List.of(LOGIN_KEY_PROPERTY, JWT_KEY_PROPERTY).stream().sorted().toList(),
                altered.stream().sorted().toList(),
                "only the two signing keys may differ from the shipped file");
    }

    /*
     * Canonicalization: what java.util.Properties gives the application, not what the shell validated
     */

    /**
     * A key with whitespace at either end is refused rather than silently shortened.
     *
     * <p>Every validation in this script runs on the shell's copy of the value, but the application reads what
     * the rendered line yields <em>through {@code UtilProperties}</em>, and two separate layers shorten a key
     * before it gets there.</p>
     *
     * <p>{@code java.util.Properties} discards blanks between the {@code =} and the first non-blank character, so
     * a 64 character key with three LEADING spaces passes the HMAC512 length floor in the shell and arrives at
     * {@code JWTManager} as 61 characters, where it is rejected outright at the first token; a leading tab does
     * the same.</p>
     *
     * <p>A TRAILING blank survives {@code Properties.load} - the value runs to the end of the line - but it does
     * not survive the accessor: {@code LoginWorker} reads {@code login.secret_key_string} and {@code JWTManager}
     * reads {@code security.token.key} through {@code UtilProperties.getPropertyValue}, which returns
     * {@code value.trim()}. A key whose last character is a space is therefore validated at one length and used
     * at another, exactly as a leading blank is, and a 64 character key with one trailing space is used as 63 -
     * below the floor the shell just enforced. The value is also one no operator can reproduce: it cannot be
     * typed back, and rotating to "the same" key without the invisible character produces a different key and
     * invalidates every issued token.</p>
     *
     * <p>Both ends are consequently refused before anything is written, while whitespace INSIDE a key is
     * accepted - neither layer touches it - which is what stops the rule from becoming an arbitrary
     * restriction. A blank at an end is exactly what a YAML block scalar or a copied secret-manager value
     * produces, so this is a configuration mistake rather than an attack, and it has to be reported at start
     * up.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void aSigningKeyWithWhitespaceAtEitherEndIsRefusedRatherThanSilentlyShortened(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");

        for (String variable : List.of(LOGIN_KEY_VARIABLE, JWT_KEY_VARIABLE)) {
            for (String blank : List.of(" ", "   ", "\t", " \t ")) {
                for (boolean leading : List.of(true, false)) {
                    Path sandbox = prepareSandbox(Files.createTempDirectory(tempDir, "blank"));
                    Map<String, String> environment = new LinkedHashMap<>();
                    environment.put("OFBIZ_PROFILE", "prod");
                    environment.put(LOGIN_KEY_VARIABLE, LOGIN_KEY);
                    environment.put(JWT_KEY_VARIABLE, JWT_KEY);
                    String key = LOGIN_KEY_VARIABLE.equals(variable) ? LOGIN_KEY : JWT_KEY;
                    String padded = leading ? blank + key : key + blank;
                    environment.put(variable, padded);

                    RendererRun run = renderSecurityConfiguration(tempDir, sandbox, environment);

                    assertNotEquals(0, run.exitCode(), variable + " with "
                            + (leading ? "leading" : "trailing")
                            + " whitespace must be refused, output was:\n" + run.output());
                    assertTrue(run.output().contains(variable),
                            "the failure must name the offending variable, output was:\n" + run.output());
                    assertFalse(Files.exists(sandbox.resolve(SECURITY_OVERRIDE)),
                            "a refused key must leave no configuration behind");
                    assertFalse(run.output().contains(padded.strip()),
                            "the refused key leaked into the output:\n" + run.output());
                }
            }
        }

        // Whitespace INSIDE a key is not a canonicalization hazard - neither Properties.load nor
        // UtilProperties.getPropertyValue touches it - so it must still be accepted and must still round trip
        // verbatim, which is what stops the refusal above from becoming a blanket "no whitespace" rule.
        Path accepted = prepareSandbox(Files.createTempDirectory(tempDir, "inner-blank"));
        String innerBlanks = "Login Signing Key With Inner Blanks And No Edge One-0123456789012";
        assertTrue(innerBlanks.length() >= SIGNING_KEY_MIN_LENGTH, "the fixture must clear the length floor");
        RendererRun run = renderSecurityConfiguration(tempDir, accepted, Map.of(
                "OFBIZ_PROFILE", "prod", LOGIN_KEY_VARIABLE, innerBlanks, JWT_KEY_VARIABLE, JWT_KEY));
        assertEquals(0, run.exitCode(), "inner blanks must be accepted, output was:\n" + run.output());
        assertEquals(innerBlanks, loadProperties(accepted.resolve(SECURITY_OVERRIDE))
                .getProperty(LOGIN_KEY_PROPERTY), "a key with inner blanks must round trip exactly");
    }

    /**
     * The render is refused when the property it produced would not read back as the key that was validated.
     *
     * <p>{@code require_rendered_declaration} asserts a value is PRESENT; this asserts it is the RIGHT one, which
     * is the half a presence check cannot see. {@code java.util.Properties} takes the LAST declaration of a
     * duplicated key, so a source file that carries the anchor twice - a merge, a hand edit, a patch applied
     * twice - renders both lines, and the application then reads whichever one happens to be last. Here the
     * second anchor is left blank, so a render that did not read its own output back would hand OFBiz an empty
     * signing key while reporting success, and the first failure would be a JWT rejection with no cause
     * anywhere in the log.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void aDuplicatedAnchorIsRefusedBecausePropertiesWouldUseTheOtherDeclaration(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");

        for (String property : List.of(LOGIN_KEY_PROPERTY, JWT_KEY_PROPERTY)) {
            Path sandbox = prepareSandbox(Files.createTempDirectory(tempDir, "duplicate"));
            Path source = sandbox.resolve(SECURITY_SOURCE);
            // A second, blank anchor for the same property. The renderer substitutes '^property=.*', so both
            // lines are rewritten; the point is that even if only one were, the file would carry two.
            Files.writeString(source, Files.readString(source, StandardCharsets.UTF_8) + "\n" + property + "=\n",
                    StandardCharsets.UTF_8);

            RendererRun run = renderSecurityConfiguration(tempDir, sandbox, Map.of(
                    "OFBIZ_PROFILE", "prod", LOGIN_KEY_VARIABLE, LOGIN_KEY, JWT_KEY_VARIABLE, JWT_KEY));

            assertNotEquals(0, run.exitCode(), "a duplicated " + property
                    + " anchor must be refused, output was:\n" + run.output());
            assertTrue(run.output().contains("exactly one declaration")
                    || run.output().contains("does not read back"),
                    "the failure must say why the rendered file is unusable, output was:\n" + run.output());
            assertNoKeyValueLeaked(run, sandbox);
        }
    }

    /**
     * The two signing keys must be different values, because they protect different things.
     *
     * <p>{@code login.secret_key_string} names the {@code EntityKeyStore} entry that encrypts the temporary
     * password of the forgot-password flow; {@code security.token.key} is the HMAC512 key that signs and
     * verifies JSON Web Tokens. The token key travels with every issued token and is handled by far more code,
     * so it is much the more likely of the two to be disclosed - and if the same value serves both, anyone who
     * obtains it can also decrypt stored password material. Sharing one value is the kind of shortcut a
     * deployment takes when it has one secret to hand and two variables to fill, so it is refused rather than
     * merely discouraged. Generated values are drawn independently and never collide, which the dev profile's
     * own test asserts.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void oneValueSuppliedForBothSigningKeysIsRefusedInEveryProfile(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");

        for (String profile : List.of("dev", "prod")) {
            Path sandbox = prepareSandbox(Files.createTempDirectory(tempDir, "shared"));

            RendererRun run = renderSecurityConfiguration(tempDir, sandbox, Map.of(
                    "OFBIZ_PROFILE", profile, LOGIN_KEY_VARIABLE, LOGIN_KEY, JWT_KEY_VARIABLE, LOGIN_KEY));

            assertNotEquals(0, run.exitCode(),
                    "one value for both keys must be refused in " + profile + ", output was:\n" + run.output());
            assertTrue(run.output().contains(LOGIN_KEY_VARIABLE) && run.output().contains(JWT_KEY_VARIABLE),
                    "the failure must name both variables, output was:\n" + run.output());
            assertFalse(Files.exists(sandbox.resolve(SECURITY_OVERRIDE)),
                    "no configuration may be rendered with one key doing both jobs");
            assertNoKeyValueLeaked(run, sandbox);
        }
    }

    /*
     * The development profile: zero configuration, still no secret in the tree
     */

    @Test
    public void developmentGeneratesAStableDistinctKeyPairWithNoConfigurationAtAll(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);
        Path override = sandbox.resolve(SECURITY_OVERRIDE);

        RendererRun first = renderSecurityConfiguration(tempDir, sandbox, Map.of("OFBIZ_PROFILE", "dev"));

        assertEquals(0, first.exitCode(), "the dev render must succeed, output was:\n" + first.output());
        Properties rendered = loadProperties(override);
        String login = rendered.getProperty(LOGIN_KEY_PROPERTY);
        String jwt = rendered.getProperty(JWT_KEY_PROPERTY);
        // Zero configuration has to keep working: this is what replaced generating the keys into the
        // image at build time, and a developer must still be able to start a container with no secrets.
        assertNotNull(login, "dev must still produce a usable login key with no configuration at all");
        assertNotNull(jwt, "dev must still produce a usable JWT key with no configuration at all");
        assertEquals(SIGNING_KEY_MIN_LENGTH, login.length(), "a generated login key must clear the HMAC512 floor");
        assertEquals(SIGNING_KEY_MIN_LENGTH, jwt.length(), "a generated JWT key must clear the HMAC512 floor");
        assertNotEquals(login, jwt, "the two keys sign different things and must be independently generated");
        assertTrue(distinctCharacters(login) >= MIN_DISTINCT_CHARACTERS,
                "a generated login key must clear the entropy floor it would itself be rejected by");
        assertTrue(distinctCharacters(jwt) >= MIN_DISTINCT_CHARACTERS,
                "a generated JWT key must clear the entropy floor it would itself be rejected by");
        // Reported by variable name only. The report is the operator's signal that no key was supplied.
        assertTrue(first.output().contains(LOGIN_KEY_VARIABLE) && first.output().contains(JWT_KEY_VARIABLE),
                "the dev profile must say which variables it generated, output was:\n" + first.output());
        assertFalse(first.output().contains(login), "the generated login key leaked into the output");
        assertFalse(first.output().contains(jwt), "the generated JWT key leaked into the output");

        // Stability is functional parity, not a convenience: these values name EntityKeyStore entries
        // and sign issued JWTs, so regenerating them on every restart would orphan encrypted data and
        // invalidate every token already in a user's hands.
        RendererRun second = renderSecurityConfiguration(tempDir, sandbox, Map.of("OFBIZ_PROFILE", "dev"));
        assertEquals(0, second.exitCode(), "the second dev render must succeed, output was:\n" + second.output());
        Properties again = loadProperties(override);
        assertEquals(login, again.getProperty(LOGIN_KEY_PROPERTY),
                "a generated login key must stay stable for the life of the container state directory");
        assertEquals(jwt, again.getProperty(JWT_KEY_PROPERTY),
                "a generated JWT key must stay stable for the life of the container state directory");
        assertFalse(second.output().contains(LOGIN_KEY_VARIABLE),
                "a reused key must not be reported as newly generated, output was:\n" + second.output());
    }

    @Test
    public void aGeneratedKeyIsStoredOnlyWhereTheOfbizUserAloneCanReadIt(@TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        RendererRun run = renderSecurityConfiguration(tempDir, sandbox, Map.of("OFBIZ_PROFILE", "dev"));

        assertEquals(0, run.exitCode(), "the dev render must succeed, output was:\n" + run.output());
        Properties rendered = loadProperties(sandbox.resolve(SECURITY_OVERRIDE));
        String login = rendered.getProperty(LOGIN_KEY_PROPERTY);
        // A generated key exists in exactly two places: the rendered configuration, and the store that
        // makes it stable across restarts. Both are the container's own volume, and neither is in the
        // source tree, the distribution or an image layer.
        assertEquals(Set.of(SECURITY_OVERRIDE, "secrets/" + LOGIN_KEY_VARIABLE), filesContaining(sandbox, login),
                "a generated login key must exist only in the rendered configuration and its store");
        assertEquals(PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(sandbox.resolve("secrets").resolve(LOGIN_KEY_VARIABLE)),
                "the stored key must not be readable by other accounts in the container");
        assertEquals(PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(sandbox.resolve("secrets")),
                "the directory holding stored keys must not be traversable by other accounts");
    }

    @Test
    public void aStoredKeyThatIsNoLongerUsableIsReplacedRatherThanFailingTheStart(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);
        Path store = sandbox.resolve("secrets").resolve(LOGIN_KEY_VARIABLE);
        // What a truncated write or a hand-edited file leaves behind. Refusing to start on it would
        // make a developer's container unrecoverable without deleting a volume they cannot see.
        Files.writeString(store, "truncated", StandardCharsets.UTF_8);

        RendererRun run = renderSecurityConfiguration(tempDir, sandbox, Map.of("OFBIZ_PROFILE", "dev"));

        assertEquals(0, run.exitCode(), "an unusable stored key must be replaced, output was:\n" + run.output());
        String login = loadProperties(sandbox.resolve(SECURITY_OVERRIDE)).getProperty(LOGIN_KEY_PROPERTY);
        assertEquals(SIGNING_KEY_MIN_LENGTH, login.length(), "the replacement must be a full length key");
        assertEquals(login, Files.readString(store, StandardCharsets.UTF_8),
                "the replacement must be written back so it is stable from now on");
    }

    /*
     * Key lifecycle: a key that has been replaced must not be able to come back
     */

    /**
     * Supplying a key destroys the generated one it replaced, so a later start that omits the variable cannot
     * revive it.
     *
     * <p>The generated store exists to keep a dev key stable across restarts, and that is exactly what made it
     * dangerous: a container that started generated, was then given a real key, and later lost that variable -
     * a templating slip, a rolled-back manifest, a secret store that returned nothing - fell back to the key
     * from before the rotation and did so without a single message. The key names {@code EntityKeyStore} entries
     * and signs JWTs, so tokens minted under the retired key would start verifying again. The replacement is
     * therefore destructive and is REPORTED, because an operator needs to know that data encrypted with the
     * discarded value can no longer be read.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void aSuppliedKeyDestroysTheGeneratedOneItReplacedSoItCannotBeRevived(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);
        Path loginStore = sandbox.resolve("secrets").resolve(LOGIN_KEY_VARIABLE);
        Path jwtStore = sandbox.resolve("secrets").resolve(JWT_KEY_VARIABLE);

        RendererRun generated = renderSecurityConfiguration(tempDir, sandbox, Map.of("OFBIZ_PROFILE", "dev"));
        assertEquals(0, generated.exitCode(), "the dev render must succeed, output was:\n" + generated.output());
        assertTrue(Files.exists(loginStore) && Files.exists(jwtStore),
                "the dev profile must have stored a generated key pair for this case to mean anything");
        String retiredLoginKey = Files.readString(loginStore, StandardCharsets.UTF_8);

        RendererRun supplied = renderSecurityConfiguration(tempDir, sandbox, Map.of(
                "OFBIZ_PROFILE", "dev", LOGIN_KEY_VARIABLE, LOGIN_KEY, JWT_KEY_VARIABLE, JWT_KEY));
        assertEquals(0, supplied.exitCode(), "a supplied pair must be accepted, output was:\n" + supplied.output());
        assertFalse(Files.exists(loginStore), "the generated login key must be destroyed, not kept beside its "
                + "replacement");
        assertFalse(Files.exists(jwtStore), "the generated JWT key must be destroyed, not kept beside its "
                + "replacement");
        assertTrue(supplied.output().contains(LOGIN_KEY_VARIABLE) && supplied.output().contains(JWT_KEY_VARIABLE),
                "the operator must be told which generated values were discarded, output was:\n"
                        + supplied.output());
        assertFalse(supplied.output().contains(retiredLoginKey),
                "the discarded key must not be echoed while it is being discarded:\n" + supplied.output());
        assertEquals(LOGIN_KEY, loadProperties(sandbox.resolve(SECURITY_OVERRIDE)).getProperty(LOGIN_KEY_PROPERTY),
                "the supplied key must be the one in force");

        // The rollback path, driven by omitting the variable. A fresh value is generated instead, so the
        // retired one is not put back in force.
        RendererRun omitted = renderSecurityConfiguration(tempDir, sandbox, Map.of("OFBIZ_PROFILE", "dev"));
        assertEquals(0, omitted.exitCode(), "dev must still start, output was:\n" + omitted.output());
        assertNotEquals(retiredLoginKey,
                loadProperties(sandbox.resolve(SECURITY_OVERRIDE)).getProperty(LOGIN_KEY_PROPERTY),
                "a start that omits the variable must not resurrect the key that was rotated away from");
    }

    /**
     * The deployed profile never runs on generated key material, and destroys any it finds.
     *
     * <p>A runtime volume promoted from a dev container to a prod one still holds the keys that dev generated.
     * Prod requires every secret to be supplied, so that material can only be a liability: leaving it in place
     * means an accidental relapse to {@code OFBIZ_PROFILE=dev} - or any future code path that consulted the
     * store - would put a key nobody chose back into service. It is therefore destroyed as soon as the profile
     * is known, before a single secret is resolved, and the prod render still fails closed on the absent
     * secret rather than quietly using what it found.</p>
     *
     * @param tempDir a per-test sandbox; nothing outside it is written
     * @throws Exception if the shell could not be run at all, which fails the test rather than being handled
     */
    @Test
    public void theDeployedProfileDestroysGeneratedKeyMaterialLeftOnTheVolumeAndNeverUsesIt(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);
        Path secrets = sandbox.resolve("secrets");

        RendererRun generated = renderSecurityConfiguration(tempDir, sandbox, Map.of("OFBIZ_PROFILE", "dev"));
        assertEquals(0, generated.exitCode(), "the dev render must succeed, output was:\n" + generated.output());
        String strandedKey = Files.readString(secrets.resolve(LOGIN_KEY_VARIABLE), StandardCharsets.UTF_8);

        // The dev render's own output is removed first, so the existence of the override below is a clean signal
        // about what the PROD run did rather than a leftover from the run that seeded the store.
        Files.delete(sandbox.resolve(SECURITY_OVERRIDE));

        // The renderer alone must refuse to consult the store, which is the defence in depth that stops any
        // future call path from reaching stored material in the deployed profile.
        RendererRun production = renderSecurityConfiguration(tempDir, sandbox, Map.of("OFBIZ_PROFILE", "prod"));
        assertNotEquals(0, production.exitCode(),
                "prod must not start on a key it was not given, output was:\n" + production.output());
        assertFalse(production.output().contains(strandedKey),
                "the stranded key must not be echoed:\n" + production.output());
        assertFalse(Files.exists(sandbox.resolve(SECURITY_OVERRIDE)),
                "prod must render nothing when a required secret is absent");

        // And the state directory step destroys the store outright, so nothing is left for a relapse to find.
        RendererRun discarded = discardGeneratedSecrets(tempDir, sandbox, Map.of("OFBIZ_PROFILE", "prod"));
        assertEquals(0, discarded.exitCode(), "the state directory step must succeed, output was:\n"
                + discarded.output());
        assertFalse(Files.exists(secrets),
                "prod must destroy the generated secret store left behind by an earlier dev run");
        assertTrue(discarded.output().contains("prod"),
                "the operator must be told the store was discarded, output was:\n" + discarded.output());

        // The same step in dev leaves the store alone, because that is what makes a dev key stable.
        Path development = prepareSandbox(Files.createTempDirectory(tempDir, "dev-keeps"));
        assertEquals(0, renderSecurityConfiguration(tempDir, development, Map.of("OFBIZ_PROFILE", "dev")).exitCode(),
                "the dev render must succeed");
        assertEquals(0, discardGeneratedSecrets(tempDir, development, Map.of("OFBIZ_PROFILE", "dev")).exitCode(),
                "the state directory step must succeed in dev");
        assertTrue(Files.exists(development.resolve("secrets").resolve(LOGIN_KEY_VARIABLE)),
                "dev must keep its generated key, which is what makes it stable across restarts");
    }

    /*
     * The allowed host header, which shares this render
     */

    @Test
    public void theAllowedHostHeaderIsRewrittenWhenSuppliedAndLeftAloneWhenNot(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path withHost = prepareSandbox(Files.createTempDirectory(tempDir, "with-host"));
        Path withoutHost = prepareSandbox(Files.createTempDirectory(tempDir, "without-host"));

        RendererRun supplied = renderSecurityConfiguration(tempDir, withHost, Map.of(
                "OFBIZ_PROFILE", "prod", LOGIN_KEY_VARIABLE, LOGIN_KEY, JWT_KEY_VARIABLE, JWT_KEY,
                "OFBIZ_HOST", ALLOWED_HOST));
        RendererRun omitted = renderSecurityConfiguration(tempDir, withoutHost, Map.of(
                "OFBIZ_PROFILE", "prod", LOGIN_KEY_VARIABLE, LOGIN_KEY, JWT_KEY_VARIABLE, JWT_KEY));

        assertEquals(0, supplied.exitCode(), "the render must succeed, output was:\n" + supplied.output());
        assertEquals(0, omitted.exitCode(), "the render must succeed, output was:\n" + omitted.output());
        assertEquals(ALLOWED_HOST, loadProperties(withHost.resolve(SECURITY_OVERRIDE)).getProperty(HOST_HEADERS_PROPERTY),
                "a supplied host must replace the allowed host header, which is what lets a load balancer"
                        + " forward its own Host header without the request being refused");
        assertEquals(loadProperties(repositoryRoot().resolve(SECURITY_SOURCE)).getProperty(HOST_HEADERS_PROPERTY),
                loadProperties(withoutHost.resolve(SECURITY_OVERRIDE)).getProperty(HOST_HEADERS_PROPERTY),
                "an unsupplied host must leave the shipped allowed host header exactly as it is");
    }

    @Test
    public void anAllowedHostThatWouldSmuggleAnExtraPropertyIsRefusedBeforeAnythingIsWritten(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        RendererRun run = renderSecurityConfiguration(tempDir, sandbox, Map.of(
                "OFBIZ_PROFILE", "prod", LOGIN_KEY_VARIABLE, LOGIN_KEY, JWT_KEY_VARIABLE, JWT_KEY,
                // A newline in the value would append an attacker-controlled line to a security file -
                // the one place where an extra property disables a defence rather than adding a setting.
                "OFBIZ_HOST", "trusted.example\ncsrf.defense.strategy=org.apache.ofbiz.NoDefence"));

        assertNotEquals(0, run.exitCode(), "an injected line must be refused, output was:\n" + run.output());
        assertTrue(run.output().contains("OFBIZ_HOST"),
                "the failure must name the offending variable, output was:\n" + run.output());
        assertFalse(Files.exists(sandbox.resolve(SECURITY_OVERRIDE)),
                "nothing may be written when a value is refused");
        assertEquals(Set.of(), filesContaining(sandbox, "csrf.defense.strategy=org.apache.ofbiz.NoDefence"),
                "the injected property must not reach any file");
    }

    /*
     * The anchors the render depends on
     */

    @ParameterizedTest(name = "a missing {0} anchor fails the render closed")
    @ValueSource(strings = {LOGIN_KEY_PROPERTY, JWT_KEY_PROPERTY})
    public void aMissingSigningKeyAnchorInTheSourceFailsTheRenderClosed(String property, @TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "a POSIX shell is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);
        // What renaming or deleting an anchor in the source file looks like. The substitution then
        // silently matches nothing, and the rendered file would declare no key - which does not fail
        // loudly at start-up, it fails later as a broken login or an unverifiable token.
        Path source = sandbox.resolve(SECURITY_SOURCE);
        List<String> withoutAnchor = new ArrayList<>();
        for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
            if (!line.startsWith(property + "=")) {
                withoutAnchor.add(line);
            }
        }
        assertNotEquals(Files.readAllLines(source, StandardCharsets.UTF_8).size(), withoutAnchor.size(),
                "the anchor for " + property + " was expected in the source file");
        Files.write(source, withoutAnchor, StandardCharsets.UTF_8);

        RendererRun run = renderSecurityConfiguration(tempDir, sandbox, Map.of(
                "OFBIZ_PROFILE", "prod", LOGIN_KEY_VARIABLE, LOGIN_KEY, JWT_KEY_VARIABLE, JWT_KEY));

        assertNotEquals(0, run.exitCode(), "a missing anchor must fail the render, output was:\n" + run.output());
        assertTrue(run.output().contains(property.replace(".", "\\.")) || run.output().contains(property),
                "the failure must name the property that was not declared, output was:\n" + run.output());
        assertNoKeyValueLeaked(run, sandbox);
    }

    /*
     * Helpers
     */

    /**
     * Asserts that neither supplied key reached the container log or the file the image ships.
     *
     * <p>Both the value as supplied and its escaped on-disk form are looked for, because a trace that
     * echoed the substitution script rather than the environment would disclose the escaped form.
     * @param run the completed run to inspect
     * @param sandbox the directory tree the run wrote into
     */
    private static void assertNoKeyValueLeaked(RendererRun run, Path sandbox) throws IOException {
        for (String key : List.of(LOGIN_KEY, JWT_KEY)) {
            assertFalse(run.output().contains(key), "a signing key leaked into the output:\n" + run.output());
            assertFalse(run.output().contains(key.replace("\\", "\\\\")),
                    "the escaped form of a signing key leaked into the output:\n" + run.output());
            assertFalse(filesContaining(sandbox, key).contains(SECURITY_SOURCE),
                    "a signing key must never be written back into the source file the image ships");
        }
    }

    /** The number of distinct characters in a value, which is the entropy floor the script enforces. */
    private static long distinctCharacters(String value) {
        return value.chars().distinct().count();
    }

    /**
     * Every file under the sandbox whose contents carry the given value, as slash-separated paths
     * relative to the sandbox, so a non-disclosure expectation can be stated as an exact set.
     *
     * <p>Both the value as supplied and its escaped on-disk form are searched. A signing key is written
     * into a {@code .properties} file, where a backslash is the escape character, so a key containing
     * one is stored with that backslash doubled: searching only for the value as the operator typed it
     * would find nothing and would turn every sweep below into an assertion that always passes.
     * @param sandbox the directory tree to search
     * @param value the value that must not have been written
     * @return the relative paths of the files carrying it, in stable order
     */
    private static Set<String> filesContaining(Path sandbox, String value) throws IOException {
        Set<String> found = new TreeSet<>();
        String escaped = value.replace("\\", "\\\\");
        List<Path> files;
        try (Stream<Path> walk = Files.walk(sandbox)) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        for (Path file : files) {
            String contents = Files.readString(file, StandardCharsets.UTF_8);
            if (contents.contains(value) || contents.contains(escaped)) {
                found.add(sandbox.relativize(file).toString().replace('\\', '/'));
            }
        }
        return found;
    }

    /**
     * Builds the minimum of the container's {@code /ofbiz} layout that the security renderer needs: the
     * pristine source file it substitutes into, and the directory the generated-key store lives in.
     */
    private static Path prepareSandbox(Path base) throws IOException {
        Path sandbox = Files.createDirectories(base.resolve("ofbiz"));
        Path shipped = sandbox.resolve(SECURITY_SOURCE);
        Files.createDirectories(shipped.getParent());
        Files.copy(repositoryRoot().resolve(SECURITY_SOURCE), shipped);
        Files.createDirectories(sandbox.resolve("secrets"));
        return sandbox;
    }

    /**
     * Runs the real entry point's security renderer as a black box against a sandbox.
     *
     * <p>The entry point is sourced with its trailing {@code _main "$@"} line removed so the single
     * function under test can be invoked without starting OFBiz, and the generated-key store is
     * redirected into the sandbox because the script otherwise keeps it under the absolute
     * {@code /ofbiz/runtime} path. Every {@code OFBIZ_*} variable the renderer reads is removed from the
     * inherited environment first, so a value exported into the build cannot decide a case.
     * @param workDir a per-test temporary directory for the generated driver scripts
     * @param sandbox the directory the renderer treats as the OFBiz home
     * @param environment the {@code OFBIZ_*} variables to supply
     * @return the exit code and the combined output of the run
     */
    private static RendererRun renderSecurityConfiguration(Path workDir, Path sandbox, Map<String, String> environment)
            throws Exception {
        return runEntryPoint(workDir, sandbox, "render_security_configuration\n", environment);
    }

    /**
     * Runs the step that prepares the container state directory, which is where the deployed profile destroys
     * any generated secret material left on the volume by an earlier development run.
     * @param workDir a per-test temporary directory for the generated driver scripts
     * @param sandbox the directory the entry point treats as the OFBiz home
     * @param environment the {@code OFBIZ_*} variables to supply
     * @return the exit code and the combined output of the run
     */
    private static RendererRun discardGeneratedSecrets(Path workDir, Path sandbox, Map<String, String> environment)
            throws Exception {
        return runEntryPoint(workDir, sandbox, "create_ofbiz_runtime_directories\n", environment);
    }

    /**
     * Runs an arbitrary body of shell with the real entry point sourced as a library, against a sandbox.
     * @param workDir a per-test temporary directory for the generated driver scripts
     * @param sandbox the directory the entry point treats as the OFBiz home
     * @param body the shell to run once the library has been sourced
     * @param environment the {@code OFBIZ_*} variables to supply
     * @return the exit code and the combined output of the run
     */
    private static RendererRun runEntryPoint(Path workDir, Path sandbox, String body,
            Map<String, String> environment) throws Exception {
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
        Path driver = Files.createTempFile(workDir, "entrypoint-driver", ".sh");
        Files.writeString(driver, "#!/usr/bin/env bash\n"
                + ". " + shellQuote(library) + "\n"
                + "CONTAINER_STATE_DIR=" + shellQuote(sandbox.resolve("state")) + "\n"
                + "CONTAINER_GENERATED_SECRETS_DIR=" + shellQuote(sandbox.resolve("secrets")) + "\n"
                + "cd " + shellQuote(sandbox) + " || exit 1\n"
                + body, StandardCharsets.UTF_8);

        // The shared driver waits on the process before collecting its output and destroys a child that
        // outruns its deadline; draining first, as this used to, made the deadline unreachable because the
        // read blocks until the child closes its stream.
        ShellDriver.Run run = ShellDriver.run(driver, sandbox, environment);
        assertFalse(run.timedOut(), "the entry point renderer did not terminate, output was:\n" + run.output());
        return new RendererRun(run.exitCode(), run.output());
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

    /**
     * Whether a POSIX shell can be executed, so the shell-driven assertions can be skipped if not.
     *
     * <p>Delegated to the shared driver rather than repeated: this probe has to start a process, wait
     * for it and close its output, and every copy of it was one more place to get that wrong.
     *
     * @return true when {@code bash} can be run
     */
    private static boolean isBashAvailable() {
        return ShellDriver.isBashAvailable();
    }

    private static Path repositoryRoot() {
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("dependencies.gradle"))) {
            candidate = candidate.getParent();
        }
        assertNotNull(candidate, "could not locate the repository root from " + Paths.get("").toAbsolutePath());
        return candidate;
    }

    /** What one black-box execution of the entry point's security renderer produced. */
    private record RendererRun(int exitCode, String output) { }
}
