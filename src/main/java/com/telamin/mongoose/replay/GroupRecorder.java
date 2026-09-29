package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.mongoose.service.admin.impl.AdminCommand;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * RECORD mode for one processor group (spec-replay-recording R2, R4, R6). Everything here runs on the group's agent
 * thread: every Mongoose path into a processor does (R7 makes {@code audit.*} do so too), so a processor's entries are
 * appended in its true input order with no locking.
 */
@Experimental
public final class GroupRecorder {

    private static final Logger log = Logger.getLogger(GroupRecorder.class.getName());

    private final ReplayConfig config;
    private final LongSupplier live;
    private final Map<DataFlow, Recorded> byFlow = new IdentityHashMap<>();

    private static final class Recorded {
        final String name;
        final RecordingClock clock;
        long timerSeq;
        /** Why this processor's recording stopped (its store failed), or null while it records. */
        String broken;

        Recorded(String name, RecordingClock clock) {
            this.name = name;
            this.clock = clock;
        }
    }

    public GroupRecorder(ReplayConfig config, LongSupplier live) {
        this.config = config;
        this.live = live;
    }

    /** A processor joins the group: when it is recorded, its clock becomes a live {@link RecordingClock}. */
    public void attach(String name, DataFlow flow) {
        if (!config.covers(name)) return;
        RecordingClock clock = new RecordingClock(live);
        byFlow.put(flow, new Recorded(name, clock));
        flow.setClockStrategy(clock);
    }

    public void detach(DataFlow flow) {
        byFlow.remove(flow);
    }

    /** A ReplayRecord input: pin a recorded processor's clock to its instant; false when {@code flow} is not recorded. */
    public boolean pinSyntheticTime(DataFlow flow, long time) {
        Recorded r = byFlow.get(flow);
        if (r == null) return false;
        r.clock.pin(time);
        return true;
    }

    /** Just before a queue dispatches to {@code targets}. */
    public void beforeDispatch(Collection<DataFlow> targets) {
        for (DataFlow t : targets) {
            Recorded r = byFlow.get(t);
            if (r != null) r.clock.arm();
        }
    }

    /**
     * Just after: one entry per recorded target, at the instant it read. An admin command is recorded by its name and
     * arguments; an input of a journalled feed that carried its sequence number by index; anything else inline.
     */
    public void afterDispatch(String source, Object event, long seq, Collection<DataFlow> targets) {
        for (DataFlow t : targets) {
            Recorded r = byFlow.get(t);
            if (r == null) continue;
            List<Long> reads = r.clock.captured();
            ReplayEntry entry;
            if (event instanceof AdminCommand admin && admin.getArgs() != null && !admin.getArgs().isEmpty()) {
                List<String> args = admin.getArgs();
                entry = new ReplayEntry.AdminInvoked(args.get(0), List.copyOf(args.subList(1, args.size())), reads);
            } else if (seq >= 0 && config.journalled(source)) {
                entry = new ReplayEntry.Indexed(source, seq, reads);
            } else if (event instanceof com.telamin.fluxtion.runtime.event.NamedFeedEvent<?> named) {
                // the item and its number: the wrapper is configuration, rebuilt on replay (and is not serialisable)
                entry = new ReplayEntry.Inline(source, named.data(), named.sequenceNumber(), reads);
            } else {
                entry = new ReplayEntry.Inline(source, event, reads);
            }
            append(r, entry);
        }
    }

    /** A dispatch threw: marked, not reproduced (D4). */
    public void failed(String source, Object event, Throwable error, Collection<DataFlow> targets) {
        for (DataFlow t : targets) {
            Recorded r = byFlow.get(t);
            if (r == null) continue;
            append(r, new ReplayEntry.Failed(source, error + " on " + event, r.clock.captured().get(0)));
        }
    }

    /**
     * Append {@code entry} to {@code r}'s recording. Never throws: a store that fails is a recording failure, not the
     * processor's, and must not reach the agent (whose error handler may end the process). The first failure is logged,
     * a Failed marker is attempted so a replay stops at the gap rather than skipping it, and that processor's recording
     * stops.
     */
    private void append(Recorded r, ReplayEntry entry) {
        if (r.broken != null) return;
        try {
            config.store().append(r.name, entry);
        } catch (Throwable t) {
            r.broken = String.valueOf(t);
            log.severe("replay recording of " + r.name + " stopped: its store failed on " + entry + ": " + t);
            try {
                config.store().append(r.name, new ReplayEntry.Failed("recording", "the recording stopped here: " + t, entry.instant()));
            } catch (Throwable ignored) {
                // the store cannot take the marker either; the log line above is the record
            }
        }
    }

    /** Why {@code processor}'s recording stopped, or null while it records. */
    public String broken(String processor) {
        for (Recorded r : byFlow.values()) {
            if (r.name.equals(processor)) return r.broken;
        }
        return null;
    }

    /** The next schedule number of {@code flow}, or -1 when it is not recorded. */
    public long nextTimerSeq(DataFlow flow) {
        Recorded r = flow == null ? null : byFlow.get(flow);
        return r == null ? -1 : ++r.timerSeq;
    }

    public void beforeTimer(DataFlow flow) {
        Recorded r = byFlow.get(flow);
        if (r != null) r.clock.arm();
    }

    public void timerFired(DataFlow flow, long seq) {
        Recorded r = byFlow.get(flow);
        if (r != null) append(r, new ReplayEntry.TimerFired(seq, r.clock.captured()));
    }

    /** A timer's action threw: marked, as a dispatch that throws is (D4). */
    public void timerFailed(DataFlow flow, long seq, Throwable error) {
        Recorded r = byFlow.get(flow);
        if (r != null) append(r, new ReplayEntry.Failed("timer#" + seq, String.valueOf(error), r.clock.captured().get(0)));
    }
}
