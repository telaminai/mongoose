package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.mongoose.dispatch.ProcessorContext;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;

/**
 * RECORD mode's scheduler (R4): fires as the live one does, and numbers each schedule call per processor, in the
 * processor's own count. A replay makes the same calls in the same order, so the numbers repeat. When a timer fires the
 * processor's clock is armed, the action runs in the scheduling processor's context (so a timer it schedules is
 * numbered as its own), and {@code TimerFired} is appended at the instant the processor read.
 */
public class RecordingScheduler extends DeadWheelScheduler {

    private final GroupRecorder recorder;

    public RecordingScheduler(GroupRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public long scheduleAtTime(long expireTime, Runnable action) {
        return super.scheduleAtTime(expireTime, wrap(action));
    }

    @Override
    public long scheduleAfterDelay(long waitTime, Runnable action) {
        return super.scheduleAfterDelay(waitTime, wrap(action));
    }

    private Runnable wrap(Runnable action) {
        DataFlow flow = ProcessorContext.currentProcessor();
        long seq = recorder.nextTimerSeq(flow);
        if (seq < 0) return action;                     // not a recorded processor's timer
        return () -> {
            DataFlow outer = ProcessorContext.currentProcessor();
            ProcessorContext.setCurrentProcessor(flow);
            recorder.beforeTimer(flow);
            try {
                action.run();
            } finally {
                recorder.timerFired(flow, seq);
                if (outer == null) ProcessorContext.removeCurrentProcessor();
                else ProcessorContext.setCurrentProcessor(outer);
            }
        };
    }
}
