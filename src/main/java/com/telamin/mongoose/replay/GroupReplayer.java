package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.event.NamedFeedEventImpl;
import com.telamin.mongoose.dutycycle.EventQueueToEventProcessorAgent;
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
public final class GroupReplayer {

    private static final Logger log = Logger.getLogger(GroupReplayer.class.getName());

    private final ReplayConfig config;
    private final ReplayRouting routing;
    private final ReplayScheduler scheduler;
    private final List<Cursor> cursors = new ArrayList<>();
    private final List<String> adminReplies = new CopyOnWriteArrayList<>();

    private static final class Cursor {
        final String name;
        final DataFlow flow;
        final ReplayClock clock;
        final List<ReplayEntry> entries;
        int next;
        String stopped;

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
        cursors.add(new Cursor(name, flow, clock, config.store().entries(name)));
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
                work++;
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
                EventQueueToEventProcessorAgent route = routing.routeFor(i.source(), c.flow);
                if (route == null) return false;                          // not subscribed yet
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
            }
            case ReplayEntry.Inline in -> {
                EventQueueToEventProcessorAgent route = routing.routeFor(in.source(), c.flow);
                if (route == null) return false;
                pin(c, in.reads());
                route.replayTo(c.flow, in.event());
            }
            case ReplayEntry.TimerFired t -> {
                pin(c, t.reads());
                scheduler.fire(c.flow, t.seq());
            }
            case ReplayEntry.AdminInvoked a -> {
                AdminCommand template = routing.adminCommand(a.command());
                EventQueueToEventProcessorAgent route = routing.routeFor("adminCommand." + a.command(), c.flow);
                if (template == null || route == null) return false;      // not registered yet
                AdminCommandRequest request = new AdminCommandRequest();
                request.setCommand(a.command());
                request.setArguments(a.args());
                request.setOutput(o -> adminReplies.add(c.name + ": " + o));
                request.setErrOutput(o -> adminReplies.add(c.name + " err: " + o));
                pin(c, a.reads());
                route.replayTo(c.flow, new AdminCommand(template, request));
            }
            case ReplayEntry.Failed f -> {
                return stop(c, "the recorded run failed here: " + f.description());
            }
        }
        return true;
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
