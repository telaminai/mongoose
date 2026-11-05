package com.telamin.mongoose.control;

import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.control.event.ReplyOutcome;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;
import com.telamin.mongoose.dutycycle.ProcessorRegistration;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent review of the PR 76 fix commits: probes at the edges of the control-plane fixes (follow-up findings 2
 * and 7). Each test asserts the behaviour the fix claims; a failure at 0dea3b3 demonstrates the defect it names.
 */
@Timeout(30)
class IndependentReviewControlProbesTest {

    @AfterEach
    void clearMark() {
        AgentThreads.exit();
    }

    /**
     * Follow-up finding 7 said "the same issue affects configured void-call waits". Only stopProcessor(group, name,
     * waitMillis) got the single deadline: the void stopProcessor(group, name) still sends with no admission deadline
     * (Long.MAX_VALUE), so on a full mailbox it waits the separate 10 s full-mailbox budget before awaitWhereSafe even
     * starts the configured controlWaitMillis.
     */
    @Test
    void theVoidStopWithAConfiguredWaitMustIncludeMailboxAdmissionInItsDeadline() throws Exception {
        var config = new MongooseServerConfig();
        config.setControlWaitMillis(30);
        var server = new MongooseServer(config);
        server.init();
        server.start();
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
            long started = System.nanoTime();
            Future<?> stopped = caller.submit(() -> server.stopProcessor("g", "p"));
            // The deadline is what this test is about: the call must end within 1 s, not after the 10 s queue-full
            // budget. Adjusted to accept the refusal: a void stop that is not admitted within controlWaitMillis throws
            // IllegalStateException, as one refused for a full mailbox always has, rather than return as if it applied.
            try {
                stopped.get(1, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException refused) {
                assertTrue(refused.getCause() instanceof IllegalStateException
                        && refused.getCause().getMessage().contains("not admitted within its deadline"), refused.toString());
            } catch (java.util.concurrent.TimeoutException stillWaiting) {
                throw new AssertionError("a void stop with controlWaitMillis=30 must not spend the separate 10 s queue-full"
                        + " budget on admission (waited " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                        + " ms so far)");
            }
        } finally {
            release.countDown();
            caller.shutdown();
            caller.awaitTermination(15, TimeUnit.SECONDS);
            server.shutdown();
        }
    }

    /** A CaptureIo whose timer cannot take a task (as a scheduled executor that was shut down answers). */
    static final class TimerDownIo implements CaptureIo {
        final CaptureRig.ManualIo manual = new CaptureRig.ManualIo();
        boolean timerDown;

        @Override
        public void open(Runnable task) {
            manual.open(task);
        }

        @Override
        public void close(Runnable task) {
            manual.close(task);
        }

        @Override
        public Cancellable schedule(Runnable task, long millis) {
            if (timerDown) throw new java.util.concurrent.RejectedExecutionException("simulated: the timer is shut down");
            return manual.schedule(task, millis);
        }
    }

    /**
     * Follow-up finding 2's permit: openCapture takes a permit, then calls io.schedule(timeout) outside the try that
     * returns the permit when io.open is refused. A timer that refuses leaks the permit for the life of the group: no
     * physical operation exists, yet the group counts one in flight, and once the limit is reached every later audit
     * start is refused for "opens still in flight" that never were.
     */
    @Test
    void anOpenWhoseTimerRefusesMustReturnItsPermit() throws Exception {
        var io = new TimerDownIo();
        var resources = new CaptureRig.FakeResources();
        var group = new ComposingEventProcessorAgent("permits", new EventFlowManager(), null, new DeadWheelScheduler(),
                new ConcurrentHashMap<>());
        group.setCaptureIo(io);
        group.setAuditCaptureResources(resources);
        group.setCaptureOpenLimit(1);
        group.onStart();
        group.addNamedEventProcessor(() -> new NamedEventProcessor("p", new CaptureAsEffectsTest.Proc("p")), new ProcessorRegistration("p"));
        group.doWork();
        try {
            io.timerDown = true;
            var first = group.auditStart("p", null);
            group.doWork();
            assertTrue(first.reply().isDone(), "the start is answered");
            assertEquals(ReplyOutcome.FAILED, first.reply().join().outcome());
            assertTrue(io.manual.opens.isEmpty(), "no physical open was handed to a helper");
            assertEquals(0, group.captureOpensInFlight(),
                    "no physical open is in flight, yet the group still holds a permit for one");

            io.timerDown = false;
            var second = group.auditStart("p", null);
            group.doWork();
            assertEquals(1, io.manual.opens.size(), "with the timer back, the next start must reach a helper, but was refused: "
                    + second.reply().getNow(null));
        } finally {
            group.onClose();
            io.manual.runAll();
        }
    }
}
