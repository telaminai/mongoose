package com.telamin.mongoose.replay;

import java.util.Map;
import java.util.Set;

/**
 * Record or replay processors' inputs (spec-replay-recording R1). {@link Mode#OFF}, the default, changes nothing.
 *
 * @param mode             off, record, or replay
 * @param processors       the processors recorded (RECORD) or replayed (REPLAY); empty means every processor
 * @param journalledFeeds  the feeds whose items are journalled, each with its codec; their inputs are recorded as indexes
 * @param journal          where journalled items are written (RECORD) and read (REPLAY)
 * @param store            where each processor's entries are appended (RECORD) and read (REPLAY)
 */
public record ReplayConfig(Mode mode, Set<String> processors, Map<String, EventCodec> journalledFeeds,
                           EventJournal journal, ReplayStore store) {

    public enum Mode { OFF, RECORD, REPLAY }

    public static final ReplayConfig OFF = new ReplayConfig(Mode.OFF, Set.of(), Map.of(), null, null);

    public ReplayConfig {
        processors = processors == null ? Set.of() : Set.copyOf(processors);
        journalledFeeds = journalledFeeds == null ? Map.of() : Map.copyOf(journalledFeeds);
        if (mode != Mode.OFF && store == null) throw new IllegalArgumentException("replay mode " + mode + " needs a store");
        if (!journalledFeeds.isEmpty() && journal == null) throw new IllegalArgumentException("journalled feeds need a journal");
    }

    public static ReplayConfig record(Set<String> processors, Map<String, EventCodec> journalledFeeds, EventJournal journal,
                                      ReplayStore store) {
        return new ReplayConfig(Mode.RECORD, processors, journalledFeeds, journal, store);
    }

    public static ReplayConfig replay(Set<String> processors, Map<String, EventCodec> journalledFeeds, EventJournal journal,
                                      ReplayStore store) {
        return new ReplayConfig(Mode.REPLAY, processors, journalledFeeds, journal, store);
    }

    public boolean covers(String processorName) {
        return mode != Mode.OFF && (processors.isEmpty() || processors.contains(processorName));
    }

    public boolean journalled(String source) {
        return journalledFeeds.containsKey(source);
    }
}
