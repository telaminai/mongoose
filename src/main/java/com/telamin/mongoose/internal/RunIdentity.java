/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.mongoose.service.audit.AuditCaptureIdentity;

import java.io.InputStream;
import java.net.InetAddress;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Which run of which server wrote an audit stream: the host part of the evidence-set key.
 *
 * <p>A processor's generation counts replacements inside one process and restarts at 1 with the process, so a
 * static deployment restarted twice writes {@code group/name#1} three times. {@link #runId()} is created once per
 * server instance, at construction, and is what separates those runs. {@link #serverId()} is the configured server
 * name, else the host name. Both go into every audit stream header through {@link #headerContext}, and into the
 * capture sidecar.
 */
public final class RunIdentity {

    private static final String MONGOOSE_VERSION = readVersion();

    private final String serverId;
    private final String runId;
    private final Instant startedAt;

    private RunIdentity(String serverId, String runId, Instant startedAt) {
        this.serverId = serverId;
        this.runId = runId;
        this.startedAt = startedAt;
    }

    /** A new run: a fresh random run id, started now. A blank {@code configuredServerId} falls back to the host name. */
    public static RunIdentity create(String configuredServerId) {
        String serverId = configuredServerId == null || configuredServerId.isBlank()
                ? hostName()
                : configuredServerId.trim();
        return new RunIdentity(serverId, UUID.randomUUID().toString(), Instant.now());
    }

    public String serverId() {
        return serverId;
    }

    public String runId() {
        return runId;
    }

    public Instant startedAt() {
        return startedAt;
    }

    /** The Mongoose version on the classpath, or null when its build stamp cannot be read. */
    public static String mongooseVersion() {
        return MONGOOSE_VERSION;
    }

    /**
     * The audit stream header's {@code host} map for one processor of this run, in a fixed order:
     * {@code serverId, runId, group, name, generation, mongooseVersion}. The run key is
     * {@code serverId + runId + group/name#generation}.
     */
    public Map<String, String> headerContext(AuditCaptureIdentity processor) {
        Map<String, String> host = new LinkedHashMap<>();
        host.put("serverId", serverId);
        host.put("runId", runId);
        host.put("group", processor.group());
        host.put("name", processor.name());
        host.put("generation", Long.toString(processor.generation()));
        if (MONGOOSE_VERSION != null) {
            host.put("mongooseVersion", MONGOOSE_VERSION);
        }
        return host;
    }

    /** Resolved once per JVM, and only when no server id is configured: a host-name lookup can be slow. */
    /**
     * The header context for a group's control processor: the run, the group, the name {@code control} and generation
     * {@code 0}, which no user processor has (user generations start at 1), so its stream is never taken for one.
     */
    public Map<String, String> controlContext(String group) {
        Map<String, String> host = new LinkedHashMap<>();
        host.put("serverId", serverId);
        host.put("runId", runId);
        host.put("group", group);
        host.put("name", "control");
        host.put("generation", "0");
        if (MONGOOSE_VERSION != null) {
            host.put("mongooseVersion", MONGOOSE_VERSION);
        }
        return host;
    }

    private static String hostName() {
        return HostName.VALUE;
    }

    private static final class HostName {
        static final String VALUE = resolve();

        private static String resolve() {
            String env = System.getenv("HOSTNAME");
            if (env != null && !env.isBlank()) {
                return env.trim();
            }
            try {
                String name = InetAddress.getLocalHost().getHostName();
                return name == null || name.isBlank() ? "unknown-host" : name;
            } catch (Exception unresolvable) {
                return "unknown-host";
            }
        }
    }

    private static String readVersion() {
        try (InputStream in = RunIdentity.class.getResourceAsStream("/com/telamin/mongoose/build.properties")) {
            if (in == null) {
                return null;
            }
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty("version");
            return v == null || v.isBlank() || v.startsWith("${") ? null : v.trim();
        } catch (Exception unreadable) {
            return null;
        }
    }

    @Override
    public String toString() {
        return serverId + " · " + runId;
    }
}
