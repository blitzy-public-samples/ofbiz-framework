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
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
 * <p><strong>One resolution per delegator scope, keyed by the whole configuration.</strong> A
 * resolved provider is kept so that it is constructed once rather than once per request. What is
 * kept is a single immutable record holding three things together: the normalised provider value, a
 * digest of every {@code content.store.*} value the providers read, and the provider built from
 * them. Because the three are published as one reference, no caller can be handed a provider that
 * was built from a configuration other than the one just read - which a pair of independently
 * updated fields could not guarantee. Because the record is keyed by delegator name, a
 * {@code SystemProperty} row that configures one tenant's storage never answers another tenant's
 * resolution. And because the digest covers the whole configuration rather than only the selector,
 * a changed bucket, endpoint, credential or bound is honoured exactly as a changed provider name
 * is. Caching the database-mode outcome, including the one an unrecognised value falls back to, is
 * safe precisely because that outcome is not a failure: nothing is poisoned and every later call
 * behaves identically.
 *
 * <p><strong>A provider is configured from the layer that selected it.</strong> The delegator a
 * resolution is asked for is the delegator the provider is built through, so every
 * {@code content.store.*} value - the selector, the bucket, the endpoint, the credentials, the
 * prefix, the deadlines and the bounds - is read from the same place. Reading the selector through
 * the {@code SystemProperty} entity and the rest from {@code content.properties} would let a
 * deployment select a provider it had not configured, and would make a change the digest above
 * notices produce a rebuilt provider that ignored it.
 *
 * <p><strong>A displaced provider is closed exactly once.</strong> A provider may own a client with
 * connections of its own, so it cannot simply be dropped. When a configuration change replaces the
 * record for a scope, the provider it displaced is closed by whichever thread installed the
 * replacement, and a provider whose construction lost that race is closed by the thread that built
 * it; every provider still held is closed by a JVM shutdown hook. The closing is not free of
 * consequence and is not claimed to be: a request that had already been handed the displaced
 * provider can fail against a closed client. That is the accepted cost of not leaking connections,
 * and it is bounded by the fact that a configuration change is an operator action rather than
 * something a request can trigger.
 *
 * <p><strong>Storage keys are minted here.</strong> {@link #storeKey} is the one place a key is
 * derived, because the derivation depends on which provider is active and no caller should have to
 * know that: the filesystem provider is keyed by the {@code ofbiz.home}-relative path the
 * deployment already uses, while the object store is keyed by immutable identity - namespace,
 * tenant scope and {@code dataResourceId} - so that one bucket shared by several tenants cannot
 * serve one tenant's content to another. {@link #maxObjectSize} bounds what any single read may
 * materialise, {@link #publicationRequired} says whether a local write still has to be handed to the
 * provider, and {@link #localFallbackEnabled} decides what a store miss means for a resource this
 * instance holds a file for.
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

    /**
     * The prefix every property this package reads shares, and therefore the set the resolution
     * digest covers. Discovered from the resource rather than listed here, so a property added to
     * {@code content.properties} is covered without this class having to be edited too.
     */
    private static final String STORE_PROPERTY_PREFIX = "content.store.";

    /**
     * The ceiling on what a single read may materialise, and on what one publication may hand over,
     * in bytes.
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
     * The committed answer to a store miss for a resource this instance holds a file for.
     *
     * <p>True, so that selecting a provider never refuses content that predates the selection. See
     * {@link #localFallbackEnabled} for why that is the parity-preserving default and what setting
     * it false buys.
     */
    private static final boolean DEFAULT_LOCAL_FALLBACK = true;

    /** The committed ceiling on a single read, used whenever the configured value is unusable. */
    private static final long DEFAULT_MAX_OBJECT_SIZE = 10485760L;

    /** The smallest ceiling worth honouring: below this no realistic content would be readable. */
    private static final long MINIMUM_MAX_OBJECT_SIZE = 1024L;

    /** The largest ceiling accepted, which is what keeps a mistyped value from meaning "unbounded". */
    private static final long MAXIMUM_MAX_OBJECT_SIZE = 2147483639L;

    /**
     * The first segment of every object-store key, which is what makes an unscoped key recognisable.
     *
     * <p>Package-private because {@link S3ContentStore} refuses a key that does not begin with it:
     * the segment is only worth anything if the boundary that receives the key insists on it, and
     * both sides have to mean the same segment.
     */
    static final String IDENTITY_KEY_NAMESPACE = "dataresource";

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

    static {
        registerShutdownCleanup();
    }

    private ContentStoreFactory() { }

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
     * in the committed configuration. The store remains the authority wherever it holds content -
     * an object in the store always wins over a local copy of the same resource - and this setting
     * decides only what a store <em>miss</em> means for a resource this instance happens to hold a
     * file for. Answering it from that file is what keeps selecting a provider from refusing content
     * that predates the selection: the shipped demo content, every file-backed resource uploaded
     * before the provider was configured, and anything an operator has yet to copy into the bucket
     * would otherwise stop serving the moment the provider is switched on, which is the functional
     * parity the plan requires of every new capability (plan sections 0.1.2 and 0.7.1). New content
     * needs no such bridge, because an upload is published to the store as it is written.
     *
     * <p>Setting it {@code false} is the strict, fail-closed mode: once a provider is configured,
     * content it does not hold is an error rather than a file only one fleet member can see. That is
     * the mode to run once existing content has been migrated, and selecting an object store while
     * it is set reports a warning naming what will be refused, because the choice is not one to make
     * by accident.
     *
     * <p>An unusable value is reported once and the committed default applies, exactly as every
     * other setting of this package treats one: a typo must not decide whether a fleet serves its
     * own shipped content.
     *
     * @param delegator the delegator a {@code SystemProperty} override is read through; may be null,
     *     in which case only {@code content.properties} is consulted
     * @return true when a local copy may answer a store miss, which is the committed default; false
     *     only when the value reads as {@code false}
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
     * Derives the storage key a {@code DataResource}'s content is held under by the active provider.
     *
     * <p>The derivation is here, and not at the call site, because it depends on which provider is
     * active:
     *
     * <ul>
     * <li>The object store is keyed by immutable identity - {@code dataresource/<scope>/<id>} -
     * where the scope is the delegator's base name, joined with {@code ~} and the tenant identifier
     * when the delegator serves a tenant, and each part is restricted to characters that cannot
     * introduce a separator, so two tenancies can never produce one scope. Nothing
     * a {@code DataResource} row carries takes part in the key, so no recorded path can steer a read
     * at another object, and one bucket shared by several tenants cannot serve one tenant's content
     * to another. An object store is a flat namespace shared by every instance, which is exactly why
     * the scope has to be in the key.</li>
     * <li>Every other provider is keyed by {@code relativePath}, the {@code ofbiz.home}-relative
     * path the deployment already stores its content at. This is what keeps filesystem mode
     * behaving as it does today, which the plan freezes: a {@code DataResource} whose content lives
     * under {@code runtime/uploads} is read from exactly where it already is.</li>
     * </ul>
     *
     * <p>The key returned always satisfies the grammar {@link ContentStore} documents, and each
     * provider validates it again at its own boundary.
     *
     * @param store the provider the key is for, as resolved by this factory; never null, because
     *     database mode holds content in the {@code DataResource} columns and has no key
     * @param delegator the delegator the {@code DataResource} was read through, which carries the
     *     tenant scope; required for an identity-keyed provider
     * @param dataResourceId the immutable identifier of the {@code DataResource}; required for an
     *     identity-keyed provider
     * @param relativePath the {@code ofbiz.home}-relative path the content is stored at; required
     *     for a path-keyed provider
     * @return the storage key, never blank
     * @throws GeneralException if the provider is null, if what the provider's key is derived from is
     *     missing or carries something a key may not, or if the derived key exceeds
     *     {@link ContentStore#MAX_KEY_LENGTH_BYTES}
     */
    /**
     * Reports whether content written to the deployment's own tree still has to be handed to the
     * provider, or whether writing it locally has already placed it there.
     *
     * <p>The answer follows from how a provider is keyed, which is why it is decided here beside
     * {@link #storeKey} rather than at the write seam. A path-keyed provider is the deployment's own
     * tree at the deployment's own paths, so a service that has just written a file under
     * {@code ofbiz.home} has by construction written it into the provider and handing the same bytes
     * over again would only rewrite the file it already wrote. An identity-keyed provider - the
     * object store - is a namespace outside every instance, so content that is only on this
     * instance's disk is content the rest of the fleet cannot read: it has to be published.
     *
     * <p>Database mode, which is {@code null}, requires no publication either: the bytes are held in
     * the {@code DataResource} columns and are shared by every instance the moment the row commits.
     *
     * @param store the provider content was written under, or {@code null} for database mode
     * @return true only when the provider holds content apart from the deployment's own tree and a
     *     local write therefore has to be published to it
     */
    public static boolean publicationRequired(ContentStore store) {
        return store instanceof S3ContentStore;
    }

    public static String storeKey(ContentStore store, Delegator delegator, String dataResourceId,
            String relativePath) throws GeneralException {
        if (store == null) {
            throw new GeneralException("A content storage key was asked for while content is held in the database,"
                    + " where there is no storage key");
        }
        String key = store instanceof S3ContentStore
                ? IDENTITY_KEY_NAMESPACE + "/" + scopeSegment(delegator) + "/" + segment("data resource id",
                        dataResourceId)
                : relativePath;
        return validatedKey(key);
    }

    /**
     * Resolves the provider configured for a scope, constructing it at most once per configuration.
     *
     * @param delegator the delegator the configuration is read through, or null to read
     *     {@code content.properties} alone
     * @return the provider, or {@code null} for database mode and for an unrecognised value
     * @throws GeneralException if a recognised provider cannot be constructed
     */
    private static ContentStore resolve(Delegator delegator) throws GeneralException {
        String scope = delegator == null ? GLOBAL_SCOPE : delegator.getDelegatorName();
        String selected = normalised(propertyValue(PROVIDER_PROPERTY, delegator));
        String fingerprint = fingerprint(delegator);

        Resolution held = RESOLUTIONS.get(scope);
        if (held != null && held.matches(selected, fingerprint)) {
            return held.store();
        }
        // Constructed before the map is touched, and deliberately outside the remapping function
        // below: construction reads configuration, opens a client and may fail, none of which
        // belongs inside a ConcurrentHashMap remapping function, which runs holding a bin lock.
        Resolution candidate = new Resolution(selected, fingerprint, construct(selected, delegator));
        List<Resolution> displaced = new ArrayList<>(1);
        Resolution installed = RESOLUTIONS.compute(scope, (key, present) -> {
            if (present != null && present.matches(selected, fingerprint)) {
                // Another thread resolved the same configuration first: keep its provider, so that
                // one configuration is served by one provider however many threads raced for it.
                return present;
            }
            if (present != null) {
                displaced.add(present);
            }
            return candidate;
        });
        if (installed == candidate) {
            for (Resolution lost : displaced) {
                close(lost.store());
            }
        } else {
            // This thread's construction lost the race, so this thread closes what it built. Doing
            // it here rather than in the remapping function is what keeps exactly one close per
            // provider: the winner is in the map, and the loser is closed by whoever built it.
            close(candidate.store());
        }
        return installed.store();
    }

    /**
     * Constructs the provider a normalised value names.
     *
     * <p>The delegator that selected the provider is handed to the provider, so that every
     * {@code content.store.*} value is read through the same delegator that chose it. Reading the
     * selector through the {@code SystemProperty} entity and then building the provider from
     * {@code content.properties} alone would let a deployment select a provider it had not
     * configured - and, because {@link #fingerprint} is delegator-aware, would turn the held
     * resolution over on a change the rebuilt provider then ignored.
     *
     * @param selected the trimmed, lower-cased configured value, never empty
     * @param delegator the delegator every configuration value is read through; may be null, in
     *     which case the provider reads {@code content.properties} alone
     * @return the provider, or {@code null} for database mode and for an unrecognised value
     * @throws GeneralException if a recognised provider cannot be constructed
     */
    private static ContentStore construct(String selected, Delegator delegator) throws GeneralException {
        switch (selected) {
        case DATABASE:
            return null;
        case FILESYSTEM:
            Debug.logInfo("Content storage provider [" + FILESYSTEM + "] selected by " + PROVIDER_PROPERTY, MODULE);
            return new FileSystemContentStore(delegator);
        case S3:
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
            return new S3ContentStore(delegator);
        default:
            // Warned about and treated as database mode, never thrown: see the class documentation.
            Debug.logWarning("Unrecognised " + PROVIDER_PROPERTY + " value [" + selected + "]; content is stored in"
                    + " the database instead. Set it to " + DATABASE + ", " + FILESYSTEM + " or " + S3 + ".", MODULE);
            return null;
        }
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
     * Reads one property of the content resource, honouring a {@code SystemProperty} override.
     *
     * <p>Package-private because it is the one place a {@code content.store.*} value is read in this
     * package: the factory, the filesystem provider and the object-store provider all read through
     * it, so a value overridden through the {@code SystemProperty} entity reaches every one of them
     * or none of them. A provider that read the resource for itself would be configured from the
     * property file while the selector that chose it came from the database.
     *
     * @param name the property to read
     * @param delegator the delegator the override is read through; may be null, in which case only
     *     {@code content.properties} is consulted
     * @return the configured value, or "" when the property is absent; never null
     */
    static String propertyValue(String name, Delegator delegator) {
        return delegator == null
                ? UtilProperties.getPropertyValue(PROPERTY_RESOURCE, name)
                : EntityUtilProperties.getPropertyValue(PROPERTY_RESOURCE, name, delegator);
    }

    /**
     * Digests every configuration value this package reads, so that a resolution can be recognised
     * as belonging to the configuration in force rather than only to the provider name.
     *
     * <p>The property set is discovered from the resource and filtered by
     * {@link #STORE_PROPERTY_PREFIX} rather than listed in this class, so a setting added to
     * {@code content.properties} is covered the moment it is shipped. What is kept is a digest and
     * not the values: one of them is a secret access key, and there is no reason to hold a second,
     * unclearable copy of it for the life of the JVM.
     *
     * @param delegator the delegator overrides are read through; may be null
     * @return a hexadecimal SHA-256 digest of the configuration, never blank
     * @throws GeneralException if SHA-256 is unavailable, which the platform requires it not to be
     */
    private static String fingerprint(Delegator delegator) throws GeneralException {
        Properties committed = UtilProperties.getProperties(PROPERTY_RESOURCE);
        SortedSet<String> names = new TreeSet<>();
        // Sorted, so the digest depends on the configuration and not on iteration order. The selector
        // is added explicitly because it must be covered even in a deployment whose property file
        // does not declare it at all.
        names.add(PROVIDER_PROPERTY);
        if (committed != null) {
            for (String name : committed.stringPropertyNames()) {
                if (name.startsWith(STORE_PROPERTY_PREFIX)) {
                    names.add(name);
                }
            }
        }
        StringBuilder joined = new StringBuilder();
        for (String name : names) {
            String value = delegator == null
                    ? (committed == null ? "" : committed.getProperty(name, ""))
                    : EntityUtilProperties.getPropertyValue(PROPERTY_RESOURCE, name, delegator);
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
     * Derives the scope segment of an object-store key from the delegator's tenancy.
     *
     * <p>A tenant is qualified by the base delegator it belongs to, joined with {@code ~}, which the
     * accepted character set for either part excludes. Two tenancies therefore cannot produce the
     * same segment, and a tenant whose identifier happens to equal a base delegator's name cannot
     * read the content of the deployment that delegator serves.
     *
     * @param delegator the delegator the content was read through
     * @return the scope segment, never blank
     * @throws GeneralException if there is no delegator, or if the tenancy carries a character a key
     *     segment may not
     */
    private static String scopeSegment(Delegator delegator) throws GeneralException {
        if (delegator == null) {
            throw new GeneralException("An object-store key cannot be derived without a delegator, because the"
                    + " key's tenant scope is derived from it");
        }
        String base = segment("delegator base name", delegator.getDelegatorBaseName());
        String tenantId = delegator.getDelegatorTenantId();
        return UtilValidate.isEmpty(tenantId) ? base : base + "~" + segment("tenant id", tenantId);
    }

    /**
     * Validates one segment of a derived key.
     *
     * @param what what the segment is, named in a refusal so the mistake can be found
     * @param value the segment value
     * @return the value, unchanged
     * @throws GeneralException if the value is empty, is a relative-path component, or carries a
     *     character outside {@code [A-Za-z0-9._-]}
     */
    private static String segment(String what, String value) throws GeneralException {
        if (UtilValidate.isEmpty(value)) {
            throw new GeneralException("An object-store key cannot be derived because its " + what + " is empty");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean accepted = character >= 'a' && character <= 'z' || character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9' || character == '.' || character == '_'
                    || character == '-';
            if (!accepted) {
                throw new GeneralException("An object-store key cannot be derived because its " + what + " ["
                        + value + "] carries a character outside [A-Za-z0-9._-]");
            }
        }
        if (".".equals(value) || "..".equals(value)) {
            throw new GeneralException("An object-store key cannot be derived because its " + what + " is ["
                    + value + "], which names a directory rather than a resource");
        }
        return value;
    }

    /**
     * Checks a minted key against the grammar {@link ContentStore} documents.
     *
     * <p>Applied to every key this factory hands out, whichever provider it was derived for, so that
     * a key accepted by one provider is a key every provider accepts. Each provider validates again
     * at its own boundary, because a provider is reachable without coming through here.
     *
     * @param key the derived key
     * @return the key, unchanged
     * @throws GeneralException if the key is blank, absolute, carries a {@code .} or {@code ..}
     *     component or a control character, or is longer than the contract allows
     */
    private static String validatedKey(String key) throws GeneralException {
        if (UtilValidate.isEmpty(key)) {
            throw new GeneralException("A content storage key cannot be empty");
        }
        for (int index = 0; index < key.length(); index++) {
            if (Character.isISOControl(key.charAt(index))) {
                throw new GeneralException("A content storage key must not carry a control character");
            }
        }
        if (key.startsWith("/") || key.startsWith("\\")) {
            throw new GeneralException("A content storage key must be provider-relative: [" + key + "]");
        }
        for (String part : key.split("/")) {
            if (".".equals(part) || "..".equals(part)) {
                throw new GeneralException("A content storage key must not carry a [" + part + "] component: ["
                        + key + "]");
            }
        }
        int length = key.getBytes(StandardCharsets.UTF_8).length;
        if (length > ContentStore.MAX_KEY_LENGTH_BYTES) {
            throw new GeneralException("A content storage key must be at most " + ContentStore.MAX_KEY_LENGTH_BYTES
                    + " bytes, and this one is " + length);
        }
        return key;
    }

    /**
     * Closes a provider that is no longer held, if it owns anything that has to be closed.
     *
     * <p>{@link ContentStore} carries five operations and no close, which the plan freezes, so the
     * one provider that owns a client with a connection pool is closed through a package-private
     * hook of its own. A provider added later that owns a resource has to be closed here too; one
     * that owns nothing needs no branch.
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
     * One provider, the configuration it was built from, and nothing else.
     *
     * <p>Immutable and published as a single reference, which is what makes "this provider belongs to
     * this configuration" an invariant rather than a hope: a reader either sees the whole record or
     * the one it replaced, and never a provider paired with another configuration's key.
     */
    private static final class Resolution {

        private final String provider;
        private final String fingerprint;
        private final ContentStore store;

        private Resolution(String provider, String fingerprint, ContentStore store) {
            this.provider = provider;
            this.fingerprint = fingerprint;
            this.store = store;
        }

        /**
         * Reports whether this resolution was built from the configuration described.
         *
         * @param otherProvider the normalised provider value in force
         * @param otherFingerprint the configuration digest in force
         * @return true when this resolution can answer for that configuration
         */
        private boolean matches(String otherProvider, String otherFingerprint) {
            return provider.equals(otherProvider) && fingerprint.equals(otherFingerprint);
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
