/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.audit.EventLogControlEvent;
import com.telamin.fluxtion.runtime.audit.EventLogManager;
import com.telamin.fluxtion.runtime.audit.EventLogNode;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.fluxtion.runtime.describe.ProcessorDescriptor;
import com.telamin.fluxtion.runtime.time.Clock;
import com.telamin.mongoose.config.AuditCaptureConfig;
import com.telamin.mongoose.service.audit.AuditCaptureIdentity;
import net.openhft.chronicle.core.time.SetTimeProvider;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.RollCycles;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.wire.DocumentContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Mongoose half of the evidence-set key: a capture begins with the processor's stream header, never counts it as
 * a record, repeats it at the start of every roll cycle so each cycle file is attributable on its own, and records the
 * graph fingerprint and run identity in its sidecar.
 */
class EvidenceSetKeyCaptureTest {

    static final String FINGERPRINT = "5f3c0a9e1b7d4c2a";

    /** A processor with a real audit manager, driven by hand: one record per {@link #publish}. */
    static final class AuditedFlow implements DataFlow {
        public final EventLogManager eventLogger = new EventLogManager();
        private final Book book = new Book();

        static final class Book extends EventLogNode {
            void log(int qty) { auditLog.info("qty", qty); }
        }

        AuditedFlow() {
            Clock clock = new Clock();
            clock.init();
            eventLogger.clock = clock;
            eventLogger.init();
            eventLogger.nodeRegistered(book, "book");
        }

        void publish(int qty) {
            Object e = new Object();
            eventLogger.clock.eventReceived(e);
            eventLogger.eventReceived(e);
            book.log(qty);
            eventLogger.processingComplete();
        }

        @Override public void onEvent(Object event) { }

        @Override
        public void setAuditLogProcessor(LogRecordListener listener) {
            eventLogger.calculationLogConfig(new EventLogControlEvent(listener).descriptor(getDescriptor()));
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T getNodeById(String id) throws NoSuchFieldException {
            if (EventLogManager.NODE_NAME.equals(id)) return (T) eventLogger;
            throw new NoSuchFieldException(id);
        }

        @Override
        public ProcessorDescriptor getDescriptor() {
            return new ProcessorDescriptor() {
                @Override public String name() { return "com.acme.TradeProcessor"; }
                @Override public String sourceFingerprint() { return FINGERPRINT; }
                @Override public List<Input> inputs() { return List.of(); }
                @Override public List<Sink> sinks() { return List.of(); }
                @Override public List<Service> services() { return List.of(); }
                @Override public Class<? extends DataFlow> dataFlowClass() { return null; }
                @Override public Supplier<? extends DataFlow> factory() { return null; }
            };
        }
    }

    static AuditCaptureConfig config(Path root) {
        var config = new AuditCaptureConfig();
        config.setEnabled(true);
        config.setDirectory(root.toString());
        return config;
    }

    static List<String> documents(Path dir) {
        List<String> docs = new ArrayList<>();
        try (ChronicleQueue q = SingleChronicleQueueBuilder.binary(dir).rollCycle(net.openhft.chronicle.queue.rollcycles.TestRollCycles.TEST_SECONDLY).build()) {
            ExcerptTailer tailer = q.createTailer().toStart();
            while (true) {
                try (DocumentContext dc = tailer.readingDocument()) {
                    if (!dc.isPresent()) break;
                    docs.add(dc.wire().getValueIn().text());
                }
            }
        }
        return docs;
    }

    @Test
    void aCaptureBeginsWithTheHeaderRepeatsItEachRollCycleAndNeverCountsIt(@TempDir Path root) throws Exception {
        SetTimeProvider time = new SetTimeProvider(1_700_000_000_000L);
        var capture = new ChronicleAuditCaptureService(config(root), NoOpCountersService.INSTANCE,
                path -> SingleChronicleQueueBuilder.binary(path).rollCycle(net.openhft.chronicle.queue.rollcycles.TestRollCycles.TEST_SECONDLY).timeProvider(time).build());
        var flow = new AuditedFlow();
        var run = RunIdentity.create("pricing-1");
        var owner = new AuditCaptureIdentity("pricing", "quoter", 1);
        flow.eventLogger.setHeaderContext(run.headerContext(owner));
        capture.attachScoped(flow, owner, r -> { }, Runnable::run);
        capture.startScoped(owner);
        var sink = capture.currentSink(owner.selector());

        flow.publish(1);
        flow.publish(2);
        time.advanceMillis(1_500);                  // the next record lands in a new cycle file
        flow.publish(3);
        assertEquals(3, capture.currentSink(owner.selector()).recordCount(), "the header is not a record");
        capture.stop(owner.selector());

        List<String> docs = documents(sink.path());
        assertEquals(5, docs.size(), docs::toString);
        assertTrue(docs.get(0).startsWith("auditStreamHeader:"), docs.get(0));
        assertTrue(docs.get(1).startsWith("eventLogRecord:"));
        assertTrue(docs.get(2).startsWith("eventLogRecord:"));
        assertEquals(docs.get(0), docs.get(3), "the same header opens the new cycle");
        assertTrue(docs.get(4).startsWith("eventLogRecord:"));
        String header = docs.get(0);
        assertTrue(header.contains("\n    sourceFingerprint: " + FINGERPRINT), header);
        assertTrue(header.contains("host: {serverId: pricing-1, runId: " + run.runId()
                + ", group: pricing, name: quoter, generation: '1'"), header);
    }

    @Test
    void theSidecarRecordsTheGraphFingerprintAndTheRun(@TempDir Path root) throws Exception {
        var capture = new ChronicleAuditCaptureService(config(root), NoOpCountersService.INSTANCE);
        var flow = new AuditedFlow();
        var run = RunIdentity.create("pricing-1");
        var owner = new AuditCaptureIdentity("pricing", "quoter", 1);
        flow.eventLogger.setHeaderContext(run.headerContext(owner));
        capture.attachScoped(flow, owner, r -> { }, Runnable::run);
        capture.startScoped(owner);
        var sink = capture.currentSink(owner.selector());
        capture.stop(owner.selector());

        Properties sidecar = new Properties();
        try (var reader = Files.newBufferedReader(sink.path().resolve(".mongoose-capture.properties"))) {
            sidecar.load(reader);
        }
        assertEquals("1", sidecar.getProperty("schema"), "keys are additive within schema 1");
        assertEquals(FINGERPRINT, sidecar.getProperty("sourceFingerprint"));
        assertEquals("pricing-1", sidecar.getProperty("serverId"));
        assertEquals(run.runId(), sidecar.getProperty("runId"));
        assertEquals("pricing", sidecar.getProperty("group"));
        assertEquals("1", sidecar.getProperty("generation"));
    }

    @Test
    void aProcessorWithNoAuditLogLeavesTheKeysOutRatherThanFailing(@TempDir Path root) throws Exception {
        var capture = new ChronicleAuditCaptureService(config(root), NoOpCountersService.INSTANCE);
        DataFlow bare = new DataFlow() {
            @Override public void onEvent(Object event) { }
            @Override public void setAuditLogProcessor(LogRecordListener listener) { }
            @Override public <T> T getNodeById(String id) throws NoSuchFieldException { throw new NoSuchFieldException(id); }
        };
        var owner = new AuditCaptureIdentity("g", "bare", 1);
        capture.attachScoped(bare, owner, r -> { }, Runnable::run);
        capture.startScoped(owner);
        var sink = capture.currentSink(owner.selector());
        capture.stop(owner.selector());
        Properties sidecar = new Properties();
        try (var reader = Files.newBufferedReader(sink.path().resolve(".mongoose-capture.properties"))) {
            sidecar.load(reader);
        }
        assertNull(sidecar.getProperty("sourceFingerprint"));
        assertNull(sidecar.getProperty("runId"));
        assertEquals("bare", sidecar.getProperty("name"));
    }

    @Test
    void eachRunHasItsOwnIdAndTheConfiguredServerIdOrTheHostName() {
        var first = RunIdentity.create("pricing-1");
        var second = RunIdentity.create("pricing-1");
        assertNotEquals(first.runId(), second.runId(), "a restart is a new run even though every generation is #1");
        assertEquals("pricing-1", first.serverId());
        assertFalse(RunIdentity.create("  ").serverId().isBlank(), "blank falls back to the host name");
        Map<String, String> host = first.headerContext(new AuditCaptureIdentity("g", "p", 1));
        assertEquals(List.of("serverId", "runId", "group", "name", "generation"),
                List.copyOf(host.keySet()).subList(0, 5));
    }
}
