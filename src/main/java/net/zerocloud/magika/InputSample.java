/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;

/** Bounded head/tail views and the complete input length. */
final class InputSample {
    final ByteBuffer head;
    final ByteBuffer tail;
    final long length;

    private InputSample(ByteBuffer head, ByteBuffer tail, long length) {
        this.head = head;
        this.tail = tail;
        this.length = length;
    }

    static InputSample fromBytes(byte[] content, int window) {
        int count = Math.min(content.length, window);
        return new InputSample(ByteBuffer.wrap(content, 0, count).slice(),
                ByteBuffer.wrap(content, content.length - count, count).slice(), content.length);
    }

    static InputSample fromStream(InputStream input, int window, long maxBytes) {
        byte[] head = new byte[window];
        byte[] tail = new byte[window];
        byte[] buffer = new byte[window];
        int headCount = 0;
        int tailEnd = 0;
        long length = 0;
        try {
            while (length < maxBytes) {
                // Subtract before narrowing; never compute maxBytes + 1, even for the EOF probe.
                int wanted = (int) Math.min(buffer.length, maxBytes - length);
                int read = input.read(buffer, 0, wanted);
                if (read == 0) {
                    // Avoid spinning on a stream that makes no progress in a bulk read.
                    int value = input.read();
                    if (value < 0) { break; }
                    buffer[0] = (byte) value;
                    read = 1;
                }
                if (read < 0) { break; }
                int toHead = Math.min(read, window - headCount);
                System.arraycopy(buffer, 0, head, headCount, toHead);
                headCount += toHead;
                // A circular tail keeps short reads linear in the number of bytes consumed.
                int first = Math.min(read, window - tailEnd);
                System.arraycopy(buffer, 0, tail, tailEnd, first);
                System.arraycopy(buffer, first, tail, 0, read - first);
                tailEnd = (tailEnd + read) % window;
                length += read;
            }
            if (length == maxBytes && input.read() != -1) {
                throw new MagikaException(MagikaException.Category.INPUT,
                        "InputStream, maxStreamBytes=" + maxBytes,
                        "Stream exceeds configured byte limit", null);
            }
        } catch (IOException | SecurityException failure) {
            throw new MagikaException(MagikaException.Category.INPUT,
                    "InputStream, bytesRead=" + length + ", maxStreamBytes=" + maxBytes,
                    "Cannot read stream", failure);
        }
        ByteBuffer first = ByteBuffer.wrap(head, 0, headCount).slice();
        if (length <= window) {
            return new InputSample(first, first, length);
        }
        // Reuse the work buffer to put the circular tail in content order at EOF.
        System.arraycopy(tail, tailEnd, buffer, 0, window - tailEnd);
        System.arraycopy(tail, 0, buffer, window - tailEnd, tailEnd);
        return new InputSample(first, ByteBuffer.wrap(buffer), length);
    }

    static InputSample fromPath(Path path, int window) {
        try {
            // Follow links, but reject special files before any content open (notably FIFOs).
            if (!Files.readAttributes(path, BasicFileAttributes.class).isRegularFile()) {
                throw new IOException("Input is not a regular file");
            }
            try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
                long length = channel.size();
                int count = (int) Math.min(length, window);
                ByteBuffer head = readWindow(channel, 0, count);
                ByteBuffer tail = length <= window ? head : readWindow(channel, length - count, count);
                if (channel.size() != length) {
                    throw new IOException("File size changed while sampling");
                }
                return new InputSample(head, tail, length);
            }
        } catch (IOException | SecurityException failure) {
            throw new MagikaException(MagikaException.Category.INPUT, path.toString(),
                    "Cannot read regular file", failure);
        }
    }

    private static ByteBuffer readWindow(SeekableByteChannel channel, long offset, int count) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(count);
        channel.position(offset);
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer);
            if (read < 0) {
                throw new EOFException("File ended before the sampled window at byte " + (offset + buffer.position()));
            }
            if (read == 0) {
                throw new IOException("File read made no progress at byte " + (offset + buffer.position()));
            }
        }
        buffer.flip();
        return buffer;
    }
}
