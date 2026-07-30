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

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpConfigurationOption;
import software.amazon.awssdk.http.SdkHttpService;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.utils.AttributeMap;

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
 * <p><strong>Every wait is bounded, and repeated failure stops being retried.</strong> A request thread that
 * reaches an object store must not be able to wait indefinitely, and a store that is down must not be given a
 * fresh outbound attempt by every arriving request. Four independent bounds are configured explicitly rather
 * than inherited from whatever the SDK, the JVM or the environment happens to default to:
 * <ul>
 *   <li><strong>Transport deadlines</strong> - connection establishment, connection acquisition from the pool,
 *       socket reads and socket writes, from {@code content.store.s3.connect.timeout.millis} and
 *       {@code content.store.s3.read.timeout.millis}. These are applied to the HTTP client the SDK builds for
 *       this provider, so they hold no matter which HTTP implementation is on the class path.</li>
 *   <li><strong>Per-attempt deadline</strong> - {@code content.store.s3.call.attempt.timeout.millis}, which
 *       bounds one attempt end to end, so a connection that is established and then stalls is abandoned.</li>
 *   <li><strong>Total call deadline</strong> - {@code content.store.s3.call.timeout.millis}, which bounds the
 *       whole operation including every retry, so the worst case a request thread can experience is a single
 *       known number rather than the product of the retry count and the attempt deadline.</li>
 *   <li><strong>Bounded retries</strong> - {@code content.store.s3.max.attempts}, replacing the SDK's default
 *       attempt count with an explicit one while keeping the SDK's service-appropriate backoff.</li>
 * </ul>
 *
 * <p><strong>Service breaker.</strong> On top of those deadlines, consecutive failures trip a breaker: after
 * {@code content.store.s3.breaker.failure.threshold} failures in a row the provider refuses operations
 * outright for {@code content.store.s3.breaker.reset.millis} instead of letting every arriving request spend
 * its attempt deadline discovering the same outage. When the window elapses one request is let through, and a
 * single further failure re-opens the breaker immediately, so a store that is still down costs one probe per
 * window rather than one probe per request.
 *
 * <p><strong>Configuration.</strong> Every property is read from the {@code content} resource. The ten below
 * describe the store, and all of them are committed blank, or {@code false}, so that no credential and nothing
 * environment-specific lives in the repository or in the container image; {@code docker/docker-entrypoint.sh}
 * injects the deployed values from the environment, having first validated each one - the bucket against the S3
 * naming rules, the region against the region grammar, the endpoint as an absolute {@code http}/{@code https}
 * URI that is neither a cloud instance metadata address nor, in the deployed profile, plaintext - and it
 * withdraws both credential variables from the environment afterwards, so the secret access key exists only in
 * a mode {@code 0600} file:
 * <ul>
 *   <li>{@code content.store.s3.bucket} - the bucket every key is stored in; required</li>
 *   <li>{@code content.store.s3.region} - the region identifier; required</li>
 *   <li>{@code content.store.s3.endpoint} - endpoint override; blank selects Amazon S3</li>
 *   <li>{@code content.store.s3.endpoint.allowlist} - the hosts an endpoint override may name; required
 *       whenever an endpoint override is configured</li>
 *   <li>{@code content.store.s3.allow.plaintext.endpoint} - development-only permission for a
 *       {@code http://} endpoint; {@code false} everywhere else</li>
 *   <li>{@code content.store.s3.credentials.provider} - {@code static} or {@code default-chain};
 *       required, because it decides which identity the deployment authenticates as</li>
 *   <li>{@code content.store.s3.access.key.id} - the access key id, for {@code static}</li>
 *   <li>{@code content.store.s3.secret.access.key} - the secret access key, for {@code static}</li>
 *   <li>{@code content.store.s3.path.style} - {@code true} to force path-style addressing</li>
 *   <li>{@code content.store.s3.key.prefix} - the key prefix new uploads are placed under</li>
 * </ul>
 *
 * <p><strong>Credential resolution is explicit, never inferred.</strong>
 * {@code content.store.s3.credentials.provider} has to name the identity source, and the two credential
 * properties have to agree with it: {@code static} requires both of them, {@code default-chain} requires
 * neither of them, and every other combination - a blank mode, an unrecognised mode, one credential without
 * the other, credentials alongside {@code default-chain} - is refused with a
 * {@link ContentStoreConfigurationException}.
 *
 * <p>Inferring the mode from whether both credentials happen to be present, which is what this provider used
 * to do, is the defect that made the rule necessary. A typo in one of the two credential property names, or a
 * secret whose injection silently failed, left one credential blank; the provider then quietly authenticated
 * with the ambient credential chain instead - the EC2 instance role, the ECS task role, a shared profile - and
 * the deployment ran with <em>a different identity from the one it was configured with</em>, typically a
 * broader one, with nothing at all reported. Making the mode explicit means the same accident is a startup
 * refusal naming the property that is wrong.
 *
 * <p><strong>Absence is narrow.</strong> Only an unambiguous object-not-found signal is reported as absence.
 * A bare HTTP 404 is not enough on its own - it is equally what a deleted bucket, a mis-routed endpoint or a
 * policy concealing an object produces - so when the store answers 404 without naming the key, the bucket is
 * probed to disambiguate and an unreachable bucket is propagated as a provider failure. Reporting a deleted
 * bucket as "this key holds no content" would let a caller conclude that content was never stored, and would
 * make {@link #delete(String)} report success for content it never removed.
 *
 * <p><strong>Bounded memory.</strong> {@link #put(String, InputStream, long)} streams content of any size
 * straight to the store with its length declared up front, and {@link #openStream(String)} streams it back;
 * neither materialises content in the heap. The whole-content convenience pair
 * {@link #put(String, byte[])} and {@link #get(String)} is bounded by
 * {@code content.store.max.memory.bytes}.
 * <p><strong>Bounded calls and lifecycle.</strong> Every client this provider builds is given an explicit
 * ceiling on a complete storage call and on a single attempt within it, so that an unreachable or stalled
 * store fails a request instead of pinning a request thread behind a load balancer. A client this provider
 * built is released through its package-private close hook when the provider is replaced or discarded, so a
 * reconfiguration leaves no connection pool behind; a client handed in through the test seam belongs to
 * whoever supplied it. The SPI itself stays closeable-free: lifecycle is the factory's concern, never the
 * caller's.
 *
 * <p><strong>Inert unless selected.</strong> {@code database} is the committed default of
 * {@code content.store.provider}; in that mode the pre-existing {@code DataResource} database-storage path is
 * used unchanged, this class is never instantiated and the AWS SDK is never touched. Nothing here runs from a
 * static initialiser, and the client itself is created only on first use, so even loading the class resolves
 * no credential and opens no connection.
 *
 * <p><strong>Where requests may go.</strong> An endpoint override decides which host every byte of this
 * deployment's content is sent to, and the SDK will faithfully address whatever it is given - including a
 * link-local address such as a cloud instance metadata service, whose reply is a set of credentials for the
 * whole instance. Four rules therefore constrain it, all applied in {@link #buildClient()} before any client
 * exists: the value must be an absolute {@code http}/{@code https} URI with a real host and with no user
 * information, query or fragment - {@code https://user:password@host} would otherwise place a credential in
 * configuration that every diagnostic echoing the endpoint would then disclose; an instance metadata address
 * is refused outright, whatever the allowlist says; a {@code http://} endpoint is refused unless
 * {@code content.store.s3.allow.plaintext.endpoint} is explicitly {@code true}, which exists for a developer
 * running a local store and which {@code docker/docker-entrypoint.sh} refuses to render in the deployed
 * profile; and the host must appear in {@code content.store.s3.endpoint.allowlist}, so a single mis-set
 * property cannot redirect content traffic to a host the deployment never named.
 *
 * <p><strong>Keys.</strong> A key is an opaque object key inside the configured bucket. Object keys are flat,
 * so no intermediate structure is ever created and, unlike a filesystem provider, no key can escape the
 * provider's root: the bucket <em>is</em> the root, and a key containing {@code ..} is just an ordinary key.
 * A key is never composed into a message or a log line as itself - it can carry the name a user gave an
 * uploaded file, and with it a person's name or a case reference - so every diagnostic identifies content by
 * the opaque, one-way reference produced by {@code ContentStoreUtil}.
 *
 * <p><strong>Whole-content reads are bounded.</strong> {@link #get(String)} materialises an object in the
 * heap, so it refuses an object larger than {@code content.store.max.get.bytes} (32 MiB by default) or than
 * the shared {@code content.store.max.memory.bytes} ceiling, and directs the caller to
 * {@link #openStream(String)}. Whichever ceiling is lower is the one that applies, and both are checked
 * against the content length the store reports before anything is transferred as well as against what is
 * actually read, so a store that under-reports cannot get past them.
 *
 * <p><strong>Failures are sanitised.</strong> An error reply from an object store can echo the request it
 * received - bucket, key, endpoint, headers, and the identity that signed it - so the SDK exception is neither
 * chained nor quoted into anything this class throws. What survives is only what is stable and safe: the HTTP
 * status, the store's request identifiers, whether a deadline expired, and the SDK exception's class name.
 * See {@link #storeFailure}.
 *
 * <p><strong>Lifecycle.</strong> The client holds a connection pool, so {@link #close()} closes it and is
 * idempotent. {@code ContentStoreFactory} closes a provider it replaces and closes the last one at JVM
 * shutdown, which is what keeps a configuration change or a lost construction race from orphaning a pool.
 *
 * <p><strong>Thread safety.</strong> An instance holds no per-request state; it is immutable apart from the
 * lazily created client, which is published under the instance lock, and the breaker counters, which are
 * atomic. One instance is therefore shared safely by many request threads, as the SPI requires.
 */
public final class S3ContentStore implements ContentStore, ContentUploadLocation {

    private static final String MODULE = S3ContentStore.class.getName();

    private static final String BUCKET_PROPERTY = "content.store.s3.bucket";
    private static final String REGION_PROPERTY = "content.store.s3.region";
    private static final String ENDPOINT_PROPERTY = "content.store.s3.endpoint";
    private static final String ENDPOINT_ALLOWLIST_PROPERTY = "content.store.s3.endpoint.allowlist";
    private static final String ALLOW_PLAINTEXT_ENDPOINT_PROPERTY = "content.store.s3.allow.plaintext.endpoint";
    private static final String CREDENTIALS_PROVIDER_PROPERTY = "content.store.s3.credentials.provider";
    private static final String ACCESS_KEY_ID_PROPERTY = "content.store.s3.access.key.id";
    private static final String SECRET_ACCESS_KEY_PROPERTY = "content.store.s3.secret.access.key";
    private static final String PATH_STYLE_PROPERTY = "content.store.s3.path.style";
    private static final String KEY_PREFIX_PROPERTY = "content.store.s3.key.prefix";
    private static final String CONNECT_TIMEOUT_PROPERTY = "content.store.s3.connect.timeout.millis";
    private static final String READ_TIMEOUT_PROPERTY = "content.store.s3.read.timeout.millis";
    private static final String CALL_TIMEOUT_PROPERTY = "content.store.s3.call.timeout.millis";
    private static final String ATTEMPT_TIMEOUT_PROPERTY = "content.store.s3.call.attempt.timeout.millis";
    private static final String MAX_ATTEMPTS_PROPERTY = "content.store.s3.max.attempts";
    private static final String BREAKER_THRESHOLD_PROPERTY = "content.store.s3.breaker.failure.threshold";
    private static final String BREAKER_RESET_PROPERTY = "content.store.s3.breaker.reset.millis";

    /** The resource every {@code content.store.*} property this provider reads is declared in. */
    private static final String PROPERTY_RESOURCE = ContentStoreSupport.PROPERTY_RESOURCE;

    /** Authenticate with the access key id and secret access key held in configuration. */
    private static final String CREDENTIALS_STATIC = "static";

    /** Authenticate with whatever the AWS default credential provider chain resolves. */
    private static final String CREDENTIALS_DEFAULT_CHAIN = "default-chain";

    /** Reported in place of an endpoint when none is overridden, so a log line never shows a blank value. */
    private static final String AMAZON_S3_ENDPOINT = "amazon-s3-default";

    /** Status an object store returns for a key that resolves to nothing - and for several other conditions. */
    private static final int HTTP_NOT_FOUND = 404;

    /**
     * Error codes an object store reports when a key holds no object, matched lower-cased.
     *
     * <p>{@code NoSuchKey} is S3's own code for that case, and it is the code the SDK derives for a bodyless
     * {@code HEAD} reply as well. {@code NotFound} is the equivalent some S3-compatible stores report, in the
     * two spellings the SDK surfaces. Nothing else counts: a code this set does not contain is a fault of the
     * store, the bucket or the routing, not an absent object.
     */
    private static final Set<String> OBJECT_ABSENCE_CODES = Set.of("nosuchkey", "notfound", "404 not found");

    /**
     * Reasons that mean "the object was not found", for a reply that carried no error code at all.
     *
     * <p>A bodyless reply leaves the SDK nothing to unmarshal, so the reason sent with the status is the only
     * thing the store said. A 404 whose reason names a not-found condition is ambiguous and is resolved by
     * probing the bucket; a 404 with neither a code nor such a reason said nothing about the object and is a
     * store failure. Matched against the lower-cased reported text, and never reproduced anywhere.
     */
    private static final List<String> OBJECT_ABSENCE_PHRASES = List.of("not found", "no such key", "nosuchkey");

    /** The key prefix new uploads are placed under when the property yields nothing. */
    private static final String DEFAULT_KEY_PREFIX = "content/uploads";

    private static final long DEFAULT_CONNECT_TIMEOUT_MILLIS = 5000L;
    private static final long DEFAULT_READ_TIMEOUT_MILLIS = 30000L;
    private static final long DEFAULT_CALL_TIMEOUT_MILLIS = 60000L;
    private static final long DEFAULT_ATTEMPT_TIMEOUT_MILLIS = 20000L;
    private static final long MIN_TIMEOUT_MILLIS = 100L;
    private static final long MAX_TIMEOUT_MILLIS = 600000L;

    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final int MIN_MAX_ATTEMPTS = 1;
    private static final int MAX_MAX_ATTEMPTS = 10;

    private static final int DEFAULT_BREAKER_THRESHOLD = 5;
    private static final int MIN_BREAKER_THRESHOLD = 1;
    private static final int MAX_BREAKER_THRESHOLD = 1000;

    private static final long DEFAULT_BREAKER_RESET_MILLIS = 30000L;
    private static final long MIN_BREAKER_RESET_MILLIS = 100L;
    private static final long MAX_BREAKER_RESET_MILLIS = 3600000L;

    /** Sentinel deadline meaning the breaker is closed; tested for identity before any arithmetic. */
    private static final long BREAKER_CLOSED = Long.MIN_VALUE;

    private static final String NANOS_PER_MILLI_UNIT = "millis";

    /** Stable code reported once the provider's client has been built from configuration. */
    private static final String EVENT_CONFIGURED = "CONTENT-STORE-S3-CONFIGURED";

    /** Stable code reported when consecutive failures trip the breaker. */
    private static final String EVENT_BREAKER_OPEN = "CONTENT-STORE-S3-BREAKER-OPEN";

    /** Stable code reported when the breaker's window elapses and one probe is allowed through. */
    private static final String EVENT_BREAKER_PROBE = "CONTENT-STORE-S3-BREAKER-PROBE";

    /** Stable code reported when a bare 404 had to be disambiguated by probing the bucket. */
    private static final String EVENT_AMBIGUOUS_NOT_FOUND = "CONTENT-STORE-S3-AMBIGUOUS-NOT-FOUND";

    /** Stable code reported when the configured bucket could not be reached. */
    private static final String EVENT_BUCKET_UNREACHABLE = "CONTENT-STORE-S3-BUCKET-UNREACHABLE";

    /** Stable code reported when a removal found no object to remove. */
    private static final String EVENT_DELETE_NOOP = "CONTENT-STORE-S3-DELETE-NOOP";

    /** Stable code reported when explicit transport deadlines could not be applied. */
    private static final String EVENT_TRANSPORT_DEFAULTS = "CONTENT-STORE-S3-TRANSPORT-DEFAULTS";

    /**
     * Hosts that must never be addressed as an object store, whatever the allowlist permits.
     *
     * <p>Each is a cloud instance metadata service. A request to one of them is answered with credentials for
     * the instance or task the process runs in, so an endpoint override naming one turns this provider into a
     * ready-made server-side request forgery primitive: content operations would send signed requests to the
     * metadata service, and its replies - role credentials - would flow back into the application. The AWS
     * IPv4 and IPv6 addresses, the ECS task credential address and the Google Cloud metadata name are all
     * refused, because an S3-compatible deployment may well run on a non-AWS cloud.
     */
    private static final List<String> FORBIDDEN_ENDPOINT_HOSTS = List.of(
            "169.254.169.254", "fd00:ec2::254", "169.254.170.2", "metadata.google.internal");

    private final String bucket;
    private final String region;
    private final String endpoint;
    private final String endpointAllowlist;
    private final boolean allowPlaintextEndpoint;
    private final String credentialsProvider;
    private final String accessKeyId;
    private final String secretAccessKey;
    private final boolean pathStyle;

    /** Consecutive failures observed since the last success; the breaker's input. */
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    /** Monotonic deadline the breaker stays open until, or {@link #BREAKER_CLOSED}. */
    private final AtomicLong breakerDeadlineNanos = new AtomicLong(BREAKER_CLOSED);

    /**
     * Whether the client this provider uses is its own to close.
     *
     * <p>A client this provider built belongs to it, and has to be released when the provider is replaced or
     * discarded so that its connection pool and its idle threads go with it. A client handed in through the
     * test seam belongs to the caller and is never closed by {@link #closeOwnedClient()}.
     */
    private final boolean ownsClient;

    /** Created on first use rather than at construction, and published under this instance's lock. */
    private S3Client s3Client;

    /** Set by {@link #close()}, after which no operation may build or use a client. */
    private boolean closed;

    /**
     * Creates the provider from the {@code content.store.*} properties of the {@code content} resource.
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
        // so the committed blank values arrive here as the empty string. Every configured value is normalised
        // once, here, rather than at each point of use: that makes this provider's view of its configuration
        // independent of how the lookup happens to treat surrounding whitespace, and it means a value holding
        // nothing but whitespace is treated as absent everywhere instead of being sent to the store as a blank
        // bucket, region or endpoint.
        this.bucket = trimmedOrEmpty(UtilProperties.getPropertyValue(PROPERTY_RESOURCE, BUCKET_PROPERTY, ""));
        this.region = trimmedOrEmpty(UtilProperties.getPropertyValue(PROPERTY_RESOURCE, REGION_PROPERTY, ""));
        this.endpoint = trimmedOrEmpty(UtilProperties.getPropertyValue(PROPERTY_RESOURCE, ENDPOINT_PROPERTY, ""));
        this.endpointAllowlist = trimmedOrEmpty(UtilProperties.getPropertyValue(PROPERTY_RESOURCE,
                ENDPOINT_ALLOWLIST_PROPERTY, ""));
        this.allowPlaintextEndpoint =
                UtilProperties.getPropertyAsBoolean(PROPERTY_RESOURCE, ALLOW_PLAINTEXT_ENDPOINT_PROPERTY, false);
        this.credentialsProvider = trimmedOrEmpty(UtilProperties.getPropertyValue(PROPERTY_RESOURCE,
                CREDENTIALS_PROVIDER_PROPERTY, ""));
        // The credentials are normalised the same way, so that a whitespace-only credential counts as absent
        // rather than as one configured half of a pair. Nothing is logged about either value at any point.
        this.accessKeyId = trimmedOrEmpty(UtilProperties.getPropertyValue(PROPERTY_RESOURCE,
                ACCESS_KEY_ID_PROPERTY, ""));
        this.secretAccessKey = trimmedOrEmpty(UtilProperties.getPropertyValue(PROPERTY_RESOURCE,
                SECRET_ACCESS_KEY_PROPERTY, ""));
        // The boolean lookup trims the configured text as well, and answers with the supplied default for
        // anything that is neither true nor false, so a blank or malformed setting keeps virtual-host
        // addressing - the form Amazon S3 itself expects.
        this.pathStyle = UtilProperties.getPropertyAsBoolean(PROPERTY_RESOURCE, PATH_STYLE_PROPERTY, false);
        this.ownsClient = true;
    }

    /**
     * Package-private test seam: builds a provider around an already-constructed client and bucket.
     *
     * <p>It exists so that this provider's own logic - key and content validation, the mapping of an absent
     * object onto {@link FileNotFoundException}, the classification of an ambiguous 404, the breaker and the
     * idempotence of {@link #delete(String)} - can be unit-tested against a mocked {@link S3Client} from the
     * sibling {@code src/test/java} tree, which shares this package. Without the seam a test would have to
     * supply real AWS configuration, resolve real credentials and reach a real endpoint, none of which
     * belongs in a unit test. Production code must use the public configuration-driven constructor instead;
     * the remaining configuration is deliberately left blank here because the supplied client already
     * embodies it.
     *
     * @param s3Client the client every storage operation is issued through; must not be null
     * @param bucket the bucket every storage operation addresses; must be neither null nor empty
     */
    S3ContentStore(S3Client s3Client, String bucket) {
        this.bucket = trimmedOrEmpty(bucket);
        this.region = "";
        this.endpoint = "";
        this.endpointAllowlist = "";
        this.allowPlaintextEndpoint = false;
        this.credentialsProvider = "";
        this.accessKeyId = "";
        this.secretAccessKey = "";
        this.pathStyle = false;
        // The client came from the caller, so it is the caller's to close: closeOwnedClient() leaves it alone.
        this.ownsClient = false;
        this.s3Client = s3Client;
    }

    /**
     * One storage operation, expressed so that the breaker, the client lookup and the failure accounting are
     * written once rather than repeated in every operation.
     *
     * @param <T> what the operation produces
     */
    @FunctionalInterface
    private interface S3Operation<T> {
        /**
         * Performs the operation against the supplied client.
         *
         * @param client the client to issue the request through
         * @return the operation's result
         * @throws GeneralException if the request cannot be formed or the provider is not usable
         * @throws IOException if the object store cannot serve the request
         */
        T perform(S3Client client) throws GeneralException, IOException;
    }

    /**
     * Stores the supplied content as the object under the supplied key in the configured bucket.
     *
     * <p>A second write to the same key replaces the object in full rather than appending, because that is
     * S3's own {@code PutObject} semantics, and no intermediate structure has to be prepared because object
     * keys are flat. A zero-length array is stored as an existing but empty object.
     *
     * <p>This is the bounded convenience form: content longer than {@code content.store.max.memory.bytes} is
     * refused, because the caller has already materialised it in the heap by the time it arrives here. The
     * array is streamed rather than copied into the request body, so the content exists once, not twice.
     *
     * @param key the object key to write, relative to the configured bucket; must be neither null nor empty
     * @param data the complete content to store; must not be null, and may be a zero-length array
     * @throws GeneralException if the key is null or empty, the content is null or longer than the in-memory
     *     ceiling, or the provider configuration is incomplete
     * @throws IOException if the object store cannot be written to
     */
    @Override
    public void put(String key, byte[] data) throws GeneralException, IOException {
        requireUsableKey(key, "put");
        ContentStoreSupport.requireStorableContent(key, data, ContentStoreSupport.maxInMemoryBytes());
        // fromInputStream over the caller's array rather than fromBytes, which would clone the whole content
        // into the request body and hold two copies of it for the duration of the call
        put(key, new ByteArrayInputStream(data), data.length);
    }

    /**
     * Stores exactly {@code length} bytes read from the supplied stream as the object under the supplied key
     * in the configured bucket.
     *
     * <p>The length is declared to the store up front, which is what lets the content be transferred in a
     * single request without being buffered anywhere - neither in this provider's heap nor in the SDK's. This
     * is the form every integrated write path uses, so content whose size an uploader chooses never becomes
     * an allocation of that size.
     *
     * <p><strong>Exactly the declared length is transferred, and a short stream is refused.</strong> The
     * caller's stream is handed to the SDK through a length-aware wrapper rather than directly, because
     * declaring a length to the SDK only sets a header: it does not stop the request body being read past
     * that length, and it does not turn a stream that ends early into a failure. The wrapper does both, so
     * a caller streaming content it still owns keeps the remainder of its stream, and a truncated upload
     * fails loudly rather than storing a silently short object.
     *
     * <p>The stream is neither closed nor rewound here. For a retry to be possible the SDK has to be able to
     * replay the body, so a stream that does not support {@code mark}/{@code reset} makes the operation
     * effectively single-attempt; that is a property of the caller's stream rather than of this provider.
     *
     * @param key the object key to write, relative to the configured bucket; must be neither null nor empty
     * @param content the stream to transfer content from; must not be null
     * @param length the exact number of bytes to read from the stream and store; must not be negative
     * @throws GeneralException if the key is null or empty, the stream is null, the length is negative, or
     *     the provider configuration is incomplete
     * @throws IOException if the object store cannot be written to
     */
    @Override
    public void put(String key, InputStream content, long length) throws GeneralException, IOException {
        requireUsableKey(key, "put");
        ContentStoreSupport.requireStorableStream(key, content, length);
        guarded("put", key, client -> {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentLength(length)
                    .build();
            try {
                client.putObject(request,
                        RequestBody.fromInputStream(ContentStoreSupport.exactLengthStream(content, length, key), length));
            } catch (SdkException e) {
                throw storeFailure("store", key, e);
            }
            return Boolean.TRUE;
        });
    }

    /**
     * Reads the whole object stored under the supplied key into memory.
     *
     * <p>Built on the same {@code GetObject} call as {@link #openStream(String)}, so the two cannot drift
     * apart over how an absent object is reported. The stream is opened and closed here.
     *
     * <p><strong>Bounded twice over.</strong> This method materialises the whole object in the heap, so it is
     * subject to two ceilings: {@code content.store.max.memory.bytes}, the shared in-memory ceiling every
     * provider applies, and {@code content.store.max.get.bytes}, the whole-object read ceiling - a single
     * oversized object would otherwise be enough to exhaust the heap of whichever instance received the
     * request. The object's length is established with a {@code HeadObject} call first, so an object above
     * either ceiling is refused <em>before its content is transferred at all</em>; the read ceiling is then
     * enforced again while reading, which is what makes it a real bound rather than a courtesy - a store that
     * under-reports a length, or whose object was replaced between the two calls, is refused just the same.
     * Use {@link #openStream(String)} for content that is legitimately large: it streams, and is subject to
     * neither ceiling.
     *
     * @param key the object key to read, relative to the configured bucket; must be neither null nor empty
     * @return the complete stored content, never null; a zero-length array means the object is empty
     * @throws GeneralException if the key is null or empty, the provider configuration is incomplete, or the
     *     object is longer than the in-memory ceiling
     * @throws IOException if the object cannot be read, if it holds more than the configured maximum, or -
     *     as a {@link FileNotFoundException} - if the key resolves to no object, so that this method never
     *     returns null
     */
    @Override
    public byte[] get(String key) throws GeneralException, IOException {
        // Validated here as well as in the operations this is built on, so that a rejection names the
        // operation the caller actually invoked rather than the one it delegates to.
        requireUsableKey(key, "get");
        long memoryCeiling = ContentStoreSupport.maxInMemoryBytes();
        long readCeiling = ContentStoreUtil.maxWholeReadBytes();
        long reported = size(key);
        ContentStoreSupport.requireReadableInMemory(key, reported, memoryCeiling);
        if (reported > readCeiling) {
            throw new IOException("Content store provider [s3] refused to read "
                    + ContentStoreUtil.reference(key) + " in full: the object store reports " + reported
                    + " bytes, more than the " + readCeiling + " bytes allowed by [content.store.max.get.bytes] of"
                    + " resource [" + PROPERTY_RESOURCE + "]. Read it through openStream instead, which"
                    + " streams without materialising it");
        }
        try (InputStream content = openStream(key)) {
            return ContentStoreUtil.readWithin(content, readCeiling, "s3", ContentStoreUtil.reference(key));
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
        return guarded("openStream", key, client -> {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();
            try {
                // the SDK stream owns the connection to the object store, and that ownership is exactly what
                // this method passes on to its caller
                return client.getObject(request);
            } catch (S3Exception e) {
                if (isAbsentObject(client, key, e)) {
                    throw absentObject(key, e);
                }
                throw storeFailure("read", key, e);
            } catch (SdkException e) {
                throw storeFailure("read", key, e);
            }
        });
    }

    /**
     * Reports the exact length in bytes of the object stored under the supplied key.
     *
     * @param key the object key to measure, relative to the configured bucket; must be neither null nor empty
     * @return the length of the stored object in bytes, never negative
     * @throws GeneralException if the key is null or empty, or the provider configuration is incomplete
     * @throws IOException if the length cannot be established; in particular a {@link FileNotFoundException}
     *     when the key resolves to no object
     */
    @Override
    public long size(String key) throws GeneralException, IOException {
        requireUsableKey(key, "size");
        return guarded("size", key, client -> {
            HeadObjectResponse response = head(client, key, "measure");
            // A conforming store always answers HEAD with metadata. A client that answers without any
            // is read as "length unknown" rather than dereferenced, so the read-time bound - which is
            // the real one - is what applies instead of the read failing outright.
            Long length = response == null ? null : response.contentLength();
            return length == null ? 0L : length;
        });
    }

    /**
     * Reports whether the supplied key currently resolves to an object in the configured bucket.
     *
     * <p>This is the non-exceptional probe, and the distinction it draws matters: {@code false} means the key
     * genuinely resolves to no object, while a store that cannot be reached, a bucket that is gone and a
     * request that is refused all raise an exception instead of being reported as absence.
     *
     * @param key the object key to probe, relative to the configured bucket; must be neither null nor empty
     * @return {@code true} if and only if the key resolves to a stored object
     * @throws GeneralException if the key is null or empty, or the provider configuration is incomplete
     * @throws IOException if the object store cannot be interrogated
     */
    @Override
    public boolean exists(String key) throws GeneralException, IOException {
        requireUsableKey(key, "exists");
        return guarded("exists", key, client -> {
            HeadObjectRequest request = HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();
            try {
                client.headObject(request);
                return Boolean.TRUE;
            } catch (S3Exception e) {
                if (isAbsentObject(client, key, e)) {
                    return Boolean.FALSE;
                }
                throw storeFailure("probe", key, e);
            } catch (SdkException e) {
                throw storeFailure("probe", key, e);
            }
        });
    }

    /**
     * Removes the object stored under the supplied key.
     *
     * <p>The operation is idempotent, so replayed or repeated clean-up is safe: S3 already treats the
     * deletion of an absent key as a success, and a store that instead reports the absence explicitly is
     * normalised here to that same successful no-op. A 404 that cannot be attributed to the key is
     * <em>not</em> normalised, because reporting a deleted bucket or a mis-routed endpoint as a successful
     * removal would tell a caller that content it still holds a reference to is gone.
     *
     * @param key the object key to remove, relative to the configured bucket; must be neither null nor empty
     * @throws GeneralException if the key is null or empty, or the provider configuration is incomplete
     * @throws IOException if the object store cannot be modified
     */
    @Override
    public void delete(String key) throws GeneralException, IOException {
        requireUsableKey(key, "delete");
        guarded("delete", key, client -> {
            DeleteObjectRequest request = DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();
            try {
                client.deleteObject(request);
            } catch (S3Exception e) {
                if (!isAbsentObject(client, key, e)) {
                    throw storeFailure("delete", key, e);
                }
                Debug.logVerbose(EVENT_DELETE_NOOP + " key [" + ContentStoreSupport.reference(key)
                        + "]: no object was stored, so the removal is a no-op", MODULE);
            } catch (SdkException e) {
                throw storeFailure("delete", key, e);
            }
            return Boolean.TRUE;
        });
    }

    /**
     * Closes the client and the connection pool it holds, and refuses every later operation.
     *
     * <p>Idempotent, as the SPI requires: the client reference is taken and cleared under the instance lock,
     * so only the first call has anything to close and a second call is a successful no-op. The close itself
     * happens outside the lock, because closing a pool can block and no other thread should be made to wait
     * for it.
     */
    @Override
    public void close() {
        S3Client closing;
        synchronized (this) {
            closing = s3Client;
            s3Client = null;
            closed = true;
        }
        if (closing != null) {
            closing.close();
        }
    }

    /**
     * Returns the key prefix new uploads are placed under.
     *
     * <p>Both requested forms yield the same value, and deliberately so: an object key has no notion of being
     * absolute or relative, so the distinction that {@code DataResource.objectInfo} persists for filesystem
     * content has no counterpart here. The prefix carries no leading separator, because an S3 key beginning
     * with {@code /} names an object whose first path segment is empty.
     *
     * @param delegator the delegator, unused: the prefix is deployment configuration rather than a system
     *     property an administrator overrides per tenant
     * @param absolute unused, for the reason given above
     * @return the key prefix new uploads are placed under, never null and never empty
     */
    @Override
    public String uploadPath(Delegator delegator, boolean absolute) {
        String configured = UtilProperties.getPropertyValue(PROPERTY_RESOURCE, KEY_PREFIX_PROPERTY,
                DEFAULT_KEY_PREFIX);
        String prefix = UtilValidate.isEmpty(configured) ? DEFAULT_KEY_PREFIX : configured.trim();
        while (prefix.startsWith("/")) {
            prefix = prefix.substring(1);
        }
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix.isEmpty() ? DEFAULT_KEY_PREFIX : prefix;
    }

    /**
     * Runs one storage operation with the breaker, the client lookup and the failure accounting applied.
     *
     * <p>An absent object is deliberately recorded as a <em>success</em>: the store answered, and answered
     * correctly, so it must not push the provider towards tripping the breaker.
     *
     * @param <T> what the operation produces
     * @param operation the operation name, used in the refusal message and the breaker log line
     * @param key the storage key the operation addresses
     * @param action the operation to run
     * @return whatever the operation produced
     * @throws GeneralException if the breaker is open, the provider is not usable, or the operation rejects
     *     its input
     * @throws IOException if the object store cannot serve the operation
     */
    private <T> T guarded(String operation, String key, S3Operation<T> action) throws GeneralException, IOException {
        requireBreakerClosed(operation, key);
        try {
            T result = action.perform(requireClient());
            recordSuccess();
            return result;
        } catch (FileNotFoundException e) {
            // the store answered, and its answer was "nothing is stored here"; that is not a failure
            recordSuccess();
            throw e;
        } catch (GeneralException | IOException | RuntimeException e) {
            recordFailure(operation);
            throw e;
        }
    }

    /**
     * Refuses an operation while the breaker is open, and lets exactly one through once its window elapses.
     *
     * @param operation the operation name, used in the refusal message
     * @param key the storage key the operation addresses, referred to by digest in the message
     * @throws GeneralException if the breaker is open
     */
    private void requireBreakerClosed(String operation, String key) throws GeneralException {
        long deadline = breakerDeadlineNanos.get();
        if (deadline == BREAKER_CLOSED) {
            return;
        }
        // the subtraction is only ever reached for a real deadline, so the sentinel can never overflow it;
        // nanoTime differences are compared rather than the values themselves, as nanoTime requires
        if (System.nanoTime() - deadline < 0) {
            throw new GeneralException("Content store provider [s3] refused the [" + operation + "] operation for key ["
                    + ContentStoreSupport.reference(key) + "]: the object store breaker is open after "
                    + breakerThreshold() + " consecutive failures");
        }
        if (breakerDeadlineNanos.compareAndSet(deadline, BREAKER_CLOSED)) {
            // half-open: one operation is allowed through, and the failure count is left one short of the
            // threshold so that a single further failure re-opens the breaker immediately
            consecutiveFailures.set(Math.max(0, breakerThreshold() - 1));
            Debug.logWarning(EVENT_BREAKER_PROBE + " operation [" + operation + "]: the breaker window elapsed,"
                    + " allowing one operation through", MODULE);
        }
    }

    /** Records that the object store answered, closing the breaker and clearing the failure count. */
    private void recordSuccess() {
        if (consecutiveFailures.get() != 0) {
            consecutiveFailures.set(0);
        }
        if (breakerDeadlineNanos.get() != BREAKER_CLOSED) {
            breakerDeadlineNanos.set(BREAKER_CLOSED);
        }
    }

    /**
     * Records a failure and opens the breaker once enough of them have happened in a row.
     *
     * @param operation the operation that failed, named in the log line
     */
    private void recordFailure(String operation) {
        int threshold = breakerThreshold();
        if (consecutiveFailures.incrementAndGet() < threshold) {
            return;
        }
        long window = ContentStoreSupport.boundedLongProperty(BREAKER_RESET_PROPERTY, DEFAULT_BREAKER_RESET_MILLIS,
                MIN_BREAKER_RESET_MILLIS, MAX_BREAKER_RESET_MILLIS);
        long deadline = System.nanoTime() + Duration.ofMillis(window).toNanos();
        // MIN_VALUE is the closed sentinel and can never be produced by nanoTime plus a positive window, so
        // the deadline is always distinguishable from "closed"
        if (breakerDeadlineNanos.getAndSet(deadline) == BREAKER_CLOSED) {
            // logged only on the transition, so a sustained outage costs one line per window rather than one
            // line per refused request
            Debug.logWarning(EVENT_BREAKER_OPEN + " operation [" + operation + "]: " + threshold
                    + " consecutive failures; refusing operations for " + window + " " + NANOS_PER_MILLI_UNIT, MODULE);
        }
    }

    /**
     * The number of consecutive failures that opens the breaker.
     *
     * @return the configured threshold, or the default when none is usable
     */
    private static int breakerThreshold() {
        return ContentStoreSupport.boundedIntProperty(BREAKER_THRESHOLD_PROPERTY, DEFAULT_BREAKER_THRESHOLD,
                MIN_BREAKER_THRESHOLD, MAX_BREAKER_THRESHOLD);
    }

    /**
     * Issues a {@code HeadObject} call, mapping an absent object onto the SPI's absence signal.
     *
     * @param client the client to issue the request through
     * @param key the object key to interrogate
     * @param action the action being performed, named in a failure message
     * @return the object's metadata
     * @throws GeneralException if the bucket cannot be reached
     * @throws IOException if the object cannot be interrogated, in particular a
     *     {@link FileNotFoundException} when the key resolves to no object
     */
    private HeadObjectResponse head(S3Client client, String key, String action) throws GeneralException, IOException {
        HeadObjectRequest request = HeadObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        try {
            return client.headObject(request);
        } catch (S3Exception e) {
            if (isAbsentObject(client, key, e)) {
                throw absentObject(key, e);
            }
            throw storeFailure(action, key, e);
        } catch (SdkException e) {
            throw storeFailure(action, key, e);
        }
    }

    /**
     * Refuses a storage key this provider must not send to the object store.
     *
     * <p>Null, empty and blank keys are refused by the shared rule every provider applies, so that one key is
     * unusable everywhere rather than in some providers only. On top of that a key carrying a control
     * character is refused here. Such a key is legal for S3 but is echoed back by the store's own access log
     * and by every error reply, so a key containing a line feed would forge a record in logs this process
     * cannot sanitise - and the same key reaches OFBiz's own diagnostics on the way. Refusing it at the
     * boundary is the only place the problem can be solved once.
     *
     * @param key the key supplied by the caller
     * @param operation the SPI operation being attempted, named so the message identifies the caller
     * @throws GeneralException if the key is null, empty, blank or carries a control character
     */
    private static void requireUsableKey(String key, String operation) throws GeneralException {
        ContentStoreSupport.requireUsableKey(key);
        if (ContentStoreUtil.hasUnsafeCharacter(key)) {
            throw new GeneralException("Content store provider [s3] rejected the [" + operation + "] operation for "
                    + ContentStoreUtil.reference(key) + ": the storage key contains a control character, which"
                    + " would be reproduced in the object store's own logs and in its error replies");
        }
    }

    /**
     * Reports whether a failure from the object store unambiguously means the key resolves to no object.
     *
     * <p>The HTTP status is never consulted on its own. A bucket that does not exist, an endpoint that points
     * at the wrong store and a routing style the store rejects are all answered with the very same {@code 404}
     * as an absent object, so reading a status alone would report a deployment fault as "content not found"
     * and hide it behind an empty read. Four cases are separated instead, because collapsing them is what lets
     * a deleted bucket read as missing content:
     * <ul>
     *   <li>{@link NoSuchKeyException} - the store named the key as missing. This is the unambiguous absence
     *       signal, and it is what both Amazon S3 and the compatible stores return for {@code GetObject} and
     *       {@code HeadObject}. The type test comes first because it also holds for a
     *       {@link NoSuchKeyException} built in a unit test, where no status and no error details are set.</li>
     *   <li>{@link NoSuchBucketException} - the container itself is gone. Never absence; propagated as a
     *       provider failure so that an operator sees a configuration or lifecycle problem rather than an
     *       empty store.</li>
     *   <li>An error code the store itself reported - one of {@link #OBJECT_ABSENCE_CODES} is absence, and
     *       anything else is not. A {@code NoSuchBucket}, {@code PermanentRedirect} or
     *       {@code InvalidBucketName} reply therefore never satisfies this test whatever status accompanies
     *       it, and it costs no extra round trip to establish that: the store has already said the failure is
     *       not about the key.</li>
     *   <li>A bare 404 the SDK could not narrow and that carries no code - possible when a compatible store
     *       answers a {@code HEAD} or {@code DELETE} with no body. Only if the reason the store did send names
     *       a not-found condition is the 404 a candidate for absence at all; the bucket is then probed to
     *       decide. If the bucket answers, the 404 was about the key; if it does not, the 404 is propagated as
     *       a failure. The probe needs permission to interrogate the bucket, and a deployment that withholds
     *       it gets the fail-closed outcome, which is the correct direction to be wrong in. A 404 carrying
     *       neither a code nor a not-found reason is reported as a store failure without a probe, because
     *       nothing in it says anything about the object.</li>
     * </ul>
     *
     * <p>One residual ambiguity belongs to the protocol rather than to this classification: a {@code HEAD}
     * reply carries no error document, so a store answering a probe for a key in a bucket that does not exist
     * may be surfaced by the SDK as {@code NoSuchKey}, and {@link #exists(String)} then answers {@code false}.
     * Nothing is masked by that: the same bucket fails loudly on every read and every write, whose replies do
     * carry the store's own error code.
     *
     * @param client the client the failure came from, reused for the disambiguating probe
     * @param key the object key the failed request addressed
     * @param cause the failure reported by the object store
     * @return {@code true} if and only if the failure means the key resolves to no object
     * @throws IOException if the failure is attributable to the bucket rather than to the key
     */
    private boolean isAbsentObject(S3Client client, String key, S3Exception cause) throws IOException {
        if (cause instanceof NoSuchKeyException) {
            return true;
        }
        if (cause instanceof NoSuchBucketException) {
            throw bucketFailure(cause);
        }
        String reported = errorCodeOf(cause);
        if (OBJECT_ABSENCE_CODES.contains(reported)) {
            return true;
        }
        if (!reported.isEmpty()) {
            // The store named something, and it was not an absent object: NoSuchBucket, PermanentRedirect and
            // InvalidBucketName all arrive with the very same 404 as an absent key. There is nothing to
            // disambiguate and no probe to pay for, because the store has already said this is not the key.
            return false;
        }
        if (cause.statusCode() != HTTP_NOT_FOUND || !reportsObjectNotFound(cause)) {
            // Either the status was never 404, or it was a 404 that arrived with nothing meaning "not found".
            // A reply that says nothing at all about the object is not attributable to the key, so it is a
            // store failure: answering it as absence is exactly how a wrong bucket or endpoint stays hidden.
            return false;
        }
        Debug.logWarning(EVENT_AMBIGUOUS_NOT_FOUND + " key [" + ContentStoreSupport.reference(key)
                + "]: the store answered 404 without naming the key; probing the bucket to classify it", MODULE);
        if (bucketReachable(client)) {
            return true;
        }
        throw bucketFailure(cause);
    }

    /**
     * Returns the error code the object store itself reported, lower-cased for matching.
     *
     * <p>Every step is guarded because an SDK exception raised before, or instead of, a parsed error reply -
     * including one built in a unit test - carries no error details at all.
     *
     * @param cause the failure reported by the object store
     * @return the reported error code, lower-cased, or an empty string when the store named none
     */
    private static String errorCodeOf(S3Exception cause) {
        AwsErrorDetails details = cause.awsErrorDetails();
        String errorCode = details == null ? null : details.errorCode();
        return errorCode == null ? "" : errorCode.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Reports whether a reply that carried no error code nevertheless said the object was not found.
     *
     * <p>A bodyless {@code HEAD} or {@code DELETE} reply gives the SDK no error document to unmarshal, so the
     * only surviving trace of what the store said is the reason it sent with the status. A reason naming a
     * not-found condition makes the 404 a <em>candidate</em> for absence, to be settled by probing the bucket.
     * A 404 arriving with nothing of the sort - no code and no reason - said nothing about the object at all,
     * and is reported as a store failure rather than resolved, because a wrong bucket, a mis-routed endpoint
     * and a rejected addressing style all answer exactly that way. The reported text is only ever matched
     * against the fixed set below and is never composed into anything this class throws or logs.
     *
     * @param cause the failure reported by the object store
     * @return {@code true} if the reported reason names a not-found condition
     */
    private static boolean reportsObjectNotFound(S3Exception cause) {
        String reported = cause.getMessage() == null ? "" : cause.getMessage().toLowerCase(Locale.ROOT);
        for (String phrase : OBJECT_ABSENCE_PHRASES) {
            if (reported.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Probes the configured bucket so that an unattributable 404 can be classified.
     *
     * @param client the client to issue the probe through
     * @return {@code true} if the bucket answered
     */
    private boolean bucketReachable(S3Client client) {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return true;
        } catch (SdkException e) {
            // the probe's own failure is reported by type only: an object-store error reply can echo request
            // and credential material, and none of it may be composed into a message this class builds
            Debug.logWarning(EVENT_BUCKET_UNREACHABLE + ": the configured bucket did not answer ["
                    + e.getClass().getSimpleName() + "]", MODULE);
            return false;
        }
    }

    /**
     * Returns the client every storage operation is issued through, creating it on first use.
     *
     * @return the client for this provider, never null
     * @throws GeneralException if the provider has been closed, the bucket is not configured, or the client
     *     cannot be built
     */
    private synchronized S3Client requireClient() throws GeneralException {
        if (closed) {
            throw new GeneralException("Content store provider [s3] is not usable: it has been closed");
        }
        if (UtilValidate.isEmpty(bucket)) {
            throw new GeneralException("Content store provider [s3] is not usable: property [" + BUCKET_PROPERTY
                    + "] of resource [" + PROPERTY_RESOURCE + "] is not configured");
        }

        if (s3Client == null) {
            s3Client = buildClient();
        }
        return s3Client;
    }

    /**
     * Releases the client this provider built, so a replaced or discarded provider leaves nothing running.
     *
     * <p>An SDK client owns an HTTP connection pool and its idle threads, so a provider that is swapped out
     * when the configuration changes has to hand those back; otherwise every reconfiguration leaks a pool for
     * the lifetime of the JVM. The hook is package-private on purpose: {@link ContentStore} deliberately has
     * no close operation, so this is the factory's lifecycle concern and never the caller's.
     *
     * <p>It is safe to call at any time and any number of times. The reference is cleared under the same lock
     * {@link #requireClient()} publishes it under, so a storage operation either used the client before it was
     * closed or builds a fresh one afterwards - it can never be handed a closed client. A client supplied
     * through the test seam is left untouched, because it belongs to whoever supplied it. This is deliberately
     * narrower than {@link #close()}, which retires the provider itself: the hook releases only what this
     * provider owns and leaves it able to build again.
     */
    synchronized void closeOwnedClient() {
        if (s3Client == null || !ownsClient) {
            return;
        }
        S3Client closing = s3Client;
        s3Client = null;
        try {
            closing.close();
        } catch (SdkException e) {
            // Reported rather than propagated: the caller is discarding this provider, and a client that
            // cannot be shut down cleanly must not stop its replacement from being published. Reported by
            // type and opaque reference only, for the reason given on bucketReachable.
            Debug.logWarning("Content store provider [s3] could not close the client for bucket "
                    + ContentStoreUtil.reference(bucket) + " [" + e.getClass().getSimpleName() + "]", MODULE);
        }
    }

    /**
     * Builds the client from the configured region, endpoint, addressing style, credentials and deadlines.
     *
     * <p>Every policy decision is taken here, before a client exists, so a configuration that would send
     * content to an unintended host or authenticate as an unintended identity produces no client at all
     * rather than a working one. Every configured value is validated before the SDK is asked for anything, so
     * that a deployment mistake is reported as a configuration fault naming the property at issue rather than
     * as an opaque SDK or authorisation error much later, on the first storage operation that happens to run.
     *
     * @return a newly built client, carrying this provider's deadlines and attempt ceiling
     * @throws GeneralException if the region is not configured, a configured value is malformed or unsafe, the
     *     credential mode or the endpoint is not permitted, or the SDK rejects the resulting configuration
     */
    private S3Client buildClient() throws GeneralException {
        if (UtilValidate.isEmpty(region)) {
            throw new GeneralException("Content store provider [s3] is not usable: property [" + REGION_PROPERTY
                    + "] of resource [" + PROPERTY_RESOURCE + "] is not configured");
        }
        boolean staticCredentials = requireCredentialMode();
        // A blank endpoint keeps Amazon S3's own endpoint; any other value targets a compatible store, and it
        // is validated HERE - before the SDK is asked for anything and before any log line is built from it -
        // so neither a malformed value nor one carrying a credential can reach either.
        URI endpointOverride = requirePermittedEndpoint();
        try {
            ClientOverrideConfiguration overrides = boundedCallConfiguration();
            S3ClientBuilder builder = S3Client.builder()
                    .region(Region.of(region))
                    .forcePathStyle(pathStyle)
                    .overrideConfiguration(overrides);
            SdkHttpClient.Builder<?> transport = boundedTransport();
            if (transport != null) {
                // supplied as a BUILDER rather than a built client, so the SDK owns the HTTP client's
                // lifecycle and closes it together with the S3 client
                builder = builder.httpClientBuilder(transport);
            }
            if (endpointOverride != null) {
                builder = builder.endpointOverride(endpointOverride);
            }
            if (staticCredentials) {
                builder = builder.credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
            } else {
                // The deployment asked for the ambient chain explicitly, so defer to the instance role,
                // container credentials, shared profile or process environment that it resolves. A
                // half-configured pair was already refused, so nothing is being substituted silently here.
                // The chain is requested through its builder because the shorter create() is deprecated in
                // this SDK line, and this file has to stay free of deprecation warnings under -Xlint:all.
                builder = builder.credentialsProvider(DefaultCredentialsProvider.builder().build());
            }
            S3Client client = builder.build();
            // Addressing configuration only, and none of it in a form that can carry a secret. The bucket is
            // an opaque reference, because a bucket name identifies the deployment's storage. The endpoint is
            // reduced to the scheme, host and port that identify the store: it has been validated by this
            // point, so it provably carries no user information, no path, no query and no fragment, and a
            // REFUSED endpoint is reported the other way round - as an opaque reference - precisely because
            // that value has not been validated. The credential source is a closed-enum name rather than any
            // credential value, so no access key id and no secret access key can reach the log. A built client
            // has proved nothing about the store itself, so this reports the configuration it was built from -
            // including the deadlines and the attempt ceiling, which are the numbers an unexplained stall is
            // measured against - rather than any claim about reachability.
            Debug.logInfo(EVENT_CONFIGURED + " Content store provider [s3] ready: bucket "
                    + ContentStoreUtil.reference(bucket) + ", region [" + ContentStoreUtil.describe(region)
                    + "], endpoint [" + endpointMarker(endpointOverride) + "], path-style addressing ["
                    + pathStyle + "], credentials ["
                    + (staticCredentials ? CREDENTIALS_STATIC : CREDENTIALS_DEFAULT_CHAIN) + "], call-timeout ["
                    + millisOf(overrides.apiCallTimeout()) + " " + NANOS_PER_MILLI_UNIT + "], attempt-timeout ["
                    + millisOf(overrides.apiCallAttemptTimeout()) + " " + NANOS_PER_MILLI_UNIT
                    + "], max-attempts [" + boundedMaxAttempts() + "]", MODULE);
            return client;
        } catch (IllegalArgumentException e) {
            throw new GeneralException("Content store provider [s3] could not be configured for bucket "
                    + ContentStoreUtil.reference(bucket) + ": a configured region, endpoint or deadline value is"
                    + " not valid", e);
        } catch (SdkException e) {
            // The SDK failure is not chained: see storeFailure for why an object-store diagnostic is never
            // attached to anything this class throws.
            throw new GeneralException("Content store provider [s3] could not be initialised for bucket "
                    + ContentStoreUtil.reference(bucket) + " [" + e.getClass().getSimpleName() + "]");
        }
    }

    /**
     * Establishes which identity the provider authenticates as, refusing every configuration that leaves it
     * ambiguous.
     *
     * <p>The mode is read from configuration and never inferred, and the two credential properties have to
     * agree with it. That is the whole point: the identity a deployment authenticates as decides what it is
     * allowed to read and write, so it must be stated rather than deduced from whether a value happens to be
     * present. Each refused combination below is a real accident with a silent outcome - a mistyped property
     * name, an injection that failed, a credential left behind after switching to an instance role - and every
     * one of them used to end in the ambient chain being used instead, with no error at all.
     *
     * @return {@code true} for static credentials, {@code false} for the AWS default credential chain
     * @throws GeneralException as a {@link ContentStoreConfigurationException} if the mode is absent or
     *     unrecognised, or if the credential properties do not match it
     */
    private boolean requireCredentialMode() throws GeneralException {
        boolean hasAccessKeyId = UtilValidate.isNotEmpty(accessKeyId);
        boolean hasSecretAccessKey = UtilValidate.isNotEmpty(secretAccessKey);
        String mode = credentialsProvider == null ? "" : credentialsProvider.trim().toLowerCase(Locale.ROOT);

        if (CREDENTIALS_STATIC.equals(mode)) {
            if (!hasAccessKeyId && !hasSecretAccessKey) {
                throw new ContentStoreConfigurationException("Content store provider [s3] is configured with ["
                        + CREDENTIALS_PROVIDER_PROPERTY + "=" + CREDENTIALS_STATIC + "] but both ["
                        + ACCESS_KEY_ID_PROPERTY + "] and [" + SECRET_ACCESS_KEY_PROPERTY + "] of resource ["
                        + PROPERTY_RESOURCE + "] are blank. Supply both credential properties, or select ["
                        + CREDENTIALS_PROVIDER_PROPERTY + "=" + CREDENTIALS_DEFAULT_CHAIN + "] to authenticate"
                        + " with the ambient credential chain instead");
            }
            // Refuses a one-sided pair, which is the accident that used to authenticate as the ambient identity
            requireCredentialPair(accessKeyId, secretAccessKey);
            return true;
        }
        if (CREDENTIALS_DEFAULT_CHAIN.equals(mode)) {
            if (hasAccessKeyId || hasSecretAccessKey) {
                throw new ContentStoreConfigurationException("Content store provider [s3] is configured with ["
                        + CREDENTIALS_PROVIDER_PROPERTY + "=" + CREDENTIALS_DEFAULT_CHAIN + "] but a credential is"
                        + " also configured in resource [" + PROPERTY_RESOURCE + "]. The credential would be ignored"
                        + " and the deployment would authenticate as the ambient identity instead, so the"
                        + " contradiction is refused: either clear [" + ACCESS_KEY_ID_PROPERTY + "] and ["
                        + SECRET_ACCESS_KEY_PROPERTY + "], or select [" + CREDENTIALS_PROVIDER_PROPERTY + "="
                        + CREDENTIALS_STATIC + "]");
            }
            return false;
        }
        throw new ContentStoreConfigurationException("Content store provider [s3] requires property ["
                + CREDENTIALS_PROVIDER_PROPERTY + "] of resource [" + PROPERTY_RESOURCE + "] to name the identity"
                + " source, but it is " + ContentStoreUtil.describe(credentialsProvider) + "; expected one of ["
                + CREDENTIALS_STATIC + ", " + CREDENTIALS_DEFAULT_CHAIN + "]. It is not inferred from whether a"
                + " credential happens to be present, because that inference silently changes which identity the"
                + " deployment authenticates as");
    }

    /**
     * Refuses a one-sided static credential pair, and reports which source the complete configuration selects.
     *
     * <p>Static and argument-driven rather than reading the fields directly, so that the credential-source
     * decision can be exercised for every combination without a client, a network or a credential being
     * involved. {@link #requireCredentialMode()} applies it once the deployment has named {@code static}, so
     * the pair rule and the mode rule cannot drift apart.
     *
     * @param configuredKeyId the configured access key id; may be null or blank
     * @param configuredSecret the configured secret access key; may be null or blank
     * @return {@code true} when both credential properties are supplied, {@code false} when both are blank
     * @throws GeneralException as a {@link ContentStoreConfigurationException} if exactly one of the two is
     *     supplied
     */
    static boolean requireCredentialPair(String configuredKeyId, String configuredSecret) throws GeneralException {
        boolean hasId = UtilValidate.isNotEmpty(configuredKeyId);
        boolean hasSecret = UtilValidate.isNotEmpty(configuredSecret);
        if (hasId != hasSecret) {
            // refused rather than silently falling back to the default chain: falling back would authenticate
            // as whatever ambient instance or container identity happens to be available, which is a different
            // principal with potentially different permissions on potentially different data. Neither value is
            // echoed - only the property names an operator has to look at.
            throw new ContentStoreConfigurationException("Content store provider [s3] is not usable: exactly one of ["
                    + ACCESS_KEY_ID_PROPERTY + "] and [" + SECRET_ACCESS_KEY_PROPERTY + "] is configured. Supply"
                    + " both to use static credentials with [" + CREDENTIALS_PROVIDER_PROPERTY + "="
                    + CREDENTIALS_STATIC + "], or neither to use [" + CREDENTIALS_PROVIDER_PROPERTY + "="
                    + CREDENTIALS_DEFAULT_CHAIN + "] and the AWS default credential provider chain");
        }
        return hasId;
    }

    /**
     * Validates the configured endpoint override and returns it, or {@code null} for Amazon S3's own endpoint.
     *
     * <p>The endpoint decides where every byte of this deployment's content is sent, so it is checked against
     * a deliberately narrow grammar, refused outright if it addresses a cloud instance metadata service, and
     * then checked against the hosts the deployment declared. A blank value is not a risk and is the
     * recommended setting for Amazon S3, so it needs no allowlist.
     *
     * @return the endpoint to override the SDK's default with, or {@code null} to leave the default in place
     * @throws GeneralException as a {@link ContentStoreConfigurationException} if the endpoint is malformed,
     *     plaintext without explicit permission, a cloud instance metadata address, or absent from the
     *     allowlist
     */
    private URI requirePermittedEndpoint() throws GeneralException {
        if (UtilValidate.isEmpty(endpoint) || endpoint.trim().isEmpty()) {
            return null;
        }
        URI permitted = validatedEndpoint(endpoint, allowPlaintextEndpoint);
        // Compared against the normalised host, so a literal IPv6 endpoint matches the same list entry
        // whichever way it was written. URI.getHost() KEEPS the brackets of an IPv6 literal - it answers
        // "[fd00:ec2::254]" for http://[fd00:ec2::254]/ - so comparing its result directly against the
        // unbracketed list entry would let the IPv6 metadata address straight through.
        if (!allowlistedHosts().contains(normaliseHost(permitted.getHost()))) {
            throw endpointRefused("its host is not listed in [" + ENDPOINT_ALLOWLIST_PROPERTY + "] of resource ["
                    + PROPERTY_RESOURCE + "]. List every host this deployment may send content to, so that a"
                    + " single mis-set property cannot redirect content traffic elsewhere");
        }
        return permitted;
    }

    /**
     * Validates a configured endpoint against the grammar and the safety rules every deployment is held to.
     *
     * <p>Static and argument-driven for the same reason as {@link #requireCredentialPair(String, String)}: the
     * whole rejection matrix is then reachable without building a client. The allowlist is deliberately
     * <em>not</em> applied here - it is deployment policy rather than grammar, and
     * {@link #requirePermittedEndpoint()} applies it to the result.
     *
     * @param configuredEndpoint the configured endpoint override; surrounding whitespace is tolerated
     * @param plaintextPermitted whether a plain {@code http} endpoint has been explicitly accepted
     * @return the endpoint to override the default with, never null
     * @throws GeneralException as a {@link ContentStoreConfigurationException} if the endpoint is not an
     *     absolute {@code http} or {@code https} URI naming a host, carries user information, a query or a
     *     fragment, addresses a cloud instance metadata service, or is plaintext without plaintext having
     *     been permitted
     */
    static URI validatedEndpoint(String configuredEndpoint, boolean plaintextPermitted) throws GeneralException {
        String supplied = configuredEndpoint == null ? "" : configuredEndpoint;
        // Checked on the SUPPLIED value, before it is trimmed and before it is parsed, because neither of those
        // two steps objects to a control character and both hide it. String.trim() removes every character at or
        // below U+0020 from each end, so a trailing line break or a trailing 0x01 is simply deleted; and
        // java.net.URI accepts one inside a path and stops the path there. Either way an endpoint of
        // "https://objects.example/<0x01>" becomes "https://objects.example/", every check below passes, and the
        // client is pointed at an address that is not the one an operator configured. A line break is worse than
        // misleading: a configured value carrying one can forge a second declaration in a rendered properties
        // file and a second line in any log that reports the endpoint. An ordinary space is deliberately NOT
        // refused here - it is not hidden by anything, the URI parser rejects it as the malformed address it is,
        // and surrounding spaces are what an operator legitimately copies in.
        for (int index = 0; index < supplied.length(); index++) {
            if (Character.isISOControl(supplied.charAt(index))) {
                throw endpointRefused(supplied, "it contains a control character at position " + index
                        + ", which no endpoint address may carry: whitespace trimming would delete it and the URI"
                        + " parser would truncate the address at it, so the store addressed would not be the"
                        + " store configured");
            }
        }
        String candidate = supplied.trim();
        URI parsed;
        try {
            parsed = new URI(candidate);
        } catch (URISyntaxException e) {
            throw endpointRefused(candidate, "it is not a valid URI");
        }
        String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
        String host = normaliseHost(parsed.getHost());
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw endpointRefused(candidate, "its scheme must be https, or http with ["
                    + ALLOW_PLAINTEXT_ENDPOINT_PROPERTY + "=true]");
        }
        if (!parsed.isAbsolute() || host.isEmpty()) {
            throw endpointRefused(candidate, "it names no host, so it is not an absolute endpoint address");
        }
        if (parsed.getUserInfo() != null || parsed.getRawUserInfo() != null) {
            throw endpointRefused(candidate, "it carries user information before the host, which would place a"
                    + " credential in configuration that every diagnostic echoing the endpoint would then"
                    + " disclose, and can disguise which host is really addressed");
        }
        if (parsed.getQuery() != null || parsed.getFragment() != null) {
            throw endpointRefused(candidate, "it carries a query or fragment, which an endpoint address must not"
                    + " have");
        }
        if (FORBIDDEN_ENDPOINT_HOSTS.contains(host)) {
            throw endpointRefused(candidate, "it addresses a cloud instance metadata service, which is refused in"
                    + " every configuration and by no allowlist: that address answers with credentials for the"
                    + " whole instance, so content operations must never be pointed at it");
        }
        if (!"https".equals(scheme) && !plaintextPermitted) {
            throw endpointRefused(candidate, "it is plaintext http, so the deployment's content and the requests"
                    + " signed for it would cross the network unencrypted. Set ["
                    + ALLOW_PLAINTEXT_ENDPOINT_PROPERTY + "=true] only for a local development store");
        }
        return parsed;
    }

    /**
     * Returns the hosts an endpoint override is permitted to name.
     *
     * <p>Comma separated, whitespace tolerated, compared in lower case. An empty result is the reason a
     * configured endpoint is refused when the allowlist has not been filled in: requiring the host to be named
     * a second time is deliberate, because it is what turns "whatever this property happens to hold" into "one
     * of the hosts the deployment declared".
     *
     * @return the permitted hosts, never null and possibly empty
     */
    private Set<String> allowlistedHosts() {
        Set<String> permitted = new LinkedHashSet<>();
        for (String candidate : endpointAllowlist.split(",")) {
            String host = normaliseHost(candidate);
            if (!host.isEmpty()) {
                permitted.add(host);
            }
        }
        return permitted;
    }

    /**
     * Reduces a host to the one form every comparison in this class uses.
     *
     * <p>Trimmed, lower-cased, and with the brackets of an IPv6 literal removed. The brackets matter:
     * {@link URI#getHost()} keeps them, answering {@code [fd00:ec2::254]} rather than
     * {@code fd00:ec2::254}, so without this an IPv6 address would compare unequal to the same address
     * written plainly in {@link #FORBIDDEN_ENDPOINT_HOSTS} or in the allowlist. Normalising both sides
     * with the same method means an operator may write either form and the refusals still apply.
     *
     * @param host the host to normalise, which may be null
     * @return the normalised host, never null and possibly empty
     */
    private static String normaliseHost(String host) {
        if (host == null) {
            return "";
        }
        String normalised = host.trim().toLowerCase(Locale.ROOT);
        if (normalised.startsWith("[") && normalised.endsWith("]") && normalised.length() > 2) {
            normalised = normalised.substring(1, normalised.length() - 1);
        }
        return normalised;
    }

    /**
     * Builds the refusal for this provider's configured endpoint, naming the reason without echoing the value.
     *
     * @param reason why the endpoint is not permitted
     * @return the exception to throw
     */
    private ContentStoreConfigurationException endpointRefused(String reason) {
        return endpointRefused(endpoint, reason);
    }

    /**
     * Builds the refusal for a configured endpoint, naming the reason without echoing the endpoint.
     *
     * <p>The endpoint is identified by opaque reference rather than quoted: it names an internal host, it may
     * carry a credential in its user-information component, and it is operator-supplied text that must not be
     * able to forge a log record. The property name is what an operator needs in order to fix it.
     *
     * @param endpointValue the configured endpoint that is not permitted
     * @param reason why the endpoint is not permitted
     * @return the exception to throw
     */
    private static ContentStoreConfigurationException endpointRefused(String endpointValue, String reason) {
        return new ContentStoreConfigurationException("Content store provider [s3] rejected property ["
                + ENDPOINT_PROPERTY + "] of resource [" + PROPERTY_RESOURCE + "], "
                + ContentStoreUtil.reference(endpointValue) + ": " + reason);
    }

    /**
     * Builds an HTTP client builder carrying this provider's explicit transport deadlines.
     *
     * <p>The deadlines have to reach the HTTP layer, and the HTTP layer is pluggable: the implementation on
     * the class path is discovered by the SDK through {@link SdkHttpService}, so naming a concrete one here
     * would both add a dependency and pin the deployment to it. Instead the very same discovery is performed,
     * and the discovered builder is wrapped so that this provider's options are merged over the SDK's service
     * defaults. If no implementation announces itself the SDK resolves one itself with its own defaults, which
     * are finite; that is reported, because deadlines silently not being applied is exactly the kind of
     * divergence that only shows up as an unexplained stall under load.
     *
     * @return a builder the SDK will build and own, or {@code null} to let the SDK resolve its own
     */
    private static SdkHttpClient.Builder<?> boundedTransport() {
        long connectTimeout = ContentStoreSupport.boundedLongProperty(CONNECT_TIMEOUT_PROPERTY,
                DEFAULT_CONNECT_TIMEOUT_MILLIS, MIN_TIMEOUT_MILLIS, MAX_TIMEOUT_MILLIS);
        long readTimeout = ContentStoreSupport.boundedLongProperty(READ_TIMEOUT_PROPERTY, DEFAULT_READ_TIMEOUT_MILLIS,
                MIN_TIMEOUT_MILLIS, MAX_TIMEOUT_MILLIS);
        AttributeMap options = AttributeMap.builder()
                .put(SdkHttpConfigurationOption.CONNECTION_TIMEOUT, Duration.ofMillis(connectTimeout))
                .put(SdkHttpConfigurationOption.CONNECTION_ACQUIRE_TIMEOUT, Duration.ofMillis(connectTimeout))
                .put(SdkHttpConfigurationOption.TLS_NEGOTIATION_TIMEOUT, Duration.ofMillis(connectTimeout))
                .put(SdkHttpConfigurationOption.READ_TIMEOUT, Duration.ofMillis(readTimeout))
                .put(SdkHttpConfigurationOption.WRITE_TIMEOUT, Duration.ofMillis(readTimeout))
                .build();
        try {
            Iterator<SdkHttpService> discovered =
                    ServiceLoader.load(SdkHttpService.class, S3ContentStore.class.getClassLoader()).iterator();
            if (discovered.hasNext()) {
                return new BoundedTransportBuilder(discovered.next().createHttpClientBuilder(), options);
            }
        } catch (ServiceConfigurationError | RuntimeException e) {
            Debug.logWarning(EVENT_TRANSPORT_DEFAULTS + ": the HTTP implementation could not be discovered ["
                    + e.getClass().getSimpleName() + "]; the SDK's own transport defaults apply", MODULE);
            return null;
        }
        Debug.logWarning(EVENT_TRANSPORT_DEFAULTS + ": no HTTP implementation announced itself; the SDK's own"
                + " transport defaults apply", MODULE);
        return null;
    }

    /**
     * Returns the call ceiling, the attempt ceiling and the attempt count every client is built with.
     *
     * <p>Two ceilings are set, and both matter for an instance behind a load balancer: the call timeout bounds
     * a complete storage call, so an unreachable store fails a request instead of hanging it, and the attempt
     * timeout bounds one attempt within that budget, so a single stalled attempt is retried rather than
     * consuming the whole of it. The attempt ceiling is never allowed to exceed the call ceiling, because an
     * attempt permitted to outlive the call it belongs to is not a bound on anything.
     *
     * <p>All three numbers are read from configuration through the same bounded readers the rest of this
     * provider uses, so a deployment can tune them and a malformed value falls back to the committed default
     * rather than removing the bound. Package-private so that the store package's own tests can assert the
     * ceilings are present and sane without building a client or reaching an object store.
     *
     * @return the client override configuration carrying both ceilings and the attempt count
     */
    static ClientOverrideConfiguration boundedCallConfiguration() {
        long callTimeout = ContentStoreSupport.boundedLongProperty(CALL_TIMEOUT_PROPERTY, DEFAULT_CALL_TIMEOUT_MILLIS,
                MIN_TIMEOUT_MILLIS, MAX_TIMEOUT_MILLIS);
        long attemptTimeout = Math.min(callTimeout, ContentStoreSupport.boundedLongProperty(ATTEMPT_TIMEOUT_PROPERTY,
                DEFAULT_ATTEMPT_TIMEOUT_MILLIS, MIN_TIMEOUT_MILLIS, MAX_TIMEOUT_MILLIS));
        return ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofMillis(callTimeout))
                .apiCallAttemptTimeout(Duration.ofMillis(attemptTimeout))
                // the SDK's service-appropriate backoff is kept; only the attempt count becomes explicit, so
                // the worst-case number of outbound attempts is a configured number rather than a default that
                // a future SDK line is free to change
                .retryStrategy(strategy -> strategy.maxAttempts(boundedMaxAttempts()))
                .build();
    }

    /**
     * Returns the configured ceiling on how many times one storage call may be attempted.
     *
     * @return the attempt count, bounded to the range this provider accepts
     */
    private static int boundedMaxAttempts() {
        return ContentStoreSupport.boundedIntProperty(MAX_ATTEMPTS_PROPERTY, DEFAULT_MAX_ATTEMPTS, MIN_MAX_ATTEMPTS,
                MAX_MAX_ATTEMPTS);
    }

    /**
     * Reports a deadline a built configuration holds, so a log line states what was actually applied.
     *
     * <p>Read back from the configuration rather than from the property a second time, so the number reported
     * and the number the client carries cannot drift apart.
     *
     * @param deadline the deadline the configuration holds
     * @return the deadline in milliseconds, or {@code -1} when the configuration holds none
     */
    private static long millisOf(Optional<Duration> deadline) {
        return deadline.map(Duration::toMillis).orElse(-1L);
    }

    /**
     * Validates a configured endpoint's shape, leaving the deployment's own policy to
     * {@link #requirePermittedEndpoint()}.
     *
     * <p>Package-private and single-argument so that the store package's own tests can assert the grammar,
     * and the absolute refusals that go with it, directly. This is the plaintext-permitting form of
     * {@link #validatedEndpoint(String, boolean)}: an instance metadata address is refused here as it is
     * everywhere, because that refusal is not policy; whether a plain {@code http} endpoint is acceptable and
     * which hosts a deployment has declared are policy, and {@link #requirePermittedEndpoint()} applies them
     * to the result.
     *
     * @param configuredEndpoint the configured endpoint override; surrounding whitespace is tolerated
     * @return the endpoint to override the default with, never null
     * @throws GeneralException as a {@link ContentStoreConfigurationException} if the endpoint is not an
     *     absolute {@code http} or {@code https} URI naming a host, carries user information, a query or a
     *     fragment, or addresses a cloud instance metadata service
     */
    static URI usableEndpoint(String configuredEndpoint) throws GeneralException {
        return validatedEndpoint(configuredEndpoint, true);
    }

    /**
     * Reduces an endpoint to the scheme, host and port that identify the store, for reporting.
     *
     * <p>A configuration <em>refusal</em> identifies the endpoint by opaque reference instead, because at that
     * point the value has not been validated and may still carry a credential in its user-information
     * component. A report about a client that was successfully built can name the store, because the value
     * reaching here has already been validated to carry no user information, no query and no fragment - and
     * naming the store an instance actually attached to is what an operator needs in order to confirm it. This
     * is the only form in which any part of the configured endpoint reaches a log line.
     *
     * <p>Package-private so that the store package's own tests can prove exactly that.
     *
     * @param endpointOverride the validated endpoint, or null when Amazon S3's own endpoint applies
     * @return a value safe to log, which identifies the store without reproducing the configured value
     */
    static String endpointMarker(URI endpointOverride) {
        if (endpointOverride == null) {
            return AMAZON_S3_ENDPOINT;
        }
        StringBuilder marker = new StringBuilder(endpointOverride.getScheme()).append("://")
                .append(endpointOverride.getHost());
        if (endpointOverride.getPort() != -1) {
            marker.append(':').append(endpointOverride.getPort());
        }
        return marker.toString();
    }

    /**
     * Normalises a configured value, so that null and whitespace-only alike are read as "not configured".
     *
     * @param value the value as configured, which may be null
     * @return the trimmed value, or an empty string when nothing was configured
     */
    private static String trimmedOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Translates an absent object into the signal the SPI requires, so that callers which dereference the
     * result without a null check keep behaving exactly as they do against a filesystem.
     *
     * <p>The store's own failure is deliberately not chained, for the reason given on {@link #storeFailure}.
     * An absent object is in any case an expected outcome rather than a diagnostic puzzle: the key, as an
     * opaque reference, is the whole of what a caller needs.
     *
     * @param key the object key that resolved to nothing
     * @param cause the failure reported by the object store, summarised rather than retained
     * @return the exception to throw
     */
    private FileNotFoundException absentObject(String key, SdkException cause) {
        return new FileNotFoundException("Content store provider [s3] found no object " + ContentStoreUtil.reference(key)
                + " in bucket " + ContentStoreUtil.reference(bucket) + " [" + cause.getClass().getSimpleName() + "]");
    }

    /**
     * Translates a failure attributable to the bucket rather than to a key.
     *
     * <p>Reported as a provider failure rather than as absence, because collapsing the two is what lets a
     * deleted or mis-routed bucket read as missing content for the entire catalogue. The store's own
     * diagnostic is summarised rather than chained, for the reason given on {@link #storeFailure}.
     *
     * <p>Reported with the SPI's I/O signal, exactly as {@link #storeFailure} is, and for the same reason: a
     * bucket that answers 404 is a store that did not serve the request, not a provider whose configuration is
     * incomplete - which is what {@code GeneralException} means in this SPI. The store says the same thing
     * three ways - a typed {@link NoSuchBucketException}, a {@code NoSuchBucket} error code, and a bare 404 the
     * bucket probe could not account for - and all three arrive here, so all three have to be reported
     * identically or a caller's handling would depend on which shape the store happened to choose.
     *
     * @param cause the failure reported by the object store
     * @return the exception to throw
     */
    private IOException bucketFailure(SdkException cause) {
        return new IOException("Content store provider [s3] could not reach the configured bucket "
                + ContentStoreUtil.reference(bucket) + ": " + summarise(cause) + "; the failure is not object"
                + " absence, so it is reported rather than answered as missing content");
    }

    /**
     * Translates a failure of the object store itself into the SPI's I/O signal, keeping only what is stable
     * and safe.
     *
     * <p><strong>The SDK exception is neither chained nor quoted.</strong> An error reply from an object store
     * is composed by the remote end and can echo the request that produced it - the bucket, the object key, the
     * endpoint, request headers, and the identity that signed the request - and an S3-compatible store on the
     * other end of an endpoint override is free to put anything at all in it. Chaining that exception, which is
     * what this method used to do, put all of it into every stack trace: into OFBiz's own logs, into any log
     * aggregator they are shipped to, and into whatever a user-facing error page or service fault chose to
     * render. The exception was retained precisely so the message need not repeat sensitive text, which quietly
     * achieved the opposite - the cause is the part that gets printed.
     *
     * <p>What survives is only what is stable, useful and generated locally or by the transport rather than by
     * the remote end's prose: the HTTP status code, the request and extended request identifiers a store issues
     * for exactly this purpose (they are what support is quoted, and they carry no content), whether a deadline
     * expired, the SDK exception's class name, and an opaque reference to the key. That is enough to correlate a
     * failure with the store's own server-side record, which is where the full detail belongs.
     *
     * @param action the storage action that failed, named so the message identifies it
     * @param key the object key the action addressed, rendered as an opaque reference
     * @param cause the failure reported by the object store, summarised rather than retained
     * @return the exception to throw
     */
    private IOException storeFailure(String action, String key, SdkException cause) {
        return new IOException("Content store provider [s3] could not " + action + " " + ContentStoreUtil.reference(key)
                + " in bucket " + ContentStoreUtil.reference(bucket) + ": " + summarise(cause)
                + ". The object store's own diagnostic is deliberately not reproduced here; correlate the request"
                + " identifier with the store's server-side log for the detail");
    }

    /**
     * Summarises a failure reported by the object store using only fields the remote end cannot fill with
     * arbitrary text.
     *
     * <p>A deadline that expired is named as such, because "the store did not answer in time" and "the store
     * answered with an error" call for quite different operator action. The distinction is drawn from the SDK
     * exception's own type, so it is locally generated rather than read out of a remote reply.
     *
     * @param cause the failure reported by the object store
     * @return a short, safe summary, never null
     */
    private static String summarise(SdkException cause) {
        StringBuilder summary = new StringBuilder(cause.getClass().getSimpleName());
        if (cause instanceof ApiCallTimeoutException) {
            summary.append(" the call deadline expired");
        } else if (cause instanceof ApiCallAttemptTimeoutException) {
            summary.append(" every attempt deadline expired");
        }
        if (cause instanceof AwsServiceException) {
            AwsServiceException service = (AwsServiceException) cause;
            summary.append(" status=").append(service.statusCode());
            AwsErrorDetails details = service.awsErrorDetails();
            if (details != null) {
                // The error CODE is a closed vocabulary defined by the S3 API - NoSuchBucket, AccessDenied and
                // so on - so it is safe and genuinely diagnostic. The error MESSAGE is free text from the remote
                // end and is deliberately not read.
                summary.append(" code=").append(ContentStoreUtil.describe(details.errorCode()));
            }
            summary.append(" requestId=").append(ContentStoreUtil.describe(service.requestId()))
                    .append(" extendedRequestId=").append(ContentStoreUtil.describe(service.extendedRequestId()));
        }
        return summary.toString();
    }

    /**
     * An {@link SdkHttpClient.Builder} that merges this provider's transport deadlines over the SDK's service
     * defaults, without this file naming any concrete HTTP implementation.
     *
     * <p>The SDK hands a service's defaults to {@code buildWithDefaults} at the moment it builds the client.
     * Merging with this provider's options on the higher-precedence side is what makes the configured
     * deadlines win while every option this provider does not set keeps the SDK's own value.
     */
    private static final class BoundedTransportBuilder implements SdkHttpClient.Builder<BoundedTransportBuilder> {

        private final SdkHttpClient.Builder<?> delegate;
        private final AttributeMap options;

        BoundedTransportBuilder(SdkHttpClient.Builder<?> delegate, AttributeMap options) {
            this.delegate = delegate;
            this.options = options;
        }

        @Override
        public SdkHttpClient buildWithDefaults(AttributeMap serviceDefaults) {
            return delegate.buildWithDefaults(options.merge(serviceDefaults));
        }
    }
}
