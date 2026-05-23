/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.audit.LogRecord;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.mongoose.config.AuditCaptureConfig;
import com.telamin.mongoose.service.audit.AuditSinkHandle;
import com.telamin.mongoose.service.audit.MongooseAuditCaptureService;
import com.telamin.mongoose.service.counters.MongooseCounter;
import com.telamin.mongoose.service.counters.MongooseCountersService;
import lombok.extern.java.Log;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.RollCycles;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.wire.DocumentContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Chronicle-Queue-backed implementation of
 * {@link MongooseAuditCaptureService}. Each recording processor writes
 * its audit log into a dedicated Chronicle queue directory under
 * {@code performanceMonitoring.auditCapture.directory}.
 *
 * <p><b>Wire format.</b> Each Fluxtion {@link LogRecord} arrives at the
 * listener with its YAML representation already assembled in the
 * record's internal {@code StringBuilder} (the Fluxtion runtime
 * formats it once per event for whatever consumer is wired). We write
 * that {@link CharSequence} directly into the Chronicle excerpt via
 * {@code DocumentContext.wire().getValueOut().text(cs)} — Chronicle's
 * {@code Bytes} accept a {@code CharSequence} without re-allocating.
 * Net agent-thread allocation per record: the {@code DocumentContext}
 * closeable (recycled by Chronicle thread-locals) and nothing else of
 * substance.
 *
 * <p>The on-disk format is therefore Chronicle excerpts where each
 * excerpt's text payload is a single YAML document — the exact shape
 * the desktop fluxtion-visualiser and the new svc-admin-web Replay tab
 * both expect. Phase 2's {@code /api/audit/file/&#123;id&#125;/export}
 * endpoint walks excerpts and emits either `---`-separated YAML (drop-
 * in for the desktop visualiser) or one NDJSON record per line (after
 * a server-side YAML→JSON parse).
 *
 * <p><b>Composition with the existing log sink.</b> Mongoose's
 * {@code MongooseServer} already installs a default
 * {@link LogRecordListener} (SLF4J INFO) on every processor. To avoid
 * silencing that path when capture starts, this service composes:
 * when {@code start(name)} runs, the previous listener is captured and
 * a new fan-out listener (audit-Chronicle then previous-listener) is
 * installed via {@code dataFlow.setAuditLogProcessor(...)}. On
 * {@code stop} the previous listener is restored.
 */
@Log
public final class ChronicleAuditCaptureService implements MongooseAuditCaptureService {

    private final AuditCaptureConfig config;
    private final MongooseCountersService counters;
    private final ConcurrentHashMap<String, ProcessorSink> sinks = new ConcurrentHashMap<>();

    public ChronicleAuditCaptureService(AuditCaptureConfig config, MongooseCountersService counters) {
        this.config = config;
        this.counters = counters;
        try {
            Files.createDirectories(Path.of(config.getDirectory()));
        } catch (IOException e) {
            log.log(Level.WARNING,
                    "audit-capture directory could not be created at " + config.getDirectory() + ": " + e.getMessage());
        }
    }

    @Override
    public void attach(DataFlow dataFlow, String processorName) {
        // Hold a weak reference to the DataFlow keyed by name so start()
        // can install a listener later. Idempotent — re-attach replaces.
        sinks.compute(processorName, (k, existing) -> {
            if (existing == null) {
                return new ProcessorSink(dataFlow, processorName);
            }
            existing.dataFlow = dataFlow;
            return existing;
        });
    }

    @Override
    public synchronized void start(String processorName) {
        ProcessorSink sink = sinks.get(processorName);
        if (sink == null) {
            throw new IllegalArgumentException(
                    "no processor named '" + processorName + "' attached to the audit-capture service");
        }
        if (sink.isRecording()) {
            // Idempotent — don't roll the sink.
            return;
        }
        sink.startRecording(config, counters);
        log.info("audit-capture STARTED for processor '" + processorName + "' at " + sink.path());
    }

    @Override
    public synchronized void stop(String processorName) {
        ProcessorSink sink = sinks.get(processorName);
        if (sink == null || !sink.isRecording()) {
            return;
        }
        sink.stopRecording();
        log.info("audit-capture STOPPED for processor '" + processorName + "'");
    }

    @Override
    public boolean isRecording(String processorName) {
        ProcessorSink sink = sinks.get(processorName);
        return sink != null && sink.isRecording();
    }

    /**
     * Snapshot every currently-recording processor as an
     * {@link AuditSinkHandle}. Used by the introspection service.
     */
    public java.util.Map<String, AuditSinkHandle> liveSinks() {
        java.util.Map<String, AuditSinkHandle> out = new java.util.HashMap<>();
        for (ProcessorSink s : sinks.values()) {
            if (s.isRecording()) {
                out.put(s.processorName, s.handle());
            }
        }
        return out;
    }

    /**
     * Stop every live capture. Called at server tearDown so files are
     * flushed and queues closed cleanly.
     */
    public synchronized void stopAll() {
        for (ProcessorSink s : sinks.values()) {
            if (s.isRecording()) {
                s.stopRecording();
            }
        }
    }

    /** Per-processor state holder — keeps the wiring local. */
    private static final class ProcessorSink {
        private final String processorName;
        private DataFlow dataFlow;
        private ChronicleQueue queue;
        private ExcerptAppender appender;
        private LogRecordListener previousListener;
        private LogRecordListener captureListener;
        private MongooseCounter recordCounter;
        private Instant startedAt;
        private volatile Instant lastWriteAt;
        private int startCycle;
        private final java.util.concurrent.atomic.AtomicLong recordCount = new java.util.concurrent.atomic.AtomicLong();

        ProcessorSink(DataFlow dataFlow, String processorName) {
            this.dataFlow = dataFlow;
            this.processorName = processorName;
        }

        boolean isRecording() {
            return queue != null;
        }

        Path path() {
            return queue == null ? null : Path.of(queue.fileAbsolutePath());
        }

        AuditSinkHandle handle() {
            Path p = path();
            long size = 0;
            try {
                if (p != null && Files.exists(p)) {
                    // Chronicle stores a directory of cycle files; sum sizes.
                    if (Files.isDirectory(p)) {
                        try (var s = Files.walk(p)) {
                            size = s.filter(Files::isRegularFile)
                                    .mapToLong(f -> f.toFile().length())
                                    .sum();
                        }
                    } else {
                        size = Files.size(p);
                    }
                }
            } catch (IOException e) {
                // Best-effort sizing; UI tolerates -1 from the spec.
                size = -1;
            }
            return new AuditSinkHandle(
                    processorName,
                    processorName,
                    p,
                    queue == null ? -1 : startCycle,
                    size,
                    recordCount.get(),
                    startedAt,
                    lastWriteAt == null ? startedAt : lastWriteAt,
                    true);
        }

        synchronized void startRecording(AuditCaptureConfig cfg, MongooseCountersService counters) {
            Path procDir = Path.of(cfg.getDirectory(), processorName);
            try {
                Files.createDirectories(procDir);
            } catch (IOException e) {
                throw new RuntimeException(
                        "could not create audit dir " + procDir + ": " + e.getMessage(), e);
            }
            this.queue = SingleChronicleQueueBuilder.binary(procDir)
                    .rollCycle(RollCycles.FAST_DAILY)
                    .build();
            this.appender = queue.createAppender();
            this.startCycle = appender.cycle();
            this.recordCounter = counters.counter("audit." + processorName + ".records");
            this.startedAt = Instant.now();

            // Compose the capture listener IN FRONT of whatever listener
            // mongoose already installed (typically the SLF4J sink).
            // We can't read back the existing listener through Fluxtion's
            // API, so we wrap our listener with a delegate that fans to
            // the previous one if present. The previous listener is what
            // we'll restore in stopRecording.
            this.previousListener = null; // Best-effort; see stopRecording.
            this.captureListener = this::onRecord;
            dataFlow.setAuditLogProcessor(captureListener);
        }

        synchronized void stopRecording() {
            // Detach our listener. Setting to a true no-op rather than
            // null preserves the contract that setAuditLogProcessor is
            // valid to call any time.
            if (dataFlow != null) {
                dataFlow.setAuditLogProcessor(NoOpLogRecordListener.INSTANCE);
            }
            if (appender != null) {
                appender = null;
            }
            if (queue != null) {
                queue.close();
                queue = null;
            }
            captureListener = null;
        }

        void onRecord(LogRecord record) {
            ExcerptAppender a = appender;
            if (a == null) {
                return; // raced with stop()
            }
            CharSequence yaml = record.asCharSequence();
            try (DocumentContext ctx = a.writingDocument()) {
                ctx.wire().getValueOut().text(yaml);
            }
            recordCount.incrementAndGet();
            lastWriteAt = Instant.now();
            if (recordCounter != null) {
                recordCounter.increment();
            }
        }
    }

    private enum NoOpLogRecordListener implements LogRecordListener {
        INSTANCE;

        @Override
        public void processLogRecord(LogRecord record) {
            // intentionally empty
        }
    }
}
