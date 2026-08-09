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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.UtilHttp;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.entity.util.EntityUtilProperties;
import org.apache.ofbiz.security.SecuredUpload;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * DataEvents Class
 */
public class DataEvents {

    private static final String MODULE = DataEvents.class.getName();
    private static final String ERR_RESOURCE = "ContentErrorUiLabels";

    /**
     * The MIME types a browser will EXECUTE if it renders them as a document in this origin.
     *
     * <p>Content of these types is served as an attachment rather than inline, so that a file a user
     * uploaded cannot run script with the authority of the authenticated session that fetched it. Every
     * other type - images, PDFs, plain text - is served inline, exactly as before, because that is how
     * the screens embed it. Matched as a PREFIX, because a recorded MIME type carries parameters
     * ({@code text/html;charset=UTF-8}).
     */
    private static final List<String> ACTIVE_CONTENT_TYPES = List.of(
            "text/html", "application/xhtml+xml", "image/svg+xml", "application/xml", "text/xml",
            "application/xslt+xml", "text/javascript", "application/javascript", "application/ecmascript");

    public static String uploadImage(HttpServletRequest request, HttpServletResponse response) {
        return DataResourceWorker.uploadAndStoreImage(request, "dataResourceId", "imageData");
    }

    /**
     * Streams any binary content data to the browser.
     * <p>Supersedes {@link org.apache.ofbiz.content.data.DataEvents#serveImage(HttpServletRequest, HttpServletResponse) DataEvents#serveImage()}</p>
     */
    public static String serveObjectData(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        HttpSession session = request.getSession();
        Locale locale = UtilHttp.getLocale(request);

        GenericValue userLogin = (GenericValue) session.getAttribute("userLogin");
        String userAgent = request.getHeader("User-Agent");

        Map<String, Object> httpParams = UtilHttp.getParameterMap(request);
        String contentId = (String) httpParams.get("contentId");
        if (UtilValidate.isEmpty(contentId)) {
            String errorMsg = "Required parameter contentId not found!";
            Debug.logError(errorMsg, MODULE);
            request.setAttribute("_ERROR_MESSAGE_", errorMsg);
            return "error";
        }

        // get the permission service required for streaming data; default is always the genericContentPermission
        String permissionService = EntityUtilProperties.getPropertyValue("content", "stream.permission.service",
                "genericContentPermission", delegator);

        // For OFBIZ-11840, validate contentId using a strict allow-list instead of a
        // deny-list: entity keys must match [a-zA-Z0-9_:\-]{1,255}.  An allow-list
        // cannot be bypassed by encoding tricks or token splitting.
        if (!SecuredUpload.isValidEntityKey(contentId)) {
            Debug.logError("contentId parameter has invalid format, rejected for security reason", MODULE);
            return "success";
        }
        try {
            if (!SecuredUpload.isValidText(contentId, Collections.emptyList())) {
                Debug.logError("================== Not saved for security reason ==================", MODULE);
                return "success";
            }
        } catch (IOException e) {
            Debug.logError("================== Not saved for security reason ==================", MODULE);
            return "success";
        }

        // get the content record
        GenericValue content;
        try {
            content = EntityQuery.use(delegator).from("Content").where("contentId", contentId).queryOne();
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            request.setAttribute("_ERROR_MESSAGE_", e.getMessage());
            return "error";
        }

        // make sure content exists
        if (content == null) {
            String errorMsg = "No content found for Content ID: " + contentId;
            Debug.logError(errorMsg, MODULE);
            request.setAttribute("_ERROR_MESSAGE_", errorMsg);
            return "error";
        }

        // make sure there is a DataResource for this content
        String dataResourceId = content.getString("dataResourceId");
        if (UtilValidate.isEmpty(dataResourceId)) {
            String errorMsg = "No Data Resource found for Content ID: " + contentId;
            Debug.logError(errorMsg, MODULE);
            request.setAttribute("_ERROR_MESSAGE_", errorMsg);
            return "error";
        }

        // get the data RESOURCE
        GenericValue dataResource;
        try {
            dataResource = EntityQuery.use(delegator).from("DataResource").where("dataResourceId", dataResourceId).queryOne();
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            request.setAttribute("_ERROR_MESSAGE_", e.getMessage());
            return "error";
        }

        // make sure the data RESOURCE exists
        if (dataResource == null) {
            String errorMsg = "No Data Resource found for ID: " + dataResourceId;
            Debug.logError(errorMsg, MODULE);
            request.setAttribute("_ERROR_MESSAGE_", errorMsg);
            return "error";
        }

        // see if data RESOURCE is public or not
        String isPublic = dataResource.getString("isPublic");
        if (UtilValidate.isEmpty(isPublic)) {
            isPublic = "N";
        }

        // not public check security
        if (!"Y".equalsIgnoreCase(isPublic)) {
            // do security check
            Map<String, ? extends Object> permSvcCtx = UtilMisc.toMap("userLogin", userLogin, "locale", locale, "mainAction",
                    "VIEW", "contentId", contentId);
            Map<String, Object> permSvcResp;
            try {
                permSvcResp = dispatcher.runSync(permissionService, permSvcCtx);
            } catch (GenericServiceException e) {
                Debug.logError(e, MODULE);
                request.setAttribute("_ERROR_MESSAGE_", e.getMessage());
                return "error";
            }
            if (ServiceUtil.isError(permSvcResp)) {
                String errorMsg = ServiceUtil.getErrorMessage(permSvcResp);
                Debug.logError(errorMsg, MODULE);
                request.setAttribute("_ERROR_MESSAGE_", errorMsg);
                return "error";
            }

            // no service errors; now check the actual response
            Boolean hasPermission = (Boolean) permSvcResp.get("hasPermission");
            if (!hasPermission) {
                String errorMsg = (String) permSvcResp.get("failMessage");
                Debug.logError(errorMsg, MODULE);
                request.setAttribute("_ERROR_MESSAGE_", errorMsg);
                return "error";
            }
        }

        // get objects needed for data processing
        String contextRoot = (String) request.getAttribute("_CONTEXT_ROOT_");
        String webSiteId = (String) session.getAttribute("webSiteId");
        String dataName = dataResource.getString("dataResourceName");

        // get the mime type
        String mimeType = DataResourceWorker.getMimeType(dataResource);

        // hack for IE and mime types
        if (UtilValidate.isNotEmpty(userAgent) && userAgent.indexOf("MSIE") > -1) {
            Debug.logInfo("Found MSIE changing mime type from - " + mimeType, MODULE);
            mimeType = "application/octet-stream";
        }

        // for local resources; use HTTPS if we are requested via HTTPS
        String https = "false";
        String protocol = request.getProtocol();
        if ("https".equalsIgnoreCase(protocol)) {
            https = "true";
        }

        // get the data RESOURCE stream and content length
        Map<String, Object> resourceData;
        try {
            resourceData = DataResourceWorker.getDataResourceStream(dataResource, https, webSiteId, locale, contextRoot, false);
        } catch (IOException | GeneralException e) {
            Debug.logError(e, "Error getting DataResource stream", MODULE);
            request.setAttribute("_ERROR_MESSAGE_", e.getMessage());
            return "error";
        }

        // get the stream data
        InputStream stream = null;
        Long length = null;

        if (resourceData != null) {
            stream = (InputStream) resourceData.get("stream");
            length = (Long) resourceData.get("length");
        }
        Debug.logInfo("Got RESOURCE data stream: " + length + " bytes", MODULE);

        // stream the content to the browser
        if (stream != null && length != null) {
            try {
                UtilHttp.streamContentToBrowser(response, stream, length.intValue(), mimeType, dataName);
            } catch (IOException e) {
                Debug.logError(e, "Unable to write content to browser", MODULE);
                request.setAttribute("_ERROR_MESSAGE_", e.getMessage());
                // this must be handled with a special error string because the output stream has been already used and we will not be able
                // to return the error page;
                // the "io-error" should be associated to a response of type "none"
                return "io-error";
            }
        } else {
            String errorMsg = "No data is available.";
            Debug.logError(errorMsg, MODULE);
            request.setAttribute("_ERROR_MESSAGE_", errorMsg);
            return "error";
        }

        return "success";
    }

    /**
     * Streams ImageDataResource data to the output.
     * <p>Superseded by {@link org.apache.ofbiz.content.data.DataEvents#serveObjectData(HttpServletRequest, HttpServletResponse)
     * DataEvents#serveObjectData}</p>
     */
    @Deprecated
    public static String serveImage(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession();
        ServletContext application = session.getServletContext();

        Delegator delegator = (Delegator) request.getAttribute("delegator");
        Map<String, Object> parameters = UtilHttp.getParameterMap(request);

        Debug.logInfo("Img UserAgent - " + request.getHeader("User-Agent"), MODULE);

        String dataResourceId = (String) parameters.get("imgId");
        if (UtilValidate.isEmpty(dataResourceId)) {
            String errorMsg = "Error getting image record from db: " + " dataResourceId is empty";
            Debug.logError(errorMsg, MODULE);
            request.setAttribute("_ERROR_MESSAGE_", errorMsg);
            return "error";
        }

        // Held outside the resolution block, because the response is written only after the whole of it
        // has succeeded - see the comment on the transfer below.
        GenericValue dataResource;
        String mimeType;
        InputStream stream;
        Long length;
        try {
            dataResource = EntityQuery.use(delegator).from("DataResource").where("dataResourceId", dataResourceId).cache().queryOne();
            if (dataResource == null) {
                throw new GeneralException("No Data Resource found with ID [" + dataResourceId + "]");
            }
            if (!"Y".equals(dataResource.getString("isPublic"))) {
                // now require login...
                GenericValue userLogin = (GenericValue) session.getAttribute("userLogin");
                if (userLogin == null) {
                    String errorMsg = "You must be logged in to download the Data Resource with ID [" + dataResourceId + "]";
                    Debug.logError(errorMsg, MODULE);
                    request.setAttribute("_ERROR_MESSAGE_", errorMsg);
                    return "error";
                }

                // make sure the logged in user can download this content; otherwise is a pretty big security hole for DataResource records...
                // TODO: should we restrict the roleTypeId?
                long contentAndRoleCount = EntityQuery.use(delegator).from("ContentAndRole")
                        .where("partyId", userLogin.get("partyId"),
                                "dataResourceId", dataResourceId)
                        .queryCount();
                if (contentAndRoleCount == 0) {
                    String errorMsg = "You do not have permission to download the Data Resource with ID [" + dataResourceId
                            + "], ie you are not associated with it.";
                    Debug.logError(errorMsg, MODULE);
                    request.setAttribute("_ERROR_MESSAGE_", errorMsg);
                    return "error";
                }
            }

            mimeType = DataResourceWorker.getMimeType(dataResource);

            // hack for IE and mime types
            String userAgent = request.getHeader("User-Agent");
            if (userAgent != null && userAgent.indexOf("MSIE") > -1) {
                Debug.logInfo("Found MSIE changing mime type from - " + mimeType, MODULE);
                mimeType = "application/octet-stream";
            }

            // RESOLVED BEFORE THE RESPONSE IS TOUCHED, deliberately. This used to call
            // response.getOutputStream() first and only then resolve the content, which had two
            // consequences. A resource whose content could not be resolved at all - a row created by the
            // content screens whose file has not been uploaded yet, so it records no location, or a
            // location whose object the store no longer holds - failed AFTER the response had been
            // claimed as a binary stream, so the framework could not render its error view into it
            // ("ERROR in error page ... IllegalStateException") and the container answered a bare 500
            // over a response that had already begun. And the whole object was collapsed into a byte
            // array (see below), so nothing was streamed at all. Resolving first means the ordinary
            // failures are ordinary errors again, handled by this method's own error response.
            Map<String, Object> resourceData = DataResourceWorker.getDataResourceStream(dataResource, "",
                    application.getInitParameter("webSiteId"), UtilHttp.getLocale(request), application.getRealPath("/"), false);
            stream = (InputStream) resourceData.get("stream");
            length = resourceData.get("length") instanceof Long ? (Long) resourceData.get("length") : null;
            if (stream == null) {
                throw new GeneralException("The content of the Data Resource with ID [" + dataResourceId
                        + "] could not be opened");
            }
        } catch (GeneralException | IOException e) {
            String errMsg = "Error downloading digital product content: " + e.toString();
            Debug.logError(e, errMsg, MODULE);
            request.setAttribute("_ERROR_MESSAGE_", errMsg);
            return "error";
        }

        // STREAMED THROUGH A FIXED BUFFER, never collected into one. This was
        // os.write(IOUtils.toByteArray(stream)), which held the WHOLE object in the heap - and with a
        // content store configured an object may be as large as ContentStore.MAX_OBJECT_BYTES, so a
        // handful of concurrent reads of allowed-size content was enough to exhaust the shipped
        // -Xmx1024M and answer HTTP 500 with java.lang.OutOfMemoryError. InputStream.transferTo uses a
        // fixed internal buffer, so the memory a read costs no longer depends on the size of the
        // content or on how many reads are in flight. The source is closed either way, which the
        // previous code never did: with a store configured it is a remote response stream, and leaking
        // it holds a pooled connection open for the life of the instance.
        try (InputStream source = stream) {
            applyUserContentHeaders(response, dataResource, mimeType);
            if (mimeType != null) {
                response.setContentType(mimeType);
            }
            if (length != null && length >= 0L) {
                response.setContentLengthLong(length);
            }
            OutputStream os = response.getOutputStream();
            source.transferTo(os);
            os.flush();
        } catch (IOException e) {
            Debug.logError(e, "Unable to write the content of the Data Resource with ID [" + dataResourceId
                    + "] to the client", MODULE);
            // Nothing may be rendered into a response that has already begun - the reason
            // serveObjectData answers a response of type "none" in the same situation. When the
            // response has NOT been committed the buffer and the headers are dropped and a status is
            // sent, so the caller sees a failure rather than a short body; once it HAS been committed
            // the transfer is abandoned, and the caller sees fewer bytes than the Content-Length it was
            // promised, which is what tells it the content is incomplete. Either way no error view is
            // attempted, because attempting one is what turned a storage timeout into a container error
            // page written over a partial payload.
            if (!response.isCommitted()) {
                response.reset();
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            }
            return "success";
        }

        return "success";
    }

    /**
     * Applies the defence-in-depth headers a response carrying USER-SUPPLIED content needs.
     *
     * <p>The payload responses of this route carried no security headers at all, while every screen
     * response OFBiz renders carries the standard set. The content is uploaded by users and served from
     * the application's own origin, so the headers below are what keep a file that is not what its
     * recorded MIME type claims, or is an active format, from acting inside that origin:
     *
     * <ul>
     *   <li>{@code X-Content-Type-Options: nosniff} - the recorded MIME type is honoured as given
     *       rather than re-guessed from the bytes, which is how a file uploaded as text comes to be
     *       executed as script.</li>
     *   <li>{@code Content-Security-Policy} - for a payload NAVIGATED to as a document, nothing may
     *       load or execute; it does not affect an image loaded as a sub-resource of a screen, because
     *       a policy on a sub-resource response is not applied to the embedding document.</li>
     *   <li>{@code Content-Disposition} - {@code attachment} for the formats a browser will execute in
     *       this origin (HTML, XHTML, XML and SVG), {@code inline} for everything else, so that the
     *       images and documents the screens embed keep rendering exactly as they did.</li>
     *   <li>{@code X-Frame-Options} and {@code Referrer-Policy} - the payload may not be framed by
     *       another site, and navigating away from it does not disclose the URL, which carries the
     *       identifier of the content.</li>
     *   <li>{@code Cache-Control: private, no-store} for content that is NOT public, so an
     *       intermediary or a shared browser cache does not keep a copy of a resource whose delivery
     *       required a permission check. Public content is left cacheable, as it was.</li>
     * </ul>
     *
     * <p>{@code Strict-Transport-Security} is applied through the same switch every other OFBiz response
     * uses - {@code requestHandler.strict-transport-security}, on by default - rather than being decided
     * here. It is a HOST-wide directive with a lifetime, so a deployment that turns it off must have it
     * off everywhere, and a payload response that quietly kept sending it would defeat that.
     *
     * @param response the response to apply the headers to
     * @param dataResource the resource being served, whose {@code isPublic} and {@code mimeTypeId}
     *     decide the cache policy and the disposition
     * @param mimeType the MIME type the response will declare
     */
    private static void applyUserContentHeaders(HttpServletResponse response, GenericValue dataResource, String mimeType) {
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (UtilProperties.getPropertyAsBoolean("requestHandler", "strict-transport-security", true)) {
            response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
        response.setHeader("Content-Security-Policy", "default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'; sandbox");
        response.setHeader("X-Frame-Options", "SAMEORIGIN");
        response.setHeader("Referrer-Policy", "same-origin");
        if (!"Y".equals(dataResource.getString("isPublic"))) {
            response.setHeader("Cache-Control", "private, no-store");
        }
        response.setHeader("Content-Disposition",
                contentDisposition(mimeType, dataResource.getString("dataResourceName")));
    }

    /**
     * Returns the {@code Content-Disposition} header value for a payload of the given type and name.
     *
     * <p>{@code attachment} for the formats a browser executes when it renders them as a document in
     * this origin, {@code inline} for everything else - the images and documents the screens embed keep
     * being displayed rather than downloaded. Package-private so the decision can be tested on its own.
     *
     * @param mimeType the MIME type the response declares, which may be null or carry parameters
     * @param dataResourceName the recorded name of the content, which may be null or unusable
     * @return the header value
     */
    static String contentDisposition(String mimeType, String dataResourceName) {
        String type = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT).trim();
        boolean active = ACTIVE_CONTENT_TYPES.stream().anyMatch(type::startsWith);
        String name = safeDownloadName(dataResourceName);
        return (active ? "attachment" : "inline") + (name == null ? "" : "; filename=\"" + name + "\"");
    }

    /**
     * Returns a file name safe to put in a {@code Content-Disposition} header, or null when none is.
     *
     * <p>A recorded {@code dataResourceName} is user input: a quotation mark in it would end the
     * quoted-string early and let the rest of the name be read as further header parameters, and a
     * control character has no place in a header value at all. Only the characters a file name needs
     * are kept, and a name left with nothing is answered as null so the header carries no file name
     * rather than an empty one.
     *
     * @param name the recorded name
     * @return a safe file name, or null
     */
    static String safeDownloadName(String name) {
        if (UtilValidate.isEmpty(name)) {
            return null;
        }
        StringBuilder safe = new StringBuilder(name.length());
        for (char c : name.toCharArray()) {
            if (c >= ' ' && c != '"' && c != '\\' && c != 0x7f) {
                safe.append(c);
            }
        }
        String answer = safe.toString().trim();
        return answer.isEmpty() ? null : answer;
    }


    /** Dual create and edit event.
     *  Needed to make permission criteria available to services.
     */
    public static String persistDataResource(HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> result = null;
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        GenericValue userLogin = (GenericValue) request.getSession().getAttribute("userLogin");
        Map<String, Object> paramMap = UtilHttp.getParameterMap(request);
        String dataResourceId;
        GenericValue dataResource = delegator.makeValue("DataResource");
        dataResource.setPKFields(paramMap);
        dataResource.setNonPKFields(paramMap);
        Map<String, Object> serviceInMap = UtilMisc.makeMapWritable(dataResource);
        serviceInMap.put("userLogin", userLogin);
        String mode = (String) paramMap.get("mode");
        Locale locale = UtilHttp.getLocale(request);

        try {
            if (mode != null && "UPDATE".equals(mode)) {
                result = dispatcher.runSync("updateDataResource", serviceInMap);
                if (ServiceUtil.isError(result)) {
                    String errMsg = UtilProperties.getMessage(ERR_RESOURCE, "dataEvents.error_call_update_service", locale);
                    String errorMsg = ServiceUtil.getErrorMessage(result);
                    Debug.logError(errorMsg, MODULE);
                    request.setAttribute("_ERROR_MESSAGE_", errMsg);
                    return "error";
                }
            } else {
                mode = "CREATE";
                result = dispatcher.runSync("createDataResource", serviceInMap);
                if (ServiceUtil.isError(result)) {
                    String errMsg = UtilProperties.getMessage(ERR_RESOURCE, "dataEvents.error_call_create_service", locale);
                    String errorMsg = ServiceUtil.getErrorMessage(result);
                    Debug.logError(errorMsg, MODULE);
                    request.setAttribute("_ERROR_MESSAGE_", errMsg);
                    return "error";
                }
                dataResourceId = (String) result.get("dataResourceId");
                dataResource.set("dataResourceId", dataResourceId);
            }
        } catch (GenericServiceException e) {
            Debug.logError(e, MODULE);
            request.setAttribute("_ERROR_MESSAGE_", e.toString());
            return "error";
        }

        String returnStr = "success";
        if ("CREATE".equals(mode)) {
            // Set up return message to guide selection of follow on view
            request.setAttribute("dataResourceId", result.get("dataResourceId"));
            String dataResourceTypeId = (String) serviceInMap.get("dataResourceTypeId");
            if (dataResourceTypeId != null) {
                if ("ELECTRONIC_TEXT".equals(dataResourceTypeId)
                        || "IMAGE_OBJECT".equals(dataResourceTypeId)) {
                    returnStr = dataResourceTypeId;
                }
            }
        }

        return returnStr;
    }
}
