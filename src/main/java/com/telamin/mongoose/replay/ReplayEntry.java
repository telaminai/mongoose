package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;

import java.util.List;

/**
 * One recorded input of one processor, in its input sequence (spec-replay-recording §2). An entry is an index into a
 * feed's journal, or the input itself. An input names its source and the ROUTE (callback type) that delivered it: one
 * source can reach a processor by more than one configured route, and a replay must take the one the recorded run took
 * (review of 90f0d9b, finding 4). An empty route is an entry that did not record one; a replay then refuses a source
 * with more than one route to the processor rather than choose.
 *
 * <p>{@code instant} is the time the input was handled at: its {@code processTime}, the first reading when the cycle
 * read the clock. {@code reads} are every reading the processor actually took of its clock while it handled the input,
 * in order, and may be empty: a cycle that read no clock records none (finding 6; it used to record one, the instant, so
 * a replayed cycle that dropped its only read went undetected). A replay plays them back in the same order and requires
 * the same count.
 */
@Experimental
public sealed interface ReplayEntry {

    /** The instant this input was handled at: its {@code processTime}. */
    long instant();

    /** An input that carries the clock readings its cycle took. */
    sealed interface Timed extends ReplayEntry {
        List<Long> reads();
    }

    /** The first of {@code reads}: the instant of a cycle that read the clock (the convenience constructors' rule). */
    private static long first(List<Long> reads) {
        if (reads.isEmpty()) throw new IllegalArgumentException("no readings: give the instant explicitly");
        return reads.get(0);
    }

    /** An input from a journalled feed: the event is the journal's {@code (source, seq)}. */
    record Indexed(String source, String route, long seq, long instant, List<Long> reads) implements Timed {
        public Indexed(String source, long seq, List<Long> reads) {
            this(source, "", seq, first(reads), reads);
        }
    }

    /**
     * An input from a feed with no journal, recorded as it was delivered: a copy taken before the processor handled it
     * (finding 2). A delivered {@code NamedFeedEvent} is a {@link RecordedNamedEvent}, rebuilt on replay with its own
     * name, topic and number (finding 3); {@code seq} is its number, otherwise -1.
     */
    record Inline(String source, String route, Object event, long seq, long instant, List<Long> reads) implements Timed {
        public Inline(String source, Object event, long seq, List<Long> reads) {
            this(source, "", event, seq, first(reads), reads);
        }

        public Inline(String source, Object event, List<Long> reads) {
            this(source, event, -1, reads);
        }
    }

    /** A timer the processor scheduled fired: its schedule number, in the processor's own count. */
    record TimerFired(long seq, long instant, List<Long> reads) implements Timed {
        public TimerFired(long seq, List<Long> reads) {
            this(seq, first(reads), reads);
        }
    }

    /** A processor-owned admin command ran: its name and arguments. */
    record AdminInvoked(String command, List<String> args, long instant, List<Long> reads) implements Timed {
        public AdminInvoked(String command, List<String> args, List<Long> reads) {
            this(command, args, first(reads), reads);
        }
    }

    /** A dispatch threw: the stream is not reproducible past this point (a retry is a failure, D4). */
    record Failed(String source, String description, long instant) implements ReplayEntry { }
}
