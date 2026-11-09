/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.mongoose.MongooseServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mongoose makes no Chronicle analytics calls.
 * <p>
 * Chronicle Queue reports a "started" event to Google Analytics on every queue open, through the
 * client chronicle-core builds once per JVM. The client is a silent {@code MuteAnalytics} when
 * {@code chronicle.analytics.disable} is true or the chronicle-analytics jar is absent. Mongoose's
 * classpath leaves the jar out (pom exclusion, guarded by the no-chronicle-analytics enforcer rule
 * and by {@link #theAnalyticsJarIsNotOnMongoosesClasspath()}). The property cases add the jar back
 * (copied to target/chronicle-analytics by the build) to prove the property path on its own.
 * <p>
 * Each case runs in a fresh JVM, so the client Chronicle builds is decided by that case alone. The
 * client class is the Chronicle-side indicator: core's {@code MuteAnalytics} has no send path.
 * No case reaches the network: the jar's own builder also mutes itself when JUnit is on the
 * classpath, and the child's home directory is a temporary one.
 */
class ChronicleAnalyticsTest {

    private static final String MUTE = "net.openhft.chronicle.core.internal.analytics.MuteAnalytics";

    @TempDir
    Path dir;

    @Test
    void theAnalyticsJarIsNotOnMongoosesClasspath() throws Exception {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("net.openhft.chronicle.analytics.Analytics"));
        Map<String, String> r = child(false, "none");
        assertEquals("false", r.get("analyticsJarPresent"), r.toString());
        assertEquals("false", r.get("enabled"), r.toString());
        assertEquals(MUTE, r.get("client"), r.toString());
    }

    @Test
    void mongooseServerTurnsChronicleAnalyticsOff() throws Exception {
        Map<String, String> r = child(true, MongooseServer.class.getName());
        assertEquals("null", r.get("before"), r.toString());
        assertEquals("true", r.get("after"), r.toString());
        assertEquals("true", r.get("analyticsJarPresent"), "the property must work with the jar present: " + r);
        assertEquals("false", r.get("enabled"), r.toString());
        assertEquals(MUTE, r.get("client"), r.toString());
    }

    @Test
    void captureServiceAloneTurnsChronicleAnalyticsOff() throws Exception {
        Map<String, String> r = child(true, ChronicleAuditCaptureService.class.getName());
        assertEquals("true", r.get("after"), r.toString());
        assertEquals("false", r.get("enabled"), r.toString());
        assertEquals(MUTE, r.get("client"), r.toString());
    }

    @Test
    void controlWithoutMongooseChronicleBuildsALiveClient() throws Exception {
        Map<String, String> r = child(true, "none");
        assertEquals("null", r.get("after"), r.toString());
        assertEquals("true", r.get("enabled"), "the indicator must be live: " + r);
        assertNotEquals(MUTE, r.get("client"), r.toString());
    }

    @Test
    void anExplicitSettingWins() throws Exception {
        Map<String, String> r = child(true, MongooseServer.class.getName(), "-D" + ChronicleAnalytics.DISABLE_PROPERTY + "=false");
        assertEquals("false", r.get("after"), r.toString());
        assertEquals("true", r.get("enabled"), r.toString());
        assertNotEquals(MUTE, r.get("client"), r.toString());
    }

    @Test
    void setsOnlyWhenUnset() {
        Properties unset = new Properties();
        assertTrue(ChronicleAnalytics.disableUnlessConfigured(unset));
        assertEquals("true", unset.getProperty(ChronicleAnalytics.DISABLE_PROPERTY));

        Properties explicit = new Properties();
        explicit.setProperty(ChronicleAnalytics.DISABLE_PROPERTY, "false");
        assertFalse(ChronicleAnalytics.disableUnlessConfigured(explicit));
        assertEquals("false", explicit.getProperty(ChronicleAnalytics.DISABLE_PROPERTY));
    }

    private Map<String, String> child(boolean withAnalyticsJar, String entry, String... jvmArgs) throws Exception {
        String classPath = System.getProperty("java.class.path");
        if (withAnalyticsJar) {
            Path jar = Path.of(System.getProperty("basedir", "."), "target", "chronicle-analytics", "chronicle-analytics.jar");
            assertTrue(Files.isRegularFile(jar), "the build copies the jar here (maven-dependency-plugin): " + jar);
            classPath = classPath + File.pathSeparator + jar;
        }
        Path home = dir.resolve("home-" + System.nanoTime());
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (a.startsWith("--add-opens") || a.startsWith("--add-exports")) cmd.add(a);
        }
        cmd.add("-Duser.home=" + home);
        cmd.addAll(List.of(jvmArgs));
        cmd.add("-cp");
        cmd.add(classPath);
        cmd.add(ChronicleAnalyticsChild.class.getName());
        cmd.add(entry);
        cmd.add(dir.resolve("queue-" + System.nanoTime()).toString());
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "child did not exit");
        assertEquals(0, p.exitValue(), out);
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : out.split("\\R")) {
            int eq = line.indexOf('=');
            if (eq > 0 && line.substring(0, eq).matches("[A-Za-z]+")) result.put(line.substring(0, eq), line.substring(eq + 1));
        }
        assertTrue(result.containsKey("client"), out);
        return result;
    }
}
