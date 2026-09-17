/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class ModelBoundaryTest {
    @Test
    public void actualModelFeaturesUse1024PerEndAndUnsignedBytes() {
        for (int size : new int[] {0, 1, 7, 8, 1023, 1024, 1025, 2047, 2048, 2049,
                4095, 4096, 4097, 8191, 8192, 8193, 32768}) {
            byte[] content = new byte[size];
            for (int i = 0; i < size; i++) {
                content[i] = (byte) (128 + i % 128);
            }
            int[] actual = extract(content);
            assertEquals(2048, actual.length);
            for (int i = 0; i < 1024; i++) {
                assertEquals("head at length " + size, i < size ? content[i] & 255 : 256, actual[i]);
                int inputIndex = size - 1024 + i;
                assertEquals("tail at length " + size, inputIndex < 0 ? 256 : content[inputIndex] & 255,
                        actual[1024 + i]);
            }
        }
    }

    @Test
    public void strippingNeverEscapes4096ByteWindows() {
        for (int spaces : new int[] {0, 1, 1023, 1024, 1025, 4094, 4095, 4096, 4097, 8192}) {
            byte[] content = new byte[spaces * 2 + 1];
            Arrays.fill(content, (byte) ' ');
            content[spaces] = 'A';
            int[] actual = extract(content);
            if (spaces >= 4096) {
                for (int token : actual) {
                    assertEquals(256, token);
                }
            } else {
                assertEquals('A', actual[0]);
                assertEquals('A', actual[2047]);
                // Opposite-end whitespace remains; only the outside of each window is stripped.
                int remaining = Math.min(spaces, 4095 - spaces);
                for (int i = 1; i < 1024; i++) {
                    int expected = i <= remaining ? ' ' : 256;
                    assertEquals("head with spaces=" + spaces, expected, actual[i]);
                    assertEquals("tail with spaces=" + spaces, expected, actual[2047 - i]);
                }
            }
        }
    }

    @Test
    public void onlyTheSixPythonByteWhitespaceValuesAreStripped() {
        for (int value = 0; value <= 255; value++) {
            int expected = value == 32 || (value >= 9 && value <= 13) ? 256 : value;
            int[] actual = extract(new byte[] {(byte) value});
            assertEquals(expected, actual[0]);
            assertEquals(expected, actual[2047]);
        }
        int[] actual = extract(new byte[] {32, 0, 9, 65, 32, 0, 10});
        assertArrayEquals(new int[] {0, 9, 65, 32, 0, 10, 256}, Arrays.copyOf(actual, 7));
        assertArrayEquals(new int[] {256, 32, 0, 9, 65, 32, 0}, Arrays.copyOfRange(actual, 2041, 2048));
    }

    @Test
    public void everyLabelHasBelowEqualAndAboveThresholdCoverageInHighAndMediumModes() throws Exception {
        ModelAssets assets = ModelAssets.load();
        JsonObject config = Fixtures.asset("config.min.json");
        JsonObject kb = Fixtures.asset("content_types_kb.min.json");
        JsonObject thresholds = config.getAsJsonObject("thresholds");
        JsonObject mappings = config.getAsJsonObject("overwrite_map");
        for (PredictionMode mode : new PredictionMode[] {PredictionMode.HIGH_CONFIDENCE,
                PredictionMode.MEDIUM_CONFIDENCE}) {
            int comparisons = 0;
            for (JsonElement element : config.getAsJsonArray("target_labels_space")) {
                String rawLabel = element.getAsString();
                String mapped = mappings.has(rawLabel) ? mappings.get(rawLabel).getAsString() : rawLabel;
                double threshold = mode == PredictionMode.HIGH_CONFIDENCE && thresholds.has(rawLabel)
                        ? thresholds.get(rawLabel).getAsDouble()
                        : config.get("medium_confidence_threshold").getAsDouble();
                double[] scores = {Math.nextDown(threshold), threshold, Math.nextUp(threshold)};
                for (int i = 0; i < scores.length; i++) {
                    String expectedLabel = i == 0
                            ? (kb.getAsJsonObject(mapped).get("is_text").getAsBoolean() ? "txt" : "unknown")
                            : mapped;
                    OverwriteReason expectedReason = rawLabel.equals(expectedLabel) ? OverwriteReason.NONE
                            : (i == 0 ? OverwriteReason.LOW_CONFIDENCE : OverwriteReason.OVERWRITE_MAP);
                    prediction(assets, kb, mode, rawLabel, scores[i], expectedLabel, expectedReason);
                    comparisons++;
                }
            }
            assertEquals(642, comparisons);
            System.out.println(mode + " threshold comparisons: " + comparisons);
        }
    }

    @Test
    public void lowScoresPreserveMappingFallbackReasonsAndOriginalScore() throws Exception {
        ModelAssets assets = ModelAssets.load();
        JsonObject kb = Fixtures.asset("content_types_kb.min.json");
        for (PredictionMode mode : PredictionMode.values()) {
            boolean bestGuess = mode == PredictionMode.BEST_GUESS;
            OverwriteReason mapping = bestGuess ? OverwriteReason.OVERWRITE_MAP : OverwriteReason.LOW_CONFIDENCE;
            OverwriteReason fallback = bestGuess ? OverwriteReason.NONE : OverwriteReason.LOW_CONFIDENCE;
            // Both mappings must survive even a score of zero in BEST_GUESS.
            prediction(assets, kb, mode, "randomtxt", 0, "txt", mapping);
            prediction(assets, kb, mode, "randombytes", 0, "unknown", mapping);
            prediction(assets, kb, mode, "html", 0, bestGuess ? "html" : "txt", fallback);
            prediction(assets, kb, mode, "pdf", 0, bestGuess ? "pdf" : "unknown", fallback);
            // A fallback to the original label is not an overwrite.
            prediction(assets, kb, mode, "txt", 0, "txt", OverwriteReason.NONE);
        }
    }

    private static void prediction(ModelAssets assets, JsonObject kb, PredictionMode mode,
                                   String rawLabel, double score, String label, OverwriteReason reason) {
        DetectionResult result = ModelAdapter.resultForPrediction(assets, new RawPrediction(rawLabel, score), mode);
        String message = mode + " " + rawLabel + " at score " + score;
        assertEquals(message, label, result.getLabel());
        assertEquals(message, reason, result.getOverwriteReason());
        assertEquals(message, Fixtures.mime(kb, label), result.getMimeType());
        assertEquals(message, score, result.getScore(), 0);
        assertEquals(message, rawLabel, result.getRawPrediction().get().getLabel());
        assertEquals(message, score, result.getRawPrediction().get().getScore(), 0);
        assertTrue(message, result.isModelUsed());
        assertEquals("standard_v3_3", result.getModelVersion());
    }

    private static int[] extract(byte[] content) {
        return Features.extract(content, 1024, 1024, 4096, 256);
    }
}
