/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.stress;

import com.telamin.mongoose.StopResult;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.replay.EventCodec;
import com.telamin.mongoose.replay.GroupReplayer;
import com.telamin.mongoose.replay.InMemoryEventJournal;
import com.telamin.mongoose.replay.InMemoryReplayStore;
import com.telamin.mongoose.replay.JavaSerializationCodec;
import com.telamin.mongoose.replay.ReplayConfig;
import com.telamin.mongoose.replay.ReplayEntry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Item 3 of the stress campaign: the chaos workload recorded ({@code ReplayConfig.record}), then replayed
 * ({@code ReplayConfig.replay}). The covered processors are the three anchors, one per group, present from boot to
 * shutdown and receiving feed events and the audit-capture changes the chaos makes
 * on them, while all around them processors are added, stopped, fail and flood the same groups. Each anchor emits a
 * line per tick carrying its count and clock reading, so replay must reproduce
 * the inputs, their order across feeds and the clock, line for line. Service churn is explicitly disabled: this test
 * does not provide an isolated registry capable of reconstructing historical service incarnations. Unsupported
 * historical bindings must fail closed, as {@code ReplayServiceFollowupReviewTest} verifies.
 * <p>
 * Not covered, by design: the processors the chaos threads add at runtime. Replay identity is keyed by name and
 * per-name build ordinal (design "Deterministic replay", D3), so replaying one requires the replay run to add the same
 * names in the same order; this test's admin threads are random and concurrent, so it does not re-run them in the
 * replay. A replay run that added them differently would replay a different stream under each ordinal, or stop it
 * loudly ("no stream was recorded"), which ReplayIdentityTest already shows.
 * <p>
 * Duration: {@code -Dmongoose.stress.replay.seconds} (default 30).
 */
@EnabledIfSystemProperty(named = "mongoose.stress", matches = "true")
class ChaosReplayStressTest {
    private static String previousCapacity;

    @BeforeAll
    static void setUp() {
        StressSupport.quietLogging();
        previousCapacity = System.getProperty(ComposingEventProcessorAgent.CONTROL_AUDIT_CAPACITY_PROPERTY);
        if (previousCapacity == null) {
            System.setProperty(ComposingEventProcessorAgent.CONTROL_AUDIT_CAPACITY_PROPERTY, "400000");
        }
    }

    @AfterAll
    static void restoreCapacity() {
        if (previousCapacity == null) System.clearProperty(ComposingEventProcessorAgent.CONTROL_AUDIT_CAPACITY_PROPERTY);
        else System.setProperty(ComposingEventProcessorAgent.CONTROL_AUDIT_CAPACITY_PROPERTY, previousCapacity);
    }

    private static Set<String> anchors(int groups) {
        Set<String> names = new TreeSet<>();
        for (int g = 0; g < groups; g++) names.add(ChaosWorkload.anchorName(g));
        return names;
    }

    private static Map<String, EventCodec> codecs(int feeds) {
        Map<String, EventCodec> codecs = new HashMap<>();
        for (int f = 0; f < feeds; f++) codecs.put(ChaosWorkload.feedName(f), new JavaSerializationCodec());
        return codecs;
    }

    @Test
    void theChaosWorkloadRecorded_replaysEveryAnchorLineForLine(@TempDir Path dir) throws Exception {
        long seconds = StressSupport.seconds("mongoose.stress.replay.seconds", 30);
        ChaosWorkload.Settings settings = new ChaosWorkload.Settings();
        settings.captureDir = dir.resolve("capture");
        settings.services = 0;
        settings.seed = Long.getLong("mongoose.stress.seed", System.nanoTime());
        settings.floodIntervalMillis = Math.min(60_000, seconds * 1000 / 3);
        InMemoryReplayStore store = new InMemoryReplayStore();
        InMemoryEventJournal journal = new InMemoryEventJournal();
        Set<String> covered = anchors(settings.groups);
        settings.replay = ReplayConfig.record(covered, codecs(settings.feeds), journal, store);
        System.out.println("chaos replay: seconds=" + seconds + " seed=" + settings.seed);

        // ---------------------------------------------------------------- the recorded run
        List<String> failures = new ArrayList<>();
        Map<String, List<String>> recorded = new LinkedHashMap<>();
        ChaosWorkload chaos = new ChaosWorkload(settings);
        StopResult stop;
        try {
            chaos.startFeeds();
            chaos.startChaos();
            Thread.sleep(TimeUnit.SECONDS.toMillis(seconds));
            chaos.stopChaos(60);
            chaos.stopEverything();
            chaos.awaitReplies(60);
            chaos.awaitQuiescent(60);
            long closeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < closeDeadline && StressSupport.groups(chaos.server).values().stream()
                    .anyMatch(group -> group.captureResourcesHeld() != 0)) Thread.sleep(20);
            failures.addAll(chaos.checkRunning());
            chaos.stopFeeds();
            Thread.sleep(500);              // the anchors drain what the feeds published
        } finally {
            chaos.running.set(false);
            chaos.feeding.set(false);
            try {
                stop = chaos.shutdown();
            } finally {
                joinActors(chaos, failures);
            }
        }
        if (!stop.complete()) failures.add("recorded run: shutdown incomplete: " + stop);
        failures.addAll(chaos.checkAfterShutdown());
        failures.addAll(chaos.checkAudits(new LinkedHashMap<>()));
        List<String> sinkLines = chaos.sink.getMessages().stream().map(String::valueOf).toList();
        for (String anchor : covered) {
            recorded.put(anchor, sinkLines.stream().filter(l -> l.startsWith(anchor + " ")).toList());
        }
        System.out.print(chaos.summary());
        for (String anchor : covered) {
            List<ReplayEntry> entries = store.entries(anchor);
            long services = entries.stream().filter(e -> e instanceof ReplayEntry.ServiceChanged change
                    && change.service().startsWith(ChaosWorkload.SVC_PREFIX)).count();
            assertEquals(0, services, "this replay intentionally excludes historical service churn");
            System.out.println("  recorded " + anchor + ": lines=" + recorded.get(anchor).size() + " entries="
                    + entries.size() + " serviceChanges=" + services);
            assertTrue(recorded.get(anchor).size() > 100, "the anchor was under load: " + recorded.get(anchor).size());
        }
        assertEquals(List.of(), ChaosWorkload.limit(failures, 20), "the recorded run kept the chaos guarantees");

        // ---------------------------------------------------------------- the replay run
        ChaosWorkload.Settings replaySettings = new ChaosWorkload.Settings();
        replaySettings.captures = false;
        replaySettings.services = 0;
        replaySettings.replay = ReplayConfig.replay(covered, codecs(settings.feeds), journal, store);
        ChaosWorkload replay = new ChaosWorkload(replaySettings);
        Map<String, String> differences = new LinkedHashMap<>();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(60, seconds * 2));
            Map<String, GroupReplayer> replayers;
            while (true) {
                replayers = replay.server.replayers();
                boolean done = replayers.size() == settings.groups
                        && replayers.values().stream().allMatch(GroupReplayer::complete);
                if (done || System.nanoTime() > deadline) break;
                Thread.sleep(20);
            }
            Thread.sleep(200);
            for (int g = 0; g < settings.groups; g++) {
                String anchor = ChaosWorkload.anchorName(g);
                GroupReplayer replayer = replayers.get(ChaosWorkload.groupName(g));
                if (replayer == null) {
                    differences.put(anchor, "no replayer for its group");
                    continue;
                }
                List<String> replayed = replayer.outputs(anchor).stream().map(String::valueOf).toList();
                List<String> live = recorded.get(anchor);
                String stopped = replayer.stopped(anchor);
                System.out.println("  replayed " + anchor + ": lines=" + replayed.size() + " complete="
                        + replayer.complete() + " stopped=" + stopped);
                if (stopped != null) differences.put(anchor + " stopped", stopped);
                if (!replayer.complete()) differences.put(anchor + " complete", "the replay did not complete");
                if (!live.equals(replayed)) differences.put(anchor, firstDifference(live, replayed));
            }
        } finally {
            StopResult replayStop = replay.shutdown();
            if (!replayStop.complete()) differences.put("replay shutdown", replayStop.toString());
        }
        assertEquals(Map.of(), differences, "every anchor's replay equals its recording");
    }

    private static void joinActors(ChaosWorkload workload, List<String> failures) {
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            for (Thread actor : workload.threads) {
                long left = deadline - System.nanoTime();
                if (left > 0) {
                    try { TimeUnit.NANOSECONDS.timedJoin(actor, left); }
                    catch (InterruptedException e) { interrupted = true; }
                }
                if (actor.isAlive()) failures.add("recording actor remained alive: " + actor.getName());
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static String firstDifference(List<String> live, List<String> replayed) {
        int n = Math.min(live.size(), replayed.size());
        for (int i = 0; i < n; i++) {
            if (!live.get(i).equals(replayed.get(i))) {
                return "line " + i + ": recorded [" + live.get(i) + "] replayed [" + replayed.get(i) + "] (recorded "
                        + live.size() + " lines, replayed " + replayed.size() + ")";
            }
        }
        return "recorded " + live.size() + " lines, replayed " + replayed.size()
                + (live.size() > n ? ", first missing [" + live.get(n) + "]" : ", first extra [" + replayed.get(n) + "]");
    }
}
