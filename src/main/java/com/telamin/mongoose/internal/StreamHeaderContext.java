/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.audit.EventLogManager;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Hands a processor its audit stream header's host context while it is being built.
 *
 * <p>A configured processor's supplier installs the audit listener and log level before it returns, and the
 * log-level change is itself an audited cycle: a header is written then. Applying the run context only after the
 * supplier returned made every configured processor's stream begin with a header carrying no run, followed by a
 * second one carrying it. The server therefore states the context for the duration of the supplier call, on the
 * building thread, and the supplier applies it to the processor before it installs anything.
 */
public final class StreamHeaderContext {

    private static final ThreadLocal<Map<String, String>> BUILDING = new ThreadLocal<>();

    private StreamHeaderContext() {
    }

    /** Calls {@code supplier} with {@code context} stated for any {@link #applyTo} it makes. */
    public static <T> T building(Map<String, String> context, Supplier<T> supplier) {
        Map<String, String> outer = BUILDING.get();
        BUILDING.set(context);
        try {
            return supplier.get();
        } finally {
            if (outer == null) BUILDING.remove();
            else BUILDING.set(outer);
        }
    }

    /** Applies the context stated by an enclosing {@link #building} call, if any; otherwise does nothing. */
    public static void applyTo(DataFlow processor) {
        Map<String, String> context = BUILDING.get();
        if (context != null) apply(processor, context);
    }

    /**
     * Sets {@code context} on the processor's audit manager. A processor built without an audit log has no manager
     * and writes no stream, so there is nothing to name; that is not a failure.
     */
    public static void apply(DataFlow processor, Map<String, String> context) {
        EventLogManager manager;
        try {
            manager = processor.getAuditorById(EventLogManager.NODE_NAME);
        } catch (NoSuchFieldException | IllegalAccessException | RuntimeException noAuditLog) {
            return;
        }
        if (manager != null) manager.setHeaderContext(context);
    }
}
