package com.telamin.mongoose.review77;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.audit.EventLogControlEvent;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.control.AgentThreads;
import com.telamin.mongoose.control.event.FailureKind;
import com.telamin.mongoose.control.generated.GroupControlProcessor;
import com.telamin.mongoose.control.validation.ControlEvidence;
import com.telamin.mongoose.control.validation.ControlInvariants;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dispatch.ProcessorContext;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;
import com.telamin.mongoose.dutycycle.ProcessorRegistration;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/** Independent review probes for issue #77 M2 at 84ebc0f. Each asserts the documented guarantee. */
@Timeout(30)
class Review77ProbesTest {
    @AfterEach void clear() { ProcessorContext.removeCurrentProcessor(); AgentThreads.exit(); }

    static class Generated extends GroupControlProcessor {
        volatile boolean failAudit;
        Generated() {
            setAuditLogProcessor(record -> { if (failAudit) throw new IllegalStateException("injected audit failure"); });
            init();
            setAuditLogLevel(EventLogControlEvent.LogLevel.INFO);
        }
    }

    private static void pump(ComposingEventProcessorAgent group, java.util.ArrayDeque<Runnable> tasks) throws Exception {
        for (int i = 0; i < 8; i++) {
            while (!tasks.isEmpty()) tasks.remove().run();
            group.doWork();
        }
    }

    /** Doc: QUARANTINED = "a request addressed one"; EffectResult kind is copied into the rule's reply. */
    @Test void auditStartOnAQuarantinedProcessorIsClassifiedQuarantined() throws Exception {
        var group = new ComposingEventProcessorAgent("q", new EventFlowManager(), null, new DeadWheelScheduler(),
                new ConcurrentHashMap<>());
        var tasks = new java.util.ArrayDeque<Runnable>();
        group.setCaptureIo(new com.telamin.mongoose.control.CaptureIo() {
            public void open(Runnable task) { tasks.add(task); }
            public void close(Runnable task) { tasks.add(task); }
            public Cancellable schedule(Runnable task, long millis) { return () -> { }; }
        });
        group.setAuditCaptureResources(new com.telamin.mongoose.service.audit.AuditCaptureResources() {
            public Object openCapture(String name) { return new Object(); }
            public void installCapture(Object handle, String name, DataFlow flow) { }
            public void uninstallCapture(Object handle, String name, DataFlow flow) { }
            public void closeCapture(Object handle) { }
        });
        Generated flow = new Generated();
        var registration = new ProcessorRegistration("generated");
        group.addNamedEventProcessor(() -> new NamedEventProcessor("generated", flow), registration);
        group.onStart();
        try {
            flow.failAudit = true;
            var service = group.broadcastService(new Service<>(new Object(), Object.class, "extra"), true, 1);
            group.doWork();
            assertFalse(service.reply().join().applied());
            var start = group.auditStart("generated", registration);
            pump(group, tasks);
            var reply = start.reply().join();
            assertFalse(reply.applied());
            assertEquals(FailureKind.QUARANTINED, reply.kind(), reply.toString());
        } finally { group.onClose(); pump(group, tasks); }
    }

    private static String audit(String... records) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < records.length; i++) {
            out.append("---\neventLogRecord:\n    nodeLogs:\n        - node: {seq: ").append(i + 1).append(", ")
                    .append(records[i]).append("}\n");
        }
        return out.append("---\n").toString();
    }

    private static final String HEALTHY_TAXONOMY =
            audit("record: Input, requestId: 1, fact: Add, generation: 7, name: p",
                  "record: Decision, requestId: 1, rule: a, generation: 7, name: p, effects: '1.1 BuildAndStart(7)'",
                  "record: EffectOutcome, requestId: 1, opId: '1.1', effect: BuildAndStart, outcome: succeeded, generation: 7, name: p",
                  "record: Decision, requestId: 1, rule: b, generation: 7, name: p, effects: '1.2 Reply(7)'",
                  "record: Reply, requestId: 1, opId: '1.2', outcome: APPLIED, generation: 7, name: p, completed: true, kind: NONE");

    /** I18 in a taxonomy export: a driver-authored FAILED record that lost its kind must not silently fall back and pass. */
    @Test void taxonomyExportDriverRecordWithoutKindIsRejected() {
        String text = HEALTHY_TAXONOMY + "---\ncontrolFallback: {record: FAILED, fallbackSeq: 1, group: g, requestId: 9, "
                + "reason: 'control processor is wedged', replyAuthor: DRIVER}\n";
        var result = ControlInvariants.check(ControlEvidence.parse(text, true));
        assertTrue(result.violations().stream().anyMatch(v -> v.startsWith("I18 ")), result.toString());
    }

    /** I1: name-only stop resolution must not depend on the engine having written a name on its BuildAndStart outcome. */
    @Test void nameOnlyStopResolutionDoesNotDependOnEngineOutcomeName() {
        String text = audit(
                "record: Input, requestId: 1, fact: Add, generation: 7, name: p",
                "record: EffectOutcome, requestId: 1, opId: '1.1', effect: BuildAndStart, outcome: succeeded, generation: 7",
                "record: Input, requestId: 2, fact: Stop, generation: -1, name: p",
                "record: Decision, requestId: 2, rule: x, generation: 7, effects: ''",
                "record: Decision, requestId: 3, rule: y, generation: 7, resourceId: 1, effects: '3.1 InstallCapture(7)'");
        var result = ControlInvariants.check(ControlEvidence.parse(text, true));
        assertTrue(result.violations().stream().anyMatch(v -> v.startsWith("I1 ")), result.toString());
    }

    static final class Plain implements DataFlow, com.telamin.fluxtion.runtime.lifecycle.Lifecycle {
        @Override public void init() { }
        @Override public void addEventFeed(com.telamin.fluxtion.runtime.input.EventFeed f) { }
        @Override public void onEvent(Object e) { }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void tearDown() { }
    }

    /**
     * M5/M8/I19: the control processor DID request the uninstall; only the injected physical detach failed (live and
     * after release). The group-close retry of that retained handle is the documented bounded UninstallCapture retry,
     * not a resource "the control processor should have released". I19 must not call it an engine leak.
     */
    @Test void injectedPhysicalDetachFailureRetriedAtCloseIsNotAnEngineLeftover() throws Exception {
        var group = new ComposingEventProcessorAgent("detach", new EventFlowManager(), null, new DeadWheelScheduler(),
                new ConcurrentHashMap<>());
        var tasks = new java.util.ArrayDeque<Runnable>();
        group.setCaptureIo(new com.telamin.mongoose.control.CaptureIo() {
            public void open(Runnable task) { tasks.add(task); }
            public void close(Runnable task) { tasks.add(task); }
            public Cancellable schedule(Runnable task, long millis) { return () -> { }; }
        });
        var failUninstall = new java.util.concurrent.atomic.AtomicBoolean();
        group.setAuditCaptureResources(new com.telamin.mongoose.service.audit.AuditCaptureResources() {
            public Object openCapture(String name) { return new Object(); }
            public void installCapture(Object handle, String name, DataFlow flow) { }
            public void uninstallCapture(Object handle, String name, DataFlow flow) {
                if (failUninstall.get()) throw new IllegalStateException("injected detach failure");
            }
            public void closeCapture(Object handle) { }
        });
        var registration = new ProcessorRegistration("p");
        group.addNamedEventProcessor(() -> new NamedEventProcessor("p", new Plain()), registration);
        group.onStart();
        var start = group.auditStart("p", registration);
        pump(group, tasks);
        assertTrue(start.reply().join().applied());
        failUninstall.set(true);
        group.removeEventProcessorByName("p", registration, null);
        pump(group, tasks);
        assertEquals(1, group.captureResourcesHeld(), "failed detach retains ownership");
        failUninstall.set(false);
        group.onClose();
        pump(group, tasks);
        assertEquals(0, group.captureResourcesHeld());
        var audit = group.control().audit();
        var result = ControlInvariants.check(ControlEvidence.parse(audit.exportText(), audit.isComplete()));
        assertTrue(result.violations().stream().noneMatch(v -> v.startsWith("I19 ")), result.toString());
    }
}
