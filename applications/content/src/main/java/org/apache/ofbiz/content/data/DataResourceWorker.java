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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
import org.apache.ofbiz.content.data.store.ContentStoreFactory;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.model.ModelReader;
import org.apache.ofbiz.entity.transaction.GenericTransactionException;
import org.apache.ofbiz.entity.transaction.TransactionUtil;
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

    // Content store seam constants. Deliberately not configurable: they bound resource use and tighten
    // permissions, so an operator has nothing to gain by changing them and a deployment has nothing to tune.
    /** How much is read from a provider at a time when content is copied through rather than held in memory. */
    private static final int COPY_BUFFER_SIZE = 8192;
    /** The share of a filesystem's free space one object may occupy while it is being served from it. */
    private static final long FREE_SPACE_DIVISOR = 2L;
    /** The share of the heap one upload may occupy while it is being published to a provider. */
    private static final long PUBLISH_HEAP_DIVISOR = 8L;
    /** Marks the private, incomplete copy a read-through writes before it is moved onto its final name. */
    private static final String STAGING_PREFIX = ".ofbiz-content-";
    /** Marks the private, incomplete copy a read-through writes before it is moved onto its final name. */
    private static final String STAGING_SUFFIX = ".part";
    /** Names the temporary file a provider's content is spooled into to be streamed to a consumer. */
    private static final String SPOOL_PREFIX = "ofbiz-content-";
    /** Names the temporary file a provider's content is spooled into to be streamed to a consumer. */
    private static final String SPOOL_SUFFIX = ".spool";
    /** The permissions an upload staging directory is restricted to where the filesystem supports them. */
    private static final String OWNER_ONLY_DIRECTORY = "rwx------";

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

    public static File getContentFile(String dataResourceTypeId, String objectInfo, String contextRoot)
            throws GeneralException, FileNotFoundException {
        // Content storage seam. When a provider is configured the authoritative bytes live in it, so a resource
        // this instance holds no local copy of is not absent. resolveContentLocation performs every check this
        // method has always performed, in the order it always performed them, and asks the provider nothing at
        // all; only once it has accepted the location is the content read through from the provider into it, so
        // a location this method would have refused never reaches the provider (CWE-200). Inert by default: with
        // no provider configured resolveContentLocation throws the FileNotFoundException at exactly the point it
        // always did and the read-through is never entered.
        File file = resolveContentLocation(dataResourceTypeId, objectInfo, contextRoot);
        if (file != null && !file.exists() && !fetchFromContentStore(file)) {
            throw new FileNotFoundException("No file found: " + file.getAbsolutePath());
        }
        return file;
    }

    /**
     * Resolves a file-backed {@code DataResource} location to the one local file it names, applying every
     * authorisation the resource type carries and performing no content-store request whatsoever.
     *
     * <p>This is the pre-existing body of {@link #getContentFile}, unchanged in statement order, extracted so
     * that the read paths which must not materialise anything locally - {@link #getDataResourceStream} - can
     * resolve and authorise a location through exactly the same code rather than a second copy of it that could
     * drift. Keeping it free of provider I/O is what makes "authorise, then ask the provider" true of every
     * caller at once: a location refused here costs no request, so no caller can be used as an existence oracle
     * for a forbidden location (CWE-200).
     *
     * <p>The single difference from the pre-refactor behaviour is which exception an absent location raises, and
     * only when a provider is configured. With no provider configured absence is still final and still raises
     * {@link FileNotFoundException} at the point it always did. With one configured absence is not yet an answer
     * - the provider may hold the content - so the remaining checks of the branch run first and the caller
     * decides what absence means once they have passed.
     *
     * @param dataResourceTypeId the {@code DataResource.dataResourceTypeId}
     * @param objectInfo the {@code DataResource.objectInfo} location
     * @param contextRoot the webapp context root, required by the {@code CONTEXT_FILE} types and otherwise
     *     ignored
     * @return the resolved location, or {@code null} for a type that is not file backed, exactly as
     *     {@link #getContentFile} has always returned for one
     * @throws GeneralException if the location is refused - a relative {@code LOCAL_FILE}, a location outside an
     *     allow list or the context root, an empty context root, or a provider that cannot be constructed
     * @throws FileNotFoundException if the location holds nothing and no provider could hold it either
     */
    private static File resolveContentLocation(String dataResourceTypeId, String objectInfo, String contextRoot)
            throws GeneralException, FileNotFoundException {
        File file = null;

        if ("LOCAL_FILE".equals(dataResourceTypeId) || "LOCAL_FILE_BIN".equals(dataResourceTypeId)) {
            file = FileUtil.getFile(objectInfo);
            if (!file.exists() && !contentStoreConfigured()) {
                throw new FileNotFoundException("No file found: " + (objectInfo));
            }
            if (!file.isAbsolute()) {
                throw new GeneralException("File (" + objectInfo + ") is not absolute");
            }
            SecurityUtil.checkLocalFileAllowList(file);
        } else if ("OFBIZ_FILE".equals(dataResourceTypeId) || "OFBIZ_FILE_BIN".equals(dataResourceTypeId)) {
            String prefix = System.getProperty("ofbiz.home");

            String sep = "";
            if (objectInfo.indexOf('/') != 0 && prefix.lastIndexOf('/') != (prefix.length() - 1)) {
                sep = "/";
            }
            file = FileUtil.getFile(prefix + sep + objectInfo);
            if (!file.exists() && !contentStoreConfigured()) {
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
            if (!file.exists() && !contentStoreConfigured()) {
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

        String uploadPath = getDataResourceContentUploadPath(initialPath, maxFiles, absolute);
        // Content storage seam. The location is handed back exactly as it has always been computed, because it
        // becomes the immutable DataResource.objectInfo of every resource written into it; what the seam adds is
        // that the chosen directory becomes private staging whose new content is published to the provider when
        // the transaction commits. Inert by default: with no provider configured stageUploadDirectory returns
        // immediately and this is the pre-existing method with one extra call.
        stageUploadDirectory(null, uploadPath, absolute);
        return uploadPath;
    }

    public static String getDataResourceContentUploadPath(Delegator delegator, boolean absolute) {
        String initialPath = EntityUtilProperties.getPropertyValue("content", "content.upload.path.prefix", delegator);
        double maxFiles = UtilProperties.getPropertyNumber("content", "content.upload.max.files");
        if (maxFiles < 1) {
            maxFiles = 250;
        }

        String uploadPath = getDataResourceContentUploadPath(initialPath, maxFiles, absolute);
        // Content storage seam, delegator aware so a SystemProperty row can select the provider exactly as it
        // already can select the upload path prefix. Inert by default, as above.
        stageUploadDirectory(delegator, uploadPath, absolute);
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

    public static void renderFile(String dataResourceTypeId, String objectInfo, String rootDir, Appendable out) throws GeneralException, IOException {
        // TODO: this method assumes the file is a text file, if it is an image we should respond differently,
        //  see the comment above for IMAGE_OBJECT type data RESOURCE

        // Content storage seam. This method only copies bytes through to the caller's Appendable, so a stream is
        // all it needs and nothing is ever written from here. Each branch keeps its own statements in its own
        // pre-existing order - which is deliberately not getContentFile's order for LOCAL_FILE - and the provider
        // is consulted only after that branch's checks have passed, so no stream is ever opened for a location
        // this method would have refused (CWE-200). Content the provider turns out not to hold falls through to
        // the local read rather than failing, which is what lets a deployment that already holds content on disk
        // adopt object storage without a migration step. Inert by default: with no provider configured the
        // absence deferral answers false where the FileNotFoundException was always thrown, the render helper
        // answers false, and every line below runs unchanged. Both are short-circuited by a present local file,
        // which therefore costs nothing at all.
        if ("LOCAL_FILE".equals(dataResourceTypeId) && UtilValidate.isNotEmpty(objectInfo)) {
            File file = FileUtil.getFile(objectInfo);
            if (!file.isAbsolute()) {
                throw new GeneralException("File (" + objectInfo + ") is not absolute");
            }
            boolean absent = !file.exists();
            if (absent && !contentStoreConfigured()) {
                throw new FileNotFoundException("No file found: " + file.getAbsolutePath());
            }
            SecurityUtil.checkLocalFileAllowList(file);
            if (renderFromContentStore(file, out)) {
                return;
            }
            if (absent) {
                throw new FileNotFoundException("No file found: " + file.getAbsolutePath());
            }
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
            boolean absent = !file.exists();
            if (absent && !contentStoreConfigured()) {
                throw new FileNotFoundException("No file found: " + file.getAbsolutePath());
            }
            SecurityUtil.checkOfbizFileAllowList(file);
            if (renderFromContentStore(file, out)) {
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
            boolean absent = !file.exists();
            if (absent && !contentStoreConfigured()) {
                throw new FileNotFoundException("No file found: " + file.getAbsolutePath());
            }
            if (renderFromContentStore(file, out)) {
                return;
            }
            if (absent) {
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
                // Content storage seam. Stream consumers are served straight from the provider rather than by
                // first materialising the object into the location objectInfo names, so serving content leaves no
                // durable local state behind at all (CWE-400/CWE-459). The location is resolved and authorised
                // first, through the very code getContentFile uses, so nothing is requested for a location that
                // method would refuse. Inert by default: with no provider configured streamFromContentStore
                // answers null and the two statements below are the pre-existing local read.
                File file = resolveContentLocation(dataResourceTypeId, objectInfo, contextRoot);
                if (file == null) {
                    throw new GeneralException("The dataResourceTypeId [" + dataResourceTypeId + "] names no file"
                            + " location; cannot stream");
                }
                Map<String, Object> streamed = streamFromContentStore(file);
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

    // ---------------------------------------------------------------------------------------------------------
    // Content store seam support
    //
    // The helpers the five seams above delegate through. Each one reports "nothing to do" when no provider is
    // configured, so the committed default - DataResource database storage, with file-backed resources on the
    // local filesystem - reaches none of it. All object-storage behaviour itself lives in the store package;
    // nothing here knows which provider is active, and nothing here touches a provider SDK.
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Reports whether a content-storage provider is configured, without asking that provider anything.
     *
     * <p>This is the whole of what the resolution and render branches need before they have finished authorising
     * a location: "could the content be somewhere other than this disk?". Answering it costs a cached property
     * read and, at most once per configuration, the construction of the provider - never a request to it - which
     * is what allows the branches to defer absence without turning a refused location into an existence oracle
     * (CWE-200).
     *
     * @return {@code true} when content is held in a provider rather than in the {@code DataResource} database
     *     columns
     * @throws GeneralException if a provider is selected but its configuration is incomplete or unusable, which
     *     is reported rather than hidden because quietly serving local files would mask a broken deployment
     */
    private static boolean contentStoreConfigured() throws GeneralException {
        return ContentStoreFactory.getContentStore() != null;
    }

    /**
     * Derives the provider-relative storage key that names a resolved content location.
     *
     * <p>The key is the location's path relative to {@code ofbiz.home}, with {@code /} separators. Deriving it
     * from the resolved path rather than from the raw {@code objectInfo} is what makes one key name one piece of
     * content from every direction: the upload publication sees only a file, the read paths see a type and an
     * {@code objectInfo}, and both arrive at the same key because both resolve to the same path first. It is
     * stable across instances because every instance of a deployment runs the same image from the same home, and
     * collision-free because a filesystem path already is.
     *
     * @param file the resolved, authorised content location
     * @return the storage key, or {@code null} when the location lies outside {@code ofbiz.home} and therefore
     *     has no key - an operator-chosen absolute {@code LOCAL_FILE} elsewhere on the host stays purely local
     */
    private static String storageKey(File file) {
        String home = System.getProperty("ofbiz.home");
        if (UtilValidate.isEmpty(home)) {
            return null;
        }
        Path root = Paths.get(home).toAbsolutePath().normalize();
        Path resolved = file.toPath().toAbsolutePath().normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            return null;
        }
        String key = root.relativize(resolved).toString().replace(File.separatorChar, '/');
        return UtilValidate.isEmpty(key) ? null : key;
    }

    /**
     * Brings a resolved content location into step with the provider, so that a caller handed a {@link File}
     * finds the content in it.
     *
     * <p>Called only for a location that is absent locally and only after its branch has authorised it, so the
     * provider is never asked about a location this class would refuse. What lands on disk is a read-through
     * copy of content whose authoritative home is the provider: any instance can rebuild it at any time, which
     * is what keeps instances freely replaceable even though the copy outlives the request. It is written to a
     * private temporary file in the destination directory and moved into place in one step, so a concurrent
     * reader sees either nothing or the whole content, and it is bounded by the free space of the filesystem it
     * lands on so that no object can fill the disk (CWE-400).
     *
     * @param file the resolved, authorised location to bring into step
     * @return {@code true} when the content now exists at that location, {@code false} when the provider holds
     *     nothing for it and the caller must treat it as absent exactly as before
     * @throws GeneralException if the provider cannot be reached, refuses the key, or the copy cannot be written
     */
    private static boolean fetchFromContentStore(File file) throws GeneralException {
        String key = storageKey(file);
        Path target = file.toPath();
        Path directory = target.getParent();
        if (key == null || directory == null) {
            return false;
        }
        InputStream stored;
        try {
            // Translated here rather than declared, because the frozen signature of the seam this serves admits
            // no IOException other than the FileNotFoundException that means "absent".
            stored = openStoredContent(key);
        } catch (IOException e) {
            throw new GeneralException("Could not reach the configured content store for [" + key + "]", e);
        }
        if (stored == null) {
            return false;
        }
        Path staging = null;
        try (InputStream in = stored) {
            Files.createDirectories(directory);
            staging = Files.createTempFile(directory, STAGING_PREFIX, STAGING_SUFFIX);
            try (OutputStream out = Files.newOutputStream(staging, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                copyBounded(in, out, freeSpaceBudget(directory));
            }
            moveIntoPlace(staging, target);
            staging = null;
            return true;
        } catch (IOException e) {
            throw new GeneralException("Could not read the content stored under [" + key + "] into [" + target + "]", e);
        } finally {
            if (staging != null) {
                deleteQuietly(staging);
            }
        }
    }

    /**
     * Renders the content the provider holds for a resolved location straight into the supplied output.
     *
     * <p>Nothing is written to local disk and nothing is held in the heap in full: the provider's stream is
     * copied through as it is consumed, which is what the pre-refactor local read also did.
     *
     * @param file the resolved, authorised location whose content is wanted
     * @param out the output to render into
     * @return {@code true} when the content was rendered from the provider, {@code false} when there is no
     *     provider, no key names the location, or the provider holds nothing for it - in each case the caller
     *     falls back to the local read it has always performed
     * @throws GeneralException if the provider cannot be reached or refuses the key
     * @throws IOException if the content cannot be read or the output cannot be written
     */
    private static boolean renderFromContentStore(File file, Appendable out) throws GeneralException, IOException {
        String key = storageKey(file);
        if (key == null) {
            return false;
        }
        InputStream stored = openStoredContent(key);
        if (stored == null) {
            return false;
        }
        try (InputStreamReader in = new InputStreamReader(stored, StandardCharsets.UTF_8)) {
            UtilIO.copy(in, out);
        }
        return true;
    }

    /**
     * Serves a stream consumer directly from the provider, together with the exact content length that
     * {@link #getDataResourceStream} must report.
     *
     * <p>The provider's stream is consumed once into a private temporary file, which is then unlinked while the
     * returned stream still holds it open. The consequences are the point of the design: the content is never
     * held in the heap in full, its exact length is known without a second request, the location
     * {@code objectInfo} names is never written to, and the temporary file cannot survive - it disappears when
     * the returned stream is closed, and when this process exits even if a caller forgets to close it
     * (CWE-400/CWE-459). The spool is bounded by the free space of the temporary filesystem, so no object can
     * fill it.
     *
     * <p>An exact length is not optional here and is the whole reason the content is passed through a file at
     * all: the event that serves object data to a browser sets {@code Content-Length} from it and refuses to
     * serve at all without one, and the storage contract offers no way to ask a provider for a size. Spooling
     * answers it from the one read that has to happen anyway, which is cheaper than a second request and, unlike
     * reading the object into a byte array, is bounded by disk rather than by the heap.
     *
     * @param file the resolved, authorised location whose content is wanted
     * @return the {@code stream} and {@code length} pair {@link #getDataResourceStream} returns, or {@code null}
     *     when there is no provider, no key names the location, or the provider holds nothing for it
     * @throws GeneralException if the provider cannot be reached, refuses the key, or the content exceeds the
     *     space available to spool it
     * @throws IOException if the content cannot be read or the spool cannot be written
     */
    private static Map<String, Object> streamFromContentStore(File file) throws GeneralException, IOException {
        String key = storageKey(file);
        if (key == null) {
            return null;
        }
        InputStream stored = openStoredContent(key);
        if (stored == null) {
            return null;
        }
        Path spool = Files.createTempFile(SPOOL_PREFIX, SPOOL_SUFFIX);
        InputStream stream = null;
        try (InputStream in = stored) {
            long length;
            try (OutputStream out = Files.newOutputStream(spool, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                length = copyBounded(in, out, freeSpaceBudget(spool.getParent()));
            }
            stream = openAndUnlink(spool);
            return UtilMisc.toMap("stream", stream, "length", length);
        } finally {
            if (stream == null) {
                deleteQuietly(spool);
            }
        }
    }

    /**
     * Opens the provider's stream for a key, translating "the provider holds nothing" into {@code null}.
     *
     * <p>Absence is the one provider failure that is not a failure of this deployment: answering it by falling
     * back to the local filesystem is what lets a deployment that already holds content on disk adopt object
     * storage without a migration step. Every other failure - an unreachable store, a refused key, a read error
     * - stays a failure and propagates, because serving a local file after one of those would hide a broken
     * deployment.
     *
     * @param key the storage key to open
     * @return the provider's stream, or {@code null} when there is no provider at all - the default - and when
     *     the provider holds nothing under that key
     * @throws GeneralException if the provider refuses the key or its configuration is unusable
     * @throws IOException if the provider cannot be reached
     */
    private static InputStream openStoredContent(String key) throws GeneralException, IOException {
        var store = ContentStoreFactory.getContentStore();
        if (store == null) {
            return null;
        }
        try {
            return store.openStream(key);
        } catch (FileNotFoundException absent) {
            Debug.logVerbose(absent, "The configured content store holds nothing under [" + key + "], so the local"
                    + " filesystem answers for it instead", MODULE);
            return null;
        }
    }

    /**
     * Copies a stream through to an output, refusing to write more than the supplied budget.
     *
     * @param in the stream to read to its end
     * @param out the output to write to
     * @param budget the greatest number of bytes that may be written
     * @return the number of bytes copied
     * @throws GeneralException if the content is larger than the budget, which is reported before the budget is
     *     exceeded rather than after the space is gone
     * @throws IOException if the copy fails
     */
    private static long copyBounded(InputStream in, OutputStream out, long budget) throws GeneralException, IOException {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long total = 0;
        int read = in.read(buffer);
        while (read != -1) {
            total += read;
            if (total > budget) {
                throw new GeneralException("The stored content is larger than the " + budget
                        + " byte budget this instance can hold while serving it");
            }
            out.write(buffer, 0, read);
            read = in.read(buffer);
        }
        return total;
    }

    /**
     * Returns how many bytes may be written to the filesystem a directory sits on.
     *
     * <p>Half of what is free, so that serving one object can never be the reason a deployment runs out of disk,
     * and so that the bound needs no configuration of its own to keep current.
     *
     * @param directory the directory the bytes will be written into
     * @return the budget in bytes, always greater than zero
     * @throws GeneralException if there is no usable space left at all
     * @throws IOException if the filesystem cannot be interrogated
     */
    private static long freeSpaceBudget(Path directory) throws GeneralException, IOException {
        long budget = Files.getFileStore(directory).getUsableSpace() / FREE_SPACE_DIVISOR;
        if (budget <= 0) {
            throw new GeneralException("There is no free space left on the filesystem holding [" + directory
                    + "], so the stored content cannot be served from this instance");
        }
        return budget;
    }

    /**
     * Opens a stream over a spool file and makes that file's disappearance unconditional.
     *
     * @param spool the spool file the caller has finished writing
     * @return a stream over the spool file, which the caller passes on to the content consumer
     * @throws IOException if the spool file cannot be opened
     */
    private static InputStream openAndUnlink(Path spool) throws IOException {
        InputStream stream = Files.newInputStream(spool, StandardOpenOption.READ);
        try {
            // Unlinking a file that is still open leaves the open descriptor perfectly readable and reclaims the
            // space when it is closed, or when this process exits. No consumer can leave the spool behind by
            // forgetting to close the stream it was handed, and no crash can leave it behind either.
            Files.delete(spool);
            return stream;
        } catch (IOException openFileCannotBeUnlinked) {
            // A filesystem that refuses to unlink an open file gets the portable equivalent instead.
            stream.close();
            Debug.logVerbose(openFileCannotBeUnlinked, "The content spool file [" + spool + "] cannot be unlinked"
                    + " while it is open, so it is removed when the stream is closed and, failing that, on exit", MODULE);
            spool.toFile().deleteOnExit();
            return Files.newInputStream(spool, StandardOpenOption.READ, StandardOpenOption.DELETE_ON_CLOSE);
        }
    }

    /**
     * Moves a completed private file onto its final name in one step where the filesystem allows it.
     *
     * @param staging the private file holding the complete content
     * @param target the name the content must appear under
     * @throws IOException if the move fails
     */
    private static void moveIntoPlace(Path staging, Path target) throws IOException {
        try {
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException atomicUnsupported) {
            Debug.logVerbose(atomicUnsupported, "The filesystem holding [" + target + "] cannot move a file"
                    + " atomically, so the replacement is not atomic there", MODULE);
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Removes a temporary file, reporting rather than propagating a failure to do so.
     *
     * @param path the temporary file to remove
     */
    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            Debug.logWarning(e, "Could not remove the temporary content file [" + path + "]", MODULE);
        }
    }

    /**
     * Turns the upload directory the pre-existing computation chose into private staging whose new content is
     * published to the provider when the current transaction commits.
     *
     * <p>The location itself is never changed. It becomes the immutable {@code DataResource.objectInfo} of every
     * resource written into it, so relocating it would write a transient path into permanent data; instead the
     * directory is restricted to this user and censused, and publication happens afterwards. This is also what
     * carries the registered write services into the provider without touching them: {@code createFile},
     * {@code createAnonFile} and {@code updateFile} build their target from the {@code objectInfo} derived from
     * this very location, so whatever they write into it is published on commit.
     *
     * @param delegator the delegator a {@code SystemProperty} provider override is resolved through; may be null
     * @param uploadPath the location the caller is about to return
     * @param absolute whether that location is absolute rather than relative to {@code ofbiz.home}
     */
    private static void stageUploadDirectory(Delegator delegator, String uploadPath, boolean absolute) {
        File directory = absolute ? FileUtil.getFile(uploadPath)
                : FileUtil.getFile(System.getProperty("ofbiz.home") + uploadPath);
        try {
            // The delegator is handed on rather than resolved around, so a SystemProperty override selects the
            // same provider here as it does everywhere else, and database mode simply has nothing to stage.
            if (ContentStoreFactory.getContentStore(delegator) == null) {
                return;
            }
            UploadPublication.stage(directory);
        } catch (GeneralException e) {
            throw uploadLocationRefused(e);
        }
    }

    /**
     * Wraps a refused upload location in the unchecked carrier the frozen upload-path signatures require.
     *
     * <p>Neither route into the store package falls back to a purely local directory: a deployment that asked for
     * object storage and cannot have it must be told, because quietly writing to local disk would store content
     * the provider never receives.
     *
     * @param refusal the checked failure the store package reported
     * @return the unchecked carrier to throw, keeping the refusal available through {@code getNested()}
     */
    private static GeneralRuntimeException uploadLocationRefused(GeneralException refusal) {
        return new GeneralRuntimeException("The configured content store provider could not supply an upload location",
                refusal);
    }

    /**
     * Publishes to the content store whatever a transaction writes into an upload directory, and removes the
     * local copy once it has.
     *
     * <p>One of these is registered with the transaction the first time an upload location is handed out inside
     * it, and it carries every directory handed out by that transaction. On commit each directory is compared
     * with the census taken when it was staged, and every file that is new or has changed is published under its
     * storage key and then removed from local disk - which is what leaves the instance with no durable local
     * state to lose. Nothing at all happens on any other outcome.
     *
     * <p>Publication is deliberately defensive, because these directories are shared by every concurrent upload:
     * a file is fingerprinted before it is read and re-checked afterwards, and one that changed in between - or
     * that is not a regular file - is left alone rather than published half-written (CWE-59/CWE-367). A file that
     * cannot be published stays on local disk, because at that moment it is the only copy of the content.
     */
    private static final class UploadPublication implements Synchronization {

        /** The publication registered for the transaction the current thread is running in, if any. */
        private static final ThreadLocal<UploadPublication> PENDING = new ThreadLocal<>();

        /** Absolute directory path to the file name/fingerprint census taken when that directory was staged. */
        private final Map<String, Map<String, String>> staged = new ConcurrentHashMap<>();

        /** Set once the transaction has completed, so that a stale thread binding is never reused. */
        private volatile boolean completed;

        private UploadPublication() { }

        /**
         * Restricts an upload directory to this user and arranges for its new content to be published when the
         * current transaction commits.
         *
         * @param directory the upload directory the caller is about to hand out
         * @throws GeneralException if the transaction manager will not accept the publication, which must not be
         *     hidden: content would otherwise be written locally and never reach the provider
         */
        static void stage(File directory) throws GeneralException {
            // First, and whatever happens next: a directory that is about to hold content on its way to a provider
            // is private to this instance's account. It is done here rather than as part of publication because it
            // is worth doing even when nothing can be published - an upload that has to stay on local disk is
            // exactly the one that should not be world readable while it waits.
            restrictToOwner(directory);
            UploadPublication pending = PENDING.get();
            if (pending != null && pending.completed) {
                // The synchronization for an earlier transaction completed on another thread, so this binding is
                // stale. It is dropped rather than reused, which would publish into a finished transaction.
                PENDING.remove();
                pending = null;
            }
            if (pending == null) {
                try {
                    if (!TransactionUtil.isTransactionInPlace()) {
                        Debug.logWarning("No transaction is in place, so uploads written into [" + directory + "] are"
                                + " not published to the content store and stay on this instance's local disk", MODULE);
                        return;
                    }
                    pending = new UploadPublication();
                    TransactionUtil.registerSynchronization(pending);
                } catch (GenericTransactionException e) {
                    // A transaction manager that is present but failing must not be papered over: the upload
                    // would be written locally while the deployment believed it was written to the provider.
                    throw new GeneralException("Could not arrange for uploads written into [" + directory
                            + "] to be published to the content store", e);
                } catch (IllegalStateException transactionsUnavailable) {
                    // No transaction subsystem at all, which is what a command line tool or a test run outside a
                    // booted instance looks like. There is nothing to publish on, and refusing the upload
                    // location would break a caller that could never have published in the first place, so this
                    // is reported exactly as having no transaction in place is.
                    Debug.logWarning(transactionsUnavailable, "Transactions are not available, so uploads written"
                            + " into [" + directory + "] are not published to the content store and stay on this"
                            + " instance's local disk", MODULE);
                    return;
                }
                PENDING.set(pending);
            }
            pending.census(directory);
        }

        /**
         * Restricts a staging directory to the account this instance runs as, where the filesystem supports it.
         *
         * @param directory the staging directory
         */
        private static void restrictToOwner(File directory) {
            try {
                Files.setPosixFilePermissions(directory.toPath(), PosixFilePermissions.fromString(OWNER_ONLY_DIRECTORY));
            } catch (UnsupportedOperationException | IOException e) {
                Debug.logVerbose(e, "The upload staging directory [" + directory + "] could not be restricted to this"
                        + " user, so content staged in it keeps the permissions the filesystem gave it", MODULE);
            }
        }

        /**
         * Records which regular files a directory already held, so that only what this transaction writes is
         * published.
         *
         * @param directory the staging directory to census
         */
        private void census(File directory) {
            Map<String, String> before = new ConcurrentHashMap<>();
            File[] entries = directory.listFiles();
            if (entries != null) {
                for (File entry : entries) {
                    try {
                        BasicFileAttributes attributes = Files.readAttributes(entry.toPath(),
                                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        if (attributes.isRegularFile()) {
                            before.put(entry.getName(), fingerprint(attributes));
                        }
                    } catch (IOException e) {
                        Debug.logVerbose(e, "Could not census [" + entry + "], so it is treated as new content"
                                + " should it still be there when the transaction commits", MODULE);
                    }
                }
            }
            staged.putIfAbsent(directory.toPath().toAbsolutePath().normalize().toString(), before);
        }

        @Override
        public void beforeCompletion() {
            // Nothing: publication must not be able to fail a transaction that has already done its work, and
            // the content it publishes is only known to be wanted once that transaction has committed.
        }

        @Override
        public void afterCompletion(int status) {
            completed = true;
            PENDING.remove();
            if (status != Status.STATUS_COMMITTED) {
                // Nothing is published for a transaction that did not commit, and nothing is deleted either.
                // These directories are shared by every concurrent upload and this synchronization cannot tell a
                // file its own rolled-back transaction wrote from one a transaction still in flight is writing;
                // removing the wrong one would destroy content. The rolled-back upload is therefore left exactly
                // where the pre-refactor code also left it, unreferenced on local disk.
                Debug.logInfo("The transaction did not commit, so nothing staged in " + staged.keySet()
                        + " is published to the content store", MODULE);
                staged.clear();
                return;
            }
            for (Map.Entry<String, Map<String, String>> directory : staged.entrySet()) {
                File[] entries = new File(directory.getKey()).listFiles();
                if (entries == null) {
                    continue;
                }
                for (File entry : entries) {
                    BasicFileAttributes attributes;
                    try {
                        attributes = Files.readAttributes(entry.toPath(), BasicFileAttributes.class,
                                LinkOption.NOFOLLOW_LINKS);
                    } catch (IOException e) {
                        Debug.logWarning(e, "Could not examine [" + entry + "], so it is not published to the"
                                + " content store and stays on local disk", MODULE);
                        continue;
                    }
                    // A symbolic link, a directory or a device is not uploaded content and is never published.
                    if (!attributes.isRegularFile()) {
                        continue;
                    }
                    if (fingerprint(attributes).equals(directory.getValue().get(entry.getName()))) {
                        // Byte for byte what was already there when this transaction started: not its content.
                        continue;
                    }
                    publish(entry.toPath(), attributes);
                }
            }
            staged.clear();
        }

        /**
         * Publishes one staged file under its storage key and removes the local copy.
         *
         * @param file the staged file
         * @param censused the attributes the file was fingerprinted with before it was read
         */
        private void publish(Path file, BasicFileAttributes censused) {
            String key = storageKey(file.toFile());
            if (key == null) {
                Debug.logWarning("The upload [" + file + "] lies outside this deployment's home directory, so no"
                        + " content store key names it and it stays on local disk", MODULE);
                return;
            }
            long budget = Runtime.getRuntime().maxMemory() / PUBLISH_HEAP_DIVISOR;
            if (censused.size() > budget) {
                Debug.logError("The upload [" + file + "] is " + censused.size() + " bytes, which is more than the "
                        + budget + " bytes this instance will hold in memory to publish one object, so it stays on"
                        + " local disk and has to be copied to the content store out of band", MODULE);
                return;
            }
            try {
                // Resolved at publication rather than held from staging, so it is always the provider the current
                // configuration names. Nothing to publish to means the local file is the content, as it has always
                // been.
                var store = ContentStoreFactory.getContentStore();
                if (store == null) {
                    Debug.logInfo("No content store is configured any longer, so the upload [" + file + "] stays"
                            + " on local disk", MODULE);
                    return;
                }
                byte[] content;
                // NOFOLLOW_LINKS on the read as well as on the census, so a symbolic link swapped in between the
                // two is refused rather than followed.
                try (InputStream in = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                    content = IOUtils.toByteArray(in);
                }
                // Still the same file, the same size and the same modification time as before the read, and as
                // many bytes as were expected. Anything else means it was replaced or is still being written, and
                // publishing what was read would publish either somebody else's bytes or half a file.
                if (!unchangedSince(file, censused) || content.length != censused.size()) {
                    Debug.logWarning("The upload [" + file + "] changed while it was being read, so it is not"
                            + " published to the content store and stays on local disk", MODULE);
                    return;
                }
                store.put(key, content);
                if (!unchangedSince(file, censused)) {
                    // Publishing changed this very file, which can only mean the provider's storage location is
                    // this path: the filesystem provider deliberately holds content exactly where the
                    // pre-refactor code left it. There is no redundant copy to remove, and removing what is now
                    // there would destroy the only copy. The same answer covers the rarer case of another writer
                    // having replaced the file while it was being published.
                    Debug.logInfo("Published the upload [" + key + "] to the content store, which holds it at that"
                            + " very location, so no local copy is removed", MODULE);
                    return;
                }
                // The provider holds the content somewhere this instance does not, so the local copy has served
                // its purpose. Removing it is what keeps the instance free of durable local state; a later read
                // fetches the content back from the provider.
                Files.deleteIfExists(file);
                Debug.logInfo("Published the upload [" + key + "] to the content store and removed the local copy",
                        MODULE);
            } catch (GeneralException | IOException e) {
                // The local copy is deliberately left in place: at this moment it is the only copy there is.
                Debug.logError(e, "Could not publish the upload [" + key + "] to the content store, so it stays on"
                        + " local disk", MODULE);
            }
        }

        /**
         * Reports whether a file is still, in every respect that matters, the file that was censused.
         *
         * <p>Used on both sides of a publication, where it answers two different questions with one comparison:
         * beforehand, whether the bytes just read are the bytes that were fingerprinted; afterwards, whether
         * publishing left this path alone, which is the only way to tell a provider that stores content
         * elsewhere from one that stores it right here without asking the provider what it is.
         *
         * @param file the file to re-examine
         * @param censused the attributes it was fingerprinted with
         * @return {@code true} when it is the same file, of the same size, modified at the same time
         */
        private static boolean unchangedSince(Path file, BasicFileAttributes censused) {
            try {
                BasicFileAttributes now = Files.readAttributes(file, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                return Objects.equals(censused.fileKey(), now.fileKey())
                        && censused.size() == now.size()
                        && censused.lastModifiedTime().equals(now.lastModifiedTime());
            } catch (IOException e) {
                // Gone, replaced by something that cannot be read, or no longer reachable without following a
                // link. In every one of those cases it is not the file that was censused.
                Debug.logVerbose(e, "[" + file + "] can no longer be examined as it was censused", MODULE);
                return false;
            }
        }

        /**
         * Fingerprints a file well enough to tell "untouched" from "written by this transaction".
         *
         * @param attributes the attributes read without following links
         * @return the fingerprint
         */
        private static String fingerprint(BasicFileAttributes attributes) {
            return attributes.size() + ":" + attributes.lastModifiedTime().toMillis();
        }
    }
}
