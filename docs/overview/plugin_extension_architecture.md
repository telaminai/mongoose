# Plugin architecture: Extending Mongoose

Mongoose Server is designed to be extended through composable plugins that provide reusable infrastructure capabilities.
Your business logic (event handlers/processors) should remain focused on domain behavior and be decoupled from
infrastructure concerns like IO, scheduling, or dispatching. Plugins encapsulate those concerns and are wired into your
application at boot.

- Audience: engineers and architects new to Mongoose Server
- Read this with: ""Event source feeds", "Event sink outputs" and "Service functions" all are plugin extensions.

At a high level, there are three kinds of plugins:

- Event sources (feeds)
- Services (functions)
- Event sinks (outputs)

These can be combined and reused across many applications. This page explains each type, how they are integrated, and
how event handlers interact with them while keeping a clean separation between domain code and infrastructure.

## Why plugins?

- Reuse: Implement once, reuse across apps (e.g., a Kafka feed, a filesystem tailer, a metrics sink).
- Separation of concerns: Business logic focuses on handling events and state transitions; plugins handle IO, timers,
  admin, and operational concerns.
- Composability: Wire multiple plugins into an application using MongooseServerConfig without modifying business code.
- Testability: Handlers can be unit tested with in-memory test doubles for the same plugin interfaces.

## Types of plugins

### 1) Event sources

Event feeds produce events and publish them into the event processing graph.

- Typical responsibilities: polling an external source, decoding payloads, backpressure awareness, checkpointing.
- Lifecycle: Usually agent-hosted services that run on their own thread (an Agrona Agent), e.g.,
  `AbstractEventSourceService` based sources.
- Integration: Configure via `EventFeedConfig` and add to your `MongooseServerConfig`.
- Naming: Feeds can be named (via `EventFeedConfig.name("prices")`), enabling selective subscription from handlers via
  `getContext().subscribeToNamedFeed("prices")`.

See also:

- Guide: Event source plugin (how to implement) — [Event source plugin](../plugin/writing-an-event-source-plugin.md)
- Example: In-memory feed — `com.telamin.mongoose.connector.memory.InMemoryEventSource`

### 2) Services

Services provide reusable functionality within the server that other components can call. Examples include
scheduling (`SchedulerService`), admin commands, configuration, or domain utilities (e.g., pricing models, reference
cache loader).

- Typical responsibilities: encapsulate shared logic or infrastructure activities for processors; may run as
  agent-hosted services.
- Lifecycle: Registered in a service group, running on an agent (see `ComposingServiceAgent`).
- Integration: Accessed in processors via dependency injection using `@ServiceRegistered` methods.

See also:

- Guide: Service plugin — [Service plugin](../plugin/writing-a-service-plugin.md)
- SchedulerService and DeadWheelScheduler implementation

### 3) Event sinks

Event sinks accept events/messages from processors and deliver them out to external systems (e.g., logs, Kafka, files,
metrics).

- Typical responsibilities: serialize, batch, flush, and reliably write outbound data; handle retries/backpressure.
- Integration: Configure via `EventSinkConfig` and add to `MongooseServerConfig`. Processors publish to sinks via simple
  interfaces (e.g., `MessageSink<T>`).

See also:

- Guide: Message sink plugin — [Message sink plugin](../plugin/writing-a-message-sink-plugin.md)

## Wiring plugins into your application

All plugin types are declared in your application configuration and are hosted in agent groups that the server composes
at boot. The fluent builder is the recommended approach:

- Event feeds: `MongooseServerConfig.builder().addEventFeed(EventFeedConfig<...>)`
- Event sinks: `MongooseServerConfig.builder().addEventSink(EventSinkConfig<...>)`
- Services: `MongooseServerConfig.builder().addService(ServiceConfig<...>)` (or convenience methods if provided)
- Processor groups: `MongooseServerConfig.builder().addProcessorGroup(EventProcessorGroupConfig)`

Key concepts:

- Agent groups: Feeds and services run on their own agent thread; processors run in processor agent groups.
- Injection: `@ServiceRegistered` methods allow services (including sinks) to be injected into processors.
- Subscription: Processors declare interest in feeds by name using `getContext().subscribeToNamedFeed(name)`.

## Keeping business logic separate from infrastructure

To ensure clean separation:

- Depend on interfaces, not implementations:
    - Accept `MessageSink<T>` instead of concrete sink types.
    - Reference `SchedulerService` instead of a scheduler implementation.
- Use `@ServiceRegistered` for injection:
    - Processors expose a method like `wire(MessageSink<MyOut> sink)` or `scheduler(SchedulerService s)`.
    - The runtime calls these when the service/sink is available; no explicit wiring inside your business code.
- Subscribe to named feeds rather than pulling from concrete sources:
    - `getContext().subscribeToNamedFeed("prices")` makes your processor agnostic of whether the feed is Kafka,
    file-based, or in-memory.
- Avoid mixing IO with domain logic inside processors:
    - Processors should transform, aggregate, and make decisions.
    - Feeds handle inbound IO; sinks handle outbound IO; services provide cross-cutting utility.
- Prefer simple DTO/events between layers:
    - Let feeds decode/normalize payloads into domain events.
    - Processors handle those domain events and publish results to sinks.

Example injection in a processor:

```java
public class PricingHandler extends ObjectEventHandlerNode {
    private MessageSink<String> out;
    private SchedulerService scheduler;

    @ServiceRegistered
    public void wire(MessageSink<String> sink) { this.out = sink; }

    @ServiceRegistered
    public void scheduler(SchedulerService s) { this.scheduler = s; }

    @Override
    public void start() {
        getContext().subscribeToNamedFeed("prices");
    }

    @Override
    protected boolean handleEvent(Object event) {
        if (event instanceof PriceUpdate p) {
            String decision = evaluate(p);
            out.accept(decision);
        }
        return true;
    }

    private String evaluate(PriceUpdate p) {
        // domain logic only, no IO here
        return (p.price() > 100) ? "SELL" : "BUY";
    }

    record PriceUpdate(String symbol, double price) {}
}
```

Note how the handler:

  - Does not know which sink implementation it is using.
  - Does not know which underlying technology provides the "prices" feed.
  - Can optionally schedule future actions via `SchedulerService` without knowing threading details.

## Interaction model between handlers and plugins

- Event flow:
    - Event feed publishes events into the dispatch layer.
    - The dispatch invokes processors (according to the chosen `EventToInvokeStrategy`).
    - Processors publish outbound messages to sinks or call services for shared logic.

- Threading:
    - Feeds and services typically run on agent threads; processors run in a processor agent.
    - If a service (like the scheduler) calls back into the processor, prefer a trigger/event to re-enter the processor’s
    single-threaded context.

- Extensibility:
    - Add new feeds/services/sinks without changing processor code; update `MongooseServerConfig` wiring instead.

## When to create a plugin vs. inline code

Create a plugin when:

  - The functionality is reusable across processors or applications (e.g., outbound Kafka writer, auth token refresher).
  - It requires lifecycle management (start/stop), threading, or external IO.
  - It needs configuration that should be isolated from domain code.

Inline within a processor when:

  - It is pure domain logic, stateless or processor-scoped state, and does not perform external IO.

## See also (detailed guides)

- [Event sink plugin](../plugin/writing-a-message-sink-plugin.md)
- [Event source plugin](../plugin/writing-an-event-source-plugin.md)
- [Service plugin](../plugin/writing-a-service-plugin.md)
- [Service plugin as event feed](../plugin/writing-a-publishing-service-plugin.md)
- [Service plugin with custom dispatch](../plugin/writing-a-typed-invoke-publishing-service-plugin.md)
