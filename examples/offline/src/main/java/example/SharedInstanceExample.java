/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package example;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.zerocloud.magika.DetectionResult;
import net.zerocloud.magika.Magika;

/** One application-owned instance serves many requests, then closes at shutdown. */
public final class SharedInstanceExample {
    public static void main(String[] args) throws Exception {
        byte[] content = "%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<<>>\n%%EOF\n"
                .getBytes(StandardCharsets.US_ASCII);
        DetectionResult retained = null;
        // In a service, create this once at startup and close it at shutdown.
        try (Magika shared = Magika.create()) {
            // Scheduling belongs to the application; the SDK creates no pool.
            ExecutorService requests = Executors.newFixedThreadPool(4);
            try {
                List<Future<DetectionResult>> results = new ArrayList<>();
                for (int request = 0; request < 16; request++) {
                    results.add(requests.submit(() -> shared.identify(content)));
                }
                requests.shutdown(); // Stop accepting application tasks.
                // Drain queued tasks too: SDK admission starts inside identify,
                // not when the application puts a task on an executor queue.
                for (Future<DetectionResult> future : results) {
                    retained = future.get();
                    if (!"pdf".equals(retained.getLabel())) {
                        throw new IllegalStateException("Unexpected shared-instance result");
                    }
                }
            } finally {
                // On failure, cancel queued tasks and request interruption of workers.
                // The resource block below still waits for accepted SDK calls.
                requests.shutdownNow();
            }
        } // close waits for accepted calls, then releases Session and SessionOptions.
        // Blocking streams need source timeouts; callbacks must finish themselves.
        // Never close from a callback or wait there for another thread to close.
        System.out.println("Shared instance: 16 requests, 4 application workers; after close=" + retained.getLabel());
    }
}
