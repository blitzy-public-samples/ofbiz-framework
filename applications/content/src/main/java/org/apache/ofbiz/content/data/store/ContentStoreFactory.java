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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.transaction.Synchronization;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.FileUtil;
import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.GeneralRuntimeException;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.transaction.GenericTransactionException;
import org.apache.ofbiz.entity.transaction.TransactionUtil;
import org.apache.ofbiz.entity.util.EntityUtilProperties;
import org.apache.ofbiz.security.SecurityUtil;

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
 * <p>The value is resolved after trimming and with case-insensitive matching. {@code database},
 * blank and unrecognised values are distinguished from one another rather than collapsed: the
 * first two are the legacy-database signal, {@code null}, while an unrecognised value is refused
 * so that content cannot silently reach a store the deployment did not ask for.
 * {@code filesystem} resolves to a {@link FileSystemContentStore} and {@code s3} to an
 * {@link S3ContentStore}.
 *
 * <p>That render is validated at container start rather than here: the entry point refuses a
 * provider name that is not one of the three below, writes the whole resource - not a fragment,
 * because an OFBiz property override shadows a resource rather than being merged into it - with
 * mode {@code 0600}, reads back every value it wrote, and then removes all eleven object-store
 * variables from the environment the OFBiz process inherits. When no object-store variable is set
 * at all it renders nothing, so an unconfigured deployment reads the committed file unchanged.
 * {@code ContentStoreRenderingTests} is the executable contract of that chain, from environment
 * variable through to the provider this factory hands out.
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
 * for exactly those three states - unset, blank or {@code database} - and never for any other
 * value. A caller - in practice the
 * delegation seam in {@code DataResourceWorker} - must read {@code null} as "no provider is
 * active, keep using the existing {@code DataResource} database-storage path unchanged". That is
 * why no {@code DatabaseContentStore} implementation exists, and why an {@code Optional} is
 * deliberately not returned: the seam guards on {@code null}. In that mode
 * {@link S3ContentStore} is never instantiated, so the AWS SDK is never loaded and stays
 * completely inert.
 *
 * <p><strong>An unusable value fails closed.</strong> Database storage is selected by exactly three
 * states - the property absent, the property blank, or the property set to {@code database} - and by
 * nothing else. Any other unrecognised value, and any failure to construct the provider that
 * <em>was</em> asked for, raises a {@link ContentStoreConfigurationException} instead of quietly
 * using database storage.
 *
 * <p>Falling back would be the more forgiving behaviour and is deliberately not what happens,
 * because the fallback is not equivalent to what was asked for. An operator who writes
 * {@code s33} for {@code s3}, or who deploys with the object-storage client missing from the
 * classpath, has stated that content must leave instance-local storage; silently writing it into the
 * database instead means content lands in a backend that was not chosen, is not covered by that
 * store's retention, encryption or access controls, and is not where anyone will look for it. The
 * misconfiguration would produce no error at all - only content in the wrong place, discovered much
 * later. A refused content operation is loud, immediate and reversible; content in an unintended
 * backend is none of those.
 *
 * <p>In a container deployment the same value is validated far earlier still:
 * {@code docker/docker-entrypoint.sh} refuses to start at all when
 * {@code OFBIZ_CONTENT_STORE_PROVIDER} is not one of the three recognised names, so this exception is
 * the second line of defence and the first line for a deployment that is not container-managed.
 *
 * <p>Refusing is the safe direction in both cases: a deployment that has explicitly asked for an
 * object store never quietly stops using one, and content never lands in a backend the fleet is not
 * provisioned, backed up and replicated for.
 *
 * <p><strong>Canonical values, caching and thread safety.</strong> The property is read on every
 * call, so an override held in the {@code SystemProperty} entity takes effect without a restart, but
 * the resolved outcome is cached: a provider is constructed, and the selection or the refusal
 * reported, only once per distinct configured value for the life of the JVM. Each configured value is
 * first reduced to one canonical form - trimmed and lower-cased - and it is that form which both
 * selects the provider and keys the cache, so {@code s3}, {@code S3} and {@code " s3 "} are one value
 * rather than three and no spelling can be resolved against one form while being cached under
 * another. The cache is a {@link ConcurrentMap} of canonical value to immutable outcome, populated
 * through {@code computeIfAbsent}, which constructs at most one provider per value however many
 * threads resolve it at once, so no provider is ever left unreachable while it still holds a
 * connection pool open. It is keyed by configuration rather than by anything a request carries, so it
 * holds one small entry per distinct configured spelling. Both providers are documented as holding no
 * per-request state, so the one instance is shared safely by every request thread. A refusal is
 * cached exactly as a success is - reported once, then enforced without rebuilding anything - and
 * correcting the value takes effect immediately, because a corrected value is a different key.
 *
 * <p><strong>Nothing is left orphaned.</strong> {@link #clearCache()} closes whatever each evicted
 * outcome held, and a hook registered the first time a provider is built closes the rest at JVM
 * shutdown, so no provider ever survives with its object-storage client or its connection pool still
 * open. A deployment in configured database mode constructs no provider and so installs no hook at
 * all.
 *
 * <p><strong>The storage-aware bridge.</strong> A provider alone cannot carry file-backed content:
 * the Content component's callers are frozen by this refactor and they deal in real local
 * {@link File} objects, streams and lengths, not in keys and byte arrays. Two of them do not even
 * consult {@code DataResourceWorker} - {@code DataServices.createFileMethod} and
 * {@code updateFileMethod} build their target with {@code new File(objectInfo)} and write it with
 * {@code FileOutputStream}/{@code Files.copy}. The bridge below closes that gap, and it is the only
 * part of this package a caller needs beyond {@link #getContentStore()}:
 * <ul>
 *   <li>{@link #storageKeyFor(String, String, String)} derives the durable, provider-relative key
 *       for all six file-backed {@code dataResourceTypeId} values, representing
 *       {@code CONTEXT_FILE}'s separate context root explicitly.</li>
 *   <li>{@link #materialiseContentFile(String, String, String)} hands a caller a real local file
 *       for a stored object and arranges for anything that caller writes into it to be published,
 *       serving a read caller and a write caller with one behaviour because the caller cannot tell
 *       them apart. It is the transaction-scoped form of the read {@code getContentFile} performs;
 *       that method reconciles the provider against its own local resolution instead, so that the
 *       write direction still reaches the provider when there is no transaction to publish on and
 *       so that every allow-list and boundary check keeps the position it has always occupied. Use
 *       this method when a transaction is available and the surrounding guards are not needed.</li>
 *   <li>{@link #openContentStream(String, String, String)} streams a stored object for
 *       {@code DataResourceWorker.renderFile}, which needs no file at all.</li>
 *   <li>{@link #uploadStagingPath(String, boolean)} answers
 *       {@code DataResourceWorker.getDataResourceContentUploadPath} with a per-allocation staging
 *       directory whose contents are published on commit, which is what captures the two writers
 *       that never call back in, and which replaces the numbered directory fan-out with flat keys
 *       because an object store needs no directories.</li>
 *   <li>{@link #publishContentFile(String, String, String)} publishes explicitly, for a caller
 *       that is not inside a transaction.</li>
 * </ul>
 * Every one of them is inert in database mode: each returns {@code null} or {@code false} without
 * touching a provider, so the seam that calls it falls straight through to the pre-existing path.
 *
 * <p><strong>Publication and the local staging file.</strong> Content is published to the provider
 * when the surrounding transaction commits, through a {@link Synchronization} registered at the
 * moment the local file is materialised or the staging directory allocated. Publication happens in
 * {@code beforeCompletion} rather than after it, so that a store that refuses the write rolls the
 * transaction back instead of committing a {@code DataResource} row whose content was never stored;
 * the reverse exposure - a stored object whose row then rolls back - is a harmless orphan that the
 * next write to the same key replaces. The local file is removed once the transaction completes,
 * either way, so an instance keeps no durable local state. Where no transaction is in place nothing
 * is registered and {@link #publishContentFile(String, String, String)} must be called explicitly.
 */
public final class ContentStoreFactory {

    private static final String MODULE = ContentStoreFactory.class.getName();

    /**
     * The property naming the active provider. {@code docker/docker-entrypoint.sh} fills it from
     * the {@code OFBIZ_CONTENT_STORE_PROVIDER} environment variable.
     */
    private static final String PROPERTY_PROVIDER = "content.store.provider";

    /**
     * Keeps the pre-existing {@code DataResource} database storage. This is the committed value of
     * {@link #PROPERTY_PROVIDER} and the meaning of an unset or blank value - and of nothing else, so
     * that content can only reach the database when the database was actually asked for.
     */
    private static final String PROVIDER_DATABASE = "database";

    private static final String PROVIDER_FILESYSTEM = "filesystem";

    private static final String PROVIDER_S3 = "s3";

    /** Stable code reported when a provider is selected. */
    private static final String EVENT_SELECTED = "CONTENT-STORE-PROVIDER-SELECTED";

    /** Stable code reported when an explicitly selected provider cannot be honoured. */
    private static final String EVENT_UNUSABLE = "CONTENT-STORE-PROVIDER-UNUSABLE";

    /** Stable code reported when a provider that is no longer in use could not be closed. */
    private static final String EVENT_CLOSE_FAILED = "CONTENT-STORE-PROVIDER-CLOSE-FAILED";

    /** Name of the thread that closes the last resolved provider at JVM shutdown. */
    private static final String SHUTDOWN_THREAD_NAME = "ofbiz-content-store-shutdown";

    /** Whether the shutdown hook that closes the last provider has been registered. */
    private static final AtomicBoolean SHUTDOWN_HOOK_REGISTERED = new AtomicBoolean();

    /** {@code DataResource.dataResourceTypeId} for content held at an absolute local path. */
    private static final String TYPE_LOCAL_FILE = "LOCAL_FILE";

    /** The binary companion of {@link #TYPE_LOCAL_FILE}. */
    private static final String TYPE_LOCAL_FILE_BIN = "LOCAL_FILE_BIN";

    /** {@code DataResource.dataResourceTypeId} for content held relative to {@code ofbiz.home}. */
    private static final String TYPE_OFBIZ_FILE = "OFBIZ_FILE";

    /** The binary companion of {@link #TYPE_OFBIZ_FILE}. */
    private static final String TYPE_OFBIZ_FILE_BIN = "OFBIZ_FILE_BIN";

    /** {@code DataResource.dataResourceTypeId} for content held relative to a separate context root. */
    private static final String TYPE_CONTEXT_FILE = "CONTEXT_FILE";

    /** The binary companion of {@link #TYPE_CONTEXT_FILE}. */
    private static final String TYPE_CONTEXT_FILE_BIN = "CONTEXT_FILE_BIN";

    /**
     * The first key segment under which {@code CONTEXT_FILE} content is stored, keeping the context
     * root in the key so that two webapps holding the same relative path cannot collide.
     */
    private static final String CONTEXT_KEY_PREFIX = "context";

    /**
     * The path segment marking a directory this bridge allocated to stage an upload.
     *
     * <p>It is spliced out of every derived key, so the durable key of a staged upload is the same
     * flat key a later read derives from the persisted {@code objectInfo}. It starts with a dot so
     * that it cannot collide with the numbered directories {@code makeNewDirectory} creates.
     */
    private static final String STAGING_SEGMENT = ".contentstore-staging";

    /** Distinguishes concurrently allocated staging directories within this JVM. */
    private static final AtomicLong STAGING_SEQUENCE = new AtomicLong();

    /**
     * The modification time a freshly materialised staging file is backdated to, so that any write
     * through it is detectable even when the write keeps the byte count identical.
     */
    private static final long STAGED_SENTINEL_MODIFIED = 1000L;

    /**
     * The outcome of every canonical configured value resolved so far, empty before anything has
     * been resolved and again after {@link #clearCache()}.
     *
     * <p>A map rather than a single slot deliberately. The two public forms of this factory can
     * legitimately see different values at the same moment - {@link #getContentStore()} reads only
     * the classpath configuration while {@link #getContentStore(Delegator)} lets a
     * {@code SystemProperty} row override it - and a one-slot cache would then be rebuilt on
     * alternate calls, repeatedly constructing and discarding a provider. Each distinct value
     * instead keeps its own outcome for the life of the JVM.
     *
     * <p>Population goes exclusively through {@code computeIfAbsent}, which holds the bin lock
     * while the mapping function runs. That is what serialises construction and publication: two
     * threads resolving the same value cannot both build a provider, so no provider is ever
     * abandoned unclosed. Neither provider constructor calls back into this factory, so the
     * mapping function cannot re-enter the map it is populating.
     */
    private static final ConcurrentMap<String, Resolution> RESOLUTIONS = new ConcurrentHashMap<>();

    private ContentStoreFactory() {
    }

    /**
     * Reads the classpath {@code content} resource and returns the selected provider, or
     * {@code null} for legacy database mode.
     *
     * @return the provider named by {@code content.store.provider}, or {@code null} when that
     *     property is unset, blank or {@code database}, in which case the caller must keep using the
     *     existing {@code DataResource} database-storage path unchanged
     * @throws GeneralException a {@link ContentStoreConfigurationException} if the configured value
     *     names a provider that does not exist, or names one that cannot be constructed; never for an
     *     absent, blank or {@code database} value
     */
    public static ContentStore getContentStore() throws GeneralException {
        // The three-argument lookup self-defaults, yielding PROVIDER_DATABASE for an absent, blank
        // or whitespace-only property value, so no separate emptiness check is needed here.
        return resolve(UtilProperties.getPropertyValue(ContentStoreSupport.PROPERTY_RESOURCE, PROPERTY_PROVIDER,
                PROVIDER_DATABASE));
    }

    /**
     * Returns the active content storage provider, letting a {@code SystemProperty} entity row
     * override the value held in the {@code content} resource.
     *
     * @param delegator the delegator used to look up the {@code SystemProperty} override; when
     *     {@code null} the classpath configuration alone is consulted
     * @return the provider named by {@code content.store.provider}, or {@code null} when that
     *     property is unset, blank or {@code database}, in which case the caller must keep using the
     *     existing {@code DataResource} database-storage path unchanged
     * @throws GeneralException a {@link ContentStoreConfigurationException} if the configured value
     *     names a provider that does not exist, or names one that cannot be constructed; never for an
     *     absent, blank or {@code database} value
     */
    public static ContentStore getContentStore(Delegator delegator) throws GeneralException {
        if (delegator == null) {
            // EntityUtilProperties dereferences the delegator to query SystemProperty and would
            // fail on null, so the classpath-only lookup is used rather than refusing the call.
            return getContentStore();
        }
        return resolve(EntityUtilProperties.getPropertyValue(ContentStoreSupport.PROPERTY_RESOURCE, PROPERTY_PROVIDER,
                PROVIDER_DATABASE, delegator));
    }

    /**
     * Returns the location the active provider wants the next uploaded file placed in.
     *
     * <p>This is how {@code DataResourceWorker} asks the question without knowing which provider is
     * active. In configured database mode there is no provider and therefore no answer, and
     * {@code null} is returned so that the caller keeps computing the location exactly as it always
     * has.
     *
     * @param delegator the delegator used to let the {@code SystemProperty} entity override both the
     *     provider selection and the configured location; may be {@code null}
     * @param absolute {@code true} for the absolute form, which is how {@code LOCAL_FILE} content is
     *     addressed; {@code false} for the form relative to the OFBiz home directory, which is how
     *     {@code OFBIZ_FILE} content is addressed
     * @return the location the next uploaded file should be placed in, or {@code null} when database
     *     storage is configured and the caller must compute the location itself
     * @throws GeneralException if the configured value names no recognised provider, if the provider
     *     cannot be constructed, or if the provider cannot establish a usable location
     */
    public static String resolveUploadPath(Delegator delegator, boolean absolute) throws GeneralException {
        ContentStore store = getContentStore(delegator);
        if (store instanceof ContentUploadLocation) {
            return ((ContentUploadLocation) store).uploadPath(delegator, absolute);
        }
        return null;
    }

    /**
     * Package-private test seam: discards every cached resolution, closing whatever it held, so
     * that the next call to {@link #getContentStore()} or {@link #getContentStore(Delegator)}
     * resolves {@code content.store.provider} from configuration afresh.
     *
     * <p>Production code never needs this. A provider is resolved once per distinct configured
     * value and then cached for the life of the JVM, which is exactly what makes several provider
     * values impossible to exercise from a single test JVM without a reset. The seam therefore
     * exists so that the unit tests in the sibling {@code src/test/java} tree - which share this
     * package and so reach a package-private member, a different source root being irrelevant to
     * package-private access in Java - can drive {@code database}, {@code filesystem}, {@code s3}
     * and an unrecognised value within one run. It is deliberately not public, so it widens no
     * API.
     *
     * <p>Discarding a provider also releases what it owns: an object-storage client the discarded
     * provider opened is closed here rather than left to the garbage collector, which would never
     * shut down its connection pool or its executor threads. Each entry is removed before what it
     * held is closed, so no caller can be handed a provider whose client is about to be closed.
     */
    static void clearCache() {
        // The key snapshot is taken first so that removal and closing are driven by a fixed set;
        // an entry another thread adds meanwhile is simply left for the next call, which is what a
        // test-only reset wants rather than an unbounded chase of a concurrently repopulated map.
        for (String provider : List.copyOf(RESOLUTIONS.keySet())) {
            closeQuietly(RESOLUTIONS.remove(provider));
        }
    }

    /**
     * Package-private test seam: seats an already-built provider as the outcome of resolving the supplied
     * configured value, closing whatever was cached under that value before it.
     *
     * <p>Production code never needs this, and it widens no API. It exists because the integration this
     * factory sits in the middle of cannot otherwise be exercised without a network: {@link #instantiate}
     * builds the object-storage provider from deployment configuration, so a test that wants
     * {@link #getContentStore()} to answer with an object-storage provider whose client is a local fake has no
     * way to say so. Seating the outcome directly is what lets the content seam in {@code DataResourceWorker}
     * be driven end to end - resolve, materialise, publish, remove - against the real provider code, with only
     * the SDK boundary replaced.
     *
     * <p>The configured value is recorded alongside the provider, so a later {@link #getContentStore()} that
     * reads the same value from configuration serves this provider from the cache rather than rebuilding it,
     * and a test that changes the configured value gets the ordinary resolution back.
     * @param configuredProvider the configured value this provider is the outcome of resolving
     * @param store the provider to serve, or {@code null} to seat configured database mode
     */
    static void installForTesting(String configuredProvider, ContentStore store) {
        // Canonicalised exactly as resolve() canonicalises it, so what is seated here is the entry a
        // later lookup of the same configured value finds rather than a second one beside it.
        String provider = (configuredProvider == null ? "" : configuredProvider.trim()).toLowerCase(Locale.ROOT);
        closeQuietly(RESOLUTIONS.put(provider, Resolution.succeeded(provider, store)));
    }

    // -------------------------------------------------------------------------------------------
    // The storage-aware bridge. The type documentation explains how the pieces fit together and
    // which frozen call site each one serves.
    // -------------------------------------------------------------------------------------------

    /**
     * Derives the durable storage key of a file-backed data resource.
     *
     * <p>The key is flat, provider-relative and never starts with a separator, because an object
     * store reads a leading separator as an empty first path segment. It is a pure function of its
     * arguments, so the key a write publishes under is by construction the key a later read of the
     * persisted {@code objectInfo} derives - including once the staging directory the write used has
     * been removed, because {@link #STAGING_SEGMENT} and the identifier following it are spliced out.
     *
     * <p>The six file-backed type identifiers map as follows, mirroring how
     * {@code DataResourceWorker.getContentFile} and {@code DataServices.createFileMethod} resolve
     * each one on local disk today.
     * <ul>
     *   <li>{@code LOCAL_FILE} and {@code LOCAL_FILE_BIN} hold an absolute location, so the key is
     *       that location made relative to {@code ofbiz.home}. An empty identifier is read the same
     *       way, which is how {@code DataServices.createFileMethod} reads it.</li>
     *   <li>{@code OFBIZ_FILE} and {@code OFBIZ_FILE_BIN} hold a location that is already relative
     *       to {@code ofbiz.home}, so the key is that location.</li>
     *   <li>{@code CONTEXT_FILE} and {@code CONTEXT_FILE_BIN} hold a location relative to a separate
     *       context root that a single key cannot otherwise express: the key carries the
     *       {@value #CONTEXT_KEY_PREFIX} prefix and the context root's own identity, so that two
     *       webapps holding the same relative location address different objects.</li>
     * </ul>
     *
     * @param dataResourceTypeId the {@code DataResource.dataResourceTypeId}; empty reads as
     *     {@code LOCAL_FILE}
     * @param objectInfo the {@code DataResource.objectInfo} location, whose shape this refactor
     *     leaves untouched
     * @param contextRoot the context root, needed only by the {@code CONTEXT_FILE} pair
     * @return the storage key, never null and never empty
     * @throws GeneralException if {@code objectInfo} is empty, if a {@code CONTEXT_FILE} arrives
     *     without a context root, if the location traverses above its root or names no content, if
     *     {@code ofbiz.home} is unset, or if the type identifier is not one of the six
     */
    public static String storageKeyFor(String dataResourceTypeId, String objectInfo, String contextRoot)
            throws GeneralException {
        if (UtilValidate.isEmpty(objectInfo)) {
            throw new GeneralException("Cannot derive a content storage key: objectInfo is null or empty");
        }
        String type = dataResourceTypeId == null ? "" : dataResourceTypeId.trim();
        String location = slashed(objectInfo);
        if (TYPE_OFBIZ_FILE.equals(type) || TYPE_OFBIZ_FILE_BIN.equals(type)) {
            return flatKey(location);
        }
        if (TYPE_CONTEXT_FILE.equals(type) || TYPE_CONTEXT_FILE_BIN.equals(type)) {
            String root = requiredContextRoot(contextRoot);
            return flatKey(CONTEXT_KEY_PREFIX + "/" + contextIdentity(root) + "/" + location);
        }
        if (TYPE_LOCAL_FILE.equals(type) || TYPE_LOCAL_FILE_BIN.equals(type) || type.isEmpty()) {
            return flatKey(relativeToHome(location));
        }
        throw new GeneralException("Cannot derive a content storage key for dataResourceTypeId ["
                + type + "]: it is not one of the six file backed types");
    }

    /**
     * Materialises the stored content of a file-backed data resource as a real local file, and
     * arranges for whatever the caller writes through that file to be published.
     *
     * <p>It deliberately serves a read caller and a write caller with one behaviour, because a caller
     * that is handed a {@link File} cannot be told apart from one that writes through it. A caller
     * that only reads leaves the file untouched and nothing is published;
     * a caller that writes through it - {@code DataServices.createBinaryFileMethod} and
     * {@code updateBinaryFileMethod} open a {@code FileOutputStream} on exactly this file - has its
     * bytes published when the transaction commits. Either way the local file is removed once the
     * transaction completes, so nothing durable is left on the instance.
     *
     * <p>{@code DataResourceWorker.getContentFile} deliberately does <em>not</em> call this method,
     * and the comment at that seam records why: reconciling the provider against the worker's own
     * resolution also carries the write direction, so a caller that writes through the returned file
     * with no transaction to hang a publication on is still published on the next resolution, and it
     * leaves every allow-list and boundary check where it has always been rather than moving them
     * behind an early return. This method is the transaction-scoped alternative, for a caller that
     * has a transaction and does not need those guards repeated.
     *
     * <p>A local file that is already present is treated as the content of record and is not
     * overwritten from the store, because within one transaction it is content that has been staged
     * but not yet published - which is precisely the state
     * {@code DataServices.createFileMethod} leaves behind after writing through
     * {@code new File(objectInfo)}. Such a file is always published, whereas a file this method
     * fetched is published only if it changed.
     *
     * @param dataResourceTypeId the {@code DataResource.dataResourceTypeId}
     * @param objectInfo the {@code DataResource.objectInfo} location
     * @param contextRoot the context root, needed only by the {@code CONTEXT_FILE} pair
     * @return the local file holding the content, or {@code null} in database mode, in which case
     *     the caller keeps resolving the location itself exactly as before
     * @throws GeneralException if the key or the local location cannot be resolved, if the location
     *     is outside the configured allow list, or if the store cannot be read
     * @throws FileNotFoundException if no content is stored under the derived key, which is the same
     *     answer the pre-existing local path gives for a missing file
     */
    public static File materialiseContentFile(String dataResourceTypeId, String objectInfo, String contextRoot)
            throws GeneralException, FileNotFoundException {
        ContentStore store = getContentStore();
        if (store == null) {
            return null;
        }
        String key = storageKeyFor(dataResourceTypeId, objectInfo, contextRoot);
        File local = localFileFor(dataResourceTypeId, objectInfo, contextRoot);
        boolean alreadyStaged = local.isFile();
        if (!alreadyStaged) {
            fetchInto(store, key, local, objectInfo);
        }
        registerOrWarn(new ContentFilePublication(key, local, alreadyStaged), "content for key [" + key + "]");
        return local;
    }

    /**
     * Opens the stored content of a file-backed data resource as a stream.
     *
     * <p>This is what {@code DataResourceWorker.renderFile} needs: it only copies bytes through to
     * an {@code Appendable}, so it never needs a file and nothing is ever published from here.
     *
     * @param dataResourceTypeId the {@code DataResource.dataResourceTypeId}
     * @param objectInfo the {@code DataResource.objectInfo} location
     * @param contextRoot the context root, needed only by the {@code CONTEXT_FILE} pair
     * @return a fresh stream the caller owns and closes, or {@code null} in database mode, in which
     *     case the caller keeps resolving the location itself exactly as before
     * @throws GeneralException if the key cannot be derived or the provider rejects it
     * @throws IOException if the stream cannot be opened; a {@link FileNotFoundException} means no
     *     content is stored under the derived key
     */
    public static InputStream openContentStream(String dataResourceTypeId, String objectInfo, String contextRoot)
            throws GeneralException, IOException {
        ContentStore store = getContentStore();
        if (store == null) {
            return null;
        }
        return store.openStream(storageKeyFor(dataResourceTypeId, objectInfo, contextRoot));
    }

    /**
     * Publishes a staged local file to the active provider immediately and removes it.
     *
     * <p>Publication is normally driven by the transaction, so this exists for the caller that has
     * no transaction to hang it on, and for a caller that wants the content stored before it
     * returns. It is idempotent in effect: publishing the same bytes twice leaves the same object.
     *
     * @param dataResourceTypeId the {@code DataResource.dataResourceTypeId}
     * @param objectInfo the {@code DataResource.objectInfo} location
     * @param contextRoot the context root, needed only by the {@code CONTEXT_FILE} pair
     * @return {@code true} when content was published, {@code false} in database mode
     * @throws GeneralException if the key or the local location cannot be resolved, or if the store
     *     refuses the write
     * @throws FileNotFoundException if there is no staged local file to publish
     */
    public static boolean publishContentFile(String dataResourceTypeId, String objectInfo, String contextRoot)
            throws GeneralException, FileNotFoundException {
        ContentStore store = getContentStore();
        if (store == null) {
            return false;
        }
        String key = storageKeyFor(dataResourceTypeId, objectInfo, contextRoot);
        File local = localFileFor(dataResourceTypeId, objectInfo, contextRoot);
        if (!local.isFile()) {
            throw new FileNotFoundException("No file found: " + objectInfo);
        }
        publish(key, local);
        discard(local);
        return true;
    }

    /**
     * Allocates the directory an upload is staged in, replacing the numbered local fan-out with flat
     * keys for the duration of the upload.
     *
     * <p>This is what {@code DataResourceWorker.getDataResourceContentUploadPath} needs, and it is
     * the piece that captures the two writers which never consult this package:
     * {@code DataServices.createFileMethod} and {@code updateFileMethod} compute their target with
     * {@code new File(objectInfo)}, and {@code objectInfo} is always built from the path returned
     * here - {@code DataServicesScript.attachLocalFileToDataResource} appends
     * {@code /<dataResourceId>.<extension>} to it and stores the result. Everything written into the
     * directory is therefore published when the transaction commits, and removed once it completes.
     *
     * <p>The directory is unique to this allocation, which is what makes publishing everything in it
     * safe while other uploads are in flight. Its name never reaches a key, because
     * {@link #storageKeyFor(String, String, String)} splices the staging segment out, so keys stay
     * flat and stable: an object store has no directories to fan out into and no per-directory limit
     * to respect, so the {@code content.upload.max.files} rotation that exists to keep local
     * directory listings small has nothing to do here.
     *
     * @param initialPath the configured {@code content.upload.path.prefix}, in the same shape the
     *     pre-existing method receives it
     * @param absolute {@code true} for the absolute form {@code LOCAL_FILE} uploads use,
     *     {@code false} for the {@code ofbiz.home}-relative form {@code OFBIZ_FILE} uploads use
     * @return the staging path in the requested form, or {@code null} in database mode, in which
     *     case the caller keeps allocating the location itself exactly as before
     * @throws GeneralRuntimeException if the staging directory cannot be allocated; the pre-existing
     *     method declares no checked exception, and falling back to a local directory would store
     *     content the provider never receives
     */
    public static String uploadStagingPath(String initialPath, boolean absolute) {
        ContentStore store;
        try {
            store = getContentStore();
        } catch (GeneralException e) {
            // The frozen caller - DataResourceWorker.getDataResourceContentUploadPath - declares no
            // checked exception, so a configured value that cannot be honoured is reported unchecked
            // rather than swallowed. Returning null here would send the upload to a local directory
            // the deployment did not ask for, which is the very fail-open behaviour this refuses.
            throw new GeneralRuntimeException("No content storage provider could be resolved from "
                    + ContentStoreSupport.PROPERTY_RESOURCE + ":" + PROPERTY_PROVIDER
                    + ", so no upload staging location can be allocated", e);
        }
        if (store == null) {
            return null;
        }
        String prefix = initialPath == null ? "" : slashed(initialPath);
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        String relative = prefix + "/" + STAGING_SEGMENT + "/" + STAGING_SEQUENCE.incrementAndGet();
        try {
            String located = requiredHome() + relative;
            File directory = requireResolved(located, FileUtil.getFile(located));
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
                throw new GeneralException("Cannot create the content staging directory [" + located + "]");
            }
            SecurityUtil.checkOfbizFileAllowList(directory);
            registerOrWarn(new StagedUploadPublication(directory), "uploads staged in [" + relative + "]");
            return absolute ? directory.getAbsolutePath().replace('\\', '/') : relative;
        } catch (GeneralException e) {
            throw new GeneralRuntimeException("Cannot allocate a content staging directory at [" + relative + "]", e);
        }
    }

    /**
     * Resolves the local file a file-backed data resource occupies, without requiring it to exist.
     *
     * <p>The resolution and every guard mirror {@code DataResourceWorker.getContentFile}, which is
     * the point: a staged file has to sit exactly where the frozen callers compute it, otherwise
     * their {@code new File(objectInfo)} and {@code FileOutputStream} would write somewhere else.
     * Only the existence check is left out, because staging a write starts with a file that is not
     * there yet.
     *
     * @param dataResourceTypeId the {@code DataResource.dataResourceTypeId}; empty reads as
     *     {@code LOCAL_FILE}
     * @param objectInfo the {@code DataResource.objectInfo} location
     * @param contextRoot the context root, needed only by the {@code CONTEXT_FILE} pair
     * @return the local file, which need not exist, never null
     * @throws GeneralException if the location is not well formed, is not absolute where it has to
     *     be, is outside the configured allow list, escapes the context root, or carries a type
     *     identifier that is not one of the six
     */
    private static File localFileFor(String dataResourceTypeId, String objectInfo, String contextRoot)
            throws GeneralException {
        String type = dataResourceTypeId == null ? "" : dataResourceTypeId.trim();
        String location = slashed(objectInfo);
        if (TYPE_OFBIZ_FILE.equals(type) || TYPE_OFBIZ_FILE_BIN.equals(type)) {
            String home = requiredHome();
            String located = home + separatorBetween(home, location) + location;
            File file = requireResolved(located, FileUtil.getFile(located));
            SecurityUtil.checkOfbizFileAllowList(file);
            return file;
        }
        if (TYPE_CONTEXT_FILE.equals(type) || TYPE_CONTEXT_FILE_BIN.equals(type)) {
            String root = requiredContextRoot(contextRoot);
            String located = root + separatorBetween(root, location) + location;
            File file = requireResolved(located, FileUtil.getFile(located));
            // The boundary check comes before anything touches the file, exactly as
            // DataResourceWorker.getContentFile orders it for CONTEXT_FILE content.
            FileSystemContentStore.checkFileBoundary(file, root);
            return file;
        }
        if (TYPE_LOCAL_FILE.equals(type) || TYPE_LOCAL_FILE_BIN.equals(type) || type.isEmpty()) {
            File file = requireResolved(location, FileUtil.getFile(location));
            if (!file.isAbsolute()) {
                throw new GeneralException("File (" + location + ") is not absolute, which LOCAL_FILE content has to be");
            }
            SecurityUtil.checkLocalFileAllowList(file);
            return file;
        }
        throw new GeneralException("Cannot resolve a local file for dataResourceTypeId [" + type
                + "]: it is not one of the six file backed types");
    }

    /**
     * Fetches a stored object into a local staging file, creating the directory holding it.
     *
     * @param store the active provider
     * @param key the storage key to read
     * @param local the local file to stage the content in
     * @param objectInfo the location to name in a not-found report, so that the message keeps the
     *     shape the pre-existing local path produces
     * @throws GeneralException if the store cannot be read or the local file cannot be written
     * @throws FileNotFoundException if nothing is stored under the key
     */
    private static void fetchInto(ContentStore store, String key, File local, String objectInfo)
            throws GeneralException, FileNotFoundException {
        byte[] content;
        try {
            content = store.get(key);
        } catch (FileNotFoundException e) {
            FileNotFoundException absent = new FileNotFoundException("No file found: " + objectInfo);
            absent.initCause(e);
            throw absent;
        } catch (IOException e) {
            throw new GeneralException("Cannot read content for key [" + key + "]: " + e.getMessage(), e);
        }
        File parent = local.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new GeneralException("Cannot create the directory holding staged content [" + parent.getPath() + "]");
        }
        try {
            Files.write(local.toPath(), content);
        } catch (IOException e) {
            throw new GeneralException("Cannot stage content for key [" + key + "] at ["
                    + local.getPath() + "]: " + e.getMessage(), e);
        }
    }

    /**
     * Writes a staged local file to the active provider.
     *
     * <p>The whole file is read into memory because the storage contract is byte oriented; that is
     * the shape of {@link ContentStore#put(String, byte[])} and of the data-resource content it
     * carries.
     *
     * @param key the storage key to write under
     * @param staged the staged local file to publish
     * @throws GeneralException if no provider is active, if the file cannot be read, or if the store
     *     refuses the write
     */
    private static void publish(String key, File staged) throws GeneralException {
        ContentStore store = getContentStore();
        if (store == null) {
            throw new GeneralException("Cannot publish content for key [" + key
                    + "]: no content storage provider is active");
        }
        try {
            store.put(key, Files.readAllBytes(staged.toPath()));
        } catch (IOException e) {
            throw new GeneralException("Cannot publish content for key [" + key + "] from ["
                    + staged.getPath() + "]: " + e.getMessage(), e);
        }
    }

    /**
     * Removes a staged local file, reporting rather than propagating a failure.
     *
     * <p>Leaving a staged file behind is untidy but harmless - the next materialisation of the same
     * location publishes it - so a failure here must never turn into a failed request or, worse, an
     * exception thrown out of a transaction completion callback.
     *
     * @param staged the staged local file to remove
     */
    private static void discard(File staged) {
        try {
            Files.deleteIfExists(staged.toPath());
        } catch (IOException e) {
            Debug.logWarning("Cannot remove the staged content file [" + staged.getPath() + "]: "
                    + e.getMessage() + ". It will be published and removed by the next access to the"
                    + " same location.", MODULE);
        }
    }

    /**
     * Registers a publication with the current transaction, reporting when there is none.
     *
     * @param publication the publication to register
     * @param what a description of what would have been published, for the report
     * @throws GeneralException if the transaction refuses the registration
     */
    private static void registerOrWarn(Synchronization publication, String what) throws GeneralException {
        if (transactionInPlace()) {
            try {
                TransactionUtil.registerSynchronization(publication);
                return;
            } catch (GenericTransactionException e) {
                throw new GeneralException("Cannot register the publication of " + what
                        + " with the current transaction", e);
            }
        }
        Debug.logWarning("No transaction is in place, so " + what + " will not be published when one commits."
                + " A caller writing content outside a transaction has to call"
                + " ContentStoreFactory.publishContentFile itself.", MODULE);
    }

    /**
     * Reports whether a transaction is in place to hang a publication on.
     *
     * @return {@code true} when a transaction is in place
     * @throws GeneralException if the transaction manager cannot be asked
     */
    private static boolean transactionInPlace() throws GeneralException {
        try {
            return TransactionUtil.isTransactionInPlace();
        } catch (GenericTransactionException e) {
            throw new GeneralException("Cannot determine whether a transaction is in place", e);
        } catch (IllegalStateException e) {
            // This is what TransactionFactoryLoader answers while the entity container has not
            // started a transaction factory - during a unit test, or in any process that uses the
            // Content component without the entity container. There is then no transaction
            // infrastructure at all, which for publication purposes is the same situation as no
            // transaction being in place, so it is reported the same way rather than as a failure.
            Debug.logVerbose("No transaction factory is initialised, so no publication can be"
                    + " registered: " + e.getMessage(), MODULE);
            return false;
        }
    }

    /**
     * Derives the storage key of a file found in a staging directory.
     *
     * <p>The path alone is enough: a staged upload is either {@code LOCAL_FILE} content, whose
     * absolute location is made relative to {@code ofbiz.home}, or {@code OFBIZ_FILE} content, whose
     * location already is - and both forms of the same upload reduce to the same flat key.
     * {@code CONTEXT_FILE} uploads never come through here, because
     * {@code DataServicesScript.attachLocalFileToDataResource} takes their root from the request
     * rather than from the upload-path seam.
     *
     * @param staged the staged local file
     * @return the storage key to publish it under
     * @throws GeneralException if the key cannot be derived
     */
    private static String stagedKeyOf(File staged) throws GeneralException {
        return flatKey(relativeToHome(slashed(staged.getAbsolutePath())));
    }

    /**
     * Derives the key-space identity of a {@code CONTEXT_FILE} context root.
     *
     * <p>Two webapps routinely hold content at the same relative location - a {@code logo.png} in
     * each - and a single key could not tell them apart, which is exactly the gap a separate context
     * root opens. Reducing the root to its {@code ofbiz.home}-relative form gives each one its own
     * stable branch of the key space without leaking an absolute path into it.
     *
     * @param contextRoot the context root, already normalised and known not to be empty
     * @return the identity to place in the key, never empty
     * @throws GeneralException if the root cannot be reduced to a usable identity
     */
    private static String contextIdentity(String contextRoot) throws GeneralException {
        String root = contextRoot;
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        return flatKey(relativeToHome(root));
    }

    /**
     * Normalises the context root a {@code CONTEXT_FILE} location resolves against, refusing an
     * absent one.
     *
     * <p>A root of nothing but whitespace is refused with the same report as an absent one, because
     * neither can identify a webapp and reporting them differently would only obscure the cause.
     *
     * @param contextRoot the context root as the caller supplied it
     * @return the root trimmed and written with forward separators, never empty
     * @throws GeneralException if there is no usable context root
     */
    private static String requiredContextRoot(String contextRoot) throws GeneralException {
        String root = contextRoot == null ? "" : slashed(contextRoot);
        if (root.isEmpty()) {
            throw new GeneralException("Cannot find CONTEXT_FILE with an empty context root!");
        }
        return root;
    }

    /**
     * Normalises a location for key derivation and path joining.
     *
     * @param value the location to normalise
     * @return the location trimmed and written with forward separators
     */
    private static String slashed(String value) {
        return value.trim().replace('\\', '/');
    }

    /**
     * Supplies the separator to place between a prefix and a location, or nothing when one side
     * already provides it.
     *
     * <p>The rule is copied from {@code DataResourceWorker.getContentFile} and
     * {@code DataServices.createFileMethod}, which both compute it this way, so that a staged file
     * lands on the very path those callers compute.
     *
     * @param prefix the prefix the location is joined to, never empty
     * @param location the location being joined
     * @return {@code "/"} or the empty string
     */
    private static String separatorBetween(String prefix, String location) {
        if (location.indexOf('/') == 0 || prefix.lastIndexOf('/') == prefix.length() - 1) {
            return "";
        }
        return "/";
    }

    /**
     * Makes a location relative to the OFBiz home directory when it lies inside it.
     *
     * @param location the location to reduce, written with forward separators
     * @return the location relative to {@code ofbiz.home}, or unchanged when it lies outside
     * @throws GeneralException if {@code ofbiz.home} is unset or the location names it exactly
     */
    private static String relativeToHome(String location) throws GeneralException {
        String home = slashed(requiredHome());
        while (home.endsWith("/")) {
            home = home.substring(0, home.length() - 1);
        }
        if (location.equals(home)) {
            throw new GeneralException("Cannot derive a content storage key from [" + location
                    + "]: it names the OFBiz home directory itself");
        }
        if (location.startsWith(home + "/")) {
            return location.substring(home.length());
        }
        return location;
    }

    /**
     * Reduces a location to a flat storage key.
     *
     * <p>The staging run is spliced out first, so a staged write and a later read agree; repeated
     * and leading separators are then removed, because a key starting with one would create an
     * empty-named top-level entry in an object store; and an upward traversal is refused outright
     * rather than left for a provider to catch, so that a key can never address content outside the
     * store's own root.
     *
     * @param location the location to reduce, written with forward separators
     * @return the flat storage key
     * @throws GeneralException if the location traverses upwards or names no content
     */
    private static String flatKey(String location) throws GeneralException {
        String key = spliceStaging(location);
        while (key.contains("//")) {
            key = key.replace("//", "/");
        }
        while (key.startsWith("/")) {
            key = key.substring(1);
        }
        for (String segment : key.split("/")) {
            if ("..".equals(segment)) {
                throw new GeneralException("Cannot derive a content storage key from [" + location
                        + "]: it traverses above its root");
            }
        }
        if (key.isEmpty() || key.endsWith("/")) {
            throw new GeneralException("Cannot derive a content storage key from [" + location
                    + "]: it does not name content");
        }
        return key;
    }

    /**
     * Removes the staging segment and the allocation identifier following it from a location.
     *
     * <p>This is what keeps a key stable across the life of the content: the directory an upload was
     * staged in is an implementation detail of one transaction and is gone shortly afterwards, so it
     * must not appear in the key a later read derives from the persisted {@code objectInfo}.
     *
     * @param location the location to reduce, written with forward separators
     * @return the location without its staging run, unchanged when it has none
     */
    private static String spliceStaging(String location) {
        String marker = STAGING_SEGMENT + "/";
        int start = location.indexOf(marker);
        while (start > 0 && location.charAt(start - 1) != '/') {
            start = location.indexOf(marker, start + 1);
        }
        if (start < 0) {
            return location;
        }
        int end = location.indexOf('/', start + marker.length());
        if (end < 0) {
            return location.substring(0, start);
        }
        return location.substring(0, start) + location.substring(end + 1);
    }

    /**
     * Supplies the OFBiz home directory, which every location resolution needs.
     *
     * @return the value of the {@code ofbiz.home} system property
     * @throws GeneralException if it is unset or blank
     */
    private static String requiredHome() throws GeneralException {
        String home = System.getProperty("ofbiz.home");
        if (UtilValidate.isEmpty(home)) {
            throw new GeneralException("Cannot resolve a content location: the ofbiz.home system property is not set");
        }
        return home;
    }

    /**
     * Checks that a location resolved to a file at all.
     *
     * <p>{@code FileUtil.getFile} is used throughout so that a {@code component://} location keeps
     * resolving as it does today, and it answers {@code null} for a malformed one, so every result
     * passes through here before it is dereferenced.
     *
     * @param located the location that was resolved, for the report
     * @param resolved the resolution result, possibly null
     * @return the resolved file, never null
     * @throws GeneralException if the location did not resolve
     */
    private static File requireResolved(String located, File resolved) throws GeneralException {
        if (resolved == null) {
            throw new GeneralException("Cannot resolve content location [" + located
                    + "]: it is not a well formed file location");
        }
        return resolved;
    }

    /**
     * Publishes one materialised content file when the transaction that produced it commits.
     *
     * <p>Registered by {@link #materialiseContentFile(String, String, String)} for every file it
     * hands out, because {@code DataResourceWorker.getContentFile} cannot tell a read from a write.
     * A file that was fetched from the store is published only when it changed, which is what makes
     * a read cost nothing; a file that was already staged is always published, because it holds
     * content that has not reached the store yet.
     */
    private static final class ContentFilePublication implements Synchronization {

        private final String key;
        private final File staged;
        private final boolean alwaysPublish;
        private final long stagedLength;
        private final long stagedModified;

        /**
         * Captures what has to be published and how a write through the file will be recognised.
         *
         * @param key the storage key the file belongs to
         * @param staged the materialised local file
         * @param alwaysPublish {@code true} when the file already held unpublished content
         */
        private ContentFilePublication(String key, File staged, boolean alwaysPublish) {
            this.key = key;
            this.staged = staged;
            this.alwaysPublish = alwaysPublish;
            if (!alwaysPublish) {
                // Backdating a freshly fetched file makes a write through it recognisable even when
                // the write keeps the byte count identical and lands inside one filesystem timestamp
                // tick. A filesystem that refuses the change simply leaves the comparison on the
                // timestamp it did keep, which is the usual, weaker behaviour rather than a failure.
                staged.setLastModified(STAGED_SENTINEL_MODIFIED);
            }
            this.stagedLength = staged.length();
            this.stagedModified = staged.lastModified();
        }

        @Override
        public void beforeCompletion() {
            if (!staged.isFile()) {
                // The file is gone, so there is nothing left to publish. That is the ordinary state
                // after publishContentFile has already published and removed it, and it must not
                // turn into a rollback.
                return;
            }
            if (!alwaysPublish && staged.length() == stagedLength && staged.lastModified() == stagedModified) {
                return;
            }
            try {
                publish(key, staged);
            } catch (GeneralException e) {
                // Publishing before the commit rather than after it is what makes this possible:
                // rolling back is the safe direction, because a DataResource row whose content never
                // reached the store would read as missing content for good, whereas a stored object
                // whose row rolls back is an orphan the next write to the same key replaces.
                throw new GeneralRuntimeException("Cannot publish content for key [" + key
                        + "], so the transaction is rolled back", e);
            }
        }

        @Override
        public void afterCompletion(int status) {
            discard(staged);
        }
    }

    /**
     * Publishes everything staged in one upload directory when the transaction that allocated it
     * commits, then removes the directory.
     *
     * <p>Registered by {@link #uploadStagingPath(String, boolean)}. The directory belongs to that
     * one allocation, so publishing everything in it cannot touch an upload another transaction is
     * still writing.
     */
    private static final class StagedUploadPublication implements Synchronization {

        private final File directory;

        /**
         * Captures the staging directory to publish.
         *
         * @param directory the directory allocated for this upload
         */
        private StagedUploadPublication(File directory) {
            this.directory = directory;
        }

        @Override
        public void beforeCompletion() {
            File[] staged = directory.listFiles();
            if (staged == null) {
                return;
            }
            for (File file : staged) {
                if (!file.isFile()) {
                    continue;
                }
                try {
                    publish(stagedKeyOf(file), file);
                } catch (GeneralException e) {
                    throw new GeneralRuntimeException("Cannot publish the upload staged at ["
                            + file.getPath() + "], so the transaction is rolled back", e);
                }
            }
        }

        @Override
        public void afterCompletion(int status) {
            File[] staged = directory.listFiles();
            if (staged != null) {
                for (File file : staged) {
                    discard(file);
                }
            }
            if (!directory.delete() && directory.isDirectory()) {
                Debug.logWarning("Cannot remove the content staging directory [" + directory.getPath()
                        + "]. It holds no published content and can be removed at any time.", MODULE);
            }
        }
    }

    /**
     * Resolves a configured value onto a provider, serving the cached outcome whenever that value
     * has been resolved before.
     *
     * <p>Package-private rather than private so that the selection contract - database mode resolving to no
     * provider at all, an unrecognised value failing closed instead of degrading to database mode, and a
     * replaced provider being closed - can be exercised directly against a configured value.
     *
     * <p>A refusal is keyed on the configured value exactly as a success is, so an operator who corrects
     * the value in the {@code SystemProperty} entity is served the corrected resolution immediately: the
     * cache is keyed on the value, and a corrected value simply resolves afresh. Re-introducing the bad
     * value is refused again rather than served from a cached provider, and a misconfigured deployment
     * keeps saying so on every content operation instead of decaying into database mode.
     *
     * @param configuredProvider the raw value read from configuration; may be null
     * @return the provider to use, or {@code null} when database storage is to be used
     * @throws GeneralException if the value names no recognised provider, or the provider it names
     *     could not be constructed; the exception is a {@link ContentStoreConfigurationException}
     */
    static ContentStore resolve(String configuredProvider) throws GeneralException {
        // Trimming here rather than relying on the property lookup is deliberate: the
        // EntityUtilProperties database branch returns a SystemProperty value verbatim, so a row
        // padded with whitespace would otherwise never match a provider name. Lower-casing in the
        // same breath yields the single canonical form used from here on, so that the form which
        // selects a provider is by construction the form the outcome is cached under - the two can
        // no longer disagree, as they did while matching ignored case but caching did not.
        String reported = configuredProvider == null ? "" : configuredProvider.trim();
        String provider = reported.toLowerCase(Locale.ROOT);
        // computeIfAbsent runs the mapping function under the map's bin lock, so exactly one
        // provider is built per canonical value however many threads arrive together. That is what
        // rules out two providers being constructed for one value and one of them then being
        // dropped, still holding an open object-storage client. Nothing is built at all on the far
        // more common path where the value has been resolved before, and a refusal is served from
        // the cache exactly as a provider is, so an unusable value is reported once and refused
        // every time thereafter.
        return RESOLUTIONS.computeIfAbsent(provider, canonical -> instantiate(canonical, reported)).require();
    }

    /**
     * Instantiates the provider named by an already-canonical configured value, reporting the
     * outcome once, since the caller caches it.
     *
     * <p>Because the value arrives trimmed and lower-cased, every comparison here is an exact
     * {@code equals} against a lower-case constant: case-insensitive matching happens once, during
     * canonicalisation, instead of being repeated with the risk of one comparison disagreeing with
     * another.
     *
     * <p>This method reports rather than raises, returning the refusal inside the outcome, so that
     * the caller's {@code computeIfAbsent} always completes and always leaves a mapping behind. A
     * failure that escaped instead would leave the map empty, and the next content operation would
     * retry construction and log the same failure again - the outcome would depend on how often it
     * was asked for, which is precisely the non-determinism to avoid. What is reported is still a
     * refusal: the cached outcome raises on every use, so a misconfigured deployment keeps saying so
     * on every content operation rather than decaying into database mode.
     *
     * @param provider the canonical - trimmed and lower-cased - configured value; may be empty
     * @param reported the trimmed configured value as the deployment spelt it, used only when
     *     naming an unrecognised value back to the operator
     * @return the outcome to cache for this value, which is never null and which carries either a
     *     provider, nothing at all for configured database mode, or the reason the value cannot be
     *     honoured - a value naming no provider, and a provider that could not be constructed, are
     *     both refused rather than turned into database storage
     */
    private static Resolution instantiate(String provider, String reported) {
        if (UtilValidate.isEmpty(provider) || PROVIDER_DATABASE.equals(provider)) {
            // The default, and the state of an unmodified checkout: no provider takes part at all
            // and the existing DataResource database-storage path is left completely untouched.
            Debug.logVerbose(EVENT_SELECTED + " provider [" + PROVIDER_DATABASE + "]: the existing DataResource"
                    + " database storage is used unchanged", MODULE);
            return Resolution.succeeded(provider, null);
        }
        if (!PROVIDER_FILESYSTEM.equals(provider) && !PROVIDER_S3.equals(provider)) {
            // Fails closed rather than degrading to the committed default: a typo must not silently
            // redirect every content read and write to a backend the deployment was not provisioned
            // for. The configured value is rendered through ContentStoreUtil.describe, which echoes a
            // short, control-free value so a typo can be corrected, and replaces anything else with an
            // opaque reference - so a value such as "s3\nERROR forged" cannot inject a line of its own
            // into the log or the message. It is the value as the deployment spelt it that is echoed,
            // not the lower-cased canonical form, because a mis-cased typo has to be findable in the
            // file it was written in.
            String reason = "Content store provider " + ContentStoreUtil.describe(reported) + " configured by "
                    + ContentStoreSupport.PROPERTY_RESOURCE + ":" + PROPERTY_PROVIDER + " is not a recognised"
                    + " provider; expected one of [" + PROVIDER_DATABASE + ", " + PROVIDER_FILESYSTEM + ", "
                    + PROVIDER_S3 + "]. Content storage is refused rather than silently redirected to ["
                    + PROVIDER_DATABASE + "], which is not what was configured";
            Debug.logError(EVENT_UNUSABLE + " " + reason, MODULE);
            return Resolution.refused(provider, reason);
        }
        try {
            ContentStore store;
            if (PROVIDER_FILESYSTEM.equals(provider)) {
                store = new FileSystemContentStore();
            } else {
                // The object-storage provider is reached only here, and its constructor reads
                // configuration without touching an object-storage client type, so selecting it neither
                // resolves a credential nor opens a connection. Because the JVM resolves this reference
                // lazily, no other path so much as loads the class or the client library behind it.
                store = new S3ContentStore();
            }
            registerShutdownHook();
            Debug.logInfo(EVENT_SELECTED + " provider [" + provider + "] selected by "
                    + ContentStoreSupport.PROPERTY_RESOURCE + ":" + PROPERTY_PROVIDER, MODULE);
            return Resolution.succeeded(provider, store);
        } catch (RuntimeException | LinkageError e) {
            // The requested provider exists but could not be built - an absent client library surfaces
            // as a LinkageError rather than an exception. That is refused rather than absorbed: a
            // deployment whose object-storage client is missing from the classpath must not quietly
            // start writing its content into the database instead. Recording the reason in the outcome
            // keeps a persistent failure from producing a fresh stack trace on every content read or
            // write while still failing every one of them, and only the recognised provider name - one
            // of two literals - reaches the message.
            String reason = "Content store provider [" + provider + "] configured by "
                    + ContentStoreSupport.PROPERTY_RESOURCE + ":" + PROPERTY_PROVIDER + " could not be created ["
                    + e.getClass().getSimpleName() + "]. Content storage is refused rather than silently"
                    + " redirected to [" + PROVIDER_DATABASE + "]";
            Debug.logError(e, EVENT_UNUSABLE + " " + reason, MODULE);
            return Resolution.failed(provider, reason, e);
        }
    }

    /**
     * Registers, at most once, the hook that closes the last resolved provider at JVM shutdown.
     *
     * <p>Registered on first successful construction rather than from a static initialiser, so a
     * deployment in configured database mode - the committed default - installs no hook at all.
     */
    private static void registerShutdownHook() {
        if (!SHUTDOWN_HOOK_REGISTERED.compareAndSet(false, true)) {
            return;
        }
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(ContentStoreFactory::clearCache, SHUTDOWN_THREAD_NAME));
        } catch (IllegalStateException | SecurityException e) {
            // shutdown is already under way, or hooks are not permitted; neither is a reason to refuse a
            // content operation, and in the first case the process is about to exit in any case
            Debug.logWarning(EVENT_CLOSE_FAILED + ": the content store shutdown hook could not be registered ["
                    + e.getClass().getSimpleName() + "]", MODULE);
        }
    }

    /**
     * Closes the provider an outcome held, if it held one, without letting the close failure displace
     * whatever the caller was doing.
     *
     * @param outcome the outcome whose provider is no longer in use; may be null
     */
    private static void closeQuietly(Resolution outcome) {
        if (outcome == null || outcome.store() == null) {
            return;
        }
        try {
            outcome.store().close();
        } catch (GeneralException | IOException | RuntimeException e) {
            // reported rather than propagated: the provider is already unreachable, and failing the
            // caller's content operation because a superseded provider could not be closed would turn a
            // resource-release problem into a functional one
            Debug.logWarning(EVENT_CLOSE_FAILED + " provider [" + ContentStoreSupport.describe(outcome.provider())
                    + "]: [" + e.getClass().getSimpleName() + "]", MODULE);
        }
    }

    /**
     * The immutable pairing of a canonical configured value with what it resolved to.
     *
     * <p>Holding the value together with its outcome in one object is what guarantees a reader can
     * never see a configured value beside an outcome belonging to a different one. At most one of
     * {@code store} and {@code failure} is ever set: both being null is configured database mode, and
     * a non-null {@code failure} is a value that cannot be honoured - either because it names no
     * provider at all, or because the provider it does name could not be constructed.
     *
     * <p>The three factory methods below are the only way an outcome is built, so an outcome pairing
     * a provider with a failure cannot be expressed. {@link #succeeded(String, ContentStore)} is also
     * the seam this package's own tests seat a provider through, which is why it is a named factory
     * rather than a bare constructor call.
     *
     * @param provider the canonical - trimmed and lower-cased - configured value this outcome was
     *     resolved from
     * @param store the provider that value resolved to, or {@code null} for database storage and for
     *     a value that cannot be honoured
     * @param failure why the value cannot be honoured, or {@code null} when it can
     * @param cause the construction failure behind {@code failure}, or {@code null} when the value
     *     was refused without anything having been constructed
     */
    private record Resolution(String provider, ContentStore store, String failure, Throwable cause) {

        /**
         * Records a value that resolved cleanly, whether or not it produced a provider.
         *
         * @param provider the canonical configured value this outcome was resolved from
         * @param store the provider that value resolved to, or {@code null} for database storage
         * @return an outcome every caller can use as it stands
         */
        static Resolution succeeded(String provider, ContentStore store) {
            return new Resolution(provider, store, null, null);
        }

        /**
         * Records a value that names no recognised provider, which is refused rather than quietly
         * read as database storage.
         *
         * @param provider the canonical configured value this outcome was resolved from
         * @param failure why the value cannot be honoured, already rendered safe to log
         * @return an outcome that refuses every use
         */
        static Resolution refused(String provider, String failure) {
            return new Resolution(provider, null, failure, null);
        }

        /**
         * Records a value that named a recognised provider which could not be constructed.
         *
         * @param provider the canonical configured value this outcome was resolved from
         * @param failure why the provider could not be constructed, already rendered safe to log
         * @param cause the construction failure itself, carried so that a caller reporting the
         *     refusal still names the underlying reason without it being logged again on every later
         *     content operation
         * @return an outcome that refuses every use
         */
        static Resolution failed(String provider, String failure, Throwable cause) {
            return new Resolution(provider, null, failure, cause);
        }

        /**
         * Returns the provider, or refuses the call when the configured value cannot be honoured.
         *
         * @return the resolved provider, or {@code null} for configured database storage
         * @throws GeneralException if the configured value cannot be honoured
         */
        private ContentStore require() throws GeneralException {
            if (failure == null) {
                return store;
            }
            // the specific type so a caller, and a test, can tell a misconfigured deployment from a
            // single failed storage operation; it is a GeneralException, so nothing else changes. A
            // fresh instance on every call, so the stack trace belongs to the thread that is failing
            // now rather than to whichever thread happened to resolve the value first.
            throw cause == null ? new ContentStoreConfigurationException(failure)
                    : new ContentStoreConfigurationException(failure, cause);
        }
    }
}
