/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.benchmarks;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import net.zerocloud.magika.DetectionResult;
import net.zerocloud.magika.Magika;

/** Fixed corpus metadata and one sequential oracle per file, not a request history. */
final class Corpus {
    final List<Entry> entries = new ArrayList<>();
    final String manifestSha256;
    long totalBytes;

    Corpus(Path repository, Magika sdk) throws Exception {
        Path root = repository.resolve("src/test/resources/reference");
        Path manifest = root.resolve("path-manifest.json");
        manifestSha256 = sha256(manifest);
        JsonObject data;
        try (Reader input = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            data = JsonParser.parseReader(input).getAsJsonObject();
        }
        require(data.get("upstreamCommit").getAsString().equals(sdk.getModelInfo().getUpstreamCommit()), "Corpus provenance");
        for (JsonElement value : data.getAsJsonArray("files")) {
            JsonObject file = value.getAsJsonObject();
            Path path = root.resolve(file.get("path").getAsString());
            long bytes = file.get("bytes").getAsLong();
            String digest = file.get("sha256").getAsString();
            require(Files.size(path) == bytes && sha256(path).equals(digest), "Corpus authentication: " + path);
            entries.add(new Entry(path, bytes, digest, sdk.identify(path)));
            totalBytes += bytes;
        }
        require(entries.size() == 69, "Expected the fixed 69-file corpus");
        require(entries.stream().filter(e -> e.expected.isModelUsed()).count() == 68, "Expected 68 model inputs");
    }

    Entry at(int index) { return entries.get(index % entries.size()); }

    static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) { digest.update(buffer, 0, count); }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) { hex.append(String.format("%02x", b & 255)); }
        return hex.toString();
    }

    static void require(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }

    static final class Entry {
        final Path path;
        final long bytes;
        final String sha256;
        final DetectionResult expected;

        Entry(Path path, long bytes, String sha256, DetectionResult expected) {
            this.path = path;
            this.bytes = bytes;
            this.sha256 = sha256;
            this.expected = expected;
        }

        void validate(DetectionResult actual, boolean batch) {
            require(expected.getLabel().equals(actual.getLabel())
                    && expected.getMimeType().equals(actual.getMimeType())
                    && expected.getOverwriteReason() == actual.getOverwriteReason()
                    && expected.getModelVersion().equals(actual.getModelVersion())
                    && expected.isModelUsed() == actual.isModelUsed()
                    && expected.getRawPrediction().isPresent() == actual.getRawPrediction().isPresent()
                    && Math.abs(expected.getScore() - actual.getScore()) <= (batch ? 1e-5 : 0), "Result mismatch: " + path);
            if (actual.isModelUsed()) {
                require(expected.getRawPrediction().get().getLabel().equals(actual.getRawPrediction().get().getLabel())
                        && actual.getRawPrediction().get().getScore() == actual.getScore(), "Raw prediction: " + path);
            } else {
                require(actual.getScore() == 1.0, "Rule score: " + path);
            }
        }
    }
}
