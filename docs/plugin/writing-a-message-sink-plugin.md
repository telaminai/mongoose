# Guide: Writing a Message Sink Plugin for Mongoose server

This guide explains how to implement and integrate a custom message sink (an output connector) for Mongoose server. A
message sink consumes values produced by event processors and forwards them to an external system (e.g., file, database,
HTTP, Kafka, etc.).

You’ll learn:

- When and how to create a sink plugin
- Extending `AbstractMessageSink` and implementing `sendToSink`
- Managing lifecycle (init/start/stop/tearDown)
- Using value mapping (`valueMapper`) to transform payloads
- Registering your sink via `EventSinkConfig` or `ServiceConfig`
- Optional: Hosting a sink on its own agent thread
- Testing patterns and common pitfalls

## When to write a sink

Create a custom sink when your application needs to publish events to an external target that is not provided
out-of-the-box. Examples: writing to a DB, pushing to a REST endpoint, publishing to a message broker, or serializing to
a bespoke format.

If your sink is IO-bound and simple (e.g., append a line to a file), you can keep it synchronous in `sendToSink`. For
heavier workloads or where you need back-pressure buffering, consider an agent-hosted service that pulls from a queue
and writes asynchronously (see Agent-hosted sinks).

## Base class: AbstractMessageSink

All sinks should extend `com.fluxtion.runtime.output.AbstractMessageSink<T>`. This base class:

- Exposes `accept(Object value)` to upstream processors.
- Applies an optional `valueMapper` before calling your sink.
- Delegates final output to your implementation via `protected void sendToSink(T value)`.

The generic type parameter `T` denotes the post-mapping type your sink expects (often `Object` for general-purpose
sinks). For example sinks in this repo use `Object`:

- File sink: `FileMessageSink extends AbstractMessageSink<Object>`
- In-memory sink (for testing): `InMemoryMessageSink extends AbstractMessageSink<Object>`

## Minimal implementation

Below is a skeleton you can copy. Replace the TODOs with your target integration:

```java
package com.mycompany.connector;

import com.fluxtion.runtime.lifecycle.Lifecycle;
import com.fluxtion.runtime.output.AbstractMessageSink;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.java.Log;

@Log
public class MyCustomMessageSink 
        extends AbstractMessageSink<Object> 
        implements Lifecycle {

    @Getter @Setter
    private String endpoint; // e.g., file name, URL, topic, etc.

    // Acquire resources (connections, clients) here
    @Override
    public void init() {
        // no-op or lightweight setup
    }

    @Override
    public void start() {
        // open connections/resources; e.g., create HTTP client, open file handle
        // throw a RuntimeException if the sink can't start
    }

    @Override
    protected void sendToSink(Object value) {
        // called by AbstractMessageSink.accept() after valueMapper is applied
        // perform the final write/publish
        // Examples:
        // - printStream.println(value)
        // - httpClient.post(endpoint, value)
        // - producer.send(topic, serialize(value))
    }

    @Override
    public void stop() {
        // flush & close resources (idempotent)
    }

    @Override
    public void tearDown() {
        stop();
    }
}
```

Key points:

- Implement `sendToSink(value)` with the minimum synchronous work needed to persist or publish the value.
- If your target may block for a long time, consider a non-blocking approach (queue + background agent) instead of doing
  heavy work in `sendToSink`.

## Value mapping (valueMapper)

Upstream processors call `accept(event)` with arbitrary objects. If your sink expects a specific representation (e.g.,
JSON string), configure a mapper to transform inputs before `sendToSink` is called.

- Programmatic configuration via `EventSinkConfig`:

```java
import com.telamin.mongoose.config.EventSinkConfig;

EventSinkConfig<MyCustomMessageSink> sinkCfg = EventSinkConfig.builder()
        .instance(mySink)
        .name("mySink")
        .valueMapper((Object in) -> toJson(in)) // map to JSON string
        .build();
```

- Or set mapper directly on the sink instance:

```java
mySink.setValueMapper((Object in) -> toJson(in));
```

The server will call `accept(value)`, which applies the mapper and then calls `sendToSink(mappedValue)`.

## Registering your sink with the server

There are two common ways to register sinks:

1) Preferred: `EventSinkConfig` (works with any `MessageSink<?>`)

```java
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.EventSinkConfig;

MyCustomMessageSink mySink = new MyCustomMessageSink();
mySink.setEndpoint("/tmp/out.log");

EventSinkConfig<MyCustomMessageSink> sinkCfg = EventSinkConfig.builder()
        .instance(mySink)
        .name("mySink")
        .build();

MongooseServerConfig app = MongooseServerConfig.builder()
        .addEventSink(sinkCfg)
        .build();
```

2) Alternative: Register as a generic service (works for any type)

```java
import com.telamin.mongoose.config.ServiceConfig;

ServiceConfig<MyCustomMessageSink> svc = ServiceConfig.builder()
        .service(mySink)
        .serviceClass(MyCustomMessageSink.class)
        .name("mySink")
        .build();

MongooseServerConfig app = MongooseServerConfig.builder()
        .addService(svc)
        .build();
```

When using the fluent builder, the server injects registered services into processors that declare them (via
`@ServiceRegistered`). For example:

```java
public class MyHandler extends ObjectEventHandlerNode {
    private MessageSink sink;

    @ServiceRegistered
    public void wire(MessageSink sink, String name) {
        this.sink = sink;
    }

    @Override
    protected boolean handleEvent(Object event) {
        if (sink != null) sink.accept(event);
        return true;
    }
}
```

See also: the example guide wiring `FileMessageSink` and `InMemoryEventSource` in
`docs/guide/file-and-memory-feeds-example.md`.

## Agent-hosted sinks (optional)

If your sink needs its own thread (e.g., to buffer and flush asynchronously), you can host it on an agent thread by
making the sink also implement Agrona `Agent` or wrapping with a worker service pattern. In most cases, output sinks can
remain simple `Lifecycle` components without an agent.

To run a sink on its own agent thread via `EventSinkConfig`:

```java
EventSinkConfig<MyCustomMessageSink> sinkCfg = EventSinkConfig.builder()
        .instance(mySink)
        .name("mySink")
        .agent("sink-agent-thread", new BusySpinIdleStrategy())
        .build();

MongooseServerConfig app = MongooseServerConfig.builder()
        .addEventSink(sinkCfg)
        .build();
```

Note: For this to work, the `instance` must also implement `com.fluxtion.agrona.concurrent.Agent` (or be wrapped in a
custom ServiceAgent). If your sink does not implement `Agent`, omit the `agent(...)` configuration.

## Testing your sink

- For sinks writing to files: open the file on `start()`, write in `sendToSink`, flush/close on `stop()`.
- For in-memory sinks: collect values into a thread-safe list; expose a snapshot getter. See
  `com.telamin.mongoose.connector.memory.InMemoryMessageSink`.
- Unit test pattern used in this repo:
    - Create a testable subclass that exposes a public method calling `sendToSink` (since it’s protected) or test via
      `accept` with a mapper if needed.
    - Drive lifecycle methods (`init()`, `start()`, `stop()`).

Example from this repository (`FileMessageSinkTest` style):

```java
static class TestableMySink extends MyCustomMessageSink {
    public void write(Object value) { super.sendToSink(value); }
}
```

## Common pitfalls and tips

- Don’t perform heavy blocking operations inside `sendToSink` if your processors are latency sensitive; consider an
  internal queue + background agent.
- Use `valueMapper` to isolate serialization/formatting from transport code.
- Make `stop()` idempotent and always close or release resources.
- If using files, create parent directories and use UTF-8 consistently.
- Log at `FINE` or `FINER` levels inside hot paths to avoid overhead; prefer guarded logs.

## Reference implementations in this repo

- File
  sink: [FileMessageSink.java]({{source_root}}/main/java/com/telamin/mongoose/connector/file/FileMessageSink.java) —
  appends
  each published message as a line to a file.
- In-memory sink (for
  testing): [InMemoryMessageSink.java]({{source_root}}/main/java/com/telamin/mongoose/connector/memory/InMemoryMessageSink.java) —
  accumulates messages in memory.
- End-to-end usage: [file-and-memory-feeds-example.md](../guide/file-and-memory-feeds-example.md) — shows processor
  wiring and
  registering sinks with `EventSinkConfig`.

With this structure, you can implement custom sinks for any target in a few lines, register them with `MongooseServerConfig`, and
begin publishing from your processors immediately.