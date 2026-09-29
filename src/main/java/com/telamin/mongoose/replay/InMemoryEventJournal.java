package com.telamin.mongoose.replay;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** SPIKE: an in-memory journal. A durable one (Chronicle) is the next step (spec §5). */
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
