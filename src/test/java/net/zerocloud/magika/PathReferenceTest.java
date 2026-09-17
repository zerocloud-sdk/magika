/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class PathReferenceTest {
    private static final String COMMIT = "9f225aa480e675af44343b9160f077073ed1b752";
    private static final String SOURCE = "https://raw.githubusercontent.com/google/magika/" + COMMIT + "/";
    private static final String REFERENCE = "standard_v3_3-inference_examples_by_path.json.gz";
    private static final String DIGEST = "9b7dedd1e1fbddf98a3ab6598fe2674deae5ba5597f6ba3bbc466737a8b357d4";

    @Test
    public void all207PathsMatchReferencesAndCompleteBytesInEveryMode() throws Exception {
        Set<String> files = authenticateOriginalFiles();
        JsonObject kb = Fixtures.asset("content_types_kb.min.json");
        int count = 0;
        double maxError = 0;
        // Read all entries once and group by the declared mode; unknown modes fail conversion.
        java.util.Map<PredictionMode, java.util.List<JsonObject>> cases = new java.util.EnumMap<>(PredictionMode.class);
        for (PredictionMode mode : PredictionMode.values()) { cases.put(mode, new java.util.ArrayList<>()); }
        try (JsonReader reader = Fixtures.reference(REFERENCE, DIGEST)) {
            reader.beginArray();
            while (reader.hasNext()) {
                JsonObject example = JsonParser.parseReader(reader).getAsJsonObject();
                PredictionMode mode = PredictionMode.valueOf(example.get("prediction_mode").getAsString()
                        .toUpperCase(Locale.ROOT));
                cases.get(mode).add(example);
            }
            reader.endArray();
            assertFalse(reader.hasNext());
        }
        for (PredictionMode mode : PredictionMode.values()) {
            Set<String> seen = new HashSet<>();
            int modelUses = 0;
            try (Magika sdk = Magika.builder().predictionMode(mode).build()) {
                for (JsonObject example : cases.get(mode)) {
                    String original = example.get("path").getAsString();
                    String message = mode + " " + original;
                    assertEquals(message, "ok", example.get("status").getAsString());
                    assertTrue(message, seen.add(original));
                    assertTrue(message, files.contains(original));
                    Path path = Paths.get(getClass().getResource("/reference/" + original).toURI());
                    DetectionResult actual = sdk.identify(path);
                    maxError = Math.max(maxError,
                            Fixtures.assertReference(message, example.getAsJsonObject("prediction"), kb, actual));
                    Fixtures.assertEquivalent(message, sdk.identify(Files.readAllBytes(path)), actual);
                    if (actual.isModelUsed()) { modelUses++; }
                    count++;
                }
            }
            assertEquals(mode.toString(), files, seen);
            assertEquals(69, seen.size());
            assertEquals(68, modelUses);
            System.out.println(mode + " path references: " + seen.size() + ", modelUsed=" + modelUses);
        }
        assertEquals(207, count);
        System.out.println("All path references: " + count + ", max absolute score error=" + maxError);
    }

    private static Set<String> authenticateOriginalFiles() throws Exception {
        byte[] bytes = Fixtures.resource("/reference/path-manifest.json");
        assertEquals("a7de97b903d30cd48cc34d07364635e988bca35996210f55831b0f39b23f1731", Fixtures.digest(bytes));
        JsonObject manifest = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(COMMIT, manifest.get("upstreamCommit").getAsString());
        assertEquals("standard_v3_3", manifest.get("modelVersion").getAsString());
        JsonObject reference = manifest.getAsJsonObject("reference");
        assertEquals(REFERENCE, reference.get("file").getAsString());
        assertEquals(SOURCE + "tests_data/reference/" + REFERENCE, reference.get("source").getAsString());
        assertEquals(DIGEST, reference.get("sha256").getAsString());
        assertEquals(2604, Fixtures.resource("/reference/" + REFERENCE).length);
        assertEquals(2604, reference.get("bytes").getAsInt());
        assertEquals(207, reference.get("cases").getAsInt());
        for (PredictionMode mode : PredictionMode.values()) {
            assertEquals(69, reference.getAsJsonObject("casesPerMode").get(mode.name().toLowerCase(Locale.ROOT)).getAsInt());
        }
        Set<String> files = new HashSet<>();
        for (JsonElement element : manifest.getAsJsonArray("files")) {
            JsonObject file = element.getAsJsonObject();
            String path = file.get("path").getAsString();
            assertTrue(path, files.add(path));
            assertTrue(path, path.startsWith("tests_data/basic/") && !path.contains(".."));
            assertEquals(SOURCE + path, file.get("source").getAsString());
            byte[] content = Fixtures.resource("/reference/" + path);
            assertEquals(path, file.get("bytes").getAsInt(), content.length);
            assertEquals(path, file.get("sha256").getAsString(), Fixtures.digest(content));
        }
        assertEquals(69, files.size());
        System.out.println("Authenticated path reference and 69 original files at " + COMMIT);
        return files;
    }
}
