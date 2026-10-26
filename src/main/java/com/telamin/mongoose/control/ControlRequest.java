/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control;

import com.telamin.mongoose.control.event.ControlInput;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;

/**
 * One control request on its way through a group's mailbox. It carries its {@code requestId}, the side payload the
 * group needs to perform its effects (a supplier, a task, a service, a registration, never seen by the control
 * processor), the factory that turns it into a normalised intent on the agent thread, and its reply.
 *
 * <h2>Two-phase claim</h2>
 * Its state moves only by compare-and-set: {@code SENT → CLAIMED → REPLIED}, or {@code SENT → REPLIED}. The agent
 * claims a request before applying it. A sweep made while the agent may still be running completes only {@code SENT}
 * requests, so it can never race the agent's own reply; a {@code CLAIMED} request is completed by the agent, or by the
 * server once the agent's thread has been joined. Either way the {@code → REPLIED} transition makes the reply happen
 * once.
 */
public final class ControlRequest {

    public enum Kind { ADD, STOP, BROADCAST, RUN, CLOSE, CAPTURE }

    static final int SENT = 0;
    static final int CLAIMED = 1;
    static final int REPLIED = 2;

    private final Kind kind;
    private final String description;
    private final LongFunction<ControlInput> intentOnAgent;
    private final Object payload;
    private final AtomicInteger state = new AtomicInteger(SENT);
    private final CompletableFuture<ControlReply> reply = new CompletableFuture<>();
    private volatile long requestId = -1;
    private volatile Runnable onReply;
    private volatile Runnable onUnapplied;
    /** Applied to every completion, whoever completes it: the group marks replies made while its audit is degraded. */
    private volatile java.util.function.UnaryOperator<ControlReply> replyMarker;

    /**
     * @param intentOnAgent builds the intent from the request id; called on the agent thread when the request is
     *                      applied, so it may read agent-only facts (whether an add was already taken)
     * @param payload       what the group needs to perform the request's effects
     */
    public ControlRequest(Kind kind, String description, LongFunction<ControlInput> intentOnAgent, Object payload) {
        this.kind = kind;
        this.description = description;
        this.intentOnAgent = intentOnAgent;
        this.payload = payload;
    }

    public Kind kind() {
        return kind;
    }

    public String description() {
        return description;
    }

    public long requestId() {
        return requestId;
    }

    void assignId(long id) {
        this.requestId = id;
    }

    ControlInput intent() {
        return intentOnAgent.apply(requestId);
    }

    @SuppressWarnings("unchecked")
    public <T> T payload() {
        return (T) payload;
    }

    /** Run once, on whichever thread completes the reply (legacy stop callbacks). */
    public ControlRequest onReply(Runnable action) {
        this.onReply = action;
        return this;
    }

    /**
     * Run once if the request is answered without ever being applied (refused at send, swept at close or death): what
     * the sender reserved for it, such as a name, must not outlive it.
     */
    public ControlRequest onUnapplied(Runnable action) {
        this.onUnapplied = action;
        return this;
    }

    /**
     * Answer the request {@code REFUSED} if the group has not begun applying it, so it never will (a caller that stopped
     * waiting). @return false once the group has claimed it: it is being, or has been, applied
     */
    public boolean cancel(String why) {
        return cancel(com.telamin.mongoose.control.event.FailureKind.REFUSED_ADMISSION, why);
    }

    /** As {@link #cancel(String)}, classified as {@code kind} (a refusal kind, such as {@code REFUSED_DEADLINE}). */
    public boolean cancel(com.telamin.mongoose.control.event.FailureKind kind, String why) {
        if (!completeUnclaimed(new ControlReply(requestId, com.telamin.mongoose.control.event.ReplyOutcome.REFUSED, why, -1,
                null, kind))) {
            return false;
        }
        runUnapplied();
        return true;
    }

    void runUnapplied() {
        Runnable action = onUnapplied;
        if (action != null) action.run();
    }

    /** The reply, completed exactly once. Callers must not run blocking or re-entrant work on the completing thread. */
    public CompletableFuture<ControlReply> reply() {
        return reply;
    }

    boolean claim() {
        return state.compareAndSet(SENT, CLAIMED);
    }

    boolean isClaimed() {
        return state.get() == CLAIMED;
    }

    boolean isReplied() {
        return state.get() == REPLIED;
    }

    /** Complete an unclaimed request: the only completion a pre-join sweep or a sender may make. */
    boolean completeUnclaimed(ControlReply answer) {
        if (!state.compareAndSet(SENT, REPLIED)) return false;
        finish(answer);
        return true;
    }

    /** Complete a claimed request: the agent's reply, or the post-join cleanup. */
    boolean completeClaimed(ControlReply answer) {
        if (!state.compareAndSet(CLAIMED, REPLIED)) return false;
        finish(answer);
        return true;
    }

    /** Set by the group at send (issue #77, review R3): a cancel or a sweep is marked like any other reply. */
    void markRepliesWith(java.util.function.UnaryOperator<ControlReply> marker) {
        this.replyMarker = marker;
    }

    private void finish(ControlReply answer) {
        java.util.function.UnaryOperator<ControlReply> marker = replyMarker;
        if (marker != null) answer = marker.apply(answer);
        Runnable action = onReply;
        try {
            if (action != null) action.run();
        } finally {
            reply.complete(answer);
        }
    }

    @Override
    public String toString() {
        return "ControlRequest{" + requestId + " " + kind + " " + description + " state=" + state.get() + "}";
    }
}
