/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.audit;

import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.AuditCaptureConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.PerformanceMonitoringConfig;
import com.telamin.mongoose.internal.NoOpAuditCaptureService;
import com.telamin.mongoose.internal.NoOpAuditIntrospectionService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 0 of the audit-log-viewer plugin (see
 * docs/audit-log-viewer-plugin-mongoose/README.md in fluxtion-web).
 *
 * <p>Pins the service contract: both audit services are discoverable
 * via the mongoose service registry on every boot, regardless of the
 * YAML flag. Phase 1's Chronicle backend will swap the NoOps for the
 * real impls when the flag is on; until then the NoOps are wired
 * unconditionally so downstream surfaces (svc-admin-web REST + WS) can
 * compose against a guaranteed-present service rather than a nullable.
 *
 * <p>Also exercises the NoOp behavioural contract — isRecording = false,
 * listAvailable empty, currentSink null — so accidental changes to the
 * NoOp impls trip a test rather than silently leaking.
 */
class AuditPhase0ServiceRegistrationTest {

    @Test
    void both_audit_services_are_registered_when_performanceMonitoring_is_off() {
        // Vanilla MongooseServerConfig — no performanceMonitoring block,
        // so the perfCfg branch in MongooseServer is the null arm. Both
        // audit services should still be present (NoOp).
        MongooseServer server = new MongooseServer(new MongooseServerConfig());
        try {
            Map<String, Service<?>> services = server.registeredServices();

            Service<?> capture = services.get(MongooseAuditCaptureService.SERVICE_NAME);
            Service<?> introspect = services.get(MongooseAuditIntrospectionService.SERVICE_NAME);

            assertNotNull(capture, "MongooseAuditCaptureService must be registered");
            assertNotNull(introspect, "MongooseAuditIntrospectionService must be registered");

            assertSame(NoOpAuditCaptureService.INSTANCE, capture.instance(),
                    "with performanceMonitoring absent, NoOp capture service is installed");
            assertSame(NoOpAuditIntrospectionService.INSTANCE, introspect.instance(),
                    "with performanceMonitoring absent, NoOp introspection service is installed");
        } finally {
            server.stop();
        }
    }

    @Test
    void chronicle_backend_is_installed_when_auditCapture_is_enabled() {
        // Phase 1 contract: with the YAML flag on, MongooseServer
        // installs the Chronicle-backed capture + introspection impls.
        // Both must still be discoverable via the service registry.
        MongooseServerConfig cfg = new MongooseServerConfig();
        PerformanceMonitoringConfig pm = new PerformanceMonitoringConfig();
        pm.setEnabled(true);
        AuditCaptureConfig audit = new AuditCaptureConfig();
        audit.setEnabled(true);
        // point at a temp dir so the test isn't writing into ./audit
        audit.setDirectory(System.getProperty("java.io.tmpdir") + "/mongoose-audit-test-" + System.nanoTime());
        pm.setAuditCapture(audit);
        cfg.setPerformanceMonitoring(pm);

        MongooseServer server = new MongooseServer(cfg);
        try {
            Map<String, Service<?>> services = server.registeredServices();
            assertNotNull(services.get(MongooseAuditCaptureService.SERVICE_NAME));
            assertNotNull(services.get(MongooseAuditIntrospectionService.SERVICE_NAME));
            // Phase 1: Chronicle-backed impl, NOT the NoOp.
            org.junit.jupiter.api.Assertions.assertNotSame(
                    NoOpAuditCaptureService.INSTANCE,
                    services.get(MongooseAuditCaptureService.SERVICE_NAME).instance(),
                    "Phase 1: with the flag on, the Chronicle impl replaces the NoOp");
            assertTrue(
                    services.get(MongooseAuditCaptureService.SERVICE_NAME).instance()
                            instanceof com.telamin.mongoose.internal.ChronicleAuditCaptureService,
                    "Phase 1 installs the Chronicle-backed capture service");
        } finally {
            server.stop();
        }
    }

    @Test
    void noop_capture_service_reports_isRecording_false_for_any_processor() {
        MongooseAuditCaptureService svc = NoOpAuditCaptureService.INSTANCE;
        assertFalse(svc.isRecording("pnl-processor"));
        assertFalse(svc.isRecording("datagen-processor"));
        assertFalse(svc.isRecording("does-not-exist"));
        // start/stop should be no-ops, not throw
        svc.start("pnl-processor");
        svc.stop("pnl-processor");
        assertFalse(svc.isRecording("pnl-processor"),
                "NoOp start must not transition into recording");
    }

    @Test
    void noop_introspection_service_returns_empty_collections() {
        MongooseAuditIntrospectionService svc = NoOpAuditIntrospectionService.INSTANCE;
        assertTrue(svc.listAvailable().isEmpty(), "no captures visible when NoOp");
        assertNull(svc.currentSink("pnl-processor"), "no live sink for any processor");
        assertTrue(svc.currentSinks().isEmpty(), "no live sinks at all");
    }

    @Test
    void auditCaptureConfig_defaults_match_the_spec() {
        // Sanity-check the spec'd defaults — if any of these drift the
        // operator-facing YAML docs would need updating in lockstep.
        AuditCaptureConfig cfg = new AuditCaptureConfig();
        assertFalse(cfg.isEnabled(), "default OFF — opt-in semantics");
        assertEquals("chronicle", cfg.getBackend());
        assertEquals("64m", cfg.getRollSize());
        assertEquals(24, cfg.getRetainHours(),
                "spec'd retention default; bump only with doc update");
        assertEquals("./audit", cfg.getDirectory(),
                "spec'd directory default; bump only with doc update");
        assertTrue(cfg.getAutoStart().isEmpty(), "default: no processors auto-start");
    }
}