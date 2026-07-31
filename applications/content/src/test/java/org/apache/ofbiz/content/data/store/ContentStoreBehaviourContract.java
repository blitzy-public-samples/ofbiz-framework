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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.ofbiz.base.util.GeneralException;
import org.junit.jupiter.api.Test;

/**
 * Reusable behaviour contract every {@link ContentStore} provider must satisfy.
 *
 * <p>An SPI is only worth having if its providers are interchangeable, and they are interchangeable only if
 * they agree on the awkward cases: what an absent key does, whether a second {@code put} replaces or appends,
 * whether an empty payload is storable, whether two readers share a stream, whether deleting nothing is an
 * error, how many bytes a known-length streaming write consumes, what a stream that ends early leaves behind,
 * and whether closing twice is safe. Those are exactly the places where independently written providers drift apart, and where
 * {@code DataResourceWorker} - whose existing callers dereference a returned handle with no null check - would
 * break in a way no compiler catches.
 *
 * <p>This class is therefore written once, against the SPI alone, and is meant to be EXTENDED rather than run
 * directly: a subclass supplies a provider from {@link #newEmptyContentStore()} and inherits the whole suite.
 * Both shipped providers do exactly that - {@link FileSystemContentStoreTests} against
 * {@link FileSystemContentStore} under a temporary {@code ofbiz.home}, and {@link S3ContentStoreTests} against
 * {@link S3ContentStore} with only its SDK client boundary replaced - so provider parity is enforced by
 * construction rather than by re-describing the same expectations in two places. A third subclass,
 * {@link ContentStoreContractSatisfiabilityTests}, runs the same suite against an implementation written from
 * this javadoc alone; it exists to show the contract below is satisfiable and is explicitly NOT provider
 * coverage.
 *
 * <p>The two provider-specific behaviour suites, {@link FileSystemContentStoreBehaviourTests} and
 * {@link S3ContentStoreBehaviourTests}, and the filesystem confinement suite,
 * {@link FileSystemContentStoreConfinementTests}, extend it as well, so each provider is held to this one
 * contract from every angle it is examined from rather than from only one.
 *
 * <p>Deliberately, no {@code ContentStore} MOCK is ever asserted against here. A mock returns whatever it was
 * told to return, so asserting on it would only restate the stubbing; every expectation below is verified
 * against a provider that actually stores and retrieves bytes.
 *
 * <p>Each test obtains its own empty store, so tests share no state and can run in any order.
 */
public abstract class ContentStoreBehaviourContract {

    private static final byte[] PAYLOAD = "the quick brown fox".getBytes(StandardCharsets.UTF_8);
    private static final byte[] REPLACEMENT = "jumps".getBytes(StandardCharsets.UTF_8);

    /** The logical key this suite writes to, before {@link #keyPrefix()} and {@link #storageKey(String)}. */
    private static final String KEY = "contract/sample.bin";

    /** A second, distinct logical key, used to show that a write under one key does not disturb another. */
    private static final String OTHER_KEY = "contract/other.bin";

    /** A well-formed logical key nothing is ever written to. */
    private static final String ABSENT_KEY = "contract/nothing-was-ever-stored-here.bin";

    /**
     * Supplies a brand new, EMPTY provider for a single test.
     *
     * <p>Implementations must return a provider that performs real storage work - a genuine backing store, or a
     * real provider whose client library boundary is mocked - never a mock of {@link ContentStore} itself.
     *
     * @return an empty provider, exclusive to the calling test
     * @throws Exception if the backing store cannot be prepared
     */
    protected abstract ContentStore newEmptyContentStore() throws Exception;

    /**
     * The prefix every key in this suite is composed with, so that a provider which confines what it may
     * MODIFY to one dedicated root can still be driven by the shared contract.
     *
     * <p>A key means the same thing to a provider whatever it is prefixed with - the SPI treats it as an
     * opaque address - so prefixing changes nothing the contract asserts. What it does is let
     * {@link FileSystemContentStore}, whose writes and removals are confined to the
     * {@code content.upload.path.prefix} tree, be exercised with keys that legitimately lie inside that tree,
     * while {@link S3ContentStore} - for which the bucket is the root, so no key can escape - keeps using
     * bare keys. Deliberately a hook rather than a hard-coded prefix in the contract: the contract must not
     * know which provider it is running against, and a provider that confines nothing must not be handed a
     * prefix that hides a bug.
     *
     * <p>Composed with {@link #storageKey(String)} rather than as an alternative to it: this hook says WHERE
     * inside the provider's own key space the contract may write, that one says how a logical key is
     * ADDRESSED. A subclass overrides whichever of the two describes its provider and leaves the other at its
     * default, so the two never have to agree on anything.
     *
     * @return the prefix, ending in a separator when it is not empty; never null
     */
    protected String keyPrefix() {
        return "";
    }

    /**
     * Maps one of this contract's logical keys onto the key the provider under test is addressed with.
     *
     * <p>The default is the identity, and a provider whose key space is unconstrained - an object store, for
     * instance - needs nothing else. It exists for the providers whose key space is NOT unconstrained: the
     * production filesystem provider only accepts keys resolving inside the deployment's allowed subtrees, so
     * without this hook the only way to run the contract against it would be to weaken that allow list, which
     * would mean the contract was no longer exercising the provider as it is actually deployed.
     *
     * <p>Only the content-addressing keys pass through here. The unusable-input expectations deliberately do
     * not, because a null or empty key must be refused as supplied rather than after being decorated.
     *
     * @param logicalKey this contract's own key, already carrying {@link #keyPrefix()}
     * @return the key the provider under test is addressed with
     */
    protected String storageKey(String logicalKey) {
        return logicalKey;
    }

    /** @return the primary content key, as this provider is addressed with it */
    private String key() {
        return storageKey(keyPrefix() + KEY);
    }

    /** @return a second, unrelated content key, as this provider is addressed with it */
    private String otherKey() {
        return storageKey(keyPrefix() + OTHER_KEY);
    }

    /** @return a well-formed key nothing has ever been stored under, as this provider is addressed with it */
    private String absentKey() {
        return storageKey(keyPrefix() + ABSENT_KEY);
    }

    /*
     * Writing
     */

    @Test
    public final void putStoresExactlyTheSuppliedBytesAndGetReturnsThem() throws Exception {
        ContentStore store = newEmptyContentStore();

        store.put(key(), PAYLOAD);

        assertArrayEquals(PAYLOAD, store.get(key()), "get must return exactly the bytes that were put");
    }

    @Test
    public final void putReplacesExistingContentInFullRatherThanAppending() throws Exception {
        ContentStore store = newEmptyContentStore();
        store.put(key(), PAYLOAD);

        store.put(key(), REPLACEMENT);

        // A provider that appended, or that left a tail of the longer previous value behind, would corrupt every
        // re-upload of an existing DataResource. REPLACEMENT is deliberately shorter than PAYLOAD.
        assertArrayEquals(REPLACEMENT, store.get(key()), "a second put must replace the content in full");
    }

    @Test
    public final void aZeroLengthPayloadIsStorableAndReadableAsEmptyContent() throws Exception {
        ContentStore store = newEmptyContentStore();

        store.put(key(), new byte[0]);

        // Empty content must be a stored, EXISTING entry - not an absent one. Collapsing the two would make an
        // empty upload indistinguishable from a failed one.
        assertTrue(store.exists(key()), "empty content must still exist");
        assertArrayEquals(new byte[0], store.get(key()), "get must return an empty array, never null");
        try (InputStream stream = store.openStream(key())) {
            assertEquals(-1, stream.read(), "the stream over empty content must be immediately at end of stream");
        }
    }

    @Test
    public final void writesUnderDistinctKeysDoNotInterfere() throws Exception {
        ContentStore store = newEmptyContentStore();

        store.put(key(), PAYLOAD);
        store.put(otherKey(), REPLACEMENT);

        assertArrayEquals(PAYLOAD, store.get(key()), key() + " must be unaffected by a write to " + otherKey());
        assertArrayEquals(REPLACEMENT, store.get(otherKey()), otherKey() + " must hold its own content");
    }

    @Test
    public final void theStreamingPutStoresExactlyTheDeclaredNumberOfBytes() throws Exception {
        ContentStore store = newEmptyContentStore();

        store.put(key(), new ByteArrayInputStream(PAYLOAD), PAYLOAD.length);

        // The streaming form is the only one large content may use, so it has to be byte-for-byte equivalent to
        // the convenience form rather than a lossy shortcut.
        assertArrayEquals(PAYLOAD, store.get(key()), "the streaming put must store exactly the streamed bytes");
        assertEquals(PAYLOAD.length, store.size(key()), "the stored length after a streaming put");
    }

    @Test
    public final void theStreamingPutConsumesTheDeclaredLengthAndNoMoreOfTheStream() throws Exception {
        ContentStore store = newEmptyContentStore();
        byte[] combined = new byte[PAYLOAD.length + REPLACEMENT.length];
        System.arraycopy(PAYLOAD, 0, combined, 0, PAYLOAD.length);
        System.arraycopy(REPLACEMENT, 0, combined, PAYLOAD.length, REPLACEMENT.length);
        InputStream stream = new ByteArrayInputStream(combined);

        store.put(key(), stream, PAYLOAD.length);

        // Callers hand over a stream they still own - a multipart upload body, for instance - so a provider that
        // drained it past the declared length would consume the next part as if it were content.
        assertArrayEquals(PAYLOAD, store.get(key()), "only the declared prefix may be stored");
        assertArrayEquals(REPLACEMENT, stream.readAllBytes(), "the remainder of the caller's stream must be untouched");
    }

    @Test
    public final void theStreamingPutReplacesExistingContentInFullRatherThanAppending() throws Exception {
        ContentStore store = newEmptyContentStore();
        store.put(key(), PAYLOAD);

        store.put(key(), new ByteArrayInputStream(REPLACEMENT), REPLACEMENT.length);

        // REPLACEMENT is deliberately shorter than PAYLOAD, so a tail left behind would be visible here.
        assertArrayEquals(REPLACEMENT, store.get(key()), "a streaming put must replace the content in full");
        assertEquals(REPLACEMENT.length, store.size(key()), "the stored length must shrink to the replacement length");
    }

    @Test
    public final void aZeroLengthStreamingPutIsStorableAsEmptyContent() throws Exception {
        ContentStore store = newEmptyContentStore();

        store.put(key(), new ByteArrayInputStream(new byte[0]), 0L);

        assertTrue(store.exists(key()), "empty streamed content must still exist");
        assertEquals(0L, store.size(key()), "empty streamed content must measure zero, not be absent");
        assertArrayEquals(new byte[0], store.get(key()), "get must return an empty array, never null");
    }

    @Test
    public final void theStreamingPutRefusesToStoreAShortEntryWhenTheStreamEndsEarly() throws Exception {
        ContentStore store = newEmptyContentStore();

        // A truncated upload must fail loudly. Storing what arrived would leave a silently corrupt DataResource
        // that reads back cleanly and is indistinguishable from intact content.
        assertThrows(IOException.class, () -> store.put(key(), new ByteArrayInputStream(REPLACEMENT), PAYLOAD.length),
                "a stream that ends before the declared length must fail rather than store a short entry");
        assertFalse(store.exists(key()), "a failed streaming put must leave no entry behind");
    }

    @Test
    public final void theStreamingPutRejectsANullStreamAndANegativeLength() throws Exception {
        ContentStore store = newEmptyContentStore();

        assertThrows(GeneralException.class, () -> store.put(key(), null, 0L), "a null stream is unusable input");
        assertThrows(GeneralException.class, () -> store.put(key(), new ByteArrayInputStream(PAYLOAD), -1L),
                "a negative declared length is unusable input");
        assertFalse(store.exists(key()), "a rejected streaming put must store nothing");
    }

    @Test
    public final void nestedKeyStructureIsCreatedOnDemand() throws Exception {
        ContentStore store = newEmptyContentStore();
        String deepKey = storageKey(keyPrefix() + "contract/deeply/nested/on/demand.bin");

        // Callers never prepare the store first, so any intermediate structure a provider needs is its own
        // responsibility. The filesystem provider creates directories; the S3 provider needs nothing.
        store.put(deepKey, PAYLOAD);

        assertTrue(store.exists(deepKey), "a nested key must be usable without the caller preparing the store");
        assertArrayEquals(PAYLOAD, store.get(deepKey), "content under a nested key");
    }

    /*
     * Reading, and the absent key
     */

    @Test
    public final void getThrowsFileNotFoundExceptionForAnAbsentKeyInsteadOfReturningNull() throws Exception {
        ContentStore store = newEmptyContentStore();

        // The existing DataResourceWorker call sites dereference the returned handle with no null check, so a
        // null here would introduce a new NullPointerException into a path that never had one.
        assertThrows(FileNotFoundException.class, () -> store.get(absentKey()),
                "an absent key must raise FileNotFoundException from get");
    }

    @Test
    public final void openStreamThrowsFileNotFoundExceptionForAnAbsentKeyInsteadOfReturningNull() throws Exception {
        ContentStore store = newEmptyContentStore();

        assertThrows(FileNotFoundException.class, () -> store.openStream(absentKey()),
                "an absent key must raise FileNotFoundException from openStream");
    }

    @Test
    public final void openStreamHandsOutAnIndependentStreamEveryTime() throws Exception {
        ContentStore store = newEmptyContentStore();
        store.put(key(), PAYLOAD);

        try (InputStream first = store.openStream(key()); InputStream second = store.openStream(key())) {
            assertNotSame(first, second, "each openStream must return its own stream, never a shared one");
            // Draining the first must leave the second positioned at the very first byte: a provider that cached
            // and handed back one stream would serve a truncated second read to a concurrent request thread.
            assertArrayEquals(PAYLOAD, first.readAllBytes(), "the first stream must yield the whole content");
            assertArrayEquals(PAYLOAD, second.readAllBytes(), "the second stream must be positioned at the start, independently");
        }
    }

    @Test
    public final void existsDistinguishesStoredContentFromAnAbsentKeyWithoutThrowing() throws Exception {
        ContentStore store = newEmptyContentStore();

        // exists() is the non-exceptional probe: a well-formed key with nothing stored is false, not an error.
        assertFalse(store.exists(absentKey()), "a well-formed key with nothing stored must simply be absent");
        store.put(key(), PAYLOAD);
        assertTrue(store.exists(key()), "stored content must be reported as present");
    }

    /*
     * Measuring
     */

    @Test
    public final void sizeReportsTheStoredByteCountWithoutReadingTheContent() throws Exception {
        ContentStore store = newEmptyContentStore();
        store.put(key(), PAYLOAD);
        store.put(otherKey(), new byte[0]);

        // size() is what lets a caller decide between a bounded in-memory read and a streamed one, so it has to be
        // exact rather than an estimate, and zero has to mean stored-and-empty rather than absent.
        assertEquals(PAYLOAD.length, store.size(key()), "the reported length of stored content");
        assertEquals(0L, store.size(otherKey()), "stored empty content must measure zero");
    }

    @Test
    public final void sizeThrowsFileNotFoundExceptionForAnAbsentKeyInsteadOfReturningASentinel() throws Exception {
        ContentStore store = newEmptyContentStore();

        // A -1 or 0 sentinel would make an absent key look like empty content to every caller that branches on
        // the length, which is exactly the confusion the exception-based absence signal exists to prevent.
        assertThrows(FileNotFoundException.class, () -> store.size(absentKey()),
                "an absent key must raise FileNotFoundException from size");
    }

    /*
     * Deleting
     */

    @Test
    public final void deleteRemovesContentAndIsIdempotent() throws Exception {
        ContentStore store = newEmptyContentStore();
        store.put(key(), PAYLOAD);

        store.delete(key());

        assertFalse(store.exists(key()), "delete must remove the content");
        assertThrows(FileNotFoundException.class, () -> store.get(key()), "a deleted key must behave exactly like an absent one");
        // Idempotent, so replayed or repeated clean-up is safe: neither of these may throw.
        store.delete(key());
        store.delete(absentKey());
    }

    /*
     * Unusable input
     */

    @Test
    public final void everyOperationRejectsANullKey() throws Exception {
        ContentStore store = newEmptyContentStore();

        for (Map.Entry<String, KeyedOperation> operation : keyedOperations(store).entrySet()) {
            assertThrows(GeneralException.class, () -> operation.getValue().accept(null),
                    operation.getKey() + " must reject a null key with GeneralException");
        }
    }

    @Test
    public final void everyOperationRejectsAnEmptyKey() throws Exception {
        ContentStore store = newEmptyContentStore();

        for (Map.Entry<String, KeyedOperation> operation : keyedOperations(store).entrySet()) {
            assertThrows(GeneralException.class, () -> operation.getValue().accept(""),
                    operation.getKey() + " must reject an empty key with GeneralException");
        }
    }

    @Test
    public final void putRejectsNullContent() throws Exception {
        ContentStore store = newEmptyContentStore();

        // An empty array stores empty content; null is simply unusable input and must be reported as such rather
        // than being silently coerced into an empty write.
        assertThrows(GeneralException.class, () -> store.put(key(), null), "put must reject null content with GeneralException");
        assertFalse(store.exists(key()), "a rejected put must store nothing");
    }

    /*
     * Lifecycle
     */

    @Test
    public final void closeIsIdempotentSoRepeatedShutdownIsSafe() throws Exception {
        ContentStore store = newEmptyContentStore();
        store.put(key(), PAYLOAD);

        store.close();

        // A provider is closed both when it is replaced by a re-resolution and again at JVM shutdown, so the
        // second close has to be a no-op rather than an error that masks the real shutdown cause in the log.
        store.close();
    }

    /** Every key-addressed operation, keyed by name, each reduced to a single storage-key argument. */
    private static Map<String, KeyedOperation> keyedOperations(ContentStore store) {
        Map<String, KeyedOperation> operations = new LinkedHashMap<>();
        operations.put("put", key -> store.put(key, PAYLOAD));
        operations.put("put(stream)", key -> store.put(key, new ByteArrayInputStream(PAYLOAD), PAYLOAD.length));
        operations.put("get", store::get);
        operations.put("openStream", store::openStream);
        operations.put("size", store::size);
        operations.put("exists", store::exists);
        operations.put("delete", store::delete);
        return operations;
    }

    /** One {@link ContentStore} operation, reduced to its storage-key argument so all of them can be driven alike. */
    private interface KeyedOperation {
        /**
         * Invokes the operation with the supplied storage key.
         *
         * @param key the storage key to pass through
         * @throws Exception whatever the underlying operation raises
         */
        void accept(String key) throws Exception;
    }
}
