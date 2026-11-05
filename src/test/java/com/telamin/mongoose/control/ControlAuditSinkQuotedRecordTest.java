/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@link ControlAuditSink#withoutRawEventText} on a record from a runtime that quotes text: the one-line quoted
 * {@code eventToString} goes, the {@code textEncoding} marker stays first, and the record still loads under the
 * export verifier's strict (no duplicate keys) SnakeYAML settings.
 */
class ControlAuditSinkQuotedRecordTest {

    @Test
    void quotedEventTextIsRemovedAndTheMarkedRecordStillLoads() {
        String record = String.join("\n",
                "eventLogRecord: ",
                "    textEncoding: QUOTED_SCALARS",
                "    eventTime: 1",
                "    logTime: 1",
                "    groupingId: null",
                "    event: AuditStartIntent",
                "    eventToString: \"AuditStartIntent{reason: late,\\nsee \\x22ops\\x22}\"",
                "    thread: chaos-g2",
                "    nodeLogs: ",
                "        - controlInputs: { record: Input, seq: 3, fact: AuditStart, opId: \"472.1\", "
                        + "reason: \"membership not active, balance due 30.0\"}",
                "    endTime: 1");
        String stripped = ControlAuditSink.withoutRawEventText(record);
        assertFalse(stripped.contains("eventToString"), stripped);
        assertEquals("eventLogRecord: \n    textEncoding: QUOTED_SCALARS\n    eventTime: 1",
                stripped.substring(0, stripped.indexOf("\n    logTime")));

        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Map<?, ?> body = (Map<?, ?>) ((Map<?, ?>) new Yaml(new SafeConstructor(options)).load(stripped))
                .get("eventLogRecord");
        assertEquals("chaos-g2", body.get("thread"));
        Map<?, ?> fields = (Map<?, ?>) ((Map<?, ?>) ((List<?>) body.get("nodeLogs")).get(0)).get("controlInputs");
        assertEquals("AuditStart", fields.get("fact"));
        assertEquals("472.1", fields.get("opId"));
        assertEquals("membership not active, balance due 30.0", fields.get("reason"));
    }
}
