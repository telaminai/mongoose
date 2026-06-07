/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.counters;

import com.telamin.fluxtion.runtime.annotations.ExportService;
import com.telamin.fluxtion.runtime.audit.Auditor;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.fluxtion.runtime.service.ServiceListener;

/**
 * Signature-only Java 8 stub of {@code com.telamin.mongoose.service.counters.PerformanceMonitorAudit}.
 * <p>
 * Just enough surface for builder classes in playground examples to type-check:
 * a no-arg-ish constructor that takes the processor name. The real auditor
 * implements {@code Auditor} + {@code @ExportService ServiceListener} and is
 * wired via {@code cfg.addAuditor(new PerformanceMonitorAudit("name"), "perfMon")}.
 * The stub never runs.
 */
public final class PerformanceMonitorAudit
        implements Auditor,
                   @ExportService(propagate = false) ServiceListener {

    // No-arg ctor — Fluxtion source-gen emits `new PerformanceMonitorAudit()`
    // in the generated processor (the processorName field is set separately
    // at runtime via setProcessorName); the type-check has to pass for the
    // browser builder phase even though this stub never executes.
    public PerformanceMonitorAudit() {
    }

    public PerformanceMonitorAudit(String processorName) {
    }

    public void setProcessorName(String name) { }

    public void tearDown() { }

    @Override public void nodeRegistered(Object node, String nodeName) { }

    // Drives per-node instrumentation. Fluxtion source-gen CALLS this on the
    // auditor instance at generation time to decide whether to weave a
    // `perfMon.nodeInvoked(...)` dispatch into every node. The real
    // PerformanceMonitorAudit returns true; the stub must match so the
    // browser-generated processor carries per-node counters
    // (node.{processor}.{nodeName}.invocations) just like the Maven build.
    // Unlike the other stubbed members this returns a real value rather than
    // being a no-op — it is consulted, not executed. The woven
    // nodeInvoked/eventReceived/processingComplete calls resolve to the
    // Auditor interface's default methods, so no extra stub surface is needed.
    @Override public boolean auditInvocations() { return true; }

    // The real auditor also implements ServiceListener, and crucially exports it
    // via @ExportService(propagate = false). That annotation is what makes
    // Fluxtion source-gen delegate the processor's registerService(...) to this
    // node — i.e. emit `perfMon.registerService(svc)` — which is how the
    // MongooseCountersService reaches the auditor at runtime so it rebinds its
    // per-node counters off the NoOp default. A plain `implements ServiceListener`
    // (no annotation) compiles but is NOT exported, so the generated processor
    // never calls perfMon.registerService, the service is never delivered, and
    // per-node counts go to no-op counters (nothing shows in the admin console).
    // Bodies are inert — the stub never runs.
    @Override public void registerService(Service<?> service) { }

    @Override public void deRegisterService(Service<?> service) { }
}
