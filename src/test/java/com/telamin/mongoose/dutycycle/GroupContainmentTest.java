package com.telamin.mongoose.dutycycle;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.MongooseServerConfig.FatalErrorPolicy;
import com.telamin.mongoose.control.AgentThreads;
import com.telamin.mongoose.control.ControlAuditSink;
import com.telamin.mongoose.control.ControlDriver;
import com.telamin.mongoose.control.event.RunIntent;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import com.telamin.mongoose.internal.ComposingEventProcessorAgentRunner;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventFlowService;
import com.telamin.mongoose.service.EventSourceKey;
import com.telamin.mongoose.service.EventSubscriptionKey;
import com.telamin.mongoose.service.health.HealthStatus;
import com.telamin.mongoose.service.health.MongooseHealthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #77 review R5 and A1 (owner confirmed): a dead control plane is DOWN and its processors receive nothing more,
 * while other groups keep serving; a fatal error in a group never exits the process unless the deployment opts in.
 */
@Timeout(30)
class GroupContainmentTest {

    @AfterEach
    void clear() {
        AgentThreads.exit();
    }

    static final class Feed implements EventFlowService<RunIntent> {
        volatile EventToQueuePublisher<RunIntent> publisher;
        public void setEventToQueuePublisher(EventToQueuePublisher<RunIntent> p) { publisher = p; }
        public void subscribe(EventSubscriptionKey<RunIntent> key) { }
        public void unSubscribe(EventSubscriptionKey<RunIntent> key) { }
    }

    static final class Counting implements DataFlow, Lifecycle {
        final AtomicInteger events = new AtomicInteger();
        EventFeed feed;
        public void init() { }
        public void onEvent(Object event) { if (event instanceof RunIntent) events.incrementAndGet(); }
        public void addEventFeed(EventFeed f) { feed = f; }
        public void start() {
            feed.subscribe(this, new EventSubscriptionKey<>(new EventSourceKey<>("feed"), CallBackType.ON_EVENT_CALL_BACK));
        }
        public void stop() { }
        public void tearDown() { }
    }

    private static void await(BooleanSupplier ready, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(ready.getAsBoolean(), what);
    }

    @SuppressWarnings("unchecked")
    private static ComposingEventProcessorAgent group(MongooseServer server, String name) throws Exception {
        var field = MongooseServer.class.getDeclaredField("composingEventProcessorAgents");
        field.setAccessible(true);
        return ((Map<String, ComposingEventProcessorAgentRunner>) field.get(server)).get(name).group();
    }

    private static HealthStatus.Verdict awaitControlVerdict(MongooseHealthService health, String group,
                                                            HealthStatus.Verdict wanted) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        HealthStatus.Verdict now;
        do {
            now = health.statusOfCheck("group:" + group, "control").verdict();
            if (now == wanted) return now;
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        return now;
    }

    @Test
    void aDeadControlPlaneIsDownAndFencedWhileOtherGroupsKeepServing() throws Exception {
        MongooseServer server = new MongooseServer(new MongooseServerConfig());
        Feed feed = new Feed();
        Counting dying = new Counting();
        Counting healthy = new Counting();
        server.registerEventSource("feed", feed);
        server.addEventProcessor("dying", "g1", null, () -> dying);
        server.addEventProcessor("healthy", "g2", null, () -> healthy);
        server.init();
        server.start();
        try {
            await(() -> feed.publisher != null && feed.publisher.getTargetQueues().size() == 2, "both subscribed");
            feed.publisher.publish(new RunIntent(1));
            await(() -> dying.events.get() == 1 && healthy.events.get() == 1, "control: both groups receive");
            MongooseHealthService health = (MongooseHealthService) server.registeredServices()
                    .get(MongooseHealthService.SERVICE_NAME).instance();
            assertEquals(HealthStatus.Verdict.UP, awaitControlVerdict(health, "g1", HealthStatus.Verdict.UP));

            // the control processor's audit listener throws once: the next rule cycle fails (FAILED_RULE)
            ComposingEventProcessorAgent g1 = group(server, "g1");
            var processorField = ControlDriver.class.getDeclaredField("processor");
            processorField.setAccessible(true);
            Object processor = processorField.get(g1.control());
            ControlAuditSink sink = g1.control().audit();
            AtomicInteger throwNext = new AtomicInteger(1);
            LogRecordListener failing = rec -> {
                if (throwNext.getAndSet(0) == 1) throw new IllegalStateException("injected control listener failure");
                sink.processLogRecord(rec);
            };
            g1.runOnAgentThread(() -> {
                try {
                    processor.getClass().getMethod("setAuditLogProcessor", LogRecordListener.class).invoke(processor, failing);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            });
            throwNext.set(1);
            var reply = server.stopProcessor("g1", "nobody-yet", 1_000);
            await(() -> g1.control().state() == ControlDriver.GroupState.DEAD, "the rule failure kills g1's control plane");
            assertEquals(HealthStatus.Verdict.DOWN, awaitControlVerdict(health, "g1", HealthStatus.Verdict.DOWN));
            assertEquals(HealthStatus.Verdict.UP, awaitControlVerdict(health, "g2", HealthStatus.Verdict.UP));
            await(() -> sink.records().stream().anyMatch(r -> r.contains("record: DataPathFenced")), "g1 fenced");

            for (int i = 0; i < 5; i++) feed.publisher.publish(new RunIntent(10 + i));
            await(() -> healthy.events.get() == 6, "g2 keeps serving");
            Thread.sleep(100);
            assertEquals(1, dying.events.get(), "a dead control plane's processors receive nothing more");
            assertTrue(g1.isProcessorRegistered("dying"), "owned, not released or restarted");
        } finally {
            server.stop();
        }
    }

    @Test
    void aFatalGroupErrorKillsThatGroupOnlyAndNeverExitsByDefault() throws Exception {
        AtomicInteger exits = new AtomicInteger();
        MongooseServer server = new MongooseServer(new MongooseServerConfig());
        server.setDefaultErrorHandler(new GlobalErrorHandler(FatalErrorPolicy.CONTAIN, status -> exits.incrementAndGet()));
        Feed feed = new Feed();
        Counting dying = new Counting();
        Counting healthy = new Counting();
        server.registerEventSource("feed", feed);
        server.addEventProcessor("dying", "g1", null, () -> dying);
        server.addEventProcessor("healthy", "g2", null, () -> healthy);
        server.init();
        server.start();
        try {
            await(() -> feed.publisher != null && feed.publisher.getTargetQueues().size() == 2, "both subscribed");
            ComposingEventProcessorAgent g1 = group(server, "g1");
            g1.runOnAgentThread(() -> { throw new StackOverflowError("injected fatal error"); });
            await(() -> g1.control().state() == ControlDriver.GroupState.DEAD, "the fatal error kills g1");
            MongooseHealthService health = (MongooseHealthService) server.registeredServices()
                    .get(MongooseHealthService.SERVICE_NAME).instance();
            assertEquals(HealthStatus.Verdict.DOWN, awaitControlVerdict(health, "g1", HealthStatus.Verdict.DOWN));
            feed.publisher.publish(new RunIntent(1));
            await(() -> healthy.events.get() == 1, "g2 keeps serving");
            assertEquals(0, exits.get(), "CONTAIN never exits the process");
        } finally {
            server.stop();
        }
    }

    @Test
    void exitIsAnExplicitOptIn() {
        AtomicInteger status = new AtomicInteger(Integer.MIN_VALUE);
        new GlobalErrorHandler(FatalErrorPolicy.EXIT_PROCESS, status::set).onError(new IllegalStateException("x"));
        assertEquals(-1, status.get(), "EXIT_PROCESS exits with -1");
        AtomicInteger contained = new AtomicInteger(Integer.MIN_VALUE);
        new GlobalErrorHandler(FatalErrorPolicy.CONTAIN, contained::set).onError(new IllegalStateException("x"));
        assertEquals(Integer.MIN_VALUE, contained.get(), "CONTAIN does not");
        assertEquals(FatalErrorPolicy.CONTAIN, new GlobalErrorHandler().policy(), "the default contains");
        assertEquals(FatalErrorPolicy.CONTAIN, new MongooseServerConfig().getFatalErrorPolicy(), "the config default contains");
    }
}
