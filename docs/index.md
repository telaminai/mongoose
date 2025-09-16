# Mongoose Server

Mongoose Server is a high‑performance, event‑driven library for building scalable event processing applications fast.
It wires sources, processors, sinks, and services for you, handling threading and lifecycle behind the scenes, so you
can focus on business logic.

Its plugin architecture lets you assemble pipelines from reusable components, including third‑party plugins from the
broader ecosystem. You can mix and match existing sources, transforms, and sinks, add your own logic where needed, and
get to a working system quickly without reinventing common building blocks.

Mongoose is an embeddable library: run multiple server instances inside a parent JVM application, or deploy it as a
standalone single‑server app — the same APIs support both.

## Why Mongoose Server?

* Process multiple event feeds: Merge data from many real-time sources and process in a single-threaded application
  handler.
* Build business logic fast: minimal learning curve, with no need to worry about threading, concurrency, or lifecycle.
* Performance: Agent‑based concurrency with configurable idle strategies enables very high throughput and predictable
  latency.
* Ease of development: Compose processors and services, configured via YAML or Java with built‑in service injection.
* Plugin architecture: Clean extension points for event feeds, sinks, services, and dispatch strategies so you can
  tailor the runtime.
* Plugin ecosystem: community plugins, including support for Kafka, Aeron, Chronicle, and more.
* Zero‑GC: Built-in object pooling to support zero‑GC event processing.
* Operational control: Admin commands, scheduling, logging/audit support, and dynamic event handler registration
  make operational control simpler.

### Performance at a glance

- At 1 million messages/second, latency statistics:
    - Avg ≈ 270 nanos (0.00027 ms), p99.999 ≈ 81 µs, Max ≈ 90.1 µs.
- Sustained 10 million messages/second with Zero‑GC.
- See detailed results in the benchmarks
  report: [Server benchmarks and performance](reports/server-benchmarks-and-performance.md).

## Build coordinates

=== "Maven"

    ``` xml
    <dependencies>
        <dependency>
            <groupId>com.telamin</groupId>
            <artifactId>mongoose</artifactId>
            <version>{{mongoose_version}}</version>
        </dependency>
    </dependencies>
    ```

=== "Gradle"

    ``` groovy
    implementation 'com.telamin:mongoose:{{mongoose_version}}'
    ```

## Quickstart: Hello Mongoose

Run the one-file example to see events flowing through a handler:

- GitHub project: [HelloMongoose](https://github.com/telaminai/hellomongoose)

### Micro Glossary:

- **Event feed**: A source of events (e.g., in‑memory, file, Kafka).
- **Processor**: Executes your handler on its own agent thread.
- **Handler**: Your business logic function that receives events.
- **Agent**: A named execution thread with a configurable idle strategy.
- **Idle strategy**: Controls how an agent waits (e.g., BusySpin for ultra‑low latency).
- **Broadcast**: When true, each event is delivered to all processors.

### Example code:

```java
public static void main(String[] args) {
    // 1) Business logic handler
    Consumer<Object> handler = event -> System.out.println(
            "thread:'" + Thread.currentThread().getName() + "' event: " + event);

    // 2) Create an in-memory event feed (String payloads)
    var feed = new InMemoryEventSource<String>();

    // 3) Build and boot mongoose server with an in-memory feed and handler using builder APIs
    var eventProcessorConfig = EventProcessorConfig.builder()
            .handlerFunction(handler)
            .name("hello-handler")
            .build();

    // 4) Wire the feed on its own agent with a busy-spin idle strategy (lowest latency)
    var feedConfig = EventFeedConfig.<String>builder()
            .instance(feed)
            .name("hello-feed")
            .broadcast(true)
            .agent("feed-agent", new BusySpinIdleStrategy())
            .build();
    
    // 5) Build the application config and boot the mongooseServer
    var app = MongooseServerConfig.builder()
            .addProcessor("processor-agent", eventProcessorConfig)
            .addEventFeed(feedConfig)
            .build();

    // 6) boot an embedded MongooseServer instance
    var mongooseServer = MongooseServer.bootServer(app);

    // 7) Publish a few events
    System.out.println("thread:'" + Thread.currentThread().getName() + "' publishing events\n");
    feed.offer("hi");
    feed.offer("mongoose");

    // 8) Cleanup (in a real app, keep running)
    mongooseServer.stop();
}
```

### Expected output

```console
thread:'main' publishing events

thread:'processor-agent' event: hi
thread:'processor-agent' event: mongoose
```

## Learning path
- Step 1: Quickstart — run the one-file example: [Hello Mongoose](https://github.com/telaminai/hellomongoose)
- Step 2: Learn the basics — [Event handling and business logic](overview/event-processing-architecture.md)
- Step 3: Do common tasks — [How-to guides](example/how-to/how-to-subscribing-to-named-event-feeds.md)
- Step 4: Understand internals — [Threading model](architecture/threading-model.md) and [Architecture overview](architecture/overview.md)

## Mongoose examples
GitHub repository [mongoose-examples](https://github.com/telaminai/mongoose-examples/).

## Documentation is organized into the following sections:

- Start with the [Overview](overview/engineers-overview.md) to learn concepts and architecture.
- See [Event processing](overview/event-processing-architecture.md) where business logic meets event handling.
- See [Examples](guide/file-and-memory-feeds-example.md) for quick hands-on guidance.
- See [Plugins](example/plugin/writing-a-message-sink-plugin.md) for advice on writing plugins.
- Use [How-to guides](example/how-to/how-to-subscribing-to-named-event-feeds.md) for common tasks and extensions.

## Architecture and threading model for internals

- Threading model → [architecture/threading-model.md](architecture/threading-model.md)
- Architecture → [architecture/index.md](architecture/index.md)
- Event flow → [architecture/event-flow.md](architecture/event-flow.md)
- Sequence diagrams → [architecture/sequence-diagrams/index.md](architecture/sequence-diagrams/index.md)

If you find an issue or want to improve the docs, click “Edit this page” in the top right or open a PR on GitHub.
