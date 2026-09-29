package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.time.ClockStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * A processor's clock that stays live, and records every reading the processor takes while it handles an input (D3).
 * The dispatcher {@link #arm arms} it just before it dispatches and {@link #captured takes} the readings just after.
 * The first is the input's {@code processTime} (the processor's {@code Clock} auditor reads before any node runs); the
 * rest are any later reads in the same cycle, such as an event the graph raised itself. Every read returns the live
 * time, so production is unchanged.
 */
public final class RecordingClock implements ClockStrategy {
    private final LongSupplier live;
    private boolean recording;
    private final List<Long> reads = new ArrayList<>();

    public RecordingClock(LongSupplier live) {
        this.live = live;
    }

    @Override
    public long getWallClockTime() {
        long now = live.getAsLong();
        if (recording) reads.add(now);
        return now;
    }

    public void arm() {
        recording = true;
        reads.clear();
    }

    /** The readings the processor took since {@link #arm}; if it took none (a call outside an event cycle), now. */
    public List<Long> captured() {
        recording = false;
        return reads.isEmpty() ? List.of(live.getAsLong()) : List.copyOf(reads);
    }
}
