# How to transform incoming feed events to a different type (value mapping)

This guide shows how to use an EventFeed value mapper to transform incoming events into a different type before they
reach your processors. We will:

- Provide an input event type (e.g., TestEvent_In)
- Implement a mapper Function<Input, ?> that converts Input to your target type
- Attach the mapper to an EventFeedConfig via .valueMapper(...)
- Subscribe to the named feed from a processor and handle the mapped output

The complete, runnable example is in:

- Example: [ExampleDataMapping.java]({{source_root}}/test/java/com/telamin/mongoose/example/datamapper/ExampleDataMapping.java)
- Complete how-to example: [Data Mapping Example](https://github.com/telaminai/mongoose-examples/blob/229e01e2f508bdf084a611677dc93c1174c96bdc/how-to/data-mapping)

## When to use value mapping

Use a value mapper when the source payload needs transformation before downstream processing:

- Parsing/normalizing inbound records
- Mapping DTOs or external wire formats into internal domain objects
- Filtering or projecting fields to a simpler structure

Because mapping occurs at the feed boundary, processors can stay focused on business logic and operate on the unified
(target) type.

## Minimal example

Below is the essence of the ExampleDataMapping sample. The mapper adapts TestEvent_In to the type your processor
expects. The processor is implemented as an ObjectEventHandlerNode and subscribes to the named feed.

### 1) Create a mapper Function<Input, ?>

```java
package com.telamin.mongoose.example.datamapper;

import java.util.function.Function;

// Example input type arriving at the EventFeed
public record TestEvent_In(String message) {}

// Mapper converts TestEvent_In -> TestEvent (or any target type you need)
public class TestDataMapper implements Function<TestEvent_In, Object> {
    @Override
    public Object apply(TestEvent_In in) {
        // Minimal transformation example: wrap into a TestEvent domain type
        return new TestEvent(in.message());
    }
}
```

Notes:

- The mapper can return any type (Object) that your downstream processor expects.
- You can perform validation, enrichment, filtering, or even return null to drop an event.

### 2) Configure an EventFeed with the value mapper

```java
TestEventSource exampleSource = new TestEventSource("exampleSource");
Function<TestEvent_In, ?> mapper = new TestDataMapper();

EventFeedConfig<TestEvent_In> feedCfg = EventFeedConfig
        .<TestEvent_In>builder()
        .instance(exampleSource)
        .name("exampleEventFeed")
        .valueMapper(mapper)          // map incoming TestEvent_In to target type
        .broadcast(true)
        .wrapWithNamedEvent(false)
        .build();
```


Notes:

- name("exampleEventFeed"): gives the feed a name so processors can subscribe by name.
- valueMapper(mapper): performs the transformation before the event reaches subscribers.
- broadcast(true): make events available to all subscribers.
- wrapWithNamedEvent(false): deliver the payload directly. Set to true if you need the NamedEvent wrapper.

### 3) Implement a processor and subscribe by feed name

```java
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;

public class TestEventProcessor extends ObjectEventHandlerNode {
    private volatile TestEvent last;

    @Override
    public void start() {
        // Subscribe to the mapped output from the feed named "exampleEventFeed"
        getContext().subscribeToNamedFeed("exampleEventFeed");
    }

    @Override
    protected boolean handleEvent(Object event) {
        // After mapping, events arriving here are TestEvent
        if (event instanceof TestEvent te) {
            last = te;
            // ... business logic
        }
        return true; // continue processing chain
    }

    public TestEvent getLastProcessedEvent() { return last; }
}
```

### 4) Wire everything and boot the server

```java
EventProcessorConfig<?> processorCfg = EventProcessorConfig
        .builder()
        .customHandler(new TestEventProcessor())
        .build();

EventProcessorGroupConfig groupCfg = EventProcessorGroupConfig.builder()
        .agentName("exampleGroup")
        .put("exampleProcessor", processorCfg)
        .build();

MongooseServerConfig config = MongooseServerConfig.builder()
        .addProcessorGroup(groupCfg)
        .addEventFeed(feedCfg)
        .build();

MongooseServer server = MongooseServer.bootServer(config, rec -> {});
```

### 5) Publish input events (they will be mapped before processing)

```java
TestEvent_In in = new TestEvent_In("hello-world");
exampleSource.publishEvent(in);
```

Your processor receives TestEvent produced by the mapper.

## Tips and considerations

- Mapper return type: Choose a stable domain type for downstream logic. You can also return null to drop events.
- Error handling: Consider guarding against malformed inputs in the mapper.
- Named feeds: Keep feed names stable; they are the contract between sources and processors when using subscribeToNamedFeed(...).
- Named wrapper: If you need the feed name at the processor, set wrapWithNamedEvent(true) and adjust payload handling accordingly.
- Performance: Complex mapping can be offloaded to dedicated mappers for reuse and testability.

## Using object pooling in mappers

For high-throughput scenarios, your mapping function can reuse objects via the built-in object pooling service. Any
mapper (Function<In, Out>) can declare a @ServiceRegistered method to receive the ObjectPoolsRegistry and lazily obtain
a pool.

Key points:

- Annotate a method with @ServiceRegistered inside your mapper; it will be invoked when the mapper is registered as a service through the feed config.
- Inject ObjectPoolsRegistry to get or create an ObjectPool<T> for your output type.
- Acquire from the pool in apply(...), populate fields, and return the pooled instance.
- Ensure pooled types define a reset method used by the pool to clear state on release.

Example (based on [PoolingDataMapper.java]({{source_root}}/test/java/com/telamin/mongoose/pool/PoolingDataMapper.java)):


```java
package com.telamin.mongoose.pool;

import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.mongoose.service.pool.ObjectPool;
import com.telamin.mongoose.service.pool.ObjectPoolsRegistry;

import java.util.function.Function;

public class PoolingDataMapper implements Function<PooledMessage, MappedPoolMessage> {

    private ObjectPool<MappedPoolMessage> pool;

    @ServiceRegistered
    public void registerObjectPool(ObjectPoolsRegistry objectPoolsRegistry, String name) {
        this.pool = objectPoolsRegistry.getOrCreate(
                MappedPoolMessage.class,
                MappedPoolMessage::new,
                MappedPoolMessage::reset);
    }

    @Override
    public MappedPoolMessage apply(PooledMessage pooledMessage) {
        MappedPoolMessage out = pool.acquire();
        out.setValue(pooledMessage.value);
        return out; // returned to downstream; pool will reclaim once processing completes
    }
}
```

Notes:

- The second parameter (String name) in the @ServiceRegistered method receives the logical service name, which can help derive pool names if needed.
- ObjectPoolsRegistry is available by default because the server registers it during boot.

## Full example reference

```java
public class ExampleDataMapping {

    public static void main(String[] args) throws Exception {
        // Latch to verify we processed one event
        CountDownLatch latch = new CountDownLatch(1);

        // Create processor instance and wrap in builder config
        TestEventProcessor processor = new TestEventProcessor(latch);
        EventProcessorConfig<?> processorCfg = EventProcessorConfig
                .builder()
                .customHandler(processor)
                .build();

        // Group config with a named processor
        EventProcessorGroupConfig groupCfg = EventProcessorGroupConfig.builder()
                .agentName("exampleGroup")
                .put("exampleProcessor", processorCfg)
                .build();


        // Create an event source value mapper
        Function<TestEvent_In, ?> testDataMapper = new TestDataMapper();

        // Create an event source (feed) and config
        TestEventSource exampleSource = new TestEventSource("exampleSource");
        EventFeedConfig<TestEvent_In> feedCfg = EventFeedConfig
                .<TestEvent_In>builder()
                .instance(exampleSource)
                .name("exampleEventFeed")
                .valueMapper(testDataMapper)
                .broadcast(true)
                .wrapWithNamedEvent(false)
                .build();

        // Build app config and boot server
        MongooseServerConfig config = MongooseServerConfig.builder()
                .addProcessorGroup(groupCfg)
                .addEventFeed(feedCfg)
                .build();

        MongooseServer server = MongooseServer.bootServer(config, l ->{});
        try {
            // Publish one event and wait for processing
            TestEvent_In event = new TestEvent_In("hello-world");
            exampleSource.publishEvent(event);

            boolean received = latch.await(15, TimeUnit.SECONDS);
            System.out.println("Event delivered: " + received + ", last= " + processor.getLastProcessedEvent());
        } finally {
            server.stop();
        }
    }
}
```
