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
        implements com.telamin.fluxtion.runtime.audit.Auditor,
                   com.telamin.fluxtion.runtime.service.ServiceListener {

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

    // The real auditor also implements ServiceListener — that is how it
    // receives the MongooseCountersService at runtime and rebinds its per-node
    // counters away from the NoOp default. Fluxtion source-gen only fans
    // `registerService(...)` out to nodes it sees as ServiceListeners at
    // GENERATION time, so the stub must declare it too; otherwise the
    // browser-generated processor never calls perfMon.registerService(...),
    // the counters service is never delivered, and per-node counts are written
    // to no-op counters (nothing shows in the admin console). Bodies are inert
    // — the stub never runs.
    @Override public void registerService(com.telamin.fluxtion.runtime.service.Service<?> service) { }

    @Override public void deRegisterService(com.telamin.fluxtion.runtime.service.Service<?> service) { }
}
