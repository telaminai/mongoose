/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control;

import com.telamin.fluxtion.runtime.audit.LogRecord;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The control processor's audit record, kept per group in a bounded ring (the analyser's {@code SessionAuditSink}
 * shape). It counts what it drops and what it fails to write, says when it is incomplete, and exports a snapshot the
 * analyser reopens one record per dispatch. It also carries the <b>fallback stream</b>: plain records written by the
 * group itself, outside the control processor, for what must stay observable when the control processor or its audit
 * cannot write (a rule that throws, a group that dies, a command swept at close).
 *
 * <p>Records arrive on the group's agent thread; reads ({@link #records()}, {@link #export}) may come from any thread,
 * so access is synchronised. Recording is a control-path cost, never an event-path one.
 */
public final class ControlAuditSink implements LogRecordListener {

    public static final int DEFAULT_CAPACITY = 10_000;

    private final int capacity;
    private final Deque<String> records = new ArrayDeque<>();
    private long total;
    private long dropped;
    private long sinkFailures;
    private String firstSinkFailure;
    private long fallbackSeq;
    /** Sink failures an operator has acknowledged, by exporting the evidence or explicitly (issue #77, M3). */
    private long acknowledgedSinkFailures;

    public ControlAuditSink() {
        this(DEFAULT_CAPACITY);
    }

    public ControlAuditSink(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive: " + capacity);
        this.capacity = capacity;
    }

    @Override
    public synchronized void processLogRecord(LogRecord logRecord) {
        try {
            add(withoutRawEventText(logRecord.asCharSequence().toString()));
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable e) {
            // any non-VM failure (an Error included) is an audit write failure: DEGRADED, never a dead control plane
            boolean wasDegraded = evidenceDegraded();
            sinkFailures++;
            if (firstSinkFailure == null) firstSinkFailure = e.toString();
            if (!wasDegraded) {
                // fail closed (issue #77, working decision 2): from here every reply is marked UNVERIFIED until the
                // evidence is exported or acknowledged. Written to the fallback stream, which needs no conversion
                fallback("EvidenceDegraded", "kind", "UNVERIFIED", "sinkFailures", sinkFailures, "reason", e.toString());
            }
        }
    }

    /**
     * The control audit failed to write a record that no operator has acknowledged yet: the group is DEGRADED and its
     * replies carry {@code evidence = UNVERIFIED}. It keeps accepting every request, stops and closes included.
     */
    public synchronized boolean evidenceDegraded() {
        return sinkFailures > acknowledgedSinkFailures;
    }

    /**
     * Acknowledge the gap: replies are no longer marked once the operator has taken the evidence as it is. The gap stays
     * recorded ({@link #isComplete()} remains false and an export says so); acknowledgement never makes evidence whole.
     */
    public synchronized void acknowledgeEvidenceGap() {
        acknowledge(sinkFailures, "acknowledged");
    }

    private void acknowledge(long upTo, String how) {
        if (upTo <= acknowledgedSinkFailures) return;
        acknowledgedSinkFailures = upTo;
        fallback("EvidenceAcknowledged", "sinkFailures", upTo, "how", how);
    }

    /**
     * A fallback record, written by the group rather than the control processor: unframed by Fluxtion, coarser, but
     * always available.
     *
     * @param fields alternating key, value pairs
     */
    public synchronized void fallback(String type, Object... fields) {
        StringBuilder b = new StringBuilder("controlFallback:\n    record: ").append(type)
                .append("\n    fallbackSeq: ").append(++fallbackSeq);
        for (int i = 0; i + 1 < fields.length; i += 2) {
            Object v = fields[i + 1];
            if (v == null) continue;
            b.append("\n    ").append(fields[i]).append(": ");
            if (v instanceof Number || v instanceof Boolean) {
                b.append(v);
            } else {
                b.append(com.telamin.mongoose.control.node.ControlAudit.quote(String.valueOf(v)));
            }
        }
        add(b.toString());
    }

    /**
     * Drop the runtime's {@code eventToString} line, and any continuation lines, from a record. It is printed unquoted
     * (some records ignore {@code printEventToString(false)}, such as an exported function's multi-line signature), so
     * it can make a record unreadable as YAML; every control field is already recorded, quoted, by the nodes.
     */
    static String withoutRawEventText(String record) {
        if (!record.contains("\n    eventToString:")) return record;
        StringBuilder out = new StringBuilder(record.length());
        boolean skipping = false;
        for (String line : record.split("\n", -1)) {
            if (line.startsWith("    eventToString:")) {
                skipping = true;
                continue;
            }
            // the record's next field (four spaces, a name, a colon) ends the event text
            if (skipping && !RECORD_FIELD.matcher(line).lookingAt()) continue;
            skipping = false;
            if (out.length() > 0) out.append('\n');
            out.append(line);
        }
        return out.toString();
    }

    private static final java.util.regex.Pattern RECORD_FIELD = java.util.regex.Pattern.compile("^    [A-Za-z]+:");

    private void add(String text) {
        total++;
        records.addLast(text);
        while (records.size() > capacity) {
            records.removeFirst();
            dropped++;
        }
    }

    public synchronized List<String> records() {
        return new ArrayList<>(records);
    }

    /** Every record ever offered, including any the ring has since dropped. */
    public synchronized long total() {
        return total;
    }

    public synchronized long dropped() {
        return dropped;
    }

    public synchronized long sinkFailures() {
        return sinkFailures;
    }

    public synchronized String firstSinkFailure() {
        return firstSinkFailure;
    }

    /** False once anything was dropped or failed to write: a truncated record must not claim to be the whole. */
    public synchronized boolean isComplete() {
        return dropped == 0 && sinkFailures == 0;
    }

    /** The records as analyser-readable YAML: a {@code ---} line before every record, as the analyser frames them. */
    public synchronized String exportText() {
        StringBuilder out = new StringBuilder();
        for (String record : records) {
            out.append("---\n").append(record);
            if (record.isEmpty() || record.charAt(record.length() - 1) != '\n') out.append('\n');
        }
        out.append("---\n");
        return out.toString();
    }

    private record ExportSnapshot(byte[] yaml, String manifest, long sinkFailures) { }

    private synchronized ExportSnapshot exportSnapshot() {
        byte[] yaml = exportText().getBytes(StandardCharsets.UTF_8);
        return new ExportSnapshot(yaml, ControlAuditExport.manifest(yaml, total, records.size(), dropped, sinkFailures),
                sinkFailures);
    }

    /** Export unchanged analyser YAML plus a versioned completeness sidecar from the same locked snapshot. */
    public Path export(Path target) throws IOException {
        ExportSnapshot snapshot = exportSnapshot();
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Path manifest = ControlAuditExport.manifestPath(target);
        // Invalidate any old completeness claim first. Interrupted writes are unknown, never silently complete.
        Files.deleteIfExists(manifest);
        Files.write(target, snapshot.yaml());
        Files.writeString(manifest, snapshot.manifest(), StandardCharsets.UTF_8);
        // the exported pair records the failures it contains: those are acknowledged, later ones are not
        synchronized (this) {
            acknowledge(snapshot.sinkFailures(), "exported");
        }
        return target;
    }
}
