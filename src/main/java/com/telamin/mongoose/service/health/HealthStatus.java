/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.health;

/**
 * Result of a single {@link MongooseHealthService.HealthCheck} evaluation or
 * the rolled-up verdict for an entire service. Immutable; the static
 * factories cover the common cases.
 *
 * <p>Verdicts rank {@code DOWN > DEGRADED > UNKNOWN > UP} for aggregation.
 * UNKNOWN deliberately ranks <em>above</em> UP so an "I tried but couldn't
 * establish state" signal isn't masked by a rollup.
 *
 * @param verdict     the rolled-up state
 * @param reason      human-readable explanation; {@code null} when verdict is UP
 * @param asOfEpochMs timestamp the verdict was computed (millis since epoch)
 */
public record HealthStatus(Verdict verdict, String reason, long asOfEpochMs) {

    public enum Verdict { UP, DEGRADED, DOWN, UNKNOWN }

    public static HealthStatus up() {
        return new HealthStatus(Verdict.UP, null, System.currentTimeMillis());
    }

    public static HealthStatus down(String why) {
        return new HealthStatus(Verdict.DOWN, why, System.currentTimeMillis());
    }

    public static HealthStatus degraded(String why) {
        return new HealthStatus(Verdict.DEGRADED, why, System.currentTimeMillis());
    }

    public static HealthStatus unknown(String why) {
        return new HealthStatus(Verdict.UNKNOWN, why, System.currentTimeMillis());
    }

    /**
     * Compare two verdicts for aggregation: returns the worse of the two.
     * Order: DOWN > DEGRADED > UNKNOWN > UP. Used by
     * {@link MongooseHealthService#aggregatedVerdict(String)} to roll up
     * a service's active checks.
     */
    public static Verdict worse(Verdict a, Verdict b) {
        return rank(a) >= rank(b) ? a : b;
    }

    private static int rank(Verdict v) {
        return switch (v) {
            case DOWN     -> 3;
            case DEGRADED -> 2;
            case UNKNOWN  -> 1;
            case UP       -> 0;
        };
    }
}
