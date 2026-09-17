/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

import java.io.EOFException;
import java.io.IOException;
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
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import net.zerocloud.magika.BatchIdentificationException.Stage;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class BatchTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void twoModelRowsPreserveSingleRowNumericalAccuracy() throws Exception {
        Path path = file(new byte[] {(byte) 128, (byte) 129, (byte) 130, (byte) 131,
                (byte) 132, (byte) 133, (byte) 134, (byte) 135});
        try (Magika sdk = Magika.create()) {
            DetectionResult expected = sdk.identify(path);
            sdk.identifyAll(Arrays.asList(path, path).iterator(), item ->
                    Fixtures.assertBatchEquivalent("two identical model rows", expected, item.getResult().get()));
        }
    }

    @Test
    public void argumentsAndClosedStateRejectBeforeConsumption() throws Exception {
        CountingPaths paths = new CountingPaths(file(MagikaTest.PDF), 3);
        Magika sdk = Magika.create();
        try {
            assertThrows(IllegalArgumentException.class, () -> sdk.identifyAll(null, item -> fail()));
            assertThrows(IllegalArgumentException.class, () -> sdk.identifyAll(paths, null));
        } finally { sdk.close(); }
        assertThrows(IllegalStateException.class, () -> sdk.identifyAll(paths, item -> fail()));
        assertEquals(0, paths.hasNextCalls);
        assertEquals(0, paths.consumed);
    }

    @Test
    public void defaultConfiguredAndImmutableBatchSizesBoundConsumption() throws Exception {
        Path path = file(MagikaTest.PDF);
        for (int invalid : new int[] {0, -1, Integer.MIN_VALUE, 262144, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> Magika.builder().batchSize(invalid));
        }
        try (Magika defaults = Magika.create(); Magika builderDefaults = Magika.builder().build()) {
            for (int count : new int[] {0, 1, 31, 32, 33, 64, 65}) {
                assertConsumption(defaults, path, count, 32);
                assertConsumption(builderDefaults, path, count, 32);
            }
        }
        Magika.Builder builder = Magika.builder().batchSize(1);
        try (Magika one = builder.build(); Magika three = builder.batchSize(3).build();
             Magika maximum = builder.batchSize(262143).build()) {
            builder.batchSize(2);
            for (int count : new int[] {0, 1, 2, 3, 4, 6, 7}) {
                assertConsumption(one, path, count, 1);
                assertConsumption(three, path, count, 3);
            }
            // A maximum valid configuration must not allocate maximum storage for tiny inputs.
            assertConsumption(maximum, path, 2, 262143);
        }
    }

    @Test
    public void mixedBoundariesDuplicatesAndFailuresMatchSinglePathsInAllModes() throws Exception {
        List<Path> paths = new ArrayList<>();
        for (byte[] bytes : Fixtures.boundaryContents()) { paths.add(file(bytes)); }
        Path missing = temporary.getRoot().toPath().resolve("missing");
        paths.add(1, missing);
        paths.add(5, temporary.getRoot().toPath());
        paths.add(8, paths.get(3));
        paths.add(paths.get(0));
        List<BatchItemResult> retained = new ArrayList<>();
        for (PredictionMode mode : PredictionMode.values()) {
            for (int batchSize : new int[] {1, 7, 32}) {
                try (Magika sdk = Magika.builder().predictionMode(mode).batchSize(batchSize).build()) {
                    List<DetectionResult> expected = new ArrayList<>();
                    for (Path path : paths) {
                        expected.add(path == missing || Files.isDirectory(path) ? null : sdk.identify(path));
                    }
                    retained.clear();
                    BatchSummary summary = sdk.identifyAll(paths.iterator(), item -> {
                        int index = retained.size();
                        assertEquals(index, item.getInputIndex());
                        assertSame(paths.get(index), item.getPath());
                        assertEquals(expected.get(index) != null, item.isSuccess());
                        assertEquals(item.isSuccess(), item.getResult().isPresent());
                        assertEquals(!item.isSuccess(), item.getError().isPresent());
                        if (item.isSuccess()) {
                            Fixtures.assertBatchEquivalent(mode + " batchSize=" + batchSize + " #" + index,
                                    expected.get(index), item.getResult().get());
                        } else {
                            assertEquals(MagikaException.Category.INPUT, item.getError().get().getCategory());
                        }
                        retained.add(item);
                    });
                    assertEquals(paths.size(), summary.getDeliveredCount());
                    assertEquals(paths.size() - 2, summary.getSuccessCount());
                    assertEquals(2, summary.getFailureCount());
                }
                assertEquals("empty", retained.get(0).getResult().get().getLabel());
                assertTrue(retained.get(1).getError().isPresent());
            }
        }
        System.out.println("Batch/single boundary parity: three modes, batch sizes 1/7/32, "
                + paths.size() + " mixed input positions per run");
    }

    @Test
    public void actualFileFailuresContinueAndReleaseHandlesWithoutRetries() throws Exception {
        Path good = file(MagikaTest.PDF);
        Path denied = file(MagikaTest.PDF);
        Path truncatedFile = file(MagikaTest.PDF);
        Path missing = temporary.getRoot().toPath().resolve("missing");
        Path broken = Files.createSymbolicLink(temporary.getRoot().toPath().resolve("broken"), missing);
        Path link = Files.createSymbolicLink(temporary.getRoot().toPath().resolve("link"), good);
        IOException readCause = new IOException("controlled read failure");
        ObservedFile read = new ObservedFile(good, channel -> { throw readCause; });
        ObservedFile closed = new ObservedFile(good, SeekableByteChannel::close);
        ObservedFile truncated = new ObservedFile(truncatedFile, channel -> {
            try (SeekableByteChannel writer = Files.newByteChannel(truncatedFile, StandardOpenOption.WRITE)) {
                writer.truncate(0);
            }
        });
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(denied);
        try (Magika sdk = Magika.create()) {
            Files.setPosixFilePermissions(denied, Collections.emptySet());
            // This must run as an unprivileged user and observe a real denied open.
            assertThrows(AccessDeniedException.class, () -> {
                try (SeekableByteChannel unexpected = Files.newByteChannel(denied)) { fail("Open succeeded " + unexpected); }
            });
            List<Path> paths = Arrays.asList(good, missing, temporary.getRoot().toPath(), denied,
                    read.path, link, closed.path, broken, truncated.path, Paths.get("/dev/null"), good);
            List<BatchItemResult> results = new ArrayList<>();
            BatchSummary summary = sdk.identifyAll(paths.iterator(), results::add);
            assertEquals(11, summary.getDeliveredCount());
            assertEquals(3, summary.getSuccessCount());
            assertEquals(8, summary.getFailureCount());
            for (int i = 0; i < paths.size(); i++) {
                BatchItemResult item = results.get(i);
                assertEquals(i, item.getInputIndex());
                assertSame(paths.get(i), item.getPath());
                if (i == 0 || i == 5 || i == 10) { assertTrue(item.isSuccess()); }
                else {
                    assertFalse(item.getResult().isPresent());
                    MagikaException error = item.getError().get();
                    assertEquals(MagikaException.Category.INPUT, error.getCategory());
                    assertEquals(paths.get(i).toString(), error.getContext());
                    assertNotNull(error.getCause());
                }
            }
            assertTrue(results.get(3).getError().get().getCause() instanceof AccessDeniedException);
            assertSame(readCause, results.get(4).getError().get().getCause());
            assertTrue(results.get(6).getError().get().getCause() instanceof ClosedChannelException);
            assertTrue(results.get(8).getError().get().getCause() instanceof EOFException);
            for (ObservedFile observed : Arrays.asList(read, closed, truncated)) {
                assertEquals(1, observed.opens);
                assertEquals(1, observed.reads);
                assertEquals(1, observed.closes);
            }
            assertEquals("pdf", sdk.identify(good).getLabel());
        } finally { Files.setPosixFilePermissions(denied, permissions); }
    }

    @Test
    public void iteratorFaultsAndNullAbortWithKnownOrUnknownIndexAndDeliveredPrefix() throws Exception {
        Path path = file(MagikaTest.PDF);
        RuntimeException cause = new IllegalStateException("iterator failed");
        try (Magika sdk = Magika.builder().batchSize(2).build()) {
            for (int position : new int[] {0, 1, 2, 3, 4}) {
                for (String fault : Arrays.asList("hasNext", "next", "null")) {
                    CountingPaths paths = new CountingPaths(path, 6) {
                        @Override public boolean hasNext() {
                            if (consumed == position && fault.equals("hasNext")) { throw cause; }
                            return super.hasNext();
                        }
                        @Override public Path next() {
                            if (consumed == position) {
                                if (fault.equals("next")) { throw cause; }
                                if (fault.equals("null")) { consumed++; return null; }
                            }
                            return super.next();
                        }
                    };
                    List<BatchItemResult> delivered = new ArrayList<>();
                    BatchIdentificationException error = assertThrows(BatchIdentificationException.class,
                            () -> sdk.identifyAll(paths, delivered::add));
                    assertAbort(error, Stage.ITERATION, position / 2 * 2,
                            fault.equals("hasNext") ? -1 : position);
                    assertEquals(error.getDeliveredCount(), delivered.size());
                    if (fault.equals("null")) { assertTrue(error.getCause() instanceof IllegalArgumentException); }
                    else { assertSame(cause, error.getCause()); }
                    for (int i = 0; i < delivered.size(); i++) { assertEquals(i, delivered.get(i).getInputIndex()); }
                    assertEquals(position + (fault.equals("null") ? 1 : 0), paths.consumed);
                }
            }
            AssertionError fatal = new AssertionError("iterator Error");
            CountingPaths paths = new CountingPaths(path, 1) {
                @Override public boolean hasNext() { throw fatal; }
            };
            assertSame(fatal, assertThrows(BatchIdentificationException.class,
                    () -> sdk.identifyAll(paths, item -> fail())).getCause());
        }
    }

    @Test
    public void callbackFailureCountsOnlyNormalReturnsAndDoesNotRetrySideEffects() throws Exception {
        Path good = file(MagikaTest.PDF);
        Path missing = good.resolveSibling("missing");
        List<Path> paths = Arrays.asList(good, missing, good, good, good, good);
        try (Magika sdk = Magika.builder().batchSize(2).build()) {
            for (int faultAt : new int[] {0, 1, 2, 5}) {
                for (boolean fatal : new boolean[] {false, true}) {
                    Throwable cause = fatal ? new AssertionError("callback failed") : new IllegalStateException("callback failed");
                    List<Long> sideEffects = new ArrayList<>();
                    BatchIdentificationException error = assertThrows(BatchIdentificationException.class,
                            () -> sdk.identifyAll(paths.iterator(), item -> {
                                sideEffects.add(item.getInputIndex());
                                if (item.getInputIndex() == faultAt) {
                                    if (fatal) { throw (Error) cause; }
                                    throw (RuntimeException) cause;
                                }
                            }));
                    assertAbort(error, Stage.CALLBACK, faultAt, faultAt);
                    assertSame(cause, error.getCause());
                    assertEquals(faultAt + 1, sideEffects.size());
                    for (int i = 0; i <= faultAt; i++) { assertEquals(i, sideEffects.get(i).longValue()); }
                }
            }
            assertEquals("pdf", sdk.identify(good).getLabel());
        }
    }

    @Test
    public void slowCallbacksHoldBackNextBatchOnTheCallingThread() throws Exception {
        CountingPaths paths = new CountingPaths(file(MagikaTest.PDF), 8);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Magika sdk = Magika.builder().batchSize(3).build()) {
            FutureTask<BatchSummary> work = new FutureTask<>(() -> {
                Thread caller = Thread.currentThread();
                return sdk.identifyAll(paths, item -> {
                    assertSame(caller, Thread.currentThread());
                    if (item.getInputIndex() == 0) {
                        entered.countDown();
                        try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                        catch (InterruptedException e) { throw new AssertionError(e); }
                        assertEquals(3, paths.consumed);
                    }
                });
            });
            Thread caller = new Thread(work, "application-batch-caller");
            caller.start();
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertEquals(3, paths.consumed);
                assertFalse(work.isDone());
            } finally { release.countDown(); }
            assertEquals(8, work.get(20, TimeUnit.SECONDS).getDeliveredCount());
            caller.join(1000);
            assertFalse(caller.isAlive());
        }
    }

    @Test
    public void interruptionPreservesFlagAtInputAndDeliveryBoundaries() throws Exception {
        Path good = file(MagikaTest.PDF);
        try (Magika sdk = Magika.builder().batchSize(2).build()) {
            CountingPaths untouched = new CountingPaths(good, 4);
            try {
                Thread.currentThread().interrupt();
                assertAbort(assertThrows(BatchIdentificationException.class,
                        () -> sdk.identifyAll(untouched, item -> fail())), Stage.INTERRUPTED, 0, -1);
                assertTrue(Thread.currentThread().isInterrupted());
                assertEquals(0, untouched.hasNextCalls);
            } finally { Thread.interrupted(); }
            for (String boundary : Arrays.asList("hasNext", "next", "read", "callback", "lastCallback")) {
                ObservedFile observed = new ObservedFile(good, channel -> {
                    if (boundary.equals("read")) { Thread.currentThread().interrupt(); }
                });
                CountingPaths paths = new CountingPaths(observed.path, 3) {
                    @Override public boolean hasNext() {
                        if (boundary.equals("hasNext") && consumed == 2) { Thread.currentThread().interrupt(); }
                        return super.hasNext();
                    }
                    @Override public Path next() {
                        Path path = super.next();
                        if (boundary.equals("next") && consumed == 3) { Thread.currentThread().interrupt(); }
                        return path;
                    }
                };
                List<BatchItemResult> delivered = new ArrayList<>();
                try {
                    BatchIdentificationException error = assertThrows(BatchIdentificationException.class,
                            () -> sdk.identifyAll(paths, item -> {
                                delivered.add(item);
                                if (boundary.equals("callback") || boundary.equals("lastCallback") && item.getInputIndex() == 2) {
                                    Thread.currentThread().interrupt();
                                }
                            }));
                    long count = boundary.equals("read") ? 0 : boundary.equals("callback") ? 1
                            : boundary.equals("lastCallback") ? 3 : 2;
                    long index = boundary.equals("read") ? 0 : boundary.equals("callback") ? 1
                            : boundary.equals("lastCallback") ? -1 : 2;
                    assertAbort(error, Stage.INTERRUPTED, count, index);
                    assertTrue(Thread.currentThread().isInterrupted());
                    assertEquals(count, delivered.size());
                    assertEquals(observed.opens, observed.closes);
                } finally { Thread.interrupted(); }
            }
            assertEquals("pdf", sdk.identify(good).getLabel());
        }
    }

    @Test
    public void systemFailureDuringSamplingAbortsInsteadOfBecomingAnInputItem() throws Exception {
        LinkageError cause = new LinkageError("provider linkage failure");
        ObservedFile observed = new ObservedFile(file(MagikaTest.PDF), channel -> { throw cause; });
        try (Magika sdk = Magika.builder().batchSize(1).build()) {
            List<BatchItemResult> delivered = new ArrayList<>();
            BatchIdentificationException error = assertThrows(BatchIdentificationException.class,
                    () -> sdk.identifyAll(Arrays.asList(file(new byte[0]), observed.path, observed.path).iterator(), delivered::add));
            assertAbort(error, Stage.IDENTIFICATION, 1, 1);
            assertSame(cause, error.getCause());
            assertEquals(1, delivered.size());
            assertEquals(1, observed.opens);
            assertEquals(1, observed.closes);
        }
    }

    private Path file(byte[] content) throws IOException {
        return Files.write(temporary.newFile().toPath(), content);
    }

    private static void assertConsumption(Magika sdk, Path path, int count, int batchSize) {
        CountingPaths paths = new CountingPaths(path, count);
        long[] delivered = {0};
        Thread caller = Thread.currentThread();
        BatchSummary summary = sdk.identifyAll(paths, item -> {
            assertSame(caller, Thread.currentThread());
            assertEquals(delivered[0], item.getInputIndex());
            assertSame(path, item.getPath());
            assertEquals(Math.min(count, (delivered[0] / batchSize + 1) * batchSize), paths.consumed);
            assertTrue(item.isSuccess());
            assertEquals("pdf", item.getResult().get().getLabel());
            delivered[0]++;
        });
        assertEquals(count, paths.consumed);
        assertEquals(count, delivered[0]);
        assertEquals(count, summary.getDeliveredCount());
        assertEquals(count, summary.getSuccessCount());
        assertEquals(0, summary.getFailureCount());
    }

    private static void assertAbort(BatchIdentificationException error, Stage stage, long delivered, long index) {
        assertEquals(MagikaException.Category.BATCH, error.getCategory());
        assertEquals(stage, error.getStage());
        assertEquals(delivered, error.getDeliveredCount());
        assertEquals(index >= 0, error.getInputIndex().isPresent());
        if (index >= 0) { assertEquals(index, error.getInputIndex().getAsLong()); }
        assertNotNull(error.getCause());
    }

    private static class CountingPaths implements Iterator<Path> {
        final Path path;
        final int count;
        int consumed;
        int hasNextCalls;

        CountingPaths(Path path, int count) { this.path = path; this.count = count; }
        @Override public boolean hasNext() { hasNextCalls++; return consumed < count; }
        @Override public Path next() {
            if (consumed == count) { throw new NoSuchElementException(); }
            consumed++;
            return path;
        }
    }
}
