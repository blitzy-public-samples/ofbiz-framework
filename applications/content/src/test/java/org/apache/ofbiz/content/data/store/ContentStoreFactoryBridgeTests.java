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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.transaction.Status;
import javax.transaction.Synchronization;

import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.GeneralRuntimeException;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.content.data.DataResourceWorker;
import org.apache.ofbiz.entity.Delegator;
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
 * <p><strong>How a publication is observed.</strong> Publication is registered with the current
 * transaction, and a unit test has no transaction factory, so {@code TransactionUtil} answers that
 * there is no transaction infrastructure and production registers nothing - which is itself asserted
 * here. Every test that needs to see a registration therefore seats a recording registrar through
 * {@link ContentStoreFactory#installPublicationRegistrarForTesting}, which is what lets the
 * <em>production</em> seam be driven end to end: the registration is the one production made, with the
 * arguments production chose, and the test only supplies the transaction that would have carried it.
 * That distinction matters, because a publication a test constructs itself says nothing about
 * whether production ever registers one - and "the write is never registered" is exactly the defect
 * this covers.
 */
public final class ContentStoreFactoryBridgeTests {

    private static final String UPLOAD_PREFIX = "runtime/uploads";
    private static final String STAGING_SEGMENT = ".contentstore-staging";

    /** What the recording store says when it refuses a write, so an assertion can look for it by name. */
    private static final String REFUSAL_TEXT = "this store refuses writes";
    private static final byte[] STORED = "stored bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] REPLACEMENT = "written bytes".getBytes(StandardCharsets.UTF_8);

    /** The flat key {@code runtime/uploads/10000.png} derives to, for the many tests that use that location. */
    private static final String KEY = "runtime/uploads/10000.png";

    /** The flat key a staged {@code 10000.bin} upload derives to. */
    private static final String KEY_BIN = "runtime/uploads/10000.bin";

    /** Chunk the large-object tests generate and drain content in; big enough to be quick, small enough to be free. */
    private static final int CHUNK = 64 * 1024;

    private Path home;
    private String previousHome;
    private RecordingContentStore store;
    private RecordingRegistrar registrar;

    @BeforeEach
    public void giveThisTestItsOwnHomeAndProvider() throws Exception {
        ContentStoreFactory.clearCache();
        home = Files.createTempDirectory("blitzy-content-bridge").toRealPath();
        previousHome = System.getProperty("ofbiz.home");
        System.setProperty("ofbiz.home", home.toString());
        store = new RecordingContentStore();
        registrar = new RecordingRegistrar();
    }

    @AfterEach
    public void leaveNoHomeProviderOrStagedFileBehind() throws Exception {
        // Restored before anything else: leaving a test registrar seated would silently redirect every
        // later test in the same JVM away from the transaction manager.
        ContentStoreFactory.installPublicationRegistrarForTesting(null);
        ContentStoreFactory.clearCache();
        if (previousHome == null) {
            System.clearProperty("ofbiz.home");
        } else {
            System.setProperty("ofbiz.home", previousHome);
        }
        deleteRecursively(home.toFile());
    }

    // key derivation

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

    // upload allocation

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
    public void allocatingAStagingPathIsInertWhileDatabaseStorageIsConfigured() throws Exception {
        assertNull(ContentStoreFactory.uploadStagingPath(UPLOAD_PREFIX, true));
        assertNull(ContentStoreFactory.uploadStagingPath(UPLOAD_PREFIX, false));
        assertFalse(new File(home + "/" + UPLOAD_PREFIX).exists(),
                "database mode must not create so much as a directory");
    }

    @Test
    public void anUnusableStagingLocationFailsLoudlyRatherThanSilentlyStayingLocal() throws Exception {
        activateRecordingProvider();
        // A regular file where the staging directory has to go stops the directory being
        // created, which is the one way this can fail on a healthy filesystem.
        File blocker = new File(home + "/" + UPLOAD_PREFIX);
        assertTrue(blocker.getParentFile().mkdirs() || blocker.getParentFile().isDirectory());
        write(blocker, STORED);
        GeneralException refused = assertThrows(GeneralException.class, () ->
                ContentStoreFactory.uploadStagingPath(UPLOAD_PREFIX, true),
                "an upload must never fall back to a local directory the deployment did not ask for");
        assertFalse(refused instanceof ContentStoreConfigurationException,
                "a usable provider whose staging location cannot be allocated is not a misconfigured deployment, "
                        + "and a caller has to be able to tell the two apart: " + refused.getMessage());
    }

    // reading

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

    // writing

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

    // diagnostics

    /**
     * The SPI requires that a diagnostic name neither the storage key, nor a resolved path, nor
     * anything the store itself said. All three are caller- or remote-influenced: a key names a
     * party's document and arrives from request data, a path publishes the deployment's layout, and
     * a message from a store or from the operating system is text this package did not write. This
     * drives the failing paths of the bridge and holds every message they produce to that rule.
     *
     * @throws Exception if the bridge cannot be driven
     */
    @Test
    public void noDiagnosticFromTheBridgeNamesTheKeyThePathOrWhatTheStoreSaid() throws Exception {
        activateRecordingProvider();
        store.refuseWrites();
        String objectInfo = "/" + UPLOAD_PREFIX + "/10000.png";
        File staged = new File(home + objectInfo);
        assertTrue(staged.getParentFile().mkdirs());
        write(staged, REPLACEMENT);

        GeneralException refused = assertThrows(GeneralException.class, () ->
                ContentStoreFactory.publishContentFile("OFBIZ_FILE", objectInfo, null));

        // getMessage is the whole of what a reader sees here, because nothing is chained on this path.
        String reported = refused.getMessage();
        assertFalse(reported.contains("runtime/uploads/10000.png"),
                "the key must be reported as an opaque reference, not as itself: " + reported);
        assertFalse(reported.contains(home.toString()),
                "a resolved path must not publish the deployment's layout: " + reported);
        assertFalse(reported.contains(REFUSAL_TEXT),
                "what the store said must not be quoted into a message: " + reported);
        assertTrue(reported.contains(ContentStoreFactory.reference("runtime/uploads/10000.png")),
                "the key still has to be identifiable, by its stable reference: " + reported);
        assertTrue(reported.contains("CONTENT-STORE-PUBLISH-FAILED"),
                "a stable code is what makes this class of failure findable at all: " + reported);
        assertTrue(reported.contains("IOException"),
                "the kind of failure is named by its type, which is generated here rather than read from the store: "
                        + reported);
        assertNull(refused.getCause(),
                "and it must not be chained either: GeneralException.getMessage() composes a nested message into its "
                        + "own, so chaining would republish what the store said however safely this message was built");
    }

    /**
     * A location bearing a control character is refused where it would become a key, rather than
     * being stored under a key no operator can address and then carried into every diagnostic about
     * it. A newline is the case that matters: reported as itself it forges a log record.
     */
    @Test
    public void aLocationCarryingAControlCharacterIsRefusedBeforeItBecomesAKey() {
        String forging = UPLOAD_PREFIX + "/10000.png\nWARN forged log record";

        GeneralException refused = assertThrows(GeneralException.class, () ->
                ContentStoreFactory.storageKeyFor("OFBIZ_FILE", forging, null),
                "a location that cannot be logged safely must not become a key either");

        String reported = refused.getMessage();
        assertFalse(reported.contains("\n") || reported.contains("\r"),
                "the refusal itself must not carry the line break it refused: " + reported);
        assertFalse(reported.contains("forged log record"), "nothing of the value may be echoed: " + reported);
    }

    /**
     * The bridge reports a configuration it cannot honour as a configuration failure on both routes
     * into it, and the frozen upload path converts that one failure into one unchecked carrier. The
     * alternative - one route checked and the other unchecked, with different wording - is what
     * leaves a caller unable to tell a misconfigured deployment from a failed allocation.
     *
     * @throws Exception if the configuration cannot be driven
     */
    @Test
    public void aConfigurationThatCannotBeHonouredIsRefusedTheSameWayOnBothRoutes() throws Exception {
        ContentStoreFactory.clearCache();
        UtilProperties.setPropertyValueInMemory("content", "content.store.provider", "s4");
        try {
            assertThrows(ContentStoreConfigurationException.class, () ->
                    ContentStoreFactory.uploadStagingPath(UPLOAD_PREFIX, true),
                    "the staging route must report a misconfigured deployment as a configuration failure");

            GeneralRuntimeException viaStaging = assertThrows(GeneralRuntimeException.class, () ->
                    DataResourceWorker.getDataResourceContentUploadPath(UPLOAD_PREFIX, 250, true));
            GeneralRuntimeException viaProvider = assertThrows(GeneralRuntimeException.class, () ->
                    DataResourceWorker.getDataResourceContentUploadPath(null, true));

            assertEquals(viaStaging.getMessage(), viaProvider.getMessage(),
                    "one policy means one message, whichever route the configuration takes");
            // GeneralRuntimeException keeps its nested failure in a field of its own rather than in the
            // Throwable cause, so getNested is what a caller has to ask - as the sibling suite does too.
            assertInstanceOf(ContentStoreConfigurationException.class, viaStaging.getNested(),
                    "the checked failure has to be carried, so a caller can still tell what kind of failure it was");
            assertInstanceOf(ContentStoreConfigurationException.class, viaProvider.getNested(),
                    "the checked failure has to be carried on this route too");
            assertFalse(new File(home + "/" + UPLOAD_PREFIX).exists(),
                    "a refused configuration must not leave a local directory an upload could be written into");
        } finally {
            UtilProperties.setPropertyValueInMemory("content", "content.store.provider", "database");
            ContentStoreFactory.clearCache();
        }
    }

    // guards

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

    // publication on commit and on rollback

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

    /**
     * Completing the transaction leaves the resolved file where the frozen callers expect to find it.
     *
     * <p>The file a resolution hands out is the location {@code DataResource.objectInfo} names, not a private
     * staging file, and the frozen callers keep using it after the transaction that resolved it has completed:
     * {@code ContentWorker} puts its path into a render context that FreeMarker reads later, and
     * {@code CompanyHeader.groovy} reads its bytes straight after resolving it. Removing it on completion would
     * make content that is present read as missing, occasionally and only under a committing transaction.
     *
     * <p>Statelessness is not weakened by leaving it. What remains is a stamped copy of what the store holds,
     * which every other instance can fetch for itself, and every resolution re-reads the store and overwrites
     * it - so no instance holds state another instance cannot reconstruct, and no copy is ever served stale.
     * A genuine staging area is different and is still removed: see
     * {@code publishingSendsTheStagedBytesToTheStoreAndRemovesTheLocalFile} and
     * {@code everyUploadStagedInADirectoryIsPublishedUnderItsFlatKeyOnCommit}.
     */
    @Test
    public void aResolvedFileSurvivesTheTransactionThatResolvedIt() throws Exception {
        activateRecordingProvider();
        File staged = stagedFileHolding(STORED);
        contentPublication("runtime/uploads/10000.png", staged, false).afterCompletion(Status.STATUS_COMMITTED);
        assertTrue(staged.isFile(), "the location objectInfo names must still be there once the transaction is done");
        assertArrayEqualsBytes(STORED, readFully(staged), "and must still hold the content it was resolved with");
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

    // the production vertical, driven through DataResourceWorker
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

    /**
     * A provider may be able to name where an upload should go without that name being a writable
     * local file, and the two frozen write services this bridge sits under do not tolerate the
     * difference: they open a {@code FileOutputStream} on whatever they are told, and for
     * {@code LOCAL_FILE} they require it to be absolute. Believing such a provider is how an upload
     * ends up in a directory created beside the process working directory, reported as stored, and
     * never sent anywhere - so a location is only ever taken from a provider that also backs the
     * very file it names, and every other provider's upload is staged locally and published on
     * commit instead.
     *
     * @throws Exception if the bridge cannot be driven
     */
    @Test
    public void aProviderNamingALocationItDoesNotBackIsStagedRatherThanBelieved() throws Exception {
        activateProvider(new AnnouncingContentStore(store));

        assertNull(ContentStoreFactory.resolveUploadPath(null, true),
                "a provider that does not back the file it names must answer no upload location at all");
        String allocated = DataResourceWorker.getDataResourceContentUploadPath(UPLOAD_PREFIX, 250, true);
        assertFalse(allocated.contains(AnnouncingContentStore.ANNOUNCED),
                "the location the provider named must never reach the upload flow: " + allocated);
        assertTrue(allocated.contains(STAGING_SEGMENT), allocated);
        assertTrue(new File(allocated).isDirectory(), "the staged location has to exist to be written to: " + allocated);
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
        withTransaction();
        store.hold(KEY, STORED);

        // The exact call DataServices.createBinaryFileMethod and updateBinaryFileMethod make.
        File served = DataResourceWorker.getContentFile("OFBIZ_FILE", UPLOAD_PREFIX + "/10000.png", null);

        // The seam has to have registered the publication itself. Nothing the
        // resolution observes can carry a write that has not happened yet, so a seam that registers
        // nothing here leaves the write below reaching local disk only - and on any other instance,
        // which has no local copy and finds the object unchanged in the store, the write is lost.
        assertEquals(1, registrar.registered().size(),
                "resolving a file while a provider is configured must register exactly one publication "
                        + "with the transaction, because the caller may write through the file afterwards");

        // exactly what DataServices.createBinaryFileMethod and updateBinaryFileMethod do
        write(served, REPLACEMENT);
        registrar.commit();

        assertArrayEqualsBytes(REPLACEMENT, store.held(KEY));
        assertTrue(served.isFile(),
                "the working copy is the location objectInfo names, not a staging file, so completing the "
                        + "transaction must not remove it");
    }

    @Test
    public void aFileTheSeamOnlyHandedOutForReadingPublishesNothingWhenTheTransactionCommits() throws Exception {
        activateRecordingProvider();
        withTransaction();
        store.hold(KEY, STORED);

        File served = DataResourceWorker.getContentFile("OFBIZ_FILE", UPLOAD_PREFIX + "/10000.png", null);
        assertEquals(1, registrar.registered().size(), "a publication is registered whether or not it is needed");
        registrar.commit();

        assertArrayEqualsBytes(STORED, readFully(served), "a read must not disturb the content");
        assertTrue(store.writes().isEmpty(),
                "a caller that only read must cost no write at all: publication has to be conditional on the "
                        + "file actually having changed, or every read would re-upload the content it served");
    }

    /**
     * The file the seam hands out carries a modification time no write can reproduce.
     *
     * <p>This is the mechanism the test below depends on, asserted directly because it is the only part of it
     * that can be established without a clock. Comparing the timestamp observed at resolution with the one
     * observed at commit is not sufficient on its own: a write that keeps the byte count and lands inside a
     * single filesystem timestamp tick is then indistinguishable from no write at all, and the granularity of
     * that tick belongs to the filesystem rather than to anything this code can assume. Stamping the resolved
     * file with a time in 1970 removes the assumption: a write stamps the file with the time of the write.
     *
     * <p>Found by this suite failing only when it ran alongside another class, that is only when the write
     * happened to land in the same tick as the resolution - which is exactly how a deployment would have lost
     * a same-length write, silently and occasionally.
     */
    @Test
    public void theSeamStampsTheFileItHandsOutSoThatAnyWriteThroughItIsDetectable() throws Exception {
        activateRecordingProvider();
        withTransaction();
        store.hold(KEY, STORED);

        File served = DataResourceWorker.getContentFile("OFBIZ_FILE", UPLOAD_PREFIX + "/10000.png", null);

        assertEquals(FileTime.fromMillis(1000L), Files.getLastModifiedTime(served.toPath()),
                "the file handed to a caller has to carry the sentinel modification time: that is what makes a "
                        + "same-length write inside one timestamp tick detectable at all");
        assertArrayEqualsBytes(STORED, readFully(served), "and stamping it must not disturb the content");
    }

    /**
     * Stamping the resolved file does not make an untouched copy look like a write.
     *
     * <p>The reconciliation register records the same {@code length:lastModified} pair the stamp changes, so a
     * stamp applied without bringing the register along would make the very next resolution read an untouched
     * file as locally modified and upload it again - on every read, for the life of the JVM.
     */
    @Test
    public void resolvingTheSameUntouchedFileInALaterTransactionPublishesNothing() throws Exception {
        activateRecordingProvider();
        store.hold(KEY, STORED);

        withTransaction();
        DataResourceWorker.getContentFile("OFBIZ_FILE", UPLOAD_PREFIX + "/10000.png", null);
        registrar.commit();

        // A second transaction on the same instance, resolving the same resource it did not write to.
        registrar = new RecordingRegistrar();
        withTransaction();
        DataResourceWorker.getContentFile("OFBIZ_FILE", UPLOAD_PREFIX + "/10000.png", null);
        registrar.commit();

        assertTrue(store.writes().isEmpty(),
                "reading content twice must cost no write at all, however the resolved file is stamped");
    }

    @Test
    public void aWriteThroughTheSeamKeepingTheByteCountIdenticalIsStillPublished() throws Exception {
        activateRecordingProvider();
        withTransaction();
        store.hold(KEY, STORED);
        File served = DataResourceWorker.getContentFile("OFBIZ_FILE", UPLOAD_PREFIX + "/10000.png", null);

        // Same length, and written immediately: nothing about the size or the timing of this write
        // distinguishes it from no write, which is why the resolved file was stamped with a 1970 time.
        byte[] sameLength = new byte[STORED.length];
        System.arraycopy(REPLACEMENT, 0, sameLength, 0, Math.min(REPLACEMENT.length, sameLength.length));
        write(served, sameLength);
        registrar.commit();

        assertArrayEqualsBytes(sameLength, store.held(KEY),
                "a write that preserves the byte count must still be published");
    }

    @Test
    public void theSeamRegistersOnePublicationPerKeyHoweverOftenTheSameFileIsResolved() throws Exception {
        activateRecordingProvider();
        withTransaction();
        store.hold(KEY, STORED);

        DataResourceWorker.getContentFile("OFBIZ_FILE", UPLOAD_PREFIX + "/10000.png", null);
        DataResourceWorker.getContentFile("OFBIZ_FILE", UPLOAD_PREFIX + "/10000.png", null);
        File served = DataResourceWorker.getContentFile("OFBIZ_FILE_BIN", UPLOAD_PREFIX + "/10000.png", null);

        // One transaction resolves the same resource several times - ContentWorker, the render pipeline
        // and the write services all do - and each extra publication would be another store round trip
        // on the critical path of the commit.
        assertEquals(1, registrar.registered().size(),
                "one key needs one publication per transaction, however often it is resolved");

        write(served, REPLACEMENT);
        registrar.commit();
        assertEquals(List.of(KEY), store.writes(), "and it must publish exactly once");
    }

    /**
     * A transaction nested on one thread publishes its own write.
     *
     * <p>OFBiz suspends and resumes transactions on the thread that owns them, so a thread can be running a
     * second transaction while a first is still in flight. Whatever records that a key is already covered has
     * to be scoped to the transaction that covers it: a record kept against the <em>thread</em> would let the
     * publication belonging to the suspended transaction stand in for the nested one, and the nested write
     * would then never leave local disk - which is the whole failure this seam exists to close.
     */
    @Test
    public void aTransactionNestedOnTheSameThreadPublishesItsOwnWriteOfTheSameKey() throws Exception {
        activateRecordingProvider();
        store.hold(KEY, STORED);

        withTransaction();
        DataResourceWorker.getContentFile("OFBIZ_FILE", UPLOAD_PREFIX + "/10000.png", null);
        RecordingRegistrar suspended = registrar;
        assertEquals(1, suspended.registered().size(), "the outer transaction registers its own publication");

        // The outer transaction is left in flight, exactly as a suspended one is.
        registrar = new RecordingRegistrar();
        withTransaction();
        File served = DataResourceWorker.getContentFile("OFBIZ_FILE", UPLOAD_PREFIX + "/10000.png", null);

        assertEquals(1, registrar.registered().size(),
                "the nested transaction has to get a publication of its own, because the outer one's cannot "
                        + "publish what the nested one writes");
        write(served, REPLACEMENT);
        registrar.commit();
        assertArrayEqualsBytes(REPLACEMENT, store.held(KEY), "so the nested transaction's write is published");
    }

    @Test
    public void theSeamRegistersNothingAtAllWhileDatabaseStorageIsConfigured() throws Exception {
        withTransaction();
        File local = stagedFileHolding(STORED);

        File served = DataResourceWorker.getContentFile("LOCAL_FILE", local.getAbsolutePath(), null);

        assertEquals(local.getAbsolutePath(), served.getAbsolutePath(), "the committed resolution is unchanged");
        assertTrue(registrar.registered().isEmpty(),
                "in the committed database mode there is no provider and the file is a copy of nothing, so "
                        + "there is nothing to publish and no transaction callback to install");
    }

    @Test
    public void theSeamRegistersNothingForAProviderThatBacksTheVerySameFile() throws Exception {
        ContentStoreFactory.installForTesting("database", ContentStoreFactory.resolve("filesystem"));
        withTransaction();
        File local = stagedFileHolding(STORED);

        DataResourceWorker.getContentFile("OFBIZ_FILE", UPLOAD_PREFIX + "/10000.png", null);

        assertTrue(registrar.registered().isEmpty(),
                "publishing a file onto itself would rewrite the content on every read, so a provider whose "
                        + "storage IS this file must have no publication registered for it");
        assertTrue(local.isFile(), "and the file must be left exactly as it was");
    }

    @Test
    public void aStoreRefusingAWriteMadeThroughTheSeamRollsTheTransactionBack() throws Exception {
        activateRecordingProvider();
        withTransaction();
        store.hold(KEY, STORED);
        File served = DataResourceWorker.getContentFile("OFBIZ_FILE", UPLOAD_PREFIX + "/10000.png", null);
        write(served, REPLACEMENT);
        store.refuseWrites();

        // Publication runs in beforeCompletion so that this is possible at all: committing here would
        // leave a DataResource row naming content the store never received.
        GeneralRuntimeException refused = assertThrows(GeneralRuntimeException.class, () -> registrar.commit(),
                "a store that will not accept the write must fail the commit");
        assertTrue(refused.getMessage().contains("rolled back"), refused.getMessage());
    }

    @Test
    public void contentWrittenThroughTheSeamOnOneInstanceIsServedByAPeerThatNeverHadALocalCopy() throws Exception {
        activateRecordingProvider();
        withTransaction();
        store.hold(KEY, STORED);

        // Instance A: resolve, write through the returned File, commit.
        File served = DataResourceWorker.getContentFile("OFBIZ_FILE", UPLOAD_PREFIX + "/10000.png", null);
        write(served, REPLACEMENT);
        registrar.commit();

        // Instance B is the same code with a different deployment directory and therefore an empty disk -
        // which is exactly the state a freshly started container behind a load balancer is in. The store is
        // the shared one, as it would be for a fleet sharing a bucket.
        Path peerHome = Files.createTempDirectory("blitzy-content-peer").toRealPath();
        try {
            System.setProperty("ofbiz.home", peerHome.toString());
            assertFalse(Files.exists(peerHome.resolve(UPLOAD_PREFIX + "/10000.png")),
                    "the peer must start with no local copy, or this proves nothing");

            File peerServed = DataResourceWorker.getContentFile("OFBIZ_FILE", UPLOAD_PREFIX + "/10000.png", null);

            assertArrayEqualsBytes(REPLACEMENT, readFully(peerServed),
                    "the peer must serve what the write produced, not the content it replaced");
        } finally {
            System.setProperty("ofbiz.home", home.toString());
            deleteRecursively(peerHome.toFile());
        }
    }

    /**
     * An instance that has already read content still serves what another instance later wrote.
     *
     * <p>The companion of the peer test above, and the harder half of it: that one starts the peer with an
     * empty disk, which any freshly started container has, while this one gives the peer the state every
     * long-running container acquires - a local copy of content it has served before. There is no invalidation
     * channel for file-backed content, so a local copy that is trusted without asking the store is a copy that
     * goes stale for the life of the JVM: every invoice this instance renders would carry the superseded logo,
     * and which logo a customer saw would depend on which instance the load balancer picked.
     */
    @Test
    public void anInstanceThatAlreadyHoldsALocalCopyStillServesWhatAnotherInstanceWrote() throws Exception {
        activateRecordingProvider();
        store.hold(KEY, STORED);

        withTransaction();
        File first = DataResourceWorker.getContentFile("OFBIZ_FILE", UPLOAD_PREFIX + "/10000.png", null);
        assertArrayEqualsBytes(STORED, readFully(first), "the first read serves what the store holds");
        registrar.commit();

        // Another instance replaces the object in the shared store. Nothing informs this one.
        store.hold(KEY, REPLACEMENT);

        registrar = new RecordingRegistrar();
        withTransaction();
        File again = DataResourceWorker.getContentFile("OFBIZ_FILE", UPLOAD_PREFIX + "/10000.png", null);

        assertArrayEqualsBytes(REPLACEMENT, readFully(again),
                "every resolution has to ask the store, or a write made on one instance is invisible on every "
                        + "instance that had already read the content it replaced");
    }

    // streaming: no ceiling on staged publication or on materialisation

    @Test
    public void aStagedUploadLargerThanTheInMemoryCeilingIsPublishedByStreamingIt() throws Exception {
        StreamingOnlyContentStore streaming = new StreamingOnlyContentStore();
        ContentStoreFactory.installForTesting("database", streaming);
        withTransaction();

        // Deliberately past content.store.max.memory.bytes (20 MiB), which is the ceiling the bounded
        // put(String, byte[]) form refuses. Staged publication must not be subject to it: the pre-existing
        // local-filesystem behaviour had no such limit, so imposing one would cap what a deployment can
        // upload, and reading the file whole would allocate the document in the heap of the serving
        // instance.
        long length = 21L * 1024L * 1024L + 7L;
        String uploadPath = DataResourceWorker.getDataResourceContentUploadPath(UPLOAD_PREFIX, 250, true);
        File uploaded = new File(uploadPath, "10000.bin");
        String expected = writePattern(uploaded, length);

        registrar.commit();

        assertEquals(List.of(KEY_BIN), streaming.streamed(), "the upload must have been published by streaming");
        assertEquals(length, streaming.lengthOf(KEY_BIN), "the whole object must have been declared and delivered");
        assertEquals(expected, streaming.digestOf(KEY_BIN), "and delivered byte for byte");
    }

    @Test
    public void materialisingAnObjectLargerThanTheInMemoryCeilingStreamsItToDisk() throws Exception {
        long length = 21L * 1024L * 1024L + 7L;
        StreamingOnlyContentStore streaming = new StreamingOnlyContentStore();
        String expected = streaming.holdPattern(KEY, length);
        ContentStoreFactory.installForTesting("database", streaming);

        File materialised = ContentStoreFactory.materialiseContentFile("OFBIZ_FILE", UPLOAD_PREFIX + "/10000.png", null);

        assertEquals(length, materialised.length(), "the whole object must have been staged");
        assertEquals(expected, digestOf(materialised), "and staged byte for byte");
        assertEquals(List.of(KEY), streaming.opened(),
                "materialisation must stream the object; the bounded whole-content read would refuse it");
    }

    @Test
    public void theBridgeNeverReachesTheBoundedWholeContentFormsAtAll() throws Exception {
        StreamingOnlyContentStore streaming = new StreamingOnlyContentStore();
        streaming.holdPattern(KEY, 1024L);
        ContentStoreFactory.installForTesting("database", streaming);
        withTransaction();

        // Every integrated direction, on content small enough that the bounded forms would have worked:
        // read, render, materialise, write through the resolved file, and a staged upload.
        File materialised = ContentStoreFactory.materialiseContentFile("OFBIZ_FILE", UPLOAD_PREFIX + "/10000.png", null);
        try (InputStream rendered = ContentStoreFactory.openContentStream("OFBIZ_FILE", UPLOAD_PREFIX + "/10000.png", null)) {
            assertNotNull(rendered);
        }
        File served = DataResourceWorker.getContentFile("OFBIZ_FILE", UPLOAD_PREFIX + "/10000.png", null);
        write(served, REPLACEMENT);
        registrar.commit();

        assertNotNull(materialised);
        // StreamingOnlyContentStore fails the test from inside get() and put(byte[]), so reaching here at
        // all is the assertion. It is stated explicitly as well, so the reason this test exists survives.
        assertTrue(streaming.streamed().contains(KEY), "the write must have been published as a stream");
        assertFalse(streaming.opened().isEmpty(), "and the reads must have been streamed");
    }

    // helpers

    /**
     * Seats the recording registrar with a transaction in place, so a registration can be observed.
     *
     * <p>Production registers through {@code TransactionUtil}, which in a test JVM reports that there is
     * no transaction infrastructure at all - see {@code ContentStoreFactory.PublicationRegistrar}. Only
     * the transaction is supplied here; the publication, its arguments and the decision to register it
     * all remain production's.
     */
    private void withTransaction() {
        ContentStoreFactory.installPublicationRegistrarForTesting(registrar);
    }

    /**
     * Reads a file whole, for an assertion on content the test wrote or the bridge staged.
     *
     * @param file the file to read
     * @return its bytes
     * @throws IOException if it cannot be read
     */
    private static byte[] readFully(File file) throws IOException {
        return Files.readAllBytes(file.toPath());
    }

    /**
     * Writes a deterministic pattern of the requested length, without ever holding it whole.
     *
     * @param file the file to create
     * @param length how many bytes to write
     * @return the SHA-256 of what was written, hex encoded
     * @throws IOException if the file cannot be written
     * @throws GeneralException if SHA-256 is unavailable, which no JRE permits
     */
    private static String writePattern(File file, long length) throws IOException, GeneralException {
        MessageDigest digest = digest();
        byte[] chunk = new byte[CHUNK];
        for (int i = 0; i < chunk.length; i++) {
            chunk[i] = (byte) (i * 31 + 7);
        }
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        try (OutputStream out = new FileOutputStream(file)) {
            long remaining = length;
            while (remaining > 0) {
                int step = (int) Math.min(chunk.length, remaining);
                out.write(chunk, 0, step);
                digest.update(chunk, 0, step);
                remaining -= step;
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Digests a file's content, for an assertion that content arrived byte for byte.
     *
     * @param file the file to digest
     * @return the SHA-256 of its content, hex encoded
     * @throws IOException if it cannot be read
     * @throws GeneralException if SHA-256 is unavailable, which no JRE permits
     */
    private static String digestOf(File file) throws IOException, GeneralException {
        MessageDigest digest = digest();
        byte[] buffer = new byte[CHUNK];
        try (InputStream in = Files.newInputStream(file.toPath())) {
            int read = in.read(buffer);
            while (read > 0) {
                digest.update(buffer, 0, read);
                read = in.read(buffer);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Supplies a SHA-256 digest.
     *
     * @return the digest
     * @throws GeneralException if SHA-256 is unavailable, which no JRE permits
     */
    private static MessageDigest digest() throws GeneralException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new GeneralException("SHA-256 is required of every JRE", e);
        }
    }

    /**
     * Places the recording provider into the factory's resolution cache under the committed
     * configuration value, so that the bridge resolves it through the very code a deployment runs.
     *
     * @throws Exception if the cache or the outcome type cannot be reached
     */
    private void activateRecordingProvider() throws Exception {
        activateProvider(store);
    }

    /**
     * Places the given provider into the factory's resolution cache under the committed
     * configuration value, so that the bridge resolves it through the very code a deployment runs.
     *
     * @param provider the provider the bridge is to resolve
     * @throws Exception if the cache or the outcome type cannot be reached
     */
    private void activateProvider(ContentStore provider) throws Exception {
        Class<?> outcome = Class.forName(ContentStoreFactory.class.getName() + "$Resolution");
        Method succeeded = outcome.getDeclaredMethod("succeeded", String.class, ContentStore.class);
        succeeded.setAccessible(true);
        Field cache = ContentStoreFactory.class.getDeclaredField("RESOLUTIONS");
        cache.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> resolutions = (Map<String, Object>) cache.get(null);
        resolutions.put("database", succeeded.invoke(null, "database", provider));
        assertNotNull(ContentStoreFactory.getContentStore(), "the provider under test has to be the active one");
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
        if (!alwaysPublish) {
            // Production's precondition, reproduced because these tests construct the publication directly
            // rather than through materialiseContentFile: a file brought into step with the store is stamped
            // with the 1970 resolution time before any publication observes it, and that stamp is what makes
            // a later write recognisable even when it keeps the byte count and lands inside one filesystem
            // timestamp tick. The publication itself only observes; it no longer stamps.
            assertTrue(staged.setLastModified(1000L), "the resolution stamp must be settable");
        }
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
    private File allocatedStagingDirectory() throws Exception {
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
                throw new IOException(REFUSAL_TEXT);
            }
            writes.add(key);
            objects.put(key, data.clone());
        }

        @Override
        public void put(String key, InputStream content, long length) throws GeneralException, IOException {
            if (refusing) {
                throw new IOException(REFUSAL_TEXT);
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

    /**
     * A provider that names an upload location it does not back, which is the shape an object store
     * has: it can say which key space an upload belongs under, but that answer is not a writable
     * local file. It stores through the recording provider, so a write that does reach it is
     * observable, and the point of the double is that no write should reach the location it names.
     */
    private static final class AnnouncingContentStore implements ContentStore, ContentUploadLocation {

        private static final String ANNOUNCED = "announced-key-space";

        private final ContentStore backing;

        AnnouncingContentStore(ContentStore backing) {
            this.backing = backing;
        }

        @Override
        public String uploadPath(Delegator delegator, boolean absolute) {
            return ANNOUNCED;
        }

        @Override
        public void put(String key, byte[] data) throws GeneralException, IOException {
            backing.put(key, data);
        }

        @Override
        public void put(String key, InputStream content, long length) throws GeneralException, IOException {
            backing.put(key, content, length);
        }

        @Override
        public byte[] get(String key) throws GeneralException, IOException {
            return backing.get(key);
        }

        @Override
        public InputStream openStream(String key) throws GeneralException, IOException {
            return backing.openStream(key);
        }

        @Override
        public long size(String key) throws GeneralException, IOException {
            return backing.size(key);
        }

        @Override
        public boolean exists(String key) throws GeneralException, IOException {
            return backing.exists(key);
        }

        @Override
        public void delete(String key) throws GeneralException, IOException {
            backing.delete(key);
        }

        @Override
        public void close() throws GeneralException, IOException {
            backing.close();
        }
    }

    /**
     * A registrar that records what the production seam registers, and drives it on demand.
     *
     * <p>It stands in for a transaction, which is the only thing a test JVM cannot arrange for itself, and
     * otherwise does nothing at all: the publication objects it holds are the objects production
     * constructed, so driving them exercises production's own commit behaviour.
     *
     * <p>One registrar is one transaction. Each test seats a new one, so each test's registrations are
     * scoped to its own transaction exactly as a real one would be - and a test that never completes its
     * transaction cannot leave a record behind that suppresses a later test's publication.
     */
    private static final class RecordingRegistrar implements ContentStoreFactory.PublicationRegistrar {

        private final List<Synchronization> registered = new ArrayList<>();

        @Override
        public Object currentTransaction() {
            return this;
        }

        @Override
        public void register(Synchronization publication) {
            registered.add(publication);
        }

        /**
         * Reports the publications registered so far.
         *
         * @return the publications, in registration order
         */
        private List<Synchronization> registered() {
            return List.copyOf(registered);
        }

        /**
         * Completes the transaction the way a successful commit does.
         */
        private void commit() {
            for (Synchronization publication : registered()) {
                publication.beforeCompletion();
            }
            for (Synchronization publication : registered()) {
                publication.afterCompletion(Status.STATUS_COMMITTED);
            }
        }

        /**
         * Completes the transaction the way a rollback does: no publication, only completion.
         */
        private void rollBack() {
            for (Synchronization publication : registered()) {
                publication.afterCompletion(Status.STATUS_ROLLEDBACK);
            }
        }
    }

    /**
     * A provider that accepts <strong>only</strong> the streaming operations, and fails the test from
     * inside the bounded ones.
     *
     * <p>This is how "no ceiling was introduced" is exercised rather than asserted. The bounded forms
     * {@link ContentStore#get(String)} and {@link ContentStore#put(String, byte[])} materialise content in
     * the heap and are therefore required to refuse anything above
     * {@code content.store.max.memory.bytes}; if the bridge reached either of them, an upload larger than
     * that ceiling would fail where the pre-existing local-filesystem behaviour succeeded. Making them
     * throw an {@link AssertionError} means a regression cannot pass by being merely slow or merely large.
     *
     * <p>Content is never held whole: a write is drained in chunks into a digest, and a read is generated
     * from the same deterministic pattern, so an object far beyond any ceiling costs no heap here either.
     */
    private static final class StreamingOnlyContentStore implements ContentStore {

        private final Map<String, Long> lengths = new HashMap<>();
        private final Map<String, String> digests = new HashMap<>();
        private final List<String> streamed = new ArrayList<>();
        private final List<String> opened = new ArrayList<>();

        @Override
        public void put(String key, byte[] data) {
            throw new AssertionError("The bounded put(String, byte[]) form must never be reached by an "
                    + "integrated write: it caps what a deployment can upload and allocates the whole "
                    + "document in the heap. Key: " + key);
        }

        @Override
        public void put(String key, InputStream content, long length) throws GeneralException, IOException {
            MessageDigest digest = digest();
            byte[] buffer = new byte[CHUNK];
            long drained = 0;
            int read = content.read(buffer);
            while (read > 0) {
                digest.update(buffer, 0, read);
                drained += read;
                read = content.read(buffer);
            }
            if (drained != length) {
                throw new IOException("The stream for key [" + key + "] yielded " + drained
                        + " byte(s) but " + length + " were declared");
            }
            lengths.put(key, length);
            digests.put(key, HexFormat.of().formatHex(digest.digest()));
            streamed.add(key);
        }

        @Override
        public byte[] get(String key) {
            throw new AssertionError("The bounded get(String) form must never be reached by an integrated "
                    + "read: it refuses content above the in-memory ceiling and allocates whatever it does "
                    + "accept. Key: " + key);
        }

        @Override
        public InputStream openStream(String key) throws GeneralException, IOException {
            Long length = lengths.get(key);
            if (length == null) {
                throw new FileNotFoundException("Nothing is stored under key [" + key + "]");
            }
            opened.add(key);
            return new PatternInputStream(length);
        }

        @Override
        public long size(String key) throws GeneralException, IOException {
            Long length = lengths.get(key);
            if (length == null) {
                throw new FileNotFoundException("Nothing is stored under key [" + key + "]");
            }
            return length;
        }

        @Override
        public boolean exists(String key) {
            return lengths.containsKey(key);
        }

        @Override
        public void delete(String key) {
            lengths.remove(key);
            digests.remove(key);
        }

        @Override
        public void close() {
            // Nothing to release: this double holds only lengths and digests.
        }

        /**
         * Seeds a stored object of the requested length, generated from the shared pattern.
         *
         * @param key the key to store it under
         * @param length how long the object is
         * @return the SHA-256 of the object's content, hex encoded
         * @throws GeneralException if SHA-256 is unavailable, which no JRE permits
         * @throws IOException if the generated stream cannot be read
         */
        private String holdPattern(String key, long length) throws GeneralException, IOException {
            MessageDigest digest = digest();
            byte[] buffer = new byte[CHUNK];
            try (InputStream in = new PatternInputStream(length)) {
                int read = in.read(buffer);
                while (read > 0) {
                    digest.update(buffer, 0, read);
                    read = in.read(buffer);
                }
            }
            String hex = HexFormat.of().formatHex(digest.digest());
            lengths.put(key, length);
            digests.put(key, hex);
            return hex;
        }

        /**
         * Reports the keys written as a stream.
         *
         * @return the keys, in order
         */
        private List<String> streamed() {
            return List.copyOf(streamed);
        }

        /**
         * Reports the keys read as a stream.
         *
         * @return the keys, in order
         */
        private List<String> opened() {
            return List.copyOf(opened);
        }

        /**
         * Reports how long a stored object is.
         *
         * @param key the key to measure
         * @return the length, or -1 when nothing is stored
         */
        private long lengthOf(String key) {
            return lengths.getOrDefault(key, -1L);
        }

        /**
         * Reports the digest of a stored object.
         *
         * @param key the key to digest
         * @return the SHA-256, hex encoded, or null when nothing is stored
         */
        private String digestOf(String key) {
            return digests.get(key);
        }
    }

    /**
     * A stream of the deterministic pattern the large-object tests use, of any length, costing no heap.
     */
    private static final class PatternInputStream extends InputStream {

        private long remaining;
        private long position;

        /**
         * @param length how many bytes the stream yields
         */
        private PatternInputStream(long length) {
            this.remaining = length;
        }

        @Override
        public int read() {
            if (remaining <= 0) {
                return -1;
            }
            remaining--;
            return byteAt(position++) & 0xFF;
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            if (remaining <= 0) {
                return -1;
            }
            int step = (int) Math.min(length, remaining);
            for (int i = 0; i < step; i++) {
                target[offset + i] = byteAt(position + i);
            }
            position += step;
            remaining -= step;
            return step;
        }

        /**
         * The pattern byte at a position, matching what {@code writePattern} produces.
         *
         * @param at the position in the stream
         * @return the byte at that position
         */
        private static byte byteAt(long at) {
            int within = (int) (at % CHUNK);
            return (byte) (within * 31 + 7);
        }
    }
}
