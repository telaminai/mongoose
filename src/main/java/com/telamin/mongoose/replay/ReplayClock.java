package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;
import com.telamin.fluxtion.runtime.time.ClockStrategy;

import java.util.List;

/**
 * A replayed processor's clock: the replay driver plays each entry's recorded readings back in order; once they are
 * used up, the last is held. It counts the reads, so the driver can tell a cycle that read the clock a different number
 * of times from the recorded one: a divergence, which it reports.
 */
@Experimental
public final class ReplayClock implements ClockStrategy {
    private volatile List<Long> reads = List.of(0L);
    private int next;
    private int taken;

    @Override
    public long getWallClockTime() {
        List<Long> r = reads;
        taken++;
        return next < r.size() ? r.get(next++) : r.get(r.size() - 1);
    }

    public void play(List<Long> readings) {
        this.next = 0;
        this.taken = 0;
        this.reads = readings;
    }

    /** How many times the processor read the clock since the last {@link #play}. */
    public int taken() {
        return taken;
    }
}
