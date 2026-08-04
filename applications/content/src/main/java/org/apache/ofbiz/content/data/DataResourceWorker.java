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
package org.apache.ofbiz.content.data;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.Security;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.transaction.Status;
import javax.transaction.Synchronization;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import org.apache.commons.fileupload2.core.DiskFileItem;
import org.apache.commons.fileupload2.core.DiskFileItemFactory;
import org.apache.commons.fileupload2.core.FileItem;
import org.apache.commons.fileupload2.core.FileUploadException;
import org.apache.commons.fileupload2.jakarta.JakartaServletFileUpload;

import org.apache.commons.io.IOUtils;
import org.apache.ofbiz.base.location.FlexibleLocation;
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.FileUtil;
import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.StringUtil;
import org.apache.ofbiz.base.util.UtilCodec;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilGenerics;
import org.apache.ofbiz.base.util.UtilHttp;
import org.apache.ofbiz.base.util.UtilIO;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilURL;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.base.util.UtilXml;
import org.apache.ofbiz.base.util.collections.MapStack;
import org.apache.ofbiz.base.util.template.FreeMarkerWorker;
import org.apache.ofbiz.base.util.template.XslTransform;
import org.apache.ofbiz.common.email.NotificationServices;
import org.apache.ofbiz.content.content.UploadContentAndImage;
import org.apache.ofbiz.content.data.store.ContentStore;
import org.apache.ofbiz.content.data.store.ContentStoreFactory;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.model.ModelReader;
import org.apache.ofbiz.entity.transaction.GenericTransactionException;
import org.apache.ofbiz.entity.transaction.TransactionUtil;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.entity.util.EntityUtil;
import org.apache.ofbiz.entity.util.EntityUtilProperties;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.security.SecurityUtil;
import org.apache.ofbiz.service.ServiceUtil;
import org.apache.ofbiz.widget.model.FormFactory;
import org.apache.ofbiz.widget.model.ModelForm;
import org.apache.ofbiz.widget.model.ModelScreen;
import org.apache.ofbiz.widget.model.ModelTheme;
import org.apache.ofbiz.widget.model.ScreenFactory;
import org.apache.ofbiz.widget.model.ThemeFactory;
import org.apache.ofbiz.widget.renderer.FormRenderer;
import org.apache.ofbiz.widget.renderer.ScreenRenderer;
import org.apache.ofbiz.widget.renderer.ScreenStringRenderer;
import org.apache.ofbiz.widget.renderer.VisualTheme;
import org.apache.ofbiz.widget.renderer.macro.MacroFormRenderer;
import org.apache.ofbiz.widget.renderer.macro.MacroScreenRenderer;
import org.apache.tika.Tika;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import freemarker.template.TemplateException;

/**
 * DataResourceWorker Class
 */
public class DataResourceWorker implements org.apache.ofbiz.widget.content.DataResourceWorkerInterface {

    private static final String MODULE = DataResourceWorker.class.getName();

    /** The lowest HTTP status that is a redirect, which the {@code URL_RESOURCE} path refuses. */
    private static final int HTTP_REDIRECT_LOWEST = 300;

    /** The first HTTP status above the redirect range. */
    private static final int HTTP_REDIRECT_ABOVE = 400;
    private static final String ERR_RESOURCE = "ContentErrorUiLabels";
    private static final String PROPERTY_RESOURCE = "content";

    /** The name every staging entry a reconstructed local copy is written through begins with. */
    private static final String LOCAL_COPY_STAGING_PREFIX = ".ofbiz-content-cache-";

    /** How much of a reconstructed copy is held in memory at a time, which is a buffer and not a bound. */
    private static final int LOCAL_COPY_BUFFER = 8192;

    /** The mode a reconstructed content copy is created with: readable and writable by its owner alone. */
    private static final Set<PosixFilePermission> LOCAL_COPY_FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

    /** The mode a content directory this instance has to create is given, for the same reason. */
    private static final Set<PosixFilePermission> LOCAL_COPY_DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");

    /**
     * Whether this filesystem applies POSIX modes at all, decided once. A mode requested on a filesystem
     * that has none - a Windows volume - raises {@link UnsupportedOperationException} from the create call
     * itself, so it is asked here rather than caught there.
     */
    private static final boolean POSIX_SUPPORTED =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    /** Where a committing transaction's captured bytes are held, relative to {@code ofbiz.home}. */
    private static final String PUBLICATION_STAGING_DIRECTORY = "runtime/tmp/content-publish";

    /** The name every captured copy begins with, so the staging area's contents are self-describing. */
    private static final String PUBLICATION_STAGING_PREFIX = "publish-";

    /** How many times a generated staging name is tried before the capture is treated as impossible. */
    private static final int PUBLICATION_STAGING_ATTEMPTS = 32;

    /** Whether the absence of {@link SecureDirectoryStream} has already been reported. */
    private static final AtomicBoolean SECURE_DIRECTORY_STREAM_REPORTED = new AtomicBoolean();

    /**
     * The storage keys whose local-copy answer has already been reported, so that a resource waiting to be
     * copied into the configured content store is named once rather than on every read of it. Keyed by the
     * storage key, because that is what an operator migrating content acts on.
     */
    private static final Set<String> REPORTED_FALLBACK_KEYS = ConcurrentHashMap.newKeySet();

    /**
     * The greatest number of keys {@link #REPORTED_FALLBACK_KEYS} holds. Past it, a local-copy answer is only
     * recorded verbosely: the report exists to tell an operator that content has yet to be migrated, and a
     * thousand distinct resources have already told them that, so the set must not grow with the catalogue.
     */
    private static final int REPORTED_FALLBACK_KEY_LIMIT = 1024;

    /**
     * The targets a resolution outside an active transaction has already been reported for, so that a location
     * resolved on every read is reported once.
     */
    private static final Set<String> REPORTED_UNBOUND_TARGETS = ConcurrentHashMap.newKeySet();

    /**
     * The greatest number of targets {@link #REPORTED_UNBOUND_TARGETS} holds, which is what keeps a deployment
     * with a great many of them from filling its log.
     */
    private static final int REPORTED_TARGET_LIMIT = 1024;

    /**
     * The targets a write-through is already registered for in the transaction running on this thread.
     *
     * <p>Per thread because a transaction is per thread, and cleared entry by entry as each registration
     * completes. It exists so that two resolutions of one location inside one transaction are one publish: the
     * state at commit decides what is published, so the second registration would only repeat the first.
     */
    private static final ThreadLocal<Set<String>> PENDING_WRITE_THROUGHS = ThreadLocal.withInitial(HashSet::new);

    /**
     * Traverses the DataCategory parent/child structure and put it in categoryNode. Returns non-null error string if there is an error.
     * @param depth The place on the categoryTypesIds to start collecting.
     * @param getAll Indicates that all descendants are to be gotten. Used as "true" to populate an
     *     indented select list.
     */
    public static String getDataCategoryMap(Delegator delegator, int depth, Map<String, Object> categoryNode, List<String> categoryTypeIds,
                                            boolean getAll) throws GenericEntityException {
        String errorMsg = null;
        String parentCategoryId = (String) categoryNode.get("id");
        String currentDataCategoryId = null;
        int sz = categoryTypeIds.size();

        // The categoryTypeIds has the most senior types at the end, so it is necessary to
        // work backwards. As "depth" is incremented, that is the effect.
        // The convention for the topmost type is "ROOT".
        if (depth >= 0 && (sz - depth) > 0) {
            currentDataCategoryId = categoryTypeIds.get(sz - depth - 1);
        }

        // Find all the categoryTypes that are children of the categoryNode.
        List<GenericValue> categoryValues = EntityQuery.use(delegator).from("DataCategory")
                .where("parentCategoryId", parentCategoryId)
                .cache().queryList();
        categoryNode.put("count", categoryValues.size());
        List<Map<String, Object>> subCategoryIds = new LinkedList<>();
        for (GenericValue category : categoryValues) {
            String id = (String) category.get("dataCategoryId");
            String categoryName = (String) category.get("categoryName");
            Map<String, Object> newNode = new HashMap<>();
            newNode.put("id", id);
            newNode.put("name", categoryName);
            errorMsg = getDataCategoryMap(delegator, depth + 1, newNode, categoryTypeIds, getAll);
            if (errorMsg != null) {
                break;
            }
            subCategoryIds.add(newNode);
        }

        // The first two parentCategoryId test just make sure that the first level of children
        // is gotten. This is a hack to make them available for display, but a more correct
        // approach should be formulated.
        // The "getAll" switch makes sure all descendants make it into the tree, if true.
        // The other test is to only get all the children if the "leaf" node where all the
        // children of the leaf are wanted for expansion.
        if (parentCategoryId == null
                || "ROOT".equals(parentCategoryId)
                || (currentDataCategoryId != null && currentDataCategoryId.equals(parentCategoryId))
                || getAll) {
            categoryNode.put("kids", subCategoryIds);
        }
        return errorMsg;
    }

    /**
     * Finds the parents of DataCategory entity and puts them in a list, the start entity at the top.
     */
    public static void getDataCategoryAncestry(Delegator delegator, String dataCategoryId, List<String> categoryTypeIds)
            throws GenericEntityException {
        categoryTypeIds.add(dataCategoryId);
        GenericValue dataCategoryValue = EntityQuery.use(delegator).from("DataCategory").where("dataCategoryId", dataCategoryId).queryOne();
        if (dataCategoryValue == null) {
            return;
        }
        String parentCategoryId = (String) dataCategoryValue.get("parentCategoryId");
        if (parentCategoryId != null) {
            getDataCategoryAncestry(delegator, parentCategoryId, categoryTypeIds);
        }
    }

    /**
     * Takes a DataCategory structure and builds a list of maps, one value (id) is the dataCategoryId value and the other
     * is an indented string suitable for use in a drop-down pick list.
     */
    public static void buildList(Map<String, Object> nd, List<Map<String, Object>> lst, int depth) {
        String id = (String) nd.get("id");
        String nm = (String) nd.get("name");
        StringBuilder spcBuilder = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            spcBuilder.append("&nbsp;&nbsp;");
        }
        Map<String, Object> map = new HashMap<>();
        spcBuilder.append(nm);
        map.put("dataCategoryId", id);
        map.put("categoryName", spcBuilder.toString());
        if (id != null && !"ROOT".equals(id) && !"".equals(id)) {
            lst.add(map);
        }
        List<Map<String, Object>> kids = UtilGenerics.cast(nd.get("kids"));
        for (Map<String, Object> kidNode : kids) {
            buildList(kidNode, lst, depth + 1);
        }
    }

    /**
     * Uploads image data from a form and stores it in ImageDataResource. Expects key data in a field identified by the
     * "idField" value and the binary data to be in a field id's by uploadField.
     */
    public static String uploadAndStoreImage(HttpServletRequest request, String idField, String uploadField) {

        JakartaServletFileUpload<DiskFileItem, DiskFileItemFactory> upload = UtilHttp.getServletFileUpload(request);
        List<FileItem<DiskFileItem>> lst = null;
        Locale locale = UtilHttp.getLocale(request);

        try {
            lst = UtilGenerics.cast(upload.parseRequest(request));
        } catch (FileUploadException e) {
            request.setAttribute("_ERROR_MESSAGE_", e.toString());
            return "error";
        }

        if (lst.isEmpty()) {
            String errMsg = UtilProperties.getMessage(ERR_RESOURCE, "dataResourceWorker.no_files_uploaded", locale);
            request.setAttribute("_ERROR_MESSAGE_", errMsg);
            Debug.logWarning("[DataEvents.uploadImage] No files uploaded", MODULE);
            return "error";
        }

        // This code finds the idField and the upload FileItems
        FileItem<DiskFileItem> fi = null;
        FileItem<DiskFileItem> imageFi = null;
        String imageFileName = null;
        Map<String, Object> passedParams = new HashMap<>();
        HttpSession session = request.getSession();
        GenericValue userLogin = (GenericValue) session.getAttribute("userLogin");
        passedParams.put("userLogin", userLogin);
        byte[] imageBytes = null;
        for (FileItem<DiskFileItem> fileItem : lst) {
            fi = fileItem;
            String fieldName = fi.getFieldName();
            if (fi.isFormField()) {
                String fieldStr = fi.getString();
                passedParams.put(fieldName, fieldStr);
            } else if (fieldName.startsWith("imageData")) {
                imageFi = fi;
                imageBytes = imageFi.get();
                passedParams.put(fieldName, imageBytes);
                imageFileName = imageFi.getName();
                passedParams.put("drObjectInfo", imageFileName);
                if (Debug.infoOn()) {
                    Debug.logInfo("[UploadContentAndImage]imageData: " + imageBytes.length, MODULE);
                }
            }
        }

        if (imageBytes != null && imageBytes.length > 0) {
            String mimeType = getMimeTypeFromImageFileName(imageFileName);
            if (UtilValidate.isNotEmpty(mimeType)) {
                passedParams.put("drMimeTypeId", mimeType);
                try {
                    String returnMsg = UploadContentAndImage.processContentUpload(passedParams, "", request);
                    if ("error".equals(returnMsg)) {
                        return "error";
                    }
                } catch (GenericServiceException e) {
                    request.setAttribute("_ERROR_MESSAGE_", e.getMessage());
                    return "error";
                }
            } else {
                request.setAttribute("_ERROR_MESSAGE_", "mimeType is empty.");
                return "error";
            }
        }
        return "success";
    }

    public static String getMimeTypeFromImageFileName(String imageFileName) {
        String mimeType = null;
        if (UtilValidate.isEmpty(imageFileName)) {
            return mimeType;
        }

        int pos = imageFileName.lastIndexOf('.');
        if (pos < 0) {
            return mimeType;
        }

        String suffix = imageFileName.substring(pos + 1);
        String suffixLC = suffix.toLowerCase(Locale.getDefault());
        if ("jpg".equals(suffixLC)) {
            mimeType = "image/jpeg";
        } else {
            mimeType = "image/" + suffixLC;
        }

        return mimeType;
    }

    /**
     * callDataResourcePermissionCheck Formats data for a call to the checkContentPermission service.
     */
    public static String callDataResourcePermissionCheck(Delegator delegator, LocalDispatcher dispatcher, Map<String, Object> context) {
        Map<String, Object> permResults = callDataResourcePermissionCheckResult(delegator, dispatcher, context);
        String permissionStatus = (String) permResults.get("permissionStatus");
        return permissionStatus;
    }

    /**
     * callDataResourcePermissionCheck Formats data for a call to the checkContentPermission service.
     */
    public static Map<String, Object> callDataResourcePermissionCheckResult(Delegator delegator, LocalDispatcher dispatcher,
                                                                            Map<String, Object> context) {

        Map<String, Object> permResults = new HashMap<>();
        String skipPermissionCheck = (String) context.get("skipPermissionCheck");
        if (Debug.infoOn()) {
            Debug.logInfo("in callDataResourcePermissionCheckResult, skipPermissionCheck:" + skipPermissionCheck, "");
        }

        if (UtilValidate.isEmpty(skipPermissionCheck)
                || (!"true".equalsIgnoreCase(skipPermissionCheck) && !"granted".equalsIgnoreCase(skipPermissionCheck))) {
            GenericValue userLogin = (GenericValue) context.get("userLogin");
            Map<String, Object> serviceInMap = new HashMap<>();
            serviceInMap.put("userLogin", userLogin);
            serviceInMap.put("targetOperationList", context.get("targetOperationList"));
            serviceInMap.put("contentPurposeList", context.get("contentPurposeList"));
            serviceInMap.put("entityOperation", context.get("entityOperation"));

            // It is possible that permission to work with DataResources will be controlled
            // by an external Content entity.
            String ownerContentId = (String) context.get("ownerContentId");
            if (UtilValidate.isNotEmpty(ownerContentId)) {
                try {
                    GenericValue content = EntityQuery.use(delegator).from("Content").where("contentId", ownerContentId).queryOne();
                    if (content != null) {
                        serviceInMap.put("currentContent", content);
                    }
                } catch (GenericEntityException e) {
                    Debug.logError(e, "e.getMessage()", "ContentServices");
                }
            }
            try {
                permResults = dispatcher.runSync("checkContentPermission", serviceInMap);
                if (ServiceUtil.isError(permResults)) {
                    return permResults;
                }
            } catch (GenericServiceException e) {
                Debug.logError(e, "Problem checking permissions", "ContentServices");
            }
        } else {
            permResults.put("permissionStatus", "granted");
        }
        return permResults;
    }

    /**
     * Gets image data from ImageDataResource and returns it as a byte array.
     */
    public static byte[] acquireImage(Delegator delegator, String dataResourceId) throws GenericEntityException {

        byte[] b = null;
        GenericValue dataResource = EntityQuery.use(delegator).from("DataResource").where("dataResourceId", dataResourceId).cache().queryOne();
        if (dataResource == null) {
            return b;
        }

        b = acquireImage(delegator, dataResource);
        return b;
    }

    public static byte[] acquireImage(Delegator delegator, GenericValue dataResource) throws GenericEntityException {
        byte[] b = null;
        String dataResourceId = dataResource.getString("dataResourceId");
        GenericValue imageDataResource = EntityQuery.use(delegator).from("ImageDataResource").where("dataResourceId", dataResourceId).queryOne();
        if (imageDataResource != null) {
            b = imageDataResource.getBytes("imageData");
        }
        return b;
    }

    /**
     * Gets the MIME-Type from a given data resource, using the default value set in properties as fallback.
     * @param dataResource
     * @return MIME-Type
     */
    public static String getMimeType(GenericValue dataResource) {
        String defaultMimeType = EntityUtilProperties.getPropertyValue(PROPERTY_RESOURCE, "defaultMimeType", "application/octet-stream",
                dataResource.getDelegator());
        return getMimeType(dataResource, defaultMimeType);
    }

    /**
     * Gets the MIME-Type from a given data resource.
     * @param dataResource
     * @param defaultMimeTypeId
     * @return MIME-Type
     */
    public static String getMimeType(GenericValue dataResource, String defaultMimeTypeId) {
        String mimeTypeId = null;
        if (dataResource != null) {
            mimeTypeId = (String) dataResource.get("mimeTypeId");
            if (UtilValidate.isEmpty(mimeTypeId)) {
                String fileName = (String) dataResource.get("objectInfo");
                mimeTypeId = getMimeType(dataResource.getDelegator(), fileName, defaultMimeTypeId);
            }
        }
        return mimeTypeId;
    }

    /**
     * Gets the MIME-Type from a given filename.
     * @param delegator
     * @param fileName
     * @param defaultMimeTypeId
     * @return MIME-Type
     */
    public static String getMimeType(Delegator delegator, String fileName, String defaultMimeTypeId) {
        String mimeTypeId = null;

        if (UtilValidate.isNotEmpty(fileName) && fileName.indexOf('.') > -1) {
            String fileExtension = fileName.substring(fileName.lastIndexOf('.') + 1);
            if (UtilValidate.isNotEmpty(fileExtension)) {
                GenericValue ext = null;
                try {
                    ext = delegator.findOne("FileExtension", true, "fileExtensionId", fileExtension);
                    if (ext != null) {
                        mimeTypeId = ext.getString("mimeTypeId");
                    }
                } catch (GenericEntityException e) {
                    Debug.logError(e, MODULE);
                }
            }
        }
        // check one last time, if we have to return a default mime type
        if (UtilValidate.isEmpty(mimeTypeId) && UtilValidate.isNotEmpty(defaultMimeTypeId)) {
            mimeTypeId = defaultMimeTypeId;
        }
        return mimeTypeId;
    }

    public static String getMimeTypeWithByteBuffer(java.nio.ByteBuffer buffer) throws IOException {
        byte[] b = buffer.array();
        Tika tika = new Tika();
        return tika.detect(b);
    }

    public static String buildRequestPrefix(Delegator delegator, Locale locale, String webSiteId, String https) {
        Map<String, Object> prefixValues = new HashMap<>();
        String prefix;

        NotificationServices.setBaseUrl(delegator, webSiteId, prefixValues);
        if (https != null && "true".equalsIgnoreCase(https)) {
            prefix = (String) prefixValues.get("baseSecureUrl");
        } else {
            prefix = (String) prefixValues.get("baseUrl");
        }
        if (UtilValidate.isEmpty(prefix)) {
            if (https != null && "true".equalsIgnoreCase(https)) {
                prefix = UtilProperties.getMessage("content", "baseSecureUrl", locale);
            } else {
                prefix = UtilProperties.getMessage("content", "baseUrl", locale);
            }
        }

        return prefix;
    }

    /**
     * Checks that the given file is within the provided context root directory.
     *
     * <p><strong>Canonical containment, and nothing weaker.</strong> Both sides are resolved with
     * {@link File#getCanonicalPath()}, which follows every symbolic link, and the file's canonical path must
     * lie inside the root's canonical path. There is no second, lexical test: a purely textual check collapses
     * {@code ..} without following links, so a symbolic link placed anywhere under the allowed root and
     * pointing outside it passes that test while resolving somewhere the deployment never authorised. Accepting
     * either result means the weaker of the two decides, which is the same as not having the stronger one at
     * all (CWE-59, CWE-22).
     *
     * <p>Resolving the root canonically is what makes this correct for a deployment whose context root, or a
     * directory inside it, is a symbolic link or a mount point: the root is resolved the same way the file is,
     * so the two are compared in the same namespace and a legitimate mounted path is contained exactly as it
     * should be. That is the case the earlier lexical fallback existed for, and resolving both sides covers it
     * without the traversal hole.
     *
     * @param file the location to check, which need not exist yet
     * @param contextRoot the directory the location must be inside
     * @throws GeneralException if the location resolves outside the root, the root is not usable, or either
     *     side cannot be resolved
     */
    static void checkContextFileBoundary(File file, String contextRoot) throws GeneralException {
        if (UtilValidate.isEmpty(contextRoot)) {
            throw new GeneralException("Access to file denied: no allowed directory was supplied to check it"
                    + " against");
        }
        try {
            // Resolved on the filesystem rather than compared as text, so a symbolic link anywhere in either
            // path is followed before the comparison and cannot be used to leave the root.
            Path canonicalRoot = new File(contextRoot).getCanonicalFile().toPath();
            Path canonicalFile = file.getCanonicalFile().toPath();
            if (!canonicalFile.startsWith(canonicalRoot)) {
                throw new GeneralException("Access to file denied: path resolves outside of the allowed directory");
            }
        } catch (IOException e) {
            // The reason is logged rather than returned: a resolution failure's message carries filesystem
            // paths, and a caller-visible message is not the place for the deployment's layout (CWE-209).
            Debug.logError(e, "A file-backed location could not be resolved for containment checking", MODULE);
            throw new GeneralException("Unable to validate file path");
        }
    }

    /**
     * Refuses a {@code URL_RESOURCE} fetch, keeping every detail of WHY server-side.
     *
     * <p>Each refusal below is decided from something the caller must not be told: the host the resource
     * names, the address that host resolved to - which, when the refusal is "this is private", names an
     * address inside the deployment's own network - or the protocol it asked for. These refusals travel
     * back through the content-rendering path, where they can reach a rendered page, so a caller learns
     * only that the content could not be retrieved and an opaque reference to look up (CWE-209). The
     * reason, with the host and the address in it, goes to the log beside the same reference.
     *
     * <p>The message of the returned exception carries no cause either, deliberately: {@code
     * GeneralException.getMessage()} appends the message of any cause it is given, and the causes here -
     * {@code UnknownHostException} for one - report the host they failed on.
     *
     * <p>Returned rather than thrown so that every call site reads {@code throw refuseUrlResource(...)},
     * which keeps the control flow visible at the site and lets the compiler see the method ends there.
     *
     * @param detail the whole reason, for the log; may name hosts, addresses and settings
     * @return the exception to throw
     */
    private static GeneralException refuseUrlResource(String detail) {
        String reference = UUID.randomUUID().toString();
        Debug.logError("URL_RESOURCE refusal [" + reference + "]: " + detail, MODULE);
        return new GeneralException("The requested URL_RESOURCE content could not be retrieved. Reference ["
                + reference + "].");
    }

    /**
     * Validates a URL for the URL_RESOURCE data type against SSRF (Server-Side Request Forgery)
     * attacks. Enforces:
     * <ul>
     *   <li>Protocol restricted to http/https only</li>
     *   <li>Host must match the configured allow-list when
     *       {@code content.data.url.resource.allowed.hosts} (security.properties) is non-empty;
     *       both exact and subdomain matches are supported</li>
     *   <li>All resolved IP addresses must not be private, loopback, link-local, multicast,
     *       or otherwise reserved (mitigates DNS-rebinding)</li>
     * </ul>
     */
    private static InetAddress[] checkUrlResourceAllowed(URL url) throws GeneralException {
        // 1. Protocol: only http and https are permitted
        String protocol = url.getProtocol();
        if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
            throw refuseUrlResource("only http and https are supported; the resource names [" + protocol + "]");
        }
        String host = url.getHost();
        if (UtilValidate.isEmpty(host)) {
            throw refuseUrlResource("the resource names no host");
        }

        // 2. Allow-list: if configured, the host must match one of the entries
        String allowedHostsStr = UtilProperties.getPropertyValue("security",
                "content.data.url.resource.allowed.hosts", "");
        if (UtilValidate.isNotEmpty(allowedHostsStr)) {
            String lcHost = host.toLowerCase(Locale.ROOT);
            boolean hostAllowed = false;
            for (String entry : allowedHostsStr.split(",")) {
                String allowedEntry = entry.trim().toLowerCase(Locale.ROOT);
                if (UtilValidate.isEmpty(allowedEntry)) {
                    continue;
                }
                if (lcHost.equals(allowedEntry) || lcHost.endsWith("." + allowedEntry)) {
                    hostAllowed = true;
                    break;
                }
            }
            if (!hostAllowed) {
                throw refuseUrlResource("host [" + host + "] is not in content.data.url.resource.allowed.hosts");
            }
        }

        // 3. DNS resolution: block private/reserved IP ranges (SSRF / DNS-rebinding mitigation)
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw refuseUrlResource("host [" + host + "] cannot be resolved");
        }
        if (addresses == null || addresses.length == 0) {
            throw refuseUrlResource("host [" + host + "] resolved to no addresses");
        }
        for (InetAddress addr : addresses) {
            checkNotPrivateOrReservedAddress(addr);
        }
        // Returned so that the peer the request is actually made to can be checked against the peer that was
        // authorised here; see requireValidatedPeer.
        return addresses;
    }

    /**
     * How the address a {@code URL_RESOURCE} fetch may reach is decided, which is not the same question
     * for the two places a fetch is made from.
     *
     * <p>{@link #EXTERNAL} is a target named by a {@code DataResource} row: the host is data, so it is put
     * through the whole allow-list and address policy, and no private or reserved address may be reached.
     * {@link #SELF} is this deployment's own configured base URL with a relative resource appended: the host
     * comes from {@code url.properties} and the site configuration rather than from a row, and it is
     * routinely a private or loopback address - a container in a private subnet reaching itself. Applying the
     * external policy there would refuse every real deployment, so the address policy is not applied and what
     * IS enforced instead is that the authority of the URL fetched is the authority the deployment
     * configured; see {@link #openSelfOriginResource}.
     */
    private enum FetchOrigin {
        /** A target named by row data. */
        EXTERNAL,
        /** This deployment's own configured origin. */
        SELF
    }

    /**
     * Requires that the host still resolves to exactly the addresses that were authorised.
     *
     * <p>Validation and connection are two separate name resolutions, and a name whose answer changes between
     * them - a DNS rebinding attack, or simply a very short TTL - could be authorised as a public address and
     * then connected to a private one (CWE-350, CWE-918). This is called once the connection is established and
     * before a single byte of the response is consumed, so an answer that has changed is refused and the
     * connection is dropped rather than read.
     *
     * <p><strong>This is the weaker of the two checks, and for {@code https} it is no longer the only one.</strong>
     * Comparing resolutions before and after says nothing about the address the connection was actually made
     * to; {@link PinnedPeerSocketFactory} inspects THAT, at the socket layer, for every {@code https} fetch. For
     * cleartext {@code http} the JDK offers no socket seam on {@code HttpURLConnection}, so what binds the
     * connect to the validated answer there is the JVM's positive DNS cache - which
     * {@link #requireDnsCacheBindsTheValidation} now REQUIRES rather than assumes - plus this comparison.
     *
     * <p>The connection itself is made through the unchanged URL, deliberately: rewriting it to the authorised
     * IP literal would send that literal as the {@code Host} header - {@code HttpURLConnection} treats
     * {@code Host} as a restricted header and ignores an attempt to set it - which breaks name-based virtual
     * hosting and, over TLS, certificate verification. A deployment that wants the question closed entirely
     * configures {@code content.data.url.resource.allowed.hosts}.
     *
     * @param url the resource URL being fetched
     * @param validated the addresses that were authorised before the connection was made
     * @param origin whether the address policy applies to the answer, which it does not for this deployment's
     *     own origin
     * @throws GeneralException if the host no longer resolves to the authorised addresses, or resolves to an
     *     address that may not be reached
     */
    private static void requireValidatedPeer(URL url, InetAddress[] validated, FetchOrigin origin)
            throws GeneralException {
        InetAddress[] current;
        try {
            current = InetAddress.getAllByName(url.getHost());
        } catch (UnknownHostException e) {
            throw refuseUrlResource("host [" + url.getHost() + "] cannot be resolved");
        }
        if (current == null || current.length == 0) {
            throw refuseUrlResource("host [" + url.getHost() + "] resolved to no addresses");
        }
        Set<String> authorised = new HashSet<>();
        for (InetAddress addr : validated) {
            authorised.add(addr.getHostAddress());
        }
        for (InetAddress addr : current) {
            if (origin == FetchOrigin.EXTERNAL) {
                checkNotPrivateOrReservedAddress(addr);
            }
            if (!authorised.contains(addr.getHostAddress())) {
                throw refuseUrlResource("host [" + url.getHost() + "] resolved to [" + addr.getHostAddress()
                        + "], which is not one of the addresses authorised before the request was made, so the"
                        + " response is refused rather than read");
            }
        }
    }

    /**
     * Requires that the JVM will serve the connect that follows a validation from the answer that was
     * validated, for the one scheme where nothing else can bind the two.
     *
     * <p><strong>The gap this closes.</strong> For cleartext {@code http} the JDK exposes no socket factory on
     * {@code HttpURLConnection}, so the address the connection is made to cannot be inspected or chosen from
     * here: the client resolves the name itself. Everything that made the earlier before-and-after comparison
     * meaningful therefore rested on the JVM's positive DNS cache serving that second resolution from the
     * first one's answer - an assumption, written in a comment, that a deployment could switch off without
     * knowing it had. With {@code networkaddress.cache.ttl=0} the comparison compares two independent lookups
     * and a name that alternates between a public and a private answer passes it while the connection goes to
     * the private one (CWE-350, CWE-918).
     *
     * <p>So the assumption is now a checked precondition: caching disabled means the fetch is refused, with the
     * two ways to make it possible again named. It is checked per fetch rather than once per JVM because a
     * security property can be set at any time by any code in the JVM, so a value read at class initialisation
     * would say nothing about the value in force now.
     *
     * <p>Only cleartext {@code http} reaches this. An {@code https} fetch is bound by
     * {@link PinnedPeerSocketFactory}, which inspects the peer the connection was really made to, and by the
     * certificate check, which a service on a rebound address cannot satisfy for the requested name.
     *
     * @param url the resource URL being fetched, for the report
     * @throws GeneralException if the JVM caches no positive DNS answer
     */
    private static void requireDnsCacheBindsTheValidation(URL url) throws GeneralException {
        // The security property is what the JDK reads first; the system property is the legacy form it falls
        // back to. Absent means the JDK default, which caches positive answers, so absence is not a failure.
        String configured = Security.getProperty("networkaddress.cache.ttl");
        if (UtilValidate.isEmpty(configured)) {
            configured = System.getProperty("sun.net.inetaddr.ttl");
        }
        if (UtilValidate.isEmpty(configured)) {
            return;
        }
        long ttl;
        try {
            ttl = Long.parseLong(configured.trim());
        } catch (NumberFormatException unusable) {
            // Unparseable means the JDK ignores it and applies its own default, which caches.
            return;
        }
        if (ttl != 0) {
            return;
        }
        throw refuseUrlResource("host [" + url.getHost() + "] is named over cleartext http while this JVM caches"
                + " no positive DNS answer (networkaddress.cache.ttl=0), so the address this validation"
                + " authorised is not the address the connection would be made to and a rebinding answer would"
                + " not be seen. Name the resource over https, where the connected peer is inspected at the"
                + " socket layer, or allow a positive DNS cache, or restrict"
                + " content.data.url.resource.allowed.hosts to hosts this deployment trusts.");
    }

    /**
     * Opens the response a {@code URL_RESOURCE} names, authorised, peer-pinned, size-capped and owning its
     * own connection.
     *
     * <p>Extracted so that both places a {@code URL_RESOURCE} is fetched - the text render and the stream seam
     * - go through one implementation. Two copies of an authority path drift, and the two copies this replaced
     * had already drifted in how they cleaned up after a failure.
     *
     * <p>The stream that comes back throws when the size cap is reached rather than reporting the end of the
     * content, and closing it disconnects the HTTP connection, so a caller cannot leak a socket by closing what
     * it was handed. Every failure before the stream is handed over disconnects too.
     *
     * @param url the absolute resource URL, with a host
     * @return the {@code stream} and {@code length} pair; the length is what the server reported, which may be
     *     {@code -1} when it reported nothing
     * @throws GeneralException if the URL is not allowed, the peer is not the authorised one, the response is a
     *     redirect, or the reported length exceeds the configured maximum
     * @throws IOException if the connection cannot be made or the response cannot be opened
     */
    private static Map<String, Object> openUrlResource(URL url) throws GeneralException, IOException {
        return openFetchedResource(url, FetchOrigin.EXTERNAL);
    }

    /**
     * Opens a resource of THIS deployment's own origin through the same hardened path as an external one.
     *
     * <p><strong>The gap this closes.</strong> A {@code URL_RESOURCE} whose {@code objectInfo} is relative is
     * resolved against the deployment's own base URL and used to be fetched with {@code URL.getContent()}: no
     * timeouts, so a hung origin held the rendering thread indefinitely; redirects followed automatically, so a
     * {@code Location} header could take the fetch to a host nothing authorised; no ceiling on the response, so
     * the whole of it was materialised in the heap; and no release of the connection (CWE-918, CWE-400). It is
     * the same fetch as the absolute case in every respect but which host it goes to, so it goes through the
     * same opener.
     *
     * <p><strong>What is checked here instead of the address policy.</strong> The host is not row data - it is
     * built by {@code buildRequestPrefix} from {@code url.properties} and the {@code WebSite} configuration -
     * and it is routinely private or loopback, so refusing private addresses would refuse every real
     * deployment. What the row DOES contribute is the path, so what is enforced is that appending the path did
     * not move the fetch off the configured origin: scheme, host and port must be exactly the prefix's own. A
     * relative value that reaches a different authority is refused rather than fetched, which is the case an
     * appended {@code //host/} or an embedded credential would otherwise produce.
     *
     * @param resource the URL built from the deployment's own prefix and the resource's relative path
     * @param origin the prefix the resource was built from, whose authority the fetch may not leave
     * @return the {@code stream} and {@code length} pair, exactly as {@link #openUrlResource} returns it
     * @throws GeneralException if the resource left the configured origin, or the fetch is refused
     * @throws IOException if the connection cannot be made or the response cannot be opened
     */
    private static Map<String, Object> openSelfOriginResource(URL resource, URL origin)
            throws GeneralException, IOException {
        if (!sameOrigin(resource, origin)) {
            throw refuseUrlResource("a relative resource resolved to [" + resource.getProtocol() + "://"
                    + resource.getAuthority() + "], which is not this deployment's configured origin ["
                    + origin.getProtocol() + "://" + origin.getAuthority() + "], so it is refused rather than"
                    + " fetched");
        }
        return openFetchedResource(resource, FetchOrigin.SELF);
    }

    /**
     * Reports whether two URLs name the same scheme, host and port.
     *
     * <p>The port is compared as the EFFECTIVE port, so {@code https://host} and {@code https://host:443} are
     * one origin: {@link URL#getPort()} answers -1 for a URL that named no port, and comparing that with 443
     * would refuse a legitimate fetch. The host comparison is case-insensitive because a host name is, and the
     * scheme comparison is too.
     *
     * @param one the first URL
     * @param other the second URL
     * @return whether both name the same origin
     */
    private static boolean sameOrigin(URL one, URL other) {
        return one.getProtocol().equalsIgnoreCase(other.getProtocol())
                && one.getHost().equalsIgnoreCase(other.getHost())
                && effectivePort(one) == effectivePort(other);
    }

    /**
     * The port a URL names, or the default port of its scheme when it named none.
     *
     * @param url the URL
     * @return the effective port
     */
    private static int effectivePort(URL url) {
        return url.getPort() == -1 ? url.getDefaultPort() : url.getPort();
    }

    /**
     * The one fetch implementation both callers share.
     *
     * @param url the absolute resource URL, with a host
     * @param origin which address policy applies to it
     * @return the {@code stream} and {@code length} pair
     * @throws GeneralException if the URL is not allowed, the peer is not the authorised one, the response is a
     *     redirect, or the reported length exceeds the configured maximum
     * @throws IOException if the connection cannot be made or the response cannot be opened
     */
    private static Map<String, Object> openFetchedResource(URL url, FetchOrigin origin)
            throws GeneralException, IOException {
        InetAddress[] validated = origin == FetchOrigin.EXTERNAL
                ? checkUrlResourceAllowed(url)
                : resolveOwnOrigin(url);
        int connectTimeout = (int) UtilProperties.getPropertyNumber("security",
                "content.data.url.resource.connect.timeout", 10000.0);
        int readTimeout = (int) UtilProperties.getPropertyNumber("security",
                "content.data.url.resource.read.timeout", 30000.0);
        long maxResponseSize = (long) UtilProperties.getPropertyNumber("security",
                "content.data.url.resource.max.response.size", (double) (10L * 1024 * 1024));

        URLConnection con = url.openConnection();
        boolean handedOver = false;
        try {
            con.setConnectTimeout(connectTimeout);
            con.setReadTimeout(readTimeout);
            // Automatic redirect following is disabled and a redirect is rejected outright: a Location header
            // names a target none of the checks above authorised, and re-validating an arbitrary one safely is
            // not something this path can do.
            if (con instanceof HttpURLConnection) {
                HttpURLConnection http = (HttpURLConnection) con;
                http.setInstanceFollowRedirects(false);
            }
            if (con instanceof HttpsURLConnection) {
                // The peer the connection is really made to is inspected at the socket layer, which is the one
                // place it can be seen. Installed per connection, so nothing about this fetch changes the
                // defaults any other code in this JVM uses.
                HttpsURLConnection https = (HttpsURLConnection) con;
                https.setSSLSocketFactory(new PinnedPeerSocketFactory(https.getSSLSocketFactory(), validated,
                        connectTimeout, origin));
            } else if (origin == FetchOrigin.EXTERNAL) {
                // Cleartext http to a host named by row data: there is no socket seam, so the property that
                // makes the before-and-after comparison mean anything is required explicitly.
                requireDnsCacheBindsTheValidation(url);
            }
            con.connect();
            requireValidatedPeer(url, validated, origin);
            if (con instanceof HttpURLConnection) {
                int responseCode = ((HttpURLConnection) con).getResponseCode();
                if (responseCode >= HTTP_REDIRECT_LOWEST && responseCode < HTTP_REDIRECT_ABOVE) {
                    throw refuseUrlResource("the response was a redirect (" + responseCode + "), and a"
                            + " Location header names a target none of the checks above authorised");
                }
            }
            long contentLength = con.getContentLengthLong();
            if (contentLength > maxResponseSize) {
                throw refuseUrlResource("the response reported " + contentLength + " bytes, over the"
                        + " content.data.url.resource.max.response.size ceiling of " + maxResponseSize);
            }
            Map<String, Object> opened = UtilMisc.toMap("stream",
                    new UrlResourceStream(con.getInputStream(), con, maxResponseSize), "length", contentLength);
            handedOver = true;
            return opened;
        } finally {
            if (!handedOver) {
                disconnect(con);
            }
        }
    }

    /**
     * Resolves the addresses of this deployment's OWN origin, without the external address policy.
     *
     * <p>The policy is deliberately absent - see {@link FetchOrigin} - but the resolution is not: the answer is
     * what the peer inspection and the before-and-after comparison are made against, so a self-origin fetch is
     * pinned to its own configured host exactly as an external one is pinned to a public one.
     *
     * @param url the URL built from this deployment's configured prefix
     * @return the addresses its host resolves to, never empty
     * @throws GeneralException if the host cannot be resolved
     */
    private static InetAddress[] resolveOwnOrigin(URL url) throws GeneralException {
        String host = url.getHost();
        if (UtilValidate.isEmpty(host)) {
            throw refuseUrlResource("this deployment's configured content prefix names no host, so a relative"
                    + " resource cannot be fetched from it");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw refuseUrlResource("this deployment's own host [" + host + "] cannot be resolved");
        }
        if (addresses == null || addresses.length == 0) {
            throw refuseUrlResource("this deployment's own host [" + host + "] resolved to no addresses");
        }
        return addresses;
    }

    /**
     * A TLS socket factory that will not let a fetch reach an address the validation did not authorise.
     *
     * <p><strong>The defect this closes.</strong> Authorising a name by resolving it and then connecting
     * through the name resolves it a SECOND time, inside the HTTP client, and nothing compared the address that
     * second resolution produced with the one that was authorised. A name that answers with a public address
     * when it is checked and a private one when it is connected to - DNS rebinding, or a short TTL and an
     * attacker-controlled zone - therefore reached the private address, and the only thing standing in the way
     * was a comparison of two lookups that were both made from this JVM (CWE-918, CWE-350).
     *
     * <p><strong>Where the peer becomes visible.</strong> {@code HttpsURLConnection} connects the plain socket
     * itself and hands it to this factory for the TLS layer, so the layered overload sees an ALREADY CONNECTED
     * socket and can read the address off it. Nothing has been sent at that point and no handshake has begun,
     * so refusing there means the request is never made and the response is never read. When the client instead
     * asks this factory to create the socket - the overload it falls back to - the address is not read but
     * CHOSEN: the socket is connected to an authorised address, and the host name is kept for SNI and for
     * certificate verification, which is what makes pinning compatible with name-based virtual hosting.
     *
     * <p><strong>The name is never replaced.</strong> Every overload receives the original host name and passes
     * it to the delegate, so the {@code Host} header, the SNI extension and the certificate check all continue
     * to name the host the resource asked for. Verification is not weakened anywhere: no trust manager and no
     * hostname verifier is replaced, and a certificate that does not name the requested host is refused by the
     * JDK exactly as before.
     *
     * <p>For an {@link FetchOrigin#EXTERNAL} fetch the connected peer is put through the whole address policy
     * as well as the authorised set, so an address that is private for a reason the set never saw is refused
     * too. For {@link FetchOrigin#SELF} only the set applies, because this deployment's own origin is
     * legitimately private.
     */
    private static final class PinnedPeerSocketFactory extends SSLSocketFactory {

        private final SSLSocketFactory delegate;
        private final InetAddress[] authorised;
        private final Set<String> authorisedAddresses;
        private final int connectTimeout;
        private final FetchOrigin origin;

        /**
         * @param delegate the factory that does the TLS work, which is the connection's own
         * @param authorised the addresses the validation authorised, in the order it produced them
         * @param connectTimeout how long a socket this factory connects itself may take, in milliseconds
         * @param origin which address policy applies to the peer
         */
        private PinnedPeerSocketFactory(SSLSocketFactory delegate, InetAddress[] authorised, int connectTimeout,
                FetchOrigin origin) {
            this.delegate = delegate;
            this.authorised = authorised.clone();
            this.authorisedAddresses = new HashSet<>();
            for (InetAddress address : authorised) {
                this.authorisedAddresses.add(address.getHostAddress());
            }
            this.connectTimeout = connectTimeout;
            this.origin = origin;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket connected, String host, int port, boolean autoClose)
                throws IOException {
            // The overload HttpsURLConnection uses: the socket is already connected, so this is the address the
            // request would really go to.
            requireAuthorised(connected.getInetAddress());
            return delegate.createSocket(connected, host, port, autoClose);
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return connectToAuthorised(host, port, null, 0);
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
                throws IOException {
            return connectToAuthorised(host, port, localHost, localPort);
        }

        @Override
        public Socket createSocket(InetAddress address, int port) throws IOException {
            requireAuthorised(address);
            return delegate.createSocket(address, port);
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
                throws IOException {
            requireAuthorised(address);
            return delegate.createSocket(address, port, localAddress, localPort);
        }

        /**
         * Connects a socket to an authorised address and layers TLS for the ORIGINAL name on top of it.
         *
         * <p>Each authorised address is tried in turn, so a host with several addresses behaves as it does
         * without pinning; only addresses outside the authorised set are unreachable. The name is what the TLS
         * layer is told, so SNI and certificate verification are unchanged.
         *
         * @param host the host name the resource named
         * @param port the port to connect to
         * @param localAddress the local address to bind, or null for any
         * @param localPort the local port to bind
         * @return the connected TLS socket
         * @throws IOException if no authorised address could be reached
         */
        private Socket connectToAuthorised(String host, int port, InetAddress localAddress, int localPort)
                throws IOException {
            IOException last = null;
            for (InetAddress address : authorised) {
                Socket plain = new Socket();
                try {
                    if (localAddress != null) {
                        plain.bind(new InetSocketAddress(localAddress, localPort));
                    }
                    plain.connect(new InetSocketAddress(address, port), connectTimeout);
                } catch (IOException unreachable) {
                    closeQuietly(plain);
                    last = unreachable;
                    continue;
                }
                try {
                    return layer(plain, host, port);
                } catch (IOException handshake) {
                    closeQuietly(plain);
                    throw handshake;
                }
            }
            throw last == null ? new IOException("URL_RESOURCE fetch reached no authorised address") : last;
        }

        /**
         * Layers TLS for one name over a connected socket, naming the host in SNI and requiring the
         * certificate to identify it.
         *
         * @param plain the connected socket
         * @param host the host name the resource named
         * @param port the port
         * @return the TLS socket, which owns the plain one
         * @throws IOException if the TLS layer cannot be created
         */
        private Socket layer(Socket plain, String host, int port) throws IOException {
            SSLSocket secured = (SSLSocket) delegate.createSocket(plain, host, port, true);
            SSLParameters parameters = secured.getSSLParameters();
            // Stated rather than left to the caller: this socket was connected by address, and endpoint
            // identification is what makes the certificate be checked against the NAME regardless.
            parameters.setServerNames(Collections.singletonList(new SNIHostName(host)));
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            secured.setSSLParameters(parameters);
            return secured;
        }

        /**
         * Refuses a peer the validation did not authorise, or that the address policy does not allow.
         *
         * @param address the address the connection was made to, or is about to be made to
         * @throws IOException if it may not be reached
         */
        private void requireAuthorised(InetAddress address) throws IOException {
            if (address == null) {
                throw new IOException("URL_RESOURCE fetch has no peer address to check");
            }
            if (origin == FetchOrigin.EXTERNAL) {
                try {
                    checkNotPrivateOrReservedAddress(address);
                } catch (GeneralException refused) {
                    // The reason is already in the log with its own reference; the outward message carries
                    // neither the address nor the reason.
                    throw new IOException("URL_RESOURCE fetch was refused before any request was sent");
                }
            }
            if (!authorisedAddresses.contains(address.getHostAddress())) {
                throw new IOException(refuseUrlResource("the connection was made to ["
                        + address.getHostAddress() + "], which is not one of the addresses the validation"
                        + " authorised, so no request is sent and no response is read").getMessage());
            }
        }

        /**
         * Closes a socket without letting the close fail the fetch.
         *
         * @param socket the socket to close
         */
        private static void closeQuietly(Socket socket) {
            try {
                socket.close();
            } catch (IOException ignored) {
                Debug.logVerbose("A pinned URL_RESOURCE socket could not be closed cleanly", MODULE);
            }
        }
    }

    /**
     * Releases an HTTP connection without letting the release itself fail an operation.
     *
     * @param con the connection to release; anything that is not an HTTP connection is left alone, because
     *     there is nothing to release
     */
    private static void disconnect(URLConnection con) {
        if (con instanceof HttpURLConnection) {
            HttpURLConnection http = (HttpURLConnection) con;
            try {
                http.disconnect();
            } catch (RuntimeException failure) {
                Debug.logWarning("A URL_RESOURCE connection could not be released cleanly: "
                        + failure.getClass().getName(), MODULE);
            }
        }
    }

    /**
     * A {@code URL_RESOURCE} response body that refuses to exceed its cap and releases its connection.
     *
     * <p><strong>Why not a plain bounded stream.</strong> A bounded stream reports the end of the content when
     * it reaches its limit, which is indistinguishable from the content having ended: an oversized response is
     * then silently truncated and stored or rendered as if it were complete. Content that is too large to
     * accept has to be refused, so this throws (CWE-393).
     *
     * <p><strong>Why closing has to disconnect.</strong> Closing the response body returns the socket to the
     * keep-alive pool but does not release the connection when the body was not read to its end, which is
     * exactly what happens when a caller stops early or a cap is hit. Closing this stream disconnects, so a
     * caller that does the one thing every consumer of a stream does releases everything (CWE-772).
     */
    private static final class UrlResourceStream extends FilterInputStream {

        private final URLConnection connection;
        private final long limit;
        private long read;
        private boolean closed;

        UrlResourceStream(InputStream body, URLConnection connection, long limit) {
            super(body);
            this.connection = connection;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int next = super.read();
            if (next != -1) {
                count(1);
            }
            return next;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int taken = super.read(buffer, offset, length);
            if (taken > 0) {
                count(taken);
            }
            return taken;
        }

        /**
         * Accounts for bytes delivered and refuses to deliver more than the cap.
         *
         * @param delivered how many bytes were just delivered
         * @throws IOException if the response has now exceeded the configured maximum
         */
        private void count(long delivered) throws IOException {
            read += delivered;
            if (read > limit) {
                throw new IOException("URL_RESOURCE response exceeds the configured maximum of " + limit
                        + " bytes and has been refused rather than truncated");
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                super.close();
            } finally {
                disconnect(connection);
            }
        }
    }

    /**
     * Throws {@link GeneralException} if {@code addr} belongs to a private, loopback,
     * link-local, multicast, or otherwise reserved IP range (IPv4 and IPv6).
     */
    private static void checkNotPrivateOrReservedAddress(InetAddress addr) throws GeneralException {
        if (addr.isLoopbackAddress()) {
            throw refuseUrlResource("the target resolves to a loopback address: " + addr.getHostAddress());
        }
        if (addr.isLinkLocalAddress()) {
            throw refuseUrlResource("the target resolves to a link-local address: " + addr.getHostAddress());
        }
        if (addr.isSiteLocalAddress()) {
            throw refuseUrlResource("the target resolves to a private (site-local) address: " + addr.getHostAddress());
        }
        if (addr.isAnyLocalAddress()) {
            throw refuseUrlResource("the target resolves to a wildcard address: " + addr.getHostAddress());
        }
        if (addr.isMulticastAddress()) {
            throw refuseUrlResource("the target resolves to a multicast address: " + addr.getHostAddress());
        }
        byte[] b = addr.getAddress();
        if (addr instanceof Inet4Address) {
            int i0 = b[0] & 0xFF;
            int i1 = b[1] & 0xFF;
            // 0.0.0.0/8 – "this" network (RFC 1122)
            if (i0 == 0) {
                throw refuseUrlResource("the target resolves to a reserved network address (0.0.0.0/8): " + addr.getHostAddress());
            }
            // 100.64.0.0/10 – shared address space / CGNAT (RFC 6598)
            if (i0 == 100 && i1 >= 64 && i1 <= 127) {
                throw refuseUrlResource("the target resolves to a shared address space (CGNAT, 100.64.0.0/10): " + addr.getHostAddress());
            }
            // 192.0.0.0/24 – IETF protocol assignments (RFC 6890)
            if (i0 == 192 && i1 == 0 && (b[2] & 0xFF) == 0) {
                throw refuseUrlResource("the target resolves to an IETF reserved address (192.0.0.0/24): " + addr.getHostAddress());
            }
            // 198.18.0.0/15 – network benchmarking (RFC 2544)
            if (i0 == 198 && (i1 == 18 || i1 == 19)) {
                throw refuseUrlResource("the target resolves to a benchmarking address (198.18.0.0/15): " + addr.getHostAddress());
            }
            // 240.0.0.0/4 – reserved for future use (RFC 1112)
            if ((i0 & 0xF0) == 240) {
                throw refuseUrlResource("the target resolves to a reserved address (240.0.0.0/4): " + addr.getHostAddress());
            }
        } else if (addr instanceof Inet6Address) {
            // fc00::/7 – Unique Local Addresses (ULA), private IPv6 (RFC 4193)
            if ((b[0] & 0xFE) == 0xFC) {
                throw refuseUrlResource("the target resolves to a unique-local (private) IPv6 address: " + addr.getHostAddress());
            }
            // ::ffff:0:0/96 – IPv4-mapped IPv6; re-validate the embedded IPv4 address
            boolean isIpv4Mapped = true;
            for (int i = 0; i < 10; i++) {
                if (b[i] != 0) {
                    isIpv4Mapped = false;
                    break;
                }
            }
            if (isIpv4Mapped && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
                try {
                    checkNotPrivateOrReservedAddress(
                            InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException e) {
                    throw refuseUrlResource("the target contains an invalid IPv4-mapped IPv6 address");
                }
            }
        }
    }

    public static File getContentFile(String dataResourceTypeId, String objectInfo, String contextRoot)
            throws GeneralException, FileNotFoundException {
        // The first of the resolution seams the plan names (plan sections 0.4.1 and 0.6.3), and the only one that
        // has to serve BOTH directions: the registered createBinaryFile and updateBinaryFile services take the
        // File this returns and open their own FileOutputStream on it, while ContentWorker and the report
        // templates dereference it to read. The frozen contract is the same either way - a non-null File that
        // exists, or a refusal - so both are served by the same two steps.
        //
        // 1. The location is READ THROUGH the store when this instance has no copy of it. Content published by
        //    another instance leaves nothing on this one's disk, so a File contract that only ever looked at
        //    local disk would have failed on every instance except the one that received the upload - which is
        //    precisely the state a fleet is in. So an absent, provider-backed location is reconstructed at its
        //    own path from the store before absence is called final: see materialiseIfStoreHolds, which is also
        //    why a WRITER cannot be handed a temporary file it would write into and lose.
        // 2. The location is WRITTEN THROUGH to the store when the transaction that resolved it commits: see
        //    registerWriteThrough. Publishing at commit rather than at write time is what keeps the store from
        //    ever holding bytes the deployment's own validation rejected, and keeps a rollback from removing
        //    content it never owned.
        //
        // Inert unless an object store is configured. In database mode there is no provider; in filesystem mode
        // the provider's tree IS this tree, so there is nothing to read through and nothing to publish. Either
        // way the single statement this method used to be is what runs.
        //
        // Resolved with no delegator, because the frozen signature carries none and the services that call it are
        // out of the plan's scope. WHICH provider is selected costs nothing: it is a deployment value read from
        // content.properties alone and never from a SystemProperty row - see
        // ContentStoreFactory.DEPLOYMENT_PROPERTIES - so this resolves the same provider a delegator would. What
        // it does mean is that the tunables this path consults, the whole-read ceiling among them, are the
        // committed ones rather than any per-delegator override; a deployment that overrides them through a
        // SystemProperty row applies that override to the read seams, which do carry a delegator. The one thing a
        // missing delegator cannot be allowed to decide is WHOSE key namespace is used, so it does not decide it:
        // see storeForSeamWithoutDelegator.
        ContentStore store = storeForSeamWithoutDelegator();
        if (!ContentStoreFactory.publicationRequired(store)) {
            return resolveContentLocation(dataResourceTypeId, objectInfo, contextRoot, ABSENCE_IS_FINAL);
        }
        File file = resolveContentLocation(dataResourceTypeId, objectInfo, contextRoot,
                absent -> !materialiseIfStoreHolds(store, dataResourceTypeId, absent));
        if (file == null) {
            return null;
        }
        String key = providerKey(store, dataResourceTypeId, file);
        if (key != null) {
            registerWriteThrough(store, file, () -> scanFile(file), null);
        }
        return file;
    }

    /**
     * Resolves the provider for the one seam whose frozen signature carries no delegator, refusing rather
     * than serving a key namespace that may not be the caller's.
     *
     * <p><strong>The exposure this closes.</strong> A storage key is the content's {@code ofbiz.home}-relative
     * path (see {@link ContentStoreFactory#storeKey}), and in a multi-tenant deployment that path is built from
     * {@code content.upload.path.prefix} read through the TENANT's delegator. Resolving the store with no
     * delegator therefore resolves the BASE deployment's tunables, so an off-instance store reached from here
     * on behalf of a tenant would read and publish under the base namespace instead of the tenant's - two
     * tenants recording the same path would name one stored object and could read, overwrite and delete each
     * other's content (CWE-668, CWE-862), below the level any row-level authorisation can see.
     * {@link ContentStoreFactory} refuses a tenant delegator that shares the base namespace, which closes the
     * seams that DO carry a delegator; this closes the one that cannot.
     *
     * <p><strong>Refused only where the ambiguity is real, so nothing else changes.</strong> The refusal needs
     * all three of: multi-tenant mode enabled (it is {@code multitenant=N} in the committed
     * {@code general.properties}, so a single-tenant deployment has exactly one namespace and is unaffected), a
     * provider in service, and that provider holding content OFF the instance. Database mode has no provider,
     * and filesystem mode's tree IS this deployment's own content tree - the same one every OFBiz release has
     * shared between tenants - so neither reaches a namespace this method could get wrong, and both keep the
     * single statement this seam used to be.
     *
     * <p>Refused rather than warned, and refused here rather than at the first object read: a warning about
     * cross-tenant content is read after the exposure, and the remedy - a per-tenant
     * {@code content.upload.path.prefix} or {@code content.store.s3.key.prefix} row, or database storage - is
     * configuration, not code. A multi-tenant deployment that wants an object store reaches it through the
     * delegator-carrying seams ({@code getDataResourceStream}, {@code renderDataResourceAsText},
     * {@code getDataResourceContentUploadPath(Delegator, boolean)}), which resolve the tenant's own namespace.
     *
     * @return the provider in service for this seam, or {@code null} for database mode
     * @throws GeneralException if a provider holding content off the instance cannot be attributed to a
     *     tenant, or if the configured provider cannot be constructed
     */
    private static ContentStore storeForSeamWithoutDelegator() throws GeneralException {
        ContentStore store = ContentStoreFactory.getContentStore(null);
        if (ContentStoreFactory.publicationRequired(store) && EntityUtil.isMultiTenantEnabled()) {
            throw new GeneralException("Content held in an off-instance store cannot be resolved through this"
                    + " seam while multitenant=Y, because the seam carries no delegator and so cannot tell which"
                    + " tenant's storage key namespace to use; serving the base deployment's namespace instead"
                    + " would let two tenants name the same stored object. Give each tenant a SystemProperty row"
                    + " for content.upload.path.prefix or content.store.s3.key.prefix and use the content"
                    + " services, which carry a delegator, or leave content.store.provider at database, where"
                    + " each tenant's content stays in its own database.");
        }
        return store;
    }

    /**
     * Reconstructs a provider-backed location this instance holds no copy of, and reports whether it now holds
     * one.
     *
     * <p><strong>Why the content's own path, and not a temporary file.</strong> The frozen contract of
     * {@link #getContentFile} is a {@code File}, and the caller may be about to WRITE to it - the binary content
     * services do exactly that. Handing back a temporary copy would send that write to a temporary file and lose
     * it silently, which is worse than the failure it replaced. Reconstructing the content at the location the
     * row names cannot lose a write: the caller overwrites the location it was always going to overwrite, the
     * write-through registered alongside publishes it, and nothing has to be cleaned up afterwards because the
     * file is the deployment's own content and not a copy of it. The local tree becomes a read-through cache of
     * the store, which is what makes an instance replaceable rather than what makes it stateful: everything on it
     * can be reconstructed by any other instance from the store.
     *
     * <p><strong>Authorised before anything is written.</strong> The branch that will report absence applies the
     * resource type's own allow list AFTER its presence check, so this cannot rely on that having happened: it
     * applies the same checks itself first, and refuses a location the allow list would refuse rather than
     * creating it. A location outside {@code ofbiz.home} has no key at all and is never reconstructed.
     *
     * <p><strong>Absence in the store is not an error here.</strong> It answers false and the caller's own branch
     * then raises the {@link FileNotFoundException} it has always raised, with the location string that branch
     * built. That is what keeps a resource that genuinely does not exist reporting exactly what it reported
     * before this seam existed.
     *
     * @param store the provider serving this operation, never null
     * @param dataResourceTypeId the {@code DataResource.dataResourceTypeId}
     * @param file the resolved location that holds nothing on this instance
     * @return {@code true} when the location now holds the store's content
     * @throws GeneralException if the location is one the resource type's allow list refuses, or the provider
     *     cannot be reached
     */
    private static boolean materialiseIfStoreHolds(ContentStore store, String dataResourceTypeId, File file)
            throws GeneralException {
        String key = providerKey(store, dataResourceTypeId, file);
        if (key == null) {
            return false;
        }
        requireAuthorisedLocation(dataResourceTypeId, file);
        try (ContentStore.ContentStream content = store.openStream(key)) {
            writeLocalCopy(file, content);
            Debug.logInfo("The content store holds " + content.length() + " bytes under "
                    + ContentStore.logReference(key) + " that this instance had no copy of, so the copy was"
                    + " reconstructed from the store", MODULE);
            return true;
        } catch (FileNotFoundException absentInStore) {
            Debug.logVerbose(absentInStore, "The content store holds nothing under "
                    + ContentStore.logReference(key) + " either, so the content does not exist", MODULE);
            return false;
        } catch (IOException e) {
            // Reported, not propagated: this runs where the caller expects either a File or the absence its own
            // branch reports, and a store that cannot be read is not the same as content that does not exist.
            // The caller's own refusal follows, and this line is what tells an operator why.
            Debug.logError(e, "The content store could not be read for " + ContentStore.logReference(key)
                    + ", so this instance's missing copy of it could not be reconstructed", MODULE);
            return false;
        }
    }

    /**
     * Applies the allow list the resource type carries, before a location is created rather than after.
     *
     * @param dataResourceTypeId the {@code DataResource.dataResourceTypeId}
     * @param file the resolved location
     * @throws GeneralException if the location is not one this resource type may name
     */
    private static void requireAuthorisedLocation(String dataResourceTypeId, File file) throws GeneralException {
        if ("LOCAL_FILE".equals(dataResourceTypeId) || "LOCAL_FILE_BIN".equals(dataResourceTypeId)) {
            SecurityUtil.checkLocalFileAllowList(file);
            return;
        }
        SecurityUtil.checkOfbizFileAllowList(file);
    }

    /**
     * Writes a local copy of content the store holds, atomically, at the location the row names.
     *
     * <p>Staged in the destination's own directory and moved onto the destination, so that a concurrent reader
     * never observes a half-written file and two instances - or two threads - reconstructing the same content at
     * once cannot interleave their bytes. Missing directories are created, because the instance that received the
     * upload created them and this one never did.
     *
     * <p><strong>Descriptor-relative, and no link is followed.</strong> Every operation below - creating the
     * staging entry, writing it and renaming it onto the destination - is performed relative to a directory this
     * method holds OPEN, so an ancestor exchanged for a symbolic link after the location was authorised cannot
     * redirect any of them: the descriptor still refers to the directory that was checked, not to whatever the
     * path now names. Path-based operations resolve the whole path afresh on every call, which is the gap this
     * closes (CWE-367, CWE-59): {@link #requireAuthorisedLocation} and the containment checks run against the
     * path, and the write that followed them resolved it a second time. Each missing level is created with an
     * explicit owner-only mode rather than whatever the process umask happens to be, and each level that already
     * exists is required to be a real directory, checked {@code NOFOLLOW_LINKS} (CWE-732).
     *
     * <p>{@link SecureDirectoryStream} is a documented optional capability. Where the filesystem does not
     * provide one there is no descriptor to work relative to, so the path-based form is used and the loss of
     * the guarantee is reported once - the same treatment, for the same reason, that
     * {@code FileSystemContentStore} gives it.
     *
     * @param file the location to reconstruct
     * @param content the store's content, positioned at its first byte
     * @throws IOException if the copy cannot be written
     */
    private static void writeLocalCopy(File file, InputStream content) throws IOException {
        Path target = file.toPath().toAbsolutePath();
        Path directory = target.getParent();
        if (directory == null) {
            throw new IOException("A content location with no directory above it cannot be reconstructed");
        }
        createOwnerOnlyDirectories(directory);
        Path name = target.getFileName();
        try (DirectoryStream<Path> opened = Files.newDirectoryStream(directory)) {
            if (opened instanceof SecureDirectoryStream) {
                @SuppressWarnings("unchecked")
                SecureDirectoryStream<Path> secure = (SecureDirectoryStream<Path>) opened;
                writeLocalCopyRelativeTo(secure, name, content);
                return;
            }
            reportMissingSecureDirectoryStream();
        }
        writeLocalCopyByPath(target, directory, content);
    }

    /**
     * Writes and publishes the local copy through an open directory descriptor.
     *
     * <p>The staging name is generated and created {@code CREATE_NEW}, so nothing can pre-exist under it and no
     * symbolic link planted at that name can be followed; the rename that publishes it is descriptor-relative
     * and replaces the destination in one step.
     *
     * @param directory the destination's own directory, held open
     * @param name the single-component name of the destination
     * @param content the store's content, positioned at its first byte
     * @throws IOException if the copy cannot be written or published
     */
    private static void writeLocalCopyRelativeTo(SecureDirectoryStream<Path> directory, Path name,
            InputStream content) throws IOException {
        Path staging = Paths.get(LOCAL_COPY_STAGING_PREFIX + Long.toHexString(ThreadLocalRandom.current().nextLong())
                + ".tmp");
        boolean published = false;
        try {
            try (SeekableByteChannel channel = directory.newByteChannel(staging,
                    EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    ownerOnlyFileAttributes())) {
                copyInto(content, channel);
            }
            directory.move(staging, directory, name);
            published = true;
        } finally {
            if (!published) {
                try {
                    directory.deleteFile(staging);
                } catch (IOException alreadyGone) {
                    Debug.logVerbose(alreadyGone, "A staged content copy could not be removed after a failed"
                            + " reconstruction", MODULE);
                }
            }
        }
    }

    /**
     * Writes and publishes the local copy by path, for a filesystem that provides no directory descriptor.
     *
     * @param target the destination
     * @param directory the destination's own directory
     * @param content the store's content, positioned at its first byte
     * @throws IOException if the copy cannot be written or published
     */
    private static void writeLocalCopyByPath(Path target, Path directory, InputStream content) throws IOException {
        Path staging = Files.createTempFile(directory, LOCAL_COPY_STAGING_PREFIX, ".tmp");
        try {
            Files.copy(content, staging, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                // Documented fallback for a filesystem that cannot rename atomically. The window it opens is the
                // one every such filesystem opens, and it is narrower than writing the destination directly.
                Debug.logVerbose(notAtomic, "This filesystem cannot move a staged content copy atomically, so the"
                        + " reconstruction is not atomic either", MODULE);
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
            }
            staging = null;
        } finally {
            if (staging != null) {
                Files.deleteIfExists(staging);
            }
        }
    }

    /**
     * Copies a store's content into an open channel.
     *
     * @param content the content, positioned at its first byte
     * @param channel the channel to write it to
     * @throws IOException if it cannot be copied
     */
    private static void copyInto(InputStream content, SeekableByteChannel channel) throws IOException {
        byte[] buffer = new byte[LOCAL_COPY_BUFFER];
        int read;
        while ((read = content.read(buffer)) >= 0) {
            ByteBuffer pending = ByteBuffer.wrap(buffer, 0, read);
            while (pending.hasRemaining()) {
                channel.write(pending);
            }
        }
    }

    /**
     * Creates every missing level of a content directory with an explicit owner-only mode, following no link.
     *
     * <p>One level at a time from the top, because that is what lets each level be judged: a level that already
     * exists is read {@code NOFOLLOW_LINKS} and must be a real directory, so a symbolic link standing in for one
     * is refused rather than descended into, and a level that does not exist is created with
     * {@code rwx------} rather than with whatever the process umask leaves. {@link Files#createDirectories}
     * offers neither - it follows links and applies the umask - which is why it is not used.
     *
     * <p>A level created concurrently by another thread or instance is not a failure: the
     * {@link FileAlreadyExistsException} is answered by re-reading the level and accepting it if it is now a
     * real directory, which is the same outcome as having found it there.
     *
     * @param directory the directory the content is filed in
     * @throws IOException if a level exists and is not a directory, or cannot be created
     */
    private static void createOwnerOnlyDirectories(Path directory) throws IOException {
        Path level = directory.getRoot();
        if (level == null) {
            throw new IOException("A content directory that is not absolute cannot be created safely");
        }
        for (Path step : directory) {
            level = level.resolve(step);
            requireDirectoryOrCreateIt(level);
        }
    }

    /**
     * Requires one path level to be a real directory, creating it owner-only when it is absent.
     *
     * @param level the level to establish
     * @throws IOException if it exists and is not a directory, or cannot be created
     */
    private static void requireDirectoryOrCreateIt(Path level) throws IOException {
        try {
            BasicFileAttributes existing = Files.readAttributes(level, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (existing.isDirectory()) {
                return;
            }
            throw new IOException("A content directory level is " + (existing.isSymbolicLink() ? "a symbolic link"
                    : "not a directory") + ", so the content below it is not reconstructed");
        } catch (NoSuchFileException absent) {
            try {
                Files.createDirectory(level, ownerOnlyDirectoryAttributes());
            } catch (FileAlreadyExistsException raced) {
                BasicFileAttributes now = Files.readAttributes(level, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (!now.isDirectory()) {
                    throw new IOException("A content directory level appeared and is not a directory, so the"
                            + " content below it is not reconstructed", raced);
                }
            }
        }
    }

    /**
     * The attributes a reconstructed content file is created with, or none where POSIX modes do not apply.
     *
     * @return the file attributes to create with
     */
    private static FileAttribute<?>[] ownerOnlyFileAttributes() {
        return POSIX_SUPPORTED
                ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(LOCAL_COPY_FILE_PERMISSIONS)}
                : new FileAttribute<?>[0];
    }

    /**
     * The attributes a created content directory is given, or none where POSIX modes do not apply.
     *
     * @return the file attributes to create with
     */
    private static FileAttribute<?>[] ownerOnlyDirectoryAttributes() {
        return POSIX_SUPPORTED
                ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(LOCAL_COPY_DIRECTORY_PERMISSIONS)}
                : new FileAttribute<?>[0];
    }

    /**
     * Reports the absence of {@link SecureDirectoryStream} once, so the loss of the guarantee is visible
     * without a line per reconstruction.
     */
    private static void reportMissingSecureDirectoryStream() {
        if (SECURE_DIRECTORY_STREAM_REPORTED.compareAndSet(false, true)) {
            Debug.logWarning("This filesystem does not provide SecureDirectoryStream, so a reconstructed content"
                    + " copy is written by path: an ancestor directory exchanged for a symbolic link between the"
                    + " containment check and the write cannot be ruled out on this filesystem", MODULE);
        }
    }

    /**
     * Derives the storage key a provider holds a resolved location's content under, or reports that the location
     * is not provider-backed at all.
     *
     * <p>Two exclusions, and they are the whole of the decision:
     *
     * <ul>
     * <li><strong>{@code CONTEXT_FILE} and {@code CONTEXT_FILE_BIN} are never provider-backed.</strong> Their
     * content is a webapp's own static files, which ship inside the container image: every instance already has
     * an identical copy, there is no durable local state to externalise, and making them provider-backed would
     * instead require every instance's static files to be uploaded before it could serve them. The render path
     * has always read them locally, so excluding them here is what makes the two directions agree - one backend
     * for writes and another for reads is how the same resource comes to be written to one place and read from
     * another.</li>
     * <li><strong>A location outside {@code ofbiz.home} is not provider-backed.</strong> An absolute
     * {@code LOCAL_FILE} elsewhere on the host is state an operator placed deliberately; no key describes it, and
     * the read seams read it from where it is.</li>
     * <li><strong>A relative location is not provider-backed.</strong> {@code LOCAL_FILE} refuses one, and it
     * refuses it AFTER it has reported absence - so a relative, absent location has always raised
     * {@link FileNotFoundException} rather than the refusal, and which of the two it raises is part of the frozen
     * behaviour. Treating a relative location as provider-backed would have inverted that whenever the process's
     * working directory happens to be {@code ofbiz.home}, which in a deployment it is.</li>
     * </ul>
     *
     * @param store the provider serving this operation, or {@code null} for database storage
     * @param dataResourceTypeId the {@code DataResource.dataResourceTypeId}
     * @param file the resolved location
     * @return the provider key, or {@code null} when this location is not provider-backed
     * @throws GeneralException if the provider refuses to derive a key from the location
     */
    private static String providerKey(ContentStore store, String dataResourceTypeId, File file)
            throws GeneralException {
        if (store == null || !file.isAbsolute() || "CONTEXT_FILE".equals(dataResourceTypeId)
                || "CONTEXT_FILE_BIN".equals(dataResourceTypeId)) {
            return null;
        }
        String relative = deploymentRelativePath(file);
        return relative == null ? null : ContentStoreFactory.storeKey(store, relative);
    }

    /**
     * What a location resolution does about content that is not on this instance's disk.
     *
     * <p>A parameter rather than a flag because the answer is no longer always the same, and because the two
     * callers that need a different answer need it for different reasons: a streamed read is about to ask the
     * provider and does not need a local file at all, while {@link #getContentFile} owes its caller a real
     * {@code File} and can reconstruct one. Asked at the exact point each branch would report absence, so every
     * branch keeps its own pre-existing message and its own pre-existing check order - which matters, because
     * {@code LOCAL_FILE} tests presence before it tests absoluteness and that decides which exception a
     * relative, absent location raises.
     */
    private interface PresencePolicy {
        /**
         * Decides whether a location holding nothing on this disk is the final answer.
         *
         * @param file the resolved location, which holds nothing
         * @return true when the caller should report absence
         * @throws GeneralException if establishing the answer refuses the location
         */
        boolean absenceIsFinal(File file) throws GeneralException;
    }

    /** Absence on this disk is the answer, which is what every caller needed before this seam existed. */
    private static final PresencePolicy ABSENCE_IS_FINAL = file -> true;

    /** Absence on this disk is not yet the answer, because the caller is about to ask the provider. */
    private static final PresencePolicy ASK_THE_PROVIDER = file -> false;

    /**
     * Resolves a file-backed {@code DataResource} location to the one local file it names, applying every
     * authorisation the resource type carries and performing no content-store request whatsoever.
     *
     * <p>This is the pre-existing body of {@link #getContentFile}, unchanged in statement order, extracted so
     * that the one read path which may be served from a provider - {@link #getDataResourceStream} - authorises a
     * location through exactly the same code rather than through a second copy of it that could drift. It issues
     * no provider request of any kind, which is what makes "authorise first, then ask the provider" true of
     * every caller at once: a location refused here costs no request, so no caller can be used as an existence
     * oracle for a location an operator has forbidden (CWE-200).
     *
     * <p>Each branch keeps its own checks in its own pre-existing order, which is deliberately not the same
     * order in every branch - {@code LOCAL_FILE} tests presence before it tests absoluteness, and reproducing
     * that matters because it decides which exception a relative, absent location raises. That is why presence
     * is checked here, inside the branch, under {@code requireLocalPresence} rather than once at the end: the
     * message each branch raises names the location string that branch built, and only the branch has it.
     *
     * @param dataResourceTypeId the {@code DataResource.dataResourceTypeId}
     * @param objectInfo the {@code DataResource.objectInfo} location
     * @param contextRoot the webapp context root, required by the {@code CONTEXT_FILE} types and otherwise
     *     ignored
     * @param presence what to do about a location holding nothing on this instance's disk. Asked at the exact
     *     point the branch would report absence, so the branch keeps its own message and its own order, and so a
     *     policy that can supply the content has the resolved location in hand when it is asked
     * @return the resolved location, or {@code null} for a type that is not file backed, exactly as
     *     {@link #getContentFile} has always returned for one
     * @throws GeneralException if the location is refused - a relative {@code LOCAL_FILE}, or a location outside
     *     an allow list or the context root, or an empty context root
     * @throws FileNotFoundException if the location holds nothing and the policy says absence is final
     */
    private static File resolveContentLocation(String dataResourceTypeId, String objectInfo, String contextRoot,
            PresencePolicy presence) throws GeneralException, FileNotFoundException {
        File file = null;

        if ("LOCAL_FILE".equals(dataResourceTypeId) || "LOCAL_FILE_BIN".equals(dataResourceTypeId)) {
            file = FileUtil.getFile(objectInfo);
            if (!file.exists() && presence.absenceIsFinal(file)) {
                throw new FileNotFoundException("No file found: " + (objectInfo));
            }
            if (!file.isAbsolute()) {
                throw new GeneralException("File (" + objectInfo + ") is not absolute");
            }
            SecurityUtil.checkLocalFileAllowList(file);
        } else if ("OFBIZ_FILE".equals(dataResourceTypeId) || "OFBIZ_FILE_BIN".equals(dataResourceTypeId)) {
            // One canonical ofbiz.home for both halves of the seam. deploymentRelativePath trims it before
            // deciding whether a location belongs to the deployment, so resolving the location from the
            // untrimmed value made the two disagree: a home with stray whitespace - which is what a shell that
            // expanded an unset variable into a quoted argument leaves - resolved the file under one path and
            // then classified it against another, so a provider-backed resource read as a local one.
            String prefix = deploymentHome();
            if (UtilValidate.isEmpty(prefix)) {
                // Refused rather than resolved relatively. The pre-existing code dereferenced this value and
                // raised a NullPointerException when it was unset; a blank root would instead silently resolve
                // the location against whatever directory the process started in, which is a different file on
                // every instance. The filesystem provider refuses a blank root for the same reason.
                throw new GeneralException("Cannot resolve the OFBIZ_FILE location [" + objectInfo + "] because"
                        + " the ofbiz.home system property is not set");
            }

            String sep = "";
            if (objectInfo.indexOf('/') != 0 && prefix.lastIndexOf('/') != (prefix.length() - 1)) {
                sep = "/";
            }
            file = FileUtil.getFile(prefix + sep + objectInfo);
            if (!file.exists() && presence.absenceIsFinal(file)) {
                throw new FileNotFoundException("No file found: " + (prefix + sep + objectInfo));
            }
            SecurityUtil.checkOfbizFileAllowList(file);
        } else if ("CONTEXT_FILE".equals(dataResourceTypeId) || "CONTEXT_FILE_BIN".equals(dataResourceTypeId)) {
            if (UtilValidate.isEmpty(contextRoot)) {
                throw new GeneralException("Cannot find CONTEXT_FILE with an empty context root!");
            }

            String sep = "";
            if (objectInfo.indexOf('/') != 0 && contextRoot.lastIndexOf('/') != (contextRoot.length() - 1)) {
                sep = "/";
            }
            file = FileUtil.getFile(contextRoot + sep + objectInfo);
            checkContextFileBoundary(file, contextRoot);
            if (!file.exists() && presence.absenceIsFinal(file)) {
                throw new FileNotFoundException("No file found: " + (contextRoot + sep + objectInfo));
            }
        }

        return file;
    }


    public static String getDataResourceMimeType(Delegator delegator, String dataResourceId, GenericValue view) throws GenericEntityException {

        String mimeType = null;
        if (view != null) {
            mimeType = view.getString("drMimeTypeId");
        }
        if (UtilValidate.isEmpty(mimeType) && UtilValidate.isNotEmpty(dataResourceId)) {
            GenericValue dataResource = EntityQuery.use(delegator).from("DataResource").where("dataResourceId", dataResourceId).cache().queryOne();
            mimeType = dataResource.getString("mimeTypeId");

        }
        return mimeType;
    }

    public static String getDataResourceContentUploadPath() {
        return getDataResourceContentUploadPath(true);
    }

    public static String getDataResourceContentUploadPath(boolean absolute) {
        String initialPath = UtilProperties.getPropertyValue("content", "content.upload.path.prefix");
        double maxFiles = UtilProperties.getPropertyNumber("content", "content.upload.max.files");
        if (maxFiles < 1) {
            maxFiles = 250;
        }

        return getDataResourceContentUploadPath(initialPath, maxFiles, absolute);
    }

    public static String getDataResourceContentUploadPath(Delegator delegator, boolean absolute) {
        String initialPath = EntityUtilProperties.getPropertyValue("content", "content.upload.path.prefix", delegator);
        double maxFiles = UtilProperties.getPropertyNumber("content", "content.upload.max.files");
        if (maxFiles < 1) {
            maxFiles = 250;
        }

        String uploadPath = getDataResourceContentUploadPath(initialPath, maxFiles, absolute);
        // The second of the resolution seams the plan names (plan sections 0.4.1 and 0.6.3), and the one that
        // catches an UPLOAD. It has to be here rather than at getContentFile, because the service that actually
        // writes an uploaded file - createFileMethod, reached through createAnonFile - resolves its own File from
        // the objectInfo it was given and never calls this class at all. The one thing every upload does pass
        // through is this method: DataServicesScript asks for the directory, composes objectInfo from it, and
        // hands the write to that service. So what is registered here is a write-through over the DIRECTORY, and
        // whatever file appears in it by the time the transaction commits is what gets published. That is what
        // makes the plan's upload story true of an object store as well - an upload is published as it is
        // written - without a business service having to know that a store exists.
        //
        // Inert unless an object store is configured, exactly as the other seam is.
        try {
            ContentStore store = ContentStoreFactory.getContentStore(delegator);
            if (ContentStoreFactory.publicationRequired(store) && UtilValidate.isNotEmpty(uploadPath)) {
                File directory = FileUtil.getFile(absolute ? uploadPath : deploymentHome() + uploadPath);
                if (deploymentRelativePath(directory) != null) {
                    registerWriteThrough(store, directory, () -> scanDirectory(directory), delegator);
                }
            }
        } catch (GeneralException e) {
            // Reported, not propagated: the frozen signature of this method declares nothing to throw, and it is
            // called while a service is composing a location rather than while content is being written. A store
            // that cannot be resolved is a deployment fault an operator has to see, and it must not turn resolving
            // an upload directory into a failure - the upload still lands on this instance, and the read seams
            // still refuse or fall back according to content.store.local.fallback.
            Debug.logError(e, "The configured content store could not be resolved while an upload directory was"
                    + " being prepared, so content written into it will not be published to the store", MODULE);
        }
        return uploadPath;
    }

    public static String getDataResourceContentUploadPath(String initialPath, double maxFiles) {
        return getDataResourceContentUploadPath(initialPath, maxFiles, true);
    }

    /**
     * Handles creating sub-directories for file storage; using a max number of files per directory
     * @param initialPath the top level location where all files should be stored
     * @param maxFiles the max number of files to place in a directory
     * @return the absolute path to the directory where the file should be placed
     */
    public static String getDataResourceContentUploadPath(String initialPath, double maxFiles, boolean absolute) {
        // The same canonical deployment root every location resolution uses. An upload path is what an
        // objectInfo value is built from, and that value is later classified against this root to decide whether
        // a provider holds the content, so the two have to be measured from one value.
        String ofbizHome = deploymentHome();

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
        if (absolute) {
            return latestDir.getAbsolutePath().replace('\\', '/');
        }
        return initialPath + "/" + name;
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

    // -------------------------------------
    // DataResource rendering methods
    // -------------------------------------

    public static void clearAssociatedRenderCache(Delegator delegator, String dataResourceId) throws GeneralException {
        if (dataResourceId == null) {
            throw new GeneralException("Cannot clear dataResource related cache for a null dataResourceId");
        }

        GenericValue dataResource = EntityQuery.use(delegator).from("DataResource").where("dataResourceId", dataResourceId).cache().queryOne();
        if (dataResource != null) {
            String dataTemplateTypeId = dataResource.getString("dataTemplateTypeId");
            if ("FTL".equals(dataTemplateTypeId)) {
                FreeMarkerWorker.clearTemplateFromCache("delegator:" + delegator.getDelegatorName() + ":DataResource:" + dataResourceId);
            }
        }
    }

    public static String renderDataResourceAsText(LocalDispatcher dispatcher, Delegator delegator, String dataResourceId,
                                                  Map<String, Object> templateContext,
                                                  Locale locale, String targetMimeTypeId, boolean cache) throws GeneralException, IOException {
        try (Writer writer = new StringWriter()) {
            renderDataResourceAsText(dispatcher, delegator, dataResourceId, writer, templateContext, locale, targetMimeTypeId, cache, null);
            return writer.toString();
        }
    }

    public static String renderDataResourceAsText(LocalDispatcher dispatcher, String dataResourceId, Appendable out,
                                                  Map<String, Object> templateContext, Locale locale, String targetMimeTypeId, boolean cache)
            throws GeneralException, IOException {
        renderDataResourceAsText(dispatcher, null, dataResourceId, out, templateContext, locale, targetMimeTypeId, cache, null);
        return out.toString();
    }

    public static void renderDataResourceAsText(LocalDispatcher dispatcher, Delegator delegator, String dataResourceId,
            Appendable out, Map<String, Object> templateContext, Locale locale, String targetMimeTypeId, boolean cache, List<GenericValue>
                                                        webAnalytics) throws GeneralException, IOException {
        if (delegator == null) {
            delegator = dispatcher.getDelegator();
        }
        if (dataResourceId == null) {
            throw new GeneralException("Cannot lookup data RESOURCE with for a null dataResourceId");
        }
        if (templateContext == null) {
            templateContext = new HashMap<>();
        }
        if (UtilValidate.isEmpty(targetMimeTypeId)) {
            targetMimeTypeId = "text/html";
        }
        if (locale == null) {
            locale = Locale.getDefault();
        }

        VisualTheme visualTheme = ThemeFactory.getVisualThemeFromId("COMMON");
        ModelTheme modelTheme = visualTheme.getModelTheme();

        // if the target mimeTypeId is not a text type, throw an exception
        if (!targetMimeTypeId.startsWith("text/")) {
            throw new GeneralException("The desired mime-type is not a text type, cannot render as text: " + targetMimeTypeId);
        }

        // get the data RESOURCE object
        GenericValue dataResource = EntityQuery.use(delegator).from("DataResource")
                .where("dataResourceId", dataResourceId)
                .cache(cache).queryOne();

        if (dataResource == null) {
            throw new GeneralException("No data RESOURCE object found for dataResourceId: [" + dataResourceId + "]");
        }

        // a data template attached to the data RESOURCE
        String dataTemplateTypeId = dataResource.getString("dataTemplateTypeId");

        // no template; or template is NONE; render the data
        if (UtilValidate.isEmpty(dataTemplateTypeId) || "NONE".equals(dataTemplateTypeId)) {
            DataResourceWorker.writeDataResourceText(dataResource, targetMimeTypeId, locale, templateContext, delegator, out, cache);
        } else {
            // a template is defined; render the template first
            templateContext.put("mimeTypeId", targetMimeTypeId);

            // FTL template
            if ("FTL".equals(dataTemplateTypeId)) {
                try {
                    // get the template data for rendering
                    String templateText = getDataResourceText(dataResource, targetMimeTypeId, locale, templateContext, delegator, cache);

                    // if use web analytics.
                    if (UtilValidate.isNotEmpty(webAnalytics)) {
                        StringBuffer newTemplateText = new StringBuffer(templateText);
                        String webAnalyticsCode = "<script type=\"text/javascript\">";
                        for (GenericValue webAnalytic : webAnalytics) {
                            StringUtil.StringWrapper wrapString = StringUtil.wrapString((String) webAnalytic.get("webAnalyticsCode"));
                            webAnalyticsCode += wrapString.toString();
                        }
                        webAnalyticsCode += "</script>";
                        newTemplateText.insert(templateText.lastIndexOf("</head>"), webAnalyticsCode);
                        templateText = newTemplateText.toString();
                    }

                    // render the FTL template
                    boolean useTemplateCache = cache && !UtilProperties.getPropertyAsBoolean("content", "disable.ftl.template.cache", false);
                    if ("ELECTRONIC_TEXT".equals(dataResource.getString("dataResourceTypeId"))
                            || "SHORT_TEXT".equalsIgnoreCase(dataResource.getString("dataResourceTypeId"))
                            || "LINK".equalsIgnoreCase(dataResource.getString("dataResourceTypeId"))) {
                        throw new GeneralException("Error rendering template: FreeMarker templates are no longer supported for "
                                + dataResource.getString("dataResourceTypeId") + " data resources.");
                    }

                    FreeMarkerWorker.renderTemplateFromString("delegator:" + delegator.getDelegatorName() + ":DataResource:"
                            + dataResourceId, templateText, templateContext, out, UtilDateTime.nowTimestamp().getTime(), useTemplateCache);
                } catch (TemplateException e) {
                    throw new GeneralException("Error rendering FTL template", e);
                }
            } else if ("XSLT".equals(dataTemplateTypeId)) {
                File targetFileLocation = new File(System.getProperty("ofbiz.home") + "/runtime/tempfiles/docbook.css");
                String defaultVisualThemeId = EntityUtilProperties.getPropertyValue("general", "VISUAL_THEME", delegator);
                visualTheme = ThemeFactory.getVisualThemeFromId(defaultVisualThemeId);
                modelTheme = visualTheme.getModelTheme();
                String docbookStylesheet = modelTheme.getProperty("VT_DOCBOOKSTYLESHEET").toString();
                File sourceFileLocation = new File(System.getProperty("ofbiz.home") + "/themes" + docbookStylesheet.substring(1,
                        docbookStylesheet.length() - 1));
                UtilMisc.copyFile(sourceFileLocation, targetFileLocation);
                // get the template data for rendering
                String templateLocation = DataResourceWorker.getContentFile(dataResource.getString("dataResourceTypeId"),
                        dataResource.getString("objectInfo"), (String) templateContext.get("contextRoot")).toString();
                // render the XSLT template and file
                String outDoc = null;
                try {
                    outDoc = XslTransform.renderTemplate(templateLocation, (String) templateContext.get("docFile"));
                } catch (TransformerException c) {
                    Debug.logError("XSL TransformerException: " + c.getMessage(), MODULE);
                }
                out.append(outDoc);

            // Screen Widget template
            } else if ("SCREEN_COMBINED".equals(dataTemplateTypeId)) {
                try {
                    MapStack<String> context = MapStack.create(templateContext);
                    context.put("locale", locale);
                    // prepare the map for preRenderedContent
                    String textData = (String) context.get("textData");
                    if (UtilValidate.isNotEmpty(textData)) {
                        Map<String, Object> prc = new HashMap<>();
                        String mapKey = (String) context.get("mapKey");
                        if (mapKey != null) {
                            prc.put(mapKey, mapKey);
                        }
                        prc.put("body", textData); // used for default screen defs
                        context.put("preRenderedContent", prc);
                    }
                    // get the screen renderer; or create a new one
                    ScreenRenderer screens = (ScreenRenderer) context.get("screens");
                    if (screens == null) {
                     // TODO: replace "screen" to support dynamic rendering of different output
                        ScreenStringRenderer screenStringRenderer = new MacroScreenRenderer(modelTheme.getType("screen"),
                                modelTheme.getScreenRendererLocation("screen"));
                        screens = new ScreenRenderer(out, context, screenStringRenderer);
                        screens.getContext().put("screens", screens);
                    }
                    // render the screen
                    ModelScreen modelScreen = null;
                    ScreenStringRenderer renderer = screens.getScreenStringRenderer();
                    String combinedName = dataResource.getString("objectInfo");
                    if ("URL_RESOURCE".equals(dataResource.getString("dataResourceTypeId")) && UtilValidate.isNotEmpty(combinedName)
                            && combinedName.startsWith("component://")) {
                        modelScreen = ScreenFactory.getScreenFromLocation(combinedName);
                    } else { // stored in  a single file, long or short text
                        Document screenXml = UtilXml.readXmlDocument(getDataResourceText(dataResource, targetMimeTypeId, locale, templateContext,
                                delegator, cache), true, true);
                        Map<String, ModelScreen> modelScreenMap = ScreenFactory.readScreenDocument(screenXml, "DataResourceId: "
                                + dataResource.getString("dataResourceId"));
                        if (UtilValidate.isNotEmpty(modelScreenMap)) {
                            Map.Entry<String, ModelScreen> entry = modelScreenMap.entrySet().iterator().next();
                            // get first entry, only one screen allowed per file
                            modelScreen = entry.getValue();
                        }
                    }
                    if (UtilValidate.isNotEmpty(modelScreen)) {
                        modelScreen.renderScreenString(out, context, renderer);
                    } else {
                        throw new GeneralException("The dataResource file [" + dataResourceId + "] could not be found");
                    }
                } catch (SAXException | ParserConfigurationException e) {
                    throw new GeneralException("Error rendering Screen template", e);
                } catch (TemplateException e) {
                    throw new GeneralException("Error creating Screen renderer", e);
                }
            } else if ("FORM_COMBINED".equals(dataTemplateTypeId)) {
                try {
                    Map<String, Object> context = UtilGenerics.cast(templateContext.get("globalContext"));
                    context.put("locale", locale);
                    context.put("simpleEncoder", UtilCodec.getEncoder(modelTheme.getEncoder("screen")));
                    HttpServletRequest request = (HttpServletRequest) context.get("request");
                    HttpServletResponse response = (HttpServletResponse) context.get("response");
                    ModelForm modelForm = null;
                    ModelReader entityModelReader = delegator.getModelReader();
                    String formText = getDataResourceText(dataResource, targetMimeTypeId, locale, templateContext, delegator, cache);
                    Document formXml = UtilXml.readXmlDocument(formText, true, true);
                    Map<String, ModelForm> modelFormMap = FormFactory.readFormDocument(formXml, entityModelReader,
                            UtilHttp.getVisualTheme(request), dispatcher.getDispatchContext(), null);

                    if (UtilValidate.isNotEmpty(modelFormMap)) {
                        Map.Entry<String, ModelForm> entry = modelFormMap.entrySet().iterator().next();
                        // get first entry, only one form allowed per file
                        modelForm = entry.getValue();
                    }
                    String formrenderer = modelTheme.getFormRendererLocation("screen");
                    MacroFormRenderer renderer = new MacroFormRenderer(formrenderer, request, response);
                    FormRenderer formRenderer = null;
                    if (modelForm != null) {
                        formRenderer = new FormRenderer(modelForm, renderer);
                        formRenderer.render(out, context);
                    } else {
                        throw new GeneralException("Error rendering Screen template");
                    }
                } catch (TemplateException e) {
                    throw new GeneralException("Error creating Screen renderer", e);
                } catch (Exception e) {
                    throw new GeneralException("Error rendering Screen template", e);
                }
            } else {
                throw new GeneralException("The dataTemplateTypeId [" + dataTemplateTypeId + "] is not yet supported");
            }
        }
    }

    // ----------------------------
    // Data Resource Data Gathering
    // ----------------------------

    public static String getDataResourceText(GenericValue dataResource, String mimeTypeId, Locale locale, Map<String, Object> context,
            Delegator delegator, boolean cache) throws IOException, GeneralException {
        Writer out = new StringWriter();
        writeDataResourceText(dataResource, mimeTypeId, locale, context, delegator, out, cache);
        return out.toString();
    }

    public static void writeDataResourceText(GenericValue dataResource, String mimeTypeId, Locale locale, Map<String, Object> templateContext,
            Delegator delegator, Appendable out, boolean cache) throws IOException, GeneralException {
        Map<String, Object> context = UtilGenerics.cast(templateContext.get("context"));
        if (context == null) {
            context = new HashMap<>();
        }
        String webSiteId = (String) templateContext.get("webSiteId");
        if (UtilValidate.isEmpty(webSiteId)) {
            webSiteId = (String) context.get("webSiteId");
        }

        String https = (String) templateContext.get("https");
        if (UtilValidate.isEmpty(https)) {
            https = (String) context.get("https");
        }

        String rootDir = (String) templateContext.get("rootDir");
        if (UtilValidate.isEmpty(rootDir)) {
            rootDir = (String) context.get("rootDir");
        }

        String dataResourceId = dataResource.getString("dataResourceId");
        String dataResourceTypeId = dataResource.getString("dataResourceTypeId");

        // default type
        if (UtilValidate.isEmpty(dataResourceTypeId)) {
            dataResourceTypeId = "SHORT_TEXT";
        }

        // text types
        if ("SHORT_TEXT".equals(dataResourceTypeId) || "LINK".equals(dataResourceTypeId)) {
            String text = dataResource.getString("objectInfo");
            writeText(dataResource, text, templateContext, mimeTypeId, locale, out);
        } else if ("ELECTRONIC_TEXT".equals(dataResourceTypeId)) {
            GenericValue electronicText = EntityQuery.use(delegator).from("ElectronicText")
                    .where("dataResourceId", dataResourceId)
                    .cache(cache).queryOne();
            if (electronicText != null) {
                String text = electronicText.getString("textData");
                writeText(dataResource, text, templateContext, mimeTypeId, locale, out);
            }

        // object types
        } else if (dataResourceTypeId.endsWith("_OBJECT")) {
            String text = (String) dataResource.get("dataResourceId");
            writeText(dataResource, text, templateContext, mimeTypeId, locale, out);

        // RESOURCE type
        } else if ("URL_RESOURCE".equals(dataResourceTypeId)) {
            String text = null;
            URL url = FlexibleLocation.resolveLocation(dataResource.getString("objectInfo"));

            if (url.getHost() != null) { // is absolute
                // One opener for both fetch sites: it validates the target, re-validates the peer it
                // actually connected to, refuses a redirect, and hands back a stream that throws at the
                // configured ceiling and disconnects when it is closed.
                try (InputStream body = (InputStream) openUrlResource(url).get("stream")) {
                    text = IOUtils.toString(body, StandardCharsets.UTF_8);
                }
            } else {
                String prefix = DataResourceWorker.buildRequestPrefix(delegator, locale, webSiteId, https);
                String sep = "";
                if (url.toString().indexOf('/') != 0 && prefix.lastIndexOf('/') != (prefix.length() - 1)) {
                    sep = "/";
                }
                String fixedUrlStr = prefix + sep + url.toString();
                URL fixedUrl = UtilURL.fromUrlString(fixedUrlStr);
                URL configuredOrigin = UtilURL.fromUrlString(prefix);
                if (fixedUrl == null || configuredOrigin == null) {
                    throw refuseUrlResource("a relative resource and this deployment's configured prefix ["
                            + prefix + "] did not combine into a URL that can be fetched");
                }
                // The SAME hardened opener the absolute case uses, with the address policy replaced by an
                // origin check: this used to be URL.getContent(), which had no timeouts, followed redirects to
                // wherever a Location header named, held the whole response in the heap and released nothing.
                try (InputStream body = (InputStream) openSelfOriginResource(fixedUrl, configuredOrigin)
                        .get("stream")) {
                    text = IOUtils.toString(body, StandardCharsets.UTF_8);
                }
            }
            out.append(text);

        // file types
        } else if (dataResourceTypeId.endsWith("_FILE_BIN")) {
            writeText(dataResource, dataResourceId, templateContext, mimeTypeId, locale, out);
        } else if (dataResourceTypeId.endsWith("_FILE")) {
            String dataResourceMimeTypeId = dataResource.getString("mimeTypeId");
            String objectInfo = dataResource.getString("objectInfo");

            if (dataResourceMimeTypeId == null || dataResourceMimeTypeId.startsWith("text")) {
                // The identity is handed on, because it is what a storage key is derived from: this is the one
                // render route that knows which DataResource it is rendering, so it is the one that can read
                // through a configured provider.
                renderFile(dataResourceTypeId, objectInfo, rootDir, out, dataResource.getDelegator(), dataResourceId);
            } else {
                writeText(dataResource, dataResourceId, templateContext, mimeTypeId, locale, out);
            }
        } else {
            throw new GeneralException("The dataResourceTypeId [" + dataResourceTypeId + "] is not supported in renderDataResourceAsText");
        }
    }

    public static void writeText(GenericValue dataResource, String textData, Map<String, Object> context, String targetMimeTypeId, Locale locale,
                                 Appendable out) throws GeneralException, IOException {
        String dataResourceMimeTypeId = dataResource.getString("mimeTypeId");
        Delegator delegator = dataResource.getDelegator();

        // assume HTML as data RESOURCE data
        if (UtilValidate.isEmpty(dataResourceMimeTypeId)) {
            dataResourceMimeTypeId = "text/html";
        }

        // assume HTML for target
        if (UtilValidate.isEmpty(targetMimeTypeId)) {
            targetMimeTypeId = "text/html";
        }

        // we can only render text
        if (!targetMimeTypeId.startsWith("text")) {
            throw new GeneralException("Method writeText() only supports rendering text content : " + targetMimeTypeId + " is not supported");
        }

        if ("text/html".equals(targetMimeTypeId)) {
            // get the default mime type template
            GenericValue mimeTypeTemplate = EntityQuery.use(delegator).from("MimeTypeHtmlTemplate").where("mimeTypeId",
                    dataResourceMimeTypeId).cache().queryOne();

            if (mimeTypeTemplate != null && mimeTypeTemplate.get("templateLocation") != null) {
                // prepare the context
                Map<String, Object> mimeContext = new HashMap<>();
                mimeContext.putAll(context);
                mimeContext.put("dataResource", dataResource);
                mimeContext.put("textData", textData);

                String mimeString = DataResourceWorker.renderMimeTypeTemplate(mimeTypeTemplate, mimeContext);
                out.append(mimeString);
            } else {
                if (textData != null) {
                    out.append(textData);
                }
            }
        } else {
            out.append(textData);
        }
    }

    public static String renderMimeTypeTemplate(GenericValue mimeTypeTemplate, Map<String, Object> context) throws GeneralException, IOException {
        String location = mimeTypeTemplate.getString("templateLocation");
        StringWriter writer = new StringWriter();
        try {
            FreeMarkerWorker.renderTemplate(location, context, writer);
        } catch (TemplateException e) {
            throw new GeneralException(e.getMessage(), e);
        }

        return writer.toString();
    }

    public static void renderFile(String dataResourceTypeId, String objectInfo, String rootDir, Appendable out) throws GeneralException, IOException {
        // A caller of this frozen signature brings neither the delegator nor the resource, and that pair of
        // nulls is what marks a call as coming from it: storeForResource selects no provider for it, so this
        // reads locally exactly as it always has. The overload below is the seam, and only the render path that
        // holds the DataResource can reach it.
        renderFile(dataResourceTypeId, objectInfo, rootDir, out, null, null);
    }

    /**
     * Renders a file-backed {@code DataResource} into the supplied output, reading through the configured content
     * storage provider when one holds the content.
     *
     * <p>This is the read seam the plan names at {@code renderFile} (plan sections 0.2.1 and 0.4.1). It only
     * copies bytes to the caller's output, so a stream is all it needs and it writes nothing anywhere - which is
     * what lets a deployment serve content it holds no local copy of without leaving durable local state behind
     * (CWE-400/CWE-459).
     *
     * <p><strong>Authorisation runs first, in each branch's own order.</strong> Every branch keeps the checks it
     * has always performed in the order it performed them, which is deliberately not the same order in every
     * branch, and the provider is consulted only once they have passed. A location this method would have refused
     * therefore costs no provider request, so no caller can use it as an existence oracle for a location an
     * operator has forbidden (CWE-200).
     *
     * <p><strong>Only content inside the deployment's own tree is provider-backed.</strong> A location that does
     * not lie under {@code ofbiz.home} is host-local state an operator placed deliberately - an absolute
     * {@code LOCAL_FILE} elsewhere on the host - and is read from where it is. This is the rule that keeps the
     * seam pointed at the content the plan puts in object storage, {@code runtime/uploads} and the file-backed
     * resources beside it (plan section 0.6.3).
     *
     * <p><strong>{@code CONTEXT_FILE} is never provider-backed.</strong> Its content ships inside the container
     * image, is byte-identical on every instance, and is not durable deployment state, so there is nothing to
     * externalise; making it provider-backed would instead require every instance's own static files to be
     * uploaded before it could serve them. That branch is untouched.
     *
     * <p><strong>Absence in the provider fails closed.</strong> When a provider is configured and holds nothing
     * for a resource, that is reported rather than quietly answered from whatever the local disk happens to hold,
     * because silently serving a different copy of content is how a stale or wrong document reaches a user
     * unnoticed. A deployment migrating existing local content into a provider sets
     * {@code content.store.local.fallback} to allow the local read explicitly and temporarily.
     *
     * @param dataResourceTypeId the {@code DataResource.dataResourceTypeId}
     * @param objectInfo the {@code DataResource.objectInfo} location
     * @param rootDir the webapp context root, used by the {@code CONTEXT_FILE} branch
     * @param out the output to render into
     * @param delegator the delegator the provider and its tunables are resolved against; null from the frozen
     *     public signature, which then never consults a provider
     * @param dataResourceId the identifier of the resource being rendered; null as above
     * @throws GeneralException if the location is refused, or a configured provider cannot be reached or holds
     *     nothing for the resource
     * @throws IOException if the content cannot be read or the output cannot be written
     */
    private static void renderFile(String dataResourceTypeId, String objectInfo, String rootDir, Appendable out,
            Delegator delegator, String dataResourceId) throws GeneralException, IOException {
        // TODO: this method assumes the file is a text file, if it is an image we should respond differently,
        //  see the comment above for IMAGE_OBJECT type data RESOURCE

        // One resolution for the whole operation, so every branch below and every helper it calls sees the same
        // provider even if the configuration is changed while this runs. Null means content is held in the
        // DataResource database columns - the committed default - and every line below is then the pre-existing
        // local read, unchanged.
        ContentStore store = storeForResource(delegator, dataResourceId);
        if ("LOCAL_FILE".equals(dataResourceTypeId) && UtilValidate.isNotEmpty(objectInfo)) {
            File file = FileUtil.getFile(objectInfo);
            if (!file.isAbsolute()) {
                throw new GeneralException("File (" + objectInfo + ") is not absolute");
            }
            boolean absent = !file.exists();
            if (absent && store == null) {
                throw new FileNotFoundException("No file found: " + file.getAbsolutePath());
            }
            SecurityUtil.checkLocalFileAllowList(file);
            if (renderThrough(store, delegator, dataResourceTypeId, file, out, absent)) {
                return;
            }
            if (absent) {
                throw new FileNotFoundException("No file found: " + file.getAbsolutePath());
            }
            try (InputStreamReader in = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                UtilIO.copy(in, out);
            }
        } else if ("OFBIZ_FILE".equals(dataResourceTypeId) && UtilValidate.isNotEmpty(objectInfo)) {
            // The same canonical value resolveContentLocation and deploymentRelativePath use; see the note there.
            String prefix = deploymentHome();
            if (UtilValidate.isEmpty(prefix)) {
                // Refused rather than resolved relatively. The pre-existing code dereferenced this value and
                // raised a NullPointerException when it was unset; a blank root would instead silently resolve
                // the location against whatever directory the process started in, which is a different file on
                // every instance. The filesystem provider refuses a blank root for the same reason.
                throw new GeneralException("Cannot resolve the OFBIZ_FILE location [" + objectInfo + "] because"
                        + " the ofbiz.home system property is not set");
            }
            String sep = "";
            if (objectInfo.indexOf('/') != 0 && prefix.lastIndexOf('/') != (prefix.length() - 1)) {
                sep = "/";
            }
            File file = FileUtil.getFile(prefix + sep + objectInfo);
            boolean absent = !file.exists();
            if (absent && store == null) {
                throw new FileNotFoundException("No file found: " + file.getAbsolutePath());
            }
            SecurityUtil.checkOfbizFileAllowList(file);
            if (renderThrough(store, delegator, dataResourceTypeId, file, out, absent)) {
                return;
            }
            if (absent) {
                throw new FileNotFoundException("No file found: " + file.getAbsolutePath());
            }
            try (InputStreamReader in = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                UtilIO.copy(in, out);
            }
        } else if ("CONTEXT_FILE".equals(dataResourceTypeId) && UtilValidate.isNotEmpty(objectInfo)) {
            String prefix = rootDir;
            String sep = "";
            if (objectInfo.indexOf('/') != 0 && prefix.lastIndexOf('/') != (prefix.length() - 1)) {
                sep = "/";
            }
            File file = FileUtil.getFile(prefix + sep + objectInfo);
            checkContextFileBoundary(file, rootDir);
            if (!file.exists()) {
                throw new FileNotFoundException("No file found: " + file.getAbsolutePath());
            }
            try (InputStreamReader in = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                if (Debug.infoOn()) {
                    String enc = in.getEncoding();
                    Debug.logInfo("in serveImage, encoding:" + enc, MODULE);
                }
                UtilIO.copy(in, out);
            } catch (FileNotFoundException e) {
                Debug.logError(e, " in renderDataResourceAsHtml(CONTEXT_FILE), in FNFexception:", MODULE);
                throw new GeneralException("Could not find context file to render", e);
            } catch (Exception e) {
                Debug.logError(" in renderDataResourceAsHtml(CONTEXT_FILE), got exception:" + e.getMessage(), MODULE);
            }
        }
    }

    // ----------------------------
    // Data Resource Streaming
    // ----------------------------

    /**
     * getDataResourceStream - gets an InputStream and Content-Length of a DataResource
     * @param dataResource
     * @param https
     * @param webSiteId
     * @param locale
     * @param contextRoot
     * @return Map containing 'stream': the InputStream and 'length' a Long containing the content-length
     * @throws IOException
     * @throws GeneralException
     */
    public static Map<String, Object> getDataResourceStream(GenericValue dataResource, String https, String webSiteId, Locale locale,
                                                            String contextRoot, boolean cache) throws IOException, GeneralException {
        if (dataResource == null) {
            throw new GeneralException("Cannot stream null data RESOURCE!");
        }

        String dataResourceTypeId = dataResource.getString("dataResourceTypeId");
        String dataResourceId = dataResource.getString("dataResourceId");
        Delegator delegator = dataResource.getDelegator();

        // first text based data
        if (dataResourceTypeId.endsWith("_TEXT") || "LINK".equals(dataResourceTypeId)) {
            String text = "";

            if ("SHORT_TEXT".equals(dataResourceTypeId) || "LINK".equals(dataResourceTypeId)) {
                text = dataResource.getString("objectInfo");
            } else if ("ELECTRONIC_TEXT".equals(dataResourceTypeId)) {
                GenericValue electronicText = EntityQuery.use(delegator).from("ElectronicText")
                        .where("dataResourceId", dataResourceId)
                        .cache(cache).queryOne();
                if (electronicText != null) {
                    text = electronicText.getString("textData");
                }
            } else {
                throw new GeneralException("Unsupported TEXT type; cannot stream");
            }

            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            return UtilMisc.toMap("stream", new ByteArrayInputStream(bytes), "length", (long) bytes.length);

        // object (binary) data
        }
        if (dataResourceTypeId.endsWith("_OBJECT")) {
            byte[] bytes = new byte[0];
            GenericValue valObj;

            if ("IMAGE_OBJECT".equals(dataResourceTypeId)) {
                valObj = EntityQuery.use(delegator).from("ImageDataResource").where("dataResourceId", dataResourceId).cache(cache).queryOne();
                if (valObj != null) {
                    bytes = valObj.getBytes("imageData");
                }
            } else if ("VIDEO_OBJECT".equals(dataResourceTypeId)) {
                valObj = EntityQuery.use(delegator).from("VideoDataResource").where("dataResourceId", dataResourceId).cache(cache).queryOne();
                if (valObj != null) {
                    bytes = valObj.getBytes("videoData");
                }
            } else if ("AUDIO_OBJECT".equals(dataResourceTypeId)) {
                valObj = EntityQuery.use(delegator).from("AudioDataResource").where("dataResourceId", dataResourceId).cache(cache).queryOne();
                if (valObj != null) {
                    bytes = valObj.getBytes("audioData");
                }
            } else if ("OTHER_OBJECT".equals(dataResourceTypeId)) {
                valObj = EntityQuery.use(delegator).from("OtherDataResource").where("dataResourceId", dataResourceId).cache(cache).queryOne();
                if (valObj != null) {
                    bytes = valObj.getBytes("dataResourceContent");
                }
            } else {
                throw new GeneralException("Unsupported OBJECT type [" + dataResourceTypeId + "]; cannot stream");
            }

            return UtilMisc.toMap("stream", new ByteArrayInputStream(bytes), "length", (long) bytes.length);

        // file data
        } else if (dataResourceTypeId.endsWith("_FILE") || dataResourceTypeId.endsWith("_FILE_BIN")) {
            String objectInfo = dataResource.getString("objectInfo");
            if (UtilValidate.isNotEmpty(objectInfo)) {
                // Content storage seam, the second of the two the plan names (plan sections 0.2.1 and 0.4.1).
                // Consumers are served straight from the provider, so serving content leaves no durable local
                // state behind (CWE-400/CWE-459). One resolution serves the whole operation, and the location is
                // authorised through the very code getContentFile uses before the provider is asked anything, so
                // nothing is requested for a location that method would refuse (CWE-200). Inert by default: with
                // no provider configured the store is null, presence is required exactly as before, and the two
                // statements after this are the pre-existing local read.
                ContentStore store = storeForResource(dataResource.getDelegator(), dataResourceId);
                File file = resolveContentLocation(dataResourceTypeId, objectInfo, contextRoot,
                        store == null ? ABSENCE_IS_FINAL : ASK_THE_PROVIDER);
                if (file == null) {
                    throw new GeneralException("The dataResourceTypeId [" + dataResourceTypeId + "] names no file"
                            + " location; cannot stream");
                }
                Map<String, Object> streamed = streamThrough(store, dataResource.getDelegator(),
                        dataResourceTypeId, file, !file.exists());
                if (streamed != null) {
                    return streamed;
                }
                if (!file.exists()) {
                    throw new FileNotFoundException("No file found: " + file.getAbsolutePath());
                }
                return UtilMisc.toMap("stream", Files.newInputStream(file.toPath(), StandardOpenOption.READ), "length", file.length());
            }
            throw new GeneralException("No objectInfo found for FILE type [" + dataResourceTypeId + "]; cannot stream");

        // URL RESOURCE data
        } else if ("URL_RESOURCE".equals(dataResourceTypeId)) {
            String objectInfo = dataResource.getString("objectInfo");
            if (UtilValidate.isNotEmpty(objectInfo)) {
                URL url = UtilURL.fromUrlString(objectInfo);
                if (url.getHost() == null) { // is relative
                    String newUrl = DataResourceWorker.buildRequestPrefix(delegator, locale, webSiteId, https);
                    if (!newUrl.endsWith("/")) {
                        newUrl = newUrl + "/";
                    }
                    newUrl = newUrl + url.toString();
                    url = UtilURL.fromUrlString(newUrl);
                }

                // The same opener as the render path above, so both sites apply one policy and one
                // ceiling, and neither can drift from the other.
                return openUrlResource(url);
            }
            throw new GeneralException("No objectInfo found for URL_RESOURCE type; cannot stream");
        }

        // unsupported type
        throw new GeneralException("The dataResourceTypeId [" + dataResourceTypeId + "] is not supported in getDataResourceStream");
    }

    /**
     * Reads a {@code DataResource}'s whole content into a buffer.
     *
     * <p>Two things are true of this method that were not true of the streams it used to read, and both come from
     * the same change: the stream it is handed may now be a response from a content-storage provider rather than
     * a file on this disk.
     *
     * <ul>
     * <li><strong>The stream is closed, on every path.</strong> A provider's stream holds a pooled connection, so
     * a stream left open by an exception is a connection this instance never gets back - and enough of them stop
     * the instance reading content at all (CWE-404/CWE-459). A local file stream was leaked here too; closing it
     * is a plain improvement with no behaviour to change.</li>
     * <li><strong>Provider content is bounded by {@code content.store.max.object.size}.</strong> That ceiling is
     * the deployment's statement of how much content a single whole read may hold in the heap of one instance, and
     * this method is a whole read by definition. It is applied to CONTENT SERVED BY A PROVIDER and to nothing
     * else, which is exactly the reach of the change: a local file and a database column are read here precisely
     * as they were before, so no deployment can find content it could read yesterday refused today. A provider's
     * stream is recognised by its type, {@link ContentStore.ContentStream}, which is also what carries the length
     * the store declared - so the refusal happens before a byte is transferred rather than after the heap has
     * already been filled. Content too large to hold whole is served by
     * {@link #getDataResourceStream}, which streams it and applies no ceiling at all.</li>
     * </ul>
     *
     * @param delegator the delegator the resource is read through
     * @param dataResourceId the immutable identifier of the resource
     * @param https whether a relative URL resource resolves over https
     * @param webSiteId the web site a relative URL resource resolves against
     * @param locale the locale a relative URL resource resolves in
     * @param rootDir the webapp context root, required by the {@code CONTEXT_FILE} types
     * @return the whole content
     * @throws IOException if the content cannot be read
     * @throws GeneralException if the resource cannot be resolved, or provider content exceeds the ceiling
     */
    public static ByteBuffer getContentAsByteBuffer(Delegator delegator, String dataResourceId, String https, String webSiteId, Locale locale,
                                                    String rootDir) throws IOException, GeneralException {
        GenericValue dataResource = EntityQuery.use(delegator).from("DataResource").where("dataResourceId", dataResourceId).queryOne();
        Map<String, Object> resourceData = DataResourceWorker.getDataResourceStream(dataResource, https, webSiteId, locale, rootDir, false);
        try (InputStream stream = (InputStream) resourceData.get("stream")) {
            if (!(stream instanceof ContentStore.ContentStream served)) {
                return ByteBuffer.wrap(IOUtils.toByteArray(stream));
            }
            long limit = ContentStoreFactory.maxObjectSize(delegator);
            if (served.length() > limit) {
                throw tooLargeToRead(dataResourceId, served.length(), limit);
            }
            // Bounded again as it is read, because the length above is what the store DECLARED and a store that
            // understates it must not be able to make this allocate more than the ceiling allows.
            byte[] read = stream.readNBytes((int) limit);
            if (stream.read() != -1) {
                throw tooLargeToRead(dataResourceId, limit + 1, limit);
            }
            return ByteBuffer.wrap(read);
        }
    }

    /**
     * Reports provider content that cannot be read whole because it exceeds the configured ceiling.
     *
     * <p>Names the resource, the size and the setting that governs it, and nothing about where the deployment
     * keeps its content: this reaches the caller of a service and, through it, an end user (CWE-200). The storage
     * key is in the log, under the same reference, wherever the provider itself reported one.
     *
     * @param dataResourceId the immutable identifier of the resource
     * @param length how large the content is, as far as it is known
     * @param limit the ceiling it exceeds
     * @return the exception to throw
     */
    private static GeneralException tooLargeToRead(String dataResourceId, long length, long limit) {
        Debug.logError("Content store refusal: the content of DataResource [" + dataResourceId + "] is " + length
                + " bytes, over the " + ContentStoreFactory.MAX_OBJECT_SIZE_PROPERTY + " ceiling of " + limit
                + "; content this large has to be streamed rather than read whole", MODULE);
        return new GeneralException("The content of DataResource [" + dataResourceId + "] is larger than the "
                + ContentStoreFactory.MAX_OBJECT_SIZE_PROPERTY + " ceiling of " + limit + " bytes, which is the"
                + " most one read may hold. Raise that ceiling, or read this content as a stream.");
    }

    @Override
    public String renderDataResourceAsTextExt(Delegator delegator, String dataResourceId, Map<String, Object> templateContext,
            Locale locale, String targetMimeTypeId, boolean cache) throws GeneralException, IOException {
        return renderDataResourceAsText(null, delegator, dataResourceId, templateContext, locale, targetMimeTypeId, cache);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Content store seam support
    //
    // The helpers the three seams the plan names delegate through (plan sections 0.2.1, 0.4.1 and 0.6.3). Two of
    // the seams read - renderFile copies content to an output and getDataResourceStream hands a consumer a
    // stream - and two resolve a location that is about to be written: getContentFile resolves a file and
    // getDataResourceContentUploadPath resolves the upload directory. Every helper reports "nothing to do" when
    // no provider is configured, so the committed default - DataResource database storage with file-backed
    // resources on the local filesystem - reaches none of it. All object-storage behaviour itself lives in the
    // store package; nothing here knows which provider is active and nothing here touches a provider SDK.
    //
    // WHY THE WRITE IS SEAMED AT A RESOLUTION METHOD, AND PUBLISHED AT COMMIT.
    //
    // The services that write file-backed content resolve a location and then write to it themselves:
    // createFile computes an objectInfo and opens its own FileOutputStream, and createBinaryFile and
    // updateBinaryFile take the File getContentFile returns and open one on that. They are business services,
    // which the plan places out of scope, so none of them can be asked to hand its bytes anywhere; and the only
    // thing they all pass through is the resolution. So the resolution is where the seam goes.
    //
    // A resolution happens BEFORE the bytes are written, which is exactly what makes publishing from it correct
    // rather than awkward. What is registered at resolution time is a write-through: a note of the location, and
    // of what was there when it was resolved. At commit, whatever is on disk THEN is what gets published. That
    // ordering is what answers, by construction rather than by care, every way the earlier design could go
    // wrong:
    //
    //   * The bytes published are the bytes the deployment's own validation accepted. createBinaryFileMethod
    //     writes the caller's array and then calls SecuredUpload.isValidFile; createFileMethod copies a
    //     validated temporary file over the target. Publishing the caller's array at write time therefore
    //     published bytes that validation had not seen - and, where a sanitiser rewrote the file, bytes that had
    //     been deliberately replaced. Publishing what is on disk at commit cannot: a rejected upload returns a
    //     service error, the transaction rolls back, and nothing is published at all.
    //   * Nothing is visible in the store before the row that describes it commits, because nothing is written
    //     to the store before then.
    //   * There is no rollback path that removes anything, so no rollback can destroy content that was already
    //     there. The earlier design published immediately and registered a delete to undo it, which deleted the
    //     one stable key the content lives under - taking with it the PREVIOUS version, which the failed
    //     transaction never owned, and any concurrent write that had committed in between.
    //   * A write that never happened publishes nothing: the file is compared against what was there when it
    //     was resolved, so a read that merely dereferenced the File leaves the store untouched.
    //   * A write that REMOVED the content publishes a delete, which is what gives ContentStore.delete a
    //     production caller and keeps the store from serving content the deployment has discarded.
    //
    // In filesystem mode none of this runs: that provider's tree IS the deployment's own tree, so the write has
    // already landed in the provider by construction. In database mode there is no provider at all.
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Registers everything a resolved, provider-backed target holds to be brought into step with the content
     * store when the transaction that resolved it commits, and reports whether it was registered.
     *
     * <p>Called from the two resolution seams the plan names: {@link #getContentFile}, whose target is one file,
     * and {@link #getDataResourceContentUploadPath}, whose target is the upload directory a file is about to be
     * written into. Both reduce to the same thing - a set of locations and what each of them held at resolution
     * time - so both are served by one synchronisation.
     *
     * <p>It is deliberately silent about whether the caller is going to write. A read that only dereferences the
     * {@code File} leaves the location as it found it, and the comparison made at commit then publishes nothing.
     * That is what lets one seam serve both directions without having to know which one it is serving.
     *
     * <p><strong>The transaction is confirmed, not assumed.</strong> {@code TransactionUtil} silently does
     * nothing when asked to register a synchronisation while no transaction is active, so code that registered
     * and then relied on having done so would have had its work quietly dropped. The status decides:
     *
     * <ul>
     * <li><strong>Active.</strong> Registered. The store is brought into step after the commit that records the
     * row.</li>
     * <li><strong>No transaction.</strong> Nothing is registered: there is nothing to bind to, and no later
     * moment at which the bytes would be known to be complete. Every write path that reaches here runs inside a
     * service and therefore inside a transaction, so a resolution outside one is a read. Reported once per
     * target, so a deployment that does write outside a transaction can see why its content stayed local,
     * without a line per read.</li>
     * <li><strong>Marked for rollback, rolling back, or any other state.</strong> Nothing is registered. The
     * transaction is already doomed, so nothing it produces may reach the store.</li>
     * </ul>
     *
     * <p>It never throws for a state it will not register in, because this sits on the READ path as much as the
     * write path and a read must not begin to fail over the state of a transaction it does not use.
     *
     * @param store the provider serving this operation; never null and always one that holds content off the
     *     instance
     * @param target the file or directory being resolved
     * @param scan how to read what the target holds, run once now and again at completion
     * @param delegator the delegator the ceiling is resolved through; may be null
     * @return {@code true} when the work was registered against an active transaction
     */
    private static boolean registerWriteThrough(ContentStore store, File target,
            Supplier<Map<File, Snapshot>> scan, Delegator delegator) {
        int status;
        try {
            status = TransactionUtil.getStatus();
        } catch (GenericTransactionException e) {
            Debug.logWarning(e, "Content written below " + ContentStore.logReference(target.getAbsolutePath())
                    + " cannot be published to the content store, because the status of the transaction it was"
                    + " resolved in could not be established", MODULE);
            return false;
        }
        String identity = target.getAbsolutePath();
        if (status != Status.STATUS_ACTIVE) {
            reportUnboundResolution(identity, status);
            return false;
        }
        if (!PENDING_WRITE_THROUGHS.get().add(identity)) {
            // Already registered in this transaction. Two resolutions of one target - a service that resolves
            // and then re-resolves, or two services inside one transaction - are one piece of work, and it is
            // the state at commit that decides what that work is, so the first registration covers the second.
            return true;
        }
        try {
            TransactionUtil.registerSynchronization(new ContentWriteThrough(store, identity, scan, scan.get(),
                    delegator));
        } catch (GenericTransactionException e) {
            PENDING_WRITE_THROUGHS.get().remove(identity);
            Debug.logWarning(e, "Content written below " + ContentStore.logReference(target.getAbsolutePath())
                    + " cannot be published to the content store, because the publish could not be registered"
                    + " with the transaction that resolved it", MODULE);
            return false;
        }
        return true;
    }

    /**
     * Reports a resolution that cannot be bound to a transaction, once per target.
     *
     * <p>Once per target rather than once per resolution, because the same location is resolved on every read of
     * the resource and the report says nothing new the second time. Bounded, so that a deployment with a great
     * many such locations cannot fill its log with them.
     *
     * @param identity the absolute path of the target that will not be published
     * @param status the transaction status that prevents it
     */
    private static void reportUnboundResolution(String identity, int status) {
        if (REPORTED_UNBOUND_TARGETS.size() >= REPORTED_TARGET_LIMIT || !REPORTED_UNBOUND_TARGETS.add(identity)) {
            return;
        }
        if (status == Status.STATUS_NO_TRANSACTION) {
            Debug.logInfo("A content location was resolved outside a transaction, so a write to it cannot be"
                    + " published to the content store and only this instance would be able to read it. Reads"
                    + " are unaffected. A service always runs inside a transaction, so this is expected for a"
                    + " read and a defect only for a write.", MODULE);
            return;
        }
        Debug.logWarning("A content location was resolved in a transaction whose status is [" + status + "] rather"
                + " than active, so nothing written through it will be published to the content store", MODULE);
    }

    /**
     * Records what a single location holds, so that what happened to it can be told at commit.
     *
     * <p>Three facts, because no one of them is enough alone: whether the location existed at all, how many bytes
     * it held, and when it was last modified. A rewrite that happens to produce content of the same length is
     * caught by the timestamp, and one that lands inside a single timestamp tick is caught by the length. The
     * timestamp is read through {@link Files} rather than {@link File#lastModified()} because the former carries
     * the filesystem's own resolution - nanoseconds on every mainstream Unix filesystem - while the latter
     * truncates it to milliseconds, which a write can comfortably finish inside.
     *
     * @param existed whether anything was at the location
     * @param length how many bytes it held
     * @param modifiedAt when it was last modified, in nanoseconds, or -1 when nothing was there
     */
    private record Snapshot(boolean existed, long length, long modifiedAt) {

        /** What an absent location looks like. */
        private static final Snapshot ABSENT = new Snapshot(false, 0L, -1L);

        /**
         * Records what a location holds now.
         *
         * @param file the location to look at
         * @return the snapshot, which reports the location absent when it cannot be read at all
         */
        private static Snapshot of(File file) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
                if (!attributes.isRegularFile()) {
                    return ABSENT;
                }
                return new Snapshot(true, attributes.size(), attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS));
            } catch (IOException | RuntimeException absent) {
                return ABSENT;
            }
        }
    }

    /**
     * Reads what one file holds, as the one-entry map a write-through compares.
     *
     * @param file the location
     * @return a single-entry map, whose value reports absence when nothing is there
     */
    private static Map<File, Snapshot> scanFile(File file) {
        return Map.of(file, Snapshot.of(file));
    }

    /**
     * Reads what every file directly inside a directory holds, as the map a write-through compares.
     *
     * <p>Direct children only, and regular files only: an upload lands as a file in the directory the upload
     * path named, and descending further would make one upload's commit responsible for every file the
     * deployment has ever placed below that tree.
     *
     * @param directory the directory
     * @return what each file in it holds, empty when the directory cannot be read
     */
    private static Map<File, Snapshot> scanDirectory(File directory) {
        File[] children = directory.listFiles();
        if (children == null) {
            return Map.of();
        }
        Map<File, Snapshot> held = new LinkedHashMap<>();
        for (File child : children) {
            Snapshot snapshot = Snapshot.of(child);
            if (snapshot.existed()) {
                held.put(child, snapshot);
            }
        }
        return held;
    }

    /**
     * Brings the content store into step with what a resolved target holds, once the transaction that resolved it
     * has committed.
     *
     * <p>One instance per target per transaction. Every decision comes from the difference between what the
     * target held when it was resolved and what it holds at completion:
     *
     * <ul>
     * <li><strong>Unchanged.</strong> Nothing happens. This is every read.</li>
     * <li><strong>Content is there and differs, or is new.</strong> It is published, streamed from the file so
     * that content of a size an uploader chose is never held in this JVM's heap.</li>
     * <li><strong>Content was there and is gone.</strong> Its key is removed, so the store does not go on serving
     * content the deployment has discarded. This is the production caller of
     * {@link ContentStore#delete(String)}.</li>
     * </ul>
     *
     * <p>The ceiling is applied in {@link #beforeCompletion()}, where refusing still means something: the
     * transaction is marked for rollback, so a row is never committed for content the store could hold but no
     * instance could afterwards read in one piece. Applied after the commit it could only have logged a complaint
     * about a row that was already there.
     *
     * <p>A failure after the commit is reported and swallowed, because there is nothing left to fail: the row is
     * committed, and an exception escaping a completion callback suppresses whatever cleanup follows it. The
     * report says what could not be done so that an operator can finish it by hand.
     */
    private static final class ContentWriteThrough implements Synchronization {

        private final ContentStore store;
        private final String identity;
        private final Supplier<Map<File, Snapshot>> scan;
        private final Map<File, Snapshot> before;
        private final Delegator delegator;

        /**
         * What this transaction publishes, captured in {@link #beforeCompletion()} and consumed once in
         * {@link #afterCompletion(int)}. Null until it is captured, and null again afterwards, so a callback
         * invoked twice cannot publish the same staged copies twice. Not volatile because both callbacks are
         * invoked by the transaction manager on the thread that is completing the transaction, which is the
         * thread that captured it.
         */
        private List<PublicationStep> plan;

        /**
         * Binds one target's content to the completion of the transaction that resolved it.
         *
         * @param store the provider to bring into step
         * @param identity the absolute path of the target, which is what the registration is deduplicated by
         * @param scan how to read what the target holds
         * @param before what it held when it was resolved
         * @param delegator the delegator the ceiling is resolved through; may be null
         */
        private ContentWriteThrough(ContentStore store, String identity, Supplier<Map<File, Snapshot>> scan,
                Map<File, Snapshot> before, Delegator delegator) {
            this.store = store;
            this.identity = identity;
            this.scan = scan;
            this.before = before;
            this.delegator = delegator;
        }

        /**
         * Captures, INSIDE the transaction, exactly the bytes this transaction is publishing.
         *
         * <p><strong>The defect this closes.</strong> The publication used to happen entirely in
         * {@link #afterCompletion(int)}: the target was scanned again there and the file was re-opened and
         * streamed to the store. Between the ceiling check here and that re-read, any other transaction, thread
         * or process could rewrite the same file - two uploads to one location, a rollback restoring an earlier
         * version, or a write this deployment's own validation went on to reject - and the commit of THIS
         * transaction published whatever happened to be on disk at that moment, under this transaction's
         * authority (CWE-362, CWE-367). Nothing about the row that committed described those bytes.
         *
         * <p>So the bytes are copied to a private, transaction-owned staging file here, while the transaction is
         * still open, and {@link #afterCompletion(int)} publishes THAT copy and nothing else. A staging file is
         * created {@code CREATE_NEW} under a name no other transaction can name, is never written again after it
         * is closed, and is removed whichever way the transaction ends. What the store receives is therefore the
         * bytes this transaction had, whole, or nothing at all.
         *
         * <p><strong>The ceiling is now applied to the staged copy</strong>, which is what makes the check and
         * the publication describe the same object: measuring the live file and then publishing a re-read of it
         * left the two able to disagree. Refusing still means something here because the transaction is still
         * open - it is marked for rollback, so no row is committed for content the store could hold but no
         * instance could afterwards read in one piece - which is exactly why this work belongs before the commit
         * and not after it.
         *
         * <p>A failure to stage is also a rollback rather than a report. Staging is a local copy, so a failure
         * means this instance cannot write to its own disk; committing a row whose content is known to be
         * unpublishable would leave the deployment with a resource that resolves to nothing on every other
         * instance.
         */
        @Override
        public void beforeCompletion() {
            long limit = ContentStoreFactory.maxObjectSize(delegator);
            // ONE scan, and the last one: every decision below is taken from it, so nothing that happens after
            // this line can change what this transaction publishes.
            Map<File, Snapshot> now = scan.get();
            List<PublicationStep> steps = new ArrayList<>();
            try {
                for (Map.Entry<File, Snapshot> entry : now.entrySet()) {
                    Snapshot state = entry.getValue();
                    if (state.equals(before.get(entry.getKey()))) {
                        continue;
                    }
                    if (!state.existed()) {
                        // Present when this transaction resolved it and absent now: the single-file scan reports
                        // an absent location as an ABSENT snapshot rather than by omitting it, so this - and not
                        // the loop below, which covers a child that left a directory listing - is where a
                        // deleted upload is noticed.
                        if (before.getOrDefault(entry.getKey(), Snapshot.ABSENT).existed()) {
                            steps.add(PublicationStep.removal(entry.getKey()));
                        }
                        continue;
                    }
                    PublicationStep staged = PublicationStep.captured(entry.getKey());
                    steps.add(staged);
                    if (staged.length() > limit) {
                        discard(steps);
                        refuseOversizedContent(staged.length(), limit);
                        return;
                    }
                }
                for (Map.Entry<File, Snapshot> entry : before.entrySet()) {
                    if (entry.getValue().existed() && !now.containsKey(entry.getKey())) {
                        steps.add(PublicationStep.removal(entry.getKey()));
                    }
                }
            } catch (IOException | RuntimeException cannotCapture) {
                discard(steps);
                refuseUncapturedContent(cannotCapture);
                return;
            }
            plan = steps;
        }

        /**
         * Marks the transaction for rollback rather than committing a row for content that cannot be published.
         *
         * @param length how large the content is
         * @param limit the ceiling it exceeds
         */
        private void refuseOversizedContent(long length, long limit) {
            Debug.logError("Content store refusal: content written below " + ContentStore.logReference(identity)
                    + " is " + length
                    + " bytes, over the " + ContentStoreFactory.MAX_OBJECT_SIZE_PROPERTY + " ceiling of " + limit
                    + "; the transaction is rolled back rather than recording content the store may hold but no"
                    + " instance could read whole. Raise that ceiling to store content this large.", MODULE);
            rollBack("Content larger than " + ContentStoreFactory.MAX_OBJECT_SIZE_PROPERTY
                    + " cannot be published to the content store");
        }

        /**
         * Marks the transaction for rollback when this instance could not take its own copy of the bytes.
         *
         * @param failure why the copy could not be taken
         */
        private void refuseUncapturedContent(Exception failure) {
            Debug.logError(failure, "Content store refusal: content written below "
                    + ContentStore.logReference(identity) + " could not be copied to this instance's staging area,"
                    + " so the bytes this transaction would publish cannot be captured. The transaction is rolled"
                    + " back rather than committing a row whose content no other instance could read.", MODULE);
            rollBack("Content could not be captured for publication to the content store");
        }

        /**
         * Marks the transaction for rollback, reporting a failure to do so rather than propagating it.
         *
         * @param reason the reason recorded with the rollback
         */
        private void rollBack(String reason) {
            try {
                TransactionUtil.setRollbackOnly(reason, null);
            } catch (GenericTransactionException e) {
                Debug.logError(e, "The transaction recording content that cannot be published could not be marked"
                        + " for rollback, so the row may commit without the content being published", MODULE);
            }
        }

        @Override
        public void afterCompletion(int status) {
            PENDING_WRITE_THROUGHS.get().remove(identity);
            List<PublicationStep> steps = plan;
            plan = null;
            if (steps == null) {
                // Either the transaction never reached beforeCompletion - a rollback, which is nothing to do -
                // or it did and refused, having already marked itself for rollback. A commit without a captured
                // plan would mean this synchronization was never given the chance to capture one, which is
                // reported because the row then describes content the store was never handed.
                if (status == Status.STATUS_COMMITTED) {
                    Debug.logError("The transaction recording content below " + ContentStore.logReference(identity)
                            + " committed without this deployment having captured the bytes to publish, so the"
                            + " content store was not brought into step with it and the content has to be"
                            + " published by hand", MODULE);
                }
                return;
            }
            try {
                if (status != Status.STATUS_COMMITTED) {
                    // Nothing was written to the store, and nothing is removed from it. A transaction that did
                    // not commit leaves the store exactly as it was - including whatever version of this content
                    // was already there, and any write another transaction committed in the meantime.
                    return;
                }
                for (PublicationStep step : steps) {
                    bringIntoStep(step);
                }
            } finally {
                // Whichever way the transaction ended, and whether or not each step succeeded: the staged copies
                // are this transaction's own and nothing reads them afterwards. The deployment's own tree still
                // holds the content, so a step that failed is republished from there by hand, exactly as the
                // report above says - keeping a second copy of it here would only fill the disk.
                discard(steps);
            }
        }

        /**
         * Publishes or removes one captured step's content, reporting rather than propagating a failure.
         *
         * @param step the captured publication or removal
         */
        private void bringIntoStep(PublicationStep step) {
            String key = null;
            try {
                String relative = deploymentRelativePath(step.file());
                if (relative == null) {
                    // Outside the deployment's own tree, so no key describes it and the read seams read it from
                    // where it is. Nothing to publish and nothing to remove.
                    return;
                }
                key = ContentStoreFactory.storeKey(store, relative);
                if (step.staged() == null) {
                    store.delete(key);
                    Debug.logInfo("Content was removed by a committed transaction, so it was removed from the"
                            + " content store under " + ContentStore.logReference(key) + " as well", MODULE);
                    return;
                }
                publish(key, step);
            } catch (GeneralException | IOException | RuntimeException e) {
                Debug.logError(e, "The transaction recording content committed, but the content store could not be"
                        + " brought into step with it under " + ContentStore.logReference(key) + ". The store and"
                        + " this deployment's own tree now disagree about this content, and it should be published"
                        + " by hand.", MODULE);
            }
        }

        /**
         * Streams one captured copy to the store.
         *
         * <p>The copy, not the live file: it is the bytes this transaction validated, it cannot have changed
         * since, and its length and its content are read from the same object - so the store is never told a
         * length that describes different bytes.
         *
         * @param key the storage key
         * @param step the captured publication
         * @throws GeneralException if the provider refuses the key or the length
         * @throws IOException if the copy cannot be read or stored
         */
        private void publish(String key, PublicationStep step) throws GeneralException, IOException {
            try (InputStream content = Files.newInputStream(step.staged(), StandardOpenOption.READ)) {
                store.put(key, content, step.length());
            }
            Debug.logInfo("Published " + step.length() + " bytes to the content store under "
                    + ContentStore.logReference(key), MODULE);
        }

        /**
         * Removes every staged copy of a plan, whether it was published or not.
         *
         * @param steps the plan, which may be partly built
         */
        private static void discard(List<PublicationStep> steps) {
            for (PublicationStep step : steps) {
                step.discard();
            }
        }
    }

    /**
     * One location's captured contribution to what a committing transaction publishes.
     *
     * <p>A publication carries the immutable staged copy of the bytes and their length; a removal carries
     * neither, because there is nothing to capture. Both carry the location, which is what the storage key is
     * derived from at publication time.
     *
     * @param file the location this step describes
     * @param staged the private copy of its bytes, or null when the step is a removal
     * @param length how many bytes the staged copy holds, 0 for a removal
     */
    private record PublicationStep(File file, Path staged, long length) {

        /**
         * Captures the current bytes of a location into a private copy this transaction owns.
         *
         * <p>Copied rather than referenced, and the length taken from the COPY: a length read from the live
         * file could describe different bytes by the time the copy was made, and the store would then be told a
         * length that does not match what it is being given.
         *
         * @param file the location whose bytes are being published
         * @return the captured step
         * @throws IOException if the bytes cannot be copied
         */
        private static PublicationStep captured(File file) throws IOException {
            Path staging = createPublicationStagingFile();
            boolean captured = false;
            try {
                Files.copy(file.toPath(), staging, StandardCopyOption.REPLACE_EXISTING);
                long length = Files.size(staging);
                captured = true;
                return new PublicationStep(file, staging, length);
            } finally {
                if (!captured) {
                    deleteQuietly(staging);
                }
            }
        }

        /**
         * Records that a location's content is gone, so its key is to be removed from the store.
         *
         * @param file the location
         * @return the removal step
         */
        private static PublicationStep removal(File file) {
            return new PublicationStep(file, null, 0L);
        }

        /** Removes this step's staged copy, if it has one. */
        private void discard() {
            if (staged != null) {
                deleteQuietly(staged);
            }
        }

        /**
         * Removes a staged copy without letting the removal fail a completion callback.
         *
         * @param staging the copy to remove
         */
        private static void deleteQuietly(Path staging) {
            try {
                Files.deleteIfExists(staging);
            } catch (IOException | RuntimeException failure) {
                Debug.logWarning("A staged content copy could not be removed from the publication staging area:"
                        + " " + failure.getClass().getName(), MODULE);
            }
        }
    }

    /**
     * Creates the private file one transaction's captured bytes are held in.
     *
     * <p>Under {@code runtime/tmp}, which is this deployment's own scratch area, in a directory of this
     * mechanism's own so that nothing else writes there and an operator can see what it holds. Created
     * {@code CREATE_NEW} under a generated name, and owner-only: the bytes are content, and a copy of content
     * is protected exactly as the content is.
     *
     * @return the staging file, empty and open to nothing
     * @throws IOException if it cannot be created
     */
    private static Path createPublicationStagingFile() throws IOException {
        Path directory = Paths.get(System.getProperty("ofbiz.home", "."), PUBLICATION_STAGING_DIRECTORY)
                .toAbsolutePath();
        createOwnerOnlyDirectories(directory);
        for (int attempt = 0; attempt < PUBLICATION_STAGING_ATTEMPTS; attempt++) {
            Path candidate = directory.resolve(PUBLICATION_STAGING_PREFIX
                    + Long.toHexString(ThreadLocalRandom.current().nextLong()) + ".tmp");
            try {
                Files.newByteChannel(candidate, EnumSet.of(StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE), ownerOnlyFileAttributes()).close();
                return candidate;
            } catch (FileAlreadyExistsException taken) {
                Debug.logVerbose(taken, "A publication staging name was already taken, so another is tried",
                        MODULE);
            }
        }
        throw new IOException("No unused publication staging name could be found after "
                + PUBLICATION_STAGING_ATTEMPTS + " attempts");
    }

    /**
     * Resolves the one content-storage provider that serves a single read operation.
     *
     * <p>Resolved once per operation and carried through it, so every branch and every helper of that operation
     * sees the same provider even if the configuration is changed while it runs, and so one operation can never
     * be served half from one provider and half from another.
     *
     * <p>A caller that brings neither a delegator nor a resource gets no provider. Not because no key could be
     * derived for it - a key comes from the location, which every caller has - but because that pair of nulls is
     * what the frozen four-argument {@link #renderFile} signature passes, and that signature has to behave
     * exactly as it did before this work. Every read that does reach a provider therefore comes from the render
     * path or the stream path, both of which hold the {@code DataResource} they are reading.
     *
     * @param delegator the delegator the provider and its tunables are resolved against
     * @param dataResourceId the identifier of the resource being read
     * @return the active provider, or {@code null} when content is held in the {@code DataResource} database
     *     columns - the committed default - or when the caller is the frozen delegator-less signature
     * @throws GeneralException if a provider is selected but its configuration is incomplete or unusable, which
     *     is reported rather than hidden because quietly reading local files would mask a broken deployment
     */
    private static ContentStore storeForResource(Delegator delegator, String dataResourceId)
            throws GeneralException {
        if (delegator == null || UtilValidate.isEmpty(dataResourceId)) {
            return null;
        }
        return ContentStoreFactory.getContentStore(delegator);
    }

    /**
     * Returns the deployment root every file-backed location is resolved against, trimmed.
     *
     * <p>One accessor, because two of them is how a location came to be resolved under one root and classified
     * against another. Whitespace is trimmed because that is what a shell which expanded an unset variable into
     * a quoted argument leaves behind, and because the filesystem provider trims it too: a root that differs by
     * a space between the two would make a provider-backed resource look like host-local state.
     *
     * @return the trimmed {@code ofbiz.home} value, or "" when it is unset or blank
     */
    private static String deploymentHome() {
        String home = System.getProperty("ofbiz.home");
        return home == null ? "" : home.trim();
    }

    /**
     * Returns a resolved location's path relative to {@code ofbiz.home}, with {@code /} separators.
     *
     * <p>This is the path a path-keyed provider stores content at, and it is also the test of whether a location
     * is part of the deployment at all. It is computed from the resolved path rather than from the raw
     * {@code objectInfo} so that the same location produces the same answer whichever resource type named it.
     *
     * @param file the resolved, already authorised location
     * @return the relative path, or {@code null} when the location is {@code ofbiz.home} itself, lies outside it,
     *     or {@code ofbiz.home} is unset or blank
     */
    private static String deploymentRelativePath(File file) {
        // The one canonical value, which every location resolution in this class also resolves against, so
        // that "part of the deployment" and "where the file is" can never be measured from different roots.
        String configured = deploymentHome();
        if (UtilValidate.isEmpty(configured)) {
            return null;
        }
        Path root = Paths.get(configured).toAbsolutePath().normalize();
        Path resolved = file.toPath().toAbsolutePath().normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            return null;
        }
        String relative = root.relativize(resolved).toString().replace(File.separatorChar, '/');
        return UtilValidate.isEmpty(relative) ? null : relative;
    }

    /**
     * Copies the content the provider holds for a location straight into the supplied output.
     *
     * <p>The provider's stream is consumed as it is written, so no part of the content is held in the heap in
     * full and nothing is written to local disk, which is what the pre-refactor local read also did.
     *
     * @param store the provider serving this operation, or {@code null} for database storage
     * @param delegator the delegator the fallback setting is resolved through
     * @param dataResourceTypeId the {@code DataResource.dataResourceTypeId}, which decides whether this resource
     *     is provider-backed at all
     * @param file the resolved, already authorised location
     * @param out the output to render into
     * @param absentLocally whether the location holds nothing on this instance's disk
     * @return {@code true} when the content was rendered from the provider, {@code false} when the caller must
     *     perform the local read it has always performed
     * @throws GeneralException if the provider cannot be reached or refuses the key, or if it holds nothing and
     *     a local copy may not answer in its place
     * @throws IOException if the content cannot be read or the output cannot be written
     */
    private static boolean renderThrough(ContentStore store, Delegator delegator, String dataResourceTypeId,
            File file, Appendable out, boolean absentLocally) throws GeneralException, IOException {
        String key = providerKey(store, dataResourceTypeId, file);
        if (key == null) {
            return false;
        }
        ContentStore.ContentStream stored;
        try {
            // Opening is kept apart from copying so that absence, which is an answer, is never confused with a
            // read failure part way through an output that has already been written to.
            stored = store.openStream(key);
        } catch (FileNotFoundException absent) {
            refuseUnlessLocalCopyMayAnswer(key, file, absentLocally, delegator, absent);
            return false;
        }
        try (InputStreamReader in = new InputStreamReader(stored, StandardCharsets.UTF_8)) {
            UtilIO.copy(in, out);
        }
        return true;
    }

    /**
     * Serves a stream consumer from the provider, together with the exact content length that
     * {@link #getDataResourceStream} must report.
     *
     * <p>The provider's own stream is handed on unread, and the length comes from the same open, so nothing is
     * materialised in the heap and nothing is written to local disk. That is what makes this path independent
     * of {@code content.store.max.object.size}: the bound exists to stop one whole-object read exhausting the
     * heap, and a stream never holds the object at all, so content of a size an uploader chose is served in
     * full rather than refused for being large.
     *
     * <p>Ownership of the stream passes to the consumer, exactly as it does on the local branch of this method,
     * which hands over a {@code FileInputStream}.
     *
     * @param store the provider serving this operation, or {@code null} for database storage
     * @param delegator the delegator the fallback setting is resolved through
     * @param dataResourceTypeId the {@code DataResource.dataResourceTypeId}, which decides whether this resource
     *     is provider-backed at all
     * @param file the resolved, already authorised location
     * @param absentLocally whether the location holds nothing on this instance's disk
     * @return the {@code stream} and {@code length} pair {@link #getDataResourceStream} returns, or {@code null}
     *     when the caller must perform the local read it has always performed
     * @throws GeneralException if the provider cannot be reached, refuses the key, or holds nothing while a
     *     local copy may not answer in its place
     * @throws IOException if the content cannot be opened
     */
    private static Map<String, Object> streamThrough(ContentStore store, Delegator delegator,
            String dataResourceTypeId, File file, boolean absentLocally) throws GeneralException, IOException {
        String key = providerKey(store, dataResourceTypeId, file);
        if (key == null) {
            return null;
        }
        ContentStore.ContentStream content;
        try {
            content = store.openStream(key);
        } catch (FileNotFoundException absent) {
            refuseUnlessLocalCopyMayAnswer(key, file, absentLocally, delegator, absent);
            return null;
        }
        // The stream is handed on unread and the length comes from the same open, so nothing is
        // materialised here and nothing is refused for its size. Ownership passes to the consumer,
        // which is what the local branch of this method has always done with a FileInputStream.
        return UtilMisc.toMap("stream", content, "length", content.length());
    }

    /**
     * Decides what a configured provider holding nothing for a resource means, and refuses to guess.
     *
     * <p>Three situations are distinguished, because they are not the same failure:
     *
     * <ul>
     * <li><strong>Nothing in the provider and nothing on disk.</strong> The content does not exist. No fallback
     * decision arises, and the caller reports absence exactly as it always has.</li>
     * <li><strong>Nothing in the provider but a local copy exists, with {@code
     * content.store.local.fallback} at its committed default.</strong> The local copy answers. Content written
     * since the provider was configured is published to it by the transaction that writes it, so this case is
     * content that predates the provider - shipped content, or an upload made before it was switched on - and refusing it
     * would make selecting a provider stop a deployment serving content it served the day before. It is
     * reported once per resource rather than once per read, because the report is about a resource that has yet
     * to be migrated and repeating it on every render says nothing new.</li>
     * <li><strong>The same, with {@code content.store.local.fallback} set false.</strong> The operator has asked
     * for the strict posture: the provider is the sole authority, so whatever is on this instance's disk is
     * either left over from before the provider was adopted or particular to this instance, and serving it would
     * let a stale or wrong document reach a user with nothing to show it had happened, differently on each
     * instance. It is refused.</li>
     * </ul>
     *
     * <p><strong>The refusal says nothing about where the content lives, and neither does the log.</strong> A
     * storage key is this deployment's own layout - the {@code ofbiz.home}-relative path of the content, under
     * whatever prefix the bucket is organised by - and this refusal travels back through the content-rendering
     * path, where it can reach a rendered page. The caller is given an opaque reference and the setting an
     * operator would change (CWE-200); the log line carries the same reference and
     * {@link ContentStore#logReference} in place of the key itself, because a centrally collected log is not a
     * place to re-publish customer file names either (CWE-532). The provider's own absence report is not
     * attached to the outward message for the first reason: {@code GeneralException.getMessage()} appends the
     * message of any cause it is given, so attaching it would put the path straight back into the text.
     *
     * <p><strong>The strict posture also ERASES the remnant, when the deployment asks it to.</strong> See
     * {@link #eraseLocalRemnant}: a local file the authoritative store does not hold cannot be served, and for a
     * deployment with an erasure obligation it must not go on existing either.
     * @param key the provider key that holds nothing
     * @param file the resolved location this instance holds a copy at, which the erasure below reaches
     * @param absentLocally whether the location holds nothing on this instance's disk either
     * @param delegator the delegator the fallback setting is resolved through
     * @param absent the provider's report of absence, kept for the log
     * @throws GeneralException when a local copy exists but may not answer for the provider
     */
    private static void refuseUnlessLocalCopyMayAnswer(String key, File file, boolean absentLocally,
            Delegator delegator, FileNotFoundException absent) throws GeneralException {
        if (absentLocally) {
            Debug.logVerbose(absent, "The configured content store holds nothing under "
                    + ContentStore.logReference(key) + " and this instance holds no copy either, so the content"
                    + " does not exist", MODULE);
            return;
        }
        if (!ContentStoreFactory.localFallbackEnabled(delegator)) {
            String reference = UUID.randomUUID().toString();
            Debug.logError(absent, "Content store refusal [" + reference + "]: the store holds no content under "
                    + ContentStore.logReference(key) + " while a local copy of it exists, and"
                    + " content.store.local.fallback is false, so the local copy is not served in its place."
                    + " Place the content in the store, or set content.store.local.fallback=true to allow local"
                    + " copies to answer while content is migrated into it.", MODULE);
            eraseLocalRemnant(key, file, delegator, reference);
            throw new GeneralException("The requested content is not available from this deployment's content"
                    + " store, and content.store.local.fallback does not allow a local copy to answer for it."
                    + " Reference [" + reference + "].");
        }
        if (REPORTED_FALLBACK_KEYS.size() < REPORTED_FALLBACK_KEY_LIMIT && REPORTED_FALLBACK_KEYS.add(key)) {
            Debug.logWarning(absent, "The configured content store holds nothing under "
                    + ContentStore.logReference(key) + ", so the local copy answers for it because"
                    + " content.store.local.fallback allows it. Content written since the store was configured is"
                    + " published to it by the transaction that writes it, so this content predates the store and"
                    + " belongs copied into it.", MODULE);
            return;
        }
        Debug.logVerbose(absent, "The configured content store holds nothing under "
                + ContentStore.logReference(key) + ", so the local copy answers for it because"
                + " content.store.local.fallback allows it", MODULE);
    }

    /**
     * Removes a local copy of content the authoritative store no longer holds, so that an authorised deletion
     * reaches every instance rather than only the one that performed it.
     *
     * <p><strong>The gap this closes.</strong> A deletion that commits removes the content from this
     * deployment's tree and, through {@code ContentWriteThrough}, from the store - but only on the instance that
     * performed it. Every other instance that had read the content through the store holds a reconstructed copy
     * of it at the same path, and nothing ever removed those. For a deployment carrying a retention or erasure
     * obligation that is a copy of a customer's document surviving the deletion that was supposed to erase it
     * (CWE-212, CWE-459), on as many instances as had served it, for as long as those instances live. The
     * refusal above stops such a copy being SERVED once the store is authoritative; this stops it EXISTING.
     *
     * <p><strong>Why it is a setting, and why it defaults to off.</strong> The evidence available here is "the
     * store does not hold this key", which is also what a mistyped bucket, a wrong
     * {@code content.store.s3.key.prefix} or a store that has not finished being loaded looks like - and
     * deleting local content on that evidence would destroy the deployment's only copy. So erasure is what a
     * deployment ASKS for, with {@code content.store.local.erase.on.store.miss=true}, once it has satisfied
     * itself that the store really is the whole of its content; until then a remnant is refused, reported and
     * left alone. Both halves are needed for erasure to be enforceable rather than a manual reconciliation:
     * the setting is read on every use, so turning it on takes effect across the fleet without a restart, and
     * every instance then erases each remnant the first time anything touches it.
     *
     * <p>A failure to delete is reported and swallowed. The caller's refusal follows either way - the content is
     * not served whether or not the file could be removed - and an exception here would replace a precise
     * refusal with a filesystem error.
     *
     * @param key the provider key the store holds nothing under, for the report
     * @param file the local copy to remove
     * @param delegator the delegator the erasure setting is resolved through
     * @param reference the reference the refusal was reported under, so both lines can be matched
     */
    private static void eraseLocalRemnant(String key, File file, Delegator delegator, String reference) {
        if (!ContentStoreFactory.localRemnantErasureEnabled(delegator)) {
            return;
        }
        try {
            // NOFOLLOW is not available on a delete - deleting a symbolic link deletes the link and never its
            // target - so the delete itself cannot be redirected. The location was authorised by the read that
            // reached here, and nothing but that location is removed.
            if (Files.deleteIfExists(file.toPath())) {
                Debug.logInfo("Content store erasure [" + reference + "]: the store holds nothing under "
                        + ContentStore.logReference(key) + ", so this instance's local copy of it was removed"
                        + " because " + ContentStoreFactory.LOCAL_REMNANT_ERASURE_PROPERTY + " is true. The"
                        + " authoritative store is the record of what exists.", MODULE);
            }
        } catch (IOException | RuntimeException failure) {
            Debug.logError(failure, "Content store erasure [" + reference + "]: this instance's local copy of "
                    + ContentStore.logReference(key) + " could not be removed, so a copy of content the store no"
                    + " longer holds survives on this instance and has to be removed by hand", MODULE);
        }
    }
}
