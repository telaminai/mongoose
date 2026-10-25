/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control.node;

import com.telamin.fluxtion.runtime.annotations.OnEventHandler;
import com.telamin.fluxtion.runtime.audit.EventLogNode;
import com.telamin.mongoose.control.event.EffectResult;

/**
 * Makes the control audit evidence rather than narration: a rule's {@code Decision} records what it asked for; this
 * node records what actually happened, under one shape, before any rule reacts to it. A reply is an effect too, so
 * the {@code Reply} record is written here when the reply has really been completed.
 */
public class EffectOutcomes extends EventLogNode {

    private final ControlState state;
    private final ControlInputs inputs;

    /** Downstream of {@link ControlInputs}: a completion's {@code Input} is on the record before its outcome. */
    public EffectOutcomes(ControlState state, ControlInputs inputs) {
        this.state = state;
        this.inputs = inputs;
    }

    /**
     * The final outcome of an asynchronous capture effect, as for every synchronous one. A completion for an open
     * already settled, or a close of a resource the table no longer holds, is marked {@code duplicate}: it is not a
     * second outcome of the effect. A timeout is not an outcome of the open.
     */
    @OnEventHandler
    public boolean onCompletion(com.telamin.mongoose.control.event.CaptureCompletion c) {
        if (c.kind() == com.telamin.mongoose.control.event.CaptureCompletion.Kind.TIMED_OUT) {
            // a timeout settles an open still pending: its final outcome, so a later completion is a duplicate
            if (state.pendingOpen(c.opId()) == null) return false;
            ControlAudit.record(auditLog, state, "EffectOutcome", "opId", c.opId(), "effect", "OpenCapture",
                    "outcome", "failed", "reason", "open timed out",
                    "cleanup", state.chain(c.requestId()) == null ? Boolean.TRUE : null);
            return true;
        }
        boolean open = c.kind() == com.telamin.mongoose.control.event.CaptureCompletion.Kind.OPENED
                || c.kind() == com.telamin.mongoose.control.event.CaptureCompletion.Kind.OPEN_FAILED;
        boolean ok = c.kind() == com.telamin.mongoose.control.event.CaptureCompletion.Kind.OPENED
                || c.kind() == com.telamin.mongoose.control.event.CaptureCompletion.Kind.CLOSED;
        boolean duplicate = open ? state.pendingOpen(c.opId()) == null : state.resource(c.resourceId()) == null;
        ControlAudit.record(auditLog, state, "EffectOutcome", "opId", c.opId(),
                "effect", open ? "OpenCapture" : "CloseCapture", "outcome", ok ? "succeeded" : "failed",
                "resourceId", c.resourceId() >= 0 ? c.resourceId() : null, "reason", c.reason(),
                "duplicate", duplicate ? Boolean.TRUE : null,
                "cleanup", state.chain(c.requestId()) == null ? Boolean.TRUE : null);
        return true;
    }

    @OnEventHandler
    public boolean onResult(EffectResult result) {
        state.beginOutcome(result.requestId(), result.opId());
        if ("Reply".equals(result.effect())) {
            ControlAudit.record(auditLog, state, "Reply", "opId", result.opId(), "outcome", result.reason(),
                    "generation", result.generation(), "name", result.name(),
                    "completed", result.succeeded());
        } else {
            ControlAudit.record(auditLog, state, "EffectOutcome", "opId", result.opId(), "effect", result.effect(),
                    "outcome", result.outcome().name().toLowerCase(), "generation", result.generation(),
                    "name", result.name(), "reason", result.reason(),
                    // an effect outcome after its request's reply is cleanup the reply never waited for
                    "cleanup", state.isCleanup(result.opId(), false) || state.chain(result.requestId()) == null
                            ? Boolean.TRUE : null);
        }
        return true;
    }
}
