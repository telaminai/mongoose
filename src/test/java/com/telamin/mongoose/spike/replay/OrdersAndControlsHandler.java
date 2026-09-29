package com.telamin.mongoose.spike.replay;

import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.event.NamedFeedEvent;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.fluxtion.runtime.output.MessageSink;

/**
 * SPIKE: a processor fed by two sources. Orders come from a journalled feed (as NamedFeedEvents with sequence
 * numbers); controls from a plain feed. A control changes how later orders are handled, so the order across the two
 * sources matters. Each line carries the processor's processTime: the instant it read for that input.
 */
public class OrdersAndControlsHandler extends ObjectEventHandlerNode {

    private final String orders, controls;
    private MessageSink<String> sink;
    private boolean suspended;

    public OrdersAndControlsHandler(String orders, String controls) {
        this.orders = orders;
        this.controls = controls;
    }

    @ServiceRegistered
    public void wire(MessageSink<String> sink, String name) {
        this.sink = sink;
    }

    @Override
    public void start() {
        getContext().subscribeToNamedFeed(orders);
        getContext().subscribeToNamedFeed(controls);
    }

    @Override
    protected boolean handleEvent(Object event) {
        if (sink == null) return true;
        long time = getContext().getClock().getProcessTime();
        Object data = event instanceof NamedFeedEvent<?> named ? named.data() : event;
        if (data instanceof String s && s.startsWith("ord-")) {
            sink.accept("order=" + s + " suspended=" + suspended + " time=" + time);
        } else if (data instanceof String s && (s.equals("suspend") || s.equals("resume"))) {
            suspended = s.equals("suspend");
            sink.accept("control=" + s + " time=" + time);
        }
        return true;
    }
}
