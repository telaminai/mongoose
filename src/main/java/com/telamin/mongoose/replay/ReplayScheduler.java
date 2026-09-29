package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;
import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.mongoose.dispatch.ProcessorContext;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * REPLAY mode's scheduler (R4). For a REPLAYED processor it numbers schedule calls exactly as {@link RecordingScheduler}
 * did and never fires by itself (the replay fires {@code seq} when it reaches {@code TimerFired{seq}}), and its time is
 * the replay's. Everything else in the group (a processor that is not replayed, or a call outside any processor) gets
 * the live scheduler it inherits: armed on the wheel, fired by the group's duty cycle, on live time.
 */
@Experimental
public class ReplayScheduler extends DeadWheelScheduler {

    private final Map<DataFlow, Map<Long, Runnable>> actions = new IdentityHashMap<>();
    private final Map<DataFlow, long[]> seqs = new IdentityHashMap<>();
    private final java.util.Set<DataFlow> replayed = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private volatile long now;

    /** {@code flow} is replayed: its timers are the replay's, and its time. */
    public void replay(DataFlow flow) {
        replayed.add(flow);
    }

    private boolean replaying() {
        DataFlow flow = ProcessorContext.currentProcessor();
        return flow != null && replayed.contains(flow);
    }

    /** The replay's time: the instant of the entry being replayed. */
    public void setNow(long instant) {
        this.now = instant;
    }

    @Override
    public long scheduleAtTime(long expireTime, Runnable action) {
        return replaying() ? register(action) : super.scheduleAtTime(expireTime, action);
    }

    @Override
    public long scheduleAfterDelay(long waitTime, Runnable action) {
        return replaying() ? register(action) : super.scheduleAfterDelay(waitTime, action);
    }

    private long register(Runnable action) {
        DataFlow flow = ProcessorContext.currentProcessor();
        long seq = ++seqs.computeIfAbsent(flow, f -> new long[1])[0];
        actions.computeIfAbsent(flow, f -> new HashMap<>()).put(seq, action);
        return seq;
    }

    /** Fire the timer {@code flow} scheduled as {@code seq}; refused if the replayed run never scheduled it. */
    public void fire(DataFlow flow, long seq) {
        Map<Long, Runnable> byFlow = actions.get(flow);
        Runnable action = byFlow == null ? null : byFlow.remove(seq);
        if (action == null) {
            throw new IllegalStateException("the replay fired timer " + seq + ", which the replayed processor never scheduled");
        }
        DataFlow outer = ProcessorContext.currentProcessor();
        ProcessorContext.setCurrentProcessor(flow);
        try {
            action.run();
        } finally {
            if (outer == null) ProcessorContext.removeCurrentProcessor();
            else ProcessorContext.setCurrentProcessor(outer);
        }
    }

    /** The replay's time for a replayed processor; live time for everything else, the wheel's own poll included. */
    @Override
    public long milliTime() {
        return replaying() ? now : super.milliTime();
    }

    @Override
    public long microTime() {
        return replaying() ? now * 1000 : super.microTime();
    }

    @Override
    public long nanoTime() {
        return replaying() ? now * 1_000_000 : super.nanoTime();
    }
}
