# Guide: Writing an Event Source Plugin for Mongoose server

This guide explains how to implement a custom event source (input connector) that publishes events into Mongoose
Server’s event flow. You will learn how to:

- Choose the right base class for your source
- Implement lifecycle and (optionally) agent work loops
- Publish events safely and efficiently (with optional pre-start caching)
- Configure wrapping, slow-consumer strategy, and data mapping
- Register your source with MongooseServerConfig via EventFeedConfig
- Test your event source

Reference implementations in this repository:

- File-based
  source: [FileEventSource.java]({{source_root}}/main/java/com/telamin/mongoose/connector/file/FileEventSource.java)
- In-memory
  source: [InMemoryEventSource.java]({{source_root}}/main/java/com/telamin/mongoose/connector/memory/InMemoryEventSource.java)
- Base
  classes: [AbstractEventSourceService.java]({{source_root}}/main/java/com/telamin/mongoose/service/extension/AbstractEventSourceService.java)
  and [AbstractAgentHostedEventSourceService.java]({{source_root}}/main/java/com/telamin/mongoose/service/extension/AbstractAgentHostedEventSourceService.java)

Complete working example in the mongoose-examples repository:

- [Event Source Example](https://github.com/telaminai/mongoose-examples/tree/main/plugins/event-source-example) - A complete example demonstrating how to create a custom event source that generates heartbeat events at regular intervals
  - [HeartBeatEventFeed.java](https://github.com/telaminai/mongoose-examples/blob/main/plugins/event-source-example/src/main/java/com/telamin/mongoose/example/eventsource/HeartBeatEventFeed.java) - A custom event source that generates heartbeat events at regular intervals
  - [EventSourceExample.java](https://github.com/telaminai/mongoose-examples/blob/main/plugins/event-source-example/src/main/java/com/telamin/mongoose/example/eventsource/EventSourceExample.java) - Main application showing how to configure and use the custom event source

## When to write a source

Create a custom source when events originate outside your processors and must be injected into the server, e.g.:

- Tail a file, read a socket/HTTP stream, consume from Kafka/JMS/MQTT, poll a DB, integrate a custom driver, etc.

If your source needs its own thread and a non-blocking work loop, implement an agent-hosted source. If events are pushed
from an external library callback (and you don’t need your own loop), a non-agent source may be sufficient.

## Pick a base class

- AbstractEventSourceService<T>
    - Use when your source does not need an Agrona Agent; e.g., you receive callbacks from another component and can
      forward to event handlers.
    - You get lifecycle hooks (init/start/stop/tearDown) and are wired into the event flow.

- AbstractAgentHostedEventSourceService<T>
    - Extends AbstractEventSourceService and implements com.fluxtion.agrona.concurrent.Agent.
    - Use when your source runs its own work loop (doWork), like file tailing, network reads, or periodic polling.
    - Provides roleName() for agent diagnostics.

Both base classes take care of registration with the EventFlowManager and provide an EventToQueuePublisher<T> named
output you use to publish events.

## Lifecycle and publishing

Typical lifecycle flow:

- init(): light setup
- start(): allocate IO/resources, set up caching or state
- startComplete(): server signals that the system is ready; you may switch from caching to publishing and replay cached
  events
- doWork(): agent loop (only for agent-hosted sources)
- stop()/tearDown(): release resources

Publishing APIs available via this.output:

- output.publish(item): map (via dataMapper) and dispatch immediately to subscribed queues
- output.cache(item): record in the event log cache but do not dispatch yet
- output.setCacheEventLog(boolean): enable/disable event log caching
- output.dispatchCachedEventLog(): dispatch any cached events to queues (useful at startComplete)
- output.getEventLog(): snapshot of NamedFeedEvent entries when caching is enabled

Wrapping and mapping are applied at dispatch time by EventToQueuePublisher.

## Minimal agent-hosted source (skeleton)

```java
package com.mycompany.source;

import com.fluxtion.runtime.event.NamedFeedEvent;
import com.telamin.mongoose.service.extension.AbstractAgentHostedEventSourceService;
import lombok.extern.java.Log;

import java.util.List;

@Log
public class MyAgentSource 
        extends AbstractAgentHostedEventSourceService<Object> {

    private boolean publishToQueue;

    public MyAgentSource() {
        super("myAgentSource");
    }

    @Override
    public void start() {
        // Enable pre-start caching if you need to read historic/backlog 
        // before serving live
        output.setCacheEventLog(true); // optional
        publishToQueue = false;        // cache until startComplete
        // Open resources (files, sockets, clients) here
    }

    @Override
    public void startComplete() {
        publishToQueue = true;
        // Replay cached data once the system is ready
        output.dispatchCachedEventLog();
    }

    @Override
    public int doWork() throws Exception {
        int work = 0;
        // 1) Read or poll your data source
        Object next = readNextOrNull();
        while (next != null) {
            if (publishToQueue) {
                output.publish(next);
            } else {
                output.cache(next);
            }
            work++;
            next = readNextOrNull();
        }
        return work;
    }

    private Object readNextOrNull() {
        // TODO: implement a non-blocking read that returns null 
        //  when no data is available
        return null;
    }

    @Override
    public void stop() {
        // Close resources
    }

    @Override
    public <T> NamedFeedEvent<T>[] eventLog() {
        List<NamedFeedEvent> log = (List) output.getEventLog();
        return log.toArray(new NamedFeedEvent[0]);
    }
}
```

## Minimal non-agent source (callback-driven)

```java
package com.mycompany.source;

import com.telamin.mongoose.service.extension.AbstractEventSourceService;

public class MyCallbackSource extends AbstractEventSourceService<String> {

    private boolean publishToQueue;

    public MyCallbackSource() {
        super("myCallbackSource");
    }

    @Override
    public void start() {
        // If you expect callbacks to start arriving before the server is fully ready
        output.setCacheEventLog(true);
        publishToQueue = false;
        // Register external callbacks here, e.g., client.onMessage(this::onData)
    }

    @Override
    public void startComplete() {
        publishToQueue = true;
        output.dispatchCachedEventLog();
    }

    // Example external callback
    public void onData(String value) {
        if (publishToQueue) {
            output.publish(value);
        } else {
            output.cache(value);
        }
    }
}
```

## Event wrapping, slow-consumer policy, and data mapping

Event wrapping determines how items are written to queues:

- SUBSCRIPTION_NOWRAP: publish raw mapped item only to subscribed processors
- SUBSCRIPTION_NAMED_EVENT: wrap in NamedFeedEvent for subscribers (default in EventFeedConfig)
- BROADCAST_NOWRAP / BROADCAST_NAMED_EVENT: deliver to all handlers regardless of subscription

Slow-consumer policy hints (currently managed internally):

- BACKOFF (default) — used by EventToQueuePublisher to avoid long blocking on contended queues

Data mapping lets you transform T->U before dispatch (e.g., parse lines, decode bytes):

- In code: setDataMapper(Function<T, ?>)
- Via config builder: EventFeedConfig.Builder#valueMapper

## Register your source with MongooseServerConfig

Use EventFeedConfig to add your source, control wrapping/broadcast, and optionally host it on an agent thread.

```java
import com.fluxtion.agrona.concurrent.BusySpinIdleStrategy;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.EventFeedConfig;

MyAgentSource src = new MyAgentSource();

EventFeedConfig<?> feed = EventFeedConfig.builder()
        .instance(src)
        .name("myFeed")
        // false to require explicit subscription or true to allow broadcast
        .broadcast(true)
        .wrapWithNamedEvent(true)        
        // required if source is Agent-hosted
        .agent("my-source-agent", new BusySpinIdleStrategy()) 
        .build();

MongooseServerConfig app = MongooseServerConfig.builder()
        .addEventFeed(feed)
        .build();
```

Notes:

- If your source implements Agent (i.e., extends AbstractAgentHostedEventSourceService), supply agent(name,
  idleStrategy) in EventFeedConfig.
- If it’s not agent-hosted, omit the agent() call.

Under the hood, ServerConfigurator will translate your EventFeedConfig into a Service or ServiceAgent and register it
with MongooseServer. During registration, AbstractEventSourceService wires itself to the EventFlowManager and prepares
the EventToQueuePublisher output.

## Pre-start caching pattern

If you need to read an initial backlog before serving live (or avoid dropping events during server startup):

- Call output.setCacheEventLog(true) in start()
- Cache(events) until startComplete()
- On startComplete(), set a flag to publish and call output.dispatchCachedEventLog()

This pattern is used by FileEventSource and InMemoryEventSource in this repo.

## Testing your source

- Unit test the doWork() loop (for agent-hosted) or the callback path (for non-agent).
- Inject a test EventToQueuePublisher and a OneToOneConcurrentArrayQueue as the target queue; drain and assert outputs.
- Verify event log when caching is enabled via output.getEventLog().
- See src/test/java/com/telamin/mongoose/connector/file/FileEventSourceTest.java and
  src/test/java/com/telamin/mongoose/connector/memory/InMemoryEventSourceTest.java for patterns.

Example snippet:

```text
// Example-only snippet for tests (not part of a compilable class)
// (wrap in a method/class in your test harness before compiling):
EventToQueuePublisher<String> pub = new EventToQueuePublisher<>("myFeed");
OneToOneConcurrentArrayQueue<Object> q = new OneToOneConcurrentArrayQueue<>(128);
pub.addTargetQueue(q, "out");
mySource.setOutput(pub); // add a package-private setter in tests if needed

mySource.start();
mySource.startComplete();
mySource.offer("x");
mySource.doWork();

List<Object> drained = new ArrayList<>();
q.drainTo(drained, 10);
assertEquals(List.of("x"), drained.stream().map(Object::toString).toList());
```

## Tips and pitfalls

- Avoid blocking indefinitely in doWork(); use non-blocking reads or time-bounded IO. Let the agent idle strategy handle
  waits.
- Guard logging in hot paths (use log.isLoggable) to avoid overhead.
- Keep data mapping simple and resilient; report mapping errors via the built-in error reporting when appropriate (
  EventToQueuePublisher logs mapping errors).
- Ensure stop() is idempotent and always closes resources.
- For file-like sources, consider read strategies (earliest, committed, latest); see FileEventSource for an example
  approach.

## See also

- [FileEventSource.java]({{source_root}}/main/java/com/telamin/mongoose/connector/file/FileEventSource.java)
  and [InMemoryEventSource.java]({{source_root}}/main/java/com/telamin/mongoose/connector/memory/InMemoryEventSource.java)
  for
  complete, working examples
- [docs/guide/file-and-memory-feeds-example.md](../guide/file-and-memory-feeds-example.md) for end-to-end wiring with
  processors
  and sinks
- [AbstractEventSourceService]({{source_root}}/main/java/com/telamin/mongoose/service/extension/AbstractEventSourceService.java), [AbstractAgentHostedEventSourceService]({{source_root}}/main/java/com/telamin/mongoose/service/extension/AbstractAgentHostedEventSourceService.java)
  for lifecycle and wiring details
- [Event Source Example](https://github.com/telaminai/mongoose-examples/tree/main/plugins/event-source-example) in the mongoose-examples repository:
  - [HeartBeatEventFeed.java](https://github.com/telaminai/mongoose-examples/blob/main/plugins/event-source-example/src/main/java/com/telamin/mongoose/example/eventsource/HeartBeatEventFeed.java) - A custom event source that generates heartbeat events at regular intervals
  - [EventSourceExample.java](https://github.com/telaminai/mongoose-examples/blob/main/plugins/event-source-example/src/main/java/com/telamin/mongoose/example/eventsource/EventSourceExample.java) - Main application showing how to configure and use the custom event source
  - [HeartbeatEvent.java](https://github.com/telaminai/mongoose-examples/blob/main/plugins/event-source-example/src/main/java/com/telamin/mongoose/example/eventsource/HeartbeatEvent.java) - The event class used by the custom event source
