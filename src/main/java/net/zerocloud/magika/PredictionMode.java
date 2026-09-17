/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

/**
 * Official confidence policies for the bundled model. All modes apply type
 * mapping and preserve the raw top-1 score. Empty and short-content rules are
 * independent of this setting and retain score 1.0 with no raw prediction.
 */
public enum PredictionMode {
    /**
     * Uses the raw label's configured threshold, falling back to the configured
     * medium-confidence threshold when no label-specific threshold exists.
     * This is the default mode.
     */
    HIGH_CONFIDENCE,
    /** Uses the configured uniform medium-confidence threshold (currently 0.5). */
    MEDIUM_CONFIDENCE,
    /** Keeps the mapped model label regardless of score; never rejects a low score. */
    BEST_GUESS
}
