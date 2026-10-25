/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.control.ControlReply;
import com.telamin.mongoose.control.event.ReplyOutcome;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.internal.ComposingEventProcessorAgentRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regressions found by the stress and chaos campaign (src/test/java/com/telamin/mongoose/stress), each reduced to a
 * deterministic reproduction that runs in the normal build.
 */
@Timeout(60)
class ControlPlaneStressRegressionTest {

    private MongooseServer server;

    @AfterEach
    void stop() {
        if (server != null) server.shutdown();
    }

    /** A processor that counts its lifecycle; its start() can be held until released. */
    static final class Proc implements DataFlow {
        final CountDownLatch startEntered = new CountDownLatch(1);
        final CountDownLatch release;
        final AtomicInteger stops = new AtomicInteger();

        Proc(boolean blockInStart) {
            this.release = new CountDownLatch(blockInStart ? 1 : 0);
        }

        @Override public void init() { }
        @Override public void addEventFeed(EventFeed f) { }
        @Override public void onEvent(Object e) { }
        @Override public void tearDown() { }

        @Override
        public void start() {
            startEntered.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void stop() {
            stops.incrementAndGet();
        }
    }

    @SuppressWarnings("unchecked")
    private static ComposingEventProcessorAgent group(MongooseServer server, String name) throws Exception {
        var field = MongooseServer.class.getDeclaredField("composingEventProcessorAgents");
        field.setAccessible(true);
        return ((Map<String, ComposingEventProcessorAgentRunner>) field.get(server)).get(name).group();
    }

    private static ControlReply await(CompletionStage<ControlReply> stage) throws Exception {
        return stage.toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    /** Boot a server with {@code victim} running in group "g", and that group's mailbox-full wait cut to 100 ms. */
    private ProcessorHandle bootWithVictim(Proc victim) throws Exception {
        server = MongooseServer.bootServer(new MongooseServerConfig(), rec -> { });
        AddedProcessor added = server.addProcessor("victim", "g", null, () -> victim);
        assertEquals(ReplyOutcome.APPLIED, await(added.reply()).outcome());
        group(server, "g").setControlQueueFullTimeout(Duration.ofMillis(100));
        return added.handle();
    }

    /** Hold the group's thread in a start(), then fill its mailbox with stops of a processor that has gone. */
    private Proc holdAndFill() throws Exception {
        AddedProcessor gone = server.addProcessor("gone", "g", null, () -> new Proc(false));
        assertEquals(ReplyOutcome.APPLIED, await(gone.reply()).outcome());
        assertEquals(ReplyOutcome.APPLIED, await(server.stopProcessor(gone.handle())).outcome());
        Proc blocker = new Proc(true);
        server.addProcessor("blocker", "g", null, () -> blocker);
        assertTrue(blocker.startEntered.await(10, TimeUnit.SECONDS), "the blocker holds the group's thread");
        int capacity = group(server, "g").control().capacity();
        for (int i = 0; i < capacity; i++) server.stopProcessor(gone.handle());
        return blocker;
    }

    /**
     * Chaos finding: a stop refused because the group's mailbox was full released the processor's name reservation
     * (the stop's completion callback ran on every answer, REFUSED included). The processor kept running, the next add
     * under its name was admitted and failed in the group with "name already in use", and the running processor could
     * no longer be found by name, so a stop-everything sweep missed it.
     */
    @Test
    void aStopRefusedOnAFullMailbox_keepsTheRunningProcessorsName() throws Exception {
        Proc victim = new Proc(false);
        ProcessorHandle handle = bootWithVictim(victim);
        Proc blocker = holdAndFill();

        ControlReply refused;
        try {
            refused = await(server.stopProcessor(handle));
        } catch (IllegalStateException thrown) {
            throw new AssertionError("stopProcessor(handle) must answer a full mailbox REFUSED, not throw: " + thrown, thrown);
        }
        assertEquals(ReplyOutcome.REFUSED, refused.outcome(), refused.toString());
        blocker.release.countDown();

        assertEquals(0, victim.stops.get(), "the refused stop did not stop it");
        ProcessorHandle current = server.processorHandle("g", "victim");
        assertNotNull(current, "the running processor keeps its name");
        assertSame(handle.generation(), current.generation());
        AddedProcessor again = server.addProcessor("victim", "g", null, () -> new Proc(false));
        assertEquals(ReplyOutcome.REFUSED, await(again.reply()).outcome(), "its name is still in use");
        assertEquals(ReplyOutcome.APPLIED, await(server.stopProcessor(current)).outcome());
        assertEquals(1, victim.stops.get());
    }

    /**
     * As above, by name with a bounded wait. The same chaos run found this form threw the mailbox-full exception too,
     * though it is documented to return the reply or null: it now returns the REFUSED reply.
     */
    @Test
    void aStopByNameRefusedOnAFullMailbox_keepsTheRunningProcessorsName() throws Exception {
        Proc victim = new Proc(false);
        ProcessorHandle handle = bootWithVictim(victim);
        Proc blocker = holdAndFill();
        ControlReply reply;
        try {
            reply = server.stopProcessor("g", "victim", 1_000);
        } catch (IllegalStateException thrown) {
            throw new AssertionError("stopProcessor(group, name, wait) returns the reply or null, it must not throw: "
                    + thrown, thrown);
        }
        assertNotNull(reply, "a refused stop is answered at once");
        assertEquals(ReplyOutcome.REFUSED, reply.outcome(), reply.toString());
        blocker.release.countDown();
        assertEquals(0, victim.stops.get());
        ProcessorHandle current = server.processorHandle("g", "victim");
        assertNotNull(current, "the running processor keeps its name");
        assertSame(handle.generation(), current.generation());
    }

    /**
     * Found while building the shutdown storm: an add waiting on a full control mailbox holds the server's admission read lock for the
     * whole full-mailbox wait (10 s by default), and the shutdown's admission barrier takes the write lock before its
     * deadline starts, so a 500 ms shutdown took as long as the wait. A sender waiting on a full mailbox now gives up
     * as soon as the server is stopping: the add is refused, and the shutdown keeps its deadline.
     */
    @Test
    void aShutdownIsNotHeldPastItsDeadlineByAnAddWaitingOnAFullMailbox() throws Exception {
        MongooseServerConfig config = new MongooseServerConfig();
        config.setShutdownTimeoutMillis(500);
        config.setShutdownDrainTimeoutMillis(50);
        server = MongooseServer.bootServer(config, rec -> { });
        AddedProcessor first = server.addProcessor("first", "g", null, () -> new Proc(false));
        assertEquals(ReplyOutcome.APPLIED, await(first.reply()).outcome());
        Proc blocker = holdAndFill();
        group(server, "g").setControlQueueFullTimeout(Duration.ofSeconds(8));
        java.util.concurrent.atomic.AtomicReference<CompletionStage<ControlReply>> late = new java.util.concurrent.atomic.AtomicReference<>();
        Thread adder = new Thread(() -> late.set(server.addProcessor("late", "g", null, () -> new Proc(false)).reply()),
                "late-adder");
        adder.start();
        Thread.sleep(300);                                  // the add is waiting on the full mailbox
        assertTrue(adder.isAlive(), "precondition: the add waits on the full mailbox");
        try {
            long t0 = System.nanoTime();
            StopResult result = server.shutdown();
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertTrue(ms < 500 + 1_000, "the shutdown kept its 500 ms deadline, took " + ms + " ms: " + result);
            adder.join(10_000);
            ControlReply reply = await(late.get());
            assertTrue(reply.outcome() == ReplyOutcome.REFUSED || reply.outcome() == ReplyOutcome.NOT_RUNNING,
                    "the waiting add was refused: " + reply);
        } finally {
            blocker.release.countDown();
        }
        assertTrue(server.shutdown().complete(), "the resumed stop completes once the blocker returns");
    }
}
