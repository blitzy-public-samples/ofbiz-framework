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

import org.apache.ofbiz.base.util.GeneralException;

/**
 * The five-method storage contract for file-backed {@code DataResource} content - {@code LOCAL_FILE},
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
 * <p><strong>Logging.</strong> Where an implementation logs a storage operation it names the key, and
 * the container holding it where the provider has one, and never the content itself.
 *
 * <p>Implementations are thread safe.
 *
 * @see ContentStoreFactory
 */
public interface ContentStore {

    /**
     * Stores the given content under the given key, replacing any object already held there.
     *
     * @param key the storage key
     * @param data the content to store, never null
     * @throws GeneralException if the key breaks the key grammar or the provider is misconfigured
     * @throws IOException if the store cannot be written
     */
    void put(String key, byte[] data) throws GeneralException, IOException;

    /**
     * Returns the whole object held under the given key.
     *
     * <p>The object is read into memory, so this is the bounded accessor: an object larger than the
     * implementation's documented limit is refused with an {@link IOException} instead of being
     * loaded. Use {@link #openStream} for content of unbounded size.
     *
     * @param key the storage key
     * @return the object's bytes
     * @throws GeneralException if the key breaks the key grammar or the provider is misconfigured
     * @throws java.io.FileNotFoundException if this store does not hold the key
     * @throws IOException if the store cannot be read or the object exceeds the implementation's limit
     */
    byte[] get(String key) throws GeneralException, IOException;

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
}
