/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.error;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ErrorReportingTest {

    @AfterEach
    void reset() {
        // reset to default to avoid test side effects
        ErrorReporting.setReporter(new DefaultErrorReporter());
    }

    @Test
    void listenerReceivesReportedError() {
        DefaultErrorReporter reporter = new DefaultErrorReporter(10);
        ErrorReporting.setReporter(reporter);

        List<ErrorEvent> received = new ArrayList<>();
        reporter.addListener(received::add);

        ErrorReporting.report("unit-test", "something went wrong", new RuntimeException("boom"), ErrorEvent.Severity.ERROR);

        assertEquals(1, received.size());
        ErrorEvent e = received.get(0);
        assertEquals("unit-test", e.getSource());
        assertEquals("something went wrong", e.getMessage());
        assertEquals(ErrorEvent.Severity.ERROR, e.getSeverity());
        assertNotNull(e.getError());
    }

    @Test
    void recentReturnsHistoryBounded() {
        DefaultErrorReporter reporter = new DefaultErrorReporter(3);
        ErrorReporting.setReporter(reporter);
        reporter.report(new ErrorEvent("s1", "m1", null, ErrorEvent.Severity.INFO));
        reporter.report(new ErrorEvent("s2", "m2", null, ErrorEvent.Severity.WARNING));
        reporter.report(new ErrorEvent("s3", "m3", null, ErrorEvent.Severity.ERROR));
        reporter.report(new ErrorEvent("s4", "m4", null, ErrorEvent.Severity.CRITICAL));

        List<ErrorEvent> last2 = reporter.recent(2);
        assertEquals(2, last2.size());
        assertEquals("s3", last2.get(0).getSource());
        assertEquals("s4", last2.get(1).getSource());
    }
}
