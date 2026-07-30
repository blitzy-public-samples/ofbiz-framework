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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.apache.ofbiz.base.util.GeneralException;
import org.junit.jupiter.api.Test;

/**
 * Executes the whole {@link ContentStoreBehaviourContract} against the PRODUCTION {@link S3ContentStore}, with
 * the AWS SDK client boundary replaced by {@link InMemoryS3Client}.
 *
 * <p>Running the identical contract against the object-storage provider is what makes provider parity
 * structural rather than aspirational: the filesystem and object-storage backends have almost nothing in common
 * mechanically - one has directories and the other a flat key space, one signals absence with a platform error
 * and the other with a modelled service exception - yet {@code DataResourceWorker} has to be able to use either
 * without knowing which. Every awkward case the contract pins is therefore asserted here too, against the same
 * production code a deployment runs.
 *
 * <p>The SDK boundary is replaced rather than the provider being mocked, so the provider's own logic - the
 * declared-length streaming write, the {@code HeadObject}-then-read sequence that refuses an over-large object
 * before transferring it, the mapping of an absent object onto {@link java.io.FileNotFoundException}, the
 * idempotent removal and the client lifecycle - is the code actually under test. No credential is resolved, no
 * endpoint is contacted and no network is touched.
 *
 * <p>Object keys are unconstrained, so no key mapping is needed and {@link #storageKey(String)} is left at its
 * identity default - which is itself worth stating, because it is the difference between this provider and the
 * filesystem one.
 */
public final class S3ContentStoreBehaviourTests extends ContentStoreBehaviourContract {

    private static final String BUCKET = "ofbiz-content";
    private static final byte[] PAYLOAD = "stored in an object store".getBytes(StandardCharsets.UTF_8);

    /** The fake behind the provider most recently handed out, so a test can inspect what reached the client. */
    private InMemoryS3Client store;

    @Override
    protected ContentStore newEmptyContentStore() {
        store = new InMemoryS3Client(BUCKET);
        return new S3ContentStore(store, BUCKET);
    }

    @Test
    public void aWholeContentWriteIsStreamedIntoASingleRequestRatherThanBufferedAgain() throws Exception {
        ContentStore provider = newEmptyContentStore();

        provider.put("uploads/party/logo.png", PAYLOAD);

        // One PutObject, with the length declared up front: that is what lets content of any size cross the
        // boundary in a single request without the provider or the SDK holding a second copy of it.
        assertEquals(1, store.putCalls(), "the convenience form must issue exactly one PutObject");
        assertEquals(PAYLOAD.length, provider.size("uploads/party/logo.png"), "the stored length must match what was declared");
        assertArrayEquals(PAYLOAD, provider.get("uploads/party/logo.png"), "the stored bytes");
    }

    @Test
    public void aReadIsAnsweredFromMetadataAndThenExactlyOneGetObject() throws Exception {
        ContentStore provider = newEmptyContentStore();
        String key = "uploads/party/statement.pdf";
        provider.put(key, new ByteArrayInputStream(PAYLOAD), PAYLOAD.length);
        int getCallsBefore = store.getCalls();
        int headCallsBefore = store.headObjectCalls();

        try (InputStream streamed = provider.openStream(key)) {
            assertArrayEquals(PAYLOAD, streamed.readAllBytes(), "the streamed content");
        }
        assertEquals(getCallsBefore + 1, store.getCalls(), "openStream must issue exactly one GetObject");

        assertEquals(PAYLOAD.length, provider.size(key), "size must be answered from metadata");
        assertEquals(headCallsBefore + 1, store.headObjectCalls(), "size must issue exactly one HeadObject, not a read");
    }

    @Test
    public void anObjectLargerThanTheInMemoryCeilingIsRefusedWithoutItsContentBeingTransferred() throws Exception {
        ContentStore provider = newEmptyContentStore();
        String key = "uploads/party/far-too-large.bin";
        provider.put(key, new ByteArrayInputStream(PAYLOAD), PAYLOAD.length);
        // The store now reports a length no instance could hold in its heap. Reporting it rather than storing it
        // is what makes this assertable at all: an object of that size could not be allocated in a unit test, and
        // the point of the metadata-first sequence is precisely that it never has to be.
        store.reportingLengthAs(4L * 1024L * 1024L * 1024L);
        int getCallsBefore = store.getCalls();

        assertThrows(GeneralException.class, () -> provider.get(key), "an object beyond the in-memory ceiling must be refused");

        // Refused from the metadata alone: had the provider read first and measured afterwards, the instance would
        // already have been carrying four gigabytes by the time it decided to refuse.
        assertEquals(getCallsBefore, store.getCalls(), "a refused read must not have transferred any content");
        // ...while the same object is still perfectly streamable, which is the whole point of the ceiling being on
        // the convenience form only.
        try (InputStream streamed = provider.openStream(key)) {
            assertArrayEquals(PAYLOAD, streamed.readAllBytes(), "the object must still be readable through openStream");
        }
    }

    @Test
    public void aClosedProviderClosesItsClientAndRefusesEveryLaterOperation() throws Exception {
        ContentStore provider = newEmptyContentStore();
        provider.put("uploads/party/logo.png", PAYLOAD);

        provider.close();

        // The client owns an HTTP connection pool, so a provider that is replaced or shut down without closing it
        // leaks that pool for the lifetime of the JVM.
        assertTrue(store.isClosed(), "closing the provider must close the client it owns");
        // And a closed provider must refuse rather than silently rebuild a client the deployment has finished with.
        assertThrows(GeneralException.class, () -> provider.get("uploads/party/logo.png"),
                "a closed provider must refuse a later operation");
        provider.close();
    }
}
