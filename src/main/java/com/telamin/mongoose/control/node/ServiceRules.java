/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control.node;

import com.telamin.fluxtion.runtime.annotations.OnEventHandler;
import com.telamin.fluxtion.runtime.audit.EventLogNode;
import com.telamin.mongoose.control.effect.ControlEffect;
import com.telamin.mongoose.control.event.BroadcastIntent;
import com.telamin.mongoose.control.event.EffectResult;
import com.telamin.mongoose.control.event.ReplyOutcome;
import com.telamin.mongoose.control.event.RunIntent;
import com.telamin.mongoose.control.node.ControlState.Chain;
import com.telamin.mongoose.control.node.ControlState.ChainKind;
import com.telamin.mongoose.control.node.ControlState.Reg;

import java.util.List;

/**
 * Service broadcasts and the deprecated run-on-agent-thread task. A broadcast is delivered once to every started
 * processor; one that throws is reported and counted as delivered, and the reply lists the failures.
 */
public class ServiceRules extends EventLogNode {

    private final ControlInputs inputs;
    private final EffectOutcomes outcomes;
    private final ControlState state;
    private final EffectQueue effects;

    public ServiceRules(ControlInputs inputs, EffectOutcomes outcomes, ControlState state, EffectQueue effects) {
        this.inputs = inputs;
        this.outcomes = outcomes;
        this.state = state;
        this.effects = effects;
    }

    @OnEventHandler
    public boolean onBroadcast(BroadcastIntent intent) {
        long req = intent.requestId();
        List<Reg> started = state.started();
        String rule = intent.register() ? "registerService" : "deregisterService";
        if (state.isClosing()) {
            ControlEffect reply = new ControlEffect.Reply(state.nextOp(req), req, -1, null, ReplyOutcome.NOT_RUNNING,
                    "group closed");
            decide(rule + "WhileClosing", intent.serviceName(), reply);
            return true;
        }
        if (!state.applyServiceVersion(intent.serviceName(), intent.version())) {
            ControlEffect reply = new ControlEffect.Reply(state.nextOp(req), req, -1, null, ReplyOutcome.APPLIED,
                    "ignored: stale version " + intent.version());
            decide(rule + "Stale", intent.serviceName(), reply);
            return true;
        }
        if (started.isEmpty()) {
            ControlEffect reply = new ControlEffect.Reply(state.nextOp(req), req, -1, null, ReplyOutcome.APPLIED,
                    "no running processors");
            decide(rule, intent.serviceName(), reply);
            return true;
        }
        Chain chain = state.openChain(req, ChainKind.BROADCAST);
        chain.outstanding = started.size();
        ControlEffect[] deliveries = new ControlEffect[started.size()];
        for (int i = 0; i < started.size(); i++) {
            Reg reg = started.get(i);
            deliveries[i] = new ControlEffect.DeliverService(state.nextOp(req), req, reg.generation, reg.name,
                    intent.serviceName(), intent.register());
        }
        decide(rule, intent.serviceName(), deliveries);
        return true;
    }

    @OnEventHandler
    public boolean onRun(RunIntent intent) {
        long req = intent.requestId();
        state.openChain(req, ChainKind.RUN);
        decide("runTask", null, new ControlEffect.RunTask(state.nextOp(req), req, -1, null));
        return true;
    }

    @OnEventHandler
    public boolean onResult(EffectResult result) {
        long req = result.requestId();
        Chain chain = state.chain(req);
        if (chain == null) {
            return false;
        }
        switch (result.effect()) {
            case "DeliverService" -> {
                if (!result.succeeded()) {
                    chain.failure = (chain.failure == null ? "" : chain.failure + "; ") + result.name() + ": "
                            + result.reason();
                }
                if (--chain.outstanding > 0) {
                    return true;
                }
                ControlEffect reply = new ControlEffect.Reply(state.nextOp(req), req, -1, null,
                        chain.failure == null ? ReplyOutcome.APPLIED : ReplyOutcome.FAILED,
                        chain.failure == null ? "delivered" : "delivered with failures: " + chain.failure);
                decide("broadcastComplete", null, reply);
            }
            case "RunTask" -> {
                ControlEffect reply = new ControlEffect.Reply(state.nextOp(req), req, -1, null,
                        result.succeeded() ? ReplyOutcome.APPLIED : ReplyOutcome.FAILED,
                        result.succeeded() ? "ran" : result.reason());
                decide("taskComplete", null, reply);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void decide(String rule, String service, ControlEffect... requested) {
        StringBuilder list = new StringBuilder();
        for (ControlEffect e : requested) {
            if (list.length() > 0) list.append(';');
            list.append(e.opId()).append(' ').append(e.effect()).append('(').append(e.generation()).append(')');
        }
        ControlAudit.record(auditLog, state, "Decision", "rule", rule, "service", service, "effects", list.toString());
        for (ControlEffect e : requested) {
            effects.request(e);
        }
    }
}
