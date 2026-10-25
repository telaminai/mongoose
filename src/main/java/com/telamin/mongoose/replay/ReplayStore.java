package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;

import java.util.List;

/** Each processor's entries, in its input order. */
@Experimental
public interface ReplayStore {
    void append(String processor, ReplayEntry entry);

    List<ReplayEntry> entries(String processor);

    /** Record how {@code processor}'s stream began, beside its entries (never among them). */
    default void began(String processor, Provenance provenance) {
    }

    /** How {@code processor}'s stream began, or null when it was recorded without provenance. */
    default Provenance provenance(String processor) {
        return null;
    }

    /** Whether a stream was recorded for {@code processor} at all: absent is not the same as empty (review of PR 6, 5). */
    default boolean has(String processor) {
        return !entries(processor).isEmpty() || provenance(processor) != null;
    }

    /** Whether it already holds a recording: RECORD mode refuses one, whose entries a new run would append after. */
    default boolean holdsRecording() {
        return false;
    }
}
