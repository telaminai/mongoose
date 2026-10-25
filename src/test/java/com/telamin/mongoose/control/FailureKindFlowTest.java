package com.telamin.mongoose.control;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.mongoose.StopResult;
import com.telamin.mongoose.control.event.FailureKind;
import com.telamin.mongoose.control.event.ReplyOutcome;
import com.telamin.mongoose.control.event.RunIntent;
import com.telamin.mongoose.control.validation.ControlEvidence;
import com.telamin.mongoose.control.validation.ControlInvariants;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;
import com.telamin.mongoose.dutycycle.ProcessorRegistration;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #77 milestone 2: the failure taxonomy is authored where each outcome is decided and reaches the caller's
 * {@link ControlReply}, the exported control audit (field {@code kind}) and {@link StopResult}. Every assertion here
 * classifies by kind; none reads reason text.
 */
@Timeout(30)
class FailureKindFlowTest {

    @AfterEach
    void clearAgentMark() {
        AgentThreads.exit();
    }

    static final class Proc implements DataFlow, Lifecycle {
        final boolean failStart;

        Proc(boolean failStart) {
            this.failStart = failStart;
        }

        @Override public void init() { }
        @Override public void addEventFeed(EventFeed f) { }
        @Override public void onEvent(Object e) { }
        @Override public void stop() { }
        @Override public void tearDown() { }

        @Override
        public void start() {
            if (failStart) throw new IllegalStateException("injected start failure");
        }
    }

    private static ComposingEventProcessorAgent group(String name) {
        return new ComposingEventProcessorAgent(name, new EventFlowManager(), null, new DeadWheelScheduler(),
                new ConcurrentHashMap<>());
    }

    private static ControlReply reply(ControlRequest r) throws Exception {
        return r.reply().get(5, TimeUnit.SECONDS);
    }

    private static ControlEvidence evidence(ComposingEventProcessorAgent group) {
        ControlAuditSink audit = group.control().audit();
        return ControlEvidence.parse(audit.exportText(), audit.isComplete());
    }

    @Test
    void ruleAuthoredRepliesCarryTheirKindToTheCallerAndTheAudit() throws Exception {
        ComposingEventProcessorAgent group = group("kinds");
        group.onStart();
        try {
            ControlRequest good = group.addNamedEventProcessor(() -> new NamedEventProcessor("good", new Proc(false)),
                    new ProcessorRegistration("good"));
            ControlRequest bad = group.addNamedEventProcessor(() -> new NamedEventProcessor("bad", new Proc(true)),
                    new ProcessorRegistration("bad"));
            ControlRequest unknown = group.removeEventProcessorByName("nobody", null, null);
            group.doWork();
            assertEquals(new Expect(ReplyOutcome.APPLIED, FailureKind.NONE), Expect.of(reply(good)));
            assertEquals(new Expect(ReplyOutcome.FAILED, FailureKind.FAILED_EFFECT), Expect.of(reply(bad)));
            assertEquals(new Expect(ReplyOutcome.REFUSED, FailureKind.REFUSED_STATE), Expect.of(reply(unknown)));

            ControlEvidence evidence = evidence(group);
            List<ControlEvidence.Rec> replies = evidence.ofType("Reply");
            assertEquals(3, replies.size(), replies.toString());
            for (ControlEvidence.Rec r : replies) {
                FailureKind kind = ControlInvariants.kindOf(r);
                assertTrue(kind != null, "every exported reply names its kind: " + r);
                assertEquals("APPLIED".equals(r.str("outcome")), kind == FailureKind.NONE, r.toString());
            }
            assertTrue(evidence.records().stream().anyMatch(r -> "EffectOutcome".equals(r.type())
                            && "BuildAndStart".equals(r.str("effect")) && ControlInvariants.kindOf(r) == FailureKind.FAILED_EFFECT),
                    "the failed effect's outcome record carries its kind");
            ControlInvariants.Result result = ControlInvariants.check(evidence);
            assertTrue(result.violations().stream().noneMatch(v -> v.startsWith("I20 ")), result.toString());
            assertTrue(result.verified(), result.toString());
        } finally {
            group.onClose();
        }
    }

    @Test
    void terminalRefusalsDistinguishAnOrderlyCloseFromADeadGroup() throws Exception {
        ComposingEventProcessorAgent closed = group("closed");
        closed.onStart();
        closed.onClose();
        ControlRequest late = closed.addNamedEventProcessor(() -> new NamedEventProcessor("late", new Proc(false)),
                new ProcessorRegistration("late"));
        assertEquals(new Expect(ReplyOutcome.NOT_RUNNING, FailureKind.REFUSED_STATE), Expect.of(reply(late)));
        assertTrue(evidence(closed).records().stream().anyMatch(r -> "NotRunning".equals(r.type())
                && ControlInvariants.kindOf(r) == FailureKind.REFUSED_STATE), "the fallback refusal names its kind");

        ComposingEventProcessorAgent dead = group("dead");
        dead.onStart();
        dead.control().markDead("injected death");
        ControlRequest afterDeath = dead.addNamedEventProcessor(() -> new NamedEventProcessor("late", new Proc(false)),
                new ProcessorRegistration("late"));
        assertEquals(new Expect(ReplyOutcome.NOT_RUNNING, FailureKind.FAILED_DEAD), Expect.of(reply(afterDeath)));
        ControlInvariants.Result result = ControlInvariants.check(evidence(dead));
        assertTrue(result.violations().stream().anyMatch(v -> v.startsWith("I18 ") && v.contains("FAILED_DEAD")),
                result.toString());
        dead.onClose();
    }

    @Test
    void admissionRefusalsAreClassifiedByCauseNotByMessage() throws Exception {
        ControlDriver driver = new ControlDriver("full", 2, effect -> null, new ControlAuditSink());
        ControlRequest refused = null;
        for (int i = 0; i < 64 && refused == null; i++) {
            ControlRequest r = new ControlRequest(ControlRequest.Kind.RUN, "fill " + i, RunIntent::new, (Runnable) () -> { });
            try {
                driver.trySend(r);
            } catch (IllegalStateException full) {
                refused = r;
            }
        }
        assertTrue(refused != null, "the mailbox filled");
        assertEquals(new Expect(ReplyOutcome.REFUSED, FailureKind.REFUSED_ADMISSION), Expect.of(reply(refused)));

        ControlRequest late = driver.send(new ControlRequest(ControlRequest.Kind.RUN, "late", RunIntent::new,
                (Runnable) () -> { }), System.nanoTime());
        assertEquals(new Expect(ReplyOutcome.REFUSED, FailureKind.REFUSED_DEADLINE), Expect.of(reply(late)));
        assertTrue(ControlEvidence.parse(driver.audit().exportText(), true).records().stream()
                .anyMatch(r -> "Refused".equals(r.type()) && ControlInvariants.kindOf(r) == FailureKind.REFUSED_DEADLINE));

        ControlRequest withdrawn = new ControlRequest(ControlRequest.Kind.RUN, "withdrawn", RunIntent::new, null);
        withdrawn.assignId(99);
        assertTrue(withdrawn.cancel(FailureKind.REFUSED_DEADLINE, "caller deadline passed"));
        assertEquals(FailureKind.REFUSED_DEADLINE, reply(withdrawn).kind());
        driver.close();
    }

    @Test
    void theKindIsNeverNullAndThePreTaxonomyConstructorUsesTheOutcomesBroadestClass() {
        assertEquals(FailureKind.NONE, new ControlReply(1, ReplyOutcome.APPLIED, "ok", -1, null).kind());
        assertEquals(FailureKind.REFUSED_STATE, new ControlReply(1, ReplyOutcome.REFUSED, "x", -1, null).kind());
        assertEquals(FailureKind.REFUSED_STATE, new ControlReply(1, ReplyOutcome.NOT_RUNNING, "x", -1, null).kind());
        assertEquals(FailureKind.FAILED_EFFECT, new ControlReply(1, ReplyOutcome.FAILED, "x", -1, null).kind());
        assertEquals(FailureKind.FAILED_EFFECT, new ControlReply(1, ReplyOutcome.FAILED, "x", -1, null, null).kind());
        assertTrue(FailureKind.FAILED_RULE.engineSafetyNet() && FailureKind.FAILED_DRIVER_FALLBACK.engineSafetyNet());
        assertFalse(FailureKind.FAILED_EFFECT.engineSafetyNet());
        assertTrue(FailureKind.REFUSED_DEADLINE.refusal());
        assertEquals(null, FailureKind.parse("NOT_A_KIND"));
        assertThrows(NullPointerException.class, () -> FailureKind.valueOf(null));
    }

    @Test
    void stopResultClassifiesItselfInTheSameTaxonomy() {
        assertEquals(FailureKind.NONE, StopResult.completed().failureKind());
        assertEquals(FailureKind.DETACHED, StopResult.handedOffToStopThread().failureKind());
        assertEquals(FailureKind.FAILED_SHUTDOWN, StopResult.incomplete(null, List.of("g"), List.of()).failureKind());
    }

    private record Expect(ReplyOutcome outcome, FailureKind kind) {
        static Expect of(ControlReply reply) {
            return new Expect(reply.outcome(), reply.kind());
        }
    }
}
