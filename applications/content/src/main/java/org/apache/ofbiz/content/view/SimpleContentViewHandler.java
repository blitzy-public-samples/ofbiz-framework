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
package org.apache.ofbiz.content.view;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.text.ParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilHttp;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.content.content.ContentWorker;
import org.apache.ofbiz.content.data.DataResourceWorker;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.entity.util.EntityUtilProperties;
import org.apache.ofbiz.security.SecuredFreemarker;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;
import org.apache.ofbiz.webapp.control.ConfigXMLReader;
import org.apache.ofbiz.webapp.view.AbstractViewHandler;
import org.apache.ofbiz.webapp.view.ViewHandlerException;
import org.apache.ofbiz.webapp.website.WebSiteWorker;

public class SimpleContentViewHandler extends AbstractViewHandler {

    private static final String MODULE = SimpleContentViewHandler.class.getName();
    private String rootDir = null;
    private String https = null;

    @Override
    public void init(ServletContext context) throws ViewHandlerException {
        rootDir = context.getRealPath("/");
        https = (String) context.getAttribute("https");
    }

    @Override
    public Map<String, Object> prepareViewContext(HttpServletRequest request, HttpServletResponse response, ConfigXMLReader.ViewMap viewMap) {
        List<String> fields = List.of("contentId", "rootContentId", "mapKey",
                "contentAssocTypeId", "fromDate", "dataResourceId",
                "contentRevisionSeqId", "mimeTypeId");
        Map<String, Object> context = new HashMap<>();
        fields.forEach(field -> context.put(field, request.getParameter(field)));
        return viewMap.isSecureContext()
                ? SecuredFreemarker.sanitizeParameterMap(context)
                : context;
    }

    /**
     * @see org.apache.ofbiz.webapp.view.ViewHandler#render(String, String, String, String, String, HttpServletRequest, HttpServletResponse, Map)
     */
    @Override
    public void render(String name, String page, String info, String contentType, String encoding, HttpServletRequest request,
                       HttpServletResponse response, Map<String, Object> context) throws ViewHandlerException {
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        HttpSession session = request.getSession();
        GenericValue userLogin = (GenericValue) session.getAttribute("userLogin");
        String contentId = (String) context.get("contentId");
        String rootContentId = (String) context.get("rootContentId");
        String mapKey = (String) context.get("mapKey");
        String contentAssocTypeId = (String) context.get("contentAssocTypeId");
        String fromDateStr = (String) context.get("fromDate");
        String dataResourceId = (String) context.get("dataResourceId");
        String contentRevisionSeqId = (String) context.get("contentRevisionSeqId");
        String mimeTypeId = (String) context.get("mimeTypeId");
        Locale locale = UtilHttp.getLocale(request);
        String webSiteId = WebSiteWorker.getWebSiteId(request);

        try {
            if (Debug.verboseOn()) {
                Debug.logVerbose("dataResourceId:" + dataResourceId, MODULE);
            }
            Delegator delegator = (Delegator) request.getAttribute("delegator");
            if (UtilValidate.isEmpty(dataResourceId)) {
                if (UtilValidate.isEmpty(contentRevisionSeqId)) {
                    if (UtilValidate.isEmpty(mapKey) && UtilValidate.isEmpty(contentAssocTypeId)) {
                        if (UtilValidate.isNotEmpty(contentId)) {
                            GenericValue content = EntityQuery.use(delegator).from("Content").where("contentId", contentId).cache().queryOne();
                            dataResourceId = content.getString("dataResourceId");
                        }
                        if (Debug.verboseOn()) {
                            Debug.logVerbose("dataResourceId:" + dataResourceId, MODULE);
                        }
                    } else {
                        Timestamp fromDate = null;
                        if (UtilValidate.isNotEmpty(fromDateStr)) {
                            try {
                                fromDate = UtilDateTime.stringToTimeStamp(fromDateStr, null, locale);
                            } catch (ParseException e) {
                                fromDate = UtilDateTime.nowTimestamp();
                            }
                        }
                        List<String> assocList = null;
                        if (UtilValidate.isNotEmpty(contentAssocTypeId)) {
                            assocList = UtilMisc.toList(contentAssocTypeId);
                        }
                        GenericValue content = ContentWorker.getSubContent(delegator, contentId, mapKey, null, null, assocList, fromDate);
                        dataResourceId = content.getString("dataResourceId");
                        if (Debug.verboseOn()) {
                            Debug.logVerbose("dataResourceId:" + dataResourceId, MODULE);
                        }
                    }
                } else {
                    GenericValue contentRevisionItem = EntityQuery.use(delegator)
                            .from("ContentRevisionItem")
                            .where("contentId", rootContentId, "itemContentId", contentId, "contentRevisionSeqId", contentRevisionSeqId)
                            .cache()
                            .queryOne();
                    if (contentRevisionItem == null) {
                        throw new ReportedFailure("ContentRevisionItem record not found for contentId=" + rootContentId
                                + ", contentRevisionSeqId=" + contentRevisionSeqId + ", itemContentId=" + contentId);
                    }
                    dataResourceId = contentRevisionItem.getString("newDataResourceId");
                    if (Debug.verboseOn()) {
                        Debug.logVerbose("contentRevisionItem:" + contentRevisionItem, MODULE);
                    }
                    if (Debug.verboseOn()) {
                        Debug.logVerbose("contentId=" + rootContentId + ", contentRevisionSeqId=" + contentRevisionSeqId
                                + ", itemContentId=" + contentId, MODULE);
                    }
                    if (Debug.verboseOn()) {
                        Debug.logVerbose("dataResourceId:" + dataResourceId, MODULE);
                    }
                }
            }
            if (UtilValidate.isNotEmpty(dataResourceId)) {
                GenericValue dataResource = EntityQuery.use(delegator).from("DataResource").where("dataResourceId",
                        dataResourceId).cache().queryOne();
                // DEJ20080717: why are we rendering the DataResource directly instead of rendering the content?
                ByteBuffer byteBuffer = DataResourceWorker.getContentAsByteBuffer(delegator, dataResourceId, https, webSiteId, locale, rootDir);
                ByteArrayInputStream bais = new ByteArrayInputStream(byteBuffer.array());
                // setup character encoding and content type
                String charset = dataResource.getString("characterSetId");
                if (UtilValidate.isEmpty(charset)) {
                    charset = encoding;
                }
                if (UtilValidate.isEmpty(mimeTypeId)) {
                    mimeTypeId = DataResourceWorker.getMimeType(dataResource);
                    if ("text/html".equalsIgnoreCase(mimeTypeId)) {
                        mimeTypeId = "application/octet-stream";
                    }
                }
                // setup content type
                String contentType2 = UtilValidate.isNotEmpty(mimeTypeId) ? mimeTypeId + "; charset=" + charset : contentType;
                String fileName = null;
                if (UtilValidate.isNotEmpty(dataResource.getString("dataResourceName"))) {
                    fileName = dataResource.getString("dataResourceName").replace(" ", "_"); // spaces in filenames can be a problem
                }

                // see if data RESOURCE is public or not
                String isPublic = dataResource.getString("isPublic");
                if (UtilValidate.isEmpty(isPublic)) {
                    isPublic = "N";
                }
                // get the permission service required for streaming data; default is always the genericContentPermission
                String permissionService = EntityUtilProperties.getPropertyValue("content", "stream.permission.service",
                        "genericContentPermission", delegator);

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
                        throw new ReportedFailure(e.getMessage());
                    }
                    if (ServiceUtil.isError(permSvcResp)) {
                        String errorMsg = ServiceUtil.getErrorMessage(permSvcResp);
                        Debug.logError(errorMsg, MODULE);
                        request.setAttribute("_ERROR_MESSAGE_", errorMsg);
                        throw new ReportedFailure(errorMsg);
                    }

                    // no service errors; now check the actual response
                    Boolean hasPermission = (Boolean) permSvcResp.get("hasPermission");
                    if (!hasPermission) {
                        String errorMsg = (String) permSvcResp.get("failMessage");
                        Debug.logError(errorMsg, MODULE);
                        request.setAttribute("_ERROR_MESSAGE_", errorMsg);
                        throw new ReportedFailure(errorMsg);
                    }
                }
                UtilHttp.streamContentToBrowser(response, bais, byteBuffer.limit(), contentType2, fileName);
            }
        } catch (IOException | GeneralException e) {
            // Logged here and reported as its message alone, because the message is all a reader can act on:
            // this handler serves content bytes, so the failures it meets are operational - a content store
            // that holds nothing yet, a location an allow list forbids - and the class and stack that carry
            // that detail belong in the log rather than on the page. See ReportedFailure below.
            Debug.logError(e, "Serving content through the view handler failed", MODULE);
            throw new ReportedFailure(e.getMessage());
        }
    }

    /**
     * The failure this handler reports, whose {@code toString()} is its message and nothing else.
     *
     * <p>{@code ControlServlet} renders {@code throwable.toString()} into the error page it serves, so a
     * plain {@link ViewHandlerException} puts its own fully qualified class name in front of the operator
     * message an end user reads - {@code org.apache.ofbiz.webapp.view.ViewHandlerException: ...} - while the
     * {@code stream} request that serves the very same content reports the message alone. Overriding
     * {@code toString()} here is what makes the two envelopes identical, and it discloses nothing about the
     * implementation behind the page (CWE-209). Every throwing site of this handler logs the failure it is
     * reporting first, so nothing is lost: the class, the cause and the stack are in the log, where an
     * operator looks for them.
     *
     * <p>Confined to this class on purpose. It is not a framework change: {@code ViewHandlerException}
     * behaves exactly as it always has for every other view handler.
     */
    private static final class ReportedFailure extends ViewHandlerException {

        private static final long serialVersionUID = 1L;

        ReportedFailure(String message) {
            super(message);
        }

        @Override
        public String toString() {
            String message = getMessage();
            return message == null ? super.toString() : message;
        }
    }
}
