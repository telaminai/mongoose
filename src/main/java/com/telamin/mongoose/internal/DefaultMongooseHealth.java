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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * Bounded ring of counter snapshots, used by {@link HealthContext#counterDelta}.
     * Sampled at ~1 Hz by {@link #recordCounterSnapshot()} — typically driven
     * by an external scheduler (e.g. svc-admin-web's MonitoringSampler tick).
     * Capacity holds 60 seconds of history; older snapshots evict.
     */
    static final int COUNTER_RING_CAPACITY = 60;
    private final Deque<CounterSnapshot> counterRing = new ArrayDeque<>(COUNTER_RING_CAPACITY);
    /** Monitor for the snapshot ring — sampler + check evaluators contend. */
    private final Object ringMutex = new Object();

    private final MongooseCountersService counters;

    public DefaultMongooseHealth(MongooseCountersService counters) {
        this.counters = counters;
    }

    /**
     * Take a snapshot of every registered counter and append to the ring.
     * Drops the oldest snapshot when at capacity. Called from outside the
     * health service — typically once per second by a sampler thread; the
     * mongoose-core test path drives it manually for determinism.
     *
     * <p>No-op when the counters service is the no-op impl — there's
     * nothing to snapshot.
     */
    public void recordCounterSnapshot() {
        if (counters == null || !counters.isOperational()) return;
        Map<String, Long> values = new HashMap<>();
        counters.forEachCounter((id, label, value) -> values.put(label, value));
        CounterSnapshot snap = new CounterSnapshot(System.currentTimeMillis(), values);
        synchronized (ringMutex) {
            if (counterRing.size() == COUNTER_RING_CAPACITY) counterRing.removeFirst();
            counterRing.addLast(snap);
        }
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
    /**
     * Per-(service, check) state with a lazy evaluation cache. Recomputes
     * the underlying {@link HealthCheck} on demand at most once per
     * {@link #cacheMs}; otherwise returns the cached {@link HealthStatus}.
     *
     * <p>Default cache window is 1 second — bounds the cost of a rapid
     * admin-UI poll against the per-check evaluator. {@code cacheMs = 0}
     * disables the cache (always re-evaluate).
     */
    static final class CheckEntry {
        static final long DEFAULT_CACHE_MS = 1_000L;

        final String serviceName;
        final HealthCheck check;
        volatile boolean enabled = true;
        volatile long cacheMs = DEFAULT_CACHE_MS;
        // Cached most-recent evaluation. The volatile reference + epoch
        // pair is loose under concurrent reads — different threads may
        // see slightly different cached views during the window after a
        // refresh, but that's fine: every check eventually settles on
        // the same value within cacheMs.
        private volatile HealthStatus cachedStatus;
        private volatile long lastEvalEpochMs;

        CheckEntry(String serviceName, HealthCheck check) {
            this.serviceName = serviceName;
            this.check = check;
        }

        HealthStatus evaluate(DefaultMongooseHealth host) {
            long now = System.currentTimeMillis();
            HealthStatus cached = cachedStatus;
            if (cached != null && cacheMs > 0 && (now - lastEvalEpochMs) < cacheMs) {
                return cached;
            }
            HealthStatus fresh;
            try {
                HealthStatus s = check.evaluate(host.contextFor(serviceName));
                fresh = s != null ? s : HealthStatus.unknown("check returned null");
            } catch (Throwable t) {
                fresh = HealthStatus.unknown("check threw: " + t.getClass().getSimpleName());
            }
            cachedStatus = fresh;
            lastEvalEpochMs = now;
            return fresh;
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
                // Current value: walk forEachCounter once. Cheap when
                // counters operational; the no-op walks nothing.
                long[] out = {0L};
                if (counters != null) {
                    counters.forEachCounter((id, l, v) -> {
                        if (l.equals(label)) out[0] = v;
                    });
                }
                return out[0];
            }
            @Override public long counterDelta(String label, long windowMs) {
                // Per the design doc:
                //   windowMs > 60_000   → Long.MIN_VALUE ("history insufficient")
                //   windowMs <= 0       → 0
                //   otherwise           → current - snapshot_at_or_before(now - windowMs)
                if (windowMs > 60_000L) return Long.MIN_VALUE;
                if (windowMs <= 0L) return 0L;
                long target = System.currentTimeMillis() - windowMs;
                long now = counter(label);
                long past;
                synchronized (ringMutex) {
                    past = lookupAtOrBefore(label, target);
                }
                if (past == Long.MIN_VALUE) return Long.MIN_VALUE; // not enough history yet
                return now - past;
            }
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

    /**
     * Return the value of {@code label} as of the requested target time,
     * using the snapshot ring. Semantics:
     *
     * <ul>
     *   <li><b>Empty ring</b> → {@link Long#MIN_VALUE} (no history to compare against).</li>
     *   <li><b>Target before oldest snapshot</b> → use the oldest available
     *       snapshot (best-effort; delta is still meaningful, just over a
     *       shorter window than requested).</li>
     *   <li><b>Target within history</b> → most recent snapshot with
     *       {@code ts ≤ target}.</li>
     * </ul>
     *
     * <p>Caller must hold {@link #ringMutex}.
     */
    private long lookupAtOrBefore(String label, long target) {
        if (counterRing.isEmpty()) return Long.MIN_VALUE;
        // Walk oldest → newest. If we see any snapshot with ts ≤ target,
        // remember its value; the last such hit is "most recent ≤ target".
        // If we never see one (target is before the entire ring), fall
        // back to the oldest snapshot value.
        Long best = null;
        Long oldest = null;
        for (CounterSnapshot s : counterRing) {
            Long v = s.values.get(label);
            long resolved = (v == null) ? 0L : v;
            if (oldest == null) oldest = resolved;
            if (s.ts > target) break;
            best = resolved;
        }
        return (best != null) ? best : oldest;
    }

    /** Snapshot record — ts + immutable values map (the map IS shared but never mutated after publish). */
    record CounterSnapshot(long ts, Map<String, Long> values) {}

    final class Handle implements HealthCheckHandle {
        private final String svc;
        private final String check;
        // Package-private so tests can poke cacheMs on the entry. Production
        // code should never reach inside the handle — the surface contract
        // is setEnabled / isEnabled / close. A future iteration can add a
        // public setCacheMs to the HealthCheckHandle interface.
        final CheckEntry entry;

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
