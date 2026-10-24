/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.ReadStrategy;
import com.telamin.mongoose.connector.file.FileEventSource;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventSourceKey;
import com.telamin.mongoose.service.EventSubscriptionKey;
import com.telamin.mongoose.service.error.ErrorEvent;
import com.telamin.mongoose.service.error.ErrorListener;
import com.telamin.mongoose.service.error.ErrorReporting;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * mongoose#56: {@link MongooseServer#stop()} runs an ordered shutdown — feeds stop, queued events drain, processors stop
 * and tear down on their own agent thread, then the remaining services stop — and a second stop is a no-op.
 */
class OrderedServerStopTest {

    static final String FEED = "feed";

    @TempDir
    Path tempDir;

    /** A processor that counts its lifecycle calls and records the thread each ran on. */
    public static class LifecycleProbe implements DataFlow {
        final AtomicInteger received = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();
        final AtomicInteger tearDowns = new AtomicInteger();
        final List<EventFeed> feeds = new ArrayList<>();
        final long sleepPerEventMillis;
        volatile boolean started;
        volatile Thread startThread, stopThread, tearDownThread;
        volatile boolean interruptedAtStop;

        LifecycleProbe(long sleepPerEventMillis) {
            this.sleepPerEventMillis = sleepPerEventMillis;
        }

        @Override public void init() {}

        @Override
        public void start() {
            startThread = Thread.currentThread();
            for (EventFeed f : feeds) {
                f.subscribe(this, new EventSubscriptionKey<>(new EventSourceKey<>(FEED), CallBackType.ON_EVENT_CALL_BACK));
            }
            started = true;
        }

        @Override
        public void stop() {
            stopThread = Thread.currentThread();
            interruptedAtStop = Thread.currentThread().isInterrupted();
            stops.incrementAndGet();
        }

        @Override
        public void tearDown() {
            tearDownThread = Thread.currentThread();
            tearDowns.incrementAndGet();
            for (EventFeed f : feeds) f.removeAllSubscriptions(this);
        }

        @Override public void addEventFeed(EventFeed f) { feeds.add(f); }

        @Override
        public void onEvent(Object e) {
            if (!(e instanceof String)) return;   // the server also sends log-control events
            if (sleepPerEventMillis > 0) {
                try {
                    Thread.sleep(sleepPerEventMillis);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            received.incrementAndGet();
        }
    }

    /** An in-memory feed that counts its stop and tear-down calls. */
    public static class CountingSource<T> extends InMemoryEventSource<T> {
        final AtomicInteger stops = new AtomicInteger();
        final AtomicInteger tearDowns = new AtomicInteger();

        @Override public void stop() { stops.incrementAndGet(); }

        @Override public void tearDown() { tearDowns.incrementAndGet(); }
    }

    static void await(BooleanSupplier c, String what) throws Exception {
        long end = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < end && !c.getAsBoolean()) Thread.sleep(10);
        assertTrue(c.getAsBoolean(), "timed out waiting for " + what);
    }

    @Test
    void stopRunsProcessorAndFeedLifecycleOnceAndIsIdempotent() throws Exception {
        CountingSource<String> feed = new CountingSource<>();
        LifecycleProbe processor = new LifecycleProbe(0);
        MongooseServerConfig cfg = new MongooseServerConfig();
        cfg.addProcessor("g", processor, "A");
        cfg.addEventSourceWorker(feed, FEED, true, "feed-agent", new SleepingMillisIdleStrategy(1));
        MongooseServer server = MongooseServer.bootServer(cfg);
        await(() -> processor.started, "processor start");

        server.stop();

        assertEquals(1, processor.stops.get(), "processor @Stop runs once on server stop");
        assertEquals(1, processor.tearDowns.get(), "processor @TearDown runs once on server stop");
        assertSame(processor.startThread, processor.stopThread, "processor stops on its agent thread");
        assertSame(processor.startThread, processor.tearDownThread, "processor tears down on its agent thread");
        assertEquals(1, feed.stops.get(), "feed stop runs once on server stop");
        assertEquals(1, feed.tearDowns.get(), "feed tearDown runs once on server stop");

        server.stop();   // a second stop is a no-op

        assertEquals(1, processor.stops.get(), "a second server stop does not stop the processor again");
        assertEquals(1, processor.tearDowns.get(), "a second server stop does not tear the processor down again");
        assertEquals(1, feed.stops.get(), "a second server stop does not stop the feed again");
        assertEquals(1, feed.tearDowns.get(), "a second server stop does not tear the feed down again");
    }

    /** A processor that blocks on its first event until the gate opens, so every later event stays queued. */
    public static class GatedProbe extends LifecycleProbe {
        final CountDownLatch gate = new CountDownLatch(1);
        final CountDownLatch blocked = new CountDownLatch(1);

        GatedProbe() {
            super(0);
        }

        @Override
        public void onEvent(Object e) {
            if (e instanceof String && blocked.getCount() > 0) {
                blocked.countDown();
                try {
                    if (!gate.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("gate never opened");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            super.onEvent(e);
        }
    }

    /** The gate opens only when the feed is stopped: the events still queued then can only arrive through the drain. */
    @Test
    void eventsQueuedBeforeStopAreDelivered() throws Exception {
        GatedProbe processor = new GatedProbe();
        CountingSource<String> feed = new CountingSource<>() {
            @Override public void stop() { super.stop(); processor.gate.countDown(); }
        };
        // the drain bound is generous so a slow machine cannot cut the drain short; the gate, not timing, decides
        MongooseServerConfig cfg = MongooseServerConfig.builder().shutdownDrainTimeoutMillis(30_000).build();
        cfg.addProcessor("g", processor, "A");
        cfg.addEventSourceWorker(feed, FEED, true, "feed-agent", new SleepingMillisIdleStrategy(1));
        MongooseServer server = MongooseServer.bootServer(cfg);
        await(() -> processor.started, "processor start");
        Thread.sleep(100);   // let the group add the queue reader

        int published = 50;
        for (int i = 0; i < published; i++) feed.publishNow("e" + i);
        assertTrue(processor.blocked.await(5, TimeUnit.SECONDS), "the processor took the first event and blocks");
        assertEquals(0, processor.received.get(), "nothing delivered before stop: the rest are queued");

        server.stop();

        assertEquals(1, feed.stops.get(), "the feed stopped, which opened the gate");
        assertEquals(published, processor.received.get(), "events queued before stop are delivered before processors stop");
    }

    /**
     * Review of #61: stop() called on one of the server's own agent threads (here a processor's onEvent) must not join
     * that thread: it returns at once and the stop runs on another thread, completing quickly with no interrupt left.
     */
    @Test
    void stopCalledFromAProcessorCompletesQuicklyWithoutInterruptingTheAgent() throws Exception {
        AtomicReference<MongooseServer> serverRef = new AtomicReference<>();
        AtomicLong stopCallNanos = new AtomicLong(-1);
        AtomicBoolean interruptedAfterStop = new AtomicBoolean();
        LifecycleProbe processor = new LifecycleProbe(0) {
            @Override
            public void onEvent(Object e) {
                if ("stop".equals(e)) {
                    long t0 = System.nanoTime();
                    serverRef.get().stop();
                    interruptedAfterStop.set(Thread.currentThread().isInterrupted());
                    stopCallNanos.set(System.nanoTime() - t0);
                }
                super.onEvent(e);
            }
        };
        CountingSource<String> feed = new CountingSource<>();
        MongooseServerConfig cfg = new MongooseServerConfig();
        cfg.addProcessor("g", processor, "A");
        cfg.addEventSourceWorker(feed, FEED, true, "feed-agent", new SleepingMillisIdleStrategy(1));
        MongooseServer server = MongooseServer.bootServer(cfg);
        serverRef.set(server);
        await(() -> processor.started, "processor start");
        Thread.sleep(100);   // let the group add the queue reader

        long t0 = System.nanoTime();
        feed.publishNow("stop");
        await(() -> processor.tearDowns.get() == 1 && feed.tearDowns.get() == 1, "stop to complete");
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertTrue(stopCallNanos.get() >= 0 && TimeUnit.NANOSECONDS.toMillis(stopCallNanos.get()) < 1_000,
                "stop() on an agent thread returns at once, took " + TimeUnit.NANOSECONDS.toMillis(stopCallNanos.get()) + "ms");
        assertTrue(tookMillis < 1_000, "stop requested from a processor completes in under 1s, took " + tookMillis + "ms");
        assertTrue(server.awaitStopped(1, TimeUnit.SECONDS), "awaitStopped sees the stop finish");
        assertFalse(interruptedAfterStop.get(), "stop() leaves the calling agent thread uninterrupted");
        assertFalse(processor.interruptedAtStop, "the processor's @Stop does not run on an interrupted thread");
        assertEquals(1, processor.stops.get(), "processor @Stop runs once");
        assertSame(processor.startThread, processor.stopThread, "processor still stops on its agent thread");
        assertEquals(1, feed.stops.get(), "feed stop runs once");
    }

    /** A worker service that records when its agent closes. */
    public static class SharedWorker implements Agent {
        volatile boolean closed;

        @Override public int doWork() { return 0; }

        @Override public void onClose() { closed = true; }

        @Override public String roleName() { return "shared-worker"; }
    }

    /**
     * Review of #61: a plain service sharing an agent group with a feed must not be closed with the feed, before the
     * processors that may still use it have drained and stopped.
     */
    @Test
    void aServiceSharingAGroupWithAFeedOutlivesTheProcessors() throws Exception {
        SharedWorker worker = new SharedWorker();
        AtomicReference<Boolean> workerClosedAtProcessorStop = new AtomicReference<>();
        LifecycleProbe processor = new LifecycleProbe(0) {
            @Override
            public void stop() {
                workerClosedAtProcessorStop.set(worker.closed);
                super.stop();
            }
        };
        CountingSource<String> feed = new CountingSource<>();
        MongooseServerConfig cfg = new MongooseServerConfig();
        cfg.addProcessor("g", processor, "A");
        cfg.addEventSourceWorker(feed, FEED, true, "shared-agent", new SleepingMillisIdleStrategy(1));
        cfg.addWorkerService(worker, Agent.class, "worker", "shared-agent", new SleepingMillisIdleStrategy(1));
        MongooseServer server = MongooseServer.bootServer(cfg);
        await(() -> processor.started, "processor start");

        server.stop();

        assertEquals(Boolean.FALSE, workerClosedAtProcessorStop.get(),
                "the service sharing the feed's group is still running when the processors stop");
        assertTrue(worker.closed, "the shared service's agent is closed by the end of stop");
        assertEquals(1, feed.stops.get(), "the feed in the shared group is stopped");
        assertEquals(1, processor.stops.get(), "processor @Stop runs once");
    }

    /** Several threads calling stop() at once: it runs once, and no caller returns before the server has stopped. */
    @Test
    void concurrentStopCallersRunTheStopOnceAndReturnWhenStopped() throws Exception {
        CountingSource<String> feed = new CountingSource<>();
        LifecycleProbe processor = new LifecycleProbe(0);
        MongooseServerConfig cfg = new MongooseServerConfig();
        cfg.addProcessor("g", processor, "A");
        cfg.addEventSourceWorker(feed, FEED, true, "feed-agent", new SleepingMillisIdleStrategy(1));
        MongooseServer server = MongooseServer.bootServer(cfg);
        await(() -> processor.started, "processor start");

        int callers = 8;
        CyclicBarrier barrier = new CyclicBarrier(callers);
        List<Integer> tearDownsSeenOnReturn = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            Thread t = new Thread(() -> {
                try {
                    barrier.await();
                    server.stop();
                    tearDownsSeenOnReturn.add(processor.tearDowns.get());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join(10_000);

        assertEquals(callers, tearDownsSeenOnReturn.size(), "every caller returned");
        assertTrue(tearDownsSeenOnReturn.stream().allMatch(n -> n == 1),
                "no stop() caller returns before the processors are torn down: " + tearDownsSeenOnReturn);
        assertEquals(1, processor.stops.get(), "processor @Stop runs once");
        assertEquals(1, feed.stops.get(), "feed stop runs once");
    }

    /** A processor failing to stop is reported at ERROR, once; its tearDown and the other processors still run. */
    @Test
    void aProcessorFailingToStopIsReportedAndTheOthersStillStop() throws Exception {
        List<ErrorEvent> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        ErrorListener listener = errors::add;
        ErrorReporting.getReporter().addListener(listener);
        try {
            LifecycleProbe failing = new LifecycleProbe(0) {
                @Override
                public void stop() {
                    super.stop();
                    throw new IllegalStateException("stop boom");
                }
            };
            LifecycleProbe healthy = new LifecycleProbe(0);
            CountingSource<String> feed = new CountingSource<>();
            MongooseServerConfig cfg = new MongooseServerConfig();
            cfg.addProcessor("g", failing, "failing");
            cfg.addProcessor("g", healthy, "healthy");
            cfg.addEventSourceWorker(feed, FEED, true, "feed-agent", new SleepingMillisIdleStrategy(1));
            MongooseServer server = MongooseServer.bootServer(cfg);
            await(() -> failing.started && healthy.started, "processors start");

            server.stop();

            assertEquals(1, failing.tearDowns.get(), "a processor whose stop throws is still torn down");
            assertEquals(1, healthy.stops.get(), "the other processor still stops");
            assertEquals(1, healthy.tearDowns.get(), "the other processor is still torn down");
            List<ErrorEvent> reported = errors.stream()
                    .filter(ev -> ev.getError() != null && "stop boom".equals(ev.getError().getMessage()))
                    .toList();
            assertEquals(1, reported.size(), "the stop failure is reported once: " + errors);
            assertEquals(ErrorEvent.Severity.ERROR, reported.get(0).getSeverity(), "reported at ERROR");
        } finally {
            ErrorReporting.getReporter().removeListener(listener);
        }
    }

    @Test
    void stopReleasesFileEventSourceResources() throws Exception {
        Path data = tempDir.resolve("events.txt");
        Files.writeString(data, "line 1\nline 2\n");
        FileEventSource feed = new FileEventSource();
        feed.setFilename(data.toString());
        feed.setReadStrategy(ReadStrategy.COMMITED);
        LifecycleProbe processor = new LifecycleProbe(0);
        MongooseServerConfig cfg = new MongooseServerConfig();
        cfg.addProcessor("g", processor, "A");
        cfg.addEventSourceWorker(feed, FEED, true, "feed-agent", new SleepingMillisIdleStrategy(1));
        MongooseServer server = MongooseServer.bootServer(cfg);
        await(() -> processor.received.get() >= 2, "file lines delivered");
        InputStream stream = (InputStream) field(feed, "stream");
        assertNotNull(stream, "the file source has its input stream open while running");

        server.stop();

        assertThrows(IOException.class, stream::read, "FileEventSource.stop closes the input stream");
        assertNull(field(feed, "commitPointer"), "FileEventSource.stop unmaps the commit pointer");
        // the server stops a feed once, so stop the source directly: a stop after the release neither forces nor
        // unmaps the commit pointer again (forcing an unmapped buffer faults) and leaves it released
        assertDoesNotThrow(feed::stop, "a second FileEventSource.stop after the server's is safe");
        assertDoesNotThrow(feed::stop, "a third FileEventSource.stop is safe");
        assertNull(field(feed, "commitPointer"), "the commit pointer stays released");
        assertNull(field(feed, "stream"), "the input stream stays released");
    }

    @Test
    void shutdownDrainTimeoutIsConfigurableFromYamlAndTheBuilder() {
        org.yaml.snakeyaml.LoaderOptions options = new org.yaml.snakeyaml.LoaderOptions();
        MongooseServerConfig fromYaml = new org.yaml.snakeyaml.Yaml(options)
                .loadAs("shutdownDrainTimeoutMillis: 1234\n", MongooseServerConfig.class);
        assertEquals(1234, fromYaml.getShutdownDrainTimeoutMillis(), "YAML key shutdownDrainTimeoutMillis");
        assertEquals(MongooseServerConfig.DEFAULT_SHUTDOWN_DRAIN_TIMEOUT_MILLIS,
                MongooseServerConfig.builder().build().getShutdownDrainTimeoutMillis(), "builder default");
        assertEquals(0, MongooseServerConfig.builder().shutdownDrainTimeoutMillis(0).build().getShutdownDrainTimeoutMillis(),
                "builder setter");
    }

    private static Object field(Object target, String name) throws Exception {
        Field f = FileEventSource.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}
