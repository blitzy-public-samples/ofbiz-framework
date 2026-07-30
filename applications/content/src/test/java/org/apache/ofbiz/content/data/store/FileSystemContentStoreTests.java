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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.content.data.DataResourceWorker;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.util.EntityUtilProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

/**
 * Executes {@link FileSystemContentStore} - the production {@code filesystem} provider itself, not a stand-in.
 *
 * <p>The whole suite runs against a temporary directory installed as {@code ofbiz.home} for the duration of a
 * single test, which is what makes it safe to exercise a provider that writes real files and creates real
 * timestamp-named upload directories: nothing it does can reach the working tree. Every global the suite
 * touches - the {@code ofbiz.home} system property and the two allow-list properties of the {@code security}
 * resource - is snapshotted before it is changed and restored afterwards, because the unit tier runs in one
 * shared JVM and {@code UtilProperties.setPropertyValueInMemory} mutates a cached {@code Properties} object in
 * place.
 *
 * <p>By extending {@link ContentStoreBehaviourContract} this class settles the whole shared SPI contract
 * against the real provider. On top of that it covers what only this provider has: the two allow-list
 * branches of its key resolution, the home boundary check that stays in force even when the allow list in front
 * of it is widened, the upload-directory rollover, and - because the {@code filesystem} provider exists to
 * reproduce what the Content component already did - a differential comparison against the pre-existing
 * {@link DataResourceWorker} behaviour it must match byte for byte and path for path.
 *
 * <p>Beyond that shared contract this class also pins the five properties that make the provider safe to
 * run in a multi-instance deployment: a write publishes atomically and leaves no staging artefact behind,
 * <strong>the whole content reaches the storage device before anything is published</strong>, <strong>a
 * location that escapes the storage root through a symbolic link is refused by the provider's own boundary
 * check</strong>, a filesystem object that is not a regular file is never mistaken for stored content or
 * removed, and a location that cannot be resolved at all is reported rather than dereferenced.
 */
public final class FileSystemContentStoreTests extends ContentStoreBehaviourContract {

    private static final String CONTENT_RESOURCE = "content";
    private static final String SECURITY_RESOURCE = "security";
    private static final String UPLOAD_PATH_PREFIX = "content.upload.path.prefix";
    private static final String UPLOAD_MAX_FILES = "content.upload.max.files";
    private static final String OFBIZ_ALLOW_LIST = "content.data.ofbiz.file.allowed.paths";
    private static final String LOCAL_ALLOW_LIST = "content.data.local.file.allowed.paths";
    private static final String OFBIZ_HOME = "ofbiz.home";
    private static final String MAX_GET_BYTES = "content.store.max.get.bytes";

    /**
     * The upload root, mirroring the committed {@code content.upload.path.prefix} with a trailing separator.
     *
     * <p>Written out as a literal so that the keys below read as keys rather than as concatenations, and
     * pinned against the property itself by
     * {@link #theKeysThisSuiteWritesLieInsideTheUploadRootTheProviderConfinesTo()} so that the two cannot
     * drift apart in silence.
     */
    private static final String UPLOAD_ROOT = "runtime/uploads/";

    private static final String KEY = UPLOAD_ROOT + "contract/sample.bin";
    private static final byte[] PAYLOAD = "content that has to survive a round trip".getBytes(StandardCharsets.UTF_8);

    /** Exactly the directories these tests write into, so an escape is still an escape. */
    private static final String TEST_ALLOW_LIST = "contract/,uploads/,runtime/,delegator-uploads/,parity/";

    /** The default the provider falls back to whenever {@code content.upload.max.files} yields nothing usable. */
    private static final int COMMITTED_DEFAULT_MAX_FILES = 250;

    /**
     * Whether the default filesystem can report POSIX permissions at all.
     *
     * <p>The provider tolerates a filesystem with no POSIX permission model - refusing there would make it
     * unusable on one - so the two tests that assert an exact mode have to be skipped rather than failed on
     * such a filesystem, or they would be asserting something the provider deliberately does not promise.
     */
    private static final boolean POSIX_PERMISSIONS_SUPPORTED =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    /**
     * The keys the provider-specific expectations at the end of this class address.
     *
     * <p>They sit in their own sub-tree of the upload root so that they cannot collide with the keys the
     * shared contract writes through: one of those expectations asserts the exact contents of the
     * directory it writes into, which a stray neighbouring file would break.
     */
    private static final String STORE_KEY = UPLOAD_ROOT + "contentstore/sample.bin";
    private static final String ABSENT_KEY = UPLOAD_ROOT + "contentstore/nothing-stored-here.bin";
    private static final String LINK_PARENT = UPLOAD_ROOT + "contentstore";
    private static final String ESCAPING_KEY = UPLOAD_ROOT + "contentstore/escape/secret.bin";
    private static final byte[] CONTENT = "the quick brown fox".getBytes(StandardCharsets.UTF_8);
    private static final byte[] REPLACEMENT = "jumps".getBytes(StandardCharsets.UTF_8);

    @TempDir
    private Path workspace;

    /** A second, distinct temporary directory standing in for anything the storage root must not reach. */
    @TempDir
    private Path outside;

    private final Map<String, String> propertySnapshot = new LinkedHashMap<>();
    private String ofbizHomeSnapshot;
    private Path home;
    private int storeSequence;

    /**
     * The provider instance the expectations in the second half of this class share.
     *
     * <p>The expectations inherited from the shared contract obtain their own store from
     * {@link #newEmptyContentStore()} instead, and the ones in the first half construct their own,
     * so this field is deliberately not used by either.
     */
    private FileSystemContentStore store;

    @BeforeEach
    public void redirectOfbizHomeIntoATemporaryDirectory() throws Exception {
        ofbizHomeSnapshot = System.getProperty(OFBIZ_HOME);
        // toRealPath so that a symlinked temporary root cannot make the provider's canonical boundary check
        // disagree with the home directory this suite believes it configured.
        home = Files.createDirectories(workspace.resolve("home")).toRealPath();
        System.setProperty(OFBIZ_HOME, home.toString());
        override(SECURITY_RESOURCE, OFBIZ_ALLOW_LIST, TEST_ALLOW_LIST);
        override(SECURITY_RESOURCE, LOCAL_ALLOW_LIST, "${ofbiz.home}");
        // The provider-specific expectations further down share one instance rooted at the home
        // directory above. It is built here rather than per test because one of them clears
        // ofbiz.home afterwards to prove the provider resolves the home directory on every call
        // instead of capturing it once at construction.
        store = new FileSystemContentStore();
    }

    @AfterEach
    public void restoreEveryGlobalThisSuiteChanged() {
        propertySnapshot.forEach((qualifiedName, previous) -> {
            int separator = qualifiedName.indexOf('|');
            UtilProperties.setPropertyValueInMemory(qualifiedName.substring(0, separator),
                    qualifiedName.substring(separator + 1), previous);
        });
        propertySnapshot.clear();
        if (ofbizHomeSnapshot == null) {
            System.clearProperty(OFBIZ_HOME);
        } else {
            System.setProperty(OFBIZ_HOME, ofbizHomeSnapshot);
        }
    }

    /**
     * Hands the shared behaviour contract a real provider over a directory nothing else has written to.
     *
     * <p>The provider roots itself at {@code ofbiz.home}, so a fresh empty store means a fresh home
     * directory; the {@code @AfterEach} above puts the original value back either way.
     */
    @Override
    protected ContentStore newEmptyContentStore() throws Exception {
        Path storeHome = Files.createDirectories(workspace.resolve("store-home-" + storeSequence++)).toRealPath();
        System.setProperty(OFBIZ_HOME, storeHome.toString());
        home = storeHome;
        return new FileSystemContentStore();
    }

    /**
     * Composes the shared contract's keys inside the upload root, because this provider confines what it may
     * modify to exactly that tree.
     *
     * <p>Derived from {@code content.upload.path.prefix} rather than hard-coded, so the prefix the contract
     * writes through is always the one the provider will confine to. The provider's own tolerance of a leading
     * separator is reproduced, because resolving an absolute prefix against the home directory would discard
     * the home directory and the contract would then be writing somewhere else entirely.
     *
     * @return the configured upload prefix, with a trailing separator
     */
    @Override
    protected String keyPrefix() {
        String prefix = UtilProperties.getPropertyValue(CONTENT_RESOURCE, UPLOAD_PATH_PREFIX).replace('\\', '/');
        while (prefix.startsWith("/")) {
            prefix = prefix.substring(1);
        }
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Where the bytes actually land
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void contentLandsOnDiskUnderneathOfbizHomeAtTheKeyRelativePath() throws Exception {
        FileSystemContentStore store = new FileSystemContentStore();

        store.put(KEY, PAYLOAD);

        // The relative form of a key is joined to ofbiz.home; nothing may be rewritten, flattened or hashed,
        // because DataResource.objectInfo values already persisted in every existing database address content
        // through exactly this path.
        Path expected = home.resolve(KEY);
        assertTrue(Files.isRegularFile(expected), "expected the content at " + expected);
        assertArrayEquals(PAYLOAD, Files.readAllBytes(expected), "the file on disk must hold exactly the bytes that were put");
    }

    @Test
    public void theKeysThisSuiteWritesLieInsideTheUploadRootTheProviderConfinesTo() {
        // Pins the literal against the property it mirrors. Were the committed prefix to change, every write in
        // this suite would start being refused for the right reason but with a message about confinement rather
        // than about whatever the test was actually asserting - so the drift is caught here, once, explicitly.
        assertEquals(UPLOAD_ROOT, keyPrefix(), "the suite's own keys and the contract's keys must share one root");
        assertEquals("runtime/uploads", UtilProperties.getPropertyValue(CONTENT_RESOURCE, UPLOAD_PATH_PREFIX),
                "the committed upload prefix is what both of them are built from");
    }

    @Test
    public void everyMissingIntermediateDirectoryIsCreatedByThePutItself() throws Exception {
        FileSystemContentStore store = new FileSystemContentStore();
        String deepKey = UPLOAD_ROOT + "contract/one/two/three/four.bin";

        store.put(deepKey, PAYLOAD);

        assertTrue(Files.isDirectory(home.resolve(UPLOAD_ROOT + "contract/one/two/three")),
                "put must create the whole intermediate structure");
        assertArrayEquals(PAYLOAD, store.get(deepKey), "the deeply nested content must read back");
    }

    @Test
    public void anAbsentKeyIsReportedAsAFileNotFoundExceptionThatIdentifiesItWithoutQuotingIt() throws Exception {
        FileSystemContentStore store = new FileSystemContentStore();
        String absent = UPLOAD_ROOT + "contract/never-written.bin";

        FileNotFoundException fromGet = assertThrows(FileNotFoundException.class, () -> store.get(absent));
        FileNotFoundException fromStream = assertThrows(FileNotFoundException.class, () -> store.openStream(absent));

        // The message is the operator's only clue when a DataResource points at content that has gone missing, so
        // it has to identify which content - but it must NOT quote the key back, because a key is caller-supplied
        // and reaches a log file that an operator reads. Both messages therefore carry the stable opaque
        // reference, which is reproducible from the key on demand and cannot forge a log record.
        String reference = ContentStoreUtil.reference(absent);
        assertTrue(fromGet.getMessage().contains(reference), "get should identify the key, was: " + fromGet.getMessage());
        assertTrue(fromStream.getMessage().contains(reference), "openStream should identify the key, was: " + fromStream.getMessage());
        assertFalse(fromGet.getMessage().contains(absent), "the key itself must not be echoed, was: " + fromGet.getMessage());
        assertFalse(fromStream.getMessage().contains(absent), "the key itself must not be echoed, was: " + fromStream.getMessage());
        assertFalse(Files.exists(home.resolve(absent)), "a failed read must not create the file");
    }

    @Test
    public void openStreamLeavesTheFileHandleOpenAndOwnedByTheCaller() throws Exception {
        FileSystemContentStore store = new FileSystemContentStore();
        store.put(KEY, PAYLOAD);

        InputStream stream = store.openStream(KEY);
        try {
            // Still readable after the provider returned: the provider must not have closed what it handed over.
            assertArrayEquals(PAYLOAD, stream.readAllBytes(), "the caller must be able to read the stream it was given");
        } finally {
            stream.close();
        }
        assertThrows(IOException.class, stream::read, "once the caller closes it, the stream is spent");
    }

    @Test
    public void deletingNothingCreatesNothing() throws Exception {
        FileSystemContentStore store = new FileSystemContentStore();
        String absent = UPLOAD_ROOT + "contract/never-written.bin";

        store.delete(absent);

        assertFalse(Files.exists(home.resolve(absent)), "an idempotent delete must not leave a file behind");
        assertFalse(store.exists(absent), "and the key must still be absent");
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Key resolution, the two allow lists, and the boundary check behind them
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void aRelativeKeyOutsideTheOfbizFileAllowListIsRefused() throws Exception {
        FileSystemContentStore store = new FileSystemContentStore();
        Files.createDirectories(home.resolve("secrets"));
        Files.write(home.resolve("secrets/credentials.bin"), PAYLOAD);

        // The directory exists and holds content, so only the allow list can be what refuses the read.
        GeneralException refused = assertThrows(GeneralException.class, () -> store.get("secrets/credentials.bin"));

        assertTrue(refused.getMessage().contains("not within an allowed directory"),
                "expected the allow-list refusal, was: " + refused.getMessage());
    }

    @Test
    public void anAbsoluteKeyUnderneathOfbizHomeIsCheckedAgainstTheLocalFileAllowList() throws Exception {
        FileSystemContentStore store = new FileSystemContentStore();
        Path target = home.resolve("contract/absolute.bin");
        Files.createDirectories(target.getParent());
        Files.write(target, PAYLOAD);
        String absoluteKey = target.toString();

        // ${ofbiz.home} is on the local allow list, so the absolute form resolves...
        assertArrayEquals(PAYLOAD, store.get(absoluteKey), "an absolute key inside the allow list must resolve");

        // ...and narrowing that list to somewhere else is what refuses it. Only the LOCAL list is narrowed here,
        // which is what proves the absolute form is checked against that list rather than the OFBiz one.
        override(SECURITY_RESOURCE, LOCAL_ALLOW_LIST, "${ofbiz.home}/nowhere");
        GeneralException refused = assertThrows(GeneralException.class, () -> store.get(absoluteKey));
        assertTrue(refused.getMessage().contains("not within an allowed directory"),
                "expected the local allow-list refusal, was: " + refused.getMessage());
    }

    @Test
    public void everyTraversalShapedKeyIsRefusedBeforeAnythingIsReadOrWritten() throws Exception {
        FileSystemContentStore store = new FileSystemContentStore();

        // Both guards in the resolution path compare canonical paths, so ../ is already collapsed before either
        // decides. Which of the two speaks first matters far less than the invariant, so the assertion is on the
        // refusal itself and on the write: a key that leaves ofbiz.home must never become a file outside it.
        for (String hostile : List.of("../escaped.bin", "contract/../../escaped.bin", "runtime/../../../escaped.bin")) {
            GeneralException onRead = assertThrows(GeneralException.class, () -> store.get(hostile),
                    hostile + " must not resolve outside ofbiz.home on a read");
            assertTrue(onRead.getMessage().startsWith("Access to file denied"),
                    "expected an access refusal for " + hostile + ", was: " + onRead.getMessage());
            assertThrows(GeneralException.class, () -> store.put(hostile, PAYLOAD),
                    hostile + " must not resolve outside ofbiz.home on a write");
        }
        assertFalse(Files.exists(workspace.resolve("escaped.bin")), "no traversal may have reached outside the home directory");
    }

    @Test
    public void theProvidersOwnHomeBoundaryStillRefusesWhatAWidenedAllowListWouldAdmit() throws Exception {
        FileSystemContentStore store = new FileSystemContentStore();
        // An operator who widens the local allow list to a shared parent - a plausible thing to do for a mounted
        // volume - hands that list a reason to say yes to a path outside ofbiz.home. The provider's own
        // canonical-plus-normalized boundary check is then the only guard left, and this is the one configuration
        // in which it is observable rather than masked by the allow list in front of it.
        override(SECURITY_RESOURCE, LOCAL_ALLOW_LIST, workspace.toString());
        String escaping = home + File.separator + ".." + File.separator + "escaped.bin";

        GeneralException refused = assertThrows(GeneralException.class, () -> store.get(escaping),
                "a key leaving ofbiz.home must be refused however wide the allow list is");

        assertTrue(refused.getMessage().contains("resolves outside of the allowed directory"),
                "expected the provider's own boundary refusal, was: " + refused.getMessage());
        assertThrows(GeneralException.class, () -> store.put(escaping, PAYLOAD), "and the same key must not be writable either");
        assertFalse(Files.exists(workspace.resolve("escaped.bin")), "nothing may have been written outside the home directory");
    }

    @Test
    public void aNullOrEmptyKeyIsRejectedBeforeAnyPathIsBuilt() throws Exception {
        FileSystemContentStore store = new FileSystemContentStore();

        for (String unusable : new String[] {null, ""}) {
            GeneralException rejected = assertThrows(GeneralException.class, () -> store.exists(unusable));
            assertTrue(rejected.getMessage().contains("the key is null or empty"),
                    "expected the unusable-key rejection, was: " + rejected.getMessage());
        }
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * The mutation surface: one confined root, which no link may redirect a change out of
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void aWriteIsRefusedForAKeyThatEveryReadWouldHappilyResolve() throws Exception {
        FileSystemContentStore store = new FileSystemContentStore();
        // On the OFBiz allow list, underneath ofbiz.home, and holding real content - so every guard the READ path
        // applies says yes to it. Only the narrower mutation confinement can be what refuses the change, which is
        // exactly what makes this the test that the mutation surface really is narrower than the read surface.
        String readable = "contract/readable.bin";
        Path target = home.resolve(readable);
        Files.createDirectories(target.getParent());
        Files.write(target, PAYLOAD);

        assertArrayEquals(PAYLOAD, store.get(readable), "the read surface must still admit this key");

        GeneralException onWrite = assertThrows(GeneralException.class, () -> store.put(readable, "replaced".getBytes(StandardCharsets.UTF_8)));
        GeneralException onRemoval = assertThrows(GeneralException.class, () -> store.delete(readable));

        assertTrue(onWrite.getMessage().contains("resolves outside the upload root"),
                "expected the confinement refusal on a write, was: " + onWrite.getMessage());
        assertTrue(onRemoval.getMessage().contains("resolves outside the upload root"),
                "expected the confinement refusal on a removal, was: " + onRemoval.getMessage());
        assertArrayEquals(PAYLOAD, Files.readAllBytes(target), "the refused write must have left the content untouched");
    }

    @Test
    public void everyKeyShapeThatCouldChooseItsOwnRootIsRefusedForAWriteAndForARemoval() throws Exception {
        FileSystemContentStore store = new FileSystemContentStore();
        Map<String, String> refusals = new LinkedHashMap<>();
        refusals.put("../escaped.bin", "traversal component is refused");
        refusals.put(UPLOAD_ROOT + "../../escaped.bin", "traversal component is refused");
        refusals.put(UPLOAD_ROOT + "sub/../../../escaped.bin", "traversal component is refused");
        refusals.put(UPLOAD_ROOT + "./here.bin", "traversal component is refused");
        // Backslashes are folded to forward slashes before the components are examined, so a Windows-shaped
        // traversal cannot slip past a check that only looked for "/../".
        refusals.put("runtime\\uploads\\..\\..\\escaped.bin", "traversal component is refused");
        refusals.put("/etc/passwd", "not by an absolute path");
        // The absolute form really persisted for LOCAL_FILE content: inside the upload root, and still refused,
        // because a write that may name its own absolute path may name any root at all.
        refusals.put(home.resolve(UPLOAD_ROOT + "absolute.bin").toString(), "not by an absolute path");
        refusals.put("component://content/data/escaped.bin", "a location URL addresses content");
        refusals.put(UPLOAD_ROOT + "nul\u0000.bin", "contains a control character");
        refusals.put(UPLOAD_ROOT + "newline\nERROR forged log record.bin", "contains a control character");

        for (Map.Entry<String, String> refusal : refusals.entrySet()) {
            String hostile = refusal.getKey();
            GeneralException onWrite = assertThrows(GeneralException.class, () -> store.put(hostile, PAYLOAD),
                    describeForAssertion(hostile) + " must not be writable");
            GeneralException onRemoval = assertThrows(GeneralException.class, () -> store.delete(hostile),
                    describeForAssertion(hostile) + " must not be removable");
            assertTrue(onWrite.getMessage().contains(refusal.getValue()),
                    "expected [" + refusal.getValue() + "] for " + describeForAssertion(hostile) + ", was: " + onWrite.getMessage());
            assertTrue(onRemoval.getMessage().contains(refusal.getValue()),
                    "expected [" + refusal.getValue() + "] for " + describeForAssertion(hostile) + ", was: " + onRemoval.getMessage());
            // Neither refusal may quote the key back: a key is caller-supplied and these messages reach a log.
            assertFalse(onWrite.getMessage().contains(hostile), "the key must not be echoed, was: " + onWrite.getMessage());
        }
        assertFalse(Files.exists(workspace.resolve("escaped.bin")), "nothing may have been written outside the home directory");
        assertFalse(Files.exists(home.resolve("escaped.bin")), "nor anywhere else outside the upload root");
        assertFalse(Files.exists(home.resolve(UPLOAD_ROOT + "absolute.bin")), "nor through the absolute form");
    }

    @Test
    public void theUploadRootItselfIsNeverAValidTargetForAWriteOrARemoval() throws Exception {
        FileSystemContentStore store = new FileSystemContentStore();

        // A change has to name something INSIDE the root. Were the root itself acceptable, a removal would be
        // asked to delete the tree that bounds every other key.
        for (String rootItself : List.of("runtime/uploads", "runtime/uploads/", "runtime/uploads/../uploads")) {
            assertThrows(GeneralException.class, () -> store.put(rootItself, PAYLOAD), rootItself + " must not be writable");
            assertThrows(GeneralException.class, () -> store.delete(rootItself), rootItself + " must not be removable");
        }
    }

    @Test
    public void aDirectoryComponentReplacedByASymbolicLinkCannotRedirectAWriteOutOfTheRoot() throws Exception {
        FileSystemContentStore store = new FileSystemContentStore();
        Path uploads = Files.createDirectories(home.resolve(UPLOAD_ROOT));
        // The link target is inside ofbiz.home AND on the OFBiz allow list on purpose. A link pointing right out
        // of the home directory is refused earlier, by the allow list the read path already applies, and would
        // therefore never reach the guard this test is about; pointing it at another allow-listed directory
        // isolates that guard as the only thing left that can say no.
        Path allowListedButOutsideTheRoot = Files.createDirectories(home.resolve("contract/planted"));
        Files.createSymbolicLink(uploads.resolve("escape"), allowListedButOutsideTheRoot);
        // A second link that points back INSIDE the root, to show the rule is "no link is a directory component
        // below the root" rather than "no link that happens to leave the root".
        Path inside = Files.createDirectories(uploads.resolve("real"));
        Files.createSymbolicLink(uploads.resolve("shortcut"), inside);

        GeneralException outward = assertThrows(GeneralException.class, () -> store.put(UPLOAD_ROOT + "escape/planted.bin", PAYLOAD));
        GeneralException inward = assertThrows(GeneralException.class, () -> store.put(UPLOAD_ROOT + "shortcut/planted.bin", PAYLOAD));

        assertTrue(outward.getMessage().contains("symbolic link planted to redirect the write"),
                "expected the planted-link refusal, was: " + outward.getMessage());
        assertTrue(inward.getMessage().contains("symbolic link planted to redirect the write"),
                "a link is refused as a directory component even when it points back inside, was: " + inward.getMessage());
        assertFalse(Files.exists(allowListedButOutsideTheRoot.resolve("planted.bin")),
                "nothing may have been written through the outward link");
        assertFalse(Files.exists(inside.resolve("planted.bin")), "nor through the inward one");
    }

    @Test
    public void aWriteThroughALinkThatLeavesTheHomeDirectoryEntirelyIsRefusedAsWell() throws Exception {
        FileSystemContentStore store = new FileSystemContentStore();
        Path uploads = Files.createDirectories(home.resolve(UPLOAD_ROOT));
        Path elsewhere = Files.createDirectories(workspace.resolve("elsewhere"));
        Files.createSymbolicLink(uploads.resolve("escape"), elsewhere);

        // Which guard speaks first is not the point and is deliberately not asserted - the allow list gets there
        // before the planted-link check does, because the canonical path of the target leaves the home directory.
        // What matters is the outcome: the write is refused and nothing appears at the far end of the link.
        assertThrows(GeneralException.class, () -> store.put(UPLOAD_ROOT + "escape/planted.bin", PAYLOAD));
        assertThrows(GeneralException.class, () -> store.delete(UPLOAD_ROOT + "escape/planted.bin"));
        assertFalse(Files.exists(elsewhere.resolve("planted.bin")), "nothing may have been written outside the home directory");
    }

    @Test
    public void aSymbolicLinkStandingWhereTheContentBelongsIsReplacedRatherThanWrittenThrough() throws Exception {
        FileSystemContentStore store = new FileSystemContentStore();
        Path uploads = Files.createDirectories(home.resolve(UPLOAD_ROOT));
        // Again allow-listed, so that the only question left is whether the write follows the link or replaces it.
        Path victim = home.resolve("contract/must-not-be-overwritten.bin");
        Files.createDirectories(victim.getParent());
        byte[] untouched = "the file another principal pointed the link at".getBytes(StandardCharsets.UTF_8);
        Files.write(victim, untouched);
        Path link = uploads.resolve("linked.bin");
        Files.createSymbolicLink(link, victim);

        // The key lands directly in the root, so this also covers the case where the containing directory IS the
        // root and there is no intermediate component to walk.
        store.put(UPLOAD_ROOT + "linked.bin", PAYLOAD);

        assertFalse(Files.isSymbolicLink(link), "the rename must have replaced the link rather than followed it");
        assertArrayEquals(PAYLOAD, Files.readAllBytes(link), "the content must be in the upload root");
        assertArrayEquals(untouched, Files.readAllBytes(victim), "the file at the far end of the link must be untouched");
    }

    @Test
    public void aSymbolicLinkIsItselfRemovedRatherThanTheFileAtItsFarEnd() throws Exception {
        FileSystemContentStore store = new FileSystemContentStore();
        Path uploads = Files.createDirectories(home.resolve(UPLOAD_ROOT));
        Path victim = home.resolve("contract/must-survive.bin");
        Files.createDirectories(victim.getParent());
        Files.write(victim, PAYLOAD);
        Path link = uploads.resolve("linked.bin");
        Files.createSymbolicLink(link, victim);

        store.delete(UPLOAD_ROOT + "linked.bin");

        assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS), "the link itself must have been removed");
        assertTrue(Files.exists(victim), "the file at the far end of the link must survive the removal");
    }

    @Test
    public void aRemovalRefusesADirectoryBecauseThisStoreRemovesContentRatherThanStructure() throws Exception {
        FileSystemContentStore store = new FileSystemContentStore();
        Path directory = Files.createDirectories(home.resolve(UPLOAD_ROOT + "a-directory"));

        GeneralException refused = assertThrows(GeneralException.class, () -> store.delete(UPLOAD_ROOT + "a-directory"));

        assertTrue(refused.getMessage().contains("removes content rather than structure"),
                "expected the directory refusal, was: " + refused.getMessage());
        assertTrue(Files.isDirectory(directory), "the directory must still be there");
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Owner-only permissions, applied as the object is created rather than afterwards
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void storedContentAndEveryDirectoryCreatedForItAreReachableOnlyByTheirOwner() throws Exception {
        assumeTrue(POSIX_PERMISSIONS_SUPPORTED, "the default filesystem has no POSIX permission model");
        FileSystemContentStore store = new FileSystemContentStore();

        store.put(UPLOAD_ROOT + "modes/deep/content.bin", PAYLOAD);

        assertEquals("rw-------", modeOf(home.resolve(UPLOAD_ROOT + "modes/deep/content.bin")),
                "uploaded content must not be readable by other local principals");
        assertEquals("rwx------", modeOf(home.resolve(UPLOAD_ROOT + "modes/deep")), "nor may the directory holding it be listable");
        assertEquals("rwx------", modeOf(home.resolve(UPLOAD_ROOT + "modes")), "at every level the put created");
        assertEquals("rwx------", modeOf(home.resolve("runtime/uploads")), "including the upload root itself");
        // Deliberately NOT asserted for runtime/: the ancestors of the upload root are not content. They are the
        // OFBiz home directory and runtime/, which the deployment owns and which every other part of OFBiz
        // creates with the ordinary umask, so narrowing them here would change permissions this provider has no
        // business changing. Owner-only starts at the upload root.
        assertTrue(Files.isDirectory(home.resolve("runtime")), "the ancestor is created, just not narrowed");
    }

    @Test
    public void aWorldReadableFileAlreadyStandingAtTheKeyIsReplacedByAnOwnerOnlyOne() throws Exception {
        assumeTrue(POSIX_PERMISSIONS_SUPPORTED, "the default filesystem has no POSIX permission model");
        FileSystemContentStore store = new FileSystemContentStore();
        Path target = home.resolve(UPLOAD_ROOT + "already-there.bin");
        Files.createDirectories(target.getParent());
        Files.write(target, "the previous content".getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-rw-rw-"));

        store.put(UPLOAD_ROOT + "already-there.bin", PAYLOAD);

        // Content written before this provider existed - or by an earlier release of it - does not keep its mode
        // just because the file already existed: the rename puts a new, owner-only object at the name.
        assertEquals("rw-------", modeOf(target), "re-storing content must narrow the mode rather than inherit it");
        assertArrayEquals(PAYLOAD, Files.readAllBytes(target), "and must still have replaced the content in full");
    }

    @Test
    public void neitherASuccessfulNorAFailedWriteLeavesAPartialFileBehind() throws Exception {
        FileSystemContentStore store = new FileSystemContentStore();
        Path uploads = Files.createDirectories(home.resolve(UPLOAD_ROOT));

        store.put(UPLOAD_ROOT + "clean.bin", PAYLOAD);
        assertEquals(0, partialFileCount(uploads), "a successful write must leave no temporary file behind");

        // A directory standing at the destination name is the one way to make the rename itself fail after the
        // temporary file has already been created and written, which is exactly the window the finally clause of
        // put exists for. Without it, every failed upload would leave a copy of the content on the volume.
        Files.createDirectories(uploads.resolve("occupied.bin/child"));
        assertThrows(IOException.class, () -> store.put(UPLOAD_ROOT + "occupied.bin", PAYLOAD),
                "a rename onto a non-empty directory cannot succeed");
        assertEquals(0, partialFileCount(uploads), "a failed write must leave no temporary file behind either");
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * The whole-content read ceiling
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void getRefusesContentAboveTheCeilingWhileOpenStreamStillStreamsIt() throws Exception {
        override(CONTENT_RESOURCE, MAX_GET_BYTES, "16");
        FileSystemContentStore store = new FileSystemContentStore();
        String atCeiling = UPLOAD_ROOT + "at-the-ceiling.bin";
        String aboveCeiling = UPLOAD_ROOT + "above-the-ceiling.bin";

        // A write is deliberately NOT subject to the ceiling - it streams from a byte array the caller already
        // holds, so refusing it would protect nothing - which is why the oversized content can be stored at all.
        store.put(atCeiling, new byte[16]);
        store.put(aboveCeiling, new byte[17]);

        assertEquals(16, store.get(atCeiling).length, "content exactly at the ceiling must still be read whole");

        IOException refused = assertThrows(IOException.class, () -> store.get(aboveCeiling));
        assertTrue(refused.getMessage().contains(MAX_GET_BYTES),
                "the refusal must name the property an operator would change, was: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("17 bytes"), "and the size it refused, was: " + refused.getMessage());
        assertTrue(refused.getMessage().contains(ContentStoreUtil.reference(aboveCeiling)),
                "and must identify the content by reference, was: " + refused.getMessage());
        assertFalse(refused.getMessage().contains(aboveCeiling), "without echoing the key, was: " + refused.getMessage());

        // The escape hatch the refusal points at has to actually work, or the ceiling would make oversized
        // content unreachable rather than merely unbuffered.
        try (InputStream stream = store.openStream(aboveCeiling)) {
            assertEquals(17, stream.readAllBytes().length, "openStream must stream what get refuses");
        }
    }

    @Test
    public void aCeilingThatIsNotAUsableNumberFallsBackToTheCommittedDefaultRatherThanRefusingEveryRead() throws Exception {
        FileSystemContentStore store = new FileSystemContentStore();
        String key = UPLOAD_ROOT + "ordinary.bin";
        store.put(key, PAYLOAD);

        // Zero and negative values are the dangerous ones: taken literally, a ceiling of 0 would refuse every
        // single read in the deployment, turning a tuning mistake into a total content outage.
        for (String unusable : List.of("0", "-1", "not-a-number", "")) {
            override(CONTENT_RESOURCE, MAX_GET_BYTES, unusable);
            assertArrayEquals(PAYLOAD, store.get(key), "a ceiling of [" + unusable + "] must fall back, not refuse");
        }
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * The upload directory: which properties are read, and when a new directory is started
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void theUploadLocationComesFromContentUploadPathPrefixAndIsCreatedOnDemand() {
        override(CONTENT_RESOURCE, UPLOAD_PATH_PREFIX, "uploads");
        override(CONTENT_RESOURCE, UPLOAD_MAX_FILES, "4");
        FileSystemContentStore store = new FileSystemContentStore();

        String relative = store.getUploadPath(false);

        // Proving the exact property is consumed: the prefix above, and nothing else, is what shapes the answer.
        assertTrue(relative.startsWith("/uploads/"), "the relative upload path must sit under the configured prefix, was: " + relative);
        assertTrue(Files.isDirectory(home.resolve(relative.substring(1))), "the upload directory must have been created");
        assertEquals(relative, store.getUploadPath(false), "an under-filled directory must be reused rather than replaced");
    }

    @Test
    public void theAbsoluteAndRelativeUploadFormsDescribeTheSameDirectory() {
        override(CONTENT_RESOURCE, UPLOAD_PATH_PREFIX, "uploads");
        override(CONTENT_RESOURCE, UPLOAD_MAX_FILES, "4");
        FileSystemContentStore store = new FileSystemContentStore();

        String relative = store.getUploadPath(false);
        String absolute = store.getUploadPath(true);

        // The two forms are never collapsed - LOCAL_FILE object information is absolute, OFBIZ_FILE is relative -
        // but they must still point at one directory.
        assertTrue(absolute.startsWith("/"), "the absolute form must be an absolute path, was: " + absolute);
        assertTrue(absolute.endsWith(relative), absolute + " should end with " + relative);
        assertTrue(Files.isDirectory(Path.of(absolute)), "the absolute form must address the directory that exists");
    }

    @Test
    public void aFullDirectoryRollsOverToANewOneAtTheConfiguredCap() throws Exception {
        override(CONTENT_RESOURCE, UPLOAD_PATH_PREFIX, "uploads");
        override(CONTENT_RESOURCE, UPLOAD_MAX_FILES, "2");
        FileSystemContentStore store = new FileSystemContentStore();
        String first = store.getUploadPath(false);
        Path firstDirectory = home.resolve(first.substring(1));

        fill(firstDirectory, 2);
        String second = store.getUploadPath(false);

        assertNotEquals(first, second, "a directory holding the configured maximum must not be handed out again");
        assertTrue(Files.isDirectory(home.resolve(second.substring(1))), "the replacement directory must have been created");
        assertEquals(2, childDirectoryCount(home.resolve("uploads")), "exactly one further directory may have been started");
    }

    @Test
    public void anUnparsableCapFallsBackToTheCommittedDefaultRatherThanToZero() throws Exception {
        override(CONTENT_RESOURCE, UPLOAD_PATH_PREFIX, "uploads");
        // Neither a number nor blank: UtilProperties.getPropertyNumber yields 0 for this, and 0 would mean every
        // single upload started a new directory. The provider has to substitute its documented default instead.
        override(CONTENT_RESOURCE, UPLOAD_MAX_FILES, "not-a-number");
        FileSystemContentStore store = new FileSystemContentStore();
        String first = store.getUploadPath(false);
        Path firstDirectory = home.resolve(first.substring(1));

        fill(firstDirectory, COMMITTED_DEFAULT_MAX_FILES - 1);
        assertEquals(first, store.getUploadPath(false), "one entry below the default cap the directory must still be reused");

        fill(firstDirectory, 1);
        assertNotEquals(first, store.getUploadPath(false), "at the default cap the provider must start a new directory");
    }

    @Test
    public void theDelegatorAwareFormTakesTheLocationFromSystemPropertyButKeepsTheCapFromTheFile() throws Exception {
        override(CONTENT_RESOURCE, UPLOAD_PATH_PREFIX, "uploads");
        override(CONTENT_RESOURCE, UPLOAD_MAX_FILES, "2");
        FileSystemContentStore store = new FileSystemContentStore();
        Delegator delegator = mock(Delegator.class);

        // The SystemProperty lookup is stubbed at the EntityUtilProperties boundary rather than at the delegator,
        // because the real query builds a primary key from the entity model and so needs a datasource-backed
        // field-type reader that no unit test has. Stubbing the boundary still pins what this suite is about: the
        // provider asks the delegator-aware lookup for the location and the plain property file for the cap.
        try (MockedStatic<EntityUtilProperties> lookup = mockStatic(EntityUtilProperties.class)) {
            lookup.when(() -> EntityUtilProperties.getPropertyValue(CONTENT_RESOURCE, UPLOAD_PATH_PREFIX, delegator))
                    .thenReturn("delegator-uploads");

            String relative = store.getUploadPath(delegator, false);

            // The location came from the entity row, not from content.properties.
            assertTrue(relative.startsWith("/delegator-uploads/"),
                    "the SystemProperty override must decide the upload location, was: " + relative);
            // The cap did NOT: had the provider asked the delegator for it too, it would have parsed
            // "delegator-uploads" as a number, fallen back to 250, and never rolled over at 2. That asymmetry is
            // pre-existing and is reproduced on purpose so that selecting this provider cannot move content.
            fill(home.resolve(relative.substring(1)), 2);
            assertNotEquals(relative, store.getUploadPath(delegator, false),
                    "the per-directory cap must still come from content.properties");
            // Exactly two lookups, both for the location: the cap was never asked of the delegator at all.
            lookup.verify(() -> EntityUtilProperties.getPropertyValue(CONTENT_RESOURCE, UPLOAD_PATH_PREFIX, delegator), times(2));
            lookup.verifyNoMoreInteractions();
        }
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Differential parity with the behaviour this provider exists to reproduce
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void theProviderPicksTheSameUploadDirectoryAsDataResourceWorker() throws Exception {
        Path prefix = Files.createDirectories(home.resolve("parity"));
        // Set the modification times explicitly: the selection is "most recently modified", and two directories
        // created microseconds apart could otherwise share a timestamp and make the comparison a coin toss.
        age(Files.createDirectory(prefix.resolve("1000000000000")), 1_000_000_000_000L);
        age(Files.createDirectory(prefix.resolve("2000000000000")), 2_000_000_000_000L);

        String fromWorker = DataResourceWorker.getDataResourceContentUploadPath("parity", 4, false);
        String fromProvider = new FileSystemContentStore().getUploadPath("parity", 4, false);

        assertEquals("/parity/2000000000000", fromWorker, "the pre-existing behaviour is the baseline being matched");
        assertEquals(fromWorker, fromProvider, "the provider must resolve the identical relative upload path");
        assertEquals(DataResourceWorker.getDataResourceContentUploadPath("parity", 4, true),
                new FileSystemContentStore().getUploadPath("parity", 4, true),
                "the absolute form must match as well");
    }

    @Test
    public void theProviderRollsOverAtTheSamePointAsDataResourceWorker() throws Exception {
        Path prefix = Files.createDirectories(home.resolve("parity"));
        Path current = Files.createDirectory(prefix.resolve("1000000000000"));
        fill(current, 3);
        // Ageing happens after the fill, because writing into a directory refreshes its modification time and
        // two directories stamped in the same millisecond would collapse into one entry of the provider's map.
        age(current, 1_000_000_000_000L);

        String workerRollover = DataResourceWorker.getDataResourceContentUploadPath("parity", 3, false);
        Path workerDirectory = home.resolve(workerRollover.substring(1));
        fill(workerDirectory, 3);
        age(current, 1_000_000_000_000L);
        age(workerDirectory, 2_000_000_000_000L);
        String providerRollover = new FileSystemContentStore().getUploadPath("parity", 3, false);

        // Both refused to hand out a directory already holding the cap, and each started exactly one more.
        assertNotEquals("/parity/1000000000000", workerRollover, "the pre-existing behaviour rolls over at the cap");
        assertNotEquals(workerRollover, providerRollover, "the provider must roll over at the very same point");
        assertEquals(3, childDirectoryCount(prefix), "each rollover may start exactly one directory");
    }

    @Test
    public void theProviderReadsTheSameBytesAsDataResourceWorkerForAnOfbizFileDataResource() throws Exception {
        String objectInfo = "runtime/uploads/1234567890/parity.bin";
        Path target = home.resolve(objectInfo);
        Files.createDirectories(target.getParent());
        Files.write(target, PAYLOAD);
        FileSystemContentStore store = new FileSystemContentStore();

        File fromWorker = DataResourceWorker.getContentFile("OFBIZ_FILE", objectInfo, null);

        assertEquals(target.toRealPath().toString(), fromWorker.getCanonicalPath(),
                "the pre-existing resolution is the baseline being matched");
        assertArrayEquals(Files.readAllBytes(fromWorker.toPath()), store.get(objectInfo),
                "the provider must return the very bytes the pre-existing resolution reaches");
        try (InputStream stream = store.openStream(objectInfo)) {
            assertArrayEquals(PAYLOAD, stream.readAllBytes(), "and must stream the same content");
        }
        assertTrue(store.exists(objectInfo), "and must agree that the content is there");
    }

    @Test
    public void theProviderResolvesALocalFileDataResourceToTheSameFileAsDataResourceWorker() throws Exception {
        // LOCAL_FILE and LOCAL_FILE_BIN content is addressed by absolute path, which is the other branch of the
        // provider's key resolution and so the other half of the parity story.
        Path target = home.resolve("parity/local.bin");
        Files.createDirectories(target.getParent());
        Files.write(target, PAYLOAD);
        String objectInfo = target.toString();
        FileSystemContentStore store = new FileSystemContentStore();

        File fromWorker = DataResourceWorker.getContentFile("LOCAL_FILE", objectInfo, null);

        assertEquals(target.toRealPath().toString(), fromWorker.getCanonicalPath(),
                "the pre-existing resolution is the baseline being matched");
        assertArrayEquals(Files.readAllBytes(fromWorker.toPath()), store.get(objectInfo),
                "the provider must return the very bytes the pre-existing resolution reaches");
        assertTrue(store.exists(objectInfo), "and must agree that the content is there");
    }

    @Test
    public void whereTheProviderDivergesFromDataResourceWorkerItIsOnlyEverStricter() throws Exception {
        Path outside = workspace.resolve("outside.bin");
        Files.write(outside, PAYLOAD);
        String objectInfo = outside.toString();
        FileSystemContentStore store = new FileSystemContentStore();
        // Widening the local allow list to a shared parent is what makes the one divergence observable.
        override(SECURITY_RESOURCE, LOCAL_ALLOW_LIST, workspace.toString());

        // The pre-existing resolution admits any absolute path the allow list admits, including one outside the
        // OFBiz home directory.
        assertEquals(outside.toRealPath().toString(),
                DataResourceWorker.getContentFile("LOCAL_FILE", objectInfo, null).getCanonicalPath(),
                "the pre-existing resolution admits this path");

        // The provider additionally holds the OFBiz home boundary, so it refuses. Pinning the direction of the
        // divergence is the point: a stateless deployment is not harmed by a provider that reads less than the
        // Content component already could, but it would be by one that reads more.
        assertThrows(GeneralException.class, () -> store.get(objectInfo),
                "the provider must never be more permissive than the behaviour it replaces");
    }

    @Test
    public void theProviderRefusesTheSameOutOfBoundsObjectInfoAsDataResourceWorker() throws Exception {
        String objectInfo = "secrets/credentials.bin";
        Path target = home.resolve(objectInfo);
        Files.createDirectories(target.getParent());
        Files.write(target, PAYLOAD);
        FileSystemContentStore store = new FileSystemContentStore();

        // Same input, same verdict: the provider may not widen what the Content component already refuses.
        assertThrows(GeneralException.class, () -> DataResourceWorker.getContentFile("OFBIZ_FILE", objectInfo, null),
                "the pre-existing resolution refuses this object information");
        assertThrows(GeneralException.class, () -> store.get(objectInfo), "so the provider must refuse it too");
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Helpers
     * ---------------------------------------------------------------------------------------------
     */

    /** Overrides a property for the duration of one test, remembering what it held so it can be put back. */
    private void override(String resource, String name, String value) {
        propertySnapshot.computeIfAbsent(resource + "|" + name, key -> UtilProperties.getPropertyValue(resource, name));
        UtilProperties.setPropertyValueInMemory(resource, name, value);
    }

    /** Adds the given number of files to a directory, so that the per-directory cap can be reached deliberately. */
    private static void fill(Path directory, int files) throws Exception {
        int existing;
        try (var entries = Files.list(directory)) {
            existing = (int) entries.count();
        }
        for (int index = 0; index < files; index++) {
            Files.write(directory.resolve("filler-" + (existing + index) + ".bin"), PAYLOAD);
        }
    }

    /** Stamps a directory with an explicit modification time, so that "most recently modified" is unambiguous. */
    private static void age(Path directory, long modifiedAt) {
        assertTrue(directory.toFile().setLastModified(modifiedAt), "could not set the modification time of " + directory);
    }

    /** Counts the sub-directories of an upload location, so that a rollover can be shown to start exactly one. */
    private static int childDirectoryCount(Path directory) throws Exception {
        try (var children = Files.list(directory)) {
            return (int) children.filter(Files::isDirectory).count();
        }
    }

    /** Reports the POSIX mode of a path in {@code rwx------} form, without following a link that stands at it. */
    private static String modeOf(Path path) throws Exception {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS));
    }

    /** Counts the half-written temporary files left in a directory, which a completed operation must never leave. */
    private static long partialFileCount(Path directory) throws Exception {
        try (var entries = Files.list(directory)) {
            return entries.filter(entry -> entry.getFileName().toString().endsWith(".part")).count();
        }
    }

    /**
     * Renders a hostile key safely for use in a failure message.
     *
     * <p>Several of the keys below carry a control character on purpose. A failure message is written to the
     * build log, so a test about keys that could forge a log record must not forge one itself when it fails.
     *
     * @param key the key to render
     * @return the key with every control character replaced by its escape
     */
    private static String describeForAssertion(String key) {
        StringBuilder rendered = new StringBuilder(key.length() + 8);
        for (int index = 0; index < key.length(); index++) {
            char character = key.charAt(index);
            if (Character.isISOControl(character)) {
                rendered.append(String.format("\\u%04x", (int) character));
            } else {
                rendered.append(character);
            }
        }
        return "[" + rendered + "]";
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * The provider's own write path and boundary check
     * ---------------------------------------------------------------------------------------------
     *
     * Everything below asserts against the real filesystem rather than through the SPI alone, and so
     * addresses content under a second sub-tree of the upload root - STORE_KEY and the keys beside it -
     * to keep it out of the directory the shared contract writes into, because one of the expectations
     * pins the exact contents of the directory it writes to.
     *
     * Seven further expectations were written for this provider whose names are already taken by
     * ContentStoreBehaviourContract. The contract's tests are final, so they cannot be redeclared here,
     * and six of them assert nothing that a surviving expectation does not already assert:
     *
     *   putStoresExactlyTheSuppliedBytesAndGetReturnsThem      the contract pins the round trip, and
     *                                                          contentLandsOnDiskUnderneathOfbizHome-
     *                                                          AtTheKeyRelativePath pins the bytes on disk
     *   aZeroLengthPayloadIsStorableAndReadableAsEmptyContent  the contract's version is stricter: it also
     *                                                          drains openStream over the empty content
     *   writesUnderDistinctKeysDoNotInterfere                  identical to the contract's version
     *   putRejectsNullContent                                  the contract's version additionally proves
     *                                                          the refused put stored nothing
     *   openStreamHandsOutAnIndependentStreamEveryTime         the contract's version additionally proves
     *                                                          the two streams are distinct objects and
     *                                                          drains both of them in full
     *   existsDistinguishesStoredContentFromAnAbsentKey...     identical to the contract's version
     *
     * The seventh, deleteRemovesContentAndIsIdempotent, did pin something nothing else here pins - that
     * the file is gone from the filesystem and not merely from the store's view - so it is kept below
     * under a name that says exactly that.
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void putReplacesPreviousContentInFullAndLeavesNoStagingArtefactBehind() throws Exception {
        store.put(STORE_KEY, CONTENT);
        store.put(STORE_KEY, REPLACEMENT);

        assertArrayEquals(REPLACEMENT, store.get(STORE_KEY), "the replacement must not be appended to the previous content");
        // The staging file is created in the destination directory, so a leaked one would be visible here
        // and would eventually fill the storage volume of a long lived instance.
        List<String> directory = listNames(home.resolve(STORE_KEY).getParent());
        assertEquals(List.of("sample.bin"), directory, "only the published content may remain in the directory");
    }

    @Test
    public void putCreatesMissingIntermediateDirectoriesOnDemand() throws Exception {
        String nested = "runtime/uploads/contentstore/deeply/nested/on/demand.bin";

        store.put(nested, CONTENT);

        assertTrue(Files.isRegularFile(home.resolve(nested)), "the whole directory chain must be created");
    }

    @Test
    public void putReportsAStorageDirectoryLocationOccupiedByAFile() throws Exception {
        store.put(STORE_KEY, CONTENT);

        // sample.bin is a file, so it can never become the parent directory of another key. A write that
        // has nowhere to land has to fail rather than be warned about and then attempted anyway.
        IOException failure = assertThrows(IOException.class, () -> store.put(STORE_KEY + "/child.bin", CONTENT));
        assertTrue(failure.getMessage().contains("is not a directory"), "the failure must name the cause: " + failure.getMessage());
    }

    @Test
    public void getAndOpenStreamSignalAbsenceRatherThanReturningNull() {
        assertThrows(FileNotFoundException.class, () -> store.get(ABSENT_KEY), "get must signal absence");
        assertThrows(FileNotFoundException.class, () -> store.openStream(ABSENT_KEY), "openStream must signal absence");
    }

    @Test
    public void aDeleteRemovesTheFileFromDiskAndNotJustFromTheStoresView() throws Exception {
        store.put(STORE_KEY, CONTENT);

        store.delete(STORE_KEY);
        store.delete(STORE_KEY);

        assertFalse(store.exists(STORE_KEY), "the content must be gone");
        assertFalse(Files.exists(home.resolve(STORE_KEY)), "the file must be gone from disk");
    }

    @Test
    public void aDirectoryIsNeverMistakenForStoredContentAndIsNeverRemoved() throws Exception {
        Path directoryKey = home.resolve(STORE_KEY);
        Files.createDirectories(directoryKey);

        assertFalse(store.exists(STORE_KEY), "a directory holds no stored content");
        assertThrows(FileNotFoundException.class, () -> store.get(STORE_KEY), "get must agree with exists");
        assertThrows(FileNotFoundException.class, () -> store.openStream(STORE_KEY), "openStream must agree with exists");
        assertThrows(GeneralException.class, () -> store.delete(STORE_KEY), "delete must refuse a directory");
        assertTrue(Files.isDirectory(directoryKey), "the refused delete must leave the directory in place");
    }

    @Test
    public void everyOperationRejectsAKeyThatIsNullOrEmpty() {
        for (String unusable : new String[] {null, ""}) {
            assertThrows(GeneralException.class, () -> store.put(unusable, CONTENT), "put must reject the key");
            assertThrows(GeneralException.class, () -> store.get(unusable), "get must reject the key");
            assertThrows(GeneralException.class, () -> store.openStream(unusable), "openStream must reject the key");
            assertThrows(GeneralException.class, () -> store.exists(unusable), "exists must reject the key");
            assertThrows(GeneralException.class, () -> store.delete(unusable), "delete must reject the key");
        }
    }

    @Test
    public void aLocationThatCannotBeResolvedIsReportedRatherThanDereferenced() {
        // A malformed component:// location resolves to nothing at all. Reporting it keeps the failure
        // actionable instead of surfacing a NullPointerException from deep inside the provider.
        String malformed = "component://no-such-component-is-loaded-here/uploads/sample.bin";

        assertThrows(GeneralException.class, () -> store.exists(malformed), "an unresolvable location must be reported");
        assertThrows(GeneralException.class, () -> store.put(malformed, CONTENT), "an unresolvable location must be reported");
    }

    @Test
    public void keysOutsideTheAllowedContentLocationsAreRejected() {
        assertThrows(GeneralException.class, () -> store.put("not-an-allowed-location/sample.bin", CONTENT),
                "a key outside the allow list must be refused");
        assertThrows(GeneralException.class, () -> store.put("runtime/../../escaped.bin", CONTENT),
                "a traversing key must be refused");
        assertFalse(Files.exists(home.getParent().resolve("escaped.bin")), "nothing may be written outside the home directory");
    }

    @Test
    public void anUnsetHomeDirectoryIsReportedRatherThanDereferenced() {
        System.clearProperty(OFBIZ_HOME);

        assertThrows(GeneralException.class, () -> store.exists(STORE_KEY), "an unusable home directory must be reported");
    }

    @Test
    public void aPartialChannelWriteIsRepeatedUntilTheWholeContentHasBeenAccepted() throws Exception {
        byte[] content = "0123456789".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream accepted = new ByteArrayOutputStream();
        // FileChannel.write is only contracted to transfer some of the remaining bytes, and a channel
        // that takes three at a time is entirely within its rights. A single write call would have
        // left "0123456789" as "012" and the caller would then have published that truncation.
        FileChannel channel = mock(FileChannel.class);
        when(channel.write(any(ByteBuffer.class))).thenAnswer(invocation -> {
            ByteBuffer buffer = invocation.getArgument(0);
            int chunk = Math.min(3, buffer.remaining());
            byte[] taken = new byte[chunk];
            buffer.get(taken);
            accepted.write(taken);
            return chunk;
        });

        FileSystemContentStore.writeFully(channel, content, STORE_KEY);

        assertArrayEquals(content, accepted.toByteArray(), "every byte of the content must reach the channel");
        verify(channel, times(4)).write(any(ByteBuffer.class));
    }

    @Test
    public void aChannelThatStopsAcceptingBytesIsReportedRatherThanRetriedForever() throws Exception {
        FileChannel channel = mock(FileChannel.class);
        when(channel.write(any(ByteBuffer.class))).thenReturn(0);

        IOException failure = assertThrows(IOException.class, () -> FileSystemContentStore.writeFully(channel, CONTENT, STORE_KEY),
                "a stalled device must be reported instead of spinning forever");
        assertTrue(failure.getMessage().contains("stopped making progress"),
                "the failure must name the cause: " + failure.getMessage());
    }

    @Test
    public void emptyContentNeverTouchesTheChannelAtAll() throws Exception {
        FileChannel channel = mock(FileChannel.class);

        FileSystemContentStore.writeFully(channel, new byte[0], STORE_KEY);

        verify(channel, never()).write(any(ByteBuffer.class));
    }

    @Test
    public void theBoundaryCheckRefusesAPathThatEscapesTheRootThroughASymbolicLink() throws Exception {
        File throughTheLink = plantEscapingSymbolicLink();

        // Lexically the path still sits inside the root, which is exactly why a check that accepted a
        // path as soon as EITHER test passed would have let this through.
        assertTrue(throughTheLink.toPath().toAbsolutePath().normalize().startsWith(home),
                "the lexical path has to stay inside the root for this test to be meaningful");
        assertFalse(throughTheLink.getCanonicalPath().startsWith(home.toRealPath().toString()),
                "the real path has to leave the root for this test to be meaningful");

        assertThrows(GeneralException.class, () -> FileSystemContentStore.checkFileBoundary(throughTheLink, home.toString()),
                "the provider boundary itself must refuse a symbolic-link escape");
    }

    @Test
    public void theBoundaryCheckStillAcceptsTheRootAndEveryLocationInsideIt() throws Exception {
        Path inside = home.resolve(STORE_KEY);
        Files.createDirectories(inside.getParent());
        Files.write(inside, CONTENT);
        String root = home.toString();

        assertDoesNotThrow(() -> FileSystemContentStore.checkFileBoundary(inside.toFile(), root),
                "stored content inside the root stays reachable");
        assertDoesNotThrow(() -> FileSystemContentStore.checkFileBoundary(home.toFile(), root),
                "the root itself is inside the root");
        // A write has to be able to create a location that does not exist yet.
        assertDoesNotThrow(() -> FileSystemContentStore.checkFileBoundary(
                home.resolve("runtime/uploads/not/created/yet.bin").toFile(), root),
                "a location that does not exist yet is still inside the root");
    }

    @Test
    public void theBoundaryCheckRefusesATraversalOutOfTheRoot() {
        File traversed = home.resolve("runtime/../../elsewhere.bin").toFile();

        assertThrows(GeneralException.class, () -> FileSystemContentStore.checkFileBoundary(traversed, home.toString()),
                "a traversal out of the root must be refused");
    }

    @Test
    public void everyOperationRefusesAKeyThatLeavesTheStorageRootThroughASymbolicLink() throws Exception {
        plantEscapingSymbolicLink();
        Path victim = outside.resolve("secret.bin");
        Files.write(victim, REPLACEMENT);

        assertThrows(GeneralException.class, () -> store.put(ESCAPING_KEY, CONTENT), "put must refuse the escape");
        assertThrows(GeneralException.class, () -> store.get(ESCAPING_KEY), "get must refuse the escape");
        assertThrows(GeneralException.class, () -> store.openStream(ESCAPING_KEY), "openStream must refuse the escape");
        assertThrows(GeneralException.class, () -> store.exists(ESCAPING_KEY), "exists must refuse the escape");
        assertThrows(GeneralException.class, () -> store.delete(ESCAPING_KEY), "delete must refuse the escape");
        assertArrayEquals(REPLACEMENT, Files.readAllBytes(victim),
                "content outside the storage root may be neither overwritten nor removed");
    }

    /**
     * Plants a symbolic link inside the storage root that points at the separate outside directory.
     *
     * @return the file addressed through the link, whose lexical path is inside the root while its real
     *     path is not
     * @throws IOException if the link cannot be created
     */
    private File plantEscapingSymbolicLink() throws IOException {
        Path linkParent = home.resolve(LINK_PARENT);
        Files.createDirectories(linkParent);
        Files.createSymbolicLink(linkParent.resolve("escape"), outside);
        return home.resolve(ESCAPING_KEY).toFile();
    }

    private static List<String> listNames(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(entry -> entry.getFileName().toString()).sorted().toList();
        }
    }
}
