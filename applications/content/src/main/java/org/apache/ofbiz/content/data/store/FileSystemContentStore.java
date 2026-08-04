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
import java.nio.channels.SeekableByteChannel;
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
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
 * that is not a regular file - a symbolic link, a directory, a device - is refused. Creating a missing
 * level of the tree is the one operation the JDK offers no descriptor-relative form of, so it is done by
 * path and then the created directory is proved, by file identity, to be the entry the open parent holds
 * under that name - an ancestor exchanged while a key was being reached is refused rather than written
 * through. See {@link #createLevel}.
 *
 * <p>A platform whose {@link java.nio.file.FileSystem} does not hand out a
 * {@link SecureDirectoryStream} - which is a documented optional capability - cannot offer
 * descriptor-relative operations at all. There the provider falls back to the ancestor walk followed
 * by a path-based open, logs that it has done so once, and accepts the narrower guarantee, because
 * refusing to serve content on such a platform would be a worse answer than serving it with the
 * protection the platform can actually provide. Every mainstream Unix filesystem supplies a secure
 * stream.
 *
 * <p><strong>Every write is staged first, and only then does it touch anything stored.</strong>
 * Content is always written to a temporary file created {@code rw-------} in the destination's own
 * directory, so it is private from the instant it exists and so a source that turns out to be unusable -
 * a stream that does not hold the length it declared, a read that fails part way - is refused with the
 * stored content untouched. That staged copy is then MOVED onto the
 * destination - one rename, whether or not anything was stored under the key - so the content a reader
 * opens is complete or is the whole of what was there before, and never a mixture of the two. In this
 * provider the storage tree *is* the deployment's existing content tree (plan section 0.6.3), so what a
 * rename would otherwise take away is kept explicitly: the live file's POSIX permissions are read before
 * anything is staged and applied to the staging entry, so the published file carries the mode the
 * deployment's own upload path produced. Directories this provider creates are created {@code rwx------}.
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
        try {
            store(key, out -> copyExactly(content, out, length, key));
        } catch (ExactLengthMismatch mismatch) {
            // Normalised to the type the contract declares. A stream that does not hold exactly the length
            // it was declared with is the CALLER'S mistake, not the store failing, and every operation of
            // this contract tells the two apart - so it must not arrive as an IOException. Nothing was
            // stored: the mismatch is established while writing the STAGING entry, which store() then
            // removes, so a live file at this key is exactly what it was.
            throw new GeneralException(mismatch.getMessage(), mismatch);
        }
    }

    /**
     * Writes a payload to the one path a key names, whichever source the payload comes from.
     *
     * <p>The two {@code put} overloads differ only in where their bytes come from, so the decision
     * between rewriting an existing file and publishing a new one is made once, here.
     *
     * <p><strong>The payload is always written to a private staging entry FIRST, and the live file is
     * only touched once that staged copy is complete.</strong> This is what makes the exact-length
     * contract safe: a stream that turns out not to hold exactly the length it declared is refused
     * while the staging entry is being written, so a live file at this key is left exactly as it was.
     * Truncating the live file and transferring into it - which is what this did - destroyed the stored
     * content before the source was known to be usable, so a short or long stream left the key holding
     * partial content that read back as complete. It also let a concurrent reader holding the file open
     * be served the same region twice, because each truncate reset the offset a writer filled from
     * while the reader's own offset stayed where it was.
     *
     * <p><strong>The staged copy then becomes the stored content by ONE ATOMIC RENAME, whether or not
     * anything was stored under the key.</strong> A rename either has happened or has not, so a
     * concurrent reader opens either the whole of the old content or the whole of the new content and
     * never a mixture of the two. This provider used to rewrite an existing file IN PLACE, to preserve
     * that file's inode, modification time and permissions; the cost was that a reader holding the file
     * open while a replacement was written observed old bytes and new bytes interleaved in one read, and
     * a reader that opened it mid-replacement could observe a document that never existed (CWE-362).
     * Content served from here reaches end users, so a torn document is not an acceptable outcome and
     * atomicity wins.
     *
     * <p>What preservation the in-place rewrite gave is kept where it can be: the LIVE file's POSIX
     * permissions are read before anything is staged and applied to the staging entry, so the renamed
     * file carries the mode the deployment's own upload path produced rather than this provider's
     * owner-only staging mode. The inode changes and the modification time becomes the moment of the
     * write - the latter is equally true of an in-place rewrite, and nothing in this provider, in
     * {@code DataResourceWorker} or in the {@code DataResource} model depends on the inode. A reader
     * that already had the old file open keeps reading the old content to its end, which is a consistent
     * snapshot rather than a fault.
     *
     * <p>A concurrent delete between the check and the rename cannot turn a write that was asked for
     * into a failure: the rename stores the content either way.
     *
     * @param key the provider-relative storage key
     * @param payload the content to write
     * @throws GeneralException if the key is unusable, something that is not a regular file occupies
     *     the target, or owner-only permissions cannot be established for the staging entry
     * @throws IOException if the content cannot be written
     */
    private void store(String key, Payload payload) throws GeneralException, IOException {
        try (Location location = locate(key, true)) {
            BasicFileAttributes existing = location.attributesOrNull();
            if (existing != null && !existing.isRegularFile()) {
                throw new GeneralException("The filesystem content store refuses to write ["
                        + relative(location.target) + "] because something that is not a regular file already"
                        + " occupies it");
            }
            // Read BEFORE the payload is staged, so the mode being preserved is the mode the content had
            // when this write began rather than one a concurrent writer established in the meantime.
            Set<PosixFilePermission> storedPermissions = existing == null ? null : location.storedPermissions();
            Staged staged = stage(location, payload);
            boolean consumed = false;
            try {
                if (storedPermissions != null) {
                    // The staged entry carries the LIVE file's mode onto the destination, which is what keeps
                    // the atomic rename below from replacing the deployment's own upload permissions with this
                    // provider's owner-only staging mode.
                    stagedPermissions(location, staged, storedPermissions);
                }
                // A concurrent delete between the check above and this line does not matter: the move stores
                // the content either way, which is what a caller that asked for a write is owed, and is what
                // the plain create-or-truncate open this provider stands in for would have done.
                interleaveBeforeOpen();
                promote(location, staged);
                consumed = true;
            } finally {
                if (!consumed) {
                    discard(location, staged);
                }
            }
        }
    }

    /**
     * Writes the whole payload to a private staging entry beside its destination, and reports where.
     *
     * <p>Staged before anything live is touched, and created {@code rw-------} so the content is private
     * from the instant it exists rather than from the instant the write finishes. A payload that fails
     * part way - a stream that does not hold the length it declared, a source that cannot be read - is
     * refused HERE, with the staging entry removed and the stored content untouched.
     *
     * @param location the located destination, whose own directory the entry is created in
     * @param payload the content to write
     * @return the staged copy
     * @throws GeneralException if owner-only permissions cannot be established
     * @throws IOException if the content cannot be produced or written
     */
    private Staged stage(Location location, Payload payload) throws GeneralException, IOException {
        Staged staged = createStaging(location);
        boolean written = false;
        try {
            try (OutputStream out = staged.openForWrite(location)) {
                payload.writeTo(out);
            }
            written = true;
            return staged;
        } finally {
            if (!written) {
                discard(location, staged);
            }
        }
    }

    /**
     * A completed, private staging entry the stored content is produced from.
     *
     * <p>One name for the two forms a staging entry can take, so {@link #store} decides once what to do
     * with it rather than branching on the platform at every step. In the ordinary case it is a
     * single-component name inside the directory the location holds open, and every operation on it is
     * descriptor-relative; in the documented no-{@link SecureDirectoryStream} fallback it is an absolute
     * path beside the destination, where the ancestor walk has already been performed.
     *
     * @param name the single-component staging name, when the entry is descriptor-relative
     * @param path the absolute staging path, when the platform offers no secure directory stream
     */
    private record Staged(Path name, Path path) {

        /**
         * Opens the staging entry for writing, truncating it.
         *
         * @param location the destination this entry was created beside
         * @return the stream to write to, which the caller closes
         * @throws IOException if it cannot be opened
         */
        private OutputStream openForWrite(Location location) throws IOException {
            if (path != null) {
                return Files.newOutputStream(path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            }
            return Channels.newOutputStream(location.directory.newByteChannel(name,
                    Set.of(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                            LinkOption.NOFOLLOW_LINKS)));
        }

    }

    /**
     * Creates the private staging entry a write is produced through, beside its destination.
     *
     * <p>Beside the destination rather than in a temporary directory, so the move onto the destination
     * stays within one filesystem and can be a rename. Created {@code rw-------} so the content is
     * private from the instant it exists.
     *
     * @param location the located destination
     * @return the created, empty staging entry
     * @throws GeneralException if owner-only permissions cannot be established
     * @throws IOException if it cannot be created
     */
    private Staged createStaging(Location location) throws GeneralException, IOException {
        if (location.directory == null) {
            Path parent = location.target.getParent();
            return new Staged(null, createStagingFile(parent == null ? root : parent));
        }
        return new Staged(createStagingEntry(location), null);
    }

    /**
     * Removes a staging entry, reporting rather than propagating a failure to do so.
     *
     * <p>Reported and swallowed because it is always the cleanup half of an operation that has already
     * decided its own outcome: turning a failure to remove a temporary file into the reported result
     * would hide either the refusal that is being propagated or a write that actually succeeded.
     *
     * @param location the destination the entry was created beside
     * @param staged the entry to remove
     */
    private void discard(Location location, Staged staged) {
        try {
            if (staged.path() != null) {
                Files.deleteIfExists(staged.path());
            } else {
                location.directory.deleteFile(staged.name());
            }
        } catch (IOException | RuntimeException e) {
            Debug.logWarning("A filesystem content store staging entry beside [" + relative(location.target)
                    + "] could not be removed: " + e.getClass().getName(), MODULE);
        }
    }

    /**
     * Applies the live content's permissions to the staging entry that is about to replace it.
     *
     * <p>Descriptor-relative where the platform allows it, so the change cannot be redirected by an
     * exchange of an ancestor; through the staging entry's own path in the documented
     * no-{@link SecureDirectoryStream} fallback, where the ancestor walk has already been performed.
     *
     * <p>A failure to apply them is REPORTED AND ACCEPTED rather than propagated. The staging entry was
     * created {@code rw-------}, so the only consequence is that the published file is more private than
     * the one it replaced, never less - and refusing the whole write because a mode could not be copied
     * would turn a cosmetic loss into an outage.
     *
     * @param location the located destination
     * @param staged the completed staging entry
     * @param permissions the permissions the stored content had
     */
    private void stagedPermissions(Location location, Staged staged, Set<PosixFilePermission> permissions) {
        try {
            PosixFileAttributeView view = staged.path() != null
                    ? Files.getFileAttributeView(staged.path(), PosixFileAttributeView.class,
                            LinkOption.NOFOLLOW_LINKS)
                    : location.directory.getFileAttributeView(staged.name(), PosixFileAttributeView.class,
                            LinkOption.NOFOLLOW_LINKS);
            if (view != null) {
                view.setPermissions(permissions);
            }
        } catch (IOException | RuntimeException e) {
            Debug.logWarning("The permissions of the content at [" + relative(location.target) + "] could not be"
                    + " carried onto its replacement, which is therefore stored with this provider's owner-only"
                    + " mode: " + e.getClass().getName(), MODULE);
        }
    }

    /**
     * Moves a completed staged copy onto a destination that holds nothing or replaces what is there.
     *
     * <p>One rename, within one directory, so a concurrent reader never observes a partially written new
     * file and never a mixture of an old and a new one: a rename is atomic on every filesystem this
     * provider runs on, and both the descriptor-relative form and the {@link Files#move} fallback replace
     * an existing entry in a single step. Both the staging entry and the move are relative to the
     * directory the location holds open, so neither can be redirected by an exchange of an ancestor.
     *
     * @param location the located destination, open on its own directory
     * @param staged the completed staged copy
     * @throws IOException if it cannot be moved into place
     */
    private void promote(Location location, Staged staged) throws IOException {
        if (staged.path() != null) {
            move(staged.path(), location.target);
            return;
        }
        location.directory.move(staged.name(), location.directory, location.name);
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
                // REFUSED, not worked around. Making the entry private after the fact needs a path, and the
                // only path available here is location.target.resolveSibling(candidate) - an ABSOLUTE path,
                // resolved after the secure descent established confinement through descriptors. Applying a
                // permission change through it re-opens the confinement question the descent had already
                // settled: an ancestor exchanged for a link in between would send the change somewhere
                // outside this provider's root. A SecureDirectoryStream offers no descriptor-relative
                // permission change, so there is no confined way to do it and the operation fails closed
                // instead. Unreachable on every mainstream filesystem, which supports POSIX permissions and
                // takes the branch above; a platform that supplies a SecureDirectoryStream and yet refuses
                // POSIX permissions gets a clear refusal rather than a private document written with
                // whatever the platform default happens to be.
                throw new GeneralException("The filesystem content store refuses to write below ["
                        + relative(location.target) + "] because this filesystem supports descriptor-relative"
                        + " operations but not POSIX permissions, so a staging entry cannot be created private"
                        + " to the OFBiz user without a path-based permission change that would leave the"
                        + " confinement this provider guarantees", notPosix);
            }
        }
        throw new IOException("The filesystem content store could not create a staging entry below ["
                + relative(location.target) + "] after " + STAGING_NAME_ATTEMPTS + " attempts");
    }

    /**
     * A stream that did not hold exactly the number of bytes it was declared with.
     *
     * <p>An {@link IOException} subtype only because {@link Payload#writeTo} can throw nothing else -
     * it is what a producer is allowed to signal. {@link #put(String, InputStream, long)} catches it and
     * rethrows it as the {@link GeneralException} the contract declares, because a length that does not
     * match its stream is the CALLER'S mistake rather than the store failing, and the contract tells
     * those two apart.
     *
     * <p>Raised while the STAGING entry is being written, so stored content is untouched: content that
     * was not there stays absent, and content that was there is exactly what it was.
     */
    private static final class ExactLengthMismatch extends IOException {

        private static final long serialVersionUID = 1L;

        ExactLengthMismatch(String message) {
            super(message);
        }
    }

    /**
     * Copies exactly the declared number of bytes, refusing a stream that yields a different number.
     *
     * <p>A length that does not match the stream is a caller error rather than something to work
     * around, and both directions matter: a short stream would store truncated content under a key
     * that reads back as complete, and a long one would silently drop the rest. It is refused into a
     * STAGING entry, before anything live has been touched - see {@link #store} - so a mismatch never
     * becomes stored content and never destroys content that was already stored.
     *
     * @param content the stream to read from; not closed here, because it belongs to the caller
     * @param out the destination to write to
     * @param length the exact number of bytes to transfer
     * @param key the key being written, named in a refusal
     * @throws ExactLengthMismatch if the stream does not hold exactly {@code length} bytes
     * @throws IOException if the transfer itself fails
     */
    private static void copyExactly(InputStream content, OutputStream out, long length, String key)
            throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        long remaining = length;
        while (remaining > 0L) {
            int wanted = (int) Math.min(buffer.length, remaining);
            int read = content.read(buffer, 0, wanted);
            if (read < 0) {
                throw new ExactLengthMismatch("The content stream for [" + key + "] ended " + remaining
                        + " bytes before the " + length + " bytes it declared, so nothing was stored");
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
        // One byte of lookahead, which is the only way to tell a stream that held exactly the declared
        // length from one holding more. It consumes a byte of the caller's stream, which is
        // inconsequential: the only case in which it finds one is the case that is refused anyway.
        if (content.read() != -1) {
            throw new ExactLengthMismatch("The content stream for [" + key + "] holds more than the " + length
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
                // The ceiling is exceeded only if the read actually FILLED it and there is still more to
                // come. Probing for a further byte after a read that stopped short of the ceiling asks a
                // different question than it appears to: readNBytes stops at end of file, and in a shared
                // tree the file can be rewritten in place between that end and this probe, so a byte
                // appearing at the old end means the content CHANGED, not that it is too large. Refusing on
                // that made an ordinary concurrent overwrite of a 48 KiB file look like a breach of a 10 MiB
                // ceiling - a refusal an operator could not act on, arriving at random under load.
                if (read.length >= limit && content.read() != -1) {
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
            // Established first, so a directory or a device under this key is refused as such rather than
            // opened, and so absence is reported the way the contract requires.
            location.requireRegularFile();
            // ONE channel, opened NOFOLLOW so the name cannot have become a link, with the length taken
            // from THAT channel.
            //
            // The length used to come from the attributes read a moment earlier, which describe the file
            // that was there when they were read rather than the file this open returned: between the two a
            // concurrent writer can replace or extend the content, and the caller would then hold a stream
            // over one object carrying the length of another - so an HTTP response would declare a
            // Content-Length it cannot satisfy, or would truncate what it sends. SeekableByteChannel.size()
            // is a property of the OPEN channel, so the number reported and the bytes delivered are the same
            // object by construction, and stay so for the life of the stream.
            //
            // Deliberately unbounded: this is the operation content of a size an uploader chose is served
            // through, and it never holds that content in the heap in full.
            SeekableByteChannel channel = location.openChannelForRead();
            ContentStream stream;
            try {
                stream = new ContentStream(closing(Channels.newInputStream(channel), location), channel.size());
            } catch (IOException | RuntimeException failed) {
                channel.close();
                throw failed;
            }
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
     * Reports that this provider does NOT hold content off the instance.
     *
     * <p>Its storage tree IS the deployment's own content tree, at the deployment's own paths, so a
     * service that has written a file under {@code ofbiz.home} has by construction written it into this
     * provider: publishing the same bytes again would only rewrite the file the service just wrote.
     * Behaving exactly as the pre-refactor local filesystem behaved is the whole purpose of this
     * provider.
     *
     * @return {@code false}, always
     */
    @Override
    public boolean holdsContentOffInstance() {
        return false;
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
     * always so or became so a moment ago. Creating a missing level is the one operation that cannot
     * be performed relative to a descriptor, and {@link #createLevel} is what keeps that from
     * weakening the guarantee.
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
        createLevel(directory, step, level, target);
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
         * Reads the POSIX permissions of the content stored here, without following a link.
         *
         * <p>Read so that {@link #store} can carry them onto the staging entry it renames into place, which
         * is what keeps an atomic replacement from changing the mode the deployment's own upload path
         * produced. Answers null when the platform cannot express POSIX permissions or when the content is
         * no longer there - in both cases the write proceeds with the staging entry's owner-only mode, which
         * is the private default and never a widening.
         *
         * @return the stored permissions, or null when there are none to preserve
         * @throws IOException if the attributes cannot be read for a reason other than absence
         */
        private Set<PosixFilePermission> storedPermissions() throws IOException {
            try {
                PosixFileAttributeView view = directory == null
                        ? Files.getFileAttributeView(target, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                        : directory.getFileAttributeView(name, PosixFileAttributeView.class,
                                LinkOption.NOFOLLOW_LINKS);
                return view == null ? null : view.readAttributes().permissions();
            } catch (NoSuchFileException removedMeanwhile) {
                return null;
            } catch (UnsupportedOperationException notPosix) {
                return null;
            }
        }

        /**
         * Requires that the key holds a regular file, reporting absence the way the contract requires.
         *
         * @return the attributes read while checking, for a caller that needs them; note that they
         *     describe the file as it was when they were read, so a caller that hands a length to a
         *     consumer must take it from the channel it opens instead - see {@link #openChannelForRead()}
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
            return Channels.newInputStream(openChannelForRead());
        }

        /**
         * Opens the content for reading as a CHANNEL, relative to the directory this location holds
         * open, so that a caller needing the length can take it from the same object it will read.
         *
         * @return the open channel, which the caller closes
         * @throws IOException if it cannot be opened, including because it is no longer there
         */
        private SeekableByteChannel openChannelForRead() throws IOException {
            if (directory == null) {
                return Files.newByteChannel(target, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
            }
            return directory.newByteChannel(name, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
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
     * Creates the one missing level a descent is standing on, and proves it is the level the descent
     * holds open before anything is written through it.
     *
     * <p>A directory is the one thing this provider cannot create relative to a descriptor:
     * {@link SecureDirectoryStream} exposes no {@code mkdirat}, so the only way to make one is by
     * absolute path - and an absolute path is resolved by NAME, re-opening the confinement question the
     * descent had already settled with descriptors. If an ancestor were exchanged for a symbolic link
     * after it was descended into, a path-based create would follow that link and make a directory
     * somewhere OUTSIDE this provider's root. The re-open that follows would then refuse to descend, so
     * no content was ever written there - but the mutation would already have happened, unreported.
     *
     * <p>Two things narrow that to the point where it can be stated rather than hoped for:
     * <ul>
     *   <li>ONE level, never a chain. The caller walks the tree a level at a time with the parent
     *       already open, so exactly one directory is ever missing here; creating parents recursively
     *       would multiply by-path mutations for no reason.</li>
     *   <li>IDENTITY, checked through the descriptor. The entry the path names and the entry the open
     *       parent holds under the same name are compared by file identity, and anything other than
     *       agreement is refused - so a create that landed outside the tree is reported rather than
     *       silently tolerated.</li>
     * </ul>
     *
     * @param parent the open directory this level is created below
     * @param step the single name being created
     * @param level the absolute path of that name, which is the only way to create it
     * @param target the whole resolved path, named in a refusal
     * @throws GeneralException if the level cannot be created privately, or cannot be shown to be the
     *     one the descent holds open
     * @throws IOException if it cannot be created for any other reason
     */
    private void createLevel(SecureDirectoryStream<Path> parent, Path step, Path level, Path target)
            throws GeneralException, IOException {
        try {
            Files.createDirectory(level, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
        } catch (FileAlreadyExistsException raced) {
            // Something is there now that was not there a moment ago. The expected cause is a concurrent
            // write creating the same level, which is ordinary rather than exceptional, so it is not a
            // failure - but what appeared is not assumed to be a directory of this tree either. The
            // identity check below, and the NOFOLLOW re-open the caller performs after it, decide that.
            Debug.logVerbose(raced, "The content store level [" + step + "] on the way to [" + relative(target)
                    + "] was created concurrently", MODULE);
        } catch (UnsupportedOperationException notPosix) {
            // REFUSED, not worked around, for the same reason a staging entry is - see
            // createStagingEntry. Making the directory private after the fact needs a path-based
            // permission change, which is exactly the confinement question this method exists to keep
            // closed. Unreachable on every mainstream filesystem, all of which support POSIX permissions.
            throw new GeneralException("The filesystem content store refuses to create the level [" + step
                    + "] on the way to [" + relative(target) + "] because this filesystem supports"
                    + " descriptor-relative operations but not POSIX permissions, so the directory cannot be"
                    + " created private to the OFBiz user without a path-based permission change that would"
                    + " leave the confinement this provider guarantees", notPosix);
        }
        requireCreatedLevelIsTheOpenOne(parent, step, level, target);
    }

    /**
     * Refuses a created level that is not the entry the open parent holds under the same name.
     *
     * <p>The two identities are read the two different ways the level can be reached: through the
     * descriptor the descent holds, and by the absolute path the creation used. They agree in every
     * ordinary case, including a concurrent create of the same directory, because both describe the same
     * inode. They disagree only when the name resolved to something else - an ancestor exchanged for a
     * link, so the created directory is outside this provider's root - which is the case that must not
     * pass silently.
     *
     * @param parent the open directory the level was created below
     * @param step the single name that was created
     * @param level the absolute path it was created by
     * @param target the whole resolved path, named in a refusal
     * @throws GeneralException if the two do not describe the same entry, or if this filesystem cannot
     *     report an identity to compare
     * @throws IOException if the attributes cannot be read
     */
    private void requireCreatedLevelIsTheOpenOne(SecureDirectoryStream<Path> parent, Path step, Path level,
            Path target) throws GeneralException, IOException {
        Object throughDescriptor = identityThroughDescriptor(parent, step, target);
        Object byPath = identityByPath(level, step, target);
        if (throughDescriptor == null && byPath == null) {
            // Created and removed again before either could be read. Nothing is there under either route,
            // so there is nothing to refuse on confinement grounds; the caller's re-open reports the
            // missing directory as what it is.
            Debug.logVerbose("The content store level [" + step + "] on the way to [" + relative(target)
                    + "] was removed as soon as it was created", MODULE);
            return;
        }
        if (!Objects.equals(throughDescriptor, byPath)) {
            throw new GeneralException("The filesystem content store refuses [" + relative(target)
                    + "] because the directory created for the level [" + step + "] is not the entry that name"
                    + " holds below the directory this descent has open, so an ancestor was exchanged while"
                    + " this key was being reached and the created directory is outside this provider's"
                    + " storage root; nothing was written and the stray directory was left in place to be"
                    + " inspected");
        }
    }

    /**
     * Reads a level's file identity through the descriptor the descent holds open.
     *
     * @param parent the open directory
     * @param step the single name to read
     * @param target the whole resolved path, named in a refusal
     * @return the identity, or {@code null} if nothing is there
     * @throws GeneralException if this filesystem reports no identity
     * @throws IOException if the attributes cannot be read
     */
    private Object identityThroughDescriptor(SecureDirectoryStream<Path> parent, Path step, Path target)
            throws GeneralException, IOException {
        try {
            return requireIdentity(parent.getFileAttributeView(step, BasicFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS).readAttributes().fileKey(), step, target);
        } catch (NoSuchFileException absent) {
            Debug.logVerbose(absent, "The content store level [" + step + "] is not held by the directory open"
                    + " on the way to [" + relative(target) + "]", MODULE);
            return null;
        }
    }

    /**
     * Reads a level's file identity by the absolute path it was created with.
     *
     * @param level the absolute path
     * @param step the single name it ends in, named in a refusal
     * @param target the whole resolved path, named in a refusal
     * @return the identity, or {@code null} if nothing is there
     * @throws GeneralException if this filesystem reports no identity
     * @throws IOException if the attributes cannot be read
     */
    private Object identityByPath(Path level, Path step, Path target) throws GeneralException, IOException {
        try {
            return requireIdentity(Files.readAttributes(level, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).fileKey(), step, target);
        } catch (NoSuchFileException absent) {
            Debug.logVerbose(absent, "The content store level [" + step + "] is not at the path it was created"
                    + " by on the way to [" + relative(target) + "]", MODULE);
            return null;
        }
    }

    /**
     * Requires that this filesystem reports an identity for an entry, so the two routes to a created
     * level can be compared at all.
     *
     * <p>Fails closed rather than assuming agreement: without an identity the by-path creation cannot be
     * shown to be the entry the descent holds, and this method exists precisely because that has to be
     * shown. Unreachable on the platforms this branch runs on - a {@link SecureDirectoryStream} is
     * supplied by the Unix provider, which reports a device-and-inode identity for every entry - so this
     * refuses a hypothetical platform that offers descriptor-relative operations while withholding the
     * one fact needed to verify them.
     *
     * @param key the identity read, possibly {@code null}
     * @param step the single name it was read for
     * @param target the whole resolved path, named in a refusal
     * @return the identity, never {@code null}
     * @throws GeneralException if there is none
     */
    private Object requireIdentity(Object key, Path step, Path target) throws GeneralException {
        if (key == null) {
            throw new GeneralException("The filesystem content store refuses [" + relative(target)
                    + "] because this filesystem reports no file identity for the level [" + step + "], so a"
                    + " directory it had to create by path cannot be shown to be the directory this descent"
                    + " holds open, and the confinement this provider guarantees cannot be demonstrated");
        }
        return key;
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
