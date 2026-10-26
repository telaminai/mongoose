/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.dispatch;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.mongoose.config.EventFeedConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.OverloadPolicyConfig;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.exception.PublishRefusedException;
import com.telamin.mongoose.internal.AgronaCountersService;
import com.telamin.mongoose.internal.DefaultMongooseHealth;
import com.telamin.mongoose.service.*;
import com.telamin.mongoose.service.health.HealthStatus;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.StringReader;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests from the third independent review (of 2980845, telaminai/mongoose#79): the reviewer's probes
 * ({@code review79c.Review79cTest}), with each defect assertion inverted to the fixed behaviour; the reviewer's
 * original assertion is quoted where it changed.
 */
@Timeout(120)
class OverloadReview3RegressionTest {

    static OneToOneConcurrentArrayQueue<Object> full(int cap) {
        var q = new OneToOneConcurrentArrayQueue<Object>(cap);
        while (q.offer("filler")) { }
        return q;
    }

    static EventToQueuePublisher<Object> latchable(String feed) {
        var p = new EventToQueuePublisher<Object>(feed);
        p.setOverload(FeedOverloadSettings.of(OverloadSettings.blockWithDeadline(Duration.ofMillis(5))).withKillSwitch(true));
        p.addTargetQueue(full(2), "q");
        return p;
    }

    static long killSwitchThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("mongoose-overload-kill-switch-r79c")).count();
    }

    /** Finding 2. Reviewer on 2980845: 40 live threads, 40 publishers reachable. Fixed: 0 and 0. */
    @Test
    void killSwitchThreadsEndAndPublishersAreCollectable() throws Exception {
        long before = killSwitchThreads();
        List<WeakReference<Object>> refs = new ArrayList<>();
        CountDownLatch fired = new CountDownLatch(40);
        for (int i = 0; i < 40; i++) {
            var p = latchable("r79c-leak-" + i);
            p.setKillSwitch(loss -> fired.countDown());
            assertThrows(PublishRefusedException.class, () -> p.publish("x"));
            refs.add(new WeakReference<>(p));
        }
        assertTrue(fired.await(10, TimeUnit.SECONDS));
        for (int i = 0; i < 5; i++) { System.gc(); Thread.sleep(100); }
        long threads = killSwitchThreads() - before;
        long alive = refs.stream().filter(r -> r.get() != null).count();
        System.out.println("REVIEW79C leak: threads=" + threads + " publishersStillReachable=" + alive
                + " daemon=" + Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("mongoose-overload-kill-switch-r79c")).allMatch(Thread::isDaemon));
        assertEquals(0, threads, "idle hook threads end");
        assertEquals(0, alive, "no hook thread pins its publisher");
    }

    /** Finding 7. Reviewer on 2980845: [A, B, B]. Fixed: a latch is reported once, [A]. */
    @Test
    void replacingTheHookDoesNotRefireALatchAlreadyReported() throws Exception {
        var p = latchable("r79c-refire");
        List<String> fired = new CopyOnWriteArrayList<>();
        p.setKillSwitch(l -> fired.add("A"));
        assertThrows(PublishRefusedException.class, () -> p.publish("x"));
        p.setKillSwitch(l -> fired.add("B"));
        p.setKillSwitch(l -> fired.add("B"));
        Thread.sleep(500);
        System.out.println("REVIEW79C refire: " + fired);
        assertEquals(List.of("A"), fired, "one latch, one hook call");
    }

    /** 3: cost of the registry scan on every publish of a never-drop feed, as live publishers grow. */
    @Test
    void nestingGuardCostScalesWithLivePublishers() {
        List<EventToQueuePublisher<?>> keep = new ArrayList<>();
        for (int n : new int[]{0, 100, 1000, 10000}) {
            while (keep.size() < n) keep.add(new EventToQueuePublisher<>("r79c-pad-" + keep.size()));
            var block = new EventToQueuePublisher<Object>("r79c-block-" + n);
            block.setOverload(FeedOverloadSettings.of(OverloadSettings.blockWithDeadline(Duration.ofMillis(100))));
            var q = new OneToOneConcurrentArrayQueue<Object>(1 << 16);
            block.addTargetQueue(q, "b");
            var drop = new EventToQueuePublisher<Object>("r79c-drop-" + n);
            var q2 = new OneToOneConcurrentArrayQueue<Object>(1 << 16);
            drop.addTargetQueue(q2, "d");
            long bestBlock = Long.MAX_VALUE, bestDrop = Long.MAX_VALUE;
            for (int round = 0; round < 6; round++) {
                long t0 = System.nanoTime();
                for (int i = 0; i < 20000; i++) { block.publish("x"); q.poll(); }
                long t1 = System.nanoTime();
                for (int i = 0; i < 20000; i++) { drop.publish("x"); q2.poll(); }
                long t2 = System.nanoTime();
                bestBlock = Math.min(bestBlock, (t1 - t0) / 20000);
                bestDrop = Math.min(bestDrop, (t2 - t1) / 20000);
            }
            System.out.println("REVIEW79C guard cost: extraPublishers=" + n + " blockFeed=" + bestBlock
                    + " ns/publish dropFeed=" + bestDrop + " ns/publish");
            // finding 1: a never-drop publish that finds room no longer scans the registry: near the drop feed's cost,
            // whatever the number of live publishers (reviewer on 2980845: 3.9 us at +100, 48 us at +1000)
            assertTrue(bestBlock < bestDrop * 4 + 300, "never-drop publish with room " + bestBlock + " ns at +" + n);
        }
        assertFalse(keep.isEmpty());
    }

    /** 3: contention on the JVM-wide registry monitor across threads publishing to unrelated never-drop feeds. */
    @Test
    void nestingGuardContention() throws Exception {
        List<EventToQueuePublisher<?>> keep = new ArrayList<>();
        for (int i = 0; i < 200; i++) keep.add(new EventToQueuePublisher<>("r79c-cpad-" + i));
        for (boolean blocking : new boolean[]{false, true}) {
            int threads = 8;
            ExecutorService ex = Executors.newFixedThreadPool(threads);
            List<Future<Long>> fs = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                var p = new EventToQueuePublisher<Object>("r79c-c-" + blocking + t);
                if (blocking) p.setOverload(FeedOverloadSettings.of(OverloadSettings.blockWithDeadline(Duration.ofMillis(100))));
                var q = new OneToOneConcurrentArrayQueue<Object>(1 << 16);
                p.addTargetQueue(q, "q");
                fs.add(ex.submit(() -> {
                    for (int i = 0; i < 50000; i++) { p.publish("x"); q.poll(); }
                    long t0 = System.nanoTime();
                    for (int i = 0; i < 200000; i++) { p.publish("x"); q.poll(); }
                    return (System.nanoTime() - t0) / 200000;
                }));
            }
            long sum = 0;
            for (var f : fs) sum += f.get();
            ex.shutdown();
            System.out.println("REVIEW79C contention: 8 threads, own feeds, blocking=" + blocking + " mean "
                    + (sum / threads) + " ns/publish");
            assertTrue(sum / threads < 5_000, "no JVM-wide lock on the never-drop path (reviewer: 65 us)");
        }
        assertFalse(keep.isEmpty());
    }

    /** 3: exactness under churn: a cross-publisher nested publish is always downgraded while publishers come and go. */
    @Test
    void nestedPublishIsAlwaysDowngradedUnderRegistryChurn() throws Exception {
        var inner = new EventToQueuePublisher<Object>("r79c-inner");
        // spin bound 0: a downgraded publish drops at once, so the timing measures the guard, not 3000 x the 10 ms
        // default spin (the reviewer's run took 50 s for that reason)
        inner.setOverload(FeedOverloadSettings.of(new OverloadSettings(OverloadPolicy.BLOCK_WITH_DEADLINE, 0,
                TimeUnit.SECONDS.toNanos(5))));
        inner.addTargetQueue(full(2), "full");
        var outer = new EventToQueuePublisher<Object>("r79c-outer");
        var oq = new OneToOneConcurrentArrayQueue<Object>(1 << 16);
        outer.addTargetQueue(oq, "o");
        outer.setDataMapper(o -> { inner.publish(o); return o; });
        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong created = new AtomicLong();
        List<Thread> churn = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            Thread th = new Thread(() -> {
                while (!stop.get()) {
                    new EventToQueuePublisher<>("r79c-churn");
                    if (created.incrementAndGet() % 5000 == 0) System.gc();
                }
            });
            th.start();
            churn.add(th);
        }
        long t0 = System.nanoTime();
        try {
            for (int i = 0; i < 3000; i++) { outer.publish(i); oq.poll(); }
        } finally {
            stop.set(true);
            for (Thread th : churn) th.join();
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("REVIEW79C churn: created=" + created.get() + " downgraded=" + inner.downgradedCount()
                + " refused=" + inner.refusedCount() + " ms=" + ms);
        assertEquals(3000, inner.downgradedCount());
        assertEquals(0, inner.refusedCount());
        assertTrue(ms < 5000, "never waited for the inner deadline");
    }

    /**
     * Findings 4 and 5. Reviewer on 2980845: status {orders={}} after the remove, and a restarted group came back UP on a
     * new path. Fixed: status lists the removed latch, and the latch, keyed by group/feed/callback, is re-applied to the
     * restarted group's queue; the stale path's check is closed.
     */
    @Test
    void overloadStatusListsARemovedLatchAndAGroupRestartKeepsIt() throws Exception {
        var counters = new AgronaCountersService();
        var health = new DefaultMongooseHealth(counters);
        var manager = new EventFlowManager();
        manager.setCountersService(counters);
        manager.setHealthService(health);
        var publisher = manager.registerEventSource("orders", new Source());
        publisher.setOverload(FeedOverloadSettings.of(OverloadSettings.blockWithDeadline(Duration.ofMillis(5))));
        var key = new EventSourceKey<Integer>("orders");
        var risk = new ComposingEventProcessorAgent("risk", manager, null, new DeadWheelScheduler(), new ConcurrentHashMap<>());
        var reader = manager.getMappingAgent(key, CallBackType.ON_EVENT_CALL_BACK, risk);
        DataFlow flow = e -> { };
        reader.registerProcessor(flow);
        int refused = 0;
        for (int i = 0; i < 1030; i++) {
            try { publisher.publish(i); } catch (PublishRefusedException e) { refused++; }
        }
        assertTrue(refused > 0);
        System.out.println("REVIEW79C status while bound: " + manager.overloadStates());
        reader.deregisterProcessor(flow);
        reader.onClose();
        Thread.sleep(1100);
        var verdictRemoved = health.aggregatedVerdict("orders");
        System.out.println("REVIEW79C after remove: status=" + manager.overloadStates() + " health=" + verdictRemoved);
        assertEquals(HealthStatus.Verdict.DEGRADED, verdictRemoved.verdict());
        assertEquals(java.util.Map.of("orders", java.util.Map.of("risk/orders/onEventCallBack [removed]", OverloadState.LATCHED)),
                manager.overloadStates(), "overload.status shows the kept latch");
        // the 'same' group restarted as a new agent object: a new path, so the latch is not re-applied
        var risk2 = new ComposingEventProcessorAgent("risk", manager, null, new DeadWheelScheduler(), new ConcurrentHashMap<>());
        var reader2 = manager.getMappingAgent(key, CallBackType.ON_EVENT_CALL_BACK, risk2);
        reader2.registerProcessor(flow);
        System.out.println("REVIEW79C restarted group: status=" + manager.overloadStates()
                + " stateRecords=" + publisher.getStateAudit().records().stream()
                .flatMap(r -> r.lines().filter(l -> l.contains("from:") || l.contains("to:"))).map(String::trim).toList());
        assertEquals(java.util.Map.of("risk/orders/onEventCallBack", OverloadState.LATCHED), manager.overloadStates().get("orders"),
                "the restarted group's queue is still latched");
        assertTrue(publisher.getStateAudit().records().getLast().contains("to: \"LATCHED\""));
        Thread.sleep(1100);
        assertEquals(HealthStatus.Verdict.DEGRADED, health.aggregatedVerdict("orders").verdict(), "latched");
        assertEquals(1, manager.clearOverloadState("orders"));
        Thread.sleep(1100);
        assertEquals(HealthStatus.Verdict.UP, health.aggregatedVerdict("orders").verdict(), "cleared; no stale check left");
        reader2.deregisterProcessor(flow);
        reader2.onClose();
    }

    static class Source implements EventSource<Integer> {
        public void subscribe(EventSubscriptionKey<Integer> key) { }
        public void unSubscribe(EventSubscriptionKey<Integer> key) { }
        public void setEventToQueuePublisher(EventToQueuePublisher<Integer> publisher) { }
    }

    /** 6: the guide's YAML example loads and means what the key table says. */
    @Test
    void guideYamlExampleLoadsAsDocumented() {
        LoaderOptions options = new LoaderOptions();
        options.setTagInspector(tag -> true);
        String yaml = """
                eventFeeds:
                  - name: venue-session
                    instance: !!com.telamin.mongoose.connector.memory.InMemoryEventSource { }
                    overload:
                      policy: BLOCK_WITH_DEADLINE
                      blockDeadlineMillis: 500
                      killSwitch: true

                  - name: fills
                    instance: !!com.telamin.mongoose.connector.memory.InMemoryEventSource { }
                    overload:
                      policy: BLOCK_WITH_DEADLINE
                      blockDeadlineMillis: 250
                      killSwitch: true
                      subscribers:
                        telemetryGroup:              # processor group (agentName) of the telemetry processors
                          policy: DROP_AFTER_SPIN
                          spinBoundMillis: 0

                  - name: prices
                    instance: !!com.telamin.mongoose.connector.memory.InMemoryEventSource { }
                    overload:
                      policy: DROP_AFTER_SPIN
                      spinBoundMillis: 1
                      quietPeriodMillis: 30000       # DEGRADED clears after 30 s without loss (minimum 1000)
                """;
        MongooseServerConfig cfg = new Yaml(options).loadAs(new StringReader(yaml), MongooseServerConfig.class);
        var vs = cfg.getEventFeeds().get(0).resolveOverload();
        var fills = cfg.getEventFeeds().get(1).resolveOverload();
        var prices = cfg.getEventFeeds().get(2).resolveOverload();
        System.out.println("REVIEW79C guide yaml: " + vs + "\n " + fills + "\n " + prices);
        assertEquals(new OverloadSettings(OverloadPolicy.BLOCK_WITH_DEADLINE, TimeUnit.MILLISECONDS.toNanos(10),
                TimeUnit.MILLISECONDS.toNanos(500)), vs.feedDefault(), "spin default 10 ms");
        assertTrue(vs.killSwitchOnLoss());
        assertEquals(TimeUnit.SECONDS.toNanos(60), vs.quietPeriodNanos(), "quiet default 60000");
        assertEquals(new OverloadSettings(OverloadPolicy.DROP_AFTER_SPIN, 0, 0), fills.forGroup("telemetryGroup"));
        assertEquals(TimeUnit.MILLISECONDS.toNanos(250), fills.forGroup("risk").blockDeadlineNanos());
        assertEquals(TimeUnit.SECONDS.toNanos(30), prices.quietPeriodNanos());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(1), prices.feedDefault().spinBoundNanos());
        assertFalse(prices.killSwitchOnLoss());
        // rules in the key table
        assertThrows(Exception.class, () -> new Yaml(options).loadAs(new StringReader("""
                eventFeeds:
                  - name: p
                    overload: { policy: DROP_AFTER_SPIN, quietPeriodMillis: 999 }
                """), MongooseServerConfig.class).getEventFeeds().get(0).resolveOverload());
        assertThrows(Exception.class, () -> new Yaml(options).loadAs(new StringReader("""
                eventFeeds:
                  - name: p
                    overload: { policy: DROP_AFTER_SPIN, blockDeadlineMillis: 5 }
                """), MongooseServerConfig.class).getEventFeeds().get(0).resolveOverload());
        assertThrows(Exception.class, () -> new Yaml(options).loadAs(new StringReader("""
                eventFeeds:
                  - name: p
                    overload: { policy: BLOCK_WITH_DEADLINE }
                """), MongooseServerConfig.class).getEventFeeds().get(0).resolveOverload());
        assertThrows(Exception.class, () -> new Yaml(options).loadAs(new StringReader("""
                eventFeeds:
                  - name: p
                    overload: { policy: DROP_AFTER_SPIN, spinBoundMillis: -1 }
                """), MongooseServerConfig.class).getEventFeeds().get(0).resolveOverload());
        // programmatic example from the guide
        var prog = EventFeedConfig.builder().name("fills").instance(new InMemoryEventSource<>())
                .overload(OverloadPolicyConfig.blockWithDeadline(Duration.ofMillis(250))
                        .killSwitch(true)
                        .subscriber("telemetryGroup", OverloadPolicyConfig.dropAfterSpin(Duration.ZERO)))
                .build().resolveOverload();
        assertEquals(fills, prog, "programmatic form equals the YAML form");
        // finding 9. Reviewer on 2980845: a 1 ms programmatic quiet period was accepted. Fixed: refused.
        assertThrows(IllegalArgumentException.class, () ->
                FeedOverloadSettings.of(OverloadSettings.dropAfterSpin(Duration.ZERO)).withQuietPeriod(Duration.ofMillis(1)));
    }

    static long blockCost() {
        var block = new EventToQueuePublisher<Object>("r79c-bc");
        block.setOverload(FeedOverloadSettings.of(OverloadSettings.blockWithDeadline(Duration.ofMillis(100))));
        var q = new OneToOneConcurrentArrayQueue<Object>(1 << 16);
        block.addTargetQueue(q, "b");
        long best = Long.MAX_VALUE;
        for (int round = 0; round < 6; round++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < 200; i++) { block.publish("x"); q.poll(); }
            best = Math.min(best, (System.nanoTime() - t0) / 200);
        }
        return best;
    }

    /** Finding 1: a past peak of live publishers no longer taxes later never-drop publishes. */
    @Test
    void registryScanCostSurvivesCollectedPublishers() throws Exception {
        long before = blockCost();
        System.out.println("REVIEW79C registry at start: " + registrySize());
        List<EventToQueuePublisher<?>> many = new ArrayList<>();
        for (int i = 0; i < 50_000; i++) many.add(new EventToQueuePublisher<>("r79c-peak"));
        long during = blockCost();
        many = null;
        for (int i = 0; i < 5; i++) { System.gc(); Thread.sleep(200); }
        long after = blockCost();
        System.out.println("REVIEW79C peak: before=" + before + " ns, with 50k live=" + during + " ns, after GC=" + after + " ns");
        assertTrue(during < before * 4 + 500 && after < before * 4 + 500, "reviewer on 2980845: 27 us after the peak");
    }

    static int registrySize() {
        return EventToQueuePublisher.registeredPublishers();
    }

    public static class Noop extends com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode {
        @Override public void start() { getContext().subscribeToNamedFeed("f0"); }
        @Override protected boolean handleEvent(Object event) { return true; }
    }

    /** 3: how many publishers a small booted server registers (the scan length of every never-drop publish). */
    @Test
    void publishersInASmallServer() throws Exception {
        int before = registrySize();
        var b = MongooseServerConfig.builder()
                .addProcessorGroup(com.telamin.mongoose.config.EventProcessorGroupConfig.builder().agentName("g1")
                        .put("p1", new com.telamin.mongoose.config.EventProcessorConfig(new Noop())).build());
        for (int i = 0; i < 3; i++) {
            InMemoryEventSource<Object> src = new InMemoryEventSource<>();
            src.setName("f" + i);
            b.addEventFeed(EventFeedConfig.builder().instance(src).name("f" + i).broadcast(true)
                    .agent("fa" + i, new org.agrona.concurrent.BusySpinIdleStrategy()).build());
        }
        var server = com.telamin.mongoose.MongooseServer.bootServer(b.build(), rec -> { });
        try {
            Thread.sleep(300);
            System.out.println("REVIEW79C publishers registered by a 3-feed, 1-group server: " + (registrySize() - before)
                    + " (JVM total " + registrySize() + ")");
        } finally {
            server.stop();
        }
        long t = Thread.getAllStackTraces().keySet().stream().filter(th -> th.getName().startsWith("mongoose-overload-kill-switch-f")).count();
        System.out.println("REVIEW79C overload threads after stop: " + t);
        assertEquals(0, t);
    }

    /** Finding 6. Reviewer on 2980845: a second callback binding on a latched, still-bound path wrote REMOVED->LATCHED. */
    @Test
    void secondBindingOnALatchedPathClaimsARemoval() throws Exception {
        var add = EventToQueuePublisher.class.getDeclaredMethod("addTargetQueue", OneToOneConcurrentArrayQueue.class, String.class,
                com.telamin.mongoose.service.counters.MongooseCounter.class, com.telamin.mongoose.control.ControlAuditSink.class, String.class, String.class);
        add.setAccessible(true);
        var ctr = com.telamin.mongoose.internal.NoOpCountersService.INSTANCE.queueDropCounter("x");
        var p = new EventToQueuePublisher<Object>("r79c-two");
        p.setOverload(FeedOverloadSettings.of(OverloadSettings.blockWithDeadline(Duration.ofMillis(5))));
        var q = full(2);
        add.invoke(p, q, "a", ctr, null, "g", "/path");
        assertThrows(PublishRefusedException.class, () -> p.publish("x"));
        add.invoke(p, q, "b", ctr, null, "g", "/path");
        var recs = p.getStateAudit().records().stream()
                .flatMap(r -> r.lines().filter(l -> l.contains("from:") || l.contains("reason:"))).map(String::trim).toList();
        System.out.println("REVIEW79C two bindings: " + recs);
        // finding 6: nothing was removed, so no REMOVED->LATCHED record
        assertTrue(recs.stream().noneMatch(l -> l.contains("REMOVED")), recs.toString());
    }
}
