package com.telamin.mongoose.control;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.control.event.FailureKind;
import com.telamin.mongoose.control.event.ReplyOutcome;
import com.telamin.mongoose.control.validation.ControlEvidence;
import com.telamin.mongoose.control.validation.ControlInvariants;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;
import com.telamin.mongoose.dutycycle.ProcessorRegistration;
import com.telamin.mongoose.service.health.HealthStatus;
import com.telamin.mongoose.service.health.MongooseHealthService;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #77 milestone 3, the audit-failure policy (owner confirmed): fail closed for the control audit (the group is
 * DEGRADED, it keeps accepting every request including stop and close, its replies are marked UNVERIFIED until the
 * evidence is exported or acknowledged, the oracle never passes the span); fail open for data capture (a write failure
 * never stops the processor, it raises health and a control audit record). Each test has its wrong-result control.
 */
@Timeout(30)
class AuditFailurePolicyTest {

    @AfterEach
    void clear() {
        AgentThreads.exit();
    }

    static final class Proc implements DataFlow, Lifecycle {
        @Override public void init() { }
        @Override public void addEventFeed(EventFeed f) { }
        @Override public void onEvent(Object e) { }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void tearDown() { }
    }

    private static ComposingEventProcessorAgent group(String name) {
        return new ComposingEventProcessorAgent(name, new EventFlowManager(), null, new DeadWheelScheduler(),
                new ConcurrentHashMap<>());
    }

    private static ControlReply reply(ControlRequest r) throws Exception {
        return r.reply().get(5, TimeUnit.SECONDS);
    }

    /** A record the sink cannot convert: the audit write fails (null makes asCharSequence throw). */
    private static void failOneAuditWrite(ControlAuditSink sink) {
        sink.processLogRecord(null);
    }

    @Test
    void theSinkDegradesOnceAndOnlyExportOrAcknowledgementEndsTheMarking(@TempDir Path dir) throws Exception {
        ControlAuditSink sink = new ControlAuditSink();
        assertFalse(sink.evidenceDegraded(), "control: a healthy sink is not degraded");
        failOneAuditWrite(sink);
        failOneAuditWrite(sink);
        assertTrue(sink.evidenceDegraded());
        assertFalse(sink.isComplete());
        assertEquals(1, sink.records().stream().filter(r -> r.contains("record: EvidenceDegraded")).count(),
                "one EvidenceDegraded record per degraded span: " + sink.records());
        sink.export(dir.resolve("audit.yaml"));
        assertFalse(sink.evidenceDegraded(), "exporting the evidence acknowledges what it contains");
        assertFalse(sink.isComplete(), "acknowledgement never makes the evidence whole");
        assertFalse(ControlAuditExport.verify(dir.resolve("audit.yaml")).permitsInvariantPass());
        failOneAuditWrite(sink);
        assertTrue(sink.evidenceDegraded(), "a later failure degrades again");
        sink.acknowledgeEvidenceGap();
        assertFalse(sink.evidenceDegraded());
    }

    @Test
    void aDegradedGroupAcceptsStopAndCloseAndMarksEveryReplyUnverified() throws Exception {
        ComposingEventProcessorAgent group = group("degraded");
        group.onStart();
        ProcessorRegistration before = new ProcessorRegistration("before");
        ControlRequest healthyAdd = group.addNamedEventProcessor(() -> new NamedEventProcessor("before", new Proc()), before);
        group.doWork();
        assertTrue(reply(healthyAdd).applied());
        assertFalse(reply(healthyAdd).madeWhileAuditDegraded(), "control: a reply before any audit failure is not marked");

        failOneAuditWrite(group.control().audit());
        ProcessorRegistration after = new ProcessorRegistration("after");
        ControlRequest add = group.addNamedEventProcessor(() -> new NamedEventProcessor("after", new Proc()), after);
        ControlRequest stop = group.removeEventProcessorByName("before", before, null);
        ControlRequest unknown = group.removeEventProcessorByName("nobody", null, null);
        group.doWork();
        for (ControlRequest r : new ControlRequest[]{add, stop}) {
            ControlReply answer = reply(r);
            assertEquals(ReplyOutcome.APPLIED, answer.outcome(), "a degraded group still applies: " + answer);
            assertEquals(com.telamin.mongoose.control.event.EvidenceStatus.UNVERIFIED, answer.evidence(), answer.toString());
            assertTrue(answer.madeWhileAuditDegraded());
        }
        assertEquals(FailureKind.REFUSED_STATE, reply(unknown).kind(), "the kind still says why");
        assertEquals(com.telamin.mongoose.control.event.EvidenceStatus.UNVERIFIED, reply(unknown).evidence());

        ControlInvariants.Result result = ControlInvariants.check(ControlEvidence.parse(group.control().audit().exportText(),
                group.control().audit().isComplete()));
        assertFalse(result.verified(), "the degraded span never passes: " + result);
        assertTrue(result.unverifiable().stream().anyMatch(u -> u.startsWith("I21 ")), result.toString());

        group.control().audit().acknowledgeEvidenceGap();
        ControlRequest afterAck = group.removeEventProcessorByName("after", after, null);
        group.doWork();
        assertTrue(reply(afterAck).applied());
        assertFalse(reply(afterAck).madeWhileAuditDegraded(), "acknowledged: later replies are no longer marked");
        group.onClose();
        assertEquals(ControlDriver.GroupState.CLOSED, group.control().state(), "a degraded group still closes");
    }

    @Test
    void aHealthyGroupsTraceStillVerifies() throws Exception {
        ComposingEventProcessorAgent group = group("healthy");
        group.onStart();
        ProcessorRegistration reg = new ProcessorRegistration("p");
        ControlRequest add = group.addNamedEventProcessor(() -> new NamedEventProcessor("p", new Proc()), reg);
        group.doWork();
        assertFalse(reply(add).madeWhileAuditDegraded());
        group.onClose();
        ControlInvariants.Result result = ControlInvariants.check(ControlEvidence.parse(group.control().audit().exportText(),
                group.control().audit().isComplete()));
        assertTrue(result.verified(), "control for I21: " + result);
    }

    @Test
    void serverHealthAndStatusReportTheDegradedControlAudit() throws Exception {
        MongooseServer server = new MongooseServer(new MongooseServerConfig());
        server.init();
        server.start();
        try {
            server.addEventProcessor("p", "g", null, Proc::new);
            MongooseHealthService health = (MongooseHealthService) server.registeredServices()
                    .get(MongooseHealthService.SERVICE_NAME).instance();
            assertEquals(HealthStatus.Verdict.UP, health.statusOfCheck("group:g", "control-audit").verdict());
            assertFalse(server.controlGroupStatus("g").controlAuditDegraded());
            failOneAuditWrite(controlAudit(server, "g"));
            assertEquals(HealthStatus.Verdict.DEGRADED, awaitVerdict(health, HealthStatus.Verdict.DEGRADED));
            assertTrue(server.controlGroupStatus("g").controlAuditDegraded());
            ControlReply stop = server.stopProcessor("g", "p", 1_000);
            assertEquals(ReplyOutcome.APPLIED, stop.outcome(), "stop always accepted when degraded: " + stop);
            assertEquals(com.telamin.mongoose.control.event.EvidenceStatus.UNVERIFIED, stop.evidence());
            server.acknowledgeControlAuditGap("g");
            assertEquals(HealthStatus.Verdict.UP, awaitVerdict(health, HealthStatus.Verdict.UP));
        } finally {
            server.stop();
        }
    }

    /** Health evaluations are cached (1 s by default): wait for the verdict to be re-evaluated. */
    private static HealthStatus.Verdict awaitVerdict(MongooseHealthService health, HealthStatus.Verdict wanted)
            throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        HealthStatus.Verdict now;
        do {
            now = health.statusOfCheck("group:g", "control-audit").verdict();
            if (now == wanted) return now;
            Thread.sleep(50);
        } while (System.nanoTime() < until);
        return now;
    }

    @SuppressWarnings("unchecked")
    private static ControlAuditSink controlAudit(MongooseServer server, String group) throws Exception {
        var field = MongooseServer.class.getDeclaredField("composingEventProcessorAgents");
        field.setAccessible(true);
        var runners = (java.util.Map<String, com.telamin.mongoose.internal.ComposingEventProcessorAgentRunner>) field.get(server);
        return runners.get(group).group().control().audit();
    }

    @Test
    void aCaptureWriteFailureNeverStopsTheProcessorButIsRecordedAndRaisesHealth() throws Exception {
        ComposingEventProcessorAgent group = group("capture");
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        AtomicReference<Consumer<String>> observer = new AtomicReference<>();
        group.setCaptureIo(new CaptureIo() {
            public void open(Runnable task) { tasks.add(task); }
            public void close(Runnable task) { tasks.add(task); }
            public Cancellable schedule(Runnable task, long millis) { return () -> { }; }
        });
        group.setAuditCaptureResources(new com.telamin.mongoose.service.audit.AuditCaptureResources() {
            public Object openCapture(String name) { return new Object(); }
            public void installCapture(Object handle, String name, DataFlow flow) { }
            public void uninstallCapture(Object handle, String name, DataFlow flow) { }
            public void closeCapture(Object handle) { }
            @Override public void observeWriteFailures(Object resource, Consumer<String> o) { observer.set(o); }
        });
        ProcessorRegistration reg = new ProcessorRegistration("p");
        group.addNamedEventProcessor(() -> new NamedEventProcessor("p", new Proc()), reg);
        group.onStart();
        try {
            ControlRequest start = group.auditStart("p", reg);
            for (int i = 0; i < 4; i++) { while (!tasks.isEmpty()) tasks.remove().run(); group.doWork(); }
            assertTrue(reply(start).applied());
            assertEquals(0, group.captureWriteFailures(), "control: nothing failed yet");
            assertTrue(observer.get() != null, "the group observes the installed capture's writes");
            observer.get().accept("injected write failure");
            assertEquals(1, group.captureWriteFailures());
            assertTrue(group.isProcessorRegistered("p"), "fail open: the processor keeps running");
            assertTrue(group.control().audit().records().stream().anyMatch(r -> r.contains("record: CaptureWriteFailed")
                    && r.contains("processorStopped: false")), group.control().audit().records().toString());
            assertFalse(group.control().audit().evidenceDegraded(), "a capture failure does not degrade the control audit");
            // review R10: acknowledgement clears the alert, never the gap
            assertTrue(group.unacknowledgedCaptureFailures() != null, "the audit-capture alert is raised");
            group.acknowledgeCaptureFailures();
            assertEquals(null, group.unacknowledgedCaptureFailures(), "acknowledged: the alert clears");
            assertEquals(1, group.captureWriteFailures(), "the failure stays counted");
            observer.get().accept("a second injected write failure");
            assertTrue(group.unacknowledgedCaptureFailures() != null, "a new failure raises the alert again");
        } finally {
            group.onClose();
            while (!tasks.isEmpty()) tasks.remove().run();
        }
    }
}
