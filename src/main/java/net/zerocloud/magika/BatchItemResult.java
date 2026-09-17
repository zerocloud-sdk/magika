/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Immutable outcome for one input position, with no native resources. Exactly
 * one of {@link #getResult()} and {@link #getError()} is present. Repeated paths
 * have distinct input indices. Retaining this value does not retain the batch.
 */
public final class BatchItemResult {
    private final long inputIndex;
    private final Path path;
    private final DetectionResult result;
    private final MagikaException error;

    private BatchItemResult(long inputIndex, Path path, DetectionResult result, MagikaException error) {
        this.inputIndex = inputIndex;
        this.path = path;
        this.result = result;
        this.error = error;
    }

    static BatchItemResult success(long index, Path path, DetectionResult result) {
        return new BatchItemResult(index, path, java.util.Objects.requireNonNull(result), null);
    }

    static BatchItemResult failure(long index, Path path, MagikaException error) {
        return new BatchItemResult(index, path, null, java.util.Objects.requireNonNull(error));
    }

    /**
     * Returns this input's position, including failed and repeated paths.
     * @return the zero-based input index
     */
    public long getInputIndex() { return inputIndex; }

    /**
     * Returns the original path without normalization or replacement.
     * @return the same non-null Path supplied by the iterator
     */
    public Path getPath() { return path; }

    /**
     * Reports identification success; empty and unknown types are successes.
     * @return true exactly when a detection result is present
     */
    public boolean isSuccess() { return result != null; }

    /**
     * Returns the successful identification.
     * @return the result, or empty for a per-file input failure
     */
    public Optional<DetectionResult> getResult() { return Optional.ofNullable(result); }

    /**
     * Returns the per-file failure, including available path context and cause.
     * @return an INPUT error, or empty for a successful identification
     */
    public Optional<MagikaException> getError() { return Optional.ofNullable(error); }
}
