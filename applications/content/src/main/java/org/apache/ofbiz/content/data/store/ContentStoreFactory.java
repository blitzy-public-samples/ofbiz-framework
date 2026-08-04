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
 * Resolves the configured {@link ContentStore}, or reports that content storage is unchanged.
 *
 * <p>The backend is named by the {@code content.store.provider} property of the {@code content}
 * resource, which {@code docker/docker-entrypoint.sh} renders from
 * {@code OFBIZ_CONTENT_STORE_PROVIDER}:
 *
 * <ul>
 *   <li>{@code database} - the shipped default, and what an unset, blank or unrecognised value falls
 *       back to. {@code DataResource} content keeps being stored the way it always has been, this
 *       factory returns {@code null}, and no storage client is ever constructed.</li>
 *   <li>{@code filesystem} - {@link FileSystemContentStore}, rooted at the upload directory named by
 *       {@code content.upload.path.prefix}. It needs no credentials and changes nothing about where
 *       content is written; it becomes a fleet-wide store when that directory is a shared mount.</li>
 *   <li>{@code s3} - {@link S3ContentStore}, an S3-compatible object store reached with the bucket,
 *       region, endpoint, credentials and addressing style declared by the remaining
 *       {@code content.store.s3.*} properties.</li>
 * </ul>
 *
 * <p><strong>{@code null} is the documented database-mode signal.</strong> An unrecognised value is
 * never fatal: it is logged as a warning naming the offending value and then treated as
 * {@code database}, so a typo cannot stop a deployment from starting.
 *
 * <p><strong>The cache is conditional.</strong> Only a {@code filesystem} or {@code s3} resolution is
 * cached, and only once one has been built: {@code database} mode answers {@code null} without
 * populating the cache, so while the configuration names {@code database} every call re-reads the
 * property and a later change to {@code filesystem} or {@code s3} does take effect in the same JVM.
 * Once a provider IS cached it is kept for the life of the JVM, together with a signature of the
 * configuration it was built from: a subsequent configuration change - a rotated credential, a
 * different bucket, a different provider, or a move back to {@code database} - is reported as a warning
 * and takes effect on the next restart. The cached provider is deliberately neither replaced nor closed
 * while it is running, because a caller may still be reading a stream it opened, and closing a provider
 * out from under that caller would turn a configuration change into a failed request. In a container
 * deployment configuration arrives from the environment at start, so a change is a redeploy in any case.
 *
 * <p>Thread safe: creation is serialised and the cached resolution is published through a volatile
 * field, so concurrent callers share one provider and none of them can observe a half-built one.
 *
 * @see ContentStore
 */
public final class ContentStoreFactory {

    private static final String MODULE = ContentStoreFactory.class.getName();

    private static final String RESOURCE = "content";

    private static final String PROVIDER_PROPERTY = "content.store.provider";

    private static final String DATABASE = "database";
    private static final String FILESYSTEM = "filesystem";
    private static final String S3 = "s3";

    /** Guards construction so that two concurrent callers cannot build two providers. */
    private static final Object CREATION_LOCK = new Object();

    private static volatile Resolution resolution;

    private ContentStoreFactory() { }

    /**
     * Returns the configured content store, or {@code null} when content storage is unchanged.
     *
     * @return the configured store, or null for {@code database} storage, which is the default
     * @throws GeneralException if a provider is named but its configuration is incomplete or unusable
     */
    public static ContentStore getContentStore() throws GeneralException {
        return resolve(setting(PROVIDER_PROPERTY, DATABASE));
    }

    /**
     * Returns the configured content store, honouring a {@code SystemProperty} row for the given
     * delegator ahead of the property file.
     *
     * @param delegator the delegator whose configuration applies, may be null
     * @return the configured store, or null for {@code database} storage, which is the default
     * @throws GeneralException if a provider is named but its configuration is incomplete or unusable
     */
    public static ContentStore getContentStore(Delegator delegator) throws GeneralException {
        if (delegator == null) {
            return getContentStore();
        }
        String configured = EntityUtilProperties.getPropertyValue(RESOURCE, PROVIDER_PROPERTY, DATABASE, delegator);
        String onFile = setting(PROVIDER_PROPERTY, DATABASE);
        if (S3.equals(configured.trim().toLowerCase(Locale.ROOT)) && !S3.equals(onFile.trim().toLowerCase(Locale.ROOT))
                && UtilValidate.isEmpty(setting("content.store.s3.bucket", ""))) {
            // The SELECTOR can be overridden by a SystemProperty row while the s3 settings can not: they
            // are read from the property file alone, so that no database row can repoint the client. A row
            // asking for s3 on a fleet whose files carry no bucket would therefore make every content
            // operation throw on each newly started instance. It is refused HERE, once, with the reason.
            Debug.logWarning("A SystemProperty row sets " + PROVIDER_PROPERTY + "=" + S3 + ", but this instance's"
                    + " content.properties declares no content.store.s3.bucket - and the s3 settings are read"
                    + " from the property file only, so a database row cannot supply them. The row is ignored"
                    + " and [" + onFile + "] storage is used. Configure the store through the environment"
                    + " (OFBIZ_CONTENT_STORE_PROVIDER and OFBIZ_S3_*) and restart. See DOCKER.adoc.", MODULE);
            return resolve(onFile);
        }
        return resolve(configured);
    }

    /**
     * Drops the cached provider, closing it if it holds resources, so that a subsequent
     * {@link #getContentStore()} re-resolves {@code content.store.provider} from configuration.
     *
     * <p>Closing here is safe in a way that closing on supersede is not: this is called only when no
     * caller holds a stream the provider opened.
     */
    static void clearCache() {
        synchronized (CREATION_LOCK) {
            Resolution dropped = resolution;
            resolution = null;
            if (dropped != null && dropped.store() instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    Debug.logWarning(e, "A discarded content store could not be closed", MODULE);
                }
            }
        }
    }

    static String setting(String name, String defaultValue) {
        String value = UtilProperties.getPropertyValue(RESOURCE, name);
        return UtilValidate.isEmpty(value) ? defaultValue : value.trim();
    }

    /**
     * Refuses a storage key that does not obey the grammar {@link ContentStore} publishes.
     *
     * <p>Every provider validates through here, so one key is accepted or refused identically whatever
     * backend is configured, and no provider has to be trusted to repeat the checks correctly. The
     * grammar is deliberately narrow: a relative POSIX path whose segments are all non-empty and
     * neither {@code .} nor {@code ..}, with no backslash and no control character. That is a
     * traversal-resistant form, so no accepted spelling can escape a filesystem provider's root.
     *
     * <p>It is NOT an authorization check. Whether the caller may address the content a key names, and
     * whether the key is the one that names the intended content, remain the caller's responsibility -
     * for the content-store seam in {@code DataResourceWorker} that means the location allow-lists it
     * applies and the {@code ofbiz.home}-relative path it derives the key from.
     *
     * @param key the storage key to check
     * @throws GeneralException if the key is empty or breaks the grammar
     */
    static void requireUsableKey(String key) throws GeneralException {
        if (UtilValidate.isEmpty(key)) {
            throw new GeneralException("A content store key is required");
        }
        if (key.startsWith("/") || key.endsWith("/") || key.contains("//") || key.indexOf('\\') >= 0) {
            throw new GeneralException("Unusable content store key [" + key + "]: a key is a relative path with no"
                    + " empty segment and no backslash");
        }
        for (String segment : key.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new GeneralException("Unusable content store key [" + key + "]: a key may not contain a"
                        + " relative segment");
            }
        }
        for (int i = 0; i < key.length(); i++) {
            if (key.charAt(i) < ' ' || key.charAt(i) == 127) {
                throw new GeneralException("Unusable content store key: a key may not contain a control character");
            }
        }
    }

    private static ContentStore resolve(String configured) throws GeneralException {
        String provider = configured == null ? DATABASE : configured.trim().toLowerCase(Locale.ROOT);
        if (!provider.isEmpty() && !DATABASE.equals(provider) && !FILESYSTEM.equals(provider) && !S3.equals(provider)) {
            Debug.logWarning("Unrecognised " + PROVIDER_PROPERTY + " [" + configured + "]. It must be " + DATABASE
                    + ", " + FILESYSTEM + " or " + S3 + "; falling back to " + DATABASE + " storage.", MODULE);
            provider = DATABASE;
        }
        if (provider.isEmpty()) {
            provider = DATABASE;
        }
        // ONE read of the volatile field, into a local, for the whole of this method. Reading it twice
        // would let a concurrent clearCache() null it between the two, and the second read would then be
        // dereferenced as if the first had succeeded.
        Resolution cached = resolution;
        if (DATABASE.equals(provider)) {
            reportStaleConfiguration(cached, DATABASE);
            return null;
        }
        requireSingleTenantDeployment(provider);
        if (cached != null) {
            // The FAST PATH, taken on every file-backed content operation. The configuration signature is
            // deliberately NOT computed here: it is a SHA-256 over six property reads, and it is only
            // needed to decide whether to REPORT a configuration change, which is worth doing once rather
            // than on every read. It is therefore computed only while the provider is not yet built, and
            // afterwards only until the change has been reported.
            if (!cached.reported()) {
                reportStaleConfiguration(cached, signature(provider));
            }
            return cached.store();
        }
        synchronized (CREATION_LOCK) {
            if (resolution == null) {
                ContentStore store = S3.equals(provider) ? new S3ContentStore() : new FileSystemContentStore();
                resolution = new Resolution(signature(provider), store);
                Debug.logInfo("Content storage provider [" + provider + "] is in use", MODULE);
                return store;
            }
            cached = resolution;
        }
        reportStaleConfiguration(cached, signature(provider));
        return cached.store();
    }

    /**
     * Returns the signature a resolution is cached against.
     *
     * @param provider the provider name
     * @return the signature
     * @throws GeneralException if the provider's own configuration cannot be read
     */
    private static String signature(String provider) throws GeneralException {
        return provider + '\n' + (S3.equals(provider)
                ? S3ContentStore.configurationSignature()
                : FileSystemContentStore.configurationSignature());
    }

    /**
     * Refuses to activate a store in a multi-tenant deployment.
     *
     * <p>A storage key is derived from the content's own {@code ofbiz.home}-relative path, and that path
     * is the same for every tenant: two tenants of one deployment generate {@code dataResourceId} values
     * from their own sequences, so they can name the same object and one tenant's content would overwrite
     * or be served in place of another's. The seam that derives the key - the file-resolution methods of
     * {@code DataResourceWorker} - is reached without a delegator on some of its paths, so the tenant
     * cannot be established there and cannot be folded into the key.
     *
     * <p>Rather than leave that hole open, an external store is refused outright while
     * {@code general.properties multitenant} is {@code Y}. Content storage then stays exactly as it is
     * today for a multi-tenant deployment, which is safe, and the operator is told why. DOCKER.adoc
     * carries this and the rule that each deployment uses its own bucket.
     *
     * @param provider the provider that was asked for
     * @throws GeneralException if this deployment is multi-tenant
     */
    private static void requireSingleTenantDeployment(String provider) throws GeneralException {
        if (UtilProperties.propertyValueEqualsIgnoreCase("general", "multitenant", "Y")) {
            throw new GeneralException("The [" + provider + "] content store cannot be used by a multi-tenant"
                    + " deployment (general.properties multitenant=Y): a storage key is derived from the content's"
                    + " own path, which carries no tenant, so one tenant's content could overwrite another's."
                    + " Leave " + PROVIDER_PROPERTY + " at " + DATABASE + ", or run one deployment per tenant with"
                    + " its own bucket. See DOCKER.adoc.");
        }
    }

    /**
     * Warns, at most once, when the configuration no longer matches the provider that is already
     * running.
     *
     * <p>The running provider is kept: replacing it would have to close a client that a caller may still
     * be streaming from. Saying so once is what turns a silently ignored change into an operator-visible
     * instruction to restart.
     *
     * @param cached the resolution that is running, or null when none is
     * @param wanted the signature the configuration now asks for, or {@code database}
     */
    private static void reportStaleConfiguration(Resolution cached, String wanted) {
        if (cached == null || cached.signature().equals(wanted) || cached.reported()) {
            return;
        }
        cached.markReported();
        String running = cached.signature().substring(0, cached.signature().indexOf('\n'));
        String asked = DATABASE.equals(wanted) ? DATABASE : wanted.substring(0, wanted.indexOf('\n'));
        Debug.logWarning("The content storage configuration now asks for [" + asked + "] but this instance resolved ["
                + running + "] when it started. The running provider is kept, because a caller may still be reading"
                + " from it; restart the instance to apply the change.", MODULE);
    }

    private static final class Resolution {

        private final String signature;
        private final ContentStore store;
        private volatile boolean reported;

        private Resolution(String signature, ContentStore store) {
            this.signature = signature;
            this.store = store;
        }

        private String signature() {
            return signature;
        }

        private ContentStore store() {
            return store;
        }

        private boolean reported() {
            return reported;
        }

        private void markReported() {
            this.reported = true;
        }
    }
}
