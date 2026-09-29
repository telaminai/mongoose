package com.telamin.mongoose.spike.replay;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.event.ReplayRecord;
import com.telamin.mongoose.service.EventToInvokeStrategy;

import java.util.Collection;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * SPIKE: record every event a queue dispatches to its processors, at the dispatch point, after the queue.
 *
 * <p>A decorator around any {@link EventToInvokeStrategy}: the plain {@code onEvent} one, or a typed one that turns a
 * queued value into a service call on the processor. An event the graph raises itself re-enters its own processor's
 * {@code onEvent} and never passes here, so everything recorded is an input by construction.
 *
 * <p>Each record carries the SOURCE it came from. The callback it becomes (an {@code onEvent}, or a typed service call)
 * is configuration: the source defines it, and a replay boots the same configuration. So a replay publishes each record
 * back through its own source, and the configured strategy delivers it as it did. The strategy does not know its source
 * name; the queue's agent does (it is named {@code group/source/callback}), so a production recorder takes it from there.
 *
 * <p>The time: the processor must use exactly the recorded instant. The strategy picks it and dispatches with it, through
 * the delegate's {@code processEvent(event, time)} (the path a {@code ReplayRecord} takes), which pins the processor's
 * clock. A replay pins it the same way. (A first version subclassed the strategy; the base class's timed
 * {@code processEvent} calls the overridable untimed one, so it recursed until the stack overflowed and the retry policy
 * dropped every event. A decorator cannot.)
 */
public class RecordingEventToInvokeStrategy implements EventToInvokeStrategy {

    /** One recorded input: the source it came from, and the event at its instant. */
    public record Recorded(String source, ReplayRecord record) { }

    private final EventToInvokeStrategy delegate;
    private final List<Recorded> recorded;
    private final LongSupplier wallClock;
    private final String source;

    public RecordingEventToInvokeStrategy(EventToInvokeStrategy delegate, List<Recorded> recorded, LongSupplier wallClock,
                                          String source) {
        this.delegate = delegate;
        this.recorded = recorded;
        this.wallClock = wallClock;
        this.source = source;
    }

    @Override
    public void processEvent(Object event) {
        processEvent(event, wallClock.getAsLong());
    }

    @Override
    public void processEvent(Object event, long time) {
        ReplayRecord r = new ReplayRecord();
        r.setEvent(event);
        r.setWallClockTime(time);
        recorded.add(new Recorded(source, r));
        delegate.processEvent(event, time);
    }

    @Override
    public void registerProcessor(DataFlow eventProcessor) {
        delegate.registerProcessor(eventProcessor);
    }

    @Override
    public void deregisterProcessor(DataFlow eventProcessor) {
        delegate.deregisterProcessor(eventProcessor);
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
