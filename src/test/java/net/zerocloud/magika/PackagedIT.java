/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class PackagedIT {
    private static final String MODEL_ROOT = "net/zerocloud/magika/model/";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private Path sdkJar() { return Paths.get(System.getProperty("sdk.jar")); }

    @Test
    public void packagedPublicApiReallyRuns() throws Exception {
        probe(sdkJar(), "256m", new String[0], "smoke");
    }

    @Test
    public void runtimeDependenciesHaveJava8BaseBytecode() throws Exception {
        int checked = 0;
        for (String path : System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"))
                .split(File.pathSeparator)) {
            String name = new File(path).getName();
            if (!(name.startsWith("onnxruntime-") || name.startsWith("gson-")
                    || name.startsWith("error_prone_annotations-"))) { continue; }
            try (JarFile jar = new JarFile(path)) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/versions/")
                            || entry.getName().equals("module-info.class")) { continue; }
                    try (DataInputStream input = new DataInputStream(jar.getInputStream(entry))) {
                        assertEquals(0xcafebabe, input.readInt());
                        input.readUnsignedShort();
                        assertTrue(path + ": " + entry.getName(), input.readUnsignedShort() <= 52);
                    }
                }
                checked++;
            }
        }
        assertEquals("All three runtime dependency JARs checked", 3, checked);
    }

    @Test
    public void distributionContainsAuthenticatedAssetsAndJava8Classes() throws Exception {
        try (JarFile jar = new JarFile(sdkJar().toFile()); Magika sdk = Magika.create()) {
            assertNotNull(jar.getEntry("META-INF/LICENSE"));
            assertNotNull(jar.getEntry("META-INF/NOTICE"));
            assertEquals(sdk.getModelInfo().getSdkVersion(), jar.getManifest().getMainAttributes()
                    .getValue("Implementation-Version"));
            JsonObject manifest = JsonParser.parseString(new String(read(jar,
                    MODEL_ROOT + "asset-manifest.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(sdk.getModelInfo().getUpstreamCommit(), manifest.get("upstreamCommit").getAsString());
            for (JsonElement element : manifest.getAsJsonArray("assets")) {
                JsonObject asset = element.getAsJsonObject();
                String file = asset.get("file").getAsString();
                byte[] content = read(jar, MODEL_ROOT + file);
                assertEquals(asset.get("bytes").getAsInt(), content.length);
                assertEquals(asset.get("sha256").getAsString(), Fixtures.digest(content));
                assertEquals(asset.get("sha256").getAsString(), sdk.getModelInfo().getAssetDigests().get(file));
                assertTrue(asset.get("source").getAsString().contains(sdk.getModelInfo().getUpstreamCommit()));
            }
            for (JsonElement element : manifest.getAsJsonArray("referenceData")) {
                JsonObject asset = element.getAsJsonObject();
                byte[] content = Fixtures.resource("/reference/" + asset.get("file").getAsString());
                assertEquals(asset.get("bytes").getAsInt(), content.length);
                assertEquals(asset.get("sha256").getAsString(), Fixtures.digest(content));
            }
            StringBuilder order = new StringBuilder();
            for (JsonElement label : Fixtures.asset("config.min.json").getAsJsonArray("target_labels_space")) {
                if (order.length() > 0) { order.append('\n'); }
                order.append(label.getAsString());
            }
            assertEquals(manifest.getAsJsonObject("modelContract").get("orderedLabelsSha256").getAsString(),
                    Fixtures.digest(order.toString().getBytes(StandardCharsets.UTF_8)));
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                assertFalse(entry.getName(), entry.getName().startsWith("reference/"));
                assertFalse(entry.getName(), entry.getName().endsWith(".py"));
                if (entry.getName().endsWith(".class")) {
                    try (DataInputStream input = new DataInputStream(jar.getInputStream(entry))) {
                        assertEquals(0xcafebabe, input.readInt());
                        input.readUnsignedShort();
                        assertEquals(entry.getName(), 52, input.readUnsignedShort());
                    }
                }
            }
        }
    }

    @Test
    public void corruptedMissingAndIncompatibleAssetsCannotInitialize() throws Exception {
        byte[] model = Fixtures.resource("/" + MODEL_ROOT + "model.onnx");
        byte[] corrupt = model.clone();
        corrupt[corrupt.length / 2] ^= 1;
        reject("model.onnx", corrupt);
        reject("model.onnx", null);
        reject("model.onnx", Arrays.copyOf(model, model.length - 1));
        reject("model.onnx", Arrays.copyOf(model, model.length + 1));
        String config = new String(Fixtures.resource("/" + MODEL_ROOT + "config.min.json"), StandardCharsets.UTF_8);
        String reordered = config.replace("\"3gp\",\"ace\"", "\"ace\",\"3gp\"");
        assertNotEquals(config, reordered);
        reject("config.min.json", reordered.getBytes(StandardCharsets.UTF_8));
        String kb = new String(Fixtures.resource("/" + MODEL_ROOT + "content_types_kb.min.json"), StandardCharsets.UTF_8);
        reject("content_types_kb.min.json", kb.replace("application/pdf", "application/pdx").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void aValidOnnxModelWithTheWrongSignatureIsRejected() throws Exception {
        byte[] model = Fixtures.resource("/" + MODEL_ROOT + "model.onnx");
        byte[] expected = "bytes".getBytes(StandardCharsets.US_ASCII);
        byte[] replacement = "wrong".getBytes(StandardCharsets.US_ASCII);
        int changes = 0;
        for (int i = 0; i <= model.length - expected.length; i++) {
            if (Arrays.equals(expected, Arrays.copyOfRange(model, i, i + expected.length))) {
                System.arraycopy(replacement, 0, model, i, replacement.length);
                changes++;
            }
        }
        assertTrue(changes > 0);
        // Validate the fault fixture using real ORT. It remains a valid model, with a wrong input name.
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(1);
            try (OrtSession session = OrtEnvironment.getEnvironment().createSession(model, options)) {
                assertTrue(session.getInputNames().contains("wrong"));
                assertFalse(session.getInputNames().contains("bytes"));
            }
        }
        // The SDK rejects this during digest validation, before exposing any instance.
        reject("model.onnx", model);
    }

    @Test
    public void nativeLoaderFailuresHaveCategoryContextAndCause() throws Exception {
        probe(sdkJar(), "256m", new String[] {"-Donnxruntime.native.path=" + temporary.getRoot()},
                "failure", "MODEL_INITIALIZATION", "standard_v3_3");
    }

    @Test
    public void largeByteArrayDoesNotNeedAnotherCompleteCopy() throws Exception {
        // G1 permits a 64 MiB contiguous allocation within 96 MiB on Java 8;
        // Parallel GC's default old generation is itself only about 64 MiB.
        probe(sdkJar(), "96m", new String[] {"-XX:+UseG1GC"}, "large-array");
    }

    @Test
    public void fileLargerThanHeapAndIntRangeUsesBoundedSampling() throws Exception {
        probe(sdkJar(), "64m", new String[0], "path-large");
    }

    @Test
    public void generatedStreamLargerThanHeapAndIntRangeUsesBoundedSampling() throws Exception {
        probe(sdkJar(), "64m", new String[0], "stream-large");
    }

    @Test
    public void specialAndUnreadableFilesFailWithoutBlocking() throws Exception {
        probe(sdkJar(), "128m", new String[0], "path-errors");
    }

    @Test
    public void singleFileHandleSurvivesReplacementAndClosesOnReadFailures() throws Exception {
        probe(sdkJar(), "128m", new String[0], "path-handles");
    }

    @Test
    public void configuredThreadCountsCreateNativeWorkersAndCloseReleasesThem() throws Exception {
        probe(sdkJar(), "256m", new String[0], "threads");
    }

    @Test
    public void repeatedSequentialUseReleasesNativeResources() throws Exception {
        probe(sdkJar(), "128m", new String[0], "resources");
    }

    private void reject(String resource, byte[] replacement) throws Exception {
        Path changed = temporary.newFile("changed-" + System.nanoTime() + ".jar").toPath();
        try (JarFile original = new JarFile(sdkJar().toFile());
             JarOutputStream output = new JarOutputStream(Files.newOutputStream(changed))) {
            Enumeration<JarEntry> entries = original.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                boolean replace = entry.getName().equals(MODEL_ROOT + resource);
                if (replace && replacement == null) { continue; }
                output.putNextEntry(new JarEntry(entry.getName()));
                if (!entry.isDirectory()) { output.write(replace ? replacement : read(original, entry.getName())); }
                output.closeEntry();
            }
        }
        probe(changed, "128m", new String[0], "failure", "ASSET_VALIDATION", resource);
    }

    private void probe(Path jar, String heap, String[] vmArgs, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(new File(System.getProperty("java.home"), "bin/java").getPath());
        command.add("-Xmx" + heap);
        command.add("-XX:ActiveProcessorCount=2");
        command.addAll(Arrays.asList(vmArgs));
        command.add("-cp");
        StringBuilder classpath = new StringBuilder(Paths.get("target/test-classes").toAbsolutePath().toString());
        classpath.append(File.pathSeparator).append(jar.toAbsolutePath());
        for (String dependency : System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"))
                .split(File.pathSeparator)) {
            if (dependency.endsWith(".jar") && !new File(dependency).getCanonicalFile().equals(sdkJar().toFile().getCanonicalFile())) {
                classpath.append(File.pathSeparator).append(dependency);
            }
        }
        command.add(classpath.toString());
        command.add(PackagedProbe.class.getName());
        command.addAll(Arrays.asList(args));
        File log = temporary.newFile("probe-" + System.nanoTime() + ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start();
        try {
            int timeout = args[0].startsWith("path-") || args[0].startsWith("stream-") ? 45 : 300;
            assertTrue("Probe timed out: " + command, process.waitFor(timeout, TimeUnit.SECONDS));
            String output = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8);
            System.out.print(output);
            assertEquals(output, 0, process.exitValue());
        } finally {
            process.destroyForcibly();
            assertTrue("Probe did not terminate", process.waitFor(10, TimeUnit.SECONDS));
        }
    }

    private static byte[] read(JarFile jar, String name) throws Exception {
        assertNotNull(name, jar.getJarEntry(name));
        try (InputStream input = jar.getInputStream(jar.getJarEntry(name));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[8192];
            int count;
            while ((count = input.read(bytes)) != -1) { output.write(bytes, 0, count); }
            return output.toByteArray();
        }
    }
}
