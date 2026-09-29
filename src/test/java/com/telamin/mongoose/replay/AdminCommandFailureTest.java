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
            registry.registerSignalCommand("p.sig");
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
        @SuppressWarnings("unchecked")
        protected boolean handleEvent(Object event) {
            if ("boom".equals(event)) {
                throw new IllegalStateException("DEMO failure on " + event);
            }
            if (event instanceof com.telamin.fluxtion.runtime.event.Signal<?> signal
                    && "admin:p.sig".equals(signal.filterString())) {
                ((AdminCommandRequest) signal.getValue()).getOutput().accept("sig ok");
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
        return boot(false);
    }

    /** {@code refusingCycle}: a processor whose own runInEventCycle refuses, as one that disabled the path does. */
    static Server boot(boolean refusingCycle) throws Exception {
        InMemoryEventSource<Object> feed = new InMemoryEventSource<>();
        feed.setName(FEED);
        FragileNode node = new FragileNode();
        DefaultEventProcessor processor = refusingCycle
                ? new DefaultEventProcessor(node) {
                    @Override
                    public void runInEventCycle(Object auditEvent, Runnable action) {
                        throw new UnsupportedOperationException("DEMO: this processor disabled runInEventCycle");
                    }
                }
                : new DefaultEventProcessor(node);
        AdminCommandProcessor admin = new AdminCommandProcessor();
        MongooseServerConfig config = MongooseServerConfig.builder()
                .addProcessorGroup(EventProcessorGroupConfig.builder().agentName("processor-agent")
                        .put("fragile", EventProcessorConfig.builder().handler(processor).build()).build())
                .addEventFeed(EventFeedConfig.builder().instance(feed).name(FEED).broadcast(true)
                        .agent("feed-agent", new BusySpinIdleStrategy()).build())
                .addService(new ServiceConfig<>(admin, AdminCommandRegistry.class, "adminService"))
                .build();
        MongooseServer server = MongooseServer.bootServer(config, r -> { });
        Thread.sleep(200);                                  // the processor subscribes in start()
        return new Server(server, feed, admin, node);
    }

    static List<Object> invoke(AdminCommandProcessor admin, String command) throws Exception {
        return invoke(admin, command, List.of());
    }

    static List<Object> invoke(AdminCommandProcessor admin, String command, List<String> arguments) throws Exception {
        List<Object> replies = new CopyOnWriteArrayList<>();
        AdminCommandRequest request = new AdminCommandRequest();
        request.setCommand(command);
        request.setArguments(arguments);
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

    /** A null argument fails building the command's event or request, before it runs: the caller is still answered. */
    @Test
    void aCommandThatFailsBeforeItRuns_onEitherPath_answersTheCaller() throws Exception {
        try (Server s = boot()) {
            List<Object> lambda = invoke(s.admin(), "p.plain", java.util.Arrays.asList("DEMO", null));
            assertEquals(1, lambda.size(), "the lambda path answers once: " + lambda);
            assertTrue(String.valueOf(lambda.get(0)).startsWith("ERR "), lambda.toString());
            assertEquals(0, s.node().plainRuns.get(), "and did not run the command");
            List<Object> signal = invoke(s.admin(), "p.sig", java.util.Arrays.asList("DEMO", null));
            assertEquals(1, signal.size(), "the signal path answers once: " + signal);
            assertTrue(String.valueOf(signal.get(0)).startsWith("ERR "), signal.toString());
        }
    }

    /** A processor that cannot run commands is what an operator must see, not only whoever typed the command. */
    @Test
    void aRefusedCommand_isReportedToOperations() throws Exception {
        List<com.telamin.mongoose.service.error.ErrorEvent> reported = new CopyOnWriteArrayList<>();
        com.telamin.mongoose.service.error.ErrorListener listener = reported::add;
        com.telamin.mongoose.service.error.ErrorReporting.getReporter().addListener(listener);
        try (Server s = boot()) {
            s.feed().offer("boom");                         // left mid-cycle: it refuses the cycle
            Thread.sleep(300);
            invoke(s.admin(), "p.plain");
            assertTrue(reported.stream().anyMatch(e -> e.getSeverity() == com.telamin.mongoose.service.error.ErrorEvent.Severity.WARNING
                            && e.getMessage().contains("p.plain") && e.getMessage().contains("did not run")),
                    "reported as a WARNING naming the command: " + reported.stream().map(e -> e.getSeverity() + " " + e.getMessage()).toList());
        } finally {
            com.telamin.mongoose.service.error.ErrorReporting.getReporter().removeListener(listener);
        }
    }

    /**
     * A processor whose own runInEventCycle refuses (it disabled the path) is not wedged and not old: its command is run
     * through the audit bracket, as a processor generated before 1.1.0 is, and is never refused to the caller.
     */
    @Test
    void aProcessorWhoseOverrideRefusesTheCycle_runsTheCommandBracketed() throws Exception {
        try (Server s = boot(true)) {
            assertEquals(List.of("plain ok"), invoke(s.admin(), "p.plain"));
            assertEquals(List.of("plain ok"), invoke(s.admin(), "p.plain"), "every time: the class is not cached as refusing");
            assertEquals(2, s.node().plainRuns.get());
        }
    }
}
