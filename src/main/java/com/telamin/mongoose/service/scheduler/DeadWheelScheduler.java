/*
 * SPDX-FileCopyrightText: © 2024 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 */

package com.telamin.mongoose.service.scheduler;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.annotations.feature.Experimental;
import com.telamin.mongoose.dispatch.ProcessorContext;
import com.telamin.mongoose.service.error.ErrorEvent;
import com.telamin.mongoose.service.error.ErrorReporting;
import org.agrona.DeadlineTimerWheel;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.OffsetEpochNanoClock;

import java.util.concurrent.TimeUnit;

@Experimental
public class DeadWheelScheduler implements SchedulerService, Agent {

    protected final DeadlineTimerWheel timerWheel = new DeadlineTimerWheel(TimeUnit.MILLISECONDS, System.currentTimeMillis(), 1024, 1);
    protected final Long2ObjectHashMap<Runnable> expiryActions = new Long2ObjectHashMap<>();
    /**
     * The processor that scheduled each timer (the {@link ProcessorContext} current processor at the call), so a dropped
     * processor's timers can be cancelled ({@link #cancelTimersOf}) and a failing callback names its processor.
     */
    protected final Long2ObjectHashMap<DataFlow> timerOwners = new Long2ObjectHashMap<>();
    private java.util.function.Function<DataFlow, String> processorNames = flow -> null;
    protected final EpochNanoClock clock;
    // Cached as a field so the agent's hot loop doesn't allocate a fresh
    // TimerHandler lambda per doWork() call — under aggressive idle strategies
    // (e.g. BackoffIdleStrategy) that's millions of allocations/sec and a
    // direct path to OutOfMemoryError on the agent thread.
    private final DeadlineTimerWheel.TimerHandler onTimerExpiryHandler = this::onTimerExpiry;

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
        track(id, expiryAction);
        return id;
    }

    @Override
    public long scheduleAfterDelay(long waitTime, Runnable expiryAction) {
        long id = timerWheel.scheduleTimer(milliTime() + waitTime);
        track(id, expiryAction);
        return id;
    }

    private void track(long id, Runnable expiryAction) {
        expiryActions.put(id, expiryAction);
        DataFlow owner = ProcessorContext.currentProcessor();
        if (owner != null) {
            timerOwners.put(id, owner);
        }
    }

    /**
     * Cancel every pending timer {@code flow} scheduled, so none fires into a processor that has left its group.
     *
     * @return how many timers were cancelled
     */
    public int cancelTimersOf(DataFlow flow) {
        if (flow == null) {
            return 0;
        }
        java.util.List<Long> ids = new java.util.ArrayList<>();
        timerOwners.forEach((id, owner) -> {
            if (owner == flow) ids.add(id);
        });
        for (long id : ids) {
            timerOwners.remove(id);
            expiryActions.remove(id);
            timerWheel.cancelTimer(id);
        }
        return ids.size();
    }

    /** How a failing timer callback's processor is named in its report; set by the processor group. */
    public void setProcessorNames(java.util.function.Function<DataFlow, String> processorNames) {
        this.processorNames = processorNames == null ? flow -> null : processorNames;
    }

    @Override
    public int doWork() {
        return timerWheel.poll(milliTime(), onTimerExpiryHandler, 100);
    }

    @Override
    public String roleName() {
        return "deadWheelScheduler";
    }

    private boolean onTimerExpiry(TimeUnit timeUnit, long now, long timerId) {
        Runnable action = expiryActions.remove(timerId);
        DataFlow owner = timerOwners.remove(timerId);
        if (action == null) {
            return true;
        }
        // the callback runs as its processor, so a timer it schedules is its own too (and cancelled with it)
        DataFlow outer = ProcessorContext.currentProcessor();
        boolean asOwner = owner != null && outer == null;
        if (asOwner) ProcessorContext.setCurrentProcessor(owner);
        // a callback's failure is its processor's, not the agent's (mongoose#57): reported, and the other timers fire.
        // Every Throwable but a VirtualMachineError, which reaches the agent's error handler as before
        try {
            action.run();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable t) {
            String name = owner == null ? null : processorNames.apply(owner);
            String whose = name != null ? "processor '" + name + "'" : owner != null ? "processor " + owner : "no processor";
            ErrorReporting.report("DeadWheelScheduler", "timer callback of " + whose + " failed: " + t, t,
                    ErrorEvent.Severity.ERROR);
        } finally {
            if (asOwner) ProcessorContext.removeCurrentProcessor();
        }
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
