/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control;

import com.telamin.fluxtion.runtime.audit.AuditStreamHeader;
import com.telamin.fluxtion.runtime.audit.StreamHeaderRecord;
import com.telamin.fluxtion.runtime.audit.LogRecord;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.internal.RunIdentity;
import com.telamin.mongoose.internal.StreamHeaderContext;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The control plane's own audit stream carries the evidence-set key: the header names the run and the control graph,
 * is kept outside the bounded ring (never counted, never evicted) and opens every export.
 */
class ControlStreamHeaderTest {

    private static LogRecord record(String text) {
        LogRecord r = new LogRecord(null);
        r.replaceBuffer(text);
        return r;
    }

    @Test
    void theHeaderIsNeitherCountedNorEvictedAndOpensTheExport() {
        ControlAuditSink sink = new ControlAuditSink(3);
        sink.processStreamHeader(new StreamHeaderRecord(
                new AuditStreamHeader("p", "abc1", null, null, "t", Map.of("runId", "r1"))));
        for (int i = 0; i < 4; i++) sink.processLogRecord(record("eventLogRecord:\n    n: " + i));
        assertEquals(4, sink.total(), "only records are counted");
        assertEquals(1, sink.dropped());
        String export = sink.exportText();
        assertTrue(export.startsWith("---\nauditStreamHeader:"), export);
        assertTrue(export.contains("runId: r1"), export);
        assertEquals(3, export.split("(?m)^eventLogRecord:", -1).length - 1, "the ring still holds three records");
    }

    @Test
    void aGroupBuiltByTheServerNamesTheRunInItsControlStream() {
        RunIdentity run = RunIdentity.create("pricing-1");
        ComposingEventProcessorAgent group = StreamHeaderContext.building(run.controlContext("pricing"),
                () -> new ComposingEventProcessorAgent("pricing", new EventFlowManager(), null, new DeadWheelScheduler(),
                        new ConcurrentHashMap<>()));
        String header = group.control().audit().streamHeader();
        assertNotNull(header, "init is audited, so the control stream has begun");
        assertTrue(header.contains("host: {serverId: pricing-1, runId: " + run.runId()
                + ", group: pricing, name: control, generation: '0'"), header);
    }

    /** Acceptance 1, not judged against itself: the header's fingerprint equals the one in the committed graphml. */
    @Test
    void theControlHeaderFingerprintEqualsItsGraphml() throws Exception {
        ComposingEventProcessorAgent group = new ComposingEventProcessorAgent("g", new EventFlowManager(), null,
                new DeadWheelScheduler(), new ConcurrentHashMap<>());
        String header = group.control().audit().streamHeader();
        String graphml;
        try (InputStream in = getClass().getResourceAsStream(
                "/com/telamin/mongoose/control/generated/GroupControlProcessor.graphml")) {
            graphml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher m = Pattern.compile("fluxtion\\.sourceFingerprint\">([0-9a-f]{64})<").matcher(graphml);
        assertTrue(m.find(), "the committed graphml states a fingerprint");
        assertTrue(header.contains("\n    sourceFingerprint: " + m.group(1)), header);
    }
}
