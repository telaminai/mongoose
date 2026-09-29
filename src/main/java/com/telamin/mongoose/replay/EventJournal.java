package com.telamin.mongoose.replay;

/** Each journalled item once, by {@code (source, seq)}, encoded with its feed's codec. */
public interface EventJournal {
    void append(String source, long seq, byte[] encoded);

    /** The encoded item, or null when the journal does not hold it. */
    byte[] get(String source, long seq);
}
