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

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.content.data.DataResourceWorker;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityFieldMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * How {@code content.store.provider} selects a content-storage provider, and how the object-store
 * provider behaves once one is selected.
 *
 * <p>These are the two decisions every content read and write passes through, so both are asserted
 * here directly rather than inferred from a deployment.
 *
 * <p><strong>{@code null} is the answer, not a missing one.</strong> Database storage - content held
 * in the {@code DataResource} columns, exactly as it always has been - is signalled by the factory
 * returning {@code null}, and it is the committed default. So the unset, blank and {@code database}
 * cases all assert {@code null}, and that is a positive assertion about the shipped behaviour.
 *
 * <p><strong>An unrecognised value must never fail a start-up.</strong> It is warned about and read
 * as database storage. A test that asserted a refusal would codify the opposite of the shipped
 * contract and would turn a typo in one environment variable into an outage, so the unrecognised
 * case asserts three things together: that nothing is thrown, that database storage is selected,
 * and that the operator is warned by name.
 *
 * <p><strong>Every mutated value is restored.</strong> {@code setPropertyValueInMemory} writes into
 * the single cached {@code Properties} instance the whole JVM shares, and Gradle runs every unit
 * test in one JVM, so an override left behind would silently change another test class's behaviour.
 * Every {@code content.store.*} key this class writes - the selector, the object-store connection
 * settings, the key prefix, the bounds and the deadlines - together with the {@code ofbiz.home}
 * system property and the factory's own resolution cache are therefore captured before each test and
 * put back after it. Restoring matters more now than it did: the factory keys its resolution by a
 * digest of the whole configuration, so a value left behind would change which provider a later test
 * is handed rather than merely what one provider is configured with.
 *
 * <p><strong>The class is final</strong> because Checkstyle's {@code DesignForExtension} rule
 * tolerates only the JUnit 4 lifecycle annotations, so a non-final class carrying the
 * {@code @BeforeEach} and {@code @AfterEach} that the restore above requires fails the build.
 *
 * <p><strong>The refusals are asserted as carefully as the successes.</strong> Two of them are
 * security controls rather than tidiness, so each has its own case here: an endpoint is where this
 * deployment's credentials are sent, and a key travels to the store inside a request. Every shape
 * of unusable endpoint is therefore refused before a client exists - including a cloud instance
 * metadata address, in every form it can be written - and every shape of unusable key is refused
 * before a request is issued, for all five operations rather than the two that are cheapest to
 * call. Each refusal is also asserted to name the property or key at fault and to echo neither the
 * configured value nor the configured credentials, because the value a refusal reports may itself
 * be a credential.
 *
 * <p><strong>Nothing here reaches a network, a database or the filesystem.</strong> The object-store
 * operations run against a mocked {@code S3Client} handed to the provider's package-private test
 * seam; the fake configuration the selection cases install builds a client entirely offline, from a
 * static credential pair and an explicit region, and no request is ever issued through it. The
 * endpoints the refusal cases configure are never reached either: they are rejected before a client
 * is built, and the one legal endpoint asserted to be accepted only ever gets as far as being
 * stored on a builder. The {@code S3Client} named here is the single sanctioned reference to the
 * AWS SDK outside {@link S3ContentStore} itself.
 *
 * <p>The public {@code Delegator} form is exercised with a null delegator, which is its documented
 * "consult {@code content.properties} only" contract. A live delegator would make this an
 * integration test, and a stand-in one would assert the entity engine's {@code SystemProperty}
 * lookup rather than anything this factory decides.
 */
public final class ContentStoreFactoryTest {

    /** The name this class logs under, so a skipped live round trip says which class skipped it. */
    private static final String MODULE = ContentStoreFactoryTest.class.getName();

    /** The resource the provider configuration is read from; never {@code content.properties}. */
    private static final String RESOURCE = "content";

    private static final String PROPERTY_PROVIDER = "content.store.provider";
    private static final String PROPERTY_S3_BUCKET = "content.store.s3.bucket";
    private static final String PROPERTY_S3_REGION = "content.store.s3.region";
    private static final String PROPERTY_S3_ENDPOINT = "content.store.s3.endpoint";
    private static final String PROPERTY_S3_ACCESS_KEY_ID = "content.store.s3.access.key.id";
    private static final String PROPERTY_S3_SECRET_ACCESS_KEY = "content.store.s3.secret.access.key";
    private static final String PROPERTY_S3_PATH_STYLE = "content.store.s3.path.style";
    private static final String PROPERTY_S3_KEY_PREFIX = "content.store.s3.key.prefix";
    private static final String PROPERTY_MAX_OBJECT_SIZE = "content.store.max.object.size";
    private static final String PROPERTY_LOCAL_FALLBACK = "content.store.local.fallback";
    private static final String PROPERTY_S3_API_TIMEOUT = "content.store.s3.api.timeout.millis";
    private static final String PROPERTY_S3_ATTEMPT_TIMEOUT = "content.store.s3.attempt.timeout.millis";
    private static final String PROPERTY_S3_MAX_RETRIES = "content.store.s3.max.retries";

    /** Every key this test writes, and therefore every key it has to put back. */
    private static final String[] MUTATED_PROPERTIES = {
        PROPERTY_PROVIDER,
        PROPERTY_S3_BUCKET,
        PROPERTY_S3_REGION,
        PROPERTY_S3_ENDPOINT,
        PROPERTY_S3_ACCESS_KEY_ID,
        PROPERTY_S3_SECRET_ACCESS_KEY,
        PROPERTY_S3_PATH_STYLE,
        PROPERTY_S3_KEY_PREFIX,
        PROPERTY_MAX_OBJECT_SIZE,
        PROPERTY_LOCAL_FALLBACK,
        PROPERTY_S3_API_TIMEOUT,
        PROPERTY_S3_ATTEMPT_TIMEOUT,
        PROPERTY_S3_MAX_RETRIES,
    };

    private static final String PROVIDER_DATABASE = "database";
    private static final String PROVIDER_FILESYSTEM = "filesystem";
    private static final String PROVIDER_S3 = "s3";

    /**
     * Obviously fake object-store configuration. A bucket and a region are required before a client
     * can be built at all, and an explicit region plus a static credential pair is what lets the
     * client be built with no credential-chain probe and no name resolution. Port 1 is unusable on
     * purpose: were a request ever issued, it would fail immediately rather than reach anything.
     */
    private static final String BUCKET = "test-bucket";
    private static final String REGION = "us-east-1";
    private static final String ENDPOINT = "http://127.0.0.1:1";
    private static final String ACCESS_KEY_ID = "test-access-key";
    private static final String SECRET_ACCESS_KEY = "test-secret-key";

    /** A bucket no property file names, so a provider addressing it can only have read the database. */
    private static final String DATABASE_BUCKET = "bucket-from-a-system-property";

    /** A second such bucket, so a change made only in the database can be told from the first. */
    private static final String OTHER_BUCKET = "bucket-changed-in-the-database";

    /**
     * An obviously fake credential planted inside an endpoint value, so that a refusal can be proved
     * not to hand back what it was given. An endpoint really can arrive carrying one - user
     * information is part of the syntax, and a pasted pre-signed URL carries one in its query - which
     * is exactly why a refusal must not repeat it into a log.
     */
    private static final String ENDPOINT_CREDENTIAL = "keyid:not-a-real-secret-in-an-endpoint";

    /** A host that resolves nowhere, for the endpoints that are refused before anything is sent. */
    private static final String UNREACHED_HOST = "objects.example.test";

    /**
     * The one shape of key the object store accepts: the namespace, the tenant scope and the
     * {@code dataResourceId}. A bare upload path is deliberately not used here, because the provider
     * refuses one - which is what keeps two tenants that recorded the same path apart.
     */
    private static final String KEY = "dataresource/default/10000";
    private static final byte[] PAYLOAD = "content bound for an object store".getBytes(StandardCharsets.UTF_8);

    /** The status several S3-compatible stores report in place of {@code NoSuchKey}. */
    private static final int HTTP_NOT_FOUND = 404;

    /** A status that is a genuine failure rather than an absence. */
    private static final int HTTP_SERVER_ERROR = 500;

    /** The committed ceiling on a whole-object read, which an unusable override must leave in force. */
    private static final long DEFAULT_MAX_OBJECT_SIZE = 10485760L;

    // The LIVE_* values below address a throwaway S3-compatible store on the loopback interface of the machine
    // running the build, and nothing else. They are development fixtures of the same kind as the committed
    // jdbc-password.h2-ofbiz value in framework/base/config/passwords.properties, not deployment secrets: a
    // deployment supplies content.store.s3.access.key.id and content.store.s3.secret.access.key from the
    // environment, which is what keeps them out of the repository and the image. Every case that uses these
    // values first calls assumeTrue(liveObjectStoreIsReachable()), so where no such store is listening the case
    // is skipped rather than failed, and no credential is ever sent off the loopback interface.

    /** The S3-compatible endpoint the live round-trip tests use when one is running. */
    private static final String LIVE_ENDPOINT = "http://127.0.0.1:9000";
    /** The bucket the live round-trip tests read and write. */
    private static final String LIVE_BUCKET = "ofbiz-content";
    /** The access key the live round-trip tests authenticate with. */
    private static final String LIVE_ACCESS_KEY_ID = "ofbizminio";
    /** The secret the live round-trip tests authenticate with. */
    private static final String LIVE_SECRET_ACCESS_KEY = "ofbizminio123";
    /** The resource identifier the live round-trip tests store content under. */
    private static final String LIVE_RESOURCE_ID = "90000";
    /** A resource identifier the live store deliberately holds nothing for. */
    private static final String LIVE_ABSENT_RESOURCE_ID = "90001";
    /** How long to wait for the live endpoint to accept a connection before skipping. */
    private static final int LIVE_PROBE_TIMEOUT_MILLIS = 1500;

    /** How many threads the concurrency cases run at once; more than one core, so they genuinely overlap. */
    private static final int CONCURRENT_THREADS = 16;
    /** How many times each of those threads repeats its work. */
    private static final int CONCURRENT_ATTEMPTS = 64;
    /** How long a concurrent case may take before it is treated as hung rather than slow. */
    private static final int CONCURRENT_TIMEOUT_SECONDS = 60;

    private final Map<String, String> committedProperties = new LinkedHashMap<>();
    private String committedOfbizHome;

    @BeforeEach
    public void captureTheConfigurationAndInstallAnOfflineObjectStoreFixture() {
        // setPropertyValueInMemory returns silently when the resource cannot be resolved, so a test whose
        // override never took effect would quietly assert against the committed value instead. Proving the
        // resource is on the test classpath up front is what turns that into a failure here.
        assertNotNull(UtilProperties.getProperties(RESOURCE), "the [" + RESOURCE + "] resource must be resolvable"
                + " on the test classpath, otherwise an in-memory override is silently discarded");
        committedProperties.clear();
        for (String property : MUTATED_PROPERTIES) {
            // Never null: the two-argument lookup answers "" for an absent key, so every captured value is
            // safe to write straight back in the restore below.
            committedProperties.put(property, UtilProperties.getPropertyValue(RESOURCE, property));
        }
        committedOfbizHome = System.getProperty("ofbiz.home");
        System.setProperty("ofbiz.home", System.getProperty("user.dir"));
        installOfflineObjectStoreConfiguration();
        ContentStoreFactory.clearCache();
    }

    @AfterEach
    public void restoreTheConfigurationAndLeaveNoResolutionBehind() {
        for (Map.Entry<String, String> committed : committedProperties.entrySet()) {
            UtilProperties.setPropertyValueInMemory(RESOURCE, committed.getKey(), committed.getValue());
        }
        if (committedOfbizHome == null) {
            System.clearProperty("ofbiz.home");
        } else {
            System.setProperty("ofbiz.home", committedOfbizHome);
        }
        ContentStoreFactory.clearCache();
    }

    @Test
    public void everyValueThatMeansDatabaseStorageResolvesToNoProvider() throws Exception {
        // Every shape a deployment can write for "leave content exactly where it is": the blank a
        // commented-out or emptied key leaves behind, the whitespace a hand-edited file arrives with, and
        // the letter cases a SystemProperty row may carry. The blank is written explicitly rather than left
        // to an absent key, because this component ships the key with a value: a case that configured
        // nothing would read that shipped value and quietly stop distinguishing unset from database.
        for (String value : new String[] {"", "   ", PROVIDER_DATABASE, "DATABASE", "  Database  "}) {
            assertNull(storeConfiguredAs(value), "[" + value + "] must select database storage");
        }
    }

    @Test
    public void theFilesystemValueResolvesToTheFilesystemProvider() throws Exception {
        ContentStore selected = storeConfiguredAs(PROVIDER_FILESYSTEM);

        assertNotNull(selected, "[" + PROVIDER_FILESYSTEM + "] must select a provider");
        assertTrue(selected instanceof FileSystemContentStore, "[" + PROVIDER_FILESYSTEM + "] must select the"
                + " filesystem provider, was: " + selected.getClass().getName());
    }

    @Test
    public void theObjectStoreValueResolvesToTheObjectStoreProvider() throws Exception {
        ContentStore selected = storeConfiguredAs(PROVIDER_S3);

        assertNotNull(selected, "[" + PROVIDER_S3 + "] must select a provider");
        assertTrue(selected instanceof S3ContentStore, "[" + PROVIDER_S3 + "] must select the object-storage"
                + " provider, was: " + selected.getClass().getName());
    }

    @Test
    public void paddingAndLetterCaseNeverChangeWhichProviderIsSelected() throws Exception {
        // A value arrives from a file an operator edited by hand or from an environment variable a shell
        // expanded, so it may be padded or cased differently from the documented spelling. It still names
        // the same provider - and there are no aliases, which the unrecognised case below proves for "fs".
        assertTrue(storeConfiguredAs(" s3 ") instanceof S3ContentStore, "a padded value must still select the"
                + " object-storage provider");
        assertTrue(storeConfiguredAs("S3") instanceof S3ContentStore, "an upper-cased value must still select"
                + " the object-storage provider");
        assertTrue(storeConfiguredAs("  FileSystem  ") instanceof FileSystemContentStore, "a padded, mixed-case"
                + " value must still select the filesystem provider");
    }

    @Test
    public void anUnrecognisedValueIsWarnedAboutAndReadAsDatabaseStorage() {
        // The contract this asserts is deliberate: refusing to serve would turn a single mistyped
        // environment variable into an outage, while reading the value as database storage is exactly the
        // behaviour every unconfigured deployment already has. The warning is what makes it diagnosable,
        // so it is asserted rather than assumed. "fs" and "local" are here because they are NOT aliases.
        for (String value : new String[] {"nonsense", "postgres", "file system", "s-3", "fs", "local", "S3Bucket"}) {
            ContentStore selected = assertDoesNotThrow(() -> storeConfiguredAs(value),
                    "[" + value + "] must not fail a start-up");
            assertNull(selected, "[" + value + "] must be read as database storage");
        }
    }

    @Test
    public void anUnrecognisedValueNamesItselfAndItsPropertyInTheWarning() {
        configureProvider("s-3");
        ContentStoreFactory.clearCache();

        // Stubbing the logger is the only way to assert that the operator is actually told. Only this one
        // resolution runs inside the stub, and it is undone as the block closes.
        try (MockedStatic<Debug> logging = mockStatic(Debug.class)) {
            ContentStore selected = assertDoesNotThrow(() -> ContentStoreFactory.getContentStore(),
                    "an unrecognised value must not fail a start-up");

            assertNull(selected, "an unrecognised value must be read as database storage");
            logging.verify(() -> Debug.logWarning(contains("s-3"), anyString()));
            logging.verify(() -> Debug.logWarning(contains(PROPERTY_PROVIDER), anyString()));
        }
    }

    @Test
    public void readingAnUnrecognisedValueAsDatabaseStorageNeverPoisonsAnotherValue() throws Exception {
        assertNull(storeConfiguredAs("nonsense"), "an unrecognised value must be read as database storage");

        // The fallback is cached like any other outcome, which is safe precisely because it is not a
        // failure: the next value configured is resolved on its own terms.
        assertTrue(storeConfiguredAs(PROVIDER_S3) instanceof S3ContentStore, "a valid value must still be"
                + " honoured after an unrecognised one");
        assertNull(storeConfiguredAs(PROVIDER_DATABASE), "database storage must still be selectable after an"
                + " unrecognised value");
    }

    @Test
    public void theDelegatorFormResolvesEveryValueExactlyAsTheFileFormDoes() throws Exception {
        // The public delegator form exists so a SystemProperty row can override the file. With no
        // delegator it is documented to consult content.properties alone, which is the contract asserted
        // here - across the whole resolution table, so the overload cannot drift away from its sibling.
        for (String value : new String[] {"", PROVIDER_DATABASE, PROVIDER_FILESYSTEM, PROVIDER_S3, "nonsense"}) {
            ContentStore viaFile = storeConfiguredAs(value);
            ContentStoreFactory.clearCache();
            ContentStore viaDelegator = ContentStoreFactory.getContentStore((Delegator) null);

            if (viaFile == null) {
                assertNull(viaDelegator, "[" + value + "] must select database storage through the delegator"
                        + " form too");
            } else {
                assertNotNull(viaDelegator, "[" + value + "] must select a provider through the delegator form"
                        + " too");
                assertEquals(viaFile.getClass(), viaDelegator.getClass(), "[" + value + "] must select the same"
                        + " provider whichever public form is used");
            }
        }
    }

    @Test
    public void oneConfiguredValueIsResolvedOnceUntilTheCacheIsCleared() throws Exception {
        ContentStore first = storeConfiguredAs(PROVIDER_FILESYSTEM);
        ContentStore again = ContentStoreFactory.getContentStore();

        // Resolved once per configured value: a provider owns connections and configuration, so building
        // one per request would leak both.
        assertSame(first, again, "the same configured value must be resolved once and reused");

        ContentStoreFactory.clearCache();
        ContentStore afterReset = ContentStoreFactory.getContentStore();

        assertNotNull(afterReset, "clearing the cache must not stop the configured value being honoured");
        assertNotSame(first, afterReset, "clearing the cache must force the configuration to be read again");
    }

    @Test
    public void changingTheConfiguredValueIsHonouredWithoutClearingTheCache() throws Exception {
        assertTrue(storeConfiguredAs(PROVIDER_FILESYSTEM) instanceof FileSystemContentStore, "the filesystem"
                + " provider must be selected first");

        // No cache reset here on purpose: the cache is keyed by the configured value itself, so a changed
        // value is picked up without anything having to invalidate it.
        configureProvider(PROVIDER_S3);

        assertTrue(ContentStoreFactory.getContentStore() instanceof S3ContentStore, "a changed value must be"
                + " honoured without the cache being cleared");
    }

    @Test
    public void anIncompleteObjectStoreConfigurationIsRefusedRatherThanHalfHonoured() {
        // A provider the deployment explicitly named must not degrade to database storage when it cannot be
        // built: content would then go somewhere nobody asked for. These three are refused before any
        // client exists, so nothing is ever issued under a principal or to a place the operator did not
        // intend. The refusals are asserted not to echo the credential values they guard.
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_BUCKET, "");
        assertTrue(refusalOf(PROVIDER_S3).getMessage().contains(PROPERTY_S3_BUCKET), "an absent bucket must be"
                + " refused by name");

        installOfflineObjectStoreConfiguration();
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_REGION, "");
        assertTrue(refusalOf(PROVIDER_S3).getMessage().contains(PROPERTY_S3_REGION), "an absent region must be"
                + " refused by name");

        installOfflineObjectStoreConfiguration();
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_SECRET_ACCESS_KEY, "");
        GeneralException oneSided = refusalOf(PROVIDER_S3);
        assertTrue(oneSided.getMessage().contains(PROPERTY_S3_ACCESS_KEY_ID), "a one-sided credential pair must"
                + " be refused by name");
        assertFalse(oneSided.getMessage().contains(ACCESS_KEY_ID), "a refusal must not echo the credential:"
                + " " + oneSided.getMessage());
    }

    @Test
    public void anUnusableObjectStoreEndpointIsRefusedBeforeACredentialCanBeSentToIt() throws Exception {
        // Every object request carries this deployment's credential to whatever the endpoint names, so the
        // endpoint is the one configuration value that decides who receives it. Each value below is a shape a
        // hand-edited file or an expanded environment variable really produces: another scheme entirely, a
        // host written with no scheme at all, an address with no host, and the three forms that smuggle extra
        // data past the authority. The last is malformed and carries a credential as well, because a syntax
        // failure reported by quoting the value back is the one way this refusal could leak what it protects.
        // All of them are refused before a client exists, so nothing is ever sent anywhere.
        String[] unusableEndpoints = {"ftp://" + UNREACHED_HOST + "/", "file:///srv/objects",
                UNREACHED_HOST + ":9000", "http:///" + BUCKET,
                "http://" + ENDPOINT_CREDENTIAL + "@" + UNREACHED_HOST + "/",
                "http://" + UNREACHED_HOST + "/?" + ENDPOINT_CREDENTIAL,
                "http://" + UNREACHED_HOST + "/#" + ENDPOINT_CREDENTIAL,
                "http://" + ENDPOINT_CREDENTIAL + "@objects example.test/"};
        for (String unusable : unusableEndpoints) {
            refusalOfEndpoint(unusable);
        }

        // A legal endpoint is still accepted. Without this the whole case above could be satisfied by
        // validation that refused everything, which would take every S3-compatible store out of service.
        configureEndpoint("https://" + UNREACHED_HOST);
        assertTrue(storeConfiguredAs(PROVIDER_S3) instanceof S3ContentStore, "a plain absolute https endpoint"
                + " must still be accepted");
    }

    @Test
    public void aCloudInstanceMetadataEndpointIsRefusedInEveryFormItCanBeWritten() {
        // This refusal is a credential-disclosure defence, not tidiness: the instance metadata service
        // answers with role credentials for the whole instance, so an endpoint naming it would turn the first
        // object request into a disclosure of everything this deployment can reach. Every form of the address
        // is asserted - IPv4 under either scheme, the IPv6 form the SDK also honours, and the container
        // credentials address - because a blocklist covering only the one address everybody quotes would be
        // no defence at all. The reason is asserted too, so the refusal cannot pass for an incidental one.
        String[] metadataEndpoints = {"http://169.254.169.254/",
                "https://169.254.169.254/latest/meta-data/iam/security-credentials/",
                "http://[fd00:ec2::254]/latest/meta-data/", "http://169.254.170.2/v2/credentials"};
        for (String metadata : metadataEndpoints) {
            GeneralException refusal = refusalOfEndpoint(metadata);

            assertTrue(refusal.getMessage().contains("instance metadata"), "[" + metadata + "] must be refused"
                    + " for naming an instance metadata address rather than for an incidental reason, was: "
                    + refusal.getMessage());
        }
    }

    @Test
    public void constructingTheObjectStoreProviderIssuesNoRequest() throws Exception {
        S3Client client = mock(S3Client.class);

        S3ContentStore store = new S3ContentStore(client, BUCKET);

        assertNotNull(store, "the test seam must yield a provider around the supplied client");
        verifyNoInteractions(client);
    }

    @Test
    public void everyStorageOperationIsIssuedThroughTheClientTheSeamWasGiven() throws Exception {
        S3Client client = mock(S3Client.class);
        // A fresh response per call: a response stream is consumed once, so returning one instance
        // twice would hand the second read a stream that is already at its end.
        when(client.getObject(any(GetObjectRequest.class))).thenAnswer(invocation -> storedObject(PAYLOAD));
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());
        ContentStore store = new S3ContentStore(client, BUCKET);

        store.put(KEY, PAYLOAD);
        ArgumentCaptor<RequestBody> written = ArgumentCaptor.forClass(RequestBody.class);
        verify(client).putObject(eq(PutObjectRequest.builder().bucket(BUCKET).key(KEY).build()),
                written.capture());
        try (InputStream sent = written.getValue().contentStreamProvider().newStream()) {
            assertArrayEquals(PAYLOAD, sent.readAllBytes(), "the bytes handed to the client must be the bytes"
                    + " the caller stored");
        }

        assertArrayEquals(PAYLOAD, store.get(KEY), "a read must yield the stored bytes");

        try (InputStream opened = store.openStream(KEY)) {
            assertArrayEquals(PAYLOAD, opened.readAllBytes(), "an opened stream must serve the stored bytes"
                    + " from the first byte");
        }
        // Both reads go through GetObject now that a whole-object read is bounded rather than handed to
        // the SDK's unbounded convenience call.
        verify(client, times(2)).getObject(eq(GetObjectRequest.builder().bucket(BUCKET).key(KEY).build()));

        assertTrue(store.exists(KEY), "a key the store holds must be reported as present");
        verify(client).headObject(eq(HeadObjectRequest.builder().bucket(BUCKET).key(KEY).build()));

        store.delete(KEY);
        verify(client).deleteObject(eq(DeleteObjectRequest.builder().bucket(BUCKET).key(KEY).build()));
    }

    @Test
    public void onlyTheExistenceOperationEverAsksWhetherAKeyIsThere() throws Exception {
        // Presence is asked about exactly once, by the one operation whose answer it is. A read, a write or
        // a removal that probed first would let a caller learn whether a key exists without being entitled
        // to its content, and would cost a second round trip for nothing.
        S3Client client = mock(S3Client.class);
        when(client.getObject(any(GetObjectRequest.class))).thenAnswer(invocation -> storedObject(PAYLOAD));
        ContentStore store = new S3ContentStore(client, BUCKET);

        store.put(KEY, PAYLOAD);
        store.get(KEY);
        store.openStream(KEY).close();
        store.delete(KEY);

        verify(client, never()).headObject(any(HeadObjectRequest.class));
    }

    @Test
    public void anAbsentObjectIsReportedAsAbsenceRatherThanAsAStoreFailure() throws Exception {
        // Two shapes of the same answer: the SDK's own typed NoSuchKey, and a plain S3Exception whose
        // error code says NoSuchKey. Both mean "nothing here", which a caller has to be able to
        // recognise without knowing an SDK type - hence FileNotFoundException from the reads, false from
        // the existence test, and silence from the removal.
        S3Exception typed = (S3Exception) NoSuchKeyException.builder().message("no such key").build();
        S3Exception byErrorCode = errorCoded("NoSuchKey", HTTP_NOT_FOUND);
        for (S3Exception absent : new S3Exception[] {typed, byErrorCode}) {
            S3Client client = mock(S3Client.class);
            when(client.getObject(any(GetObjectRequest.class))).thenThrow(absent);
            when(client.headObject(any(HeadObjectRequest.class))).thenThrow(absent);
            when(client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(absent);
            ContentStore store = new S3ContentStore(client, BUCKET);

            assertThrows(FileNotFoundException.class, () -> store.get(KEY), "a read of an absent key must"
                    + " report absence");
            assertThrows(FileNotFoundException.class, () -> store.openStream(KEY), "opening an absent key"
                    + " must report absence");
            assertFalse(store.exists(KEY), "an absent key must be reported as absent rather than as a failure");
            assertDoesNotThrow(() -> store.delete(KEY), "removing an absent key is a no-op by contract");
        }
    }

    @Test
    public void aMissingBucketOrABodylessNotFoundIsAFailureRatherThanAbsence() throws Exception {
        // The finding this proves: classifying by status code reported a missing bucket and a wrong
        // endpoint as content that is simply not there, which turns a misconfigured deployment into
        // silently missing content. Verified against a real S3-compatible store: a bucket that does not
        // exist answers 404 with NoSuchBucket, and an endpoint that is not an object store answers 404
        // with no error code at all. Neither is absence.
        S3Exception missingBucket = (S3Exception) NoSuchBucketException.builder()
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchBucket").build())
                .statusCode(HTTP_NOT_FOUND).message("no such bucket").build();
        S3Exception bodyless = (S3Exception) S3Exception.builder().statusCode(HTTP_NOT_FOUND)
                .message("not found").build();
        for (S3Exception failure : new S3Exception[] {missingBucket, bodyless}) {
            S3Client client = mock(S3Client.class);
            when(client.getObject(any(GetObjectRequest.class))).thenThrow(failure);
            when(client.headObject(any(HeadObjectRequest.class))).thenThrow(failure);
            when(client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(failure);
            ContentStore store = new S3ContentStore(client, BUCKET);

            assertStoreFailure(assertThrows(IOException.class, () -> store.get(KEY),
                    "a read against " + failure.getMessage() + " must fail rather than report absence"));
            assertStoreFailure(assertThrows(IOException.class, () -> store.openStream(KEY),
                    "opening against " + failure.getMessage() + " must fail rather than report absence"));
            assertThrows(IOException.class, () -> store.exists(KEY), "an existence test against "
                    + failure.getMessage() + " must fail rather than answer false");
            assertThrows(IOException.class, () -> store.delete(KEY), "a removal against "
                    + failure.getMessage() + " must fail rather than be treated as already removed");
        }
    }

    @Test
    public void aStoreFailureIsTranslatedSoThatNoSdkTypeEscapesTheContract() throws Exception {
        S3Exception failure = (S3Exception) S3Exception.builder().statusCode(HTTP_SERVER_ERROR)
                .message("the store is unwell").build();
        S3Client client = mock(S3Client.class);
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(failure);
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(failure);
        when(client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(failure);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(failure);
        ContentStore store = new S3ContentStore(client, BUCKET);

        // A real failure must not be mistaken for an absence, and must not arrive as an SDK type: a caller
        // that had to catch one would be coupled to the provider it is not supposed to know about.
        assertStoreFailure(assertThrows(IOException.class, () -> store.get(KEY)));
        assertStoreFailure(assertThrows(IOException.class, () -> store.openStream(KEY)));
        assertStoreFailure(assertThrows(IOException.class, () -> store.exists(KEY)));
        assertStoreFailure(assertThrows(IOException.class, () -> store.delete(KEY)));
        assertStoreFailure(assertThrows(IOException.class, () -> store.put(KEY, PAYLOAD)));
    }

    @Test
    public void aFailureReportNamesNeitherTheBucketNorTheKeyNorTheStoresOwnWords() throws Exception {
        String storeMessage = "AccessDenied: the bucket policy forbids arn:aws:iam::1234:role/private";
        S3Exception failure = (S3Exception) S3Exception.builder().statusCode(HTTP_SERVER_ERROR)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied").build())
                .requestId("REQ-1234").message(storeMessage).build();
        S3Client client = mock(S3Client.class);
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(failure);
        ContentStore store = new S3ContentStore(client, BUCKET);

        IOException thrown = assertThrows(IOException.class, () -> store.get(KEY));

        // An IOException raised while content is being rendered can reach the rendered page, so the
        // message it carries has to be safe to show to whoever asked for the content.
        String message = thrown.getMessage();
        assertFalse(message.contains(BUCKET), "the bucket must not be disclosed: " + message);
        assertFalse(message.contains(KEY), "the object key must not be disclosed: " + message);
        assertFalse(message.contains(storeMessage), "the store's own message must not be disclosed: " + message);
        assertFalse(message.contains("AccessDenied"), "the store's error code must not be disclosed: " + message);
        assertFalse(message.contains("REQ-1234"), "the store's request id must not be disclosed: " + message);
        // Useless without something that joins the report to the log line that explains it.
        assertTrue(message.contains("Reference ["), "the report must carry an opaque reference: " + message);
        assertSame(failure, thrown.getCause(), "the cause must be retained for anything that inspects it");
    }

    @Test
    public void anAbsenceReportNamesTheKeyAskedForAndNotTheDeploymentsLayout() throws Exception {
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_KEY_PREFIX, "tenants/acme");
        S3Client client = mock(S3Client.class);
        when(client.getObject(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("no such key").build());
        ContentStore store = new S3ContentStore(client, BUCKET);

        FileNotFoundException absent = assertThrows(FileNotFoundException.class, () -> store.get(KEY));

        assertTrue(absent.getMessage().contains(KEY), "the key the caller asked for names the absence: "
                + absent.getMessage());
        assertFalse(absent.getMessage().contains(BUCKET), "the bucket must not be disclosed: "
                + absent.getMessage());
        assertFalse(absent.getMessage().contains("tenants/acme"), "the configured prefix is deployment layout"
                + " and must not be disclosed: " + absent.getMessage());
    }

    @Test
    public void aWholeObjectReadIsRefusedWhenTheStoreDeclaresMoreThanTheCeilingAllows() throws Exception {
        // 1024 is the smallest ceiling the setting accepts; a smaller one would be reported as
        // unusable and the committed default would apply, which would prove nothing.
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_MAX_OBJECT_SIZE, "1024");
        byte[] oversized = new byte[4096];
        S3Client client = mock(S3Client.class);
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(storedObject(oversized));
        ContentStore store = new S3ContentStore(client, BUCKET);

        IOException refused = assertThrows(IOException.class, () -> store.get(KEY));

        assertFalse(refused instanceof FileNotFoundException, "an oversized object is present, not absent: "
                + refused);
        assertTrue(refused.getMessage().contains("Reference ["), "the refusal must carry a reference: "
                + refused.getMessage());
        // The stream is still the operation that serves content of a size an uploader chose, so the
        // ceiling must not have been applied to it as well.
        assertDoesNotThrow(() -> store.openStream(KEY).close(), "a streamed read must stay unbounded");
    }

    @Test
    public void aWholeObjectReadIsBoundedEvenWhenTheStoreUnderstatesTheSize() throws Exception {
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_MAX_OBJECT_SIZE, "1024");
        // Declares 8 bytes and delivers 4096. A declared length is something the store said, not
        // something it is held to, so the bytes actually delivered have to be bounded as well: the
        // declared check below passes and only the second check can catch this.
        ResponseInputStream<GetObjectResponse> understated = new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength(8L).build(),
                AbortableInputStream.create(new ByteArrayInputStream(new byte[4096])));
        S3Client client = mock(S3Client.class);
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(understated);
        ContentStore store = new S3ContentStore(client, BUCKET);

        IOException refused = assertThrows(IOException.class, () -> store.get(KEY));

        assertFalse(refused instanceof FileNotFoundException, "content that is there is not absent: " + refused);
        assertTrue(refused.getMessage().contains("Reference ["), "the refusal must carry a reference: "
                + refused.getMessage());
    }

    @Test
    public void theFilesystemProviderRoundTripsContentInsideItsOwnTree(@TempDir Path home) throws Exception {
        FileSystemContentStore store = filesystemStoreRootedAt(home);
        String key = "runtime/uploads/party/logo.png";

        assertFalse(store.exists(key), "nothing is stored under a key that has never been written");
        store.put(key, PAYLOAD);

        assertTrue(store.exists(key), "content that was stored must be reported as present");
        assertArrayEquals(PAYLOAD, store.get(key), "a whole read must yield the stored bytes");
        try (InputStream opened = store.openStream(key)) {
            assertArrayEquals(PAYLOAD, opened.readAllBytes(), "a streamed read must yield the stored bytes");
        }
        // Exactly where the deployment already keeps it: that is the whole point of this provider.
        assertArrayEquals(PAYLOAD, Files.readAllBytes(home.resolve(key)), "content must be stored at the"
                + " ofbiz.home-relative path the key names");

        store.delete(key);
        assertFalse(store.exists(key), "content that was removed must be reported as absent");
        assertDoesNotThrow(() -> store.delete(key), "removing what is not there is a no-op by contract");
    }

    @Test
    public void rewritingContentKeepsTheFileTheDeploymentAlreadyHas(@TempDir Path home) throws Exception {
        FileSystemContentStore store = filesystemStoreRootedAt(home);
        String key = "runtime/uploads/party/logo.png";
        Path backing = home.resolve(key);
        Files.createDirectories(backing.getParent());
        Files.write(backing, "the original upload".getBytes(StandardCharsets.UTF_8));
        // Group-readable, as an ordinary umask would leave an upload the deployment's own code wrote.
        Files.setPosixFilePermissions(backing, PosixFilePermissions.fromString("rw-r-----"));
        Object identityBefore = Files.readAttributes(backing, BasicFileAttributes.class).fileKey();
        Set<PosixFilePermission> permissionsBefore = permissionsOf(backing);

        store.put(key, PAYLOAD);

        // The finding this proves: staging and replacing gave the file a new inode and this provider's
        // own permissions, so anything holding the file open, or relying on the mode the deployment's
        // upload path produced, silently changed behaviour underneath.
        assertArrayEquals(PAYLOAD, Files.readAllBytes(backing), "the rewrite must land in the same file");
        assertEquals(identityBefore, Files.readAttributes(backing, BasicFileAttributes.class).fileKey(),
                "rewriting content must not replace the file the deployment already has");
        assertEquals(permissionsBefore, permissionsOf(backing), "rewriting content must not change the"
                + " permissions the deployment's own upload path produced");
    }

    @Test
    public void contentCreatedByTheProviderIsPrivateFromTheMomentItExists(@TempDir Path home) throws Exception {
        FileSystemContentStore store = filesystemStoreRootedAt(home);
        String key = "runtime/uploads/party/new-logo.png";

        store.put(key, PAYLOAD);

        // A file this provider creates carries no legacy mode to preserve, so it is created private.
        assertEquals(PosixFilePermissions.fromString("rw-------"), permissionsOf(home.resolve(key)),
                "content this provider creates must be readable only by the OFBiz user");
        assertEquals(PosixFilePermissions.fromString("rwx------"), permissionsOf(home.resolve("runtime/uploads")),
                "a directory this provider creates must be reachable only by the OFBiz user");
        // Nothing may be left behind: a staged file that survived would be content outside any key.
        try (var entries = Files.list(home.resolve("runtime/uploads/party"))) {
            assertEquals(1, entries.count(), "the staging file must not survive a successful write");
        }
    }

    @Test
    public void aKeyReachedThroughALinkedAncestorIsRefused(@TempDir Path home) throws Exception {
        FileSystemContentStore store = filesystemStoreRootedAt(home);
        Path outside = Files.createDirectories(home.getParent().resolve(home.getFileName() + "-outside"));
        Files.write(outside.resolve("secret.txt"), "not ours".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(home.resolve("runtime"));
        Files.createSymbolicLink(home.resolve("runtime/uploads"), outside);
        String key = "runtime/uploads/secret.txt";

        // The finding this proves: every lexical test passes - the key is relative, carries no "..",
        // and resolves under the root - yet walking to it leaves the tree entirely. Only reading the
        // ancestors on disk can tell.
        assertThrows(GeneralException.class, () -> store.get(key), "a key reached through a linked"
                + " ancestor must be refused");
        assertThrows(GeneralException.class, () -> store.openStream(key), "opening through a linked"
                + " ancestor must be refused");
        assertThrows(GeneralException.class, () -> store.exists(key), "testing through a linked ancestor"
                + " must be refused");
        assertThrows(GeneralException.class, () -> store.put(key, PAYLOAD), "writing through a linked"
                + " ancestor must be refused");
        assertThrows(GeneralException.class, () -> store.delete(key), "removing through a linked ancestor"
                + " must be refused");
        assertArrayEquals("not ours".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(outside.resolve("secret.txt")), "nothing outside the root may be touched");
    }

    @Test
    public void aLinkOrDirectoryStandingWhereContentBelongsIsRefused(@TempDir Path home) throws Exception {
        FileSystemContentStore store = filesystemStoreRootedAt(home);
        Path outside = Files.createDirectories(home.getParent().resolve(home.getFileName() + "-target"));
        Path decoy = outside.resolve("decoy.txt");
        Files.write(decoy, "not ours".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(home.resolve("runtime/uploads"));
        Files.createSymbolicLink(home.resolve("runtime/uploads/linked.png"), decoy);
        Files.createDirectories(home.resolve("runtime/uploads/adirectory"));

        for (String key : new String[] {"runtime/uploads/linked.png", "runtime/uploads/adirectory"}) {
            assertThrows(GeneralException.class, () -> store.get(key), "[" + key + "] is not a regular file");
            assertThrows(GeneralException.class, () -> store.openStream(key), "[" + key + "] is not a regular file");
            assertThrows(GeneralException.class, () -> store.put(key, PAYLOAD), "[" + key + "] must not be"
                    + " written through");
            assertThrows(GeneralException.class, () -> store.delete(key), "[" + key + "] must not be removed");
            assertFalse(store.exists(key), "[" + key + "] holds no content of this provider's");
        }
        assertArrayEquals("not ours".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(decoy),
                "the link's target must be untouched");
    }

    @Test
    public void aWholeReadOfALocalFileIsBoundedByTheConfiguredCeiling(@TempDir Path home) throws Exception {
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_MAX_OBJECT_SIZE, "1024");
        FileSystemContentStore store = filesystemStoreRootedAt(home);
        String key = "runtime/uploads/party/large.bin";
        Path backing = home.resolve(key);
        Files.createDirectories(backing.getParent());
        Files.write(backing, new byte[4096]);

        IOException refused = assertThrows(IOException.class, () -> store.get(key));

        assertFalse(refused instanceof FileNotFoundException, "an oversized file is present, not absent: "
                + refused);
        assertTrue(refused.getMessage().contains(PROPERTY_MAX_OBJECT_SIZE), "the refusal must name the setting"
                + " to change: " + refused.getMessage());
        // Streaming is how content of a size an uploader chose is served, so it stays unbounded.
        try (InputStream opened = store.openStream(key)) {
            assertEquals(4096, opened.readAllBytes().length, "a streamed read must stay unbounded");
        }
    }

    @Test
    public void ownerOnlyAccessIsAppliedAndVerifiedWhenPosixIsUnavailable(@TempDir Path home) throws Exception {
        Path entry = Files.write(home.resolve("content.bin"), PAYLOAD);
        Files.setPosixFilePermissions(entry, PosixFilePermissions.fromString("rw-rw-rw-"));

        // The path taken on a filesystem that cannot express POSIX permissions, where the alternative
        // was to accept whatever the platform default happened to be and only log about it.
        FileSystemContentStore.restrictToOwner(entry, "content");

        Set<PosixFilePermission> after = permissionsOf(entry);
        assertTrue(after.contains(PosixFilePermission.OWNER_READ), "the owner must keep read access");
        assertTrue(after.contains(PosixFilePermission.OWNER_WRITE), "the owner must keep write access");
        assertFalse(after.contains(PosixFilePermission.GROUP_READ), "the group must not keep read access");
        assertFalse(after.contains(PosixFilePermission.OTHERS_READ), "others must not keep read access");
        assertFalse(after.contains(PosixFilePermission.GROUP_WRITE), "the group must not keep write access");
        assertFalse(after.contains(PosixFilePermission.OTHERS_WRITE), "others must not keep write access");
    }

    @Test
    public void privacyThatCannotBeEstablishedIsRefusedRatherThanAssumed(@TempDir Path home) throws Exception {
        Path absent = home.resolve("never-created.bin");

        // Nothing to restrict, so the platform flags all report failure. Fail closed: a caller must not
        // be told an entry is private when nothing could be shown to have been applied to it.
        GeneralException refused = assertThrows(GeneralException.class, () -> FileSystemContentStore
                .restrictToOwner(absent, "content"));

        assertTrue(refused.getMessage().contains("private"), "the refusal must say privacy could not be"
                + " established: " + refused.getMessage());
    }

    @Test
    public void aBlankStorageRootIsRefusedExactlyAsAnAbsentOneIs() {
        // Whitespace is what a shell that expanded an unset variable into a quoted argument leaves behind.
        // Accepting it would root the whole content store at whatever directory the process started in,
        // which is a different tree on every instance and a tree nobody chose, so it is refused exactly as
        // an unset value is - loudly, when the provider is built, rather than quietly on the first read.
        for (String blank : new String[] {"", " ", "   ", "\t"}) {
            System.setProperty("ofbiz.home", blank);
            String reported = "a blank ofbiz.home [" + printable(blank) + "] must be refused";
            GeneralException refusal = assertThrows(GeneralException.class, () -> new FileSystemContentStore(null),
                    reported);
            assertTrue(refusal.getMessage().contains("ofbiz.home"), "the refusal must name the property that is"
                    + " unusable: " + refusal.getMessage());
        }

        System.clearProperty("ofbiz.home");
        assertThrows(GeneralException.class, () -> new FileSystemContentStore(null), "an unset ofbiz.home must"
                + " be refused");
    }

    @Test
    public void contentExactlyTheSizeOfTheCeilingIsReadRatherThanRefused() throws Exception {
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_MAX_OBJECT_SIZE, "1024");
        byte[] exact = new byte[1024];
        for (int index = 0; index < exact.length; index++) {
            exact[index] = (byte) index;
        }
        S3Client client = mock(S3Client.class);
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(storedObject(exact));
        ContentStore store = new S3ContentStore(client, BUCKET);

        // The bound is inclusive: refusing at exactly the configured ceiling would make the documented
        // value mean one byte less than it says.
        assertArrayEquals(exact, store.get(KEY), "content the size of the ceiling must be readable");
    }

    @Test
    public void anUnusableKeyOrPayloadIsRefusedBeforeAnyRequestIsIssued() throws Exception {
        S3Client client = mock(S3Client.class);
        ContentStore store = new S3ContentStore(client, BUCKET);

        // A bucket is one namespace shared by every instance and, in a multi-tenant deployment, by
        // every tenant, so this provider accepts only the scoped identity key and refuses everything
        // else where it is named rather than sending it to a store that would honour it. The list is
        // the ways a key can fail to be that: absent, empty, absolute, a traversal, a bare upload
        // path that two tenants could both record, the right shape under the wrong namespace, too few
        // or too many segments, and an empty segment. The last five are the right shape in every
        // respect but one character, and are refused for a second reason: a key travels to the store
        // inside the request line and its headers, so one carrying a NUL or a line break could alter
        // what is sent on this deployment's behalf rather than merely name the wrong object.
        String[] unusable = {
            null,
            "",
            "/dataresource/default/10000",
            "dataresource/../../etc/passwd",
            "runtime/uploads/party/logo.png",
            "other/default/10000",
            "dataresource/10000",
            "dataresource/default/10000/original",
            "dataresource//10000",
            "dataresource/default/",
            "dataresource/default/100\u000000",
            "dataresource/default/10\t000",
            "dataresource/default/10000\n",
            "dataresource/default/100\u007f00",
            "dataresource/default/100\u001b00",
        };
        for (String key : unusable) {
            // All five operations are asked, not only the two that are cheapest to call: they share
            // the one key check, so an edit that moved or weakened it for a single operation would
            // otherwise pass here and let that operation alone reach the store with a key nothing
            // had vetted.
            String refused = "[" + printable(key) + "] must be refused by every operation";
            assertThrows(GeneralException.class, () -> store.get(key), refused);
            assertThrows(GeneralException.class, () -> store.openStream(key), refused);
            assertThrows(GeneralException.class, () -> store.exists(key), refused);
            assertThrows(GeneralException.class, () -> store.delete(key), refused);
            assertThrows(GeneralException.class, () -> store.put(key, PAYLOAD), refused);
        }
        assertThrows(GeneralException.class, () -> store.put(KEY, null), "a null payload must be refused");

        verifyNoInteractions(client);
    }

    @Test
    public void emptyContentIsStoredRatherThanRefusedAsAMissingPayload() throws Exception {
        // The contract permits a zero-length payload and refuses only a null one, and the provider checks the
        // two a couple of lines apart, so the permitted one is asserted rather than assumed. Content that is
        // legitimately empty - a cleared upload, a zero-byte attachment - has to remain storable, and has to
        // arrive at the store as an empty object rather than as no request at all, because the caller that
        // reads it back expects the zero-length array the contract promises.
        S3Client client = mock(S3Client.class);
        ContentStore store = new S3ContentStore(client, BUCKET);

        assertDoesNotThrow(() -> store.put(KEY, new byte[0]), "empty content must be storable");

        ArgumentCaptor<RequestBody> written = ArgumentCaptor.forClass(RequestBody.class);
        verify(client).putObject(eq(PutObjectRequest.builder().bucket(BUCKET).key(KEY).build()),
                written.capture());
        assertEquals(0L, written.getValue().optionalContentLength().orElse(-1L).longValue(), "empty content must"
                + " be sent as a zero-length body rather than as a body of unknown length");
        try (InputStream sent = written.getValue().contentStreamProvider().newStream()) {
            assertEquals(0, sent.readAllBytes().length, "no bytes may be invented for empty content");
        }
    }

    @Test
    public void aKeyLongerThanAnObjectStoreAcceptsIsRefusedRatherThanTruncated() throws Exception {
        S3Client client = mock(S3Client.class);
        ContentStore store = new S3ContentStore(client, BUCKET);
        // One byte over the object-key limit, in the segment a caller controls least: the identifier.
        String tooLong = "dataresource/default/" + "9".repeat(ContentStore.MAX_KEY_LENGTH_BYTES
                - "dataresource/default/".length() + 1);

        assertThrows(GeneralException.class, () -> store.get(tooLong), "a key over the object-key limit must be"
                + " refused rather than sent and silently rejected by the store");
        verifyNoInteractions(client);
    }

    @Test
    public void theSharedKeyGrammarRefusesTheSameKeysThroughEveryProvider(@TempDir Path tree) throws Exception {
        // One grammar, one implementation, one exception type. A key is only opaque if it means the same
        // thing to every provider: a deployment that moves content from a shared filesystem to an object
        // store must not discover that a key one accepted is a key the next refuses. So the whole matrix is
        // asserted against both providers at once, on all five operations, and every refusal has to arrive
        // as a GeneralException - the caller's mistake - rather than as an IOException, which is what a
        // caller reads as the store itself having failed. Each provider still adds what its own storage
        // requires on top of this, which is why only keys the shared grammar refuses appear here.
        ContentStore filesystem = filesystemStoreRootedAt(tree);
        S3Client client = mock(S3Client.class);
        ContentStore objectStore = new S3ContentStore(client, BUCKET);

        String longestComponent = "a".repeat(ContentStore.MAX_KEY_COMPONENT_LENGTH_BYTES - 5);
        String beyondTheKeyLimit = longestComponent + "/" + longestComponent + "/" + longestComponent + "/"
                + longestComponent + "/" + longestComponent;
        String beyondTheComponentLimit = "runtime/" + "b".repeat(ContentStore.MAX_KEY_COMPONENT_LENGTH_BYTES + 1);
        String[] unusable = {null, "", "   ", "\t", "runtime/up\u0000loads/logo.png",
            "/runtime/uploads/logo.png", "\\runtime\\uploads", "C:/runtime/uploads",
            "runtime//uploads/logo.png", "runtime/uploads/", "./runtime/uploads/logo.png",
            "runtime/./uploads/logo.png", "runtime/../../etc/passwd", beyondTheKeyLimit,
            beyondTheComponentLimit, };
        for (String key : unusable) {
            for (ContentStore store : new ContentStore[] {filesystem, objectStore}) {
                String where = store.getClass().getSimpleName() + " [" + printable(key) + "]";
                assertThrows(GeneralException.class, () -> store.get(key), where + " must be refused by get");
                assertThrows(GeneralException.class, () -> store.openStream(key), where + " must be refused by"
                        + " openStream");
                assertThrows(GeneralException.class, () -> store.exists(key), where + " must be refused by"
                        + " exists");
                assertThrows(GeneralException.class, () -> store.delete(key), where + " must be refused by"
                        + " delete");
                assertThrows(GeneralException.class, () -> store.put(key, PAYLOAD), where + " must be refused by"
                        + " put");
            }
        }

        // Refused where it is named, before anything is issued or written.
        verifyNoInteractions(client);
        try (var entries = Files.list(tree)) {
            assertEquals(0, entries.count(), "a refused key must not have created anything in the tree");
        }
    }

    @Test
    public void theConfiguredKeyPrefixIsAppliedToEveryRequest() throws Exception {
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_KEY_PREFIX, "/tenants/acme/");
        S3Client client = mock(S3Client.class);
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());
        ContentStore store = new S3ContentStore(client, BUCKET);

        assertTrue(store.exists(KEY), "the key must be answered through the prefix");

        // Normalised to exactly one separator between the prefix and the key: a prefix an operator
        // wrote with a leading or trailing slash must address the same object as one without.
        verify(client).headObject(eq(HeadObjectRequest.builder().bucket(BUCKET)
                .key("tenants/acme/" + KEY).build()));
    }

    @Test
    public void anUnusableKeyPrefixIsRefusedWhenTheProviderIsBuilt() {
        for (String unusable : new String[] {"tenants/../acme", "tenants/./acme", "tenants//acme"}) {
            UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_KEY_PREFIX, unusable);
            GeneralException refused = assertThrows(GeneralException.class, () -> new S3ContentStore(
                    mock(S3Client.class), BUCKET), "[" + unusable + "] must be refused as a key prefix");
            assertTrue(refused.getMessage().contains(PROPERTY_S3_KEY_PREFIX), "the refusal must name the property:"
                    + " " + refused.getMessage());
        }
    }

    @Test
    public void anObjectStoreProviderReleasesItsClientWhenItIsClosed() throws Exception {
        S3Client client = mock(S3Client.class);
        S3ContentStore store = new S3ContentStore(client, BUCKET);

        // The factory closes a provider it displaces and every provider still held at shutdown. That
        // is only worth anything if closing the provider closes the client, which owns the connection
        // pool and the threads behind it.
        store.close();

        verify(client).close();
    }

    @Test
    public void anUnusableDeadlineIsReportedAndTheProviderIsStillBuilt() {
        // 250 is below the accepted floor, and an attempt deadline is validated on its own before it
        // is compared with the whole-call deadline, so this is the range refusal rather than the
        // contradiction refusal. Either way the provider must still build: a mistyped deadline is not
        // a reason to stop serving content that the committed deadline serves correctly.
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_ATTEMPT_TIMEOUT, "250");
        configureProvider(PROVIDER_S3);
        ContentStoreFactory.clearCache();

        try (MockedStatic<Debug> logging = mockStatic(Debug.class)) {
            ContentStore selected = assertDoesNotThrow(() -> ContentStoreFactory.getContentStore(),
                    "an unusable deadline must not stop the provider being built");

            assertTrue(selected instanceof S3ContentStore, "the object-store provider must still be selected");
            logging.verify(() -> Debug.logWarning(contains(PROPERTY_S3_ATTEMPT_TIMEOUT), anyString()));
        }
    }

    @Test
    public void anAttemptDeadlineLongerThanTheCallItRunsInsideIsReported() {
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_API_TIMEOUT, "2000");
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_ATTEMPT_TIMEOUT, "1999");
        configureProvider(PROVIDER_S3);
        ContentStoreFactory.clearCache();

        // The pair is consistent, so nothing is reported: this half of the assertion is what proves
        // the case below is the contradiction being caught and not simply a warning for every value.
        try (MockedStatic<Debug> logging = mockStatic(Debug.class)) {
            assertDoesNotThrow(() -> ContentStoreFactory.getContentStore());
            logging.verify(() -> Debug.logWarning(contains(PROPERTY_S3_ATTEMPT_TIMEOUT), anyString()), never());
        }

        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_ATTEMPT_TIMEOUT, "2001");
        ContentStoreFactory.clearCache();
        try (MockedStatic<Debug> logging = mockStatic(Debug.class)) {
            assertDoesNotThrow(() -> ContentStoreFactory.getContentStore());
            logging.verify(() -> Debug.logWarning(contains(PROPERTY_S3_API_TIMEOUT), anyString()));
        }
    }

    @Test
    public void theBoundOnAWholeObjectReadIsConfiguredAndAnUnusableValueKeepsTheDefault() {
        assertEquals(DEFAULT_MAX_OBJECT_SIZE, ContentStoreFactory.maxObjectSize(null), "the committed default must"
                + " apply when nothing overrides it");

        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_MAX_OBJECT_SIZE, "4096");
        assertEquals(4096L, ContentStoreFactory.maxObjectSize(null), "a configured bound must be honoured");

        // Each of these would mean "no bound at all" if it were taken at face value, which is the one
        // outcome a bound must never have.
        for (String unusable : new String[] {"none", "0", "-1", "1023", "9223372036854775807", " "}) {
            UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_MAX_OBJECT_SIZE, unusable);
            assertEquals(DEFAULT_MAX_OBJECT_SIZE, ContentStoreFactory.maxObjectSize(null), "[" + unusable + "] must"
                    + " leave the committed default in force");
        }
    }

    @Test
    public void theLocalFallbackIsOffUnlessItIsAskedForExactly() {
        assertFalse(ContentStoreFactory.localFallbackEnabled(null), "the committed default must fail closed");

        for (String on : new String[] {"true", "TRUE", "  True  "}) {
            UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_LOCAL_FALLBACK, on);
            assertTrue(ContentStoreFactory.localFallbackEnabled(null), "[" + on + "] must open the migration mode");
        }
        // Anything that is not "true" fails closed, including a value that looks affirmative: a
        // deployment either asked for the migration mode in the documented spelling or it did not.
        for (String off : new String[] {"false", "", "yes", "1", "Y", "on"}) {
            UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_LOCAL_FALLBACK, off);
            assertFalse(ContentStoreFactory.localFallbackEnabled(null), "[" + off + "] must keep the fail-closed"
                    + " default");
        }
    }

    @Test
    public void theObjectStoreKeyIsDerivedFromTenantScopeAndTheImmutableIdentifier() throws Exception {
        ContentStore objectStore = new S3ContentStore(mock(S3Client.class), BUCKET);

        // Only these two getters are consulted, which is why a stand-in delegator is the right stub
        // here: the derivation is about identity, and nothing else about a delegator takes part in it.
        Delegator base = mock(Delegator.class);
        when(base.getDelegatorBaseName()).thenReturn("default");
        when(base.getDelegatorTenantId()).thenReturn("");
        assertEquals("dataresource/default/10000", ContentStoreFactory.storeKey(objectStore, base, "10000",
                "runtime/uploads/party/logo.png"), "the recorded path must take no part in an object-store key");

        Delegator tenant = mock(Delegator.class);
        when(tenant.getDelegatorBaseName()).thenReturn("default");
        when(tenant.getDelegatorTenantId()).thenReturn("DEMO1");
        assertEquals("dataresource/default~DEMO1/10000", ContentStoreFactory.storeKey(objectStore, tenant, "10000",
                null), "a tenant must be scoped inside the base delegator it belongs to");

        // The same identifier under two tenancies must not be one key: that is the whole point.
        assertNotEquals(ContentStoreFactory.storeKey(objectStore, base, "10000", null),
                ContentStoreFactory.storeKey(objectStore, tenant, "10000", null),
                "two tenancies must never derive one key");
    }

    @Test
    public void aPathKeyedProviderKeepsTheLocationTheDeploymentAlreadyUses() throws Exception {
        ContentStore filesystem = new FileSystemContentStore(null);

        // Filesystem mode is the compatibility mode: the key is the location the content already
        // occupies, so a DataResource under runtime/uploads is read from where it already is.
        assertEquals("runtime/uploads/party/logo.png", ContentStoreFactory.storeKey(filesystem, null, "10000",
                "runtime/uploads/party/logo.png"), "a path-keyed provider must be keyed by that path");
    }

    @Test
    public void aKeyIsRefusedRatherThanDerivedFromSomethingUnusable() throws Exception {
        ContentStore objectStore = new S3ContentStore(mock(S3Client.class), BUCKET);
        Delegator base = mock(Delegator.class);
        when(base.getDelegatorBaseName()).thenReturn("default");
        when(base.getDelegatorTenantId()).thenReturn("");

        assertThrows(GeneralException.class, () -> ContentStoreFactory.storeKey(null, base, "10000", "a/b"),
                "database mode has no storage key, so asking for one is a mistake worth reporting");
        assertThrows(GeneralException.class, () -> ContentStoreFactory.storeKey(objectStore, null, "10000", null),
                "an object-store key cannot be derived without the tenant scope");
        for (String unusable : new String[] {"", "..", "10000/original", "10 000", "10000#1"}) {
            assertThrows(GeneralException.class, () -> ContentStoreFactory.storeKey(objectStore, base, unusable,
                    null), "[" + unusable + "] must not become part of a key");
        }
        Delegator crossable = mock(Delegator.class);
        when(crossable.getDelegatorBaseName()).thenReturn("default");
        when(crossable.getDelegatorTenantId()).thenReturn("a/b");
        assertThrows(GeneralException.class, () -> ContentStoreFactory.storeKey(objectStore, crossable, "10000",
                null), "a tenancy carrying a separator must be refused rather than widened into two segments");
    }

    @Test
    public void aConfigurationChangeThatKeepsTheProviderNameIsStillHonoured() throws Exception {
        ContentStore first = storeConfiguredAs(PROVIDER_S3);
        assertSame(first, ContentStoreFactory.getContentStore(), "one configuration must be resolved once");

        // No cache reset: the resolution is keyed by a digest of the whole configuration, so a changed
        // bucket is picked up exactly as a changed provider name is. Before that it was not - the key
        // was the provider value alone - and content would have kept going to the previous bucket.
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_BUCKET, "test-bucket-two");
        ContentStore afterBucketChange = ContentStoreFactory.getContentStore();

        assertNotSame(first, afterBucketChange, "a changed bucket must be resolved into a new provider");
        assertTrue(afterBucketChange instanceof S3ContentStore, "the provider itself must not change");

        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_KEY_PREFIX, "tenants/acme");
        assertNotSame(afterBucketChange, ContentStoreFactory.getContentStore(), "a changed key prefix must be"
                + " resolved into a new provider");
    }

    // ---------------------------------------------------------------------------------------------------------
    // The DataResourceWorker read seam
    //
    // Everything above proves the store package in isolation. These prove the thing a deployment actually
    // depends on: that the two seams inside DataResourceWorker reach the provider for the content they should,
    // reach local disk for the content they should not, and refuse rather than guess when the two disagree.
    // The seam methods are private, which is the point - they are not API - so they are reached by reflection
    // here rather than by widening them for the benefit of a test.
    // ---------------------------------------------------------------------------------------------------------

    @Test
    public void theWorkerReadsTheLegacyLocalPathWhenContentIsHeldInTheDatabase(@TempDir Path home) throws Exception {
        System.setProperty("ofbiz.home", home.toString());
        configureProvider(PROVIDER_DATABASE);
        Path uploads = Files.createDirectories(home.resolve("runtime/uploads"));
        Files.writeString(uploads.resolve("legacy.txt"), "on the local disk");

        assertEquals("on the local disk", renderedThroughSeam("OFBIZ_FILE", "/runtime/uploads/legacy.txt", null,
                seamDelegator("default", null), "10000"), "the committed default must read the file the"
                + " deployment already has, with no provider involved at all");

        // And absence is still absence, reported exactly where it always was. This is the half of the legacy
        // contract that a read-through would have quietly changed.
        assertThrows(FileNotFoundException.class, () -> renderedThroughSeam("OFBIZ_FILE",
                "/runtime/uploads/absent.txt", null, seamDelegator("default", null), "10000"),
                "database storage must report an absent file as absent, as it always has");
    }

    @Test
    public void theWorkerServesFileBackedContentThroughAConfiguredProvider(@TempDir Path home) throws Exception {
        System.setProperty("ofbiz.home", home.toString());
        configureProvider(PROVIDER_FILESYSTEM);
        Path uploads = Files.createDirectories(home.resolve("runtime/uploads"));
        Files.writeString(uploads.resolve("served.txt"), "served through the provider");

        assertEquals("served through the provider", renderedThroughSeam("OFBIZ_FILE",
                "/runtime/uploads/served.txt", null, seamDelegator("default", null), "10000"),
                "a configured provider must serve file-backed content through the seam");
    }

    @Test
    public void contextFileContentIsNeverServedThroughAProvider(@TempDir Path home) throws Exception {
        // The object store is configured and pointed at an endpoint that refuses every connection, so any
        // request at all would fail this test rather than pass it quietly. CONTEXT_FILE content ships inside
        // the image and is identical on every instance, so there is nothing to externalise.
        System.setProperty("ofbiz.home", home.toString());
        configureProvider(PROVIDER_S3);
        Path webapp = Files.createDirectories(home.resolve("webapp"));
        Files.writeString(webapp.resolve("static.txt"), "part of the image");

        assertEquals("part of the image", renderedThroughSeam("CONTEXT_FILE", "/static.txt",
                webapp.toString(), seamDelegator("default", null), "10000"),
                "CONTEXT_FILE content must be read locally even when an object store is configured");
    }

    @Test
    public void theFrozenRenderFileSignatureNeverConsultsAProvider(@TempDir Path home) throws Exception {
        // The four-argument signature carries no resource identity, and identity is what a key is derived
        // from, so it cannot reach a provider even in principle. Proven against an endpoint that refuses
        // every connection: a request would throw instead of returning.
        System.setProperty("ofbiz.home", home.toString());
        configureProvider(PROVIDER_S3);
        Path uploads = Files.createDirectories(home.resolve("runtime/uploads"));
        Files.writeString(uploads.resolve("frozen.txt"), "read from local disk");

        StringBuilder out = new StringBuilder();
        DataResourceWorker.renderFile("OFBIZ_FILE", "/runtime/uploads/frozen.txt", null, out);
        assertEquals("read from local disk", out.toString(), "the frozen signature must keep reading locally,"
                + " because it has no identity from which a storage key could be derived");
    }

    @Test
    public void aForbiddenLocationIsRefusedBeforeAnyProviderIsAsked(@TempDir Path home) throws Exception {
        // The order matters more than the refusal: a location the allow list rejects must cost no provider
        // request, or the seam becomes an oracle telling a caller whether forbidden content exists (CWE-200).
        // The object store endpoint refuses every connection, so a request would surface as a store failure
        // rather than as this allow-list refusal.
        System.setProperty("ofbiz.home", home.toString());
        configureProvider(PROVIDER_S3);
        Path outside = Files.createTempFile("outside-the-deployment", ".txt");
        try {
            Files.writeString(outside, "not reachable through a DataResource");
            GeneralException refused = assertThrows(GeneralException.class, () -> renderedThroughSeam("LOCAL_FILE",
                    outside.toString(), null, seamDelegator("default", null), "10000"));
            assertTrue(refused.getMessage().contains("not within an allowed directory"), "the refusal must come"
                    + " from the allow list, which proves authorisation ran before any provider request: ["
                    + refused.getMessage() + "]");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    public void aProviderFailureIsReportedRatherThanAnsweredFromLocalDisk(@TempDir Path home) throws Exception {
        // A store that cannot be reached is a broken deployment, not an absent object. Answering it from
        // whatever the local disk happens to hold is how an outage turns into silently serving stale content.
        System.setProperty("ofbiz.home", home.toString());
        configureProvider(PROVIDER_S3);
        Path uploads = Files.createDirectories(home.resolve("runtime/uploads"));
        Files.writeString(uploads.resolve("stale.txt"), "a copy this instance happens to hold");

        assertThrows(Exception.class, () -> renderedThroughSeam("OFBIZ_FILE", "/runtime/uploads/stale.txt", null,
                seamDelegator("default", null), "10000"), "an unreachable store must be reported, never"
                + " answered from the local copy");
    }

    @Test
    public void contentTheProviderDoesNotHoldIsRefusedWhileALocalCopyExists() throws Exception {
        // The three cases the seam has to tell apart, asserted directly because only one of them is a
        // refusal and getting that wrong in either direction is a data-integrity bug.
        configureProvider(PROVIDER_S3);
        Delegator delegator = seamDelegator("default", null);

        // Nothing in the store and nothing on disk: the content does not exist, which is not a fallback
        // decision. The caller reports absence itself, so nothing may be thrown here.
        assertDoesNotThrow(() -> refuseUnlessLocalCopyMayAnswer(KEY, true, delegator),
                "content that exists nowhere is plain absence, not a refusal");

        // Nothing in the store but a local copy exists: refused, and the refusal has to name the way out.
        GeneralException refused = assertThrows(GeneralException.class, () ->
                refuseUnlessLocalCopyMayAnswer(KEY, false, delegator));
        assertTrue(refused.getMessage().contains(PROPERTY_LOCAL_FALLBACK), "the refusal must name ["
                + PROPERTY_LOCAL_FALLBACK + "], because an operator cannot act on a refusal that does not say"
                + " what to do: [" + refused.getMessage() + "]");

        // The same, with the migration setting on: the local copy may answer.
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_LOCAL_FALLBACK, "true");
        ContentStoreFactory.clearCache();
        assertDoesNotThrow(() -> refuseUnlessLocalCopyMayAnswer(KEY, false, delegator),
                "the documented migration setting must allow the local copy to answer");
    }

    @Test
    public void theStreamSeamReportsTheExactLengthOfWhatItServes(@TempDir Path home) throws Exception {
        // The event that serves object data to a browser sets Content-Length from this and refuses to serve
        // without one, so a length that does not match the bytes is not a cosmetic error.
        System.setProperty("ofbiz.home", home.toString());
        configureProvider(PROVIDER_FILESYSTEM);
        Path uploads = Files.createDirectories(home.resolve("runtime/uploads"));
        byte[] content = "bytes whose length the consumer will trust".getBytes(StandardCharsets.UTF_8);
        Files.write(uploads.resolve("sized.bin"), content);

        Map<String, Object> served = DataResourceWorker.getDataResourceStream(
                fileResource("OFBIZ_FILE_BIN", "/runtime/uploads/sized.bin", "10000",
                        seamDelegator("default", null)), "N", null, null, null, false);
        assertEquals((long) content.length, served.get("length"), "the reported length must be the exact"
                + " number of bytes served");
        try (InputStream stream = (InputStream) served.get("stream")) {
            assertArrayEquals(content, stream.readAllBytes(), "the stream must carry the content itself");
        }
    }

    @Test
    public void contentRoundTripsThroughALiveObjectStore(@TempDir Path home) throws Exception {
        assumeTrue(liveObjectStoreIsReachable(), "no S3-compatible endpoint is reachable at " + LIVE_ENDPOINT);
        System.setProperty("ofbiz.home", home.toString());
        Path uploads = Files.createDirectories(home.resolve("runtime/uploads"));
        // Deliberately different from what goes into the bucket, so that "served from the store" and "served
        // from disk" cannot be confused. A test where both answers look the same proves nothing.
        Files.writeString(uploads.resolve("round-trip.txt"), "the copy on this instance's disk");
        configureLiveObjectStore();
        ContentStore store = ContentStoreFactory.getContentStore();
        assertNotNull(store, "the object store must be selected for this test to mean anything");
        String key = ContentStoreFactory.storeKey(store, seamDelegator("default", null), LIVE_RESOURCE_ID, null);
        byte[] inTheBucket = "the authoritative copy in the object store".getBytes(StandardCharsets.UTF_8);
        try {
            // put -> exists -> get -> openStream, the whole contract against a real store.
            store.put(key, inTheBucket);
            assertTrue(store.exists(key), "content just written must be reported as present");
            assertArrayEquals(inTheBucket, store.get(key), "a whole read must return what was written");
            try (InputStream streamed = store.openStream(key)) {
                assertArrayEquals(inTheBucket, streamed.readAllBytes(), "a streamed read must agree with a"
                        + " whole read");
            }
            // ...and the worker seam must serve the bucket's bytes, not the ones beside it on disk.
            assertEquals(new String(inTheBucket, StandardCharsets.UTF_8), renderedThroughSeam("OFBIZ_FILE",
                    "/runtime/uploads/round-trip.txt", null, seamDelegator("default", null), LIVE_RESOURCE_ID),
                    "the seam must serve the object store's content, not the local copy beside it");

            // The seam reads and only reads. Both sides are re-checked because a read-through that wrote
            // either way - refreshing the local copy, or pushing it back into the bucket - would make the
            // two converge and quietly destroy whichever one was authoritative.
            assertTrue(store.exists(key), "a read must not remove what it read");
            assertArrayEquals(inTheBucket, store.get(key), "a read must not rewrite what it read");
            assertEquals("the copy on this instance's disk",
                    Files.readString(uploads.resolve("round-trip.txt")), "a read served from the store must"
                    + " leave the local copy exactly as it was");
        } finally {
            store.delete(key);
            ContentStoreFactory.clearCache();
        }
        assertFalse(reopenLiveStore().exists(key), "content must be gone once it is deleted");
    }

    @Test
    public void aLiveObjectStoreThatHoldsNothingRefusesRatherThanServingTheLocalCopy(@TempDir Path home)
            throws Exception {
        assumeTrue(liveObjectStoreIsReachable(), "no S3-compatible endpoint is reachable at " + LIVE_ENDPOINT);
        System.setProperty("ofbiz.home", home.toString());
        Path uploads = Files.createDirectories(home.resolve("runtime/uploads"));
        Files.writeString(uploads.resolve("orphan.txt"), "a local copy the store never received");
        configureLiveObjectStore();

        GeneralException refused = assertThrows(GeneralException.class, () -> renderedThroughSeam("OFBIZ_FILE",
                "/runtime/uploads/orphan.txt", null, seamDelegator("default", null), LIVE_ABSENT_RESOURCE_ID),
                "a real store holding nothing must refuse rather than serve the local copy");
        assertTrue(refused.getMessage().contains(PROPERTY_LOCAL_FALLBACK), "the refusal must name the migration"
                + " setting: [" + refused.getMessage() + "]");

        // With the migration setting on, the same read is allowed to answer from disk.
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_LOCAL_FALLBACK, "true");
        ContentStoreFactory.clearCache();
        assertEquals("a local copy the store never received", renderedThroughSeam("OFBIZ_FILE",
                "/runtime/uploads/orphan.txt", null, seamDelegator("default", null), LIVE_ABSENT_RESOURCE_ID),
                "the documented migration setting must let existing local content keep being served");
    }

    @Test
    public void oneIdentifierUnderTwoTenanciesReachesTwoObjectsInALiveStore(@TempDir Path home) throws Exception {
        assumeTrue(liveObjectStoreIsReachable(), "no S3-compatible endpoint is reachable at " + LIVE_ENDPOINT);
        System.setProperty("ofbiz.home", home.toString());
        Path uploads = Files.createDirectories(home.resolve("runtime/uploads"));
        Files.writeString(uploads.resolve("tenanted.txt"), "never served in this test");
        configureLiveObjectStore();
        ContentStore store = ContentStoreFactory.getContentStore();
        Delegator base = seamDelegator("default", null);
        Delegator tenant = seamDelegator("default", "DEMO1");
        String baseKey = ContentStoreFactory.storeKey(store, base, LIVE_RESOURCE_ID, null);
        String tenantKey = ContentStoreFactory.storeKey(store, tenant, LIVE_RESOURCE_ID, null);
        assertNotEquals(baseKey, tenantKey, "two tenancies must not share one key");
        try {
            store.put(baseKey, "the base tenancy's content".getBytes(StandardCharsets.UTF_8));
            store.put(tenantKey, "the DEMO1 tenancy's content".getBytes(StandardCharsets.UTF_8));
            assertEquals("the base tenancy's content", renderedThroughSeam("OFBIZ_FILE",
                    "/runtime/uploads/tenanted.txt", null, base, LIVE_RESOURCE_ID),
                    "the base delegator must reach the base tenancy's object");
            assertEquals("the DEMO1 tenancy's content", renderedThroughSeam("OFBIZ_FILE",
                    "/runtime/uploads/tenanted.txt", null, tenant, LIVE_RESOURCE_ID),
                    "a tenant delegator must reach its own object and never the base tenancy's");
        } finally {
            store.delete(baseKey);
            store.delete(tenantKey);
        }
    }

    @Test
    public void aReadThroughTheSeamWritesNothingAndLeavesNothingBehind(@TempDir Path home) throws Exception {
        // The staging, spooling and free-space machinery this seam used to carry created temporary files in
        // the deployment's own tree and then had to remember to remove them - and could not, when a
        // transaction rolled back after the file was already in place. It is gone, and this proves it at run
        // time rather than by reading the source: every path, size and modification time under the
        // deployment root is identical after two reads to what it was before them. A directory's own
        // modification time changes when an entry appears in it, so even a temporary file that was created
        // and then tidied up would be caught here.
        System.setProperty("ofbiz.home", home.toString());
        configureProvider(PROVIDER_FILESYSTEM);
        Path uploads = Files.createDirectories(home.resolve("runtime/uploads"));
        Files.writeString(uploads.resolve("read-only.txt"), "content a read must not disturb");
        Map<String, String> before = snapshotOf(home);

        assertEquals("content a read must not disturb", renderedThroughSeam("OFBIZ_FILE",
                "/runtime/uploads/read-only.txt", null, seamDelegator("default", null), "10000"),
                "the seam must serve the content it is asked for");
        Map<String, Object> streamed = DataResourceWorker.getDataResourceStream(
                fileResource("OFBIZ_FILE_BIN", "/runtime/uploads/read-only.txt", "10000",
                        seamDelegator("default", null)), "N", null, null, null, false);
        try (InputStream stream = (InputStream) streamed.get("stream")) {
            stream.readAllBytes();
        }

        assertEquals(before, snapshotOf(home), "a read must leave the deployment's tree exactly as it found"
                + " it: no staged file, no spool file, nothing to roll back and nothing to clean up");
    }

    @Test
    public void theFileHandedToAWriterIsAlwaysTheLocalOne(@TempDir Path home) throws Exception {
        // getContentFile resolves a write target as often as a read location: createFile, createBinaryFile
        // and updateBinaryFile take the File it returns and write to it. Serving it from a store would hand
        // a writer a stale copy and then lose the write. The object store is configured and pointed at an
        // endpoint that refuses every connection, so a single request would fail this test.
        System.setProperty("ofbiz.home", home.toString());
        configureProvider(PROVIDER_S3);
        Path uploads = Files.createDirectories(home.resolve("runtime/uploads"));
        Path target = uploads.resolve("write-target.txt");
        Files.writeString(target, "what the deployment already holds");

        File resolved = DataResourceWorker.getContentFile("OFBIZ_FILE", "/runtime/uploads/write-target.txt",
                null);

        assertEquals(target.toRealPath(), resolved.toPath().toRealPath(), "the file handed back must be the"
                + " local location the objectInfo names");
        assertEquals("what the deployment already holds", Files.readString(target), "resolving a write target"
                + " must not overwrite what is already there");
        // And a writer's bytes land on this instance's disk, at that location and nowhere else.
        Files.writeString(resolved.toPath(), "what a writer just wrote");
        assertEquals("what a writer just wrote", Files.readString(target), "a write through the resolved file"
                + " must land at the location the objectInfo names");
        // An absent write target stays absent, because a writer has to be able to create it.
        assertThrows(FileNotFoundException.class, () -> DataResourceWorker.getContentFile("OFBIZ_FILE",
                "/runtime/uploads/never-created.txt", null), "an absent location must stay absent rather than"
                + " being reported as present because a store might hold something");
    }

    @Test
    public void manyThreadsResolvingOneConfigurationShareOneProvider() throws Exception {
        // A fleet instance resolves the provider on every content read, from every request thread at once.
        // One immutable resolution per configuration is what the factory promises. Two would mean two
        // clients and two connection pools, and - because a displaced provider is closed - a thread still
        // holding the displaced one would start failing on a closed client.
        configureProvider(PROVIDER_S3);
        ContentStore first = ContentStoreFactory.getContentStore();
        assertNotNull(first, "the object store must be selected for this test to mean anything");
        CountDownLatch startTogether = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_THREADS);
        try {
            List<Future<Integer>> answers = new ArrayList<>();
            for (int thread = 0; thread < CONCURRENT_THREADS; thread++) {
                answers.add(pool.submit(() -> {
                    startTogether.await();
                    int differed = 0;
                    for (int attempt = 0; attempt < CONCURRENT_ATTEMPTS; attempt++) {
                        if (ContentStoreFactory.getContentStore() != first) {
                            differed++;
                        }
                    }
                    return differed;
                }));
            }
            startTogether.countDown();
            for (Future<Integer> answer : answers) {
                assertEquals(0, answer.get(CONCURRENT_TIMEOUT_SECONDS, TimeUnit.SECONDS).intValue(),
                        "every concurrent resolution of one configuration must hand back the same provider");
            }
        } finally {
            pool.shutdownNow();
        }
        assertSame(first, ContentStoreFactory.getContentStore(), "the resolution must survive the load that"
                + " was just put through it");
    }

    @Test
    public void aProviderServesManyConcurrentReadsOfTheSameContent(@TempDir Path home) throws Exception {
        // One provider instance is shared by every request thread, so it has to be safe for concurrent use.
        // This provider walks the tree before every operation, reading each ancestor as it goes, which is
        // precisely the kind of work that a shared mutable field would corrupt under load.
        FileSystemContentStore store = filesystemStoreRootedAt(home);
        String key = "runtime/uploads/party/shared.bin";
        store.put(key, PAYLOAD);
        CountDownLatch startTogether = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_THREADS);
        try {
            List<Future<Integer>> answers = new ArrayList<>();
            for (int thread = 0; thread < CONCURRENT_THREADS; thread++) {
                answers.add(pool.submit(() -> {
                    startTogether.await();
                    int served = 0;
                    for (int attempt = 0; attempt < CONCURRENT_ATTEMPTS; attempt++) {
                        assertTrue(store.exists(key), "content that is there must be reported as present");
                        assertArrayEquals(PAYLOAD, store.get(key), "a whole read must yield the stored bytes");
                        try (InputStream opened = store.openStream(key)) {
                            assertArrayEquals(PAYLOAD, opened.readAllBytes(), "a streamed read must yield the"
                                    + " stored bytes");
                        }
                        served++;
                    }
                    return served;
                }));
            }
            startTogether.countDown();
            for (Future<Integer> answer : answers) {
                assertEquals(CONCURRENT_ATTEMPTS, answer.get(CONCURRENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .intValue(), "every concurrent read must be served");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void objectStoreConfigurationSuppliedOnlyThroughTheDelegatorReachesTheProvider() throws Exception {
        // The deployment this reproduces: content.properties ships blank object-store settings and an
        // operator configures the store with SystemProperty rows instead. Reading the selector through the
        // delegator and then building the provider from the property file alone selected s3 and refused it
        // in the same breath for having no bucket - a provider configured from a layer other than the one
        // that chose it. Every value below therefore comes from the delegator and from nowhere else.
        withoutObjectStoreConfigurationInThePropertyFile();
        Delegator delegator = seamDelegator("default", null, Map.of(
                PROPERTY_PROVIDER, PROVIDER_S3,
                PROPERTY_S3_BUCKET, DATABASE_BUCKET,
                PROPERTY_S3_REGION, REGION,
                PROPERTY_S3_ENDPOINT, ENDPOINT,
                PROPERTY_S3_ACCESS_KEY_ID, ACCESS_KEY_ID,
                PROPERTY_S3_SECRET_ACCESS_KEY, SECRET_ACCESS_KEY,
                PROPERTY_S3_PATH_STYLE, "true"));

        // Read without a delegator the same configuration is database storage, which is what makes the
        // assertions below statements about the delegator rather than about the property file.
        assertNull(ContentStoreFactory.getContentStore(), "the property file alone must still mean database"
                + " storage, otherwise this case proves nothing");

        ContentStore selected = ContentStoreFactory.getContentStore(delegator);

        assertTrue(selected instanceof S3ContentStore, "a provider selected through the delegator must also be"
                + " built through it, was: " + (selected == null ? "database storage" : selected.getClass().getName()));
        assertEquals(DATABASE_BUCKET, bucketOf(selected), "the provider must be built from the same layer the"
                + " selector was read from");
    }

    @Test
    public void aBucketChangedOnlyInTheDatabaseIsResolvedAfreshAndUsedByTheRebuiltProvider() throws Exception {
        withoutObjectStoreConfigurationInThePropertyFile();
        ContentStore first = ContentStoreFactory.getContentStore(objectStoreDelegatorWithBucket(DATABASE_BUCKET));

        assertEquals(DATABASE_BUCKET, bucketOf(first), "the first resolution must use the configured bucket");

        // Same delegator name, one changed row. The resolution held for that scope is keyed by a digest of
        // the whole configuration, so it has to be replaced - and the replacement has to be built from the
        // value that changed. Turning the cache over and then rebuilding from the property file would be
        // worse than not turning it over at all: the operator would see the change take effect and the
        // provider would keep using the old value.
        ContentStore second = ContentStoreFactory.getContentStore(objectStoreDelegatorWithBucket(OTHER_BUCKET));

        assertNotSame(first, second, "a value changed in the database must be resolved afresh");
        assertEquals(OTHER_BUCKET, bucketOf(second), "the rebuilt provider must use the changed value");
    }

    @Test
    public void aKeyPrefixSuppliedOnlyThroughTheDelegatorIsAppliedToEveryObjectKey() throws Exception {
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_KEY_PREFIX, "");
        Delegator delegator = seamDelegator("default", null, Map.of(PROPERTY_S3_KEY_PREFIX, "tenants/acme"));
        S3Client client = mock(S3Client.class);
        ContentStore store = new S3ContentStore(client, BUCKET, delegator);

        store.put(KEY, PAYLOAD);

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture(), any(RequestBody.class));
        assertEquals("tenants/acme/" + KEY, request.getValue().key(), "a prefix supplied through the delegator"
                + " must reach the object the store is asked for");
    }

    @Test
    public void aReadBoundSuppliedOnlyThroughTheDelegatorIsEnforced(@TempDir Path home) throws Exception {
        Delegator delegator = seamDelegator("default", null, Map.of(PROPERTY_MAX_OBJECT_SIZE, "1024"));
        FileSystemContentStore fromThePropertyFile = filesystemStoreRootedAt(home);
        String key = "runtime/uploads/party/large.bin";
        Path backing = home.resolve(key);
        Files.createDirectories(backing.getParent());
        Files.write(backing, new byte[4096]);

        // The committed ceiling is 10 MiB, so this read is well inside it: the refusal below is caused by
        // the override and by nothing else.
        assertEquals(4096, fromThePropertyFile.get(key).length, "the committed ceiling must leave this read"
                + " alone, otherwise the refusal below would prove nothing");

        FileSystemContentStore throughTheDelegator = new FileSystemContentStore(delegator);
        IOException refused = assertThrows(IOException.class, () -> throughTheDelegator.get(key));

        assertFalse(refused instanceof FileNotFoundException, "an oversized file is present, not absent: "
                + refused);
        assertTrue(refused.getMessage().contains(PROPERTY_MAX_OBJECT_SIZE), "the refusal must name the setting"
                + " to change: " + refused.getMessage());
    }

    @Test
    public void aProviderSelectedWithoutADelegatorStillReadsThePropertyFileAlone() throws Exception {
        Delegator delegator = seamDelegator("default", null, Map.of(PROPERTY_S3_BUCKET, DATABASE_BUCKET));
        ContentStore fromThePropertyFile = storeConfiguredAs(PROVIDER_S3);

        assertTrue(fromThePropertyFile instanceof S3ContentStore, "the file-only path must keep selecting the"
                + " object-storage provider");
        assertEquals(BUCKET, bucketOf(fromThePropertyFile), "a delegator-aware construction must not have made"
                + " the file-only path depend on a delegator being present");

        // The same file-configured provider, resolved through a delegator that overrides one value: the
        // layering is what OFBiz gives every other property, and the two scopes are held separately.
        ContentStore throughTheDelegator = ContentStoreFactory.getContentStore(delegator);

        assertEquals(DATABASE_BUCKET, bucketOf(throughTheDelegator), "an override must win over the file value");
        assertNotSame(fromThePropertyFile, throughTheDelegator, "the two scopes must be resolved separately");
    }

    /**
     * Records every path under a root with its kind, size and modification time.
     *
     * @param root the tree to record
     * @return the recording, ordered so that two are directly comparable
     * @throws IOException if the tree cannot be read
     */
    private static Map<String, String> snapshotOf(Path root) throws IOException {
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(root)) {
            paths = walk.sorted().toList();
        }
        Map<String, String> tree = new TreeMap<>();
        for (Path path : paths) {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            tree.put(root.relativize(path).toString(), (attributes.isDirectory() ? "dir" : "file")
                    + " size=" + attributes.size() + " modified=" + attributes.lastModifiedTime());
        }
        return tree;
    }

    /**
     * Builds the delegator the seam needs, stubbed only as far as the seam actually reaches, with no
     * {@code SystemProperty} row behind it.
     *
     * @param base the delegator base name
     * @param tenantId the tenant identifier, or null for the base tenancy
     * @return the stubbed delegator
     */
    private static Delegator seamDelegator(String base, String tenantId) {
        return seamDelegator(base, tenantId, Map.of());
    }

    /**
     * Builds the delegator the seam needs, stubbed only as far as the seam actually reaches, answering a
     * set of {@code SystemProperty} rows.
     *
     * <p>Four things are consulted. The scope getters supply the tenant scope a key is derived from.
     * {@code getDelegator} is consulted because {@code EntityQuery.use} takes a {@code DelegatorProvider} and
     * asks it for the delegator. {@code findList} answers the {@code SystemProperty} lookup that
     * {@code EntityUtilProperties} performs first: an empty answer means "no override in the database", which
     * makes it fall through to the property file - exactly what a deployment without an override does - and a
     * row means the database holds a value for that property, which is the layer a running deployment
     * configures through and the one a property file alone cannot stand in for.
     *
     * <p>The rows are answered by reading the property name out of the condition the query built, so one
     * delegator answers every property independently rather than the same value for all of them. The row
     * values are turned into stand-in entity values up front, because building one inside the answer would
     * mean stubbing a mock from inside another mock's invocation.
     *
     * @param base the delegator base name
     * @param tenantId the tenant identifier, or null for the base tenancy
     * @param systemProperties the {@code content} resource rows the database is to hold, by property name
     * @return the stubbed delegator
     */
    private static Delegator seamDelegator(String base, String tenantId, Map<String, String> systemProperties) {
        Delegator delegator = mock(Delegator.class);
        when(delegator.getDelegatorName()).thenReturn(tenantId == null ? base : base + "#" + tenantId);
        when(delegator.getDelegatorBaseName()).thenReturn(base);
        when(delegator.getDelegatorTenantId()).thenReturn(tenantId);
        when(delegator.getDelegator()).thenReturn(delegator);
        Map<String, GenericValue> rows = new LinkedHashMap<>();
        for (Map.Entry<String, String> row : systemProperties.entrySet()) {
            GenericValue held = mock(GenericValue.class);
            when(held.getString("systemPropertyValue")).thenReturn(row.getValue());
            rows.put(row.getKey(), held);
        }
        try {
            when(delegator.findList(anyString(), any(), any(), any(), any(), any(), anyBoolean()))
                    .thenAnswer(invocation -> systemPropertyRow(invocation.getArgument(1), rows));
        } catch (GenericEntityException stubbing) {
            // Declared by the interface method, unreachable on a mock: nothing queries a database here.
            throw new IllegalStateException("a mock cannot raise the interface's checked exception", stubbing);
        }
        return delegator;
    }

    /**
     * Answers a {@code SystemProperty} lookup from a set of rows.
     *
     * @param condition the condition the query built, which carries the resource and the property name
     * @param rows the rows the database is to hold, by property name
     * @return the single row the lookup finds, or an empty list when the database holds none
     */
    private static List<GenericValue> systemPropertyRow(Object condition, Map<String, GenericValue> rows) {
        if (!(condition instanceof EntityFieldMap lookup)) {
            return Collections.emptyList();
        }
        Object resource = lookup.getField("systemResourceId");
        Object property = lookup.getField("systemPropertyId");
        if (!RESOURCE.equals(resource) || property == null) {
            return Collections.emptyList();
        }
        GenericValue held = rows.get(property.toString());
        return held == null ? Collections.emptyList() : List.of(held);
    }

    /**
     * Builds the {@code DataResource} row the stream seam reads, stubbed only as far as that branch reaches.
     *
     * @param typeId the {@code dataResourceTypeId}
     * @param objectInfo the recorded location
     * @param dataResourceId the immutable identifier
     * @param delegator the delegator the row was read through
     * @return the stubbed row
     */
    private static GenericValue fileResource(String typeId, String objectInfo, String dataResourceId,
            Delegator delegator) {
        GenericValue dataResource = mock(GenericValue.class);
        when(dataResource.getString("dataResourceTypeId")).thenReturn(typeId);
        when(dataResource.getString("dataResourceId")).thenReturn(dataResourceId);
        when(dataResource.getString("objectInfo")).thenReturn(objectInfo);
        when(dataResource.getDelegator()).thenReturn(delegator);
        return dataResource;
    }

    /**
     * Renders through the worker's private read seam.
     *
     * <p>Reflection rather than a widened signature: the seam is deliberately not API, and making it visible
     * for a test would invite a caller that has no identity to reach it.
     *
     * @param typeId the {@code dataResourceTypeId}
     * @param objectInfo the recorded location
     * @param rootDir the context root, used only by the {@code CONTEXT_FILE} branch
     * @param delegator the delegator carrying the tenant scope
     * @param dataResourceId the immutable identifier
     * @return what the seam rendered
     * @throws Exception whatever the seam threw, unwrapped from the reflective call
     */
    private static String renderedThroughSeam(String typeId, String objectInfo, String rootDir,
            Delegator delegator, String dataResourceId) throws Exception {
        Method seam = DataResourceWorker.class.getDeclaredMethod("renderFile", String.class, String.class,
                String.class, Appendable.class, Delegator.class, String.class);
        seam.setAccessible(true);
        StringBuilder out = new StringBuilder();
        try {
            seam.invoke(null, typeId, objectInfo, rootDir, out, delegator, dataResourceId);
        } catch (InvocationTargetException reflected) {
            throw (Exception) reflected.getCause();
        }
        return out.toString();
    }

    /**
     * Invokes the worker's private fail-closed decision directly.
     *
     * @param key the provider key that holds nothing
     * @param absentLocally whether the location holds nothing on disk either
     * @param delegator the delegator the fallback setting is resolved through
     * @throws Exception whatever the decision threw, unwrapped from the reflective call
     */
    private static void refuseUnlessLocalCopyMayAnswer(String key, boolean absentLocally, Delegator delegator)
            throws Exception {
        Method decision = DataResourceWorker.class.getDeclaredMethod("refuseUnlessLocalCopyMayAnswer",
                String.class, boolean.class, Delegator.class, FileNotFoundException.class);
        decision.setAccessible(true);
        try {
            decision.invoke(null, key, absentLocally, delegator, new FileNotFoundException("absent"));
        } catch (InvocationTargetException reflected) {
            throw (Exception) reflected.getCause();
        }
    }

    /**
     * Points the configuration at the live S3-compatible endpoint the round-trip tests use.
     */
    private static void configureLiveObjectStore() {
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_ENDPOINT, LIVE_ENDPOINT);
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_BUCKET, LIVE_BUCKET);
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_ACCESS_KEY_ID, LIVE_ACCESS_KEY_ID);
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_SECRET_ACCESS_KEY, LIVE_SECRET_ACCESS_KEY);
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_PATH_STYLE, "true");
        configureProvider(PROVIDER_S3);
    }

    /**
     * Resolves the live provider again after the cache has been cleared, for an assertion that must not reuse
     * the client the test body used.
     *
     * @return a freshly resolved provider
     * @throws GeneralException if the provider cannot be built
     */
    private static ContentStore reopenLiveStore() throws GeneralException {
        configureLiveObjectStore();
        return ContentStoreFactory.getContentStore();
    }

    /**
     * Reports whether the live S3-compatible endpoint can be reached, so the round-trip tests skip rather than
     * fail where no object store is running.
     *
     * <p>A plain socket connect, because it answers the only question that matters - is anything listening -
     * without depending on the store's own health route or on credentials being right.
     *
     * @return true when something accepts a connection on the configured endpoint
     */
    private static boolean liveObjectStoreIsReachable() {
        URI endpoint = URI.create(LIVE_ENDPOINT);
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(endpoint.getHost(), endpoint.getPort()),
                    LIVE_PROBE_TIMEOUT_MILLIS);
            return true;
        } catch (IOException unreachable) {
            Debug.logInfo("No S3-compatible endpoint at " + LIVE_ENDPOINT + ", so the live round-trip tests are"
                    + " skipped: " + unreachable.getMessage(), MODULE);
            return false;
        }
    }

    /**
     * Writes the object-store configuration every case that selects that provider needs.
     *
     * <p>Installed for every test rather than only the object-store ones, so that a selection case can
     * never accidentally assert a refusal that came from missing configuration.
     */
    private static void installOfflineObjectStoreConfiguration() {
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_BUCKET, BUCKET);
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_REGION, REGION);
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_ENDPOINT, ENDPOINT);
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_ACCESS_KEY_ID, ACCESS_KEY_ID);
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_SECRET_ACCESS_KEY, SECRET_ACCESS_KEY);
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_PATH_STYLE, "true");
    }

    /**
     * Configures a provider value and proves the override reached the resource the factory reads.
     *
     * @param value the value to configure; never null, because the setter is backed by a
     *     {@code Hashtable} and blank is how an unset key is expressed
     */
    private static void configureProvider(String value) {
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_PROVIDER, value);
        assertEquals(value.trim(), UtilProperties.getPropertyValue(RESOURCE, PROPERTY_PROVIDER),
                "the in-memory override never reached the resource the factory reads");
    }

    /**
     * Configures a provider value and resolves it afresh.
     *
     * @param value the value to configure; never null
     * @return the provider the factory selects for that value, or {@code null} for database storage
     * @throws GeneralException if a named provider cannot be built from the configuration it needs
     */
    private static ContentStore storeConfiguredAs(String value) throws GeneralException {
        configureProvider(value);
        ContentStoreFactory.clearCache();
        return ContentStoreFactory.getContentStore();
    }

    /**
     * Configures a value that names a provider which cannot be built, and hands back the refusal.
     *
     * @param value the value to configure
     * @return the refusal the factory raised
     */
    private static GeneralException refusalOf(String value) {
        return assertThrows(GeneralException.class, () -> storeConfiguredAs(value),
                "[" + value + "] names a provider that cannot be built, so it must be refused");
    }

    /**
     * Configures an object-store endpoint, leaving the rest of the offline fixture in place.
     *
     * @param endpoint the endpoint to configure; never null, because blank is how an unset key is
     *     expressed and the setter is backed by a {@code Hashtable}
     */
    private static void configureEndpoint(String endpoint) {
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_ENDPOINT, endpoint);
    }

    /**
     * Configures an endpoint the provider must refuse, and asserts the refusal reports itself safely.
     *
     * <p>Four things at once, because an endpoint refusal that got any of them wrong would be worse
     * than none: the provider must refuse rather than build a client aimed somewhere unintended, it
     * must say which property is at fault so the deployment can be corrected, it must repeat neither
     * the value it was handed nor the credentials it holds - the value may itself be a credential, and
     * the refusal is destined for a log - and it must carry no cause, because a
     * {@code GeneralException} publishes a cause's message through its own.
     *
     * @param endpoint the endpoint to configure
     * @return the refusal, so a caller can assert more about the reason it gives
     */
    private static GeneralException refusalOfEndpoint(String endpoint) {
        configureEndpoint(endpoint);
        GeneralException refusal = refusalOf(PROVIDER_S3);
        String reported = refusal.getMessage();

        assertTrue(reported.contains(PROPERTY_S3_ENDPOINT), "[" + endpoint + "] must be refused by the name of"
                + " the property that carries it, was: " + reported);
        assertFalse(reported.contains(ENDPOINT_CREDENTIAL), "a refusal must not repeat the endpoint it was"
                + " given, because that endpoint may itself carry a credential: " + reported);
        assertFalse(reported.contains(ACCESS_KEY_ID) || reported.contains(SECRET_ACCESS_KEY), "a refusal must"
                + " not echo the configured credentials: " + reported);
        // The cause is the second way the value could escape, and the easier one to reintroduce:
        // GeneralException.getMessage() appends the message of any cause it is given, and a
        // URISyntaxException always quotes the whole input it was handed.
        assertNull(refusal.getCause(), "no cause may be attached to an endpoint refusal, because its message"
                + " would be appended to this one: " + reported);
        return refusal;
    }

    /**
     * Renders a key so that a failure message stays readable, and stays valid XML in the test report.
     *
     * @param key the key a case exercised, which may be null or carry a control character
     * @return the key with every control character shown as its code point
     */
    private static String printable(String key) {
        if (key == null) {
            return "null";
        }
        StringBuilder rendered = new StringBuilder(key.length());
        for (int index = 0; index < key.length(); index++) {
            char character = key.charAt(index);
            if (Character.isISOControl(character)) {
                rendered.append(String.format("\\u%04x", (int) character));
            } else {
                rendered.append(character);
            }
        }
        return rendered.toString();
    }

    /**
     * Asserts that a failure arrived as the contract's failure rather than as an absence.
     *
     * @param thrown the exception the operation raised
     */
    private static void assertStoreFailure(IOException thrown) {
        assertFalse(thrown instanceof FileNotFoundException, "a store failure must not be reported as an"
                + " absence: " + thrown);
        assertNotNull(thrown.getCause(), "a store failure must keep the cause available: " + thrown);
    }

    /**
     * Builds the reply a compatible object store gives to a read, so the provider under test receives
     * the response type the SDK really hands it rather than a stand-in.
     *
     * @param content the bytes the store is to serve
     * @return an SDK response stream over the supplied content
     */
    private static ResponseInputStream<GetObjectResponse> storedObject(byte[] content) {
        return new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength((long) content.length).build(),
                AbortableInputStream.create(new ByteArrayInputStream(content)));
    }

    /**
     * Builds the failure a store reports with a particular error code, which is what absence is now
     * decided by.
     *
     * @param errorCode the code the store reports
     * @param status the HTTP status it reports alongside it
     * @return the failure to throw from a mocked client
     */
    private static S3Exception errorCoded(String errorCode, int status) {
        return (S3Exception) S3Exception.builder()
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(errorCode).build())
                .statusCode(status).message(errorCode).build();
    }

    /**
     * Points {@code ofbiz.home} at a directory the test owns, so the filesystem provider resolves its
     * keys inside a temporary tree instead of inside the working copy.
     *
     * <p>The {@code @AfterEach} restores whatever {@code ofbiz.home} was before, so this needs no
     * cleanup of its own.
     *
     * @param root the directory to use as the storage root
     * @return the provider rooted there
     * @throws GeneralException if the provider cannot be constructed
     */
    private static FileSystemContentStore filesystemStoreRootedAt(Path root) throws GeneralException {
        System.setProperty("ofbiz.home", root.toAbsolutePath().toString());
        return new FileSystemContentStore(null);
    }

    /**
     * Reads an entry's POSIX permissions.
     *
     * @param path the entry to read
     * @return its permissions
     * @throws IOException if they cannot be read
     */
    private static Set<PosixFilePermission> permissionsOf(Path path) throws IOException {
        return Files.getPosixFilePermissions(path);
    }

    /**
     * Blanks every object-store setting in the property file, leaving the delegator as the only place a
     * value can come from.
     *
     * <p>The {@code @AfterEach} puts the captured values back, so this needs no cleanup of its own.
     */
    private static void withoutObjectStoreConfigurationInThePropertyFile() {
        for (String property : new String[] {PROPERTY_PROVIDER, PROPERTY_S3_BUCKET, PROPERTY_S3_REGION,
                PROPERTY_S3_ENDPOINT, PROPERTY_S3_ACCESS_KEY_ID, PROPERTY_S3_SECRET_ACCESS_KEY,
                PROPERTY_S3_PATH_STYLE, PROPERTY_S3_KEY_PREFIX}) {
            UtilProperties.setPropertyValueInMemory(RESOURCE, property, "");
        }
        ContentStore fileOnly = assertDoesNotThrow(() -> ContentStoreFactory.getContentStore(),
                "a blanked property file must not fail a resolution");
        assertNull(fileOnly, "a blanked property file must read as database storage");
        ContentStoreFactory.clearCache();
    }

    /**
     * Builds a delegator whose {@code SystemProperty} rows are the whole object-store configuration.
     *
     * @param bucket the bucket the rows name
     * @return the delegator, named so that two of them share one resolution scope
     */
    private static Delegator objectStoreDelegatorWithBucket(String bucket) {
        return seamDelegator("default", null, Map.of(
                PROPERTY_PROVIDER, PROVIDER_S3,
                PROPERTY_S3_BUCKET, bucket,
                PROPERTY_S3_REGION, REGION,
                PROPERTY_S3_ENDPOINT, ENDPOINT,
                PROPERTY_S3_ACCESS_KEY_ID, ACCESS_KEY_ID,
                PROPERTY_S3_SECRET_ACCESS_KEY, SECRET_ACCESS_KEY,
                PROPERTY_S3_PATH_STYLE, "true"));
    }

    /**
     * Reads the bucket a constructed object-store provider will address.
     *
     * <p>Read reflectively because the field is private and deliberately stays that way: nothing outside
     * the provider has any business addressing a bucket, and the alternative - exposing it so a test can
     * see it - would widen the class's surface for the test's convenience. What is being asserted is not
     * an accessor but the invariant that the value came from the layer the selector came from, and this is
     * the only way to observe that without issuing a request to a store.
     *
     * @param store the provider to inspect
     * @return the bucket it was built with
     * @throws Exception if the field cannot be read, which would mean the provider had changed shape
     */
    private static String bucketOf(ContentStore store) throws Exception {
        assertTrue(store instanceof S3ContentStore, "only the object-store provider addresses a bucket, and this"
                + " one is: " + (store == null ? "database storage" : store.getClass().getName()));
        Field bucket = S3ContentStore.class.getDeclaredField("bucket");
        bucket.setAccessible(true);
        return (String) bucket.get(store);
    }
}
