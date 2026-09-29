package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.DefaultEventProcessor;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.event.Signal;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.EventFeedConfig;
import com.telamin.mongoose.config.EventProcessorConfig;
import com.telamin.mongoose.config.EventProcessorGroupConfig;
import com.telamin.mongoose.config.EventSinkConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.ServiceConfig;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.connector.memory.InMemoryMessageSink;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.admin.AdminCommandRequest;
import com.telamin.mongoose.service.admin.impl.AdminCommandProcessor;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Option A of admin-commands-in-an-event-cycle, spiked: a signal-routed command reaches its processor as an ordinary
 * event, {@code Signal("admin:<name>", request)}, so its handler runs INSIDE an event cycle. Shown here on a hand-written
 * processor (the delivery, the cycle, the reply, the refusals, record and replay). A generated processor's audit record
 * and propagation follow from the same onEvent by construction; showing them needs a generated processor (spec 3d).
 */
class SignalAdminCommandTest {

    /** Registers two signal commands: one it handles and replies to, one it never handles. */
    public static class AlarmNode extends ObjectEventHandlerNode {
        volatile boolean raised;
        volatile Boolean handledInCycle;
        CountingProcessor processor;
        private MessageSink<String> sink;

        @ServiceRegistered
        public void admin(AdminCommandRegistry registry, String name) {
            registry.registerSignalCommand("alarm.reset");
            registry.registerSignalCommand("alarm.ignored");
        }

        @ServiceRegistered
        public void sink(MessageSink<String> sink, String name) {
            this.sink = sink;
        }

        @Override
        public void start() {
            getContext().subscribeToNamedFeed("events");
        }

        @Override
        protected boolean handleEvent(Object event) {
            long time = getContext().getClock().getProcessTime();
            if (event instanceof String s) {
                raised = true;
                if (sink != null) sink.accept("event=" + s + " raised=" + raised + " time=" + time);
            } else if (event instanceof Signal<?> signal && "admin:alarm.reset".equals(signal.filterString())) {
                // an ordinary filtered signal handler: in a generated processor, @OnEventHandler(filterString = ...)
                AdminCommandRequest request = (AdminCommandRequest) signal.getValue();
                handledInCycle = processor.inCycle;
                raised = false;
                if (sink != null) sink.accept("reset by=" + request.getArguments() + " raised=" + raised + " time=" + time);
                request.getOutput().accept("alarm cleared");
            }
            return true;
        }
    }

    /** Counts event cycles, and knows when one is open. */
    public static class CountingProcessor extends DefaultEventProcessor {
        final AtomicInteger cycles = new AtomicInteger();
        volatile boolean inCycle;

        CountingProcessor(AlarmNode node) {
            super(node);
            node.processor = this;
        }

        @Override
        public void onEvent(Object event) {
            cycles.incrementAndGet();
            boolean outer = inCycle;
            inCycle = true;
            try {
                super.onEvent(event);
            } finally {
                inCycle = outer;
            }
        }
    }

    record Server(MongooseServer server, InMemoryEventSource<Object> events, AdminCommandProcessor admin, AlarmNode node,
                  CountingProcessor processor, InMemoryMessageSink sink) implements AutoCloseable {
        List<String> lines() {
            return sink.getMessages().stream().map(String::valueOf).toList();
        }

        void awaitLines(int n) throws InterruptedException {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (sink.getMessages().size() < n && System.nanoTime() < deadline) Thread.sleep(5);
            Thread.sleep(30);
        }

        List<Object> command(String name, String... args) {
            List<Object> replies = new CopyOnWriteArrayList<>();
            AdminCommandRequest request = new AdminCommandRequest();
            request.setCommand(name);
            request.setArguments(List.of(args));
            request.setOutput(replies::add);
            request.setErrOutput(o -> replies.add("ERR " + o));
            admin.processAdminCommandRequest(request);        // returns once the processor has run it
            return replies;
        }

        @Override
        public void close() {
            server.stop();
        }
    }

    static Server boot(ReplayConfig replay) throws Exception {
        AlarmNode node = new AlarmNode();
        CountingProcessor processor = new CountingProcessor(node);
        InMemoryEventSource<Object> events = new InMemoryEventSource<>();
        events.setName("events");
        AdminCommandProcessor admin = new AdminCommandProcessor();
        InMemoryMessageSink sink = new InMemoryMessageSink();
        MongooseServerConfig config = MongooseServerConfig.builder()
                .addProcessorGroup(EventProcessorGroupConfig.builder().agentName("processor-agent")
                        .put("alarms", EventProcessorConfig.builder().handler(processor).build()).build())
                .addEventFeed(EventFeedConfig.builder().instance(events).name("events").broadcast(true)
                        .agent("events-agent", new BusySpinIdleStrategy()).build())
                .addEventSink(EventSinkConfig.<MessageSink<?>>builder().instance(sink).name("memSink").build())
                .addService(new ServiceConfig<>(admin, AdminCommandRegistry.class, "adminService"))
                .replay(replay)
                .build();
        MongooseServer server = MongooseServer.bootServer(config, r -> { });
        Thread.sleep(200);
        return new Server(server, events, admin, node, processor, sink);
    }

    @Test
    void aSignalCommandRunsInTheProcessorsEventCycle() throws Exception {
        try (Server s = boot(null)) {
            s.events().offer("DEMO-alarm");
            s.awaitLines(1);
            int before = s.processor().cycles.get();
            List<Object> replies = s.command("alarm.reset", "DEMO-operator");
            assertEquals(List.of("alarm cleared"), replies, "the handler replied");
            assertEquals(before + 1, s.processor().cycles.get(), "the command opened exactly one event cycle");
            assertEquals(Boolean.TRUE, s.node().handledInCycle, "and its handler ran inside it");
            assertTrue(s.lines().get(1).startsWith("reset by=[DEMO-operator] raised=false"), s.lines().toString());
        }
    }

    @Test
    void aCommandNoHandlerAnswers_isAnsweredWithAnError() throws Exception {
        try (Server s = boot(null)) {
            List<Object> replies = s.command("alarm.ignored");
            assertEquals(1, replies.size(), replies.toString());
            assertTrue(replies.get(0).toString().startsWith("ERR admin command 'alarm.ignored' was delivered to its processor, and no handler replied"),
                    replies.toString());
        }
    }

    @Test
    void aSignalCommandIsRecorded_andReplayedAsTheSameCycle() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        List<String> live;
        try (Server s = boot(ReplayConfig.record(Set.of("alarms"), Map.of(), null, store))) {
            s.events().offer("DEMO-alarm");
            s.awaitLines(1);
            s.command("alarm.reset", "DEMO-operator");
            s.awaitLines(2);
            s.events().offer("DEMO-alarm-2");
            s.awaitLines(3);
            live = s.lines();
        }
        List<ReplayEntry> entries = store.entries("alarms");
        assertEquals(3, entries.size(), entries.toString());
        ReplayEntry.AdminInvoked admin = assertInstanceOf(ReplayEntry.AdminInvoked.class, entries.get(1));
        assertEquals("alarm.reset", admin.command());
        try (Server r = boot(ReplayConfig.replay(Set.of("alarms"), Map.of(), null, store))) {
            r.awaitLines(3);
            assertEquals(live, r.lines(), "the command replays as the same signal cycle, at the same point and instant");
            assertEquals(Boolean.TRUE, r.node().handledInCycle, "in a cycle in the replay too");
        }
    }
}
