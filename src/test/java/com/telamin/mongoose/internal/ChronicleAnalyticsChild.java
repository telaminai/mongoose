/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import net.openhft.chronicle.core.analytics.AnalyticsFacade;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.queue.internal.AnalyticsHolder;

import java.nio.file.Path;

/**
 * Child JVM for {@link ChronicleAnalyticsTest}: initialises a Mongoose entry class (or none), opens a
 * Chronicle queue the way Mongoose does, then prints what Chronicle decided about analytics.
 * A fresh JVM, because Chronicle builds its analytics client once per JVM, on the first queue open.
 */
public final class ChronicleAnalyticsChild {
    public static void main(String[] args) throws Exception {
        String entry = args[0];
        Path queueDir = Path.of(args[1]);
        System.out.println("before=" + System.getProperty(ChronicleAnalytics.DISABLE_PROPERTY));
        if (!entry.equals("none")) {
            Class.forName(entry, true, ChronicleAnalyticsChild.class.getClassLoader());
        }
        try (ChronicleQueue queue = SingleChronicleQueueBuilder.binary(queueDir).build()) {
            try (var appender = queue.createAppender()) { appender.writeText("demo"); }
        }
        System.out.println("after=" + System.getProperty(ChronicleAnalytics.DISABLE_PROPERTY));
        System.out.println("analyticsJarPresent=" + isPresent("net.openhft.chronicle.analytics.Analytics"));
        System.out.println("enabled=" + AnalyticsFacade.isEnabled());
        System.out.println("client=" + AnalyticsHolder.instance().getClass().getName());
    }

    private static boolean isPresent(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
