package com.telamin.mongoose.replay;

import com.telamin.mongoose.service.admin.AdminCommandRequest;
import com.telamin.mongoose.service.admin.impl.AdminCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.telamin.mongoose.replay.AdminReviewRegressionTest.Block;
import static com.telamin.mongoose.replay.AdminReviewRegressionTest.PROCESSOR;
import static com.telamin.mongoose.replay.AdminReviewRegressionTest.Server;
import static com.telamin.mongoose.replay.AdminReviewRegressionTest.boot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regressions for the correction-round review of #48 at b4e80c1 (N1-N3), on real servers, through the fixture of
 * AdminReviewRegressionTest. Every wait is a latch, a thread-state condition or a bounded join of a caller.
 */
class AdminCorrectionRegressionTest {

    static final String BOUND = "mongoose.admin.completionTimeoutMs";

    @AfterEach
    void clearTheBound() {
        System.clearProperty(BOUND);
    }

    /** A caller of the template's own publishCommand(List), on its own thread, bounded. */
    record Publish(Thread thread) {
        boolean finishedWithin(long seconds) throws InterruptedException {
            thread.join(TimeUnit.SECONDS.toMillis(seconds));
            return !thread.isAlive();
        }

        void awaitWaiting() {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (thread.getState() != Thread.State.WAITING && thread.getState() != Thread.State.TIMED_WAITING
                    && thread.isAlive() && System.nanoTime() < deadline) Thread.onSpinWait();
            assertTrue(thread.getState() == Thread.State.WAITING || thread.getState() == Thread.State.TIMED_WAITING,
                    "the caller was admitted and is waiting for its command: " + thread.getState());
        }
    }

    static Publish publish(AdminCommand template, String arg) {
        Thread t = new Thread(() -> template.publishCommand(List.of("DEMO.slow", arg)), "DEMO-publisher-" + arg);
        t.setDaemon(true);
        t.start();
        return new Publish(t);
    }

    static boolean await(CountDownLatch latch) throws InterruptedException {
        return latch.await(5, TimeUnit.SECONDS);
    }

    // ---- N1: a cancelled command is not an invocation ---------------------------------------------------------

    @Test
    void n1_aCancelledCommand_isNeverRecordedAsAnInvocation_norReplayed() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        List<Object> cancelled;
        try (Server s = boot(ReplayConfig.record(Set.of(PROCESSOR), Map.of(), null, store))) {
            s.events().offer(new Block("DEMO-agent"));
            assertTrue(await(s.node().agentHeld), "the agent is held: nothing can be claimed");
            System.setProperty(BOUND, "100");
            AdminReviewRegressionTest.Call c = AdminReviewRegressionTest.call(s, "DEMO.ok");
            assertTrue(c.finishedWithin(10), "the caller is answered at the bound");
            cancelled = c.replies();
            System.clearProperty(BOUND);
            s.node().releaseAgent.countDown();
            assertEquals(List.of("DEMO-ok"), s.behindOnItsQueue("DEMO.ok"), "the barrier: queued behind it, and answered");
            assertEquals(1, s.node().okCalls.get(), "live, only the barrier ran");
        }
        assertTrue(cancelled.stream().anyMatch(r -> r.toString().contains("will not run")), "precondition, cancelled: " + cancelled);
        long invoked = store.entries(PROCESSOR).stream().filter(e -> e instanceof ReplayEntry.AdminInvoked).count();
        assertEquals(1, invoked, "only the command that ran is recorded as an invocation: " + store.entries(PROCESSOR));
        try (Server r = boot(ReplayConfig.replay(Set.of(PROCESSOR), Map.of(), null, store))) {
            r.node().releaseAgent.countDown();                   // the recorded Block replays too: let it pass
            r.awaitReplayDone();
            assertEquals(null, r.replayer().stopped(PROCESSOR));
            assertEquals(1, r.node().okCalls.get(), "the replay runs only the command that ran live");
        }
    }

    // ---- N2: each publish is its own invocation ----------------------------------------------------------------

    @Test
    void n2_aTimedOutTemplatesStillRunningCommand_neverCompletesItsNextPublish() throws Exception {
        try (Server s = boot(null)) {
            AdminCommand template = s.admin().registeredCommand("DEMO.slow");
            List<Object> replies = new CopyOnWriteArrayList<>();
            template.setOutput(replies::add);
            template.setErrOutput(o -> replies.add("ERR " + o));
            CountDownLatch release1 = new CountDownLatch(1), release2 = new CountDownLatch(1);
            s.node().slowHeld.put("1", release1);
            s.node().slowHeld.put("2", release2);
            s.node().slowStarted.put("1", new CountDownLatch(1));
            s.node().slowStarted.put("2", new CountDownLatch(1));
            try {
                System.setProperty(BOUND, "300");
                Publish first = publish(template, "1");
                assertTrue(await(s.node().slowStarted.get("1")), "the first command was claimed and is running");
                assertTrue(first.finishedWithin(10), "its caller timed out");
                System.setProperty(BOUND, "10000");
                Publish second = publish(template, "2");
                second.awaitWaiting();                           // admitted, its command queued behind the running one
                release1.countDown();                            // the first command completes, late
                assertTrue(await(s.node().slowStarted.get("2")), "the second command was claimed and is running");
                assertTrue(second.thread().isAlive(), "the second caller waits for its OWN command, not the first's completion");
                assertFalse(replies.contains("DEMO-reply-1"), "nothing from the first reaches a caller after its timeout: " + replies);
                release2.countDown();
                assertTrue(second.finishedWithin(10), "and returns once its own command completes");
                assertTrue(replies.contains("DEMO-reply-2"), "with its own reply: " + replies);
            } finally {
                release1.countDown();
                release2.countDown();
            }
        }
    }

    @Test
    void n2_aCancelledPublish_isNeverRevivedByTheNextOnTheSameTemplate() throws Exception {
        try (Server s = boot(null)) {
            AdminCommand template = s.admin().registeredCommand("DEMO.slow");
            List<Object> replies = new CopyOnWriteArrayList<>();
            template.setOutput(replies::add);
            template.setErrOutput(o -> replies.add("ERR " + o));
            s.events().offer(new Block("DEMO-agent"));
            assertTrue(await(s.node().agentHeld), "the agent is held: nothing can be claimed");
            System.setProperty(BOUND, "300");
            Publish first = publish(template, "1");
            assertTrue(first.finishedWithin(10), "the first caller is answered at the bound: its command was cancelled");
            System.setProperty(BOUND, "10000");
            // a separate request between them, on the same queue: its position shows WHICH queue slot runs. A cancelled
            // slot revived by the next publish would run first, carrying that publish's arguments
            List<Object> betweenReplies = new CopyOnWriteArrayList<>();
            AdminCommandRequest between = new AdminCommandRequest();
            between.setCommand("DEMO.slow");
            between.setArguments(List.of("X"));
            between.setOutput(betweenReplies::add);
            between.setErrOutput(o -> betweenReplies.add("ERR " + o));
            Thread x = new Thread(() -> s.admin().processAdminCommandRequest(between), "DEMO-publisher-X");
            x.setDaemon(true);
            x.start();
            new Publish(x).awaitWaiting();
            Publish second = publish(template, "2");
            second.awaitWaiting();                               // queued behind the cancelled, undrained item and X
            s.node().releaseAgent.countDown();
            assertTrue(second.finishedWithin(10), "the second completes");
            x.join(5_000);
            assertEquals(List.of("X", "2"), s.node().slowRuns,
                    "the cancelled slot never runs, not even revived as the second: each runs in its own place");
        }
    }

    @Test
    void n2_aTemplateAlreadyInUse_answersBusy_andRunsNothingForTheBusyCaller() throws Exception {   // admission, retained
        try (Server s = boot(null)) {
            AdminCommand template = s.admin().registeredCommand("DEMO.slow");
            List<Object> replies = new CopyOnWriteArrayList<>();
            template.setOutput(replies::add);
            template.setErrOutput(o -> replies.add("ERR " + o));
            CountDownLatch release1 = new CountDownLatch(1);
            s.node().slowHeld.put("1", release1);
            s.node().slowStarted.put("1", new CountDownLatch(1));
            try {
                Publish first = publish(template, "1");
                assertTrue(await(s.node().slowStarted.get("1")), "the first command is running, its caller waiting");
                Publish busy = publish(template, "2");
                assertTrue(busy.finishedWithin(10), "a second caller of the same template is answered");
                assertTrue(replies.contains("command is busy try again"), "busy: " + replies);
                release1.countDown();
                assertTrue(first.finishedWithin(10));
                assertEquals(List.of("1"), s.node().slowRuns, "nothing ran for the busy caller");
            } finally {
                release1.countDown();
            }
        }
    }

    // ---- N3: a reply consumer cannot defeat the bound ----------------------------------------------------------

    /** A reply consumer that blocks when the processor's agent thread calls it, as a slow transport would. */
    static Consumer<Object> blockingOnTheAgent(List<Object> into, CountDownLatch entered, CountDownLatch release) {
        return o -> {
            if (!Thread.currentThread().getName().startsWith("DEMO-caller")) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            into.add(o);
        };
    }

    void aBlockedConsumerNeverHoldsTheCaller(String command, boolean blockOutput) throws Exception {
        try (Server s = boot(null)) {
            List<Object> replies = new CopyOnWriteArrayList<>();
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AdminCommandRequest request = new AdminCommandRequest();
            request.setCommand(command);
            request.setArguments(List.of());
            Consumer<Object> blocking = blockingOnTheAgent(replies, entered, release);
            request.setOutput(blockOutput ? blocking : replies::add);
            request.setErrOutput(blockOutput ? o -> replies.add("ERR " + o) : blocking);
            System.setProperty(BOUND, "300");
            Thread caller = new Thread(() -> s.admin().processAdminCommandRequest(request), "DEMO-caller");
            caller.setDaemon(true);
            try {
                caller.start();
                assertTrue(await(entered), "the agent is inside the reply consumer, blocked");
                caller.join(5_000);
                assertFalse(caller.isAlive(), "the bound releases the caller while the reply consumer blocks: " + caller.getState());
            } finally {
                release.countDown();                             // cleanup: the blocked consumer is let go
                caller.join(5_000);
            }
        }
    }

    @Test
    void n3_aBlockedOutputConsumer_neverHoldsTheCallerPastTheBound() throws Exception {
        aBlockedConsumerNeverHoldsTheCaller("DEMO.ok", true);
    }

    @Test
    void n3_aBlockedErrorConsumer_neverHoldsTheCallerPastTheBound() throws Exception {
        aBlockedConsumerNeverHoldsTheCaller("DEMO.errReply", false);
    }

    @Test
    void n3_aReplySentAfterTheCommandCompleted_isSuppressed() throws Exception {       // an asynchronous reply
        try (Server s = boot(null)) {
            List<Object> replies = AdminReviewRegressionTest.command(s, "DEMO.async");
            assertTrue(replies.stream().anyMatch(r -> r.toString().contains("no handler replied")), replies.toString());
            s.node().releaseAsync.countDown();
            assertTrue(await(s.node().asyncReplied), "the late reply was attempted");
            assertFalse(replies.contains("DEMO-late"), "and did not reach the caller, whose command had completed: " + replies);
        }
    }
}
