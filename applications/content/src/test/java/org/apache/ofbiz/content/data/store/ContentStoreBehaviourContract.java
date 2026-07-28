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

import java.io.FileNotFoundException;
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
 * whether an empty payload is storable, whether two readers share a stream, and whether deleting nothing is an
 * error. Those are exactly the places where independently written providers drift apart, and where
 * {@code DataResourceWorker} - whose existing callers dereference a returned handle with no null check - would
 * break in a way no compiler catches.
 *
 * <p>This class is therefore written once, against the SPI alone, and is meant to be EXTENDED rather than run
 * directly: a subclass supplies a provider from {@link #newEmptyContentStore()} and inherits the whole suite.
 * {@link TempDirContentStoreBehaviourTests} runs it today against an implementation performing genuine
 * filesystem I/O. When the filesystem and S3 providers land, each gets a subclass of this class - the S3 one
 * with its SDK boundary mocked - and provider parity is then enforced by construction rather than by
 * re-describing the same expectations in two places.
 *
 * <p>Deliberately, no {@code ContentStore} MOCK is ever asserted against here. A mock returns whatever it was
 * told to return, so asserting on it would only restate the stubbing; every expectation below is verified
 * against a provider that actually stores and retrieves bytes.
 *
 * <p>Each test obtains its own empty store, so tests share no state and can run in any order.
 */
public abstract class ContentStoreBehaviourContract {

    private static final String KEY = "contract/sample.bin";
    private static final String OTHER_KEY = "contract/other.bin";
    private static final String ABSENT_KEY = "contract/nothing-was-ever-stored-here.bin";
    private static final byte[] PAYLOAD = "the quick brown fox".getBytes(StandardCharsets.UTF_8);
    private static final byte[] REPLACEMENT = "jumps".getBytes(StandardCharsets.UTF_8);

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

    /*
     * ---------------------------------------------------------------------------------------------
     * Writing
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public final void putStoresExactlyTheSuppliedBytesAndGetReturnsThem() throws Exception {
        ContentStore store = newEmptyContentStore();

        store.put(KEY, PAYLOAD);

        assertArrayEquals(PAYLOAD, store.get(KEY), "get must return exactly the bytes that were put");
    }

    @Test
    public final void putReplacesExistingContentInFullRatherThanAppending() throws Exception {
        ContentStore store = newEmptyContentStore();
        store.put(KEY, PAYLOAD);

        store.put(KEY, REPLACEMENT);

        // A provider that appended, or that left a tail of the longer previous value behind, would corrupt every
        // re-upload of an existing DataResource. REPLACEMENT is deliberately shorter than PAYLOAD.
        assertArrayEquals(REPLACEMENT, store.get(KEY), "a second put must replace the content in full");
    }

    @Test
    public final void aZeroLengthPayloadIsStorableAndReadableAsEmptyContent() throws Exception {
        ContentStore store = newEmptyContentStore();

        store.put(KEY, new byte[0]);

        // Empty content must be a stored, EXISTING entry - not an absent one. Collapsing the two would make an
        // empty upload indistinguishable from a failed one.
        assertTrue(store.exists(KEY), "empty content must still exist");
        assertArrayEquals(new byte[0], store.get(KEY), "get must return an empty array, never null");
        try (InputStream stream = store.openStream(KEY)) {
            assertEquals(-1, stream.read(), "the stream over empty content must be immediately at end of stream");
        }
    }

    @Test
    public final void writesUnderDistinctKeysDoNotInterfere() throws Exception {
        ContentStore store = newEmptyContentStore();

        store.put(KEY, PAYLOAD);
        store.put(OTHER_KEY, REPLACEMENT);

        assertArrayEquals(PAYLOAD, store.get(KEY), KEY + " must be unaffected by a write to " + OTHER_KEY);
        assertArrayEquals(REPLACEMENT, store.get(OTHER_KEY), OTHER_KEY + " must hold its own content");
    }

    @Test
    public final void nestedKeyStructureIsCreatedOnDemand() throws Exception {
        ContentStore store = newEmptyContentStore();
        String deepKey = "contract/deeply/nested/on/demand.bin";

        // Callers never prepare the store first, so any intermediate structure a provider needs is its own
        // responsibility. The filesystem provider creates directories; the S3 provider needs nothing.
        store.put(deepKey, PAYLOAD);

        assertTrue(store.exists(deepKey), "a nested key must be usable without the caller preparing the store");
        assertArrayEquals(PAYLOAD, store.get(deepKey), "content under a nested key");
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Reading, and the absent key
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public final void getThrowsFileNotFoundExceptionForAnAbsentKeyInsteadOfReturningNull() throws Exception {
        ContentStore store = newEmptyContentStore();

        // The existing DataResourceWorker call sites dereference the returned handle with no null check, so a
        // null here would introduce a new NullPointerException into a path that never had one.
        assertThrows(FileNotFoundException.class, () -> store.get(ABSENT_KEY),
                "an absent key must raise FileNotFoundException from get");
    }

    @Test
    public final void openStreamThrowsFileNotFoundExceptionForAnAbsentKeyInsteadOfReturningNull() throws Exception {
        ContentStore store = newEmptyContentStore();

        assertThrows(FileNotFoundException.class, () -> store.openStream(ABSENT_KEY),
                "an absent key must raise FileNotFoundException from openStream");
    }

    @Test
    public final void openStreamHandsOutAnIndependentStreamEveryTime() throws Exception {
        ContentStore store = newEmptyContentStore();
        store.put(KEY, PAYLOAD);

        try (InputStream first = store.openStream(KEY); InputStream second = store.openStream(KEY)) {
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
        assertFalse(store.exists(ABSENT_KEY), "a well-formed key with nothing stored must simply be absent");
        store.put(KEY, PAYLOAD);
        assertTrue(store.exists(KEY), "stored content must be reported as present");
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Deleting
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public final void deleteRemovesContentAndIsIdempotent() throws Exception {
        ContentStore store = newEmptyContentStore();
        store.put(KEY, PAYLOAD);

        store.delete(KEY);

        assertFalse(store.exists(KEY), "delete must remove the content");
        assertThrows(FileNotFoundException.class, () -> store.get(KEY), "a deleted key must behave exactly like an absent one");
        // Idempotent, so replayed or repeated clean-up is safe: neither of these may throw.
        store.delete(KEY);
        store.delete(ABSENT_KEY);
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Unusable input
     * ---------------------------------------------------------------------------------------------
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
        assertThrows(GeneralException.class, () -> store.put(KEY, null), "put must reject null content with GeneralException");
        assertFalse(store.exists(KEY), "a rejected put must store nothing");
    }

    /** All five operations, keyed by name, each reduced to a single storage-key argument. */
    private static Map<String, KeyedOperation> keyedOperations(ContentStore store) {
        Map<String, KeyedOperation> operations = new LinkedHashMap<>();
        operations.put("put", key -> store.put(key, PAYLOAD));
        operations.put("get", store::get);
        operations.put("openStream", store::openStream);
        operations.put("exists", store::exists);
        operations.put("delete", store::delete);
        return operations;
    }

    /** One {@link ContentStore} operation, reduced to its storage-key argument so all five can be driven alike. */
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
