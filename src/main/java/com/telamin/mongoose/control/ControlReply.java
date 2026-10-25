/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control;

import com.telamin.mongoose.control.event.FailureKind;
import com.telamin.mongoose.control.event.ReplyOutcome;

/**
 * The one final answer to a control request. {@code APPLIED} means the change is complete, not just accepted.
 *
 * @param requestId  the request it answers
 * @param reason     human-readable detail; never parse it, classify by {@code kind}
 * @param generation the registration it concerns, or -1
 * @param kind       the structured failure classification ({@link FailureKind#NONE} when applied); never null
 */
public record ControlReply(long requestId, ReplyOutcome outcome, String reason, long generation, String name,
                           FailureKind kind, FailureKind evidence) {

    /**
     * Normalised so that {@code kind == NONE} exactly when {@code outcome == APPLIED}, and {@code evidence} is
     * {@code UNVERIFIED} (the group's control audit had an unacknowledged write failure when the reply was made) or
     * {@code NONE}.
     */
    public ControlReply {
        if (outcome == ReplyOutcome.APPLIED) kind = FailureKind.NONE;
        else if (kind == null || kind == FailureKind.NONE) kind = FailureKind.defaultFor(outcome);
        evidence = evidence == FailureKind.UNVERIFIED ? FailureKind.UNVERIFIED : FailureKind.NONE;
    }

    /** A reply with recorded evidence ({@code evidence = NONE}). */
    public ControlReply(long requestId, ReplyOutcome outcome, String reason, long generation, String name,
                        FailureKind kind) {
        this(requestId, outcome, reason, generation, name, kind, FailureKind.NONE);
    }

    /**
     * The pre-taxonomy constructor: the kind is the outcome's broadest class ({@link FailureKind#defaultFor}). Engine
     * code always names the kind.
     */
    public ControlReply(long requestId, ReplyOutcome outcome, String reason, long generation, String name) {
        this(requestId, outcome, reason, generation, name, FailureKind.defaultFor(outcome));
    }

    public boolean applied() {
        return outcome == ReplyOutcome.APPLIED;
    }

    /** False when the reply was made while the group's control audit was degraded: its evidence may be missing. */
    public boolean evidenceVerified() {
        return evidence == FailureKind.NONE;
    }

    /** This reply marked as made without verifiable evidence. */
    public ControlReply unverified() {
        return new ControlReply(requestId, outcome, reason, generation, name, kind, FailureKind.UNVERIFIED);
    }

    static ControlReply notRunning(long requestId, FailureKind kind, String why) {
        return new ControlReply(requestId, ReplyOutcome.NOT_RUNNING, why, -1, null, kind);
    }
}
