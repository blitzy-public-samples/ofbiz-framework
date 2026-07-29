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
import java.nio.file.Path;
import java.util.Comparator;
import java.util.TreeMap;

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
 * the {@code content} resource. It encapsulates, without altering in any way, the file handling that
 * the Content component has always performed underneath its service layer: content is rooted at the
 * {@code content.upload.path.prefix} location (committed value {@code runtime/uploads}, resolved
 * relative to the {@code ofbiz.home} system property) and is spread over timestamp-named
 * sub-directories that hold at most {@code content.upload.max.files} entries each (committed value
 * {@code 250}, which is also the effective default whenever that property is absent or unparsable).
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
 * <p><strong>Absolute versus relative upload paths.</strong> {@link #getUploadPath(boolean)} and
 * {@link #getUploadPath(Delegator, boolean)} deliberately keep their two return forms distinct,
 * because the distinction is already persisted in {@code DataResource.objectInfo} values in every
 * existing database and is therefore frozen:
 * <ul>
 *   <li>{@code LOCAL_FILE} and {@code LOCAL_FILE_BIN} content is addressed by an <em>absolute</em>
 *       path, so those callers ask for {@code absolute = true}</li>
 *   <li>{@code OFBIZ_FILE} and {@code OFBIZ_FILE_BIN} content is addressed by a path
 *       <em>relative</em> to the OFBiz home directory, so those callers ask for
 *       {@code absolute = false}</li>
 *   <li>{@code CONTEXT_FILE} and {@code CONTEXT_FILE_BIN} content bypasses the upload path
 *       altogether, being addressed underneath a caller-supplied context root</li>
 * </ul>
 * The two forms are never collapsed into one canonical form.
 *
 * <p><strong>Key resolution and security.</strong> A storage key handed to one of the
 * {@link ContentStore} operations is a path in exactly the shape held by
 * {@code DataResource.objectInfo}. A key that already begins with the OFBiz home directory is taken
 * as the absolute form and is validated against the {@code content.data.local.file.allowed.paths}
 * allow list; every other key is taken as the relative form, is prefixed with the OFBiz home
 * directory, and is validated against the {@code content.data.ofbiz.file.allowed.paths} allow list.
 * Both forms are then confined to the OFBiz home directory by a canonical-plus-normalized boundary
 * check, which blocks {@code ../} traversal while still tolerating mount points such as a network
 * volume mounted on {@code runtime/}. That confinement is deliberately the OFBiz home directory
 * rather than the narrower upload prefix, because {@code OFBIZ_FILE} object information legitimately
 * addresses content elsewhere under the home directory and narrowing it would start rejecting keys
 * that resolve today.
 *
 * <p>Instances carry no per-request state, so one instance is safely shared by every request thread.
 */
public final class FileSystemContentStore implements ContentStore {

    private static final String MODULE = FileSystemContentStore.class.getName();

    /** The resource name of {@code content.properties}, which is the bare name without extension. */
    private static final String PROPERTY_RESOURCE = "content";

    /** The property naming the top level upload location, resolved relative to {@code ofbiz.home}. */
    private static final String UPLOAD_PATH_PREFIX_PROPERTY = "content.upload.path.prefix";

    /** The property capping the number of entries placed in a single upload sub-directory. */
    private static final String UPLOAD_MAX_FILES_PROPERTY = "content.upload.max.files";

    /** The number of entries per upload sub-directory used when the property yields nothing usable. */
    private static final double DEFAULT_MAX_FILES = 250;

    /**
     * Creates a filesystem content store.
     *
     * <p>The instance is stateless and reads every setting it needs from the {@code content}
     * resource at the moment it is used, so it never has to be rebuilt when configuration is
     * reloaded.
     */
    public FileSystemContentStore() { }

    /**
     * Stores the supplied content at the location addressed by the supplied key, creating the file
     * when it is absent and replacing its content in full when it is already there.
     *
     * <p>Any missing intermediate directory is created on demand, so a caller never has to prepare
     * the store first.
     *
     * @param key the storage key to write to, in the shape held by {@code DataResource.objectInfo};
     *     must be neither null nor empty
     * @param data the complete content to store; must not be null, but may be a zero-length array
     *     in order to store empty content
     * @throws GeneralException if the key is null or empty, if the content is null, or if the key is
     *     rejected by the allow list or by the boundary check
     * @throws IOException if the file cannot be written
     */
    @Override
    public void put(String key, byte[] data) throws GeneralException, IOException {
        if (data == null) {
            throw new GeneralException("Cannot store content under key [" + key + "]: the content is null");
        }
        File file = resolveFile(key);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            boolean created = parent.mkdirs();
            if (!created) {
                Debug.logWarning("Unable to create content storage directory [" + parent.getPath() + "].", MODULE);
            }
        }
        Files.write(file.toPath(), data);
    }

    /**
     * Reads the whole content addressed by the supplied key into memory.
     *
     * <p>Prefer {@link #openStream(String)} for large content.
     *
     * @param key the storage key to read, in the shape held by {@code DataResource.objectInfo}; must
     *     be neither null nor empty
     * @return the complete stored content, never null; a zero-length array means the stored content
     *     is empty
     * @throws GeneralException if the key is null or empty, or is rejected by the allow list or by
     *     the boundary check
     * @throws IOException if the content cannot be read, in particular a
     *     {@link FileNotFoundException} when the key addresses no file, so that null is never
     *     returned
     */
    @Override
    public byte[] get(String key) throws GeneralException, IOException {
        File file = resolveFile(key);
        if (!file.exists()) {
            throw new FileNotFoundException("No file found: " + key);
        }
        return Files.readAllBytes(file.toPath());
    }

    /**
     * Opens a fresh stream over the content addressed by the supplied key, positioned at the first
     * byte.
     *
     * <p>Every invocation returns an independent stream. <strong>The caller owns the returned stream
     * and must close it</strong>, ideally with a try-with-resources statement, because it holds an
     * open file handle.
     *
     * @param key the storage key to read, in the shape held by {@code DataResource.objectInfo}; must
     *     be neither null nor empty
     * @return a newly opened stream over the stored content, never null, positioned at the start and
     *     owned by the caller
     * @throws GeneralException if the key is null or empty, or is rejected by the allow list or by
     *     the boundary check
     * @throws IOException if the stream cannot be opened, in particular a
     *     {@link FileNotFoundException} when the key addresses no file, so that null is never
     *     returned
     */
    @Override
    public InputStream openStream(String key) throws GeneralException, IOException {
        File file = resolveFile(key);
        if (!file.exists()) {
            throw new FileNotFoundException("No file found: " + key);
        }
        // deliberately not closed here: the ContentStore contract hands ownership of the stream to
        // the caller, which is expected to close it with a try-with-resources statement
        return Files.newInputStream(file.toPath());
    }

    /**
     * Reports whether the supplied key currently addresses stored content.
     *
     * <p>This is the non-exceptional way to probe the store: it answers {@code false} for a
     * well-formed key that simply addresses no file, and reserves its exceptions for keys that are
     * unusable.
     *
     * @param key the storage key to probe, in the shape held by {@code DataResource.objectInfo};
     *     must be neither null nor empty
     * @return {@code true} if and only if the key addresses an existing file
     * @throws GeneralException if the key is null or empty, or is rejected by the allow list or by
     *     the boundary check
     * @throws IOException if the filesystem cannot be interrogated
     */
    @Override
    public boolean exists(String key) throws GeneralException, IOException {
        return resolveFile(key).exists();
    }

    /**
     * Removes the content addressed by the supplied key.
     *
     * <p>The operation is idempotent: removing a key that addresses no file is a successful no-op
     * rather than an error, so repeated or replayed clean-up is safe.
     *
     * @param key the storage key to remove, in the shape held by {@code DataResource.objectInfo};
     *     must be neither null nor empty
     * @throws GeneralException if the key is null or empty, or is rejected by the allow list or by
     *     the boundary check
     * @throws IOException if the file cannot be removed
     */
    @Override
    public void delete(String key) throws GeneralException, IOException {
        File file = resolveFile(key);
        boolean deleted = Files.deleteIfExists(file.toPath());
        if (!deleted) {
            Debug.logInfo("Nothing to remove for content storage key [" + key + "]; the removal is a no-op", MODULE);
        }
    }

    /**
     * Returns the directory that the next uploaded file should be placed in, taking the top level
     * location from {@code content.upload.path.prefix} and the per-directory cap from
     * {@code content.upload.max.files}.
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
     * <p>The per-directory cap is deliberately still read from {@code content.properties} alone,
     * without consulting the delegator. That asymmetry is pre-existing and is reproduced here on
     * purpose, so that selecting this provider cannot change where content lands.
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
     * created. The top level location itself is created when it is not there yet.
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

        // descending comparator
        Comparator<Object> desc = (o1, o2) -> {
            if ((Long) o1 > (Long) o2) {
                return -1;
            } else if ((Long) o1 < (Long) o2) {
                return 1;
            }
            return 0;
        };

        // check for the latest subdirectory
        String parentDir = ofbizHome + initialPath;
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
            // if the parent doesn't exist; create it now
            boolean created = parent.mkdir();
            if (!created) {
                Debug.logWarning("Unable to create top level upload directory [" + parentDir + "].", MODULE);
            }
        }

        // first item in map is the most current directory
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
        String name = "";
        if (latestDir != null) {
            name = latestDir.getName();
        }

        Debug.logInfo("Directory Name : " + name, MODULE);
        // the guard above and the dereference below treat latestDir inconsistently; that is
        // pre-existing behaviour and is reproduced here on purpose, because tightening it would
        // change what callers observe
        if (absolute) {
            return latestDir.getAbsolutePath().replace('\\', '/');
        }
        return initialPath + "/" + name;
    }

    /**
     * Creates a fresh timestamp-named sub-directory underneath the given top level location.
     *
     * @param parent the top level location to create the sub-directory in
     * @return the sub-directory that was created
     */
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

    /**
     * Resolves a storage key to the file that holds its content, applying the very same allow list
     * checks that the Content component applies to file-backed data resources today.
     *
     * <p>A key that already begins with the OFBiz home directory is the absolute form persisted for
     * {@code LOCAL_FILE} and {@code LOCAL_FILE_BIN} content and is checked against the local file
     * allow list. Every other key is the relative form persisted for {@code OFBIZ_FILE} and
     * {@code OFBIZ_FILE_BIN} content: it is joined to the OFBiz home directory with a separator that
     * is supplied only when neither side already provides one, and is checked against the OFBiz file
     * allow list. Both forms are then confined by {@link #checkFileBoundary(File, String)}.
     *
     * @param key the storage key to resolve, in the shape held by {@code DataResource.objectInfo}
     * @return the file that holds the content addressed by the key, which need not exist yet
     * @throws GeneralException if the key is null or empty, if it falls outside the configured allow
     *     list, or if it resolves outside the OFBiz home directory
     */
    private static File resolveFile(String key) throws GeneralException {
        if (UtilValidate.isEmpty(key)) {
            throw new GeneralException("Cannot resolve content storage key: the key is null or empty");
        }
        String prefix = System.getProperty("ofbiz.home");
        // FileUtil.getFile is used throughout rather than new File(String) so that a "component://"
        // key keeps resolving exactly as it does today; it answers null only for a malformed
        // "component://" location, which the pre-existing resolution likewise passes straight on
        File file = FileUtil.getFile(key);
        if (file.getPath().startsWith(FileUtil.getFile(prefix).getPath())) {
            // the absolute form, as persisted for LOCAL_FILE and LOCAL_FILE_BIN content
            SecurityUtil.checkLocalFileAllowList(file);
        } else {
            // the relative form, as persisted for OFBIZ_FILE and OFBIZ_FILE_BIN content
            String sep = "";
            if (key.indexOf('/') != 0 && prefix.lastIndexOf('/') != (prefix.length() - 1)) {
                sep = "/";
            }
            file = FileUtil.getFile(prefix + sep + key);
            SecurityUtil.checkOfbizFileAllowList(file);
        }
        checkFileBoundary(file, prefix);
        return file;
    }

    /**
     * Checks that the given file is within the given root directory.
     * Uses a dual-check strategy to support EFS/Docker mount points:
     * 1. Canonical paths (resolves symlinks on both sides) - works for non-mounted paths.
     * 2. Normalized absolute paths (collapses ".." without following symlinks) - fallback for
     *    when the root or a subdirectory inside it is a mount point, causing canonical paths
     *    to diverge. Path traversal via ".." is still blocked by the normalization step.
     *
     * @param file the file to check
     * @param root the directory the file has to resolve inside of
     * @throws GeneralException if the file resolves outside the root, or if either path cannot be
     *     resolved
     */
    private static void checkFileBoundary(File file, String root) throws GeneralException {
        try {
            String canonicalAllowed = FileUtil.getFile(root).getCanonicalPath();
            String canonicalFilePath = file.getCanonicalPath();
            boolean passesCanonical = canonicalFilePath.startsWith(canonicalAllowed + File.separator)
                    || canonicalFilePath.equals(canonicalAllowed);

            Path normalizedAllowed = Path.of(root).toAbsolutePath().normalize();
            Path normalizedFilePath = file.toPath().toAbsolutePath().normalize();
            boolean passesNormalized = normalizedFilePath.startsWith(normalizedAllowed);

            if (!passesCanonical && !passesNormalized) {
                throw new GeneralException("Access to file denied: path resolves outside of the allowed directory");
            }
        } catch (IOException e) {
            throw new GeneralException("Unable to validate file path: " + e.getMessage());
        }
    }
}
