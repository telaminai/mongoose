/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control.event;

/**
 * The structured classification of why something did not simply apply (issue #77, docs/design/failure-handling.md,
 * "Failure taxonomy"). It travels beside the free-text reason on {@code ControlReply}, effect results, replay stop
 * reasons and control-audit records (field {@code kind}). Free text stays as human detail; programs, the evidence
 * oracle and test harnesses must classify by kind, never by matching reason text or rule names.
 *
 * <p>A kind says who decided and what the caller may assume. It never claims more than the producer observed:
 * {@link #NONE} on an {@code APPLIED} reply means the change completed, not that every callback inside it succeeded.
 */
public enum FailureKind {
    /** Not a failure: the request applied (or an effect succeeded). */
    NONE,
    /** Not admitted: the mailbox was full, the sender cannot wait, or the caller withdrew it before it was claimed. */
    REFUSED_ADMISSION,
    /**
     * Not applied because of the target's state: no such processor, a name in use, a group or server closing or closed,
     * an add cancelled by an earlier stop. Nothing ran.
     */
    REFUSED_STATE,
    /** Not admitted within the caller's own deadline. Nothing ran. */
    REFUSED_DEADLINE,
    /**
     * The request itself is malformed or not this server's to act on (a missing or malformed argument, a handle from
     * another server). Nothing was attempted; resubmitting it unchanged is refused again. Added in review round 3:
     * neither capacity ({@code REFUSED_ADMISSION}) nor target state ({@code REFUSED_STATE}) describes it.
     */
    REFUSED_INVALID,
    /**
     * The control processor itself failed (a rule threw, effects did not settle, the processor is wedged) and the
     * driver answered in its place. An engine defect: never acceptable evidence of a healthy run.
     */
    FAILED_RULE,
    /** An effect the rules requested failed (a start, release, capture open/install/uninstall, service delivery). */
    FAILED_EFFECT,
    /**
     * The rules settled without answering, or a requested effect was abandoned, and the driver's safety net authored
     * the outcome. An engine defect: never acceptable evidence of a healthy run.
     */
    FAILED_DRIVER_FALLBACK,
    /** The group died (its agent thread ended abnormally): accepted work was answered by the death path. */
    FAILED_DEAD,
    /** The group or server ended in order with this work still outstanding, or a stop did not complete in time. */
    FAILED_SHUTDOWN,
    /**
     * A processor was quarantined after a generated callback failure (or a request addressed it while quarantined).
     * Its instance and name stay owned; there is no automatic retry or restart.
     */
    QUARANTINED,
    /**
     * The caller stopped waiting while the work may still be running; its outcome will be known later. Not a
     * cancellation.
     */
    DETACHED,
    /** The outcome cannot be verified from the evidence available (an incomplete or failed audit). Never a pass. */
    UNVERIFIED,
    /**
     * A resource the control processor should have released was reclaimed by the group's offline cleanup. Recorded as
     * a recovery, never as a normal close.
     */
    LEFTOVER_RECOVERED,
    /** A subscriber lost or refused data under its overload policy; inspect the queue state and loss detail. */
    OVERLOAD_LOSS,
    /** A replay could not match an input, stream, service version or control request to the recording. */
    REPLAY_IDENTITY_MISMATCH,
    /** A replay stopped for any other reason (a recorded failure, a delivery timeout, a clock divergence, ...). */
    REPLAY_STOPPED;

    /** Nothing was applied: the request was turned down before any effect ran. */
    public boolean refusal() {
        return this == REFUSED_ADMISSION || this == REFUSED_STATE || this == REFUSED_DEADLINE || this == REFUSED_INVALID;
    }

    /** Authored by the driver's safety net because the control processor could not answer: an engine defect. */
    public boolean engineSafetyNet() {
        return this == FAILED_RULE || this == FAILED_DRIVER_FALLBACK;
    }

    /** A replay stop classification. */
    public boolean replay() {
        return this == REPLAY_IDENTITY_MISMATCH || this == REPLAY_STOPPED;
    }

    /**
     * The kind a reply carries when its producer named none (a caller of the pre-taxonomy constructor): the outcome's
     * broadest class. Engine code always names the kind explicitly.
     */
    public static FailureKind defaultFor(ReplyOutcome outcome) {
        if (outcome == null) return UNVERIFIED;
        return switch (outcome) {
            case APPLIED -> NONE;
            case REFUSED, NOT_RUNNING -> REFUSED_STATE;
            case FAILED -> FAILED_EFFECT;
        };
    }

    /** The kind an effect result carries when its producer named none. */
    public static FailureKind defaultFor(ControlOutcome outcome) {
        return outcome == ControlOutcome.FAILED ? FAILED_EFFECT : NONE;
    }

    /** Parse an audit field; null for a missing or unknown value (an export older than the taxonomy). */
    public static FailureKind parse(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return valueOf(text.strip());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
