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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.utils.SdkAutoCloseable;

/**
 * The S3-compatible object-storage provider, which holds content in a bucket rather than on any
 * instance's disk.
 *
 * <p>Selected by {@code content.store.provider=s3}. It is the provider that makes an instance
 * genuinely replaceable: nothing durable is written locally, so content survives the instance that
 * accepted it. The same implementation targets Amazon S3 and any S3-compatible store - MinIO,
 * Ceph - because the endpoint and the addressing style are configuration:
 * {@code content.store.s3.endpoint} supplies an {@code endpointOverride} and
 * {@code content.store.s3.path.style} selects path-style addressing, which most non-AWS stores
 * require.
 *
 * <p><strong>Configuration</strong>, all read from the {@code content} resource, and all supplied
 * from the environment by {@code docker/docker-entrypoint.sh} so that no credential is committed:
 * {@code content.store.s3.bucket} and {@code content.store.s3.region} are required;
 * {@code content.store.s3.endpoint} is optional and means Amazon S3 when blank;
 * {@code content.store.s3.access.key.id} and {@code content.store.s3.secret.access.key} are used
 * together, and when both are blank the AWS default credential chain - instance role, container
 * credentials, shared profile, environment - is used instead; supplying exactly one of them is a
 * contradiction and is refused. The chain is named explicitly rather than left to the builder's
 * implicit default, so which identity a deployment authenticates as is a decision this code records
 * rather than one that follows from what the SDK happens to do.
 *
 * <p><strong>Keys are scoped, and an unscoped key is refused.</strong> A bucket is one flat
 * namespace that every instance and, in a multi-tenant deployment, every tenant shares. This
 * provider therefore accepts only the identity-derived key {@link ContentStoreFactory} mints -
 * {@code dataresource/<scope>/<dataResourceId>}, three segments, the first of them
 * {@value ContentStoreFactory#IDENTITY_KEY_NAMESPACE} - and refuses anything else before a request
 * is issued. That refusal is what makes the scoping worth something: were a bare path accepted, two
 * tenants recording the same path would address one object, and a recorded path would decide which
 * object a read returns. {@code content.store.s3.key.prefix} is prepended to every key, so one
 * bucket can hold several deployments; the prefix separates deployments and the key's own scope
 * segment separates tenants.
 *
 * <p><strong>Deadlines and retries are the deployment's decision.</strong>
 * {@code content.store.s3.api.timeout.millis} bounds a whole storage call including its retries,
 * {@code content.store.s3.attempt.timeout.millis} bounds one attempt within it, and
 * {@code content.store.s3.max.retries} caps how many attempts follow a failure. Without them a call
 * to an unreachable endpoint is bounded only by the SDK's socket timeouts and default retry count,
 * which holds a request thread far longer than rendering a page may take; with them a store that is
 * down costs a bounded wait and then a reported failure. The SDK's standard retry mode keeps its own
 * retry-capacity throttle on top of the cap, which is what stops every request retrying at once
 * while a store is failing.
 *
 * <p><strong>Endpoint safety.</strong> Every object request carries this deployment's credential,
 * so the endpoint is validated before a client is built: it must be an absolute {@code http} or
 * {@code https} URI with a host, no user information and no query or fragment, and an instance
 * metadata address is refused outright in every configuration because a request sent there would
 * hand out the instance's own role credentials. No refusal repeats the configured endpoint back,
 * since an endpoint can itself carry a credential and a refusal is destined for a log. A plaintext
 * {@code http} endpoint is accepted with a warning, for a local development store; the container
 * entry point refuses it in the deployed profile, which is where that policy belongs.
 *
 * <p><strong>Absence is one error code, not one status code.</strong> Only {@code NoSuchKey} means
 * "nothing is stored here". A missing bucket, a wrong endpoint and a refused credential are
 * failures, and every one of them can arrive as HTTP 404: a bucket that does not exist answers
 * {@code NoSuchBucket} with status 404, and an endpoint that is not an object store at all answers
 * 404 with no error code whatsoever. Classifying by status would therefore report a misconfigured
 * deployment as content that does not exist, which is how an outage becomes silent data loss - so
 * classification is by error code alone and anything else is propagated.
 *
 * <p>One case cannot be told apart at the protocol level and is not pretended otherwise:
 * {@code exists} issues a {@code HEAD}, which has no response body, so a store answering 404 for a
 * missing bucket is indistinguishable from one answering 404 for a missing key and the SDK reports
 * both as {@code NoSuchKey}. {@code exists} therefore answers {@code false} for a bucket that is not
 * there. That is bounded and safe: a bucket which does not exist fails loudly on the very next
 * {@code get}, {@code openStream}, {@code put} or {@code delete}, all of which carry a body and all
 * of which report {@code NoSuchBucket} as the failure it is. The alternative - a bucket probe on
 * every existence check - would double the request count on a read path and still be racy.
 *
 * <p><strong>Exception translation, and what a message may say.</strong> No SDK type escapes the
 * {@link ContentStore} contract. A missing object is a {@link FileNotFoundException} from
 * {@code get}/{@code openStream} and {@code false} from {@code exists}; every other store failure is
 * an {@link IOException}. A thrown message is fixed text plus an opaque reference and says nothing
 * else - not the bucket, not the object key, not the store's own message - because it can reach a
 * rendered page. The bucket, the key and the redacted status, error code and request id go to the
 * log under the same reference, so an operator joins the two without the message having disclosed
 * where this deployment keeps its content or what it calls it. The SDK failure is retained as the
 * cause for anything that inspects it.
 *
 * <p><strong>A whole-object read is bounded.</strong> {@code get} refuses content larger than
 * {@link ContentStoreFactory#maxObjectSize}, checking the declared length first and then the bytes
 * actually delivered, so neither an oversized object nor a store that understates its size can
 * exhaust the heap of the instance reading it. A refused or failed read aborts the connection rather
 * than draining it, so refusing costs no bandwidth. {@code openStream} is unbounded by design and is
 * what content of a size an uploader chose is served through.
 *
 * <p>Thread safe: {@code S3Client} is thread safe, and everything else the instance holds - the
 * bucket, the key prefix and the credential provider - is immutable and set once in the constructor.
 * The one operation that is not safe to race with a request is {@link #close}, which is why it is
 * package-private and reached only from the factory that owns the instance.
 */
public final class S3ContentStore implements ContentStore {

    private static final String MODULE = S3ContentStore.class.getName();

    /** The prefix every key is placed under, so one bucket can hold several deployments. */
    private static final String KEY_PREFIX_PROPERTY = "content.store.s3.key.prefix";

    /** The deadline for a whole storage call, retries included. */
    private static final String API_TIMEOUT_PROPERTY = "content.store.s3.api.timeout.millis";

    /** The deadline for one attempt within a storage call. */
    private static final String ATTEMPT_TIMEOUT_PROPERTY = "content.store.s3.attempt.timeout.millis";

    /** The number of retries allowed after a failed first attempt. */
    private static final String MAX_RETRIES_PROPERTY = "content.store.s3.max.retries";

    /** The committed whole-call deadline, in milliseconds. */
    private static final long DEFAULT_API_TIMEOUT = 30000L;

    /** The committed single-attempt deadline, in milliseconds. */
    private static final long DEFAULT_ATTEMPT_TIMEOUT = 10000L;

    /** The committed retry cap. */
    private static final long DEFAULT_MAX_RETRIES = 3L;

    /** The shortest whole-call deadline accepted; below it no round trip could complete. */
    private static final long MINIMUM_API_TIMEOUT = 1000L;

    /** The shortest single-attempt deadline accepted. */
    private static final long MINIMUM_ATTEMPT_TIMEOUT = 500L;

    /** The longest deadline accepted, which is what keeps a mistyped value from meaning "forever". */
    private static final long MAXIMUM_TIMEOUT = 600000L;

    /** The largest retry cap accepted. */
    private static final long MAXIMUM_MAX_RETRIES = 10L;

    /** The number of segments an object key carries: the namespace, the scope and the identifier. */
    private static final int KEY_SEGMENT_COUNT = 3;

    /**
     * The one error code that means the key holds nothing.
     *
     * <p>Every other code, and a 404 carrying no code at all, is a failure. {@code NoSuchBucket} in
     * particular is a configuration error and reporting it as absence would hide it.
     */
    private static final String ABSENT_ERROR_CODE = "NoSuchKey";

    /**
     * Link-local addresses that answer with cloud instance credentials: the EC2/GCE/Azure instance
     * metadata service, its IPv6 form, and the ECS task metadata endpoint. An endpoint naming one
     * of these is refused with no override, because the response to a request sent there is a set
     * of role credentials for the whole instance.
     */
    private static final String[] INSTANCE_METADATA_HOSTS = {
        "169.254.169.254",
        "[fd00:ec2::254]",
        "fd00:ec2::254",
        "169.254.170.2",
    };

    private final S3Client s3Client;
    private final String bucket;
    private final String keyPrefix;
    private final AwsCredentialsProvider credentialsProvider;

    /** The delegator every {@code content.store.*} value is read through; null reads the file alone. */
    private final Delegator delegator;

    /**
     * Constructs the provider from the configuration a delegator resolves, building the client that
     * configuration describes.
     *
     * <p>This is the constructor {@link ContentStoreFactory} uses. It is the only place the AWS
     * SDK is named from a configuration-driven path, which is what keeps the dependency inert in
     * every deployment that does not select this provider.
     *
     * <p>The delegator is the one that selected this provider, so every value below is read from the
     * same place the selection was. Without it a deployment could set {@code content.store.provider}
     * through a {@code SystemProperty} row, watch the provider be selected, and then be told there is
     * no bucket - because the bucket had been read from the property file that never mentioned one.
     *
     * @param delegator the delegator every configuration value is read through; may be null, in which
     *     case only {@code content.properties} is consulted
     * @throws GeneralException if the configuration is incomplete or contradictory - no bucket, no
     *     region, an unusable endpoint, an unusable key prefix, or exactly one of the two credential
     *     properties
     */
    public S3ContentStore(Delegator delegator) throws GeneralException {
        String configuredBucket = property("content.store.s3.bucket", delegator);
        String region = property("content.store.s3.region", delegator);
        String endpoint = property("content.store.s3.endpoint", delegator);
        String accessKeyId = property("content.store.s3.access.key.id", delegator);
        String secretAccessKey = property("content.store.s3.secret.access.key", delegator);
        boolean pathStyle = "true".equalsIgnoreCase(property("content.store.s3.path.style", delegator));

        if (UtilValidate.isEmpty(configuredBucket)) {
            throw new GeneralException("content.store.s3.bucket is required when content.store.provider=s3;"
                    + " there is no default bucket");
        }
        if (UtilValidate.isEmpty(region)) {
            throw new GeneralException("content.store.s3.region is required when content.store.provider=s3;"
                    + " use us-east-1 for a store that has no regions of its own");
        }
        if (UtilValidate.isEmpty(accessKeyId) != UtilValidate.isEmpty(secretAccessKey)) {
            throw new GeneralException("content.store.s3.access.key.id and content.store.s3.secret.access.key must"
                    + " be supplied together; leave both blank to authenticate with the AWS default credential"
                    + " chain instead");
        }

        // Named explicitly in both branches. Leaving the credential provider unset would give the
        // same behaviour today by accident rather than by decision, and F14's point stands: which
        // principal a deployment authenticates as is not something to leave to a builder default.
        AwsCredentialsProvider credentials = UtilValidate.isNotEmpty(accessKeyId)
                ? StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey))
                // builder().build() rather than create(): create() is deprecated because it hands out
                // a shared singleton, and an instance this provider owns is one it can also release.
                : DefaultCredentialsProvider.builder().build();

        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .forcePathStyle(pathStyle)
                .credentialsProvider(credentials)
                .overrideConfiguration(deadlines(delegator));
        if (UtilValidate.isNotEmpty(endpoint)) {
            builder.endpointOverride(validatedEndpoint(endpoint));
        }
        try {
            this.s3Client = builder.build();
        } catch (SdkException e) {
            closeQuietly(credentials);
            // The SDK's own text is logged rather than concatenated or attached: GeneralException.getMessage()
            // appends the message of any cause it is given, and an SDK build failure quotes the configuration
            // it rejected - which here is an endpoint and a credential pair. Whoever has to correct the
            // configuration reads the log; whoever merely receives the refusal does not need the value.
            Debug.logError(e, "The S3 content store client could not be built from the content.store.s3.*"
                    + " configuration", MODULE);
            throw new GeneralException("The S3 content store client could not be built from the"
                    + " content.store.s3.* configuration; the store's own diagnostic is in the server log");
        }
        this.bucket = configuredBucket;
        this.keyPrefix = validatedKeyPrefix(property(KEY_PREFIX_PROPERTY, delegator));
        this.credentialsProvider = credentials;
        this.delegator = delegator;
        Debug.logInfo("Content storage provider s3 initialised with endpoint-override ["
                + UtilValidate.isNotEmpty(endpoint) + "], path-style [" + pathStyle + "], static-credentials ["
                + UtilValidate.isNotEmpty(accessKeyId) + "], key-prefix [" + UtilValidate.isNotEmpty(keyPrefix)
                + "]", MODULE);
    }

    /**
     * Builds the deadline and retry configuration the client is bound by.
     *
     * <p>Read through {@link ContentStoreFactory#boundedLong}, so an unusable value is reported once
     * and the committed default applies rather than the setting silently becoming "unbounded". An
     * attempt deadline longer than the whole-call deadline is a contradiction - the attempt could
     * never finish inside its call - so it is reported and the committed pair applies.
     *
     * @param delegator the delegator the three settings are read through; may be null
     * @return the override configuration to build the client with
     */
    private static ClientOverrideConfiguration deadlines(Delegator delegator) {
        long apiTimeout = ContentStoreFactory.boundedLong(API_TIMEOUT_PROPERTY, delegator, DEFAULT_API_TIMEOUT,
                MINIMUM_API_TIMEOUT, MAXIMUM_TIMEOUT);
        long attemptTimeout = ContentStoreFactory.boundedLong(ATTEMPT_TIMEOUT_PROPERTY, delegator,
                DEFAULT_ATTEMPT_TIMEOUT, MINIMUM_ATTEMPT_TIMEOUT, MAXIMUM_TIMEOUT);
        if (attemptTimeout > apiTimeout) {
            ContentStoreFactory.reportUnusableValue(ATTEMPT_TIMEOUT_PROPERTY, String.valueOf(attemptTimeout),
                    "is longer than the " + API_TIMEOUT_PROPERTY + " value of " + apiTimeout
                            + " milliseconds it has to complete inside");
            apiTimeout = DEFAULT_API_TIMEOUT;
            attemptTimeout = DEFAULT_ATTEMPT_TIMEOUT;
        }
        int maxRetries = (int) ContentStoreFactory.boundedLong(MAX_RETRIES_PROPERTY, delegator, DEFAULT_MAX_RETRIES,
                0L, MAXIMUM_MAX_RETRIES);
        return ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofMillis(apiTimeout))
                .apiCallAttemptTimeout(Duration.ofMillis(attemptTimeout))
                // The configured cap is applied to the strategy the SDK selects for S3, which is the
                // standard one: its retry-capacity throttle stays in force, so a failing store is not
                // retried by every request at once.
                .retryStrategy(strategy -> strategy.maxAttempts(maxRetries + 1))
                .build();
    }

    /**
     * Validates the configured key prefix and normalises it to either "" or something ending in "/".
     *
     * @param configured the configured prefix, which may be blank
     * @return the prefix to place every key under, "" when none is configured
     * @throws GeneralException if the prefix is absolute, carries a control character, a {@code .} or
     *     {@code ..} component, or leaves no room for a key
     */
    private static String validatedKeyPrefix(String configured) throws GeneralException {
        String trimmed = configured == null ? "" : configured.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) {
            return "";
        }
        for (int index = 0; index < trimmed.length(); index++) {
            if (Character.isISOControl(trimmed.charAt(index))) {
                throw new GeneralException(KEY_PREFIX_PROPERTY + " must not carry a control character");
            }
        }
        for (String segment : trimmed.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new GeneralException(KEY_PREFIX_PROPERTY + " must be a plain relative prefix, and [" + trimmed
                        + "] carries an empty or relative component");
            }
        }
        String prefix = trimmed + "/";
        if (prefix.getBytes(StandardCharsets.UTF_8).length >= ContentStore.MAX_KEY_LENGTH_BYTES) {
            throw new GeneralException(KEY_PREFIX_PROPERTY + " is " + prefix.length() + " characters, which leaves no"
                    + " room for a key inside the " + ContentStore.MAX_KEY_LENGTH_BYTES + " byte limit");
        }
        return prefix;
    }

    /**
     * Closes a credential provider that owns resources, ignoring one that does not.
     *
     * @param credentials the provider to release
     */
    private static void closeQuietly(AwsCredentialsProvider credentials) {
        if (credentials instanceof SdkAutoCloseable closeable) {
            closeable.close();
        }
    }

    /**
     * Package-private test seam: constructs a store around a pre-built client so that the provider
     * can be unit tested with a mocked {@code S3Client}, with no AWS configuration, no network
     * access and no credential resolution.
     *
     * <p>The key prefix is read from the configuration exactly as the public constructor reads it,
     * because a test that did not see the prefix could not tell whether a key reaches the bucket
     * where the deployment says it should. Nothing else is read, and no credential is resolved.
     *
     * @param s3Client the client every operation is issued through
     * @param bucket the bucket every request names
     * @throws GeneralException if the configured key prefix is unusable
     */
    S3ContentStore(S3Client s3Client, String bucket) throws GeneralException {
        this(s3Client, bucket, null);
    }

    /**
     * Package-private test seam: constructs a store around a pre-built client whose configuration is
     * read through a delegator, so that a test can prove a value supplied only through the
     * {@code SystemProperty} entity reaches the provider without needing a real object store.
     *
     * @param s3Client the client every operation is issued through
     * @param bucket the bucket every request names
     * @param delegator the delegator the key prefix and the read bound are resolved through; may be
     *     null, in which case only {@code content.properties} is consulted
     * @throws GeneralException if the configured key prefix is unusable
     */
    S3ContentStore(S3Client s3Client, String bucket, Delegator delegator) throws GeneralException {
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.keyPrefix = validatedKeyPrefix(property(KEY_PREFIX_PROPERTY, delegator));
        // No credential provider of its own: the client was supplied already built, so there is
        // nothing here that this instance is responsible for releasing.
        this.credentialsProvider = null;
        this.delegator = delegator;
    }

    /**
     * Releases the client this provider owns, and with it the connection pool and the threads behind
     * it.
     *
     * <p>Package-private on purpose. {@link ContentStore} declares five operations and no close,
     * which the plan freezes, so the lifecycle hook is visible to {@link ContentStoreFactory} - which
     * owns every provider instance and is the only thing that can know one is no longer reachable -
     * and to nothing else. A caller that could close a shared provider would leave every other
     * request holding a closed client.
     *
     * <p>Idempotent as far as this provider is concerned: the SDK's own {@code close} tolerates being
     * called more than once, and the factory closes a displaced provider exactly once in any case.
     */
    void close() {
        try {
            s3Client.close();
            // Closed after the client and only when this instance built it: the default chain keeps a
            // client of its own for instance metadata, and the SDK closes only what it created itself.
            if (credentialsProvider != null) {
                closeQuietly(credentialsProvider);
            }
        } catch (RuntimeException e) {
            // Reported and swallowed deliberately: this runs while a provider is being replaced or
            // while the JVM is stopping, and neither has anywhere to report a failure to. Letting it
            // out of the shutdown hook would suppress the rest of the cleanup.
            Debug.logWarning("The S3 content store client could not be closed cleanly: "
                    + e.getClass().getName(), MODULE);
        }
    }

    @Override
    public void put(String key, byte[] data) throws GeneralException, IOException {
        if (data == null) {
            throw new GeneralException("Cannot store null content for content store key [" + key + "]");
        }
        String objectKey = objectKey(key);
        try {
            s3Client.putObject(PutObjectRequest.builder().bucket(bucket).key(objectKey).build(),
                    RequestBody.fromBytes(data));
        } catch (SdkException e) {
            throw storeFailure("store", objectKey, e);
        }
    }

    @Override
    public byte[] get(String key) throws GeneralException, IOException {
        String objectKey = objectKey(key);
        long limit = ContentStoreFactory.maxObjectSize(delegator);
        ResponseInputStream<GetObjectResponse> content;
        try {
            content = s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(objectKey).build());
        } catch (NoSuchKeyException absent) {
            throw absent(key, objectKey, absent);
        } catch (S3Exception e) {
            if (isAbsence(e)) {
                throw absent(key, objectKey, e);
            }
            throw storeFailure("read", objectKey, e);
        } catch (SdkException e) {
            throw storeFailure("read", objectKey, e);
        }
        // Released on every path below, and released by aborting rather than closing whenever the
        // content was not read to its end: closing a partly-read response drains the rest of the
        // object off the wire, which is exactly the transfer a refusal exists to avoid.
        boolean readToEnd = false;
        try {
            byte[] read = boundedRead(content, objectKey, limit);
            readToEnd = true;
            return read;
        } catch (SdkException e) {
            throw storeFailure("read", objectKey, e);
        } finally {
            if (readToEnd) {
                content.close();
            } else {
                content.abort();
            }
        }
    }

    @Override
    public InputStream openStream(String key) throws GeneralException, IOException {
        String objectKey = objectKey(key);
        try {
            // Handed straight to the caller, who owns closing it. Nothing happens between the call
            // and the return that could fail, so there is no path here that leaks the response.
            return s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(objectKey).build());
        } catch (NoSuchKeyException absent) {
            throw absent(key, objectKey, absent);
        } catch (S3Exception e) {
            if (isAbsence(e)) {
                throw absent(key, objectKey, e);
            }
            throw storeFailure("open", objectKey, e);
        } catch (SdkException e) {
            throw storeFailure("open", objectKey, e);
        }
    }

    @Override
    public boolean exists(String key) throws GeneralException, IOException {
        String objectKey = objectKey(key);
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
            return true;
        } catch (NoSuchKeyException absent) {
            // Absence is an answer, not a failure: the contract requires false rather than a throw.
            // A HEAD carries no response body, so this is also what a missing bucket looks like; the
            // class documentation records why that is accepted here and caught on every other path.
            Debug.logVerbose(absent, "The S3 content store holds nothing under [" + objectKey + "]", MODULE);
            return false;
        } catch (S3Exception e) {
            if (isAbsence(e)) {
                Debug.logVerbose(e, "The S3 content store holds nothing under [" + objectKey + "]", MODULE);
                return false;
            }
            throw storeFailure("test", objectKey, e);
        } catch (SdkException e) {
            throw storeFailure("test", objectKey, e);
        }
    }

    @Override
    public void delete(String key) throws GeneralException, IOException {
        String objectKey = objectKey(key);
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
        } catch (NoSuchKeyException absent) {
            // Idempotent by contract, and S3 itself reports a delete of an absent object as success,
            // so this is only reached by a store that reports the miss instead.
            Debug.logVerbose(absent, "The S3 content store already holds nothing under [" + objectKey + "]", MODULE);
        } catch (S3Exception e) {
            if (!isAbsence(e)) {
                throw storeFailure("remove", objectKey, e);
            }
            Debug.logVerbose(e, "The S3 content store already holds nothing under [" + objectKey + "]", MODULE);
        } catch (SdkException e) {
            throw storeFailure("remove", objectKey, e);
        }
    }

    /**
     * Reads a response into an array without letting it exceed the configured ceiling.
     *
     * <p>Checked twice, because either check alone is insufficient. The declared length is checked
     * first so an oversized object is refused before a byte of it is transferred. The bytes actually
     * delivered are then bounded as well, because a declared length is something the store said
     * rather than something it is held to: a store that understates it, or omits it, must not be able
     * to make this method allocate more than the ceiling allows.
     *
     * @param content the response to read, positioned at its start
     * @param objectKey the key the response is for, named in the log rather than in a thrown message
     * @param limit the greatest number of bytes that may be returned
     * @return the content, never longer than {@code limit}
     * @throws IOException if the content is longer than {@code limit}, or if the response cannot be
     *     read
     */
    private byte[] boundedRead(ResponseInputStream<GetObjectResponse> content, String objectKey, long limit)
            throws IOException {
        Long declared = content.response() == null ? null : content.response().contentLength();
        if (declared != null && declared > limit) {
            throw oversized(objectKey, String.valueOf(declared), limit);
        }
        byte[] read = content.readNBytes((int) limit);
        // One byte past the ceiling is enough to know the content does not fit; the rest is never
        // transferred, because the caller aborts the response instead of draining it.
        if (content.read() != -1) {
            throw oversized(objectKey, "more than " + limit, limit);
        }
        return read;
    }

    /**
     * Reports content that does not fit inside the configured ceiling.
     *
     * @param objectKey the key the content is stored under, for the log
     * @param size the size as far as it is known, for the log
     * @param limit the ceiling that was exceeded
     * @return the exception to throw, whose message names neither the bucket nor the key
     */
    private IOException oversized(String objectKey, String size, long limit) {
        String reference = reference();
        Debug.logError("Content store refusal [" + reference + "]: object [" + objectKey + "] in bucket [" + bucket
                + "] is " + size + " bytes, over the " + ContentStoreFactory.MAX_OBJECT_SIZE_PROPERTY + " ceiling of "
                + limit + "; content this large has to be streamed rather than read whole", MODULE);
        return new IOException("The requested content is larger than this instance may read in one piece."
                + " Reference [" + reference + "].");
    }

    /**
     * Validates a storage key and places it under the configured prefix.
     *
     * <p>The grammar every provider shares is applied first, through
     * {@link ContentStore#requireUsableKey(String)}, so that this provider refuses exactly the keys the
     * filesystem provider refuses and content migrated between the two keeps every key it had.
     *
     * <p>The scoping is enforced here and not only where the key is minted, because this class is
     * reachable without coming through {@link ContentStoreFactory} and a bucket is a namespace every
     * instance and every tenant shares. Only the three-segment identity key is accepted:
     * {@value ContentStoreFactory#IDENTITY_KEY_NAMESPACE}, then the tenant scope, then the
     * {@code dataResourceId}. A bare path is refused rather than stored, which is what keeps a
     * recorded {@code objectInfo} value from deciding which object a read returns and keeps two
     * tenants that recorded the same path from addressing one object.
     *
     * @param key the provider-relative storage key
     * @return the object key to name in a request, prefix included
     * @throws GeneralException if the key breaks the shared key grammar, is not the scoped identity
     *     key this provider accepts, or exceeds the object-key length limit once the prefix is applied
     */
    private String objectKey(String key) throws GeneralException {
        // The grammar every provider shares first, from its one implementation, so that this provider
        // refuses exactly what the filesystem provider refuses and content migrated between the two keeps
        // every key it had. What follows is what this provider alone requires of a key.
        ContentStore.requireUsableKey(key);
        String[] segments = key.split("/", -1);
        boolean scoped = segments.length == KEY_SEGMENT_COUNT
                && ContentStoreFactory.IDENTITY_KEY_NAMESPACE.equals(segments[0])
                && UtilValidate.isNotEmpty(segments[1]) && UtilValidate.isNotEmpty(segments[2])
                && !"..".equals(segments[1]) && !"..".equals(segments[2])
                && !".".equals(segments[1]) && !".".equals(segments[2]);
        if (!scoped) {
            throw new GeneralException("The object store accepts only a tenant-scoped content key of the form "
                    + ContentStoreFactory.IDENTITY_KEY_NAMESPACE + "/<scope>/<dataResourceId>, and [" + key
                    + "] is not one; an unscoped key would share one object between tenants");
        }
        String objectKey = keyPrefix + key;
        int length = objectKey.getBytes(StandardCharsets.UTF_8).length;
        if (length > ContentStore.MAX_KEY_LENGTH_BYTES) {
            throw new GeneralException("A content store key must be at most " + ContentStore.MAX_KEY_LENGTH_BYTES
                    + " bytes once the configured prefix is applied, and this one is " + length);
        }
        return objectKey;
    }

    /**
     * Validates an endpoint override before this deployment's credentials can be sent to it.
     *
     * @param endpoint the configured endpoint
     * @return the endpoint as a URI
     * @throws GeneralException if it is not a plain absolute http or https URI with a host, if it
     *     carries user information, a query or a fragment, or if it names an instance metadata
     *     address
     */
    private URI validatedEndpoint(String endpoint) throws GeneralException {
        URI uri;
        try {
            uri = new URI(endpoint);
        } catch (URISyntaxException e) {
            // The value is never echoed, because the case this exists to catch is an endpoint carrying a
            // credential. That rules out nesting the cause as much as quoting the value: GeneralException
            // appends a nested exception's message to its own, and URISyntaxException always reports the whole
            // input it was handed. Its reason and position carry the entire diagnostic and none of the value.
            throw new GeneralException("content.store.s3.endpoint is not a valid URI: " + e.getReason()
                    + (e.getIndex() < 0 ? "" : " at index " + e.getIndex()));
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new GeneralException("content.store.s3.endpoint must be an absolute http or https URI");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new GeneralException("content.store.s3.endpoint must name a host and must carry no user"
                    + " information, query or fragment");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        for (String metadataHost : INSTANCE_METADATA_HOSTS) {
            if (metadataHost.equals(host)) {
                throw new GeneralException("content.store.s3.endpoint must not name a cloud instance metadata"
                        + " address; a request sent there would disclose the instance's own role credentials");
            }
        }
        if ("http".equals(scheme)) {
            Debug.logWarning("content.store.s3.endpoint uses http, so object-store traffic and the credentials it"
                    + " carries are not encrypted. This is only appropriate for a local development store.", MODULE);
        }
        return uri;
    }

    /**
     * Reports whether an S3 failure means the key holds nothing, as opposed to meaning the store
     * could not be used at all.
     *
     * <p>Decided by error code, never by status code. A missing bucket answers 404 with
     * {@code NoSuchBucket}, and an endpoint that is not an object store answers 404 with no error
     * code at all; treating either as absence would report a misconfigured deployment as content
     * that simply is not there, which turns a loud failure into missing content. So only
     * {@value #ABSENT_ERROR_CODE} is absence, and a failure that carries no error code is a failure.
     *
     * @param e the SDK failure
     * @return {@code true} only when the store reported that this key holds nothing
     */
    private static boolean isAbsence(S3Exception e) {
        if (e instanceof NoSuchBucketException) {
            // Named rather than left to the error code, so that a store which reports a missing
            // bucket with some other code still cannot be mistaken for a missing object.
            return false;
        }
        AwsErrorDetails details = e.awsErrorDetails();
        return details != null && ABSENT_ERROR_CODE.equals(details.errorCode());
    }

    /**
     * Builds the absence report the contract requires, so that a caller never has to know an SDK
     * type to recognise it.
     *
     * <p>The message names the key the caller asked for and nothing else. The bucket and the
     * prefixed object key are deployment layout, so they go to the log rather than into an exception
     * that a rendered page could show.
     *
     * @param key the key as the caller supplied it
     * @param objectKey the prefixed object key that holds nothing, for the log
     * @param cause the SDK failure that reported it, retained for anything that inspects it
     * @return the exception to throw
     */
    private FileNotFoundException absent(String key, String objectKey, SdkException cause) {
        Debug.logVerbose(cause, "The S3 content store holds no content under object [" + objectKey + "] in bucket ["
                + bucket + "]", MODULE);
        FileNotFoundException absent = new FileNotFoundException("No content is stored under [" + key + "]");
        absent.initCause(cause);
        return absent;
    }

    /**
     * Translates an SDK failure into the contract's failure without disclosing where this deployment
     * keeps its content.
     *
     * <p>The thrown message is fixed text plus an opaque reference. It carries no bucket, no object
     * key and none of the store's own message, because an {@link IOException} raised while rendering
     * content can reach the rendered page. The reference is logged beside the bucket, the key and the
     * redacted status, error code and request id, so an operator joins the report an end user quotes
     * to the failure that produced it. The SDK failure is retained as the cause, and the full detail
     * is logged only when verbose logging is on.
     *
     * @param operation what was being attempted, for the diagnostic
     * @param objectKey the key the request named
     * @param cause the SDK failure
     * @return the exception to throw
     */
    private IOException storeFailure(String operation, String objectKey, SdkException cause) {
        String reference = reference();
        Debug.logError("Content store failure [" + reference + "]: could not " + operation + " object [" + objectKey
                + "] in bucket [" + bucket + "]; " + redacted(cause), MODULE);
        if (Debug.verboseOn()) {
            // Behind the verbose switch on purpose: the SDK failure's own message can quote the
            // request it was building, so an operator opts in to seeing it rather than having it
            // written to every log by default.
            Debug.logVerbose(cause, "Content store failure [" + reference + "] detail", MODULE);
        }
        return new IOException("The content store could not " + operation + " the requested content."
                + " Reference [" + reference + "].", cause);
    }

    /**
     * Describes an SDK failure using only fields that identify it without quoting it.
     *
     * @param cause the SDK failure
     * @return the status, error code and request id where the failure carries them, otherwise the
     *     failure's type
     */
    private static String redacted(SdkException cause) {
        if (!(cause instanceof SdkServiceException service)) {
            // A client-side failure - a deadline, a connection that could not be made - carries no
            // service fields. Its type is the whole diagnostic that can be given without its message.
            return "failure [" + cause.getClass().getSimpleName() + "]";
        }
        String errorCode = cause instanceof S3Exception s3 && s3.awsErrorDetails() != null
                ? s3.awsErrorDetails().errorCode()
                : "";
        return "status [" + service.statusCode() + "], error-code [" + (errorCode == null ? "" : errorCode)
                + "], request-id [" + (service.requestId() == null ? "" : service.requestId()) + "]";
    }

    /**
     * Mints the opaque reference that joins a report an end user can see to the log line that
     * explains it.
     *
     * @return a reference that identifies one failure and describes nothing about the deployment
     */
    private static String reference() {
        return UUID.randomUUID().toString();
    }

    /**
     * Reads one of the provider's configuration properties.
     *
     * <p>Read through {@link ContentStoreFactory#propertyValue}, the one accessor this package reads
     * {@code content.store.*} with, so that a value overridden through the {@code SystemProperty}
     * entity reaches this provider exactly as it reaches the selector that chose it.
     *
     * @param name the property name
     * @param delegator the delegator the value is read through; may be null, in which case only
     *     {@code content.properties} is consulted
     * @return the configured value, trimmed, or an empty string when it is not set
     */
    private static String property(String name, Delegator delegator) {
        return ContentStoreFactory.propertyValue(name, delegator);
    }
}
