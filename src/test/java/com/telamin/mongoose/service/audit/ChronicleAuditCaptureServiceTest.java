/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.audit;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.audit.LogRecord;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.fluxtion.runtime.time.Clock;
import com.telamin.mongoose.config.AuditCaptureConfig;
import com.telamin.mongoose.internal.AgronaCountersService;
import com.telamin.mongoose.internal.ChronicleAuditCaptureService;
import com.telamin.mongoose.internal.DirAuditIntrospectionService;
import com.telamin.mongoose.service.counters.MongooseCountersService;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.wire.DocumentContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1 contract: Chronicle-backed capture works end-to-end inside
 * the capture service. Tests the full attach → start → records-flow →
 * stop → read-back cycle without needing a Fluxtion compile or a full
 * MongooseServer boot.
 *
 * <p>The DataFlow is a JDK proxy that captures the listener handed to
 * {@code setAuditLogProcessor}. We then drive {@code processLogRecord}
 * directly with hand-built {@link LogRecord} instances — the same SPI
 * Fluxtion would invoke per event — and assert the Chronicle queue
 * contains the records on disk after {@code stop}.
 */
class ChronicleAuditCaptureServiceTest {

    @TempDir
    Path tmp;

    private AuditCaptureConfig config;
    private MongooseCountersService counters;

    @BeforeEach
    void setup() {
        config = new AuditCaptureConfig();
        config.setEnabled(true);
        config.setDirectory(tmp.toString());
        counters = new AgronaCountersService(64);
    }

    @AfterEach
    void teardown() {
        // AgronaCountersService holds an UnsafeBuffer; nothing to release
        // explicitly in tests beyond letting it fall out of scope.
    }

    @Test
    void capture_writes_log_records_to_chronicle_queue_with_pre_built_yaml_payload() {
        ChronicleAuditCaptureService svc = new ChronicleAuditCaptureService(config, counters);
        AtomicReference<LogRecordListener> installedListener = new AtomicReference<>();
        DataFlow proxy = proxyDataFlow(installedListener);

        svc.attach(proxy, "pnl-processor");
        assertFalse(svc.isRecording("pnl-processor"), "attach alone does not start recording");

        svc.start("pnl-processor");
        assertTrue(svc.isRecording("pnl-processor"));
        assertNotNull(installedListener.get(), "start() must install a listener via setAuditLogProcessor");

        // Fire 3 hand-built records through the listener. Each one carries
        // YAML text we can verify on the way back out.
        LogRecordListener listener = installedListener.get();
        listener.processLogRecord(record("eventTime: 100\nevent: Trade\n"));
        listener.processLogRecord(record("eventTime: 101\nevent: MidPrice\n"));
        listener.processLogRecord(record("eventTime: 102\nevent: Trade\n"));

        // Per-processor counter must have ticked exactly 3 times.
        Map<String, Long> snap = new HashMap<>();
        counters.forEachCounter((id, label, value) -> snap.put(label, value));
        assertEquals(3L, snap.get("audit.pnl-processor.records"),
                "per-processor records counter must reflect every successful append");

        // Sink handle live snapshot
        Map<String, AuditSinkHandle> live = svc.liveSinks();
        AuditSinkHandle handle = live.get("pnl-processor");
        assertNotNull(handle);
        assertTrue(handle.isLive());
        assertEquals(3L, handle.recordCount());
        assertEquals("pnl-processor", handle.processorName());

        // Stop and walk the queue. Tailer should yield 3 records, in
        // order, each with the YAML payload we wrote.
        svc.stop("pnl-processor");
        assertFalse(svc.isRecording("pnl-processor"));

        Path procDir = tmp.resolve("pnl-processor");
        List<String> readBack = readAll(procDir);
        assertEquals(3, readBack.size(), "every record persisted to disk");
        assertTrue(readBack.get(0).contains("event: Trade"));
        assertTrue(readBack.get(1).contains("event: MidPrice"));
        assertTrue(readBack.get(2).contains("event: Trade"));
        assertTrue(readBack.get(0).contains("eventTime: 100"));
    }

    @Test
    void start_is_idempotent_does_not_roll_the_sink() {
        ChronicleAuditCaptureService svc = new ChronicleAuditCaptureService(config, counters);
        AtomicReference<LogRecordListener> installedListener = new AtomicReference<>();
        DataFlow proxy = proxyDataFlow(installedListener);

        svc.attach(proxy, "pnl-processor");
        svc.start("pnl-processor");
        LogRecordListener first = installedListener.get();

        svc.start("pnl-processor");  // second start should be a no-op
        LogRecordListener second = installedListener.get();
        assertSame(first, second,
                "a second start() must not replace the listener or roll the sink");
    }

    @Test
    void stop_is_idempotent_when_not_recording() {
        ChronicleAuditCaptureService svc = new ChronicleAuditCaptureService(config, counters);
        svc.attach(proxyDataFlow(new AtomicReference<>()), "pnl-processor");
        // not started — stop should not throw
        svc.stop("pnl-processor");
        assertFalse(svc.isRecording("pnl-processor"));
    }

    @Test
    void start_without_attach_throws() {
        ChronicleAuditCaptureService svc = new ChronicleAuditCaptureService(config, counters);
        try {
            svc.start("nope");
            org.junit.jupiter.api.Assertions.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // good
        }
    }

    @Test
    void introspection_service_lists_closed_dirs_with_live_sinks_first() {
        ChronicleAuditCaptureService svc = new ChronicleAuditCaptureService(config, counters);
        DirAuditIntrospectionService introspection = new DirAuditIntrospectionService(config, svc);

        AtomicReference<LogRecordListener> listener = new AtomicReference<>();
        svc.attach(proxyDataFlow(listener), "pnl-processor");
        svc.start("pnl-processor");
        listener.get().processLogRecord(record("event: Trade\n"));

        // Also seed a historical-looking dir for a second processor.
        Path historic = tmp.resolve("datagen-processor");
        try {
            java.nio.file.Files.createDirectories(historic);
            java.nio.file.Files.writeString(historic.resolve("placeholder"), "x");
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }

        introspection.invalidate();
        List<AuditSinkHandle> all = introspection.listAvailable();
        assertEquals(2, all.size(), "lists both live + historical processor dirs");
        assertTrue(all.get(0).isLive(), "live sink sorts first");
        assertEquals("pnl-processor", all.get(0).processorName());
        assertFalse(all.get(1).isLive(), "historical dir sorts after live");
        assertEquals("datagen-processor", all.get(1).processorName());

        // currentSink direct lookups
        assertNotNull(introspection.currentSink("pnl-processor"));
        org.junit.jupiter.api.Assertions.assertNull(introspection.currentSink("datagen-processor"));
        org.junit.jupiter.api.Assertions.assertNull(introspection.currentSink("does-not-exist"));

        svc.stop("pnl-processor");
    }

    // ───── Helpers ──────────────────────────────────────────────────

    /** Builds a LogRecord whose asCharSequence() returns the given YAML text. */
    private static LogRecord record(String yaml) {
        Clock c = new Clock();
        c.init();
        LogRecord r = new LogRecord(c);
        r.replaceBuffer(yaml);
        return r;
    }

    /**
     * JDK proxy DataFlow that captures the listener passed to
     * setAuditLogProcessor. Every other method is a no-op default-
     * value response.
     */
    private static DataFlow proxyDataFlow(AtomicReference<LogRecordListener> sink) {
        InvocationHandler h = (proxy, method, args) -> {
            if ("setAuditLogProcessor".equals(method.getName()) && args != null && args.length == 1) {
                sink.set((LogRecordListener) args[0]);
                return null;
            }
            Class<?> rt = method.getReturnType();
            if (!rt.isPrimitive()) return null;
            if (rt == boolean.class) return false;
            return 0;
        };
        return (DataFlow) Proxy.newProxyInstance(
                DataFlow.class.getClassLoader(),
                new Class<?>[]{DataFlow.class},
                h);
    }

    /** Walks a Chronicle queue directory and returns every excerpt's text payload. */
    private static List<String> readAll(Path queueDir) {
        try (ChronicleQueue q = SingleChronicleQueueBuilder.binary(queueDir).build()) {
            ExcerptTailer tailer = q.createTailer();
            List<String> out = new java.util.ArrayList<>();
            for (; ; ) {
                try (DocumentContext dc = tailer.readingDocument()) {
                    if (!dc.isPresent()) break;
                    out.add(dc.wire().getValueIn().text());
                }
            }
            return out;
        }
    }
}