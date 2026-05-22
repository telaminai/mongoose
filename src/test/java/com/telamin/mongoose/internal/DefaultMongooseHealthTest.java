/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.mongoose.service.health.HealthStatus;
import com.telamin.mongoose.service.health.MongooseHealthService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4.5a — registration, silencing, aggregation, error sink, close.
 * Built-in checks + counter-snapshot ring exercised in Phase 4.5b tests.
 */
class DefaultMongooseHealthTest {

    @Test
    void aggregated_verdict_is_worst_of_active_checks() {
        DefaultMongooseHealth h = new DefaultMongooseHealth(NoOpCountersService.INSTANCE);

        h.registerCheck("fx-feed", "connected", ctx -> HealthStatus.up());
        h.registerCheck("fx-feed", "lag",       ctx -> HealthStatus.degraded("backlog"));
        h.registerCheck("fx-feed", "schema",    ctx -> HealthStatus.unknown("waiting"));

        HealthStatus rolled = h.aggregatedVerdict("fx-feed");
        assertEquals(HealthStatus.Verdict.DEGRADED, rolled.verdict());
        assertEquals("backlog", rolled.reason(),
                "rolled-up reason should match the worst-verdict check");
    }

    @Test
    void verdict_rank_DOWN_outranks_DEGRADED_outranks_UNKNOWN_outranks_UP() {
        DefaultMongooseHealth h = new DefaultMongooseHealth(NoOpCountersService.INSTANCE);

        h.registerCheck("svc", "a", ctx -> HealthStatus.up());
        h.registerCheck("svc", "b", ctx -> HealthStatus.unknown("?"));
        h.registerCheck("svc", "c", ctx -> HealthStatus.degraded("noise"));
        h.registerCheck("svc", "d", ctx -> HealthStatus.down("dead"));

        assertEquals(HealthStatus.Verdict.DOWN, h.aggregatedVerdict("svc").verdict());
        assertEquals("dead", h.aggregatedVerdict("svc").reason());
    }

    @Test
    void silenced_checks_are_excluded_from_the_rollup() {
        DefaultMongooseHealth h = new DefaultMongooseHealth(NoOpCountersService.INSTANCE);

        MongooseHealthService.HealthCheckHandle noisy = h.registerCheck(
                "svc", "noisy-but-known", ctx -> HealthStatus.down("expected during drain"));
        h.registerCheck("svc", "real", ctx -> HealthStatus.up());

        assertEquals(HealthStatus.Verdict.DOWN, h.aggregatedVerdict("svc").verdict(),
                "without silencing the DOWN check should rolls up to DOWN");

        noisy.setEnabled(false);
        HealthStatus rolled = h.aggregatedVerdict("svc");
        assertEquals(HealthStatus.Verdict.UP, rolled.verdict(),
                "silenced DOWN should be excluded → UP is the only active check left");
    }

    @Test
    void all_silenced_falls_back_to_UNKNOWN_with_reason() {
        DefaultMongooseHealth h = new DefaultMongooseHealth(NoOpCountersService.INSTANCE);

        var h1 = h.registerCheck("svc", "a", ctx -> HealthStatus.up());
        var h2 = h.registerCheck("svc", "b", ctx -> HealthStatus.down("x"));
        h1.setEnabled(false);
        h2.setEnabled(false);

        HealthStatus rolled = h.aggregatedVerdict("svc");
        assertEquals(HealthStatus.Verdict.UNKNOWN, rolled.verdict());
        assertEquals("all checks silenced", rolled.reason());
    }

    @Test
    void close_unregisters_the_check_dimension_entirely() {
        DefaultMongooseHealth h = new DefaultMongooseHealth(NoOpCountersService.INSTANCE);

        var handle = h.registerCheck("svc", "transient", ctx -> HealthStatus.down("gone"));
        assertEquals(HealthStatus.Verdict.DOWN, h.aggregatedVerdict("svc").verdict());

        handle.close();
        HealthStatus rolled = h.aggregatedVerdict("svc");
        assertEquals(HealthStatus.Verdict.UNKNOWN, rolled.verdict(),
                "after close, no checks remain → UNKNOWN ('no checks registered')");
    }

    @Test
    void statusOfCheck_unknown_when_pair_unregistered() {
        DefaultMongooseHealth h = new DefaultMongooseHealth(NoOpCountersService.INSTANCE);
        HealthStatus s = h.statusOfCheck("nope", "neither");
        assertEquals(HealthStatus.Verdict.UNKNOWN, s.verdict());
    }

    @Test
    void check_that_throws_does_not_break_aggregation() {
        DefaultMongooseHealth h = new DefaultMongooseHealth(NoOpCountersService.INSTANCE);

        h.registerCheck("svc", "bad",  ctx -> { throw new RuntimeException("boom"); });
        h.registerCheck("svc", "good", ctx -> HealthStatus.up());

        HealthStatus rolled = h.aggregatedVerdict("svc");
        // Throwing check → UNKNOWN; UP + UNKNOWN → UNKNOWN (UNKNOWN outranks UP).
        assertEquals(HealthStatus.Verdict.UNKNOWN, rolled.verdict());
        assertTrue(rolled.reason().contains("check threw"));
    }

    @Test
    void forEachStatus_visits_every_check_across_every_service() {
        DefaultMongooseHealth h = new DefaultMongooseHealth(NoOpCountersService.INSTANCE);

        h.registerCheck("a", "x", ctx -> HealthStatus.up());
        h.registerCheck("a", "y", ctx -> HealthStatus.degraded("?"));
        h.registerCheck("b", "z", ctx -> HealthStatus.down("!"));

        List<String> seen = new ArrayList<>();
        h.forEachStatus((svc, check, st) -> seen.add(svc + "/" + check + "=" + st.verdict()));
        assertEquals(3, seen.size());
        assertTrue(seen.contains("a/x=UP"));
        assertTrue(seen.contains("a/y=DEGRADED"));
        assertTrue(seen.contains("b/z=DOWN"));
    }

    @Test
    void errorSink_holds_a_bounded_ring_newest_first() {
        DefaultMongooseHealth h = new DefaultMongooseHealth(NoOpCountersService.INSTANCE);
        MongooseHealthService.ErrorSink sink = h.errorSink("fx-feed");

        for (int i = 0; i < 20; i++) {
            sink.record("err-" + i, new IllegalStateException("cause-" + i));
        }
        // Bounded at 16 — the last 16 should survive, newest first.
        h.registerCheck("fx-feed", "errors", ctx -> {
            List<MongooseHealthService.ErrorRecord> recent = new ArrayList<>();
            for (var r : ctx.recentErrors()) recent.add(r);
            assertEquals(16, recent.size());
            assertEquals("err-19", recent.get(0).message(), "newest record should be first");
            assertEquals("err-4",  recent.get(15).message(), "oldest retained should be last");
            return HealthStatus.up();
        });
        h.statusOfCheck("fx-feed", "errors");
    }

    @Test
    void countersOperational_signals_whether_real_counters_are_wired() {
        DefaultMongooseHealth h = new DefaultMongooseHealth(NoOpCountersService.INSTANCE);
        h.registerCheck("svc", "probe", ctx -> {
            assertFalse(ctx.countersOperational(),
                    "no-op counters service means checks should fall back to UNKNOWN");
            return HealthStatus.unknown("counters disabled");
        });

        HealthStatus s = h.statusOfCheck("svc", "probe");
        assertNotNull(s);
        assertEquals(HealthStatus.Verdict.UNKNOWN, s.verdict());
    }

    @Test
    void markTicking_is_recorded_and_queryable() {
        DefaultMongooseHealth h = new DefaultMongooseHealth(NoOpCountersService.INSTANCE);
        h.markTicking("fx-feed");
        assertTrue(h.isTicking("fx-feed"));
        assertFalse(h.isTicking("rpc-pool"));
    }

    // ── Phase 4.5b: ring + cache ────────────────────────────────────────

    @Test
    void counterDelta_returns_MIN_VALUE_when_history_is_empty() {
        AgronaCountersService counters = new AgronaCountersService(16);
        counters.feedPublishCounter("fx").increment();
        DefaultMongooseHealth h = new DefaultMongooseHealth(counters);

        // NO recordCounterSnapshot calls — ring is empty.

        long[] captured = {0L};
        h.registerCheck("fx-feed", "probe", ctx -> {
            captured[0] = ctx.counterDelta("feed.fx.published", 5_000);
            return HealthStatus.up();
        });
        h.statusOfCheck("fx-feed", "probe");

        assertEquals(Long.MIN_VALUE, captured[0],
                "empty ring → MIN_VALUE so callers can emit UNKNOWN rather than synthesise");
    }

    @Test
    void counterDelta_over_60s_window_returns_MIN_VALUE_per_contract() {
        AgronaCountersService counters = new AgronaCountersService(16);
        DefaultMongooseHealth h = new DefaultMongooseHealth(counters);
        h.recordCounterSnapshot();

        long[] captured = {0L};
        h.registerCheck("svc", "probe", ctx -> {
            captured[0] = ctx.counterDelta("anything", 90_000);
            return HealthStatus.up();
        });
        h.statusOfCheck("svc", "probe");
        assertEquals(Long.MIN_VALUE, captured[0],
                "windowMs > 60_000 explicitly returns MIN_VALUE; ring's only 60s deep");
    }

    @Test
    void counterDelta_returns_zero_for_non_positive_window() {
        AgronaCountersService counters = new AgronaCountersService(16);
        counters.feedPublishCounter("fx").increment();
        DefaultMongooseHealth h = new DefaultMongooseHealth(counters);
        h.recordCounterSnapshot();

        long[] captured = {-1L};
        h.registerCheck("svc", "probe", ctx -> {
            captured[0] = ctx.counterDelta("feed.fx.published", 0);
            return HealthStatus.up();
        });
        h.statusOfCheck("svc", "probe");
        assertEquals(0L, captured[0], "windowMs <= 0 returns 0");
    }

    @Test
    void counterDelta_against_history_computes_correctly() throws InterruptedException {
        AgronaCountersService counters = new AgronaCountersService(16);
        DefaultMongooseHealth h = new DefaultMongooseHealth(counters);

        // Snapshot @ t0 with value = 100
        for (int i = 0; i < 100; i++) counters.feedPublishCounter("fx").increment();
        h.recordCounterSnapshot();

        // Wait so the next snapshot's ts > current; then take it.
        Thread.sleep(30);
        // Snapshot @ t1 with value = 150
        for (int i = 0; i < 50; i++) counters.feedPublishCounter("fx").increment();
        h.recordCounterSnapshot();

        // Now read with a window that covers t0 — should see delta from t0
        // to now = 50 (or whatever the current value is).
        Thread.sleep(30);
        for (int i = 0; i < 25; i++) counters.feedPublishCounter("fx").increment();

        long[] captured = {0L};
        // disable cache for this probe so we evaluate every call.
        var handle = h.registerCheck("svc", "probe", ctx -> {
            captured[0] = ctx.counterDelta("feed.fx.published", 50_000);
            return HealthStatus.up();
        });
        ((DefaultMongooseHealth.Handle) handle).entry.cacheMs = 0;
        h.statusOfCheck("svc", "probe");

        // counter total = 175; oldest in-window snapshot = 100 → delta = 75
        // (the ring's first snapshot is at t0 which is within the 50s window).
        assertEquals(75L, captured[0], "delta should equal current value minus oldest-in-window snapshot");
    }

    @Test
    void check_evaluation_is_cached_for_1s_by_default() {
        DefaultMongooseHealth h = new DefaultMongooseHealth(NoOpCountersService.INSTANCE);
        int[] evals = {0};
        h.registerCheck("svc", "counted", ctx -> {
            evals[0]++;
            return HealthStatus.up();
        });

        // Three calls within the 1s window — check fires exactly once.
        h.statusOfCheck("svc", "counted");
        h.statusOfCheck("svc", "counted");
        h.statusOfCheck("svc", "counted");
        assertEquals(1, evals[0], "lazy cache should serve the same value for 3 calls within 1s");
    }

    @Test
    void cacheMs_zero_disables_the_cache() {
        DefaultMongooseHealth h = new DefaultMongooseHealth(NoOpCountersService.INSTANCE);
        int[] evals = {0};
        var handle = h.registerCheck("svc", "always-fresh", ctx -> {
            evals[0]++;
            return HealthStatus.up();
        });
        ((DefaultMongooseHealth.Handle) handle).entry.cacheMs = 0;

        h.statusOfCheck("svc", "always-fresh");
        h.statusOfCheck("svc", "always-fresh");
        h.statusOfCheck("svc", "always-fresh");
        assertEquals(3, evals[0], "cacheMs=0 means every call re-evaluates");
    }

    @Test
    void recordCounterSnapshot_is_no_op_when_counters_service_is_no_op() {
        DefaultMongooseHealth h = new DefaultMongooseHealth(NoOpCountersService.INSTANCE);
        h.recordCounterSnapshot();
        h.recordCounterSnapshot();
        h.recordCounterSnapshot();

        // The ring stays empty — counterDelta should still return MIN_VALUE
        // for any non-empty window.
        long[] captured = {0L};
        h.registerCheck("svc", "probe", ctx -> {
            captured[0] = ctx.counterDelta("any", 5_000);
            return HealthStatus.up();
        });
        h.statusOfCheck("svc", "probe");
        assertEquals(Long.MIN_VALUE, captured[0],
                "no-op counters service means recordCounterSnapshot does nothing, " +
                "so the ring stays empty and counterDelta reports insufficient history");
    }
}
