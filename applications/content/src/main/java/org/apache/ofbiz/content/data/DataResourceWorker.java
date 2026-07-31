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
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import javax.transaction.Status;
import javax.transaction.Synchronization;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import org.apache.commons.fileupload2.core.DiskFileItem;
import org.apache.commons.fileupload2.core.DiskFileItemFactory;
import org.apache.commons.fileupload2.core.FileItem;
import org.apache.commons.fileupload2.core.FileUploadException;
import org.apache.commons.fileupload2.jakarta.JakartaServletFileUpload;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.BoundedInputStream;
import org.apache.ofbiz.base.location.FlexibleLocation;
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.FileUtil;
import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.GeneralRuntimeException;
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
import org.apache.ofbiz.content.data.store.LocalContentStore;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.model.ModelReader;
import org.apache.ofbiz.entity.util.EntityQuery;
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
    private static final String ERR_RESOURCE = "ContentErrorUiLabels";
    private static final String PROPERTY_RESOURCE = "content";

    /** Stable log identifier for a read that was served from the configured content store. */
    private static final String EVENT_STORE_READ = "CONTENT-STORE-WORKER-READ";
    /** Stable log identifier for a local file that was published to the configured content store. */
    private static final String EVENT_STORE_PUBLISHED = "CONTENT-STORE-WORKER-PUBLISHED";
    /** Stable log identifier for an object that was removed from the configured content store. */
    private static final String EVENT_STORE_REMOVED = "CONTENT-STORE-WORKER-REMOVED";
    /** Stable log identifier for a synchronisation record that was dropped to keep the register bounded. */
    private static final String EVENT_STORE_REGISTER_BOUNDED = "CONTENT-STORE-WORKER-REGISTER-BOUNDED";

    /** File name prefix for the staging file a materialisation is written to before it is moved into place. */
    private static final String MATERIALISE_PREFIX = ".ofbiz-content-store-";
    /** File name suffix for that staging file. */
    private static final String MATERIALISE_SUFFIX = ".part";

    /**
     * Records, per storage key, the {@code length:lastModified} pair of the local working copy as this JVM last
     * synchronised it with the configured content store.
     *
     * <p>This is write-through bookkeeping, not durable state, and it is deliberately safe to lose. Its only
     * job is to answer one question at resolution time: has the local working copy been overwritten since we
     * last agreed with the store about it? A missing record therefore means "we do not know", and the
     * resolution rules below treat the store as authoritative in that case - which is the correct direction to
     * be wrong in for a fleet whose instances are freely replaceable, because it can only ever cost a
     * redundant transfer and can never lose content.
     *
     * <p>A stat pair rather than a digest is compared, so an ordinary read costs no file content at all. Every
     * writer that reaches the returned {@link File} does so through {@link java.io.FileOutputStream}, which
     * updates both members of the pair, so an overwrite is always detected.
     */
    private static final Map<String, String> STORE_SYNC_REGISTER = new ConcurrentHashMap<>();

    /**
     * Upper bound on {@link #STORE_SYNC_REGISTER}, so that a long-lived instance serving many distinct
     * resources cannot grow it without limit. Reaching the bound clears the register rather than evicting one
     * entry: forgetting is always safe (see above), and clearing keeps the accounting free of a per-entry
     * ordering structure.
     */
    private static final int STORE_SYNC_REGISTER_MAX = 20000;

    /**
     * The modification time a file handed to a caller is stamped with, so that a write through it is
     * detectable however fast it is and however many bytes it keeps.
     *
     * <p>One second past the epoch rather than zero, which some filesystems and tools read as "unknown"
     * rather than as a time. No write can reproduce it: a write stamps the file with the time of the write.
     * {@code ContentStoreFactory} stamps the files it stages for the same reason.
     */
    private static final long RESOLVED_SENTINEL_MODIFIED = 1000L;

    /** Stable code reported when a write made through a resolved file is published as the transaction commits. */
    private static final String EVENT_STORE_COMMITTED = "CONTENT-STORE-WORKER-COMMITTED";

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
     * Uses a dual-check strategy to support EFS/Docker mount points:
     * 1. Canonical paths (resolves symlinks on both sides) — works for non-mounted paths.
     * 2. Normalized absolute paths (collapses ".." without following symlinks) — fallback for
     *    when contextRoot or a subdirectory inside it is a mount point, causing canonical paths
     *    to diverge. Path traversal via ".." is still blocked by the normalization step.
     */
    static void checkContextFileBoundary(File file, String contextRoot) throws GeneralException {
        try {
            String canonicalAllowed = new File(contextRoot).getCanonicalPath();
            String canonicalFilePath = file.getCanonicalPath();
            boolean passesCanonical = canonicalFilePath.startsWith(canonicalAllowed + File.separator)
                    || canonicalFilePath.equals(canonicalAllowed);

            Path normalizedAllowed = Path.of(contextRoot).toAbsolutePath().normalize();
            Path normalizedFilePath = file.toPath().toAbsolutePath().normalize();
            boolean passesNormalized = normalizedFilePath.startsWith(normalizedAllowed);

            if (!passesCanonical && !passesNormalized) {
                throw new GeneralException("Access to file denied: path resolves outside of the allowed directory");
            }
        } catch (IOException e) {
            throw new GeneralException("Unable to validate file path: " + e.getMessage());
        }
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
    private static void checkUrlResourceAllowed(URL url) throws GeneralException {
        // 1. Protocol: only http and https are permitted
        String protocol = url.getProtocol();
        if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
            throw new GeneralException("URL_RESOURCE only supports http/https protocols; rejected: " + protocol);
        }
        String host = url.getHost();
        if (UtilValidate.isEmpty(host)) {
            throw new GeneralException("URL_RESOURCE URL has no host component");
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
                throw new GeneralException("URL_RESOURCE host is not in the allowed list: " + host);
            }
        }

        // 3. DNS resolution: block private/reserved IP ranges (SSRF / DNS-rebinding mitigation)
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new GeneralException("URL_RESOURCE host cannot be resolved: " + host);
        }
        if (addresses == null || addresses.length == 0) {
            throw new GeneralException("URL_RESOURCE host resolved to no addresses: " + host);
        }
        for (InetAddress addr : addresses) {
            checkNotPrivateOrReservedAddress(addr);
        }
    }

    /**
     * Throws {@link GeneralException} if {@code addr} belongs to a private, loopback,
     * link-local, multicast, or otherwise reserved IP range (IPv4 and IPv6).
     */
    private static void checkNotPrivateOrReservedAddress(InetAddress addr) throws GeneralException {
        if (addr.isLoopbackAddress()) {
            throw new GeneralException("URL_RESOURCE target resolves to a loopback address: " + addr.getHostAddress());
        }
        if (addr.isLinkLocalAddress()) {
            throw new GeneralException("URL_RESOURCE target resolves to a link-local address: " + addr.getHostAddress());
        }
        if (addr.isSiteLocalAddress()) {
            throw new GeneralException("URL_RESOURCE target resolves to a private (site-local) address: " + addr.getHostAddress());
        }
        if (addr.isAnyLocalAddress()) {
            throw new GeneralException("URL_RESOURCE target resolves to a wildcard address: " + addr.getHostAddress());
        }
        if (addr.isMulticastAddress()) {
            throw new GeneralException("URL_RESOURCE target resolves to a multicast address: " + addr.getHostAddress());
        }
        byte[] b = addr.getAddress();
        if (addr instanceof Inet4Address) {
            int i0 = b[0] & 0xFF;
            int i1 = b[1] & 0xFF;
            // 0.0.0.0/8 – "this" network (RFC 1122)
            if (i0 == 0) {
                throw new GeneralException("URL_RESOURCE target resolves to a reserved network address (0.0.0.0/8): " + addr.getHostAddress());
            }
            // 100.64.0.0/10 – shared address space / CGNAT (RFC 6598)
            if (i0 == 100 && i1 >= 64 && i1 <= 127) {
                throw new GeneralException("URL_RESOURCE target resolves to a shared address space (CGNAT, 100.64.0.0/10): " + addr.getHostAddress());
            }
            // 192.0.0.0/24 – IETF protocol assignments (RFC 6890)
            if (i0 == 192 && i1 == 0 && (b[2] & 0xFF) == 0) {
                throw new GeneralException("URL_RESOURCE target resolves to an IETF reserved address (192.0.0.0/24): " + addr.getHostAddress());
            }
            // 198.18.0.0/15 – network benchmarking (RFC 2544)
            if (i0 == 198 && (i1 == 18 || i1 == 19)) {
                throw new GeneralException("URL_RESOURCE target resolves to a benchmarking address (198.18.0.0/15): " + addr.getHostAddress());
            }
            // 240.0.0.0/4 – reserved for future use (RFC 1112)
            if ((i0 & 0xF0) == 240) {
                throw new GeneralException("URL_RESOURCE target resolves to a reserved address (240.0.0.0/4): " + addr.getHostAddress());
            }
        } else if (addr instanceof Inet6Address) {
            // fc00::/7 – Unique Local Addresses (ULA), private IPv6 (RFC 4193)
            if ((b[0] & 0xFE) == 0xFC) {
                throw new GeneralException("URL_RESOURCE target resolves to a unique-local (private) IPv6 address: " + addr.getHostAddress());
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
                    throw new GeneralException("URL_RESOURCE target contains an invalid IPv4-mapped IPv6 address");
                }
            }
        }
    }

    /**
     * Resolves the local file a file-backed {@code DataResource} is carried by, reconciling it with the
     * configured content store when one is configured.
     *
     * <p>The signature, the return type and every existing failure mode are unchanged, and so is the whole
     * resolution when no content store is configured - which is the committed default. The
     * reconciliation is layered on top rather than replacing
     * anything: the path is still computed the same way, and every existing allow-list and boundary check
     * still runs against it, so no deployment can reach a location through the store that it could not reach
     * before.
     *
     * <p>When a provider IS configured, the resolution becomes a write-through/read-through reconciliation
     * between the local working copy and the store:
     * <ul>
     *   <li>if the local copy has been overwritten since this JVM last agreed with the store about it, the
     *       local copy is <strong>published</strong>. This is what carries a write performed through the
     *       returned {@link File} - which is how the binary-file content services write - into the store;</li>
     *   <li>otherwise the store is authoritative: an object that exists is <strong>materialised</strong> over
     *       the local copy, so an instance that has never seen this resource before can still serve it;</li>
     *   <li>a local file that the store does not have yet is published, which is what lets a deployment that
     *       already holds content on disk adopt object storage without a migration step;</li>
     *   <li>if neither the store nor the local filesystem has the content, the original
     *       {@link FileNotFoundException} is raised, with the same message as before.</li>
     * </ul>
     *
     * <p><strong>A write made through the returned file is published by the transaction that made it.</strong>
     * The reconciliation above can only see what is on disk at the moment the location is resolved, and the
     * frozen binary-content services write <em>afterwards</em>: {@code DataServices.createBinaryFileMethod} and
     * {@code updateBinaryFileMethod} take the {@link File} returned here and open a {@code FileOutputStream} on
     * it. Reconciling again at the next resolution would catch that write only on the instance that made it,
     * and only if a later resolution happened at all - another instance has no local copy, finds the object
     * unchanged in the store, and serves the content the write was meant to replace, so the write is lost.
     * A publication is therefore registered with the surrounding transaction for every file handed out, and it
     * publishes the file if, and only if, it changed. A caller that only reads leaves the file alone and
     * nothing is published, so a read still costs nothing; a caller that writes has its bytes in the store
     * before the transaction commits, and a store that refuses the write rolls the transaction back rather than
     * committing a {@code DataResource} row whose content was never stored. When there is no transaction at
     * all, nothing is registered and the reconciliation above remains the mechanism, exactly as before.
     *
     * @param dataResourceTypeId the {@code DataResource} type, which selects how the path is resolved
     * @param objectInfo the type-relative location recorded on the {@code DataResource}
     * @param contextRoot the webapp context root, required for the {@code CONTEXT_FILE} types
     * @return the local file carrying the content, never null for a file-backed type
     * @throws GeneralException if the location is unusable, is refused by an allow list, or the configured
     *     content store cannot be reached
     * @throws FileNotFoundException if neither the content store nor the local filesystem holds the content
     */
    public static File getContentFile(String dataResourceTypeId, String objectInfo, String contextRoot)
            throws GeneralException, FileNotFoundException {
        // Content storage seam. The resolution below is the pre-existing one, with the configured provider
        // reconciled against the local working copy inside each branch: when a non-database provider is
        // configured the bytes live in that provider rather than only on this instance's disk, they are
        // materialised as a local file here, and whatever the caller writes through that file is published
        // back. That is what makes the write callers store their content without any change to their own
        // code - DataServices.createBinaryFileMethod and updateBinaryFileMethod open a FileOutputStream on
        // exactly this file. In the default database mode the factory answers null before anything is
        // derived, so every line below runs exactly as it always has.
        //
        // ContentStoreFactory.materialiseContentFile is the bridge's transaction-scoped form of the same
        // read. The reconciliation here is what this method uses instead, for two reasons: it also carries
        // the write direction, so a caller that writes through the returned File with no transaction to hang
        // a publication on is still published on the next resolution; and it leaves every allow-list and
        // boundary check in the position it has always occupied rather than moving them behind an early
        // return.
        File file = null;
        // Resolved once, before any path is touched, so that the checks below keep running in exactly the order
        // they always have: the store only ever widens the "is the content available?" verdict, and every
        // boundary and allow-list check still gates the reconciliation that follows it.
        ContentStore store = configuredContentStore();
        String key = store == null ? null : contentStoreKey(dataResourceTypeId, objectInfo, contextRoot);

        if ("LOCAL_FILE".equals(dataResourceTypeId) || "LOCAL_FILE_BIN".equals(dataResourceTypeId)) {
            file = FileUtil.getFile(objectInfo);
            if (!contentAvailable(store, key, file)) {
                throw new FileNotFoundException("No file found: " + (objectInfo));
            }
            if (!file.isAbsolute()) {
                throw new GeneralException("File (" + objectInfo + ") is not absolute");
            }
            SecurityUtil.checkLocalFileAllowList(file);
            reconcileWithContentStore(store, key, file);
            publishOnCommitIfWrittenThrough(store, key, file);
        } else if ("OFBIZ_FILE".equals(dataResourceTypeId) || "OFBIZ_FILE_BIN".equals(dataResourceTypeId)) {
            String prefix = System.getProperty("ofbiz.home");

            String sep = "";
            if (objectInfo.indexOf('/') != 0 && prefix.lastIndexOf('/') != (prefix.length() - 1)) {
                sep = "/";
            }
            file = FileUtil.getFile(prefix + sep + objectInfo);
            if (!contentAvailable(store, key, file)) {
                throw new FileNotFoundException("No file found: " + (prefix + sep + objectInfo));
            }
            SecurityUtil.checkOfbizFileAllowList(file);
            reconcileWithContentStore(store, key, file);
            publishOnCommitIfWrittenThrough(store, key, file);
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
            if (!contentAvailable(store, key, file)) {
                throw new FileNotFoundException("No file found: " + (contextRoot + sep + objectInfo));
            }
            reconcileWithContentStore(store, key, file);
            publishOnCommitIfWrittenThrough(store, key, file);
        }

        return file;
    }

    /**
     * Publishes the content of a local file into the configured content store, so that content written through
     * the local filesystem becomes durable for the whole fleet rather than for one instance.
     *
     * <p>This is the explicit write half of the store seam, for a caller that has just produced content and
     * does not want to wait for the next resolution to reconcile it. It is a no-op that reports {@code false}
     * when no content store is configured, so a caller can invoke it unconditionally: in the committed default
     * the {@code DataResource} database storage remains the only place content lives, exactly as before.
     *
     * @param dataResourceTypeId the {@code DataResource} type, which selects how the storage key is derived
     * @param objectInfo the type-relative location recorded on the {@code DataResource}
     * @param contextRoot the webapp context root, required for the {@code CONTEXT_FILE} types
     * @param source the local file whose content is to be published; must exist
     * @return {@code true} if the content was published, {@code false} if no content store is configured
     * @throws GeneralException if the type is not file-backed, the key cannot be derived, or the store refuses
     *     the content
     * @throws IOException if the local file cannot be read or the store cannot be written to
     */
    public static boolean storeContentFile(String dataResourceTypeId, String objectInfo, String contextRoot, File source)
            throws GeneralException, IOException {
        ContentStore store = configuredContentStore();
        if (store == null) {
            return false;
        }
        if (source == null) {
            throw new GeneralException("Cannot publish a null file for dataResourceTypeId [" + dataResourceTypeId + "]");
        }
        String key = contentStoreKey(dataResourceTypeId, objectInfo, contextRoot);
        publish(store, key, source);
        return true;
    }

    /**
     * Removes the object a file-backed {@code DataResource} is carried by from the configured content store.
     *
     * <p>Idempotent, because the underlying providers are: removing content that is not there is a successful
     * no-op, so replayed clean-up is safe. As with {@link #storeContentFile}, this reports {@code false}
     * without touching anything when no content store is configured.
     *
     * @param dataResourceTypeId the {@code DataResource} type, which selects how the storage key is derived
     * @param objectInfo the type-relative location recorded on the {@code DataResource}
     * @param contextRoot the webapp context root, required for the {@code CONTEXT_FILE} types
     * @return {@code true} if the removal was issued, {@code false} if no content store is configured
     * @throws GeneralException if the type is not file-backed or the key cannot be derived
     * @throws IOException if the store cannot be modified
     */
    public static boolean removeContentFile(String dataResourceTypeId, String objectInfo, String contextRoot)
            throws GeneralException, IOException {
        ContentStore store = configuredContentStore();
        if (store == null) {
            return false;
        }
        String key = contentStoreKey(dataResourceTypeId, objectInfo, contextRoot);
        store.delete(key);
        STORE_SYNC_REGISTER.remove(key);
        Debug.logVerbose(EVENT_STORE_REMOVED + " " + ContentStoreFactory.reference(key), MODULE);
        return true;
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

    /**
     * Returns the location a new upload should be placed in, taken from the configured content store when one
     * supplies an upload location and computed exactly as before otherwise.
     *
     * @param absolute whether the absolute path is wanted rather than the deployment-relative one
     * @return the upload location, in the requested form
     */
    public static String getDataResourceContentUploadPath(boolean absolute) {
        String provided = contentStoreUploadPath(null, absolute);
        if (provided != null) {
            return provided;
        }
        String initialPath = UtilProperties.getPropertyValue("content", "content.upload.path.prefix");
        double maxFiles = UtilProperties.getPropertyNumber("content", "content.upload.max.files");
        if (maxFiles < 1) {
            maxFiles = 250;
        }

        return getDataResourceContentUploadPath(initialPath, maxFiles, absolute);
    }

    /**
     * Returns the location a new upload should be placed in, resolving deployment configuration through the
     * supplied delegator.
     *
     * <p>The delegator is passed on to the content store, so a deployment that keeps its storage configuration
     * in {@code SystemProperty} rather than in {@code content.properties} is honoured here too, exactly as it
     * already is for the upload path prefix.
     *
     * @param delegator the delegator to resolve deployment configuration through
     * @param absolute whether the absolute path is wanted rather than the deployment-relative one
     * @return the upload location, in the requested form
     */
    public static String getDataResourceContentUploadPath(Delegator delegator, boolean absolute) {
        String provided = contentStoreUploadPath(delegator, absolute);
        if (provided != null) {
            return provided;
        }
        String initialPath = EntityUtilProperties.getPropertyValue("content", "content.upload.path.prefix", delegator);
        double maxFiles = UtilProperties.getPropertyNumber("content", "content.upload.max.files");
        if (maxFiles < 1) {
            maxFiles = 250;
        }

        return getDataResourceContentUploadPath(initialPath, maxFiles, absolute);
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
        // Content storage seam. This is the one implementation every upload-path overload delegates
        // to, so hooking it here - rather than each overload - covers the delegator-aware caller in
        // DataServicesScript as well as the property-driven ones, with the resolved prefix already
        // passed in. With a non-database provider configured the upload is staged in a directory of
        // its own and everything written there is published when the transaction commits, which
        // captures the writers that never consult this package: DataServices.createFileMethod and
        // updateFileMethod build their target with new File(objectInfo), and objectInfo is always
        // derived from the path returned here. The maxFiles fan-out has nothing to do in that mode,
        // because an object store has neither directories nor a per-directory limit. In the default
        // database mode the factory answers null and the local allocation below runs unchanged.
        String staged;
        try {
            staged = ContentStoreFactory.uploadStagingPath(initialPath, absolute);
        } catch (GeneralException e) {
            throw uploadLocationRefused(e);
        }
        if (staged != null) {
            return staged;
        }

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
                checkUrlResourceAllowed(url);
                int connectTimeout = (int) UtilProperties.getPropertyNumber("security",
                        "content.data.url.resource.connect.timeout", 10000.0);
                int readTimeout = (int) UtilProperties.getPropertyNumber("security",
                        "content.data.url.resource.read.timeout", 30000.0);
                long maxResponseSize = (long) UtilProperties.getPropertyNumber("security",
                        "content.data.url.resource.max.response.size", (double) (10L * 1024 * 1024));
                URLConnection con = url.openConnection();
                con.setConnectTimeout(connectTimeout);
                con.setReadTimeout(readTimeout);
                // Disable automatic redirect-following to prevent SSRF bypass via redirect to private addresses
                if (con instanceof HttpURLConnection) ((HttpURLConnection) con).setInstanceFollowRedirects(false);
                con.connect();
                // Reject redirects outright; we cannot safely re-validate an arbitrary Location header
                if (con instanceof HttpURLConnection) {
                    HttpURLConnection httpCon = (HttpURLConnection) con;
                    int responseCode = httpCon.getResponseCode();
                    if (responseCode >= 300 && responseCode < 400) {
                        httpCon.disconnect();
                        throw new GeneralException("URL_RESOURCE request returned a redirect (" + responseCode
                                + "); redirects are not followed for security reasons");
                    }
                }
                try (InputStream limitedIn = BoundedInputStream.builder()
                        .setInputStream(con.getInputStream())
                        .setMaxCount(maxResponseSize)
                        .get()) {
                    text = IOUtils.toString(limitedIn, StandardCharsets.UTF_8);
                }
            } else {
                String prefix = DataResourceWorker.buildRequestPrefix(delegator, locale, webSiteId, https);
                String sep = "";
                if (url.toString().indexOf('/') != 0 && prefix.lastIndexOf('/') != (prefix.length() - 1)) {
                    sep = "/";
                }
                String fixedUrlStr = prefix + sep + url.toString();
                URL fixedUrl = UtilURL.fromUrlString(fixedUrlStr);
                text = (String) fixedUrl.getContent();
            }
            out.append(text);

        // file types
        } else if (dataResourceTypeId.endsWith("_FILE_BIN")) {
            writeText(dataResource, dataResourceId, templateContext, mimeTypeId, locale, out);
        } else if (dataResourceTypeId.endsWith("_FILE")) {
            String dataResourceMimeTypeId = dataResource.getString("mimeTypeId");
            String objectInfo = dataResource.getString("objectInfo");

            if (dataResourceMimeTypeId == null || dataResourceMimeTypeId.startsWith("text")) {
                renderFile(dataResourceTypeId, objectInfo, rootDir, out);
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

    /**
     * Renders a file-backed {@code DataResource} into the supplied output, reading it from the configured
     * content store when one is configured and holds it.
     *
     * <p>The store is read as a stream rather than as a whole value, so the content crosses this method
     * without ever being held in the heap in full - which matters here because the same method serves a
     * template of a few hundred bytes and an uploaded document of arbitrary size. The stream is opened and
     * closed by this method, because it is this method that consumes it; nothing about the stream escapes.
     *
     * <p>When no content store is configured, or the store does not hold this resource, the resolution falls
     * back to the local filesystem exactly as before, with the same allow-list and boundary checks.
     *
     * @param dataResourceTypeId the {@code DataResource} type, which selects how the location is resolved
     * @param objectInfo the type-relative location recorded on the {@code DataResource}
     * @param rootDir the webapp context root, required for the {@code CONTEXT_FILE} type
     * @param out the output to render into
     * @throws GeneralException if the location is unusable, is refused by an allow list, or the configured
     *     content store cannot be reached
     * @throws IOException if the content cannot be read or written
     */
    public static void renderFile(String dataResourceTypeId, String objectInfo, String rootDir, Appendable out) throws GeneralException, IOException {
        // TODO: this method assumes the file is a text file, if it is an image we should respond differently,
        //  see the comment above for IMAGE_OBJECT type data RESOURCE

        // Content storage seam. This method only copies bytes through to the caller's Appendable, so a
        // stream is all it needs and nothing is ever published from here. The helper's type guard repeats
        // exactly the three types resolved below - deliberately not the _BIN types, which this method has
        // never handled - so an unhandled type still writes nothing at all rather than being streamed as
        // text. In the default database mode no provider is consulted and the local resolution below runs
        // exactly as it always has.
        if (renderFromContentStore(dataResourceTypeId, objectInfo, rootDir, out)) {
            return;
        }

        if ("LOCAL_FILE".equals(dataResourceTypeId) && UtilValidate.isNotEmpty(objectInfo)) {
            File file = FileUtil.getFile(objectInfo);
            if (!file.isAbsolute()) {
                throw new GeneralException("File (" + objectInfo + ") is not absolute");
            }
            if (!file.exists()) {
                throw new FileNotFoundException("No file found: " + file.getAbsolutePath());
            }
            SecurityUtil.checkLocalFileAllowList(file);
            try (InputStreamReader in = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                UtilIO.copy(in, out);
            }
        } else if ("OFBIZ_FILE".equals(dataResourceTypeId) && UtilValidate.isNotEmpty(objectInfo)) {
            String prefix = System.getProperty("ofbiz.home");
            String sep = "";
            if (objectInfo.indexOf('/') != 0 && prefix.lastIndexOf('/') != (prefix.length() - 1)) {
                sep = "/";
            }
            File file = FileUtil.getFile(prefix + sep + objectInfo);
            if (!file.exists()) {
                throw new FileNotFoundException("No file found: " + file.getAbsolutePath());
            }
            SecurityUtil.checkOfbizFileAllowList(file);
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
                // Served straight from the configured content store when one holds it, so that a large upload is
                // streamed rather than materialised locally first. Ownership of the returned stream passes to the
                // caller either way, exactly as it always has, and the caller cannot tell the two apart.
                Map<String, Object> fromStore = streamFromContentStore(dataResourceTypeId, objectInfo, contextRoot);
                if (fromStore != null) {
                    return fromStore;
                }
                File file = DataResourceWorker.getContentFile(dataResourceTypeId, objectInfo, contextRoot);
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

                // SSRF prevention: validate protocol, optional host allow-list, and resolved IP ranges
                checkUrlResourceAllowed(url);

                int connectTimeout = (int) UtilProperties.getPropertyNumber("security",
                        "content.data.url.resource.connect.timeout", 10000.0);
                int readTimeout = (int) UtilProperties.getPropertyNumber("security",
                        "content.data.url.resource.read.timeout", 30000.0);
                long maxResponseSize = (long) UtilProperties.getPropertyNumber("security",
                        "content.data.url.resource.max.response.size", (double) (10L * 1024 * 1024));

                URLConnection con = url.openConnection();
                con.setConnectTimeout(connectTimeout);
                con.setReadTimeout(readTimeout);
                // Disable automatic redirect-following to prevent SSRF bypass via redirect to private addresses
                if (con instanceof HttpURLConnection) ((HttpURLConnection) con).setInstanceFollowRedirects(false);
                con.connect();

                // Reject redirects outright; we cannot safely re-validate an arbitrary Location header
                if (con instanceof HttpURLConnection) {
                    HttpURLConnection httpCon = (HttpURLConnection) con;
                    int responseCode = httpCon.getResponseCode();
                    if (responseCode >= 300 && responseCode < 400) {
                        httpCon.disconnect();
                        throw new GeneralException("URL_RESOURCE request returned a redirect (" + responseCode
                                + "); redirects are not followed for security reasons");
                    }
                }

                long contentLength = con.getContentLengthLong();
                if (contentLength > maxResponseSize) {
                    if (con instanceof HttpURLConnection) ((HttpURLConnection) con).disconnect();
                    throw new GeneralException("URL_RESOURCE response Content-Length (" + contentLength
                            + " bytes) exceeds the configured maximum of " + maxResponseSize + " bytes");
                }

                // Wrap with a bounded stream to enforce the size cap regardless of the Content-Length header
                InputStream limitedStream = BoundedInputStream.builder()
                        .setInputStream(con.getInputStream())
                        .setMaxCount(maxResponseSize)
                        .get();
                return UtilMisc.toMap("stream", limitedStream, "length", contentLength);
            }
            throw new GeneralException("No objectInfo found for URL_RESOURCE type; cannot stream");
        }

        // unsupported type
        throw new GeneralException("The dataResourceTypeId [" + dataResourceTypeId + "] is not supported in getDataResourceStream");
    }

    public static ByteBuffer getContentAsByteBuffer(Delegator delegator, String dataResourceId, String https, String webSiteId, Locale locale,
                                                    String rootDir) throws IOException, GeneralException {
        GenericValue dataResource = EntityQuery.use(delegator).from("DataResource").where("dataResourceId", dataResourceId).queryOne();
        Map<String, Object> resourceData = DataResourceWorker.getDataResourceStream(dataResource, https, webSiteId, locale, rootDir, false);
        InputStream stream = (InputStream) resourceData.get("stream");
        ByteBuffer byteBuffer = ByteBuffer.wrap(IOUtils.toByteArray(stream));
        return byteBuffer;
    }

    @Override
    public String renderDataResourceAsTextExt(Delegator delegator, String dataResourceId, Map<String, Object> templateContext,
            Locale locale, String targetMimeTypeId, boolean cache) throws GeneralException, IOException {
        return renderDataResourceAsText(null, delegator, dataResourceId, templateContext, locale, targetMimeTypeId, cache);
    }

    // Content store seam
    //
    // The whole integration with the object-storage providers lives below. It is deliberately confined to
    // this one region, and every method in it returns "nothing to do" when no provider is configured, so the
    // committed default - DataResource database storage, with file-backed resources on the local filesystem -
    // reaches none of it.

    /**
     * Returns the configured content store, or null when the deployment stores content in the database.
     *
     * <p>Failing to resolve a provider is deliberately not softened into "no provider": a deployment that
     * asked for object storage and cannot have it must be told, rather than quietly writing content to a local
     * disk that the next request may not land on.
     *
     * @return the configured provider, or null when content is stored in the database
     * @throws GeneralException if a provider was configured but cannot be used
     */
    private static ContentStore configuredContentStore() throws GeneralException {
        return ContentStoreFactory.getContentStore();
    }

    /**
     * Derives the storage key a file-backed {@code DataResource} is addressed by.
     *
     * <p>The derivation itself belongs to {@link ContentStoreFactory#storageKeyFor(String, String, String)},
     * which owns the key space for the whole package: the key is the resource's location expressed relative to
     * whatever root its type implies - the deployment home for {@code LOCAL_FILE} and {@code OFBIZ_FILE}, and
     * the context root's own identity for {@code CONTEXT_FILE}, which a single location could not otherwise
     * distinguish between two webapps. Deriving it from {@code objectInfo} rather than inventing an identifier
     * is what keeps the {@code DataResource} entity model untouched: the same rows address the same content
     * whether the deployment stores it on a disk or in a bucket, so switching a deployment over is a
     * configuration change and not a migration. The roots the local path is built from are per-instance
     * paths, so they are deliberately not part of the key - two instances unpacked at different paths must
     * address one object, not two.
     *
     * <p>This method delegates rather than repeating those rules, because the key a write publishes under has
     * to be the key a later read derives, and two independent derivations cannot guarantee that.
     *
     * @param dataResourceTypeId the {@code DataResource} type
     * @param objectInfo the type-relative location recorded on the {@code DataResource}
     * @param contextRoot the webapp context root, required for the {@code CONTEXT_FILE} types
     * @return the storage key, never null or empty
     * @throws GeneralException if the type is not one of the six file-backed types, if the location is empty,
     *     if it traverses above its root, or if a {@code CONTEXT_FILE} arrives without a context root
     */
    private static String contentStoreKey(String dataResourceTypeId, String objectInfo, String contextRoot) throws GeneralException {
        return ContentStoreFactory.storageKeyFor(dataResourceTypeId, objectInfo, contextRoot);
    }

    /**
     * Reports whether a {@code DataResource} type is one this class renders as text.
     *
     * <p>The three {@code _BIN} companions are excluded on purpose:
     * {@link #renderFile(String, String, String, Appendable)} has never handled them, so the store seam in
     * front of it must not start handling them either.
     *
     * @param dataResourceTypeId the type to classify
     * @return {@code true} for the three rendered file types
     */
    private static boolean isRenderedFileType(String dataResourceTypeId) {
        return "LOCAL_FILE".equals(dataResourceTypeId) || "OFBIZ_FILE".equals(dataResourceTypeId)
                || "CONTEXT_FILE".equals(dataResourceTypeId);
    }

    /**
     * Reports whether the content is available at all, from either side of the seam.
     *
     * <p>This exists so that the existing existence check keeps its exact position in
     * {@link #getContentFile(String, String, String)} while becoming aware of the store. In the committed
     * default it is precisely the {@code file.exists()} call it replaced.
     *
     * @param store the configured provider, or null when content is stored in the database
     * @param key the storage key, or null when there is no provider
     * @param file the local working copy
     * @return {@code true} if either the local copy or the stored object exists
     * @throws GeneralException if the provider cannot be interrogated
     */
    private static boolean contentAvailable(ContentStore store, String key, File file) throws GeneralException {
        if (file.exists()) {
            return true;
        }
        if (store == null) {
            return false;
        }
        try {
            return store.exists(key);
        } catch (IOException e) {
            // The failure is named by type rather than chained: GeneralException.getMessage() composes a
            // nested message into its own, and a provider's or the operating system's text is not this
            // package's to republish.
            throw new GeneralException("The configured content store could not be interrogated for "
                    + ContentStoreFactory.reference(key) + ": " + e.getClass().getSimpleName());
        }
    }

    /**
     * Brings the local working copy and the stored object into agreement, in whichever direction is correct.
     *
     * <p>Direction is decided by one question: has this JVM overwritten the local copy since it last agreed
     * with the store about it? If it has, the local copy is the newer content and is published - this is what
     * carries a write made through the {@link File} returned by
     * {@link #getContentFile(String, String, String)} into the store. If it has not, the store is
     * authoritative and the local copy is refreshed from it, which is what lets any instance serve content
     * another instance wrote.
     *
     * <p>A local file the store has never held is published rather than deleted, so a deployment that already
     * has content on disk adopts object storage on first use instead of losing it.
     *
     * <p>An unmodified local copy is refreshed from the store on <strong>every</strong> resolution rather than
     * being trusted because it was fetched before. There is no invalidation channel for file-backed content, so
     * a copy trusted on the strength of an earlier fetch is a copy that goes stale the moment another instance
     * writes: that instance's content would be invisible here for the life of the JVM, and which version a
     * customer saw would depend on which instance the load balancer picked. The cost is one store read per
     * resolution, which is the same cost the render seam already pays by streaming every render straight out of
     * the store, and it is what makes a fleet's instances interchangeable.
     *
     * @param store the configured provider, or null when content is stored in the database
     * @param key the storage key, or null when there is no provider
     * @param file the local working copy
     * @throws GeneralException if the provider cannot be reached or the local copy cannot be reconciled
     */
    private static void reconcileWithContentStore(ContentStore store, String key, File file) throws GeneralException {
        if (store == null || backsTheSameFile(store, key, file)) {
            return;
        }
        try {
            boolean present = file.exists();
            if (present && isLocallyModified(key, file)) {
                publish(store, key, file);
                return;
            }
            if (store.exists(key)) {
                materialise(store, key, file);
                return;
            }
            if (present) {
                publish(store, key, file);
            }
        } catch (IOException e) {
            throw new GeneralException("The configured content store could not be reconciled for "
                    + ContentStoreFactory.reference(key) + ": " + e.getClass().getSimpleName());
        }
    }

    /**
     * Arranges for a write made through a resolved local file to be published when the transaction commits.
     *
     * <p>This is the durability half of {@link #getContentFile(String, String, String)}, and it exists because
     * that method hands out a {@link File} and cannot tell a reader from a writer. The frozen binary-content
     * services write through the returned file after it has been resolved, so nothing the resolution itself
     * observes can carry those bytes to the store. Registering here means the transaction that performed the
     * write is the transaction that publishes it - not a later resolution on the same instance, which another
     * instance would never perform.
     *
     * <p>Nothing is registered in two cases, each of which would make the registration wrong rather than
     * merely redundant: in the committed database mode there is no provider and the file is not a copy of
     * anything, and for a provider that backs this very file, publishing would write the file onto itself.
     * A resource resolved repeatedly inside one transaction still costs one publication, because
     * {@link ContentStoreFactory#publishOnCommit(javax.transaction.Synchronization, String, String)} attaches
     * at most one per key to a given transaction.
     *
     * @param store the configured provider, or null when content is stored in the database
     * @param key the storage key, or null when there is no provider
     * @param file the local working copy that was just handed to the caller
     * @throws GeneralException if a transaction is in place but refuses the registration, or if the provider
     *     cannot be asked which file it backs
     */
    private static void publishOnCommitIfWrittenThrough(ContentStore store, String key, File file) throws GeneralException {
        if (store == null || backsTheSameFile(store, key, file)) {
            return;
        }
        ContentStoreFactory.publishOnCommit(new LocalWritePublication(key, file), key,
                "content resolved for key [" + key + "]");
    }

    /**
     * Publishes a local working copy at commit time, if and only if it changed after it was resolved.
     *
     * <p>Registered by {@link #publishOnCommitIfWrittenThrough(ContentStore, String, File)} for every file
     * {@link #getContentFile(String, String, String)} hands out while a provider is configured. It answers the
     * one question the resolution cannot: did the caller write through the file it was given?
     *
     * <p>Change is decided from the file's own attributes, read once at registration and once again at commit.
     * Comparing what was observed at each moment is not on its own enough: a write that preserves the byte
     * count and lands within one filesystem timestamp tick would be indistinguishable from no write at all,
     * and the timestamp granularity is a property of the filesystem rather than something this code can
     * assume. Registration therefore does not merely observe the timestamp, it <em>sets</em> it, to
     * {@link #RESOLVED_SENTINEL_MODIFIED}. Any write moves the timestamp to the time of the write, which is
     * never the sentinel, so detection does not depend on the granularity, on the size changing, or on how
     * quickly the caller writes. A file that was absent at registration and is present at commit is new
     * content and is always published.
     *
     * <p>If the timestamp cannot be set - a read-only filesystem, or a file this process does not own -
     * detection falls back to comparing the observed attributes. A caller that cannot set the modification
     * time of a file cannot write to it either, so the fallback covers a case that is degenerate by
     * construction, and it is never worse than comparing attributes alone.
     *
     * <p>Publication runs in {@code beforeCompletion}, so a store that refuses the write fails the commit
     * instead of leaving a {@code DataResource} row pointing at content that was never stored. It is reported
     * as an unchecked failure because the callback signature admits nothing else, which is the same contract
     * the storage bridge's own publications observe.
     *
     * <p>{@code afterCompletion} deliberately does <strong>not</strong> remove the local file. Unlike a staged
     * upload, this file is the caller's working copy at the location {@code DataResource.objectInfo} names: it
     * is a reconstructible cache of the stored object, removing it would force a fetch on every single read,
     * and for a provider whose content is on local disk it would delete the content itself.
     */
    private static final class LocalWritePublication implements Synchronization {

        private final String key;
        private final File file;
        private final boolean existed;
        private final long resolvedSize;
        private final FileTime resolvedModified;

        /**
         * Records how the file stood at the moment it was handed to the caller.
         *
         * @param key the storage key the file carries the content of
         * @param file the local working copy handed to the caller
         */
        private LocalWritePublication(String key, File file) {
            this.key = key;
            this.file = file;
            BasicFileAttributes attributes = attributesOrNull(file);
            this.existed = attributes != null;
            this.resolvedSize = attributes == null ? -1L : attributes.size();
            this.resolvedModified = attributes == null ? null : armChangeDetection(key, file, attributes);
        }

        @Override
        public void beforeCompletion() {
            BasicFileAttributes attributes = attributesOrNull(file);
            if (attributes == null || !attributes.isRegularFile()) {
                // The caller removed the file, or never created one. Content removal is not this
                // publication's business - removeContentFile is - so there is nothing to do.
                return;
            }
            if (existed && attributes.size() == resolvedSize
                    && attributes.lastModifiedTime().equals(resolvedModified)) {
                return;
            }
            try {
                publish(configuredContentStore(), key, file);
                Debug.logVerbose(EVENT_STORE_COMMITTED + " " + ContentStoreFactory.reference(key)
                        + " published as the transaction commits",
                        MODULE);
            } catch (GeneralException | IOException e) {
                // Named by type rather than chained. GeneralRuntimeException composes a nested failure's
                // message into its own, and the local working copy's absolute path is exactly what an
                // IOException raised while reading it would carry - the one value the opaque reference
                // above exists to withhold. The type, the reference and the provider's own log entry are
                // what a reader correlates.
                throw new GeneralRuntimeException("Cannot publish the content written for "
                        + ContentStoreFactory.reference(key) + ", so the transaction is rolled back: "
                        + e.getClass().getSimpleName());
            }
        }

        @Override
        public void afterCompletion(int status) {
            if (status != Status.STATUS_COMMITTED) {
                // The row that named this content did not commit, so the local copy no longer describes
                // anything the store is expected to hold. Forgetting the stamp costs at most one redundant
                // transfer on the next resolution and can never lose content.
                STORE_SYNC_REGISTER.remove(key);
            }
        }

        /**
         * Stamps the file with the sentinel modification time, so that any write through it is detectable.
         *
         * <p>The synchronisation register is brought along with the stamp, because it records the same
         * {@code length:lastModified} pair: leaving the recorded pair naming the timestamp this call has just
         * replaced would make the next resolution read an untouched file as locally modified and publish it
         * again. The register is only updated when it already holds this key, so arming never asserts an
         * agreement with the store that was not established by an actual transfer.
         *
         * @param key the storage key the file carries the content of
         * @param file the local working copy being handed to the caller
         * @param observed the attributes just read from the file
         * @return the modification time now in force, which is the sentinel when it could be set and the
         *     observed time when it could not
         */
        private static FileTime armChangeDetection(String key, File file, BasicFileAttributes observed) {
            if (!observed.isRegularFile()) {
                return observed.lastModifiedTime();
            }
            FileTime sentinel = FileTime.fromMillis(RESOLVED_SENTINEL_MODIFIED);
            if (sentinel.equals(observed.lastModifiedTime())) {
                return sentinel;
            }
            try {
                Files.setLastModifiedTime(file.toPath(), sentinel);
            } catch (IOException e) {
                Debug.logVerbose("The modification time of " + ContentStoreFactory.reference(file.getPath())
                        + " could not be set, so a write"
                        + " through it is detected by comparing its attributes: "
                        + e.getClass().getSimpleName(), MODULE);
                return observed.lastModifiedTime();
            }
            if (STORE_SYNC_REGISTER.containsKey(key)) {
                recordSynchronised(key, file);
            }
            return sentinel;
        }

        /**
         * Reads a file's attributes, answering null for a file that is not there or cannot be read.
         *
         * @param target the file to stat
         * @return its attributes, or null
         */
        private static BasicFileAttributes attributesOrNull(File target) {
            try {
                return Files.readAttributes(target.toPath(), BasicFileAttributes.class);
            } catch (IOException e) {
                return null;
            }
        }
    }

    /**
     * Reports whether the store's content for this key and the local working copy are literally the same file.
     *
     * <p>They are, for a provider that keeps content on the local filesystem, whenever a location resolves the
     * same way through both routes - which is the ordinary case for relative {@code OFBIZ_FILE} content.
     * Reconciling a file with itself would rewrite it on every single read, so this is checked first and
     * exactly, by asking the provider which file backs the key rather than by comparing sizes.
     *
     * @param store the configured provider
     * @param key the storage key
     * @param file the local working copy
     * @return {@code true} if there is nothing to reconcile because both sides name one file
     * @throws GeneralException if the provider refuses to resolve the key
     */
    private static boolean backsTheSameFile(ContentStore store, String key, File file) throws GeneralException {
        if (!(store instanceof LocalContentStore)) {
            return false;
        }
        Path backing = ((LocalContentStore) store).backingPath(key);
        return backing.equals(file.toPath().toAbsolutePath().normalize());
    }

    /**
     * Reports whether the local working copy has changed since this JVM last synchronised it with the store.
     *
     * @param key the storage key
     * @param file the local working copy
     * @return {@code true} if the copy is known to have been overwritten locally
     */
    private static boolean isLocallyModified(String key, File file) {
        String recorded = STORE_SYNC_REGISTER.get(key);
        return recorded != null && !recorded.equals(syncStamp(file));
    }

    /**
     * The stat pair a synchronisation is recorded by.
     *
     * @param file the local working copy
     * @return the {@code length:lastModified} pair
     */
    private static String syncStamp(File file) {
        return file.length() + ":" + file.lastModified();
    }

    /**
     * Records that the local working copy and the stored object now agree.
     *
     * @param key the storage key
     * @param file the local working copy
     */
    private static void recordSynchronised(String key, File file) {
        if (STORE_SYNC_REGISTER.size() >= STORE_SYNC_REGISTER_MAX) {
            // forgetting is always safe: an absent record means "we do not know", and the reconciliation then
            // treats the store as authoritative, which costs a transfer and never content
            STORE_SYNC_REGISTER.clear();
            Debug.logInfo(EVENT_STORE_REGISTER_BOUNDED + ": the content store synchronisation register reached "
                    + STORE_SYNC_REGISTER_MAX + " entries and was cleared", MODULE);
        }
        STORE_SYNC_REGISTER.put(key, syncStamp(file));
    }

    /**
     * Writes the local working copy into the store, streaming it with its length declared up front.
     *
     * @param store the configured provider
     * @param key the storage key
     * @param file the local working copy to publish
     * @throws GeneralException if the store refuses the content
     * @throws IOException if the local copy cannot be read or the store cannot be written to
     */
    private static void publish(ContentStore store, String key, File file) throws GeneralException, IOException {
        Path path = file.toPath();
        long length = Files.size(path);
        try (InputStream content = Files.newInputStream(path, StandardOpenOption.READ)) {
            store.put(key, content, length);
        }
        recordSynchronised(key, file);
        Debug.logVerbose(EVENT_STORE_PUBLISHED + " " + ContentStoreFactory.reference(key) + " length [" + length + "]",
                MODULE);
    }

    /**
     * Writes the stored object over the local working copy, so that a caller holding a {@link File} sees the
     * content the store holds.
     *
     * <p>The copy is staged beside its destination and moved into place, so a reader that opens the working
     * copy while this is happening sees either the whole previous content or the whole new content and never a
     * partially written file. The staging file is removed on every path, so a failed materialisation leaves
     * nothing behind to be mistaken for content.
     *
     * @param store the configured provider
     * @param key the storage key
     * @param file the local working copy to overwrite
     * @throws GeneralException if the store refuses the read
     * @throws IOException if the object cannot be read or the local copy cannot be written
     */
    private static void materialise(ContentStore store, String key, File file) throws GeneralException, IOException {
        Path target = file.toPath();
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("Cannot materialise " + ContentStoreFactory.reference(key) + ": "
                    + ContentStoreFactory.reference(file.getPath()) + " has no parent directory");
        }
        Files.createDirectories(parent);
        Path staging = Files.createTempFile(parent, MATERIALISE_PREFIX, MATERIALISE_SUFFIX);
        boolean placed = false;
        try {
            try (InputStream content = store.openStream(key);
                    OutputStream sink = Files.newOutputStream(staging, StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING)) {
                content.transferTo(sink);
            }
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
            }
            placed = true;
        } finally {
            if (!placed) {
                Files.deleteIfExists(staging);
            }
        }
        recordSynchronised(key, file);
        Debug.logVerbose(EVENT_STORE_READ + " " + ContentStoreFactory.reference(key) + " materialised for local access",
                MODULE);
    }

    /**
     * Renders a file-backed resource directly out of the store, when the store holds it.
     *
     * <p>The type guard is deliberately narrower than {@code _FILE}/{@code _FILE_BIN}: it admits exactly the
     * three types the local resolution below the seam renders, so a {@code _BIN} type - which this method has
     * never rendered as text - still writes nothing at all rather than starting to be rendered the moment a
     * provider is configured.
     *
     * <p>The stream is opened through {@link ContentStoreFactory#openContentStream(String, String, String)},
     * which is the bridge form of exactly this read, so the key a render resolves is by construction the key
     * a write published under. Absence is answered by falling back to the local filesystem rather than by
     * failing, which is what lets a deployment that already holds content on disk adopt object storage
     * without a migration step.
     *
     * @param dataResourceTypeId the {@code DataResource} type
     * @param objectInfo the type-relative location recorded on the {@code DataResource}
     * @param contextRoot the webapp context root, required for the {@code CONTEXT_FILE} type
     * @param out the output to render into
     * @return {@code true} if the content was rendered from the store, {@code false} if the caller should fall
     *     back to the local filesystem
     * @throws GeneralException if the provider cannot be reached
     * @throws IOException if the content cannot be read or written
     */
    private static boolean renderFromContentStore(String dataResourceTypeId, String objectInfo, String contextRoot, Appendable out)
            throws GeneralException, IOException {
        if (!isRenderedFileType(dataResourceTypeId) || UtilValidate.isEmpty(objectInfo)) {
            return false;
        }
        if (configuredContentStore() == null) {
            // Database mode: no key is derived and no provider is consulted, so the local resolution runs
            // unchanged.
            return false;
        }
        String key = contentStoreKey(dataResourceTypeId, objectInfo, contextRoot);
        InputStream content;
        try {
            content = ContentStoreFactory.openContentStream(dataResourceTypeId, objectInfo, contextRoot);
        } catch (FileNotFoundException absent) {
            return false;
        }
        if (content == null) {
            return false;
        }
        try (InputStream owned = content;
                InputStreamReader in = new InputStreamReader(owned, StandardCharsets.UTF_8)) {
            UtilIO.copy(in, out);
        }
        Debug.logVerbose(EVENT_STORE_READ + " " + ContentStoreFactory.reference(key) + " rendered", MODULE);
        return true;
    }

    /**
     * Opens a stream over a file-backed resource in the store, when the store holds it.
     *
     * <p><strong>Ownership of the returned stream passes to the caller</strong>, which is the same contract the
     * local {@link Files#newInputStream} branch has always had, so the two are interchangeable from a caller's
     * point of view.
     *
     * @param dataResourceTypeId the {@code DataResource} type
     * @param objectInfo the type-relative location recorded on the {@code DataResource}
     * @param contextRoot the webapp context root, required for the {@code CONTEXT_FILE} type
     * @return the {@code stream}/{@code length} pair, or null if the caller should fall back to the local
     *     filesystem
     * @throws GeneralException if the provider cannot be reached
     * @throws IOException if the stream cannot be opened
     */
    private static Map<String, Object> streamFromContentStore(String dataResourceTypeId, String objectInfo, String contextRoot)
            throws GeneralException, IOException {
        ContentStore store = configuredContentStore();
        if (store == null) {
            return null;
        }
        String key = ContentStoreFactory.storageKeyFor(dataResourceTypeId, objectInfo, contextRoot);
        if (!store.exists(key)) {
            return null;
        }
        long length = store.size(key);
        InputStream content = store.openStream(key);
        Debug.logVerbose(EVENT_STORE_READ + " " + ContentStoreFactory.reference(key) + " streamed length [" + length + "]",
                MODULE);
        return UtilMisc.toMap("stream", content, "length", length);
    }

    /**
     * Returns the upload location the configured content store supplies, or null when it supplies none.
     *
     * @param delegator the delegator to resolve deployment configuration through, may be null
     * @param absolute whether the absolute form is wanted
     * @return the store-supplied upload location, or null when the local computation should be used
     */
    private static String contentStoreUploadPath(Delegator delegator, boolean absolute) {
        try {
            // The delegator is handed on rather than resolved around, so a SystemProperty override selects the
            // same provider here as it does everywhere else, and database mode simply answers null.
            return ContentStoreFactory.resolveUploadPath(delegator, absolute);
        } catch (GeneralException e) {
            throw uploadLocationRefused(e);
        }
    }

    /**
     * Converts a refused upload location into the one unchecked failure the frozen upload path can raise.
     *
     * <p>This is the single place the package's checked failure policy is converted, and it exists because the
     * upload-path methods have never declared a checked exception: widening them would change a public signature
     * this refactor must preserve. Both ways into the content store - the provider that supplies a location of its
     * own and the staging allocation used for one that does not - are converted here, so the identical condition is
     * reported identically whichever route the deployment's configuration takes, and the checked failure is carried
     * as the cause so a caller can still tell a misconfigured deployment
     * ({@code ContentStoreConfigurationException}) from a single failed allocation.
     *
     * <p>Refusing is the point. Handing back a local path to a deployment that believes it is writing to object
     * storage is how one instance ends up holding the only copy of an upload, so no location is ever invented here.
     *
     * @param refusal the checked failure the content store reported
     * @return the unchecked failure to throw
     */
    private static GeneralRuntimeException uploadLocationRefused(GeneralException refusal) {
        return new GeneralRuntimeException("The configured content store provider could not supply an upload location",
                refusal);
    }
}
