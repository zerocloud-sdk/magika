/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import static org.junit.Assert.*;

final class Fixtures {
    private Fixtures() { }

    static List<byte[]> boundaryContents() {
        List<byte[]> cases = new ArrayList<>();
        for (int size : new int[] {0, 1, 7, 8, 1023, 1024, 1025, 2047, 2048, 2049,
                4095, 4096, 4097, 8191, 8192, 8193, 32768}) {
            byte[] content = new byte[size];
            for (int i = 0; i < size; i++) { content[i] = (byte) (128 + i % 128); }
            cases.add(content);
        }
        for (int spaces : new int[] {0, 1, 1023, 1024, 1025, 4088, 4089, 4094, 4095, 4096, 4097, 8192}) {
            byte[] content = new byte[spaces * 2 + 8];
            Arrays.fill(content, (byte) ' ');
            System.arraycopy(MagikaTest.PDF, 0, content, spaces, 8);
            cases.add(content);
        }
        byte[][] utf8 = {{0}, {(byte) 0xc2, (byte) 0x80}, {(byte) 0xe0, (byte) 0xa0, (byte) 0x80},
                {(byte) 0xf4, (byte) 0x8f, (byte) 0xbf, (byte) 0xbf}, {(byte) 0x80}, {(byte) 0xc0, (byte) 0x80},
                {(byte) 0xed, (byte) 0xa0, (byte) 0x80}, {(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80},
                {(byte) 0xff}, {(byte) 0xf0, (byte) 0x9f, (byte) 0x92}, {(byte) 0xc2, 32}};
        cases.addAll(Arrays.asList(utf8));
        for (byte[] sequence : utf8) {
            // Exercise both complete and split UTF-8 at the first window's boundary.
            for (int end : new int[] {4096, 4097}) {
                byte[] content = new byte[20000];
                byte[] spaces = {9, 10, 11, 12, 13, 32};
                for (int i = 0; i < content.length; i++) { content[i] = spaces[i % spaces.length]; }
                System.arraycopy(sequence, 0, content, end - sequence.length, sequence.length);
                content[10000] = (byte) 0xff;
                cases.add(content);
            }
        }
        byte[] whitespace = new byte[20000];
        Arrays.fill(whitespace, (byte) ' ');
        cases.add(whitespace);
        return cases;
    }

    static JsonReader reference(String name, String sha256) throws Exception {
        byte[] bytes = resource("/reference/" + name);
        assertEquals("Pinned reference digest", sha256, digest(bytes));
        return new JsonReader(new InputStreamReader(new GZIPInputStream(new ByteArrayInputStream(bytes)),
                StandardCharsets.UTF_8));
    }

    static JsonObject asset(String name) throws Exception {
        return JsonParser.parseString(new String(resource("/net/zerocloud/magika/model/" + name),
                StandardCharsets.UTF_8)).getAsJsonObject();
    }

    static byte[] resource(String name) throws Exception {
        try (InputStream input = Fixtures.class.getResourceAsStream(name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertNotNull("Missing resource " + name, input);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    static String digest(byte[] bytes) throws Exception {
        StringBuilder output = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            output.append(String.format("%02x", value & 255));
        }
        return output.toString();
    }

    static String mime(JsonObject kb, String label) {
        JsonObject type = kb.getAsJsonObject(label);
        return type.get("mime_type").isJsonNull()
                ? (type.get("is_text").getAsBoolean() ? "text/plain" : "application/octet-stream")
                : type.get("mime_type").getAsString();
    }

    static double assertReference(String message, JsonObject expected, JsonObject kb, DetectionResult actual) {
        assertEquals(message, expected.get("output").getAsString(), actual.getLabel());
        assertEquals(message, mime(kb, actual.getLabel()), actual.getMimeType());
        assertEquals(message, OverwriteReason.valueOf(expected.get("overwrite_reason").getAsString()
                .toUpperCase(Locale.ROOT)), actual.getOverwriteReason());
        assertEquals(message, expected.get("score").getAsDouble(), actual.getScore(), 1e-5);
        assertEquals("standard_v3_3", actual.getModelVersion());
        String dl = expected.get("dl").getAsString();
        if ("undefined".equals(dl)) {
            assertFalse(message, actual.getRawPrediction().isPresent());
            assertFalse(message, actual.isModelUsed());
            assertEquals(1.0, actual.getScore(), 0);
            assertEquals(OverwriteReason.NONE, actual.getOverwriteReason());
        } else {
            assertTrue(message, actual.isModelUsed());
            assertEquals(message, dl, actual.getRawPrediction().get().getLabel());
            assertEquals(actual.getScore(), actual.getRawPrediction().get().getScore(), 0);
        }
        return Math.abs(expected.get("score").getAsDouble() - actual.getScore());
    }

    static void assertEquivalent(String message, DetectionResult expected, DetectionResult actual) {
        assertEquals(message, expected.getLabel(), actual.getLabel());
        assertEquals(message, expected.getMimeType(), actual.getMimeType());
        assertEquals(message, expected.getScore(), actual.getScore(), 0);
        assertEquals(message, expected.getOverwriteReason(), actual.getOverwriteReason());
        assertEquals(message, expected.isModelUsed(), actual.isModelUsed());
        assertEquals(message, expected.getModelVersion(), actual.getModelVersion());
        assertEquals(message, expected.getRawPrediction().isPresent(), actual.getRawPrediction().isPresent());
        if (expected.getRawPrediction().isPresent()) {
            assertEquals(message, expected.getRawPrediction().get().getLabel(), actual.getRawPrediction().get().getLabel());
            assertEquals(message, expected.getRawPrediction().get().getScore(), actual.getRawPrediction().get().getScore(), 0);
        }
    }
}
