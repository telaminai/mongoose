/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.stress;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventSourceKey;
import com.telamin.mongoose.service.EventSubscriptionKey;
import com.telamin.mongoose.service.extension.AbstractEventSourceService;
import org.HdrHistogram.Histogram;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * High-volume delivery through a real server: several sources, several processor groups, several processors per group,
 * every processor subscribed to every source. Each event carries its source and a per-source sequence number, and
 * every processor checks the sequence it receives from each source, so a lost, duplicated or reordered event is seen
 * where it happens, not inferred from a total.
 * <p>
 * Stress only: run with {@code -Dmongoose.stress=true}. Scale: {@code -Dmongoose.stress.eventsPerSource} (default
 * 1,000,000), {@code .sources} (4), {@code .groups} (3), {@code .processorsPerGroup} (2).
 */
@EnabledIfSystemProperty(named = "mongoose.stress", matches = "true")
class HighVolumeEventStressTest {

    static final int SOURCES = Integer.getInteger("mongoose.stress.sources", 4);
    static final int GROUPS = Integer.getInteger("mongoose.stress.groups", 3);
    static final int PER_GROUP = Integer.getInteger("mongoose.stress.processorsPerGroup", 2);
    static final long EVENTS_PER_SOURCE = Long.getLong("mongoose.stress.eventsPerSource", 1_000_000L);
    /** In flight per source, below the 1024-slot reader queue, so a full queue (and its 10 ms drop) never happens. */
    static final int CREDIT_WINDOW = 512;
    static final long DEADLINE_SECONDS = Long.getLong("mongoose.stress.highVolume.deadlineSeconds", 600);

    private MongooseServer server;
    private ExecutorService publishers;

    @BeforeAll
    static void quietLogs() {
        StressSupport.quietLogging();
    }

    @AfterEach
    void tearDown() {
        if (publishers != null) publishers.shutdownNow();
        if (server != null) server.shutdown();
    }

    /** One event: its source, its sequence number in that source (from 0; -1 is a warm-up probe), when it was sent. */
    public record Tick(int source, long seq, long sentNanos) implements java.io.Serializable { }

    /** Something a test publishes into, one thread per source. */
    interface Feed {
        void publish(Tick tick);

        /** Items the feed's publisher dropped on a full reader queue (the 10 ms spin bound). */
        long dropped();
    }

    /** A source published from the caller's thread (no agent). */
    static final class DirectSource extends AbstractEventSourceService<Tick> implements Feed {
        DirectSource(String name) {
            super(name);
        }

        @Override
        public void publish(Tick tick) {
            output.publish(tick);
        }

        @Override
        public long dropped() {
            return output.droppedCount();
        }
    }

    /** A source hosted on its own agent thread: offered from the caller, published by its agent. */
    static final class AgentSource extends InMemoryEventSource<Tick> implements Feed {
        @Override
        public void publish(Tick tick) {
            offer(tick);
        }

        @Override
        public long dropped() {
            return output.droppedCount();
        }
    }

    /**
     * Subscribes to every source in {@code start()} and checks each source's sequence. All state is written on its
     * group's thread; the progress array is read by publishers (credit) and by the test after quiescence.
     */
    static final class CheckingProcessor implements DataFlow, Lifecycle {
        final String name;
        final int sources;
        final List<EventFeed> feeds = new ArrayList<>();
        /** Next expected sequence per source: published with release semantics for the publishers' credit check. */
        final AtomicLongArray next;
        final long[] duplicatesOrReordered;
        final long[] gaps;
        final AtomicLongArray probes;
        final Histogram latency = new Histogram(TimeUnit.SECONDS.toNanos(10), 3);
        volatile long received;
        /** When positive, the handler stalls 20 ms every this many events: a slow consumer, so its queue fills. */
        long stallEvery;

        CheckingProcessor(String name, int sources) {
            this.name = name;
            this.sources = sources;
            this.next = new AtomicLongArray(sources);
            this.duplicatesOrReordered = new long[sources];
            this.gaps = new long[sources];
            this.probes = new AtomicLongArray(sources);
        }

        @Override public void init() { }
        @Override public void tearDown() { }
        @Override public void stop() { }

        @Override
        public void addEventFeed(EventFeed feed) {
            feeds.add(feed);
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public void start() {
            for (int s = 0; s < sources; s++) {
                EventSubscriptionKey<Object> key = new EventSubscriptionKey<>(new EventSourceKey<>(sourceName(s)),
                        CallBackType.ON_EVENT_CALL_BACK);
                for (EventFeed feed : feeds) feed.subscribe(this, key);
            }
        }

        @Override
        public void onEvent(Object event) {
            if (!(event instanceof Tick t)) return;
            int s = t.source();
            if (t.seq() < 0) {
                probes.lazySet(s, probes.get(s) + 1);
                return;
            }
            long expected = next.get(s);
            if (t.seq() == expected) {
                next.lazySet(s, expected + 1);
            } else if (t.seq() < expected) {
                duplicatesOrReordered[s]++;
            } else {
                gaps[s] += t.seq() - expected;
                next.lazySet(s, t.seq() + 1);
            }
            long l = System.nanoTime() - t.sentNanos();
            if (l > 0) latency.recordValue(Math.min(l, latency.getHighestTrackableValue()));
            received++;
            if (stallEvery > 0 && received % stallEvery == 0) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
    }

    static String sourceName(int s) {
        return "hv-source-" + s;
    }

    record Rig(List<Feed> feeds, List<List<CheckingProcessor>> groups) {
        List<CheckingProcessor> all() {
            return groups.stream().flatMap(List::stream).toList();
        }
    }

    private Rig boot(boolean agentHostedSources) {
        MongooseServerConfig config = new MongooseServerConfig();
        List<Feed> feeds = new ArrayList<>();
        for (int s = 0; s < SOURCES; s++) {
            if (agentHostedSources) {
                AgentSource src = new AgentSource();
                src.setName(sourceName(s));
                config.addEventSourceWorker(src, sourceName(s), false, "hv-feed-agent-" + s, new BackoffIdleStrategy());
                feeds.add(src);
            } else {
                DirectSource src = new DirectSource(sourceName(s));
                config.addEventSource(src, sourceName(s), false);
                feeds.add(src);
            }
        }
        List<List<CheckingProcessor>> groups = new ArrayList<>();
        for (int g = 0; g < GROUPS; g++) {
            List<CheckingProcessor> members = new ArrayList<>();
            for (int p = 0; p < PER_GROUP; p++) {
                CheckingProcessor processor = new CheckingProcessor("hv-g" + g + "-p" + p, SOURCES);
                members.add(processor);
                config.addProcessor("hv-group-" + g, processor, processor.name);
            }
            groups.add(members);
        }
        config.getEventHandlers().forEach(g -> g.setIdleStrategy(new BusySpinIdleStrategy()));
        server = MongooseServer.bootServer(config, rec -> { });
        return new Rig(feeds, groups);
    }

    /** Probe each source until every processor has seen it: the subscriptions made in start() are in place. */
    private static void warmUp(Rig rig) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            boolean all = true;
            for (CheckingProcessor p : rig.all()) {
                for (int s = 0; s < SOURCES; s++) all &= p.probes.get(s) > 0;
            }
            if (all) return;
            assertTrue(System.nanoTime() < deadline, "processors never subscribed to every source");
            for (int s = 0; s < SOURCES; s++) rig.feeds().get(s).publish(new Tick(s, -1, System.nanoTime()));
            Thread.sleep(1);
        }
    }

    /** The least progress any processor has made on source {@code s}. */
    private static long minProgress(Rig rig, int s) {
        long min = Long.MAX_VALUE;
        for (CheckingProcessor p : rig.all()) min = Math.min(min, p.next.get(s));
        return min;
    }

    private interface Pacer {
        /** Called before publishing {@code seq} on source {@code s}. */
        void beforePublish(int s, long seq) throws InterruptedException;
    }

    private long publishAll(Rig rig, Pacer pacer) throws Exception {
        publishers = Executors.newFixedThreadPool(SOURCES);
        long t0 = System.nanoTime();
        List<Future<?>> done = new ArrayList<>();
        for (int s = 0; s < SOURCES; s++) {
            int source = s;
            Feed feed = rig.feeds().get(s);
            done.add(publishers.submit(() -> {
                for (long seq = 0; seq < EVENTS_PER_SOURCE; seq++) {
                    pacer.beforePublish(source, seq);
                    feed.publish(new Tick(source, seq, System.nanoTime()));
                }
                return null;
            }));
        }
        for (Future<?> f : done) f.get(DEADLINE_SECONDS, TimeUnit.SECONDS);
        return t0;
    }

    /** Wait until every processor has accounted for every event of every source (handled, or missed as a gap). */
    private static void awaitAccounted(Rig rig) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS);
        LongSupplier last = () -> {
            long sum = 0;
            for (CheckingProcessor p : rig.all()) sum += p.received;
            return sum;
        };
        long previous = -1;
        long stillSince = System.nanoTime();
        while (true) {
            boolean all = true;
            for (int s = 0; s < SOURCES; s++) all &= minProgress(rig, s) >= EVENTS_PER_SOURCE;
            if (all) return;
            long now = last.getAsLong();
            if (now != previous) {
                previous = now;
                stillSince = System.nanoTime();
            } else if (System.nanoTime() - stillSince > TimeUnit.SECONDS.toNanos(5)) {
                // nothing moved for 5 s: whatever is missing will not come (it was dropped at the tail of a source)
                return;
            }
            assertTrue(System.nanoTime() < deadline, "events still undelivered at the deadline");
            Thread.sleep(5);
        }
    }

    private static void report(String test, Rig rig, long t0) {
        double secs = (System.nanoTime() - t0) / 1e9;
        long deliveries = rig.all().stream().mapToLong(p -> p.received).sum();
        long published = EVENTS_PER_SOURCE * SOURCES;
        Histogram all = new Histogram(TimeUnit.SECONDS.toNanos(10), 3);
        rig.all().forEach(p -> all.add(p.latency));
        long dropped = rig.feeds().stream().mapToLong(Feed::dropped).sum();
        System.out.printf("%n[%s] sources=%d groups=%d processors=%d published=%,d deliveries=%,d dropped=%,d in %.2fs:"
                        + " %,.0f published/s, %,.0f deliveries/s; publish->handler latency us p50=%.1f p99=%.1f p99.9=%.1f max=%.1f%n",
                test, SOURCES, GROUPS, GROUPS * PER_GROUP, published, deliveries, dropped, secs, published / secs,
                deliveries / secs, all.getValueAtPercentile(50) / 1e3, all.getValueAtPercentile(99) / 1e3,
                all.getValueAtPercentile(99.9) / 1e3, all.getMaxValue() / 1e3);
    }

    /** No event lost, duplicated or reordered, for every processor and every source. */
    private static void assertExactlyOnce(Rig rig) {
        for (CheckingProcessor p : rig.all()) {
            for (int s = 0; s < SOURCES; s++) {
                assertEquals(0, p.duplicatesOrReordered[s], p.name + " source " + s + ": duplicated or reordered events");
                assertEquals(0, p.gaps[s], p.name + " source " + s + ": events never delivered");
                assertEquals(EVENTS_PER_SOURCE, p.next.get(s), p.name + " source " + s + ": events delivered");
            }
            assertEquals(EVENTS_PER_SOURCE * SOURCES, p.received, p.name + ": events handled");
        }
        for (Feed f : rig.feeds()) assertEquals(0, f.dropped(), "a publisher dropped events");
    }

    /**
     * Sustained maximum rate with a credit window per source (publish at most {@value #CREDIT_WINDOW} ahead of the
     * slowest processor), so no reader queue ever fills: every event must reach every processor exactly once, in order.
     */
    @Test
    void sustainedRate_directSources_everyEventDeliveredExactlyOnceInOrder() throws Exception {
        Rig rig = boot(false);
        warmUp(rig);
        long t0 = publishAll(rig, (s, seq) -> {
            while (seq - minProgress(rig, s) >= CREDIT_WINDOW) Thread.onSpinWait();
        });
        awaitAccounted(rig);
        report("sustained/direct", rig, t0);
        assertExactlyOnce(rig);
    }

    /** As the sustained test, with every source hosted on its own agent thread (offer, then published by the agent). */
    @Test
    void sustainedRate_agentHostedSources_everyEventDeliveredExactlyOnceInOrder() throws Exception {
        Rig rig = boot(true);
        warmUp(rig);
        long t0 = publishAll(rig, (s, seq) -> {
            while (seq - minProgress(rig, s) >= CREDIT_WINDOW) Thread.onSpinWait();
        });
        awaitAccounted(rig);
        report("sustained/agent-hosted", rig, t0);
        assertExactlyOnce(rig);
    }

    /**
     * Unthrottled bursts of 50,000 per source with 50 ms pauses, and one processor that stalls 20 ms every 100,000
     * events. Its group's reader queues fill and the publisher's
     * documented policy is then to drop the item for that queue after a 10 ms spin (the source's {@code SlowConsumerStrategy}, BACKOFF by default, is
     * not consulted by the publisher). So: never a duplicate or a reorder,
     * every processor of a group sees the same gaps (they share one reader queue per source), and per source the gaps
     * over all groups add up exactly to what the publisher counted as dropped.
     */
    @Test
    void unthrottledBursts_noDuplicatesNoReorder_andEveryMissingEventIsACountedDrop() throws Exception {
        Rig rig = boot(false);
        // one slow consumer in group 0: its group's reader queues fill, the other groups' do not
        rig.groups().get(0).get(0).stallEvery = 100_000;
        warmUp(rig);
        long t0 = publishAll(rig, (s, seq) -> {
            if (seq > 0 && seq % 50_000 == 0) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
        });
        awaitAccounted(rig);
        report("bursts/unthrottled", rig, t0);
        for (CheckingProcessor p : rig.all()) {
            for (int s = 0; s < SOURCES; s++) {
                assertEquals(0, p.duplicatesOrReordered[s], p.name + " source " + s + ": duplicated or reordered events");
            }
        }
        for (int s = 0; s < SOURCES; s++) {
            long gapsOverGroups = 0;
            for (List<CheckingProcessor> group : rig.groups()) {
                long g0 = group.get(0).gaps[s] + (EVENTS_PER_SOURCE - group.get(0).next.get(s));
                for (CheckingProcessor p : group) {
                    assertEquals(g0, p.gaps[s] + (EVENTS_PER_SOURCE - p.next.get(s)),
                            p.name + " source " + s + ": processors sharing a reader queue missed different events");
                }
                gapsOverGroups += g0;
            }
            assertEquals(rig.feeds().get(s).dropped(), gapsOverGroups,
                    "source " + s + ": every event missing from a group is one its publisher counted as dropped");
        }
    }
}
