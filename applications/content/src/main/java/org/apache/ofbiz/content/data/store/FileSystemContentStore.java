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

import java.io.ByteArrayOutputStream;
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
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.FileUtil;
import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.util.EntityUtilProperties;

/**
 * The filesystem content-storage provider.
 *
 * <p>Its storage root is the upload directory named by {@code content.upload.path.prefix} -
 * {@code runtime/uploads} out of the box - resolved under {@code ofbiz.home}. The sharding rule that
 * spreads uploads over timestamped sub-directories {@code content.upload.max.files} at a time is
 * implemented here, by the {@link #getUploadPath} overloads {@code DataResourceWorker} delegates to,
 * so selecting this provider does not change the directory an upload lands in. It needs no
 * credentials, and it becomes a fleet-wide store when that directory is a shared mount.
 *
 * <p><strong>Keys</strong> are resolved beneath the storage root and confined to it at operation
 * time, against the root's <em>real</em> path rather than a lexically normalised one. The key grammar
 * ({@link ContentStoreFactory#requireUsableKey}) rules out relative segments, but only resolving the
 * real path rules out a symbolic link planted inside the tree that would otherwise redirect a read, a
 * write or a delete outside the root.
 *
 * <p><strong>Replacement is atomic</strong>, as {@link ContentStore#put} requires: content is written
 * to a staging file in the destination's own directory and then moved onto the destination with
 * {@link StandardCopyOption#ATOMIC_MOVE}. A reader therefore sees either the previous file or the new
 * one. Staging in the destination directory rather than in a temporary directory is what makes the
 * move a rename within one filesystem, which is the only way it can be atomic. The staging file is
 * given the permissions of an ordinary write before it is moved, so a stored object is readable by the
 * same users as the file it replaced.
 *
 * <p>Thread safe: it holds only the immutable storage root.
 */
public final class FileSystemContentStore implements ContentStore {

    private static final String MODULE = FileSystemContentStore.class.getName();

    private static final String UPLOAD_PREFIX_PROPERTY = "content.upload.path.prefix";
    private static final String MAX_FILES_PROPERTY = "content.upload.max.files";
    private static final String DEFAULT_UPLOAD_PREFIX = "runtime/uploads";
    private static final double DEFAULT_MAX_FILES = 250;
    private static final String STAGING_SUFFIX = ".ofbizstore";
    private static final int BUFFER_SIZE = 8192;

    /**
     * The permissions a stored object is given, matching the umask-default 0644 an ordinary write
     * produced before this provider existed.
     *
     * <p>Set EXPLICITLY, because content is written through {@code Files.createTempFile}, which creates
     * a file readable and writable by its owner alone. Leaving that in place would silently narrow the
     * permissions of every stored object and break a sidecar, static file server or backup agent that
     * runs as another user - a behaviour change this provider must not make. It is applied on a
     * best-effort basis: a filesystem with no POSIX permission view keeps whatever it created.
     */
    private static final Set<PosixFilePermission> STORED_OBJECT_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ);

    private final Path root;

    FileSystemContentStore() throws GeneralException {
        this(storageRootPath());
    }

    /**
     * Builds a provider on a named storage root instead of the configured upload directory.
     *
     * <p>The root-injecting constructor the configured one delegates to. It is also how
     * {@code ContentStoreFactoryTest} exercises every operation of this provider against a temporary
     * directory, without depending on {@code ofbiz.home} or on the deployment's own upload directory.
     *
     * @param directory the storage root
     * @throws GeneralException if no storage root was given
     */
    FileSystemContentStore(String directory) throws GeneralException {
        if (UtilValidate.isEmpty(directory)) {
            throw new GeneralException("The filesystem content store has no storage root");
        }
        this.root = Paths.get(directory).toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, byte[] data) throws GeneralException, IOException {
        if (data == null) {
            throw new IOException("Content is required to store [" + key + "]");
        }
        if (data.length > MAX_OBJECT_BYTES) {
            throw new IOException("Content of [" + key + "] holds " + data.length + " bytes, more than the "
                    + MAX_OBJECT_BYTES + " bytes one object may hold; stream it instead");
        }
        Path target = resolve(key, true);
        Path directory = target.getParent();
        Files.createDirectories(directory);
        Path staged = Files.createTempFile(directory, target.getFileName().toString(), STAGING_SUFFIX);
        try {
            Files.write(staged, data, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            applyStoredObjectPermissions(staged);
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    @Override
    public byte[] get(String key) throws GeneralException, IOException {
        // Read through the bounded stream rather than checking the size first: a size check followed by
        // a separate read can be defeated by a file that grows between the two, which would let an
        // object past the in-memory limit after all.
        try (InputStream content = openStream(key)) {
            return readBounded(content, key);
        }
    }

    @Override
    public InputStream openStream(String key) throws GeneralException, IOException {
        Path target = resolve(key, false);
        try {
            // NOFOLLOW_LINKS: a stored object is a regular file. Refusing to follow a link here is the
            // last of the three confinement checks, after the key grammar and the real-path comparison.
            return Files.newInputStream(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) {
            throw absence(key, absent);
        }
    }

    @Override
    public boolean exists(String key) throws GeneralException, IOException {
        return Files.isRegularFile(resolve(key, false), LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public Optional<Description> describe(String key) throws GeneralException, IOException {
        Path target = resolve(key, false);
        try {
            BasicFileAttributes held = Files.readAttributes(target, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!held.isRegularFile()) {
                return Optional.empty();
            }
            // The tag is the size and the modification time, which is what a filesystem can say about a
            // version without reading the bytes. It is opaque to the caller, which only compares it with
            // a tag this same provider produced.
            return Optional.of(new Description(held.size(), held.lastModifiedTime().toMillis(),
                    held.size() + "-" + held.lastModifiedTime().toMillis()));
        } catch (NoSuchFileException absent) {
            return Optional.empty();
        }
    }

    @Override
    public void delete(String key) throws GeneralException, IOException {
        Files.deleteIfExists(resolve(key, false));
    }

    /**
     * Returns the absolute path of the directory an upload should be written into.
     *
     * @param absolute whether to answer an absolute path rather than an {@code ofbiz.home}-relative one
     * @return the upload directory
     */
    public static String getUploadPath(boolean absolute) {
        return getUploadPath(UtilProperties.getPropertyValue("content", UPLOAD_PREFIX_PROPERTY, DEFAULT_UPLOAD_PREFIX),
                maxFiles(), absolute);
    }

    /**
     * Returns the directory an upload should be written into, honouring a delegator's own
     * {@code SystemProperty} override of the upload prefix.
     *
     * @param delegator the delegator whose configuration applies
     * @param absolute whether to answer an absolute path rather than an {@code ofbiz.home}-relative one
     * @return the upload directory
     */
    public static String getUploadPath(Delegator delegator, boolean absolute) {
        return getUploadPath(EntityUtilProperties.getPropertyValue("content", UPLOAD_PREFIX_PROPERTY,
                DEFAULT_UPLOAD_PREFIX, delegator), maxFiles(), absolute);
    }

    /**
     * Handles creating sub-directories for file storage, using a maximum number of files per directory.
     *
     * <p>This is the sharding rule OFBiz has always applied to uploads, unchanged: the most recently
     * modified sub-directory of the upload prefix is reused until it holds {@code maxFiles} entries,
     * at which point a new sub-directory named after the current epoch millisecond is created.
     *
     * @param initialPath the top level location where all files should be stored
     * @param maxFiles the maximum number of files to place in a directory
     * @param absolute whether to answer an absolute path rather than an {@code ofbiz.home}-relative one
     * @return the path of the directory where the file should be placed
     */
    public static String getUploadPath(String initialPath, double maxFiles, boolean absolute) {
        String ofbizHome = System.getProperty("ofbiz.home");
        String prefix = initialPath;

        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }

        Comparator<Object> desc = (o1, o2) -> {
            if ((Long) o1 > (Long) o2) {
                return -1;
            } else if ((Long) o1 < (Long) o2) {
                return 1;
            }
            return 0;
        };

        String parentDir = ofbizHome + prefix;
        File parent = FileUtil.getFile(parentDir);
        TreeMap<Long, File> dirMap = new TreeMap<>(desc);
        if (parent.exists()) {
            File[] subs = parent.listFiles();
            if (subs != null) {
                for (File sub : subs) {
                    if (sub.isDirectory()) {
                        dirMap.put(sub.lastModified(), sub);
                    }
                }
            }
        } else {
            boolean created = parent.mkdir();
            if (!created) {
                Debug.logWarning("Unable to create top level upload directory [" + parentDir + "].", MODULE);
            }
        }

        File latestDir = null;
        if (UtilValidate.isNotEmpty(dirMap)) {
            latestDir = dirMap.values().iterator().next();
            if (latestDir != null) {
                File[] dirList = latestDir.listFiles();
                if (dirList != null) {
                    int length = dirList.length;
                    if (length >= maxFiles) {
                        latestDir = makeNewDirectory(parent);
                    }
                }
            }
        } else {
            latestDir = makeNewDirectory(parent);
        }
        if (latestDir == null) {
            // Neither an existing sub-directory nor a newly created one: the upload directory cannot be
            // used at all, and answering a path derived from null would fail later with a
            // NullPointerException instead of naming the cause.
            throw new IllegalStateException("No upload directory could be resolved under [" + parentDir
                    + "]: it holds no sub-directory and none could be created. Check that the directory"
                    + " exists and is writable by the OFBiz user.");
        }

        // Verbose rather than info: this resolves on every upload-path lookup in every mode, so at info
        // level it is one line per content operation for a value the caller already has.
        if (Debug.verboseOn()) {
            Debug.logVerbose("Upload directory resolved to [" + latestDir.getName() + "]", MODULE);
        }
        if (absolute) {
            return latestDir.getAbsolutePath().replace('\\', '/');
        }
        return prefix + "/" + latestDir.getName();
    }

    static String configurationSignature() throws GeneralException {
        return storageRootPath();
    }

    private static File makeNewDirectory(File parent) {
        File latestDir = null;
        boolean newDir = false;
        while (!newDir) {
            latestDir = new File(parent, "" + System.currentTimeMillis());
            if (!latestDir.exists()) {
                if (!latestDir.mkdir()) {
                    Debug.logError("Directory: " + latestDir.getName() + ", couldn't be created", MODULE);
                }
                newDir = true;
            }
        }
        return latestDir;
    }

    private static double maxFiles() {
        double configured = UtilProperties.getPropertyNumber("content", MAX_FILES_PROPERTY);
        return configured < 1 ? DEFAULT_MAX_FILES : configured;
    }

    private static String storageRootPath() throws GeneralException {
        String home = System.getProperty("ofbiz.home");
        if (UtilValidate.isEmpty(home)) {
            throw new GeneralException("The filesystem content store cannot be resolved: ofbiz.home is not set");
        }
        String prefix = UtilProperties.getPropertyValue("content", UPLOAD_PREFIX_PROPERTY, DEFAULT_UPLOAD_PREFIX);
        return Paths.get(home, prefix).toAbsolutePath().normalize().toString();
    }

    /**
     * Gives a staged object the permissions a stored object has, where the filesystem supports them.
     *
     * @param staged the staging file, before it is moved onto its destination
     */
    private static void applyStoredObjectPermissions(Path staged) {
        PosixFileAttributeView posix = Files.getFileAttributeView(staged, PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (posix == null) {
            return;
        }
        try {
            posix.setPermissions(STORED_OBJECT_PERMISSIONS);
        } catch (IOException unsupported) {
            Debug.logWarning(unsupported, "The permissions of a stored content object could not be set; it"
                    + " keeps the ones it was created with", MODULE);
        }
    }

    /**
     * Resolves a storage key to the one path inside this provider's root that it names.
     *
     * @param key the storage key
     * @param creating whether the storage root should be created when it is absent
     * @return the absolute path, guaranteed to be inside the real storage root
     * @throws GeneralException if the key breaks the key grammar or would escape the root
     * @throws IOException if the root or the key's existing ancestors cannot be inspected
     */
    private Path resolve(String key, boolean creating) throws GeneralException, IOException {
        ContentStoreFactory.requireUsableKey(key);
        if (creating) {
            Files.createDirectories(root);
        }
        Path realRoot = Files.exists(root, LinkOption.NOFOLLOW_LINKS) ? root.toRealPath() : root;
        Path resolved = realRoot.resolve(key).normalize();
        if (!resolved.startsWith(realRoot) || resolved.equals(realRoot)) {
            throw new GeneralException("Unusable content store key [" + key + "]: it resolves outside the store");
        }
        // The lexical check above cannot see a symbolic link, so the deepest ancestor that exists is
        // resolved to its real path and required to stay inside the real root. A link anywhere between
        // the root and the key - including the key itself - that points elsewhere is refused here.
        //
        // The walk stops AT the root and never climbs above it. Above the root there is nothing this
        // provider owns, so an ancestor found there is not a link inside the store and says nothing about
        // this key. Climbing past the root also used to be reachable in an ordinary situation rather than
        // a hostile one: on an instance whose storage root has not been created yet - a fresh deployment
        // that has stored nothing - the loop ran out of existing directories inside the root, settled on
        // an ancestor ABOVE it, and every read then failed with a symbolic-link error for a store that was
        // merely empty. A read of an object that is not held must answer absence, which is what the
        // callers below do once this returns.
        Path existing = resolved;
        while (existing != null && !existing.equals(realRoot)
                && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing != null && !existing.equals(realRoot) && !existing.toRealPath().startsWith(realRoot)) {
            throw new GeneralException("Unusable content store key [" + key + "]: a symbolic link on that path"
                    + " leads outside the store");
        }
        if (Files.isSymbolicLink(resolved)) {
            throw new GeneralException("Unusable content store key [" + key + "]: it names a symbolic link, and a"
                    + " stored object is a regular file");
        }
        return resolved;
    }

    private static byte[] readBounded(InputStream content, String key) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        ByteArrayOutputStream held = new ByteArrayOutputStream();
        for (int read = content.read(buffer); read >= 0; read = content.read(buffer)) {
            if (held.size() + read > MAX_OBJECT_BYTES) {
                throw new IOException("Content of [" + key + "] holds more than the " + MAX_OBJECT_BYTES
                        + " bytes that may be read into memory; stream it instead");
            }
            held.write(buffer, 0, read);
        }
        return held.toByteArray();
    }

    private static FileNotFoundException absence(String key, IOException cause) {
        FileNotFoundException absent = new FileNotFoundException("The content store holds no [" + key + "]");
        absent.initCause(cause);
        return absent;
    }
}
