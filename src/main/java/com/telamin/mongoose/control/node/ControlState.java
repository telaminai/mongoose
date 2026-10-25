/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control.node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The control processor's decided state: registrations by generation, the request chains still waiting on effect
 * outcomes, and the identities of the record being written. Read and written only by control-processor nodes, on the
 * group's agent thread. All of it is derived local state, so every field is {@code transient}: none is supplied by the
 * graph builder.
 */
public class ControlState {

    /** Lifecycle of one registration, as the control processor has decided it. */
    public enum Lifecycle { STARTING, STARTED, RELEASING, RELEASED, FAILED }

    /** One accepted add the control processor knows about. Removed once released, failed or cancelled. */
    public static final class Reg {
        public final long generation;
        public final int incarnation;
        public String name;
        public final long addRequestId;
        public Lifecycle lifecycle = Lifecycle.STARTING;

        Reg(long generation, int incarnation, String name, long addRequestId) {
            this.generation = generation;
            this.incarnation = incarnation;
            this.name = name;
            this.addRequestId = addRequestId;
        }
    }

    /** What a request is waiting for: the next step is decided when the outcome of its current effect arrives. */
    public enum ChainKind { ADD, REFUSED_ADD, STOP, CANCEL, CLOSE, BROADCAST, RUN }

    /** A request whose reply depends on effect outcomes still to come. */
    public static final class Chain {
        public final long requestId;
        public final ChainKind kind;
        public int outstanding;
        public String failure;
        public boolean failBoot;

        Chain(long requestId, ChainKind kind) {
            this.requestId = requestId;
            this.kind = kind;
        }
    }

    private transient long seq;
    private transient Map<Long, Reg> registrations = new LinkedHashMap<>();
    private transient Map<Long, Chain> chains = new HashMap<>();
    private transient Map<Long, Integer> opCounters = new HashMap<>();

    /** Identity of the cycle being recorded. */
    private transient long requestId;
    private transient long rootId;
    private transient long causeSeq = -1;
    private transient String causeOp;
    private transient long inputSeq = -1;
    private transient boolean closing;

    /** Begin recording a command or completion cycle. */
    public void beginInput(long requestId) {
        this.requestId = requestId;
        this.rootId = requestId;
        this.causeSeq = -1;
        this.causeOp = null;
        this.inputSeq = -1;
    }

    /** Begin recording a cycle caused by an effect outcome. */
    public void beginOutcome(long requestId, String opId) {
        this.requestId = requestId;
        this.rootId = requestId;
        this.causeOp = opId;
        this.causeSeq = -1;
        this.inputSeq = -1;
    }

    public long nextSeq() {
        return ++seq;
    }

    public long seq() {
        return seq;
    }

    public long requestId() {
        return requestId;
    }

    public long rootId() {
        return rootId;
    }

    public long causeSeq() {
        return causeSeq;
    }

    public String causeOp() {
        return causeOp;
    }

    /** The {@code seq} of this cycle's first record, which later records in the cycle name as their cause. */
    public void markInput(long seq) {
        this.inputSeq = seq;
    }

    public long inputSeq() {
        return inputSeq;
    }

    /** The next effect operation id of a request: {@code requestId.n}. */
    public String nextOp(long requestId) {
        int n = opCounters.merge(requestId, 1, Integer::sum);
        return requestId + "." + n;
    }

    public Reg admit(long generation, int incarnation, String name, long addRequestId) {
        Reg reg = new Reg(generation, incarnation, name, addRequestId);
        registrations.put(generation, reg);
        return reg;
    }

    public Reg registration(long generation) {
        return registrations.get(generation);
    }

    /** The live registration answering to {@code name}, for a stop that names a processor but carries no generation. */
    public Reg registration(String name) {
        if (name == null) return null;
        for (Reg r : registrations.values()) {
            if (name.equals(r.name) && (r.lifecycle == Lifecycle.STARTED || r.lifecycle == Lifecycle.RELEASING)) return r;
        }
        return null;
    }

    /** Set by the close rule: from then on adds are refused and nothing new is started. */
    public void closing() {
        this.closing = true;
    }

    public boolean isClosing() {
        return closing;
    }

    public void forget(long generation) {
        registrations.remove(generation);
    }

    /** Started registrations, in the order their adds were applied. */
    public List<Reg> started() {
        List<Reg> out = new ArrayList<>();
        for (Reg r : registrations.values()) {
            if (r.lifecycle == Lifecycle.STARTED) out.add(r);
        }
        return out;
    }

    /** Registrations not yet released, for the read snapshot and invariant I5. */
    public List<Reg> live() {
        return new ArrayList<>(registrations.values());
    }

    public Chain openChain(long requestId, ChainKind kind) {
        return chains.computeIfAbsent(requestId, id -> new Chain(id, kind));
    }

    public Chain chain(long requestId) {
        return chains.get(requestId);
    }

    /** The request has its reply: its bookkeeping is released, so the state stays bounded. */
    public void closeChain(long requestId) {
        chains.remove(requestId);
        opCounters.remove(requestId);
    }

    public int openChains() {
        return chains.size();
    }
}
