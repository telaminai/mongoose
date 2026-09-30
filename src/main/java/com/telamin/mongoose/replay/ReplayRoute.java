package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;

import com.telamin.fluxtion.runtime.DataFlow;

/** How a replay delivers a recorded input: to one processor, as its source's queue delivered it live (R5). */
@Experimental
public interface ReplayRoute {
    void replayTo(DataFlow target, Object event);
}
