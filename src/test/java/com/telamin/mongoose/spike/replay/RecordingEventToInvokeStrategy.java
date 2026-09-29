package com.telamin.mongoose.spike.replay;

import com.telamin.fluxtion.runtime.event.ReplayRecord;
import com.telamin.mongoose.dispatch.EventToOnEventInvokeStrategy;

import java.util.List;
import java.util.function.LongSupplier;

/**
 * SPIKE: record every event a queue dispatches to its processors, at the dispatch point, as a {@link ReplayRecord}.
 *
 * <p>An event the graph raises itself re-enters its processor's own {@code onEvent} and never passes this strategy, so
 * nothing here tells inputs from graph-raised events: everything recorded is an input by construction.
 *
 * <p>The time: the processor must use exactly the recorded instant, or a replay cannot reproduce it. So the strategy
 * picks the instant and dispatches with it, through Mongoose's existing {@code processEvent(event, time)} (the path a
 * {@code ReplayRecord} takes), which drives the processor's clock. A replay then drives the same clock the same way.
 */
public class RecordingEventToInvokeStrategy extends EventToOnEventInvokeStrategy {

    private final List<ReplayRecord> recorded;
    private final LongSupplier wallClock;

    public RecordingEventToInvokeStrategy(List<ReplayRecord> recorded, LongSupplier wallClock) {
        this.recorded = recorded;
        this.wallClock = wallClock;
    }

    /**
     * True while the base class dispatches a timed event: its {@code processEvent(event, time)} sets the processors'
     * clocks and then calls the overridable {@code processEvent(event)}, which must then be the base loop, not a second
     * recording (found by the spike: without this it recursed until the stack overflowed, and every event was dropped).
     */
    private boolean dispatching;

    @Override
    public void processEvent(Object event) {
        if (dispatching) {
            super.processEvent(event);
            return;
        }
        processEvent(event, wallClock.getAsLong());
    }

    @Override
    public void processEvent(Object event, long time) {
        ReplayRecord r = new ReplayRecord();
        r.setEvent(event);
        r.setWallClockTime(time);
        recorded.add(r);
        dispatching = true;
        try {
            super.processEvent(event, time);
        } finally {
            dispatching = false;
        }
    }
}
