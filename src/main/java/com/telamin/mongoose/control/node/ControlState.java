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

    /** A registration's audit capture sub-state (design "Audit capture"). */
    public enum Capture { OFF, OPENING, RECORDING, CLOSING }

    /** One accepted add the control processor knows about. Removed once released, failed or cancelled. */
    public static final class Reg {
        public final long generation;
        public final int incarnation;
        public String name;
        public final long addRequestId;
        public Lifecycle lifecycle = Lifecycle.STARTING;
        public Capture capture = Capture.OFF;
        /** The open in progress, while OPENING. */
        public String captureOp;
        /** The installed (or closing) resource, while RECORDING or CLOSING. */
        public long captureResource = -1;
        /** The close of the capture last handed to the closer, while it is outstanding. */
        public String captureCloseOp;
        /** The audit-start requests waiting on the open in progress: answered together when it settles. */
        public final List<Long> captureWaiters = new ArrayList<>();

        Reg(long generation, int incarnation, String name, long addRequestId) {
            this.generation = generation;
            this.incarnation = incarnation;
            this.name = name;
            this.addRequestId = addRequestId;
        }
    }

    /** What a request is waiting for: the next step is decided when the outcome of its current effect arrives. */
    public enum ChainKind { ADD, REFUSED_ADD, STOP, CANCEL, CLOSE, BROADCAST, RUN, AUDIT_START, AUDIT_STOP }

    /** An open requested and not yet completed, kept until its completion arrives even if its registration has gone. */
    public static final class PendingOpen {
        public final String opId;
        public final long generation;
        public final long rootId;
        /** Its waiters were answered already (the open was cancelled or timed out): its completion is cleanup. */
        public boolean settled;

        PendingOpen(String opId, long generation, long rootId) {
            this.opId = opId;
            this.generation = generation;
            this.rootId = rootId;
        }
    }

    public enum ResourceState { OPEN, INSTALLED, CLOSING }

    /** One capture resource the group holds: exactly one owner (a generation, or none for a compensated open). */
    public static final class Resource {
        public final long id;
        public long owner;
        public ResourceState state;
        public final String openedBy;
        /** The generation and processor it was opened for: its close is recorded against them, owned or not (FM-3). */
        public final long openedFor;
        public final String openedName;

        Resource(long id, long owner, ResourceState state, String openedBy, long openedFor, String openedName) {
            this.id = id;
            this.owner = owner;
            this.state = state;
            this.openedBy = openedBy;
            this.openedFor = openedFor;
            this.openedName = openedName;
        }
    }

    /** How many settled opens are remembered to recognise a duplicate completion; older ones are classified unknown. */
    static final int SETTLED_OPENS_REMEMBERED = 1024;

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
    private transient Map<String, Long> serviceVersions = new HashMap<>();
    private transient Map<String, PendingOpen> pendingOpens = new HashMap<>();
    private transient Map<Long, Resource> resources = new LinkedHashMap<>();
    private transient Map<String, Boolean> settledOpens = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > SETTLED_OPENS_REMEMBERED;
        }
    };
    /** Roots with asynchronous capture work outstanding: their op counters outlive their reply. */
    private transient Map<Long, Integer> retainedRoots = new HashMap<>();
    /** Effects requested after their root's reply: their outcomes are cleanup, not part of the request. */
    private transient java.util.Set<String> cleanupOps = new java.util.HashSet<>();

    /** True while recording a cycle caused by an effect outcome or a completion, not by a command. */
    private transient boolean outcomeCycle;

    public boolean isOutcomeCycle() {
        return outcomeCycle;
    }

    /** Begin recording a command or completion cycle. */
    public void beginInput(long requestId) {
        this.outcomeCycle = false;
        this.requestId = requestId;
        this.rootId = requestId;
        this.causeSeq = -1;
        this.causeOp = null;
        this.inputSeq = -1;
    }

    /** Begin recording a cycle caused by an effect outcome. */
    public void beginOutcome(long requestId, String opId) {
        this.outcomeCycle = true;
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

    private transient long compensations;

    /**
     * An op id for compensating a completion nothing expects (a duplicate or unknown one), which can arrive after its
     * root's own sequence has been released: {@code requestId.cN}, from a sequence of its own, so it never collides.
     */
    public String nextCompensationOp(long requestId) {
        return requestId + ".c" + (++compensations);
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

    /**
     * Record a service transition's version; @return false if it is not newer than the last applied (a stale
     * broadcast). Version 0 is unversioned and always applies.
     */
    public boolean applyServiceVersion(String service, long version) {
        if (version <= 0) return true;
        Long last = serviceVersions.get(service);
        if (last != null && version <= last) return false;
        serviceVersions.put(service, version);
        return true;
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
        if (!retainedRoots.containsKey(requestId)) opCounters.remove(requestId);
    }

    // ------------------------------------------------------------------ audit capture

    /** Asynchronous work under {@code rootId} began: its op ids keep counting after the root is answered. */
    public void retain(long rootId) {
        retainedRoots.merge(rootId, 1, Integer::sum);
    }

    /** Asynchronous work under {@code rootId} ended. */
    public void release(long rootId) {
        Integer left = retainedRoots.computeIfPresent(rootId, (k, n) -> n > 1 ? n - 1 : null);
        if (left == null && !chains.containsKey(rootId)) opCounters.remove(rootId);
    }

    public int retainedRoots() {
        return retainedRoots.size();
    }

    public PendingOpen addPendingOpen(String opId, long generation, long rootId) {
        PendingOpen open = new PendingOpen(opId, generation, rootId);
        pendingOpens.put(opId, open);
        return open;
    }

    public PendingOpen pendingOpen(String opId) {
        return pendingOpens.get(opId);
    }

    /** The open's completion arrived: it is remembered for a while, so a second completion reads as a duplicate. */
    public PendingOpen settleOpen(String opId) {
        PendingOpen open = pendingOpens.remove(opId);
        if (open != null) settledOpens.put(opId, Boolean.TRUE);
        return open;
    }

    public boolean wasSettled(String opId) {
        return settledOpens.containsKey(opId);
    }

    public Resource addResource(long id, long owner, ResourceState resourceState, String openedBy, long openedFor,
                                String openedName) {
        Resource r = new Resource(id, owner, resourceState, openedBy, openedFor, openedName);
        resources.put(id, r);
        return r;
    }

    public Resource resource(long id) {
        return resources.get(id);
    }

    /** Closed: the entry is removed, so the table stays bounded. */
    public Resource removeResource(long id) {
        return resources.remove(id);
    }

    public int resources() {
        return resources.size();
    }

    public int pendingOpens() {
        return pendingOpens.size();
    }

    private transient Map<String, Long> effectResources = new HashMap<>();

    public void trackEffectResource(String opId, long resourceId) {
        effectResources.put(opId, resourceId);
    }

    /** Read by the outcome auditor before the capture rule consumes the association. */
    public long effectResource(String opId) {
        return effectResources.getOrDefault(opId, -1L);
    }

    /** The resource a capture effect acted on, once its final result is in; -1 for any other effect. */
    public long takeEffectResource(String opId) {
        Long id = effectResources.remove(opId);
        return id == null ? -1 : id;
    }

    public void markCleanup(String opId) {
        cleanupOps.add(opId);
    }

    /** Whether {@code opId} was requested after its root's reply; forgotten once asked for a final outcome. */
    public boolean isCleanup(String opId, boolean forget) {
        return forget ? cleanupOps.remove(opId) : cleanupOps.contains(opId);
    }


    /** The request waits on an asynchronous effect's completion, so settling its effects does not answer it. */
    public boolean awaitsCompletion(long requestId) {
        Chain chain = chains.get(requestId);
        return chain != null && chain.kind == ChainKind.AUDIT_START;
    }

    public int openChains() {
        return chains.size();
    }
}
