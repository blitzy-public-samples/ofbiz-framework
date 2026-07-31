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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * An in-memory {@link S3Client} that stands in for a real object store in unit tests.
 *
 * <p>This is a FAKE rather than a mock, and the distinction is the whole point. A mocked {@code S3Client}
 * returns whatever a test told it to return, so asserting on it only restates the stubbing; this one actually
 * stores and serves bytes, honours the declared content length, and answers an absent key the way an object
 * store does. {@code S3ContentStore} is therefore exercised as a real client would exercise it - including the
 * parts that only show up under real semantics, such as a declared length that the body does not satisfy - while
 * no credential is resolved, no endpoint is contacted and no network is touched.
 *
 * <p>The failure surface is configurable, because the interesting behaviour of the provider is how it
 * CLASSIFIES a failure rather than how it succeeds:
 * <ul>
 * <li>{@link Absence} chooses how an absent key is reported: as the unambiguous {@code NoSuchKey}, as a bare
 *     404 that names nothing, or as {@code NoSuchBucket}. Amazon S3 and the S3-compatible stores genuinely
 *     differ here, and conflating the three is what lets a deleted bucket read as missing content;</li>
 * <li>{@link #withUnreachableBucket()} makes the bucket probe fail, which is what turns a bare 404 from
 *     "the object is gone" into "the store is not the one we think it is";</li>
 * <li>{@link #failEveryCallWith(RuntimeException)} makes every operation fail, so the provider's breaker can be
 *     driven deterministically instead of by timing.</li>
 * </ul>
 *
 * <p>Call counters are exposed so a test can assert that an operation reached the client - or, in the case of a
 * shed request, that it did NOT.
 */
final class InMemoryS3Client implements S3Client {

    /** How this store reports that a key resolves to no object. */
    enum Absence {
        /** The store names the key as missing, which is the only unambiguous signal. */
        NO_SUCH_KEY,
        /** The store answers 404 without naming anything, as some S3-compatible stores do for HEAD. */
        BARE_NOT_FOUND,
        /** The store reports the bucket as missing, which is not about the key at all. */
        NO_SUCH_BUCKET
    }

    /** The status code every not-found reply carries, whichever of the three styles reports it. */
    private static final int HTTP_NOT_FOUND = 404;

    private final Map<String, byte[]> objects = new LinkedHashMap<>();
    private final String bucket;

    private Absence absence = Absence.NO_SUCH_KEY;
    private boolean bucketReachable = true;
    private boolean reportAbsentDeleteAsFailure;
    private RuntimeException everyCallFailure;
    private long reportedLength = -1L;
    private boolean closed;

    private int putCalls;
    private int getCalls;
    private int headObjectCalls;
    private int deleteCalls;
    private int headBucketCalls;

    InMemoryS3Client(String bucket) {
        this.bucket = bucket;
    }

    /*
     * Configuration
     */

    /**
     * Chooses how an absent key is reported.
     *
     * @param style the reporting style to use
     * @return this client, for chaining
     */
    InMemoryS3Client reportingAbsenceAs(Absence style) {
        this.absence = style;
        return this;
    }

    /**
     * Makes the bucket probe fail, as it would when the bucket is gone or the endpoint is mis-routed.
     *
     * @return this client, for chaining
     */
    InMemoryS3Client withUnreachableBucket() {
        this.bucketReachable = false;
        return this;
    }

    /**
     * Makes the removal of an absent key report the absence rather than succeeding silently.
     *
     * @return this client, for chaining
     */
    InMemoryS3Client reportingAbsentDeleteAsFailure() {
        this.reportAbsentDeleteAsFailure = true;
        return this;
    }

    /**
     * Makes {@code HeadObject} report the supplied length regardless of what is stored, so that an object too
     * large to allocate in a unit test can still be presented to the provider.
     *
     * @param length the length to report
     * @return this client, for chaining
     */
    InMemoryS3Client reportingLengthAs(long length) {
        this.reportedLength = length;
        return this;
    }

    /**
     * Makes every operation fail with the supplied error, so a sustained outage can be simulated.
     *
     * @param failure the error every operation raises
     * @return this client, for chaining
     */
    InMemoryS3Client failEveryCallWith(RuntimeException failure) {
        this.everyCallFailure = failure;
        return this;
    }

    /** @return {@code true} once {@link #close()} has been called */
    boolean isClosed() {
        return closed;
    }

    /** @return the number of {@code HeadObject} calls received */
    int headObjectCalls() {
        return headObjectCalls;
    }

    /** @return the number of {@code HeadBucket} calls received */
    int headBucketCalls() {
        return headBucketCalls;
    }

    /** @return the number of {@code PutObject} calls received */
    int putCalls() {
        return putCalls;
    }

    /** @return the number of {@code GetObject} calls received */
    int getCalls() {
        return getCalls;
    }

    /** @return the number of {@code DeleteObject} calls received */
    int deleteCalls() {
        return deleteCalls;
    }

    /*
     * The three accessors below reach the stored objects without going through the client surface, and
     * deliberately leave the call counters alone. They are what lets a test state its expectation about the
     * store's contents rather than about the provider's own report of them: seeding directly is how "the
     * provider read what was already there" is asserted, and reading directly is how "the provider really
     * wrote it" is asserted, neither of which is evidence if the provider is the one being asked.
     */

    /**
     * Places an object as though some other instance had written it.
     *
     * @param key the object key
     * @param content the bytes to hold
     */
    void seed(String key, byte[] content) {
        objects.put(key, content.clone());
    }

    /**
     * @param key the object key
     * @return the bytes held for the key, or null when nothing is held
     */
    byte[] stored(String key) {
        byte[] held = objects.get(key);
        return held == null ? null : held.clone();
    }

    /**
     * @param key the object key
     * @return {@code true} when an object is held for the key
     */
    boolean holds(String key) {
        return objects.containsKey(key);
    }

    /*
     * The client surface the provider uses
     */

    @Override
    public String serviceName() {
        return S3Client.SERVICE_NAME;
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public PutObjectResponse putObject(PutObjectRequest request, RequestBody body) {
        putCalls++;
        refuseWhenFailing();
        requireBucket(request.bucket());
        long declared = request.contentLength() == null ? -1L : request.contentLength();
        if (declared < 0) {
            // a real store needs the length up front for a single-request upload
            throw SdkClientException.create("PutObject requires a content length");
        }
        byte[] stored = readExactly(body.contentStreamProvider().newStream(), declared);
        objects.put(request.key(), stored);
        return PutObjectResponse.builder().build();
    }

    @Override
    public ResponseInputStream<GetObjectResponse> getObject(GetObjectRequest request) {
        getCalls++;
        refuseWhenFailing();
        requireBucket(request.bucket());
        byte[] stored = objects.get(request.key());
        if (stored == null) {
            throw absent();
        }
        GetObjectResponse response = GetObjectResponse.builder().contentLength((long) stored.length).build();
        // a fresh stream per call, exactly as a real GetObject yields
        return new ResponseInputStream<>(response, AbortableInputStream.create(new ByteArrayInputStream(stored)));
    }

    @Override
    public HeadObjectResponse headObject(HeadObjectRequest request) {
        headObjectCalls++;
        refuseWhenFailing();
        requireBucket(request.bucket());
        byte[] stored = objects.get(request.key());
        if (stored == null) {
            throw absent();
        }
        long length = reportedLength < 0L ? stored.length : reportedLength;
        return HeadObjectResponse.builder().contentLength(length).build();
    }

    @Override
    public DeleteObjectResponse deleteObject(DeleteObjectRequest request) {
        deleteCalls++;
        refuseWhenFailing();
        requireBucket(request.bucket());
        if (objects.remove(request.key()) == null && reportAbsentDeleteAsFailure) {
            throw absent();
        }
        return DeleteObjectResponse.builder().build();
    }

    @Override
    public HeadBucketResponse headBucket(HeadBucketRequest request) {
        headBucketCalls++;
        requireBucket(request.bucket());
        if (!bucketReachable) {
            throw NoSuchBucketException.builder().statusCode(HTTP_NOT_FOUND).message("The specified bucket does not exist").build();
        }
        return HeadBucketResponse.builder().build();
    }

    /*
     * Helpers
     */

    private void refuseWhenFailing() {
        if (everyCallFailure != null) {
            throw everyCallFailure;
        }
    }

    private void requireBucket(String requested) {
        if (!bucket.equals(requested)) {
            throw NoSuchBucketException.builder().statusCode(HTTP_NOT_FOUND).message("The specified bucket does not exist").build();
        }
    }

    private S3Exception absent() {
        switch (absence) {
        case BARE_NOT_FOUND:
            // S3Exception's builder is not narrowed to its own type the way the modelled subclasses' builders
            // are, so the declared return is the AWS base exception and the cast is what the SDK requires to
            // produce the bare, unmodelled 404 a compatible store answers a HEAD or DELETE with.
            return (S3Exception) S3Exception.builder().statusCode(HTTP_NOT_FOUND).message("Not Found").build();
        case NO_SUCH_BUCKET:
            return NoSuchBucketException.builder().statusCode(HTTP_NOT_FOUND).message("The specified bucket does not exist").build();
        default:
            return NoSuchKeyException.builder().statusCode(HTTP_NOT_FOUND).message("The specified key does not exist").build();
        }
    }

    /**
     * Reads exactly the declared number of bytes, failing the way a real store fails a body that is shorter than
     * the {@code Content-Length} it was promised.
     *
     * @param body the request body stream
     * @param declared the number of bytes the request declared
     * @return the bytes the store would have persisted
     */
    private static byte[] readExactly(InputStream body, long declared) {
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long remaining = declared;
        try {
            while (remaining > 0) {
                int read = body.read(chunk, 0, (int) Math.min(chunk.length, remaining));
                if (read < 0) {
                    throw SdkClientException.create("The request body ended after " + (declared - remaining)
                            + " of the declared " + declared + " bytes");
                }
                received.write(chunk, 0, read);
                remaining -= read;
            }
        } catch (IOException e) {
            throw SdkClientException.create("The request body could not be read", e);
        }
        return received.toByteArray();
    }
}
