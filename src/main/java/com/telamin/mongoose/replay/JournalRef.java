package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;

import java.io.Serializable;

/**
 * A payload that stays in the feed's journal: {@code (source, seq)}, resolved through the feed's codec on replay. Held by a
 * {@link RecordedNamedEvent} whose payload the processor received as the journal holds it, so the wrapper's own fields are
 * recorded while the item is stored once (review of cd52628, F2: recorded as a bare index, the wrapper was rebuilt fresh,
 * with the replay's time). A journal that lacks the item stops the replay there, by name.
 */
@Experimental
public record JournalRef(String source, long seq) implements Serializable { }
