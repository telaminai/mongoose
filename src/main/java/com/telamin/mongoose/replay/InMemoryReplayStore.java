package com.telamin.mongoose.replay;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** SPIKE: an in-memory entry store. */
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
