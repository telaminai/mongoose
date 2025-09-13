/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.error;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents an error/alert that can be reported and broadcast to listeners.
 */
public final class ErrorEvent {
    public enum Severity {INFO, WARNING, ERROR, CRITICAL}

    private final Instant timestamp;
    private final String source;
    private final String message;
    private final Throwable error;
    private final Severity severity;

    public ErrorEvent(String source, String message, Throwable error, Severity severity) {
        this.timestamp = Instant.now();
        this.source = source == null ? "unknown" : source;
        this.message = message == null ? "" : message;
        this.error = error;
        this.severity = severity == null ? Severity.ERROR : severity;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getSource() {
        return source;
    }

    public String getMessage() {
        return message;
    }

    public Throwable getError() {
        return error;
    }

    public Severity getSeverity() {
        return severity;
    }

    @Override
    public String toString() {
        return "ErrorEvent{" +
                "timestamp=" + timestamp +
                ", source='" + source + '\'' +
                ", message='" + message + '\'' +
                ", severity=" + severity +
                (error != null ? ", error=" + error : "") +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ErrorEvent that)) return false;
        return Objects.equals(timestamp, that.timestamp) &&
                Objects.equals(source, that.source) &&
                Objects.equals(message, that.message) &&
                severity == that.severity &&
                Objects.equals(error == null ? null : error.toString(), that.error == null ? null : that.error.toString());
    }

    @Override
    public int hashCode() {
        return Objects.hash(timestamp, source, message, severity);
    }
}
