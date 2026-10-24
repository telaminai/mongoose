/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control.node;

import com.telamin.fluxtion.runtime.annotations.OnEventHandler;
import com.telamin.fluxtion.runtime.audit.EventLogNode;
import com.telamin.mongoose.control.event.AddIntent;
import com.telamin.mongoose.control.event.BroadcastIntent;
import com.telamin.mongoose.control.event.CloseIntent;
import com.telamin.mongoose.control.event.RunIntent;
import com.telamin.mongoose.control.event.StopIntent;

/**
 * The entry for every command: upstream of every rule, so each command's {@code Input} record is written before any
 * rule decides anything about it. It decides nothing itself.
 */
public class ControlInputs extends EventLogNode {

    private final ControlState state;

    public ControlInputs(ControlState state) {
        this.state = state;
    }

    @OnEventHandler
    public boolean onAdd(AddIntent intent) {
        state.beginInput(intent.requestId());
        ControlAudit.record(auditLog, state, "Input", "fact", "Add", "generation", intent.generation(),
                "incarnation", intent.incarnation(), "name", intent.name(), "cancelled", intent.cancelled(),
                "boot", intent.boot(), "legacy", intent.legacy());
        return true;
    }

    @OnEventHandler
    public boolean onStop(StopIntent intent) {
        state.beginInput(intent.requestId());
        ControlAudit.record(auditLog, state, "Input", "fact", "Stop", "generation", intent.generation(),
                "name", intent.name(), "bound", intent.bound(), "addTaken", intent.addTaken());
        return true;
    }

    @OnEventHandler
    public boolean onBroadcast(BroadcastIntent intent) {
        state.beginInput(intent.requestId());
        ControlAudit.record(auditLog, state, "Input", "fact", intent.register() ? "Register" : "Deregister",
                "service", intent.serviceName(), "version", intent.version());
        return true;
    }

    @OnEventHandler
    public boolean onRun(RunIntent intent) {
        state.beginInput(intent.requestId());
        ControlAudit.record(auditLog, state, "Input", "fact", "Run");
        return true;
    }

    @OnEventHandler
    public boolean onClose(CloseIntent intent) {
        state.beginInput(intent.requestId());
        ControlAudit.record(auditLog, state, "Input", "fact", "Close");
        return true;
    }
}
