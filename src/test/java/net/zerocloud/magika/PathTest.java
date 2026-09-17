/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class PathTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void pathAndBytesShareFeaturesAndResultsAtContentBoundaries() throws Exception {
        List<byte[]> cases = Fixtures.boundaryContents();
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
