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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.UtilValidate;

/**
 * The filesystem content-storage provider, which holds content in a directory tree.
 *
 * <p>The root is {@code runtime/contentstore} inside the deployment, and a key - the content's
 * {@code ofbiz.home}-relative path - is resolved beneath it. It becomes a fleet-wide store when that
 * one directory is a shared mount, which is the configuration in which it makes an instance
 * replaceable; on purely local storage it holds a second copy of the content and is what lets the
 * store seam be exercised without an object store. Either way the content component keeps writing and
 * reading file-backed content where it always did, because the local copy is always preferred.
 *
 * <p>A key is resolved against the root and then confined to it, both lexically and after
 * normalisation, so no key can address a path outside the root. Confinement is checked on the
 * normalised absolute path rather than on the key alone, because the key grammar
 * ({@link ContentStoreFactory#requireUsableKey}) rules out relative segments but not a symbolic link
 * planted inside the tree.
 *
 * <p>Replacement is atomic, as {@link ContentStore#put} requires: content is written to a staging file
 * in the destination's own directory and then moved onto the destination with
 * {@link StandardCopyOption#ATOMIC_MOVE}. A reader therefore sees either the previous file or the new
 * one. Staging in the destination directory rather than in a temporary directory is what makes the
 * move a rename within one filesystem, which is the only way it can be atomic.
 *
 * <p>Thread safe: it holds only the immutable storage root.
 */
public final class FileSystemContentStore implements ContentStore {

    /** Suffix of the staging file a put writes before moving it onto the destination. */
    private static final String STAGING_SUFFIX = ".ofbizstore";

    /** The largest object {@link #get} will read into memory. */
    private static final long MAX_IN_MEMORY_OBJECT = 16L * 1024L * 1024L;

    private final Path root;

    /**
     * Creates a provider rooted at the given directory.
     *
     * @param directory the absolute storage root
     * @throws GeneralException if no root was given
     */
    FileSystemContentStore(String directory) throws GeneralException {
        if (UtilValidate.isEmpty(directory)) {
            throw new GeneralException("The filesystem content store has no storage root");
        }
        this.root = Paths.get(directory).toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, InputStream content, long length) throws GeneralException, IOException {
        if (content == null || length < 0L) {
            throw new IOException("Content of a known, non-negative length is required to store [" + key + "]");
        }
        Path target = resolve(key);
        Path directory = target.getParent();
        Files.createDirectories(directory);
        Path staged = Files.createTempFile(directory, target.getFileName().toString(), STAGING_SUFFIX);
        try {
            long copied = Files.copy(new BoundedStream(content, length), staged,
                    StandardCopyOption.REPLACE_EXISTING);
            if (copied != length) {
                throw new IOException("Content of [" + key + "] ended after " + copied + " of " + length + " bytes");
            }
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    @Override
    public byte[] get(String key) throws GeneralException, IOException {
        Path target = resolve(key);
        try {
            long size = Files.size(target);
            if (size > MAX_IN_MEMORY_OBJECT) {
                throw new IOException("Content of [" + key + "] holds " + size + " bytes, more than the "
                        + MAX_IN_MEMORY_OBJECT + " bytes that may be read into memory; stream it instead");
            }
            return Files.readAllBytes(target);
        } catch (NoSuchFileException absent) {
            throw absence(key, absent);
        }
    }

    @Override
    public InputStream openStream(String key) throws GeneralException, IOException {
        Path target = resolve(key);
        try {
            return Files.newInputStream(target);
        } catch (NoSuchFileException absent) {
            throw absence(key, absent);
        }
    }

    @Override
    public boolean exists(String key) throws GeneralException {
        return Files.isRegularFile(resolve(key));
    }

    @Override
    public void delete(String key) throws GeneralException, IOException {
        Files.deleteIfExists(resolve(key));
    }

    /**
     * Resolves a storage key to the one path inside this provider's root that it names.
     *
     * @param key the storage key
     * @return the normalised absolute path, guaranteed to be inside the storage root
     * @throws GeneralException if the key breaks the key grammar or would escape the root
     */
    private Path resolve(String key) throws GeneralException {
        ContentStoreFactory.requireUsableKey(key);
        Path resolved = root.resolve(key).normalize().toAbsolutePath();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new GeneralException("Unusable content store key [" + key + "]: it resolves outside the store");
        }
        return resolved;
    }

    /**
     * Reports an object this store does not hold, as the contract's one absence signal.
     *
     * @param key the storage key
     * @param cause what the filesystem reported
     * @return the exception to throw
     */
    private static FileNotFoundException absence(String key, IOException cause) {
        FileNotFoundException absent = new FileNotFoundException("The content store holds no [" + key + "]");
        absent.initCause(cause);
        return absent;
    }

    /**
     * Reads at most the declared number of bytes from the content being stored.
     *
     * <p>A caller declares how many bytes it is storing, and this is what holds it to that: a stream
     * that turns out to be longer cannot make the staging file grow past the declared length, and one
     * that is shorter is caught by the byte count {@link #put} compares afterwards.
     */
    private static final class BoundedStream extends InputStream {

        private final InputStream source;
        private long remaining;

        private BoundedStream(InputStream source, long limit) {
            this.source = source;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0L) {
                return -1;
            }
            int read = source.read();
            if (read >= 0) {
                remaining--;
            }
            return read;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0L) {
                return -1;
            }
            int read = source.read(buffer, offset, (int) Math.min(length, remaining));
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }
    }
}
