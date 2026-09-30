package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.DefaultEventProcessor;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.audit.EventLogControlEvent;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.EventFeedConfig;
import com.telamin.mongoose.config.EventProcessorConfig;
import com.telamin.mongoose.config.EventProcessorGroupConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.ServiceConfig;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.admin.AdminCommandRequest;
import com.telamin.mongoose.service.admin.impl.AdminCommandProcessor;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Do processor-owned admin commands run in an event cycle? (Asked 2026-09-29; the proposal is #45.) Yes, on fluxtion
 * runtime 1.1.0: a lambda command runs through {@code DataFlow.runInEventCycle}, as its own cycle of the processor, not
 * through {@code onEvent} and so not as an input the graph dispatches. Before, it ran outside any cycle; this test then
 * characterised that, and is now inverted, as it said it would be. (Its audit record on a generated processor:
 * GeneratedAdminAuditTest. A signal-routed command, which propagates: SignalAdminCommandTest.)
 */
class AdminCommandAuditTest {

    /** The node: a command that changes state and audit-logs, and an event handler. */
    public static class AlarmNode extends ObjectEventHandlerNode {
        volatile boolean raised;
        volatile boolean inCycleDuringCommand = true;
        CountingProcessor processor;

        @ServiceRegistered
        public void admin(AdminCommandRegistry registry, String name) {
            registry.registerCommand("alarm.reset", (args, out, err) -> {
                raised = false;
                inCycleDuringCommand = processor.inCycle;
                auditLog.info("reset", true).info("resetVia", "admin");
                out.accept("alarm cleared");
            });
        }

        @Override
        public void start() {
            getContext().subscribeToNamedFeed("events");
        }

        @Override
        protected boolean handleEvent(Object event) {
            if (event instanceof String) raised = true;
            return true;
        }
    }

    /** A processor that counts its event cycles, and its host cycles, and knows when one is open. */
    public static class CountingProcessor extends DefaultEventProcessor {
        final java.util.concurrent.atomic.AtomicInteger cycles = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger hostCycles = new java.util.concurrent.atomic.AtomicInteger();
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

        @Override
        public void runInEventCycle(Object auditEvent, Runnable action) {
            hostCycles.incrementAndGet();
            boolean outer = inCycle;
            inCycle = true;
            try {
                super.runInEventCycle(auditEvent, action);
            } finally {
                inCycle = outer;
            }
        }
    }

    @Test
    void anAdminCommand_runsInItsOwnEventCycle_notAsAnInput() throws Exception {
        AlarmNode node = new AlarmNode();
        CountingProcessor processor = new CountingProcessor(node);
        InMemoryEventSource<Object> events = new InMemoryEventSource<>();
        events.setName("events");
        AdminCommandProcessor admin = new AdminCommandProcessor();
        MongooseServerConfig config = MongooseServerConfig.builder()
                .addProcessorGroup(EventProcessorGroupConfig.builder().agentName("processor-agent")
                        .put("alarms", EventProcessorConfig.builder().handler(processor)
                                .logLevel(EventLogControlEvent.LogLevel.INFO).build()).build())
                .addEventFeed(EventFeedConfig.builder().instance(events).name("events").broadcast(true)
                        .agent("events-agent", new BusySpinIdleStrategy()).build())
                .addService(new ServiceConfig<>(admin, AdminCommandRegistry.class, "adminService"))
                .build();
        MongooseServer server = MongooseServer.bootServer(config, r -> { });
        try {
            Thread.sleep(200);
            events.offer("DEMO-alarm");
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!node.raised && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue(node.raised, "the event raised the alarm");
            int before = processor.cycles.get();
            int hostBefore = processor.hostCycles.get();
            List<Object> replies = new CopyOnWriteArrayList<>();
            AdminCommandRequest request = new AdminCommandRequest();
            request.setCommand("alarm.reset");
            request.setArguments(List.of());
            request.setOutput(replies::add);
            request.setErrOutput(replies::add);
            admin.processAdminCommandRequest(request);        // returns once the processor has run it
            assertEquals(List.of("alarm cleared"), replies, "the command ran");
            assertFalse(node.raised, "and changed the node's state");
            assertEquals(hostBefore + 1, processor.hostCycles.get(), "the command opens one cycle of its own");
            assertEquals(before, processor.cycles.get(), "not through onEvent: it is not an input the graph dispatches");
            assertTrue(node.inCycleDuringCommand, "and the command's code runs inside that cycle");
        } finally {
            server.stop();
        }
    }
}
