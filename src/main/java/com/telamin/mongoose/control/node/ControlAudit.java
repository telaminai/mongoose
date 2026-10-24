/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control.node;

import com.telamin.fluxtion.runtime.audit.EventLogger;

/**
 * Writes one control-audit record through a node's Fluxtion {@link EventLogger}. Every record carries its type,
 * {@code seq}, {@code requestId}, {@code rootId} and causal links, then its own fields. Values are always quoted,
 * because a node's entry is rendered as a YAML flow mapping and a plain value containing a comma or colon would break
 * the record for the analyser.
 */
public final class ControlAudit {

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
            log.info("causeOp", quote(state.causeOp()));
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
                log.info(key, quote(String.valueOf(v)));
            }
        }
        if (state.inputSeq() < 0) {
            state.markInput(seq);
        }
        return seq;
    }

    /** A double-quoted YAML scalar. */
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
