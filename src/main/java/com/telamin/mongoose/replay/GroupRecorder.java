package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.mongoose.service.admin.impl.AdminCommand;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * RECORD mode for one processor group (spec-replay-recording R2, R4, R6). Everything here runs on the group's agent
 * thread: every Mongoose path into a processor does (R7 makes {@code audit.*} do so too), so a processor's entries are
 * appended in its true input order with no locking.
 */
public final class GroupRecorder {

    private final ReplayConfig config;
    private final LongSupplier live;
    private final Map<DataFlow, Recorded> byFlow = new IdentityHashMap<>();

    private static final class Recorded {
        final String name;
        final RecordingClock clock;
        long timerSeq;

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
            } else {
                entry = new ReplayEntry.Inline(source, event, reads);
            }
            config.store().append(r.name, entry);
        }
    }

    /** A dispatch threw: marked, not reproduced (D4). */
    public void failed(String source, Object event, Throwable error, Collection<DataFlow> targets) {
        for (DataFlow t : targets) {
            Recorded r = byFlow.get(t);
            if (r == null) continue;
            config.store().append(r.name, new ReplayEntry.Failed(source, error + " on " + event, r.clock.captured().get(0)));
        }
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
        if (r != null) config.store().append(r.name, new ReplayEntry.TimerFired(seq, r.clock.captured()));
    }
}
