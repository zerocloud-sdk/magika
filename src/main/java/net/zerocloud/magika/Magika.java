/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

import java.nio.file.Path;

/**
 * Offline byte array and regular file identification using the bundled standard_v3_3 model and
 * a configurable {@link PredictionMode}, defaulting to HIGH_CONFIDENCE.
 * Creating an instance eagerly validates assets and loads
 * a CPU ONNX Runtime session. Reuse instances for sequential calls.
 * Creation disables telemetry on the JVM-shared ORT environment, as upstream does.
 *
 * <p>This version requires callers to serialize identification and closing;
 * shared concurrent use and close during identification are not supported yet.
 */
public final class Magika implements AutoCloseable {
    private final ModelAdapter adapter;
    private boolean closed;

    private Magika(int intraOpThreads, PredictionMode predictionMode) {
        adapter = ModelAdapter.create(intraOpThreads, predictionMode);
    }

    /**
     * Creates an instance with HIGH_CONFIDENCE rules, sequential graph execution,
     * and one intra-operation thread.
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
        public Magika build() { return new Magika(intraOpThreads, predictionMode); }
    }
}
