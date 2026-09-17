/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

/** Explains why the final label differs from the raw model prediction. */
public enum OverwriteReason {
    /**
     * The final label equals the raw label, even if its score is below the threshold,
     * or no model inference was needed.
     */
    NONE,
    /** The fixed model configuration maps the raw label to another label. */
    OVERWRITE_MAP,
    /**
     * The score is below the selected mode's fixed threshold and the label changed
     * to {@code txt} or {@code unknown}, chosen using the mapped type's text metadata.
     * This takes precedence over mapping when both rules apply; BEST_GUESS never uses it.
     */
    LOW_CONFIDENCE
}
