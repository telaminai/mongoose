/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Optional test that exercises CoreAffinity helper.
 * The test is written to be environment friendly:
 * - If net.openhft.affinity is not on the test classpath or invocation fails, the test is skipped.
 * - If present, we assert that CoreAffinity reports a successful pin attempt.
 */
public class CoreAffinityOptionalTest {

    @Test
    void pinCurrentThread_reports_success_when_openhft_affinity_present() {
        boolean affinityOnClasspath;
        try {
            Class.forName("net.openhft.affinity.Affinity");
            affinityOnClasspath = true;
        } catch (Throwable t) {
            affinityOnClasspath = false;
        }
        // Make the test optional: skip if not present
        Assumptions.assumeTrue(affinityOnClasspath, "OpenHFT Affinity not available on classpath - skipping pinning test");

        // When present, our helper should return true indicating the reflection call succeeded
        boolean pinned = CoreAffinity.pinCurrentThreadToCore(0);
        assertTrue(pinned, "Expected CoreAffinity to report successful pin when Affinity is available");
    }
}
