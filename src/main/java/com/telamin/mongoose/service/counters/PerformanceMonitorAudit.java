/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.counters;

import com.telamin.fluxtion.runtime.annotations.ExportService;
import com.telamin.fluxtion.runtime.annotations.builder.FluxtionIgnore;
import com.telamin.fluxtion.runtime.audit.Auditor;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.fluxtion.runtime.service.ServiceListener;
import com.telamin.fluxtion.runtime.time.Clock;
import com.telamin.mongoose.internal.NoOpCountersService;
import com.telamin.mongoose.internal.NoOpLatencyService;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

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
 * <p>The auditor receives the {@link MongooseCountersService} via
 * {@link ServiceListener#registerService(com.telamin.fluxtion.runtime.service.Service)}.
 * It is exported with {@code @ExportService(propagate = false)} so the
 * runtime container dispatches every registered service to it directly —
 * the canonical service-injection path for auditors (Fluxtion's
 * {@code ServiceRegistryNode} only scans <em>nodes</em> for
 * {@code @ServiceRegistered} methods, so that annotation on an auditor
 * is dead code). Until the real service arrives, the auditor uses the
 * no-op service as a safe default — holding a stale auditor reference
 * in tests, or running inside a flow that never registers the counters
 * service, doesn't NPE.
 */
public final class PerformanceMonitorAudit
        implements Auditor,
        @ExportService(propagate = false) ServiceListener {

    static {
        // Clock.DEFAULT_CLOCK is a static singleton, but its wallClock
        // strategy is wired in init() rather than the ctor — so calling
        // getWallClockTime() on a fresh DEFAULT_CLOCK NPEs. Each Fluxtion
        // processor calls init() on ITS own Clock instance via
        // initialiseAuditor, but anyone reaching for the shared static
        // (which is the auditor's default time source) needs to bootstrap
        // it explicitly. Do it once at class load.
        Clock.DEFAULT_CLOCK.init();
    }

    // processorName carries no @FluxtionIgnore: Fluxtion source-gen reads
    // it and emits `new PerformanceMonitorAudit("<value>")` into the
    // generated SEP source. Every other field is runtime state —
    // @FluxtionIgnore tells source-gen to skip rendering them (the Map /
    // interface refs aren't source-renderable anyway), so the generated
    // constructor call is a clean single-arg ctor invocation.
    //
    // Not final: the mongoose runtime overrides it via setProcessorName()
    // when the auditor is hosted under a different YAML processor name.
    private String processorName;
    @FluxtionIgnore private MongooseCountersService counters = NoOpCountersService.INSTANCE;
    @FluxtionIgnore private MongooseCounter eventCounter;
    @FluxtionIgnore private final Map<String, MongooseCounter> nodeCounters = new HashMap<>();
    @FluxtionIgnore private volatile boolean writeEnabled = true;

    // Latency capture (opt-in via the latencyHistograms YAML flag).
    // Default = NoOpLatencyService → recordNodeLatency() is JIT-elided.
    @FluxtionIgnore private MongooseLatencyService latencyService = NoOpLatencyService.INSTANCE;
    // Time source for latency samples — defaults to Fluxtion's data-driven
    // Clock for replay safety. The default clock returns millis, so
    // sub-ms per-node intervals collapse to 0; meaningful sub-ms numbers
    // require swapping in a higher-resolution LongSupplier (e.g. a perf
    // clock backed by System.nanoTime / 1_000_000 — exposed as a hook so
    // we don't bake the policy in here).
    @FluxtionIgnore private LongSupplier timeSource = Clock.DEFAULT_CLOCK::getWallClockTime;
    // Per-event latency state machine. nodeInvoked records the interval
    // since the PREVIOUS nodeInvoked (since intra-event boundaries are
    // implicit — the SPI has no per-node "end" callback). The tail node
    // is recorded on processingComplete.
    @FluxtionIgnore private String lastNodeName;
    @FluxtionIgnore private long lastNodeStartTs;

    public PerformanceMonitorAudit(String processorName) {
        this.processorName = Objects.requireNonNull(processorName, "processorName must be non-null");
        // Pre-populate with the default no-op so a stray callback before
        // init/service-injection doesn't NPE.
        this.eventCounter = counters.processorEventsCounter(processorName);
    }

    // Service injection path. Auditors are NOT scanned by ServiceRegistryNode
    // for @ServiceRegistered methods (that scanning only runs against graph
    // nodes via Auditor.nodeRegistered) — so an @ServiceRegistered method on
    // an Auditor is dead code. Implementing ServiceListener with
    // @ExportService(propagate = false) is the canonical path: the runtime
    // container invokes registerService(...) directly on every exported
    // ServiceListener whenever any service is bound.
    @Override
    public void registerService(Service<?> service) {
        if (MongooseCountersService.class.isAssignableFrom(service.serviceClass())) {
            MongooseCountersService svc = (MongooseCountersService) service.instance();
            this.counters = Objects.requireNonNull(svc, "counters service must be non-null");
            rebindCountersAgainstCurrentService();
        } else if (MongooseLatencyService.class.isAssignableFrom(service.serviceClass())) {
            MongooseLatencyService svc = (MongooseLatencyService) service.instance();
            this.latencyService = Objects.requireNonNull(svc, "latency service must be non-null");
        }
    }

    @Override
    public void deRegisterService(Service<?> service) {
        if (MongooseCountersService.class.isAssignableFrom(service.serviceClass())) {
            // Service went away — fall back to the no-op so we don't NPE.
            this.counters = NoOpCountersService.INSTANCE;
            rebindCountersAgainstCurrentService();
        } else if (MongooseLatencyService.class.isAssignableFrom(service.serviceClass())) {
            this.latencyService = NoOpLatencyService.INSTANCE;
        }
    }

    private void rebindCountersAgainstCurrentService() {
        // Re-bind every cached counter handle against the current service.
        // Fluxtion calls nodeRegistered during the processor CONSTRUCTOR,
        // which runs before the mongoose container dispatches
        // registerService(MongooseCountersService). If we left the handles
        // captured in nodeRegistered alone, they would stay bound to the
        // no-op forever and per-node counters would never tick.
        this.eventCounter = counters.processorEventsCounter(processorName);
        for (Map.Entry<String, MongooseCounter> e : nodeCounters.entrySet()) {
            e.setValue(counters.nodeInvocationCounter(processorName, e.getKey()));
        }
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
        // Fluxtion invokes this once per node from the generated processor
        // CONSTRUCTOR (via initialiseAuditor), which runs BEFORE the mongoose
        // runtime dispatches registerService. So `counters` here is usually
        // still the no-op default; we record the key so registerService can
        // rebind the handle later in rebindCountersAgainstCurrentService().
        nodeCounters.put(nodeName, counters.nodeInvocationCounter(processorName, nodeName));
    }

    @Override
    public void eventReceived(Object event) {
        if (writeEnabled) {
            eventCounter.increment();
        }
        // Reset the latency state machine for the new event. Cheap even
        // when latency capture is off — single nullification.
        lastNodeName = null;
    }

    @Override
    public void nodeInvoked(Object node, String nodeName, String methodName, Object event) {
        if (writeEnabled) {
            MongooseCounter c = nodeCounters.get(nodeName);
            if (c != null) {
                c.increment();
            }
            // The SPI has no per-node end callback, so each nodeInvoked
            // closes out the PREVIOUS node's interval. The tail node is
            // closed by processingComplete.
            long now = timeSource.getAsLong();
            if (lastNodeName != null) {
                latencyService.recordNodeLatency(processorName, lastNodeName, now - lastNodeStartTs);
            }
            lastNodeName = nodeName;
            lastNodeStartTs = now;
        }
    }

    @Override
    public void processingComplete() {
        if (writeEnabled && lastNodeName != null) {
            long now = timeSource.getAsLong();
            latencyService.recordNodeLatency(processorName, lastNodeName, now - lastNodeStartTs);
            lastNodeName = null;
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

    /**
     * Override the time source used for latency samples. Defaults to
     * {@code Clock.DEFAULT_CLOCK::getWallClockTime} (millisecond
     * resolution, replay-safe). Tests inject a deterministic counter;
     * future high-resolution perf clocks plug in here without touching
     * the rest of the auditor.
     */
    public void setTimeSource(LongSupplier timeSource) {
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource must be non-null");
    }

    /**
     * Override the processor name used as the counter-label prefix.
     * Called by the mongoose runtime when registering the host processor,
     * so the auditor's counter namespace matches the YAML processor name
     * regardless of what the SEP author passed to the constructor.
     *
     * <p>Re-walks the existing per-node counter map so handles bound under
     * the old name are reissued under the new one — without this, callers
     * who set the name after {@code nodeRegistered} has run would still
     * write under the original prefix.
     *
     * <p>Safe to call at any lifecycle stage. Idempotent if {@code name}
     * is unchanged.
     */
    public void setProcessorName(String name) {
        Objects.requireNonNull(name, "processorName must be non-null");
        if (name.equals(this.processorName)) {
            return;
        }
        this.processorName = name;
        rebindCountersAgainstCurrentService();
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
