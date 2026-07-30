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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.ofbiz.base.util.UtilProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link ContentStoreUtil}, the two safety mechanisms every provider in this package leans on.
 *
 * <p>Both mechanisms are the kind that look like formatting and logging conveniences and are not. They are
 * covered here, directly and exhaustively, rather than only through the providers, because a provider test
 * shows that <em>one</em> message is safe while these tests show that the function producing every such
 * message cannot be made to produce an unsafe one.
 *
 * <p><strong>Safe diagnostics.</strong> {@link ContentStoreUtil#reference(String)} must be stable - the same
 * value always yields the same identifier, or an operator cannot correlate two log lines about one object -
 * one-way, and composed only of characters that cannot forge a log record.
 * {@link ContentStoreUtil#describe(String)} must echo a configured value while that is provably harmless, so
 * an operator can be told about a typo, and must stop echoing at exactly the stated boundary.
 *
 * <p><strong>The bounded whole-content read.</strong> {@link ContentStoreUtil#readWithin} is the only thing
 * standing between a single oversized object and the heap of whichever instance in a load-balanced fleet
 * happened to receive that request. The ceiling therefore has to hold against a stream that reports nothing
 * about its own size, and {@link ContentStoreUtil#maxWholeReadBytes()} has to stay in force rather than be
 * switched off by a configuration mistake.
 */
public final class ContentStoreUtilTests {

    private static final String CONTENT_RESOURCE = "content";
    private static final String MAX_GET_BYTES_PROPERTY = "content.store.max.get.bytes";

    /** The ceiling the class falls back to whenever the property yields nothing usable: 32 MiB. */
    private static final long COMMITTED_DEFAULT_CEILING = 33_554_432L;

    /** The longest configured value {@code describe} echoes rather than replacing with a reference. */
    private static final int ECHO_LENGTH_BOUNDARY = 48;

    private final Map<String, String> propertySnapshot = new LinkedHashMap<>();

    @AfterEach
    public void restoreEveryPropertyThisSuiteChanged() {
        propertySnapshot.forEach((name, previous) -> UtilProperties.setPropertyValueInMemory(CONTENT_RESOURCE, name, previous));
        propertySnapshot.clear();
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * reference: stable, one-way, and incapable of forging a log record
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void theSameValueAlwaysYieldsTheSameReferenceAndDifferentValuesDoNotCollide() {
        // Stability is what makes the whole scheme usable: an operator who suspects a particular key confirms it
        // by digesting that key themselves, and two log lines about one object have to carry one identifier.
        assertEquals(ContentStoreUtil.reference("runtime/uploads/1699999999999/invoice.pdf"),
                ContentStoreUtil.reference("runtime/uploads/1699999999999/invoice.pdf"),
                "one value must always render as one reference");

        Set<String> distinct = new LinkedHashSet<>();
        for (String value : List.of("a", "b", "runtime/uploads/a.bin", "runtime/uploads/b.bin", "s3", "s4", "S3")) {
            assertTrue(distinct.add(ContentStoreUtil.reference(value)), "values must not share a reference: " + value);
        }
        assertNotEquals(ContentStoreUtil.reference("s3"), ContentStoreUtil.reference("s3 "),
                "a reference must distinguish values a human would confuse, since that is what it is for");
    }

    @Test
    public void aReferenceIsOnlyEverHexadecimalSoNoInputCanForgeALogRecordThroughIt() {
        // Every one of these is an attempt to end the log line and start a new one, to drive the terminal that
        // renders the log, or to truncate what follows. None of them can survive a one-way hexadecimal digest,
        // and asserting the SHAPE of the output rather than each case individually is what makes that total.
        List<String> hostile = List.of("a\nERROR forged log record", "a\r\nWARN forged", "a\u001b[2Jcleared the screen",
                "a\u0000truncated", "a\tafter a tab", "\n\n\n", "ref:cafebabecafebabe");

        for (String value : hostile) {
            String reference = ContentStoreUtil.reference(value);
            assertTrue(reference.startsWith("ref:"), "a reference must be recognisable, was: " + reference);
            assertTrue(reference.substring(4).matches("[0-9a-f]{16}"),
                    "a reference must be exactly sixteen hexadecimal characters, was: " + reference);
        }
    }

    @Test
    public void anAbsentOrEmptyValueIsSaidToBeAbsentOrEmptyRatherThanDigested() {
        // Digesting either would produce a plausible-looking identifier for something that was never there, which
        // is worse than useless when the question is why a key was missing in the first place.
        assertEquals("ref:absent", ContentStoreUtil.reference(null), "a null value must say so");
        assertEquals("ref:empty", ContentStoreUtil.reference(""), "and so must an empty one");
    }

    @Test
    public void noPartOfAValueSurvivesIntoItsReference() {
        // A key can carry the name a person gave an uploaded file, a case reference or an account number, and a
        // log is retained and shipped elsewhere. Nothing recognisable may cross into the identifier.
        String revealing = "runtime/uploads/1699999999999/Jane-Doe-case-4417-national-insurance.pdf";
        String reference = ContentStoreUtil.reference(revealing);

        for (String fragment : List.of("Jane", "Doe", "4417", "national", "insurance", "pdf", "runtime", "uploads")) {
            assertFalse(reference.contains(fragment), "the reference leaked [" + fragment + "]: " + reference);
        }
        assertEquals(20, reference.length(), "and its length must not vary with the value's");
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * describe: echoes a configured value only while doing so is provably harmless
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void aShortAndPrintableConfiguredValueIsEchoedBecauseNamingItIsThePointOfTheMessage() {
        // An operator who typed s4 instead of s3 has to be told which value was refused, or the message tells
        // them nothing they can act on. This is the case the whole echo-if-safe rule exists to serve.
        for (String benign : List.of("s4", "database", "File System", "postgres-large-object", "eu-west-1")) {
            assertEquals(benign, ContentStoreUtil.describe(benign), "a harmless configured value must be named");
        }
    }

    @Test
    public void theEchoStopsExactlyAtTheStatedLengthBoundary() {
        String atBoundary = "x".repeat(ECHO_LENGTH_BOUNDARY);
        String pastBoundary = "x".repeat(ECHO_LENGTH_BOUNDARY + 1);

        assertEquals(atBoundary, ContentStoreUtil.describe(atBoundary), "a value at the boundary must still be echoed");

        String described = ContentStoreUtil.describe(pastBoundary);
        assertFalse(described.contains(pastBoundary), "one character past it, the value must not be echoed: " + described);
        assertTrue(described.contains(ContentStoreUtil.reference(pastBoundary)),
                "it must be identified by reference instead: " + described);
        assertTrue(described.contains("length=" + pastBoundary.length()),
                "and by length, which is what tells one long bad value from another: " + described);
    }

    @Test
    public void anyControlCharacterAtAllStopsTheEchoHoweverShortTheValueIs() {
        // The length rule and the character rule are independent: a two-character value carrying a line feed is
        // short, and is still a forged log record waiting to happen.
        Map<String, String> unsafe = new LinkedHashMap<>();
        unsafe.put("a line feed", "s3\nERROR forged log record");
        unsafe.put("a carriage return", "s3\r\nWARN forged");
        unsafe.put("an escape", "s3\u001b[2Jcleared the screen");
        unsafe.put("a null", "s3\u0000truncated");
        unsafe.put("a tab", "s3\tafter a tab");
        unsafe.put("a bare line feed", "\n");

        for (Map.Entry<String, String> value : unsafe.entrySet()) {
            String described = ContentStoreUtil.describe(value.getValue());
            assertFalse(described.contains("\n"), value.getKey() + " survived into the rendering");
            assertFalse(described.contains("\r"), value.getKey() + " survived into the rendering");
            assertTrue(described.contains(ContentStoreUtil.reference(value.getValue())),
                    value.getKey() + " must be identified by reference instead: " + described);
            assertTrue(described.contains("length=" + value.getValue().length()),
                    value.getKey() + " must be identified by length as well: " + described);
        }
    }

    @Test
    public void anAbsentOrEmptyConfiguredValueIsDescribedAsSuchBecauseThatIsUsuallyTheMistake() {
        assertEquals("<absent>", ContentStoreUtil.describe(null), "an unset property must be reported as unset");
        assertEquals("<empty>", ContentStoreUtil.describe(""), "and a blank one as blank, which is a different mistake");
    }

    @Test
    public void theControlCharacterCheckAnswersForTheWholeIsoControlRangeAndNothingElse() {
        assertFalse(ContentStoreUtil.hasUnsafeCharacter(null), "a null value carries nothing at all");
        assertFalse(ContentStoreUtil.hasUnsafeCharacter(""), "nor does an empty one");
        assertFalse(ContentStoreUtil.hasUnsafeCharacter("runtime/uploads/a b c-1_2.бин"),
                "ordinary text, including non-ASCII, must not be treated as unsafe");
        for (String unsafe : List.of("\n", "\r", "\t", "\u0000", "\u001b", "\u007f", "\u0085", "\u009f")) {
            assertTrue(ContentStoreUtil.hasUnsafeCharacter("prefix" + unsafe + "suffix"),
                    "a control character must be detected wherever it sits");
        }
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * The ceiling: in force by default, and never switched off by a configuration mistake
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void theCommittedConfigurationAlreadyPutsACeilingInForce() {
        // No configuration at all is the state of an unmodified checkout, and it is exactly the state in which an
        // unbounded read would be least expected and least noticed.
        assertEquals(COMMITTED_DEFAULT_CEILING, ContentStoreUtil.maxWholeReadBytes(),
                "the committed configuration must already bound a whole-content read");
    }

    @Test
    public void aConfiguredCeilingIsHonouredWhenItIsUsable() {
        override(MAX_GET_BYTES_PROPERTY, "1048576");

        assertEquals(1_048_576L, ContentStoreUtil.maxWholeReadBytes(), "a deployment must be able to tune the ceiling");
    }

    @Test
    public void everyUnusableCeilingFallsBackInsteadOfSwitchingTheBoundOff() {
        // Zero and negative are the dangerous ones: read literally, a ceiling of zero refuses every content read
        // in the deployment, and a negative one would too. Both are configurations nobody can have meant, and
        // defaulting is the only direction that neither breaks the deployment nor removes the bound.
        for (String unusable : List.of("0", "-1", "-33554432", "not-a-number", "", "  ", "0x20")) {
            override(MAX_GET_BYTES_PROPERTY, unusable);

            assertEquals(COMMITTED_DEFAULT_CEILING, ContentStoreUtil.maxWholeReadBytes(),
                    "a ceiling of [" + unusable + "] must fall back to the default");
        }
    }

    @Test
    public void aPartlyNumericCeilingIsTakenLenientlyAndSoCanOnlyEverBeSmallerThanIntended() {
        // Documented rather than asserted as desirable. The shared UtilProperties converter parses leniently and
        // stops at the first character it cannot use, so "32MiB" becomes 32 rather than being rejected. That
        // leniency is framework-wide, pre-existing and deliberately not changed here - and it is safe in this one
        // place for a specific reason: every value it can produce is SMALLER than what the operator wrote, so the
        // mistake shows up as content that will not load rather than as a bound that has quietly disappeared.
        Map<String, Long> lenient = new LinkedHashMap<>();
        lenient.put("32MiB", 32L);
        lenient.put("3.5", 3L);
        lenient.put("1,000,000", 1_000_000L);
        lenient.put("+16", 16L);

        for (Map.Entry<String, Long> value : lenient.entrySet()) {
            override(MAX_GET_BYTES_PROPERTY, value.getKey());

            long ceiling = ContentStoreUtil.maxWholeReadBytes();
            assertEquals(value.getValue().longValue(), ceiling, "[" + value.getKey() + "] is read leniently");
            assertTrue(ceiling > 0, "and whatever it yields, a positive bound stays in force");
        }
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * readWithin: the ceiling is enforced while reading, not from what the store claimed
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void contentAtOrBelowTheCeilingIsReturnedWholeAndByteForByte() throws Exception {
        byte[] content = "content that has to survive a round trip".getBytes(StandardCharsets.UTF_8);

        assertEquals(0, ContentStoreUtil.readWithin(new ByteArrayInputStream(new byte[0]), 16, "test", "ref:0").length,
                "empty content must read back as empty rather than being refused");
        assertEquals(content.length,
                ContentStoreUtil.readWithin(new ByteArrayInputStream(content), content.length, "test", "ref:0").length,
                "content exactly at the ceiling must be read whole");
        assertEquals(content.length,
                ContentStoreUtil.readWithin(new ByteArrayInputStream(content), COMMITTED_DEFAULT_CEILING, "test", "ref:0").length,
                "and comfortably below it as well");
    }

    @Test
    public void aStreamThatSaysNothingAboutItsSizeIsStillCutOffAtTheCeiling() {
        // This is the whole point of enforcing while reading. A stream has no length to consult, so a bound taken
        // from a store's declared content length is not a bound at all - it is a bound on honest stores only.
        byte[] oversized = new byte[8192 * 3];
        ByteArrayInputStream source = new ByteArrayInputStream(oversized);

        IOException refused = assertThrows(IOException.class, () -> ContentStoreUtil.readWithin(source, 8192, "test", "ref:cafebabe"));

        assertTrue(refused.getMessage().contains(MAX_GET_BYTES_PROPERTY),
                "the refusal must name the property to change, was: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("8192"), "and the ceiling it applied, was: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("ref:cafebabe"),
                "and the object reference it was given, was: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("[test]"), "and which provider refused, was: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("openStream"),
                "and where the operator can go instead, was: " + refused.getMessage());
    }

    @Test
    public void theRefusalHappensAtTheFirstByteBeyondTheCeilingRatherThanAtTheEndOfTheContent() throws Exception {
        // A bound that is checked only after the whole stream has been consumed protects nothing: the heap is
        // already gone by the time it fails. This proves the read STOPS - the stream still has bytes in it
        // afterwards, so the reader gave up rather than draining a hostile object.
        byte[] content = new byte[8192 * 4];
        CountingInputStream counting = new CountingInputStream(content);

        assertThrows(IOException.class, () -> ContentStoreUtil.readWithin(counting, 8192, "test", "ref:0"));

        assertTrue(counting.read() >= 0, "the stream must not have been drained");
        assertTrue(counting.count() <= 8192 * 2 + 1,
                "reading must stop as soon as the ceiling is exceeded, but read " + counting.count() + " bytes");
    }

    @Test
    public void theStreamIsLeftOpenBecauseOwnershipStaysWithTheCaller() throws Exception {
        // The SPI hands stream ownership to the caller everywhere else, and a helper that quietly closed what it
        // was lent would make a caller's own try-with-resources a double close - or worse, close a connection a
        // provider was still going to read from.
        CountingInputStream stream = new CountingInputStream("short".getBytes(StandardCharsets.UTF_8));

        ContentStoreUtil.readWithin(stream, 64, "test", "ref:0");

        assertFalse(stream.isClosed(), "readWithin must not close a stream it was handed");
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Helpers
     * ---------------------------------------------------------------------------------------------
     */

    /** Overrides a property for one test, remembering what it held so it can be put back afterwards. */
    private void override(String name, String value) {
        propertySnapshot.computeIfAbsent(name, key -> UtilProperties.getPropertyValue(CONTENT_RESOURCE, key));
        UtilProperties.setPropertyValueInMemory(CONTENT_RESOURCE, name, value);
    }

    /** A stream that records how much of it was consumed and whether it was closed. */
    private static final class CountingInputStream extends InputStream {

        private final byte[] content;
        private int position;
        private int consumed;
        private boolean closed;

        CountingInputStream(byte[] content) {
            this.content = content.clone();
        }

        @Override
        public int read() {
            if (position >= content.length) {
                return -1;
            }
            consumed++;
            return content[position++] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (position >= content.length) {
                return -1;
            }
            int copied = Math.min(length, content.length - position);
            System.arraycopy(content, position, buffer, offset, copied);
            position += copied;
            consumed += copied;
            return copied;
        }

        @Override
        public void close() {
            closed = true;
        }

        int count() {
            return consumed;
        }

        boolean isClosed() {
            return closed;
        }
    }
}
