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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.apache.ofbiz.base.util.GeneralException;
import org.junit.jupiter.api.Test;

/**
 * The safety bounds every {@link ContentStore} provider shares, and the form in which this package names an
 * identifier in a log line or an exception message.
 *
 * <p>Both concerns live in {@code ContentStoreSupport} because both have to be identical across providers, and
 * both are the kind of protection that is invisible until it is missing:
 * <ul>
 * <li>a whole-content {@code put} or {@code get} materialises the entire object in the heap, so without a hard
 *     ceiling a single request for a multi-gigabyte object would take the instance down rather than being
 *     refused - and refusal has to name the streaming alternative, otherwise the ceiling reads as a defect;</li>
 * <li>a known-length streaming write has to move exactly the declared number of bytes: consuming more would
 *     eat whatever the caller had queued behind the content, and accepting fewer would store a truncated object
 *     that reads back cleanly and is indistinguishable from intact content;</li>
 * <li>a content key names a party's document or an order attachment and arrives from request data, so it may
 *     neither be written verbatim into a log - which would publish the object layout of the store and let
 *     request data choose what a record contains - nor carry a control character into one.</li>
 * </ul>
 *
 * <p>Every expectation below is exercised against the production helpers themselves, with real byte arrays and
 * real streams. Nothing is mocked, and no storage backend, network or credential is involved.
 */
public final class ContentStoreSafetyBoundsTests {

    private static final String KEY = "uploads/party/PARTY-1/statement.pdf";
    private static final String OTHER_KEY = "uploads/party/PARTY-2/statement.pdf";
    private static final byte[] PAYLOAD = "the quick brown fox".getBytes(StandardCharsets.UTF_8);

    /** The committed default of {@code content.store.max.memory.bytes}, in bytes. */
    private static final long COMMITTED_CEILING = 20L * 1024L * 1024L;

    /** A property that is deliberately not declared anywhere, so its lookup must yield the caller's fallback. */
    private static final String UNDECLARED = "content.store.deliberately.not.declared";

    /** A declared property whose committed value is not a number at all. */
    private static final String NON_NUMERIC = "content.store.provider";

    /** A declared numeric property, committed as 3. */
    private static final String NUMERIC = "content.store.s3.max.attempts";

    /*
     * ---------------------------------------------------------------------------------------------
     * Resolving a bound
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void theInMemoryCeilingResolvesToTheCommittedDefault() {
        // The ceiling is the single number that decides whether a whole-content operation is allowed at all, so
        // the shipped configuration has to be what the providers actually enforce rather than a value the code
        // silently substitutes.
        assertEquals(COMMITTED_CEILING, ContentStoreSupport.maxInMemoryBytes(), "the enforced in-memory ceiling");
    }

    @Test
    public void anAbsentUnparsableOrOutOfRangeTuningValueFallsBackToItsDefault() {
        // Absent: nothing declared, so the caller's default stands.
        assertEquals(42L, ContentStoreSupport.boundedLongProperty(UNDECLARED, 42L, 1L, 100L),
                "an undeclared property must yield the caller's default");
        // Declared but not a number: a mistyped tuning value must not stop content being served.
        assertEquals(9L, ContentStoreSupport.boundedLongProperty(NON_NUMERIC, 9L, 1L, 100L),
                "a non-numeric value must yield the caller's default");
        // Declared, numeric, but outside the accepted range: refused in favour of the default rather than
        // clamped, because a clamped value is a third value the operator never asked for.
        assertEquals(7L, ContentStoreSupport.boundedLongProperty(NUMERIC, 7L, 1L, 2L),
                "a value above the accepted range must yield the caller's default");
        assertEquals(7L, ContentStoreSupport.boundedLongProperty(NUMERIC, 7L, 4L, 10L),
                "a value below the accepted range must yield the caller's default");
        // Declared, numeric and in range: honoured, which is what makes the whole mechanism worth having.
        assertEquals(3L, ContentStoreSupport.boundedLongProperty(NUMERIC, 7L, 1L, 10L),
                "a value inside the accepted range must be honoured");
        assertEquals(3, ContentStoreSupport.boundedIntProperty(NUMERIC, 7, 1, 10),
                "the int form must narrow the same resolved value");
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Enforcing the bound
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void aWholeContentWriteIsRefusedAboveTheCeilingAndTheStreamingFormIsNamed() {
        GeneralException refused = assertThrows(GeneralException.class, () ->
                ContentStoreSupport.requireStorableContent(KEY, new byte[9], 8L),
                "content longer than the ceiling must be refused");
        // Naming the alternative matters: a caller who only learns the operation is too large has no way to know
        // the same content is storable through the streaming form.
        assertTrue(refused.getMessage().contains("streaming"), "the refusal must name the streaming alternative: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("content.store.max.memory.bytes"),
                "the refusal must name the property that sets the ceiling: " + refused.getMessage());

        // The ceiling is inclusive, so content of exactly the permitted length is storable.
        assertDoesNotThrow(() -> ContentStoreSupport.requireStorableContent(KEY, new byte[8], 8L));
        assertThrows(GeneralException.class, () -> ContentStoreSupport.requireStorableContent(KEY, null, 8L),
                "null content is unusable input, not empty content");
    }

    @Test
    public void aWholeContentReadIsRefusedWhenTheKnownLengthAlreadyExceedsTheCeiling() {
        // Refused from the known length, before a single byte is transferred: that is the difference between
        // declining an over-large read and surviving one.
        GeneralException refused = assertThrows(GeneralException.class, () ->
                ContentStoreSupport.requireReadableInMemory(KEY, 9L, 8L), "an over-large read must be refused up front");
        assertTrue(refused.getMessage().contains("openStream"), "the refusal must name the streaming alternative: " + refused.getMessage());
        assertDoesNotThrow(() -> ContentStoreSupport.requireReadableInMemory(KEY, 8L, 8L));
    }

    @Test
    public void aBoundedReadAbandonsContentThatGrowsPastTheCeilingAndReturnsWhatFits() throws Exception {
        // A store that cannot report a length up front leaves the ceiling to be enforced as the bytes arrive.
        assertThrows(GeneralException.class, () ->
                ContentStoreSupport.readBounded(new ByteArrayInputStream(new byte[128 * 1024]), KEY, 8L),
                "a read that grows past the ceiling must be abandoned");

        assertArrayEquals(PAYLOAD, ContentStoreSupport.readBounded(new ByteArrayInputStream(PAYLOAD), KEY, PAYLOAD.length),
                "content exactly at the ceiling must be returned in full");
        assertArrayEquals(new byte[0], ContentStoreSupport.readBounded(new ByteArrayInputStream(new byte[0]), KEY, 8L),
                "empty content must read back as an empty array, never null");
    }

    @Test
    public void anExactTransferMovesTheDeclaredBytesAndNoMoreOfTheSource() throws Exception {
        InputStream source = new ByteArrayInputStream("HEADbody".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        ContentStoreSupport.transferExactly(source, sink, 4L, KEY);

        assertArrayEquals("HEAD".getBytes(StandardCharsets.UTF_8), sink.toByteArray(), "only the declared prefix may be moved");
        assertArrayEquals("body".getBytes(StandardCharsets.UTF_8), source.readAllBytes(),
                "the remainder of the source must be left for its owner");
    }

    @Test
    public void anExactTransferFailsRatherThanWritingAShortEntryWhenTheSourceEndsEarly() {
        InputStream source = new ByteArrayInputStream(PAYLOAD);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        IOException failed = assertThrows(IOException.class, () ->
                ContentStoreSupport.transferExactly(source, sink, PAYLOAD.length + 1L, KEY),
                "a source that ends early must fail rather than write a short entry");

        // Both counts are reported, because "ended after N of M declared bytes" is what lets an operator tell a
        // truncated upload apart from a store that rejected the write.
        assertTrue(failed.getMessage().contains(String.valueOf(PAYLOAD.length)),
                "the failure must report how much arrived: " + failed.getMessage());
        assertTrue(failed.getMessage().contains(String.valueOf(PAYLOAD.length + 1)),
                "the failure must report how much was declared: " + failed.getMessage());
    }

    @Test
    public void anUnusableStorageKeyOrStreamIsRefusedBeforeAnyStoreIsTouched() throws Exception {
        assertThrows(GeneralException.class, () -> ContentStoreSupport.requireUsableKey(null), "a null key is unusable");
        assertThrows(GeneralException.class, () -> ContentStoreSupport.requireUsableKey(""), "an empty key is unusable");
        assertSame(KEY, ContentStoreSupport.requireUsableKey(KEY), "a usable key must be returned unchanged, so the check can be inlined");

        assertThrows(GeneralException.class, () -> ContentStoreSupport.requireStorableStream(KEY, null, 0L), "a null stream is unusable");
        assertThrows(GeneralException.class, () ->
                ContentStoreSupport.requireStorableStream(KEY, new ByteArrayInputStream(PAYLOAD), -1L),
                "a negative declared length is unusable");
        assertDoesNotThrow(() -> ContentStoreSupport.requireStorableStream(KEY, new ByteArrayInputStream(new byte[0]), 0L));
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Naming an identifier safely
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void anOperatorConfiguredValueIsSanitisedAndLengthCappedBeforeItReachesAMessage() {
        String hostile = "provider\nWARN forged log record\u001b[2Jcleared";

        String rendered = ContentStoreSupport.describe(hostile);

        // Neither a newline nor a terminal escape may survive: the first forges a log record, the second rewrites
        // the terminal of whoever tails the log.
        for (char character : rendered.toCharArray()) {
            assertFalse(Character.isISOControl(character), "no control character may survive into a message: " + rendered);
        }
        assertTrue(rendered.contains("provider"), "the value must still be identifiable: " + rendered);

        String overlong = "x".repeat(500);
        String capped = ContentStoreSupport.describe(overlong);
        assertTrue(capped.length() < overlong.length(), "an overlong value must be capped so it cannot flood the log");
        assertTrue(capped.contains("500"), "a capped rendering must report the true length: " + capped);

        assertEquals("<null>", ContentStoreSupport.describe(null), "a null value must render as a sentinel, not as \"null\"");
        assertEquals("<empty>", ContentStoreSupport.describe(""), "an empty value must render as a sentinel");
    }

    @Test
    public void aContentIdentifierIsRedactedToAStableReferenceRatherThanBeingEchoed() {
        String reference = ContentStoreSupport.reference(KEY);

        // The key itself, and every path segment of it, must be absent: a log reader must not be able to
        // reconstruct the object layout of the store, and must not learn which party a document belongs to.
        assertFalse(reference.contains("statement"), "the key must not be echoed: " + reference);
        assertFalse(reference.contains("PARTY-1"), "no path segment of the key may be echoed: " + reference);
        assertTrue(reference.startsWith("ref:"), "a reference must be recognisable as one: " + reference);

        // Deterministic, so one failure can be correlated across records and across instances...
        assertEquals(reference, ContentStoreSupport.reference(KEY), "the same key must always produce the same reference");
        // ...and discriminating, so two objects are not confused for one another.
        assertNotEquals(reference, ContentStoreSupport.reference(OTHER_KEY), "different keys must produce different references");

        assertEquals("<null>", ContentStoreSupport.reference(null), "a null key must render as a sentinel");
        assertEquals("<empty>", ContentStoreSupport.reference(""), "an empty key must render as a sentinel");
    }
}
