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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.UtilProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the whole {@link ContentStoreBehaviourContract} against the production
 * {@link FileSystemContentStore}, and proves that everything which CHANGES the store is confined to the
 * configured {@code content.upload.path.prefix} upload root.
 *
 * <p><strong>Two surfaces, deliberately unequal.</strong> A storage key is resolved relative to
 * {@code ofbiz.home}, because that is what a {@code DataResource.objectInfo} value has always meant, and a
 * read is bounded by the Content component's own allow lists - which are broad, since existing object
 * information legitimately points all over the OFBiz home directory. {@code put} and {@code delete} are
 * bounded again and far more narrowly: a key resolving outside the upload root is REFUSED rather than
 * re-rooted onto it. Refusing rather than re-rooting is the whole design, because re-rooting would make
 * {@code put} and {@code get} address different files for one key and would silently change the meaning of
 * every location already persisted. The tests below therefore hold the two surfaces to the reasons that
 * actually apply to each, while still driving all five operations for every hostile key.
 *
 * <p>Inheriting the shared contract is the point of the class rather than a convenience: it is what
 * makes the production provider interchangeable with the S3 provider by construction, exactly as the
 * contract's own javadoc anticipates, instead of by two sets of hand-written expectations that can
 * drift apart. Everything runs against real bytes on a real filesystem - files are written, read back,
 * streamed twice and deleted - so a pass says something about storage behaviour rather than about
 * stubbing.
 *
 * <p><strong>Why confinement gets its own tests.</strong> A storage key reaches a path-backed provider
 * from request data, so a resolver that lets the key rather than the configuration choose the location
 * is a read, overwrite and delete primitive over the whole application tree. The tests below therefore
 * do not merely assert that hostile keys raise an exception; the decisive ones plant a real file
 * OUTSIDE the upload root and prove it is still byte-identical afterwards. That is an assertion no
 * rewording of the resolver can satisfy by accident.
 *
 * <p><strong>Global state.</strong> {@link FileSystemContentStore} is configuration-driven: it reads
 * the {@code ofbiz.home} system property and the {@code content} resource at the moment it is used, so
 * a test cannot hand it a root through a constructor. Each test therefore redirects {@code ofbiz.home}
 * at its own {@link TempDir} tree and, where it needs to, edits the relevant property in memory. Both
 * the system property and every property touched here are snapshotted before each test and restored
 * afterwards, so no test can leak configuration into another.
 */
public final class FileSystemContentStoreConfinementTests extends ContentStoreBehaviourContract {

    /** The resource the provider reads its upload prefix from. */
    private static final String CONTENT_RESOURCE = "content";

    /** The property the provider roots every storage key underneath. */
    private static final String UPLOAD_PATH_PREFIX = "content.upload.path.prefix";

    /** The committed upload prefix, relative to the OFBiz home directory. */
    private static final String COMMITTED_PREFIX = "runtime/uploads";

    /** The resource holding the allow list the provider applies to a resolved file. */
    private static final String SECURITY_RESOURCE = "security";

    /** The allow list the provider applies, which is the one governing OFBiz-relative content. */
    private static final String OFBIZ_FILE_ALLOWED_PATHS = "content.data.ofbiz.file.allowed.paths";

    private static final byte[] PAYLOAD = "stored by the production filesystem provider".getBytes(StandardCharsets.UTF_8);

    /** Sentinel content for a file planted outside the upload root, which must never be touched. */
    private static final byte[] UNTOUCHABLE = "state that lives outside the upload root".getBytes(StandardCharsets.UTF_8);

    /** JUnit creates this per test and deletes it recursively afterwards, so no artefact outlives a test. */
    @TempDir
    private Path homes;

    private String previousOfbizHome;
    private String previousUploadPrefix;
    private String previousAllowedPaths;
    private int homeSequence;
    private Path currentHome;

    @BeforeEach
    public void snapshotTheConfigurationEachTestIsAboutToRedirect() {
        previousOfbizHome = System.getProperty("ofbiz.home");
        previousUploadPrefix = UtilProperties.getPropertyValue(CONTENT_RESOURCE, UPLOAD_PATH_PREFIX);
        previousAllowedPaths = UtilProperties.getPropertyValue(SECURITY_RESOURCE, OFBIZ_FILE_ALLOWED_PATHS);
    }

    @AfterEach
    public void restoreTheConfigurationForEverySubsequentTest() {
        if (previousOfbizHome == null) {
            System.clearProperty("ofbiz.home");
        } else {
            System.setProperty("ofbiz.home", previousOfbizHome);
        }
        // An in-memory property edit outlives the test that made it, because the Properties object is
        // cached per resource for the whole JVM. Restoring is therefore not tidiness but isolation:
        // an allow list left narrowed here would fail unrelated tests that run afterwards. Writing the
        // empty string back is what "absent" means to the three-argument lookup these values are read
        // through, so a value this resource never declared is restored to being absent.
        UtilProperties.setPropertyValueInMemory(CONTENT_RESOURCE, UPLOAD_PATH_PREFIX, previousUploadPrefix);
        UtilProperties.setPropertyValueInMemory(SECURITY_RESOURCE, OFBIZ_FILE_ALLOWED_PATHS, previousAllowedPaths);
    }

    @Override
    protected ContentStore newEmptyContentStore() throws IOException {
        return newFileSystemContentStore();
    }

    /**
     * Places the shared contract's keys inside the tree the provider is allowed to MODIFY.
     *
     * <p>A storage key is resolved relative to {@code ofbiz.home} - that is what a
     * {@code DataResource.objectInfo} value has always meant, and the provider deliberately refuses a key
     * that lands outside the upload root rather than re-rooting it onto the root, because re-rooting would
     * make {@code put} and {@code get} address different files for one key and would change the meaning of
     * every location already persisted. A key that a write may use is therefore one whose home-relative
     * form already lies under {@code content.upload.path.prefix}, which is what this prefix supplies.
     *
     * @return the committed upload prefix, ending in a separator
     */
    @Override
    protected String keyPrefix() {
        return COMMITTED_PREFIX + "/";
    }

    /**
     * Points {@code ofbiz.home} at a brand new, empty home directory and returns a provider over it.
     *
     * <p>A fresh home per call is what makes the store empty, since the provider's root is derived
     * from that home rather than passed in. The {@code runtime} directory is created because a real
     * OFBiz home always has one and because {@code getUploadPath} creates its top level location with
     * {@code mkdir} rather than {@code mkdirs} - pre-existing behaviour this class reproduces
     * faithfully and therefore must accommodate rather than paper over.
     *
     * @return a provider whose upload root is empty and exclusive to the calling test
     * @throws IOException if the home directory cannot be created
     */
    private FileSystemContentStore newFileSystemContentStore() throws IOException {
        currentHome = Files.createDirectories(homes.resolve("home-" + homeSequence++).resolve("runtime")).getParent();
        System.setProperty("ofbiz.home", currentHome.toString());
        return new FileSystemContentStore();
    }

    /** The home directory the most recently created provider resolves its root against. */
    private Path currentOfbizHome() {
        return currentHome;
    }

    /** The upload root the most recently created provider confines every key to. */
    private Path currentUploadRoot() {
        return currentHome.resolve(COMMITTED_PREFIX);
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Where content lands
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void storedContentLandsBeneathTheConfiguredUploadPrefixAndNowhereElse() throws Exception {
        ContentStore store = newFileSystemContentStore();
        Path home = currentOfbizHome();

        store.put(COMMITTED_PREFIX + "/party/logo.png", PAYLOAD);

        // Reading the bytes back through the filesystem rather than through the provider is what shows
        // where they really went.
        Path expected = home.resolve(COMMITTED_PREFIX + "/party/logo.png");
        assertTrue(Files.isRegularFile(expected), "the provider must have created " + expected);
        assertArrayEquals(PAYLOAD, Files.readAllBytes(expected), "the bytes on disk");

        // "and nowhere else" is the half with teeth, and it takes two keys because two independent
        // boundaries are in force. A key the deployment's allow list does not cover at all is refused by
        // every operation; a key the allow list DOES cover - runtime/ is on it - but which lies outside the
        // upload root is refused by the two that would change the store. Without the second boundary a
        // storage key could put request-supplied content one level below the application.
        assertEveryOperationRefuses(store, "party/logo.png", "not within an allowed directory");
        assertEveryMutationRefuses(store, "runtime/party/logo.png", "the only tree this provider may modify");
        assertFalse(Files.exists(home.resolve("party")), "nothing may be written directly under the OFBiz home directory");
        assertFalse(Files.exists(home.resolve("runtime/party")), "nothing may be written outside the upload root");
    }

    @Test
    public void theUploadRootFollowsTheConfiguredPrefixRatherThanBeingHardCoded() throws Exception {
        ContentStore store = newFileSystemContentStore();
        Path home = currentOfbizHome();
        String alternativePrefix = "runtime/uploads-alternative";
        UtilProperties.setPropertyValueInMemory(CONTENT_RESOURCE, UPLOAD_PATH_PREFIX, alternativePrefix);

        store.put(alternativePrefix + "/party/logo.png", PAYLOAD);

        // A provider that had hard-coded runtime/uploads, or the home directory, would pass every other
        // test in this class and still ignore the operator's configuration.
        Path expected = home.resolve(alternativePrefix + "/party/logo.png");
        assertTrue(Files.isRegularFile(expected), "the provider must follow the configured prefix and create " + expected);
        assertArrayEquals(PAYLOAD, Files.readAllBytes(expected), "the bytes on disk");

        // The other direction is what proves the configured prefix is the BOUNDARY and not merely the
        // default location: the tree that was the upload root a moment ago is now outside it, and a write
        // addressed there is refused. A provider that treated the prefix as a default would accept both.
        assertEveryMutationRefuses(store, COMMITTED_PREFIX + "/party/logo.png", "the only tree this provider may modify");
        assertFalse(Files.exists(home.resolve(COMMITTED_PREFIX)), "nothing may be written to the prefix that is no longer configured");
    }

    @Test
    public void aKeyNamingStateOutsideTheUploadRootCanNoLongerBeOverwrittenOrDeleted() throws Exception {
        ContentStore store = newFileSystemContentStore();
        Path home = currentOfbizHome();
        // A real path from a running instance: the embedded database lives under runtime/data/h2, which
        // is inside runtime/ and therefore ON the OFBiz file allow list. A resolver rooted at the home
        // directory resolves this key straight onto it and the allow list raises no objection, which is
        // exactly why the upload root has to be the confinement for anything that CHANGES the store.
        String key = "runtime/data/h2/ofbiz.mv.db";
        Path victim = home.resolve(key);
        Files.createDirectories(victim.getParent());
        Files.write(victim, UNTOUCHABLE);

        assertEveryMutationRefuses(store, key, "the only tree this provider may modify");

        // The decisive assertion: the file is still there, byte for byte. No rewording of the resolver can
        // satisfy this by accident.
        assertArrayEquals(UNTOUCHABLE, Files.readAllBytes(victim), "the file outside the upload root must be byte-identical afterwards");

        // And the refusal is a refusal rather than a redirection. A provider that quietly re-rooted the key
        // onto the upload root would leave the victim intact too - and would also make put and get address
        // different files for one key, and change the meaning of every objectInfo value already persisted -
        // so nothing may appear underneath the upload root either.
        Path confined = home.resolve(COMMITTED_PREFIX).resolve(key);
        assertFalse(Files.exists(confined), "a refused write may not be re-rooted onto the upload root, at " + confined);

        // Reading such a key deliberately still resolves, and that is a compatibility guarantee rather than
        // an oversight: OFBIZ_FILE object information in existing databases points all over the OFBiz home
        // directory, and narrowing reads to the upload root would start refusing content that resolves
        // today. The Content component's allow lists stay the read boundary; the upload root is the write
        // boundary. Pinning it here is what stops the read surface being narrowed by accident later.
        assertArrayEquals(UNTOUCHABLE, store.get(key), "an allow-listed key must keep resolving for a read");
        assertTrue(store.exists(key), "and must keep being reported present");
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Keys that must be refused
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void everyOperationRefusesAKeyThatTraversesOutOfTheUploadRoot() throws Exception {
        ContentStore store = newFileSystemContentStore();
        Path home = currentOfbizHome();
        Path insideTheHome = home.resolve("runtime/escaped.bin");
        Path insideTheTree = home.resolve("escaped.bin");

        for (String hostile : new String[] {
                "../escaped.bin",
                "./../escaped.bin",
                "uploads/../../escaped.bin",
                "a/b/../../../escaped.bin",
                "../../escaped.bin",
                "../../../../../../etc/passwd"}) {
            // A mutation refuses the traversal component itself, before the key is resolved at all, which
            // is stricter than resolving and then testing containment: normalising is exactly what makes
            // a/../../b look innocuous.
            assertEveryMutationRefuses(store, hostile, "a traversal component is refused rather than normalised away");
            // A read refuses it too, through the Content component's allow list, because every one of these
            // keys resolves outside every allow-listed subtree of the OFBiz home directory.
            assertEveryReadRefuses(store, hostile, "not within an allowed directory");
        }
        assertFalse(Files.exists(insideTheHome), "traversal may not create " + insideTheHome);
        assertFalse(Files.exists(insideTheTree), "traversal may not create " + insideTheTree);
    }

    @Test
    public void everyOperationRefusesAnAbsoluteKeyEvenWhenItPointsInsideTheUploadRoot() throws Exception {
        ContentStore store = newFileSystemContentStore();
        Path inside = currentUploadRoot().resolve("absolute-but-inside.bin");

        // The third case is the interesting one. It addresses a legal location, so a resolver could be
        // tempted to accept it - but a key is provider-relative by contract, and accepting an absolute
        // form for a WRITE would mean the same key meant different things to this provider and to the S3
        // one, and would leave the escape one configuration change away from being reachable again.
        for (String hostile : new String[] {"/etc/passwd", "/tmp/escaped-by-an-absolute-key.bin", inside.toString()}) {
            assertEveryMutationRefuses(store, hostile, "not by an absolute path");
        }

        // Reads are a different surface, deliberately: LOCAL_FILE object information in existing databases
        // IS absolute, so an absolute key has to keep resolving for a read. What bounds it there is the
        // Content component's local-file allow list together with the provider's own containment, and that
        // is what refuses the two keys which leave the deployment tree.
        assertEveryReadRefuses(store, "/etc/passwd", "not within an allowed directory");
        assertEveryReadRefuses(store, "/tmp/escaped-by-an-absolute-key.bin", "not within an allowed directory");

        // The one addressing a legal location inside the upload root resolves, and finds nothing. That is
        // the proof the refused write was refused outright rather than performed somewhere unnoticed.
        assertThrows(FileNotFoundException.class, () -> store.get(inside.toString()),
                "an absolute key inside the tree must resolve and report absence, not content");
        assertFalse(store.exists(inside.toString()), "and must report the location as empty");
        assertFalse(Files.exists(inside), "an absolute key may not create " + inside);
    }

    @Test
    public void everyOperationRefusesAKeyThatOnlyReAddressesTheUploadRootItself() throws Exception {
        ContentStore store = newFileSystemContentStore();

        // A key has to address content. Letting one address the root directory would turn get and
        // openStream into directory reads and delete into an attempt on the root itself. The resolver
        // forecloses the whole class one step earlier than a containment test would, by refusing any key
        // carrying a [.] or [..] component at all - which covers these a fortiori and cannot be defeated
        // by a spelling that normalises to the root only after the fact.
        for (String hostile : new String[] {".", "./", "a/..", "./.", "a/b/../.."}) {
            assertEveryMutationRefuses(store, hostile, "a traversal component is refused rather than normalised away");
            assertEveryReadRefuses(store, hostile, "not within an allowed directory");
        }
    }

    @Test
    public void everyOperationRefusesABlankKey() throws Exception {
        ContentStore store = newFileSystemContentStore();

        // The shared contract already pins null and empty. A key that is nothing but whitespace is the
        // same unusable input wearing a length, and resolving it would create an entry whose name no
        // caller could reliably address a second time.
        for (String blank : new String[] {" ", "\t", "  \n "}) {
            assertEveryOperationRefuses(store, blank, "the key is null, empty or blank");
        }
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Failing closed
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void everyOperationFailsClosedWhenTheUploadRootCannotBeResolved() throws Exception {
        ContentStore store = newFileSystemContentStore();
        String key = COMMITTED_PREFIX + "/party/logo.png";

        System.clearProperty("ofbiz.home");
        assertEveryOperationRefuses(store, key, "the ofbiz.home system property is not set");

        System.setProperty("ofbiz.home", currentOfbizHome().toString());
        UtilProperties.setPropertyValueInMemory(CONTENT_RESOURCE, UPLOAD_PATH_PREFIX, "");
        // Refusing rather than silently falling back to the home directory is the whole difference
        // between a confined provider and the defect this class exists to prevent from returning. The
        // key used here is one the allow list accepts, so the refusal can only be coming from the missing
        // prefix; and only the mutating operations are asserted, because the prefix bounds what may be
        // CHANGED - with no prefix configured there is no upload root for a write to be inside of, while a
        // read is still governed by the allow list and the deployment boundary as before.
        assertEveryMutationRefuses(store, key,
                "[" + UPLOAD_PATH_PREFIX + "] of resource [" + CONTENT_RESOURCE + "] is not configured");
    }

    @Test
    public void everyOperationFailsClosedWhenTheAllowListDoesNotCoverTheUploadRoot() throws Exception {
        ContentStore store = newFileSystemContentStore();
        Path inside = currentUploadRoot().resolve("party/logo.png");
        UtilProperties.setPropertyValueInMemory(SECURITY_RESOURCE, OFBIZ_FILE_ALLOWED_PATHS, "applications/");

        // Confinement to the upload root is necessary but not sufficient: the operator also decides
        // which parts of the tree may hold file-backed content at all. Asserting the allow list's own
        // wording is what proves that check is genuinely in the chain rather than merely nearby.
        assertEveryOperationRefuses(store, COMMITTED_PREFIX + "/party/logo.png", "not within an allowed directory");
        assertFalse(Files.exists(inside), "a key the allow list rejects may not create " + inside);
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * The upload path helpers, which storage keys do not go through
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void theUploadPathHelpersStillReturnTheirTwoDistinctFormsUnderneathTheConfiguredPrefix() throws Exception {
        FileSystemContentStore store = newFileSystemContentStore();
        Path uploadRoot = currentUploadRoot();

        String absolute = store.getUploadPath(true);
        String relative = store.getUploadPath(false);

        // These two forms are persisted in DataResource.objectInfo values in every existing database and
        // are therefore frozen; confining storage keys must not have collapsed them into one form.
        assertTrue(absolute.startsWith(uploadRoot + "/"), "the absolute form must sit under the upload root, but was " + absolute);
        assertTrue(relative.startsWith("/" + COMMITTED_PREFIX + "/"),
                "the relative form must stay relative to the OFBiz home directory, but was " + relative);
        assertNotEquals(absolute, relative, "the two forms must never be collapsed into one");
        assertTrue(Files.isDirectory(Path.of(absolute)), "the absolute form must name a directory that was created");
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Helpers
     * ---------------------------------------------------------------------------------------------
     */

    /**
     * Asserts that all five operations refuse the supplied key, each reporting the same expected reason.
     *
     * <p>Driving every operation matters: a resolver applied to four of the five would leave one
     * unguarded primitive, which is precisely the shape of the defect being guarded against. This form is
     * for the inputs that no operation can make sense of, so all five genuinely report one reason - an
     * unusable key, a missing {@code ofbiz.home}, an allow list that covers nothing relevant.
     *
     * @param store the provider under test
     * @param key the key every operation must refuse
     * @param expectedReason a fragment the reported reason has to contain, so that the key is refused
     *     for the reason under test rather than incidentally
     */
    private static void assertEveryOperationRefuses(ContentStore store, String key, String expectedReason) {
        assertEachRefuses(operations(store), key, expectedReason);
    }

    /**
     * Asserts that both operations which CHANGE the store refuse the supplied key for the expected reason.
     *
     * <p>Used wherever the upload-root confinement is what is under test, because that boundary applies to
     * {@code put} and {@code delete} and deliberately not to the three read operations - a read is bounded
     * by the Content component's allow lists instead, so holding it to a mutation's wording would assert
     * something the provider is documented not to do.
     *
     * @param store the provider under test
     * @param key the key both mutating operations must refuse
     * @param expectedReason a fragment the reported reason has to contain
     */
    private static void assertEveryMutationRefuses(ContentStore store, String key, String expectedReason) {
        assertEachRefuses(mutatingOperations(store), key, expectedReason);
    }

    /**
     * Asserts that all three read operations refuse the supplied key for the expected reason.
     *
     * <p>Paired with {@link #assertEveryMutationRefuses(ContentStore, String, String)} so that every one of
     * the five operations is still driven for every hostile key: leaving one unguarded primitive is
     * precisely the shape of the defect this class exists to prevent, and splitting the assertion by
     * surface must not be allowed to reintroduce it.
     *
     * @param store the provider under test
     * @param key the key every read operation must refuse
     * @param expectedReason a fragment the reported reason has to contain
     */
    private static void assertEveryReadRefuses(ContentStore store, String key, String expectedReason) {
        assertEachRefuses(readingOperations(store), key, expectedReason);
    }

    /**
     * Shared core: every supplied operation must refuse the key, and must say why.
     *
     * @param operations the operations to drive, keyed by the name used in the failure message
     * @param key the key each operation must refuse
     * @param expectedReason a fragment the reported reason has to contain, so that the key is refused for
     *     the reason under test rather than incidentally
     */
    private static void assertEachRefuses(Map<String, KeyedOperation> operations, String key, String expectedReason) {
        // A guard rather than a courtesy: an empty map would make every caller of this helper vacuous.
        assertFalse(operations.isEmpty(), "the set of operations under test must not be empty");
        for (Map.Entry<String, KeyedOperation> operation : operations.entrySet()) {
            GeneralException failure = assertThrows(GeneralException.class, () -> operation.getValue().accept(key),
                    operation.getKey() + " must refuse the key [" + key + "] with GeneralException");
            assertTrue(String.valueOf(failure.getMessage()).contains(expectedReason),
                    operation.getKey() + " must refuse the key [" + key + "] because it " + expectedReason
                            + ", but reported: " + failure.getMessage());
        }
    }

    /** All five operations, keyed by name, each reduced to a single storage-key argument. */
    private static Map<String, KeyedOperation> operations(ContentStore store) {
        Map<String, KeyedOperation> operations = new LinkedHashMap<>(mutatingOperations(store));
        operations.putAll(readingOperations(store));
        return operations;
    }

    /** The two operations that change the store, which are the ones confined to the upload root. */
    private static Map<String, KeyedOperation> mutatingOperations(ContentStore store) {
        Map<String, KeyedOperation> operations = new LinkedHashMap<>();
        operations.put("put", key -> store.put(key, PAYLOAD));
        operations.put("delete", store::delete);
        return operations;
    }

    /** The three operations that only read the store, which honour the Content component's allow lists. */
    private static Map<String, KeyedOperation> readingOperations(ContentStore store) {
        Map<String, KeyedOperation> operations = new LinkedHashMap<>();
        operations.put("get", store::get);
        operations.put("openStream", store::openStream);
        operations.put("exists", store::exists);
        return operations;
    }

    /** One {@link ContentStore} operation, reduced to its storage-key argument so all five can be driven alike. */
    private interface KeyedOperation {
        /**
         * Invokes the operation with the supplied storage key.
         *
         * @param key the storage key to pass through
         * @throws Exception whatever the underlying operation raises
         */
        void accept(String key) throws Exception;
    }
}
