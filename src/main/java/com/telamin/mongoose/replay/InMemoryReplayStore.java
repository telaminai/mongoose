package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An in-memory {@link ReplayStore}, for tests and for a replay within one process. It is not durable;
 * {@link CsvReplayStore} is the durable sample.
 */
@Experimental
public class InMemoryReplayStore implements ReplayStore {
    private final Map<String, List<ReplayEntry>> entries = new ConcurrentHashMap<>();

    @Override
    public void append(String processor, ReplayEntry entry) {
        entries.computeIfAbsent(processor, p -> new CopyOnWriteArrayList<>()).add(entry);
    }

    private final Map<String, Provenance> provenance = new ConcurrentHashMap<>();

    @Override
    public void began(String processor, Provenance p) {
        provenance.put(processor, p);
    }

    @Override
    public Provenance provenance(String processor) {
        return provenance.get(processor);
    }

    @Override
    public boolean has(String processor) {
        return entries.containsKey(processor);
    }

    @Override
    public List<ReplayEntry> entries(String processor) {
        return List.copyOf(entries.getOrDefault(processor, List.of()));
    }

    @Override
    public boolean holdsRecording() {
        return entries.values().stream().anyMatch(l -> !l.isEmpty());
    }
}
