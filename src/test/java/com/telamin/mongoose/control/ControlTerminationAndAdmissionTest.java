package com.telamin.mongoose.control;

import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.control.event.CaptureCompletion;
import com.telamin.mongoose.control.event.ReplyOutcome;
import com.telamin.mongoose.control.event.RunIntent;
import com.telamin.mongoose.control.validation.ControlEvidence;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;
import com.telamin.mongoose.dutycycle.ProcessorRegistration;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class ControlTerminationAndAdmissionTest {
    private static ComposingEventProcessorAgent group(String name) {
        return new ComposingEventProcessorAgent(name, new EventFlowManager(), null,
                new DeadWheelScheduler(), new ConcurrentHashMap<>());
    }

    @Test void errorHandlerDeathCompletesClaimsWithoutAnotherSenderAndAllowsReentrantCompletion() throws Exception {
        var group = group("error-handler-death");
        var io = new CaptureRig.ManualIo();
        group.setCaptureIo(io);
        group.setAuditCaptureResources(new CaptureRig.FakeResources());
        var registration = new ProcessorRegistration("p");
        var add = group.addNamedEventProcessor(() -> new NamedEventProcessor("p", new RepliesAndServiceLockReviewTest.Proc()), registration);
        var crash = new AtomicBoolean();
        Agent outsideWrapper = new Agent() {
            public String roleName() { return group.roleName(); }
            public void onStart() { group.onStart(); }
            public void onClose() { group.onClose(); }
            public int doWork() throws Exception {
                int work = group.doWork();
                if (crash.get()) throw new IllegalStateException("failure outside the group's wrapper");
                return work;
            }
        };
        var runner = new AgentRunner(new BusySpinIdleStrategy(), failure -> {
            throw new IllegalStateException("error handler also failed", failure);
        }, null, outsideWrapper);
        var callbackEntered = new CountDownLatch(1);
        var releaseCallback = new CountDownLatch(1);
        ExecutorService cleanup = Executors.newSingleThreadExecutor();
        AgentRunner.startOnThread(runner);
        try {
            add.reply().get(2, TimeUnit.SECONDS);
            var first = group.auditStart("p", registration);
            var second = group.auditStart("p", registration);
            var callbacks = new AtomicInteger();
            first.onReply(() -> {
                callbacks.incrementAndGet();
                group.control().failClaimed("reentrant terminal completion");
                callbackEntered.countDown();
                try { releaseCallback.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                throw new IllegalStateException("legacy completion callback failed");
            });
            second.onReply(callbacks::incrementAndGet);
            group.control().send(new ControlRequest(ControlRequest.Kind.RUN, "fence", RunIntent::new,
                    (Runnable) () -> {})).reply().get(2, TimeUnit.SECONDS);
            crash.set(true);
            assertTrue(callbackEntered.await(2, TimeUnit.SECONDS));
            cleanup.submit(() -> {
                try { group.cleanupAfterDeath(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            }).get(250, TimeUnit.MILLISECONDS);
            assertFalse(first.reply().isDone(), "the callback is deliberately blocked outside the ownership lock");
            releaseCallback.countDown();
            assertEquals(ReplyOutcome.FAILED, first.reply().get(2, TimeUnit.SECONDS).outcome());
            assertEquals(ReplyOutcome.FAILED, second.reply().get(2, TimeUnit.SECONDS).outcome());
            assertEquals(2, callbacks.get(), "each callback runs once, including recursive failClaimed");
            assertEquals(ControlDriver.GroupState.DEAD, group.control().state());
            // The server may concurrently run its post-join completion path; it must not race the claimed HashMap.
            group.cleanupAfterDeath();
            assertEquals(2, callbacks.get());
        } finally {
            releaseCallback.countDown();
            cleanup.shutdown();
            cleanup.awaitTermination(2, TimeUnit.SECONDS);
            runner.close();
            group.cleanupAfterDeath();
            io.runAll();
        }
    }

    @Test void completionRacingCloseIsDispatchedOrOrphanedExactlyOnce() throws Exception {
        for (int round = 0; round < 20; round++) {
            var group = group("completion-close-" + round);
            var observed = new ConcurrentHashMap<Long, AtomicInteger>();
            group.control().onOrphanCompletion(input -> observed.computeIfAbsent(
                    ((CaptureCompletion) input).completionId(), key -> new AtomicInteger()).incrementAndGet());
            var firstHalf = new CountDownLatch(1);
            var race = new CountDownLatch(1);
            Thread producer = new Thread(() -> {
                for (int id = 0; id < 100; id++) {
                    if (id == 50) {
                        firstHalf.countDown();
                        try { race.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                    }
                    group.control().complete(new CaptureCompletion(id, id + ".1", id,
                            CaptureCompletion.Kind.OPEN_FAILED, -1, "synthetic failure"));
                }
            });
            producer.start();
            assertTrue(firstHalf.await(2, TimeUnit.SECONDS));
            race.countDown();
            group.onClose();
            producer.join(2000);
            assertFalse(producer.isAlive());
            var audit = group.control().audit();
            for (var record : ControlEvidence.parse(audit.exportText(), audit.isComplete()).ofType("Input")) {
                if ("CaptureOpenFailed".equals(record.str("fact"))) {
                    observed.computeIfAbsent(record.num("completionId"), key -> new AtomicInteger()).incrementAndGet();
                }
            }
            assertEquals(100, observed.size());
            assertTrue(observed.values().stream().allMatch(count -> count.get() == 1));
        }
    }

    @Test void voidAndAsyncCallsCannotSpendAnExtraQueueFullTimeout() throws Exception {
        var config = new MongooseServerConfig();
        config.setControlWaitMillis(30);
        var server = new MongooseServer(config);
        server.init(); server.start();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            server.addEventProcessor("p", "g", null, RepliesAndServiceLockReviewTest.Proc::new);
            var handle = server.processorHandle("g", "p");
            server.addProcessor("holder", "g", null, () -> new RepliesAndServiceLockReviewTest.Proc() {
                public void start() {
                    entered.countDown();
                    try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 4096; i++) server.addProcessor("queued" + i, "g", null, RepliesAndServiceLockReviewTest.Proc::new);
            var async = caller.submit(() -> server.addProcessor("refused", "g", null, RepliesAndServiceLockReviewTest.Proc::new))
                    .get(250, TimeUnit.MILLISECONDS);
            assertNull(async.handle());
            assertEquals(ReplyOutcome.REFUSED, async.reply().toCompletableFuture().get(2, TimeUnit.SECONDS).outcome());
            var stopped = caller.submit(() -> server.stopProcessor(handle)).get(250, TimeUnit.MILLISECONDS);
            assertEquals(ReplyOutcome.REFUSED, stopped.toCompletableFuture().get(2, TimeUnit.SECONDS).outcome());
            caller.submit(() -> assertThrows(IllegalStateException.class, () -> server.stopProcessor("g", "p")))
                    .get(250, TimeUnit.MILLISECONDS);
            caller.submit(() -> assertThrows(IllegalStateException.class,
                            () -> server.addEventProcessor("void-refused", "g", null, RepliesAndServiceLockReviewTest.Proc::new)))
                    .get(250, TimeUnit.MILLISECONDS);
            assertNotNull(server.processorHandle("g", "p"), "refusing a stop never releases the processor's name");
            assertNull(server.processorHandle("g", "refused"));
            assertNull(server.processorHandle("g", "void-refused"));
        } finally {
            release.countDown();
            caller.shutdown();
            caller.awaitTermination(5, TimeUnit.SECONDS);
            server.shutdown();
        }
    }

    @Test void admissionTimeIsDeductedFromTheRemainingVoidReplyWait() throws Exception {
        var config = new MongooseServerConfig();
        config.setControlWaitMillis(500);
        var server = new MongooseServer(config);
        server.init(); server.start();
        var firstEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var releaseSecond = new CountDownLatch(1);
        var calling = new CountDownLatch(1);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            server.addEventProcessor("p", "g", null, RepliesAndServiceLockReviewTest.Proc::new);
            server.addProcessor("first-holder", "g", null, () -> new RepliesAndServiceLockReviewTest.Proc() {
                public void start() {
                    firstEntered.countDown();
                    try { releaseFirst.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
            });
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
            // Releasing the first holder frees a mailbox slot, but this next processor prevents the stop executing.
            server.addProcessor("second-holder", "g", null, () -> new RepliesAndServiceLockReviewTest.Proc() {
                public void start() {
                    try { releaseSecond.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
            });
            for (int i = 0; i < 4095; i++) server.addProcessor("queued" + i, "g", null, RepliesAndServiceLockReviewTest.Proc::new);
            Future<?> stopped = caller.submit(() -> {
                calling.countDown();
                server.stopProcessor("g", "p");
            });
            assertTrue(calling.await(2, TimeUnit.SECONDS));
            Thread.sleep(350);
            releaseFirst.countDown();
            stopped.get(300, TimeUnit.MILLISECONDS); // 500ms total, not 350ms admission plus another 500ms
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
            caller.shutdown();
            caller.awaitTermination(5, TimeUnit.SECONDS);
            server.shutdown();
        }
    }
}
