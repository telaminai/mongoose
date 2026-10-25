package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.EventFeedConfig;
import com.telamin.mongoose.config.EventProcessorConfig;
import com.telamin.mongoose.config.EventProcessorGroupConfig;
import com.telamin.mongoose.config.EventSinkConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.connector.memory.InMemoryMessageSink;
import com.telamin.mongoose.service.servercontrol.MongooseServerController;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review probe: a replayed processor's registerService is intercepted (never executed), but its stream then records
 * the ServiceChanged that registration produced; the replay cannot deliver a service this run never registered.
 */
class ReviewReplayServiceInterceptionTest {

    static final String ORDERS = "orders", PROCESSOR = "quotes", GROUP = "processor-agent";

    public interface Extra { }

    public static class RegistersAService extends ObjectEventHandlerNode {
        private MongooseServerController controller;
        private MessageSink<String> sink;

        @ServiceRegistered
        public void controller(MongooseServerController controller, String name) {
            this.controller = controller;
        }

        @ServiceRegistered
        public void sink(MessageSink<String> sink, String name) {
            this.sink = sink;
        }

        @ServiceRegistered
        public void extra(Extra extra, String name) {
            if (sink != null) sink.accept("got extra");
        }

        @Override
        public void start() {
            getContext().subscribeToNamedFeed(ORDERS);
        }

        @Override
        protected boolean handleEvent(Object event) {
            if ("register".equals(event)) {
                controller.registerService(new Service<>(new Extra() { }, Extra.class, "extra"));
            }
            return true;
        }
    }

    record Server(MongooseServer server, InMemoryEventSource<Object> orders, InMemoryMessageSink sink) implements AutoCloseable {
        GroupReplayer replayer() {
            return server.replayers().get(GROUP);
        }

        List<String> sinkLines() {
            return sink.getMessages().stream().map(String::valueOf).toList();
        }

        @Override
        public void close() {
            server.stop();
        }
    }

    static Server boot(ReplayConfig replay) throws Exception {
        InMemoryEventSource<Object> orders = new InMemoryEventSource<>();
        orders.setName(ORDERS);
        orders.setCacheEventLog(true);
        InMemoryMessageSink sink = new InMemoryMessageSink();
        MongooseServerConfig config = MongooseServerConfig.builder()
                .addProcessorGroup(EventProcessorGroupConfig.builder().agentName(GROUP)
                        .put(PROCESSOR, new EventProcessorConfig(new RegistersAService())).build())
                .addEventFeed(EventFeedConfig.builder().instance(orders).name(ORDERS).broadcast(true)
                        .agent("orders-agent", new BusySpinIdleStrategy()).build())
                .addEventSink(EventSinkConfig.<MessageSink<?>>builder().instance(sink).name("memSink").build())
                .replay(replay)
                .build();
        MongooseServer server = MongooseServer.bootServer(config, rec -> { });
        Thread.sleep(200);
        return new Server(server, orders, sink);
    }

    private static void awaitTrue(java.util.function.BooleanSupplier c, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!c.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(c.getAsBoolean(), what);
    }

    @Test
    void aServiceRegistrationFromTheReplayIsRefusedWithoutMutatingTheLiveRegistry() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        InMemoryEventJournal journal = new InMemoryEventJournal();
        try (Server s = boot(ReplayConfig.record(Set.of(PROCESSOR), Map.of(ORDERS, new JavaSerializationCodec()), journal, store))) {
            s.orders().offer("register");
            awaitTrue(() -> s.sinkLines().contains("got extra"), "the recorded run was given the service it registered");
        }
        List<ReplayEntry> entries = store.entries(PROCESSOR);
        assertTrue(entries.stream().anyMatch(e -> e instanceof ReplayEntry.ControlCall cc && cc.method().equals("registerService")), entries.toString());
        assertTrue(entries.stream().anyMatch(e -> e instanceof ReplayEntry.ServiceChanged sc && sc.service().equals("extra")), entries.toString());

        try (Server s = boot(ReplayConfig.replay(Set.of(PROCESSOR), Map.of(ORDERS, new JavaSerializationCodec()), journal, store)
                .withDeliveryTimeout(Duration.ofMillis(500)))) {
            awaitTrue(() -> s.replayer().complete(), "replay finished one way or another");
            String stopped = s.replayer().stopped(PROCESSOR);
            assertNotNull(stopped, "registry reconstruction requires an isolated replay registry");
            assertTrue(stopped.contains("registry mutation registerService"), stopped);
            assertFalse(s.server().registeredServices().containsKey("extra"), "no live registration or injection");
            assertTrue(s.replayer().outputs(PROCESSOR).isEmpty());
        }
    }
}
