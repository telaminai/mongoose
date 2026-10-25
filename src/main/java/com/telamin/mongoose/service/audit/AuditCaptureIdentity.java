/*
 * SPDX-FileCopyrightText: © 2026 Telamin
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.audit;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Immutable owner of a server capture. Names are data, never filesystem path components. */
public record AuditCaptureIdentity(String group, String name, long generation) {
    public AuditCaptureIdentity {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(name, "name");
        if (generation < 0) throw new IllegalArgumentException("unassigned registration");
    }

    public String selector() { return selector(group, name); }

    /** Percent-encoded components keep '/', '%' and other delimiters unambiguous. */
    public static String selector(String group, String name) { return encode(group) + "/" + encode(name); }
    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }
    public static String decode(String text) {
        return URLDecoder.decode(text.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}
