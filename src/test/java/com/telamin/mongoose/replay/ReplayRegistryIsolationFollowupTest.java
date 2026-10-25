package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceDeregistered;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.*;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.service.servercontrol.MongooseServerController;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ReplayRegistryIsolationFollowupTest {
    public interface Shared { }
    public static class SharedService implements Shared, Lifecycle {
        final AtomicInteger stops = new AtomicInteger();
        public void init() { }
        public void start() { }
        public void stop() { stops.incrementAndGet(); }
        public void tearDown() { }
    }
    public static class Actor extends ObjectEventHandlerNode {
        MongooseServerController controller;
        @ServiceRegistered public void controller(MongooseServerController controller, String name) { this.controller = controller; }
        public void start() { getContext().subscribeToNamedFeed("feed"); }
        protected boolean handleEvent(Object event) {
            if ("remove".equals(event)) controller.removeService("shared");
            return true;
        }
    }
    public static class LiveObserver extends ObjectEventHandlerNode {
        final AtomicInteger withdrawals = new AtomicInteger();
        @ServiceRegistered public void shared(Shared service, String name) { }
        @ServiceDeregistered public void withdrawn(Shared service, String name) { withdrawals.incrementAndGet(); }
        protected boolean handleEvent(Object event) { return true; }
    }
    record Running(MongooseServer server, InMemoryEventSource<Object> feed, SharedService shared, LiveObserver observer)
            implements AutoCloseable {
        public void close() { server.stop(); }
    }
    private Running boot(ReplayConfig replay) throws Exception {
        var shared = new SharedService();
        var observer = new LiveObserver();
        var feed = new InMemoryEventSource<Object>();
        feed.setName("feed");
        feed.setCacheEventLog(true);
        var config = MongooseServerConfig.builder()
                .addProcessorGroup(EventProcessorGroupConfig.builder().agentName("g")
                        .put("actor", new EventProcessorConfig(new Actor()))
                        .put("observer", new EventProcessorConfig(observer)).build())
                .addService(new ServiceConfig<>(shared, Shared.class.getName(), "shared", null, null))
                .addEventFeed(EventFeedConfig.builder().instance(feed).name("feed").broadcast(true)
                        .agent("feed-agent", new BusySpinIdleStrategy()).build())
                .replay(replay).build();
        var server = MongooseServer.bootServer(config, r -> { });
        Thread.sleep(100);
        return new Running(server, feed, shared, observer);
    }
    private void await(java.util.function.BooleanSupplier c) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!c.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        assertTrue(c.getAsBoolean());
    }
    @Test void matchedReplayRemovalMustNotStopAServiceUsedByAnUnreplayedProcessor() throws Exception {
        var store = new InMemoryReplayStore();
        var journal = new InMemoryEventJournal();
        var codecs = Map.<String, EventCodec>of("feed", new JavaSerializationCodec());
        try (var live = boot(ReplayConfig.record(Set.of("actor"), codecs, journal, store))) {
            live.feed.offer("remove");
            await(() -> live.shared.stops.get() == 1);
            assertEquals(1, live.observer.withdrawals.get());
        }
        try (var replay = boot(ReplayConfig.replay(Set.of("actor"), codecs, journal, store))) {
            var replayer = replay.server.replayers().get("g");
            await(replayer::complete);
            Thread.sleep(100);
            assertNotNull(replayer.stopped("actor"));
            assertTrue(replayer.stopped("actor").contains("registry mutation removeService"));
            assertEquals(0, replay.shared.stops.get(), "replayed removeService must not stop the live shared service");
            assertEquals(0, replay.observer.withdrawals.get(), "unreplayed processor must retain its service");
            assertTrue(replay.server.registeredServices().containsKey("shared"));
        }
    }
}
