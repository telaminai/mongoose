package com.telamin.mongoose.control;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.ProcessorHandle;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.control.event.ReplyOutcome;
import com.telamin.mongoose.control.event.RunIntent;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Design "Agent threads and replies" (PR 3): agent identity, never blocking an agent, isolated replies, handles. */
@Timeout(60)
class AgentThreadsAndRepliesTest {

    static class Proc implements DataFlow, Lifecycle {
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();
        volatile Thread startThread;

        @Override public void init() { }
        @Override public void addEventFeed(EventFeed f) { }
        @Override public void onEvent(Object e) { }
        @Override public void start() { starts.incrementAndGet(); startThread = Thread.currentThread(); }
        @Override public void stop() { stops.incrementAndGet(); }
        @Override public void tearDown() { }
    }

    private static MongooseServer started() {
        MongooseServer s = new MongooseServer(new MongooseServerConfig());
        s.init();
        s.start();
        return s;
    }

    @Test
    void theVoidAddReturnsOnceTheProcessorHasStarted() {
        MongooseServer s = started();
        try {
            Proc first = new Proc();
            s.addEventProcessor("first", "g", null, () -> first);   // creates and starts the group
            Proc second = new Proc();
            s.addEventProcessor("second", "g", null, () -> second);
            assertEquals(1, second.starts.get(), "the void add waited for the processor's start, off any agent thread");
        } finally {
            s.stop();
        }
    }

    @Test
    void aReplyStageNeverRunsItsDependentsOnAnAgentThread() throws Exception {
        MongooseServer s = started();
        try {
            Proc seed = new Proc();
            s.addEventProcessor("seed", "g", null, () -> seed);
            AtomicReference<Thread> ranOn = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            CountDownLatch attached = new CountDownLatch(1);
            Proc p = new Proc() {
                @Override public void start() {
                    super.start();
                    try {
                        attached.await(5, TimeUnit.SECONDS);    // the reply cannot complete before its dependent is attached
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
            s.addProcessor("p", "g", null, () -> p).thenAccept(reply -> {
                ranOn.set(Thread.currentThread());
                done.countDown();
            });
            attached.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertNotNull(p.startThread);
            assertFalse(ranOn.get() == p.startThread, "a dependent ran on the group's agent thread");
            assertEquals("mongoose-control-replies", ranOn.get().getName());
        } finally {
            s.stop();
        }
    }

    @Test
    void aStaleHandleNeverStopsAReplacement() throws Exception {
        MongooseServer s = started();
        try {
            Proc old = new Proc();
            s.addEventProcessor("p", "g", null, () -> old);
            ProcessorHandle oldHandle = s.processorHandle("g", "p");
            ControlReply stopped = s.stopProcessor(oldHandle).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(ReplyOutcome.APPLIED, stopped.outcome());
            Proc replacement = new Proc();
            s.addEventProcessor("p", "g", null, () -> replacement);
            ControlReply stale = s.stopProcessor(oldHandle).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(ReplyOutcome.APPLIED, stale.outcome(), "a stale handle is answered, as already stopped");
            assertEquals(0, replacement.stops.get(), "and never reaches the replacement");
            assertEquals(1, old.stops.get());
        } finally {
            s.stop();
        }
    }

    @Test
    void addProcessorReportsADuplicateAsRefusedWithoutThrowing() throws Exception {
        MongooseServer s = started();
        try {
            s.addEventProcessor("p", "g", null, Proc::new);
            ControlReply dup = s.addProcessor("p", "g", null, Proc::new).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(ReplyOutcome.REFUSED, dup.outcome());
        } finally {
            s.stop();
        }
    }

    @Test
    void anAgentThreadIsRefusedAtOnceByAFullMailboxInsteadOfBackingOff() throws Exception {
        ComposingEventProcessorAgent full = new ComposingEventProcessorAgent("full", new EventFlowManager(), null,
                new DeadWheelScheduler(), new ConcurrentHashMap<>());       // never started: nothing drains it
        for (int i = 0; i < full.control().capacity(); i++) {
            full.control().send(new ControlRequest(ControlRequest.Kind.RUN, "fill", RunIntent::new, (Runnable) () -> { }));
        }
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<Long> tookMs = new AtomicReference<>();
        Thread agentLike = new Thread(() -> {
            AgentThreads.enter(new Object());
            long t0 = System.nanoTime();
            try {
                full.control().send(new ControlRequest(ControlRequest.Kind.RUN, "late", RunIntent::new, (Runnable) () -> { }));
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                tookMs.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
                AgentThreads.exit();
            }
        });
        agentLike.start();
        agentLike.join(10_000);
        assertTrue(thrown.get() instanceof IllegalStateException, "refused: " + thrown.get());
        assertTrue(tookMs.get() < 1_000, "an agent thread never backs off on a full mailbox: " + tookMs.get() + " ms");
        full.onClose();
    }

    @Test
    void anAgentThreadIsRecognisedThroughItsGroupsClose() throws Exception {
        MongooseServer s = started();
        AtomicReference<Boolean> agentInStop = new AtomicReference<>();
        s.addEventProcessor("p", "g", null, () -> new Proc() {
            @Override public void stop() {
                super.stop();
                agentInStop.set(AgentThreads.isAgentThread());
            }
        });
        s.stop();
        assertEquals(Boolean.TRUE, agentInStop.get(), "a processor's stop during its group's close runs as agent code");
    }

    @Test
    void anAdminStopRepliesWithinASecondWithTheOutcome() {
        MongooseServer s = started();
        try {
            Proc p = new Proc();
            s.addEventProcessor("p", "g", null, () -> p);
            long t0 = System.nanoTime();
            ControlReply reply = s.stopProcessor("g", "p", 1_000);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 2_000);
            assertNotNull(reply, "answered within the admin wait");
            assertEquals(ReplyOutcome.APPLIED, reply.outcome());
            assertEquals(1, p.stops.get());
        } finally {
            s.stop();
        }
    }
}
