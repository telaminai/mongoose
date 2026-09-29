package com.telamin.mongoose.example.replay;

import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.fluxtion.runtime.output.MessageSink;

/**
 * The processor of the record-and-replay example: it subscribes to a price feed and writes each price with the
 * instant it read from the processor's clock, so a replay that differed in order or in time would show.
 */
public class RecordedPriceHandler extends ObjectEventHandlerNode {

    private final String feedName;
    private MessageSink<String> sink;

    public RecordedPriceHandler(String feedName) {
        this.feedName = feedName;
    }

    @ServiceRegistered
    public void sink(MessageSink<String> sink, String name) {
        this.sink = sink;
    }

    @Override
    public void start() {
        getContext().subscribeToNamedFeed(feedName);
    }

    @Override
    protected boolean handleEvent(Object event) {
        long time = getContext().getClock().getProcessTime();
        if (sink != null && event instanceof String price) {
            sink.accept("price=" + price + " time=" + time);
        }
        return true;
    }
}
