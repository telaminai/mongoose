package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;

import java.util.List;

/** Each processor's entries, in its input order. */
@Experimental
public interface ReplayStore {
    void append(String processor, ReplayEntry entry);

    List<ReplayEntry> entries(String processor);

    /** Whether a stream was recorded for {@code processor} at all: absent is not the same as empty (review of PR 6, 5). */
    default boolean has(String processor) {
        return !entries(processor).isEmpty();
    }

    /** Whether it already holds a recording: RECORD mode refuses one, whose entries a new run would append after. */
    default boolean holdsRecording() {
        return false;
    }
}
