/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control.node;

import com.telamin.fluxtion.runtime.audit.EventLogControlEvent;
import com.telamin.fluxtion.runtime.audit.EventLogger;
import com.telamin.fluxtion.runtime.audit.LogRecord;
import com.telamin.fluxtion.runtime.time.Clock;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Control fields read back as the exact text recorded, whichever Fluxtion runtime is on the classpath: one that
 * appends text verbatim (Mongoose quotes) or one that quotes awkward text itself (Mongoose passes it raw). Quoting in
 * both places would leave literal quote marks in the value.
 */
class ControlAuditTextTest {

    @Test
    void controlFieldsReadBackAsRecordedOnThisRuntime() {
        LogRecord record = new LogRecord(Clock.DEFAULT_CLOCK);
        EventLogger log = new EventLogger(record, "controlNode").setLevel(EventLogControlEvent.LogLevel.INFO);
        ControlState state = new ControlState();
        state.beginOutcome(7, "op \"1.1\"");
        ControlAudit.record(log, state, "AuditStart",
                "reason", "membership not active, balance due 30.0",
                "effects", "stop g(3)",
                "opId", "1.1",
                "name", "AuditStart",
                "note", "line one\nline two \\ end",
                "count", 2L);

        String text = record.asCharSequence().toString();
        assertFalse(text.contains("\\x22\\\""), "quoted twice: " + text);
        assertFalse(text.contains("\"\\x22"), "quoted twice: " + text);

        String flowMap = text.substring(text.indexOf('{')) + "}";
        Map<?, ?> fields = new Yaml(new SafeConstructor(new LoaderOptions())).load(flowMap);
        assertEquals("AuditStart", fields.get("record"), text);
        assertEquals("op \"1.1\"", fields.get("causeOp"), text);
        assertEquals("membership not active, balance due 30.0", fields.get("reason"), text);
        assertEquals("stop g(3)", fields.get("effects"), text);
        assertEquals("1.1", fields.get("opId"), text);
        assertEquals("AuditStart", fields.get("name"), text);
        assertEquals("line one\nline two \\ end", fields.get("note"), text);
        assertEquals(2, fields.get("count"), text);
    }

    @Test
    void theProbeMatchesTheRuntime() {
        boolean quotingRuntime;
        try {
            Class.forName("com.telamin.fluxtion.runtime.audit.YamlScalar");
            quotingRuntime = true;
        } catch (ClassNotFoundException absent) {
            quotingRuntime = false;
        }
        assertEquals(quotingRuntime, ControlAudit.RUNTIME_QUOTES_TEXT);
        assertEquals(quotingRuntime ? "a, b" : "\"a, b\"", ControlAudit.text("a, b"));
    }
}
