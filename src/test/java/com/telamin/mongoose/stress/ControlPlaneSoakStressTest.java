/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.stress;

import com.telamin.mongoose.StopResult;
import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Item 5 of the stress campaign: the chaos workload as a soak, {@code -Dmongoose.stress.soak.seconds} (default 120),
 * sampling heap after GC, live threads and outstanding control requests every
 * {@code -Dmongoose.stress.soak.sampleSeconds} (default 30), asserting no upward drift, and reporting publish-to-handler
 * latency percentiles for the steady feeds as the anchors see them. A second test measures the same latency on a quiet
 * server (no chaos) as the baseline, for {@code -Dmongoose.stress.latency.seconds} (default 60).
 * <p>
 * The workload runs in soak mode, so the test's own ledger does not grow with the run, and the control audit keeps its
 * default bounded ring (the invariants are judged by the chaos test, where the ring holds the whole run).
 */
@EnabledIfSystemProperty(named = "mongoose.stress", matches = "true")
class ControlPlaneSoakStressTest {

    @BeforeAll
    static void quiet() {
        StressSupport.quietLogging();
    }

    record Sample(long atSeconds, long heapBytes, int threads, long outstanding, long requests, long ledger) { }

    @Test
    void soak_noDriftInHeapThreadsOrOutstandingRequests(@TempDir Path dir) throws Exception {
        long seconds = StressSupport.seconds("mongoose.stress.soak.seconds", 120);
        long sampleSeconds = StressSupport.seconds("mongoose.stress.soak.sampleSeconds", 30);
        ChaosWorkload.Settings settings = new ChaosWorkload.Settings();
        settings.soak = true;
        settings.captureDir = dir.resolve("capture");
        settings.seed = Long.getLong("mongoose.stress.seed", System.nanoTime());
        settings.floodIntervalMillis = Long.getLong("mongoose.stress.floodIntervalMillis", 90_000);
        System.out.println("soak: seconds=" + seconds + " sample=" + sampleSeconds + "s seed=" + settings.seed);

        List<Sample> samples = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        ChaosWorkload chaos = new ChaosWorkload(settings);
        long t0 = System.nanoTime();
        StopResult stop;
        try {
            chaos.startFeeds();
            chaos.startChaos();
            long end = t0 + TimeUnit.SECONDS.toNanos(seconds);
            long next = t0 + TimeUnit.SECONDS.toNanos(sampleSeconds);
            while (System.nanoTime() < end) {
                Thread.sleep(250);
                if (System.nanoTime() < next) continue;
                next += TimeUnit.SECONDS.toNanos(sampleSeconds);
                chaos.pruneReleased(failures);
                Sample s = new Sample(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - t0), StressSupport.heapAfterGc(),
                        StressSupport.threadCount(), chaos.outstandingRequests(), chaos.asyncCount.get(),
                        chaos.instances.size());
                samples.add(s);
                System.out.printf("soak t=%5ds heap=%6.1f MB threads=%3d outstanding=%5d requests=%8d ledger=%5d anomalies=%d%n",
                        s.atSeconds(), s.heapBytes() / 1048576.0, s.threads(), s.outstanding(), s.requests(), s.ledger(),
                        chaos.anomalies.size());
            }
            chaos.stopChaos(60);
            chaos.stopEverything();
            chaos.awaitReplies(60);
            Thread.sleep(6_000);
            chaos.pruneReleased(failures);
            failures.addAll(chaos.checkRunning());
            chaos.stopFeeds();
        } finally {
            stop = chaos.shutdown();
        }
        if (!stop.complete()) failures.add("shutdown incomplete: " + stop);
        failures.addAll(chaos.checkAfterShutdown());

        Histogram latency = chaos.anchorLatency();
        System.out.println("\n===== soak summary (" + seconds + " s, seed " + settings.seed + ") =====");
        System.out.print(chaos.summary());
        System.out.printf("  steady-feed latency under chaos (publish -> anchor handler), %,d samples: p50=%.1f us"
                        + " p99=%.1f us p99.9=%.1f us max=%.1f ms%n", latency.getTotalCount(),
                latency.getValueAtPercentile(50) / 1e3, latency.getValueAtPercentile(99) / 1e3,
                latency.getValueAtPercentile(99.9) / 1e3, latency.getMaxValue() / 1e6);

        // drift: the samples after a warm-up fifth, first half against second half
        failures.addAll(drift(samples));
        System.out.println("  failures: " + failures.size());
        failures.stream().limit(40).forEach(f -> System.out.println("  FAIL " + f));
        assertEquals(List.of(), ChaosWorkload.limit(failures, 40), "soak");
    }

    static List<String> drift(List<Sample> samples) {
        List<String> failures = new ArrayList<>();
        if (samples.size() < 4) {
            System.out.println("  drift: too few samples (" + samples.size() + ") to judge");
            return failures;
        }
        List<Sample> steady = samples.subList(samples.size() / 5, samples.size());
        int half = steady.size() / 2;
        List<Sample> early = steady.subList(0, half), late = steady.subList(half, steady.size());
        double heapEarly = early.stream().mapToLong(Sample::heapBytes).average().orElse(0);
        double heapLate = late.stream().mapToLong(Sample::heapBytes).average().orElse(0);
        int threadsEarly = early.stream().mapToInt(Sample::threads).max().orElse(0);
        int threadsLate = late.stream().mapToInt(Sample::threads).max().orElse(0);
        long outstandingLate = late.stream().mapToLong(Sample::outstanding).max().orElse(0);
        System.out.printf("  drift: heap early=%.1f MB late=%.1f MB; threads early max=%d late max=%d; outstanding late max=%d%n",
                heapEarly / 1048576.0, heapLate / 1048576.0, threadsEarly, threadsLate, outstandingLate);
        if (heapLate > heapEarly * 1.25 + 16 * 1048576.0) {
            failures.add(String.format("heap after GC drifted up: %.1f MB -> %.1f MB", heapEarly / 1048576.0, heapLate / 1048576.0));
        }
        if (threadsLate > threadsEarly + 6) failures.add("thread count drifted up: " + threadsEarly + " -> " + threadsLate);
        // a flood leaves up to a mailbox of requests in flight for a few seconds; outstanding requests must not pile up
        if (outstandingLate > 2 * 4096) failures.add("outstanding control requests piled up: " + outstandingLate);
        return failures;
    }

    /** The baseline: the same feeds and anchors on a quiet server, no chaos. */
    @Test
    void steadyFeedLatency_onAQuietServer(@TempDir Path dir) throws Exception {
        long seconds = StressSupport.seconds("mongoose.stress.latency.seconds", 60);
        ChaosWorkload.Settings settings = new ChaosWorkload.Settings();
        settings.soak = true;
        settings.captures = false;
        settings.feedRatePerSec = Integer.getInteger("mongoose.stress.latency.rate", 10_000);
        ChaosWorkload quiet = new ChaosWorkload(settings);
        try {
            quiet.startFeeds();
            Thread.sleep(5_000);                    // warm-up, then measure afresh
            ChaosWorkload.Instance.latencyFromNanos = System.nanoTime();
            Thread.sleep(TimeUnit.SECONDS.toMillis(seconds));
            quiet.stopFeeds();
        } finally {
            quiet.shutdown();
            ChaosWorkload.Instance.latencyFromNanos = Long.MIN_VALUE;
        }
        Histogram latency = quiet.anchorLatency();
        System.out.printf("%nsteady-feed latency, quiet server, %d feeds x %,d/s, %d anchors, %d s, %,d samples:"
                        + " p50=%.1f us p99=%.1f us p99.9=%.1f us max=%.1f ms%n", settings.feeds, settings.feedRatePerSec,
                settings.groups, seconds, latency.getTotalCount(), latency.getValueAtPercentile(50) / 1e3,
                latency.getValueAtPercentile(99) / 1e3, latency.getValueAtPercentile(99.9) / 1e3,
                latency.getMaxValue() / 1e6);
    }
}
