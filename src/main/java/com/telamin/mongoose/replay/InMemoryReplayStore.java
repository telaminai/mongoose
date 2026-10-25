package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-memory {@link ReplayStore}, for tests and for a replay within one process. It is not durable;
 * {@link CsvReplayStore} is the durable sample.
 */
@Experimental
public class InMemoryReplayStore implements ReplayStore {
    private final Map<String, List<ReplayEntry>> entries = new ConcurrentHashMap<>();

    @Override
    public void append(String processor, ReplayEntry entry) {
        // Recording appends on every input; copying the whole history per append makes allocation quadratic.
        entries.computeIfAbsent(processor, p -> Collections.synchronizedList(new ArrayList<>())).add(entry);
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
        return entries.containsKey(processor) || provenance.containsKey(processor);
    }

    @Override
    public List<ReplayEntry> entries(String processor) {
        List<ReplayEntry> recorded = entries.get(processor);
        if (recorded == null) return List.of();
        synchronized (recorded) {
            return List.copyOf(recorded);
        }
    }

    @Override
    public boolean holdsRecording() {
        return !provenance.isEmpty() || entries.values().stream().anyMatch(l -> !l.isEmpty());
    }
}
