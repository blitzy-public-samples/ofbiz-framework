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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.apache.ofbiz.base.util.GeneralException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks that {@link ContentStoreBehaviourContract} is SATISFIABLE - that the documented SPI can be honoured
 * in full by an implementation written from nothing but its javadoc.
 *
 * <p><strong>This suite is not coverage of either shipped provider, and must never be read as such.</strong>
 * The provider it runs, {@link DirectoryContentStore}, is a reference implementation that lives only in the
 * test tree and is deployed nowhere. Behaviour coverage of the real providers lives in
 * {@link FileSystemContentStoreTests} and {@link S3ContentStoreTests}, which subclass the same contract and
 * execute {@link FileSystemContentStore} and {@link S3ContentStore} themselves; provider selection is covered
 * by {@link ContentStoreProviderSelectionTests}.
 *
 * <p>What this suite is for is the one thing those three cannot establish. An SPI whose javadoc demands a
 * combination of behaviours that no implementation can actually honour - a null-key rule that contradicts a
 * stream-ownership rule, say - is a defect in the contract rather than in any provider, and it stays invisible
 * for as long as the only implementations are the ones the contract was reverse-engineered from. Writing a
 * fresh implementation straight from the prose and passing the same thirteen assertions is what rules that out,
 * and it also keeps the contract honest as it grows: a new clause that cannot be met from the javadoc alone
 * fails here first.
 *
 * <p>Because it is a reference implementation rather than a mock, every assertion still moves real bytes
 * through a real filesystem - written, read back, streamed twice and deleted - so a pass says something about
 * storage semantics and not about scaffolding. The two expectations added on top of the contract are the ones
 * specific to any root-relative, path-backed provider: content lands at the key-relative path, and a key that
 * would escape the configured root is refused.
 */
public final class ContentStoreContractSatisfiabilityTests extends ContentStoreBehaviourContract {

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
     * writes in both the convenience and the known-length streaming form, {@link FileNotFoundException} for an
     * absent key from every reader including the length probe, independent streams, an idempotent delete, an
     * idempotent close, and {@link GeneralException} for a key that is null, empty or would escape the root.
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
        public void put(String key, InputStream content, long length) throws GeneralException, IOException {
            Path target = resolve(key);
            if (content == null) {
                throw new GeneralException("content stream must not be null for storage key [" + key + "]");
            }
            if (length < 0L) {
                throw new GeneralException("declared length " + length + " is negative for storage key [" + key + "]");
            }
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // Staged then moved into place, so a stream that ends early leaves no short entry behind - which is
            // what the shared contract asserts, and what a provider writing straight to the target could not do.
            Path staging = Files.createTempFile(parent == null ? root : parent, ".staging-", ".tmp");
            boolean placed = false;
            try {
                try (OutputStream sink = Files.newOutputStream(staging)) {
                    copyExactly(content, sink, length, key);
                }
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
                placed = true;
            } finally {
                if (!placed) {
                    Files.deleteIfExists(staging);
                }
            }
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
        public long size(String key) throws GeneralException, IOException {
            return Files.size(existing(key));
        }

        @Override
        public boolean exists(String key) throws GeneralException, IOException {
            return Files.isRegularFile(resolve(key));
        }

        @Override
        public void delete(String key) throws GeneralException, IOException {
            Files.deleteIfExists(resolve(key));
        }

        @Override
        public void close() throws GeneralException, IOException {
            // A path-backed provider holds nothing beyond the per-call streams the caller already closes, so
            // close is a documented no-op here - and therefore trivially idempotent.
        }

        /**
         * Copies exactly {@code length} bytes, failing rather than writing a short entry if the source ends first.
         *
         * @param source the stream to read from
         * @param sink the stream to write to
         * @param length the exact number of bytes to move
         * @param key the storage key, for the failure message
         * @throws IOException if the source ends before {@code length} bytes have been read
         */
        private static void copyExactly(InputStream source, OutputStream sink, long length, String key) throws IOException {
            byte[] buffer = new byte[8192];
            long remaining = length;
            while (remaining > 0L) {
                int wanted = (int) Math.min(buffer.length, remaining);
                int read = source.read(buffer, 0, wanted);
                if (read < 0) {
                    throw new IOException("content under key [" + key + "] ended after " + (length - remaining)
                            + " of the declared " + length + " bytes");
                }
                sink.write(buffer, 0, read);
                remaining -= read;
            }
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
