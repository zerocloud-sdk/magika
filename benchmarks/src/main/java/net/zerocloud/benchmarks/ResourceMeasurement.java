/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.benchmarks;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import net.zerocloud.magika.BatchIdentificationException;
import net.zerocloud.magika.BatchSummary;
import net.zerocloud.magika.DetectionResult;
import net.zerocloud.magika.Magika;
import net.zerocloud.magika.MagikaException;

/** Separate from timing: repeated normal and exceptional public SDK lifetimes. */
public final class ResourceMeasurement {
    public static void main(String[] args) throws Exception {
        Corpus.require(args.length == 5, "repository output warmupCycles phases cyclesPerPhase");
        Path output = Paths.get(args[1]);
        int warmup = Integer.parseInt(args[2]);
        int phases = Integer.parseInt(args[3]);
        int cycles = Integer.parseInt(args[4]);
        Corpus.require(warmup > 0 && phases > 0 && cycles > 0, "Positive cycle counts");
        Path directory = Files.createTempDirectory("magika-resource-measurement-");
        Path missing = directory.resolve("absent");
        try (Measurements measurements = new Measurements(output)) {
            Corpus corpus;
            try (Magika sdk = Magika.create()) {
                corpus = new Corpus(Paths.get(args[0]), sdk);
                Map<String, Object> config = new LinkedHashMap<>();
                config.put("warmupCycles", warmup);
                config.put("phases", phases);
                config.put("cyclesPerPhase", cycles);
                config.put("batchSize", 32);
                config.put("intraOpThreads", 2);
                config.put("callers", 1);
                config.put("predictionMode", "HIGH_CONFIDENCE");
                config.put("maxStreamBytes", 67_108_864);
                config.put("graphExecution", "SEQUENTIAL / NO_OPT / stable LayerNorm reductions");
                Map<String, Object> metadata = Measurements.metadata(sdk.getModelInfo(), config);
                metadata.put("sdkJarSha256", Corpus.sha256(Paths.get(Magika.class.getProtectionDomain().getCodeSource().getLocation().toURI())));
                metadata.put("corpusManifestSha256", corpus.manifestSha256);
                metadata.put("perCycleObservedModelResults", 73);
                metadata.put("perCycleObservedRuleResults", 16);
                metadata.put("perCycleInputFailures", 18);
                metadata.put("perCycleBatchAborts", 3);
                metadata.put("perCycleExceptionalBodyExits", 1);
                Measurements.json(output.resolve("metadata.json"), metadata);
            }
            Corpus.Entry model = corpus.entries.stream().filter(e -> e.expected.getLabel().equals("pdf")).findFirst().get();
            Corpus.Entry rule = corpus.entries.stream().filter(e -> !e.expected.isModelUsed()).findFirst().get();
            for (int i = 0; i < warmup; i++) { cycle(model, rule, missing); }
            samplePhase(measurements, "warmup", warmup);
            for (int phase = 1; phase <= phases; phase++) {
                for (int i = 0; i < cycles; i++) { cycle(model, rule, missing); }
                samplePhase(measurements, "phase_" + phase, warmup + (long) phase * cycles);
            }
        } finally { Files.delete(directory); }
        System.out.println("Completed resource measurement: " + output);
    }

    private static void samplePhase(Measurements measurements, String phase, long cycles) throws Exception {
        measurements.sample(phase + "_before_gc", cycles);
        System.gc();
        measurements.sample(phase + "_after_gc_request", cycles);
    }

    private static void cycle(Corpus.Entry model, Corpus.Entry rule, Path missing) throws Exception {
        long[] counts = new long[3]; // Observed model results, rule results, input failures.
        Magika sdk = Magika.builder().batchSize(32).intraOpThreads(2).build();
        RuntimeException exit = new IllegalStateException("deliberate exceptional body exit");
        DetectionResult retained = null;
        try (Magika owned = sdk) {
            consume(model, owned.identify(model.path), counts, false);
            try (InputStream input = Files.newInputStream(model.path)) {
                consume(model, owned.identify(input), counts, false);
                Corpus.require(input.read() == -1, "Resource stream EOF");
            }
            try { owned.identify(missing); throw new AssertionError("Missing input succeeded"); }
            catch (MagikaException error) {
                Corpus.require(error.getCategory() == MagikaException.Category.INPUT, "Missing-file category");
                counts[2]++;
            }
            try (FaultStream input = new FaultStream()) {
                try { owned.identify(input); throw new AssertionError("Failed stream succeeded"); }
                catch (MagikaException error) {
                    Corpus.require(error.getCategory() == MagikaException.Category.INPUT && error.getCause() == input.failure
                            && !input.closed, "Stream error/ownership");
                    counts[2]++;
                }
            }
            BatchSummary summary = owned.identifyAll(new PathsIterator(64, model.path, model.path, rule.path, missing), item -> {
                if (item.isSuccess()) { consume(item.getInputIndex() % 4 == 2 ? rule : model, item.getResult().get(), counts, true); }
                else {
                    Corpus.require(item.getInputIndex() % 4 == 3 && item.getError().get().getCategory() == MagikaException.Category.INPUT,
                            "Per-file failure");
                    counts[2]++;
                }
            });
            Corpus.require(summary.getDeliveredCount() == 64 && summary.getSuccessCount() == 48 && summary.getFailureCount() == 16,
                    "Mixed batch summary");
            try {
                owned.identifyAll(new PathsIterator(64, model.path), item -> {
                    if (item.getInputIndex() == 5) { throw exit; }
                    consume(model, item.getResult().get(), counts, true);
                });
                throw new AssertionError("Callback abort missing");
            } catch (BatchIdentificationException error) { abort(error, BatchIdentificationException.Stage.CALLBACK, 5, exit); }
            Iterator<Path> broken = new Iterator<Path>() {
                int next;
                @Override public boolean hasNext() { if (next == 33) { throw exit; } return true; }
                @Override public Path next() { next++; return model.path; }
            };
            try {
                owned.identifyAll(broken, item -> consume(model, item.getResult().get(), counts, true));
                throw new AssertionError("Iterator abort missing");
            } catch (BatchIdentificationException error) { abort(error, BatchIdentificationException.Stage.ITERATION, 32, exit); }
            try {
                owned.identifyAll(new PathsIterator(64, model.path), item -> {
                    consume(model, item.getResult().get(), counts, true);
                    Thread.currentThread().interrupt();
                });
                throw new AssertionError("Interrupt abort missing");
            } catch (BatchIdentificationException error) {
                Corpus.require(error.getStage() == BatchIdentificationException.Stage.INTERRUPTED
                        && error.getDeliveredCount() == 1 && Thread.currentThread().isInterrupted(), "Interrupt progress");
            } finally { Thread.interrupted(); }
            retained = owned.identify(model.path);
            consume(model, retained, counts, false);
            throw exit;
        } catch (RuntimeException error) {
            if (error != exit || error.getSuppressed().length != 0) { throw error; }
        }
        sdk.close();
        model.validate(retained, false);
        try { sdk.identify(model.path); throw new AssertionError("Closed SDK accepted input"); }
        catch (IllegalStateException expected) { /* Public rejection after cleanup. */ }
        Corpus.require(counts[0] == 73 && counts[1] == 16 && counts[2] == 18, "Resource workload counts");
    }

    private static void abort(BatchIdentificationException error, BatchIdentificationException.Stage stage,
                              long delivered, Throwable cause) {
        Corpus.require(error.getStage() == stage && error.getDeliveredCount() == delivered && error.getCause() == cause,
                "Batch abort progress/cause");
    }

    private static void consume(Corpus.Entry expected, DetectionResult result, long[] counts, boolean batch) {
        expected.validate(result, batch);
        counts[result.isModelUsed() ? 0 : 1]++;
    }

    private static final class FaultStream extends InputStream {
        final IOException failure = new IOException("deliberate read failure");
        boolean closed;
        @Override public int read() throws IOException { throw failure; }
        @Override public void close() { closed = true; }
    }

    private static final class PathsIterator implements Iterator<Path> {
        private final int count;
        private final Path[] pattern;
        private int next;
        PathsIterator(int count, Path... pattern) { this.count = count; this.pattern = pattern; }
        @Override public boolean hasNext() { return next < count; }
        @Override public Path next() {
            if (!hasNext()) { throw new NoSuchElementException(); }
            return pattern[next++ % pattern.length];
        }
    }
}
