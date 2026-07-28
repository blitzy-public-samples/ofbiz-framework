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
 * <p><strong>Requirements on implementations.</strong> Every operation addresses content
 * through a single {@code String} key that is opaque and provider-relative: a filesystem
 * implementation reads it as a path under its configured root, an S3 implementation as an
 * object key inside its configured bucket. An implementation must reject a null or empty key
 * and any key that would escape its configured root; must throw
 * {@link java.io.FileNotFoundException} from {@link #get(String)} and
 * {@link #openStream(String)} rather than return {@code null} for a key that resolves to no
 * stored content, leaving {@link #exists(String)} as the non-exceptional probe; must return a
 * fresh {@link InputStream} from {@link #openStream(String)} that the caller owns and closes;
 * and must hold no per-request state, so that many request threads can share one instance.
 */
public interface ContentStore {

    /**
     * Stores the supplied content under the supplied key, creating the entry when it is
     * absent and replacing it in full when it already exists.
     *
     * <p>Implementations must create any intermediate structure they need on demand, so that
     * callers do not have to prepare the store first.
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
     * <p>Prefer {@link #openStream(String)} for large content.
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
