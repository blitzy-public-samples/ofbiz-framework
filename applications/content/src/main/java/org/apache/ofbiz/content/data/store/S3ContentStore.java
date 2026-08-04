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
import java.net.URISyntaxException;
import java.util.Locale;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.UtilValidate;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * The S3-compatible object-storage provider.
 *
 * <p>It is built on the AWS SDK for Java v2 synchronous {@link S3Client}, configured from
 * {@code applications/content/config/content.properties} - which
 * {@code docker/docker-entrypoint.sh} renders from the {@code OFBIZ_S3_*} environment variables:
 *
 * <ul>
 *   <li>{@code content.store.s3.bucket} - required.</li>
 *   <li>{@code content.store.s3.region} - required; any value is accepted by an S3-compatible store
 *       that does not use regions, because the SDK only needs one to sign a request.</li>
 *   <li>{@code content.store.s3.endpoint} - optional. When set it is applied with
 *       {@code endpointOverride}, which is what lets the same client address MinIO, Ceph or any other
 *       S3-compatible store as well as Amazon S3.</li>
 *   <li>{@code content.store.s3.access.key.id} and {@code content.store.s3.secret.access.key} -
 *       optional, and required together. When both are absent the SDK's default credential chain is
 *       used, which is how an instance picks up an IAM role rather than a static key.</li>
 *   <li>{@code content.store.s3.path.style} - optional, default false. Set it for a store that cannot
 *       serve virtual-host-style addressing, which most non-AWS implementations cannot.</li>
 * </ul>
 *
 * <p>Timeouts, retries and the HTTP client are the SDK's own defaults; nothing here overrides them.
 *
 * <p>Absence is separated from failure as {@link ContentStore} requires: a key the bucket does not
 * hold becomes {@link FileNotFoundException}, while every other SDK failure - credentials, network,
 * permissions, an absent bucket - becomes an {@link IOException} that is not a
 * {@code FileNotFoundException}. Logging names the bucket and the key and never the content, per the
 * policy {@link ContentStore} publishes.
 *
 * <p>Thread safe: {@link S3Client} is thread safe and every other field is immutable.
 */
public final class S3ContentStore implements ContentStore, AutoCloseable {

    private static final String MODULE = S3ContentStore.class.getName();

    private static final String BUCKET_PROPERTY = "content.store.s3.bucket";
    private static final String REGION_PROPERTY = "content.store.s3.region";
    private static final String ENDPOINT_PROPERTY = "content.store.s3.endpoint";
    private static final String ACCESS_KEY_PROPERTY = "content.store.s3.access.key.id";
    private static final String SECRET_KEY_PROPERTY = "content.store.s3.secret.access.key";
    private static final String PATH_STYLE_PROPERTY = "content.store.s3.path.style";

    /** The largest object {@link #get} will read into memory. */
    private static final long MAX_IN_MEMORY_OBJECT = 16L * 1024L * 1024L;

    private final String bucket;
    private final S3Client client;

    /**
     * Builds the client for the configuration this deployment declares.
     *
     * @throws GeneralException if the configuration is incomplete or unusable
     */
    S3ContentStore() throws GeneralException {
        this.bucket = required(BUCKET_PROPERTY);
        String region = required(REGION_PROPERTY);
        String endpoint = ContentStoreFactory.setting(ENDPOINT_PROPERTY, "");
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(credentials())
                .forcePathStyle(Boolean.parseBoolean(
                        ContentStoreFactory.setting(PATH_STYLE_PROPERTY, "false")));
        if (!endpoint.isEmpty()) {
            builder.endpointOverride(endpointOverride(endpoint));
        }
        this.client = builder.build();
    }

    @Override
    public void put(String key, InputStream content, long length) throws GeneralException, IOException {
        ContentStoreFactory.requireUsableKey(key);
        if (content == null || length < 0L) {
            throw new IOException("Content of a known, non-negative length is required to store " + reference(key));
        }
        try {
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentLength(length).build(),
                    RequestBody.fromInputStream(content, length));
        } catch (SdkException failure) {
            throw failed("store", key, failure);
        }
    }

    @Override
    public byte[] get(String key) throws GeneralException, IOException {
        ContentStoreFactory.requireUsableKey(key);
        long held = size(key);
        if (held > MAX_IN_MEMORY_OBJECT) {
            throw new IOException("The content store holds " + held + " bytes for " + reference(key) + ", more than"
                    + " the " + MAX_IN_MEMORY_OBJECT + " bytes that may be read into memory; stream it instead");
        }
        try {
            return client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
        } catch (NoSuchKeyException absent) {
            throw absence(key, absent);
        } catch (SdkException failure) {
            throw failed("read", key, failure);
        }
    }

    @Override
    public InputStream openStream(String key) throws GeneralException, IOException {
        ContentStoreFactory.requireUsableKey(key);
        try {
            return client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (NoSuchKeyException absent) {
            throw absence(key, absent);
        } catch (SdkException failure) {
            throw failed("read", key, failure);
        }
    }

    @Override
    public boolean exists(String key) throws GeneralException, IOException {
        ContentStoreFactory.requireUsableKey(key);
        try {
            head(key);
            return true;
        } catch (FileNotFoundException absent) {
            return false;
        }
    }

    @Override
    public void delete(String key) throws GeneralException, IOException {
        ContentStoreFactory.requireUsableKey(key);
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (NoSuchKeyException alreadyGone) {
            Debug.logInfo("The content store already held no " + reference(key) + ", so there was nothing to"
                    + " remove", MODULE);
        } catch (SdkException failure) {
            throw failed("remove", key, failure);
        }
    }

    /**
     * Shuts the SDK client down when this store is superseded by a re-resolved configuration.
     */
    @Override
    public void close() {
        client.close();
    }

    /**
     * Returns the signature {@link ContentStoreFactory} caches a resolution against.
     *
     * <p>It covers every setting the client is constructed from, so a change to any of them resolves a
     * new client rather than keeping one that no longer matches the configuration. The secret access
     * key is represented by its length alone: rotating it must still invalidate the cache, and a
     * signature is held in memory next to the cache rather than being treated as secret material.
     *
     * @return the signature
     */
    static String configurationSignature() {
        return String.join("\n",
                ContentStoreFactory.setting(BUCKET_PROPERTY, ""),
                ContentStoreFactory.setting(REGION_PROPERTY, ""),
                ContentStoreFactory.setting(ENDPOINT_PROPERTY, ""),
                ContentStoreFactory.setting(ACCESS_KEY_PROPERTY, ""),
                Integer.toString(ContentStoreFactory.setting(SECRET_KEY_PROPERTY, "").length()),
                ContentStoreFactory.setting(PATH_STYLE_PROPERTY, "false"));
    }

    /**
     * Resolves the credentials provider the configuration asks for.
     *
     * @return static credentials when both halves are configured, otherwise the SDK's default chain
     * @throws GeneralException if exactly one half of a static credential pair is configured
     */
    private static AwsCredentialsProvider credentials() throws GeneralException {
        String accessKey = ContentStoreFactory.setting(ACCESS_KEY_PROPERTY, "");
        String secretKey = ContentStoreFactory.setting(SECRET_KEY_PROPERTY, "");
        if (accessKey.isEmpty() && secretKey.isEmpty()) {
            // builder().build() rather than the deprecated create(): the two are equivalent, but only
            // the builder form survives the SDK's own deprecation of the shortcut.
            return DefaultCredentialsProvider.builder().build();
        }
        if (accessKey.isEmpty() || secretKey.isEmpty()) {
            // Refused rather than half-applied: falling back to the default chain here would answer a
            // half-configured deployment with a confusing permission error from a different identity.
            throw new GeneralException(ACCESS_KEY_PROPERTY + " and " + SECRET_KEY_PROPERTY + " are configured"
                    + " together, or neither is configured and the SDK's default credential chain is used");
        }
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    }

    /**
     * Validates the configured endpoint and returns it.
     *
     * @param endpoint the configured endpoint
     * @return the endpoint to override the SDK's own with
     * @throws GeneralException if it is not an absolute http or https URI
     */
    private static URI endpointOverride(String endpoint) throws GeneralException {
        URI uri;
        try {
            uri = new URI(endpoint);
        } catch (URISyntaxException malformed) {
            throw new GeneralException(ENDPOINT_PROPERTY + " [" + endpoint + "] is not a valid URI", malformed);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (UtilValidate.isEmpty(uri.getHost()) || !("http".equals(scheme) || "https".equals(scheme))) {
            throw new GeneralException(ENDPOINT_PROPERTY + " [" + endpoint + "] must be an absolute http or https"
                    + " URI, for example https://s3.example.internal:9000");
        }
        if ("http".equals(scheme)) {
            Debug.logWarning("The content store endpoint [" + endpoint + "] is plain http, so object content and"
                    + " the credentials that sign for it cross the network unencrypted. Use https unless the"
                    + " endpoint is reached over a network the deployment controls end to end.", MODULE);
        }
        return uri;
    }

    /**
     * Reads a required setting.
     *
     * @param name the property name
     * @return the configured value
     * @throws GeneralException if it is absent or blank
     */
    private static String required(String name) throws GeneralException {
        String value = ContentStoreFactory.setting(name, "");
        if (value.isEmpty()) {
            throw new GeneralException(name + " is required when content.store.provider is s3");
        }
        return value;
    }

    /**
     * Returns how many bytes the bucket holds for a key.
     *
     * @param key the storage key
     * @return the object's length in bytes
     * @throws FileNotFoundException if the bucket holds no such key
     * @throws IOException if the store cannot answer
     */
    private long size(String key) throws IOException {
        return head(key).contentLength();
    }

    /**
     * Reads an object's metadata with HeadObject, which is the existence check this provider uses.
     *
     * @param key the storage key
     * @return the object's metadata
     * @throws FileNotFoundException if the bucket holds no such key
     * @throws IOException if the store cannot answer
     */
    private HeadObjectResponse head(String key) throws IOException {
        try {
            return client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (NoSuchKeyException absent) {
            throw absence(key, absent);
        } catch (S3Exception failure) {
            // A store that answers 404 without the NoSuchKey code - which several S3-compatible
            // implementations do for HeadObject, because a HEAD response carries no error body to put a
            // code in - is reporting absence just as much as NoSuchKeyException is.
            if (failure.statusCode() == 404) {
                throw absence(key, failure);
            }
            throw failed("read", key, failure);
        } catch (SdkException failure) {
            throw failed("read", key, failure);
        }
    }

    /**
     * Reports an object the bucket does not hold, as the contract's one absence signal.
     *
     * @param key the storage key
     * @param cause what the SDK reported
     * @return the exception to throw
     */
    private FileNotFoundException absence(String key, SdkException cause) {
        FileNotFoundException absent = new FileNotFoundException("The content store holds no " + reference(key));
        absent.initCause(cause);
        return absent;
    }

    /**
     * Reports a store that could not answer, which is never absence.
     *
     * @param operation what was attempted, for the message
     * @param key the storage key
     * @param cause what the SDK reported
     * @return the exception to throw
     */
    private IOException failed(String operation, String key, SdkException cause) {
        return new IOException("The content store could not " + operation + " " + reference(key) + ": "
                + cause.getMessage(), cause);
    }

    /**
     * Names an object in a log line or a message, per the logging policy {@link ContentStore} publishes.
     *
     * @param key the storage key
     * @return the bucket and key
     */
    private String reference(String key) {
        return "[" + bucket + "/" + key + "]";
    }
}
