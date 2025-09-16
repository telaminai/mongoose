# How to write a publishing Service plugin (processor subscribes via @ServiceRegistered)

This guide shows how to implement a Service plugin that can publish events into the Mongoose event flow, and how an
EventProcessor subscribes to that service. The processor receives the service instance via `@ServiceRegistered` and
calls
`service.subscribe()` to start receiving events.

We will:

- Implement a minimal `PublishingService` by extending `AbstractEventSourceService<String>`
- Implement a processor that injects the service via `@ServiceRegistered`, calls `subscribe()` in `start()`, and
  forwards events to a sink
- Wire everything with `MongooseServerConfig`, publish a few events via the service, and verify reception in a sink

References in this repository:

- Example
  service: [PublishingService.java]({{source_root}}/test/java/com/telamin/mongoose/example/PublishingService.java)
- Example
  processor: [PublishingServiceSubscriberHandler.java]({{source_root}}/test/java/com/telamin/mongoose/example/PublishingServiceSubscriberHandler.java)
- Example
  test: [PublishingServicePluginExampleTest.java]({{source_root}}/test/java/com/telamin/mongoose/example/PublishingServicePluginExampleTest.java)
- Service base
  class: [AbstractEventSourceService.java]({{source_root}}/main/java/com/telamin/mongoose/service/extension/AbstractEventSourceService.java)

## 1) Implement a publishing Service

Extend `AbstractEventSourceService<T>` to integrate with the event flow. Use `output.publish(event)` to emit events to
all subscribers. Processors request subscription by calling `service.subscribe()`.

```java
package com.telamin.mongoose.example;

import com.telamin.mongoose.service.extension.AbstractEventSourceService;

public class PublishingService extends AbstractEventSourceService<String> {

    public PublishingService(String name) {
        super(name);
    }

    // Publish an event to all subscribers
    public void publish(String event) {
        if (output != null) {
            output.publish(event);
        }
    }
}
```

Key points:

- The service registers itself with the event flow when the server boots (via `setEventFlowManager` in the base class).
- `subscribe()` uses the current ProcessorContext to add a subscription for the calling processor.

## 2) Implement a processor that subscribes via @ServiceRegistered

Inject the service, call `subscribe()` in `start()`, and forward events to a sink in `handleEvent`.

```java
package com.telamin.mongoose.example;

import com.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.fluxtion.runtime.output.MessageSink;

public class PublishingServiceSubscriberHandler extends ObjectEventHandlerNode {

    private PublishingService publishingService;
    private MessageSink<String> sink;

    @ServiceRegistered
    public void wire(PublishingService service, String name) {
        this.publishingService = service;
    }

    @ServiceRegistered
    public void sink(MessageSink<String> sink, String name) {
        this.sink = sink;
    }

    @Override
    public void start() {
        if (publishingService != null) {
            publishingService.subscribe();
        }
    }

    @Override
    protected boolean handleEvent(Object event) {
        if (event instanceof String s && sink != null) {
            sink.accept(s);
        }
        return true;
    }
}
```

Notes:

- `@ServiceRegistered` injects services by type (and optionally name).
- Calling `subscribe()` from `start()` ensures the processor is subscribed before events are published.

## 3) Wire and run

Create the service and processor, wire them into `MongooseServerConfig`, boot the server, then publish events.

```java
import com.fluxtion.agrona.concurrent.BusySpinIdleStrategy;
import com.fluxtion.runtime.audit.LogRecordListener;
import com.fluxtion.runtime.output.MessageSink;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.*;
import com.telamin.mongoose.connector.memory.InMemoryMessageSink;

// Service and sink
PublishingService pubService = new PublishingService("pubService");
InMemoryMessageSink memSink = new InMemoryMessageSink();

// Processor that subscribes and forwards to sink
PublishingServiceSubscriberHandler handler = new PublishingServiceSubscriberHandler();

EventProcessorGroupConfig processorGroup = EventProcessorGroupConfig.builder()
        .agentName("processor-agent")
        .put("subscriber-processor", new EventProcessorConfig(handler))
        .build();

ServiceConfig<PublishingService> svcCfg = ServiceConfig.<PublishingService>builder()
        .service(pubService)
        .serviceClass(PublishingService.class)
        .name("pubService")
        .agent("service-agent", new BusySpinIdleStrategy()) // optional: can omit to run without its own agent
        .build();

EventSinkConfig<MessageSink<?>> sinkCfg = EventSinkConfig.<MessageSink<?>>builder()
        .instance(memSink)
        .name("memSink")
        .build();

MongooseServerConfig mongooseServerConfig = MongooseServerConfig.builder()
        .addProcessorGroup(processorGroup)
        .addService(svcCfg)
        .addEventSink(sinkCfg)
        .build();

LogRecordListener logListener = rec -> {};
MongooseServer server = MongooseServer.bootServer(mongooseServerConfig, logListener);

// Later: publish events via the service
pubService.publish("e1");
pubService.publish("e2");
```

The processor will receive these events via its `handleEvent` and forward them to the sink.

## When to use this pattern

- You want a reusable service that can push events to processors on-demand (e.g., adapters, gateways, timers).
- Processors opt-in by calling `service.subscribe()` so the service receives a subscribe request from the processor.
- You want to leverage Mongoose’s event flow, backpressure, and dispatching while keeping a clean plugin boundary.
