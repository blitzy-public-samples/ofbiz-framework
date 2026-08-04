/*
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
 */
package org.apache.ofbiz.content.data.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.Map;

import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.content.data.DataResourceWorker;
import org.apache.ofbiz.entity.transaction.TransactionUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Provider selection, the provider contract, and the worker's storage seam.
 *
 * <p>Every test runs offline: {@code ofbiz.home} is redirected at a temporary directory and the provider
 * under test is the filesystem one, which is the provider that can be observed without a network. The S3
 * provider is exercised for the things that do not need a bucket - its configuration contract, and that a
 * store which cannot answer reports a failure rather than absence.
 *
 * <p>Configuration is set with {@code UtilProperties.setPropertyValueInMemory}, and every value this class
 * changes is restored afterwards, because that call lasts for the life of the JVM and the properties are
 * shared with every other test in the same run.
 */
public final class ContentStoreFactoryTest {

    private static final String RESOURCE = "content";
    private static final String PROVIDER = "content.store.provider";
    private static final String S3_BUCKET = "content.store.s3.bucket";
    private static final String S3_REGION = "content.store.s3.region";
    private static final String S3_ENDPOINT = "content.store.s3.endpoint";
    private static final String S3_ACCESS_KEY = "content.store.s3.access.key.id";
    private static final String S3_SECRET_KEY = "content.store.s3.secret.access.key";
    private static final String S3_PATH_STYLE = "content.store.s3.path.style";

    /** Every property this class writes, so that each is restored whatever a test did to it. */
    private static final String[] TOUCHED = {PROVIDER, S3_BUCKET, S3_REGION, S3_ENDPOINT, S3_ACCESS_KEY,
        S3_SECRET_KEY, S3_PATH_STYLE};

    @TempDir
    private Path home;

    private final Map<String, String> committed = new LinkedHashMap<>();
    private String committedOfbizHome;

    @BeforeEach
    public void redirectDeploymentAtATemporaryDirectory() throws IOException {
        assertNotNull(UtilProperties.getProperties(RESOURCE), "the [" + RESOURCE + "] resource must resolve");
        for (String property : TOUCHED) {
            committed.put(property, UtilProperties.getPropertyValue(RESOURCE, property));
        }
        committedOfbizHome = System.getProperty("ofbiz.home");
        System.setProperty("ofbiz.home", home.toString());
        Files.createDirectories(home.resolve("runtime/uploads"));
    }

    @AfterEach
    public void restoreTheCommittedConfiguration() {
        for (Map.Entry<String, String> property : committed.entrySet()) {
            UtilProperties.setPropertyValueInMemory(RESOURCE, property.getKey(), property.getValue());
        }
        if (committedOfbizHome == null) {
            System.clearProperty("ofbiz.home");
        } else {
            System.setProperty("ofbiz.home", committedOfbizHome);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Provider selection
    // ---------------------------------------------------------------------------------------------

    @Test
    public void theShippedDefaultSelectsNoProvider() throws GeneralException {
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROVIDER, "database");
        assertNull(ContentStoreFactory.getContentStore(),
                "database storage must resolve no external provider, so the seam stays inert");
    }

    @Test
    public void anUnsetProviderSelectsNoProvider() throws GeneralException {
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROVIDER, "");
        assertNull(ContentStoreFactory.getContentStore(), "an unset provider must fall back to database storage");
    }

    @Test
    public void filesystemSelectsTheFilesystemProvider() throws GeneralException {
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROVIDER, "filesystem");
        assertInstanceOf(FileSystemContentStore.class, ContentStoreFactory.getContentStore());
    }

    @Test
    public void theProviderNameIsCaseInsensitive() throws GeneralException {
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROVIDER, "FileSystem");
        assertInstanceOf(FileSystemContentStore.class, ContentStoreFactory.getContentStore());
    }

    @Test
    public void s3SelectsTheS3Provider() throws GeneralException {
        configureS3("http://127.0.0.1:1/");
        assertInstanceOf(S3ContentStore.class, ContentStoreFactory.getContentStore());
    }

    @Test
    public void anUnrecognisedProviderIsRefused() {
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROVIDER, "gcs");
        GeneralException refused = assertThrows(GeneralException.class, ContentStoreFactory::getContentStore);
        assertTrue(refused.getMessage().contains("gcs"), "the refusal must name the value it refused");
    }

    @Test
    public void oneResolutionIsReusedUntilTheConfigurationChanges() throws GeneralException {
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROVIDER, "filesystem");
        assertSameInstance(ContentStoreFactory.getContentStore(), ContentStoreFactory.getContentStore());
        configureS3("http://127.0.0.1:1/");
        assertInstanceOf(S3ContentStore.class, ContentStoreFactory.getContentStore(),
                "a changed configuration must resolve a new provider rather than reuse the cached one");
    }

    // ---------------------------------------------------------------------------------------------
    // The S3 provider's configuration contract
    // ---------------------------------------------------------------------------------------------

    @Test
    public void s3RequiresABucketAndARegion() {
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROVIDER, "s3");
        UtilProperties.setPropertyValueInMemory(RESOURCE, S3_REGION, "us-east-1");
        UtilProperties.setPropertyValueInMemory(RESOURCE, S3_BUCKET, "");
        assertTrue(assertThrows(GeneralException.class, ContentStoreFactory::getContentStore)
                .getMessage().contains(S3_BUCKET));

        UtilProperties.setPropertyValueInMemory(RESOURCE, S3_BUCKET, "a-bucket");
        UtilProperties.setPropertyValueInMemory(RESOURCE, S3_REGION, "");
        assertTrue(assertThrows(GeneralException.class, ContentStoreFactory::getContentStore)
                .getMessage().contains(S3_REGION));
    }

    @Test
    public void s3RefusesHalfACredentialPair() {
        configureS3("");
        UtilProperties.setPropertyValueInMemory(RESOURCE, S3_SECRET_KEY, "");
        assertTrue(assertThrows(GeneralException.class, ContentStoreFactory::getContentStore)
                .getMessage().contains(S3_SECRET_KEY), "half a credential pair must be refused, not half-applied");
    }

    @Test
    public void s3RefusesAnEndpointThatIsNotAnAbsoluteHttpUri() {
        configureS3("s3.example.internal:9000");
        assertTrue(assertThrows(GeneralException.class, ContentStoreFactory::getContentStore)
                .getMessage().contains(S3_ENDPOINT));
    }

    @Test
    public void s3AcceptsAnEndpointOverrideWithPathStyleAddressing() throws GeneralException {
        configureS3("https://s3.example.internal:9000");
        UtilProperties.setPropertyValueInMemory(RESOURCE, S3_PATH_STYLE, "true");
        assertInstanceOf(S3ContentStore.class, ContentStoreFactory.getContentStore());
    }

    // ---------------------------------------------------------------------------------------------
    // The provider contract, exercised against the filesystem provider
    // ---------------------------------------------------------------------------------------------

    @Test
    public void contentRoundTripsThroughEveryOperation() throws GeneralException, IOException {
        ContentStore store = new FileSystemContentStore(home.resolve("store").toString());
        byte[] content = "the-content".getBytes(StandardCharsets.UTF_8);

        assertFalse(store.exists("runtime/uploads/1/10000.txt"), "nothing is held before a put");
        store.put("runtime/uploads/1/10000.txt", new ByteArrayInputStream(content), content.length);

        assertTrue(store.exists("runtime/uploads/1/10000.txt"));
        assertArrayEquals(content, store.get("runtime/uploads/1/10000.txt"));
        try (InputStream opened = store.openStream("runtime/uploads/1/10000.txt")) {
            assertArrayEquals(content, opened.readAllBytes());
        }

        store.delete("runtime/uploads/1/10000.txt");
        assertFalse(store.exists("runtime/uploads/1/10000.txt"));
        store.delete("runtime/uploads/1/10000.txt");
    }

    @Test
    public void aReplacementOfEqualLengthIsVisibleInFull() throws GeneralException, IOException {
        ContentStore store = new FileSystemContentStore(home.resolve("store").toString());
        put(store, "runtime/uploads/1/10000.txt", "first-version");
        put(store, "runtime/uploads/1/10000.txt", "secnd-version");
        assertEquals("secnd-version", new String(store.get("runtime/uploads/1/10000.txt"), StandardCharsets.UTF_8),
                "a replacement of the same length must replace the whole object");
    }

    @Test
    public void absenceIsReportedAsFileNotFoundAndNothingElseIs() throws GeneralException, IOException {
        ContentStore store = new FileSystemContentStore(home.resolve("store").toString());
        assertThrows(FileNotFoundException.class, () -> store.get("runtime/uploads/1/absent.txt"));
        assertThrows(FileNotFoundException.class, () -> store.openStream("runtime/uploads/1/absent.txt"));
        assertFalse(store.exists("runtime/uploads/1/absent.txt"));
    }

    @Test
    public void aKeyThatBreaksTheGrammarIsRefused() throws GeneralException {
        ContentStore store = new FileSystemContentStore(home.resolve("store").toString());
        for (String key : new String[] {"", "/absolute/path", "runtime//uploads/x", "runtime/../../escape",
                "runtime\\uploads\\x", "runtime/uploads/"}) {
            assertThrows(GeneralException.class, () -> store.exists(key), "key [" + key + "] must be refused");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The worker's storage seam
    // ---------------------------------------------------------------------------------------------

    @Test
    public void aStandardUploadIsPublishedOnCommitAndReadBackAfterTheLocalCopyIsGone()
            throws GeneralException, IOException {
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROVIDER, "filesystem");
        ContentStore store = ContentStoreFactory.getContentStore();

        Path uploaded = uploadInOneTransaction("10000.txt", "the-uploaded-content");
        String key = home.relativize(uploaded).toString();
        assertTrue(store.exists(key), "an upload must be published to the store by the time its transaction commits");
        assertEquals("the-uploaded-content", new String(store.get(key), StandardCharsets.UTF_8));

        // The standard upload service records this exact absolute path as a LOCAL_FILE objectInfo, so the
        // read has to pass the LOCAL_FILE allow-list before the provider is consulted. Removing the local
        // copy is what a replaced instance looks like.
        Files.delete(uploaded);
        File resolved = DataResourceWorker.getContentFile("LOCAL_FILE", uploaded.toString(), null);
        assertTrue(resolved.exists(), "the local copy must be read back from the store");
        assertEquals("the-uploaded-content", Files.readString(resolved.toPath()));
    }

    @Test
    public void aRewriteOfEqualLengthAndUnchangedModificationTimeIsStillPublished()
            throws GeneralException, IOException {
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROVIDER, "filesystem");
        ContentStore store = ContentStoreFactory.getContentStore();

        Path uploaded = uploadInOneTransaction("10000.txt", "first-version");
        String key = home.relativize(uploaded).toString();
        FileTime unchanged = Files.getLastModifiedTime(uploaded);

        boolean began = TransactionUtil.begin();
        DataResourceWorker.getDataResourceContentUploadPath(true);
        Files.writeString(uploaded, "secnd-version", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(uploaded, unchanged);
        assertEquals("first-version".length(), Files.size(uploaded), "the rewrite must be of equal length");
        assertEquals(unchanged, Files.getLastModifiedTime(uploaded), "the rewrite must keep the timestamp");
        commit(began);

        assertEquals("secnd-version", new String(store.get(key), StandardCharsets.UTF_8),
                "a rewrite that changes neither length nor timestamp must still be published, or the store"
                        + " keeps serving the previous content for ever");
    }

    @Test
    public void aPublicationFailureRollsTheTransactionBack() throws GeneralException {
        // An endpoint that refuses every connection: the store cannot take the content, and a row naming it
        // must therefore not be committed.
        configureS3("http://127.0.0.1:1/");
        assertNotNull(ContentStoreFactory.getContentStore());

        boolean began = TransactionUtil.begin();
        assertThrows(Exception.class, () -> {
            String directory = DataResourceWorker.getDataResourceContentUploadPath(true);
            Files.writeString(Path.of(directory, "10000.txt"), "unpublishable", StandardCharsets.UTF_8);
            commit(began);
        }, "the commit must fail when the content could not be published");
    }

    @Test
    public void aStoreThatCannotAnswerIsAFailureAndNotAnAbsence() throws IOException {
        configureS3("http://127.0.0.1:1/");
        Path absent = home.resolve("runtime/uploads/1/10000.txt");
        Files.createDirectories(absent.getParent());

        Exception raised = assertThrows(Exception.class, () ->
                DataResourceWorker.getContentFile("LOCAL_FILE", absent.toString(), null));
        assertInstanceOf(GeneralException.class, raised,
                "a store that cannot answer must raise a failure, not report the content missing");
        assertFalse(raised instanceof FileNotFoundException,
                "FileNotFoundException means the store does not hold the content, which is not what happened");
    }

    @Test
    public void theSeamIsInertUnderTheShippedDefault() throws GeneralException, IOException {
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROVIDER, "database");

        Path uploaded = uploadInOneTransaction("10000.txt", "local-only");
        assertFalse(Files.exists(home.resolve("runtime/contentstore")),
                "database storage must publish nothing at all");
        assertTrue(Files.exists(uploaded), "the upload must still be where it has always been");
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    /**
     * Resolves an upload directory inside a transaction, writes a file into it and commits, which is the
     * sequence {@code attachUploadToDataResource} performs for a LOCAL_FILE upload.
     *
     * @param name the file name the upload service would compose from the dataResourceId
     * @param content what to write
     * @return the file that was written
     * @throws IOException if the file cannot be written
     * @throws GeneralException if the transaction cannot be begun or committed
     */
    private Path uploadInOneTransaction(String name, String content) throws IOException, GeneralException {
        boolean began = TransactionUtil.begin();
        String directory = DataResourceWorker.getDataResourceContentUploadPath(true);
        Path uploaded = Path.of(directory, name);
        Files.writeString(uploaded, content, StandardCharsets.UTF_8);
        commit(began);
        return uploaded;
    }

    /**
     * Commits, reporting a rolled-back transaction as a checked failure.
     *
     * @param began whether this test began the transaction
     * @throws GeneralException if the transaction could not be committed
     */
    private static void commit(boolean began) throws GeneralException {
        try {
            TransactionUtil.commit(began);
        } catch (Exception e) {
            throw new GeneralException(e);
        }
    }

    /**
     * Stores content under a key.
     *
     * @param store the store
     * @param key the storage key
     * @param content what to store
     * @throws GeneralException if the key is unusable
     * @throws IOException if the store cannot be written
     */
    private static void put(ContentStore store, String key, String content) throws GeneralException, IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        store.put(key, new ByteArrayInputStream(bytes), bytes.length);
    }

    /**
     * Declares a complete, valid S3 configuration with the given endpoint.
     *
     * @param endpoint the endpoint to declare, which may be blank for Amazon S3
     */
    private static void configureS3(String endpoint) {
        UtilProperties.setPropertyValueInMemory(RESOURCE, PROVIDER, "s3");
        UtilProperties.setPropertyValueInMemory(RESOURCE, S3_BUCKET, "a-bucket");
        UtilProperties.setPropertyValueInMemory(RESOURCE, S3_REGION, "us-east-1");
        UtilProperties.setPropertyValueInMemory(RESOURCE, S3_ENDPOINT, endpoint);
        UtilProperties.setPropertyValueInMemory(RESOURCE, S3_ACCESS_KEY, "an-access-key");
        UtilProperties.setPropertyValueInMemory(RESOURCE, S3_SECRET_KEY, "a-secret-key");
        UtilProperties.setPropertyValueInMemory(RESOURCE, S3_PATH_STYLE, "true");
    }

    /**
     * Asserts that two resolutions returned the very same provider instance.
     *
     * @param first the first resolution
     * @param second the second resolution
     */
    private static void assertSameInstance(ContentStore first, ContentStore second) {
        assertSame(first, second, "an unchanged configuration must reuse one provider instance");
    }
}
