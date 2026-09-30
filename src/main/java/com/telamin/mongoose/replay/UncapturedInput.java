package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;

/**
 * Given to the recorder, in place of an input, by a strategy that cannot say what each processor received (re-review
 * N4): the recording then marks that input Failed, naming why, and a replay stops there. Live dispatch is not affected.
 */
@Experimental
public record UncapturedInput(String reason) { }
