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

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.UtilValidate;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * The S3-compatible object-storage provider, which holds file-backed content in a bucket.
 *
 * <p>It is built on the AWS SDK for Java v2 synchronous {@link S3Client}, configured from these
 * {@code content} properties, which {@code docker/docker-entrypoint.sh} renders from the
 * {@code OFBIZ_S3_*} environment variables:
 *
 * <ul>
 *   <li>{@code content.store.s3.bucket} - required.</li>
 *   <li>{@code content.store.s3.region} - required; an S3-compatible store that does not use regions
 *       accepts any value, because the SDK only needs one to sign a request.</li>
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
 * <p><strong>Every call is bounded.</strong> A content read or write happens inside a request, and
 * usually inside a transaction, so the client is built with an explicit per-attempt timeout, an
 * explicit overall API-call timeout that spans retries, and a bounded number of attempts, rather than
 * being allowed to hold a request thread until the operating system gives up on the socket.
 *
 * <p><strong>Transport.</strong> An {@code https} endpoint is expected. A plain {@code http} endpoint
 * is accepted so that a store reached over a network the deployment controls end to end still works,
 * but it is logged as a warning, and the container entry point refuses it outright in the {@code prod}
 * profile.
 *
 * <p>Absence is separated from failure as {@link ContentStore} requires: a key the bucket does not
 * hold becomes {@link FileNotFoundException}, while every other SDK failure - credentials, network,
 * permissions, an absent bucket - becomes an {@link IOException} that is not a
 * {@code FileNotFoundException}. Log lines and messages name the bucket and the key and never the
 * content, per the policy {@link ContentStore} publishes.
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

    private static final int MAX_IN_MEMORY_OBJECT = 16 * 1024 * 1024;
    private static final int BUFFER_SIZE = 8192;
    private static final Duration ATTEMPT_TIMEOUT = Duration.ofSeconds(15L);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(45L);
    private static final int MAX_ATTEMPTS = 3;

    private final String bucket;
    private final S3Client client;

    S3ContentStore() throws GeneralException {
        this.bucket = required(BUCKET_PROPERTY);
        String region = required(REGION_PROPERTY);
        String endpoint = ContentStoreFactory.setting(ENDPOINT_PROPERTY, "");
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(credentials())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallAttemptTimeout(ATTEMPT_TIMEOUT)
                        .apiCallTimeout(CALL_TIMEOUT)
                        .retryStrategy(retry -> retry.maxAttempts(MAX_ATTEMPTS))
                        .build())
                .forcePathStyle(Boolean.parseBoolean(
                        ContentStoreFactory.setting(PATH_STYLE_PROPERTY, "false")));
        if (!endpoint.isEmpty()) {
            builder.endpointOverride(endpointOverride(endpoint));
        }
        this.client = builder.build();
    }

    S3ContentStore(S3Client s3Client, String s3Bucket) {
        this.client = s3Client;
        this.bucket = s3Bucket;
    }

    @Override
    public void put(String key, byte[] data) throws GeneralException, IOException {
        ContentStoreFactory.requireUsableKey(key);
        if (data == null) {
            throw new IOException("Content is required to store " + reference(key));
        }
        try {
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),
                    RequestBody.fromBytes(data));
        } catch (SdkException failure) {
            throw failed("store", key, failure);
        }
    }

    @Override
    public byte[] get(String key) throws GeneralException, IOException {
        // Read through the response stream and stop as soon as the limit is passed. Asking the store how
        // large the object is and then reading it are two separate operations, and an object replaced
        // between them would defeat the limit, so the bytes actually read are what is bounded.
        try (InputStream content = openStream(key)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            ByteArrayOutputStream held = new ByteArrayOutputStream();
            for (int read = content.read(buffer); read >= 0; read = content.read(buffer)) {
                if (held.size() + read > MAX_IN_MEMORY_OBJECT) {
                    throw new IOException("The content store holds more than the " + MAX_IN_MEMORY_OBJECT
                            + " bytes for " + reference(key) + " that may be read into memory; stream it instead");
                }
                held.write(buffer, 0, read);
            }
            return held.toByteArray();
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
        } catch (S3Exception failure) {
            throw notFoundOrFailure("read", key, failure);
        } catch (SdkException failure) {
            throw failed("read", key, failure);
        }
    }

    @Override
    public boolean exists(String key) throws GeneralException, IOException {
        ContentStoreFactory.requireUsableKey(key);
        try {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException absent) {
            return false;
        } catch (S3Exception failure) {
            // A store that answers 404 without the NoSuchKey code - which several S3-compatible
            // implementations do for HeadObject, because a HEAD response carries no error body to put a
            // code in - is reporting absence just as much as NoSuchKeyException is.
            if (failure.statusCode() == 404) {
                return false;
            }
            throw failed("inspect", key, failure);
        } catch (SdkException failure) {
            throw failed("inspect", key, failure);
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
     * Closes the SDK client and releases its resources.
     */
    @Override
    public void close() {
        client.close();
    }

    /**
     * Returns the signature {@link ContentStoreFactory} caches a resolution against.
     *
     * <p>It is a SHA-256 digest of every setting the client is constructed from, the secret access key
     * included in full, so that rotating a credential to a different value of the same length still
     * resolves a new client. A digest rather than the values themselves so that the signature, which is
     * held in memory next to the cache and compared on every resolution, does not retain the plaintext
     * settings.
     *
     * @return the signature
     */
    static String configurationSignature() {
        String settings = String.join("\n",
                ContentStoreFactory.setting(BUCKET_PROPERTY, ""),
                ContentStoreFactory.setting(REGION_PROPERTY, ""),
                ContentStoreFactory.setting(ENDPOINT_PROPERTY, ""),
                ContentStoreFactory.setting(ACCESS_KEY_PROPERTY, ""),
                ContentStoreFactory.setting(SECRET_KEY_PROPERTY, ""),
                ContentStoreFactory.setting(PATH_STYLE_PROPERTY, "false"));
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(settings.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            // SHA-256 is mandatory on every Java platform, so this cannot happen. Failing loudly rather
            // than falling back to the settings themselves keeps a credential out of the signature.
            throw new IllegalStateException("SHA-256 is required to sign the content store configuration",
                    unavailable);
        }
    }

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
            Debug.logWarning("The content store endpoint [" + endpoint + "] is plain http, so object content"
                    + " and the request authorization metadata, including the access-key identifier and"
                    + " signature, cross the network unencrypted. Use https unless the endpoint is reached over"
                    + " a network the deployment controls end to end; the container entry point refuses plain"
                    + " http in the prod profile.", MODULE);
        }
        return uri;
    }

    private static String required(String name) throws GeneralException {
        String value = ContentStoreFactory.setting(name, "");
        if (value.isEmpty()) {
            throw new GeneralException(name + " is required when content.store.provider is s3");
        }
        return value;
    }

    private IOException notFoundOrFailure(String operation, String key, S3Exception failure) {
        if (failure.statusCode() == 404) {
            return absence(key, failure);
        }
        return failed(operation, key, failure);
    }

    private FileNotFoundException absence(String key, SdkException cause) {
        FileNotFoundException absent = new FileNotFoundException("The content store holds no " + reference(key));
        absent.initCause(cause);
        return absent;
    }

    private IOException failed(String operation, String key, SdkException cause) {
        return new IOException("The content store could not " + operation + " " + reference(key) + ": "
                + cause.getMessage(), cause);
    }

    private String reference(String key) {
        return "[" + bucket + "/" + key + "]";
    }
}
