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
import java.nio.charset.StandardCharsets;

import org.apache.ofbiz.base.util.GeneralException;

/**
 * The storage contract a content-storage provider satisfies, so that file-backed
 * {@code DataResource} content and user uploads can be held somewhere every instance of a
 * horizontally scaled deployment can reach.
 *
 * <p>Five operations, and deliberately no more: store, read whole, read as a stream, test and
 * remove. Listing, copying, metadata, presigned URLs, batching and multipart controls are
 * deliberately absent; a deployment that needs object expiry uses the object store's own lifecycle
 * rules, which needs no code here at all.
 *
 * <p><strong>Keys.</strong> A key is an opaque, provider-relative location expressed with {@code /}
 * separators. It carries no leading separator, no {@code .} or {@code ..} component and no control
 * character, and it is at most {@link #MAX_KEY_LENGTH_BYTES} bytes when encoded as UTF-8 - the
 * limit an object store imposes, applied to every provider so that a key one provider accepts is a
 * key all of them accept. A key is never invented by a caller: it comes from
 * {@link ContentStoreFactory#storeKey}, which derives it from what the active provider needs - the
 * {@code ofbiz.home}-relative path for a path-keyed provider, and namespace, tenant scope and
 * {@code dataResourceId} for the object store - so the same resource maps to the same key on every
 * instance and no two tenants map to one key. Every provider applies that grammar again at its own
 * boundary through the one implementation of it, {@link #requireUsableKey(String)}, which lives here
 * beside the documentation it enforces so that a key one provider accepts is never a key another
 * refuses; a provider then adds whatever its own storage requires and refuses a key it cannot
 * confine to the one tree or bucket it owns rather than resolve it somewhere else.
 *
 * <p><strong>Bounded reads.</strong> {@link #get(String)} materialises whole content and is
 * therefore bounded by {@link ContentStoreFactory#maxObjectSize}: content larger than the
 * configured ceiling is refused rather than allocated, so one oversized object cannot exhaust the
 * heap of the instance that reads it. {@link #openStream(String)} is the unbounded operation, and it
 * is unbounded in the sense that matters - it never holds the content in the heap in full - which is
 * why it, and not {@code get}, is what content of a size an uploader chose is served through.
 *
 * <p><strong>Absence.</strong> {@link #get(String)} and {@link #openStream(String)} throw
 * {@link java.io.FileNotFoundException} for a key that holds nothing; they never return
 * {@code null}. {@link #exists(String)} answers the same question without throwing, and
 * {@link #delete(String)} is idempotent, so removing content that is not there succeeds. Absence is
 * the one condition a provider must report as absence: a store it cannot reach, a bucket that is
 * not there and a credential that is refused are failures, and reporting any of them as "nothing
 * stored here" would turn an outage into silently missing content.
 *
 * <p><strong>What the integration seam uses, and what it cannot.</strong> The seam in
 * {@link org.apache.ofbiz.content.data.DataResourceWorker} reads content through this contract. It
 * does not write through it, and the reason is in the write path this refactor may not change: the
 * services that create or update file-backed content resolve the target themselves and write the
 * bytes themselves - {@code createFileMethod} writes to the path it builds, and
 * {@code createBinaryFileMethod} and {@code updateBinaryFileMethod} write to the file the seam
 * resolves for them - so there is no moment inside the seam at which content bytes are handed over.
 * There is no removal flow either: nothing in those services deletes a backing file. Publishing an
 * upload from inside the seam would therefore have to happen outside the transaction that records
 * the {@code DataResource} row, which is a conflict this contract cannot resolve on its own, so it
 * is reported here rather than worked around: those services are business logic the plan places
 * out of scope (plan section 0.2.2), and the seam is confined to the file-resolution methods the
 * plan names (plan sections 0.2.1 and 0.6.3). {@link #put(String, byte[])} and
 * {@link #delete(String)} are consequently part of the contract - the plan freezes the five
 * operations (plan section 0.4.1) - and are what an out-of-band ingest or migration performs, and
 * what the provider tests exercise, rather than operations a request reaches.
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
     * The greatest length of a storage key, in bytes of its UTF-8 encoding.
     *
     * <p>1024 is the object-store limit on an object key. Applying it to every provider is what
     * keeps a key portable between them: content stored while one provider was configured is
     * addressable by the same key after a deployment changes provider.
     */
    int MAX_KEY_LENGTH_BYTES = 1024;

    /**
     * The greatest length of one key component, in bytes of its UTF-8 encoding.
     *
     * <p>255 is the file-name limit common filesystems impose. Applying it to every provider is the
     * other half of key portability: a key an object store accepts as one long string has to remain
     * writable as a path once a deployment changes to a path-keyed provider.
     */
    int MAX_KEY_COMPONENT_LENGTH_BYTES = 255;

    /**
     * Requires that a key satisfies the grammar documented above, so that every provider refuses
     * exactly the same keys, in the same way, before it issues any request or touches any storage.
     *
     * <p>One implementation for every provider, because a key is only opaque if it means the same
     * thing everywhere: a deployment that migrates content from one provider to another must not
     * discover that a key one accepted is a key the next refuses. It is also why this raises a
     * {@link GeneralException} rather than an {@link IOException} - an unusable key is the caller's
     * mistake, not the store's failure, and every caller of this contract distinguishes the two.
     *
     * <p>Refused, in this order: a null, empty or whitespace-only key; a control character anywhere,
     * because a key travels to an object store inside the request line and its headers; a leading
     * {@code /} or {@code \} or a Windows drive prefix, any of which would make the key absolute and
     * so make a provider ignore its own root; a key longer than
     * {@link #MAX_KEY_LENGTH_BYTES} bytes; an empty component, which is a doubled or trailing
     * separator naming no content at all; a {@code .} or {@code ..} component, which names something
     * other than what it appears to; and a component longer than
     * {@link #MAX_KEY_COMPONENT_LENGTH_BYTES} bytes. A colon that is not a drive prefix is legal in a
     * POSIX file name and is deliberately allowed.
     *
     * @param key the key to check
     * @throws GeneralException if the key does not satisfy the grammar, naming the rule it broke
     */
    static void requireUsableKey(String key) throws GeneralException {
        if (key == null || key.trim().isEmpty()) {
            throw new GeneralException("A content store key must not be empty");
        }
        for (int index = 0; index < key.length(); index++) {
            if (Character.isISOControl(key.charAt(index))) {
                throw new GeneralException("A content store key must not contain a control character");
            }
        }
        // A leading separator or a Windows drive prefix would make a provider ignore its own root entirely.
        if (key.startsWith("/") || key.startsWith("\\")
                || (key.length() > 1 && key.charAt(1) == ':' && Character.isLetter(key.charAt(0)))) {
            throw new GeneralException("A content store key must be relative and must not carry a drive prefix:"
                    + " [" + key + "]");
        }
        if (key.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_LENGTH_BYTES) {
            throw new GeneralException("A content store key must be at most " + MAX_KEY_LENGTH_BYTES
                    + " bytes long");
        }
        // Split keeping trailing empties, so that "a/b/" and "a//b" are both seen as an empty component.
        for (String component : key.split("/", -1)) {
            if (component.isEmpty()) {
                throw new GeneralException("A content store key must not contain an empty component: ["
                        + key + "]");
            }
            if (".".equals(component) || "..".equals(component)) {
                throw new GeneralException("A content store key must not contain a '" + component
                        + "' component: [" + key + "]");
            }
            if (component.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_COMPONENT_LENGTH_BYTES) {
                throw new GeneralException("A content store key component must be at most "
                        + MAX_KEY_COMPONENT_LENGTH_BYTES + " bytes long: [" + key + "]");
            }
        }
    }

    /**
     * Stores the supplied content under the supplied key, creating the entry when it is absent
     * and replacing it in full when it already exists.
     *
     * <p>Implementations create whatever intermediate structure they need on demand, and create it
     * with owner-only permissions where the store is local.
     *
     * <p>Creating content that was not there is all-or-nothing: it becomes visible complete or not at
     * all, and it is private from the instant it exists. Replacing content that was already there is
     * all-or-nothing only where the store makes it so - an object store replaces an object in one
     * operation, while the filesystem provider rewrites the existing file in place, deliberately,
     * because in that provider the storage tree is the deployment's own content tree and the file's
     * identity, timestamps and permissions are part of what has to be preserved. A caller must
     * therefore not assume that a concurrent reader of content being replaced sees only the whole old
     * or the whole new bytes; it must assume only that the content it stored is what a later read
     * returns.
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
     * <p>Bounded: content larger than {@link ContentStoreFactory#maxObjectSize} is refused before
     * anything is allocated for it, so a single oversized object cannot exhaust the heap.
     *
     * @param key the opaque, provider-relative storage key; neither null nor empty
     * @return the complete stored content, never null; a zero-length array means the stored
     *     content is empty
     * @throws GeneralException if the key is null, empty, refused by the provider, the provider
     *     configuration is incomplete, or the stored content is larger than the configured maximum
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
