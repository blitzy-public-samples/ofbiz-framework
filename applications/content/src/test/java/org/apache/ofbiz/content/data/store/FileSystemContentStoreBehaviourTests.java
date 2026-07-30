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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.ofbiz.base.util.GeneralException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Executes the whole {@link ContentStoreBehaviourContract} against the PRODUCTION
 * {@link FileSystemContentStore}, and adds the expectations that are specific to it.
 *
 * <p>{@link TempDirContentStoreBehaviourTests} runs the same contract against a reference implementation, which
 * proves the contract is satisfiable; this class proves the shipped provider actually satisfies it. That
 * distinction matters because the provider does considerably more than the reference does - it stages writes and
 * moves them into place, it applies the deployment's file allow list, and it confines every resolved location
 * canonically - and each of those is a place where a provider can pass a compiler and still misbehave.
 *
 * <p>The provider resolves every key against {@code ofbiz.home}, so each test runs against a throwaway
 * deployment home: {@code ofbiz.home} is pointed at a fresh {@link TempDir} for the duration of the test and
 * restored afterwards, and keys are prefixed with {@code runtime/uploads/} - {@code runtime/} because that is
 * one of the subtrees the shipped {@code content.data.ofbiz.file.allowed.paths} permits, and {@code uploads/}
 * because a write or a removal is additionally confined to the {@code content.upload.path.prefix} tree. Nothing
 * outside the temporary directory is read or written, and both the deployment's allow list and the provider's
 * upload confinement are exercised exactly as configured rather than being widened to accommodate the test.
 *
 * <p>Three provider-specific expectations are asserted beyond the shared contract, each corresponding to a way
 * a path-backed provider silently corrupts or leaks:
 * <ul>
 * <li>a write leaves no staging artefact behind, whether it succeeded or failed;</li>
 * <li>a key that would resolve outside the deployment tree, or outside the allowed subtrees within it, is
 *     refused rather than resolved;</li>
 * <li>a filesystem answer that is NOT "no such file" is propagated instead of being reported as absence, so a
 *     store that cannot be read is never mistaken for a store that is empty.</li>
 * </ul>
 */
public final class FileSystemContentStoreBehaviourTests extends ContentStoreBehaviourContract {

    /** The subtree keys are placed under, because the shipped allow list permits {@code runtime/}. */
    private static final String ALLOWED_SUBTREE = "runtime/";

    /** The remainder of the committed {@code content.upload.path.prefix}, which is the only tree writes may land in. */
    private static final String UPLOAD_SUBTREE = "uploads/";

    private static final String OFBIZ_HOME = "ofbiz.home";
    private static final String STAGING_PREFIX = ".ofbiz-content-";
    private static final byte[] PAYLOAD = "stored by the production provider".getBytes(StandardCharsets.UTF_8);

    /** A throwaway deployment home; JUnit creates it per test and deletes it recursively afterwards. */
    @TempDir
    private Path deploymentHome;

    private String previousOfbizHome;

    @BeforeEach
    public void pointOfbizHomeAtTheThrowawayDeployment() {
        previousOfbizHome = System.getProperty(OFBIZ_HOME);
        System.setProperty(OFBIZ_HOME, deploymentHome.toAbsolutePath().toString());
    }

    @AfterEach
    public void restoreOfbizHome() {
        // Restored rather than left set, so this class cannot influence any other test in the same JVM.
        if (previousOfbizHome == null) {
            System.clearProperty(OFBIZ_HOME);
        } else {
            System.setProperty(OFBIZ_HOME, previousOfbizHome);
        }
    }

    @Override
    protected ContentStore newEmptyContentStore() {
        // The deployment home is fresh per test, so a newly constructed provider addresses an empty store.
        return new FileSystemContentStore();
    }

    @Override
    protected String storageKey(String logicalKey) {
        return ALLOWED_SUBTREE + logicalKey;
    }

    /**
     * Places the shared contract's keys inside the tree the provider is allowed to MODIFY.
     *
     * <p>The provider applies two independent restrictions, and a key has to satisfy both. Every key is
     * resolved relative to {@code ofbiz.home} and checked against the deployment's
     * {@code content.data.ofbiz.file.allowed.paths} allow list, which {@link #storageKey(String)}'s
     * {@code runtime/} satisfies; a write or a removal is then additionally refused unless it lands inside
     * {@code content.upload.path.prefix}, which is {@code runtime/uploads}. Composing this prefix with that
     * one addresses {@code runtime/uploads/...}, so the contract exercises the provider under both
     * restrictions exactly as deployed, rather than either being widened for the test.
     *
     * <p>These are the same keys this class's own tests use, which is deliberate: the shared contract and
     * the provider-specific expectations must be writing to one key space, or a divergence between them
     * would be invisible.
     *
     * @return the upload subtree, ending in a separator
     */
    @Override
    protected String keyPrefix() {
        return UPLOAD_SUBTREE;
    }

    @Test
    public void contentLandsOnDiskUnderTheDeploymentHomeAtTheKeyRelativePath() throws Exception {
        ContentStore store = newEmptyContentStore();
        String key = storageKey("uploads/party/logo.png");

        store.put(key, PAYLOAD);

        // Read back through the filesystem rather than through the provider: that is what shows the provider
        // really wrote the bytes, and wrote them where the key resolves rather than somewhere else.
        Path expected = deploymentHome.resolve(key);
        assertTrue(Files.isRegularFile(expected), "the provider must have created " + expected);
        assertArrayEquals(PAYLOAD, Files.readAllBytes(expected), "the bytes on disk");
    }

    @Test
    public void aWriteLeavesNoStagingArtefactBehindWhetherItSucceededOrFailed() throws Exception {
        ContentStore store = newEmptyContentStore();
        String key = storageKey("uploads/staging/content.bin");

        store.put(key, PAYLOAD);
        assertEquals(List.of("content.bin"), namesIn(deploymentHome.resolve(key).getParent()),
                "a successful write must leave only the content behind");

        // A staging file that outlived a failed write would accumulate in the upload location for ever, and
        // would be indistinguishable from content to anything that lists the directory.
        assertThrows(IOException.class, () ->
                store.put(key, new ByteArrayInputStream(PAYLOAD), PAYLOAD.length + 1L), "a stream that ends early must fail");
        assertEquals(List.of("content.bin"), namesIn(deploymentHome.resolve(key).getParent()),
                "a failed write must leave neither a staging file nor a damaged target");
        assertArrayEquals(PAYLOAD, store.get(key), "a failed write must leave the previous content intact");
    }

    @Test
    public void aKeyResolvingOutsideTheAllowedDeploymentSubtreesIsRefused() throws Exception {
        ContentStore store = newEmptyContentStore();
        Path outside = deploymentHome.getParent().resolve("escaped-" + deploymentHome.getFileName() + ".bin");

        // Storage keys reach a path-backed provider from request data, so traversal and absolute escapes have to
        // be refused rather than resolved - and so does a key that stays inside the deployment but outside the
        // subtrees the deployment's own allow list names.
        for (String hostile : new String[] {
                ALLOWED_SUBTREE + "../../escaped.bin",
                "../escaped.bin",
                "/etc/ofbiz-escaped.bin",
                "contract/outside-an-allowed-subtree.bin"}) {
            assertThrows(GeneralException.class, () -> store.put(hostile, PAYLOAD), "put must refuse the key " + hostile);
            assertThrows(GeneralException.class, () -> store.exists(hostile), "exists must refuse the key " + hostile);
            assertThrows(GeneralException.class, () -> store.delete(hostile), "delete must refuse the key " + hostile);
        }
        assertFalse(Files.exists(outside), "nothing may be written outside the deployment home");
    }

    @Test
    public void aFilesystemAnswerThatIsNotNoSuchFileIsPropagatedRatherThanReportedAsAbsence() throws Exception {
        ContentStore store = newEmptyContentStore();
        String blocking = storageKey("uploads/blocking.bin");
        store.put(blocking, PAYLOAD);

        // A key whose ancestor is a regular file cannot be a file: the platform answers "not a directory", which
        // is emphatically not "no such file". Reporting it as absence would make an unreadable store look empty,
        // and an unreadable store that looks empty is how content silently starts being re-created from nothing.
        String beneathAFile = blocking + "/beneath-a-file.bin";
        IOException probed = assertThrows(IOException.class, () -> store.exists(beneathAFile),
                "an answer other than no-such-file must be propagated by exists");
        assertFalse(probed instanceof FileNotFoundException,
                "the propagated failure must not be the absence signal: " + probed.getClass().getName());
        assertThrows(IOException.class, () -> store.size(beneathAFile), "and by size");
        assertThrows(IOException.class, () -> store.openStream(beneathAFile), "and by openStream");

        // Absence itself still resolves to the documented signal, so the narrowing has not simply turned every
        // answer into a failure.
        assertFalse(store.exists(storageKey("uploads/genuinely-absent.bin")), "a genuinely absent key must still be absent");
    }

    @Test
    public void theUploadLocationIsCreatedUnderTheDeploymentHomeAndReusedWhileItHasRoom() throws Exception {
        FileSystemContentStore store = new FileSystemContentStore();

        String relative = store.uploadPath(null, false);
        String again = store.uploadPath(null, false);

        // The relative form is what OFBIZ_FILE content persists, and it must stay under the configured prefix.
        assertTrue(relative.startsWith("/runtime/uploads/"), "the upload location must sit under the configured prefix: " + relative);
        assertEquals(relative, again, "a location with room must be reused rather than replaced on every upload");
        Path created = deploymentHome.resolve(relative.substring(1));
        assertTrue(Files.isDirectory(created), "the selected upload location must exist: " + created);

        // The absolute form is what LOCAL_FILE content persists, and it must name the same directory.
        String absolute = store.uploadPath(null, true);
        assertEquals(created.toAbsolutePath().toString().replace('\\', '/'), absolute,
                "the absolute form must name the same directory as the relative one");
    }

    /**
     * The sorted names directly inside a directory.
     *
     * @param directory the directory to list
     * @return every entry name, sorted, so the comparison is order-independent
     * @throws IOException if the directory cannot be read
     */
    private static List<String> namesIn(Path directory) throws IOException {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                names.add(entry.getFileName().toString());
            }
        }
        names.sort(String::compareTo);
        for (String name : names) {
            assertFalse(name.startsWith(STAGING_PREFIX), "a staging artefact was left behind: " + name);
        }
        return names;
    }
}
