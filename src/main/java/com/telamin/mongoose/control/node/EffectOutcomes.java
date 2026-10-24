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

    public EffectOutcomes(ControlState state) {
        this.state = state;
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
                    "name", result.name(), "reason", result.reason());
        }
        return true;
    }
}
