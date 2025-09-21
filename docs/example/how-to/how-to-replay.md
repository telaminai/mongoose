# How-to: Deterministic replay with ReplayRecord and the data-driven clock

This guide shows how to replay events with an explicit wall-clock time so that your handlers see a deterministic time 
via `getContext().getClock()` during processing.

Key points:

- Publish a `ReplayRecord` which contains both the original event payload and a `wallClockTime` value to apply when processing.
- The event runner uses this time to drive the processor context clock for the corresponding event delivery.
- Handlers can read the time with `getContext().getClock()` and will observe the same values when the replay is repeated.
- The handler receives the event wrapped in the `ReplayRecord` and not the `ReplayRecord` itself.

## Sample code

- Processor
  source: [HandlerPipeServerBootExample.java]({{source_root}}/test/java/com/telamin/mongoose/example/replay/ReplayCaptureHandler.java)
- Test
  node: [HandlerPipeTest.java]({{source_root}}/test/java/com/telamin/mongoose/example/replay/ReplayServerBootExample.java)
- Complete how-to example: [Replay Example](https://github.com/telaminai/mongoose-examples/blob/229e01e2f508bdf084a611677dc93c1174c96bdc/how-to/replay)

What it demonstrates:

- Booting the server with an `InMemoryEventSource` as an EventFeed.
- A handler that reads `getContext().getClock()` to capture the data-driven time.
- Publishing `ReplayRecord` objects through the source using the standard `offer(...)`.
- Verifying that the observed times match the replayed timestamps.

## Minimal example

- A handler that subscribes to a feed and writes the event and `getContext().getClock()` time to a sink:

```java
public class ReplayCaptureHandler extends ObjectEventHandlerNode {
    private final String feedName;
    private MessageSink<String> sink;

    public ReplayCaptureHandler(String feedName) { this.feedName = feedName; }

    @ServiceRegistered
    public void wire(MessageSink<ReplayRecord> sink, String name) { this.sink = sink; }

    @Override
    public void start() { getContext().subscribeToNamedFeed(feedName); }

    @Override
    protected boolean handleEvent(Object event) {
        long time = getContext().getClock().getWallClockTime();
        sink.accept("event=" + event + ", time=" + time);
        return true;
    }
}
```

- Boot a server with an in-VM event source and publish `ReplayRecord` entries:

```java
InMemoryEventSource<String> source = new InMemoryEventSource<>();
source.setName("replayFeed");
ReplayCaptureHandler handler = new ReplayCaptureHandler(source.getName());
InMemoryMessageSink sink = new InMemoryMessageSink();

// Build MongooseServerConfig with the event feed, processor, and sink (see full example below)

long t1 = 1_696_000_000_000L; // epoch millis
long t2 = t1 + 1234;
ReplayRecord r1 = new ReplayRecord();
r1.setEvent("alpha");
r1.setWallClockTime(t1);
ReplayRecord r2 = new ReplayRecord();
r2.setEvent("beta");
r2.setWallClockTime(t2);

// Publish with explicit replay times
source.offer(r1);
source.offer(r2);
```

The handler will emit lines like:

```
event=alpha, time=1696000000000
event=beta, time=1696000001234
```

## Why the clock is data driven

When a `ReplayRecord` is processed, the event runner calls the event-to-invocation strategy with: `(event, wallClockTime)`
. This sets the processor context clock to the supplied value for that event delivery. As a result, `getContext().getClock()`
is deterministic with respect to the replay data. Re-running the same `ReplayRecord` stream will produce the same clock
readings, ensuring reproducible behavior. The dispatcher unwraps the event in the `ReplayRecord` and publish this wrapped
event to the handler not the `ReplayRecord`.

If you run the replay again with the same `ReplayRecord` inputs, your handler will see the same times. 
This makes tests and off-line analyses reproducible.
