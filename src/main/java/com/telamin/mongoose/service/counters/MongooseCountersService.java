/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.counters;

/**
 * Published interface for hot-path counters in a running Mongoose server.
 *
 * <p>Counters are the numeric substrate underneath the throughput dashboard
 * (svc-admin-web), future metric exporters (Prometheus / OTLP), and the
 * health service. The same surface is consumed by all of them: hot-path
 * components allocate handles at registration time and increment on each
 * event; sampling consumers walk the registered counters via
 * {@link #forEachCounter(CounterVisitor)} once per second.
 *
 * <p>Two implementations exist, selected at JVM boot by the global
 * {@code mongoose.performanceMonitoring.enabled} flag:
 * <ul>
 *     <li><b>No-op</b> — every handle is a shared sentinel whose methods
 *     are empty. Because the implementation class is uniquely loaded across
 *     the JVM and call sites are monomorphic, C2 inlines the no-op
 *     {@code increment()} to nothing.</li>
 *     <li><b>Agrona-backed</b> — counters live in an on-heap
 *     {@code UnsafeBuffer} managed by {@code CountersManager}; allocation
 *     and label-lookup are confined to startup, and increments are a single
 *     volatile add.</li>
 * </ul>
 *
 * <p>The service is registered into the service registry at boot, the same
 * way as {@code AdminCommandRegistry} and {@code MongooseIntrospectionService};
 * consumers inject via {@code @ServiceRegistered}.
 */
public interface MongooseCountersService {

    String SERVICE_NAME = "com.telamin.mongoose.service.counters.MongooseCountersService";

    /**
     * Allocate (or fetch the cached handle for) a counter tracking events
     * published into the given feed. Label form: {@code feed.{name}.published}.
     */
    MongooseCounter feedPublishCounter(String feed);

    /**
     * Counter tracking events dispatched by the given agent group.
     * Label form: {@code group.{name}.processed}.
     */
    MongooseCounter agentEventsCounter(String group);

    /**
     * Counter tracking idle cycles in the given agent group's main loop.
     * Label form: {@code group.{name}.idleCycles}.
     */
    MongooseCounter agentIdleCyclesCounter(String group);

    /**
     * Gauge tracking the current backlog depth of the given queue path.
     * Label form: {@code queue.{path}.depth}. Written via
     * {@link MongooseCounter#setOrdered(long)} rather than increment.
     */
    MongooseCounter queueDepthGauge(String path);

    /**
     * Counter tracking events dispatched to the given processor.
     * Label form: {@code processor.{name}.events}.
     */
    MongooseCounter processorEventsCounter(String processor);

    /**
     * Counter tracking invocations of a single node inside a processor.
     * Label form: {@code node.{processor}.{node}.invocations}. Populated
     * only when a {@code PerformanceMonitorAudit} is bound at build time
     * (Phase 3).
     */
    MongooseCounter nodeInvocationCounter(String processor, String node);

    /**
     * Walk every registered counter. Called by sampling consumers (svc-admin-web,
     * future Prometheus / OTLP exporters) on a 1 Hz tick. No allocation per
     * visit; the visitor receives an internal counter id, the flat label, and
     * the current value.
     *
     * <p>The no-op implementation visits nothing — it tracks nothing to visit.
     */
    void forEachCounter(CounterVisitor visitor);

    /**
     * @return {@code true} when this is a real (Agrona-backed) counters
     * service, {@code false} for the no-op. Health checks consult this to
     * decide whether to emit {@code UNKNOWN} ("counters disabled") rather
     * than misreporting DOWN due to zero counter reads.
     */
    boolean isOperational();

    /**
     * Visitor passed to {@link #forEachCounter(CounterVisitor)}.
     */
    @FunctionalInterface
    interface CounterVisitor {
        /**
         * @param id    internal counter id (stable for the lifetime of the JVM)
         * @param label flat counter label, e.g. {@code feed.fx-market-data.published}
         * @param value the current counter value
         */
        void visit(int id, String label, long value);
    }
}
