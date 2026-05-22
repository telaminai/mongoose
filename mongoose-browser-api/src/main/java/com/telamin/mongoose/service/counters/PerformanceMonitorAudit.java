/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.counters;

/**
 * Signature-only Java 8 stub of {@code com.telamin.mongoose.service.counters.PerformanceMonitorAudit}.
 * <p>
 * Just enough surface for builder classes in playground examples to type-check:
 * a no-arg-ish constructor that takes the processor name. The real auditor
 * implements {@code com.telamin.fluxtion.runtime.audit.Auditor} and is wired
 * via {@code cfg.addAuditor(new PerformanceMonitorAudit("name"), "perfMon")}.
 * The stub never runs.
 */
public final class PerformanceMonitorAudit
        implements com.telamin.fluxtion.runtime.audit.Auditor {

    public PerformanceMonitorAudit(String processorName) {
    }

    @Override public void nodeRegistered(Object node, String nodeName) { }
}
