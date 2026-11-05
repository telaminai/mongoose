package com.telamin.mongoose.replay;

import com.telamin.mongoose.control.event.FailureKind;
import com.telamin.mongoose.control.AgentThreads;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.event.NamedFeedEventImpl;
import com.telamin.mongoose.service.EventSource;
import com.telamin.mongoose.service.admin.AdminCommandRequest;
import com.telamin.mongoose.service.admin.impl.AdminCommand;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * REPLAY mode for one processor group (spec-replay-recording R5): one ordered stream per replayed processor, drained on
 * the group's agent thread. Each entry goes to the processor that received it, alone, through the route this group's
 * configuration gives its source, with the processor's clock pinned to the entry's instant. An index is joined with the
 * journal first. A timer fires when the stream reaches it. An admin command is rebuilt from the one the replayed
 * processor registered, with its replies collected. A {@code Failed} entry ends that processor's replay.
 */
@Experimental
public final class GroupReplayer {

    private static final Logger log = Logger.getLogger(GroupReplayer.class.getName());

    private final ReplayConfig config;
    private final ReplayRouting routing;
    private final ReplayScheduler scheduler;
    // Membership changes only on attach/detach; event replay never copies this list.
    private final List<Cursor> cursors = new CopyOnWriteArrayList<>();
    private final List<String> adminReplies = java.util.Collections.synchronizedList(new ArrayList<>());
    /** L2: what each replayed processor sent to its sinks, captured instead of delivered (D8). */
    private final java.util.Map<String, List<Object>> outputs = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<DataFlow, java.util.Map<com.telamin.fluxtion.runtime.service.Service<?>,
            com.telamin.fluxtion.runtime.service.Service<?>>> captured = new java.util.IdentityHashMap<>();

    private static final class Cursor {
        final String name;
        final DataFlow flow;
        final ReplayClock clock;
        /** Empty until the store is read at attach; stays empty when setup failed (the cursor is then stopped). */
        volatile List<ReplayEntry> entries = List.of();
        volatile int next;
        volatile String stopped;
        /** {@link #stopped} with its kind, published together so a reader never pairs a reason with another kind. */
        volatile StopReason stopReason;
        /** Losses the recording names (#79): items dropped before reaching the processor in the recorded run. */
        final List<String> losses = new java.util.concurrent.CopyOnWriteArrayList<>();
        /** While the current entry cannot be delivered yet: since when (System.nanoTime) and why. */
        long waitingSince;
        String waitingFor;
        /** Ended by a recorded Stop: the processor was stopped where the recorded run stopped it. */
        volatile boolean ended;
        /** The stream records its service changes: they are replayed in position and live ones suppressed. */
        volatile boolean recordsServices;
        /** Every service the stream records a change of: those changes reach the processor only from the stream. */
        volatile java.util.Set<String> changedServices = java.util.Set.of();
        /** Services the stream registers before it ever withdraws them: not given at build, but where it has them. */
        volatile java.util.Set<String> deferred = java.util.Set.of();
        /** The clock readings the recorded stop took, checked once the replayed stop has run. */
        List<Long> stopReads;
        /** Control requests the recorded run made before the next input completed: the replayed ones must match. */
        final java.util.ArrayDeque<ReplayEntry.ControlCall> expectedCalls = new java.util.ArrayDeque<>();

        Cursor(String name, DataFlow flow, ReplayClock clock) {
            this.name = name;
            this.flow = flow;
            this.clock = clock;
        }

        volatile boolean attaching = true;

        boolean done() {
            return stopped != null || !attaching && (ended || next >= entries.size());
        }
    }

    private volatile java.util.function.BooleanSupplier ownThread = AgentThreads::isAgentThread;

    /** How to tell the replaying group's own agent thread: only there is a control request in input order. */
    public void onOwnThread(java.util.function.BooleanSupplier ownThread) {
        this.ownThread = ownThread;
    }

    public GroupReplayer(ReplayConfig config, ReplayRouting routing, ReplayScheduler scheduler) {
        this.config = config;
        this.routing = routing;
        this.scheduler = scheduler;
    }

    public ReplayScheduler scheduler() {
        return scheduler;
    }

    /**
     * A processor joins the group: when it is replayed, its clock is pinned by the replay. Runs on the group's agent
     * thread, so NOTHING here may throw out: the agent's default error handler ends the process. The cursor is
     * registered FIRST, so whatever fails after it - installing the replay clock, reading the store - leaves the processor
     * replayed (its live inputs muted, its sinks captured) and stopped with the reason (re-review N1: a processor that
     * refused the ReplayClock escaped before its cursor existed, unmuted, and a real server exited 255).
     */
    public void attach(String name, DataFlow flow) {
        attach(name, 1, flow);
    }

    /** As {@link #attach(String, DataFlow)}, from the stream of the processor's {@code incarnation} under the name. */
    public void attach(String name, int incarnation, DataFlow flow) {
        if (!config.covers(name)) return;
        ReplayClock clock = new ReplayClock();
        Cursor cursor = new Cursor(ReplayStreams.key(name, incarnation), flow, clock);
        int forIncarnation = incarnation;
        cursors.add(cursor);
        scheduler.replay(flow);                          // its timers are the replay's, whatever follows
        String failed = null;
        FailureKind failedKind = FailureKind.REPLAY_STOPPED;
        try {
            flow.setClockStrategy(clock);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            failed = "the replay clock could not be installed: " + t;
        }
        if (failed == null && config.recordProvenance()) {
            try {
                Provenance recorded = config.store().provenance(cursor.name);
                String differs = recorded == null ? null : recorded.differenceFrom(Provenance.of(flow, forIncarnation, 0));
                if (differs != null) {
                    failed = "the replayed processor is not the recorded one: " + differs;
                    failedKind = FailureKind.REPLAY_IDENTITY_MISMATCH;
                }
            } catch (VirtualMachineError e) {
                throw e;
            } catch (Throwable t) {
                failed = "the stream's provenance could not be read: " + t;
            }
        }
        if (failed == null) {
            try {
                String legacyKey = incarnation <= 1 ? name : name + "#" + incarnation;
                if (!cursor.name.equals(legacyKey) && !config.store().has(cursor.name) && config.store().has(legacyKey)) {
                    failed = "ambiguous legacy stream key " + legacyKey + ": migrate the processor name's '#' escapes to "
                            + cursor.name;
                    failedKind = FailureKind.REPLAY_IDENTITY_MISMATCH;
                } else if (forIncarnation > 1 && !config.store().has(cursor.name)) {
                    failed = "no stream was recorded for " + cursor.name;    // loud, never an instantly complete cursor
                    failedKind = FailureKind.REPLAY_IDENTITY_MISMATCH;
                } else {
                    cursor.entries = config.store().entries(cursor.name);
                    cursor.recordsServices = cursor.entries.stream().anyMatch(e -> e instanceof ReplayEntry.ServiceChanged);
                    cursor.changedServices = cursor.entries.stream()
                            .filter(e -> e instanceof ReplayEntry.ServiceChanged)
                            .map(e -> ((ReplayEntry.ServiceChanged) e).service())
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
                    cursor.deferred = firstRegistered(cursor.entries);
                    // requests the processor made while it was built and started (before any input) are expected now:
                    // its start() runs before the cursor is first driven (final review, finding 1)
                    while (cursor.next < cursor.entries.size()
                            && cursor.entries.get(cursor.next) instanceof ReplayEntry.ControlCall cc) {
                        cursor.expectedCalls.add(cc);
                        cursor.next++;
                    }
                }
            } catch (VirtualMachineError e) {
                throw e;
            } catch (Throwable t) {
                failed = "the replay store could not be read: " + t;
            }
        }
        if (failed != null) stop(cursor, failedKind, failed);
        cursor.attaching = false;
    }

    /** A processor that left its group (stopped, mongoose#71, or a failed start, review of #66): its replay cursor and captures are dropped. */
    public void detach(DataFlow flow) {
        for (Cursor c : cursors) {
            if (c.flow != flow) continue;
            // A control refusal can fail start() and immediately detach this processor, before replay drains any
            // entries. Removing the cursor must not erase the refusal and make the settled replay appear successful.
            StopReason reason = c.stopReason;
            if (reason != null) end(c.name, reason.kind(), reason.why(), false);
            if (!c.done()) {
                end(c.name, FailureKind.REPLAY_STOPPED, "left the group at entry " + c.next + " of " + c.entries.size()
                        + " before its replay completed", false);
            }
            if (!c.expectedCalls.isEmpty()) {
                end(c.name, FailureKind.REPLAY_IDENTITY_MISMATCH, "the recorded run made control request " + describe(c.expectedCalls.peek())
                        + " that the replay did not", true);
                log.warning("replay of " + c.name + ": " + ended.get(c.name).why());
            }
            if (c.stopReads == null) continue;
            // the replayed stop has run: it must have read the clock as often as the recorded one did
            int taken = c.clock.taken();
            if (taken != c.stopReads.size()) {
                end(c.name, FailureKind.REPLAY_STOPPED, "clock divergence in the replayed stop: it read the clock " + taken
                        + " time(s), the recorded one " + c.stopReads.size(), true);
                log.warning("replay of " + c.name + ": " + ended.get(c.name).why());
            }
        }
        cursors.removeIf(c -> c.flow == flow);
        captured.remove(flow);
    }

    /** Why a stream that has left the group diverged at its end, by stream name. */
    private final java.util.Map<String, StopReason> ended = new java.util.concurrent.ConcurrentHashMap<>();

    /** A replay stop's kind and reason, as one value. */
    private record StopReason(FailureKind kind, String why) { }

    private void end(String name, FailureKind kind, String why, boolean replace) {
        if (replace) {
            ended.put(name, new StopReason(kind == null ? FailureKind.REPLAY_STOPPED : kind, why));
        } else {
            ended.putIfAbsent(name, new StopReason(kind == null ? FailureKind.REPLAY_STOPPED : kind, why));
        }
    }

    /** The services a stream registers before it withdraws them: the processor had them only from that point. */
    private static java.util.Set<String> firstRegistered(List<ReplayEntry> entries) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.Set<String> deferred = new java.util.HashSet<>();
        for (ReplayEntry e : entries) {
            if (e instanceof ReplayEntry.ServiceChanged sc && seen.add(sc.service()) && sc.register()) deferred.add(sc.service());
        }
        return deferred;
    }

    /**
     * Whether {@code flow} must not be given {@code service} at build (review of PR 6, finding 1): its stream records
     * the service arriving later, so the replay delivers it there, not before the processor starts.
     */
    public boolean defersService(DataFlow flow, String service) {
        for (Cursor c : cursors) {
            if (c.flow == flow) return c.deferred.contains(service);
        }
        return false;
    }

    /**
     * L2 (D8): the service {@code flow} is given. A replayed processor's sinks are replaced by a capture, so what it sends
     * is kept for comparison ({@link #outputs}) and never delivered: a replay must not repeat a run's side effects
     * (orders, messages) or feed another processor what it already received. Any other service, or any other processor,
     * gets {@code service} itself. The same capture is returned for the same service, so it is deregistered too.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public com.telamin.fluxtion.runtime.service.Service<?> serviceFor(DataFlow flow, com.telamin.fluxtion.runtime.service.Service<?> service) {
        Cursor cursor = null;
        for (Cursor c : cursors) {
            if (c.flow == flow) cursor = c;
        }
        if (cursor != null && config.interceptControl() && ControlInterception.isController(service)) {
            Cursor c = cursor;
            return captured.computeIfAbsent(flow, f -> new java.util.IdentityHashMap<>())
                    .computeIfAbsent(service, s -> intercepted(c, s));
        }
        if (cursor == null || !(service.instance() instanceof com.telamin.fluxtion.runtime.output.MessageSink<?>)) return service;
        String name = cursor.name;
        return captured.computeIfAbsent(flow, f -> new java.util.IdentityHashMap<>()).computeIfAbsent(service,
                // a synchronized list, not a CopyOnWriteArrayList: every output is an add, and copying the whole capture
                // on each made a replay quadratic in its length (found by the stress campaign); readers copy it whole,
                // under its lock, through outputs()
                s -> new com.telamin.fluxtion.runtime.service.Service(new CapturingSink(outputs.computeIfAbsent(name,
                        n -> java.util.Collections.synchronizedList(new java.util.ArrayList<>()))), s.serviceClass(),
                        s.serviceName()));
    }

    /**
     * A replayed processor's control requests are never executed (they would change the live server a second time):
     * each is matched against the request the recorded run made at this point and answered with its recorded outcome.
     * A request the recorded run did not make, or made differently, is a divergence: the replay stops, saying so.
     */
    private com.telamin.fluxtion.runtime.service.Service<?> intercepted(Cursor cursor,
                                                                        com.telamin.fluxtion.runtime.service.Service<?> service) {
        return ControlInterception.wrap(service, (method, args, m, raw) -> {
            controlRequests.computeIfAbsent(cursor.name, n -> java.util.Collections.synchronizedList(new ArrayList<>())).add(method + args);
            if (!ownThread.getAsBoolean()) {
                // off the processor's own thread: not part of its input order, so not reproducible (final review, 11)
                stop(cursor, FailureKind.REPLAY_STOPPED, "control request " + method + args + " made off the processor's thread; not executed");
                return null;
            }
            if ("addEventProcessor".equals(method)) {
                // A Supplier is executable construction logic, not a stable description of the generated processor.
                // Until its build identity is recorded, refuse rather than invoke it against the live server.
                String why = "addEventProcessor supplier cannot be verified from the recording; not executed";
                stop(cursor, FailureKind.REPLAY_IDENTITY_MISMATCH, why);
                throw new UnsupportedOperationException(why);
            }
            ReplayEntry.ControlCall expected = cursor.expectedCalls.poll();
            if (expected == null) {
                stop(cursor, FailureKind.REPLAY_IDENTITY_MISMATCH, "control request " + method + args + " was not made by the recorded run here; not executed");
                return null;
            }
            if (!expected.method().equals(method) || !expected.args().equals(args)) {
                stop(cursor, FailureKind.REPLAY_IDENTITY_MISMATCH, "control request differs: recorded " + describe(expected) + ", replayed " + method + args
                        + "; not executed");
                return null;
            }
            if (expected.outcome().startsWith("failed")) {
                // Text does not preserve the original throwable's type or payload. A processor may branch on those,
                // or catch this substitute exception; either way it must not turn an unverifiable replay into success.
                String why = "recorded control exception cannot be reconstructed for " + method + ": " + expected.outcome();
                stop(cursor, FailureKind.REPLAY_STOPPED, why);
                throw new IllegalStateException(why);
            }
            if (ControlInterception.REGISTRY.contains(method)) {
                // The server registry is shared with unreplayed processors. Matching text does not authorize real
                // injection, withdrawal, stop callbacks or feed setup. These require an isolated replay registry.
                String why = "registry mutation " + method + " requires an isolated replay registry; not executed";
                stop(cursor, FailureKind.REPLAY_STOPPED, why);
                throw new UnsupportedOperationException(why);
            }
            return null;
        });
    }

    private static String describe(ReplayEntry.ControlCall call) {
        return call.method() + call.args();
    }

    private final java.util.Map<String, List<String>> controlRequests = new java.util.concurrent.ConcurrentHashMap<>();

    /** The control requests {@code processor} made during the replay, intercepted, in order. */
    public List<String> controlRequests(String processor) {
        return snapshot(controlRequests.getOrDefault(processor, List.of()));
    }

    /** What {@code processor} sent to its sinks during the replay, in order (L2: captured, not delivered). */
    public List<Object> outputs(String processor) {
        return snapshot(outputs.getOrDefault(processor, List.of()));
    }

    /** Copy only when queried; captured event/admin/control outputs append in constant time. */
    private static <T> List<T> snapshot(List<T> values) {
        synchronized (values) { return java.util.Collections.unmodifiableList(new ArrayList<>(values)); }
    }

    /** A sink that keeps what it is given, after the processor's own value mapper, and delivers nothing. */
    private static final class CapturingSink implements com.telamin.fluxtion.runtime.output.MessageSink<Object> {
        private final List<Object> into;
        private java.util.function.Function<Object, ?> mapper = java.util.function.Function.identity();

        CapturingSink(List<Object> into) {
            this.into = into;
        }

        @Override
        public void accept(Object value) {
            into.add(mapper.apply(value));
        }

        @Override
        @SuppressWarnings("unchecked")
        public void setValueMapper(java.util.function.Function<? super Object, ?> valueMapper) {
            this.mapper = (java.util.function.Function<Object, ?>) valueMapper;
        }
    }

    /**
     * Whether a live service change must not reach {@code flow}: it is replayed from a stream that records its service
     * changes, so they are delivered in position instead. A stream recorded without them (an earlier recording) gets
     * live deliveries as before, and is partial in that respect.
     */
    /**
     * Whether a live change of {@code service} must not reach {@code flow}, whatever the replay server's own state: the
     * stream records changes of that service, so they are delivered in position. A replay can run its inputs while its
     * server is still starting, so "after the server started" cannot decide this here (final review, finding 2).
     */
    public boolean suppressesLiveService(DataFlow flow, String service) {
        for (Cursor c : cursors) {
            if (c.flow == flow) return c.changedServices.contains(service);
        }
        return false;
    }

    public boolean suppressesLiveServices(DataFlow flow) {
        for (Cursor c : cursors) {
            if (c.flow == flow) return c.recordsServices;
        }
        return false;
    }

    /** Whether {@code flow} is replayed here: its live inputs are then muted. */
    public boolean replays(DataFlow flow) {
        for (Cursor c : cursors) {
            if (c.flow == flow) return true;
        }
        return false;
    }

    /** One entry per replayed processor per call, so the group's own work (subscriptions) interleaves. */
    public int doWork() {
        int work = 0;
        for (Cursor c : cursors) {
            if (c.done()) continue;
            boolean delivered;
            ReplayEntry entry = c.entries.get(c.next);
            try {
                delivered = deliver(c, entry);
                // an input has been handled: every control request the recorded run made before it must have been made
                if (delivered && c.stopped == null && !c.expectedCalls.isEmpty()
                        && !(entry instanceof ReplayEntry.ControlCall) && !(entry instanceof ReplayEntry.Stopped)
                        && !(entry instanceof ReplayEntry.Dropped)) {
                    stop(c, FailureKind.REPLAY_IDENTITY_MISMATCH, "the recorded run made control request " + describe(c.expectedCalls.peek())
                            + " here; the replay did not");
                }
            } catch (VirtualMachineError e) {
                throw e;
            } catch (Throwable e) {
                // a replay that cannot deliver an entry stops that processor's replay and says why; it does not
                // take the group (or the server) down with it. Any Throwable: a decoder's AssertionError reached the
                // agent's error handler, which ended the process (review of 90f0d9b, finding 1)
                delivered = stop(c, FailureKind.REPLAY_STOPPED, "entry " + c.next + " could not be replayed: " + e);
            }
            if (delivered) {
                c.next++;
                c.waitingSince = 0;
                c.waitingFor = null;
                work++;
            } else if (c.waitingSince == 0) {
                c.waitingSince = System.nanoTime();
            } else if (System.nanoTime() - c.waitingSince > config.deliveryTimeout().toNanos()) {
                // an entry that never becomes deliverable (a route or admin command the replayed configuration never
                // makes) stops the replay, saying why, rather than stalling it silently forever
                stop(c, FailureKind.REPLAY_STOPPED, "entry " + c.next + " could not be delivered within " + config.deliveryTimeout().toMillis()
                        + " ms: " + c.waitingFor);
            }
        }
        return work;
    }

    public boolean complete() {
        return cursors.stream().allMatch(Cursor::done);
    }

    public List<String> adminReplies() {
        return snapshot(adminReplies);
    }

    /**
     * True when no replayed processor's recording has named a loss so far (#79), including processors that have since
     * detached, stopped or been shut down: once false, it stays false. Check it beside {@link #complete()}:
     * a replay can complete and still have reached {@code Dropped} entries, as it can complete with a processor
     * {@link #stopped(String) stopped}. {@link #losses(String)} says what was lost.
     */
    public boolean lossless() {
        return retainedLosses.isEmpty();
    }

    /** Losses reached, by stream; kept after the processor detaches, so lossless() and losses() are monotonic. */
    private final java.util.Map<String, List<String>> retainedLosses = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The losses a processor's recording names, as the replay has reached them (#79): items dropped by a full queue in
     * the recorded run, before the processor received them. Beside {@link #stopped(String)}: a stopped replay ended
     * early; a replay with losses ran to the end of a stream that has gaps the live run also had.
     */
    public List<String> losses(String processor) {
        List<String> losses = retainedLosses.get(processor);
        return losses == null ? List.of() : List.copyOf(losses);
    }


    /** Why a processor's replay stopped early, or null. Human detail: classify with {@link #stopKind}. */
    public String stopped(String processor) {
        for (Cursor c : cursors) {
            if (c.name.equals(processor)) return c.stopped;
        }
        StopReason reason = ended.get(processor);
        return reason == null ? null : reason.why();
    }

    /**
     * The structured kind of {@link #stopped}: {@code REPLAY_IDENTITY_MISMATCH} when the replay could not match an
     * input, stream, service version or control request to the recording, {@code REPLAY_STOPPED} for any other stop,
     * or null when the replay has not stopped.
     */
    public FailureKind stopKind(String processor) {
        for (Cursor c : cursors) {
            if (c.name.equals(processor)) {
                StopReason reason = c.stopReason;
                return reason == null ? null : reason.kind();
            }
        }
        StopReason reason = ended.get(processor);
        return reason == null ? null : reason.kind();
    }

    private boolean deliver(Cursor c, ReplayEntry entry) {
        switch (entry) {
            case ReplayEntry.Indexed i -> {
                ReplayRoute route = routing.routeFor(i.source(), i.route(), c.flow);
                if (route == null) return waiting(c, "no route " + routeName(i.route()) + "delivers " + i.source() + " to " + c.name);
                byte[] bytes = config.journal().get(i.source(), i.seq());
                if (bytes == null) return stop(c, FailureKind.REPLAY_IDENTITY_MISMATCH, "the journal holds no " + i.source() + "#" + i.seq());
                Object item = config.journalledFeeds().get(i.source()).decode(bytes.clone());   // from a copy: G1's contract
                EventSource.EventWrapStrategy wrap = routing.wrapOf(i.source());
                Object event = wrap == EventSource.EventWrapStrategy.SUBSCRIPTION_NAMED_EVENT
                        || wrap == EventSource.EventWrapStrategy.BROADCAST_NAMED_EVENT
                        ? new NamedFeedEventImpl<>(i.source()).data(item).sequenceNumber(i.seq())
                        : item;
                pin(c, i.instant(), i.reads());
                route.replayTo(c.flow, event);
                return readsMatch(c, i.reads());
            }
            case ReplayEntry.Inline in -> {
                ReplayRoute route = routing.routeFor(in.source(), in.route(), c.flow);
                if (route == null) return waiting(c, "no route " + routeName(in.route()) + "delivers " + in.source() + " to " + c.name);
                // a copy, so a replayed handler that changes its input cannot change the recording (finding 2); a
                // recorded NamedFeedEvent is rebuilt with its own fields, whatever the feed's wrap (finding 3); an input
                // held in its feed's codec, or in the journal, is read back through that codec, never another (cd52628 F1)
                Object event = materialised(in.event());
                pin(c, in.instant(), in.reads());
                route.replayTo(c.flow, event instanceof RecordedNamedEvent named ? named.rebuild(materialised(named.data()))
                        : rewrapped(in.source(), event, in.seq()));
                return readsMatch(c, in.reads());
            }
            case ReplayEntry.TimerFired t -> {
                pin(c, t.instant(), t.reads());
                scheduler.fire(c.flow, t.seq());
                return readsMatch(c, t.reads());
            }
            case ReplayEntry.AdminInvoked a -> {
                AdminCommand template = routing.adminCommand(a.command());
                ReplayRoute route = routing.routeFor("adminCommand." + a.command(), c.flow);
                if (template == null) return waiting(c, "admin command " + a.command() + " is not registered");
                if (route == null) return waiting(c, "no route delivers admin command " + a.command() + " to " + c.name);
                AdminCommandRequest request = new AdminCommandRequest();
                request.setCommand(a.command());
                request.setArguments(a.args());
                request.setOutput(o -> adminReplies.add(c.name + ": " + o));
                request.setErrOutput(o -> adminReplies.add(c.name + " err: " + o));
                pin(c, a.instant(), a.reads());
                route.replayTo(c.flow, new AdminCommand(template, request));
                return readsMatch(c, a.reads());
            }
            case ReplayEntry.Failed f -> {
                return stop(c, FailureKind.REPLAY_STOPPED, "the recorded run failed here: " + f.description());
            }
            case ReplayEntry.Dropped d -> {
                // the recorded run lost these items before the processor saw them: named, not reproduced, and the
                // stream goes on (#79)
                c.losses.add(d.description());
                // retained by stream, independently of the cursor, so a detach, stop or shutdown cannot erase it
                // (external review of d9eec79, finding 2)
                retainedLosses.computeIfAbsent(c.name, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                        .add(d.description());
                log.warning("replay of " + c.name + " reached a recorded loss: " + d.description()
                        + "; the replay carries on (the processor never received those items)");
                return true;
            }
            case ReplayEntry.ControlCall cc -> {
                // made while the recorded run handled the input that follows: the replayed request must match it
                c.expectedCalls.add(cc);
                return true;
            }
            case ReplayEntry.Stopped s -> {
                // the recorded run stopped the processor here: so does the replay, through its group's control processor;
                // the clock holds the recorded stop's readings until the stop runs, and they are checked when it has
                try {
                    if (!routing.stopProcessor(c.flow)) return stop(c, FailureKind.REPLAY_STOPPED, "the recorded stop could not be replayed");
                } catch (IllegalStateException mailboxFull) {
                    return waiting(c, "the group's mailbox is full: " + mailboxFull.getMessage());
                }
                pin(c, s.instant(), s.reads());
                c.stopReads = s.reads();
                c.ended = true;
                return true;
            }
            case ReplayEntry.ServiceChanged sc -> {
                pin(c, sc.instant(), sc.reads());
                String outcome = routing.deliverService(c.flow, sc.service(), sc.register(), sc.version());
                if (outcome != null) {
                    if (outcome.startsWith(ReplayRouting.WAIT)) return waiting(c, outcome.substring(ReplayRouting.WAIT.length()));
                    // the routing's protocol prefix, written by the routing itself, says whether identity failed
                    if (outcome.startsWith(ReplayRouting.MISMATCH)) {
                        return stop(c, FailureKind.REPLAY_IDENTITY_MISMATCH, outcome.substring(ReplayRouting.MISMATCH.length()));
                    }
                    return stop(c, FailureKind.REPLAY_STOPPED, outcome);
                }
                return readsMatch(c, sc.reads());
            }
        }
    }

    /**
     * What an Inline entry holds, as the processor is given it: a feed codec's bytes and a journal reference through that
     * feed's codec (fresh each replay, so a replayed handler cannot change the recording); a named event as recorded, its
     * payload resolved when it is rebuilt; anything else copied (InputCopy).
     */
    private Object materialised(Object recorded) {
        if (recorded instanceof EncodedInput encoded) return codecOf(encoded.source()).decode(encoded.bytes());
        if (recorded instanceof JournalRef ref) {
            byte[] bytes = config.journal() == null ? null : config.journal().get(ref.source(), ref.seq());
            if (bytes == null) throw new IllegalStateException("the journal holds no " + ref.source() + "#" + ref.seq());
            return codecOf(ref.source()).decode(bytes.clone());    // a decoder that consumes its input cannot change the journal (G1)
        }
        if (recorded instanceof RecordedNamedEvent) return recorded;
        return InputCopy.of(recorded);
    }

    private EventCodec codecOf(String source) {
        EventCodec codec = config.journalledFeeds().get(source);
        if (codec == null) throw new IllegalStateException("no codec is configured for " + source + ", whose input was recorded in it");
        return codec;
    }

    /** Not deliverable yet (a route or command the configuration makes shortly after boot): wait, and say for what. */
    private static boolean waiting(Cursor c, String reason) {
        c.waitingFor = reason;
        return false;
    }

    /** An inline item of a named-event feed was recorded bare, with its number: rebuilt as the processor received it. */
    private Object rewrapped(String source, Object event, long seq) {
        if (event instanceof com.telamin.fluxtion.runtime.event.NamedFeedEvent<?>) return event;
        EventSource.EventWrapStrategy wrap = routing.wrapOf(source);
        return wrap == EventSource.EventWrapStrategy.SUBSCRIPTION_NAMED_EVENT
                || wrap == EventSource.EventWrapStrategy.BROADCAST_NAMED_EVENT
                ? new NamedFeedEventImpl<>(source).data(event).sequenceNumber(seq)
                : event;
    }

    /**
     * The cycle read the clock exactly as often as the recorded one did, or this is a divergence, reported by stopping.
     * A cycle that read nothing records no reading, so zero to zero matches and a recorded read the replay did not take
     * is a divergence (review of 90f0d9b, finding 6: it used to be accepted as the padded instant).
     */
    private boolean readsMatch(Cursor c, List<Long> recorded) {
        int taken = c.clock.taken();
        if (taken == recorded.size()) return true;
        return stop(c, FailureKind.REPLAY_STOPPED, "clock divergence: the replayed cycle read the clock " + taken + " time(s), the recorded one "
                + recorded.size());
    }

    /** The entry's instant, and its clock readings played back in order: its processTime first, then any later reads. */
    private void pin(Cursor c, long instant, List<Long> reads) {
        scheduler.setNow(instant);
        c.clock.play(instant, reads);
    }

    private static String routeName(String route) {
        return route.isEmpty() ? "" : "(" + route + ") ";
    }

    private boolean stop(Cursor c, FailureKind kind, String why) {
        c.stopReason = new StopReason(kind == null ? FailureKind.REPLAY_STOPPED : kind, why);
        c.stopped = why;
        log.warning("replay of " + c.name + " stopped at entry " + c.next + ": " + why);
        return true;
    }
}
