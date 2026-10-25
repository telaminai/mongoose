package com.telamin.mongoose.benchmark;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.EventFeedConfig;
import com.telamin.mongoose.config.EventProcessorConfig;
import com.telamin.mongoose.config.EventProcessorGroupConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.agrona.concurrent.BusySpinIdleStrategy;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The control plane's cost on the event path (design prediction P7): an idle duty cycle ({@code doWork()} with an
 * empty control mailbox, ns/op) and end-to-end event throughput through a real server (M events/s). Run its main
 * with the surefire JVM flags (see pom.xml argLine) against two builds to compare them; {@code throughputOnly} as a
 * second argument skips the idle phase. Results and method: docs/design/control-plane-predictions.md, P7.
 */
public class ControlPlaneCostBenchmark {

    static final class Quiet implements DataFlow, Lifecycle {
        @Override public void init() { }
        @Override public void addEventFeed(EventFeed f) { }
        @Override public void onEvent(Object e) { }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void tearDown() { }
    }

    /**
     * The count, in a field of its own padded object (follow-up review, finding 9: padding around a reference to a
     * separately allocated AtomicLong isolated nothing). Verified on JDK 25 (Apple M4) with Unsafe.objectFieldOffset:
     * {@code value} at offset 144, with 128 bytes of the object's own fields before and after it, which covers a
     * 128-byte cache line. The JVM lays fields out, not the declaration order: re-check the offsets on another JVM.
     */
    static final class PaddedCount {
        long p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15, p16;
        volatile long value;
        long q1, q2, q3, q4, q5, q6, q7, q8, q9, q10, q11, q12, q13, q14, q15, q16;
        static final java.util.concurrent.atomic.AtomicLongFieldUpdater<PaddedCount> VALUE =
                java.util.concurrent.atomic.AtomicLongFieldUpdater.newUpdater(PaddedCount.class, "value");
    }

    public static final class Counter extends ObjectEventHandlerNode {
        static final PaddedCount COUNT = new PaddedCount();
        @Override public void start() { getContext().subscribeToNamedFeed("feed"); }
        @Override protected boolean handleEvent(Object event) {
            PaddedCount.VALUE.lazySet(COUNT, COUNT.value + 1);
            return true;
        }
        static long get() { return COUNT.value; }
    }

    public static void main(String[] args) throws Exception {
        String label = args[0];
        double[] idle = new double[7];
        ComposingEventProcessorAgent group = new ComposingEventProcessorAgent("bench", new EventFlowManager(), null,
                new DeadWheelScheduler(), new ConcurrentHashMap<>());
        group.onStart();
        group.addNamedEventProcessor(() -> new NamedEventProcessor("q", new Quiet()));
        for (int i = 0; i < 1000; i++) group.doWork();
        long sink = 0;
        boolean idlePhase = args.length < 2 || !args[1].equals("throughputOnly");
        for (int r = -2; idlePhase && r < idle.length; r++) {            // two warm-up rounds
            int n = 50_000_000;
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) sink += group.doWork();
            double ns = (System.nanoTime() - t0) / (double) n;
            if (r >= 0) idle[r] = ns;
        }

        InMemoryEventSource<Object> feed = new InMemoryEventSource<>();
        feed.setName("feed");
        MongooseServerConfig config = MongooseServerConfig.builder()
                .addProcessorGroup(EventProcessorGroupConfig.builder().agentName("processor-agent")
                        .idleStrategy(new BusySpinIdleStrategy())
                        .put("counter", new EventProcessorConfig(new Counter())).build())
                .addEventFeed(EventFeedConfig.builder().instance(feed).name("feed").broadcast(true)
                        .agent("feed-agent", new BusySpinIdleStrategy()).build())
                .build();
        MongooseServer server = MongooseServer.bootServer(config, rec -> { });
        Thread.sleep(500);
        Integer item = 42;
        double[] mps = new double[7];
        for (int r = -2; r < mps.length; r++) {
            int n = 2_000_000;
            long start = Counter.get();
            long t0 = System.nanoTime();
            long deadline = t0 + 30_000_000_000L;
            for (int i = 0; i < n; i++) {
                feed.offer(item);
                while (i - (Counter.get() - start) > 512) {
                    if (System.nanoTime() > deadline) throw new AssertionError("delivery deadline exceeded");
                    Thread.onSpinWait();
                }   // bounded in-flight
            }
            while (Counter.get() - start < n) {
                if (System.nanoTime() > deadline) throw new AssertionError("delivery deadline exceeded");
                Thread.onSpinWait();
            }
            double secs = (System.nanoTime() - t0) / 1e9;
            if (r >= 0) mps[r] = n / secs / 1e6;
        }
        server.stop();
        Arrays.sort(idle);
        Arrays.sort(mps);
        System.out.printf("RESULT %s idleDoWork_ns median=%.2f min=%.2f max=%.2f | throughput_Mev/s median=%.3f min=%.3f max=%.3f (sink %d)%n",
                label, idle[3], idle[0], idle[6], mps[3], mps[0], mps[6], sink);
        System.exit(0);
    }
}
