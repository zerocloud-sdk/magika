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
import java.util.zip.GZIPInputStream;
import static org.junit.Assert.*;

final class Fixtures {
    private Fixtures() { }

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
}
