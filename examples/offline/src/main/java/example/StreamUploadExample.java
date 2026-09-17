/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package example;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import net.zerocloud.magika.DetectionResult;
import net.zerocloud.magika.Magika;
import net.zerocloud.magika.OverwriteReason;

/** Identify the remaining upload body; the application owns closing and replay. */
public final class StreamUploadExample {
    public static void main(String[] args) throws Exception {
        // A small in-memory request makes this example self-contained. A server can
        // supply its request stream directly without collecting the upload bytes.
        byte[] request = ("envelope\n%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<<>>\n%%EOF\n")
                .getBytes(StandardCharsets.US_ASCII);
        DetectionResult result;
        try (InputStream upload = new ByteArrayInputStream(request);
             Magika sdk = Magika.builder().maxStreamBytes(128L * 1024 * 1024).build()) {
            // The application has already consumed its envelope before identification.
            for (int i = 0; i < "envelope\n".length(); i++) {
                if (upload.read() < 0) { throw new IllegalStateException("Missing envelope"); }
            }
            result = sdk.identify(upload);
            if (upload.read() != -1) { throw new IllegalStateException("Upload not consumed to EOF"); }
            // The SDK does not close/reset upload; this application's resource block closes it.
            // Reopening or saving content for later use belongs to the caller. Configure
            // blocking read timeouts on the input source, such as the HTTP connection.
        }
        if (!"pdf".equals(result.getLabel()) || !"application/pdf".equals(result.getMimeType())
                || !result.isModelUsed() || !"pdf".equals(result.getRawPrediction().get().getLabel())
                || result.getOverwriteReason() != OverwriteReason.NONE
                || Math.abs(result.getScore() - 0.9922314882278442) > 1e-5
                || result.getScore() != result.getRawPrediction().get().getScore()) {
            throw new IllegalStateException("Unexpected stream-upload result");
        }
        System.out.println("Stream upload from current position to EOF: label=" + result.getLabel()
                + ", MIME=" + result.getMimeType() + ", score=" + result.getScore());
    }
}
