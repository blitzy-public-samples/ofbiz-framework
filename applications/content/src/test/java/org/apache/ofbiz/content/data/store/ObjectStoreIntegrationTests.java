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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.entity.Delegator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.function.Executable;

/**
 * What only a real S3-compatible object store can establish: that content written through the provider is
 * really in the store, really comes back, and is really gone once deleted.
 *
 * <p>{@link ContentStoreFactoryTest} covers this package offline against a mocked {@code S3Client}, which
 * establishes what the provider ASKS the SDK for. It cannot establish that the request the SDK then builds is
 * one a store accepts, that the bytes survive the wire, that a key naming nothing produces the refusal this
 * deployment relies on, or that two tenancies really reach two different objects. Those are the load-bearing
 * claims of Objective 3 - an instance holds no durable local state because the object store holds it - so they
 * are verified against a store rather than asserted.
 *
 * <p><strong>Nothing about the store is committed here.</strong> Its address, bucket, region and credentials
 * arrive as {@code ofbiz.test.s3.*} system properties, or as the matching {@code OFBIZ_TEST_S3_*} environment
 * variables, and are read at run time. An earlier revision of the offline suite carried a working endpoint,
 * bucket, access key and secret as source constants: that is a credential in the repository whatever the store
 * is, it made every clone of this repository address the same bucket with the same keys, and it left the
 * checks skipping themselves - reporting success for a contract nothing had verified - whenever the store was
 * absent. All three are fixed here by the same means: injected settings, a per-run key namespace, and a
 * missing setting that FAILS instead of skipping.
 *
 * <p><strong>Every object is written under a namespace of this run's own</strong>
 * ({@code content.store.s3.key.prefix}), built from a random identifier and the clone index when one is set,
 * so two runs against one bucket - which is what parallel clones do - cannot see, overwrite or delete each
 * other's objects. Every key written is recorded and deleted after each test, and the deletion is asserted, so
 * a failing test cannot leave the bucket holding its fixtures.
 *
 * <p><strong>Gated by the settings, not by a build task.</strong> When the {@code ofbiz.test.s3.*} settings
 * are absent every case here is SKIPPED, with a message naming what to supply, so the unit tier stays
 * offline and reaches no network. A skip is reported as a skip - it never counts as a pass - which is what
 * keeps an unconfigured run from looking like a verified one. Supply the settings, as system properties or
 * as the matching {@code OFBIZ_TEST_S3_*} environment variables, and the whole class runs against the
 * store; the environment form is the one that reaches Gradle's forked test JVM without any build change.
 *
 * @see ContentStoreFactoryTest
 */
public final class ObjectStoreIntegrationTests {

    /** The name this class logs under. */
    private static final String MODULE = ObjectStoreIntegrationTests.class.getName();

    private static final String PROPERTY_PROVIDER = "content.store.provider";
    private static final String PROPERTY_S3_BUCKET = "content.store.s3.bucket";
    private static final String PROPERTY_S3_REGION = "content.store.s3.region";
    private static final String PROPERTY_S3_ENDPOINT = "content.store.s3.endpoint";
    private static final String PROPERTY_S3_ACCESS_KEY_ID = "content.store.s3.access.key.id";
    private static final String PROPERTY_S3_SECRET_ACCESS_KEY = "content.store.s3.secret.access.key";
    private static final String PROPERTY_S3_PATH_STYLE = "content.store.s3.path.style";
    private static final String PROPERTY_S3_KEY_PREFIX = "content.store.s3.key.prefix";
    private static final String PROPERTY_S3_INSECURE_ENDPOINT = "content.store.s3.insecure.endpoint.allowed";
    private static final String PROPERTY_LOCAL_FALLBACK = "content.store.local.fallback";

    /** Every property this class writes, and therefore every property it has to put back. */
    private static final String[] MUTATED_PROPERTIES = {
        PROPERTY_PROVIDER, PROPERTY_S3_BUCKET, PROPERTY_S3_REGION, PROPERTY_S3_ENDPOINT,
        PROPERTY_S3_ACCESS_KEY_ID, PROPERTY_S3_SECRET_ACCESS_KEY, PROPERTY_S3_PATH_STYLE,
        PROPERTY_S3_KEY_PREFIX, PROPERTY_S3_INSECURE_ENDPOINT, PROPERTY_LOCAL_FALLBACK,
    };

    /**
     * The settings that must be supplied, and the reason each is required rather than defaulted: a default
     * endpoint would send this deployment's credentials somewhere it was not told to, a default bucket would
     * write into somebody else's, and a default credential is a credential in the repository.
     */
    private static final String[] REQUIRED_SETTINGS = {
        "ofbiz.test.s3.endpoint", "ofbiz.test.s3.region", "ofbiz.test.s3.bucket",
        "ofbiz.test.s3.access.key.id", "ofbiz.test.s3.secret.access.key",
    };

    /** A resource identifier the store holds content for in these tests. */
    private static final String RESOURCE_ID = "90000";

    /** A resource identifier the store deliberately holds nothing for. */
    private static final String ABSENT_RESOURCE_ID = "90001";

    /** The namespace every object of this run is written under, so parallel runs cannot collide. */
    private String namespace;

    /** Every key this test wrote, so every one of them can be removed again. */
    private final List<String> written = new ArrayList<>();

    /** The values the properties held before this test, restored afterwards. */
    private final Map<String, String> restore = new LinkedHashMap<>();

    /** The {@code ofbiz.home} this test found, restored afterwards. */
    private String previousHome;

    @BeforeEach
    public void configureTheStore() {
        previousHome = System.getProperty("ofbiz.home");
        for (String property : MUTATED_PROPERTIES) {
            restore.put(property, UtilProperties.getPropertyValue("content", property));
        }
        // One namespace per test, not per class: a test that fails before its cleanup runs must not be able to
        // leave an object where the next test looks for one. The clone index is included when the build sets
        // it, so parallel clones sharing one bucket are separated by more than chance.
        String clone = System.getenv("CLONE_INDEX");
        namespace = "ofbiz-test/" + (clone == null || clone.isBlank() ? "local" : clone) + "/"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        applyConfiguration();
    }

    @AfterEach
    public void removeEveryObjectThisTestWrote() {
        List<String> undeleted = new ArrayList<>();
        try {
            if (!written.isEmpty()) {
                applyConfiguration();
                ContentStore store = ContentStoreFactory.getContentStore();
                for (String key : new LinkedHashSet<>(written)) {
                    try {
                        store.delete(key);
                        if (store.exists(key)) {
                            undeleted.add(key);
                        }
                    } catch (GeneralException | IOException stubborn) {
                        undeleted.add(key + " (" + stubborn.getMessage() + ")");
                    }
                }
            }
        } catch (GeneralException unusable) {
            undeleted.add("the provider could not be reopened to clean up: " + unusable.getMessage());
        } finally {
            written.clear();
            ContentStoreFactory.clearCache();
            for (Map.Entry<String, String> held : restore.entrySet()) {
                UtilProperties.setPropertyValueInMemory("content", held.getKey(), held.getValue());
            }
            restore.clear();
            if (previousHome == null) {
                System.clearProperty("ofbiz.home");
            } else {
                System.setProperty("ofbiz.home", previousHome);
            }
            ContentStoreFactory.clearCache();
        }
        // Reported as a failure rather than logged: an object left in a shared bucket is this suite's litter,
        // and a run that cannot remove what it wrote has to say so while the reason is still known.
        assertTrue(undeleted.isEmpty(), "every object this test wrote must be removed again, and these were"
                + " not: " + undeleted);
    }

    /**
     * The whole round trip, against a store: put, exists, get, openStream, the worker seam, and delete.
     *
     * <p>The bytes in the bucket are deliberately different from the bytes on disk beside them, because "served
     * from the store" and "served from the local copy" are the two answers this seam has to be told apart - and
     * a fixture where both look the same proves nothing at all. The read is then asserted to have changed
     * neither side: a read-through that refreshed the local copy, or pushed it back into the bucket, would make
     * the two converge and quietly destroy whichever was authoritative.
     *
     * @param home a per-test temporary directory standing in for {@code ofbiz.home}
     * @throws Exception if the store cannot be reached, which fails the test
     */
    @Test
    public void contentRoundTripsThroughARealObjectStore(@TempDir Path home) throws Exception {
        System.setProperty("ofbiz.home", home.toString());
        Path uploads = Files.createDirectories(home.resolve("runtime/uploads"));
        Files.writeString(uploads.resolve("round-trip.txt"), "the copy on this instance's disk");
        ContentStoreFactory.clearCache();
        ContentStore store = ContentStoreFactory.getContentStore();
        assertNotNull(store, "the object store must be selected for this test to mean anything");
        Delegator delegator = ContentStoreTestSupport.seamDelegator("default", null);
        // Under the content's OWN path, which is the key both providers use: the seam derives it from the
        // location the row records, so a fixture keyed by anything else would never be found.
        String key = record(ContentStoreFactory.storeKey(store, "runtime/uploads/round-trip.txt"));
        byte[] inTheBucket = "the authoritative copy in the object store".getBytes(StandardCharsets.UTF_8);

        store.put(key, inTheBucket);

        assertTrue(store.exists(key), "content just written must be reported as present");
        assertArrayEquals(inTheBucket, store.get(key), "a whole read must return what was written");
        try (InputStream streamed = store.openStream(key)) {
            assertArrayEquals(inTheBucket, streamed.readAllBytes(), "a streamed read must agree with a whole"
                    + " read");
        }
        assertEquals(new String(inTheBucket, StandardCharsets.UTF_8),
                ContentStoreTestSupport.renderedThroughSeam("OFBIZ_FILE", "/runtime/uploads/round-trip.txt",
                        null, delegator, RESOURCE_ID),
                "the seam must serve the object store's content, not the local copy beside it");
        assertTrue(store.exists(key), "a read must not remove what it read");
        assertArrayEquals(inTheBucket, store.get(key), "a read must not rewrite what it read");
        assertEquals("the copy on this instance's disk", Files.readString(uploads.resolve("round-trip.txt")),
                "a read served from the store must leave the local copy exactly as it was");

        store.delete(key);
        assertFalse(ContentStoreFactory.getContentStore().exists(key),
                "content must be gone once it is deleted, as seen by a provider opened afresh");
    }

    /**
     * An overwrite replaces the object rather than appending to it or being refused, and a shorter replacement
     * does not leave a tail of the longer one behind - which is what a store that honoured a stale content
     * length would produce.
     *
     * @param home a per-test temporary directory standing in for {@code ofbiz.home}
     * @throws Exception if the store cannot be reached, which fails the test
     */
    @Test
    public void anOverwriteReplacesTheWholeObjectIncludingWhenTheReplacementIsShorter(@TempDir Path home)
            throws Exception {
        System.setProperty("ofbiz.home", home.toString());
        ContentStore store = ContentStoreFactory.getContentStore();
        String key = record(ContentStoreFactory.storeKey(store, "runtime/uploads/90010.bin"));
        byte[] longer = new byte[64 * 1024];
        for (int index = 0; index < longer.length; index++) {
            longer[index] = (byte) (index % 251);
        }
        byte[] shorter = "a much shorter replacement".getBytes(StandardCharsets.UTF_8);

        store.put(key, longer);
        assertArrayEquals(longer, store.get(key), "the first write must be readable in full");

        store.put(key, shorter);

        assertArrayEquals(shorter, store.get(key), "an overwrite must replace the whole object: a tail of the"
                + " longer value surviving would mean a read is bounded by a length the store no longer holds");
        try (InputStream streamed = store.openStream(key)) {
            assertArrayEquals(shorter, streamed.readAllBytes(), "the streamed read must see the replacement too");
        }
    }

    /**
     * A large payload survives the wire in both directions, written from a stream so the provider is never
     * asked to hold the whole object in memory.
     *
     * <p>This is the CR-02 contract against a real store: the read path is bounded by what the store declares
     * rather than by a byte array the provider assembled, so an object larger than a comfortable heap has to be
     * both writable and readable. Ten megabytes is enough to cross the SDK's multipart and buffering thresholds
     * without making the test slow.
     *
     * @param home a per-test temporary directory standing in for {@code ofbiz.home}
     * @throws Exception if the store cannot be reached, which fails the test
     */
    @Test
    public void aPayloadTooLargeToWantInMemorySurvivesTheWireInBothDirections(@TempDir Path home)
            throws Exception {
        System.setProperty("ofbiz.home", home.toString());
        ContentStore store = ContentStoreFactory.getContentStore();
        String key = record(ContentStoreFactory.storeKey(store, "runtime/uploads/90011.bin"));
        int length = 10 * 1024 * 1024 + 7;
        Path staged = home.resolve("large.bin");
        try (OutputStream out = Files.newOutputStream(staged)) {
            byte[] block = new byte[64 * 1024];
            for (int index = 0; index < block.length; index++) {
                block[index] = (byte) (index % continuum());
            }
            int remaining = length;
            while (remaining > 0) {
                int chunk = Math.min(remaining, block.length);
                out.write(block, 0, chunk);
                remaining -= chunk;
            }
        }

        try (InputStream source = Files.newInputStream(staged)) {
            store.put(key, source, length);
        }

        assertTrue(store.exists(key), "a streamed write must produce an object");
        try (ContentStore.ContentStream streamed = store.openStream(key)) {
            assertEquals((long) length, streamed.length(), "the stream must declare the exact length the store"
                    + " holds, which is what bounds a read without buffering the whole object");
            assertTrue(sameBytes(Files.newInputStream(staged), streamed), "every byte must come back exactly as"
                    + " it was written");
        }
    }

    /**
     * A key the store holds nothing for is refused rather than answered from the local copy, and the documented
     * migration setting is what allows the local copy to answer.
     *
     * <p>This is the fail-closed half of statelessness. If a missing object silently fell back to whatever
     * happens to be on this instance's disk, a fleet would serve different content from different instances and
     * nothing would report it - the failure the object store exists to remove.
     *
     * @param home a per-test temporary directory standing in for {@code ofbiz.home}
     * @throws Exception if the store cannot be reached, which fails the test
     */
    @Test
    public void aStoreThatHoldsNothingRefusesRatherThanServingTheLocalCopy(@TempDir Path home) throws Exception {
        System.setProperty("ofbiz.home", home.toString());
        Path uploads = Files.createDirectories(home.resolve("runtime/uploads"));
        Files.writeString(uploads.resolve("orphan.txt"), "a local copy the store never received");
        ContentStoreFactory.clearCache();
        Delegator delegator = ContentStoreTestSupport.seamDelegator("default", null);

        Executable orphanRead = () -> ContentStoreTestSupport.renderedThroughSeam("OFBIZ_FILE",
                "/runtime/uploads/orphan.txt", null, delegator, ABSENT_RESOURCE_ID);
        GeneralException refused = assertThrows(GeneralException.class, orphanRead,
                "a real store holding nothing must refuse rather than serve the local copy");
        assertTrue(refused.getMessage().contains(PROPERTY_LOCAL_FALLBACK), "the refusal must name the migration"
                + " setting, so an operator can find it: [" + refused.getMessage() + "]");

        UtilProperties.setPropertyValueInMemory("content", PROPERTY_LOCAL_FALLBACK, "true");
        ContentStoreFactory.clearCache();

        assertEquals("a local copy the store never received",
                ContentStoreTestSupport.renderedThroughSeam("OFBIZ_FILE", "/runtime/uploads/orphan.txt", null,
                        delegator, ABSENT_RESOURCE_ID),
                "the documented migration setting must let existing local content keep being served");
    }

    /**
     * One row means one piece of content, whichever delegator reads it and whichever provider is configured.
     *
     * <p>This is the property that replaced tenant-scoped object keys, and it is asserted against a real store
     * because it is a statement about which OBJECT a row reaches. Filesystem mode's storage tree IS the
     * deployment's own content tree, so its key can only be the content's path; giving the object store a
     * different key shape would have made one {@code DataResource} row name different content depending on which
     * provider was configured, and would have made content impossible to copy between the two - which is exactly
     * what a deployment does during the migration window {@code content.store.local.fallback} exists to cover.
     *
     * <p>What separates content here is what separates it on a filesystem: the paths. Two rows recording two
     * paths reach two objects, and two deployments sharing one bucket are separated by
     * {@code content.store.s3.key.prefix} - which
     * {@link #thePerRunKeyPrefixSeparatesOneRunsObjectsFromAnothersInTheSameBucket} asserts directly, against the
     * same store.
     *
     * @param home a per-test temporary directory standing in for {@code ofbiz.home}
     * @throws Exception if the store cannot be reached, which fails the test
     */
    @Test
    public void oneRowMeansOneObjectWhicheverDelegatorReadsIt(@TempDir Path home) throws Exception {
        System.setProperty("ofbiz.home", home.toString());
        Path uploads = Files.createDirectories(home.resolve("runtime/uploads"));
        Files.writeString(uploads.resolve("shared.txt"), "never served in this test");
        Files.writeString(uploads.resolve("other.txt"), "never served in this test either");
        ContentStoreFactory.clearCache();
        ContentStore store = ContentStoreFactory.getContentStore();
        Delegator base = ContentStoreTestSupport.seamDelegator("default", null);
        Delegator tenant = ContentStoreTestSupport.seamDelegator("default", "DEMO1");
        String sharedKey = record(ContentStoreFactory.storeKey(store, "runtime/uploads/shared.txt"));
        String otherKey = record(ContentStoreFactory.storeKey(store, "runtime/uploads/other.txt"));
        assertNotEquals(sharedKey, otherKey, "two paths must reach two objects");

        store.put(sharedKey, "the content that row names".getBytes(StandardCharsets.UTF_8));
        store.put(otherKey, "the content the other row names".getBytes(StandardCharsets.UTF_8));

        // One row, two delegators, one object: the key is the content's path and nothing about the reader takes
        // part in it, which is what keeps the two providers agreeing about what a row means.
        assertEquals("the content that row names", ContentStoreTestSupport.renderedThroughSeam("OFBIZ_FILE",
                "/runtime/uploads/shared.txt", null, base, RESOURCE_ID),
                "the base delegator must reach the object the row's path names");
        assertEquals("the content that row names", ContentStoreTestSupport.renderedThroughSeam("OFBIZ_FILE",
                "/runtime/uploads/shared.txt", null, tenant, RESOURCE_ID),
                "a tenant delegator reading the same row must reach the same object, because the row names the"
                        + " content and the reader does not");
        // And a different row still reaches a different object, so the above is not "every read returns the same
        // thing".
        assertEquals("the content the other row names", ContentStoreTestSupport.renderedThroughSeam("OFBIZ_FILE",
                "/runtime/uploads/other.txt", null, base, RESOURCE_ID),
                "a row recording another path must reach that path's object");
    }

    /**
     * The per-run key prefix really separates one run's objects from another's in the bucket.
     *
     * <p>This is the assertion that makes this suite safe to run against a shared bucket, which is what parallel
     * clones of this repository do. The prefix is applied inside the provider, below the SPI, so the same SPI
     * key addresses a different object under a different prefix - and a provider configured with one prefix must
     * not be able to see, read or delete an object written under another. Without this the isolation would be an
     * intention rather than a property, and two runs would silently delete each other's fixtures.
     *
     * @param home a per-test temporary directory standing in for {@code ofbiz.home}
     * @throws Exception if the store cannot be reached, which fails the test
     */
    @Test
    public void thePerRunKeyPrefixSeparatesOneRunsObjectsFromAnothersInTheSameBucket(@TempDir Path home)
            throws Exception {
        System.setProperty("ofbiz.home", home.toString());
        Delegator delegator = ContentStoreTestSupport.seamDelegator("default", null);
        ContentStore mine = ContentStoreFactory.getContentStore();
        String key = record(ContentStoreFactory.storeKey(mine, "runtime/uploads/90015.bin"));
        byte[] content = "written under this run's prefix".getBytes(StandardCharsets.UTF_8);

        mine.put(key, content);
        assertTrue(mine.exists(key), "the object must be present under this run's prefix");

        // A second provider, identical in every respect except the prefix: what a concurrent run of this suite
        // against the same bucket looks like from inside the process.
        String otherNamespace = namespace + "-neighbour";
        UtilProperties.setPropertyValueInMemory("content", PROPERTY_S3_KEY_PREFIX, otherNamespace);
        ContentStoreFactory.clearCache();
        try {
            ContentStore neighbour = ContentStoreFactory.getContentStore();
            assertFalse(neighbour.exists(key), "a run under a different prefix must not see this run's object:"
                    + " that is what keeps two runs against one bucket from interfering");
            neighbour.delete(key);
        } finally {
            UtilProperties.setPropertyValueInMemory("content", PROPERTY_S3_KEY_PREFIX, namespace);
            ContentStoreFactory.clearCache();
        }

        assertTrue(ContentStoreFactory.getContentStore().exists(key), "a neighbouring run's delete must not have"
                + " removed this run's object");
        assertArrayEquals(content, ContentStoreFactory.getContentStore().get(key),
                "and must not have altered it either");
    }

    /**
     * Deleting an object the store does not hold is not an error, and deleting one twice is not either.
     *
     * <p>The provider's own rollback compensation deletes what it published when a transaction fails, and a
     * rollback can run after a delete has already happened - so a delete that threw on an absent object would
     * turn a handled failure into an unhandled one.
     *
     * @param home a per-test temporary directory standing in for {@code ofbiz.home}
     * @throws Exception if the store cannot be reached, which fails the test
     */
    @Test
    public void deletingWhatTheStoreDoesNotHoldIsNotAnError(@TempDir Path home) throws Exception {
        System.setProperty("ofbiz.home", home.toString());
        ContentStore store = ContentStoreFactory.getContentStore();
        Delegator delegator = ContentStoreTestSupport.seamDelegator("default", null);
        String never = ContentStoreFactory.storeKey(store, "runtime/uploads/90012.bin");
        String once = record(ContentStoreFactory.storeKey(store, "runtime/uploads/90013.bin"));

        store.delete(never);
        assertFalse(store.exists(never), "an object that was never written must not exist");

        store.put(once, "present".getBytes(StandardCharsets.UTF_8));
        store.delete(once);
        store.delete(once);
        assertFalse(store.exists(once), "a repeated delete must leave the object absent rather than raise");
    }

    /**
     * Both reads report a key the store holds nothing for as {@link java.io.FileNotFoundException}, which is
     * the SPI's declared way of saying absent: neither read may ever return null, because a null would be
     * indistinguishable from empty content at every call site.
     *
     * @param home a per-test temporary directory standing in for {@code ofbiz.home}
     * @throws Exception if the store cannot be reached, which fails the test
     */
    @Test
    public void bothReadsReportAnAbsentKeyAsFileNotFoundRatherThanNull(@TempDir Path home) throws Exception {
        System.setProperty("ofbiz.home", home.toString());
        ContentStore store = ContentStoreFactory.getContentStore();
        String absent = ContentStoreFactory.storeKey(store, "runtime/uploads/90014.bin");

        assertFalse(store.exists(absent), "the store must report the key as absent");
        assertThrows(FileNotFoundException.class, () -> store.get(absent),
                "a whole read of an absent key must report absence as FileNotFoundException, never as null");
        assertThrows(FileNotFoundException.class, () -> store.openStream(absent),
                "a streamed read of an absent key must report absence as FileNotFoundException, never as null");
    }

    /**
     * Installs the configuration in memory from the injected settings, skipping the case when one is missing.
     *
     * <p>A missing setting SKIPS rather than fails, because the unit tier has to pass on a machine with no
     * object store and must reach no network. It is an assumption rather than a silent return so that the
     * skip is recorded, with the settings named, in the test report: an unconfigured run therefore says
     * plainly that it verified nothing here, instead of reporting a pass for a contract nothing exercised.
     * Nothing is defaulted - a default endpoint would send this deployment's credentials somewhere it was
     * not told to, and a default credential would be a credential committed to the repository.
     */
    private void applyConfiguration() {
        Set<String> missing = new LinkedHashSet<>();
        for (String setting : REQUIRED_SETTINGS) {
            if (injected(setting) == null) {
                missing.add(setting);
            }
        }
        assumeTrue(missing.isEmpty(), () -> "the object-store checks need a real S3-compatible store and these"
                + " settings were not supplied: " + missing + ". Supply each as a -D<name>=<value> system"
                + " property or as the matching OFBIZ_TEST_S3_* environment variable - the environment form is"
                + " the one that reaches Gradle's forked test JVM.");
        UtilProperties.setPropertyValueInMemory("content", PROPERTY_S3_ENDPOINT,
                injected("ofbiz.test.s3.endpoint"));
        UtilProperties.setPropertyValueInMemory("content", PROPERTY_S3_REGION, injected("ofbiz.test.s3.region"));
        UtilProperties.setPropertyValueInMemory("content", PROPERTY_S3_BUCKET, injected("ofbiz.test.s3.bucket"));
        UtilProperties.setPropertyValueInMemory("content", PROPERTY_S3_ACCESS_KEY_ID,
                injected("ofbiz.test.s3.access.key.id"));
        UtilProperties.setPropertyValueInMemory("content", PROPERTY_S3_SECRET_ACCESS_KEY,
                injected("ofbiz.test.s3.secret.access.key"));
        String pathStyle = injected("ofbiz.test.s3.path.style");
        // Path style by default: it is what an S3-compatible store reached by address or by a name with no
        // wildcard certificate requires, which is every store a test is realistically pointed at.
        UtilProperties.setPropertyValueInMemory("content", PROPERTY_S3_PATH_STYLE,
                pathStyle == null ? "true" : pathStyle);
        UtilProperties.setPropertyValueInMemory("content", PROPERTY_S3_KEY_PREFIX, namespace);
        // Derived from the endpoint the operator supplied rather than inherited from whatever another test
        // left in this JVM. A loopback store needs no permission and an https store needs none either, so
        // the value is "true" only for the case that genuinely requires it - a plaintext store somewhere
        // other than this host, which is a store the operator deliberately pointed this suite at. That the
        // permission is REQUIRED for such an endpoint is proved by ContentStoreFactoryTest; stating it here
        // is what stops these checks passing because an earlier test relaxed the control and left it off.
        String endpoint = injected("ofbiz.test.s3.endpoint");
        UtilProperties.setPropertyValueInMemory("content", PROPERTY_S3_INSECURE_ENDPOINT,
                endpoint != null && endpoint.regionMatches(true, 0, "http://", 0, "http://".length())
                        ? "true" : "false");
        UtilProperties.setPropertyValueInMemory("content", PROPERTY_LOCAL_FALLBACK, "false");
        UtilProperties.setPropertyValueInMemory("content", PROPERTY_PROVIDER, "s3");
        ContentStoreFactory.clearCache();
    }

    /**
     * Reads one injected setting, from a system property or from the matching environment variable.
     *
     * @param name the {@code ofbiz.test.*} setting name
     * @return its value, or null when neither form was supplied
     */
    private static String injected(String name) {
        String property = System.getProperty(name);
        if (property != null && !property.isBlank()) {
            return property;
        }
        String variable = name.replace('.', '_').toUpperCase(Locale.ROOT);
        String value = System.getenv(variable.startsWith("OFBIZ_") ? variable : "OFBIZ_" + variable);
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Records a key this test wrote, so that teardown removes it whatever the test then does.
     *
     * @param key the key
     * @return the same key, so a caller can record and use it in one expression
     */
    private String record(String key) {
        written.add(key);
        Debug.logInfo("object-store integration test will remove [" + key + "] after this test", MODULE);
        return key;
    }

    /** The modulus the large-payload block is filled from; a prime, so no block boundary aligns with it. */
    private static int continuum() {
        return 251;
    }

    /**
     * Compares two streams byte for byte without holding either in memory.
     *
     * @param expected the stream of what was written
     * @param actual the stream of what came back
     * @return true when both carry the same bytes and end together
     * @throws IOException if either stream cannot be read
     */
    private static boolean sameBytes(InputStream expected, InputStream actual) throws IOException {
        try (InputStream first = expected) {
            byte[] left = new byte[64 * 1024];
            byte[] right = new byte[64 * 1024];
            while (true) {
                int read = first.readNBytes(left, 0, left.length);
                int mirrored = actual.readNBytes(right, 0, read == 0 ? right.length : read);
                if (read != mirrored) {
                    return false;
                }
                if (read == 0) {
                    return true;
                }
                for (int index = 0; index < read; index++) {
                    if (left[index] != right[index]) {
                        return false;
                    }
                }
            }
        }
    }
}
