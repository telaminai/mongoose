package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;

import java.util.List;

/** Each processor's entries, in its input order. */
@Experimental
public interface ReplayStore {
    void append(String processor, ReplayEntry entry);

    List<ReplayEntry> entries(String processor);

    /** Whether it already holds a recording: RECORD mode refuses one, whose entries a new run would append after. */
    default boolean holdsRecording() {
        return false;
    }
}
