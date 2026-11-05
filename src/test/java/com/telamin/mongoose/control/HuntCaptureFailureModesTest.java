package com.telamin.mongoose.control;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.mongoose.control.CaptureRig.FakeResources;
import com.telamin.mongoose.control.CaptureRig.ManualIo;
import com.telamin.mongoose.control.event.ReplyOutcome;
import com.telamin.mongoose.control.validation.ControlEvidence;
import com.telamin.mongoose.control.validation.ControlInvariants;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;
import com.telamin.mongoose.dutycycle.ProcessorRegistration;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Adversarial hunt, audit capture driven inline through a real group. A failing test demonstrates the failure mode. */
class HuntCaptureFailureModesTest {

    static class Proc implements DataFlow, Lifecycle {
        final String label;
        Proc(String label) { this.label = label; }
        @Override public void init() { }
        @Override public void addEventFeed(EventFeed f) { }
        @Override public void onEvent(Object e) { }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void tearDown() { }
        @Override public String toString() { return label; }
    }

    private final Map<String, ProcessorRegistration> reserved = new ConcurrentHashMap<>();
    private ComposingEventProcessorAgent group;
    private ManualIo io;
    private FakeResources resources;

    @BeforeEach
    void group() {
        group = new ComposingEventProcessorAgent("H", new EventFlowManager(), null, new DeadWheelScheduler(),
                new ConcurrentHashMap<>());
        io = new ManualIo();
        resources = new FakeResources();
        group.setCaptureIo(io);
        group.setAuditCaptureResources(resources);
        group.onStart();
    }

    @AfterEach
    void clearAgentMark() {
        AgentThreads.exit();
    }

    private ProcessorRegistration add(String name, Proc proc) throws Exception {
        ProcessorRegistration reg = new ProcessorRegistration(name);
        reg.nameReleaser(() -> reserved.remove(name, reg));
        assertNull(reserved.putIfAbsent(name, reg), "name free");
        ControlRequest r = group.addNamedEventProcessor(() -> new NamedEventProcessor(name, proc), reg);
        group.doWork();
        assertEquals(ReplyOutcome.APPLIED, r.reply().getNow(null).outcome());
        return reg;
    }

    private void work() throws Exception {
        for (int i = 0; i < 4; i++) group.doWork();
    }

    private ControlEvidence evidence(String exportName) throws Exception {
        ControlAuditSink audit = group.control().audit();
        audit.export(Path.of("target/control-audit/" + exportName + ".yaml"));
        return ControlEvidence.parse(audit.exportText(), audit.isComplete());
    }

    private static List<ControlEvidence.Rec> decisions(ControlEvidence evidence, String rule) {
        return evidence.ofType("Decision").stream().filter(d -> rule.equals(d.str("rule"))).toList();
    }

    /** Recording, then audit stop (the resource goes to the closer), then audit start again while it closes. */
    private ProcessorRegistration recordStopStartAgain() throws Exception {
        ProcessorRegistration reg = add("p", new Proc("p"));
        group.auditStart("p", reg);
        work();
        io.runOpen();
        work();                                   // Recording on resource 1
        ControlRequest stop = group.auditStop("p", reg);
        work();                                   // Uninstall, Close(1) handed to the closer, reply APPLIED
        assertEquals(ReplyOutcome.APPLIED, stop.reply().getNow(null).outcome());
        assertEquals(1, io.closes.size(), "the first resource is with the closer");
        ControlRequest again = group.auditStart("p", reg);
        work();                                   // Closing -> a second open; resource 1 is disowned
        assertFalse(again.reply().isDone());
        assertEquals(1, io.opens.size(), "a new open is with a helper");
        return reg;
    }

    /**
     * FM: the close of a capture whose owner started a new capture meanwhile is recorded against generation -1 and no
     * name, though the resource was opened, installed and uninstalled for that generation. The audit can no longer
     * attribute the close (an analyser query by generation or name misses it).
     */
    @Test
    void closeOfTheDisownedResourceMustStillNameItsGeneration() throws Exception {
        ProcessorRegistration reg = recordStopStartAgain();
        io.runClose();                            // resource 1 closes
        work();
        io.runOpen();                             // resource 2 opens and is installed
        work();
        group.onClose();
        io.runAll();
        ControlEvidence evidence = evidence("hunt-disowned-close");
        ControlInvariants.Result result = ControlInvariants.check(evidence);
        assertTrue(result.ok(), result.toString());
        assertEquals("", resources.leaks());
        List<ControlEvidence.Rec> closed = decisions(evidence, "captureClosed");
        assertEquals(1, closed.size(), closed.toString());
        ControlEvidence.Rec rec = closed.get(0);
        assertEquals(1, rec.num("resourceId"));
        assertEquals(reg.generation(), rec.num("generation"), "the close of resource 1 is attributed to its generation: " + rec);
        assertEquals("p", rec.str("name"), "and to its processor: " + rec);
    }

    /**
     * FM: a stop of a registration that has a new open in progress while its previous capture is still with the closer
     * reports only the open in pendingCleanup; the outstanding close of the previous resource is not listed although
     * the design says every outstanding resource work of a release is recorded as pending cleanup.
     */
    @Test
    void stopMustListEveryOutstandingResourceOperationAsPendingCleanup() throws Exception {
        ProcessorRegistration reg = recordStopStartAgain();
        ControlRequest stop = group.removeEventProcessorByName("p", reg, null);
        work();
        assertEquals(ReplyOutcome.APPLIED, stop.reply().getNow(null).outcome());
        ControlEvidence evidence = evidence("hunt-pending-cleanup");
        List<ControlEvidence.Rec> stops = decisions(evidence, "stopStarted");
        assertEquals(1, stops.size());
        String pending = stops.get(0).str("pendingCleanup");
        assertNotNull(pending, "the stop left resource work outstanding");
        // the audit stop's close of resource 1 (op <auditStopRequest>.2) and the second audit start's open (<again>.1)
        List<String> ops = List.of(pending.split(";"));
        long openOps = ops.stream().filter(op -> op.endsWith(".1")).count();
        long closeOps = ops.stream().filter(op -> op.endsWith(".2")).count();
        assertEquals(1, openOps, "the cancelled open is pending: " + pending);
        assertEquals(1, closeOps, "the previous resource's close, still with the closer, is pending too: " + pending);
        io.runAll();
        work();
        io.runAll();      // the late open's compensating close, requested by the work above
        work();
        group.onClose();
        io.runAll();
        assertEquals("", resources.leaks());
    }

    /** A timer that cannot be scheduled: the shared timer never refuses, an injected one may. */
    static final class NoTimerIo implements CaptureIo {
        final ManualIo inner = new ManualIo();
        boolean refuseSchedule;
        @Override public void open(Runnable task) { inner.open(task); }
        @Override public void close(Runnable task) { inner.close(task); }
        @Override
        public Cancellable schedule(Runnable task, long millis) {
            if (refuseSchedule) throw new java.util.concurrent.RejectedExecutionException("simulated: timer shut down");
            return inner.schedule(task, millis);
        }
    }

    /**
     * FM: when the open's timeout cannot be scheduled, the audit start is answered Failed but the in-flight permit taken
     * just before is never returned. After captureOpenLimit such refusals every later audit start is refused for
     * "opens still in flight" although no helper is running anything.
     */
    @Test
    void aRefusedTimeoutScheduleMustReturnTheOpenPermit() throws Exception {
        NoTimerIo noTimer = new NoTimerIo();
        group.setCaptureIo(noTimer);
        group.setCaptureOpenLimit(1);
        ProcessorRegistration reg = add("p", new Proc("p"));
        noTimer.refuseSchedule = true;
        ControlRequest first = group.auditStart("p", reg);
        work();
        assertEquals(ReplyOutcome.FAILED, first.reply().getNow(null).outcome());
        assertTrue(noTimer.inner.opens.isEmpty(), "nothing was handed to a helper");
        assertEquals(0, group.captureOpensInFlight(), "no physical open is in flight, so no permit is held");
        noTimer.refuseSchedule = false;
        ControlRequest second = group.auditStart("p", reg);
        work();
        assertEquals(1, noTimer.inner.opens.size(), "the next audit start must be able to open: " + second.reply().getNow(null));
    }
}
