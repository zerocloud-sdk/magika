/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

import java.util.Optional;

/**
 * Immutable identification result, containing only Java values. It remains
 * usable after its Magika instance closes and owns no native resources.
 */
public final class DetectionResult {
    private final String label;
    private final String mimeType;
    private final RawPrediction rawPrediction;
    private final OverwriteReason overwriteReason;
    private final String modelVersion;

    DetectionResult(String label, String mimeType, RawPrediction rawPrediction,
                    OverwriteReason overwriteReason, String modelVersion) {
        this.label = label;
        this.mimeType = mimeType;
        this.rawPrediction = rawPrediction;
        this.overwriteReason = overwriteReason;
        this.modelVersion = modelVersion;
    }

    /**
     * Returns the final label.
     * @return the label, including successful {@code empty} and {@code unknown} results
     */
    public String getLabel() { return label; }

    /**
     * Returns the final MIME type.
     * @return the MIME type from the bundled metadata
     */
    public String getMimeType() { return mimeType; }

    /**
     * Returns the raw top-1 score when the model ran, even if the label changed.
     * This is not the probability of the final MIME type. Rule-only results score 1.0.
     * @return the original model score or the rule score 1.0
     */
    public double getScore() { return rawPrediction == null ? 1.0 : rawPrediction.getScore(); }

    /**
     * Returns the optional raw prediction.
     * @return empty when no model inference was needed
     */
    public Optional<RawPrediction> getRawPrediction() { return Optional.ofNullable(rawPrediction); }

    /**
     * Explains label rewriting under the selected {@link PredictionMode}.
     * If the final label equals the raw label, the reason is {@code NONE},
     * including when a low score still yields the same label.
     * @return the reason the raw label was changed, or {@code NONE}
     */
    public OverwriteReason getOverwriteReason() { return overwriteReason; }

    /**
     * Reports model use.
     * @return whether this identification executed the model
     */
    public boolean isModelUsed() { return rawPrediction != null; }

    /**
     * Identifies the model.
     * @return the bundled model version, independently of the SDK version
     */
    public String getModelVersion() { return modelVersion; }
}
