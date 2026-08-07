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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.UtilValidate;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

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
 *   <li>{@code content.store.s3.encryption} - optional, default {@code none}. {@code sse-s3} asks the
 *       store to encrypt every object with its own managed key; {@code sse-kms} asks it to use the KMS
 *       key named by {@code content.store.s3.kms.key.id}, which is then required. The request carries
 *       the header rather than relying on a bucket default, so content is encrypted at rest whether or
 *       not the bucket declares one, and a bucket policy that requires the header is satisfied.</li>
 *   <li>{@code content.store.s3.kms.key.id} - the KMS key id, alias or ARN. Required for
 *       {@code sse-kms}, ignored otherwise.</li>
 * </ul>
 *
 * <p><strong>The configured endpoint is the whole endpoint story.</strong> With
 * {@code content.store.s3.endpoint} blank the SDK still honours its own {@code AWS_ENDPOINT_URL_S3} and
 * {@code AWS_ENDPOINT_URL} environment variables, so one of those being set would send content somewhere
 * the validated configuration does not describe. That case is reported at WARNING, naming the variable
 * and its value, so it cannot pass unnoticed.
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
 * <p><strong>A missing BUCKET is a fault, never absence.</strong> Both answers are a 404, so the
 * distinction is drawn deliberately rather than left to the status code. On a read the store's own error
 * code decides it, because a {@code GetObject} 404 carries a body: only {@code NoSuchKey} is absence, and
 * {@code NoSuchBucket} is raised. On the two metadata operations no code is available - a {@code HeadObject}
 * response has no body, so Amazon S3 and MinIO alike answer both cases with the 404 the SDK types as
 * {@code NoSuchKeyException} - so absence is CONFIRMED with a {@code HeadBucket} before it is reported. That
 * costs one extra request on the absence path only, and it is what stops a bucket that has been renamed,
 * deleted or made inaccessible from making content quietly appear never to have been stored. See
 * {@link #confirmBucketHolds}.
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
    private static final String ENCRYPTION_PROPERTY = "content.store.s3.encryption";
    private static final String KMS_KEY_PROPERTY = "content.store.s3.kms.key.id";

    /** The SDK's own environment variables for an endpoint, which it honours with no configuration here. */
    private static final String[] AMBIENT_ENDPOINT_VARIABLES = {"AWS_ENDPOINT_URL_S3", "AWS_ENDPOINT_URL"};

    private static final String ENCRYPTION_NONE = "none";
    private static final String ENCRYPTION_SSE_S3 = "sse-s3";
    private static final String ENCRYPTION_SSE_KMS = "sse-kms";

    private static final int BUFFER_SIZE = 8192;
    private static final Duration ATTEMPT_TIMEOUT = Duration.ofSeconds(15L);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(45L);
    private static final int MAX_ATTEMPTS = 3;

    /** The S3 error code that names the KEY as the thing the store does not hold. */
    private static final String NO_SUCH_KEY_CODE = "NoSuchKey";

    private static final int NOT_FOUND = 404;

    private final String bucket;
    private final S3Client client;
    private final String encryption;
    private final String kmsKeyId;

    /**
     * Whether a bucket the store would not confirm has already been reported.
     *
     * <p>Only the ambiguous outcome of {@link #confirmBucketHolds} sets this - a store that answers the
     * bucket question with neither "it is here" nor "it is gone", which a credential permitted to read an
     * object but not to inspect its bucket produces. One line per store instance rather than one per read,
     * because absence is answered on a request path.
     */
    private final AtomicBoolean unconfirmableBucketReported = new AtomicBoolean();

    S3ContentStore() throws GeneralException {
        this.bucket = required(BUCKET_PROPERTY);
        this.encryption = encryptionMode();
        this.kmsKeyId = ContentStoreFactory.setting(KMS_KEY_PROPERTY, "");
        if (ENCRYPTION_SSE_KMS.equals(encryption) && kmsKeyId.isEmpty()) {
            throw new GeneralException(KMS_KEY_PROPERTY + " is required when " + ENCRYPTION_PROPERTY + " is "
                    + ENCRYPTION_SSE_KMS + ": the key that encrypts stored content has to be named.");
        }
        String region = required(REGION_PROPERTY);
        String endpoint = ContentStoreFactory.setting(ENDPOINT_PROPERTY, "");
        if (endpoint.isEmpty()) {
            reportAmbientEndpoint();
        }
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
        this(s3Client, s3Bucket, ENCRYPTION_NONE, "");
    }

    S3ContentStore(S3Client s3Client, String s3Bucket, String s3Encryption, String s3KmsKeyId) {
        this.client = s3Client;
        this.bucket = s3Bucket;
        this.encryption = s3Encryption;
        this.kmsKeyId = s3KmsKeyId;
    }

    @Override
    public void put(String key, byte[] data) throws GeneralException, IOException {
        ContentStoreFactory.requireUsableKey(key);
        if (data == null) {
            throw new IOException("Content is required to store " + reference(key));
        }
        if (data.length > MAX_OBJECT_BYTES) {
            throw new IOException("Content of " + reference(key) + " holds " + data.length + " bytes, more than"
                    + " the " + MAX_OBJECT_BYTES + " bytes one object may hold");
        }
        PutObjectRequest.Builder request = PutObjectRequest.builder().bucket(bucket).key(key);
        // Server-side encryption is requested ON THE REQUEST rather than left to the bucket's default, so
        // that content is encrypted at rest whether or not the bucket carries one - and so that a bucket
        // policy which REQUIRES the header does not reject the write.
        if (ENCRYPTION_SSE_S3.equals(encryption)) {
            request.serverSideEncryption(ServerSideEncryption.AES256);
        } else if (ENCRYPTION_SSE_KMS.equals(encryption)) {
            request.serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(kmsKeyId);
        }
        try {
            client.putObject(request.build(), RequestBody.fromBytes(data));
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
                if (held.size() + read > MAX_OBJECT_BYTES) {
                    // Aborted rather than closed: ResponseInputStream.close() DRAINS the rest of the
                    // object to keep the connection reusable, so an object past the limit would still be
                    // transferred in full. abort() gives up the connection instead and transfers nothing
                    // more, which is the point of the limit.
                    if (content instanceof ResponseInputStream<?> response) {
                        response.abort();
                    }
                    throw new IOException("The content store holds more than the " + MAX_OBJECT_BYTES
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
            // Confirmed, not believed: see confirmBucketHolds().
            confirmBucketHolds(key, absent);
            return false;
        } catch (S3Exception failure) {
            // A store that answers 404 without the NoSuchKey code - which several S3-compatible
            // implementations do for HeadObject, because a HEAD response carries no error body to put a
            // code in - is reporting absence just as much as NoSuchKeyException is. A 404 that names
            // anything else, NoSuchBucket above all, is a store fault and is raised below.
            if (meansAbsentKey(failure)) {
                confirmBucketHolds(key, failure);
                return false;
            }
            throw failed("inspect", key, failure);
        } catch (SdkException failure) {
            throw failed("inspect", key, failure);
        }
    }

    @Override
    public Optional<Description> describe(String key) throws GeneralException, IOException {
        ContentStoreFactory.requireUsableKey(key);
        try {
            HeadObjectResponse held = client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            long length = held.contentLength() == null ? 0L : held.contentLength();
            long modifiedAt = held.lastModified() == null ? 0L : held.lastModified().toEpochMilli();
            return Optional.of(new Description(length, modifiedAt, held.eTag()));
        } catch (NoSuchKeyException absent) {
            confirmBucketHolds(key, absent);
            return Optional.empty();
        } catch (S3Exception failure) {
            // As in exists(): a 404 without the NoSuchKey code - which several S3-compatible stores answer
            // for HeadObject, because a HEAD response carries no error body to put a code in - is absence,
            // and a 404 that names something else is a fault.
            if (meansAbsentKey(failure)) {
                confirmBucketHolds(key, failure);
                return Optional.empty();
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
                ContentStoreFactory.setting(PATH_STYLE_PROPERTY, "false"),
                ContentStoreFactory.setting(ENCRYPTION_PROPERTY, ENCRYPTION_NONE),
                ContentStoreFactory.setting(KMS_KEY_PROPERTY, ""));
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
        if (meansAbsentKey(failure)) {
            return absence(key, failure);
        }
        return failed(operation, key, failure);
    }

    /**
     * Reports whether a 404 from the store says the KEY is the thing it does not hold.
     *
     * <p>A 404 alone does not: {@code NoSuchBucket} is a 404 too, and reading it as absence is what would
     * make every object in a bucket that has been renamed, deleted or replaced look as though it had never
     * been stored - the one failure mode a fleet whose only durable copy is the object cannot afford,
     * because the caller would fall back to a local copy that does not exist and answer 404 to the user
     * with nothing in the log. So the code is read, and only {@code NoSuchKey} is absence.
     *
     * <p>A 404 that carries NO error code at all is absence as well, because a request that names a key
     * has nothing else to be missing that the store would answer 404 for, and several S3-compatible
     * implementations answer a {@code HeadObject} that way - a HEAD response has no body to put a code in.
     * {@link #confirmBucketHolds} is what closes that gap for the two metadata operations.
     *
     * @param failure the store's answer
     * @return true when the failure means this store holds no object under the requested key
     */
    private static boolean meansAbsentKey(S3Exception failure) {
        if (failure.statusCode() != NOT_FOUND) {
            return false;
        }
        String code = errorCode(failure);
        return code.isEmpty() || NO_SUCH_KEY_CODE.equals(code);
    }

    /**
     * Returns the store's own error code for a failure, or the empty string when it reported none.
     *
     * @param failure the store's answer
     * @return the error code, never null
     */
    private static String errorCode(S3Exception failure) {
        var details = failure.awsErrorDetails();
        if (details == null || details.errorCode() == null) {
            return "";
        }
        return details.errorCode();
    }

    /**
     * Confirms the bucket exists before an absence answered by a metadata request is passed on as absence.
     *
     * <p><strong>Why this request is worth making.</strong> A {@code HeadObject} cannot say WHICH thing is
     * missing. Its response carries no body, so there is no error code in it: Amazon S3 and MinIO both
     * answer a {@code HeadObject} for a key in a bucket that does not exist with the same 404 the SDK
     * types as {@link NoSuchKeyException} that it uses for a key the bucket really does not hold. Reading
     * that as absence is how a bucket-level misconfiguration - renamed, deleted, or a credential whose
     * access to it was revoked - turns into content silently appearing not to exist, with no operational
     * alarm anywhere. {@code GetObject} does distinguish the two, because its 404 carries a body and
     * therefore an error code, which is why only the metadata path needs this.
     *
     * <p><strong>What it costs.</strong> One {@code HeadBucket} request, and ONLY on the absence path: a
     * key the store holds is answered by the single {@code HeadObject} as before. Absence is the rare
     * answer in a store-backed deployment, so this buys the distinction without changing the cost of a
     * normal read.
     *
     * <p><strong>The three outcomes.</strong> The bucket answers - absence is genuine and is passed on.
     * The bucket is reported gone, or the store cannot be reached at all - raised through
     * {@link #failed}, so the log names the object and the reason and the caller gets the bounded
     * operational error. The store answers something else, a 403 above all - the question could not be
     * answered, which a credential allowed to read an object but not to inspect its bucket produces, so
     * the original absence stands and one WARNING per store instance records that this deployment cannot
     * tell the two apart.
     *
     * @param key the storage key whose absence is being confirmed
     * @param absence the store's answer to the metadata request
     * @throws IOException if the bucket is gone, or the store could not be asked about it
     */
    private void confirmBucketHolds(String key, SdkException absence) throws IOException {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception failure) {
            if (failure.statusCode() == NOT_FOUND) {
                throw bucketGone(key, failure);
            }
            if (unconfirmableBucketReported.compareAndSet(false, true)) {
                Debug.logWarning("The content store reported no object under " + reference(key) + " and then"
                        + " answered [" + failure.statusCode() + "] when asked whether the bucket itself exists,"
                        + " so a missing object and a missing bucket cannot be told apart on this deployment."
                        + " The object is being reported absent. Allow the credential to inspect the bucket"
                        + " (s3:ListBucket on the bucket) to have a bucket fault raised instead. This is"
                        + " reported once per instance.", MODULE);
            }
        } catch (SdkException failure) {
            // The store could not be reached for the second question although it answered the first, which
            // is a fault however it is read - and never a reason to report content as absent.
            throw failed("inspect", key, failure);
        }
        if (Debug.verboseOn()) {
            Debug.logVerbose("The content store holds no " + reference(key) + ", and its bucket answered, so"
                    + " the object is absent rather than the bucket being gone. Original answer: "
                    + absence.getMessage(), MODULE);
        }
    }

    private FileNotFoundException absence(String key, SdkException cause) {
        FileNotFoundException absent = new FileNotFoundException("The content store holds no " + reference(key));
        absent.initCause(cause);
        return absent;
    }

    /**
     * Reports a storage failure: the detail goes to the log, the caller gets a message that names nothing
     * about the deployment.
     *
     * <p>A message raised here travels out through {@code GeneralException} into an OFBiz service error and
     * is rendered on a content screen. The bucket name, the object key and the store's own message - which
     * can carry the endpoint host - are operator information, not information for whoever is looking at
     * that screen, so they are logged with the key that ties the two together and left out of the message.
     *
     * @param operation what was being attempted, for the log line
     * @param key the storage key
     * @param cause the SDK failure
     * @return the exception the caller throws
     */
    private IOException failed(String operation, String key, SdkException cause) {
        Debug.logError(cause, "The content store could not " + operation + " " + reference(key) + ": "
                + cause.getMessage(), MODULE);
        return bounded(operation);
    }

    /**
     * Reports that the BUCKET, not the object, is what the store does not have.
     *
     * <p>Logged as its own line rather than through {@link #failed}, because a store that answers a
     * {@code HeadBucket} with 404 sends no message with it - a HEAD response has no body - so the generic
     * line would say only "Status Code: 404" and leave an operator to work out which of the two things was
     * missing. This says which, and what to look at. The caller is given the same bounded message every
     * other inspection failure produces, so nothing about the deployment reaches a rendered page.
     *
     * @param key the storage key that was being inspected
     * @param cause the store's answer to the bucket question
     * @return the exception the caller throws
     */
    private IOException bucketGone(String key, SdkException cause) {
        Debug.logError(cause, "The content store bucket [" + bucket + "] does not exist, so nothing can be read"
                + " from it and " + reference(key) + " is reported as a store fault rather than as content that"
                + " was never stored. Check content.store.s3.bucket (OFBIZ_S3_BUCKET), the endpoint the"
                + " instance is configured with, and whether the bucket has been renamed or removed.", MODULE);
        return bounded("inspect");
    }

    /**
     * Returns the message a caller is given for a storage failure, which names nothing about the deployment.
     *
     * @param operation what was being attempted
     * @return the exception the caller throws
     */
    private static IOException bounded(String operation) {
        return new IOException("The content store could not " + operation + " the requested content."
                + " The server log records which object and why.");
    }

    private String reference(String key) {
        return "[" + bucket + "/" + key + "]";
    }

    /**
     * Resolves the requested server-side encryption mode, refusing a value that cannot be applied.
     *
     * @return the mode, one of {@code none}, {@code sse-s3} or {@code sse-kms}
     * @throws GeneralException if the configured value is not one of those three
     */
    private static String encryptionMode() throws GeneralException {
        String configured = ContentStoreFactory.setting(ENCRYPTION_PROPERTY, ENCRYPTION_NONE)
                .toLowerCase(Locale.ROOT);
        if (configured.isEmpty()) {
            return ENCRYPTION_NONE;
        }
        if (!ENCRYPTION_NONE.equals(configured) && !ENCRYPTION_SSE_S3.equals(configured)
                && !ENCRYPTION_SSE_KMS.equals(configured)) {
            // Refused rather than defaulted: silently storing content unencrypted after being asked to
            // encrypt it would be the one failure mode this setting exists to prevent.
            throw new GeneralException(ENCRYPTION_PROPERTY + " [" + configured + "] must be "
                    + ENCRYPTION_NONE + ", " + ENCRYPTION_SSE_S3 + " or " + ENCRYPTION_SSE_KMS + ".");
        }
        return configured;
    }

    /**
     * Reports an endpoint the SDK would take from the environment while none is configured here.
     *
     * <p>The SDK honours {@code AWS_ENDPOINT_URL_S3} and {@code AWS_ENDPOINT_URL} on its own. With
     * {@code content.store.s3.endpoint} blank, the validated configuration surface would then not describe
     * where content actually goes. The variable is reported at WARNING - naming it and its value, which is
     * an address and not a credential - rather than refused, because a deployment that sets it deliberately
     * must keep working; what must not happen is that it goes unnoticed.
     */
    private static void reportAmbientEndpoint() {
        for (String variable : AMBIENT_ENDPOINT_VARIABLES) {
            String ambient = System.getenv(variable);
            if (UtilValidate.isNotEmpty(ambient)) {
                Debug.logWarning("The content store endpoint is not configured, but the environment sets "
                        + variable + "=" + ambient + ", which the AWS SDK applies on its own. Content will be"
                        + " stored at that endpoint. Set " + ENDPOINT_PROPERTY + " (OFBIZ_S3_ENDPOINT) to the"
                        + " endpoint this deployment intends to use, or unset " + variable + ".", MODULE);
                return;
            }
        }
    }
}
