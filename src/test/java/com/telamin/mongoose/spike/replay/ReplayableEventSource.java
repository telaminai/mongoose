package com.telamin.mongoose.spike.replay;

import com.telamin.fluxtion.runtime.event.ReplayRecord;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;

/** SPIKE: an in-memory feed that can also publish a recorded input back onto its queues, unwrapped, as a ReplayRecord. */
public class ReplayableEventSource extends InMemoryEventSource<Object> {
    public void replay(ReplayRecord record) {
        output.publishReplay(record);
    }
}
