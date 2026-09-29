package com.telamin.mongoose.dutycycle;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
}
