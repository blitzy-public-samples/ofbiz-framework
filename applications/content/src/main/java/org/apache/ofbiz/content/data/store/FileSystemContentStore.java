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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;

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
 * <p><strong>Confinement is lexical and then checked on disk.</strong> A key is first put through the
 * grammar every provider shares, {@link ContentStore#requireUsableKey(String)}, which refuses a
 * control character, an absolute path, a Windows drive prefix, an empty component and a {@code .} or
 * {@code ..} component before any filesystem call is made; the resolved, normalised path must then
 * lie inside {@code ofbiz.home}.
 * That much is only arithmetic on strings, so every ancestor that already exists is then read with
 * {@link LinkOption#NOFOLLOW_LINKS} and refused if it is a symbolic link or is not a directory:
 * without that walk a key like {@code runtime/uploads/x} passes every lexical test while
 * {@code runtime/uploads} is a link to somewhere else entirely, and the provider would read and
 * write outside the root it believes it is confined to.
 *
 * <p><strong>Links are never followed.</strong> Attributes are read and content is opened with
 * {@code NOFOLLOW_LINKS}, so the final component can never be a link, and anything that is not a
 * regular file - a symbolic link, a directory, a device - is refused. A directory this provider is
 * told already exists is re-read after the fact rather than assumed, so an entry planted while a
 * concurrent write was creating its parents is refused instead of written through.
 *
 * <p>What that does not claim: between the ancestor walk and the open, an actor who can write to a
 * directory inside {@code ofbiz.home} could still exchange one of those directories for a link.
 * Closing that window entirely needs every operation performed relative to an open directory
 * descriptor, and it is not closed here because an actor with write access inside
 * {@code ofbiz.home} is running as the OFBiz user - and can therefore read and rewrite the content
 * directly, without needing this provider to do it for them. The walk exists to stop a link that is
 * simply *there*, which is the case that occurs without an attacker at all.
 *
 * <p><strong>A new file is created privately; an existing file is written in place.</strong> When
 * nothing is stored under a key yet, content is written to a temporary file created
 * {@code rw-------} in the destination's own directory and then moved onto the destination
 * atomically, so the content appears complete or not at all and is private from the instant it
 * exists. When a regular file is already there, it is truncated and rewritten in place instead: in
 * this provider the storage tree *is* the deployment's existing content tree, and replacing the file
 * would give it a new inode, a new modification time and this provider's permissions rather than the
 * ones the deployment's own upload path produced. Preserving that lifecycle is what the plan
 * requires of filesystem mode (plan section 0.6.3), and it is the same in-place rewrite the
 * {@code DataResource} services perform, so an in-place write is not a weaker guarantee than
 * content already has - it is the guarantee it already has. Directories this provider creates are
 * created {@code rwx------}.
 *
 * <p><strong>Privacy fails closed.</strong> On a filesystem that cannot express POSIX permissions
 * the owner-only mode is applied through the platform's own access flags and then verified; if it
 * cannot be established, the write is refused rather than completed with whatever the platform
 * default happens to be. Content held here can be a private document, and a default that turns out
 * to be world-readable is not something to discover later.
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

    /** The delegator every {@code content.store.*} value is read through; null reads the file alone. */
    private final Delegator delegator;

    /**
     * Constructs the provider around the {@code ofbiz.home} tree, reading its configuration through
     * a delegator.
     *
     * <p>This is the constructor {@link ContentStoreFactory} uses, and it is handed the delegator
     * that selected this provider so that the bound this provider enforces is read from the same
     * place the selection was: a deployment that raises {@code content.store.max.object.size}
     * through a {@code SystemProperty} row raises it here too.
     *
     * @param delegator the delegator every configuration value is read through; may be null, in
     *     which case only {@code content.properties} is consulted
     * @throws GeneralException if {@code ofbiz.home} is unset or blank, either of which means the
     *     provider has no storage root and cannot resolve any key
     */
    public FileSystemContentStore(Delegator delegator) throws GeneralException {
        // Trimmed before it is tested: whitespace is what a shell that expanded an unset variable into a
        // quoted argument leaves behind, and accepting it would silently root the whole content store at
        // whatever directory the process happened to start in - a different tree on every instance.
        String home = System.getProperty("ofbiz.home");
        String configured = home == null ? "" : home.trim();
        if (UtilValidate.isEmpty(configured)) {
            throw new GeneralException("The filesystem content store cannot be used because the ofbiz.home system"
                    + " property is not set, so it has no storage root to resolve a key against");
        }
        this.root = Paths.get(configured).toAbsolutePath().normalize();
        this.delegator = delegator;
    }

    @Override
    public void put(String key, byte[] data) throws GeneralException, IOException {
        if (data == null) {
            throw new GeneralException("Cannot store null content for content store key [" + key + "]");
        }
        Path target = resolve(key);
        BasicFileAttributes existing = readAttributes(target);
        if (existing != null) {
            if (!existing.isRegularFile()) {
                throw new GeneralException("The filesystem content store refuses to write [" + relative(target)
                        + "] because something that is not a regular file already occupies it");
            }
            // The same backing file the deployment's own upload path already manages, so it is
            // rewritten rather than replaced: see the class documentation for why keeping this file's
            // inode, modification time and permissions matters more here than an atomic swap would.
            writeInPlace(target, data);
            return;
        }
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
        BasicFileAttributes attributes = requireRegularFile(target);
        long limit = ContentStoreFactory.maxObjectSize(delegator);
        if (attributes.size() > limit) {
            throw oversized(target, String.valueOf(attributes.size()), limit);
        }
        // Bounded again while reading, because the size above was true when it was measured and the
        // file can be appended to between that measurement and this read.
        try (InputStream content = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
            byte[] read = content.readNBytes((int) limit);
            if (content.read() != -1) {
                throw oversized(target, "more than " + limit, limit);
            }
            return read;
        } catch (NoSuchFileException removedMeanwhile) {
            // This tree is shared, so content can be removed between the check above and this open. The
            // filesystem reports that as a NoSuchFileException, which is an IOException but not a
            // FileNotFoundException, so it would escape every caller that catches absence - including the
            // integration seam, which catches FileNotFoundException alone.
            throw absent(target, removedMeanwhile);
        }
    }

    @Override
    public InputStream openStream(String key) throws GeneralException, IOException {
        Path target = resolve(key);
        requireRegularFile(target);
        // Deliberately unbounded, and opened NOFOLLOW so the name cannot have become a link: this is
        // the operation content of a size an uploader chose is served through, and it never holds
        // that content in the heap in full.
        try {
            return Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException removedMeanwhile) {
            throw absent(target, removedMeanwhile);
        }
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
     * <p>The shared key grammar first, then the lexical root test, then the on-disk confinement check:
     * passing the string tests only establishes that the key names a path under the root, not that
     * walking to it stays under the root, which is a different question whenever an ancestor is a link.
     *
     * @param key the provider-relative storage key
     * @return the resolved, normalised absolute path, guaranteed to be inside the storage root and
     *     reachable without traversing a link
     * @throws GeneralException if the key breaks the shared key grammar, would escape the root, or is
     *     reached through an ancestor that is a link or is not a directory
     * @throws IOException if an ancestor's attributes cannot be read
     */
    private Path resolve(String key) throws GeneralException, IOException {
        // The grammar every provider shares, from its one implementation: empty, control characters, an
        // absolute path or a drive prefix, an empty or relative component, and the length bounds. What
        // follows is what this provider alone requires - that the key stay inside the tree it owns.
        ContentStore.requireUsableKey(key);
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new GeneralException("The content store key [" + key + "] resolves outside the storage root");
        }
        confine(resolved);
        return resolved;
    }

    /**
     * Refuses a path that is only nominally inside the storage root.
     *
     * <p>Walks the ancestors from the root down to, but not including, the content itself, reading
     * each with {@link LinkOption#NOFOLLOW_LINKS}. An ancestor that is a symbolic link or is not a
     * directory is refused, because either one means that reaching the content leaves the tree this
     * provider is confined to - and a lexical check cannot see that, since it compares strings and a
     * link's name says nothing about where it points. The final component is not walked: it is the
     * content, and every operation opens it {@code NOFOLLOW} so it cannot be a link either.
     *
     * <p>The walk stops at the first ancestor that does not exist. Nothing below a path that is not
     * there can redirect anything, and what is created below it is created by this provider, as a
     * directory, owner-only.
     *
     * @param target the resolved path, already known to be lexically inside the root
     * @throws GeneralException if an existing ancestor is a symbolic link or is not a directory
     * @throws IOException if an ancestor's attributes cannot be read
     */
    private void confine(Path target) throws GeneralException, IOException {
        Path relative = root.relativize(target);
        Path ancestor = root;
        for (int index = 0; index < relative.getNameCount() - 1; index++) {
            ancestor = ancestor.resolve(relative.getName(index));
            BasicFileAttributes attributes = readAttributes(ancestor);
            if (attributes == null) {
                return;
            }
            if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                throw new GeneralException("The filesystem content store refuses [" + relative(target)
                        + "] because the ancestor [" + relative(ancestor) + "] is "
                        + (attributes.isSymbolicLink() ? "a symbolic link" : "not a directory")
                        + ", so reaching that key would leave the storage root");
            }
        }
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
     * @return the attributes read while checking, so that a caller which needs the size does not
     *     have to read them a second time and risk disagreeing with this check
     * @throws GeneralException if the path exists but is not a regular file
     * @throws FileNotFoundException if nothing is stored there, which is how the contract
     *     reports an absent key
     */
    private BasicFileAttributes requireRegularFile(Path target) throws GeneralException, IOException {
        BasicFileAttributes attributes = readAttributes(target);
        if (attributes == null) {
            throw absent(target, null);
        }
        if (!attributes.isRegularFile()) {
            throw new GeneralException("The filesystem content store refuses [" + relative(target)
                    + "] because it is not a regular file");
        }
        return attributes;
    }

    /**
     * Builds the absence report the contract requires, whichever way the filesystem reported it.
     *
     * <p>The cause is attached rather than described, because {@link FileNotFoundException} does not
     * fold a cause's message into its own: a caller sees the storage location it asked for, while the
     * filesystem's own detail stays available to anything that inspects the cause.
     *
     * @param target the resolved path that holds nothing
     * @param cause the filesystem's own report, or {@code null} when absence was established by
     *     reading attributes rather than by a read that failed
     * @return the exception to throw
     */
    private FileNotFoundException absent(Path target, IOException cause) {
        FileNotFoundException absent = new FileNotFoundException("The filesystem content store holds no content at ["
                + relative(target) + "]");
        if (cause != null) {
            absent.initCause(cause);
        }
        return absent;
    }

    /**
     * Rewrites an existing file's content without replacing the file.
     *
     * <p>Opened {@code WRITE} and {@code TRUNCATE_EXISTING} with {@code NOFOLLOW_LINKS} and without
     * {@code CREATE}, so it writes to the file that is already there or to nothing at all. The file
     * keeps its inode, its ownership and its permissions, which is what makes this provider's writes
     * indistinguishable from the ones the deployment's own upload path performs.
     *
     * @param target the existing regular file
     * @param data the content to write
     * @throws IOException if the file cannot be written
     */
    private void writeInPlace(Path target, byte[] data) throws IOException {
        try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
            out.write(data);
        }
    }

    /**
     * Reports content that does not fit inside the configured ceiling.
     *
     * @param target the resolved path, named relative to the root
     * @param size the size as far as it is known
     * @param limit the ceiling that was exceeded
     * @return the exception to throw
     */
    private IOException oversized(Path target, String size, long limit) {
        return new IOException("The filesystem content store refuses to read [" + relative(target) + "] whole"
                + " because it is " + size + " bytes, over the " + ContentStoreFactory.MAX_OBJECT_SIZE_PROPERTY
                + " ceiling of " + limit + "; content this large has to be streamed rather than read whole");
    }

    /**
     * Creates a directory and any missing parent inside the storage root, owner-only.
     *
     * @param directory the directory to create
     * @throws GeneralException if what already occupies the path is not a directory, or if owner-only
     *     permissions cannot be established
     * @throws IOException if it cannot be created
     */
    private void createDirectories(Path directory) throws GeneralException, IOException {
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
            // Something is there now that was not there a moment ago. The expected cause is a
            // concurrent upload creating the same directory, but "a directory appeared" is exactly
            // what a planted link looks like too, so what appeared is re-read rather than assumed.
            requireDirectory(directory, raced);
        } catch (UnsupportedOperationException notPosix) {
            createDirectoryWithoutPosix(directory);
        }
    }

    /**
     * Re-reads an entry that appeared while its parents were being created, and refuses it unless it
     * is a real directory.
     *
     * @param directory the path that already exists
     * @param raced the report that it already exists
     * @throws GeneralException if it is a link or is not a directory
     * @throws IOException if its attributes cannot be read
     */
    private void requireDirectory(Path directory, FileAlreadyExistsException raced)
            throws GeneralException, IOException {
        BasicFileAttributes attributes = readAttributes(directory);
        if (attributes == null) {
            // Created and removed again between the two calls. Nothing is there, so nothing can be
            // written through it; the write below will report the missing directory itself.
            Debug.logVerbose(raced, "The content store directory [" + relative(directory)
                    + "] appeared and was removed again while it was being created", MODULE);
            return;
        }
        if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
            throw new GeneralException("The filesystem content store refuses to write below ["
                    + relative(directory) + "] because " + (attributes.isSymbolicLink() ? "a symbolic link"
                    : "something that is not a directory") + " appeared there while it was being created");
        }
        Debug.logVerbose(raced, "The content store directory [" + relative(directory)
                + "] was created concurrently", MODULE);
    }

    /**
     * Creates a directory on a filesystem that cannot express POSIX permissions, applying and
     * verifying owner-only access through the platform's own flags instead.
     *
     * @param directory the directory to create
     * @throws GeneralException if owner-only access cannot be established
     * @throws IOException if it cannot be created
     */
    private void createDirectoryWithoutPosix(Path directory) throws GeneralException, IOException {
        Files.createDirectories(directory);
        restrictToOwner(directory, "directory");
    }

    /**
     * Creates the owner-only temporary file a write is staged through, in the destination's own
     * directory so that the move onto the destination stays within one filesystem and can be
     * atomic.
     *
     * @param directory the destination's directory
     * @return the staging file
     * @throws GeneralException if owner-only permissions cannot be established on a filesystem that
     *     does not support POSIX permissions
     * @throws IOException if it cannot be created
     */
    private Path createStagingFile(Path directory) throws GeneralException, IOException {
        try {
            return Files.createTempFile(directory, STAGING_PREFIX, ".tmp",
                    PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
        } catch (UnsupportedOperationException notPosix) {
            Path staging = Files.createTempFile(directory, STAGING_PREFIX, ".tmp");
            boolean restricted = false;
            try {
                restrictToOwner(staging, "content");
                restricted = true;
            } finally {
                if (!restricted) {
                    // Created with the platform default and could not be made private, so it is
                    // removed rather than left behind readable while the refusal propagates.
                    Files.deleteIfExists(staging);
                }
            }
            return staging;
        }
    }

    /**
     * Applies owner-only access through the platform's own flags and refuses to continue if it cannot
     * be established.
     *
     * <p>Reached only on a filesystem that does not support POSIX permissions, where the alternative
     * is whatever the platform default happens to be. Content held here can be a private document,
     * so this fails closed: read and write are first removed for everyone and then granted to the
     * owner, and every one of those four calls has to report success. A platform that will not say
     * yes is a platform where privacy cannot be demonstrated, and completing the write there would
     * mean discovering the exposure later rather than now.
     *
     * <p>Package-private so that the fail-closed behaviour is testable without a non-POSIX
     * filesystem to hand; nothing outside this package calls it.
     *
     * @param path the entry to restrict
     * @param what what the entry is, for the refusal message
     * @throws GeneralException if owner-only access cannot be established
     */
    static void restrictToOwner(Path path, String what) throws GeneralException {
        File entry = path.toFile();
        boolean applied = entry.setReadable(false, false)
                && entry.setWritable(false, false)
                && entry.setReadable(true, true)
                && entry.setWritable(true, true);
        if (!applied) {
            throw new GeneralException("The filesystem content store refuses to create " + what + " at ["
                    + path.getFileName() + "] because this filesystem supports neither POSIX permissions nor the"
                    + " platform access flags, so the entry cannot be shown to be private to the OFBiz user");
        }
        Debug.logVerbose("This filesystem does not support POSIX permissions, so owner-only access for the content"
                + " store " + what + " was applied and verified through the platform access flags", MODULE);
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
