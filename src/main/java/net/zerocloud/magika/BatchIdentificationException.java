/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

import java.util.OptionalLong;

/**
 * Aborts an entire batch call. The delivered prefix remains valid. A throwing
 * callback is not counted, even though it may already have performed side effects.
 * Nothing is retried or rolled back; recovery belongs to the caller.
 */
public final class BatchIdentificationException extends MagikaException {
    private static final long serialVersionUID = 1L;

    /** The boundary at which the batch call stopped. */
    public enum Stage {
        /** Iterator hasNext/next failed, returned null, or exhausted index capacity. */
        ITERATION,
        /** Sampling/preparation suffered a system failure, or model inference failed. */
        IDENTIFICATION,
        /** The result consumer threw an exception or error. */
        CALLBACK,
        /** The calling thread was interrupted at a controllable boundary. */
        INTERRUPTED
    }

    /**
     * The failing batch boundary.
     * @serial The failing batch boundary.
     */
    private final Stage stage;
    /**
     * The delivered prefix length.
     * @serial Number of callbacks that returned normally before the abort.
     */
    private final long deliveredCount;
    /**
     * The affected input position when known.
     * @serial Known zero-based input index, or -1 when no single index is known.
     */
    private final long inputIndex;

    BatchIdentificationException(Stage stage, long deliveredCount, long inputIndex, Throwable cause) {
        super(Category.BATCH, "stage=" + stage + ", delivered=" + deliveredCount
                        + (inputIndex < 0 ? "" : ", inputIndex=" + inputIndex),
                "Batch identification aborted", cause);
        this.stage = stage;
        this.deliveredCount = deliveredCount;
        this.inputIndex = inputIndex;
    }

    /**
     * Identifies the boundary where processing stopped.
     * @return the failure stage
     */
    public Stage getStage() { return stage; }

    /**
     * Counts only callbacks that returned normally before this failure.
     * @return the length of the valid delivered prefix
     */
    public long getDeliveredCount() { return deliveredCount; }

    /**
     * Identifies an affected input only when it can be determined. A failed
     * hasNext or inference over multiple inputs has no uniquely failing index.
     * next failures, null elements, preparation and callback failures do have one.
     * Interruptions identify the current input only when one is established.
     * @return the zero-based index, or empty when unknown
     */
    public OptionalLong getInputIndex() {
        return inputIndex < 0 ? OptionalLong.empty() : OptionalLong.of(inputIndex);
    }
}
