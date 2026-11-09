/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import java.util.Properties;

/**
 * Keeps Chronicle's usage analytics off in a Mongoose JVM.
 * <p>
 * Chronicle Queue sends a usage event to Google Analytics each time a queue is opened, unless the
 * {@code net.openhft:chronicle-analytics} jar is absent or the system property
 * {@value #DISABLE_PROPERTY} is true when Chronicle first builds its analytics client. Mongoose
 * excludes that jar from its own dependencies; this class covers the other case, where an
 * application brings the jar back through another dependency, by setting the property before any
 * Mongoose code opens a queue.
 * <p>
 * An explicit setting wins: if {@value #DISABLE_PROPERTY} is already set (for example with
 * {@code -Dchronicle.analytics.disable=false}), it is left as it is.
 */
public final class ChronicleAnalytics {

    /** The system property Chronicle reads (chronicle-core {@code AnalyticsFacade.isEnabled()}). */
    public static final String DISABLE_PROPERTY = "chronicle.analytics.disable";

    private ChronicleAnalytics() {
    }

    /** Sets {@value #DISABLE_PROPERTY}=true in the JVM's system properties unless it is already set. */
    public static void disableUnlessConfigured() {
        disableUnlessConfigured(System.getProperties());
    }

    /**
     * Sets {@value #DISABLE_PROPERTY}=true in {@code properties} unless it is already set.
     *
     * @return true if this call set it, false if a value was already present
     */
    static boolean disableUnlessConfigured(Properties properties) {
        synchronized (properties) {
            if (properties.getProperty(DISABLE_PROPERTY) != null) {
                return false;
            }
            properties.setProperty(DISABLE_PROPERTY, "true");
            return true;
        }
    }
}
