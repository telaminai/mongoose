/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.stress;

import com.telamin.mongoose.StopResult;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Item 2 of the stress campaign: the control plane under concurrent chaos against a real server, for a configurable
 * duration ({@code -Dmongoose.stress.seconds}, default 30). Admin threads add, stop and re-add processors across three
 * groups, register and remove services, start and stop audit capture, while two feeds publish continuously; processors
 * throw or are slow in start(), stop(), tearDown() and their handlers; a flood fills a group's control mailbox. Then:
 * every request answered exactly once and none hanging, no name left reserved, no leaked thread or capture resource,
 * events reaching processors and never after their stop, the service views consistent, and every group's exported
 * control audit passing {@code ControlInvariants}.
 * <p>
 * Each group's control audit must hold the whole run: size it with {@code -Dmongoose.control.auditCapacity} for long
 * runs (the test sets 400,000 unless told otherwise).
 */
@EnabledIfSystemProperty(named = "mongoose.stress", matches = "true")
class ControlPlaneChaosStressTest {

    @BeforeAll
    static void setUp() {
        StressSupport.quietLogging();
        if (System.getProperty(ComposingEventProcessorAgent.CONTROL_AUDIT_CAPACITY_PROPERTY) == null) {
            System.setProperty(ComposingEventProcessorAgent.CONTROL_AUDIT_CAPACITY_PROPERTY, "400000");
        }
    }

    @Test
    void chaosAgainstARealServer_keepsEveryControlPlaneGuarantee(@TempDir Path dir) throws Exception {
        long seconds = StressSupport.seconds("mongoose.stress.seconds", 30);
        ChaosWorkload.Settings settings = new ChaosWorkload.Settings();
        settings.captureDir = dir.resolve("capture");
        settings.seed = Long.getLong("mongoose.stress.seed", System.nanoTime());
        settings.floodIntervalMillis = Long.getLong("mongoose.stress.floodIntervalMillis", Math.min(60_000, seconds * 1000 / 3));
        System.out.println("chaos: seconds=" + seconds + " seed=" + settings.seed);

        int threadsBefore = StressSupport.threadCount();
        List<String> nonDaemonBefore = StressSupport.nonDaemonThreads();
        long t0 = System.nanoTime();
        ChaosWorkload chaos = new ChaosWorkload(settings);
        List<String> failures = new ArrayList<>();
        Map<String, String> audits = new LinkedHashMap<>();
        StopResult stop;
        try {
            chaos.startFeeds();
            chaos.startChaos();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            long nextReport = System.nanoTime() + TimeUnit.SECONDS.toNanos(Long.getLong("mongoose.stress.reportSeconds", 60));
            while (System.nanoTime() < end) {
                Thread.sleep(200);
                if (System.nanoTime() > nextReport) {
                    nextReport += TimeUnit.SECONDS.toNanos(Long.getLong("mongoose.stress.reportSeconds", 60));
                    System.out.printf("chaos t=%ds requests=%d instances=%d outstanding=[%s] threads=%d anomalies=%d%n",
                            TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - t0), chaos.asyncCount.get(),
                            chaos.instances.size(), chaos.outstandingByGroup(), StressSupport.threadCount(),
                            chaos.anomalies.size());
                }
            }
            java.util.Map<String, Long> byPrefix = new java.util.TreeMap<>();
            for (String n : StressSupport.liveThreads()) byPrefix.merge(n.replaceAll("[-0-9]+( \\(daemon\\))?$", ""), 1L, Long::sum);
            System.out.println("threads by name before quiescing: " + byPrefix);
            chaos.stopChaos(60);
            failures.addAll(chaos.checkServiceViews());
            chaos.stopEverything();
            chaos.awaitReplies(60);
            Thread.sleep(200);
            failures.addAll(chaos.checkRunning());
            chaos.stopFeeds();
        } finally {
            long s0 = System.nanoTime();
            stop = chaos.shutdown();
            System.out.println("shutdown took " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - s0) + " ms: " + stop);
        }
        failures.addAll(chaos.checkAfterShutdown());
        // judged after the shutdown, so each group's close (and the captures it closes) is on the record too
        failures.addAll(chaos.checkAudits(audits));
        if (!stop.complete()) failures.add("shutdown incomplete: " + stop);

        // no leaked threads: the server's agent and reply threads end, and no non-daemon thread outlives it
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        List<String> leaked;
        List<String> nonDaemonAfter;
        do {
            Thread.sleep(100);
            leaked = StressSupport.serverThreads(List.of("chaos-g", "chaos-feed-agent"));
            nonDaemonAfter = StressSupport.nonDaemonThreads();
            nonDaemonAfter.removeAll(nonDaemonBefore);
        } while ((!leaked.isEmpty() || !nonDaemonAfter.isEmpty()) && System.nanoTime() < deadline);
        if (!leaked.isEmpty()) failures.add("server threads still alive after shutdown: " + leaked);
        if (!nonDaemonAfter.isEmpty()) failures.add("non-daemon threads leaked: " + nonDaemonAfter);
        int threadsAfter = StressSupport.threadCount();

        System.out.println("\n===== chaos summary (" + seconds + " s, seed " + settings.seed + ") =====");
        System.out.print(chaos.summary());
        audits.forEach((g, s) -> System.out.println("  audit " + g + ": " + s));
        System.out.println("  threads before=" + threadsBefore + " after=" + threadsAfter + " (daemon helper pools may linger)");
        System.out.println("  max synchronous admin call: " + chaos.maxSyncMillis() + " ms");
        System.out.println("  failures: " + failures.size());
        failures.stream().limit(40).forEach(f -> System.out.println("  FAIL " + f));

        assertTrue(chaos.maxSyncMillis() < 45_000, "a synchronous admin call hung: " + chaos.maxSyncMillis() + " ms");
        assertEquals(List.of(), ChaosWorkload.limit(failures, 40), "chaos guarantees");
    }
}
