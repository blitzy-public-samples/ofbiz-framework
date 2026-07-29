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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * S3-compatible object-storage provider for the {@link ContentStore} SPI.
 *
 * <p>This is the provider selected when the {@code content.store.provider} property of the {@code content}
 * resource is set to {@code s3}. It adapts the AWS SDK for Java <strong>v2</strong> {@link S3Client} to the
 * SPI so that the bytes behind file-backed {@code DataResource} content, and behind user uploads, live in an
 * object store instead of on instance-local disk. An instance of the application then holds no durable local
 * state of its own and is freely replaceable, which is what makes a load-balanced, multi-instance deployment
 * safe.
 *
 * <p><strong>Amazon S3 or any S3-compatible store.</strong> The client is built with {@code endpointOverride}
 * and {@code forcePathStyle}, and that pair is what lets one provider address Amazon S3 as well as a non-AWS
 * S3-compatible store such as MinIO or Ceph. Leave {@code content.store.s3.endpoint} blank for Amazon S3, or
 * point it at the compatible store's endpoint - usually together with {@code content.store.s3.path.style=true},
 * because such stores commonly do not offer virtual-host-style addressing.
 *
 * <p><strong>Configuration.</strong> Exactly six properties of the {@code content} resource are read. All of
 * them are committed blank, or {@code false}, so that no credential and nothing environment-specific lives in
 * the repository or in the container image; {@code docker/docker-entrypoint.sh} injects the deployed values
 * from the environment:
 * <ul>
 *   <li>{@code content.store.s3.bucket} - the bucket every key is stored in; required</li>
 *   <li>{@code content.store.s3.region} - the region identifier; required</li>
 *   <li>{@code content.store.s3.endpoint} - endpoint override; blank selects Amazon S3</li>
 *   <li>{@code content.store.s3.access.key.id} - optional access key id</li>
 *   <li>{@code content.store.s3.secret.access.key} - optional secret access key</li>
 *   <li>{@code content.store.s3.path.style} - {@code true} to force path-style addressing</li>
 * </ul>
 *
 * <p><strong>Credential resolution.</strong> When <em>both</em> credential properties are supplied they are
 * used as static credentials. When either is blank the AWS default credential provider chain is used instead,
 * which resolves an instance role, container credentials, a shared profile or the process environment. The
 * recommended deployment - no credential material in configuration at all - is therefore the default, and
 * needs no extra configuration.
 *
 * <p><strong>Inert unless selected.</strong> {@code database} is the committed default of
 * {@code content.store.provider}; in that mode the pre-existing {@code DataResource} database-storage path is
 * used unchanged, this class is never instantiated and the AWS SDK is never touched. Nothing here runs from a
 * static initialiser, and the client itself is created only on first use, so even loading the class resolves
 * no credential and opens no connection.
 *
 * <p><strong>Keys.</strong> A key is an opaque object key inside the configured bucket. Object keys are flat,
 * so no intermediate structure is ever created and, unlike a filesystem provider, no key can escape the
 * provider's root: the bucket <em>is</em> the root, and a key containing {@code ..} is just an ordinary key.
 *
 * <p><strong>Thread safety.</strong> An instance holds no per-request state; it is immutable apart from the
 * lazily created client, which is published under the instance lock, and the SDK client is itself
 * thread-safe. One instance is therefore shared safely by many request threads, as the SPI requires.
 */
public final class S3ContentStore implements ContentStore {

    private static final String MODULE = S3ContentStore.class.getName();

    /** Resource holding the configuration, named without its extension as {@code UtilProperties} requires. */
    private static final String PROPERTY_RESOURCE = "content";

    private static final String BUCKET_PROPERTY = "content.store.s3.bucket";
    private static final String REGION_PROPERTY = "content.store.s3.region";
    private static final String ENDPOINT_PROPERTY = "content.store.s3.endpoint";
    private static final String ACCESS_KEY_ID_PROPERTY = "content.store.s3.access.key.id";
    private static final String SECRET_ACCESS_KEY_PROPERTY = "content.store.s3.secret.access.key";
    private static final String PATH_STYLE_PROPERTY = "content.store.s3.path.style";

    /** Status an object store returns for a key that resolves to nothing. */
    private static final int HTTP_NOT_FOUND = 404;

    /** Reported in place of a blank endpoint, which selects Amazon S3's own endpoint. */
    private static final String AMAZON_S3_ENDPOINT = "amazon-s3-default";

    private final String bucket;
    private final String region;
    private final String endpoint;
    private final String accessKeyId;
    private final String secretAccessKey;
    private final boolean pathStyle;

    /** Created on first use rather than at construction, and published under this instance's lock. */
    private S3Client s3Client;

    /**
     * Creates the provider from the {@code content.store.s3.*} properties of the {@code content} resource.
     *
     * <p>Only configuration is read here: no AWS SDK type is touched, no credential is resolved and no
     * connection is opened, so selecting this provider can neither fail with an SDK error nor slow component
     * start-up. Configuration that is absent or unusable is reported from the storage operations as a
     * {@link GeneralException}, which is precisely how the SPI documents an incomplete provider
     * configuration. That also keeps this constructor free of checked exceptions, so the provider can be
     * selected with a plain {@code new S3ContentStore()}.
     */
    public S3ContentStore() {
        // A three-argument UtilProperties lookup self-defaults on a blank value as well as on an absent key,
        // so the committed blank values arrive here as the empty string and need no separate handling.
        this.bucket = UtilProperties.getPropertyValue(PROPERTY_RESOURCE, BUCKET_PROPERTY, "");
        this.region = UtilProperties.getPropertyValue(PROPERTY_RESOURCE, REGION_PROPERTY, "");
        this.endpoint = UtilProperties.getPropertyValue(PROPERTY_RESOURCE, ENDPOINT_PROPERTY, "");
        this.accessKeyId = UtilProperties.getPropertyValue(PROPERTY_RESOURCE, ACCESS_KEY_ID_PROPERTY, "");
        this.secretAccessKey = UtilProperties.getPropertyValue(PROPERTY_RESOURCE, SECRET_ACCESS_KEY_PROPERTY, "");
        this.pathStyle = UtilProperties.getPropertyAsBoolean(PROPERTY_RESOURCE, PATH_STYLE_PROPERTY, false);
    }

    /**
     * Package-private test seam: builds a provider around an already-constructed client and bucket.
     *
     * <p>It exists so that this provider's own logic - key and content validation, the mapping of an absent
     * object onto {@link FileNotFoundException}, and the idempotence of {@link #delete(String)} - can be
     * unit-tested against a mocked {@link S3Client} from the sibling {@code src/test/java} tree, which shares
     * this package. Without the seam a test would have to supply real AWS configuration, resolve real
     * credentials and reach a real endpoint, none of which belongs in a unit test. Production code must use
     * the public configuration-driven constructor instead; the remaining configuration is deliberately left
     * blank here because the supplied client already embodies it.
     *
     * @param s3Client the client every storage operation is issued through; must not be null
     * @param bucket the bucket every storage operation addresses; must be neither null nor empty
     */
    S3ContentStore(S3Client s3Client, String bucket) {
        this.bucket = bucket;
        this.region = "";
        this.endpoint = "";
        this.accessKeyId = "";
        this.secretAccessKey = "";
        this.pathStyle = false;
        this.s3Client = s3Client;
    }

    /**
     * Stores the supplied content as the object under the supplied key in the configured bucket.
     *
     * <p>A second write to the same key replaces the object in full rather than appending, because that is
     * S3's own {@code PutObject} semantics, and no intermediate structure has to be prepared because object
     * keys are flat. A zero-length array is stored as an existing but empty object.
     *
     * @param key the object key to write, relative to the configured bucket; must be neither null nor empty
     * @param data the complete content to store; must not be null, and may be a zero-length array
     * @throws GeneralException if the key is null or empty, the content is null, or the provider
     *     configuration is incomplete
     * @throws IOException if the object store cannot be written to
     */
    @Override
    public void put(String key, byte[] data) throws GeneralException, IOException {
        requireUsableKey(key, "put");
        if (data == null) {
            // An empty array stores empty content, so null is unusable input rather than an empty write. It
            // is refused before anything is sent, which is what makes a rejected put store nothing at all.
            throw new GeneralException("Content store provider [s3] rejected the [put] operation for key [" + key
                    + "]: the content to store must not be null");
        }
        S3Client client = requireClient();
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        try {
            client.putObject(request, RequestBody.fromBytes(data));
        } catch (SdkException e) {
            throw storeFailure("store", key, e);
        }
    }

    /**
     * Reads the whole object stored under the supplied key into memory.
     *
     * <p>Implemented on top of {@link #openStream(String)} so that one {@code GetObject} call serves both
     * operations and the two cannot drift apart over how an absent object is reported. The stream is opened
     * and closed here, so prefer {@link #openStream(String)} for large content.
     *
     * @param key the object key to read, relative to the configured bucket; must be neither null nor empty
     * @return the complete stored content, never null; a zero-length array means the object is empty
     * @throws GeneralException if the key is null or empty, or the provider configuration is incomplete
     * @throws IOException if the object cannot be read; in particular a {@link FileNotFoundException} when
     *     the key resolves to no object, so that this method never returns null
     */
    @Override
    public byte[] get(String key) throws GeneralException, IOException {
        // Validated here as well as in openStream so that a rejection names the operation the caller
        // actually invoked; openStream would otherwise report the delegate's name in the message.
        requireUsableKey(key, "get");
        try (InputStream content = openStream(key)) {
            return content.readAllBytes();
        }
    }

    /**
     * Opens a fresh stream over the object stored under the supplied key, positioned at its first byte.
     *
     * <p>Every invocation issues its own {@code GetObject} call and hands back an independent stream, so
     * concurrent readers never share, truncate or rewind one another's view of the content.
     * <strong>Ownership of the returned stream passes to the caller, which must close it</strong>, ideally
     * with a try-with-resources statement: it is an SDK response stream and holds an open connection to the
     * object store until it is closed. It is deliberately not closed here.
     *
     * @param key the object key to read, relative to the configured bucket; must be neither null nor empty
     * @return a newly opened stream over the stored object, never null, owned by the caller
     * @throws GeneralException if the key is null or empty, or the provider configuration is incomplete
     * @throws IOException if the stream cannot be opened; in particular a {@link FileNotFoundException} when
     *     the key resolves to no object, so that this method never returns null
     */
    @Override
    public InputStream openStream(String key) throws GeneralException, IOException {
        requireUsableKey(key, "openStream");
        S3Client client = requireClient();
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        try {
            // Typed explicitly to keep the boundary visible: the SDK stream owns the connection to the object
            // store, and that ownership is exactly what this method passes on to its caller.
            ResponseInputStream<GetObjectResponse> content = client.getObject(request);
            return content;
        } catch (S3Exception e) {
            if (isAbsentObject(e)) {
                throw absentObject(key, e);
            }
            throw storeFailure("read", key, e);
        } catch (SdkException e) {
            throw storeFailure("read", key, e);
        }
    }

    /**
     * Reports whether the supplied key currently resolves to an object in the configured bucket.
     *
     * <p>This is the non-exceptional probe: a well-formed key with no object behind it is simply
     * {@code false}, and exceptions are reserved for unusable keys and for a store that cannot be reached.
     *
     * @param key the object key to probe, relative to the configured bucket; must be neither null nor empty
     * @return {@code true} if and only if the key resolves to a stored object
     * @throws GeneralException if the key is null or empty, or the provider configuration is incomplete
     * @throws IOException if the object store cannot be interrogated
     */
    @Override
    public boolean exists(String key) throws GeneralException, IOException {
        requireUsableKey(key, "exists");
        S3Client client = requireClient();
        HeadObjectRequest request = HeadObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        try {
            client.headObject(request);
            return true;
        } catch (S3Exception e) {
            if (isAbsentObject(e)) {
                return false;
            }
            throw storeFailure("probe", key, e);
        } catch (SdkException e) {
            throw storeFailure("probe", key, e);
        }
    }

    /**
     * Removes the object stored under the supplied key.
     *
     * <p>The operation is idempotent, so replayed or repeated clean-up is safe: S3 already treats the
     * deletion of an absent key as a success, and a store that instead reports the absence explicitly is
     * normalised here to that same successful no-op.
     *
     * @param key the object key to remove, relative to the configured bucket; must be neither null nor empty
     * @throws GeneralException if the key is null or empty, or the provider configuration is incomplete
     * @throws IOException if the object store cannot be modified
     */
    @Override
    public void delete(String key) throws GeneralException, IOException {
        requireUsableKey(key, "delete");
        S3Client client = requireClient();
        DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        try {
            client.deleteObject(request);
        } catch (S3Exception e) {
            if (!isAbsentObject(e)) {
                throw storeFailure("delete", key, e);
            }
            Debug.logVerbose("Content store provider [s3] deleted nothing for absent key [" + key + "] in bucket ["
                    + bucket + "]", MODULE);
        } catch (SdkException e) {
            throw storeFailure("delete", key, e);
        }
    }

    /**
     * Rejects a key the SPI declares unusable, before anything at all is sent to the object store.
     *
     * @param key the key supplied by the caller
     * @param operation the SPI operation being attempted, named so the message identifies the caller
     * @throws GeneralException if the key is null or empty
     */
    private static void requireUsableKey(String key, String operation) throws GeneralException {
        if (UtilValidate.isEmpty(key)) {
            throw new GeneralException("Content store provider [s3] rejected the [" + operation
                    + "] operation: the storage key must be neither null nor empty");
        }
    }

    /**
     * Reports whether a failure from the object store means the key simply resolves to no object.
     *
     * <p>Both signals have to be honoured. A store that answers with a full error document yields
     * {@link NoSuchKeyException}, but a {@code HEAD} or {@code DELETE} reply carries no body to unmarshal, so
     * an S3-compatible store may only be able to signal a bare {@code 404} that the SDK cannot narrow. The
     * type test comes first because it also holds for a {@link NoSuchKeyException} built in a unit test,
     * where no HTTP status has been set.
     *
     * @param cause the failure reported by the object store
     * @return {@code true} if the failure means the key resolves to nothing
     */
    private static boolean isAbsentObject(S3Exception cause) {
        return cause instanceof NoSuchKeyException || cause.statusCode() == HTTP_NOT_FOUND;
    }

    /**
     * Returns the client every storage operation is issued through, creating it on first use.
     *
     * @return the client for this provider, never null
     * @throws GeneralException if the bucket is not configured, or the client cannot be built
     */
    private synchronized S3Client requireClient() throws GeneralException {
        if (UtilValidate.isEmpty(bucket)) {
            throw new GeneralException("Content store provider [s3] is not usable: property ["
                    + BUCKET_PROPERTY + "] of resource [" + PROPERTY_RESOURCE + "] is not configured");
        }
        if (s3Client == null) {
            s3Client = buildClient();
        }
        return s3Client;
    }

    /**
     * Builds the client from the configured region, endpoint, addressing style and credentials.
     *
     * @return a newly built client
     * @throws GeneralException if the region is not configured, a configured value is malformed, or the SDK
     *     rejects the resulting configuration
     */
    private S3Client buildClient() throws GeneralException {
        if (UtilValidate.isEmpty(region)) {
            throw new GeneralException("Content store provider [s3] is not usable: property ["
                    + REGION_PROPERTY + "] of resource [" + PROPERTY_RESOURCE + "] is not configured");
        }
        boolean staticCredentials = UtilValidate.isNotEmpty(accessKeyId) && UtilValidate.isNotEmpty(secretAccessKey);
        try {
            S3ClientBuilder builder = S3Client.builder()
                    .region(Region.of(region))
                    .forcePathStyle(pathStyle);
            if (UtilValidate.isNotEmpty(endpoint)) {
                // A blank endpoint keeps Amazon S3's own endpoint; any other value targets a compatible store.
                builder = builder.endpointOverride(URI.create(endpoint));
            }
            if (staticCredentials) {
                builder = builder.credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
            } else {
                // At least one credential property is blank, so defer to the instance role, container
                // credentials, shared profile or process environment that the default chain resolves. The
                // chain is requested through its builder because the shorter create() is deprecated in this
                // SDK line, and this file has to stay free of deprecation warnings under -Xlint:all.
                builder = builder.credentialsProvider(DefaultCredentialsProvider.builder().build());
            }
            S3Client client = builder.build();
            // Addressing configuration only. The credential source is reported rather than any credential
            // value, so that no access key id and no secret access key can ever reach the log.
            Debug.logInfo("Content store provider [s3] ready: bucket [" + bucket + "], region [" + region
                    + "], endpoint [" + (UtilValidate.isEmpty(endpoint) ? AMAZON_S3_ENDPOINT : endpoint)
                    + "], path-style addressing [" + pathStyle + "], credentials ["
                    + (staticCredentials ? "configured-properties" : "aws-default-provider-chain") + "]", MODULE);
            return client;
        } catch (IllegalArgumentException e) {
            throw new GeneralException("Content store provider [s3] could not be configured for bucket [" + bucket
                    + "]: the configured region or endpoint value is not valid", e);
        } catch (SdkException e) {
            throw new GeneralException("Content store provider [s3] could not be initialised for bucket [" + bucket
                    + "]", e);
        }
    }

    /**
     * Translates an absent object into the signal the SPI requires, so that callers which dereference the
     * result without a null check keep behaving exactly as they do against a filesystem.
     *
     * @param key the object key that resolved to nothing
     * @param cause the failure reported by the object store, retained as the cause
     * @return the exception to throw
     */
    private FileNotFoundException absentObject(String key, SdkException cause) {
        FileNotFoundException absent = new FileNotFoundException("Content store provider [s3] found no object for key ["
                + key + "] in bucket [" + bucket + "]");
        absent.initCause(cause);
        return absent;
    }

    /**
     * Translates a failure of the object store itself into the SPI's I/O signal.
     *
     * <p>The cause is chained rather than folded into the message: an object-store error reply can echo
     * request and credential details, and nothing credential-derived may be composed into a message this
     * class builds. Chaining keeps the whole diagnostic available without doing that.
     *
     * @param action the storage action that failed, named so the message identifies it
     * @param key the object key the action addressed
     * @param cause the failure reported by the object store
     * @return the exception to throw
     */
    private IOException storeFailure(String action, String key, SdkException cause) {
        return new IOException("Content store provider [s3] could not " + action + " key [" + key + "] in bucket ["
                + bucket + "]", cause);
    }
}
