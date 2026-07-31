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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.apache.ofbiz.base.util.GeneralException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * How a configured value becomes a storage provider, and which configurations are refused outright.
 *
 * <p>Provider selection is the one decision in this package that every content read and write passes through, and
 * the two ways it can go wrong are both silent:
 * <ul>
 * <li>degrading an unusable selection to database mode. An operator who deploys with a mistyped provider name, an
 *     absent client library or a one-sided credential pair would then watch the instance start cleanly and serve
 *     content from the database, while every upload the deployment was provisioned to put in an object store went
 *     somewhere else entirely. Failing closed is what turns that into a visible, diagnosable error;</li>
 * <li>constructing more than one provider for the same configuration. The losing instance of a race would hold an
 *     HTTP connection pool that nothing can ever reach again, let alone close.</li>
 * </ul>
 *
 * <p>The S3 configuration rejections are asserted here rather than against a client because that is exactly where
 * they belong: a one-sided credential pair, an endpoint carrying user information and a plaintext endpoint must all
 * be refused BEFORE a client exists, so no request is ever issued under a principal or over a transport the
 * operator did not intend. Nothing here builds a client, resolves a credential or reaches a network.
 *
 * <p>Selection is cached in static state by design - it is resolved once per configured value - so every test
 * clears that cache before and after itself and leaves nothing behind for the rest of the suite.
 */
public final class ContentStoreSelectionTests {

    private static final String ENDPOINT_PROPERTY = "content.store.s3.endpoint";
    private static final String HOST = "objects.example.com";

    @BeforeEach
    public void resetSelectionBefore() {
        ContentStoreFactory.clearCache();
    }

    @AfterEach
    public void resetSelectionAfter() {
        ContentStoreFactory.clearCache();
    }

    /*
     * Selecting a provider
     */

    @Test
    public void configuredDatabaseModeSelectsNoProviderAtAll() throws Exception {
        // No provider at all, rather than a provider that happens to do nothing: that is what leaves the
        // pre-existing DataResource database-storage path completely untouched and the object-storage code -
        // client library included - entirely inert in an unmodified checkout.
        assertNull(ContentStoreFactory.resolve("database"), "explicit database mode must select no provider");
        ContentStoreFactory.clearCache();
        assertNull(ContentStoreFactory.resolve(""), "an unconfigured value must select no provider");
        ContentStoreFactory.clearCache();
        assertNull(ContentStoreFactory.resolve(null), "an absent value must select no provider");
        ContentStoreFactory.clearCache();
        assertNull(ContentStoreFactory.resolve("  DataBase  "), "the value must be trimmed and matched without regard to case");
    }

    @Test
    public void anUnrecognisedProviderFailsClosedRatherThanDegradingToDatabaseMode() {
        GeneralException refused = assertThrows(GeneralException.class, () -> ContentStoreFactory.resolve("s4"),
                "an unrecognised provider must not be honoured");

        // The message has to name the offending value and the accepted set, because the operator's only other
        // signal is content quietly going to the wrong backend.
        assertTrue(refused.getMessage().contains("s4"), "the refusal must name the configured value: " + refused.getMessage());
        for (String accepted : new String[] {"database", "filesystem", "s3"}) {
            assertTrue(refused.getMessage().contains(accepted), "the refusal must name the accepted value " + accepted);
        }

        // And it must keep failing. A cached failure that decayed into database mode on the second call would be
        // worse than the original defect, because it would be intermittent.
        assertThrows(GeneralException.class, () -> ContentStoreFactory.resolve("s4"), "a refused selection must stay refused");
    }

    @Test
    public void aFilesystemSelectionYieldsTheFilesystemProviderAndTheUploadLocationItImplies() throws Exception {
        ContentStore selected = ContentStoreFactory.resolve("filesystem");

        assertTrue(selected instanceof FileSystemContentStore, "the filesystem value must select the filesystem provider");
        // The worker seam asks the active provider where uploads belong, so a provider that can answer has to
        // announce it structurally rather than by being recognised by type. Both capabilities are required,
        // not just the first: the location is written to directly by frozen services that open a
        // FileOutputStream on it, so it is only a usable answer from a provider that also backs that very
        // local file. See ContentStoreFactory.resolveUploadPath.
        assertTrue(selected instanceof ContentUploadLocation, "the filesystem provider must report an upload location");
        assertTrue(selected instanceof LocalContentStore,
                "the filesystem provider's upload location is the file it stores, which is what makes the "
                        + "location it reports a usable one");
    }

    @Test
    public void anObjectStoreSelectionYieldsAProviderThatAnnouncesNoUploadLocationAtAll() throws Exception {
        ContentStore selected = ContentStoreFactory.resolve("s3");

        assertTrue(selected instanceof S3ContentStore, "the s3 value must select the object-store provider");
        // A bucket has no writable local path, and a key prefix is not one. Announcing the capability and
        // answering with a prefix is the defect this asserts against: the frozen callers treat the answer as
        // a directory to open a FileOutputStream in, so the upload would have been created beside the process
        // working directory and never published to the bucket at all. An upload bound for this provider is
        // staged by ContentStoreFactory.uploadStagingPath and published on commit instead.
        assertFalse(selected instanceof ContentUploadLocation,
                "the object-store provider must announce no upload location: it has no writable local path to "
                        + "offer, and offering one would send the upload to local disk instead of the bucket");
        assertFalse(selected instanceof LocalContentStore,
                "the object-store provider backs no local file, so it must not claim to");
    }

    @Test
    public void oneProviderIsConstructedPerConfiguredValueAndAChangedValueReplacesIt() throws Exception {
        ContentStore first = ContentStoreFactory.resolve("filesystem");
        ContentStore second = ContentStoreFactory.resolve("filesystem");

        // Same value, same instance: a second construction would leave the first unreachable and unclosed.
        assertSame(first, second, "an unchanged configured value must reuse the resolved provider");

        // A changed value resolves afresh; the provider it replaces is closed by the factory as it goes.
        assertNotSame(first, ContentStoreFactory.resolve("s3"), "a changed configured value must resolve a new provider");
    }

    /*
     * Refusing an unusable object-store configuration, before any client exists
     */

    @Test
    public void aOneSidedStaticCredentialPairIsRefusedInsteadOfFallingBackToTheDefaultChain() {
        // Falling back would authenticate as whatever ambient instance or container identity happens to be
        // available - a different principal, with potentially different permissions on potentially different
        // data - which is precisely the kind of substitution that must never happen implicitly.
        assertThrows(GeneralException.class, () -> S3ContentStore.requireCredentialPair("configured-id", ""),
                "an access key id without a secret must be refused");
        assertThrows(GeneralException.class, () -> S3ContentStore.requireCredentialPair("configured-id", null),
                "an access key id without a secret must be refused however the secret is absent");
        assertThrows(GeneralException.class, () -> S3ContentStore.requireCredentialPair("", "configured-secret"),
                "a secret without an access key id must be refused");
    }

    @Test
    public void aCompletePairSelectsStaticCredentialsAndNeitherSelectsTheDefaultChain() throws Exception {
        assertTrue(S3ContentStore.requireCredentialPair("configured-id", "configured-secret"),
                "a complete pair must select static credentials");
        assertFalse(S3ContentStore.requireCredentialPair("", ""), "two blank values must select the default credential chain");
        assertFalse(S3ContentStore.requireCredentialPair(null, null), "two absent values must select the default credential chain");
    }

    @Test
    public void anEndpointCarryingUserInformationIsRefusedWithoutBeingEchoed() {
        GeneralException refused = assertThrows(GeneralException.class, () ->
                S3ContentStore.validatedEndpoint("https://configured-id:configured-secret@" + HOST, false),
                "an endpoint carrying user information must be refused");

        // The value is a credential, so the refusal names the PROPERTY and never the value - otherwise the
        // rejection itself would write the secret into the log it was meant to keep it out of.
        assertTrue(refused.getMessage().contains(ENDPOINT_PROPERTY), "the refusal must name the property: " + refused.getMessage());
        assertFalse(refused.getMessage().contains("configured-secret"), "the refusal must not echo the credential: " + refused.getMessage());
        assertFalse(refused.getMessage().contains("configured-id"), "the refusal must not echo the credential: " + refused.getMessage());
    }

    @Test
    public void aPlaintextEndpointIsRefusedUnlessItHasBeenExplicitlyAccepted() throws Exception {
        assertThrows(GeneralException.class, () -> S3ContentStore.validatedEndpoint("http://" + HOST, false),
                "a plaintext endpoint must be refused by default, because static credentials travel with every request");

        // Permitted only as an explicit, auditable operator decision - a trusted in-cluster store, for instance.
        assertEquals(HOST, S3ContentStore.validatedEndpoint("http://" + HOST, true).getHost(),
                "an explicitly accepted plaintext endpoint must be honoured");
    }

    @Test
    public void anEndpointThatIsNotAnAbsoluteHttpUriNamingAHostIsRefused() throws Exception {
        for (String unusable : new String[] {HOST, "/objects", "ftp://" + HOST, "https:///bucket", "not a uri at all"}) {
            assertThrows(GeneralException.class, () -> S3ContentStore.validatedEndpoint(unusable, true),
                    "an unusable endpoint must be refused: " + unusable);
        }

        // A usable endpoint is accepted, and surrounding whitespace - which a copied environment value routinely
        // carries - is not what makes it unusable.
        URI accepted = S3ContentStore.validatedEndpoint("  https://" + HOST + ":9000  ", false);
        assertEquals(HOST, accepted.getHost(), "the validated endpoint must retain its host");
        assertEquals(9000, accepted.getPort(), "the validated endpoint must retain its port");
        assertEquals("https", accepted.getScheme(), "the validated endpoint must retain its scheme");
    }
}
