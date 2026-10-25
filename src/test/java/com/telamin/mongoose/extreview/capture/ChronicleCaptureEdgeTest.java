package com.telamin.mongoose.extreview.capture;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.mongoose.config.AuditCaptureConfig;
import com.telamin.mongoose.control.ControlRequest;
import com.telamin.mongoose.internal.ChronicleAuditCaptureService;
import com.telamin.mongoose.internal.DirAuditIntrospectionService;
import com.telamin.mongoose.internal.NoOpCountersService;
import com.telamin.mongoose.service.audit.AuditCaptureIdentity;
import com.telamin.mongoose.service.audit.AuditSinkHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** External review probes of the Chronicle capture service's resource API (routed mode, service only). */
class ChronicleCaptureEdgeTest {

    static class Flow implements DataFlow {
        volatile LogRecordListener listener;
        final String label;
        Flow(String label) { this.label = label; }
        @Override public void onEvent(Object event) { }
        @Override public void addEventFeed(EventFeed feed) { }
        @Override public void setAuditLogProcessor(LogRecordListener l) { this.listener = l; }
        @Override public String toString() { return label; }
    }

    static AuditCaptureConfig config(Path dir) {
        var c = new AuditCaptureConfig();
        c.setEnabled(true);
        c.setDirectory(dir.toString());
        return c;
    }

    static ChronicleAuditCaptureService routed(Path dir) {
        var svc = new ChronicleAuditCaptureService(config(dir), NoOpCountersService.INSTANCE);
        svc.routeThrough(new ChronicleAuditCaptureService.CaptureRouter() {
            public ControlRequest send(String name, boolean start) { throw new AssertionError("resource API probe"); }
            public boolean safeToWait(String name) { return true; }
        });
        return svc;
    }

    @Test void selectorsWithSlashPercentSpacePlusAndEmptyGroupRoundTrip(@TempDir Path dir) throws Exception {
        var svc = routed(dir);
        var introspection = new DirAuditIntrospectionService(config(dir), svc);
        var awkward = new AuditCaptureIdentity("g/a", "p% +x/y", 1);
        var emptyGroup = new AuditCaptureIdentity("", "p", 2);
        LogRecordListener server = r -> { };
        var f1 = new Flow("awkward");
        var f2 = new Flow("emptyGroup");
        svc.attachScoped(f1, awkward, server, Runnable::run);
        svc.attachScoped(f2, emptyGroup, server, Runnable::run);
        Object h1 = svc.openCaptureScoped(awkward);
        Object h2 = svc.openCaptureScoped(emptyGroup);
        svc.installCapture(h1, awkward.name(), f1);
        svc.installCapture(h2, emptyGroup.name(), f2);
        assertEquals("g%2Fa/p%25%20%2Bx%2Fy", awkward.selector());
        assertEquals("/p", emptyGroup.selector());
        assertTrue(svc.isRecording(awkward.selector()));
        assertTrue(svc.isRecording(emptyGroup.selector()));
        assertTrue(svc.isRecording("p"), "bare name 'p' is unique");
        assertNotNull(svc.currentSink(awkward.selector()));
        assertNotNull(svc.currentSink(emptyGroup.selector()));
        assertEquals(2, svc.liveSinks().size());
        assertTrue(svc.liveSinks().containsKey(awkward.selector()), svc.liveSinks().keySet().toString());
        assertTrue(svc.liveSinks().containsKey("/p"), svc.liveSinks().keySet().toString());
        svc.uninstallCapture(h1, awkward.name(), f1);
        svc.uninstallCapture(h2, emptyGroup.name(), f2);
        svc.closeCapture(h1);
        svc.closeCapture(h2);
        assertSame(server, f1.listener);
        assertSame(server, f2.listener);
        List<AuditSinkHandle> listed = introspection.listAvailable();
        assertEquals(2, listed.size());
        assertTrue(listed.stream().anyMatch(h -> h.processorName().equals(awkward.selector())), listed.toString());
        assertTrue(listed.stream().anyMatch(h -> h.processorName().equals("/p")), listed.toString());
        assertTrue(listed.stream().noneMatch(AuditSinkHandle::isLive));
    }

    @Test void legacyRoutedAttachIsIdempotentAndConflictRefused(@TempDir Path dir) {
        var svc = routed(dir);
        var flow = new Flow("legacy");
        LogRecordListener l = r -> { };
        svc.attach(flow, "p", l);
        assertDoesNotThrow(() -> svc.attach(flow, "p", l));
        assertThrows(IllegalStateException.class, () -> svc.attach(flow, "q", l));
        assertThrows(IllegalStateException.class, () -> svc.attach(flow, "p", r -> { }));
    }

    /**
     * Hypothesis: two scoped identities attached to ONE flow (misuse, direct embedding) silently clobber each other:
     * the second install replaces the first's listener; the first's uninstall restores the server listener over the
     * second's capture listener while the second sink still reports recording.
     */
    @Test void twoScopedIdentitiesOnOneFlowClobberEachOther(@TempDir Path dir) throws Exception {
        var svc = routed(dir);
        var flow = new Flow("shared");
        LogRecordListener server = r -> { };
        var a = new AuditCaptureIdentity("g", "a", 1);
        var b = new AuditCaptureIdentity("g", "b", 2);
        svc.attachScoped(flow, a, server, Runnable::run);
        svc.attachScoped(flow, b, server, Runnable::run);
        Object ha = svc.openCaptureScoped(a);
        Object hb = svc.openCaptureScoped(b);
        svc.installCapture(ha, "a", flow);
        LogRecordListener captureA = flow.listener;
        svc.installCapture(hb, "b", flow);
        LogRecordListener captureB = flow.listener;
        assertNotSame(captureA, captureB);
        svc.uninstallCapture(ha, "a", flow);
        svc.closeCapture(ha);
        // b still claims to record, yet its capture listener is no longer on the flow
        assertTrue(svc.isRecording("g/b"));
        assertSame(server, flow.listener, "a's uninstall restored the server listener over b's live capture");
        svc.uninstallCapture(hb, "b", flow);
        svc.closeCapture(hb);
    }

    /** A second uninstall of a handle whose session has ended must be a silent no-op, never a restore. */
    @Test void repeatedUninstallOfAnEndedSessionIsSilent(@TempDir Path dir) throws Exception {
        var svc = routed(dir);
        var flow = new Flow("f");
        LogRecordListener server = r -> { };
        var id = new AuditCaptureIdentity("g", "p", 1);
        svc.attachScoped(flow, id, server, Runnable::run);
        Object h1 = svc.openCaptureScoped(id);
        svc.installCapture(h1, "p", flow);
        svc.uninstallCapture(h1, "p", flow);
        svc.closeCapture(h1);
        Object h2 = svc.openCaptureScoped(id);
        svc.installCapture(h2, "p", flow);
        LogRecordListener live = flow.listener;
        assertDoesNotThrow(() -> svc.uninstallCapture(h1, "p", flow));
        assertDoesNotThrow(() -> svc.uninstallCapture(h1, "p", null));
        assertSame(live, flow.listener, "an old handle must not touch the live session's listener");
        assertTrue(svc.isRecording("g/p"));
        assertThrows(IllegalArgumentException.class, () -> svc.installCapture(h1, "p", flow), "closed handle cannot install");
        svc.uninstallCapture(h2, "p", flow);
        svc.closeCapture(h2);
        assertSame(server, flow.listener);
    }

    /**
     * Scoped standalone (no router, no group): the design says these bindings "support start, stop and retirement".
     * Hypothesis: retiring the OLD generation's instance with processorStopped(group, name, oldFlow) falls through to
     * the legacy bare-name stop(name), which ends whichever sink records under that name: the NEW generation's.
     */
    @Test void retiringAnOldGenerationMustNotStopTheNewGenerationsCapture(@TempDir Path dir) {
        var svc = new ChronicleAuditCaptureService(config(dir), NoOpCountersService.INSTANCE);   // unrouted
        LogRecordListener server = r -> { };
        var f1 = new Flow("gen1");
        var f2 = new Flow("gen2");
        var gen1 = new AuditCaptureIdentity("g", "p", 1);
        var gen2 = new AuditCaptureIdentity("g", "p", 2);
        svc.attachScoped(f1, gen1, server, Runnable::run);
        svc.startScoped(gen1);
        // gen1 is stopped as a processor: its capture ended and the listener restored (as the group's release does)
        svc.stop("g/p");
        assertSame(server, f1.listener);
        // the replacement generation attaches and records
        svc.attachScoped(f2, gen2, server, Runnable::run);
        svc.startScoped(gen2);                        // scoped start resolves by identity, not by name
        // while gen1 is still attached, even the qualified selector is ambiguous (two attached generations)
        assertThrows(IllegalArgumentException.class, () -> svc.isRecording("g/p"));
        LogRecordListener live = f2.listener;
        assertNotSame(server, live, "gen2 is recording");
        // retire gen1's instance: per the design, ignored for any other instance
        svc.processorStopped("g", "p", f1);
        assertTrue(svc.isRecording("g/p"), "retiring gen1's instance must not end gen2's capture");
        assertSame(live, f2.listener, "gen2's capture listener must still be installed");
        svc.stop("g/p");
    }

    /** Standalone attach must not be able to claim a routed capture directory, and a routed open never reuses a path. */
    @Test void routedOpensNeverReuseADirectoryEvenForTheSameIdentity(@TempDir Path dir) throws Exception {
        var svc = routed(dir);
        var flow = new Flow("f");
        var id = new AuditCaptureIdentity("g", "p", 1);
        svc.attachScoped(flow, id, r -> { }, Runnable::run);
        Object h1 = svc.openCaptureScoped(id);
        Object h2 = svc.openCaptureScoped(id);          // a second open for the same identity (e.g. rerun helper)
        svc.installCapture(h1, "p", flow);
        AuditSinkHandle live = svc.currentSink("g/p");
        assertThrows(IllegalStateException.class, () -> svc.installCapture(h2, "p", flow), "already recording");
        assertEquals(live.path(), svc.currentSink("g/p").path(), "the failed second install did not swap the queue");
        svc.closeCapture(h2);                           // compensation closes the duplicate, uninstalled
        assertTrue(svc.isRecording("g/p"));
        svc.uninstallCapture(h1, "p", flow);
        svc.closeCapture(h1);
        assertFalse(svc.isRecording("g/p"));
        assertEquals(2, new DirAuditIntrospectionService(config(dir), svc).listAvailable().size(),
                "both opens left a (distinct) historical directory");
    }
}
