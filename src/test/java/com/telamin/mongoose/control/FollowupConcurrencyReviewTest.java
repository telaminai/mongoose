package com.telamin.mongoose.control;

import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.control.event.RunIntent;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;
import com.telamin.mongoose.dutycycle.ProcessorRegistration;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class FollowupConcurrencyReviewTest {
    @Test void deathMustCompleteEarlierClaimedAsyncRequestWithoutAnotherServerCall() throws Exception {
        var group = new ComposingEventProcessorAgent("dies-pending", new EventFlowManager(), null,
                new DeadWheelScheduler(), new ConcurrentHashMap<>());
        var io = new CaptureRig.ManualIo();
        group.setCaptureIo(io);
        group.setAuditCaptureResources(new CaptureRig.FakeResources());
        var reg = new ProcessorRegistration("p");
        var add = group.addNamedEventProcessor(() -> new NamedEventProcessor("p", new RepliesAndServiceLockReviewTest.Proc()), reg);
        var runner = new AgentRunner(new BusySpinIdleStrategy(), t -> {}, null, group);
        AgentRunner.startOnThread(runner);
        try {
            add.reply().get(2, TimeUnit.SECONDS);
            var opening = group.auditStart("p", reg);
            var fence = group.control().send(new ControlRequest(ControlRequest.Kind.RUN, "fence", RunIntent::new, (Runnable) () -> {}));
            fence.reply().get(2, TimeUnit.SECONDS);
            assertFalse(opening.reply().isDone());
            var fatal = group.addNamedEventProcessor(() -> {
                throw new StackOverflowError("simulated fatal command after audit request");
            }, new ProcessorRegistration("fatal"));
            fatal.reply().get(2, TimeUnit.SECONDS);
            group.control().agentThread().join(2000);
            assertFalse(group.control().agentThread().isAlive());
            io.runAll();
            assertTrue(opening.reply().isDone(), "the group died and the open completed, but the earlier claimed reply is orphaned");
        } finally {
            runner.close();
            group.cleanupAfterDeath();
        }
    }

    @Test void adminStopDeadlineMustIncludeMailboxAdmission() throws Exception {
        var config = new MongooseServerConfig();
        config.setControlWaitMillis(30);
        var server = new MongooseServer(config);
        server.init(); server.start();
        var hold = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            server.addEventProcessor("p", "g", null, RepliesAndServiceLockReviewTest.Proc::new);
            server.addProcessor("holder", "g", null, () -> new RepliesAndServiceLockReviewTest.Proc() {
                public void start() {
                    hold.countDown();
                    try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
            });
            assertTrue(hold.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 4096; i++) server.addProcessor("queued" + i, "g", null, RepliesAndServiceLockReviewTest.Proc::new);
            Future<?> stopped = caller.submit(() -> server.stopProcessor("g", "p", 30));
            assertDoesNotThrow(() -> stopped.get(250, TimeUnit.MILLISECONDS),
                    "a 30ms admin stop must not spend the separate 10s queue-full deadline on admission");
        } finally {
            release.countDown();
            caller.shutdown();
            caller.awaitTermination(5, TimeUnit.SECONDS);
            server.shutdown();
        }
    }
}
