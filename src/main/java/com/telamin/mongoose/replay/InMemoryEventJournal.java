package com.telamin.mongoose.replay;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-memory {@link EventJournal}, for tests and for a replay within one process. It is not durable: a restart
 * loses it. {@link CsvEventJournal} is the durable sample; a production journal (e.g. Chronicle) is open work
 * (design-doc/spec-replay-recording.md §5).
 */
public class InMemoryEventJournal implements EventJournal {
    private final Map<String, Map<Long, byte[]>> items = new ConcurrentHashMap<>();

    @Override
    public void append(String source, long seq, byte[] encoded) {
        items.computeIfAbsent(source, s -> new ConcurrentHashMap<>()).put(seq, encoded);
    }

    @Override
    public byte[] get(String source, long seq) {
        Map<Long, byte[]> bySeq = items.get(source);
        return bySeq == null ? null : bySeq.get(seq);
    }

    public int size(String source) {
        Map<Long, byte[]> bySeq = items.get(source);
        return bySeq == null ? 0 : bySeq.size();
    }
}
