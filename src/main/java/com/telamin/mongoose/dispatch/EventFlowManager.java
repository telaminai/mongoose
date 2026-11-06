/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dispatch;

import com.telamin.mongoose.dutycycle.EventQueueToEventProcessor;
import com.telamin.mongoose.dutycycle.EventQueueToEventProcessorAgent;
import com.telamin.mongoose.internal.NoOpCountersService;
import com.telamin.mongoose.replay.ReplayConfig;
import com.telamin.mongoose.service.*;
import com.telamin.mongoose.service.counters.MongooseCountersService;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.function.Supplier;


/**
 * Manages the flow of events between event sources, sinks, and subscribers. This class is responsible
 * for registering event sources and sinks, managing subscriptions, and ensuring proper interaction
 * between event components through queues.
 * <p>
 * The {@code EventFlowManager} class supports operations for:
 * - Registering event sources that publish events.
 * - Registering event sinks to receive published events.
 * - Subscribing and unsubscribing to specific event flows.
 * - Managing the lifecycle of registered event sources.
 * - Creating mapping agents for specific event source and subscriber combinations.
 * - Logging and diagnosing the configuration of the event queues.
 * <p>
 * Thread Safety:
 * This class leverages {@link ConcurrentHashMap} for thread-safe
 * management of event sources, sinks, and subscriptions. Methods are designed to be thread-safe
 * for concurrent operations in a multi-threaded environment.
 * <p>
 * Key Responsibilities:
 * - Lifecycle events: Initialize and start registered event sources during their lifecycle.
 * - Event source registration: Allows the addition of event sources and their corresponding publishers.
 * - Event sink registration: Maps event sinks and readers for receiving event data.
 * - Subscription management: Handles event source subscriptions and unsubscriptions for a given key.
 * - Mapping agents: Creates mapping agents that manage the processing of events from sources to subscribers.
 * - Queue diagnostics: Appends configurations of event queues for debugging and tracing.
 */
public class EventFlowManager {

    private final ConcurrentHashMap<EventSourceKey<?>, EventSource_QueuePublisher<?>> eventSourceToQueueMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<EventSinkKey<?>, ManyToOneConcurrentArrayQueue<?>> eventSinkToQueueMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CallBackType, Supplier<EventToInvokeStrategy>> eventToInvokerFactoryMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<EventSourceKey_Subscriber<?>, OneToOneConcurrentArrayQueue<Object>> subscriberKeyToQueueMap = new ConcurrentHashMap<>();
    /**
     * Per subscriber queue, by identity, the items its readers have finished dispatching (docs/design/feed-caught-up.md):
     * compared with the queue position a feed status was reported at, it says the processors have drained the feed.
     */
    private final java.util.Map<OneToOneConcurrentArrayQueue<Object>, java.util.concurrent.atomic.AtomicLong> dispatchedByQueue =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());
    /**
     * mongoose#55 review: how many group readers each subscription key has, across all groups. A source is told to
     * unsubscribe a key only when the last of them is released, not when one group's last listener leaves. The count
     * object is also the lock that orders a release against a new reader for the same key.
     */
    private final ConcurrentHashMap<EventSubscriptionKey<?>, ReaderCount> readersByKey = new ConcurrentHashMap<>();
    // Counters service — default no-op, swapped to the Agrona impl by MongooseServer
    // during construction before any source registration happens. The reference
    // settles to the chosen impl before any publisher / agent loop reads it, so
    // hot-path call sites stay monomorphic and the no-op JIT-inlines away.
    private MongooseCountersService countersService = NoOpCountersService.INSTANCE;
    private com.telamin.mongoose.service.health.MongooseHealthService healthService;
    private final ConcurrentHashMap<EventSourceKey_Subscriber<?>, QueueDropCheck> queueDropChecks = new ConcurrentHashMap<>();
    /** Overload policies by feed name (telaminai/mongoose#79), applied to the feed's publisher when it registers. */
    private final ConcurrentHashMap<String, FeedOverloadSettings> overloadByFeed = new ConcurrentHashMap<>();

    /**
     * Set the overload policy of feed {@code sourceName} (telaminai/mongoose#79): applied now if the feed is registered,
     * else when it registers. Overrides are keyed by subscriber group (the subscribing agent's role name).
     */
    public void setFeedOverload(String sourceName, FeedOverloadSettings settings) {
        Objects.requireNonNull(sourceName, "sourceName must be non-null");
        Objects.requireNonNull(settings, "settings must be non-null");
        overloadByFeed.put(sourceName, settings);
        EventSource_QueuePublisher<?> p = eventSourceToQueueMap.get(new EventSourceKey<>(sourceName));
        if (p != null) {
            p.queuePublisher().setOverload(settings);
        }
    }

    /** Bind before subscriptions are created, like the counters service. */
    public void setHealthService(com.telamin.mongoose.service.health.MongooseHealthService healthService) {
        this.healthService = healthService;
    }

    private static final Logger LOG = Logger.getLogger(EventFlowManager.class.getName());
    private final AtomicBoolean stopped = new AtomicBoolean();

    public EventFlowManager() {
        eventToInvokerFactoryMap.put(CallBackType.ON_EVENT_CALL_BACK, EventToOnEventInvokeStrategy::new);
    }

    private ReplayConfig replayConfig = ReplayConfig.OFF;

    /** Set before any event source registers (spec-replay-recording R1). */
    public void setReplayConfig(ReplayConfig replayConfig) {
        this.replayConfig = replayConfig == null ? ReplayConfig.OFF : replayConfig;
    }

    public ReplayConfig getReplayConfig() {
        return replayConfig;
    }

    /** How {@code sourceName}'s publisher wraps its items, or null when no such source is registered. */
    public EventSource.EventWrapStrategy wrapStrategyOf(String sourceName) {
        EventSource_QueuePublisher<?> p = eventSourceToQueueMap.get(new EventSourceKey<>(sourceName));
        return p == null ? null : p.queuePublisher().getEventWrapStrategy();
    }

    /**
     * Bind the counters service. Called once by {@link com.telamin.mongoose.MongooseServer}
     * during its constructor, before any feed / sink / processor wiring. The
     * default before this call is the no-op service, so any pre-binding access
     * (typically only test harnesses) still returns a valid handle.
     */
    public void setCountersService(MongooseCountersService countersService) {
        this.countersService = Objects.requireNonNull(countersService, "countersService must be non-null");
    }

    public MongooseCountersService getCountersService() {
        return countersService;
    }

    /**
     * Sample the depth of every per-subscriber dispatch queue and write the
     * value to the {@code queue.{path}.depth} gauge on the supplied counters
     * service. Called from a monitoring sampler tick — once per ~1 s — so
     * the cost of {@link OneToOneConcurrentArrayQueue#size()} (a producer/
     * consumer position read) doesn't multiply across the hot path.
     *
     * <p>Queue path is rendered as
     * {@code /feed/{sourceName}/subscriber/{subscriberType}#{identityHashCode}}
     * so each (source, subscriber) pair gets a unique stable label. This
     * lets the front-end correlate gauges across ticks without needing the
     * subscriber's full identity in the wire payload.
     */
    public void sampleQueueDepths(MongooseCountersService countersService) {
        if (countersService == null || !countersService.isOperational()) return;
        subscriberKeyToQueueMap.forEach((key, queue) -> {
            String path = queuePathFor(key);
            countersService.queueDepthGauge(path).setOrdered(queue.size());
        });
    }

    private static String queuePathFor(EventSourceKey_Subscriber<?> key) {
        String src = key.eventSourceKey().sourceName();
        Object sub = key.subscriber();
        // When the subscriber is an Agrona Agent (which is the common case in
        // Mongoose — ComposingEventProcessorAgent / ComposingServiceAgent both
        // are), embed roleName() so downstream consumers (svc-admin-web's
        // Throughput card) can identify the consuming agent group without an
        // extra lookup. Falls back to the class name when the subscriber
        // isn't an Agent.
        String group = (sub instanceof org.agrona.concurrent.Agent agent)
                ? agent.roleName()
                : sub.getClass().getSimpleName();
        String subRef = sub.getClass().getSimpleName() + "#" + Integer.toHexString(System.identityHashCode(sub));
        return "/feed/" + src + "/group/" + group + "/subscriber/" + subRef;
    }

    public void init() {
        forEachLifeCycleEventSource(LifeCycleEventSource::init);
    }

    public void start() {
        forEachLifeCycleEventSource(LifeCycleEventSource::start);
    }

    /** Let the server admit and cancel each source's startup independently of the others. */
    public void start(java.util.function.Consumer<LifeCycleEventSource> startSource) {
        forEachLifeCycleEventSource(startSource);
    }

    /**
     * Stop and tear down every registered {@link LifeCycleEventSource} (mongoose#56), once: a second call is a no-op. A
     * source that throws is logged and the others still stop. Called by the server's ordered shutdown after the agent
     * threads hosting feeds have closed, so an agent-hosted source is not stopped under its own running duty cycle.
     */
    public void stop() {
        // first, and on every call: a publish waiting for room in a BLOCK_WITH_DEADLINE queue is refused rather than
        // left parked, so a source's stop that joins its publishing thread returns (#79)
        cancelBlockedPublishes();
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        forEachLifeCycleEventSource(source -> {
            try {
                source.stop();
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "event source " + source + " failed to stop", t);
            }
            try {
                source.tearDown();
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "event source " + source + " failed to tear down", t);
            }
        });
    }

    /**
     * Refuse every publish waiting for room in a BLOCK_WITH_DEADLINE queue, now and from now on (telaminai/mongoose#79).
     * Idempotent; never waits for a dispatch.
     */
    private volatile OverloadKillSwitch killSwitch;

    /** The hook for never-drop losses on feeds with the kill switch on (#79 D5); null removes it. */
    public void setOverloadKillSwitch(OverloadKillSwitch killSwitch) {
        this.killSwitch = killSwitch;
        eventSourceToQueueMap.values().forEach(p -> p.queuePublisher().setKillSwitch(killSwitch));
    }

    public OverloadKillSwitch overloadKillSwitch() {
        return killSwitch;
    }

    /** Feeds whose overload settings turn the kill switch on. */
    public java.util.Set<String> killSwitchFeeds() {
        java.util.Set<String> feeds = new java.util.TreeSet<>();
        overloadByFeed.forEach((feed, s) -> { if (s.killSwitchOnLoss()) feeds.add(feed); });
        return feeds;
    }

    /**
     * Operator clear of feed {@code sourceName}'s overload health (#79 D4): every DEGRADED or LATCHED queue returns to
     * UP. Returns the number of queues cleared, or -1 when no such feed is registered.
     */
    public int clearOverloadState(String sourceName) {
        EventSource_QueuePublisher<?> p = eventSourceToQueueMap.get(new EventSourceKey<>(sourceName));
        if (p == null) return -1;
        int cleared = p.queuePublisher().clearOverloadState();
        // health checks kept only for latched queues that have since gone away are closed now
        for (EventSourceKey_Subscriber<?> key : queueDropChecks.keySet()) {
            if (!key.eventSourceKey().sourceName().equals(sourceName)) continue;
            queueDropChecks.computeIfPresent(key, (k, check) -> {
                if (!check.bindings.isEmpty()) return check;
                check.handle.close();
                return null;
            });
        }
        return cleared;
    }

    /** Per feed and queue, the overload state: for the admin {@code overload.status} command. */
    public java.util.Map<String, java.util.Map<String, OverloadState>> overloadStates() {
        java.util.Map<String, java.util.Map<String, OverloadState>> states = new java.util.TreeMap<>();
        eventSourceToQueueMap.forEach((key, p) -> {
            java.util.Map<String, OverloadState> queues = new java.util.TreeMap<>();
            for (EventToQueuePublisher.NamedQueue q : p.queuePublisher().getTargetQueues()) {
                OverloadState s = p.queuePublisher().overloadState(q.name());
                if (s != null) queues.put(q.name(), s);
            }
            // queues removed while latched: still LATCHED until an operator clears them (finding 4)
            for (String removed : p.queuePublisher().removedLatches()) {
                queues.put(removed + " [removed]", OverloadState.LATCHED);
            }
            states.put(key.sourceName(), queues);
        });
        return states;
    }

    /** Diagnostic overload state by feed/binding for one processor group, including removed latches. */
    public java.util.Map<String, java.util.Map<String, String>> groupOverloadStates(String group) {
        java.util.Map<String, java.util.Map<String, String>> result = new java.util.TreeMap<>();
        eventSourceToQueueMap.forEach((key, publisher) -> {
            var states = publisher.queuePublisher().groupOverloadStates(group);
            if (!states.isEmpty()) result.put(key.sourceName(), states);
        });
        return java.util.Map.copyOf(result);
    }

    /** Per feed, the overload override groups that have never bound a subscriber queue (review finding 6). */
    public java.util.Map<String, java.util.Set<String>> unboundOverloadOverrides() {
        java.util.Map<String, java.util.Set<String>> unbound = new java.util.TreeMap<>();
        eventSourceToQueueMap.forEach((key, p) -> {
            java.util.Set<String> groups = p.queuePublisher().unboundOverrideGroups();
            if (!groups.isEmpty()) unbound.put(key.sourceName(), groups);
        });
        return unbound;
    }

    public void cancelBlockedPublishes() {
        eventSourceToQueueMap.values().forEach(p -> p.queuePublisher().cancelBlockedPublishes());
    }

    /**
     * Wait, at most {@code timeoutMillis}, for every per-subscriber dispatch queue to empty (mongoose#56): the drain step
     * of the ordered shutdown, run once feeds have stopped and while processors still consume.
     *
     * @return true when every queue emptied in time, false when the bound was reached first
     */
    public boolean awaitQueuesDrained(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
        while (pendingQueuedEvents() > 0) {
            if (System.nanoTime() - deadline >= 0) {
                return false;
            }
            Thread.sleep(1);
        }
        return true;
    }

    /** Events still waiting in the per-subscriber dispatch queues, summed over all queues. */
    public long pendingQueuedEvents() {
        long pending = 0;
        for (OneToOneConcurrentArrayQueue<Object> queue : subscriberKeyToQueueMap.values()) {
            pending += queue.size();
        }
        return pending;
    }

    // ---- processing checkpoints (docs/design/feed-delivery.md, section 5) ----
    private final ConcurrentHashMap<String, Checkpoint> checkpoints = new ConcurrentHashMap<>();
    /** Per physical subscriber queue, by identity, its checkpoint binding; also the lock for its binding count. */
    private final java.util.Map<OneToOneConcurrentArrayQueue<Object>, CheckpointBinding> queueBindings =
            new java.util.IdentityHashMap<>();

    /**
     * The final commit of a clean stop (docs/design/feed-delivery.md section 4): each feed whose committer allows it
     * acknowledges what the processor groups finished after its source stopped. Called by the ordered shutdown once
     * the queues have drained and again once the processor groups have closed. A failure is logged, never thrown.
     */
    public void commitProcessedAfterStop() {
        eventSourceToQueueMap.forEach((key, p) -> {
            try {
                p.queuePublisher().pollAcknowledgementsAfterStop();
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "feed " + key.sourceName() + ": final commit after stop failed", t);
            }
        });
    }

    private static String checkpointKey(String feed, String group) {
        return feed + '\u0000' + group;
    }

    private static final java.util.concurrent.atomic.AtomicLong CHECKPOINT_BINDINGS = new java.util.concurrent.atomic.AtomicLong();

    /**
     * One physical queue's durable state for a checkpoint (review F3): its own dispatched counter and durable mark, so a
     * mark is in the units of the queue it was taken on, a resubscribe (a new queue) starts from nothing durable, and
     * the queue going away drops it. One per physical queue, shared by every callback binding of the group that reads
     * the queue, counted by {@code bindings}: a second binding does not replace it and one binding leaving does not
     * drop it while another still reads the queue (review 2, finding 5).
     */
    private static final class CheckpointBinding {
        final long id = CHECKPOINT_BINDINGS.incrementAndGet();
        final java.util.concurrent.atomic.AtomicLong dispatched;
        final java.util.concurrent.atomic.AtomicLong durable = new java.util.concurrent.atomic.AtomicLong();
        /** Subscription bindings reading the queue; guarded by {@link #queueBindings}. */
        int bindings;

        CheckpointBinding(java.util.concurrent.atomic.AtomicLong dispatched) {
            this.dispatched = dispatched;
        }
    }

    /** A (feed, processor group) acknowledgement hold; unheld, acknowledgement follows the queue's dispatched count. */
    private static final class Checkpoint implements com.telamin.mongoose.service.delivery.ProcessingCheckpoint {
        final String feed;
        final String group;
        volatile CheckpointBinding binding;
        volatile boolean held;

        Checkpoint(String feed, String group) {
            this.feed = feed;
            this.group = group;
        }

        /** How far {@code b}'s queue may be acknowledged. */
        long processed(CheckpointBinding b) {
            long dispatchedNow = b.dispatched.get();
            return held ? Math.min(dispatchedNow, b.durable.get()) : dispatchedNow;
        }

        @Override public String feedName() { return feed; }

        @Override public String processorGroup() { return group; }

        @Override
        public Mark mark() {
            CheckpointBinding b = binding;
            return b == null ? new Mark(-1, 0) : new Mark(b.id, b.dispatched.get());
        }

        @Override
        public void durableThrough(Mark mark) {
            CheckpointBinding b = binding;
            if (mark != null && b != null && b.id == mark.binding()) {
                b.durable.accumulateAndGet(mark.count(), Math::max);
            }
        }

        @Override
        public void release() {
            held = false;
        }

        @Override
        public String toString() {
            CheckpointBinding b = binding;
            return "ProcessingCheckpoint[" + feed + "/" + group + " held=" + held + " durable="
                    + (b == null ? "unbound" : b.durable.get()) + "]";
        }
    }

    /**
     * Hold acknowledgement of {@code feedName}'s items on {@code processorGroup}'s queue until the processor says its
     * state is durable (docs/design/feed-delivery.md, section 5). Registered as the server's
     * {@link com.telamin.mongoose.service.delivery.ProcessingCheckpointService}.
     */
    public com.telamin.mongoose.service.delivery.ProcessingCheckpoint processingCheckpoint(String feedName,
                                                                                           String processorGroup) {
        Objects.requireNonNull(feedName, "feedName must be non-null");
        Objects.requireNonNull(processorGroup, "processorGroup must be non-null");
        Checkpoint checkpoint = checkpoints.computeIfAbsent(checkpointKey(feedName, processorGroup),
                k -> new Checkpoint(feedName, processorGroup));
        checkpoint.held = true;
        return checkpoint;
    }

    /**
     * The status of the named feeds, or of every registered feed when {@code feedNames} is empty, for a host deciding
     * whether its feeds have settled (docs/design/feed-caught-up.md). A feed is drained when each of its subscriber
     * queues has dispatched at least as many items as the queue had been given when the status was reported: a position
     * comparison, never "queue empty", which races with a dispatch in flight. A queue added without a reader of this
     * manager is judged by its consumer position instead, which counts an item taken but still being dispatched.
     *
     * @throws IllegalArgumentException naming a feed that is not registered
     */
    public com.telamin.mongoose.service.feedstatus.FeedStatusReport feedStatus(java.util.Collection<String> feedNames) {
        java.util.TreeMap<String, EventToQueuePublisher<?>> selected = new java.util.TreeMap<>();
        if (feedNames == null || feedNames.isEmpty()) {
            eventSourceToQueueMap.forEach((key, p) -> selected.put(key.sourceName(), p.queuePublisher()));
        } else {
            for (String feed : feedNames) {
                EventSource_QueuePublisher<?> p = eventSourceToQueueMap.get(new EventSourceKey<>(feed));
                if (p == null) throw new IllegalArgumentException("no such feed: " + feed);
                selected.put(feed, p.queuePublisher());
            }
        }
        java.util.List<com.telamin.mongoose.service.feedstatus.FeedStatusReport.Feed> feeds = new java.util.ArrayList<>();
        selected.forEach((feed, publisher) -> {
            // the position first, then the dispatched counts: they only grow, so a count read after the position that
            // reaches it proves every item published before the report was dispatched
            EventToQueuePublisher.FeedPosition position = publisher.feedPosition();
            java.util.List<com.telamin.mongoose.service.feedstatus.FeedStatusReport.Subscriber> subscribers = new java.util.ArrayList<>();
            boolean drained = !position.cachedPending();
            for (EventToQueuePublisher.NamedQueue q : publisher.getTargetQueues()) {
                long at = position.positionOf(q.targetQueue());
                java.util.concurrent.atomic.AtomicLong counter = dispatchedByQueue.get(q.targetQueue());
                long done = counter != null ? counter.get() : q.targetQueue().removedCount();
                boolean queueDrained = done >= at;
                drained &= queueDrained;
                subscribers.add(new com.telamin.mongoose.service.feedstatus.FeedStatusReport.Subscriber(q.name(), at, done,
                        queueDrained));
            }
            var status = position.status();
            var delivery = new com.telamin.mongoose.service.feedstatus.FeedStatusReport.Delivery(
                    publisher.overload().feedDefault().policy().name(), publisher.droppedCount(),
                    publisher.refusedCount(), publisher.downgradedCount(), publisher.paused(), publisher.pausedCount(),
                    publisher.acknowledgedSequence(), publisher.pendingAcknowledgements(), publisher.liveConsumers(),
                    publisher.stallReason());
            feeds.add(new com.telamin.mongoose.service.feedstatus.FeedStatusReport.Feed(feed, status.state(),
                    status.bounded(), status.sequence(), position.version(), status.detail(), drained, delivery,
                    subscribers));
        });
        return com.telamin.mongoose.service.feedstatus.FeedStatusReport.of(feeds);
    }

    @SuppressWarnings("unchecked")
    public <T> ManyToOneConcurrentArrayQueue<T> registerEventSink(EventSourceKey<T> sinkKey, Object sinkReader) {
        Objects.requireNonNull(sinkKey, "sinkKey must be non-null");
        EventSinkKey<T> eventSinkKey = new EventSinkKey<>(sinkKey, sinkReader);
        return (ManyToOneConcurrentArrayQueue<T>) eventSinkToQueueMap.computeIfAbsent(
                eventSinkKey,
                key -> new ManyToOneConcurrentArrayQueue<T>(1024));
    }

    @SuppressWarnings("unchecked")
    public void subscribe(EventSubscriptionKey<?> subscriptionKey) {
        Objects.requireNonNull(subscriptionKey, "subscriptionKey must be non-null");

        EventSource_QueuePublisher<?> eventSourceQueuePublisher = eventSourceToQueueMap.get(subscriptionKey.eventSourceKey());
        Objects.requireNonNull(eventSourceQueuePublisher, "no EventSource registered for EventSourceKey:" + subscriptionKey);
        eventSourceQueuePublisher.eventSource().subscribe((EventSubscriptionKey) subscriptionKey);
    }

    @SuppressWarnings("unchecked")
    public void unSubscribe(EventSubscriptionKey<?> subscriptionKey) {
        Objects.requireNonNull(subscriptionKey, "subscriptionKey must be non-null");

        EventSource_QueuePublisher<?> eventSourceQueuePublisher = eventSourceToQueueMap.get(subscriptionKey.eventSourceKey());
        Objects.requireNonNull(eventSourceQueuePublisher, "no EventSource registered for EventSourceKey:" + subscriptionKey);
        eventSourceQueuePublisher.eventSource().unSubscribe((EventSubscriptionKey) subscriptionKey);
    }

    /**
     * A group released its reader for {@code subscriptionKey} (its last listener left). The source is unsubscribed,
     * through {@link #unSubscribe(EventSubscriptionKey)}, only when no group has a reader for the key any more, so one
     * group leaving a feed does not cut off another group still subscribed to it. A release with no reader counted (a
     * reader not made by {@link #getMappingAgent}) is treated as the last one.
     * <p>
     * The decrement and the source call are atomic with respect to a new reader for the same key: a group subscribing
     * the key concurrently either keeps the count above zero, or makes its reader, and so its source subscribe, after
     * this unsubscribe has completed. An exception from the source propagates, with the release already counted.
     */
    public void releaseReader(EventSubscriptionKey<?> subscriptionKey) {
        Objects.requireNonNull(subscriptionKey, "subscriptionKey must be non-null");
        ReaderCount count = readersByKey.computeIfAbsent(subscriptionKey, k -> new ReaderCount());
        synchronized (count) {
            if (count.readers > 0) {
                count.readers--;
            }
            if (count.readers == 0) {
                unSubscribe(subscriptionKey);
            }
        }
    }

    /** How many group readers currently exist for {@code subscriptionKey}, across all groups. */
    public int readerCount(EventSubscriptionKey<?> subscriptionKey) {
        ReaderCount count = readersByKey.get(subscriptionKey);
        if (count == null) {
            return 0;
        }
        synchronized (count) {
            return count.readers;
        }
    }

    @SuppressWarnings("unchecked")
    public <T> EventToQueuePublisher<T> registerEventSource(String sourceName, EventSource<T> eventSource) {
        Objects.requireNonNull(eventSource, "eventSource must be non-null");

        EventSource_QueuePublisher<?> eventSourceQueuePublisher = eventSourceToQueueMap.computeIfAbsent(
                new EventSourceKey<>(sourceName),
                eventSourceKey -> new EventSource_QueuePublisher<>(
                        new EventToQueuePublisher<>(sourceName, countersService.feedPublishCounter(sourceName)),
                        eventSource));

        EventToQueuePublisher<T> queuePublisher = (EventToQueuePublisher<T>) eventSourceQueuePublisher.queuePublisher();
        FeedOverloadSettings overload = overloadByFeed.get(sourceName);
        if (overload != null) {
            queuePublisher.setOverload(overload);
        }
        queuePublisher.downgradeCounter(countersService.feedDowngradeCounter(sourceName));
        queuePublisher.setKillSwitch(killSwitch);
        // R3: a journalled feed's items are journalled once, and carry their sequence number to every queue
        if (replayConfig.mode() == ReplayConfig.Mode.RECORD && replayConfig.journalled(sourceName)) {
            queuePublisher.journal(replayConfig.journal(), replayConfig.journalledFeeds().get(sourceName));
        }
        eventSource.setEventToQueuePublisher(queuePublisher);
        return queuePublisher;
    }

    public void registerEventMapperFactory(Supplier<EventToInvokeStrategy> eventMapper, CallBackType type) {
        Objects.requireNonNull(eventMapper, "eventMapper must be non-null");
        Objects.requireNonNull(type, "type must be non-null");

        eventToInvokerFactoryMap.put(type, eventMapper);
    }

    public void registerEventMapperFactory(Supplier<EventToInvokeStrategy> eventMapper, Class<?> type) {
        Objects.requireNonNull(eventMapper, "eventMapper must be non-null");
        Objects.requireNonNull(type, "Callback class type must be non-null");

        registerEventMapperFactory(eventMapper, CallBackType.forClass(type));
    }

    public <T> EventQueueToEventProcessor getMappingAgent(EventSourceKey<T> eventSourceKey, CallBackType type, Agent subscriber) {
        Objects.requireNonNull(eventSourceKey, "eventSourceKey must be non-null");
        Objects.requireNonNull(type, "type must be non-null");
        Objects.requireNonNull(subscriber, "subscriber must be non-null");

        Supplier<EventToInvokeStrategy> eventMapperSupplier = eventToInvokerFactoryMap.get(type);
        Objects.requireNonNull(eventMapperSupplier, "no EventMapper registered for type:" + type);

        EventSource_QueuePublisher<T> sourcePublisher = getEventSourceQueuePublisherOrThrow(eventSourceKey);

        // create or re-use a target queue
        EventSourceKey_Subscriber<T> keySubscriber = new EventSourceKey_Subscriber<>(eventSourceKey, subscriber);
        OneToOneConcurrentArrayQueue<Object> eventQueue = getOrCreateSubscriberQueue(keySubscriber);

        // add as a target to the source
        String name = buildSubscriptionName(subscriber, eventSourceKey, type);
        String path = queuePathFor(keySubscriber);
        var drops = countersService.queueDropCounter(path);
        var groupAudit = subscriber instanceof com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent group
                ? group.control().audit() : null;
        EventToQueuePublisher<T> queuePublisher = sourcePublisher.queuePublisher();
        queuePublisher.addTargetQueue(eventQueue, name, drops, groupAudit, subscriber.roleName(), path);
        // #79 M3: overload counters per queue, and in RECORD mode the drop marks its reader turns into Dropped entries
        DropMarks dropMarks = replayConfig.mode() == ReplayConfig.Mode.RECORD ? new DropMarks() : null;
        queuePublisher.bindOverloadExtras(name, countersService.queueRefusalCounter(path),
                countersService.queueBlockCounter(path), countersService.queueBlockedNanosGauge(path),
                countersService.queueBlockedMaxNanosGauge(path), dropMarks);
        if (healthService != null) {
            queueDropChecks.compute(keySubscriber, (key, existing) -> {
                if (existing == null) existing = new QueueDropCheck(healthService.registerCheck(eventSourceKey.sourceName(),
                    "queue-drops:" + path, ctx -> {
                        if (!ctx.countersOperational()) return com.telamin.mongoose.service.health.HealthStatus.unknown("counters disabled");
                        if (!queuePublisher.overloadExplicit()) {
                            // #79 D2 parity: a feed with no policy reports exactly as PR #76 did, from the path's
                            // cumulative drop counter, which survives an unsubscribe and resubscribe (finding 3)
                            long count = drops.get();
                            return count == 0 ? com.telamin.mongoose.service.health.HealthStatus.up()
                                    : com.telamin.mongoose.service.health.HealthStatus.degraded(count + " item(s) dropped by queue " + path);
                        }
                        // #79 D4: never-drop queues latch until an operator clears them; drop-tolerant queues recover
                        // after the feed's quiet period
                        EventToQueuePublisher.OverloadHealth h = queuePublisher.overloadHealth(path);
                        return switch (h.state()) {
                            case UP -> com.telamin.mongoose.service.health.HealthStatus.up();
                            case DEGRADED -> com.telamin.mongoose.service.health.HealthStatus.degraded(h.drops()
                                    + " item(s) dropped by queue " + path);
                            case LATCHED -> com.telamin.mongoose.service.health.HealthStatus.degraded("never-drop queue "
                                    + path + " lost items (" + h.drops() + " dropped, " + h.refusals() + " refused; "
                                    + h.reason() + "); latched until cleared (overload.clear " + eventSourceKey.sourceName() + ")");
                        };
                    }));
                existing.bindings.add(name);
                return existing;
            });
        }

        // a check kept for a latched queue that has come back on another path (its group restarted) is closed (finding 5)
        for (EventSourceKey_Subscriber<?> other : queueDropChecks.keySet()) {
            if (other.equals(keySubscriber) || !other.eventSourceKey().equals(eventSourceKey)) continue;
            queueDropChecks.computeIfPresent(other, (k, check) -> {
                if (!check.bindings.isEmpty() || queuePublisher.latched(queuePathFor(k))) return check;
                check.handle.close();
                return null;
            });
        }

        Runnable unsubscribe = createUnsubscribeAction(sourcePublisher, name, keySubscriber);

        ReaderCount count = readersByKey.computeIfAbsent(new EventSubscriptionKey<>(eventSourceKey, type), k -> new ReaderCount());
        synchronized (count) {
            count.readers++;
        }

        java.util.concurrent.atomic.AtomicLong dispatched =
                dispatchedByQueue.computeIfAbsent(eventQueue, q -> new java.util.concurrent.atomic.AtomicLong());
        // docs/design/feed-delivery.md: an item is acknowledged to its source once this queue has dispatched it, or, for
        // a group holding a processing checkpoint on the feed, once the group's state is durable through it
        Checkpoint checkpoint = checkpoints.computeIfAbsent(checkpointKey(eventSourceKey.sourceName(), subscriber.roleName()),
                k -> new Checkpoint(eventSourceKey.sourceName(), subscriber.roleName()));
        CheckpointBinding binding;
        synchronized (queueBindings) {
            // one binding per physical queue: a second callback type of the group reads the same queue (finding 5)
            binding = queueBindings.computeIfAbsent(eventQueue, q -> new CheckpointBinding(dispatched));
            binding.bindings++;
        }
        checkpoint.binding = binding;
        queuePublisher.bindAcknowledgementPoint(eventQueue, () -> checkpoint.processed(binding));
        Runnable unsubscribeAndUnbind = () -> {
            boolean lastOfQueue;
            synchronized (queueBindings) {
                lastOfQueue = --binding.bindings == 0;
                if (lastOfQueue) queueBindings.remove(eventQueue, binding);
            }
            // the queue itself stays the group's queue for the feed while another of its bindings reads it
            unsubscribe.run();
            if (lastOfQueue) {
                subscriberKeyToQueueMap.remove(keySubscriber, eventQueue);
                dispatchedByQueue.remove(eventQueue);
                // the queue is gone: its durable state goes with it; a resubscribe starts a new binding (review F3)
                if (checkpoint.binding == binding) checkpoint.binding = null;
            }
        };
        var reader = new EventQueueToEventProcessorAgent(eventQueue, eventMapperSupplier.get(), name, eventSourceKey.sourceName(),
                type.name()).withUnsubscribeAction(unsubscribeAndUnbind).withDropMarks(dropMarks)
                .withDispatchedCounter(dispatched);
        queuePublisher.bindContainment(name, () -> reader.failureFenced()
                || (subscriber instanceof com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent group
                    && group.control().state() == com.telamin.mongoose.control.ControlDriver.GroupState.DEAD),
                reader::failureFenceVersion);
        return reader;
    }

    public <T> EventQueueToEventProcessor getMappingAgent(EventSubscriptionKey<T> subscriptionKey, Agent subscriber) {
        return getMappingAgent(subscriptionKey.eventSourceKey(), subscriptionKey.callBackType(), subscriber);
    }

    public void appendQueueInformation(Appendable appendable) {
        if (eventSourceToQueueMap.isEmpty()) {
            safeAppend(appendable, "No event readers registered");
            return;
        }
        eventSourceToQueueMap.forEach((key, value) -> appendQueueDetails(appendable, key.sourceName(), value.queuePublisher()));
    }

    private void forEachLifeCycleEventSource(java.util.function.Consumer<LifeCycleEventSource> action) {
        eventSourceToQueueMap.values().stream()
                .map(EventSource_QueuePublisher::eventSource)
                .filter(LifeCycleEventSource.class::isInstance)
                .map(LifeCycleEventSource.class::cast)
                .forEach(action);
    }

    /**
     * Returns {@code true} when {@code candidate} has been registered with this
     * manager as an event source (via {@link #registerEventSource(String, EventSource)}).
     * Used by lifecycle plumbing to detect {@code LifeCycleEventSource} services
     * that the {@code LifecycleManager} deliberately skips but the flow manager
     * never picked up either — those would otherwise silently miss their
     * {@code init()} / {@code start()} calls.
     */
    public boolean knowsEventSource(Object candidate) {
        if (candidate == null) return false;
        return eventSourceToQueueMap.values().stream()
                .map(EventSource_QueuePublisher::eventSource)
                .anyMatch(src -> src == candidate);
    }

    private <T> EventSource_QueuePublisher<T> getEventSourceQueuePublisherOrThrow(EventSourceKey<T> eventSourceKey) {
        @SuppressWarnings("unchecked")
        EventSource_QueuePublisher<T> publisher = (EventSource_QueuePublisher<T>) eventSourceToQueueMap.get(eventSourceKey);
        return Objects.requireNonNull(publisher, "no EventSource registered for EventSourceKey:" + eventSourceKey);
    }

    private <T> OneToOneConcurrentArrayQueue<Object> getOrCreateSubscriberQueue(EventSourceKey_Subscriber<T> keySubscriber) {
        return subscriberKeyToQueueMap.computeIfAbsent(keySubscriber, key -> new OneToOneConcurrentArrayQueue<>(1024));
    }

    private static String buildSubscriptionName(Agent subscriber, EventSourceKey<?> eventSourceKey, CallBackType type) {
        return subscriber.roleName() + "/" + eventSourceKey.sourceName() + "/" + type.name();
    }

    private Runnable createUnsubscribeAction(EventSource_QueuePublisher<?> sourcePublisher, String name, EventSourceKey_Subscriber<?> keySubscriber) {
        return () -> {
            // the subscriber queue, its dispatched counter and its checkpoint binding are dropped by the caller once the
            // last binding reading the queue has left (review 2, finding 5)
            sourcePublisher.queuePublisher().removeTargetQueueByName(name);
            queueDropChecks.computeIfPresent(keySubscriber, (key, check) -> {
                check.bindings.remove(name);
                if (!check.bindings.isEmpty()) return check;
                // #79 D4: a latched never-drop queue keeps its health check after it goes away, until an operator clears
                if (sourcePublisher.queuePublisher().latched(queuePathFor(key))) return check;
                check.handle.close();
                return null;
            });
        };
    }

    private static void safeAppend(Appendable appendable, String text) {
        try {
            appendable.append(text);
        } catch (IOException ex) {
            System.err.println("problem logging event queues, exception:" + ex);
        }
    }

    private static void appendQueueDetails(Appendable appendable, String sourceName, EventToQueuePublisher<?> queue) {
        try {
            appendable.append("eventSource:").append(sourceName)
                    .append("\n\treadQueues:\n");
            for (EventToQueuePublisher.NamedQueue q : queue.getTargetQueues()) {
                appendable.append("\t\t").append(q.name()).append(" -> ").append(q.targetQueue().toString()).append("\n");
            }
        } catch (IOException ex) {
            System.err.println("problem logging event queues, exception:" + ex);
        }
    }

    /** Multiple callback bindings can share one physical queue and one health check. Mutated only in map.compute. */
    private static final class QueueDropCheck {
        final com.telamin.mongoose.service.health.MongooseHealthService.HealthCheckHandle handle;
        final java.util.Set<String> bindings = new java.util.HashSet<>();
        QueueDropCheck(com.telamin.mongoose.service.health.MongooseHealthService.HealthCheckHandle handle) { this.handle = handle; }
    }

    private static final class ReaderCount {
        private int readers;
    }

    private record EventSource_QueuePublisher<T>(EventToQueuePublisher<T> queuePublisher, EventSource<T> eventSource) {
    }

    private record EventSourceKey_Subscriber<T>(EventSourceKey<T> eventSourceKey, Object subscriber) {
    }

    private record EventSinkKey<T>(EventSourceKey<T> eventSourceKey, Object subscriber) {
    }
}
