package com.telamin.mongoose.review77c;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.StopResult;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.MongooseServerConfig.FatalErrorPolicy;
import com.telamin.mongoose.control.AgentThreads;
import com.telamin.mongoose.control.ControlAuditSink;
import com.telamin.mongoose.control.ControlDriver;
import com.telamin.mongoose.control.ControlReply;
import com.telamin.mongoose.control.event.FailureKind;
import com.telamin.mongoose.control.event.RunIntent;
import com.telamin.mongoose.control.generated.GroupControlProcessor;
import com.telamin.mongoose.dispatch.EventToOnEventInvokeStrategy;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import com.telamin.mongoose.dispatch.ProcessorContext;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.dutycycle.GlobalErrorHandler;
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
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review of issue #77 round 3 (df11115): reviewer probes, adopted as permanent tests. The two that confirmed defects
 * (the failed boot under CONTAIN, the dead service agent) are inverted to the fixed behaviour; the held-names probe
 * now asserts its observation, and a companion test reaches the dead-group branch.
 */
@Timeout(60)
class Review77cProbesTest {

    @AfterEach
    void clear() {
        AgentThreads.exit();
        ProcessorContext.removeCurrentProcessor();
    }

    static EventSubscriptionKey<Object> key(String feed) {
        return new EventSubscriptionKey<>(new EventSourceKey<>(feed), CallBackType.ON_EVENT_CALL_BACK);
    }

    static final class Feed implements EventFlowService<RunIntent> {
        volatile EventToQueuePublisher<RunIntent> publisher;
        public void setEventToQueuePublisher(EventToQueuePublisher<RunIntent> p) { publisher = p; }
        public void subscribe(EventSubscriptionKey<RunIntent> key) { }
        public void unSubscribe(EventSubscriptionKey<RunIntent> key) { }
    }

    static class Counting implements DataFlow, Lifecycle {
        final AtomicInteger events = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();
        final AtomicInteger tearDowns = new AtomicInteger();
        EventFeed feed;
        public void init() { }
        public void onEvent(Object event) { if (event instanceof RunIntent) events.incrementAndGet(); }
        public void addEventFeed(EventFeed f) { feed = f; }
        public void start() { feed.subscribe(this, key("feed")); }
        public void stop() { stops.incrementAndGet(); }
        public void tearDown() { tearDowns.incrementAndGet(); }
    }

    static final class BlockingStop extends Counting {
        final CountDownLatch release = new CountDownLatch(1);
        @Override public void stop() {
            super.stop();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private static void await(BooleanSupplier ready, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(ready.getAsBoolean(), what);
    }

    @SuppressWarnings("unchecked")
    private static ComposingEventProcessorAgent group(MongooseServer server, String name) throws Exception {
        var field = MongooseServer.class.getDeclaredField("composingEventProcessorAgents");
        field.setAccessible(true);
        return ((Map<String, ComposingEventProcessorAgentRunner>) field.get(server)).get(name).group();
    }

    private static HealthStatus.Verdict verdict(MongooseServer server, String group) {
        MongooseHealthService health = (MongooseHealthService) server.registeredServices()
                .get(MongooseHealthService.SERVICE_NAME).instance();
        return health.statusOfCheck("group:" + group, "control").verdict();
    }

    private static HealthStatus.Verdict awaitVerdict(MongooseServer server, String group, HealthStatus.Verdict wanted)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        HealthStatus.Verdict now;
        do {
            now = verdict(server, group);
            if (now == wanted) return now;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        return now;
    }

    /** As GroupContainmentTest: the control processor's audit listener throws once, so the next rule cycle fails. */
    private static ControlAuditSink killControlPlane(MongooseServer server, String groupName) throws Exception {
        ComposingEventProcessorAgent g = group(server, groupName);
        var processorField = ControlDriver.class.getDeclaredField("processor");
        processorField.setAccessible(true);
        Object processor = processorField.get(g.control());
        ControlAuditSink sink = g.control().audit();
        AtomicInteger throwNext = new AtomicInteger(0);
        LogRecordListener failing = rec -> {
            if (throwNext.getAndSet(0) == 1) throw new IllegalStateException("injected control listener failure");
            sink.processLogRecord(rec);
        };
        g.runOnAgentThread(() -> {
            try {
                processor.getClass().getMethod("setAuditLogProcessor", LogRecordListener.class).invoke(processor, failing);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        throwNext.set(1);
        server.stopProcessor(groupName, "nobody", 1_000);
        await(() -> g.control().state() == ControlDriver.GroupState.DEAD, "control plane dead");
        await(() -> sink.records().stream().anyMatch(r -> r.contains("record: DataPathFenced")), "fenced");
        return sink;
    }

    // ---------------------------------------------------------------- R5: fence vs. shared feed, and shutdown

    /** A fenced group stops being a target of the shared feed, so a large burst cannot back up into the healthy group. */
    @Test
    void fencedGroupLeavesTheSharedFeedAndABurstStillReachesTheHealthyGroup() throws Exception {
        var config = new MongooseServerConfig();
        config.setShutdownTimeoutMillis(2_000);
        MongooseServer server = new MongooseServer(config);
        Feed feed = new Feed();
        Counting dying = new Counting();
        Counting healthy = new Counting();
        server.registerEventSource("feed", feed);
        server.addEventProcessor("dying", "g1", null, () -> dying);
        server.addEventProcessor("healthy", "g2", null, () -> healthy);
        server.init();
        server.start();
        StopResult result;
        try {
            await(() -> feed.publisher != null && feed.publisher.getTargetQueues().size() == 2, "both subscribed");
            killControlPlane(server, "g1");
            await(() -> feed.publisher.getTargetQueues().size() == 1, "the fenced group's queue left the feed: "
                    + feed.publisher.getTargetQueues().size());
            int burst = 200_000;
            for (int i = 0; i < burst; i++) feed.publisher.publish(new RunIntent(i));
            await(() -> healthy.events.get() == burst, "healthy got " + healthy.events.get());
            assertEquals(0, dying.events.get());
            assertEquals(HealthStatus.Verdict.DOWN, verdict(server, "g1"));
            assertEquals(HealthStatus.Verdict.UP, verdict(server, "g2"));
            // other delivery paths into a dead group: runOnAgentThread from another thread is refused, never run
            AtomicInteger ran = new AtomicInteger();
            Throwable refused = null;
            try {
                group(server, "g1").runOnAgentThread(ran::incrementAndGet);
            } catch (IllegalStateException e) {
                refused = e;
            }
            Thread.sleep(100);
            System.out.println("REVIEW77C runOnAgentThread on dead group: ran=" + ran.get() + " refused=" + refused);
            assertEquals(0, ran.get(), "nothing runs on a dead control plane's group");
        } finally {
            result = server.shutdown();
        }
        System.out.println("REVIEW77C fenced shutdown: " + result + " dying stops=" + dying.stops.get()
                + " tearDowns=" + dying.tearDowns.get());
    }

    // ---------------------------------------------------------------- M4: heldNames, dead-group branch

    /**
     * The dead-group branch of StopResult.heldNames: a rule-failed (fenced, thread alive) group plus a group whose stop
     * blocks past the shutdown deadline, so the stop is incomplete and held names are computed.
     */
    @Test
    void heldNamesDeadGroupBranch() throws Exception {
        var config = new MongooseServerConfig();
        config.setShutdownTimeoutMillis(500);
        MongooseServer server = new MongooseServer(config);
        Feed feed = new Feed();
        Counting dying = new Counting();
        BlockingStop slow = new BlockingStop();
        server.registerEventSource("feed", feed);
        server.addEventProcessor("dying", "g1", null, () -> dying);
        server.addEventProcessor("slow", "g3", null, () -> slow);
        server.init();
        server.start();
        StopResult result;
        try {
            await(() -> feed.publisher != null && feed.publisher.getTargetQueues().size() == 2, "both subscribed");
            killControlPlane(server, "g1");
            result = server.shutdown();
            System.out.println("REVIEW77C heldNames incomplete stop: " + result + " dying stops=" + dying.stops.get()
                    + " tearDowns=" + dying.tearDowns.get());
        } finally {
            slow.release.countDown();
        }
        assertFalse(result.complete(), "the blocking stop makes the result incomplete: " + result);
        StopResult again = server.shutdown();
        System.out.println("REVIEW77C heldNames resumed stop: " + again);
        // the dead group's own close released its names, so nothing is held for it
        System.out.println("REVIEW77C heldNames=" + result.heldNames());
        assertTrue(result.heldNames().isEmpty(), "a dead group whose close completed holds no names: " + result);
    }

    /**
     * Round 4: the dead-group branch IS reachable when the dead group's own close cannot finish: its processor's stop
     * blocks past the deadline, so its name is still reserved and reported as held by a dead group.
     */
    @Test
    void heldNamesListsADeadGroupWhoseCloseIsBlocked() throws Exception {
        var config = new MongooseServerConfig();
        config.setShutdownTimeoutMillis(500);
        MongooseServer server = new MongooseServer(config);
        Feed feed = new Feed();
        BlockingStop slow = new BlockingStop();
        server.registerEventSource("feed", feed);
        server.addEventProcessor("slow", "g1", null, () -> slow);
        server.init();
        server.start();
        StopResult result;
        try {
            await(() -> feed.publisher != null && feed.publisher.getTargetQueues().size() == 1, "subscribed");
            killControlPlane(server, "g1");
            result = server.shutdown();
        } finally {
            slow.release.countDown();
        }
        assertFalse(result.complete(), result.toString());
        assertEquals(List.of("g1/slow: group dead"), result.heldNames().stream()
                .map(h -> h.group() + "/" + h.name() + ": " + h.reason()).toList(), result.toString());
        await(() -> server.shutdown().complete(), "the resumed stop completes once the stop returns");
    }

    // ---------------------------------------------------------------- A1: opaque dispatch under the default handler

    static class HealthyGenerated extends GroupControlProcessor {
        EventFeed feed;
        HealthyGenerated() { init(); }
        @Override public void addEventFeed(EventFeed feed) { super.addEventFeed(feed); this.feed = feed; }
        @Override public void startComplete() { super.startComplete(); feed.subscribe(this, key("feed")); }
    }

    static class Plain implements DataFlow {
        public void init() { }
        public void onEvent(Object event) { }
    }

    static class Throwing implements DataFlow {
        EventFeed feed;
        public void init() { }
        public void onEvent(Object event) {
            if (event instanceof RunIntent) throw new IllegalStateException("handwritten failure");
        }
        public void addEventFeed(EventFeed f) { feed = f; }
        public void start() { feed.subscribe(this, key("feed")); }
    }

    /** rc1 N2 path 1 (opaque dispatch) under bootServer's handler, default CONTAIN: no exit, DEAD + DOWN, g2 serves. */
    @Test
    void opaqueDispatchUnderTheDefaultHandlerIsContained() throws Exception {
        AtomicInteger exits = new AtomicInteger();
        var config = new MongooseServerConfig();
        config.setShutdownTimeoutMillis(1_000);
        MongooseServer server = new MongooseServer(config);
        server.setDefaultErrorHandler(new GlobalErrorHandler(config.getFatalErrorPolicy(), s -> exits.incrementAndGet()));
        server.registerEventMapperFactory(() -> new EventToOnEventInvokeStrategy() {
            @Override public int failedPosition() { return -1; }
            @Override public DataFlow failedProcessor() { return null; }
        }, CallBackType.ON_EVENT_CALL_BACK);
        Feed feed = new Feed();
        server.registerEventSource("feed", feed);
        server.addEventProcessor("throwing", "g", null, Throwing::new);
        server.addEventProcessor("generated", "g", null, HealthyGenerated::new);
        Counting healthy = new Counting();
        server.addEventProcessor("healthy", "g2", null, () -> healthy);
        server.init();
        server.start();
        try {
            await(() -> feed.publisher != null && feed.publisher.getTargetQueues().size() == 2, "subscribed");
            Thread.sleep(200);
            feed.publisher.publish(new RunIntent(1));
            ComposingEventProcessorAgent gAgent = group(server, "g");
            // superseded by rc1 review N1 (2fa536c): the handwritten thrower is identified at throw time and retried,
            // so the group neither dies nor quarantines its healthy generated peer
            Thread.sleep(300);
            assertEquals(ControlDriver.GroupState.RUNNING, gAgent.control().state(), "g keeps running");
            assertTrue(gAgent.quarantinedProcessors().isEmpty(), gAgent.quarantinedProcessors().toString());
            assertEquals(HealthStatus.Verdict.UP, awaitVerdict(server, "g", HealthStatus.Verdict.UP));
            feed.publisher.publish(new RunIntent(2));
            await(() -> healthy.events.get() == 2, "g2 keeps serving: " + healthy.events.get());
            assertEquals(0, exits.get(), "CONTAIN never exits");
        } finally {
            server.stop();
        }
    }

    // ---------------------------------------------------------------- A1: boot failure under CONTAIN

    /**
     * A failed bootServer under the default CONTAIN, in a child JVM whose main catches the failure and returns
     * normally (no System.exit). Does the JVM end, or do the already-started agent threads keep it alive?
     */
    @Test
    void failedBootUnderContainLeavesTheProcessRunningWhenMainReturns(@TempDir Path dir) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(a -> a.startsWith("--add-opens")).toList());
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), ReturningBootMain.class.getName()));
        Path log = dir.resolve("boot.log");
        Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            boolean ended = child.waitFor(8, TimeUnit.SECONDS);
            String out = Files.readString(log);
            System.out.println("REVIEW77C child ended=" + ended + "\n" + out.lines()
                    .filter(l -> l.startsWith("REVIEW77C") || l.contains("caller caught")).reduce("", (a, b) -> a + b + "\n"));
            assertTrue(out.contains("caller caught boot failure"), out);
            // inverted to the fixed behaviour (review round 4): bootServer stops what it started, then throws
            assertTrue(ended, "a contained boot failure leaves nothing running once main returns: " + out);
            assertFalse(out.contains("REVIEW77C live non-daemon thread"), out);
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }

    public static class ReturningBootMain {
        public static void main(String[] args) throws Exception {
            String healthy = com.telamin.mongoose.internal.CaptureMigrationAcceptanceTest.TrackedProcessor.class.getName();
            String failing = com.telamin.mongoose.internal.CaptureMigrationAcceptanceTest.FailedStart.class.getName();
            String yaml = """
                    eventFeeds:
                      - name: readings
                        instance: !!com.telamin.mongoose.connector.memory.InMemoryEventSource {}
                        broadcast: true
                        agentName: readings-agent
                    eventHandlers:
                      - agentName: good
                        eventHandlers:
                          p:
                            eventHandler: !!%s {}
                            logLevel: INFO
                      - agentName: bad
                        eventHandlers:
                          q:
                            eventHandler: !!%s {}
                            logLevel: INFO
                    """.formatted(healthy, failing);
            try {
                MongooseServer.bootServer(new StringReader(yaml), r -> { });
                System.out.println("REVIEW77C unexpected: boot succeeded");
            } catch (Throwable failed) {
                System.out.println("caller caught boot failure: " + failed);
            }
            Thread.sleep(1_000);
            Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> t.isAlive() && !t.isDaemon() && t != Thread.currentThread())
                    .forEach(t -> System.out.println("REVIEW77C live non-daemon thread: " + t.getName()));
            System.out.flush();
            // main returns normally: the JVM should end unless non-daemon threads remain
        }
    }

    // ---------------------------------------------------------------- M4: JSON status, R7 foreign handle

    @Test
    void jsonStatusHasEveryRecordComponent() {
        var status = new MongooseServer.ControlGroupStatus("g\"\\\n\u0001 ", ControlDriver.GroupState.RUNNING, 1, 2, 3,
                Map.of(7L, "close \"x\""), Map.of(), Map.of("p\tq", "reason\r"), true, 4);
        String json = status.toJson();
        System.out.println("REVIEW77C json=" + json);
        for (var c : MongooseServer.ControlGroupStatus.class.getRecordComponents()) {
            assertTrue(json.contains("\"" + c.getName() + "\":"), "missing " + c.getName() + " in " + json);
        }
        assertTrue(json.contains("g\\\"\\\\\\n\\u0001"), json);
        assertFalse(json.chars().anyMatch(ch -> ch < 0x20), "no raw control characters: " + json);
    }

    @Test
    void foreignHandleIsRefusedInvalid() throws Exception {
        MongooseServer a = new MongooseServer(new MongooseServerConfig());
        MongooseServer b = new MongooseServer(new MongooseServerConfig());
        a.init(); a.start(); b.init(); b.start();
        try {
            var added = a.addProcessor("p", "g", null, Plain::new);
            assertNotNull(added.handle());
            ControlReply reply = b.stopProcessor(added.handle()).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(FailureKind.REFUSED_INVALID, reply.kind(), reply.toString());
            assertTrue(reply.kind().refusal());
        } finally {
            a.stop(); b.stop();
        }
    }


    /** A1: an Error in an agent-hosted service group under CONTAIN. Old behaviour: process exit. New: is it reported? */
    @Test
    void serviceAgentErrorUnderContain() throws Exception {
        AtomicInteger exits = new AtomicInteger();
        java.util.List<Throwable> handled = new java.util.concurrent.CopyOnWriteArrayList<>();
        var cfg = new MongooseServerConfig();
        var perf = new com.telamin.mongoose.config.PerformanceMonitoringConfig();
        perf.setEnabled(true);
        cfg.setPerformanceMonitoring(perf);
        MongooseServer server = new MongooseServer(cfg);
        GlobalErrorHandler contain = new GlobalErrorHandler(FatalErrorPolicy.CONTAIN, s -> exits.incrementAndGet());
        server.setDefaultErrorHandler(t -> { handled.add(t); contain.onError(t); });
        java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<Thread> agentThread = new java.util.concurrent.atomic.AtomicReference<>();
        org.agrona.concurrent.Agent delegate = new org.agrona.concurrent.Agent() {
            public int doWork() {
                agentThread.set(Thread.currentThread());
                if (fail.get()) throw new AssertionError("injected service-agent error");
                return 0;
            }
            public String roleName() { return "svc-delegate"; }
        };
        Runnable instance = () -> { };
        server.registerWorkerService(new com.telamin.mongoose.dutycycle.ServiceAgent<>("svc-agent", null,
                new com.telamin.fluxtion.runtime.service.Service<>(instance, Runnable.class, "svc"), delegate));
        server.init();
        server.start();
        try {
            await(() -> agentThread.get() != null, "service agent running");
            fail.set(true);
            await(() -> !agentThread.get().isAlive(), "service agent thread ended");
            MongooseHealthService health = (MongooseHealthService) server.registeredServices()
                    .get(MongooseHealthService.SERVICE_NAME).instance();
            System.out.println("REVIEW77C service agent died: handled=" + handled + " exits=" + exits.get()
                    + " started=" + server.isStarted() + " svc/up=" + health.statusOfCheck("svc", "up")
                    + " svc aggregated=" + health.aggregatedVerdict("svc"));
            StringBuilder downs = new StringBuilder();
            health.forEachStatus((svc, check, st) -> { if (st.verdict() != HealthStatus.Verdict.UP) downs.append(svc).append('/').append(check).append('=').append(st).append("; "); });
            System.out.println("REVIEW77C non-UP checks after service agent death: " + downs);
            // inverted to the fixed behaviour (review round 4): the dead agent group is DOWN and its service is down
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((health.statusOfCheck("agent-group:svc-agent", "agent").verdict() != HealthStatus.Verdict.DOWN
                    || health.statusOfCheck("svc", "up").verdict() == HealthStatus.Verdict.UP) && System.nanoTime() < until) {
                Thread.sleep(50);
            }
            assertEquals(HealthStatus.Verdict.DOWN, health.statusOfCheck("agent-group:svc-agent", "agent").verdict());
            assertTrue(health.statusOfCheck("svc", "up").verdict() != HealthStatus.Verdict.UP,
                    health.statusOfCheck("svc", "up").toString());
            assertEquals(0, exits.get(), "CONTAIN never exits");
        } finally {
            server.stop();
        }
    }

    /** Asked: does the CONTAIN default also reach configs built without bootServer? (embedders' own handler) */
    @Test
    void containIsTheDefaultPolicy() {
        assertEquals(FatalErrorPolicy.CONTAIN, new MongooseServerConfig().getFatalErrorPolicy());
    }
}
