/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control.node;

import com.telamin.fluxtion.runtime.audit.EventLogger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Writes one control-audit record through a node's Fluxtion {@link EventLogger}. Every record carries its type,
 * {@code seq}, {@code requestId}, {@code rootId} and causal links, then its own fields. A node's entry is rendered as
 * a YAML flow mapping, so a plain text value containing a comma or colon would break the record for the analyser, and
 * every control text field must read back as a String: op ids such as {@code 472.1} and {@code 472.10} are distinct
 * identifiers, equal as numbers.
 * <p>
 * How a text field is written depends on the Fluxtion runtime on the classpath, detected once at class load:
 * <ul>
 * <li>A runtime with the text API ({@code EventLogger.infoText(String, CharSequence)}) is handed the raw text through
 * it. It writes the value as a String in every reader: double-quoted for any structural reason and when it looks
 * like a number, boolean or null, and it marks the record {@code textEncoding: QUOTED_SCALARS}. Quoting here as well
 * would be escaped and leave literal quote marks ({@code "\x22472.1\x22"}).</li>
 * <li>An older runtime (1.1.3) appends text verbatim, so the value is {@link #quote quoted} here, as before.</li>
 * </ul>
 * The handle is a {@code static final} {@link MethodHandle}, so the call costs no reflection per record.
 */
public final class ControlAudit {

    /** {@code EventLogger.infoText(String, CharSequence)}, or {@code null} on a runtime without the text API. */
    private static final MethodHandle INFO_TEXT = findInfoText();

    /** True when the runtime has the text API and control text is logged raw through it. */
    public static final boolean RUNTIME_TEXT_API = INFO_TEXT != null;

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
            text(log, "causeOp", state.causeOp());
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
                text(log, key, String.valueOf(v));
            }
        }
        if (state.inputSeq() < 0) {
            state.markInput(seq);
        }
        return seq;
    }

    /** Logs {@code raw} at INFO so every reader reads it back as this String. */
    static void text(EventLogger log, String key, String raw) {
        if (INFO_TEXT == null) {
            log.info(key, quote(raw));
            return;
        }
        try {
            EventLogger ignored = (EventLogger) INFO_TEXT.invokeExact(log, key, (CharSequence) raw);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable impossible) {
            // infoText declares no checked exception
            throw new IllegalStateException(impossible);
        }
    }

    private static MethodHandle findInfoText() {
        try {
            return MethodHandles.publicLookup().findVirtual(EventLogger.class, "infoText",
                    MethodType.methodType(EventLogger.class, String.class, CharSequence.class));
        } catch (NoSuchMethodException | IllegalAccessException absent) {
            return null;
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
