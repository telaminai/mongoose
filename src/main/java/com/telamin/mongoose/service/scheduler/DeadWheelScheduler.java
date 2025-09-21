/*
 * SPDX-FileCopyrightText: © 2024 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 */

package com.telamin.mongoose.service.scheduler;

import com.fluxtion.agrona.DeadlineTimerWheel;
import com.fluxtion.agrona.collections.Long2ObjectHashMap;
import com.fluxtion.agrona.concurrent.Agent;
import com.fluxtion.agrona.concurrent.EpochNanoClock;
import com.fluxtion.agrona.concurrent.OffsetEpochNanoClock;
import com.telamin.fluxtion.runtime.annotations.feature.Experimental;

import java.util.concurrent.TimeUnit;

@Experimental
public class DeadWheelScheduler implements SchedulerService, Agent {

    protected final DeadlineTimerWheel timerWheel = new DeadlineTimerWheel(TimeUnit.MILLISECONDS, System.currentTimeMillis(), 1024, 1);
    protected final Long2ObjectHashMap<Runnable> expiryActions = new Long2ObjectHashMap<>();
    protected final EpochNanoClock clock;

    public DeadWheelScheduler() {
        this(new OffsetEpochNanoClock());
    }

    public DeadWheelScheduler(EpochNanoClock clock) {
        this.clock = clock;
        timerWheel.currentTickTime(clock.nanoTime());
    }

    @Override
    public long scheduleAtTime(long expireTIme, Runnable expiryAction) {
        long id = timerWheel.scheduleTimer(expireTIme);
        expiryActions.put(id, expiryAction);
        return id;
    }

    @Override
    public long scheduleAfterDelay(long waitTime, Runnable expiryAction) {
        long id = timerWheel.scheduleTimer(milliTime() + waitTime);
        expiryActions.put(id, expiryAction);
        return id;
    }

    @Override
    public int doWork() {
        return timerWheel.poll(milliTime(), this::onTimerExpiry, 100);
    }

    @Override
    public String roleName() {
        return "deadWheelScheduler";
    }

    private boolean onTimerExpiry(TimeUnit timeUnit, long now, long timerId) {
        expiryActions.remove(timerId).run();
        return true;
    }

    @Override
    public long milliTime() {
        long millisToNanos = clock.nanoTime() / 1_000_000;//TimeUnit.NANOSECONDS.toMillis(clock.nanoTime());
        return millisToNanos;
    }

    @Override
    public long microTime() {
        return TimeUnit.NANOSECONDS.toMicros(clock.nanoTime());
    }

    @Override
    public long nanoTime() {
        return clock.nanoTime();
    }

    public void setcurrentTickTime(long now) {
        timerWheel.currentTickTime(now);
    }
}
