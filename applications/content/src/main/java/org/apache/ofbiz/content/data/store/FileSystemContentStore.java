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
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.FileUtil;
import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.util.EntityUtilProperties;
import org.apache.ofbiz.security.SecurityUtil;

/**
 * Filesystem content storage provider for the Content component.
 *
 * <p>This is the {@code filesystem} provider named by the {@code content.store.provider} property of
 * the {@code content} resource. Content is rooted at the {@code content.upload.path.prefix} location
 * (committed value {@code runtime/uploads}, resolved relative to the {@code ofbiz.home} system
 * property) and is spread over timestamp-named sub-directories, each of which is reused until its
 * observed entry count reaches {@code content.upload.max.files} (committed value {@code 250}, which is
 * also the effective default whenever that property is absent or unusable). The count is observed, not
 * held under a lock, so concurrent uploads may carry a directory past it.
 *
 * <p><strong>This provider is opt-in and is never the default.</strong> The committed value of
 * {@code content.store.provider} is {@code database}; in that mode the pre-existing
 * {@code DataResource} database-storage path is used unchanged and this class is not instantiated at
 * all. Nothing here executes until an operator explicitly selects {@code filesystem}.
 *
 * <p><strong>Operator caveat: {@code filesystem} is not a stateless choice.</strong> Everything this
 * provider writes lands on the disk of the instance that served the request, so in a multi-instance
 * deployment behind a load balancer the {@code content.upload.path.prefix} directory must live on
 * storage shared by every instance and mounted at the same path inside each of them. Where instances
 * cannot share storage, select {@code database} or {@code s3} instead.
 *
 * <p><strong>Writes are all-or-nothing.</strong> Content is never written into the file a reader
 * addresses. Every write goes to a temporary file in the same directory and is then moved onto the
 * target name, atomically where the filesystem supports it, so a reader concurrent with a write sees
 * either the whole previous content or the whole new content, and a write that fails part-way - a full
 * disk, a truncated upload stream, a killed instance - leaves the previous content untouched instead
 * of leaving a half-overwritten file behind.
 *
 * <p><strong>Absence is distinguished from failure.</strong> Every probe reads file attributes rather
 * than asking {@code File.exists()}, because {@code File.exists()} answers {@code false} for a file
 * that is present but unreadable exactly as it does for a file that is not there. Only
 * {@link NoSuchFileException} is reported as absence; a refused permission or a failing filesystem
 * propagates, so a caller can never mistake an unreachable file for content that was never stored.
 *
 * <p><strong>Absolute versus relative upload paths.</strong> {@link #uploadPath(Delegator, boolean)}
 * keeps its two return forms distinct, because the distinction is already persisted in
 * {@code DataResource.objectInfo} values in every existing database and is therefore frozen:
 * <ul>
 *   <li>{@code LOCAL_FILE} and {@code LOCAL_FILE_BIN} content is addressed by an <em>absolute</em>
 *       path, so those callers ask for {@code absolute = true}</li>
 *   <li>{@code OFBIZ_FILE} and {@code OFBIZ_FILE_BIN} content is addressed by a path
 *       <em>relative</em> to the OFBiz home directory, so those callers ask for
 *       {@code absolute = false}</li>
 *   <li>{@code CONTEXT_FILE} and {@code CONTEXT_FILE_BIN} content bypasses the upload path
 *       altogether, being addressed underneath a caller-supplied context root</li>
 * </ul>
 * The two forms are never collapsed into one canonical form. They govern only what the
 * {@code getUploadPath} methods <em>return</em>; they are not the shape of a storage key, which the
 * next paragraph defines.
 *
 * <p><strong>Key resolution, the two allow lists, and confinement.</strong> A storage key handed to one
 * of the {@link ContentStore} operations is a path in exactly the shape held by
 * {@code DataResource.objectInfo}, because that is the shape the {@code DataResourceWorker} seam has to
 * hand a provider: rows already persisted in every existing database address content through it, so a
 * provider that understood some other shape could not read the content a deployment already has. A key
 * that already begins with the OFBiz home directory is taken as the absolute form and is validated
 * against the {@code content.data.local.file.allowed.paths} allow list; every other key is taken as the
 * relative form, is prefixed with the OFBiz home directory, and is validated against the
 * {@code content.data.ofbiz.file.allowed.paths} allow list. Both are then confined twice, and each test
 * closes something the other does not: the boundary check the rest of the Content component uses,
 * which requires canonical containment and lexical containment of the normalized paths together so
 * that a traversal is refused even where the tail of the path does not exist yet, and a purely
 * canonical containment test against the declared roots, in which symbolic links are resolved on both
 * sides so that a link planted inside the tree pointing out of it is refused rather than accepted. A
 * deployment whose content really does live behind such a link declares the far end through
 * {@code content.store.filesystem.allowed.roots} rather than the test being relaxed for everyone.
 *
 * <p><strong>The mutation surface is narrower than the read surface.</strong> A read may address
 * anything the two allow lists admit inside the home directory, because that is exactly what the
 * Content component could already read. A <em>change</em> may not: {@link #put(String, byte[])},
 * {@link #put(String, InputStream, long)} and {@link #delete(String)} accept only a key that resolves
 * inside the {@code content.upload.path.prefix} tree, and refuse an absolute key, a traversal
 * component, a location URL and a control character outright. Otherwise a key arriving from request
 * data could choose its own root and overwrite or remove application state that has nothing to do with
 * uploaded content. See {@link #resolveMutationTarget(String)}.
 *
 * <p>Because a key is a location and not an identifier, translating a {@code DataResource.objectInfo}
 * value into a key belongs to the {@code DataResourceWorker} seam that calls this provider, which is
 * where the legacy absolute and home-relative forms listed above are understood; doing it there rather
 * than here is what lets one key mean the same thing to this provider and to the S3 provider.
 *
 * <p><strong>Operator caveat: put shared storage behind a mount point, not a symbolic link.</strong>
 * A mounted volume does not change a canonical path, so mounting shared storage on
 * {@code runtime/uploads} - or anywhere else under the home directory - is fully supported. Replacing
 * part of that tree with a <em>symbolic link</em> pointing outside the declared roots is not: the
 * canonical test resolves the link, sees content leaving the confinement and refuses the key, because
 * a link that redirects storage out of the tree is indistinguishable from the traversal the test
 * exists to stop. A deployment whose content really does live behind such a link names the far end
 * explicitly in {@code content.store.filesystem.allowed.roots} instead of the test being relaxed for
 * everyone. Below the upload root the rule is stricter still: a directory component that is a
 * symbolic link is refused outright, whichever way it points.
 *
 * <p>Instances carry no per-request state, so one instance is safely shared by every request thread.
 */
public final class FileSystemContentStore implements ContentStore, ContentUploadLocation, LocalContentStore {

    private static final String MODULE = FileSystemContentStore.class.getName();

    private static final String PROPERTY_RESOURCE = ContentStoreSupport.PROPERTY_RESOURCE;

    private static final String UPLOAD_PATH_PREFIX_PROPERTY = "content.upload.path.prefix";

    private static final String UPLOAD_MAX_FILES_PROPERTY = "content.upload.max.files";

    /** The property declaring roots, beyond {@code ofbiz.home}, that content may canonically resolve inside. */
    private static final String ALLOWED_ROOTS_PROPERTY = "content.store.filesystem.allowed.roots";

    /** The property capping how many upload sub-directories are examined while choosing one to reuse. */
    private static final String MAX_UPLOAD_DIRECTORIES_PROPERTY = "content.store.filesystem.max.upload.directories";

    /** The top level upload location used when {@code content.upload.path.prefix} yields nothing. */
    private static final String DEFAULT_UPLOAD_PATH_PREFIX = "runtime/uploads";

    /** The number of entries per upload sub-directory used when the property yields nothing usable. */
    private static final long DEFAULT_MAX_FILES = 250L;

    /** The largest per-directory entry cap that may be configured. */
    private static final long MAX_FILES_LIMIT = 1_000_000L;

    /** How many upload sub-directories are examined when the property yields nothing usable. */
    private static final int DEFAULT_MAX_UPLOAD_DIRECTORIES = 5000;

    /** The largest number of upload sub-directories that may be configured for examination. */
    private static final int MAX_UPLOAD_DIRECTORIES_LIMIT = 1_000_000;

    /** How many distinct names are tried before giving up on creating a fresh upload sub-directory. */
    private static final int DIRECTORY_CREATE_ATTEMPTS = 64;

    /** The longest a directory name may be and still be read as a millisecond timestamp. */
    private static final int MAX_TIMESTAMP_DIGITS = 19;

    /** The separator declared roots are listed with in {@code content.store.filesystem.allowed.roots}. */
    private static final String ROOT_SEPARATOR = ",";

    /** Prefix of the temporary file a write is staged in, so a stray one is identifiable. */
    private static final String STAGING_PREFIX = ".ofbiz-content-";

    private static final String STAGING_SUFFIX = ".part";

    /** Stable code reported when the upload-directory scan stopped at its configured bound. */
    private static final String EVENT_UPLOAD_SCAN_BOUNDED = "CONTENT-STORE-FS-UPLOAD-SCAN-BOUNDED";

    /** Stable code reported when a fresh upload sub-directory name was already taken. */
    private static final String EVENT_UPLOAD_DIRECTORY_TAKEN = "CONTENT-STORE-FS-UPLOAD-DIRECTORY-TAKEN";

    /** Stable code reported when the filesystem cannot replace content atomically. */
    private static final String EVENT_ATOMIC_MOVE_UNAVAILABLE = "CONTENT-STORE-FS-ATOMIC-MOVE-UNAVAILABLE";

    /** Stable code reported when a removal found nothing to remove. */
    private static final String EVENT_DELETE_NOOP = "CONTENT-STORE-FS-DELETE-NOOP";

    /** The only permissions a stored content file may carry: readable and writable by its owner alone. */
    private static final Set<PosixFilePermission> OWNER_ONLY_FILE =
            PosixFilePermissions.fromString("rw-------");

    /** The only permissions a created content directory may carry: usable by its owner alone. */
    private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY =
            PosixFilePermissions.fromString("rwx------");

    /** {@link #OWNER_ONLY_FILE} as a creation attribute, so a file is never briefly world readable. */
    private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY_FILE_ATTRIBUTE =
            PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE);

    /** {@link #OWNER_ONLY_DIRECTORY} as a creation attribute, applied atomically by {@code mkdir}. */
    private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY_DIRECTORY_ATTRIBUTE =
            PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY);

    /**
     * Reads every setting from the {@code content} resource at the moment it is used, so an instance
     * does not have to be rebuilt when configuration changes.
     */
    public FileSystemContentStore() {
    }

    /**
     * Emits content into the staging channel of an atomic write.
     *
     * <p>Exists so that the staging-file-then-move dance is written once and is therefore identical
     * for the in-memory and the streaming form of {@code put}.
     *
     * <p>A channel rather than a stream, because the in-memory form goes through
     * {@link #writeFully(FileChannel, byte[], String)}: a channel write is only contracted to accept
     * some of what it is offered, and that is the one place a partial write can be noticed rather than
     * silently published as truncated content.
     */
    @FunctionalInterface
    private interface ContentEmitter {
        /**
         * Writes the whole content to the supplied channel, which the caller forces and closes.
         *
         * @param sink the staging channel to write to
         * @throws IOException if the content cannot be produced or cannot be written
         */
        void emitTo(FileChannel sink) throws IOException;
    }

    /**
     * Stores the supplied content at the location addressed by the supplied key, creating the file
     * when it is absent and replacing its content in full when it is already there.
     *
     * <p>The replacement is all-or-nothing: the bytes are staged in a temporary file beside the target
     * and moved onto it, so a concurrent reader never observes a partially written file and a failed
     * write leaves the previous content intact. Any missing intermediate directory is created on
     * demand, so a caller never has to prepare the store first; each is created readable and writable
     * by its owner alone. Failure to create one is reported rather than warned about, because a write
     * that has nowhere to land must not look as though it succeeded.
     *
     * <p>This is the bounded convenience form: content longer than
     * {@code content.store.max.memory.bytes} is refused rather than handled, because the caller has
     * already materialised it in the heap by the time it arrives here. Use
     * {@link #put(String, InputStream, long)} for content that is legitimately large.
     *
     * <p><strong>Confined to the upload root.</strong> Unlike a read, a write is accepted only for a key
     * that resolves inside the {@code content.upload.path.prefix} tree. See
     * {@link #resolveMutationTarget(String)} for why the mutation surface is narrower than the read
     * surface and what that rules out.
     *
     * <p><strong>Written atomically, and never through a symbolic link.</strong> The content is written
     * to a fresh temporary file in the destination directory, created with owner-only permissions and
     * with {@code CREATE_NEW} semantics so it cannot be an existing object, and is then renamed onto the
     * destination. Three properties follow, and each of them closes a real failure. The rename is atomic,
     * so a reader never sees a half-written file and a failed write leaves the previous content intact
     * rather than truncated. The permissions are set as the file is created rather than afterwards, so
     * there is no window in which uploaded content is readable by every local principal - which is what a
     * {@code umask}-dependent {@code 0644} amounts to on a shared host. And the rename replaces the
     * destination <em>name</em>: if the destination is a symbolic link planted by another local
     * principal, the link is replaced rather than followed, so a write can never be redirected through
     * one.
     *
     * @param key the storage key to write to, relative to the upload root; must be neither null nor
     *     empty and must not escape that root
     * @param data the complete content to store; must not be null, but may be a zero-length array
     *     in order to store empty content
     * @throws GeneralException if the key is null, empty, carries a control character or a traversal
     *     component, is absolute, resolves outside the upload root, if a directory component of the
     *     key has been replaced by a symbolic link, or if the content is null or longer than the
     *     in-memory ceiling
     * @throws IOException if a storage directory cannot be created or is occupied by something that
     *     is not a directory, or if the content cannot be written, forced to the device or published
     */
    @Override
    public void put(String key, byte[] data) throws GeneralException, IOException {
        ContentStoreSupport.requireStorableContent(key, data, ContentStoreSupport.maxInMemoryBytes());
        writeAtomically(resolveMutationTarget(key), key, channel -> writeFully(channel, data, key));
    }

    /**
     * Stores exactly {@code length} bytes read from the supplied stream at the location addressed by
     * the supplied key, creating the file when it is absent and replacing its content in full when it
     * is already there.
     *
     * <p>The transfer goes straight to a staging file, so content of any size is written without being
     * held in the heap, and the staging file is moved onto the target only once the whole declared
     * length has been transferred. A stream that ends early therefore leaves the previous content
     * intact rather than replacing it with a truncated copy.
     *
     * <p>The same confinement, staging, owner-only mode and link handling apply as for
     * {@link #put(String, byte[])}: both forms go through one write path.
     *
     * @param key the storage key to write to, in the shape held by {@code DataResource.objectInfo};
     *     must be neither null nor empty and must resolve inside the upload root
     * @param content the stream to transfer content from; must not be null, and is neither closed nor
     *     rewound by this method
     * @param length the exact number of bytes to read from the stream and store; must not be negative
     * @throws GeneralException if the key is null, empty, carries a control character or a traversal
     *     component, is absolute, resolves outside the upload root, if a directory component of the
     *     key has been replaced by a symbolic link, if the stream is null, or if the length is
     *     negative
     * @throws IOException if the stream cannot be read, if it ends before {@code length} bytes have
     *     been read, or if the content cannot be written, forced to the device or published
     */
    @Override
    public void put(String key, InputStream content, long length) throws GeneralException, IOException {
        ContentStoreSupport.requireStorableStream(key, content, length);
        writeAtomically(resolveMutationTarget(key), key,
                channel -> ContentStoreSupport.transferExactly(content, Channels.newOutputStream(channel),
                        length, key));
    }

    /**
     * Reads the whole content addressed by the supplied key into memory.
     *
     * <p><strong>Bounded.</strong> This method materialises the whole file in the heap, so a file larger
     * than {@code content.store.max.get.bytes} (32 MiB by default) is refused rather than read: on a
     * shared upload volume a single oversized file is otherwise enough to exhaust the heap of whichever
     * instance served the request. The file's attributes are read first, so a key addressing something
     * that is not a regular file is reported as absence before any content is opened. The size is then
     * taken from the <em>opened</em> file rather than from that separate {@code stat}, so the size that
     * is checked and the bytes that are read belong to the same object even if the name is replaced in
     * between, and the ceiling is enforced again while reading in case the file grows underneath the
     * read. Use {@link #openStream(String)} for content that is legitimately large: it streams, and is
     * not subject to the ceiling.
     *
     * @param key the storage key to read, a path relative to the configured upload root; must be
     *     neither null nor blank
     * @return the complete stored content, never null; a zero-length array means the stored content
     *     is empty
     * @throws GeneralException if the key is null or empty, or is rejected by the allow list or by the
     *     confinement check
     * @throws IOException if the content cannot be read, if it is larger than the ceiling named by
     *     {@code content.store.max.get.bytes}, or - as a {@link FileNotFoundException} - when the key
     *     addresses no regular file, whether because nothing is stored there, because a directory or
     *     another non-content filesystem object sits at the location, or because the content was
     *     removed concurrently, so that null is never returned
     */
    @Override
    public byte[] get(String key) throws GeneralException, IOException {
        Path path = resolvePath(key);
        long limit = ContentStoreUtil.maxWholeReadBytes();
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) {
                throw absent(key);
            }
            try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
                long size = channel.size();
                if (size > limit) {
                    throw new IOException("Content store provider [filesystem] refused to read "
                            + ContentStoreUtil.reference(key) + " in full: it holds " + size + " bytes, more than the "
                            + limit + " bytes allowed by [content.store.max.get.bytes] of resource ["
                            + PROPERTY_RESOURCE + "]. Read it through openStream instead, which streams without"
                            + " materialising it");
                }
                return ContentStoreUtil.readWithin(Channels.newInputStream(channel), limit, "filesystem",
                        ContentStoreUtil.reference(key));
            }
        } catch (NoSuchFileException e) {
            throw absent(key, e);
        }
    }

    /**
     * Opens a fresh stream over the content addressed by the supplied key, positioned at the first
     * byte.
     *
     * <p>Every invocation returns an independent stream. <strong>The caller owns the returned stream
     * and must close it</strong>, ideally with a try-with-resources statement, because it holds an
     * open file handle.
     *
     * @param key the storage key to read, a path relative to the configured upload root; must be
     *     neither null nor blank
     * @return a newly opened stream over the stored content, never null, positioned at the start and
     *     owned by the caller
     * @throws GeneralException if the key is null or empty, or is rejected by the allow list or by
     *     the confinement check
     * @throws IOException if the stream cannot be opened, in particular a
     *     {@link FileNotFoundException} when the key addresses no regular file - whether because
     *     nothing is stored there, because a directory or other non-content filesystem object sits at
     *     the location, or because the content was removed concurrently - so that null is never
     *     returned
     */
    @Override
    public InputStream openStream(String key) throws GeneralException, IOException {
        Path path = resolvePath(key);
        try {
            // Only a regular file holds stored content, and that is established before the stream is
            // opened so that a directory - or anything else get() could not read - is reported as the
            // same absence here rather than as a failure a caller cannot tell apart from a real one.
            if (!Files.readAttributes(path, BasicFileAttributes.class).isRegularFile()) {
                throw absent(key);
            }
            return Files.newInputStream(path, StandardOpenOption.READ);
        } catch (NoSuchFileException e) {
            // the content was removed between the check and the open; the SPI promises the same absence
            // signal for that race as for a key that never addressed anything
            throw absent(key, e);
        }
    }

    /**
     * Reports the exact length in bytes of the content addressed by the supplied key.
     *
     * @param key the storage key to measure, in the shape held by {@code DataResource.objectInfo};
     *     must be neither null nor empty
     * @return the length of the stored content in bytes, never negative
     * @throws GeneralException if the key is null or empty, or is rejected by the allow list or by
     *     the confinement check
     * @throws IOException if the length cannot be established, in particular a
     *     {@link FileNotFoundException} when the key addresses no file
     */
    @Override
    public long size(String key) throws GeneralException, IOException {
        Path path = resolvePath(key);
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) {
                throw absent(key);
            }
            return attributes.size();
        } catch (NoSuchFileException e) {
            throw absent(key, e);
        }
    }

    /**
     * Reports whether the supplied key currently addresses stored content.
     *
     * <p>This is the non-exceptional way to probe the store, and the distinction it draws matters:
     * {@code false} means the key genuinely addresses no file, while a file that exists but cannot be
     * interrogated raises an {@link IOException} instead of being reported as absent.
     *
     * <p>Only a regular file counts as stored content. A directory, or any other filesystem object
     * that {@link #get(String)} could not read as content, is reported as absent, so that a key never
     * looks occupied to this method and then unreadable to the read operations.
     *
     * @param key the storage key to probe, in the shape held by {@code DataResource.objectInfo}; must
     *     be neither null nor empty
     * @return {@code true} if and only if the key addresses an existing regular file
     * @throws GeneralException if the key is null or empty, or is rejected by the allow list or by
     *     the confinement check
     * @throws IOException if the filesystem cannot be interrogated for any reason other than the file
     *     being absent
     */
    @Override
    public boolean exists(String key) throws GeneralException, IOException {
        Path path = resolvePath(key);
        try {
            return Files.readAttributes(path, BasicFileAttributes.class).isRegularFile();
        } catch (NoSuchFileException e) {
            // the one condition that legitimately means "nothing is stored here"; a refused permission
            // or a failing filesystem is deliberately allowed to propagate instead
            return false;
        }
    }

    /**
     * Removes the content addressed by the supplied key.
     *
     * <p>The operation is idempotent: removing a key that addresses no file is a successful no-op
     * rather than an error, so repeated or replayed clean-up is safe.
     *
     * <p><strong>Confined, and never through a symbolic link.</strong> Like {@link #put(String, byte[])}
     * this accepts only a key that resolves inside the {@code content.upload.path.prefix} tree, and the
     * containing directory is re-checked fully resolved immediately beforehand, so a directory component
     * replaced by a link cannot redirect the removal. A directory is refused outright - this store removes
     * content, not structure - and because {@code Files.delete} operates on the name it is given rather
     * than on what that name points at, a symbolic link is itself removed instead of the file at its far
     * end. There is therefore no key at all for which this method can delete something outside the upload
     * root.
     *
     * <p>A key that addresses a directory is refused instead of removed. Removal is the one operation
     * that cannot be taken back, and a directory is never stored content, so a key that
     * {@link #exists(String)} already reports as holding nothing must not be able to delete the
     * directory sitting at that location.
     *
     * @param key the storage key to remove, in the shape held by {@code DataResource.objectInfo}; must be
     *     neither null nor empty, and must resolve inside the upload root
     * @throws GeneralException if the key is null, empty, carries a control character or a traversal
     *     component, is absolute, is rejected by the allow list or by the confinement check, resolves
     *     outside the upload root, or addresses a directory
     * @throws IOException if the file cannot be removed
     */
    @Override
    public void delete(String key) throws GeneralException, IOException {
        Path target = resolveMutationTarget(key);
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            // Nothing to remove, because the containing directory is not there. Reported the same way as an
            // absent file, since the SPI makes removal idempotent.
            Debug.logVerbose(EVENT_DELETE_NOOP + " key [" + ContentStoreUtil.reference(key)
                    + "]: the containing directory is not there, so the removal is a no-op", MODULE);
            return;
        }
        requireContainerInsideUploadRoot(parent.toRealPath(), key);

        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException absent) {
            // NoSuchFileException is the ordinary case here and is the idempotent no-op the SPI requires. Any
            // other read failure is reported the same way rather than being turned into a removal, because a
            // path whose nature cannot be established must not be removed on a guess.
            Debug.logVerbose("Content store provider [filesystem] removed nothing for "
                    + ContentStoreUtil.reference(key) + " [" + absent.getClass().getSimpleName()
                    + "]; the removal is a no-op", MODULE);
            return;
        }
        if (attributes.isDirectory()) {
            throw new GeneralException("Content store provider [filesystem] refused to remove "
                    + ContentStoreUtil.reference(key) + ": it addresses a directory, and this store removes"
                    + " content rather than structure");
        }
        Files.deleteIfExists(target);
    }

    /**
     * Releases the resources this provider holds, of which there are none.
     *
     * <p>A filesystem provider owns no pool, thread or long-lived handle: every operation opens what
     * it needs and hands ownership of any stream to its caller. The method is still implemented, and
     * is still idempotent, because {@code ContentStoreFactory} closes whichever provider is active
     * without knowing which one it is.
     */
    @Override
    public void close() {
    }

    /**
     * Returns the directory the next uploaded file should be placed in, taking the top level location
     * from {@code content.upload.path.prefix} and the per-directory cap from
     * {@code content.upload.max.files}.
     *
     * <p>The most recent sub-directory of the top level location is reused until its observed entry
     * count reaches the cap, at which point a further timestamp-named sub-directory is created. Two
     * properties of this selection are deliberate:
     * <ul>
     *   <li>The scan is <strong>bounded</strong>. At most
     *       {@code content.store.filesystem.max.upload.directories} entries are examined, one at a
     *       time, and the counting of the chosen directory stops at the cap. Nothing accumulates a
     *       list or a sort of the whole directory, so an upload location that has grown to a very
     *       large number of sub-directories cannot turn a single upload into an unbounded amount of
     *       work on a request thread.</li>
     *   <li>Only sub-directories whose name is a millisecond timestamp - the ones this provider
     *       creates - take part. An unrelated directory sharing the upload location is neither
     *       examined nor written into.</li>
     * </ul>
     *
     * @param delegator the delegator used to let the {@code SystemProperty} entity override
     *     {@code content.upload.path.prefix}; may be null, in which case only the property file is
     *     consulted. The per-directory cap is deliberately still read from {@code content.properties}
     *     alone, which is what keeps selecting this provider from changing where content lands
     * @param absolute {@code true} for the absolute path, which is the form {@code LOCAL_FILE} and
     *     {@code LOCAL_FILE_BIN} content is addressed by; {@code false} for the path relative to the
     *     OFBiz home directory, which is the form {@code OFBIZ_FILE} and {@code OFBIZ_FILE_BIN}
     *     content is addressed by
     * @return the path to the directory where the file should be placed
     * @throws GeneralException if the configured location resolves outside every allowed root, or if
     *     the directory to place the file in cannot be established or created
     */
    @Override
    public String uploadPath(Delegator delegator, boolean absolute) throws GeneralException {
        String configured = delegator == null
                ? UtilProperties.getPropertyValue(ContentStoreSupport.PROPERTY_RESOURCE, UPLOAD_PATH_PREFIX_PROPERTY)
                : EntityUtilProperties.getPropertyValue(ContentStoreSupport.PROPERTY_RESOURCE, UPLOAD_PATH_PREFIX_PROPERTY, delegator);
        String initialPath = UtilValidate.isEmpty(configured) ? DEFAULT_UPLOAD_PATH_PREFIX : configured;
        String relativePath = initialPath.startsWith("/") ? initialPath : "/" + initialPath;
        long maxFiles = ContentStoreSupport.boundedLongProperty(UPLOAD_MAX_FILES_PROPERTY, DEFAULT_MAX_FILES, 1L, MAX_FILES_LIMIT);

        // the configured location is confined BEFORE anything is listed or created underneath it, so a
        // prefix carrying traversal is refused rather than acted on and then judged
        String location = ofbizHome() + relativePath;
        String rendered = ContentStoreUtil.reference(location);
        Path parent = confine(toPath(location, rendered), rendered);
        Path selected = selectUploadDirectory(parent, maxFiles);
        if (absolute) {
            return selected.toAbsolutePath().toString().replace('\\', '/');
        }
        return relativePath + "/" + selected.getFileName();
    }

    /**
     * Returns the directory that the next uploaded file should be placed in, taking the top level
     * location from {@code content.upload.path.prefix} and the per-directory cap from
     * {@code content.upload.max.files}.
     *
     * <p>This and the two overloads below are the <strong>frozen legacy surface</strong>. They exist to
     * be at exact parity with {@code DataResourceWorker.getDataResourceContentUploadPath}, right down to
     * carrying no checked exception and to selecting the most recently <em>modified</em> sub-directory
     * rather than the highest-numbered one, so that selecting this provider cannot move where content
     * lands. {@link #uploadPath(Delegator, boolean)} is the storage-contract form and is the one a
     * caller that can report a failure should use.
     *
     * @param absolute {@code true} for the absolute path, which is the form {@code LOCAL_FILE} and
     *     {@code LOCAL_FILE_BIN} content is addressed by; {@code false} for the path relative to the
     *     OFBiz home directory, which is the form {@code OFBIZ_FILE} and {@code OFBIZ_FILE_BIN}
     *     content is addressed by
     * @return the path to the directory where the file should be placed
     */
    public String getUploadPath(boolean absolute) {
        String initialPath = UtilProperties.getPropertyValue(PROPERTY_RESOURCE, UPLOAD_PATH_PREFIX_PROPERTY);
        double maxFiles = UtilProperties.getPropertyNumber(PROPERTY_RESOURCE, UPLOAD_MAX_FILES_PROPERTY);
        if (maxFiles < 1) {
            maxFiles = DEFAULT_MAX_FILES;
        }

        return getUploadPath(initialPath, maxFiles, absolute);
    }

    /**
     * Returns the directory that the next uploaded file should be placed in, letting the given
     * delegator override the top level location from the {@code SystemProperty} entity.
     *
     * <p>The delegator may override {@code content.upload.path.prefix}. The per-directory cap
     * {@code content.upload.max.files} stays classpath-configured and is not looked up through the
     * delegator, which is what keeps selecting this provider from changing where content lands.
     *
     * @param delegator the delegator used to look {@code content.upload.path.prefix} up in the
     *     {@code SystemProperty} entity; the property file value is used when the entity holds none
     * @param absolute {@code true} for the absolute path, which is the form {@code LOCAL_FILE} and
     *     {@code LOCAL_FILE_BIN} content is addressed by; {@code false} for the path relative to the
     *     OFBiz home directory, which is the form {@code OFBIZ_FILE} and {@code OFBIZ_FILE_BIN}
     *     content is addressed by
     * @return the path to the directory where the file should be placed
     */
    public String getUploadPath(Delegator delegator, boolean absolute) {
        String initialPath = EntityUtilProperties.getPropertyValue(PROPERTY_RESOURCE, UPLOAD_PATH_PREFIX_PROPERTY, delegator);
        double maxFiles = UtilProperties.getPropertyNumber(PROPERTY_RESOURCE, UPLOAD_MAX_FILES_PROPERTY);
        if (maxFiles < 1) {
            maxFiles = DEFAULT_MAX_FILES;
        }

        return getUploadPath(initialPath, maxFiles, absolute);
    }

    /**
     * Handles creating sub-directories for file storage; using a max number of files per directory.
     *
     * <p>The most recently modified sub-directory of the top level location is reused until it holds
     * {@code maxFiles} entries or more, at which point a further timestamp-named sub-directory is
     * created. The top level location itself is created when it is not there yet. The sub-directory
     * scan is bounded by {@code content.store.filesystem.max.upload.directories}; see
     * {@link #mostRecentSubdirectory(File)}.
     *
     * @param initialPath the top level location where all files should be stored, relative to the
     *     OFBiz home directory; a missing leading {@code /} is supplied
     * @param maxFiles the max number of files to place in a directory
     * @param absolute {@code true} for the absolute path to the directory, with any backslash
     *     rewritten as a forward slash; {@code false} for the path relative to the OFBiz home
     *     directory
     * @return the path to the directory where the file should be placed
     */
    public String getUploadPath(String initialPath, double maxFiles, boolean absolute) {
        String ofbizHome = System.getProperty("ofbiz.home");

        if (!initialPath.startsWith("/")) {
            initialPath = "/" + initialPath;
        }

        String parentDir = ofbizHome + initialPath;
        File parent = FileUtil.getFile(parentDir);
        File latestDir = null;
        if (parent.exists()) {
            latestDir = mostRecentSubdirectory(parent);
        } else {
            // If the top level location doesn't exist, create it now - owner-only, and with the mode applied
            // by mkdir itself rather than afterwards, so there is no window in which the directory holding
            // uploaded content is listable by every local principal. The location is reported as an opaque
            // reference because it is assembled from configuration.
            if (!createOwnerOnlyDirectoryQuietly(parent.toPath())) {
                Debug.logWarning("Content store provider [filesystem] could not create the top level upload"
                        + " directory " + ContentStoreUtil.reference(parentDir), MODULE);
            }
        }

        if (latestDir != null) {
            File[] dirList = latestDir.listFiles();
            if (dirList != null && dirList.length >= maxFiles) {
                latestDir = makeNewDirectory(parent);
            }
        } else {
            latestDir = makeNewDirectory(parent);
        }
        String name = "";
        if (latestDir != null) {
            name = latestDir.getName();
        }

        // Reported as an opaque reference rather than as the directory name: the name is assembled from
        // configuration and from the filesystem, and a diagnostic must not be able to carry either into the
        // log verbatim. Verbose rather than informational, because it identifies one upload's destination.
        Debug.logVerbose("Content store provider [filesystem] upload directory " + ContentStoreUtil.reference(name),
                MODULE);
        // Only the name is null-guarded; absolute mode still dereferences latestDir. Preserving that
        // asymmetry is what keeps the answer identical to the one DataResourceWorker gives, so selecting
        // this provider cannot change what a caller observes.
        if (absolute) {
            return latestDir.getAbsolutePath().replace('\\', '/');
        }
        return initialPath + "/" + name;
    }

    /**
     * Chooses the upload sub-directory to place the next file in, creating one when no reusable
     * sub-directory is available.
     *
     * @param parent the confined top level upload location
     * @param maxFiles the number of entries after which a sub-directory stops being reused
     * @return the sub-directory to place the next file in, never null and guaranteed to exist
     * @throws GeneralException if the location cannot be prepared, examined or extended
     */
    private static Path selectUploadDirectory(Path parent, long maxFiles) throws GeneralException {
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new GeneralException("Unable to create the top level content upload directory ["
                    + ContentStoreUtil.reference(parent.toString()) + "]: " + e.getClass().getSimpleName());
        }
        int scanLimit = ContentStoreSupport.boundedIntProperty(MAX_UPLOAD_DIRECTORIES_PROPERTY,
                DEFAULT_MAX_UPLOAD_DIRECTORIES, 1, MAX_UPLOAD_DIRECTORIES_LIMIT);
        Path newest = null;
        long newestStamp = Long.MIN_VALUE;
        int examined = 0;
        boolean bounded = false;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(parent)) {
            for (Path entry : entries) {
                examined++;
                if (examined > scanLimit) {
                    bounded = true;
                    break;
                }
                long stamp = timestampOf(entry.getFileName().toString());
                if (stamp < 0 || stamp <= newestStamp || !Files.isDirectory(entry)) {
                    continue;
                }
                newestStamp = stamp;
                newest = entry;
            }
        } catch (IOException e) {
            throw new GeneralException("Unable to examine the content upload directory ["
                    + ContentStoreUtil.reference(parent.toString()) + "]: " + e.getClass().getSimpleName());
        }
        if (bounded) {
            Debug.logWarning(EVENT_UPLOAD_SCAN_BOUNDED + " location [" + ContentStoreUtil.reference(parent.toString())
                    + "]: only the first " + scanLimit + " entries were examined; raise ["
                    + MAX_UPLOAD_DIRECTORIES_PROPERTY + "] or prune the location", MODULE);
        }
        if (newest != null && countUpTo(newest, maxFiles) < maxFiles) {
            return newest;
        }
        return createUploadDirectory(parent);
    }

    /**
     * Counts the entries of a directory, stopping as soon as the supplied ceiling is reached.
     *
     * <p>Stopping early is the point: the answer is only ever compared against the ceiling, so
     * counting past it would be work performed on a request thread for no purpose.
     *
     * @param directory the directory to count
     * @param ceiling the count at which to stop
     * @return the number of entries counted, never more than the ceiling
     * @throws GeneralException if the directory cannot be examined for any reason other than having
     *     been removed in the meantime
     */
    private static long countUpTo(Path directory, long ceiling) throws GeneralException {
        long counted = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            Iterator<Path> iterator = entries.iterator();
            while (counted < ceiling && iterator.hasNext()) {
                iterator.next();
                counted++;
            }
        } catch (NoSuchFileException e) {
            // the directory was removed between being chosen and being counted; treat it as full so a
            // fresh one is created rather than failing an upload
            return ceiling;
        } catch (IOException e) {
            throw new GeneralException("Unable to count the content upload directory ["
                    + ContentStoreUtil.reference(directory.toString()) + "]: " + e.getClass().getSimpleName());
        }
        return counted;
    }

    /**
     * Creates a fresh timestamp-named sub-directory underneath the given top level location.
     *
     * <p>Creation is atomic and is never assumed to have succeeded: {@link Files#createDirectory}
     * fails if the name is already taken, which is exactly the collision a second instance writing
     * into shared storage in the same millisecond produces, so the name is advanced and the creation
     * retried. Every other failure propagates, rather than being logged while the directory is returned
     * as though it had been created, because that is what turns a full or read-only volume into a
     * mysterious "file not found" much later.
     *
     * @param parent the top level location to create the sub-directory in
     * @return the sub-directory that was created, which is guaranteed to exist
     * @throws GeneralException if no sub-directory could be created
     */
    private static Path createUploadDirectory(Path parent) throws GeneralException {
        long stamp = System.currentTimeMillis();
        for (int attempt = 0; attempt < DIRECTORY_CREATE_ATTEMPTS; attempt++) {
            Path candidate = parent.resolve(Long.toString(stamp + attempt));
            try {
                return Files.createDirectory(candidate, OWNER_ONLY_DIRECTORY_ATTRIBUTE);
            } catch (FileAlreadyExistsException e) {
                Debug.logVerbose(EVENT_UPLOAD_DIRECTORY_TAKEN + " location ["
                        + ContentStoreUtil.reference(candidate.toString()) + "]: trying the next name", MODULE);
            } catch (UnsupportedOperationException e) {
                // a filesystem with no POSIX permission model cannot be handed a mode; the directory is
                // still created, just with whatever the platform's default is
                try {
                    return Files.createDirectory(candidate);
                } catch (FileAlreadyExistsException taken) {
                    Debug.logVerbose(EVENT_UPLOAD_DIRECTORY_TAKEN + " location ["
                            + ContentStoreUtil.reference(candidate.toString()) + "]: trying the next name", MODULE);
                } catch (IOException failed) {
                    throw new GeneralException("Unable to create the content upload directory ["
                            + ContentStoreUtil.reference(candidate.toString()) + "]: "
                            + failed.getClass().getSimpleName());
                }
            } catch (IOException e) {
                throw new GeneralException("Unable to create the content upload directory ["
                        + ContentStoreUtil.reference(candidate.toString()) + "]: " + e.getClass().getSimpleName());
            }
        }
        throw new GeneralException("Unable to create a content upload directory under ["
                + ContentStoreUtil.reference(parent.toString()) + "] after " + DIRECTORY_CREATE_ATTEMPTS + " attempts");
    }

    /**
     * Reads a directory name as the millisecond timestamp this provider names its sub-directories
     * with.
     *
     * @param name the directory name to read
     * @return the timestamp, or {@code -1} when the name is not one this provider created
     */
    private static long timestampOf(String name) {
        if (name.isEmpty() || name.length() > MAX_TIMESTAMP_DIGITS) {
            return -1;
        }
        for (int index = 0; index < name.length(); index++) {
            if (!Character.isDigit(name.charAt(index))) {
                return -1;
            }
        }
        try {
            return Long.parseLong(name);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Creates a fresh timestamp-named sub-directory underneath the given top level location for the
     * legacy upload-path surface, reporting a failure rather than throwing.
     *
     * <p>This is the form {@link #getUploadPath(String, double, boolean)} needs. That method carries no
     * checked exception, because {@code DataResourceWorker.getDataResourceContentUploadPath} - the
     * behaviour it is at parity with - carries none either, and adding one would change what its
     * callers observe. Creation is still atomic and is still never assumed to have succeeded:
     * {@link Files#createDirectory} fails if the name is already taken, which is exactly the collision
     * two instances writing into shared storage in the same millisecond produce, so the name is
     * advanced and the creation retried. Callers that CAN report a failure - the
     * {@link #uploadPath(Delegator, boolean)} surface - go through
     * {@link #createUploadDirectory(Path)} instead, which propagates it.
     *
     * @param parent the top level location to create the sub-directory in
     * @return the sub-directory that was created
     */
    private static File makeNewDirectory(File parent) {
        long stamp = System.currentTimeMillis();
        File latestDir = null;
        for (int attempt = 0; attempt < DIRECTORY_CREATE_ATTEMPTS; attempt++) {
            latestDir = new File(parent, Long.toString(stamp + attempt));
            if (latestDir.exists()) {
                continue;
            }
            // Owner-only, applied by mkdir itself: uploaded content must not be listable by every local
            // principal, and setting the mode after creation would leave a window in which it was.
            if (!createOwnerOnlyDirectoryQuietly(latestDir.toPath())) {
                Debug.logError("Content store provider [filesystem] could not create the upload directory "
                        + ContentStoreUtil.reference(latestDir.getName()), MODULE);
            }
            return latestDir;
        }
        Debug.logError("Content store provider [filesystem] could not find a free upload directory name under "
                + ContentStoreUtil.reference(parent.getPath()) + " after " + DIRECTORY_CREATE_ATTEMPTS
                + " attempts", MODULE);
        return latestDir;
    }

    /**
     * The most recently modified sub-directory of the given location, or null when there is none.
     *
     * <p>The scan is <strong>bounded</strong>: at most
     * {@code content.store.filesystem.max.upload.directories} entries are examined, one at a time,
     * and only the newest is remembered. Nothing accumulates a list or a sort of the whole directory,
     * so an upload location that has grown to a very large number of sub-directories cannot turn a
     * single upload into an unbounded amount of work - or an unbounded amount of heap - on a request
     * thread.
     *
     * <p>Selection is by modification time rather than by name, because that is what
     * {@code DataResourceWorker.getDataResourceContentUploadPath} does and this surface exists to be
     * at parity with it.
     *
     * @param parent the top level upload location to examine
     * @return the most recently modified sub-directory, or null when the location holds none or
     *     cannot be examined
     */
    private static File mostRecentSubdirectory(File parent) {
        int scanLimit = ContentStoreSupport.boundedIntProperty(MAX_UPLOAD_DIRECTORIES_PROPERTY,
                DEFAULT_MAX_UPLOAD_DIRECTORIES, 1, MAX_UPLOAD_DIRECTORIES_LIMIT);
        File newest = null;
        long newestStamp = Long.MIN_VALUE;
        int examined = 0;
        boolean bounded = false;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(parent.toPath())) {
            for (Path entry : entries) {
                examined++;
                if (examined > scanLimit) {
                    bounded = true;
                    break;
                }
                File candidate = entry.toFile();
                if (!candidate.isDirectory()) {
                    continue;
                }
                long stamp = candidate.lastModified();
                if (stamp >= newestStamp) {
                    newestStamp = stamp;
                    newest = candidate;
                }
            }
        } catch (IOException e) {
            // exactly what File.listFiles answering null already meant here: the location cannot be
            // examined, so no sub-directory is reusable and a fresh one is started
            Debug.logWarning("Content store provider [filesystem] could not examine the upload location ["
                    + ContentStoreUtil.reference(parent.getPath()) + "] [" + e.getClass().getSimpleName()
                    + "]; a new sub-directory will be started", MODULE);
            return null;
        }
        if (bounded) {
            Debug.logWarning(EVENT_UPLOAD_SCAN_BOUNDED + " location ["
                    + ContentStoreUtil.reference(parent.getPath()) + "]: only the first " + scanLimit
                    + " entries were examined; raise [" + MAX_UPLOAD_DIRECTORIES_PROPERTY
                    + "] or prune the location", MODULE);
        }
        return newest;
    }

    /**
     * Writes content onto the given target so that the target is either wholly the previous content
     * or wholly the new content, and never a mixture of the two.
     *
     * <p>Every property of this method closes a real failure:
     * <ul>
     *   <li>The staging file is created in the <em>same</em> directory as the target, so the move onto
     *       the target is a rename within one filesystem rather than a copy.</li>
     *   <li>It is created with owner-only permissions applied by the creation itself, so there is no
     *       window in which uploaded content is readable by every local principal - which is what a
     *       {@code umask}-dependent {@code 0644} amounts to on a shared host - and it is opened
     *       {@code NOFOLLOW_LINKS}, so it cannot be written through a link.</li>
     *   <li>The containing directory is re-checked <em>fully resolved</em> immediately after it has
     *       been created, so a directory component replaced by a symbolic link cannot redirect the
     *       write even though the textual path still looked contained.</li>
     *   <li>The rename replaces the destination <em>name</em>: if the destination is a symbolic link
     *       planted by another local principal, the link is replaced rather than followed.</li>
     *   <li>The staging file is forced to the storage device before it is published, so a crash
     *       cannot leave the destination name pointing at content the device never received.</li>
     *   <li>A failed write leaves neither a half-written target nor a stray staging file.</li>
     * </ul>
     *
     * @param target the file to end up holding the content, already confined to the upload root
     * @param key the storage key being written, used only to build messages
     * @param emitter produces the content into the staging stream
     * @throws GeneralException if the staging location cannot be established or cannot be trusted
     * @throws IOException if the content cannot be produced, staged or moved into place
     */
    private static void writeAtomically(Path target, String key, ContentEmitter emitter)
            throws GeneralException, IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent == null) {
            // resolveMutationTarget always returns a path underneath the upload root, so it always has a
            // parent; the check exists so the dereference below cannot be a latent null dereference.
            throw new GeneralException("Cannot store content under " + ContentStoreUtil.reference(key)
                    + ": the key resolves to no containing directory");
        }
        createOwnerOnlyDirectories(parent);
        // Re-established on the FULLY resolved parent, which toRealPath produces by following every
        // symbolic link in the chain. The check has to come after the directories exist, because a path
        // that is not there yet cannot be resolved. The container form is used, because the containing
        // directory of a key addressing content directly in the upload root IS the upload root.
        requireContainerInsideUploadRoot(parent.toRealPath(), key);

        Path staging = createStagingFile(parent);
        boolean placed = false;
        try {
            // WRITE only: the file was just created, so no CREATE flag is needed and the open cannot
            // bring a new object into existence.
            try (FileChannel channel = FileChannel.open(staging, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                emitter.emitTo(channel);
                // Forced to the storage device BEFORE the content is published, so that a crash between
                // the write and the rename cannot leave the destination name pointing at content the
                // device never actually received.
                channel.force(true);
            }
            try {
                // REPLACE_EXISTING is documented as ignored alongside ATOMIC_MOVE, and is passed anyway so
                // that the intent is explicit: whatever stands at the target name - including a symbolic
                // link planted there - is replaced rather than written through.
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // some filesystems cannot rename atomically; the replacement is then not atomic, but it
                // is still the last step, so a failed write has still left the previous content intact
                Debug.logWarning(EVENT_ATOMIC_MOVE_UNAVAILABLE + " key [" + ContentStoreUtil.reference(key)
                        + "]: replacing without an atomic move", MODULE);
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
            }
            placed = true;
        } finally {
            if (!placed) {
                // a failed write must leave neither a half-written target nor a stray staging file
                Files.deleteIfExists(staging);
            }
        }
        requireOwnerOnly(target, key);
    }

    /**
     * Creates the staging file a write is assembled in, owner-only wherever the platform can express
     * that.
     *
     * @param parent the directory to create the staging file in, which is the target's own directory
     * @return the staging file, which exists and is empty
     * @throws IOException if the staging file cannot be created
     */
    private static Path createStagingFile(Path parent) throws IOException {
        try {
            return Files.createTempFile(parent, STAGING_PREFIX, STAGING_SUFFIX, OWNER_ONLY_FILE_ATTRIBUTE);
        } catch (UnsupportedOperationException e) {
            // a filesystem with no POSIX permission model cannot be handed a mode; requireOwnerOnly
            // reports the same condition once the content is in place
            return Files.createTempFile(parent, STAGING_PREFIX, STAGING_SUFFIX);
        }
    }

    /**
     * Writes the whole of the supplied content to the supplied channel before the caller publishes it.
     *
     * <p>{@link FileChannel#write(ByteBuffer)} is only contracted to transfer <em>some</em> of the bytes
     * remaining in the buffer, so a single call can legitimately return having consumed only part of the
     * content - most visibly on a full or quota-limited filesystem, on a network filesystem such as the
     * shared upload volume a multi-instance deployment mounts, and on any channel whose write is cut
     * short. Because the caller then forces the staging file to the device and publishes it with a
     * rename, a partial write that went unnoticed would install <em>truncated content under the
     * destination name</em>, which is strictly worse than not writing at all: the truncation is durable,
     * indistinguishable from the real content, and silently replaces what was there before.
     *
     * <p>The write is therefore repeated until the buffer is exhausted, and a call that transfers nothing
     * is treated as a failure rather than retried forever, so a stalled device surfaces as an
     * {@link IOException} that keeps the staging file out of the destination.
     *
     * <p>Package-private rather than private because the channel is the only seam at which a partial
     * write can be provoked deterministically: a real filesystem decides for itself how much of a buffer
     * it takes, so this contract can only be pinned by driving the method with a channel that accepts the
     * content a few bytes at a time.
     *
     * @param channel the channel to write to, positioned at the start of the staging file
     * @param data the complete content to write; may be a zero-length array, in which case nothing is
     *     written and the empty staging file is published as-is
     * @param key the storage key being written, reported as an opaque reference so that nothing
     *     request-supplied reaches the message
     * @throws IOException if the channel stops accepting bytes before the whole content is written
     */
    static void writeFully(FileChannel channel, byte[] data, String key) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        while (buffer.hasRemaining()) {
            int written = channel.write(buffer);
            if (written <= 0) {
                throw new IOException("Content store provider [filesystem] could not store "
                        + ContentStoreUtil.reference(key) + ": the storage device accepted only "
                        + (data.length - buffer.remaining()) + " of " + data.length
                        + " bytes and then stopped making progress");
            }
        }
    }

    /**
     * Creates one directory with owner-only permissions, reporting success rather than throwing.
     *
     * <p>This is the form the pre-existing upload-path code needs: {@link #getUploadPath(String, double,
     * boolean)} and {@link #makeNewDirectory(File)} report a creation failure and carry on, exactly as they
     * did when they called {@code File.mkdir}, and changing that into an exception would change what their
     * callers observe. The only difference from {@code mkdir} is the mode, which is applied atomically by
     * the underlying {@code mkdir(2)} call instead of being left to the process {@code umask}.
     *
     * @param directory the directory to create
     * @return {@code true} if the directory now exists as a directory
     */
    private static boolean createOwnerOnlyDirectoryQuietly(Path directory) {
        try {
            Files.createDirectory(directory, OWNER_ONLY_DIRECTORY_ATTRIBUTE);
            return true;
        } catch (FileAlreadyExistsException concurrent) {
            // Another thread, or another instance sharing the volume, created it first: that is a success as
            // long as what exists really is a directory and not a link planted in its place.
            return Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException noPosix) {
            // The filesystem has no POSIX permission model, so the mode cannot be requested at all.
            // Creating the directory without one is the only available behaviour.
            return createDirectoryWithoutMode(directory);
        } catch (IOException e) {
            Debug.logVerbose("Content store provider [filesystem] could not create directory "
                    + ContentStoreUtil.reference(directory.toString()) + ": " + e.getClass().getName(), MODULE);
            return false;
        }
    }

    /**
     * Creates one directory without a permission attribute, for a filesystem with no POSIX model.
     *
     * @param directory the directory to create
     * @return {@code true} if the directory now exists as a directory
     */
    private static boolean createDirectoryWithoutMode(Path directory) {
        try {
            Files.createDirectory(directory);
            return true;
        } catch (IOException e) {
            return Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS);
        }
    }

    /**
     * Resolves a storage key that is about to be <em>written to or removed</em>, confining it to the one
     * directory tree this provider is allowed to modify.
     *
     * <p><strong>Why the mutation surface is narrower than the read surface.</strong> Reads go through
     * {@link #resolveFile(String)}, which honours the allow lists the Content component has always applied
     * to file-backed data resources - {@code applications/}, {@code themes/}, {@code plugins/},
     * {@code runtime/} and the rest - because {@code OFBIZ_FILE} and {@code LOCAL_FILE} object information
     * in existing databases legitimately points all over the OFBiz home directory, and narrowing reads
     * would start refusing content that resolves today. Those allow lists were written for reading, and
     * they are far too broad to be a write boundary: they encompass the deployed application tree, every
     * theme and every plugin, so a key that merely satisfies them can name a Groovy script, a screen
     * definition or a configuration file that OFBiz itself will later load and execute. Writes and
     * removals are therefore confined to the upload tree named by {@code content.upload.path.prefix},
     * which is the only place this provider is meant to own.
     *
     * <p><strong>The key keeps its historical meaning.</strong> A key is resolved by
     * {@link #resolveFile(String)} - the very same resolution the read operations use - so a key means
     * exactly what it has always meant: the path relative to the OFBiz home directory that
     * {@code DataResource.objectInfo} holds. Confinement is then applied by <em>refusing</em> a key that
     * lands outside the upload root, never by re-rooting it. That distinction is the whole design: silently
     * resolving keys against the upload root instead would make {@code put} and {@code get} address
     * different files for the same key, break the interchangeability the SPI exists to provide, and change
     * the meaning of every {@code objectInfo} value already persisted in every existing database.
     *
     * <p><strong>What this rejects, and in what order.</strong> Every check happens before the path is
     * used for anything:
     * <ul>
     *   <li>a null, empty or control-bearing key, the last because such a key would be reproduced in
     *       logs and in filesystem diagnostics;</li>
     *   <li>a key with a {@code .} or {@code ..} component, in either separator style. A traversal
     *       component is refused outright rather than normalised away, because normalising is what makes
     *       {@code a/../../b} look innocuous, and because no legitimate storage key needs one;</li>
     *   <li>an absolute key, and a {@code component://} location. Both address content by a route that
     *       bypasses the upload root by design, and a write must not be able to choose its own root;</li>
     *   <li>anything that, once resolved, lies outside the upload root - including through a symbolic
     *       link, since the check is applied to the fully resolved form of whichever ancestor already
     *       exists.</li>
     * </ul>
     *
     * <p><strong>What is left of the race.</strong> The residual window between resolving a path and
     * acting on it cannot be closed in pure Java, which has no {@code openat}-style API for walking a
     * directory tree by handle. It is narrowed to almost nothing instead, and its consequence is removed:
     * the callers re-verify the fully resolved containing directory immediately before acting, they create
     * the object with {@code CREATE_NEW} rather than opening one that may already be there, and they reach
     * the final name only through a rename, which replaces a symbolic link rather than following it, so
     * winning the race does not redirect a write or a removal.
     *
     * @param key the storage key to resolve, in the shape held by {@code DataResource.objectInfo} and so
     *     relative to the OFBiz home directory
     * @return the absolute, normalised path the operation may act on, which need not exist yet
     * @throws GeneralException if the key is unusable, carries a traversal component, is absolute, or
     *     resolves outside the upload root
     */
    private static Path resolveMutationTarget(String key) throws GeneralException {
        // The very same unusable-key check the read path applies, so that a key which cannot be read
        // cannot be written either, and both report it in the same words.
        ContentStoreSupport.requireUsableKey(key);
        if (ContentStoreUtil.hasUnsafeCharacter(key)) {
            throw new GeneralException("Content store provider [filesystem] refused the change to "
                    + ContentStoreUtil.reference(key) + ": the storage key contains a control character");
        }
        String normalisedSeparators = key.replace('\\', '/');
        if (normalisedSeparators.contains("://")) {
            throw new GeneralException("Content store provider [filesystem] refused the change to "
                    + ContentStoreUtil.reference(key) + ": a location URL addresses content outside the upload"
                    + " root by design, so it cannot name a file this provider may modify");
        }
        if (normalisedSeparators.startsWith("/")) {
            throw new GeneralException("Content store provider [filesystem] refused the change to "
                    + ContentStoreUtil.reference(key) + ": a write or a removal is addressed by a key relative to"
                    + " the upload root named by [" + UPLOAD_PATH_PREFIX_PROPERTY + "], not by an absolute path");
        }
        for (String component : normalisedSeparators.split("/")) {
            if (".".equals(component) || "..".equals(component)) {
                throw new GeneralException("Content store provider [filesystem] refused the change to "
                        + ContentStoreUtil.reference(key) + ": the storage key contains a [" + component
                        + "] component, and a traversal component is refused rather than normalised away");
            }
        }

        // Resolved through the read path on purpose, so that a key can never address one file for a write and
        // another for a read: whatever resolveFile answers for this key is exactly what get, openStream and
        // exists will act on. The allow-list and boundary checks it applies stay in force, and the upload-root
        // requirement below is strictly narrower than any of them.
        Path resolved = resolveFile(key).toPath().toAbsolutePath().normalize();
        requireInsideUploadRoot(resolved, key);
        return resolved;
    }

    /**
     * Returns the one directory tree this provider may write into and delete from.
     *
     * <p>The location is {@code content.upload.path.prefix} resolved underneath the {@code ofbiz.home}
     * system property, which is exactly where {@link #getUploadPath(boolean)} places content, so
     * confining mutations to it takes nothing away from the provider's own upload path.
     *
     * @return the absolute, normalised upload root, which need not exist yet
     * @throws GeneralException if {@code ofbiz.home} or the upload prefix is not configured
     */
    private static Path uploadRoot() throws GeneralException {
        String home = System.getProperty("ofbiz.home");
        if (UtilValidate.isEmpty(home)) {
            throw new GeneralException("Content store provider [filesystem] is not usable: the [ofbiz.home] system"
                    + " property is not set, so the upload root cannot be located");
        }
        String prefix = UtilProperties.getPropertyValue(PROPERTY_RESOURCE, UPLOAD_PATH_PREFIX_PROPERTY);
        if (UtilValidate.isEmpty(prefix)) {
            throw new GeneralException("Content store provider [filesystem] is not usable: property ["
                    + UPLOAD_PATH_PREFIX_PROPERTY + "] of resource [" + PROPERTY_RESOURCE + "] is not configured,"
                    + " so there is no upload root to confine changes to");
        }
        // The committed value carries no leading separator and getUploadPath tolerates one by supplying it,
        // so a leading separator is stripped here as well rather than resolving an absolute path against the
        // home directory - which would silently discard the home directory altogether.
        String relative = prefix.replace('\\', '/');
        while (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        try {
            return Path.of(home).toAbsolutePath().normalize().resolve(relative).normalize();
        } catch (InvalidPathException e) {
            throw new GeneralException("Content store provider [filesystem] is not usable: the [ofbiz.home] system"
                    + " property and property [" + UPLOAD_PATH_PREFIX_PROPERTY + "] of resource ["
                    + PROPERTY_RESOURCE + "] do not form a usable path [" + e.getClass().getSimpleName() + "]");
        }
    }

    /**
     * Refuses a path that is not strictly inside the upload root.
     *
     * <p>This is the check applied to the OBJECT a change names. The root itself is not an acceptable
     * target: a change has to name something inside it, never the directory that bounds it, or a removal
     * could be asked to delete the tree that confines every other key.
     *
     * @param candidate the already-normalised path to check
     * @param key the storage key the path came from, reported as an opaque reference
     * @throws GeneralException if the path is the upload root or lies outside it
     */
    private static void requireInsideUploadRoot(Path candidate, String key) throws GeneralException {
        requireWithinUploadRoot(candidate, key, false);
    }

    /**
     * Refuses a containing directory that is not the upload root or a directory inside it.
     *
     * <p>This is the check applied to the DIRECTORY a change acts in, which is why - unlike
     * {@link #requireInsideUploadRoot(Path, String)} - the root itself is acceptable: a key addressing
     * content directly in the upload root, which is the shape {@code content.upload.path.prefix} plus a
     * file name produces, has the root as its containing directory. Refusing it here would make the most
     * ordinary key in the store unwritable.
     *
     * <p>Callers pass the FULLY RESOLVED directory, so a component that has been replaced by a symbolic
     * link pointing out of the upload root is caught here even though the textual path still looked
     * contained.
     *
     * @param directory the already-resolved containing directory to check
     * @param key the storage key the path came from, reported as an opaque reference
     * @throws GeneralException if the directory lies outside the upload root
     */
    private static void requireContainerInsideUploadRoot(Path directory, String key) throws GeneralException {
        requireWithinUploadRoot(directory, key, true);
    }

    /**
     * Shared core of the two confinement checks.
     *
     * <p>Compared after normalisation on both sides, and against the upload root resolved through its own
     * links as well as against its textual form, so that an upload root which is itself a symbolic link or
     * a mount point - a network volume mounted on {@code runtime/}, which is a supported deployment -
     * compares equal to a candidate resolved the same way instead of being refused for a difference that
     * is not a traversal.
     *
     * @param candidate the already-normalised path to check
     * @param key the storage key the path came from, reported as an opaque reference
     * @param rootItselfAcceptable {@code true} when the candidate is a containing directory, for which the
     *     root is a legitimate answer; {@code false} when it is the object being changed
     * @throws GeneralException if the path lies outside the upload root, or equals it and equality is not
     *     acceptable for this caller
     */
    private static void requireWithinUploadRoot(Path candidate, String key, boolean rootItselfAcceptable)
            throws GeneralException {
        Path root = uploadRoot();
        Path resolvedRoot = root;
        try {
            if (Files.exists(root)) {
                // Resolved so that an upload root which is itself a symbolic link or a mount point - a network
                // volume mounted on runtime/, which is a supported deployment - compares equal to a candidate
                // resolved the same way, instead of being refused for a difference that is not a traversal.
                resolvedRoot = root.toRealPath();
            }
        } catch (IOException e) {
            throw new GeneralException("Content store provider [filesystem] could not resolve the upload root named"
                    + " by [" + UPLOAD_PATH_PREFIX_PROPERTY + "] [" + e.getClass().getSimpleName() + "]");
        }
        boolean isRootItself = candidate.equals(resolvedRoot) || candidate.equals(root);
        boolean isInsideRoot = candidate.startsWith(resolvedRoot) || candidate.startsWith(root);
        if (isRootItself ? !rootItselfAcceptable : !isInsideRoot) {
            throw new GeneralException("Content store provider [filesystem] refused the change to "
                    + ContentStoreUtil.reference(key) + ": it resolves outside the upload root named by ["
                    + UPLOAD_PATH_PREFIX_PROPERTY + "] of resource [" + PROPERTY_RESOURCE + "], which is the only"
                    + " tree this provider may modify");
        }
    }

    /**
     * Creates a directory and every missing ancestor of it inside the upload root, owner-only, refusing to
     * treat anything that is not really a directory as one.
     *
     * <p>{@code Files.createDirectories} is deliberately not used. It creates each component with whatever
     * the process {@code umask} allows - typically {@code 0755}, so every local principal can list uploaded
     * content - and it accepts an existing symbolic link as a satisfied component, which is exactly how a
     * planted link redirects everything written below it. This walks the chain instead: each component is
     * created with owner-only permissions applied by {@code mkdir} itself, so there is no window during
     * which it is world readable, and a component that already exists has to be a real directory, checked
     * without following links.
     *
     * @param directory the directory to bring into existence, already confined to the upload root
     * @throws GeneralException if an existing path component is a symbolic link, which below the
     *     root can only be a redirection
     * @throws IOException if a directory cannot be created, or if an existing path component is
     *     something other than a directory or a link, so that the content has nowhere to land
     */
    private static void createOwnerOnlyDirectories(Path directory) throws GeneralException, IOException {
        Path root = uploadRoot();
        createUploadRoot(root);
        Path walked = root;
        for (Path component : root.relativize(directory)) {
            walked = walked.resolve(component);
            if (Files.exists(walked, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(walked)) {
                    // A link BELOW the root is a redirection whichever way it points, so it is refused as
                    // the security condition it is and is named as such.
                    throw new GeneralException("Content store provider [filesystem] refused to store content"
                            + " underneath " + ContentStoreUtil.reference(walked.toString()) + ": that path"
                            + " component is a symbolic link planted to redirect the write");
                }
                if (!Files.isDirectory(walked, LinkOption.NOFOLLOW_LINKS)) {
                    // Something that is neither a directory nor a link stands where a directory has to be, so
                    // the write has nowhere to land. Reported rather than warned about, because a write that
                    // cannot happen must not look as though it succeeded.
                    throw new IOException("Content store provider [filesystem] refused to store content"
                            + " underneath " + ContentStoreUtil.reference(walked.toString()) + ": that path"
                            + " component exists and is not a directory, so the content has nowhere to land");
                }
                continue;
            }
            createOwnerOnlyDirectory(walked);
        }
    }

    /**
     * Brings the upload root itself into existence, creating whatever is missing above it as well.
     *
     * <p>The root is where owner-only permissions START: it is the first directory that holds uploaded
     * content, so it and everything below it are created {@code rwx------}. Its ancestors are not content -
     * by default they are the OFBiz home directory and {@code runtime/}, which the deployment owns and which
     * every other part of OFBiz creates with the ordinary process {@code umask} - so they are created the
     * ordinary way rather than being narrowed to this provider's own mode. Creating them at all is necessary
     * because {@code mkdir} makes one directory: without this, the very first upload into a deployment whose
     * {@code runtime/} tree has not been created yet would fail with a bare {@code NoSuchFileException}.
     *
     * <p>Whether the root already exists is asked WITHOUT following links, but whether what is there is
     * usable is asked WITH following them. That asymmetry is deliberate and applies only to the root: a
     * shared network volume mounted - or symbolically linked - at the upload location is a supported
     * deployment, and {@link #requireWithinUploadRoot} resolves the root the same way. Every component
     * BELOW the root is checked without following links, because down there a link is a redirection.
     *
     * @param root the upload root, absolute and normalised
     * @throws GeneralException if the root exists but is not a usable directory
     * @throws IOException if the root or one of its ancestors cannot be created
     */
    private static void createUploadRoot(Path root) throws GeneralException, IOException {
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(root)) {
                throw new GeneralException("Content store provider [filesystem] cannot store content: the upload"
                        + " root named by [" + UPLOAD_PATH_PREFIX_PROPERTY + "] of resource [" + PROPERTY_RESOURCE
                        + "] exists but is not a usable directory");
            }
            return;
        }
        Path enclosing = root.getParent();
        if (enclosing != null) {
            Files.createDirectories(enclosing);
        }
        createOwnerOnlyDirectory(root);
    }

    /**
     * Creates one directory with owner-only permissions, tolerating a concurrent creation of the same
     * directory.
     *
     * @param directory the directory to create
     * @throws IOException if the directory cannot be created and does not already exist
     */
    private static void createOwnerOnlyDirectory(Path directory) throws IOException {
        try {
            Files.createDirectory(directory, OWNER_ONLY_DIRECTORY_ATTRIBUTE);
        } catch (FileAlreadyExistsException concurrent) {
            // Another request thread, or another instance sharing the upload volume, created it first. That is
            // an ordinary outcome rather than an error, provided what now exists really is a directory - which
            // the caller establishes before it uses the path.
            Debug.logVerbose("Content store provider [filesystem] found the content directory "
                    + ContentStoreUtil.reference(directory.toString()) + " already created", MODULE);
        }
    }

    /**
     * Verifies that a stored file really carries owner-only permissions.
     *
     * <p>The permissions were requested as the file was created, so this reads back what the filesystem
     * actually applied. It is not redundant: a filesystem may narrow or widen the requested mode - some
     * network filesystems ignore POSIX permissions altogether, and a default ACL on the directory can add
     * entries - and uploaded content that is world readable on a shared host is precisely the defect this
     * closes. A filesystem with no POSIX permission model at all cannot be checked and is reported rather
     * than failed, because refusing there would make the provider unusable on it.
     *
     * @param file the file that was just stored
     * @param key the storage key it was stored under, reported as an opaque reference
     * @throws GeneralException if the file is readable or writable by anyone other than its owner
     * @throws IOException if the permissions cannot be read
     */
    private static void requireOwnerOnly(Path file, String key) throws GeneralException, IOException {
        Set<PosixFilePermission> applied;
        try {
            applied = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException noPosix) {
            Debug.logVerbose("Content store provider [filesystem] stored " + ContentStoreUtil.reference(key)
                    + " on a filesystem with no POSIX permissions, so the owner-only mode could not be verified",
                    MODULE);
            return;
        }
        if (!OWNER_ONLY_FILE.equals(applied)) {
            throw new GeneralException("Content store provider [filesystem] stored " + ContentStoreUtil.reference(key)
                    + " but the filesystem applied " + PosixFilePermissions.toString(applied) + " instead of "
                    + PosixFilePermissions.toString(OWNER_ONLY_FILE) + ", so the content is reachable by other"
                    + " local principals");
        }
    }

    /**
     * Resolves a storage key to the file that holds its content.
     *
     * <p>Exactly {@link #resolvePath(String)}, in the {@link File} form the pre-existing upload-path and
     * mutation code already works in. Both names reach one implementation on purpose: a second resolver
     * would be a second security boundary, and the two would drift apart at the first change to either.
     *
     * @param key the storage key to resolve, in the shape held by {@code DataResource.objectInfo}
     * @return the file that holds the content addressed by the key, which need not exist yet
     * @throws GeneralException if the key is null or empty, names no usable location, falls outside
     *     the configured allow list, or resolves outside every allowed root
     */
    private static File resolveFile(String key) throws GeneralException {
        return resolvePath(key).toFile();
    }

    /**
     * Returns the local file this provider holds the supplied key's content in.
     *
     * <p>Answered by the same resolution every read and write uses, so the path handed back is exactly the one
     * this provider would act on - including every allow-list and containment check, which run here too. A
     * caller therefore cannot use this to learn about a location the provider itself would refuse.
     *
     * @param key the storage key to locate, in the shape held by {@code DataResource.objectInfo}; must be
     *     neither null nor empty
     * @return the absolute, normalised local path backing the key, never null
     * @throws GeneralException if the key is null or empty, or resolves outside the permitted locations
     */
    @Override
    public Path backingPath(String key) throws GeneralException {
        return resolvePath(key);
    }

    /**
     * Resolves a storage key to the location that holds its content, applying the allow list the
     * Content component applies to file-backed data resources and then confining the result.
     *
     * <p>This is the READ resolution, and it is deliberately as wide as the behaviour it replaces and
     * no wider. A key already beginning with the OFBiz home directory is the absolute form persisted
     * for {@code LOCAL_FILE} content and is checked against
     * {@code content.data.local.file.allowed.paths}; every other key is the form relative to the home
     * directory persisted for {@code OFBIZ_FILE} content, is prefixed with that directory, and is
     * checked against {@code content.data.ofbiz.file.allowed.paths}. Reproducing that distinction is
     * not a convenience: {@code DataResource.objectInfo} values in every existing database are already
     * written in these two forms, so a provider that understood only one of them could not read
     * content the deployment already has.
     *
     * <p>Two containment tests then run, and each closes something the other does not:
     * <ol>
     *   <li>{@link #checkFileBoundary(File, String)} against the OFBiz home directory - the
     *       canonical-plus-normalised shape the rest of the Content component uses, which requires both
     *       tests together. It is what refuses an absolute key that a widened allow list would otherwise
     *       admit, and its normalised test is what still refuses a traversal whose tail does not exist
     *       yet.</li>
     *   <li>{@link #confine(Path, String)} against the home directory plus every root declared by
     *       {@code content.store.filesystem.allowed.roots} - a purely canonical test, so a symbolic
     *       link planted inside the tree that points out of it is refused rather than accepted. A
     *       deployment whose content really does live behind such a link declares that root explicitly
     *       instead of the test being relaxed for everyone.</li>
     * </ol>
     *
     * <p>The MUTATION surface is narrower still: see {@link #resolveMutationTarget(String)}.
     *
     * @param key the storage key to resolve, in the shape held by {@code DataResource.objectInfo}
     * @return the absolute, normalised location that holds the content, which need not exist yet
     * @throws GeneralException if the key is null or empty, names no usable location, falls outside
     *     the configured allow list, or resolves outside every allowed root
     */
    private static Path resolvePath(String key) throws GeneralException {
        ContentStoreSupport.requireUsableKey(key);
        String prefix = ofbizHome();
        // FileUtil.getFile is used throughout rather than new File(String) so that a "component://" key
        // retains OFBiz resolution semantics; it answers null for a malformed "component://" location,
        // which is refused here rather than being dereferenced
        String identifier = ContentStoreSupport.reference(key);
        File resolved = requireLocation(FileUtil.getFile(key), identifier);
        File home = requireLocation(FileUtil.getFile(prefix), ContentStoreUtil.reference(prefix));
        if (resolved.getPath().startsWith(home.getPath())) {
            SecurityUtil.checkLocalFileAllowList(resolved);
        } else {
            String separator = "";
            if (key.indexOf('/') != 0 && prefix.lastIndexOf('/') != (prefix.length() - 1)) {
                separator = "/";
            }
            resolved = requireLocation(FileUtil.getFile(prefix + separator + key), identifier);
            SecurityUtil.checkOfbizFileAllowList(resolved);
        }
        checkFileBoundary(resolved, prefix);
        return confine(resolved.toPath(), identifier).toAbsolutePath().normalize();
    }

    /**
     * Checks that the given file resolves inside the given root directory.
     *
     * <p><strong>Both</strong> of two independent containment tests have to pass:
     * <ol>
     *   <li><em>Canonical</em> containment, which resolves every symbolic link on both sides before
     *       comparing, so a link planted anywhere along the path cannot put the real file outside the
     *       root while the spelling of the path still looks contained.</li>
     *   <li><em>Lexical</em> containment of the normalized absolute paths, which collapses
     *       {@code ".."} segments without touching the filesystem, so a traversal is still rejected
     *       when part of the path does not exist yet and canonicalisation therefore has nothing to
     *       resolve for it.</li>
     * </ol>
     *
     * <p><strong>Requiring both is the security contract of this provider.</strong> Accepting a path
     * because <em>either</em> test passed would let a symbolic link inside the storage root resolve to
     * any location the OFBiz process can reach - the lexical test still sees a contained path, so the
     * canonical failure would be overruled - which is precisely the path traversal this method exists
     * to stop. The wider OFBiz allow lists that {@link #resolveFile(String)} applies beforehand are
     * deliberately broad (the {@code ofbiz} list covers {@code applications/}, {@code themes/},
     * {@code plugins/} and {@code runtime/} and, by its own documented design, skips the
     * "must be under ofbiz.home" precondition) and therefore do not restore this narrower
     * per-provider boundary.
     *
     * <p>A mounted volume is unaffected, because mounting does not change a canonical path; a
     * symbolic link that redirects part of the tree outside the root is refused. See the operator
     * caveat on this class for the deployment consequence.
     *
     * <p>Package-private rather than private so that {@link ContentStoreFactory}'s storage-aware
     * bridge confines a {@code CONTEXT_FILE} location to its context root through this one
     * implementation instead of carrying a third copy of the same control.
     *
     * <p>A resolution failure is reported by the type of the underlying failure and never by its message.
     * The message produced by {@link File#getCanonicalPath()} embeds the path it was working on, and this
     * failure is composed into an exception that reaches a caller, so quoting it would publish the very
     * absolute path this method exists to keep inside the boundary.
     *
     * @param file the file to check
     * @param root the directory the file has to resolve inside of
     * @throws GeneralException if the file resolves outside the root under either test, or if either
     *     path cannot be resolved
     */
    static void checkFileBoundary(File file, String root) throws GeneralException {
        try {
            String canonicalAllowed = requireLocation(FileUtil.getFile(root),
                    ContentStoreUtil.reference(root)).getCanonicalPath();
            String canonicalFilePath = file.getCanonicalPath();
            boolean passesCanonical = canonicalFilePath.startsWith(canonicalAllowed + File.separator)
                    || canonicalFilePath.equals(canonicalAllowed);

            Path normalizedAllowed = Path.of(root).toAbsolutePath().normalize();
            Path normalizedFilePath = file.toPath().toAbsolutePath().normalize();
            boolean passesNormalized = normalizedFilePath.startsWith(normalizedAllowed);

            if (!passesCanonical || !passesNormalized) {
                throw new GeneralException("Access to file denied: path resolves outside of the allowed directory");
            }
        } catch (IOException e) {
            throw new GeneralException("Unable to validate file path: " + e.getClass().getName());
        }
    }

    /**
     * Rejects a location that could not be resolved at all.
     *
     * @param location the resolved location, or null when resolution failed
     * @param identifier the already-rendered identifier of what was being resolved, used only to build
     *     the message; never the raw text, so nothing request-supplied reaches the message
     * @return the location, unchanged
     * @throws GeneralException if the location is null
     */
    private static File requireLocation(File location, String identifier) throws GeneralException {
        if (location == null) {
            throw new GeneralException("Cannot resolve content storage location from [" + identifier
                    + "]: it names no usable location");
        }
        return location;
    }

    /**
     * Confines a resolved location to one of the allowed roots.
     *
     * <p>The test is purely canonical on both sides: symbolic links and mount points are resolved as
     * far as the location exists, and the not-yet-existing tail is appended in normalised form. A link
     * inside the tree that points out of it therefore fails the test, which a normalised-path test
     * would have passed because it never looks at the link at all.
     *
     * @param candidate the resolved location to confine
     * @param identifier the already-rendered identifier of the location, used only to build the message
     * @return the candidate, unchanged, so that this can be used inline
     * @throws GeneralException if the location resolves outside every allowed root, or if it cannot be
     *     canonicalised
     */
    private static Path confine(Path candidate, String identifier) throws GeneralException {
        Path canonical = canonicalise(candidate, identifier);
        for (Path root : allowedRoots()) {
            if (canonical.equals(root) || canonical.startsWith(root)) {
                return candidate;
            }
        }
        throw new GeneralException("Access to content denied: [" + identifier
                + "] resolves outside every allowed root");
    }

    /**
     * The roots content may canonically resolve inside: the OFBiz home directory, plus every root
     * declared by {@code content.store.filesystem.allowed.roots}.
     *
     * <p>Declaring additional roots is how a deployment whose upload location is a mount point or a
     * symbolic link - a network volume mounted on {@code runtime/}, for instance - is supported without
     * weakening the containment test for everyone else.
     *
     * @return the canonical allowed roots, never empty
     * @throws GeneralException if the OFBiz home directory or a declared root cannot be canonicalised
     */
    private static List<Path> allowedRoots() throws GeneralException {
        List<Path> roots = new ArrayList<>();
        String home = ofbizHome();
        roots.add(canonicalise(toPath(home, ContentStoreUtil.reference(home)), ContentStoreUtil.reference(home)));
        String declared = UtilProperties.getPropertyValue(ContentStoreSupport.PROPERTY_RESOURCE, ALLOWED_ROOTS_PROPERTY);
        if (UtilValidate.isNotEmpty(declared)) {
            for (String candidate : declared.split(ROOT_SEPARATOR)) {
                String trimmed = candidate.trim();
                if (!trimmed.isEmpty()) {
                    String rendered = ContentStoreUtil.describe(trimmed);
                    roots.add(canonicalise(toPath(trimmed, rendered), rendered));
                }
            }
        }
        return roots;
    }

    /**
     * Canonicalises a location that need not exist yet.
     *
     * <p>{@link Path#toRealPath} is applied to the deepest ancestor that does exist, which is what
     * resolves symbolic links and mount points, and the remaining names are appended in normalised
     * form. A location that does not exist at all still normalises, so traversal is collapsed either
     * way.
     *
     * @param candidate the location to canonicalise
     * @param identifier the already-rendered identifier of the location, used only to build the message
     * @return the canonical form of the location
     * @throws GeneralException if the location cannot be canonicalised
     */
    private static Path canonicalise(Path candidate, String identifier) throws GeneralException {
        Path absolute = candidate.toAbsolutePath().normalize();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return absolute;
        }
        try {
            Path real = existing.toRealPath();
            if (existing.getNameCount() >= absolute.getNameCount()) {
                return real;
            }
            return real.resolve(absolute.subpath(existing.getNameCount(), absolute.getNameCount()));
        } catch (IOException e) {
            throw new GeneralException("Unable to validate the content location [" + identifier + "]: "
                    + e.getClass().getSimpleName());
        }
    }

    /**
     * Converts text to a path, refusing text the platform cannot express as one.
     *
     * @param location the text to convert
     * @param identifier the already-rendered identifier of the location, used only to build the message
     * @return the path the text names
     * @throws GeneralException if the text is not a usable path on this platform
     */
    private static Path toPath(String location, String identifier) throws GeneralException {
        try {
            return Path.of(location);
        } catch (InvalidPathException e) {
            throw new GeneralException("Unusable content location [" + identifier + "]: "
                    + e.getClass().getSimpleName());
        }
    }

    /**
     * The OFBiz home directory every filesystem storage key is resolved against.
     *
     * @return the value of the {@code ofbiz.home} system property
     * @throws GeneralException if the property is not set, in which case no key can be resolved safely
     */
    private static String ofbizHome() throws GeneralException {
        String home = System.getProperty("ofbiz.home");
        if (UtilValidate.isEmpty(home)) {
            throw new GeneralException("Cannot address filesystem content storage: the ofbiz.home system property is not set");
        }
        return home;
    }

    /**
     * The one form in which this provider reports that a key addresses no stored content.
     *
     * @param key the storage key that addresses nothing
     * @return the exception to throw
     */
    private static FileNotFoundException absent(String key) {
        return new FileNotFoundException("No content found for " + ContentStoreUtil.reference(key));
    }

    /**
     * The one form in which this provider reports that a key addresses no stored content when the
     * filesystem is what established the absence.
     *
     * <p>The filesystem answer is <em>not</em> carried. A {@link NoSuchFileException} names the resolved
     * path in full - the one thing {@link #absent(String)} deliberately does not name - so attaching it
     * would republish, through any reader that prints the trace, exactly what the opaque reference exists
     * to withhold. Nothing diagnostic is lost: the absence is fully described by the key's reference, and
     * the type is named so a reader can tell a filesystem-established absence from one established by the
     * attribute check.
     *
     * @param key the storage key that addresses nothing
     * @param cause the filesystem answer that established the absence, named by type only
     * @return the exception to throw
     */
    private static FileNotFoundException absent(String key, NoSuchFileException cause) {
        return new FileNotFoundException("No content found for " + ContentStoreUtil.reference(key)
                + ": " + cause.getClass().getSimpleName());
    }
}
