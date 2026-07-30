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

import java.nio.file.Path;

import org.apache.ofbiz.base.util.GeneralException;

/**
 * Implemented by a {@link ContentStore} whose content <em>is</em> a file on the local filesystem, so that a
 * caller which also holds a local path can find out whether the two are the same file.
 *
 * <p>This exists for one reason, and it is a correctness reason rather than an optimisation. The content
 * seam in {@code DataResourceWorker} reconciles a local working copy with the configured store on every
 * resolution: it publishes the local copy when it has been overwritten, and refreshes it from the store
 * otherwise. When the store keeps its content on the local filesystem, those two files can be the very same
 * file - a relative {@code OFBIZ_FILE} location resolves to the same place through either route - and
 * reconciling a file with itself would rewrite it on every read. That would churn the file's modification
 * time, defeat any modification-time-based caching above it, and cost a full copy for nothing.
 *
 * <p>The alternative would be to guess at sameness by comparing lengths, which is wrong in exactly the case
 * that matters: a stale local copy that happens to be the same length as the stored object would be treated
 * as up to date and served. Asking the store which file backs a key answers the question exactly instead.
 *
 * <p>Deliberately a separate capability rather than a member of {@link ContentStore}: an object store has no
 * local file to name, and widening the storage contract with a method most providers cannot answer would push
 * that "not applicable" case onto every implementation. A caller tests for the capability with
 * {@code instanceof} in the same way it does for {@code ContentUploadLocation}. It is public, not
 * package-private, because unlike the upload-location capability it is consulted from the content component's
 * worker rather than from within this package.
 */
public interface LocalContentStore {

    /**
     * Returns the local file that holds the content for the supplied key.
     *
     * <p>The path is returned whether or not anything is stored there yet, because the question being asked
     * is "where would this content live?" and not "is it there?". It is absolute and normalised, so a caller
     * can compare it with a path of its own without repeating that work.
     *
     * @param key the storage key to locate, relative to the provider's configured root; must be neither null
     *     nor empty
     * @return the absolute, normalised local path backing the key, never null
     * @throws GeneralException if the key is null or empty, or resolves outside the locations this provider is
     *     permitted to use
     */
    Path backingPath(String key) throws GeneralException;
}
