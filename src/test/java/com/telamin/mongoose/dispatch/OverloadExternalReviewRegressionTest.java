/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.dispatch;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.time.ClockStrategy;
import com.telamin.mongoose.exception.PublishRefusedException;
import com.telamin.mongoose.replay.*;
import com.telamin.mongoose.replay.GroupRecorder;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests from the external review of d9eec79 on PR #81 (telaminai/mongoose#79): the reviewer's
 * reproductions ({@code review79ext.IndependentOverloadReviewTest}), with the defect assertion of finding 1 inverted to
 * the fixed behaviour (the others already asserted it), plus the interrupt race (finding 3) and a controlled
 * interleaving for the drop-mark overflow (finding 5).
 */
@Timeout(10)
class OverloadExternalReviewRegressionTest {
    static EventToQueuePublisher<Object> publisher(String name, long millis) {
        var p = new EventToQueuePublisher<Object>(name);
        p.setOverload(FeedOverloadSettings.of(new OverloadSettings(OverloadPolicy.BLOCK_WITH_DEADLINE, 0,
                TimeUnit.MILLISECONDS.toNanos(millis))));
        return p;
    }
    static OneToOneConcurrentArrayQueue<Object> full() {
        var q = new OneToOneConcurrentArrayQueue<Object>(2);
        while(q.offer("filler")) {}
        return q;
    }
    static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    @Test void journalReentrantPublishCannotTakeTheOuterPublishsReservedRoom() {
        var p = publisher("journal-reentrant", 30);
        var a = new OneToOneConcurrentArrayQueue<Object>(8);
        var b = new OneToOneConcurrentArrayQueue<Object>(2);
        b.offer("filler");
        p.addTargetQueue(a, "a"); p.addTargetQueue(b, "b");
        p.journal(new InMemoryEventJournal(), new EventCodec() {
            boolean nested;
            public byte[] encode(Object item) {
                if (item.equals("outer") && !nested) { nested = true; p.publish("nested"); }
                return item.toString().getBytes(StandardCharsets.UTF_8);
            }
            public Object decode(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
        });
        p.publish("outer");
        var deliveriesA = new ArrayList<Object>(); var deliveriesB = new ArrayList<Object>();
        Object item;
        while ((item = a.poll()) != null) deliveriesA.add(item instanceof JournalledItem j ? j.item() : item);
        while ((item = b.poll()) != null) deliveriesB.add(item instanceof JournalledItem j ? j.item() : item);
        // reviewer on d9eec79: a=[nested, outer], b=[filler, nested]: the outer publish returned with a partial fan-out.
        // Fixed: the nested publish is deferred until the outer commit's offers are done, so the outer item keeps the
        // room it was checked for; the nested one, published afterwards, finds b full and is downgraded (counted)
        assertEquals(List.of("outer", "nested"), deliveriesA);
        assertEquals(List.of("filler", "outer"), deliveriesB, "all-or-nothing for the outer publish");
        assertEquals(1, p.downgradedCount());
        assertEquals(1, p.droppedCount());
    }
    @Test void replayLossHistorySurvivesProcessorDetach() {
        var store = new InMemoryReplayStore();
        store.append("p", new ReplayEntry.Dropped("feed", "one lost input", 1));
        var replay = new GroupReplayer(ReplayConfig.replay(Set.of("p"), Map.of(), null, store), null, new ReplayScheduler());
        DataFlow flow = new DataFlow() {
            public void onEvent(Object event) {}
            public void setClockStrategy(ClockStrategy clock) {}
        };
        replay.attach("p", flow);
        replay.doWork();
        assertFalse(replay.lossless());
        assertEquals(1, replay.losses("p").size());
        replay.detach(flow);
        assertTrue(replay.complete());
        assertNull(replay.stopped("p"));
        assertFalse(replay.lossless(), "detaching a completed lossy stream must not turn the session lossless");
        assertEquals(1, replay.losses("p").size());
    }
    @Test void shutdownWinsOverRoomMadeAvailableForAnAlreadyWaitingPublisher() throws Exception {
        var p = publisher("cancel-priority", 5000);
        var q = full(); p.addTargetQueue(q, "q");
        var result = new AtomicReference<Object>();
        Thread sender = new Thread(() -> { try { p.publish("late"); result.set("APPLIED"); } catch (Throwable t) { result.set(t); } });
        sender.start();
        try {
            Set<?> waiters = (Set<?>) field(p, "waiters");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (waiters.isEmpty() && System.nanoTime() < deadline) Thread.onSpinWait();
            assertFalse(waiters.isEmpty(), "sender reached wait registration");
            synchronized(field(p, "publishLock")) { p.cancelBlockedPublishes(); q.poll(); }
            sender.join(2000);
            assertFalse(sender.isAlive());
            assertInstanceOf(PublishRefusedException.class, result.get(), "shutdown must cancel the already-waiting publication");
            assertEquals(PublishRefusedException.Reason.SHUTDOWN, ((PublishRefusedException)result.get()).reason());
        } finally { p.cancelBlockedPublishes(); sender.interrupt(); sender.join(2000); }
    }
    @Test void queuedKillSwitchDoesNotStartAfterShutdown() throws Exception {
        var p = publisher("queued-hook", 20);
        p.setOverload(p.overload().withKillSwitch(true));
        CountDownLatch firstStarted = new CountDownLatch(1), releaseFirst = new CountDownLatch(1), secondRan = new CountDownLatch(1);
        var calls = new CopyOnWriteArrayList<String>();
        p.setKillSwitch(loss -> {
            calls.add(loss.queue());
            if (loss.queue().equals("q1")) { firstStarted.countDown(); try { releaseFirst.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
            else secondRan.countDown();
        });
        var q1 = full(); p.addTargetQueue(q1, "q1");
        try {
            assertThrows(PublishRefusedException.class, () -> p.publish("first"));
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            q1.clear(); p.addTargetQueue(full(), "q2");
            assertThrows(PublishRefusedException.class, () -> p.publish("second"));
            p.cancelBlockedPublishes(); releaseFirst.countDown();
            assertFalse(secondRan.await(500, TimeUnit.MILLISECONDS), "queued hook started during shutdown: " + calls);
        } finally { releaseFirst.countDown(); p.cancelBlockedPublishes(); }
    }

    /** Finding 3, the interrupt race: room appearing does not let an interrupted waiting publish through. */
    @Test void interruptWinsOverRoomMadeAvailableForAnAlreadyWaitingPublisher() throws Exception {
        var p = publisher("interrupt-priority", 5000);
        var q = full(); p.addTargetQueue(q, "q");
        var result = new AtomicReference<Object>();
        var interruptedAfter = new java.util.concurrent.atomic.AtomicBoolean();
        Thread sender = new Thread(() -> {
            try { p.publish("late"); result.set("APPLIED"); } catch (Throwable t) { result.set(t); }
            interruptedAfter.set(Thread.currentThread().isInterrupted());
        });
        sender.start();
        try {
            Set<?> waiters = (Set<?>) field(p, "waiters");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (waiters.isEmpty() && System.nanoTime() < deadline) Thread.onSpinWait();
            assertFalse(waiters.isEmpty(), "sender reached wait registration");
            synchronized (field(p, "publishLock")) { sender.interrupt(); q.poll(); }
            sender.join(2000);
            assertFalse(sender.isAlive());
            assertInstanceOf(PublishRefusedException.class, result.get(), "the interrupt cancels the waiting publication");
            assertEquals(PublishRefusedException.Reason.INTERRUPTED, ((PublishRefusedException) result.get()).reason());
            assertTrue(interruptedAfter.get(), "interrupt status kept");
            assertEquals("filler", q.poll());
            assertNull(q.poll(), "the refused item reached no queue");
        } finally { sender.interrupt(); sender.join(2000); }
    }

    /** Finding 3: the refusal is attributed to the last blocker even when that queue has been removed. */
    @Test void shutdownRefusalIsAttributedToARemovedBlocker() throws Exception {
        var p = publisher("removed-blocker", 5000);
        var q = full(); p.addTargetQueue(q, "gone");
        p.addTargetQueue(full(), "other");
        var result = new AtomicReference<Object>();
        Thread sender = new Thread(() -> { try { p.publish("late"); result.set("APPLIED"); } catch (Throwable t) { result.set(t); } });
        sender.start();
        try {
            Set<?> waiters = (Set<?>) field(p, "waiters");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (waiters.isEmpty() && System.nanoTime() < deadline) Thread.onSpinWait();
            synchronized (field(p, "publishLock")) { p.cancelBlockedPublishes(); }
            p.removeTargetQueueByName("gone");
            p.removeTargetQueueByName("other");
            sender.join(2000);
            var refused = assertInstanceOf(PublishRefusedException.class, result.get());
            assertEquals(PublishRefusedException.Reason.SHUTDOWN, refused.reason());
            assertEquals("gone", refused.queue());
            assertEquals(1, p.refusedCount());
        } finally { sender.interrupt(); sender.join(2000); }
    }

    /**
     * Finding 5, controlled interleaving: with the marks full, a drop is counted as overflow only after the reader has
     * drained the last mark and taken the overflow (then zero). With no later drop, the next reader pass must still
     * export it.
     */
    @Test void anOverflowCountedAfterTheLastMarkWasTakenIsStillExported() throws Exception {
        var marks = new DropMarks();
        var queue = new OneToOneConcurrentArrayQueue<Object>(16);
        var store = new InMemoryReplayStore();
        DataFlow proc = new DataFlow() {
            public void onEvent(Object event) {}
            public void setClockStrategy(ClockStrategy clock) {}
            public String toString() { return "p"; }
        };
        var recorder = new GroupRecorder(ReplayConfig.record(Set.of("p"), Map.of(), null, store), () -> 7L);
        recorder.attach("p", proc);
        var reader = new com.telamin.mongoose.dutycycle.EventQueueToEventProcessorAgent(queue,
                new EventToOnEventInvokeStrategy(), "r", "feed").withDropMarks(marks);
        reader.recordWith(recorder);
        reader.registerProcessor(proc);
        for (int i = 0; i < DropMarks.CAPACITY; i++) marks.add(0, i + 1, "full");
        // the producer's overflow path pauses before counting: the reader drains every mark (overflow still 0)
        marks.beforeOverflowCount = () -> {
            try { while (reader.doWork() > 0) { } reader.doWork(); } catch (Exception e) { throw new RuntimeException(e); }
        };
        marks.add(0, DropMarks.CAPACITY + 1, "full");   // counted as overflow after the drain
        marks.beforeOverflowCount = null;
        long before = store.entries("p").size();
        reader.doWork();   // no further drops: the next pass must export the stranded count
        var entries = store.entries("p");
        assertEquals(before + 1, entries.size(), "the overflowed drop is exported");
        var dropped = assertInstanceOf(ReplayEntry.Dropped.class, entries.getLast());
        assertTrue(dropped.description().startsWith("1 item(s)") && dropped.description().contains("position approximate"),
                dropped.description());
    }
}
