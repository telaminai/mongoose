package com.telamin.mongoose.spike.replay;

import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.fluxtion.runtime.output.MessageSink;

/**
 * SPIKE: a processor that raises its own event. Each order is counted; the second raises a {@link Breach} on the
 * processor itself, the same type an external source may also send. The sink line for every event handled, with the
 * clock the processor read, is what a replay must reproduce.
 */
public class BreachHandler extends ObjectEventHandlerNode {

    public record Breach(String orderId, int liveOrders) { }

    private final String feedName;
    private MessageSink<String> sink;
    private int live;

    public BreachHandler(String feedName) {
        this.feedName = feedName;
    }

    @ServiceRegistered
    public void wire(MessageSink<String> sink, String name) {
        this.sink = sink;
    }

    @Override
    public void start() {
        getContext().subscribeToNamedFeed(feedName);
    }

    @Override
    protected boolean handleEvent(Object event) {
        long time = getContext().getClock().getWallClockTime();
        if (event instanceof String order) {
            live++;
            sink.accept("order=" + order + " live=" + live + " time=" + time);
            if (live == 2) getContext().getParentDataFlow().onEvent(new Breach(order, live));   // raised by the graph
        } else if (event instanceof Breach breach) {
            sink.accept("breach=" + breach + " time=" + time);
        }
        return true;
    }
}
