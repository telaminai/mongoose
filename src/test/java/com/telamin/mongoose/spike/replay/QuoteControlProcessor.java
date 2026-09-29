package com.telamin.mongoose.spike.replay;

import com.telamin.fluxtion.runtime.DefaultEventProcessor;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.fluxtion.runtime.output.MessageSink;

/**
 * SPIKE: a processor exporting {@link QuoteControl}. A service call and an ordinary event with the same value are
 * different inputs, and the node says which it got, with the clock it read, so a replay through the wrong path shows.
 */
public class QuoteControlProcessor extends DefaultEventProcessor implements QuoteControl {

    private final Node node;

    public QuoteControlProcessor(Node node) {
        super(node);
        this.node = node;
    }

    @Override
    public void onQuoteControl(String command) {
        node.onQuoteControl(command);
    }

    public static class Node extends ObjectEventHandlerNode implements QuoteControl {
        private final String feedName;
        private QuoteControlService service;
        private MessageSink<String> sink;
        private boolean suspended;

        public Node(String feedName) {
            this.feedName = feedName;
        }

        @ServiceRegistered
        public void service(QuoteControlService service, String name) {
            this.service = service;
        }

        @ServiceRegistered
        public void sink(MessageSink<String> sink, String name) {
            this.sink = sink;
        }

        @Override
        public void start() {
            if (service != null) service.subscribe();
            getContext().subscribeToNamedFeed(feedName);
        }

        @Override
        public void onQuoteControl(String command) {
            suspended = command.equals("suspend");
            sink.accept("call=" + command + " suspended=" + suspended + " time=" + getContext().getClock().getWallClockTime());
        }

        @Override
        protected boolean handleEvent(Object event) {
            // only the commands: a processor also hears its own set-up events, some before the sink is registered
            if (event instanceof String command && sink != null) {
                sink.accept("event=" + command + " suspended=" + suspended + " time=" + getContext().getClock().getWallClockTime());
            }
            return true;
        }
    }
}
