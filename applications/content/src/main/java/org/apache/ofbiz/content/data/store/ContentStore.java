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
 * <p>This service provider interface abstracts where the bytes behind a file-backed
 * {@code DataResource}, and behind user uploads, physically live, so that no caller has to
 * know the backing store. It exists purely so that an OFBiz instance can be run as a
 * stateless, horizontally scalable service: once an object store is configured, an instance
 * holds no durable local state that a sibling instance behind the same load balancer
 * cannot also read.
 *
 * <p><strong>Provider selection.</strong> Implementations are never instantiated by
 * callers. {@code ContentStoreFactory} resolves the active provider at runtime from the
 * {@code content.store.provider} property of the {@code content} resource (the bare OFBiz
 * resource name is {@code content}). Exactly three values are recognised:
 * <ul>
 * <li>{@code database} - the <strong>default</strong>, and the value committed to the
 *     repository. In this mode <em>no implementation of this interface is used at all</em>:
 *     the pre-existing {@code DataResource} database-storage path runs completely
 *     unchanged, and the object-storage code, including its client library, stays inert.</li>
 * <li>{@code filesystem} - the historical local-disk behaviour, rooted at the directory
 *     named by {@code content.upload.path.prefix} ({@code runtime/uploads} by default,
 *     relative to {@code ofbiz.home}).</li>
 * <li>{@code s3} - an S3-compatible object store, configured through the
 *     {@code content.store.s3.bucket}, {@code content.store.s3.region},
 *     {@code content.store.s3.endpoint}, {@code content.store.s3.access.key.id},
 *     {@code content.store.s3.secret.access.key} and {@code content.store.s3.path.style}
 *     properties. Those values are injected from the deployment environment; no
 *     credential, bucket or endpoint literal is committed to the source tree or baked into
 *     a container image.</li>
 * </ul>
 *
 * <p><strong>Keys.</strong> Every operation addresses content through a single
 * {@code String} key. The key is deliberately opaque and provider-relative: the filesystem
 * provider interprets it as a path relative to its configured upload prefix, while the S3
 * provider interprets it as an object key inside its configured bucket. The whole contract
 * is expressed with nothing but {@code String} keys, {@code byte} arrays, a {@code boolean}
 * and {@link InputStream}: no local-file handle, no object-store client type and no
 * framework, entity or service type appears in it anywhere. That keeps this contract below
 * the service layer and stops callers from becoming coupled to one backing store.
 *
 * <p><strong>An absent key never yields {@code null}.</strong> Both {@link #get(String)}
 * and {@link #openStream(String)} throw {@link java.io.FileNotFoundException} when the key
 * does not resolve to stored content, instead of returning {@code null}. That mirrors the
 * existing file-resolution behaviour of {@code DataResourceWorker}, whose callers
 * dereference the returned handle with no null check, so routing them through a provider
 * cannot introduce a new null into those paths. Use {@link #exists(String)} whenever
 * absence is an expected, non-exceptional outcome.
 *
 * <p><strong>Exceptions.</strong> The declared checked exceptions are deliberately limited
 * to {@link GeneralException} and {@link IOException}, because the existing call sites
 * already catch {@code FileNotFoundException} and {@code GeneralException} and have to keep
 * compiling and behaving identically. No new exception type is introduced.
 *
 * <p>Implementations are expected to keep no per-request state and to be safe for
 * concurrent use by many request threads.
 */
public interface ContentStore {

    /**
     * Stores the supplied content under the supplied key, creating the entry when it is
     * absent and replacing it in full when it already exists.
     *
     * <p>Any intermediate structure the provider needs is created on demand, so callers do
     * not have to prepare the store first. After a successful return the key resolves to
     * exactly the supplied bytes.
     *
     * @param key the opaque, provider-relative storage key to write to; must be neither
     *     null nor empty
     * @param data the complete content to store; must not be null, but may be a
     *     zero-length array in order to store empty content
     * @throws GeneralException if the key or the content is not usable - for instance a
     *     null or empty key, a key the provider rejects because it would escape the
     *     provider's configured root, or an incomplete provider configuration
     * @throws IOException if the underlying store cannot be written to
     */
    void put(String key, byte[] data) throws GeneralException, IOException;

    /**
     * Reads the whole content stored under the supplied key into memory.
     *
     * <p>This is the convenience form of {@link #openStream(String)}, intended for the
     * modestly sized payloads that the Content component already buffers in memory. Prefer
     * {@link #openStream(String)} for large content.
     *
     * @param key the opaque, provider-relative storage key to read; must be neither null
     *     nor empty
     * @return the complete stored content, never null; a zero-length array means the
     *     stored content is empty
     * @throws GeneralException if the key is null or empty, is rejected by the provider, or
     *     the provider configuration is incomplete
     * @throws IOException if the content cannot be read; in particular a
     *     {@link java.io.FileNotFoundException} is thrown when the key does not resolve to
     *     stored content, so that this method never returns null
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
     * @param key the opaque, provider-relative storage key to remove; must be neither null
     *     nor empty
     * @throws GeneralException if the key is null or empty, is rejected by the provider, or
     *     the provider configuration is incomplete
     * @throws IOException if the underlying store cannot be modified
     */
    void delete(String key) throws GeneralException, IOException;
}
