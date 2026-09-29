package com.telamin.mongoose.replay;

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
    private final List<Cursor> cursors = new ArrayList<>();
    private final List<String> adminReplies = new CopyOnWriteArrayList<>();
    /** L2: what each replayed processor sent to its sinks, captured instead of delivered (D8). */
    private final java.util.Map<String, List<Object>> outputs = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<DataFlow, java.util.Map<com.telamin.fluxtion.runtime.service.Service<?>,
            com.telamin.fluxtion.runtime.service.Service<?>>> captured = new java.util.IdentityHashMap<>();

    private static final class Cursor {
        final String name;
        final DataFlow flow;
        final ReplayClock clock;
        final List<ReplayEntry> entries;
        int next;
        String stopped;
        /** While the current entry cannot be delivered yet: since when (System.nanoTime) and why. */
        long waitingSince;
        String waitingFor;

        Cursor(String name, DataFlow flow, ReplayClock clock, List<ReplayEntry> entries) {
            this.name = name;
            this.flow = flow;
            this.clock = clock;
            this.entries = entries;
        }

        boolean done() {
            return stopped != null || next >= entries.size();
        }
    }

    public GroupReplayer(ReplayConfig config, ReplayRouting routing, ReplayScheduler scheduler) {
        this.config = config;
        this.routing = routing;
        this.scheduler = scheduler;
    }

    public ReplayScheduler scheduler() {
        return scheduler;
    }

    /** A processor joins the group: when it is replayed, its clock is pinned by the replay. */
    public void attach(String name, DataFlow flow) {
        if (!config.covers(name)) return;
        ReplayClock clock = new ReplayClock();
        flow.setClockStrategy(clock);
        scheduler.replay(flow);
        cursors.add(new Cursor(name, flow, clock, config.store().entries(name)));
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
        if (cursor == null || !(service.instance() instanceof com.telamin.fluxtion.runtime.output.MessageSink<?>)) return service;
        String name = cursor.name;
        return captured.computeIfAbsent(flow, f -> new java.util.IdentityHashMap<>()).computeIfAbsent(service,
                s -> new com.telamin.fluxtion.runtime.service.Service(new CapturingSink(outputs.computeIfAbsent(name,
                        n -> new CopyOnWriteArrayList<>())), s.serviceClass(), s.serviceName()));
    }

    /** What {@code processor} sent to its sinks during the replay, in order (L2: captured, not delivered). */
    public List<Object> outputs(String processor) {
        return List.copyOf(outputs.getOrDefault(processor, List.of()));
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
            try {
                delivered = deliver(c, c.entries.get(c.next));
            } catch (RuntimeException e) {
                // a replay that cannot deliver an entry stops that processor's replay and says why; it does not
                // take the group (or the server) down with it
                delivered = stop(c, "entry " + c.next + " could not be replayed: " + e);
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
                stop(c, "entry " + c.next + " could not be delivered within " + config.deliveryTimeout().toMillis()
                        + " ms: " + c.waitingFor);
            }
        }
        return work;
    }

    public boolean complete() {
        return cursors.stream().allMatch(Cursor::done);
    }

    public List<String> adminReplies() {
        return List.copyOf(adminReplies);
    }

    /** Why a processor's replay stopped early, or null. */
    public String stopped(String processor) {
        for (Cursor c : cursors) {
            if (c.name.equals(processor)) return c.stopped;
        }
        return null;
    }

    private boolean deliver(Cursor c, ReplayEntry entry) {
        switch (entry) {
            case ReplayEntry.Indexed i -> {
                ReplayRoute route = routing.routeFor(i.source(), c.flow);
                if (route == null) return waiting(c, "no route delivers " + i.source() + " to " + c.name);
                byte[] bytes = config.journal().get(i.source(), i.seq());
                if (bytes == null) return stop(c, "the journal holds no " + i.source() + "#" + i.seq());
                Object item = config.journalledFeeds().get(i.source()).decode(bytes);
                EventSource.EventWrapStrategy wrap = routing.wrapOf(i.source());
                Object event = wrap == EventSource.EventWrapStrategy.SUBSCRIPTION_NAMED_EVENT
                        || wrap == EventSource.EventWrapStrategy.BROADCAST_NAMED_EVENT
                        ? new NamedFeedEventImpl<>(i.source()).data(item).sequenceNumber(i.seq())
                        : item;
                pin(c, i.reads());
                route.replayTo(c.flow, event);
                return readsMatch(c, i.reads());
            }
            case ReplayEntry.Inline in -> {
                ReplayRoute route = routing.routeFor(in.source(), c.flow);
                if (route == null) return waiting(c, "no route delivers " + in.source() + " to " + c.name);
                pin(c, in.reads());
                route.replayTo(c.flow, rewrapped(in.source(), in.event(), in.seq()));
                return readsMatch(c, in.reads());
            }
            case ReplayEntry.TimerFired t -> {
                pin(c, t.reads());
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
                pin(c, a.reads());
                route.replayTo(c.flow, new AdminCommand(template, request));
                return readsMatch(c, a.reads());
            }
            case ReplayEntry.Failed f -> {
                return stop(c, "the recorded run failed here: " + f.description());
            }
        }
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
     * The cycle read the clock as often as the recorded one did, or this is a divergence, reported by stopping. One
     * recorded reading that went unused is not: a cycle that read nothing is recorded as one reading, the instant.
     */
    private boolean readsMatch(Cursor c, List<Long> recorded) {
        int taken = c.clock.taken();
        boolean matches = taken == recorded.size() || (recorded.size() == 1 && taken == 0);
        if (matches) return true;
        return stop(c, "clock divergence: the replayed cycle read the clock " + taken + " time(s), the recorded one "
                + recorded.size());
    }

    /** The entry's clock readings, played back in order: its processTime first, then any later reads in its cycle. */
    private void pin(Cursor c, List<Long> reads) {
        scheduler.setNow(reads.get(0));
        c.clock.play(reads);
    }

    private boolean stop(Cursor c, String why) {
        c.stopped = why;
        log.warning("replay of " + c.name + " stopped at entry " + c.next + ": " + why);
        return true;
    }
}
