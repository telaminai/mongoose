package com.telamin.mongoose.review5;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.control.ControlDriver;
import com.telamin.mongoose.control.event.RunIntent;
import com.telamin.mongoose.control.generated.GroupControlProcessor;
import com.telamin.mongoose.dispatch.EventToOnEventInvokeStrategy;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import com.telamin.mongoose.dispatch.ProcessorContext;
import com.telamin.mongoose.dutycycle.ProcessorDispatchException;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventFlowService;
import com.telamin.mongoose.service.EventSourceKey;
import com.telamin.mongoose.service.EventSubscriptionKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review 5 (rc1 4924645). Contract: "Ordinary handwritten DataFlow retry/cleanup behaviour is unchanged."
 * With an opaque dispatcher (failedPosition() == -1) the queue agent marks the failure "unsafe" whenever ANY
 * generated processor shares the reader, even when the processor that threw is handwritten. A transient handwritten
 * failure that the retry policy would absorb instead quarantines a healthy generated peer (reason: the handwritten
 * exception) and terminates the group through the agent error handler (under bootServer: GlobalErrorHandler,
 * System.exit(-1)).
 */
@Timeout(30)
class OpaqueDispatchHandwrittenFailureTest {
    @AfterEach void clear() { ProcessorContext.removeCurrentProcessor(); }

    static EventSubscriptionKey<Object> key(String feed) {
        return new EventSubscriptionKey<>(new EventSourceKey<>(feed), CallBackType.ON_EVENT_CALL_BACK);
    }

    /** A healthy generated graph that never throws. */
    static class HealthyGenerated extends GroupControlProcessor {
        final AtomicInteger calls = new AtomicInteger();
        EventFeed feed;
        HealthyGenerated() { init(); }
        @Override public void onEvent(Object event) { calls.incrementAndGet(); super.onEvent(event); }
        @Override public void addEventFeed(EventFeed feed) { super.addEventFeed(feed); this.feed = feed; }
        @Override public void startComplete() { super.startComplete(); feed.subscribe(this, key("feed")); }
    }

    /** Handwritten: throws once (transient), then succeeds. The default retry policy absorbs this. */
    static class FlakyHandwritten implements DataFlow {
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicInteger handled = new AtomicInteger();
        EventFeed feed;
        public void init() { }
        public void onEvent(Object event) {
            if (!(event instanceof RunIntent)) return;
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("transient handwritten failure");
            handled.incrementAndGet();
        }
        public void addEventFeed(EventFeed f) { feed = f; }
        public void start() { feed.subscribe(this, key("feed")); }
    }

    static class Feed implements EventFlowService<RunIntent> {
        volatile EventToQueuePublisher<RunIntent> publisher;
        public void setEventToQueuePublisher(EventToQueuePublisher<RunIntent> p) { publisher = p; }
        public void subscribe(EventSubscriptionKey<RunIntent> key) { }
        public void unSubscribe(EventSubscriptionKey<RunIntent> key) { }
    }

    private void run(boolean withGeneratedPeer) throws Exception {
        var errors = new CopyOnWriteArrayList<Throwable>();
        var config = new MongooseServerConfig(); config.setShutdownTimeoutMillis(500);
        var server = new MongooseServer(config);
        server.setDefaultErrorHandler(errors::add);
        server.registerEventMapperFactory(() -> new EventToOnEventInvokeStrategy() {
            @Override public int failedPosition() { return -1; }
            @Override public DataFlow failedProcessor() { return null; }
        }, CallBackType.ON_EVENT_CALL_BACK);
        Feed feed = new Feed();
        server.registerEventSource("feed", feed);
        FlakyHandwritten flaky = new FlakyHandwritten();
        HealthyGenerated generated = new HealthyGenerated();
        server.addEventProcessor("flaky", "g", null, () -> flaky);
        if (withGeneratedPeer) server.addEventProcessor("generated", "g", null, () -> generated);
        server.init(); server.start();
        try {
            int listeners = withGeneratedPeer ? 2 : 1;
            await(() -> feed.publisher != null && !feed.publisher.getTargetQueues().isEmpty());
            Thread.sleep(200); // both processors subscribed
            feed.publisher.publish(new RunIntent(1));
            await(() -> flaky.handled.get() == 1 || !errors.isEmpty()
                    || !server.controlGroupStatus("g").quarantinedProcessors().isEmpty());
            Thread.sleep(200);
            var status = server.controlGroupStatus("g");
            System.out.println("REVIEW5 opaque withGeneratedPeer=" + withGeneratedPeer + " attempts=" + flaky.attempts.get()
                    + " handled=" + flaky.handled.get() + " state=" + status.state()
                    + " quarantined=" + status.quarantinedProcessors() + " errors=" + errors);
            if (!withGeneratedPeer) {
                // control: handwritten-only reader retries and recovers, group alive
                assertEquals(1, flaky.handled.get());
                assertEquals(ControlDriver.GroupState.RUNNING, status.state());
                assertTrue(errors.isEmpty());
            } else {
                // Confirms the defect: the handwritten failure is never retried, the healthy generated peer is
                // quarantined with the handwritten exception as its reason, and the group is terminated.
                assertEquals(0, flaky.handled.get(), "handwritten retry did not happen");
                assertTrue(status.quarantinedProcessors().containsKey("generated"), status.toString());
                // the healthy peer's recorded reason names no processor; the real cause is only on the wrapped exception
                assertTrue(status.quarantinedProcessors().get("generated").contains("processor=unknown"));
                assertTrue(errors.stream().anyMatch(e -> e.getCause() != null
                        && "transient handwritten failure".equals(e.getCause().getMessage())));
                assertTrue(errors.stream().anyMatch(e -> e instanceof ProcessorDispatchException),
                        "termination reaches the agent error handler (GlobalErrorHandler exits under bootServer)");
                assertEquals(ControlDriver.GroupState.DEAD, status.state());
            }
        } finally {
            server.stop();
        }
    }

    @Test void controlHandwrittenOnlyOpaqueReaderRetries() throws Exception { run(false); }

    @Test void handwrittenTransientFailureQuarantinesHealthyGeneratedPeerAndKillsGroup() throws Exception { run(true); }

    private static void await(BooleanSupplier ready) throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
    }
}
