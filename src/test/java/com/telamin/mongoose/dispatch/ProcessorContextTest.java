/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dispatch;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ProcessorContext ensuring correct ThreadLocal semantics.
 */
public class ProcessorContextTest {

    @Test
    void currentProcessor_isNullByDefault_andSetRemoveWorks() {
        try {
            assertNull(ProcessorContext.currentProcessor(), "Expected null before setting any processor");

            DummyProcessor p = new DummyProcessor();
            ProcessorContext.setCurrentProcessor(p);
            assertSame(p, ProcessorContext.currentProcessor(), "Should return the processor set in this thread");
        } finally {
            ProcessorContext.removeCurrentProcessor();
            assertNull(ProcessorContext.currentProcessor(), "Expected null after removal");
        }
    }

    @Test
    void processorContext_isThreadLocal_isolatedAcrossThreads() throws Exception {
        DummyProcessor mainProcessor = new DummyProcessor();
        ProcessorContext.setCurrentProcessor(mainProcessor);
        assertSame(mainProcessor, ProcessorContext.currentProcessor(), "main thread should see its own processor");

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        final DataFlow[] seenInWorker = new DataFlow[1];

        Thread t = new Thread(() -> {
            try {
                // At start, other thread should not see main thread's processor
                seenInWorker[0] = ProcessorContext.currentProcessor();
                started.countDown();

                // Now set a different processor in worker thread and verify isolation
                DummyProcessor workerProcessor = new DummyProcessor();
                ProcessorContext.setCurrentProcessor(workerProcessor);
                assertSame(workerProcessor, ProcessorContext.currentProcessor(), "worker thread should see its own processor");
            } finally {
                ProcessorContext.removeCurrentProcessor();
                done.countDown();
            }
        }, "processor-context-worker");
        t.start();

        // Wait for worker to read current context
        assertTrue(started.await(5, TimeUnit.SECONDS), "worker should start");
        assertNull(seenInWorker[0], "worker should not see main thread's processor (ThreadLocal isolation)");

        // Ensure worker finishes cleanup
        assertTrue(done.await(5, TimeUnit.SECONDS), "worker should finish");

        // Main thread still has its processor
        assertSame(mainProcessor, ProcessorContext.currentProcessor(), "main thread's processor should be unchanged by worker");

        ProcessorContext.removeCurrentProcessor();
    }

    /**
     * Minimal DataFlow for testing ProcessorContext.
     */
    private static class DummyProcessor implements DataFlow {
        private final List<EventFeed> feeds = new ArrayList<>();
        @Override public void onEvent(Object event) { }
        @Override public void addEventFeed(EventFeed eventFeed) { feeds.add(eventFeed); }
        @Override public void init() { }
        @Override public void start() { }
        @Override public void tearDown() { }
    }
}
