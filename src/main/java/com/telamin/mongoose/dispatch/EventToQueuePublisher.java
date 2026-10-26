/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dispatch;

import com.telamin.fluxtion.runtime.event.NamedFeedEvent;
import com.telamin.fluxtion.runtime.event.NamedFeedEventImpl;
import com.telamin.fluxtion.runtime.event.ReplayRecord;
import com.telamin.mongoose.internal.NoOpCountersService;
import com.telamin.mongoose.replay.EventCodec;
import com.telamin.mongoose.replay.EventJournal;
import com.telamin.mongoose.replay.JournalledItem;
import com.telamin.mongoose.service.EventSource;
import com.telamin.mongoose.exception.PublishRefusedException;
import com.telamin.mongoose.service.counters.MongooseCounter;
import com.telamin.mongoose.service.pool.PoolAware;
import com.telamin.mongoose.service.pool.impl.PoolTracker;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.java.Log;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * EventToQueuePublisher is a generic class that facilitates the publishing of events to
 * one or more concurrent queues. It supports caching, custom data mapping, and different
 * event wrapping strategies during dispatch.
 *
 * @param <T> the type of event that this publisher handles
 */
@ToString
@Log
@Getter
public class EventToQueuePublisher<T> {

    private final List<NamedQueue> targetQueues = new CopyOnWriteArrayList<>();
    /** Only read on a drop; guarded by publishLock, so delivered events pay no lookup or counter cost. */
    @Getter(lombok.AccessLevel.NONE)
    private final java.util.Map<NamedQueue, QueueDrops> queueDrops = new java.util.HashMap<>();
    /** Bounded first-drop evidence for direct publishers and subscribers without a processor control audit. */
    private final com.telamin.mongoose.control.ControlAuditSink dropAudit =
            new com.telamin.mongoose.control.ControlAuditSink();
    /** One {@code OverloadState} record per loss-state change of a queue (#79 M3); also on the group's control audit. */
    private final com.telamin.mongoose.control.ControlAuditSink stateAudit =
            new com.telamin.mongoose.control.ControlAuditSink();

    private final List<NamedFeedEvent<?>> eventLog = new ArrayList<>();
    private final String name;
    /**
     * Per-feed publish counter. Hot-path field — incremented once per
     * successful {@link #publish(Object)}. Defaults to a no-op handle so the
     * publisher works in any test harness that constructs it directly without
     * going through EventFlowManager.
     */
    private final MongooseCounter publishCounter;

    public EventToQueuePublisher(String name, MongooseCounter publishCounter) {
        this.name = name;
        this.publishCounter = publishCounter;
        register(this);
    }

    public EventToQueuePublisher(String name) {
        this(name, NoOpCountersService.INSTANCE.feedPublishCounter(name));
    }
    /**
     * Cache published items in the event log so they can be replayed to subscribers at {@link #startComplete()}.
     * Publishes stop adding to the log once {@link #startComplete()} has run (#59): the log holds the pre-start
     * history, not the feed's traffic.
     */
    @Setter
    private boolean cacheEventLog;
    /**
     * Keep logging published items after {@link #startComplete()}. Off by default: the log then grows with every
     * publish for the life of the feed, without bound, so only turn it on for a bounded feed or a test that reads the
     * whole history back through {@link #getEventLog()}.
     */
    @Setter
    private boolean retainEventLogAfterStart;
    /** Set by {@link #startComplete()}, under the publish lock: publishes no longer add to the event log. */
    @Getter(lombok.AccessLevel.NONE)
    private boolean startComplete;
    /** Written under the publish lock only; volatile so {@link #getSequenceNumber()} can read it without the lock. */
    private volatile long sequenceNumber = 0;
    @Setter
    private EventSource.EventWrapStrategy eventWrapStrategy = EventSource.EventWrapStrategy.SUBSCRIPTION_NOWRAP;
    @Setter
    private Function<T, ?> dataMapper = Function.identity();
    /** Written under the publish lock only; volatile so {@link #getCacheReadPointer()} can read it without the lock. */
    private volatile int cacheReadPointer = 0;
    /** Replay R3: when set, each item is journalled once, encoded, and carries its sequence number to the queue. */
    private EventJournal journal;
    private EventCodec journalCodec;
    /** Set when a journal append or encoding failed: the feed stops journalling (a gap a replay stops at). */
    private volatile String journalBroken;
    private final boolean logWarning = log.isLoggable(Level.WARNING);
    private final boolean logInfo = log.isLoggable(Level.INFO);
    private final boolean logFine = log.isLoggable(Level.FINE);
    /**
     * Serialises every write into the target queues, and the sequence number and event log with them. Each target
     * queue is a single-producer {@link OneToOneConcurrentArrayQueue}, but a publisher can be called from several
     * threads: a {@code HandlerPipe} sink from every processor that publishes to it, and a source's
     * {@code startComplete} replaying the cache while its own thread publishes. Two unserialised offers can claim the
     * same slot and lose an event (telaminai/mongoose#51). Adding and removing target queues take it too, so a dispatch
     * never sees the list change under it. Uncontended, the lock costs a few nanoseconds per publish.
     * <p>
     * <b>Cost under a slow subscriber.</b> The lock is held for the whole dispatch, including the bounded spin in
     * {@code writeToQueue} when a target queue is full (up to about 10 ms per full queue, after which the item is
     * dropped for that queue and counted in {@link #droppedCount()}). The lock is what keeps one producer per
     * single-producer queue, so it cannot be released during that spin. While one subscriber stays full, therefore:
     * <ul>
     *     <li>every publish on this publisher, from any thread, takes up to ~10 ms per full queue, so one slow
     *     subscriber throttles all publishers of this feed (and delays delivery to its other subscribers);</li>
     *     <li>a publish or {@link #getEventLog()} waiting for the lock can wait behind each publisher queued ahead of
     *     it: up to N x 10 ms with N callers ahead, per full queue;</li>
     *     <li>{@link #addTargetQueue} and {@link #removeTargetQueueByName} wait only for the dispatch in flight:
     *     publishers hold back while one is waiting ({@link #subscriberChangesWaiting}). A Java monitor is not fair,
     *     so without that a thread publishing in a loop takes the lock back every time and a remove never gets in.</li>
     * </ul>
     * <p>
     * With a journal ({@link #journal}), each item is encoded and appended to the journal under this lock too (see
     * {@code journalItem}), so the journal's append cost, including any I/O it does, adds to every caller's wait.
     */
    @Getter(lombok.AccessLevel.NONE)
    private final Object publishLock = new Object();
    /**
     * Adds and removes of target queues waiting for {@link #publishLock}. While it is non-zero, a publish holds back
     * before taking the lock, so the change gets it next (one volatile read per publish when it is zero).
     */
    @Getter(lombok.AccessLevel.NONE)
    private final java.util.concurrent.atomic.AtomicInteger subscriberChangesWaiting =
            new java.util.concurrent.atomic.AtomicInteger();
    /** Dropped queue copies: ordinary publish's bounded spin, or publishReplay's immediate failed offer. */
    @Getter(lombok.AccessLevel.NONE)
    private volatile long droppedCount;

    // ---- overload policy (telaminai/mongoose#79); see docs/design/overload-policy.md ----
    /**
     * Longest single park of a publish waiting for room; bounds how late it notices room a consumer freed. Package
     * private and mutable only so a test can lengthen it and prove that subscriber changes wake a waiter explicitly.
     */
    static volatile long maxParkNanos = java.util.concurrent.TimeUnit.MICROSECONDS.toNanos(100);
    /**
     * Every live publisher, weakly held, so a publish about to wait can tell whether the current thread holds ANY
     * publisher's lock up the stack (review of 35bdeb2, finding 1; review of 1d925ee, the exact zero-default-cost form).
     * A dispatch runs user code under its publish lock (a data mapper, a journal codec, an item's toString, an error
     * listener); a publish from there into a BLOCK_WITH_DEADLINE feed must not wait, or it would hold the outer lock for
     * the deadline. Consulted only on the blocking path, never by a default-policy publish. Leaf lock: held only to
     * register or to iterate.
     */
    private static final java.util.concurrent.atomic.AtomicReference<java.lang.ref.WeakReference<?>[]> PUBLISHERS =
            new java.util.concurrent.atomic.AtomicReference<>(new java.lang.ref.WeakReference<?>[0]);

    /** Copy-on-write registration, pruning collected publishers; read without a lock (review of 2980845, finding 1). */
    private static void register(EventToQueuePublisher<?> publisher) {
        java.lang.ref.WeakReference<?> mine = new java.lang.ref.WeakReference<>(publisher);
        while (true) {
            java.lang.ref.WeakReference<?>[] current = PUBLISHERS.get();
            java.util.ArrayList<java.lang.ref.WeakReference<?>> next = new java.util.ArrayList<>(current.length + 1);
            for (java.lang.ref.WeakReference<?> ref : current) {
                if (ref.get() != null) next.add(ref);
            }
            next.add(mine);
            if (PUBLISHERS.compareAndSet(current, next.toArray(new java.lang.ref.WeakReference<?>[0]))) {
                return;
            }
        }
    }

    /** Live registered publishers, for tests. */
    static int registeredPublishers() {
        int live = 0;
        for (java.lang.ref.WeakReference<?> ref : PUBLISHERS.get()) if (ref.get() != null) live++;
        return live;
    }
    /** Override groups that have bound a queue at some time; guarded by the publish lock. */
    @Getter(lombok.AccessLevel.NONE)
    private final java.util.Set<String> boundGroups = new java.util.HashSet<>();
    @Getter(lombok.AccessLevel.NONE)
    private boolean cacheReplayWarned;
    /** True while a commit on a feed with a blocking queue is offering its item; guarded by the publish lock. */
    @Getter(lombok.AccessLevel.NONE)
    private boolean committing;
    /**
     * Producer operations on this feed made during such a commit (publish, publishOrCache, cache, publishReplay,
     * startComplete, dispatchCachedEventLog), run in order once it completes; guarded by the lock.
     */
    @Getter(lombok.AccessLevel.NONE)
    private final java.util.ArrayDeque<Deferred> deferredDuringCommit = new java.util.ArrayDeque<>();
    /** True while the outermost commit drains {@link #deferredDuringCommit}; nested commits only append. */
    @Getter(lombok.AccessLevel.NONE)
    private boolean drainingDeferred;

    private enum DeferredKind { PUBLISH, PUBLISH_OR_CACHE, CACHE, REPLAY, START_COMPLETE, DISPATCH_CACHED }

    /** A producer operation deferred until the commit in progress completes. */
    private record Deferred(DeferredKind kind, Object item) {
    }

    /** Defer an operation made during a commit on this feed. Under the lock; only the committing thread gets here. */
    private void defer(DeferredKind kind, Object item) {
        deferredDuringCommit.addLast(new Deferred(kind, item));
    }
    private static final MongooseCounter NO_COUNTER = NoOpCountersService.INSTANCE.counter("noop");
    @Getter(lombok.AccessLevel.NONE)
    private volatile MongooseCounter downgradeCounter = NO_COUNTER;
    @Getter(lombok.AccessLevel.NONE)
    private volatile OverloadKillSwitch killSwitch;
    /**
     * Latches by binding name ({@code group/feed/callback}: stable across a group's restart as a new agent object, unlike
     * the queue path; finding 5), kept across the queue's removal until an operator clears them; guarded by the lock.
     */
    @Getter(lombok.AccessLevel.NONE)
    private final java.util.Map<String, Latch> latches = new java.util.HashMap<>();
    @Getter(lombok.AccessLevel.NONE)
    private java.util.concurrent.ExecutorService hookExecutor;
    @Getter(lombok.AccessLevel.NONE)
    private volatile long hookStartedNanos;
    private static final long HOOK_WARN_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    /** How long an idle hook thread lives before it ends. */
    private static final long HOOK_IDLE_MILLIS = 100;
    /** The feed's policy and per-group overrides. Written under the publish lock. */
    @Getter(lombok.AccessLevel.NONE)
    private volatile FeedOverloadSettings overload = FeedOverloadSettings.DEFAULT;
    /**
     * Bindings whose policy is BLOCK_WITH_DEADLINE; guarded by the publish lock. While it is zero (the default policy
     * everywhere) a publish takes exactly the path it took before #79: this one int compare is the only addition.
     */
    @Getter(lombok.AccessLevel.NONE)
    private int blockingTargets;
    /** Set once, when the server stops: a blocking publish then never waits, and a waiting one is refused. */
    @Getter(lombok.AccessLevel.NONE)
    private volatile boolean closing;
    /** Threads waiting, outside the publish lock, for room: woken by a subscriber change, a policy change or close. */
    @Getter(lombok.AccessLevel.NONE)
    private final java.util.Set<Thread> waiters = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Bumped on every subscriber or policy change, so a waiting publish re-evaluates its targets at once. */
    @Getter(lombok.AccessLevel.NONE)
    private final java.util.concurrent.atomic.AtomicInteger targetsEpoch = new java.util.concurrent.atomic.AtomicInteger();
    /** Why the publish in flight does not wait for a blocking queue (agent thread, nested); guarded by the lock. */
    @Getter(lombok.AccessLevel.NONE)
    private String currentDowngrade;
    @Getter(lombok.AccessLevel.NONE)
    private boolean downgradeWarned;
    /** Refused BLOCK_WITH_DEADLINE publishes (deadline or shutdown); written under the publish lock. */
    @Getter(lombok.AccessLevel.NONE)
    private volatile long refusedCount;
    /** Publishes that met a blocking queue on a thread that may not wait, and so used the drop policy instead. */
    @Getter(lombok.AccessLevel.NONE)
    private volatile long downgradedCount;

    /** Journal this feed's items (spec-replay-recording R3). */
    public void journal(EventJournal journal, EventCodec codec) {
        this.journal = journal;
        this.journalCodec = codec;
    }

    public void addTargetQueue(OneToOneConcurrentArrayQueue<Object> targetQueue, String name) {
        addTargetQueue(targetQueue, name, NoOpCountersService.INSTANCE.queueDropCounter(name), null, null, name);
    }

    void addTargetQueue(OneToOneConcurrentArrayQueue<Object> targetQueue, String name, MongooseCounter dropCounter,
                        com.telamin.mongoose.control.ControlAuditSink groupAudit, String group, String path) {
        NamedQueue namedQueue = new NamedQueue(name, targetQueue);
        if (log.isLoggable(Level.FINE)) {
            log.fine("adding a publisher queue:" + namedQueue);
        }
        // under the publish lock: a dispatch loop never sees the list change under it, and contains-then-add is atomic
        subscriberChangesWaiting.incrementAndGet();
        try {
            synchronized (publishLock) {
                if (!targetQueues.contains(namedQueue)) {
                    QueueDrops binding = new QueueDrops(dropCounter, groupAudit, group, path);
                    binding.settings = overload.forGroup(group);
                    Latch latch = latches.get(name);
                    if (latch != null && latch.removed) {
                        // the queue was removed while latched and is back (perhaps on a new path, if its group was
                        // restarted): still latched until an operator clears it
                        binding.state = OverloadState.LATCHED;
                        binding.stateReason = latch.reason;
                        latch.removed = false;
                        latch.path = path;
                        long now = System.currentTimeMillis();
                        writeStateRecord(name, binding, "REMOVED", "LATCHED",
                                "queue re-added while latched (" + latch.reason + ")", now, now, true);
                    }
                    queueDrops.put(namedQueue, binding);
                    diagnosticBindings.put(name, new DiagnosticBinding(binding, false));
                    targetQueues.add(namedQueue);
                    if (group != null) {
                        boundGroups.add(group);
                    }
                    if (binding.settings.blocks()) {
                        blockingTargets++;
                    }
                }
            }
        } finally {
            subscriberChangesWaiting.decrementAndGet();
        }
        wakeWaiters();
    }

    /**
     * Set this feed's overload policy and its per-subscriber-group overrides (telaminai/mongoose#79). Applies to the
     * current bindings and to every binding added later; a publish waiting for room re-evaluates at once.
     */
    public void setOverload(FeedOverloadSettings settings) {
        java.util.Objects.requireNonNull(settings, "settings must be non-null");
        subscriberChangesWaiting.incrementAndGet();
        try {
            synchronized (publishLock) {
                overload = settings;
                int blocking = 0;
                for (QueueDrops binding : queueDrops.values()) {
                    binding.settings = settings.forGroup(binding.group);
                    if (binding.settings.blocks()) {
                        blocking++;
                    }
                }
                blockingTargets = blocking;
            }
        } finally {
            subscriberChangesWaiting.decrementAndGet();
        }
        wakeWaiters();
    }

    /** This feed's overload policy. */
    public FeedOverloadSettings overload() {
        synchronized (publishLock) {
            return overload;
        }
    }

    /**
     * The server is stopping (telaminai/mongoose#79): a publish waiting for room in a BLOCK_WITH_DEADLINE queue is
     * refused at once ({@link PublishRefusedException.Reason#SHUTDOWN}), and a later one is refused instead of waiting
     * when a blocking queue is full. Permanent and idempotent. Never takes the publish lock, so it cannot wait behind a
     * dispatch.
     */
    public void cancelBlockedPublishes() {
        closing = true;
        wakeWaiters();
        // no new hook is started while closing: one queued but not yet started is skipped when its turn comes (external
        // review of d9eec79, finding 4). A hook already running is not interrupted and may run to completion.
        shutdownHookExecutor();
    }

    /** Wake every publish waiting for room, so it re-evaluates (a target, the policy or {@link #closing} changed). */
    /** A downgraded publish that would have waited (M3 hook: counters and health). Under the publish lock. */
    private void onDowngrade(String downgrade) {
        increment(downgradeCounter);
    }

    /** A blocking publish waited {@code waitedNanos} and was then delivered (M3 hook). Under the publish lock. */
    private void onWaited(NamedQueue blocker, long waitedNanos) {
        QueueDrops binding = queueDrops.get(blocker);
        if (binding != null) {
            recordWait(binding, waitedNanos);
        }
    }

    /** One wait for room in {@code binding}'s queue, delivered or refused. Under the publish lock. */
    private static void recordWait(QueueDrops binding, long waitedNanos) {
        binding.blocks++;
        binding.blockedNanosTotal += waitedNanos;
        binding.blockedNanosMax = Math.max(binding.blockedNanosMax, waitedNanos);
        increment(binding.blockCounter);
        setOrdered(binding.blockedNanosGauge, binding.blockedNanosTotal);
        setOrdered(binding.blockedMaxNanosGauge, binding.blockedNanosMax);
    }

    private static void increment(MongooseCounter counter) {
        try {
            counter.increment();
        } catch (RuntimeException failed) {
            log.warning("overload counter failed: " + failed);
        }
    }

    private static void setOrdered(MongooseCounter counter, long value) {
        try {
            counter.setOrdered(value);
        } catch (RuntimeException failed) {
            log.warning("overload gauge failed: " + failed);
        }
    }

    /**
     * A loss on a binding (decision D4): a never-drop (BLOCK_WITH_DEADLINE) binding latches until an operator clears it;
     * a drop-tolerant one is DEGRADED until its quiet period passes. The binding's quiet-period recovery is applied first,
     * so separate loss episodes are separate records (review of 1d925ee, finding 2). One log line and one audit record
     * per state change, and, on a feed with the kill switch on, the hook on entering LATCHED. Under the publish lock.
     */
    private void loss(NamedQueue queue, QueueDrops binding, String kind, long sequence, String reason) {
        long nowNanos = System.nanoTime();
        long nowMillis = System.currentTimeMillis();
        refreshBinding(queue, binding, nowNanos, nowMillis);
        binding.lastLossNanos = nowNanos;
        binding.lastLossEpochMillis = nowMillis;
        OverloadState to = binding.settings.blocks() ? OverloadState.LATCHED : OverloadState.DEGRADED;
        if (binding.state == to || binding.state == OverloadState.LATCHED) {
            return;
        }
        changeState(queue.name(), binding, binding.state.name(), to, kind + ": " + reason, nowMillis, nowMillis);
        if (to == OverloadState.LATCHED) {
            Latch latch = new Latch(kind + ": " + reason,
                    new OverloadLoss(name, queue.name(), binding.group, kind, sequence, reason, nowMillis));
            latch.groupAudit = binding.groupAudit;
            latch.path = binding.path;
            latches.put(queue.name(), latch);
            fireKillSwitch(queue.name(), latch);
        }
    }

    /**
     * Apply one state change: the binding's state, one log line and one audit record. {@code epochMillis} is when the
     * change happened (for a quiet-period recovery: when the quiet period ended), {@code observedEpochMillis} when it was
     * applied. Drop-tolerant changes go to the publisher's state audit only; never-drop changes (anything to or from
     * LATCHED) also go to the subscribing group's control audit (review of 1d925ee, finding 9).
     */
    private void changeState(String queueName, QueueDrops binding, String from, OverloadState to, String why,
                             long epochMillis, long observedEpochMillis) {
        binding.state = to;
        binding.stateReason = why;
        writeStateRecord(queueName, binding, from, to.name(), why, epochMillis, observedEpochMillis,
                binding.settings.blocks() || "LATCHED".equals(from) || to == OverloadState.LATCHED);
    }

    private void writeStateRecord(String queueName, QueueDrops binding, String from, String to, String why,
                                  long epochMillis, long observedEpochMillis, boolean toGroupAudit) {
        String line = "overload state: feed=" + name + " queue=" + queueName + " group=" + binding.group
                + " from=" + from + " to=" + to + " reason=" + why;
        if ("UP".equals(to)) {
            log.info(line);
        } else {
            log.warning(line);
        }
        Object[] fields = {"feed", name, "queue", queueName, "queuePath", binding.path, "group", binding.group,
                "from", from, "to", to, "reason", why, "epochMillis", epochMillis,
                "observedEpochMillis", observedEpochMillis,
                "kind", "UP".equals(to) ? com.telamin.mongoose.control.event.FailureKind.NONE
                        : com.telamin.mongoose.control.event.FailureKind.OVERLOAD_LOSS,
                "evidence", evidenceOf(binding)};
        stateAudit.fallback("OverloadState", fields);
        if (toGroupAudit && binding.groupAudit != null) binding.groupAudit.fallback("OverloadState", fields);
    }

    private com.telamin.mongoose.control.event.EvidenceStatus evidenceOf(QueueDrops binding) {
        return stateAudit.evidenceDegraded() || (binding != null && binding.groupAudit != null
                && binding.groupAudit.evidenceDegraded())
                ? com.telamin.mongoose.control.event.EvidenceStatus.UNVERIFIED
                : com.telamin.mongoose.control.event.EvidenceStatus.NOT_DEGRADED;
    }

    /** Membership is published only on bind/remove/clear; diagnostics never take a user-code publisher lock. */
    @Getter(lombok.AccessLevel.NONE)
    private final java.util.concurrent.ConcurrentHashMap<String, DiagnosticBinding> diagnosticBindings =
            new java.util.concurrent.ConcurrentHashMap<>();
    private record DiagnosticBinding(QueueDrops binding, boolean removed) { }

    /**
     * Weakly consistent, nonblocking diagnostic states. Quiet recovery is derived from the volatile last-loss time;
     * the ordered transition audit is materialized by the next publisher state observation under publishLock.
     */
    public java.util.Map<String, String> groupOverloadStates(String group) {
        java.util.Map<String, String> result = new java.util.TreeMap<>();
        long now = System.nanoTime();
        FeedOverloadSettings current = overload;
        diagnosticBindings.forEach((name, view) -> {
            QueueDrops binding = view.binding();
            if (!java.util.Objects.equals(group, binding.group)) return;
            OverloadState state = binding.state;
            if (state == OverloadState.DEGRADED && current.explicit()
                    && now - binding.lastLossNanos >= current.quietPeriodNanos()) state = OverloadState.UP;
            result.put(name, view.removed() ? "REMOVED" : state.name());
        });
        return java.util.Map.copyOf(result);
    }

    /**
     * A drop-tolerant binding whose quiet period has passed returns to UP, stamped with the time the quiet period ended.
     * Not on an unconfigured feed: there a loss leaves the queue DEGRADED, as before #79 (D2 parity, finding 4).
     */
    private void refreshBinding(NamedQueue queue, QueueDrops b, long nowNanos, long nowMillis) {
        if (!overload.explicit()) {
            return;
        }
        long quiet = overload.quietPeriodNanos();
        if (b.state == OverloadState.DEGRADED && nowNanos - b.lastLossNanos >= quiet) {
            changeState(queue.name(), b, OverloadState.DEGRADED.name(), OverloadState.UP,
                    "quiet for " + (quiet / 1_000_000) + " ms",
                    b.lastLossEpochMillis + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(quiet), nowMillis);
        }
    }

    /** Apply every binding's quiet-period recovery. Under the publish lock. */
    private void refreshStates() {
        long nowNanos = System.nanoTime();
        long nowMillis = System.currentTimeMillis();
        for (var e : queueDrops.entrySet()) {
            refreshBinding(e.getKey(), e.getValue(), nowNanos, nowMillis);
        }
    }

    /**
     * The worst loss state of the bindings with queue path {@code path} (several callback bindings can share one
     * queue), after applying the quiet period, with a description. A path whose queue was removed while LATCHED is still
     * LATCHED until an operator clears it (finding 1).
     */
    public OverloadHealth overloadHealth(String path) {
        synchronized (publishLock) {
            refreshStates();
            OverloadState worst = OverloadState.UP;
            long drops = 0, refusals = 0;
            String reason = "";
            for (QueueDrops b : queueDrops.values()) {
                if (!java.util.Objects.equals(b.path, path)) continue;
                drops += b.count;
                refusals += b.refusals;
                if (b.state.compareTo(worst) > 0) {
                    worst = b.state;
                    reason = b.stateReason;
                }
            }
            for (Latch latch : latches.values()) {
                if (latch.removed && java.util.Objects.equals(latch.path, path) && worst != OverloadState.LATCHED) {
                    worst = OverloadState.LATCHED;
                    reason = latch.reason + " (queue removed while latched)";
                }
            }
            return new OverloadHealth(worst, drops, refusals, reason);
        }
    }

    /** True while the queue path is latched, bound or not: its health check is kept until an operator clears it. */
    public boolean latched(String path) {
        synchronized (publishLock) {
            for (Latch latch : latches.values()) {
                if (java.util.Objects.equals(latch.path, path)) return true;
            }
            return false;
        }
    }

    /** Names of the queues removed while latched, whose latch is kept until an operator clears it (finding 4). */
    public java.util.Set<String> removedLatches() {
        synchronized (publishLock) {
            java.util.Set<String> removed = new java.util.TreeSet<>();
            latches.forEach((queue, latch) -> { if (latch.removed) removed.add(queue); });
            return removed;
        }
    }

    /** Whether this feed configured a policy; false: PR #76 health semantics apply (finding 3). Lock-free. */
    public boolean overloadExplicit() {
        return overload.explicit();
    }

    /** The loss state of the binding named {@code queueName}, after applying the quiet period; null when not bound. */
    public OverloadState overloadState(String queueName) {
        synchronized (publishLock) {
            refreshStates();
            for (var e : queueDrops.entrySet()) {
                if (e.getKey().name().equals(queueName)) return e.getValue().state;
            }
            return null;
        }
    }

    /**
     * Operator clear (decision D4): every DEGRADED or LATCHED binding of this feed returns to UP, and every latch kept
     * for a removed queue is dropped, each with its state change record. A kill-switch hook still queued for a cleared
     * latch is skipped. Counters are not reset. Returns how many queues were cleared.
     */
    public int clearOverloadState() {
        synchronized (publishLock) {
            if (!overload.explicit()) {
                // a feed with no policy keeps PR #76 health: DEGRADED after a loss, not cleared (finding 3)
                log.info("feed " + name + " has no overload policy: its health follows PR #76 and is not cleared");
                return 0;
            }
            int cleared = 0;
            long now = System.currentTimeMillis();
            java.util.Set<String> boundPaths = new java.util.HashSet<>();
            for (var e : queueDrops.entrySet()) {
                boundPaths.add(e.getValue().path);
                if (e.getValue().state != OverloadState.UP) {
                    changeState(e.getKey().name(), e.getValue(), e.getValue().state.name(), OverloadState.UP,
                            "cleared by operator", now, now);
                    cleared++;
                }
            }
            for (var e : latches.entrySet()) {
                if (!e.getValue().removed) continue;
                QueueDrops ghost = new QueueDrops(NO_COUNTER, e.getValue().groupAudit, e.getValue().loss.group(),
                        e.getValue().path);
                writeStateRecord(e.getKey(), ghost, "LATCHED", "UP", "cleared by operator (queue removed)", now, now,
                        true);
                cleared++;
            }
            diagnosticBindings.entrySet().removeIf(entry -> entry.getValue().removed());
            latches.clear();
            return cleared;
        }
    }

    /**
     * Run the kill switch for a latch (decision D5), unless the feed did not turn it on, the server is stopping (the
     * state is still recorded; finding 5), or no hook is set (warned). Each feed has its own hook thread, so a hook that
     * blocks never holds up another feed's (finding 3); a hook still queued when its latch is cleared is skipped.
     * Under the publish lock; the hook itself runs on the feed's hook thread.
     */
    private void fireKillSwitch(String queueName, Latch latch) {
        if (!overload.killSwitchOnLoss()) {
            return;
        }
        if (closing) {
            log.info("feed " + name + ": kill switch not run for the latch of " + latch.loss.queue()
                    + " (server stopping); the state is recorded");
            return;
        }
        OverloadKillSwitch hook = killSwitch;
        if (hook == null) {
            log.warning("feed " + name + ": overload kill switch enabled but no hook is set "
                    + "(MongooseServer.setOverloadKillSwitch): the latch of " + latch.loss.queue() + " is not reported");
            return;
        }
        long running = hookStartedNanos;
        if (running != 0 && System.nanoTime() - running > HOOK_WARN_NANOS) {
            log.warning("feed " + name + ": the previous kill switch hook has been running for "
                    + (System.nanoTime() - running) / 1_000_000 + " ms; this latch's hook waits behind it");
        }
        latch.fired = true; // told once: a later setKillSwitch does not repeat it (finding 7)
        hookExecutor().execute(() -> {
            synchronized (publishLock) {
                if (closing) {
                    log.info("feed " + name + ": kill switch not run for " + latch.loss.queue()
                            + ": the server began to stop before the queued hook started");
                    return;
                }
                if (latches.get(queueName) != latch) {
                    log.info("feed " + name + ": kill switch skipped for " + latch.loss.queue()
                            + ": its latch was cleared before the hook ran");
                    return;
                }
            }
            long start = System.nanoTime();
            hookStartedNanos = start;
            try {
                hook.onNeverDropLoss(latch.loss);
            } catch (Throwable t) {
                log.log(Level.SEVERE, "overload kill switch failed for " + latch.loss, t);
            } finally {
                hookStartedNanos = 0;
                long took = System.nanoTime() - start;
                if (took > HOOK_WARN_NANOS) {
                    log.warning("feed " + name + ": kill switch hook took " + took / 1_000_000 + " ms");
                }
            }
        });
    }

    /**
     * The feed's hook thread: one at most, started on demand and ended after {@link #HOOK_IDLE_MILLIS} idle, so no
     * thread outlives its work or pins this publisher (the thread factory captures only the name; finding 2). Shut down
     * by {@link #cancelBlockedPublishes()}.
     */
    private synchronized java.util.concurrent.ExecutorService hookExecutor() {
        if (hookExecutor == null) {
            String threadName = "mongoose-overload-kill-switch-" + name;
            java.util.concurrent.ThreadPoolExecutor executor = new java.util.concurrent.ThreadPoolExecutor(1, 1,
                    HOOK_IDLE_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(), r -> {
                        Thread t = new Thread(r, threadName);
                        t.setDaemon(true);
                        return t;
                    });
            executor.allowCoreThreadTimeOut(true);
            hookExecutor = executor;
        }
        return hookExecutor;
    }

    private synchronized void shutdownHookExecutor() {
        if (hookExecutor != null) {
            hookExecutor.shutdown();
        }
    }

    /** A latch, kept per binding name so it survives the queue's removal and re-add until an operator clears it. */
    private static final class Latch {
        final String reason;
        final OverloadLoss loss;
        com.telamin.mongoose.control.ControlAuditSink groupAudit;
        /** The queue path of the binding it latched (updated when the queue comes back on a new path). */
        String path;
        /** The queue was removed while latched; the latch is kept. */
        boolean removed;
        /** A hook has been given this latch. */
        boolean fired;

        Latch(String reason, OverloadLoss loss) {
            this.reason = reason;
            this.loss = loss;
        }
    }

    /** Set the hook called when a never-drop queue latches, on feeds with the kill switch on (decision D5). */
    public void setKillSwitch(OverloadKillSwitch killSwitch) {
        synchronized (publishLock) {
            this.killSwitch = killSwitch;
            // a hook set after a queue latched still hears of that latch (finding 6)
            if (killSwitch != null) {
                latches.forEach((queue, latch) -> { if (!latch.fired) fireKillSwitch(queue, latch); });
            }
        }
    }

    /** Attach the M3 counters and, in RECORD mode, the drop marks to the binding named {@code queueName}. */
    void bindOverloadExtras(String queueName, MongooseCounter refusals, MongooseCounter blocks,
                            MongooseCounter blockedNanos, MongooseCounter blockedMaxNanos, DropMarks marks) {
        synchronized (publishLock) {
            for (var e : queueDrops.entrySet()) {
                if (!e.getKey().name().equals(queueName)) continue;
                QueueDrops b = e.getValue();
                b.refusalCounter = refusals;
                b.blockCounter = blocks;
                b.blockedNanosGauge = blockedNanos;
                b.blockedMaxNanosGauge = blockedMaxNanos;
                b.dropMarks = marks;
            }
        }
    }

    /** The feed-level downgrade counter (decision: counters for downgrades). */
    void downgradeCounter(MongooseCounter counter) {
        this.downgradeCounter = counter;
    }

    /** One queue's overload health. */
    public record OverloadHealth(OverloadState state, long drops, long refusals, String reason) {
    }


    /**
     * True when the current thread holds another publisher's lock: the publish is nested inside that dispatch. Exact for
     * the current thread ({@link Thread#holdsLock}), lock-free, and evaluated only when a publish is about to wait (a
     * blocking queue lacks room), never on a publish that finds room.
     */
    private boolean heldByAnotherPublisher() {
        for (java.lang.ref.WeakReference<?> ref : PUBLISHERS.get()) {
            Object other = ref.get();
            if (other != null && other != this && Thread.holdsLock(((EventToQueuePublisher<?>) other).publishLock)) {
                return true;
            }
        }
        return false;
    }


    /**
     * Override keys of this feed's policy that have never bound a subscriber queue: a misspelt or renamed group
     * silently gets the feed default (review finding 6). Checked once the server has started.
     */
    public java.util.Set<String> unboundOverrideGroups() {
        synchronized (publishLock) {
            java.util.Set<String> unbound = new java.util.TreeSet<>(overload.bySubscriberGroup().keySet());
            unbound.removeAll(boundGroups);
            return unbound;
        }
    }

    private void wakeWaiters() {
        targetsEpoch.incrementAndGet();
        for (Thread waiter : waiters) {
            LockSupport.unpark(waiter);
        }
    }

    /**
     * Hold back while an add or remove of a target queue is waiting for the lock, so it is not starved by publishers
     * that take the lock back-to-back. Not when this thread already holds the lock (a nested publish), which would
     * deadlock.
     */
    private void yieldToSubscriberChanges() {
        while (subscriberChangesWaiting.get() != 0 && !Thread.holdsLock(publishLock)) {
            Thread.yield();
        }
    }

    public void publish(T itemToPublish) {
        if (itemToPublish == null) {
            log.info("itemToPublish is null");
            return;
        }
        yieldToSubscriberChanges();
        synchronized (publishLock) {
            if (blockingTargets == 0 && !committing) {
                publishSerialised(itemToPublish);
                return;
            }
        }
        publishWithOverload(itemToPublish);
    }

    private void publishSerialised(T itemToPublish) {

        Object mappedItem = mapItemSafely(itemToPublish, "publish");
        if (mappedItem == null) {
            log.fine("mapped itemToPublish is null");
            return;
        }
        publishMapped(itemToPublish, mappedItem);
    }

    /**
     * A publish when at least one binding is BLOCK_WITH_DEADLINE (telaminai/mongoose#79). All-or-nothing: the item is
     * committed (sequence number, journal, offers) only once every blocking queue has room for it, which then cannot
     * shrink while the lock is held, since this publisher is the queues' only producer. Until then it waits WITHOUT the
     * publish lock, so an unsubscribe, a queue removal or another publisher is never held up by it, and nothing has
     * been consumed: a refusal leaves no queue holding the item and no sequence number used. Called without the lock.
     */
    private void publishWithOverload(T itemToPublish) {
        // never wait on an agent thread (it would stall its group, and every service it hosts), nor inside a publish
        // that already holds the lock (it could not let go of it to wait)
        // A publish nested in ANOTHER publisher's dispatch is detected only once this one would wait (below)
        String downgrade = Thread.holdsLock(publishLock) ? "publish nested inside a publisher's dispatch"
                : com.telamin.mongoose.control.AgentThreads.isAgentThread()
                ? "agent thread " + Thread.currentThread().getName() : null;
        Object mappedItem;
        NamedQueue blocker;
        java.util.List<AdmissionTarget> admissionTargets;
        synchronized (publishLock) {
            if (committing) {
                // nested in this feed's own commit: published once that commit's offers are done (finding 1)
                defer(DeferredKind.PUBLISH, itemToPublish);
                return;
            }
            if (blockingTargets == 0) {
                publishSerialised(itemToPublish);
                return;
            }
            if (downgrade != null) {
                publishDowngraded(itemToPublish, downgrade);
                return;
            }
            mappedItem = mapItemSafely(itemToPublish, "publish");
            if (mappedItem == null) {
                log.fine("mapped itemToPublish is null");
                return;
            }
            NamedQueue fenced = unavailableTarget();
            if (fenced != null) throw refuse(mappedItem, fenced, PublishRefusedException.Reason.TARGET_UNAVAILABLE, 0);
            blocker = blockerWithoutRoom();
            if (blocker == null) {
                publishMapped(itemToPublish, mappedItem);
                return;
            }
            if (heldByAnotherPublisher()) {
                // nested in another publisher's dispatch: waiting would hold that lock for the deadline (finding 1)
                String nested = "publish nested inside a publisher's dispatch";
                noteDowngrade(nested);
                currentDowngrade = nested;
                try {
                    publishMapped(itemToPublish, mappedItem);
                } finally {
                    currentDowngrade = null;
                }
                return;
            }
            if (closing) {
                throw refuse(mappedItem, blocker, PublishRefusedException.Reason.SHUTDOWN, 0);
            }
            // Only waiting publishes allocate a snapshot. It survives unsubscribe so a terminal target cannot vanish
            // from admission evidence while this producer waits. Ordinary unsubscribe retains #79 semantics.
            admissionTargets = new java.util.ArrayList<>();
            retainCurrentTargets(admissionTargets);
        }
        awaitRoomThenPublish(itemToPublish, mappedItem, blocker, admissionTargets);
    }

    /** Count a downgraded publish (one that would have waited) and warn once per publisher. Under the lock. */
    private void noteDowngrade(String downgrade) {
        downgradedCount++;
        onDowngrade(downgrade);
        if (!downgradeWarned && logWarning) {
            downgradeWarned = true;
            log.warning("feed " + name + ": BLOCK_WITH_DEADLINE downgraded to drop-after-spin for a publish on a "
                    + "thread that may not wait (" + downgrade + "); counted in downgradedCount(), warned once");
        }
    }

    /** Publish on a thread that may not wait: blocking queues use the drop policy for this publish, counted. */
    private void publishDowngraded(T itemToPublish, String downgrade) {
        // counted only when the publish would have had to wait: a blocking queue lacks room (review finding 4)
        if (blockerWithoutRoom() == null) {
            publishSerialised(itemToPublish);
            return;
        }
        noteDowngrade(downgrade);
        currentDowngrade = downgrade;
        try {
            publishSerialised(itemToPublish);
        } finally {
            currentDowngrade = null;
        }
    }

    /**
     * Wait, without the publish lock, until every blocking queue has room, then publish; or refuse at the deadline, or
     * at once when the publisher closes. Owns the publisher's reference to a pooled item throughout: publishMapped
     * releases it on success, refuse on refusal, so it is released exactly once.
     */
    private void awaitRoomThenPublish(Object itemToPublish, Object mappedItem, NamedQueue blocker,
                                      java.util.List<AdmissionTarget> admissionTargets) {
        long start = System.nanoTime();
        long deadlineNanos;
        long spinNanos;
        synchronized (publishLock) {
            OverloadSettings settings = settingsOf(blocker);
            deadlineNanos = settings.blockDeadlineNanos();
            spinNanos = Math.min(settings.spinBoundNanos(), deadlineNanos);
        }
        Thread self = Thread.currentThread();
        waiters.add(self);
        try {
            int epochSeen = targetsEpoch.get();
            while (true) {
                long now = System.nanoTime();
                long waited = now - start;
                boolean timedOut = waited >= deadlineNanos;
                // re-evaluate under the lock when there may be room, the targets changed, closing, or at the deadline
                boolean interrupted = self.isInterrupted();
                if (cancelledTarget(admissionTargets) != null || closing || timedOut || interrupted || epochSeen != targetsEpoch.get()
                        || blocker.targetQueue().remainingCapacity() > 0 || !targetQueues.contains(blocker)) {
                    epochSeen = targetsEpoch.get();
                    yieldToSubscriberChanges();
                    synchronized (publishLock) {
                        retainCurrentTargets(admissionTargets);
                        NamedQueue current = blockingTargets == 0 ? null : blockerWithoutRoom();
                        // terminal cancellation wins over room that appeared meanwhile: a waiting publish is refused
                        // at shutdown or on interrupt, attributed to its last blocker even if that queue has gone
                        // (external review of d9eec79, finding 3)
                        NamedQueue attribution = current != null ? current : blocker;
                        // re-read inside the lock: an interrupt that arrived while this thread acquired the monitor
                        // still cancels (review of a6f9c62, finding C)
                        interrupted = interrupted || self.isInterrupted();
                        if (closing) {
                            throw refuse(mappedItem, attribution, PublishRefusedException.Reason.SHUTDOWN, waited);
                        }
                        if (interrupted) {
                            // the interrupt stays set for the caller
                            throw refuse(mappedItem, attribution, PublishRefusedException.Reason.INTERRUPTED, waited);
                        }
                        AdmissionTarget cancelled = cancelledTarget(admissionTargets);
                        if (cancelled != null) {
                            throw refuse(mappedItem, cancelled.queue(), PublishRefusedException.Reason.TARGET_UNAVAILABLE,
                                    waited, cancelled.binding());
                        }
                        if (current == null) {
                            publishMapped(itemToPublish, mappedItem);
                            onWaited(blocker, System.nanoTime() - start);
                            return;
                        }
                        blocker = current;
                        // the blocker may now be another group's queue, or the policy may have changed: re-read its
                        // deadline and spin bound (review finding 9)
                        OverloadSettings settings = settingsOf(current);
                        deadlineNanos = settings.blocks() ? settings.blockDeadlineNanos() : deadlineNanos;
                        spinNanos = Math.min(settings.spinBoundNanos(), deadlineNanos);
                        timedOut = waited >= deadlineNanos;
                        if (timedOut) {
                            throw refuse(mappedItem, current, PublishRefusedException.Reason.DEADLINE, waited);
                        }
                    }
                }
                if (waited < spinNanos) {
                    Thread.onSpinWait();
                } else {
                    LockSupport.parkNanos(this, Math.max(1, Math.min(maxParkNanos, deadlineNanos - waited)));
                }
            }
        } finally {
            waiters.remove(self);
        }
    }

    private record AdmissionTarget(NamedQueue queue, QueueDrops binding, long fenceVersion) { }

    /** Include newly joined targets before evaluating their capacity, and retain them if later removed. Under lock. */
    private void retainCurrentTargets(java.util.List<AdmissionTarget> targets) {
        for (var q : targetQueues) {
            QueueDrops b = queueDrops.get(q);
            if (b != null && targets.stream().noneMatch(t -> t.binding() == b))
                targets.add(new AdmissionTarget(q, b, b.fenceVersion.getAsLong()));
        }
    }

    private AdmissionTarget cancelledTarget(java.util.List<AdmissionTarget> targets) {
        for (AdmissionTarget target : targets) {
            QueueDrops b = target.binding();
            if (b != null && (b.unavailable.getAsBoolean() || b.fenceVersion.getAsLong() != target.fenceVersion()))
                return target;
        }
        return null;
    }

    /** Called only on the blocking admission path, under publishLock. */
    private NamedQueue unavailableTarget() {
        for (var q : targetQueues) {
            var b = queueDrops.get(q);
            if (b != null && b.unavailable.getAsBoolean()) return q;
        }
        return null;
    }

    /** Bind group death and quarantine to blocking admission; predicates must be nonblocking volatile reads. */
    public void bindContainment(String queueName, java.util.function.BooleanSupplier unavailable,
                                java.util.function.LongSupplier fenceVersion) {
        synchronized (publishLock) {
            queueDrops.forEach((q, b) -> {
                if (q.name().equals(queueName)) { b.unavailable = unavailable; b.fenceVersion = fenceVersion; }
            });
        }
    }

    /**
     * The first BLOCK_WITH_DEADLINE binding whose queue lacks room for this publish, or null when all have room.
     * Called under the publish lock. Room means one slot per binding sharing the physical queue (several callback
     * types of one group share it), plus one per cached item the publish dispatches first.
     */
    private NamedQueue blockerWithoutRoom() {
        int cachedFirst = cacheEventLog ? eventLog.size() - cacheReadPointer : 0;
        for (int i = 0, n = targetQueues.size(); i < n; i++) {
            NamedQueue candidate = targetQueues.get(i);
            if (!settingsOf(candidate).blocks()) {
                continue;
            }
            OneToOneConcurrentArrayQueue<Object> physical = candidate.targetQueue();
            int bindings = 0;
            boolean checkedAlready = false;
            for (int j = 0; j < n; j++) {
                NamedQueue other = targetQueues.get(j);
                if (other.targetQueue() == physical) {
                    if (j < i && settingsOf(other).blocks()) {
                        checkedAlready = true;
                        break;
                    }
                    bindings++;
                }
            }
            if (!checkedAlready && physical.remainingCapacity() < (long) bindings * (1 + cachedFirst)) {
                return candidate;
            }
        }
        return null;
    }

    /** A binding's resolved settings; the feed default for one no longer bound. Called under the publish lock. */
    private OverloadSettings settingsOf(NamedQueue queue) {
        QueueDrops binding = queueDrops.get(queue);
        return binding == null ? overload.feedDefault() : binding.settings;
    }

    /**
     * Count a refusal and release the publisher's reference to a pooled item (no queue holds it, so it goes back to
     * its pool), then return the exception for the caller. The item must not be published again.
     */
    private PublishRefusedException refuse(Object mappedItem, NamedQueue queue, PublishRefusedException.Reason reason,
                                           long waitedNanos) {
        return refuse(mappedItem, queue, reason, waitedNanos, null);
    }

    private PublishRefusedException refuse(Object mappedItem, NamedQueue queue, PublishRefusedException.Reason reason,
                                           long waitedNanos, QueueDrops originalBinding) {
        String description;
        try {
            description = String.valueOf(mappedItem);
        } catch (Throwable t) {
            description = mappedItem.getClass().getName() + " (toString failed: " + t + ")";
        }
        long refusalsForQueue;
        long refusalsForFeed;
        com.telamin.mongoose.control.event.EvidenceStatus evidence;
        synchronized (publishLock) {
            refusalsForFeed = ++refusedCount;
            QueueDrops binding = queueDrops.get(queue);
            evidence = evidenceOf(binding == null ? originalBinding : binding);
            refusalsForQueue = binding == null ? 0 : ++binding.refusals;
            if (binding != null) {
                increment(binding.refusalCounter);
                if (waitedNanos > 0) {
                    recordWait(binding, waitedNanos);
                }
                // a refusal at shutdown is reported to the caller and is not a health event: the server is stopping
                if (reason != PublishRefusedException.Reason.SHUTDOWN && reason != PublishRefusedException.Reason.TARGET_UNAVAILABLE) {
                    loss(queue, binding, "refused", -1, reason + " after ~" + (waitedNanos / 1_000_000) + " ms");
                }
            }
        }
        if ((refusalsForQueue == 1 || refusalsForFeed == 1) && logWarning) {
            log.warning("feed " + name + ": first refused publish for queue " + queue.name() + " (" + reason
                    + ", waited ~" + (waitedNanos / 1_000_000) + " ms); later refusals are counted in refusedCount()");
        }
        PoolTracker<?> publisherRef = trackerOf(mappedItem);
        if (publisherRef != null) {
            publisherRef.releaseReference();
        }
        return new PublishRefusedException(name, queue.name(), reason, waitedNanos, description, evidence);
    }

    /** Commit and dispatch an already mapped item: sequence number, journal, event log, offers. Under the lock. */
    private void publishMapped(Object itemToPublish, Object mappedItem) {
        // On a feed with a blocking queue, the room checked for this item is reserved until its offers are done: a
        // publish into this feed from code run during the commit (the journal codec or append, an error listener, an
        // item's toString) is deferred until the commit completes (external review of d9eec79, finding 1)
        boolean reserve = blockingTargets > 0 && !committing;
        if (!reserve) {
            commit(itemToPublish, mappedItem);
            return;
        }
        committing = true;
        boolean committed = false;
        try {
            commit(itemToPublish, mappedItem);
            committed = true;
        } finally {
            committing = false;
            if (!committed && !drainingDeferred) {
                // the commit failed: operations deferred during it are not run; their pooled references are released
                discardDeferred();
            }
        }
        if (!drainingDeferred) {
            drainDeferred();
        }
    }

    /** Drop every queued deferred operation, releasing each pooled item's handed-over reference exactly once. */
    private void discardDeferred() {
        Deferred left;
        while ((left = deferredDuringCommit.pollFirst()) != null) {
            if (left.item() != null && left.kind() != DeferredKind.REPLAY) releaseUnpublished(left.item());
        }
    }

    /**
     * Run what was deferred during commits, in order, iteratively: only the outermost commit drains; a commit made
     * while draining appends to the same queue (review of a6f9c62, finding B: the recursive drain overflowed the stack).
     * If an operation throws, the pooled references held by the operations still queued are released, each once, and
     * the exception propagates to the outermost caller.
     */
    @SuppressWarnings("unchecked")
    private void drainDeferred() {
        drainingDeferred = true;
        try {
            Deferred next;
            while ((next = deferredDuringCommit.pollFirst()) != null) {
                runDeferred(next);
            }
        } catch (Throwable failed) {
            discardDeferred();
            throw failed;
        } finally {
            drainingDeferred = false;
        }
    }

    /** One deferred operation, with the semantics it had when called: a nested publish never waits. */
    @SuppressWarnings("unchecked")
    private void runDeferred(Deferred op) {
        switch (op.kind()) {
            case PUBLISH -> {
                if (blockingTargets == 0) publishSerialised((T) op.item());
                else publishDowngraded((T) op.item(), "publish nested inside a publisher's dispatch");
            }
            case PUBLISH_OR_CACHE -> {
                if (!startComplete) cacheSerialised((T) op.item());
                else if (blockingTargets == 0) publishSerialised((T) op.item());
                else publishDowngraded((T) op.item(), "publish nested inside a publisher's dispatch");
            }
            case CACHE -> cacheSerialised((T) op.item());
            case REPLAY -> replaySerialised((ReplayRecord) op.item());
            case START_COMPLETE -> {
                warnCacheReplayOnBlockingFeed();
                dispatchCachedEventLogSerialised();
                startComplete = true;
            }
            case DISPATCH_CACHED -> {
                warnCacheReplayOnBlockingFeed();
                dispatchCachedEventLogSerialised();
            }
        }
    }

    private void commit(Object itemToPublish, Object mappedItem) {
        publishCounter.increment();
        sequenceNumber++;
        journalItem(mappedItem, sequenceNumber);

        if (log.isLoggable(Level.FINE)) {
            log.fine("listenerCount:" + targetQueues.size() + " sequenceNumber:" + sequenceNumber + " publish:" + itemToPublish);
        }

        // the publisher's own reference to a pooled item (the one acquire handed over), held until every queue holds
        // its own: released first, the first consumer to finish could return it to the pool while it was still being
        // offered to the next queue (#54). Released last, it returns the item when no queue took it
        PoolTracker<?> publisherRef = trackerOf(mappedItem);
        try {
            if (cacheEventLog) {
                dispatchCachedEventLogSerialised();
                if (!startComplete || retainEventLogAfterStart) {
                    // Store a detached snapshot in the cache to avoid retaining pooled instances: the cache holds no
                    // reference
                    Object cachedData = (mappedItem instanceof PoolAware)
                            ? String.valueOf(mappedItem)
                            : mappedItem;
                    NamedFeedEventImpl<Object> namedFeedEvent = new NamedFeedEventImpl<>(name)
                            .data(cachedData)
                            .sequenceNumber(sequenceNumber);
                    eventLog.add(namedFeedEvent);
                    // dispatched below, so never replayed from the log
                    cacheReadPointer = eventLog.size();
                }
            }

            dispatch(mappedItem, sequenceNumber);
        } finally {
            if (publisherRef != null) {
                publisherRef.releaseReference();
            }
        }
    }

    /**
     * Publishes the item if {@link #startComplete()} has run, else caches it, deciding under the publish lock and so
     * atomically with {@code startComplete}: a source that switches from caching to publishing at start cannot cache
     * an item after the replay (it would wait for the next publish) or publish one before it.
     */
    public void publishOrCache(T item) {
        if (item == null) {
            return;
        }
        yieldToSubscriberChanges();
        synchronized (publishLock) {
            if (committing) {
                // a cached item would be flushed by the commit in progress, ahead of the item it reserved room for
                // (review of a6f9c62, finding A): deferred until that commit completes
                defer(DeferredKind.PUBLISH_OR_CACHE, item);
                return;
            }
            if (!startComplete) {
                cacheSerialised(item);
                return;
            }
            if (blockingTargets == 0) {
                publishSerialised(item);
                return;
            }
        }
        publishWithOverload(item);
    }

    public void cache(T itemToCache) {
        if (itemToCache == null) {
            log.fine("itemToCache is null");
            return;
        }
        yieldToSubscriberChanges();
        synchronized (publishLock) {
            if (committing) {
                defer(DeferredKind.CACHE, itemToCache);
                return;
            }
            cacheSerialised(itemToCache);
        }
    }

    private void cacheSerialised(T itemToCache) {

        Object mappedItem = mapItemSafely(itemToCache, "cache");
        if (mappedItem == null) {
            return;
        }

        if (log.isLoggable(Level.FINE)) {
            log.fine("listenerCount:" + targetQueues.size() + " sequenceNumber:" + sequenceNumber + " publish:" + itemToCache);
        }
        sequenceNumber++;
        journalItem(mappedItem, sequenceNumber);
        if (cacheEventLog) {
            // For explicit cache without publish, detach from pool and store the original instance
            PoolTracker<?> tracker = trackerOf(mappedItem);
            if (tracker != null) {
                tracker.removeFromPool();
            }
            NamedFeedEventImpl<Object> namedFeedEvent = new NamedFeedEventImpl<>(name)
                    .data(mappedItem)
                    .sequenceNumber(sequenceNumber);
            eventLog.add(namedFeedEvent);
        } else {
            // nothing holds the item (no event log, no dispatch): drop the reference handed over with it
            PoolTracker<?> tracker = trackerOf(mappedItem);
            if (tracker != null) {
                tracker.releaseReference();
            }
        }
    }

    /** Legacy synthetic replay publication: one offer per target, no spin/retry; failed offers count as drops. */
    public void publishReplay(ReplayRecord record) {
        if (record == null) {
            log.fine("itemToPublish is null");
            return;
        }
        if (log.isLoggable(Level.FINE)) {
            log.fine("listenerCount:" + targetQueues.size() + " publish:" + record);
        }

        yieldToSubscriberChanges();
        synchronized (publishLock) {
            if (committing) {
                defer(DeferredKind.REPLAY, record);
                return;
            }
            replaySerialised(record);
        }
    }

    private void replaySerialised(ReplayRecord record) {
        for (int i = 0, targetQueuesSize = targetQueues.size(); i < targetQueuesSize; i++) {
            NamedQueue namedQueue = targetQueues.get(i);
            OneToOneConcurrentArrayQueue<Object> targetQueue = namedQueue.targetQueue();
            if (!targetQueue.offer(record)) recordDrop(namedQueue, -1, "replay offer full; no retry");
            if (log.isLoggable(Level.FINE)) {
                log.fine("queue:" + namedQueue.name() + " size:" + targetQueue.size());
            }
        }
    }

    /**
     * The owning source's start is complete: replay what was cached before it to the target queues, and stop adding
     * published items to the event log (unless {@link #setRetainEventLogAfterStart(boolean) retained}). Items given to
     * {@link #cache(Object)} afterwards are still logged and replayed on the next publish or dispatch.
     */
    public void startComplete() {
        yieldToSubscriberChanges();
        synchronized (publishLock) {
            if (committing) {
                defer(DeferredKind.START_COMPLETE, null);
                return;
            }
            warnCacheReplayOnBlockingFeed();
            dispatchCachedEventLogSerialised();
            startComplete = true;
        }
    }

    public void dispatchCachedEventLog() {
        yieldToSubscriberChanges();
        synchronized (publishLock) {
            if (committing) {
                defer(DeferredKind.DISPATCH_CACHED, null);
                return;
            }
            warnCacheReplayOnBlockingFeed();
            dispatchCachedEventLogSerialised();
        }
    }

    /**
     * The pre-start cache is replayed with drop semantics, also to BLOCK_WITH_DEADLINE queues (a replay never waits):
     * warned once per feed when that can lose items (review finding 5). Under the publish lock.
     */
    private void warnCacheReplayOnBlockingFeed() {
        if (!cacheReplayWarned && blockingTargets > 0 && cacheReadPointer < eventLog.size() && logWarning) {
            cacheReplayWarned = true;
            log.warning("feed " + name + ": the cached event log is replayed with drop-after-spin semantics, also to its "
                    + "BLOCK_WITH_DEADLINE queues; a full queue drops cached items (counted)");
        }
    }

    private void dispatchCachedEventLogSerialised() {
        if (cacheReadPointer < eventLog.size()) {
            if (log.isLoggable(Level.FINE)) {
                log.fine("publishing cached items cacheReadPointer:" + cacheReadPointer + " eventLog.size():" + eventLog.size());
            }
            //send updates
            for (int i = cacheReadPointer, eventLogSize = eventLog.size(); i < eventLogSize; i++) {
                NamedFeedEvent<?> cachedFeedEvent = eventLog.get(i);
                // each cached item with ITS sequence number: it was the current one, which a late subscriber's
                // catch-up then carried on every cached item (found by the replay spike, R3)
                dispatch(cachedFeedEvent.data(), cachedFeedEvent.sequenceNumber());
            }

        }
        cacheReadPointer = eventLog.size();
    }

    /**
     * A snapshot of the event log: what was published before {@link #startComplete()}, and what was given to
     * {@link #cache(Object)}. Empty unless {@code cacheEventLog} is set.
     */
    public List<NamedFeedEvent<?>> getEventLog() {
        if (!cacheEventLog) {
            return Collections.emptyList();
        }
        // a snapshot taken under the publish lock: the log is appended to by whichever thread publishes
        synchronized (publishLock) {
            return Collections.unmodifiableList(new ArrayList<>(eventLog));
        }
    }

    private Object mapItemSafely(T item, String context) {
        try {
            Object mapped = dataMapper.apply(item);
            if (mapped == null) {
                log.fine("mappedItem is null");
            }
            if (item != mapped && (item instanceof PoolAware poolAware)) {
                // the input is not published (its mapping is, or nothing is): drop the reference handed over with it
                poolAware.getPoolTracker().releaseReference();
            }
            return mapped;
        } catch (Throwable t) {
            // the input is not published: drop the reference handed over with it, or a pooled input leaks and the
            // pool runs dry (review of #63)
            releaseUnpublished(item);
            log.severe("data mapping (" + context + ") failed: publisher=" + name + ", nextSequenceNumber=" + (sequenceNumber + 1) + ", item=" + item + ", error=" + t);
            com.telamin.mongoose.service.error.ErrorReporting.report(
                    "EventToQueuePublisher:" + name,
                    "data mapping failed for " + context + ": nextSeq=" + (sequenceNumber + 1) + ", item=" + item,
                    t,
                    com.telamin.mongoose.service.error.ErrorEvent.Severity.ERROR);
            return null;
        }
    }

    /** Releases the reference handed over with a pooled item that is not published; a failed release is reported. */
    private void releaseUnpublished(Object item) {
        if (item instanceof PoolAware poolAware) {
            try {
                poolAware.getPoolTracker().releaseReference();
            } catch (Throwable releaseFailed) {
                log.severe("pooled item reference release failed: publisher=" + name + ", error=" + releaseFailed);
                com.telamin.mongoose.service.error.ErrorReporting.report("EventToQueuePublisher:" + name,
                        "pooled item reference release failed", releaseFailed,
                        com.telamin.mongoose.service.error.ErrorEvent.Severity.ERROR);
            }
        }
    }

    /**
     * Encodes and appends one item to the journal. Called under the publish lock, deliberately: the append must follow
     * sequence-number order (a replay reads the journal in append order and stops at the first gap), and it must happen
     * before dispatch, because once a pooled item is on a queue its consumer can return it to the pool and it can be
     * rewritten. Moving the append outside the lock would let two publishers append out of order. The cost is that the
     * codec's encode and the journal's append (which may do I/O) are paid while the lock is held, so a slow journal
     * slows every publisher of this feed.
     */
    private void journalItem(Object mappedItem, long seq) {
        if (journal == null || journalBroken != null) return;
        try {
            // encoded before dispatch, so before a pooled item can return to its pool
            // a copy: the journal owns what it holds, so a codec that reuses its buffer cannot rewrite a journalled item
            // with the feed's next one (the byte-ownership contract of EventCodec; local review of d2c6428)
            journal.append(name, seq, journalCodec.encode(mappedItem).clone());
        } catch (Throwable failed) {
            // never out of publish: from a feed agent it would reach the default error handler, which exits the process
            // (#47 re-review B). The feed stops journalling; its items still carry their numbers, so a replay of the
            // recording stops at the first missing one ("the journal holds no ..."), naming the gap, never joining across it
            journalBroken = String.valueOf(failed);
            String why = "replay journal of feed " + name + " stopped at item #" + seq + " (" + mappedItem + "): " + failed;
            log.severe(why);
            com.telamin.mongoose.service.error.ErrorReporting.report("EventToQueuePublisher:" + name, why, failed,
                    com.telamin.mongoose.service.error.ErrorEvent.Severity.ERROR);
        }
    }

    /** Dropped queue copies across all targets, including publishReplay's immediate failed offers. */
    public long droppedCount() {
        return droppedCount;
    }

    /** Refused BLOCK_WITH_DEADLINE publishes, at the deadline or on shutdown (telaminai/mongoose#79). */
    public long refusedCount() {
        return refusedCount;
    }

    /** Refusals attributed to the current binding named {@code queueName}; removed bindings are forgotten. */
    public long refusedCount(String queueName) {
        synchronized (publishLock) {
            return queueDrops.entrySet().stream().filter(e -> java.util.Objects.equals(e.getKey().name(), queueName))
                    .mapToLong(e -> e.getValue().refusals).sum();
        }
    }

    /**
     * Publishes that met a BLOCK_WITH_DEADLINE queue on a thread that may not wait (an agent thread, or a nested
     * publish), and so used the drop-after-spin policy for that publish (telaminai/mongoose#79).
     */
    public long downgradedCount() {
        return downgradedCount;
    }

    /** Why this feed's journal stopped (an append or its encoding failed), or null while it journals. */
    public String journalBroken() {
        return journalBroken;
    }

    private void dispatch(Object mappedItem, long seq) {
        // writeToQueue acquires one PoolAware reference per queue
        for (int i = 0, targetQueuesSize = targetQueues.size(); i < targetQueuesSize; i++) {
            NamedQueue namedQueue = targetQueues.get(i);
            OneToOneConcurrentArrayQueue<Object> targetQueue = namedQueue.targetQueue();
            switch (eventWrapStrategy) {
                case SUBSCRIPTION_NOWRAP, BROADCAST_NOWRAP -> writeToQueue(namedQueue,
                        journal == null ? mappedItem : new JournalledItem(seq, mappedItem), seq);
                case SUBSCRIPTION_NAMED_EVENT, BROADCAST_NAMED_EVENT -> {
                    //TODO reduce memory pressure by using copy or a recyclable wrapper if needed
                    NamedFeedEventImpl<Object> namedFeedEvent = new NamedFeedEventImpl<>(name)
                            .data(mappedItem)
                            .sequenceNumber(seq);
                    writeToQueue(namedQueue, namedFeedEvent, seq);
                }
            }
            if (log.isLoggable(Level.FINE)) {
                log.fine("queue:" + namedQueue.name() + " size:" + targetQueue.size());
            }
        }
    }

    private void writeToQueue(NamedQueue namedQueue, Object itemToPublish, long sequence) {
        OneToOneConcurrentArrayQueue<Object> targetQueue = namedQueue.targetQueue();
        boolean offered = false;
        long startNs = -1;
        long maxSpinNs = 0; // the binding's spin bound (10 ms by default), looked up on the first failed offer only
        PoolTracker<?> tracker = trackerOf(itemToPublish);
        // the queue's reference, taken before the offer (a consumer may poll and release at once) and kept across
        // retries; the publisher still holds its own, so releasing this one on a drop never returns the item early
        boolean queueRef = false;
        try {
            if (tracker != null) {
                tracker.acquireReference();
                queueRef = true;
            }
            while (!offered) {
                offered = targetQueue.offer(itemToPublish);
                if (!offered) {
                    if (startNs < 0) {
                        startNs = System.nanoTime();
                        maxSpinNs = settingsOf(namedQueue).spinBoundNanos();
                    } else if (System.nanoTime() - startNs > maxSpinNs) {
                        recordDrop(namedQueue, sequence, dropReason(namedQueue, maxSpinNs));
                        if (queueRef) {
                            queueRef = false;
                            tracker.releaseReference();
                        }
                        if (logWarning) {
                            log.warning("dropping publish to slow/contended queue: " + namedQueue.name() +
                                    " after ~" + ((System.nanoTime() - startNs) / 1_000_000) + "ms seq:" + sequenceNumber +
                                    " queueSize:" + targetQueue.size());
                        }
                        return;
                    }
                    Thread.onSpinWait();
                }
            }
        } catch (Throwable t) {
            // ensure no queue ref remains on the exception path when the item never reached the queue
            if (queueRef && !offered) {
                try {
                    tracker.releaseReference();
                } catch (Throwable ignored) {
                }
            }
            log.severe("queue write failed: publisher=" + name + ", queue=" + namedQueue.name() + ", seq=" + sequenceNumber + ", item=" + itemToPublish + ", error=" + t);
            com.telamin.mongoose.service.error.ErrorReporting.report(
                    "EventToQueuePublisher:" + name,
                    "queue write failed: queue=" + namedQueue.name() + ", seq=" + sequenceNumber + ", item=" + itemToPublish,
                    t,
                    com.telamin.mongoose.service.error.ErrorEvent.Severity.CRITICAL);
            throw new com.telamin.mongoose.exception.QueuePublishException("Failed to write to queue '" + namedQueue.name() + "' for publisher '" + name + "'", t);
        }
        if (logFine && startNs > 1) {
            long delta = System.nanoTime() - startNs;
            log.fine("spin wait took " + (delta / 1_000_000) + "ms queue:" + namedQueue.name() + " size:" + targetQueue.size());
        }
    }

    /** The drop's reason for the first-loss evidence: unchanged text for the default policy. */
    private String dropReason(NamedQueue queue, long spinNanos) {
        String spun = "queue full after " + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(spinNanos) + " ms spin";
        if (!settingsOf(queue).blocks()) {
            return spun;
        }
        return spun + "; BLOCK_WITH_DEADLINE not waited: " + (currentDowngrade != null ? currentDowngrade
                : "cached or replayed item");
    }

    private PoolTracker<?> trackerOf(Object item) {
        if (item instanceof JournalledItem journalled) {
            item = journalled.item();
        }
        if (item instanceof PoolAware pa) {
            return pa.getPoolTracker();
        }
        if (item instanceof NamedFeedEvent<?> nfe) {
            Object data = nfe.data();
            if (data instanceof PoolAware pa) {
                return pa.getPoolTracker();
            }
        }
        return null;
    }

    /** Current binding's drop count, even when performance counters are disabled; removed bindings are forgotten. */
    public long droppedCount(String queueName) {
        synchronized (publishLock) {
            return queueDrops.entrySet().stream().filter(e -> java.util.Objects.equals(e.getKey().name(), queueName))
                    .mapToLong(e -> e.getValue().count).sum();
        }
    }

    private void recordDrop(NamedQueue queue, long sequence, String reason) {
        droppedCount++;
        QueueDrops drops = queueDrops.get(queue);
        if (drops == null) return;
        try { drops.counter.increment(); }
        catch (RuntimeException failed) { log.warning("queue drop counter failed for " + queue.name() + ": " + failed); }
        if (drops.dropMarks != null) {
            // RECORD mode: the reader marks the loss in its processors' recordings once it reaches this point (#79)
            drops.dropMarks.add(queue.targetQueue().addedCount(), sequence, reason);
        }
        if (++drops.count == 1) {
            Object[] fields = {"feed", name, "queue", queue.name(), "queuePath", drops.path, "group", drops.group,
                    "sequence", sequence, "dropped", 1, "reason", reason, "epochMillis", System.currentTimeMillis()};
            dropAudit.fallback("QueueDrop", fields);
            if (drops.groupAudit != null) drops.groupAudit.fallback("QueueDrop", fields);
        }
        loss(queue, drops, "dropped", sequence, reason);
    }

    private static final class QueueDrops {
        final MongooseCounter counter;
        final com.telamin.mongoose.control.ControlAuditSink groupAudit;
        final String group;
        final String path;
        long count;
        volatile java.util.function.BooleanSupplier unavailable = () -> false;
        volatile java.util.function.LongSupplier fenceVersion = () -> 0;
        /** This binding's resolved overload policy; written under the publish lock. */
        OverloadSettings settings = OverloadSettings.DEFAULT;
        long refusals;
        // ---- M3 (#79): loss state, counters, recording marks; written under the publish lock ----
        volatile OverloadState state = OverloadState.UP;
        volatile long lastLossNanos;
        long lastLossEpochMillis;
        String stateReason = "";
        MongooseCounter refusalCounter = NO_COUNTER;
        MongooseCounter blockCounter = NO_COUNTER;
        MongooseCounter blockedNanosGauge = NO_COUNTER;
        MongooseCounter blockedMaxNanosGauge = NO_COUNTER;
        long blocks;
        long blockedNanosTotal;
        long blockedNanosMax;
        DropMarks dropMarks;
        QueueDrops(MongooseCounter counter, com.telamin.mongoose.control.ControlAuditSink groupAudit, String group, String path) {
            this.counter = counter; this.groupAudit = groupAudit; this.group = group; this.path = path;
        }
    }

    public record NamedQueue(String name, OneToOneConcurrentArrayQueue<Object> targetQueue) {
    }

    public void removeTargetQueueByName(String queueName) {
        if (queueName == null) {
            return;
        }
        // under the publish lock: an unsubscribe on a processor's thread must not shrink the list while a dispatch on
        // the feed's thread is indexing it (an IndexOutOfBounds out of publish exits the process via the agent's
        // error handler). The remove waits for the dispatch in flight (up to ~10 ms per full target queue): publishers
        // hold back while it waits, so one publishing in a loop cannot starve it. See the publishLock javadoc.
        // A publish waiting for room in a BLOCK_WITH_DEADLINE queue does not hold the lock, so this never waits for it
        // (#79); waking it makes it re-evaluate against the remaining targets at once
        subscriberChangesWaiting.incrementAndGet();
        try {
            wakeWaiters();
            synchronized (publishLock) {
                targetQueues.removeIf(q -> queueName.equals(q.name()));
                queueDrops.entrySet().removeIf(e -> {
                    if (!queueName.equals(e.getKey().name())) {
                        return false;
                    }
                    if (e.getValue().settings.blocks()) {
                        blockingTargets--;
                    }
                    if (e.getValue().state == OverloadState.LATCHED) {
                        diagnosticBindings.put(queueName, new DiagnosticBinding(e.getValue(), true));
                        // not a clear: the latch stays until an operator clears it (finding 1)
                        Latch latch = latches.get(queueName);
                        if (latch != null) latch.removed = true;
                        long now = System.currentTimeMillis();
                        writeStateRecord(queueName, e.getValue(), "LATCHED", "REMOVED",
                                "queue removed while latched; the latch is kept until an operator clears it", now, now,
                                true);
                    } else {
                        diagnosticBindings.remove(queueName);
                    }
                    return true;
                });
            }
        } finally {
            subscriberChangesWaiting.decrementAndGet();
        }
        wakeWaiters();
    }
}
