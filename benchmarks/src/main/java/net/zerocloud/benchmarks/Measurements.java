/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.benchmarks;

import com.google.gson.GsonBuilder;
import java.io.BufferedWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.zerocloud.magika.ModelInfo;

/** Out-of-band Linux process observations and exact, finite-run timing summaries. */
final class Measurements implements AutoCloseable {
    private final BufferedWriter resources;
    private final long origin = System.nanoTime();

    Measurements(Path output) throws Exception {
        Files.createDirectories(output);
        resources = Files.newBufferedWriter(output.resolve("resources.csv"), StandardCharsets.UTF_8);
        resources.write("stage,completed_units,elapsed_ns,heap_used_bytes,heap_committed_bytes,heap_max_bytes,nonheap_used_bytes,rss_kib,rss_anon_kib,rss_file_kib,fd_count,gc_count,gc_ms\n");
        sample("before_sdk", 0);
    }

    void sample(String stage, long completed) throws Exception {
        Map<String, Long> status = new LinkedHashMap<>();
        for (String line : Files.readAllLines(Paths.get("/proc/self/status"), StandardCharsets.US_ASCII)) {
            String[] parts = line.trim().split("\\s+");
            if (parts[0].equals("VmRSS:") || parts[0].equals("RssAnon:") || parts[0].equals("RssFile:")) {
                status.put(parts[0], Long.parseLong(parts[1]));
            }
        }
        long descriptors;
        try (Stream<Path> paths = Files.list(Paths.get("/proc/self/fd"))) { descriptors = paths.count(); }
        long collections = 0;
        long gcMillis = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            collections += gc.getCollectionCount();
            gcMillis += gc.getCollectionTime();
        }
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        resources.write(stage + "," + completed + "," + (System.nanoTime() - origin) + ","
                + heap.getUsed() + "," + heap.getCommitted() + "," + heap.getMax() + ","
                + ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed() + ","
                + status.get("VmRSS:") + "," + status.get("RssAnon:") + "," + status.get("RssFile:") + ","
                + descriptors + "," + collections + "," + gcMillis + "\n");
        resources.flush();
    }

    static Map<String, Object> metadata(ModelInfo model, Map<String, Object> configuration) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reportFormat", "magika-performance-v1");
        data.put("configuration", configuration);
        data.put("javaRuntime", System.getProperty("java.runtime.version"));
        data.put("javaVendor", System.getProperty("java.vendor"));
        data.put("javaHome", System.getProperty("java.home"));
        data.put("jvmArguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
        data.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        data.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        data.put("nativeOrtVersion", ai.onnxruntime.OrtEnvironment.getEnvironment().getVersion());
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("sdkVersion", model.getSdkVersion());
        provenance.put("modelVersion", model.getModelVersion());
        provenance.put("upstreamCommit", model.getUpstreamCommit());
        provenance.put("assetDigests", model.getAssetDigests());
        data.put("model", provenance);
        return data;
    }

    static void json(Path path, Object value) throws Exception {
        try (BufferedWriter output = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(value, output);
            output.write('\n');
        }
    }

    static Map<String, Object> distribution(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        long sum = 0;
        for (long value : sorted) { sum = Math.addExact(sum, value); }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("samples", sorted.length);
        stats.put("meanNs", (double) sum / sorted.length);
        stats.put("minNs", sorted[0]);
        stats.put("p50Ns", sorted[(int) Math.ceil(sorted.length * 0.50) - 1]);
        stats.put("p95Ns", sorted[(int) Math.ceil(sorted.length * 0.95) - 1]);
        stats.put("p99Ns", sorted[(int) Math.ceil(sorted.length * 0.99) - 1]);
        stats.put("maxNs", sorted[sorted.length - 1]);
        return stats;
    }

    static void summarize(Path output, List<IdentificationBenchmark.Round> rounds, boolean batch) throws Exception {
        long wall = 0;
        long model = 0;
        long rule = 0;
        long checksum = 0;
        long[] latencies = new long[rounds.size() * rounds.get(0).latencies.length];
        long[] calls = new long[rounds.size()];
        for (int i = 0; i < rounds.size(); i++) {
            IdentificationBenchmark.Round round = rounds.get(i);
            wall += round.wallNs;
            model += round.model;
            rule += round.rule;
            checksum += round.checksum;
            System.arraycopy(round.latencies, 0, latencies, i * round.latencies.length, round.latencies.length);
            calls[i] = round.wallNs;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("items", latencies.length);
        summary.put("modelResults", model);
        summary.put("ruleResults", rule);
        summary.put("failures", 0); // Any unexpected error aborts without a successful summary.
        summary.put("checksum", checksum);
        summary.put("totalMeasuredWallNs", wall);
        summary.put("throughputItemsPerSecond", latencies.length * 1e9 / wall);
        summary.put(batch ? "amortizedBatchNsPerItem" : "amortizedWallNsPerItem", (double) wall / latencies.length);
        summary.put(batch ? "itemDeliveryLatency" : "requestLatency", distribution(latencies));
        summary.put(batch ? "batchCallLatency" : "roundWallTime", distribution(calls));
        json(output.resolve("summary.json"), summary);
    }

    @Override public void close() throws java.io.IOException { resources.close(); }
}
