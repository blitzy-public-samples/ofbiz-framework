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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.apache.ofbiz.base.util.UtilProperties;

/**
 * Shared, non-instantiable helpers for the content storage providers and their factory.
 *
 * <p>Everything here exists to make two classes of defect impossible rather than to be convenient.
 *
 * <p><strong>Safe diagnostics.</strong> A storage key, a filesystem path and a configured provider name are
 * all attacker-influenced text: a key can carry the name a user gave an uploaded file, and a configured value
 * can carry anything an operator pasted. Composing such text straight into a log line lets a value containing
 * a line break forge a whole log record, and lets a key containing a person's name or a case reference put
 * that identifier into a log that is retained and shipped elsewhere. {@link #reference(String)} therefore
 * turns any such value into a short, stable, one-way identifier: two log lines about the same object carry the
 * same reference, so a problem is still traceable, while the value itself never appears.
 * {@link #describe(String)} is the narrower form used for configuration values, where naming the offending
 * value is what lets an operator fix a typo - it echoes a value only while the value is short and free of
 * anything that could forge a log line, and falls back to a reference otherwise.
 *
 * <p><strong>Bounded whole-content reads.</strong> {@code ContentStore.get} answers a {@code byte[]}, so it
 * necessarily materialises a whole object in the heap. An object store or a shared upload volume can hold
 * content far larger than the heap, and a single such read is enough to bring an instance down - which in a
 * load-balanced fleet is a request-driven denial of service against whichever instance received it.
 * {@link #maxWholeReadBytes()} publishes the configured ceiling and {@link #readWithin} enforces it while
 * reading, so a store that under-reports a size cannot get past it either.
 *
 * <p><strong>Why this class and {@link ContentStoreSupport} both exist.</strong> The two carry helpers of the
 * same names, and they are deliberately NOT interchangeable: each is pinned by its own suite -
 * {@code ContentStoreUtilTests} for these, {@code ContentStoreSafetyBoundsTests} for those - so moving a call
 * site from one to the other changes a message those tests assert on. What differs is small and worth knowing:
 *
 * <ul>
 *   <li>{@link #reference(String)} renders sixteen hexadecimal digits where
 *       {@code ContentStoreSupport.reference} renders twelve. Both are the leading bytes of the SHA-256 of the
 *       same UTF-8 encoding rendered through the same alphabet, so for any non-empty value the shorter form is
 *       a strict PREFIX of the longer one: a record written through either helper still correlates with one
 *       written through the other.</li>
 *   <li>Only the degenerate cases fail to correlate, because the two spell them differently:
 *       {@code ref:absent} and {@code ref:empty} here, {@code <null>} and {@code <empty>} there.</li>
 *   <li>{@link #describe(String)} is the stricter of the two. It echoes a value only while it is at most
 *       {@value #MAX_DESCRIBED_LENGTH} characters and free of control characters, and otherwise replaces it
 *       wholesale with an opaque reference and its length; {@code ContentStoreSupport.describe} always echoes,
 *       substituting a full stop for each control character and truncating with a character count. Use this
 *       one where the value may have been chosen by a hostile client, the other where an operator needs to
 *       read back what they typed.</li>
 *   <li>The two ceilings are different settings rather than two spellings of one. {@code ContentStoreSupport}
 *       owns {@code content.store.max.memory.bytes}, the ceiling on what a whole-content operation may HOLD in
 *       the heap, which bounds {@code put(String, byte[])} in both providers and the size an
 *       {@code S3ContentStore.get} was told to expect. This class owns {@code content.store.max.get.bytes},
 *       the ceiling on a whole-content READ, enforced by {@link #readWithin} while reading so that a store
 *       which under-reports a size cannot get past it. The heap ceiling is the lower of the two by default, so
 *       it binds first wherever both apply.</li>
 * </ul>
 *
 * <p>This class is package-private on purpose: it is an implementation detail of the {@code store} package
 * and widens no API.
 */
final class ContentStoreUtil {

    /** The resource holding the configuration, named without its extension as {@code UtilProperties} requires. */
    private static final String PROPERTY_RESOURCE = "content";

    /** The property capping how much content a single whole-content read may materialise. */
    private static final String MAX_WHOLE_READ_PROPERTY = "content.store.max.get.bytes";

    /**
     * The ceiling used when {@link #MAX_WHOLE_READ_PROPERTY} yields nothing usable: 32 MiB.
     *
     * <p>Chosen to be far larger than the documents and images a content-management deployment handles
     * routinely, and far smaller than a default JVM heap, so it bounds the damage without being reached by
     * ordinary content.
     */
    private static final long DEFAULT_MAX_WHOLE_READ_BYTES = 33_554_432L;

    /** Hex digits, used to render a digest without allocating a formatter per call. */
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** How many digest bytes a reference carries. Eight bytes is 16 hex characters. */
    private static final int REFERENCE_BYTES = 8;

    /** Longest configuration value {@link #describe(String)} will echo rather than replace with a reference. */
    private static final int MAX_DESCRIBED_LENGTH = 48;

    /** Size of the buffer {@link #readWithin} copies through. */
    private static final int COPY_BUFFER_BYTES = 8192;

    private ContentStoreUtil() { }

    /**
     * Returns a short, stable, one-way identifier for a value that must never be logged as itself.
     *
     * <p>The identifier is the first {@value #REFERENCE_BYTES} bytes of the SHA-256 digest of the value's
     * UTF-8 encoding, rendered as lower-case hexadecimal and prefixed so a reader can tell what they are
     * looking at. Two properties matter and both are deliberate: the same value always yields the same
     * reference, so several log lines about one object can be correlated and an operator who suspects a
     * particular key can confirm it by digesting that key themselves; and no part of the value can be
     * recovered from the reference, so a key bearing a person's name, a case number or an account identifier
     * leaves nothing behind in the log. The result contains only hexadecimal characters, so it can never
     * forge a log line however hostile the input was.
     *
     * @param value the value to identify; may be null or empty
     * @return an opaque identifier, never null, safe to place in any message
     */
    static String reference(String value) {
        if (value == null) {
            return "ref:absent";
        }
        if (value.isEmpty()) {
            return "ref:empty";
        }
        byte[] digest = sha256(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder rendered = new StringBuilder(4 + REFERENCE_BYTES * 2);
        rendered.append("ref:");
        for (int index = 0; index < REFERENCE_BYTES; index++) {
            int unsigned = digest[index] & 0xFF;
            rendered.append(HEX[unsigned >>> 4]).append(HEX[unsigned & 0x0F]);
        }
        return rendered.toString();
    }

    /**
     * Renders a configured value for a message, echoing it only while doing so is provably harmless.
     *
     * <p>Configuration is the one place where naming the offending value is the whole point of the message:
     * an operator who typed {@code s4} instead of {@code s3} needs to be told so. But a configured value is
     * still untrusted text, so it is echoed only when it is short and contains nothing that could forge a log
     * line - no control character, and in particular no carriage return or line feed. Anything else is
     * replaced by an opaque {@link #reference(String)} together with its length, which is enough to tell one
     * bad value from another and to confirm a suspected value, without reproducing it.
     *
     * @param value the configured value to render; may be null or empty
     * @return a rendering that is always safe to place in a message, never null
     */
    static String describe(String value) {
        if (value == null) {
            return "<absent>";
        }
        if (value.isEmpty()) {
            return "<empty>";
        }
        if (value.length() > MAX_DESCRIBED_LENGTH || hasUnsafeCharacter(value)) {
            return "<" + reference(value) + ", length=" + value.length() + ">";
        }
        return value;
    }

    /**
     * Reports whether a value carries a character that must never reach a log line or a message.
     *
     * <p>Java's {@link Character#isISOControl(char)} covers the C0 and C1 control ranges, which includes the
     * carriage return and line feed used to forge a log record and the escape character used to drive a
     * terminal that renders the log. The check is deliberately on the whole of those ranges rather than on
     * newlines alone, because the safe set for a message is small and easily stated.
     *
     * @param value the value to inspect; may be null
     * @return {@code true} if the value contains a character that is unsafe to echo
     */
    static boolean hasUnsafeCharacter(String value) {
        if (value == null) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the configured ceiling, in bytes, on how much content one whole-content read may materialise.
     *
     * <p>A value that is absent, blank, unparsable or not positive yields
     * {@value #DEFAULT_MAX_WHOLE_READ_BYTES}. Every one of those cases is a configuration the deployment
     * cannot have meant, and defaulting is the safe direction: the ceiling stays in force rather than being
     * switched off by a typo.
     *
     * @return the ceiling in bytes, always positive
     */
    static long maxWholeReadBytes() {
        long configured = UtilProperties.getPropertyAsLong(PROPERTY_RESOURCE, MAX_WHOLE_READ_PROPERTY,
                DEFAULT_MAX_WHOLE_READ_BYTES);
        return configured > 0 ? configured : DEFAULT_MAX_WHOLE_READ_BYTES;
    }

    /**
     * Reads a stream to its end into memory, refusing to materialise more than the supplied ceiling.
     *
     * <p>The ceiling is enforced <em>while</em> reading rather than from a size the store reported
     * beforehand, which is what makes it a real bound: a store that under-reports a content length, a store
     * whose object grew between the metadata call and the read, and a store that reports no length at all are
     * all handled identically. Reading stops as soon as the ceiling would be exceeded, so the heap high-water
     * mark of a refused read stays within the ceiling instead of following the content.
     *
     * <p>The stream is not closed here: ownership stays with the caller, which is what the
     * {@link ContentStore} contract says about every stream it hands out.
     *
     * @param content the stream to read; must not be null
     * @param limit the greatest number of bytes that may be materialised; must be positive
     * @param provider the provider name used in a failure message, so the message says which store refused
     * @param objectReference an opaque {@link #reference(String)} for the content, never a raw key
     * @return everything the stream held, never null, never longer than {@code limit}
     * @throws IOException if the stream cannot be read, or if it holds more than {@code limit} bytes
     */
    static byte[] readWithin(InputStream content, long limit, String provider, String objectReference)
            throws IOException {
        // The accumulator is only as large as what has actually been read, so a refused read never allocates
        // in proportion to the content - only in proportion to the ceiling it was refused for.
        ByteArrayOutputStream accumulated = new ByteArrayOutputStream(COPY_BUFFER_BYTES);
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        long total = 0;
        int read = content.read(buffer);
        while (read >= 0) {
            total += read;
            if (total > limit) {
                throw new IOException("Content store provider [" + provider + "] refused to read " + objectReference
                        + " in full: it holds more than the " + limit + " bytes allowed by ["
                        + MAX_WHOLE_READ_PROPERTY + "] of resource [" + PROPERTY_RESOURCE
                        + "]. Read it through openStream instead, which streams without materialising it");
            }
            accumulated.write(buffer, 0, read);
            read = content.read(buffer);
        }
        return accumulated.toByteArray();
    }

    /**
     * Digests bytes with SHA-256.
     *
     * @param value the bytes to digest
     * @return the digest
     */
    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException e) {
            // Every conforming Java runtime is required to provide SHA-256, so this cannot happen. It is
            // turned into an unchecked failure rather than a silent fallback because a diagnostics helper that
            // quietly stopped being one-way would defeat the purpose of every caller.
            throw new IllegalStateException("SHA-256 is required by the Java platform but is unavailable", e);
        }
    }
}
