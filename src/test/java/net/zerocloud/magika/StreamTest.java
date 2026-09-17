/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class StreamTest {
    @Test
    public void chunkingPreservesFeaturesAndEveryResultFieldAtContentBoundaries() {
        List<byte[]> cases = Fixtures.boundaryContents();
        for (int size : new int[] {4095, 4096, 4097, 8191, 8192, 8193, 32785}) {
            byte[] bytes = new byte[size];
            new Random(size).nextBytes(bytes);
            cases.add(bytes);
        }
        int[][] chunks = {{1}, {7}, {4096}, {8192}, {1, 113, 3, 4095, 17}};
        int comparisons = 0;
        for (PredictionMode mode : PredictionMode.values()) {
            try (Magika sdk = Magika.builder().predictionMode(mode).build()) {
                for (byte[] content : cases) {
                    DetectionResult expected = sdk.identify(content);
                    for (int[] sizes : chunks) {
                        String message = mode + " length=" + content.length + " chunks=" + Arrays.toString(sizes);
                        ObservedStream input = new ObservedStream(content, sizes);
                        Fixtures.assertEquivalent(message, expected, sdk.identify(input));
                        assertEquals(message, content.length, input.position);
                        assertTrue(message, input.eofSeen);
                        untouchedOwnership(input);
                        assertArrayEquals(message, Features.extract(content, 1024, 1024, 4096, 256),
                                Features.extract(InputSample.fromStream(new ObservedStream(content, sizes),
                                        4096, Long.MAX_VALUE), 1024, 1024, 256));
                        comparisons++;
                    }
                }
            }
        }
        assertEquals(1050, comparisons);
        System.out.println("Stream/byte[] exact feature and full-result boundary comparisons: " + comparisons);
    }

    @Test
    public void consumesOnlyRemainingContentWithoutResetOrClose() throws Exception {
        byte[] envelope = new byte[19 + MagikaTest.PDF.length];
        Arrays.fill(envelope, 0, 19, (byte) 0xff);
        System.arraycopy(MagikaTest.PDF, 0, envelope, 19, MagikaTest.PDF.length);
        ObservedStream input = new ObservedStream(envelope, 3, 17, 1);
        input.resettable = true;
        for (int i = 0; i < 19; i++) { assertEquals(255, input.read()); }
        // The limit applies to remaining bytes, excluding the already consumed envelope.
        DetectionResult retained;
        try (Magika sdk = Magika.builder().maxStreamBytes(MagikaTest.PDF.length).build()) {
            retained = sdk.identify(input);
            Fixtures.assertEquivalent("nonzero position", sdk.identify(MagikaTest.PDF), retained);
            assertEquals(envelope.length, input.position);
            assertTrue(input.eofSeen);
            assertEquals("empty", sdk.identify(input).getLabel());
        }
        untouchedOwnership(input);
        assertEquals("pdf", retained.getLabel());
        // Replay belongs to the caller; after it repositions, the stream remains usable.
        input.reset();
        assertEquals(255, input.read());
        input.close();
        assertEquals(1, input.closes);
    }

    @Test
    public void defaultLimitAcceptsExactly64MiBAndStopsAfterOneExtraByte() {
        try (Magika created = Magika.create(); Magika built = Magika.builder().build()) {
            for (Magika sdk : new Magika[] {created, built}) {
                GeneratedStream exact = new GeneratedStream(67_108_864L);
                assertTrue(sdk.identify(exact).isModelUsed());
                assertEquals(67_108_864L, exact.consumed);
                assertTrue(exact.eofSeen);
                GeneratedStream oversized = new GeneratedStream(Long.MAX_VALUE);
                limitFailure(sdk, oversized, 67_108_864L);
                assertEquals(67_108_865L, oversized.consumed);
                assertFalse(oversized.eofSeen);
                assertEquals(0, exact.closes + exact.resets + oversized.closes + oversized.resets);
            }
        }
    }

    @Test
    public void customLimitsIncludeZeroExactAndOversizedInputs() {
        for (int limit : new int[] {0, 1, 7, 4095, 4096, 4097, 8193}) {
            try (Magika sdk = Magika.builder().maxStreamBytes(limit).build()) {
                for (int length : new int[] {0, Math.max(0, limit - 1), limit}) {
                    byte[] content = new byte[length];
                    new Random(length).nextBytes(content);
                    ObservedStream input = new ObservedStream(content, 13, 1, 4096);
                    Fixtures.assertEquivalent("limit=" + limit + " length=" + length,
                            sdk.identify(content), sdk.identify(input));
                    assertEquals(length, input.position);
                    assertTrue(input.eofSeen);
                    untouchedOwnership(input);
                }
                for (int length : new int[] {limit + 1, limit + 12345}) {
                    ObservedStream oversized = new ObservedStream(new byte[length], 8192);
                    limitFailure(sdk, oversized, limit);
                    assertEquals("At most one excess byte", limit + 1, oversized.position);
                    assertFalse("No reads after excess byte", oversized.eofSeen);
                    untouchedOwnership(oversized);
                }
            }
        }
    }

    @Test
    public void builderRejectsNegativeLimitsAndSnapshotsLongLimits() {
        for (long invalid : new long[] {-1, Long.MIN_VALUE}) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> Magika.builder().maxStreamBytes(invalid));
            assertTrue(failure.getMessage().contains("maxStreamBytes"));
        }
        Magika.Builder builder = Magika.builder().maxStreamBytes(1);
        try (Magika one = builder.build();
             Magika zero = builder.maxStreamBytes(0).build();
             Magika unlimited = builder.maxStreamBytes(Long.MAX_VALUE).build()) {
            builder.maxStreamBytes(2);
            assertEquals("txt", one.identify(new ByteArrayInputStream(new byte[1])).getLabel());
            limitFailure(one, new ByteArrayInputStream(new byte[2]), 1);
            assertEquals("empty", zero.identify(new ByteArrayInputStream(new byte[0])).getLabel());
            limitFailure(zero, new ByteArrayInputStream(new byte[1]), 0);
            Fixtures.assertEquivalent("Long.MAX_VALUE must not overflow during read/probe arithmetic",
                    unlimited.identify(MagikaTest.PDF), unlimited.identify(new ByteArrayInputStream(MagikaTest.PDF)));
            // A stream limit does not change byte[] identification.
            assertEquals("pdf", zero.identify(MagikaTest.PDF).getLabel());
        }
    }

    @Test
    public void readFailuresKeepTheirCauseAndCallerOwnershipIncludingAtTheLimit() {
        IOException original = new IOException("source read failed");
        for (int failAt : new int[] {0, 7, 5000, 8193}) {
            ObservedStream input = new ObservedStream(new byte[8193], 3, 1024);
            input.failureAt = failAt;
            input.failure = original;
            try (Magika sdk = Magika.builder().maxStreamBytes(8193).build()) {
                MagikaException failure = assertThrows(MagikaException.class, () -> sdk.identify(input));
                assertEquals(MagikaException.Category.INPUT, failure.getCategory());
                assertSame(original, failure.getCause());
                assertTrue(failure.getContext().contains("bytesRead=" + failAt));
                assertEquals(failAt, input.position);
                assertEquals(1, input.failures);
                assertFalse(input.eofSeen);
                assertEquals("pdf", sdk.identify(new ByteArrayInputStream(MagikaTest.PDF)).getLabel());
            }
            untouchedOwnership(input);
        }
    }

    @Test
    public void zeroLengthBulkReadsDoNotSpinOrLoseBytes() {
        try (Magika sdk = Magika.create()) {
            ObservedStream input = new ObservedStream(MagikaTest.PDF, 0, 3, 0, 1);
            Fixtures.assertEquivalent("zero-length bulk progress", sdk.identify(MagikaTest.PDF), sdk.identify(input));
            assertEquals(MagikaTest.PDF.length, input.position);
            assertTrue(input.eofSeen);
            untouchedOwnership(input);
        }
    }

    @Test
    public void nullAndClosedCallsFailBeforeReading() {
        ObservedStream input = new ObservedStream(MagikaTest.PDF, 1);
        try (Magika sdk = Magika.create()) {
            assertThrows(IllegalArgumentException.class, () -> sdk.identify((InputStream) null));
            sdk.close();
            assertThrows(IllegalStateException.class, () -> sdk.identify(input));
            assertEquals(0, input.reads);
        }
        untouchedOwnership(input);
    }

    private static void limitFailure(Magika sdk, InputStream input, long limit) {
        MagikaException failure = assertThrows(MagikaException.class, () -> sdk.identify(input));
        assertEquals(MagikaException.Category.INPUT, failure.getCategory());
        assertTrue(failure.getContext(), failure.getContext().contains("maxStreamBytes=" + limit));
        assertTrue(failure.getMessage(), failure.getMessage().contains("exceeds"));
    }

    private static void untouchedOwnership(ObservedStream input) {
        assertEquals("Caller owns close", 0, input.closes);
        assertEquals("Caller owns reset", 0, input.resets);
    }

    /** Observe the public stream boundary, without exposing SDK internals. */
    private static final class ObservedStream extends InputStream {
        private final byte[] content;
        private final int[] chunks;
        private int chunk;
        int position;
        int reads;
        int closes;
        int resets;
        int failures;
        int failureAt = Integer.MAX_VALUE;
        IOException failure;
        boolean eofSeen;
        boolean resettable;

        ObservedStream(byte[] content, int... chunks) {
            this.content = content;
            this.chunks = chunks;
        }

        private boolean atEnd() throws IOException {
            reads++;
            if (position == failureAt) {
                failures++;
                throw failure;
            }
            if (position == content.length) {
                eofSeen = true;
                return true;
            }
            return false;
        }

        @Override
        public int read() throws IOException {
            return atEnd() ? -1 : content[position++] & 255;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            if (length == 0) { return 0; }
            if (atEnd()) { return -1; }
            int count = Math.min(length, chunks[chunk++ % chunks.length]);
            count = Math.min(count, Math.min(content.length, failureAt) - position);
            System.arraycopy(content, position, target, offset, count);
            position += count;
            return count;
        }

        // available() deliberately remains zero even when bytes remain; reset is normally unsupported.
        @Override public boolean markSupported() { return resettable; }
        @Override public void close() { closes++; }
        @Override public void reset() throws IOException {
            resets++;
            if (!resettable) { throw new IOException("Stream cannot reset"); }
            position = 0;
        }
    }
}
