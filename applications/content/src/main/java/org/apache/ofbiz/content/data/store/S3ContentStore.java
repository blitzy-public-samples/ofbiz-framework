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
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
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
 * contradiction and is refused.
 *
 * <p><strong>Endpoint safety.</strong> Every object request carries this deployment's credential,
 * so the endpoint is validated before a client is built: it must be an absolute {@code http} or
 * {@code https} URI with a host, no user information and no query or fragment, and an instance
 * metadata address is refused outright in every configuration because a request sent there would
 * hand out the instance's own role credentials. A plaintext {@code http} endpoint is accepted with
 * a warning, for a local development store; the container entry point refuses it in the deployed
 * profile, which is where that policy belongs.
 *
 * <p><strong>Exception translation.</strong> No SDK type escapes the {@link ContentStore}
 * contract. A missing object is a {@link FileNotFoundException} from {@code get}/{@code openStream}
 * and {@code false} from {@code exists}; every other store failure is an {@link IOException}
 * carrying the SDK failure as its cause.
 *
 * <p>Thread safe: {@code S3Client} is thread safe and the instance holds only it and the bucket
 * name.
 */
public final class S3ContentStore implements ContentStore {

    private static final String MODULE = S3ContentStore.class.getName();

    /** The resource the provider's configuration is read from. */
    private static final String PROPERTY_RESOURCE = "content";

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

    /**
     * Constructs the provider from {@code content.properties}, building the client the
     * configuration describes.
     *
     * <p>This is the constructor {@link ContentStoreFactory} uses. It is the only place the AWS
     * SDK is named from a configuration-driven path, which is what keeps the dependency inert in
     * every deployment that does not select this provider.
     *
     * @throws GeneralException if the configuration is incomplete or contradictory - no bucket, no
     *     region, an unusable endpoint, or exactly one of the two credential properties
     */
    public S3ContentStore() throws GeneralException {
        String configuredBucket = property("content.store.s3.bucket");
        String region = property("content.store.s3.region");
        String endpoint = property("content.store.s3.endpoint");
        String accessKeyId = property("content.store.s3.access.key.id");
        String secretAccessKey = property("content.store.s3.secret.access.key");
        boolean pathStyle = "true".equalsIgnoreCase(property("content.store.s3.path.style"));

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

        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .forcePathStyle(pathStyle);
        if (UtilValidate.isNotEmpty(endpoint)) {
            builder.endpointOverride(validatedEndpoint(endpoint));
        }
        if (UtilValidate.isNotEmpty(accessKeyId)) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
        }
        try {
            this.s3Client = builder.build();
        } catch (SdkException e) {
            throw new GeneralException("The S3 content store client could not be built from"
                    + " content.store.s3.* : " + e.getMessage(), e);
        }
        this.bucket = configuredBucket;
        Debug.logInfo("Content storage provider s3 initialised with endpoint-override ["
                + UtilValidate.isNotEmpty(endpoint) + "], path-style [" + pathStyle + "], static-credentials ["
                + UtilValidate.isNotEmpty(accessKeyId) + "]", MODULE);
    }

    /**
     * Package-private test seam: constructs a store around a pre-built client so that the provider
     * can be unit tested with a mocked {@code S3Client}, with no AWS configuration, no network
     * access and no credential resolution.
     *
     * @param s3Client the client every operation is issued through
     * @param bucket the bucket every request names
     */
    S3ContentStore(S3Client s3Client, String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
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
        try {
            return s3Client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(objectKey).build()).asByteArray();
        } catch (NoSuchKeyException absent) {
            throw absent(objectKey, absent);
        } catch (S3Exception e) {
            if (isNotFound(e)) {
                throw absent(objectKey, e);
            }
            throw storeFailure("read", objectKey, e);
        } catch (SdkException e) {
            throw storeFailure("read", objectKey, e);
        }
    }

    @Override
    public InputStream openStream(String key) throws GeneralException, IOException {
        String objectKey = objectKey(key);
        try {
            return s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(objectKey).build());
        } catch (NoSuchKeyException absent) {
            throw absent(objectKey, absent);
        } catch (S3Exception e) {
            if (isNotFound(e)) {
                throw absent(objectKey, e);
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
            Debug.logVerbose(absent, "The S3 content store holds nothing under [" + objectKey + "]", MODULE);
            return false;
        } catch (S3Exception e) {
            if (isNotFound(e)) {
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
            // Idempotent by contract, and S3 itself reports a delete of an absent object as success.
            Debug.logVerbose(absent, "The S3 content store already holds nothing under [" + objectKey + "]", MODULE);
        } catch (S3Exception e) {
            if (!isNotFound(e)) {
                throw storeFailure("remove", objectKey, e);
            }
            Debug.logVerbose(e, "The S3 content store already holds nothing under [" + objectKey + "]", MODULE);
        } catch (SdkException e) {
            throw storeFailure("remove", objectKey, e);
        }
    }

    /**
     * Validates and normalises a storage key into an object key.
     *
     * @param key the provider-relative storage key
     * @return the object key to name in a request
     * @throws GeneralException if the key is null, empty, carries a control character, is absolute
     *     or contains a {@code ..} component
     */
    private String objectKey(String key) throws GeneralException {
        if (UtilValidate.isEmpty(key)) {
            throw new GeneralException("A content store key must not be empty");
        }
        for (int index = 0; index < key.length(); index++) {
            if (Character.isISOControl(key.charAt(index))) {
                throw new GeneralException("A content store key must not contain a control character");
            }
        }
        if (key.startsWith("/")) {
            throw new GeneralException("A content store key must be relative: [" + key + "]");
        }
        for (String segment : key.split("/")) {
            if ("..".equals(segment)) {
                throw new GeneralException("A content store key must not contain a '..' component: [" + key + "]");
            }
        }
        return key;
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
            // The value is never echoed: the case this exists to catch is an endpoint carrying a credential.
            throw new GeneralException("content.store.s3.endpoint is not a valid URI", e);
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
     * Reports whether an S3 failure is the store saying the object or bucket is not there.
     *
     * @param e the SDK failure
     * @return {@code true} when it is a 404, which several S3-compatible stores report in place of
     *     {@code NoSuchKey}
     */
    private boolean isNotFound(S3Exception e) {
        return e.statusCode() == 404;
    }

    /**
     * Builds the absence report the contract requires, so that a caller never has to know an SDK
     * type to recognise it.
     *
     * @param objectKey the key that holds nothing
     * @param cause the SDK failure that reported it
     * @return the exception to throw
     */
    private FileNotFoundException absent(String objectKey, SdkException cause) {
        FileNotFoundException absent = new FileNotFoundException("The S3 content store holds no content under ["
                + objectKey + "]");
        absent.initCause(cause);
        return absent;
    }

    /**
     * Translates an SDK failure into the contract's failure, keeping the cause available and never
     * letting an SDK type escape.
     *
     * @param operation what was being attempted, for the diagnostic
     * @param objectKey the key the request named
     * @param cause the SDK failure
     * @return the exception to throw
     */
    private IOException storeFailure(String operation, String objectKey, SdkException cause) {
        return new IOException("The S3 content store could not " + operation + " content under [" + objectKey
                + "] in bucket [" + bucket + "]: " + cause.getMessage(), cause);
    }

    /**
     * Reads one of the provider's configuration properties.
     *
     * @param name the property name
     * @return the configured value, trimmed, or an empty string when it is not set
     */
    private static String property(String name) {
        return UtilProperties.getPropertyValue(PROPERTY_RESOURCE, name);
    }
}
