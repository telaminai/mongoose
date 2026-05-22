/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.mongoose.service.counters.MongooseCountersService;
import com.telamin.mongoose.service.health.HealthStatus;
import com.telamin.mongoose.service.health.MongooseHealthService;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Default {@link MongooseHealthService} implementation. Phase 4.5a:
 * registration, silencing, aggregation, error sinks. Built-in liveness /
 * error-spike / connected checks land in 4.5b along with the 60-s × 1-Hz
 * counter-snapshot ring backing
 * {@link com.telamin.mongoose.service.health.MongooseHealthService.HealthContext#counterDelta(String, long)}.
 *
 * <p>Owned by {@link com.telamin.mongoose.MongooseServer}, registered into
 * the service registry alongside the counters service.
 */
public final class DefaultMongooseHealth implements MongooseHealthService {

    /** Per (serviceName, checkName) registry of active and silenced checks. */
    private final ConcurrentMap<String, ConcurrentMap<String, CheckEntry>> bySvc = new ConcurrentHashMap<>();
    /** Services that opted in via {@link #markTicking(String)}. */
    private final Set<String> tickingServices = ConcurrentHashMap.newKeySet();
    /** Per-service error sinks. */
    private final ConcurrentMap<String, BoundedErrorSink> errorSinks = new ConcurrentHashMap<>();

    private final MongooseCountersService counters;

    public DefaultMongooseHealth(MongooseCountersService counters) {
        this.counters = counters;
    }

    // ── registration ────────────────────────────────────────────────────

    @Override
    public HealthCheckHandle registerCheck(String serviceName, String checkName, HealthCheck check) {
        CheckEntry entry = new CheckEntry(serviceName, check);
        bySvc.computeIfAbsent(serviceName, k -> new ConcurrentHashMap<>())
                .put(checkName, entry);
        return new Handle(serviceName, checkName, entry);
    }

    @Override
    public void markTicking(String serviceName) {
        tickingServices.add(serviceName);
    }

    @Override
    public ErrorSink errorSink(String serviceName) {
        return errorSinks.computeIfAbsent(serviceName, k -> new BoundedErrorSink());
    }

    boolean isTicking(String serviceName) {
        return tickingServices.contains(serviceName);
    }

    // ── querying ────────────────────────────────────────────────────────

    @Override
    public HealthStatus statusOfCheck(String serviceName, String checkName) {
        ConcurrentMap<String, CheckEntry> svc = bySvc.get(serviceName);
        if (svc == null) return HealthStatus.unknown("check not registered");
        CheckEntry entry = svc.get(checkName);
        if (entry == null) return HealthStatus.unknown("check not registered");
        return entry.evaluate(this);
    }

    @Override
    public HealthStatus aggregatedVerdict(String serviceName) {
        ConcurrentMap<String, CheckEntry> svc = bySvc.get(serviceName);
        if (svc == null || svc.isEmpty()) {
            return HealthStatus.unknown("no checks registered");
        }
        HealthStatus.Verdict worst = HealthStatus.Verdict.UP;
        String worstReason = null;
        long worstTs = System.currentTimeMillis();
        int activeChecks = 0;
        for (var e : svc.entrySet()) {
            CheckEntry entry = e.getValue();
            if (!entry.enabled) continue; // silenced — excluded from rollup
            activeChecks++;
            HealthStatus s = entry.evaluate(this);
            HealthStatus.Verdict next = HealthStatus.worse(worst, s.verdict());
            if (next != worst) {
                worst = next;
                worstReason = s.reason();
                worstTs = s.asOfEpochMs();
            }
        }
        if (activeChecks == 0) {
            return HealthStatus.unknown("all checks silenced");
        }
        return new HealthStatus(worst, worstReason, worstTs);
    }

    @Override
    public void forEachStatus(StatusVisitor visitor) {
        for (var svcEntry : bySvc.entrySet()) {
            for (var checkEntry : svcEntry.getValue().entrySet()) {
                HealthStatus s = checkEntry.getValue().evaluate(this);
                visitor.visit(svcEntry.getKey(), checkEntry.getKey(), s);
            }
        }
    }

    // Phase 4.5b will use these for the counter-history ring + built-in checks.
    MongooseCountersService counters() { return counters; }

    // ── nested impl types ───────────────────────────────────────────────

    /**
     * Per-(service, check) state. Holds the user's HealthCheck plus the
     * silence flag. Phase 4.5b will add the lazy-cache (cachedStatus +
     * lastEvalEpochMs + cacheMs).
     */
    static final class CheckEntry {
        final String serviceName;
        final HealthCheck check;
        volatile boolean enabled = true;
        // Phase 4.5b: cached status + lastEvalEpochMs + cacheMs.

        CheckEntry(String serviceName, HealthCheck check) {
            this.serviceName = serviceName;
            this.check = check;
        }

        HealthStatus evaluate(DefaultMongooseHealth host) {
            try {
                HealthStatus s = check.evaluate(host.contextFor(serviceName));
                return s != null ? s : HealthStatus.unknown("check returned null");
            } catch (Throwable t) {
                return HealthStatus.unknown("check threw: " + t.getClass().getSimpleName());
            }
        }
    }

    /**
     * Phase 4.5a HealthContext stub — minimal surface for the skeleton.
     * Phase 4.5b replaces with the full impl reading from the 60-s × 1-Hz
     * counter-snapshot ring.
     */
    HealthContext contextFor(String serviceName) {
        return new HealthContext() {
            @Override public long counter(String label) {
                // Phase 4.5a: stub. 4.5b wires up the counter-snapshot ring
                // and reads the current value from it. Returning 0 for now
                // means user-defined checks that reference a counter will
                // either pass (if their condition is "delta > 0") or fail
                // (if "value > 0") — until 4.5b, custom checks that need
                // counter data should consult countersOperational() first
                // and emit UNKNOWN when false.
                return 0L;
            }
            @Override public long counterDelta(String label, long windowMs) { return 0L; }
            @Override public Iterable<ErrorRecord> recentErrors() {
                BoundedErrorSink sink = errorSinks.get(serviceName);
                return sink == null ? Collections.emptyList() : sink.snapshotNewestFirst();
            }
            @Override public long nowEpochMs() { return System.currentTimeMillis(); }
            @Override public boolean countersOperational() {
                return counters != null && counters.isOperational();
            }
        };
    }

    final class Handle implements HealthCheckHandle {
        private final String svc;
        private final String check;
        private final CheckEntry entry;

        Handle(String svc, String check, CheckEntry entry) {
            this.svc = svc;
            this.check = check;
            this.entry = entry;
        }

        @Override public void setEnabled(boolean enabled) { entry.enabled = enabled; }
        @Override public boolean isEnabled() { return entry.enabled; }

        @Override
        public void close() {
            ConcurrentMap<String, CheckEntry> map = bySvc.get(svc);
            if (map != null) {
                map.remove(check, entry);
                if (map.isEmpty()) bySvc.remove(svc, map);
            }
        }
    }

    /**
     * Bounded MPSC error ring. Phase 4.5a uses a synchronised ArrayDeque
     * since 16 entries × infrequent error writes is below any meaningful
     * contention; Phase 4.5b can swap to Agrona's ManyToOneRingBuffer if
     * profiling shows the synchronized block becomes a problem.
     */
    static final class BoundedErrorSink implements ErrorSink {
        static final int CAPACITY = 16;
        private final Deque<ErrorRecord> ring = new ArrayDeque<>(CAPACITY);

        @Override
        public synchronized void record(String message, Throwable cause) {
            if (ring.size() == CAPACITY) ring.removeFirst();
            String cls = cause != null ? cause.getClass().getSimpleName() : "—";
            ring.addLast(new ErrorRecord(System.currentTimeMillis(), cls, message));
        }

        synchronized List<ErrorRecord> snapshotNewestFirst() {
            // ring is oldest-first (addLast); reverse so callers see the
            // most-recent error first, which is what the UI wants.
            List<ErrorRecord> out = new java.util.ArrayList<>(ring);
            Collections.reverse(out);
            return out;
        }
    }
}
