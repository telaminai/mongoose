package com.telamin.mongoose.review77ext;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.control.event.RunIntent;
import com.telamin.mongoose.dispatch.EventToOnEventInvokeStrategy;
import com.telamin.mongoose.service.CallBackType;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Companion to {@link OpaquePeerRedeliveryTest} (external review 1, owner decision: never redeliver to a peer that
 * already handled the event): a handwritten thrower on an opaque reader is retried alone, resumed at its captured
 * position, so the healthy peer before it receives the event once.
 */
public class OpaqueHandwrittenPeerTest {

    static class FlakyHandwritten implements DataFlow {
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicInteger handled = new AtomicInteger();
        EventFeed feed;
        public void init() { }
        public void addEventFeed(EventFeed f) { feed = f; }
        public void start() { feed.subscribe(this, OpaquePeerRedeliveryTest.key()); }
        public void onEvent(Object event) {
            if (!(event instanceof RunIntent)) return;
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("transient handwritten failure");
            handled.incrementAndGet();
        }
    }

    @Test
    void aHandwrittenThrowerIsRetriedAloneAndItsHealthyPeerReceivesTheEventOnce() throws Exception {
        var config = new MongooseServerConfig();
        config.setShutdownTimeoutMillis(500);
        var server = new MongooseServer(config);
        server.setDefaultErrorHandler(Throwable::printStackTrace);
        server.registerEventMapperFactory(() -> new EventToOnEventInvokeStrategy() {
            @Override public int failedPosition() { return -1; }
            @Override public DataFlow failedProcessor() { return null; }
        }, CallBackType.ON_EVENT_CALL_BACK);
        var feed = new OpaquePeerRedeliveryTest.Feed();
        var healthy = new OpaquePeerRedeliveryTest.Healthy();
        var flaky = new FlakyHandwritten();
        server.registerEventSource("feed", feed);
        server.addEventProcessor("healthy", "g", null, () -> healthy);
        server.addEventProcessor("flaky", "g", null, () -> flaky);
        server.init();
        server.start();
        try {
            Thread.sleep(200);
            feed.publisher.publish(new RunIntent(1));
            long end = System.nanoTime() + 3_000_000_000L;
            while (flaky.handled.get() == 0 && System.nanoTime() < end) Thread.sleep(5);
            Thread.sleep(50);
            assertEquals(1, flaky.handled.get(), "the handwritten thrower is retried");
            assertEquals(2, flaky.attempts.get());
            assertEquals(1, healthy.calls.get(), "the peer that already handled the event never receives it again");
            assertTrue(server.controlGroupStatus("g").quarantinedProcessors().isEmpty());
        } finally {
            server.stop();
        }
    }
}
