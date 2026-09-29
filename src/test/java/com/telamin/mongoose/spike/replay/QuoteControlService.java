package com.telamin.mongoose.spike.replay;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.event.ReplayRecord;
import com.telamin.mongoose.dispatch.AbstractEventToInvocationStrategy;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventToInvokeStrategy;
import com.telamin.mongoose.service.extension.AbstractEventSourceService;

import java.util.function.Supplier;

/**
 * SPIKE: a publishing service whose values reach a processor as SERVICE CALLS, through a typed invoke strategy, as in
 * the "typed invoke publishing service" plugin guide. The strategy is supplied, so a recording decorator can wrap it.
 */
public class QuoteControlService extends AbstractEventSourceService<String> {

    public static final CallBackType CALL_BACK = CallBackType.forClass(QuoteControl.class);

    public QuoteControlService(String name, Supplier<EventToInvokeStrategy> strategy) {
        super(name, CALL_BACK, strategy);
    }

    public void publish(String command) {
        output.publish(command);
    }

    /** A recorded call, back through this service's own queue, so the same typed strategy delivers it. */
    public void replay(ReplayRecord record) {
        output.publishReplay(record);
    }

    /** The typed invoke: a queued command becomes {@code onQuoteControl} on each processor that exports it. */
    public static class TypedInvoke extends AbstractEventToInvocationStrategy {
        @Override
        protected void dispatchEvent(Object event, DataFlow eventProcessor) {
            if (eventProcessor instanceof QuoteControl control && event instanceof String command) {
                control.onQuoteControl(command);
            } else {
                eventProcessor.onEvent(event);
            }
        }

        @Override
        protected boolean isValidTarget(DataFlow eventProcessor) {
            return eventProcessor instanceof QuoteControl;
        }
    }
}
