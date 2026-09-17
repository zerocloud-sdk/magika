/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

/** Immutable top-1 model prediction, before type mapping and confidence rules. */
public final class RawPrediction {
    private final String label;
    private final double score;

    RawPrediction(String label, double score) {
        this.label = label;
        this.score = score;
    }

    /**
     * Returns the raw model label.
     * @return the raw model label
     */
    public String getLabel() { return label; }

    /**
     * Returns the original top-1 model score.
     * @return the original top-1 model score
     */
    public double getScore() { return score; }
}
