/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control;

import com.telamin.mongoose.control.event.ReplyOutcome;

/**
 * The one final answer to a control request. {@code APPLIED} means the change is complete, not just accepted.
 *
 * @param requestId  the request it answers
 * @param generation the registration it concerns, or -1
 */
public record ControlReply(long requestId, ReplyOutcome outcome, String reason, long generation, String name) {

    public boolean applied() {
        return outcome == ReplyOutcome.APPLIED;
    }

    static ControlReply notRunning(long requestId, String why) {
        return new ControlReply(requestId, ReplyOutcome.NOT_RUNNING, why, -1, null);
    }
}
