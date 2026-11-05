/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control.event;

/**
 * The late result of an asynchronous capture effect, posted once by the helper that performed it to the group's
 * completion queue (design "Audit capture"). {@code requestId} is the root the effect belongs to and {@code opId} the
 * effect; a completion never carries the resource itself, only its id in the group's resource table.
 */
public record CaptureCompletion(long requestId, String opId, long completionId, Kind kind, long resourceId, String reason)
        implements ControlInput {

    public enum Kind { OPENED, OPEN_FAILED, CLOSED, CLOSE_FAILED, TIMED_OUT }

    /** The input's name in the audit vocabulary. */
    public String fact() {
        return switch (kind) {
            case OPENED -> "CaptureOpened";
            case OPEN_FAILED -> "CaptureOpenFailed";
            case CLOSED, CLOSE_FAILED -> "CaptureClosed";
            case TIMED_OUT -> "CaptureOpenTimedOut";
        };
    }
}
