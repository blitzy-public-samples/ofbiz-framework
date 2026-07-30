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
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;

/**
 * Shared, provider-independent plumbing for the {@link ContentStore} implementations of this package.
 *
 * <p>It exists so that the three behaviours every provider owes its callers are written once and are
 * therefore identical across providers rather than re-derived, and differently, in each of them:
 * <ul>
 *   <li><strong>Bounded whole-content operations.</strong> {@link ContentStore#get(String)} and
 *       {@link ContentStore#put(String, byte[])} materialise content in the heap, and the size of
 *       that content is chosen by whoever uploaded it. The ceiling named by
 *       {@code content.store.max.memory.bytes} is resolved and enforced here, so a provider cannot
 *       forget to enforce it and no two providers can enforce a different one.</li>
 *   <li><strong>Exact-length streaming.</strong> The transfer loop behind
 *       {@link ContentStore#put(String, InputStream, long)} consumes exactly the declared number of
 *       bytes and fails on a short stream, which is what keeps a truncated upload from being stored
 *       as though it were complete.</li>
 *   <li><strong>Safe identifiers in logs and messages.</strong> Storage keys and the locations they
 *       resolve to arrive from request data, so this package never puts one into a log line or an
 *       exception message verbatim. {@link #reference(String)} renders a content identifier as a
 *       stable digest - correlatable across records without disclosing the key, the path or the
 *       object layout - and {@link #describe(String)} renders an operator-supplied value, such as a
 *       configured location or a mistyped property value, with every control character removed and
 *       its length capped so that it can neither forge a log record nor flood one.</li>
 * </ul>
 *
 * <p><strong>Why this class and {@link ContentStoreUtil} both exist.</strong> That class carries helpers of
 * the same names, and the two are deliberately NOT interchangeable: each is pinned by its own suite -
 * {@code ContentStoreSafetyBoundsTests} for these, {@code ContentStoreUtilTests} for those - so moving a call
 * site from one to the other changes a message those tests assert on. What differs:
 *
 * <ul>
 *   <li>{@link #reference(String)} renders twelve hexadecimal digits where
 *       {@code ContentStoreUtil.reference} renders sixteen. Both take the leading bytes of the SHA-256 of the
 *       same UTF-8 encoding and render them through the same alphabet, so this form is a strict PREFIX of that
 *       one for any non-empty value and the two still correlate in a log. The degenerate cases are the only
 *       outputs that do not: {@code <null>} and {@code <empty>} here, {@code ref:absent} and
 *       {@code ref:empty} there.</li>
 *   <li>{@link #describe(String)} always echoes, with each control character replaced by a full stop and the
 *       result truncated at {@value #DESCRIPTION_MAX_LENGTH} characters plus a character count, which is what
 *       an operator needs when the point of the message is to read back what they typed.
 *       {@code ContentStoreUtil.describe} is stricter: it echoes only a short, control-character-free value
 *       and replaces anything else wholesale with an opaque reference.</li>
 *   <li>The ceilings are different settings rather than two spellings of one. This class owns
 *       {@code content.store.max.memory.bytes}, the ceiling on what a whole-content operation may HOLD in the
 *       heap; {@code ContentStoreUtil} owns {@code content.store.max.get.bytes}, the ceiling on a whole-content
 *       READ, which it enforces while reading rather than from a reported size. The heap ceiling is the lower
 *       of the two by default, so it binds first wherever both apply.</li>
 * </ul>
 *
 * <p>This class is package-private and holds no state: it is plumbing for the providers beside it,
 * not part of the storage contract, and nothing outside this package can reach it.
 */
final class ContentStoreSupport {

    private static final String MODULE = ContentStoreSupport.class.getName();

    /** The resource name of {@code content.properties}, which is the bare name without extension. */
    static final String PROPERTY_RESOURCE = "content";

    /** The property capping how much content a whole-content operation may hold in the heap. */
    private static final String MAX_MEMORY_BYTES_PROPERTY = "content.store.max.memory.bytes";

    /** The heap ceiling applied when the property is absent, unparsable or out of range: 20 MiB. */
    private static final long DEFAULT_MAX_MEMORY_BYTES = 20L * 1024L * 1024L;

    /** The smallest configurable heap ceiling; below this even a modest thumbnail would be refused. */
    private static final long MIN_MAX_MEMORY_BYTES = 4L * 1024L;

    /** The largest configurable heap ceiling; above this the ceiling stops being a safety limit. */
    private static final long MAX_MAX_MEMORY_BYTES = 512L * 1024L * 1024L;

    /** Transfer chunk size: large enough to keep syscall overhead low, small enough to stay off the heap's large-object path. */
    private static final int TRANSFER_CHUNK_BYTES = 64 * 1024;

    /** How many characters of an identifier reach a log line or an exception message. */
    private static final int DESCRIPTION_MAX_LENGTH = 96;

    /** The stand-in a control character is replaced by, so no identifier can inject a line into a log. */
    private static final char SANITISED_REPLACEMENT = '.';

    /** The digest algorithm content identifiers are rendered through; every JRE is required to provide it. */
    private static final String REFERENCE_ALGORITHM = "SHA-256";

    /** How many hexadecimal characters of the digest are shown: enough to correlate, too few to reverse. */
    private static final int REFERENCE_LENGTH = 12;

    /** Hexadecimal alphabet used to render a digest without pulling in a formatter. */
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private ContentStoreSupport() {
    }

    /**
     * Resolves the ceiling, in bytes, that a whole-content operation may materialise in the heap.
     *
     * <p>Read from {@code content.store.max.memory.bytes} on every call rather than cached, so that a
     * configuration reload takes effect without rebuilding a provider. An absent, unparsable or
     * out-of-range setting yields the default and is reported once per occurrence at warning level -
     * silently substituting a different ceiling than the operator configured is exactly the kind of
     * divergence that only surfaces as an unexplained refusal much later.
     *
     * @return the ceiling in bytes, always between {@value #MIN_MAX_MEMORY_BYTES} and
     *     {@value #MAX_MAX_MEMORY_BYTES} inclusive
     */
    static long maxInMemoryBytes() {
        return boundedLongProperty(MAX_MEMORY_BYTES_PROPERTY, DEFAULT_MAX_MEMORY_BYTES,
                MIN_MAX_MEMORY_BYTES, MAX_MAX_MEMORY_BYTES);
    }

    /**
     * Resolves a numeric property of the {@code content} resource, refusing a value outside the
     * supplied inclusive range.
     *
     * <p>An out-of-range or unparsable value falls back to the supplied default instead of
     * propagating: a mistyped tuning value must not stop content being served, but it must be
     * visible, so the fallback is logged with the offending value in its sanitised form.
     *
     * @param property the fully qualified property name to read
     * @param fallback the value used when the property yields nothing usable
     * @param minimum the smallest accepted value, inclusive
     * @param maximum the largest accepted value, inclusive
     * @return the configured value when it parses and is in range, the fallback otherwise
     */
    static long boundedLongProperty(String property, long fallback, long minimum, long maximum) {
        String raw = UtilProperties.getPropertyValue(PROPERTY_RESOURCE, property);
        if (UtilValidate.isEmpty(raw)) {
            return fallback;
        }
        long parsed;
        try {
            parsed = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            Debug.logWarning("Content store property [" + property + "] is not a whole number ["
                    + describe(raw) + "]; using [" + fallback + "]", MODULE);
            return fallback;
        }
        if (parsed < minimum || parsed > maximum) {
            Debug.logWarning("Content store property [" + property + "] value [" + parsed + "] is outside ["
                    + minimum + ".." + maximum + "]; using [" + fallback + "]", MODULE);
            return fallback;
        }
        return parsed;
    }

    /**
     * Resolves a numeric property of the {@code content} resource as an {@code int}.
     *
     * @param property the fully qualified property name to read
     * @param fallback the value used when the property yields nothing usable
     * @param minimum the smallest accepted value, inclusive
     * @param maximum the largest accepted value, inclusive
     * @return the configured value when it parses and is in range, the fallback otherwise
     */
    static int boundedIntProperty(String property, int fallback, int minimum, int maximum) {
        return (int) boundedLongProperty(property, fallback, minimum, maximum);
    }

    /**
     * Rejects a storage key that no provider can act on.
     *
     * <p>A key that is nothing but whitespace is rejected as well, and is reported separately from a
     * null or empty one because it is a different mistake: it has a length, so it survives every
     * emptiness test, yet it names an entry no caller could reliably address a second time.
     *
     * @param key the storage key supplied by the caller
     * @return the key, unchanged, so that this can be used inline
     * @throws GeneralException if the key is null, empty or blank
     */
    static String requireUsableKey(String key) throws GeneralException {
        if (UtilValidate.isEmpty(key)) {
            throw new GeneralException("Cannot address content storage: the key is null or empty");
        }
        if (key.isBlank()) {
            throw new GeneralException("Cannot address content storage: the key is null, empty or blank");
        }
        return key;
    }

    /**
     * Rejects content that a whole-content write cannot accept, before any of it reaches the store.
     *
     * @param key the storage key the content would be written under, used only to build the message
     * @param data the content the caller supplied
     * @param ceiling the largest accepted content length in bytes
     * @throws GeneralException if the content is null, or is longer than the ceiling
     */
    static void requireStorableContent(String key, byte[] data, long ceiling) throws GeneralException {
        if (data == null) {
            throw new GeneralException("Cannot store content under key [" + reference(key) + "]: the content is null");
        }
        if (data.length > ceiling) {
            throw new GeneralException("Cannot store content under key [" + reference(key) + "]: " + data.length
                    + " bytes exceeds the " + ceiling + " byte in-memory ceiling ["
                    + MAX_MEMORY_BYTES_PROPERTY + "]; use the streaming form of put instead");
        }
    }

    /**
     * Rejects a streaming write whose stream or declared length cannot be acted on.
     *
     * @param key the storage key the content would be written under, used only to build the message
     * @param content the stream the caller supplied
     * @param length the number of bytes the caller declared
     * @throws GeneralException if the stream is null or the length is negative
     */
    static void requireStorableStream(String key, InputStream content, long length) throws GeneralException {
        if (content == null) {
            throw new GeneralException("Cannot store content under key [" + reference(key) + "]: the stream is null");
        }
        if (length < 0) {
            throw new GeneralException("Cannot store content under key [" + reference(key) + "]: the declared length "
                    + length + " is negative");
        }
    }

    /**
     * Refuses to materialise content whose length is already known to exceed the heap ceiling.
     *
     * <p>Called before a read begins, so that an over-large object is refused without being
     * transferred at all when the store can report its length up front.
     *
     * @param key the storage key being read, used only to build the message
     * @param length the known content length in bytes
     * @param ceiling the largest length that may be materialised
     * @throws GeneralException if the length exceeds the ceiling
     */
    static void requireReadableInMemory(String key, long length, long ceiling) throws GeneralException {
        if (length > ceiling) {
            throw new GeneralException("Cannot read content under key [" + reference(key) + "] into memory: " + length
                    + " bytes exceeds the " + ceiling + " byte in-memory ceiling [" + MAX_MEMORY_BYTES_PROPERTY
                    + "]; use openStream instead");
        }
    }

    /**
     * Reads a stream to its end into a byte array, abandoning the read as soon as it is known to
     * exceed the supplied ceiling.
     *
     * <p>The ceiling is tested as the content arrives rather than afterwards, so content that is
     * larger than the ceiling is never fully held: at most one chunk beyond the ceiling is ever
     * buffered. The stream is not closed - the caller owns it.
     *
     * @param stream the stream to drain; must not be null
     * @param key the storage key being read, used only to build the message
     * @param ceiling the largest number of bytes that may be accumulated
     * @return everything the stream yielded, never null
     * @throws GeneralException if the stream yields more than the ceiling allows
     * @throws IOException if the stream cannot be read
     */
    static byte[] readBounded(InputStream stream, String key, long ceiling) throws GeneralException, IOException {
        ByteArrayOutputStream accumulated = new ByteArrayOutputStream();
        byte[] chunk = new byte[TRANSFER_CHUNK_BYTES];
        long total = 0;
        int read = stream.read(chunk);
        while (read >= 0) {
            total += read;
            if (total > ceiling) {
                throw new GeneralException("Cannot read content under key [" + reference(key) + "] into memory: it exceeds the "
                        + ceiling + " byte in-memory ceiling [" + MAX_MEMORY_BYTES_PROPERTY + "]; use openStream instead");
            }
            accumulated.write(chunk, 0, read);
            read = stream.read(chunk);
        }
        return accumulated.toByteArray();
    }

    /**
     * Transfers exactly the declared number of bytes from one stream to another.
     *
     * <p>Neither stream is closed, and no more than {@code length} bytes are consumed from the
     * source, so the caller keeps whatever follows. A source that ends early is a failure rather
     * than a short write, because a short write would be indistinguishable from content that was
     * genuinely that short.
     *
     * @param source the stream to read from; must not be null
     * @param sink the stream to write to; must not be null
     * @param length the exact number of bytes to move
     * @param key the storage key being written, used only to build the message
     * @throws IOException if either stream fails, or if the source ends before {@code length} bytes
     *     have been read
     */
    static void transferExactly(InputStream source, OutputStream sink, long length, String key) throws IOException {
        byte[] chunk = new byte[TRANSFER_CHUNK_BYTES];
        long remaining = length;
        while (remaining > 0) {
            int wanted = (int) Math.min(chunk.length, remaining);
            int read = source.read(chunk, 0, wanted);
            if (read < 0) {
                throw new IOException("Content under key [" + reference(key) + "] ended after " + (length - remaining)
                        + " of " + length + " declared bytes");
            }
            sink.write(chunk, 0, read);
            remaining -= read;
        }
    }

    /**
     * Wraps a caller's stream so that exactly the declared number of bytes can be read from it, and no
     * more.
     *
     * <p>This exists for the write paths that hand a stream to a client library rather than copying it
     * themselves - an object-store SDK request body, for instance. Declaring a length to such a library
     * only sets a header; it does not bound what the library reads from the stream it was given. Verified
     * against the shipped SDK: a request body built over a stream with a declared length of 19 still
     * yields all 24 bytes of a 24-byte stream, and a stream holding fewer bytes than were declared is
     * transmitted short without complaint. Both guarantees therefore have to be made here, which is what
     * this wrapper does, without buffering the content anywhere:
     *
     * <ul>
     * <li><strong>Never over-reads.</strong> The wrapper reports end-of-stream once {@code length} bytes
     * have been handed out, leaving the rest of the caller's stream untouched. Callers stream content
     * they still own - the next part of a multipart body, for example - and a library that drained past
     * the declared length would consume that next part as if it were content.</li>
     * <li><strong>Fails rather than sends a short body.</strong> If the underlying stream ends before
     * {@code length} bytes have been produced, the read fails with an {@link IOException} instead of
     * quietly storing what arrived, which would leave a silently truncated entry that reads back cleanly
     * and is indistinguishable from intact content.</li>
     * </ul>
     *
     * <p>The wrapper does not close the stream it was given: ownership stays with the caller, exactly as
     * it does for the write methods themselves. {@code mark}/{@code reset} are delegated so that a
     * library able to replay a body still can, with the outstanding count restored alongside the stream
     * position so a replayed attempt is bounded identically to the first.
     *
     * @param source the caller's stream to read content from; must not be null
     * @param length the exact number of bytes that may be read from it; must not be negative
     * @param key the storage key being written, used only to build the failure message
     * @return a stream over the first {@code length} bytes of the source, never null
     */
    static InputStream exactLengthStream(InputStream source, long length, String key) {
        return new FilterInputStream(source) {

            private long remaining = length;
            private long markedRemaining = -1L;

            @Override
            public int read() throws IOException {
                if (remaining == 0) {
                    return -1;
                }
                int value = in.read();
                if (value < 0) {
                    throw endedEarly();
                }
                remaining--;
                return value;
            }

            @Override
            public int read(byte[] buffer, int offset, int wanted) throws IOException {
                if (wanted == 0) {
                    return 0;
                }
                if (remaining == 0) {
                    return -1;
                }
                int read = in.read(buffer, offset, (int) Math.min(wanted, remaining));
                if (read < 0) {
                    throw endedEarly();
                }
                remaining -= read;
                return read;
            }

            @Override
            public long skip(long requested) throws IOException {
                long skipped = in.skip(Math.min(requested, remaining));
                remaining -= skipped;
                return skipped;
            }

            @Override
            public int available() throws IOException {
                return (int) Math.min(in.available(), remaining);
            }

            @Override
            public synchronized void mark(int readLimit) {
                in.mark(readLimit);
                markedRemaining = remaining;
            }

            @Override
            public synchronized void reset() throws IOException {
                in.reset();
                if (markedRemaining >= 0) {
                    remaining = markedRemaining;
                }
            }

            @Override
            public void close() {
                // deliberately not closed: the stream belongs to the caller that supplied it
            }

            private IOException endedEarly() {
                return new IOException("Content under key [" + reference(key) + "] ended after "
                        + (length - remaining) + " of " + length + " declared bytes");
            }
        };
    }

    /**
     * Renders an identifier - a storage key, a path, a configuration value - in the only form this
     * package puts one into a log line or an exception message.
     *
     * <p>Two things are done to it, and both matter because storage keys reach this package from
     * request data. Every control character is replaced, so a key containing a newline or a terminal
     * escape sequence cannot forge a log record or rewrite an operator's terminal; and the result is
     * capped in length, with the true length appended when it is capped, so a very long key cannot
     * flood the log while still being identifiable.
     *
     * @param value the identifier to render; may be null
     * @return a rendering that is safe to log, never null
     */
    static String describe(String value) {
        if (value == null) {
            return "<null>";
        }
        if (value.isEmpty()) {
            return "<empty>";
        }
        int retained = Math.min(value.length(), DESCRIPTION_MAX_LENGTH);
        StringBuilder rendered = new StringBuilder(retained + 24);
        for (int index = 0; index < retained; index++) {
            char character = value.charAt(index);
            rendered.append(Character.isISOControl(character) ? SANITISED_REPLACEMENT : character);
        }
        if (value.length() > retained) {
            rendered.append("...(").append(value.length()).append(" characters)");
        }
        return rendered.toString();
    }

    /**
     * Renders a content identifier - a storage key, or the location one resolved to - as a stable
     * digest, which is the only form in which this package refers to one in a log line or an exception
     * message.
     *
     * <p>A content key names a party's document, an order attachment or a product image, and it reaches
     * this package from request data. Writing it out verbatim would publish the object layout of the
     * store to anyone who can read a log, and would let request data choose what a log record contains.
     * A digest avoids both while keeping the identifier useful: it is deterministic, so the same key
     * always produces the same reference and a failure can be correlated across records and across
     * instances, and it is short, so it cannot flood a log.
     *
     * @param value the content identifier to render; may be null
     * @return a rendering that is safe to log and stable across calls, never null
     */
    static String reference(String value) {
        if (value == null) {
            return "<null>";
        }
        if (value.isEmpty()) {
            return "<empty>";
        }
        byte[] digest;
        try {
            digest = MessageDigest.getInstance(REFERENCE_ALGORITHM).digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated for every Java platform, so this cannot happen; falling back to a
            // length-only token keeps a diagnostic message buildable rather than turning a storage
            // failure into a second, unrelated failure while reporting the first
            Debug.logWarning("Digest algorithm [" + REFERENCE_ALGORITHM + "] is unavailable; content references"
                    + " are degraded to a length-only form", MODULE);
            return "len:" + value.length();
        }
        StringBuilder rendered = new StringBuilder(REFERENCE_LENGTH + 8).append("ref:");
        for (int index = 0; index < REFERENCE_LENGTH / 2; index++) {
            int unsigned = digest[index] & 0xff;
            rendered.append(HEX[unsigned >>> 4]).append(HEX[unsigned & 0x0f]);
        }
        return rendered.toString();
    }
}
