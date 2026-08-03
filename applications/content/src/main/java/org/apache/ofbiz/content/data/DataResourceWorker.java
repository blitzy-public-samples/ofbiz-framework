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
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

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
        // Returned so that the peer the request is actually made to can be checked against the peer that was
        // authorised here; see requireValidatedPeer.
        return addresses;
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
     * <p>The connection itself is made through the unchanged URL, deliberately: rewriting it to the authorised
     * IP literal would send that literal as the {@code Host} header - {@code HttpURLConnection} treats
     * {@code Host} as a restricted header and ignores an attempt to set it - which breaks name-based virtual
     * hosting and, over TLS, certificate verification. Two things bind the connection to the authorised answer
     * instead: the JVM's positive DNS cache, which serves the connect that immediately follows the validation
     * from the very answer that was validated, and for {@code https} the certificate check, which a service on
     * a rebound private address cannot satisfy for the requested name. This check is what remains after those
     * two, and a deployment that wants the question closed entirely configures
     * {@code content.data.url.resource.allowed.hosts}.
     *
     * @param url the resource URL being fetched
     * @param validated the addresses that were authorised before the connection was made
     * @throws GeneralException if the host no longer resolves to the authorised addresses, or resolves to an
     *     address that may not be reached
     */
    private static void requireValidatedPeer(URL url, InetAddress[] validated) throws GeneralException {
        InetAddress[] current;
        try {
            current = InetAddress.getAllByName(url.getHost());
        } catch (UnknownHostException e) {
            throw new GeneralException("URL_RESOURCE host cannot be resolved: " + url.getHost());
        }
        if (current == null || current.length == 0) {
            throw new GeneralException("URL_RESOURCE host resolved to no addresses: " + url.getHost());
        }
        Set<String> authorised = new HashSet<>();
        for (InetAddress addr : validated) {
            authorised.add(addr.getHostAddress());
        }
        for (InetAddress addr : current) {
            checkNotPrivateOrReservedAddress(addr);
            if (!authorised.contains(addr.getHostAddress())) {
                throw new GeneralException("URL_RESOURCE host resolution changed while the request was being"
                        + " made, so the response is refused rather than read");
            }
        }
    }

    /**
     * Opens the response a {@code URL_RESOURCE} names, authorised, peer-checked, size-capped and owning its
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
        InetAddress[] validated = checkUrlResourceAllowed(url);
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
            con.connect();
            requireValidatedPeer(url, validated);
            if (con instanceof HttpURLConnection) {
                int responseCode = ((HttpURLConnection) con).getResponseCode();
                if (responseCode >= HTTP_REDIRECT_LOWEST && responseCode < HTTP_REDIRECT_ABOVE) {
                    throw new GeneralException("URL_RESOURCE request returned a redirect (" + responseCode
                            + "); redirects are not followed for security reasons");
                }
            }
            long contentLength = con.getContentLengthLong();
            if (contentLength > maxResponseSize) {
                throw new GeneralException("URL_RESOURCE response Content-Length (" + contentLength
                        + " bytes) exceeds the configured maximum of " + maxResponseSize + " bytes");
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
        // No content-storage seam here, deliberately. This method resolves a WRITE TARGET as often as it
        // resolves a read location: the registered createFile, createBinaryFile and updateBinaryFile services
        // take the File it returns and write to it directly, so the location it names has to be the one the
        // caller's bytes will land in, and an absent location has to stay final. Reading a provider's content
        // into that location instead would hand a writer a stale copy and then lose its write, and reporting an
        // absent location as present would let a writer believe a file it never created is there. The read paths
        // that may legitimately be served from a provider - renderFile and getDataResourceStream - carry the
        // resource identity a provider key needs and consult it themselves; see the class comment below.
        return resolveContentLocation(dataResourceTypeId, objectInfo, contextRoot, true);
    }

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
     * @param requireLocalPresence whether a location holding nothing locally is final. True for every caller
     *     that needs the local file itself, which is all of them unless a provider holds the content; false
     *     only for a read that has already established a provider is configured and is about to ask it, where
     *     absence on this disk is not yet an answer
     * @return the resolved location, or {@code null} for a type that is not file backed, exactly as
     *     {@link #getContentFile} has always returned for one
     * @throws GeneralException if the location is refused - a relative {@code LOCAL_FILE}, or a location outside
     *     an allow list or the context root, or an empty context root
     * @throws FileNotFoundException if the location holds nothing and {@code requireLocalPresence} is set
     */
    private static File resolveContentLocation(String dataResourceTypeId, String objectInfo, String contextRoot,
            boolean requireLocalPresence) throws GeneralException, FileNotFoundException {
        File file = null;

        if ("LOCAL_FILE".equals(dataResourceTypeId) || "LOCAL_FILE_BIN".equals(dataResourceTypeId)) {
            file = FileUtil.getFile(objectInfo);
            if (!file.exists() && requireLocalPresence) {
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
            if (!file.exists() && requireLocalPresence) {
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
            if (!file.exists() && requireLocalPresence) {
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
        // A caller of this frozen signature brings no resource identity, and identity is what a storage key is
        // derived from, so there is nothing to read through a provider and this reads locally exactly as it
        // always has. The overload below is the seam, and only the render path that holds the DataResource can
        // reach it.
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
     * @param delegator the delegator the resource was read through, carrying the tenant scope a provider key
     *     needs; null from the frozen public signature, which then never consults a provider
     * @param dataResourceId the immutable identifier of the resource; null as above
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
            if (renderThrough(store, delegator, dataResourceId, file, out, absent)) {
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
            if (renderThrough(store, delegator, dataResourceId, file, out, absent)) {
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
                File file = resolveContentLocation(dataResourceTypeId, objectInfo, contextRoot, store == null);
                if (file == null) {
                    throw new GeneralException("The dataResourceTypeId [" + dataResourceTypeId + "] names no file"
                            + " location; cannot stream");
                }
                Map<String, Object> streamed = streamThrough(store, dataResource.getDelegator(), dataResourceId,
                        file, !file.exists());
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
    // The helpers the seams above and the publication seam below delegate through. Two seams read - renderFile
    // copies content to an output, and getDataResourceStream hands a consumer a stream - and one writes:
    // publishToContentStore places content a service has just written where every instance can read it. Each
    // helper reports "nothing to do" when no provider is configured, so the committed default, DataResource
    // database storage with file-backed resources on the local filesystem, reaches none of it. All
    // object-storage behaviour itself lives in the store package; nothing here knows which provider is active
    // and nothing here touches a provider SDK.
    //
    // Where the write path is seamed, and why here. The services that create file-backed content resolve their
    // own target and write the bytes themselves: createFile builds a File from the objectInfo it computed and
    // writes to it, and createBinaryFile and updateBinaryFile take the File that getContentFile returns and
    // open their own FileOutputStream on it. None of them can hand its bytes to a provider on its own without
    // learning which provider is active, which is exactly the knowledge the plan confines to this seam and the
    // store package (plan sections 0.2.1 and 0.6.3). So each of them calls publishToContentStore once its write
    // has succeeded, and every decision - whether a provider is configured, whether it holds content apart from
    // the deployment's own tree, which key the content belongs under, and what bound applies - is taken here.
    // That is what makes the plan's upload story true of an object store as well: an upload is published as it
    // is written, so the round trip the plan requires of a configured store holds in both directions (plan
    // sections 0.1.1 goal 3 and 0.7.3). In filesystem mode publication is a no-op, because the provider's tree
    // is the deployment's own tree and the write has already landed in it by construction; in database mode
    // nothing is asked of a provider at all.
    // The seams are four, and together they are what makes an instance replaceable: renderFile and
    // getDataResourceStream read content through a provider, and storeContent and removeStoredContent place it
    // there and take it away again. The write pair is what the object-storage objective needs, because an
    // upload that lands on the instance that accepted it is durable local state by definition: another instance
    // cannot serve it, and replacing the instance loses it. The registered content services hold the bytes, so
    // they call storeContent from where they would otherwise have opened a FileOutputStream - a call, not a
    // change of contract: no service signature, no service definition and no entity field moves, and when the
    // call answers false, which is what the committed default answers, the local write below it is the one that
    // was always performed.
    //
    // Publishing and the metadata transaction. A published object and the DataResource row describing it have
    // to agree, and they are written to different places, so the publish is bound to the transaction that
    // records the row: registerPublishRollback asks the transaction manager to remove the object again if that
    // transaction rolls back. This is what makes an upload all-or-nothing across the two stores, and it is the
    // production path on which ContentStore.delete is exercised. It is registered only for a provider that
    // holds content off the instance, because the filesystem provider's tree IS the deployment's own tree,
    // where a rolled-back write has always left its file behind and parity means it still does.
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Publishes content a caller has just written at a file-backed location to the configured content storage
     * provider, so that every instance of a deployment can read it and not only the one that received it.
     *
     * <p>This is the file-oriented form of the write half of the storage seam (plan sections 0.1.1 goal 3,
     * 0.4.1 and 0.7.3), for a caller that has content at a location rather than in hand: it publishes what is
     * on disk. The registered content services hold their bytes, so they publish through
     * {@link #storeContent}, which is the same seam expressed for a caller that has not written anything yet;
     * both take every provider decision on the caller's behalf so that no service has to know which provider
     * is active, both derive the key through {@link ContentStoreFactory#storeKey}, and both bind the publish
     * to the transaction that records the row. This form is what an ingest, a migration or a caller outside
     * the content services uses.
     *
     * <p><strong>Inert unless an object store is configured.</strong> In database mode there is no provider and
     * nothing is asked of one. In filesystem mode the provider's tree is the deployment's own tree, so the write
     * the caller has just performed already landed in the provider and handing the same bytes over again would
     * only rewrite the file it wrote; {@link ContentStoreFactory#publicationRequired} is what says so. Only an
     * identity-keyed provider - a store that holds content apart from every instance - is published to.
     *
     * <p><strong>The bytes published are the bytes on disk.</strong> They are read back from the location the
     * caller wrote, so what the store holds is what the deployment's own validation accepted and what a local
     * read would return, rather than a second copy of a buffer that may have been transformed on its way to the
     * file. The read is bounded by {@code content.store.max.object.size}, the same ceiling a whole read is
     * bounded by, because content the store could hold but no consumer could then be served in one piece would
     * only look published (CWE-400).
     *
     * <p><strong>A failure is a failure of the write.</strong> Nothing is caught here: a caller that cannot
     * publish its content reports an error and lets its transaction roll back, so a {@code DataResource} row is
     * never committed for content the rest of the fleet cannot read. That is the whole point of publishing
     * inside the write rather than out of band afterwards.
     *
     * <p><strong>Content outside the deployment's own tree is not published.</strong> An absolute
     * {@code LOCAL_FILE} elsewhere on the host is state an operator placed deliberately, no storage key can be
     * derived for it, and the read seams read it from where it is; publication reports it and leaves it there,
     * exactly as a read would.
     *
     * @param delegator the delegator the content was written through, carrying the tenant scope; required when a
     *     provider that has to be published to is configured
     * @param dataResourceId the immutable identifier of the resource the content belongs to; required for the
     *     same reason, because it is what the storage key is derived from
     * @param file the location the caller has finished writing, or {@code null} when it wrote nothing
     * @throws GeneralException if a provider that has to be published to is configured but the resource identity
     *     it keys content by is missing, if the provider refuses the key, or if the content is larger than one
     *     read may hold
     * @throws IOException if the content cannot be read back from the location, or the provider cannot store it
     */
    public static void publishToContentStore(Delegator delegator, String dataResourceId, File file)
            throws GeneralException, IOException {
        publishContentFile(delegator, dataResourceId, file);
    }

    /**
     * Publishes content a caller has just written, and reports whether the configured provider took it.
     *
     * <p>The same seam as {@link #publishToContentStore}, in the form a caller that wants to know uses: it
     * answers {@code false} for every configuration in which there is nothing to publish - database mode,
     * where the bytes are in the row; a path-keyed provider, whose tree IS the deployment's own tree, so the
     * write already landed in it; and a location outside that tree, which no key can be derived for - and
     * {@code true} only when an object now holds the content. There is one implementation, so the two forms
     * cannot diverge.
     *
     * @param delegator the delegator the content was written through, carrying the tenant scope
     * @param dataResourceId the immutable identifier of the resource the content belongs to
     * @param file the location the caller has finished writing, or {@code null} when it wrote nothing
     * @return {@code true} when the content was published to a provider that holds it off this instance
     * @throws GeneralException if a provider that has to be published to is configured but the resource
     *     identity it keys content by is missing, if the provider refuses the key, or if the content is
     *     larger than one read may hold
     * @throws IOException if the content cannot be read back from the location, or the provider cannot store it
     */
    public static boolean publishContentFile(Delegator delegator, String dataResourceId, File file)
            throws GeneralException, IOException {
        if (file == null) {
            return false;
        }
        ContentStore store = ContentStoreFactory.getContentStore(delegator);
        if (!ContentStoreFactory.publicationRequired(store)) {
            return false;
        }
        if (delegator == null || UtilValidate.isEmpty(dataResourceId)) {
            throw new GeneralException("Content written for a file-backed resource cannot be published to the"
                    + " configured content store, because the resource identity the store keys content by was not"
                    + " supplied with it");
        }
        String relative = deploymentRelativePath(file);
        if (relative == null) {
            Debug.logWarning("The content of DataResource [" + dataResourceId + "] was written outside this"
                    + " deployment's own tree, so it is not published to the content store and only this instance"
                    + " can read it", MODULE);
            return false;
        }
        String key = ContentStoreFactory.storeKey(store, delegator, dataResourceId, relative);
        // Bound to the metadata transaction exactly as storeContent binds its own publish, and for the same
        // reason: the object and the DataResource row describing it are written to two places, only one of
        // which is transactional, so a transaction that does not commit has to take the object with it.
        // Registered BEFORE the object exists, so no published object is ever without its undo.
        registerPublishRollback(store, key, dataResourceId);
        store.put(key, contentToPublish(delegator, dataResourceId, file));
        Debug.logInfo("Published the content of DataResource [" + dataResourceId + "] to the content store under ["
                + key + "]", MODULE);
        return true;
    }

    /**
     * Removes content a caller has just abandoned from the configured provider, and reports whether the
     * provider held it.
     *
     * <p>The withdrawal half of {@link #publishContentFile}, and answered by the same rules: nothing is
     * withdrawn in database mode, from a path-keyed provider - whose file the deployment's own delete path
     * owns - or for a location outside the deployment's tree. Idempotent by the provider contract, so
     * withdrawing content that is not there succeeds.
     *
     * @param delegator the delegator the content was written through, carrying the tenant scope
     * @param dataResourceId the immutable identifier of the resource the content belongs to
     * @param file the location whose content is being withdrawn, or {@code null} when there is none
     * @return {@code true} when the provider no longer holds the content, {@code false} when no provider
     *     held it at all
     * @throws GeneralException if the resource identity is missing or the provider refuses the key
     * @throws IOException if the provider cannot be modified
     */
    public static boolean withdrawContentFile(Delegator delegator, String dataResourceId, File file)
            throws GeneralException, IOException {
        if (file == null) {
            return false;
        }
        ContentStore store = ContentStoreFactory.getContentStore(delegator);
        if (!ContentStoreFactory.publicationRequired(store)) {
            return false;
        }
        if (delegator == null || UtilValidate.isEmpty(dataResourceId)) {
            throw new GeneralException("Content written for a file-backed resource cannot be withdrawn from the"
                    + " configured content store, because the resource identity the store keys content by was not"
                    + " supplied with it");
        }
        String relative = deploymentRelativePath(file);
        if (relative == null) {
            return false;
        }
        store.delete(ContentStoreFactory.storeKey(store, delegator, dataResourceId, relative));
        return true;
    }

    /**
     * Reads back the content that is about to be published, refusing content larger than the configured ceiling.
     *
     * <p>Bounded twice, as every whole read in this refactor is: once from the size the filesystem reports, and
     * again as the content is read, because the location is one the deployment writes to and the size that was
     * measured can be stale by the time it is read.
     *
     * @param delegator the delegator the bound is resolved through
     * @param dataResourceId the immutable identifier of the resource, for the refusal
     * @param file the location the caller has finished writing
     * @return the content to hand to the provider
     * @throws GeneralException if the content is larger than one read may hold
     * @throws IOException if it cannot be read
     */
    private static byte[] contentToPublish(Delegator delegator, String dataResourceId, File file)
            throws GeneralException, IOException {
        long limit = ContentStoreFactory.maxObjectSize(delegator);
        if (file.length() > limit) {
            throw tooLargeToPublish(dataResourceId, limit);
        }
        try (InputStream content = Files.newInputStream(file.toPath(), StandardOpenOption.READ)) {
            byte[] read = content.readNBytes((int) limit);
            if (content.read() != -1) {
                throw tooLargeToPublish(dataResourceId, limit);
            }
            return read;
        }
    }

    /**
     * Reports content that cannot be published because it exceeds the configured whole-read ceiling.
     *
     * <p>Names the resource, the ceiling and the setting that governs it, and deliberately not the location the
     * content was written to: this message reaches the caller of a service and, through it, an end user
     * (CWE-200). The location is already in the caller's own log.
     *
     * @param dataResourceId the immutable identifier of the resource
     * @param limit the ceiling that was exceeded
     * @return the exception to throw
     */
    private static GeneralException tooLargeToPublish(String dataResourceId, long limit) {
        return new GeneralException("The content of DataResource [" + dataResourceId + "] is larger than the "
                + ContentStoreFactory.MAX_OBJECT_SIZE_PROPERTY + " ceiling of " + limit + " bytes, which is the"
                + " most one read may hold, so it cannot be published to the content store. Raise that ceiling"
                + " to store content this large.");
    }

    /**
     * Resolves the one content-storage provider that serves a single read operation.
     *
     * <p>Resolved once per operation and carried through it, so every branch and every helper of that operation
     * sees the same provider even if the configuration is changed while it runs, and so one operation can never
     * be served half from one provider and half from another.
     *
     * <p>Both parts of the resource's identity are required, because identity is what a storage key is derived
     * from: without them there is no key to read or write and the only correct answer is the local one. That is
     * what makes the frozen four-argument {@link #renderFile} signature behave exactly as it did before this
     * work.
     *
     * @param delegator the delegator the resource was read or written through, carrying the tenant scope
     * @param dataResourceId the immutable identifier of the resource
     * @return the active provider, or {@code null} when content is held in the {@code DataResource} database
     *     columns - the committed default - or when the caller brought no resource identity
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
     * Reports whether a configured provider holds the content of a file-backed location, and is therefore where
     * a write to it has to go.
     *
     * <p>Asked by a content service before it writes, so that it can prepare the bytes the way the provider
     * needs them - validated on a staged copy rather than on a file in the deployment tree - and so that a
     * deployment with no provider configured takes exactly the local path it always took. It resolves the
     * location through the same authorisation the read paths use and derives the same key, so "the provider
     * holds this" means the identical thing to both halves of the seam.
     *
     * @param delegator the delegator the resource is written through, carrying the tenant scope
     * @param dataResourceId the immutable identifier of the resource
     * @param dataResourceTypeId the {@code DataResource.dataResourceTypeId}
     * @param objectInfo the {@code DataResource.objectInfo} location
     * @param contextRoot the webapp context root, required by the {@code CONTEXT_FILE} types
     * @return {@code true} when a provider holds this location's content
     * @throws GeneralException if the location is refused, or a provider is selected but unusable
     */
    public static boolean contentStoreHolds(Delegator delegator, String dataResourceId, String dataResourceTypeId,
            String objectInfo, String contextRoot) throws GeneralException {
        return storeKeyFor(delegator, dataResourceId, dataResourceTypeId, objectInfo, contextRoot) != null;
    }

    /**
     * Resolves the location a write is aimed at, requiring it to exist locally only when no provider holds the
     * content.
     *
     * <p>{@link #getContentFile} is a read resolution and treats an absent location as final, which is right
     * for every caller that needs the local file itself. A write to a provider-backed resource does not: the
     * bytes are going to the provider, and the local location exists only to be authorised and to name the key.
     * Every authorisation {@code getContentFile} applies is applied here, through the same code, so a location
     * an allow list or a context root forbids is refused for a write exactly as it is for a read.
     *
     * @param delegator the delegator the resource is written through
     * @param dataResourceId the immutable identifier of the resource
     * @param dataResourceTypeId the {@code DataResource.dataResourceTypeId}
     * @param objectInfo the {@code DataResource.objectInfo} location
     * @param contextRoot the webapp context root, required by the {@code CONTEXT_FILE} types
     * @return the resolved location, or {@code null} for a type that is not file backed
     * @throws GeneralException if the location is refused, or a provider is selected but unusable
     * @throws FileNotFoundException if the location holds nothing and no provider holds the content either,
     *     which is the pre-existing behaviour of {@link #getContentFile} for exactly that case
     */
    public static File getContentWriteFile(Delegator delegator, String dataResourceId, String dataResourceTypeId,
            String objectInfo, String contextRoot) throws GeneralException, FileNotFoundException {
        ContentStore store = storeForResource(delegator, dataResourceId);
        return resolveContentLocation(dataResourceTypeId, objectInfo, contextRoot, store == null);
    }

    /**
     * Stores content for a file-backed {@code DataResource} in the configured provider.
     *
     * <p>The write half of the seam. It is what keeps an upload off the instance that accepted it: the bytes go
     * to the provider every instance of the deployment reaches, under the key both read seams derive for the
     * same resource, so the content is servable by any instance and survives the one that received it.
     *
     * <p>Answers {@code false} without touching anything when no provider holds this location's content -
     * database storage, which is the committed default, and any location outside the deployment tree - and the
     * caller then performs the local write it has always performed. That is the whole of the backward
     * compatibility: an unconfigured deployment reaches the {@code false} return and nothing else here.
     *
     * <p>Bound to the metadata transaction. When the provider holds content off this instance, a rollback
     * synchronisation is registered so that a transaction which records no {@code DataResource} row leaves no
     * object behind either. Registration failure is a refusal rather than a warning: an object published
     * without that binding is one nothing will ever remove.
     *
     * @param delegator the delegator the resource is written through, carrying the tenant scope
     * @param dataResourceId the immutable identifier of the resource
     * @param dataResourceTypeId the {@code DataResource.dataResourceTypeId}
     * @param objectInfo the {@code DataResource.objectInfo} location
     * @param contextRoot the webapp context root, required by the {@code CONTEXT_FILE} types
     * @param content the complete content to store; never null, and may be empty
     * @return {@code true} when the provider now holds the content, {@code false} when the caller must perform
     *     its own local write
     * @throws GeneralException if the location is refused, the provider is unusable, the content is null, or
     *     the publish could not be bound to the transaction that records the metadata
     * @throws IOException if the provider cannot be written to
     */
    public static boolean storeContent(Delegator delegator, String dataResourceId, String dataResourceTypeId,
            String objectInfo, String contextRoot, byte[] content) throws GeneralException, IOException {
        if (content == null) {
            throw new GeneralException("Cannot store null content for dataResourceId [" + dataResourceId + "]");
        }
        ContentStore store = storeForResource(delegator, dataResourceId);
        String key = storeKeyFor(store, delegator, dataResourceId, dataResourceTypeId, objectInfo, contextRoot);
        if (key == null) {
            return false;
        }
        // Registered BEFORE the object exists, deliberately. Registering afterwards would leave a window in
        // which a published object had no undo, and a registration that fails after the publish would leave one
        // permanently: this way a transaction manager that cannot accept the synchronisation costs the upload
        // rather than orphaning an object in the bucket.
        registerPublishRollback(store, key, dataResourceId);
        store.put(key, content);
        Debug.logInfo("Published " + content.length + " bytes of content for dataResourceId [" + dataResourceId
                + "] to the configured content store", MODULE);
        return true;
    }

    /**
     * Removes the content a file-backed {@code DataResource} holds in the configured provider.
     *
     * <p>The removal half of the seam, and idempotent by the provider's contract: removing content that is not
     * there succeeds. Answers {@code false} without touching anything when no provider holds this location's
     * content, so a caller that also has a local file to remove keeps doing exactly that.
     *
     * @param delegator the delegator the resource is written through, carrying the tenant scope
     * @param dataResourceId the immutable identifier of the resource
     * @param dataResourceTypeId the {@code DataResource.dataResourceTypeId}
     * @param objectInfo the {@code DataResource.objectInfo} location
     * @param contextRoot the webapp context root, required by the {@code CONTEXT_FILE} types
     * @return {@code true} when the provider no longer holds the content, {@code false} when no provider holds
     *     it at all
     * @throws GeneralException if the location is refused or the provider is unusable
     * @throws IOException if the provider cannot be modified
     */
    public static boolean removeStoredContent(Delegator delegator, String dataResourceId,
            String dataResourceTypeId, String objectInfo, String contextRoot)
            throws GeneralException, IOException {
        ContentStore store = storeForResource(delegator, dataResourceId);
        String key = storeKeyFor(store, delegator, dataResourceId, dataResourceTypeId, objectInfo, contextRoot);
        if (key == null) {
            return false;
        }
        store.delete(key);
        return true;
    }

    /**
     * Derives the provider key for a file-backed location, resolving the provider first.
     *
     * @param delegator the delegator the resource is read or written through
     * @param dataResourceId the immutable identifier of the resource
     * @param dataResourceTypeId the {@code DataResource.dataResourceTypeId}
     * @param objectInfo the {@code DataResource.objectInfo} location
     * @param contextRoot the webapp context root, required by the {@code CONTEXT_FILE} types
     * @return the provider key, or {@code null} when no provider holds this location's content
     * @throws GeneralException if the location is refused or the provider is unusable
     */
    private static String storeKeyFor(Delegator delegator, String dataResourceId, String dataResourceTypeId,
            String objectInfo, String contextRoot) throws GeneralException {
        return storeKeyFor(storeForResource(delegator, dataResourceId), delegator, dataResourceId,
                dataResourceTypeId, objectInfo, contextRoot);
    }

    /**
     * Derives the provider key for a file-backed location through an already resolved provider.
     *
     * <p>The location is resolved and authorised before any key exists, through the same code
     * {@link #getContentFile} uses, so a location an operator has forbidden costs no provider request; and the
     * key itself comes from {@link #readKey}, the one derivation both read seams use, so a resource written
     * under this key is a resource they read back.
     *
     * @param store the resolved provider, or {@code null} for database storage
     * @param delegator the delegator the resource is read or written through
     * @param dataResourceId the immutable identifier of the resource
     * @param dataResourceTypeId the {@code DataResource.dataResourceTypeId}
     * @param objectInfo the {@code DataResource.objectInfo} location
     * @param contextRoot the webapp context root, required by the {@code CONTEXT_FILE} types
     * @return the provider key, or {@code null} when no provider holds this location's content
     * @throws GeneralException if the location is refused or the provider is unusable
     */
    private static String storeKeyFor(ContentStore store, Delegator delegator, String dataResourceId,
            String dataResourceTypeId, String objectInfo, String contextRoot) throws GeneralException {
        if (store == null || UtilValidate.isEmpty(objectInfo)) {
            return null;
        }
        File file;
        try {
            file = resolveContentLocation(dataResourceTypeId, objectInfo, contextRoot, false);
        } catch (FileNotFoundException absent) {
            // Cannot happen with requireLocalPresence false, and is reported rather than swallowed if the
            // resolution ever changes: silently answering "no provider holds this" would send content local.
            throw new GeneralException("The location [" + objectInfo + "] of dataResourceId [" + dataResourceId
                    + "] could not be resolved", absent);
        }
        return file == null ? null : readKey(store, delegator, dataResourceId, file);
    }

    /**
     * Binds a publish to the transaction that records the metadata describing it.
     *
     * <p>An object store and the {@code DataResource} row are two stores, and only one of them is transactional.
     * Without this, a service that published an upload and then failed - a permission check, an ECA, a
     * constraint, anything after the bytes were sent - would leave an object in the bucket that no row refers
     * to: content nobody can reach and nothing will remove, accumulating on every retry. So the removal is
     * registered as a rollback action of the current transaction, which is the same mechanism the entity engine
     * uses for its own after-transaction work.
     *
     * <p>Only for a provider that holds content off this instance. The filesystem provider's storage tree is
     * the deployment's own tree, and a rolled-back local write has always left its file behind; removing it now
     * would be a behaviour change in the one mode whose whole purpose is to behave as it always did.
     *
     * <p>No transaction, no registration, and that is correct rather than a gap:
     * {@code TransactionUtil.registerSynchronization} does nothing when no transaction is active, which is a
     * caller that is not recording a row either.
     *
     * @param store the provider the content is being published to, never null
     * @param key the key the content is published under
     * @param dataResourceId the resource being published, for the diagnostic
     * @throws GeneralException if the synchronisation cannot be registered, which is refused rather than
     *     ignored because an unbound publish is an object nothing will ever remove
     */
    private static void registerPublishRollback(ContentStore store, String key, String dataResourceId)
            throws GeneralException {
        if (!ContentStoreFactory.holdsContentOffInstance(store)) {
            return;
        }
        try {
            TransactionUtil.registerSynchronization(new Synchronization() {
                @Override
                public void beforeCompletion() {
                    // Nothing: the object is already published, and this exists only to undo it.
                }

                @Override
                public void afterCompletion(int status) {
                    if (status == Status.STATUS_COMMITTED) {
                        return;
                    }
                    try {
                        store.delete(key);
                        Debug.logInfo("The transaction recording dataResourceId [" + dataResourceId + "] did not"
                                + " commit, so the content published for it was removed from the content store",
                                MODULE);
                    } catch (GeneralException | IOException e) {
                        // Reported and swallowed: this runs after the transaction has completed, where there is
                        // nothing left to fail. What is left behind is an unreferenced object, which the message
                        // names so that it can be removed - either by hand or by the store's own lifecycle rules.
                        Debug.logError(e, "The transaction recording dataResourceId [" + dataResourceId + "] did"
                                + " not commit and the content published for it could not be removed from the"
                                + " content store. It is unreferenced and should be removed.", MODULE);
                    }
                }
            });
        } catch (GenericTransactionException e) {
            throw new GeneralException("The content for dataResourceId [" + dataResourceId + "] was not published,"
                    + " because the removal that undoes it if this transaction rolls back could not be"
                    + " registered", e);
        }
    }

    /**
     * Derives the key the active provider holds a resolved location's content under.
     *
     * <p>The derivation itself belongs to {@link ContentStoreFactory#storeKey}, which knows what the active
     * provider is keyed by: immutable identity for an object store, the {@code ofbiz.home}-relative path for a
     * path-keyed provider. What is decided here is the prior question of whether the provider can hold this
     * content at all. A location outside {@code ofbiz.home} is host-local state an operator placed deliberately,
     * so it has no place in a provider and is read from where it is.
     *
     * @param store the provider serving this operation, never null
     * @param delegator the delegator the resource was read through
     * @param dataResourceId the immutable identifier of the resource
     * @param file the resolved, already authorised location
     * @return the provider key, or {@code null} when the location lies outside {@code ofbiz.home} and is
     *     therefore not provider-backed content
     * @throws GeneralException if the provider refuses to derive a key from this resource's identity
     */
    private static String readKey(ContentStore store, Delegator delegator, String dataResourceId, File file)
            throws GeneralException {
        String relative = deploymentRelativePath(file);
        if (relative == null) {
            return null;
        }
        return ContentStoreFactory.storeKey(store, delegator, dataResourceId, relative);
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
     * @param delegator the delegator the resource was read through
     * @param dataResourceId the immutable identifier of the resource
     * @param file the resolved, already authorised location
     * @param out the output to render into
     * @param absentLocally whether the location holds nothing on this instance's disk
     * @return {@code true} when the content was rendered from the provider, {@code false} when the caller must
     *     perform the local read it has always performed
     * @throws GeneralException if the provider cannot be reached or refuses the key, or if it holds nothing and
     *     a local copy may not answer in its place
     * @throws IOException if the content cannot be read or the output cannot be written
     */
    private static boolean renderThrough(ContentStore store, Delegator delegator, String dataResourceId, File file,
            Appendable out, boolean absentLocally) throws GeneralException, IOException {
        if (store == null) {
            return false;
        }
        String key = readKey(store, delegator, dataResourceId, file);
        if (key == null) {
            return false;
        }
        ContentStore.ContentStream stored;
        try {
            // Opening is kept apart from copying so that absence, which is an answer, is never confused with a
            // read failure part way through an output that has already been written to.
            stored = store.openStream(key);
        } catch (FileNotFoundException absent) {
            refuseUnlessLocalCopyMayAnswer(key, absentLocally, delegator, absent);
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
     * @param delegator the delegator the resource was read through
     * @param dataResourceId the immutable identifier of the resource
     * @param file the resolved, already authorised location
     * @param absentLocally whether the location holds nothing on this instance's disk
     * @return the {@code stream} and {@code length} pair {@link #getDataResourceStream} returns, or {@code null}
     *     when the caller must perform the local read it has always performed
     * @throws GeneralException if the provider cannot be reached, refuses the key, or holds nothing while a
     *     local copy may not answer in its place
     * @throws IOException if the content cannot be opened
     */
    private static Map<String, Object> streamThrough(ContentStore store, Delegator delegator, String dataResourceId,
            File file, boolean absentLocally) throws GeneralException, IOException {
        if (store == null) {
            return null;
        }
        String key = readKey(store, delegator, dataResourceId, file);
        if (key == null) {
            return null;
        }
        ContentStore.ContentStream content;
        try {
            content = store.openStream(key);
        } catch (FileNotFoundException absent) {
            refuseUnlessLocalCopyMayAnswer(key, absentLocally, delegator, absent);
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
     * since the provider was configured is published to it as it is written, so this case is content that
     * predates the provider - shipped content, or an upload made before it was switched on - and refusing it
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
     * @param key the provider key that holds nothing
     * @param absentLocally whether the location holds nothing on this instance's disk either
     * @param delegator the delegator the fallback setting is resolved through
     * @param absent the provider's report of absence, kept as the cause and for verbose logging
     * @throws GeneralException when a local copy exists but may not answer for the provider
     */
    private static void refuseUnlessLocalCopyMayAnswer(String key, boolean absentLocally, Delegator delegator,
            FileNotFoundException absent) throws GeneralException {
        if (absentLocally) {
            Debug.logVerbose(absent, "The configured content store holds nothing under [" + key + "] and this"
                    + " instance holds no copy either, so the content does not exist", MODULE);
            return;
        }
        if (!ContentStoreFactory.localFallbackEnabled(delegator)) {
            throw new GeneralException("The configured content store holds no content under [" + key + "] while a"
                    + " local copy of it exists. The store is the authority for this content, so the local copy"
                    + " is not served in its place. Place the content in the store, or set"
                    + " content.store.local.fallback=true to allow local copies to answer while content is"
                    + " migrated into it.", absent);
        }
        if (REPORTED_FALLBACK_KEYS.size() < REPORTED_FALLBACK_KEY_LIMIT && REPORTED_FALLBACK_KEYS.add(key)) {
            Debug.logWarning(absent, "The configured content store holds nothing under [" + key + "], so the"
                    + " local copy answers for it because content.store.local.fallback allows it. Content"
                    + " written since the store was configured is published to it as it is written, so this"
                    + " content predates the store and belongs copied into it.", MODULE);
            return;
        }
        Debug.logVerbose(absent, "The configured content store holds nothing under [" + key + "], so the local"
                + " copy answers for it because content.store.local.fallback allows it", MODULE);
    }
}
