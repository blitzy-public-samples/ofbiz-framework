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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.transaction.Status;
import javax.transaction.Synchronization;
import javax.transaction.SystemException;
import javax.transaction.TransactionManager;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.content.data.store.ContentStore.Description;
import org.apache.ofbiz.entity.transaction.GenericTransactionException;
import org.apache.ofbiz.entity.transaction.TransactionFactoryLoader;
import org.apache.ofbiz.entity.transaction.TransactionUtil;

/**
 * Moves file-backed content between an instance and the configured {@link ContentStore}.
 *
 * <p>This is the whole of the object-storage behaviour that the content component's file-resolution
 * methods delegate to: deriving a storage key, reading an object onto the instance, streaming one
 * straight through, publishing what a transaction wrote, and erasing content from both places. It lives
 * in the isolated store package rather than in the seam so that every rule below is reachable from a
 * unit test, and so that the seam stays a handful of delegating calls.
 *
 * <p><strong>Inert by default.</strong> Every entry point begins by resolving the provider and returns
 * immediately when there is none - {@code database} storage, the shipped default - or when it is
 * {@link FileSystemContentStore}, whose root already IS the directory the content lives in, so there is
 * nothing to move. No storage client is constructed and no behaviour changes in either case.
 *
 * <p><strong>Keys.</strong> A key is {@value #KEY_NAMESPACE} followed by the content's own
 * {@code ofbiz.home}-relative POSIX path, so one key names the same content on every instance. Content
 * outside {@code ofbiz.home} has no key and is neither published nor looked up. A key carries no tenant
 * scope, which is why {@link ContentStoreFactory} refuses to activate a store at all in a multi-tenant
 * deployment.
 *
 * <h2>Reading</h2>
 *
 * <p>The store is authoritative, and the read is arranged so that it costs one metadata request and
 * touches the local filesystem only when it genuinely has to:
 *
 * <ol>
 *   <li>{@link ContentStore#describe} - a {@code HeadObject}, no content transferred. An object the
 *       store does not hold ends the read here, with NO local side effect whatsoever: no directory is
 *       created and no temporary file is written, so a pure read of local-only content leaves the
 *       filesystem exactly as it was.</li>
 *   <li>A local copy LATER than the store's object is kept, and the store's object is NOT written over
 *       it. That is the one case where the local file may hold a write this seam never published - the
 *       create-file and update-file services compose their own path and never reach the seam - and
 *       silently replacing it would destroy it. The retention is reported once per key.</li>
 *   <li>A local copy whose size and modification time match the object is already the object: nothing
 *       is transferred and nothing is written. This is what lets an instance whose filesystem is
 *       read-only, or whose content directory is a read-only shared mount, serve content it holds.</li>
 *   <li>Otherwise the object is staged in the destination's own directory and moved onto the
 *       destination atomically, and the local modification time is then set to the object's own. That
 *       stamp is what makes step 3 hit on every subsequent read, and what keeps a read from looking
 *       like a write to the publication below.</li>
 * </ol>
 *
 * <p>A caller that only needs the bytes - serving a download, rendering a text resource - uses
 * {@link #open} instead, which streams the object straight out of the store and writes nothing locally
 * at all.
 *
 * <h2>Writing</h2>
 *
 * <p>Content is published to the store by the transaction that wrote it, in its
 * {@code beforeCompletion} callback, so a row can never commit while naming content the fleet cannot
 * read: a publication that fails rolls the transaction back. Two kinds of publication exist, and
 * neither of them ever watches storage shared with another transaction:
 *
 * <ul>
 *   <li>A NAMED publication watches exactly the file a caller resolved and is about to write. Whether
 *       it changed is decided by comparing its length and modification time with the ones recorded when
 *       the publication was registered, so a read - which resolves a file too, and cannot be told apart
 *       from a write here - publishes nothing.</li>
 *   <li>A TRANSACTION-OWNED DIRECTORY publication watches a directory created for one transaction
 *       alone. The upload services resolve an upload directory and then write into it through a path
 *       they compose themselves, so the file name cannot be known when the directory is resolved; with
 *       a store configured, {@link #publishedUploadPath} therefore hands the caller a private
 *       sub-directory of the upload shard instead of the shard itself. Every file in it at commit was
 *       written by this transaction, so all of them are published - and no file of a concurrent
 *       transaction can be published, refused or deleted by this one.</li>
 * </ul>
 *
 * <p>A transaction that does not commit deletes the objects it CREATED, on a best-effort basis. An
 * object that REPLACED one an earlier committed row still names is deliberately not in that list: this
 * publication holds no copy of the previous object, so deleting the key would destroy content instead
 * of restoring it.
 *
 * <p>Thread safe: it holds no mutable static state other than per-thread transaction scope, which is
 * created for one transaction, validated against the transaction it belongs to on every use, and
 * discarded when that transaction completes.
 *
 * @see ContentStore
 * @see ContentStoreFactory
 */
public final class ContentStorePublisher {

    /** The prefix every storage key carries, so one bucket can hold unrelated objects beside content. */
    public static final String KEY_NAMESPACE = "ofbiz";

    private static final String MODULE = ContentStorePublisher.class.getName();

    private static final String STAGING_SUFFIX = ".ofbizfetch";
    private static final String TRANSACTION_DIRECTORY_PREFIX = "txn-";
    private static final int DIGEST_BUFFER = 8192;

    /**
     * The transaction scope of the calling thread: the private upload directory the transaction was
     * given, and the publications already bound to it.
     */
    private static final ThreadLocal<TransactionScope> SCOPE = new ThreadLocal<>();

    /** Keys whose local retention has already been reported, so the warning is one per key per JVM. */
    private static final Set<String> RETENTION_REPORTED =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    private ContentStorePublisher() { }

    /**
     * What the store holds for one key, as an open stream and its length.
     *
     * @param stream the object's bytes, which the caller closes
     * @param length the object's length in bytes
     */
    public record StoredContent(InputStream stream, long length) { }

    /**
     * Returns the configured store when it holds content somewhere other than where the instance keeps
     * it, or null when there is nothing to move.
     *
     * @return the external store, or null for {@code database} and {@code filesystem} storage
     * @throws GeneralException if a provider is named but its configuration is unusable
     */
    public static ContentStore externalStore() throws GeneralException {
        ContentStore store = ContentStoreFactory.getContentStore();
        // A FileSystemContentStore's root IS the upload directory the content already lives in, so
        // publishing to it and reading from it would copy a file onto itself.
        return store instanceof FileSystemContentStore ? null : store;
    }

    /**
     * Returns the storage key naming the given content, or null when it cannot have one.
     *
     * @param file the content's location on this instance
     * @return the storage key, or null when the location is not inside {@code ofbiz.home}
     */
    public static String storeKey(File file) {
        String home = System.getProperty("ofbiz.home");
        if (file == null || UtilValidate.isEmpty(home)) {
            return null;
        }
        Path root = Paths.get(home).toAbsolutePath().normalize();
        Path target = file.toPath().toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            return null;
        }
        return KEY_NAMESPACE + "/" + root.relativize(target).toString().replace(File.separatorChar, '/');
    }

    /**
     * Makes the store's copy of the given content available at the given location on this instance.
     *
     * <p>The read described in the class comment. Absence is not failure: content the store does not
     * hold answers false and the caller falls back to the local copy exactly as it did before a store
     * existed. A store that cannot answer, or a local copy that cannot be written, raises instead -
     * reporting a fault as missing content would turn an outage into a 404 and hide it from the
     * operator.
     *
     * @param file the location the content would be read from on this instance, already authorised by
     *     the caller for this deployment
     * @return true when the content this location names is now readable on this instance
     * @throws GeneralException if a store is configured but could not be resolved, could not answer, or
     *     holds content this instance could not place
     */
    public static boolean fetch(File file) throws GeneralException {
        ContentStore store = externalStore();
        if (store == null) {
            return false;
        }
        return fetch(store, file);
    }

    /**
     * Performs the read of {@link #fetch(File)} against a given store.
     *
     * <p>Resolving which store is configured is separated from acting on it so that the read can be
     * driven against any {@link ContentStore} - which is how {@code ContentStoreFactoryTest} exercises
     * this engine, including the counting store that proves a matching local copy transfers nothing.
     * The public entry point resolves the store and calls this.
     *
     * @param store the store to read from, never null
     * @param file the location the content would be read from on this instance
     * @return true when the content this location names is now readable on this instance
     * @throws GeneralException if the store could not answer, or holds content this instance could not
     *     place
     */
    static boolean fetch(ContentStore store, File file) throws GeneralException {
        String key = storeKey(file);
        if (key == null) {
            return false;
        }
        Description held = describe(store, key);
        if (held == null) {
            return false;
        }
        Path target = file.toPath().toAbsolutePath();
        FileFacts local = facts(target);
        if (local != null) {
            if (local.modifiedAt() > held.modifiedAt()) {
                reportLocalRetention(key, target);
                return true;
            }
            if (local.length() == held.length() && local.modifiedAt() == held.modifiedAt()) {
                if (Debug.verboseOn()) {
                    Debug.logVerbose("Content [" + key + "] on this instance is already the object the content"
                            + " store holds, so nothing was transferred", MODULE);
                }
                return true;
            }
        }
        return materialise(store, key, held, target);
    }

    /**
     * Opens the store's copy of the given content, without writing anything on this instance.
     *
     * <p>For a caller that needs the bytes and not a file: serving a download, or rendering a text
     * resource into a writer. Nothing is staged, no directory is created and no local copy is made, so
     * this path works unchanged on an instance whose filesystem is read-only.
     *
     * @param file the location that names the content, already authorised by the caller for this
     *     deployment
     * @return the object's stream and length, or {@link Optional#empty()} when no store is configured or
     *     the store holds no object for this content, in which case the caller reads the local copy
     * @throws GeneralException if a store is configured but could not be resolved or could not answer
     */
    public static Optional<StoredContent> open(File file) throws GeneralException {
        ContentStore store = externalStore();
        if (store == null) {
            return Optional.empty();
        }
        return open(store, file);
    }

    /**
     * Performs the read of {@link #open(File)} against a given store.
     *
     * @param store the store to read from, never null
     * @param file the location that names the content
     * @return the object's stream and length, or {@link Optional#empty()} when the store holds no object
     *     for this content or a later local copy is preferred
     * @throws GeneralException if the store could not answer
     */
    static Optional<StoredContent> open(ContentStore store, File file) throws GeneralException {
        String key = storeKey(file);
        if (key == null) {
            return Optional.empty();
        }
        Description held = describe(store, key);
        if (held == null) {
            return Optional.empty();
        }
        FileFacts local = facts(file.toPath().toAbsolutePath());
        if (local != null && local.modifiedAt() > held.modifiedAt()) {
            // As in fetch(): a later local copy may hold an unpublished write, and it is what this
            // instance serves. Reported there, so it is not reported twice here.
            reportLocalRetention(key, file.toPath().toAbsolutePath());
            return Optional.empty();
        }
        try {
            return Optional.of(new StoredContent(store.openStream(key), held.length()));
        } catch (FileNotFoundException removed) {
            // Deleted between the metadata request and the read. The local copy answers, as for any
            // object the store does not hold.
            return Optional.empty();
        } catch (IOException failure) {
            throw new GeneralException("The content store could not be read for [" + key + "]", failure);
        }
    }

    /**
     * Erases the content the given location names, from the store and from this instance.
     *
     * <p>The authorised erasure entry point. It exists because removing a {@code DataResource} row has
     * never removed the file it names - the row is deleted by the entity engine and the bytes stay
     * where they are - so with a store configured the object would stay in it just as the file stays on
     * disk, and deleting the local file alone would not erase anything, because the next read would
     * fetch the object again. Erasing both is the only operation that does.
     *
     * <p>Idempotent, and safe to call when no store is configured, in which case it removes the local
     * file alone. DOCKER.adoc carries the bucket retention and purge procedure that goes with it.
     *
     * @param file the content's location on this instance, already authorised by the caller
     * @return true when this instance no longer holds the content and the store no longer holds its
     *     object
     * @throws GeneralException if the store is configured but the object could not be removed. The
     *     local file is removed first only when the object was, so a failure never leaves content
     *     readable from the store while the row that named it is gone.
     */
    public static boolean erase(File file) throws GeneralException {
        if (file == null) {
            return false;
        }
        return erase(externalStore(), file);
    }

    /**
     * Performs the erasure of {@link #erase(File)} against a given store.
     *
     * @param store the store to erase from, or null when none is configured, in which case the local
     *     file alone is removed
     * @param file the content's location on this instance
     * @return true when neither this instance nor the store holds the content any longer
     * @throws GeneralException if the store is configured but the object could not be removed
     */
    static boolean erase(ContentStore store, File file) throws GeneralException {
        if (file == null) {
            return false;
        }
        String key = storeKey(file);
        if (store != null && key != null) {
            try {
                store.delete(key);
                Debug.logInfo("Content [" + key + "] was erased from the content store", MODULE);
            } catch (GeneralException | IOException failure) {
                throw new GeneralException("Content [" + key + "] could not be erased from the content store, so"
                        + " the local copy is kept: erasing one of the two would leave the content readable from"
                        + " the other", failure);
            }
        }
        try {
            Files.deleteIfExists(file.toPath());
            return true;
        } catch (IOException failure) {
            throw new GeneralException("Content [" + file.getAbsolutePath() + "] could not be erased from this"
                    + " instance", failure);
        }
    }

    /**
     * Binds the one file a caller has just resolved, and is about to write, to its transaction.
     *
     * <p>A read resolves a file too and cannot be told apart from a write here, so the publication is
     * registered either way: at commit the file is published only if it actually changed, and neither a
     * read nor a fetch changes it - {@link #fetch} sets the fetched file's modification time to the
     * object's own, so the state it leaves behind is recognisably not a write.
     *
     * @param file the file that was resolved, may be null when the resource type is not file backed
     */
    public static void bindWrittenFile(File file) {
        if (file == null || file.getParentFile() == null) {
            return;
        }
        bind(file.getParentFile(), Set.of(file.getName()), false);
    }

    /**
     * Returns the directory an upload should be written into, scoped to the calling transaction when a
     * store is configured, and binds its content to that transaction.
     *
     * <p>With no store configured the resolved upload directory is answered UNCHANGED, so nothing about
     * where an upload lands differs from the shipped behaviour.
     *
     * <p>With a store configured the answer is a private sub-directory of that directory, created for
     * this transaction alone. That is what makes the publication safe: the upload services write a file
     * whose name this method cannot know, so the publication has to watch a directory, and a directory
     * shared with concurrent transactions would let this transaction publish - or, on rollback, delete -
     * another transaction's content. The sub-directory name becomes part of the {@code objectInfo} the
     * caller stores and therefore part of the storage key, so it is stable for the life of the content.
     *
     * <p>It is deliberately FAIL-FAST: an upload that cannot be bound to a transaction would commit a
     * row naming content only this instance can read.
     *
     * @param uploadPath the upload directory that was resolved
     * @param absolute whether that value is absolute rather than {@code ofbiz.home}-relative
     * @return the directory to write into, in the same form as {@code uploadPath}
     * @throws IllegalStateException if a store is configured and the upload cannot be published to it:
     *     the store could not be resolved, the upload directory is outside {@code ofbiz.home}, the
     *     private sub-directory could not be created, or no transaction is active to publish in
     */
    public static String publishedUploadPath(String uploadPath, boolean absolute) {
        if (UtilValidate.isEmpty(uploadPath)) {
            return uploadPath;
        }
        ContentStore store;
        try {
            store = externalStore();
        } catch (GeneralException e) {
            throw new IllegalStateException("An upload directory cannot be prepared: the configured content store"
                    + " could not be resolved, so content written into it could not be published to it", e);
        }
        if (store == null) {
            return uploadPath;
        }
        String home = System.getProperty("ofbiz.home");
        File shard = new File(absolute ? uploadPath : home + uploadPath);
        if (storeKey(shard) == null) {
            throw new IllegalStateException("An upload directory outside the OFBiz home directory cannot be"
                    + " published to the content store: [" + shard.getAbsolutePath() + "]");
        }
        TransactionScope scope = activeScope("An upload directory was resolved outside an active transaction"
                + " while a content store is configured, so content written into it could not be published"
                + " before the row naming it commits. Resolve the upload path inside the transaction that"
                + " writes the file.");
        File privateDirectory = new File(shard, scope.uploadDirectoryName());
        try {
            Files.createDirectories(privateDirectory.toPath());
        } catch (IOException unwritable) {
            throw new IllegalStateException("The upload directory [" + privateDirectory.getAbsolutePath()
                    + "] could not be created on this instance", unwritable);
        }
        bind(privateDirectory, null, true);
        return uploadPath + "/" + scope.uploadDirectoryName();
    }

    /**
     * Drops the calling thread's transaction scope.
     *
     * <p>Called by a completed publication, and available to a test that has to leave no per-thread
     * state behind.
     */
    static void clearScope() {
        SCOPE.remove();
    }

    /**
     * Registers a publication with the current transaction.
     *
     * @param directory the directory to watch
     * @param names the file names to watch, or null to watch every direct child of a directory this
     *     transaction owns
     * @param writing whether this is a write path, where the absence of a usable transaction is a fault.
     *     A read path reports and carries on, because it has nothing to publish
     */
    private static void bind(File directory, Set<String> names, boolean writing) {
        ContentStore store;
        try {
            store = externalStore();
        } catch (GeneralException e) {
            if (!writing) {
                Debug.logError(e, "The configured content store could not be resolved", MODULE);
                return;
            }
            throw new IllegalStateException("Content cannot be written: the configured content store could not"
                    + " be resolved, so what is written could not be published to it", e);
        }
        if (store == null) {
            return;
        }
        if (storeKey(directory) == null) {
            if (!writing) {
                return;
            }
            throw new IllegalStateException("Content outside the OFBiz home directory cannot be published to the"
                    + " content store: [" + directory.getAbsolutePath() + "]");
        }
        String identity = directory.getAbsolutePath() + (names == null ? "" : names);
        TransactionScope scope;
        try {
            if (TransactionUtil.getStatus() != Status.STATUS_ACTIVE) {
                if (!writing) {
                    // A resolution outside a transaction is a read: there is no write to publish, and
                    // nothing to bind a publication to.
                    return;
                }
                throw new IllegalStateException("Content was resolved outside an active transaction while a"
                        + " content store is configured, so it could not be published before the row naming it"
                        + " commits.");
            }
            scope = scope();
            if (!scope.claim(identity)) {
                // Already bound in this transaction. Two resolutions of one target are one piece of work,
                // and it is the state at commit that decides what that work is, so the first binding
                // covers the second.
                return;
            }
            TransactionUtil.registerSynchronization(new ContentPublication(store, directory, names, identity));
        } catch (GenericTransactionException e) {
            TransactionScope held = SCOPE.get();
            if (held != null) {
                held.release(identity);
            }
            if (!writing) {
                Debug.logError(e, "A content store publication could not be bound to the current transaction",
                        MODULE);
                return;
            }
            throw new IllegalStateException("Content cannot be written: a publication to the content store could"
                    + " not be bound to the transaction that resolved it", e);
        }
    }

    /**
     * Asks the store what it holds, translating a failure into the caller's exception type.
     *
     * @param store the store
     * @param key the storage key
     * @return what the store holds, or null when it holds no object under that key
     * @throws GeneralException if the store could not answer
     */
    private static Description describe(ContentStore store, String key) throws GeneralException {
        try {
            return store.describe(key).orElse(null);
        } catch (IOException failure) {
            throw new GeneralException("The content store could not be asked about [" + key + "]", failure);
        }
    }

    /**
     * Writes the store's object onto this instance, without destroying a local copy that differs from it
     * only because the store replaced its own object.
     *
     * @param store the store
     * @param key the storage key
     * @param held what the store reported holding
     * @param target the location to place the content at
     * @return true when the content is now readable at that location, false when the object was removed
     *     from the store between the metadata request and the read
     * @throws GeneralException if the store could not be read or this instance could not be written
     */
    private static boolean materialise(ContentStore store, String key, Description held, Path target)
            throws GeneralException {
        Path directory = target.getParent();
        Path staged;
        try {
            Files.createDirectories(directory);
            staged = Files.createTempFile(directory, target.getFileName().toString(), STAGING_SUFFIX);
        } catch (IOException unwritable) {
            // Attributed to the LOCAL filesystem, deliberately: the store answered, and reporting this as
            // a storage failure would send an operator looking in the wrong place. A read-only root
            // filesystem is the usual cause - see DOCKER.adoc, which records that the content directory
            // has to be writable for an instance to take delivery of content it does not already hold.
            throw new GeneralException("Content [" + key + "] is held by the content store, but this instance"
                    + " could not place it at [" + target + "]: its own filesystem could not be written."
                    + " The directory holding content must be writable by the OFBiz user, or the content must"
                    + " already be present on the instance.", unwritable);
        }
        try {
            try (InputStream content = store.openStream(key)) {
                Files.copy(content, staged, StandardCopyOption.REPLACE_EXISTING);
            }
            if (sameContent(staged, target)) {
                // The store holds exactly what is already here; only the recorded time differed. The local
                // file is left in place - keeping its inode, its permissions and any hard link to it - and
                // only its modification time is aligned, so the next read needs no transfer at all.
                stamp(target, held.modifiedAt());
                return true;
            }
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            stamp(target, held.modifiedAt());
            if (Debug.verboseOn()) {
                Debug.logVerbose("Content [" + key + "] was read from the content store onto this instance",
                        MODULE);
            }
            return true;
        } catch (FileNotFoundException removed) {
            return false;
        } catch (IOException failure) {
            throw new GeneralException("The content store could not be read for [" + key + "]", failure);
        } finally {
            removeQuietly(staged);
        }
    }

    /**
     * Sets a local file's modification time to the store object's own.
     *
     * <p>This is what makes the whole read model cheap and non-destructive at the same time: a later
     * read recognises the local copy AS the object and transfers nothing, and a local file that is
     * NEWER than the object is therefore recognisable as a write this seam never published. Failing to
     * set it is not an error - the next read simply compares content again - so a filesystem that
     * refuses is logged at verbose and nothing else.
     *
     * @param target the local file
     * @param modifiedAt the object's modification time, in epoch milliseconds
     */
    private static void stamp(Path target, long modifiedAt) {
        if (modifiedAt <= 0L) {
            return;
        }
        try {
            Files.setLastModifiedTime(target, FileTime.fromMillis(modifiedAt));
        } catch (IOException refused) {
            if (Debug.verboseOn()) {
                Debug.logVerbose("The modification time of [" + target + "] could not be aligned with the"
                        + " content store's object, so the next read compares content again: "
                        + refused.getMessage(), MODULE);
            }
        }
    }

    /**
     * Reports, once per key, that a local copy later than the store's object was kept.
     *
     * @param key the storage key
     * @param target the local file that was kept
     */
    private static void reportLocalRetention(String key, Path target) {
        if (!RETENTION_REPORTED.add(key)) {
            return;
        }
        Debug.logWarning("Content [" + target + "] on this instance is NEWER than the object the content store"
                + " holds for [" + key + "], so the local copy is served and the object is not written over it."
                + " A local write that the content-store seam never saw - a create-file or update-file service"
                + " call that composed its own path - is the usual cause, and that content is not in the store,"
                + " so another instance cannot serve it. Publish it with the migration procedure in DOCKER.adoc."
                + " This is reported once per object for the life of the instance.", MODULE);
    }

    /**
     * Reports whether two files hold the same bytes.
     *
     * @param staged the freshly downloaded object
     * @param target the local file, which may not exist
     * @return true when both exist and hold identical content
     */
    private static boolean sameContent(Path staged, Path target) {
        try {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(staged) != Files.size(target)) {
                return false;
            }
            return Arrays.equals(digest(staged), digest(target));
        } catch (IOException unreadable) {
            return false;
        }
    }

    /**
     * Returns a SHA-256 digest of a file's content.
     *
     * <p>Used to compare two local files, never as a security control.
     *
     * @param path the file
     * @return the digest
     * @throws IOException if the file cannot be read
     */
    private static byte[] digest(Path path) throws IOException {
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            // SHA-256 is mandatory on every Java platform.
            throw new IOException("SHA-256 is required to compare content", unavailable);
        }
        try (InputStream content = Files.newInputStream(path)) {
            byte[] buffer = new byte[DIGEST_BUFFER];
            for (int read = content.read(buffer); read >= 0; read = content.read(buffer)) {
                sha256.update(buffer, 0, read);
            }
        }
        return sha256.digest();
    }

    /**
     * Returns what a local file holds, or null when it is not a regular file.
     *
     * @param path the file
     * @return its length and modification time, or null
     */
    private static FileFacts facts(Path path) {
        try {
            BasicFileAttributes held = Files.readAttributes(path, BasicFileAttributes.class);
            return held.isRegularFile()
                    ? new FileFacts(held.size(), held.lastModifiedTime().toMillis())
                    : null;
        } catch (NoSuchFileException absent) {
            return null;
        } catch (IOException unreadable) {
            return null;
        }
    }

    private static void removeQuietly(Path staged) {
        if (staged == null) {
            return;
        }
        try {
            Files.deleteIfExists(staged);
        } catch (IOException e) {
            Debug.logWarning(e, "A staging file left by a content store read could not be removed: " + staged,
                    MODULE);
        }
    }

    /**
     * Returns the calling thread's scope for the transaction that is active now, creating it if needed.
     *
     * @return the scope
     */
    private static TransactionScope scope() {
        Object current = currentTransaction();
        TransactionScope held = SCOPE.get();
        // Identity, not merely presence: a suspended or abandoned transaction leaves its scope on a
        // pooled thread, and a later transaction that inherited the thread must not be able to reuse it
        // and skip binding a publication of its own.
        if (held != null && held.belongsTo(current)) {
            return held;
        }
        TransactionScope fresh = new TransactionScope(current);
        SCOPE.set(fresh);
        return fresh;
    }

    /**
     * Returns the scope of the active transaction, refusing when there is none.
     *
     * @param refusal the message to fail with when no transaction is active
     * @return the scope
     * @throws IllegalStateException with the given message when no transaction is active
     */
    private static TransactionScope activeScope(String refusal) {
        try {
            if (TransactionUtil.getStatus() != Status.STATUS_ACTIVE) {
                throw new IllegalStateException(refusal);
            }
        } catch (GenericTransactionException unavailable) {
            throw new IllegalStateException(refusal, unavailable);
        }
        return scope();
    }

    /**
     * Returns the transaction the calling thread is in, as an identity to compare scopes against.
     *
     * @return the transaction, or null when it cannot be established
     */
    private static Object currentTransaction() {
        try {
            TransactionManager manager = TransactionFactoryLoader.getInstance().getTransactionManager();
            if (manager == null) {
                return null;
            }
            return manager.getTransaction();
        } catch (SystemException | RuntimeException unavailable) {
            if (Debug.verboseOn()) {
                Debug.logVerbose("The current transaction could not be identified for a content store"
                        + " publication scope: " + unavailable.getMessage(), MODULE);
            }
            return null;
        }
    }

    /** What a watched file held, for the change comparison. */
    private record FileFacts(long length, long modifiedAt) { }

    /**
     * The per-thread state of one transaction: its private upload directory and its bound publications.
     */
    private static final class TransactionScope {

        private final Object transaction;
        private final Set<String> bound = new HashSet<>();
        private String uploadDirectory;

        private TransactionScope(Object owner) {
            this.transaction = owner;
        }

        private boolean belongsTo(Object candidate) {
            return transaction != null && transaction == candidate;
        }

        /**
         * Returns the name of this transaction's private upload directory, creating it on first use so
         * that two uploads in one transaction share one directory.
         *
         * @return the directory name
         */
        private String uploadDirectoryName() {
            if (uploadDirectory == null) {
                uploadDirectory = TRANSACTION_DIRECTORY_PREFIX
                        + UUID.randomUUID().toString().replace("-", "");
            }
            return uploadDirectory;
        }

        private boolean claim(String identity) {
            return bound.add(identity);
        }

        private void release(String identity) {
            bound.remove(identity);
        }

        private void completed(String identity) {
            bound.remove(identity);
            if (bound.isEmpty()) {
                // Removed rather than left empty, so a pooled thread does not carry the scope for the life
                // of the JVM.
                clearScope();
            }
        }
    }

    /**
     * Publishes the content a transaction wrote, before that transaction commits.
     *
     * <p>Registered by {@link #bind} once per watched identity per transaction, and used only by the
     * transaction that registered it.
     */
    private static final class ContentPublication implements Synchronization {

        private final ContentStore store;
        private final File directory;
        private final Set<String> names;
        private final String identity;
        private final Map<String, FileFacts> before;
        private final long registeredAt;
        private final List<String> created = new LinkedList<>();

        private ContentPublication(ContentStore store, File directory, Set<String> names, String identity) {
            this.store = store;
            this.directory = directory;
            this.names = names;
            this.identity = identity;
            this.registeredAt = System.currentTimeMillis();
            this.before = snapshot(directory, names);
        }

        @Override
        public void beforeCompletion() {
            for (File candidate : candidates()) {
                if (changed(candidate)) {
                    publish(candidate);
                }
            }
        }

        @Override
        public void afterCompletion(int status) {
            TransactionScope scope = SCOPE.get();
            if (scope != null) {
                scope.completed(identity);
            }
            if (status == Status.STATUS_COMMITTED || created.isEmpty()) {
                return;
            }
            // The transaction did not commit, so the objects it CREATED are named by no committed row and
            // are removed again, on a best-effort basis, rather than left as orphans. A key that REPLACED
            // an object an earlier committed row still names is deliberately not in this list: this
            // publication holds no copy of the previous object, so deleting the key would destroy that
            // content instead of restoring it.
            for (String key : created) {
                try {
                    store.delete(key);
                    Debug.logInfo("Content [" + key + "] was removed from the content store, because the"
                            + " transaction that wrote it did not commit", MODULE);
                } catch (GeneralException | IOException failure) {
                    Debug.logError(failure, "Content [" + key + "] was published by a transaction that did not"
                            + " commit and could not be removed again, so it is now an orphan in the content"
                            + " store", MODULE);
                }
            }
        }

        /**
         * Returns the files this publication is responsible for.
         *
         * <p>For a named publication that is the named files. For a directory publication the directory
         * belongs to this transaction alone - {@link #publishedUploadPath} created it for one transaction
         * and nothing else writes into it - so every regular file in it was written here.
         *
         * @return the candidate files
         */
        private List<File> candidates() {
            if (names != null) {
                List<File> named = new ArrayList<>(names.size());
                for (String name : names) {
                    named.add(new File(directory, name));
                }
                return named;
            }
            File[] children = directory.listFiles();
            if (children == null) {
                // Being unable to inspect the directory is a publication FAILURE and never a quiet
                // success: returning here would let the transaction commit rows naming content that was
                // never published.
                throw refuse(directory.getAbsolutePath(),
                        new IOException("the upload directory could not be listed"));
            }
            return Arrays.asList(children);
        }

        /**
         * Reports whether a candidate holds content this transaction has to publish.
         *
         * @param candidate the candidate file
         * @return true when it must be published
         */
        private boolean changed(File candidate) {
            if (!candidate.isFile()) {
                return false;
            }
            if (names == null) {
                // A directory this transaction owns started out empty, so every file in it is this
                // transaction's own write. No comparison is needed, and none would be sound: the file was
                // created after the snapshot by definition.
                return true;
            }
            FileFacts earlier = before.get(candidate.getName());
            // A HEURISTIC, deliberately, rather than a digest: a named file counts as written when it was
            // not there when the publication was registered, or its length changed, or its modification
            // time changed, or it was touched at or after the instant the publication was registered.
            // Hashing it instead would make every resolution pay for reading the whole file inside the
            // transaction.
            // A READ does not trip it: a fetch from the store sets the local modification time to the
            // object's own, which is in the past, so neither the recorded facts nor the registration
            // instant sees a change. Its limit: on a filesystem that refuses to set a modification time,
            // a fetched file keeps the time of the fetch and is republished once - the same bytes, under
            // the same key.
            return earlier == null
                    || earlier.length() != candidate.length()
                    || earlier.modifiedAt() != candidate.lastModified()
                    || candidate.lastModified() >= registeredAt;
        }

        /**
         * Publishes one file, refusing the transaction when the store will not take it, and records
         * whether the object was created rather than replaced.
         *
         * @param file the file to publish
         */
        private void publish(File file) {
            String key = storeKey(file);
            if (key == null) {
                throw refuse(file.getAbsolutePath(),
                        new IOException("the file is not inside the OFBiz home directory"));
            }
            if (Files.isSymbolicLink(file.toPath())) {
                throw refuse(key, new IOException("the file is a symbolic link, and only a regular file inside the"
                        + " deployment is published to the content store"));
            }
            long length = file.length();
            if (length > ContentStore.MAX_OBJECT_BYTES) {
                throw refuse(key, new IOException("the file holds " + length + " bytes, more than the "
                        + ContentStore.MAX_OBJECT_BYTES + " bytes one content store object may hold"));
            }
            // Asked before the write, because only an object this transaction created may be removed
            // again after a non-commit. A store that cannot answer is treated as already holding the key,
            // so the cleanup errs towards leaving an object behind rather than deleting content an
            // earlier committed row may still name.
            boolean replacement = true;
            try {
                replacement = store.exists(key);
            } catch (GeneralException | IOException unknown) {
                Debug.logWarning(unknown, "The content store could not report whether it already held ["
                        + key + "], so it is left in place if this transaction does not commit", MODULE);
            }
            try {
                store.put(key, Files.readAllBytes(file.toPath()));
                if (!replacement) {
                    created.add(key);
                }
                // The local copy and the object are the same content from here on, and the object's
                // recorded time is the moment of the write, so the local time is left where it is: a read
                // compares content, finds it identical, and aligns the time without transferring again.
                Debug.logInfo("Content [" + key + "] was published to the content store", MODULE);
            } catch (GeneralException | IOException failure) {
                throw refuse(key, failure);
            }
        }

        /**
         * Refuses the transaction, because content it wrote is not in the store.
         *
         * <p>The transaction is both marked for rollback and failed by throwing. Marking it is the
         * explicit request; throwing is what still rolls it back if the mark could not be set, because a
         * transaction manager rolls back a transaction whose {@code beforeCompletion} callback threw. A
         * row naming content the fleet cannot read must never commit, so there is no path here that
         * merely logs.
         *
         * @param reference what could not be published
         * @param failure why
         * @return the exception the caller throws
         */
        private IllegalStateException refuse(String reference, Throwable failure) {
            String message = "Content [" + reference + "] could not be published to the content store, so the"
                    + " transaction that wrote it is rolled back rather than committing a row that names content"
                    + " the fleet cannot read";
            Debug.logError(failure, message, MODULE);
            try {
                TransactionUtil.setRollbackOnly(message, failure);
            } catch (GenericTransactionException e) {
                Debug.logError(e, "The transaction could not be marked for rollback after a content store"
                        + " publication failed", MODULE);
            }
            return new IllegalStateException(message, failure);
        }

        /**
         * Records what the watched files held before anything was written.
         *
         * @param directory the watched directory
         * @param names the watched file names, or null for a directory this transaction owns, which
         *     starts out empty and therefore needs no snapshot
         * @return what each watched file held, by file name
         */
        private static Map<String, FileFacts> snapshot(File directory, Set<String> names) {
            Map<String, FileFacts> held = new LinkedHashMap<>();
            if (names == null) {
                return held;
            }
            for (String name : names) {
                File child = new File(directory, name);
                if (child.isFile()) {
                    held.put(name, new FileFacts(child.length(), child.lastModified()));
                }
            }
            return held;
        }
    }
}
