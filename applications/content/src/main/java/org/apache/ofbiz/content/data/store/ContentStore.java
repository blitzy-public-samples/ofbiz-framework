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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.apache.ofbiz.base.util.GeneralException;

/**
 * The storage contract for file-backed {@code DataResource} content - {@code LOCAL_FILE},
 * {@code OFBIZ_FILE} and {@code CONTEXT_FILE} together with their {@code _BIN} variants - with one
 * implementation per storage backend.
 *
 * <p><strong>Selection.</strong> An implementation is chosen at run time by
 * {@link ContentStoreFactory} from the {@code content.store.provider} property of the {@code content}
 * resource. The three accepted values are {@code database} (the shipped default), {@code filesystem}
 * and {@code s3}. Under {@code database} - and under an unset or unrecognised value - the factory
 * returns <em>no provider at all</em>: the pre-existing {@code DataResource} database-storage path runs
 * unchanged and no storage client is constructed.
 *
 * <p><strong>Keys.</strong> A key is an opaque, provider-relative POSIX path. It carries no leading
 * or trailing slash, no empty segment, no {@code .} or {@code ..} segment, no backslash and no
 * control character; a key that breaks that grammar is rejected with a {@link GeneralException}
 * rather than resolved. Choosing which key names which content, and deciding whether a caller may
 * address it, are the caller's responsibility - see
 * {@link ContentStoreFactory#requireUsableKey(String)}.
 *
 * <p><strong>Absence is not failure.</strong> {@link #get} and {@link #openStream} report an object
 * this store does not hold by throwing {@link java.io.FileNotFoundException} - and nothing else
 * throws it. Every other outcome, a provider or network fault included, is an {@link IOException}
 * that is not a {@code FileNotFoundException}, or a {@link GeneralException} for a configuration or
 * key fault. Callers depend on that distinction so that a store which cannot answer is never mistaken
 * for content that is genuinely absent.
 *
 * <p><strong>Streams are the caller's.</strong> {@link #openStream} hands back an open stream that the
 * caller closes; {@link #get} reads a bounded object into memory and closes what it opened.
 *
 * <p><strong>Replacement is atomic.</strong> A {@link #put} that replaces an existing object is
 * atomic as far as a concurrent reader is concerned: the reader sees either the whole previous object
 * or the whole new one, never a mixture of the two. {@link #delete} is idempotent.
 *
 * <p><strong>One size bound, {@link #MAX_OBJECT_BYTES}, governs the whole contract.</strong> It bounds
 * what {@link #get} will read into memory and what a caller may {@link #put}, so an object written
 * through this contract can always be read back through it. Content larger than that is streamed with
 * {@link #openStream} rather than being handled as a byte array.
 *
 * <p><strong>Logging.</strong> Where an implementation logs a storage operation it names the key, and
 * the container holding it where the provider has one, and never the content itself.
 *
 * <p>Implementations are thread safe.
 *
 * @see ContentStoreFactory
 */
public interface ContentStore {

    /**
     * The largest object this contract handles as a byte array, 64 MiB.
     *
     * <p>ONE bound for the whole contract, so that what {@link #put} accepts is exactly what
     * {@link #get} can read back. Larger content is streamed with {@link #openStream}.
     */
    long MAX_OBJECT_BYTES = 64L * 1024L * 1024L;

    /**
     * What a store holds for one key, without transferring the content itself.
     *
     * <p>Answered by {@link ContentStore#describe} from a metadata request - a {@code HeadObject} for an
     * object store, a stat for a filesystem - so a caller can decide whether it needs the bytes at all.
     *
     * @param length the object's size in bytes
     * @param modifiedAt the epoch millisecond the object was last written, or 0 when the store does not
     *     report one
     * @param entityTag the store's own opaque version tag for the object, or null when it reports none.
     *     Compare it only for equality, and only against a tag from the same store.
     */
    record Description(long length, long modifiedAt, String entityTag) { }

    /**
     * Stores the given content under the given key, replacing any object already held there.
     *
     * @param key the storage key
     * @param data the content to store, never null and no larger than {@link #MAX_OBJECT_BYTES}
     * @throws GeneralException if the key breaks the key grammar or the provider is misconfigured
     * @throws IOException if the store cannot be written or the content exceeds
     *     {@link #MAX_OBJECT_BYTES}
     */
    void put(String key, byte[] data) throws GeneralException, IOException;

    /**
     * Stores the content of the given FILE under the given key, replacing any object already held there.
     *
     * <p>The size-bounded write. {@link #put(String, byte[])} requires its caller to hold the whole
     * object in memory first, which for content at the {@link #MAX_OBJECT_BYTES} ceiling is 64 MiB per
     * concurrent write; this overload lets a provider send the bytes from where they already are. The
     * default implementation reads the file and delegates, so a provider that cannot stream still works
     * and behaves identically.
     *
     * @param key the storage key
     * @param file the file whose content to store, no larger than {@link #MAX_OBJECT_BYTES}
     * @throws GeneralException if the key breaks the key grammar or the provider is misconfigured
     * @throws IOException if the file cannot be read, the store cannot be written, or the content
     *     exceeds {@link #MAX_OBJECT_BYTES}
     */
    default void put(String key, Path file) throws GeneralException, IOException {
        put(key, Files.readAllBytes(file));
    }

    /**
     * Returns the whole object held under the given key.
     *
     * <p>The object is read into memory, so this is the bounded accessor: an object larger than
     * {@link #MAX_OBJECT_BYTES} is refused with an {@link IOException} instead of being loaded. Use
     * {@link #openStream} for content of unbounded size.
     *
     * @param key the storage key
     * @return the object's bytes
     * @throws GeneralException if the key breaks the key grammar or the provider is misconfigured
     * @throws java.io.FileNotFoundException if this store does not hold the key
     * @throws IOException if the store cannot be read or the object exceeds {@link #MAX_OBJECT_BYTES}
     */
    byte[] get(String key) throws GeneralException, IOException;

    /**
     * Reports what this store holds under the given key, without transferring the content.
     *
     * <p>The cheap question. A caller that already holds a copy of the content asks this first and
     * transfers nothing when the copy still matches, which is what keeps a read from downloading an
     * object it does not need - and what lets an instance whose filesystem is read-only serve content it
     * already has.
     *
     * @param key the storage key
     * @return what the store holds, or {@link Optional#empty()} when it holds no object under that key
     * @throws GeneralException if the key breaks the key grammar or the provider is misconfigured
     * @throws IOException if the store cannot answer. Absence is NOT a failure and is reported by the
     *     empty result, so a caller can tell a missing object from a store that is unreachable.
     */
    Optional<Description> describe(String key) throws GeneralException, IOException;

    /**
     * Opens the object held under the given key for reading.
     *
     * @param key the storage key
     * @return a stream over the object's bytes, positioned at the start, which the caller closes
     * @throws GeneralException if the key breaks the key grammar or the provider is misconfigured
     * @throws java.io.FileNotFoundException if this store does not hold the key
     * @throws IOException if the store cannot be read
     */
    InputStream openStream(String key) throws GeneralException, IOException;

    /**
     * Reports whether this store holds an object under the given key.
     *
     * @param key the storage key
     * @return true when the object is held
     * @throws GeneralException if the key breaks the key grammar or the provider is misconfigured
     * @throws IOException if the store cannot answer
     */
    boolean exists(String key) throws GeneralException, IOException;

    /**
     * Removes the object held under the given key.
     *
     * <p>Idempotent: removing a key this store does not hold succeeds and does nothing.
     *
     * @param key the storage key
     * @throws GeneralException if the key breaks the key grammar or the provider is misconfigured
     * @throws IOException if the store cannot be written
     */
    void delete(String key) throws GeneralException, IOException;

    /**
     * Confirms that this store can be reached and used right now, for a readiness probe.
     *
     * <p><strong>Why the contract needs this at all.</strong> Every other method here names an object, and
     * a readiness probe has no object to name: it asks whether the instance is fit to receive a request
     * that WOULD name one. Asking with {@link #exists} on a made-up key cannot answer that - a filesystem
     * provider reports a missing key as absent whether its storage root is mounted or gone - so each
     * provider answers the question with the cheapest request that actually distinguishes a store it can
     * use from one it cannot: a bucket-level request for an object store, an inspection of the storage root
     * for a filesystem.
     *
     * <p><strong>The contract.</strong> Returning normally means this instance could serve a content
     * operation now. Throwing means it could not, and the exception message says why in terms an operator
     * can act on - it names the container, root or endpoint at fault and never a credential. Nothing here
     * transfers content, writes anything, or depends on any particular object existing, so it stays cheap
     * enough to run on every probe interval; the caller is nonetheless expected to cache the answer and
     * bound the call, because a store that has stopped answering will make this HANG rather than fail.
     *
     * @throws GeneralException if this provider's own configuration is unusable
     * @throws IOException if the store cannot be reached, or reports that the container holding this
     *     deployment's content is gone
     */
    void requireReachable() throws GeneralException, IOException;
}
