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
import java.util.concurrent.atomic.AtomicReference;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;

/**
 * Resolves the configured {@link ContentStore}, or reports that content storage is unchanged.
 *
 * <p>The backend is named by the {@code content.store.provider} property of
 * {@code applications/content/config/content.properties}, which
 * {@code docker/docker-entrypoint.sh} renders from {@code OFBIZ_CONTENT_STORE_PROVIDER}:
 *
 * <ul>
 *   <li>{@code database} - the shipped default, and what an unset or unrecognised value falls back to.
 *       {@code DataResource} content keeps being stored the way it always has been, and this factory
 *       returns {@code null}: there is no external store, and the S3 client is never constructed.</li>
 *   <li>{@code filesystem} - {@link FileSystemContentStore}, rooted at {@code runtime/contentstore}
 *       inside the deployment. It becomes a fleet-wide store when that directory is a shared mount, and
 *       it needs no credentials, which makes it the way to run the store seam without an object store.</li>
 *   <li>{@code s3} - {@link S3ContentStore}, an S3-compatible object store reached with the bucket,
 *       region, endpoint, credentials and addressing style declared by the remaining
 *       {@code content.store.s3.*} properties.</li>
 * </ul>
 *
 * <p>Configuration is read with {@link UtilProperties}, which is the resource the container entry point
 * renders, so the read seam and the write seam resolve the same provider from the same place. Resolution
 * is cached against a signature of the configuration it was built from, so the S3 client is constructed
 * once rather than per call and a configuration change is picked up by rebuilding rather than by
 * restarting. A superseded store that holds resources is closed.
 *
 * <p>Thread safe: the cached resolution is held in an {@link AtomicReference}.
 *
 * @see ContentStore
 */
public final class ContentStoreFactory {

    private static final String MODULE = ContentStoreFactory.class.getName();

    /** The property resource every setting below is read from. */
    private static final String RESOURCE = "content";

    private static final String PROVIDER_PROPERTY = "content.store.provider";

    /**
     * Where the filesystem provider keeps content, relative to {@code ofbiz.home}.
     *
     * <p>A fixed location rather than a setting: the deployment already says whether content is held
     * off the instance by naming a provider, and a second setting for where would only add a way to
     * point one instance at a directory another cannot see. Mount it to share it.
     */
    private static final String FILESYSTEM_ROOT = "runtime/contentstore";

    private static final String DATABASE = "database";
    private static final String FILESYSTEM = "filesystem";
    private static final String S3 = "s3";

    private static final AtomicReference<Resolution> RESOLUTION = new AtomicReference<>();

    private ContentStoreFactory() {
    }

    /**
     * Returns the configured content store, or {@code null} when content storage is unchanged.
     *
     * @return the configured store, or null for {@code database} storage, which is the default
     * @throws GeneralException if a provider is named but its configuration is incomplete or unusable
     */
    public static ContentStore getContentStore() throws GeneralException {
        String provider = setting(PROVIDER_PROPERTY, DATABASE).toLowerCase(Locale.ROOT);
        if (DATABASE.equals(provider)) {
            return null;
        }
        if (!FILESYSTEM.equals(provider) && !S3.equals(provider)) {
            // Refused rather than guessed at: silently storing content somewhere other than where the
            // deployment asked for it is how content goes missing. Reported once per distinct value by
            // the caller's own error handling, which is what turns this into a start-up-visible fault.
            throw new GeneralException("Unrecognised " + PROVIDER_PROPERTY + " [" + provider + "]. It must be "
                    + DATABASE + ", " + FILESYSTEM + " or " + S3 + ", or be left unset for " + DATABASE + " storage.");
        }
        String signature = provider + '\n' + (S3.equals(provider)
                ? S3ContentStore.configurationSignature()
                : filesystemRoot());
        Resolution cached = RESOLUTION.get();
        if (cached != null && cached.signature().equals(signature)) {
            return cached.store();
        }
        ContentStore store = S3.equals(provider)
                ? new S3ContentStore()
                : new FileSystemContentStore(filesystemRoot());
        release(RESOLUTION.getAndSet(new Resolution(signature, store)));
        Debug.logInfo("Content storage provider [" + provider + "] is in use", MODULE);
        return store;
    }

    /**
     * Reads a content property.
     *
     * @param name the property name
     * @param defaultValue what to answer when the property is absent or blank
     * @return the trimmed value, or {@code defaultValue}
     */
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
     * neither {@code .} nor {@code ..}. That is what a key derived from content's own
     * {@code ofbiz.home}-relative path always looks like, and it leaves no spelling that could resolve
     * outside a filesystem provider's root or address an unintended object in a bucket.
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

    /**
     * Resolves the filesystem provider's root inside this deployment.
     *
     * @return the absolute path of {@link #FILESYSTEM_ROOT}
     * @throws GeneralException if {@code ofbiz.home} is not set, so nothing can be resolved against it
     */
    private static String filesystemRoot() throws GeneralException {
        String home = System.getProperty("ofbiz.home");
        if (UtilValidate.isEmpty(home)) {
            throw new GeneralException("The filesystem content store cannot be resolved: ofbiz.home is not set");
        }
        return home + "/" + FILESYSTEM_ROOT;
    }

    /**
     * Closes a superseded store that holds resources, so a re-resolution cannot leak a client.
     *
     * @param superseded the resolution the cache no longer holds, possibly null
     */
    private static void release(Resolution superseded) {
        if (superseded == null || !(superseded.store() instanceof AutoCloseable closeable)) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            // Logged and swallowed: the replacement store is already in the cache and serving, and a
            // client that will never be used again failing to shut down cannot be acted on by a caller.
            Debug.logWarning(e, "A superseded content store could not be closed", MODULE);
        }
    }

    /**
     * A cached store together with the configuration signature it was built from.
     *
     * @param signature the configuration this store reflects
     * @param store the store built from it
     */
    private record Resolution(String signature, ContentStore store) {
    }
}
