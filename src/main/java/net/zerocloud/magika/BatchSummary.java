/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

/** Immutable counts from a completed batch call; retains no inputs or results. */
public final class BatchSummary {
    private final long successCount;
    private final long failureCount;

    BatchSummary(long successCount, long failureCount) {
        this.successCount = successCount;
        this.failureCount = failureCount;
    }

    /**
     * Counts callbacks that returned normally.
     * @return the sum of successes and per-file failures
     */
    public long getDeliveredCount() { return successCount + failureCount; }

    /**
     * Counts delivered successful identifications, including empty and unknown.
     * @return the number of successful items
     */
    public long getSuccessCount() { return successCount; }

    /**
     * Counts delivered per-file input failures.
     * @return the number of failed items
     */
    public long getFailureCount() { return failureCount; }
}
