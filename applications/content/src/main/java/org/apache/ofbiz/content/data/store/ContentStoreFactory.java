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

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.util.EntityUtilProperties;

/**
 * Configuration-driven factory for the Content component's content storage provider.
 *
 * <p>Which {@link ContentStore} implementation is active is named by the
 * {@code content.store.provider} property of the {@code content} resource, that is by
 * {@code applications/content/config/content.properties} - {@code content} without its
 * extension, because that is the resource name {@code UtilProperties} expects. Nothing here
 * reads the environment: in a container deployment {@code docker/docker-entrypoint.sh}
 * substitutes {@code OFBIZ_CONTENT_STORE_PROVIDER} into the {@code config/} copy of that
 * properties file, and because {@code config/} precedes {@code lib/ofbiz.jar} on the classpath
 * the injected value is the one {@code UtilProperties} sees.
 *
 * <p><strong>The three recognised values.</strong>
 * <ul>
 *   <li>{@code database} - the committed default. No provider is returned; see below.</li>
 *   <li>{@code filesystem} - a {@link FileSystemContentStore}, which preserves the pre-existing
 *       {@code content.upload.path.prefix} behaviour.</li>
 *   <li>{@code s3} - an {@link S3ContentStore}, configured from the {@code content.store.s3.*}
 *       properties.</li>
 * </ul>
 * Matching ignores case and surrounding whitespace, so {@code Database} and {@code S3} are
 * understood, as is a value padded with spaces. No other spelling, abbreviation or synonym is.
 *
 * <p><strong>{@code null} is the database-storage signal, not an error.</strong> Both
 * {@link #getContentStore()} and {@link #getContentStore(Delegator)} return {@code null}
 * whenever the property is unset, blank or {@code database}. A caller - in practice the
 * delegation seam in {@code DataResourceWorker} - must read {@code null} as "no provider is
 * active, keep using the existing {@code DataResource} database-storage path unchanged". That is
 * why no {@code DatabaseContentStore} implementation exists, and why an {@code Optional} is
 * deliberately not returned: the seam guards on {@code null}. In that mode
 * {@link S3ContentStore} is never instantiated, so the AWS SDK is never loaded and stays
 * completely inert.
 *
 * <p><strong>An unusable value never breaks a boot.</strong> A value that is none of the three
 * recognised names is reported as a warning naming the offending value and is then treated as
 * {@code database}. This factory throws nothing and fails no start-up, so an unmodified checkout
 * - and equally a deployment with a typo in its configuration - behaves exactly as it does
 * today. The same holds if constructing a provider fails, for instance because the
 * object-storage client is absent from the classpath: the failure is reported and database
 * storage is used.
 *
 * <p><strong>Caching and thread safety.</strong> The property is read on every call, so an
 * override held in the {@code SystemProperty} entity takes effect without a restart, but the
 * resolved outcome is cached: a provider is constructed, and the selection logged, only once per
 * distinct configured value for the life of the JVM. The cache is a single volatile reference to
 * an immutable holder, so the configured value and the provider it produced are always published
 * together. Both providers are documented as holding no per-request state, so the one instance
 * is shared safely by every request thread.
 */
public final class ContentStoreFactory {

    private static final String MODULE = ContentStoreFactory.class.getName();

    /** The resource holding the configuration, named without its extension as {@code UtilProperties} requires. */
    private static final String RESOURCE = "content";

    /**
     * The property naming the active provider. {@code docker/docker-entrypoint.sh} fills it from
     * the {@code OFBIZ_CONTENT_STORE_PROVIDER} environment variable.
     */
    private static final String PROPERTY_PROVIDER = "content.store.provider";

    /**
     * Keeps the pre-existing {@code DataResource} database storage. This is the committed value of
     * {@link #PROPERTY_PROVIDER} and the fallback for anything unset, blank or unrecognised.
     */
    private static final String PROVIDER_DATABASE = "database";

    /** Selects {@link FileSystemContentStore}. */
    private static final String PROVIDER_FILESYSTEM = "filesystem";

    /** Selects {@link S3ContentStore}. */
    private static final String PROVIDER_S3 = "s3";

    /**
     * The cached outcome of the most recent resolution; {@code null} before anything has been
     * resolved and again after {@link #clearCache()}.
     */
    private static volatile Resolution resolution;

    private ContentStoreFactory() { }

    /**
     * Returns the active content storage provider, reading the configuration from the
     * {@code content} resource on the classpath.
     *
     * <p>Use this form where no {@link Delegator} is available - the file-resolution helpers in
     * {@code DataResourceWorker} are static and have none. Where a delegator is at hand prefer
     * {@link #getContentStore(Delegator)}, which additionally honours an override held in the
     * {@code SystemProperty} entity.
     *
     * @return the provider named by {@code content.store.provider}, or {@code null} when that
     *     property is unset, blank, {@code database} or unrecognised, in which case the caller
     *     must keep using the existing {@code DataResource} database-storage path unchanged
     */
    public static ContentStore getContentStore() {
        // The three-argument lookup self-defaults, yielding PROVIDER_DATABASE for an absent, blank
        // or whitespace-only property value, so no separate emptiness check is needed here.
        return resolve(UtilProperties.getPropertyValue(RESOURCE, PROPERTY_PROVIDER, PROVIDER_DATABASE));
    }

    /**
     * Returns the active content storage provider, letting a {@code SystemProperty} entity row
     * override the value held in the {@code content} resource.
     *
     * <p>This is the preferred form wherever a delegator is available, because it lets an operator
     * change the storage backend through data as well as through configuration. The four-argument
     * {@code EntityUtilProperties} lookup is used deliberately: it self-defaults both when the row
     * is absent and when its value is blank, which the three-argument delegator-aware form does
     * not do.
     *
     * @param delegator the delegator used to look up the {@code SystemProperty} override; may be
     *     {@code null}, in which case the classpath configuration alone is consulted, exactly as
     *     {@link #getContentStore()} does
     * @return the provider named by {@code content.store.provider}, or {@code null} when that
     *     property is unset, blank, {@code database} or unrecognised, in which case the caller
     *     must keep using the existing {@code DataResource} database-storage path unchanged
     */
    public static ContentStore getContentStore(Delegator delegator) {
        if (delegator == null) {
            // EntityUtilProperties dereferences the delegator to query SystemProperty and would
            // fail on null, so the classpath-only lookup is used rather than refusing the call.
            return getContentStore();
        }
        return resolve(EntityUtilProperties.getPropertyValue(RESOURCE, PROPERTY_PROVIDER, PROVIDER_DATABASE, delegator));
    }

    /**
     * Package-private test seam: discards the cached resolution, so that the next call to
     * {@link #getContentStore()} or {@link #getContentStore(Delegator)} resolves
     * {@code content.store.provider} from configuration afresh.
     *
     * <p>Production code never needs this. A provider is resolved once per distinct configured
     * value and then cached for the life of the JVM, which is exactly what makes several provider
     * values impossible to exercise from a single test JVM without a reset. The seam therefore
     * exists so that the unit tests in the sibling {@code src/test/java} tree - which share this
     * package and so reach a package-private member, a different source root being irrelevant to
     * package-private access in Java - can drive {@code database}, {@code filesystem}, {@code s3}
     * and an unrecognised value within one run. It is deliberately not public, so it widens no
     * API.
     */
    static void clearCache() {
        resolution = null;
    }

    /**
     * Resolves a configured value onto a provider, serving the cached outcome whenever the value
     * has not changed since it was last resolved.
     *
     * @param configuredProvider the raw value read from configuration; may be null
     * @return the provider to use, or {@code null} when database storage is to be used
     */
    private static ContentStore resolve(String configuredProvider) {
        // Trimming here rather than relying on the property lookup is deliberate: the
        // EntityUtilProperties database branch returns a SystemProperty value verbatim, so a row
        // padded with whitespace would otherwise never match a provider name.
        String provider = configuredProvider == null ? "" : configuredProvider.trim();
        Resolution cached = resolution;
        if (cached != null && cached.provider().equals(provider)) {
            return cached.store();
        }
        // A benign race can have two threads build a provider for the same value; the instances are
        // interchangeable because providers hold no per-request state. Publishing the value and the
        // provider as one immutable holder is what stops a reader from ever pairing a stale value
        // with a newer provider, which two independent volatile fields would permit.
        Resolution resolved = new Resolution(provider, instantiate(provider));
        resolution = resolved;
        return resolved.store();
    }

    /**
     * Instantiates the provider named by an already-trimmed configured value, reporting the outcome
     * once, since the caller caches it.
     *
     * @param provider the trimmed configured value; may be empty
     * @return the provider to use, or {@code null} when database storage is to be used
     */
    private static ContentStore instantiate(String provider) {
        if (UtilValidate.isEmpty(provider) || PROVIDER_DATABASE.equalsIgnoreCase(provider)) {
            // The default, and the state of an unmodified checkout: no provider takes part at all
            // and the existing DataResource database-storage path is left completely untouched.
            Debug.logVerbose("Content store provider [" + PROVIDER_DATABASE + "] in effect; the existing DataResource"
                    + " database storage is used unchanged", MODULE);
            return null;
        }
        if (!PROVIDER_FILESYSTEM.equalsIgnoreCase(provider) && !PROVIDER_S3.equalsIgnoreCase(provider)) {
            // Deliberately not fatal: a typo must never stop OFBiz from starting, so an
            // unrecognised value degrades to the committed default. Only the provider name is
            // reported, so nothing sensitive can reach the log.
            Debug.logWarning("Content store provider [" + provider + "] configured by " + RESOURCE + ":"
                    + PROPERTY_PROVIDER + " is not recognised; expected one of [" + PROVIDER_DATABASE + ", "
                    + PROVIDER_FILESYSTEM + ", " + PROVIDER_S3 + "]. Falling back to [" + PROVIDER_DATABASE
                    + "], so the existing DataResource database storage is used unchanged", MODULE);
            return null;
        }
        try {
            if (PROVIDER_FILESYSTEM.equalsIgnoreCase(provider)) {
                FileSystemContentStore fileSystemStore = new FileSystemContentStore();
                Debug.logInfo("Content store provider [" + PROVIDER_FILESYSTEM + "] selected by " + RESOURCE + ":"
                        + PROPERTY_PROVIDER, MODULE);
                return fileSystemStore;
            }
            // The object-storage provider is reached only here, and its constructor reads
            // configuration without touching an object-storage client type, so selecting it neither
            // resolves a credential nor opens a connection. Because the JVM resolves this reference
            // lazily, no other path so much as loads the class or the client library behind it.
            S3ContentStore s3Store = new S3ContentStore();
            Debug.logInfo("Content store provider [" + PROVIDER_S3 + "] selected by " + RESOURCE + ":"
                    + PROPERTY_PROVIDER, MODULE);
            return s3Store;
        } catch (RuntimeException | LinkageError e) {
            // A provider that cannot be built - an absent client library surfaces as a LinkageError
            // rather than an exception - must not stop OFBiz from starting either, so the failure is
            // reported and database storage is used. Caching the outcome keeps a persistent failure
            // from being reported again on every content read or write.
            Debug.logError(e, "Content store provider [" + provider + "] could not be created; falling back to ["
                    + PROVIDER_DATABASE + "], so the existing DataResource database storage is used unchanged", MODULE);
            return null;
        }
    }

    /**
     * The immutable pairing of a configured value with the provider it produced.
     *
     * <p>Holding both in one object, referenced from one volatile field, is what guarantees a reader
     * can never see a stale configured value beside a newer provider.
     *
     * @param provider the trimmed configured value this outcome was resolved from
     * @param store the provider that value resolved to, or {@code null} for database storage
     */
    private record Resolution(String provider, ContentStore store) { }
}
