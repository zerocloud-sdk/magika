/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** A separate JVM uses only the public SDK API against the packaged JAR. */
public final class PackagedProbe {
    private static final byte[] PDF = "%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<<>>\n%%EOF\n"
            .getBytes(StandardCharsets.US_ASCII);

    public static void main(String[] args) throws Exception {
        check(Magika.class.getProtectionDomain().getCodeSource().getLocation().getPath().endsWith(".jar"),
                "Probe must consume the packaged SDK, not target/classes");
        switch (args[0]) {
            case "smoke":
                try (Magika sdk = Magika.create()) {
                    check("pdf".equals(sdk.identify(PDF).getLabel()), "PDF identification");
                    System.out.println("Packaged SDK " + sdk.getModelInfo().getSdkVersion()
                            + " on Java " + System.getProperty("java.runtime.version"));
                }
                break;
            case "failure":
                failure(MagikaException.Category.valueOf(args[1]), args[2]);
                break;
            case "large-array":
                largeArray();
                break;
            case "resources":
                resources();
                break;
            case "threads":
                threads();
                break;
            default:
                throw new AssertionError("Unknown probe " + args[0]);
        }
    }

    private static void failure(MagikaException.Category expected, String context) throws Exception {
        long before = entries("/proc/self/fd");
        for (int i = 0; i < 30; i++) {
            try (Magika unexpected = Magika.create()) {
                throw new AssertionError("Initialization unexpectedly succeeded: " + unexpected.getModelInfo());
            } catch (MagikaException e) {
                check(e.getCategory() == expected, "Failure category: " + e);
                check(e.getContext().contains(context), "Failure context: " + e);
                if (expected == MagikaException.Category.MODEL_INITIALIZATION) {
                    check(e.getCause() != null, "Native error cause must be preserved");
                }
                if (i == 0) {
                    System.out.println("Expected failure: " + e.getCategory() + ": " + e.getMessage()
                            + "; cause=" + (e.getCause() == null ? "none" : e.getCause().getClass().getName()));
                }
            }
        }
        check(entries("/proc/self/fd") <= before + 12, "Initialization failures leaked file handles");
    }

    private static void largeArray() {
        // A second full copy cannot fit alongside this array in a 96 MiB heap.
        byte[] content = new byte[64 * 1024 * 1024];
        System.arraycopy(PDF, 0, content, 0, PDF.length);
        content[content.length - 1] = 42;
        try (Magika sdk = Magika.create()) {
            DetectionResult result = sdk.identify(content);
            check(result.isModelUsed(), "Large input must perform inference");
            check(content[content.length - 1] == 42 && content[0] == '%', "Input was modified");
            System.out.println("64 MiB complete byte[] identified with max heap "
                    + Runtime.getRuntime().maxMemory() + "; no room for another whole-array copy");
        }
    }

    private static void threads() throws Exception {
        // Warm JVM and native loading before counting per-session workers.
        try (Magika warm = Magika.create()) {
            for (int i = 0; i < 100; i++) {
                warm.identify(PDF);
            }
        }
        try (Magika defaults = Magika.create()) {
            defaults.identify(PDF);
            long baseline = entries("/proc/self/task");
            try (Magika anotherDefault = Magika.builder().build()) {
                anotherDefault.identify(PDF);
                long defaultCount = entries("/proc/self/task");
                check(defaultCount <= baseline + 1, "Default intraOpThreads=1 created worker threads");
                Magika.Builder builder = Magika.builder().intraOpThreads(3);
                try (Magika three = builder.build(); Magika four = builder.intraOpThreads(4).build()) {
                    three.identify(PDF);
                    four.identify(PDF);
                    long configured = entries("/proc/self/task");
                    check(configured >= defaultCount + 5, "Configured native workers were not created");
                    System.out.println("Native thread counts: default=" + defaultCount
                            + ", intraOpThreads 3 and 4 sessions=" + configured);
                }
                check(entries("/proc/self/task") <= defaultCount + 1, "Native worker threads survived close");
                check("pdf".equals(defaults.identify(PDF).getLabel()), "Other instance close damaged shared environment");
            }
        }
    }

    private static void resources() throws Exception {
        for (int i = 0; i < 10; i++) {
            cycle();
        }
        try (Magika sdk = Magika.create()) {
            for (int i = 0; i < 1000; i++) {
                sdk.identify(PDF);
            }
        }
        System.gc();
        long before = residentKiB();
        long descriptors = entries("/proc/self/fd");
        List<Long> samples = new ArrayList<>();
        samples.add(before);
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 20; i++) {
                cycle();
            }
            try (Magika sdk = Magika.create()) {
                for (int i = 0; i < 2000; i++) {
                    sdk.identify(PDF);
                }
            }
            System.gc();
            samples.add(residentKiB());
            check(entries("/proc/self/fd") <= descriptors + 3, "File handles grow after close");
        }
        System.out.println("Post-warmup RSS KiB: " + samples + "; file handles=" + descriptors
                + " -> " + entries("/proc/self/fd") + "; 60 create/close cycles + 6000 reused-session inferences");
        check(samples.get(samples.size() - 1) <= before + 96 * 1024,
                "Native memory grows beyond a 96 MiB allocator/JVM tolerance: " + samples);
    }

    private static void cycle() {
        Magika sdk = Magika.builder().intraOpThreads(2).build();
        DetectionResult result;
        try {
            result = sdk.identify(PDF);
            try {
                sdk.identify(null);
                throw new AssertionError("Null input succeeded");
            } catch (IllegalArgumentException expected) {
                check("pdf".equals(sdk.identify(PDF).getLabel()), "Argument failure damaged instance");
            }
        } finally {
            sdk.close();
        }
        sdk.close();
        check("pdf".equals(result.getLabel()), "Closed instance invalidated result");
    }

    private static long entries(String directory) throws Exception {
        try (Stream<Path> paths = Files.list(Paths.get(directory))) {
            return paths.count();
        }
    }

    private static long residentKiB() throws Exception {
        for (String line : Files.readAllLines(Paths.get("/proc/self/status"), StandardCharsets.US_ASCII)) {
            if (line.startsWith("VmRSS:")) {
                return Long.parseLong(line.trim().split("\\s+")[1]);
            }
        }
        throw new AssertionError("VmRSS missing");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
