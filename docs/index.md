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

### Quickstart: Hello Mongoose

Run the one-file example to see events flowing through a handler:
- Source: [HelloMongoose.java]({{source_root}}/main/java/com/telamin/mongoose/example/hellomongoose/HelloMongoose.java)

```java
public static void main(String[] args) {
    // 1) Business logic handler
    var handler = new ObjectEventHandlerNode() {
        @Override
        protected boolean handleEvent(Object event) {
            if (event instanceof String s) {
                System.out.println("Got event: " + s);
            }
            return true;
    }};

    // 2) Create an in-memory event feed (String payloads)
    var feed = new InMemoryEventSource<String>();

    // 3) Wire processor group with our handler
    var processorGroup = EventProcessorGroupConfig.builder()
            .agentName("processor-agent")
            .put("hello-processor", new EventProcessorConfig<>(handler))
            .build();

    // 4) Wire the feed on its own agent with a busy-spin idle strategy (lowest latency)
    var feedCfg = EventFeedConfig.builder()
            .instance(feed)
            .name("hello-feed")
            .broadcast(true)
            .agent("feed-agent", new BusySpinIdleStrategy())
            .build();

    // 5) Build the application config and boot the mongooseServer
    var mongooseServerConfig = MongooseServerConfig.builder()
            .addProcessorGroup(processorGroup)
            .addEventFeed(feedCfg)
            .build();

    // boot with a no-op record consumer
    var mongooseServer = MongooseServer.bootServer(
            mongooseServerConfig, rec -> {/* no-op */});

    // 6) Publish a few events
    feed.offer("hi");
    feed.offer("mongoose");

    // 7) Stop the mongooseServer (in real apps, you keep it running)
    mongooseServer.stop();
}
```

### Start here: Learn path
- Step 1: Quickstart — run the one-file example: [Hello Mongoose]({{source_root}}/main/java/com/telamin/mongoose/example/hellomongoose/HelloMongoose.java)
- Step 2: Learn the basics — [Event handling and business logic](overview/event-processing-architecture.md)
- Step 3: Do common tasks — [How-to guides](how-to/how-to-subscribing-to-named-event-feeds.md)
- Step 4: Understand internals — [Threading model](architecture/threading-model.md) and [Architecture overview](architecture/overview.md)

## Documentation is organized into the following sections:

- Start with the [Overview](overview/engineers-overview.md) to learn concepts and architecture.
- See [Event processing](overview/event-processing-architecture.md) where business logic meets event handling.
- See [Examples](guide/file-and-memory-feeds-example.md) for quick hands-on guidance.
- See [Plugins](plugin/writing-a-message-sink-plugin.md) for advice on writing plugins.
- Use [How-to guides](how-to/how-to-subscribing-to-named-event-feeds.md) for common tasks and extensions.

## Architecture and threading model for internals

- Threading model → [architecture/threading-model.md](architecture/threading-model.md)
- Architecture → [architecture/index.md](architecture/index.md)
- Event flow → [architecture/event-flow.md](architecture/event-flow.md)
- Sequence diagrams → [architecture/sequence-diagrams/index.md](architecture/sequence-diagrams/index.md)

If you find an issue or want to improve the docs, click “Edit this page” in the top right or open a PR on GitHub.
