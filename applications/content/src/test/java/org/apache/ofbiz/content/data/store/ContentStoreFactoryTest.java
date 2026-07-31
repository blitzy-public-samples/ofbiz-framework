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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.UtilProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.MockedStatic;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Unit tests for the content storage provider selection performed by {@link ContentStoreFactory}, and
 * for the object-storage provider it selects, exercised against a mocked object-storage client.
 *
 * <p>These are pure unit tests. No delegator, no dispatcher, no database, no filesystem write and no
 * network: the configured value is varied in memory and read back through the public lookup production
 * code calls, and the cases that reach the object store drive a client the test itself supplied.
 *
 * <p><strong>Why {@code null} is an asserted outcome rather than a defect.</strong> An absent, blank or
 * {@code database} value resolves to no provider at all, and that is the single documented signal for
 * "keep using the pre-existing {@code DataResource} database storage, untouched". There is no sentinel
 * provider type and no {@code Optional}, so {@code assertNull} is how the default-off guarantee - the
 * state of an unmodified checkout - is asserted.
 *
 * <p><strong>Why an unrecognised value is asserted to fail rather than to fall back.</strong> A typo
 * must not be read as a deliberate instruction: {@link ContentStoreFactory} refuses it with a
 * {@link ContentStoreConfigurationException} instead of quietly selecting database storage, which would
 * put content in a backend nobody chose. That refusal is confined to the content operation that asked -
 * nothing resolves a provider while the container starts - so a misconfigured deployment fails the
 * operation it misconfigured rather than the process, which is asserted here as well.
 *
 * <p><strong>Why the class is {@code final}.</strong> Checkstyle's {@code DesignForExtension} exempts
 * only the JUnit 4 lifecycle annotations, so a non-final class carrying a {@code @BeforeEach} or an
 * {@code @AfterEach} - which the configuration restore below requires - fails the build.
 *
 * <p><strong>Why the configured value is restored after every test.</strong>
 * {@code UtilProperties.setPropertyValueInMemory} mutates the cached {@code Properties} instance the
 * whole JVM shares, and the unit tier runs every test class in one JVM, so an override left behind
 * would change what another class observes. It is therefore captured before each test and written back
 * after it, together with the factory's own resolution cache.
 */
public final class ContentStoreFactoryTest {

    /** The resource the factory reads its selection from; the bare name, never the file name. */
    private static final String RESOURCE = "content";
    private static final String PROPERTY_PROVIDER = "content.store.provider";
    private static final String PROPERTY_S3_BUCKET = "content.store.s3.bucket";

    private static final String PROVIDER_DATABASE = "database";
    private static final String PROVIDER_FILESYSTEM = "filesystem";
    private static final String PROVIDER_S3 = "s3";

    /** An obviously fake bucket, so no test ever needs a real one, and no credential is involved. */
    private static final String BUCKET = "test-bucket";
    private static final String KEY = "uploads/party/logo.png";
    private static final byte[] PAYLOAD = "content bound for an object store".getBytes(StandardCharsets.UTF_8);

    /** The status a compatible object store answers an absent key with. */
    private static final int HTTP_NOT_FOUND = 404;

    private static final int WORKERS = 32;
    private static final long PATIENCE_SECONDS = 30L;

    /** The configured value as the classpath resource carries it, restored after every test. */
    private String committedProvider;

    @BeforeEach
    public void captureTheConfigurationAndDiscardAnyOutcomeAnEarlierTestCached() {
        // setPropertyValueInMemory returns silently when the resource cannot be resolved, so a test whose
        // override never took effect would quietly assert against the committed value instead. Proving the
        // resource is on the test classpath up front is what turns that into a failure here.
        assertNotNull(UtilProperties.getProperties(RESOURCE), "the [" + RESOURCE + "] resource must be resolvable"
                + " on the test classpath, otherwise an in-memory override is silently discarded");
        // Never null: the two-argument lookup answers "" for an absent key, so this is always safe to write
        // straight back in the restore below.
        committedProvider = UtilProperties.getPropertyValue(RESOURCE, PROPERTY_PROVIDER);
        ContentStoreFactory.clearCache();
    }

    @AfterEach
    public void restoreTheConfigurationAndLeaveNoOutcomeBehind() {
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_PROVIDER, committedProvider);
        ContentStoreFactory.clearCache();
    }

    @Test
    public void everyValueThatMeansDatabaseStorageResolvesToNoProvider() throws Exception {
        // Every shape a deployment can write for "leave content exactly where it is": the blank a
        // commented-out or emptied key leaves behind, and the letter cases and padding a hand-edited file
        // or a SystemProperty row arrives with. Each must leave the existing DataResource database-storage
        // path in charge. The blank is written explicitly rather than left to an absent key: this component
        // ships the key with a value, so a case that asserted nothing at all would read that shipped value
        // and quietly stop distinguishing "unset" from "database".
        for (String value : new String[] {"", "   ", PROVIDER_DATABASE, "DATABASE", "  Database  "}) {
            ContentStoreFactory.clearCache();
            assertNull(storeConfiguredAs(value), "[" + value + "] must select database storage");
        }
    }

    @Test
    public void theFilesystemValueResolvesToTheFilesystemProvider() throws Exception {
        ContentStore selected = storeConfiguredAs(PROVIDER_FILESYSTEM);

        // The type is the whole assertion. Asking the provider for an upload location would create a real
        // timestamped directory under the working tree as a side effect, which no unit test may do.
        assertNotNull(selected, "[" + PROVIDER_FILESYSTEM + "] must select a provider");
        assertTrue(selected instanceof FileSystemContentStore, "[" + PROVIDER_FILESYSTEM + "] must select the"
                + " filesystem provider, was: " + selected.getClass().getName());
    }

    @Test
    public void theObjectStoreValueResolvesToTheObjectStoreProvider() throws Exception {
        // No object-store configuration is written first, and none is needed: selecting this provider reads
        // configuration only. No client is built, no credential is resolved and no endpoint is contacted
        // until a storage operation is actually issued, which is what keeps selection free of I/O.
        ContentStore selected = storeConfiguredAs(PROVIDER_S3);

        assertNotNull(selected, "[" + PROVIDER_S3 + "] must select a provider");
        assertTrue(selected instanceof S3ContentStore, "[" + PROVIDER_S3 + "] must select the object-storage"
                + " provider, was: " + selected.getClass().getName());
    }

    @Test
    public void anUnrecognisedValueIsRefusedRatherThanReadAsDatabaseStorage() {
        // A typo must never be mistaken for a deliberate instruction. Every one of these values names a
        // backend the deployment is not provisioned for, and quietly answering "database" would send content
        // somewhere nobody asked for. Note that "fs" and "local" are refused too: there are no aliases.
        for (String value : new String[] {"nonsense", "postgres", "file system", "s-3", "fs", "local", "S3Bucket"}) {
            ContentStoreFactory.clearCache();
            ContentStoreConfigurationException refused = refusalOf(value);
            // The value as it was spelt, so a mis-cased typo can be found in the file it was written in,
            // and the key that carries it, so the operator knows where to correct it.
            assertTrue(refused.getMessage().contains(value), refused.getMessage());
            assertTrue(refused.getMessage().contains(RESOURCE + ":" + PROPERTY_PROVIDER), refused.getMessage());
            assertTrue(refused.getMessage().contains("not a recognised"), refused.getMessage());
        }
    }

    @Test
    public void aRefusalStaysConfinedToTheValueThatEarnedIt() throws Exception {
        // This is how "a misconfigured value never fails the container" is asserted. Nothing resolves a
        // provider while the container starts, so the refusal above can only reach the content operation
        // that asked for it: the factory itself stays perfectly usable afterwards.
        refusalOf("nonsense");

        assertDoesNotThrow(() -> storeConfiguredAs(PROVIDER_DATABASE),
                "a refused value must not stop database storage being selected");
        assertNull(storeConfiguredAs(PROVIDER_DATABASE));
        assertTrue(storeConfiguredAs(PROVIDER_FILESYSTEM) instanceof FileSystemContentStore,
                "a refused value must not stop another provider being built");
        assertTrue(storeConfiguredAs(PROVIDER_S3) instanceof S3ContentStore,
                "a refused value must not stop another provider being built");
    }

    @Test
    public void oneCanonicalValueYieldsOneProviderHoweverItIsSpelt() throws Exception {
        // Matching ignores case and surrounding whitespace; what these spellings pin is that the cache is
        // keyed by the same canonical form, so none of them rebuilds the provider.
        ContentStore first = storeConfiguredAs(PROVIDER_FILESYSTEM);
        assertNotNull(first);
        assertSame(first, storeConfiguredAs("FileSystem"));
        assertSame(first, storeConfiguredAs("FILESYSTEM"));
        assertSame(first, storeConfiguredAs("  filesystem  "));
        assertSame(first, storeConfiguredAs("\tFileSystem\n"));
    }

    @Test
    public void aCaseOnlyDifferenceNeverRebuildsTheObjectStoreProvider() throws Exception {
        // The object-storage provider is the expensive one to rebuild: each instance lazily opens its own
        // client, and a rebuild would abandon the previous client still holding its connections.
        ContentStore first = storeConfiguredAs(PROVIDER_S3);
        assertTrue(first instanceof S3ContentStore, "the object-storage provider");
        assertSame(first, storeConfiguredAs("S3"));
        assertSame(first, storeConfiguredAs(" s3 "));
        assertSame(first, storeConfiguredAs("  S3  "));
    }

    @Test
    public void distinctValuesEachKeepTheirOwnOutcome() throws Exception {
        // A SystemProperty row can override the resource for one caller while another still reads the
        // resource, so the two can legitimately see different values at the same moment. Selecting one must
        // therefore not evict another: alternating between them would otherwise rebuild both providers over
        // and over, abandoning a client each time.
        ContentStore fileSystem = storeConfiguredAs(PROVIDER_FILESYSTEM);
        ContentStore objectStore = storeConfiguredAs(PROVIDER_S3);
        assertNotNull(fileSystem);
        assertNotNull(objectStore);
        assertNotSame(fileSystem, objectStore);
        assertNull(storeConfiguredAs(PROVIDER_DATABASE));
        assertSame(fileSystem, storeConfiguredAs(PROVIDER_FILESYSTEM));
        assertSame(objectStore, storeConfiguredAs(PROVIDER_S3));
    }

    @Test
    public void concurrentResolutionOfOneValueConstructsExactlyOneProvider() throws Exception {
        // Written once, before any worker is released, so every thread reads the same configured value and
        // they contend for the one outcome that value does not have yet.
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_PROVIDER, PROVIDER_S3);
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        try {
            CountDownLatch atTheGate = new CountDownLatch(WORKERS);
            CountDownLatch gateOpen = new CountDownLatch(1);
            Callable<ContentStore> worker = () -> {
                atTheGate.countDown();
                if (!gateOpen.await(PATIENCE_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the starting gate never opened");
                }
                return ContentStoreFactory.getContentStore();
            };
            List<Future<ContentStore>> outcomes = new ArrayList<>();
            for (int started = 0; started < WORKERS; started++) {
                outcomes.add(pool.submit(worker));
            }
            assertTrue(atTheGate.await(PATIENCE_SECONDS, TimeUnit.SECONDS), "not every worker reached the gate");
            // Releasing every thread at once is what makes them contend for the same unresolved value.
            gateOpen.countDown();

            ContentStore only = outcomes.get(0).get(PATIENCE_SECONDS, TimeUnit.SECONDS);
            assertTrue(only instanceof S3ContentStore, "the object-storage provider");
            for (Future<ContentStore> outcome : outcomes) {
                // A second instance here would be one that no caller keeps and nothing ever closes.
                assertSame(only, outcome.get(PATIENCE_SECONDS, TimeUnit.SECONDS),
                        "concurrent resolution built more than one provider for one value");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void clearingTheCacheClosesTheClientOfTheProviderItDiscards() throws Exception {
        S3Client opened = mock(S3Client.class);
        // Seated through the factory's own test seam, holding a client this test can watch. A provider the
        // factory discards owns an HTTP connection pool and its idle threads, so a reset that dropped the
        // provider without closing it would leak that pool for the lifetime of the JVM.
        S3ContentStore cached = new S3ContentStore(opened, BUCKET);
        ContentStoreFactory.installForTesting(PROVIDER_S3, cached);
        assertSame(cached, storeConfiguredAs(PROVIDER_S3), "the seated provider must be the outcome");

        ContentStoreFactory.clearCache();

        verify(opened, times(1)).close();
        // Releasing the client is only half of it: the outcome must be gone too, so the next call resolves
        // the configuration afresh rather than handing back a provider whose client is closed.
        assertNotSame(cached, storeConfiguredAs(PROVIDER_S3));
        assertThrows(GeneralException.class, () -> cached.exists(KEY),
                "the discarded provider must refuse a later operation rather than rebuild a client");
    }

    @Test
    public void clearingTheCacheIsHarmlessWhateverTheCachedOutcomesAre() throws Exception {
        // Every shape of outcome has to survive the reset rather than being narrowed to one: an outcome
        // holding no provider at all would be dereferenced, a recorded refusal holds no provider to close,
        // and a filesystem provider holds no object-storage client. All three are cached here first.
        assertNull(storeConfiguredAs(PROVIDER_DATABASE));
        refusalOf("nonsense");
        ContentStore beforeTheReset = storeConfiguredAs(PROVIDER_FILESYSTEM);
        assertNotNull(beforeTheReset);

        assertDoesNotThrow(ContentStoreFactory::clearCache);

        // The reset really happened, rather than being abandoned part-way: the filesystem provider is built
        // afresh, and the refusal is re-derived rather than served from a surviving entry.
        assertNotSame(beforeTheReset, storeConfiguredAs(PROVIDER_FILESYSTEM));
        refusalOf("nonsense");
    }

    @Test
    public void aRecognisedProviderThatCannotBeBuiltFailsRatherThanSwitchingToDatabaseStorage() {
        // The configured value is written before the configuration is broken, so what fails here is the
        // provider's own construction rather than the factory's reading of the selection.
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_PROVIDER, PROVIDER_S3);

        ContentStoreConfigurationException refused = withUnreadableObjectStoreConfiguration(() -> assertThrows(
                ContentStoreConfigurationException.class, ContentStoreFactory::getContentStore));

        // Silently answering null here would send content to a backend nobody asked for.
        assertTrue(refused.getMessage().contains(PROVIDER_S3), refused.getMessage());
        assertTrue(refused.getMessage().contains(RESOURCE + ":" + PROPERTY_PROVIDER), refused.getMessage());
        assertTrue(refused.getNested() instanceof IllegalStateException,
                "the underlying reason must be kept, was: " + refused.getNested());
    }

    @Test
    public void aConstructionFailureKeepsFailingIdenticallyOnceTheCauseHasPassed() {
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_PROVIDER, PROVIDER_S3);
        ContentStoreConfigurationException first = withUnreadableObjectStoreConfiguration(() -> assertThrows(
                ContentStoreConfigurationException.class, ContentStoreFactory::getContentStore));

        // The transient condition is over, yet the answer must not change: an outcome that depended on how
        // often it was asked for would let one caller store content while another was refused.
        ContentStoreConfigurationException later = refusalOf(PROVIDER_S3);
        ContentStoreConfigurationException laterStill = refusalOf("  S3  ");

        assertEquals(first.getMessage(), later.getMessage());
        assertEquals(first.getMessage(), laterStill.getMessage());
        assertSame(first.getNested(), later.getNested());
        // A fresh wrapper each time, so a stack trace belongs to the thread that is being refused now.
        assertNotSame(first, later);
    }

    @Test
    public void clearingTheCacheLetsARepairedProviderBeBuiltAgain() throws Exception {
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_PROVIDER, PROVIDER_S3);
        withUnreadableObjectStoreConfiguration(() -> assertThrows(
                ContentStoreConfigurationException.class, ContentStoreFactory::getContentStore));

        ContentStoreFactory.clearCache();

        // Nothing is permanently poisoned: once the configuration is readable the provider is built.
        assertTrue(storeConfiguredAs(PROVIDER_S3) instanceof S3ContentStore,
                "a repaired configuration must build the provider it names");
    }

    @Test
    public void anOverrideReachesTheVeryResourceInstanceTheFactoryReads() throws Exception {
        // The guard against a write that never landed. setPropertyValueInMemory mutates the cached
        // Properties instance and returns without a word when the resource cannot be resolved, so a case
        // whose override was discarded would silently assert against the value this component ships and
        // pass for the wrong reason. Reading the value back, and then watching the selection follow it and
        // follow it back again, is what rules that out for every other case here.
        ContentStore selected = storeConfiguredAs("  FileSystem  ");

        assertTrue(selected instanceof FileSystemContentStore, "the selection must follow the override, was: "
                + (selected == null ? PROVIDER_DATABASE : selected.getClass().getName()));

        ContentStoreFactory.clearCache();

        assertNull(storeConfiguredAs(""), "withdrawing the override must return the selection to database storage");
    }

    @Test
    public void everyStorageOperationIsIssuedThroughTheClientTheSeamWasGiven() throws Exception {
        S3Client client = mock(S3Client.class);
        when(client.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(KEY).build()))
                .thenReturn(HeadObjectResponse.builder().contentLength((long) PAYLOAD.length).build());
        when(client.getObject(GetObjectRequest.builder().bucket(BUCKET).key(KEY).build()))
                .thenAnswer(invocation -> storedObject(PAYLOAD));
        // The production provider, with only the SDK boundary replaced: no configuration is read, no
        // credential is resolved and no endpoint is contacted, yet the provider's own request building,
        // absence mapping and stream ownership are the code under test.
        ContentStore provider = new S3ContentStore(client, BUCKET);

        provider.put(KEY, PAYLOAD);
        assertTrue(provider.exists(KEY), "a stored key must be reported as present");
        assertEquals(PAYLOAD.length, provider.size(KEY), "the length must come from the object's metadata");
        assertArrayEquals(PAYLOAD, provider.get(KEY), "the whole object must be returned");
        try (InputStream streamed = provider.openStream(KEY)) {
            assertArrayEquals(PAYLOAD, streamed.readAllBytes(), "openStream must serve the object from its start");
        }
        provider.delete(KEY);

        // Each operation must reach the store as the one request S3 expects, addressed to the configured
        // bucket and the caller's key. The write declares its length up front, which is what lets content
        // of any size cross the boundary in a single request without a second copy being held.
        verify(client).putObject(eq(PutObjectRequest.builder().bucket(BUCKET).key(KEY)
                .contentLength((long) PAYLOAD.length).build()), any(RequestBody.class));
        verify(client).deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(KEY).build());
    }

    @Test
    public void anAbsentObjectIsReportedAsAbsenceRatherThanAsAStoreFailure() throws Exception {
        S3Client client = mock(S3Client.class);
        NoSuchKeyException absent = NoSuchKeyException.builder()
                .statusCode(HTTP_NOT_FOUND)
                .message("The specified key does not exist")
                .build();
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(absent);
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(absent);
        when(client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(absent);
        ContentStore provider = new S3ContentStore(client, BUCKET);

        // A key that resolves to nothing is a fact about the store, not a failure of it: the probe answers
        // false rather than raising, so a caller can ask without having to catch.
        assertFalse(provider.exists(KEY), "an absent key must be reported as absent, not as a failure");
        // The reading operations do raise - so that neither of them can ever answer null - and they raise
        // the platform's own absence type, which is what lets the delegation seam treat a missing object
        // exactly as it treats a missing file.
        assertThrows(FileNotFoundException.class, () -> provider.get(KEY));
        assertThrows(FileNotFoundException.class, () -> provider.openStream(KEY));
        // And removal is idempotent, so replayed or repeated clean-up is safe.
        assertDoesNotThrow(() -> provider.delete(KEY), "deleting an absent key must be a successful no-op");
    }

    /**
     * Configures the supplied value and hands back what the factory then selects, which is how every case
     * here drives the selection: through the configuration a deployment actually writes and the public
     * lookup production code actually calls, rather than around either of them.
     *
     * <p>The value is read straight back before the lookup because
     * {@code UtilProperties.setPropertyValueInMemory} is documented to return without a word when the
     * resource cannot be resolved. A discarded write would otherwise leave the case asserting against the
     * value this component ships, so this is the guard that turns that into a failure. The comparison drops
     * the padding because the two-argument lookup trims what it returns.
     *
     * <p>The factory's own cache is deliberately <em>not</em> reset here: a case that wants a provider built
     * afresh resets it first, and the cases that pin canonicalisation rely on a second spelling of the same
     * value reaching the outcome already cached for it.
     *
     * @param configuredProvider the value to configure; never null, because the setter is backed by a
     *     {@code Hashtable} and blank is how an unset key is expressed
     * @return the provider the factory selects for that value, or {@code null} for database mode
     * @throws GeneralException if the factory refuses the configured value
     */
    private static ContentStore storeConfiguredAs(String configuredProvider) throws GeneralException {
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_PROVIDER, configuredProvider);
        assertEquals(configuredProvider.trim(), UtilProperties.getPropertyValue(RESOURCE, PROPERTY_PROVIDER),
                "the in-memory override never reached the resource the factory reads");
        return ContentStoreFactory.getContentStore();
    }

    /**
     * Configures a value that must not be honoured, and hands back the refusal it earned.
     *
     * <p>Named once here because a refusal is asserted from several angles - an unrecognised value, the same
     * value asked for again, and a refusal standing beside outcomes that must survive it - and every one of
     * them wants the exception itself rather than only the fact that one was raised.
     *
     * @param configuredProvider the value expected to be refused; never null
     * @return the refusal the factory raised for that value
     */
    private static ContentStoreConfigurationException refusalOf(String configuredProvider) {
        return assertThrows(ContentStoreConfigurationException.class, () -> storeConfiguredAs(configuredProvider),
                "[" + configuredProvider + "] must be refused rather than honoured");
    }

    /**
     * Runs the supplied assertion while the object-storage provider's own first configuration read fails,
     * which is how a provider that a deployment explicitly named comes to be unbuildable without the
     * client library being absent.
     *
     * <p>Stubbing the configuration lookup is the only way to reach this state: neither provider
     * constructor validates anything, so no configured value can make one of them fail. Only that single
     * lookup is broken - every other static call keeps its real behaviour - so the failure arises inside
     * the constructor exactly as an environmental fault would.
     *
     * @param <T> the type the assertion yields
     * @param assertion the assertion to run against the broken configuration
     * @return whatever the assertion yielded
     */
    private static <T> T withUnreadableObjectStoreConfiguration(Callable<T> assertion) {
        try (MockedStatic<UtilProperties> properties = mockStatic(UtilProperties.class, Answers.CALLS_REAL_METHODS)) {
            properties.when(() -> UtilProperties.getPropertyValue(RESOURCE, PROPERTY_S3_BUCKET, ""))
                    .thenThrow(new IllegalStateException("configuration store unreadable"));
            return assertion.call();
        } catch (Exception e) {
            throw new AssertionError("the assertion under a broken configuration could not be run", e);
        }
    }

    /**
     * Builds the reply a compatible object store gives to a read, so the provider under test receives the
     * response type the SDK really hands it rather than a stand-in.
     *
     * <p>A fresh stream per call, because {@code openStream} is documented to hand back an independent
     * stream positioned at the first byte however many times it is invoked.
     *
     * @param content the bytes the store is to serve
     * @return an SDK response stream over the supplied content
     */
    private static ResponseInputStream<GetObjectResponse> storedObject(byte[] content) {
        return new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength((long) content.length).build(),
                AbortableInputStream.create(new ByteArrayInputStream(content)));
    }
}
