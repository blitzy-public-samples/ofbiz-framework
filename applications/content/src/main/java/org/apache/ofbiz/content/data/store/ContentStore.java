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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.apache.ofbiz.base.util.GeneralException;

/**
 * The storage contract a content-storage provider satisfies, so that file-backed
 * {@code DataResource} content and user uploads can be held somewhere every instance of a
 * horizontally scaled deployment can reach.
 *
 * <p>Five operations, and deliberately no more: store, read whole, read as a stream, test and
 * remove. Storing has two overloads - from an array and from a stream of a known length - because the
 * source differs and the operation does not. Listing, copying, metadata, presigned URLs, batching and
 * multipart controls are
 * deliberately absent; a deployment that needs object expiry uses the object store's own lifecycle
 * rules, which needs no code here at all.
 *
 * <p><strong>Keys.</strong> A key is an opaque, provider-relative location expressed with {@code /}
 * separators. It carries no leading separator, no {@code .} or {@code ..} component and no control
 * character, and it is bounded both as a whole and per component by the limits an object store and a
 * filesystem impose, applied to every provider so that a key one provider accepts is a key all of
 * them accept. A key is never invented by a caller: it comes from
 * {@link ContentStoreFactory#storeKey}, which derives it from what the active provider needs - the
 * {@code ofbiz.home}-relative path for a path-keyed provider, and namespace, tenant scope and
 * {@code dataResourceId} for the object store - so the same resource maps to the same key on every
 * instance and no two tenants map to one key. Every provider applies that grammar again at its own
 * boundary, and it does so through the single validator the factory owns, so that a key one provider
 * accepts is never a key another refuses; a provider then adds whatever its own storage requires and
 * refuses a key it cannot confine to the one tree or bucket it owns rather than resolve it somewhere
 * else. This interface declares the five operations and nothing else - no constant and no helper -
 * because the grammar is one implementation shared by the factory and every provider rather than a
 * second thing an implementer could satisfy differently.
 *
 * <p><strong>Bounded reads.</strong> {@link #get(String)} materialises whole content and is
 * therefore bounded by {@link ContentStoreFactory#maxObjectSize}: content larger than the
 * configured ceiling is refused rather than allocated, so one oversized object cannot exhaust the
 * heap of the instance that reads it. {@link #openStream(String)} is the unbounded operation, and it
 * is unbounded in the sense that matters - it never holds the content in the heap in full - which is
 * why it, and not {@code get}, is what content of a size an uploader chose is served through. It
 * returns a {@link ContentStream}, which carries the exact number of bytes the stream will yield
 * alongside the stream itself, so a caller that has to declare a length - an HTTP response, or the
 * {@code length} a stream consumer is handed - gets one without a second request and without reading
 * the content to measure it.
 *
 * <p><strong>Absence.</strong> {@link #get(String)} and {@link #openStream(String)} throw
 * {@link java.io.FileNotFoundException} for a key that holds nothing; they never return
 * {@code null}. {@link #exists(String)} answers the same question without throwing, and
 * {@link #delete(String)} is idempotent, so removing content that is not there succeeds. Absence is
 * the one condition a provider must report as absence: a store it cannot reach, a bucket that is
 * not there and a credential that is refused are failures, and reporting any of them as "nothing
 * stored here" would turn an outage into silently missing content.
 *
 * <p><strong>What the integration seam uses.</strong> The seam in
 * {@link org.apache.ofbiz.content.data.DataResourceWorker} both reads and writes through this
 * contract, which is what makes an instance replaceable: an upload becomes readable by every other
 * instance before the request that carried it completes.
 *
 * <ul>
 * <li><em>Reads.</em> Rendering text content and serving binary content go through
 * {@link #openStream(String)}; a caller that needs the whole content in the heap, and knows it is
 * modest, uses {@link #get(String)}.</li>
 * <li><em>Writes.</em> The services that create or update file-backed content resolve a local
 * {@code File} and write their bytes to it - {@code createFileMethod} writes to the path it builds,
 * and {@code createBinaryFileMethod} and {@code updateBinaryFileMethod} write to the file the seam
 * resolved for them. Once those bytes are on disk and have passed the upload validation those
 * services perform, the seam publishes them with {@link #put(String, InputStream, long)}, streaming
 * from that file so an upload is not held in the heap a second time. Their signatures, their service
 * definitions and the {@code DataResource} entity are untouched: publishing is an additional step
 * taken inside the same service call, and it does nothing at all when no provider is
 * configured.</li>
 * <li><em>Rollback.</em> A publish happens inside the transaction that records the
 * {@code DataResource} row, and {@link #delete(String)} is what undoes it when that transaction rolls
 * back, so a failed create leaves no object behind. That is why removal is part of this contract
 * rather than an out-of-band chore.</li>
 * </ul>

 *
 * <p>When no provider is configured the seam answers "nothing to do" and the content services perform
 * exactly the local write they always performed: no service signature, no service definition and no
 * field of the {@code DataResource} row changes for any of this.
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
     * An open stream over stored content, together with the exact number of bytes it will yield.
     *
     * <p>It is an {@link InputStream}, so every consumer that only wants the bytes treats it as one and
     * closing it closes whatever the provider opened underneath. What it adds is the one thing a stream
     * cannot answer for itself: how long the content is. A caller that has to declare a length up front -
     * an HTTP {@code Content-Length}, or the {@code length} the {@code getDataResourceStream} contract
     * returns beside its stream - therefore gets one without a second request to the store and without
     * reading the content into the heap to measure it, which is the whole reason content of a size an
     * uploader chose can be served through this operation rather than through {@link #get(String)}.
     *
     * <p>The length is what the store reports, and it is reported rather than trusted: a consumer that
     * copies the stream should still copy until the stream ends rather than assume exactly this many
     * bytes arrive. It is declared {@code final} because it holds no policy - it is a stream and a
     * number - and a provider that needed to add behaviour would add it to the stream it wraps.
     */
    final class ContentStream extends FilterInputStream {

        private final long length;

        /**
         * Wraps an open stream with the length of the content behind it.
         *
         * @param content the open stream, positioned at the first byte; must not be null
         * @param length the exact number of bytes the stream will yield; must not be negative
         */
        public ContentStream(InputStream content, long length) {
            super(content);
            if (content == null) {
                throw new IllegalArgumentException("A content stream must wrap an open stream");
            }
            if (length < 0L) {
                throw new IllegalArgumentException("A content stream cannot be " + length + " bytes long");
            }
            this.length = length;
        }

        /**
         * Returns the exact number of bytes this stream will yield.
         *
         * @return the content length in bytes, never negative
         */
        public long length() {
            return length;
        }
    }

    /**
     * The greatest length of a storage key, in bytes of its UTF-8 encoding.
     *
     * <p>The value is {@link ContentStoreFactory#MAX_KEY_LENGTH_BYTES}, which is where the one key
     * grammar lives; this is the name that grammar is published under to a caller holding only this
     * contract. Two declarations of one number, not two numbers.
     */
    int MAX_KEY_LENGTH_BYTES = ContentStoreFactory.MAX_KEY_LENGTH_BYTES;

    /**
     * The greatest length of one key component, in bytes of its UTF-8 encoding.
     *
     * <p>As above: the value is {@link ContentStoreFactory#MAX_KEY_COMPONENT_LENGTH_BYTES}.
     */
    int MAX_KEY_COMPONENT_LENGTH_BYTES = ContentStoreFactory.MAX_KEY_COMPONENT_LENGTH_BYTES;

    /**
     * Requires that a key satisfies the grammar documented above, so that every provider refuses
     * exactly the same keys, in the same way, before it issues any request or touches any storage.
     *
     * <p><strong>One implementation, reached by two names.</strong> The grammar is
     * {@link ContentStoreFactory#requireUsableKey(String)}: the factory mints keys and both providers
     * validate them there, so this method delegates rather than restates. A second copy of the rules was
     * the defect - the two lists disagreed, so the factory could mint a key the provider it was minted
     * for then refused - and this is the name a caller holding only this contract uses to reach the one
     * that remains.
     *
     * @param key the key to check
     * @throws GeneralException if the key does not satisfy the grammar, naming the rule it broke
     */
    static void requireUsableKey(String key) throws GeneralException {
        ContentStoreFactory.requireUsableKey(key);
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
     * Stores content read from a stream under the supplied key, creating the entry when it is absent
     * and replacing it in full when it already exists.
     *
     * <p>The same operation as {@link #put(String, byte[])} with the same visibility, permission and
     * atomicity guarantees; what differs is where the bytes come from. This is the overload a caller
     * uses when the content is already somewhere it can be streamed from - a validated upload on
     * local disk, for instance - because it never holds that content in the heap in full, and it is
     * therefore the overload that makes publishing an upload of a size the uploader chose safe.
     *
     * <p>The length has to be exact and has to be known in advance: an object store needs it to frame
     * the request. Supplying a length that does not match the stream is a caller error, and a provider
     * reports it rather than storing content that is silently truncated or padded.
     *
     * <p>Ownership of the stream stays with the caller, which must close it; a provider reads from it
     * and does not close it, so a caller can go on using the source it came from.
     *
     * @param key the opaque, provider-relative storage key; neither null nor empty, and must resolve
     *     inside the provider's own storage root
     * @param content the stream to read the content from, positioned at its first byte; must not be
     *     null
     * @param length the exact number of bytes to read from {@code content}; must not be negative, and
     *     may be zero in order to store empty content
     * @throws GeneralException if the key or the content is unusable - a null or empty key, a key
     *     carrying a control character or a traversal component, a key that would escape the
     *     provider's storage root, a null stream, a negative length, a stream that does not yield
     *     exactly {@code length} bytes, or an incomplete provider configuration
     * @throws IOException if the content cannot be read or the underlying store cannot be written to
     */
    void put(String key, InputStream content, long length) throws GeneralException, IOException;

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
     * the heap in full. The returned {@link ContentStream} also reports the exact length of the
     * content, so a caller that has to declare one does not have to read the content to find it.
     *
     * @param key the opaque, provider-relative storage key; neither null nor empty
     * @return a fresh stream over the stored content, carrying its exact length; never null
     * @throws GeneralException if the key is null, empty, refused by the provider, or the
     *     provider configuration is incomplete
     * @throws IOException if the content cannot be opened; in particular
     *     {@link java.io.FileNotFoundException} when the key holds nothing, so that this
     *     method never returns null
     */
    ContentStream openStream(String key) throws GeneralException, IOException;

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
