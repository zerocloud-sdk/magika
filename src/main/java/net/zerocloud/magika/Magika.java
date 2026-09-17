/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.function.Consumer;

/**
 * Offline byte array, regular file, stream and lazy batch identification using the bundled standard_v3_3 model and
 * a configurable {@link PredictionMode}, defaulting to HIGH_CONFIDENCE.
 * Creating an instance eagerly validates assets and loads
 * a CPU ONNX Runtime session. Reuse instances for sequential calls.
 * Creation disables telemetry on the JVM-shared ORT environment, as upstream does.
 * After authentication, two model reductions use an equivalent axis layout to
 * keep single/multirow scores compatible; graph optimizations that undo this
 * layout are disabled. Weights and bundled asset digests are unchanged.
 *
 * <p>This version requires callers to serialize identification and closing;
 * shared concurrent use and close during identification are not supported yet.
 */
public final class Magika implements AutoCloseable {
    private final ModelAdapter adapter;
    private final long maxStreamBytes;
    private final int batchSize;
    private boolean closed;

    private Magika(int intraOpThreads, PredictionMode predictionMode, long maxStreamBytes, int batchSize) {
        adapter = ModelAdapter.create(intraOpThreads, predictionMode);
        this.maxStreamBytes = maxStreamBytes;
        this.batchSize = batchSize;
    }

    /**
     * Creates an instance with HIGH_CONFIDENCE rules, sequential graph execution,
     * one intra-operation thread, batches of at most 32 paths, and a
     * 67,108,864-byte (64 MiB) stream limit.
     * @return a fully initialized instance
     * @throws MagikaException if asset validation or initialization fails
     */
    public static Magika create() { return builder().build(); }

    /**
     * Creates a configuration builder.
     * @return a new builder with default settings
     */
    public static Builder builder() { return new Builder(); }

    /**
     * Identifies complete content. Keep the input unchanged until this call returns.
     * Only the required head and tail windows are examined; the SDK does not copy
     * the entire array. The input is not retained or modified.
     * @param content complete file content, not just a prefix
     * @return an immutable result with no native resources to close
     * @throws IllegalArgumentException if content is null
     * @throws IllegalStateException if this instance has been closed
     * @throws MagikaException if inference fails
     */
    public DetectionResult identify(byte[] content) {
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        if (closed) {
            throw new IllegalStateException("Magika is closed");
        }
        return adapter.identify(InputSample.fromBytes(content, ModelAssets.WINDOW_SIZE));
    }

    /**
     * Identifies a regular file, following symbolic links. Directories, devices,
     * pipes and other non-regular inputs are rejected before opening content.
     * The SDK opens one file handle, randomly reads bounded head and tail windows,
     * and closes its handle on success or failure. There is no default file size
     * limit; sampling memory does not grow with the total file size.
     *
     * <p>Keep the file and its path stable throughout this call. The SDK provides
     * no snapshot and does not detect every concurrent modification. Observable
     * truncation or read failures throw an input error without retrying.
     * @param path the saved regular file to identify
     * @return an immutable result with no native resources to close
     * @throws IllegalArgumentException if path is null
     * @throws IllegalStateException if this instance has been closed
     * @throws MagikaException if the input is not a readable regular file, sampling
     *         or closing its handle fails, or inference fails
     */
    public DetectionResult identify(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        if (closed) {
            throw new IllegalStateException("Magika is closed");
        }
        return adapter.identify(InputSample.fromPath(path, ModelAssets.WINDOW_SIZE));
    }

    /**
     * Identifies all remaining content from the stream's current position to EOF.
     * The SDK never closes or resets the stream, on success or failure. Arrange
     * any replay or reopening in the calling application; blocking read timeouts
     * are controlled by the input source.
     *
     * <p>The configured {@link Builder#maxStreamBytes(long)} limit defaults to
     * 67,108,864 bytes (64 MiB). At most one extra byte is consumed to detect
     * overflow; exceeding the limit fails immediately without identifying a
     * truncated input. A zero limit accepts only an empty remaining stream.
     *
     * <p>Sampling retains at most 4096 bytes at each end, a fixed work buffer and
     * the total length, without storing the complete stream in memory or a file.
     * This bound excludes model, ONNX Runtime and caller-owned buffers or results.
     * The same complete content produces the same result as {@link #identify(byte[])}.
     * @param input the caller-owned stream, positioned at the start of the content
     * @return an immutable result with no native resources to close
     * @throws IllegalArgumentException if input is null
     * @throws IllegalStateException if this instance has been closed; no bytes are read
     * @throws MagikaException if reading fails, the stream exceeds the configured
     *         limit (both INPUT failures), or inference fails
     */
    public DetectionResult identify(InputStream input) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        if (closed) {
            throw new IllegalStateException("Magika is closed");
        }
        return adapter.identify(InputSample.fromStream(input, ModelAssets.WINDOW_SIZE, maxStreamBytes));
    }

    /**
     * Synchronously identifies a lazy sequence of regular files, delivering each
     * outcome in input order on the calling thread. Each batch consumes at most
     * {@link Builder#batchSize(int)} paths (default 32); only inputs needing the
     * model enter the inference tensor. The next batch is not consumed until all
     * current callbacks return. The SDK creates no worker pool and retains only
     * the current batch, excluding model/ORT memory and caller-retained values.
     *
     * <p>File errors are delivered as failed items and processing continues.
     * System, iterator (including null elements), or callback failures abort the
     * call with stage and delivery progress. Only a normally returning callback
     * counts as delivered. An aborted batch can have consumed inputs beyond that
     * prefix. A throwing callback can already have side effects: no automatic
     * retry or rollback occurs. The caller owns traversal, scheduling and recovery,
     * and must close any resource underlying its iterator.
     *
     * <p>Interruption is checked between inputs, before inference and callbacks,
     * and after each batch. The flag is preserved. Blocking input, user code and
     * native inference are not forcibly stopped. Keep paths/files stable while
     * sampled, as for {@link #identify(Path)}. Serialize this entire call with
     * other instance operations; callbacks must not close this instance.
     * @param paths non-null lazy input iterator; each element must be non-null
     * @param consumer non-null callback, invoked at most once per input position
     * @return counts of normally delivered successes and per-file failures
     * @throws IllegalArgumentException if either argument is null
     * @throws IllegalStateException if this instance has been closed
     * @throws BatchIdentificationException if the call aborts
     */
    public BatchSummary identifyAll(Iterator<Path> paths, Consumer<BatchItemResult> consumer) {
        if (paths == null || consumer == null) {
            throw new IllegalArgumentException("paths and consumer must not be null");
        }
        if (closed) { throw new IllegalStateException("Magika is closed"); }
        return new BatchIdentification(adapter, batchSize).run(paths, consumer);
    }

    /**
     * Reports model provenance.
     * @return immutable version and asset information, also available after close
     */
    public ModelInfo getModelInfo() { return adapter.modelInfo(); }

    /**
     * Closes the session, then its options. Repeated sequential calls are harmless.
     * The JVM-shared ONNX Runtime environment is not closed. If native release
     * fails, this instance remains closed and cannot be reused.
     * @throws MagikaException if releasing owned resources fails
     */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            adapter.close();
        }
    }

    /** Mutable configuration builder; not thread-safe. */
    public static final class Builder {
        private int intraOpThreads = 1;
        private PredictionMode predictionMode = PredictionMode.HIGH_CONFIDENCE;
        private long maxStreamBytes = 67_108_864L;
        private int batchSize = 32;

        private Builder() { }

        /**
         * Selects the official confidence policy. All modes apply type mapping;
         * HIGH_CONFIDENCE and MEDIUM_CONFIDENCE also reject scores strictly below
         * their configured threshold. BEST_GUESS skips low-score rejection.
         * @param mode the prediction mode; the default is HIGH_CONFIDENCE
         * @return this builder
         * @throws IllegalArgumentException if mode is null
         */
        public Builder predictionMode(PredictionMode mode) {
            if (mode == null) {
                throw new IllegalArgumentException("predictionMode must not be null");
            }
            predictionMode = mode;
            return this;
        }

        /**
         * Limits the number of remaining bytes accepted by stream identification.
         * At most one additional byte is consumed to distinguish EOF from an
         * oversized stream. This setting does not limit byte arrays or regular files.
         * @param bytes a nonnegative limit; default 67,108,864 (64 MiB), zero accepts
         *              only empty streams, and Long.MAX_VALUE is supported
         * @return this builder
         * @throws IllegalArgumentException if bytes is negative
         */
        public Builder maxStreamBytes(long bytes) {
            if (bytes < 0) {
                throw new IllegalArgumentException("maxStreamBytes must not be negative");
            }
            maxStreamBytes = bytes;
            return this;
        }

        /**
         * Limits paths consumed per batch, including rule results and file errors.
         * The maximum ensures both INT32 tensor element counts and direct-buffer
         * byte capacities fit an int. Storage grows only with actual inputs.
         * @param size a positive size up to 262143; default 32
         * @return this builder
         * @throws IllegalArgumentException if size is nonpositive or exceeds tensor capacity
         */
        public Builder batchSize(int size) {
            if (size <= 0 || size > ModelAdapter.MAX_BATCH_SIZE) {
                throw new IllegalArgumentException("batchSize must be between 1 and " + ModelAdapter.MAX_BATCH_SIZE);
            }
            batchSize = size;
            return this;
        }

        /**
         * Sets ONNX Runtime's intra-operation thread count. Graph execution remains sequential.
         * @param threads a positive thread count; the default is 1
         * @return this builder
         * @throws IllegalArgumentException if threads is not positive
         */
        public Builder intraOpThreads(int threads) {
            if (threads <= 0) {
                throw new IllegalArgumentException("intraOpThreads must be positive");
            }
            intraOpThreads = threads;
            return this;
        }

        /**
         * Validates the bundled assets and eagerly creates a native session.
         * Later builder changes do not change an existing instance.
         * @return a fully initialized instance owned by the caller
         * @throws MagikaException if validation or initialization fails
         */
        public Magika build() { return new Magika(intraOpThreads, predictionMode, maxStreamBytes, batchSize); }
    }
}
