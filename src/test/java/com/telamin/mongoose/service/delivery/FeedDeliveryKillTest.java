/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.delivery;

import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A real crash: the server runs in a separate JVM ({@link KillChildServer}) and is killed with SIGKILL
 * ({@code Process.destroyForcibly}) 0.1 s into a burst appended in one write, while its stateful processor is part way
 * through it. The read pointer is memory-mapped, so its page survives the kill exactly as written. Then:
 * <ul>
 *     <li>the pointer is never ahead of the processor's durable state (the position saved with it): no line is skipped;</li>
 *     <li>a restart re-delivers from the pointer: every line reaches the output; without de-duplication the lines
 *     processed after the last commit are applied to the state twice (the hazard for a stateful app);</li>
 *     <li>with the consumer-side offset (skip a delivery whose position is at or before the saved one), every line is
 *     applied to the state exactly once.</li>
 * </ul>
 */
@DisabledOnOs(OS.WINDOWS)
class FeedDeliveryKillTest {

    @TempDir
    Path dir;

    private static String line(int i) {
        return "line-" + i;
    }

    private void append(Path data, int from, int to) throws Exception {
        StringBuilder b = new StringBuilder();
        for (int i = from; i <= to; i++) b.append(line(i)).append('\n');
        Files.writeString(data, b, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private Process start(Path data, Path state, Path out, boolean dedupe, int parkMicros) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (a.startsWith("--add-opens")) cmd.add(a);
        }
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(KillChildServer.class.getName());
        cmd.add(data.toString());
        cmd.add(state.toString());
        cmd.add(out.toString());
        cmd.add(String.valueOf(dedupe));
        cmd.add(String.valueOf(parkMicros));
        File log = dir.resolve("child-" + System.nanoTime() + ".log").toFile();
        return new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log).start();
    }

    private static long[] state(Path state) {
        try {
            if (!Files.exists(state)) return new long[]{0, -1};
            String[] s = Files.readString(state).trim().split(" ");
            return new long[]{Long.parseLong(s[0]), Long.parseLong(s[1])};
        } catch (Exception e) {
            return new long[]{0, -1};
        }
    }

    private static long pointer(Path data) throws Exception {
        return ByteBuffer.wrap(Files.readAllBytes(Path.of(data + ".readPointer")), 0, 8).getLong();
    }

    private static void await(BooleanSupplier c, String what, Process child) throws Exception {
        long end = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < end && !c.getAsBoolean()) {
            if (!child.isAlive()) fail("the server exited while waiting for " + what);
            Thread.sleep(5);
        }
        assertTrue(c.getAsBoolean(), "timed out waiting for " + what);
    }

    private static void kill9(Process p) throws Exception {
        p.destroyForcibly();   // SIGKILL on Unix: no shutdown hook, no stop(), no flush
        p.waitFor();
    }

    @ParameterizedTest(name = "dedupe={0}")
    @CsvSource({"false", "true"})
    void killMinus9MidBurstSkipsNoLineAndTheConsumerSideOffsetAppliesEachLineOnce(boolean dedupe) throws Exception {
        int before = 2000, total = 5000;
        Path data = dir.resolve("lines.txt"), state = dir.resolve("state.txt"), out = dir.resolve("out.txt");
        append(data, 1, before);
        long fileEnd = Files.size(data);
        Process first = start(data, state, out, dedupe, 50);
        await(() -> state(state)[1] == fileEnd, "the first " + before + " lines applied", first);

        append(data, before + 1, total);           // one burst, one write
        Thread.sleep(100);
        kill9(first);

        long pointerAtKill = pointer(data);
        long[] stateAtKill = state(state);
        assertTrue(stateAtKill[0] < total, "killed part way through the burst: " + stateAtKill[0]);
        assertTrue(pointerAtKill <= stateAtKill[1], "the committed pointer (" + pointerAtKill + ") is never ahead of the "
                + "position of the durable state (" + stateAtKill[1] + "): no line is skipped");

        long end = Files.size(data);
        Process second = start(data, state, out, dedupe, 0);
        try {
            await(() -> state(state)[1] == end, "every line applied after the restart", second);
            await(() -> {
                try {
                    return pointer(data) == end;
                } catch (Exception e) {
                    return false;
                }
            }, "everything committed", second);
        } finally {
            kill9(second);
        }

        List<String> output = Files.readAllLines(out);
        Map<String, Integer> seen = new HashMap<>();
        for (String l : output) seen.merge(l, 1, Integer::sum);
        for (int i = 1; i <= total; i++) assertTrue(seen.containsKey(line(i)), line(i) + " was lost");
        int duplicates = output.size() - total;
        long applied = state(state)[0];
        System.out.println("kill -9 test dedupe=" + dedupe + ": state at kill " + stateAtKill[0] + " lines (position "
                + stateAtKill[1] + "), pointer " + pointerAtKill + " (" + (stateAtKill[1] - pointerAtKill)
                + " bytes behind the state), duplicate outputs " + duplicates + ", lines applied to state " + applied);
        if (dedupe) {
            assertEquals(total, applied, "the consumer-side offset applies every line to the state exactly once");
            assertTrue(duplicates <= 1, "at most the line whose output was written before its state at the kill");
        } else {
            // every re-delivered line is applied again; one duplicate output more if the kill fell between a line's
            // output and its state save (that line was not yet in the state, so applying it once is correct)
            assertTrue(applied >= total && applied <= total + duplicates && total + duplicates - applied <= 1,
                    "without de-duplication a re-delivered line is applied again: applied " + applied + ", duplicates "
                            + duplicates);
        }
    }
}
