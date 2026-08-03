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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.imaging.ImageReadException;
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilGenerics;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.security.SecuredUpload;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.ModelService;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * DataServices Class
 */
public class DataServices {

    private static final String MODULE = DataServices.class.getName();
    private static final String RESOURCE = "ContentUiLabels";

    /** The label resource the upload-validation refusals below are worded from. */
    private static final String SECURITY_RESOURCE = "SecurityUiLabels";

    /**
     * Publishes a file-backed {@code DataResource}'s content to the configured content store, when one holds it.
     *
     * <p>This is the whole of the write half of the content-store seam as the services see it: one call, placed
     * where each of them would otherwise open a stream on a local file. It exists here, once, rather than four
     * times inline, because the sequence has to be identical everywhere - decide whether a provider holds this
     * content, validate the bytes, then publish - and because getting the ORDER wrong would matter: nothing
     * unvalidated may reach a shared store, where it would be readable by every instance of the deployment.
     *
     * <p>The validation is the same {@code SecuredUpload} check the local write performs, applied to a staged
     * copy of the bytes rather than to a file in the deployment tree. The staged copy keeps the target's file
     * name extension, because that is part of what the check reads, so a payload accepted here is one the local
     * path would also have accepted. The staging file is removed as soon as the check is done, whichever way it
     * went, so nothing durable is left on this instance - which is the point of publishing at all.
     *
     * @param delegator the delegator the resource is written through, carrying the tenant scope
     * @param dataResourceId the immutable identifier of the resource; a resource without one is never
     *     provider-backed, because identity is what an object key is derived from
     * @param dataResourceTypeId the {@code DataResource.dataResourceTypeId}
     * @param objectInfo the {@code DataResource.objectInfo} location
     * @param rootDir the webapp context root, required by the {@code CONTEXT_FILE} types
     * @param target the resolved local location, used for the staged copy's file name
     * @param content the bytes to publish; null or empty means the caller has nothing to publish
     * @param uploadKind the {@code SecuredUpload} file kind the caller validates with
     * @param refusalLabel the {@code SecurityUiLabels} property naming the accepted formats
     * @param failureLabel the {@code ContentUiLabels} property naming a write failure
     * @param locale the locale every message is resolved in
     * @return {@code null} when no provider holds this content and the caller must perform its own local write;
     *     otherwise the service result to return - success when the content was published, an error when it was
     *     refused or could not be published
     */
    private static Map<String, Object> publishThroughContentStore(Delegator delegator, String dataResourceId,
            String dataResourceTypeId, String objectInfo, String rootDir, File target, byte[] content,
            String uploadKind, String refusalLabel, String failureLabel, Locale locale) {
        if (content == null || content.length == 0 || UtilValidate.isEmpty(dataResourceId)) {
            return null;
        }
        try {
            if (!DataResourceWorker.contentStoreHolds(delegator, dataResourceId, dataResourceTypeId, objectInfo,
                    rootDir)) {
                return null;
            }
            if (!stagedContentIsValid(content, target, uploadKind, delegator)) {
                return ServiceUtil.returnError(UtilProperties.getMessage(SECURITY_RESOURCE, refusalLabel, locale));
            }
            DataResourceWorker.storeContent(delegator, dataResourceId, dataResourceTypeId, objectInfo, rootDir,
                    content);
            return ServiceUtil.returnSuccess();
        } catch (GeneralException | IOException | ImageReadException e) {
            // The same shape the local write reports a failure with, so a caller sees one kind of error whichever
            // backend holds the content. The provider's own diagnostic is already in the log, sanitised.
            Debug.logError(e, "Could not publish the content of dataResourceId [" + dataResourceId + "] to the"
                    + " configured content store", MODULE);
            return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, failureLabel,
                    UtilMisc.toMap("fileName", objectInfo), locale));
        }
    }

    /**
     * Publishes a file an image service has just written, and reports a failure as that service's own error.
     *
     * <p>The image family is the one write path that cannot hand its bytes over instead of writing them: it
     * derives the file name from {@code image.file.name.format}, writes the image, and only then has a
     * {@code DataResource} row to key an object with. So the local write stays, and the file is published
     * afterwards - which leaves the same guarantee as the four {@code *File*} services, by a different route:
     * every instance of the deployment can read what one of them received.
     *
     * <p>Inert unless a provider holds this content off the instance.
     * {@link DataResourceWorker#publishContentFile} answers {@code false} for database storage - the committed
     * default - and for the filesystem provider, whose tree the write already landed in.
     *
     * <p>A failure is the service's failure. Reporting success for content only the receiving instance can
     * read is the defect this whole seam exists to remove, so a publish that cannot be performed refuses the
     * write rather than leaving the row pointing at a file the rest of the fleet cannot reach.
     *
     * @param delegator the delegator the resource was written through, carrying the tenant scope
     * @param dataResourceId the immutable identifier of the resource
     * @param file the file the service wrote
     * @param locale the locale the error message is resolved in
     * @return {@code null} when there is nothing to publish or the publish succeeded, otherwise the service
     *     error to return
     */
    private static Map<String, Object> publishToContentStore(Delegator delegator, String dataResourceId, File file,
            Locale locale) {
        try {
            DataResourceWorker.publishContentFile(delegator, dataResourceId, file);
            return null;
        } catch (GeneralException | IOException e) {
            Debug.logError(e, "The content written for dataResourceId [" + dataResourceId + "] could not be"
                    + " published to the configured content store, so this write is refused rather than left"
                    + " readable only by this instance", MODULE);
            return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "ContentUnableWriteBinaryDataToFile",
                    UtilMisc.toMap("fileName", file.getAbsolutePath()), locale));
        }
    }

    /**
     * Runs the upload validation on a staged copy of the bytes about to be published.
     *
     * <p>{@code SecuredUpload.isValidFile} reads a file, and it reads the file NAME as well as the content, so
     * the staged copy is given the target's extension. Written with owner-only permissions where the platform
     * supports it - {@code Files.createTempFile} does that on POSIX - and removed in a finally block, so an
     * upload that is refused leaves nothing behind and one that is accepted leaves nothing either.
     *
     * @param content the bytes to validate
     * @param target the resolved location, whose file name extension the staged copy takes
     * @param uploadKind the {@code SecuredUpload} file kind to validate as
     * @param delegator the delegator the validation reads its configuration through
     * @return true when the content is acceptable
     * @throws IOException if the staged copy cannot be written
     * @throws ImageReadException if the validation cannot read the content as the kind it claims to be
     */
    private static boolean stagedContentIsValid(byte[] content, File target, String uploadKind, Delegator delegator)
            throws IOException, ImageReadException {
        String name = target == null ? "" : target.getName();
        int dot = name.lastIndexOf('.');
        Path staged = Files.createTempFile(null, dot >= 0 ? name.substring(dot) : null);
        try {
            Files.write(staged, content);
            return SecuredUpload.isValidFile(staged.toString(), uploadKind, delegator);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    /**
     * Reads the resource identity from a service context, whichever way the caller supplied it.
     *
     * <p>{@code createFile} declares {@code dataResourceId} through the {@code createDataResource} service it
     * implements, and its callers often pass the whole {@code DataResource} value as well; either is the same
     * identity, and the content-store key is derived from it, so both are accepted here rather than in each
     * service.
     *
     * @param context the service context
     * @return the {@code dataResourceId}, or null when the context carries no identity
     */
    private static String dataResourceId(Map<String, ? extends Object> context) {
        String supplied = (String) context.get("dataResourceId");
        if (UtilValidate.isNotEmpty(supplied)) {
            return supplied;
        }
        GenericValue dataResource = (GenericValue) context.get("dataResource");
        return dataResource == null ? null : dataResource.getString("dataResourceId");
    }

    public static Map<String, Object> clearAssociatedRenderCache(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        String dataResourceId = (String) context.get("dataResourceId");
        Locale locale = (Locale) context.get("locale");
        try {
            DataResourceWorker.clearAssociatedRenderCache(delegator, dataResourceId);
        } catch (GeneralException e) {
            Debug.logError(e, "Unable to clear associated render cache with dataResourceId=" + dataResourceId, MODULE);
            return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "ContentClearAssociatedRenderCacheError",
                    UtilMisc.toMap("dataResourceId", dataResourceId), locale));
        }
        return ServiceUtil.returnSuccess();
    }

    /**
     * A top-level service for creating a DataResource and ElectronicText together.
     */
    public static Map<String, Object> createDataResourceAndText(DispatchContext dctx, Map<String, ? extends Object> rcontext) {
        Map<String, Object> context = UtilMisc.makeMapWritable(rcontext);
        Map<String, Object> result = new HashMap<>();

        Map<String, Object> thisResult = createDataResourceMethod(dctx, context);
        if (thisResult.get(ModelService.RESPONSE_MESSAGE) != null) {
            return ServiceUtil.returnError((String) thisResult.get(ModelService.ERROR_MESSAGE));
        }

        result.put("dataResourceId", thisResult.get("dataResourceId"));
        context.put("dataResourceId", thisResult.get("dataResourceId"));

        String dataResourceTypeId = (String) context.get("dataResourceTypeId");
        if (dataResourceTypeId != null && "ELECTRONIC_TEXT".equals(dataResourceTypeId)) {
            thisResult = createElectronicText(dctx, context);
            if (thisResult.get(ModelService.RESPONSE_MESSAGE) != null) {
                return ServiceUtil.returnError((String) thisResult.get(ModelService.ERROR_MESSAGE));
            }
        }

        return result;
    }

    /**
     * A service wrapper for the createDataResourceMethod method. Forces permissions to be checked.
     */
    public static Map<String, Object> createDataResource(DispatchContext dctx, Map<String, ? extends Object> context) {
        Map<String, Object> result = createDataResourceMethod(dctx, context);
        return result;
    }

    public static Map<String, Object> createDataResourceMethod(DispatchContext dctx, Map<String, ? extends Object> rcontext) {
        Map<String, Object> context = UtilMisc.makeMapWritable(rcontext);
        Map<String, Object> result = new HashMap<>();
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String userLoginId = (String) userLogin.get("userLoginId");
        String createdByUserLogin = userLoginId;
        String lastModifiedByUserLogin = userLoginId;
        Timestamp createdDate = UtilDateTime.nowTimestamp();
        Timestamp lastModifiedDate = UtilDateTime.nowTimestamp();
        String dataTemplateTypeId = (String) context.get("dataTemplateTypeId");
        if (UtilValidate.isEmpty(dataTemplateTypeId)) {
            dataTemplateTypeId = "NONE";
            context.put("dataTemplateTypeId", dataTemplateTypeId);
        }

        // If textData exists, then create DataResource and return dataResourceId
        String dataResourceId = (String) context.get("dataResourceId");
        if (UtilValidate.isEmpty(dataResourceId)) {
            dataResourceId = delegator.getNextSeqId("DataResource");
        }
        if (Debug.infoOn()) {
            Debug.logInfo("in createDataResourceMethod, dataResourceId:" + dataResourceId, MODULE);
        }
        GenericValue dataResource = delegator.makeValue("DataResource", UtilMisc.toMap("dataResourceId", dataResourceId));
        dataResource.setNonPKFields(context);
        dataResource.put("createdByUserLogin", createdByUserLogin);
        dataResource.put("lastModifiedByUserLogin", lastModifiedByUserLogin);
        dataResource.put("createdDate", createdDate);
        dataResource.put("lastModifiedDate", lastModifiedDate);
        // get first statusId  for content out of the statusItem table if not provided
        if (UtilValidate.isEmpty(dataResource.get("statusId"))) {
            try {
                GenericValue statusItem = EntityQuery.use(delegator).from("StatusItem").where("statusTypeId", "CONTENT_STATUS")
                        .orderBy("sequenceId").queryFirst();
                if (statusItem != null) {
                    dataResource.put("statusId", statusItem.get("statusId"));
                }
            } catch (GenericEntityException e) {
                return ServiceUtil.returnError(e.getMessage());
            }
        }

        try {
            dataResource.create();
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
        result.put("dataResourceId", dataResourceId);
        result.put("dataResource", dataResource);
        return result;
    }

    /**
     * A service wrapper for the createElectronicTextMethod method. Forces permissions to be checked.
     */
    public static Map<String, Object> createElectronicText(DispatchContext dctx, Map<String, ? extends Object> context) {
        Map<String, Object> result = createElectronicTextMethod(dctx, context);
        return result;
    }

    public static Map<String, Object> createElectronicTextMethod(DispatchContext dctx, Map<String, ? extends Object> context) {
        Map<String, Object> result = new HashMap<>();
        Delegator delegator = dctx.getDelegator();
        String dataResourceId = (String) context.get("dataResourceId");
        String textData = (String) context.get("textData");
        if (UtilValidate.isNotEmpty(textData)) {
            GenericValue electronicText = delegator.makeValue("ElectronicText",
                    UtilMisc.toMap("dataResourceId", dataResourceId, "textData", textData));
            try {
                electronicText.create();
            } catch (GenericEntityException e) {
                return ServiceUtil.returnError(e.getMessage());
            }
        }
        return result;
    }

    /**
     * A service wrapper for the createFileMethod method. Forces permissions to be checked.
     */
    public static Map<String, Object> createFile(DispatchContext dctx, Map<String, ? extends Object> context) {
        return createFileMethod(dctx, context);
    }

    public static Map<String, Object> createFileNoPerm(DispatchContext dctx, Map<String, ? extends Object> rcontext) throws IOException,
            ImageReadException {
        String originalFileName = (String) rcontext.get("dataResourceName");
        String fileNameAndPath = (String) rcontext.get("objectInfo");
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) rcontext.get("locale");
        File file = new File(fileNameAndPath);
        if (!originalFileName.isEmpty()) {
            // Check the file name
            if (!SecuredUpload.isValidFileName(originalFileName, delegator)) {
                String errorMessage = UtilProperties.getMessage("SecurityUiLabels", "SupportedFileFormatsIncludingSvg", locale);
                return ServiceUtil.returnError(errorMessage);
            }
            // TODO we could verify the file type (here "All") with dataResourceTypeId. Anyway it's done with isValidFile()
            // We would just have a better error message
            if (file.exists()) {
                // Check if a webshell is not uploaded
                if (!SecuredUpload.isValidFile(fileNameAndPath, "All", delegator)) {
                    String errorMessage = UtilProperties.getMessage("SecurityUiLabels", "SupportedFileFormatsIncludingSvg", locale);
                    return ServiceUtil.returnError(errorMessage);
                }
            }
        }

        Map<String, Object> context = UtilMisc.makeMapWritable(rcontext);
        context.put("skipPermissionCheck", "true");
        return createFileMethod(dctx, context);
    }

    public static Map<String, Object> createFileMethod(DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        String dataResourceTypeId = (String) context.get("dataResourceTypeId");
        String objectInfo = (String) context.get("objectInfo");
        ByteBuffer binData = (ByteBuffer) context.get("binData");
        String textData = (String) context.get("textData");
        Locale locale = (Locale) context.get("locale");

        // a few place holders
        String prefix = "";
        String sep = "";

        // extended validation for binary/character data
        if (UtilValidate.isNotEmpty(textData) && binData != null) {
            return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "ContentCannotProcessBothCharacterAndBinaryFile", locale));
        }

        // obtain a reference to the file
        File file = null;
        if (UtilValidate.isEmpty(objectInfo)) {
            return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "ContentUnableObtainReferenceToFile",
                    UtilMisc.toMap("objectInfo", ""), locale));
        }
        if (UtilValidate.isEmpty(dataResourceTypeId) || "LOCAL_FILE".equals(dataResourceTypeId) || "LOCAL_FILE_BIN".equals(dataResourceTypeId)) {
            file = new File(objectInfo);
            if (!file.isAbsolute()) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "ContentLocalFileDoesNotPointToAbsoluteLocation", locale));
            }
        } else if ("OFBIZ_FILE".equals(dataResourceTypeId) || "OFBIZ_FILE_BIN".equals(dataResourceTypeId)) {
            prefix = System.getProperty("ofbiz.home");
            if (objectInfo.indexOf('/') != 0 && prefix.lastIndexOf('/') != (prefix.length() - 1)) {
                sep = "/";
            }
            file = new File(prefix + sep + objectInfo);
        } else if ("CONTEXT_FILE".equals(dataResourceTypeId) || "CONTEXT_FILE_BIN".equals(dataResourceTypeId)) {
            prefix = (String) context.get("rootDir");
            if (UtilValidate.isEmpty(prefix)) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "ContentCannotFindContextFileWithEmptyContextRoot", locale));
            }
            if (objectInfo.indexOf('/') != 0 && prefix.lastIndexOf('/') != (prefix.length() - 1)) {
                sep = "/";
            }
            file = new File(prefix + sep + objectInfo);
            try {
                DataResourceWorker.checkContextFileBoundary(file, prefix);
            } catch (GeneralException e) {
                return ServiceUtil.returnError(e.getMessage());
            }
        }
        if (file == null) {
            return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "ContentUnableObtainReferenceToFile",
                    UtilMisc.toMap("objectInfo", objectInfo), locale));
        }

        // Content store seam (write). When a provider holds this resource's content the bytes go there instead
        // of onto this instance's disk, which is what keeps an upload from becoming state only this instance
        // has. Answers null in the committed default - database storage - and the pre-existing local write below
        // is then performed unchanged. The service signature, the service definition and the DataResource row
        // are untouched either way: this decides only where the bytes land.
        Map<String, Object> published = publishThroughContentStore(delegator, dataResourceId(context),
                dataResourceTypeId, objectInfo, (String) context.get("rootDir"), file,
                UtilValidate.isNotEmpty(textData) ? textData.getBytes(StandardCharsets.UTF_8)
                        : binData == null ? null : binData.array(),
                UtilValidate.isNotEmpty(textData) ? "Text" : "All",
                UtilValidate.isNotEmpty(textData) ? "SupportedTextFileFormats" : "SupportedFileFormatsIncludingSvg",
                UtilValidate.isNotEmpty(textData) ? "ContentUnableWriteCharacterDataToFile"
                        : "ContentUnableWriteBinaryDataToFile",
                locale);
        if (published != null) {
            return published;
        }

        // write the data to the file
        if (UtilValidate.isNotEmpty(textData)) {
            try (OutputStreamWriter out = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);) {
                out.write(textData);
                // Check if a webshell is not uploaded
                // TODO I believe the call below to SecuredUpload::isValidFile is now useless because of the same in createFileNoPerm
                if (!SecuredUpload.isValidFile(file.getAbsolutePath(), "Text", delegator)) {
                    String errorMessage = UtilProperties.getMessage(SECURITY_RESOURCE, "SupportedTextFileFormats", locale);
                    return ServiceUtil.returnError(errorMessage);
                }
            } catch (IOException | ImageReadException e) {
                Debug.logWarning(e, MODULE);
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "ContentUnableWriteCharacterDataToFile",
                        UtilMisc.toMap("fileName", file.getAbsolutePath()), locale));
            }
        } else if (binData != null) {
            try {
                String origName = file.getName();
                int dotIdx = origName.lastIndexOf('.');
                String fileExt = dotIdx >= 0 ? origName.substring(dotIdx) : null;
                Path tempFile = Files.createTempFile(null, fileExt);
                Files.write(tempFile, binData.array(), StandardOpenOption.APPEND);
                if (!SecuredUpload.isValidFile(tempFile.toString(), "All", delegator)) {
                    String errorMessage = UtilProperties.getMessage(SECURITY_RESOURCE, "SupportedFileFormatsIncludingSvg", locale);
                    new File(tempFile.toString()).deleteOnExit();
                    return ServiceUtil.returnError(errorMessage);
                }
                Files.copy(tempFile, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                new File(tempFile.toString()).deleteOnExit();

            } catch (ImageReadException e) {
                Debug.logError(e, MODULE);
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "ContentUnableToOpenFileForWriting",
                        UtilMisc.toMap("fileName", file.getAbsolutePath()), locale));
            } catch (IOException e) {
                Debug.logError(e, MODULE);
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "ContentUnableWriteBinaryDataToFile",
                        UtilMisc.toMap("fileName", file.getAbsolutePath()), locale));
            }
        } else {
            return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "ContentNoContentFilePassed",
                    UtilMisc.toMap("fileName", file.getAbsolutePath()), locale));
        }


        Map<String, Object> result = ServiceUtil.returnSuccess();
        return result;
    }

    /**
     * A top-level service for updating a DataResource and ElectronicText together.
     */
    public static Map<String, Object> updateDataResourceAndText(DispatchContext dctx, Map<String, ? extends Object> context) {
        Map<String, Object> thisResult = updateDataResourceMethod(dctx, context);
        if (thisResult.get(ModelService.RESPONSE_MESSAGE) != null) {
            return ServiceUtil.returnError((String) thisResult.get(ModelService.ERROR_MESSAGE));
        }
        String dataResourceTypeId = (String) context.get("dataResourceTypeId");
        if (dataResourceTypeId != null && "ELECTRONIC_TEXT".equals(dataResourceTypeId)) {
            thisResult = updateElectronicText(dctx, context);
            if (thisResult.get(ModelService.RESPONSE_MESSAGE) != null) {
                return ServiceUtil.returnError((String) thisResult.get(ModelService.ERROR_MESSAGE));
            }
        }
        return ServiceUtil.returnSuccess();
    }

    /**
     * A service wrapper for the updateDataResourceMethod method. Forces permissions to be checked.
     */
    public static Map<String, Object> updateDataResource(DispatchContext dctx, Map<String, ? extends Object> context) {
        Map<String, Object> result = updateDataResourceMethod(dctx, context);
        return result;
    }

    public static Map<String, Object> updateDataResourceMethod(DispatchContext dctx, Map<String, ? extends Object> context) {
        Map<String, Object> result = new HashMap<>();
        Delegator delegator = dctx.getDelegator();
        GenericValue dataResource = null;
        Locale locale = (Locale) context.get("locale");
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String userLoginId = (String) userLogin.get("userLoginId");
        String lastModifiedByUserLogin = userLoginId;
        Timestamp lastModifiedDate = UtilDateTime.nowTimestamp();

        // If textData exists, then create DataResource and return dataResourceId
        String dataResourceId = (String) context.get("dataResourceId");
        try {
            dataResource = EntityQuery.use(delegator).from("DataResource").where("dataResourceId", dataResourceId).queryOne();
        } catch (GenericEntityException e) {
            Debug.logWarning(e, MODULE);
            return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "ContentDataResourceNotFound",
                    UtilMisc.toMap("parameters.dataResourceId", dataResourceId), locale));
        }

        dataResource.setNonPKFields(context);
        dataResource.put("lastModifiedByUserLogin", lastModifiedByUserLogin);
        dataResource.put("lastModifiedDate", lastModifiedDate);

        try {
            dataResource.store();
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }

        result.put("dataResource", dataResource);
        return result;
    }

    /**
     * A service wrapper for the updateElectronicTextMethod method. Forces permissions to be checked.
     */
    public static Map<String, Object> updateElectronicText(DispatchContext dctx, Map<String, ? extends Object> context) {
        Map<String, Object> result = updateElectronicTextMethod(dctx, context);
        return result;
    }

    /**
     * Because sometimes a DataResource will exist, but no ElectronicText has been created, this method will create an
     * ElectronicText if it does not exist.
     * @param dctx the dispatch context
     * @param context the context
     * @return update the ElectronicText
     */
    public static Map<String, Object> updateElectronicTextMethod(DispatchContext dctx, Map<String, ? extends Object> context) {
        Map<String, Object> result = new HashMap<>();
        Delegator delegator = dctx.getDelegator();
        GenericValue electronicText = null;
        Locale locale = (Locale) context.get("locale");
        String dataResourceId = (String) context.get("dataResourceId");
        result.put("dataResourceId", dataResourceId);
        String contentId = (String) context.get("contentId");
        result.put("contentId", contentId);
        if (UtilValidate.isEmpty(dataResourceId)) {
            Debug.logError("dataResourceId is null.", MODULE);
            return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "ContentDataResourceIsNull", locale));
        }
        String textData = (String) context.get("textData");
        if (Debug.verboseOn()) {
            Debug.logVerbose("in updateElectronicText, textData:" + textData, MODULE);
        }
        try {
            electronicText = EntityQuery.use(delegator).from("ElectronicText").where("dataResourceId", dataResourceId).queryOne();
            if (electronicText != null) {
                electronicText.put("textData", textData);
                electronicText.store();
            } else {
                electronicText = delegator.makeValue("ElectronicText");
                electronicText.put("dataResourceId", dataResourceId);
                electronicText.put("textData", textData);
                electronicText.create();
            }
        } catch (GenericEntityException e) {
            Debug.logWarning(e, MODULE);
            return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "ContentElectronicTextNotFound", locale) + " " + e.getMessage());
        }

        return result;
    }

    /**
     * A service wrapper for the updateFileMethod method. Forces permissions to be checked.
     */
    public static Map<String, Object> updateFile(DispatchContext dctx, Map<String, ? extends Object> context) {
        Map<String, Object> result = null;
        try {
            result = updateFileMethod(dctx, context);
        } catch (GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
        return result;
    }

    public static Map<String, Object> updateFileMethod(DispatchContext dctx, Map<String, ? extends Object> context) throws GenericServiceException {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = new HashMap<>();
        Locale locale = (Locale) context.get("locale");
        String dataResourceTypeId = (String) context.get("dataResourceTypeId");
        String objectInfo = (String) context.get("objectInfo");
        String textData = (String) context.get("textData");
        ByteBuffer binData = (ByteBuffer) context.get("binData");
        String prefix = "";
        File file = null;
        String fileName = "";
        String sep = "";
        try {
            if (UtilValidate.isEmpty(dataResourceTypeId) || dataResourceTypeId.startsWith("LOCAL_FILE")) {
                fileName = prefix + sep + objectInfo;
                file = new File(fileName);
                if (!file.isAbsolute()) {
                    throw new GenericServiceException("File: " + fileName + " is not absolute.");
                }
            } else if (dataResourceTypeId.startsWith("OFBIZ_FILE")) {
                prefix = System.getProperty("ofbiz.home");
                if (objectInfo.indexOf('/') != 0 && prefix.lastIndexOf('/') != (prefix.length() - 1)) {
                    sep = "/";
                }
                file = new File(prefix + sep + objectInfo);
            } else if (dataResourceTypeId.startsWith("CONTEXT_FILE")) {
                prefix = (String) context.get("rootDir");
                if (objectInfo.indexOf('/') != 0 && prefix.lastIndexOf('/') != (prefix.length() - 1)) {
                    sep = "/";
                }
                file = new File(prefix + sep + objectInfo);
                try {
                    DataResourceWorker.checkContextFileBoundary(file, prefix);
                } catch (GeneralException e) {
                    return ServiceUtil.returnError(e.getMessage());
                }
            }
            if (file == null) {
                throw new IOException("File is null");
            }

            // Content store seam (write), exactly as in createFileMethod above: the provider receives the bytes
            // when it holds this resource's content, and the local write below is unchanged when it does not.
            Map<String, Object> published = publishThroughContentStore(delegator, dataResourceId(context),
                    dataResourceTypeId, objectInfo, (String) context.get("rootDir"), file,
                    UtilValidate.isNotEmpty(textData) ? textData.getBytes(StandardCharsets.UTF_8)
                            : binData == null ? null : binData.array(),
                    UtilValidate.isNotEmpty(textData) ? "Text" : "Image",
                    UtilValidate.isNotEmpty(textData) ? "SupportedTextFileFormats"
                            : "SupportedFileFormatsIncludingSvg",
                    UtilValidate.isNotEmpty(textData) ? "ContentUnableWriteCharacterDataToFile"
                            : "ContentUnableWriteBinaryDataToFile",
                    locale);
            if (published != null) {
                return published;
            }

            // write the data to the file
            if (UtilValidate.isNotEmpty(textData)) {
                try (OutputStreamWriter out = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);) {
                    out.write(textData);
                    // Check if a webshell is not uploaded
                    // TODO I believe the call below to SecuredUpload::isValidFile is now useless because of the same in createFileNoPerm
                    if (!SecuredUpload.isValidFile(file.getAbsolutePath(), "Text", delegator)) {
                        String errorMessage = UtilProperties.getMessage(SECURITY_RESOURCE, "SupportedTextFileFormats", locale);
                        return ServiceUtil.returnError(errorMessage);
                    }
                } catch (IOException | ImageReadException e) {
                    Debug.logWarning(e, MODULE);
                    return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "ContentUnableWriteCharacterDataToFile",
                            UtilMisc.toMap("fileName", file.getAbsolutePath()), locale));
                }
            } else if (binData != null) {
                try {
                    String origName = file.getName();
                    int dotIdx = origName.lastIndexOf('.');
                    String fileExt = dotIdx >= 0 ? origName.substring(dotIdx) : null;
                    Path tempFile = Files.createTempFile(null, fileExt);
                    Files.write(tempFile, binData.array(), StandardOpenOption.APPEND);
                    if (!SecuredUpload.isValidFile(tempFile.toString(), "Image", delegator)) {
                        String errorMessage = UtilProperties.getMessage(SECURITY_RESOURCE, "SupportedFileFormatsIncludingSvg", locale);
                        new File(tempFile.toString()).deleteOnExit();
                        return ServiceUtil.returnError(errorMessage);
                    }
                    Files.copy(tempFile, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    new File(tempFile.toString()).deleteOnExit();
                } catch (ImageReadException e) {
                    Debug.logError(e, MODULE);
                    return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "ContentUnableToOpenFileForWriting",
                            UtilMisc.toMap("fileName", file.getAbsolutePath()), locale));
                } catch (IOException e) {
                    Debug.logError(e, MODULE);
                    return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "ContentUnableWriteBinaryDataToFile",
                            UtilMisc.toMap("fileName", file.getAbsolutePath()), locale));
                }
            } else {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "ContentNoContentFilePassed",
                        UtilMisc.toMap("fileName", file.getAbsolutePath()), locale));
            }

        } catch (IOException e) {
            Debug.logWarning(e, MODULE);
            throw new GenericServiceException(e.getMessage());
        }


        return result;
    }

    public static Map<String, Object> renderDataResourceAsText(DispatchContext dctx, Map<String, ? extends Object> context)
            throws GeneralException, IOException {
        Map<String, Object> results = new HashMap<>();
        //LocalDispatcher dispatcher = dctx.getDispatcher();
        Writer out = (Writer) context.get("outWriter");
        Map<String, Object> templateContext = UtilGenerics.cast(context.get("templateContext"));
        //GenericValue userLogin = (GenericValue) context.get("userLogin");
        String dataResourceId = (String) context.get("dataResourceId");
        if (templateContext != null && UtilValidate.isEmpty(dataResourceId)) {
            dataResourceId = (String) templateContext.get("dataResourceId");
        }
        String mimeTypeId = (String) context.get("mimeTypeId");
        if (templateContext != null && UtilValidate.isEmpty(mimeTypeId)) {
            mimeTypeId = (String) templateContext.get("mimeTypeId");
        }

        Locale locale = (Locale) context.get("locale");

        if (templateContext == null) {
            templateContext = new HashMap<>();
        }

        Writer outWriter = new StringWriter();
        DataResourceWorker.renderDataResourceAsText(dctx.getDispatcher(), dataResourceId, outWriter, templateContext, locale, mimeTypeId, true);
        try {
            out.write(outWriter.toString());
            results.put("textData", outWriter.toString());
        } catch (IOException e) {
            Debug.logError(e, "Error rendering sub-content text", MODULE);
            return ServiceUtil.returnError(e.toString());
        }
        return results;
    }

    /**
     * A service wrapper for the updateImageMethod method. Forces permissions to be checked.
     */
    public static Map<String, Object> updateImage(DispatchContext dctx, Map<String, ? extends Object> context) {
        Map<String, Object> result = updateImageMethod(dctx, context);
        return result;
    }

    public static Map<String, Object> updateImageMethod(DispatchContext dctx, Map<String, ? extends Object> context) {
        Map<String, Object> result = new HashMap<>();
        Delegator delegator = dctx.getDelegator();
        //Locale locale = (Locale) context.get("locale");
        String dataResourceId = (String) context.get("dataResourceId");
        ByteBuffer byteBuffer = (ByteBuffer) context.get("imageData");
        if (byteBuffer != null) {
            byte[] imageBytes = byteBuffer.array();
            try {
                GenericValue imageDataResource = EntityQuery.use(delegator).from("ImageDataResource")
                        .where("dataResourceId", dataResourceId).queryOne();
                if (Debug.infoOn()) {
                    Debug.logInfo("imageDataResource(U):" + imageDataResource, MODULE);
                    // Length, not content. The bytes are an uploaded image: rendering them into a log line
                    // writes user-supplied binary data - which can be a photograph of a person or a scan of a
                    // document - into a file with a different audience and retention from the database column
                    // it belongs in, and does so at INFO (CWE-532). The length is the diagnostic that was
                    // actually wanted, and is what the neighbouring create path already logs.
                    Debug.logInfo("imageBytes(U) length:" + imageBytes.length, MODULE);
                }
                if (imageDataResource == null) {
                    return createImageMethod(dctx, context);
                }
                imageDataResource.setBytes("imageData", imageBytes);
                imageDataResource.store();
            } catch (GenericEntityException e) {
                return ServiceUtil.returnError(e.getMessage());
            }
        }
        return result;
    }

    /**
     * A service wrapper for the createImageMethod method. Forces permissions to be checked.
     */
    public static Map<String, Object> createImage(DispatchContext dctx, Map<String, ? extends Object> context) {
        Map<String, Object> result = createImageMethod(dctx, context);
        return result;
    }

    public static Map<String, Object> createImageMethod(DispatchContext dctx, Map<String, ? extends Object> context) {
        Map<String, Object> result = new HashMap<>();
        Delegator delegator = dctx.getDelegator();
        String dataResourceId = (String) context.get("dataResourceId");
        ByteBuffer byteBuffer = (ByteBuffer) context.get("imageData");
        if (byteBuffer != null) {
            byte[] imageBytes = byteBuffer.array();
            try {
                GenericValue imageDataResource = delegator.makeValue("ImageDataResource", UtilMisc.toMap("dataResourceId", dataResourceId));
                imageDataResource.setBytes("imageData", imageBytes);
                if (Debug.infoOn()) {
                    Debug.logInfo("imageDataResource(C):" + imageDataResource, MODULE);
                }
                imageDataResource.create();
            } catch (GenericEntityException e) {
                return ServiceUtil.returnError(e.getMessage());
            }
        }

        return result;
    }

    /**
     * A service wrapper for the createBinaryFileMethod method. Forces permissions to be checked.
     */
    public static Map<String, Object> createBinaryFile(DispatchContext dctx, Map<String, ? extends Object> context) {
        Map<String, Object> result = null;
        try {
            result = createBinaryFileMethod(dctx, context);
        } catch (GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
        return result;
    }

    public static Map<String, Object> createBinaryFileMethod(DispatchContext dctx, Map<String, ? extends Object> context)
            throws GenericServiceException {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = new HashMap<>();
        GenericValue dataResource = (GenericValue) context.get("dataResource");
        String dataResourceTypeId = (String) dataResource.get("dataResourceTypeId");
        String objectInfo = (String) dataResource.get("objectInfo");
        byte[] imageData = (byte[]) context.get("imageData");
        String rootDir = (String) context.get("rootDir");
        Locale locale = (Locale) context.get("locale");
        File file = null;
        if (Debug.infoOn()) {
            Debug.logInfo("in createBinaryFileMethod, dataResourceTypeId:" + dataResourceTypeId, MODULE);
            Debug.logInfo("in createBinaryFileMethod, objectInfo:" + objectInfo, MODULE);
            Debug.logInfo("in createBinaryFileMethod, rootDir:" + rootDir, MODULE);
        }
        try {
            // Resolved as a WRITE target: every authorisation getContentFile applies is applied, but a location
            // that holds nothing locally is only final when no provider holds the content - which is what lets
            // these services create content in an object store, where the local file legitimately never exists.
            // With no provider configured this is getContentFile's behaviour exactly, absence included.
            file = DataResourceWorker.getContentWriteFile(delegator, dataResource.getString("dataResourceId"),
                    dataResourceTypeId, objectInfo, rootDir);
        } catch (FileNotFoundException | GeneralException e) {
            Debug.logWarning(e, MODULE);
            throw new GenericServiceException(e.getMessage());
        }
        if (Debug.infoOn()) {
            Debug.logInfo("in createBinaryFileMethod, file:" + file, MODULE);
            Debug.logInfo("in createBinaryFileMethod, imageData:" + imageData.length, MODULE);
        }
        if (imageData != null && imageData.length > 0) {
            // Content store seam (write). The bytes are published to the provider that holds this resource's
            // content, and the local write below runs only when none does - which is the committed default.
            Map<String, Object> published = publishThroughContentStore(delegator,
                    dataResource.getString("dataResourceId"), dataResourceTypeId, objectInfo, rootDir, file,
                    imageData, "All", "SupportedFileFormatsIncludingSvg", "ContentUnableWriteBinaryDataToFile",
                    locale);
            if (published != null) {
                return published;
            }
            try (FileOutputStream out = new FileOutputStream(file);) {
                out.write(imageData);
                // Check if a webshell is not uploaded
                // TODO I believe the call below to SecuredUpload::isValidFile is now useless because of the same in createFileNoPerm
                if (!SecuredUpload.isValidFile(file.getAbsolutePath(), "All", delegator)) {
                    String errorMessage = UtilProperties.getMessage(SECURITY_RESOURCE, "SupportedFileFormatsIncludingSvg", locale);
                    return ServiceUtil.returnError(errorMessage);
                }
                if (Debug.infoOn()) {
                    Debug.logInfo("in createBinaryFileMethod, length:" + file.length(), MODULE);
                }
            } catch (IOException | ImageReadException e) {
                Debug.logWarning(e, MODULE);
                throw new GenericServiceException(e.getMessage());
            }
            // Only when something was actually written: an empty imageData writes no file, so there is nothing
            // to publish and publishing would store content the caller never supplied.
            Map<String, Object> unpublished = publishToContentStore(delegator,
                    (String) dataResource.get("dataResourceId"), file, locale);
            if (unpublished != null) {
                return unpublished;
            }
        }
        return result;
    }


    /**
     * A service wrapper for the createBinaryFileMethod method. Forces permissions to be checked.
     */
    public static Map<String, Object> updateBinaryFile(DispatchContext dctx, Map<String, ? extends Object> context) {
        Map<String, Object> result = null;
        try {
            result = updateBinaryFileMethod(dctx, context);
        } catch (GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
        return result;
    }

    public static Map<String, Object> updateBinaryFileMethod(DispatchContext dctx, Map<String, ? extends Object> context)
            throws GenericServiceException {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = new HashMap<>();
        GenericValue dataResource = (GenericValue) context.get("dataResource");
        String dataResourceTypeId = (String) dataResource.get("dataResourceTypeId");
        String objectInfo = (String) dataResource.get("objectInfo");
        byte[] imageData = (byte[]) context.get("imageData");
        String rootDir = (String) context.get("rootDir");
        Locale locale = (Locale) context.get("locale");
        File file = null;
        if (Debug.infoOn()) {
            Debug.logInfo("in updateBinaryFileMethod, dataResourceTypeId:" + dataResourceTypeId, MODULE);
            Debug.logInfo("in updateBinaryFileMethod, objectInfo:" + objectInfo, MODULE);
            Debug.logInfo("in updateBinaryFileMethod, rootDir:" + rootDir, MODULE);
        }
        try {
            // Resolved as a WRITE target: every authorisation getContentFile applies is applied, but a location
            // that holds nothing locally is only final when no provider holds the content - which is what lets
            // these services create content in an object store, where the local file legitimately never exists.
            // With no provider configured this is getContentFile's behaviour exactly, absence included.
            file = DataResourceWorker.getContentWriteFile(delegator, dataResource.getString("dataResourceId"),
                    dataResourceTypeId, objectInfo, rootDir);
        } catch (FileNotFoundException | GeneralException e) {
            Debug.logWarning(e, MODULE);
            throw new GenericServiceException(e.getMessage());
        }
        if (Debug.infoOn()) {
            Debug.logInfo("in updateBinaryFileMethod, file:" + file, MODULE);
            // Length, not content, and for the same reason: an uploaded image logged in full at INFO puts
            // user-supplied binary data into the log, and createBinaryFileMethod a few lines above already
            // logs only the length, so this also restores consistency between two copies of one flow.
            Debug.logInfo("in updateBinaryFileMethod, imageData length:"
                    + (imageData == null ? "none" : String.valueOf(imageData.length)), MODULE);
        }
        if (imageData != null && imageData.length > 0) {
            // Content store seam (write). The bytes are published to the provider that holds this resource's
            // content, and the local write below runs only when none does - which is the committed default.
            Map<String, Object> published = publishThroughContentStore(delegator,
                    dataResource.getString("dataResourceId"), dataResourceTypeId, objectInfo, rootDir, file,
                    imageData, "All", "SupportedFileFormatsIncludingSvg", "ContentUnableWriteBinaryDataToFile",
                    locale);
            if (published != null) {
                return published;
            }
            try (FileOutputStream out = new FileOutputStream(file);) {
                out.write(imageData);
                // Check if a webshell is not uploaded
                // TODO I believe the call below to SecuredUpload::isValidFile is now useless because of the same in createFileNoPerm
                if (!SecuredUpload.isValidFile(file.getAbsolutePath(), "All", delegator)) {
                    String errorMessage = UtilProperties.getMessage(SECURITY_RESOURCE, "SupportedFileFormatsIncludingSvg", locale);
                    return ServiceUtil.returnError(errorMessage);
                }

                if (Debug.infoOn()) {
                    Debug.logInfo("in updateBinaryFileMethod, length:" + file.length(), MODULE);
                }
            } catch (IOException | ImageReadException e) {
                Debug.logWarning(e, MODULE);
                throw new GenericServiceException(e.getMessage());
            }
            // Same rule as the create above: publish only what was written.
            Map<String, Object> unpublished = publishToContentStore(delegator,
                    (String) dataResource.get("dataResourceId"), file, locale);
            if (unpublished != null) {
                return unpublished;
            }
        }
        return result;
    }

}
