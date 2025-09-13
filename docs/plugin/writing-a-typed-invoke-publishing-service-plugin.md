# How to write a publishing Service plugin that uses a typed invoke strategy

This guide extends the basic publishing service example by delivering events via a strongly-typed interface on the event
processor. Instead of always calling onEvent(Object), the service configures a custom EventToInvokeStrategy and a
CallBackType bound to an interface. Only processors that implement that interface will be targeted, and the strategy
invokes the typed method.

What you will learn:

- Define a listener interface that processors can implement (e.g., PublishingServiceListener)
- Configure a service to use CallBackType.forClass(PublishingServiceListener.class)
- Provide an EventToInvokeStrategy that calls listener.onServiceEvent(String)
- Subscribe from the processor via @ServiceRegistered and service.subscribe()

References in this repository:

- Typed
  service: [PublishingService.java]({{source_root}}/test/java/com/telamin/mongoose/example/PublishingServiceTyped.java)
- Listener
  interface: [PublishingServiceListener.java]({{source_root}}/test/java/com/telamin/mongoose/example/PublishingServiceListener.java)
- Typed
  processor: [PublishingServiceTypedSubscriberHandler.java]({{source_root}}/test/java/com/telamin/mongoose/example/PublishingServiceTypedSubscriberHandler.java)
- End-to-end
  test: [PublishingServiceTypedInvokeExampleTest.java]({{source_root}}/test/java/com/telamin/mongoose/example/PublishingServiceTypedInvokeExampleTest.java)
- Base service
  support: [AbstractEventSourceService.java]({{source_root}}/main/java/com/telamin/mongoose/service/extension/AbstractEventSourceService.java)

## 1) Define a listener interface

```java
package com.telamin.mongoose.example;

public interface PublishingServiceListener {
    void onServiceEvent(String event);
}
```

## 2) Implement a typed publishing service

Extend AbstractEventSourceService<String> and configure:

- CallBackType.forClass(PublishingServiceListener.class) as the callback type
- A supplier of a custom EventToInvokeStrategy that calls the typed method

```java
package com.telamin.mongoose.example;

import com.telamin.mongoose.dispatch.AbstractEventToInvocationStrategy;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.extension.AbstractEventSourceService;
import com.fluxtion.runtime.StaticEventProcessor;

public class PublishingServiceTyped extends AbstractEventSourceService<String> {

    public PublishingServiceTyped(String name) {
        super(name,
                CallBackType.forClass(PublishingServiceListener.class),
                TypedInvokeStrategy::new);
    }

    public void publish(String event) {
        if (output != null) {
            output.publish(event);
        }
    }

    static class TypedInvokeStrategy extends AbstractEventToInvocationStrategy {
        @Override
        protected void dispatchEvent(Object event, StaticEventProcessor eventProcessor) {
            if (eventProcessor instanceof PublishingServiceListener listener && event instanceof String s) {
                listener.onServiceEvent(s);
            } else {
                eventProcessor.onEvent(event);
            }
        }

        @Override
        protected boolean isValidTarget(StaticEventProcessor eventProcessor) {
            return eventProcessor instanceof PublishingServiceListener;
        }
    }
}
```

## 3) Implement a processor that subscribes and implements the listener

```java
package com.telamin.mongoose.example;

import com.fluxtion.runtime.DefaultEventProcessor;
import com.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.fluxtion.runtime.output.MessageSink;

public class PublishingServiceTypedSubscriberHandler extends DefaultEventProcessor
        implements PublishingServiceListener {

    private final TypedHandler typedHandler;

    public PublishingServiceTypedSubscriberHandler(TypedHandler typedHandler) {
        super(typedHandler);
        this.typedHandler = typedHandler;
    }

    @Override
    public void onServiceEvent(String event) {
        typedHandler.onServiceEvent(event);
    }

    public static class TypedHandler extends ObjectEventHandlerNode implements PublishingServiceListener {

        private PublishingServiceTyped service;
        private MessageSink<String> sink;

        @ServiceRegistered
        public void wire(PublishingServiceTyped service, String name) {
            this.service = service;
        }

        @ServiceRegistered
        public void sink(MessageSink<String> sink, String name) {
            this.sink = sink;
        }

        @Override
        public void start() {
            if (service != null) {
                service.subscribe();
            }
        }

        @Override
        public void tearDown() {
            // No-op
        }

        @Override
        public void onServiceEvent(String event) {
            if (sink != null) {
                sink.accept(event);
            }
        }
    }
}
```

## 4) Wire and test

The test boots a server, registers the typed service and a processor that implements the listener, then publishes
events:

```java
PublishingServiceTyped pubService = new PublishingServiceTyped("pubServiceTyped");
PublishingServiceTypedSubscriberHandler handler = new PublishingServiceTypedSubscriberHandler(new PublishingServiceTypedSubscriberHandler.TypedHandler());

EventProcessorGroupConfig processorGroup = EventProcessorGroupConfig.builder()
        .agentName("processor-agent")
        .put("typed-subscriber-processor", EventProcessorConfig.builder().handler(handler).build())
        .build();

ServiceConfig<PublishingServiceTyped> svcCfg = ServiceConfig.<PublishingServiceTyped>builder()
        .service(pubService)
        .serviceClass(PublishingServiceTyped.class)
        .name("pubServiceTyped")
        //.agent("service-agent", new BusySpinIdleStrategy()) // optional: uncomment to give the service its own agent
        .build();

MongooseServerConfig mongooseServerConfig = MongooseServerConfig.builder()
        .addProcessorGroup(processorGroup)
        .addService(svcCfg)
        .build();

MongooseServer server = MongooseServer.bootServer(mongooseServerConfig, rec -> {});

pubService.publish("t1");
pubService.publish("t2");
```

The processor receives these via onServiceEvent and can forward them to a sink.

## Why use typed invokes?

- Explicit contracts between service and processor via an interface
- Compile-time safety for callback signatures
- Ability to filter eligible processors by interface
- Coexists with onEvent fallback if desired
