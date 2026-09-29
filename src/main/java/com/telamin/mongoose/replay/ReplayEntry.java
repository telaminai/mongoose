package com.telamin.mongoose.replay;

import java.util.List;

/**
 * One recorded input of one processor, in its input sequence (spec-replay-recording §2). An entry is an index into a
 * feed's journal, or the input itself. It names its source, never its callback: the callback is configuration, and a
 * replay boots the same configuration.
 */
public sealed interface ReplayEntry {

    /** The instant the processor read for this input. */
    long instant();

    /** An input from a journalled feed: the event is the journal's {@code (source, seq)}. */
    record Indexed(String source, long seq, long instant) implements ReplayEntry { }

    /** An input from a feed with no journal, recorded as it was delivered. */
    record Inline(String source, Object event, long instant) implements ReplayEntry { }

    /** A timer the processor scheduled fired: its schedule number, in the processor's own count. */
    record TimerFired(long seq, long instant) implements ReplayEntry { }

    /** A processor-owned admin command ran: its name and arguments. */
    record AdminInvoked(String command, List<String> args, long instant) implements ReplayEntry { }

    /** A dispatch threw: the stream is not reproducible past this point (a retry is a failure, D4). */
    record Failed(String source, String description, long instant) implements ReplayEntry { }
}
