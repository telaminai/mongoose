/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control.node;

import com.telamin.fluxtion.runtime.audit.EventLogger;
import com.telamin.fluxtion.runtime.audit.LogRecord;
import com.telamin.fluxtion.runtime.time.Clock;

/**
 * Writes one control-audit record through a node's Fluxtion {@link EventLogger}. Every record carries its type,
 * {@code seq}, {@code requestId}, {@code rootId} and causal links, then its own fields. A node's entry is rendered as
 * a YAML flow mapping, so a plain text value containing a comma or colon would break the record for the analyser.
 * <p>
 * Who quotes text values depends on the Fluxtion runtime on the classpath. A runtime that writes awkward text values
 * as YAML double-quoted scalars itself (and marks the record {@code textEncoding: QUOTED_SCALARS}) is handed the raw
 * text: quoting it here as well would leave literal quote marks in every value ({@code "\x221.1\x22"}). An older
 * runtime appends text verbatim, so the value is quoted here. {@link #RUNTIME_QUOTES_TEXT} decides, once, by asking
 * the runtime's own {@link LogRecord} to write a value that needs quoting.
 */
public final class ControlAudit {

    /**
     * True when the runtime's {@link LogRecord} quotes a text value that YAML would misread; text is then passed raw.
     */
    public static final boolean RUNTIME_QUOTES_TEXT = runtimeQuotesText();

    private ControlAudit() {
    }

    /**
     * @param fields alternating key, value pairs
     * @return the record's {@code seq}
     */
    public static long record(EventLogger log, ControlState state, String type, Object... fields) {
        long seq = state.nextSeq();
        log.info("record", type)
                .info("seq", seq)
                .info("requestId", state.requestId())
                .info("rootId", state.rootId());
        if (state.inputSeq() >= 0) {
            log.info("causeSeq", state.inputSeq());
        }
        if (state.causeOp() != null) {
            log.info("causeOp", text(state.causeOp()));
        }
        for (int i = 0; i + 1 < fields.length; i += 2) {
            Object v = fields[i + 1];
            String key = String.valueOf(fields[i]);
            if (v == null) continue;
            if (v instanceof Long l) {
                log.info(key, (long) l);
            } else if (v instanceof Integer n) {
                log.info(key, (int) n);
            } else if (v instanceof Boolean b) {
                log.info(key, (boolean) b);
            } else {
                log.info(key, text(String.valueOf(v)));
            }
        }
        if (state.inputSeq() < 0) {
            state.markInput(seq);
        }
        return seq;
    }

    /** A text value as this runtime needs it: raw when the runtime quotes it, else {@link #quote quoted} here. */
    static String text(String raw) {
        return RUNTIME_QUOTES_TEXT ? raw : quote(raw);
    }

    /**
     * Probes the runtime: a released runtime appends {@code a, b} bare inside the flow map; one that quotes text
     * values writes it double-quoted. Anything other than the bare value means the runtime has rewritten it. If the
     * probe itself fails, falls back to the presence of the runtime's scalar writer, {@code YamlScalar}.
     */
    static boolean runtimeQuotesText() {
        try {
            LogRecord probe = new LogRecord(Clock.DEFAULT_CLOCK);
            probe.addRecord("probe", "v", "a, b");
            return !probe.asCharSequence().toString().contains("v: a, b");
        } catch (RuntimeException | LinkageError e) {
            try {
                Class.forName("com.telamin.fluxtion.runtime.audit.YamlScalar", false, LogRecord.class.getClassLoader());
                return true;
            } catch (ClassNotFoundException | LinkageError absent) {
                return false;
            }
        }
    }

    /** A double-quoted YAML scalar, for a runtime that does not quote text values itself. */
    public static String quote(String raw) {
        StringBuilder b = new StringBuilder(raw.length() + 2).append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                default -> b.append(c < 0x20 ? ' ' : c);
            }
        }
        return b.append('"').toString();
    }
}
