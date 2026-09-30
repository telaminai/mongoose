package com.telamin.mongoose.dutycycle;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Work handed to an agent thread runs at most once, and never after its caller stopped waiting (mongoose#46). */
class AgentHandoffTest {

    @Test
    void workTheCallerStoppedWaitingFor_neverRuns() throws Exception {
        List<Runnable> held = new CopyOnWriteArrayList<>();
        AtomicInteger runs = new AtomicInteger();
        AgentHandoff handoff = AgentHandoff.submit(held::add, runs::incrementAndGet);
        assertFalse(handoff.await(50, TimeUnit.MILLISECONDS), "cancelled, unrun, on timeout");
        held.forEach(Runnable::run);
        assertEquals(0, runs.get(), "and the late agent thread does not run it");
    }

    @Test
    void workTheAgentRan_isAwaited() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        AgentHandoff handoff = AgentHandoff.submit(Runnable::run, runs::incrementAndGet);
        assertTrue(handoff.await(1, TimeUnit.SECONDS));
        assertEquals(1, runs.get());
    }

    @Test
    void onceRunsItsWorkOnce_whoeverRunsItFirst() {
        AtomicInteger runs = new AtomicInteger();
        Runnable once = AgentHandoff.once(runs::incrementAndGet);
        once.run();                                                  // the agent thread
        once.run();                                                  // the caller, seeing the thread stopped
        assertEquals(1, runs.get());
    }

    /** Review of 90f0d9b, finding 5: a caller interrupted before the agent thread claimed the work cancels it. */
    @Test
    void workWhoseCallerWasInterrupted_beforeItStarted_neverRuns() throws Exception {
        List<Runnable> held = new CopyOnWriteArrayList<>();
        AtomicInteger runs = new AtomicInteger();
        AtomicReference<Object> outcome = new AtomicReference<>();
        AtomicBoolean stillInterrupted = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            AgentHandoff handoff = AgentHandoff.submit(held::add, runs::incrementAndGet);
            try {
                outcome.set(handoff.await(10, TimeUnit.SECONDS));
            } catch (Exception e) {
                outcome.set(e);
            }
            stillInterrupted.set(Thread.currentThread().isInterrupted());
        });
        caller.start();
        while (held.isEmpty()) Thread.onSpinWait();
        caller.interrupt();
        caller.join(5_000);
        held.forEach(Runnable::run);                                 // the agent thread gets to it, after the refusal
        assertEquals(0, runs.get(), "work a caller was interrupted out of never runs later");
        assertEquals(Boolean.FALSE, outcome.get(), "the caller is told it was cancelled");
        assertTrue(stillInterrupted.get(), "and the interrupt is kept for the caller's caller");
    }

    /** Finding 5: work already running when its caller is interrupted is waited for, not abandoned half applied. */
    @Test
    void workAlreadyRunning_whenItsCallerIsInterrupted_isWaitedFor() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        AtomicReference<Object> outcome = new AtomicReference<>();
        AtomicBoolean finishedWhenCallerReturned = new AtomicBoolean();
        AtomicBoolean stillInterrupted = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            AgentHandoff handoff = AgentHandoff.submit(r -> new Thread(r, "DEMO-agent").start(), () -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                finished.set(true);
            });
            try {
                outcome.set(handoff.await(10, TimeUnit.SECONDS));
            } catch (Exception e) {
                outcome.set(e);
            }
            finishedWhenCallerReturned.set(finished.get());
            stillInterrupted.set(Thread.currentThread().isInterrupted());
        });
        caller.start();
        started.await();
        caller.interrupt();
        caller.join(200);
        boolean returnedWhileRunning = !caller.isAlive();
        release.countDown();
        caller.join(5_000);
        assertFalse(returnedWhileRunning, "the caller does not return while its work is running");
        assertTrue(finishedWhenCallerReturned.get(), "it returns once the work finished");
        assertEquals(Boolean.TRUE, outcome.get(), "and is told the work ran");
        assertTrue(stillInterrupted.get(), "with its interrupt kept");
    }
}
