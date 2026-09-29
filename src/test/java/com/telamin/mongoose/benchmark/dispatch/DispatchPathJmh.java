package com.telamin.mongoose.benchmark.dispatch;

import com.telamin.fluxtion.runtime.DefaultEventProcessor;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.mongoose.dispatch.EventToOnEventInvokeStrategy;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import com.telamin.mongoose.dutycycle.EventQueueToEventProcessorAgent;
import com.telamin.mongoose.service.EventSource;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * The dispatch path the replay work changed, with replay OFF (spec-replay-recording, performance check): a publish
 * through EventToQueuePublisher onto a subscriber queue, then EventQueueToEventProcessorAgent.doWork draining it into a
 * processor through the onEvent strategy. Single-threaded, so it measures the code, not thread hand-off. The payload
 * is pre-allocated and the handler does nothing, so any allocation reported is the dispatch path's own.
 *
 * <p>It uses only APIs present before and after the change, so the same file runs on both.
 * Run: java -cp <test classpath> org.openjdk.jmh.Main DispatchPathJmh -prof gc
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 3, jvmArgsAppend = {"-Xms1g", "-Xmx1g", "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED"})
public class DispatchPathJmh {

    /** NOWRAP is the default feed; NAMED wraps each item in a NamedFeedEvent carrying its sequence number. */
    @Param({"SUBSCRIPTION_NOWRAP", "SUBSCRIPTION_NAMED_EVENT"})
    public String wrap;

    private EventToQueuePublisher<Object> publisher;
    private EventQueueToEventProcessorAgent agent;
    private final Object payload = "DEMO-order";
    public static final class Count extends ObjectEventHandlerNode {
        long seen;

        @Override
        protected boolean handleEvent(Object event) {
            seen++;
            return true;
        }
    }

    private Count count;

    @Setup(Level.Trial)
    public void setUp() {
        publisher = new EventToQueuePublisher<>("orders");
        publisher.setEventWrapStrategy(EventSource.EventWrapStrategy.valueOf(wrap));
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(1024);
        publisher.addTargetQueue(queue, "orders-queue");
        agent = new EventQueueToEventProcessorAgent(queue, new EventToOnEventInvokeStrategy(), "bench/orders/onEvent");
        count = new Count();
        DefaultEventProcessor processor = new DefaultEventProcessor(count);
        processor.init();
        processor.start();
        agent.registerProcessor(processor);
    }

    @Benchmark
    public long publishAndDispatch() {
        publisher.publish(payload);
        agent.doWork();
        return count.seen;
    }
}
