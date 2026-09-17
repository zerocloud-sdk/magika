/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import net.zerocloud.magika.BatchIdentificationException.Stage;

/** Per-invocation state; owns at most one batch and never invokes code under a lock. */
final class BatchIdentification {
    private final ModelAdapter adapter;
    private final int batchSize;
    private long nextIndex;
    private long successes;
    private long failures;

    BatchIdentification(ModelAdapter adapter, int batchSize) {
        this.adapter = adapter;
        this.batchSize = batchSize;
    }

    BatchSummary run(Iterator<Path> paths, Consumer<BatchItemResult> consumer) {
        boolean exhausted = false;
        while (!exhausted) {
            List<Pending> batch = new ArrayList<>();
            List<int[]> modelRows = new ArrayList<>();
            for (int i = 0; i < batchSize; i++) {
                checkInterrupted(-1);
                boolean hasNext;
                try {
                    hasNext = paths.hasNext();
                } catch (RuntimeException | Error error) {
                    throw abort(Stage.ITERATION, -1, error);
                }
                checkInterrupted(hasNext ? nextIndex : -1);
                if (!hasNext) {
                    exhausted = true;
                    break;
                }
                Path path;
                try {
                    if (nextIndex == Long.MAX_VALUE) {
                        throw new ArithmeticException("Batch input count exceeds long capacity");
                    }
                    path = paths.next();
                    if (path == null) { throw new IllegalArgumentException("Iterator produced a null Path"); }
                } catch (RuntimeException | Error error) {
                    throw abort(Stage.ITERATION, nextIndex, error);
                }
                checkInterrupted(nextIndex);
                Pending pending = prepare(path, nextIndex++);
                checkInterrupted(pending.index);
                batch.add(pending);
                if (pending.features != null) { modelRows.add(pending.features); }
            }
            checkInterrupted(-1);
            if (!modelRows.isEmpty()) {
                infer(batch, modelRows);
            }
            for (Pending pending : batch) {
                checkInterrupted(pending.index);
                try {
                    consumer.accept(pending.outcome);
                } catch (RuntimeException | Error error) {
                    throw abort(Stage.CALLBACK, pending.index, error);
                }
                if (pending.outcome.isSuccess()) { successes++; } else { failures++; }
            }
            // Includes interruption in the final callback, even for a partial final batch.
            checkInterrupted(-1);
        }
        return new BatchSummary(successes, failures);
    }

    private Pending prepare(Path path, long index) {
        Pending pending = new Pending(path, index);
        try {
            ModelAdapter.Prepared prepared = adapter.prepare(InputSample.fromPath(path, ModelAssets.WINDOW_SIZE));
            pending.features = prepared.features;
            if (prepared.result != null) {
                pending.outcome = BatchItemResult.success(index, path, prepared.result);
            }
        } catch (MagikaException error) {
            if (Thread.currentThread().isInterrupted()) {
                throw abort(Stage.INTERRUPTED, index, error);
            }
            if (error.getCategory() != MagikaException.Category.INPUT) {
                throw abort(Stage.IDENTIFICATION, index, error);
            }
            pending.outcome = BatchItemResult.failure(index, path, error);
        } catch (RuntimeException | Error error) {
            throw abort(Stage.IDENTIFICATION, index, error);
        }
        return pending;
    }

    private void infer(List<Pending> batch, List<int[]> modelRows) {
        long inputIndex = -1;
        if (modelRows.size() == 1) {
            for (Pending pending : batch) {
                if (pending.features != null) { inputIndex = pending.index; break; }
            }
        }
        try {
            List<DetectionResult> results = adapter.infer(modelRows);
            int row = 0;
            for (Pending pending : batch) {
                if (pending.features != null) {
                    pending.outcome = BatchItemResult.success(pending.index, pending.path, results.get(row++));
                }
            }
        } catch (RuntimeException | Error error) {
            throw abort(Stage.IDENTIFICATION, inputIndex, error);
        }
    }

    private void checkInterrupted(long inputIndex) {
        if (Thread.currentThread().isInterrupted()) {
            throw abort(Stage.INTERRUPTED, inputIndex, new InterruptedException("Batch thread interrupted"));
        }
    }

    private BatchIdentificationException abort(Stage stage, long inputIndex, Throwable cause) {
        return new BatchIdentificationException(stage, successes + failures, inputIndex, cause);
    }

    private static final class Pending {
        final Path path;
        final long index;
        int[] features;
        BatchItemResult outcome;

        Pending(Path path, long index) {
            this.path = path;
            this.index = index;
        }
    }
}
