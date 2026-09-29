package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.DefaultEventProcessor;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.EventProcessorConfig;
import com.telamin.mongoose.config.EventProcessorGroupConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.ServiceConfig;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.admin.AdminCommandRequest;
import com.telamin.mongoose.service.admin.impl.AdminCommandProcessor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A lambda admin command run through DataFlow.runInEventCycle (Fluxtion spike/run-in-event-cycle): an event the command
 * redispatches is queued and handled AFTER the command, as its own cycle, not inside it. Needs a runtime with the
 * method: skipped on one without it (Mongoose's own 1.0.15), where the invoker falls back to the audit bracket.
 * Run locally: mvn test -Dtest=RunInEventCycleAdminTest -Dfluxtion.version=1.0.17-SNAPSHOT
 */
class RunInEventCycleAdminTest {

    public static class RedispatchingNode extends ObjectEventHandlerNode {
        final List<String> order = new CopyOnWriteArrayList<>();

        @ServiceRegistered
        public void admin(AdminCommandRegistry registry, String name) {
            registry.registerCommand("quotes.refresh", (args, out, err) -> {
                order.add("command begins");
                getContext().getParentDataFlow().onEvent("DEMO-refresh");   // the command redispatches
                order.add("command ends");
                out.accept("refreshed");
            });
        }

        @Override
        protected boolean handleEvent(Object event) {
            if (event instanceof String s) order.add("handled " + s);
            return true;
        }
    }

    @Test
    void aRedispatchedEventRunsAfterTheCommand_asItsOwnCycle() throws Exception {
        boolean available;
        try {
            DataFlow.class.getMethod("runInEventCycle", Object.class, Runnable.class);
            available = true;
        } catch (NoSuchMethodException e) {
            available = false;
        }
        Assumptions.assumeTrue(available, "needs a Fluxtion runtime with DataFlow.runInEventCycle");

        RedispatchingNode node = new RedispatchingNode();
        AdminCommandProcessor admin = new AdminCommandProcessor();
        MongooseServerConfig config = MongooseServerConfig.builder()
                .addProcessorGroup(EventProcessorGroupConfig.builder().agentName("processor-agent")
                        .put("quotes", EventProcessorConfig.builder().handler(new DefaultEventProcessor(node)).build()).build())
                .addService(new ServiceConfig<>(admin, AdminCommandRegistry.class, "adminService"))
                .build();
        MongooseServer server = MongooseServer.bootServer(config, r -> { });
        try {
            Thread.sleep(200);
            List<Object> replies = new CopyOnWriteArrayList<>();
            AdminCommandRequest request = new AdminCommandRequest();
            request.setCommand("quotes.refresh");
            request.setArguments(List.of());
            request.setOutput(replies::add);
            request.setErrOutput(o -> replies.add("ERR " + o));
            admin.processAdminCommandRequest(request);
            assertEquals(List.of("refreshed"), replies);
            // the caller is released when the command itself returns; the event it queued runs straight after, on the
            // processor's thread, as its own cycle
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (node.order.size() < 3 && System.nanoTime() < deadline) Thread.sleep(5);
            assertEquals(List.of("command begins", "command ends", "handled DEMO-refresh"), node.order,
                    "the redispatched event is queued and dispatched after the command, not inside it");
        } finally {
            server.stop();
        }
    }
}
