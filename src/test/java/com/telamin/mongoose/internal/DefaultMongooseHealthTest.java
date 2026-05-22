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
}
