package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.DefaultEventProcessor;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A lambda admin command when something fails around it, on the runInEventCycle path. Neither case may be worse than
 * before that path existed: a caller is always answered and released, and a command runs exactly once.
 * <ul>
 *   <li>A processor left mid-cycle by a node that threw refuses the cycle before the command runs
 *   (IllegalStateException, not re-entrant): the caller is answered with an error, not left waiting forever.</li>
 *   <li>An event the command raised throws while the cycle drains: the agent reports the failure and retries the
 *   dispatch, and the retry must not run the command again.</li>
 * </ul>
 */
class AdminCommandFailureTest {

    static final String FEED = "orders";

    public static class FragileNode extends ObjectEventHandlerNode {
        final AtomicInteger plainRuns = new AtomicInteger();
        final AtomicInteger raiseRuns = new AtomicInteger();

        @ServiceRegistered
        public void admin(AdminCommandRegistry registry, String name) {
            registry.registerCommand("p.plain", (args, out, err) -> {
                plainRuns.incrementAndGet();
                out.accept("plain ok");
            });
            registry.registerCommand("p.raise", (args, out, err) -> {
                raiseRuns.incrementAndGet();
                getContext().getParentDataFlow().onEvent("boom");     // queued; throws when the cycle drains it
                out.accept("raised");
            });
        }

        @Override
        public void start() {
            getContext().subscribeToNamedFeed(FEED);
        }

        @Override
        protected boolean handleEvent(Object event) {
            if ("boom".equals(event)) {
                throw new IllegalStateException("DEMO failure on " + event);
            }
            return true;
        }
    }

    record Server(MongooseServer server, InMemoryEventSource<Object> feed, AdminCommandProcessor admin, FragileNode node)
            implements AutoCloseable {
        @Override
        public void close() {
            server.stop();
        }
    }

    static Server boot() throws Exception {
        InMemoryEventSource<Object> feed = new InMemoryEventSource<>();
        feed.setName(FEED);
        FragileNode node = new FragileNode();
        AdminCommandProcessor admin = new AdminCommandProcessor();
        MongooseServerConfig config = MongooseServerConfig.builder()
                .addProcessorGroup(EventProcessorGroupConfig.builder().agentName("processor-agent")
                        .put("fragile", EventProcessorConfig.builder().handler(new DefaultEventProcessor(node)).build()).build())
                .addEventFeed(EventFeedConfig.builder().instance(feed).name(FEED).broadcast(true)
                        .agent("feed-agent", new BusySpinIdleStrategy()).build())
                .addService(new ServiceConfig<>(admin, AdminCommandRegistry.class, "adminService"))
                .build();
        MongooseServer server = MongooseServer.bootServer(config, r -> { });
        Thread.sleep(200);                                  // the processor subscribes in start()
        return new Server(server, feed, admin, node);
    }

    static List<Object> invoke(AdminCommandProcessor admin, String command) throws Exception {
        List<Object> replies = new CopyOnWriteArrayList<>();
        AdminCommandRequest request = new AdminCommandRequest();
        request.setCommand(command);
        request.setArguments(List.of());
        request.setOutput(replies::add);
        request.setErrOutput(o -> replies.add("ERR " + o));
        CompletableFuture.runAsync(() -> admin.processAdminCommandRequest(request)).get(5, TimeUnit.SECONDS);
        return replies;
    }

    @Test
    void aProcessorThatCannotRunTheCycle_answersTheCaller_andTheCommandDoesNotRun() throws Exception {
        try (Server s = boot()) {
            s.feed().offer("boom");                         // a node throws: the processor is left mid-cycle
            Thread.sleep(300);
            List<Object> replies = invoke(s.admin(), "p.plain");     // must return, not block forever
            assertEquals(1, replies.size(), "one answer: " + replies);
            assertTrue(String.valueOf(replies.get(0)).startsWith("ERR "), "an error, naming why: " + replies);
            assertEquals(0, s.node().plainRuns.get(), "the command did not run");
        }
    }

    @Test
    void aRaisedEventThatThrows_doesNotRunTheCommandAgain() throws Exception {
        try (Server s = boot()) {
            List<Object> replies = invoke(s.admin(), "p.raise");
            Thread.sleep(500);                              // room for the agent's retries (5 ms, 10 ms backoff)
            assertEquals(1, s.node().raiseRuns.get(), "the command ran once, whatever the retry policy does");
            assertEquals(List.of("raised"), replies, "and answered once");
        }
    }
}
