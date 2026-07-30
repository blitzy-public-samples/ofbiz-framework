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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.util.EntityUtilProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.MockedStatic;

import software.amazon.awssdk.services.s3.S3Client;

/**
 * Unit tests for {@link ContentStoreFactory}: how a configured value is normalised, how the outcome
 * it resolves to is cached and released, and what happens when a provider the deployment explicitly
 * named cannot be built.
 *
 * <p><strong>Why these tests reach past the two public methods.</strong> Both public forms read
 * {@code content.store.provider} from the {@code content} resource, and the copy of that resource on
 * the test classpath carries the committed default, {@code database}. Several tests therefore drive
 * the private resolution seam directly, which is the same code the public methods run and is the only
 * way to exercise {@code filesystem}, {@code s3} and an unrecognised value in one JVM without
 * rewriting a file on the classpath mid-run. That the public methods reach that seam - and that the
 * committed configuration really does select database storage - is covered separately, by stubbing
 * the property lookup itself. The keys and committed values of the resource are pinned by
 * {@link ContentStorePropertiesContractTests}.
 *
 * <p><strong>Why the object-storage client type appears here.</strong> One test has to observe that
 * clearing the cache closes a client the factory had opened, and the only way to observe that is to
 * verify {@code close()} on a client the test supplied. The reference is confined to that one
 * concern; no production class outside {@link S3ContentStore} refers to the client library.
 */
public final class ContentStoreFactoryTest {

    private static final String RESOURCE = "content";
    private static final String PROPERTY_PROVIDER = "content.store.provider";
    private static final String PROVIDER_DATABASE = "database";
    private static final String PROVIDER_FILESYSTEM = "filesystem";
    private static final String PROVIDER_S3 = "s3";

    /** The private resolution seam these tests drive; see the type comment for why. */
    private static final Method RESOLVE = resolutionSeam();

    @BeforeEach
    public void discardAnyOutcomeAnEarlierTestCached() {
        ContentStoreFactory.clearCache();
    }

    @AfterEach
    public void leaveNoOutcomeBehindForTheNextTest() {
        ContentStoreFactory.clearCache();
    }

    @Test
    public void everyValueThatMeansDatabaseStorageResolvesToNoProvider() throws Exception {
        // null covers a lookup that yielded nothing at all; the rest are the shapes a deployment can
        // write. Each must leave the existing DataResource database-storage path in charge.
        for (String value : new String[] {null, "", "   ", PROVIDER_DATABASE, "DATABASE", "  Database  "}) {
            ContentStoreFactory.clearCache();
            assertNull(resolve(value), "[" + value + "] must select database storage");
        }
    }

    @Test
    public void anUnrecognisedValueIsRefusedRatherThanReadAsDatabaseStorage() {
        // A typo must never be mistaken for a deliberate instruction. It is refused rather than read as
        // database storage: every one of these values names a backend the deployment is not provisioned
        // for, and quietly answering "database" would send content somewhere nobody asked for. Nothing
        // is resolved during start up - the factory is reached from a content operation - so a refusal
        // here fails the operation that is misconfigured instead of the container.
        for (String value : new String[] {"nonsense", "postgres", "file system", "s-3", "fs", "local", "S3Bucket"}) {
            ContentStoreFactory.clearCache();
            ContentStoreConfigurationException refused = assertThrows(
                    ContentStoreConfigurationException.class, () -> resolve(value),
                    "[" + value + "] is unrecognised and must be refused");
            // The value as it was spelt, so a mis-cased typo can be found in the file it was written in,
            // and the key that carries it, so the operator knows where to correct it.
            assertTrue(refused.getMessage().contains(value), refused.getMessage());
            assertTrue(refused.getMessage().contains(RESOURCE + ":" + PROPERTY_PROVIDER), refused.getMessage());
            assertTrue(refused.getMessage().contains("not a recognised"), refused.getMessage());
        }
    }

    @Test
    public void theFilesystemValueResolvesToTheFilesystemProvider() throws Exception {
        assertInstanceOf(FileSystemContentStore.class, resolve(PROVIDER_FILESYSTEM));
    }

    @Test
    public void theObjectStoreValueResolvesToTheObjectStoreProvider() throws Exception {
        assertInstanceOf(S3ContentStore.class, resolve(PROVIDER_S3));
    }

    @Test
    public void oneCanonicalValueYieldsOneProviderHoweverItIsSpelt() throws Exception {
        // Matching has always ignored case and surrounding whitespace; what these spellings pin is
        // that the cache is keyed by the same canonical form, so none of them rebuilds the provider.
        ContentStore first = resolve(PROVIDER_FILESYSTEM);
        assertNotNull(first);
        assertSame(first, resolve("FileSystem"));
        assertSame(first, resolve("FILESYSTEM"));
        assertSame(first, resolve("  filesystem  "));
        assertSame(first, resolve("\tFileSystem\n"));
    }

    @Test
    public void aCaseOnlyDifferenceNeverRebuildsTheObjectStoreProvider() throws Exception {
        // The object-storage provider is the expensive one to rebuild: each instance lazily opens its
        // own client, and a rebuild would abandon the previous client still holding its connections.
        ContentStore first = resolve(PROVIDER_S3);
        assertInstanceOf(S3ContentStore.class, first);
        assertSame(first, resolve("S3"));
        assertSame(first, resolve(" s3 "));
        assertSame(first, resolve("  S3  "));
    }

    @Test
    public void distinctValuesEachKeepTheirOwnOutcome() throws Exception {
        // The two public forms can legitimately see different values at the same moment, so resolving
        // one value must not evict another: alternating between them would otherwise rebuild both
        // providers over and over.
        ContentStore fileSystem = resolve(PROVIDER_FILESYSTEM);
        ContentStore objectStore = resolve(PROVIDER_S3);
        assertNotNull(fileSystem);
        assertNotNull(objectStore);
        assertNotSame(fileSystem, objectStore);
        assertNull(resolve(PROVIDER_DATABASE));
        assertSame(fileSystem, resolve(PROVIDER_FILESYSTEM));
        assertSame(objectStore, resolve(PROVIDER_S3));
    }

    @Test
    public void concurrentResolutionOfOneValueConstructsExactlyOneProvider() throws Exception {
        int workers = 32;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            CountDownLatch atTheGate = new CountDownLatch(workers);
            CountDownLatch gateOpen = new CountDownLatch(1);
            Callable<ContentStore> worker = () -> {
                atTheGate.countDown();
                if (!gateOpen.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the starting gate never opened");
                }
                return resolve(PROVIDER_S3);
            };
            List<Future<ContentStore>> outcomes = new ArrayList<>();
            for (int worker0 = 0; worker0 < workers; worker0++) {
                outcomes.add(pool.submit(worker));
            }
            assertTrue(atTheGate.await(30, TimeUnit.SECONDS), "not every worker reached the starting gate");
            // Releasing every thread at once is what makes them contend for the same unresolved value.
            gateOpen.countDown();

            ContentStore only = outcomes.get(0).get(30, TimeUnit.SECONDS);
            assertInstanceOf(S3ContentStore.class, only);
            for (Future<ContentStore> outcome : outcomes) {
                // A second instance here would be one that no caller keeps and nothing ever closes.
                assertSame(only, outcome.get(30, TimeUnit.SECONDS),
                        "concurrent resolution built more than one provider for one value");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void clearingTheCacheClosesAnObjectStoreClientTheProviderOpened() throws Exception {
        S3ContentStore cached = assertInstanceOf(S3ContentStore.class, resolve(PROVIDER_S3));
        S3Client opened = mock(S3Client.class);
        // The provider came from the factory, so it owns whatever client it holds; standing one in
        // here is the only way to watch the factory release it.
        injectClient(cached, opened);

        ContentStoreFactory.clearCache();

        verify(opened, times(1)).close();
        // Releasing the client is only half of it: the outcome must be gone too, so the next call
        // resolves the configuration afresh rather than handing back a provider with a closed client.
        assertNotSame(cached, resolve(PROVIDER_S3));
    }

    @Test
    public void clearingTheCacheIsHarmlessWhenNoObjectStoreProviderWasEverResolved() throws Exception {
        // Every outcome that holds no object-storage client has to be passed over rather than
        // narrowed to one: a filesystem provider narrowed to the object-storage type would fail, an
        // outcome holding no provider at all would be dereferenced, and a recorded refusal holds no
        // provider to close either. All three shapes are cached here before the reset.
        assertNull(resolve(PROVIDER_DATABASE));
        assertThrows(ContentStoreConfigurationException.class, () -> resolve("nonsense"));
        ContentStore beforeTheReset = resolve(PROVIDER_FILESYSTEM);
        assertNotNull(beforeTheReset);

        assertDoesNotThrow(ContentStoreFactory::clearCache);
        // The reset really happened, rather than being abandoned part-way: the filesystem provider is
        // built afresh, and the refusal is re-derived rather than served from a surviving entry.
        assertNotSame(beforeTheReset, resolve(PROVIDER_FILESYSTEM));
        assertThrows(ContentStoreConfigurationException.class, () -> resolve("nonsense"));
    }

    @Test
    public void clearingTheCacheReleasesEveryCachedOutcome() throws Exception {
        ContentStore fileSystem = resolve(PROVIDER_FILESYSTEM);
        S3ContentStore objectStore = assertInstanceOf(S3ContentStore.class, resolve(PROVIDER_S3));
        S3Client opened = mock(S3Client.class);
        injectClient(objectStore, opened);
        assertEquals(2, cachedOutcomes().size());

        ContentStoreFactory.clearCache();

        assertTrue(cachedOutcomes().isEmpty(), "the cache still holds an outcome");
        verify(opened, times(1)).close();
        assertNotSame(fileSystem, resolve(PROVIDER_FILESYSTEM));
    }

    @Test
    public void aRecognisedProviderThatCannotBeBuiltFailsRatherThanSwitchingToDatabaseStorage() {
        ContentStoreConfigurationException refused = withUnreadableObjectStoreConfiguration(() ->
                assertThrows(ContentStoreConfigurationException.class, () -> resolve(PROVIDER_S3)));

        // Silently answering null here would send content to a backend nobody asked for.
        assertTrue(refused.getMessage().contains(PROVIDER_S3), refused.getMessage());
        assertTrue(refused.getMessage().contains(RESOURCE + ":" + PROPERTY_PROVIDER), refused.getMessage());
        assertInstanceOf(IllegalStateException.class, refused.getNested());
    }

    @Test
    public void aConstructionFailureKeepsFailingIdenticallyOnceTheCauseHasPassed() {
        ContentStoreConfigurationException first = withUnreadableObjectStoreConfiguration(() ->
                assertThrows(ContentStoreConfigurationException.class, () -> resolve(PROVIDER_S3)));

        // The transient condition is over, yet the answer must not change: an outcome that depended
        // on how often it was asked for would let one caller store content while another was refused.
        ContentStoreConfigurationException later =
                assertThrows(ContentStoreConfigurationException.class, () -> resolve(PROVIDER_S3));
        ContentStoreConfigurationException laterStill =
                assertThrows(ContentStoreConfigurationException.class, () -> resolve("  S3  "));

        assertEquals(first.getMessage(), later.getMessage());
        assertEquals(first.getMessage(), laterStill.getMessage());
        assertSame(first.getNested(), later.getNested());
        // A fresh wrapper each time, so a stack trace belongs to the thread that is being refused now.
        assertNotSame(first, later);
    }

    @Test
    public void clearingTheCacheLetsARepairedProviderBeBuiltAgain() throws Exception {
        withUnreadableObjectStoreConfiguration(() ->
                assertThrows(ContentStoreConfigurationException.class, () -> resolve(PROVIDER_S3)));

        ContentStoreFactory.clearCache();

        // Nothing is permanently poisoned: once the configuration is readable the provider is built.
        assertInstanceOf(S3ContentStore.class, resolve(PROVIDER_S3));
    }

    @Test
    public void aFailureForOneValueLeavesTheOtherValuesAlone() throws Exception {
        withUnreadableObjectStoreConfiguration(() ->
                assertThrows(ContentStoreConfigurationException.class, () -> resolve(PROVIDER_S3)));

        // Each value keeps its own outcome, so a failure recorded against one must not spread: the
        // configured database value still selects database storage, an unrecognised value is still
        // refused for its own reason rather than for this one, and the filesystem provider still
        // builds.
        assertNull(resolve(PROVIDER_DATABASE));
        ContentStoreConfigurationException unrelated =
                assertThrows(ContentStoreConfigurationException.class, () -> resolve("nonsense"));
        assertTrue(unrelated.getMessage().contains("not a recognised"), unrelated.getMessage());
        assertInstanceOf(FileSystemContentStore.class, resolve(PROVIDER_FILESYSTEM));
    }

    @Test
    public void theCommittedConfigurationSelectsDatabaseStorage() throws Exception {
        // No stubbing at all: this is the real content.properties on the classpath, and an unmodified
        // checkout must keep using the existing DataResource database storage.
        assertNull(ContentStoreFactory.getContentStore());
        assertNull(ContentStoreFactory.getContentStore(null));
    }

    @Test
    public void theClasspathFormResolvesTheProviderTheResourceNames() throws Exception {
        try (MockedStatic<UtilProperties> properties = mockStatic(UtilProperties.class, Answers.CALLS_REAL_METHODS)) {
            properties.when(() -> UtilProperties.getPropertyValue(RESOURCE, PROPERTY_PROVIDER, PROVIDER_DATABASE))
                    .thenReturn("  FileSystem  ");

            ContentStore selected = ContentStoreFactory.getContentStore();

            assertInstanceOf(FileSystemContentStore.class, selected);
            // The public form shares the cache with every other form, padding and case included.
            assertSame(selected, resolve(PROVIDER_FILESYSTEM));
        }
    }

    @Test
    public void theDelegatorFormHonoursASystemPropertyOverride() throws Exception {
        Delegator delegator = mock(Delegator.class);
        try (MockedStatic<EntityUtilProperties> properties =
                mockStatic(EntityUtilProperties.class, Answers.CALLS_REAL_METHODS)) {
            properties.when(() -> EntityUtilProperties
                            .getPropertyValue(RESOURCE, PROPERTY_PROVIDER, PROVIDER_DATABASE, delegator))
                    .thenReturn("S3");

            ContentStore selected = ContentStoreFactory.getContentStore(delegator);

            assertInstanceOf(S3ContentStore.class, selected);
            assertSame(selected, resolve(PROVIDER_S3));
        }
    }

    @Test
    public void aNullDelegatorFallsBackToTheClasspathLookup() throws Exception {
        try (MockedStatic<UtilProperties> properties = mockStatic(UtilProperties.class, Answers.CALLS_REAL_METHODS)) {
            properties.when(() -> UtilProperties.getPropertyValue(RESOURCE, PROPERTY_PROVIDER, PROVIDER_DATABASE))
                    .thenReturn(PROVIDER_FILESYSTEM);

            // EntityUtilProperties dereferences the delegator, so a null one has to take this route.
            assertInstanceOf(FileSystemContentStore.class, ContentStoreFactory.getContentStore(null));
        }
    }

    @Test
    public void aConstructionFailureSurfacesThroughThePublicFormsToo() {
        try (MockedStatic<UtilProperties> properties = mockStatic(UtilProperties.class, Answers.CALLS_REAL_METHODS)) {
            properties.when(() -> UtilProperties.getPropertyValue(RESOURCE, PROPERTY_PROVIDER, PROVIDER_DATABASE))
                    .thenReturn(PROVIDER_S3);
            properties.when(() -> UtilProperties.getPropertyValue(RESOURCE, "content.store.s3.bucket", ""))
                    .thenThrow(new IllegalStateException("configuration store unreadable"));

            assertThrows(ContentStoreConfigurationException.class, ContentStoreFactory::getContentStore);
        }
    }

    /**
     * Runs the supplied assertion while the object-storage provider's own first configuration read
     * fails, which is how a provider that a deployment explicitly named comes to be unbuildable
     * without the client library being absent.
     *
     * @param <T> the type the assertion yields
     * @param assertion the assertion to run against the broken configuration
     * @return whatever the assertion yielded
     */
    private static <T> T withUnreadableObjectStoreConfiguration(Callable<T> assertion) {
        try (MockedStatic<UtilProperties> properties = mockStatic(UtilProperties.class, Answers.CALLS_REAL_METHODS)) {
            // Only this one lookup is broken; everything else keeps its real behaviour, so the failure
            // arises inside the provider's constructor exactly as an environmental fault would.
            properties.when(() -> UtilProperties.getPropertyValue(RESOURCE, "content.store.s3.bucket", ""))
                    .thenThrow(new IllegalStateException("configuration store unreadable"));
            return assertion.call();
        } catch (Exception e) {
            throw new AssertionError("the assertion under a broken configuration could not be run", e);
        }
    }

    /**
     * Invokes the factory's private resolution seam.
     *
     * <p>The seam declares a checked {@link GeneralException}, so reflection's wrapper is unwrapped
     * and the refusal is rethrown as itself: a test asserting how a value is refused must see the
     * factory's own exception, not an {@code InvocationTargetException} around it.
     *
     * @param configuredProvider the raw configured value to resolve; may be null
     * @return the provider that value selects, or null for database storage
     * @throws GeneralException if the configured value cannot be honoured, which is what the factory
     *     raises for a value naming no provider and for a provider that could not be constructed
     */
    private static ContentStore resolve(String configuredProvider) throws GeneralException {
        try {
            return (ContentStore) RESOLVE.invoke(null, configuredProvider);
        } catch (IllegalAccessException e) {
            throw new AssertionError("the resolution seam is not reachable", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof GeneralException refused) {
                throw refused;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError("resolution failed in an unexpected way", cause);
        }
    }

    /**
     * @return the factory's live cache, so a test can see how many outcomes it holds
     */
    private static Map<?, ?> cachedOutcomes() throws ReflectiveOperationException {
        Field field = ContentStoreFactory.class.getDeclaredField("RESOLUTIONS");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(null);
    }

    /**
     * @return the factory's private resolution seam
     */
    private static Method resolutionSeam() {
        try {
            Method seam = ContentStoreFactory.class.getDeclaredMethod("resolve", String.class);
            seam.setAccessible(true);
            return seam;
        } catch (NoSuchMethodException e) {
            throw new AssertionError("ContentStoreFactory.resolve(String) is the seam these tests drive", e);
        }
    }

    /**
     * Stands a caller-supplied client into a provider the factory built, leaving the provider's own
     * ownership flag untouched so the factory still treats the client as its own to close.
     *
     * @param target the provider to inject into
     * @param injected the client the provider is to hold
     * @throws ReflectiveOperationException if the provider's client field cannot be reached
     */
    private static void injectClient(S3ContentStore target, S3Client injected) throws ReflectiveOperationException {
        Field field = S3ContentStore.class.getDeclaredField("s3Client");
        field.setAccessible(true);
        field.set(target, injected);
    }
}
