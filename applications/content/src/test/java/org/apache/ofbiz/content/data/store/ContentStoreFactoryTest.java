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

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;
import javax.transaction.Status;
import javax.transaction.Synchronization;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.content.data.DataResourceWorker;
import org.apache.ofbiz.content.data.DataServices;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityFieldMap;
import org.apache.ofbiz.entity.transaction.TransactionUtil;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.DefaultRetryStrategy;
import software.amazon.awssdk.retries.api.RetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.utils.SdkAutoCloseable;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.withSettings;
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

    /** The name this class logs under. */
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
    /** Whether the provider may dial a plaintext endpoint to a host other than its own. */
    private static final String PROPERTY_S3_INSECURE_ENDPOINT = "content.store.s3.insecure.endpoint.allowed";

    /** The one byte an existence probe asks for, which is what makes a ranged GET cost what a HEAD did. */
    private static final String EXISTENCE_RANGE = "bytes=0-0";

    /** The status a store answers a range that lies past the end of an object with. */
    private static final int HTTP_RANGE_NOT_SATISFIABLE = 416;

    private static final String PROPERTY_S3_KEY_PREFIX = "content.store.s3.key.prefix";
    private static final String PROPERTY_MAX_OBJECT_SIZE = "content.store.max.object.size";
    private static final String PROPERTY_LOCAL_FALLBACK = "content.store.local.fallback";
    private static final String PROPERTY_S3_API_TIMEOUT = "content.store.s3.api.timeout.millis";
    private static final String PROPERTY_S3_ATTEMPT_TIMEOUT = "content.store.s3.attempt.timeout.millis";
    private static final String PROPERTY_S3_MAX_RETRIES = "content.store.s3.max.retries";

    /** The deadline a whole streamed response body is bound by. */
    private static final String PROPERTY_S3_STREAM_TIMEOUT = "content.store.s3.stream.total.timeout.millis";

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
        PROPERTY_S3_STREAM_TIMEOUT,
        // The plaintext-endpoint permission belongs here as much as any other, and for a stronger reason
        // than tidiness: setPropertyValueInMemory lasts for the life of the JVM, so a test that turned it
        // on and did not put it back left every LATER test in this JVM permitted to send its credentials
        // to a plaintext endpoint. A suite that relaxes a security control for its own convenience and
        // then forgets to restore it stops being able to prove that the control works.
        PROPERTY_S3_INSECURE_ENDPOINT,
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

    /**
     * The bucket name the offline cases hand to a provider built over a mocked client.
     *
     * <p>A name and nothing more. No endpoint, bucket, access key or secret of any real store is present in
     * this class: an earlier revision carried a working set of four, which is a credential in the repository
     * whatever the store is, made every clone address the same bucket with the same keys, and left the round
     * trips skipping themselves whenever the store was absent - reporting success for a contract nothing had
     * verified. The round trips now live in {@link ObjectStoreIntegrationTests}, which is given its settings
     * and fails without them. Nothing here opens a socket.
     */
    private static final String OFFLINE_BUCKET = "offline-fixture-bucket";

    /** How many threads the concurrency cases run at once; more than one core, so they genuinely overlap. */
    private static final int CONCURRENT_THREADS = 16;
    /** How many of the contending threads delete, the rest reading beside the single writer. */
    private static final int CONCURRENT_DELETERS = 5;
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
        // Removed first, and unconditionally: a seam left installed would make the next test in this JVM
        // resolve through a mock, a stubbed resolver or a substituted SDK its own configuration never
        // asked for.
        ContentStoreFactory.installConstructionForTesting(null);
        S3ContentStore.installSdkConstructionForTesting(null);
        S3ContentStore.installHostResolverForTesting(null);
        FileSystemContentStore.installInterleavedActionForTesting(null);
        FileSystemContentStore.installBeforeOpenActionForTesting(null);
        for (Map.Entry<String, String> committed : committedProperties.entrySet()) {
            UtilProperties.setPropertyValueInMemory(RESOURCE, committed.getKey(), committed.getValue());
        }
        if (committedOfbizHome == null) {
            System.clearProperty("ofbiz.home");
        } else {
            System.setProperty("ofbiz.home", committedOfbizHome);
        }
        ContentStoreFactory.clearCache();
        // The factory reports a misconfigured value once per JVM, which is right for a deployment and
        // wrong here: without this, whether a test sees the warning it asserts on depends on whether an
        // earlier test in this JVM happened to configure the same property to the same value, so the
        // suite would pass or fail according to the order the runner chose. Cleared last, so it is
        // cleared even for a test that reported a value while being torn down.
        ContentStoreFactory.clearReportedUnusableValues();
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
    public void aMisconfiguredValueIsReportedOncePerJvmAndTheDeDuplicationCanBeResetForTesting() {
        // Both halves of the same property, because each is what makes the other safe.
        //
        // Reporting once is deliberate: a bound is read on every content operation, so reporting per read
        // would fill the log with one operator mistake. But process-global de-duplication is also a hidden
        // dependency between tests in one JVM - a test asserting on this warning sees it only if no earlier
        // test happened to configure the same property to the same value, so the suite passes or fails
        // according to the order the runner chose, and a genuine regression in the reporting is masked by
        // whichever unrelated test ran first. That is what the reset exists for, and it is used in this
        // class's teardown, so this asserts it actually works rather than merely being callable.
        UtilProperties.setPropertyValueInMemory(RESOURCE, ContentStoreFactory.MAX_OBJECT_SIZE_PROPERTY,
                "not-a-number");
        try (MockedStatic<Debug> logging = mockStatic(Debug.class)) {
            ContentStoreFactory.maxObjectSize(null);
            ContentStoreFactory.maxObjectSize(null);
            ContentStoreFactory.maxObjectSize(null);
            logging.verify(() -> Debug.logWarning(contains("not-a-number"), anyString()), times(1));
        }
        try (MockedStatic<Debug> logging = mockStatic(Debug.class)) {
            ContentStoreFactory.maxObjectSize(null);
            logging.verify(() -> Debug.logWarning(contains("not-a-number"), anyString()), never());
        }

        ContentStoreFactory.clearReportedUnusableValues();

        try (MockedStatic<Debug> logging = mockStatic(Debug.class)) {
            ContentStoreFactory.maxObjectSize(null);
            logging.verify(() -> Debug.logWarning(contains("not-a-number"), anyString()), times(1));
        }
    }

    @Test
    public void theDelegatorFormResolvesEveryValueExactlyAsTheFileFormDoes() throws Exception {
        // The public delegator form exists so a SystemProperty row can override the documented tunables.
        // Whichever form is used, the selector is read from content.properties, which is the contract
        // asserted here - across the whole resolution table, so the overload cannot drift away from its
        // sibling, refusal included.
        configureProvider("nonsense");
        ContentStoreFactory.clearCache();
        assertThrows(GeneralException.class, () -> ContentStoreFactory.getContentStore((Delegator) null),
                "an unrecognised value must be refused through the delegator form too");

        for (String value : new String[] {"", PROVIDER_DATABASE, PROVIDER_FILESYSTEM, PROVIDER_S3}) {
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
        // object request into a disclosure of everything this deployment can reach.
        //
        // Every vector below denotes 169.254.169.254, 169.254.170.2 or fd00:ec2::254, and every one of them
        // is accepted as that address by the C resolver the SDK's HTTP client ends up using. A textual
        // comparison against the dotted-quad spelling therefore defends against nothing: 2852039166 shares
        // not one character with it. The list is the alternate-form matrix - one decimal number, hexadecimal
        // with and without dots, octal, the two- and three-part short forms, mixed bases, an IPv4-mapped IPv6
        // literal - plus the metadata host names, which are refused whether or not they resolve.
        String[] metadataEndpoints = {
            // The spellings everybody quotes.
            "http://169.254.169.254/",
            "https://169.254.169.254/latest/meta-data/iam/security-credentials/",
            "http://[fd00:ec2::254]/latest/meta-data/",
            "http://169.254.170.2/v2/credentials",
            // One decimal number: 169*2^24 + 254*2^16 + 169*2^8 + 254.
            "http://2852039166/",
            // Hexadecimal, whole and per-octet.
            "http://0xA9FEA9FE/",
            "http://0xa9.0xfe.0xa9.0xfe/",
            // Octal, per-octet.
            "http://0251.0376.0251.0376/",
            // Short forms: three parts and two parts.
            "http://169.254.43518/",
            "http://169.16689150/",
            // Mixed bases in one literal.
            "http://0251.254.0xa9fe/",
            // The IPv4-mapped and IPv4-compatible IPv6 literals.
            "http://[::ffff:169.254.169.254]/",
            "http://[0:0:0:0:0:ffff:a9fe:a9fe]/",
            // The container credentials address in the same alternate forms.
            "http://2852039682/",
            // The IPv6 metadata address without brackets in the authority is not a legal URI, but its
            // upper-case spelling is, and case must not be a bypass either.
            "http://[FD00:EC2::254]/",
            // Metadata host names, refused by name.
            "http://metadata.google.internal/computeMetadata/v1/",
            "http://instance-data/latest/meta-data/",
        };
        for (String metadata : metadataEndpoints) {
            GeneralException refusal = refusalOfEndpoint(metadata);

            assertTrue(refusal.getMessage().contains("instance metadata"), "[" + metadata + "] must be refused"
                    + " for naming an instance metadata address rather than for an incidental reason, was: "
                    + refusal.getMessage());
            assertFalse(refusal.getMessage().contains(metadata), "the refusal must not quote the configured"
                    + " endpoint back, because an endpoint may carry a credential: " + refusal.getMessage());
        }
    }

    @Test
    public void anEndpointThatIsMerelyNumericIsStillAccepted() throws Exception {
        // The other half of the matrix, and the half that would break every real deployment if the
        // canonicalisation were too eager: an ordinary address written in an alternate form is not a
        // metadata address and must be accepted. 127.0.0.1 as one decimal number is 2130706433.
        //
        // The plaintext permission is granted here EXPLICITLY, and only here, because this case isolates
        // the metadata-address policy from the transport policy: several of the addresses below are
        // plaintext and not on this host, which the transport policy refuses on its own account, and
        // asserting them without saying so would be asserting two policies at once and passing only while
        // some other test happened to have relaxed one of them. That the transport policy REFUSES such an
        // endpoint unless it is permitted is proved by
        // aPlaintextEndpointIsRefusedUnlessItIsOnThisHostOrExplicitlyPermitted, and the restore hook puts
        // this value back so no later test inherits it.
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_INSECURE_ENDPOINT, "true");
        String[] ordinaryEndpoints = {"http://127.0.0.1:9000/", "http://2130706433:9000/",
                "http://0x7f000001:9000/", "http://[::1]:9000/", "https://10.0.0.7:9000/",
                "http://169.253.169.254/", "http://169.255.169.254/"};
        for (String endpoint : ordinaryEndpoints) {
            configureEndpoint(endpoint);
            assertNotNull(storeConfiguredAs(PROVIDER_S3), "[" + endpoint + "] must be accepted: it is"
                    + " not a metadata address, and refusing it would refuse a real deployment");
        }
    }

    @Test
    public void aNameThatResolvesToAMetadataAddressIsRefusedAndOneThatDoesNotIsAccepted() throws Exception {
        // The DNS-alias policy, asserted with no name server: an alias for the metadata service is refused
        // however it is spelled, because the ADDRESS is what is judged.
        S3ContentStore.installHostResolverForTesting(host -> {
            if ("store.example.internal".equals(host)) {
                return new InetAddress[] {InetAddress.getByName("10.0.0.7")};
            }
            if ("alias.example.internal".equals(host)) {
                // A name whose second answer is the metadata address: every answer has to be judged, not
                // just the first, or a resolver that round-robins would let the refusal through half the time.
                return new InetAddress[] {InetAddress.getByName("10.0.0.7"),
                    InetAddress.getByName("169.254.169.254")};
            }
            throw new UnknownHostException(host);
        });
        try {
            GeneralException refusal = refusalOfEndpoint("https://alias.example.internal:9000/");
            assertTrue(refusal.getMessage().contains("resolve to"), "the refusal must say the name resolved to"
                    + " a metadata address: " + refusal.getMessage());

            configureEndpoint("https://store.example.internal:9000/");
            assertNotNull(storeConfiguredAs(PROVIDER_S3), "a name that resolves to an ordinary address"
                    + " must be accepted");

            // And a name that does not resolve at all is accepted rather than refused: a container is
            // routinely started before its resolver or its private zone is reachable, and the SDK resolves
            // the name again on every connection in any case.
            configureEndpoint("https://not-yet-in-dns.example.internal:9000/");
            assertNotNull(storeConfiguredAs(PROVIDER_S3), "an unresolvable name must not stop the"
                    + " provider being built");
        } finally {
            S3ContentStore.installHostResolverForTesting(null);
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
        // Asked with a ranged GET rather than a HEAD: a HEAD answers 404 with no error document, so the SDK
        // cannot say whether the key, the bucket or the endpoint was the missing thing.
        verify(client).getObject(eq(GetObjectRequest.builder().bucket(BUCKET).key(KEY)
                .range(EXISTENCE_RANGE).build()));

        store.delete(KEY);
        verify(client).deleteObject(eq(DeleteObjectRequest.builder().bucket(BUCKET).key(KEY).build()));
    }

    @Test
    public void onlyTheExistenceOperationEverAsksWhetherAKeyIsThere() throws Exception {
        // Presence is asked about exactly once, by the one operation whose answer it is. A read, a write or
        // a removal that probed first would let a caller learn whether a key exists without being entitled
        // to its content, and would cost a second round trip for nothing. The existence probe is the only
        // request that carries a range, so a range is what identifies it.
        S3Client client = mock(S3Client.class);
        when(client.getObject(any(GetObjectRequest.class))).thenAnswer(invocation -> storedObject(PAYLOAD));
        ContentStore store = new S3ContentStore(client, BUCKET);

        store.put(KEY, PAYLOAD);
        store.get(KEY);
        store.openStream(KEY).close();
        store.delete(KEY);

        verify(client, never()).getObject(argThat((GetObjectRequest request) -> request.range() != null));
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
        //
        // exists is asserted here alongside the reads, and it can be: it asks with a ranged GET, which
        // carries an error document, so the error code that separates these two failures from a missing
        // key reaches the provider. A HEAD carries no body, which is why it could not tell them apart and
        // answered false to all three.
        S3Exception missingBucket = (S3Exception) NoSuchBucketException.builder()
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchBucket").build())
                .statusCode(HTTP_NOT_FOUND).message("no such bucket").build();
        S3Exception bodyless = (S3Exception) S3Exception.builder().statusCode(HTTP_NOT_FOUND)
                .message("not found").build();
        for (S3Exception failure : new S3Exception[] {missingBucket, bodyless}) {
            S3Client client = mock(S3Client.class);
            when(client.getObject(any(GetObjectRequest.class))).thenThrow(failure);
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
        // The cause is the second route the store's own words could travel, and the easier one to
        // overlook: anything that logs a caught IOException with its stack trace, or reads
        // getCause().getMessage(), republishes whatever the SDK quoted - and an SDK message can quote the
        // request it was building, endpoint and signed headers included. So the SDK failure itself crosses
        // no boundary; a sanitised stand-in does, so that the report is still something a caller can
        // branch on. See assertSanitisedCause.
        assertSanitisedCause(thrown);
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
    public void aKeyReachedThroughALinkedAncestorIsRefused(@TempDir Path home, @TempDir Path elsewhere)
            throws Exception {
        FileSystemContentStore store = filesystemStoreRootedAt(home);
        // A SECOND JUnit temporary directory rather than a sibling of the first. It is genuinely outside the
        // store root, which is what this case needs, and the extension owns it - so it is removed however the
        // test ends. A hand-built sibling of @TempDir is outside what JUnit cleans up, so every run of this
        // suite left a directory and a file behind in the build's temporary area.
        Path outside = Files.createDirectories(elsewhere.resolve("outside-the-root"));
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
    public void aLinkOrDirectoryStandingWhereContentBelongsIsRefused(@TempDir Path home, @TempDir Path elsewhere)
            throws Exception {
        FileSystemContentStore store = filesystemStoreRootedAt(home);
        // Extension owned, for the reason given at the case above: outside the store root but inside what
        // JUnit removes.
        Path outside = Files.createDirectories(elsewhere.resolve("link-target"));
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
        // Identical posture to the object-store provider's refusal of the same thing: an IOException raised
        // while serving content reaches the rendered page, so the reader is given the breach and a reference
        // and the location is left to the log. Which provider backs the content must not change that.
        assertTrue(refused.getMessage().contains("Reference ["), "the refusal must carry a reference: "
                + refused.getMessage());
        assertFalse(refused.getMessage().contains(key), "the refusal must not disclose the content location: "
                + refused.getMessage());
        assertFalse(refused.getMessage().contains("large.bin"), "the refusal must not disclose the file name: "
                + refused.getMessage());
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

        // A key here is the content's ofbiz.home-relative path, the same key the filesystem provider uses, and
        // it is refused where it is named rather than sent to a store that would honour it. The list is the ways
        // a path can fail to be one: absent, empty, absolute, a traversal, a bare relative component, an empty
        // component and a trailing separator. The last five are usable paths in every respect but one character,
        // and are refused for a second reason: a key travels to the store inside the request line and its
        // headers, so one carrying a NUL or a line break could alter what is sent on this deployment's behalf
        // rather than merely name the wrong object.
        String[] unusable = {
            null,
            "",
            "/runtime/uploads/party/logo.png",
            "runtime/../../etc/passwd",
            "runtime/./uploads/logo.png",
            "..",
            "runtime//uploads/logo.png",
            "runtime/uploads/",
            "runtime/uploads/logo\u0000.png",
            "runtime/uploads/lo\tgo.png",
            "runtime/uploads/logo.png\n",
            "runtime/uploads/logo\u007f.png",
            "runtime/uploads/logo\u001b.png",
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
        when(client.getObject(any(GetObjectRequest.class))).thenAnswer(invocation -> storedObject(PAYLOAD));
        ContentStore store = new S3ContentStore(client, BUCKET);

        assertTrue(store.exists(KEY), "the key must be answered through the prefix");

        // Normalised to exactly one separator between the prefix and the key: a prefix an operator
        // wrote with a leading or trailing slash must address the same object as one without.
        verify(client).getObject(eq(GetObjectRequest.builder().bucket(BUCKET)
                .key("tenants/acme/" + KEY).range(EXISTENCE_RANGE).build()));
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
    public void anAncestorExchangedForALinkAfterItWasCheckedCannotRedirectTheOperation(@TempDir Path home)
            throws Exception {
        // The defect this closes is a race, and a race asserted by scheduling luck is a test that passes on
        // a fast machine for the wrong reason. So the provider is asked to run the exchange itself, at the
        // one instant it can do any harm: after the tree has been walked and before the content is touched.
        // A provider that re-resolves the path there reads the substitute; one that operates through the
        // descriptors it already holds does not.
        // The bait, outside the tree the provider is confined to. Every case below aims an operation at a
        // key inside the root whose ancestor is exchanged for a link to this, so if any operation follows
        // the exchange it lands here - and this content is checked afterwards, byte for byte, to prove none
        // of them did.
        Path bait = Files.createDirectories(home.resolve("bait/uploads"));
        Files.write(bait.resolve("secret.txt"), "ATTACKER".getBytes(StandardCharsets.UTF_8));
        FileSystemContentStore store = filesystemStoreRootedAt(home);

        // One subtree per operation, because the exchange is not undone: a single tree would leave the
        // second operation descending through a link that is simply there, which is a different case
        // (asserted at the end) and would say nothing about the window.
        //
        // Each is checked afterwards at its MOVED-ASIDE location, not at the name it was created under. The
        // exchange moves the real directory aside and puts the link at the original name, so after it the
        // original name resolves to the bait and the confined directory is reachable only where it was moved
        // to. That is the whole point: the provider is still working on that directory, through descriptors,
        // while the name it was reached by no longer refers to it.
        confinedContentUnder(home, "whole");
        confinedContentUnder(home, "stream");
        confinedContentUnder(home, "written");
        confinedContentUnder(home, "removed");
        try {
            exchangeDuringNextOperation(home, "whole", bait.getParent());
            assertEquals("CONFINED", new String(store.get("whole/uploads/secret.txt"), StandardCharsets.UTF_8),
                    "a whole read must return the content inside the storage root, not what an ancestor"
                            + " exchanged mid-operation points at");

            exchangeDuringNextOperation(home, "stream", bait.getParent());
            try (InputStream streamed = store.openStream("stream/uploads/secret.txt")) {
                assertEquals("CONFINED", new String(streamed.readAllBytes(), StandardCharsets.UTF_8),
                        "a streamed read must be confined for the same reason a whole read is");
            }

            exchangeDuringNextOperation(home, "written", bait.getParent());
            store.put("written/uploads/secret.txt", "REWRITTEN".getBytes(StandardCharsets.UTF_8));
            assertEquals("REWRITTEN", Files.readString(movedAside(home, "written").resolve("secret.txt")),
                    "a write must land on the file the descent established, inside the storage root");

            exchangeDuringNextOperation(home, "removed", bait.getParent());
            store.delete("removed/uploads/secret.txt");
            assertFalse(Files.exists(movedAside(home, "removed").resolve("secret.txt"),
                    LinkOption.NOFOLLOW_LINKS), "a removal must remove the confined file the descent"
                            + " established, and this one was left in place");
        } finally {
            FileSystemContentStore.installInterleavedActionForTesting(null);
        }

        // Whatever the exchanges left behind, the content outside the root was never touched: not read from,
        // not written to and not removed. A single operation that followed one of the links would have
        // changed this, or been served by it.
        assertEquals("ATTACKER", Files.readString(bait.resolve("secret.txt")), "no operation may read, write"
                + " or remove content outside the storage root");
        assertEquals("CONFINED", Files.readString(movedAside(home, "whole").resolve("secret.txt")),
                "a read must not disturb what it read");
        assertEquals("CONFINED", Files.readString(movedAside(home, "stream").resolve("secret.txt")),
                "a streamed read must not disturb what it read");

        // And the exchange is genuinely effective and genuinely detectable, so none of the above passed
        // because the substitution quietly failed to happen. Asked for a key whose ancestor is a link before
        // the operation begins, the provider refuses it outright.
        Files.createSymbolicLink(home.resolve("planted"), bait.getParent());
        GeneralException refused = assertThrows(GeneralException.class, () -> store.get(
                "planted/uploads/secret.txt"), "a key reached through an ancestor that is a link must be"
                        + " refused");
        assertTrue(refused.getMessage().contains("planted"), "the refusal must name the ancestor it refused: "
                + refused.getMessage());
    }

    @Test
    public void everyConfiguredSettingReachesTheClientBuilderTheDeploymentDescribed() throws Exception {
        // What the public constructor asks the SDK for is not observable from a finished client, so it was
        // not observed at all: the tests handed the provider a client that had already been built. That
        // leaves the settings that only exist at build time - region, addressing style, which principal, the
        // deadlines - asserted nowhere, and those are exactly the settings a deployment gets wrong. Supplying
        // the builder rather than the client is what makes them observable.
        RecordedSdk sdk = new RecordedSdk();
        S3ContentStore.installSdkConstructionForTesting(sdk);
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_BUCKET, "configured-bucket");
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_REGION, "eu-west-2");
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_ENDPOINT, "https://store.example.internal");
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_PATH_STYLE, "true");
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_ACCESS_KEY_ID, "AKIAEXAMPLE");
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_SECRET_ACCESS_KEY, "s3cr3t");

        new S3ContentStore((Delegator) null);

        verify(sdk.builder()).region(Region.of("eu-west-2"));
        verify(sdk.builder()).forcePathStyle(true);
        verify(sdk.builder()).endpointOverride(URI.create("https://store.example.internal"));
        verify(sdk.builder()).build();

        // The deadlines and the retry cap are the settings that decide whether a store that has stopped
        // answering ties up a request thread or releases it, so it is not enough that some override
        // configuration was passed - the configured numbers have to be in it. Nothing but the builder ever
        // sees this object, which is why it was previously asserted nowhere.
        ArgumentCaptor<ClientOverrideConfiguration> overrides =
                ArgumentCaptor.forClass(ClientOverrideConfiguration.class);
        verify(sdk.builder()).overrideConfiguration(overrides.capture());
        ClientOverrideConfiguration deadlines = overrides.getValue();
        assertEquals(Duration.ofMillis(Long.parseLong(committedProperties.get(PROPERTY_S3_API_TIMEOUT))),
                deadlines.apiCallTimeout().orElseThrow(), "the whole-call deadline the component ships must"
                        + " reach the client");
        assertEquals(Duration.ofMillis(Long.parseLong(committedProperties.get(PROPERTY_S3_ATTEMPT_TIMEOUT))),
                deadlines.apiCallAttemptTimeout().orElseThrow(), "the per-attempt deadline the component ships"
                        + " must reach the client");
        assertTrue(deadlines.retryStrategy().isPresent() || deadlines.retryStrategyConfigurator().isPresent(),
                "the configured retry cap must reach the client as a retry strategy, so a failing store is"
                        + " not retried without limit");
        // The principal is named explicitly rather than left to a builder default, and a configured pair
        // means static credentials rather than the default chain - so the default chain must be untouched.
        assertTrue(sdk.credentialsGiven() instanceof StaticCredentialsProvider, "a configured credential pair"
                + " must authenticate as those credentials, and this authenticated as "
                + sdk.credentialsGiven().getClass().getName());
        assertEquals("AKIAEXAMPLE", sdk.credentialsGiven().resolveCredentials().accessKeyId(),
                "the configured access key must be the one the client is given");
        assertEquals(0, sdk.defaultChainsCreated(), "the default credential chain must not be built when a"
                + " credential pair is configured");
    }

    @Test
    public void anEndpointlessConfigurationLeavesTheBuilderToItsOwnEndpointAndUsesTheDefaultChain()
            throws Exception {
        // The other side of the branch above, so neither default is asserted only by implication: no
        // endpoint means no override at all rather than an override of "", and no credential pair means the
        // default chain - which is how a deployment authenticates with an instance role.
        RecordedSdk sdk = new RecordedSdk();
        S3ContentStore.installSdkConstructionForTesting(sdk);
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_BUCKET, "configured-bucket");
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_REGION, "us-east-1");
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_ENDPOINT, "");
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_PATH_STYLE, "");
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_ACCESS_KEY_ID, "");
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_SECRET_ACCESS_KEY, "");

        new S3ContentStore((Delegator) null);

        verify(sdk.builder()).region(Region.of("us-east-1"));
        // Explicitly false, not merely unset: an absent value is a decision to use virtual-host addressing.
        verify(sdk.builder()).forcePathStyle(false);
        verify(sdk.builder(), never()).endpointOverride(any(URI.class));
        assertEquals(1, sdk.defaultChainsCreated(), "no configured credential pair must authenticate through"
                + " the default credential chain");
        assertSame(sdk.lastDefaultChain(), sdk.credentialsGiven(), "the client must be given the default chain"
                + " instance this provider created, so it is one the provider may also release");
    }

    @Test
    public void aSettingThisProviderCanJudgeItselfIsRefusedBeforeAnythingIsBuiltToLeak() throws Exception {
        // A constructor that creates a client and a credential provider and then throws leaves both
        // unreachable and unclosed: there is no `this` to close and no caller holding a reference, so the
        // connection pool and its threads are held for the life of the JVM. It cannot be repaired
        // afterwards, only avoided - so nothing is created until nothing local can still refuse the
        // configuration. Each value below is one this provider judges on its own, without the SDK.
        Map<String, String> refusable = new LinkedHashMap<>();
        refusable.put(PROPERTY_S3_BUCKET, "");
        refusable.put(PROPERTY_S3_REGION, "");
        refusable.put(PROPERTY_S3_KEY_PREFIX, "../escape");
        refusable.put(PROPERTY_S3_ENDPOINT, "http://169.254.169.254/");
        for (Map.Entry<String, String> refused : refusable.entrySet()) {
            RecordedSdk sdk = new RecordedSdk();
            S3ContentStore.installSdkConstructionForTesting(sdk);
            UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_BUCKET, "configured-bucket");
            UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_REGION, "us-east-1");
            UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_ENDPOINT, "");
            UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_KEY_PREFIX, "");
            UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_ACCESS_KEY_ID, "");
            UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_SECRET_ACCESS_KEY, "");
            UtilProperties.setPropertyValueInMemory(RESOURCE, refused.getKey(), refused.getValue());

            assertThrows(GeneralException.class, () -> new S3ContentStore((Delegator) null),
                    "[" + refused.getKey() + "=" + refused.getValue() + "] must be refused");

            assertEquals(0, sdk.buildersCreated(), "[" + refused.getKey() + "] is judged without the SDK, so no"
                    + " client builder may have been asked for before it was refused");
            assertEquals(0, sdk.defaultChainsCreated(), "[" + refused.getKey() + "] is judged without the SDK,"
                    + " so no credential provider may have been created before it was refused");
        }
    }

    @Test
    public void aClientThatCannotBeBuiltReleasesTheCredentialProviderItWasAlreadyGiven() throws Exception {
        // Past the local checks the SDK can still refuse, and then the credential provider already exists.
        // It is the constructor's to release, because nothing else has a reference to it.
        RecordedSdk sdk = new RecordedSdk();
        sdk.failTheBuildWith(new IllegalArgumentException("the SDK refuses this configuration"));
        S3ContentStore.installSdkConstructionForTesting(sdk);
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_BUCKET, "configured-bucket");
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_REGION, "us-east-1");
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_ENDPOINT, "");
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_KEY_PREFIX, "");
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_ACCESS_KEY_ID, "");
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_SECRET_ACCESS_KEY, "");

        GeneralException refused = assertThrows(GeneralException.class, () -> new S3ContentStore((Delegator) null),
                "a client the SDK will not build must be reported rather than half-constructed");
        assertFalse(refused.getMessage().contains("s3cr3t"), "a refusal must not quote the credential");
        assertTrue(sdk.lastDefaultChain().isClosed(), "the credential provider created before the failing"
                + " build must be closed, because nothing else can ever reach it");
    }

    @Test
    public void everyKeyTheProvidersRefuseIsAlsoRefusedWhenItIsMinted() throws Exception {
        // The factory mints keys and the providers consume them, so a key the factory hands out that a
        // provider then refuses is the worst shape this can take: the caller has already been given content
        // to store and only finds out at the point of use. That happens whenever the two apply different
        // grammars, which is why minting delegates to the single implementation of the contract rather than
        // restating part of it - and why this drives the provider matrix through the minting path, which is
        // the direction the earlier duplicated check was wrong in.
        //
        // Path-keyed, because that is the branch where the key is a value a caller supplies rather than one
        // the factory composes from an identifier it has already validated.
        ContentStore filesystem = new FileSystemContentStore(null);
        String overlongComponent = "a".repeat(ContentStore.MAX_KEY_COMPONENT_LENGTH_BYTES + 1);
        String overlongKey = ("b".repeat(ContentStore.MAX_KEY_COMPONENT_LENGTH_BYTES) + "/")
                .repeat(ContentStore.MAX_KEY_LENGTH_BYTES / ContentStore.MAX_KEY_COMPONENT_LENGTH_BYTES + 1)
                + "tail";
        String[] unusable = {
            null,
            "",
            "   ",
            "\t",
            "/absolute/path",
            "\\windows\\absolute",
            "C:/windows/drive",
            "c:\\windows\\drive",
            "has\u0000a/null",
            "has\na/newline",
            "has\ra/carriage/return",
            "trailing/separator/",
            "doubled//separator",
            "./relative",
            "relative/./inside",
            "relative/../escape",
            "..",
            "runtime/uploads/" + overlongComponent,
            overlongKey,
        };
        for (String key : unusable) {
            Executable minting = () -> ContentStoreFactory.storeKey(filesystem, key);
            GeneralException refused = assertThrows(GeneralException.class, minting, "minting must refuse ["
                    + key + "], because every provider refuses it");
            assertNotNull(refused.getMessage(), "a refusal must say which rule was broken");
            // The same value, offered straight to the contract every provider validates against. Both have
            // to refuse it; a value only one of them refuses is exactly the divergence this guards against.
            assertThrows(GeneralException.class, () -> ContentStore.requireUsableKey(key),
                    "the shared grammar must refuse [" + key + "] too, or minting and using disagree");
        }

        // And the boundary just inside every limit is minted, so the delegation did not simply refuse
        // everything: a check that says no to all keys would pass the loop above and be useless.
        String atComponentLimit = "c".repeat(ContentStore.MAX_KEY_COMPONENT_LENGTH_BYTES);
        for (String key : new String[] {"a", "runtime/uploads/party/logo.png", atComponentLimit,
                "runtime/uploads/" + atComponentLimit, "name with spaces.png", "colon:in:a:posix:name",
                "unicode/ünïcödé.png"}) {
            assertEquals(key, ContentStoreFactory.storeKey(filesystem, key),
                    "[" + key + "] is inside every limit and must be minted unchanged");
            assertDoesNotThrow(() -> ContentStore.requireUsableKey(key),
                    "the shared grammar must accept [" + key + "] too");
        }
    }

    /**
     * The two key limits are asserted as PAIRS: the longest value that must be accepted, and the same value one
     * byte longer, which must be refused.
     *
     * <p>A limit tested only from the outside is not tested at all. "256 bytes is refused" is satisfied by an
     * implementation that refuses 200, or 2, and the failure that would cause - a deployment whose existing keys
     * suddenly become unusable - is far more damaging than the over-limit key the check was written for. Both
     * limits therefore get an exactly-at and a one-over case, and both are driven through the minting path and
     * the shared grammar so the pair cannot hold in one and not the other.
     *
     * <p>The multibyte cases are the ones a character-counting implementation gets wrong. The limits are in
     * BYTES, because that is what a filesystem and an object store impose, and a two-byte or four-byte character
     * makes byte length and character length diverge - so a key of 128 two-byte characters is exactly at a
     * 256-byte limit while being half its length in characters, and one character more crosses it by two.
     *
     * @throws Exception if the grammar cannot be exercised
     */
    @Test
    public void eachKeyLimitIsAssertedAsAnExactlyAtAndAOneByteOverPairIncludingMultibyte() throws Exception {
        ContentStore filesystem = new FileSystemContentStore(null);

        // COMPONENT LIMIT, single byte characters: exactly at, then one byte over.
        String componentAt = "c".repeat(ContentStore.MAX_KEY_COMPONENT_LENGTH_BYTES);
        String componentOver = "c".repeat(ContentStore.MAX_KEY_COMPONENT_LENGTH_BYTES + 1);
        assertBoundaryPair(filesystem, componentAt, componentOver, "a single component");

        // COMPONENT LIMIT, two byte characters. 'ü' is two bytes in UTF-8, so the accepted value is half the
        // limit in characters; a character-counting check would accept both of these and a byte-counting one
        // accepts exactly the first.
        int twoByteAt = ContentStore.MAX_KEY_COMPONENT_LENGTH_BYTES / 2;
        assertBoundaryPair(filesystem, "\u00fc".repeat(twoByteAt) + "c", "\u00fc".repeat(twoByteAt) + "cc",
                "a component of two byte characters");

        // COMPONENT LIMIT, four byte characters. A supplementary code point is one Java char PAIR and four
        // UTF-8 bytes, so this is where a check that counted String.length() diverges furthest from the limit.
        int fourByteAt = ContentStore.MAX_KEY_COMPONENT_LENGTH_BYTES / 4;
        String fourBytePadding = "c".repeat(ContentStore.MAX_KEY_COMPONENT_LENGTH_BYTES - fourByteAt * 4);
        assertBoundaryPair(filesystem, "\uD83D\uDE00".repeat(fourByteAt) + fourBytePadding,
                "\uD83D\uDE00".repeat(fourByteAt) + fourBytePadding + "c",
                "a component of four byte characters");

        // TOTAL KEY LIMIT: exactly at, then one byte over. Built from components each inside the component
        // limit, so the only rule the over case can break is the total - which is what makes the pair sharp.
        String keyAt = keyOfExactByteLength(ContentStore.MAX_KEY_LENGTH_BYTES);
        String keyOver = keyOfExactByteLength(ContentStore.MAX_KEY_LENGTH_BYTES + 1);
        assertEquals(ContentStore.MAX_KEY_LENGTH_BYTES, keyAt.getBytes(StandardCharsets.UTF_8).length,
                "the fixture must be exactly at the limit, or this pair asserts nothing");
        assertEquals(ContentStore.MAX_KEY_LENGTH_BYTES + 1, keyOver.getBytes(StandardCharsets.UTF_8).length,
                "the fixture must be exactly one byte over the limit");
        assertEquals(keyAt, ContentStoreFactory.storeKey(filesystem, keyAt),
                "a key of exactly " + ContentStore.MAX_KEY_LENGTH_BYTES + " bytes must be minted unchanged:"
                        + " refusing it would make an existing deployment's longest keys unusable");
        assertDoesNotThrow(() -> ContentStore.requireUsableKey(keyAt),
                "the shared grammar must accept a key of exactly " + ContentStore.MAX_KEY_LENGTH_BYTES + " bytes");
        assertThrows(GeneralException.class, () -> ContentStoreFactory.storeKey(filesystem, keyOver),
                "a key one byte over the total limit must be refused when it is minted");
        assertThrows(GeneralException.class, () -> ContentStore.requireUsableKey(keyOver),
                "the shared grammar must refuse a key one byte over the total limit");

        // TOTAL KEY LIMIT with multibyte components, for the same reason as the component pair above.
        String multibyteAt = multibyteKeyOfExactByteLength(ContentStore.MAX_KEY_LENGTH_BYTES);
        String multibyteOver = multibyteKeyOfExactByteLength(ContentStore.MAX_KEY_LENGTH_BYTES + 2);
        assertTrue(multibyteAt.length() < ContentStore.MAX_KEY_LENGTH_BYTES, "the multibyte fixture must be"
                + " shorter in characters than in bytes, or it does not distinguish the two counts");
        assertDoesNotThrow(() -> ContentStore.requireUsableKey(multibyteAt),
                "a multibyte key of exactly " + ContentStore.MAX_KEY_LENGTH_BYTES + " bytes must be accepted");
        assertThrows(GeneralException.class, () -> ContentStore.requireUsableKey(multibyteOver),
                "a multibyte key over the total byte limit must be refused, however few characters it has");
    }

    /**
     * Asserts one component-limit pair through both the minting path and the shared grammar.
     *
     * @param store the provider the key is minted for
     * @param accepted a component exactly at the limit
     * @param refused the same component one byte longer
     * @param what the kind of component, named in a failure
     * @throws Exception if the grammar cannot be exercised
     */
    private static void assertBoundaryPair(ContentStore store, String accepted, String refused, String what)
            throws Exception {
        assertEquals(ContentStore.MAX_KEY_COMPONENT_LENGTH_BYTES,
                accepted.getBytes(StandardCharsets.UTF_8).length, what + ": the accepted fixture must be exactly"
                        + " at the limit, or this pair asserts nothing");
        assertTrue(refused.getBytes(StandardCharsets.UTF_8).length > ContentStore.MAX_KEY_COMPONENT_LENGTH_BYTES,
                what + ": the refused fixture must be over the limit");
        String acceptedKey = "runtime/uploads/" + accepted;
        String refusedKey = "runtime/uploads/" + refused;
        assertEquals(acceptedKey, ContentStoreFactory.storeKey(store, acceptedKey),
                what + " of exactly " + ContentStore.MAX_KEY_COMPONENT_LENGTH_BYTES + " bytes must be minted"
                        + " unchanged: refusing it would make an existing deployment's keys unusable");
        assertDoesNotThrow(() -> ContentStore.requireUsableKey(acceptedKey),
                what + " at the limit must be accepted by the shared grammar too");
        assertThrows(GeneralException.class, () -> ContentStoreFactory.storeKey(store, refusedKey),
                what + " over the limit must be refused when it is minted");
        assertThrows(GeneralException.class, () -> ContentStore.requireUsableKey(refusedKey),
                what + " over the limit must be refused by the shared grammar too");
    }

    /**
     * Builds a key of an exact UTF-8 byte length from single-byte components, each inside the component limit.
     *
     * @param bytes the length to produce
     * @return a key of exactly that many bytes
     */
    private static String keyOfExactByteLength(int bytes) {
        // 200 byte components plus one separator each: comfortably inside the component limit, so the only
        // limit a long key can break is the total one.
        int componentLength = 200;
        StringBuilder key = new StringBuilder();
        while (key.length() + componentLength + 1 <= bytes) {
            key.append("d".repeat(componentLength)).append('/');
        }
        key.append("e".repeat(bytes - key.length()));
        return key.toString();
    }

    /**
     * Builds a key of an exact UTF-8 byte length whose characters are two bytes each, so that byte length and
     * character length differ.
     *
     * @param bytes the length to produce; must be even
     * @return a key of exactly that many bytes and half as many characters
     */
    private static String multibyteKeyOfExactByteLength(int bytes) {
        int componentCharacters = 100;
        StringBuilder key = new StringBuilder();
        int used = 0;
        while (used + componentCharacters * 2 + 1 <= bytes) {
            key.append("\u00fc".repeat(componentCharacters)).append('/');
            used += componentCharacters * 2 + 1;
        }
        int remaining = bytes - used;
        key.append("\u00fc".repeat(remaining / 2));
        if (remaining % 2 == 1) {
            key.append('f');
        }
        return key.toString();
    }

    /**
     * The filesystem provider's payload and absence contracts, asserted exactly rather than by implication.
     *
     * <p>Four of these look like tidiness and are not. A null payload accepted as an empty write would replace
     * stored content with nothing on a caller's programming error. A zero-length payload REFUSED would make an
     * empty upload - which a browser will happily produce - fail at the storage layer rather than at validation.
     * And an absent key has to be reported as {@link FileNotFoundException} by both reads, because that is the
     * single exception the local-fallback decision acts on: reported as anything else, an absent object during a
     * migration would become an outage, and reported as null it would be indistinguishable from empty content.
     *
     * @param home a per-test temporary directory standing in for the storage root
     * @throws Exception if the provider cannot be exercised
     */
    @Test
    public void theFilesystemProvidersPayloadAndAbsenceContractsAreExact(@TempDir Path home) throws Exception {
        FileSystemContentStore store = filesystemStoreRootedAt(home);
        String key = "runtime/uploads/party/contract.bin";

        // ABSENCE, before anything is written. exists() answers without raising; both reads raise the one
        // exception the fallback decision recognises, and neither returns null.
        assertFalse(store.exists(key), "nothing is stored under a key that was never written");
        assertThrows(FileNotFoundException.class, () -> store.get(key), "a whole read of an absent key must be"
                + " reported as FileNotFoundException, which is what the local-fallback decision acts on");
        assertThrows(FileNotFoundException.class, () -> store.openStream(key), "a streamed read of an absent key"
                + " must be reported the same way, so the two reads cannot disagree about absence");
        assertDoesNotThrow(() -> store.delete(key), "removing what is not there is a no-op by contract, because"
                + " a rollback may run after a delete has already happened");

        // A NULL PAYLOAD is a caller's error and is refused. Accepting it as an empty write would replace
        // stored content with nothing.
        assertThrows(GeneralException.class, () -> store.put(key, (byte[]) null),
                "a null payload must be refused rather than stored as empty content");
        assertFalse(store.exists(key), "a refused write must store nothing at all");
        assertThrows(GeneralException.class, () -> store.put(key, null, 0L),
                "a null stream must be refused by the streaming form too");
        assertFalse(store.exists(key), "a refused streamed write must store nothing at all");

        // A ZERO-LENGTH PAYLOAD is legal, and reads back as a zero-length array rather than as absence.
        store.put(key, new byte[0]);
        assertTrue(store.exists(key), "empty content is content: it must be reported as present");
        assertArrayEquals(new byte[0], store.get(key), "a whole read of empty content must yield a zero-length"
                + " array, never null and never an exception");
        try (ContentStore.ContentStream opened = store.openStream(key)) {
            assertEquals(0L, opened.length(), "the stream over empty content must declare a length of zero");
            assertArrayEquals(new byte[0], opened.readAllBytes(), "and must yield no bytes");
        }

        // ...and an empty write REPLACES content, which is the other half of the same contract.
        store.put(key, PAYLOAD);
        assertArrayEquals(PAYLOAD, store.get(key), "the non-empty rewrite must be readable");
        store.put(key, new byte[0]);
        assertArrayEquals(new byte[0], store.get(key), "an empty write must truncate what was there, not leave"
                + " a tail of the longer value behind");

        store.delete(key);
        assertFalse(store.exists(key), "content that was removed must be reported as absent");
        assertThrows(FileNotFoundException.class, () -> store.get(key), "and must be absent to a whole read");
        assertThrows(FileNotFoundException.class, () -> store.openStream(key),
                "and must be absent to a streamed read");
    }

    /**
     * The committed default is {@code database}, asserted from the shipped resource itself and from a factory
     * that has had nothing installed into it.
     *
     * <p>Every other case in this class writes the provider property in memory before resolving, which is what
     * makes them cases about the resolution. None of them establishes the thing this refactor actually promised:
     * that a checkout with no configuration at all keeps the existing database storage. An in-memory override of
     * {@code database} would pass such a test even if the committed file said {@code s3}, so the value is read
     * here from the resource on the class path with nothing set, and separately from the committed file on disk -
     * two independent readings of the same promise.
     *
     * @throws Exception if the resource cannot be read
     */
    @Test
    public void theCommittedDefaultIsDatabaseStorageInBothTheResourceAndAnUntouchedFactory() throws Exception {
        // FROM THE RAW COMMITTED FILE, byte for byte, at column one: this is the declaration an operator reads
        // and the anchor the container's renderer substitutes into.
        List<String> committed = Files.readAllLines(committedContentProperties(), StandardCharsets.UTF_8);
        List<String> declarations = committed.stream()
                .filter(line -> line.startsWith(PROPERTY_PROVIDER + "="))
                .toList();
        assertEquals(1, declarations.size(), "the committed resource must declare " + PROPERTY_PROVIDER
                + " exactly once at column one");
        assertEquals(PROPERTY_PROVIDER + "=" + PROVIDER_DATABASE, declarations.get(0),
                "the committed default must be database storage: an unconfigured checkout has to keep the"
                        + " existing DataResource database storage, which is what makes every object-store"
                        + " capability opt-in");
        for (String blank : List.of("content.store.s3.bucket", "content.store.s3.region",
                "content.store.s3.endpoint", "content.store.s3.access.key.id",
                "content.store.s3.secret.access.key")) {
            assertTrue(committed.contains(blank + "="), "the committed resource must declare [" + blank + "]"
                    + " blank: a committed value would be a deployment setting - or a credential - in the"
                    + " repository, and would make the object store reachable without anyone configuring it");
        }

        // FROM AN UNTOUCHED FACTORY. The offline object-store configuration this class installs for every test
        // is put back to the committed values first, so what is resolved below is whatever the resource on the
        // class path says - the state a bare checkout starts in - rather than anything a test wrote.
        for (Map.Entry<String, String> held : committedProperties.entrySet()) {
            UtilProperties.setPropertyValueInMemory(RESOURCE, held.getKey(), held.getValue());
        }
        ContentStoreFactory.clearCache();
        assertNull(ContentStoreFactory.getContentStore(), "with nothing configured the factory must select no"
                + " provider at all, which is how the existing database storage stays in force");
        assertNull(ContentStoreFactory.getContentStore(null), "the delegator form must agree, because a"
                + " deployment resolves through it");
    }

    /**
     * Locates the committed {@code content.properties} in the source tree rather than on the class path.
     *
     * <p>The class path copy is the build's, and a build could in principle filter it. What has to be asserted
     * is the file a reviewer reads and the container renders from.
     *
     * @return the committed resource
     */
    private static Path committedContentProperties() {
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("dependencies.gradle"))) {
            candidate = candidate.getParent();
        }
        assertNotNull(candidate, "could not locate the repository root from " + Paths.get("").toAbsolutePath());
        Path resource = candidate.resolve("applications/content/config/content.properties");
        assertTrue(Files.isRegularFile(resource), "the committed resource must exist at " + resource);
        return resource;
    }

    /**
     * Content removed between the descent to a key and the operation performed through it is reported as
     * absence by both reads, deterministically.
     *
     * <p>The window is real and shared: the storage tree is a volume other instances and other processes write
     * to, so the file that was there when the key was resolved can be gone by the time it is opened. The
     * filesystem reports that as {@link java.nio.file.NoSuchFileException}, which is an {@link IOException} but
     * NOT a {@link FileNotFoundException} - and the integration seam catches {@code FileNotFoundException}
     * alone. Left untranslated it would escape every caller that handles absence, so a removed object during a
     * migration would surface as an outage instead of falling back as documented.
     *
     * <p>Driven through the provider's own interleaving seam rather than by racing threads, so the window is
     * entered exactly once, on purpose, every run.
     *
     * @param home a per-test temporary directory standing in for the storage root
     * @throws Exception if the provider cannot be exercised
     */
    @Test
    public void contentRemovedBetweenTheDescentAndTheOpenIsReportedAsAbsenceByBothReads(@TempDir Path home)
            throws Exception {
        for (String operation : List.of("get", "openStream")) {
            Path root = Files.createDirectories(home.resolve(operation));
            FileSystemContentStore store = filesystemStoreRootedAt(root);
            String key = "runtime/uploads/party/vanishes.bin";
            store.put(key, PAYLOAD);
            assertTrue(store.exists(key), "the fixture must be present before the window is entered");

            // Fires once, at the exact instant the provider has resolved the key and has not yet operated on it.
            java.util.concurrent.atomic.AtomicBoolean fired = new java.util.concurrent.atomic.AtomicBoolean();
            FileSystemContentStore.installInterleavedActionForTesting(() -> {
                if (fired.compareAndSet(false, true)) {
                    try {
                        Files.delete(root.resolve(key));
                    } catch (IOException removalFailed) {
                        throw new IllegalStateException("the interleaved removal failed", removalFailed);
                    }
                }
            });
            Executable read = () -> {
                if ("get".equals(operation)) {
                    store.get(key);
                } else {
                    store.openStream(key);
                }
            };
            try {
                IOException reported = assertThrows(FileNotFoundException.class, read,
                        operation + " must report content removed inside the window as FileNotFoundException,"
                                + " because that is the single exception the fallback decision recognises");
                assertTrue(fired.get(), "the window must actually have been entered, or this case asserts"
                        + " nothing about it");
                assertNotNull(reported.getMessage(), "the report must say which key held nothing");
            } finally {
                FileSystemContentStore.installInterleavedActionForTesting(null);
            }
        }
    }

    /**
     * A write whose stream does not hold what it declared stores nothing, in both directions, and leaves no
     * staging entry behind.
     *
     * <p>Both directions matter and neither is symmetric with the other. A stream that ends early would store
     * truncated content under a key that reads back as complete - silent corruption. A stream that holds more
     * than it declared would silently drop the rest. And because the write is staged beside its destination, a
     * refusal that left the staging entry would leave content in the tree under no key at all, which nothing
     * would ever read, delete or account for.
     *
     * <p>The TYPE is asserted, not just the refusal: a length that does not match its stream is the caller's
     * own mistake, so {@link ContentStore#put(String, java.io.InputStream, long)} reports it as
     * {@link GeneralException} - the exception every operation of the contract uses for a request it will not
     * carry out - rather than as an {@link IOException}, which the contract reserves for the storage failing.
     * A caller that retries on {@code IOException} and reports {@code GeneralException} would otherwise retry
     * a request that cannot ever succeed.
     *
     * @param home a per-test temporary directory standing in for the storage root
     * @throws Exception if the provider cannot be exercised
     */
    @Test
    public void aStreamedWriteThatDoesNotHoldWhatItDeclaredStoresNothingAndStagesNothing(@TempDir Path home)
            throws Exception {
        FileSystemContentStore store = filesystemStoreRootedAt(home);
        String key = "runtime/uploads/party/mismatch.bin";
        Path directory = Files.createDirectories(home.resolve("runtime/uploads/party"));

        Executable declaresMoreThanItHolds = () -> store.put(key, new ByteArrayInputStream(PAYLOAD), PAYLOAD.length + 8L);
        GeneralException short0 = assertThrows(GeneralException.class, declaresMoreThanItHolds,
                "a stream that ends before the length it declared must be refused as the caller error it is,"
                        + " not stored truncated and not reported as the storage failing");
        assertTrue(short0.getMessage().contains("bytes before"), "the refusal must say the stream ended early: ["
                + short0.getMessage() + "]");
        assertFalse(store.exists(key), "a refused write must store nothing at all");
        assertEquals(List.of(), namesIn(directory), "a refused write must leave no staging entry behind: content"
                + " in the tree under no key would never be read, deleted or accounted for");

        Executable declaresLessThanItHolds = () -> store.put(key, new ByteArrayInputStream(PAYLOAD), PAYLOAD.length - 1L);
        GeneralException long0 = assertThrows(GeneralException.class, declaresLessThanItHolds,
                "a stream that holds more than the length it declared must be refused as the caller error it is,"
                        + " not stored short and not reported as the storage failing");
        assertTrue(long0.getMessage().contains("holds more than"), "the refusal must say the stream was longer: ["
                + long0.getMessage() + "]");
        assertFalse(store.exists(key), "a refused write must store nothing at all");
        assertEquals(List.of(), namesIn(directory), "and must leave no staging entry behind");

        // ...and the same key is still writable afterwards, so a refusal leaves no state that poisons a retry.
        store.put(key, new ByteArrayInputStream(PAYLOAD), PAYLOAD.length);
        assertArrayEquals(PAYLOAD, store.get(key), "a correct write after a refused one must succeed");
        assertEquals(List.of("mismatch.bin"), namesIn(directory), "and must leave exactly the content, with no"
                + " staging entry beside it");
    }

    /**
     * A rewrite whose stream does not hold what it declared leaves the content the deployment already had.
     *
     * <p>This is the exact defect the staging rewrite exists to remove, and the case a mismatch test written
     * against a NEW key cannot reach. The provider used to open the existing file for rewriting - truncating it -
     * and only then discover that the stream was short or long, so a failed write destroyed the previous version
     * and left either a partial file or one padded to a length nothing had sent. The content was gone, the
     * refusal named a caller error, and there was nothing left to fall back to.
     *
     * <p>Both directions are driven against an EXISTING key, and three things are asserted after each: the
     * previous content is byte-for-byte intact, no staging entry is left beside it, and the file is the SAME file
     * - its identity is unchanged, so the deployment's own inode, links and permissions were never replaced.
     *
     * @param home a per-test temporary directory standing in for the storage root
     * @throws Exception if the provider cannot be exercised
     */
    @Test
    public void aMismatchedRewriteLeavesTheContentTheDeploymentAlreadyHad(@TempDir Path home) throws Exception {
        FileSystemContentStore store = filesystemStoreRootedAt(home);
        String key = "runtime/uploads/party/existing.bin";
        Path directory = Files.createDirectories(home.resolve("runtime/uploads/party"));
        byte[] established = "the version this deployment already had".getBytes(StandardCharsets.UTF_8);
        store.put(key, new ByteArrayInputStream(established), established.length);
        Path content = directory.resolve("existing.bin");
        Object identity = Files.readAttributes(content, BasicFileAttributes.class).fileKey();

        Executable declaresMoreThanItHolds = () ->
                store.put(key, new ByteArrayInputStream(PAYLOAD), PAYLOAD.length + 8L);
        assertThrows(GeneralException.class, declaresMoreThanItHolds, "a stream ending before the length it"
                + " declared must be refused");
        assertArrayEquals(established, store.get(key), "the content the deployment already had must survive a"
                + " refused rewrite: validating after truncating is what destroyed it");
        assertEquals(List.of("existing.bin"), namesIn(directory), "and no staging entry may be left beside it");
        assertEquals(identity, Files.readAttributes(content, BasicFileAttributes.class).fileKey(), "and it must"
                + " still be the same file, so nothing about it - inode, links, permissions - was replaced");

        Executable declaresLessThanItHolds = () ->
                store.put(key, new ByteArrayInputStream(PAYLOAD), PAYLOAD.length - 1L);
        assertThrows(GeneralException.class, declaresLessThanItHolds, "a stream holding more than the length it"
                + " declared must be refused");
        assertArrayEquals(established, store.get(key), "a longer-than-declared stream must not leave the file"
                + " holding the declared prefix of it either");
        assertEquals(List.of("existing.bin"), namesIn(directory), "and still no staging entry");
        assertEquals(identity, Files.readAttributes(content, BasicFileAttributes.class).fileKey(), "and still"
                + " the same file");

        // A correct rewrite still replaces the content in place, so the refusals above left nothing that
        // poisons the next write.
        store.put(key, new ByteArrayInputStream(PAYLOAD), PAYLOAD.length);
        assertArrayEquals(PAYLOAD, store.get(key), "a correct rewrite after a refused one must take effect");
        assertEquals(identity, Files.readAttributes(content, BasicFileAttributes.class).fileKey(), "and must"
                + " still rewrite the deployment's own file rather than replace it");
    }

    /**
     * A staged write is invisible at its key until it is complete.
     *
     * <p>This is the guarantee the staging entry exists for, and it is asserted at the one instant that can
     * disprove it: with half the payload written. Content for a key that holds nothing yet is written to a
     * private entry beside its destination and moved on, so a reader either finds nothing or finds the whole
     * value - never a file being written through. A provider that created the destination and wrote into it
     * would fail here, because half the content would already be readable under the key.
     *
     * <p>Deterministic rather than raced: the payload blocks in the middle of the copy until this thread has
     * looked, so there is no scheduling luck involved in observing the window.
     *
     * @param home a per-test temporary directory standing in for the storage root
     * @throws Exception if the provider cannot be exercised
     */
    @Test
    public void aStagedWriteIsInvisibleAtItsKeyUntilItIsComplete(@TempDir Path home) throws Exception {
        FileSystemContentStore store = filesystemStoreRootedAt(home);
        String key = "runtime/uploads/party/staged.bin";
        Path directory = home.resolve("runtime/uploads/party");
        byte[] payload = new byte[48 * 1024];
        java.util.Arrays.fill(payload, (byte) 'S');
        HalfwayStream source = new HalfwayStream(payload);

        ExecutorService writer = Executors.newSingleThreadExecutor();
        try {
            Future<?> written = writer.submit(() -> {
                store.put(key, source, payload.length);
                return null;
            });
            assertTrue(source.reachedHalfway(CONCURRENT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "the write must reach the middle of its payload, or the window under test never opened");

            assertFalse(store.exists(key), "a write in progress must not be visible at its key");
            assertThrows(FileNotFoundException.class, () -> store.get(key), "a reader must find nothing at a key"
                    + " whose first write has not finished, rather than a prefix of the content");
            List<String> midWrite = namesIn(directory);
            assertEquals(1, midWrite.size(), "the content in progress must exist somewhere, and exactly once:"
                    + " " + midWrite);
            assertTrue(midWrite.get(0).startsWith(".ofbiz-content-store-"), "content in progress must be staged"
                    + " beside its destination under a private name, and is at: " + midWrite);

            source.release();
            written.get(CONCURRENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            source.release();
            writer.shutdownNow();
        }

        assertArrayEquals(payload, store.get(key), "the completed write must be readable whole");
        assertEquals(List.of("staged.bin"), namesIn(directory), "the staging entry must be gone once the write"
                + " has been published, leaving only the content");
    }

    /**
     * An in-place rewrite keeps the file the deployment already had, which is what filesystem mode promises.
     *
     * <p>Filesystem mode's storage tree <em>is</em> the deployment's own content tree (plan section 0.6.3), so
     * an existing file is truncated and rewritten rather than replaced: replacing it would hand the
     * deployment's own content a new inode, a new modification time and this provider's permissions instead of
     * the ones its upload path produced. That decision is stated in the class documentation and is asserted
     * here, because a later change to publish every write through the staging entry would look like a
     * strengthening and would quietly break the promise.
     *
     * <p>What in-place does NOT mean is asserted in the same place, because that is the half a later change
     * could quietly get wrong in either direction. The source is consumed into a private staging entry first,
     * and the file the deployment had is opened only once that staged copy is complete, so a reader arriving
     * while the write is still reading its source sees the WHOLE OLD value - not a prefix of the new one, and
     * never a mixture. Transferring the caller's stream straight into the live file, which is what this did,
     * meant a source that turned out to be unusable had already destroyed the stored content; the staging
     * entry present beside the content during the window is the visible evidence of the fix, and it is
     * asserted here so that removing it would fail rather than pass quietly.
     *
     * @param home a per-test temporary directory standing in for the storage root
     * @throws Exception if the provider cannot be exercised
     */
    @Test
    public void anInPlaceRewriteKeepsTheFileTheDeploymentAlreadyHad(@TempDir Path home) throws Exception {
        FileSystemContentStore store = filesystemStoreRootedAt(home);
        String key = "runtime/uploads/party/inplace.bin";
        Path target = home.resolve("runtime/uploads/party/inplace.bin");
        byte[] first = new byte[8 * 1024];
        byte[] second = new byte[48 * 1024];
        java.util.Arrays.fill(first, (byte) 'A');
        java.util.Arrays.fill(second, (byte) 'B');
        store.put(key, first);
        Object identityBefore = Files.readAttributes(target, BasicFileAttributes.class).fileKey();
        Set<PosixFilePermission> permissionsBefore = Files.getPosixFilePermissions(target);
        assertNotNull(identityBefore, "the platform must expose a file identity, or this assertion proves nothing");

        HalfwayStream source = new HalfwayStream(second);
        ExecutorService writer = Executors.newSingleThreadExecutor();
        try {
            Future<?> written = writer.submit(() -> {
                store.put(key, source, second.length);
                return null;
            });
            assertTrue(source.reachedHalfway(CONCURRENT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "the rewrite must reach the middle of its payload, or the window under test never opened");

            // The key stays visible throughout, and what it holds while the source is still being read is
            // the whole OLD value: the live file is not opened until the staged copy is complete.
            assertTrue(store.exists(key), "an in-place rewrite must leave the key visible throughout");
            assertArrayEquals(first, store.get(key), "a reader arriving while the write is still consuming its"
                    + " source must see the whole value that was stored, because a source that turns out to be"
                    + " unusable must not have destroyed it");
            List<String> during = namesIn(target.getParent());
            assertTrue(during.contains("inplace.bin"), "the content must stay in place while it is being"
                    + " replaced: " + during);
            assertEquals(2, during.size(), "the replacement must be staged beside the content rather than"
                    + " transferred into it, so exactly one staging entry must be present during the window: "
                    + during);

            source.release();
            written.get(CONCURRENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            source.release();
            writer.shutdownNow();
        }

        assertArrayEquals(second, store.get(key), "the completed rewrite must be readable whole");
        assertEquals(identityBefore, Files.readAttributes(target, BasicFileAttributes.class).fileKey(),
                "an in-place rewrite must keep the file the deployment already had: a new inode would give the"
                        + " deployment's own content a new identity, which plan section 0.6.3 rules out");
        assertEquals(permissionsBefore, Files.getPosixFilePermissions(target),
                "and must keep the permissions that file already carried");
    }

    /**
     * A filesystem that cannot move atomically still publishes the content, and drops only the atomicity.
     *
     * <p>The atomic move is what makes a published file appear whole or not at all, and it is a documented
     * optional capability - a network filesystem may not offer it. The fallback is therefore code that runs on
     * a real deployment and never on the platforms this is built on, which is exactly the code most likely to
     * be wrong. Both attempts are inspected here: the atomic form has to be tried first, the retry has to drop
     * only that option and keep replacing what is there, and the content has to land either way.
     *
     * @param home a per-test temporary directory standing in for the storage root
     * @throws Exception if the move cannot be exercised
     */
    @Test
    public void aFilesystemThatCannotMoveAtomicallyStillPublishesTheContent(@TempDir Path home) throws Exception {
        FileSystemContentStore store = filesystemStoreRootedAt(home);
        List<List<CopyOption>> refusingAttempts = new ArrayList<>();
        FileSystemContentStore.Mover refusesAtomic = (from, to, options) -> {
            refusingAttempts.add(List.of(options));
            for (CopyOption option : options) {
                if (option == StandardCopyOption.ATOMIC_MOVE) {
                    throw new AtomicMoveNotSupportedException(from.toString(), to.toString(),
                            "this filesystem has no atomic rename");
                }
            }
            Files.move(from, to, options);
        };

        Path staging = home.resolve("staged-without-atomicity.tmp");
        Path target = home.resolve("published-without-atomicity.bin");
        Files.write(staging, PAYLOAD);
        Files.write(target, "the value being replaced".getBytes(StandardCharsets.UTF_8));
        store.move(staging, target, refusesAtomic);

        assertEquals(2, refusingAttempts.size(), "the atomic form must be tried and the plain one must follow it,"
                + " and the attempts were: " + refusingAttempts);
        assertTrue(refusingAttempts.get(0).contains(StandardCopyOption.ATOMIC_MOVE),
                "the atomic form must be tried first, so a filesystem that can move atomically does");
        assertFalse(refusingAttempts.get(1).contains(StandardCopyOption.ATOMIC_MOVE),
                "the retry must drop the option the filesystem refused, or it refuses again forever");
        assertTrue(refusingAttempts.get(1).contains(StandardCopyOption.REPLACE_EXISTING),
                "and must still replace what is there, or publishing over existing content would fail");
        assertArrayEquals(PAYLOAD, Files.readAllBytes(target), "the content must land even without atomicity");
        assertFalse(Files.exists(staging), "and the staged entry must be gone, not copied");

        // The ordinary filesystem, so the fallback above cannot be mistaken for the only path taken.
        List<List<CopyOption>> atomicAttempts = new ArrayList<>();
        FileSystemContentStore.Mover records = (from, to, options) -> {
            atomicAttempts.add(List.of(options));
            Files.move(from, to, options);
        };
        Path atomicStaging = home.resolve("staged-atomically.tmp");
        Path atomicTarget = home.resolve("published-atomically.bin");
        Files.write(atomicStaging, PAYLOAD);
        store.move(atomicStaging, atomicTarget, records);

        assertEquals(1, atomicAttempts.size(), "a filesystem that moves atomically must be asked exactly once");
        assertTrue(atomicAttempts.get(0).contains(StandardCopyOption.ATOMIC_MOVE),
                "and that one move must be the atomic one");
        assertArrayEquals(PAYLOAD, Files.readAllBytes(atomicTarget), "the content must land");
    }

    /**
     * Content deleted between a write's look at its destination and its open is published as a new file.
     *
     * <p>A write that finds a regular file stored rewrites it in place, opened without {@code CREATE} so it
     * can never write to something that is not the file it looked at. A delete of the same key landing between
     * those two steps therefore leaves the open with nothing to write to - and reporting that as a failure
     * would refuse a write that was asked for, where the upload path this provider stands in for, a plain
     * create-or-truncate open, would have stored the content. So the write publishes a new file instead.
     *
     * <p>Driven through the provider's own before-open seam rather than by contention, so the branch is
     * asserted every run rather than whenever the scheduler happens to interleave two threads the right way.
     * The seam fires once: a write that recovers by publishing must not then be re-entered.
     *
     * @param home a per-test temporary directory standing in for the storage root
     * @throws Exception if the provider cannot be exercised
     */
    @Test
    public void contentDeletedBetweenAWritesLookAndItsOpenIsPublishedAsANewFile(@TempDir Path home)
            throws Exception {
        FileSystemContentStore store = filesystemStoreRootedAt(home);
        String key = "runtime/uploads/party/vanishing.bin";
        Path target = home.resolve("runtime/uploads/party/vanishing.bin");
        byte[] replacement = new byte[3072];
        java.util.Arrays.fill(replacement, (byte) 'R');
        store.put(key, PAYLOAD);
        // Widened on purpose, and it is what tells the two paths apart afterwards. An in-place rewrite writes
        // through the file that is already there and leaves its mode alone; a published file is created
        // owner-only by this provider. The inode cannot be used for this - a filesystem is free to hand the
        // just-freed inode straight back to the staging entry, and Linux routinely does.
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r--r--"));

        AtomicBoolean once = new AtomicBoolean(false);
        AtomicInteger removals = new AtomicInteger();
        FileSystemContentStore.installBeforeOpenActionForTesting(() -> {
            if (once.compareAndSet(false, true)) {
                try {
                    Files.delete(target);
                    removals.incrementAndGet();
                } catch (IOException e) {
                    throw new IllegalStateException("the fixture could not remove the content", e);
                }
            }
        });
        try {
            store.put(key, replacement);
        } finally {
            FileSystemContentStore.installBeforeOpenActionForTesting(null);
        }

        assertEquals(1, removals.get(), "the fixture must have removed the content exactly once, or the branch"
                + " under test was never entered");
        assertArrayEquals(replacement, store.get(key), "a write whose destination was removed between the look"
                + " and the open must still store the content it was given");
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(target), "and must store it as a newly published file, created"
                        + " owner-only: the widened mode of the file it looked at cannot have survived, because"
                        + " that file no longer existed when the open happened");
        assertEquals(List.of("vanishing.bin"), namesIn(target.getParent()), "and must leave no staging entry"
                + " behind");
    }

    /**
     * Concurrent overwrites, deletes and reads of one key leave no staging entry and no unexplained failure.
     *
     * <p>Three things are asserted together, because each is a different way the provider could be wrong under
     * contention. A write must not be turned into a failure by a delete that ran between the moment the file
     * was looked at and the moment it was opened - the write was asked for, and the upload path this provider
     * stands in for would have stored it. A read must see only bytes that some write actually produced, never
     * the zeroes a write at an offset would leave behind. And whatever the last operation was, no staging entry
     * may survive: content in the tree under no key is content that would never be read, deleted or accounted
     * for.
     *
     * <p>A prefix, or a mixture of two written values, is admitted rather than refused here because an
     * existing file is rewritten in place by design; {@link #anInPlaceRewriteKeepsTheFileTheDeploymentAlreadyHad}
     * asserts that decision directly, and {@link #aStagedWriteIsInvisibleAtItsKeyUntilItIsComplete} asserts the
     * whole-or-nothing guarantee on the path that makes it. Absence is admitted too, because a delete can win.
     * NOTHING ELSE is: a contended read must not be refused, and in particular must not be refused by the
     * {@code content.store.max.object.size} ceiling, which content of 48 and 64 KiB cannot reach. That is
     * asserted rather than tolerated because it used to happen: the whole-object read probed for one byte
     * beyond what it had read even when it had stopped at end of file well short of the ceiling, so an
     * ordinary overwrite arriving in that instant was reported to the caller as content too large to read -
     * a refusal an operator could not act on, appearing at random under load and in roughly half of these runs.
     *
     * @param home a per-test temporary directory standing in for the storage root
     * @throws Exception if the provider cannot be exercised
     */
    @Test
    public void concurrentOverwritesAndDeletesLeaveNoStagingEntryAndNoUnexplainedFailure(@TempDir Path home)
            throws Exception {
        FileSystemContentStore store = filesystemStoreRootedAt(home);
        String key = "runtime/uploads/party/contended.bin";
        byte[] first = new byte[48 * 1024];
        byte[] second = new byte[64 * 1024];
        java.util.Arrays.fill(first, (byte) 'A');
        java.util.Arrays.fill(second, (byte) 'B');
        store.put(key, first);
        Path directory = home.resolve("runtime/uploads/party");

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_THREADS);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        List<String> staging = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int thread = 0; thread < CONCURRENT_THREADS; thread++) {
                // One writer, several deleters, the rest readers. Deliberately one writer: two in-place
                // rewrites of one key overlapping is an application-level conflict whose outcome is
                // undefined for exactly the reason it is undefined for the upload path this provider stands
                // in for - each open truncates and each writer holds its own offset - so this asserts the
                // contention that has a defined answer rather than the conflict that does not.
                int role = thread == 0 ? 0 : thread <= CONCURRENT_DELETERS ? 1 : 2;
                pool.execute(() -> {
                    try {
                        start.await();
                        for (int attempt = 0; attempt < CONCURRENT_ATTEMPTS; attempt++) {
                            switch (role) {
                            case 0 -> store.put(key, attempt % 2 == 0 ? first : second);
                            case 1 -> store.delete(key);
                            default -> {
                                try {
                                    byte[] read = store.get(key);
                                    for (byte seen : read) {
                                        if (seen != (byte) 'A' && seen != (byte) 'B') {
                                            failures.add(new AssertionError("a concurrent read saw a byte that"
                                                    + " was never written, which is what a write at an offset"
                                                    + " or a sparse hole would leave"));
                                            break;
                                        }
                                    }
                                } catch (FileNotFoundException deletedMeanwhile) {
                                    // A delete won the race. Absence is the one legitimate refusal here, and
                                    // every other IOException - the ceiling included - reaches the catch below
                                    // and is recorded as the unexplained failure it would be.
                                    assertNotNull(deletedMeanwhile.getMessage(), "absence must be explained");
                                }
                            }
                            }
                            // Sampled from every thread, so an abandoned staging entry is caught while the
                            // contention is running rather than only in whatever state it happens to end in.
                            for (String name : namesIn(directory)) {
                                if (!"contended.bin".equals(name)) {
                                    staging.add(name);
                                }
                            }
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        failures.add(interrupted);
                    } catch (Exception unexpected) {
                        failures.add(unexpected);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(CONCURRENT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "the contended operations must finish rather than deadlock");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(List.of(), failures, "no contended operation may fail or see content that was never"
                + " written: a delete that ran between a write's look and its open must not refuse the write");
        // Whatever the last operation was, the directory holds either the content or nothing - never a staging
        // entry, which is what an abandoned write would leave.
        List<String> survivors = namesIn(directory);
        assertTrue(survivors.isEmpty() || survivors.equals(List.of("contended.bin")),
                "the tree must hold either the content or nothing after the contention, and holds: " + survivors);
        // A staging entry observed while a write was in flight is expected and is not a failure; one that is
        // still there afterwards is, and that is what the sweep above and this assertion together establish.
        assertEquals(List.of(), namesIn(directory).stream().filter(name -> !"contended.bin".equals(name)).toList(),
                "no staging entry may survive the contention, and " + staging.size() + " were seen in flight");
    }

    /**
     * Lists the entry names of a directory, sorted, so a staging entry left behind is visible in a failure.
     *
     * @param directory the directory to list
     * @return its entry names in a stable order
     * @throws IOException if the directory cannot be listed
     */
    private static List<String> namesIn(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(entry -> entry.getFileName().toString()).sorted().toList();
        }
    }

    /**
     * An overwrite through the object store issues a second write of the whole object, and the read that follows
     * returns the replacement.
     *
     * <p>Asserted on the requests rather than on a store's behaviour, because that is what this provider decides:
     * a provider that issued a conditional or partial write - or that skipped the second write because the key
     * already existed - would leave the earlier content in place, and every caller would keep reading the value
     * it thought it had replaced.
     *
     * @throws Exception if the provider cannot be exercised
     */
    @Test
    public void anOverwriteThroughTheObjectStoreWritesTheWholeObjectAgainAndIsReadBack() throws Exception {
        S3Client client = mock(S3Client.class);
        installObjectStoreClient(client);
        ContentStore store = ContentStoreFactory.getContentStore();
        byte[] replacement = "the replacement".getBytes(StandardCharsets.UTF_8);

        store.put(KEY, PAYLOAD);
        store.put(KEY, replacement);

        ArgumentCaptor<PutObjectRequest> requests = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodies = ArgumentCaptor.forClass(RequestBody.class);
        verify(client, times(2)).putObject(requests.capture(), bodies.capture());
        assertEquals(List.of(KEY, KEY), requests.getAllValues().stream().map(PutObjectRequest::key).toList(),
                "both writes must name the same object key: a provider that varied the key would leave the"
                        + " earlier content readable under the old one");
        assertEquals(List.of((long) PAYLOAD.length, (long) replacement.length),
                bodies.getAllValues().stream().map(body -> body.optionalContentLength().orElseThrow()).toList(),
                "each write must send its own whole content, so the second is a replacement and not an append");
        for (PutObjectRequest issued : requests.getAllValues()) {
            assertNull(issued.ifNoneMatch(), "a write must not be made conditional on the object being absent:"
                    + " that would silently skip an overwrite");
        }
    }

    /**
     * The configured retry cap reaches the client as a bounded number of attempts.
     *
     * <p>The cap is what turns a store that has stopped answering into a request that fails rather than one that
     * retries until a deadline. Asserted through the strategy the builder is given: {@code maxAttempts} is one
     * more than the retry cap, because the first attempt is not a retry - an off-by-one here silently doubles or
     * removes the deployment's tolerance.
     *
     * @throws Exception if the provider cannot be built
     */
    @Test
    public void theConfiguredRetryCapReachesTheClientAsABoundedAttemptCount() throws Exception {
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_MAX_RETRIES, "5");
        RecordedSdk sdk = new RecordedSdk();
        S3ContentStore.installSdkConstructionForTesting(sdk);
        configureProvider(PROVIDER_S3);
        ContentStoreFactory.clearCache();

        assertNotNull(ContentStoreFactory.getContentStore(), "the provider must be built");

        ArgumentCaptor<ClientOverrideConfiguration> overrides =
                ArgumentCaptor.forClass(ClientOverrideConfiguration.class);
        verify(sdk.builder()).overrideConfiguration(overrides.capture());
        ClientOverrideConfiguration applied = overrides.getValue();
        assertTrue(applied.retryStrategy().isPresent() || applied.retryStrategyConfigurator().isPresent(),
                "a configured retry cap must reach the client as a retry strategy, or a failing store is retried"
                        + " until a deadline instead of a bounded number of times");
        RetryStrategy strategy;
        if (applied.retryStrategy().isPresent()) {
            strategy = applied.retryStrategy().get();
        } else {
            // The provider configures the strategy the SDK selects rather than supplying one, which is what
            // keeps the standard retry-capacity throttle in force. Applying that configurator to the standard
            // builder is therefore the only way to observe the cap it sets.
            RetryStrategy.Builder<?, ?> builder = DefaultRetryStrategy.standardStrategyBuilder();
            applied.retryStrategyConfigurator().orElseThrow().accept(builder);
            strategy = builder.build();
        }
        assertEquals(6, strategy.maxAttempts(), "a retry cap of 5 must become 6 attempts: the first attempt is"
                + " not a retry, and an off-by-one here silently changes the deployment's tolerance");
    }

    /**
     * Closing the provider is idempotent, and a client that raises while closing does not escape.
     *
     * <p>This runs while a provider is being replaced by a configuration change or while the JVM is stopping,
     * and neither has anywhere to report a failure to. Letting one out of the shutdown path would suppress the
     * rest of the cleanup - so every remaining resource would leak because of the first one that could not be
     * released.
     *
     * @throws Exception if the provider cannot be built
     */
    @Test
    public void closingTheProviderIsIdempotentAndSurvivesAClientThatRaisesWhileClosing() throws Exception {
        S3Client raising = mock(S3Client.class);
        doThrow(SdkClientException.create("the connection pool could not be shut down")).when(raising).close();
        S3ContentStore store = new S3ContentStore(raising, OFFLINE_BUCKET, null);

        assertDoesNotThrow(store::close, "a client that raises while closing must not let the failure escape the"
                + " shutdown path, or the rest of the cleanup is suppressed");
        assertDoesNotThrow(store::close, "closing twice must be a no-op: the factory closes a displaced provider"
                + " once, and a shutdown hook may close it again");
        verify(raising, times(2)).close();

        // The ordinary case, so the tolerance above cannot be mistaken for a provider that never closes at all.
        S3Client quiet = mock(S3Client.class);
        S3ContentStore closes = new S3ContentStore(quiet, OFFLINE_BUCKET, null);
        closes.close();
        verify(quiet).close();
    }

    /**
     * A read that finds more content than the ceiling allows aborts the response instead of draining it.
     *
     * <p>The distinction is the whole point of the bound. Closing an unread response makes the SDK read the rest
     * of the object first so the connection can be reused - which for an oversized object transfers exactly the
     * content the ceiling was meant to prevent transferring. Aborting drops the connection instead, which is the
     * cheaper outcome by the whole size of the object.
     *
     * <p>Both directions of the refusal are exercised, because they are two different pieces of code and only
     * one of them can be reached at a time. A response that <em>declares</em> a length over the ceiling is
     * refused before a single byte is taken off the wire; a response that under-declares - a store that omits
     * or misreports {@code Content-Length} - is refused one byte past the ceiling, which is the least that
     * proves the content does not fit. Each case asserts what was actually transferred, so an implementation
     * that drained the object and then complained would fail rather than pass on the exception type alone.
     *
     * @throws Exception if the provider cannot be exercised
     */
    @Test
    public void aReadOverTheCeilingAbortsTheResponseRatherThanDrainingIt() throws Exception {
        // The smallest ceiling the factory accepts, so the bound under test is the configured one rather than
        // the committed default a refused value would fall back to.
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_MAX_OBJECT_SIZE, "1024");
        S3Client client = mock(S3Client.class);
        installObjectStoreClient(client);
        ContentStore store = ContentStoreFactory.getContentStore();

        // A response that declares its oversize. Nothing may be read from it at all.
        CountedBody declared = new CountedBody(4096);
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength(4096L).build(), declared.abortable()));
        IOException refusedOnDeclaration = assertThrows(IOException.class, () -> store.get(KEY),
                "an object whose declared length is over the ceiling must be refused rather than read");
        assertTrue(refusedOnDeclaration.getMessage().contains("larger than this instance may read"),
                "the refusal must name the ceiling rather than surface an SDK failure: ["
                        + refusedOnDeclaration.getMessage() + "]");
        assertEquals(0, declared.bytesRead(), "a response that declares a length over the ceiling must be"
                + " refused before anything is transferred, which is the whole value of the declaration");
        assertEquals(1, declared.aborts(), "the response must be released by aborting, so the connection is"
                + " dropped rather than drained");

        // A response that under-declares. It has to be read, but only just past the ceiling.
        CountedBody underDeclared = new CountedBody(4096);
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength(1024L).build(), underDeclared.abortable()));
        IOException refusedOnRead = assertThrows(IOException.class, () -> store.get(KEY),
                "an object that holds more than the ceiling must be refused even when it declares less");
        assertTrue(refusedOnRead.getMessage().contains("larger than this instance may read"),
                "the refusal must name the ceiling rather than surface an SDK failure: ["
                        + refusedOnRead.getMessage() + "]");
        assertEquals(1025, underDeclared.bytesRead(), "the read must stop at exactly one byte past the ceiling:"
                + " fewer cannot prove the content does not fit, and more transfers content the ceiling exists"
                + " to avoid transferring - " + underDeclared.bytesRead() + " of 4096 bytes were taken");
        assertEquals(1, underDeclared.aborts(), "the response must be released by aborting, so the connection"
                + " is dropped rather than drained");

        // The ordinary case, so neither assertion above can be satisfied by a provider that refuses everything.
        CountedBody fits = new CountedBody(1024);
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength(1024L).build(), fits.abortable()));
        assertEquals(1024, store.get(KEY).length, "content inside the ceiling must be returned whole");
        assertEquals(0, fits.aborts(), "content that was read to its end must be released by closing, so the"
                + " connection can be reused");
    }

    /**
     * The two classes of setting are exactly what the factory says they are: every DYNAMIC setting is absent
     * from the construction-bound set, every other {@code content.store.*} setting is present in it, and
     * changing a dynamic one therefore cannot produce a "restart to apply" report.
     *
     * <p>This is the property the reload semantics rest on, and conflating the two classes was the defect: one
     * fingerprint covered every {@code content.store.*} value and one warning said every one of them waited for
     * a restart, while {@code content.store.max.object.size} and {@code content.store.local.fallback} are read
     * on every use and so were already in force. A deployment acting on that warning would have restarted a
     * fleet for a change that had already applied, and - worse in the other direction - would have been told
     * nothing at all about the settings that genuinely do wait.
     *
     * <p>Asserted against the SHIPPED resource rather than a fixture, so that a setting added to
     * {@code content.properties} is classified by this test the moment it is shipped: a new setting lands in
     * the construction-bound set unless it is declared dynamic, which is the fail-safe direction.
     *
     * @throws Exception if the resource cannot be read
     */
    @Test
    public void theDynamicSettingsAreExcludedFromTheConstructionBoundSetAndEveryOtherOneIsIncluded()
            throws Exception {
        Properties committed = new Properties();
        try (InputStream bytes = Files.newInputStream(committedContentProperties())) {
            committed.load(bytes);
        }
        SortedSet<String> constructionBound = ContentStoreFactory.constructionBoundProperties();

        // The dynamic set is not empty, so the assertions below are not vacuously true.
        assertEquals(Set.of(PROPERTY_MAX_OBJECT_SIZE, PROPERTY_LOCAL_FALLBACK), ContentStoreFactory.DYNAMIC_PROPERTIES,
                "the settings read on every use are the read ceiling and the local fallback, and adding another"
                        + " one has to be a deliberate change to this assertion as well");

        for (String dynamic : ContentStoreFactory.DYNAMIC_PROPERTIES) {
            assertTrue(committed.containsKey(dynamic), "a setting declared dynamic must actually be shipped: ["
                    + dynamic + "]");
            assertFalse(constructionBound.contains(dynamic), "a setting read on every use must be absent from the"
                    + " construction-bound set, or a change to it would be reported as needing a restart it does"
                    + " not need: [" + dynamic + "]");
        }

        // The selector is covered even though a deployment may not declare it at all.
        assertTrue(constructionBound.contains(PROPERTY_PROVIDER), "the provider selector is construction-bound"
                + " whether or not the shipped file declares it");

        for (String name : committed.stringPropertyNames()) {
            if (!name.startsWith("content.store.") || ContentStoreFactory.DYNAMIC_PROPERTIES.contains(name)) {
                continue;
            }
            assertTrue(constructionBound.contains(name), "every shipped content.store.* setting that is not"
                    + " declared dynamic is sealed into a provider when it is built and must be covered by the"
                    + " construction-bound set: [" + name + "]");
        }
    }

    /**
     * The committed deadlines, retry cap, read ceiling and tail defaults are the values an untouched provider
     * applies, read from the shipped file rather than from a value a test wrote.
     *
     * <p>These are the settings with no environment variable of their own: they are documented in
     * {@code content.properties} beside the values themselves, every deployment runs on them, and each is
     * overridable per instance through a {@code SystemProperty} row. Nothing established that what the file says
     * is what the code applies, which is the one thing the documentation is actually promising - so a change to
     * either side alone silently makes the documentation wrong.
     *
     * @throws Exception if the resource cannot be read
     */
    @Test
    public void theCommittedDeadlinesRetryCapAndTailDefaultsAreTheOnesTheProviderApplies() throws Exception {
        Properties committed = new Properties();
        try (InputStream bytes = Files.newInputStream(committedContentProperties())) {
            committed.load(bytes);
        }

        // The documented values. Stated here as literals on purpose: reading them from the file and comparing
        // them with themselves would assert nothing, so the file has to agree with a number written down.
        assertEquals("30000", committed.getProperty(PROPERTY_S3_API_TIMEOUT), "the committed whole-call deadline");
        assertEquals("10000", committed.getProperty(PROPERTY_S3_ATTEMPT_TIMEOUT),
                "the committed per-attempt deadline, which must be shorter than the whole-call one");
        assertEquals("3", committed.getProperty(PROPERTY_S3_MAX_RETRIES), "the committed retry cap");
        assertEquals("10485760", committed.getProperty(PROPERTY_MAX_OBJECT_SIZE),
                "the committed ceiling on a whole-object read");
        assertEquals("true", committed.getProperty(PROPERTY_LOCAL_FALLBACK),
                "the committed local fallback must be ON: selecting an object store must not stop a deployment"
                        + " serving content it served the day before, so content that predates the store keeps"
                        + " answering from the local copy - reported once per resource - until it has been copied"
                        + " in, and the strict posture is then asked for explicitly");
        assertEquals("false", committed.getProperty(PROPERTY_S3_PATH_STYLE),
                "the committed addressing style must be Amazon S3's, which is what an unconfigured value means");
        assertEquals("", committed.getProperty(PROPERTY_S3_KEY_PREFIX),
                "the committed key prefix must be blank, so keys start at the root of the bucket");
        assertTrue(Long.parseLong(committed.getProperty(PROPERTY_S3_ATTEMPT_TIMEOUT))
                < Long.parseLong(committed.getProperty(PROPERTY_S3_API_TIMEOUT)),
                "a per-attempt deadline at or above the whole-call one leaves no room for a retry, which is what"
                        + " the retry cap exists to allow");

        // ...and the code applies exactly those. Read back through the accessors a deployment resolves through,
        // with nothing written in memory first.
        for (Map.Entry<String, String> held : committedProperties.entrySet()) {
            UtilProperties.setPropertyValueInMemory(RESOURCE, held.getKey(), held.getValue());
        }
        ContentStoreFactory.clearCache();
        assertEquals(Long.parseLong(committed.getProperty(PROPERTY_MAX_OBJECT_SIZE)),
                ContentStoreFactory.maxObjectSize(null), "the ceiling the code applies must be the committed one");
        assertTrue(ContentStoreFactory.localFallbackEnabled(null),
                "the fallback the code applies must be the committed one");
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
    public void aForbiddenLocationIsRefusedBeforeAnyProviderIsAsked(@TempDir Path home, @TempDir Path elsewhere)
            throws Exception {
        // The order matters more than the refusal: a location the allow list rejects must cost no provider
        // request, or the seam becomes an oracle telling a caller whether forbidden content exists (CWE-200).
        // The object store endpoint refuses every connection, so a request would surface as a store failure
        // rather than as this allow-list refusal.
        System.setProperty("ofbiz.home", home.toString());
        configureProvider(PROVIDER_S3);
        // Extension owned rather than a bare createTempFile: outside the deployment tree, which is what makes
        // the allow list refuse it, but inside a directory JUnit removes whatever this test does.
        Path outside = elsewhere.resolve("outside-the-deployment.txt");
        Files.writeString(outside, "not reachable through a DataResource");

        GeneralException refused = assertThrows(GeneralException.class, () -> renderedThroughSeam("LOCAL_FILE",
                outside.toString(), null, seamDelegator("default", null), "10000"));

        assertTrue(refused.getMessage().contains("not within an allowed directory"), "the refusal must come"
                + " from the allow list, which proves authorisation ran before any provider request: ["
                + refused.getMessage() + "]");
    }

    @Test
    public void aProviderFailureIsReportedRatherThanAnsweredFromLocalDisk(@TempDir Path home) throws Exception {
        // A store that cannot be reached is a broken deployment, not an absent object. Answering it from
        // whatever the local disk happens to hold is how an outage turns into silently serving stale content.
        System.setProperty("ofbiz.home", home.toString());
        configureProvider(PROVIDER_S3);
        Path uploads = Files.createDirectories(home.resolve("runtime/uploads"));
        Files.writeString(uploads.resolve("stale.txt"), "a copy this instance happens to hold");

        // The exact type, not Exception: an assertion that accepts any exception is satisfied by a
        // NullPointerException from a mis-stubbed fixture, by a reflective failure in the seam invocation and
        // by an assertion error from a helper - none of which is a store failure being reported, which is the
        // only thing this case is about. IOException is the SPI's declared way of saying "the store could not
        // serve this", and it is what the caller distinguishes from FileNotFoundException.
        IOException reported = assertThrows(IOException.class, () -> renderedThroughSeam("OFBIZ_FILE",
                "/runtime/uploads/stale.txt", null, seamDelegator("default", null), "10000"),
                "an unreachable store must be reported, never answered from the local copy");
        assertFalse(reported instanceof FileNotFoundException, "an unreachable store must NOT be reported as an"
                + " absent object: FileNotFoundException is what the local-fallback decision acts on, so"
                + " reporting an outage that way would let a migration setting serve stale content during one");
        assertTrue(reported.getMessage().contains("could not open the requested content"),
                "the report must say that the store could not serve the content: [" + reported.getMessage() + "]");
        // A correlation reference and nothing else. The message reaches a caller that may be a browser, so it
        // must carry neither the endpoint, the bucket, the key nor the credential - only enough to find the
        // logged cause.
        assertTrue(reported.getMessage().contains("Reference ["), "the report must carry a correlation reference"
                + " so the cause can be found in the log: [" + reported.getMessage() + "]");
        for (String withheld : List.of(BUCKET, ACCESS_KEY_ID, SECRET_ACCESS_KEY, ENDPOINT, "/runtime/uploads")) {
            assertFalse(reported.getMessage().contains(withheld), "the report must not disclose [" + withheld
                    + "]: it reaches a caller that may be a browser. [" + reported.getMessage() + "]");
        }
        // And the SDK failure itself crosses no boundary, not even as the cause: anything that logs a caught
        // IOException with its stack trace, or reads getCause().getMessage(), would republish whatever the SDK
        // quoted - and an SDK message can quote the request it was building, endpoint and signed headers
        // included. What crosses is the sanitised stand-in, which is what keeps the report inspectable
        // without making it disclosing. See assertSanitisedCause.
        assertSanitisedCause(reported);
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

    // ---------------------------------------------------------------------------------------------------------
    // The caller-level matrix
    //
    // The tests above each prove one behaviour of the seams. These prove the same behaviours across the whole
    // surface the callers actually present: every file-backed resource type, every render branch, both upload
    // families, and the refusals whose ORDER decides which exception a caller sees. Table driven, because the
    // rows are the point - a branch missing from the table is a branch nothing asserts, and a table makes that
    // visible in a way a set of individually named tests does not.
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Every file-backed resource type resolves to its own local location, and asks a provider for nothing.
     *
     * <p>Six types name a file - three families, each with a {@code _BIN} variant - and each family builds its
     * location differently: {@code LOCAL_FILE} from the {@code objectInfo} alone, {@code OFBIZ_FILE} under
     * {@code ofbiz.home}, {@code CONTEXT_FILE} under the webapp context root. The {@code _BIN} variants are not
     * cosmetic: they are what an image or document upload records, so a family whose variant fell through would
     * lose exactly the content this objective is about.
     *
     * <p>What is asserted for every row is the same two things. The location is the local one the
     * {@code objectInfo} names, with the content this instance holds - because the write services take the
     * {@code File} this returns and write through it, so a provider's copy handed back here would be written
     * over and lost. And no request of any kind reaches the object store, asserted against a client that was
     * never stubbed, so any interaction at all fails the row.
     *
     * @param type the {@code dataResourceTypeId} under test
     * @param family which of the three location forms the type uses
     * @param home a per-test temporary directory standing in for {@code ofbiz.home}
     * @throws Exception if the location cannot be resolved
     */
    @ParameterizedTest(name = "{0} resolves to its own local location")
    @CsvSource({
        "LOCAL_FILE,local",
        "LOCAL_FILE_BIN,local",
        "OFBIZ_FILE,ofbiz",
        "OFBIZ_FILE_BIN,ofbiz",
        "CONTEXT_FILE,context",
        "CONTEXT_FILE_BIN,context"})
    public void everyFileBackedTypeResolvesToItsOwnLocalLocation(String type, String family, @TempDir Path home)
            throws Exception {
        System.setProperty("ofbiz.home", home.toString());
        S3Client untouched = mock(S3Client.class);
        installObjectStoreClient(untouched);
        Path expected = locationFor(family, home, "resolved.bin");
        Files.write(expected, (type + " content held by this instance").getBytes(StandardCharsets.UTF_8));

        File resolved = DataResourceWorker.getContentFile(type, objectInfoFor(family, expected),
                contextRootFor(family, home));

        assertNotNull(resolved, type + " names a file, so a location must be resolved for it");
        assertEquals(expected.toRealPath(), resolved.toPath().toRealPath(), "the resolved location must be the"
                + " local one the objectInfo names, because a writer writes through it");
        assertArrayEquals(Files.readAllBytes(expected), Files.readAllBytes(resolved.toPath()),
                "and must hold what this instance holds, not a provider's copy of it");
        verifyNoInteractions(untouched);
    }

    /**
     * A resource type that names no file resolves to nothing, and still asks a provider for nothing.
     *
     * <p>The other half of the row set above. {@code getContentFile} has always answered {@code null} for a type
     * whose content is in the database or at a URL, and callers branch on that {@code null}; answering anything
     * else - including throwing - would change what every caller does with a text resource.
     *
     * @param type a {@code dataResourceTypeId} that names no file
     * @param home a per-test temporary directory standing in for {@code ofbiz.home}
     * @throws Exception if the resolution throws
     */
    @ParameterizedTest(name = "{0} names no file and resolves to nothing")
    @ValueSource(strings = {"ELECTRONIC_TEXT", "SHORT_TEXT", "IMAGE_OBJECT", "VIDEO_OBJECT", "AUDIO_OBJECT",
        "OTHER_OBJECT", "URL_RESOURCE", "LINK"})
    public void aTypeThatNamesNoFileResolvesToNothing(String type, @TempDir Path home) throws Exception {
        System.setProperty("ofbiz.home", home.toString());
        S3Client untouched = mock(S3Client.class);
        installObjectStoreClient(untouched);

        assertNull(DataResourceWorker.getContentFile(type, "/runtime/uploads/irrelevant.txt", home.toString()),
                type + " holds no file, so no location may be resolved for it: callers branch on this null");
        verifyNoInteractions(untouched);
    }

    /**
     * Each type's refusals keep the order they have always had, so each names the exception it always named.
     *
     * <p>The order is the contract, not just the refusals. {@code LOCAL_FILE} tests presence <em>before</em> it
     * tests absoluteness, so a relative location that holds nothing is a {@code FileNotFoundException} while a
     * relative location that holds something is a {@code GeneralException} - two different answers from one
     * pair of checks, and swapping them would change which one every caller of a relative location receives.
     * The table therefore carries both, together with the allow-list refusal of each family, the empty context
     * root, and a location that escapes the context root.
     *
     * <p>An object store is configured throughout, and for every REFUSAL it is never stubbed and never touched:
     * a location an operator has forbidden must cost no provider request, or a caller becomes an oracle for
     * whether forbidden content exists. The one row that is not a refusal of the location - an {@code OFBIZ_FILE}
     * inside the tree that simply holds nothing - is the opposite case and asserts the opposite thing: the store
     * MUST be asked, because content published by another instance leaves nothing on this one\'s disk, and only
     * once the store has answered "nothing here either" is absence the answer. It gets a store that reports
     * absence the way a real one does.
     *
     * @param type the {@code dataResourceTypeId} under test
     * @param form which refusal the row exercises
     * @param expected the simple name of the exception the row must raise
     * @param fragment what the refusal must say
     * @param home a per-test temporary directory standing in for {@code ofbiz.home}
     * @param elsewhere a per-test temporary directory outside the deployment tree
     * @throws Exception if the fixture cannot be built
     */
    @ParameterizedTest(name = "{0} {1} is refused as {2}")
    @CsvSource({
        "LOCAL_FILE,relative-absent,FileNotFoundException,No file found",
        "LOCAL_FILE_BIN,relative-absent,FileNotFoundException,No file found",
        "LOCAL_FILE,relative-present,GeneralException,is not absolute",
        "LOCAL_FILE_BIN,relative-present,GeneralException,is not absolute",
        "LOCAL_FILE,outside-allow-list,GeneralException,not within an allowed directory",
        "LOCAL_FILE_BIN,outside-allow-list,GeneralException,not within an allowed directory",
        "OFBIZ_FILE,ofbiz-absent,FileNotFoundException,No file found",
        "OFBIZ_FILE_BIN,ofbiz-absent,FileNotFoundException,No file found",
        "OFBIZ_FILE,ofbiz-outside-allow-list,GeneralException,not within an allowed directory",
        "OFBIZ_FILE_BIN,ofbiz-outside-allow-list,GeneralException,not within an allowed directory",
        "CONTEXT_FILE,empty-context-root,GeneralException,empty context root",
        "CONTEXT_FILE_BIN,empty-context-root,GeneralException,empty context root",
        "CONTEXT_FILE,context-escape,GeneralException,resolves outside of the allowed directory",
        "CONTEXT_FILE_BIN,context-escape,GeneralException,resolves outside of the allowed directory",
        "CONTEXT_FILE,context-absent,FileNotFoundException,No file found"})
    public void eachTypesRefusalsKeepTheOrderTheyHaveAlwaysHad(String type, String form, String expected,
            String fragment, @TempDir Path home, @TempDir Path elsewhere) throws Exception {
        System.setProperty("ofbiz.home", home.toString());
        // The store is consulted only for a location that is inside the deployment tree and merely absent; every
        // other row is a refusal of the location itself, which must never reach a provider.
        boolean storeMayBeAsked = "ofbiz-absent".equals(form);
        S3Client untouched = storeMayBeAsked ? inMemoryObjectStore(new ConcurrentHashMap<>()) : mock(S3Client.class);
        installObjectStoreClient(untouched);
        Files.createDirectories(home.resolve("runtime/uploads"));
        Path context = Files.createDirectories(home.resolve("webapp"));
        String objectInfo;
        String contextRoot = context.toString();
        switch (form) {
        case "relative-absent" -> objectInfo = "no-such-relative-content-" + type + ".bin";
        case "relative-present" -> objectInfo = anExistingRelativeName();
        case "outside-allow-list" -> {
            Path outside = elsewhere.resolve("outside-the-deployment.bin");
            Files.write(outside, PAYLOAD);
            objectInfo = outside.toAbsolutePath().toString();
        }
        case "ofbiz-absent" -> objectInfo = "/runtime/uploads/never-written.bin";
        case "ofbiz-outside-allow-list" -> {
            Path forbidden = Files.createDirectories(home.resolve("notallowed")).resolve("present.bin");
            Files.write(forbidden, PAYLOAD);
            objectInfo = "/notallowed/present.bin";
        }
        case "empty-context-root" -> {
            objectInfo = "/static.bin";
            contextRoot = "";
        }
        case "context-escape" -> {
            Files.write(home.resolve("escaped.bin"), PAYLOAD);
            objectInfo = "/../escaped.bin";
        }
        case "context-absent" -> objectInfo = "/never-shipped.bin";
        default -> throw new AssertionError("the table names a form this test does not build: " + form);
        }

        Class<? extends Exception> refusal = "FileNotFoundException".equals(expected)
                ? FileNotFoundException.class : GeneralException.class;
        String location = objectInfo;
        String root = contextRoot;
        Exception thrown = assertThrows(refusal, () -> DataResourceWorker.getContentFile(type, location, root),
                type + " " + form + " must be refused as " + expected);

        assertTrue(thrown.getMessage() != null && thrown.getMessage().contains(fragment),
                "the refusal must say [" + fragment + "] and said: [" + thrown.getMessage() + "]");
        // FileNotFoundException is a GeneralException in neither direction, so the row above already pins the
        // type - but a refusal raised as the wrong one of the two would still be caught by a caller that
        // catches both, which is why the exact class is asserted rather than a common supertype.
        assertEquals(refusal, thrown.getClass(), "the exact exception class is part of the contract, because"
                + " the two are distinguished by callers that treat absence differently from refusal");
        if (storeMayBeAsked) {
            verify(untouched).getObject(any(GetObjectRequest.class));
        } else {
            verifyNoInteractions(untouched);
        }
    }

    /**
     * Every render branch is served from the provider when one is configured, except the one that ships in the
     * image, and every branch falls back to the local read when content is held in the database.
     *
     * <p>{@code renderFile} has three file branches and they are separate pieces of code, so a seam present in
     * one is not present in the others by implication. {@code LOCAL_FILE} and {@code OFBIZ_FILE} name content a
     * deployment writes and therefore content a fleet must share; {@code CONTEXT_FILE} names content that ships
     * inside the image and is identical on every instance, so it is read locally even when a store is
     * configured - externalising it would move a read from a local disk to a network for nothing.
     *
     * <p>Each row is decided by content, not by counting calls: the store holds a different value from the
     * local file, so which one came back says which path ran.
     *
     * @param type the {@code dataResourceTypeId} under test
     * @param family which of the three location forms the type uses
     * @param storage the configured provider
     * @param servedBy which copy the row must be served from
     * @param home a per-test temporary directory standing in for {@code ofbiz.home}
     * @throws Exception if the seam cannot be exercised
     */
    @ParameterizedTest(name = "{0} under {2} storage is served from the {3} copy")
    @CsvSource({
        "LOCAL_FILE,local,s3,provider",
        "OFBIZ_FILE,ofbiz,s3,provider",
        "CONTEXT_FILE,context,s3,local",
        "LOCAL_FILE,local,database,local",
        "OFBIZ_FILE,ofbiz,database,local",
        "CONTEXT_FILE,context,database,local"})
    public void everyRenderBranchIsServedFromTheCopyItsContentBelongsTo(String type, String family, String storage,
            String servedBy, @TempDir Path home) throws Exception {
        System.setProperty("ofbiz.home", home.toString());
        Path local = locationFor(family, home, "rendered.txt");
        Files.writeString(local, "the local copy");
        Map<String, byte[]> objects = new ConcurrentHashMap<>();
        // Under the key the seam derives, which is the content's own ofbiz.home-relative path - the same key the
        // filesystem provider uses, so one row means one piece of content whichever provider is configured.
        objects.put(home.relativize(local).toString().replace('\\', '/'),
                "the provider's copy".getBytes(StandardCharsets.UTF_8));
        if (PROVIDER_S3.equals(storage)) {
            installObjectStoreClient(inMemoryObjectStore(objects));
        } else {
            configureProvider(storage);
            ContentStoreFactory.clearCache();
        }

        String rendered = renderedThroughSeam(type, objectInfoFor(family, local), contextRootFor(family, home),
                seamDelegator("default", null), "10000");

        assertEquals("provider".equals(servedBy) ? "the provider's copy" : "the local copy", rendered,
                type + " under " + storage + " storage must be served from the " + servedBy + " copy");
    }

    /**
     * Builds the local location a family's row is written at.
     *
     * @param family one of local, ofbiz or context
     * @param home the directory standing in for {@code ofbiz.home}
     * @param name the file name to use
     * @return the created parent's resolved child path
     * @throws IOException if the parent cannot be created
     */
    private static Path locationFor(String family, Path home, String name) throws IOException {
        return switch (family) {
        case "local", "ofbiz" -> Files.createDirectories(home.resolve("runtime/uploads")).resolve(name);
        case "context" -> Files.createDirectories(home.resolve("webapp")).resolve(name);
        default -> throw new AssertionError("the table names a family this test does not build: " + family);
        };
    }

    /**
     * Builds the {@code objectInfo} a family records for a location.
     *
     * @param family one of local, ofbiz or context
     * @param location the local location
     * @return the {@code objectInfo} value
     */
    private static String objectInfoFor(String family, Path location) {
        return switch (family) {
        // LOCAL_FILE records an absolute path; the other two record a path relative to their own root.
        case "local" -> location.toAbsolutePath().toString();
        case "ofbiz" -> "/runtime/uploads/" + location.getFileName();
        case "context" -> "/" + location.getFileName();
        default -> throw new AssertionError("the table names a family this test does not build: " + family);
        };
    }

    /**
     * Builds the context root a family is resolved against.
     *
     * @param family one of local, ofbiz or context
     * @param home the directory standing in for {@code ofbiz.home}
     * @return the context root, or null for the families that ignore it
     */
    private static String contextRootFor(String family, Path home) {
        return "context".equals(family) ? home.resolve("webapp").toString() : null;
    }

    /**
     * Finds the name of a file that exists relative to this process's working directory.
     *
     * <p>Needed by the "relative but present" row, which is the one that pins the order of the presence and
     * absoluteness checks. Discovered rather than written down, and nothing is created, so the row asserts
     * against the tree as it is and leaves it exactly so.
     *
     * @return a relative name that resolves to an existing file
     */
    private static String anExistingRelativeName() {
        File[] entries = new File(".").listFiles();
        assertNotNull(entries, "this process's working directory must be listable for this row to exist");
        for (File entry : entries) {
            if (entry.isFile() && new File(entry.getName()).isFile()) {
                return entry.getName();
            }
        }
        throw new AssertionError("no file exists relative to this process's working directory, so the relative"
                + " but present case cannot be built");
    }

    @Test
    public void contentTheUploadValidationRefusesIsNeverPublished(@TempDir Path home) throws Exception {
        // Publishing happens AFTER the upload validation, which is the whole reason the seam sits where it
        // does: content a service is about to refuse must never reach a store every instance reads.
        System.setProperty("ofbiz.home", home.toString());
        Files.createDirectories(home.resolve("runtime/uploads"));
        RecordedObjectStore store = recordingObjectStoreClient();

        Map<String, Object> result = DataServices.createFileMethod(dispatchContextFor(seamDelegator("default", null)),
                UtilMisc.toMap("dataResourceTypeId", "OFBIZ_FILE_BIN", "objectInfo",
                        "/runtime/uploads/webshell.jsp", "dataResourceId", "10007", "binData",
                        ByteBuffer.wrap(smallPng()), "locale", Locale.ENGLISH));

        assertTrue(ServiceUtil.isError(result), "an upload the validation refuses must not report success");
        verify(store.client(), never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    public void contentLargerThanTheWholeReadCeilingIsStreamedRatherThanRefused(@TempDir Path home)
            throws Exception {
        // CR-02. The whole-read ceiling is 10 MiB, and a stream consumer must not be bound by it: binary
        // content is served through the streaming operation, so an upload larger than the ceiling is served
        // in full. The declared length is what the consumer reports, and it arrives without the content
        // being materialised anywhere.
        System.setProperty("ofbiz.home", home.toString());
        Path uploads = Files.createDirectories(home.resolve("runtime/uploads"));
        Files.writeString(uploads.resolve("large.bin"), "the local copy is never served here");
        long length = DEFAULT_MAX_OBJECT_SIZE + 1024L;
        S3Client client = mock(S3Client.class);
        when(client.getObject(any(GetObjectRequest.class))).thenAnswer(invocation -> new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength(length).build(),
                AbortableInputStream.create(new RepeatingInputStream(length))));
        installObjectStoreClient(client);

        Map<String, Object> served = DataResourceWorker.getDataResourceStream(
                fileResource("OFBIZ_FILE_BIN", "/runtime/uploads/large.bin", "10006",
                        seamDelegator("default", null)), "N", null, null, null, false);

        assertEquals(length, served.get("length"), "the exact length must be reported without reading the"
                + " content to measure it");
        long counted = 0L;
        try (InputStream stream = (InputStream) served.get("stream")) {
            byte[] buffer = new byte[64 * 1024];
            for (int read = stream.read(buffer); read >= 0; read = stream.read(buffer)) {
                counted += read;
            }
        }
        assertEquals(length, counted, "every byte above the whole-read ceiling must still be served");
        assertEquals(1, Files.list(uploads).count(), "streaming must spool nothing into the deployment's tree");
    }

    @Test
    public void manyThreadsResolvingOneConfigurationFromColdShareOneProviderAndCloseEveryLoser()
            throws Exception {
        // The race this asserts on happens exactly once in an instance's life: at the first content read
        // after a start, or after a configuration change, when nothing is resolved yet and every request
        // thread that arrives goes on to build a provider of its own. Resolving once before the threads
        // start would warm the map and make every thread take the fast path, so the construction path -
        // the only place two clients can be built and one of them has to be closed - would never run at
        // all. This therefore begins from an EMPTY resolution map and never resolves before the threads.
        //
        // Two things then have to hold, and they are not the same thing. One provider is installed and
        // handed to every thread, because two would mean two connection pools and a thread still holding
        // the displaced one would start failing on a closed client. And every provider that lost the race
        // is closed by whoever built it, because a client dropped without being closed keeps its pool and
        // its threads for the life of the JVM - which is a leak proportional to how hard the instance was
        // hit at the moment it started, the worst possible time.
        CountDownLatch racing = new CountDownLatch(CONCURRENT_THREADS);
        CountingConstruction construction = installCountedObjectStoreConstruction(racing);
        ContentStoreFactory.clearCache();
        assertEquals(0, construction.constructed(), "the resolution map must be empty before the threads"
                + " start, otherwise this test cannot reach the construction path at all");

        CountDownLatch startTogether = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_THREADS);
        List<ContentStore> handedOut = Collections.synchronizedList(new ArrayList<>());
        try {
            List<Future<Void>> answers = new ArrayList<>();
            for (int thread = 0; thread < CONCURRENT_THREADS; thread++) {
                answers.add(pool.submit(() -> {
                    startTogether.await();
                    for (int attempt = 0; attempt < CONCURRENT_ATTEMPTS; attempt++) {
                        handedOut.add(ContentStoreFactory.getContentStore());
                    }
                    return null;
                }));
            }
            startTogether.countDown();
            for (Future<Void> answer : answers) {
                answer.get(CONCURRENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        ContentStore installed = ContentStoreFactory.getContentStore();
        assertNotNull(installed, "the object store must be selected for this test to mean anything");
        assertEquals(1, new HashSet<>(handedOut).size(), "every concurrent resolution of one configuration must"
                + " hand back the one provider, and " + new HashSet<>(handedOut).size() + " distinct providers"
                + " were handed out across " + handedOut.size() + " resolutions");
        assertSame(installed, handedOut.get(0), "the provider still held must be the one the threads were"
                + " given, not a later replacement");

        // Counted rather than assumed: how many threads actually reached the construction path depends on
        // the scheduling, and anywhere from one to CONCURRENT_THREADS is correct. What is not
        // scheduling-dependent is the relationship - exactly one construction survives and every other one
        // is closed - so that is what is asserted.
        int constructed = construction.constructed();
        assertTrue(constructed > 1, "the construction race must actually have happened for the closure"
                + " assertion below to mean anything: " + constructed + " provider(s) were built, so there"
                + " was no loser to close. Every thread should have been held inside the construction until"
                + " its peers joined it.");
        assertEquals(constructed - 1, construction.closed().size(), "exactly the losers must be closed:"
                + " of " + constructed + " constructions, " + construction.closed().size() + " were closed");
        assertFalse(construction.closed().contains(installed), "the provider the factory still hands out must"
                + " not be among the closed ones");
        for (ContentStore loser : construction.all()) {
            if (loser != installed) {
                assertTrue(construction.closed().contains(loser), "a provider that lost the construction race"
                        + " must be closed by whoever built it");
            }
        }
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

    /**
     * One object-store provider serves every operation from many threads at once without crossing them.
     *
     * <p>The factory hands one provider instance to every request thread, and this one holds the bucket, the
     * key prefix and the SDK client as fields. A field written per request rather than per provider would show
     * up here and nowhere else: each thread stores, checks, reads, streams and removes content under a key of
     * its own, so content served to the wrong caller is a failed assertion rather than an incident. The client
     * is a small in-memory store rather than a stub returning one canned answer, because a canned answer
     * cannot tell a crossed key from a correct one.
     *
     * @throws Exception if the provider cannot be exercised
     */
    @Test
    public void oneObjectStoreProviderServesEveryOperationConcurrentlyWithoutCrossingKeys() throws Exception {
        Map<String, byte[]> objects = new ConcurrentHashMap<>();
        installObjectStoreClient(inMemoryObjectStore(objects));
        ContentStore store = ContentStoreFactory.getContentStore();
        assertNotNull(store, "the object-store provider must be selected");

        CountDownLatch startTogether = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_THREADS);
        try {
            List<Future<Integer>> answers = new ArrayList<>();
            for (int thread = 0; thread < CONCURRENT_THREADS; thread++) {
                String key = "dataresource/default/9" + String.format(Locale.ROOT, "%04d", thread);
                answers.add(pool.submit(() -> {
                    startTogether.await();
                    int served = 0;
                    for (int attempt = 0; attempt < CONCURRENT_ATTEMPTS; attempt++) {
                        byte[] mine = (key + "#" + attempt).getBytes(StandardCharsets.UTF_8);
                        store.put(key, mine);
                        assertTrue(store.exists(key), "content just stored must be reported as present");
                        assertArrayEquals(mine, store.get(key), "a whole read must return this thread's own"
                                + " content, not another thread's");
                        try (ContentStore.ContentStream opened = store.openStream(key)) {
                            assertEquals(mine.length, opened.length(), "a streamed read must declare this"
                                    + " thread's own length");
                            assertArrayEquals(mine, opened.readAllBytes(), "a streamed read must return this"
                                    + " thread's own content");
                        }
                        store.delete(key);
                        assertFalse(store.exists(key), "content just removed must be reported as absent");
                        served++;
                    }
                    return served;
                }));
            }
            startTogether.countDown();
            for (Future<Integer> answer : answers) {
                assertEquals(CONCURRENT_ATTEMPTS, answer.get(CONCURRENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .intValue(), "every concurrent operation must be served");
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(Map.of().keySet(), objects.keySet(), "every key must have been removed by the thread that"
                + " created it, and these were left: " + objects.keySet());
    }

    /**
     * A client that behaves like a small object store, so a crossed key is visible rather than invisible.
     *
     * @param objects the content the store holds, keyed by object key
     * @return the client to hand the provider
     */
    private static S3Client inMemoryObjectStore(Map<String, byte[]> objects) {
        S3Client client = mock(S3Client.class);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(call -> {
            PutObjectRequest request = call.getArgument(0);
            RequestBody body = call.getArgument(1);
            try (InputStream bytes = body.contentStreamProvider().newStream()) {
                objects.put(request.key(), bytes.readAllBytes());
            }
            return PutObjectResponse.builder().build();
        });
        when(client.getObject(any(GetObjectRequest.class))).thenAnswer(call -> {
            GetObjectRequest request = call.getArgument(0);
            byte[] held = objects.get(request.key());
            if (held == null) {
                throw NoSuchKeyException.builder().message("no such key").build();
            }
            return new ResponseInputStream<>(GetObjectResponse.builder().contentLength((long) held.length).build(),
                    AbortableInputStream.create(new ByteArrayInputStream(held)));
        });
        when(client.headObject(any(HeadObjectRequest.class))).thenAnswer(call -> {
            HeadObjectRequest request = call.getArgument(0);
            byte[] held = objects.get(request.key());
            if (held == null) {
                throw NoSuchKeyException.builder().message("no such key").build();
            }
            return HeadObjectResponse.builder().contentLength((long) held.length).build();
        });
        when(client.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(call -> {
            objects.remove(((DeleteObjectRequest) call.getArgument(0)).key());
            return DeleteObjectResponse.builder().build();
        });
        return client;
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
        assertTrue(refused.getMessage().contains("Reference ["), "the refusal must carry a reference: "
                + refused.getMessage());
    }

    @Test
    public void aProviderSelectedWithoutADelegatorStillReadsThePropertyFileAlone() throws Exception {
        Delegator delegator = seamDelegator("default", null, Map.of(PROPERTY_S3_KEY_PREFIX, "tenants/acme"));
        ContentStore fromThePropertyFile = storeConfiguredAs(PROVIDER_S3);

        assertTrue(fromThePropertyFile instanceof S3ContentStore, "the file-only path must keep selecting the"
                + " object-storage provider");
        assertEquals(BUCKET, bucketOf(fromThePropertyFile), "a delegator-aware construction must not have made"
                + " the file-only path depend on a delegator being present");

        // The same file-configured provider, resolved through a delegator that overrides one TUNABLE: the
        // layering is what OFBiz gives every other property, and the two scopes are held separately. The
        // deployment values are not layered this way, which the cases above assert; the key prefix is,
        // because it changes how the configured store is used rather than which store it is.
        ContentStore throughTheDelegator = ContentStoreFactory.getContentStore(delegator);

        assertEquals(BUCKET, bucketOf(throughTheDelegator), "the bucket must still come from the property file");
        assertNotSame(fromThePropertyFile, throughTheDelegator, "the two scopes must be resolved separately");
    }

    @Test
    public void anUnrecognisedValueIsRefusedRatherThanReadAsDatabaseStorage() {
        // The contract this asserts is a fail-fast one, and the reason is where the content ends up. A
        // value that is PRESENT was written by somebody who meant to select a backend, so reading it as
        // database storage means a deployment that asked for s3 and mistyped it starts, reports success,
        // and writes its durable content somewhere nobody asked for - which no later restart can put
        // right, because the content is already in the wrong place. Only an ABSENT selector defaults, and
        // the case below asserts that separately. "fs" and "local" are here because they are NOT aliases.
        for (String value : new String[] {"nonsense", "postgres", "file system", "s-3", "fs", "local", "S3Bucket"}) {
            GeneralException refusal = refusalOf(value);

            // The value is named as it was COMPARED - trimmed and lower-cased - because that is what was
            // measured against the accepted set, and a refusal quoting something other than what it judged
            // would be misleading about why it refused.
            assertTrue(refusal.getMessage().contains(value.trim().toLowerCase(Locale.ROOT)), "[" + value + "]"
                    + " must be refused by naming the value that was configured, was: " + refusal.getMessage());
        }
    }

    @Test
    public void anAbsentSelectorIsStillDatabaseStorageRatherThanARefusal() throws Exception {
        // The other half of the rule above, and the one that keeps every unconfigured deployment working:
        // nothing configured, or a declared blank value, is database storage exactly as it always was.
        assertNull(storeConfiguredAs(""), "an unset selector must be read as database storage");
        assertNull(storeConfiguredAs("   "), "a blank selector must be read as database storage");
    }

    @Test
    public void anUnrecognisedValueNamesItselfItsPropertyAndTheAcceptedSetInTheRefusal() {
        GeneralException refusal = refusalOf("s-3");
        String reported = refusal.getMessage();

        // A refusal an operator cannot act on is barely better than a silent fallback, so all three are
        // asserted: what was configured, where it was configured, and what would have been accepted.
        assertTrue(reported.contains("s-3"), "the refusal must name the configured value: " + reported);
        assertTrue(reported.contains(PROPERTY_PROVIDER), "the refusal must name the property: " + reported);
        for (String accepted : new String[] {PROVIDER_DATABASE, PROVIDER_FILESYSTEM, PROVIDER_S3}) {
            assertTrue(reported.contains(accepted), "the refusal must name [" + accepted + "] as an accepted"
                    + " value: " + reported);
        }
    }

    @Test
    public void aRefusedValueNeverPoisonsTheNextResolution() throws Exception {
        refusalOf("nonsense");

        // A refusal installs nothing, so the next value configured is resolved entirely on its own terms.
        // Without this the fail-fast could have been implemented by caching the failure, which would turn
        // one mistyped value into a permanently unusable factory even after the value was corrected.
        assertTrue(storeConfiguredAs(PROVIDER_S3) instanceof S3ContentStore, "a valid value must still be"
                + " honoured after a refused one");
        assertNull(storeConfiguredAs(PROVIDER_DATABASE), "database storage must still be selectable after a"
                + " refused value");
    }

    @Test
    public void aValueChangedUnderARunningInstanceNeverReplacesTheProviderInService() throws Exception {
        // The finding this proves: replacing the resolution when the configuration changes meant closing the
        // provider it displaced, and a provider owns the client, the connection pool and every stream
        // openStream has already handed out. Nothing here can know the last of those has been released, so
        // an in-flight read - including one whose stream a response is still being written from - would fail
        // against a closed client. The resolution is therefore established once and kept: a change is
        // reported and takes effect on restart, which is what every other change of storage backend needs.
        ContentStore established = storeConfiguredAs(PROVIDER_FILESYSTEM);
        assertTrue(established instanceof FileSystemContentStore, "the filesystem provider must be selected"
                + " first");

        configureProvider(PROVIDER_S3);

        assertSame(established, ContentStoreFactory.getContentStore(), "a provider already in service must"
                + " never be replaced underneath the requests holding it");
        // Asked twice, because the report is emitted once per change and the second call must still answer
        // with the provider in service rather than take a different path.
        assertSame(established, ContentStoreFactory.getContentStore(), "the established provider must keep"
                + " answering however many times it is asked for");

        // ... and the change is honoured by the next process, which is what clearing the cache stands in for.
        ContentStoreFactory.clearCache();

        assertTrue(ContentStoreFactory.getContentStore() instanceof S3ContentStore, "the changed value must be"
                + " what a freshly started instance resolves");
    }

    @Test
    public void theMetadataDenylistIsTheSameOneTheContainerEntryPointRefuses() {
        // The entry point refuses five hosts; this provider is reachable without it - a hand-maintained
        // content.properties, or a non-container deployment - so the weaker of the two lists would be the
        // one that actually decided. metadata.google.internal was the entry point's fifth entry and was
        // missing here, which meant exactly one supported route to the credentials of a GCE instance.
        for (String metadata : new String[] {"http://metadata.google.internal/computeMetadata/v1/",
                "https://METADATA.GOOGLE.INTERNAL/", "https://[FD00:EC2::254]/latest/meta-data/"}) {
            GeneralException refusal = refusalOfEndpoint(metadata);

            assertTrue(refusal.getMessage().contains("instance metadata"), "[" + metadata + "] must be refused"
                    + " for naming an instance metadata address, was: " + refusal.getMessage());
        }
    }

    @Test
    public void aPlaintextEndpointIsRefusedUnlessItIsOnThisHostOrExplicitlyPermitted() throws Exception {
        // Every object written carries the store credential and every object read may be content that is
        // not public, so http exposes both to anything on the network path. A warning was no defence: the
        // credentials travelled in clear text either way. Three cases, and the middle one is the point.
        installOfflineObjectStoreConfiguration();
        // Asserted rather than assumed: this case only means anything while the permission is off, and the
        // permission is a JVM-wide in-memory value any earlier test could have turned on. Reading it back
        // here is what makes the refusal below evidence about the provider instead of evidence about which
        // test ran first.
        assertEquals("false", UtilProperties.getPropertyValue(RESOURCE, PROPERTY_S3_INSECURE_ENDPOINT),
                "every configuration this suite installs states the strict posture explicitly, so a plaintext"
                        + " endpoint must be refused on its own account here");
        configureEndpoint("http://" + UNREACHED_HOST + ":9000");
        GeneralException refusal = refusalOf(PROVIDER_S3);
        assertTrue(refusal.getMessage().contains(PROPERTY_S3_INSECURE_ENDPOINT), "the refusal must name the"
                + " setting that would permit it: " + refusal.getMessage());

        // A store on this host: the traffic never reaches a network, which is the local MinIO a developer
        // runs beside OFBiz and the reason the zero-configuration development flow needs no setting at all.
        installOfflineObjectStoreConfiguration();
        configureEndpoint("http://127.0.0.1:9000");
        assertTrue(storeConfiguredAs(PROVIDER_S3) instanceof S3ContentStore, "a plaintext endpoint on this host"
                + " must be accepted");
        installOfflineObjectStoreConfiguration();
        configureEndpoint("http://localhost:9000");
        assertTrue(storeConfiguredAs(PROVIDER_S3) instanceof S3ContentStore, "localhost must be recognised as"
                + " this host");

        // Or explicitly permitted, which is what a container renders from a development OFBIZ_PROFILE.
        installOfflineObjectStoreConfiguration();
        configureEndpoint("http://" + UNREACHED_HOST + ":9000");
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_INSECURE_ENDPOINT, "true");
        assertTrue(storeConfiguredAs(PROVIDER_S3) instanceof S3ContentStore, "an explicitly permitted plaintext"
                + " endpoint must be accepted");
    }

    @Test
    public void anUnusableBucketNameIsRefusedWithTheSameRulesTheEntryPointApplies() {
        // The store reports a malformed bucket name on the FIRST REQUEST, by which time a page is already
        // trying to render content to a user. The entry point refuses these shapes before the JVM starts;
        // this provider is reachable without it, so it refuses them too, and by the same rules - otherwise
        // which rules apply would depend on how the value happened to arrive.
        for (String bucket : new String[] {"ab", "UPPERCASE", "under_score", "-leading", "trailing-",
                "double..dot", "dot-.hyphen", "hyphen.-dot", "192.168.0.1"}) {
            installOfflineObjectStoreConfiguration();
            UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_BUCKET, bucket);
            GeneralException refusal = refusalOf(PROVIDER_S3);

            assertTrue(refusal.getMessage().contains(PROPERTY_S3_BUCKET), "[" + bucket + "] must be refused by"
                    + " the name of the property that carries it, was: " + refusal.getMessage());
        }

        // And a usable one is still accepted, so the case above cannot be satisfied by refusing everything.
        installOfflineObjectStoreConfiguration();
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_BUCKET, "ofbiz.content-store1");
        assertDoesNotThrow(() -> storeConfiguredAs(PROVIDER_S3), "a usable bucket name must be accepted");
    }

    @Test
    public void anUnusableRegionIdentifierIsRefusedWithTheSameRulesTheEntryPointApplies() {
        for (String region : new String[] {"E", "US-EAST-1", "eu_west_2", "-eu-west-2", "eu-west-2-",
                "https://s3.eu-west-2.amazonaws.com"}) {
            installOfflineObjectStoreConfiguration();
            UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_REGION, region);
            GeneralException refusal = refusalOf(PROVIDER_S3);

            assertTrue(refusal.getMessage().contains(PROPERTY_S3_REGION), "[" + region + "] must be refused by"
                    + " the name of the property that carries it, was: " + refusal.getMessage());
        }

        installOfflineObjectStoreConfiguration();
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_REGION, "cn-north-1");
        assertDoesNotThrow(() -> storeConfiguredAs(PROVIDER_S3), "a usable region identifier must be accepted");
    }

    @Test
    public void aNonBooleanAddressingStyleIsRefusedRatherThanReadAsFalse() {
        // Path-style addressing decides the shape of every request URL, and most S3-compatible stores serve
        // nothing without it, so "anything that is not the word true" silently meaning false answered a
        // configuration mistake with a choice about whether content could be read at all.
        for (String pathStyle : new String[] {"yes", "1", "TRUE!", "on"}) {
            installOfflineObjectStoreConfiguration();
            UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_PATH_STYLE, pathStyle);
            GeneralException refusal = refusalOf(PROVIDER_S3);

            assertTrue(refusal.getMessage().contains(PROPERTY_S3_PATH_STYLE), "[" + pathStyle + "] must be"
                    + " refused by the name of the property that carries it, was: " + refusal.getMessage());
        }

        // Blank is still the committed default rather than a refusal, so an unconfigured value is not one.
        installOfflineObjectStoreConfiguration();
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_PATH_STYLE, "");
        assertDoesNotThrow(() -> storeConfiguredAs(PROVIDER_S3), "a blank addressing style must mean false");
        for (String pathStyle : new String[] {"true", "FALSE", " true "}) {
            installOfflineObjectStoreConfiguration();
            UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_PATH_STYLE, pathStyle);
            assertDoesNotThrow(() -> storeConfiguredAs(PROVIDER_S3), "[" + pathStyle + "] must be accepted");
        }
    }

    @Test
    public void anExistenceProbeCostsNoTransferAndIsReleasedByAborting() throws Exception {
        // The one byte the probe asks for is never read, and the response is aborted rather than closed, so
        // the connection is dropped instead of the remainder of the object being drained off the wire. That
        // is what makes a GET as cheap as the HEAD it replaced for content of any size.
        S3Client client = mock(S3Client.class);
        AtomicBoolean aborted = new AtomicBoolean(false);
        AtomicInteger delivered = new AtomicInteger(0);
        // AbortableInputStream is what the SDK wraps a real response body in, and it routes abort() to the
        // action it is given, so recording that action is exactly what tells an abort from a close.
        InputStream counted = new FilterInputStream(new ByteArrayInputStream(PAYLOAD)) {
            @Override
            public int read() throws IOException {
                int octet = super.read();
                if (octet >= 0) {
                    delivered.incrementAndGet();
                }
                return octet;
            }

            @Override
            public int read(byte[] into, int offset, int length) throws IOException {
                int count = super.read(into, offset, length);
                if (count > 0) {
                    delivered.addAndGet(count);
                }
                return count;
            }
        };
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength((long) PAYLOAD.length).build(),
                AbortableInputStream.create(counted, () -> aborted.set(true))));
        ContentStore store = new S3ContentStore(client, BUCKET);

        assertTrue(store.exists(KEY), "an object the store holds must be reported as present");

        assertTrue(aborted.get(), "the existence probe must abort its response rather than drain it");
        assertEquals(0, delivered.get(), "the existence probe must read none of the byte it asked for");
    }

    @Test
    public void anEmptyObjectExistsRatherThanBeingReportedAbsentOrFailing() throws Exception {
        // A store answers a range that lies past the end of an object with InvalidRange, and a zero-length
        // object is shorter than the first byte. put accepts a zero-length array, so an empty object is
        // legitimate stored content: the one error code that means "there, and empty" must not be read as
        // absence and must not be read as a store failure either.
        S3Client client = mock(S3Client.class);
        when(client.getObject(any(GetObjectRequest.class)))
                .thenThrow(errorCoded("InvalidRange", HTTP_RANGE_NOT_SATISFIABLE));
        ContentStore store = new S3ContentStore(client, BUCKET);

        assertTrue(store.exists(KEY), "an empty object must be reported as present");
    }

    @Test
    public void aFailureIsLoggedAsSanitisedFieldsRatherThanAsTheStoresOwnDiagnostic() throws Exception {
        String storeMessage = "AccessDenied: the bucket policy forbids arn:aws:iam::1234:role/private";
        S3Exception failure = (S3Exception) S3Exception.builder().statusCode(HTTP_SERVER_ERROR)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied").build())
                .requestId("REQ-1234").message(storeMessage).build();
        S3Client client = mock(S3Client.class);
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(failure);
        ContentStore store = new S3ContentStore(client, BUCKET);

        // Stubbing the logger is the only way to see what is written, and it is what proves the report
        // takes the sanitising path rather than the throwable-and-stack-trace overload, which writes the
        // store's own message into the log whatever the sanitised text says.
        try (MockedStatic<Debug> logging = mockStatic(Debug.class)) {
            assertThrows(IOException.class, () -> store.get(KEY));

            // The fields that identify the failure, and the type, so an operator can act on it.
            logging.verify(() -> Debug.logError(contains("AccessDenied"), anyString()));
            logging.verify(() -> Debug.logError(contains("REQ-1234"), anyString()));
            logging.verify(() -> Debug.logError(contains("S3Exception"), anyString()));
            // And never the throwable itself, on any level: that overload is what bypasses redaction.
            logging.verify(() -> Debug.logError(any(Throwable.class), anyString(), anyString()), never());
            logging.verify(() -> Debug.logVerbose(any(Throwable.class), anyString(), anyString()), never());
        }
    }

    @Test
    public void anUnusableKeyPrefixIsRefusedBeforeAnySdkClientIsBuilt() {
        // The finding this proves: the key prefix used to be validated AFTER the client had been built and
        // assigned, so a rejected prefix left an SDK client - and, on the static-credential branch, the
        // credential provider it holds - unreachable and unclosed. ContentStoreFactory does not cache a
        // construction that failed, so every read retried it and leaked another connection pool and its
        // threads: one mistyped property became an unbounded leak. Asserted by mocking the static builder
        // accessor and requiring that it is never reached, which is the only observation that distinguishes
        // "validated first" from "validated after the client exists".
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_BUCKET, BUCKET);
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_REGION, REGION);
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_ACCESS_KEY_ID, ACCESS_KEY_ID);
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_SECRET_ACCESS_KEY, SECRET_ACCESS_KEY);
        for (String unusable : new String[] {"tenants/../acme", "tenants/./acme", "tenants//acme"}) {
            UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_KEY_PREFIX, unusable);
            try (MockedStatic<S3Client> sdk = mockStatic(S3Client.class)) {
                GeneralException refused = assertThrows(GeneralException.class, () -> new S3ContentStore(
                        (Delegator) null), "[" + unusable + "] must be refused");
                assertTrue(refused.getMessage().contains(PROPERTY_S3_KEY_PREFIX), "the refusal must name the"
                        + " property: " + refused.getMessage());
                sdk.verify(S3Client::builder, never());
            }
        }
    }

    @Test
    public void theLocalFallbackServesExistingContentUnlessTheStrictPostureIsAskedForExactly() {
        // The committed default lets the local copy answer a store miss, so selecting a provider never stops a
        // deployment serving content that predates the selection - the shipped content, and every file uploaded
        // before the provider was switched on. Content written afterwards is published as it is written.
        assertTrue(ContentStoreFactory.localFallbackEnabled(null), "the committed default must keep serving"
                + " content the store does not hold yet");

        // The strict posture is the one an operator asks for by name, once existing content has been migrated.
        for (String off : new String[] {"false", "FALSE", "  False  "}) {
            UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_LOCAL_FALLBACK, off);
            assertFalse(ContentStoreFactory.localFallbackEnabled(null), "[" + off + "] must select the strict,"
                    + " store-is-the-only-authority posture");
        }

        for (String on : new String[] {"true", "TRUE", "  True  "}) {
            UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_LOCAL_FALLBACK, on);
            assertTrue(ContentStoreFactory.localFallbackEnabled(null), "[" + on + "] must let the local copy"
                    + " answer");
        }

        // Anything that is neither spelling is reported once and the committed default applies, exactly as
        // every other setting of this package treats an unusable value: a typo must not decide whether a fleet
        // serves its own shipped content.
        for (String unusable : new String[] {"", "yes", "no", "1", "Y", "on"}) {
            UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_LOCAL_FALLBACK, unusable);
            assertTrue(ContentStoreFactory.localFallbackEnabled(null), "[" + unusable + "] must leave the"
                    + " committed default in force");
        }
    }

    @Test
    public void aConfigurationChangeBehindTheProviderNameIsReportedAndNotAppliedInPlace() throws Exception {
        ContentStore first = storeConfiguredAs(PROVIDER_S3);
        assertSame(first, ContentStoreFactory.getContentStore(), "one configuration must be resolved once");

        // A changed bucket and a changed key prefix are detected - the whole content.store.* configuration is
        // digested, not just the selector - but detection is what they get. Acting on either would mean
        // closing the client this provider owns while requests are reading through it, so both are reported
        // and the provider in service is returned unchanged until the instance restarts.
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_BUCKET, "test-bucket-two");
        assertSame(first, ContentStoreFactory.getContentStore(), "a changed bucket must not replace a provider"
                + " in service");

        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_KEY_PREFIX, "tenants/acme");
        assertSame(first, ContentStoreFactory.getContentStore(), "a changed key prefix must not replace a"
                + " provider in service");
        assertEquals(BUCKET, bucketOf(first), "the provider in service must still hold the bucket it was built"
                + " from");
    }

    @Test
    public void contentTheProviderDoesNotHoldIsRefusedWhileALocalCopyExistsInTheStrictPosture() throws Exception {
        // The three cases the seam has to tell apart, asserted directly because only one of them is a
        // refusal and getting that wrong in either direction is a data-integrity bug.
        configureProvider(PROVIDER_S3);
        Delegator delegator = seamDelegator("default", null);

        // Nothing in the store and nothing on disk: the content does not exist, which is not a fallback
        // decision. The caller reports absence itself, so nothing may be thrown here.
        assertDoesNotThrow(() -> refuseUnlessLocalCopyMayAnswer(KEY, true, delegator),
                "content that exists nowhere is plain absence, not a refusal");

        // Nothing in the store but a local copy exists, with the committed default in force: the local copy
        // answers, because refusing it would stop a deployment serving content that predates the provider.
        assertDoesNotThrow(() -> refuseUnlessLocalCopyMayAnswer(KEY, false, delegator),
                "the committed default must let a local copy answer for content the store does not hold yet");

        // The strict posture an operator asks for once content has been migrated: refused, and the refusal has
        // to name the way out.
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_LOCAL_FALLBACK, "false");
        ContentStoreFactory.clearCache();
        GeneralException refused = assertThrows(GeneralException.class, () ->
                refuseUnlessLocalCopyMayAnswer(KEY, false, delegator));
        assertTrue(refused.getMessage().contains(PROPERTY_LOCAL_FALLBACK), "the refusal must name ["
                + PROPERTY_LOCAL_FALLBACK + "], because an operator cannot act on a refusal that does not say"
                + " what to do: [" + refused.getMessage() + "]");
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
    public void publicationIsRequiredOnlyWhereAProviderHoldsContentApartFromTheDeployment() throws Exception {
        // Database mode shares content the moment the row commits, and the filesystem provider's tree IS the
        // deployment's tree, so in both a local write has already put the content where a reader will look.
        // Only a store outside every instance has to be handed the bytes.
        assertFalse(ContentStoreFactory.publicationRequired(null), "database storage needs no publication");
        assertFalse(ContentStoreFactory.publicationRequired(storeConfiguredAs(PROVIDER_FILESYSTEM)),
                "a write into the deployment's own tree is already in the filesystem provider");
        assertTrue(ContentStoreFactory.publicationRequired(storeConfiguredAs(PROVIDER_S3)),
                "content written locally is invisible to the fleet until it is published to the object store");
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
    public void objectStoreDeploymentConfigurationInTheDatabaseNeverOverridesThePropertyFile() throws Exception {
        // The security contract of the storage configuration, asserted from the direction that matters. The
        // seven deployment values - the selector, the bucket, the region, the endpoint, the addressing
        // style and the two credentials - are what the container entry point validates and renders, so a
        // SystemProperty row able to override them would be a second control plane over WHERE this
        // deployment's content is written and WHICH principal writes it: a row could aim a production fleet
        // at another bucket, at a plaintext endpoint the entry point had refused, or at another identity,
        // in a change no start-up validation ever sees and with no atomicity across the seven.
        withoutObjectStoreConfigurationInThePropertyFile();
        Delegator delegator = seamDelegator("default", null, Map.of(
                PROPERTY_PROVIDER, PROVIDER_S3,
                PROPERTY_S3_BUCKET, DATABASE_BUCKET,
                PROPERTY_S3_REGION, REGION,
                PROPERTY_S3_ENDPOINT, ENDPOINT,
                PROPERTY_S3_ACCESS_KEY_ID, ACCESS_KEY_ID,
                PROPERTY_S3_SECRET_ACCESS_KEY, SECRET_ACCESS_KEY,
                PROPERTY_S3_PATH_STYLE, "true"));

        // The property file selects nothing, and the rows above are ignored, so this is database storage
        // through the delegator form exactly as it is through the file-only one.
        assertNull(ContentStoreFactory.getContentStore(delegator), "a SystemProperty row must not be able to"
                + " select the object store");
        assertNull(ContentStoreFactory.getContentStore(), "the property file alone must still mean database"
                + " storage, otherwise this case proves nothing");
    }

    @Test
    public void aBucketDeclaredOnlyInTheDatabaseNeverReachesAFileConfiguredProvider() throws Exception {
        installOfflineObjectStoreConfiguration();
        configureProvider(PROVIDER_S3);
        ContentStoreFactory.clearCache();

        // The provider is selected and configured by the property file; the row names another bucket. The
        // bucket the provider addresses has to be the file's, because that is the one the entry point
        // validated and rendered - a row that could move it would move where durable content is written.
        ContentStore throughTheDelegator =
                ContentStoreFactory.getContentStore(objectStoreDelegatorWithBucket(OTHER_BUCKET));

        assertEquals(BUCKET, bucketOf(throughTheDelegator), "a bucket declared only in the database must not"
                + " reach the provider");
    }
    /**
     * A provider that records the keys it was asked to remove and refuses everything else.
     *
     * <p>Used where the case is about the removal a rolled-back publish performs, so every other operation
     * throws rather than answering: a double that quietly answered a read would let a case pass while the
     * seam did something other than what it is being asserted to do.
     */
    private static final class RecordingContentStore implements ContentStore {

        /** The keys this store was asked to remove, in order. */
        private final List<String> deleted = new ArrayList<>();

        @Override
        public void put(String key, byte[] data) {
            throw new UnsupportedOperationException("this double only records removals");
        }

        @Override
        public void put(String key, InputStream content, long length) {
            throw new UnsupportedOperationException("this double only records removals");
        }

        @Override
        public byte[] get(String key) {
            throw new UnsupportedOperationException("this double only records removals");
        }

        @Override
        public ContentStream openStream(String key) {
            throw new UnsupportedOperationException("this double only records removals");
        }

        @Override
        public boolean exists(String key) {
            throw new UnsupportedOperationException("this double only records removals");
        }

        @Override
        public void delete(String key) {
            deleted.add(key);
        }

        @Override
        public boolean holdsContentOffInstance() {
            return true;
        }
    }

    /**
     * Content written through the resolution seam reaches the store when, and only when, the transaction that
     * resolved it commits.
     *
     * <p>The two halves are one test because they are one decision, and getting either of them wrong is a
     * different kind of corruption. Publishing at write time - which is what this did - put bytes in the store
     * that the deployment's own validation had not yet seen, and made a rollback responsible for removing them
     * again: that removal deleted the one stable key the content lives under, taking the PREVIOUS version with it,
     * which the failed transaction never owned. Publishing at commit cannot do either: nothing reaches the store
     * until the row that describes it is committed, and a transaction that does not commit leaves the store
     * exactly as it found it, including whatever was already under that key.
     *
     * @param home a per-test temporary directory standing in for {@code ofbiz.home}
     * @throws Exception if the seam cannot be exercised
     */
    @Test
    public void contentReachesTheStoreOnCommitAndNeverOnRollback(@TempDir Path home) throws Exception {
        System.setProperty("ofbiz.home", home.toString());
        Map<String, byte[]> objects = new ConcurrentHashMap<>();
        byte[] previous = "the version that was already published".getBytes(StandardCharsets.UTF_8);
        objects.put("runtime/uploads/target.bin", previous);
        installObjectStoreClient(inMemoryObjectStore(objects));
        Path target = Files.createDirectories(home.resolve("runtime/uploads")).resolve("target.bin");
        Files.write(target, "the copy this instance already had".getBytes(StandardCharsets.UTF_8));

        // ROLLBACK first, so that what the commit case proves cannot be an artefact of the order.
        Synchronization rolledBack = writeThroughFor(() -> {
            File resolved = DataResourceWorker.getContentFile("OFBIZ_FILE", "/runtime/uploads/target.bin", null);
            Files.write(resolved.toPath(), "bytes a doomed transaction wrote".getBytes(StandardCharsets.UTF_8));
        });
        assertNotNull(rolledBack, "a provider-backed resolution inside an active transaction must register a"
                + " write-through, or nothing would ever be published");
        rolledBack.afterCompletion(Status.STATUS_ROLLEDBACK);
        assertArrayEquals(previous, objects.get("runtime/uploads/target.bin"), "a transaction that did not commit"
                + " must leave the store exactly as it was, including the version that was already published:"
                + " deleting the stable key would destroy content the failed transaction never owned");

        // COMMIT, with the bytes that are on disk at that moment.
        byte[] written = "the bytes on disk when the transaction committed".getBytes(StandardCharsets.UTF_8);
        Synchronization committed = writeThroughFor(() -> {
            File resolved = DataResourceWorker.getContentFile("OFBIZ_FILE", "/runtime/uploads/target.bin", null);
            Files.write(resolved.toPath(), written);
        });
        assertNotNull(committed, "the second resolution must register too");
        committed.beforeCompletion();
        committed.afterCompletion(Status.STATUS_COMMITTED);
        assertArrayEquals(written, objects.get("runtime/uploads/target.bin"), "a committed transaction must"
                + " publish what was on disk when it committed, under the content's own path key");
    }

    /**
     * A resolution that only reads publishes nothing, which is what lets one seam serve both directions.
     *
     * <p>{@code getContentFile} resolves a read location as often as a write target - {@code ContentWorker} and
     * the report templates dereference the {@code File} - so a seam that published whatever it resolved would
     * re-upload content on every read, and would overwrite a newer object in the store with this instance's older
     * local copy. Comparing the location against what it held when it was resolved is what prevents both.
     *
     * @param home a per-test temporary directory standing in for {@code ofbiz.home}
     * @throws Exception if the seam cannot be exercised
     */
    @Test
    public void aResolutionThatOnlyReadsPublishesNothing(@TempDir Path home) throws Exception {
        System.setProperty("ofbiz.home", home.toString());
        Map<String, byte[]> objects = new ConcurrentHashMap<>();
        byte[] newerInTheStore = "the newer copy another instance published".getBytes(StandardCharsets.UTF_8);
        objects.put("runtime/uploads/read.bin", newerInTheStore);
        installObjectStoreClient(inMemoryObjectStore(objects));
        Path local = Files.createDirectories(home.resolve("runtime/uploads")).resolve("read.bin");
        Files.write(local, "this instance's older local copy".getBytes(StandardCharsets.UTF_8));

        Synchronization readOnly = writeThroughFor(() ->
                assertNotNull(DataResourceWorker.getContentFile("OFBIZ_FILE", "/runtime/uploads/read.bin", null),
                        "the frozen contract is a non-null File"));
        assertNotNull(readOnly, "the registration itself is not conditional on writing: what is published is");
        readOnly.beforeCompletion();
        readOnly.afterCompletion(Status.STATUS_COMMITTED);

        assertArrayEquals(newerInTheStore, objects.get("runtime/uploads/read.bin"), "a resolution that wrote"
                + " nothing must publish nothing: republishing the local copy would overwrite a newer object with"
                + " an older one");
    }

    /**
     * Content a committed transaction removed is removed from the store as well.
     *
     * <p>This is the production caller of {@link ContentStore#delete(String)}, and the reason the store cannot go
     * on serving content the deployment has discarded: without it, an object outlives the file it mirrors and
     * every instance except the one that performed the removal keeps serving it.
     *
     * @param home a per-test temporary directory standing in for {@code ofbiz.home}
     * @throws Exception if the seam cannot be exercised
     */
    @Test
    public void contentRemovedByACommittedTransactionIsRemovedFromTheStore(@TempDir Path home) throws Exception {
        System.setProperty("ofbiz.home", home.toString());
        Map<String, byte[]> objects = new ConcurrentHashMap<>();
        objects.put("runtime/uploads/discarded.bin", PAYLOAD);
        installObjectStoreClient(inMemoryObjectStore(objects));
        Path local = Files.createDirectories(home.resolve("runtime/uploads")).resolve("discarded.bin");
        Files.write(local, PAYLOAD);

        Synchronization removal = writeThroughFor(() -> {
            File resolved = DataResourceWorker.getContentFile("OFBIZ_FILE", "/runtime/uploads/discarded.bin", null);
            Files.delete(resolved.toPath());
        });
        assertNotNull(removal, "the resolution must register, or a removal could never be mirrored");
        removal.beforeCompletion();
        removal.afterCompletion(Status.STATUS_COMMITTED);

        assertFalse(objects.containsKey("runtime/uploads/discarded.bin"), "content removed by a committed"
                + " transaction must be removed from the store, or the store keeps serving what the deployment"
                + " discarded");
    }

    /**
     * A resolution in a transaction that is not active registers nothing, rather than believing it has.
     *
     * <p>{@code TransactionUtil.registerSynchronization} silently does nothing when no transaction is active, so
     * code that registered and then relied on having done so would have had its work dropped without a word. Both
     * states are asserted: no transaction at all, which is what a read outside a service looks like, and a
     * transaction already marked for rollback, which is doomed and must produce nothing.
     *
     * @param home a per-test temporary directory standing in for {@code ofbiz.home}
     * @throws Exception if the seam cannot be exercised
     */
    @Test
    public void aResolutionOutsideAnActiveTransactionRegistersNothing(@TempDir Path home) throws Exception {
        System.setProperty("ofbiz.home", home.toString());
        Map<String, byte[]> objects = new ConcurrentHashMap<>();
        installObjectStoreClient(inMemoryObjectStore(objects));
        Path local = Files.createDirectories(home.resolve("runtime/uploads")).resolve("unbound.bin");
        Files.write(local, PAYLOAD);

        for (int status : new int[] {Status.STATUS_NO_TRANSACTION, Status.STATUS_MARKED_ROLLBACK,
                Status.STATUS_ROLLING_BACK, Status.STATUS_UNKNOWN}) {
            List<Synchronization> registered = new ArrayList<>();
            try (MockedStatic<TransactionUtil> transactions = mockStatic(TransactionUtil.class,
                    withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
                transactions.when(TransactionUtil::getStatus).thenReturn(status);
                transactions.when(() -> TransactionUtil.registerSynchronization(any(Synchronization.class)))
                        .thenAnswer(call -> registered.add(call.getArgument(0)));
                File resolved = DataResourceWorker.getContentFile("OFBIZ_FILE", "/runtime/uploads/unbound.bin",
                        null);
                assertNotNull(resolved, "the frozen contract still has to be honoured in every transaction state");
                Files.write(resolved.toPath(), "written with nothing to bind to".getBytes(StandardCharsets.UTF_8));
            }
            assertEquals(List.of(), registered, "status [" + status + "] is not active, so nothing may be"
                    + " registered against it");
            assertEquals(Map.of(), objects, "status [" + status + "] must publish nothing at all");
        }
    }

    /**
     * Content too large to be read back in one piece rolls the transaction back rather than being committed.
     *
     * <p>The ceiling is the deployment's statement of how much content one whole read may hold, and it is applied
     * BEFORE the commit for a reason: content the store could hold but no instance could afterwards read whole
     * would only look published, and after the commit there is nothing left to do about it but complain. Refusing
     * before it means the row and the object stay in step.
     *
     * @param home a per-test temporary directory standing in for {@code ofbiz.home}
     * @throws Exception if the seam cannot be exercised
     */
    @Test
    public void contentTooLargeToPublishRollsTheTransactionBackBeforeItCommits(@TempDir Path home)
            throws Exception {
        System.setProperty("ofbiz.home", home.toString());
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_MAX_OBJECT_SIZE, "1024");
        Map<String, byte[]> objects = new ConcurrentHashMap<>();
        installObjectStoreClient(inMemoryObjectStore(objects));
        Path local = Files.createDirectories(home.resolve("runtime/uploads")).resolve("oversized.bin");
        Files.write(local, new byte[8]);

        List<String> rolledBack = new ArrayList<>();
        List<Synchronization> registered = new ArrayList<>();
        try (MockedStatic<TransactionUtil> transactions = mockStatic(TransactionUtil.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
            transactions.when(TransactionUtil::getStatus).thenReturn(Status.STATUS_ACTIVE);
            transactions.when(() -> TransactionUtil.registerSynchronization(any(Synchronization.class)))
                    .thenAnswer(call -> registered.add(call.getArgument(0)));
            transactions.when(() -> TransactionUtil.setRollbackOnly(anyString(), any()))
                    .thenAnswer(call -> rolledBack.add(call.getArgument(0)));
            File resolved = DataResourceWorker.getContentFile("OFBIZ_FILE", "/runtime/uploads/oversized.bin", null);
            Files.write(resolved.toPath(), new byte[4096]);
            assertEquals(1, registered.size(), "the resolution must have registered");
            registered.get(0).beforeCompletion();
        }

        assertEquals(1, rolledBack.size(), "oversized content must roll the transaction back before it commits,"
                + " rather than committing a row for content no instance could read whole: " + rolledBack);
        assertTrue(rolledBack.get(0).contains(PROPERTY_MAX_OBJECT_SIZE), "the rollback must name the setting that"
                + " governs it: " + rolledBack.get(0));
        assertEquals(Map.of(), objects, "nothing may have been published");
    }

    /**
     * A location this instance holds no copy of is reconstructed from the store, so a caller that needs a
     * {@code File} still gets one.
     *
     * <p>The frozen contract of {@code getContentFile} is a non-null {@code File} that exists, and content
     * published by another instance leaves nothing on this one's disk - which is the state every instance except
     * the one that received the upload is in. A seam that only ever looked at local disk therefore failed on
     * every other instance.
     *
     * <p>It is reconstructed AT ITS OWN PATH rather than into a temporary file, and that is the whole point: the
     * caller may be about to write to it, and a temporary file would have taken that write with it. The parent
     * directories are created too, because the instance that received the upload created them and this one never
     * did.
     *
     * @param home a per-test temporary directory standing in for {@code ofbiz.home}
     * @throws Exception if the seam cannot be exercised
     */
    @Test
    public void aLocationThisInstanceHasNoCopyOfIsReconstructedFromTheStore(@TempDir Path home) throws Exception {
        System.setProperty("ofbiz.home", home.toString());
        byte[] published = "published by another instance".getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> objects = new ConcurrentHashMap<>();
        objects.put("runtime/uploads/00002/reconstructed.bin", published);
        installObjectStoreClient(inMemoryObjectStore(objects));
        Path expected = home.resolve("runtime/uploads/00002/reconstructed.bin");
        assertFalse(Files.exists(expected.getParent()), "the directory must be missing, or this asserts nothing");

        File resolved = DataResourceWorker.getContentFile("OFBIZ_FILE", "/runtime/uploads/00002/reconstructed.bin",
                null);

        assertNotNull(resolved, "the frozen contract is a non-null File");
        assertTrue(resolved.exists(), "the File a caller is handed has to exist: every caller either reads it or"
                + " writes to it, and both need a real path");
        assertEquals(expected.toFile().getCanonicalPath(), resolved.getCanonicalPath(), "the content must be"
                + " reconstructed at the location the row names, not at a temporary path a write would be lost to");
        assertArrayEquals(published, Files.readAllBytes(resolved.toPath()), "the reconstruction must be what the"
                + " store holds");
        // And a write to it lands where the write-through will find it, which is what a temporary file could not
        // have offered.
        Files.write(resolved.toPath(), PAYLOAD);
        assertArrayEquals(PAYLOAD, Files.readAllBytes(expected), "a write through the reconstructed File must"
                + " reach the location the row names");
    }

    /**
     * A {@code CONTEXT_FILE} is neither published to the store nor read through it.
     *
     * <p>Its content is a webapp's own static files, which ship inside the container image: every instance already
     * has an identical copy, so there is no durable local state to externalise - and making them provider-backed
     * would instead mean every instance's static files had to be uploaded before it could serve them. The render
     * path has always read them locally, so a write path that gave them provider keys would have had the same
     * resource written to one place and read from another, which is the inconsistency this asserts against.
     *
     * @param home a per-test temporary directory standing in for {@code ofbiz.home}
     * @throws Exception if the seam cannot be exercised
     */
    @Test
    public void aContextFileIsNeitherPublishedNorReadThroughTheStore(@TempDir Path home) throws Exception {
        System.setProperty("ofbiz.home", home.toString());
        S3Client untouched = mock(S3Client.class);
        installObjectStoreClient(untouched);
        Path context = Files.createDirectories(home.resolve("webapp"));
        Files.write(context.resolve("static.bin"), PAYLOAD);

        List<Synchronization> registered = new ArrayList<>();
        try (MockedStatic<TransactionUtil> transactions = mockStatic(TransactionUtil.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
            transactions.when(TransactionUtil::getStatus).thenReturn(Status.STATUS_ACTIVE);
            transactions.when(() -> TransactionUtil.registerSynchronization(any(Synchronization.class)))
                    .thenAnswer(call -> registered.add(call.getArgument(0)));
            File resolved = DataResourceWorker.getContentFile("CONTEXT_FILE", "/static.bin", context.toString());
            assertNotNull(resolved, "a context file still resolves to the file it always resolved to");
            Files.write(resolved.toPath(), "rewritten in place".getBytes(StandardCharsets.UTF_8));
        }

        assertEquals(List.of(), registered, "a context file must register no write-through: it is not"
                + " provider-backed in either direction");
        verifyNoInteractions(untouched);
        // And the read direction agrees, which is the half that was already local-only.
        assertEquals("rewritten in place", renderedThroughSeam("CONTEXT_FILE", "/static.bin", context.toString(),
                seamDelegator("default", null), "10000"), "a context file must be rendered from the local copy");
        verifyNoInteractions(untouched);
    }

    /**
     * A file written into a resolved upload directory is published, which is the only way an upload can be.
     *
     * <p>The service that writes an uploaded file resolves its own {@code File} from the {@code objectInfo} it was
     * given and never calls this class, so there is no file resolution to seam. What every upload does pass
     * through is the upload DIRECTORY: the script asks for it, composes {@code objectInfo} from it, and hands the
     * write to that service. So the directory is what the write-through watches, and whatever file has appeared in
     * it by the time the transaction commits is what gets published.
     *
     * @param home a per-test temporary directory standing in for {@code ofbiz.home}
     * @throws Exception if the seam cannot be exercised
     */
    @Test
    public void aFileWrittenIntoAResolvedUploadDirectoryIsPublished(@TempDir Path home) throws Exception {
        System.setProperty("ofbiz.home", home.toString());
        Map<String, byte[]> objects = new ConcurrentHashMap<>();
        installObjectStoreClient(inMemoryObjectStore(objects));
        // The upload root, which the deployment's own installation creates: getDataResourceContentUploadPath
        // creates only the numbered subdirectory below it, with a single-level mkdir.
        Files.createDirectories(home.resolve("runtime/uploads"));
        byte[] uploaded = "the bytes an upload wrote".getBytes(StandardCharsets.UTF_8);

        Synchronization upload = writeThroughFor(() -> {
            String directory = DataResourceWorker.getDataResourceContentUploadPath(seamDelegator("default", null),
                    true);
            assertNotNull(directory, "the upload path is what an objectInfo is composed from");
            Files.write(Paths.get(directory).resolve("10000.png"), uploaded);
        });

        assertNotNull(upload, "resolving an upload directory inside an active transaction must register a"
                + " write-through, or an upload could never be published");
        upload.beforeCompletion();
        upload.afterCompletion(Status.STATUS_COMMITTED);

        assertEquals(1, objects.size(), "exactly the uploaded file must have been published: " + objects.keySet());
        String key = objects.keySet().iterator().next();
        assertTrue(key.endsWith("/10000.png"), "the key must be the upload's own path: " + key);
        assertArrayEquals(uploaded, objects.get(key), "the published bytes must be the bytes the upload wrote");
    }

    /**
     * The bytes the upload validation left behind are the bytes published, not the bytes the caller submitted.
     *
     * <p>This is the property the whole write path was arranged around, and the one that is easiest to lose.
     * {@code createFileMethod} writes the submitted array to a TEMPORARY file, hands that file to
     * {@code SecuredUpload}, which decodes the image and REWRITES it - stripping every metadata chunk with it -
     * and only then copies the rewritten file onto the destination. So the destination holds post-validation
     * content and the submitted array does not. Publishing from the array would put content the deployment's own
     * sanitiser rejected into a store every instance reads, and it would do so silently, because the local copy
     * would still be the clean one. Publishing at commit, from the FILE, is what makes that impossible.
     *
     * <p>The fixture carries a marker inside a PNG {@code tEXt} chunk so that the assertion cannot be vacuous:
     * the submitted bytes and the published bytes are asserted to DIFFER, and the marker is asserted absent from
     * what reached the store. A test whose input survived sanitising unchanged would prove nothing.
     *
     * @param home a per-test temporary directory standing in for {@code ofbiz.home}
     * @throws Exception if the seam cannot be exercised
     */
    @Test
    public void theBytesTheUploadSanitiserLeftBehindAreTheOnesPublished(@TempDir Path home) throws Exception {
        System.setProperty("ofbiz.home", home.toString());
        Files.createDirectories(home.resolve("runtime/uploads"));
        RecordedObjectStore store = recordingObjectStoreClient();
        String marker = "BLITZY-METADATA-THAT-MUST-NOT-SURVIVE";
        byte[] submitted = pngCarryingMetadata(marker);
        assertTrue(new String(submitted, StandardCharsets.ISO_8859_1).contains(marker), "the fixture must"
                + " actually carry the marker, or this case cannot tell the two copies apart");
        Path[] written = new Path[1];

        Synchronization upload = writeThroughFor(() -> {
            String directory = DataResourceWorker.getDataResourceContentUploadPath(seamDelegator("default", null),
                    true);
            String relative = home.relativize(Paths.get(directory)).toString().replace(File.separatorChar, '/');
            written[0] = Paths.get(directory).resolve("10008.png");
            Map<String, Object> result = DataServices.createFileMethod(
                    dispatchContextFor(seamDelegator("default", null)),
                    UtilMisc.toMap("dataResourceTypeId", "OFBIZ_FILE_BIN", "objectInfo",
                            "/" + relative + "/10008.png", "dataResourceId", "10008", "binData",
                            ByteBuffer.wrap(submitted), "locale", Locale.ENGLISH));
            assertFalse(ServiceUtil.isError(result), "a valid image upload must be accepted: " + result);
        });

        assertNotNull(upload, "the upload directory resolution must register the publish");
        upload.beforeCompletion();
        upload.afterCompletion(Status.STATUS_COMMITTED);

        assertEquals(1, store.publishedKeys().size(), "exactly the uploaded file must be published: "
                + store.publishedKeys());
        String key = store.publishedKeys().iterator().next();
        byte[] published = store.contentOf(key);
        byte[] onDisk = Files.readAllBytes(written[0]);
        assertArrayEquals(onDisk, published, "what is published must be exactly what the validation left on"
                + " disk, because that file is the post-validation content and the submitted array is not");
        assertFalse(Arrays.equals(submitted, published), "the sanitiser rewrote the file, so publishing the"
                + " submitted array would be publishing content this deployment refused - if these are equal"
                + " the fixture no longer exercises the sanitiser and the case proves nothing");
        assertFalse(new String(published, StandardCharsets.ISO_8859_1).contains(marker), "the metadata the"
                + " sanitiser stripped must not reach the store");
    }

    /**
     * An upload of nothing is published rather than left where only one instance can see it.
     *
     * <p>Zero bytes is a legitimate outcome - a cleared attachment, an empty export - and it is the case a
     * publication decision made on "is there anything to send" gets wrong: the file EXISTS, the row that names
     * it commits, and every instance except this one would then find nothing behind it. What decides is whether
     * the location changed, not whether it holds anything, so an empty file is published exactly like any other
     * and reads back as the zero-length content the contract promises.
     *
     * @param home a per-test temporary directory standing in for {@code ofbiz.home}
     * @throws Exception if the seam cannot be exercised
     */
    @Test
    public void anEmptyUploadIsPublishedRatherThanLeftOnlyOnTheInstance(@TempDir Path home) throws Exception {
        System.setProperty("ofbiz.home", home.toString());
        Files.createDirectories(home.resolve("runtime/uploads"));
        RecordedObjectStore store = recordingObjectStoreClient();
        Path[] written = new Path[1];

        Synchronization upload = writeThroughFor(() -> {
            String directory = DataResourceWorker.getDataResourceContentUploadPath(seamDelegator("default", null),
                    true);
            written[0] = Files.createFile(Paths.get(directory).resolve("10009.bin"));
        });

        assertNotNull(upload, "the upload directory resolution must register the publish");
        upload.beforeCompletion();
        upload.afterCompletion(Status.STATUS_COMMITTED);

        assertEquals(1, store.publishedKeys().size(), "an empty upload must be published like any other: "
                + store.publishedKeys());
        assertArrayEquals(new byte[0], store.contentOf(store.publishedKeys().iterator().next()),
                "the published object must be the zero-length content the file holds");
        assertTrue(Files.exists(written[0]), "publishing must not disturb the file it published");
        assertTrue(store.removedKeys().isEmpty(), "an empty file is content, not an absence, so nothing may be"
                + " removed from the store for it: " + store.removedKeys());
    }

    /**
     * Removing only the metadata leaves the stored object exactly where filesystem mode leaves the file.
     *
     * <p>{@code removeDataResource} is declared {@code engine="entity-auto" invoke="delete"}: it deletes the row
     * and nothing else, so a deployment on local files keeps the file on disk afterwards. An object store has to
     * behave the same way, because AAP 0.7.1 requires every webapp and every service to behave identically
     * before and after this work - a provider that deleted the object on a row removal would make the object
     * store LOSE content that filesystem mode keeps, and no service asked it to.
     *
     * <p>What is mirrored is the removal of the CONTENT, which is the case
     * {@link #contentRemovedByACommittedTransactionIsRemovedFromTheStore} covers. The two together are the whole
     * of the delete policy: the store follows the file, and nothing else.
     *
     * @param home a per-test temporary directory standing in for {@code ofbiz.home}
     * @throws Exception if the seam cannot be exercised
     */
    @Test
    public void removingOnlyTheMetadataLeavesTheStoredObjectExactlyAsFilesystemModeDoes(@TempDir Path home)
            throws Exception {
        System.setProperty("ofbiz.home", home.toString());
        RecordedObjectStore store = recordingObjectStoreClient();
        Path local = Files.createDirectories(home.resolve("runtime/uploads")).resolve("kept.bin");
        Files.writeString(local, "the version this deployment held before the write below");

        // The store is seeded by a committed write-through rather than by planting an object in the fixture, so
        // what it holds got there the way a deployment's content really does: resolved inside a transaction,
        // written, and published when that transaction committed.
        Synchronization seeding = writeThroughFor(() -> {
            assertNotNull(DataResourceWorker.getContentFile("OFBIZ_FILE", "/runtime/uploads/kept.bin", null),
                    "the resolution must answer with the local file");
            Files.write(local, PAYLOAD);
        });
        assertNotNull(seeding, "the resolution must register");
        seeding.beforeCompletion();
        seeding.afterCompletion(Status.STATUS_COMMITTED);
        assertArrayEquals(PAYLOAD, store.contentOf("runtime/uploads/kept.bin"), "the store must hold the"
                + " content before the removal under test, or the case cannot show it survives");

        // The row is deleted; the file is not. That is exactly what the entity-auto service does.
        Synchronization metadataOnly = writeThroughFor(() ->
                DataResourceWorker.getContentFile("OFBIZ_FILE", "/runtime/uploads/kept.bin", null));
        assertNotNull(metadataOnly, "the resolution must register");
        metadataOnly.beforeCompletion();
        metadataOnly.afterCompletion(Status.STATUS_COMMITTED);

        assertTrue(store.removedKeys().isEmpty(), "a metadata-only removal must remove nothing from the store,"
                + " because filesystem mode keeps the file: " + store.removedKeys());
        assertArrayEquals(PAYLOAD, store.contentOf("runtime/uploads/kept.bin"), "the object must survive a"
                + " removal of the row that named it, exactly as the local file does");
        assertTrue(Files.exists(local), "the local file must survive too, which is what makes the two agree");
    }

    /**
     * No completion status other than {@code STATUS_COMMITTED} touches the store, whatever the transaction
     * manager reports.
     *
     * <p>{@code afterCompletion} is called with the status the transaction reached, and a synchronisation that
     * acted on anything except a commit would act for a transaction whose row was never recorded. Marked for
     * rollback, rolling back, prepared, unknown - each of them means the row is not there, or not there yet, and
     * publishing content for a row that does not exist is how a store comes to hold objects nothing references
     * and to have had a stable key deleted by a transaction that never owned it.
     *
     * <p>Every status in the specification is driven except the one that publishes, and the store is asserted
     * untouched after each - both the version it already held and the fact that nothing was removed. The write
     * is real: the file on disk differs from the object throughout, so a synchronisation that acted on any of
     * these statuses would be visible immediately.
     *
     * @param home a per-test temporary directory standing in for {@code ofbiz.home}
     * @throws Exception if the seam cannot be exercised
     */
    @Test
    public void noCompletionStatusOtherThanCommittedTouchesTheStore(@TempDir Path home) throws Exception {
        System.setProperty("ofbiz.home", home.toString());
        RecordedObjectStore store = recordingObjectStoreClient();
        Path target = Files.createDirectories(home.resolve("runtime/uploads")).resolve("doomed.bin");
        Files.writeString(target, "the copy this instance already had");

        Synchronization pending = writeThroughFor(() -> {
            File resolved = DataResourceWorker.getContentFile("OFBIZ_FILE", "/runtime/uploads/doomed.bin", null);
            Files.write(resolved.toPath(), PAYLOAD);
        });
        assertNotNull(pending, "the resolution must register, or this case would prove only that nothing was"
                + " listening");
        pending.beforeCompletion();

        int[] everyStatusButACommit = {Status.STATUS_ACTIVE, Status.STATUS_MARKED_ROLLBACK,
                Status.STATUS_PREPARED, Status.STATUS_ROLLEDBACK, Status.STATUS_UNKNOWN,
                Status.STATUS_NO_TRANSACTION, Status.STATUS_PREPARING, Status.STATUS_COMMITTING,
                Status.STATUS_ROLLING_BACK, };
        for (int status : everyStatusButACommit) {
            pending.afterCompletion(status);

            assertTrue(store.publishedKeys().isEmpty(), "completion status [" + status + "] is not a commit, so"
                    + " nothing may be published for it: " + store.publishedKeys());
            assertTrue(store.removedKeys().isEmpty(), "completion status [" + status + "] is not a commit, so"
                    + " nothing may be removed either - deleting the stable key would destroy content this"
                    + " transaction never owned: " + store.removedKeys());
        }
        assertArrayEquals(PAYLOAD, Files.readAllBytes(target), "the local write itself is untouched by any of"
                + " this, which is what makes the store the only thing being asserted about");
    }

    /**
     * The strict-fallback refusal names no storage key, because it reaches whoever asked for the content.
     *
     * <p>A storage key is this deployment's own layout - the {@code ofbiz.home}-relative path of the content,
     * under whatever prefix the bucket is organised by - and this refusal travels back through the
     * content-rendering path, where it can reach a rendered page. So the key goes to the log beside an opaque
     * reference, and the message carries the reference and the setting an operator would change (CWE-200).
     *
     * @param home a per-test temporary directory standing in for {@code ofbiz.home}
     * @throws Exception if the seam cannot be exercised
     */
    @Test
    public void theStrictFallbackRefusalNamesNoStorageKey(@TempDir Path home) throws Exception {
        System.setProperty("ofbiz.home", home.toString());
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_LOCAL_FALLBACK, "false");
        installObjectStoreClient(inMemoryObjectStore(new ConcurrentHashMap<>()));
        Path local = Files.createDirectories(home.resolve("runtime/uploads")).resolve("only-local.bin");
        Files.writeString(local, "a copy only this instance has");

        GeneralException refused = assertThrows(GeneralException.class, () -> renderedThroughSeam("OFBIZ_FILE",
                "/runtime/uploads/only-local.bin", null, seamDelegator("default", null), "10000"),
                "with the strict posture the store is the authority, so a local copy must not answer for it");

        for (String disclosure : new String[] {"runtime/uploads/only-local.bin", "only-local.bin",
                home.toString()}) {
            assertFalse(refused.getMessage().contains(disclosure), "the refusal reaches whoever asked for the"
                    + " content, so it must not disclose [" + disclosure + "]: " + refused.getMessage());
        }
        assertTrue(refused.getMessage().contains("Reference ["), "the refusal must carry the opaque reference that"
                + " joins it to the log line explaining it: " + refused.getMessage());
        assertTrue(refused.getMessage().contains(PROPERTY_LOCAL_FALLBACK), "the refusal must name the setting an"
                + " operator would change: " + refused.getMessage());
    }

    /**
     * Runs a seam call inside a transaction that reports itself active, and returns the write-through it
     * registered.
     *
     * <p>A real transaction manager is not available to a unit test, and it is not what is under test: what is
     * under test is which synchronisation the seam registers and what that synchronisation then does at each
     * completion status. Substituting {@code TransactionUtil} makes both observable, and driving the returned
     * synchronisation directly is what lets one test assert the commit and the rollback outcomes of the same
     * resolution.
     *
     * @param seam the seam call to make, and the write it should perform
     * @return the registered write-through, or null when the seam registered none
     * @throws Exception if the seam call itself fails
     */
    private static Synchronization writeThroughFor(ThrowingRunnable seam) throws Exception {
        List<Synchronization> registered = new ArrayList<>();
        try (MockedStatic<TransactionUtil> transactions = mockStatic(TransactionUtil.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
            transactions.when(TransactionUtil::getStatus).thenReturn(Status.STATUS_ACTIVE);
            transactions.when(() -> TransactionUtil.registerSynchronization(any(Synchronization.class)))
                    .thenAnswer(call -> registered.add(call.getArgument(0)));
            seam.run();
        }
        return registered.isEmpty() ? null : registered.get(0);
    }

    /** A seam call that may fail, so a test body can be handed to {@link #writeThroughFor}. */
    private interface ThrowingRunnable {
        /**
         * Makes the seam call.
         *
         * @throws Exception if it fails
         */
        void run() throws Exception;
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
        return ContentStoreTestSupport.seamDelegator(base, tenantId);
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
        // The binary write services read the same three fields through get() rather than getString(), so a
        // row stubbed for one accessor alone would hand them nulls and prove nothing about their behaviour.
        when(dataResource.get("dataResourceTypeId")).thenReturn(typeId);
        when(dataResource.get("dataResourceId")).thenReturn(dataResourceId);
        when(dataResource.get("objectInfo")).thenReturn(objectInfo);
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
        // STATED, not inherited. Every configuration this suite installs is a strict one, so a test that
        // needs the plaintext permission has to ask for it in its own body and every other test is proved
        // against the posture a deployment actually runs. Left to whatever the JVM happened to hold, a test
        // asserting that a plaintext endpoint is REFUSED would pass or fail according to which test ran
        // before it - and the committed default is this value, so stating it changes nothing but the
        // suite's independence from its own execution order.
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_INSECURE_ENDPOINT, "false");
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
     * Creates a confined content file inside the storage root, under its own first-level directory.
     *
     * @param home the storage root
     * @param branch the first-level directory name, so each case has a subtree of its own
     * @return the {@code uploads} directory holding the content
     * @throws IOException if the tree cannot be created
     */
    private static Path confinedContentUnder(Path home, String branch) throws IOException {
        Path uploads = Files.createDirectories(home.resolve(branch).resolve("uploads"));
        Files.write(uploads.resolve("secret.txt"), "CONFINED".getBytes(StandardCharsets.UTF_8));
        return uploads;
    }

    /**
     * Returns where a branch's real directory ends up once it has been exchanged for a link.
     *
     * <p>After the exchange the original name resolves to the link's target, so the confined directory - the
     * one the provider is still operating on through its descriptors - is reachable only here. Reading through
     * the original name instead would read the bait and report a pass as a failure.
     *
     * @param home the storage root
     * @param branch the first-level directory that was exchanged
     * @return the {@code uploads} directory of the moved-aside original
     */
    private static Path movedAside(Path home, String branch) {
        return home.resolve(branch + "-moved-aside").resolve("uploads");
    }

    /**
     * Arranges for one directory to be exchanged for a symbolic link during the provider's next operation,
     * at the instant between the tree being walked and the content being touched.
     *
     * <p>One shot, because an operation walks the tree once and the exchange must happen once: performed
     * again it would find the directory already moved aside and fail, and repeating it would say nothing
     * further anyway.
     *
     * <p>The directory is moved aside rather than deleted, so the directory the provider already holds open
     * still exists and still holds its content. That is the situation being tested - the name now points
     * elsewhere while the directory itself is untouched - and it is what distinguishes "the operation
     * followed the new name" from "the operation failed because the content vanished".
     *
     * @param home the storage root
     * @param branch the first-level directory to exchange
     * @param pointingAt what the replacing link is to point at
     */
    private static void exchangeDuringNextOperation(Path home, String branch, Path pointingAt) {
        Path directory = home.resolve(branch);
        AtomicBoolean exchanged = new AtomicBoolean(false);
        FileSystemContentStore.installInterleavedActionForTesting(() -> {
            if (!exchanged.compareAndSet(false, true)) {
                return;
            }
            try {
                Files.move(directory, directory.resolveSibling(branch + "-moved-aside"));
                Files.createSymbolicLink(directory, pointingAt);
            } catch (IOException e) {
                throw new IllegalStateException("the ancestor exchange this test depends on could not be"
                        + " performed", e);
            }
        });
    }

    /**
     * Asserts that a failure arrived as the contract's failure rather than as an absence.
     *
     * @param thrown the exception the operation raised
     */
    private static void assertStoreFailure(IOException thrown) {
        assertFalse(thrown instanceof FileNotFoundException, "a store failure must not be reported as an"
                + " absence: " + thrown);
        // Identified by its reference rather than by the store's own words: the report reaches whoever asked
        // for the content, so what the store said - which can quote the request it was building - stays in
        // the log beside this reference, sanitised.
        assertTrue(thrown.getMessage().contains("Reference ["), "a store failure must carry the opaque"
                + " reference that joins it to the log line explaining it: " + thrown);
        assertSanitisedCause(thrown);
    }

    /**
     * Asserts that a translated failure carries a cause that can be inspected and cannot leak.
     *
     * <p>Both halves matter and they pull against each other, which is why they are asserted in one place.
     * Attaching the SDK failure itself would republish whatever it quoted - an SDK message can quote the
     * request it was building, endpoint and signed headers included - through anything that logs a caught
     * {@code IOException} with its stack trace or reads {@code getCause().getMessage()}. Attaching NOTHING,
     * which is what this did, left a caller with fixed English text and an opaque reference as its only
     * diagnostics: there was no way to tell an expired deadline from {@code AccessDenied}, so no way to
     * decide whether retrying could ever help or which alert to raise.
     *
     * <p>So the cause is a sanitised stand-in: it carries the type and the service fields as data, carries
     * no stack trace of its own - it is built where the translation happens, not where the failure did -
     * and quotes none of the SDK's message, cause or suppressed failures.
     *
     * @param thrown the translated exception
     */
    private static void assertSanitisedCause(Throwable thrown) {
        Throwable cause = thrown.getCause();
        assertNotNull(cause, "a translated failure must carry a cause an inspecting caller can branch on,"
                + " because fixed text and an opaque reference are not diagnostics: " + thrown);
        assertFalse(cause instanceof SdkException, "the cause must not be an SDK type, or no SDK type would"
                + " have been kept out of the contract after all: " + cause.getClass().getName());
        assertTrue(cause instanceof S3ContentStore.RedactedStoreCause, "the cause must be the sanitised"
                + " stand-in rather than anything of the SDK's: " + cause.getClass().getName());
        S3ContentStore.RedactedStoreCause redacted = (S3ContentStore.RedactedStoreCause) cause;
        assertNull(redacted.getCause(), "the sanitised cause must carry no cause of its own, or the SDK"
                + " failure would be one dereference further down the same chain");
        assertEquals(0, redacted.getSuppressed().length, "the sanitised cause must suppress nothing");
        assertEquals(0, redacted.getStackTrace().length, "the sanitised cause must carry no stack trace: it"
                + " is built where the translation happens, not where the failure did, so a trace of its own"
                + " would describe the translator and mislead");
        assertNotNull(redacted.getMessage(), "the sanitised cause must describe the failure by type");
        assertTrue(redacted.getMessage().contains(redacted.failureType()), "the message and the fields must"
                + " agree: [" + redacted.getMessage() + "] vs [" + redacted.failureType() + "]");
        for (String secret : new String[] {BUCKET, KEY}) {
            assertFalse(redacted.getMessage().contains(secret), "the sanitised cause must disclose neither"
                    + " the bucket nor the key: [" + redacted.getMessage() + "]");
        }
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

    /*
     * Write-seam fixtures
     */

    @Test
    public void aStreamedWriteToTheObjectStoreIsHeldToTheLengthItDeclared() throws Exception {
        // The object-store half of the same contract the filesystem provider is held to, and the one where
        // getting it wrong was silent: the SDK frames a PutObject from the declared length, writes exactly
        // that many bytes and never looks at the rest, so a stream holding MORE than it declared was stored
        // TRUNCATED and stored successfully. Nothing anywhere reported it and a later read returned content
        // that looked complete. The recording client below writes the body exactly as the SDK does - the
        // declared number of bytes and no more - so what is asserted is the real interaction and not a
        // stand-in for it.
        List<byte[]> sent = new ArrayList<>();
        S3Client client = clientWritingPutBodiesLikeTheSdk(sent);
        ContentStore store = new S3ContentStore(client, OFFLINE_BUCKET);

        Executable declaresLessThanItHolds = () -> store.put(KEY, new ByteArrayInputStream(PAYLOAD),
                PAYLOAD.length - 1L);
        GeneralException tooLong = assertThrows(GeneralException.class, declaresLessThanItHolds,
                "a stream holding more than the length it declared must be refused rather than stored"
                        + " truncated");
        assertTrue(tooLong.getMessage().contains("holds more than"), "the refusal must say the stream was"
                + " longer than it declared: [" + tooLong.getMessage() + "]");

        Executable declaresMoreThanItHolds = () -> store.put(KEY, new ByteArrayInputStream(PAYLOAD),
                PAYLOAD.length + 8L);
        GeneralException tooShort = assertThrows(GeneralException.class, declaresMoreThanItHolds,
                "a stream ending before the length it declared must be refused as the caller error it is,"
                        + " not reported as the store failing");
        assertTrue(tooShort.getMessage().contains("bytes before"), "the refusal must say the stream ended"
                + " early: [" + tooShort.getMessage() + "]");

        assertEquals(List.of(), sent, "a refused write must leave nothing stored: neither mismatch may have"
                + " completed a request");

        // And the check is transparent to a stream that does agree with its length - including the look-ahead,
        // which must not eat the last byte of a correct write.
        store.put(KEY, new ByteArrayInputStream(PAYLOAD), PAYLOAD.length);
        assertEquals(1, sent.size(), "a correct write must reach the store exactly once");
        assertArrayEquals(PAYLOAD, sent.get(0), "a correct write must arrive whole and unaltered");
    }

    @Test
    public void aClientThatCannotBeClosedStillReleasesTheCredentialProviderItOwns() throws Exception {
        // Two owned resources, closed in one try: a client whose own close threw took the credential
        // provider's release with it. That release is not optional - the default chain keeps an HTTP client
        // of its own for instance metadata, and only the instance that built the chain can close it - so
        // skipping it leaks that client's pool and threads for the life of the JVM, at exactly the moment
        // something was already going wrong.
        RecordedSdk sdk = new RecordedSdk();
        sdk.failTheCloseWith(new IllegalStateException("the SDK client cannot be closed"));
        S3ContentStore.installSdkConstructionForTesting(sdk);
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_BUCKET, "configured-bucket");
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_REGION, "us-east-1");
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_ENDPOINT, "");
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_KEY_PREFIX, "");
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_ACCESS_KEY_ID, "");
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_SECRET_ACCESS_KEY, "");
        S3ContentStore store = new S3ContentStore((Delegator) null);
        assertNotNull(sdk.lastDefaultChain(), "no configured credential pair must build the default chain,"
                + " or this test asserts nothing about releasing one");

        // Reported, never propagated: close runs while a provider is being replaced or while the JVM is
        // stopping, and neither has anywhere to report a failure to.
        assertDoesNotThrow(store::close, "a close that cannot release the client must still return");

        assertTrue(sdk.lastDefaultChain().isClosed(), "the credential provider this provider built must be"
                + " released even when closing the client fails, because nothing else can ever reach it");
    }

    @Test
    public void aBodyThatFailsWhileItIsBeingReadIsTranslatedLikeEveryOtherStoreFailure() throws Exception {
        // Translation used to stop at the moment openStream returned. After it the caller held the SDK's own
        // response stream, so a reset connection or a truncated body arrived as whatever the SDK or the HTTP
        // client chose to say - uncorrelated and unredacted, on the path whose whole purpose is to render
        // content into a response.
        AtomicBoolean aborted = new AtomicBoolean(false);
        S3Client client = mock(S3Client.class);
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(bodyFailingAfter(
                "https://store.example.internal/" + OFFLINE_BUCKET + "/secret-layout", aborted));
        ContentStore store = new S3ContentStore(client, OFFLINE_BUCKET);

        ContentStore.ContentStream body = store.openStream(KEY);
        IOException reported = assertThrows(IOException.class, body::read,
                "a body that fails mid-read must be reported through the contract, not through the SDK");

        assertFalse(reported instanceof FileNotFoundException, "a failure to read a body that was found is"
                + " not an absence: " + reported);
        assertTrue(reported.getMessage().contains("Reference ["), "the report must carry the opaque reference"
                + " that joins it to the log line explaining it: " + reported.getMessage());
        for (String disclosure : new String[] {"store.example.internal", OFFLINE_BUCKET, "secret-layout", KEY}) {
            assertFalse(reported.getMessage().contains(disclosure), "the report reaches whoever asked for the"
                    + " content, so it must not disclose [" + disclosure + "]: " + reported.getMessage());
        }
        assertTrue(aborted.get(), "a failed body must be ABORTED rather than closed: closing a partly-read"
                + " response drains the remainder of the object off the wire, which is the transfer a"
                + " refusal exists to avoid");
    }

    @Test
    public void aBodyStillBeingReadAfterItsDeadlineIsAbandonedRatherThanWaitedOn() throws Exception {
        // The SDK's api-call and attempt deadlines stop at the moment the body is handed over, and what it
        // bounds after that is INACTIVITY: a store answering each read just inside the socket timeout never
        // trips it and holds a request thread, a connection and a pool slot for as long as it likes. This is
        // the deadline over the WHOLE body that closes that, so "any size" does not also mean "any duration".
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROPERTY_S3_STREAM_TIMEOUT, "1000");
        AtomicBoolean aborted = new AtomicBoolean(false);
        S3Client client = mock(S3Client.class);
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength((long) PAYLOAD.length).build(),
                AbortableInputStream.create(new ByteArrayInputStream(PAYLOAD), () -> aborted.set(true))));
        ContentStore store = new S3ContentStore(client, OFFLINE_BUCKET);

        ContentStore.ContentStream body = store.openStream(KEY);
        assertTrue(body.read() >= 0, "the body must serve content before its deadline");
        Thread.sleep(1100L);

        IOException refused = assertThrows(IOException.class, body::read,
                "a body still being read after its deadline must be refused rather than served");
        assertTrue(refused.getMessage().contains("Reference ["), "the refusal must carry the opaque reference"
                + " that joins it to the log line explaining it: " + refused.getMessage());
        assertFalse(refused.getMessage().contains(PROPERTY_S3_STREAM_TIMEOUT), "the refusal reaches whoever"
                + " asked for the content, so the setting that governs it belongs in the log: "
                + refused.getMessage());
        assertTrue(aborted.get(), "an overrun body must be abandoned rather than drained");
        // And it stays refused: a caller that keeps reading must not be handed the rest of a partial object
        // as though nothing had happened.
        assertThrows(IOException.class, body::read, "an abandoned body must stay abandoned");
    }

    /**
     * Builds a client that writes a {@code PutObject} body exactly as the SDK writes one, recording what
     * arrived.
     *
     * <p>Faithful in the one respect that matters here: the SDK frames the request from the DECLARED length
     * and writes that many bytes from the body, never more. A mock that ignored the body entirely could not
     * tell a stream that agreed with its length from one that did not, and a mock that drained the body to
     * its end would be testing something the SDK never does. An {@link IOException} raised while the body is
     * being written is reported the way the SDK reports one, as an {@link SdkClientException}, because that
     * is what the provider has to recognise.
     *
     * @param sent collects the body of every request that completed
     * @return the client to issue requests through
     */
    private static S3Client clientWritingPutBodiesLikeTheSdk(List<byte[]> sent) {
        S3Client client = mock(S3Client.class);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(call -> {
            RequestBody body = call.getArgument(1);
            long declared = body.optionalContentLength().orElse(0L);
            try (InputStream content = body.contentStreamProvider().newStream()) {
                sent.add(content.readNBytes((int) declared));
            } catch (IOException e) {
                throw SdkClientException.create("Unable to execute HTTP request", e);
            }
            return PutObjectResponse.builder().build();
        });
        return client;
    }

    /**
     * Builds a response whose body fails as soon as it is read, quoting deployment layout in the way an SDK
     * or HTTP-client failure does.
     *
     * @param quoted what the failure's own message discloses, which must not reach the caller
     * @param aborted set when the response is abandoned rather than closed
     * @return an SDK response stream that fails on first read
     */
    private static ResponseInputStream<GetObjectResponse> bodyFailingAfter(String quoted, AtomicBoolean aborted) {
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("Connection reset while reading " + quoted);
            }
        };
        return new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength(1024L).build(),
                AbortableInputStream.create(failing, () -> aborted.set(true)));
    }

    /**
     * Installs an object-store provider whose SDK client is the supplied mock, so the whole provider - key
     * derivation, prefixing, request building and failure translation - runs for real with no network, no
     * credential and no configuration beyond the selector.
     *
     * @param client the client every request is issued through
     */
    private static void installObjectStoreClient(S3Client client) {
        configureProvider(PROVIDER_S3);
        ContentStoreFactory.installConstructionForTesting((provider, delegator) ->
                new S3ContentStore(client, OFFLINE_BUCKET, delegator));
    }

    /**
     * The SDK, substituted: a recording client builder and a close-observable credential chain.
     *
     * <p>The builder is a Mockito mock answering {@code RETURNS_SELF}, so the provider's fluent chain works
     * as it does against the real builder and every call it makes is recorded for verification. The
     * credential chain is a written-out class rather than a mock because what has to be observed is that it
     * was <em>closed</em>, and the release path tests {@code instanceof SdkAutoCloseable} - so the fixture has
     * to genuinely be one, which a mock of the interface alone is not.
     */
    private static final class RecordedSdk implements S3ContentStore.SdkConstruction {

        private final AtomicInteger builders = new AtomicInteger();
        private final AtomicInteger defaultChains = new AtomicInteger();
        private final S3ClientBuilder builder = mock(S3ClientBuilder.class, RETURNS_SELF);
        private final S3Client client = mock(S3Client.class);
        private ClosableCredentials lastDefaultChain;
        private AwsCredentialsProvider credentialsGiven;
        private RuntimeException buildFailure;
        private RuntimeException closeFailure;

        @Override
        public S3ClientBuilder clientBuilder() {
            builders.incrementAndGet();
            when(builder.credentialsProvider(any(AwsCredentialsProvider.class))).thenAnswer(call -> {
                credentialsGiven = call.getArgument(0);
                return builder;
            });
            if (buildFailure == null) {
                when(builder.build()).thenReturn(client);
            } else {
                when(builder.build()).thenThrow(buildFailure);
            }
            if (closeFailure != null) {
                doThrow(closeFailure).when(client).close();
            }
            return builder;
        }

        @Override
        public AwsCredentialsProvider defaultCredentialsProvider() {
            defaultChains.incrementAndGet();
            lastDefaultChain = new ClosableCredentials();
            return lastDefaultChain;
        }

        /**
         * Makes the substituted builder refuse to build, the way the SDK refuses a configuration it will
         * not accept.
         *
         * @param failure what the build throws
         */
        private void failTheBuildWith(RuntimeException failure) {
            buildFailure = failure;
        }

        /**
         * Makes the substituted client refuse to close, the way a client with a failing resource does.
         *
         * @param failure what the close throws
         */
        private void failTheCloseWith(RuntimeException failure) {
            closeFailure = failure;
        }

        /**
         * Returns the recording builder, for verification.
         *
         * @return the builder every construction was configured on
         */
        private S3ClientBuilder builder() {
            return builder;
        }

        /**
         * Returns how many times a client builder was asked for.
         *
         * @return the count, which must be zero when a local setting was refused
         */
        private int buildersCreated() {
            return builders.get();
        }

        /**
         * Returns how many default credential chains were created.
         *
         * @return the count, which must be zero when a credential pair is configured
         */
        private int defaultChainsCreated() {
            return defaultChains.get();
        }

        /**
         * Returns the last default credential chain created, so its closure can be asserted.
         *
         * @return the chain, or null when none was created
         */
        private ClosableCredentials lastDefaultChain() {
            return lastDefaultChain;
        }

        /**
         * Returns the credential provider the client was actually given.
         *
         * @return the provider handed to the builder
         */
        private AwsCredentialsProvider credentialsGiven() {
            return credentialsGiven;
        }
    }

    /** A credential provider that is genuinely closeable, so that closing it can be observed. */
    private static final class ClosableCredentials implements AwsCredentialsProvider, SdkAutoCloseable {

        private final AtomicBoolean closed = new AtomicBoolean(false);

        @Override
        public AwsCredentials resolveCredentials() {
            return AwsBasicCredentials.create("default-chain-key", "default-chain-secret");
        }

        @Override
        public void close() {
            closed.set(true);
        }

        /**
         * Reports whether this provider was released.
         *
         * @return true once {@code close} has been called
         */
        private boolean isClosed() {
            return closed.get();
        }
    }

    /**
     * Installs an object-store construction that counts every provider it builds and observes every one
     * that is closed.
     *
     * <p>Each construction wraps a {@link S3Client} of its own, which is what makes closure observable: the
     * factory closes a provider it no longer holds through the provider's own package-private hook, and
     * that hook closes the client. A shared client could not distinguish which of several providers had
     * been closed, and a fake provider could not be closed at all - the factory closes the object-store
     * provider specifically, because it is the one that owns something to release. So the thing built here
     * is a real {@link S3ContentStore} over a mocked client, and "was it closed" is answered by asking the
     * client it was given.
     *
     * @return the record of what was built and what was closed
     */
    private static CountingConstruction installCountedObjectStoreConstruction() {
        return installCountedObjectStoreConstruction(null);
    }

    /**
     * Installs a counted object-store construction that holds each provider it builds until the given
     * number of builds are under way at once.
     *
     * @param racing the latch each construction counts down and then waits on, or null for none
     * @return the record of what was built and what was closed
     */
    private static CountingConstruction installCountedObjectStoreConstruction(CountDownLatch racing) {
        configureProvider(PROVIDER_S3);
        CountingConstruction construction = new CountingConstruction(racing);
        ContentStoreFactory.installConstructionForTesting(construction);
        return construction;
    }

    /**
     * A provider construction that remembers every provider it built and can say which of them were closed.
     *
     * <p>Every collection here is concurrent or synchronised, because the point of the fixture is to be
     * driven by many threads at once: an {@link ArrayList} would lose constructions to a lost update and
     * turn a real leak into a passing test.
     */
    private static final class CountingConstruction implements ContentStoreFactory.ProviderConstruction {

        private final AtomicInteger constructions = new AtomicInteger();
        private final List<ContentStore> built = Collections.synchronizedList(new ArrayList<>());
        private final Map<ContentStore, S3Client> clients = new ConcurrentHashMap<>();

        /**
         * How many constructions must be in progress at once before any of them is allowed to finish, or
         * {@code null} when constructions may proceed alone.
         *
         * <p>This is what makes the race happen rather than hoping for it. Without it the first thread can
         * complete its whole resolution - construct, install, return - before the second thread has looked
         * at the map, so the second takes the fast path, only one provider is ever built, and the assertion
         * that every loser is closed becomes the assertion that zero losers are closed: true of a factory
         * that closes nothing at all. Holding each construction until its peers have joined it means the
         * losers exist, so the closure assertion has something to be wrong about.
         */
        private final CountDownLatch racing;

        /** Constructs a record whose constructions may each proceed alone. */
        CountingConstruction() {
            this(null);
        }

        /**
         * Constructs a record that holds every construction until the given number are under way.
         *
         * @param racing the latch each construction counts down and then waits on, or null for none
         */
        CountingConstruction(CountDownLatch racing) {
            this.racing = racing;
        }

        @Override
        public ContentStore create(String provider, Delegator delegator) throws GeneralException {
            constructions.incrementAndGet();
            if (racing != null) {
                racing.countDown();
                try {
                    // Bounded, and its result deliberately ignored: if the peers do not arrive this must
                    // carry on and let the test report the construction count it actually achieved, rather
                    // than hang the build. The count is asserted on, so a race that did not happen fails
                    // the test with an explanation instead of timing out without one.
                    racing.await(CONCURRENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new GeneralException("interrupted while holding a construction open for its peers",
                            interrupted);
                }
            }
            S3Client client = mock(S3Client.class);
            S3ContentStore store = new S3ContentStore(client, OFFLINE_BUCKET, delegator);
            clients.put(store, client);
            built.add(store);
            return store;
        }

        /**
         * Returns how many providers were built.
         *
         * @return the construction count
         */
        private int constructed() {
            return constructions.get();
        }

        /**
         * Returns every provider that was built, in the order they were built.
         *
         * @return the providers
         */
        private List<ContentStore> all() {
            return List.copyOf(built);
        }

        /**
         * Returns every provider whose client has been closed.
         *
         * <p>Determined by asking each mocked client whether {@code close} was called on it, so it reports
         * what the provider's lifecycle hook actually did rather than what a fixture was told.
         *
         * @return the closed providers, in construction order
         */
        private List<ContentStore> closed() {
            List<ContentStore> answer = new ArrayList<>();
            for (ContentStore store : all()) {
                try {
                    verify(clients.get(store), atLeastOnce()).close();
                    answer.add(store);
                } catch (AssertionError notClosed) {
                    // Mockito reports "never called" by throwing, which here is the answer "not closed"
                    // rather than a failure: this method exists to report which providers were closed.
                    continue;
                }
            }
            return answer;
        }
    }

    /**
     * Installs an object-store client that accepts every write and records what it was given, at the moment
     * it was given it.
     *
     * <p>Recorded on the way through rather than captured and replayed afterwards, because a request body is
     * a stream over content the caller owns: the seam closes the file it streamed from as soon as the store
     * has taken it, so a body replayed after the call has nothing left to read. Reading it inside the answer
     * is also the more faithful fixture - it is what a real store does with the body.
     *
     * @return the recorder, holding the client to install and everything it received
     */
    private static RecordedObjectStore recordingObjectStoreClient() {
        RecordedObjectStore recorder = new RecordedObjectStore();
        installObjectStoreClient(recorder.client());
        return recorder;
    }

    /**
     * An object store that accepts every request and remembers what it was asked to hold.
     *
     * <p>Backed by a mocked SDK client, so the whole provider - key derivation, prefixing, request building
     * and failure translation - runs for real above it.
     */
    private static final class RecordedObjectStore {

        private final Map<String, byte[]> held = new LinkedHashMap<>();
        private final List<String> removed = new ArrayList<>();
        private final S3Client client = mock(S3Client.class);

        private RecordedObjectStore() {
            when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(invocation -> {
                PutObjectRequest request = invocation.getArgument(0);
                RequestBody body = invocation.getArgument(1);
                try (InputStream content = body.contentStreamProvider().newStream()) {
                    held.put(request.key(), content.readAllBytes());
                }
                return PutObjectResponse.builder().build();
            });
            when(client.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(invocation -> {
                DeleteObjectRequest request = invocation.getArgument(0);
                removed.add(request.key());
                held.remove(request.key());
                return DeleteObjectResponse.builder().build();
            });
        }

        private S3Client client() {
            return client;
        }

        /**
         * Returns the content held under a key, failing when nothing was stored under it.
         *
         * @param key the object key, without a prefix, which the offline fixture leaves blank
         * @return the stored content
         */
        private byte[] contentOf(String key) {
            byte[] content = held.get(key);
            if (content == null) {
                throw new AssertionError("nothing was published under [" + key + "]; the store holds "
                        + held.keySet());
            }
            return content;
        }

        private List<String> removedKeys() {
            return removed;
        }

        /**
         * Returns every key this store was asked to hold, in the order it was asked.
         *
         * @return the published keys
         */
        private Set<String> publishedKeys() {
            return held.keySet();
        }
    }

    /**
     * Builds the dispatch context the write services read their delegator from.
     *
     * @param delegator the delegator the write is performed through
     * @return the stubbed context
     */
    private static DispatchContext dispatchContextFor(Delegator delegator) {
        DispatchContext dctx = mock(DispatchContext.class);
        when(dctx.getDelegator()).thenReturn(delegator);
        return dctx;
    }

    /**
     * A small, valid PNG the upload validation accepts.
     *
     * <p>Encoded through {@code ImageIO} rather than written out as literal bytes, so the fixture is a real
     * image by construction. It is 8x8 rather than 1x1 because the validation rescales what it is given in
     * order to strip anything hidden in the file, and a one-pixel image rescales to nothing.
     *
     * @return the encoded image
     * @throws IOException if the image cannot be encoded, which no platform this runs on does
     */
    private static byte[] pngCarryingMetadata(String marker) throws IOException {
        byte[] png = smallPng();
        // The IEND chunk is the last twelve bytes of every PNG: a zero length, the type, and its CRC. A tEXt
        // chunk spliced in front of it is a well formed PNG that carries the marker in its metadata, which is
        // precisely what the upload sanitiser exists to strip - so it is what tells a published copy that came
        // from the sanitised FILE apart from one that came from the submitted array.
        byte[] data = ("Comment\u0000" + marker).getBytes(StandardCharsets.ISO_8859_1);
        CRC32 crc = new CRC32();
        crc.update("tEXt".getBytes(StandardCharsets.US_ASCII));
        crc.update(data);
        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        chunk.write(fourBytes(data.length));
        chunk.write("tEXt".getBytes(StandardCharsets.US_ASCII));
        chunk.write(data);
        chunk.write(fourBytes(crc.getValue()));

        ByteArrayOutputStream carrying = new ByteArrayOutputStream();
        carrying.write(png, 0, png.length - 12);
        carrying.write(chunk.toByteArray());
        carrying.write(png, png.length - 12, 12);
        return carrying.toByteArray();
    }

    /**
     * Encodes a value as the four big-endian bytes a PNG chunk header and CRC are written with.
     *
     * @param value the value to encode
     * @return the four bytes, most significant first
     */
    private static byte[] fourBytes(long value) {
        return new byte[] {(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
    }

    /**
     * A minimal, valid PNG, encoded by the platform so that the upload validation accepts it.
     *
     * @return the encoded image
     * @throws IOException if the platform cannot encode a PNG
     */
    private static byte[] smallPng() throws IOException {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                image.setRGB(x, y, (x + y) % 2 == 0 ? 0xFFFFFF : 0x000000);
            }
        }
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", encoded), "the platform must be able to encode a PNG");
        return encoded.toByteArray();
    }

    /**
     * A stream that yields a fixed number of bytes without holding them, so content larger than any bound
     * this suite applies can be served without allocating it.
     *
     * <p>This is what makes the large-content case honest: a fixture that built a byte array of the size
     * under test would prove the array could be allocated rather than that the content was streamed.
     */
    private static final class RepeatingInputStream extends InputStream {

        private long remaining;

        private RepeatingInputStream(long length) {
            this.remaining = length;
        }

        @Override
        public int read() {
            if (remaining <= 0L) {
                return -1;
            }
            remaining--;
            return 'x';
        }

        @Override
        public int read(byte[] buffer, int offset, int wanted) {
            if (remaining <= 0L) {
                return -1;
            }
            int produced = (int) Math.min(wanted, remaining);
            for (int index = 0; index < produced; index++) {
                buffer[offset + index] = 'x';
            }
            remaining -= produced;
            return produced;
        }
    }

    /**
     * A payload source that stops in the middle of itself until it is released.
     *
     * <p>This is what makes the write-visibility assertions decided rather than raced: the reader looks at the
     * key while the writer is provably half way through its content, so a provider that exposed a partly
     * written file fails every time rather than on a slow machine.
     */
    private static final class HalfwayStream extends InputStream {

        private final byte[] payload;
        private final CountDownLatch halfway = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private int position;

        private HalfwayStream(byte[] payload) {
            this.payload = payload;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int produced = read(one, 0, 1);
            return produced == -1 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int wanted) throws IOException {
            if (position >= payload.length) {
                return -1;
            }
            if (position >= payload.length / 2) {
                halfway.countDown();
                try {
                    released.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("the halfway payload was interrupted", interrupted);
                }
            }
            int produced = Math.min(wanted, payload.length - position);
            System.arraycopy(payload, position, buffer, offset, produced);
            position += produced;
            return produced;
        }

        /**
         * Waits until the payload has delivered at least half of itself and stopped.
         *
         * @param timeout how long to wait
         * @param unit the unit of {@code timeout}
         * @return whether the halfway point was reached inside the timeout
         * @throws InterruptedException if the wait is interrupted
         */
        private boolean reachedHalfway(long timeout, TimeUnit unit) throws InterruptedException {
            return halfway.await(timeout, unit);
        }

        /** Lets the rest of the payload through; safe to call more than once. */
        private void release() {
            released.countDown();
        }
    }

    /**
     * A response body that counts what was taken off it and whether it was aborted.
     *
     * <p>Written out rather than mocked because {@link AbortableInputStream} is final, and because the
     * question being asked is not "was a method called" but "how many bytes actually crossed the
     * connection" - which only a real stream can answer.
     */
    private static final class CountedBody {

        private final RepeatingInputStream content;
        private final AtomicInteger read = new AtomicInteger();
        private final AtomicInteger aborted = new AtomicInteger();

        private CountedBody(long length) {
            this.content = new RepeatingInputStream(length);
        }

        /**
         * Returns the body as the SDK hands it to a provider, with an abort the test can observe.
         *
         * @return the abortable stream to build a {@code ResponseInputStream} on
         */
        private AbortableInputStream abortable() {
            InputStream counting = new InputStream() {
                @Override
                public int read() throws IOException {
                    int next = content.read();
                    if (next != -1) {
                        read.incrementAndGet();
                    }
                    return next;
                }

                @Override
                public int read(byte[] buffer, int offset, int wanted) throws IOException {
                    int produced = content.read(buffer, offset, wanted);
                    if (produced > 0) {
                        read.addAndGet(produced);
                    }
                    return produced;
                }
            };
            return AbortableInputStream.create(counting, aborted::incrementAndGet);
        }

        /**
         * Returns how many bytes were taken off the body.
         *
         * @return the byte count, which is what a drained response makes large
         */
        private int bytesRead() {
            return read.get();
        }

        /**
         * Returns how many times the body was aborted.
         *
         * @return the abort count, one for every response released without being read to its end
         */
        private int aborts() {
            return aborted.get();
        }
    }
}
