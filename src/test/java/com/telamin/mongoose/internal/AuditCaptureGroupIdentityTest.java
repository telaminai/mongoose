/*
 * SPDX-FileCopyrightText: © 2026 Telamin
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.AuditCaptureConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.PerformanceMonitoringConfig;
import com.telamin.mongoose.service.audit.MongooseAuditCaptureService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class AuditCaptureGroupIdentityTest {
    public static class Recording implements DataFlow {
        volatile LogRecordListener listener;
        @Override public void onEvent(Object event) { }
        @Override public void addEventFeed(EventFeed feed) { }
        @Override public void setAuditLogProcessor(LogRecordListener listener) { this.listener = listener; }
    }

    @Test void qualifiedCapturesOfTwoGroupsAreIndependent(@TempDir Path dir) throws Exception {
        verifyTwoGroups(dir, false);
    }
    @Test void bareAutoStartAppliesIndependentlyToEveryMatchingGroup(@TempDir Path dir) throws Exception {
        verifyTwoGroups(dir, true);
    }
    private void verifyTwoGroups(Path dir, boolean autoStart) throws Exception {
        var cfg = new MongooseServerConfig();
        var first = new Recording();
        var second = new Recording();
        cfg.addProcessor("g1", first, "p");
        cfg.addProcessor("g2", second, "p");
        var capture = new AuditCaptureConfig();
        capture.setEnabled(true);
        capture.setDirectory(dir.toString());
        if (autoStart) capture.setAutoStart(new ArrayList<>(List.of("p")));
        var perf = new PerformanceMonitoringConfig();
        perf.setAuditCapture(capture);
        cfg.setPerformanceMonitoring(perf);
        LogRecordListener original = r -> { };
        var server = MongooseServer.bootServer(cfg, original);
        try {
            var service = (ChronicleAuditCaptureService) server.registeredServices()
                    .get(MongooseAuditCaptureService.SERVICE_NAME).instance();
            if (!autoStart) {
                assertThrows(IllegalArgumentException.class, () -> service.start("p"));
                service.start("g1/p");
                service.start("g2/p");
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((!service.isRecording("g1/p") || !service.isRecording("g2/p")) && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertTrue(service.isRecording("g1/p"));
            assertTrue(service.isRecording("g2/p"));
            assertNotSame(first.listener, second.listener);
            assertNotSame(original, first.listener);
            assertNotSame(original, second.listener);
            var introspection = new DirAuditIntrospectionService(capture, service);
            assertNotEquals(introspection.currentSink("g1/p").path(), introspection.currentSink("g2/p").path());
            assertEquals(2, introspection.currentSinks().size());
            LogRecordListener secondCapture = second.listener;
            service.stop("g1/p");
            assertFalse(service.isRecording("g1/p"));
            assertTrue(service.isRecording("g2/p"));
            assertSame(original, first.listener);
            assertSame(secondCapture, second.listener);
            service.start("g1/p");
            assertTrue(service.isRecording("g1/p"));
            assertEquals(3, introspection.listAvailable().size(), "historical capture retained alongside two live captures");
        } finally {
            server.stop();
            assertTrue(server.awaitStopped(10, TimeUnit.SECONDS));
        }
    }

    public static class BadDescription extends Recording {
        @Override public String toString() { throw new IllegalStateException("description failed after capture attach"); }
    }
    public static class BadStart extends Recording {
        @Override public void start() { throw new IllegalStateException("start failed after capture attach"); }
    }
    @Test void failedProcessorBuildAndStartRetireTheirScopedBindings(@TempDir Path dir) throws Exception {
        var cfg = new MongooseServerConfig();
        cfg.addProcessor("g", new Recording(), "anchor");
        var capture = new AuditCaptureConfig();
        capture.setEnabled(true);
        capture.setDirectory(dir.toString());
        var perf = new PerformanceMonitoringConfig();
        perf.setAuditCapture(capture);
        cfg.setPerformanceMonitoring(perf);
        var server = MongooseServer.bootServer(cfg, r -> { });
        try {
            var service = (ChronicleAuditCaptureService) server.registeredServices()
                    .get(MongooseAuditCaptureService.SERVICE_NAME).instance();
            var field = ChronicleAuditCaptureService.class.getDeclaredField("ownedSinks");
            field.setAccessible(true);
            for (int i = 0; i < 8; i++) {
                boolean buildFailure = (i & 1) == 0;
                var added = server.addProcessor("bad", "g", null, () -> buildFailure ? new BadDescription() : new BadStart());
                var reply = added.reply().toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertFalse(reply.applied(), "injected failure must reject the add");
                assertEquals(1, ((java.util.Map<?, ?>) field.get(service)).size(), "only anchor remains attached");
            }
        } finally { server.stop(); assertTrue(server.awaitStopped(10, TimeUnit.SECONDS)); }
    }
}
