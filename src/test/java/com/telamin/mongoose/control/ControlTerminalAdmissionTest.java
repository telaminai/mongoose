package com.telamin.mongoose.control;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.mongoose.control.event.ReplyOutcome;
import com.telamin.mongoose.control.event.RunIntent;
import com.telamin.mongoose.control.validation.ControlEvidence;
import com.telamin.mongoose.control.validation.ControlInvariants;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;
import com.telamin.mongoose.dutycycle.ProcessorRegistration;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Terminal admission and death (design "Terminal admission and death", interleavings table): no request is orphaned
 * or answered twice when a group closes, closes itself, or dies.
 */
@Timeout(30)
class ControlTerminalAdmissionTest {

    static final class Proc implements DataFlow, Lifecycle {
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger tearDowns = new AtomicInteger();
        final Throwable startFailure;

        Proc(Throwable startFailure) {
            this.startFailure = startFailure;
        }

        @Override public void init() { }
        @Override public void addEventFeed(EventFeed f) { }
        @Override public void onEvent(Object e) { }
        @Override public void stop() { }
        @Override public void tearDown() { tearDowns.incrementAndGet(); }

        @Override
        public void start() {
            starts.incrementAndGet();
            if (startFailure instanceof RuntimeException r) throw r;
            if (startFailure instanceof Error e) throw e;
        }
    }

    private static ComposingEventProcessorAgent group(String name) {
        return new ComposingEventProcessorAgent(name, new EventFlowManager(), null, new DeadWheelScheduler(),
                new ConcurrentHashMap<>());
    }

    private static ControlReply reply(ControlRequest r) throws Exception {
        return r.reply().get(5, TimeUnit.SECONDS);
    }

    private static void assertAuditHolds(ComposingEventProcessorAgent group) {
        ControlAuditSink audit = group.control().audit();
        ControlInvariants.Result result = ControlInvariants.check(ControlEvidence.parse(audit.exportText(), audit.isComplete()));
        assertTrue(result.ok(), result.toString());
    }

    @Test
    void aSenderPausedBeforePublishingIsAnsweredWhenTheGroupCloses() throws Exception {
        ComposingEventProcessorAgent group = group("paused");
        group.onStart();
        ControlRequest paused = new ControlRequest(ControlRequest.Kind.RUN, "paused", RunIntent::new, (Runnable) () -> { });
        group.control().registerUnpublished(paused);
        group.onClose();
        assertEquals(ReplyOutcome.NOT_RUNNING, reply(paused).outcome(), "the sweep answers what was never published");
        assertEquals(0, group.control().outstanding());
        assertAuditHolds(group);
    }

    @Test
    void aRequestSentToAClosedGroupIsAnsweredNotRunningAtOnce() throws Exception {
        ComposingEventProcessorAgent group = group("closed");
        group.onStart();
        group.onClose();
        ControlRequest add = group.addNamedEventProcessor(() -> new NamedEventProcessor("late", new Proc(null)),
                new ProcessorRegistration("late"));
        assertEquals(ReplyOutcome.NOT_RUNNING, reply(add).outcome());
        assertEquals(0, group.control().outstanding());
    }

    @Test
    void requestsQueuedAtCloseAreAnsweredByTheRulesWithoutStartingAnything() throws Exception {
        ComposingEventProcessorAgent group = group("queued");
        group.onStart();
        Proc running = new Proc(null);
        ProcessorRegistration runningReg = new ProcessorRegistration("running");
        group.addNamedEventProcessor(() -> new NamedEventProcessor("running", running), runningReg);
        group.doWork();
        Proc queued = new Proc(null);
        ControlRequest add = group.addNamedEventProcessor(() -> new NamedEventProcessor("queued", queued),
                new ProcessorRegistration("queued"));
        ControlRequest stop = group.removeEventProcessorByName("running", runningReg, null);
        group.onClose();
        assertEquals(ReplyOutcome.NOT_RUNNING, reply(add).outcome(), "an add queued at close is refused");
        assertEquals(0, queued.starts.get(), "and its processor never starts");
        assertEquals(ReplyOutcome.APPLIED, reply(stop).outcome(), "a stop queued at close finds its processor stopped");
        assertEquals(1, running.tearDowns.get(), "the running processor was stopped by the close, once");
        assertEquals(0, group.control().outstanding());
        assertAuditHolds(group);
    }

    @Test
    void aGroupThatClosesItselfOnABootFailureAnswersLaterSendersNotRunning() throws Exception {
        ComposingEventProcessorAgent group = group("bootfail");     // no server: boot failures are fail-fast
        Proc bad = new Proc(new IllegalStateException("boot start refused"));
        Proc never = new Proc(null);
        group.addNamedEventProcessor(() -> new NamedEventProcessor("bad", bad), new ProcessorRegistration("bad"));
        ControlRequest queued = group.addNamedEventProcessor(() -> new NamedEventProcessor("never", never),
                new ProcessorRegistration("never"));
        AgentRunner runner = new AgentRunner(new BusySpinIdleStrategy(), t -> { }, null, group);
        AgentRunner.startOnThread(runner);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!runner.isClosed() && System.nanoTime() < until) Thread.sleep(1);
        assertTrue(runner.isClosed(), "onStart threw, so Agrona closed the group itself");
        assertEquals(ReplyOutcome.NOT_RUNNING, reply(queued).outcome(), "the add behind the failure was not started");
        assertEquals(0, never.starts.get());
        ControlRequest late = group.addNamedEventProcessor(() -> new NamedEventProcessor("late", new Proc(null)),
                new ProcessorRegistration("late"));
        assertEquals(ReplyOutcome.NOT_RUNNING, reply(late).outcome(), "a sender after the self-close is answered at once");
        assertEquals(0, group.control().outstanding());
        runner.close();
    }

    @Test
    void aGroupWhoseThreadDiesIsCleanedUpAndLaterSendersAreAnswered() throws Exception {
        ComposingEventProcessorAgent group = group("dies");
        Proc survivor = new Proc(null);
        group.addNamedEventProcessor(() -> new NamedEventProcessor("survivor", survivor), new ProcessorRegistration("survivor"));
        AgentRunner runner = new AgentRunner(new BusySpinIdleStrategy(), t -> { }, null, group);
        AgentRunner.startOnThread(runner);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (survivor.starts.get() == 0 && System.nanoTime() < until) Thread.sleep(1);
        // an effect that throws a fatal error: the group goes Dead and its thread exits without onClose
        ControlRequest fatal = group.addNamedEventProcessor(
                () -> new NamedEventProcessor("fatal", new Proc(new StackOverflowError("simulated fatal start"))),
                new ProcessorRegistration("fatal"));
        assertEquals(ReplyOutcome.FAILED, reply(fatal).outcome(), "the request that hit the fatal error is answered");
        Thread agent = group.control().agentThread();
        agent.join(5_000);
        assertFalse(agent.isAlive(), "the agent thread died");
        assertEquals(ControlDriver.GroupState.DEAD, group.control().state());
        ControlRequest late = group.addNamedEventProcessor(() -> new NamedEventProcessor("late", new Proc(null)),
                new ProcessorRegistration("late"));
        assertEquals(ReplyOutcome.NOT_RUNNING, reply(late).outcome(), "a sender to a dead group is answered at once");
        runner.close();                      // returns at once: Agrona never runs onClose for a dead thread
        assertEquals(0, survivor.tearDowns.get(), "nothing released the survivor yet");
        group.cleanupAfterDeath();
        assertEquals(1, survivor.tearDowns.get(), "post-death cleanup releases what the dead group held");
        assertEquals(0, group.control().outstanding());
    }

    @Test
    void aSenderThatFindsTheAgentThreadDeadRunsTheDeathPath() throws Exception {
        ControlAuditSink audit = new ControlAuditSink();
        ControlDriver driver = new ControlDriver("ghost", 64, effect -> { throw new AssertionError("no effect expected"); }, audit);
        Thread dead = new Thread(() -> { });
        dead.start();
        dead.join();
        driver.starting(dead);
        driver.running();
        ControlRequest r = driver.send(new ControlRequest(ControlRequest.Kind.RUN, "late", RunIntent::new, (Runnable) () -> { }));
        assertEquals(ReplyOutcome.NOT_RUNNING, reply(r).outcome());
        assertEquals(ControlDriver.GroupState.DEAD, driver.state());
        assertTrue(audit.exportText().contains("record: Dead"), "the death is on the fallback record");
    }
}
