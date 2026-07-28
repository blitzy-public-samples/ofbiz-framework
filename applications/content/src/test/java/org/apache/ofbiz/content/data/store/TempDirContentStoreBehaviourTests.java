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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.ofbiz.base.util.GeneralException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Executes the whole {@link ContentStoreBehaviourContract} against a provider that performs genuine
 * filesystem I/O, and adds the expectations that are specific to a root-relative, path-backed provider.
 *
 * <p>The provider used here, {@link DirectoryContentStore}, is a REFERENCE implementation written directly
 * from the {@link ContentStore} javadoc and living only in the test tree. It exists for two reasons. First, it
 * turns the contract from prose into something executable today: the production {@code FileSystemContentStore}
 * and {@code S3ContentStore} are later deliverables, and a contract that cannot be run until they arrive would
 * not protect the SPI in the meantime. Second, and more importantly, it proves the documented contract is
 * actually SATISFIABLE and self-consistent - an SPI whose javadoc demands a combination no implementation can
 * honour is a defect that only shows up when the first provider is written.
 *
 * <p>Every assertion runs against real bytes on a real filesystem: files are written, read back, streamed twice
 * and deleted. Nothing is stubbed and no {@code ContentStore} mock is involved, so a pass here says something
 * about storage behaviour rather than about test scaffolding.
 *
 * <p>When the production providers land, each subclasses {@link ContentStoreBehaviourContract} in exactly the
 * way this class does - the S3 one supplying a provider whose SDK client boundary is mocked - and inherits this
 * identical suite, which is what makes provider parity structural rather than a matter of remembering to
 * re-assert the same things twice.
 */
public final class TempDirContentStoreBehaviourTests extends ContentStoreBehaviourContract {

    private static final byte[] PAYLOAD = "stored on a real filesystem".getBytes(StandardCharsets.UTF_8);

    /** JUnit creates this per test and deletes it recursively afterwards, so no artefact outlives a test. */
    @TempDir
    private Path tempRoot;

    private int storeSequence;

    @Override
    protected ContentStore newEmptyContentStore() throws IOException {
        // A fresh, empty root per call, so even a test that asks for two stores gets two isolated ones.
        return new DirectoryContentStore(Files.createDirectory(tempRoot.resolve("store-" + storeSequence++)));
    }

    @Test
    public void storedContentLandsOnDiskAtTheKeyRelativePath() throws Exception {
        Path root = Files.createDirectory(tempRoot.resolve("visible"));
        ContentStore store = new DirectoryContentStore(root);

        store.put("uploads/party/logo.png", PAYLOAD);

        // Reading the bytes back through the filesystem rather than through the provider is what shows the
        // provider really wrote them, and wrote them where the key says.
        Path expected = root.resolve("uploads/party/logo.png");
        assertTrue(Files.isRegularFile(expected), "the provider must have created " + expected);
        assertArrayEquals(PAYLOAD, Files.readAllBytes(expected), "the bytes on disk");
    }

    @Test
    public void keysThatWouldEscapeTheConfiguredRootAreRejected() throws Exception {
        Path root = Files.createDirectory(tempRoot.resolve("confined"));
        ContentStore store = new DirectoryContentStore(root);
        Path outside = tempRoot.resolve("escaped.bin");

        // Content keys reach a path-backed provider from request data, so traversal has to be refused rather than
        // resolved. This expectation is specific to a provider with a configured root: an S3 object key has none,
        // which is why it lives here and not in the shared contract.
        for (String hostile : new String[] {"../escaped.bin", "uploads/../../escaped.bin", "/absolute/escaped.bin"}) {
            assertThrows(GeneralException.class, () -> store.put(hostile, PAYLOAD), "put must refuse the escaping key " + hostile);
            assertThrows(GeneralException.class, () -> store.exists(hostile), "exists must refuse the escaping key " + hostile);
        }
        assertFalse(Files.exists(outside), "nothing may be written outside the configured root");
    }

    /**
     * Minimal root-relative {@link ContentStore} implemented straight from the SPI javadoc: replace-in-full
     * writes, {@link FileNotFoundException} for an absent key from both readers, independent streams, an
     * idempotent delete, and {@link GeneralException} for a key that is null, empty or would escape the root.
     */
    private static final class DirectoryContentStore implements ContentStore {

        private final Path root;

        DirectoryContentStore(Path root) {
            this.root = root.toAbsolutePath().normalize();
        }

        @Override
        public void put(String key, byte[] data) throws GeneralException, IOException {
            Path target = resolve(key);
            if (data == null) {
                throw new GeneralException("content must not be null for storage key [" + key + "]");
            }
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // CREATE + TRUNCATE_EXISTING semantics: an existing entry is replaced in full, never appended to.
            Files.write(target, data);
        }

        @Override
        public byte[] get(String key) throws GeneralException, IOException {
            return Files.readAllBytes(existing(key));
        }

        @Override
        public InputStream openStream(String key) throws GeneralException, IOException {
            return Files.newInputStream(existing(key));
        }

        @Override
        public boolean exists(String key) throws GeneralException, IOException {
            return Files.isRegularFile(resolve(key));
        }

        @Override
        public void delete(String key) throws GeneralException, IOException {
            Files.deleteIfExists(resolve(key));
        }

        /** Resolves a key that must already hold content, signalling absence the way the SPI documents. */
        private Path existing(String key) throws GeneralException, IOException {
            Path target = resolve(key);
            if (!Files.isRegularFile(target)) {
                throw new FileNotFoundException("no content stored under key [" + key + "]");
            }
            return target;
        }

        private Path resolve(String key) throws GeneralException {
            if (key == null || key.isEmpty()) {
                throw new GeneralException("storage key must be neither null nor empty");
            }
            Path resolved = root.resolve(key).normalize();
            if (!resolved.startsWith(root) || resolved.equals(root)) {
                throw new GeneralException("storage key [" + key + "] would escape the configured root");
            }
            return resolved;
        }
    }
}
