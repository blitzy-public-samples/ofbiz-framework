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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
import org.apache.ofbiz.content.data.store.FileSystemContentStore;
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

        // The first two parentCategoryId tests make sure that the first level of children is gotten, so
        // that they are available for display.
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
     * Validates that a CONTEXT_FILE location is inside its webapp root, and binds it to the transaction.
     *
     * <p>The validation is unchanged and is delegated to {@link #assertInsideContextRoot}. The binding is
     * the content-store seam for {@code DataServices.createFileMethod} and {@code updateFileMethod},
     * because this is the one method of this class they call after composing the File they are about to
     * write. The binding does nothing at all unless a store is configured, and publishes only what the
     * transaction actually changed.
     *
     * @param file the location to validate
     * @param contextRoot the webapp root the location must be inside
     * @throws GeneralException if the location resolves outside the webapp root
     */
    static void checkContextFileBoundary(File file, String contextRoot) throws GeneralException {
        assertInsideContextRoot(file, contextRoot);
        bindWrittenFile(file);
    }

    /**
     * Validates that a location is inside a webapp root, with no side effect.
     *
     * <p>Uses a dual-check strategy to support EFS/Docker mount points:
     * <ol>
     *   <li>Canonical paths (resolves symlinks on both sides) - works for non-mounted paths.</li>
     *   <li>Normalized absolute paths (collapses ".." without following symlinks) - fallback for when
     *       contextRoot or a subdirectory inside it is a mount point, causing canonical paths to
     *       diverge. Path traversal via ".." is still blocked by the normalization step.</li>
     * </ol>
     *
     * @param file the location to validate
     * @param contextRoot the webapp root the location must be inside
     * @throws GeneralException if the location resolves outside the webapp root
     */
    private static void assertInsideContextRoot(File file, String contextRoot) throws GeneralException {
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
        File file = null;

        if ("LOCAL_FILE".equals(dataResourceTypeId) || "LOCAL_FILE_BIN".equals(dataResourceTypeId)) {
            file = FileUtil.getFile(objectInfo);
            if (!readFromStore(dataResourceTypeId, file, contextRoot) && !file.exists()) {
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
            if (!readFromStore(dataResourceTypeId, file, contextRoot) && !file.exists()) {
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
            assertInsideContextRoot(file, contextRoot);
            if (!readFromStore(dataResourceTypeId, file, contextRoot) && !file.exists()) {
                throw new FileNotFoundException("No file found: " + (contextRoot + sep + objectInfo));
            }
        }

        // DataServices.createBinaryFileMethod and updateBinaryFileMethod resolve here the one file they
        // are about to write, so binding it here is what carries that write to a configured store.
        bindWrittenFile(file);
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
        String uploadPath = FileSystemContentStore.getUploadPath(absolute);
        bindUploadDirectory(uploadPath, absolute);
        return uploadPath;
    }

    public static String getDataResourceContentUploadPath(Delegator delegator, boolean absolute) {
        String uploadPath = FileSystemContentStore.getUploadPath(delegator, absolute);
        bindUploadDirectory(uploadPath, absolute);
        return uploadPath;
    }

    public static String getDataResourceContentUploadPath(String initialPath, double maxFiles) {
        return getDataResourceContentUploadPath(initialPath, maxFiles, true);
    }

    /**
     * Handles creating sub-directories for file storage; using a max number of files per directory
     * @param initialPath the top level location where all files should be stored
     * @param maxFiles the max number of files to place in a directory
     * @param absolute whether to answer an absolute path rather than an {@code ofbiz.home}-relative one
     * @return the path to the directory where the file should be placed
     */
    public static String getDataResourceContentUploadPath(String initialPath, double maxFiles, boolean absolute) {
        return FileSystemContentStore.getUploadPath(initialPath, maxFiles, absolute);
    }

    private static final String KEY_NAMESPACE = "ofbiz";
    private static final long MAX_PUBLISHED_FILE = 64L * 1024L * 1024L;
    private static final ThreadLocal<Set<String>> PENDING_PUBLICATIONS = ThreadLocal.withInitial(HashSet::new);

    /**
     * Returns the storage key naming the given content, or null when it cannot have one.
     *
     * <p>The key is {@code KEY_NAMESPACE} followed by the content's own {@code ofbiz.home}-relative POSIX
     * path, so one key means the same object on every instance. Content outside {@code ofbiz.home} has no
     * key and is therefore neither published nor looked up. The path carries no tenant scope, which is why
     * {@link ContentStoreFactory} refuses to activate a store at all in a multi-tenant deployment.
     *
     * @param file the content's location on this instance
     * @return the storage key, or null when the location is not inside {@code ofbiz.home}
     */
    private static String storeKey(File file) {
        String home = System.getProperty("ofbiz.home");
        if (UtilValidate.isEmpty(home)) {
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
     * Copies the content the configured store holds onto this instance, so that a local read answers it.
     *
     * <p>Called before the local copy is consulted, so the store is authoritative and a replacement made
     * by another instance is picked up immediately. The copy is written through a staging file in the
     * destination's own directory and moved into place atomically, so a concurrent reader never sees a
     * half-written file.
     *
     * <p><strong>Absence is not failure.</strong> Content the store does not hold answers false and the
     * caller falls back to the local copy exactly as it did before a store existed. A store that cannot
     * answer, or a local copy that cannot be written, raises a {@link GeneralException} instead:
     * reporting a fault as missing content would turn an outage into a 404 and hide it from the operator.
     *
     * @param dataResourceTypeId the resource type, which decides which location check applies
     * @param file the location the content would be read from on this instance
     * @param contextRoot the webapp root for a CONTEXT_FILE, otherwise ignored
     * @return true when the store's copy of the content is now on this instance
     * @throws GeneralException if a store is configured but could not be resolved or could not answer
     */
    private static boolean readFromStore(String dataResourceTypeId, File file, String contextRoot)
            throws GeneralException {
        ContentStore store = ContentStoreFactory.getContentStore();
        if (store == null || store instanceof FileSystemContentStore) {
            return false;
        }
        String key = storeKey(file);
        if (key == null || !authorisedLocation(dataResourceTypeId, file, contextRoot)) {
            return false;
        }
        Path target = file.toPath().toAbsolutePath();
        Path directory = target.getParent();
        Path staged = null;
        try {
            Files.createDirectories(directory);
            staged = Files.createTempFile(directory, target.getFileName().toString(), ".ofbizfetch");
            try (InputStream content = store.openStream(key)) {
                Files.copy(content, staged, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            staged = null;
            if (Debug.verboseOn()) {
                Debug.logVerbose("Content [" + key + "] was read from the content store onto this instance", MODULE);
            }
            return true;
        } catch (FileNotFoundException absent) {
            return false;
        } catch (IOException failure) {
            throw new GeneralException("The content store could not be read for [" + key + "]", failure);
        } finally {
            if (staged != null) {
                removeQuietly(staged);
            }
        }
    }

    /**
     * Reports whether this deployment allows content to be read from the given location.
     *
     * <p>The same check the read would apply to the file afterwards, applied before anything is written
     * to it. Without it, an {@code objectInfo} value could name any path inside {@code ofbiz.home} and
     * have store content written there. A refused location answers false rather than raising, so the
     * caller behaves exactly as it did before a store existed.
     *
     * @param dataResourceTypeId the resource type
     * @param file the location
     * @param contextRoot the webapp root for a CONTEXT_FILE, otherwise ignored
     * @return true when content may be read from this location
     */
    private static boolean authorisedLocation(String dataResourceTypeId, File file, String contextRoot) {
        if (dataResourceTypeId == null) {
            return false;
        }
        try {
            if (dataResourceTypeId.startsWith("LOCAL_FILE")) {
                SecurityUtil.checkLocalFileAllowList(file);
            } else if (dataResourceTypeId.startsWith("OFBIZ_FILE")) {
                SecurityUtil.checkOfbizFileAllowList(file);
            } else if (dataResourceTypeId.startsWith("CONTEXT_FILE")) {
                if (UtilValidate.isEmpty(contextRoot)) {
                    return false;
                }
                assertInsideContextRoot(file, contextRoot);
            } else {
                return false;
            }
            return true;
        } catch (GeneralException refused) {
            Debug.logWarning("Content the store holds for [" + file.getAbsolutePath() + "] was not read onto this"
                    + " instance, because this deployment does not allow content to be read from that location: "
                    + refused.getMessage(), MODULE);
            return false;
        }
    }

    /**
     * Binds the one file a caller has just resolved, and is about to write, to its transaction.
     *
     * <p>A read resolves a file too, and cannot be told apart from a write here, so the publication is
     * registered either way: at commit the file is published only if it actually changed, and a read
     * changes nothing. That is why the check is a length and modification-time comparison of one named
     * file rather than anything that reads content.
     *
     * @param file the file that was resolved, may be null when the resource type is not file backed
     */
    private static void bindWrittenFile(File file) {
        if (file == null || file.getParentFile() == null) {
            return;
        }
        bindPublication(file.getParentFile(), Set.of(file.getName()), false);
    }

    /**
     * Binds the content written into an upload directory to the transaction that resolved it.
     *
     * <p>It is deliberately FAIL-FAST. With an external store configured, an upload that cannot be bound
     * to a transaction would commit a row naming content only this instance can read, so the resolution
     * is refused instead. Nothing here runs when no store is configured, which is the shipped default.
     *
     * @param uploadPath the value returned to the caller
     * @param absolute whether that value is already absolute
     */
    private static void bindUploadDirectory(String uploadPath, boolean absolute) {
        if (UtilValidate.isEmpty(uploadPath)) {
            return;
        }
        String path = absolute ? uploadPath : System.getProperty("ofbiz.home") + uploadPath;
        bindPublication(FileUtil.getFile(path), null, true);
    }

    /**
     * Registers a publication of a directory, or of named files inside it, with the current transaction.
     *
     * @param directory the directory to watch
     * @param names the file names to watch, or null to watch every direct child
     * @param writing whether this is a write path, where the absence of a usable transaction is a fault -
     *     a read path reports and carries on, because it has nothing to publish
     */
    private static void bindPublication(File directory, Set<String> names, boolean writing) {
        ContentStore store;
        try {
            store = ContentStoreFactory.getContentStore();
        } catch (GeneralException e) {
            if (!writing) {
                Debug.logError(e, "The configured content store could not be resolved", MODULE);
                return;
            }
            throw new IllegalStateException("An upload directory cannot be prepared: the configured content store"
                    + " could not be resolved, so content written into it could not be published to it", e);
        }
        if (store == null || store instanceof FileSystemContentStore) {
            // Database storage, or a filesystem store whose root IS the upload directory: the content is
            // already exactly where the deployment says it lives, so there is nothing to publish.
            return;
        }
        if (storeKey(directory) == null) {
            if (!writing) {
                return;
            }
            throw new IllegalStateException("An upload directory outside the OFBiz home directory cannot be"
                    + " published to the content store: [" + directory.getAbsolutePath() + "]");
        }
        String identity = directory.getAbsolutePath() + (names == null ? "" : names);
        try {
            if (TransactionUtil.getStatus() != Status.STATUS_ACTIVE) {
                if (!writing) {
                    // A resolution outside a transaction is a read: there is no write to publish, and
                    // nothing to bind a publication to.
                    return;
                }
                throw new IllegalStateException("An upload directory was resolved outside an active transaction"
                        + " while a content store is configured, so content written into it could not be published"
                        + " before the row naming it commits. Resolve the upload path inside the transaction that"
                        + " writes the file.");
            }
            if (!PENDING_PUBLICATIONS.get().add(identity)) {
                // Already bound in this transaction. Two resolutions of one target are one piece of work,
                // and it is the state at commit that decides what that work is, so the first binding
                // covers the second.
                return;
            }
            TransactionUtil.registerSynchronization(new ContentPublication(store, directory, names, identity));
        } catch (GenericTransactionException e) {
            PENDING_PUBLICATIONS.get().remove(identity);
            if (!writing) {
                Debug.logError(e, "A content store publication could not be bound to the current transaction",
                        MODULE);
                return;
            }
            throw new IllegalStateException("An upload directory cannot be prepared: a publication to the content"
                    + " store could not be bound to the transaction that resolved it", e);
        }
    }

    private static void removeQuietly(Path staged) {
        try {
            Files.deleteIfExists(staged);
        } catch (IOException e) {
            Debug.logWarning(e, "A staging file left by a content store read could not be removed: " + staged,
                    MODULE);
        }
    }

    private record FileFacts(long length, long modifiedAt) {
    }

    /**
     * Publishes the content a transaction wrote, before that transaction commits.
     *
     * <p>Registered by {@link #bindPublication} once per watched identity per transaction, and used only
     * by the transaction that registered it.
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
            Set<String> pending = PENDING_PUBLICATIONS.get();
            pending.remove(identity);
            if (pending.isEmpty()) {
                // Removed rather than left empty, so a pooled thread does not carry the entry for the
                // life of the JVM.
                PENDING_PUBLICATIONS.remove();
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

        private List<File> candidates() {
            if (names != null) {
                List<File> named = new LinkedList<>();
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

        private boolean changed(File candidate) {
            if (!candidate.isFile()) {
                return false;
            }
            FileFacts earlier = before.get(candidate.getName());
            // A HEURISTIC, deliberately, rather than a digest: a file counts as written when it was not
            // there when the publication was registered, or its length changed, or its modification time
            // changed, or it was touched at or after the instant the publication was registered. Hashing
            // every file in the watched directory instead would make one upload pay for every byte the
            // shard already holds, twice, inside the transaction.
            // Its limit: a rewrite to exactly the same length can go unnoticed where the filesystem's
            // lastModified() resolution is coarser than the interval between registration and the write,
            // because the recorded timestamp can then be unchanged AND below registeredAt. Content that
            // must be republished in that case is written under a new key - which uploaded content always
            // is, because the key ends in the immutable dataResourceId.
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
            if (length > MAX_PUBLISHED_FILE) {
                throw refuse(key, new IOException("the file holds " + length + " bytes, more than the "
                        + MAX_PUBLISHED_FILE + " bytes this seam publishes as one object"));
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
         * Records what the watched files held before anything was written: direct children and regular
         * files only, because an upload lands as a file in the directory the upload path named, and
         * descending further would make one upload's commit responsible for every file the deployment has
         * ever placed below that tree.
         *
         * @param directory the watched directory
         * @param names the watched file names, or null to watch every direct child
         * @return what each watched file held, by file name
         */
        private static Map<String, FileFacts> snapshot(File directory, Set<String> names) {
            Map<String, FileFacts> held = new LinkedHashMap<>();
            if (names != null) {
                for (String name : names) {
                    File child = new File(directory, name);
                    if (child.isFile()) {
                        held.put(name, new FileFacts(child.length(), child.lastModified()));
                    }
                }
                return held;
            }
            File[] children = directory.listFiles();
            if (children == null) {
                return held;
            }
            for (File child : children) {
                if (child.isFile()) {
                    held.put(child.getName(), new FileFacts(child.length(), child.lastModified()));
                }
            }
            return held;
        }
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
                     // The renderer is fixed to the theme's "screen" output type; no other output type is
                     // rendered from here.
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
        // This method writes the file to a character Appendable, so it handles text content only. Binary
        // content is served through getDataResourceStream instead; see the IMAGE_OBJECT handling above.

        if ("LOCAL_FILE".equals(dataResourceTypeId) && UtilValidate.isNotEmpty(objectInfo)) {
            File file = FileUtil.getFile(objectInfo);
            if (!file.isAbsolute()) {
                throw new GeneralException("File (" + objectInfo + ") is not absolute");
            }
            if (!readFromStore(dataResourceTypeId, file, rootDir) && !file.exists()) {
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
            if (!readFromStore(dataResourceTypeId, file, rootDir) && !file.exists()) {
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
            assertInsideContextRoot(file, rootDir);
            if (!readFromStore(dataResourceTypeId, file, rootDir) && !file.exists()) {
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
}
