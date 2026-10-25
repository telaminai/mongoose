/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.AuditCaptureConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.PerformanceMonitoringConfig;
import com.telamin.mongoose.service.audit.MongooseAuditCaptureService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Server stop closes every audit capture still recording, once the processors have stopped. Before, nothing called
 * {@code stopAll}, so a capture's Chronicle queue stayed open until the JVM exited.
 */
class ServerStopEndsAuditCaptureTest {

    public static class Quiet implements DataFlow {
        @Override public void onEvent(Object event) {}
        @Override public void addEventFeed(com.telamin.fluxtion.runtime.input.EventFeed eventFeed) {}
    }

    @Test
    void serverStopEndsARecordingCapture(@TempDir Path dir) throws Exception {
        MongooseServerConfig cfg = new MongooseServerConfig();
        cfg.addProcessor("g", new Quiet(), "p");
        AuditCaptureConfig capture = new AuditCaptureConfig();
        capture.setEnabled(true);
        capture.setDirectory(dir.toString());
        capture.setAutoStart(new ArrayList<>(List.of("p")));
        PerformanceMonitoringConfig perf = new PerformanceMonitoringConfig();
        perf.setAuditCapture(capture);
        cfg.setPerformanceMonitoring(perf);
        MongooseServer server = MongooseServer.bootServer(cfg, rec -> { });
        MongooseAuditCaptureService svc = (MongooseAuditCaptureService)
                server.registeredServices().get(MongooseAuditCaptureService.SERVICE_NAME).instance();
        // capture is opened off the group's thread and installed once open (design "Audit capture"): wait for it
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!svc.isRecording("p") && System.nanoTime() < until) Thread.sleep(5);
        assertTrue(svc.isRecording("p"), "precondition: autoStart is recording p");

        server.stop();
        assertTrue(server.awaitStopped(10, java.util.concurrent.TimeUnit.SECONDS), "the server stopped");

        assertFalse(svc.isRecording("p"), "server stop ends the capture and closes its queue");
    }
}
