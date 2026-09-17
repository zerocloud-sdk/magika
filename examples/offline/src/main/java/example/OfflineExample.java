/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package example;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.security.MessageDigest;
import net.zerocloud.magika.DetectionResult;
import net.zerocloud.magika.Magika;
import net.zerocloud.magika.OverwriteReason;
import net.zerocloud.magika.PredictionMode;

/** Standalone consumer: depends only on the installed SDK artifact. */
public final class OfflineExample {
    public static void main(String[] args) throws Exception {
        Path sdk = new File(Magika.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toPath();
        if (!Files.isRegularFile(sdk) || !sdk.toString().endsWith(".jar")) {
            throw new IllegalStateException("SDK must come from a dependency JAR: " + sdk);
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(sdk)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) { digest.update(buffer, 0, count); }
        }
        StringBuilder actual = new StringBuilder();
        for (byte value : digest.digest()) { actual.append(String.format("%02x", value & 255)); }
        String expected = System.getProperty("expected.sdk.sha256");
        if (expected != null && !expected.equals(actual.toString())) {
            throw new IllegalStateException("SDK differs from the verified candidate");
        }
        System.out.println("SDK origin=" + sdk + "; SHA-256=" + actual);
        if (args.length > 0 && "--check-isolated".equals(args[0])) {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isUp() && !iface.isLoopback()) {
                    throw new IllegalStateException("Network is available: " + iface.getName());
                }
            }
            if (!"/jdk/bin".equals(System.getenv("PATH")) || new File("/usr/bin").exists()) {
                throw new IllegalStateException("Expected isolated Java-only filesystem");
            }
            System.out.println("Isolation verified: no external network interface; Java-only root and PATH");
        }
        byte[] content = "%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<<>>\n%%EOF\n"
                .getBytes(StandardCharsets.US_ASCII);
        DetectionResult result;
        try (Magika magika = Magika.create()) {
            result = magika.identify(content);
            if (!"pdf".equals(result.getLabel()) || !"application/pdf".equals(result.getMimeType())
                    || !result.isModelUsed() || !"pdf".equals(result.getRawPrediction().get().getLabel())
                    || result.getOverwriteReason() != OverwriteReason.NONE) {
                throw new IllegalStateException("Unexpected PDF identification result");
            }
            System.out.println("SDK " + magika.getModelInfo().getSdkVersion() + "; model "
                    + magika.getModelInfo().getModelVersion() + "; Java " + System.getProperty("java.runtime.version"));
        }
        System.out.println(result.getLabel() + " " + result.getMimeType() + " score=" + result.getScore());
        SavedUploadExample.main(new String[0]);
        StreamUploadExample.main(new String[0]);
        BatchExample.main(new String[0]);
        SharedInstanceExample.main(new String[0]);

        byte[] ambiguousContent = {0, 1, 2, 3, 4, 5, 6, 7};
        for (PredictionMode mode : PredictionMode.values()) {
            try (Magika magika = Magika.builder().predictionMode(mode).build()) {
                DetectionResult prediction = magika.identify(ambiguousContent);
                boolean bestGuess = mode == PredictionMode.BEST_GUESS;
                String expectedLabel = bestGuess ? "wasm" : "unknown";
                String expectedMime = bestGuess ? "application/wasm" : "application/octet-stream";
                OverwriteReason expectedReason = bestGuess ? OverwriteReason.NONE : OverwriteReason.LOW_CONFIDENCE;
                if (!expectedLabel.equals(prediction.getLabel()) || !expectedMime.equals(prediction.getMimeType())
                        || !prediction.isModelUsed() || !"wasm".equals(prediction.getRawPrediction().get().getLabel())
                        || prediction.getOverwriteReason() != expectedReason
                        || Math.abs(prediction.getScore() - 0.3142167329788208) > 1e-5
                        || prediction.getScore() != prediction.getRawPrediction().get().getScore()) {
                    throw new IllegalStateException("Unexpected identification result for " + mode);
                }
                System.out.println(mode + ": raw=" + prediction.getRawPrediction().get().getLabel()
                        + ", final=" + prediction.getLabel() + ", score=" + prediction.getScore()
                        + ", reason=" + prediction.getOverwriteReason());
            }
        }
    }
}
