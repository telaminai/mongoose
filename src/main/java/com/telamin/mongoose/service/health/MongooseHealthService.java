/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.health;

/**
 * Per-service health verdict registry. Sibling to
 * {@link com.telamin.mongoose.service.counters.MongooseCountersService}:
 * counters carry the raw <em>numeric</em> signal, this surface carries the
 * structured <em>verdict</em> ({@code UP / DEGRADED / DOWN / UNKNOWN}) that
 * the admin UI badges and {@code /api/health} return.
 *
 * <p>Each Mongoose service can register one or more checks under its name.
 * The aggregated verdict for a service is the worst-of across its
 * <em>active</em> (non-silenced) checks; silenced checks are excluded from
 * the rollup rather than reported as UNKNOWN, since silencing is an
 * affirmative "ignore this dimension" decision and a k8s readiness probe
 * shouldn't fail on a muted check.
 *
 * <p>Phase 4.5a — interface + skeleton default impl. Built-in checks
 * (liveness / error-spike / connected) and the counter-snapshot ring
 * backing {@link HealthContext#counterDelta(String, long)} land in 4.5b.
 *
 * @see HealthStatus
 */
public interface MongooseHealthService {

    String SERVICE_NAME = "com.telamin.mongoose.service.health.MongooseHealthService";

    // ── registration ─────────────────────────────────────────────────────

    /**
     * Register a check under {@code serviceName}. A service may register
     * many checks (e.g. {@code connected}, {@code lag}, {@code error-rate}).
     *
     * <p>The returned handle is the runtime control surface:
     * {@link HealthCheckHandle#setEnabled(boolean) setEnabled(false)}
     * silences the check (excluded from {@link #aggregatedVerdict(String)})
     * and {@link HealthCheckHandle#close()} unregisters it entirely.
     *
     * <p>The normal pattern is "register at service start, hold the handle
     * for the service's lifetime, never close it" — {@link AutoCloseable}
     * is for try-with-resources discoverability, not the everyday usage.
     */
    HealthCheckHandle registerCheck(String serviceName, String checkName, HealthCheck check);

    /**
     * Opt the service into the built-in liveness check. Services that tick
     * (event feeds, periodic workers) call this once at registration so
     * the liveness check evaluates {@code lastTickEpoch} delta against the
     * configured {@code livenessWindowMs}. Pure RPC services don't call
     * it; the liveness check is skipped for them rather than emitting a
     * permanent UNKNOWN.
     *
     * <p>{@code markTicking} is permanent for the JVM lifetime — services
     * that go quiet are diagnosed by the liveness check itself going DOWN.
     */
    void markTicking(String serviceName);

    /**
     * Per-service error sink — same handle-cached pattern as counters.
     * Service caches the returned reference at registration; the bounded
     * ring (~16 entries) holds the last-N errors as {@link ErrorRecord}.
     * Backed by an MPSC structure since errors can arrive from any thread.
     */
    ErrorSink errorSink(String serviceName);

    // ── querying ─────────────────────────────────────────────────────────

    /**
     * Status for a single check on a service. For the rolled-up "what
     * verdict does this service show?" answer, use {@link #aggregatedVerdict(String)}.
     *
     * @return {@code UNKNOWN} ("check not registered") when the
     * {@code (serviceName, checkName)} pair isn't in the registry
     */
    HealthStatus statusOfCheck(String serviceName, String checkName);

    /**
     * Aggregated per-service verdict — worst-of across every <em>active</em>
     * (non-silenced) check registered for that service. Silenced checks
     * are excluded from the rollup. If <em>every</em> check is silenced,
     * the result is {@code UNKNOWN} with reason "all checks silenced".
     *
     * <p>This is what the admin UI Services-view badge and {@code /api/health}
     * surface; {@link #statusOfCheck(String, String)} is the drill-down.
     */
    HealthStatus aggregatedVerdict(String serviceName);

    /**
     * Walk every registered check, calling the visitor for each. Used by
     * the admin sampler to populate the per-check drill-down. No
     * allocation per visit; the visitor receives the (serviceName,
     * checkName, status) tuple directly.
     *
     * <p>Skips silenced checks unless {@code includeSilenced} is set.
     */
    void forEachStatus(StatusVisitor visitor);

    // ── nested types ─────────────────────────────────────────────────────

    /** A health-check predicate. */
    @FunctionalInterface
    interface HealthCheck {
        HealthStatus evaluate(HealthContext ctx);
    }

    /** Visitor for {@link #forEachStatus(StatusVisitor)}. */
    @FunctionalInterface
    interface StatusVisitor {
        void visit(String serviceName, String checkName, HealthStatus status);
    }

    /**
     * Read-only view passed to a {@link HealthCheck}. Counters + last-errors
     * for the service the check belongs to. Composable: a check shouldn't
     * reach beyond its own service (no global counter walk).
     */
    interface HealthContext {
        /** Current counter value, or {@code 0} if absent. */
        long counter(String label);

        /**
         * {@code value(now) − value(now − windowMs)} from the 1-Hz × 60-s
         * counter-snapshot ring. {@code windowMs > 60_000} returns
         * {@code Long.MIN_VALUE} — callers should treat that as "history
         * insufficient" and emit UNKNOWN rather than synthesise a value.
         * {@code windowMs <= 0} returns {@code 0}.
         */
        long counterDelta(String label, long windowMs);

        /** Last-N errors for this service, newest first. */
        Iterable<ErrorRecord> recentErrors();

        long nowEpochMs();

        /**
         * Whether the underlying {@link com.telamin.mongoose.service.counters.MongooseCountersService}
         * is operational (Agrona-backed) or the no-op singleton. Built-in
         * checks consult this to return UNKNOWN ("counters disabled") rather
         * than synthesising DOWN from zero counter reads.
         */
        boolean countersOperational();
    }

    /** Per-service error ingest handle. Holds a bounded MPSC ring. */
    interface ErrorSink {
        void record(String message, Throwable cause);
    }

    /** What survives in the per-service error ring. Stack trace dropped. */
    record ErrorRecord(long epochMs, String errorClass, String message) {}

    /**
     * Runtime control surface for a registered check. {@code close()} is
     * the explicit-unregister method — not intended for try-with-resources.
     * Hold the handle for the service's lifetime; close only when the
     * dimension stops applying (e.g. a feed has been permanently
     * disconnected).
     */
    interface HealthCheckHandle extends AutoCloseable {
        /** Silence / unsilence. Silenced checks are excluded from {@link #aggregatedVerdict}. */
        void setEnabled(boolean enabled);
        boolean isEnabled();
        /** Fully unregister; the verdict drops this dimension entirely. */
        @Override void close();
    }
}
