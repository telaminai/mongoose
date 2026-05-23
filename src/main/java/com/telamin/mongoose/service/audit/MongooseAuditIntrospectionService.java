/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.audit;

import java.util.List;
import java.util.Map;

/**
 * READ-ONLY surface for the audit-log capture plugin. Backs the file
 * picker, status pill, and metadata endpoints on the svc-admin-web
 * Replay tab; never mutates state.
 *
 * <p>Paired with {@link MongooseAuditCaptureService} (the control
 * surface). Callers that only need to list available captures or look
 * up sink metadata should depend on this interface, not the capture
 * service.
 *
 * <p><b>Cost.</b> A naïve {@link #listAvailable()} walks the audit
 * directory every call — O(files). At long retention with high-
 * throughput captures that's thousands of files. The default impl
 * caches the result and invalidates the cache on capture roll / janitor
 * sweep, so the steady-state cost is O(1) reads with periodic O(files)
 * rebuilds. Subclasses must preserve that contract.
 */
public interface MongooseAuditIntrospectionService {

    String SERVICE_NAME = "com.telamin.mongoose.service.audit.MongooseAuditIntrospectionService";

    /**
     * Every audit file currently visible in the configured audit
     * directory, oldest first. Includes both closed historical files
     * and the live sink(s) for processors currently recording — call
     * {@link AuditSinkHandle#isLive()} to distinguish.
     *
     * <p>Returns an empty list when the capture service is the NoOp
     * (i.e. {@code performanceMonitoring.auditCapture.enabled: false}).
     */
    List<AuditSinkHandle> listAvailable();

    /**
     * The active sink for the named processor, or {@code null} if that
     * processor is not currently recording. Cheap; does not touch disk.
     */
    AuditSinkHandle currentSink(String processorName);

    /**
     * Every active sink keyed by processor name. Snapshot — subsequent
     * mutations on the capture service are not reflected.
     */
    Map<String, AuditSinkHandle> currentSinks();
}