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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.transaction.Status;
import javax.transaction.Synchronization;

import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.GeneralRuntimeException;
import org.apache.ofbiz.content.data.DataResourceWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the storage-aware bridge on {@link ContentStoreFactory}: how a data-resource
 * location becomes a durable storage key, how a stored object is handed to a caller that needs a
 * real local file, how a write through that file reaches the store, and how an upload is staged and
 * published.
 *
 * <p><strong>How a provider is made active.</strong> The bridge asks
 * {@link ContentStoreFactory#getContentStore()} for the active provider, which reads
 * {@code content.store.provider} from the {@code content} resource - and the copy on the test
 * classpath carries the committed default, {@code database}. Rather than rewrite a classpath
 * resource mid-run, these tests place a recording provider into the factory's resolution cache under
 * that same committed value, so the very code path a deployment runs is exercised with a store whose
 * every read and write can be asserted. Not placing one leaves the committed configuration in force,
 * which is how the database-mode tests below confirm the bridge is inert.
 *
 * <p><strong>Why the publications are constructed directly.</strong> Publication is registered with
 * the current transaction, and a unit test has no transaction factory, so
 * {@code TransactionUtil.isTransactionInPlace} answers that there is no transaction infrastructure
 * and nothing is registered - which is itself asserted here. The two publication callbacks are
 * therefore built with the arguments the bridge builds them with and driven directly, which is the
 * only way to assert what happens on commit and on rollback without a running entity container.
 */
public final class ContentStoreFactoryBridgeTests {

    private static final String UPLOAD_PREFIX = "runtime/uploads";
    private static final String STAGING_SEGMENT = ".contentstore-staging";
    private static final byte[] STORED = "stored bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] REPLACEMENT = "written bytes".getBytes(StandardCharsets.UTF_8);

    private Path home;
    private String previousHome;
    private RecordingContentStore store;

    @BeforeEach
    public void giveThisTestItsOwnHomeAndProvider() throws Exception {
        ContentStoreFactory.clearCache();
        home = Files.createTempDirectory("blitzy-content-bridge").toRealPath();
        previousHome = System.getProperty("ofbiz.home");
        System.setProperty("ofbiz.home", home.toString());
        store = new RecordingContentStore();
    }

    @AfterEach
    public void leaveNoHomeProviderOrStagedFileBehind() throws Exception {
        ContentStoreFactory.clearCache();
        if (previousHome == null) {
            System.clearProperty("ofbiz.home");
        } else {
            System.setProperty("ofbiz.home", previousHome);
        }
        deleteRecursively(home.toFile());
    }

    // --- key derivation -----------------------------------------------------------------------

    @Test
    public void everyLocalFileFormDerivesTheKeyRelativeToTheHomeDirectory() throws Exception {
        String absolute = home + "/" + UPLOAD_PREFIX + "/10000.png";
        assertEquals("runtime/uploads/10000.png", ContentStoreFactory.storageKeyFor("LOCAL_FILE", absolute, null));
        assertEquals("runtime/uploads/10000.png", ContentStoreFactory.storageKeyFor("LOCAL_FILE_BIN", absolute, null));
        assertEquals("runtime/uploads/10000.png", ContentStoreFactory.storageKeyFor("", absolute, null),
                "an empty type identifier reads as LOCAL_FILE, exactly as DataServices.createFileMethod reads it");
        assertEquals("runtime/uploads/10000.png", ContentStoreFactory.storageKeyFor(null, absolute, null));
    }

    @Test
    public void everyOfbizFileFormDerivesTheKeyFromTheLocationItself() throws Exception {
        assertEquals("runtime/uploads/10000.png",
                ContentStoreFactory.storageKeyFor("OFBIZ_FILE", "/" + UPLOAD_PREFIX + "/10000.png", null));
        assertEquals("runtime/uploads/10000.png",
                ContentStoreFactory.storageKeyFor("OFBIZ_FILE_BIN", UPLOAD_PREFIX + "/10000.png", null));
    }

    @Test
    public void theAbsoluteAndRelativeFormsOfOneUploadShareOneKey() throws Exception {
        String viaLocal = ContentStoreFactory.storageKeyFor("LOCAL_FILE", home + "/" + UPLOAD_PREFIX + "/1.png", null);
        String viaOfbiz = ContentStoreFactory.storageKeyFor("OFBIZ_FILE", "/" + UPLOAD_PREFIX + "/1.png", null);
        assertEquals(viaLocal, viaOfbiz,
                "content must not be orphaned by content.upload.always.local.file flipping");
    }

    @Test
    public void everyContextFileFormCarriesItsContextRootIntoTheKey() throws Exception {
        String contentRoot = home + "/applications/content/webapp/content";
        String partyRoot = home + "/applications/party/webapp/partymgr";
        String forContent = ContentStoreFactory.storageKeyFor("CONTEXT_FILE", "images/logo.png", contentRoot);
        String forParty = ContentStoreFactory.storageKeyFor("CONTEXT_FILE_BIN", "images/logo.png", partyRoot);
        assertEquals("context/applications/content/webapp/content/images/logo.png", forContent);
        assertNotEquals(forContent, forParty,
                "two webapps holding the same relative location must address different objects");
        assertEquals(forContent, ContentStoreFactory.storageKeyFor("CONTEXT_FILE", "/images/logo.png",
                contentRoot + "/"), "a padded root and a rooted location must not change the key");
    }

    @Test
    public void aContextFileWithoutAContextRootIsRefused() {
        for (String absent : new String[] {null, "", " ", "\t"}) {
            GeneralException refused = assertThrows(GeneralException.class, () ->
                    ContentStoreFactory.storageKeyFor("CONTEXT_FILE", "images/logo.png", absent));
            assertTrue(refused.getMessage().contains("empty context root"), refused.getMessage());
        }
    }

    @Test
    public void aTypeIdentifierThatIsNotOneOfTheSixIsRefusedRatherThanGuessed() {
        GeneralException refused = assertThrows(GeneralException.class, () ->
                ContentStoreFactory.storageKeyFor("IMAGE_OBJECT", "/runtime/uploads/1.png", null));
        assertTrue(refused.getMessage().contains("not one of the six"), refused.getMessage());
    }

    @Test
    public void anEmptyLocationIsRefused() {
        assertThrows(GeneralException.class, () -> ContentStoreFactory.storageKeyFor("OFBIZ_FILE", " ", null));
        assertThrows(GeneralException.class, () -> ContentStoreFactory.storageKeyFor("OFBIZ_FILE", null, null));
    }

    @Test
    public void aKeyNeverStartsWithASeparatorAndNeverRepeatsOne() throws Exception {
        String key = ContentStoreFactory.storageKeyFor("OFBIZ_FILE", "//runtime//uploads//1.png", null);
        assertEquals("runtime/uploads/1.png", key,
                "a leading separator would create an empty-named top level entry in an object store");
    }

    @Test
    public void anUpwardTraversalIsRefusedBeforeAnyProviderSeesIt() {
        GeneralException refused = assertThrows(GeneralException.class, () ->
                ContentStoreFactory.storageKeyFor("OFBIZ_FILE", "/runtime/uploads/../../etc/shadow", null));
        assertTrue(refused.getMessage().contains("traverses above its root"), refused.getMessage());
    }

    @Test
    public void aLocationNamingNoContentIsRefused() {
        assertThrows(GeneralException.class, () ->
                ContentStoreFactory.storageKeyFor("OFBIZ_FILE", "/runtime/uploads/", null));
        assertThrows(GeneralException.class, () ->
                ContentStoreFactory.storageKeyFor("LOCAL_FILE", home.toString(), null));
    }

    @Test
    public void theStagingRunNeverReachesTheKey() throws Exception {
        String staged = home + "/" + UPLOAD_PREFIX + "/" + STAGING_SEGMENT + "/7/10000.png";
        String plain = home + "/" + UPLOAD_PREFIX + "/10000.png";
        assertEquals(ContentStoreFactory.storageKeyFor("LOCAL_FILE", plain, null),
                ContentStoreFactory.storageKeyFor("LOCAL_FILE", staged, null),
                "a key has to survive the staging directory being removed");
        assertEquals("runtime/uploads/10000.png", ContentStoreFactory.storageKeyFor("OFBIZ_FILE",
                "/" + UPLOAD_PREFIX + "/" + STAGING_SEGMENT + "/318/10000.png", null));
    }

    // --- upload allocation --------------------------------------------------------------------

    @Test
    public void allocatingAStagingPathAnswersBothFormsTheUploadFlowUses() throws Exception {
        activateRecordingProvider();
        String absolute = ContentStoreFactory.uploadStagingPath(UPLOAD_PREFIX, true);
        String relative = ContentStoreFactory.uploadStagingPath(UPLOAD_PREFIX, false);
        assertTrue(absolute.startsWith(home + "/" + UPLOAD_PREFIX + "/" + STAGING_SEGMENT + "/"), absolute);
        assertTrue(new File(absolute).isDirectory(), "the absolute form has to be a directory that exists");
        assertTrue(relative.startsWith("/" + UPLOAD_PREFIX + "/" + STAGING_SEGMENT + "/"), relative);
        assertTrue(new File(home + relative).isDirectory(), "the relative form has to resolve under ofbiz.home");
    }

    @Test
    public void anUnrootedPrefixIsRootedTheWayThePreExistingSeamRootsIt() throws Exception {
        activateRecordingProvider();
        assertTrue(ContentStoreFactory.uploadStagingPath(UPLOAD_PREFIX, false).startsWith("/"));
        assertTrue(ContentStoreFactory.uploadStagingPath("/" + UPLOAD_PREFIX, false).startsWith("/" + UPLOAD_PREFIX));
    }

    @Test
    public void everyAllocationGetsItsOwnDirectorySoConcurrentUploadsCannotMix() throws Exception {
        activateRecordingProvider();
        Set<String> allocated = new LinkedHashSet<>();
        for (int i = 0; i < 8; i++) {
            allocated.add(ContentStoreFactory.uploadStagingPath(UPLOAD_PREFIX, true));
        }
        assertEquals(8, allocated.size(), "publishing a whole directory is only safe if it belongs to one upload");
    }

    @Test
    public void allocationNeverFansOutIntoNumberedDirectoriesAndNeverMovesAKey() throws Exception {
        activateRecordingProvider();
        String expected = null;
        for (int i = 0; i < 300; i++) {
            String uploadPath = ContentStoreFactory.uploadStagingPath(UPLOAD_PREFIX, true);
            String key = ContentStoreFactory.storageKeyFor("LOCAL_FILE", uploadPath + "/10000.png", null);
            if (expected == null) {
                expected = key;
            }
            assertEquals(expected, key, "flat keys must not depend on which staging directory was used");
        }
        assertEquals("runtime/uploads/10000.png", expected);
        File[] children = new File(home + "/" + UPLOAD_PREFIX).listFiles();
        assertNotNull(children);
        assertEquals(1, children.length, "the numbered content.upload.max.files rotation has nothing to do here");
        assertEquals(STAGING_SEGMENT, children[0].getName());
    }

    @Test
    public void allocatingAStagingPathIsInertWhileDatabaseStorageIsConfigured() {
        assertNull(ContentStoreFactory.uploadStagingPath(UPLOAD_PREFIX, true));
        assertNull(ContentStoreFactory.uploadStagingPath(UPLOAD_PREFIX, false));
        assertFalse(new File(home + "/" + UPLOAD_PREFIX).exists(),
                "database mode must not create so much as a directory");
    }

    @Test
    public void anUnusableStagingLocationFailsLoudlyRatherThanSilentlyStayingLocal() throws Exception {
        activateRecordingProvider();
        // A regular file where the staging directory has to go makes the directory impossible to
        // create, which is the one way this can fail on a healthy filesystem.
        File blocker = new File(home + "/" + UPLOAD_PREFIX);
        assertTrue(blocker.getParentFile().mkdirs() || blocker.getParentFile().isDirectory());
        write(blocker, STORED);
        assertThrows(GeneralRuntimeException.class, () -> ContentStoreFactory.uploadStagingPath(UPLOAD_PREFIX, true));
    }

    // --- reading ------------------------------------------------------------------------------

    @Test
    public void materialisingHandsBackExactlyTheFileTheFrozenCallersCompute() throws Exception {
        activateRecordingProvider();
        store.hold("runtime/uploads/10000.png", STORED);
        String objectInfo = home + "/" + UPLOAD_PREFIX + "/10000.png";
        File materialised = ContentStoreFactory.materialiseContentFile("LOCAL_FILE", objectInfo, null);
        assertNotNull(materialised);
        assertEquals(objectInfo, materialised.getAbsolutePath().replace('\\', '/'),
                "DataServices writes to new File(objectInfo), so the staged file has to be exactly there");
        assertArrayEqualsBytes(STORED, Files.readAllBytes(materialised.toPath()));
        assertEquals(List.of("runtime/uploads/10000.png"), store.reads());
    }

    @Test
    public void materialisingWorksForEveryRootIncludingTheSeparateContextRoot() throws Exception {
        activateRecordingProvider();
        String contextRoot = home + "/applications/content/webapp/content";
        store.hold("runtime/uploads/a.txt", STORED);
        store.hold("context/applications/content/webapp/content/images/logo.png", STORED);
        File viaOfbiz = ContentStoreFactory.materialiseContentFile("OFBIZ_FILE", "/runtime/uploads/a.txt", null);
        File viaContext = ContentStoreFactory.materialiseContentFile("CONTEXT_FILE", "images/logo.png", contextRoot);
        assertEquals(home + "/runtime/uploads/a.txt", viaOfbiz.getAbsolutePath().replace('\\', '/'));
        assertEquals(contextRoot + "/images/logo.png", viaContext.getAbsolutePath().replace('\\', '/'));
        assertArrayEqualsBytes(STORED, Files.readAllBytes(viaContext.toPath()));
    }

    @Test
    public void materialisingAbsentContentReportsItTheWayThePreExistingLocalPathDoes() throws Exception {
        activateRecordingProvider();
        String objectInfo = "/" + UPLOAD_PREFIX + "/missing.png";
        FileNotFoundException absent = assertThrows(FileNotFoundException.class, () ->
                ContentStoreFactory.materialiseContentFile("OFBIZ_FILE", objectInfo, null));
        assertEquals("No file found: " + objectInfo, absent.getMessage(),
                "DataServices catches FileNotFoundException, so the shape of this report is frozen");
        assertNotNull(absent.getCause(), "the store's own report has to stay attached for diagnosis");
    }

    @Test
    public void aFileStagedEarlierInTheSameTransactionIsNotOverwrittenFromTheStore() throws Exception {
        activateRecordingProvider();
        store.hold("runtime/uploads/10000.png", STORED);
        String objectInfo = home + "/" + UPLOAD_PREFIX + "/10000.png";
        File staged = new File(objectInfo);
        assertTrue(staged.getParentFile().mkdirs());
        write(staged, REPLACEMENT);
        File materialised = ContentStoreFactory.materialiseContentFile("LOCAL_FILE", objectInfo, null);
        assertArrayEqualsBytes(REPLACEMENT, Files.readAllBytes(materialised.toPath()));
        assertTrue(store.reads().isEmpty(), "unpublished local content is the content of record");
    }

    @Test
    public void materialisingIsInertWhileDatabaseStorageIsConfigured() throws Exception {
        String objectInfo = home + "/" + UPLOAD_PREFIX + "/10000.png";
        assertNull(ContentStoreFactory.materialiseContentFile("LOCAL_FILE", objectInfo, null));
        assertFalse(new File(objectInfo).exists());
        assertTrue(store.reads().isEmpty());
    }

    @Test
    public void openingAStreamServesTheRendererWithoutStagingAnythingLocally() throws Exception {
        activateRecordingProvider();
        store.hold("runtime/uploads/a.txt", STORED);
        try (InputStream opened = ContentStoreFactory.openContentStream("OFBIZ_FILE", "/runtime/uploads/a.txt", null)) {
            assertNotNull(opened);
            assertArrayEqualsBytes(STORED, opened.readAllBytes());
        }
        assertFalse(new File(home + "/runtime/uploads/a.txt").exists(),
                "renderFile only copies bytes, so it must not leave a local file behind");
    }

    @Test
    public void openingAStreamIsInertWhileDatabaseStorageIsConfigured() throws Exception {
        assertNull(ContentStoreFactory.openContentStream("OFBIZ_FILE", "/runtime/uploads/a.txt", null));
    }

    // --- writing ------------------------------------------------------------------------------

    @Test
    public void publishingSendsTheStagedBytesToTheStoreAndRemovesTheLocalFile() throws Exception {
        activateRecordingProvider();
        String objectInfo = "/" + UPLOAD_PREFIX + "/10000.png";
        File staged = new File(home + objectInfo);
        assertTrue(staged.getParentFile().mkdirs());
        write(staged, REPLACEMENT);
        assertTrue(ContentStoreFactory.publishContentFile("OFBIZ_FILE", objectInfo, null));
        assertArrayEqualsBytes(REPLACEMENT, store.held("runtime/uploads/10000.png"));
        assertFalse(staged.exists(), "an instance must keep no durable local state");
    }

    @Test
    public void publishingWithNothingStagedIsReportedAsNotFound() throws Exception {
        activateRecordingProvider();
        assertThrows(FileNotFoundException.class, () ->
                ContentStoreFactory.publishContentFile("OFBIZ_FILE", "/runtime/uploads/1.png", null));
    }

    @Test
    public void publishingIsInertWhileDatabaseStorageIsConfigured() throws Exception {
        assertFalse(ContentStoreFactory.publishContentFile("OFBIZ_FILE", "/runtime/uploads/1.png", null));
        assertTrue(store.writes().isEmpty());
    }

    @Test
    public void aRefusedWriteIsReportedRatherThanLostQuietly() throws Exception {
        activateRecordingProvider();
        store.refuseWrites();
        String objectInfo = "/" + UPLOAD_PREFIX + "/10000.png";
        File staged = new File(home + objectInfo);
        assertTrue(staged.getParentFile().mkdirs());
        write(staged, REPLACEMENT);
        assertThrows(GeneralException.class, () ->
                ContentStoreFactory.publishContentFile("OFBIZ_FILE", objectInfo, null));
    }

    // --- guards -------------------------------------------------------------------------------

    @Test
    public void aLocationOutsideTheAllowListIsRefusedBeforeAnythingIsStaged() throws Exception {
        activateRecordingProvider();
        store.hold("etc/shadow", STORED);
        assertThrows(GeneralException.class, () ->
                ContentStoreFactory.materialiseContentFile("OFBIZ_FILE", "/etc/shadow", null));
    }

    @Test
    public void aLocalFileLocationThatIsNotAbsoluteIsRefused() throws Exception {
        activateRecordingProvider();
        assertThrows(GeneralException.class, () ->
                ContentStoreFactory.materialiseContentFile("LOCAL_FILE", "runtime/uploads/1.png", null));
    }

    @Test
    public void aContextFileLocationEscapingItsRootIsRefused() throws Exception {
        activateRecordingProvider();
        String contextRoot = home + "/applications/content/webapp/content";
        assertTrue(new File(contextRoot).mkdirs());
        assertThrows(GeneralException.class, () -> ContentStoreFactory.materialiseContentFile("CONTEXT_FILE",
                "images/../../../../etc/shadow", contextRoot));
    }

    // --- publication on commit and on rollback ------------------------------------------------

    @Test
    public void noPublicationIsRegisteredWhereThereIsNoTransactionInfrastructure() throws Exception {
        activateRecordingProvider();
        store.hold("runtime/uploads/10000.png", STORED);
        File materialised = ContentStoreFactory.materialiseContentFile("LOCAL_FILE",
                home + "/" + UPLOAD_PREFIX + "/10000.png", null);
        assertNotNull(materialised, "a missing transaction factory must not make the bridge fail");
        assertTrue(store.writes().isEmpty(), "nothing can be published without a transaction to commit");
    }

    @Test
    public void aMaterialisedFileThatWasOnlyReadPublishesNothingOnCommit() throws Exception {
        activateRecordingProvider();
        File staged = stagedFileHolding(STORED);
        Synchronization publication = contentPublication("runtime/uploads/10000.png", staged, false);
        publication.beforeCompletion();
        assertTrue(store.writes().isEmpty(), "a read must not cost a write");
    }

    @Test
    public void aMaterialisedFileThatWasWrittenThroughIsPublishedOnCommit() throws Exception {
        activateRecordingProvider();
        File staged = stagedFileHolding(STORED);
        Synchronization publication = contentPublication("runtime/uploads/10000.png", staged, false);
        write(staged, REPLACEMENT);
        publication.beforeCompletion();
        assertArrayEqualsBytes(REPLACEMENT, store.held("runtime/uploads/10000.png"));
    }

    @Test
    public void aWriteKeepingTheByteCountIdenticalIsStillPublished() throws Exception {
        activateRecordingProvider();
        File staged = stagedFileHolding(STORED);
        Synchronization publication = contentPublication("runtime/uploads/10000.png", staged, false);
        byte[] sameLength = new byte[STORED.length];
        for (int i = 0; i < sameLength.length; i++) {
            sameLength[i] = (byte) ('x');
        }
        write(staged, sameLength);
        publication.beforeCompletion();
        assertArrayEqualsBytes(sameLength, store.held("runtime/uploads/10000.png"),
                "backdating the staged file is what makes a same-size write recognisable");
    }

    @Test
    public void contentAlreadyStagedBeforeMaterialisationIsAlwaysPublished() throws Exception {
        activateRecordingProvider();
        File staged = stagedFileHolding(REPLACEMENT);
        Synchronization publication = contentPublication("runtime/uploads/10000.png", staged, true);
        publication.beforeCompletion();
        assertArrayEqualsBytes(REPLACEMENT, store.held("runtime/uploads/10000.png"),
                "content written by DataServices.createFileMethod has never reached the store");
    }

    @Test
    public void aPublicationWhoseFileHasAlreadyGoneDoesNotRollTheTransactionBack() throws Exception {
        activateRecordingProvider();
        File staged = stagedFileHolding(REPLACEMENT);
        Synchronization publication = contentPublication("runtime/uploads/10000.png", staged, true);
        assertTrue(staged.delete());
        publication.beforeCompletion();
        assertTrue(store.writes().isEmpty());
    }

    @Test
    public void aStoreRefusingTheWriteRollsTheTransactionBack() throws Exception {
        activateRecordingProvider();
        store.refuseWrites();
        File staged = stagedFileHolding(REPLACEMENT);
        Synchronization publication = contentPublication("runtime/uploads/10000.png", staged, true);
        GeneralRuntimeException refused = assertThrows(GeneralRuntimeException.class, publication::beforeCompletion);
        assertTrue(refused.getMessage().contains("rolled back"), refused.getMessage());
    }

    @Test
    public void aMaterialisedFileIsRemovedOnceTheTransactionCompletes() throws Exception {
        activateRecordingProvider();
        File staged = stagedFileHolding(STORED);
        contentPublication("runtime/uploads/10000.png", staged, false).afterCompletion(Status.STATUS_COMMITTED);
        assertFalse(staged.exists(), "no instance may hold durable local state");
    }

    @Test
    public void everyUploadStagedInADirectoryIsPublishedUnderItsFlatKeyOnCommit() throws Exception {
        activateRecordingProvider();
        File directory = allocatedStagingDirectory();
        write(new File(directory, "10000.png"), STORED);
        write(new File(directory, "10001.txt"), REPLACEMENT);
        Synchronization publication = uploadPublication(directory);
        publication.beforeCompletion();
        assertArrayEqualsBytes(STORED, store.held("runtime/uploads/10000.png"));
        assertArrayEqualsBytes(REPLACEMENT, store.held("runtime/uploads/10001.txt"));
        publication.afterCompletion(Status.STATUS_COMMITTED);
        assertFalse(directory.exists(), "the staging directory is an implementation detail of one transaction");
    }

    @Test
    public void anUploadStagedInARolledBackTransactionIsDiscardedUnpublished() throws Exception {
        activateRecordingProvider();
        File directory = allocatedStagingDirectory();
        write(new File(directory, "10000.png"), STORED);
        uploadPublication(directory).afterCompletion(Status.STATUS_ROLLEDBACK);
        assertTrue(store.writes().isEmpty(), "beforeCompletion never runs on a rollback");
        assertFalse(directory.exists());
    }

    @Test
    public void anEmptyStagingDirectoryPublishesNothingAndStillGoesAway() throws Exception {
        activateRecordingProvider();
        File directory = allocatedStagingDirectory();
        Synchronization publication = uploadPublication(directory);
        publication.beforeCompletion();
        publication.afterCompletion(Status.STATUS_COMMITTED);
        assertTrue(store.writes().isEmpty());
        assertFalse(directory.exists());
    }

    @Test
    public void aStoreRefusingAStagedUploadRollsTheTransactionBack() throws Exception {
        activateRecordingProvider();
        store.refuseWrites();
        File directory = allocatedStagingDirectory();
        write(new File(directory, "10000.png"), STORED);
        Synchronization publication = uploadPublication(directory);
        assertThrows(GeneralRuntimeException.class, publication::beforeCompletion);
    }

    // --- the production vertical, driven through DataResourceWorker ---------------------------
    //
    // Everything above drives the bridge directly. These tests instead call the frozen entry points
    // a deployment calls, so they fail if the seam in DataResourceWorker is ever removed - which is
    // the difference between a bridge that exists and a bridge that is actually reached.

    @Test
    public void theProductionReadPathServesContentFromTheStore() throws Exception {
        activateRecordingProvider();
        store.hold("runtime/uploads/10000.png", STORED);
        File served = DataResourceWorker.getContentFile("OFBIZ_FILE", UPLOAD_PREFIX + "/10000.png", null);
        assertNotNull(served, "the seam must never hand a null file back to the frozen callers");
        assertEquals(List.of("runtime/uploads/10000.png"), store.reads(), "the store has to be the one that served it");
        assertArrayEqualsBytes(STORED, Files.readAllBytes(served.toPath()));
    }

    @Test
    public void theProductionReadPathServesEveryFileBackedTypeFromTheStore() throws Exception {
        activateRecordingProvider();
        store.hold("runtime/uploads/10000.png", STORED);
        assertNotNull(DataResourceWorker.getContentFile("LOCAL_FILE", home + "/" + UPLOAD_PREFIX + "/10000.png", null));
        assertNotNull(DataResourceWorker.getContentFile("OFBIZ_FILE_BIN", UPLOAD_PREFIX + "/10000.png", null));
        String contextRoot = home + "/" + UPLOAD_PREFIX;
        store.hold(ContentStoreFactory.storageKeyFor("CONTEXT_FILE", "10001.png", contextRoot), REPLACEMENT);
        assertNotNull(DataResourceWorker.getContentFile("CONTEXT_FILE", "10001.png", contextRoot));
    }

    @Test
    public void theProductionReadPathStaysEntirelyLocalWhileDatabaseStorageIsConfigured() throws Exception {
        File local = stagedFileHolding(STORED);
        File served = DataResourceWorker.getContentFile("OFBIZ_FILE", UPLOAD_PREFIX + "/10000.png", null);
        assertEquals(local.getCanonicalFile(), served.getCanonicalFile(), "the committed default resolves locally");
        assertTrue(store.reads().isEmpty(), "database mode must not consult a provider at all");
    }

    @Test
    public void theProductionReadPathReportsAbsentStoredContentTheWayTheLocalPathDoes() throws Exception {
        activateRecordingProvider();
        assertThrows(FileNotFoundException.class, () -> DataResourceWorker.getContentFile("OFBIZ_FILE",
                UPLOAD_PREFIX + "/missing.png", null),
                "the frozen callers catch FileNotFoundException, so absence has to keep arriving as one");
    }

    @Test
    public void theProductionRenderPathStreamsFromTheStoreWithoutStagingAnything() throws Exception {
        activateRecordingProvider();
        store.hold("runtime/uploads/10000.txt", STORED);
        StringBuilder rendered = new StringBuilder();
        DataResourceWorker.renderFile("OFBIZ_FILE", UPLOAD_PREFIX + "/10000.txt", null, rendered);
        assertEquals(new String(STORED, StandardCharsets.UTF_8), rendered.toString());
        assertFalse(new File(home + "/" + UPLOAD_PREFIX + "/10000.txt").exists(),
                "rendering only copies bytes through, so it must leave nothing on the instance");
    }

    @Test
    public void theProductionRenderPathStaysLocalWhileDatabaseStorageIsConfigured() throws Exception {
        stagedFileHolding(STORED);
        StringBuilder rendered = new StringBuilder();
        DataResourceWorker.renderFile("OFBIZ_FILE", UPLOAD_PREFIX + "/10000.png", null, rendered);
        assertEquals(new String(STORED, StandardCharsets.UTF_8), rendered.toString());
        assertTrue(store.reads().isEmpty(), "database mode must not consult a provider at all");
    }

    @Test
    public void theProductionRenderPathLeavesTypesItHasNeverHandledAloneEvenInStoreMode() throws Exception {
        activateRecordingProvider();
        store.hold("runtime/uploads/10000.png", STORED);
        StringBuilder rendered = new StringBuilder();
        DataResourceWorker.renderFile("LOCAL_FILE_BIN", home + "/" + UPLOAD_PREFIX + "/10000.png", null, rendered);
        assertEquals("", rendered.toString(),
                "a _BIN type has never been rendered as text and selecting a provider must not start rendering it");
        assertTrue(store.reads().isEmpty());
    }

    @Test
    public void theProductionUploadPathStagesForTheStoreInsteadOfFanningOutLocally() throws Exception {
        activateRecordingProvider();
        String allocated = DataResourceWorker.getDataResourceContentUploadPath(UPLOAD_PREFIX, 250, true);
        assertTrue(allocated.contains(STAGING_SEGMENT), allocated);
        assertTrue(new File(allocated).isDirectory(), allocated);
    }

    @Test
    public void theProductionUploadPathFansOutLocallyWhileDatabaseStorageIsConfigured() {
        // The pre-existing local allocation creates only one directory level itself, so the prefix
        // has to be there already - which it is in any real deployment, and which selecting a
        // provider must not change.
        assertTrue(new File(home + "/" + UPLOAD_PREFIX).mkdirs());
        String allocated = DataResourceWorker.getDataResourceContentUploadPath(UPLOAD_PREFIX, 250, true);
        assertFalse(allocated.contains(STAGING_SEGMENT), allocated);
        assertTrue(new File(allocated).isDirectory(), allocated);
    }

    @Test
    public void theProductionUploadPathStagesTheRelativeFormTheOfbizFileFlowPersists() throws Exception {
        activateRecordingProvider();
        String allocated = DataResourceWorker.getDataResourceContentUploadPath(UPLOAD_PREFIX, 250, false);
        assertEquals('/', allocated.charAt(0), allocated);
        assertFalse(allocated.startsWith(home.toString()), "the relative form is what OFBIZ_FILE objectInfo holds");
        assertTrue(allocated.startsWith("/" + UPLOAD_PREFIX + "/" + STAGING_SEGMENT), allocated);
    }

    @Test
    public void theProductionUploadAndCreateVerticalPublishesToTheStoreOnCommit() throws Exception {
        activateRecordingProvider();
        String uploadPath = DataResourceWorker.getDataResourceContentUploadPath(UPLOAD_PREFIX, 250, true);
        // exactly what DataServices.createFileMethod does with the objectInfo built from that path
        write(new File(uploadPath, "10000.png"), STORED);
        uploadPublication(new File(uploadPath)).beforeCompletion();
        assertArrayEqualsBytes(STORED, store.held("runtime/uploads/10000.png"),
                "the numbered staging directory must never reach the key");
    }

    @Test
    public void theProductionBinaryWriteVerticalPublishesWhatTheFrozenCallerWrote() throws Exception {
        activateRecordingProvider();
        store.hold("runtime/uploads/10000.png", STORED);
        File served = DataResourceWorker.getContentFile("OFBIZ_FILE", UPLOAD_PREFIX + "/10000.png", null);
        // The publication is built here because that is where materialiseContentFile builds it - it
        // captures the fetched file's length and timestamp in order to recognise a later write - and
        // a unit test has no transaction for the seam to have registered it with.
        Synchronization publication = contentPublication("runtime/uploads/10000.png", served, false);
        // exactly what DataServices.createBinaryFileMethod and updateBinaryFileMethod do
        write(served, REPLACEMENT);
        publication.beforeCompletion();
        assertArrayEqualsBytes(REPLACEMENT, store.held("runtime/uploads/10000.png"));
    }

    // --- helpers ------------------------------------------------------------------------------

    /**
     * Places the recording provider into the factory's resolution cache under the committed
     * configuration value, so that the bridge resolves it through the very code a deployment runs.
     *
     * @throws Exception if the cache or the outcome type cannot be reached
     */
    private void activateRecordingProvider() throws Exception {
        Class<?> outcome = Class.forName(ContentStoreFactory.class.getName() + "$Resolution");
        Method succeeded = outcome.getDeclaredMethod("succeeded", String.class, ContentStore.class);
        succeeded.setAccessible(true);
        Field cache = ContentStoreFactory.class.getDeclaredField("RESOLUTIONS");
        cache.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> resolutions = (Map<String, Object>) cache.get(null);
        resolutions.put("database", succeeded.invoke(null, "database", store));
        assertNotNull(ContentStoreFactory.getContentStore(), "the recording provider has to be the active one");
    }

    /**
     * Builds the publication the bridge registers for a materialised content file.
     *
     * @param key the storage key the file belongs to
     * @param staged the materialised local file
     * @param alwaysPublish whether the file already held unpublished content
     * @return the publication, ready to be driven
     * @throws Exception if the publication cannot be constructed
     */
    private Synchronization contentPublication(String key, File staged, boolean alwaysPublish) throws Exception {
        Class<?> type = Class.forName(ContentStoreFactory.class.getName() + "$ContentFilePublication");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, File.class, boolean.class);
        constructor.setAccessible(true);
        return (Synchronization) constructor.newInstance(key, staged, alwaysPublish);
    }

    /**
     * Builds the publication the bridge registers for an allocated staging directory.
     *
     * @param directory the staging directory
     * @return the publication, ready to be driven
     * @throws Exception if the publication cannot be constructed
     */
    private Synchronization uploadPublication(File directory) throws Exception {
        Class<?> type = Class.forName(ContentStoreFactory.class.getName() + "$StagedUploadPublication");
        Constructor<?> constructor = type.getDeclaredConstructor(File.class);
        constructor.setAccessible(true);
        return (Synchronization) constructor.newInstance(directory);
    }

    /**
     * Allocates a staging directory through the bridge itself.
     *
     * @return the allocated directory
     */
    private File allocatedStagingDirectory() {
        String allocated = ContentStoreFactory.uploadStagingPath(UPLOAD_PREFIX, true);
        File directory = new File(allocated);
        assertTrue(directory.isDirectory(), allocated);
        return directory;
    }

    /**
     * Stages a file at the location a {@code LOCAL_FILE} upload occupies.
     *
     * @param content the bytes to stage
     * @return the staged file
     * @throws IOException if the file cannot be written
     */
    private File stagedFileHolding(byte[] content) throws IOException {
        File staged = new File(home + "/" + UPLOAD_PREFIX + "/10000.png");
        assertTrue(staged.getParentFile().isDirectory() || staged.getParentFile().mkdirs());
        write(staged, content);
        return staged;
    }

    /**
     * Writes bytes to a file the way the frozen callers write them, through a stream on the file
     * itself rather than through an atomic replacement.
     *
     * @param file the file to write
     * @param content the bytes to write
     * @throws IOException if the file cannot be written
     */
    private static void write(File file, byte[] content) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(content);
        }
    }

    /**
     * Removes a directory tree, tolerating anything already gone.
     *
     * @param root the tree to remove
     */
    private static void deleteRecursively(File root) {
        File[] children = root.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!root.delete() && root.exists()) {
            throw new IllegalStateException("Cannot remove the test directory " + root);
        }
    }

    /**
     * Compares two byte arrays, reporting their contents rather than their identity.
     *
     * @param expected the expected bytes
     * @param actual the actual bytes
     */
    private static void assertArrayEqualsBytes(byte[] expected, byte[] actual) {
        assertArrayEqualsBytes(expected, actual, "content");
    }

    /**
     * Compares two byte arrays, reporting their contents rather than their identity.
     *
     * @param expected the expected bytes
     * @param actual the actual bytes
     * @param what what is being compared, for the report
     */
    private static void assertArrayEqualsBytes(byte[] expected, byte[] actual, String what) {
        assertNotNull(actual, what);
        assertEquals(new String(expected, StandardCharsets.UTF_8), new String(actual, StandardCharsets.UTF_8), what);
    }

    /**
     * An in-memory provider that records every read and write, so that a test can assert exactly
     * which keys the bridge used and what it stored under them.
     */
    private static final class RecordingContentStore implements ContentStore {

        private final Map<String, byte[]> objects = new HashMap<>();
        private final List<String> reads = new ArrayList<>();
        private final List<String> writes = new ArrayList<>();
        private boolean refusing;

        @Override
        public void put(String key, byte[] data) throws GeneralException, IOException {
            if (refusing) {
                throw new IOException("this store refuses writes");
            }
            writes.add(key);
            objects.put(key, data.clone());
        }

        @Override
        public void put(String key, InputStream content, long length) throws GeneralException, IOException {
            if (refusing) {
                throw new IOException("this store refuses writes");
            }
            if (length < 0 || length > Integer.MAX_VALUE) {
                throw new GeneralException("A length of " + length + " cannot be stored under key [" + key + "]");
            }
            byte[] transferred = content.readNBytes((int) length);
            if (transferred.length != length) {
                // The SPI requires a short stream to leave any previous entry intact, so nothing is
                // recorded and nothing is replaced when the declared length is not delivered.
                throw new IOException("The stream for key [" + key + "] yielded " + transferred.length
                        + " byte(s) but " + length + " were declared");
            }
            writes.add(key);
            objects.put(key, transferred);
        }

        @Override
        public byte[] get(String key) throws GeneralException, IOException {
            reads.add(key);
            byte[] stored = objects.get(key);
            if (stored == null) {
                throw new FileNotFoundException("Nothing is stored under key [" + key + "]");
            }
            return stored.clone();
        }

        @Override
        public InputStream openStream(String key) throws GeneralException, IOException {
            return new ByteArrayInputStream(get(key));
        }

        @Override
        public long size(String key) throws GeneralException, IOException {
            byte[] stored = objects.get(key);
            if (stored == null) {
                // The same absence report the read path uses, so a caller never has to distinguish
                // "measured as absent" from "read as absent".
                throw new FileNotFoundException("Nothing is stored under key [" + key + "]");
            }
            return stored.length;
        }

        @Override
        public boolean exists(String key) {
            return objects.containsKey(key);
        }

        @Override
        public void delete(String key) {
            objects.remove(key);
        }

        @Override
        public void close() {
            // Nothing to release: this double holds its objects in a map, not in a pool, a thread or an
            // open handle. Declared without the interface's checked exceptions on purpose, so that a
            // failure observed in a bridge test can only have come from the bridge.
        }

        /**
         * Seeds a stored object.
         *
         * @param key the key to store it under
         * @param content the bytes to store
         */
        private void hold(String key, byte[] content) {
            objects.put(key, content.clone());
        }

        /**
         * Reads a stored object without recording the read.
         *
         * @param key the key to read
         * @return the stored bytes, or null when nothing is stored
         */
        private byte[] held(String key) {
            return objects.get(key);
        }

        /**
         * Makes every subsequent write fail.
         */
        private void refuseWrites() {
            refusing = true;
        }

        /**
         * Reports the keys read so far.
         *
         * @return the keys, in order
         */
        private List<String> reads() {
            return List.copyOf(reads);
        }

        /**
         * Reports the keys written so far.
         *
         * @return the keys, in order
         */
        private List<String> writes() {
            return List.copyOf(writes);
        }
    }
}
