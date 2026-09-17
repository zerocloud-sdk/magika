/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.ByteArrayInputStream;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import net.zerocloud.magika.ConcurrentTestSupport.Gate;
import net.zerocloud.magika.ConcurrentTestSupport.Work;
import static net.zerocloud.magika.ConcurrentTestSupport.*;
import static org.junit.Assert.*;

/** Packaged public SDK + real ORT, with faults only at public native boundaries. */
final class LifecycleOrtProbe {
    static void run() throws Exception {
        concurrentNativeCallsDrainBeforeRelease();
        nativeFailureDrainsAndClosesItsInput();
        for (String fault : Arrays.asList("none", "session", "options", "both", "linkage", "error")) {
            concurrentClosersShareReleaseAndFailure(fault);
        }
        initializationFailuresReleaseOwnedResources();
        automaticRepeatedCloseDoesNotMaskReleaseFailure();
        System.out.println("Native lifecycle: shared Session; separate tensors/results; drain, close order/once, "
                + "interrupts, release failures, initialization cleanup and environment isolation passed");
    }

    private static void concurrentNativeCallsDrainBeforeRelease() throws Exception {
        Path pdf = Files.createTempFile("magika-concurrent-", ".pdf");
        Files.write(pdf, MagikaTest.PDF);
        Magika sdk = Magika.builder().batchSize(2).build();
        DetectionResult expected = sdk.identify(MagikaTest.PDF);
        ModelInfo info = sdk.getModelInfo();
        assertTrue(OrtProbeAgent.installed);
        OrtProbeAgent.reset();
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch returned = new CountDownLatch(4);
        CountDownLatch run = new CountDownLatch(1);
        CountDownLatch copy = new CountDownLatch(1);
        OrtProbeAgent.runHook = (session, input) -> { entered.countDown(); await(run); };
        OrtProbeAgent.resultHook = result -> { returned.countDown(); await(copy); };
        List<Work<DetectionResult>> calls = new ArrayList<>();
        calls.add(new Work<>(() -> sdk.identify(MagikaTest.PDF)));
        calls.add(new Work<>(() -> sdk.identify(pdf)));
        calls.add(new Work<>(() -> sdk.identify(new ByteArrayInputStream(MagikaTest.PDF))));
        calls.add(new Work<>(() -> {
            List<DetectionResult> results = new ArrayList<>();
            sdk.identifyAll(Arrays.asList(pdf, pdf).iterator(), item -> {
                Fixtures.assertBatchEquivalent("native concurrent batch", expected, item.getResult().get());
                results.add(item.getResult().get());
            });
            assertEquals(2, results.size());
            return results.get(0);
        }));
        try {
            await(entered);
            assertEquals(4, OrtProbeAgent.inputs.size());
            assertDistinct(OrtProbeAgent.inputs);
            OrtSession shared = OrtProbeAgent.sessions.get(0);
            for (OrtSession session : OrtProbeAgent.sessions) { assertSame(shared, session); }
            Work<Boolean> close = ConcurrencyTest.closer(sdk, false);
            closing(sdk);
            close.assertPending();
            assertTrue(OrtProbeAgent.closingResources.isEmpty());
            run.countDown();
            await(returned);
            assertEquals(4, OrtProbeAgent.outputs.size());
            assertDistinct(OrtProbeAgent.outputs);
            for (OnnxTensor input : OrtProbeAgent.inputs) { assertFalse(input.isClosed()); }
            for (OrtSession.Result output : OrtProbeAgent.outputs) { assertNotNull(output.get(0)); }
            close.assertPending();
            assertTrue(OrtProbeAgent.closingResources.isEmpty());
            copy.countDown();
            for (int i = 0; i < calls.size(); i++) {
                DetectionResult actual = calls.get(i).get();
                if (i == 3) { Fixtures.assertBatchEquivalent("batch after drain", expected, actual); }
                else { Fixtures.assertEquivalent("single after drain", expected, actual); }
            }
            assertFalse(close.get());
            assertCallResourcesClosed();
            assertReleasedPair();
            assertSame(shared, OrtProbeAgent.closedResources.get(0));
            Fixtures.assertEquivalent("retained result", expected, calls.get(0).get());
            assertSame(info, sdk.getModelInfo());
            assertEquals(3, info.getAssetDigests().size());
            sdk.close();
            assertReleasedPair();
        } finally {
            run.countDown(); copy.countDown();
            for (Work<DetectionResult> call : calls) { call.get(); }
            sdk.close();
            OrtProbeAgent.reset();
            Files.delete(pdf);
        }
    }

    private static void nativeFailureDrainsAndClosesItsInput() throws Exception {
        Magika sdk = Magika.create();
        Gate gate = new Gate();
        try (OnnxTensor wrong = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(),
                IntBuffer.wrap(new int[] {0}), new long[] {1, 1})) {
            OrtProbeAgent.reset();
            OrtProbeAgent.replacement = wrong;
            OrtProbeAgent.failOnRun = 1;
            OrtProbeAgent.runHook = (session, input) -> gate.stop();
            Work<MagikaException> call = new Work<>(() -> assertThrows(MagikaException.class,
                    () -> sdk.identify(MagikaTest.PDF)));
            try {
                gate.awaitEntry();
                Work<Boolean> close = ConcurrencyTest.closer(sdk, false);
                closing(sdk);
                close.assertPending();
                assertTrue(OrtProbeAgent.closingResources.isEmpty());
                gate.open();
                MagikaException error = call.get();
                assertEquals(MagikaException.Category.INFERENCE, error.getCategory());
                assertTrue(error.getCause() instanceof OrtException);
                assertFalse(close.get());
                assertEquals(1, OrtProbeAgent.inputs.size());
                assertTrue(OrtProbeAgent.outputs.isEmpty());
                assertCallResourcesClosed();
                assertReleasedPair();
            } finally { gate.open(); call.get(); }
        } finally { sdk.close(); OrtProbeAgent.reset(); }
    }

    private static void concurrentClosersShareReleaseAndFailure(String fault) throws Exception {
        Magika sdk = Magika.create();
        Magika other = Magika.create();
        Gate sessionRelease = new Gate();
        Gate optionsRelease = new Gate();
        RuntimeException sessionFailure = new IllegalStateException("session release failed");
        RuntimeException optionsFailure = new IllegalStateException("options release failed");
        Error serious = new AssertionError("release error");
        Error linkage = new UnsatisfiedLinkError("release linkage error");
        OrtProbeAgent.reset();
        OrtProbeAgent.beforeCloseHook = resource -> {
            if (resource instanceof OrtSession) { sessionRelease.stopUninterruptibly(); }
            else if (resource instanceof OrtSession.SessionOptions) { optionsRelease.stopUninterruptibly(); }
            else { fail("SDK closed the shared environment"); }
        };
        // Fail after the real release: tests do not intentionally leak native handles.
        OrtProbeAgent.afterCloseHook = resource -> {
            if (resource instanceof OrtSession) {
                if (fault.equals("session") || fault.equals("both")) { throw sessionFailure; }
                if (fault.equals("error")) { throw serious; }
                if (fault.equals("linkage")) { throw linkage; }
            } else if (fault.equals("options") || fault.equals("both")) { throw optionsFailure; }
        };
        AtomicBoolean ownerInterrupted = new AtomicBoolean();
        AtomicBoolean waiterInterrupted = new AtomicBoolean();
        Work<Throwable> owner = closeOutcome(sdk, ownerInterrupted, true);
        Work<Throwable> waiter = null;
        try {
            sessionRelease.awaitEntry();
            waiter = closeOutcome(sdk, waiterInterrupted, false);
            waiter.awaitWaiting();
            waiter.thread.interrupt();
            assertThrows(IllegalStateException.class, () -> sdk.identify(MagikaTest.PDF));
            assertEquals("pdf", other.identify(MagikaTest.PDF).getLabel());
            owner.assertPending();
            waiter.assertPending();
            sessionRelease.open();
            optionsRelease.awaitEntry();
            assertEquals(1, OrtProbeAgent.closedResources.size());
            owner.assertPending();
            waiter.assertPending();
            optionsRelease.open();
            Throwable ownerError = owner.get();
            Throwable waiterError = waiter.get();
            assertTrue(ownerInterrupted.get());
            assertTrue(waiterInterrupted.get());
            assertReleasedPair();
            if (fault.equals("none")) {
                assertNull(ownerError);
                assertNull(waiterError);
                sdk.close();
            } else {
                Throwable cause = fault.equals("options") ? optionsFailure
                        : fault.equals("error") ? serious : fault.equals("linkage") ? linkage : sessionFailure;
                assertSame(cause, rootCause(ownerError));
                assertSame(cause, rootCause(waiterError));
                if (!fault.equals("error")) {
                    assertEquals(MagikaException.Category.RESOURCE_RELEASE, ((MagikaException) ownerError).getCategory());
                    assertNotSame(ownerError, waiterError);
                }
                if (fault.equals("both")) { assertArrayEquals(new Throwable[] {optionsFailure}, cause.getSuppressed()); }
                assertSame(cause, rootCause(assertThrows(Throwable.class, sdk::close)));
            }
            assertReleasedPair();
            assertThrows(IllegalStateException.class, () -> sdk.identify(new byte[0]));
            assertEquals("pdf", other.identify(MagikaTest.PDF).getLabel());
        } finally {
            sessionRelease.open(); optionsRelease.open();
            owner.get();
            if (waiter != null) { waiter.get(); }
            OrtProbeAgent.reset();
            other.close();
            OrtProbeAgent.reset();
        }
    }

    private static Work<Throwable> closeOutcome(Magika sdk, AtomicBoolean interrupted, boolean preInterrupt) {
        return new Work<>(() -> {
            if (preInterrupt) { Thread.currentThread().interrupt(); }
            try { sdk.close(); return null; }
            catch (Throwable failure) { return failure; }
            finally { interrupted.set(Thread.currentThread().isInterrupted()); }
        });
    }

    private static void automaticRepeatedCloseDoesNotMaskReleaseFailure() {
        for (boolean serious : new boolean[] {false, true}) {
            OrtProbeAgent.reset();
            Throwable cause = serious ? new AssertionError("release error")
                    : new IllegalStateException("release failed");
            try {
                Throwable error = assertThrows(Throwable.class, () -> {
                    try (Magika sdk = Magika.create()) {
                        OrtProbeAgent.afterCloseHook = resource -> {
                            if (resource instanceof OrtSession) {
                                if (cause instanceof Error) { throw (Error) cause; }
                                throw (RuntimeException) cause;
                            }
                        };
                        sdk.close();
                    }
                });
                if (serious) { assertSame(cause, error); }
                else { assertEquals(MagikaException.Category.RESOURCE_RELEASE, ((MagikaException) error).getCategory()); }
                assertSame(cause, rootCause(error));
                assertEquals(1, error.getSuppressed().length);
                assertTrue(error.getSuppressed()[0] instanceof MagikaException);
                assertNotSame(error, error.getSuppressed()[0]);
                assertSame(cause, error.getSuppressed()[0].getCause());
                assertReleasedPair();
            } finally { OrtProbeAgent.reset(); }
        }
    }

    private static void initializationFailuresReleaseOwnedResources() throws Exception {
        for (boolean optionsOnly : new boolean[] {true, false}) {
            for (boolean serious : new boolean[] {false, true}) {
                OrtProbeAgent.reset();
                Throwable cause = serious ? new AssertionError("initialization error")
                        : new IllegalStateException("initialization failed");
                Runnable fail = () -> {
                    if (cause instanceof Error) { throw (Error) cause; }
                    throw (RuntimeException) cause;
                };
                if (optionsOnly) { OrtProbeAgent.configurationHook = fail; }
                else { OrtProbeAgent.initializationHook = fail; }
                RuntimeException release = new IllegalStateException("initialization cleanup failed");
                RuntimeException optionsRelease = new IllegalStateException("second cleanup failed");
                OrtProbeAgent.afterCloseHook = resource -> {
                    throw optionsOnly || resource instanceof OrtSession ? release : optionsRelease;
                };
                try {
                    Throwable error = serious ? assertThrows(Error.class, Magika::create)
                            : assertThrows(MagikaException.class, Magika::create);
                    assertSame(cause, rootCause(error));
                    assertEquals(1, error.getSuppressed().length);
                    assertSame(release, error.getSuppressed()[0]);
                    if (!optionsOnly) { assertArrayEquals(new Throwable[] {optionsRelease}, release.getSuppressed()); }
                    assertEquals(optionsOnly ? 1 : 2, OrtProbeAgent.closedResources.size());
                    assertEquals(OrtProbeAgent.closedResources, OrtProbeAgent.closingResources);
                    if (optionsOnly) { assertTrue(OrtProbeAgent.closedResources.get(0) instanceof OrtSession.SessionOptions); }
                    else { assertReleasedPair(); }
                    OrtProbeAgent.initializationHook = null;
                    OrtProbeAgent.configurationHook = null;
                    for (Object resource : OrtProbeAgent.closedResources) {
                        if (resource instanceof OrtSession) {
                            assertThrows(IllegalStateException.class, ((OrtSession) resource)::getInputInfo);
                        } else {
                            assertThrows(IllegalStateException.class,
                                    () -> ((OrtSession.SessionOptions) resource).setIntraOpNumThreads(1));
                        }
                    }
                } finally { OrtProbeAgent.reset(); }
                try (Magika healthy = Magika.create()) { assertEquals("pdf", healthy.identify(MagikaTest.PDF).getLabel()); }
            }
        }
        OrtProbeAgent.reset();
    }

    private static Throwable rootCause(Throwable error) {
        assertNotNull(error);
        while (error.getCause() != null) { error = error.getCause(); }
        return error;
    }

    private static void assertDistinct(List<?> values) {
        Set<Object> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        identities.addAll(values);
        assertEquals(values.size(), identities.size());
    }

    private static void assertReleasedPair() {
        assertEquals(2, OrtProbeAgent.closingResources.size());
        assertEquals(2, OrtProbeAgent.closedResources.size());
        assertTrue(OrtProbeAgent.closedResources.get(0) instanceof OrtSession);
        assertTrue(OrtProbeAgent.closedResources.get(1) instanceof OrtSession.SessionOptions);
        assertEquals(OrtProbeAgent.closingResources, OrtProbeAgent.closedResources);
    }

    private static void assertCallResourcesClosed() {
        for (OnnxTensor input : OrtProbeAgent.inputs) { assertTrue(input.isClosed()); }
        for (OrtSession.Result output : OrtProbeAgent.outputs) {
            assertThrows(IllegalStateException.class, () -> output.get(0));
        }
    }
}
