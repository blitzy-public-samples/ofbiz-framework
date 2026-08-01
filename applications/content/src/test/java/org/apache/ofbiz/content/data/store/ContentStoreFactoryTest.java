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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.entity.Delegator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
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
 * The provider key, all six {@code content.store.s3.*} keys, the {@code ofbiz.home} system property
 * and the factory's own resolution cache are therefore captured before each test and put back after
 * it.
 *
 * <p><strong>The class is final</strong> because Checkstyle's {@code DesignForExtension} rule
 * tolerates only the JUnit 4 lifecycle annotations, so a non-final class carrying the
 * {@code @BeforeEach} and {@code @AfterEach} that the restore above requires fails the build.
 *
 * <p><strong>Nothing here reaches a network, a database or the filesystem.</strong> The object-store
 * operations run against a mocked {@code S3Client} handed to the provider's package-private test
 * seam; the fake configuration the selection cases install builds a client entirely offline, from a
 * static credential pair and an explicit region, and no request is ever issued through it. The
 * {@code S3Client} named here is the single sanctioned reference to the AWS SDK outside
 * {@link S3ContentStore} itself.
 *
 * <p>The public {@code Delegator} form is exercised with a null delegator, which is its documented
 * "consult {@code content.properties} only" contract. A live delegator would make this an
 * integration test, and a stand-in one would assert the entity engine's {@code SystemProperty}
 * lookup rather than anything this factory decides.
 */
public final class ContentStoreFactoryTest {

    /** The resource the provider configuration is read from; never {@code content.properties}. */
    private static final String RESOURCE = "content";

    private static final String PROPERTY_PROVIDER = "content.store.provider";
    private static final String PROPERTY_S3_BUCKET = "content.store.s3.bucket";
    private static final String PROPERTY_S3_REGION = "content.store.s3.region";
    private static final String PROPERTY_S3_ENDPOINT = "content.store.s3.endpoint";
    private static final String PROPERTY_S3_ACCESS_KEY_ID = "content.store.s3.access.key.id";
    private static final String PROPERTY_S3_SECRET_ACCESS_KEY = "content.store.s3.secret.access.key";
    private static final String PROPERTY_S3_PATH_STYLE = "content.store.s3.path.style";

    /** Every key this test writes, and therefore every key it has to put back. */
    private static final String[] MUTATED_PROPERTIES = {
        PROPERTY_PROVIDER,
        PROPERTY_S3_BUCKET,
        PROPERTY_S3_REGION,
        PROPERTY_S3_ENDPOINT,
        PROPERTY_S3_ACCESS_KEY_ID,
        PROPERTY_S3_SECRET_ACCESS_KEY,
        PROPERTY_S3_PATH_STYLE,
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

    private static final String KEY = "runtime/uploads/party/logo.png";
    private static final byte[] PAYLOAD = "content bound for an object store".getBytes(StandardCharsets.UTF_8);

    /** The status several S3-compatible stores report in place of {@code NoSuchKey}. */
    private static final int HTTP_NOT_FOUND = 404;

    /** A status that is a genuine failure rather than an absence. */
    private static final int HTTP_SERVER_ERROR = 500;

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
    public void constructingTheObjectStoreProviderIssuesNoRequest() {
        S3Client client = mock(S3Client.class);

        S3ContentStore store = new S3ContentStore(client, BUCKET);

        assertNotNull(store, "the test seam must yield a provider around the supplied client");
        verifyNoInteractions(client);
    }

    @Test
    public void everyStorageOperationIsIssuedThroughTheClientTheSeamWasGiven() throws Exception {
        S3Client client = mock(S3Client.class);
        when(client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), PAYLOAD));
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(storedObject(PAYLOAD));
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
        verify(client).getObjectAsBytes(eq(GetObjectRequest.builder().bucket(BUCKET).key(KEY).build()));

        try (InputStream opened = store.openStream(KEY)) {
            assertArrayEquals(PAYLOAD, opened.readAllBytes(), "an opened stream must serve the stored bytes"
                    + " from the first byte");
        }
        verify(client).getObject(eq(GetObjectRequest.builder().bucket(BUCKET).key(KEY).build()));

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
        when(client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), PAYLOAD));
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(storedObject(PAYLOAD));
        ContentStore store = new S3ContentStore(client, BUCKET);

        store.put(KEY, PAYLOAD);
        store.get(KEY);
        store.openStream(KEY).close();
        store.delete(KEY);

        verify(client, never()).headObject(any(HeadObjectRequest.class));
    }

    @Test
    public void anAbsentObjectIsReportedAsAbsenceRatherThanAsAStoreFailure() throws Exception {
        // Two shapes of the same answer: the SDK's own NoSuchKey, and the bare 404 that several
        // S3-compatible stores send instead. Both mean "nothing here", which a caller has to be able to
        // recognise without knowing an SDK type - hence FileNotFoundException from the reads, false from
        // the existence test, and silence from the removal.
        S3Exception noSuchKey = (S3Exception) NoSuchKeyException.builder().message("no such key").build();
        S3Exception notFound = (S3Exception) S3Exception.builder().statusCode(HTTP_NOT_FOUND)
                .message("not found").build();
        for (S3Exception absent : new S3Exception[] {noSuchKey, notFound}) {
            S3Client client = mock(S3Client.class);
            when(client.getObjectAsBytes(any(GetObjectRequest.class))).thenThrow(absent);
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
    public void aStoreFailureIsTranslatedSoThatNoSdkTypeEscapesTheContract() {
        S3Exception failure = (S3Exception) S3Exception.builder().statusCode(HTTP_SERVER_ERROR)
                .message("the store is unwell").build();
        S3Client client = mock(S3Client.class);
        when(client.getObjectAsBytes(any(GetObjectRequest.class))).thenThrow(failure);
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
    public void anUnusableKeyOrPayloadIsRefusedBeforeAnyRequestIsIssued() {
        S3Client client = mock(S3Client.class);
        ContentStore store = new S3ContentStore(client, BUCKET);

        // A key is provider-relative by contract. An absolute one, or one that climbs out of the key space,
        // must be refused where it is named rather than sent to a store that might honour it.
        for (String unusable : new String[] {"", "/runtime/uploads/logo.png", "runtime/../../etc/passwd"}) {
            assertThrows(GeneralException.class, () -> store.get(unusable), "[" + unusable + "] must be refused");
            assertThrows(GeneralException.class, () -> store.put(unusable, PAYLOAD), "[" + unusable + "] must be"
                    + " refused");
        }
        assertThrows(GeneralException.class, () -> store.put(KEY, null), "a null payload must be refused");

        verifyNoInteractions(client);
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
}
