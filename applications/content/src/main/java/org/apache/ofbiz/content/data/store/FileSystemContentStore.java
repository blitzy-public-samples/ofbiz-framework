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
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
 * <p><strong>Confinement is lexical, and then it is held open.</strong> A key is first put through
 * the grammar every provider shares, {@link ContentStore#requireUsableKey(String)}, which refuses a
 * control character, an absolute path, a Windows drive prefix, an empty component and a {@code .} or
 * {@code ..} component before any filesystem call is made; the resolved, normalised path must then
 * lie inside {@code ofbiz.home}. That much is only arithmetic on strings: a key like
 * {@code runtime/uploads/x} passes every lexical test while {@code runtime/uploads} is a link to
 * somewhere else entirely, and a provider that trusted the arithmetic would read and write outside
 * the root it believes it is confined to.
 *
 * <p>So the tree is walked, and the walk is what every operation is then performed <em>through</em>.
 * Starting at the root, each intervening directory is opened relative to the directory already open
 * above it - {@link SecureDirectoryStream#newDirectoryStream} with
 * {@link LinkOption#NOFOLLOW_LINKS} - and the content itself is opened, measured, rewritten or
 * removed relative to the innermost of those open directories. The path string is never re-walked by
 * the kernel after it has been checked, because after the descent there is no path left to walk: an
 * open directory names one directory for as long as it stays open, whatever later happens to the
 * name it was reached by.
 *
 * <p>That is what makes the check meaningful rather than advisory. Checking each ancestor and then
 * reopening the content by path leaves a window between the two in which an actor able to write into
 * one of those directories can exchange it for a symbolic link, and the reopen - the whole path
 * resolved again from the beginning - follows the link out of the root. {@code NOFOLLOW_LINKS} on the
 * final component does not help, because the component that was exchanged is an ancestor and the
 * final component is reached only after the kernel has already traversed it. Descending by descriptor
 * removes the window rather than narrowing it: there is no second resolution to attack.
 *
 * <p><strong>Links are never followed.</strong> Every descent and every open passes
 * {@code NOFOLLOW_LINKS}, so neither an ancestor nor the final component can be a link, and anything
 * that is not a regular file - a symbolic link, a directory, a device - is refused. A directory this
 * provider is told already exists is re-read after the fact rather than assumed, so an entry planted
 * while a concurrent write was creating its parents is refused instead of written through.
 *
 * <p>A platform whose {@link java.nio.file.FileSystem} does not hand out a
 * {@link SecureDirectoryStream} - which is a documented optional capability - cannot offer
 * descriptor-relative operations at all. There the provider falls back to the ancestor walk followed
 * by a path-based open, logs that it has done so once, and accepts the narrower guarantee, because
 * refusing to serve content on such a platform would be a worse answer than serving it with the
 * protection the platform can actually provide. Every mainstream Unix filesystem supplies a secure
 * stream.
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

    /**
     * How much of a streamed payload is held at once while it is copied.
     *
     * <p>Fixed and modest on purpose: this is the buffer that makes publishing an upload of a size the
     * uploader chose safe, so it must not scale with the content. 8 KiB is the size the JDK's own
     * stream copies use.
     */
    private static final int COPY_BUFFER_BYTES = 8192;

    /** How many times a staging name collision is retried before the write gives up. */
    private static final int STAGING_NAME_ATTEMPTS = 8;

    /**
     * Whether the absence of {@link SecureDirectoryStream} has already been reported, so it is
     * reported once per JVM rather than once per operation.
     */
    private static final AtomicBoolean INSECURE_FILE_SYSTEM_REPORTED = new AtomicBoolean(false);

    /**
     * What runs between a key's descent and the operation performed through it, or {@code null} in
     * every deployment.
     *
     * <p>This exists so the confinement guarantee can be tested rather than argued about. The defect
     * it guards against is a race - an ancestor exchanged for a symbolic link after it has been
     * checked and before the content is opened - and a race that a test has to win by scheduling luck
     * is a test that passes for the wrong reason on a fast machine and fails for no reason on a slow
     * one. Handing the test the exact instant between the two steps makes the window's presence or
     * absence a decided fact: a provider that re-resolves the path reads what the test substituted,
     * and one that operates through the descriptors it already holds does not.
     *
     * <p>Installed by this package's own test alone, and consulted at one point, so a deployment runs
     * the same code with one {@code null} check.
     */
    private static final AtomicReference<Runnable> BETWEEN_DESCENT_AND_OPERATION = new AtomicReference<>(null);

    /**
     * What runs between the moment a write looks at its destination and the moment it opens it, or
     * {@code null} in every deployment.
     *
     * <p>The second instant this provider cannot otherwise be tested at, and it exists for the same
     * reason as {@link #BETWEEN_DESCENT_AND_OPERATION}. A write that finds a regular file already
     * stored rewrites it in place, and between the two steps a concurrent delete of the same key can
     * remove it; the write then has to publish a new file rather than report a failure. Reaching that
     * branch by contention alone takes scheduling luck, so it would be a branch whose behaviour is
     * asserted only some of the time. Handing a test the exact instant makes it a decided fact.
     *
     * <p>Installed by this package's own test alone, and consulted at one point, so a deployment runs
     * the same code with one {@code null} check.
     */
    private static final AtomicReference<Runnable> BETWEEN_LOOK_AND_OPEN = new AtomicReference<>(null);

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
        store(key, out -> out.write(data));
    }

    @Override
    public void put(String key, InputStream content, long length) throws GeneralException, IOException {
        if (content == null) {
            throw new GeneralException("Cannot store content from a null stream for content store key ["
                    + key + "]");
        }
        if (length < 0L) {
            throw new GeneralException("Cannot store " + length + " bytes for content store key [" + key + "]");
        }
        store(key, out -> copyExactly(content, out, length, key));
    }

    /**
     * Writes a payload to the one path a key names, whichever source the payload comes from.
     *
     * <p>The two {@code put} overloads differ only in where their bytes come from, so the decision
     * between rewriting an existing file and publishing a new one is made once, here. Rewriting is
     * deliberate for a file that is already there: see the class documentation for why keeping that
     * file's inode, modification time and permissions matters more here than an atomic swap would.
     * A file that is not there yet is staged beside its target and moved into place, so a concurrent
     * reader never observes a partially written new file. A file that was there when it was looked at
     * and gone by the time it was opened is published as a new one, so a concurrent delete of the same
     * key cannot turn a write that was asked for into a failure.
     *
     * @param key the provider-relative storage key
     * @param payload the content to write
     * @throws GeneralException if the key is unusable or something that is not a regular file occupies
     *     the target
     * @throws IOException if the content cannot be written
     */
    private void store(String key, Payload payload) throws GeneralException, IOException {
        try (Location location = locate(key, true)) {
            BasicFileAttributes existing = location.attributesOrNull();
            if (existing != null) {
                if (!existing.isRegularFile()) {
                    throw new GeneralException("The filesystem content store refuses to write ["
                            + relative(location.target) + "] because something that is not a regular file already"
                            + " occupies it");
                }
                // The open decides, not the check above. Between the two, a concurrent delete of the same
                // key can remove the file, and a rewrite opened without CREATE then finds nothing there;
                // reporting that as a failure would refuse a write that was asked for, where the upload
                // path this provider stands in for - a plain create-or-truncate open - would have stored
                // the content. So an open that finds the file gone publishes a new one instead.
                interleaveBeforeOpen();
                OutputStream rewrite;
                try {
                    rewrite = location.openForRewrite();
                } catch (NoSuchFileException removedMeanwhile) {
                    rewrite = null;
                }
                if (rewrite != null) {
                    try (OutputStream out = rewrite) {
                        payload.writeTo(out);
                    }
                    return;
                }
            }
            publishNewFile(location, payload);
        }
    }

    /**
     * Writes content that is not there yet to a private staging entry beside its destination and then
     * moves it onto the destination.
     *
     * <p>Staged and moved rather than created and written, so a concurrent reader never observes a
     * partially written new file, and created {@code rw-------} so it is private from the instant it
     * exists rather than from the instant the write finishes. Both the staging entry and the move are
     * relative to the directory the location holds open, so neither can be redirected by an exchange
     * of an ancestor.
     *
     * @param location the located destination, open on its own directory
     * @param payload the content to write
     * @throws GeneralException if owner-only permissions cannot be established
     * @throws IOException if the content cannot be written or moved into place
     */
    private void publishNewFile(Location location, Payload payload) throws GeneralException, IOException {
        if (location.directory == null) {
            Path parent = location.target.getParent();
            Path staging = createStagingFile(parent == null ? root : parent);
            try {
                try (OutputStream out = Files.newOutputStream(staging, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
                    payload.writeTo(out);
                }
                move(staging, location.target);
                staging = null;
            } finally {
                if (staging != null) {
                    Files.deleteIfExists(staging);
                }
            }
            return;
        }
        Path staging = createStagingEntry(location);
        boolean published = false;
        try {
            try (OutputStream out = Channels.newOutputStream(location.directory.newByteChannel(staging,
                    Set.of(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                            LinkOption.NOFOLLOW_LINKS)))) {
                payload.writeTo(out);
            }
            // Directory-relative, and within one directory, so it is the atomic rename the contract
            // promises rather than a copy that could be observed half done.
            location.directory.move(staging, location.directory, location.name);
            published = true;
        } finally {
            if (!published) {
                try {
                    location.directory.deleteFile(staging);
                } catch (IOException | RuntimeException e) {
                    Debug.logWarning("A filesystem content store staging entry could not be removed after a failed"
                            + " write below [" + relative(location.target) + "]: " + e.getClass().getName(), MODULE);
                }
            }
        }
    }

    /**
     * Creates the private staging entry a new file is written through, relative to the destination's
     * own open directory.
     *
     * <p>{@link java.nio.file.Files#createTempFile} cannot be used here because it works by path;
     * the name is generated and created with {@code CREATE_NEW} instead, which is what makes the
     * creation exclusive, and a collision is retried rather than reported because two concurrent
     * writes below one directory is ordinary rather than exceptional.
     *
     * @param location the located destination, whose directory the entry is created in
     * @return the single-component staging name
     * @throws GeneralException if owner-only permissions cannot be established
     * @throws IOException if it cannot be created
     */
    private Path createStagingEntry(Location location) throws GeneralException, IOException {
        for (int attempt = 0; attempt < STAGING_NAME_ATTEMPTS; attempt++) {
            Path candidate = Paths.get(STAGING_PREFIX + Long.toHexString(ThreadLocalRandom.current().nextLong())
                    + ".tmp");
            try {
                location.directory.newByteChannel(candidate,
                        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                        PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS)).close();
                return candidate;
            } catch (FileAlreadyExistsException collision) {
                Debug.logVerbose(collision, "A content store staging name was already taken; trying another",
                        MODULE);
            } catch (UnsupportedOperationException notPosix) {
                return createStagingEntryWithoutPosix(location, candidate);
            }
        }
        throw new IOException("The filesystem content store could not create a staging entry below ["
                + relative(location.target) + "] after " + STAGING_NAME_ATTEMPTS + " attempts");
    }

    /**
     * Creates the staging entry on a filesystem that cannot express POSIX permissions, applying and
     * verifying owner-only access through the platform's own flags instead.
     *
     * <p>Reachable only where a platform supplies a {@link SecureDirectoryStream} and yet refuses
     * POSIX permissions, which no mainstream filesystem does. It is written out rather than left to
     * chance because the alternative is a staging entry created with whatever the platform default
     * happens to be, holding content that can be a private document.
     *
     * @param location the located destination, whose directory the entry is created in
     * @param candidate the staging name to create
     * @return the staging name
     * @throws GeneralException if owner-only access cannot be established
     * @throws IOException if it cannot be created
     */
    private Path createStagingEntryWithoutPosix(Location location, Path candidate)
            throws GeneralException, IOException {
        location.directory.newByteChannel(candidate,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)).close();
        boolean restricted = false;
        try {
            restrictToOwner(location.target.resolveSibling(candidate), "content");
            restricted = true;
        } finally {
            if (!restricted) {
                // Created with the platform default and could not be made private, so it is removed
                // rather than left behind readable while the refusal propagates.
                try {
                    location.directory.deleteFile(candidate);
                } catch (IOException | RuntimeException e) {
                    Debug.logWarning("A content store staging entry that could not be made private could not be"
                            + " removed either: " + e.getClass().getName(), MODULE);
                }
            }
        }
        return candidate;
    }

    /**
     * Copies exactly the declared number of bytes, refusing a stream that yields a different number.
     *
     * <p>A length that does not match the stream is a caller error rather than something to work
     * around, and both directions matter: a short stream would store truncated content under a key
     * that reads back as complete, and a long one would silently drop the rest. Refusing before the
     * content is published - the caller's target is a staging file or an already-validated rewrite -
     * is what keeps a mismatch from becoming stored content.
     *
     * @param content the stream to read from; not closed here, because it belongs to the caller
     * @param out the destination to write to
     * @param length the exact number of bytes to transfer
     * @param key the key being written, named in a refusal
     * @throws IOException if the transfer fails, or if the stream does not hold exactly {@code length}
     *     bytes
     */
    private static void copyExactly(InputStream content, OutputStream out, long length, String key)
            throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        long remaining = length;
        while (remaining > 0L) {
            int wanted = (int) Math.min(buffer.length, remaining);
            int read = content.read(buffer, 0, wanted);
            if (read < 0) {
                throw new IOException("The content stream for [" + key + "] ended " + remaining + " bytes before"
                        + " the " + length + " bytes it declared, so nothing was stored");
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
        if (content.read() != -1) {
            throw new IOException("The content stream for [" + key + "] holds more than the " + length
                    + " bytes it declared, so nothing was stored");
        }
    }

    /** What a {@code put} overload contributes: the bytes, written to wherever this provider decided. */
    private interface Payload {
        /**
         * Writes the whole content to the supplied destination.
         *
         * @param out the destination, owned and closed by the caller
         * @throws IOException if the content cannot be produced or written
         */
        void writeTo(OutputStream out) throws IOException;
    }

    @Override
    public byte[] get(String key) throws GeneralException, IOException {
        try (Location location = locate(key, false)) {
            BasicFileAttributes attributes = location.requireRegularFile();
            long limit = ContentStoreFactory.maxObjectSize(delegator);
            if (attributes.size() > limit) {
                throw oversized(location.target, String.valueOf(attributes.size()), limit);
            }
            // Bounded again while reading, because the size above was true when it was measured and the
            // file can be appended to between that measurement and this read.
            try (InputStream content = location.openForRead()) {
                byte[] read = content.readNBytes((int) limit);
                if (content.read() != -1) {
                    throw oversized(location.target, "more than " + limit, limit);
                }
                return read;
            } catch (NoSuchFileException removedMeanwhile) {
                // This tree is shared, so content can be removed between the check above and this open. The
                // filesystem reports that as a NoSuchFileException, which is an IOException but not a
                // FileNotFoundException, so it would escape every caller that catches absence - including the
                // integration seam, which catches FileNotFoundException alone.
                throw absent(location.target, removedMeanwhile);
            }
        }
    }

    @Override
    public ContentStream openStream(String key) throws GeneralException, IOException {
        Location location = locate(key, false);
        boolean handedOver = false;
        try {
            BasicFileAttributes attributes = location.requireRegularFile();
            // Deliberately unbounded, and opened NOFOLLOW so the name cannot have become a link: this is
            // the operation content of a size an uploader chose is served through, and it never holds
            // that content in the heap in full.
            //
            // The length comes from the attributes that established this is a regular file, so the
            // length reported and the file opened are the same measurement rather than two.
            ContentStream stream = new ContentStream(closing(location.openForRead(), location),
                    attributes.size());
            handedOver = true;
            return stream;
        } catch (NoSuchFileException removedMeanwhile) {
            throw absent(location.target, removedMeanwhile);
        } finally {
            if (!handedOver) {
                // The directory handles are released here only when the stream was never handed out;
                // otherwise they stay open until the caller closes the stream, because the open file
                // was reached through them.
                location.close();
            }
        }
    }

    /**
     * Wraps a stream so that closing it also releases the directory handles it was opened through.
     *
     * <p>This is what lets {@link #openStream} return before its descent is closed: the open file is
     * reached through those directories, so they are the caller's to release, and the only moment the
     * caller can be relied upon to act is when it closes the stream it was given.
     *
     * @param content the stream opened relative to the location's directory
     * @param location the descent that must be released with it
     * @return a stream that closes both, the content first
     */
    private static InputStream closing(InputStream content, Location location) {
        return new FilterInputStream(content) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    location.close();
                }
            }
        };
    }

    @Override
    public boolean exists(String key) throws GeneralException, IOException {
        try (Location location = locate(key, false)) {
            BasicFileAttributes attributes = location.attributesOrNull();
            return attributes != null && attributes.isRegularFile();
        }
    }

    @Override
    public void delete(String key) throws GeneralException, IOException {
        try (Location location = locate(key, false)) {
            BasicFileAttributes attributes = location.attributesOrNull();
            if (attributes == null) {
                // Idempotent: nothing stored under this key, so the removal has already happened.
                return;
            }
            if (!attributes.isRegularFile()) {
                throw new GeneralException("The filesystem content store refuses to remove ["
                        + relative(location.target) + "] because it is not a regular file");
            }
            location.removeFile();
        }
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
        ContentStoreFactory.requireUsableKey(key);
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new GeneralException("The content store key [" + key + "] resolves outside the storage root");
        }
        return resolved;
    }

    /**
     * Resolves a key and descends to the directory that holds it, keeping every directory on the way
     * open so the operation that follows is performed relative to a descriptor rather than a name.
     *
     * <p>This is the one place a key becomes something operable, and the reason it returns a handle
     * rather than a path is the whole of the confinement guarantee: see the class documentation.
     * The caller must close what it is given, which releases the descriptors the descent holds.
     *
     * @param key the provider-relative storage key
     * @param creatingParents whether a missing intervening directory should be created, which is true
     *     for a write and false for every read, so that a read never leaves a directory behind
     * @return the located content, open on its own directory
     * @throws GeneralException if the key breaks the shared key grammar, would escape the root, or is
     *     reached through an ancestor that is a link or is not a directory
     * @throws IOException if the descent fails for any other reason
     */
    private Location locate(String key, boolean creatingParents) throws GeneralException, IOException {
        Location located = descendTo(key, creatingParents);
        interleave();
        return located;
    }

    /**
     * Performs the descent {@link #locate} returns, without the test interleaving point, so that the
     * point is reached exactly once per located key whichever way the descent ended.
     *
     * @param key the provider-relative storage key
     * @param creatingParents whether a missing intervening directory should be created
     * @return the located content, open on its own directory
     * @throws GeneralException if the key breaks the shared key grammar, would escape the root, or is
     *     reached through an ancestor that is a link or is not a directory
     * @throws IOException if the descent fails for any other reason
     */
    private Location descendTo(String key, boolean creatingParents) throws GeneralException, IOException {
        Path target = resolve(key);
        Path relative = root.relativize(target);
        Path name = relative.getFileName();
        List<DirectoryStream<Path>> opened = new ArrayList<>(relative.getNameCount());
        boolean handedOver = false;
        try {
            DirectoryStream<Path> rootStream = Files.newDirectoryStream(root);
            opened.add(rootStream);
            if (!(rootStream instanceof SecureDirectoryStream)) {
                // Documented fallback: this platform cannot operate relative to a descriptor, so the
                // ancestor walk and a path-based open are the strongest available guarantee.
                reportInsecureFileSystem();
                closeAll(opened);
                opened.clear();
                confine(target);
                Location located = new Location(target, name, null, false, opened);
                handedOver = true;
                return located;
            }
            SecureDirectoryStream<Path> directory = (SecureDirectoryStream<Path>) rootStream;
            Path level = root;
            for (int index = 0; index < relative.getNameCount() - 1; index++) {
                Path step = relative.getName(index);
                level = level.resolve(step);
                SecureDirectoryStream<Path> descended = descend(directory, step, level, target, creatingParents);
                if (descended == null) {
                    // Nothing exists at this level and none is being created, so nothing is stored
                    // under this key. Reported as an absent parent rather than as a failure.
                    Location located = new Location(target, name, null, true, opened);
                    handedOver = true;
                    return located;
                }
                opened.add(descended);
                directory = descended;
            }
            Location located = new Location(target, name, directory, false, opened);
            handedOver = true;
            return located;
        } finally {
            if (!handedOver) {
                closeAll(opened);
            }
        }
    }

    /**
     * Runs whatever a test asked to have happen between a descent and the operation performed
     * through it, and nothing at all in a deployment.
     *
     * @see #BETWEEN_DESCENT_AND_OPERATION
     */
    private static void interleave() {
        Runnable between = BETWEEN_DESCENT_AND_OPERATION.get();
        if (between != null) {
            between.run();
        }
    }

    /**
     * Installs, or removes, what runs between a key's descent and the operation performed through it.
     *
     * <p>Package-private, and null in every deployment.
     *
     * @param between what to run at that instant, or {@code null} to run nothing
     */
    static void installInterleavedActionForTesting(Runnable between) {
        BETWEEN_DESCENT_AND_OPERATION.set(between);
    }

    /**
     * Runs whatever a test asked to have happen between a write's look at its destination and the
     * open performed on it, and nothing at all in a deployment.
     *
     * @see #BETWEEN_LOOK_AND_OPEN
     */
    private static void interleaveBeforeOpen() {
        Runnable between = BETWEEN_LOOK_AND_OPEN.get();
        if (between != null) {
            between.run();
        }
    }

    /**
     * Installs, or removes, what runs between a write's look at its destination and the open
     * performed on it.
     *
     * <p>Package-private, and null in every deployment.
     *
     * @param between what to run at that instant, or {@code null} to run nothing
     */
    static void installBeforeOpenActionForTesting(Runnable between) {
        BETWEEN_LOOK_AND_OPEN.set(between);
    }

    /**
     * Opens one directory below an already-open directory, creating it first when a write requires it.
     *
     * <p>Opened with {@code NOFOLLOW_LINKS} relative to the directory above, so a name that is a
     * symbolic link, or that is not a directory at all, cannot be descended into - whether it was
     * always so or became so a moment ago. Creation is by path because
     * {@link SecureDirectoryStream} offers no descriptor-relative create; that is sound because the
     * created directory is not then used by name - it is opened by the same
     * {@code NOFOLLOW_LINKS} descent as any other level, so a directory exchanged for a link between
     * being created and being opened is refused here exactly as a planted link would be.
     *
     * @param directory the directory to descend from
     * @param step the single name to descend into
     * @param level the absolute path of that name, used only to create it
     * @param target the whole resolved path, named in a refusal
     * @param creatingParents whether a missing directory should be created
     * @return the opened directory, or {@code null} when it does not exist and none is being created
     * @throws GeneralException if the name is a link, or is not a directory, or cannot be created
     *     privately
     * @throws IOException if it cannot be opened for any other reason
     */
    private SecureDirectoryStream<Path> descend(SecureDirectoryStream<Path> directory, Path step, Path level,
            Path target, boolean creatingParents) throws GeneralException, IOException {
        try {
            return directory.newDirectoryStream(step, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) {
            if (!creatingParents) {
                Debug.logVerbose(absent, "The filesystem content store holds nothing at [" + relative(target)
                        + "] because [" + step + "] does not exist", MODULE);
                return null;
            }
        } catch (NotDirectoryException notADirectory) {
            throw refuseAncestor(target, step, "is not a directory", notADirectory);
        } catch (FileSystemException wrongKind) {
            // A symbolic link opened NOFOLLOW is reported here, as ELOOP, and so is anything else the
            // platform will not open as a directory in its own right. Either way this level does not
            // name a directory of the tree, so the descent stops.
            throw refuseAncestor(target, step, "is a symbolic link or cannot be opened as a directory",
                    wrongKind);
        }
        createDirectories(level);
        try {
            return directory.newDirectoryStream(step, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException removedMeanwhile) {
            throw new GeneralException("The filesystem content store could not descend into [" + step
                    + "] on the way to [" + relative(target) + "] because it was removed as soon as it was"
                    + " created", removedMeanwhile);
        } catch (FileSystemException wrongKind) {
            throw refuseAncestor(target, step, "was replaced by a symbolic link or by something that is not a"
                    + " directory while it was being created", wrongKind);
        }
    }

    /**
     * Builds the refusal for an ancestor that cannot be part of this provider's tree.
     *
     * @param target the resolved path being reached
     * @param step the ancestor name that was refused
     * @param because what is wrong with it
     * @param cause the platform's own report
     * @return the exception to throw
     */
    private GeneralException refuseAncestor(Path target, Path step, String because, IOException cause) {
        return new GeneralException("The filesystem content store refuses [" + relative(target)
                + "] because the ancestor [" + step + "] " + because + ", so reaching that key would leave the"
                + " storage root", cause);
    }

    /**
     * Closes a descent's directory handles, innermost first, reporting rather than propagating a
     * failure.
     *
     * <p>Innermost first because that is the order they were opened in reversed, and a failure to
     * close one must not prevent the rest being closed - a leaked directory descriptor is held for the
     * life of the JVM.
     *
     * @param opened the handles to close
     */
    private static void closeAll(List<DirectoryStream<Path>> opened) {
        for (int index = opened.size() - 1; index >= 0; index--) {
            try {
                opened.get(index).close();
            } catch (IOException | RuntimeException e) {
                Debug.logWarning("A filesystem content store directory handle could not be closed: "
                        + e.getClass().getName(), MODULE);
            }
        }
    }

    /**
     * Reports once that this platform cannot offer descriptor-relative operations.
     *
     * <p>Once, because it is a property of the filesystem rather than of a request: reporting it per
     * operation would fill the log without adding anything after the first line.
     */
    private void reportInsecureFileSystem() {
        if (INSECURE_FILE_SYSTEM_REPORTED.compareAndSet(false, true)) {
            Debug.logWarning("This filesystem does not provide SecureDirectoryStream, so the content store cannot"
                    + " perform directory-relative operations. Confinement is enforced by walking the ancestors of"
                    + " each key and then opening it by path, which is a narrower guarantee: an actor able to write"
                    + " inside [" + root + "] could exchange a directory for a symbolic link between the two steps.",
                    MODULE);
        }
    }

    /**
     * Content located inside the storage root, together with the open directory it is reached through.
     *
     * <p>Every operation of this provider goes through one of these. Holding the directory open for
     * the duration of the operation is what the confinement guarantee rests on, so an instance owns
     * descriptors and must be closed - which is also why nothing outside this class ever sees one.
     *
     * <p>Three states, and the operations below behave differently in each:
     * <ul>
     * <li>{@code directory} non-null: the ordinary case, and every operation is descriptor-relative.</li>
     * <li>{@code directory} null and {@code parentMissing} false: the documented fallback for a
     * platform with no {@link SecureDirectoryStream}, where operations are path-based and the ancestor
     * walk has already been performed.</li>
     * <li>{@code parentMissing} true: an intervening directory does not exist and none was being
     * created, so nothing is stored under this key and no operation has anything to act on.</li>
     * </ul>
     */
    private final class Location implements AutoCloseable {

        private final Path target;
        private final Path name;
        private final SecureDirectoryStream<Path> directory;
        private final boolean parentMissing;
        private final List<DirectoryStream<Path>> opened;

        /**
         * Constructs a located key.
         *
         * @param target the resolved absolute path
         * @param name the final component of the key
         * @param directory the open directory holding it, or null in the fallback and absent-parent states
         * @param parentMissing whether an intervening directory does not exist
         * @param opened every directory handle the descent opened, to be closed together
         */
        Location(Path target, Path name, SecureDirectoryStream<Path> directory, boolean parentMissing,
                List<DirectoryStream<Path>> opened) {
            this.target = target;
            this.name = name;
            this.directory = directory;
            this.parentMissing = parentMissing;
            this.opened = opened;
        }

        /**
         * Reads the content's own attributes without following a link.
         *
         * @return the attributes, or {@code null} when nothing is stored here
         * @throws IOException if they cannot be read for any other reason
         */
        private BasicFileAttributes attributesOrNull() throws IOException {
            if (parentMissing) {
                return null;
            }
            if (directory == null) {
                return readAttributes(target);
            }
            try {
                return directory.getFileAttributeView(name, BasicFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS).readAttributes();
            } catch (NoSuchFileException absent) {
                Debug.logVerbose(absent, "The filesystem content store holds nothing at [" + relative(target) + "]",
                        MODULE);
                return null;
            }
        }

        /**
         * Requires that the key holds a regular file, reporting absence the way the contract requires.
         *
         * @return the attributes read while checking, so a caller needing the size does not read them
         *     a second time and risk disagreeing with this check
         * @throws GeneralException if something that is not a regular file is stored here
         * @throws FileNotFoundException if nothing is stored here
         * @throws IOException if the attributes cannot be read
         */
        private BasicFileAttributes requireRegularFile() throws GeneralException, IOException {
            BasicFileAttributes attributes = attributesOrNull();
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
         * Opens the content for reading, relative to the directory this location holds open.
         *
         * @return the stream to read from, which the caller closes
         * @throws IOException if it cannot be opened, including because it is no longer there
         */
        private InputStream openForRead() throws IOException {
            if (directory == null) {
                return Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS);
            }
            return Channels.newInputStream(directory.newByteChannel(name,
                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)));
        }

        /**
         * Opens the content for rewriting in place, truncating it, relative to the directory this
         * location holds open.
         *
         * <p>Without {@code CREATE}, so it writes to the file that is already there or to nothing at
         * all, and with {@code NOFOLLOW_LINKS}, so it cannot write through a link.
         *
         * @return the stream to write to, which the caller closes
         * @throws IOException if it cannot be opened
         */
        private OutputStream openForRewrite() throws IOException {
            if (directory == null) {
                return Files.newOutputStream(target, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            }
            return Channels.newOutputStream(directory.newByteChannel(name,
                    Set.of(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                            LinkOption.NOFOLLOW_LINKS)));
        }

        /**
         * Removes the content, relative to the directory this location holds open.
         *
         * @throws IOException if it cannot be removed for a reason other than being gone already
         */
        private void removeFile() throws IOException {
            if (directory == null) {
                Files.deleteIfExists(target);
                return;
            }
            try {
                directory.deleteFile(name);
            } catch (NoSuchFileException removedMeanwhile) {
                // Idempotent, exactly as the contract requires: another writer removed it first.
                Debug.logVerbose(removedMeanwhile, "The filesystem content store key [" + relative(target)
                        + "] was already removed", MODULE);
            }
        }

        @Override
        public void close() {
            closeAll(opened);
            opened.clear();
        }
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
     * Reports content that does not fit inside the configured ceiling.
     *
     * <p>The thrown message names the ceiling and an opaque reference, and deliberately not the content's
     * location: an {@link IOException} raised while serving content reaches the rendered page, and the
     * path a deployment keeps its content at is not an end user's to know (CWE-200). The location, the
     * size and the ceiling are logged beside the same reference, so an operator joins the report a user
     * quotes to the file that produced it. This is the posture the object-store provider already takes
     * for the same refusal, and the two are deliberately identical: whether a path or a bucket backs the
     * content must not change what a reader is told.
     *
     * @param target the resolved path, named relative to the root, for the log
     * @param size the size as far as it is known, for the log
     * @param limit the ceiling that was exceeded
     * @return the exception to throw
     */
    private IOException oversized(Path target, String size, long limit) {
        String reference = reference();
        Debug.logError("Content store refusal [" + reference + "]: content at [" + relative(target) + "] is " + size
                + " bytes, over the " + ContentStoreFactory.MAX_OBJECT_SIZE_PROPERTY + " ceiling of " + limit
                + "; content this large has to be streamed rather than read whole", MODULE);
        return new IOException("The requested content is larger than this instance may read in one piece."
                + " Reference [" + reference + "].");
    }

    /**
     * Mints the opaque reference that joins a report an end user can see to the log line that explains it.
     *
     * @return a reference that identifies one refusal and describes nothing about the deployment
     */
    private static String reference() {
        return UUID.randomUUID().toString();
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
        move(staging, target, Files::move);
    }

    /**
     * Moves staged content onto its destination, atomically where the filesystem supports it.
     *
     * <p>The move itself is a parameter, and package-private, for one reason: whether the fallback works
     * cannot otherwise be established. Every mainstream Unix filesystem moves atomically, so on the
     * platforms this is built and tested on the atomic form never fails and the fallback is code that
     * has never run. Handing the move in lets the refusal be produced deliberately. A deployment always
     * passes {@link Files#move}, so what runs in production is what the delegating overload above calls.
     *
     * @param staging the staged content
     * @param target the destination
     * @param mover how the move is performed
     * @throws IOException if the move fails for a reason other than not being atomic
     */
    void move(Path staging, Path target, Mover mover) throws IOException {
        try {
            mover.move(staging, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Debug.logVerbose(notAtomic, "This filesystem cannot move atomically, so content is replaced with a"
                    + " plain move at [" + relative(target) + "]", MODULE);
            mover.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * How staged content is moved onto its destination.
     *
     * <p>{@link Files#move} in every deployment; substituted only by this package's own test, and only
     * to produce the refusal a filesystem without atomic renames would produce.
     */
    @FunctionalInterface
    interface Mover {

        /**
         * Moves one entry onto another.
         *
         * @param from the entry to move
         * @param to where it is moved to
         * @param options how the move is performed
         * @throws IOException if the move fails
         */
        void move(Path from, Path to, CopyOption... options) throws IOException;
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
