/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.benchmarks;

import java.io.BufferedWriter;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.zerocloud.magika.BatchSummary;
import net.zerocloud.magika.DetectionResult;
import net.zerocloud.magika.Magika;
import net.zerocloud.magika.PredictionMode;

/** Measures complete identification through an installed SDK JAR's public API. */
public final class IdentificationBenchmark {
    public static void main(String[] args) throws Exception {
        Corpus.require(args.length == 9, "repository output scenario warmup rounds repeats batchSize intraOpThreads callers");
        Path repository = Paths.get(args[0]).toAbsolutePath();
        Path output = Paths.get(args[1]);
        String scenario = args[2];
        Corpus.require(Arrays.asList("path", "stream", "batch", "shared").contains(scenario), "Unknown scenario");
        int warmup = positive(args[3]);
        int rounds = positive(args[4]);
        int repeats = positive(args[5]);
        int batchSize = positive(args[6]);
        int threads = positive(args[7]);
        int callers = scenario.equals("shared") ? positive(args[8]) : 1;
        int items = Math.multiplyExact(69, repeats);
        // Finite primitive timing storage is separate from the bounded SDK/resource probes.
        Corpus.require((long) items * rounds <= 1_000_000 && callers <= items, "Measurement sample budget");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("scenario", scenario);
        config.put("warmupRounds", warmup);
        config.put("measurementRounds", rounds);
        config.put("corpusRepeatsPerRound", repeats);
        config.put("itemsPerRound", items);
        config.put("batchSize", batchSize);
        config.put("intraOpThreads", threads);
        config.put("callers", callers);
        config.put("predictionMode", "HIGH_CONFIDENCE");
        config.put("maxStreamBytes", 67_108_864);
        config.put("graphExecution", "SEQUENTIAL / NO_OPT / stable LayerNorm reductions");
        List<Round> measured = new ArrayList<>();
        ExecutorService executor = scenario.equals("shared") ? Executors.newFixedThreadPool(callers) : null;
        try (Measurements measurements = new Measurements(output)) {
            try (Magika sdk = Magika.builder().predictionMode(PredictionMode.HIGH_CONFIDENCE)
                    .batchSize(batchSize).intraOpThreads(threads).build()) {
                Path sdkJar = Paths.get(Magika.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                Corpus.require(sdkJar.toString().endsWith(".jar"), "Must consume packaged SDK");
                Corpus corpus = new Corpus(repository, sdk);
                Map<String, Object> metadata = Measurements.metadata(sdk.getModelInfo(), config);
                metadata.put("sdkJarSha256", Corpus.sha256(sdkJar));
                metadata.put("corpusManifestSha256", corpus.manifestSha256);
                metadata.put("corpusFiles", corpus.entries.size());
                metadata.put("corpusBytes", corpus.totalBytes);
                Measurements.json(output.resolve("metadata.json"), metadata);
                writeCorpus(output, corpus);
                measurements.sample("initialized_and_oracle", 0);
                try (BufferedWriter roundOutput = Files.newBufferedWriter(output.resolve("rounds.csv"), StandardCharsets.UTF_8);
                     BufferedWriter timingOutput = Files.newBufferedWriter(output.resolve("latencies.csv"), StandardCharsets.UTF_8)) {
                    roundOutput.write("phase,round,items,wall_ns,model_results,rule_results,failures,checksum\n");
                    timingOutput.write("round,item,latency_ns\n");
                    for (int number = -warmup; number < rounds; number++) {
                        Round result = run(sdk, corpus, scenario, items, batchSize, callers, executor);
                        Corpus.require(result.model == 68L * repeats && result.rule == repeats, "Outcome counts");
                        String phase = number < 0 ? "warmup" : "measured";
                        roundOutput.write(phase + "," + number + "," + items + "," + result.wallNs + ","
                                + result.model + "," + result.rule + ",0," + result.checksum + "\n");
                        roundOutput.flush();
                        if (number >= 0) {
                            measured.add(result);
                            for (int i = 0; i < items; i++) { timingOutput.write(number + "," + i + "," + result.latencies[i] + "\n"); }
                            timingOutput.flush();
                        }
                        measurements.sample(phase + "_" + number, (long) (number + warmup + 1) * items);
                    }
                }
            }
            measurements.sample("after_sdk_close", (long) (warmup + rounds) * items);
        } finally {
            if (executor != null) {
                executor.shutdownNow();
                Corpus.require(executor.awaitTermination(30, TimeUnit.SECONDS), "Worker shutdown");
            }
        }
        Measurements.summarize(output, measured, scenario.equals("batch"));
        System.out.println("Completed " + scenario + ": " + output);
    }

    private static Round run(Magika sdk, Corpus corpus, String scenario, int items, int batchSize,
                             int callers, ExecutorService executor) throws Exception {
        Round result = new Round(items);
        if (scenario.equals("batch")) {
            long[] admitted = new long[Math.min(items, batchSize)];
            Iterator<Path> paths = new Iterator<Path>() {
                int next;
                @Override public boolean hasNext() { return next < items; }
                @Override public Path next() {
                    if (!hasNext()) { throw new NoSuchElementException(); }
                    Path path = corpus.at(next).path;
                    admitted[next % admitted.length] = System.nanoTime();
                    next++;
                    return path;
                }
            };
            long start = System.nanoTime();
            BatchSummary summary = sdk.identifyAll(paths, item -> {
                long delivered = System.nanoTime();
                int index = Math.toIntExact(item.getInputIndex());
                Corpus.require(index == result.model + result.rule && item.isSuccess(), "Ordered successful delivery");
                Corpus.require(item.getPath().equals(corpus.at(index).path), "Delivered input path");
                result.latencies[index] = delivered - admitted[index % admitted.length];
                result.consume(corpus.at(index), item.getResult().get(), true);
            });
            result.wallNs = System.nanoTime() - start;
            Corpus.require(summary.getDeliveredCount() == items && summary.getSuccessCount() == items
                    && summary.getFailureCount() == 0, "Batch summary");
        } else if (executor == null) {
            long start = System.nanoTime();
            for (int i = 0; i < items; i++) { identify(sdk, corpus.at(i), scenario.equals("stream"), result, i); }
            result.wallNs = System.nanoTime() - start;
        } else {
            CountDownLatch ready = new CountDownLatch(callers);
            CountDownLatch release = new CountDownLatch(1);
            List<Future<Round>> workers = new ArrayList<>();
            for (int worker = 0; worker < callers; worker++) {
                final int first = worker;
                workers.add(executor.submit(() -> {
                    Round local = new Round((items - 1 - first) / callers + 1);
                    ready.countDown();
                    Corpus.require(release.await(30, TimeUnit.SECONDS), "Start gate");
                    for (int i = first, slot = 0; i < items; i += callers, slot++) {
                        identify(sdk, corpus.at(i), false, local, slot);
                    }
                    return local;
                }));
            }
            Corpus.require(ready.await(30, TimeUnit.SECONDS), "Workers ready");
            long start = System.nanoTime();
            release.countDown();
            for (int worker = 0; worker < callers; worker++) {
                Round local = workers.get(worker).get(5, TimeUnit.MINUTES);
                result.model += local.model;
                result.rule += local.rule;
                result.checksum += local.checksum;
                for (int slot = 0, i = worker; i < items; i += callers, slot++) { result.latencies[i] = local.latencies[slot]; }
            }
            result.wallNs = System.nanoTime() - start;
        }
        return result;
    }

    private static void identify(Magika sdk, Corpus.Entry entry, boolean stream, Round results, int slot) throws Exception {
        long start = System.nanoTime();
        DetectionResult result;
        if (stream) {
            try (CountedStream input = new CountedStream(Files.newInputStream(entry.path))) {
                result = sdk.identify(input);
                Corpus.require(!input.closed && input.bytes == entry.bytes && input.read() == -1, "Stream EOF/ownership");
            }
        } else { result = sdk.identify(entry.path); }
        results.latencies[slot] = System.nanoTime() - start;
        results.consume(entry, result, false);
    }

    private static void writeCorpus(Path output, Corpus corpus) throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(output.resolve("corpus.csv"), StandardCharsets.UTF_8)) {
            writer.write("path,bytes,sha256,model_used,label,score\n");
            for (Corpus.Entry entry : corpus.entries) {
                writer.write(entry.path + "," + entry.bytes + "," + entry.sha256 + "," + entry.expected.isModelUsed()
                        + "," + entry.expected.getLabel() + "," + entry.expected.getScore() + "\n");
            }
        }
    }

    private static int positive(String text) {
        int value = Integer.parseInt(text);
        Corpus.require(value > 0, "Positive parameter required");
        return value;
    }

    static final class Round {
        final long[] latencies;
        long wallNs;
        long model;
        long rule;
        long checksum;
        Round(int items) { latencies = new long[items]; }
        void consume(Corpus.Entry entry, DetectionResult result, boolean batch) {
            entry.validate(result, batch);
            if (result.isModelUsed()) { model++; } else { rule++; }
            checksum += result.getLabel().hashCode() + Double.doubleToLongBits(result.getScore());
        }
    }

    private static final class CountedStream extends FilterInputStream {
        long bytes;
        boolean closed;
        CountedStream(InputStream input) { super(input); }
        @Override public int read() throws IOException {
            int value = in.read();
            if (value >= 0) { bytes++; }
            return value;
        }
        @Override public int read(byte[] target, int offset, int length) throws IOException {
            int count = in.read(target, offset, length);
            if (count > 0) { bytes += count; }
            return count;
        }
        @Override public void reset() { throw new AssertionError("SDK reset the stream"); }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }
}
