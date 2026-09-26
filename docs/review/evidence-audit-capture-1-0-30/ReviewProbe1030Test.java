/*
 * SPDX-FileCopyrightText: © 2026 Telamin
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.audit.LogRecord;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.mongoose.config.AuditCaptureConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/** Review probe for 1.0.30 (not a product test): prints what each listener receives. */
class ReviewProbe1030Test {
    static DataFlow flow(AtomicReference<LogRecordListener> installed) {
        return (DataFlow) Proxy.newProxyInstance(DataFlow.class.getClassLoader(), new Class<?>[]{DataFlow.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "setAuditLogProcessor" -> { installed.set((LogRecordListener) args[0]); yield null; }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "flow";
                    default -> m.getReturnType() == boolean.class ? false : m.getReturnType().isPrimitive() && m.getReturnType() != void.class ? 0 : null;
                });
    }
    static LogRecord rec(String t) { LogRecord r = new LogRecord(new com.telamin.fluxtion.runtime.time.Clock()); r.addRecord("probe", "msg", t); return r; }
    static AuditCaptureConfig cfg(Path d) { AuditCaptureConfig c = new AuditCaptureConfig(); c.setEnabled(true); c.setDirectory(d.toString()); return c; }

    @Test void reAttachWhileRecordingWithADifferentListener(@TempDir Path dir) {
        List<String> l1 = new CopyOnWriteArrayList<>(), l2 = new CopyOnWriteArrayList<>();
        LogRecordListener L1 = r -> l1.add("L1"), L2 = r -> l2.add("L2");
        AtomicReference<LogRecordListener> first = new AtomicReference<>(), second = new AtomicReference<>();
        var svc = new ChronicleAuditCaptureService(cfg(dir), NoOpCountersService.INSTANCE);
        svc.attach(flow(first), "p", L1);
        svc.start("p");
        svc.attach(flow(second), "p", L2);                 // re-registered, the server now hands over L2
        second.get().processLogRecord(rec("x"));
        System.out.println("PROBE-A while recording after re-attach with L2: L1 got " + l1.size() + ", L2 got " + l2.size());
        svc.stop("p");
        System.out.println("PROBE-A after stop, installed on the NEW flow is " + (second.get() == L1 ? "L1" : second.get() == L2 ? "L2" : "other"));
        svc.start("p");
        second.get().processLogRecord(rec("y"));
        System.out.println("PROBE-A after stop+start: L1 got " + l1.size() + ", L2 got " + l2.size());
    }

    @Test void reAttachAfterStop(@TempDir Path dir) {
        List<String> l1 = new CopyOnWriteArrayList<>(), l2 = new CopyOnWriteArrayList<>();
        LogRecordListener L1 = r -> l1.add("L1"), L2 = r -> l2.add("L2");
        AtomicReference<LogRecordListener> first = new AtomicReference<>(), second = new AtomicReference<>();
        var svc = new ChronicleAuditCaptureService(cfg(dir), NoOpCountersService.INSTANCE);
        svc.attach(flow(first), "p", L1);
        svc.start("p"); svc.stop("p");
        svc.attach(flow(second), "p", L2);
        System.out.println("PROBE-B re-attach after stop: installed on new flow by capture? " + (second.get() != null));
        svc.start("p");
        second.get().processLogRecord(rec("z"));
        System.out.println("PROBE-B start after re-attach: L1 got " + l1.size() + ", L2 got " + l2.size());
    }

    @Test void twoQuickReAttachesWhileRecording(@TempDir Path dir) {
        LogRecordListener L = r -> { };
        AtomicReference<LogRecordListener> a = new AtomicReference<>(), b = new AtomicReference<>(), c = new AtomicReference<>();
        var svc = new ChronicleAuditCaptureService(cfg(dir), NoOpCountersService.INSTANCE);
        svc.attach(flow(a), "p", L); svc.start("p");
        svc.attach(flow(b), "p", L); svc.attach(flow(c), "p", L);
        System.out.println("PROBE-C capture listener on a/b/c: " + (a.get() != L) + "/" + (b.get() != L) + "/" + (c.get() != L)
                + " (stop restores only the current flow)");
        svc.stop("p");
        System.out.println("PROBE-C after stop: a restored " + (a.get() == L) + ", b restored " + (b.get() == L) + ", c restored " + (c.get() == L));
    }
}
