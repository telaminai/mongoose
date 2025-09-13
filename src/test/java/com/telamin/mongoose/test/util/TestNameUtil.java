/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.test.util;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Utility for generating unique, human-readable names for test resources
 * (event feeds, services, processors). Includes a test-run stable prefix
 * to make logs easier to correlate.
 */
public final class TestNameUtil {
    private static final String RUN_ID = UUID.randomUUID().toString().substring(0, 8);
    private static final AtomicLong COUNTER = new AtomicLong(0);

    private TestNameUtil() {
    }

    public static String unique(String base) {
        long n = COUNTER.incrementAndGet();
        return base + "-" + RUN_ID + "-" + n;

    }
}
