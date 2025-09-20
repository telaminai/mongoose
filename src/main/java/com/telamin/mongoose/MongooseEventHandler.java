package com.telamin.mongoose;

import com.telamin.fluxtion.runtime.context.DataFlowContext;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.fluxtion.runtime.DefaultEventProcessor;

import java.util.Map;
import java.util.function.Consumer;

/**
 * A specialized event processor implementation that handles Mongoose events.
 * Extends DefaultEventProcessor to provide custom event handling capabilities
 * with support for functional and object-based event handlers.
 */
public class MongooseEventHandler extends DefaultEventProcessor {

    /**
     * Creates a MongooseEventHandler with a custom event handler and context map.
     *
     * @param allEventHandler the event handler node to process events
     * @param contextMap      the context map containing configuration parameters
     */
    public MongooseEventHandler(ObjectEventHandlerNode allEventHandler, Map<Object, Object> contextMap) {
        super(allEventHandler, contextMap);
    }

    /**
     * Creates a MongooseEventHandler with a custom event handler.
     *
     * @param allEventHandler the event handler node to process events
     */
    public MongooseEventHandler(ObjectEventHandlerNode allEventHandler) {
        super(allEventHandler);
    }

    /**
     * Creates a MongooseEventHandler with a functional event handler.
     *
     * @param handlerFunction the consumer function to process events
     */
    public MongooseEventHandler(Consumer<Object> handlerFunction) {
        super(new FunctionalEventHandler(handlerFunction));
    }

    /**
     * Initializes the event handler and registers it with the service registry.
     * Called during startup to prepare the handler for event processing.
     */
    @Override
    public void init() {
        super.init();
        serviceRegistry.nodeRegistered(this, "mongooseEventHandler");
    }

    /**
     * Retrieves the current event processor context.
     *
     * @return the EventProcessorContext associated with this handler
     */
    protected DataFlowContext getContext() {
        return serviceRegistry.getDataFlowContext();
    }

    private static class FunctionalEventHandler extends ObjectEventHandlerNode {

        private final Consumer<Object> handlerFunction;

        public FunctionalEventHandler(Consumer<Object> handlerFunction) {
            this.handlerFunction = handlerFunction;
        }

        protected boolean handleEvent(Object event) {
            handlerFunction.accept(event);
            return true;
        }
    }
}
