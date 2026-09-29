package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.time.ClockStrategy;

import java.util.function.LongSupplier;

/**
 * A processor's clock that stays live, and captures the instant the processor used for each input (D3). The
 * processor's {@code Clock} auditor reads its strategy once when an input arrives, before any node runs; the dispatcher
 * {@link #arm arms} this clock just before it dispatches, so the first read after that is the input's
 * {@code processTime}. Every read returns the live time.
 */
public final class RecordingClock implements ClockStrategy {
    private final LongSupplier live;
    private boolean armed;
    private boolean caught;
    private long captured;

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

    public void arm() {
        armed = true;
        caught = false;
    }

    /** The processor's read for the input just dispatched; or, if it made none (a call outside an event cycle), now. */
    public long capturedOrNow() {
        armed = false;
        return caught ? captured : live.getAsLong();
    }
}
