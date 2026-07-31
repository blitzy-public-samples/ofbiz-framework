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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.UtilProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Executes {@link S3ContentStore} - the production object-storage provider itself - entirely offline.
 *
 * <p>Nothing here reaches a network, resolves an AWS credential or needs an object store to exist. The
 * provider's own package-private {@code S3ContentStore(S3Client, String)} seam supplies a stubbed
 * {@link S3Client} whose four object operations are backed by an in-memory map, which makes the provider a
 * genuinely storing implementation with only its SDK boundary replaced - exactly what
 * {@link ContentStoreBehaviourContract} requires of a subclass, and the reason the whole shared SPI contract
 * is settled here against real provider code.
 *
 * <p>On top of the contract this suite covers what only this provider has: the request each operation actually
 * issues, the two different ways an S3-compatible store can report an absent object, the failure translation
 * for every operation, stream ownership either side of {@link S3ContentStore#get(String)}, the configuration
 * the client is built from - endpoint override, path-style addressing and the choice between configured and
 * default-chain credentials, verified by mocking {@code S3Client.builder()} so that no client is ever really
 * created - and, deliberately, that no credential value can escape into anything the provider says.
 *
 * <p>Every property this suite changes is snapshotted and put back afterwards, because
 * {@code UtilProperties.setPropertyValueInMemory} mutates a cached {@code Properties} object that the whole
 * shared unit-test JVM sees.
 *
 * <p>Four further properties are pinned against a plain Mockito {@link S3Client} rather than the in-memory
 * one, because they are the behaviours a live store cannot be relied upon to reproduce on demand:
 * <ul>
 *   <li>an absent object is reported as absent, but a bucket, endpoint or routing fault is reported as a
 *       store failure rather than as empty content;</li>
 *   <li>static credentials are configured as a pair or not at all, and a half-configured pair is refused
 *       instead of being resolved from somewhere the operator did not choose;</li>
 *   <li>an endpoint that could redirect a request or carry a secret is refused, and what reaches a log line
 *       is only the scheme, host and port;</li>
 *   <li>a client the provider built is closed when the provider is discarded, and a client supplied by a
 *       caller is not.</li>
 * </ul>
 */
public final class S3ContentStoreTests extends ContentStoreBehaviourContract {

    private static final String CONTENT_RESOURCE = "content";
    private static final String BUCKET_PROPERTY = "content.store.s3.bucket";
    private static final String REGION_PROPERTY = "content.store.s3.region";
    private static final String ENDPOINT_PROPERTY = "content.store.s3.endpoint";
    private static final String ACCESS_KEY_ID_PROPERTY = "content.store.s3.access.key.id";
    private static final String SECRET_ACCESS_KEY_PROPERTY = "content.store.s3.secret.access.key";
    private static final String PATH_STYLE_PROPERTY = "content.store.s3.path.style";
    private static final String CREDENTIALS_PROVIDER_PROPERTY = "content.store.s3.credentials.provider";
    private static final String ENDPOINT_ALLOWLIST_PROPERTY = "content.store.s3.endpoint.allowlist";
    private static final String ALLOW_PLAINTEXT_PROPERTY = "content.store.s3.allow.plaintext.endpoint";
    private static final String MAX_GET_BYTES_PROPERTY = "content.store.max.get.bytes";

    /** The two names {@code content.store.s3.credentials.provider} accepts, and nothing else. */
    private static final String STATIC_CREDENTIALS = "static";
    private static final String DEFAULT_CHAIN_CREDENTIALS = "default-chain";

    private static final String BUCKET = "ofbiz-content-under-test";
    private static final String KEY = "contract/sample.bin";
    private static final String ABSENT_KEY = "contract/nothing-was-ever-stored-here.bin";
    private static final byte[] PAYLOAD = "content that has to survive a round trip".getBytes(StandardCharsets.UTF_8);
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_SERVER_ERROR = 500;
    private static final String REGION = "eu-west-1";
    private static final String ENDPOINT = "https://object-store.example.internal:9000";

    /**
     * Stand-ins for a credential pair, never valid anywhere, chosen so that a leak is unmistakable.
     *
     * <p>They are asserted <em>absent</em> from every message the provider composes and from everything it
     * logs, which is the only way to show that a store failure - whose error reply can echo the request that
     * caused it - cannot turn into a credential disclosure in a log file or an error page.
     */
    private static final String ACCESS_KEY_SENTINEL = "AKIAOFBIZTESTSENTINEL";
    private static final String SECRET_ACCESS_KEY_SENTINEL = "cQ7nEVERLOGTHISsecretSENTINEL9wZ";

    private final Map<String, String> propertySnapshot = new LinkedHashMap<>();

    /**
     * A plain mocked client and the provider around it, rebuilt for every test.
     *
     * <p>This is the second of the two stubbing strategies this suite uses. The in-memory client behind
     * {@link #newEmptyContentStore()} is a storing implementation and answers like a real store; this one
     * answers only what a test tells it to, which is what makes a bucket fault, a status-only reply and a
     * never-completed credential pair reachable at all.
     */
    private S3Client client;
    private S3ContentStore store;

    @BeforeEach
    public void buildProviderAroundAMockedClient() {
        client = mock(S3Client.class);
        store = new S3ContentStore(client, BUCKET);
    }

    @AfterEach
    public void restoreEveryPropertyThisSuiteChanged() {
        propertySnapshot.forEach((qualifiedName, previous) -> {
            int separator = qualifiedName.indexOf('|');
            UtilProperties.setPropertyValueInMemory(qualifiedName.substring(0, separator),
                    qualifiedName.substring(separator + 1), previous);
        });
        propertySnapshot.clear();
    }

    /** Hands the shared behaviour contract the real provider over a store nothing else has written to. */
    @Override
    protected ContentStore newEmptyContentStore() {
        return new S3ContentStore(inMemoryClient(new ConcurrentHashMap<>()), BUCKET);
    }

    /*
     * The round trip, and the requests each operation issues
     */

    @Test
    public void contentSurvivesAFullWriteProbeReadStreamAndRemoveRoundTrip() throws Exception {
        Map<String, byte[]> stored = new ConcurrentHashMap<>();
        S3ContentStore store = new S3ContentStore(inMemoryClient(stored), BUCKET);

        store.put(KEY, PAYLOAD);

        assertTrue(store.exists(KEY), "the object must be reported present straight after the write");
        assertArrayEquals(PAYLOAD, store.get(KEY), "the object must read back byte for byte");
        try (InputStream stream = store.openStream(KEY)) {
            assertArrayEquals(PAYLOAD, stream.readAllBytes(), "and must stream back byte for byte");
        }
        assertArrayEquals(PAYLOAD, stored.get(KEY), "the object really did reach the store rather than a cache");
        store.delete(KEY);
        assertFalse(store.exists(KEY), "the object must be gone after the removal");
        assertThrows(FileNotFoundException.class, () -> store.get(KEY), "and a removed key must behave like an absent one");
    }

    @Test
    public void everyOperationAddressesTheConfiguredBucketAndTheSuppliedKey() throws Exception {
        Map<String, byte[]> stored = new ConcurrentHashMap<>();
        S3Client client = inMemoryClient(stored);
        S3ContentStore store = new S3ContentStore(client, BUCKET);

        store.put(KEY, PAYLOAD);
        store.get(KEY);
        store.exists(KEY);
        store.delete(KEY);

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        ArgumentCaptor<GetObjectRequest> get = ArgumentCaptor.forClass(GetObjectRequest.class);
        ArgumentCaptor<HeadObjectRequest> head = ArgumentCaptor.forClass(HeadObjectRequest.class);
        ArgumentCaptor<DeleteObjectRequest> delete = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(client).putObject(put.capture(), body.capture());
        verify(client).getObject(get.capture());
        // Twice, and deliberately: a whole-object read establishes the object's length from its metadata before
        // transferring anything, so that an object above the read ceiling is refused without its content being
        // fetched at all. The probe accounts for the other one. Both requests are captured, and the assertion
        // below therefore holds for the last of them.
        verify(client, times(2)).headObject(head.capture());
        verify(client).deleteObject(delete.capture());

        // A provider that dropped the bucket, rewrote the key or sent a truncated body would still pass a
        // round-trip test against its own in-memory double; the request itself is what the store will see.
        assertEquals(BUCKET, put.getValue().bucket(), "PutObject must address the configured bucket");
        assertEquals(KEY, put.getValue().key(), "PutObject must use the key it was given");
        try (InputStream sent = body.getValue().contentStreamProvider().newStream()) {
            assertArrayEquals(PAYLOAD, sent.readAllBytes(), "PutObject must carry exactly the supplied bytes");
        }
        assertEquals(BUCKET, get.getValue().bucket(), "GetObject must address the configured bucket");
        assertEquals(KEY, get.getValue().key(), "GetObject must use the key it was given");
        assertEquals(BUCKET, head.getValue().bucket(), "HeadObject must address the configured bucket");
        assertEquals(KEY, head.getValue().key(), "HeadObject must use the key it was given");
        assertEquals(BUCKET, delete.getValue().bucket(), "DeleteObject must address the configured bucket");
        assertEquals(KEY, delete.getValue().key(), "DeleteObject must use the key it was given");
    }

    @Test
    public void aSecondWriteReplacesTheObjectRatherThanIssuingAnAppend() throws Exception {
        Map<String, byte[]> stored = new ConcurrentHashMap<>();
        S3Client client = inMemoryClient(stored);
        S3ContentStore store = new S3ContentStore(client, BUCKET);

        store.put(KEY, PAYLOAD);
        store.put(KEY, "shorter".getBytes(StandardCharsets.UTF_8));

        assertArrayEquals("shorter".getBytes(StandardCharsets.UTF_8), stored.get(KEY),
                "the second write must replace the object in full");
        verify(client, times(2)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    /*
     * Stream ownership across the get / openStream boundary
     */

    @Test
    public void getClosesTheSdkStreamItOpenedRatherThanLeakingTheConnection() throws Exception {
        CloseTrackingInputStream sdkStream = new CloseTrackingInputStream(PAYLOAD);
        S3Client client = mock(S3Client.class);
        when(client.getObject(any(GetObjectRequest.class)))
                .thenReturn(new ResponseInputStream<>(GetObjectResponse.builder().build(), sdkStream));
        S3ContentStore store = new S3ContentStore(client, BUCKET);

        assertArrayEquals(PAYLOAD, store.get(KEY), "get must return the whole object");

        // get() is documented to read the object into memory, so the connection it borrowed has to be given
        // back. A leak here exhausts the SDK connection pool after a few hundred content reads.
        assertTrue(sdkStream.isClosed(), "get must close the SDK response stream it opened");
    }

    @Test
    public void openStreamHandsTheStillOpenSdkStreamToTheCaller() throws Exception {
        CloseTrackingInputStream sdkStream = new CloseTrackingInputStream(PAYLOAD);
        S3Client client = mock(S3Client.class);
        when(client.getObject(any(GetObjectRequest.class)))
                .thenReturn(new ResponseInputStream<>(GetObjectResponse.builder().build(), sdkStream));
        S3ContentStore store = new S3ContentStore(client, BUCKET);

        InputStream handedOver = store.openStream(KEY);

        assertFalse(sdkStream.isClosed(), "openStream must not close the stream it is handing over");
        assertArrayEquals(PAYLOAD, handedOver.readAllBytes(), "the caller must be able to read what it was given");
        handedOver.close();
        assertTrue(sdkStream.isClosed(), "closing the handed-over stream must release the SDK connection");
    }

    @Test
    public void eachOpenStreamIssuesItsOwnGetObjectSoConcurrentReadersNeverShareAStream() throws Exception {
        Map<String, byte[]> stored = new ConcurrentHashMap<>();
        S3Client client = inMemoryClient(stored);
        S3ContentStore store = new S3ContentStore(client, BUCKET);
        store.put(KEY, PAYLOAD);

        try (InputStream first = store.openStream(KEY); InputStream second = store.openStream(KEY)) {
            assertNotSame(first, second, "two readers must never be handed the same stream");
            assertArrayEquals(PAYLOAD, first.readAllBytes(), "the first reader must see the whole object");
            assertArrayEquals(PAYLOAD, second.readAllBytes(), "and draining the first must not truncate the second");
        }
        verify(client, times(2)).getObject(any(GetObjectRequest.class));
    }

    @Test
    public void largeContentIsStoredAndReadBackWithoutTruncation() throws Exception {
        byte[] large = new byte[3 * 1024 * 1024 + 7];
        for (int index = 0; index < large.length; index++) {
            large[index] = (byte) (index % 251);
        }
        S3ContentStore store = new S3ContentStore(inMemoryClient(new ConcurrentHashMap<>()), BUCKET);

        store.put(KEY, large);

        assertArrayEquals(large, store.get(KEY), "a multi-megabyte object must round trip intact through get");
        try (InputStream stream = store.openStream(KEY)) {
            assertArrayEquals(large, stream.readAllBytes(), "and intact through openStream");
        }
    }

    /*
     * The two ways an S3-compatible store can say "there is nothing there"
     */

    @Test
    public void anObjectStoreThatAnswersNoSuchKeyProducesAFileNotFoundIdentifyingTheObjectOpaquely() throws Exception {
        NoSuchKeyException absent = NoSuchKeyException.builder().message("The specified key does not exist.").build();
        S3Client client = mock(S3Client.class);
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(absent);
        S3ContentStore store = new S3ContentStore(client, BUCKET);

        FileNotFoundException fromStream = assertThrows(FileNotFoundException.class, () -> store.openStream(ABSENT_KEY));
        FileNotFoundException fromGet = assertThrows(FileNotFoundException.class, () -> store.get(ABSENT_KEY));

        // FileNotFoundException specifically, because the pre-existing DataResourceWorker call sites dereference
        // the handle they are given without a null check and already handle exactly this signal.
        for (FileNotFoundException reported : List.of(fromStream, fromGet)) {
            // The object and the bucket are identified, but by stable opaque reference rather than by name: a key
            // is caller-supplied text that must not be able to forge a log record, and a bucket name identifies
            // the deployment's storage to anyone who reads the log or an error page.
            assertTrue(reported.getMessage().contains(ContentStoreUtil.reference(ABSENT_KEY)),
                    "the object must be identified, was: " + reported.getMessage());
            assertTrue(reported.getMessage().contains(ContentStoreUtil.reference(BUCKET)),
                    "and so must the bucket, was: " + reported.getMessage());
            assertFalse(reported.getMessage().contains(ABSENT_KEY), "the key must not be echoed, was: " + reported.getMessage());
            assertFalse(reported.getMessage().contains(BUCKET), "nor the bucket name, was: " + reported.getMessage());
            // The store's own report is deliberately NOT chained. Its message is free text from the remote end -
            // an S3 error document echoes the request that caused it, credentials included - and a chained cause
            // is folded into every stack trace and most error pages. Only the SDK type is carried across.
            assertNull(reported.getCause(), "the object store's own diagnostic must not be chained");
            assertTrue(reported.getMessage().contains(NoSuchKeyException.class.getSimpleName()),
                    "the SDK signal is what stays, was: " + reported.getMessage());
            assertFalse(reported.getMessage().contains("The specified key does not exist."),
                    "the remote message must not be reproduced, was: " + reported.getMessage());
        }
    }

    @Test
    public void anObjectStoreThatCanOnlySignalABare404IsUnderstoodJustTheSame() throws Exception {
        // A HEAD or DELETE reply carries no body for the SDK to unmarshal, so a compatible store may be unable to
        // narrow the failure to NoSuchKeyException. Honouring only the narrow type would turn an absent object
        // into a hard read error on those stores.
        S3Exception bare = s3Failure(HTTP_NOT_FOUND, "Not Found");
        S3Client client = mock(S3Client.class);
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(bare);
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(bare);
        when(client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(bare);
        S3ContentStore store = new S3ContentStore(client, BUCKET);

        assertThrows(FileNotFoundException.class, () -> store.get(ABSENT_KEY), "a bare 404 on a read is an absent object");
        assertFalse(store.exists(ABSENT_KEY), "a bare 404 on a probe is simply absence, not a failure");
        store.delete(ABSENT_KEY);
    }

    /*
     * A store that is reachable but failing, and a store that cannot be reached at all
     */

    @Test
    public void aServerSideFailureBecomesAnIoExceptionNamingTheActionForEveryOperation() throws Exception {
        S3Exception failure = s3Failure(HTTP_SERVER_ERROR, "We encountered an internal error.");

        assertEachOperationReportsTheFailure(failure, "a 5xx reply");
    }

    @Test
    public void aTransportFailureThatIsNotAnS3ExceptionBecomesAnIoExceptionForEveryOperation() throws Exception {
        // Nothing narrows this one: no HTTP status, no error document. It must not slip past the S3Exception
        // branches and escape as an unchecked SDK exception through a signature that promises IOException.
        SdkClientException failure = SdkClientException.create("Unable to execute HTTP request: connect timed out");

        assertEachOperationReportsTheFailure(failure, "a transport failure");
    }

    /*
     * Configuration: what the provider reads, and what it builds from it
     */

    @Test
    public void theProviderIsUnusableUntilABucketIsConfiguredAndSaysWhichPropertyIsMissing() {
        // The committed configuration leaves every content.store.s3.* key blank, so this is the state of an
        // unmodified checkout in which somebody has selected s3 without configuring it.
        configure("", "", "", "", "", "", "false");
        S3ContentStore store = new S3ContentStore();

        for (Map.Entry<String, StoreOperation> operation : operations(store).entrySet()) {
            GeneralException unusable = assertThrows(GeneralException.class, () -> operation.getValue().run(),
                    operation.getKey() + " must refuse to run without a bucket");
            assertTrue(unusable.getMessage().contains(BUCKET_PROPERTY),
                    "the message must name " + BUCKET_PROPERTY + ", was: " + unusable.getMessage());
        }
    }

    @Test
    public void theProviderIsUnusableUntilARegionIsConfiguredAndSaysWhichPropertyIsMissing() {
        configure(BUCKET, "", "", "", "", "", "false");
        S3ContentStore store = new S3ContentStore();

        GeneralException unusable = assertThrows(GeneralException.class, () -> store.exists(KEY));

        assertTrue(unusable.getMessage().contains(REGION_PROPERTY),
                "the message must name " + REGION_PROPERTY + ", was: " + unusable.getMessage());
    }

    @Test
    public void aCompatibleStoreIsTargetedThroughEndpointOverrideAndPathStyleAddressing() throws Exception {
        configure(BUCKET, REGION, ENDPOINT, STATIC_CREDENTIALS, ACCESS_KEY_SENTINEL, SECRET_ACCESS_KEY_SENTINEL, "true");
        RecordingClientBuilder recorder = new RecordingClientBuilder();

        try (MockedStatic<S3Client> clients = mockStatic(S3Client.class)) {
            clients.when(S3Client::builder).thenReturn(recorder.builder());
            new S3ContentStore().exists(KEY);
        }

        // These two settings together are the whole reason one client can serve Amazon S3 and an S3-compatible
        // store alike, so both have to reach the builder from configuration rather than being hard-coded.
        verify(recorder.builder()).endpointOverride(URI.create(ENDPOINT));
        verify(recorder.builder()).forcePathStyle(true);
        verify(recorder.builder()).region(Region.of(REGION));
        AwsCredentialsProvider credentials = recorder.credentialsProvider();
        assertInstanceOf(StaticCredentialsProvider.class, credentials,
                "both credential properties were supplied, so the configured pair must be used");
        AwsCredentials resolved = credentials.resolveCredentials();
        assertEquals(ACCESS_KEY_SENTINEL, resolved.accessKeyId(), ACCESS_KEY_ID_PROPERTY + " must reach the client");
        assertEquals(SECRET_ACCESS_KEY_SENTINEL, resolved.secretAccessKey(), SECRET_ACCESS_KEY_PROPERTY + " must reach the client");
    }

    @Test
    public void aBlankEndpointLeavesAmazonsOwnEndpointInPlaceAndPathStyleAddressingOff() throws Exception {
        configure(BUCKET, REGION, "", DEFAULT_CHAIN_CREDENTIALS, "", "", "false");
        RecordingClientBuilder recorder = new RecordingClientBuilder();

        try (MockedStatic<S3Client> clients = mockStatic(S3Client.class)) {
            clients.when(S3Client::builder).thenReturn(recorder.builder());
            new S3ContentStore().exists(KEY);
        }

        // Overriding the endpoint with a blank value would break every Amazon S3 deployment, so a blank endpoint
        // must mean "do not override" rather than "override with nothing".
        verify(recorder.builder(), times(0)).endpointOverride(any(URI.class));
        verify(recorder.builder()).forcePathStyle(false);
    }

    @Test
    public void theAmbientCredentialChainIsUsedOnlyWhenTheDeploymentAsksForItByName() throws Exception {
        // Asking for the ambient chain is a real deployment choice - an instance role, container credentials, a
        // shared profile - and it is honoured. What it may not be is INFERRED: see the refusal test below.
        configure(BUCKET, REGION, "", DEFAULT_CHAIN_CREDENTIALS, "", "", "false");
        RecordingClientBuilder recorder = new RecordingClientBuilder();

        try (MockedStatic<S3Client> clients = mockStatic(S3Client.class)) {
            clients.when(S3Client::builder).thenReturn(recorder.builder());
            new S3ContentStore().exists(KEY);
        }

        assertInstanceOf(DefaultCredentialsProvider.class, recorder.credentialsProvider(),
                "the named ambient chain must be what the client is built with");
    }

    @Test
    public void anIdentitySourceThatIsNotNamedOrThatContradictsTheCredentialsIsRefused() {
        // Every one of these would be accepted by a resolver that INFERRED the identity source from whether a credential
        // happened to be present. Half a pair silently authenticated as the ambient identity - the instance role
        // of whatever host the container ran on - which is a different, usually far more privileged, principal
        // than the one the operator was trying to configure. Inference is exactly what is being removed here.
        Map<String, List<String>> refused = new LinkedHashMap<>();
        refused.put("an unnamed identity source", List.of("", ACCESS_KEY_SENTINEL, SECRET_ACCESS_KEY_SENTINEL));
        refused.put("an unnamed source with no credentials either", List.of("", "", ""));
        refused.put("an unrecognised identity source", List.of("instance-role", "", ""));
        // Near-misses, not different spellings of the same word: the mode IS matched case-insensitively and after
        // trimming, which the test below pins, so only a genuinely different name may be refused here.
        refused.put("a near-miss identity source", List.of("statics", ACCESS_KEY_SENTINEL, SECRET_ACCESS_KEY_SENTINEL));
        refused.put("an underscored identity source", List.of("default_chain", "", ""));
        refused.put("static with no secret access key", List.of(STATIC_CREDENTIALS, ACCESS_KEY_SENTINEL, ""));
        refused.put("static with no access key id", List.of(STATIC_CREDENTIALS, "", SECRET_ACCESS_KEY_SENTINEL));
        refused.put("static with neither", List.of(STATIC_CREDENTIALS, "", ""));
        refused.put("the ambient chain contradicted by an access key id", List.of(DEFAULT_CHAIN_CREDENTIALS, ACCESS_KEY_SENTINEL, ""));
        refused.put("the ambient chain contradicted by a secret access key", List.of(DEFAULT_CHAIN_CREDENTIALS, "", SECRET_ACCESS_KEY_SENTINEL));

        for (Map.Entry<String, List<String>> configuration : refused.entrySet()) {
            List<String> values = configuration.getValue();
            configure(BUCKET, REGION, "", values.get(0), values.get(1), values.get(2), "false");
            S3ContentStore store = new S3ContentStore();

            ContentStoreConfigurationException rejected = assertThrows(ContentStoreConfigurationException.class, () -> store.exists(KEY),
                    configuration.getKey() + " must be refused rather than guessed at");

            assertTrue(rejected.getMessage().contains(CREDENTIALS_PROVIDER_PROPERTY),
                    "the refusal must name the property to set, was: " + rejected.getMessage());
            assertFalse(rejected.getMessage().contains(ACCESS_KEY_SENTINEL),
                    "no access key id may appear in the refusal: " + rejected.getMessage());
            assertFalse(rejected.getMessage().contains(SECRET_ACCESS_KEY_SENTINEL),
                    "no secret access key may appear in the refusal: " + rejected.getMessage());
        }
    }

    @Test
    public void theIdentitySourceIsMatchedWithoutRegardToSpacingOrCaseBecauseAnOperatorTypesIt() throws Exception {
        // An operator writes this value into an environment variable or a properties file by hand, so a capital
        // letter or a trailing space must not be what makes a deployment unusable. The name is a closed enum,
        // not a byte string, and this is the boundary the refusals above are measured against.
        for (String spelling : List.of("static", "Static", "STATIC", " static ")) {
            configure(BUCKET, REGION, "", spelling, ACCESS_KEY_SENTINEL, SECRET_ACCESS_KEY_SENTINEL, "false");
            RecordingClientBuilder recorder = new RecordingClientBuilder();

            try (MockedStatic<S3Client> clients = mockStatic(S3Client.class)) {
                clients.when(S3Client::builder).thenReturn(recorder.builder());
                new S3ContentStore().exists(KEY);
            }

            assertInstanceOf(StaticCredentialsProvider.class, recorder.credentialsProvider(),
                    "[" + spelling + "] must select the configured credential pair");
        }
        for (String spelling : List.of("default-chain", "Default-Chain", " DEFAULT-CHAIN ")) {
            configure(BUCKET, REGION, "", spelling, "", "", "false");
            RecordingClientBuilder recorder = new RecordingClientBuilder();

            try (MockedStatic<S3Client> clients = mockStatic(S3Client.class)) {
                clients.when(S3Client::builder).thenReturn(recorder.builder());
                new S3ContentStore().exists(KEY);
            }

            assertInstanceOf(DefaultCredentialsProvider.class, recorder.credentialsProvider(),
                    "[" + spelling + "] must select the ambient chain");
        }
    }

    /*
     * Which endpoint the provider may be pointed at
     */

    @Test
    public void anEndpointThatNamesAHostNoOneListedIsRefused() {
        configure(BUCKET, REGION, ENDPOINT, DEFAULT_CHAIN_CREDENTIALS, "", "", "false");
        // One mis-set property is all it takes to redirect every byte of a deployment's content to a host the
        // operator never intended, and a signed request carries an identity with it. The allowlist is what makes
        // that a two-property change rather than a one-property change.
        override(ENDPOINT_ALLOWLIST_PROPERTY, "some-other-store.example.internal");
        S3ContentStore store = new S3ContentStore();

        ContentStoreConfigurationException refused =
                assertThrows(ContentStoreConfigurationException.class, () -> store.exists(KEY));

        assertTrue(refused.getMessage().contains(ENDPOINT_ALLOWLIST_PROPERTY),
                "the refusal must name the allowlist, was: " + refused.getMessage());
        assertFalse(refused.getMessage().contains(ENDPOINT),
                "the endpoint names an internal host and must not be echoed, was: " + refused.getMessage());
        assertTrue(refused.getMessage().contains(ContentStoreUtil.reference(ENDPOINT)),
                "it must be identified by reference instead, was: " + refused.getMessage());
    }

    @Test
    public void anAllowlistIsReadAsAListAndMatchedWithoutRegardToSpacingOrCase() throws Exception {
        configure(BUCKET, REGION, ENDPOINT, DEFAULT_CHAIN_CREDENTIALS, "", "", "false");
        // An operator writes a list by hand, so a stray space or a capital letter must not be what refuses a
        // legitimate endpoint - the list is a set of host names, not a set of strings to compare byte for byte.
        override(ENDPOINT_ALLOWLIST_PROPERTY, " first.example.internal , OBJECT-STORE.Example.Internal ,, ");
        RecordingClientBuilder recorder = new RecordingClientBuilder();

        try (MockedStatic<S3Client> clients = mockStatic(S3Client.class)) {
            clients.when(S3Client::builder).thenReturn(recorder.builder());
            new S3ContentStore().exists(KEY);
        }

        verify(recorder.builder()).endpointOverride(URI.create(ENDPOINT));
    }

    @Test
    public void aPlaintextEndpointIsRefusedUntilTheDevelopmentOnlyEscapeIsSetExplicitly() throws Exception {
        String plaintext = "http://localhost:9000";
        configure(BUCKET, REGION, plaintext, DEFAULT_CHAIN_CREDENTIALS, "", "", "true");
        S3ContentStore store = new S3ContentStore();

        ContentStoreConfigurationException refused = assertThrows(ContentStoreConfigurationException.class, () -> store.exists(KEY),
                "http must not be usable by default");
        assertTrue(refused.getMessage().contains(ALLOW_PLAINTEXT_PROPERTY),
                "the refusal must name the escape it is pointing at, was: " + refused.getMessage());

        // And the escape has to actually work, or a developer running MinIO on a laptop could not use the
        // provider at all and would end up switching the production check off some other way.
        override(ALLOW_PLAINTEXT_PROPERTY, "true");
        RecordingClientBuilder recorder = new RecordingClientBuilder();
        try (MockedStatic<S3Client> clients = mockStatic(S3Client.class)) {
            clients.when(S3Client::builder).thenReturn(recorder.builder());
            new S3ContentStore().exists(KEY);
        }
        verify(recorder.builder()).endpointOverride(URI.create(plaintext));
    }

    @Test
    public void everyInstanceMetadataAddressIsRefusedNoMatterWhatTheConfigurationSays() {
        // The one refusal no setting can lift: that address answers with credentials for the whole instance, so
        // an endpoint pointed at it turns a content read into a credential read. The bracketed IPv6 form is here
        // for a specific reason - URI.getHost() KEEPS the brackets, so a check that compared its answer straight
        // against an unbracketed list would have let this exact address through.
        List<String> metadata = List.of("http://169.254.169.254/", "https://169.254.169.254/latest/meta-data/",
                "http://[fd00:ec2::254]/", "http://169.254.170.2/v2/credentials/", "http://metadata.google.internal/");

        for (String address : metadata) {
            configure(BUCKET, REGION, address, DEFAULT_CHAIN_CREDENTIALS, "", "", "false");
            // Deliberately allow-listed AND with the plaintext escape open, so that nothing else can be what
            // refuses it: this is the refusal that outranks every other setting.
            override(ENDPOINT_ALLOWLIST_PROPERTY, hostOf(address) + ",169.254.169.254,fd00:ec2::254,[fd00:ec2::254]");
            override(ALLOW_PLAINTEXT_PROPERTY, "true");
            S3ContentStore store = new S3ContentStore();

            ContentStoreConfigurationException refused = assertThrows(ContentStoreConfigurationException.class, () -> store.exists(KEY),
                    address + " must be refused in every configuration");

            assertTrue(refused.getMessage().contains("cloud instance metadata service"),
                    "expected the metadata refusal for " + address + ", was: " + refused.getMessage());
        }
    }

    @Test
    public void anEndpointThatIsNotAPlainAbsoluteAddressIsRefused() {
        Map<String, String> refusals = new LinkedHashMap<>();
        refusals.put("ht tp://not a uri", "it is not a valid URI");
        refusals.put("file:///etc/passwd", "its scheme must be https");
        refusals.put("s3://object-store.example.internal", "its scheme must be https");
        refusals.put("object-store.example.internal:9000", "its scheme must be https");
        refusals.put("https:///no-host-at-all", "it names no host");
        refusals.put("https://user:secret@object-store.example.internal", "it carries user information");
        refusals.put("https://object-store.example.internal?probe=1", "it carries a query or fragment");
        refusals.put("https://object-store.example.internal#fragment", "it carries a query or fragment");

        for (Map.Entry<String, String> refusal : refusals.entrySet()) {
            configure(BUCKET, REGION, refusal.getKey(), DEFAULT_CHAIN_CREDENTIALS, "", "", "false");
            // Allow-listed and plaintext-permitted, so the shape of the address is the only thing left to refuse it.
            override(ENDPOINT_ALLOWLIST_PROPERTY, "object-store.example.internal,no-host-at-all");
            override(ALLOW_PLAINTEXT_PROPERTY, "true");
            S3ContentStore store = new S3ContentStore();

            ContentStoreConfigurationException refused = assertThrows(ContentStoreConfigurationException.class, () -> store.exists(KEY),
                    refusal.getKey() + " must be refused");

            assertTrue(refused.getMessage().contains(refusal.getValue()),
                    "expected [" + refusal.getValue() + "] for " + refusal.getKey() + ", was: " + refused.getMessage());
        }
    }

    @Test
    public void aMalformedEndpointIsReportedAsAConfigurationProblemRatherThanAnSdkStackTrace() {
        configure(BUCKET, REGION, "ht tp://not a uri", DEFAULT_CHAIN_CREDENTIALS, "", "", "false");
        S3ContentStore store = new S3ContentStore();

        // A configuration mistake is not a runtime failure of the store, and it must not surface as an SDK
        // stack trace an operator has to decode. ContentStoreConfigurationException is the type this package
        // uses for "the deployment is not configured for what it was asked to do", and it is a checked
        // GeneralException, so every SPI method declares it and no caller can overlook it.
        ContentStoreConfigurationException misconfigured =
                assertThrows(ContentStoreConfigurationException.class, () -> store.exists(KEY));

        assertTrue(misconfigured.getMessage().contains(ENDPOINT_PROPERTY),
                "an operator has to be told which setting to fix, was: " + misconfigured.getMessage());
        assertTrue(misconfigured.getMessage().contains("not a valid URI"),
                "and what is wrong with it, was: " + misconfigured.getMessage());
    }

    @Test
    public void theClientIsBuiltLazilyAtMostOnceAndThenReusedByEveryOperation() throws Exception {
        configure(BUCKET, REGION, ENDPOINT, DEFAULT_CHAIN_CREDENTIALS, "", "", "false");
        RecordingClientBuilder recorder = new RecordingClientBuilder();

        try (MockedStatic<S3Client> clients = mockStatic(S3Client.class)) {
            clients.when(S3Client::builder).thenReturn(recorder.builder());
            S3ContentStore store = new S3ContentStore();
            // Nothing has been built yet: selecting the provider must not resolve a credential or open a
            // connection, which is what lets an unconfigured s3 selection fail on use rather than at start-up.
            verify(recorder.builder(), times(0)).build();

            store.put(KEY, PAYLOAD);
            store.exists(KEY);
            store.delete(KEY);

            verify(recorder.builder(), times(1)).build();
        }
    }

    @Test
    public void theLazyClientBuildIsSerialisedSoTwoRequestThreadsCannotRaceIt() throws Exception {
        Method requireClient = S3ContentStore.class.getDeclaredMethod("requireClient");

        // The field is written on first use from whichever request thread gets there first. Without this the two
        // threads can build two clients and publish one of them unsafely.
        assertTrue(Modifier.isSynchronized(requireClient.getModifiers()),
                "requireClient must be synchronized, or the lazily built client can be raced");
    }

    @Test
    public void everyOperationStaysCorrectWhenManyRequestThreadsShareOneProvider() throws Exception {
        int threads = 16;
        S3ContentStore store = new S3ContentStore(inMemoryClient(new ConcurrentHashMap<>()), BUCKET);
        ExecutorService workers = Executors.newFixedThreadPool(threads);
        try {
            List<Future<byte[]>> results = new ArrayList<>();
            for (int index = 0; index < threads; index++) {
                String key = "contract/concurrent-" + index + ".bin";
                byte[] payload = ("payload-" + index).getBytes(StandardCharsets.UTF_8);
                results.add(workers.submit(() -> {
                    store.put(key, payload);
                    assertTrue(store.exists(key), key + " must be present to the thread that wrote it");
                    return store.get(key);
                }));
            }
            for (int index = 0; index < threads; index++) {
                assertArrayEquals(("payload-" + index).getBytes(StandardCharsets.UTF_8), results.get(index).get(30, TimeUnit.SECONDS),
                        "every thread must read back exactly what it wrote");
            }
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(30, TimeUnit.SECONDS), "the worker pool must terminate");
        }
    }

    /*
     * The whole-object read ceiling
     */

    @Test
    public void getRefusesAnObjectAboveTheCeilingWhileOpenStreamStillStreamsIt() throws Exception {
        override(MAX_GET_BYTES_PROPERTY, "16");
        Map<String, byte[]> stored = new ConcurrentHashMap<>();
        S3ContentStore store = new S3ContentStore(inMemoryClient(stored), BUCKET);

        // A write is deliberately not subject to the ceiling, so the oversized object can be put there at all;
        // what the ceiling protects is the heap of the instance that later reads it.
        store.put("contract/at-the-ceiling.bin", new byte[16]);
        store.put("contract/above-the-ceiling.bin", new byte[17]);

        assertEquals(16, store.get("contract/at-the-ceiling.bin").length, "an object exactly at the ceiling must still be read");

        IOException refused = assertThrows(IOException.class, () -> store.get("contract/above-the-ceiling.bin"));
        assertFalse(refused instanceof FileNotFoundException, "an oversized object is present, not absent");
        assertTrue(refused.getMessage().contains("content.store.max.get.bytes"),
                "the refusal must name the property an operator would change, was: " + refused.getMessage());
        assertTrue(refused.getMessage().contains(ContentStoreUtil.reference("contract/above-the-ceiling.bin")),
                "and must identify the object by reference, was: " + refused.getMessage());
        assertFalse(refused.getMessage().contains("contract/above-the-ceiling.bin"),
                "without echoing the key, was: " + refused.getMessage());

        // The escape hatch the refusal points at has to work, or the ceiling would make oversized content
        // unreachable rather than merely unbuffered.
        try (InputStream stream = store.openStream("contract/above-the-ceiling.bin")) {
            assertEquals(17, stream.readAllBytes().length, "openStream must stream what get refuses");
        }
    }

    @Test
    public void anObjectThatUnderReportsItsOwnLengthIsStillCutOffAtTheCeiling() throws Exception {
        override(MAX_GET_BYTES_PROPERTY, "16");
        // The declared length is what a compatible store CLAIMS, and a store that is lying, buggy or streaming a
        // chunked reply can claim anything - including nothing at all. Trusting it alone would leave the heap
        // exposed to exactly the case the ceiling exists for, so the limit is enforced again while reading.
        CloseTrackingInputStream body = new CloseTrackingInputStream(new byte[64]);
        S3Client client = mock(S3Client.class);
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(
                new ResponseInputStream<>(GetObjectResponse.builder().contentLength(4L).build(), body));
        S3ContentStore store = new S3ContentStore(client, BUCKET);

        IOException refused = assertThrows(IOException.class, () -> store.get(KEY));

        assertTrue(refused.getMessage().contains("content.store.max.get.bytes"),
                "the refusal must still name the ceiling, was: " + refused.getMessage());
        assertTrue(body.isClosed(), "and the borrowed SDK connection must be given back even on a refusal");
    }

    @Test
    public void anObjectThatDeclaresNoLengthAtAllIsStillReadUpToTheCeiling() throws Exception {
        override(MAX_GET_BYTES_PROPERTY, "64");
        // A reply with no Content-Length must not be treated as a reason to refuse the read outright, or every
        // chunked-transfer compatible store would become unreadable.
        S3Client client = mock(S3Client.class);
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(
                new ResponseInputStream<>(GetObjectResponse.builder().build(), new CloseTrackingInputStream(PAYLOAD)));
        S3ContentStore store = new S3ContentStore(client, BUCKET);

        assertArrayEquals(PAYLOAD, store.get(KEY), "an object below the ceiling must be read even with no declared length");
    }

    /*
     * Nothing credential-derived may escape
     */

    @Test
    public void aStoreFailureIsReducedToIdentifiersThatCorrelateWithoutReproducingAnythingRemote() throws Exception {
        // Everything a server-side diagnostic is actually useful for - which store, which request, what class of
        // failure - is a stable identifier. Everything that makes it dangerous is free text. This pins the split.
        AwsErrorDetails details = AwsErrorDetails.builder().errorCode("AccessDenied")
                .errorMessage("Access Denied for arn:aws:iam::123456789012:role/leaked-role-name").build();
        S3Exception detailed = (S3Exception) S3Exception.builder().statusCode(HTTP_SERVER_ERROR)
                .message("Access Denied for arn:aws:iam::123456789012:role/leaked-role-name")
                .awsErrorDetails(details).requestId("REQ-0123456789").extendedRequestId("EXT-9876543210").build();
        S3Client client = mock(S3Client.class);
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(detailed);
        S3ContentStore store = new S3ContentStore(client, BUCKET);

        IOException reported = assertThrows(IOException.class, () -> store.get(KEY));

        String message = reported.getMessage();
        assertTrue(message.contains("status=" + HTTP_SERVER_ERROR), "the HTTP status is stable and belongs, was: " + message);
        assertTrue(message.contains("code=AccessDenied"), "the error code is a closed vocabulary and belongs, was: " + message);
        assertTrue(message.contains("requestId=REQ-0123456789"), "the request id is how an operator correlates, was: " + message);
        assertTrue(message.contains("extendedRequestId=EXT-9876543210"), "and so is the extended one, was: " + message);
        // The free-text half: the remote message names a principal here, and on a signature failure it names a
        // credential. Neither the exception message nor a chained cause may carry it.
        assertFalse(message.contains("leaked-role-name"), "the remote error message must not be reproduced, was: " + message);
        assertFalse(message.contains("arn:aws:iam"), "nor anything it embedded, was: " + message);
        assertNull(reported.getCause(), "and it must not arrive through a chained cause either");
    }

    @Test
    public void noCredentialValueReachesAMessageTheProviderComposes() throws Exception {
        configure(BUCKET, REGION, ENDPOINT, STATIC_CREDENTIALS, ACCESS_KEY_SENTINEL, SECRET_ACCESS_KEY_SENTINEL, "true");
        // An object store echoes the offending request back in its error document, so the failure this provider
        // has to wrap can itself carry a credential. Keeping it out of the message is not enough, because a
        // chained cause is folded into every stack trace and most error pages, which is why nothing is chained.
        S3Exception echoing = s3Failure(HTTP_SERVER_ERROR,
                "SignatureDoesNotMatch for " + ACCESS_KEY_SENTINEL + " using " + SECRET_ACCESS_KEY_SENTINEL);
        S3Client client = mock(S3Client.class);
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(echoing);
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(echoing);
        when(client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(echoing);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(echoing);
        S3ContentStore store = new S3ContentStore(client, BUCKET);

        for (Map.Entry<String, StoreOperation> operation : operations(store).entrySet()) {
            IOException reported = assertThrows(IOException.class, () -> operation.getValue().run(),
                    operation.getKey() + " must report the failure as an IOException");
            assertFalse(reported.getMessage().contains(ACCESS_KEY_SENTINEL),
                    operation.getKey() + " leaked an access key id: " + reported.getMessage());
            assertFalse(reported.getMessage().contains(SECRET_ACCESS_KEY_SENTINEL),
                    operation.getKey() + " leaked a secret access key: " + reported.getMessage());
            assertNull(reported.getCause(), operation.getKey() + " chained the credential-bearing diagnostic as a cause");
            // Non-vacuous: the sanitized summary really is there, so the credential was removed from a message
            // that was composed rather than from a message that was never composed at all.
            assertTrue(reported.getMessage().contains("status=" + HTTP_SERVER_ERROR),
                    "the stable HTTP status must survive sanitisation, was: " + reported.getMessage());
        }
    }

    @Test
    public void noCredentialValueReachesTheLogWhenTheClientIsBuilt() throws Exception {
        configure(BUCKET, REGION, ENDPOINT, STATIC_CREDENTIALS, ACCESS_KEY_SENTINEL, SECRET_ACCESS_KEY_SENTINEL, "true");
        RecordingClientBuilder recorder = new RecordingClientBuilder();
        List<String> logged;

        try (CapturedLog log = CapturedLog.attach(); MockedStatic<S3Client> clients = mockStatic(S3Client.class)) {
            clients.when(S3Client::builder).thenReturn(recorder.builder());
            new S3ContentStore().exists(KEY);
            logged = log.messages();
        }

        // Non-vacuous: the line really was emitted, and it reports where the credentials came FROM rather than
        // what they are. A configuration summary is worth logging; a credential never is.
        String ready = logged.stream().filter(message -> message.contains("Content store provider [s3] ready")).findFirst()
                .orElseThrow(() -> new AssertionError("the provider must report its configuration once, saw: " + logged));
        assertTrue(ready.contains("credentials [" + STATIC_CREDENTIALS + "]"),
                "the credential SOURCE belongs in the log, was: " + ready);
        for (String message : logged) {
            assertFalse(message.contains(ACCESS_KEY_SENTINEL), "an access key id reached the log: " + message);
            assertFalse(message.contains(SECRET_ACCESS_KEY_SENTINEL), "a secret access key reached the log: " + message);
        }
    }

    /*
     * Helpers
     */

    /** Drives every operation against a client that always fails, and checks each wraps the failure identically. */
    private void assertEachOperationReportsTheFailure(RuntimeException failure, String description) throws Exception {
        S3Client client = mock(S3Client.class);
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(failure);
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(failure);
        when(client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(failure);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(failure);
        S3ContentStore store = new S3ContentStore(client, BUCKET);

        for (Map.Entry<String, StoreOperation> operation : operations(store).entrySet()) {
            IOException reported = assertThrows(IOException.class, () -> operation.getValue().run(),
                    operation.getKey() + " must translate " + description + " into an IOException");
            assertFalse(reported instanceof FileNotFoundException,
                    operation.getKey() + " must not mistake " + description + " for an absent object");
            // Identified opaquely, never quoted: see the absent-object test for why the key and the bucket are
            // reduced to stable references and the store's own diagnostic is not chained.
            assertTrue(reported.getMessage().contains(ContentStoreUtil.reference(KEY)),
                    "the object must be identified, was: " + reported.getMessage());
            assertTrue(reported.getMessage().contains(ContentStoreUtil.reference(BUCKET)),
                    "and so must the bucket, was: " + reported.getMessage());
            assertFalse(reported.getMessage().contains(KEY), "the key must not be echoed, was: " + reported.getMessage());
            assertFalse(reported.getMessage().contains(BUCKET), "nor the bucket name, was: " + reported.getMessage());
            assertNull(reported.getCause(), "the object store's own diagnostic must not be chained");
            assertTrue(reported.getMessage().contains(failure.getClass().getSimpleName()),
                    "the SDK signal is what stays, was: " + reported.getMessage());
            assertTrue(reported.getMessage().contains("correlate the request"),
                    "and the operator must be told where the detail is, was: " + reported.getMessage());
        }
    }

    /** All five SPI operations, each reduced to a no-argument call, so one loop can drive the whole surface. */
    private static Map<String, StoreOperation> operations(ContentStore store) {
        Map<String, StoreOperation> byName = new LinkedHashMap<>();
        byName.put("put", () -> store.put(KEY, PAYLOAD));
        byName.put("get", () -> store.get(KEY));
        byName.put("openStream", () -> store.openStream(KEY));
        byName.put("exists", () -> store.exists(KEY));
        byName.put("delete", () -> store.delete(KEY));
        return byName;
    }

    /**
     * Writes every {@code content.store.s3.*} value the provider reads, remembering what each one held.
     *
     * <p>Two of the nine are derived rather than passed, so that the ordinary case reads as one line and only
     * the tests that are ABOUT them have to mention them. The endpoint allowlist is derived from the endpoint
     * itself, which is what a correctly configured deployment holds, and the plaintext escape is left switched
     * off. A test about the allowlist or about that escape overrides the property explicitly afterwards, which
     * is also what makes those tests visibly about it.
     *
     * @param bucket the bucket name
     * @param region the region name
     * @param endpoint the endpoint override, or blank for Amazon S3's own endpoint
     * @param credentialsMode the value of {@code content.store.s3.credentials.provider}
     * @param accessKeyId the access key id, or blank
     * @param secretAccessKey the secret access key, or blank
     * @param pathStyle {@code "true"} or {@code "false"} for path-style addressing
     */
    private void configure(String bucket, String region, String endpoint, String credentialsMode,
            String accessKeyId, String secretAccessKey, String pathStyle) {
        override(BUCKET_PROPERTY, bucket);
        override(REGION_PROPERTY, region);
        override(ENDPOINT_PROPERTY, endpoint);
        override(ENDPOINT_ALLOWLIST_PROPERTY, hostOf(endpoint));
        override(ALLOW_PLAINTEXT_PROPERTY, "false");
        override(CREDENTIALS_PROVIDER_PROPERTY, credentialsMode);
        override(ACCESS_KEY_ID_PROPERTY, accessKeyId);
        override(SECRET_ACCESS_KEY_PROPERTY, secretAccessKey);
        override(PATH_STYLE_PROPERTY, pathStyle);
    }

    /**
     * The host of an endpoint, so a configuration is allow-listed for exactly the endpoint it names.
     *
     * @param endpoint the endpoint to take the host of
     * @return the host, or an empty string when the endpoint is blank or does not parse
     */
    private static String hostOf(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "";
        }
        try {
            String host = new URI(endpoint.trim()).getHost();
            return host == null ? "" : host;
        } catch (URISyntaxException malformed) {
            // A test supplying a malformed endpoint is testing the refusal of it, and no allowlist can be
            // derived from something that does not parse. The endpoint check refuses before the list is read.
            return "";
        }
    }

    private void override(String name, String value) {
        propertySnapshot.computeIfAbsent(CONTENT_RESOURCE + "|" + name,
                key -> UtilProperties.getPropertyValue(CONTENT_RESOURCE, name));
        UtilProperties.setPropertyValueInMemory(CONTENT_RESOURCE, name, value);
    }

    /**
     * Builds a service-side failure carrying an HTTP status.
     *
     * <p>The cast is unavoidable rather than careless: {@code S3Exception.Builder} covariantly overrides every
     * setter but declares no {@code build()} of its own, so the inherited {@code AwsServiceException.Builder}
     * signature is what the chain ends on even though an {@code S3Exception} is what it constructs.
     *
     * @param statusCode the HTTP status the store replied with
     * @param message the message the store supplied
     * @return the failure an SDK call would raise
     */
    private static S3Exception s3Failure(int statusCode, String message) {
        return (S3Exception) S3Exception.builder().statusCode(statusCode).message(message).build();
    }

    /**
     * A client whose four object operations are backed by the supplied map.
     *
     * <p>Only the SDK boundary is replaced: the provider under test still validates keys, still builds real
     * requests, still translates failures and still owns its streams. An absent object is reported the way a
     * store with a full error document reports it on a read and the way a store with no reply body reports it on
     * a probe, so both signals are exercised by the shared behaviour contract.
     */
    private static S3Client inMemoryClient(Map<String, byte[]> objects) {
        S3Client client = mock(S3Client.class);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(invocation -> {
            PutObjectRequest request = invocation.getArgument(0);
            RequestBody body = invocation.getArgument(1);
            try (InputStream content = body.contentStreamProvider().newStream()) {
                objects.put(request.key(), content.readAllBytes());
            }
            return PutObjectResponse.builder().build();
        });
        when(client.getObject(any(GetObjectRequest.class))).thenAnswer(invocation -> {
            GetObjectRequest request = invocation.getArgument(0);
            byte[] stored = objects.get(request.key());
            if (stored == null) {
                throw NoSuchKeyException.builder().message("The specified key does not exist.").build();
            }
            return new ResponseInputStream<>(GetObjectResponse.builder().contentLength((long) stored.length).build(),
                    new ByteArrayInputStream(stored));
        });
        when(client.headObject(any(HeadObjectRequest.class))).thenAnswer(invocation -> {
            HeadObjectRequest request = invocation.getArgument(0);
            byte[] stored = objects.get(request.key());
            if (stored == null) {
                throw s3Failure(HTTP_NOT_FOUND, "Not Found");
            }
            return HeadObjectResponse.builder().contentLength((long) stored.length).build();
        });
        when(client.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(invocation -> {
            DeleteObjectRequest request = invocation.getArgument(0);
            objects.remove(request.key());
            return DeleteObjectResponse.builder().build();
        });
        return client;
    }

    /** One {@link ContentStore} operation reduced to a no-argument call. */
    private interface StoreOperation {
        /**
         * Runs the operation.
         *
         * @throws Exception whatever the operation raises
         */
        void run() throws Exception;
    }

    /** An input stream that remembers whether it was closed, so stream ownership can be asserted. */
    private static final class CloseTrackingInputStream extends FilterInputStream {

        private boolean closed;

        CloseTrackingInputStream(byte[] content) {
            super(new ByteArrayInputStream(content));
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        boolean isClosed() {
            return closed;
        }
    }

    /**
     * A stubbed {@link S3ClientBuilder} that records what the provider configured on it.
     *
     * <p>Mocking the builder is what keeps this suite offline: the provider walks its whole real configuration
     * path - region, endpoint, addressing style, credential choice - and hands the result to a builder that
     * yields a stubbed client instead of opening anything.
     */
    private static final class RecordingClientBuilder {

        private final S3ClientBuilder builder = mock(S3ClientBuilder.class, RETURNS_SELF);
        private final ArgumentCaptor<AwsCredentialsProvider> credentials = ArgumentCaptor.forClass(AwsCredentialsProvider.class);

        RecordingClientBuilder() {
            when(builder.build()).thenReturn(mock(S3Client.class));
        }

        S3ClientBuilder builder() {
            return builder;
        }

        AwsCredentialsProvider credentialsProvider() {
            verify(builder).credentialsProvider(credentials.capture());
            return credentials.getValue();
        }
    }

    /**
     * Captures everything logged while it is open, so that a log line can be asserted on rather than assumed.
     *
     * <p>The root logger of the shipped configuration is already at {@code all} and carries no per-logger
     * override, so attaching here sees every message the provider emits. The appender is removed again on close,
     * which matters because the whole unit tier shares one logging configuration.
     */
    private static final class CapturedLog implements AutoCloseable {

        private final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        private final LoggerContext context;
        private final LoggerConfig rootLogger;
        private final AbstractAppender appender;
        private final Level previousLevel;

        private CapturedLog() {
            List<String> sink = messages;
            appender = new AbstractAppender("ofbizContentStoreLogCapture", null, PatternLayout.createDefaultLayout(), true,
                    Property.EMPTY_ARRAY) {
                @Override
                public void append(LogEvent event) {
                    sink.add(event.getMessage().getFormattedMessage());
                }
            };
            appender.start();
            context = (LoggerContext) LogManager.getContext(false);
            Configuration configuration = context.getConfiguration();
            configuration.addAppender(appender);
            rootLogger = configuration.getRootLogger();
            previousLevel = rootLogger.getLevel();
            rootLogger.setLevel(Level.ALL);
            rootLogger.addAppender(appender, Level.ALL, null);
            context.updateLoggers();
        }

        static CapturedLog attach() {
            return new CapturedLog();
        }

        List<String> messages() {
            return List.copyOf(messages);
        }

        @Override
        public void close() {
            rootLogger.removeAppender(appender.getName());
            rootLogger.setLevel(previousLevel);
            context.updateLoggers();
            appender.stop();
        }
    }

    /*
     * Against a plain mocked client: absence versus store failure, credential selection, endpoint
     * validation and client ownership
     */

    @Test
    public void putSendsExactlyTheSuppliedBytesToTheConfiguredBucketAndKey() throws Exception {
        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);

        store.put(KEY, PAYLOAD);

        verify(client).putObject(request.capture(), body.capture());
        assertEquals(BUCKET, request.getValue().bucket());
        assertEquals(KEY, request.getValue().key());
        assertArrayEquals(PAYLOAD, body.getValue().contentStreamProvider().newStream().readAllBytes());
    }

    @Test
    public void getReadsBackExactlyTheStoredBytes() throws Exception {
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(responseStream(PAYLOAD));

        assertArrayEquals(PAYLOAD, store.get(KEY));
    }

    @Test
    public void aConfiguredBucketIsUsedWithoutSurroundingWhitespace() throws Exception {
        S3ContentStore padded = new S3ContentStore(client, "  " + BUCKET + "\t");
        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);

        padded.put(KEY, PAYLOAD);

        verify(client).putObject(request.capture(), any(RequestBody.class));
        assertEquals(BUCKET, request.getValue().bucket());
    }

    @Test
    public void everyOperationRejectsAnUnusableKeyBeforeTheStoreIsTouched() {
        for (String unusable : new String[] {null, ""}) {
            assertThrows(GeneralException.class, () -> store.put(unusable, PAYLOAD));
            assertThrows(GeneralException.class, () -> store.get(unusable));
            assertThrows(GeneralException.class, () -> store.openStream(unusable));
            assertThrows(GeneralException.class, () -> store.exists(unusable));
            assertThrows(GeneralException.class, () -> store.delete(unusable));
        }
        verifyNoInteractions(client);
    }

    @Test
    public void putRejectsNullContentBeforeTheStoreIsTouched() {
        assertThrows(GeneralException.class, () -> store.put(KEY, null));
        verifyNoInteractions(client);
    }

    @Test
    public void aReadOfAnAbsentObjectIsReportedAsAbsentContent() {
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());

        assertThrows(FileNotFoundException.class, () -> store.openStream(KEY));
        assertThrows(FileNotFoundException.class, () -> store.get(KEY));
    }

    @Test
    public void aReadIsReportedAsAbsentContentForAnObjectAbsenceErrorCode() {
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(codedFailure("NoSuchKey", HTTP_NOT_FOUND));

        assertThrows(FileNotFoundException.class, () -> store.openStream(KEY));
    }

    @Test
    public void aReadFromAMissingBucketIsReportedAsAStoreFailureRatherThanAbsentContent() {
        when(client.getObject(any(GetObjectRequest.class)))
                .thenThrow(NoSuchBucketException.builder().statusCode(HTTP_NOT_FOUND).build());

        IOException failure = assertThrows(IOException.class, () -> store.openStream(KEY));
        assertEquals(IOException.class, failure.getClass(), "a bucket fault is not an absent object");
    }

    @Test
    public void aBucketLevelErrorCodeIsNeverReadAsAbsentContent() {
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(codedFailure("NoSuchBucket", HTTP_NOT_FOUND));

        IOException failure = assertThrows(IOException.class, () -> store.openStream(KEY));
        assertEquals(IOException.class, failure.getClass(), "a bucket fault is not an absent object");
    }

    @Test
    public void anUnqualifiedNotFoundOnAReadIsReportedAsAStoreFailure() {
        // A read reply always carries the store's own error document, so a status with nothing behind it means
        // the request never reached an object at all - the bucket or the endpoint is wrong.
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(statusOnlyFailure(HTTP_NOT_FOUND));

        IOException failure = assertThrows(IOException.class, () -> store.openStream(KEY));
        assertEquals(IOException.class, failure.getClass(), "an unqualified status is not an absent object");
    }

    @Test
    public void theProbeAnswersTrueWhenTheStoreAcknowledgesTheObject() throws Exception {
        assertTrue(store.exists(KEY));
        verify(client).headObject(any(HeadObjectRequest.class));
    }

    @Test
    public void theProbeAnswersFalseForAnAbsentObjectHoweverTheStoreNamesIt() throws Exception {
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().build())
                .thenThrow(codedFailure("NoSuchKey", HTTP_NOT_FOUND))
                // The equivalent code an S3-compatible store reports for the same case.
                .thenThrow(codedFailure("NotFound", HTTP_NOT_FOUND));

        assertFalse(store.exists(KEY));
        assertFalse(store.exists(KEY));
        assertFalse(store.exists(KEY));
    }

    @Test
    public void anUnqualifiedNotFoundOnAProbeIsReportedAsAStoreFailure() {
        // Nothing but a status came back, so the store said nothing about the object: reporting that as "no
        // content here" is exactly how a wrong bucket or endpoint would be hidden behind an empty answer.
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(statusOnlyFailure(HTTP_NOT_FOUND));

        assertThrows(IOException.class, () -> store.exists(KEY));
    }

    @Test
    public void anUnqualifiedNotFoundOnADeleteIsReportedAsAStoreFailure() {
        when(client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(statusOnlyFailure(HTTP_NOT_FOUND));

        assertThrows(IOException.class, () -> store.delete(KEY));
    }

    @Test
    public void theProbeReportsAStoreFailureWhenTheBucketIsWhatCouldNotBeFound() {
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchBucketException.builder().statusCode(HTTP_NOT_FOUND).build());

        assertThrows(IOException.class, () -> store.exists(KEY));
    }

    @Test
    public void theProbeReportsAStoreFailureForARoutingFaultCarryingTheSameStatus() {
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(codedFailure("PermanentRedirect", HTTP_NOT_FOUND));

        assertThrows(IOException.class, () -> store.exists(KEY));
    }

    @Test
    public void deleteTreatsAnAbsentObjectAsSuccess() throws Exception {
        when(client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());

        store.delete(KEY);

        verify(client).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    public void deleteReportsAStoreFailureWhenTheBucketIsWhatCouldNotBeFound() {
        when(client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(NoSuchBucketException.builder().statusCode(HTTP_NOT_FOUND).build());

        assertThrows(IOException.class, () -> store.delete(KEY));
    }

    @Test
    public void bothStaticCredentialsTogetherSelectTheConfiguredPair() throws Exception {
        assertTrue(S3ContentStore.requireCredentialPair("AKIAEXAMPLEID", "example-secret"));
    }

    @Test
    public void neitherCredentialConfiguredSelectsTheAwsDefaultChain() throws Exception {
        assertFalse(S3ContentStore.requireCredentialPair("", ""));
    }

    @Test
    public void aHalfConfiguredCredentialPairIsRefusedNamingOnlyTheProperties() {
        GeneralException noSecret = assertThrows(GeneralException.class, () ->
                S3ContentStore.requireCredentialPair("AKIAEXAMPLEID", ""));
        assertTrue(noSecret.getMessage().contains("content.store.s3.secret.access.key"));
        assertFalse(noSecret.getMessage().contains("AKIAEXAMPLEID"), "no credential value may be reported");

        GeneralException noKeyId = assertThrows(GeneralException.class, () ->
                S3ContentStore.requireCredentialPair("", "example-secret"));
        assertTrue(noKeyId.getMessage().contains("content.store.s3.access.key.id"));
        assertFalse(noKeyId.getMessage().contains("example-secret"), "no credential value may be reported");
    }

    @Test
    public void aUsableEndpointIsAcceptedForBothSchemes() throws Exception {
        assertEquals(URI.create("http://127.0.0.1:9000"), S3ContentStore.usableEndpoint("http://127.0.0.1:9000"));
        assertEquals(URI.create("https://objects.example"), S3ContentStore.usableEndpoint("https://objects.example"));
        assertEquals(URI.create("https://objects.example/prefix"),
                S3ContentStore.usableEndpoint("https://objects.example/prefix"));
    }

    @Test
    public void anEndpointCarryingCredentialsIsRefusedWithoutEchoingTheValue() {
        GeneralException refused = assertThrows(GeneralException.class, () ->
                S3ContentStore.usableEndpoint("https://AKIAEXAMPLEID:example-secret@objects.example:9000"));

        assertTrue(refused.getMessage().contains("content.store.s3.endpoint"));
        assertFalse(refused.getMessage().contains("example-secret"), "a rejected endpoint must not be echoed");
        assertFalse(refused.getMessage().contains("AKIAEXAMPLEID"), "a rejected endpoint must not be echoed");
    }

    @Test
    public void anEndpointThatCannotAddressAnObjectStoreIsRefused() {
        String[] unusable = {"objects.example:9000", "/objects", "objects.example", "ftp://objects.example",
                "file:///var/lib/objects", "https://objects.example?prefix=x", "https://objects.example#fragment",
                "https://objects example:9000", "https://objects.example/\u0001", "https://objects.example/\n"};
        for (String endpoint : unusable) {
            assertThrows(GeneralException.class, () -> S3ContentStore.usableEndpoint(endpoint),
                    "endpoint [" + endpoint + "] cannot address an object store and must be refused");
        }
    }

    @Test
    public void theReportedEndpointKeepsOnlySchemeHostAndPort() {
        assertEquals("https://objects.example:9000", S3ContentStore.endpointMarker(
                URI.create("https://AKIAEXAMPLEID:example-secret@objects.example:9000/prefix?x=1#fragment")));
        assertEquals("http://127.0.0.1", S3ContentStore.endpointMarker(URI.create("http://127.0.0.1")));
        assertEquals("amazon-s3-default", S3ContentStore.endpointMarker(null));
    }

    @Test
    public void everyClientIsBuiltWithBoundedCallAndAttemptCeilings() {
        ClientOverrideConfiguration bounded = S3ContentStore.boundedCallConfiguration();

        Duration call = bounded.apiCallTimeout().orElseThrow();
        Duration attempt = bounded.apiCallAttemptTimeout().orElseThrow();
        assertTrue(!call.isZero() && !call.isNegative(), "a storage call must have a positive ceiling");
        assertTrue(!attempt.isZero() && !attempt.isNegative(), "one attempt must have a positive ceiling");
        assertTrue(attempt.compareTo(call) <= 0, "one attempt may not be allowed to outlive the whole call");
    }

    @Test
    public void aClientSuppliedByACallerIsNeverClosedByTheProvider() {
        store.closeOwnedClient();

        verify(client, never()).close();
    }

    @Test
    public void aClientTheProviderBuiltIsClosedOnceAndTheHookIsIdempotent() throws Exception {
        S3ContentStore configured = new S3ContentStore();
        S3Client owned = mock(S3Client.class);
        injectClient(configured, owned);

        configured.closeOwnedClient();
        configured.closeOwnedClient();

        verify(owned, times(1)).close();
        // The reference was cleared under the lock, so a later operation builds a fresh client rather than
        // reaching for the closed one. Nothing is configured here, so that build stops at the unconfigured
        // bucket - which is also the committed default, and shows the provider is inert until configured.
        GeneralException refused = assertThrows(GeneralException.class, () -> configured.exists(KEY));
        assertTrue(refused.getMessage().contains("content.store.s3.bucket"));
    }

    @Test
    public void anUnconfiguredProviderReportsTheMissingBucketRatherThanReachingAStore() {
        GeneralException refused = assertThrows(GeneralException.class, () -> new S3ContentStore().put(KEY, PAYLOAD));

        assertTrue(refused.getMessage().contains("content.store.s3.bucket"));
    }

    /**
     * Builds the reply an object store gives for a successful read.
     *
     * @param content the bytes the store returns
     * @return the SDK's streamed response, as {@code getObject} hands it over
     */
    private static ResponseInputStream<GetObjectResponse> responseStream(byte[] content) {
        return new ResponseInputStream<>(GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(content)));
    }

    /**
     * Builds a failure that carries a status and nothing else, as a bodyless reply can.
     *
     * @param statusCode the HTTP status the store answered with
     * @return the failure the SDK would surface
     */
    private static S3Exception statusOnlyFailure(int statusCode) {
        S3Exception.Builder builder = S3Exception.builder();
        builder.statusCode(statusCode);
        builder.message("status only");
        // The builder's build() is typed to the general service exception, so the concrete type is asserted
        // here; the object it returns is an S3Exception, which is what the provider catches.
        return (S3Exception) builder.build();
    }

    /**
     * Builds a failure that carries the store's own error code, as a reply with an error document does.
     *
     * @param errorCode the code the store named
     * @param statusCode the HTTP status the store answered with
     * @return the failure the SDK would surface
     */
    private static S3Exception codedFailure(String errorCode, int statusCode) {
        S3Exception.Builder builder = S3Exception.builder();
        builder.statusCode(statusCode);
        builder.awsErrorDetails(AwsErrorDetails.builder().errorCode(errorCode).build());
        return (S3Exception) builder.build();
    }

    /**
     * Publishes a client into a provider that would otherwise build its own on first use.
     *
     * <p>Reflection is used deliberately and only here: the point of the test is the ownership rule applied to
     * a client the provider itself created, and that rule cannot be observed through the seam constructor,
     * which exists precisely to hand ownership to the caller.
     *
     * @param target the provider to publish into
     * @param injected the client the provider is to treat as its own
     * @throws ReflectiveOperationException if the field cannot be reached, which would mean the provider no
     *     longer holds its client the way this test assumes
     */
    private static void injectClient(S3ContentStore target, S3Client injected) throws ReflectiveOperationException {
        Field field = S3ContentStore.class.getDeclaredField("s3Client");
        field.setAccessible(true);
        field.set(target, injected);
    }
}
