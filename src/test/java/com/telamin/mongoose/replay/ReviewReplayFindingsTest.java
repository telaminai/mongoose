package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.EventFeedConfig;
import com.telamin.mongoose.config.EventProcessorConfig;
import com.telamin.mongoose.config.EventProcessorGroupConfig;
import com.telamin.mongoose.config.EventSinkConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.connector.memory.InMemoryMessageSink;
import com.telamin.mongoose.internal.ConfigAwareEventProcessor;
import com.telamin.mongoose.service.servercontrol.MongooseServerController;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Independent review probes for 1eeac8f (recorded and intercepted control requests, provenance). */
class ReviewReplayFindingsTest {

    static final String ORDERS = "orders", PROCESSOR = "quotes", VICTIM = "victim", GROUP = "processor-agent";

    /** Makes its control request in start(), before any input: a boot-time request. */
    public static class KillsInStart extends ObjectEventHandlerNode {
        private MongooseServerController controller;

        @ServiceRegistered
        public void controller(MongooseServerController controller, String name) {
            this.controller = controller;
        }

        @Override
        public void start() {
            getContext().subscribeToNamedFeed(ORDERS);
            controller.stopService("no-such-service");       // a request that changes the server, made at start
        }

        @Override
        protected boolean handleEvent(Object event) {
            return true;
        }
    }

    /** On "spawn", adds a processor to the live server: a request that changes the server but is not intercepted. */
    public static class Spawner extends ObjectEventHandlerNode {
        private MongooseServerController controller;

        @ServiceRegistered
        public void controller(MongooseServerController controller, String name) {
            this.controller = controller;
        }

        @Override
        public void start() {
            getContext().subscribeToNamedFeed(ORDERS);
        }

        @Override
        protected boolean handleEvent(Object event) {
            if ("spawn".equals(event)) {
                controller.addEventProcessor("child", GROUP, null, () -> {
                    ConfigAwareEventProcessor flow = new ConfigAwareEventProcessor(new Quiet());
                    flow.init();
                    return flow;
                });
            }
            return true;
        }
    }

    public static class Quiet extends ObjectEventHandlerNode {
        @Override
        protected boolean handleEvent(Object event) {
            return true;
        }
    }

    record Server(MongooseServer server, InMemoryEventSource<Object> orders) implements AutoCloseable {
        GroupReplayer replayer() {
            return server.replayers().get(GROUP);
        }

        boolean running(String name) {
            return server.registeredProcessors().getOrDefault(GROUP, List.of()).stream().anyMatch(n -> n.name().equals(name));
        }

        @Override
        public void close() {
            server.stop();
        }
    }

    static Server boot(ReplayConfig replay, Supplier<ObjectEventHandlerNode> handler) throws Exception {
        InMemoryEventSource<Object> orders = new InMemoryEventSource<>();
        orders.setName(ORDERS);
        orders.setCacheEventLog(true);
        MongooseServerConfig config = MongooseServerConfig.builder()
                .addProcessorGroup(EventProcessorGroupConfig.builder().agentName(GROUP)
                        .put(PROCESSOR, new EventProcessorConfig(handler.get()))
                        .put(VICTIM, new EventProcessorConfig(new Quiet())).build())
                .addEventFeed(EventFeedConfig.builder().instance(orders).name(ORDERS).broadcast(true)
                        .agent("orders-agent", new BusySpinIdleStrategy()).build())
                .addEventSink(EventSinkConfig.<MessageSink<?>>builder().instance(new InMemoryMessageSink()).name("memSink").build())
                .replay(replay)
                .build();
        MongooseServer server = MongooseServer.bootServer(config, rec -> { });
        Thread.sleep(200);
        return new Server(server, orders);
    }

    private static ReplayConfig record(InMemoryReplayStore store, InMemoryEventJournal journal) {
        return ReplayConfig.record(Set.of(PROCESSOR), Map.of(ORDERS, new JavaSerializationCodec()), journal, store);
    }

    private static ReplayConfig replay(InMemoryReplayStore store, InMemoryEventJournal journal) {
        return ReplayConfig.replay(Set.of(PROCESSOR), Map.of(ORDERS, new JavaSerializationCodec()), journal, store);
    }

    private static void awaitTrue(java.util.function.BooleanSupplier c, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!c.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(c.getAsBoolean(), what);
    }

    /**
     * Finding: a control request made in start() (or any build-time callback) is recorded as the stream's first entry,
     * but in REPLAY the processor's start() runs in buildAndStart, before GroupReplayer.doWork has delivered that
     * ControlCall entry into expectedCalls, so the identical request is reported as a divergence.
     */
    @Test
    void aControlRequestMadeInStartReplaysWithoutDivergence() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        InMemoryEventJournal journal = new InMemoryEventJournal();
        try (Server s = boot(record(store, journal), KillsInStart::new)) {
            s.orders().offer("tick");
            Thread.sleep(200);
        }
        List<ReplayEntry> entries = store.entries(PROCESSOR);
        assertTrue(entries.get(0) instanceof ReplayEntry.ControlCall, "recorded first: " + entries);

        try (Server s = boot(replay(store, journal), KillsInStart::new)) {
            Thread.sleep(300);
            String stopped = s.replayer().stopped(PROCESSOR);
            // the same program, the same request, the same position: there is no divergence to report
            assertNull(stopped, "false divergence: " + stopped);
        }
    }

    /** Finding: addEventProcessor is not in ControlInterception.CHANGES, so a replayed processor adds live. */
    @Test
    void anAddFromAReplayedProcessorIsInterceptedNotExecuted() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        InMemoryEventJournal journal = new InMemoryEventJournal();
        try (Server s = boot(record(store, journal), Spawner::new)) {
            s.orders().offer("spawn");
            awaitTrue(() -> s.running("child"), "the recorded run added the child");
        }
        assertTrue(store.entries(PROCESSOR).stream().anyMatch(e -> e instanceof ReplayEntry.ControlCall cc
                        && cc.method().equals("addEventProcessor")),
                "recorded as a control call: " + store.entries(PROCESSOR));
        try (Server s = boot(replay(store, journal), Spawner::new)) {
            awaitTrue(() -> s.replayer().complete(), "replayed");
            Thread.sleep(200);
            assertFalse(s.running("child"), "the replay executed the recorded run's add against the live server");
        }
    }

    /**
     * Finding: a later incarnation that was recorded (its BEGAN provenance is in the store) but received no input before
     * the recording ended has no entries, and ReplayStore.has() looks only at entries, so the replay stops it loudly as
     * "no stream was recorded" although one was.
     */
    @Test
    void aRecordedIncarnationWithNoInputsReplays() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        InMemoryEventJournal journal = new InMemoryEventJournal();
        String secondKey = ReplayStreams.key(PROCESSOR, 2);
        try (Server s = boot(record(store, journal), Quiet::new)) {
            s.server().stopProcessor(GROUP, PROCESSOR);
            awaitTrue(() -> !s.running(PROCESSOR), "stopped");
            reAdd(s);
            awaitTrue(() -> s.running(PROCESSOR), "re-added");
            Thread.sleep(100);
        }
        assertTrue(store.provenance(secondKey) != null, "the second incarnation was recorded (BEGAN)");
        assertTrue(store.entries(secondKey).isEmpty(), "but took no input");

        try (Server s = boot(replay(store, journal), Quiet::new)) {
            awaitTrue(() -> !s.running(PROCESSOR), "the replay stopped the first incarnation");
            reAdd(s);
            awaitTrue(() -> s.running(PROCESSOR), "re-added");
            Thread.sleep(100);
            String stopped = s.replayer().stopped(secondKey);
            assertNull(stopped, "a recorded, empty stream is not an absent one: " + stopped);
        }
    }

    private static void reAdd(Server s) {
        s.server().addEventProcessor(PROCESSOR, GROUP, null, () -> {
            ConfigAwareEventProcessor flow = new ConfigAwareEventProcessor(new Quiet());
            flow.init();
            return flow;
        });
    }

    @SuppressWarnings("unused")
    private static DataFlow unused() {
        return null;
    }
}
