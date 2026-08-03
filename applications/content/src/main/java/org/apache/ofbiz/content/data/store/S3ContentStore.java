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
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
 * {@code http} endpoint is accepted only for a store on this host, or when
 * {@code content.store.s3.insecure.endpoint.allowed} explicitly permits it; the container
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
 * <p>That rule holds on every operation, {@code exists} included, and it is why {@code exists} asks
 * the question with a ranged {@code GET} rather than a {@code HEAD}. A {@code HEAD} response carries
 * no body, so a store answering 404 to one supplies no error code and the SDK reports a missing
 * bucket, a refused credential and an endpoint that is not an object store all as {@code NoSuchKey} -
 * every one of which would then have been answered {@code false}, which is exactly how an outage
 * becomes silently missing content. A {@code GET} answers with an error document, so the code is real:
 * {@code NoSuchKey} is absence, {@code NoSuchBucket} and {@code AccessDenied} and a 404 with no code
 * are failures, and {@code InvalidRange} means the object is there and empty. The response is aborted
 * unread and asks for one byte, so it costs what the {@code HEAD} cost; and it needs only
 * {@code s3:GetObject}, which the read path already requires, whereas verifying the bucket instead
 * would have needed {@code s3:ListBucket} and would have turned every absence into a failure for a
 * least-privilege deployment. See {@link #exists(String)}.
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
     * The error code a store answers when the requested byte range lies past the end of the object.
     *
     * <p>It means the object EXISTS and is shorter than the range asked for, which for
     * {@value #EXISTENCE_RANGE} means it is empty. {@link #put} accepts a zero-length array, so an
     * empty object is legitimate stored content and this code is an existence answer rather than an
     * absence or a failure.
     */
    private static final String EMPTY_RANGE_ERROR_CODE = "InvalidRange";

    /**
     * The byte range {@link #exists} asks for: the first byte and no more.
     *
     * <p>A GET is what makes an absence distinguishable from a store failure, and this range is what
     * keeps that GET from costing a transfer. The response is aborted without being read, so not even
     * this byte is delivered.
     */
    private static final String EXISTENCE_RANGE = "bytes=0-0";

    /**
     * Link-local addresses that answer with cloud instance credentials: the EC2/GCE/Azure instance
     * metadata service, its IPv6 form, and the ECS task metadata endpoint. An endpoint naming one
     * of these is refused with no override, because the response to a request sent there is a set
     * Hosts that answer with cloud instance credentials: the EC2/GCE/Azure instance metadata service,
     * its IPv6 form, the ECS task metadata endpoint and the Google metadata name. An endpoint naming
     * one of these is refused with no override, because the response to a request sent there is a set
     * of role credentials for the whole instance.
     *
     * <p>This is the same list, with the same entries, that the container entry point refuses in
     * {@code INSTANCE_METADATA_HOSTS}. The two are kept identical deliberately: a value refused before
     * the JVM starts must also be refused when it is written straight into {@code content.properties},
     * or the weaker of the two lists is the one that decides.
     */
    private static final String[] INSTANCE_METADATA_HOSTS = {
        "169.254.169.254",
        "[fd00:ec2::254]",
        "fd00:ec2::254",
        "169.254.170.2",
        "metadata.google.internal",
    };

    /** The property that selects the bucket, read from the property file alone. */
    private static final String BUCKET_PROPERTY = "content.store.s3.bucket";

    /** The property that selects the region, read from the property file alone. */
    private static final String REGION_PROPERTY = "content.store.s3.region";

    /** The property that overrides the endpoint, read from the property file alone. */
    private static final String ENDPOINT_PROPERTY = "content.store.s3.endpoint";

    /** The property that supplies the access key id, read from the property file alone. */
    private static final String ACCESS_KEY_ID_PROPERTY = "content.store.s3.access.key.id";

    /** The property that supplies the secret access key, read from the property file alone. */
    private static final String SECRET_ACCESS_KEY_PROPERTY = "content.store.s3.secret.access.key";

    /** The property that selects path-style addressing, read from the property file alone. */
    private static final String PATH_STYLE_PROPERTY = "content.store.s3.path.style";

    /**
     * The property that permits a plaintext endpoint to a host other than this one.
     *
     * <p>The container entry point renders it from {@code OFBIZ_PROFILE}: {@code true} only for a
     * development profile that supplied an {@code http://} endpoint, {@code false} otherwise - and in
     * the deployed profile it refuses such an endpoint outright before this is ever read. Committed
     * {@code false}, so a hand-maintained {@code content.properties} fails closed as well.
     */
    private static final String INSECURE_ENDPOINT_PROPERTY = "content.store.s3.insecure.endpoint.allowed";

    /** The shortest bucket name an S3-compatible store accepts. */
    private static final int BUCKET_MIN_LENGTH = 3;

    /** The longest bucket name an S3-compatible store accepts. */
    private static final int BUCKET_MAX_LENGTH = 63;

    /** The shortest region identifier accepted; the shape rather than a list of known regions. */
    private static final int REGION_MIN_LENGTH = 2;

    /** The longest region identifier accepted, beyond which the value is a URL in the wrong variable. */
    private static final int REGION_MAX_LENGTH = 32;
    /**
     * Host NAMES that answer with cloud instance credentials, refused whether or not they resolve.
     *
     * <p>These are refused by name as well as by address because the address check below needs the name to
     * resolve, and a container whose resolver is not yet reachable would otherwise accept them. There is no
     * legitimate object store behind any of them.
     */
    private static final String[] INSTANCE_METADATA_NAMES = {
        "metadata.google.internal",
        "metadata.goog",
        "instance-data",
        "instance-data.ec2.internal",
    };

    /**
     * The one unique-local address that answers with cloud instance credentials: the IPv6 form of the
     * EC2 instance metadata service.
     *
     * <p>Only this address, and not the whole of {@code fd00::/8}, because unique-local addressing is
     * legitimate private space that an object store may well sit in - so refusing the range would refuse
     * real deployments, while refusing this address refuses only the metadata service.
     */
    private static final String METADATA_IPV6_ADDRESS = "fd00:ec2::254";

    /** The first octet of the IPv4 link-local range, {@code 169.254.0.0/16}. */
    private static final int LINK_LOCAL_FIRST_OCTET = 169;

    /** The second octet of the IPv4 link-local range, {@code 169.254.0.0/16}. */
    private static final int LINK_LOCAL_SECOND_OCTET = 254;

    /** How many parts an IPv4 address may be written in: {@code a}, {@code a.b}, {@code a.b.c}, {@code a.b.c.d}. */
    private static final int MAX_IPV4_PARTS = 4;

    /** Bits in one octet, used to fold a short-form IPv4 literal into its four octets. */
    private static final int OCTET_BITS = 8;

    /** The greatest value a whole IPv4 literal can carry, {@code 2^32 - 1}. */
    private static final long MAX_IPV4_VALUE = 4294967295L;

    /**
     * How a host name is resolved to addresses, so that the endpoint check can be exercised offline.
     *
     * <p>Overridden by this package's own test alone. The default is the platform resolver; a test installs
     * a resolver of its own so that the DNS-alias policy can be asserted deterministically, with no name
     * server, no network and no dependence on what the build host's resolver happens to answer.
     */
    interface HostResolver {
        /**
         * Resolves a host name to every address it names.
         *
         * @param host the host name
         * @return the addresses it resolves to, never empty
         * @throws UnknownHostException if the name does not resolve
         */
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /** The resolver a test installed, or {@code null} for the platform resolver, which is every deployment. */
    private static final AtomicReference<HostResolver> HOST_RESOLVER = new AtomicReference<>(null);

    /**
     * How the two SDK objects this provider owns are created, so that how they are configured, and
     * that they are released, can both be observed.
     *
     * <p>The public constructor is the only place the AWS SDK is named from a configuration-driven
     * path, so it is the only place that decides which region, which addressing style, which
     * principal and which deadlines a deployment actually gets. None of that is observable through a
     * pre-built client: a test handed a finished {@code S3Client} can see what the provider does with
     * it, but not what the provider asked for when it was made. This seam supplies the builder
     * instead of the client, so the configuration reaching the builder is exactly what a test
     * inspects, and the client the builder returns is one the test can watch being closed.
     *
     * <p>Overridden by this package's own test alone; {@code null} in every deployment, which builds
     * a real client from {@link S3Client#builder()} and a real default credential chain.
     */
    interface SdkConstruction {
        /**
         * Supplies the builder the client is configured on and built from.
         *
         * @return a fresh builder, never null and never shared between calls
         */
        S3ClientBuilder clientBuilder();

        /**
         * Supplies the credential provider used when no static credential pair is configured.
         *
         * @return the provider to authenticate with, never null
         */
        AwsCredentialsProvider defaultCredentialsProvider();
    }

    /**
     * The real SDK: a fresh {@link S3Client} builder, and a default credential chain instance this
     * provider owns.
     *
     * <p>{@code DefaultCredentialsProvider.builder().build()} rather than {@code create()}, because
     * {@code create()} is deprecated for handing out a shared singleton - and a singleton is not
     * something this provider may close when it is displaced, whereas an instance it made is.
     */
    private static final SdkConstruction REAL_SDK = new SdkConstruction() {
        @Override
        public S3ClientBuilder clientBuilder() {
            return S3Client.builder();
        }

        @Override
        public AwsCredentialsProvider defaultCredentialsProvider() {
            return DefaultCredentialsProvider.builder().build();
        }
    };

    /** The SDK construction a test installed, or {@code null} for the real SDK, which is every deployment. */
    private static final AtomicReference<SdkConstruction> SDK_CONSTRUCTION = new AtomicReference<>(null);

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
     * <p>The delegator is the one that selected this provider, so every overridable tunable below - the
     * key prefix, the read bound, the deadlines and the retry cap - is read from the same layer the
     * selection was, and a {@code SystemProperty} row that changes one of them reaches this provider.
     * The bucket, the region, the endpoint, the addressing style and the credentials are read from
     * {@code content.properties} alone, whichever delegator asked: they decide where durable content is
     * written and which principal writes it, they are what the container entry point validates and
     * renders, and a database row able to change them would be a control plane over this deployment's
     * storage location and identity that no start-up validation sees. Both halves are read here, in one
     * place, so the selector and the provider can never be configured from different layers.
     *
     * <p>Failure-safe: every value the configuration can be refused for is read and checked before the
     * client is built, so a refusal never leaves an SDK client or a credential provider behind. That
     * matters more here than the ordering usually would, because a construction that fails is not
     * cached - a leak would therefore repeat on every read rather than happen once.
     *
     * @param delegator the delegator the overridable tunables are read through; may be null, in which
     *     case only {@code content.properties} is consulted
     * @throws GeneralException if the configuration is incomplete or contradictory - no bucket or an
     *     unusable one, no region or an unusable one, an unusable endpoint, a plaintext endpoint that
     *     is neither loopback nor permitted, a non-boolean addressing style, an unusable key prefix, or
     *     exactly one of the two credential properties
     */
    public S3ContentStore(Delegator delegator) throws GeneralException {
        // Every value below decides where this deployment's durable content is written, or which
        // principal writes it, so each is read from content.properties alone through
        // ContentStoreFactory.deploymentValue - never through a SystemProperty row. The container entry
        // point validates and renders exactly these, and a database-resident override of them would be a
        // second control plane over the deployment's storage location and identity that no start-up check
        // sees. The key prefix, the read bound and the deadlines are tunables and are still overridable.
        String configuredBucket = deploymentValue(BUCKET_PROPERTY);
        String region = deploymentValue(REGION_PROPERTY);
        String endpoint = deploymentValue(ENDPOINT_PROPERTY);
        String accessKeyId = deploymentValue(ACCESS_KEY_ID_PROPERTY);
        String secretAccessKey = deploymentValue(SECRET_ACCESS_KEY_PROPERTY);
        // Parsed strictly rather than as "true or not true": path-style addressing decides the shape of
        // every request URL, and most S3-compatible stores serve nothing without it, so a mistyped value
        // silently meaning false would turn a configuration mistake into content that cannot be read.
        boolean pathStyle = requiredBoolean(PATH_STYLE_PROPERTY, deploymentValue(PATH_STYLE_PROPERTY), false);

        // EVERY setting that can be judged without the SDK is judged here, before the block below
        // creates anything. The order matters, and not only for tidiness: a credential provider and a
        // client both own resources, and an exception thrown after they exist but before this
        // constructor returns leaves them unreachable and unclosed, holding a connection pool and its
        // threads for the life of the JVM. There is no `this` to close yet and no caller holding a
        // reference to close, so the leak cannot be repaired afterwards - it can only be avoided by
        // not creating anything until nothing local can still refuse the configuration. An earlier
        // version validated the key prefix after building the client and leaked exactly that way.
        requireUsableBucket(configuredBucket);
        requireUsableRegion(region);
        if (UtilValidate.isEmpty(accessKeyId) != UtilValidate.isEmpty(secretAccessKey)) {
            throw new GeneralException("content.store.s3.access.key.id and content.store.s3.secret.access.key must"
                    + " be supplied together; leave both blank to authenticate with the AWS default credential"
                    + " chain instead");
        }
        // Validated HERE, before anything is built, and not where it is assigned. Every refusal this
        // constructor can raise now happens while it owns nothing: an unusable key prefix used to be
        // discovered after the client existed, which left that client - and, on the static branch, the
        // credential provider it holds - unreachable and unclosed. ContentStoreFactory does not cache a
        // construction that failed, so every subsequent read retried the construction and leaked
        // another client, turning one mistyped property into an unbounded leak of connection pools and
        // their threads. After this line nothing that can throw remains before the last assignment.
        String prefix = validatedKeyPrefix(property(KEY_PREFIX_PROPERTY, delegator));
        URI endpointOverride = UtilValidate.isNotEmpty(endpoint) ? validatedEndpoint(endpoint) : null;

        // Past this line every refusal comes from the SDK, and every SDK object created is either
        // owned by a constructed instance or closed on the way out.
        SdkConstruction construction = sdkConstruction();
        // Named explicitly in both branches. Leaving the credential provider unset would give the
        // same behaviour today by accident rather than by decision, and F14's point stands: which
        // principal a deployment authenticates as is not something to leave to a builder default.
        AwsCredentialsProvider credentials = UtilValidate.isNotEmpty(accessKeyId)
                ? StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey))
                : construction.defaultCredentialsProvider();

        try {
            S3ClientBuilder builder = construction.clientBuilder()
                    .region(Region.of(region))
                    .forcePathStyle(pathStyle)
                    .credentialsProvider(credentials)
                    .overrideConfiguration(deadlines(delegator));
            if (endpointOverride != null) {
                builder.endpointOverride(endpointOverride);
            }
            this.s3Client = builder.build();
        // RuntimeException rather than SdkException, which it is a subclass of: a builder refuses an
        // unusable region or an unusable override with IllegalArgumentException as readily as the SDK
        // refuses one with SdkException, and either way the credential provider above has to be
        // released before this constructor gives up.
        } catch (RuntimeException e) {
            closeQuietly(credentials);
            // NEITHER LOGGED NOR ATTACHED: only the sanitised description of the failure is recorded. An
            // SDK build failure quotes the configuration it rejected - which here is an endpoint and a
            // credential pair - so the exception object cannot be handed to Debug either, because logging
            // it writes its message and the whole stack trace into the container log. What is recorded is
            // the failure's type and, where it has them, its service fields; what is thrown carries no
            // cause at all, because GeneralException.getMessage() appends the message of any cause it is
            // given and this refusal is reported to whoever asked for the content.
            Debug.logError("The S3 content store client could not be built from the content.store.s3.*"
                    + " configuration: " + redacted(e), MODULE);
            throw new GeneralException("The S3 content store client could not be built from the"
                    + " content.store.s3.* configuration; the sanitised diagnostic is in the server log");
        }
        this.bucket = configuredBucket;
        this.keyPrefix = prefix;
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
        if (prefix.getBytes(StandardCharsets.UTF_8).length >= ContentStoreFactory.MAX_KEY_LENGTH_BYTES) {
            throw new GeneralException(KEY_PREFIX_PROPERTY + " is " + prefix.length() + " characters, which leaves no"
                    + " room for a key inside the " + ContentStoreFactory.MAX_KEY_LENGTH_BYTES + " byte limit");
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
        putObject(key, RequestBody.fromBytes(data));
    }

    @Override
    public void put(String key, InputStream content, long length) throws GeneralException, IOException {
        if (content == null) {
            throw new GeneralException("Cannot store content from a null stream for content store key ["
                    + key + "]");
        }
        if (length < 0L) {
            throw new GeneralException("Cannot store " + length + " bytes for content store key [" + key + "]");
        }
        // fromInputStream, not fromContentProvider: the SDK then frames the request from the length it
        // was given and streams the body, so the content is never held in this JVM's heap in full - which
        // is the whole reason this overload exists. The stream is deliberately NOT closed here; the
        // contract leaves it with the caller, which is what lets a caller go on using its own source.
        putObject(key, RequestBody.fromInputStream(content, length));
    }

    /**
     * Issues one {@code PutObject} for either {@code put} overload.
     *
     * <p>Both overloads name the same bucket, the same object key and the same failure report; only the
     * body differs, so the request is built once here rather than twice.
     *
     * @param key the provider-relative storage key
     * @param body the request body the SDK sends
     * @throws GeneralException if the key is unusable or the store refused the write
     * @throws IOException if the store could not be written to
     */
    private void putObject(String key, RequestBody body) throws GeneralException, IOException {
        String objectKey = objectKey(key);
        try {
            s3Client.putObject(PutObjectRequest.builder().bucket(bucket).key(objectKey).build(), body);
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
    public ContentStream openStream(String key) throws GeneralException, IOException {
        String objectKey = objectKey(key);
        ResponseInputStream<GetObjectResponse> content;
        try {
            content = s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(objectKey).build());
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
        // Wrapped rather than handed over bare, so the caller receives the object's length with the
        // stream and does not have to issue a HEAD - or read the content - to find it. Closing the
        // wrapper closes the response, so ownership still passes to the caller exactly as before.
        // The response is aborted rather than closed if the length cannot be established at all,
        // because a response left open would hold a connection from the pool for good.
        long declared = declaredLength(content, objectKey);
        return new ContentStream(content, declared);
    }

    /**
     * Reports the length the store declared for an open response, refusing a response that declares
     * none.
     *
     * <p>A missing or negative {@code Content-Length} is a store that cannot answer the question this
     * operation exists to answer, and guessing - by reading the content, or by reporting zero - would
     * either defeat the streaming or make a caller declare a length that is wrong. The response is
     * aborted rather than closed, because closing a response that has not been read drains the whole
     * object off the wire, which is exactly the transfer a refusal exists to avoid.
     *
     * @param content the open response
     * @param objectKey the prefixed object key, for the log rather than for the thrown message
     * @return the declared length, never negative
     * @throws IOException if the store declared no usable length
     */
    private long declaredLength(ResponseInputStream<GetObjectResponse> content, String objectKey)
            throws IOException {
        GetObjectResponse response = content.response();
        Long declared = response == null ? null : response.contentLength();
        if (declared != null && declared >= 0L) {
            return declared;
        }
        content.abort();
        Debug.logError("Content store refusal: object [" + objectKey + "] in bucket [" + bucket + "] was served"
                + " without a usable content length, so its size cannot be reported to a consumer that has to"
                + " declare one", MODULE);
        throw new IOException("The requested content could not be served because the store did not report its"
                + " size.");
    }

    /**
     * {@inheritDoc}
     *
     * <p><strong>Asked with a GET, not a HEAD, and that is the whole point.</strong> A {@code HEAD}
     * response carries no body, so a store answering {@code 404} to one cannot say WHY: the SDK has no
     * error code to model and raises {@code NoSuchKeyException} whether the key is absent, the bucket
     * is absent, the credentials cannot see it or the endpoint is not an object store at all. Answering
     * {@code false} to that is what turns a misconfigured or unreachable store into "the content simply
     * is not there", which is the one thing {@link ContentStore} forbids a provider to do.
     *
     * <p>A {@code GET} answers a failure with an error document, so the SDK models it: an absent key is
     * {@code NoSuchKey}, an absent bucket is {@code NoSuchBucket}, a refused credential is
     * {@code AccessDenied}, and a {@code 404} carrying no code at all - what a plain web server
     * answers - remains a failure. Only the first is absence; everything else is reported through
     * {@link #storeFailure}.
     *
     * <p>The transfer that a GET would otherwise cost is not paid. The request asks for
     * {@value #EXISTENCE_RANGE} - one byte - and the response is {@code abort()}ed rather than closed,
     * so the connection is dropped instead of the remainder of the object being drained off the wire.
     * Content of any size therefore costs the same as the HEAD did, plus that one byte. A store answers
     * a range that lies beyond the object with {@code InvalidRange}, which is the one error code that
     * means the object EXISTS - it is what a zero-length object returns, and {@link #put} accepts a
     * zero-length array - so it is mapped to {@code true} rather than to absence or to a failure.
     *
     * <p>It also needs no permission beyond {@code s3:GetObject}, which {@link #get} and
     * {@link #openStream} already require. Verifying the bucket instead would need
     * {@code s3:ListBucket}, so a least-privilege deployment granted only object permissions would have
     * had every absence reported as a failure.
     */
    @Override
    public boolean exists(String key) throws GeneralException, IOException {
        String objectKey = objectKey(key);
        ResponseInputStream<GetObjectResponse> probe = null;
        try {
            probe = s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(objectKey)
                    .range(EXISTENCE_RANGE).build());
            return true;
        } catch (NoSuchKeyException absent) {
            // Absence is an answer, not a failure: the contract requires false rather than a throw.
            // Reached only from a response whose error document named this key as the missing thing,
            // because a GET always carries one - which is what a HEAD could not tell us. Described rather
            // than logged as an object, for the reason storeFailure gives: handing the SDK failure to Debug
            // writes its message and stack trace, which is what redaction exists to prevent.
            Debug.logVerbose("The S3 content store holds nothing under [" + objectKey + "]: "
                    + redacted(absent), MODULE);
            return false;
        } catch (S3Exception e) {
            if (isEmptyObjectRange(e)) {
                // The range asked for lies past the end of the object, so the object is there and has
                // no bytes in it. Present, not absent, and certainly not a failure.
                return true;
            }
            if (isAbsence(e)) {
                Debug.logVerbose("The S3 content store holds nothing under [" + objectKey + "]: " + redacted(e),
                        MODULE);
                return false;
            }
            throw storeFailure("test", objectKey, e);
        } catch (SdkException e) {
            throw storeFailure("test", objectKey, e);
        } finally {
            if (probe != null) {
                // Aborted rather than closed: closing drains the rest of the response, and nothing here
                // reads even the one byte that was asked for.
                probe.abort();
            }
        }
    }

    @Override
    public void delete(String key) throws GeneralException, IOException {
        String objectKey = objectKey(key);
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
        } catch (NoSuchKeyException absent) {
            // Idempotent by contract, and S3 itself reports a delete of an absent object as success,
            // so this is only reached by a store that reports the miss instead. Described rather than
            // logged as an object, for the reason storeFailure gives.
            Debug.logVerbose("The S3 content store already holds nothing under [" + objectKey + "]: "
                    + redacted(absent), MODULE);
        } catch (S3Exception e) {
            if (!isAbsence(e)) {
                throw storeFailure("remove", objectKey, e);
            }
            Debug.logVerbose("The S3 content store already holds nothing under [" + objectKey + "]: " + redacted(e),
                    MODULE);
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
     * {@link ContentStoreFactory#requireUsableKey(String)}, so that this provider refuses exactly the
     * keys the filesystem provider refuses and content migrated between the two keeps every key it
     * had.
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
        ContentStoreFactory.requireUsableKey(key);
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
        if (length > ContentStoreFactory.MAX_KEY_LENGTH_BYTES) {
            throw new GeneralException("A content store key must be at most " + ContentStoreFactory.MAX_KEY_LENGTH_BYTES
                    + " bytes once the configured prefix is applied, and this one is " + length);
        }
        return objectKey;
    }

    /**
     * Requires a value that is a usable object-store bucket name.
     *
     * <p>The published S3 naming rules, applied here rather than left to the store, because the store
     * reports a malformed name as a failure on the first request - by which time a page is already
     * trying to render content to a user - whereas this reports it while the provider is being built,
     * naming the property. These are the same rules, in the same order, that the container entry point
     * applies to {@code OFBIZ_S3_BUCKET}: 3 to 63 characters of lower-case letters, digits, {@code .}
     * and {@code -}, beginning and ending with a letter or a digit, no {@code ..}, {@code .-} or
     * {@code -.} pair, and never the shape of an IPv4 address, which such stores reserve.
     *
     * @param bucket the configured bucket name
     * @throws GeneralException if the value is absent or is not a usable bucket name
     */
    private static void requireUsableBucket(String bucket) throws GeneralException {
        if (UtilValidate.isEmpty(bucket)) {
            throw new GeneralException(BUCKET_PROPERTY + " is required when content.store.provider=s3;"
                    + " there is no default bucket");
        }
        if (bucket.length() < BUCKET_MIN_LENGTH || bucket.length() > BUCKET_MAX_LENGTH) {
            throw new GeneralException(BUCKET_PROPERTY + " must be between " + BUCKET_MIN_LENGTH + " and "
                    + BUCKET_MAX_LENGTH + " characters long, which is what an object-store bucket name allows,"
                    + " and this one is " + bucket.length());
        }
        for (int index = 0; index < bucket.length(); index++) {
            char character = bucket.charAt(index);
            boolean accepted = character >= 'a' && character <= 'z' || character >= '0' && character <= '9'
                    || character == '.' || character == '-';
            if (!accepted) {
                throw new GeneralException(BUCKET_PROPERTY + " must contain only lower-case letters, digits, '.'"
                        + " and '-', which is what an object-store bucket name allows");
            }
        }
        if (!isBucketBoundary(bucket.charAt(0)) || !isBucketBoundary(bucket.charAt(bucket.length() - 1))) {
            throw new GeneralException(BUCKET_PROPERTY + " must begin and end with a lower-case letter or a digit");
        }
        if (bucket.contains("..") || bucket.contains(".-") || bucket.contains("-.")) {
            throw new GeneralException(BUCKET_PROPERTY + " must not contain '..', '.-' or '-.'. Such a bucket name"
                    + " cannot be addressed virtual-host-style or over TLS");
        }
        if (bucket.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) {
            throw new GeneralException(BUCKET_PROPERTY + " must not be formatted as an IPv4 address;"
                    + " S3-compatible stores reserve that shape");
        }
    }

    /**
     * Reports whether a character may begin or end a bucket name.
     *
     * @param character the character to test
     * @return true for a lower-case letter or a digit
     */
    private static boolean isBucketBoundary(char character) {
        return character >= 'a' && character <= 'z' || character >= '0' && character <= '9';
    }

    /**
     * Requires a value that is a usable object-store region identifier.
     *
     * <p>Validated for shape rather than against a list of known regions, exactly as the container
     * entry point validates {@code OFBIZ_S3_REGION}: a list would go stale and would refuse a
     * perfectly good private store, while the shape - lower-case letters, digits and {@code -}, and
     * short - is what tells a region apart from a whole URL pasted into the wrong setting.
     *
     * @param region the configured region identifier
     * @throws GeneralException if the value is absent or is not a usable region identifier
     */
    private static void requireUsableRegion(String region) throws GeneralException {
        if (UtilValidate.isEmpty(region)) {
            throw new GeneralException(REGION_PROPERTY + " is required when content.store.provider=s3;"
                    + " use us-east-1 for a store that has no regions of its own");
        }
        if (region.length() < REGION_MIN_LENGTH || region.length() > REGION_MAX_LENGTH) {
            throw new GeneralException(REGION_PROPERTY + " must be between " + REGION_MIN_LENGTH + " and "
                    + REGION_MAX_LENGTH + " characters long, and this one is " + region.length()
                    + ". A region identifier is short - us-east-1 is 9 characters - so an over-long value is"
                    + " usually an endpoint supplied to the wrong property");
        }
        for (int index = 0; index < region.length(); index++) {
            char character = region.charAt(index);
            boolean accepted = character >= 'a' && character <= 'z' || character >= '0' && character <= '9'
                    || character == '-';
            if (!accepted) {
                throw new GeneralException(REGION_PROPERTY + " must contain only lower-case letters, digits and"
                        + " '-', which is the shape of a region identifier such as us-east-1");
            }
        }
        if (region.startsWith("-") || region.endsWith("-")) {
            throw new GeneralException(REGION_PROPERTY + " must begin and end with a lower-case letter or a digit");
        }
    }

    /**
     * Reads a boolean setting of this provider, refusing a value that is neither {@code true} nor
     * {@code false}.
     *
     * <p>Strict on purpose, and unlike the tunables read through
     * {@link ContentStoreFactory#boundedLong}: a mistyped bound still leaves content readable at the
     * committed default, whereas each setting read through here decides how requests are addressed or
     * whether credentials may travel in clear text. Treating an unrecognised value as {@code false} -
     * which is what {@code "true".equalsIgnoreCase(value)} does - would answer a configuration mistake
     * with a silent choice about exactly those things.
     *
     * @param property the property being read, named in a refusal
     * @param configured the configured value, which may be blank
     * @param whenAbsent the value a blank setting means
     * @return the parsed value
     * @throws GeneralException if the value is present and is neither true nor false
     */
    private static boolean requiredBoolean(String property, String configured, boolean whenAbsent)
            throws GeneralException {
        if (UtilValidate.isEmpty(configured)) {
            return whenAbsent;
        }
        String normalised = configured.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalised)) {
            return true;
        }
        if ("false".equals(normalised)) {
            return false;
        }
        throw new GeneralException(property + " must be true or false, or be left blank for " + whenAbsent
                + ". A value that is neither is refused rather than read as false, because this setting decides"
                + " how object-store requests are made.");
    }

    /**
     * Validates an endpoint override before this deployment's credentials can be sent to it.
     *
     * @param endpoint the configured endpoint
     * @return the endpoint as a URI
     * @throws GeneralException if it is not a plain absolute http or https URI with a host, if it
     *     carries user information, a query or a fragment, if it names an instance metadata address,
     *     or if it is plaintext and neither loopback nor explicitly permitted
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
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new GeneralException("content.store.s3.endpoint must name a host and must carry no user"
                    + " information, query or fragment");
        }
        // Judged before the host is required to be one java.net.URI recognises, and judged on the authority
        // as written when it is not. URI's host grammar rejects a label that starts with a digit followed by
        // a letter, so 0xa9.0xfe.0xa9.0xfe reports no host at all - while the resolver inside the HTTP client
        // accepts it as 169.254.169.254. Refusing it as "no host" would be the right outcome for the wrong
        // reason, and the reason is what tells an operator what they did.
        refuseCredentialDisclosingHost(uri.getHost() == null ? authorityHost(endpoint) : uri.getHost());
        if (uri.getHost() == null) {
            throw new GeneralException("content.store.s3.endpoint must name a host and must carry no user"
                    + " information, query or fragment");
        }
        // Normalised the way the entry point normalises it - lower-cased and with the brackets of an IPv6
        // literal removed - so that https://[FD00:EC2::254]/ and https://fd00:ec2::254/ are one host to
        // both layers. java.net.URI.getHost() keeps the brackets, which is why they are stripped here.
        String host = unbracketed(uri.getHost().toLowerCase(Locale.ROOT));
        for (String metadataHost : INSTANCE_METADATA_HOSTS) {
            if (unbracketed(metadataHost).equals(host)) {
                throw new GeneralException("content.store.s3.endpoint must not name a cloud instance metadata"
                        + " address; a request sent there would disclose the instance's own role credentials");
            }
        }
        if ("http".equals(scheme)) {
            requirePlaintextEndpointIsAcceptable(host);
        }
        return uri;
    }

    /**
     * Reports whether an S3 failure means the object is there but has no byte in the requested range.
     *
     * <p>Only {@link #exists} asks for a range, and it asks for {@value #EXISTENCE_RANGE}, so the one
     * object this can be true of is an empty one - which {@link #put} is allowed to store. Present, and
     * therefore neither absence nor failure.
     *
     * @param e the SDK failure
     * @return {@code true} only when the store reported the range as unsatisfiable
     */
    private static boolean isEmptyObjectRange(S3Exception e) {
        AwsErrorDetails details = e.awsErrorDetails();
        return details != null && EMPTY_RANGE_ERROR_CODE.equals(details.errorCode());
    }

    /**
     * Requires that a plaintext endpoint is one this deployment may really use.
     *
     * <p>Every object written carries the store credential and every object read may be content that is
     * not public, so a plaintext endpoint exposes both to anything on the network path. Two cases are
     * accepted, and nothing else is:
     *
     * <ul>
     * <li><strong>A store on this host.</strong> Traffic to a loopback address never reaches a network,
     * so there is no path to expose it on. This is the local MinIO a developer runs beside OFBiz, and it
     * is why the zero-configuration development flow needs no setting at all.</li>
     * <li><strong>An explicitly permitted store.</strong> {@code content.store.s3.insecure.endpoint
     * .allowed=true} states that plaintext is acceptable for this deployment. The container entry point
     * renders it from {@code OFBIZ_PROFILE} - true only for a development profile that supplied an
     * {@code http://} endpoint, and never in the deployed profile, which refuses such an endpoint
     * outright before the JVM starts. Committed {@code false}, so a hand-maintained
     * {@code content.properties} has to say so as well.</li>
     * </ul>
     *
     * <p>The refusal replaces a warning. A warning in a start-up log is not a defence: the credentials
     * travel in clear text either way, and the deployment that most needs to be told is the one nobody
     * is reading the log of.
     *
     * @param host the endpoint's host, lower-cased and without IPv6 brackets
     * @throws GeneralException when the endpoint is plaintext, is not on this host, and has not been
     *     explicitly permitted
     */
    private void requirePlaintextEndpointIsAcceptable(String host) throws GeneralException {
        if (isLoopbackHost(host)) {
            Debug.logInfo("content.store.s3.endpoint uses http to a loopback address, which never leaves this"
                    + " host. Accepted for a store running beside this instance.", MODULE);
            return;
        }
        if (!requiredBoolean(INSECURE_ENDPOINT_PROPERTY, deploymentValue(INSECURE_ENDPOINT_PROPERTY), false)) {
            throw new GeneralException("content.store.s3.endpoint uses http to a host other than this one, so"
                    + " object-store traffic and the credentials it carries would not be encrypted. Use https,"
                    + " or - for a development store only - set " + INSECURE_ENDPOINT_PROPERTY + "=true. A"
                    + " container renders that setting from OFBIZ_PROFILE and never permits it in the deployed"
                    + " profile.");
        }
        Debug.logWarning("content.store.s3.endpoint uses http, so object-store traffic and the credentials it"
                + " carries are not encrypted. Accepted only because " + INSECURE_ENDPOINT_PROPERTY + " is true,"
                + " which is a development setting.", MODULE);
    }

    /**
     * Reports whether a host names this machine, and therefore an endpoint whose traffic never reaches
     * a network.
     *
     * <p>Decided from the literal text rather than by resolving the name, deliberately: resolving it
     * would make the answer depend on DNS at the moment the provider is built, and a name that resolves
     * to a loopback address today can resolve elsewhere tomorrow - which is exactly the kind of change
     * that must not silently turn a refused endpoint into an accepted one.
     *
     * @param host the endpoint's host, lower-cased and without IPv6 brackets
     * @return true for {@code localhost}, an IPv4 loopback address or the IPv6 loopback address
     */
    private static boolean isLoopbackHost(String host) {
        return "localhost".equals(host)
                || "::1".equals(host)
                || "0:0:0:0:0:0:0:1".equals(host)
                || host.matches("127\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}");
    }

    /**
     * Removes the brackets an IPv6 literal is written with, leaving every other host unchanged.
     *
     * @param host the host as written
     * @return the host without surrounding brackets
     */
    private static String unbracketed(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    /**
     * Refuses an endpoint host that names, or resolves to, an address which answers with this deployment's
     * own credentials.
     *
     * <p>A textual comparison against {@code 169.254.169.254} is not a defence, because that address can be
     * written in forms that share no characters with it: {@code 2852039166} as one decimal number,
     * {@code 0xA9FEA9FE} as one hexadecimal number, {@code 0251.0376.0251.0376} in octal,
     * {@code 169.254.43518} in the three-part short form, and every mixture of those. The C resolver, and
     * therefore the SDK's HTTP client, accepts all of them as the same address. So the host is
     * <em>canonicalized</em> instead - parsed into the address it actually denotes - and the address is what
     * is judged.
     *
     * <p>What is refused, and why the ranges rather than the individual addresses:
     *
     * <ul>
     * <li>every IPv4 address in {@code 169.254.0.0/16}, the link-local range. The EC2, GCE and Azure
     * metadata service ({@code 169.254.169.254}) and the ECS task metadata endpoint
     * ({@code 169.254.170.2}) both live there, and nothing legitimately reachable does: a link-local
     * address is not routable, so no object store can be behind one;</li>
     * <li>every IPv6 link-local address, {@code fe80::/10}, for the same reason;</li>
     * <li>{@code fd00:ec2::254} exactly, the IPv6 form of the EC2 metadata service. Only that address, not
     * the surrounding unique-local range, which is legitimate private space an object store may well sit
     * in;</li>
     * <li>the metadata host <em>names</em> in {@link #INSTANCE_METADATA_NAMES}, refused whether or not they
     * resolve.</li>
     * </ul>
     *
     * <p><strong>DNS aliases and rebinding.</strong> A name that resolves to any of the above is refused too,
     * so an alias cannot be used to reach the metadata service. A name that does not resolve at all is
     * reported and accepted: a container is routinely started before its resolver, or before the private
     * zone holding the store's name, is reachable, and refusing would turn that into a failed deployment
     * while accepting costs nothing - the SDK resolves the name again on every connection and this check is
     * not what stands between the process and the network. For the same reason this check cannot defend
     * against rebinding, where a name resolves acceptably here and to a metadata address later: nothing at
     * this layer can, because the resolution that matters happens inside the HTTP client on each request.
     * The control for that is egress policy - a security group, a firewall rule or a proxy that refuses
     * link-local destinations - and IMDSv2 on the instance. This check is defence in depth against a
     * configuration mistake or a single tampered variable, and is deliberately not claimed to be more.
     *
     * @param configuredHost the host component of the configured endpoint, as {@code java.net.URI} reports it
     *     - which keeps the brackets of an IPv6 literal
     * @throws GeneralException if the host names or resolves to a credential-disclosing address
     */
    private static void refuseCredentialDisclosingHost(String configuredHost) throws GeneralException {
        String host = configuredHost.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        for (String metadataName : INSTANCE_METADATA_NAMES) {
            if (metadataName.equals(host)) {
                throw metadataRefusal();
            }
        }
        InetAddress literal = numericAddress(host);
        if (literal != null) {
            if (disclosesCredentials(literal)) {
                throw metadataRefusal();
            }
            return;
        }
        InetAddress[] resolved;
        try {
            HostResolver resolver = HOST_RESOLVER.get();
            resolved = resolver == null ? InetAddress.getAllByName(host) : resolver.resolve(host);
        } catch (UnknownHostException unresolved) {
            // Reported and accepted; see the DNS paragraph above. The name is not echoed at error level
            // because an endpoint is configuration an operator supplied and may carry deployment topology.
            Debug.logInfo("The content.store.s3.endpoint host does not resolve yet, so it could not be checked"
                    + " against the cloud instance metadata addresses. The object store's own client resolves"
                    + " it again on every connection.", MODULE);
            return;
        }
        for (InetAddress address : resolved) {
            if (disclosesCredentials(address)) {
                throw metadataRefusal();
            }
        }
    }

    /**
     * Extracts the host component of an endpoint textually, for a value {@code java.net.URI} parses but whose
     * host it declines to recognise.
     *
     * <p>Everything between {@code ://} and the first {@code /}, {@code ?} or {@code #}, less any user
     * information and any port, with the brackets of an IPv6 literal removed. Deliberately permissive: this
     * is only ever used to decide whether a value that has already failed URI's host grammar is nonetheless a
     * metadata address, so being generous about what it extracts can only widen the refusal.
     *
     * @param endpoint the configured endpoint
     * @return the host as written, possibly empty
     */
    private static String authorityHost(String endpoint) {
        int schemeEnd = endpoint.indexOf("://");
        String authority = schemeEnd < 0 ? endpoint : endpoint.substring(schemeEnd + 3);
        for (String terminator : new String[] {"/", "?", "#"}) {
            int end = authority.indexOf(terminator);
            if (end >= 0) {
                authority = authority.substring(0, end);
            }
        }
        int userInfoEnd = authority.lastIndexOf('@');
        if (userInfoEnd >= 0) {
            authority = authority.substring(userInfoEnd + 1);
        }
        if (authority.startsWith("[")) {
            int bracketEnd = authority.indexOf(']');
            return bracketEnd < 0 ? authority : authority.substring(1, bracketEnd);
        }
        int portStart = authority.lastIndexOf(':');
        return portStart < 0 ? authority : authority.substring(0, portStart);
    }

    /**
     * Reports whether an address is one that answers with the instance's own credentials.
     *
     * @param address the address to judge
     * @return {@code true} when a request sent there could disclose role credentials
     */
    private static boolean disclosesCredentials(InetAddress address) {
        if (address.isLinkLocalAddress()) {
            // Covers 169.254.0.0/16 and fe80::/10 in every spelling, because this is decided from the
            // address bytes and not from how the address was written.
            return true;
        }
        byte[] octets = address.getAddress();
        if (octets.length == 4) {
            return (octets[0] & 0xFF) == LINK_LOCAL_FIRST_OCTET && (octets[1] & 0xFF) == LINK_LOCAL_SECOND_OCTET;
        }
        try {
            return address.equals(InetAddress.getByName(METADATA_IPV6_ADDRESS));
        } catch (UnknownHostException impossible) {
            // A literal, so it never resolves. Kept as a failure rather than swallowed silently.
            Debug.logWarning("The IPv6 metadata address literal [" + METADATA_IPV6_ADDRESS + "] could not be"
                    + " parsed, so an endpoint could not be compared with it", MODULE);
            return false;
        }
    }

    /**
     * Builds the refusal, so that every path refuses with the same words.
     *
     * <p>The configured value is deliberately not quoted back: the message reaches whoever supplied it, and
     * an endpoint is the one setting most likely to have a credential embedded in it.
     *
     * @return the refusal to throw
     */
    private static GeneralException metadataRefusal() {
        return new GeneralException("content.store.s3.endpoint must not name, or resolve to, a cloud instance"
                + " metadata address; a request sent there would disclose the instance's own role credentials."
                + " This is refused in every profile and has no override.");
    }

    /**
     * Parses a host as a numeric address literal, in any of the forms a resolver accepts, without consulting
     * DNS.
     *
     * <p>The IPv4 forms are the {@code inet_aton} grammar: one to four dot-separated parts, each decimal,
     * octal when it carries a leading {@code 0}, or hexadecimal when it carries a leading {@code 0x}. Fewer
     * than four parts means the last part supplies the remaining octets - so {@code 169.254.43518} and
     * {@code 2852039166} both denote {@code 169.254.169.254}. Reproducing that grammar here is the whole
     * point: the platform's own {@code InetAddress.getByName} accepts some of these forms and rejects
     * others depending on the release, and a check that relied on it would silently stop covering the forms
     * it stopped accepting - while the C resolver inside the HTTP client would go on accepting them.
     *
     * @param host the lower-cased host, with any IPv6 brackets already removed
     * @return the address the host denotes, or {@code null} when it is not a numeric literal and therefore
     *     has to be resolved
     */
    private static InetAddress numericAddress(String host) {
        if (host.indexOf(':') >= 0) {
            // An IPv6 literal is the one numeric form with no ambiguity, and getByName parses it without
            // resolving anything because a name may not contain a colon.
            try {
                return InetAddress.getByName(host);
            } catch (UnknownHostException notALiteral) {
                Debug.logVerbose(notALiteral, "The content.store.s3.endpoint host is not an IPv6 literal", MODULE);
                return null;
            }
        }
        String[] parts = host.split("\\.", -1);
        if (parts.length > MAX_IPV4_PARTS) {
            return null;
        }
        long[] values = new long[parts.length];
        for (int index = 0; index < parts.length; index++) {
            long value = numericPart(parts[index]);
            // The last part carries every octet the earlier parts did not, so its ceiling grows as the
            // number of parts shrinks; every earlier part is one octet.
            long ceiling = index == parts.length - 1
                    ? (MAX_IPV4_VALUE >>> (OCTET_BITS * index))
                    : 0xFFL;
            if (value < 0L || value > ceiling) {
                return null;
            }
            values[index] = value;
        }
        long address = values[parts.length - 1];
        for (int index = 0; index < parts.length - 1; index++) {
            address |= values[index] << (OCTET_BITS * (MAX_IPV4_PARTS - 1 - index));
        }
        byte[] octets = {
            (byte) (address >>> 24), (byte) (address >>> 16), (byte) (address >>> 8), (byte) address,
        };
        try {
            return InetAddress.getByAddress(octets);
        } catch (UnknownHostException impossible) {
            // Four bytes is always a valid IPv4 address; this branch exists only because the method declares it.
            Debug.logWarning("Four octets were rejected as an address, which cannot happen", MODULE);
            return null;
        }
    }

    /**
     * Parses one part of an IPv4 literal in decimal, octal or hexadecimal.
     *
     * @param part the part as written
     * @return its value, or {@code -1} when it is not a number in any of the three bases
     */
    private static long numericPart(String part) {
        try {
            if (part.startsWith("0x")) {
                return part.length() == 2 ? -1L : Long.parseLong(part.substring(2), 16);
            }
            if (part.length() > 1 && part.charAt(0) == '0') {
                return Long.parseLong(part.substring(1), 8);
            }
            return part.isEmpty() ? -1L : Long.parseLong(part, 10);
        } catch (NumberFormatException notANumber) {
            return -1L;
        }
    }

    /**
     * Installs, or removes, the host resolver this package's test supplies.
     *
     * <p>Package-private, and null in every deployment. It exists so the DNS-alias policy above can be
     * asserted without a name server: a test installs a resolver that answers a chosen name with a chosen
     * address, or that reports the name as unresolvable, and the endpoint check behaves exactly as it would
     * against a real resolver that said the same thing.
     *
     * @param resolver the resolver to use, or {@code null} to restore the platform resolver
     */
    static void installHostResolverForTesting(HostResolver resolver) {
        HOST_RESOLVER.set(resolver);
    }

    /**
     * Installs, or removes, the SDK construction this package's test supplies.
     *
     * <p>Package-private, and null in every deployment, so a deployed instance always builds a real
     * client from a real builder and authenticates through the real default credential chain.
     *
     * @param construction the construction to use, or {@code null} to restore the real SDK
     */
    static void installSdkConstructionForTesting(SdkConstruction construction) {
        SDK_CONSTRUCTION.set(construction);
    }

    /**
     * Returns the SDK construction in force: the one a test installed, or the real SDK.
     *
     * @return the construction to create this provider's client and credential provider with
     */
    private static SdkConstruction sdkConstruction() {
        SdkConstruction installed = SDK_CONSTRUCTION.get();
        return installed != null ? installed : REAL_SDK;
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
     * <p>The SDK failure itself is neither logged nor attached, for the reason given on
     * {@link #storeFailure}: its message can quote the request that produced it, and an absence report
     * travels back through the content-rendering path. Its sanitised description goes to the log with
     * the object key, which is all an operator needs to tell "nothing stored" apart from "asked the
     * wrong bucket".
     *
     * @param key the key as the caller supplied it
     * @param objectKey the prefixed object key that holds nothing, for the log
     * @param cause the SDK failure that reported it
     * @return the exception to throw
     */
    private FileNotFoundException absent(String key, String objectKey, SdkException cause) {
        Debug.logVerbose("The S3 content store holds no content under object [" + objectKey + "] in bucket ["
                + bucket + "]: " + redacted(cause), MODULE);
        return new FileNotFoundException("No content is stored under [" + key + "]");
    }

    /**
     * Translates an SDK failure into the contract's failure without disclosing where this deployment
     * keeps its content.
     *
     * <p>The thrown message is fixed text plus an opaque reference. It carries no bucket, no object
     * key and none of the store's own message, because an {@link IOException} raised while rendering
     * content can reach the rendered page. The reference is logged beside the bucket, the key and the
     * sanitised type, status, error code and request id, so an operator joins the report an end user
     * quotes to the failure that produced it.
     *
     * <p><strong>The SDK failure crosses no boundary at all.</strong> It is not attached as the cause
     * and it is not handed to {@code Debug} in any form, not even behind the verbose switch. Two
     * separate paths made that necessary. Attaching it meant the value travelled with the exception:
     * anything that logs a caught {@code IOException} with its stack trace, or reads
     * {@code getCause().getMessage()}, republishes whatever the SDK quoted - and an SDK message can
     * quote the request it was building, endpoint and signed headers included. Logging the object
     * itself did the same thing directly, because {@code Debug} writes the message and the whole stack
     * trace, which is the one route that bypasses the sanitising below. What is kept is exactly what
     * identifies a failure without describing it: the type, and the service fields where there are any.
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
        return new IOException("The content store could not " + operation + " the requested content."
                + " Reference [" + reference + "].");
    }

    /**
     * Describes an SDK failure using only fields that identify it without quoting it.
     *
     * <p>The type is always named, because it is the only diagnostic a client-side failure - an expired
     * deadline, a connection that could not be made - carries at all, and because a service failure is
     * easier to act on when the report says which kind it was. Nothing else of the failure is used: not
     * its message, not its cause and not its stack trace.
     *
     * <p>Declared over {@link RuntimeException} rather than over {@link SdkException} because the client
     * builder refuses an unusable region or override with an {@code IllegalArgumentException} as readily as
     * the SDK refuses one with an {@code SdkException}, and that refusal has to be described by exactly the
     * same rules. Everything below is reached through {@code instanceof}, so an argument that is an SDK
     * failure is described exactly as it was before.
     *
     * @param cause the failure, an SDK one or one the client builder raised
     * @return the failure's type, with its status, error code and request id where it carries them
     */
    private static String redacted(RuntimeException cause) {
        String type = "failure [" + cause.getClass().getSimpleName() + "]";
        if (!(cause instanceof SdkServiceException service)) {
            return type;
        }
        String errorCode = cause instanceof S3Exception s3 && s3.awsErrorDetails() != null
                ? s3.awsErrorDetails().errorCode()
                : "";
        return type + ", status [" + service.statusCode() + "], error-code [" + (errorCode == null ? "" : errorCode)
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
     * Reads one of the provider's overridable tunables.
     *
     * <p>Read through {@link ContentStoreFactory#propertyValue}, the one accessor this package reads an
     * overridable {@code content.store.*} value with, so that a value overridden through the
     * {@code SystemProperty} entity reaches this provider exactly as it reaches the factory.
     *
     * @param name the property name; must be a tunable, not one of
     *     {@link ContentStoreFactory#DEPLOYMENT_PROPERTIES}
     * @param delegator the delegator the value is read through; may be null, in which case only
     *     {@code content.properties} is consulted
     * @return the configured value, trimmed, or an empty string when it is not set
     */
    private static String property(String name, Delegator delegator) {
        return ContentStoreFactory.propertyValue(name, delegator);
    }

    /**
     * Reads one of the values that decide where this deployment's content is written and which
     * principal writes it.
     *
     * <p>Read through {@link ContentStoreFactory#deploymentValue}, which consults
     * {@code content.properties} alone. These are the values the container entry point validates and
     * renders from the environment, and honouring a {@code SystemProperty} row over them would let a
     * database row point a fleet at another bucket, another endpoint or another principal in a change
     * no start-up validation sees. See {@link ContentStoreFactory#DEPLOYMENT_PROPERTIES}.
     *
     * @param name the property name; must be one of {@link ContentStoreFactory#DEPLOYMENT_PROPERTIES}
     * @return the configured value, trimmed, or an empty string when it is not set
     */
    private static String deploymentValue(String name) {
        return ContentStoreFactory.deploymentValue(name);
    }
}
