package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;

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
 * @param clock            the live clock a recorded processor reads; the system clock by default
 * @param deliveryTimeout  REPLAY: how long an entry may wait for its route or admin command before the replay stops,
 *                         saying why (5 s by default)
 */
@Experimental
public record ReplayConfig(Mode mode, Set<String> processors, Map<String, EventCodec> journalledFeeds,
                           EventJournal journal, ReplayStore store, java.util.function.LongSupplier clock,
                           java.time.Duration deliveryTimeout) {

    public enum Mode { OFF, RECORD, REPLAY }

    public static final ReplayConfig OFF = new ReplayConfig(Mode.OFF, Set.of(), Map.of(), null, null, null, null);

    public ReplayConfig(Mode mode, Set<String> processors, Map<String, EventCodec> journalledFeeds, EventJournal journal,
                        ReplayStore store, java.util.function.LongSupplier clock) {
        this(mode, processors, journalledFeeds, journal, store, clock, null);
    }

    public ReplayConfig {
        processors = processors == null ? Set.of() : Set.copyOf(processors);
        journalledFeeds = journalledFeeds == null ? Map.of() : Map.copyOf(journalledFeeds);
        clock = clock == null ? System::currentTimeMillis : clock;
        deliveryTimeout = deliveryTimeout == null ? java.time.Duration.ofSeconds(5) : deliveryTimeout;
        if (mode != Mode.OFF && store == null) throw new IllegalArgumentException("replay mode " + mode + " needs a store");
        if (!journalledFeeds.isEmpty() && journal == null) throw new IllegalArgumentException("journalled feeds need a journal");
    }

    public static ReplayConfig record(Set<String> processors, Map<String, EventCodec> journalledFeeds, EventJournal journal,
                                      ReplayStore store) {
        return new ReplayConfig(Mode.RECORD, processors, journalledFeeds, journal, store, null);
    }

    public static ReplayConfig replay(Set<String> processors, Map<String, EventCodec> journalledFeeds, EventJournal journal,
                                      ReplayStore store) {
        return new ReplayConfig(Mode.REPLAY, processors, journalledFeeds, journal, store, null);
    }

    /** The live clock recorded processors read (a test ticks one); the system clock by default. */
    public ReplayConfig withClock(java.util.function.LongSupplier liveClock) {
        return new ReplayConfig(mode, processors, journalledFeeds, journal, store, liveClock, deliveryTimeout);
    }

    /** REPLAY: how long an entry may wait for its route or admin command before the replay stops, saying why. */
    public ReplayConfig withDeliveryTimeout(java.time.Duration timeout) {
        return new ReplayConfig(mode, processors, journalledFeeds, journal, store, clock, timeout);
    }

    public boolean covers(String processorName) {
        return mode != Mode.OFF && (processors.isEmpty() || processors.contains(processorName));
    }

    public boolean journalled(String source) {
        return journalledFeeds.containsKey(source);
    }
}
