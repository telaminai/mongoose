package com.telamin.mongoose.control;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.mongoose.AddedProcessor;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.control.event.ReplyOutcome;
import com.telamin.mongoose.dutycycle.ProcessorRegistration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class FailedStartNameOwnershipTest {
    @Test
    void failedStartKeepsItsNameThroughTheReleaseCallback() throws Exception {
        AtomicReference<AddedProcessor> reentrant = new AtomicReference<>();
        MongooseServer server = new MongooseServer(new MongooseServerConfig()) {
            @Override public void processorDropped(String group, String name, DataFlow flow,
                                                    ProcessorRegistration registration) {
                super.processorDropped(group, name, flow, registration);
                reentrant.set(addProcessor(name, group, null, RepliesAndServiceLockReviewTest.Proc::new));
            }
        };
        server.init();
        server.start();
        try {
            var failed = server.addProcessor("p", "g", null, () -> new RepliesAndServiceLockReviewTest.Proc() {
                @Override public void start() { throw new IllegalStateException("injected start failure"); }
            });
            assertEquals(ReplyOutcome.FAILED, failed.reply().toCompletableFuture().get(5, TimeUnit.SECONDS).outcome());
            assertNotNull(reentrant.get());
            assertEquals(ReplyOutcome.REFUSED,
                    reentrant.get().reply().toCompletableFuture().get(5, TimeUnit.SECONDS).outcome(),
                    "ReleaseName, not the binding-release callback, must free admission");
            assertEquals(ReplyOutcome.APPLIED, server.addProcessor("p", "g", null,
                    RepliesAndServiceLockReviewTest.Proc::new).reply().toCompletableFuture()
                    .get(5, TimeUnit.SECONDS).outcome(), "the failed add must eventually release its name");
        } finally {
            server.stop();
        }
    }
}
