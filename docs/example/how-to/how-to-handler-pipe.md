# How-to: Use HandlerPipe for in-VM communication between handlers

HandlerPipe is a lightweight, in-VM pipe for sending messages from one handler (or service) to other handlers via Mongoose's event flow, without external IO.

It couples:
- a publish-side MessageSink (sink()) that you call to send data, and
- a receive-side InMemoryEventSource (getSource()) that integrates with the event flow, allowing processors to subscribe.

## When to use
- You want handlers to talk to each other inside the same JVM without setting up external transports.
- You want lifecycle-aware dispatch: cache events before startComplete and replay them once the system is ready.
- You want to reuse Mongoose’s subscription, wrapping, and data-mapping features.

## Two configuration shapes

There are two ways to wire a `HandlerPipe` into a Mongoose server. Both produce
the same runtime; pick the one that reads better for your call site.

### Declarative — `HandlerPipeConfig` (recommended for boot-time wiring)

A single `addPipe(...)` call on the server builder registers **both halves** of
the pipe under one logical name:

```java
MongooseServerConfig cfg = MongooseServerConfig.builder()
        .addPipe(HandlerPipeConfig.builder()
                .name("orders")                                  // feed-side name
                .broadcast(true)
                .cacheEventLog(true)
                .agent("pipe-agent", new SleepingMillisIdleStrategy(1))
                .build())
        .addProcessorGroup(...)                                  // publishers + subscribers
        .build();
```

After boot:

- **Subscribers** reach the pipe via `subscribeToNamedFeed("orders")`.
- **Publishers** receive the sink via
  `@ServiceRegistered void onSink(MessageSink<?> sink, String name)` with
  `name = "orders.sink"` (default `.sink` suffix; override with `sinkName(...)`
  on the builder).

The two-name convention works around Mongoose's name-keyed service registry —
which rejects same-name registrations even with different service classes. The
suffix is small operator-facing cost; the upside is one config entry per pipe
instead of separate `addEventFeed` + `addEventSink` calls that must stay in sync.

YAML equivalent — drop into `config/server.yml`:

```yaml
pipes:
  - name: orders
    broadcast: true
    cacheEventLog: true
    agentName: pipe-agent
    idleStrategy: !!org.agrona.concurrent.SleepingMillisIdleStrategy {}
    # sinkName: orders-in   # optional override of the default ".sink" suffix
```

Cross-thread safe — the underlying `InMemoryEventSource` extends
`AbstractAgentHostedEventSourceService`, so the publisher's agent thread
enqueues, the pipe's agent thread drains, and the subscriber's agent thread
receives. Producer and consumer can sit on independent agent groups without
explicit synchronization.

### How pipes appear in the admin web

Pipes are tracked separately from regular services so the admin surface
can render them as one logical entity rather than two unrelated
`<name>` + `<name>.sink` rows:

- **`/api/pipes`** returns the list of configured pipes:
  `{name, sinkName, agentName, broadcast, cacheEventLog}`. The admin
  fetches this alongside `/api/services` + `/api/agents`.
- **Overview · Pipes card** lists one row per pipe with both endpoint
  names, agent, and flags (broadcast / cache).
- **Topology** view collapses the two halves into a single
  diamond-shaped `pipe` node with both directions wired — incoming
  arrows from publisher processors (sink-side), outgoing arrows to
  subscriber agent groups (feed-side). The two underlying service
  rows in the Feeds + Sinks Overview cards are suppressed so they
  aren't double-counted; they're still discoverable in the Services
  list view if an operator wants the raw view.

`MongooseServerController` adds a `registeredPipes()` method (default
returns empty for any controller that hasn't been updated). Operators
building custom admin surfaces consume the same list to render pipes
in their own way.

### Programmatic — direct `HandlerPipe` construction

When you need the pipe instance available at boot time (e.g. to inject it into
a service constructor), construct it directly and wire the source side as an
`EventFeedConfig`:

```java
// Create a pipe for a logical feed name
HandlerPipe<String> pipe = HandlerPipe.<String>of("ordersFeed").cacheEventLog(true);

// Wire the receive-side into your server configuration (pseudo-code):
MongooseServerConfig cfg = new MongooseServerConfig();
cfg.addService("ordersFeedService", pipe.getSource());

// In your processor(s), subscribe to the source by service name
pipe.getSource().subscribe(); // typically invoked during composition/registration

// Publish from anywhere within the JVM
pipe.sink().accept("order-123-created");
```

Notes:
- `cacheEventLog(true)` will cache any events published before `startComplete`
  and replay them automatically when the source calls `startComplete()`.
- You can customise data mapping and wrapping like other `EventSource` services.

## Sample code

- Processor
  source: [HandlerPipeServerBootExample.java]({{source_root}}/test/java/com/telamin/mongoose/example/handlerpipe/HandlerPipeServerBootExample.java)
- Test
  node: [HandlerPipeTest.java]({{source_root}}/test/java/com/telamin/mongoose/connector/memory/HandlerPipeTest.java#L17)
- Complete how-to example: [HandlerPipe Example](https://github.com/telaminai/mongoose-examples/blob/229e01e2f508bdf084a611677dc93c1174c96bdc/how-to/handler-pipe)

## Lifecycle semantics
HandlerPipe delegates lifecycle to InMemoryEventSource:
- start(): If cacheEventLog is true, publishes are cached (not dispatched).
- startComplete(): Cached events are replayed to subscribers, subsequent publishes dispatch immediately.

You can still push items before start() using pipe.sink().accept(...); they will be cached if caching is enabled and replayed later.

## Controlling wrapping and mapping

- Wrapping: choose how events are wrapped for subscribers.
```java
HandlerPipe<String> pipe = HandlerPipe.of("myFeed", EventSource.EventWrapStrategy.SUBSCRIPTION_NOWRAP);
```

- Data mapping: transform outgoing items before dispatch.
```java
pipe.dataMapper((String s) -> s.toUpperCase());
```

## Testing and local observation
To observe dispatches without a full server, attach a queue to the publisher used by the source. In tests, we use EventToQueuePublisher and a OneToOneConcurrentArrayQueue:

```java
HandlerPipe<String> pipe = HandlerPipe.<String>of("handlerPipeFeed").cacheEventLog(true);
EventToQueuePublisher<String> eventToQueue = new EventToQueuePublisher<>("handlerPipeFeed");
OneToOneConcurrentArrayQueue<Object> targetQueue = new OneToOneConcurrentArrayQueue<>(128);
eventToQueue.addTargetQueue(targetQueue, "outputQueue");
pipe.getSource().setOutput(eventToQueue); // test hook on source for injection

pipe.getSource().start();
pipe.sink().accept("a");
pipe.sink().accept("b");

// No items dispatched until startComplete when caching
targetQueue.drainTo(new ArrayList<>(), 100); // empty

pipe.getSource().startComplete();
// Now queue drains ["a", "b"]
```

For simplistic in-memory collection of published values, consider InMemoryMessageSink. You can replace the default sink by wrapping or delegating to pipe.sink().

## Full server boot example

A complete, runnable example is available here:
- Path: src/test/java/com/telamin/mongoose/example/handlerpipe/HandlerPipeServerBootExample.java

Key parts of the example:

- Create the pipe and a processor that subscribes to its feed name, plus an in-memory sink:
```java
HandlerPipe<String> pipe = HandlerPipe.<String>of("examplePipe").cacheEventLog(true);
NamedFeedsFilterHandler handler = new NamedFeedsFilterHandler(java.util.Set.of(pipe.getSource().getName()));
InMemoryMessageSink sink = new InMemoryMessageSink();
```

- Wire the pipe’s source as an EventFeed and boot the server:
```java
EventProcessorGroupConfig processors = EventProcessorGroupConfig.builder()
        .agentName("processor-agent")
        .put("pipe-listener", new EventProcessorConfig(handler))
        .build();

EventFeedConfig<?> pipeFeed = EventFeedConfig.builder()
        .instance(pipe.getSource())
        .name(pipe.getSource().getName())
        .broadcast(true)
        .agent("pipe-agent", new BusySpinIdleStrategy())
        .build();

EventSinkConfig<MessageSink<?>> sinkCfg = EventSinkConfig.<MessageSink<?>>builder()
        .instance(sink)
        .name("memSink")
        .build();

MongooseServerConfig mongooseServerConfig = MongooseServerConfig.builder()
        .addProcessorGroup(processors)
        .addEventFeed(pipeFeed)
        .addEventSink(sinkCfg)
        .build();

MongooseServer server = MongooseServer.bootServer(mongooseServerConfig, rec -> {});
```

- Publish via the pipe and observe results in the sink:
```java
pipe.sink().accept("hello");
pipe.sink().accept("world");
List<Object> out = waitForMessages(sink, 2, 5, TimeUnit.SECONDS);
```

See the full file for the waitForMessages helper and assertions.

## Tips
- Use a descriptive feed name; processors subscribe by service name.
- Prefer small, concise data mappers on the source side when transforming events.
- For backpressure/slow-consumer concerns, see EventToQueuePublisher settings (wrapping, logging). The pipe uses the same underlying publisher infrastructure via the source.
