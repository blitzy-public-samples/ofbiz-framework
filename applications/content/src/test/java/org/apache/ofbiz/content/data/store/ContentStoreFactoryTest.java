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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.transaction.Status;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.content.data.DataResourceWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Provider selection and S3-provider behaviour, as pure unit tests.
 *
 * <p><strong>Hermetic.</strong> Nothing here opens a database connection, resolves a delegator, starts a
 * transaction, writes a file or reaches the network. Selection is exercised by varying
 * {@code content.store.provider} in memory and asserting only what {@code ContentStoreFactory} answers;
 * the S3 provider is exercised against a Mockito mock of the SDK client, so no AWS configuration,
 * credential resolution or endpoint is involved.
 *
 * <p><strong>Global state is restored.</strong> Gradle runs the whole unit tier in ONE JVM, with no
 * {@code forkEvery} and no {@code maxParallelForks}, so anything global this class writes is still set for
 * every test class that runs after it. Three kinds of global state are written here and all three are put
 * back after each test: the {@code ofbiz.home} SYSTEM PROPERTY, which is snapshotted and either restored to
 * the value it held or cleared when it held none; the {@code content} resource's properties, which
 * {@code UtilProperties.setPropertyValueInMemory} mutates on the shared cached {@code Properties} instance;
 * and the factory's static resolution cache, which is dropped. The {@code ERROR} debug level is restored
 * too, having been turned off so that the deliberate failures exercised here do not fill the build log.
 */
public final class ContentStoreFactoryTest {

    private static final String RESOURCE = "content";

    private static final String PROVIDER_KEY = "content.store.provider";
    private static final String BUCKET_KEY = "content.store.s3.bucket";
    private static final String REGION_KEY = "content.store.s3.region";
    private static final String ENDPOINT_KEY = "content.store.s3.endpoint";
    private static final String ACCESS_KEY_KEY = "content.store.s3.access.key.id";
    private static final String SECRET_KEY_KEY = "content.store.s3.secret.access.key";
    private static final String PATH_STYLE_KEY = "content.store.s3.path.style";

    private static final List<String> MANAGED_KEYS = List.of(PROVIDER_KEY, BUCKET_KEY, REGION_KEY, ENDPOINT_KEY,
            ACCESS_KEY_KEY, SECRET_KEY_KEY, PATH_STYLE_KEY, "content.upload.path.prefix");

    private static final String KEY = "ofbiz/runtime/uploads/1700000000000/10000.png";
    private static final String BUCKET = "test-bucket";

    private static final String HOME_PROPERTY = "ofbiz.home";
    private static final String UPLOAD_PREFIX_KEY = "content.upload.path.prefix";

    private final Map<String, String> original = new LinkedHashMap<>();
    private boolean logErrorOn;
    private String homeSnapshot;
    private boolean homeWasSet;

    /** A storage root and a local tree, both thrown away after each test. */
    @TempDir
    private Path workspace;

    @BeforeEach
    public void initialize() {
        homeSnapshot = System.getProperty(HOME_PROPERTY);
        homeWasSet = homeSnapshot != null;
        System.setProperty(HOME_PROPERTY, System.getProperty("user.dir"));
        // A resource that cannot be resolved would make setPropertyValueInMemory a silent no-op, and every
        // selection assertion below would then be reading the committed value instead of the written one.
        assertNotNull(UtilProperties.getProperties(RESOURCE), "the content resource must resolve on the class path");
        for (String key : MANAGED_KEYS) {
            original.put(key, UtilProperties.getPropertyValue(RESOURCE, key));
        }
        logErrorOn = Debug.isOn(Debug.ERROR);
        Debug.set(Debug.ERROR, false);
        ContentStoreFactory.clearCache();
    }

    @AfterEach
    public void restore() {
        for (Map.Entry<String, String> held : original.entrySet()) {
            UtilProperties.setPropertyValueInMemory(RESOURCE, held.getKey(), held.getValue());
        }
        original.clear();
        ContentStoreFactory.clearCache();
        ContentStorePublisher.clearScope();
        Debug.set(Debug.ERROR, logErrorOn);
        // Restored rather than left overwritten: the unit tier shares one JVM, so a value left here would
        // be read by every later test class. Cleared, not set to "null", when it held nothing.
        if (homeWasSet) {
            System.setProperty(HOME_PROPERTY, homeSnapshot);
        } else {
            System.clearProperty(HOME_PROPERTY);
        }
    }

    @Test
    public void anUnsetProviderResolvesToDatabaseMode() throws GeneralException {
        select("");

        assertNull(ContentStoreFactory.getContentStore(), "an unset provider must leave content storage unchanged");
    }

    @Test
    public void anExplicitDatabaseProviderResolvesToDatabaseMode() throws GeneralException {
        select("database");

        assertNull(ContentStoreFactory.getContentStore(), "database storage must resolve no provider at all");
    }

    @Test
    public void theFilesystemProviderIsResolved() throws GeneralException {
        select("filesystem");

        ContentStore store = ContentStoreFactory.getContentStore();

        assertNotNull(store, "the filesystem provider must be resolved");
        assertTrue(store instanceof FileSystemContentStore, "the filesystem provider must be resolved");
    }

    @Test
    public void theS3ProviderIsResolved() throws GeneralException {
        configureS3();
        select("s3");

        ContentStore store = ContentStoreFactory.getContentStore();

        assertNotNull(store, "the s3 provider must be resolved");
        assertTrue(store instanceof S3ContentStore, "the s3 provider must be resolved");
    }

    @Test
    public void anUnrecognisedProviderFallsBackToDatabaseModeWithoutThrowing() {
        select("nonsense");

        // Both halves of the contract in one assertion: resolution must not throw, and what it answers
        // must be database mode. A block lambda is void-only, so it binds to Executable unambiguously.
        assertDoesNotThrow(() -> {
            assertNull(ContentStoreFactory.getContentStore(),
                    "an unrecognised provider must fall back to database storage");
        }, "an unrecognised provider must never stop an instance from starting");
    }

    @Test
    public void theProviderNameIsCaseInsensitive() throws GeneralException {
        select("Database");
        assertNull(ContentStoreFactory.getContentStore(), "the provider name must be matched case insensitively");

        configureS3();
        select("S3");
        assertTrue(ContentStoreFactory.getContentStore() instanceof S3ContentStore,
                "the provider name must be matched case insensitively");
    }

    @Test
    public void theProviderNameIsTrimmed() throws GeneralException {
        configureS3();
        select(" s3 ");

        assertTrue(ContentStoreFactory.getContentStore() instanceof S3ContentStore,
                "a provider name with surrounding space must still be matched");
    }

    @Test
    public void oneProviderIsResolvedOncePerJvm() throws GeneralException {
        select("filesystem");

        ContentStore first = ContentStoreFactory.getContentStore();
        ContentStore second = ContentStoreFactory.getContentStore();

        assertSame(first, second, "the resolved provider must be reused rather than rebuilt per call");
    }

    @Test
    public void everyOperationIsIssuedAgainstTheConfiguredBucketAndKey() throws GeneralException, IOException {
        S3Client client = mock(S3Client.class);
        ContentStore store = new S3ContentStore(client, BUCKET);
        byte[] content = "the content".getBytes(StandardCharsets.UTF_8);

        // Captured rather than matched with any(): a null bucket, a bucket hard coded to something else, or
        // a key with the ofbiz/ namespace stripped or doubled would all satisfy any(...) while breaking the
        // stable-keying requirement, which nothing else asserts.
        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<GetObjectRequest> get = ArgumentCaptor.forClass(GetObjectRequest.class);
        ArgumentCaptor<HeadObjectRequest> head = ArgumentCaptor.forClass(HeadObjectRequest.class);
        ArgumentCaptor<DeleteObjectRequest> remove = ArgumentCaptor.forClass(DeleteObjectRequest.class);

        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        store.put(KEY, content);

        when(client.getObject(any(GetObjectRequest.class))).thenReturn(response(content));
        assertArrayEquals(content, store.get(KEY), "get must answer the bytes the bucket holds");

        when(client.getObject(any(GetObjectRequest.class))).thenReturn(response(content));
        try (InputStream opened = store.openStream(KEY)) {
            assertArrayEquals(content, opened.readAllBytes(), "openStream must answer the same bytes");
        }

        when(client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength((long) content.length).build());
        assertTrue(store.exists(KEY), "exists must be true for a key the bucket holds");

        when(client.deleteObject(any(DeleteObjectRequest.class))).thenReturn(DeleteObjectResponse.builder().build());
        store.delete(KEY);

        verify(client).putObject(put.capture(), any(RequestBody.class));
        verify(client, times(2)).getObject(get.capture());
        verify(client).headObject(head.capture());
        verify(client).deleteObject(remove.capture());

        assertEquals(BUCKET, put.getValue().bucket(), "put must address the configured bucket");
        assertEquals(KEY, put.getValue().key(), "put must address the key exactly as given");
        assertEquals(BUCKET, head.getValue().bucket(), "exists must address the configured bucket");
        assertEquals(KEY, head.getValue().key(), "exists must address the key exactly as given");
        assertEquals(BUCKET, remove.getValue().bucket(), "delete must address the configured bucket");
        assertEquals(KEY, remove.getValue().key(), "delete must address the key exactly as given");
        // get() and openStream() are the two captured GetObject calls, in that order.
        assertEquals(List.of(BUCKET, BUCKET), get.getAllValues().stream().map(GetObjectRequest::bucket).toList(),
                "get and openStream must both address the configured bucket");
        assertEquals(List.of(KEY, KEY), get.getAllValues().stream().map(GetObjectRequest::key).toList(),
                "get and openStream must both address the key exactly as given");
    }

    @Test
    public void absenceIsReportedAsAbsenceAndNothingElseIs() throws GeneralException, IOException {
        S3Client client = mock(S3Client.class);
        ContentStore store = new S3ContentStore(client, BUCKET);

        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        assertFalse(store.exists(KEY), "exists must answer false, not throw, for a key the bucket does not hold");

        when(client.getObject(any(GetObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        assertThrows(FileNotFoundException.class, () -> store.get(KEY),
                "an absent object must be reported as absence so that a caller answers 404");
        assertThrows(FileNotFoundException.class, () -> store.openStream(KEY),
                "an absent object must be reported as absence so that a caller answers 404");
    }

    @Test
    public void aPlain404IsAbsenceEvenWithoutTheTypedException() throws GeneralException, IOException {
        S3Client client = mock(S3Client.class);
        ContentStore store = new S3ContentStore(client, BUCKET);

        // Not every S3-compatible store answers with the typed NoSuchKeyException; a bare 404 must be read
        // as absence too, or content the store genuinely does not hold would be reported as an outage.
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(status(404));
        assertThrows(FileNotFoundException.class, () -> store.get(KEY),
                "a 404 must be reported as absence whether or not the SDK typed it");

        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(status(404));
        assertFalse(store.exists(KEY), "a 404 from HeadObject must answer false rather than throw");
    }

    @Test
    public void anOutageIsNeverReportedAsAbsence() throws GeneralException {
        S3Client client = mock(S3Client.class);
        ContentStore store = new S3ContentStore(client, BUCKET);

        // The distinction this asserts is the difference between a 503 and a permanent 404 for the caller.
        // A regression that mapped either of these to FileNotFoundException would make a transient outage
        // look like content that had been deleted, and the local fallback would hide the fault entirely.
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(status(500));
        IOException serverFailure = assertThrows(IOException.class, () -> store.get(KEY),
                "a server failure must be raised, not swallowed");
        assertFalse(serverFailure instanceof FileNotFoundException,
                "a 500 must NOT be reported as absence: an outage is not a deletion");

        when(client.getObject(any(GetObjectRequest.class)))
                .thenThrow(SdkClientException.builder().message("connection reset").build());
        IOException clientFailure = assertThrows(IOException.class, () -> store.openStream(KEY),
                "a transport failure must be raised, not swallowed");
        assertFalse(clientFailure instanceof FileNotFoundException,
                "a transport failure must NOT be reported as absence");

        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(status(500));
        IOException existsFailure = assertThrows(IOException.class, () -> store.exists(KEY),
                "exists must raise on a server failure rather than answer a false negative");
        assertFalse(existsFailure instanceof FileNotFoundException,
                "a server failure must not be reported as a key the store does not hold");
    }

    @Test
    public void aMissingBucketIsAStoreFaultAndNeverAbsence() throws GeneralException {
        S3Client client = mock(S3Client.class);
        ContentStore store = new S3ContentStore(client, BUCKET);

        // The distinction this asserts is the one an operator's alarm depends on. A bucket that has been
        // renamed, deleted or made inaccessible is a store fault; reported as absence it would make every
        // object in it look as though it had never been stored - and in a fleet whose only durable copy IS
        // the object, that is content vanishing silently with nothing in the log to say why.
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(noSuchBucket());
        IOException readFailure = assertThrows(IOException.class, () -> store.get(KEY),
                "a read from a bucket that does not exist must be raised");
        assertFalse(readFailure instanceof FileNotFoundException,
                "a missing bucket must NOT be reported as a key the store does not hold");
        IOException streamFailure = assertThrows(IOException.class, () -> store.openStream(KEY),
                "openStream must raise for a missing bucket too");
        assertFalse(streamFailure instanceof FileNotFoundException,
                "a missing bucket must NOT be reported as absence on the streaming read either");

        // HeadObject cannot say WHICH thing is missing - a HEAD response carries no body to put an error
        // code in, so both cases arrive as the same typed 404 - which is why the provider confirms the
        // bucket before it reports absence. The confirmation is what distinguishes the two here.
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        when(client.headBucket(any(HeadBucketRequest.class))).thenThrow(noSuchBucket());
        assertThrows(IOException.class, () -> store.exists(KEY),
                "exists must raise rather than answer false when the bucket itself is gone");
        assertThrows(IOException.class, () -> store.describe(KEY),
                "describe must raise rather than answer empty when the bucket itself is gone");
    }

    @Test
    public void absenceIsStillAbsenceWhenTheBucketAnswers() throws GeneralException, IOException {
        S3Client client = mock(S3Client.class);
        ContentStore store = new S3ContentStore(client, BUCKET);
        when(client.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());

        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        assertFalse(store.exists(KEY), "a key a healthy bucket does not hold is absent, not a fault");
        assertTrue(store.describe(KEY).isEmpty(), "a key a healthy bucket does not hold has no description");
        // Confirming the bucket must cost one request and only on this path, so a fetch that falls back to
        // the local copy is not turned into a chain of retries.
        verify(client, times(2)).headBucket(any(HeadBucketRequest.class));

        // The other spelling of absence: a bare 404 carrying no error code, which several S3-compatible
        // stores answer for HeadObject. Still absence when the bucket answers.
        S3Client codeless = mock(S3Client.class);
        ContentStore other = new S3ContentStore(codeless, BUCKET);
        when(codeless.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());
        when(codeless.headObject(any(HeadObjectRequest.class))).thenThrow(status(404));
        assertFalse(other.exists(KEY), "a codeless 404 with a healthy bucket is absence");
        assertTrue(other.describe(KEY).isEmpty(), "a codeless 404 with a healthy bucket has no description");
    }

    @Test
    public void aBucketTheStoreWillNotDiscussLeavesTheAbsenceVerdictAlone() throws GeneralException, IOException {
        S3Client client = mock(S3Client.class);
        ContentStore store = new S3ContentStore(client, BUCKET);
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        // A credential allowed to read an object but not to inspect its bucket cannot answer the question,
        // and 403 is not "the bucket is gone". The absence verdict therefore stands, so a least-privilege
        // deployment keeps exactly the behaviour it had before the confirmation existed.
        when(client.headBucket(any(HeadBucketRequest.class))).thenThrow(status(403));

        assertFalse(store.exists(KEY), "an unanswerable bucket question must not turn absence into a fault");
        assertTrue(store.describe(KEY).isEmpty(), "an unanswerable bucket question must leave describe empty");

        // A store that cannot be reached AT ALL for the confirmation is a different matter: that is an
        // outage, and an outage is never absence.
        S3Client unreachable = mock(S3Client.class);
        ContentStore down = new S3ContentStore(unreachable, BUCKET);
        when(unreachable.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        when(unreachable.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(SdkClientException.builder().message("connection reset").build());
        assertThrows(IOException.class, () -> down.exists(KEY),
                "a store that cannot be asked about its bucket must raise rather than report absence");
        assertThrows(IOException.class, () -> down.describe(KEY),
                "describe must raise as well when the bucket cannot be asked about");
    }

    @Test
    public void aKeyThatBreaksTheGrammarIsRefused() {
        ContentStore store = new S3ContentStore(mock(S3Client.class), BUCKET);
        byte[] content = new byte[0];

        assertThrows(GeneralException.class, () -> store.put("", content), "an empty key must be refused");
        assertThrows(GeneralException.class, () -> store.put("/absolute", content), "a rooted key must be refused");
        assertThrows(GeneralException.class, () -> store.put("trailing/", content),
                "a key ending in a separator must be refused");
        assertThrows(GeneralException.class, () -> store.put("a//b", content),
                "a key with an empty segment must be refused");
        assertThrows(GeneralException.class, () -> store.put("a/../b", content),
                "a key with a relative segment must be refused");
        assertThrows(GeneralException.class, () -> store.put("a\\b", content),
                "a key with a backslash must be refused");
        assertThrows(GeneralException.class, () -> store.put("a/\u0001b", content),
                "a key holding a control character must be refused");
    }

    // ---------------------------------------------------------------------------------------------
    // The publication engine and the filesystem provider.
    //
    // Both are exercised against real objects rather than mocks: a FileSystemContentStore rooted at a
    // temporary directory IS the store, wrapped in a counter so that "nothing was transferred" can be
    // asserted rather than assumed. Nothing here uses reflection - ContentStorePublisher exposes
    // store-taking overloads for exactly this purpose - and nothing opens a database connection or a
    // socket.
    // ---------------------------------------------------------------------------------------------

    @Test
    public void theFilesystemProviderIsNeverUsedAsAnExternalStore() throws GeneralException {
        select("filesystem");

        assertNotNull(ContentStoreFactory.getContentStore(), "the provider itself must still be resolvable");
        // Deliberate, and load bearing: this provider's root IS the upload directory the content already
        // lives in, so publishing to it would copy a file onto itself and a read would fetch a file from
        // itself. It exists to exercise the SPI without an object store, not to make an instance stateless.
        assertNull(ContentStorePublisher.externalStore(),
                "the filesystem provider must never be used as the store content is published to");
    }

    @Test
    public void aStorageKeyIsTheOfbizHomeRelativePathUnderOneNamespace() throws GeneralException {
        Path home = localHome();

        assertEquals("ofbiz/runtime/uploads/1/2.png",
                ContentStorePublisher.storeKey(home.resolve("runtime/uploads/1/2.png").toFile()),
                "a key must be the ofbiz.home-relative path under the one namespace, so that every instance"
                        + " and every provider names the same object");
        assertNull(ContentStorePublisher.storeKey(new File("/etc/passwd")),
                "content outside ofbiz.home has no key, so it is neither published nor looked up");
    }

    @Test
    public void anAbsentObjectLeavesThisInstanceCompletelyUntouched() throws GeneralException, IOException {
        Path home = localHome();
        CountingStore store = objectStore();
        File target = home.resolve("runtime/uploads/shard/1.txt").toFile();

        assertFalse(ContentStorePublisher.fetch(store, target), "an absent object must answer false");

        assertFalse(target.getParentFile().exists(),
                "an absent object must create no directory: a read must not need a writable filesystem");
        assertEquals(1, store.describes, "absence must be settled with exactly one metadata request");
        assertEquals(0, store.opens, "an absent object must transfer nothing");
    }

    @Test
    public void aLocalCopyThatAlreadyMatchesTheObjectIsNotTransferred() throws GeneralException, IOException {
        Path home = localHome();
        CountingStore store = objectStore();
        File target = home.resolve("runtime/uploads/shard/1.txt").toFile();
        write(target, "hello", 1000L);
        hold(store, "ofbiz/runtime/uploads/shard/1.txt", "hello", 1000L);

        assertTrue(ContentStorePublisher.fetch(store, target), "the content must be reported readable");

        assertEquals(0, store.opens, "a local copy that matches the object must not be downloaded again");
        assertEquals("hello", Files.readString(target.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    public void aLocallyNewerCopyIsNeverOverwritten() throws GeneralException, IOException {
        Path home = localHome();
        CountingStore store = objectStore();
        File target = home.resolve("runtime/uploads/shard/1.txt").toFile();
        write(target, "the local update", 9000L);
        hold(store, "ofbiz/runtime/uploads/shard/1.txt", "the older object", 1000L);

        assertTrue(ContentStorePublisher.fetch(store, target), "the content must be reported readable");

        // The defect this guards against: an unconditional move would destroy a write this instance has
        // made but not yet published, losing content with no error anywhere.
        assertEquals("the local update", Files.readString(target.toPath(), StandardCharsets.UTF_8),
                "a local copy newer than the object must be kept, not overwritten");
        assertEquals(0, store.opens, "a retained local copy must not be downloaded over");
        assertTrue(ContentStorePublisher.open(store, target).isEmpty(),
                "streaming must prefer the newer local copy too, or the two paths would disagree");
    }

    @Test
    public void anObjectNewerThanTheLocalCopyIsMaterialisedAndStamped() throws GeneralException, IOException {
        Path home = localHome();
        CountingStore store = objectStore();
        File target = home.resolve("runtime/uploads/shard/1.txt").toFile();
        write(target, "stale", 1000L);
        hold(store, "ofbiz/runtime/uploads/shard/1.txt", "fresh", 5000L);

        assertTrue(ContentStorePublisher.fetch(store, target));

        assertEquals("fresh", Files.readString(target.toPath(), StandardCharsets.UTF_8),
                "a replacement made by another instance must be picked up");
        assertEquals(5000L, target.lastModified(),
                "the local copy must carry the object's own time, so the next read needs no transfer and the"
                        + " fetch is not mistaken for a local write at commit");
        assertEquals(1, store.opens, "the object must be transferred exactly once");

        assertTrue(ContentStorePublisher.fetch(store, target), "the second read must still report it readable");
        assertEquals(1, store.opens, "a second read must be one metadata request and no transfer");
        assertEquals(2, store.describes, "each read costs exactly one metadata request");
    }

    @Test
    public void aStorageFailureTellsTheCallerAReferenceAndNotTheKey() throws IOException {
        Path home = localHome();
        File target = home.resolve("runtime/uploads/shard/10020.txt").toFile();
        write(target, "local", 1000L);
        ContentStore unreachable = new UnreachableStore();

        GeneralException raised = assertThrows(GeneralException.class, () -> ContentStorePublisher.open(
                unreachable, target), "a store that cannot be reached must raise rather than answer empty");

        String told = raised.getMessage();
        assertFalse(told.contains("ofbiz/runtime/uploads/shard/10020.txt"),
                "the storage key must not reach the caller: the message is rendered on a content screen, and"
                        + " the key would disclose the deployment's internal path scheme. Was: " + told);
        assertFalse(told.contains(target.getAbsolutePath()),
                "the local path must not reach the caller either. Was: " + told);
        assertTrue(told.matches(".*Reference \\[[0-9a-f-]{36}\\].*"),
                "the caller must be given an opaque reference that ties the message to the log line holding"
                        + " the detail. Was: " + told);
    }

    @Test
    public void aTransactionThatDoesNotCommitLeavesNoUploadDirectoryBehind() throws GeneralException, IOException {
        Path home = localHome();
        CountingStore store = objectStore();
        File owned = home.resolve("runtime/uploads/1700000000000/txn-0123456789abcdef").toFile();
        File upload = new File(owned, "10020.txt");
        write(upload, "rolled back", 1000L);

        // What the transaction manager calls back when the transaction rolls back. A directory publication
        // carries no file names, which is what marks the directory as this transaction's own.
        new ContentStorePublisher.ContentPublication(store, owned, null, owned.getAbsolutePath())
                .afterCompletion(Status.STATUS_ROLLEDBACK);

        assertFalse(upload.exists(), "an upload no committed row names must not be left on the instance");
        assertFalse(owned.exists(), "the transaction's own upload directory must be removed with it, or a"
                + " failed upload leaves durable local state behind on whichever instance took it");
    }

    @Test
    public void aTransactionThatDoesNotCommitLeavesNoObjectBehindEither() throws GeneralException, IOException {
        Path home = localHome();
        CountingStore store = objectStore();
        File owned = home.resolve("runtime/uploads/1700000000000/txn-0123456789abcdef").toFile();
        File upload = new File(owned, "10020.txt");
        write(upload, "published then rolled back", 1000L);
        String key = "ofbiz/runtime/uploads/1700000000000/txn-0123456789abcdef/10020.txt";

        ContentStorePublisher.ContentPublication publication =
                new ContentStorePublisher.ContentPublication(store, owned, null, owned.getAbsolutePath());
        // The transaction publishes what it wrote, and then does not commit: the row naming the content is
        // gone, so both halves of what the upload left - the object and the staging directory - have to go
        // with it.
        publication.beforeCompletion();
        assertTrue(store.exists(key), "the publication must have written the object before the rollback");

        publication.afterCompletion(Status.STATUS_ROLLEDBACK);

        assertFalse(store.exists(key), "an object no committed row names must be removed from the store again");
        assertFalse(owned.exists(), "and the staging directory must go with it");
    }

    @Test
    public void aTransactionThatCommitsKeepsItsUploadDirectory() throws GeneralException, IOException {
        Path home = localHome();
        CountingStore store = objectStore();
        File owned = home.resolve("runtime/uploads/1700000000000/txn-0123456789abcdef").toFile();
        File upload = new File(owned, "10020.txt");
        write(upload, "committed", 1000L);

        new ContentStorePublisher.ContentPublication(store, owned, null, owned.getAbsolutePath())
                .afterCompletion(Status.STATUS_COMMITTED);

        assertTrue(upload.exists(), "a committed upload's local copy is what this instance serves and must stay");
    }

    @Test
    public void aNonCommitLeavesFilesTheTransactionDoesNotOwnAlone() throws GeneralException, IOException {
        Path home = localHome();
        CountingStore store = objectStore();
        File shared = home.resolve("runtime/uploads/shard").toFile();
        File other = new File(shared, "1.txt");
        write(other, "not mine", 1000L);

        // A NAMED publication watches a file it did not create, in a directory it does not own.
        new ContentStorePublisher.ContentPublication(store, shared, Set.of("1.txt"), shared.getAbsolutePath())
                .afterCompletion(Status.STATUS_ROLLEDBACK);

        assertTrue(other.exists(), "content the transaction does not own must never be deleted by its rollback");
        assertTrue(shared.exists(), "a directory the transaction does not own must never be removed");
    }

    @Test
    public void databaseModeTouchesNothingAtAll() throws GeneralException, IOException {
        Path home = localHome();
        select("database");
        File target = home.resolve("runtime/uploads/shard/1.txt").toFile();

        assertFalse(ContentStorePublisher.fetch(target), "database mode must not fetch");
        assertTrue(ContentStorePublisher.open(target).isEmpty(), "database mode must not open");
        assertEquals("x", ContentStorePublisher.publishedUploadPath("x", false),
                "database mode must hand back the upload path exactly as it was given");
        assertFalse(target.getParentFile().exists(), "database mode must create nothing");
    }

    @Test
    public void anUploadResolvedOutsideATransactionIsRefused() throws GeneralException, IOException {
        Path home = localHome();
        UtilProperties.setPropertyValueInMemory(RESOURCE, UPLOAD_PREFIX_KEY, "runtime/uploads");
        configureS3();
        select("s3");
        assertNotNull(ContentStorePublisher.externalStore(),
                "the precondition of this test is that an EXTERNAL store is configured");

        // A publication has to belong to a transaction: it is what decides when the object is written and
        // what removes it again if that transaction rolls back. Resolving an upload directory with no
        // transaction in progress therefore cannot be honoured, and is refused loudly rather than
        // published outside any transaction.
        String shard = home.resolve("runtime/uploads/shard").toString();
        Executable resolve = () -> ContentStorePublisher.publishedUploadPath(shard, true);
        IllegalStateException refused = assertThrows(IllegalStateException.class, resolve,
                "an upload directory resolved outside a transaction must be refused");
        assertTrue(refused.getMessage().contains("transaction"),
                "the refusal must say what is missing, not just fail: " + refused.getMessage());
    }

    @Test
    public void theFilesystemProviderRoundTripsThroughItsOwnRoot() throws GeneralException, IOException {
        Path root = workspace.resolve("object-store");
        ContentStore store = new FileSystemContentStore(root.toString());
        byte[] content = "the content".getBytes(StandardCharsets.UTF_8);

        assertFalse(store.exists(KEY), "nothing is held before anything is stored");
        assertTrue(store.describe(KEY).isEmpty(), "an absent object has no description");

        store.put(KEY, content);

        assertTrue(store.exists(KEY), "a stored object must be held");
        assertArrayEquals(content, store.get(KEY), "a stored object must read back byte for byte");
        try (InputStream opened = store.openStream(KEY)) {
            assertArrayEquals(content, opened.readAllBytes(), "openStream must answer the same bytes");
        }
        Optional<ContentStore.Description> described = store.describe(KEY);
        assertTrue(described.isPresent(), "a stored object must be describable");
        assertEquals(content.length, described.get().length(), "the description must carry the object's length");
        assertTrue(Files.exists(root.resolve(KEY)), "the object must live under this provider's own root");

        store.delete(KEY);
        assertFalse(store.exists(KEY), "a deleted object must no longer be held");
        assertDoesNotThrow(() -> store.delete(KEY), "deleting an absent object must be idempotent");
    }

    @Test
    public void anObjectLargerThanTheSharedBoundIsRefusedByEveryProvider() throws GeneralException {
        ContentStore filesystem = new FileSystemContentStore(workspace.resolve("object-store").toString());
        ContentStore s3 = new S3ContentStore(mock(S3Client.class), BUCKET);
        // One bound, declared once on the SPI and used by both providers and by the publication engine, so
        // that content can never be accepted by one and refused by another.
        byte[] tooLarge = new byte[(int) ContentStore.MAX_OBJECT_BYTES + 1];

        assertThrows(IOException.class, () -> filesystem.put(KEY, tooLarge),
                "the filesystem provider must refuse an object beyond the shared bound");
        assertThrows(IOException.class, () -> s3.put(KEY, tooLarge),
                "the s3 provider must refuse an object beyond the shared bound");
    }

    @Test
    public void absentContentIsReportedWithItsLocationWhenNoExternalStoreIsConfigured() {
        select("");
        String location = workspace.resolve("absent-content.txt").toAbsolutePath().toString();

        Executable resolve = () -> DataResourceWorker.getContentFile("LOCAL_FILE", location, null);

        FileNotFoundException absent = assertThrows(FileNotFoundException.class, resolve,
                "content that is not on this instance must be reported as absent");

        // Character for character what this has always said: with no store the location names nothing but a
        // file here, and the shipped default must not change - including what a caller reads out of it.
        assertEquals("No file found: " + location, absent.getMessage(),
                "without an external store the absence must still name the location");
    }

    @Test
    public void absentContentIsReportedWithoutItsLocationWhenAnExternalStoreIsConfigured() {
        configureS3();
        select("s3");
        String location = workspace.resolve("absent-content.txt").toAbsolutePath().toString();

        Executable resolve = () -> DataResourceWorker.getContentFile("LOCAL_FILE", location, null);

        FileNotFoundException absent = assertThrows(FileNotFoundException.class, resolve,
                "content held by neither the store nor this instance must still be reported as absent");

        // With a store configured the location IS the storage key, so the caller gets a reference and the
        // log gets the location. The whole message is asserted, not just the absence of the path: a message
        // that stopped naming the location but started naming the bucket would pass a containment check.
        String opaque = "The requested content could not be read\\. Reference \\[[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}"
                + "-[0-9a-f]{4}-[0-9a-f]{12}\\]";
        assertTrue(absent.getMessage().matches(opaque),
                "the caller must be given an opaque reference and nothing else, but was given: "
                        + absent.getMessage());
        assertFalse(absent.getMessage().contains(location),
                "the location must not reach the caller, because it is the storage key");
        assertFalse(absent.getMessage().contains("absent-content"),
                "no part of the location may reach the caller");
        assertFalse(absent.getMessage().contains(BUCKET),
                "the bucket must not reach the caller either");
    }

    /**
     * Points {@code ofbiz.home} at a private tree and answers it.
     *
     * @return the directory that stands in for the OFBiz home of this instance
     */
    private Path localHome() {
        Path home = workspace.resolve("home");
        System.setProperty(HOME_PROPERTY, home.toString());
        return home;
    }

    /**
     * Builds the store the publication engine is driven against.
     *
     * @return a real filesystem provider, rooted outside the local tree, that counts what it was asked
     * @throws GeneralException if the provider cannot be built
     */
    private CountingStore objectStore() throws GeneralException {
        return new CountingStore(new FileSystemContentStore(workspace.resolve("object-store").toString()));
    }

    /**
     * Writes a local file with a known modification time.
     *
     * @param file the file to write
     * @param content its content
     * @param modifiedAt the modification time to stamp on it
     * @throws IOException if the file cannot be written
     */
    private static void write(File file, String content, long modifiedAt) throws IOException {
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(modifiedAt));
    }

    /**
     * Puts an object into the store with a known modification time.
     *
     * @param store the store to write into
     * @param key the storage key
     * @param content the object's content
     * @param modifiedAt the modification time the store must report for it
     * @throws GeneralException if the object cannot be stored
     * @throws IOException if the object cannot be stored
     */
    private void hold(CountingStore store, String key, String content, long modifiedAt)
            throws GeneralException, IOException {
        store.put(key, content.getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(workspace.resolve("object-store").resolve(key), FileTime.fromMillis(modifiedAt));
    }

    /**
     * A real provider that counts the requests made of it.
     *
     * <p>Counting is what turns "nothing was transferred" from a claim into an assertion: the read model
     * is one metadata request per read and a transfer only when the object differs, and neither half can
     * be observed from the filesystem alone.
     */
    private static final class CountingStore implements ContentStore {

        private final ContentStore delegate;
        private int describes;
        private int opens;

        CountingStore(ContentStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void put(String key, byte[] data) throws GeneralException, IOException {
            delegate.put(key, data);
        }

        @Override
        public byte[] get(String key) throws GeneralException, IOException {
            opens++;
            return delegate.get(key);
        }

        @Override
        public InputStream openStream(String key) throws GeneralException, IOException {
            opens++;
            return delegate.openStream(key);
        }

        @Override
        public boolean exists(String key) throws GeneralException, IOException {
            return delegate.exists(key);
        }

        @Override
        public Optional<Description> describe(String key) throws GeneralException, IOException {
            describes++;
            return delegate.describe(key);
        }

        @Override
        public void delete(String key) throws GeneralException, IOException {
            delegate.delete(key);
        }
    }

    /**
     * A store that cannot be reached, for the disclosure tests.
     *
     * <p>Answers every request with an {@code IOException} whose own message names nothing, so what the
     * caller is told can only have come from the publisher's own message building.
     */
    private static final class UnreachableStore implements ContentStore {

        @Override
        public void put(String key, byte[] data) throws IOException {
            throw new IOException("unreachable");
        }

        @Override
        public byte[] get(String key) throws IOException {
            throw new IOException("unreachable");
        }

        @Override
        public InputStream openStream(String key) throws IOException {
            throw new IOException("unreachable");
        }

        @Override
        public boolean exists(String key) throws IOException {
            throw new IOException("unreachable");
        }

        @Override
        public Optional<Description> describe(String key) throws IOException {
            throw new IOException("unreachable");
        }

        @Override
        public void delete(String key) throws IOException {
            throw new IOException("unreachable");
        }
    }

    private static void select(String provider) {
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROVIDER_KEY, provider);
        ContentStoreFactory.clearCache();
    }

    private static void configureS3() {
        UtilProperties.setPropertyValueInMemory(RESOURCE, BUCKET_KEY, BUCKET);
        UtilProperties.setPropertyValueInMemory(RESOURCE, REGION_KEY, "us-east-1");
        UtilProperties.setPropertyValueInMemory(RESOURCE, ENDPOINT_KEY, "http://127.0.0.1:1");
        UtilProperties.setPropertyValueInMemory(RESOURCE, ACCESS_KEY_KEY, "test-access-key");
        UtilProperties.setPropertyValueInMemory(RESOURCE, SECRET_KEY_KEY, "test-secret-key");
        UtilProperties.setPropertyValueInMemory(RESOURCE, PATH_STYLE_KEY, "true");
    }

    /**
     * Builds an SDK service exception carrying a status code, for the failure-branch tests.
     *
     * @param code the HTTP status the store answered with
     * @return the exception the SDK would raise
     */
    private static S3Exception status(int code) {
        return (S3Exception) S3Exception.builder().statusCode(code).message("status " + code).build();
    }

    /**
     * The answer a store gives for a bucket it does not have: a 404 that names the BUCKET, not the key.
     *
     * <p>Built with the status code and the error code an S3-compatible store really sends - verified
     * against MinIO - because it is exactly those two fields the provider reads to tell a missing bucket
     * from a missing object.
     *
     * @return the exception
     */
    private static NoSuchBucketException noSuchBucket() {
        return NoSuchBucketException.builder()
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchBucket").build())
                .statusCode(404)
                .message("The specified bucket does not exist")
                .build();
    }

    private static ResponseInputStream<GetObjectResponse> response(byte[] content) {
        return new ResponseInputStream<>(GetObjectResponse.builder().contentLength((long) content.length).build(),
                new ByteArrayInputStream(content));
    }
}
