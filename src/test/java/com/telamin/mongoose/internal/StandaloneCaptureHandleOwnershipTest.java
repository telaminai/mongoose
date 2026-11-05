/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.mongoose.config.AuditCaptureConfig;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class StandaloneCaptureHandleOwnershipTest {
    static class Flow implements DataFlow {
        public void onEvent(Object e) { }
        public void setAuditLogProcessor(LogRecordListener listener) { }
    }
    @Test void processorStopDoesNotPhysicallyCloseAGroupOwnedHandle(@TempDir Path dir) {
        var cfg = new AuditCaptureConfig(); cfg.setEnabled(true); cfg.setDirectory(dir.toString());
        var closes = new AtomicInteger();
        var service = new ChronicleAuditCaptureService(cfg, NoOpCountersService.INSTANCE, path -> {
            ChronicleQueue real = SingleChronicleQueueBuilder.binary(path).build();
            return (ChronicleQueue) Proxy.newProxyInstance(ChronicleQueue.class.getClassLoader(), new Class[]{ChronicleQueue.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("close")) closes.incrementAndGet();
                    try { return method.invoke(real, args); }
                    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
        });
        Flow flow = new Flow(); service.attach(flow, "p", r -> { });
        Object handle = service.openCapture("p");
        try {
            service.installCapture(handle, "p", flow);
            service.processorStopped("p", flow);
            assertEquals(0, closes.get(), "physical ownership remains with the group handle");
            service.uninstallCapture(handle, "p", null);
            service.closeCapture(handle); service.closeCapture(handle);
            assertEquals(1, closes.get());
        } finally { service.closeCapture(handle); }
    }
}
