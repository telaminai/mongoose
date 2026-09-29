package com.telamin.mongoose.replay;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An in-memory {@link ReplayStore}, for tests and for a replay within one process. It is not durable;
 * {@link CsvReplayStore} is the durable sample.
 */
public class InMemoryReplayStore implements ReplayStore {
    private final Map<String, List<ReplayEntry>> entries = new ConcurrentHashMap<>();

    @Override
    public void append(String processor, ReplayEntry entry) {
        entries.computeIfAbsent(processor, p -> new CopyOnWriteArrayList<>()).add(entry);
    }

    @Override
    public List<ReplayEntry> entries(String processor) {
        return List.copyOf(entries.getOrDefault(processor, List.of()));
    }
}
