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

    /**
     * Current and historical captures for one selector, newest first, as an immutable snapshot. A bare name is
     * accepted only when the available history identifies one selector; ambiguity reports the qualified choices.
     * An active capture comes first, followed by opened-at time (last-write time for older files), then capture id.
     * Plugins returning unqualified names retain that namespace; the defaults do not invent a group for old files.
     */
    default List<AuditSinkHandle> capturesFor(String processorSelector) {
        java.util.Objects.requireNonNull(processorSelector, "processorSelector");
        int slash = processorSelector.indexOf('/');
        String qualified = slash < 0 ? null : AuditCaptureIdentity.selector(
                AuditCaptureIdentity.decode(processorSelector.substring(0, slash)),
                AuditCaptureIdentity.decode(processorSelector.substring(slash + 1)));
        List<AuditSinkHandle> matches = listAvailable().stream().filter(handle -> {
            if (qualified != null) return qualified.equals(handle.processorName());
            String selector = handle.processorName();
            if (selector.equals(processorSelector)) return true;
            int separator = selector.indexOf('/');
            if (separator < 0) return false;
            try { return AuditCaptureIdentity.decode(selector.substring(separator + 1)).equals(processorSelector); }
            catch (IllegalArgumentException invalidLegacyName) { return false; }
        }).toList();
        var selectors = matches.stream().map(AuditSinkHandle::processorName).distinct().sorted().toList();
        if (qualified == null && selectors.size() > 1) {
            throw new IllegalArgumentException("ambiguous audit processor '" + processorSelector
                    + "': use one of " + selectors);
        }
        java.util.Comparator<AuditSinkHandle> order = java.util.Comparator.comparing(AuditSinkHandle::isLive).reversed()
                .thenComparing(MongooseAuditIntrospectionService::captureTime, java.util.Comparator.reverseOrder())
                .thenComparing(AuditSinkHandle::id, java.util.Comparator.reverseOrder());
        return matches.stream().sorted(order).toList();
    }

    private static java.time.Instant captureTime(AuditSinkHandle handle) {
        java.time.Instant opened = handle.startedAt();
        if (opened != null && !opened.equals(java.time.Instant.EPOCH)) return opened;
        return handle.lastWriteAt() == null ? java.time.Instant.EPOCH : handle.lastWriteAt();
    }

    /** Exact group/name lookup; historical captures without group metadata are not attributed to an invented group. */
    default List<AuditSinkHandle> capturesFor(String group, String processorName) {
        return capturesFor(AuditCaptureIdentity.selector(group, processorName));
    }

    /** The active capture, or newest historical capture, or null when none matches. */
    default AuditSinkHandle latestCapture(String processorSelector) {
        List<AuditSinkHandle> captures = capturesFor(processorSelector);
        return captures.isEmpty() ? null : captures.getFirst();
    }

    /** Exact group/name form of {@link #latestCapture(String)}. */
    default AuditSinkHandle latestCapture(String group, String processorName) {
        return latestCapture(AuditCaptureIdentity.selector(group, processorName));
    }
}
