/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control;

import com.telamin.fluxtion.runtime.audit.EventLogControlEvent;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.control.effect.ControlEffect;
import com.telamin.mongoose.control.effect.EffectAdapter;
import com.telamin.mongoose.control.event.CloseIntent;
import com.telamin.mongoose.control.event.ControlInput;
import com.telamin.mongoose.control.event.ControlOutcome;
import com.telamin.mongoose.control.event.EffectResult;
import com.telamin.mongoose.control.event.FailureKind;
import com.telamin.mongoose.control.event.ReplyOutcome;
import com.telamin.mongoose.control.generated.GroupControlProcessor;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Feeds a group's control requests into its generated control processor, one at a time, and settles the effects each
 * one asks for: {@code onEvent(intent)}, then {@code batchEnd()} while effects are pending, at most
 * {@value #MAX_EFFECT_ROUNDS} rounds (the analyser's {@code SessionDriver}, adapted to many senders, closing and
 * death).
 *
 * <h2>Threads</h2>
 * Any thread may {@link #send}; only the group's agent thread drains, applies and closes, except that a group that
 * never started is closed on the closing thread (as Agrona closes it), and a dead group's leftovers are completed by
 * whoever finds it dead. The control processor itself is touched only by the agent thread (or by the closing thread
 * when no agent thread ever existed).
 *
 * <h2>Terminal admission</h2>
 * Every request is added to the outstanding registry before its state is checked; whoever ends the group moves its
 * state to {@code CLOSING} or {@code DEAD} before sweeping that registry. The sender's add-then-read and the
 * terminator's write-then-sweep are sequentially consistent, so one of them sees the other: no request is orphaned,
 * even when its sender is paused between reserving a mailbox slot and publishing into it. The two-phase claim on
 * {@link ControlRequest} makes every reply happen once.
 */
public final class ControlDriver {

    /** One cold-path checker for all groups; no per-group waiting thread and no duty-cycle polling cost. */
    private static final Set<ControlDriver> LIVE = ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.ScheduledExecutorService DEATH_CHECKS =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "mongoose-control-liveness");
                thread.setDaemon(true);
                return thread;
            });

    static {
        DEATH_CHECKS.scheduleWithFixedDelay(() -> {
            for (ControlDriver driver : LIVE) {
                Thread agent = driver.agentThread;
                if (agent != null && !agent.isAlive() && LIVE.remove(driver)) {
                    // Completion callbacks cannot hold up the checker for unrelated groups. Only terminated groups
                    // get a cleanup task, and joining before touching claimed gives ownership to that task.
                    Thread.ofVirtual().name("mongoose-control-ended-" + driver.group).start(() -> {
                        try {
                            agent.join();
                            driver.markDead("agent thread terminated", true);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        } finally {
                            driver.failClaimed("group died");
                        }
                    });
                }
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
    }

    public enum GroupState { NEW, STARTING, RUNNING, CLOSING, CLOSED, DEAD }

    /** Generous: the deepest real cascade is a close that releases every processor. A bound makes a runaway loud. */
    public static final int MAX_EFFECT_ROUNDS = 64;

    private final String group;
    private final GroupControlProcessor processor = new GroupControlProcessor();
    private final ControlAuditSink audit;
    private final ManyToOneConcurrentArrayQueue<ControlRequest> mailbox;
    private final ConcurrentLinkedQueue<ControlInput> completions = new ConcurrentLinkedQueue<>();
    private final Set<ControlRequest> outstanding = ConcurrentHashMap.newKeySet();
    /** Claimed requests not yet replied: agent thread only (or the post-join cleanup). */
    private final Map<Long, ControlRequest> claimed = new HashMap<>();
    private final AtomicReference<GroupState> state = new AtomicReference<>(GroupState.NEW);
    private final AtomicLong requestIds = new AtomicLong();
    private volatile Thread agentThread;
    private volatile long fullTimeoutNanos = TimeUnit.SECONDS.toNanos(10);
    private boolean controlWedged;

    public ControlDriver(String group, int capacity, EffectAdapter adapter, ControlAuditSink audit) {
        this.group = group;
        this.audit = audit;
        this.mailbox = new ManyToOneConcurrentArrayQueue<>(capacity);
        // the sink and the level BEFORE init(): init audits a lifecycle event, and the generated EventLogManager's
        // default sink is System.out (measured in the analyser's SessionDriver)
        processor.setAuditLogProcessor(audit);
        processor.eventLogger.logLevel(EventLogControlEvent.LogLevel.INFO);
        // the runtime prints each event's toString unquoted: a name with a colon, or the multi-line text of an exported
        // function, breaks the YAML for the analyser. The Input records carry every field, quoted, so drop it
        processor.eventLogger.printEventToString(false);
        processor.init();
        processor.start();
        processor.registerService(new Service<>(adapter, EffectAdapter.class));
    }

    // =============================================================================================== sender side

    /**
     * Send a request. Returns once it is in the mailbox, or completed: {@code NOT_RUNNING} on a closing, closed or dead
     * group.
     *
     * @throws IllegalStateException when the mailbox is full and cannot drain: at once on the group's own thread, or
     *                               after the full-mailbox timeout elsewhere. The request is then completed
     *                               {@code REFUSED}, so it is never left pending.
     */
    public ControlRequest send(ControlRequest request) {
        return send(request, Long.MAX_VALUE);
    }

    /**
     * Send a request that must be admitted by {@code admitByNanos} ({@link System#nanoTime}): a caller with a deadline
     * spends it on admission and on the reply together (follow-up review, finding 7). Missing it answers the request
     * {@code REFUSED} and returns it, rather than throw; the full-mailbox timeout still bounds it when it is later.
     */
    private volatile java.util.function.BooleanSupplier abandonWhen = () -> false;

    /**
     * A condition under which a sender waiting on a full mailbox gives up at once: the server's stop, so a sender
     * holding the admission lock never blocks the stop's barrier for the whole backoff (failure-mode hunt, FM-2).
     */
    public void abandonWhen(java.util.function.BooleanSupplier condition) {
        this.abandonWhen = condition == null ? () -> false : condition;
    }

    public ControlRequest send(ControlRequest request, long admitByNanos) {
        if (request.requestId() < 0) request.assignId(requestIds.incrementAndGet());
        outstanding.add(request);
        if (ended()) {
            refuseTerminal(request);
            return request;
        }
        if (!mailbox.offer(request)) {
            long deadline = System.nanoTime() + fullTimeoutNanos;
            while (!mailbox.offer(request)) {
                if (admitByNanos != Long.MAX_VALUE && System.nanoTime() - admitByNanos > 0) {
                    refuse(request, FailureKind.REFUSED_DEADLINE, "not admitted within its deadline: mailbox full");
                    return request;
                }
                if (abandonWhen.getAsBoolean()) {
                    if (request.completeUnclaimed(ControlReply.notRunning(request.requestId(), FailureKind.REFUSED_STATE,
                            "server is stopping"))) {
                        request.runUnapplied();
                        audit.fallback("NotRunning", "group", group, "requestId", request.requestId(),
                                "request", request.description(), "reason", "server is stopping",
                                "kind", FailureKind.REFUSED_STATE);
                    }
                    outstanding.remove(request);
                    return request;
                }
                if (ended()) {
                    refuseTerminal(request);
                    return request;
                }
                if (Thread.currentThread() != agentThread && AgentThreads.isAgentThread()) {
                    // another group's agent thread never backs off: blocking it would stall that group's events
                    refuse(request, FailureKind.REFUSED_ADMISSION, "mailbox full");
                    throw new IllegalStateException("group " + group + ": " + queueName(request.kind())
                            + " queue is full (" + mailbox.capacity() + ") and an agent thread never waits for it");
                }
                if (Thread.currentThread() == agentThread) {
                    refuse(request, FailureKind.REFUSED_ADMISSION, "mailbox full");
                    throw new IllegalStateException("group " + group + ": " + queueName(request.kind())
                            + " queue is full (" + mailbox.capacity() + ") and cannot drain while its own thread waits on it");
                }
                if (System.nanoTime() - deadline > 0) {
                    refuse(request, FailureKind.REFUSED_ADMISSION, "mailbox full");
                    throw new IllegalStateException("group " + group + ": " + queueName(request.kind())
                            + " queue stayed full (" + mailbox.capacity() + ") for "
                            + TimeUnit.NANOSECONDS.toMillis(fullTimeoutNanos) + " ms; is the group's thread running?");
                }
                LockSupport.parkNanos(50_000);
            }
        }
        if (ended()) {
            refuseTerminal(request);
        }
        return request;
    }

    /**
     * Send without ever waiting: for a sender holding a lock (the service lock). A full mailbox refuses the request at
     * once and throws, as {@link #send} does when it gives up.
     */
    public ControlRequest trySend(ControlRequest request) {
        if (request.requestId() < 0) request.assignId(requestIds.incrementAndGet());
        outstanding.add(request);
        if (ended()) {
            refuseTerminal(request);
            return request;
        }
        if (!mailbox.offer(request)) {
            refuse(request, FailureKind.REFUSED_ADMISSION, "mailbox full");
            throw new IllegalStateException("group " + group + ": " + queueName(request.kind()) + " queue is full ("
                    + mailbox.capacity() + ") and the sender cannot wait for it");
        }
        if (ended()) {
            refuseTerminal(request);
        }
        return request;
    }

    /** The name each kind of request had as a queue of its own before the single mailbox, kept in error messages. */
    private static String queueName(ControlRequest.Kind kind) {
        return switch (kind) {
            case ADD -> "processor-start";
            case STOP -> "processor-stop";
            case BROADCAST -> "service broadcast";
            case RUN -> "agent hand-off";
            case CLOSE -> "close";
            case CAPTURE -> "audit capture";
        };
    }

    /**
     * A late result from an asynchronous effect; never refused, whatever the mailbox holds. One that reaches a group that
     * has ended is handed to the orphan handler exactly once, by whichever of this sender and the closer removes it.
     */
    public void complete(ControlInput completion) {
        completions.add(completion);
        GroupState s = state.get();
        if ((s == GroupState.CLOSED || s == GroupState.DEAD) && completions.remove(completion)) {
            orphan(completion);
        }
    }

    private volatile java.util.function.Consumer<ControlInput> orphanHandler = c -> { };

    /** What to do with a completion no control processor will see (the group has ended): close what it carries. */
    public void onOrphanCompletion(java.util.function.Consumer<ControlInput> handler) {
        this.orphanHandler = handler == null ? c -> { } : handler;
    }

    private void orphan(ControlInput completion) {
        try {
            orphanHandler.accept(completion);
        } catch (RuntimeException e) {
            audit.fallback("Anomaly", "group", group, "requestId", completion.requestId(),
                    "description", "orphaned completion could not be handled: " + e);
        }
    }

    /** Hand every completion still queued to the orphan handler, once the group can no longer dispatch them. */
    private void orphanLeftovers() {
        for (ControlInput c; (c = completions.poll()) != null; ) orphan(c);
    }

    private boolean ended() {
        GroupState s = state.get();
        if (s == GroupState.CLOSING || s == GroupState.CLOSED || s == GroupState.DEAD) return true;
        Thread t = agentThread;
        if (t != null && !t.isAlive() && (s == GroupState.RUNNING || s == GroupState.STARTING)) {
            // the agent died without the fatal wrapper or onClose running: whoever notices runs the death path
            markDead("agent thread terminated without closing");
            return true;
        }
        return false;
    }

    /**
     * Mark the group dead if its captured thread has ended without closing it (the error handler threw, so Agrona's
     * loop exited without the fatal wrapper or onClose). The server calls it before and after closing the group.
     */
    public void detectDeath() {
        Thread t = agentThread;
        GroupState s = state.get();
        if (t == null || t.isAlive() || s == GroupState.CLOSED || s == GroupState.DEAD) return;
        markDead("agent thread terminated without closing");
    }

    private void refuseTerminal(ControlRequest request) {
        GroupState now = state.get();
        FailureKind kind = terminalKind(now);
        if (request.completeUnclaimed(ControlReply.notRunning(request.requestId(), kind, "group " + group + " is " + now))) {
            request.runUnapplied();
            audit.fallback("NotRunning", "group", group, "requestId", request.requestId(), "request", request.description(),
                    "kind", kind);
        }
        outstanding.remove(request);
    }

    /** A request never applied because its group has ended: a dead group's is a death outcome, otherwise a refusal. */
    private static FailureKind terminalKind(GroupState state) {
        return state == GroupState.DEAD ? FailureKind.FAILED_DEAD : FailureKind.REFUSED_STATE;
    }

    private void refuse(ControlRequest request, FailureKind kind, String why) {
        if (request.completeUnclaimed(new ControlReply(request.requestId(), ReplyOutcome.REFUSED, why, -1, null, kind))) {
            request.runUnapplied();
            audit.fallback("Refused", "group", group, "requestId", request.requestId(), "reason", why, "kind", kind);
        }
        outstanding.remove(request);
    }

    public void setFullTimeout(java.time.Duration timeout) {
        this.fullTimeoutNanos = timeout.toNanos();
    }

    public int capacity() {
        return mailbox.capacity();
    }

    // =============================================================================================== agent side

    /** Called on the agent thread as the group starts: liveness is judged from this captured thread from now on. */
    public void starting(Thread agent) {
        this.agentThread = agent;
        state.compareAndSet(GroupState.NEW, GroupState.STARTING);
        LIVE.add(this);
    }

    public void running() {
        state.compareAndSet(GroupState.STARTING, GroupState.RUNNING);
    }

    public GroupState state() {
        return state.get();
    }

    public boolean isEmpty() {
        return mailbox.isEmpty() && completions.isEmpty();
    }

    /** Requests accepted before now: the boot drain applies exactly these with the boot failure policy. */
    public int queued() {
        return mailbox.size();
    }

    /**
     * Apply up to {@code max} requests and every pending completion, stopping early once {@code budgetNanos} has
     * passed (at least one request is always applied).
     *
     * @return the number applied
     */
    public int drain(int max, long budgetNanos) {
        if (controlWedged) return 0;
        int work = 0;
        for (ControlInput c; (c = completions.poll()) != null; ) {
            dispatch(c, null);
            work++;
        }
        long start = System.nanoTime();
        for (int i = 0; i < max; i++) {
            ControlRequest request = mailbox.poll();
            if (request == null) break;
            apply(request);
            work++;
            if (System.nanoTime() - start > budgetNanos) break;
        }
        return work;
    }

    /** Apply one request now: claim it, dispatch its intent, settle its effects. */
    public void apply(ControlRequest request) {
        if (!request.claim()) {
            outstanding.remove(request);      // already answered by a sweep or its sender
            return;
        }
        if (controlWedged) {
            complete(request, FailureKind.FAILED_RULE, "control processor is wedged");
            return;
        }
        claimed.put(request.requestId(), request);
        dispatch(request.intent(), request);
    }

    private void dispatch(ControlInput input, ControlRequest request) {
        try {
            processor.onEvent(input);
        } catch (Throwable t) {
            // a throw from a rule leaves the generated processor's processing flag set: it cannot dispatch again
            controlWedged = true;
            audit.fallback("Anomaly", "group", group, "requestId", input.requestId(),
                    "description", "control processor threw during dispatch: " + t, "kind", FailureKind.FAILED_RULE);
            if (request != null) complete(request, FailureKind.FAILED_RULE, "control processor threw: " + t);
            markDead("control processor threw during dispatch");
            if (t instanceof VirtualMachineError e) {
                failClaimed("group died: " + e);   // the agent owns what it claimed: answered before the thread ends
                throw e;
            }
            return;
        }
        settle(input, request);
    }

    private void settle(ControlInput input, ControlRequest request) {
        int rounds = 0;
        while (!processor.effectQueue.isEmpty()) {
            if (++rounds > MAX_EFFECT_ROUNDS) {
                List<ControlEffect> left = processor.effectQueue.takeAbandoned();
                audit.fallback("Anomaly", "group", group, "requestId", input.requestId(),
                        "description", "effects did not settle after " + MAX_EFFECT_ROUNDS + " batchEnd rounds; still asking for "
                                + (left.isEmpty() ? "nothing" : left.get(0).effect()));
                if (request != null) complete(request, FailureKind.FAILED_RULE, "effects did not settle");
                return;
            }
            try {
                processor.batchEnd();
            } catch (Throwable t) {
                // a rule threw while an outcome was dispatched inside batchEnd: the generated method has no finally,
                // so the processor's processing flag stays set and it cannot dispatch again
                controlWedged = true;
                audit.fallback("Anomaly", "group", group, "requestId", input.requestId(),
                        "description", "control processor threw during batchEnd: " + t, "kind", FailureKind.FAILED_RULE);
                if (request != null) complete(request, FailureKind.FAILED_RULE, "control processor threw: " + t);
                markDead("control processor threw during batchEnd");
                if (t instanceof VirtualMachineError e) {
                    failClaimed("group died: " + e);
                    throw e;
                }
                return;
            }
            Throwable fatal = processor.effectQueue.takeFatal();
            if (fatal != null) {
                List<ControlEffect> abandoned = processor.effectQueue.takeAbandoned();
                if (fatal instanceof VirtualMachineError e) {
                    for (ControlEffect a : abandoned) {
                        audit.fallback("EffectOutcome", "group", group, "opId", a.opId(), "effect", a.effect(),
                                "outcome", "failed", "reason", "abandoned", "kind", FailureKind.FAILED_DRIVER_FALLBACK);
                    }
                    audit.fallback("Anomaly", "group", group, "requestId", input.requestId(), "description", "fatal: " + e);
                    if (request != null) complete(request, FailureKind.FAILED_DEAD, "fatal error: " + e);
                    markDead("fatal error while performing effects");
                    // earlier requests it claimed and still owes (an audit start waiting on its open) are answered by the
                    // agent itself now, before its thread ends: no later call is needed (follow-up review, finding 1)
                    failClaimed("group died: " + e);
                    throw e;
                }
                audit.fallback("Anomaly", "group", group, "requestId", input.requestId(),
                        "description", "effect batch abandoned: " + fatal);
                // the processor is usable again: give every abandoned request a recorded final outcome
                for (ControlEffect a : abandoned) {
                    dispatch(new EffectResult(a.opId(), a.requestId(), a.effect(), ControlOutcome.FAILED, a.generation(),
                            a.name(), "abandoned", FailureKind.FAILED_DRIVER_FALLBACK), null);
                    if (controlWedged) return;
                }
            }
        }
        // a request still claimed after its effects settle will never be answered by a rule, unless it waits on an
        // asynchronous effect (an audit start waiting on its open): answer it here rather than leave it pending
        if (request != null && claimed.containsKey(request.requestId())
                && !processor.controlState.awaitsCompletion(request.requestId())) {
            complete(request, FailureKind.FAILED_DRIVER_FALLBACK, "no reply after its effects settled");
        }
    }

    /** The control processor's Reply effect lands here: complete the claimed request once. */
    public boolean reply(long requestId, ReplyOutcome outcome, String reason, long generation, String name) {
        return reply(requestId, outcome, reason, generation, name, FailureKind.defaultFor(outcome));
    }

    /** As {@link #reply(long, ReplyOutcome, String, long, String)}, with the rule's structured classification. */
    public boolean reply(long requestId, ReplyOutcome outcome, String reason, long generation, String name,
                         FailureKind kind) {
        ControlRequest request = claimed.remove(requestId);
        if (request == null) return false;
        boolean delivered = request.completeClaimed(new ControlReply(requestId, outcome, reason, generation, name, kind));
        outstanding.remove(request);
        return delivered;
    }

    /**
     * The driver answers a claimed request FAILED because the control processor could not (principle 1: the reply has
     * an identified author). {@code replyAuthor: DRIVER} and {@code kind} classify it; {@code failureKind} repeats the
     * kind for readers of the 1.0.33-rc1 export format.
     */
    private void complete(ControlRequest request, FailureKind kind, String reason) {
        claimed.remove(request.requestId());
        if (request.completeClaimed(new ControlReply(request.requestId(), ReplyOutcome.FAILED, reason, -1, null, kind))) {
            audit.fallback(ReplyOutcome.FAILED.name(), "group", group, "requestId", request.requestId(), "reason", reason,
                    "replyAuthor", "DRIVER", "kind", kind, "failureKind", kind);
        }
        outstanding.remove(request);
    }

    /** The request being applied with this id, for the group's effects to find their payload. */
    public ControlRequest claimedRequest(long requestId) {
        return claimed.get(requestId);
    }

    // =============================================================================================== ending

    /**
     * Close the group, on the agent thread from {@code onClose} (or the closing thread for a group that never
     * started): queued requests are completed without being applied, a stop is answered once the close has stopped
     * its processor, every started processor is stopped in add order through the control processor, and the
     * registry is swept.
     */
    public void close() {
        GroupState before = state.get();
        if (before == GroupState.CLOSED) return;
        if (before != GroupState.DEAD) state.set(GroupState.CLOSING);
        List<ControlRequest> held = new ArrayList<>();
        for (ControlRequest r; (r = mailbox.poll()) != null; ) held.add(r);
        if (!controlWedged && before != GroupState.DEAD) {
            for (ControlInput c; (c = completions.poll()) != null; ) dispatch(c, null);
            // work handed to the group's thread before the close still runs before its processors stop
            for (ControlRequest r : held) {
                if (r.kind() == ControlRequest.Kind.RUN && !controlWedged) apply(r);
            }
            ControlRequest close = new ControlRequest(ControlRequest.Kind.CLOSE, "close " + group, CloseIntent::new, null);
            close.assignId(requestIds.incrementAndGet());
            outstanding.add(close);
            if (!controlWedged) apply(close);
            // what else was queued before the close is answered by the control processor's rules, now that it is
            // closing: an add is refused and its name released without its supplier running, a broadcast is refused,
            // a stop finds its processor already stopped
            for (ControlRequest r : held) {
                if (r.kind() != ControlRequest.Kind.RUN && !controlWedged) apply(r);
            }
            // completions that arrived while closing (a capture closed synchronously by a test's closer) are still
            // recorded by the control processor
            for (ControlInput c; !controlWedged && (c = completions.poll()) != null; ) dispatch(c, null);
        }
        failClaimed(FailureKind.FAILED_SHUTDOWN, "group ended");
        if (state.get() != GroupState.DEAD) state.set(GroupState.CLOSED);
        LIVE.remove(this);
        sweep();
        orphanLeftovers();
    }

    /**
     * The server is about to close this group: from now on senders are answered NOT_RUNNING at once and senders waiting
     * on a full mailbox leave. The agent goes on draining what was accepted until its onClose runs.
     */
    public void beginClosing() {
        GroupState s;
        do {
            s = state.get();
            if (s == GroupState.CLOSING || s == GroupState.CLOSED || s == GroupState.DEAD) return;
        } while (!state.compareAndSet(s, GroupState.CLOSING));
    }

    /** Move the group to DEAD and sweep. Idempotent; run again after the thread is joined. */
    public void markDead(String why) {
        markDead(why, Thread.currentThread() == agentThread);
    }

    /** completeClaims is used only by the agent itself or the checker after joining its terminated thread. */
    private void markDead(String why, boolean completeClaims) {
        GroupState s;
        do {
            s = state.get();
            if (s == GroupState.DEAD || s == GroupState.CLOSED) break;
        } while (!state.compareAndSet(s, GroupState.DEAD));
        if (s != GroupState.DEAD && s != GroupState.CLOSED) {
            audit.fallback("Dead", "group", group, "reason", why, "kind", FailureKind.FAILED_DEAD);
        }
        // A wedged control graph may mark itself dead without terminating the data agent. Its owner can answer
        // older claimed requests now; an external detector must wait for thread termination before touching them.
        if (completeClaims) failClaimed("group died");
        sweep();
        orphanLeftovers();
    }

    /** Complete every request still {@code SENT}: never one the agent has claimed. Idempotent. */
    public void sweep() {
        for (ControlRequest r : outstanding.toArray(new ControlRequest[0])) {
            if (r.isClaimed()) continue;
            GroupState now = state.get();
            FailureKind kind = terminalKind(now);
            if (r.completeUnclaimed(ControlReply.notRunning(r.requestId(), kind, "group " + group + " is " + now))) {
                r.runUnapplied();
                audit.fallback("NotRunning", "group", group, "requestId", r.requestId(), "request", r.description(),
                        "kind", kind);
            }
            if (r.isReplied()) outstanding.remove(r);
        }
    }

    /**
     * Complete claimed requests still waiting: by the agent in {@code onClose}, or after a dead agent's thread has been
     * joined (which gives the caller a happens-before edge with everything the agent wrote).
     */
    public void failClaimed(String why) {
        failClaimed(FailureKind.FAILED_DEAD, why);
    }

    /** As {@link #failClaimed(String)}: {@code FAILED_DEAD} after death, {@code FAILED_SHUTDOWN} at an orderly close. */
    public void failClaimed(FailureKind kind, String why) {
        List<ControlRequest> pending;
        synchronized (this) {
            pending = new ArrayList<>(claimed.values());
            claimed.clear();
        }
        // Do not hold the ownership lock while callbacks or the audit sink run: post-join server cleanup must not
        // wait for another completer's callback. Requests remain in outstanding until their completion returns.
        VirtualMachineError fatal = null;
        for (ControlRequest r : pending) {
            try {
                if (r.completeClaimed(new ControlReply(r.requestId(), ReplyOutcome.FAILED, why, -1, null, kind))) {
                    audit.fallback("Failed", "group", group, "requestId", r.requestId(), "reason", why, "kind", kind);
                }
            } catch (Throwable callbackFailure) {
                // ControlRequest completes its future in finally even if its legacy callback throws. Finish the
                // other detached claims as well, then propagate a fatal callback failure after all replies exist.
                audit.fallback("Failed", "group", group, "requestId", r.requestId(), "reason", why, "kind", kind);
                audit.fallback("Anomaly", "group", group, "requestId", r.requestId(),
                        "description", "terminal reply callback failed: " + callbackFailure);
                if (callbackFailure instanceof VirtualMachineError e && fatal == null) fatal = e;
            } finally {
                outstanding.remove(r);
            }
        }
        if (fatal != null) throw fatal;
    }

    /**
     * Test seam for terminal admission: register a request as a sender does before publishing it, without publishing
     * it, as if the sender were paused between reserving a mailbox slot and writing it.
     */
    void registerUnpublished(ControlRequest request) {
        if (request.requestId() < 0) request.assignId(requestIds.incrementAndGet());
        outstanding.add(request);
    }

    /** Sent and not yet finally answered: for tests and the shutdown result. */
    public int outstanding() {
        return outstanding.size();
    }

    public Thread agentThread() {
        return agentThread;
    }

    public ControlAuditSink audit() {
        return audit;
    }

    /** The control processor, for tests and the read snapshot. */
    public GroupControlProcessor processor() {
        return processor;
    }

    public String group() {
        return group;
    }
}
