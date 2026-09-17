/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

/** Unchecked SDK failure; operational failures are never returned as unknown types. */
public class MagikaException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** The stage that failed. */
    public enum Category {
        /** A bundled asset is missing, corrupt, or inconsistent. */
        ASSET_VALIDATION,
        /** Native loading, session creation, or model signature validation failed. */
        MODEL_INITIALIZATION,
        /** Model execution or output validation failed. */
        INFERENCE,
        /** Releasing instance resources failed. */
        RESOURCE_RELEASE
    }

    /**
     * The stage that failed.
     * @serial The failure category.
     */
    private final Category category;
    /**
     * Available operation context.
     * @serial Asset, model, or input-length context.
     */
    private final String context;

    MagikaException(Category category, String context, String message, Throwable cause) {
        super(message + " [" + context + "]", cause);
        this.category = category;
        this.context = context;
    }

    /**
     * Identifies the failure stage.
     * @return the failure category
     */
    public Category getCategory() { return category; }

    /**
     * Describes the affected operation.
     * @return available context such as the asset filename or input length
     */
    public String getContext() { return context; }
}
