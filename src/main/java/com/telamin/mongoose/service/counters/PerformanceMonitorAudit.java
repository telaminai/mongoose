/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.counters;

import com.telamin.fluxtion.runtime.annotations.builder.FluxtionIgnore;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.audit.Auditor;
import com.telamin.mongoose.internal.NoOpCountersService;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Opt-in Fluxtion {@link Auditor} that writes per-processor and per-node
 * invocation counts into the {@link MongooseCountersService}.
 *
 * <p><b>Binding</b> — added to a Fluxtion processor at build time:
 *
 * <pre>{@code
 * DataFlow flow = Fluxtion.compile(cfg -> {
 *     cfg.addNode(myHandler, "priceCalc");
 *     cfg.addAuditor(new PerformanceMonitorAudit("priceCalc"), "perfMon");
 * });
 * }</pre>
 *
 * <p>No binding → no extra bytecode in the generated SEP → zero overhead,
 * ever. With binding, every event dispatch through the processor bumps
 * the per-processor counter and (when {@link #auditInvocations()} is
 * {@code true}) every node-invocation bumps a per-node counter.
 *
 * <p><b>Runtime toggle</b> — {@link #setWriteEnabled(boolean)} flips a
 * predictable branch around the counter writes. The global counters
 * service is still consulted; when the no-op impl is installed the
 * writes are JIT-eliminated regardless of this flag.
 *
 * <p><b>Counters allocated</b>
 * <ul>
 *     <li>{@code processor.{processorName}.events} — incremented on each
 *     {@code eventReceived(Object)} callback.</li>
 *     <li>{@code node.{processorName}.{nodeName}.invocations} — incremented
 *     on each {@code nodeInvoked(...)} callback.</li>
 * </ul>
 *
 * <p>The {@code @ServiceRegistered} method picks up the
 * {@code MongooseCountersService} from the running data flow. Until it
 * arrives, the auditor uses the no-op service as a safe default — so
 * holding a stale auditor reference in tests, or running the auditor
 * inside a flow that never has the service registered, doesn't NPE.
 */
public final class PerformanceMonitorAudit implements Auditor {

    // processorName carries no @FluxtionIgnore: Fluxtion source-gen reads
    // it and emits `new PerformanceMonitorAudit("<value>")` into the
    // generated SEP source. Every other field is runtime state —
    // @FluxtionIgnore tells source-gen to skip rendering them (the Map /
    // interface refs aren't source-renderable anyway), so the generated
    // constructor call is a clean single-arg ctor invocation.
    private final String processorName;
    @FluxtionIgnore private MongooseCountersService counters = NoOpCountersService.INSTANCE;
    @FluxtionIgnore private MongooseCounter eventCounter;
    @FluxtionIgnore private final Map<String, MongooseCounter> nodeCounters = new HashMap<>();
    @FluxtionIgnore private volatile boolean writeEnabled = true;

    public PerformanceMonitorAudit(String processorName) {
        this.processorName = Objects.requireNonNull(processorName, "processorName must be non-null");
        // Pre-populate with the default no-op so a stray callback before
        // init/service-injection doesn't NPE.
        this.eventCounter = counters.processorEventsCounter(processorName);
    }

    @ServiceRegistered
    public void countersService(MongooseCountersService svc, String name) {
        this.counters = Objects.requireNonNull(svc, "counters service must be non-null");
        // Re-allocate the per-processor handle against the real service.
        // Per-node handles are allocated lazily in nodeRegistered, which
        // Fluxtion invokes after init() — by then the real service is in
        // place and we don't need to re-walk anything.
        this.eventCounter = counters.processorEventsCounter(processorName);
    }

    @Override
    public void init() {
        // Ensure the event counter is bound against whatever service was
        // injected (or the no-op default if none was). Called by Fluxtion
        // exactly once at flow init.
        this.eventCounter = counters.processorEventsCounter(processorName);
    }

    @Override
    public void nodeRegistered(Object node, String nodeName) {
        // Fluxtion invokes this once per node, after init() and before any
        // event processing — the SPI guarantees it's quiescent here, so a
        // plain HashMap is fine.
        nodeCounters.put(nodeName, counters.nodeInvocationCounter(processorName, nodeName));
    }

    @Override
    public void eventReceived(Object event) {
        if (writeEnabled) {
            eventCounter.increment();
        }
    }

    @Override
    public void nodeInvoked(Object node, String nodeName, String methodName, Object event) {
        if (writeEnabled) {
            MongooseCounter c = nodeCounters.get(nodeName);
            if (c != null) {
                c.increment();
            }
        }
    }

    @Override
    public boolean auditInvocations() {
        // Opt in to per-node callbacks — that's the whole point of this
        // auditor; without it nodeInvoked never fires.
        return true;
    }

    @Override
    public void tearDown() {
        // nothing to release — the counter handles outlive the auditor.
    }

    /**
     * Runtime write toggle. When {@code false}, the auditor still receives
     * SPI callbacks but skips the counter writes. Predictable branch,
     * sub-nanosecond when the global service is the no-op anyway.
     */
    public void setWriteEnabled(boolean enabled) {
        this.writeEnabled = enabled;
    }

    public boolean isWriteEnabled() {
        return writeEnabled;
    }

    public String getProcessorName() {
        return processorName;
    }

    // Convenience builder helper (addTo / withPerformanceMonitor) would live
    // here, but mongoose-core deliberately doesn't depend on fluxtion-builder
    // (build-time-only concern, heavyweight). Users call the canonical
    // Fluxtion API directly:
    //
    //     cfg.addAuditor(new PerformanceMonitorAudit("priceCalc"), "perfMon");
    //
    // The mongoose-builder-helpers plugin (separate artifact, depends on
    // fluxtion-builder) can host the sugar if it ever proves useful.
}
