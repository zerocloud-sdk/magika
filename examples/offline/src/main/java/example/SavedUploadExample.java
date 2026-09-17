/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package example;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.zerocloud.magika.DetectionResult;
import net.zerocloud.magika.Magika;
import net.zerocloud.magika.OverwriteReason;

/** Save an upload completely, then identify the saved regular file. */
public final class SavedUploadExample {
    public static void main(String[] args) throws Exception {
        Path saved = Files.createTempFile("saved-upload-", ".bin");
        try {
            byte[] requestBody = "%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<<>>\n%%EOF\n"
                    .getBytes(StandardCharsets.US_ASCII);
            // The application owns and closes its upload stream. Files.copy closes its output handle.
            try (InputStream upload = new ByteArrayInputStream(requestBody)) {
                Files.copy(upload, saved, StandardCopyOption.REPLACE_EXISTING);
            }
            DetectionResult result;
            // A server should reuse a long-lived instance for sequential requests in this version.
            try (Magika sdk = Magika.create()) {
                result = sdk.identify(saved);
            }
            if (!"pdf".equals(result.getLabel()) || !"application/pdf".equals(result.getMimeType())
                    || !result.isModelUsed() || !"pdf".equals(result.getRawPrediction().get().getLabel())
                    || result.getOverwriteReason() != OverwriteReason.NONE
                    || Math.abs(result.getScore() - 0.9922314882278442) > 1e-5
                    || result.getScore() != result.getRawPrediction().get().getScore()) {
                throw new IllegalStateException("Unexpected saved-upload result");
            }
            System.out.println("Saved upload: bytes=" + Files.size(saved) + ", label=" + result.getLabel()
                    + ", MIME=" + result.getMimeType() + ", score=" + result.getScore());
        } finally {
            // The application owns the file; the SDK has already closed its own read handle.
            Files.deleteIfExists(saved);
        }
    }
}
