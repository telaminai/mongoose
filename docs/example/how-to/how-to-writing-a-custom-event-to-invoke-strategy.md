# How to write a custom EventToInvokeStrategy

This guide shows how to build and plug in your own EventToInvokeStrategy to control how events are dispatched from
queues to StaticEventProcessor instances.

When to customize:

- Filter which processors can receive events
- Transform events before delivery
- Route or multiplex events differently than the default onEvent dispatch
- Callback to a strongly-typed interface

## 1) Choose a base: implement the interface or extend the helper

You can:

- Implement the low-level interface
  directly: [EventToInvokeStrategy]({{source_root}}/main/java/com/telamin/mongoose/service/EventToInvokeStrategy.java)
- Or extend the convenience base
  class: [AbstractEventToInvocationStrategy]({{source_root}}/main/java/com/telamin/mongoose/dispatch/AbstractEventToInvocationStrategy.java)

The helper already manages:

- Registration/deregistration of processors (thread-safe list)
- Per-dispatch ProcessorContext current-processor handling
- Synthetic clock wiring when you call processEvent(event, time)

With the helper, you only implement:

- protected void dispatchEvent(Object event, StaticEventProcessor eventProcessor)
- protected boolean isValidTarget(StaticEventProcessor eventProcessor)

## 2) Example: filter targets and transform events (strongly-typed callback)

The following example accepts only processors that implement a MarkerProcessor interface and uppercases String events
before delivering them via a strongly-typed callback or onEvent(Object) if the event is not a String:

```java
import com.telamin.fluxtion.runtime.StaticEventProcessor;
import com.telamin.mongoose.dispatch.AbstractEventToInvocationStrategy;

public interface MarkerProcessor {
    void onString(String s);
}

public class UppercaseStringStrategy extends AbstractEventToInvocationStrategy {
    @Override
    protected void dispatchEvent(Object event, StaticEventProcessor eventProcessor) {
        if (event instanceof String s && eventProcessor instanceof MarkerProcessor marker) {
            marker.onString(s.toUpperCase());
        } else {
            //normal dispatch to onEvent
            eventProcessor.onEvent(event);
        }
    }

    @Override
    protected boolean isValidTarget(StaticEventProcessor eventProcessor) {
        return eventProcessor instanceof MarkerProcessor;
    }
}
```

Notes:

- Using an invoker strategy allows your event processors to be strongly typed (e.g., MarkerProcessor.onString), while
  the strategy takes responsibility for mapping inbound events to the correct callback. This reduces boilerplate and
  centralizes dispatch logic, which can make future maintenance easier.
- [ProcessorContext]({{source_root}}/main/java/com/telamin/mongoose/dispatch/ProcessorContext.java)
  is automatically set to the current target processor during dispatch. Inside the processor, you can call
  ProcessorContext.currentProcessor() if needed.
- If you call processEvent(event, time), AbstractEventToInvocationStrategy wires a synthetic clock into each target
  processor via setClockStrategy so that processors can use a provided time source.

## 3) Wire your strategy into the runtime

Register your strategy as a factory for
 a [CallBackType]({{source_root}}/main/java/com/telamin/mongoose/service/CallBackType.java).

Via MongooseServerConfig fluent builder (server will register on boot), and override the default onEvent strategy,
ON_EVENT_CALL_BACK,
delivers raw events to processors via the onEvent(Object) callback.

```java
// Register for the standard onEvent path (optional if you want raw onEvent only)
MongooseServerConfig mongooseServerConfig = MongooseServerConfig.builder()
    .onEventInvokeStrategy(UppercaseStringStrategy::new)
    // add groups, feeds, sinks, services
    .build();
MongooseServer server = MongooseServer.bootServer(mongooseServerConfig);
```

For a full end-to-end example that boots the server via the fluent MongooseServerConfig builder and verifies the custom strategy,
see the test method fluentBuilder_bootsServer_and_applies_custom_strategy
in [CustomEventToInvokeStrategyTest.java]({{source_root}}/test/java/com/telamin/mongoose/dispatch/CustomEventToInvokeStrategyTest.java).

Via MongooseServer (register at runtime), beware that custom strategy will not affect queues that are already in use
and have been registered before the new invoker strategy is registered.

```java
MongooseServer server = MongooseServer.bootServer(mongooseServerConfig);
server.registerEventMapperFactory(UppercaseStringStrategy::new, CallBackType.ON_EVENT_CALL_BACK);
```

## 4) Testing tips

- Use a RecordingProcessor that implements StaticEventProcessor (and your marker if filtering) to capture received
  events.
- Assert listenerCount() after registering processors to ensure your isValidTarget filter works.
- Publish test events through EventToQueuePublisher and call agent.doWork() to force processing.
- If you need timestamp semantics, publish a ReplayRecord through the EventToQueuePublisher or use processEvent(event,
  time) inside a controlled driver and have your processor consult its clock strategy.

See [CustomEventToInvokeStrategyTest.java]({{source_root}}/test/java/com/telamin/mongoose/dispatch/CustomEventToInvokeStrategyTest.java)
for a complete, runnable example. It includes:

- A direct EventFlowManager usage example of a custom strategy ✓
- A fluent MongooseServerConfig builder example that boots a MongooseServer and registers the custom strategy ✓

Complete how-to example: [Writing a Custom Event to Invoke Strategy Example](https://github.com/telaminai/mongoose-examples/blob/229e01e2f508bdf084a611677dc93c1174c96bdc/how-to/writing-a-custom-event-to-invoke-strategy)

References:

- [EventFlowManager]({{source_root}}/main/java/com/telamin/mongoose/dispatch/EventFlowManager.java)
- [EventToQueuePublisher]({{source_root}}/main/java/com/telamin/mongoose/dispatch/EventToQueuePublisher.java)
- [EventSourceKey]({{source_root}}/main/java/com/telamin/mongoose/service/EventSourceKey.java)
- [MongooseServer]({{source_root}}/main/java/com/telamin/mongoose/MongooseServer.java)
- [CallBackType]({{source_root}}/main/java/com/telamin/mongoose/service/CallBackType.java)
- [AbstractEventToInvocationStrategy]({{source_root}}/main/java/com/telamin/mongoose/dispatch/AbstractEventToInvocationStrategy.java)
- [ProcessorContext]({{source_root}}/main/java/com/telamin/mongoose/dispatch/ProcessorContext.java)
