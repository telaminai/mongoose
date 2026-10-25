/*
 * SPDX-FileCopyrightText: © 2026 Telamin
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.extreview.capture;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.mongoose.config.AuditCaptureConfig;
import com.telamin.mongoose.control.AgentThreads;
import com.telamin.mongoose.control.CaptureIo;
import com.telamin.mongoose.control.ControlRequest;
import com.telamin.mongoose.control.event.ReplyOutcome;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;
import com.telamin.mongoose.dutycycle.ProcessorRegistration;
import com.telamin.mongoose.internal.ChronicleAuditCaptureService;
import com.telamin.mongoose.internal.DirAuditIntrospectionService;
import com.telamin.mongoose.internal.NoOpCountersService;
import com.telamin.mongoose.service.audit.AuditCaptureIdentity;
import com.telamin.mongoose.service.audit.AuditCaptureResources;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import static org.junit.jupiter.api.Assertions.*;

/** External review: a real group driven inline, with the real Chronicle service as its resources (direct embedding). */
@Timeout(60)
class ChronicleGroupInlineTest {

    static final class ManualIo implements CaptureIo {
        final Deque<Runnable> opens = new ArrayDeque<>();
        final Deque<Runnable> closes = new ArrayDeque<>();
        final List<Runnable> opensRun = new ArrayList<>();
        final List<Runnable> timeouts = new ArrayList<>();
        final List<Boolean> timeoutCancelled = new ArrayList<>();
        @Override public void open(Runnable task) { opens.add(task); }
        @Override public void close(Runnable task) { closes.add(task); }
        @Override public Cancellable schedule(Runnable task, long millis) {
            int i = timeouts.size();
            timeouts.add(task);
            timeoutCancelled.add(false);
            return () -> timeoutCancelled.set(i, true);
        }
        boolean runOpen() { Runnable r = opens.poll(); if (r == null) return false; opensRun.add(r); r.run(); return true; }
        boolean runClose() { Runnable r = closes.poll(); if (r == null) return false; r.run(); return true; }
        void rerunOpen(int i) { opensRun.get(i).run(); }
        void fireTimeout(int i) { if (!timeoutCancelled.get(i)) timeouts.get(i).run(); }
        void runAll() { boolean any = true; while (any) any = runOpen() | runClose(); }
    }

    static class Proc implements DataFlow, Lifecycle {
        final String label;
        final List<LogRecordListener> listeners = new CopyOnWriteArrayList<>();
        Proc(String label) { this.label = label; }
        @Override public void init() { }
        @Override public void addEventFeed(EventFeed f) { }
        @Override public void onEvent(Object e) { }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void tearDown() { }
        @Override public void setAuditLogProcessor(LogRecordListener l) { listeners.add(l); }
        LogRecordListener current() { return listeners.isEmpty() ? null : listeners.get(listeners.size() - 1); }
        @Override public String toString() { return label; }
    }

    private final Map<String, ProcessorRegistration> reserved = new ConcurrentHashMap<>();
    private ComposingEventProcessorAgent group;
    private ManualIo io;
    private ChronicleAuditCaptureService svc;
    private AuditCaptureConfig cfg;
    private final LogRecordListener server = r -> { };

    @TempDir Path dir;

    @BeforeEach
    void group() {
        cfg = new AuditCaptureConfig();
        cfg.setEnabled(true);
        cfg.setDirectory(dir.toString());
        svc = new ChronicleAuditCaptureService(cfg, NoOpCountersService.INSTANCE);
        group = new ComposingEventProcessorAgent("H", new EventFlowManager(), null, new DeadWheelScheduler(),
                new ConcurrentHashMap<>());
        io = new ManualIo();
        group.setCaptureIo(io);
        group.setAuditCaptureResources(svc);
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
        // as the server does, attach the exact instance under its generation identity (here: after the add)
        svc.attachScoped(proc, new AuditCaptureIdentity("H", name, reg.generation()), server, group::runOnAgentThread);
        return reg;
    }

    private void work() throws Exception {
        for (int i = 0; i < 4; i++) group.doWork();
    }

    @SuppressWarnings("unchecked")
    private int openResources() throws Exception {
        var f = ChronicleAuditCaptureService.class.getDeclaredField("openResources");
        f.setAccessible(true);
        return ((Set<Object>) f.get(svc)).size();
    }

    @SuppressWarnings("unchecked")
    private int ownedSinks() throws Exception {
        var f = ChronicleAuditCaptureService.class.getDeclaredField("ownedSinks");
        f.setAccessible(true);
        return ((Map<Object, Object>) f.get(svc)).size();
    }

    @Test void lateOpenAfterTimeoutIsClosedAndNextSessionIsUnaffected() throws Exception {
        Proc p = new Proc("p");
        ProcessorRegistration reg = add("p", p);
        ControlRequest start = group.auditStart("p", reg);
        work();
        assertEquals(1, io.opens.size());
        io.fireTimeout(0);                         // the timer wins
        work();
        assertEquals(ReplyOutcome.FAILED, start.reply().getNow(null).outcome());
        assertTrue(io.runOpen());                  // late open: a real queue is created
        work();                                    // stale: closed, never installed
        assertEquals(1, io.closes.size());
        assertEquals(0, p.listeners.size(), "nothing was installed on the processor: " + p.listeners);
        io.runClose();
        work();
        assertEquals(0, openResources(), "the late resource was closed");
        assertEquals(0, group.captureResourceSlotsInUse());
        assertFalse(svc.isRecording("H/p"));
        var listing = new DirAuditIntrospectionService(cfg, svc).listAvailable();
        assertEquals(1, listing.size(), "one historical, empty capture: " + listing);
        assertFalse(listing.get(0).isLive());

        ControlRequest again = group.auditStart("p", reg);
        work();
        io.runOpen();
        work();
        assertEquals(ReplyOutcome.APPLIED, again.reply().getNow(null).outcome());
        assertTrue(svc.isRecording("H/p"));
        assertEquals(1, openResources());
        assertEquals(1, p.listeners.size());
        group.onClose();
        io.runAll();
        assertEquals(0, openResources());
        assertSame(server, p.current(), "exact server listener restored on close");
    }

    @Test void duplicateOpenCompletionLeavesTheLiveCaptureIntact() throws Exception {
        Proc p = new Proc("p");
        ProcessorRegistration reg = add("p", p);
        ControlRequest start = group.auditStart("p", reg);
        work();
        io.runOpen();
        work();
        assertEquals(ReplyOutcome.APPLIED, start.reply().getNow(null).outcome());
        LogRecordListener live = p.current();
        Path livePath = svc.currentSink("H/p").path();
        io.rerunOpen(0);                           // a repeating helper: a second resource for the same op
        work();                                    // duplicate: closed, never installed
        assertEquals(1, io.closes.size());
        io.runClose();
        work();
        assertTrue(svc.isRecording("H/p"));
        assertSame(live, p.current(), "the duplicate did not touch the installed listener");
        assertEquals(livePath, svc.currentSink("H/p").path());
        assertEquals(1, openResources());
        assertEquals(1, group.captureResourceSlotsInUse());
        group.onClose();
        io.runAll();
        assertEquals(0, openResources());
        assertEquals(0, group.captureResourceSlotsInUse());
        assertSame(server, p.current());
    }

    @Test void repeatedSessionsRestoreTheExactListenerEachTime() throws Exception {
        Proc p = new Proc("p");
        ProcessorRegistration reg = add("p", p);
        for (int i = 0; i < 3; i++) {
            ControlRequest start = group.auditStart("p", reg);
            work();
            io.runOpen();
            work();
            assertEquals(ReplyOutcome.APPLIED, start.reply().getNow(null).outcome(), "session " + i);
            assertNotSame(server, p.current());
            ControlRequest stop = group.auditStop("p", reg);
            work();
            assertEquals(ReplyOutcome.APPLIED, stop.reply().getNow(null).outcome());
            assertSame(server, p.current(), "session " + i + " restored the exact server listener");
            assertFalse(svc.isRecording("H/p"));
            io.runClose();
            work();
            assertEquals(0, openResources());
        }
        assertEquals(3, new DirAuditIntrospectionService(cfg, svc).listAvailable().size());
        group.onClose();
        io.runAll();
    }

    /**
     * Hypothesis: in a direct embedding (no MongooseServer) nothing calls processorStopped, so the retired generation's
     * scoped binding stays in ownedSinks and still reads as attached; once a replacement generation attaches under the
     * same name, every selector for it is ambiguous.
     */
    @Test void replacementGenerationWithoutServerRetiresTheOldBinding() throws Exception {
        Proc p1 = new Proc("p1");
        ProcessorRegistration reg1 = add("p", p1);
        ControlRequest start = group.auditStart("p", reg1);
        work();
        io.runOpen();
        work();
        assertEquals(ReplyOutcome.APPLIED, start.reply().getNow(null).outcome());
        ControlRequest stop = group.removeEventProcessorByName("p", reg1, null);
        work();
        assertEquals(ReplyOutcome.APPLIED, stop.reply().getNow(null).outcome());
        io.runAll();
        work();
        assertSame(server, p1.current(), "release restored the exact listener on gen1");
        assertEquals(0, openResources());
        int owned = ownedSinks();
        Proc p2 = new Proc("p2");
        ProcessorRegistration reg2 = add("p", p2);
        assertNotEquals(reg1.generation(), reg2.generation());
        // Without processorStopped the gen1 binding is retained and attached (dataFlow non-null)
        System.out.println("ownedSinks after gen1 release: " + owned + ", after gen2 attach: " + ownedSinks());
        boolean ambiguous = false;
        try {
            svc.isRecording("H/p");
        } catch (IllegalArgumentException e) {
            ambiguous = true;
            System.out.println("isRecording(H/p) -> " + e.getMessage());
        }
        // Run gen2 capture through the group regardless: must still work (identity-pinned)
        ControlRequest start2 = group.auditStart("p", reg2);
        work();
        io.runOpen();
        work();
        assertEquals(ReplyOutcome.APPLIED, start2.reply().getNow(null).outcome(), "gen2 capture must open and install");
        assertNotSame(server, p2.current());
        assertSame(server, p1.current(), "gen2's install never touched gen1's instance");
        assertEquals(0, owned, "the directly embedded group retires its released generation");
        assertFalse(ambiguous, "the retired generation cannot make the replacement ambiguous");
        // Now the explicit retirement of the OLD instance, as the server would have performed it. Hypothesis: with no
        // router, processorStopped(group, name, flow) falls through to the legacy bare-name stop(name) and ends the
        // live capture of the NEW generation, closing a queue the group still owns.
        LogRecordListener liveOnP2 = p2.current();
        int openBefore = openResources();
        svc.processorStopped("H", "p", p1);
        assertEquals(1, ownedSinks());
        assertTrue(svc.isRecording("H/p"), "retiring gen1's instance must not end gen2's live capture");
        assertSame(liveOnP2, p2.current(), "gen2's capture listener must still be installed");
        assertEquals(openBefore, openResources(), "gen2's queue must still be open");
        group.onClose();
        io.runAll();
        assertEquals(0, openResources());
    }

    /** Resources whose detach fails forever: how is the retained resource reported? */
    static final class DetachFailing implements AuditCaptureResources {
        volatile boolean failDetach = true;
        int opens; int closes;
        @Override public Object openCapture(String n) { opens++; return new Object(); }
        @Override public void installCapture(Object r, String n, DataFlow f) { }
        @Override public void uninstallCapture(Object r, String n, DataFlow f) {
            if (failDetach) throw new IllegalStateException("detach unavailable (flow=" + f + ")");
        }
        @Override public void closeCapture(Object r) { closes++; }
    }

    @Test void failedDetachRetainsItsResourceAndReportsCleanupFailure() throws Exception {
        var res = new DetachFailing();
        group.setAuditCaptureResources(res);
        Proc p = new Proc("p");
        ProcessorRegistration reg = new ProcessorRegistration("p");
        ControlRequest r = group.addNamedEventProcessor(() -> new NamedEventProcessor("p", p), reg);
        group.doWork();
        assertEquals(ReplyOutcome.APPLIED, r.reply().getNow(null).outcome());
        ControlRequest start = group.auditStart("p", reg);
        work();
        io.runOpen();
        work();
        assertEquals(ReplyOutcome.APPLIED, start.reply().getNow(null).outcome());
        ControlRequest stop = group.removeEventProcessorByName("p", reg, null);
        work();
        io.runAll();
        work();
        System.out.println("stop reply: " + stop.reply().getNow(null));
        assertEquals(1, group.captureResourcesHeld(), "the resource is retained (detach failed twice)");
        assertEquals(1, group.captureResourceSlotsInUse());
        assertEquals(0, res.closes, "never closed while possibly still installed");
        System.out.println("captureCloseFailures=" + group.captureCloseFailures());
        assertTrue(group.captureCloseFailures().isEmpty(), "no physical close was attempted");
        assertEquals(1, group.captureCleanupFailures().size());
        assertTrue(group.captureCleanupFailures().values().iterator().next().contains("detach unavailable"));
        assertEquals(1, group.control().processor().controlState.resources(), "retained physical ownership stays in the control table");
        assertEquals(com.telamin.mongoose.control.node.ControlState.ResourceState.CLOSE_FAILED,
                group.control().processor().controlState.resource(1).state);
        // detach recovers at group close: the leftover is released
        res.failDetach = false;
        group.onClose();
        io.runAll();
        assertEquals(0, group.captureResourcesHeld());
        assertEquals(0, group.captureResourceSlotsInUse());
        assertEquals(1, res.closes);
        assertTrue(group.captureCleanupFailures().isEmpty());
    }
}
