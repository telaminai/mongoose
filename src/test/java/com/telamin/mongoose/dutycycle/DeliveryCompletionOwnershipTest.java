/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dutycycle;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.event.ReplayRecord;
import com.telamin.fluxtion.runtime.time.ClockStrategy;
import com.telamin.mongoose.dispatch.EventToOnEventInvokeStrategy;
import com.telamin.mongoose.dispatch.PositionedItem;
import com.telamin.mongoose.dispatch.ProcessorContext;
import com.telamin.mongoose.dispatch.RetryPolicy;
import com.telamin.mongoose.replay.GroupRecorder;
import com.telamin.mongoose.replay.InMemoryReplayStore;
import com.telamin.mongoose.replay.ReplayConfig;
import com.telamin.mongoose.replay.ReplayEntry;
import com.telamin.mongoose.service.delivery.DeliveryContext;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Who owns a delivery-complete callback (docs/design/feed-delivery.md 5b), from the hook recheck: F2, a callback
 * registered during a synthetic-clock installation must belong to the processor whose clock it is, so another
 * processor's failure does not discard it; F3, a processor's input is marked Failed once however its callbacks fail.
 */
class DeliveryCompletionOwnershipTest {

    private static final RetryPolicy THREE_ATTEMPTS = new RetryPolicy(3, 0, 0, 1, Set.of(RuntimeException.class));

    @AfterEach
    void clear() {
        ProcessorContext.removeCurrentProcessor();
        DeliveryContext.exit();
    }

    static final class Proc implements DataFlow {
        final Runnable work;

        Proc(Runnable work) {
            this.work = work;
        }

        @Override
        public void onEvent(Object e) {
            work.run();
        }

        @Override
        public void setClockStrategy(ClockStrategy s) {
        }
    }

    /** A new instance each time, so identity de-duplication cannot merge two failures. */
    static Runnable broken() {
        return new Runnable() {
            @Override
            public void run() {
                throw new IllegalStateException("DEMO completion failed");
            }
        };
    }

    private static InMemoryReplayStore record(DataFlow one, DataFlow two, Object input) {
        var store = new InMemoryReplayStore();
        var recorder = new GroupRecorder(ReplayConfig.record(Set.of("one", "two"), Map.of(), null, store), () -> 7L);
        recorder.attach("one", one);
        recorder.attach("two", two);
        var queue = new OneToOneConcurrentArrayQueue<Object>(16);
        var dispatched = new AtomicLong();
        var reader = new EventQueueToEventProcessorAgent(queue, new EventToOnEventInvokeStrategy(), "ownership", "feed")
                .withRetryPolicy(THREE_ATTEMPTS).withDispatchedCounter(dispatched);
        reader.registerProcessor(one);
        reader.registerProcessor(two);
        reader.recordWith(recorder);
        queue.offer(new PositionedItem(1, 100L, 7, input));
        reader.doWork();
        assertEquals(1, dispatched.get());
        return store;
    }

    /** Register {@code callback} with no current processor, as code outside a strategy's dispatch would. */
    private static void unowned(Runnable callback) {
        DataFlow owner = ProcessorContext.currentProcessor();
        ProcessorContext.removeCurrentProcessor();
        try {
            DeliveryContext.onComplete(callback);
        } finally {
            ProcessorContext.setCurrentProcessor(owner);
        }
    }

    // ---- F3 (the reviewer's IndependentCompletionOwnershipTest) ----------------------------------------------------

    @Test
    void failedOwnerGetsOneMarkAndSuccessfulPeerKeepsItsInput() {
        var store = record(new Proc(() -> {
            DeliveryContext.onComplete(broken());
            DeliveryContext.onComplete(broken());
        }), new Proc(() -> { }), "input");
        assertEquals(1, store.entries("one").size(), store.entries("one").toString());
        assertInstanceOf(ReplayEntry.Failed.class, store.entries("one").get(0));
        assertEquals(1, store.entries("two").size());
        assertInstanceOf(ReplayEntry.Inline.class, store.entries("two").get(0));
    }

    @Test
    void unknownOwnerMarksBothTargetsOnce() {
        var store = record(new Proc(() -> unowned(broken())), new Proc(() -> { }), "input");
        for (String name : List.of("one", "two")) {
            assertEquals(1, store.entries(name).size(), store.entries(name).toString());
            assertInstanceOf(ReplayEntry.Failed.class, store.entries(name).get(0));
        }
    }

    @Test
    void knownThenUnknownFailureStillMarksEachOwnerOnlyOnce() {
        var store = record(new Proc(() -> {
            DeliveryContext.onComplete(broken());
            unowned(broken());
        }), new Proc(() -> { }), "input");
        assertEquals(1, store.entries("one").size(), "one input should not get duplicate Failed markers: "
                + store.entries("one"));
        assertEquals(1, store.entries("two").size(), store.entries("two").toString());
    }

    @Test
    void unknownThenKnownFailureStillMarksEachOwnerOnlyOnce() {
        var store = record(new Proc(() -> {
            unowned(broken());
            DeliveryContext.onComplete(broken());
        }), new Proc(() -> { }), "input");
        assertEquals(1, store.entries("one").size(), store.entries("one").toString());
        assertEquals(1, store.entries("two").size(), store.entries("two").toString());
    }

    // ---- F2 ----------------------------------------------------------------------------------------------------------

    /**
     * Like a generated processor: installing a clock runs an event cycle, whose {@code @AfterEvent} registers the
     * node's save; the business cycle registers the same instance again.
     */
    static final class ClockCycleProc implements DataFlow {
        final List<String> log;
        final Runnable save;

        ClockCycleProc(List<String> log) {
            this.log = log;
            this.save = () -> log.add("save by " + ProcessorContext.currentProcessor());
        }

        @Override
        public void setClockStrategy(ClockStrategy s) {
            DeliveryContext.onComplete(save);     // the clock-install cycle's @AfterEvent
        }

        @Override
        public void onEvent(Object e) {
            log.add("apply " + e);
            DeliveryContext.onComplete(save);     // the business cycle's @AfterEvent: already pending
        }

        @Override
        public String toString() {
            return "first";
        }
    }

    @Test
    void aSuccessfulPeersCallbackRegisteredDuringClockInstallationSurvivesAnotherProcessorsFailure() {
        List<String> log = new ArrayList<>();
        ClockCycleProc first = new ClockCycleProc(log);
        int[] fails = {1};
        Proc second = new Proc(() -> {
            if (fails[0]-- > 0) throw new IllegalStateException("DEMO second fails once");
        });
        var queue = new OneToOneConcurrentArrayQueue<Object>(16);
        var reader = new EventQueueToEventProcessorAgent(queue, new EventToOnEventInvokeStrategy(), "ownership", "feed")
                .withRetryPolicy(THREE_ATTEMPTS);
        reader.registerProcessor(first);
        reader.registerProcessor(second);
        ReplayRecord record = new ReplayRecord();
        record.setEvent("payment");
        record.setWallClockTime(1_700_000_000_000L);
        queue.offer(new PositionedItem(1, 100L, 7, record));
        reader.doWork();
        assertEquals(List.of("apply payment", "save by first"), log,
                "the first processor handled the item and is not retried: its save must run, owned by it");
    }

    @Test
    void aReRegistrationByAKnownProcessorClaimsAnUnownedPendingCallback() {
        DeliveryContext.enter("feed", 1, 7, 10L);
        Runnable save = () -> { };
        Proc owner = new Proc(() -> { });
        assertTrue(DeliveryContext.onComplete(save));            // no current processor
        ProcessorContext.setCurrentProcessor(owner);
        assertTrue(DeliveryContext.onComplete(save));            // the same instance, now by a known processor
        ProcessorContext.removeCurrentProcessor();
        DeliveryContext.dispatchFailed(new Proc(() -> { }));     // another processor fails
        DeliveryContext.Completion kept = DeliveryContext.takeCompletion();
        assertNotNull(kept, "claimed by its processor, so another processor's failure keeps it");
        assertSame(owner, kept.owner());
    }

    // ---- recheck 2, F2: one Failed mark per processor and input across dispatch and completion ---------------------

    @Test
    void dispatchFailureThenCompletionFailureShouldMarkInputOnce() {
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var store = record(new Proc(() -> {
            if (attempts.getAndIncrement() == 0) throw new IllegalStateException("DEMO dispatch fails once");
            DeliveryContext.onComplete(broken());
        }), new Proc(() -> { }), "input");
        assertEquals(1, store.entries("one").size(), "one input should have one Failed: " + store.entries("one"));
        assertInstanceOf(ReplayEntry.Failed.class, store.entries("one").get(0));
        assertEquals(1, store.entries("two").size());
        assertInstanceOf(ReplayEntry.Inline.class, store.entries("two").get(0));
    }
}
