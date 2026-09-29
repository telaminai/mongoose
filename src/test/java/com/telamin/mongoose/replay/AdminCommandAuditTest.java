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
 * Do processor-owned admin commands run in an event cycle, and audit properly? (Asked 2026-09-29; the proposal is
 * origin/proposal/admin-commands-in-event-cycle.) Not yet. This CHARACTERISES the current behaviour: the command runs on
 * the processor's thread through {@code AdminCommandInvoker}, never through {@code onEvent}, so no event cycle
 * opens for it: no dirty flags, nothing downstream triggered. (In a generated processor the invoker now brackets the
 * lambda with the processor's audit record, so it is audited: GeneratedAdminAuditTest. A signal-routed command runs in
 * a full cycle: SignalAdminCommandTest.)
 * A hand-written {@code DefaultEventProcessor} has no {@code EventLogManager} and writes no audit log, and Mongoose's
 * tests have no generated processor, so the audit half is shown there, not here; what is shown here is the cause.
 * When admin commands are delivered in an event cycle (the proposal's option A, or B), invert the marked assertion.
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

    /** A processor that counts its event cycles and knows when one is open. */
    public static class CountingProcessor extends DefaultEventProcessor {
        final java.util.concurrent.atomic.AtomicInteger cycles = new java.util.concurrent.atomic.AtomicInteger();
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

    @Test
    void anAdminCommandToday_runsOutsideAnEventCycle() throws Exception {
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
            List<Object> replies = new CopyOnWriteArrayList<>();
            AdminCommandRequest request = new AdminCommandRequest();
            request.setCommand("alarm.reset");
            request.setArguments(List.of());
            request.setOutput(replies::add);
            request.setErrOutput(replies::add);
            admin.processAdminCommandRequest(request);        // returns once the processor has run it
            assertEquals(List.of("alarm cleared"), replies, "the command ran");
            assertFalse(node.raised, "and changed the node's state");
            // INVERT when admin commands run in an event cycle: then the command opens one, and runs inside it
            assertEquals(before, processor.cycles.get(), "today the command opens no event cycle");
            assertFalse(node.inCycleDuringCommand, "today the command's code runs outside any event cycle");
        } finally {
            server.stop();
        }
    }
}
