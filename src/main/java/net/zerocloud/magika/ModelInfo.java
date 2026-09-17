/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable provenance of the SDK and its bundled model asset set. */
public final class ModelInfo {
    private final String sdkVersion;
    private final String modelVersion;
    private final String upstreamCommit;
    private final Map<String, String> assetDigests;

    ModelInfo(String sdkVersion, String modelVersion, String upstreamCommit,
              Map<String, String> assetDigests) {
        this.sdkVersion = sdkVersion;
        this.modelVersion = modelVersion;
        this.upstreamCommit = upstreamCommit;
        this.assetDigests = Collections.unmodifiableMap(new LinkedHashMap<>(assetDigests));
    }

    /**
     * Identifies the SDK.
     * @return the SDK artifact version
     */
    public String getSdkVersion() { return sdkVersion; }

    /**
     * Identifies the model.
     * @return the model version
     */
    public String getModelVersion() { return modelVersion; }

    /**
     * Identifies the upstream source.
     * @return the full pinned upstream commit identifier
     */
    public String getUpstreamCommit() { return upstreamCommit; }

    /**
     * Lists verified assets.
     * @return an unmodifiable map from filename to lowercase SHA-256 digest
     */
    public Map<String, String> getAssetDigests() { return assetDigests; }
}
