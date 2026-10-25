/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.stress;

import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.internal.ComposingEventProcessorAgentRunner;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Shared helpers for the stress tests: quiet logs, the server's groups, thread snapshots. */
final class StressSupport {

    private StressSupport() {
    }

    /** INFO logging from every lifecycle step would dominate a stress run; keep warnings and above. */
    static void quietLogging() {
        Logger root = Logger.getLogger("");
        root.setLevel(Level.SEVERE);
        Arrays.stream(root.getHandlers()).forEach(h -> h.setLevel(Level.SEVERE));
        Logger.getLogger("com.telamin").setLevel(Level.SEVERE);
    }

    static long seconds(String property, long defaultSeconds) {
        return Long.getLong(property, defaultSeconds);
    }

    /** The server's processor groups, by name (read reflectively: tests need each group's control driver and audit). */
    @SuppressWarnings("unchecked")
    static Map<String, ComposingEventProcessorAgent> groups(MongooseServer server) {
        try {
            var field = MongooseServer.class.getDeclaredField("composingEventProcessorAgents");
            field.setAccessible(true);
            Map<String, ComposingEventProcessorAgentRunner> runners = (Map<String, ComposingEventProcessorAgentRunner>) field.get(server);
            Map<String, ComposingEventProcessorAgent> out = new TreeMap<>();
            runners.forEach((k, v) -> out.put(k, v.group()));
            return out;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Names of live threads, sorted. */
    static List<String> liveThreads() {
        List<String> out = new ArrayList<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive()) out.add(t.getName() + (t.isDaemon() ? " (daemon)" : ""));
        }
        out.sort(String::compareTo);
        return out;
    }

    /** Live non-daemon threads, by name, excluding the JVM's and the test runner's own. */
    static List<String> nonDaemonThreads() {
        List<String> out = new ArrayList<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && !t.isDaemon() && t != Thread.currentThread() && !t.getName().equals("main")
                    && !t.getName().startsWith("surefire") && !t.getName().equals("DestroyJavaVM")) {
                out.add(t.getName());
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    /** Live threads whose name says they belong to Mongoose or one of its agents (daemon or not). */
    static List<String> serverThreads(List<String> agentNamePrefixes) {
        List<String> out = new ArrayList<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (!t.isAlive()) continue;
            String n = t.getName();
            boolean ours = n.startsWith("mongoose-control-replies") || n.startsWith("mongoose-server-stop");
            for (String p : agentNamePrefixes) ours |= n.startsWith(p);
            if (ours) out.add(n);
        }
        out.sort(String::compareTo);
        return out;
    }

    static int threadCount() {
        return ManagementFactory.getThreadMXBean().getThreadCount();
    }

    /** Heap used after a few full GCs, in bytes. */
    static long heapAfterGc() {
        for (int i = 0; i < 3; i++) {
            System.gc();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    static String dumpThreads() {
        StringBuilder b = new StringBuilder();
        Thread.getAllStackTraces().forEach((t, st) -> {
            b.append('"').append(t.getName()).append('"').append(t.isDaemon() ? " daemon" : "").append(' ')
                    .append(t.getState()).append('\n');
            for (int i = 0; i < Math.min(st.length, 25); i++) b.append("    at ").append(st[i]).append('\n');
        });
        return b.toString();
    }
}
