/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import net.zerocloud.magika.BatchIdentificationException.Stage;
import net.zerocloud.magika.ConcurrentTestSupport.Gate;
import net.zerocloud.magika.ConcurrentTestSupport.Work;
import static net.zerocloud.magika.ConcurrentTestSupport.*;
import static org.junit.Assert.*;

public class ConcurrencyTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test(timeout = 120000)
    public void sharedInstanceMatchesEverySequentialResultFieldInAllModes() throws Exception {
        List<byte[]> contents = Fixtures.boundaryContents();
        List<Path> paths = new ArrayList<>();
        for (byte[] content : contents) { paths.add(file(content)); }
        for (PredictionMode mode : PredictionMode.values()) {
            try (Magika sdk = Magika.builder().predictionMode(mode).batchSize(7).build()) {
                List<DetectionResult> expected = new ArrayList<>();
                for (byte[] content : contents) { expected.add(sdk.identify(content)); }
                CountDownLatch start = new CountDownLatch(1);
                List<Work<Void>> workers = new ArrayList<>();
                for (int worker = 0; worker < 4; worker++) {
                    final int entry = worker;
                    workers.add(new Work<>(() -> {
                        await(start);
                        for (int repeat = 0; repeat < 2; repeat++) {
                            if (entry == 3) {
                                BatchSummary summary = sdk.identifyAll(paths.iterator(), item -> {
                                    assertTrue(item.isSuccess());
                                    assertSame(paths.get((int) item.getInputIndex()), item.getPath());
                                    Fixtures.assertBatchEquivalent(mode + " concurrent batch",
                                            expected.get((int) item.getInputIndex()), item.getResult().get());
                                });
                                assertEquals(paths.size(), summary.getDeliveredCount());
                                assertEquals(0, summary.getFailureCount());
                            } else {
                                for (int i = 0; i < contents.size(); i++) {
                                    DetectionResult actual = entry == 0 ? sdk.identify(contents.get(i))
                                            : entry == 1 ? sdk.identify(paths.get(i))
                                            : sdk.identify(new ByteArrayInputStream(contents.get(i)));
                                    Fixtures.assertEquivalent(mode + " concurrent entry=" + entry + " input=" + i,
                                            expected.get(i), actual);
                                }
                            }
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (Work<Void> worker : workers) { worker.get(); }
            }
        }
        System.out.println("Concurrent full-result comparisons: " + contents.size() * 4 * 2 * 3
                + "; four entries, three modes; exact singles, batch tolerance 1e-5");
    }

    @Test(timeout = 60000)
    public void closeDrainsBothSinglesRejectsUntouchedInputsAndRestoresOwnerAndWaiterInterrupts() throws Exception {
        Magika sdk = Magika.create();
        Gate streamGate = new Gate();
        Gate pathGate = new Gate();
        ObservedFile file = new ObservedFile(file(MagikaTest.PDF), channel -> pathGate.stop());
        ByteArrayInputStream stream = new ByteArrayInputStream(MagikaTest.PDF) {
            @Override public synchronized int read(byte[] bytes, int offset, int count) {
                streamGate.stop();
                return super.read(bytes, offset, count);
            }
            @Override public void close() { fail("Caller owns the stream"); }
        };
        Work<DetectionResult> streamCall = new Work<>(() -> sdk.identify(stream));
        Work<DetectionResult> pathCall = new Work<>(() -> sdk.identify(file.path));
        try {
            streamGate.awaitEntry();
            pathGate.awaitEntry();
            Work<Boolean> owner = closer(sdk, false);
            closing(sdk);
            owner.awaitWaiting();
            owner.thread.interrupt();
            Work<Boolean> waiter = closer(sdk, false);
            waiter.awaitWaiting();
            waiter.thread.interrupt();
            Work<Boolean> preInterrupted = closer(sdk, true);
            preInterrupted.awaitWaiting();
            assertRejectedWithoutConsumption(sdk, file.path);
            owner.assertPending();
            waiter.assertPending();
            streamGate.open();
            assertEquals("pdf", streamCall.get().getLabel());
            owner.assertPending();
            waiter.assertPending();
            preInterrupted.assertPending();
            assertEquals(0, file.closes);
            pathGate.open();
            assertEquals("pdf", pathCall.get().getLabel());
            assertTrue(owner.get());
            assertTrue(waiter.get());
            assertTrue(preInterrupted.get());
            assertEquals(1, file.closes);
            sdk.close();
            assertRejectedWithoutConsumption(sdk, file.path);
        } finally {
            streamGate.open();
            pathGate.open();
            streamCall.get();
            pathCall.get();
            sdk.close();
        }
    }

    @Test(timeout = 60000)
    public void closeDrainsRemainingIteratorInputsAndTheFinalCallbackWithoutHoldingUserCodeLock() throws Exception {
        Magika sdk = Magika.builder().batchSize(1).build();
        Path pdf = file(MagikaTest.PDF);
        Gate firstCallback = new Gate();
        Gate laterInput = new Gate();
        Gate lastCallback = new Gate();
        AtomicInteger delivered = new AtomicInteger();
        Iterator<Path> iterator = new Iterator<Path>() {
            int index;
            @Override public boolean hasNext() { return index < 3; }
            @Override public Path next() {
                if (index == 1) {
                    laterInput.stop();
                    assertThrows(IllegalStateException.class, sdk::close);
                }
                index++;
                return pdf;
            }
        };
        Work<BatchSummary> batch = new Work<>(() -> sdk.identifyAll(iterator, item -> {
            assertEquals(delivered.get(), item.getInputIndex());
            assertEquals("pdf", item.getResult().get().getLabel());
            if (item.getInputIndex() == 0) {
                // A different thread can enter while the callback is on the stack.
                try { assertEquals("pdf", new Work<>(() -> sdk.identify(MagikaTest.PDF)).get().getLabel()); }
                catch (Exception error) { throw new AssertionError(error); }
                firstCallback.stop();
            }
            if (item.getInputIndex() == 2) { lastCallback.stop(); }
            assertThrows(IllegalStateException.class, sdk::close);
            delivered.incrementAndGet();
        }));
        try {
            firstCallback.awaitEntry();
            Work<Boolean> close = closer(sdk, false);
            closing(sdk);
            close.assertPending();
            firstCallback.open();
            laterInput.awaitEntry();
            assertEquals(1, delivered.get());
            close.assertPending();
            laterInput.open();
            lastCallback.awaitEntry();
            assertEquals(2, delivered.get());
            close.assertPending();
            lastCallback.open();
            assertEquals(3, batch.get().getDeliveredCount());
            assertFalse(close.get());
        } finally {
            firstCallback.open(); laterInput.open(); lastCallback.open();
            batch.get();
            sdk.close();
        }
    }

    @Test(timeout = 60000)
    public void reentrantCloseFromReadsIteratorAndCallbackLeavesInstanceOpenEvenAfterNestedCalls() throws Exception {
        try (Magika sdk = Magika.create()) {
            Runnable reenter = () -> {
                assertEquals("empty", sdk.identify(new byte[0]).getLabel());
                assertThrows(IllegalStateException.class, sdk::close);
                assertEquals("pdf", sdk.identify(MagikaTest.PDF).getLabel());
            };
            ByteArrayInputStream stream = new ByteArrayInputStream(MagikaTest.PDF) {
                @Override public synchronized int read(byte[] bytes, int offset, int count) {
                    reenter.run();
                    return super.read(bytes, offset, count);
                }
            };
            assertEquals("pdf", sdk.identify(stream).getLabel());
            ObservedFile file = new ObservedFile(file(MagikaTest.PDF), channel -> reenter.run());
            assertEquals("pdf", sdk.identify(file.path).getLabel());
            Iterator<Path> paths = new Iterator<Path>() {
                int index;
                @Override public boolean hasNext() { reenter.run(); return index == 0; }
                @Override public Path next() { reenter.run(); index++; return file.path; }
            };
            Magika other = Magika.create();
            try {
                assertEquals(1, sdk.identifyAll(paths, item -> {
                    reenter.run();
                    other.close(); // Reentrancy is scoped to this instance.
                }).getDeliveredCount());
            } finally { other.close(); }
            assertEquals("pdf", sdk.identify(MagikaTest.PDF).getLabel());
        }
    }

    @Test(timeout = 60000)
    public void failuresAndBatchInterruptsLeaveNoAdmissionBehindDuringClose() throws Exception {
        for (String fault : Arrays.asList("stream", "path", "iterator", "callback", "interrupt")) {
            Magika sdk = Magika.builder().batchSize(1).build();
            Gate gate = new Gate();
            IOException readFailure = new IOException("read failed");
            RuntimeException userFailure = new IllegalArgumentException("user failed");
            ObservedFile file = new ObservedFile(file(MagikaTest.PDF), channel -> {
                if (fault.equals("path")) { gate.stop(); throw readFailure; }
            });
            Work<MagikaException> operation = new Work<>(() -> {
                if (fault.equals("stream")) {
                    return assertThrows(MagikaException.class, () -> sdk.identify(new InputStream() {
                        @Override public int read() throws IOException { gate.stop(); throw readFailure; }
                    }));
                }
                if (fault.equals("path")) { return assertThrows(MagikaException.class, () -> sdk.identify(file.path)); }
                Iterator<Path> paths = new Iterator<Path>() {
                    int index;
                    @Override public boolean hasNext() {
                        if (fault.equals("iterator") && index == 1) { gate.stop(); throw userFailure; }
                        return index < 3;
                    }
                    @Override public Path next() { index++; return file.path; }
                };
                BatchIdentificationException error = assertThrows(BatchIdentificationException.class,
                        () -> sdk.identifyAll(paths, item -> {
                            if (item.getInputIndex() == 1) {
                                gate.stop();
                                if (fault.equals("interrupt")) { Thread.currentThread().interrupt(); }
                                else { throw userFailure; }
                            }
                        }));
                assertEquals(fault.equals("iterator") ? Stage.ITERATION
                        : fault.equals("callback") ? Stage.CALLBACK : Stage.INTERRUPTED, error.getStage());
                assertEquals(fault.equals("interrupt") ? 2 : 1, error.getDeliveredCount());
                assertEquals(fault.equals("interrupt"), Thread.currentThread().isInterrupted());
                if (!fault.equals("interrupt")) { assertSame(userFailure, error.getCause()); }
                return error;
            });
            try {
                gate.awaitEntry();
                Work<Boolean> close = closer(sdk, false);
                closing(sdk);
                close.assertPending();
                gate.open();
                MagikaException error = operation.get();
                if (fault.equals("stream") || fault.equals("path")) {
                    assertEquals(MagikaException.Category.INPUT, error.getCategory());
                    assertSame(readFailure, error.getCause());
                }
                assertFalse(close.get());
                assertEquals(file.opens, file.closes);
            } finally { gate.open(); operation.get(); sdk.close(); }
        }
    }

    static Work<Boolean> closer(Magika sdk, boolean interrupted) {
        return new Work<>(() -> {
            if (interrupted) { Thread.currentThread().interrupt(); }
            sdk.close();
            return Thread.currentThread().isInterrupted();
        });
    }

    private void assertRejectedWithoutConsumption(Magika sdk, Path path) {
        assertThrows(IllegalStateException.class, () -> sdk.identify(MagikaTest.PDF));
        assertThrows(IllegalStateException.class, () -> sdk.identify(new InputStream() {
            @Override public int read() { throw new AssertionError("Rejected input read"); }
        }));
        ObservedFile untouched = new ObservedFile(path, channel -> fail("Rejected path read"));
        assertThrows(IllegalStateException.class, () -> sdk.identify(untouched.path));
        assertEquals(0, untouched.opens);
        assertThrows(IllegalStateException.class, () -> sdk.identifyAll(new Iterator<Path>() {
            @Override public boolean hasNext() { throw new AssertionError("Rejected iterator consumed"); }
            @Override public Path next() { throw new AssertionError("Rejected iterator consumed"); }
        }, item -> fail("Rejected callback invoked")));
    }

    private Path file(byte[] content) throws IOException { return Files.write(temporary.newFile().toPath(), content); }
}
