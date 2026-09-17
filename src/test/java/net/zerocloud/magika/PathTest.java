/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class PathTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void pathAndBytesShareFeaturesAndResultsAtContentBoundaries() throws Exception {
        List<byte[]> cases = new ArrayList<>();
        for (int size : new int[] {0, 1, 7, 8, 1023, 1024, 1025, 2047, 2048, 2049,
                4095, 4096, 4097, 8191, 8192, 8193, 32768}) {
            byte[] content = new byte[size];
            for (int i = 0; i < size; i++) { content[i] = (byte) (128 + i % 128); }
            cases.add(content);
        }
        for (int spaces : new int[] {0, 1, 1023, 1024, 1025, 4088, 4089, 4094, 4095, 4096, 4097, 8192}) {
            byte[] content = new byte[spaces * 2 + 8];
            Arrays.fill(content, (byte) ' ');
            System.arraycopy(MagikaTest.PDF, 0, content, spaces, 8);
            cases.add(content);
        }
        byte[][] utf8 = {{0}, {(byte) 0xc2, (byte) 0x80}, {(byte) 0xe0, (byte) 0xa0, (byte) 0x80},
                {(byte) 0xf4, (byte) 0x8f, (byte) 0xbf, (byte) 0xbf}, {(byte) 0x80}, {(byte) 0xc0, (byte) 0x80},
                {(byte) 0xed, (byte) 0xa0, (byte) 0x80}, {(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80},
                {(byte) 0xff}, {(byte) 0xf0, (byte) 0x9f, (byte) 0x92}, {(byte) 0xc2, 32}};
        cases.addAll(Arrays.asList(utf8));
        for (byte[] sequence : utf8) {
            // Exercise both complete and split UTF-8 at the first window's boundary.
            for (int end : new int[] {4096, 4097}) {
                byte[] content = new byte[20000];
                byte[] spaces = {9, 10, 11, 12, 13, 32};
                for (int i = 0; i < content.length; i++) { content[i] = spaces[i % spaces.length]; }
                System.arraycopy(sequence, 0, content, end - sequence.length, sequence.length);
                content[10000] = (byte) 0xff;
                cases.add(content);
            }
        }
        byte[] whitespace = new byte[20000];
        Arrays.fill(whitespace, (byte) ' ');
        cases.add(whitespace);
        Path file = temporary.newFile().toPath();
        int comparisons = 0;
        for (PredictionMode mode : PredictionMode.values()) {
            try (Magika sdk = Magika.builder().predictionMode(mode).build()) {
                for (int i = 0; i < cases.size(); i++) {
                    byte[] content = cases.get(i);
                    Files.write(file, content);
                    String message = mode + " boundary #" + i + " length=" + content.length;
                    Fixtures.assertEquivalent(message, sdk.identify(content), sdk.identify(file));
                    // The existing restricted feature contract, with real file sampling as its input.
                    assertArrayEquals(message, Features.extract(content, 1024, 1024, 4096, 256),
                            Features.extract(InputSample.fromPath(file, 4096), 1024, 1024, 256));
                    comparisons++;
                }
            }
        }
        System.out.println("Path/byte[] exact feature and full-result boundary comparisons: " + comparisons);
    }

    @Test
    public void nullClosedAndLinkedPathsFollowThePublicContract() throws Exception {
        Path file = temporary.newFile().toPath();
        Files.write(file, MagikaTest.PDF);
        Path link = temporary.getRoot().toPath().resolve("link");
        Files.createSymbolicLink(link, file);
        Magika sdk = Magika.create();
        DetectionResult retained;
        try {
            assertThrows(IllegalArgumentException.class, () -> sdk.identify((Path) null));
            retained = sdk.identify(link);
            Fixtures.assertEquivalent("symbolic link", sdk.identify(file), retained);
        } finally {
            sdk.close();
        }
        assertThrows(IllegalStateException.class, () -> sdk.identify(file));
        assertThrows(IllegalStateException.class, () -> sdk.identify(file.resolveSibling("missing")));
        assertEquals("pdf", retained.getLabel());
        assertEquals("pdf", retained.getRawPrediction().get().getLabel());
    }
}
