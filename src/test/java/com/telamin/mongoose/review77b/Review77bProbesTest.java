package com.telamin.mongoose.review77b;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.audit.LogRecord;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.fluxtion.runtime.time.Clock;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.AuditCaptureConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.control.AgentThreads;
import com.telamin.mongoose.control.CaptureIo;
import com.telamin.mongoose.control.ControlAuditSink;
import com.telamin.mongoose.control.ControlDriver;
import com.telamin.mongoose.control.ControlReply;
import com.telamin.mongoose.control.ControlRequest;
import com.telamin.mongoose.control.event.FailureKind;
import com.telamin.mongoose.control.event.ReplyOutcome;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;
import com.telamin.mongoose.dutycycle.ProcessorRegistration;
import com.telamin.mongoose.internal.ChronicleAuditCaptureService;
import com.telamin.mongoose.internal.NoOpCountersService;
import com.telamin.mongoose.service.audit.AuditCaptureIdentity;
import com.telamin.mongoose.service.audit.AuditCaptureResources;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review of issue #77 round 2 (69e5ef3), adopted as permanent tests. Probes that asserted the guarantee are kept as
 * written (evidence now typed {@code EvidenceStatus}); probes whose fixed behaviour differs from the probe's assumption
 * (C3: documented semantics, B: the dead control plane is fenced, not released) are inverted to the fixed behaviour.
 */
@Timeout(60)
class Review77bProbesTest {

    @AfterEach
    void clear() { AgentThreads.exit(); }

    static class Proc implements DataFlow, Lifecycle {
        final List<LogRecordListener> listeners = new CopyOnWriteArrayList<>();
        /** Emulates Fluxtion's audited listener swap: the swap cycle's record goes to the NEW listener. */
        Runnable beforeSwapRecord;
        boolean writeSwapRecord;
        @Override public void init() { }
        @Override public void addEventFeed(EventFeed f) { }
        @Override public void onEvent(Object e) { }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void tearDown() { }
        @Override public void setAuditLogProcessor(LogRecordListener l) {
            listeners.add(l);
            if (writeSwapRecord) {
                if (beforeSwapRecord != null) beforeSwapRecord.run();
                l.processLogRecord(record("swap"));
            }
        }
        LogRecordListener current() { return listeners.get(listeners.size() - 1); }
    }

    static LogRecord record(String what) {
        LogRecord r = new LogRecord(Clock.DEFAULT_CLOCK);
        r.replaceBuffer("eventLogRecord:\n    probe: " + what + "\n");
        return r;
    }

    static final class ManualIo implements CaptureIo {
        final Deque<Runnable> tasks = new ArrayDeque<>();
        @Override public void open(Runnable task) { tasks.add(task); }
        @Override public void close(Runnable task) { tasks.add(task); }
        @Override public Cancellable schedule(Runnable task, long millis) { return () -> { }; }
        void runAll() { while (!tasks.isEmpty()) tasks.remove().run(); }
    }

    private static ComposingEventProcessorAgent group(String name) {
        return new ComposingEventProcessorAgent(name, new EventFlowManager(), null, new DeadWheelScheduler(),
                new ConcurrentHashMap<>());
    }

    private static void failOneAuditWrite(ControlAuditSink sink) { sink.processLogRecord(null); }

    // ---------------------------------------------------------------- C: UNVERIFIED on every reply in the span

    /** C1: a caller-side deadline cancel (ChronicleAuditCaptureService uses it) during a degraded span. */
    @Test
    void cancelledRequestDuringDegradedSpanIsMarkedUnverified() throws Exception {
        ComposingEventProcessorAgent g = group("c1");
        g.onStart();
        failOneAuditWrite(g.control().audit());
        assertTrue(g.control().audit().evidenceDegraded());
        ControlRequest add = g.addNamedEventProcessor(() -> new NamedEventProcessor("p", new Proc()),
                new ProcessorRegistration("p"));
        assertTrue(add.cancel(FailureKind.REFUSED_DEADLINE, "not applied within 5s"));
        ControlReply reply = add.reply().get(1, TimeUnit.SECONDS);
        assertEquals(com.telamin.mongoose.control.event.EvidenceStatus.UNVERIFIED, reply.evidence(), "a reply made during the degraded span: " + reply);
    }

    /** C2: server-authored replies of stopProcessor(group, name, wait) during a degraded span. */
    @Test
    void serverAuthoredStopRefusalDuringDegradedSpanIsMarkedUnverified() throws Exception {
        MongooseServer server = new MongooseServer(new MongooseServerConfig());
        server.init();
        server.start();
        try {
            server.addEventProcessor("p", "g", null, Proc::new);
            failOneAuditWrite(audit(server, "g"));
            assertTrue(server.controlGroupStatus("g").controlAuditDegraded());
            ControlReply reply = server.stopProcessor("g", "nobody", 1_000);
            assertEquals(FailureKind.REFUSED_STATE, reply.kind());
            assertEquals(com.telamin.mongoose.control.event.EvidenceStatus.UNVERIFIED, reply.evidence(), "a reply made during the degraded span: " + reply);
        } finally {
            server.stop();
        }
    }

    @SuppressWarnings("unchecked")
    private static ControlAuditSink audit(MongooseServer server, String group) throws Exception {
        var field = MongooseServer.class.getDeclaredField("composingEventProcessorAgents");
        field.setAccessible(true);
        var runners = (Map<String, com.telamin.mongoose.internal.ComposingEventProcessorAgentRunner>) field.get(server);
        return runners.get(group).group().control().audit();
    }

    /**
     * C3: the request's own record fails AFTER its reply was delivered (the record of the cycle that performs/closes the
     * reply). Does the reply still claim verified evidence while a record of that request is missing?
     */
    @Test
    void recordOfTheReplyingRequestLostAfterTheReplyIsDelivered() throws Exception {
        ComposingEventProcessorAgent g = group("c3");
        g.onStart();
        ControlAuditSink sink = g.control().audit();
        var f = ControlDriver.class.getDeclaredField("processor");
        f.setAccessible(true);
        Object processor = f.get(g.control());
        AtomicReference<ControlRequest> target = new AtomicReference<>();
        List<String> dropped = new ArrayList<>();
        List<String> afterReply = new ArrayList<>();
        LogRecordListener wrapper = rec -> {
            ControlRequest t = target.get();
            if (t != null && t.reply().isDone() && rec != null) {
                String text = rec.asCharSequence().toString();
                afterReply.add(text);
                if (dropped.isEmpty() && text.contains("requestId: " + t.requestId())) {
                    dropped.add(text);
                    sink.processLogRecord(null);   // this record is lost: the sink write fails
                    return;
                }
            }
            sink.processLogRecord(rec);
        };
        processor.getClass().getMethod("setAuditLogProcessor", LogRecordListener.class).invoke(processor, wrapper);
        ProcessorRegistration reg = new ProcessorRegistration("p");
        ControlRequest add = g.addNamedEventProcessor(() -> new NamedEventProcessor("p", new Proc()), reg);
        target.set(add);
        g.doWork();
        ControlReply reply = add.reply().get(1, TimeUnit.SECONDS);
        System.out.println("C3 reply=" + reply + "\nC3 records after reply=" + afterReply.size()
                + "\nC3 dropped=" + (dropped.isEmpty() ? "none" : dropped.get(0)));
        // R4, inverted to the documented semantics: evidence describes the audit when the reply was made, so a record
        // lost after delivery leaves the reply NOT_DEGRADED; the evidence itself can never pass
        assertFalse(dropped.isEmpty(), "the probe lost a record of the replying request");
        assertEquals(com.telamin.mongoose.control.event.EvidenceStatus.NOT_DEGRADED, reply.evidence());
        assertFalse(sink.isComplete());
        var result = com.telamin.mongoose.control.validation.ControlInvariants.check(
                com.telamin.mongoose.control.validation.ControlEvidence.parse(sink.exportText(), sink.isComplete()));
        assertFalse(result.verified(), "a lost record of the request never verifies: " + result);
    }

    // ---------------------------------------------------------------- D: Chronicle capture fail open, real queue

    private static Object onlyHandle(ChronicleAuditCaptureService svc) throws Exception {
        var f = ChronicleAuditCaptureService.class.getDeclaredField("openResources");
        f.setAccessible(true);
        Set<?> open = (Set<?>) f.get(svc);
        assertEquals(1, open.size());
        return open.iterator().next();
    }

    private static void closeQueue(Object handle) throws Exception {
        var q = handle.getClass().getDeclaredMethod("queue");
        q.setAccessible(true);
        ((AutoCloseable) q.invoke(handle)).close();
    }

    private record Rig(ComposingEventProcessorAgent group, ChronicleAuditCaptureService svc, ManualIo io,
                       ProcessorRegistration reg) { }

    private static Rig chronicleRig(Path dir, Proc proc) throws Exception {
        AuditCaptureConfig cfg = new AuditCaptureConfig();
        cfg.setEnabled(true);
        cfg.setDirectory(dir.toString());
        ChronicleAuditCaptureService svc = new ChronicleAuditCaptureService(cfg, NoOpCountersService.INSTANCE);
        ComposingEventProcessorAgent g = group("D");
        ManualIo io = new ManualIo();
        g.setCaptureIo(io);
        g.setAuditCaptureResources(svc);
        g.onStart();
        ProcessorRegistration reg = new ProcessorRegistration("p");
        ControlRequest r = g.addNamedEventProcessor(() -> new NamedEventProcessor("p", proc), reg);
        g.doWork();
        assertEquals(ReplyOutcome.APPLIED, r.reply().getNow(null).outcome());
        svc.attachScoped(proc, new AuditCaptureIdentity("D", "p", reg.generation()), rec -> { }, g::runOnAgentThread);
        return new Rig(g, svc, io, reg);
    }

    private static void work(Rig rig) throws Exception {
        for (int i = 0; i < 4; i++) { rig.io().runAll(); rig.group().doWork(); }
    }

    /** D1: a real Chronicle queue closed under a live recording: the next write fails. */
    @Test
    void realClosedChronicleQueueReportsCaptureWriteFailedAndKeepsTheProcessor(@TempDir Path dir) throws Exception {
        Proc proc = new Proc();
        Rig rig = chronicleRig(dir, proc);
        ControlRequest start = rig.group().auditStart("p", rig.reg());
        work(rig);
        assertEquals(ReplyOutcome.APPLIED, start.reply().getNow(null).outcome());
        LogRecordListener capture = proc.current();
        capture.processLogRecord(record("ok"));                 // control: a healthy write
        assertEquals(0, rig.group().captureWriteFailures());
        closeQueue(onlyHandle(rig.svc()));
        capture.processLogRecord(record("after close"));
        capture.processLogRecord(record("after close 2"));
        assertEquals(1, rig.group().captureWriteFailures(), "first write failure reaches the group, once");
        assertTrue(rig.group().isProcessorRegistered("p"), "fail open");
        assertTrue(rig.group().control().audit().records().stream()
                .anyMatch(r -> r.contains("record: CaptureWriteFailed") && r.contains("processorStopped: false")));
        rig.group().onClose();
        rig.io().runAll();
    }

    /**
     * D2: the first write of a recording happens inside installCapture (Fluxtion writes the listener-swap cycle's record
     * to the new listener), before observeWriteFailures registers the observer. If that write fails, is it reported?
     */
    @Test
    void firstWriteFailureDuringInstallIsReported(@TempDir Path dir) throws Exception {
        Proc proc = new Proc();
        Rig rig = chronicleRig(dir, proc);
        AtomicReference<Object> handle = new AtomicReference<>();
        proc.writeSwapRecord = true;
        proc.beforeSwapRecord = () -> {
            try {
                if (proc.listeners.size() == 1) closeQueue(onlyHandle(rig.svc()));   // only on the capture install
            } catch (Exception e) { throw new RuntimeException(e); }
        };
        ControlRequest start = rig.group().auditStart("p", rig.reg());
        work(rig);
        proc.writeSwapRecord = false;
        System.out.println("D2 start reply=" + start.reply().getNow(null));
        // the recording keeps failing afterwards too
        if (start.reply().getNow(null).applied()) proc.current().processLogRecord(record("later"));
        assertEquals(1, rig.group().captureWriteFailures(),
                "the recording's writes failed but the group was never told (observer registered after the first write)");
        rig.group().onClose();
        rig.io().runAll();
    }

    /** D3: a third-party observeWriteFailures that throws turns a successful install into a FAILED start. */
    @Test
    void throwingObserverRegistrationDoesNotFailASuccessfulInstall() throws Exception {
        ComposingEventProcessorAgent g = group("D3");
        ManualIo io = new ManualIo();
        g.setCaptureIo(io);
        AtomicInteger installs = new AtomicInteger(), uninstalls = new AtomicInteger(), closes = new AtomicInteger();
        g.setAuditCaptureResources(new AuditCaptureResources() {
            public Object openCapture(String name) { return new Object(); }
            public void installCapture(Object h, String name, DataFlow flow) { installs.incrementAndGet(); }
            public void uninstallCapture(Object h, String name, DataFlow flow) { uninstalls.incrementAndGet(); }
            public void closeCapture(Object h) { closes.incrementAndGet(); }
            @Override public void observeWriteFailures(Object r, Consumer<String> o) {
                throw new UnsupportedOperationException("diagnostics hook not supported");
            }
        });
        ProcessorRegistration reg = new ProcessorRegistration("p");
        g.addNamedEventProcessor(() -> new NamedEventProcessor("p", new Proc()), reg);
        g.onStart();
        g.doWork();
        ControlRequest start = g.auditStart("p", reg);
        for (int i = 0; i < 4; i++) { io.runAll(); g.doWork(); }
        ControlReply reply = start.reply().getNow(null);
        System.out.println("D3 reply=" + reply + " installs=" + installs + " uninstalls=" + uninstalls + " closes=" + closes);
        try {
            assertTrue(reply.applied() || installs.get() == uninstalls.get(),
                    "install succeeded, start answered " + reply + ", but the listener was never uninstalled (installs="
                            + installs + ", uninstalls=" + uninstalls + ", closes=" + closes + ")");
            assertTrue(reply.applied(), "a throwing diagnostics hook never fails a successful install: " + reply);
            assertEquals(1, g.captureUnobservable(), "the capture is recorded as unobservable");
            assertTrue(g.unacknowledgedCaptureFailures() != null, "and raises audit-capture health");
        } finally {
            g.onClose();
            io.runAll();
        }
    }

    /**
     * Round 4 (survivor x-report-on-attach-removed): a write failure that happens before any observer is attached is
     * reported when one attaches, once per recording.
     */
    @Test
    void aWriteFailureBeforeTheObserverAttachesIsReportedOnAttach(@TempDir Path dir) throws Exception {
        AuditCaptureConfig cfg = new AuditCaptureConfig();
        cfg.setEnabled(true);
        cfg.setDirectory(dir.toString());
        ChronicleAuditCaptureService svc = new ChronicleAuditCaptureService(cfg, NoOpCountersService.INSTANCE);
        Proc proc = new Proc();
        AuditCaptureIdentity identity = new AuditCaptureIdentity("attach", "p", 1);
        svc.attachScoped(proc, identity, rec -> { }, Runnable::run);
        Object handle = svc.openCaptureScoped(identity);
        svc.installCapture(handle, "p", proc);                 // installed with no observer
        closeQueue(handle);
        proc.current().processLogRecord(record("fails before any observer"));
        List<String> reported = new ArrayList<>();
        svc.observeWriteFailures(handle, reported::add);
        assertEquals(1, reported.size(), "the earlier failure is reported on attach: " + reported);
        proc.current().processLogRecord(record("fails again"));
        assertEquals(1, reported.size(), "once per recording");
        List<String> control = new ArrayList<>();
        svc.observeWriteFailures(handle, control::add);
        assertEquals(0, control.size(), "control: an already reported failure is not reported again");
    }

    /** D2 premise: does a real generated processor write the listener-swap cycle's record to the NEW listener? */
    @Test
    void generatedProcessorWritesTheSwapRecordToTheNewListener() throws Exception {
        ComposingEventProcessorAgent g = group("swap");
        g.onStart();
        var f = ControlDriver.class.getDeclaredField("processor");
        f.setAccessible(true);
        Object processor = f.get(g.control());
        List<String> got = new ArrayList<>();
        LogRecordListener counting = rec -> got.add(rec.asCharSequence().toString());
        processor.getClass().getMethod("setAuditLogProcessor", LogRecordListener.class).invoke(processor, counting);
        System.out.println("SWAP records to new listener during the swap: " + got.size() + " " + got);
        assertEquals(1, got.size());
    }

    /**
     * B: a control-audit listener that throws once (a documented 1.1.3 failed-cycle case) kills the control plane: the
     * group is DEAD, but its processors stay registered and the data path keeps running; stops are refused.
     */
    @Test
    void ruleFailureLeavesProcessorsRunningButUnstoppable() throws Exception {
        ComposingEventProcessorAgent g = group("B");
        g.onStart();
        ProcessorRegistration reg = new ProcessorRegistration("p");
        ControlRequest add = g.addNamedEventProcessor(() -> new NamedEventProcessor("p", new Proc()), reg);
        g.doWork();
        assertTrue(add.reply().getNow(null).applied());
        var f = ControlDriver.class.getDeclaredField("processor");
        f.setAccessible(true);
        Object processor = f.get(g.control());
        ControlAuditSink sink = g.control().audit();
        AtomicInteger throwNext = new AtomicInteger(0);
        LogRecordListener wrapper = rec -> {
            if (throwNext.getAndSet(0) == 1) throw new IllegalStateException("audit listener failed");
            sink.processLogRecord(rec);
        };
        processor.getClass().getMethod("setAuditLogProcessor", LogRecordListener.class).invoke(processor, wrapper);
        throwNext.set(1);
        ControlRequest stop = g.removeEventProcessorByName("p", reg, null);
        g.doWork();
        ControlRequest stop2 = g.removeEventProcessorByName("p", reg, null);
        g.doWork();
        System.out.println("B stop=" + stop.reply().getNow(null) + "\nB stop2=" + stop2.reply().getNow(null)
                + "\nB state=" + g.control().state() + " registered=" + g.isProcessorRegistered("p")
                + " sinkFailures=" + sink.sinkFailures() + " degraded=" + sink.evidenceDegraded());
        try {
            // R5, inverted to the fixed behaviour (owner confirmed: isolate, no automatic restart): the dead control plane
            // cannot release p, so p stays owned, but the group fences it from every input and timer
            assertEquals(ControlDriver.GroupState.DEAD, g.control().state());
            assertEquals(FailureKind.FAILED_RULE, stop.reply().getNow(null).kind());
            assertTrue(g.isProcessorRegistered("p"), "owned, never released by a dead control plane");
            g.doWork();
            assertTrue(sink.records().stream().anyMatch(r -> r.contains("record: DataPathFenced") && r.contains("processors: \"p\"")),
                    "the dead control plane fences p's data path: " + sink.records());
        } finally {
            g.onClose();
        }
    }

    /** C4: acknowledge, then the EvidenceDegraded record is evicted from the ring: can anything now PASS? */
    @Test
    void acknowledgementPlusEvictionNeverPermitsAPass(@TempDir Path dir) throws Exception {
        ControlAuditSink sink = new ControlAuditSink(3);
        failOneAuditWrite(sink);
        sink.acknowledgeEvidenceGap();
        for (int i = 0; i < 10; i++) sink.fallback("Note", "i", i);
        assertFalse(sink.records().stream().anyMatch(r -> r.contains("EvidenceDegraded")), "evicted");
        Path out = sink.export(dir.resolve("a.yaml"));
        var verification = com.telamin.mongoose.control.ControlAuditExport.verify(out);
        var result = com.telamin.mongoose.control.validation.ControlInvariants.check(
                com.telamin.mongoose.control.validation.ControlEvidence.parse(sink.exportText(), sink.isComplete()));
        System.out.println("C4 complete=" + sink.isComplete() + " verify=" + verification + " result=" + result);
        assertFalse(verification.permitsInvariantPass());
        assertFalse(result.verified());
    }
}
