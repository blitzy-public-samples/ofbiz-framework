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
 * The storage contract for file-backed content, with one implementation per storage backend.
 *
 * <p>File-backed {@code DataResource} content - {@code LOCAL_FILE}, {@code OFBIZ_FILE} and
 * {@code CONTEXT_FILE} and their {@code _BIN} variants - has always lived on the local filesystem of
 * the instance that wrote it, which is what stops an instance from being freely replaceable. This
 * interface is the seam that lets that content live somewhere every instance can reach instead. It is
 * selected by configuration through {@link ContentStoreFactory}; when no provider is configured the
 * content component keeps storing content exactly where it always did, so an unconfigured deployment
 * behaves as it did before this interface existed.
 *
 * <p><strong>Keys.</strong> A key is the {@code ofbiz.home}-relative POSIX path of the content, for
 * example {@code runtime/uploads/1717171717171/10000.png}. It carries no leading slash, no empty
 * segment, no {@code .} or {@code ..} segment, no backslash and no control character; a key that
 * breaks that grammar is rejected with a {@link GeneralException} rather than resolved. Deriving the
 * key from the content's own path is what makes one key mean the same object on every instance and in
 * every provider.
 *
 * <p><strong>Absence is not failure.</strong> {@link #get} and {@link #openStream} report an object
 * this store does not hold by throwing {@link java.io.FileNotFoundException} - and nothing else throws it.
 * Every other outcome, a provider or network fault included, is an {@link IOException} that is not a
 * {@code FileNotFoundException}, or a {@link GeneralException} for a configuration or key fault.
 * Callers depend on that distinction: content that is genuinely absent is a 404, while a store that
 * cannot answer must not be reported as missing content.
 *
 * <p><strong>Replacement is atomic.</strong> A {@link #put} that replaces an existing object is
 * atomic as far as a concurrent reader is concerned: the reader sees either the whole previous object
 * or the whole new one, never a mixture of the two.
 *
 * <p><strong>Logging.</strong> Implementations log the storage key, and the container that holds it
 * where the provider has one, and never the content itself. Keys name content by
 * {@code dataResourceId} rather than by the name a user uploaded, so they are safe to log and are
 * what makes a log line actionable.
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
     * @param content the content to store; read to {@code length} bytes and not closed by this method
     * @param length the number of bytes to read from {@code content}, never negative
     * @throws GeneralException if the key breaks the key grammar or the provider is misconfigured
     * @throws IOException if the content cannot be read or the store cannot be written
     */
    void put(String key, InputStream content, long length) throws GeneralException, IOException;

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
     * @return a stream over the object's bytes, which the caller closes
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
