/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.scheduler;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicInteger;

class DeadWheelSchedulerTest {

    /**
     * Regression: {@link DeadWheelScheduler#doWork()} must reuse a single
     * {@link org.agrona.DeadlineTimerWheel.TimerHandler} instance across calls.
     * <p>
     * Originally implemented as {@code timerWheel.poll(milliTime(), this::onTimerExpiry, 100)},
     * which allocated a fresh capturing lambda per invocation. Co-hosting the scheduler with
     * a worker-services agent under {@code BackoffIdleStrategy} produced ~10^6 throwaway
     * allocations/sec and an eventual {@link OutOfMemoryError} inside
     * {@code Unsafe.allocateInstance} on the agent thread.
     * <p>
     * This test calls {@code doWork()} in a tight loop on a single thread with an empty
     * timer wheel and asserts that thread-local allocation stays far below what the
     * per-call lambda pattern would produce.
     */
    @Test
    void doWork_does_not_allocate_per_call_when_timer_wheel_is_empty() {
        DeadWheelScheduler scheduler = new DeadWheelScheduler();

        ThreadMXBean threadMx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        Assertions.assertTrue(threadMx.isThreadAllocatedMemorySupported(),
                "JVM must support thread allocated memory measurement for this test");
        threadMx.setThreadAllocatedMemoryEnabled(true);

        long threadId = Thread.currentThread().threadId();

        // Warm-up: trigger JIT compilation paths so we measure steady-state.
        for (int i = 0; i < 20_000; i++) {
            scheduler.doWork();
        }

        final int iterations = 200_000;
        long before = threadMx.getThreadAllocatedBytes(threadId);
        for (int i = 0; i < iterations; i++) {
            scheduler.doWork();
        }
        long after = threadMx.getThreadAllocatedBytes(threadId);
        long bytesAllocated = after - before;

        // With the bug (`this::onTimerExpiry` re-evaluated per call), this loop allocates
        // ~iterations * 16-24 bytes ≈ 3.2-4.8 MB. With the cached field reference,
        // measured allocation should be essentially zero — give a generous 256 KB ceiling
        // to absorb any incidental measurement noise / JIT bookkeeping.
        long bytesPerCall = bytesAllocated / iterations;
        Assertions.assertTrue(
                bytesAllocated < 256_000,
                "doWork() allocated " + bytesAllocated + " bytes across " + iterations
                        + " calls (" + bytesPerCall + " bytes/call). "
                        + "Expect ~0 bytes/call once the TimerHandler is cached as a field.");
    }

    /**
     * Sanity check: with the cached handler in place, scheduled actions still fire correctly.
     */
    @Test
    void scheduled_action_fires_on_expiry() throws InterruptedException {
        DeadWheelScheduler scheduler = new DeadWheelScheduler();
        AtomicInteger fireCount = new AtomicInteger();

        scheduler.scheduleAfterDelay(10, fireCount::incrementAndGet);

        long deadlineNs = System.nanoTime() + 1_000_000_000L;
        while (fireCount.get() == 0 && System.nanoTime() < deadlineNs) {
            scheduler.doWork();
            Thread.sleep(1);
        }

        Assertions.assertEquals(1, fireCount.get(),
                "scheduled action should fire exactly once after its delay");
    }
}
