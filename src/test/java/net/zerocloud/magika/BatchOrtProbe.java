/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.zerocloud.magika.BatchIdentificationException.Stage;
import static org.junit.Assert.*;

/** Runs only in a fork with the test-only ORT boundary agent installed. */
final class BatchOrtProbe {
    static void run() throws Exception {
        Path directory = Files.createTempDirectory("magika-batch-ort-");
        Path pdf = Files.write(directory.resolve("pdf"), MagikaTest.PDF);
        Path empty = Files.createFile(directory.resolve("empty"));
        Path missing = directory.resolve("missing");
        try (Magika sdk = Magika.builder().batchSize(4).build()) {
            assertTrue("ORT run boundary agent installed", OrtProbeAgent.installed);
            List<Path> paths = Arrays.asList(pdf, empty, missing, pdf, empty, missing, empty, empty, pdf);
            List<BatchItemResult> items = new ArrayList<>();
            OrtProbeAgent.reset();
            BatchSummary summary = sdk.identifyAll(paths.iterator(), item -> {
                assertResourcesClosed();
                assertEquals(items.size(), item.getInputIndex());
                assertSame(paths.get(items.size()), item.getPath());
                items.add(item);
            });
            assertEquals(9, summary.getDeliveredCount());
            assertEquals(2, summary.getFailureCount());
            assertEquals(2, OrtProbeAgent.inputs.size());
            assertArrayEquals(new long[] {2, 2048}, OrtProbeAgent.inputs.get(0).getInfo().getShape());
            assertArrayEquals(new long[] {1, 2048}, OrtProbeAgent.inputs.get(1).getInfo().getShape());
            assertEquals(2, OrtProbeAgent.outputs.size());
            System.out.println("Observed real ORT batches: [2,2048], no call for rule/error-only batch, [1,2048]; ordered 9 items");

            for (boolean singleModelRow : new boolean[] {false, true}) {
                List<Path> failing = Arrays.asList(pdf, empty, missing, pdf, empty, missing,
                        pdf, singleModelRow ? empty : pdf, pdf);
                try (OnnxTensor wrongShape = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(),
                        IntBuffer.wrap(new int[] {0}), new long[] {1, 1})) {
                    OrtProbeAgent.reset();
                    OrtProbeAgent.failOnRun = 2;
                    OrtProbeAgent.replacement = wrongShape;
                    items.clear();
                    BatchIdentificationException error = assertThrows(BatchIdentificationException.class,
                            () -> sdk.identifyAll(failing.iterator(), items::add));
                    assertEquals(Stage.IDENTIFICATION, error.getStage());
                    assertEquals(4, error.getDeliveredCount());
                    assertEquals(4, items.size());
                    assertEquals(singleModelRow, error.getInputIndex().isPresent());
                    if (singleModelRow) { assertEquals(6, error.getInputIndex().getAsLong()); }
                    assertTrue(error.getCause() instanceof MagikaException);
                    assertEquals(MagikaException.Category.INFERENCE, ((MagikaException) error.getCause()).getCategory());
                    assertTrue(error.getCause().getCause() instanceof OrtException);
                    assertTrue(error.getCause().getCause().getMessage().contains("Got invalid dimensions"));
                    assertEquals(2, OrtProbeAgent.inputs.size());
                    assertEquals(1, OrtProbeAgent.outputs.size());
                    assertResourcesClosed();
                    System.out.println("Native inference abort: delivered=4, knownIndex=" + error.getInputIndex()
                            + ", cause=" + error.getCause().getCause());
                } finally { OrtProbeAgent.reset(); }
            }
            RuntimeException cause = new IllegalStateException("sink failed after inference");
            BatchIdentificationException error = assertThrows(BatchIdentificationException.class,
                    () -> sdk.identifyAll(paths.iterator(), item -> { assertResourcesClosed(); throw cause; }));
            assertEquals(Stage.CALLBACK, error.getStage());
            assertSame(cause, error.getCause());
            assertResourcesClosed();
            OrtProbeAgent.reset();
            assertEquals("pdf", sdk.identify(pdf).getLabel());
            assertResourcesClosed();
        } finally {
            OrtProbeAgent.reset();
            Files.delete(pdf);
            Files.delete(empty);
            Files.delete(directory);
        }
    }

    private static void assertResourcesClosed() {
        for (OnnxTensor input : OrtProbeAgent.inputs) { assertTrue("Input tensor closed", input.isClosed()); }
        for (OrtSession.Result output : OrtProbeAgent.outputs) {
            assertThrows(IllegalStateException.class, () -> output.get(0));
        }
    }
}
