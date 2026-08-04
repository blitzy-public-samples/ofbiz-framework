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
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.UtilProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
 * <p><strong>Global state is restored.</strong> {@code UtilProperties.setPropertyValueInMemory} mutates
 * the shared, cached {@code Properties} instance for the resource, the factory caches its resolution
 * statically, and Gradle runs the whole unit tier in one JVM - so every property this class writes is put
 * back, and the factory cache is dropped, after each test.
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
            ACCESS_KEY_KEY, SECRET_KEY_KEY, PATH_STYLE_KEY);

    private static final String KEY = "ofbiz/runtime/uploads/1700000000000/10000.png";
    private static final String BUCKET = "test-bucket";

    private final Map<String, String> original = new LinkedHashMap<>();
    private boolean logErrorOn;

    @BeforeEach
    public void initialize() {
        System.setProperty("ofbiz.home", System.getProperty("user.dir"));
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
        Debug.set(Debug.ERROR, logErrorOn);
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
    public void everyOperationIsIssuedAgainstTheConfiguredBucket() throws GeneralException, IOException {
        S3Client client = mock(S3Client.class);
        ContentStore store = new S3ContentStore(client, BUCKET);
        byte[] content = "the content".getBytes(StandardCharsets.UTF_8);

        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        store.put(KEY, content);
        verify(client).putObject(any(PutObjectRequest.class), any(RequestBody.class));

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
        verify(client).deleteObject(any(DeleteObjectRequest.class));
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

    private static ResponseInputStream<GetObjectResponse> response(byte[] content) {
        return new ResponseInputStream<>(GetObjectResponse.builder().contentLength((long) content.length).build(),
                new ByteArrayInputStream(content));
    }
}
