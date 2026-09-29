package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.time.ClockStrategy;

import java.util.List;

/**
 * A replayed processor's clock: the replay driver plays each entry's recorded readings back in order; once they are
 * used up, the last is held (a build that reads more often than the recorded one is diverging anyway).
 */
public final class ReplayClock implements ClockStrategy {
    private volatile List<Long> reads = List.of(0L);
    private int next;

    @Override
    public long getWallClockTime() {
        List<Long> r = reads;
        return next < r.size() ? r.get(next++) : r.get(r.size() - 1);
    }

    public void play(List<Long> readings) {
        this.next = 0;
        this.reads = readings;
    }
}
