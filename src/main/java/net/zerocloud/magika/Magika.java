/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

/**
 * Offline byte array identification using the bundled standard_v3_3 model and
 * HIGH_CONFIDENCE rules. Creating an instance eagerly validates assets and loads
 * a CPU ONNX Runtime session. Reuse instances for sequential calls.
 * Creation disables telemetry on the JVM-shared ORT environment, as upstream does.
 *
 * <p>This version requires callers to serialize identification and closing;
 * shared concurrent use and close during identification are not supported yet.
 */
public final class Magika implements AutoCloseable {
    private final ModelAdapter adapter;
    private boolean closed;

    private Magika(int intraOpThreads) {
        adapter = ModelAdapter.create(intraOpThreads);
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
        return adapter.identify(content);
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

        private Builder() { }

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
        public Magika build() { return new Magika(intraOpThreads); }
    }
}
