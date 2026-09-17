/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

import java.io.InputStream;
import java.util.Random;

/** Test source with a fixed 4096-byte pattern, regardless of its declared length. */
final class GeneratedStream extends InputStream {
    private final byte[] pattern = new byte[4096];
    private final long length;
    long consumed;
    boolean eofSeen;
    int closes;
    int resets;

    GeneratedStream(long length) {
        this.length = length;
        new Random(5).nextBytes(pattern);
    }

    @Override
    public int read() {
        if (consumed == length) {
            eofSeen = true;
            return -1;
        }
        return pattern[(int) (consumed++ % pattern.length)] & 255;
    }

    @Override
    public int read(byte[] target, int offset, int count) {
        if (count == 0) { return 0; }
        if (consumed == length) {
            eofSeen = true;
            return -1;
        }
        int read = (int) Math.min(count, length - consumed);
        int copied = 0;
        while (copied < read) {
            int start = (int) (consumed % pattern.length);
            int part = Math.min(read - copied, pattern.length - start);
            System.arraycopy(pattern, start, target, offset + copied, part);
            copied += part;
            consumed += part;
        }
        return read;
    }

    @Override public void close() { closes++; }
    @Override public void reset() { resets++; throw new AssertionError("Generated stream cannot reset"); }
}
