# How-to: Record a processor's inputs and replay them

Mongoose can record exactly what an event processor received, then replay it into a fresh server, so the processor
does what it did. The output is the same, and so are the instants its clock read. Use it to reproduce an incident,
to check a fix against a real run, or to turn a run into a test.

!!! warning "Not yet for a live deployment"
    In `REPLAY` mode a replayed processor's feeds are still subscribed, and its sinks and publications still go
    out. Replay into a server whose sources and sinks are in-memory or otherwise isolated, as the example below
    does. Disconnecting live inputs and muting outputs during a replay is open work (limits L1 and L2 in
    `design-doc/spec-replay-recording.md` §3e).

## How it relates to `ReplayRecord`

[Event replay and synthetic time](how-to-replay.md) shows how to *drive* a processor with events that carry their own
wall-clock time (`ReplayRecord`). This page is about *capturing* what a processor received in a live run, so that
the run itself can be replayed. The two are independent. `ReplayRecord` is an input format, and recording is a
server mode.

## What is recorded

Recording happens at the one point every input passes: the dispatch from the agent's queue into the processor. By
then filters, redispatch and fan-out have happened, so the record is what the processor saw, in its order.

| input | recorded as |
|---|---|
| an item from a **journalled** feed | an index, `(feed, sequence number)`. The item itself is written once, to the `EventJournal` |
| an item from any other feed, or a typed service call | the event itself (inline) |
| a scheduler timer firing | a timer entry, so it fires at the same point on replay |
| a processor-owned admin command | its name and arguments |
| a dispatch that threw | a failure marker; a replay stops there |

Each entry also carries every clock reading the processor made in that cycle, including readings for events the
graph raised itself. On replay the processor reads the same instants.

## Configure it

Replay is off by default. `MongooseServerConfig.builder().replay(...)` takes a `ReplayConfig`:

```java
// record the "pricer" processor; the "prices" feed is journalled with Java serialisation
ReplayConfig record = ReplayConfig.record(Set.of("pricer"), Map.of("prices", new JavaSerializationCodec()),
        journal, store);

// later, in a fresh server: replay it from the same journal and store
ReplayConfig replay = ReplayConfig.replay(Set.of("pricer"), Map.of("prices", new JavaSerializationCodec()),
        journal, store);
```

- **`processors`**: the processors to record or replay; an empty set means every processor.
- **`journalledFeeds`**: the feeds whose items are journalled, each with its `EventCodec`. Their inputs are recorded
  as indexes.
- **`journal`** (`EventJournal`): where journalled items are written, and read back on replay.
- **`store`** (`ReplayStore`): where each processor's entries are appended, and read back on replay.

Implementations:

- `InMemoryEventJournal` / `InMemoryReplayStore`: for tests and for a replay within one process.
- `CsvEventJournal` / `CsvReplayStore`: durable samples. A run is recorded into two files, which another process opens
  and replays from alone.
- `JavaSerializationCodec`: a codec for `Serializable` items; a feed with other items supplies its own `EventCodec`.

## Example

A processor that writes each price with the instant it read:

```java
public class RecordedPriceHandler extends ObjectEventHandlerNode {
    private final String feedName;
    private MessageSink<String> sink;

    public RecordedPriceHandler(String feedName) { this.feedName = feedName; }

    @ServiceRegistered
    public void sink(MessageSink<String> sink, String name) { this.sink = sink; }

    @Override
    public void start() { getContext().subscribeToNamedFeed(feedName); }

    @Override
    protected boolean handleEvent(Object event) {
        long time = getContext().getClock().getProcessTime();
        if (sink != null && event instanceof String price) {
            sink.accept("price=" + price + " time=" + time);
        }
        return true;
    }
}
```

Record a live run, then replay it into a fresh server. Nothing is offered to the feed on replay; the inputs come
from the store and the journal:

```java
InMemoryEventJournal journal = new InMemoryEventJournal();
InMemoryReplayStore store = new InMemoryReplayStore();
Map<String, EventCodec> journalled = Map.of("prices", new JavaSerializationCodec());

// record
MongooseServer live = boot(ReplayConfig.record(Set.of("pricer"), journalled, journal, store), livePrices, liveSink);
livePrices.offer("DEMO-101.5");
livePrices.offer("DEMO-101.7");
livePrices.offer("DEMO-101.6");
// ... wait for the three lines, then
live.stop();

// replay, in a fresh server with a fresh processor
MongooseServer replay = boot(ReplayConfig.replay(Set.of("pricer"), journalled, journal, store), priceFeed(), replaySink);
// ... wait until replay.replayers().get("processor-agent").complete()
// replaySink now holds exactly liveSink's lines, times included
```

`boot` builds an ordinary `MongooseServerConfig`, with the processor in a group, the feed, and a sink, and adds
`.replay(config)`.

Sample code:

- Test: [RecordAndReplayExampleTest.java]({{source_root}}/test/java/com/telamin/mongoose/example/replay/RecordAndReplayExampleTest.java)
- Processor: [RecordedPriceHandler.java]({{source_root}}/test/java/com/telamin/mongoose/example/replay/RecordedPriceHandler.java)
- Durable CSV journal and store, replayed from the files alone: [CsvDurableReplayTest.java]({{source_root}}/test/java/com/telamin/mongoose/replay/CsvDurableReplayTest.java)
- Every input kind in one processor: two feeds, a raised event, a timer, an admin command, a failure. See [ReplayRecordingAcceptanceTest.java]({{source_root}}/test/java/com/telamin/mongoose/replay/ReplayRecordingAcceptanceTest.java).

## Scope and limits

- **One processor at a time.** A replay is per processor, against its own recorded inputs. Several processors in one
  agent group are recorded correctly, each with its own stream. Replaying several together needs outputs muted
  (above), or one processor's replayed output would reach another whose stream already holds it.
- **Hidden inputs are not supplied.** Randomness, iteration order, reads of files, databases or networks, or values
  read back from injected services are detected by divergence on replay, not recorded.
- **Calls outside Mongoose's paths**, from code holding a processor directly, are not recorded.
- **After a failure** the recording is marked and a replay stops there. Determinism is not claimed after a failure.
- **Durability.** The CSV journal and store are samples; a production journal (for example Chronicle), with
  retention, is open work.

With replay off, the dispatch path is unchanged apart from a sequence number on journalled feeds, and it allocates
nothing (measured with `DispatchPathJmh`).
