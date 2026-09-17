/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

/** A generated stream, real ORT and the packaged SDK in a killable, heap-limited JVM. */
final class StreamProbe {
    static void run() {
        long length = (2L << 30) + 17;
        long maxHeap = Runtime.getRuntime().maxMemory();
        check(length > maxHeap && length > Integer.MAX_VALUE, "Input must exceed actual heap and int range");
        GeneratedStream input = new GeneratedStream(length);
        // The generator repeats every 4096 bytes. This small complete input has
        // the same first and last windows, including the unaligned final 17 bytes.
        byte[] representative = new byte[8192 + (int) (length % 4096)];
        GeneratedStream small = new GeneratedStream(representative.length);
        check(small.read(representative, 0, representative.length) == representative.length, "Representative fixture");
        DetectionResult actual;
        try (Magika sdk = Magika.builder().maxStreamBytes(length).build()) {
            actual = sdk.identify(input);
            check(actual.isModelUsed(), "Large stream must execute real ORT");
            Fixtures.assertEquivalent("Large generated stream", sdk.identify(representative), actual);
            check(input.consumed == length && input.eofSeen, "Entire stream must be consumed through EOF");
        }
        check(input.closes == 0 && input.resets == 0, "Stream ownership must remain with the caller");
        System.out.println("Large generated stream: bytes=" + input.consumed + ", maxHeap=" + maxHeap
                + ", maxStreamBytes=" + length + ", eof=" + input.eofSeen + ", label=" + actual.getLabel()
                + ", MIME=" + actual.getMimeType() + ", raw=" + actual.getRawPrediction().get().getLabel()
                + ", score=" + actual.getScore() + ", reason=" + actual.getOverwriteReason()
                + ", modelUsed=" + actual.isModelUsed() + ", Java=" + System.getProperty("java.runtime.version"));
    }

    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
