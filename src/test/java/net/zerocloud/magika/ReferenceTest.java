/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReferenceTest {
    @Test
    public void all9261OfficialFeaturesMatchExactly() throws Exception {
        int count = 0;
        try (JsonReader reader = Fixtures.reference("features_extraction_examples.json.gz",
                "f8c78b07f3070089799f97d530658744604970688a15e25479cf9c3afbe49e41")) {
            reader.beginArray();
            while (reader.hasNext()) {
                JsonObject example = JsonParser.parseReader(reader).getAsJsonObject();
                JsonObject args = example.getAsJsonObject("args");
                assertEquals(128, args.get("beg_size").getAsInt());
                assertEquals(64, args.get("end_size").getAsInt());
                assertEquals(512, args.get("block_size").getAsInt());
                assertEquals(256, args.get("padding_token").getAsInt());
                assertEquals(0, args.get("mid_size").getAsInt());
                assertFalse(args.get("use_inputs_at_offsets").getAsBoolean());
                byte[] content = Base64.getDecoder().decode(example.get("content_base64").getAsString());
                int[] actual = Features.extract(content, 128, 64, 512, 256);
                JsonObject expected = example.getAsJsonObject("features");
                int[] combined = new int[192];
                copy(expected.getAsJsonArray("beg"), combined, 0);
                copy(expected.getAsJsonArray("end"), combined, 128);
                for (String field : new String[] {"mid", "offset_0x8000_0x8007", "offset_0x8800_0x8807",
                        "offset_0x9000_0x9007", "offset_0x9800_0x9807"}) {
                    assertEquals(0, expected.getAsJsonArray(field).size());
                }
                assertArrayEquals("Feature reference #" + count + ": " + example.get("metadata"), combined, actual);
                count++;
            }
            reader.endArray();
            assertFalse(reader.hasNext());
        }
        assertEquals(9261, count);
        System.out.println("Exact feature references: " + count);
    }

    private static void copy(JsonArray values, int[] target, int offset) {
        for (JsonElement value : values) {
            target[offset++] = value.getAsInt();
        }
    }

    @Test
    public void all141ContentsMatchThroughPublicApiInEveryMode() throws Exception {
        JsonObject kb = Fixtures.asset("content_types_kb.min.json");
        int count = 0;
        Map<PredictionMode, Integer> counts = new EnumMap<>(PredictionMode.class);
        Map<PredictionMode, Integer> modelUses = new EnumMap<>(PredictionMode.class);
        for (PredictionMode mode : PredictionMode.values()) {
            counts.put(mode, 0);
            modelUses.put(mode, 0);
        }
        double maxError = 0;
        try (Magika high = Magika.builder().predictionMode(PredictionMode.HIGH_CONFIDENCE).build();
             Magika medium = Magika.builder().predictionMode(PredictionMode.MEDIUM_CONFIDENCE).build();
             Magika best = Magika.builder().predictionMode(PredictionMode.BEST_GUESS).build();
             JsonReader reader = Fixtures.reference("standard_v3_3-inference_examples_by_content.json.gz",
                     "3eda361e3d7290457bc859c02d491a5d94fcda3df75f1c53147adda57c0f756f")) {
            Map<PredictionMode, Magika> instances = new EnumMap<>(PredictionMode.class);
            instances.put(PredictionMode.HIGH_CONFIDENCE, high);
            instances.put(PredictionMode.MEDIUM_CONFIDENCE, medium);
            instances.put(PredictionMode.BEST_GUESS, best);
            reader.beginArray();
            while (reader.hasNext()) {
                JsonObject example = JsonParser.parseReader(reader).getAsJsonObject();
                PredictionMode mode = PredictionMode.valueOf(example.get("prediction_mode").getAsString()
                        .toUpperCase(Locale.ROOT));
                assertEquals("ok", example.get("status").getAsString());
                JsonObject expected = example.getAsJsonObject("prediction");
                byte[] content = Base64.getDecoder().decode(example.get("content_base64").getAsString());
                DetectionResult actual = instances.get(mode).identify(content);
                String message = mode + " content reference #" + count;
                maxError = Math.max(maxError, Fixtures.assertReference(message, expected, kb, actual));
                if (actual.isModelUsed()) {
                    modelUses.put(mode, modelUses.get(mode) + 1);
                }
                counts.put(mode, counts.get(mode) + 1);
                count++;
            }
            reader.endArray();
            assertFalse(reader.hasNext());
        }
        assertEquals(141, count);
        for (PredictionMode mode : PredictionMode.values()) {
            assertEquals(mode + " references", 47, (int) counts.get(mode));
            assertEquals(mode + " model inferences", 43, (int) modelUses.get(mode));
            System.out.println(mode + " references: " + counts.get(mode) + ", modelUsed=" + modelUses.get(mode));
        }
        System.out.println("All content references: " + count + ", max absolute score error=" + maxError);
    }
}
