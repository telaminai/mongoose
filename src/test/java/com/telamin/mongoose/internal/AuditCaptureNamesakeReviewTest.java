/*
 * SPDX-FileCopyrightText: © 2026 Telamin
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.audit.LogRecord;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.mongoose.config.AuditCaptureConfig;
import com.telamin.mongoose.service.audit.AuditCaptureIdentity;
import com.telamin.mongoose.control.ControlRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class AuditCaptureNamesakeReviewTest {
    private static ChronicleAuditCaptureService service(Path dir) {
        AuditCaptureConfig c = new AuditCaptureConfig();
        c.setEnabled(true);
        c.setDirectory(dir.toString());
        var svc = new ChronicleAuditCaptureService(c, NoOpCountersService.INSTANCE);
        svc.routeThrough(new ChronicleAuditCaptureService.CaptureRouter() {
            public ControlRequest send(String name, boolean start) { throw new AssertionError("resource API probe"); }
            public boolean safeToWait(String name) { return true; }
        });
        return svc;
    }
    private static DataFlow flow(AtomicReference<LogRecordListener> installed) {
        return (DataFlow) Proxy.newProxyInstance(DataFlow.class.getClassLoader(), new Class<?>[]{DataFlow.class},
            (proxy, method, args) -> switch(method.getName()) {
                case "setAuditLogProcessor" -> { installed.set((LogRecordListener)args[0]); yield null; }
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "flow";
                default -> null;
            });
    }
    @Test void captureOfEarlierNamesakeUsesItsOwnConfiguredListener(@TempDir Path dir) {
        var svc = service(dir);
        var firstInstalled = new AtomicReference<LogRecordListener>();
        var secondInstalled = new AtomicReference<LogRecordListener>();
        var first = flow(firstInstalled);
        var second = flow(secondInstalled);
        var firstCalls = new AtomicInteger();
        var secondCalls = new AtomicInteger();
        LogRecordListener firstListener = r -> firstCalls.incrementAndGet();
        LogRecordListener secondListener = r -> secondCalls.incrementAndGet();
        first.setAuditLogProcessor(firstListener);
        second.setAuditLogProcessor(secondListener);
        svc.attach(first, "p", firstListener);   // group g1, capture off
        svc.attach(second, "p", secondListener); // group g2, capture off
        Object handle = svc.openCapture("p"); // routed g1/p passes bare effect.name
        try {
            svc.installCapture(handle, "p", first);
            var record = new LogRecord(new com.telamin.fluxtion.runtime.time.Clock());
            record.addRecord("probe", "msg", "from-g1");
            firstInstalled.get().processLogRecord(record);
            svc.uninstallCapture(handle, "p", first);
            assertAll(
                () -> assertEquals(1, firstCalls.get(), "g1 audit must reach g1 listener"),
                () -> assertEquals(0, secondCalls.get(), "g1 audit must not reach g2 listener"),
                () -> assertSame(firstListener, firstInstalled.get(), "restore g1 listener"));
        } finally { svc.uninstallCapture(handle, "p", first); svc.closeCapture(handle); }
    }
    @Test void twoGroupsCanCaptureSameNamedProcessorsIndependently(@TempDir Path dir) {
        var svc = service(dir);
        var first = flow(new AtomicReference<>());
        var second = flow(new AtomicReference<>());
        LogRecordListener listener = r -> { };
        svc.attach(first, "p", listener);
        svc.attach(second, "p", listener);
        Object h1 = svc.openCapture("p");
        Object h2 = svc.openCapture("p");
        try {
            svc.installCapture(h1, "p", first);
            assertDoesNotThrow(() -> svc.installCapture(h2, "p", second), "g2/p is independent of g1/p");
            assertEquals(2, svc.liveSinks().values().stream().map(h -> h.path()).distinct().count(),
                    "physical queues must be independent too");
        } finally {
            svc.uninstallCapture(h1, "p", first);
            svc.uninstallCapture(h2, "p", second);
            svc.closeCapture(h1); svc.closeCapture(h2);
        }
    }

    @Test void oldResourceCannotInstallOnOrUninstallAReplacement(@TempDir Path dir) {
        var svc = service(dir);
        var first = flow(new AtomicReference<>());
        var installed = new AtomicReference<LogRecordListener>();
        var replacement = flow(installed);
        var oldOwner = new AuditCaptureIdentity("g", "p", 1);
        var newOwner = new AuditCaptureIdentity("g", "p", 2);
        svc.attachScoped(first, oldOwner, r -> { }, Runnable::run);
        Object oldOpen = svc.openCaptureScoped(oldOwner);
        svc.processorStopped("g", "p", first);
        svc.attachScoped(replacement, newOwner, r -> { }, Runnable::run);
        Object current = svc.openCaptureScoped(newOwner);
        try {
            assertThrows(IllegalArgumentException.class, () -> svc.installCapture(oldOpen, "p", replacement));
            assertThrows(IllegalArgumentException.class, () -> svc.installCapture(current, "p", first));
            assertThrows(IllegalArgumentException.class, () -> svc.openCaptureScoped(oldOwner));
            svc.installCapture(current, "p", replacement);
            LogRecordListener live = installed.get();
            svc.uninstallCapture(oldOpen, "p", null);
            svc.closeCapture(oldOpen);
            assertTrue(svc.isRecording("g/p"));
            assertSame(live, installed.get());
            assertEquals(2, new DirAuditIntrospectionService(config(dir), svc).listAvailable().size());
        } finally {
            svc.uninstallCapture(current, "p", replacement);
            svc.closeCapture(current);
            svc.closeCapture(oldOpen);
        }
    }

    private static AuditCaptureConfig config(Path dir) {
        var config = new AuditCaptureConfig();
        config.setEnabled(true);
        config.setDirectory(dir.toString());
        return config;
    }

    @Test void selectorsPathsAndHistoricalCaptureIdsStayDistinct(@TempDir Path dir) {
        var svc = service(dir);
        var introspection = new DirAuditIntrospectionService(config(dir), svc);
        var owners = java.util.List.of(new AuditCaptureIdentity("g/a", "p%/..", 1),
                new AuditCaptureIdentity("g", "a/p%/..", 1),
                new AuditCaptureIdentity("A", "p", 1), new AuditCaptureIdentity("a", "p", 1),
                new AuditCaptureIdentity("", "..", 1), new AuditCaptureIdentity("g", "p", 1),
                new AuditCaptureIdentity("other", "g/p", 1));
        var handles = new java.util.ArrayList<Object>();
        var flows = new java.util.ArrayList<DataFlow>();
        try {
            for (var owner : owners) {
                DataFlow flow = flow(new AtomicReference<>());
                flows.add(flow);
                svc.attachScoped(flow, owner, r -> { }, Runnable::run);
                Object handle = svc.openCaptureScoped(owner);
                handles.add(handle);
                svc.installCapture(handle, owner.name(), flow);
                assertTrue(svc.isRecording(owner.selector()), owner.selector());
                assertNotNull(introspection.currentSink(owner.selector()));
            }
            assertTrue(svc.isRecording("%67/p"), "encoded and canonical selectors resolve identically");
            assertNotNull(introspection.currentSink("%67/p"));
            assertThrows(IllegalArgumentException.class, () -> svc.isRecording("p"));
            var live = introspection.currentSinks().values();
            assertEquals(owners.size(), live.size());
            assertEquals(owners.size(), live.stream().map(h -> h.path()).distinct().count());
            assertTrue(live.stream().allMatch(h -> h.path().getParent().equals(dir)));
        } finally {
            for (int i = 0; i < handles.size(); i++) {
                svc.uninstallCapture(handles.get(i), owners.get(i).name(), flows.get(i));
                svc.closeCapture(handles.get(i));
                svc.processorStopped(owners.get(i).group(), owners.get(i).name(), flows.get(i));
            }
        }
        var historical = new DirAuditIntrospectionService(config(dir), service(dir)).listAvailable();
        assertEquals(owners.size(), historical.size());
        assertEquals(owners.size(), historical.stream().map(h -> h.id()).distinct().count());
        assertEquals(new java.util.HashSet<>(owners.stream().map(AuditCaptureIdentity::selector).toList()),
                new java.util.HashSet<>(historical.stream().map(h -> h.processorName()).toList()));
        assertTrue(historical.stream().noneMatch(h -> h.isLive()));
    }

    @Test void eachSessionHasItsOwnCountersAndHistory(@TempDir Path dir) {
        var svc = service(dir);
        var installed = new AtomicReference<LogRecordListener>();
        var flow = flow(installed);
        var owner = new AuditCaptureIdentity("g", "p", 1);
        svc.attachScoped(flow, owner, r -> { }, Runnable::run);
        var introspection = new DirAuditIntrospectionService(config(dir), svc);
        Object first = svc.openCaptureScoped(owner);
        svc.installCapture(first, "p", flow);
        var record = new LogRecord(new com.telamin.fluxtion.runtime.time.Clock());
        record.addRecord("probe", "msg", "first-session");
        installed.get().processLogRecord(record);
        var firstInfo = introspection.currentSink("p");
        assertEquals(1, firstInfo.recordCount());
        svc.uninstallCapture(first, "p", flow);
        svc.closeCapture(first);
        Object second = svc.openCaptureScoped(owner);
        try {
            svc.installCapture(second, "p", flow);
            var liveListener = installed.get();
            svc.uninstallCapture(first, "p", null); // duplicate late cleanup of the previous session
            assertSame(liveListener, installed.get());
            var secondInfo = introspection.currentSink("g/p");
            assertEquals(0, secondInfo.recordCount());
            assertNotEquals(firstInfo.path(), secondInfo.path());
            assertEquals(secondInfo.startedAt(), secondInfo.lastWriteAt());
            var history = introspection.listAvailable();
            assertEquals(2, history.size());
            assertEquals(1, history.stream().filter(h -> h.isLive()).count());
        } finally { svc.uninstallCapture(second, "p", flow); svc.closeCapture(second); }
    }

    @Test void separateServiceInstancesNeverShareACapturePath(@TempDir Path dir) {
        var left = service(dir);
        var right = service(dir);
        var owner = new AuditCaptureIdentity("g", "p", 1);
        var f1 = flow(new AtomicReference<>());
        var f2 = flow(new AtomicReference<>());
        left.attachScoped(f1, owner, r -> { }, Runnable::run);
        right.attachScoped(f2, owner, r -> { }, Runnable::run);
        Object h1 = left.openCaptureScoped(owner), h2 = right.openCaptureScoped(owner);
        try {
            left.installCapture(h1, "p", f1); right.installCapture(h2, "p", f2);
            assertNotEquals(left.currentSink("p").path(), right.currentSink("p").path());
        } finally {
            left.uninstallCapture(h1, "p", f1); right.uninstallCapture(h2, "p", f2);
            left.closeCapture(h1); right.closeCapture(h2);
        }
    }

    @Test void retiredRegistrationsDoNotAccumulateInMemory(@TempDir Path dir) throws Exception {
        var svc = service(dir);
        for (int generation = 0; generation < 128; generation++) {
            var flow = flow(new AtomicReference<>());
            svc.attachScoped(flow, new AuditCaptureIdentity("g", "p", generation), r -> { }, Runnable::run);
            if ((generation & 1) == 0) svc.processorStopped("g", "p", flow);
            else svc.detach("g", "p", flow);
        }
        var field = ChronicleAuditCaptureService.class.getDeclaredField("ownedSinks");
        field.setAccessible(true);
        assertTrue(((java.util.Map<?, ?>) field.get(svc)).isEmpty());
    }

    @Test void oldDirectGroupAttachmentUsesUniqueResourceIdsAndFreshCounters(@TempDir Path dir) {
        var svc = new ChronicleAuditCaptureService(config(dir), NoOpCountersService.INSTANCE);
        var installed = new AtomicReference<LogRecordListener>();
        var flow = flow(installed);
        svc.attach(flow, "p", r -> { }); // pre-existing direct-group embedding API
        var owner = new AuditCaptureIdentity("g", "p", 1);
        var introspection = new DirAuditIntrospectionService(config(dir), svc);
        Object first = svc.openCaptureScoped(owner);
        svc.installCapture(first, "p", flow);
        var record = new LogRecord(new com.telamin.fluxtion.runtime.time.Clock());
        record.addRecord("probe", "msg", "one");
        installed.get().processLogRecord(record);
        assertEquals(1, introspection.listAvailable().size());
        assertEquals(1, introspection.currentSink("p").recordCount());
        svc.uninstallCapture(first, "p", flow);
        svc.closeCapture(first);
        Object second = svc.openCaptureScoped(owner);
        try {
            svc.installCapture(second, "p", flow);
            assertEquals(0, introspection.currentSink("p").recordCount());
            assertEquals(2, introspection.listAvailable().size());
            assertEquals(1, introspection.listAvailable().stream().filter(h -> h.isLive()).count());
        } finally { svc.uninstallCapture(second, "p", flow); svc.closeCapture(second); }
    }

    @Test void scopedStandaloneAttachmentCanStartStopAndRetire(@TempDir Path dir) throws Exception {
        var svc = new ChronicleAuditCaptureService(config(dir), NoOpCountersService.INSTANCE);
        var owner = new AuditCaptureIdentity("g", "p", 1);
        var flow = flow(new AtomicReference<>());
        svc.attachScoped(flow, owner, r -> { }, Runnable::run);
        svc.startScoped(owner);
        assertTrue(svc.isRecording("g/p"));
        svc.processorStopped("g", "p", flow);
        assertFalse(svc.isRecording("g/p"));
        assertThrows(IllegalArgumentException.class, () -> svc.startScoped(owner));
        var field = ChronicleAuditCaptureService.class.getDeclaredField("ownedSinks");
        field.setAccessible(true);
        assertTrue(((java.util.Map<?, ?>) field.get(svc)).isEmpty());
    }
}
