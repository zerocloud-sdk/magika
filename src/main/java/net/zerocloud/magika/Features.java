/*
 * Copyright 2026 ZeroCloud SDK contributors.
 * Adapted from Magika, Copyright 2024 Google LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package net.zerocloud.magika;

import java.util.Arrays;

/** The restricted feature extraction boundary, also used for upstream fixtures. */
final class Features {
    private Features() { }

    static int[] extract(byte[] content, int headSize, int tailSize, int window, int padding) {
        return extract(InputSample.fromBytes(content, window), headSize, tailSize, padding);
    }

    static int[] extract(InputSample sample, int headSize, int tailSize, int padding) {
        int[] features = new int[headSize + tailSize];
        Arrays.fill(features, padding);
        int headLimit = sample.head.limit();
        int headStart = 0;
        while (headStart < headLimit && isWhitespace(sample.head.get(headStart))) {
            headStart++;
        }
        int headCount = Math.min(headSize, headLimit - headStart);
        for (int i = 0; i < headCount; i++) {
            features[i] = sample.head.get(headStart + i) & 0xff;
        }

        int tailLimit = sample.tail.limit();
        while (tailLimit > 0 && isWhitespace(sample.tail.get(tailLimit - 1))) {
            tailLimit--;
        }
        int tailCount = Math.min(tailSize, tailLimit);
        for (int i = 0; i < tailCount; i++) {
            features[headSize + tailSize - tailCount + i] = sample.tail.get(tailLimit - tailCount + i) & 0xff;
        }
        return features;
    }

    // Python bytes.lstrip/rstrip recognize exactly these six ASCII bytes.
    private static boolean isWhitespace(byte value) {
        return value == 0x20 || (value >= 0x09 && value <= 0x0d);
    }
}
