/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control.node;

import com.telamin.fluxtion.runtime.annotations.OnBatchEnd;
import com.telamin.fluxtion.runtime.annotations.builder.Inject;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.context.DataFlowContext;
import com.telamin.mongoose.control.effect.ControlEffect;
import com.telamin.mongoose.control.effect.EffectAdapter;
import com.telamin.mongoose.control.event.ControlOutcome;
import com.telamin.mongoose.control.event.EffectResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Where rules ask for effects, and where those effects are performed: at Fluxtion's own transaction boundary,
 * {@code batchEnd()}, after the decision cycle's {@code afterEvent()} has published its records. So <b>decided →
 * recorded → acted</b> holds by construction (the analyser's {@code EffectQueue}, adopted).
 *
 * <p>Each result is fed back with {@code processAsNewEventCycle}. During {@code batchEnd()} the processor is
 * {@code processing}, so with re-entrancy on (asserted by {@code ControlProcessorShapeTest}) each result is queued and
 * dispatched after the batch record, as its own audited cycle.
 *
 * <p><b>Nothing may throw out of here.</b> The generated {@code batchEnd()} sets {@code processing = true} with no
 * {@code try}/{@code finally}; a throw would leave the processor wedged. So every effect is caught (a failure becomes a
 * failed result), the re-dispatch itself is guarded, and a {@link VirtualMachineError} or a failed re-dispatch is
 * stashed for the driver to handle once {@code batchEnd()} has returned. The rest of that batch is then abandoned and
 * listed, so each abandoned request still gets a recorded outcome.
 */
public class EffectQueue {

    private transient List<ControlEffect> pending = new ArrayList<>();
    private transient List<ControlEffect> abandoned = new ArrayList<>();
    private transient Throwable fatal;

    @Inject
    private DataFlowContext dataFlowContext;

    private transient EffectAdapter adapter;

    @ServiceRegistered
    public void adapterRegistered(EffectAdapter adapter) {
        this.adapter = adapter;
    }

    /** Called by a rule during dispatch. Appending to a list is not an effect. */
    public void request(ControlEffect effect) {
        pending.add(effect);
    }

    @OnBatchEnd
    public void performRequestedEffects() {
        if (pending.isEmpty() || fatal != null) {
            return;
        }
        List<ControlEffect> batch = new ArrayList<>(pending);
        pending.clear();
        for (int i = 0; i < batch.size(); i++) {
            ControlEffect effect = batch.get(i);
            EffectResult result = perform(effect);
            if (result == null) {
                // this effect included: it stashed the fatal and has no recorded outcome
                abandoned.addAll(batch.subList(i, batch.size()));
                return;
            }
            try {
                dataFlowContext.getEventDispatcher().processAsNewEventCycle(result);
            } catch (Throwable t) {
                fatal = t;
                // this effect included: it was performed, but its result never reached the record
                abandoned.addAll(batch.subList(i, batch.size()));
                return;
            }
        }
    }

    /** @return the result, or {@code null} having stashed a fatal error */
    private EffectResult perform(ControlEffect effect) {
        if (adapter == null) {
            fatal = new IllegalStateException("no EffectAdapter registered: the group must register its adapter "
                    + "before any effect is requested; " + effect.effect() + " could not be performed");
            return null;
        }
        try {
            EffectResult result = adapter.perform(effect);
            if (result == null) {
                return failed(effect, "adapter returned no result: an effect must be answered");
            }
            return result;
        } catch (VirtualMachineError e) {
            fatal = e;
            return null;
        } catch (Throwable t) {
            return failed(effect, t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        }
    }

    private static EffectResult failed(ControlEffect effect, String reason) {
        return new EffectResult(effect.opId(), effect.requestId(), effect.effect(), ControlOutcome.FAILED,
                effect.generation(), effect.name(), reason);
    }

    public boolean isEmpty() {
        return pending.isEmpty();
    }

    /** Taken by the driver after {@code batchEnd()} returns, when {@code processing} is clear again. */
    public Throwable takeFatal() {
        Throwable t = fatal;
        fatal = null;
        return t;
    }

    /** Effects requested but never performed because the batch was abandoned; taken by the driver with the fatal. */
    public List<ControlEffect> takeAbandoned() {
        List<ControlEffect> out = new ArrayList<>(abandoned);
        abandoned.clear();
        out.addAll(pending);
        pending.clear();
        return out;
    }

    public DataFlowContext getDataFlowContext() {
        return dataFlowContext;
    }

    public void setDataFlowContext(DataFlowContext dataFlowContext) {
        this.dataFlowContext = dataFlowContext;
    }
}
