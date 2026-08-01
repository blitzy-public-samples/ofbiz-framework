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
 * The storage contract a content-storage provider satisfies, so that file-backed
 * {@code DataResource} content and user uploads can be held somewhere every instance of a
 * horizontally scaled deployment can reach.
 *
 * <p>Five operations, and deliberately no more: store, read whole, read as a stream, test and
 * remove. That is exactly what the one integration seam in
 * {@link org.apache.ofbiz.content.data.DataResourceWorker} needs, and holding the contract to it
 * keeps every provider trivially substitutable. Listing, copying, metadata, presigned URLs,
 * batching and multipart controls are deliberately absent; a deployment that needs object
 * expiry uses the object store's own lifecycle rules, which needs no code here at all.
 *
 * <p><strong>Keys.</strong> A key is an opaque, provider-relative location, expressed with
 * {@code /} separators and carrying no leading separator, no {@code .} or {@code ..} component
 * and no control character. The caller derives it deterministically from the
 * {@code DataResource} type and its recorded {@code objectInfo}, so the same resource always
 * maps to the same key on every instance. A provider must refuse a key it cannot confine to the
 * one tree or bucket it owns rather than resolve it somewhere else.
 *
 * <p><strong>Absence.</strong> {@link #get(String)} and {@link #openStream(String)} throw
 * {@link java.io.FileNotFoundException} for a key that holds nothing; they never return
 * {@code null}. {@link #exists(String)} answers the same question without throwing, and
 * {@link #delete(String)} is idempotent, so removing content that is not there succeeds.
 *
 * <p><strong>Provider selection.</strong> Instances are obtained from
 * {@link ContentStoreFactory}, never constructed by callers. A {@code null} store is the
 * documented signal that content is held in the database exactly as it always has been, which
 * is the default when nothing is configured.
 *
 * <p><strong>Thread safety.</strong> Implementations must be safe for concurrent use by many
 * request threads; a single instance is cached and shared for the life of the configuration.
 */
public interface ContentStore {

    /**
     * Stores the supplied content under the supplied key, creating the entry when it is absent
     * and replacing it in full when it already exists.
     *
     * <p>Implementations create whatever intermediate structure they need on demand, create it
     * with owner-only permissions where the store is local, and make the replacement of
     * existing content all-or-nothing: a concurrent reader sees either the whole previous
     * content or the whole new content, and a write that fails part-way leaves the previous
     * content intact.
     *
     * @param key the opaque, provider-relative storage key; neither null nor empty, and must
     *     resolve inside the provider's own storage root
     * @param data the complete content to store; must not be null, and may be a zero-length
     *     array in order to store empty content
     * @throws GeneralException if the key or the content is unusable, for instance a null or
     *     empty key, a key carrying a control character or a traversal component, a key that
     *     would escape the provider's storage root, or an incomplete provider configuration
     * @throws IOException if the underlying store cannot be written to
     */
    void put(String key, byte[] data) throws GeneralException, IOException;

    /**
     * Reads the whole content stored under the supplied key.
     *
     * <p>For content whose size is known to be modest. Use {@link #openStream(String)} for
     * content whose size an uploader chose, so that it is streamed rather than held in the heap
     * in full.
     *
     * @param key the opaque, provider-relative storage key; neither null nor empty
     * @return the complete stored content, never null; a zero-length array means the stored
     *     content is empty
     * @throws GeneralException if the key is null, empty, refused by the provider, or the
     *     provider configuration is incomplete
     * @throws IOException if the content cannot be read; in particular
     *     {@link java.io.FileNotFoundException} when the key holds nothing, so that this
     *     method never returns null
     */
    byte[] get(String key) throws GeneralException, IOException;

    /**
     * Opens a stream over the content stored under the supplied key, positioned at its first
     * byte.
     *
     * <p>Ownership of the returned stream passes to the caller, which must close it. This is
     * the unbounded read: content is transferred as it is consumed and is never materialised in
     * the heap in full.
     *
     * @param key the opaque, provider-relative storage key; neither null nor empty
     * @return a fresh stream over the stored content, never null
     * @throws GeneralException if the key is null, empty, refused by the provider, or the
     *     provider configuration is incomplete
     * @throws IOException if the content cannot be opened; in particular
     *     {@link java.io.FileNotFoundException} when the key holds nothing, so that this
     *     method never returns null
     */
    InputStream openStream(String key) throws GeneralException, IOException;

    /**
     * Reports whether the supplied key holds stored content.
     *
     * <p>The question is answered rather than signalled: a key that holds nothing is
     * {@code false}, not an exception. Only a key the provider refuses outright, or a store it
     * cannot reach, fails.
     *
     * @param key the opaque, provider-relative storage key; neither null nor empty
     * @return {@code true} when the key resolves to stored content
     * @throws GeneralException if the key is null, empty, refused by the provider, or the
     *     provider configuration is incomplete
     * @throws IOException if the store cannot be reached
     */
    boolean exists(String key) throws GeneralException, IOException;

    /**
     * Removes the content stored under the supplied key.
     *
     * <p>Idempotent: removing a key that holds nothing is a successful no-op, so a replayed
     * clean-up is safe and a caller never has to test first.
     *
     * @param key the opaque, provider-relative storage key; neither null nor empty
     * @throws GeneralException if the key is null, empty, refused by the provider, or the
     *     provider configuration is incomplete
     * @throws IOException if the store cannot be modified
     */
    void delete(String key) throws GeneralException, IOException;
}
