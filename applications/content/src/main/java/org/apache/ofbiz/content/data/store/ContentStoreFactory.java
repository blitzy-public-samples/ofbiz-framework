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

import java.util.Locale;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.util.EntityUtilProperties;

/**
 * Resolves the configured content-storage provider from {@code content.store.provider}.
 *
 * <p>This is the only way a {@link ContentStore} is obtained, so that no caller has to know which
 * backing store a deployment selected, and the only place the provider names appear.
 *
 * <p><strong>The resolution table.</strong>
 * <table>
 * <caption>How {@code content.store.provider} selects a provider</caption>
 * <tr><th>Configured value</th><th>Result</th></tr>
 * <tr><td>unset, blank or {@code database}</td><td>{@code null}</td></tr>
 * <tr><td>{@code filesystem}</td><td>a {@link FileSystemContentStore}</td></tr>
 * <tr><td>{@code s3}</td><td>an {@link S3ContentStore}</td></tr>
 * <tr><td>anything else</td><td>a warning naming the value, then {@code null}</td></tr>
 * </table>
 *
 * <p><strong>{@code null} means database mode.</strong> It is the documented signal that content is
 * held in the {@code DataResource} database columns exactly as it always has been, which is the
 * committed default. There is no sentinel provider type and no {@code Optional}: a caller tests for
 * {@code null} and, finding it, does exactly what it did before this refactor.
 *
 * <p><strong>An unrecognised value never fails a start-up.</strong> It is warned about and treated
 * as database mode, because the alternative - refusing to serve - turns a typo in one environment
 * variable into an outage, while the fallback is the behaviour every unconfigured deployment
 * already has. The container entry point applies the same rule when it renders the property.
 *
 * <p>Comparison is trimmed and case-insensitive, so {@code " S3 "} and {@code "Database"} resolve.
 * There are no aliases: {@code fs} and {@code local} are not accepted and are warned about like any
 * other unrecognised value.
 *
 * <p>The resolved provider is cached against the configured value, so a provider is constructed
 * once rather than per request, and a configuration change is picked up because the cache key is
 * the value itself. Caching a fallback is safe precisely because the fallback is not a failure:
 * nothing is poisoned and every later call behaves identically.
 */
public final class ContentStoreFactory {

    private static final String MODULE = ContentStoreFactory.class.getName();

    /** The resource the provider selection is read from. */
    private static final String PROPERTY_RESOURCE = "content";

    /** The property that selects the backing store. */
    private static final String PROVIDER_PROPERTY = "content.store.provider";

    /** The value that means "hold content in the database", which is also what unset means. */
    private static final String DATABASE = "database";

    /** The value that selects {@link FileSystemContentStore}. */
    private static final String FILESYSTEM = "filesystem";

    /** The value that selects {@link S3ContentStore}. */
    private static final String S3 = "s3";

    /** The normalised value {@link #cached} was resolved from; null when nothing is cached yet. */
    private static volatile String cachedFor;

    /** The provider resolved for {@link #cachedFor}; null both when unresolved and in database mode. */
    private static volatile ContentStore cached;

    private ContentStoreFactory() { }

    /**
     * Returns the configured content-storage provider.
     *
     * @return the provider to store and read content through, or {@code null} when content is held
     *     in the database, which is the default when nothing is configured
     * @throws GeneralException if a provider was selected but cannot be constructed from the
     *     configuration it needs - a missing bucket or region, or an unusable endpoint
     */
    public static ContentStore getContentStore() throws GeneralException {
        return resolve(UtilProperties.getPropertyValue(PROPERTY_RESOURCE, PROVIDER_PROPERTY, DATABASE));
    }

    /**
     * Returns the configured content-storage provider, honouring a {@code SystemProperty} override.
     *
     * <p>Identical to {@link #getContentStore()} except that the selection may also come from the
     * {@code SystemProperty} entity, exactly as every other OFBiz property may.
     *
     * @param delegator the delegator a {@code SystemProperty} override is read through; may be null,
     *     in which case only {@code content.properties} is consulted
     * @return the provider to store and read content through, or {@code null} for database mode
     * @throws GeneralException if a provider was selected but cannot be constructed
     */
    public static ContentStore getContentStore(Delegator delegator) throws GeneralException {
        if (delegator == null) {
            return getContentStore();
        }
        return resolve(EntityUtilProperties.getPropertyValue(PROPERTY_RESOURCE, PROVIDER_PROPERTY, DATABASE,
                delegator));
    }

    /**
     * Discards the cached provider, so that the next resolution reads the configuration again.
     *
     * <p>Package-private because the only caller is {@code ContentStoreFactoryTest}, which varies
     * {@code content.store.provider} in memory and needs each case resolved rather than answered
     * from the previous case's cache.
     */
    static void clearCache() {
        cachedFor = null;
        cached = null;
    }

    /**
     * Applies the resolution table to a configured value.
     *
     * @param configured the raw configured value, which may be null, blank or padded
     * @return the provider, or {@code null} for database mode and for an unrecognised value
     * @throws GeneralException if a recognised provider cannot be constructed
     */
    private static ContentStore resolve(String configured) throws GeneralException {
        String selected = configured == null ? "" : configured.trim().toLowerCase(Locale.ROOT);
        if (UtilValidate.isEmpty(selected)) {
            selected = DATABASE;
        }
        // Read once into a local: the pair is volatile and read twice, and a concurrent clearCache
        // between the two reads would otherwise be seen as "cached for this value, and it is null".
        String resolvedFor = cachedFor;
        if (selected.equals(resolvedFor)) {
            return cached;
        }
        ContentStore store = construct(selected);
        cached = store;
        cachedFor = selected;
        return store;
    }

    /**
     * Constructs the provider a normalised value names.
     *
     * @param selected the trimmed, lower-cased configured value, never empty
     * @return the provider, or {@code null} for database mode and for an unrecognised value
     * @throws GeneralException if a recognised provider cannot be constructed
     */
    private static ContentStore construct(String selected) throws GeneralException {
        switch (selected) {
        case DATABASE:
            return null;
        case FILESYSTEM:
            Debug.logInfo("Content storage provider [" + FILESYSTEM + "] selected by " + PROVIDER_PROPERTY, MODULE);
            return new FileSystemContentStore();
        case S3:
            Debug.logInfo("Content storage provider [" + S3 + "] selected by " + PROVIDER_PROPERTY, MODULE);
            return new S3ContentStore();
        default:
            // Warned about and treated as database mode, never thrown: see the class documentation.
            Debug.logWarning("Unrecognised " + PROVIDER_PROPERTY + " value [" + selected + "]; content is stored in"
                    + " the database instead. Set it to " + DATABASE + ", " + FILESYSTEM + " or " + S3 + ".", MODULE);
            return null;
        }
    }
}
