/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.stress;

import com.telamin.mongoose.StopResult;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sustained real-server fault workload, separately gated from regression and volume tests.
 * Run with -Dmongoose.soak=true; duration defaults to 600 seconds. The audit stays enabled and
 * must remain complete: exhausting its explicitly sized ring is a failure, not permission to
 * accept an unverifiable trace. Heap/GC samples are observations, not a leak or latency proof:
 * retained audit history and on-disk capture sessions deliberately grow during this run.
 */
@EnabledIfSystemProperty(named = "mongoose.soak", matches = "true")
class ControlPlaneSoakTest {
    @Test
    @Timeout(value = 25, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void sustainedLifecycleFaultsAndCaptureChurn(@TempDir Path directory) throws Exception {
        StressSupport.quietLogging();
        String capacityProperty = ComposingEventProcessorAgent.CONTROL_AUDIT_CAPACITY_PROPERTY;
        String previousCapacity = System.getProperty(capacityProperty);
        if (previousCapacity == null) System.setProperty(capacityProperty, "400000");
        long seconds = Long.getLong("mongoose.soak.seconds", 600);
        assertTrue(seconds > 0 && seconds <= 900, "soak duration must be 1..900 seconds");
        ChaosWorkload.Settings settings = new ChaosWorkload.Settings();
        settings.soak = true;
        settings.seed = Long.getLong("mongoose.soak.seed", 20261025L);
        settings.adminPauseMillis = 200;
        settings.floodIntervalMillis = 60_000;
        settings.captureDir = directory.resolve("captures");
        List<String> failures = new ArrayList<>();
        List<String> nonDaemonBefore = StressSupport.nonDaemonThreads();
        LinkedHashMap<String, String> audits = new LinkedHashMap<>();
        long gcCountBefore = gcCount();
        long gcMillisBefore = gcMillis();
        long begin = System.nanoTime();
        long nextReport = begin;
        ChaosWorkload workload = null;
        try {
            workload = new ChaosWorkload(settings);
            try {
                workload.startFeeds();
                workload.startChaos();
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
                while (System.nanoTime() < end) {
                    Thread.sleep(250);
                    if (System.nanoTime() >= nextReport) {
                        nextReport = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                        workload.pruneReleased(failures);
                        long records = StressSupport.groups(workload.server).values().stream()
                                .mapToLong(g -> g.control().audit().total()).sum();
                        long resources = StressSupport.groups(workload.server).values().stream()
                                .mapToLong(ComposingEventProcessorAgent::captureResourcesHeld).sum();
                        System.out.printf("SOAK elapsed=%ds seed=%d requests=%d retainedInstances=%d pruned=%d "
                                        + "outstanding=%d resources=%d auditRecords=%d heapUsed=%d threads=%d "
                                        + "gcCount=%d gcMillis=%d anomalies=%d%n",
                                TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - begin), settings.seed,
                                workload.asyncCount.get(), workload.instances.size(), workload.pruned.get(),
                                workload.outstandingRequests(), resources, records,
                                ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),
                                StressSupport.threadCount(), gcCount() - gcCountBefore,
                                gcMillis() - gcMillisBefore, workload.anomalies.size());
                        assertTrue(failures.isEmpty(), () -> "retired instance violations: " + failures);
                        assertTrue(workload.anomalies.isEmpty(), "request/lifecycle anomaly during soak");
                        for (var group : StressSupport.groups(workload.server).values()) {
                            assertTrue(group.control().audit().isComplete(), "soak audit lost evidence");
                        }
                    }
                }
                workload.stopChaos(60);
                failures.addAll(workload.checkServiceViews());
                workload.stopEverything();
                workload.awaitReplies(60);
                workload.awaitQuiescent(60);
                // Physical close is asynchronous; shutdown below is the definitive completion check.
                long physicalDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (System.nanoTime() < physicalDeadline && StressSupport.groups(workload.server).values()
                        .stream().anyMatch(g -> g.captureResourcesHeld() != 0)) Thread.sleep(20);
                failures.addAll(workload.checkRunning());
            } finally {
                workload.running.set(false);
                workload.feeding.set(false);
                boolean interrupted = Thread.interrupted();
                try {
                    try {
                        workload.stopFeeds();
                    } finally {
                        try {
                            long shutdownStart = System.nanoTime();
                            StopResult result = workload.shutdown();
                            long shutdownMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - shutdownStart);
                            System.out.println("SOAK shutdownMillis=" + shutdownMillis + " result=" + result);
                            if (!result.complete()) failures.add("incomplete shutdown: " + result);
                            if (shutdownMillis > settings.shutdownTimeoutMillis + 2000)
                                failures.add("shutdown exceeded deadline allowance: " + shutdownMillis + "ms");
                        } finally {
                            joinActors(workload, failures);
                        }
                    }
                } finally {
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
            failures.addAll(workload.checkAfterShutdown());
            failures.addAll(workload.checkAudits(audits));
            long threadDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            List<String> leaked;
            do {
                Thread.sleep(100);
                leaked = StressSupport.nonDaemonThreads();
                leaked.removeAll(nonDaemonBefore);
                leaked.addAll(StressSupport.serverThreads(List.of("chaos-g", "chaos-feed-agent")));
            } while (!leaked.isEmpty() && System.nanoTime() < threadDeadline);
            if (!leaked.isEmpty()) failures.add("threads remaining after shutdown: " + leaked);
            System.out.println("SOAK workloadSeconds=" + seconds + " seed=" + settings.seed);
            System.out.print(workload.summary());
            audits.forEach((group, result) -> System.out.println("SOAK audit " + group + " " + result));
            System.out.println("SOAK failures=" + failures);
            assertEquals(List.of(), failures);
        } finally {
            if (previousCapacity == null) System.clearProperty(capacityProperty);
            else System.setProperty(capacityProperty, previousCapacity);
        }
    }

    /** A timeout must still shut down the server and account for the harness's own daemon threads. */
    private static void joinActors(ChaosWorkload workload, List<String> failures) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean interrupted = Thread.interrupted();
        try {
            for (Thread actor : workload.threads) {
                while (actor.isAlive() && System.nanoTime() < deadline) {
                    try {
                        actor.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                if (actor.isAlive()) failures.add("soak actor did not finish: " + actor.getName());
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(gc -> Math.max(0, gc.getCollectionCount())).sum();
    }

    private static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(gc -> Math.max(0, gc.getCollectionTime())).sum();
    }
}
