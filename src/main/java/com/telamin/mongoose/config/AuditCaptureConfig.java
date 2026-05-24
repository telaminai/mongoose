/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.config;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * YAML-driven configuration for the audit-log capture plugin. Nests
 * under {@link PerformanceMonitoringConfig#getAuditCapture()}:
 *
 * <pre>
 * performanceMonitoring:
 *   enabled: true
 *   counterBufferKb: 256
 *   latencyHistograms: true
 *   auditCapture:
 *     enabled: false           # default off — opt in to install the Chronicle impl
 *     backend: chronicle       # primary backend (Chronicle BinaryWire on disk);
 *                              # JSONL/YAML are produced via the /export endpoint,
 *                              # not as a separate capture backend
 *     rollSize: 64m            # Chronicle roll size — files stay scannable
 *     retainHours: 24          # janitor prunes files older than this
 *     directory: ./audit       # relative to working dir; matches Chronicle conventions
 *                              # elsewhere in the stack
 *     autoStart: []            # processor names to start capturing at boot; [] = none
 * </pre>
 *
 * <p>When {@code enabled} is {@code false} (the default) the server
 * installs the NoOp capture + introspection services. Hot-path
 * components see a NoOp and the JIT inlines start/stop/isRecording to
 * nothing — zero overhead.
 *
 * <p>Recording is also OFF per-processor by default even when the
 * Chronicle impl is installed. The operator opts processors in via
 * {@link #getAutoStart()} (boot-time), the {@code audit.start} admin
 * command, the {@code POST /api/audit/&#123;processor&#125;/start} REST
 * endpoint, or directly via
 * {@link com.telamin.mongoose.service.audit.MongooseAuditCaptureService#start(String)}.
 */
@Data
public class AuditCaptureConfig {

    /** Install the Chronicle-backed capture service. Default: {@code false}. */
    private boolean enabled = false;

    /**
     * Persistence backend. Currently only {@code chronicle} is
     * implemented; {@code aeron} is a planned future option. The
     * JSONL / YAML formats expected by the desktop fluxtion-visualiser
     * are produced via the {@code /api/audit/file/&#123;id&#125;/export}
     * endpoint at read time, not as separate capture backends.
     */
    private String backend = "chronicle";

    /**
     * Chronicle Queue roll size — controls how often capture rolls
     * into a new file. Smaller values make individual files easier to
     * scan / download / fingerprint; larger values reduce file count
     * at high throughput. Format: "{n}m" (megabytes), "{n}g" (gigabytes).
     */
    private String rollSize = "64m";

    /**
     * Janitor retention window. Files older than this are pruned by
     * the janitor coroutine. Default 24h matches "find me yesterday's
     * incident"; bump to 48–720h for pharma / audit-evidence retention
     * requirements.
     */
    private int retainHours = 24;

    /**
     * Where the audit files live on disk. Relative to the working
     * directory by default, matching how Chronicle Queue paths default
     * everywhere else in the stack.
     */
    private String directory = "./audit";

    /**
     * Processor names to start capturing at boot. Empty by default —
     * recording is opt-in even when the service is installed.
     */
    private List<String> autoStart = new ArrayList<>();
}