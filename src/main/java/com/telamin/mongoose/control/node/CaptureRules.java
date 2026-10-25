/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control.node;

import com.telamin.fluxtion.runtime.annotations.OnEventHandler;
import com.telamin.fluxtion.runtime.audit.EventLogNode;
import com.telamin.mongoose.control.effect.ControlEffect;
import com.telamin.mongoose.control.event.AuditStartIntent;
import com.telamin.mongoose.control.event.AuditStopIntent;
import com.telamin.mongoose.control.event.CaptureCompletion;
import com.telamin.mongoose.control.event.EffectResult;
import com.telamin.mongoose.control.event.ReplyOutcome;
import com.telamin.mongoose.control.node.ControlState.Capture;
import com.telamin.mongoose.control.node.ControlState.Chain;
import com.telamin.mongoose.control.node.ControlState.ChainKind;
import com.telamin.mongoose.control.node.ControlState.Lifecycle;
import com.telamin.mongoose.control.node.ControlState.PendingOpen;
import com.telamin.mongoose.control.node.ControlState.Reg;
import com.telamin.mongoose.control.node.ControlState.Resource;
import com.telamin.mongoose.control.node.ControlState.ResourceState;

import java.util.ArrayList;
import java.util.List;

/**
 * Audit capture as effects (design "Audit capture"): a registration's capture moves {@code Off → Opening → Recording →
 * Closing → Off}; opening and closing are asynchronous effects whose completions come back as inputs, and every
 * completion is classified against the registration and the resource table before anything is installed:
 * <ul>
 *   <li><b>current</b>: the registration is Started and its capture is {@code Opening(opId)}: the capture is installed;</li>
 *   <li><b>stale</b>: the open was cancelled or timed out, or its registration released or replaced: the resource is
 *   closed, never installed;</li>
 *   <li><b>duplicate</b>: a completion for an open already settled: no effect;</li>
 *   <li><b>unknown</b>: nothing remembers the open: recorded, and a resource it carries that the table does not hold is
 *   closed.</li>
 * </ul>
 * Completions are matched on {@code opId} and {@code generation}, never on the processor's name, so a late open never
 * reaches a replacement registered under the same name.
 *
 * <p>Upstream of {@link LifecycleRules}, which asks {@link #releaseCapture} for the capture effects of a stop or close.
 */
public class CaptureRules extends EventLogNode {

    private final ControlInputs inputs;
    private final EffectOutcomes outcomes;
    private final ControlState state;
    private final EffectQueue effects;

    public CaptureRules(ControlInputs inputs, EffectOutcomes outcomes, ControlState state, EffectQueue effects) {
        this.inputs = inputs;
        this.outcomes = outcomes;
        this.state = state;
        this.effects = effects;
    }

    // ------------------------------------------------------------------ commands
    //
    // One record per node per event cycle: a Fluxtion node's log is one entry per cycle, so a second record would
    // overwrite the first. Every rule here writes exactly one Decision, carrying its notes (pending cleanup, cleared,
    // anomaly) as fields, and the replies to waiting audit starts are effects of that decision.

    @OnEventHandler
    public boolean onAuditStart(AuditStartIntent intent) {
        long req = intent.requestId();
        if (state.isClosing()) {
            decide("auditStartWhileClosing", intent.generation(), intent.name(), null, null, null,
                    reply(req, intent.generation(), intent.name(), ReplyOutcome.NOT_RUNNING, "group closed"));
            return true;
        }
        Reg reg = intent.bound() ? state.registration(intent.generation()) : state.registration(intent.name());
        if (reg == null || reg.lifecycle != Lifecycle.STARTED) {
            boolean starting = reg != null && reg.lifecycle == Lifecycle.STARTING;
            decide(starting ? "auditStartNotStarted" : "auditStartUnknown", intent.generation(), intent.name(), null,
                    null, null, reply(req, intent.generation(), intent.name(), ReplyOutcome.REFUSED,
                            starting ? "not started yet" : "no such processor"));
            return true;
        }
        switch (reg.capture) {
            case RECORDING -> decide("auditStartRecording", reg.generation, reg.name, "Recording", "Recording", null,
                    reply(req, reg.generation, reg.name, ReplyOutcome.APPLIED, "already recording"));
            case OPENING -> {
                // joins the open in progress: answered with it
                state.openChain(req, ChainKind.AUDIT_START);
                reg.captureWaiters.add(req);
                decide("auditStartJoinsOpen", reg.generation, reg.name, "Opening", "Opening", null);
            }
            default -> {
                // Off, or Closing: the previous resource is with the closer and closes on its own, owned by nothing
                String from = reg.capture == Capture.CLOSING ? "Closing" : "Off";
                if (reg.capture == Capture.CLOSING) {
                    Resource previous = state.resource(reg.captureResource);
                    if (previous != null) previous.owner = -1;
                    reg.captureResource = -1;
                }
                state.openChain(req, ChainKind.AUDIT_START);
                String op = state.nextOp(req);
                state.retain(req);
                state.addPendingOpen(op, reg.generation, req);
                reg.capture = Capture.OPENING;
                reg.captureOp = op;
                reg.captureWaiters.add(req);
                decide("auditStartOnStarted", reg.generation, reg.name, from, "Opening", null,
                        new ControlEffect.OpenCapture(op, req, reg.generation, reg.name));
            }
        }
        return true;
    }

    @OnEventHandler
    public boolean onAuditStop(AuditStopIntent intent) {
        long req = intent.requestId();
        if (state.isClosing()) {
            decide("auditStopWhileClosing", intent.generation(), intent.name(), null, null, null,
                    reply(req, intent.generation(), intent.name(), ReplyOutcome.NOT_RUNNING, "group closed"));
            return true;
        }
        Reg reg = intent.bound() ? state.registration(intent.generation()) : state.registration(intent.name());
        if (reg == null || reg.lifecycle != Lifecycle.STARTED || reg.capture == Capture.OFF) {
            long gen = reg == null ? intent.generation() : reg.generation;
            decide("auditStopNotRecording", gen, intent.name(), null, null, null,
                    reply(req, gen, intent.name(), ReplyOutcome.APPLIED, "not recording"));
            return true;
        }
        switch (reg.capture) {
            case OPENING -> {
                String open = reg.captureOp;
                List<ControlEffect> requested = cancelOpen(reg, "cancelled by an audit stop");
                requested.add(reply(req, reg.generation, reg.name, ReplyOutcome.APPLIED, "open cancelled"));
                decide("auditStopCancelsOpen", reg.generation, reg.name, "Opening", "Off", note("pendingCleanup", open),
                        requested.toArray(new ControlEffect[0]));
            }
            case CLOSING -> decide("auditStopWhileCaptureClosing", reg.generation, reg.name, "Closing", "Closing", null,
                    reply(req, reg.generation, reg.name, ReplyOutcome.APPLIED, "closing"));
            default -> {
                state.openChain(req, ChainKind.AUDIT_STOP);
                reg.capture = Capture.CLOSING;
                Resource r = state.resource(reg.captureResource);
                if (r != null) r.state = ResourceState.CLOSING;
                decide("auditStopRecording", reg.generation, reg.name, "Recording", "Closing", null,
                        tracked(new ControlEffect.UninstallCapture(state.nextOp(req), req, reg.generation, reg.name,
                                reg.captureResource)));
            }
        }
        return true;
    }

    /**
     * The capture effects of releasing {@code reg} under request {@code req} (a stop, or the group's close), for
     * {@link LifecycleRules} to put in its own {@code Decision} ahead of the release: a recording capture is uninstalled
     * and handed to the closer; an open in progress is cancelled and its waiters answered now, its completion
     * compensated when it comes. The release never waits for either. The ops left outstanding are added to
     * {@code pendingCleanup}.
     */
    public List<ControlEffect> releaseCapture(Reg reg, long req, List<String> pendingCleanup) {
        List<ControlEffect> out = new ArrayList<>();
        switch (reg.capture) {
            case RECORDING -> {
                reg.capture = Capture.CLOSING;
                Resource r = state.resource(reg.captureResource);
                if (r != null) r.state = ResourceState.CLOSING;
                out.add(tracked(new ControlEffect.UninstallCapture(state.nextOp(req), req, reg.generation, reg.name,
                        reg.captureResource)));
                String close = state.nextOp(req);
                state.retain(req);
                out.add(tracked(new ControlEffect.CloseCapture(close, req, reg.generation, reg.name, reg.captureResource)));
                reg.captureCloseOp = close;
            }
            case OPENING -> {
                pendingCleanup.add(reg.captureOp);
                out.addAll(cancelOpen(reg, "generation " + reg.generation + " released before the capture opened"));
            }
            default -> {
                // OFF or CLOSING: earlier closes are enumerated below.
            }
        }
        // Resource entries outlive the latest capture sub-state and are removed on close completion.
        // This covers every old close even while a successor is Recording, Opening or already Off.
        pendingCleanup.addAll(state.pendingCaptureCloses(reg.generation));
        return out;
    }

    /** Cancel the open in progress: capture → Off; returns the Refused replies to its waiters, sent at once. */
    private List<ControlEffect> cancelOpen(Reg reg, String why) {
        PendingOpen open = state.pendingOpen(reg.captureOp);
        if (open != null) open.settled = true;
        reg.capture = Capture.OFF;
        reg.captureOp = null;
        return answerWaiters(reg, ReplyOutcome.REFUSED, why);
    }

    /** The replies to every audit start waiting on {@code reg}'s open; each op id names the request it answers. */
    private List<ControlEffect> answerWaiters(Reg reg, ReplyOutcome outcome, String why) {
        List<ControlEffect> replies = new ArrayList<>();
        for (long waiter : reg.captureWaiters) replies.add(reply(waiter, reg.generation, reg.name, outcome, why));
        reg.captureWaiters.clear();
        return replies;
    }

    // ------------------------------------------------------------------ completions

    @OnEventHandler
    public boolean onCompletion(CaptureCompletion c) {
        switch (c.kind()) {
            case OPENED, OPEN_FAILED -> openCompleted(c);
            case CLOSED, CLOSE_FAILED -> closeCompleted(c);
            case TIMED_OUT -> openTimedOut(c);
        }
        return true;
    }

    private void openCompleted(CaptureCompletion c) {
        PendingOpen open = state.settleOpen(c.opId());
        try {
            openCompleted(c, open);
        } finally {
            // after any follow-up effect is requested: its op id continues the root's sequence, never restarts it
            if (open != null) state.release(open.rootId);
        }
    }

    private void openCompleted(CaptureCompletion c, PendingOpen open) {
        boolean opened = c.kind() == CaptureCompletion.Kind.OPENED;
        if (open == null) {
            boolean duplicate = state.wasSettled(c.opId());
            // a duplicate of an accepted open never closes the live capture; a resource nothing holds is closed
            List<ControlEffect> close = new ArrayList<>();
            if (opened && c.resourceId() >= 0 && state.resource(c.resourceId()) == null) {
                String op = state.nextCompensationOp(c.requestId());
                state.retain(c.requestId());
                // held by nothing, closing: so its close completes against the table like any other
                state.addResource(c.resourceId(), -1, ResourceState.CLOSING, c.opId(), -1, null);
                state.markCleanup(op);
                close.add(tracked(new ControlEffect.CloseCapture(op, c.requestId(), -1, null, c.resourceId())));
            }
            decideCompletion(duplicate ? "captureDuplicate" : "captureUnknown", -1, null, c.resourceId(),
                    duplicate ? note("duplicate", true) : note("anomaly", "completion for an open nothing remembers"),
                    close.toArray(new ControlEffect[0]));
            return;
        }
        long root = open.rootId;
        Reg reg = state.registration(open.generation);
        boolean current = reg != null && reg.lifecycle == Lifecycle.STARTED && reg.capture == Capture.OPENING
                && c.opId().equals(reg.captureOp);
        String name = reg == null ? null : reg.name;
        if (!opened) {
            if (current) {
                reg.capture = Capture.OFF;
                reg.captureOp = null;
                decideCompletion("captureOpenFailed", open.generation, name, -1, null,
                        answerWaiters(reg, ReplyOutcome.FAILED, "open failed: " + c.reason()).toArray(new ControlEffect[0]));
            } else {
                decideCompletion("captureOpenFailedStale", open.generation, name, -1,
                        note("stale", true, "cleared", c.opId()));
            }
            return;
        }
        if (current) {
            state.addResource(c.resourceId(), open.generation, ResourceState.OPEN, c.opId(), open.generation, name);
            reg.captureResource = c.resourceId();
            decideCompletion("captureOpened", open.generation, name, c.resourceId(), null,
                    tracked(new ControlEffect.InstallCapture(state.nextOp(root), root, open.generation, name, c.resourceId())));
            return;
        }
        // stale: the resource enters the table with no owner and is closed, never installed
        state.addResource(c.resourceId(), -1, ResourceState.CLOSING, c.opId(), open.generation, name);
        decideCompletion("captureOpenedStale", open.generation, name, c.resourceId(), note("stale", true),
                closeEffect(root, open.generation, name, c.resourceId()));
    }

    private void closeCompleted(CaptureCompletion c) {
        boolean ok = c.kind() == CaptureCompletion.Kind.CLOSED;
        state.takeEffectResource(c.opId());
        state.isCleanup(c.opId(), true);
        Resource r = state.removeResource(c.resourceId());
        if (r == null) {
            decideCompletion("captureCloseUnknown", -1, null, c.resourceId(), note("duplicate", true));
            return;
        }
        state.release(c.requestId());
        Reg reg = r.owner < 0 ? null : state.registration(r.owner);
        if (reg != null && reg.capture == Capture.CLOSING && reg.captureResource == r.id) {
            reg.capture = Capture.OFF;
            reg.captureResource = -1;
        }
        // a resource disowned by a later open still belongs to the generation it was opened for: its close is recorded
        // against that generation and processor, and clears that registration's outstanding close (FM-3)
        Reg openedFor = reg != null ? reg : r.openedFor < 0 ? null : state.registration(r.openedFor);
        if (openedFor != null && c.opId().equals(openedFor.captureCloseOp)) openedFor.captureCloseOp = null;
        long generation = r.owner >= 0 ? r.owner : r.openedFor;
        String name = reg != null ? reg.name : r.openedName;
        // a failed physical close is recorded and counted, and never blocks anything
        decideCompletion(ok ? "captureClosed" : "captureCloseFailed", generation, name, c.resourceId(),
                note("cleared", c.opId()));
    }

    /**
     * The open's timer fired before its completion: the open is settled now (its final outcome, failed, is recorded by
     * {@link EffectOutcomes}), so a helper that never returns leaks no bookkeeping; a completion arriving later is a
     * duplicate, and a resource it carries is closed (final review, finding 8).
     */
    private void openTimedOut(CaptureCompletion c) {
        PendingOpen open = state.settleOpen(c.opId());
        if (open == null) {
            decideCompletion("captureTimeoutIgnored", -1, null, -1, null);
            return;
        }
        try {
            Reg reg = state.registration(open.generation);
            boolean current = reg != null && reg.capture == Capture.OPENING && c.opId().equals(reg.captureOp);
            if (!current) {
                decideCompletion("captureOpenTimedOutAfterCancel", open.generation, reg == null ? null : reg.name, -1,
                        note("cleared", c.opId()));
                return;
            }
            reg.capture = Capture.OFF;
            reg.captureOp = null;
            decideCompletion("captureOpenTimedOut", reg.generation, reg.name, -1, null,
                    answerWaiters(reg, ReplyOutcome.FAILED, "open timed out").toArray(new ControlEffect[0]));
        } finally {
            state.release(open.rootId);
        }
    }

    // ------------------------------------------------------------------ outcomes of synchronous capture effects

    @OnEventHandler
    public boolean onResult(EffectResult result) {
        long req = result.requestId();
        if (result.outcome() == com.telamin.mongoose.control.event.ControlOutcome.PENDING) {
            return false;
        }
        long res = state.takeEffectResource(result.opId());
        switch (result.effect()) {
            case "OpenCapture" -> {
                // the open could not even be handed to a helper: no completion will come
                PendingOpen open = state.settleOpen(result.opId());
                if (open != null) state.release(open.rootId);
                Reg reg = state.registration(result.generation());
                if (reg != null && result.opId().equals(reg.captureOp)) {
                    reg.capture = Capture.OFF;
                    reg.captureOp = null;
                    decide("captureOpenNotStarted", reg.generation, reg.name, "Opening", "Off", null,
                            answerWaiters(reg, ReplyOutcome.FAILED, "open failed: " + result.reason())
                                    .toArray(new ControlEffect[0]));
                }
                return true;
            }
            case "InstallCapture" -> {
                Reg reg = state.registration(result.generation());
                Resource r = state.resource(res);
                if (result.succeeded() && reg != null && reg.capture == Capture.OPENING) {
                    reg.capture = Capture.RECORDING;
                    reg.captureOp = null;
                    if (r != null) r.state = ResourceState.INSTALLED;
                    decide("captureInstalled", reg.generation, reg.name, "Opening", "Recording", null,
                            answerWaiters(reg, ReplyOutcome.APPLIED, "recording").toArray(new ControlEffect[0]));
                    return true;
                }
                // not installed: compensate, and answer the waiters Failed
                if (r != null) {
                    r.owner = -1;
                    r.state = ResourceState.CLOSING;
                }
                List<ControlEffect> requested = new ArrayList<>();
                requested.add(closeEffect(req, result.generation(), result.name(), res));
                if (reg != null) {
                    reg.capture = Capture.OFF;
                    reg.captureOp = null;
                    reg.captureResource = -1;
                    requested.addAll(answerWaiters(reg, ReplyOutcome.FAILED, "install failed: " + result.reason()));
                }
                decide("captureInstallFailed", result.generation(), result.name(), "Opening", "Off", null,
                        requested.toArray(new ControlEffect[0]));
                return true;
            }
            case "UninstallCapture" -> {
                Chain chain = state.chain(req);
                if (chain == null || chain.kind != ChainKind.AUDIT_STOP) {
                    if (chain != null && !result.succeeded()) {
                        chain.failure = "processor released, but capture uninstall failed: " + result.reason();
                    }
                    return false;   // part of a release: its close follows the release
                }
                if (!result.succeeded()) {
                    // still installed: never close a resource that may be receiving records (review of PR 5, finding 2)
                    Reg reg = state.registration(result.generation());
                    Resource r = state.resource(res);
                    if (reg != null && reg.captureResource == res) reg.capture = Capture.RECORDING;
                    if (r != null) r.state = ResourceState.INSTALLED;
                    decide("captureUninstallFailed", result.generation(), result.name(), "Closing", "Recording", null,
                            reply(req, result.generation(), result.name(), ReplyOutcome.FAILED,
                                    "uninstall failed: " + result.reason()));
                    return true;
                }
                ControlEffect close = closeEffect(req, result.generation(), result.name(), res);
                Reg owner = state.registration(result.generation());
                if (owner != null) owner.captureCloseOp = close.opId();
                ControlEffect answer = reply(req, result.generation(), result.name(), ReplyOutcome.APPLIED, "stopped");
                decide("captureUninstalled", result.generation(), result.name(), "Closing", "Closing", null, close, answer);
                return true;
            }
            case "CloseCapture" -> {
                // refused before reaching the closer (an unknown resource): no completion will come
                state.release(req);
                Resource r = state.removeResource(res);
                Reg reg = r == null || r.owner < 0 ? null : state.registration(r.owner);
                if (reg != null && reg.captureResource == r.id) {
                    reg.capture = Capture.OFF;
                    reg.captureResource = -1;
                }
                decideCompletion("captureCloseRefused", result.generation(), result.name(), res, note("closed", false));
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private ControlEffect closeEffect(long root, long generation, String name, long resourceId) {
        String op = state.nextOp(root);
        state.retain(root);
        if (state.chain(root) == null) state.markCleanup(op);
        return tracked(new ControlEffect.CloseCapture(op, root, generation, name, resourceId));
    }

    /** Remember which resource a capture effect acts on, for when its result comes back. */
    private ControlEffect tracked(ControlEffect effect) {
        long resourceId = switch (effect) {
            case ControlEffect.InstallCapture e -> e.resourceId();
            case ControlEffect.UninstallCapture e -> e.resourceId();
            case ControlEffect.CloseCapture e -> e.resourceId();
            default -> -1;
        };
        state.trackEffectResource(effect.opId(), resourceId);
        if (effect instanceof ControlEffect.CloseCapture) {
            Resource resource = state.resource(resourceId);
            if (resource != null) resource.closeOp = effect.opId();
        }
        return effect;
    }

    private ControlEffect reply(long req, long generation, String name, ReplyOutcome outcome, String reason) {
        return new ControlEffect.Reply(state.nextOp(req), req, generation, name, outcome, reason);
    }

    /** Extra fields for a decision, as alternating key, value pairs. */
    private static Object[] note(Object... fields) {
        return fields;
    }

    private void decide(String rule, long generation, String name, String from, String to, Object[] notes,
                        ControlEffect... requested) {
        record(rule, generation, name, from == null ? null : from + "->" + to, -1, notes, requested);
    }

    /** A decision on a completion, marked cleanup when its root has been answered already. */
    private void decideCompletion(String rule, long generation, String name, long resourceId, Object[] notes,
                                  ControlEffect... requested) {
        record(rule, generation, name, null, resourceId, notes, requested);
    }

    private void record(String rule, long generation, String name, String capture, long resourceId, Object[] notes,
                        ControlEffect... requested) {
        List<Object> fields = new ArrayList<>(List.of("rule", rule, "generation", generation));
        if (name != null) fields.addAll(List.of("name", name));
        if (capture != null) fields.addAll(List.of("capture", capture));
        if (resourceId >= 0) fields.addAll(List.of("resourceId", resourceId));
        if (notes != null) fields.addAll(List.of(notes));
        Boolean cleanup = cleanup();
        if (cleanup != null) fields.addAll(List.of("cleanup", cleanup));
        fields.addAll(List.of("effects", effectList(requested)));
        ControlAudit.record(auditLog, state, "Decision", fields.toArray());
        for (ControlEffect e : requested) effects.request(e);
    }

    /**
     * True for a record written after its request's reply (compensation, a late completion): the reply never waited for
     * it, so the post-reply sequencing check (I15) does not apply to it. Resource invariants still apply.
     * Null otherwise, so the field is left out.
     */
    private Boolean cleanup() {
        // a command's own decision is never cleanup, even when it answers at once with no chain (review of PR 5, 4)
        return state.isOutcomeCycle() && state.chain(state.requestId()) == null ? Boolean.TRUE : null;
    }

    static String effectList(ControlEffect... requested) {
        StringBuilder list = new StringBuilder();
        for (ControlEffect e : requested) {
            if (list.length() > 0) list.append(';');
            list.append(e.opId()).append(' ').append(e.effect()).append('(').append(e.generation()).append(')');
        }
        return list.toString();
    }
}
