package com.telamin.mongoose.spike.replay;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.event.NamedFeedEvent;
import com.telamin.mongoose.service.EventToInvokeStrategy;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * SPIKE: a processor's recorded inputs as "an index or an event". An input that came from a journalled feed carries
 * its feed's sequence number on the queue ({@code NamedFeedEvent}), so only {@link Indexed}{source, seq, instant} is
 * recorded: the event is in the feed's journal, serialised once for every processor. Anything else is recorded
 * {@link Inline}. The instant is the one the processor read, from its {@link RecordingClock}: the clock is not pinned.
 *
 * <p>The strategy does not know which source its queue drains; an indexed input names its own. For an inline one the
 * spike asks {@code sourceOf}; a production recorder takes it from the queue's agent ({@code group/source/callback}).
 */
public class IndexingRecorder implements EventToInvokeStrategy {

    public sealed interface Entry permits Indexed, Inline { }

    public record Indexed(String source, long seq, long instant) implements Entry { }

    public record Inline(String source, Object event, long instant) implements Entry { }

    private final EventToInvokeStrategy delegate;
    private final List<Entry> recorded;
    private final Map<DataFlow, RecordingClock> clocks;
    private final LongSupplier live;
    private final Function<Object, String> sourceOf;

    public IndexingRecorder(EventToInvokeStrategy delegate, List<Entry> recorded, Map<DataFlow, RecordingClock> clocks,
                            LongSupplier live, Function<Object, String> sourceOf) {
        this.delegate = delegate;
        this.recorded = recorded;
        this.clocks = clocks;
        this.live = live;
        this.sourceOf = sourceOf;
    }

    @Override
    public void registerProcessor(DataFlow processor) {
        synchronized (clocks) {
            if (!clocks.containsKey(processor)) {
                RecordingClock clock = new RecordingClock(live);
                clocks.put(processor, clock);
                processor.setClockStrategy(clock);
            }
        }
        delegate.registerProcessor(processor);
    }

    @Override
    public void processEvent(Object event) {
        // one processor in the spike; a production recorder keeps one sequence per processor
        Collection<DataFlow> targets = delegate.registeredProcessors();
        Map<DataFlow, RecordingClock> armed = new IdentityHashMap<>();
        for (DataFlow p : targets) {
            RecordingClock c = clocks.get(p);
            c.arm();
            armed.put(p, c);
        }
        delegate.processEvent(event);
        for (RecordingClock c : armed.values()) {
            long instant = c.captured();
            recorded.add(event instanceof NamedFeedEvent<?> named
                    ? new Indexed(named.eventFeedName(), named.sequenceNumber(), instant)
                    : new Inline(sourceOf.apply(event), event, instant));
        }
    }

    @Override
    public void processEvent(Object event, long time) {
        delegate.processEvent(event, time);
    }

    @Override
    public void deregisterProcessor(DataFlow processor) {
        delegate.deregisterProcessor(processor);
    }

    @Override
    public int listenerCount() {
        return delegate.listenerCount();
    }

    @Override
    public Collection<DataFlow> registeredProcessors() {
        return delegate.registeredProcessors();
    }
}
