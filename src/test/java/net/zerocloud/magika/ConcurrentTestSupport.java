/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Bounded, signal-driven coordination through public operations and thread state. */
final class ConcurrentTestSupport {
    static void await(CountDownLatch signal) {
        try {
            assertTrue("Signal timed out", signal.await(20, TimeUnit.SECONDS));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    static void until(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) { fail("Condition timed out"); }
            Thread.yield();
        }
    }

    static void closing(Magika sdk) {
        until(() -> {
            try { sdk.identify(new byte[0]); return false; }
            catch (IllegalStateException expected) { return true; }
        });
    }

    static final class Gate {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);
        void stop() { entered.countDown(); await(released); }
        void stopUninterruptibly() {
            entered.countDown();
            boolean interrupted = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            try {
                for (;;) {
                    try {
                        assertTrue("Signal timed out", released.await(deadline - System.nanoTime(), TimeUnit.NANOSECONDS));
                        return;
                    } catch (InterruptedException ignored) { interrupted = true; }
                }
            } finally { if (interrupted) { Thread.currentThread().interrupt(); } }
        }
        void awaitEntry() { await(entered); }
        void open() { released.countDown(); }
    }

    static final class Work<T> {
        final FutureTask<T> result;
        final Thread thread;

        Work(Callable<T> action) {
            result = new FutureTask<>(action);
            thread = new Thread(result, "magika-test-call");
            // A broken SDK must fail the test deadline instead of hanging the test JVM.
            thread.setDaemon(true);
            thread.start();
        }

        T get() throws Exception { return result.get(20, TimeUnit.SECONDS); }
        void assertPending() { assertFalse("Call completed before its dependency", result.isDone()); }
        void awaitWaiting() {
            until(() -> {
                assertPending();
                return thread.getState() == Thread.State.WAITING;
            });
        }
    }
}
