package com.telamin.mongoose.review77ext;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.control.event.RunIntent;
import com.telamin.mongoose.dispatch.EventToOnEventInvokeStrategy;
import com.telamin.mongoose.dispatch.ProcessorContext;
import com.telamin.mongoose.service.CallBackType;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * External re-verification A (written from the reviewer's description): a custom strategy that records the thrower's
 * identity but not its position. Owner decision: never redeliver to a peer that already handled the event; a handwritten
 * thrower is retried alone and the event skipped for the rest, as for a generated thrower. A strategy that records
 * nothing keeps the documented legacy whole-dispatch retry (control).
 */
public class IdentityOnlyRecoveryFollowupOwnTest {

    /** Dispatches without the base loop; records the thrower's identity only when {@code record} is set. */
    static EventToOnEventInvokeStrategy strategy(boolean record) {
        return new EventToOnEventInvokeStrategy() {
            @Override public int failedPosition() { return -1; }
            @Override public DataFlow failedProcessor() { return null; }
            @Override public void processEvent(Object event) {
                for (DataFlow target : eventProcessorSinks) {
                    try {
                        target.onEvent(event);
                    } catch (RuntimeException thrown) {
                        if (record) ProcessorContext.recordFailedProcessor(target);
                        throw thrown;
                    }
                }
            }
        };
    }

    private int healthyCalls(boolean record, Supplier<DataFlow> second, String secondName, boolean expectQuarantine)
            throws Exception {
        var config = new MongooseServerConfig();
        config.setShutdownTimeoutMillis(500);
        var server = new MongooseServer(config);
        server.setDefaultErrorHandler(Throwable::printStackTrace);
        server.registerEventMapperFactory(() -> strategy(record), CallBackType.ON_EVENT_CALL_BACK);
        var feed = new OpaquePeerRedeliveryTest.Feed();
        var healthy = new OpaquePeerRedeliveryTest.Healthy();
        server.registerEventSource("feed", feed);
        server.addEventProcessor("healthy", "g", null, () -> healthy);
        server.addEventProcessor(secondName, "g", null, second);
        server.init();
        server.start();
        try {
            Thread.sleep(200);
            feed.publisher.publish(new RunIntent(1));
            Thread.sleep(400);
            assertEquals(expectQuarantine, server.controlGroupStatus("g").quarantinedProcessors().containsKey(secondName),
                    server.controlGroupStatus("g").toString());
            return healthy.calls.get();
        } finally {
            server.stop();
        }
    }

    @Test
    void identityOnlyHandwrittenFailureMustNotRedeliverHealthyPeers() throws Exception {
        var flaky = new OpaqueHandwrittenPeerTest.FlakyHandwritten();
        assertEquals(1, healthyCalls(true, () -> flaky, "flaky", false), "the healthy peer receives the event once");
        assertEquals(1, flaky.handled.get(), "the handwritten thrower alone is retried and handles it");
        assertEquals(2, flaky.attempts.get());
    }

    @Test
    void identityOnlyGeneratedFailureIsQuarantinedWithoutRedelivery() throws Exception {
        assertEquals(1, healthyCalls(true, OpaquePeerRedeliveryTest.BrokenGenerated::new, "broken", true));
    }

    /** Control: a wholly uninstrumented strategy keeps the documented legacy whole-dispatch retry. */
    @Test
    void anUninstrumentedHandwrittenFailureKeepsTheDocumentedLegacyRetry() throws Exception {
        var flaky = new OpaqueHandwrittenPeerTest.FlakyHandwritten();
        int calls = healthyCalls(false, () -> flaky, "flaky", false);
        assertEquals(2, calls, "legacy whole-dispatch retry redelivers to the peer, as documented");
        assertTrue(flaky.handled.get() == 1);
    }
}
