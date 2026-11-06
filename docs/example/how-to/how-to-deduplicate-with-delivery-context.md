# How-to: De-duplicate re-delivered events with DeliveryContext

A [`COMMITED` file feed](how-to-file-feed-at-least-once.md) is at-least-once: after a crash it re-delivers the few
lines it had not yet committed, and some of them your processor has already handled. For a stateless or idempotent
processor that is harmless. For one that keeps a counter, a balance or a join, a re-delivered line applied a second
time gives a wrong answer.

From Mongoose 1.1.3 a processor can tell a re-delivery from a new line. For each item of a feed that tracks positions,
`DeliveryContext` gives the item's source position. Save that position **in the same atomic write as your state**, and
after a restart skip any item at or before it. Each line's effect on your state is then applied exactly once, however
far the source's own pointer lags. This is the pattern Kafka applications use when they store offsets with their state.

The design is in [Feed delivery guarantees (design), sections 5a and 5b](../../design/feed-delivery.md).

## Sample code

- Complete how-to example: [File feed at least once]({{example_root}}/how-to/file-feed-at-least-once). Its
  `LedgerProcessor` keeps a running total, and the example kills and restarts the server with and without the guard.
- Tests in the Mongoose repository: `FeedDeliveryKillTest` (a real `kill -9`: without the guard the state counts
  5,005 lines for 5,000; with it, exactly 5,000), `FeedDeliveryReplayContextTest` (the delivery is recorded and
  replayed), `DeliveryCompletionTest` and `DeliveryCompletionServerTest` (the delivery-complete hook).

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

This processor saves in its handler, which is right for it: it never sends itself another event. A processor that
does (a handler or a sink calling `onEvent`) should save at delivery completion instead, below.

## Save at delivery completion (recommended)

A handler, or a sink it publishes to, may send the processor another event with `onEvent` while it handles a line. A
generated processor queues that event and runs it after the current cycle's `@AfterEvent`, as a second cycle under the
same delivery. A save in the first cycle's handler or `@AfterEvent` then stores the line's position before the queued
event has run:

- the queued event runs under a position already saved, so a guard skips it as a replay, and it is lost;
- and if it is applied anyway, a crash after the first save skips the re-delivered line, so nothing queues the event
  again: its effect is lost after the restart.

`DeliveryContext.onComplete(Runnable)` runs a callback when the delivery is complete. Register the save there:

```java
private final Runnable save = this::save;   // one instance: registering it again in the same delivery is a no-op

@AfterEvent
public void afterEvent() {
    if (!DeliveryContext.onComplete(save)) {
        save();   // no positioned delivery (a timer, another feed): save now, as before
    }
}

private void save() {
    Delivery d = DeliveryContext.current();   // still set in the callback
    if (d != null) {
        epoch = d.epoch();
        position = d.positionAsLong();
    }
    new LedgerState(applied, total, epoch, position).write(stateFile);   // state + position, atomically
}
```

The guarantees:

- **After all the work of the delivery.** The callbacks run once the processors of the queue have handled the line,
  including every event the line queued reentrantly, on the processor group's thread, with `DeliveryContext` still set.
- **Before the feed can commit the line.** The line counts as processed only after its callbacks return, so the source
  cannot acknowledge or commit it before the save. A crash before the callback leaves nothing of the line saved and the
  line uncommitted: the restart re-delivers it and the whole delivery, queued events included, is applied again. A
  crash after it skips the re-delivered line, and the saved state already holds all of it.
- **Once per callback, in order.** Callbacks run in registration order; registering the same instance again while it is
  pending does nothing, so a node can register in every cycle of the delivery.
- **Only for positioned deliveries.** `onComplete` returns false, and keeps nothing, when the calling thread is not
  handling an item with a source position (a timer, another feed, a service callback): do the work yourself then.
- **Failures.** If a processor's dispatch throws, the callbacks it registered are discarded and a retry registers them
  again; `DeliveryContext.attempt()` tells a retry (the count grew) from a queued event (the same count). A callback
  that throws is retried alone, under the processor group's retry policy, with the line still uncommitted; if the policy
  gives up, the error is reported and the line is committed, like any line a failure policy has decided on, and
  `feed.status` counts it in the feed's `completionFailures` and reports the feed `lossless:false`: a save that kept
  failing let the source move past state that is not saved. A callback that succeeds on a retry is not a loss. In a
  recording, a callback that failed marks its processor's input
  `Failed`, and a replay stops there.
- **Replay.** A replay runs each replayed input's callbacks the same way, inside the input's replay.

The mongoose-plugins `PersistentNode` (svc-cache) does this for you from Mongoose 1.1.3.

The rules:

1. **Save the position with the state, in one atomic write.** Keep `(epoch, position)` per feed you consume, in the
   same record as the state it produced. Write it atomically: a temporary file and an `ATOMIC_MOVE`, a database
   transaction, or an append to a log with a checksum per record.
2. **Save when the delivery is complete.** In a `DeliveryContext.onComplete` callback (recommended, and required if
   the processor sends itself events while handling a line), or, for a processor that never does, in the handler or
   the `@AfterEvent` that saves. If `DeliveryContext.current()` is null, save the state and leave the stored position
   unchanged. Key positions by
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

The order of a delivery is: outputs, then the state save. A kill between the two re-applies nothing (the state
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
