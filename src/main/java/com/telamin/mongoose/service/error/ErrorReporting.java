/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.error;

/**
 * Static facade for global error reporting. Allows core components to
 * raise error notifications without wiring a reporter through constructors.
 */
public final class ErrorReporting {
    private static volatile ErrorReporter reporter = new DefaultErrorReporter();

    private ErrorReporting() {
    }

    public static ErrorReporter getReporter() {
        return reporter;
    }

    public static void setReporter(ErrorReporter customReporter) {
        if (customReporter != null) reporter = customReporter;
    }

    public static void report(String source, String message, Throwable error, ErrorEvent.Severity severity) {
        reporter.report(source, message, error, severity);
    }
}
