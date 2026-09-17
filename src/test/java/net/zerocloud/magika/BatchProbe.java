/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Stream;
import static org.junit.Assert.*;

/** Independent heap-limited packaged consumer; never retains the complete input/output sequence. */
final class BatchProbe {
    static void run(String scenario) throws Exception {
        Path directory = Files.createTempDirectory("magika-batch-");
        char[] name = new char[180];
        Arrays.fill(name, 'x');
        Path rule = Files.createFile(directory.resolve(new String(name)));
        Path pdf = Files.write(directory.resolve("pdf"), MagikaTest.PDF);
        Path missing = directory.resolve("missing");
        try (Magika sdk = Magika.create()) {
            if (scenario.equals("batch-large")) { large(sdk, rule, pdf, missing); }
            else { resources(sdk, pdf, missing); }
        } finally {
            Files.delete(rule);
            Files.delete(pdf);
            Files.delete(directory);
        }
    }

    private static void large(Magika sdk, Path rule, Path pdf, Path missing) throws Exception {
        // Warm all paths and the single-model-row native allocation shape before measurement.
        sdk.identifyAll(new LazyPaths(10000, rule, pdf, missing), item -> { });
        long count = 1_000_003;
        long heap = Runtime.getRuntime().maxMemory();
        assertTrue(count * rule.toString().length() > heap * 2);
        LazyPaths paths = new LazyPaths(count, rule, pdf, missing);
        long[] delivered = {0};
        long[] successes = {0};
        long[] failures = {0};
        long[] modelUses = {0};
        BatchItemResult[] retained = {null};
        Thread caller = Thread.currentThread();
        long descriptors = entries("/proc/self/fd");
        List<Long> rss = new ArrayList<>();
        rss.add(residentKiB());
        BatchSummary summary = sdk.identifyAll(paths, item -> {
            assertSame(caller, Thread.currentThread());
            assertEquals(delivered[0]++, item.getInputIndex());
            assertTrue("Bounded lookahead", paths.consumed - delivered[0] < 32);
            if (item.isSuccess()) {
                successes[0]++;
                if (item.getResult().get().isModelUsed()) { modelUses[0]++; }
            } else {
                failures[0]++;
                assertEquals(MagikaException.Category.INPUT, item.getError().get().getCategory());
            }
            retained[0] = item;
            if (delivered[0] % 250000 == 0) {
                try { rss.add(residentKiB()); }
                catch (Exception error) { throw new AssertionError(error); }
            }
        });
        assertEquals(count, summary.getDeliveredCount());
        assertEquals(successes[0], summary.getSuccessCount());
        assertEquals(failures[0], summary.getFailureCount());
        assertEquals((count + 1023) / 1024, modelUses[0]);
        assertEquals((count + 4094) / 4096, failures[0]);
        assertEquals(count - 1, retained[0].getInputIndex());
        long after = entries("/proc/self/fd");
        assertTrue("File handles grew: " + descriptors + " -> " + after
                        + (after > descriptors + 2 ? descriptorTargets() : ""),
                after <= descriptors + 2);
        System.out.println("Lazy batch: inputs=" + count + ", batchSize=32, maxHeap=" + heap
                + ", delivered=" + summary.getDeliveredCount() + ", failures=" + failures[0]
                + ", nativeModelRows=" + modelUses[0] + ", retainedResults=1, fresh Path per input"
                + ", RSS KiB=" + rss + ", fd=" + descriptors + "->" + entries("/proc/self/fd"));
    }

    private static void resources(Magika sdk, Path pdf, Path missing) throws Exception {
        List<Path> batch = new ArrayList<>();
        for (int i = 0; i < 32; i++) { batch.add(i % 4 == 0 ? missing : pdf); }
        for (int i = 0; i < 10; i++) { cycle(sdk, batch); }
        System.gc();
        long descriptors = entries("/proc/self/fd");
        List<Long> rss = new ArrayList<>();
        rss.add(residentKiB());
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 30; i++) { cycle(sdk, batch); }
            System.gc();
            rss.add(residentKiB());
            assertTrue("Batch file handles leaked", entries("/proc/self/fd") <= descriptors + 2);
        }
        assertTrue("Native batch memory keeps growing: " + rss,
                rss.get(rss.size() - 1) <= rss.get(0) + 96 * 1024);
        System.out.println("Batch resources: 90 post-warmup cycles of success/input errors, callback failure,"
                + " iterator failure and interruption; RSS KiB=" + rss + ", fd=" + descriptors + "->"
                + entries("/proc/self/fd"));
    }

    private static void cycle(Magika sdk, List<Path> paths) {
        assertEquals(32, sdk.identifyAll(paths.iterator(), item -> { }).getDeliveredCount());
        IllegalStateException failure = new IllegalStateException("sink fault");
        BatchIdentificationException aborted = assertThrows(BatchIdentificationException.class,
                () -> sdk.identifyAll(paths.iterator(), item -> { if (item.getInputIndex() == 5) { throw failure; } }));
        assertEquals(5, aborted.getDeliveredCount());
        assertSame(failure, aborted.getCause());
        Iterator<Path> broken = new Iterator<Path>() {
            int consumed;
            @Override public boolean hasNext() { if (consumed == 33) { throw failure; } return true; }
            @Override public Path next() { consumed++; return paths.get(1); }
        };
        assertEquals(32, assertThrows(BatchIdentificationException.class,
                () -> sdk.identifyAll(broken, item -> { })).getDeliveredCount());
        try {
            assertEquals(1, assertThrows(BatchIdentificationException.class,
                    () -> sdk.identifyAll(paths.iterator(), item -> Thread.currentThread().interrupt())).getDeliveredCount());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    private static long entries(String directory) throws Exception {
        try (Stream<Path> paths = Files.list(Paths.get(directory))) { return paths.count(); }
    }

    private static String descriptorTargets() throws Exception {
        StringBuilder targets = new StringBuilder();
        try (Stream<Path> paths = Files.list(Paths.get("/proc/self/fd"))) {
            Iterator<Path> descriptors = paths.iterator();
            while (descriptors.hasNext()) {
                Path descriptor = descriptors.next();
                try {
                    targets.append("\n").append(descriptor).append(" -> ")
                            .append(Files.readSymbolicLink(descriptor));
                } catch (java.nio.file.NoSuchFileException closedDuringObservation) {
                    // A process-global background descriptor can close during observation.
                }
            }
        }
        return targets.toString();
    }

    private static long residentKiB() throws Exception {
        for (String line : Files.readAllLines(Paths.get("/proc/self/status"), StandardCharsets.US_ASCII)) {
            if (line.startsWith("VmRSS:")) { return Long.parseLong(line.trim().split("\\s+")[1]); }
        }
        throw new AssertionError("VmRSS missing");
    }

    private static final class LazyPaths implements Iterator<Path> {
        private final long count;
        private final String rule;
        private final String pdf;
        private final String missing;
        private long consumed;

        LazyPaths(long count, Path rule, Path pdf, Path missing) {
            this.count = count;
            this.rule = rule.toString();
            this.pdf = pdf.toString();
            this.missing = missing.toString();
        }
        @Override public boolean hasNext() { return consumed < count; }
        @Override public Path next() {
            if (!hasNext()) { throw new NoSuchElementException(); }
            long index = consumed++;
            return Paths.get(index % 1024 == 0 ? pdf : index % 4096 == 1 ? missing : rule);
        }
    }
}
