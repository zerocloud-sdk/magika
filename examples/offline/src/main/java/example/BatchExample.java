/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package example;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import net.zerocloud.magika.BatchIdentificationException;
import net.zerocloud.magika.BatchSummary;
import net.zerocloud.magika.Magika;

/** Application-owned input traversal, per-file handling and abort accounting. */
public final class BatchExample {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("batch-example-");
        Path pdf = Files.write(directory.resolve("saved.pdf"),
                "%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<<>>\n%%EOF\n"
                        .getBytes(StandardCharsets.US_ASCII));
        Path empty = Files.createFile(directory.resolve("empty"));
        Path missing = directory.resolve("missing");
        try (Magika magika = Magika.builder().batchSize(2).build()) {
            // A Files.walk(...).iterator() can be passed instead; the application
            // must close that stream and owns traversal, scheduling and recovery.
            BatchSummary summary = magika.identifyAll(Arrays.asList(pdf, missing, empty, pdf).iterator(), item -> {
                if (item.isSuccess()) {
                    System.out.println("Input " + item.getInputIndex() + ": " + item.getResult().get().getLabel());
                } else {
                    System.out.println("Input " + item.getInputIndex() + " failed: " + item.getError().get().getCategory());
                }
            });
            if (summary.getDeliveredCount() != 4 || summary.getSuccessCount() != 3 || summary.getFailureCount() != 1) {
                throw new IllegalStateException("Incorrect batch counts");
            }
            System.out.println("Batch delivered=" + summary.getDeliveredCount() + ", successful="
                    + summary.getSuccessCount() + ", fileFailures=" + summary.getFailureCount());

            IllegalStateException sinkFailure = new IllegalStateException("Example destination unavailable");
            long[] sideEffects = {0};
            try {
                magika.identifyAll(Arrays.asList(empty, pdf, missing, pdf).iterator(), item -> {
                    sideEffects[0]++; // A real sink might have written something already.
                    if (item.getInputIndex() == 2) { throw sinkFailure; }
                });
                throw new IllegalStateException("Expected an aborted callback");
            } catch (BatchIdentificationException error) {
                if (error.getStage() != BatchIdentificationException.Stage.CALLBACK
                        || error.getDeliveredCount() != 2 || error.getInputIndex().getAsLong() != 2
                        || error.getCause() != sinkFailure || sideEffects[0] != 3) {
                    throw new IllegalStateException("Incorrect abort progress", error);
                }
                System.out.println("Batch aborted: stage=" + error.getStage() + ", delivered="
                        + error.getDeliveredCount() + ", inputIndex=" + error.getInputIndex()
                        + ", callbackSideEffects=" + sideEffects[0]);
                // The first two outcomes remain valid. The throwing callback may
                // have side effects too. Reconcile the destination before deciding
                // whether/how to recover; the SDK neither retries nor rolls back.
            }
        } finally {
            Files.delete(pdf);
            Files.delete(empty);
            Files.delete(directory);
        }
    }
}
