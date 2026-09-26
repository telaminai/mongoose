/*
 * SPDX-FileCopyrightText: © 2026 Telamin
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.audit.LogRecord;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.mongoose.config.AuditCaptureConfig;
import com.telamin.mongoose.service.audit.AuditSinkHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/** Review probe (not a product test): the listing read while a writer thread appends. */
class ReviewProbe1030ListingTest {
    @Test void listingWhileAWriterAppends(@TempDir Path dir) throws Exception {
        AuditCaptureConfig c = new AuditCaptureConfig(); c.setEnabled(true); c.setDirectory(dir.toString());
        var capture = new ChronicleAuditCaptureService(c, NoOpCountersService.INSTANCE);
        var listing = new DirAuditIntrospectionService(c, capture);
        AtomicReference<LogRecordListener> installed = new AtomicReference<>();
        DataFlow flow = (DataFlow) Proxy.newProxyInstance(DataFlow.class.getClassLoader(), new Class<?>[]{DataFlow.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "setAuditLogProcessor" -> { installed.set((LogRecordListener) args[0]); yield null; }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "flow";
                    default -> m.getReturnType() == boolean.class ? false : m.getReturnType().isPrimitive() && m.getReturnType() != void.class ? 0 : null;
                });
        capture.attach(flow, "p", r -> { });
        capture.start("p");
        int n = 2000;
        Thread writer = new Thread(() -> {
            for (int i = 0; i < n; i++) {
                LogRecord r = new LogRecord(new com.telamin.fluxtion.runtime.time.Clock());
                r.addRecord("probe", "i", String.valueOf(i));
                installed.get().processLogRecord(r);
            }
        });
        long lastCount = -1, lastSize = -1; int reads = 0, backwards = 0;
        writer.start();
        while (writer.isAlive() || reads < 5) {
            for (AuditSinkHandle h : listing.listAvailable()) {
                if (!"p".equals(h.processorName())) continue;
                if (h.recordCount() < lastCount || (h.sizeBytes() >= 0 && h.sizeBytes() < lastSize)) backwards++;
                lastCount = h.recordCount(); lastSize = Math.max(lastSize, h.sizeBytes());
            }
            reads++;
        }
        writer.join();
        long finalCount = listing.listAvailable().stream().filter(h -> "p".equals(h.processorName()))
                .mapToLong(AuditSinkHandle::recordCount).findFirst().orElse(-1);
        System.out.println("PROBE-L reads=" + reads + " backwards=" + backwards + " finalListed=" + finalCount + " written=" + n);
        capture.stop("p");
    }
}
