package com.telamin.mongoose.replay;

/**
 * An item of a journalled feed on its way to a processor (R3): the feed's sequence number travels with it, so the
 * dispatcher can record an index. The queue's agent unwraps it, so the processor receives the bare item.
 */
public record JournalledItem(long seq, Object item) { }
