package com.telamin.mongoose.internal;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.audit.LogRecord;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.mongoose.config.AuditCaptureConfig;
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
        } finally {
            svc.uninstallCapture(h1, "p", first);
            svc.uninstallCapture(h2, "p", second);
            svc.closeCapture(h1); svc.closeCapture(h2);
        }
    }
}
