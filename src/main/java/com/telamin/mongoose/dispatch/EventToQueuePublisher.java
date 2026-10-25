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
     * How many publishers' dispatches the current thread is inside (any publisher, not just this one). A dispatch runs
     * user code under its publish lock (a data mapper, a journal codec, an item's toString, an error listener); a
     * publish from there into a BLOCK_WITH_DEADLINE feed must not wait, or it would hold the outer publisher's lock for
     * the deadline (review of 35bdeb2, finding 1). Raised around every locked publish path that can run user code.
     */
    private static final ThreadLocal<int[]> DISPATCH_DEPTH = ThreadLocal.withInitial(() -> new int[1]);
    /** Override groups that have bound a queue at some time; guarded by the publish lock. */
    @Getter(lombok.AccessLevel.NONE)
    private final java.util.Set<String> boundGroups = new java.util.HashSet<>();
    @Getter(lombok.AccessLevel.NONE)
    private boolean cacheReplayWarned;
    private static final MongooseCounter NO_COUNTER = NoOpCountersService.INSTANCE.counter("noop");
    @Getter(lombok.AccessLevel.NONE)
    private volatile MongooseCounter downgradeCounter = NO_COUNTER;
    @Getter(lombok.AccessLevel.NONE)
    private volatile OverloadKillSwitch killSwitch;
    /** The feed's policy and per-group overrides. Written under the publish lock. */
    @Getter(lombok.AccessLevel.NONE)
    private FeedOverloadSettings overload = FeedOverloadSettings.DEFAULT;
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
                    queueDrops.put(namedQueue, binding);
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
     * a drop-tolerant one is DEGRADED until its quiet period passes. One log line and one audit record per state change,
     * and, on a feed with the kill switch on, the hook on entering LATCHED. Under the publish lock.
     */
    private void loss(NamedQueue queue, QueueDrops binding, String kind, long sequence, String reason) {
        long now = System.nanoTime();
        binding.lastLossNanos = now;
        OverloadState to = binding.settings.blocks() ? OverloadState.LATCHED : OverloadState.DEGRADED;
        if (binding.state == to || binding.state == OverloadState.LATCHED) {
            return;
        }
        changeState(queue, binding, to, kind + ": " + reason);
        OverloadKillSwitch hook = killSwitch;
        if (to == OverloadState.LATCHED && overload.killSwitchOnLoss() && hook != null) {
            OverloadLoss event = new OverloadLoss(name, queue.name(), binding.group, kind, sequence, reason,
                    System.currentTimeMillis());
            KillSwitchThread.submit(hook, event);
        }
    }

    private void changeState(NamedQueue queue, QueueDrops binding, OverloadState to, String why) {
        OverloadState from = binding.state;
        binding.state = to;
        binding.stateReason = why;
        String line = "overload state: feed=" + name + " queue=" + queue.name() + " group=" + binding.group
                + " from=" + from + " to=" + to + " reason=" + why;
        if (to == OverloadState.UP) {
            log.info(line);
        } else {
            log.warning(line);
        }
        Object[] fields = {"feed", name, "queue", queue.name(), "queuePath", binding.path, "group", binding.group,
                "from", from.name(), "to", to.name(), "reason", why, "epochMillis", System.currentTimeMillis()};
        stateAudit.fallback("OverloadState", fields);
        if (binding.groupAudit != null) binding.groupAudit.fallback("OverloadState", fields);
    }

    /** Drop-tolerant bindings whose quiet period has passed return to UP. Under the publish lock. */
    private void refreshStates() {
        long now = System.nanoTime();
        long quiet = overload.quietPeriodNanos();
        for (var e : queueDrops.entrySet()) {
            QueueDrops b = e.getValue();
            if (b.state == OverloadState.DEGRADED && now - b.lastLossNanos >= quiet) {
                changeState(e.getKey(), b, OverloadState.UP, "quiet for " + (quiet / 1_000_000) + " ms");
            }
        }
    }

    /**
     * The worst loss state of the bindings with queue path {@code path} (several callback bindings can share one
     * queue), after applying the quiet period, with a description; UP with an empty description when none is degraded.
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
            return new OverloadHealth(worst, drops, refusals, reason);
        }
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
     * Operator clear (decision D4): every DEGRADED or LATCHED binding of this feed returns to UP, each with its state
     * change record. Counters are not reset. Returns how many bindings were cleared.
     */
    public int clearOverloadState() {
        synchronized (publishLock) {
            int cleared = 0;
            for (var e : queueDrops.entrySet()) {
                if (e.getValue().state != OverloadState.UP) {
                    changeState(e.getKey(), e.getValue(), OverloadState.UP, "cleared by operator");
                    cleared++;
                }
            }
            return cleared;
        }
    }

    /** Set the hook called when a never-drop queue latches, on feeds with the kill switch on (decision D5). */
    public void setKillSwitch(OverloadKillSwitch killSwitch) {
        this.killSwitch = killSwitch;
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

    /** Runs kill-switch hooks off the publishing thread and outside any publish lock. */
    private static final class KillSwitchThread {
        private static final java.util.concurrent.ExecutorService EXECUTOR =
                java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "mongoose-overload-kill-switch");
                    t.setDaemon(true);
                    return t;
                });

        static void submit(OverloadKillSwitch hook, OverloadLoss loss) {
            EXECUTOR.execute(() -> {
                try {
                    hook.onNeverDropLoss(loss);
                } catch (Throwable t) {
                    log.log(Level.SEVERE, "overload kill switch failed for " + loss, t);
                }
            });
        }
    }

    /** True while the current thread is inside any publisher's dispatch (see {@link #DISPATCH_DEPTH}). */
    static boolean insideDispatch() {
        return DISPATCH_DEPTH.get()[0] > 0;
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
        int[] depth = DISPATCH_DEPTH.get();
        depth[0]++;
        try {
            synchronized (publishLock) {
                if (blockingTargets == 0) {
                    publishSerialised(itemToPublish);
                    return;
                }
            }
        } finally {
            depth[0]--;
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
        // (insideDispatch: this or ANY other publisher's lock is held up the stack, through user code it ran)
        String downgrade = insideDispatch() ? "publish nested inside a publisher's dispatch"
                : com.telamin.mongoose.control.AgentThreads.isAgentThread()
                ? "agent thread " + Thread.currentThread().getName() : null;
        Object mappedItem;
        NamedQueue blocker;
        int[] depth = DISPATCH_DEPTH.get();
        depth[0]++;
        try {
            synchronized (publishLock) {
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
                blocker = blockerWithoutRoom();
                if (blocker == null) {
                    publishMapped(itemToPublish, mappedItem);
                    return;
                }
                if (closing) {
                    throw refuse(mappedItem, blocker, PublishRefusedException.Reason.SHUTDOWN, 0);
                }
            }
        } finally {
            depth[0]--;
        }
        awaitRoomThenPublish(itemToPublish, mappedItem, blocker);
    }

    /** Publish on a thread that may not wait: blocking queues use the drop policy for this publish, counted. */
    private void publishDowngraded(T itemToPublish, String downgrade) {
        // counted only when the publish would have had to wait: a blocking queue lacks room (review finding 4)
        if (blockerWithoutRoom() == null) {
            publishSerialised(itemToPublish);
            return;
        }
        downgradedCount++;
        onDowngrade(downgrade);
        if (!downgradeWarned && logWarning) {
            downgradeWarned = true;
            log.warning("feed " + name + ": BLOCK_WITH_DEADLINE downgraded to drop-after-spin for a publish on a "
                    + "thread that may not wait (" + downgrade + "); counted in downgradedCount(), warned once");
        }
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
    private void awaitRoomThenPublish(Object itemToPublish, Object mappedItem, NamedQueue blocker) {
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
                if (closing || timedOut || interrupted || epochSeen != targetsEpoch.get()
                        || blocker.targetQueue().remainingCapacity() > 0 || !targetQueues.contains(blocker)) {
                    epochSeen = targetsEpoch.get();
                    yieldToSubscriberChanges();
                    int[] depth = DISPATCH_DEPTH.get();
                    depth[0]++;
                    try {
                    synchronized (publishLock) {
                        NamedQueue current = blockingTargets == 0 ? null : blockerWithoutRoom();
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
                        if (closing) {
                            throw refuse(mappedItem, current, PublishRefusedException.Reason.SHUTDOWN, waited);
                        }
                        if (timedOut) {
                            throw refuse(mappedItem, current, PublishRefusedException.Reason.DEADLINE, waited);
                        }
                        if (interrupted) {
                            // the interrupt stays set for the caller
                            throw refuse(mappedItem, current, PublishRefusedException.Reason.INTERRUPTED, waited);
                        }
                    }
                    } finally {
                        depth[0]--;
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
        String description;
        try {
            description = String.valueOf(mappedItem);
        } catch (Throwable t) {
            description = mappedItem.getClass().getName() + " (toString failed: " + t + ")";
        }
        long refusalsForQueue;
        long refusalsForFeed;
        synchronized (publishLock) {
            refusalsForFeed = ++refusedCount;
            QueueDrops binding = queueDrops.get(queue);
            refusalsForQueue = binding == null ? 0 : ++binding.refusals;
            if (binding != null) {
                increment(binding.refusalCounter);
                if (waitedNanos > 0) {
                    recordWait(binding, waitedNanos);
                }
                // a refusal at shutdown is reported to the caller and is not a health event: the server is stopping
                if (reason != PublishRefusedException.Reason.SHUTDOWN) {
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
        return new PublishRefusedException(name, queue.name(), reason, waitedNanos, description);
    }

    /** Commit and dispatch an already mapped item: sequence number, journal, event log, offers. Under the lock. */
    private void publishMapped(Object itemToPublish, Object mappedItem) {
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
        int[] depth = DISPATCH_DEPTH.get();
        depth[0]++;
        try {
            synchronized (publishLock) {
                if (!startComplete) {
                    cacheSerialised(item);
                    return;
                }
                if (blockingTargets == 0) {
                    publishSerialised(item);
                    return;
                }
            }
        } finally {
            depth[0]--;
        }
        publishWithOverload(item);
    }

    public void cache(T itemToCache) {
        if (itemToCache == null) {
            log.fine("itemToCache is null");
            return;
        }
        yieldToSubscriberChanges();
        int[] depth = DISPATCH_DEPTH.get();
        depth[0]++;
        try {
            synchronized (publishLock) {
                cacheSerialised(itemToCache);
            }
        } finally {
            depth[0]--;
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
        int[] depth = DISPATCH_DEPTH.get();
        depth[0]++;
        try {
        synchronized (publishLock) {
            for (int i = 0, targetQueuesSize = targetQueues.size(); i < targetQueuesSize; i++) {
                NamedQueue namedQueue = targetQueues.get(i);
                OneToOneConcurrentArrayQueue<Object> targetQueue = namedQueue.targetQueue();
                if (!targetQueue.offer(record)) recordDrop(namedQueue, -1, "replay offer full; no retry");
                if (log.isLoggable(Level.FINE)) {
                    log.fine("queue:" + namedQueue.name() + " size:" + targetQueue.size());
                }
            }
        }
        } finally {
            depth[0]--;
        }
    }

    /**
     * The owning source's start is complete: replay what was cached before it to the target queues, and stop adding
     * published items to the event log (unless {@link #setRetainEventLogAfterStart(boolean) retained}). Items given to
     * {@link #cache(Object)} afterwards are still logged and replayed on the next publish or dispatch.
     */
    public void startComplete() {
        yieldToSubscriberChanges();
        int[] depth = DISPATCH_DEPTH.get();
        depth[0]++;
        try {
            synchronized (publishLock) {
                warnCacheReplayOnBlockingFeed();
                dispatchCachedEventLogSerialised();
                startComplete = true;
            }
        } finally {
            depth[0]--;
        }
    }

    public void dispatchCachedEventLog() {
        yieldToSubscriberChanges();
        int[] depth = DISPATCH_DEPTH.get();
        depth[0]++;
        try {
            synchronized (publishLock) {
                warnCacheReplayOnBlockingFeed();
                dispatchCachedEventLogSerialised();
            }
        } finally {
            depth[0]--;
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
        /** This binding's resolved overload policy; written under the publish lock. */
        OverloadSettings settings = OverloadSettings.DEFAULT;
        long refusals;
        // ---- M3 (#79): loss state, counters, recording marks; written under the publish lock ----
        OverloadState state = OverloadState.UP;
        long lastLossNanos;
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
                    return true;
                });
            }
        } finally {
            subscriberChangesWaiting.decrementAndGet();
        }
        wakeWaiters();
    }
}
