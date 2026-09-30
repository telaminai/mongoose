# How-to: Record a processor's inputs and replay them

Mongoose can record exactly what an event processor received, then replay it into a fresh server, giving the
processor the same inputs at the same instants its clock read. When the processor depends on nothing else, it does
what it did; whether it did is shown by comparing the replay's captured outputs, or its audit log, with the recorded
run's. Use it to reproduce an incident,
to check a fix against a real run, or to turn a run into a test.

!!! note "A replay is isolated from the live world"
    In `REPLAY` mode a replayed processor receives only its replay: its live inputs are muted (limit L1). What it
    sends to its registered `MessageSink` services is captured for comparison,
    `replayers().get(group).outputs(processor)`, and never delivered (L2). Anything it sends through any other service
    it was given (an injected gateway, a publisher, another processor's exported service) is delivered as live: isolate
    those yourself before replaying. Other processors in the group are unaffected: they keep their live inputs, outputs
    and timers.

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
// replay.replayers().get("processor-agent").outputs("pricer") holds exactly liveSink's lines, times included;
// replaySink receives nothing: a replay's outputs are captured, not delivered
```

`boot` builds an ordinary `MongooseServerConfig`, with the processor in a group, the feed, and a sink, and adds
`.replay(config)`.

Sample code:

- Test: [RecordAndReplayExampleTest.java]({{source_root}}/test/java/com/telamin/mongoose/example/replay/RecordAndReplayExampleTest.java)
- Processor: [RecordedPriceHandler.java]({{source_root}}/test/java/com/telamin/mongoose/example/replay/RecordedPriceHandler.java)
- Durable CSV journal and store, replayed from the files alone: [CsvDurableReplayTest.java]({{source_root}}/test/java/com/telamin/mongoose/replay/CsvDurableReplayTest.java)
- Every input kind in one processor: two feeds, a raised event, a timer, an admin command, a failure. See [ReplayRecordingAcceptanceTest.java]({{source_root}}/test/java/com/telamin/mongoose/replay/ReplayRecordingAcceptanceTest.java).

## Scope and limits

- **Per processor.** A replay is per processor, against its own recorded inputs. Several processors in one agent group
  are recorded correctly, each with its own stream, and can be replayed together: each one's outputs are captured, so
  none reaches another whose stream already holds it.
- **Hidden inputs are not supplied, and not detected.** Randomness, iteration order, reads of files, databases or
  networks, or values read back from injected services are not recorded. A replay does not notice them either, unless
  they change how often the processor reads its clock: the replay completes with no stop reason and a different
  result. Compare `outputs(processor)`, or the audit log, with the recorded run's; `complete()` with a null `stopped()`
  means every entry was delivered, not that the run was reproduced.
- **An input is recorded as each processor received it, or refused.** It is copied just before the processor handles
  it, so a handler that changes its input, or an application that reuses the object, does not change the recording.
  What can be copied:
  - values that cannot change, kept as they are;
  - `Serializable` inputs whose classes declare no `transient` field, by Java serialisation. Their serial form must
    carry everything a handler reads; state it omits (nested objects, custom `writeObject` or `Externalizable` forms) is
    not checked, and cannot be;
  - `NamedFeedEventImpl` itself, with its feed name, topic, number, delete flag, event time and both filters, inline or
    journalled. A subclass or another `NamedFeedEvent` implementation is refused;
  - a journalled input through the feed's codec only. It is recorded by index while the processor receives what the
    journal holds, and otherwise as that codec's own bytes, which the replay decodes with the same codec. It is never
    re-copied by Java serialisation. The recording keeps its own copy of those bytes and decodes from a copy, so a codec
    may reuse its buffers; it must still be faithful for its items, and thread-safe if more than one agent uses it.

  Anything else is recorded as a failure naming why, and a replay stops there. Live delivery is never affected.
- **Custom strategies.** An `EventToInvokeStrategy` that extends `AbstractEventToInvocationStrategy`, or overrides
  `processEventRecording`, records fan-out per processor. One that inherits the interface's default records a single
  processor, and refuses a fan-out it cannot see into. One whose `registeredProcessors()` is empty cannot be recorded:
  the processors it delivers to have their recordings failed, by name.
- **A processor that refuses the recording clock** has its recording failed at setup, by name, and runs on live,
  unrecorded, with its live time as with replay off. RECORD never stops a processor.
- **A strategy that names only some of its processors** is trusted: the ones it does not name are not recorded, and
  this is not detected.
- **Each entry names its route.** A source that reaches a processor by more than one callback type replays each entry
  through the one that delivered it.
- **Calls outside Mongoose's paths**, from code holding a processor directly, are not recorded.
- **After a failure** the recording is marked and a replay stops there. Determinism is not claimed after a failure.
- **A replay stops, saying why,** rather than stalling, in the cases it can see: an entry that cannot be delivered
  within `ReplayConfig.deliveryTimeout` (5 s), a journal missing an item, a store or decoder that fails, or a cycle that
  reads the clock a different number of times from the recorded one. `replayers().get(group).stopped(processor)` gives
  the reason. A replay failure stops that processor's replay; it does not end the server.
- **A recording is refused over a recording.** `RECORD` needs an empty journal and store: a second run numbers its
  items from 1 again. A store in the earlier six-field CSV format is read, never appended to. A torn last CSV line (a
  crash mid-write) is set aside with a warning; the file is still read,
  but `RECORD` refuses it and nothing is appended to it.
- **Clock reads outside an input's cycle** (in `start()` or `@Initialise`) are not recorded.
- **Durability.** The CSV journal and store are samples; a production journal (for example Chronicle), with
  retention, is open work.

With replay off, the default feed's dispatch allocates nothing, as before, and no stable timing difference from
`main` is resolved. A named-event feed measures about 0.4 ns slower per item (`DispatchPathJmh -prof gc`, alternating
runs). Recording itself allocates: each inline input is copied.
