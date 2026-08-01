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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.UtilValidate;

/**
 * The filesystem content-storage provider, which holds content in a directory tree rooted at
 * {@code ofbiz.home}.
 *
 * <p>This is the provider that preserves the deployment's existing behaviour: a storage key is an
 * {@code ofbiz.home}-relative path, so a {@code DataResource} whose content already lives under
 * {@code ofbiz.home} - a {@code runtime/uploads} upload, an {@code OFBIZ_FILE} resource - is
 * stored at exactly the location it already occupies. It is selected by
 * {@code content.store.provider=filesystem} and is intended for a deployment whose instances
 * share that tree through a network filesystem, which is what makes locally written content
 * reachable from every instance.
 *
 * <p><strong>Confinement.</strong> Every key is resolved and then normalised, and a key that
 * escapes {@code ofbiz.home} is refused rather than resolved elsewhere. Keys carrying a control
 * character, an absolute path, a {@code ..} component or a Windows drive prefix are refused
 * before any filesystem call is made.
 *
 * <p><strong>Links are never followed.</strong> Attributes are read and content is opened with
 * {@link LinkOption#NOFOLLOW_LINKS}, and anything that is not a regular file - a symbolic link, a
 * directory, a device - is refused. A local actor who can create a link inside the tree therefore
 * cannot make this provider read or overwrite a file outside it, and cannot substitute a link for
 * a regular file between the moment content is examined and the moment it is transferred.
 *
 * <p><strong>Writes are all-or-nothing and owner-only.</strong> Content is written to a temporary
 * file created with {@code rw-------} in the destination's own directory and then moved onto the
 * destination atomically, so a concurrent reader sees either the whole previous content or the
 * whole new content, and a failed write leaves the previous content intact. Directories this
 * provider creates are created {@code rwx------}.
 *
 * <p>Thread safe: it holds only the immutable storage root.
 */
public final class FileSystemContentStore implements ContentStore {

    private static final String MODULE = FileSystemContentStore.class.getName();

    /** Owner-only permissions for content this provider writes. */
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

    /** Owner-only permissions for directories this provider creates. */
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");

    /** Prefix of the temporary file a write is staged through, in the destination's own directory. */
    private static final String STAGING_PREFIX = ".ofbiz-content-store-";

    private final Path root;

    /**
     * Constructs the provider around the {@code ofbiz.home} tree.
     *
     * @throws GeneralException if {@code ofbiz.home} is not set, which means the provider has no
     *     storage root and cannot resolve any key
     */
    public FileSystemContentStore() throws GeneralException {
        String home = System.getProperty("ofbiz.home");
        if (UtilValidate.isEmpty(home)) {
            throw new GeneralException("The filesystem content store cannot be used because the ofbiz.home system"
                    + " property is not set, so it has no storage root to resolve a key against");
        }
        this.root = Paths.get(home).toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, byte[] data) throws GeneralException, IOException {
        if (data == null) {
            throw new GeneralException("Cannot store null content for content store key [" + key + "]");
        }
        Path target = resolve(key);
        refuseIrregularExistingEntry(target);
        Path parent = target.getParent();
        if (parent != null) {
            createDirectories(parent);
        }
        Path staging = createStagingFile(parent == null ? root : parent);
        try {
            Files.write(staging, data);
            move(staging, target);
            staging = null;
        } finally {
            if (staging != null) {
                Files.deleteIfExists(staging);
            }
        }
    }

    @Override
    public byte[] get(String key) throws GeneralException, IOException {
        Path target = resolve(key);
        requireRegularFile(target);
        return Files.readAllBytes(target);
    }

    @Override
    public InputStream openStream(String key) throws GeneralException, IOException {
        Path target = resolve(key);
        requireRegularFile(target);
        return Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public boolean exists(String key) throws GeneralException, IOException {
        Path target = resolve(key);
        BasicFileAttributes attributes = readAttributes(target);
        return attributes != null && attributes.isRegularFile();
    }

    @Override
    public void delete(String key) throws GeneralException, IOException {
        Path target = resolve(key);
        BasicFileAttributes attributes = readAttributes(target);
        if (attributes == null) {
            // Idempotent: nothing stored under this key, so the removal has already happened.
            return;
        }
        if (!attributes.isRegularFile()) {
            throw new GeneralException("The filesystem content store refuses to remove ["
                    + relative(target) + "] because it is not a regular file");
        }
        Files.deleteIfExists(target);
    }

    /**
     * Resolves a storage key to the one path inside this provider's root that it names.
     *
     * @param key the provider-relative storage key
     * @return the resolved, normalised absolute path, guaranteed to be inside the storage root
     * @throws GeneralException if the key is null, empty, carries a control character, an
     *     absolute path, a {@code ..} component or a drive prefix, or would escape the root
     */
    private Path resolve(String key) throws GeneralException {
        if (UtilValidate.isEmpty(key)) {
            throw new GeneralException("A content store key must not be empty");
        }
        for (int index = 0; index < key.length(); index++) {
            if (Character.isISOControl(key.charAt(index))) {
                throw new GeneralException("A content store key must not contain a control character");
            }
        }
        // A leading separator or a Windows drive prefix would make resolve() ignore the root entirely.
        // A colon elsewhere is a legal character in a POSIX file name and is deliberately allowed.
        if (key.startsWith("/") || key.startsWith("\\")
                || (key.length() > 1 && key.charAt(1) == ':' && Character.isLetter(key.charAt(0)))) {
            throw new GeneralException("A content store key must be relative and must not carry a drive prefix:"
                    + " [" + key + "]");
        }
        for (String segment : key.split("/")) {
            if ("..".equals(segment)) {
                throw new GeneralException("A content store key must not contain a '..' component: [" + key + "]");
            }
        }
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new GeneralException("The content store key [" + key + "] resolves outside the storage root");
        }
        return resolved;
    }

    /**
     * Reads a path's own attributes without following a link.
     *
     * @param target the path to read
     * @return the attributes, or {@code null} when nothing exists at that path
     * @throws IOException if the attributes cannot be read for any other reason
     */
    private BasicFileAttributes readAttributes(Path target) throws IOException {
        try {
            return Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) {
            Debug.logVerbose(absent, "The filesystem content store holds nothing at [" + relative(target) + "]",
                    MODULE);
            return null;
        }
    }

    /**
     * Requires that a path holds a regular file, reporting an absent key the way the contract
     * requires and refusing anything that is not a regular file.
     *
     * @param target the resolved path
     * @throws GeneralException if the path exists but is not a regular file
     * @throws FileNotFoundException if nothing is stored there, which is how the contract
     *     reports an absent key
     */
    private void requireRegularFile(Path target) throws GeneralException, IOException {
        BasicFileAttributes attributes = readAttributes(target);
        if (attributes == null) {
            throw new FileNotFoundException("The filesystem content store holds no content at ["
                    + relative(target) + "]");
        }
        if (!attributes.isRegularFile()) {
            throw new GeneralException("The filesystem content store refuses [" + relative(target)
                    + "] because it is not a regular file");
        }
    }

    /**
     * Refuses to overwrite an existing entry that is not a regular file, so that a link or a
     * directory planted at a destination cannot turn a write into a write somewhere else.
     *
     * @param target the resolved destination
     * @throws GeneralException if something other than a regular file already occupies it
     * @throws IOException if its attributes cannot be read
     */
    private void refuseIrregularExistingEntry(Path target) throws GeneralException, IOException {
        BasicFileAttributes attributes = readAttributes(target);
        if (attributes != null && !attributes.isRegularFile()) {
            throw new GeneralException("The filesystem content store refuses to write [" + relative(target)
                    + "] because something that is not a regular file already occupies it");
        }
    }

    /**
     * Creates a directory and any missing parent inside the storage root, owner-only.
     *
     * @param directory the directory to create
     * @throws IOException if it cannot be created
     */
    private void createDirectories(Path directory) throws IOException {
        if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path parent = directory.getParent();
        if (parent != null && parent.startsWith(root) && !parent.equals(root)) {
            createDirectories(parent);
        }
        try {
            Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
        } catch (FileAlreadyExistsException raced) {
            // Another request created it first, which is the expected outcome of concurrent uploads.
            Debug.logVerbose(raced, "The content store directory [" + relative(directory)
                    + "] was created concurrently", MODULE);
        } catch (UnsupportedOperationException notPosix) {
            Debug.logVerbose(notPosix, "This filesystem does not support POSIX permissions, so the content store"
                    + " directory [" + relative(directory) + "] is created with the platform default", MODULE);
            Files.createDirectories(directory);
        }
    }

    /**
     * Creates the owner-only temporary file a write is staged through, in the destination's own
     * directory so that the move onto the destination stays within one filesystem and can be
     * atomic.
     *
     * @param directory the destination's directory
     * @return the staging file
     * @throws IOException if it cannot be created
     */
    private Path createStagingFile(Path directory) throws IOException {
        try {
            return Files.createTempFile(directory, STAGING_PREFIX, ".tmp",
                    PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
        } catch (UnsupportedOperationException notPosix) {
            Debug.logVerbose(notPosix, "This filesystem does not support POSIX permissions, so content is staged"
                    + " with the platform default permissions", MODULE);
            return Files.createTempFile(directory, STAGING_PREFIX, ".tmp");
        }
    }

    /**
     * Moves staged content onto its destination, atomically where the filesystem supports it.
     *
     * @param staging the staged content
     * @param target the destination
     * @throws IOException if the move fails
     */
    private void move(Path staging, Path target) throws IOException {
        try {
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Debug.logVerbose(notAtomic, "This filesystem cannot move atomically, so content is replaced with a"
                    + " plain move at [" + relative(target) + "]", MODULE);
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Renders a resolved path relative to the storage root, so that a diagnostic names the key
     * rather than the deployment's directory layout.
     *
     * @param target the resolved path
     * @return the root-relative form, or the path itself when it is the root
     */
    private String relative(Path target) {
        return target.startsWith(root) ? root.relativize(target).toString() : target.toString();
    }
}
