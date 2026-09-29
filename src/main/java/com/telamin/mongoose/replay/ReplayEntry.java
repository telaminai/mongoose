package com.telamin.mongoose.replay;

import java.util.List;

/**
 * One recorded input of one processor, in its input sequence (spec-replay-recording §2). An entry is an index into a
 * feed's journal, or the input itself. It names its source, never its callback: the callback is configuration, and a
 * replay boots the same configuration.
 *
 * <p>{@code reads} are every reading the processor took of its clock while it handled the input, in order: the first is
 * the input's {@code processTime}; a later one is, for example, an event the graph raised itself during the cycle, or a
 * node's own live read. A replay plays them back in the same order, so all of them are reproduced (found on CI: a
 * graph-raised event's second read fell a millisecond after the first, and a replay pinned to one instant differed).
 */
public sealed interface ReplayEntry {

    /** The instant the processor read first for this input: its {@code processTime}. */
    long instant();

    /** An input that carries the clock readings its cycle took. */
    sealed interface Timed extends ReplayEntry {
        List<Long> reads();

        @Override
        default long instant() {
            return reads().get(0);
        }
    }

    /** An input from a journalled feed: the event is the journal's {@code (source, seq)}. */
    record Indexed(String source, long seq, List<Long> reads) implements Timed { }

    /**
     * An input from a feed with no journal, recorded as it was delivered. For a named-event feed {@code event} is the
     * item and {@code seq} its sequence number (the wrapper is rebuilt on replay, as for an index); otherwise -1.
     */
    record Inline(String source, Object event, long seq, List<Long> reads) implements Timed {
        public Inline(String source, Object event, List<Long> reads) {
            this(source, event, -1, reads);
        }
    }

    /** A timer the processor scheduled fired: its schedule number, in the processor's own count. */
    record TimerFired(long seq, List<Long> reads) implements Timed { }

    /** A processor-owned admin command ran: its name and arguments. */
    record AdminInvoked(String command, List<String> args, List<Long> reads) implements Timed { }

    /** A dispatch threw: the stream is not reproducible past this point (a retry is a failure, D4). */
    record Failed(String source, String description, long instant) implements ReplayEntry { }
}
