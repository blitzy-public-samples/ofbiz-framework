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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.apache.ofbiz.base.util.GeneralException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * How {@link S3ContentStore} classifies a failure from the object store, and how it behaves once the store stops
 * answering at all.
 *
 * <p>Classification is where an object-storage provider does the most damage when it is wrong, because the
 * wrong answer is a plausible one. Every case below reduces to the same question - is this key genuinely
 * carrying no content, or is the store not the store we think it is? - and getting it wrong in the permissive
 * direction is silently destructive:
 * <ul>
 * <li>a deleted or mis-routed bucket answers 404 for every key. Reported as absence, a content read returns
 *     "nothing stored here" for the entire catalogue, and any caller that re-creates absent content on demand
 *     would then re-create all of it, against the wrong store;</li>
 * <li>a removal that reports a 404 the key cannot account for is not a successful removal. Telling a caller its
 *     content is gone when the store merely could not be addressed loses the only reference to that content;</li>
 * <li>an object store that has stopped answering must not be asked once per request for as long as the outage
 *     lasts, because every one of those requests occupies a request thread until its deadline expires.</li>
 * </ul>
 *
 * <p>Each expectation runs against the production provider with the SDK boundary replaced by
 * {@link InMemoryS3Client}, so the classification code under test is the deployed one. No credential is
 * resolved, no endpoint is contacted and no network is touched.
 */
public final class S3ContentStoreFailureClassificationTests {

    private static final String BUCKET = "ofbiz-content";
    private static final String KEY = "uploads/party/statement.pdf";
    private static final String ABSENT_KEY = "uploads/party/never-stored.pdf";
    private static final byte[] PAYLOAD = "stored in an object store".getBytes(StandardCharsets.UTF_8);

    /** The provider's committed breaker threshold, in consecutive failures. */
    private static final int BREAKER_THRESHOLD = 5;

    @Test
    public void anUnambiguousNoSuchKeyIsNormalisedToAbsenceWithoutProbingTheBucket() throws Exception {
        InMemoryS3Client client = new InMemoryS3Client(BUCKET).reportingAbsenceAs(InMemoryS3Client.Absence.NO_SUCH_KEY);
        ContentStore provider = new S3ContentStore(client, BUCKET);

        assertFalse(provider.exists(ABSENT_KEY), "a key the store names as missing is simply absent");
        assertThrows(FileNotFoundException.class, () -> provider.get(ABSENT_KEY), "and reads raise the documented absence signal");

        // No bucket probe was needed: the store already named the key, so there is nothing ambiguous to resolve
        // and no extra round trip to pay for on what is a perfectly ordinary cache miss.
        assertEquals(0, client.headBucketCalls(), "an unambiguous absence must not cost a bucket probe");
    }

    @Test
    public void aBareNotFoundIsClassifiedByProbingTheBucketAndIsAbsenceOnlyIfTheBucketAnswers() throws Exception {
        InMemoryS3Client client = new InMemoryS3Client(BUCKET).reportingAbsenceAs(InMemoryS3Client.Absence.BARE_NOT_FOUND);
        ContentStore provider = new S3ContentStore(client, BUCKET);

        // Some S3-compatible stores answer HEAD with a bare 404 that names nothing. It CAN mean the object is
        // missing, so it is resolved rather than guessed: the bucket is probed, and a bucket that answers settles
        // it as absence.
        assertFalse(provider.exists(ABSENT_KEY), "a bare 404 with a reachable bucket resolves to absence");
        assertEquals(1, client.headBucketCalls(), "the ambiguity must be resolved by probing the bucket");
    }

    @Test
    public void aBareNotFoundFromAnUnreachableBucketIsPropagatedRatherThanReportedAsAbsence() {
        InMemoryS3Client client = new InMemoryS3Client(BUCKET)
                .reportingAbsenceAs(InMemoryS3Client.Absence.BARE_NOT_FOUND)
                .withUnreachableBucket();
        ContentStore provider = new S3ContentStore(client, BUCKET);

        // This is the destructive case: every key answers 404 because the bucket is gone or the endpoint is
        // mis-routed. It must surface as a provider failure, never as "there is no content here".
        IOException propagated = assertBucketFault(() -> provider.exists(ABSENT_KEY),
                "an unattributable 404 from an unreachable bucket must not be reported as absence");
        assertTrue(propagated.getMessage().contains("not object absence"),
                "the failure must say what it is not: " + propagated.getMessage());
        assertEquals(1, client.headBucketCalls(), "the classification must have consulted the bucket");
    }

    @Test
    public void aMissingBucketIsNeverReportedAsMissingContent() {
        InMemoryS3Client client = new InMemoryS3Client(BUCKET).reportingAbsenceAs(InMemoryS3Client.Absence.NO_SUCH_BUCKET);
        ContentStore provider = new S3ContentStore(client, BUCKET);

        // The store named the BUCKET, not the key, so there is no ambiguity to resolve and nothing to probe.
        assertBucketFault(() -> provider.exists(ABSENT_KEY), "a missing bucket must not be reported as absence by exists");
        assertBucketFault(() -> provider.get(ABSENT_KEY), "nor by get");
        assertBucketFault(() -> provider.size(ABSENT_KEY), "nor by size");
        assertBucketFault(() -> provider.openStream(ABSENT_KEY), "nor by openStream");
        assertEquals(0, client.headBucketCalls(), "an explicitly named bucket failure needs no probe");
    }

    @Test
    public void removalStaysASuccessfulNoOpWhenTheStoreItselfReportsTheAbsence() throws Exception {
        InMemoryS3Client client = new InMemoryS3Client(BUCKET)
                .reportingAbsenceAs(InMemoryS3Client.Absence.NO_SUCH_KEY)
                .reportingAbsentDeleteAsFailure();
        ContentStore provider = new S3ContentStore(client, BUCKET);

        // Amazon S3 treats deleting an absent key as a success; a compatible store that reports the absence
        // instead must be normalised to that same successful no-op, so replayed clean-up stays safe either way.
        provider.delete(ABSENT_KEY);
        assertEquals(1, client.deleteCalls(), "the removal must have been attempted");
    }

    @Test
    public void removalOfAnAbsentObjectIsStillRefusedWhenTheStoreCannotBeAddressed() {
        InMemoryS3Client client = new InMemoryS3Client(BUCKET)
                .reportingAbsenceAs(InMemoryS3Client.Absence.BARE_NOT_FOUND)
                .reportingAbsentDeleteAsFailure()
                .withUnreachableBucket();
        ContentStore provider = new S3ContentStore(client, BUCKET);

        // The difference from the previous case is the whole point: an unattributable 404 during a removal must
        // not be reported as "already gone", because the caller would then drop its last reference to content
        // that is still there.
        assertBucketFault(() -> provider.delete(ABSENT_KEY),
                "an unattributable failure during removal must not be reported as success");
    }

    /**
     * Asserts an operation fails with the SPI's store-failure signal rather than with its absence signal.
     *
     * <p>A bucket that cannot be addressed is a store that did not serve the request, not a provider whose
     * configuration is incomplete, so the SPI reports it as an {@link IOException} - the same signal every other
     * shape of the same fault arrives as, whether the store named the bucket in a typed exception, in an error
     * code, or not at all. A caller cannot choose which shape the store sends, so all of them have to be
     * reported identically.
     *
     * <p>The EXACT class is asserted, not the type, because {@link FileNotFoundException} is itself an
     * {@link IOException}: asserting the type alone would be satisfied by the very absence answer every one of
     * these cases exists to forbid.
     *
     * @param operation the provider call that must fail
     * @param message what the failure means, used for both assertions
     * @return the failure, so a caller can assert on its message
     */
    private static IOException assertBucketFault(Executable operation, String message) {
        IOException failure = assertThrows(IOException.class, operation, message);
        assertEquals(IOException.class, failure.getClass(), message + " - and absence is not what it is");
        return failure;
    }

    @Test
    public void sustainedFailureOpensTheBreakerAndShedsFurtherCallsWithoutReachingTheStore() {
        InMemoryS3Client client = new InMemoryS3Client(BUCKET)
                .failEveryCallWith(S3Exception.builder().statusCode(500).message("Internal Error").build());
        ContentStore provider = new S3ContentStore(client, BUCKET);

        for (int attempt = 1; attempt <= BREAKER_THRESHOLD; attempt++) {
            assertThrows(Exception.class, () -> provider.exists(KEY), "every attempt against a failing store must fail");
        }
        int callsWhenBreakerOpened = client.headObjectCalls();
        assertEquals(BREAKER_THRESHOLD, callsWhenBreakerOpened, "each attempt up to the threshold must have reached the store");

        // Past the threshold the provider stops asking. That is what keeps an object-store outage from occupying
        // a request thread per request for as long as it lasts.
        GeneralException shed = assertThrows(GeneralException.class, () -> provider.exists(KEY),
                "once the breaker is open the call must be refused rather than attempted");
        assertTrue(shed.getMessage().contains("breaker is open"), "the refusal must say why: " + shed.getMessage());
        assertEquals(callsWhenBreakerOpened, client.headObjectCalls(), "a shed call must not reach the store at all");
    }

    @Test
    public void anAbsentObjectDoesNotCountTowardsTheBreaker() throws Exception {
        InMemoryS3Client client = new InMemoryS3Client(BUCKET).reportingAbsenceAs(InMemoryS3Client.Absence.NO_SUCH_KEY);
        ContentStore provider = new S3ContentStore(client, BUCKET);
        provider.put(KEY, PAYLOAD);

        // A store that answers "nothing is stored here" has answered correctly. Counting that as a failure would
        // let ordinary cache misses trip the breaker and shed traffic against a perfectly healthy store.
        for (int attempt = 0; attempt <= BREAKER_THRESHOLD; attempt++) {
            assertFalse(provider.exists(ABSENT_KEY), "an absent key must stay absent however often it is probed");
        }
        assertTrue(provider.exists(KEY), "the store must still be usable after any number of absent-key probes");
    }
}
