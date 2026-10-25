/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.stress;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceDeregistered;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.AddedProcessor;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.ProcessorHandle;
import com.telamin.mongoose.StopResult;
import com.telamin.mongoose.config.AuditCaptureConfig;
import com.telamin.mongoose.config.EventFeedConfig;
import com.telamin.mongoose.config.EventProcessorConfig;
import com.telamin.mongoose.config.EventProcessorGroupConfig;
import com.telamin.mongoose.config.EventSinkConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.PerformanceMonitoringConfig;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.control.ControlAuditSink;
import com.telamin.mongoose.control.ControlReply;
import com.telamin.mongoose.control.event.ReplyOutcome;
import com.telamin.mongoose.control.validation.ControlEvidence;
import com.telamin.mongoose.control.validation.ControlInvariants;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.internal.ConfigAwareEventProcessor;
import com.telamin.mongoose.replay.ReplayConfig;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventSourceKey;
import com.telamin.mongoose.service.EventSubscriptionKey;
import com.telamin.mongoose.service.audit.MongooseAuditCaptureService;
import org.HdrHistogram.Histogram;
import org.agrona.concurrent.BusySpinIdleStrategy;

import java.io.Serializable;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A chaos workload against a real server: feeds publishing continuously, admin threads adding, stopping and re-adding
 * processors across a few groups, registering and removing services, starting and stopping audit capture, and a flood
 * thread filling a group's control mailbox while its thread is held in a slow start. Processors may throw or be slow in
 * start(), stop(), tearDown() and their handler. Every processor instance keeps its own ledger, and every control
 * request its own record, so the checks judge what happened from the outside, independently of the engine's state.
 * <p>
 * Used by the chaos, replay and soak tests.
 */
final class ChaosWorkload implements AutoCloseable {

    // ================================================================================================ settings

    static final class Settings {
        int groups = 3;
        int namesPerGroup = 6;
        int feeds = 2;
        int feedRatePerSec = 2_000;
        int adminThreads = 4;
        int services = 4;
        /** Pause between one admin thread's operations, uniformly random in [0, this). */
        int adminPauseMillis = 40;
        boolean faults = true;
        boolean captures = true;
        boolean floods = true;
        long floodIntervalMillis = 60_000;
        /** How long a producer waits on a full control mailbox before it is refused (the engine's default is 10 s). */
        long mailboxFullTimeoutMillis = 1_000;
        long seed = 42;
        Path captureDir;
        ReplayConfig replay;
        long shutdownTimeoutMillis = 30_000;
        /**
         * Soak mode: nothing grows with the run's length on the test's side. Requests are checked as they are answered
         * and forgotten, synchronous calls are aggregated, and released instances are checked and pruned by
         * {@link #pruneReleased}, so heap samples measure the server, not the ledger.
         */
        boolean soak;
    }

    static final String GROUP_PREFIX = "chaos-g";
    static final String FEED_PREFIX = "chaos-feed-";
    static final String SVC_PREFIX = "chaos-svc-";

    static String groupName(int g) {
        return GROUP_PREFIX + g;
    }

    static String anchorName(int g) {
        return "anchor-" + g;
    }

    static String feedName(int f) {
        return FEED_PREFIX + f;
    }

    // ================================================================================================ model

    public interface ChaosService {
        int id();
    }

    public record ChaosServiceImpl(int id) implements ChaosService { }

    /** A feed event: its feed, its sequence in that feed, when it was offered (System.nanoTime). */
    public record Tick(int feed, long seq, long sentNanos) implements Serializable { }

    enum Kind { NORMAL, NODE, START_THROWS, START_SLOW, STOP_THROWS, STOP_SLOW, TEARDOWN_THROWS, HANDLER_THROWS,
        HANDLER_SLOW, FLOOD, BLOCKER, ANCHOR }

    /** One built processor instance's own account of what happened to it. */
    static final class Instance {
        static final AtomicLong SERIALS = new AtomicLong();
        /** Ticks sent before this are not measured (a warm-up); set by a test, read on the group threads. */
        static volatile long latencyFromNanos = Long.MIN_VALUE;
        final long serial = SERIALS.incrementAndGet();
        final String group;
        final String name;
        final Kind kind;
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();
        final AtomicInteger tearDowns = new AtomicInteger();
        final AtomicLong afterStop = new AtomicLong();
        final AtomicLong afterTearDown = new AtomicLong();
        final AtomicLong wrongThread = new AtomicLong();
        final Set<String> services = ConcurrentHashMap.newKeySet();
        final CountDownLatch startEntered = new CountDownLatch(1);
        volatile long startedNanos;
        volatile long stopNanos;
        volatile boolean startFailed;
        volatile boolean stopEntered;
        volatile boolean tornDown;
        volatile long received;
        volatile Thread agentThread;
        volatile long tornDownNanos;
        long slowStartMillis;
        /** Anchors only: publish-to-handler latency, written on the group's thread. */
        Histogram latency;

        Instance(String group, String name, Kind kind) {
            this.group = group;
            this.name = name;
            this.kind = kind;
        }

        boolean running() {
            return starts.get() == 1 && !startFailed && !stopEntered && !tornDown;
        }

        /** Lifetime in which feeds could reach it, in ms: from its started start() to its stop (or now). */
        long liveMillis() {
            if (startedNanos == 0 || startFailed) return 0;
            long end = stopNanos != 0 ? stopNanos : System.nanoTime();
            return TimeUnit.NANOSECONDS.toMillis(end - startedNanos);
        }

        void onTick(Tick t) {
            if (stopEntered) afterStop.incrementAndGet();
            if (tornDown) afterTearDown.incrementAndGet();
            Thread current = Thread.currentThread();
            if (agentThread != null && agentThread != current) wrongThread.incrementAndGet();
            received++;
            Histogram latency = this.latency;
            if (latency != null && t.sentNanos() >= latencyFromNanos) {
                long l = System.nanoTime() - t.sentNanos();
                if (l > 0) latency.recordValue(Math.min(l, latency.getHighestTrackableValue()));
            }
        }

        @Override
        public String toString() {
            return name + "#" + serial + "[" + kind + " starts=" + starts + " stops=" + stops + " tearDowns=" + tearDowns
                    + " startFailed=" + startFailed + " received=" + received + "]";
        }
    }

    /** A processor written directly against DataFlow, with the injected faults of its kind. */
    final class ChaosProcessor implements DataFlow {
        final Instance me;
        final List<EventFeed> feeds = new ArrayList<>();
        final Random random;

        ChaosProcessor(Instance me, long seed) {
            this.me = me;
            this.random = new Random(seed);
        }

        @Override public void init() { }

        @Override
        public void addEventFeed(EventFeed feed) {
            feeds.add(feed);
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public void start() {
            me.starts.incrementAndGet();
            me.agentThread = Thread.currentThread();
            me.startEntered.countDown();
            if (me.kind != Kind.FLOOD) {
                for (int f = 0; f < settings.feeds; f++) {
                    EventSubscriptionKey<Object> key = new EventSubscriptionKey<>(new EventSourceKey<>(feedName(f)),
                            CallBackType.ON_EVENT_CALL_BACK);
                    for (EventFeed feed : feeds) feed.subscribe(this, key);
                }
            }
            switch (me.kind) {
                case START_THROWS -> {
                    me.startFailed = true;
                    throw new IllegalStateException("CHAOS start failure of " + me);
                }
                case START_SLOW, BLOCKER -> sleep(me.slowStartMillis);
                default -> { }
            }
            me.startedNanos = System.nanoTime();
        }

        @Override
        public void stop() {
            me.stopNanos = System.nanoTime();
            me.stopEntered = true;
            me.stops.incrementAndGet();
            switch (me.kind) {
                case STOP_THROWS -> throw new IllegalStateException("CHAOS stop failure of " + me);
                case STOP_SLOW -> sleep(20 + random.nextInt(180));
                default -> { }
            }
        }

        @Override
        public void tearDown() {
            me.tearDowns.incrementAndGet();
            me.tornDownNanos = System.nanoTime();
            me.tornDown = true;
            if (me.kind == Kind.TEARDOWN_THROWS) throw new IllegalStateException("CHAOS tearDown failure of " + me);
        }

        @Override
        public void onEvent(Object event) {
            if (!(event instanceof Tick t)) return;
            if (me.kind == Kind.HANDLER_THROWS && random.nextInt(2_000) == 0) {
                throw new IllegalStateException("CHAOS handler failure of " + me + " on " + t);
            }
            me.onTick(t);
            if (me.kind == Kind.HANDLER_SLOW && me.received % 500 == 0) sleep(1);
        }

        @Override
        public void registerService(Service<?> service) {
            if (service.instance() instanceof ChaosService) me.services.add(service.serviceName());
        }

        @Override
        public void deRegisterService(Service<?> service) {
            if (service.instance() instanceof ChaosService) me.services.remove(service.serviceName());
        }
    }

    /**
     * A generated-runtime processor (an ObjectEventHandlerNode in a ConfigAwareEventProcessor). Anchors emit one line
     * per tick to the sink, carrying everything their state depends on, so a replay can be compared line for line.
     */
    public static final class ChaosNode extends ObjectEventHandlerNode {
        final Instance me;
        final int feeds;
        final boolean emit;
        private MessageSink<String> sink;
        private long count;

        ChaosNode(Instance me, int feeds, boolean emit) {
            this.me = me;
            this.feeds = feeds;
            this.emit = emit;
        }

        @ServiceRegistered
        public void sink(MessageSink<String> sink, String name) {
            this.sink = sink;
        }

        @ServiceRegistered
        public void chaos(ChaosService service, String name) {
            me.services.add(name);
        }

        @ServiceDeregistered
        public void chaosGone(ChaosService service, String name) {
            me.services.remove(name);
        }

        @Override
        public void start() {
            me.starts.incrementAndGet();
            me.agentThread = Thread.currentThread();
            me.startEntered.countDown();
            for (int f = 0; f < feeds; f++) getContext().subscribeToNamedFeed(feedName(f));
            me.startedNanos = System.nanoTime();
        }

        @Override
        public void stop() {
            me.stopNanos = System.nanoTime();
            me.stopEntered = true;
            me.stops.incrementAndGet();
        }

        @Override
        public void tearDown() {
            me.tearDowns.incrementAndGet();
            me.tornDownNanos = System.nanoTime();
            me.tornDown = true;
        }

        @Override
        protected boolean handleEvent(Object event) {
            if (event instanceof Tick t) {
                me.onTick(t);
                count++;
                if (emit && sink != null) {
                    sink.accept(me.name + " feed=" + t.feed() + " seq=" + t.seq() + " n=" + count + " services="
                            + me.services.size() + " time="
                            + getContext().getClock().getProcessTime());
                }
            }
            return true;
        }
    }

    /**
     * The anchors' sink. Not InMemoryMessageSink: that keeps a CopyOnWriteArrayList, so every message copies the whole
     * history and a long run's group threads slowed quadratically (it made the first 10-minute chaos run back up).
     */
    public static final class LinesSink extends com.telamin.fluxtion.runtime.output.AbstractMessageSink<Object> {
        private final List<Object> lines = new ArrayList<>();

        @Override
        protected synchronized void sendToSink(Object value) {
            if (value != null) lines.add(value);
        }

        synchronized List<Object> getMessages() {
            return new ArrayList<>(lines);
        }
    }

    // ================================================================================================ ops ledger

    /** One control request sent through an asynchronous API: answered exactly once, by its stage. */
    static final class AsyncOp {
        final String what;
        final String group;
        final String name;
        final long sentNanos = System.nanoTime();
        final AtomicInteger answers = new AtomicInteger();
        volatile long answeredNanos;
        volatile ControlReply reply;
        volatile Throwable error;
        volatile Instance instance;      // an add: what its supplier built, if it ran
        volatile Instance target;        // a stop by handle: the instance its add built, if known then
        volatile boolean stopTargetReleased = true;
        ProcessorHandle handle;
        CompletableFuture<ControlReply> future;

        AsyncOp(String what, String group, String name) {
            this.what = what;
            this.group = group;
            this.name = name;
        }

        @Override
        public String toString() {
            return what + " " + group + "/" + name + " -> " + (reply != null ? reply : error) + " answers=" + answers;
        }
    }

    /** One synchronous admin call: how long it took and how it ended. */
    record SyncOp(String what, long nanos, String outcome) { }

    // ================================================================================================ state

    final Settings settings;
    final MongooseServer server;
    final List<InMemoryEventSource<Object>> feeds = new ArrayList<>();
    final LinesSink sink = new LinesSink();
    final MongooseAuditCaptureService capture;
    final ConcurrentLinkedQueue<Instance> instances = new ConcurrentLinkedQueue<>();
    final Set<AsyncOp> pendingOps = ConcurrentHashMap.newKeySet();
    final AtomicLong asyncCount = new AtomicLong();
    final AtomicLong maxAsyncNanos = new AtomicLong();
    final AtomicLong pruned = new AtomicLong();
    final Map<String, long[]> syncStats = new ConcurrentHashMap<>();
    final long bootNanos = System.nanoTime();
    /** Requests answered after more than 5 s: what, where, when sent (s since boot), how long. */
    final ConcurrentLinkedQueue<String> slowReplies = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<AsyncOp> asyncOps = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<SyncOp> syncOps = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<String> anomalies = new ConcurrentLinkedQueue<>();
    final Map<String, AsyncOp> latestAdd = new ConcurrentHashMap<>();
    final List<ProcessorHandle> staleHandles = new CopyOnWriteArrayList<>();
    final Set<String> namesUsed = ConcurrentHashMap.newKeySet();
    final Map<String, AtomicLong> outcomes = new ConcurrentHashMap<>();
    final ReentrantReadWriteLock floodLock = new ReentrantReadWriteLock();
    final AtomicBoolean running = new AtomicBoolean();
    final AtomicBoolean feeding = new AtomicBoolean();
    final AtomicLong feedPublished = new AtomicLong();
    final AtomicLong floods = new AtomicLong();
    final AtomicLong floodRefused = new AtomicLong();
    final AtomicLong floodThrew = new AtomicLong();
    final List<Thread> threads = new ArrayList<>();
    final Instance[] anchors;
    final AtomicLong servicesRegistered = new AtomicLong();
    final AtomicLong servicesRemoved = new AtomicLong();
    final AtomicLong captureStarts = new AtomicLong();
    final AtomicLong captureStops = new AtomicLong();

    ChaosWorkload(Settings settings) {
        this.settings = settings;
        this.anchors = new Instance[settings.groups];
        MongooseServerConfig.Builder builder = MongooseServerConfig.builder()
                .shutdownTimeoutMillis(settings.shutdownTimeoutMillis);
        for (int g = 0; g < settings.groups; g++) {
            Instance anchor = new Instance(groupName(g), anchorName(g), Kind.ANCHOR);
            anchor.latency = new Histogram(TimeUnit.SECONDS.toNanos(60), 3);
            anchors[g] = anchor;
            instances.add(anchor);
            builder.addProcessorGroup(EventProcessorGroupConfig.builder().agentName(groupName(g))
                    .idleStrategy(new BusySpinIdleStrategy())
                    .put(anchorName(g), new EventProcessorConfig(new ChaosNode(anchor, settings.feeds, settings.replay != null))).build());
        }
        for (int f = 0; f < settings.feeds; f++) {
            InMemoryEventSource<Object> feed = new InMemoryEventSource<>();
            feed.setName(feedName(f));
            feeds.add(feed);
            builder.addEventFeed(EventFeedConfig.builder().instance(feed).name(feedName(f)).broadcast(true)
                    .agent(FEED_PREFIX + "agent-" + f, new BusySpinIdleStrategy()).build());
        }
        builder.addEventSink(EventSinkConfig.<MessageSink<?>>builder().instance(sink).name("chaosSink").build());
        if (settings.replay != null) builder.replay(settings.replay);
        MongooseServerConfig config = builder.build();
        if (settings.captures) {
            AuditCaptureConfig captureConfig = new AuditCaptureConfig();
            captureConfig.setEnabled(true);
            captureConfig.setDirectory(settings.captureDir.toString());
            PerformanceMonitoringConfig perf = new PerformanceMonitoringConfig();
            perf.setAuditCapture(captureConfig);
            config.setPerformanceMonitoring(perf);
        }
        server = MongooseServer.bootServer(config, rec -> { });
        capture = (MongooseAuditCaptureService) server.registeredServices()
                .get(MongooseAuditCaptureService.SERVICE_NAME).instance();
        for (ComposingEventProcessorAgent group : StressSupport.groups(server).values()) {
            group.setControlQueueFullTimeout(Duration.ofMillis(settings.mailboxFullTimeoutMillis));
        }
    }

    // ================================================================================================ running

    /** Start feeding, and wait until every anchor receives from every feed. */
    void startFeeds() throws InterruptedException {
        feeding.set(true);
        for (int f = 0; f < settings.feeds; f++) {
            int feed = f;
            Thread t = new Thread(() -> feed(feed), "chaos-feeder-" + f);
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        for (Instance anchor : anchors) {
            while (anchor.received == 0) {
                if (System.nanoTime() > deadline) throw new IllegalStateException("anchor never received: " + anchor);
                Thread.sleep(5);
            }
        }
    }

    private void feed(int f) {
        long periodNanos = TimeUnit.SECONDS.toNanos(1) / settings.feedRatePerSec;
        long next = System.nanoTime();
        long seq = 0;
        InMemoryEventSource<Object> source = feeds.get(f);
        while (feeding.get()) {
            source.offer(new Tick(f, seq++, System.nanoTime()));
            feedPublished.incrementAndGet();
            next += periodNanos;
            long wait = next - System.nanoTime();
            if (wait > 0) LockSupport.parkNanos(wait);
            else if (wait < -TimeUnit.MILLISECONDS.toNanos(100)) next = System.nanoTime();   // fell behind: do not burst
        }
    }

    void stopFeeds() throws InterruptedException {
        feeding.set(false);
        for (Thread t : threads) {
            if (t.getName().startsWith("chaos-feeder")) t.join(5_000);
        }
    }

    /** Start the admin threads and, when enabled, the flood thread. */
    void startChaos() {
        running.set(true);
        for (int i = 0; i < settings.adminThreads; i++) {
            Random random = new Random(settings.seed * 31 + i);
            Thread t = new Thread(() -> adminLoop(random), "chaos-admin-" + i);
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }
        if (settings.floods) {
            Random random = new Random(settings.seed * 17);
            Thread t = new Thread(() -> floodLoop(random), "chaos-flooder");
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }
    }

    /** Stop the admin and flood threads, and wait for every asynchronous reply (failing loudly if one hangs). */
    void stopChaos(long replyWaitSeconds) throws InterruptedException {
        running.set(false);
        for (Thread t : threads) {
            if (t.getName().startsWith("chaos-admin") || t.getName().startsWith("chaos-flood")) {
                t.join(120_000);
                if (t.isAlive()) anomalies.add("admin thread " + t.getName() + " did not finish: hung in\n"
                        + StressSupport.dumpThreads());
            }
        }
        awaitReplies(replyWaitSeconds);
        awaitQuiescent(replyWaitSeconds);
    }

    /**
     * Wait until no group has a control request outstanding: a synchronous call that timed out leaves its request
     * queued, still to apply, so quiescence is judged by the groups, not by the callers.
     */
    void awaitQuiescent(long seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (outstandingRequests() > 0 && System.nanoTime() < deadline) Thread.sleep(10);
        if (outstandingRequests() > 0) anomalies.add(outstandingRequests() + " control requests still outstanding after "
                + seconds + " s of quiescence");
    }

    void recordSync(String what, long nanos, String outcome) {
        if (!settings.soak) syncOps.add(new SyncOp(what, nanos, outcome));
        long[] a = syncStats.computeIfAbsent(what, k -> new long[2]);
        synchronized (a) {
            a[0]++;
            a[1] = Math.max(a[1], nanos);
        }
    }

    void awaitReplies(long seconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        for (AsyncOp op : new ArrayList<>(pendingOps)) {
            long left = deadline - System.nanoTime();
            try {
                op.future.get(Math.max(1, left), TimeUnit.NANOSECONDS);
            } catch (Exception e) {
                // judged by the checks
            }
        }
    }

    private void adminLoop(Random random) {
        while (running.get()) {
            try {
                int roll = random.nextInt(100);
                if (roll < 35) asyncAdd(random);
                else if (roll < 40) syncAdd(random);
                else if (roll < 65) asyncStop(random);
                else if (roll < 70) syncStop(random, false);
                else if (roll < 75) syncStop(random, true);
                else if (roll < 88) serviceChange(random);
                else if (settings.captures) captureChange(random);
            } catch (Throwable t) {
                anomalies.add("admin op threw unexpectedly: " + t + "\n" + stack(t));
            }
            int pause = settings.adminPauseMillis <= 0 ? 0 : random.nextInt(settings.adminPauseMillis);
            if (pause > 0) sleep(pause);
        }
    }

    private String randomGroup(Random random) {
        return groupName(random.nextInt(settings.groups));
    }

    private String randomName(Random random, String group) {
        return "c" + group.substring(GROUP_PREFIX.length()) + "-" + random.nextInt(settings.namesPerGroup);
    }

    private Kind randomKind(Random random) {
        if (!settings.faults) return random.nextInt(4) == 0 ? Kind.NODE : Kind.NORMAL;
        int r = random.nextInt(100);
        if (r < 40) return Kind.NORMAL;
        if (r < 55) return Kind.NODE;
        if (r < 62) return Kind.START_THROWS;
        if (r < 69) return Kind.START_SLOW;
        if (r < 76) return Kind.STOP_THROWS;
        if (r < 83) return Kind.STOP_SLOW;
        if (r < 88) return Kind.TEARDOWN_THROWS;
        if (r < 94) return Kind.HANDLER_THROWS;
        return Kind.HANDLER_SLOW;
    }

    /** A supplier building one instance of {@code kind}, recorded in the ledger and on {@code op} when it runs. */
    private java.util.function.Supplier<DataFlow> supplier(String group, String name, Kind kind, long seed, AsyncOp op,
                                                           long slowStartMillis) {
        return () -> {
            Instance me = new Instance(group, name, kind);
            me.slowStartMillis = slowStartMillis;
            instances.add(me);
            if (op != null) op.instance = me;
            if (kind == Kind.NODE) {
                ConfigAwareEventProcessor flow = new ConfigAwareEventProcessor(new ChaosNode(me, settings.feeds, false));
                flow.init();
                return flow;
            }
            return new ChaosProcessor(me, seed);
        };
    }

    AsyncOp asyncAdd(String group, String name, Kind kind, long seed, long slowStartMillis) {
        namesUsed.add(group + "/" + name);
        AsyncOp op = new AsyncOp("add " + kind, group, name);
        AddedProcessor added = server.addProcessor(name, group, null, supplier(group, name, kind, seed, op, slowStartMillis));
        op.handle = added.handle();
        track(op, added.reply());
        if (added.handle() != null) latestAdd.put(group + "/" + name, op);
        return op;
    }

    private void asyncAdd(Random random) {
        String group = randomGroup(random);
        Kind kind = randomKind(random);
        asyncAdd(group, randomName(random, group), kind, random.nextLong(), 20 + random.nextInt(180));
    }

    private void syncAdd(Random random) {
        String group = randomGroup(random);
        String name = randomName(random, group);
        namesUsed.add(group + "/" + name);
        Kind kind = randomKind(random);
        long t0 = System.nanoTime();
        String outcome = "returned";
        try {
            server.addEventProcessor(name, group, null, supplier(group, name, kind, random.nextLong(), null,
                    20 + random.nextInt(180)));
        } catch (IllegalArgumentException duplicate) {
            outcome = "IAE";
        } catch (IllegalStateException refused) {
            outcome = "ISE";
        }
        recordSync("addEventProcessor", System.nanoTime() - t0, outcome);
        count("sync addEventProcessor " + outcome);
    }

    private void asyncStop(Random random) {
        String group = randomGroup(random);
        String name = randomName(random, group);
        AsyncOp add = latestAdd.get(group + "/" + name);
        ProcessorHandle handle = add != null ? add.handle : server.processorHandle(group, name);
        if (handle == null) return;
        stopByHandle(handle, add);
    }

    AsyncOp stopByHandle(ProcessorHandle handle, AsyncOp add) {
        AsyncOp op = new AsyncOp("stop", handle.group(), handle.name());
        op.handle = handle;
        op.target = add == null ? null : add.instance;
        CompletableFuture<ControlReply> stage;
        try {
            stage = server.stopProcessor(handle).toCompletableFuture();
        } catch (RuntimeException thrown) {
            // the API says it returns the outcome without waiting; a throw is recorded and judged by the checks
            count("stopProcessor(handle) threw " + thrown.getClass().getSimpleName());
            anomalies.add("stopProcessor(handle) threw instead of answering: " + thrown);
            return null;
        }
        track(op, stage);
        staleHandles.add(handle);
        if (staleHandles.size() > 64) staleHandles.remove(0);
        return op;
    }

    private void syncStop(Random random, boolean bounded) {
        String group = randomGroup(random);
        String name = randomName(random, group);
        long t0 = System.nanoTime();
        String outcome;
        if (bounded) {
            ControlReply reply = server.stopProcessor(group, name, 1_000);
            outcome = reply == null ? "null" : reply.outcome().name();
        } else {
            try {
                server.stopProcessor(group, name);
                outcome = "returned";
            } catch (IllegalStateException notQueued) {
                outcome = "ISE";     // the void form reports a full mailbox by throwing, as addEventProcessor does
            }
        }
        String what = bounded ? "stopProcessor(g,n,1000)" : "stopProcessor(g,n)";
        recordSync(what, System.nanoTime() - t0, outcome);
        count("sync " + what + " " + outcome);
    }

    private void serviceChange(Random random) {
        int id = random.nextInt(settings.services);
        String name = SVC_PREFIX + id;
        floodLock.readLock().lock();
        try {
            long t0 = System.nanoTime();
            String outcome;
            if (random.nextBoolean()) {
                try {
                    server.registerService(new Service<>(new ChaosServiceImpl(id), ChaosService.class, name));
                    servicesRegistered.incrementAndGet();
                    outcome = "registered";
                } catch (com.telamin.mongoose.exception.ServiceRegistrationException taken) {
                    outcome = "name taken";
                }
                recordSync("registerService", System.nanoTime() - t0, outcome);
            } else {
                boolean present = server.registeredServices().containsKey(name);
                server.removeService(name);
                if (present) servicesRemoved.incrementAndGet();
                outcome = present ? "removed" : "absent";
                recordSync("removeService", System.nanoTime() - t0, outcome);
            }
            count("service " + outcome);
        } finally {
            floodLock.readLock().unlock();
        }
    }

    private void captureChange(Random random) {
        String group = randomGroup(random);
        String name = random.nextInt(4) == 0 ? anchorName(Integer.parseInt(group.substring(GROUP_PREFIX.length())))
                : randomName(random, group);
        boolean start = random.nextBoolean();
        long t0 = System.nanoTime();
        String outcome = "ok";
        try {
            if (start) {
                capture.start(name);
                captureStarts.incrementAndGet();
            } else {
                capture.stop(name);
                captureStops.incrementAndGet();
            }
        } catch (IllegalArgumentException | IllegalStateException refused) {
            outcome = refused.getClass().getSimpleName();
        }
        String what = start ? "capture.start" : "capture.stop";
        recordSync(what, System.nanoTime() - t0, outcome);
        count(what + " " + outcome);
    }

    /**
     * Fill one group's control mailbox while its thread is held in a slow start: a blocker processor whose start()
     * sleeps longer than the mailbox-full timeout, then more stale-handle stops than the mailbox holds, sent from several
     * threads, then adds that should be refused. Service changes are held off meanwhile (a full mailbox is a documented
     * reason for a group to miss a broadcast), so the service views can be checked exactly afterwards.
     */
    private void floodLoop(Random random) {
        long next = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settings.floodIntervalMillis / 3);
        while (running.get()) {
            if (System.nanoTime() < next) {
                sleep(50);
                continue;
            }
            next = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settings.floodIntervalMillis);
            floodLock.writeLock().lock();
            try {
                flood(random);
            } catch (Throwable t) {
                anomalies.add("flood threw: " + t + "\n" + stack(t));
            } finally {
                floodLock.writeLock().unlock();
            }
        }
    }

    private void flood(Random random) throws Exception {
        floods.incrementAndGet();
        int g = random.nextInt(settings.groups);
        String group = groupName(g);
        String blockerName = "c" + g + "-blocker";
        long blockMillis = settings.mailboxFullTimeoutMillis * 3;
        AsyncOp blocker = asyncAdd(group, blockerName, Kind.BLOCKER, random.nextLong(), blockMillis);
        if (blocker.handle == null) return;
        // wait for the blocker's start() to hold the group's thread
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (blocker.instance == null && System.nanoTime() < until) sleep(1);
        if (blocker.instance == null || !blocker.instance.startEntered.await(30, TimeUnit.SECONDS)) {
            anomalies.add("flood: the blocker never started in " + group);
            return;
        }
        ProcessorHandle stale = blocker.handle;
        for (ProcessorHandle h : staleHandles) {
            if (h.group().equals(group)) stale = h;
        }
        ProcessorHandle target = stale;
        int senders = 4;
        int perSender = (4096 + 400) / senders;
        List<Thread> flooders = new ArrayList<>();
        for (int s = 0; s < senders; s++) {
            Thread t = new Thread(() -> {
                for (int i = 0; i < perSender; i++) {
                    try {
                        AsyncOp op = new AsyncOp("flood stop", target.group(), target.name());
                        op.stopTargetReleased = false;
                        track(op, server.stopProcessor(target).toCompletableFuture());
                    } catch (IllegalStateException full) {
                        floodThrew.incrementAndGet();
                    }
                }
            }, "chaos-flood-sender-" + s);
            t.setDaemon(true);
            flooders.add(t);
            t.start();
        }
        for (int i = 0; i < 20; i++) {
            AsyncOp add = asyncAdd(group, "c" + g + "-flood-" + i, Kind.FLOOD, random.nextLong(), 0);
            if (add.handle == null || (add.future.isDone() && add.reply != null && add.reply.outcome() == ReplyOutcome.REFUSED)) {
                floodRefused.incrementAndGet();
            }
        }
        for (Thread t : flooders) t.join(60_000);
        blocker.future.get(60, TimeUnit.SECONDS);
        // let the group drain, then stop what the flood added
        for (int i = 0; i < 20; i++) {
            ProcessorHandle h = server.processorHandle(group, "c" + g + "-flood-" + i);
            if (h != null) stopByHandle(h, latestAdd.get(group + "/c" + g + "-flood-" + i));
        }
        ProcessorHandle h = server.processorHandle(group, blockerName);
        if (h != null) stopByHandle(h, blocker);
    }

    private void track(AsyncOp op, java.util.concurrent.CompletionStage<ControlReply> stage) {
        CompletableFuture<ControlReply> future = stage.toCompletableFuture();
        op.future = future;
        asyncCount.incrementAndGet();
        pendingOps.add(op);
        if (!settings.soak) asyncOps.add(op);
        future.whenComplete((reply, error) -> {
            op.answeredNanos = System.nanoTime();
            long took = op.answeredNanos - op.sentNanos;
            maxAsyncNanos.accumulateAndGet(took, Math::max);
            if (took > TimeUnit.SECONDS.toNanos(5) && !op.what.startsWith("flood") && slowReplies.size() < 2000) {
                slowReplies.add(String.format("%s %s/%s sent t=%.1fs took %dms -> %s", op.what, op.group, op.name,
                        (op.sentNanos - bootNanos) / 1e9, TimeUnit.NANOSECONDS.toMillis(took), reply));
            }
            if (op.answers.incrementAndGet() != 1) anomalies.add("request answered more than once: " + op);
            if (error != null) anomalies.add("request completed exceptionally: " + op + " " + error);
            pendingOps.remove(op);
            if (op.what.startsWith("add") && reply != null) checkAddReply(op, reply);
            op.reply = reply;
            op.error = error;
            count(op.what.split(" ")[0] + (op.what.startsWith("flood") ? " flood" : "") + " "
                    + (reply == null ? "error" : reply.outcome() + (reply.reason() == null ? "" : " (" + shortReason(reply.reason()) + ")")));
            // a handle-bound stop answered APPLIED: everything its processor held is released before the reply
            Instance t = op.target;
            if (op.what.equals("stop") && reply != null && reply.applied() && t != null && t.starts.get() == 1
                    && !t.startFailed && t.tearDowns.get() != 1) {
                anomalies.add("stop answered APPLIED before its processor was torn down: " + t + " " + op);
            }
        });
    }

    /** An add's reply agrees with what its processor saw (its start ran on the group's thread before the reply). */
    private void checkAddReply(AsyncOp op, ControlReply reply) {
        ReplyOutcome outcome = reply.outcome();
        Instance built = op.instance;
        if (outcome == ReplyOutcome.APPLIED) {
            if (built == null || built.starts.get() != 1 || built.startFailed) {
                anomalies.add("add answered APPLIED but its processor did not start cleanly: " + op + " " + built);
            }
        } else if (outcome == ReplyOutcome.FAILED) {
            if (built != null && !built.startFailed) {
                anomalies.add("add answered FAILED but its processor started, or was never started: " + op + " " + built);
            }
        } else if (built != null && built.starts.get() > 0) {
            anomalies.add("add answered " + outcome + " but its processor was started: " + op + " " + built);
        }
    }

    private static String shortReason(String reason) {
        String r = reason.replaceAll("[0-9]+", "N");
        return r.length() > 60 ? r.substring(0, 60) : r;
    }

    void count(String what) {
        outcomes.computeIfAbsent(what, k -> new AtomicLong()).incrementAndGet();
    }

    // ================================================================================================ checks

    /** Stop every processor the chaos left running (not the anchors), by handle, and wait for each reply. */
    void stopEverything() throws Exception {
        List<CompletableFuture<ControlReply>> replies = new ArrayList<>();
        for (String key : namesUsed) {
            String[] parts = key.split("/");
            ProcessorHandle h = server.processorHandle(parts[0], parts[1]);
            if (h == null) continue;
            AsyncOp op = stopByHandle(h, latestAdd.get(key));
            if (op != null) replies.add(op.future);
        }
        for (CompletableFuture<ControlReply> f : replies) f.get(60, TimeUnit.SECONDS);
        // the anchors keep running, so a capture started on one is still (legitimately) held: stop it
        if (settings.captures) {
            for (int g = 0; g < settings.groups; g++) {
                try {
                    capture.stop(anchorName(g));
                } catch (RuntimeException e) {
                    anomalies.add("capture.stop of a running anchor failed: " + e);
                }
            }
        }
    }

    /** Every check that holds while the server is still running; returns the failures. */
    List<String> checkRunning() {
        List<String> failures = new ArrayList<>(anomalies);
        // every asynchronous request answered (exactly once and as its ledger says: checked as each was answered)
        long pending = pendingOps.size();
        for (AsyncOp op : pendingOps) {
            if (failures.size() < 5) failures.add("request never answered: " + op);
        }
        if (pending > 0) failures.add(pending + " requests never answered");
        // no processor name stays reserved once its processor is gone
        for (String key : namesUsed) {
            String[] parts = key.split("/");
            if (server.processorHandle(parts[0], parts[1]) != null) failures.add("name still reserved: " + key);
        }
        Map<String, ComposingEventProcessorAgent> groups = StressSupport.groups(server);
        for (var e : groups.entrySet()) {
            ComposingEventProcessorAgent group = e.getValue();
            List<String> left = group.registeredEventProcessors().stream().map(n -> n.name())
                    .filter(n -> !n.startsWith("anchor-")).toList();
            if (!left.isEmpty()) failures.add(e.getKey() + " still hosts " + left);
            if (group.captureResourcesHeld() != 0) {
                failures.add(e.getKey() + " still holds " + group.captureResourcesHeld() + " capture resources");
            }
            if (group.control().outstanding() != 0) {
                failures.add(e.getKey() + " has " + group.control().outstanding() + " outstanding control requests");
            }
        }
        // the ledger: each instance started at most once, stopped at most once, torn down at most once; nothing after
        for (Instance i : instances) checkInstance(i, failures);
        for (Instance a : anchors) {
            if (!a.running()) failures.add("anchor not running: " + a);
        }
        return failures;
    }

    void checkInstance(Instance i, List<String> failures) {
        if (i.starts.get() > 1 || i.stops.get() > 1 || i.tearDowns.get() > 1) failures.add("lifecycle repeated: " + i);
        if (i.afterStop.get() > 0) failures.add("events after stop(): " + i.afterStop + " to " + i);
        if (i.afterTearDown.get() > 0) failures.add("events after tearDown(): " + i.afterTearDown + " to " + i);
        if (i.wrongThread.get() > 0) failures.add("events off the group's thread: " + i);
        if (i.kind == Kind.ANCHOR) return;
        if (i.starts.get() == 1 && !i.startFailed && (i.stops.get() != 1 || i.tearDowns.get() != 1)) {
            failures.add("a started processor was not stopped and torn down once: " + i);
        }
        if (i.startFailed && (i.stops.get() != 0 || i.tearDowns.get() != 1)) {
            failures.add("a processor whose start failed was not torn down once, without a stop: " + i);
        }
        if (i.starts.get() == 0 && (i.stops.get() != 0 || i.tearDowns.get() > 1)) {
            failures.add("a processor never started was stopped: " + i);
        }
        boolean shouldReceive = i.kind != Kind.FLOOD && i.kind != Kind.BLOCKER && i.kind != Kind.HANDLER_THROWS
                && i.kind != Kind.START_THROWS;
        if (shouldReceive && i.liveMillis() >= 5_000 && i.received == 0) {
            failures.add("a processor live for " + i.liveMillis() + " ms received nothing: " + i);
        }
    }

    /**
     * Soak mode: check and forget every instance torn down more than 5 s ago (so a late event after its tearDown would
     * have shown), keeping the ledger's size independent of the run's length.
     */
    void pruneReleased(List<String> failures) {
        long cutoff = System.nanoTime() - TimeUnit.SECONDS.toNanos(5);
        for (var it = instances.iterator(); it.hasNext(); ) {
            Instance i = it.next();
            if (i.kind == Kind.ANCHOR || !i.tornDown || i.tornDownNanos > cutoff) continue;
            checkInstance(i, failures);
            it.remove();
            pruned.incrementAndGet();
        }
    }

    /** At quiescence, with no flood having refused a broadcast: every live processor holds exactly the registry's. */
    List<String> checkServiceViews() {
        List<String> failures = new ArrayList<>();
        Set<String> registry = new java.util.TreeSet<>();
        for (String name : server.registeredServices().keySet()) {
            if (name.startsWith(SVC_PREFIX)) registry.add(name);
        }
        for (Instance i : instances) {
            if (!i.running()) continue;
            Set<String> held = new java.util.TreeSet<>(i.services);
            if (!held.equals(registry)) failures.add("service view of " + i + " is " + held + ", registry " + registry);
        }
        return failures;
    }

    /** Every group's exported control audit, judged by the independent invariants. */
    List<String> checkAudits(Map<String, String> summary) {
        List<String> failures = new ArrayList<>();
        for (var e : StressSupport.groups(server).entrySet()) {
            ControlAuditSink audit = e.getValue().control().audit();
            String text = audit.exportText();
            ControlEvidence evidence = ControlEvidence.parse(text, audit.isComplete());
            ControlInvariants.Result result = ControlInvariants.check(evidence);
            summary.put(e.getKey(), "records=" + audit.total() + " dropped=" + audit.dropped() + " complete="
                    + audit.isComplete() + " " + result);
            if (!audit.isComplete()) failures.add(e.getKey() + " audit incomplete: dropped=" + audit.dropped()
                    + " sinkFailures=" + audit.sinkFailures() + " " + audit.firstSinkFailure());
            if (!result.ok() || !audit.isComplete()) {
                try {
                    Path out = audit.export(Path.of(System.getProperty("mongoose.stress.auditDir", "target/stress-audit"),
                            e.getKey() + "-" + settings.seed + ".yaml"));
                    summary.put(e.getKey() + " export", out.toAbsolutePath().toString());
                } catch (java.io.IOException io) {
                    summary.put(e.getKey() + " export", "failed: " + io);
                }
            }
            if (!result.ok()) {
                List<String> v = result.violations();
                failures.add(e.getKey() + " invariant violations (" + v.size() + "): "
                        + v.subList(0, Math.min(10, v.size())));
            }
        }
        return failures;
    }

    StopResult shutdown() {
        return server.shutdown();
    }

    /** After shutdown: the anchors were stopped and torn down once each. */
    List<String> checkAfterShutdown() {
        List<String> failures = new ArrayList<>();
        for (Instance a : anchors) {
            if (a.stops.get() != 1 || a.tearDowns.get() != 1) failures.add("anchor not stopped once at shutdown: " + a);
        }
        return failures;
    }

    String summary() {
        StringBuilder b = new StringBuilder();
        b.append("feed events offered=").append(feedPublished.get()).append(", instances built=").append(instances.size() + pruned.get())
                .append(", async requests=").append(asyncCount.get()).append(", sync calls=")
                .append(syncStats.values().stream().mapToLong(a -> a[0]).sum())
                .append(", floods=").append(floods.get()).append(" (stop() threw on full mailbox ").append(floodThrew.get())
                .append(")\n");
        Map<String, Long> sorted = new TreeMap<>();
        outcomes.forEach((k, v) -> sorted.put(k, v.get()));
        sorted.forEach((k, v) -> b.append("  ").append(k).append(": ").append(v).append('\n'));
        new TreeMap<>(syncStats).forEach((k, a) -> b.append("  sync ").append(k).append(": n=").append(a[0]).append(" max=")
                .append(TimeUnit.NANOSECONDS.toMillis(a[1])).append(" ms\n"));
        b.append("  slowest async reply: ").append(TimeUnit.NANOSECONDS.toMillis(maxAsyncNanos.get())).append(" ms; replies over 5 s: ")
                .append(slowReplies.size()).append('\n');
        slowReplies.stream().sorted(java.util.Comparator.comparingLong((String r) ->
                        Long.parseLong(r.replaceAll(".* took ([0-9]+)ms.*", "$1"))).reversed())
                .limit(15).forEach(r -> b.append("    slow ").append(r).append('\n'));
        Map<Kind, long[]> byKind = new TreeMap<>();
        for (Instance i : instances) {
            long[] a = byKind.computeIfAbsent(i.kind, k -> new long[2]);
            a[0]++;
            a[1] += i.received;
        }
        byKind.forEach((k, a) -> b.append("  instances ").append(k).append(": ").append(a[0]).append(" received ")
                .append(a[1]).append('\n'));
        return b.toString();
    }

    long maxSyncMillis() {
        long max = 0;
        for (long[] a : syncStats.values()) max = Math.max(max, a[1]);
        return TimeUnit.NANOSECONDS.toMillis(max);
    }

    /** Publish-to-handler latency over every anchor, merged. */
    Histogram anchorLatency() {
        Histogram all = new Histogram(TimeUnit.SECONDS.toNanos(60), 3);
        for (Instance a : anchors) all.add(a.latency);
        return all;
    }

    String outstandingByGroup() {
        StringBuilder b = new StringBuilder();
        StressSupport.groups(server).forEach((k, g) -> b.append(k).append('=').append(g.control().outstanding()).append(' '));
        return b.toString().trim();
    }

    long outstandingRequests() {
        long n = 0;
        for (ComposingEventProcessorAgent g : StressSupport.groups(server).values()) n += g.control().outstanding();
        return n;
    }

    @Override
    public void close() {
        running.set(false);
        feeding.set(false);
        server.shutdown();
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String stack(Throwable t) {
        java.io.StringWriter w = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(w));
        String s = w.toString();
        return s.length() > 3000 ? s.substring(0, 3000) : s;
    }

    static List<String> limit(List<String> list, int n) {
        return list.size() <= n ? list : new ArrayList<>(list.subList(0, n));
    }

    static List<String> sortedCopy(java.util.Collection<String> c) {
        List<String> l = new ArrayList<>(c);
        Collections.sort(l);
        return l;
    }
}
