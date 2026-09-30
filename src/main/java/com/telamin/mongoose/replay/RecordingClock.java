package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;
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
@Experimental
public final class RecordingClock implements ClockStrategy {
    private final LongSupplier live;
    private boolean recording;
    private final List<Long> reads = new ArrayList<>();
    /** Set by a ReplayRecord input: the clock then holds that instant, as the strategy's synthetic clock would. */
    private boolean pinned;
    private long pinnedTime;

    public RecordingClock(LongSupplier live) {
        this.live = live;
    }

    /**
     * Hold {@code time} from now on, as a {@code ReplayRecord} input's synthetic clock holds it for a processor that is
     * not recorded (until the next one); the processor's reads of it are still recorded.
     */
    public void pin(long time) {
        pinned = true;
        pinnedTime = time;
    }

    private long now() {
        return pinned ? pinnedTime : live.getAsLong();
    }

    @Override
    public long getWallClockTime() {
        long now = now();
        if (recording) reads.add(now);
        return now;
    }

    public void arm() {
        recording = true;
        reads.clear();
    }

    /**
     * The readings the processor took since {@link #arm}, exactly: none when it read no clock (a call outside an event
     * cycle). The count is what a replay checks, so it is never padded (review of 90f0d9b, finding 6); the instant the
     * input was handled at is {@link #instant}.
     */
    public List<Long> captured() {
        recording = false;
        return List.copyOf(reads);
    }

    /** The instant of a cycle whose readings are {@code captured}: its first reading, or now when it took none. */
    public long instant(List<Long> captured) {
        return captured.isEmpty() ? now() : captured.get(0);
    }
}
