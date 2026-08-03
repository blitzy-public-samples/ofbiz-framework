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
package org.apache.ofbiz.webapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.ofbiz.base.test.ShellDriver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Executable contract of the entry point's content-URL-prefix renderer - the step that writes
 * {@code OFBIZ_CONTENT_URL_PREFIX} into {@code /ofbiz/config/url.properties} at container start.
 *
 * <p>{@code content.url.prefix.secure} and {@code content.url.prefix.standard} are the
 * origin OFBiz puts in front of every generated content URL, so they decide where a browser fetches
 * every image, style sheet and download from. The entry point validates the supplied value and then
 * substitutes it into a properties file, and validation happens on the shell's copy while the
 * application reads whatever {@code java.util.Properties} makes of the rendered line. The two can
 * differ: {@code Properties} discards blanks between the {@code =} and the first non-blank character,
 * interprets backslash escapes, and takes the LAST declaration of a duplicated key. A prefix that
 * survives validation but arrives shortened, or that is shadowed by a leftover duplicate anchor, points
 * the whole deployment's content at the wrong origin - and nothing notices, because every individual
 * page still renders.
 *
 * <p>The real {@code docker/docker-entrypoint.sh} is sourced as a library with its
 * trailing {@code _main "$@"} line removed, so {@code render_content_url_configuration} can be driven
 * as a black box without starting OFBiz, against a throwaway sandbox that stands in for {@code /ofbiz}.
 * Nothing is reimplemented and nothing is stubbed: every assertion below reads the file the production
 * script wrote, parsed by the same {@code java.util.Properties} the application uses.
 *
 * @see <a href="https://ofbiz.apache.org/">Apache OFBiz</a>
 */
public final class ContentUrlPrefixRenderingTests {

    private static final String ENTRY_POINT = "docker/docker-entrypoint.sh";
    private static final String URL_SOURCE = "framework/webapp/config/url.properties";
    private static final String URL_OVERRIDE = "config/url.properties";

    private static final String SECURE_PROPERTY = "content.url.prefix.secure";
    private static final String STANDARD_PROPERTY = "content.url.prefix.standard";
    private static final String PREFIX_VARIABLE = "OFBIZ_CONTENT_URL_PREFIX";

    /**
     * Every {@code OFBIZ_*} variable this renderer reads, removed from each run's environment before the
     * case's own values are applied so that a value exported into the build cannot decide a case.
     */
    private static final List<String> RENDERER_VARIABLES = List.of("OFBIZ_TRACE", PREFIX_VARIABLE);

    @Test
    public void theShippedSourceStillCarriesBothAnchorsSoTheSubstitutionHasSomethingToReplace() throws IOException {
        List<String> shipped = Files.readAllLines(repositoryRoot().resolve(URL_SOURCE), StandardCharsets.UTF_8);

        assertEquals(1, shipped.stream().filter(line -> line.startsWith(SECURE_PROPERTY + "=")).count(),
                URL_SOURCE + " must declare " + SECURE_PROPERTY + " exactly once: the renderer replaces that "
                        + "line, and a duplicate would let java.util.Properties use the one it did not replace");
        assertEquals(1, shipped.stream().filter(line -> line.startsWith(STANDARD_PROPERTY + "=")).count(),
                URL_SOURCE + " must declare " + STANDARD_PROPERTY + " exactly once, for the same reason");
    }

    @Test
    public void aSuppliedPrefixIsReadBackFromTheRenderedFileExactlyAsItWasSupplied(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "bash is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        // Deliberately awkward rather than merely long: the value carries a character that is special to
        // each of the three grammars it passes through - the sed replacement ('&', '|', '\'), the
        // java.util.Properties value ('\', ':', '='), and the shell ('$', '"') - so an escaping mistake in
        // any one of them shows up as a failed round trip here instead of as a silently wrong origin.
        String prefix = "https://content.example:8443/prefix?a=1&b=2#frag$dollar";
        RendererRun run = renderContentUrlConfiguration(tempDir, sandbox, Map.of(PREFIX_VARIABLE, prefix));

        assertEquals(0, run.exitCode(), "rendering a valid prefix must succeed. Output:\n" + run.output());
        Properties rendered = loadProperties(sandbox.resolve(URL_OVERRIDE));
        assertEquals(prefix, rendered.getProperty(SECURE_PROPERTY),
                "the secure prefix java.util.Properties reads must be the value that was supplied");
        assertEquals(prefix, rendered.getProperty(STANDARD_PROPERTY),
                "the standard prefix java.util.Properties reads must be the value that was supplied");
    }

    @Test
    public void anAbsentPrefixLeavesTheShippedConfigurationAloneRatherThanRenderingAnEmptyOne(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "bash is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        RendererRun run = renderContentUrlConfiguration(tempDir, sandbox, Map.of());

        assertEquals(0, run.exitCode(), "omitting the prefix must not be an error. Output:\n" + run.output());
        assertFalse(Files.exists(sandbox.resolve(URL_OVERRIDE)),
                "with no prefix supplied there is nothing to override, so no " + URL_OVERRIDE
                        + " may be written: an empty override would blank the shipped defaults");
    }

    /**
     * The DP-01 case. {@code java.util.Properties} discards every blank between the {@code =} and the
     * first non-blank character, so a prefix beginning with whitespace would be validated as one value by
     * the shell and read back as a different, shorter one by the application. The value is refused before
     * anything is written rather than escaped, because a prefix with a leading blank is a typo in every
     * deployment that has ever produced one.
     * @param blankPrefixed a prefix that begins with a character Properties.load discards
     * @param tempDir a per-test temporary directory, injected by JUnit
     */
    @ParameterizedTest
    @ValueSource(strings = {" https://content.example", "   https://content.example", "\thttps://content.example",
        " \t https://content.example", "\fhttps://content.example"})
    public void aPrefixThatBeginsWithWhitespaceIsRefusedRatherThanSilentlyShortened(String blankPrefixed,
            @TempDir Path tempDir) throws Exception {
        assumeTrue(isBashAvailable(), "bash is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        RendererRun run = renderContentUrlConfiguration(tempDir, sandbox, Map.of(PREFIX_VARIABLE, blankPrefixed));

        assertFalse(run.exitCode() == 0,
                "a prefix beginning with whitespace must be refused, because java.util.Properties would "
                        + "read back a different value than the one that was validated. Output:\n" + run.output());
        assertTrue(run.output().contains(PREFIX_VARIABLE),
                "the refusal must name the variable so the operator can find it. Output:\n" + run.output());
        assertFalse(Files.exists(sandbox.resolve(URL_OVERRIDE)),
                "a refused prefix must leave no rendered override behind");
    }

    /**
     * Inner and trailing blanks are NOT a Properties hazard - only leading ones are discarded - so they
     * must still be accepted and must still round trip verbatim. Without this the refusal above could be
     * implemented as a blanket "no whitespace anywhere" rule, which would reject legitimate values.
     */
    @Test
    public void aPrefixWithInnerOrTrailingBlanksIsStillAcceptedAndStillRoundTripsExactly(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "bash is required to execute the entry point");

        for (String prefix : List.of("https://content.example/a b", "https://content.example/trailing ")) {
            Path sandbox = prepareSandbox(Files.createTempDirectory(tempDir, "inner-blank"));
            RendererRun run = renderContentUrlConfiguration(tempDir, sandbox, Map.of(PREFIX_VARIABLE, prefix));

            assertEquals(0, run.exitCode(),
                    "a blank that java.util.Properties does not discard must be accepted: [" + prefix
                            + "]. Output:\n" + run.output());
            Properties rendered = loadProperties(sandbox.resolve(URL_OVERRIDE));
            assertEquals(prefix, rendered.getProperty(SECURE_PROPERTY),
                    "the secure prefix must round trip verbatim: [" + prefix + "]");
            assertEquals(prefix, rendered.getProperty(STANDARD_PROPERTY),
                    "the standard prefix must round trip verbatim: [" + prefix + "]");
        }
    }

    /**
     * The read-back has to establish more than "a value is present". A source file carrying a second
     * declaration of either anchor - a stray line left by a merge, or a local edit - would be rendered
     * with the substitution applied to both, but the read-back counts the declarations and refuses,
     * because a duplicate is the shape in which a NON-substituted leftover would silently win.
     */
    @Test
    public void aDuplicatedAnchorIsRefusedBecausePropertiesWouldUseTheOtherDeclaration(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "bash is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        Path shipped = sandbox.resolve(URL_SOURCE);
        Files.writeString(shipped, Files.readString(shipped, StandardCharsets.UTF_8)
                + System.lineSeparator() + SECURE_PROPERTY + "=https://left.over.example"
                + System.lineSeparator(), StandardCharsets.UTF_8);

        RendererRun run = renderContentUrlConfiguration(tempDir, sandbox,
                Map.of(PREFIX_VARIABLE, "https://content.example"));

        assertFalse(run.exitCode() == 0,
                "a duplicated anchor must be refused, because java.util.Properties uses the last "
                        + "declaration. Output:\n" + run.output());
        assertTrue(run.output().contains("exactly one declaration"),
                "the refusal must explain that exactly one declaration is required. Output:\n" + run.output());
        assertNoOverrideSurvived(run, sandbox, "a duplicated anchor");
    }

    /**
     * The other half of the read-back. If the anchor the renderer substitutes into is renamed or deleted
     * in the source file, the substitution matches nothing, the rendered file carries no prefix at all,
     * and every generated content URL falls back to a relative path. That must abort the start rather
     * than produce a configuration with the property missing.
     */
    @Test
    public void aRenamedAnchorIsRefusedInsteadOfProducingAConfigurationWithNoPrefixAtAll(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isBashAvailable(), "bash is required to execute the entry point");
        Path sandbox = prepareSandbox(tempDir);

        Path shipped = sandbox.resolve(URL_SOURCE);
        Files.writeString(shipped, Files.readString(shipped, StandardCharsets.UTF_8)
                .replace(STANDARD_PROPERTY + "=", "content.url.prefix.renamed="), StandardCharsets.UTF_8);

        RendererRun run = renderContentUrlConfiguration(tempDir, sandbox,
                Map.of(PREFIX_VARIABLE, "https://content.example"));

        assertFalse(run.exitCode() == 0,
                "a missing anchor must be refused rather than rendered as an absent property. Output:\n"
                        + run.output());
        assertTrue(run.output().contains(URL_SOURCE),
                "the refusal must name the source file whose anchor is missing. Output:\n" + run.output());
        assertNoOverrideSurvived(run, sandbox, "a renamed anchor");
    }

    /**
     * Asserts that a refusal raised <em>after</em> the file was written left no override behind.
     *
     * <p>The read-back checks are the only ones in this renderer that run once the destination exists, so
     * they are the only ones that can leave one. That matters here more than for the secret renderers,
     * because this one writes nothing at all when no prefix is supplied: a leftover in {@code /ofbiz/config},
     * which takes class path precedence, would therefore survive untouched into a later start that configures
     * no prefix, and every content URL would then carry a prefix the read-back had already refused.</p>
     *
     * @param run the completed render
     * @param sandbox the sandbox the render was driven against
     * @param cause what was wrong with the source, reported when the assertion fails
     */
    private static void assertNoOverrideSurvived(RendererRun run, Path sandbox, String cause) {
        assertFalse(Files.exists(sandbox.resolve(URL_OVERRIDE)),
                cause + " must leave no rendered override behind: this renderer writes nothing when no prefix "
                        + "is supplied, so a leftover would become the effective configuration on a later "
                        + "start. Output:\n" + run.output());
    }

    /**
     * Builds the minimum of the container's {@code /ofbiz} layout this renderer needs: the pristine
     * source file it substitutes into.
     * @param base the directory to build the sandbox under
     * @return the sandbox directory that stands in for {@code /ofbiz}
     */
    private static Path prepareSandbox(Path base) throws IOException {
        Path sandbox = Files.createDirectories(base.resolve("ofbiz"));
        Path shipped = sandbox.resolve(URL_SOURCE);
        Files.createDirectories(shipped.getParent());
        Files.copy(repositoryRoot().resolve(URL_SOURCE), shipped);
        return sandbox;
    }

    /**
     * Runs the real entry point's content-URL renderer as a black box against a sandbox.
     * @param workDir a per-test temporary directory for the generated driver scripts
     * @param sandbox the directory the renderer treats as the OFBiz home
     * @param environment the {@code OFBIZ_*} variables to supply
     * @return the exit code and the combined output of the run
     */
    private static RendererRun renderContentUrlConfiguration(Path workDir, Path sandbox,
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
                + "cd " + shellQuote(sandbox) + " || exit 1\n"
                + "render_content_url_configuration\n", StandardCharsets.UTF_8);

        // The shared driver waits on the process before collecting its output, and destroys a child that
        // outruns its deadline. It also strips every inherited OFBIZ_ variable, which is what keeps a run
        // from being steered by the environment of whoever started the build.
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

    /** What one black-box execution of the entry point's content-URL renderer produced. */
    private record RendererRun(int exitCode, String output) { }
}
