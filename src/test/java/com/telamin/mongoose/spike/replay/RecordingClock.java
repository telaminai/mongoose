package com.telamin.mongoose.spike.replay;

import com.telamin.fluxtion.runtime.time.ClockStrategy;

import java.util.function.LongSupplier;

/**
 * SPIKE: a processor's clock that stays LIVE, and captures the instant the processor used for each input.
 *
 * <p>Pinning the clock to a recorded instant makes a replay exact, but changes production: a live read later in the
 * cycle, or between cycles, stops moving. It is not needed. The processor's {@code Clock} auditor reads the strategy
 * once when an input arrives, before any node runs ({@code Clock.eventReceived}: {@code processTime =
 * getWallClockTime()}). So the dispatcher {@link #arm arms} this clock just before it dispatches, and the first read
 * after that is the input's {@code processTime}: captured, and returned unchanged. Every other read is live.
 */
public final class RecordingClock implements ClockStrategy {

    private final LongSupplier live;
    private boolean armed;
    private long captured;
    private boolean caught;

    public RecordingClock(LongSupplier live) {
        this.live = live;
    }

    @Override
    public long getWallClockTime() {
        long now = live.getAsLong();
        if (armed) {
            captured = now;
            caught = true;
            armed = false;
        }
        return now;
    }

    /** Before a dispatch: the next read is the input's instant. */
    public void arm() {
        armed = true;
        caught = false;
    }

    /** After a dispatch: the instant the processor read for the input. */
    public long captured() {
        if (!caught) throw new IllegalStateException("the processor did not read its clock for this input");
        return captured;
    }
}
