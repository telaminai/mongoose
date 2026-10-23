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
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test
    void eventsQueuedBeforeStopAreDelivered() throws Exception {
        InMemoryEventSource<String> feed = new InMemoryEventSource<>();
        LifecycleProbe processor = new LifecycleProbe(1);   // slower than the feed, so events queue up
        MongooseServerConfig cfg = new MongooseServerConfig();
        cfg.addProcessor("g", processor, "A");
        cfg.addEventSourceWorker(feed, FEED, true, "feed-agent", new SleepingMillisIdleStrategy(1));
        MongooseServer server = MongooseServer.bootServer(cfg);
        await(() -> processor.started, "processor start");
        Thread.sleep(100);   // let the group add the queue reader

        int published = 300;   // ~300 ms of processor work, inside the drain bound
        for (int i = 0; i < published; i++) feed.publishNow("e" + i);
        assertTrue(processor.received.get() < published, "the test needs events still queued at stop");

        server.stop();

        assertEquals(published, processor.received.get(), "events queued before stop are delivered before processors stop");
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
        server.stop();   // a second stop does not unmap the commit pointer twice
    }

    private static Object field(Object target, String name) throws Exception {
        Field f = FileEventSource.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}
