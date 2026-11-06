# Mongoose 1.1.3 (unreleased)

This release adds a caught-up signal for feeds and makes file feeds lossless. It is not published yet: the
features below are on the `feature/feed-caught-up` branch, and this page describes that branch. Dependencies are
unchanged from 1.1.2.

- **Know when a run has processed everything.** The `feed.status` admin command and `MongooseServer.feedStatus(...)`
  report, per feed, `UNKNOWN`, `BEHIND`, `CAUGHT_UP` or `END_OF_STREAM`, whether the processors have drained it, and
  whether anything was lost. A feed can also deliver its status into the graph as a `FeedStatus` event.
  See [Wait until a feed has caught up](../example/how-to/how-to-wait-for-feed-caught-up.md).
- **At-least-once file feeds.** A `COMMITED` file feed commits its read pointer only after every subscriber has
  processed the line, so a restart after a crash re-delivers instead of skipping.
  See [Process a file feed at least once](../example/how-to/how-to-file-feed-at-least-once.md).
- **`PAUSE` overload policy.** A full queue pauses a re-readable source instead of dropping. It is the new default for
  file feeds. See [Configuring overload and loss](../guide/overload-configuration.md).
- **`DeliveryContext`.** A processor can read each item's feed, sequence, epoch and source position, store the position
  with its state, and skip re-delivered items (`Delivery.alreadyApplied`). Recordings carry it.
  `DeliveryContext.onComplete(Runnable)` runs a callback once the delivery is complete, after the events it queued
  reentrantly and before the feed can commit it: the recommended place to save state with the position. A callback
  given up on after its retries is a loss: `feed.status` counts it in `completionFailures` and reports `lossless:false`.
  See [De-duplicate re-delivered events](../example/how-to/how-to-deduplicate-with-delivery-context.md).
- **`ProcessingCheckpoint`.** A processor that saves state asynchronously or in batches can hold commits until its
  state is durable.

## Behaviour changes

Read these before upgrading a deployment that uses file feeds.

1. **File feeds default to `PAUSE`.** A `FileEventSource` feed with no `overload` block pauses at a full queue instead
   of dropping after 10 ms. A slow processor now slows the file feed down instead of losing lines. An explicit policy
   always wins: set `overload.policy: DROP_AFTER_SPIN` to keep the old behaviour. The deprecated
   `slowConsumerStrategy: BACKOFF` is an explicit `DROP_AFTER_SPIN`, file feeds included. Other sources keep
   `DROP_AFTER_SPIN` as their default. The boot warning for a feed with no policy now names both defaults.
2. **`COMMITED` commits after processing.** After a crash, a restart re-delivers the lines that were read but not
   processed, and the few lines processed since the last commit (measured at 1 to 5 lines). Expect duplicates, never a
   gap. A clean stop commits everything the processor groups finished, including what they finished after the feed
   stopped.
3. **The pointer is the exact byte offset**, and the pointer file also holds an epoch, the input file's identity and a
   fingerprint of its first 4 KB. A pointer written by 1.1.2 or earlier keeps its position and gets an epoch on first
   use. After non-ASCII text, the old pointer was a character count: it is aligned to the next line start, and lines
   it fell short of are delivered again. After a downgrade to 1.1.2 and a new upgrade, delete the pointer.
4. **A replaced or truncated input file resets the pointer** to 0 under a new epoch, with a warning, at start and
   while tailing: a rotated log is picked up without a restart. Before, a truncated file was read from past its end,
   and a tailing source stayed on a replaced file.
5. **A new inode with the same first 4 KB keeps the pointer.** A file under another file key (a remount, a copy-up,
   or a writer that appends by writing a temporary file and renaming it) whose fingerprinted bytes match, and which is
   not shorter than the pointer, is taken as the same file: the pointer and the epoch are kept, with a warning. A
   rewrite that keeps the first 4 KB and the length is not detected.
6. **A removed consumer holds the pointer for the rest of the run.** If a consumer of a `COMMITED` feed is removed
   while it has unprocessed lines, for any reason including a voluntary unsubscribe, the pointer stays before those
   lines until the server restarts, and `feed.status` reports the feed `stalled` (not settled, still lossless). If it
   was the last consumer, the feed also stops reading. A fenced or dead consumer pauses the whole feed. There is no
   operator release yet; a `delivery.release` command is planned. Avoid unsubscribing consumers with a backlog from a
   `COMMITED` feed you expect to complete.
7. **Recordings carry each positioned input's `DeliveryContext`.** `CsvReplayStore` writes a `DELIVERY` line before such
   an input, and a reader from 1.1.2 or earlier refuses the file. Recordings of feeds without positions are unchanged.
   A recording made by a pre-release build of this branch, before delivery metadata was recorded, replays without
   context: not equivalent for a processor that reads `DeliveryContext`. Record such streams again.
8. **Items of a `COMMITED` feed travel in their queues wrapped as `PositionedItem`.** Processors receive the bare item,
   as before. Code that drains a subscriber queue itself (some tests) sees the wrapper; its `toString` is the item's.

## New API

- `MongooseServer.feedStatus(String...)`, `FeedStatusReport`, admin command `feed.status [--json] [feed ...]`
  (registered by `MongooseServerAdmin`).
- `FeedState`, `FeedStatus`; `EventFeedConfig.feedStatusEvents` (YAML `feedStatusEvents: true`), off by default.
- `OverloadPolicy.PAUSE`, `OverloadPolicyConfig.pause()`; YAML `overload.policy: PAUSE`.
- `DeliveryContext` and `Delivery` (service `mongoose.deliveryContext`); `DeliveryContext.onComplete`,
  `whenComplete`, `onFailure`, `whenFailed` and `attempt()`.
- `FeedStatusReport.Delivery.completionFailures()` (`feed.status` field `completionFailures`), counted in `lossless`.
- `ProcessingCheckpointService` (service `mongoose.processingCheckpoints`), `ProcessingCheckpoint`,
  `MongooseServer.processingCheckpoint(feed, group)`.
- For source authors: `EventToQueuePublisher.tryPublish(item, position)`, `acknowledgeWith`, `pollAcknowledgements`,
  `flushPaused`, `setPauseByDefault`, `SourceCommitter`, and `reportFeedStatus` on `AbstractEventSourceService`. The
  protocol is in [Feed delivery guarantees (design)](../design/feed-delivery.md).

Plugin connectors are unchanged in this release: they report `UNKNOWN` and keep push semantics.

## Design

- [Feed caught-up signal (design)](../design/feed-caught-up.md)
- [Feed delivery guarantees (design)](../design/feed-delivery.md)
