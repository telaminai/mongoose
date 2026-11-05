package com.telamin.mongoose.control;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ControlAuditExportTest {
    @TempDir Path dir;

    @Test void completeSnapshotCanBeCheckedWithoutTheSink() throws Exception {
        var sink = new ControlAuditSink(); sink.fallback("Accepted", "requestId", 1);
        Path file = sink.export(dir.resolve("control.yaml"));
        assertEquals(sink.exportText(), Files.readString(file), "raw analyser format is unchanged");
        assertEquals(ControlAuditExport.Completeness.COMPLETE, ControlAuditExport.verify(file).completeness());
        assertTrue(ControlAuditExport.verify(file).permitsInvariantPass());
    }

    @Test void droppedFallbackWithoutDecisionSequenceGapIsStillIncomplete() throws Exception {
        var sink = new ControlAuditSink(1);
        sink.fallback("Refused", "requestId", 1); sink.fallback("Refused", "requestId", 2);
        Path file = sink.export(dir.resolve("control.yaml"));
        assertFalse(Files.readString(file).contains("seq:"), "no control decision sequence can expose this loss");
        var result = ControlAuditExport.verify(file);
        assertEquals(ControlAuditExport.Completeness.INCOMPLETE, result.completeness());
        assertFalse(result.permitsInvariantPass());
        var evidence = com.telamin.mongoose.control.validation.ControlEvidence.parse(
                Files.readString(file), result.permitsInvariantPass());
        var invariants = com.telamin.mongoose.control.validation.ControlInvariants.check(evidence);
        assertTrue(invariants.ok(), "the existing boolean still means no observed violations");
        assertFalse(invariants.unverifiable().isEmpty(), "missing evidence is not a passing investigation");
        assertFalse(result.permitsInvariantPass() && invariants.ok() && invariants.unverifiable().isEmpty());
    }

    @Test void missingMalformedOrMismatchedManifestCannotAuthorizePass() throws Exception {
        var sink = new ControlAuditSink(); sink.fallback("Accepted");
        Path file = sink.export(dir.resolve("control.yaml"));
        Path manifest = ControlAuditExport.manifestPath(file);
        String original = Files.readString(manifest);
        Files.delete(manifest);
        assertUnknown(file);
        Files.writeString(manifest, "version: [broken");
        assertUnknown(file);
        Files.writeString(manifest, original.replace("version: 1", "version: 2"));
        assertUnknown(file);
        Files.writeString(manifest, original.replace("retained: 1", "retained: 0"));
        assertUnknown(file);
        Files.writeString(manifest, original);
        Files.writeString(file, Files.readString(file) + "# altered\n");
        assertUnknown(file);
    }

    @Test void sinkFailureSurvivesExportAndFalseCompleteClaimIsRejected() throws Exception {
        var sink = new ControlAuditSink();
        sink.processLogRecord(null); // conversion failure increments sinkFailures without producing a document
        Path file = sink.export(dir.resolve("control.yaml"));
        assertEquals(ControlAuditExport.Completeness.INCOMPLETE, ControlAuditExport.verify(file).completeness());
        Path manifest = ControlAuditExport.manifestPath(file);
        Files.writeString(manifest, Files.readString(manifest).replace("complete: false", "complete: true"));
        assertUnknown(file);
    }

    @Test void manifestFromAnotherExportCannotAuthorizePass() throws Exception {
        var a = new ControlAuditSink(); a.fallback("Accepted", "requestId", 1);
        var b = new ControlAuditSink(); b.fallback("Accepted", "requestId", 2);
        Path first = a.export(dir.resolve("first.yaml")); Path second = b.export(dir.resolve("second.yaml"));
        Files.writeString(ControlAuditExport.manifestPath(second), Files.readString(ControlAuditExport.manifestPath(first)));
        assertUnknown(second);
    }

    @Test void concurrentRecordingCannotMixSnapshotRecordsAndCounters() throws Exception {
        var sink = new ControlAuditSink(16);
        var running = new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread producer = new Thread(() -> { while (running.get()) sink.fallback("Observed"); });
        producer.start();
        try {
            for (int i = 0; i < 20; i++) {
                Path file = sink.export(dir.resolve("snapshot-" + i + ".yaml"));
                var result = ControlAuditExport.verify(file);
                assertNotEquals(ControlAuditExport.Completeness.UNKNOWN, result.completeness(), result.reason());
            }
        } finally {
            running.set(false);
            producer.join(5000);
        }
        assertFalse(producer.isAlive());
    }

    private static void assertUnknown(Path file) {
        var result = ControlAuditExport.verify(file);
        assertEquals(ControlAuditExport.Completeness.UNKNOWN, result.completeness(), result.reason());
        assertFalse(result.permitsInvariantPass());
    }
}
