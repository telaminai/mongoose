package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.time.ClockStrategy;

/** A replayed processor's clock: pinned, by the replay driver, to each entry's instant. */
public final class ReplayClock implements ClockStrategy {
    private volatile long now;

    @Override
    public long getWallClockTime() {
        return now;
    }

    public void pin(long instant) {
        now = instant;
    }
}
