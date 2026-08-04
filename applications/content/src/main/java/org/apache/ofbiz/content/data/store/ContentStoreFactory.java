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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.DelegatorFactory;
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
 * <tr><td>anything else</td><td>refused, naming the value and the accepted set</td></tr>
 * </table>
 *
 * <p><strong>{@code null} means database mode.</strong> It is the documented signal that content is
 * held in the {@code DataResource} database columns exactly as it always has been, which is the
 * committed default. There is no sentinel provider type and no {@code Optional}: a caller tests for
 * {@code null} and, finding it, does exactly what it did before this refactor.
 *
 * <p><strong>Only an ABSENT selector defaults; an explicit one is honoured or refused.</strong> An
 * unset or blank {@code content.store.provider} is database mode, which is what keeps an
 * unconfigured deployment working exactly as it always has. A value that is present but is not one
 * of the three accepted tokens is refused, because the alternative is worse than a refusal: a
 * deployment that asked for {@code s3} and mistyped it would start, report success, and write its
 * durable content to a different backend from the one the manifest plainly names - and content
 * written to the wrong place is not something a later restart can put right. The container entry
 * point applies the same rule to {@code OFBIZ_CONTENT_STORE_PROVIDER} before the JVM starts, so the
 * mistake is normally reported there, with the variable named, rather than here.
 *
 * <p>Comparison is trimmed and case-insensitive, so {@code " S3 "} and {@code "Database"} resolve.
 * There are no aliases: {@code fs} and {@code local} are not accepted and are refused like any
 * other unrecognised value.
 *
 * <p><strong>Deployment values come from the property file alone; only tunables may be overridden
 * from the database.</strong> The nine values that decide WHERE durable content is written, WHO
 * it is written as and whether it is stored encrypted - the selector, the bucket, the region, the
 * endpoint, the addressing style, the two credentials and the two server-side-encryption settings -
 * are read from {@code content.properties} only, never from the
 * {@code SystemProperty} entity. They are the values the container entry point validates and
 * renders from the environment, and a second, database-resident control plane over them would be a
 * way to bypass that validation entirely: a row could point a production fleet at a plaintext
 * endpoint, at another bucket, at another principal, or into a bucket with no encryption asked of
 * it, in a change no start-up check ever sees and with no atomicity across the nine. The documented non-secret tunables - the key prefix, the read
 * ceiling, the migration fallback, the deadlines and the retry cap - keep honouring a
 * {@code SystemProperty} row, because they change how the configured store is used rather than
 * which store it is.
 *
 * <p><strong>WHEN a change takes effect: two classes of setting, and they behave differently.</strong>
 * Conflating them was a defect in its own right, because one warning claimed that every
 * {@code content.store.*} change waits for a restart while two of them in fact applied immediately.
 *
 * <ul>
 * <li><em>Construction-bound</em> - {@link #constructionBoundProperties()}. Read once, when the
 * provider for a scope is built: the selector, the bucket, the region, the endpoint, the addressing
 * style, the plaintext-endpoint allowance, the two credentials, the key prefix, the two SDK deadlines,
 * the response-body deadline and the retry cap. These are the ONLY settings the resolution fingerprint
 * covers, and therefore the only ones a change to which is reported as "restart to apply". They are
 * construction-bound because they are sealed into a client, its credential provider and its override
 * configuration, none of which can be replaced under requests already holding it.</li>
 * <li><em>Dynamic</em> - {@link #DYNAMIC_PROPERTIES}. Read on every use, so a change applies at once
 * with no restart: {@code content.store.max.object.size}, {@code content.store.local.fallback} and
 * {@code content.store.local.erase.on.store.miss}.
 * Each is a number or a flag consulted by the operation that needs it; nothing is built from them.
 * They are excluded from the fingerprint precisely so that changing one never produces a "restart to
 * apply" line for a change that is already in force.</li>
 * </ul>
 *
 * <p><strong>One resolution per delegator scope, keyed by the whole configuration.</strong> A
 * resolved provider is kept so that it is constructed once rather than once per request. What is
 * kept is a record holding three things together: the normalised provider value, a digest of every
 * {@code content.store.*} value the providers read, and the provider built from them. Because the
 * three are published as one reference, no caller can be handed a provider that was built from a
 * configuration other than the one just read - which a pair of independently updated fields could
 * not guarantee. Because the record is keyed by delegator name, a {@code SystemProperty} row that
 * configures one tenant's storage never answers another tenant's resolution. Caching the
 * database-mode outcome, including the one an unrecognised value falls back to, is safe precisely
 * because that outcome is not a failure: nothing is poisoned and every later call behaves
 * identically.
 *
 * <p><strong>A provider is configured from the layer that selected it.</strong> The delegator a
 * resolution is asked for is the delegator every overridable {@code content.store.*} tunable is read
 * through, and the deployment values above are read from the property file for both the selector and
 * the provider it selects. Each value therefore reaches the provider from the same layer it reached
 * the selection from, which is what stops a deployment selecting a provider it had not configured,
 * and what makes a change the digest above notices produce a rebuilt provider that honours it.
 *
 * <p><strong>A provider in service is never closed.</strong> A provider owns a client, the connection
 * pool behind it and the streams {@link ContentStore#openStream} has already handed to callers, and
 * nothing here can know that the last of those has been released. So a {@code content.store.*} change
 * made to a running instance does not replace the provider: it is reported once, naming the scope and
 * saying that a restart is what applies it, and the provider already in service continues to answer.
 * The alternative - swapping the record and closing what it displaced - fails in-flight reads against
 * a closed client, including reads whose stream a response is still being written from, which is a
 * worse outcome than a configuration change that waits for the restart every other storage-backend
 * change waits for. Exactly two closes exist, and neither can reach a provider a caller holds: a
 * provider whose construction lost the installation race is closed by the thread that built it, having
 * never been published, and whatever is still held when the JVM stops is closed by a shutdown hook.
 *
 * <p><strong>Storage keys are minted here.</strong> {@link #storeKey} is the one place a key is
 * derived, so that no caller has to know how it is derived, and every provider is keyed the same
 * way: by the {@code ofbiz.home}-relative path of the content. That path is the location the
 * deployment already uses, and it is the one value both resolution seams always have. One
 * derivation for every provider is what makes the providers interchangeable - content published
 * through one can be read through another, so a deployment can move between them - and what keeps
 * the filesystem provider's tree identical to the deployment's own tree. Keeping one deployment's
 * content apart from another's inside a shared bucket is a deployment concern, served by
 * {@code content.store.s3.key.prefix} rather than by the key derivation.
 * {@link #maxObjectSize} bounds what any single read may
 * materialise, {@link #publicationRequired} says whether a local write still has to be handed to the
 * provider, {@link #localFallbackEnabled} decides what a store miss means for a resource this
 * instance holds a file for, and {@link #localRemnantErasureEnabled} decides whether such a resource
 * is erased as well as refused.
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

    /** The prefix every property this package reads shares. */
    private static final String STORE_PROPERTY_PREFIX = "content.store.";

    /**
     * The settings that are read on EVERY use, and therefore take effect without a restart.
     *
     * <p>Each is consulted by the operation that needs it - the read ceiling by a whole-object read and
     * by the publication bound, the fallback flag by a store miss - and nothing is built from either, so
     * a change to one is in force the moment it is made. They are excluded from
     * {@link #fingerprint(Delegator)} for exactly that reason: including them would make a change that
     * is ALREADY in force produce a "restart to apply" warning, which is the opposite of the truth.
     *
     * <p>Every other {@code content.store.*} setting is construction-bound; see
     * {@link #constructionBoundProperties()}.
     */
    static final Set<String> DYNAMIC_PROPERTIES = Set.of(
            "content.store.max.object.size",
            "content.store.local.fallback",
            // Dynamic for a reason of its own: it is the switch a deployment turns on when it has an erasure
            // obligation to satisfy, and requiring a fleet-wide restart to satisfy one would be the wrong
            // answer. Read on every use, so it applies from the next read on every instance.
            "content.store.local.erase.on.store.miss");

    /**
     * The values that decide where durable content is written and which principal writes it, and
     * which are therefore read from {@code content.properties} alone.
     *
     * <p>These are the ones the container entry point validates and renders from the environment,
     * together with the plaintext-endpoint allowance it derives from the deployment profile - the one
     * value that decides whether this deployment's credentials may travel unencrypted, which is a
     * deployment decision by exactly the same argument - and the two server-side-encryption settings,
     * which decide whether the content is stored encrypted at all. Reading them through the
     * {@code SystemProperty} entity as well would put a second
     * control plane over the deployment's storage location and identity - one that no start-up
     * validation sees, that cannot change all ten atomically, and that could send a production
     * fleet's content and credentials to an endpoint the entry point would have refused, or store its
     * content unencrypted. Every other
     * {@code content.store.*} value is a documented tunable and keeps honouring an override; see the
     * class documentation.
     *
     * <p>Package-private because {@link S3ContentStore} reads its share of them through
     * {@link #deploymentValue(String)}, and the two must agree on which values those are.
     */
    static final Set<String> DEPLOYMENT_PROPERTIES = Set.of(
            "content.store.provider",
            "content.store.s3.bucket",
            "content.store.s3.region",
            "content.store.s3.endpoint",
            "content.store.s3.access.key.id",
            "content.store.s3.secret.access.key",
            "content.store.s3.path.style",
            "content.store.s3.insecure.endpoint.allowed",
            // Server-side encryption is a deployment decision for the same reason the endpoint and the
            // credentials are: it says whether this deployment's content is stored encrypted at all, the
            // entry point validates and renders it from the environment, and a SystemProperty row that
            // could turn it off would be a second control plane over that decision which no start-up
            // validation sees.
            "content.store.s3.sse",
            "content.store.s3.sse.kms.key.id");

    /**
     * The ceiling on what a single read may materialise, in bytes.
     *
     * <p>Public because everything that refuses content for exceeding it names this property in the
     * refusal it reports - the two providers when a read is too large, and the write seam when
     * content is too large to publish. An operator reading that line needs to know which setting to
     * change, and the name should come from the one place it is defined rather than be spelled out
     * again.
     */
    public static final String MAX_OBJECT_SIZE_PROPERTY = "content.store.max.object.size";

    /** The property that decides whether a store miss falls back to the local copy of a resource. */
    private static final String LOCAL_FALLBACK_PROPERTY = "content.store.local.fallback";

    /**
     * The setting that turns a refused local remnant into an erased one.
     *
     * <p>Public for the same reason {@link #MAX_OBJECT_SIZE_PROPERTY} is: the erasure the write seam
     * performs names this setting in the line it reports, so an operator reading that line is told which
     * setting produced the erasure - and the name comes from the one place it is defined rather than
     * being spelled out again.
     */
    public static final String LOCAL_REMNANT_ERASURE_PROPERTY = "content.store.local.erase.on.store.miss";

    /**
     * The property that names the tree uploaded content is filed under, read PER DELEGATOR because it is
     * what separates one tenant's content from another's; see
     * {@link #requireDistinctTenantKeyNamespace(String, Delegator)}.
     */
    private static final String UPLOAD_PATH_PREFIX_PROPERTY = "content.upload.path.prefix";

    /**
     * The property that names the prefix every object-store key is written beneath, read per delegator for
     * the same reason.
     */
    private static final String KEY_PREFIX_PROPERTY = "content.store.s3.key.prefix";

    /**
     * The committed answer to a store miss for a resource this instance holds a file for.
     *
     * <p>True, so that selecting a provider never refuses content that predates the selection. See
     * {@link #localFallbackEnabled} for why that is the parity-preserving default and what setting
     * it false buys.
     */
    private static final boolean DEFAULT_LOCAL_FALLBACK = false;

    /**
     * The committed erasure posture: a remnant the store does not hold is refused and reported, and
     * removed only where a deployment has asked for that; see {@link #localRemnantErasureEnabled}.
     */
    private static final boolean DEFAULT_LOCAL_REMNANT_ERASURE = false;

    /** The committed ceiling on a single read, used whenever the configured value is unusable. */
    private static final long DEFAULT_MAX_OBJECT_SIZE = 10485760L;

    /** The smallest ceiling worth honouring: below this no realistic content would be readable. */
    private static final long MINIMUM_MAX_OBJECT_SIZE = 1024L;

    /** The largest ceiling accepted, which is what keeps a mistyped value from meaning "unbounded". */
    private static final long MAXIMUM_MAX_OBJECT_SIZE = 2147483639L;

    /** The scope key a resolution without a delegator is held under; no delegator is ever named this. */
    private static final String GLOBAL_SCOPE = "";

    /**
     * The resolution held for each delegator scope, or for {@link #GLOBAL_SCOPE} when there is no
     * delegator. Bounded by the number of delegators a deployment runs, and every entry is replaced
     * rather than added to when that scope's configuration changes.
     */
    private static final ConcurrentMap<String, Resolution> RESOLUTIONS = new ConcurrentHashMap<>();

    /**
     * The {@code property=value} pairs already reported as unusable, so that a misconfigured bound
     * is reported once rather than on every read. Keyed by the value as well as the property, so a
     * value corrected and then mistyped again is reported again. Bounded by the number of distinct
     * unusable values an operator writes, which is a configuration edit rather than a request.
     */
    private static final Set<String> REPORTED_UNUSABLE_VALUES = ConcurrentHashMap.newKeySet();

    /**
     * How a provider is built for a recognised selector, so that a test can build one without the
     * configuration, the credentials and the network a deployment's provider needs.
     */
    interface ProviderConstruction {
        /**
         * Builds the provider a selector names.
         *
         * @param provider the normalised selector, {@code filesystem} or {@code s3}
         * @param delegator the delegator every configuration value is read through; may be null
         * @return the provider to install
         * @throws GeneralException if the provider cannot be built, which the factory reports exactly as
         *     it reports a real construction failure
         */
        ContentStore create(String provider, Delegator delegator) throws GeneralException;
    }

    /**
     * The provider construction a test installed, and {@code null} in every deployment - so a deployed
     * instance always builds the provider its configuration describes.
     *
     * <p>The seam exists because the two things a test most needs to observe about this factory cannot be
     * observed from outside it: that one configuration is constructed exactly once however many threads
     * race for it, and that a provider which lost that race, or was displaced by a configuration change,
     * is closed. Both are statements about construction and closure, so a test has to be able to count
     * constructions and see closures - which means supplying the construction. It is also what lets the
     * object-store provider be exercised end to end with a mocked SDK client, offline and with no
     * credential anywhere.
     *
     * <p>Held in an {@link AtomicReference} rather than a plain field so that
     * a value installed by one thread is visible to the request threads a concurrency test starts.
     */
    private static final AtomicReference<ProviderConstruction> CONSTRUCTION_OVERRIDE = new AtomicReference<>(null);

    static {
        registerShutdownCleanup();
    }

    private ContentStoreFactory() { }

    /**
     * Installs, or removes, the provider construction a test supplies.
     *
     * <p>Package-private, like {@link #clearCache()}, because the only caller is this package's own test.
     * Every held resolution is discarded and closed first, so the next resolution goes through the
     * construction just installed rather than answering from a provider the previous one built.
     *
     * @param construction the construction to use, or {@code null} to restore the configured one
     */
    static void installConstructionForTesting(ProviderConstruction construction) {
        CONSTRUCTION_OVERRIDE.set(construction);
        closeEveryResolution();
    }

    /**
     * Returns the configured content-storage provider.
     *
     * @return the provider to store and read content through, or {@code null} when content is held
     *     in the database, which is the default when nothing is configured
     * @throws GeneralException if a provider was selected but cannot be constructed from the
     *     configuration it needs - a missing bucket or region, an unusable endpoint or key prefix,
     *     or exactly one half of a credential pair
     */
    public static ContentStore getContentStore() throws GeneralException {
        return resolve(null);
    }

    /**
     * Reads one of the values that decide where durable content is written, from
     * {@code content.properties} and from nowhere else.
     *
     * <p>Package-private because {@link S3ContentStore} reads the object-store half of
     * {@link #DEPLOYMENT_PROPERTIES} through it: one accessor for these values means no provider can
     * accidentally acquire the {@code SystemProperty} precedence this deliberately does not have.
     * Refuses a name that is not one of them, so the distinction cannot be blurred by a later caller.
     *
     * @param name the property to read; must be one of {@link #DEPLOYMENT_PROPERTIES}
     * @return the configured value, or "" when the property is absent; never null
     */
    static String deploymentValue(String name) {
        if (!DEPLOYMENT_PROPERTIES.contains(name)) {
            throw new IllegalArgumentException("[" + name + "] is not a content-storage deployment property, so it"
                    + " must be read through propertyValue so that a SystemProperty override reaches it");
        }
        return UtilProperties.getPropertyValue(PROPERTY_RESOURCE, name);
    }

    /**
     * Returns the configured content-storage provider, honouring a {@code SystemProperty} override of
     * the documented tunables.
     *
     * <p>Identical to {@link #getContentStore()} except that the key prefix, the read ceiling, the
     * migration fallback, the deadlines and the retry cap may also come from the
     * {@code SystemProperty} entity, exactly as every other OFBiz property may. The selector and the
     * object-store deployment values are read from {@code content.properties} either way; see the
     * class documentation for why.
     *
     * @param delegator the delegator a {@code SystemProperty} override of a tunable is read through,
     *     and whose tenancy scopes an object-store key; may be null
     * @return the provider to store and read content through, or {@code null} for database mode
     * @throws GeneralException if the selector names something other than the three accepted values,
     *     or if a provider was selected but cannot be constructed
     */
    public static ContentStore getContentStore(Delegator delegator) throws GeneralException {
        return resolve(delegator);
    }

    /**
     * Discards every held resolution, closing each provider, so that the next resolution reads the
     * configuration again.
     *
     * <p>Package-private because the only caller is {@code ContentStoreFactoryTest}, which varies
     * the {@code content.store.*} configuration in memory and needs each case resolved rather than
     * answered from the previous case's cache. Closing rather than dropping is what keeps a test
     * that resolves the object-store provider from leaving an SDK client and its connection pool
     * behind for the rest of the test run.
     */
    static void clearCache() {
        closeEveryResolution();
    }

    /**
     * Forgets every value already reported as unusable, so the next unusable value is reported again.
     *
     * <p>Package-private, and for the same reason as {@link #clearCache()}: this class deliberately
     * reports a misconfigured value once per JVM rather than once per read, which is right for a
     * deployment and wrong for a test suite. Without this, whether a test observes the warning it
     * asserts on depends on whether an earlier test in the same JVM happened to configure the same
     * property to the same value - so the suite passes or fails according to the order the runner
     * chose, and a genuine regression in the reporting can be masked by an unrelated test that ran
     * first. Resetting after every test makes each case start from the same state as the first one.
     *
     * <p>A deterministic hook rather than a time- or count-based expiry, because a test needs the
     * state to be gone at a known point, not eventually.
     */
    static void clearReportedUnusableValues() {
        REPORTED_UNUSABLE_VALUES.clear();
    }

    /**
     * Returns the ceiling, in bytes, on what a single read may materialise.
     *
     * <p>Read from {@code content.store.max.object.size}. A value that is not a number, is not
     * positive or lies outside {@value #MINIMUM_MAX_OBJECT_SIZE}..{@value #MAXIMUM_MAX_OBJECT_SIZE}
     * is reported once and the committed default of {@value #DEFAULT_MAX_OBJECT_SIZE} bytes applies,
     * because a bound that silently became "unbounded" would defeat the purpose of having one. The
     * upper limit is {@code Integer.MAX_VALUE - 8}, the largest array the JVM can be asked for, so a
     * whole-object read can always report the refusal rather than fail allocating.
     *
     * @param delegator the delegator a {@code SystemProperty} override is read through; may be null,
     *     in which case only {@code content.properties} is consulted
     * @return the maximum number of bytes one read may hold at once, always positive
     */
    public static long maxObjectSize(Delegator delegator) {
        return boundedLong(MAX_OBJECT_SIZE_PROPERTY, delegator, DEFAULT_MAX_OBJECT_SIZE, MINIMUM_MAX_OBJECT_SIZE,
                MAXIMUM_MAX_OBJECT_SIZE);
    }

    /**
     * Reports whether a store miss may fall back to the content the instance holds locally.
     *
     * <p>Read from {@code content.store.local.fallback}, which is {@value #DEFAULT_LOCAL_FALLBACK}
     * in the committed configuration. The store is the authority wherever it holds content - an
     * object in the store always wins over a local copy of the same resource - and this setting
     * decides only what a store <em>miss</em> means for a resource this instance happens to hold a
     * file for.
     *
     * <p><strong>The committed default is the strict, fail-closed one, and this is why.</strong> A
     * local copy that answers for a key the store does not hold is indistinguishable from a local
     * copy that answers for a key the store no longer holds: the deployment deletes a document, the
     * object goes, and every instance that had reconstructed that document from the store goes on
     * serving its own copy of it (CWE-212, CWE-459). One is a migration convenience and the other is
     * a deletion that did not take effect, and nothing at the point of the read can tell them apart.
     * A deletion that fails to erase is the more serious of the two mistakes by a wide margin, so the
     * default is the one that cannot make it: a store miss is refused. Configuring an object store is
     * itself opt-in, so this changes nothing for a deployment that has not configured one - the
     * committed database mode never consults this setting at all.
     *
     * <p>Setting it {@code true} is the MIGRATION WINDOW, and it is deliberately something a
     * deployment asks for: while it is set, content the store does not hold yet - the shipped demo
     * content, and every file-backed resource uploaded before the provider was configured - is served
     * from this instance's own disk, so switching a provider on does not stop a deployment serving
     * content it served the day before. Each such resource is reported once, naming what has yet to
     * be copied. New content needs no bridge, because content is published to the store by the
     * transaction that writes it. Turn it off again once the copy is complete; while it is on, a
     * deletion is not guaranteed to have reached every instance.
     *
     * <p>An unusable value is reported once and the committed default applies, exactly as every
     * other setting of this package treats one: a typo must not decide whether a fleet may serve
     * content its store does not hold.
     *
     * @param delegator the delegator a {@code SystemProperty} override is read through; may be null,
     *     in which case only {@code content.properties} is consulted
     * @return true only when the value reads as {@code true}; false, the committed default, in every
     *     other case
     */
    public static boolean localFallbackEnabled(Delegator delegator) {
        String configured = propertyValue(LOCAL_FALLBACK_PROPERTY, delegator);
        if (UtilValidate.isEmpty(configured)) {
            return DEFAULT_LOCAL_FALLBACK;
        }
        String normalised = configured.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalised)) {
            return true;
        }
        if ("false".equals(normalised)) {
            return false;
        }
        reportUnusableValue(LOCAL_FALLBACK_PROPERTY, configured, "is not true or false");
        return DEFAULT_LOCAL_FALLBACK;
    }

    /**
     * Reports whether a local copy the authoritative store does not hold is to be ERASED rather than
     * merely refused.
     *
     * <p>Read from {@code content.store.local.erase.on.store.miss}, which is
     * {@value #DEFAULT_LOCAL_REMNANT_ERASURE} in the committed configuration, and consulted only where
     * {@link #localFallbackEnabled} is false - a deployment in its migration window is asking for the
     * opposite of erasure, and the two settings cannot contradict each other because the erasure is
     * reached only through the refusal. What it enables is described at
     * {@code DataResourceWorker.eraseLocalRemnant}: without it an authorised deletion removes the
     * object and the deleting instance's file while every other instance keeps the copy it
     * reconstructed, which for a deployment with a retention or erasure obligation is the deletion not
     * having taken effect.
     *
     * <p>Off by default because the evidence is "the store does not hold this key", which is equally
     * what a mistyped bucket or key prefix looks like: on that evidence, erasing would destroy the
     * deployment's only copy of its own content. Turning it on is the deployment stating that the
     * store is the whole of its content, which is a fact only the deployment knows. Read on every use,
     * so it takes effect across a fleet without a restart.
     *
     * @param delegator the delegator a {@code SystemProperty} override is read through; may be null,
     *     in which case only {@code content.properties} is consulted
     * @return true only when the value reads as {@code true}; false, the committed default, in every
     *     other case
     */
    public static boolean localRemnantErasureEnabled(Delegator delegator) {
        String configured = propertyValue(LOCAL_REMNANT_ERASURE_PROPERTY, delegator);
        if (UtilValidate.isEmpty(configured)) {
            return DEFAULT_LOCAL_REMNANT_ERASURE;
        }
        String normalised = configured.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalised)) {
            return true;
        }
        if ("false".equals(normalised)) {
            return false;
        }
        reportUnusableValue(LOCAL_REMNANT_ERASURE_PROPERTY, configured, "is not true or false");
        return DEFAULT_LOCAL_REMNANT_ERASURE;
    }

    /**
     * Reports whether content written to the deployment's own tree still has to be handed to the
     * provider, or whether writing it locally has already placed it there.
     *
     * <p>ONE predicate, and it asks the PROVIDER rather than testing its class. Two overlapping
     * predicates used to answer this with two different {@code instanceof} tests - one calling every
     * provider that was not the filesystem one remote, the other calling only the object store one that
     * needed publication - so a provider added later would have been classified inconsistently by the
     * two and the write seam could disagree with the rest of the package about the same provider. The
     * capability now lives on {@link ContentStore#holdsContentOffInstance()}, where the provider that
     * knows the answer states it.
     *
     * <p>Database mode, which is {@code null}, requires no publication: the bytes are held in the
     * {@code DataResource} columns and are shared by every instance the moment the row commits.
     *
     * @param store the provider content was written under, or {@code null} for database mode
     * @return true only when the provider holds content apart from the deployment's own tree and a
     *     local write therefore has to be published to it
     */
    public static boolean publicationRequired(ContentStore store) {
        return store != null && store.holdsContentOffInstance();
    }

    /**
     * Derives the storage key a {@code DataResource}'s content is held under, which is its
     * {@code ofbiz.home}-relative path and is the same for every provider.
     *
     * <p><strong>One key, every provider.</strong> The path a deployment already stores its content at
     * IS the key: a {@code DataResource} whose content lives at {@code runtime/uploads/party/logo.png}
     * is held under {@code runtime/uploads/party/logo.png} in filesystem mode and under the same string,
     * beneath {@code content.store.s3.key.prefix}, in object-store mode. Three things follow from that,
     * and each of them is why it is done this way:
     *
     * <ul>
     * <li><strong>Filesystem parity, which the plan freezes.</strong> Filesystem mode's storage tree IS
     * the deployment's own content tree (plan section 0.6.3), so its key can only ever be the path. A
     * second key shape for the object store would mean one {@code DataResource} row naming different
     * content depending on which provider was configured, and would make content impossible to copy
     * between the two - which is precisely what a deployment does during the migration window that
     * {@code content.store.local.fallback} exists to cover.</li>
     * <li><strong>The seams the plan names have a path, and not always an identity.</strong> The
     * integration points are {@code DataResourceWorker}'s file-resolution methods (plan section 0.6.3).
     * {@code getContentFile} carries no {@code dataResourceId} in its frozen signature, and
     * {@code getDataResourceContentUploadPath} resolves the upload DIRECTORY before any
     * {@code DataResource} row exists at all. An identity key cannot be derived at either, so it could
     * not have been the key for the write path the plan requires.</li>
     * <li><strong>The path is already confined, twice.</strong> A location reaches this method only
     * after the resource type's own allow list has accepted it - {@code SecurityUtil}'s local and
     * {@code ofbiz.home} lists - and only after it has been shown to lie inside {@code ofbiz.home}; the
     * key grammar below then refuses a control character, an absolute path, a drive prefix and any
     * {@code .} or {@code ..} component. A recorded {@code objectInfo} therefore cannot address content
     * outside the tree the deployment's own configuration already allows it to address.</li>
     * </ul>
     *
     * <p><strong>What separates one deployment, or one tenant, from another</strong> is what separates
     * them on the filesystem: the paths. Two deployments sharing one bucket are separated by
     * {@code content.store.s3.key.prefix}; a tenant that must not share an upload tree is separated by
     * its own {@code content.upload.path.prefix}, which is read per delegator. Scoping the object-store
     * key by tenant while the filesystem key stayed unscoped would have made the two providers disagree
     * about what a row means, which is the one thing this method exists to prevent.
     *
     * <p><strong>For a tenant that separation is REQUIRED, not advisory, and it is enforced.</strong>
     * {@link #requireDistinctTenantKeyNamespace(String, Delegator)} refuses to construct a filesystem or
     * object-store provider for a tenant delegator that resolves the same separator as the base
     * delegator, so a multi-tenant deployment cannot reach the collision this paragraph describes by
     * leaving the configuration at its default. Database mode - the committed default, where each
     * tenant's content stays in its own database - is never affected.
     *
     * @param store the provider the key is for, as resolved by this factory; never null, because
     *     database mode holds content in the {@code DataResource} columns and has no key
     * @param relativePath the {@code ofbiz.home}-relative path the content is stored at, with
     *     {@code /} separators
     * @return the storage key, never blank
     * @throws GeneralException if the provider is null, if the path is missing or carries something a
     *     key may not, or if the key exceeds {@link ContentStore#MAX_KEY_LENGTH_BYTES}
     */
    public static String storeKey(ContentStore store, String relativePath) throws GeneralException {
        if (store == null) {
            throw new GeneralException("A content storage key was asked for while content is held in the database,"
                    + " where there is no storage key");
        }
        ContentStore.requireUsableKey(relativePath);
        return relativePath;
    }

    /**
     * Resolves the provider configured for a scope, constructing it at most once for the life of the
     * JVM.
     *
     * <p><strong>The resolution is immutable once established.</strong> The first resolution for a
     * scope is the resolution that scope keeps until the process ends. A later
     * {@code content.store.*} change is detected, reported once and then ignored, and the provider
     * already in service is returned unchanged.
     *
     * <p>That is deliberate, and it is the whole point: a provider owns a client, a connection pool
     * and the streams {@link ContentStore#openStream} has already handed to callers, and there is no
     * moment at which this class can know that every one of them has been released. Replacing the
     * record and closing what it displaced - which is what this method used to do - would fail
     * in-flight reads against a closed client, including reads whose stream a response is still being
     * written from. Nothing here can be closed safely, so nothing here is closed: the provider lives
     * as long as the JVM, and a configuration change takes effect the way every other change to a
     * running instance's storage backend does, on restart. In a fleet that is a rolling restart, which
     * is exactly the operation the deployment this refactor prepares is built to perform.
     *
     * @param delegator the delegator the configuration is read through, or null to read
     *     {@code content.properties} alone
     * @return the provider, or {@code null} for database mode and for an unrecognised value
     * @throws GeneralException if a recognised provider cannot be constructed
     */
    private static ContentStore resolve(Delegator delegator) throws GeneralException {
        String scope = delegator == null ? GLOBAL_SCOPE : delegator.getDelegatorName();
        // Read from the property file for every scope, because the selector is a deployment value: see
        // DEPLOYMENT_PROPERTIES. The scope still keys the held resolution, because the tunables and the
        // tenant an object-store key is scoped by are per-delegator.
        String selected = normalised(deploymentValue(PROVIDER_PROPERTY));
        String fingerprint = fingerprint(delegator);

        Resolution held = RESOLUTIONS.get(scope);
        if (held != null) {
            reportIgnoredConfigurationChange(scope, held, selected, fingerprint);
            return held.store();
        }
        // Constructed before the map is touched: construction reads configuration, opens a client and
        // may fail, none of which belongs inside a ConcurrentHashMap remapping function, which runs
        // holding a bin lock.
        Resolution candidate = new Resolution(selected, fingerprint, construct(selected, delegator));
        Resolution installed = RESOLUTIONS.putIfAbsent(scope, candidate);
        if (installed == null) {
            return candidate.store();
        }
        // This thread's construction lost the race, so this thread closes what it built. Nothing has
        // ever been handed the loser - it was never in the map - so closing it here is the one close
        // in this class that cannot reach a provider somebody is using, and it is what keeps a race
        // from leaking a client. The winner is returned to every racer, so one scope is served by one
        // provider however many threads arrived at once.
        close(candidate.store());
        return installed.store();
    }

    /**
     * Reports, once per scope, that the configuration changed after the provider was established.
     *
     * <p>Reported rather than acted on, because {@link #resolve} cannot know that the provider in
     * service has been released by everything holding it. An operator who changes a construction-bound
     * setting on a running instance needs to be told that the change is not in force, and needs to be
     * told once rather than on every read - so the first detection replaces the held record's
     * fingerprint with the one now configured, which makes every later read compare equal and stay
     * silent until the configuration changes again. The provider inside the record is never touched.
     *
     * <p>Only a construction-bound change reaches here at all: {@link #fingerprint(Delegator)} covers
     * exactly {@link #constructionBoundProperties()}, so changing {@link #DYNAMIC_PROPERTIES} produces
     * no warning - correctly, because such a change is already in force.
     *
     * @param scope the delegator scope the resolution is held under, named in the report
     * @param held the resolution in service
     * @param selected the normalised provider value now configured
     * @param fingerprint the configuration digest now in force
     */
    private static void reportIgnoredConfigurationChange(String scope, Resolution held, String selected,
            String fingerprint) {
        if (held.matches(selected, fingerprint) || !held.noteConfigurationChange(selected, fingerprint)) {
            return;
        }
        Debug.logWarning("A construction-bound part of the content storage configuration of delegator scope ["
                + scope + "] has changed, but the [" + held.provider() + "] provider resolved earlier stays in"
                + " service: a provider owns the client and the open streams of requests already using it, so it"
                + " cannot be replaced underneath them. Restart this instance for the change to take effect. The"
                + " settings read on every use - " + String.join(", ", new TreeSet<>(DYNAMIC_PROPERTIES))
                + " - are NOT covered by this warning and are already in force.", MODULE);
    }

    /**
     * Constructs the provider a normalised value names.
     *
     * <p>The delegator that selected the provider is handed to the provider, so that every overridable
     * {@code content.store.*} tunable is read through the same delegator, while the deployment values
     * both sides read come from the property file. Building the provider through a different layer
     * from the one the tunables were read through would make a change the delegator-aware
     * {@link #fingerprint} notices produce a rebuilt provider that ignored it.
     *
     * @param selected the trimmed, lower-cased configured value, never empty
     * @param delegator the delegator the overridable tunables are read through; may be null, in
     *     which case the provider reads {@code content.properties} alone
     * @return the provider, or {@code null} for database mode
     * @throws GeneralException if the value is not one of the three accepted tokens, or if a
     *     recognised provider cannot be constructed
     */
    private static ContentStore construct(String selected, Delegator delegator) throws GeneralException {
        ProviderConstruction override = CONSTRUCTION_OVERRIDE.get();
        switch (selected) {
        case DATABASE:
            return null;
        case FILESYSTEM:
            requireDistinctTenantKeyNamespace(selected, delegator);
            Debug.logInfo("Content storage provider [" + FILESYSTEM + "] selected by " + PROVIDER_PROPERTY, MODULE);
            return override == null ? new FileSystemContentStore(delegator) : override.create(FILESYSTEM, delegator);
        case S3:
            requireDistinctTenantKeyNamespace(selected, delegator);
            Debug.logInfo("Content storage provider [" + S3 + "] selected by " + PROVIDER_PROPERTY, MODULE);
            if (!localFallbackEnabled(delegator)) {
                // Reported at selection rather than at the first refused read, because the refusal an
                // operator would otherwise meet first is a shipped image or a document uploaded before
                // the store existed, and by then it looks like a fault rather than the configured
                // posture. One line per resolution, not per read.
                Debug.logWarning(LOCAL_FALLBACK_PROPERTY + " is false, so this instance refuses to read any"
                        + " file-backed content the object store does not hold, including content written before"
                        + " the store was configured. Uploads made from now on are published to the store as they"
                        + " are written; copy existing content into the store, or set " + LOCAL_FALLBACK_PROPERTY
                        + "=true until it has been copied.", MODULE);
            }
            return override == null ? new S3ContentStore(delegator) : override.create(S3, delegator);
        default:
            // Refused, not defaulted. An absent selector is database mode and never reaches this switch -
            // normalised() answers it - so a value here was written by somebody who meant to select a
            // backend. Storing content in a different one from the one they named, and only saying so in a
            // log line, is the outcome this refusal exists to prevent: see the class documentation.
            throw new GeneralException("Unrecognised " + PROVIDER_PROPERTY + " value [" + selected + "]. It must be"
                    + " " + DATABASE + ", " + FILESYSTEM + " or " + S3 + ", or be left unset for " + DATABASE
                    + " storage. Content is not stored in a backend other than the one named.");
        }
    }

    /**
     * Refuses to serve a TENANT delegator from a shared key namespace.
     *
     * <p><strong>The exposure this closes, and why it belongs to this change set.</strong> A provider key
     * is the content's {@code ofbiz.home}-relative path, which is the same string for every provider (see
     * {@link #storeKey}). In a multi-tenant deployment that path comes from
     * {@code content.upload.path.prefix}, read through the tenant's own delegator, so two tenants that
     * leave it at the committed default record content under the SAME path - and therefore under the same
     * storage key. Each could then read, overwrite and delete the other's documents (CWE-668, CWE-862),
     * and no row-level authorisation would see it happen, because the collision is below the row.
     *
     * <p>That sharing is not new to the filesystem: {@code runtime/uploads} has always been one tree.
     * What IS new is that a multi-tenant deployment's content used to be in the {@code DataResource}
     * columns of each tenant's OWN database - perfectly isolated - and selecting {@code filesystem} or
     * {@code s3} moves it into one namespace. Since that move is what this change set adds, the guard
     * against it belongs here, and it is applied ONLY to the capability being added: database mode, which
     * is the committed default, never reaches this method, and a deployment with no tenant delegator is
     * unaffected.
     *
     * <p><strong>What counts as distinct.</strong> Either separator is enough, because either one gives
     * the tenant a key space of its own: an upload path prefix that is not the base delegator's - which
     * separates the paths themselves, and therefore the keys, for every provider - or, for the object
     * store, a key prefix that is not the base delegator's, which separates the objects inside the
     * bucket. Both are ordinary per-tenant {@code SystemProperty} rows.
     *
     * <p>Refused rather than warned. A warning about cross-tenant content exposure is read after the
     * exposure, and the remedy is one configuration row.
     *
     * @param selected the provider being constructed, for the message
     * @param delegator the delegator the provider is being constructed for; null and non-tenant
     *     delegators are accepted without a check
     * @throws GeneralException if a tenant delegator would share one key namespace with the base delegator
     */
    private static void requireDistinctTenantKeyNamespace(String selected, Delegator delegator)
            throws GeneralException {
        if (delegator == null || UtilValidate.isEmpty(delegator.getDelegatorTenantId())) {
            return;
        }
        Delegator base = DelegatorFactory.getDelegator(delegator.getDelegatorBaseName());
        if (base == null) {
            // Nothing to compare against. The base delegator is always resolvable in a running deployment -
            // it is the one the tenant delegator was derived from - so this is unreachable rather than a
            // case with a policy; accepting is the same outcome as a deployment with no tenants at all.
            return;
        }
        String tenantUploadPath = propertyValue(UPLOAD_PATH_PREFIX_PROPERTY, delegator);
        String baseUploadPath = propertyValue(UPLOAD_PATH_PREFIX_PROPERTY, base);
        if (!Objects.equals(tenantUploadPath, baseUploadPath)) {
            return;
        }
        if (S3.equals(selected)) {
            String tenantKeyPrefix = propertyValue(KEY_PREFIX_PROPERTY, delegator);
            String baseKeyPrefix = propertyValue(KEY_PREFIX_PROPERTY, base);
            if (!Objects.equals(tenantKeyPrefix, baseKeyPrefix)) {
                return;
            }
        }
        throw new GeneralException("Content storage provider [" + selected + "] is refused for tenant ["
                + delegator.getDelegatorTenantId() + "] because it would share one storage key namespace with"
                + " the base deployment: a storage key is the content's ofbiz.home-relative path, and this"
                + " tenant resolves the same " + UPLOAD_PATH_PREFIX_PROPERTY + " as the base delegator, so two"
                + " tenants recording the same path would name the same stored object and could read,"
                + " overwrite and delete each other's content. Give the tenant a SystemProperty row for "
                + UPLOAD_PATH_PREFIX_PROPERTY + (S3.equals(selected) ? " or " + KEY_PREFIX_PROPERTY : "")
                + ", or leave " + PROVIDER_PROPERTY + " at " + DATABASE + ", where each tenant's content stays"
                + " in its own database.");
    }

    /**
     * Reads a numeric setting of the content resource, keeping it inside the range it is useful in.
     *
     * <p>Package-private because it is the one place a numeric setting of this package is parsed:
     * every bound and deadline is read through it, so they all treat an unusable value the same way -
     * report it once, naming the property and what is wrong with it, and apply the committed default.
     * Falling back rather than refusing is deliberate for a bound: a mistyped ceiling must not stop a
     * fleet member serving content it can serve correctly with the default.
     *
     * @param property the property to read
     * @param delegator the delegator a {@code SystemProperty} override is read through; may be null
     * @param defaultValue the committed default, which applies when the value is absent or unusable
     * @param minimum the smallest accepted value, inclusive
     * @param maximum the largest accepted value, inclusive
     * @return the configured value when it is usable, otherwise {@code defaultValue}
     */
    static long boundedLong(String property, Delegator delegator, long defaultValue, long minimum, long maximum) {
        String configured = propertyValue(property, delegator);
        if (UtilValidate.isEmpty(configured)) {
            return defaultValue;
        }
        long parsed;
        try {
            parsed = Long.parseLong(configured.trim());
        } catch (NumberFormatException e) {
            reportUnusableValue(property, configured, "is not a whole number");
            return defaultValue;
        }
        if (parsed < minimum || parsed > maximum) {
            reportUnusableValue(property, configured, "is outside the accepted range " + minimum + ".." + maximum);
            return defaultValue;
        }
        return parsed;
    }

    /**
     * Reports a configured value that cannot be used, once per distinct value.
     *
     * <p>Package-private for the same reason {@link #boundedLong} is: a provider that validates a
     * setting of its own reports it the same way, so an operator sees one kind of message however the
     * setting is consumed.
     *
     * @param property the property the value was read from
     * @param configured the value as configured
     * @param because what is wrong with it, phrased to complete the sentence
     */
    static void reportUnusableValue(String property, String configured, String because) {
        if (REPORTED_UNUSABLE_VALUES.add(property + "=" + configured)) {
            Debug.logWarning("The " + property + " value [" + configured + "] " + because + "; the committed default"
                    + " applies instead", MODULE);
        }
    }

    /**
     * Normalises a configured provider value to one of the names the resolution table lists.
     *
     * @param configured the raw configured value, which may be null, blank or padded
     * @return the trimmed, lower-cased value, or {@link #DATABASE} when nothing was configured
     */
    private static String normalised(String configured) {
        String selected = configured == null ? "" : configured.trim().toLowerCase(Locale.ROOT);
        return UtilValidate.isEmpty(selected) ? DATABASE : selected;
    }

    /**
     * Reads one overridable tunable of the content resource, honouring a {@code SystemProperty}
     * override.
     *
     * <p>Package-private because it is the one place an overridable {@code content.store.*} value is
     * read in this package: the factory, the filesystem provider and the object-store provider all
     * read through it, so a value overridden through the {@code SystemProperty} entity reaches every
     * one of them or none of them.
     *
     * <p>Not for the deployment values. Those are read through {@link #deploymentValue(String)}, which
     * consults the property file alone, and passing one of them here is refused rather than quietly
     * granting it a database override; see {@link #DEPLOYMENT_PROPERTIES}.
     *
     * @param name the property to read; must not be one of {@link #DEPLOYMENT_PROPERTIES}
     * @param delegator the delegator the override is read through; may be null, in which case only
     *     {@code content.properties} is consulted
     * @return the configured value, or "" when the property is absent; never null
     */
    static String propertyValue(String name, Delegator delegator) {
        if (DEPLOYMENT_PROPERTIES.contains(name)) {
            throw new IllegalArgumentException("[" + name + "] decides where durable content is written, so it must"
                    + " be read through deploymentValue and must not honour a SystemProperty override");
        }
        return delegator == null
                ? UtilProperties.getPropertyValue(PROPERTY_RESOURCE, name)
                : EntityUtilProperties.getPropertyValue(PROPERTY_RESOURCE, name, delegator);
    }

    /**
     * Reads one {@code content.store.*} value from the layer that is authoritative for it.
     *
     * <p>The one accessor that answers "what is this setting actually going to be", whichever kind of
     * setting it is: a deployment value from the property file, a tunable from the property file with a
     * {@code SystemProperty} row taking precedence. {@link #fingerprint} digests through it so that the
     * digest describes the configuration the providers will really be built from - a row that cannot
     * override a value must not be able to turn a held resolution over either.
     *
     * @param name the property to read
     * @param delegator the delegator an override is read through; may be null
     * @return the effective value, or "" when the property is absent; never null
     */
    private static String effectiveValue(String name, Delegator delegator) {
        return DEPLOYMENT_PROPERTIES.contains(name) ? deploymentValue(name) : propertyValue(name, delegator);
    }

    /**
     * The {@code content.store.*} settings a provider is BUILT from, and therefore the exact set a
     * change to which needs a restart.
     *
     * <p>Discovered from the resource and filtered by {@link #STORE_PROPERTY_PREFIX}, minus
     * {@link #DYNAMIC_PROPERTIES}, so a setting added to {@code content.properties} is covered the
     * moment it is shipped without this class having to be edited - while the two settings that are
     * genuinely read on every use stay out of it. The selector is added explicitly because it must be
     * covered even in a deployment whose property file does not declare it at all.
     *
     * <p>Package-private so the provider tests can assert the split itself rather than a
     * re-implementation of it: that every dynamic setting is absent and every other one present is the
     * property the reload semantics rest on.
     *
     * @return the construction-bound property names, sorted
     */
    static SortedSet<String> constructionBoundProperties() {
        SortedSet<String> names = new TreeSet<>();
        names.add(PROVIDER_PROPERTY);
        Properties committed = UtilProperties.getProperties(PROPERTY_RESOURCE);
        if (committed != null) {
            for (String name : committed.stringPropertyNames()) {
                if (name.startsWith(STORE_PROPERTY_PREFIX) && !DYNAMIC_PROPERTIES.contains(name)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /**
     * Digests every CONSTRUCTION-BOUND configuration value, so that a resolution can be recognised as
     * belonging to the configuration its provider was built from rather than only to the provider name.
     *
     * <p>The dynamic settings are deliberately absent - see {@link #DYNAMIC_PROPERTIES}. Including them
     * would make a change that already applies without a restart turn the fingerprint over and emit a
     * "restart to apply" warning about a change that is in force, which is exactly the contradiction
     * this split removes.
     *
     * <p>What is kept is a digest and not the values: one of them is a secret access key, and there is
     * no reason to hold a second, unclearable copy of it for the life of the JVM.
     *
     * @param delegator the delegator overrides are read through; may be null
     * @return a hexadecimal SHA-256 digest of the construction-bound configuration, never blank
     * @throws GeneralException if SHA-256 is unavailable, which the platform requires it not to be
     */
    private static String fingerprint(Delegator delegator) throws GeneralException {
        SortedSet<String> names = new TreeSet<>(constructionBoundProperties());
        StringBuilder joined = new StringBuilder();
        for (String name : names) {
            // Digested through the accessor each setting is really read with, so the digest describes the
            // configuration the providers will be built from: a SystemProperty row over a deployment value
            // changes nothing here because it changes nothing there either.
            String value = effectiveValue(name, delegator);
            // The separators cannot occur in a property name and are escaped nowhere else, so two
            // different configurations cannot join into one identical string.
            joined.append(name).append('\u0000').append(value).append('\u0001');
        }
        return digest(joined.toString());
    }

    /**
     * Digests a string with SHA-256 and renders it as lower-case hexadecimal.
     *
     * @param value the string to digest
     * @return the digest as 64 hexadecimal characters
     * @throws GeneralException if SHA-256 is unavailable; every Java platform is required to
     *     provide it, so this is reported rather than worked around
     */
    private static String digest(String value) throws GeneralException {
        try {
            byte[] digested = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexadecimal = new StringBuilder(digested.length * 2);
            for (byte octet : digested) {
                hexadecimal.append(Character.forDigit((octet >> 4) & 0x0F, 16))
                        .append(Character.forDigit(octet & 0x0F, 16));
            }
            return hexadecimal.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new GeneralException("The content storage configuration cannot be resolved because SHA-256 is not"
                    + " available from this Java platform", e);
        }
    }

    /**
     * Closes a provider nothing can be holding, if it owns anything that has to be closed.
     *
     * <p>{@link ContentStore} carries five operations and no close, which the plan freezes, so the
     * one provider that owns a client with a connection pool is closed through a package-private
     * hook of its own. A provider added later that owns a resource has to be closed here too; one
     * that owns nothing needs no branch.
     *
     * <p>Only two callers exist, and each one has established that no caller can be using the provider
     * it passes: {@link #resolve} closes a candidate that lost the installation race, which was never
     * published, and {@link #closeEveryResolution} runs from the JVM shutdown hook or from a test that
     * has finished with it. Nothing closes a provider that is in service - see the class documentation
     * for why a configuration change is reported instead.
     *
     * @param store the provider to close; may be null, which is database mode
     */
    private static void close(ContentStore store) {
        if (store instanceof S3ContentStore objectStore) {
            objectStore.close();
        }
    }

    /**
     * Discards every held resolution and closes each provider exactly once.
     *
     * <p>Each entry is removed before its provider is closed, so a resolution running concurrently
     * either finds the entry and returns its provider or misses it and builds a new one; and because
     * only the thread whose {@code remove} returned the entry closes it, no provider is closed twice.
     *
     * <p>Reached from the JVM shutdown hook, where there is no request left to fail, and from
     * {@link #clearCache} in the tests, which resolve one configuration per case and are done with the
     * previous one. It is deliberately not reachable from a configuration change.
     */
    private static void closeEveryResolution() {
        for (String scope : new ArrayList<>(RESOLUTIONS.keySet())) {
            Resolution removed = RESOLUTIONS.remove(scope);
            if (removed != null) {
                close(removed.store());
            }
        }
    }

    /**
     * Registers the JVM shutdown cleanup that closes whatever providers are still held.
     *
     * <p>A shutdown hook rather than a container hook, because this package deliberately adds no
     * component of its own and there is nothing else that runs when the JVM stops. This is the
     * mechanism the start-up code itself uses to stop the server, so it is the established one here.
     *
     * <p>A failure to register is reported and swallowed, not thrown. This runs from a static
     * initialiser, so letting it out would make the class permanently unusable and stop content being
     * served at all - a far worse outcome than a client left open by a JVM that is stopping anyway.
     * The cases are a JVM already shutting down, in which case there is nothing to clean up yet, and
     * a security policy that forbids hooks, in which case there never will be one.
     */
    private static void registerShutdownCleanup() {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(ContentStoreFactory::closeEveryResolution,
                    "content-store-cleanup"));
        } catch (RuntimeException e) {
            Debug.logWarning("The content storage cleanup could not be registered (" + e.getClass().getName()
                    + "), so a provider that owns a client will be released by the JVM exiting rather than by"
                    + " being closed", MODULE);
        }
    }

    /**
     * One provider, the configuration it was built from, and the configuration last observed.
     *
     * <p>The provider and the configuration it was built from are final and published as a single
     * reference, which is what makes "this provider belongs to this configuration" an invariant rather
     * than a hope: a reader sees the whole record or none of it, and never a provider paired with
     * another configuration's key. The record is never replaced once installed, so that invariant also
     * holds for the whole life of the JVM.
     *
     * <p>The one mutable field is the configuration last observed, and it exists only so that a change
     * an operator makes to a running instance is reported once rather than on every read. It has no
     * bearing on which provider is returned.
     */
    private static final class Resolution {

        private final String provider;
        private final ContentStore store;
        private final AtomicReference<String> observedConfiguration;

        private Resolution(String provider, String fingerprint, ContentStore store) {
            this.provider = provider;
            this.store = store;
            this.observedConfiguration = new AtomicReference<>(configurationOf(provider, fingerprint));
        }

        /**
         * Joins a provider value and a configuration digest into the one string the two are compared
         * as, so that a change to either is one comparison rather than two.
         *
         * @param providerValue the normalised provider value
         * @param fingerprint the configuration digest
         * @return the joined description, separated by a character neither part can contain
         */
        private static String configurationOf(String providerValue, String fingerprint) {
            return providerValue + '\u0000' + fingerprint;
        }

        /**
         * Reports whether the configuration described is the one last observed for this resolution.
         *
         * @param otherProvider the normalised provider value in force
         * @param otherFingerprint the configuration digest in force
         * @return true when nothing has changed since this resolution last looked
         */
        private boolean matches(String otherProvider, String otherFingerprint) {
            return observedConfiguration.get().equals(configurationOf(otherProvider, otherFingerprint));
        }

        /**
         * Records a configuration that differs from the one last observed, and reports whether this
         * call is the one that recorded it.
         *
         * <p>Compare-and-set rather than get-and-set, so that when several threads notice the same
         * change at once exactly one of them is told to report it.
         *
         * @param otherProvider the normalised provider value in force
         * @param otherFingerprint the configuration digest in force
         * @return true when this call recorded the change and is therefore the one that reports it
         */
        private boolean noteConfigurationChange(String otherProvider, String otherFingerprint) {
            String updated = configurationOf(otherProvider, otherFingerprint);
            String current = observedConfiguration.get();
            return !updated.equals(current) && observedConfiguration.compareAndSet(current, updated);
        }

        /**
         * Returns the provider value this resolution was built from.
         *
         * @return the normalised provider value, never null
         */
        private String provider() {
            return provider;
        }

        /**
         * Returns the resolved provider.
         *
         * @return the provider, or null when this resolution is database mode
         */
        private ContentStore store() {
            return store;
        }
    }
}
