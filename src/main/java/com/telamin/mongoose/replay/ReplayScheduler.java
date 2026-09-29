package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.mongoose.dispatch.ProcessorContext;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * REPLAY mode's scheduler (R4): numbers schedule calls per processor exactly as {@link RecordingScheduler} did, and
 * never fires by itself: nothing is armed on the timer wheel, so the wheel it inherits stays empty, and the replay fires
 * {@code seq} when it reaches {@code TimerFired{seq}}. Its time is the replay's.
 */
public class ReplayScheduler extends DeadWheelScheduler {

    private final Map<DataFlow, Map<Long, Runnable>> actions = new IdentityHashMap<>();
    private final Map<DataFlow, long[]> seqs = new IdentityHashMap<>();
    private volatile long now;

    /** The replay's time: the instant of the entry being replayed. */
    public void setNow(long instant) {
        this.now = instant;
    }

    @Override
    public long scheduleAtTime(long expireTime, Runnable action) {
        return register(action);
    }

    @Override
    public long scheduleAfterDelay(long waitTime, Runnable action) {
        return register(action);
    }

    private long register(Runnable action) {
        DataFlow flow = ProcessorContext.currentProcessor();
        if (flow == null) return -1;                    // not a processor's timer: it never fires in a replay
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
        ProcessorContext.setCurrentProcessor(flow);
        try {
            action.run();
        } finally {
            ProcessorContext.removeCurrentProcessor();
        }
    }

    @Override
    public long milliTime() {
        return now;
    }

    @Override
    public long microTime() {
        return now * 1000;
    }

    @Override
    public long nanoTime() {
        return now * 1_000_000;
    }
}
