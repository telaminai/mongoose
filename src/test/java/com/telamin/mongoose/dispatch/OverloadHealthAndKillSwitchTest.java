/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.dispatch;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.time.ClockStrategy;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.dutycycle.EventQueueToEventProcessor;
import com.telamin.mongoose.exception.PublishRefusedException;
import com.telamin.mongoose.internal.AgronaCountersService;
import com.telamin.mongoose.internal.DefaultMongooseHealth;
import com.telamin.mongoose.replay.GroupRecorder;
import com.telamin.mongoose.replay.InMemoryReplayStore;
import com.telamin.mongoose.replay.ReplayConfig;
import com.telamin.mongoose.replay.ReplayEntry;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventSourceKey;
import com.telamin.mongoose.service.EventSubscriptionKey;
import com.telamin.mongoose.service.health.HealthStatus;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Milestone 3 of telaminai/mongoose#79: the health latch (D4), counters, one log line and audit record per state
 * change, the kill-switch hook (D5), and the Failed marker a drop leaves in a recording.
 */
@Timeout(60)
class OverloadHealthAndKillSwitchTest {

    private static final String RISK = "risk/feed/onEventCallBack";
    private static final String TELEMETRY = "telemetry/feed/onEventCallBack";

    /** A feed with a never-drop group (risk) and a drop-tolerant override (telemetry), on real health and counters. */
    static final class Rig implements AutoCloseable {
        final AgronaCountersService counters = new AgronaCountersService();
        final DefaultMongooseHealth health = new DefaultMongooseHealth(counters);
        final EventFlowManager manager = new EventFlowManager();
        final EventToQueuePublisher<Integer> publisher;
        final ComposingEventProcessorAgent riskGroup;
        final EventQueueToEventProcessor risk;
        final EventQueueToEventProcessor telemetry;
        final DataFlow riskFlow = e -> { };
        final DataFlow telemetryFlow = e -> { };

        Rig(FeedOverloadSettings settings) {
            manager.setCountersService(counters);
            manager.setHealthService(health);
            manager.setFeedOverload("feed", settings);
            publisher = manager.registerEventSource("feed", new IntSource());
            riskGroup = new ComposingEventProcessorAgent("risk", manager, null, new DeadWheelScheduler(), new ConcurrentHashMap<>());
            var telemetryGroup = new ComposingEventProcessorAgent("telemetry", manager, null, new DeadWheelScheduler(), new ConcurrentHashMap<>());
            var key = new EventSourceKey<Integer>("feed");
            risk = manager.getMappingAgent(key, CallBackType.ON_EVENT_CALL_BACK, riskGroup);
            telemetry = manager.getMappingAgent(key, CallBackType.ON_EVENT_CALL_BACK, telemetryGroup);
            risk.registerProcessor(riskFlow);
            telemetry.registerProcessor(telemetryFlow);
        }

        long counter(String group, String suffix) {
            Map<String, Long> values = new HashMap<>();
            counters.forEachCounter((id, label, value) -> values.put(label, value));
            return values.entrySet().stream().filter(e -> e.getKey().contains("/group/" + group + "/")
                    && e.getKey().endsWith(suffix)).findFirst().map(Map.Entry::getValue).orElse(-1L);
        }

        long feedCounter(String label) {
            Map<String, Long> values = new HashMap<>();
            counters.forEachCounter((id, l, value) -> values.put(l, value));
            return values.getOrDefault(label, -1L);
        }

        static void drain(EventQueueToEventProcessor reader) throws Exception {
            while (reader.doWork() > 0) { }
        }

        @Override
        public void close() {
            risk.deregisterProcessor(riskFlow);
            telemetry.deregisterProcessor(telemetryFlow);
            risk.onClose();
            telemetry.onClose();
        }
    }

    private static FeedOverloadSettings riskBlocksTelemetryDrops(Duration quiet) {
        return FeedOverloadSettings.of(OverloadSettings.blockWithDeadline(Duration.ofMillis(50)))
                .withSubscriberGroup("telemetry", OverloadSettings.dropAfterSpin(Duration.ZERO))
                .withQuietPeriod(quiet);
    }

    @Test
    void dropTolerantRecoversAfterQuietPeriodNeverDropLatchesUntilCleared() throws Exception {
        try (Rig rig = new Rig(riskBlocksTelemetryDrops(Duration.ofMillis(300)));
             Captured log = new Captured()) {
            // telemetry full (not drained), risk drained: telemetry drops, DEGRADED
            for (int i = 0; i < 1025; i++) {
                rig.publisher.publish(i);
                Rig.drain(rig.risk);
            }
            assertEquals(OverloadState.DEGRADED, rig.publisher.overloadState(TELEMETRY));
            assertEquals(OverloadState.UP, rig.publisher.overloadState(RISK));
            assertEquals(HealthStatus.Verdict.DEGRADED, rig.health.aggregatedVerdict("feed").verdict());

            // quiet period passes with no new loss: UP again
            Rig.drain(rig.telemetry);
            Thread.sleep(1_100); // past the 300 ms quiet period and the health service's 1 s status cache
            assertEquals(HealthStatus.Verdict.UP, rig.health.aggregatedVerdict("feed").verdict(),
                    rig.health.aggregatedVerdict("feed") + " " + rig.publisher.overload());
            assertEquals(OverloadState.UP, rig.publisher.overloadState(TELEMETRY));

            // risk full (not drained): the next publish waits 50 ms and is refused: LATCHED
            for (int i = 0; i < 1024; i++) {
                rig.publisher.publish(i);
                Rig.drain(rig.telemetry);
            }
            assertThrows(PublishRefusedException.class, () -> rig.publisher.publish(-1));
            assertEquals(OverloadState.LATCHED, rig.publisher.overloadState(RISK));
            Thread.sleep(1_100); // past the quiet period and the 1 s health cache
            assertEquals(OverloadState.LATCHED, rig.publisher.overloadState(RISK), "no quiet-period recovery");
            assertEquals(HealthStatus.Verdict.DEGRADED, rig.health.aggregatedVerdict("feed").verdict());

            // counters
            assertEquals(1, rig.counter("risk", ".refused"));
            assertEquals(1, rig.counter("risk", ".blocked"));
            assertTrue(rig.counter("risk", ".blockedNanos") >= TimeUnit.MILLISECONDS.toNanos(45));
            assertTrue(rig.counter("risk", ".blockedMaxNanos") >= TimeUnit.MILLISECONDS.toNanos(45));
            assertEquals(1, rig.counter("telemetry", ".dropped"));

            // an agent-thread publish that would have waited: downgraded (counter), dropped, still latched
            Thread agent = new Thread(() -> {
                com.telamin.mongoose.control.AgentThreads.enter(this);
                try { rig.publisher.publish(-2); } finally { com.telamin.mongoose.control.AgentThreads.exit(); }
            });
            agent.start();
            agent.join();
            assertEquals(1, rig.feedCounter("feed.feed.downgraded"));
            assertEquals(OverloadState.LATCHED, rig.publisher.overloadState(RISK));

            // operator clear
            assertEquals(1, rig.manager.clearOverloadState("feed"));
            assertEquals(OverloadState.UP, rig.publisher.overloadState(RISK));
            Thread.sleep(1_100); // the health service caches a status for 1 s
            assertEquals(HealthStatus.Verdict.UP, rig.health.aggregatedVerdict("feed").verdict());
            assertEquals(-1, rig.manager.clearOverloadState("nope"));

            // one log line and one audit record per state change, never per event
            List<String> changes = List.of(
                    "queue=" + TELEMETRY + " group=telemetry from=UP to=DEGRADED",
                    "queue=" + TELEMETRY + " group=telemetry from=DEGRADED to=UP",
                    "queue=" + RISK + " group=risk from=UP to=LATCHED",
                    "queue=" + RISK + " group=risk from=LATCHED to=UP");
            List<String> lines = log.messages.stream().filter(m -> m.startsWith("overload state:")).toList();
            assertEquals(4, lines.size(), lines.toString());
            for (int i = 0; i < 4; i++) assertTrue(lines.get(i).contains(changes.get(i)), lines.get(i));
            assertTrue(lines.get(3).contains("cleared by operator"), lines.get(3));
            List<String> audit = rig.publisher.getStateAudit().records();
            assertEquals(4, audit.size(), audit.toString());
            assertTrue(audit.get(2).contains("record: OverloadState") && audit.get(2).contains("LATCHED"), audit.get(2));
            assertEquals(2, rig.riskGroup.control().audit().records().stream()
                    .filter(r -> r.contains("record: OverloadState")).count(), "the risk group's own audit has its two");
        }
    }

    @Test
    void killSwitchFiresOnceWhenANeverDropQueueLatchesOffThePublishingThread() throws Exception {
        try (Rig rig = new Rig(riskBlocksTelemetryDrops(Duration.ofSeconds(60)).withKillSwitch(true))) {
            List<OverloadLoss> losses = new CopyOnWriteArrayList<>();
            List<String> threads = new CopyOnWriteArrayList<>();
            rig.manager.setOverloadKillSwitch(loss -> { losses.add(loss); threads.add(Thread.currentThread().getName()); });
            for (int i = 0; i < 1024; i++) {
                rig.publisher.publish(i);
                Rig.drain(rig.telemetry);
            }
            assertThrows(PublishRefusedException.class, () -> rig.publisher.publish(-1));
            assertThrows(PublishRefusedException.class, () -> rig.publisher.publish(-1));
            await(() -> losses.size() >= 1);
            Thread.sleep(100);
            assertEquals(1, losses.size(), "once per latch, not per refusal");
            OverloadLoss loss = losses.getFirst();
            assertEquals("feed", loss.feed());
            assertEquals(RISK, loss.queue());
            assertEquals("risk", loss.group());
            assertEquals("refused", loss.kind());
            assertEquals("mongoose-overload-kill-switch", threads.getFirst());

            rig.manager.clearOverloadState("feed");
            assertThrows(PublishRefusedException.class, () -> rig.publisher.publish(-1));
            await(() -> losses.size() == 2);

            // a refusal at shutdown is not a loss event
            rig.manager.clearOverloadState("feed");
            rig.manager.cancelBlockedPublishes();
            assertThrows(PublishRefusedException.class, () -> rig.publisher.publish(-1));
            Thread.sleep(100);
            assertEquals(2, losses.size());
            assertEquals(OverloadState.UP, rig.publisher.overloadState(RISK));
        }
    }

    @Test
    void killSwitchIsOffByDefault() throws Exception {
        try (Rig rig = new Rig(riskBlocksTelemetryDrops(Duration.ofSeconds(60)))) {
            List<OverloadLoss> losses = new CopyOnWriteArrayList<>();
            rig.manager.setOverloadKillSwitch(losses::add);
            for (int i = 0; i < 1024; i++) {
                rig.publisher.publish(i);
                Rig.drain(rig.telemetry);
            }
            assertThrows(PublishRefusedException.class, () -> rig.publisher.publish(-1));
            assertEquals(OverloadState.LATCHED, rig.publisher.overloadState(RISK));
            Thread.sleep(150);
            assertEquals(List.of(), losses, "the feed did not turn the kill switch on");
        }
    }

    /**
     * A drop keeps its sequence number; the recording gets a Dropped entry naming it, exactly where it was lost (a
     * replay reports it and carries on: the processor never saw the items). The entry survives a CSV store round trip.
     */
    @Test
    void aDropIsMarkedDroppedInTheRecordingAtThePointOfLoss() throws Exception {
        var store = new InMemoryReplayStore();
        var manager = new EventFlowManager();
        manager.setReplayConfig(ReplayConfig.record(Set.of("p"), Map.of(), null, store));
        manager.setFeedOverload("feed", FeedOverloadSettings.of(OverloadSettings.dropAfterSpin(Duration.ZERO)));
        var publisher = manager.registerEventSource("feed", new IntSource());
        var group = new ComposingEventProcessorAgent("g", manager, null, new DeadWheelScheduler(), new ConcurrentHashMap<>());
        var reader = (com.telamin.mongoose.dutycycle.EventQueueToEventProcessorAgent)
                manager.getMappingAgent(new EventSourceKey<Integer>("feed"), CallBackType.ON_EVENT_CALL_BACK, group);
        Proc p = new Proc();
        var recorder = new GroupRecorder(ReplayConfig.record(Set.of("p"), Map.of(), null, store), () -> 7L);
        recorder.attach("p", p);
        reader.recordWith(recorder);
        reader.registerProcessor(p);
        try {
            for (int i = 1; i <= 1026; i++) publisher.publish(i);   // 1025 and 1026 are dropped
            assertEquals(2, publisher.droppedCount());
            assertEquals(1026, publisher.getSequenceNumber(), "the dropped items keep their sequence numbers");
            while (reader.doWork() > 0) { }
            publisher.publish(1027);
            while (reader.doWork() > 0) { }
            List<ReplayEntry> entries = store.entries("p");
            assertEquals(1026, entries.size(), "1024 delivered, one Dropped marker, 1027");
            for (int i = 0; i < 1024; i++) assertInstanceOf(ReplayEntry.Inline.class, entries.get(i), "entry " + i);
            ReplayEntry.Dropped dropped = assertInstanceOf(ReplayEntry.Dropped.class, entries.get(1024));
            assertEquals("feed", dropped.source());
            assertTrue(dropped.description().contains("2 item(s) of feed dropped") && dropped.description()
                    .contains("sequence 1025..1026"), dropped.description());
            java.nio.file.Path csv = java.nio.file.Files.createTempDirectory("overload79").resolve("store.csv");
            try (var store2 = new com.telamin.mongoose.replay.CsvReplayStore(csv, new com.telamin.mongoose.replay.JavaSerializationCodec())) {
                store2.append("p", dropped);
            }
            try (var store3 = new com.telamin.mongoose.replay.CsvReplayStore(csv, new com.telamin.mongoose.replay.JavaSerializationCodec())) {
                assertEquals(List.of(dropped), store3.entries("p"));
            }
            assertEquals(1027, ((ReplayEntry.Inline) entries.get(1025)).event());
            assertEquals(1025, p.received.size(), "live delivery unchanged: 1024, then 1027");
        } finally {
            reader.deregisterProcessor(p);
            reader.onClose();
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long end = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }

    static final class IntSource implements com.telamin.mongoose.service.EventSource<Integer> {
        public void subscribe(EventSubscriptionKey<Integer> key) { }
        public void unSubscribe(EventSubscriptionKey<Integer> key) { }
        public void setEventToQueuePublisher(EventToQueuePublisher<Integer> publisher) { }
    }

    static final class Proc implements DataFlow {
        final List<Object> received = new CopyOnWriteArrayList<>();

        @Override
        public void onEvent(Object event) {
            received.add(event);
        }

        @Override
        public void setClockStrategy(ClockStrategy clockStrategy) {
        }

        @Override
        public String toString() {
            return "p";
        }
    }

    static final class Captured extends Handler implements AutoCloseable {
        final List<String> messages = new CopyOnWriteArrayList<>();
        final Logger logger = Logger.getLogger(EventToQueuePublisher.class.getName());

        Captured() {
            logger.addHandler(this);
        }

        @Override
        public void publish(LogRecord record) {
            messages.add(record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            logger.removeHandler(this);
        }
    }
}
