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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.util.EntityUtilProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Executes {@link ContentStoreFactory} - the production selection logic itself - for every configured value.
 *
 * <p>The factory is the only thing standing between an operator's one-word setting and which storage backend a
 * whole deployment uses, and it has two non-negotiable duties. It must <strong>answer {@code null} whenever
 * database storage was actually asked for</strong> - an unmodified checkout, a blank value or the committed
 * {@code database} value - because {@code null} is what keeps the pre-existing {@code DataResource}
 * database-storage path in use, untouched. And it must <strong>refuse anything else</strong>: a value that
 * names no provider, or a provider that cannot be built, raises {@link ContentStoreConfigurationException}
 * rather than reaching that same {@code null}. Those two answers look similar and are not: falling back would
 * put content into a backend nobody chose, with different retention, encryption and access controls, and would
 * do so with no error at all. This suite exists to keep the boundary between them exact.
 *
 * <p>Two details make this suite look the way it does. The factory caches one resolution per distinct
 * configured value for the life of the JVM, so {@code ContentStoreFactory.clearCache()} is called before and
 * after every test - otherwise the first value resolved in this shared JVM would be the only value any later
 * test could ever see. And {@code UtilProperties.setPropertyValueInMemory} mutates a cached {@code Properties}
 * object rather than a copy, so every value written here is snapshotted first and put back afterwards.
 */
public final class ContentStoreProviderSelectionTests {

    private static final String CONTENT_RESOURCE = "content";
    private static final String PROVIDER_PROPERTY = "content.store.provider";
    private static final String CONTENT_PROPERTIES = "applications/content/config/content.properties";

    private final Map<String, String> propertySnapshot = new LinkedHashMap<>();

    @BeforeEach
    public void discardAnyResolutionAnEarlierTestLeftBehind() {
        ContentStoreFactory.clearCache();
    }

    @AfterEach
    public void restoreTheConfigurationAndDiscardTheResolution() {
        propertySnapshot.forEach((name, previous) -> UtilProperties.setPropertyValueInMemory(CONTENT_RESOURCE, name, previous));
        propertySnapshot.clear();
        ContentStoreFactory.clearCache();
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Database storage is the default, and the default is what keeps existing deployments intact
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void theValueAnUnmodifiedCheckoutShipsResolvesToNoProviderAtAll() throws Exception {
        // Take the value from the committed file rather than from UtilProperties - an in-memory override left
        // behind by another test in this shared JVM could otherwise make this pass while the shipped default had
        // been changed to something that quietly relocates every deployment's content - and then push exactly
        // that text through the production factory. The sibling contract suite pins the declaration; this pins
        // what the declaration *does*, which is the half that matters to an operator who changes nothing.
        List<String> declarations = Files.readAllLines(repositoryRoot().resolve(CONTENT_PROPERTIES), StandardCharsets.UTF_8).stream()
                .map(String::strip)
                .filter(line -> line.startsWith(PROVIDER_PROPERTY + "="))
                .toList();
        assertEquals(1, declarations.size(), "the provider selector must be declared exactly once");

        select(declarations.get(0).substring((PROVIDER_PROPERTY + "=").length()));

        assertNull(ContentStoreFactory.getContentStore(),
                "the committed default must resolve to no provider, so an unmodified checkout keeps database storage");
    }

    @Test
    public void theCommittedDatabaseValueYieldsNoProviderSoTheDataResourcePathIsUntouched() throws Exception {
        select("database");

        // null is the whole contract here: the delegation seam in DataResourceWorker keeps doing exactly what it
        // did before when the factory answers null, so an unmodified checkout is bit-for-bit unaffected.
        assertNull(ContentStoreFactory.getContentStore(), "database storage must be signalled by the absence of a provider");
    }

    @Test
    public void anAbsentOrBlankValueFallsBackToDatabaseStorage() throws Exception {
        for (String unconfigured : List.of("", "   ", "\t")) {
            ContentStoreFactory.clearCache();
            select(unconfigured);

            assertNull(ContentStoreFactory.getContentStore(),
                    "a value of [" + unconfigured + "] must leave database storage in place");
        }
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Anything that is not one of those three states fails closed
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void anUnrecognisedValueIsRefusedRatherThanSilentlyRedirectingContentToTheDatabase() {
        for (String typo : List.of("s4", "filesystem ,s3", "postgres-large-object", "File System", "s3;filesystem",
                "databse", "aws", "minio", "local", "none", "off", "false", "null", "default")) {
            ContentStoreFactory.clearCache();
            select(typo);

            // The refusal is the fix for the fail-open selection this suite used to pin. An operator who writes
            // "s4" for "s3", or "local" for "filesystem", has stated that content must leave the database;
            // answering null would put it back there without a word, in a backend with different retention and
            // access controls, and the mistake would surface only when someone went looking for the content.
            ContentStoreConfigurationException refused = assertThrows(
                    ContentStoreConfigurationException.class, () -> ContentStoreFactory.getContentStore(),
                    "an unrecognised value of [" + typo + "] must be refused, not read as database storage");
            assertTrue(refused.getMessage().contains("not a recognised provider"),
                    "the refusal must say the value names no provider, but said: " + refused.getMessage());
            // Naming all three accepted spellings is what makes the message actionable without the operator
            // having to find this class.
            assertTrue(refused.getMessage().contains("[database, filesystem, s3]"),
                    "the refusal must list the accepted values, but said: " + refused.getMessage());
        }
    }

    @Test
    public void aRefusalIsNotCachedSoCorrectingTheValueTakesEffectImmediately() throws Exception {
        select("s4");
        assertThrows(ContentStoreConfigurationException.class, () -> ContentStoreFactory.getContentStore(),
                "the unrecognised value must be refused");

        // No clearCache() here on purpose: an operator who corrects the SystemProperty row cannot call a
        // package-private test seam, so the corrected value has to be honoured by the production path alone.
        select("filesystem");
        assertInstanceOf(FileSystemContentStore.class, ContentStoreFactory.getContentStore(),
                "a corrected value must be honoured without a restart");

        select("s4");
        assertThrows(ContentStoreConfigurationException.class, () -> ContentStoreFactory.getContentStore(),
                "and re-introducing the bad value must be refused again rather than served from a cached provider");
    }

    @Test
    public void aRefusedValueIsNeverEchoedIntoTheMessageWhenItCouldForgeALogLine() {
        // A provider value can carry whatever an operator pasted or a SystemProperty row holds, and the refusal
        // message is logged. A value carrying a line break must therefore not be able to write a log record of
        // its own, and a long value must not be reproduced wholesale - both are replaced by an opaque
        // reference, while a short, control-free typo is still echoed because that is what makes it fixable.
        Map<String, Boolean> echoed = new LinkedHashMap<>();
        echoed.put("s4", true);
        echoed.put("postgres-large-object", true);
        echoed.put("s3\nERROR forged log record", false);
        echoed.put("s3\r\nWARN forged", false);
        echoed.put("s3\u001b[2Jcleared the terminal", false);
        echoed.put("s3\u0000truncated", false);
        echoed.put("s3\tafter a tab", false);
        echoed.put("s3" + "x".repeat(80), false);

        echoed.forEach((configured, shouldEcho) -> {
            ContentStoreFactory.clearCache();
            select(configured);

            ContentStoreConfigurationException refused = assertThrows(
                    ContentStoreConfigurationException.class, () -> ContentStoreFactory.getContentStore(),
                    "[" + describeForAssertion(configured) + "] must be refused");
            String message = refused.getMessage();
            assertFalse(message.contains("\n") || message.contains("\r"),
                    "no refusal message may contain a line break, but one did for [" + describeForAssertion(configured) + "]");
            if (shouldEcho) {
                assertTrue(message.contains(configured),
                        "a short, control-free value must be echoed so it can be corrected, but was not: " + message);
            } else {
                assertFalse(message.contains(configured),
                        "an unsafe or over-long value must not be echoed, but was: " + describeForAssertion(message));
                assertTrue(message.contains("ref:") && message.contains("length=" + configured.length()),
                        "an unsafe or over-long value must be replaced by a reference and its length, but the message was: "
                                + describeForAssertion(message));
            }
        });
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * The two providers that do exist, and how forgiving the value is
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void theFilesystemValueSelectsTheFilesystemProvider() throws Exception {
        select("filesystem");

        assertInstanceOf(FileSystemContentStore.class, ContentStoreFactory.getContentStore(),
                "filesystem must select the filesystem provider");
    }

    @Test
    public void theS3ValueSelectsTheObjectStorageProvider() throws Exception {
        select("s3");

        // Reaching this class is also the proof that selecting s3 costs nothing until it is used: its constructor
        // reads configuration only, so no credential is resolved and no connection is opened here.
        assertInstanceOf(S3ContentStore.class, ContentStoreFactory.getContentStore(),
                "s3 must select the object-storage provider");
    }

    @Test
    public void surroundingWhitespaceAndLetterCaseDoNotChangeTheSelection() throws Exception {
        // A SystemProperty row is returned verbatim and a hand-edited properties file often carries a stray
        // space, so neither may be the difference between database storage and object storage.
        for (String value : List.of("  filesystem  ", "FILESYSTEM", "FileSystem", "\tfilesystem\t")) {
            ContentStoreFactory.clearCache();
            select(value);
            assertInstanceOf(FileSystemContentStore.class, ContentStoreFactory.getContentStore(),
                    "[" + value + "] must still select the filesystem provider");
        }
        for (String value : List.of(" s3 ", "S3")) {
            ContentStoreFactory.clearCache();
            select(value);
            assertInstanceOf(S3ContentStore.class, ContentStoreFactory.getContentStore(), "[" + value + "] must still select s3");
        }
        for (String value : List.of(" database ", "DATABASE", "Database")) {
            ContentStoreFactory.clearCache();
            select(value);
            assertNull(ContentStoreFactory.getContentStore(), "[" + value + "] must still mean database storage");
        }
    }

    @Test
    public void onlyTheCommittedPropertyNameIsConsulted() throws Exception {
        select("database");
        override("content.store.providers", "s3");
        override("store.provider", "s3");

        // A near-miss key must be inert: the factory reads content.store.provider of the content resource, and
        // nothing else, so a mistyped key leaves the deployment on database storage rather than silently
        // switching it.
        assertNull(ContentStoreFactory.getContentStore(), "a similarly named property must not be able to select a provider");
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Caching: one resolution per distinct value, and a seam to discard it
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void anUnchangedValueIsResolvedOnceAndTheProviderIsThenReused() throws Exception {
        select("filesystem");

        ContentStore first = ContentStoreFactory.getContentStore();
        ContentStore second = ContentStoreFactory.getContentStore();

        // Providers hold no per-request state, so reusing one is safe - and resolving on every content read
        // would put a property lookup and an object allocation on the hot path of every upload and download.
        assertSame(first, second, "an unchanged configuration must be resolved once and the provider reused");
    }

    @Test
    public void changingTheValueIsPickedUpRatherThanServedFromTheCache() throws Exception {
        select("filesystem");
        ContentStore beforeChange = ContentStoreFactory.getContentStore();

        select("s3");
        ContentStore afterChange = ContentStoreFactory.getContentStore();

        assertInstanceOf(FileSystemContentStore.class, beforeChange, "the first value must have been honoured");
        assertInstanceOf(S3ContentStore.class, afterChange, "a changed value must not be answered from the cache");
        select("database");
        assertNull(ContentStoreFactory.getContentStore(), "and changing it back to database must switch off object storage again");
    }

    @Test
    public void theCacheSeamDiscardsTheResolutionSoConfigurationIsReadAfresh() throws Exception {
        select("filesystem");
        ContentStore before = ContentStoreFactory.getContentStore();

        ContentStoreFactory.clearCache();
        ContentStore after = ContentStoreFactory.getContentStore();

        assertNotSame(before, after, "clearCache must force a fresh resolution");
        assertInstanceOf(FileSystemContentStore.class, after, "and the fresh resolution must honour the same configuration");
    }

    @Test
    public void concurrentCallersNeverSeeAStaleValuePairedWithANewerProvider() throws Exception {
        int threads = 16;
        select("filesystem");
        ExecutorService workers = Executors.newFixedThreadPool(threads);
        try {
            List<Future<ContentStore>> answers = new ArrayList<>();
            for (int index = 0; index < threads; index++) {
                answers.add(workers.submit(() -> ContentStoreFactory.getContentStore()));
            }
            for (Future<ContentStore> answer : answers) {
                assertInstanceOf(FileSystemContentStore.class, answer.get(30, TimeUnit.SECONDS),
                        "every caller must be handed a provider consistent with the configured value");
            }
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(30, TimeUnit.SECONDS), "the worker pool must terminate");
        }
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * The delegator-aware overload
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void aNullDelegatorIsAnsweredFromTheClasspathConfigurationWithoutTouchingTheDatabase() throws Exception {
        select("filesystem");

        try (MockedStatic<EntityUtilProperties> databaseLookup = mockStatic(EntityUtilProperties.class)) {
            // EntityUtilProperties dereferences the delegator to query SystemProperty, so the overload must fall
            // back rather than fail: the static file-resolution helpers in DataResourceWorker have no delegator
            // to offer, and a content read must not be the thing that discovers that.
            assertInstanceOf(FileSystemContentStore.class, ContentStoreFactory.getContentStore(null),
                    "a null delegator must behave exactly like the classpath-only lookup");
            databaseLookup.verifyNoInteractions();
        }
    }

    @Test
    public void theDelegatorAwareFormAsksForTheCommittedResourceAndPropertyAndDefaultsToDatabase() throws Exception {
        Delegator delegator = mock(Delegator.class);
        try (MockedStatic<EntityUtilProperties> databaseLookup = mockStatic(EntityUtilProperties.class)) {
            databaseLookup.when(() -> EntityUtilProperties.getPropertyValue(CONTENT_RESOURCE, PROVIDER_PROPERTY, "database", delegator))
                    .thenReturn("s3");

            assertInstanceOf(S3ContentStore.class, ContentStoreFactory.getContentStore(delegator),
                    "a SystemProperty override must be able to select a provider the property file does not");

            // Pinning the four arguments is what proves the committed key names are the ones consumed, and that
            // "database" - not "" - is the default handed to the lookup, which is why an emptied SystemProperty
            // row means database storage instead of taking the unrecognised-value branch.
            databaseLookup.verify(() -> EntityUtilProperties.getPropertyValue(CONTENT_RESOURCE, PROVIDER_PROPERTY, "database", delegator));
            databaseLookup.verifyNoMoreInteractions();
        }
    }

    @Test
    public void theDelegatorAwareFormRunsTheSameSelectionAndTrimmingAsTheClasspathForm() throws Exception {
        Delegator delegator = mock(Delegator.class);
        Map<String, Class<?>> expected = new LinkedHashMap<>();
        expected.put("database", null);
        expected.put("", null);
        expected.put("   ", null);
        expected.put("filesystem", FileSystemContentStore.class);
        expected.put("  FileSystem  ", FileSystemContentStore.class);
        expected.put("s3", S3ContentStore.class);
        expected.put(" S3 ", S3ContentStore.class);

        for (Map.Entry<String, Class<?>> expectation : expected.entrySet()) {
            String configured = expectation.getKey();
            Class<?> provider = expectation.getValue();
            ContentStoreFactory.clearCache();
            try (MockedStatic<EntityUtilProperties> databaseLookup = mockStatic(EntityUtilProperties.class)) {
                databaseLookup.when(() -> EntityUtilProperties.getPropertyValue(CONTENT_RESOURCE, PROVIDER_PROPERTY, "database", delegator))
                        .thenReturn(configured);

                ContentStore selected = ContentStoreFactory.getContentStore(delegator);
                if (provider == null) {
                    assertNull(selected, "[" + configured + "] from the database must leave database storage in place");
                } else {
                    assertInstanceOf(provider, selected, "[" + configured + "] from the database must select " + provider.getSimpleName());
                }
            }
        }
    }

    @Test
    public void aSystemPropertyRowNamingNoProviderIsRefusedJustAsTheFileWouldBe() {
        Delegator delegator = mock(Delegator.class);
        // The SystemProperty entity is the one configuration surface an operator can change without a
        // restart and without a code review, so it is also the one most likely to hold a typo. It gets the
        // same refusal as the properties file: there is no path into the fail-open behaviour.
        for (String typo : List.of("not-a-provider", "s4", "File System", "objectstore")) {
            ContentStoreFactory.clearCache();
            try (MockedStatic<EntityUtilProperties> databaseLookup = mockStatic(EntityUtilProperties.class)) {
                databaseLookup.when(() -> EntityUtilProperties.getPropertyValue(CONTENT_RESOURCE, PROVIDER_PROPERTY, "database", delegator))
                        .thenReturn(typo);

                assertThrows(ContentStoreConfigurationException.class, () -> ContentStoreFactory.getContentStore(delegator),
                        "[" + typo + "] held in SystemProperty must be refused, not read as database storage");
            }
        }
    }

    @Test
    public void theDelegatorAwareFormToleratesALookupThatAnswersNothingAtAll() {
        Delegator delegator = mock(Delegator.class);
        try (MockedStatic<EntityUtilProperties> databaseLookup = mockStatic(EntityUtilProperties.class)) {
            // An unstubbed static mock answers null, standing in for any future lookup that cannot resolve a
            // value; the factory must read that as database storage rather than dereferencing it.
            ContentStore selected = assertDoesNotThrow(() -> ContentStoreFactory.getContentStore(delegator),
                    "an unresolved lookup must not throw");
            assertNull(selected, "an unresolved lookup must leave database storage in place");
            databaseLookup.verify(() -> EntityUtilProperties.getPropertyValue(CONTENT_RESOURCE, PROVIDER_PROPERTY, "database", delegator));
        }
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Helpers
     * ---------------------------------------------------------------------------------------------
     */

    /** Configures the provider selector for one test, remembering what it held. */
    private void select(String provider) {
        override(PROVIDER_PROPERTY, provider);
    }

    /**
     * Renders a value for an assertion message with its control characters made visible.
     *
     * <p>A failure message is written to the build log, so a test about values that can forge a log line must
     * not forge one itself when it fails.
     */
    private static String describeForAssertion(String value) {
        StringBuilder rendered = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (Character.isISOControl(codePoint)) {
                rendered.append(String.format("\\u%04x", codePoint));
            } else {
                rendered.appendCodePoint(codePoint);
            }
        });
        return rendered.toString();
    }

    private void override(String name, String value) {
        propertySnapshot.computeIfAbsent(name, key -> UtilProperties.getPropertyValue(CONTENT_RESOURCE, name));
        UtilProperties.setPropertyValueInMemory(CONTENT_RESOURCE, name, value);
    }

    private static Path repositoryRoot() {
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("dependencies.gradle"))) {
            candidate = candidate.getParent();
        }
        assertTrue(candidate != null, "could not locate the repository root from " + Paths.get("").toAbsolutePath());
        return candidate;
    }
}
