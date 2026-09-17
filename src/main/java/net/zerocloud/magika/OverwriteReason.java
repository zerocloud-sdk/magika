/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

/** Explains why the final label differs from the raw model prediction. */
public enum OverwriteReason {
    /** No raw prediction was changed, including results that did not use the model. */
    NONE,
    /** The fixed model configuration maps the raw label to another label. */
    OVERWRITE_MAP,
    /** The score is below the fixed threshold and a generic label is returned. */
    LOW_CONFIDENCE
}
