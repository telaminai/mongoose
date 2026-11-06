# How-to: Process a file feed at least once, and resume after a crash

A `FileEventSource` with the `COMMITED` read strategy remembers how far it has got in a side-car file,
`<filename>.readPointer`, and resumes from there after a restart. From Mongoose 1.1.3 that pointer only moves past a
line once **every subscriber has processed it**, and a full queue pauses the file feed instead of dropping lines. A
`COMMITED` file feed is therefore **at-least-once**: after a crash, a restart re-delivers what was not yet committed;
it never skips a line.

You'll learn:

- how to configure an at-least-once file feed;
- exactly what is re-delivered after a `kill -9`, and after a clean stop;
- how the pointer survives appends, rotation, replacement and truncation (epochs);
- when a feed holds its pointer and reports `stalled`;
- how to hold commits until state saved asynchronously is durable (`ProcessingCheckpoint`);
- what changes for pointer files written by 1.1.2 and earlier.

At-least-once means a re-delivered line can be processed twice. That is harmless for stateless or idempotent
processing. If your processor keeps state that a duplicate would corrupt (a counter, a balance, a join), also follow
[De-duplicate re-delivered events with DeliveryContext](how-to-deduplicate-with-delivery-context.md).

The design is in [Feed delivery guarantees (design)](../../design/feed-delivery.md).

## Sample code

- Complete how-to example: [File feed at least once]({{example_root}}/how-to/file-feed-at-least-once). It starts a
  server in a child JVM on a 5,000-line file, kills it with SIGKILL part way through, restarts it, and checks that no
  line was lost: once without and once with de-duplication. It needs Mongoose 1.1.3.
- Tests in the Mongoose repository: `FeedDeliveryKillTest` (a real `kill -9`), `FeedDeliveryServerTest`,
  `FileEventSourceDeliveryTest`, `FileEventSourcePointerTest` and `FileEventSourceIndependentReviewTest`.

## Configure it

`COMMITED` is the `FileEventSource` default. Set the overload policy explicitly so the boot log does not warn:

```yaml
eventFeeds:
  - name: orders
    instance: !!com.telamin.mongoose.connector.file.FileEventSource
      filename: data/orders.csv
      readStrategy: COMMITED
    agentName: orders-feed-agent
    overload:
      policy: PAUSE           # the default for a file feed; never drops a line
```

Or in Java, from the example's `AtLeastOnceServer`:

```java
FileEventSource source = new FileEventSource();
source.setFilename(dir.resolve("ledger.csv").toString());
source.setReadStrategy(ReadStrategy.COMMITED);   // resume from ledger.csv.readPointer

MongooseServerConfig config = MongooseServerConfig.builder()
        .addEventFeed(EventFeedConfig.builder()
                .instance(source)
                .name(FEED)
                .agent("ledger-feed-agent", new SleepingMillisIdleStrategy(1))
                .build())
        .addProcessorGroup(EventProcessorGroupConfig.builder()
                .agentName("ledger-agent")
                .put("ledger", new EventProcessorConfig(ledger))
                .build())
        .build();
```

The example sets no policy, so it gets the file-feed default, `PAUSE`, and the boot log says so:
`feed 'ledger' has no explicit overload policy: using the source's default, PAUSE for a re-readable source
(FileEventSource), else DROP_AFTER_SPIN ...`. To make it explicit, add
`.overload(OverloadPolicyConfig.pause())` to the feed.

Only `COMMITED` commits. `EARLIEST` and `ONCE_EARLIEST` have no pointer and re-read the file from the start on every
run; `LATEST` and `ONCE_LATEST` start at the end of the file. See the [read strategy guide](../../guide/read-strategy.md).

## What is committed, and when

1. The source reads a line and publishes it with its position: the byte offset just after the line.
2. The processors handle it. A processor group's reader counts the line as dispatched when its event cycle ends, or
   when it was skipped after a failure (the failure policy has already decided its fate).
3. At the start of each duty cycle the source asks which lines every subscriber queue has dispatched, and writes the
   highest such offset to the pointer file. The pointer only moves forward.

With several processor groups on the feed, the pointer follows the slowest. A line that a subscriber's
`DROP_AFTER_SPIN` queue dropped is committed past, which is what a drop means; see
[Configuring overload and loss](../../guide/overload-configuration.md).

**Commit lag.** The pointer trails processing by about one duty cycle of the source's agent: with
`SleepingMillisIdleStrategy(1)`, about 1 ms. A real `kill -9` test measured it at 1 to 5 lines behind the processor's
saved state. `settled` in `feed.status` does not wait for the commit: at the moment a feed settles, `pendingAcks` may
still be a few lines.

## What a restart re-delivers

| How the server ended | What the next start reads |
| --- | --- |
| `kill -9`, power loss, or any crash | Every line after the pointer: lines read but not processed, plus the few lines processed in the commit lag. **Duplicates, never a gap.** |
| Clean stop (`server.stop()`) | Lines no processor group finished. The stop commits again after the processor groups have drained their queues, so lines they finished after the feed itself stopped are committed too. |
| A processor group that never processed its queue (its drain timed out, its queue was dropped at close) | Those lines, again. |

The example shows the crash case. Its output, from one run:

```text
=== without DeliveryContext de-duplication ===
killed: 2002 lines applied, saved position 22755, read pointer 22733 (never ahead of the state: true)
lines missing: 0, output lines written twice: 2
state: applied 5002 (expected 5000), total 252505 (expected 252500)  <- lines applied twice

=== with DeliveryContext de-duplication ===
killed: 2003 lines applied, saved position 22766, read pointer 22755 (never ahead of the state: true)
lines missing: 0, output lines written twice: 0
state: applied 5000 (expected 5000), total 252500 (expected 252500)
```

The pointer was 22 bytes (two lines) behind the processor's saved state when it was killed. The restart re-delivered
those two lines. Without de-duplication the stateful processor applied them twice. A kill that lands between a
line's output and its state save also repeats that one output line, in either scenario. Its effect on the state is
applied only once, because the state saved before the kill does not contain it.

The feed's sequence numbers restart at 1 with every run. Never use them to recognise a line across a restart; use the
source position (see [De-duplicate re-delivered events](how-to-deduplicate-with-delivery-context.md)).

A line without its final newline is not published until the newline arrives: a tailing feed reports `CAUGHT_UP` with
the detail `partial line pending`.

## Epochs: appends, rotation, replacement and truncation

The pointer file holds the byte offset, an **epoch**, the input file's identity (its file key: device and inode on
Unix) and a fingerprint of its first bytes (up to 4 KB). The epoch names the position space: an offset only means
something within its epoch. A new pointer file gets a new epoch.

When the source opens the input file (at start, or after a reopen), it compares the file with the pointer:

| The input file | Pointer | Epoch |
| --- | --- | --- |
| Appended to, or its times changed | kept | kept |
| Shorter than the pointer (**truncated**) | reset to 0, with a warning | new |
| Other leading bytes (**replaced**, or rewritten in place) | reset to 0, with a warning | new |
| Another file key (a new inode) and the same leading bytes, not shorter than the pointer: a remount, a copy-up, or a writer that appends by writing a temporary file and renaming it | kept, with a warning | kept |
| Pointer file deleted | starts at 0 | new |

**While tailing**, at the end of the open file and before it reports `CAUGHT_UP`, the source also checks that the
path still names the file it is reading and that the file is not shorter than what it has read. If not, it reports
`BEHIND` with the reason (`the input file was replaced while being read ...` or `... truncated ...`), closes the
file, and opens the one at the path, running the checks above. So a rotated or truncated file is picked up without a
restart:

- on a rotation, the lines the writer appended to the old file before the new one appeared are read first;
- lines of the old file already published are still delivered, but their commits are never written into the new
  file's pointer;
- an unterminated last line of the old file is not published;
- a path with no file for a moment (a rotation in progress) is not a change: the open file is read on.

Not detected:

- a file rewritten in place, keeping its first 4 KB and at least the length already read;
- while running, a file rewritten in place to at least the length already read. The fingerprint catches it at the
  next open, unless the first 4 KB are unchanged.

On a platform without file keys (Windows) only the fingerprint and the size decide.

Each item of a `COMMITED` feed carries its epoch and position to the processor, through `DeliveryContext`. A
processor that stores them with its state can tell a re-delivery from a new line, and notice a new epoch: see
[De-duplicate re-delivered events](how-to-deduplicate-with-delivery-context.md).

## When the feed holds its pointer: `stalled`

The pointer never moves past a line that a consumer was given and did not process. Mongoose cannot tell a deliberate
unsubscribe from a failure, so the rule is the same for every way a consumer can go:

| What happened | What the feed does | `feed.status` |
| --- | --- | --- |
| A consumer with unprocessed lines was removed (its processor quarantined or removed, its group restarted, or a voluntary unsubscribe), and others remain | The others go on being fed. The pointer stays before the removed consumer's first unprocessed line for the rest of the run, unless that consumer's reader still finishes the lines it was given (it was part way through them when removed). | `stalled: a removed consumer left unprocessed items ...` |
| The last consumer was removed | The source stops reading and committing. A later subscriber resumes reading, but the pointer stays before the removed consumer's lines. | `consumers: 0`, `stalled: no live consumer` |
| A consumer's group is fenced (a quarantine in progress) or dead | The whole feed pauses: no consumer receives lines until the fence lifts. A dead group never lifts it: the feed stays paused until the group is removed or the server restarts. | `stalled: consumer <queue> fenced or dead` |
| A consumer that had processed everything it was given is removed | Nothing: it holds nothing. | |

A stalled feed is **not settled**, so a launcher waiting for `settled` times out with the reason instead of declaring
an incomplete run complete. It stays **lossless**: nothing is lost, and a restart re-delivers the held lines to every
consumer (duplicates for the consumers that had processed them).

There is no operator command to release a hold yet; a restart is the only way. A `delivery.release` command is
planned. Until then, avoid unsubscribing consumers with a backlog from a `COMMITED` feed you expect to complete.

Holds apply only to feeds that commit positions. A feed without positions (an in-memory feed, a pipe, `EARLIEST`)
keeps the earlier containment rule: a fenced or dead group does not hold it back, and its losses are counted.

## State saved asynchronously or in batches: `ProcessingCheckpoint`

By default "processed" means the line's event cycle ended. That is the right commit point when your processor saves
its state synchronously in the cycle. If it saves asynchronously or in batches, a crash after the commit and before the
save would lose the effect of lines the source will not re-deliver. Hold commits until your state is durable:

```java
public class BatchingProcessor extends ObjectEventHandlerNode {

    private ProcessingCheckpoint checkpoint;
    private final List<String> batch = new ArrayList<>();

    @ServiceRegistered
    public void checkpoints(ProcessingCheckpointService service, String name) {
        // hold commits of the "orders" feed for this processor's group until the state is durable
        checkpoint = service.hold("orders", "orders-agent");
    }

    @Override
    public void start() {
        getContext().subscribeToNamedFeed("orders");
    }

    @Override
    protected boolean handleEvent(Object event) {
        if (event instanceof String line) {
            batch.add(line);
            if (batch.size() == 100) {
                // what this group's queue has finished dispatching: excludes the line being handled now
                ProcessingCheckpoint.Mark mark = checkpoint.mark();
                List<String> toSave = List.copyOf(batch);
                batch.clear();
                CompletableFuture.runAsync(() -> saveBatch(toSave))
                        .thenRun(() -> checkpoint.durableThrough(mark));   // any thread
            }
        }
        return true;
    }

    private void saveBatch(List<String> lines) {
        // write the batch to your store
    }
}
```

- A hold is per feed and processor group (the group's agent name). While held, that group's queue counts as processed
  only up to the last mark you declared durable. `release()` returns to the default.
- `mark()` reads how many items the group's queue has finished dispatching. Inside a handler that excludes the item
  being handled, so that item is re-delivered after a crash: conservative, never lossy. Marks only advance.
- Take the hold before the feed delivers (in `@ServiceRegistered`, `init` or `start`). Items committed before it stay
  committed.
- With `feedStatusEvents` on, the status events pass through the queue too, and a mark counts them.
- A group restarted as a new agent subscribes a new queue: marks taken before the restart are ignored, and the hold
  starts at nothing durable. Take a new mark once the restored state is durable.
- A host can take the same hold with `MongooseServer.processingCheckpoint(feed, group)`. The service is registered
  as `mongoose.processingCheckpoints`.

The snippet above was checked against 1.1.3-SNAPSHOT: with 250 lines and batches of 100, the source committed 199
lines (the second mark) and held the rest.

## Pointer files from 1.1.2 and earlier

Earlier releases wrote the pointer when a line was **read**, not processed, and stored a count of decoded characters,
not bytes. On first use, 1.1.3:

- keeps the stored position and gives the pointer an epoch;
- if the position is inside a line (possible after non-ASCII text), resumes after the next line terminator, and
  commits that offset, with the epoch, at once.

A pointer file shorter than 8 bytes, or empty, is read from 0 with a warning.

For a file that is all ASCII the count is the byte offset and nothing changes. After non-ASCII text the old count can
be several lines short of what was processed: those lines are delivered again, under the new epoch, so
`Delivery.alreadyApplied` does not recognise them. If your processor cannot tolerate that, either set the pointer
yourself before upgrading, or de-duplicate those lines by content.

**Downgrade, then upgrade again.** Once 1.1.3 has written an epoch, it trusts the stored offset. If you run 1.1.2 on
the same pointer after non-ASCII lines, it writes a character count that 1.1.3 then reads as a byte offset. Delete the
pointer after such a downgrade: the next start re-reads the file under a new epoch.

## Checklist

- [ ] The feed uses `COMMITED`, and its overload policy is `PAUSE`, explicitly.
- [ ] Processing is stateless or idempotent, or it de-duplicates with `DeliveryContext`.
- [ ] State saved asynchronously or in batches is covered by a `ProcessingCheckpoint`.
- [ ] Your launcher treats `stalled` as a failure reason, and restarts to clear a hold.
- [ ] Log rotation is append-then-rename or a new file at the path, not an in-place rewrite that keeps the first 4 KB.
- [ ] Before upgrading from 1.1.2, you know whether the input has non-ASCII text before the pointer.

## Run the example

The example needs Mongoose 1.1.3. Until 1.1.3 is published, install a 1.1.3-SNAPSHOT build of Mongoose locally,
then:

```bash
cd how-to/file-feed-at-least-once
mvn compile exec:exec
```

It writes its files under `target/at-least-once-run`, including each server's log (`run1.log`, `run2.log`).
