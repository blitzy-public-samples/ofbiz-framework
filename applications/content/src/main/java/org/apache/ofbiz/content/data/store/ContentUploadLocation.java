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

import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.entity.Delegator;

/**
 * Where a provider wants the next uploaded file placed.
 *
 * <p>Deliberately NOT part of {@link ContentStore}. Storing and retrieving content is addressed by an
 * opaque key and is all any caller of the storage contract needs; choosing the location a new upload
 * should be given is a separate concern that only the two location-bearing providers - filesystem and
 * S3 - have an answer for, and that the {@code database} provider has no answer for at all. Keeping it
 * on its own package-private interface is what lets {@code ContentStoreFactory} ask the question of
 * whichever provider is active without widening the storage contract every provider must implement.
 *
 * <p>The returned value is in exactly the shape {@code DataResource.objectInfo} already persists, so
 * the pre-existing distinction between the absolute form (used by {@code LOCAL_FILE} content) and the
 * form relative to the OFBiz home directory (used by {@code OFBIZ_FILE} content) is preserved rather
 * than collapsed.
 */
interface ContentUploadLocation {

    /**
     * Returns the location the next uploaded file should be placed in.
     *
     * @param delegator the delegator used to let the {@code SystemProperty} entity override the
     *     configured upload location; may be null, in which case only the property file is consulted
     * @param absolute {@code true} for the absolute form, which is how {@code LOCAL_FILE} and
     *     {@code LOCAL_FILE_BIN} content is addressed; {@code false} for the form relative to the
     *     OFBiz home directory, which is how {@code OFBIZ_FILE} and {@code OFBIZ_FILE_BIN} content is
     *     addressed
     * @return the location the next uploaded file should be placed in, never null and never empty
     * @throws GeneralException if the location cannot be established or cannot be trusted - an
     *     unusable configured location, a location outside every allowed root, or a location that
     *     cannot be prepared
     */
    String uploadPath(Delegator delegator, boolean absolute) throws GeneralException;
}
