# How to publish pooled events (for event source authors)

This guide, aimed at event source authors, shows how to publish events using the global Object Pool. The framework manages
reference counting and return-to-pool automatically across multiple threads. A Zero‑GC hot path is possible if the event
source uses the object pool as the source of event instances. In the example below, the pool is used to publish events at 
a rate of four million per second with Zero‑GC.

If you need the low-level details of the pool internals (capacity, partitions, MPMC queues, reference counting
implementation), see the Architecture and design page: [Object pooling](../architecture/object_pooling.md).

## Quick start

1) Define your pooled message type that extends BasePoolAware:

```java
public static class PooledMessage extends BasePoolAware {
    public String value;

    @Override
    public String toString() {
        return "PooledMessage{" + value + '}';
    }
}
```

2) Create a PooledEventSource that receives an injected ObjectPoolsRegistry. Use the ObjectPoolsRegistry to create an
   ObjectPool and publish the event. The framework will manage references and return-to-pool at the end of the cycle:


```java
public static class PooledEventSource extends AbstractEventSourceService<PooledMessage> {
    private ObjectPool<PooledMessage> pool;

    @ServiceRegistered
    public void setObjectPoolsRegistry(
        ObjectPoolsRegistry objectPoolsRegistry, 
        String name) {
        this.pool = objectPoolsRegistry.getOrCreate(
                PooledMessage.class,
                PooledMessage::new,
                pm -> pm.value = null
        );
    }

    /**
     * Publish a message value. The framework acquires and releases references as the
     * message passes through queues and consumers; the object is returned to the pool
     * automatically at end-of-cycle once all references are released.
     */
    public void publish(String value) {
        PooledMessage msg = pool.acquire();
        msg.value = value;
        output.publish(msg);
        // No manual release needed; the framework manages references after publish
    }
}
```

4) Create a main method that wires the EventSource into the server, boots the server and then calls publish:

```java
public static void main(String[] args) throws Exception {
    PooledEventSource source = new PooledEventSource();

    MongooseServerConfig cfg = new MongooseServerConfig()
            .addProcessor("thread-p1", new MyHandler(), "processor")
            .addEventSource(source, "pooledSource", true);

    MongooseServer server = MongooseServer.bootServer(cfg, rec -> {});

    // Publish a few messages; the framework handles pooling
    source.publish("hello-1");
    source.publish("hello-2");
    source.publish("hello-3");
}
```

5) Full end-to-end example with a real EventSource (this guide mirrors it):
- [PoolEventSourceServerExample.java](https://github.com/gregv12/fluxtion-server/blob/main/src/test/java/com/telamin/mongoose/example/objectpool/PoolEventSourceServerExample.java)

What it shows:

- A PooledMessage that extends BasePoolAware so it already has a PoolTracker.
- An EventSource (extends AbstractEventSourceService) that obtains an ObjectPool via ObjectPoolsRegistry (injected using @ServiceRegistered).
- Publishing without try-with-resources; the framework handles object pooling across threads automatically.
- A simple handler and a main() method you can run from your IDE.

## How the framework handles pooling across threads

- When you publish a pooled object, the framework acquires additional references for each queue/cache that will hold the
  object. Consumers drop their reference when done.
- The sender does not need to call releaseReference() after publishing. After publish, the framework takes ownership of reference
  management. The object is returned to the pool once ALL references (queues + consumers + optional cache) are released and the
  end-of-cycle returnToPool() occurs.
- When event caching is enabled, the publisher detaches the pooled instance from the pool (via removeFromPool) and caches the original object, while the pool immediately stages a fresh replacement instance. This avoids holding pool references for long-lived cached items and maintains pool capacity.
- Early calls to returnToPool() are safe; the object is returned only once the reference count reaches zero.

## Important constraints (read this!)

- Publisher MAY NOT continue to use the pooled object after it has been published within the scope of the publishing
  method. If you need to keep data, copy out the fields you require before publishing.
- Receivers MAY NOT retain the event object beyond the onEvent handler or the current event cycle. The pooled instance
  may be
  reclaimed and reused by the framework after the cycle completes. If you need to keep data, copy out the fields you
  require.
- If you manually pass the object to other components that outlive the event cycle, those components MUST call
  acquireReference() when they take ownership and releaseReference() when finished.

## Troubleshooting tips

- If an object is not returned to the pool, check for a missing releaseReference() in one of your consumers or caches.
- For high fan-out or bursts, consider increasing capacity/partitions when creating the pool.

### Runtime metrics output in the example

The example publishes at high rate and prints progress every 1,000,000 messages, including GC and heap memory stats. 
Example output with a 250 nanosecond pause between messages, equivalent to 4 million messages per second:

```
Processed 12000000 messages in 252 ms, heap used: 23 MB, GC count: 0
Processed 13000000 messages in 254 ms, heap used: 23 MB, GC count: 0
Processed 14000000 messages in 253 ms, heap used: 23 MB, GC count: 0
Processed 15000000 messages in 251 ms, heap used: 23 MB, GC count: 0
Processed 16000000 messages in 251 ms, heap used: 23 MB, GC count: 0
Processed 17000000 messages in 250 ms, heap used: 23 MB, GC count: 0
Processed 18000000 messages in 250 ms, heap used: 23 MB, GC count: 0
```

For design and performance details, see:
- Architecture and design: [Object pooling](../architecture/object_pooling.md)
