/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Loads only the authenticated, bundled asset set; no external model loading. */
final class ModelAssets {
    static final String MODEL_VERSION = "standard_v3_3";
    static final String UPSTREAM_COMMIT = "9f225aa480e675af44343b9160f077073ed1b752";
    static final int HEAD_SIZE = 1024;
    static final int TAIL_SIZE = 1024;
    static final int WINDOW_SIZE = 4096;
    static final int PADDING = 256;
    static final int MIN_MODEL_BYTES = 8;
    static final int LABEL_COUNT = 214;
    private static final String RESOURCE_ROOT = "/net/zerocloud/magika/model/";

    final byte[] model;
    final ModelInfo info;
    final List<String> labels = new ArrayList<>();
    private final Map<String, ContentType> contentTypes = new HashMap<>();
    private final Map<String, Double> thresholds = new HashMap<>();
    private final Map<String, String> overwriteMap = new HashMap<>();
    private final double mediumConfidenceThreshold;

    private ModelAssets() throws IOException {
        Map<String, String> digests = new LinkedHashMap<>();
        model = readAsset("model.onnx", 3163737,
                "fe2d2eb49c5f88a9e0a6c048e15d6ffdf86235519c2afc535044de433169ec8c", digests);
        byte[] configBytes = readAsset("config.min.json", 2141,
                "ae24c742205358f6ff6dfd5facb6743fb69743dbba8373e73da58ff0cbd695db", digests);
        byte[] kbBytes = readAsset("content_types_kb.min.json", 44768,
                "75208adba69bc0556403b62b32ef3ccf8b5ce494411780845852e52e6583a10d", digests);
        JsonObject config = parse(configBytes);
        require(config.get("beg_size").getAsInt() == HEAD_SIZE, "head feature size");
        require(config.get("end_size").getAsInt() == TAIL_SIZE, "tail feature size");
        require(config.get("block_size").getAsInt() == WINDOW_SIZE, "window size");
        require(config.get("padding_token").getAsInt() == PADDING, "padding token");
        require(config.get("min_file_size_for_dl").getAsInt() == MIN_MODEL_BYTES, "model minimum size");
        require(config.get("mid_size").getAsInt() == 0, "middle features");
        require(!config.get("use_inputs_at_offsets").getAsBoolean(), "offset features");
        require(config.get("version_major").getAsInt() == 3, "configuration version");
        require("none".equals(config.get("protection").getAsString()), "model protection");
        for (JsonElement label : config.getAsJsonArray("target_labels_space")) {
            labels.add(label.getAsString());
        }
        // The exact config digest authenticates order, not only set membership.
        require(labels.size() == LABEL_COUNT && new HashSet<>(labels).size() == LABEL_COUNT,
                "ordered model labels");

        for (Map.Entry<String, JsonElement> entry : parse(kbBytes).entrySet()) {
            JsonObject value = entry.getValue().getAsJsonObject();
            require(value.get("is_text").getAsJsonPrimitive().isBoolean(), "is_text metadata");
            boolean text = value.get("is_text").getAsBoolean();
            String mime = value.get("mime_type").isJsonNull()
                    ? (text ? "text/plain" : "application/octet-stream")
                    : value.get("mime_type").getAsString();
            contentTypes.put(entry.getKey(), new ContentType(mime, text));
        }
        for (String label : labels) {
            requireContentType(label);
        }
        for (String label : new String[] {"empty", "txt", "unknown"}) {
            requireContentType(label);
        }
        mediumConfidenceThreshold = config.get("medium_confidence_threshold").getAsDouble();
        require(validThreshold(mediumConfidenceThreshold), "medium confidence threshold");
        for (Map.Entry<String, JsonElement> entry : config.getAsJsonObject("thresholds").entrySet()) {
            double threshold = entry.getValue().getAsDouble();
            require(labels.contains(entry.getKey()) && validThreshold(threshold), "label threshold");
            thresholds.put(entry.getKey(), threshold);
        }
        for (Map.Entry<String, JsonElement> entry : config.getAsJsonObject("overwrite_map").entrySet()) {
            String target = entry.getValue().getAsString();
            require(labels.contains(entry.getKey()) && contentTypes.containsKey(target), "type mapping");
            requireContentType(target);
            overwriteMap.put(entry.getKey(), target);
        }
        Properties sdk = new Properties();
        try (InputStream input = ModelAssets.class.getResourceAsStream("/net/zerocloud/magika/sdk.properties")) {
            if (input == null) {
                throw invalid("sdk.properties", "Missing SDK version resource", null);
            }
            sdk.load(input);
        }
        String version = sdk.getProperty("sdk.version");
        require(version != null && version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-.][A-Za-z0-9.-]+)?"),
                "SDK version");
        info = new ModelInfo(version, MODEL_VERSION, UPSTREAM_COMMIT, digests);
    }

    static ModelAssets load() {
        try {
            return new ModelAssets();
        } catch (MagikaException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw invalid(MODEL_VERSION, "Cannot read model configuration or metadata", e);
        }
    }

    private static JsonObject parse(byte[] bytes) {
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static byte[] readAsset(String name, int size, String expected, Map<String, String> digests) {
        try (InputStream input = ModelAssets.class.getResourceAsStream(RESOURCE_ROOT + name)) {
            if (input == null) {
                throw invalid(name, "Missing bundled asset", null);
            }
            byte[] bytes = new byte[size];
            int offset = 0;
            while (offset < size) {
                int count = input.read(bytes, offset, size - offset);
                if (count < 0) {
                    throw invalid(name, "Truncated bundled asset; expected " + size + " bytes", null);
                }
                offset += count;
            }
            if (input.read() != -1) {
                throw invalid(name, "Oversized bundled asset; expected " + size + " bytes", null);
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder actual = new StringBuilder(64);
            for (byte value : digest) {
                actual.append(Character.forDigit((value >>> 4) & 15, 16));
                actual.append(Character.forDigit(value & 15, 16));
            }
            if (!expected.contentEquals(actual)) {
                throw invalid(name, "SHA-256 mismatch; expected " + expected + ", got " + actual, null);
            }
            digests.put(name, expected);
            return bytes;
        } catch (IOException | NoSuchAlgorithmException e) {
            throw invalid(name, "Cannot verify bundled asset", e);
        }
    }

    private static boolean validThreshold(double value) {
        return !Double.isNaN(value) && value >= 0 && value <= 1;
    }

    private static void require(boolean condition, String field) {
        if (!condition) {
            throw invalid(MODEL_VERSION, "Invalid " + field, null);
        }
    }

    private static MagikaException invalid(String context, String message, Throwable cause) {
        return new MagikaException(MagikaException.Category.ASSET_VALIDATION, context, message, cause);
    }

    ContentType contentType(String label) { return contentTypes.get(label); }

    private void requireContentType(String label) {
        ContentType type = contentTypes.get(label);
        // Preserve upstream strings verbatim (for example OCaml is "text-ocaml").
        require(type != null && !type.mimeType.isEmpty(),
                "metadata for supported label " + label);
    }

    String mappedLabel(String label) { return overwriteMap.getOrDefault(label, label); }

    double threshold(String rawLabel, PredictionMode mode) {
        return mode == PredictionMode.HIGH_CONFIDENCE
                ? thresholds.getOrDefault(rawLabel, mediumConfidenceThreshold) : mediumConfidenceThreshold;
    }

    static final class ContentType {
        final String mimeType;
        final boolean text;

        ContentType(String mimeType, boolean text) {
            this.mimeType = mimeType;
            this.text = text;
        }
    }
}
