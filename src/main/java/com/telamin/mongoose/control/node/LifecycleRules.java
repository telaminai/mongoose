/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control.node;

import com.telamin.fluxtion.runtime.annotations.OnEventHandler;
import com.telamin.fluxtion.runtime.audit.EventLogNode;
import com.telamin.mongoose.control.effect.ControlEffect;
import com.telamin.mongoose.control.event.AddIntent;
import com.telamin.mongoose.control.event.CloseIntent;
import com.telamin.mongoose.control.event.EffectResult;
import com.telamin.mongoose.control.event.ReplyOutcome;
import com.telamin.mongoose.control.event.StopIntent;
import com.telamin.mongoose.control.node.ControlState.Chain;
import com.telamin.mongoose.control.node.ControlState.ChainKind;
import com.telamin.mongoose.control.node.ControlState.Lifecycle;
import com.telamin.mongoose.control.node.ControlState.Reg;

import java.util.List;

/**
 * The lifecycle state machine for a group's processors: add, stop and close, and what follows each effect outcome.
 * Each transition is one labelled rule, written as a {@code Decision} record naming the rule, the state before and
 * after, and the effects requested. Effects are only requested here; {@link EffectQueue} performs them at batch end,
 * and their results come back through {@link #onResult}.
 *
 * <p>Downstream of {@link ControlInputs} and {@link EffectOutcomes}, so the input or outcome is on the record before
 * any rule fires.
 */
public class LifecycleRules extends EventLogNode {

    private final ControlInputs inputs;
    private final EffectOutcomes outcomes;
    private final ControlState state;
    private final EffectQueue effects;

    public LifecycleRules(ControlInputs inputs, EffectOutcomes outcomes, ControlState state, EffectQueue effects) {
        this.inputs = inputs;
        this.outcomes = outcomes;
        this.state = state;
        this.effects = effects;
    }

    // ------------------------------------------------------------------ add

    @OnEventHandler
    public boolean onAdd(AddIntent intent) {
        long req = intent.requestId();
        if (intent.cancelled()) {
            ControlEffect reply = reply(req, intent.generation(), intent.name(), ReplyOutcome.REFUSED, "cancelled");
            decide("addCancelled", intent.generation(), intent.name(), "Admitted", "Cancelled", reply);
            return true;
        }
        if (state.isClosing()) {
            // queued before the close: nothing new starts in a closing group and its supplier never runs; its name is
            // released before it is answered, so it does not outlive the refusal
            state.openChain(req, ChainKind.REFUSED_ADD);
            ControlEffect free = new ControlEffect.ReleaseName(state.nextOp(req), req, intent.generation(), intent.name());
            decide("addWhileClosing", intent.generation(), intent.name(), "Admitted", "Refused", free);
            return true;
        }
        Reg reg = state.admit(intent.generation(), intent.incarnation(), intent.name(), req);
        reg.lifecycle = Lifecycle.STARTING;
        Chain chain = state.openChain(req, ChainKind.ADD);
        chain.failBoot = intent.boot() && intent.failBootOnStartFailure();
        ControlEffect start = new ControlEffect.BuildAndStart(state.nextOp(req), req, intent.generation(), intent.name());
        decide("addAdmitted", intent.generation(), intent.name(), "Admitted", "Starting", start);
        return true;
    }

    // ------------------------------------------------------------------ stop

    @OnEventHandler
    public boolean onStop(StopIntent intent) {
        long req = intent.requestId();
        // a stop bound to a registration addresses that generation only; one that only names a processor addresses
        // whichever registration answers to the name now
        Reg reg = intent.bound() ? state.registration(intent.generation()) : state.registration(intent.name());
        if (reg == null) {
            if (!intent.bound()) {
                ControlEffect reply = reply(req, intent.generation(), intent.name(), ReplyOutcome.REFUSED,
                        "no such processor");
                decide("stopUnknown", intent.generation(), intent.name(), "Absent", "Absent", reply);
            } else if (!intent.addTaken()) {
                // the stop overtook its add: cancel the registration, so the add is skipped when it arrives, and free
                // the name now
                state.openChain(req, ChainKind.CANCEL);
                ControlEffect cancel = new ControlEffect.CancelRegistration(state.nextOp(req), req, intent.generation(),
                        intent.name());
                decide("stopBeforeAdd", intent.generation(), intent.name(), "Admitted", "Cancelled", cancel);
            } else {
                ControlEffect reply = reply(req, intent.generation(), intent.name(), ReplyOutcome.APPLIED,
                        "alreadyStopped");
                decide("stopReleased", intent.generation(), intent.name(), "Released", "Released", reply);
            }
            return true;
        }
        switch (reg.lifecycle) {
            case STARTED -> {
                reg.lifecycle = Lifecycle.RELEASING;
                state.openChain(req, ChainKind.STOP);
                ControlEffect release = new ControlEffect.Release(state.nextOp(req), req, reg.generation, reg.name, true);
                decide("stopStarted", reg.generation, reg.name, "Started", "Releasing", release);
            }
            case RELEASING -> {
                ControlEffect reply = reply(req, reg.generation, reg.name, ReplyOutcome.APPLIED, "releasing");
                decide("stopWhileReleasing", reg.generation, reg.name, "Releasing", "Releasing", reply);
            }
            default -> {
                // STARTING cannot be seen between commands: an add settles within its own drain step
                ControlEffect reply = reply(req, reg.generation, reg.name, ReplyOutcome.APPLIED, "alreadyStopped");
                decide("stopReleased", reg.generation, reg.name, reg.lifecycle.name(), reg.lifecycle.name(), reply);
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ close

    @OnEventHandler
    public boolean onClose(CloseIntent intent) {
        long req = intent.requestId();
        state.closing();
        List<Reg> started = state.started();
        if (started.isEmpty()) {
            ControlEffect reply = reply(req, -1, null, ReplyOutcome.APPLIED, "nothing running");
            decide("closeEmpty", -1, null, "Running", "Closed", reply);
            return true;
        }
        Chain chain = state.openChain(req, ChainKind.CLOSE);
        ControlEffect[] releases = new ControlEffect[started.size()];
        for (int i = 0; i < started.size(); i++) {
            Reg reg = started.get(i);
            reg.lifecycle = Lifecycle.RELEASING;
            releases[i] = new ControlEffect.Release(state.nextOp(req), req, reg.generation, reg.name, true);
        }
        chain.outstanding = releases.length;
        decide("closeGroup", -1, null, "Running", "Closing", releases);
        return true;
    }

    // ------------------------------------------------------------------ outcomes

    @OnEventHandler
    public boolean onResult(EffectResult result) {
        if ("Reply".equals(result.effect())) {
            // the request is answered: its bookkeeping is released, so the state stays bounded
            state.closeChain(result.requestId());
            return false;
        }
        Chain chain = state.chain(result.requestId());
        if (chain == null) {
            return false;
        }
        long req = result.requestId();
        switch (result.effect()) {
            case "BuildAndStart" -> started(chain, result);
            case "Release" -> released(chain, result);
            case "CancelRegistration" -> {
                ControlEffect free = new ControlEffect.ReleaseName(state.nextOp(req), req, result.generation(),
                        result.name());
                decide("cancelled", result.generation(), result.name(), "Cancelled", "Cancelled", free);
            }
            case "ReleaseName" -> nameReleased(chain, result);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void started(Chain chain, EffectResult result) {
        long req = result.requestId();
        Reg reg = state.registration(result.generation());
        if (result.succeeded()) {
            if (reg != null) {
                reg.lifecycle = Lifecycle.STARTED;
                if (reg.name == null) reg.name = result.name();   // an add without admission: named once built
            }
            ControlEffect reply = reply(req, result.generation(), result.name(), ReplyOutcome.APPLIED, "started");
            decide("started", result.generation(), result.name(), "Starting", "Started", reply);
            return;
        }
        // the processor is dropped: released without a stop(), then its name freed, then the add is answered Failed
        if (reg != null) reg.lifecycle = Lifecycle.FAILED;
        chain.failure = result.reason();
        ControlEffect release = new ControlEffect.Release(state.nextOp(req), req, result.generation(), result.name(),
                false);
        // at boot under the fail-fast policy the group's start fails; otherwise the failure is reported and the group
        // carries on
        ControlEffect consequence = chain.failBoot
                ? new ControlEffect.FailBoot(state.nextOp(req), req, result.generation(), result.name(), result.reason())
                : new ControlEffect.ReportFailure(state.nextOp(req), req, result.generation(), result.name(),
                "failed to start, dropped from the group");
        decide(chain.failBoot ? "bootStartFailed" : "startFailed", result.generation(), result.name(), "Starting",
                "Failed", consequence, release);
    }

    private void released(Chain chain, EffectResult result) {
        long req = result.requestId();
        Reg reg = state.registration(result.generation());
        String from = reg == null ? "Releasing" : capital(reg.lifecycle.name());
        if (reg != null && reg.lifecycle != Lifecycle.FAILED) reg.lifecycle = Lifecycle.RELEASED;
        ControlEffect free = new ControlEffect.ReleaseName(state.nextOp(req), req, result.generation(), result.name());
        String to = reg != null && reg.lifecycle == Lifecycle.FAILED ? "Failed" : "Released";
        // a release whose stop or tearDown threw still leaves the group holding nothing for the processor: the name is
        // freed (as 1.0.33 does); only a fatal error mid-release leaves the group dead, with its names held
        boolean clean = result.succeeded() && result.reason() == null;
        decide(clean ? "released" : "releasedWithErrors", result.generation(), result.name(), from, to, free);
    }

    private void nameReleased(Chain chain, EffectResult result) {
        long req = result.requestId();
        state.forget(result.generation());
        switch (chain.kind) {
            case CLOSE -> {
                if (--chain.outstanding > 0) {
                    decide("nameReleased", result.generation(), result.name(), "Released", "Released");
                    return;
                }
                ControlEffect reply = reply(req, -1, null, ReplyOutcome.APPLIED, "closed");
                decide("groupClosed", result.generation(), result.name(), "Closing", "Closed", reply);
            }
            case ADD -> {
                // an add whose start failed: answered Failed now that everything it held is released
                ControlEffect reply = reply(req, result.generation(), result.name(), ReplyOutcome.FAILED,
                        chain.failure == null ? "start failed" : chain.failure);
                decide("dropped", result.generation(), result.name(), "Failed", "Failed", reply);
            }
            case CANCEL -> {
                ControlEffect reply = reply(req, result.generation(), result.name(), ReplyOutcome.APPLIED, "cancelled");
                decide("cancelComplete", result.generation(), result.name(), "Cancelled", "Cancelled", reply);
            }
            case REFUSED_ADD -> {
                ControlEffect reply = reply(req, result.generation(), result.name(), ReplyOutcome.NOT_RUNNING, "group closed");
                decide("addRefused", result.generation(), result.name(), "Refused", "Refused", reply);
            }
            default -> {
                ControlEffect reply = reply(req, result.generation(), result.name(), ReplyOutcome.APPLIED, "stopped");
                decide("stopComplete", result.generation(), result.name(), "Released", "Released", reply);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private ControlEffect reply(long req, long generation, String name, ReplyOutcome outcome, String reason) {
        return new ControlEffect.Reply(state.nextOp(req), req, generation, name, outcome, reason);
    }

    private void decide(String rule, long generation, String name, String from, String to, ControlEffect... requested) {
        StringBuilder list = new StringBuilder();
        for (ControlEffect e : requested) {
            if (list.length() > 0) list.append(';');
            list.append(e.opId()).append(' ').append(e.effect()).append('(').append(e.generation()).append(')');
        }
        ControlAudit.record(auditLog, state, "Decision", "rule", rule, "generation", generation, "name", name,
                "from", from, "to", to, "effects", list.toString());
        for (ControlEffect e : requested) {
            effects.request(e);
        }
    }

    private static String capital(String upper) {
        return upper.charAt(0) + upper.substring(1).toLowerCase();
    }
}
