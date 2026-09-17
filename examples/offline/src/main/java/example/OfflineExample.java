/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package example;

import java.io.File;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import net.zerocloud.magika.DetectionResult;
import net.zerocloud.magika.Magika;
import net.zerocloud.magika.OverwriteReason;

/** Standalone consumer: depends only on the installed SDK artifact. */
public final class OfflineExample {
    public static void main(String[] args) throws Exception {
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
    }
}
