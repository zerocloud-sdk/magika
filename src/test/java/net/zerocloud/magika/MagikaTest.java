/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

import ai.onnxruntime.OrtEnvironment;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class MagikaTest {
    static final byte[] PDF = "%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<<>>\n%%EOF\n"
            .getBytes(StandardCharsets.US_ASCII);

    @Test
    public void runtimeIsTheSelectedJvmAndOfficialOrtReallyRuns() throws Exception {
        File expectedHome = new File(System.getProperty("expected.java.home")).getCanonicalFile();
        File actualHome = new File(System.getProperty("java.home")).getCanonicalFile();
        assertTrue(actualHome.equals(expectedHome) || actualHome.equals(new File(expectedHome, "jre")));
        System.out.println("TEST JVM: " + System.getProperty("java.runtime.version") + "; "
                + System.getProperty("java.vendor") + "; " + actualHome + "; "
                + System.getProperty("os.name") + "/" + System.getProperty("os.arch"));
        assertEquals("1.30.0", OrtEnvironment.getEnvironment().getVersion());
        try (Magika magika = Magika.create()) {
            DetectionResult result = magika.identify(PDF);
            assertTrue(result.isModelUsed());
            assertEquals("pdf", result.getLabel());
            assertEquals("application/pdf", result.getMimeType());
            System.out.println("Native ORT: " + OrtEnvironment.getEnvironment().getVersion()
                    + "; actual inference: " + result.getLabel() + "/" + result.getScore());
        }
    }

    @Test
    public void resultsAndProvenanceSurviveIdempotentSequentialClose() {
        Magika magika = Magika.builder().intraOpThreads(2).build();
        DetectionResult result;
        ModelInfo info;
        try {
            result = magika.identify(PDF);
            info = magika.getModelInfo();
        } finally {
            magika.close();
        }
        magika.close();
        assertThrows(IllegalStateException.class, () -> magika.identify(PDF));
        assertEquals("pdf", result.getLabel());
        assertEquals("pdf", result.getRawPrediction().get().getLabel());
        assertEquals("0.1.0", info.getSdkVersion());
        assertEquals("standard_v3_3", info.getModelVersion());
        assertEquals("9f225aa480e675af44343b9160f077073ed1b752", info.getUpstreamCommit());
        assertEquals(3, info.getAssetDigests().size());
        assertEquals("fe2d2eb49c5f88a9e0a6c048e15d6ffdf86235519c2afc535044de433169ec8c",
                info.getAssetDigests().get("model.onnx"));
        assertThrows(UnsupportedOperationException.class, () -> info.getAssetDigests().clear());
        assertSame(info, magika.getModelInfo());
        try (Magika next = Magika.create()) {
            assertEquals(result.getLabel(), next.identify(PDF).getLabel());
        }
    }

    @Test
    public void invalidArgumentsAreNotIdentificationResults() {
        assertThrows(IllegalArgumentException.class, () -> Magika.builder().intraOpThreads(0));
        assertThrows(IllegalArgumentException.class, () -> Magika.builder().intraOpThreads(-1));
        try (Magika magika = Magika.create()) {
            assertThrows(IllegalArgumentException.class, () -> magika.identify(null));
            assertEquals("pdf", magika.identify(PDF).getLabel());
        }
    }

    @Test
    public void rulesUseStrictUtf8AndKeepUnknownAndEmptySuccessful() {
        byte[][] valid = {new byte[] {0}, "hi".getBytes(StandardCharsets.UTF_8),
                "你好".getBytes(StandardCharsets.UTF_8), {(byte) 0xf4, (byte) 0x8f, (byte) 0xbf, (byte) 0xbf},
                {(byte) 0xc2, (byte) 0x80}, {(byte) 0xe0, (byte) 0xa0, (byte) 0x80}};
        byte[][] invalid = {{(byte) 0x80}, {(byte) 0xc0, (byte) 0x80}, {(byte) 0xc1, (byte) 0xbf},
                {(byte) 0xe0, (byte) 0x80, (byte) 0x80}, {(byte) 0xed, (byte) 0xa0, (byte) 0x80},
                {(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80}, {(byte) 0xff},
                {(byte) 0xf0, (byte) 0x9f, (byte) 0x92}, {(byte) 0xc2, 0x20}};
        try (Magika magika = Magika.create()) {
            rule(magika.identify(new byte[0]), "empty", "inode/x-empty");
            for (byte[] input : valid) {
                rule(magika.identify(input), "txt", "text/plain");
            }
            for (byte[] input : invalid) {
                rule(magika.identify(input), "unknown", "application/octet-stream");
            }
            byte[] whitespace = new byte[20000];
            byte[] spaces = {9, 10, 11, 12, 13, 32};
            for (int i = 0; i < whitespace.length; i++) {
                whitespace[i] = spaces[i % spaces.length];
            }
            rule(magika.identify(whitespace), "txt", "text/plain");
            // Upstream decides from the first window when it lacks eight meaningful bytes.
            whitespace[10000] = (byte) 0xff;
            rule(magika.identify(whitespace), "txt", "text/plain");
            whitespace[4095] = (byte) 0xc2;
            rule(magika.identify(whitespace), "unknown", "application/octet-stream");
        }
    }

    @Test
    public void minimumEightBytesAndInputOwnershipAreObservable() {
        try (Magika magika = Magika.create()) {
            assertFalse(magika.identify(new byte[7]).isModelUsed());
            assertTrue(magika.identify(new byte[8]).isModelUsed());
            byte[] input = Arrays.copyOf(PDF, PDF.length);
            DetectionResult first = magika.identify(input);
            assertArrayEquals(PDF, input);
            Arrays.fill(input, (byte) 0xff);
            assertEquals("pdf", first.getLabel());
            assertEquals("pdf", first.getRawPrediction().get().getLabel());
        }
    }

    private static void rule(DetectionResult result, String label, String mime) {
        assertEquals(label, result.getLabel());
        assertEquals(mime, result.getMimeType());
        assertFalse(result.isModelUsed());
        assertFalse(result.getRawPrediction().isPresent());
        assertEquals(OverwriteReason.NONE, result.getOverwriteReason());
        assertEquals(1.0, result.getScore(), 0);
    }
}
