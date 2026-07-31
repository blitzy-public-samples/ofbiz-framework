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
 * Content storage strategy contract for the Content component.
 *
 * <p>Abstracts where the bytes behind a file-backed {@code DataResource}, and behind user
 * uploads, physically live, so that callers need not know the backing store and content need
 * not sit on instance-local disk.
 *
 * <p><strong>Provider selection.</strong> The intended provider is named by the
 * {@code content.store.provider} property of the {@code content} resource, whose recognised
 * values are {@code database}, {@code filesystem} and {@code s3}. {@code database} is the
 * default and the committed value; in that mode no implementation of this interface takes part
 * and the pre-existing {@code DataResource} database-storage path is used unchanged.
 * {@code filesystem} is rooted at {@code content.upload.path.prefix}, and {@code s3} is
 * configured through the {@code content.store.s3.*} properties, whose committed values are
 * blank.
 *
 * <p>An unrecognised provider name is <strong>refused</strong>, not worked around: selection
 * fails with a {@link ContentStoreConfigurationException} rather than degrading to database
 * storage, because a deployment that believed it had selected {@code s3} must not silently
 * write somewhere else on the strength of a typo.
 *
 * <p><strong>Requirements on implementations.</strong> Every operation addresses content
 * through a single {@code String} key that is opaque and provider-relative: a filesystem
 * implementation reads it as a path under its configured root, an S3 implementation as an
 * object key inside its configured bucket. An implementation must reject a null or empty key
 * and any key that would escape its configured root; must throw
 * {@link java.io.FileNotFoundException} from {@link #get(String)},
 * {@link #openStream(String)} and {@link #size(String)} rather than return {@code null} or a
 * sentinel for a key that resolves to no stored content, leaving {@link #exists(String)} as the
 * non-exceptional probe; must return a fresh {@link InputStream} from
 * {@link #openStream(String)} that the caller owns and closes; and must hold no per-request
 * state, so that many request threads can share one instance.
 *
 * <p><strong>The mutation surface is narrower than the read surface.</strong> A key handed to
 * {@link #get(String)}, {@link #openStream(String)} or {@link #exists(String)} may address any
 * location the provider is configured to read, because {@code DataResource.objectInfo} values in
 * existing databases legitimately point across the whole deployment. A key handed to
 * {@link #put(String, byte[])}, {@link #put(String, InputStream, long)} or
 * {@link #delete(String)} must additionally resolve inside the one tree or bucket the provider owns,
 * and an implementation must refuse anything else - an absolute path, a location URL, a {@code .} or
 * {@code ..} component, or a path that escapes through a symbolic link. A read allow list is far too
 * broad to be a write boundary: it spans application code, themes and plugins, so a writable key
 * satisfying it could name a script or a configuration file that the application itself later loads.
 *
 * <p><strong>What is stored is reachable only by the deployment.</strong> An implementation that
 * writes to local storage must create both content and any directory it needs with owner-only
 * permissions, applied as each is created rather than afterwards, and must place content at its
 * final name atomically so that a reader never observes a partial write and a failed write never
 * truncates what was already there.
 *
 * <p><strong>Absence is narrow, and everything else is not absence.</strong> Only an
 * unambiguous "this key holds no content" answer from the backing store may be reported as
 * absence - a missing filesystem entry, or an object-store response that names the key as
 * missing. A refused permission, an exhausted credential, an unreachable store, a missing
 * container (a deleted bucket or an unreadable root), or any answer the provider cannot
 * classify must surface as a failure, because reporting such a condition as absence would let
 * a caller conclude that content was never stored - and, worse, let a caller overwrite or
 * garbage-collect content that is merely temporarily unreachable.
 *
 * <p><strong>Whole-content operations are bounded; streaming is not.</strong>
 * {@link #get(String)} and {@link #put(String, byte[])} are convenience forms that materialise
 * the whole content in the heap, so an implementation must refuse content larger than the
 * ceilings named by {@code content.store.max.memory.bytes} and, for the read path,
 * {@code content.store.max.get.bytes}, rather than attempt an allocation whose size an uploader
 * chooses: one oversized object is otherwise enough to exhaust the heap of whichever instance
 * served the request. Content of any size is handled through the streaming pair
 * {@link #openStream(String)} and {@link #put(String, InputStream, long)}, which is what every
 * integrated read and write path is expected to use, and which is not subject to either
 * ceiling.
 *
 * <p><strong>Diagnostics carry no raw key and no remote text.</strong> An implementation must not
 * compose a storage key, a resolved path, a bucket name, an endpoint or a credential into an
 * exception message or a log line as itself: those values are caller-influenced and identify the
 * deployment's storage, and one containing a newline would forge a log record. It must report them
 * as a stable opaque reference instead. For the same reason a message produced by a remote store,
 * or by the underlying I/O layer, must not be quoted or chained into anything the implementation
 * throws; the failure is summarised by its type and by stable request identifiers.
 *
 * <p><strong>Lifecycle.</strong> A provider may hold pooled connections, threads or file
 * handles. {@link #close()} releases them, is idempotent, and is called by
 * {@code ContentStoreFactory} both when a resolved provider is replaced and when the JVM shuts
 * down, so no provider instance is ever orphaned with its resources still held. An instance
 * must not be used after it has been closed.
 *
 * <p><strong>What the single key does and does not express.</strong> The key addresses content
 * inside one provider; on its own it says nothing about how a {@code DataResource} location
 * becomes such a key. That translation is not a provider's business and is deliberately absent
 * from this contract: the six file-backed {@code dataResourceTypeId} values resolve against three
 * different roots - an absolute path, {@code ofbiz.home}, and, for {@code CONTEXT_FILE} and
 * {@code CONTEXT_FILE_BIN}, a context root supplied per call - so a location by itself is
 * ambiguous until those are taken into account. {@link ContentStoreFactory} owns that translation,
 * in {@link ContentStoreFactory#storageKeyFor(String, String, String)}.
 *
 * <p><strong>Bytes here, files there.</strong> The operations below are byte oriented, whereas the
 * Content component's callers deal in real local {@link java.io.File} objects, streams and lengths,
 * and two of them write their target directly without consulting the Content component's file
 * resolution at all. Bridging those two shapes - materialising a stored object as a local file,
 * staging a write, publishing it when the transaction commits, and allocating an upload location -
 * is likewise not a provider's business; it is the storage-aware bridge on
 * {@link ContentStoreFactory}, which an implementation of this interface neither sees nor needs.
 *
 * <p><strong>Which operations the Content component reaches.</strong> Every integrated path uses the
 * streaming pair and the measurements beside it, never the bounded convenience forms: the component's
 * file-resolution seam reaches {@link #openStream(String)} when it renders content straight to a
 * response and when it materialises stored content as a local file, {@link #size(String)} when it
 * declares a length beside a stream, {@link #exists(String)} when it decides which side of the seam
 * holds the content, and {@link #put(String, InputStream, long)} when a staged write or upload is
 * published as the transaction commits - which together cover reading, rendering, uploading, creating
 * and updating file-backed content. Content that an uploader chose the size of therefore never becomes
 * an allocation of that size, and no ceiling applies to it.
 *
 * <p>{@link #get(String)} and {@link #put(String, byte[])} are consequently reached by no integrated
 * path at all. They remain part of the contract, and every provider implements and is tested against
 * both, because they are the natural shape for a caller that already holds - or genuinely wants - the
 * whole content in memory; that is exactly why they are the forms that carry the in-memory ceilings.
 *
 * <p>{@link #delete(String)} completes the storage contract, and every provider implements and is
 * tested against it, but no Content component path invokes it.
 * That is not an omission in this package: OFBiz removes a file-backed data resource with the
 * {@code removeDataResource} service, which is declared {@code engine="entity-auto"} with
 * {@code invoke="delete"} and therefore removes the {@code DataResource} row alone. No OFBiz code
 * has ever deleted the backing bytes - a pre-existing behaviour that leaves an orphaned file on
 * local disk today - so there is no content-removal path to route to a provider, and creating one
 * would mean changing a service definition that this refactor must leave untouched. A deployment
 * that wants orphaned objects reclaimed should use the object store's own lifecycle rules, which
 * is where that concern belongs and which needs no code at all.
 */
public interface ContentStore {

    /**
     * Stores the supplied content under the supplied key, creating the entry when it is
     * absent and replacing it in full when it already exists.
     *
     * <p>Implementations must create any intermediate structure they need on demand, so that
     * callers do not have to prepare the store first, must create it with owner-only
     * permissions where the store is local, and must make the replacement of existing content
     * all-or-nothing: a reader concurrent with this call sees either the whole previous content
     * or the whole new content, never a truncated or half-written mixture, and a write that
     * fails part-way leaves the previous content intact.
     *
     * <p>The key must resolve inside the one tree or bucket the provider owns, which is
     * narrower than what a read accepts; see the interface documentation for why.
     *
     * <p>This is the bounded convenience form. It refuses content longer than
     * {@code content.store.max.memory.bytes}; use {@link #put(String, InputStream, long)} for
     * content whose size is chosen by an uploader.
     *
     * @param key the opaque, provider-relative storage key to write to; must be neither
     *     null nor empty, and must resolve inside the provider's own storage root
     * @param data the complete content to store; must not be null, but may be a
     *     zero-length array in order to store empty content
     * @throws GeneralException if the key or the content is not usable - for instance a
     *     null or empty key, a key the provider rejects because it carries a control
     *     character or a traversal component or would escape the provider's own storage
     *     root, content longer than the in-memory ceiling, or an incomplete provider
     *     configuration
     * @throws IOException if the underlying store cannot be written to
     */
    void put(String key, byte[] data) throws GeneralException, IOException;

    /**
     * Stores exactly {@code length} bytes read from the supplied stream under the supplied key,
     * creating the entry when it is absent and replacing it in full when it already exists.
     *
     * <p>This is the streaming form, and the one every integrated write path uses: the content
     * is transferred to the backing store without being materialised in the heap, so content
     * whose size an uploader chooses can never turn into an allocation of that size. The length
     * is supplied by the caller because it is known before the transfer begins - a file's length
     * on disk, or a declared upload size - and because an object store must be told the length
     * up front in order to store the object in a single request rather than buffering it.
     *
     * <p>Exactly {@code length} bytes are consumed from the stream, no more, so a caller may write
     * several entries from one stream. The stream is read from its current position and
     * <strong>is not closed by this method</strong>; the caller retains ownership. An
     * implementation must fail rather than store a short entry when the stream reaches its end
     * before {@code length} bytes have been read, and must leave any previous content intact when
     * it does so.
     *
     * @param key the opaque, provider-relative storage key to write to; must be neither
     *     null nor empty
     * @param content the stream to transfer content from; must not be null, and is neither
     *     closed nor rewound by this method
     * @param length the exact number of bytes to read from the stream and store; must not be
     *     negative, and may be zero in order to store empty content
     * @throws GeneralException if the key, the stream or the length is not usable - for
     *     instance a null or empty key, a null stream, a negative length, a key the provider
     *     rejects because it would escape the provider's configured root, or an incomplete
     *     provider configuration
     * @throws IOException if the stream cannot be read, if it does not yield exactly
     *     {@code length} bytes, or if the underlying store cannot be written to
     */
    void put(String key, InputStream content, long length) throws GeneralException, IOException;

    /**
     * Reads the whole content stored under the supplied key into memory.
     *
     * <p><strong>Bounded.</strong> This is the bounded convenience form, for content whose size
     * is known to be modest: content larger than {@code content.store.max.memory.bytes} or
     * {@code content.store.max.get.bytes} is refused rather than read, because this method
     * materialises it in the heap and the size would otherwise be one an uploader chose. Use
     * {@link #openStream(String)} for content that is legitimately large: it streams and is not
     * subject to either ceiling.
     *
     * @param key the opaque, provider-relative storage key to read; must be neither null
     *     nor empty
     * @return the complete stored content, never null; a zero-length array means the
     *     stored content is empty
     * @throws GeneralException if the key is null or empty, is rejected by the provider, the
     *     provider configuration is incomplete, or the stored content is longer than the
     *     in-memory ceiling
     * @throws IOException if the content cannot be read or is larger than the configured
     *     maximum; in particular a {@link java.io.FileNotFoundException} is thrown when the
     *     key does not resolve to stored content, so that this method never returns null
     */
    byte[] get(String key) throws GeneralException, IOException;

    /**
     * Opens a fresh stream over the content stored under the supplied key, positioned at
     * the first byte.
     *
     * <p>Every invocation returns an independent stream; streams are never shared or
     * rewound between callers. <strong>The caller owns the returned stream and must close
     * it</strong>, ideally with a try-with-resources statement, because it may hold an open
     * file handle or an open connection to the object store.
     *
     * @param key the opaque, provider-relative storage key to read; must be neither null
     *     nor empty
     * @return a newly opened stream over the stored content, never null, positioned at the
     *     start and owned by the caller
     * @throws GeneralException if the key is null or empty, is rejected by the provider, or
     *     the provider configuration is incomplete
     * @throws IOException if the stream cannot be opened; in particular a
     *     {@link java.io.FileNotFoundException} is thrown when the key does not resolve to
     *     stored content, so that this method never returns null
     */
    InputStream openStream(String key) throws GeneralException, IOException;

    /**
     * Reports the exact length in bytes of the content stored under the supplied key.
     *
     * <p>This is what lets a caller stream content while still declaring its length - a
     * {@code Content-Length} on a response, or the length a service contract returns beside a
     * stream - without first reading the content in order to measure it.
     *
     * @param key the opaque, provider-relative storage key to measure; must be neither null
     *     nor empty
     * @return the length of the stored content in bytes, never negative; zero means the stored
     *     content is empty
     * @throws GeneralException if the key is null or empty, is rejected by the provider, or
     *     the provider configuration is incomplete
     * @throws IOException if the length cannot be established; in particular a
     *     {@link java.io.FileNotFoundException} is thrown when the key does not resolve to
     *     stored content, so that this method never returns a sentinel length
     */
    long size(String key) throws GeneralException, IOException;

    /**
     * Reports whether the supplied key currently resolves to stored content.
     *
     * <p>This is the non-exceptional way to probe the store: it returns {@code false} for a
     * well-formed key that simply has nothing stored under it, and reserves its exceptions
     * for keys that are unusable or for stores that cannot be reached.
     *
     * @param key the opaque, provider-relative storage key to probe; must be neither null
     *     nor empty
     * @return {@code true} if and only if the key resolves to stored content
     * @throws GeneralException if the key is null or empty, is rejected by the provider, or
     *     the provider configuration is incomplete
     * @throws IOException if the underlying store cannot be interrogated
     */
    boolean exists(String key) throws GeneralException, IOException;

    /**
     * Removes the content stored under the supplied key.
     *
     * <p>The operation is idempotent: deleting a key that resolves to nothing is a
     * successful no-op rather than an error, so repeated or replayed clean-up is safe.
     *
     * <p>Like {@link #put(String, byte[])} the key must resolve inside the one tree or bucket
     * the provider owns, and a local implementation must remove the name it was given rather
     * than whatever that name points at, so that a removal can never be redirected through a
     * symbolic link. Structure is not removable through this operation.
     *
     * @param key the opaque, provider-relative storage key to remove; must be neither null
     *     nor empty, and must resolve inside the provider's own storage root
     * @throws GeneralException if the key is null or empty, is rejected by the provider, or
     *     the provider configuration is incomplete
     * @throws IOException if the underlying store cannot be modified
     */
    void delete(String key) throws GeneralException, IOException;

    /**
     * Releases every resource this provider holds - pooled connections, background threads, open
     * handles - and renders the instance unusable.
     *
     * <p>Must be idempotent, so that a second call is a successful no-op, and must not throw for
     * a provider that holds nothing. {@code ContentStoreFactory} calls this when a resolved
     * provider is replaced and once more when the JVM shuts down, which is what guarantees that
     * a provider built during a configuration change, or a provider that lost a construction
     * race, does not survive as an orphan holding a connection pool open.
     *
     * @throws GeneralException if the provider cannot release a resource for a reason that is
     *     not an I/O failure
     * @throws IOException if a resource cannot be released
     */
    void close() throws GeneralException, IOException;
}
