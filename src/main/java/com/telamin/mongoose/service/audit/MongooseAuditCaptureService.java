/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.audit;

/**
 * CONTROL surface for the audit-log capture plugin. Starts and stops
 * per-processor audit recording into a persistent backend (Chronicle
 * BinaryWire from Phase 1 onwards; NoOp default).
 *
 * <p>Paired with {@link MongooseAuditIntrospectionService}, the
 * read-only surface that lists available captures and exposes sink
 * metadata. The split keeps callers that only need to LIST audit files
 * from holding a reference to anything that can mutate capture state —
 * mirrors the existing
 * {@code MongooseServerController} (control) /
 * {@code MongooseIntrospectionService} (read) split already in the
 * codebase.
 *
 * <p>Recording is OFF by default for every processor at boot, even when
 * the service is installed (i.e. {@code performanceMonitoring.auditCapture.enabled: true}).
 * The operator opts each processor in via:
 * <ul>
 *   <li>YAML {@code performanceMonitoring.auditCapture.autoStart: [pnl-processor, …]} —
 *       starts at boot;</li>
 *   <li>{@code POST /api/audit/&#123;processor&#125;/start} on svc-admin-web;</li>
 *   <li>{@code audit.start &lt;processor&gt;} admin command;</li>
 *   <li>{@code MongooseAuditCaptureService.start(name)} directly from Java.</li>
 * </ul>
 *
 * <p>When the NoOp impl is installed, every method is a JIT-elided no-op
 * and {@link #isRecording(String)} returns false unconditionally.
 */
public interface MongooseAuditCaptureService {

    String SERVICE_NAME = "com.telamin.mongoose.service.audit.MongooseAuditCaptureService";

    /**
     * Begin capturing audit records for the named processor. The
     * processor must already be registered with the mongoose runtime
     * (i.e. live in {@code MongooseServer.registeredProcessors()}).
     * Idempotent — calling on an already-recording processor is a no-op
     * and does not roll the underlying sink.
     *
     * @param processorName the YAML processor name (as it appears in
     *                      {@code eventHandlers.&lt;name&gt;}).
     * @throws IllegalArgumentException if no processor with that name
     *         is registered.
     */
    void start(String processorName);

    /**
     * Halt capture for the named processor. Flushes any pending records
     * and closes the sink. Idempotent — calling on a non-recording
     * processor is a no-op.
     */
    void stop(String processorName);

    /**
     * True iff capture is currently active for the named processor.
     * Constant-time lookup; safe to call from any thread.
     */
    boolean isRecording(String processorName);
}