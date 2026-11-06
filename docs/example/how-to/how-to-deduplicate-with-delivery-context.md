# How-to: De-duplicate re-delivered events with DeliveryContext

A [`COMMITED` file feed](how-to-file-feed-at-least-once.md) is at-least-once: after a crash it re-delivers the few
lines it had not yet committed, and some of them your processor has already handled. For a stateless or idempotent
processor that is harmless. For one that keeps a counter, a balance or a join, a re-delivered line applied a second
time gives a wrong answer.

From Mongoose 1.1.3 a processor can tell a re-delivery from a new line. For each item of a feed that tracks positions,
`DeliveryContext` gives the item's source position. Save that position **in the same atomic write as your state**, and
after a restart skip any item at or before it. Each line's effect on your state is then applied exactly once, however
far the source's own pointer lags. This is the pattern Kafka applications use when they store offsets with their state.

The design is in [Feed delivery guarantees (design), section 5a](../../design/feed-delivery.md).

## Sample code

- Complete how-to example: [File feed at least once]({{example_root}}/how-to/file-feed-at-least-once). Its
  `LedgerProcessor` keeps a running total, and the example kills and restarts the server with and without the guard.
- Tests in the Mongoose repository: `FeedDeliveryKillTest` (a real `kill -9`: without the guard the state counts
  5,005 lines for 5,000; with it, exactly 5,000) and `FeedDeliveryReplayContextTest` (the delivery is recorded and
  replayed).

## The API

`com.telamin.mongoose.service.delivery.DeliveryContext` is set by the processor group's reader for the whole event
cycle of each positioned item, in every handler and `@AfterEvent`, and cleared when the cycle ends:

```java
Delivery d = DeliveryContext.current();   // null for an event without a source position
d.feed();               // the feed name
d.sequence();           // this run's publish sequence: restarts at 1, never use it across a restart
d.epoch();              // the source's position space; changes when the source is reset or replaced
d.position();           // the source's position after the item: a FileEventSource gives the Long byte offset
d.positionAsLong();
d.alreadyApplied(storedEpoch, storedPosition);   // same epoch, and position <= stored
DeliveryContext.currentPosition();               // the position only, no allocation
```

`DeliveryContext.current()` is null for an event that has no source position: an item of a feed that does not track
positions, a timer, a service callback. Today the only core source with positions is `FileEventSource` with
`COMMITED`. `EARLIEST`, `LATEST` and the `ONCE_*` strategies have no pointer, so their items carry no position.

`alreadyApplied` is false when either position is null, and false for a different epoch: after the source was reset or
replaced, its offsets say nothing about your stored state, so new lines are never skipped by mistake.

You can also inject it. It is registered as the service `mongoose.deliveryContext`:

```java
private DeliveryContext deliveryContext;

@ServiceRegistered
public void delivery(DeliveryContext deliveryContext, String name) {
    this.deliveryContext = deliveryContext;
}

// in a handler
Delivery d = deliveryContext.delivery();   // the same as DeliveryContext.current()
```

## The pattern

From the example's `LedgerProcessor`, which adds each line's amount to a running total:

```java
@Override
protected boolean handleEvent(Object event) {
    if (!(event instanceof String line)) {
        return true;
    }
    Delivery delivery = DeliveryContext.current();   // non-null for every line of a COMMITED file feed
    if (deduplicate && delivery != null && delivery.alreadyApplied(epoch, position)) {
        return false;   // re-delivered after a crash: its effect is already in the saved state
    }
    try {
        appliedLog.write((line + "\n").getBytes(StandardCharsets.UTF_8));   // the output
        applied++;
        total += Long.parseLong(line.substring(line.indexOf(',') + 1));
        if (delivery != null) {
            epoch = delivery.epoch();
            position = delivery.positionAsLong();
        }
        new LedgerState(applied, total, epoch, position).write(stateFile);   // state + position, atomically
    } catch (IOException e) {
        throw new UncheckedIOException(e);
    }
    return true;
}
```

The state record is written to a temporary file and moved into place, so a kill never leaves a torn file:

```java
void write(Path file) throws IOException {
    Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
    Files.writeString(tmp, applied + " " + total + " " + epoch + " " + position);
    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
}
```

The rules:

1. **Save the position with the state, in one atomic write.** Keep `(epoch, position)` per feed you consume, in the
   same record as the state it produced. Write it atomically: a temporary file and an `ATOMIC_MOVE`, a database
   transaction, or an append to a log with a checksum per record.
2. **Read the position in the cycle that changes the state.** In the handler, or in the `@AfterEvent` that saves. If
   `DeliveryContext.current()` is null, save the state and leave the stored position unchanged. Key positions by
   `Delivery.feed()` if the processor consumes more than one positioned feed.
3. **Check before every change and every output.** Call `alreadyApplied` before the first mutation and the first
   output for the event, and skip the event if it is true.
4. **Restore with the state, before the feed delivers.** The example reads its state in the constructor.
5. **Decide what a new epoch means.** A new epoch means the input file was replaced or truncated, or the pointer was
   deleted. `alreadyApplied` returns false, so every line is applied. If a new input file should start from fresh
   state, compare `epoch()` with your stored epoch at the first event, then log, or clear your state.

## Why the position must be saved with the state

The source's read pointer and your state file are two separate durable records. No two files can be written
atomically together, so after a crash one is always a little ahead of the other:

- the pointer is never ahead of what was processed (it is committed after processing), so nothing is skipped;
- your state is usually a few lines ahead of the pointer (the commit lag), so those lines are re-delivered.

Storing the position in the same write as the state makes your state the authority on what has been applied. The
source pointer only bounds how far back a restart re-reads. Nothing else is needed: the commit order does not have to
change.

Do not use `sequence()` for this. It restarts at 1 with every run.

## Outputs

The order inside an event cycle is: outputs, then the state save. A kill between the two re-applies nothing (the state
does not contain the line, so the line is applied once, after the restart) but repeats that line's outputs. So the
guard gives at most one duplicated output per crash. For exactly-once outputs too, either:

- write each output with its `(feed, epoch, position)` key and let the sink drop a key it has already written; or
- write the outputs into the same atomic record as the state (an outbox) and publish them from there.

A node without persistent state needs no guard, unless its outputs must not repeat: then give it the same check. It
can read the persistent node's stored position.

## Asynchronous or batched state

If you save state asynchronously or in batches, hold the source's commits with a `ProcessingCheckpoint` as well (see
[Process a file feed at least once](how-to-file-feed-at-least-once.md#state-saved-asynchronously-or-in-batches-processingcheckpoint)),
and store the `(epoch, position)` of the last item each saved batch includes.

## Record and replay

What a processor reads from `DeliveryContext` is input to it, so a recording keeps it. In `RECORD` mode each
positioned input's delivery (feed, sequence, epoch, position) is stored with its entry, and a replay sets
`DeliveryContext` for the replayed cycle. A processor that skips lines by stored position makes the same decisions in
the replay as in the live run. In a `CsvReplayStore` the delivery is a `DELIVERY` line written just before the input's
line; a reader from before 1.1.3 refuses such a file rather than replay it without the delivery.

A recording without delivery metadata replays with none:

- for a recording made by 1.1.2 or earlier, that is equivalent: `DeliveryContext` did not exist, so the live cycles
  read none either;
- for a recording made by a pre-release build of 1.1.3 before this was added, it is not equivalent for a processor
  that read `DeliveryContext`. Record such a stream again.

A re-delivered item is an ordinary input of the run that reads it. Each run's recording holds its own inputs, so a
line processed before a crash and re-delivered after it appears once in each run's recording.

See [Record and replay a processor](how-to-record-and-replay-a-processor.md).
