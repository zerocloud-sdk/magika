/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Public Path API probes run in a killable, heap-limited JVM against the SDK JAR. */
final class PathProbe {
    static void run(String scenario) throws Exception {
        Path directory = Files.createTempDirectory("magika-path-");
        try {
            switch (scenario) {
                case "path-large": largeFile(directory); break;
                case "path-errors": errors(directory); break;
                case "path-handles": handles(directory); break;
                default: throw new AssertionError(scenario);
            }
        } finally {
            // All fixtures are immediate children; never follow links or open special files for cleanup.
            try (Stream<Path> files = Files.list(directory)) {
                for (Path file : (Iterable<Path>) files::iterator) { Files.deleteIfExists(file); }
            }
            Files.delete(directory);
        }
    }

    private static void largeFile(Path directory) throws Exception {
        Path file = directory.resolve("large.bin");
        long length = (5L << 30) + 17;
        byte[] representative = new byte[8192];
        for (int i = 0; i < representative.length; i++) { representative[i] = (byte) (i * 31); }
        try (SeekableByteChannel output = Files.newByteChannel(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            output.write(ByteBuffer.wrap(representative, 0, 4096));
            output.position(length - 4096);
            output.write(ByteBuffer.wrap(representative, 4096, 4096));
        }
        check(Files.size(file) == length && length > Runtime.getRuntime().maxMemory(), "Large-file fixture size");
        try (Magika sdk = Magika.create()) {
            ObservedFile observed = new ObservedFile(file, channel -> { });
            DetectionResult actual = sdk.identify(observed.path);
            check(actual.isModelUsed(), "Large-file result must use real ORT");
            equivalent(sdk.identify(representative), actual);
            equivalent(actual, sdk.identify(file));
            check(observed.opens == 1 && observed.closes == 1, "Single owned handle for large-file sampling");
            check(observed.bytesRead <= 8192, "Sampling read more than the bounded windows");
            check(targetHandles(file) == 0, "Large-file handle survived identification");
            System.out.println("Large regular file: bytes=" + Files.size(file) + ", maxHeap="
                    + Runtime.getRuntime().maxMemory() + ", sampledBytes=" + observed.bytesRead
                    + ", opens=" + observed.opens + ", closes=" + observed.closes
                    + ", label=" + actual.getLabel() + ", raw=" + actual.getRawPrediction().get().getLabel()
                    + ", score=" + actual.getScore() + ", reason=" + actual.getOverwriteReason());
        }
    }

    private static void errors(Path directory) throws Exception {
        Path file = Files.write(directory.resolve("readable.pdf"), MagikaTest.PDF);
        Path missing = directory.resolve("missing");
        Path broken = Files.createSymbolicLink(directory.resolve("broken"), missing);
        Path link = Files.createSymbolicLink(directory.resolve("link"), file);
        Path directoryLink = Files.createSymbolicLink(directory.resolve("directory-link"), directory);
        Path fifo = directory.resolve("fifo");
        Process mkfifo = new ProcessBuilder("mkfifo", fifo.toString()).redirectErrorStream(true).start();
        try {
            check(mkfifo.waitFor(5, TimeUnit.SECONDS) && mkfifo.exitValue() == 0, "Create real FIFO");
        } finally { mkfifo.destroyForcibly(); }
        Path fifoLink = Files.createSymbolicLink(directory.resolve("fifo-link"), fifo);
        try (Magika sdk = Magika.create()) {
            equivalent(sdk.identify(file), sdk.identify(link));
            for (Path special : Arrays.asList(directory, directoryLink, Paths.get("/dev/null"),
                    Paths.get("/dev/zero"), fifo, fifoLink)) {
                ObservedFile observed = new ObservedFile(special, channel -> { });
                inputFailure(sdk, observed.path, IOException.class);
                check(observed.opens == 0, "Special file was opened: " + special);
                inputFailure(sdk, special, IOException.class);
                System.out.println("Rejected before content open: " + special);
            }
            inputFailure(sdk, missing, java.nio.file.NoSuchFileException.class);
            inputFailure(sdk, broken, java.nio.file.NoSuchFileException.class);
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
            try {
                Files.setPosixFilePermissions(file, Collections.emptySet());
                // Permission evidence must be an actual denied open, not isReadable or a skipped root test.
                try (SeekableByteChannel unexpected = Files.newByteChannel(file, StandardOpenOption.READ)) {
                    throw new AssertionError("Permission probe requires an unprivileged user; open succeeded: " + unexpected);
                } catch (AccessDeniedException expected) {
                    inputFailure(sdk, file, AccessDeniedException.class);
                    System.out.println("Permission denial confirmed by real open and public SDK: " + expected);
                }
            } finally { Files.setPosixFilePermissions(file, permissions); }
            check("pdf".equals(sdk.identify(file).getLabel()), "Input failures damaged the SDK");
        }
    }

    private static void handles(Path directory) throws Exception {
        Path file = directory.resolve("sample.bin");
        byte[] original = new byte[16384];
        for (int i = 0; i < original.length; i++) { original[i] = (byte) (i * 37); }
        try (Magika sdk = Magika.create()) {
            Files.write(file, original);
            DetectionResult expected = sdk.identify(original);
            // Replacing the directory entry cannot change the already opened file's tail.
            boolean[] replaced = {false};
            ObservedFile replacement = new ObservedFile(file, channel -> {
                if (!replaced[0]) {
                    replaced[0] = true;
                    Files.delete(file);
                    Files.write(file, new byte[original.length]);
                }
            });
            equivalent(expected, sdk.identify(replacement.path));
            check(replacement.opens == 1 && replacement.closes == 1, "Path replacement reopened the file");
            Files.write(file, original);
            ObservedFile shortReads = new ObservedFile(file, channel -> { });
            shortReads.maxRead = 13;
            equivalent(expected, sdk.identify(shortReads.path));
            check(shortReads.opens == 1 && shortReads.closes == 1, "Short reads reopened or leaked the file");

            // Warm both failure paths before sampling descriptors; no GC masks leaked channels.
            for (int i = 0; i < 10; i++) { failureCycle(sdk, file, original); }
            List<Long> descriptors = new ArrayList<>();
            descriptors.add(entries());
            for (int round = 0; round < 3; round++) {
                for (int i = 0; i < 100; i++) {
                    Files.write(file, original);
                    equivalent(expected, sdk.identify(file));
                    failureCycle(sdk, file, original);
                }
                check(targetHandles(file) == 0, "Owned file handles survived success/failure");
                descriptors.add(entries());
                check(descriptors.get(descriptors.size() - 1) <= descriptors.get(0) + 2,
                        "Continuously growing file handles: " + descriptors);
            }
            System.out.println("Path handles: " + descriptors + "; target handles=" + targetHandles(file)
                    + "; 300 successes + 300 real truncations + 300 closed-channel read failures; no GC"
                    + "; replacement/short-read opens=" + replacement.opens + "/" + shortReads.opens
                    + "; causes=EOFException,ClosedChannelException; no retries");
        }
    }

    private static void failureCycle(Magika sdk, Path file, byte[] original) throws Exception {
        Files.write(file, original);
        ObservedFile truncated = new ObservedFile(file, channel -> {
            try (SeekableByteChannel writer = Files.newByteChannel(file, StandardOpenOption.WRITE)) { writer.truncate(0); }
        });
        inputFailure(sdk, truncated.path, EOFException.class);
        check(truncated.opens == 1 && truncated.reads == 1 && truncated.closes == 1, "Truncation retry or leaked handle");
        check(targetHandles(file) == 0, "Truncation leaked an open handle");
        Files.write(file, original);
        ObservedFile closed = new ObservedFile(file, SeekableByteChannel::close);
        inputFailure(sdk, closed.path, ClosedChannelException.class);
        check(closed.opens == 1 && closed.reads == 1 && closed.closes == 1, "Read failure retry or leaked handle");
    }

    private static void inputFailure(Magika sdk, Path path, Class<? extends Throwable> cause) {
        try {
            sdk.identify(path);
            throw new AssertionError("Input error returned a result: " + path);
        } catch (MagikaException e) {
            check(e.getCategory() == MagikaException.Category.INPUT, "Input category: " + e);
            check(path.toString().equals(e.getContext()) && e.getMessage().contains(path.toString()), "Path context: " + e);
            check(cause.isInstance(e.getCause()), "Original input cause: " + e.getCause());
        }
    }

    private static void equivalent(DetectionResult expected, DetectionResult actual) {
        Fixtures.assertEquivalent("Packaged Path result", expected, actual);
    }

    private static long entries() throws IOException {
        try (Stream<Path> files = Files.list(Paths.get("/proc/self/fd"))) { return files.count(); }
    }

    private static long targetHandles(Path file) throws IOException {
        String target = file.toAbsolutePath().toString();
        long count = 0;
        try (Stream<Path> files = Files.list(Paths.get("/proc/self/fd"))) {
            for (Path descriptor : (Iterable<Path>) files::iterator) {
                String link = Files.readSymbolicLink(descriptor).toString();
                if (link.equals(target) || link.equals(target + " (deleted)")) { count++; }
            }
        }
        return count;
    }

    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
