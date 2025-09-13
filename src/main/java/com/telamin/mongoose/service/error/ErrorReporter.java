/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.error;

import java.util.List;

public interface ErrorReporter {
    void addListener(ErrorListener listener);

    void removeListener(ErrorListener listener);

    void report(ErrorEvent event);

    default void report(String source, String message, Throwable error, ErrorEvent.Severity severity) {
        report(new ErrorEvent(source, message, error, severity));
    }

    List<ErrorEvent> recent(int limit);
}
