/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.dispatch;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.time.ClockStrategy;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.dutycycle.EventQueueToEventProcessorAgent;
import com.telamin.mongoose.exception.PublishRefusedException;
import com.telamin.mongoose.internal.AgronaCountersService;
import com.telamin.mongoose.internal.DefaultMongooseHealth;
import com.telamin.mongoose.replay.GroupRecorder;
import com.telamin.mongoose.replay.InMemoryReplayStore;
import com.telamin.mongoose.replay.ReplayConfig;
import com.telamin.mongoose.replay.ReplayEntry;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventSourceKey;
import com.telamin.mongoose.service.health.HealthStatus;
import com.telamin.mongoose.service.pool.PoolAware;
import com.telamin.mongoose.service.pool.impl.PoolTracker;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests from the second independent review (of 1d925ee, telaminai/mongoose#79). The reviewer's probes
 * ({@code review79b.Review79bTest}) are kept; those that asserted a defect assert the fixed behaviour, and the
 * reviewer's original assertion is quoted in each javadoc. The rest cover findings 4, 7 and 8 and the latch-cleared skip.
 */
@Timeout(60)
class OverloadReview2RegressionTest {

    static OneToOneConcurrentArrayQueue<Object> full(int cap) {
        var q = new OneToOneConcurrentArrayQueue<Object>(cap);
        while (q.offer("filler")) { }
        return q;
    }

    static final class Exploding implements PoolAware {
        public PoolTracker<?> getPoolTracker() { throw new IllegalStateException("boom"); }
    }

    static long field(String record, String key) {
        return Long.parseLong(record.replaceAll("(?s).*\\n\\s*" + key + ": (\\d+).*", "$1"));
    }

    /** Reviewer A1 (kept): a publish that throws out of the locked path leaves no nesting state behind. */
    @Test
    void aThrowingPublishLeavesNoNestingStateBehind() throws Exception {
        var p = new EventToQueuePublisher<Object>("throwing");
        p.addTargetQueue(new OneToOneConcurrentArrayQueue<>(8), "q");
        assertThrows(RuntimeException.class, () -> p.publish(new Exploding()));
        p.setDataMapper(o -> { throw new RuntimeException("mapper"); });
        p.publish("x"); // caught internally
        var b = new EventToQueuePublisher<Object>("block");
        b.setOverload(FeedOverloadSettings.of(OverloadSettings.blockWithDeadline(Duration.ofMillis(100))));
        b.addTargetQueue(full(2), "bq");
        PublishRefusedException e = assertThrows(PublishRefusedException.class, () -> b.publish("y"));
        assertEquals(PublishRefusedException.Reason.DEADLINE, e.reason());
        assertEquals(0, b.downgradedCount());
    }

    /**
     * Finding 1. Reviewer on 1d925ee: after remove and re-add the queue was UP, with 1 record. Fixed: the latch is kept
     * for the queue path, health stays LATCHED while the queue is away, and it comes back LATCHED; the history is
     * UP→LATCHED, LATCHED→REMOVED, REMOVED→LATCHED, and only the operator clears it.
     */
    @Test
    void latchSurvivesRemovalAndReaddUntilAnOperatorClears() {
        var p = new EventToQueuePublisher<Object>("orders");
        p.setOverload(FeedOverloadSettings.of(OverloadSettings.blockWithDeadline(Duration.ofMillis(20))));
        p.addTargetQueue(full(2), "risk");
        assertThrows(PublishRefusedException.class, () -> p.publish("x"));
        assertEquals(OverloadState.LATCHED, p.overloadState("risk"));
        p.removeTargetQueueByName("risk");
        assertNull(p.overloadState("risk"));
        assertEquals(OverloadState.LATCHED, p.overloadHealth("risk").state(), "health of the path after removal");
        p.addTargetQueue(new OneToOneConcurrentArrayQueue<>(8), "risk");
        assertEquals(OverloadState.LATCHED, p.overloadState("risk"), "still latched: no operator clear");
        List<String> recs = p.getStateAudit().records();
        assertEquals(3, recs.size(), recs.toString());
        assertTrue(recs.get(1).contains("from: \"LATCHED\"") && recs.get(1).contains("to: \"REMOVED\""), recs.get(1));
        assertTrue(recs.get(2).contains("from: \"REMOVED\"") && recs.get(2).contains("to: \"LATCHED\""), recs.get(2));
        assertEquals(1, p.clearOverloadState());
        assertEquals(OverloadState.UP, p.overloadState("risk"));
        assertFalse(p.latched("risk"));
    }

    /** Finding 1, through the flow manager: the health check is kept while the queue is away, and closed on clear. */
    @Test
    void healthCheckIsKeptWhileALatchedQueueIsAwayAndClosedOnClear() throws Exception {
        var counters = new AgronaCountersService();
        var health = new DefaultMongooseHealth(counters);
        var manager = new EventFlowManager();
        manager.setCountersService(counters);
        manager.setHealthService(health);
        manager.setFeedOverload("feed", FeedOverloadSettings.of(OverloadSettings.blockWithDeadline(Duration.ofMillis(20))));
        var publisher = manager.registerEventSource("feed", new OverloadHealthAndKillSwitchTest.IntSource());
        var group = new ComposingEventProcessorAgent("risk", manager, null, new DeadWheelScheduler(), new ConcurrentHashMap<>());
        var key = new EventSourceKey<Integer>("feed");
        var reader = manager.getMappingAgent(key, CallBackType.ON_EVENT_CALL_BACK, group);
        DataFlow flow = e -> { };
        reader.registerProcessor(flow);
        for (int i = 0; i < 1024; i++) publisher.publish(i);
        assertThrows(PublishRefusedException.class, () -> publisher.publish(-1));
        reader.deregisterProcessor(flow); // unsubscribe: the queue goes away
        reader.onClose();
        Thread.sleep(1_100); // the health service caches a status for 1 s
        assertEquals(HealthStatus.Verdict.DEGRADED, health.aggregatedVerdict("feed").verdict(), "still reported");
        assertTrue(health.aggregatedVerdict("feed").reason().contains("latched until cleared"),
                health.aggregatedVerdict("feed").reason());
        assertEquals(1, manager.clearOverloadState("feed"));
        assertEquals(HealthStatus.Verdict.UNKNOWN, health.aggregatedVerdict("feed").verdict(), "check closed on clear");
    }

    /**
     * Finding 2. Reviewer on 1d925ee: drop, 400 ms quiet (period 100 ms), drop gave 1 record, and the UP record was
     * stamped at the read time. Fixed: 3 records, the UP stamped when the quiet period ended, with the observation time.
     */
    @Test
    void quietRecoveryIsItsOwnRecordStampedWhenTheQuietPeriodEnded() throws Exception {
        var p = new EventToQueuePublisher<Object>("telemetry");
        p.setOverload(FeedOverloadSettings.of(OverloadSettings.dropAfterSpin(Duration.ZERO))
                .withQuietPeriod(Duration.ofMillis(1000)));
        p.addTargetQueue(full(2), "t");
        long lossAt = System.currentTimeMillis();
        p.publish("a"); // drop -> DEGRADED
        Thread.sleep(1400); // quiet period passes; nobody reads
        long secondAt = System.currentTimeMillis();
        p.publish("b"); // second, separate loss episode
        assertEquals(OverloadState.DEGRADED, p.overloadState("t"));
        List<String> recs = p.getStateAudit().records();
        assertEquals(3, recs.size(), recs.toString());
        assertTrue(recs.get(1).contains("to: \"UP\""), recs.get(1));
        long upAt = field(recs.get(1), "epochMillis");
        assertTrue(upAt - lossAt >= 995 && upAt - lossAt < 1200, "UP stamped " + (upAt - lossAt) + " ms after the loss");
        assertTrue(field(recs.get(1), "observedEpochMillis") >= secondAt, "observed when the second loss applied it");
        assertTrue(recs.get(2).contains("from: \"UP\"") && recs.get(2).contains("to: \"DEGRADED\""), recs.get(2));

        var p2 = new EventToQueuePublisher<Object>("telemetry2");
        p2.setOverload(FeedOverloadSettings.of(OverloadSettings.dropAfterSpin(Duration.ZERO))
                .withQuietPeriod(Duration.ofMillis(1000)));
        p2.addTargetQueue(full(2), "t");
        long lossAt2 = System.currentTimeMillis();
        p2.publish("a");
        Thread.sleep(1600);
        assertEquals(OverloadState.UP, p2.overloadState("t"));
        String up = p2.getStateAudit().records().get(1);
        long stamped = field(up, "epochMillis");
        assertTrue(stamped - lossAt2 >= 995 && stamped - lossAt2 < 1200, "stamped " + (stamped - lossAt2) + " ms after");
        assertTrue(field(up, "observedEpochMillis") - lossAt2 >= 1550, up);
    }

    /** Finding 8: losses that continue through the quiet period keep the queue DEGRADED. */
    @Test
    void lossesContinuingThroughTheQuietPeriodKeepTheQueueDegraded() throws Exception {
        var p = new EventToQueuePublisher<Object>("telemetry");
        p.setOverload(FeedOverloadSettings.of(OverloadSettings.dropAfterSpin(Duration.ZERO))
                .withQuietPeriod(Duration.ofMillis(1000)));
        p.addTargetQueue(full(2), "t");
        for (int i = 0; i < 8; i++) {
            p.publish(i);
            Thread.sleep(300);
            assertEquals(OverloadState.DEGRADED, p.overloadState("t"), "loss every 300 ms, quiet period 1000 ms");
        }
        assertEquals(1, p.getStateAudit().records().size(), "one episode");
        Thread.sleep(1300);
        assertEquals(OverloadState.UP, p.overloadState("t"));
    }

    /** Reviewer D (kept): a flood of drops writes one state record and one QueueDrop record. */
    @Test
    void floodWritesOneStateRecord() {
        var p = new EventToQueuePublisher<Object>("flood");
        p.setOverload(FeedOverloadSettings.of(OverloadSettings.dropAfterSpin(Duration.ZERO)));
        p.addTargetQueue(full(2), "f");
        for (int i = 0; i < 100_000; i++) p.publish(i);
        assertEquals(100_000, p.droppedCount());
        assertEquals(1, p.getStateAudit().records().size());
        assertEquals(1, p.getDropAudit().records().size());
    }

    /**
     * Finding 3. Reviewer on 1d925ee: feedB's hook ran only after feedA's blocked hook returned. Fixed: each feed has
     * its own hook thread.
     */
    @Test
    void aBlockedHookDoesNotStarveAnotherFeedsKillSwitch() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<String> fired = new CopyOnWriteArrayList<>();
        var a = latchable("feedA");
        var b = latchable("feedB");
        a.setKillSwitch(loss -> { fired.add(loss.feed()); try { release.await(); } catch (InterruptedException ie) { } });
        b.setKillSwitch(loss -> fired.add(loss.feed()));
        try {
            assertThrows(PublishRefusedException.class, () -> a.publish("x"));
            await(() -> fired.contains("feedA"));
            assertThrows(PublishRefusedException.class, () -> b.publish("x"));
            await(() -> fired.contains("feedB"));
            assertEquals(List.of("feedA", "feedB"), fired, "B ran while A's hook was still blocked");
        } finally {
            release.countDown();
        }
    }

    /** Finding 3: a hook still queued when its latch is cleared is skipped. */
    @Test
    void aQueuedHookWhoseLatchWasClearedIsSkipped() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<String> fired = new CopyOnWriteArrayList<>();
        var p = new EventToQueuePublisher<Object>("feedE");
        p.setOverload(FeedOverloadSettings.of(OverloadSettings.blockWithDeadline(Duration.ofMillis(20))).withKillSwitch(true));
        var q1 = full(2);
        p.addTargetQueue(q1, "q1");
        p.setKillSwitch(loss -> {
            fired.add(loss.queue());
            if (loss.queue().equals("q1")) try { release.await(); } catch (InterruptedException ie) { }
        });
        assertThrows(PublishRefusedException.class, () -> p.publish("x")); // q1 latches; its hook blocks
        await(() -> fired.contains("q1"));
        q1.clear();
        p.addTargetQueue(full(2), "q2");
        assertThrows(PublishRefusedException.class, () -> p.publish("y")); // q2 latches; its hook queues behind q1's
        assertEquals(OverloadState.LATCHED, p.overloadState("q2"));
        p.clearOverloadState();
        release.countDown();
        Thread.sleep(200);
        assertEquals(List.of("q1"), fired, "q2's hook was skipped: its latch was cleared before it ran");
    }

    /** Finding 6. Reviewer on 1d925ee: a hook set after the latch never fired. Fixed: it fires for that latch. */
    @Test
    void aHookSetAfterALatchIsCalledForIt() throws Exception {
        var a = latchable("feedC");
        assertThrows(PublishRefusedException.class, () -> a.publish("x"));
        List<OverloadLoss> fired = new CopyOnWriteArrayList<>();
        a.setKillSwitch(fired::add);
        await(() -> fired.size() == 1);
        assertThrows(PublishRefusedException.class, () -> a.publish("x"));
        Thread.sleep(200);
        assertEquals(OverloadState.LATCHED, a.overloadState("q"));
        assertEquals(1, fired.size(), "once for the latch, not again for the later refusal");
    }

    /**
     * Finding 5. Reviewer on 1d925ee: an agent-thread drop after cancel latched and fired the hook. Fixed: the state is
     * still recorded, but the hook is not run while the server stops.
     */
    @Test
    void aLatchWhileStoppingIsRecordedButDoesNotRunTheKillSwitch() throws Exception {
        var a = latchable("feedD");
        List<OverloadLoss> fired = new CopyOnWriteArrayList<>();
        a.setKillSwitch(fired::add);
        a.cancelBlockedPublishes();
        assertThrows(PublishRefusedException.class, () -> a.publish("x"));
        assertEquals(OverloadState.UP, a.overloadState("q"));
        Thread agent = new Thread(() -> {
            com.telamin.mongoose.control.AgentThreads.enter(this);
            try { a.publish("y"); } finally { com.telamin.mongoose.control.AgentThreads.exit(); }
        });
        agent.start();
        agent.join();
        Thread.sleep(200);
        assertEquals(OverloadState.LATCHED, a.overloadState("q"), "the state is recorded");
        assertEquals(List.of(), fired, "the kill switch is not run while stopping");
    }

    /**
     * Finding 4 (D2 parity, owner decision) and review 3 finding 3: a feed that configured no policy reports health as
     * PR #76 did, from the path's cumulative drop counter: DEGRADED after a loss, no quiet-period recovery, still
     * DEGRADED with the cumulative count after an unsubscribe and resubscribe of the same group, not cleared by
     * overload.clear, and no OverloadState record in the group control audit. The transcript is the reviewer's
     * DefaultParityProbeTest, whose output at dfbfdd9 these values match.
     */
    @Test
    void anUnconfiguredFeedKeepsPr76HealthSemantics() throws Exception {
        assertFalse(FeedOverloadSettings.DEFAULT.explicit());
        var counters = new AgronaCountersService();
        var health = new DefaultMongooseHealth(counters);
        var manager = new EventFlowManager();
        manager.setCountersService(counters);
        manager.setHealthService(health);
        // no setFeedOverload: the unconfigured default
        var publisher = manager.registerEventSource("feed", new OverloadHealthAndKillSwitchTest.IntSource());
        var group = new ComposingEventProcessorAgent("slow", manager, null, new DeadWheelScheduler(), new ConcurrentHashMap<>());
        var key = new EventSourceKey<Integer>("feed");
        var reader = manager.getMappingAgent(key, CallBackType.ON_EVENT_CALL_BACK, group);
        DataFlow flow = e -> { };
        reader.registerProcessor(flow);
        for (int i = 0; i < 1030; i++) publisher.publish(i);
        assertEquals("DEGRADED / 6 item(s) dropped", verdict(health));
        while (reader.doWork() > 0) { }
        assertEquals("DEGRADED / 6 item(s) dropped", verdict(health), "no recovery");
        assertEquals(0, manager.clearOverloadState("feed"), "overload.clear does not reset a feed with no policy");
        assertEquals("DEGRADED / 6 item(s) dropped", verdict(health));
        reader.deregisterProcessor(flow);
        reader.onClose();
        var reader2 = manager.getMappingAgent(key, CallBackType.ON_EVENT_CALL_BACK, group);
        reader2.registerProcessor(flow);
        assertEquals("DEGRADED / 6 item(s) dropped", verdict(health), "the same group resubscribed: still DEGRADED");
        for (int i = 0; i < 1030; i++) publisher.publish(i);
        assertEquals("DEGRADED / 12 item(s) dropped", verdict(health), "cumulative, as at dfbfdd9");
        assertTrue(group.control().audit().records().stream().noneMatch(r -> r.contains("record: OverloadState")),
                "no new records in the group control audit");
        reader2.deregisterProcessor(flow);
        reader2.onClose();
    }

    static String verdict(DefaultMongooseHealth health) throws InterruptedException {
        Thread.sleep(1_100); // the health service caches a status for 1 s
        var s = health.aggregatedVerdict("feed");
        String reason = String.valueOf(s.reason());
        return s.verdict() + " / " + (reason.contains(" item(s) dropped") ? reason.substring(0, reason.indexOf(" by queue")) : reason);
    }

    /** Finding 9: drop-tolerant state changes stay out of the group control audit even on a configured feed. */
    @Test
    void dropTolerantChangesStayOutOfTheGroupControlAudit() throws Exception {
        var manager = new EventFlowManager();
        manager.setFeedOverload("feed", FeedOverloadSettings.of(OverloadSettings.dropAfterSpin(Duration.ZERO))
                .withQuietPeriod(Duration.ofMillis(1000)));
        var publisher = manager.registerEventSource("feed", new OverloadHealthAndKillSwitchTest.IntSource());
        var group = new ComposingEventProcessorAgent("g", manager, null, new DeadWheelScheduler(), new ConcurrentHashMap<>());
        var reader = manager.getMappingAgent(new EventSourceKey<Integer>("feed"), CallBackType.ON_EVENT_CALL_BACK, group);
        DataFlow flow = e -> { };
        reader.registerProcessor(flow);
        try {
            for (int i = 0; i < 1025; i++) publisher.publish(i);
            Thread.sleep(1100);
            assertEquals(OverloadState.UP, publisher.overloadState("g/feed/onEventCallBack"));
            assertEquals(2, publisher.getStateAudit().records().size());
            assertTrue(group.control().audit().records().stream().noneMatch(r -> r.contains("record: OverloadState")));
        } finally {
            reader.deregisterProcessor(flow);
            reader.onClose();
        }
    }

    /**
     * Finding 7: a drop marked after the reader's check but before the next item was offered still lands before that
     * item. The queue's poll adds the mark just before handing out item #2, as the publisher could on another thread.
     */
    @Test
    void aDropMarkedJustBeforeTheNextItemLandsBeforeIt() throws Exception {
        var marks = new DropMarks();
        var queue = new OneToOneConcurrentArrayQueue<Object>(16) {
            boolean marked;

            @Override
            public Object poll() {
                if (!marked && removedCount() == 2 && size() > 0) {
                    marked = true;
                    marks.add(2, 99, "test drop"); // the drop happened before item #2 was offered
                }
                return super.poll();
            }
        };
        var store = new InMemoryReplayStore();
        var proc = new Proc();
        var recorder = new GroupRecorder(ReplayConfig.record(Set.of("p"), Map.of(), null, store), () -> 7L);
        recorder.attach("p", proc);
        var reader = new EventQueueToEventProcessorAgent(queue, new EventToOnEventInvokeStrategy(), "r", "feed")
                .withDropMarks(marks);
        reader.recordWith(recorder);
        reader.registerProcessor(proc);
        for (int i = 0; i < 4; i++) queue.offer("e" + i);
        while (reader.doWork() > 0) { }
        List<ReplayEntry> entries = store.entries("p");
        assertEquals(5, entries.size(), entries.toString());
        assertEquals("e1", ((ReplayEntry.Inline) entries.get(1)).event());
        assertInstanceOf(ReplayEntry.Dropped.class, entries.get(2), "exactly where the item was lost: " + entries);
        assertEquals("e2", ((ReplayEntry.Inline) entries.get(3)).event());
    }

    static EventToQueuePublisher<Object> latchable(String feed) {
        var p = new EventToQueuePublisher<Object>(feed);
        p.setOverload(FeedOverloadSettings.of(OverloadSettings.blockWithDeadline(Duration.ofMillis(20))).withKillSwitch(true));
        p.addTargetQueue(full(2), "q");
        return p;
    }

    static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long end = System.currentTimeMillis() + 3_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }

    static final class Proc implements DataFlow {
        @Override
        public void onEvent(Object event) {
        }

        @Override
        public void setClockStrategy(ClockStrategy clockStrategy) {
        }

        @Override
        public String toString() {
            return "p";
        }
    }
}
