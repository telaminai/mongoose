# Feed caught-up signal

Status: implemented on `feature/feed-caught-up` (based on `main` at `902c3b6`, release `v1.1.2`). Uncommitted.
Scope: the Mongoose dispatch layer (`EventToQueuePublisher`, `EventFlowManager`, `EventQueueToEventProcessorAgent`),
the core sources (`FileEventSource`, `InMemoryEventSource`, the admin command queues), the admin command surface
(`feed.status`), and feed configuration (`feedStatusEvents`). No change to Fluxtion, generated processors, the control
plane or the lifecycle phases.

Each item below is marked **implemented** or **proposed** (follow-up).

Delivery guarantees built on this signal (commit the read position only after processing, the no-drop `PAUSE` policy
for re-readable sources, and the `lossless` flag of `feed.status`) are specified in
[feed-delivery.md](feed-delivery.md).

## 1. Problem

A host that runs a bundle to completion (the starter's run scripts, a test harness, a batch launcher) must know when
the run has *settled*: every feed has delivered what is available to it, and the processors have handled it. Mongoose
offers no such signal. `startComplete()` (Fluxtion `@StartComplete`) means "the server has started", not "the feed
has been read". So today the starter's `BundleLifecycle` polls record counts through the admin/registry API and waits
for a quiet period: counts unchanged for N seconds. That

- costs N seconds on every server run, even when the input was read in milliseconds;
- can cut a feed short when the feed pauses longer than N seconds (a slow disk, a large line, a GC pause);
- cannot tell "nothing more will come" from "nothing has come yet".

The `FileEventSource` has the information, but drops it: `doWork()` loops `while (reader.ready())`, publishes each
line and returns 0 when nothing new is available. Nothing records that it reached the end of the file.

## 2. Two consumers, two deliverables

| Consumer | Deliverable | Default | Status |
| --- | --- | --- | --- |
| **Primary: the host** (launcher, test harness, operator) | A status *query*: per feed, the state its source reports, at which position, and whether every subscriber queue has dispatched up to that position. Java `MongooseServer.feedStatus(...)`, admin command `feed.status`. No event enters any graph. | always on | implemented |
| **Secondary: a processor** that wants to react in-graph (flush a batch at end of file, emit a summary) | The same status as an ordinary event, `FeedStatus`, delivered in order with the feed's items. | off; per-feed opt-in `feedStatusEvents: true` | implemented |

The primary deliverable never changes what a graph sees, so audit logs, replay recordings, record counts and existing
tests are unchanged. The secondary one is an ordinary event once a feed turns it on: it is audited, recorded and
replayed like any other input (section 9).

## 3. States

`com.telamin.mongoose.service.feedstatus.FeedState`:

| State | Meaning | Host action |
| --- | --- | --- |
| `UNKNOWN` | The source cannot tell, or does not report. The state of every feed whose source predates this work (all plugin connectors today), and of sources that can never know (JMS, multicast). | fall back to the quiet-period heuristic for this feed only |
| `BEHIND` | More is available than the source has published: it is starting, reading, or saw more arrive after it caught up. | wait |
| `CAUGHT_UP` | Everything currently available has been published, up to `sequence`. More may arrive; the source then reports `BEHIND` *before* it publishes it. | settled once drained |
| `END_OF_STREAM` | A bounded source has published everything it ever will, up to `sequence`. Final. | settled once drained |

**Bounded versus unbounded is a property of the source, not a state.** `FeedStatus.bounded()` says whether the source is
finite (ends in `END_OF_STREAM`). An unbounded source at its head (a tailed file at its current end, a Kafka consumer
with zero lag against the end offsets) is `CAUGHT_UP`. Changes from the design direction, and why:

- **No `LIVE` state.** "An infinite source has reached the live head" and "a tailing file has reached its current
  end" mean the same to a host: everything available now has been delivered. One state, `CAUGHT_UP`, plus the `bounded`
  flag, keeps the host rule to one line and leaves no question of which of two states a connector should use.
- **No `CATCHING_UP` separate from `BEHIND`.** Nothing a host does differs between them.
- **`UNKNOWN`, not `NOT_SUPPORTED`.** A source may know sometimes and not others (a Kafka consumer during a rebalance,
  a file source after a read error). One state covers "cannot tell now", whatever the reason; `detail` says why.
- **No `STARTING`.** A reporting source reports `BEHIND` (detail `starting`) at `start()`, so a host polling before
  the start completes sees "wait", which is right.

## 4. Event shape

```java
package com.telamin.mongoose.service.feedstatus;

public record FeedStatus(String feedName, FeedState state, long sequence, boolean bounded, String detail)
        implements com.telamin.fluxtion.runtime.event.Event, java.io.Serializable {
    @Override public String filterString() { return feedName; }   // @OnEventHandler(filterString = "<feed>")
}
```

- `sequence` is the feed publisher's sequence number when the state was reported: the number of items the feed had
  published (or cached) by then. It is the *position* of the claim "caught up at sequence N".
- No wall-clock time: the event must replay byte for byte, and a recording already gives each input its instant.
- `detail` is for humans: `starting`, `file not found: <path>`, `partial line pending`,
  `unterminated last line not published`, `read failed, reopening: <exception>`. Null when there is nothing to add.
- It lives in Mongoose (`service.feedstatus`), not the Fluxtion runtime. It is a hosting concept, like admin commands;
  only the generic `Event` interface is Fluxtion's. No Fluxtion change is needed. (A follow-up could move it if
  Fluxtion's own runners want it; section 13.)

## 5. Reporting: transitions only, at a position

A source reports a state **on a transition only, never per poll**:

```java
// AbstractEventSourceService (protected), delegating to EventToQueuePublisher.reportFeedStatus
reportFeedStatus(FeedState state, boolean bounded, String detail);
```

`EventToQueuePublisher.reportFeedStatus` (**implemented**) runs under the publish lock, the lock every item's dispatch
takes, and records in one immutable `FeedPosition`:

- the `FeedStatus`, with `sequence` = the publisher's current sequence number;
- `version` + 1 (how many states have been reported; a host can see that a feed moved between two reads);
- every target queue's producer position (`OneToOneConcurrentArrayQueue.addedCount()`), keyed by queue identity;
- `cachedPending`: items in the event log not yet dispatched (a cached feed before its start completes).

Because the publisher is every target queue's only producer and holds the lock, the positions are exact: queue *q* had
been given exactly `positions[q]` items when the source said "caught up at N". A report equal to the last one at the
same sequence number is ignored, so a source that reports more than it needs to costs nothing downstream.

Before the publisher's `startComplete()` nothing has been dispatched; a status reported then is kept, and at start
completion (after the cache is replayed) its positions are taken again so they count the replayed items. With
`feedStatusEvents` on, that status is also the first one delivered in-band.

A report made from code running inside a commit on the same feed (a journal codec, an error listener) is deferred
until the commit completes, like every other producer operation on a feed with a blocking queue (`FEED_STATUS` deferred
kind).

**Probed feeds (implemented).** A source with no transitions of its own, whose items go straight to the queues, gives
the publisher a probe instead: `EventToQueuePublisher.feedStatusProbe(BooleanSupplier caughtUp, boolean bounded)`. The
host's query evaluates it under the publish lock and takes the queue positions *then*. Used by:

- `InMemoryEventSource` (and so `HandlerPipe`): caught up when its start is complete and every item accepted by
  `offer`/`publishNow` has been published. Accepted is counted before the item is queued, published after its publish
  returns, so the host can only see `BEHIND` too long, never `CAUGHT_UP` with an item on its way. Cost: two
  uncontended `AtomicLong` increments per item. A probed feed reports no transitions, so it delivers no in-band event.
- the admin command queues (`adminCommand.<name>`, the registry's own feed): always caught up (a command goes straight
  to its queue), so they never hold a host back yet are still checked for drained.

## 6. FileEventSource states per read strategy (implemented)

`bounded` = the strategy is `ONCE_*`. All reports are made from `doWork()` once `startComplete()` has run
(`publishToQueue`); `start()` reports `BEHIND` / `starting`.

| Strategy | Start | File has unread data | Read to the current end | Later growth |
| --- | --- | --- | --- | --- |
| `COMMITED` (tail from the read pointer) | `BEHIND` | `BEHIND` before the first line | `CAUGHT_UP` @ lines published this run | `BEHIND`, lines, `CAUGHT_UP` |
| `EARLIEST` (tail from 0) | `BEHIND` | as above | `CAUGHT_UP` | as above |
| `LATEST` (tail from EOF at start) | `BEHIND` | (nothing before EOF is read) | `CAUGHT_UP` @ 0 | as above; publishes the last line of each read, as before |
| `ONCE_EARLIEST` | `BEHIND` | `BEHIND` | `END_OF_STREAM` @ lines published | ignored; stays `END_OF_STREAM` |
| `ONCE_LATEST` | `BEHIND` | — | `END_OF_STREAM` @ 0 at the first poll | ignored |

Edge cases:

- **Missing file** (any strategy): `CAUGHT_UP`, detail `file not found: <path>`. Everything available (nothing) has been
  delivered; the source keeps polling for the file, as before, and reports `BEHIND` when it appears. A launcher that
  needs the file to exist should check that itself (open question 2).
- **Empty file with `ONCE_EARLIEST`**: `CAUGHT_UP` (bounded) until data appears; the existing code keeps polling an
  empty file and reads it once when it has data, then `END_OF_STREAM`.
- **Unterminated last line**: not published (existing behaviour). A tailing source reports `CAUGHT_UP` with detail
  `partial line pending` and publishes the line when its newline arrives; a once source reports `END_OF_STREAM` with
  detail `unterminated last line not published`.
- **Read error**: `UNKNOWN`, detail `read failed, reopening: ...`; the next poll reopens at the stream offset and
  reports as above.
- **`cacheEventLog: true`**: lines read in `start()` are cached; the status is reported after `startComplete()`, and
  the positions count the replayed lines (`cachedPending` stays true until they are dispatched).
- `ONCE_LATEST` publishes nothing: as `docs/guide/read-strategy.md` documents, it starts at the end of the file, makes
  one pass and completes. Its signal is `END_OF_STREAM` @ 0 at the first poll after start, so a launcher waiting on it
  settles at once (asserted in `FileEventSourceFeedStatusTest`).

## 7. Drain detection (implemented)

"Drained" is a **position comparison, never "queue empty"**: an empty queue races with a dispatch in flight (the reader
has polled the last item and the processor is still handling it).

- Each subscriber queue made by `EventFlowManager.getMappingAgent` gets a `dispatched` counter (an `AtomicLong`, shared
  by the readers of that queue, all on the group's thread). `EventQueueToEventProcessorAgent` adds 1 with an ordered
  write (`lazySet`) after each item it takes has finished dispatching to every processor, or was skipped after a
  failure. One ordered store per item on the group thread.
- A feed is **drained** when, for each current target queue *q*, `dispatched[q] >= positions[q]` of the last report,
  and `cachedPending` is false. A queue added after the report has position 0 (it holds only later items). A queue the
  flow manager did not make (a test harness's `addTargetQueue`) is judged by its consumer position, which counts an item
  taken but still being dispatched; documented as best effort.
- The host reads the position first, then the counters. Counters only grow, so a counter read after the position that
  reaches it proves every item published before the report was dispatched; the ordered write and volatile read make the
  processors' effects visible to the host.
- With `feedStatusEvents` on, the positions are taken *after* the in-band status is offered, so drained also means the
  processors handled the status event itself.
- A feed with no subscribers is drained.

**What drained does not cover.** Only work dispatched from the feed queues. A service on its own thread, a vendor SDK
callback, a timer, or a processor that hands work to an executor can still produce output after its feed is drained.
That is why the launcher keeps a short backstop (section 8).

A reader that has died (terminal failure) never reaches its position, so the feed is never drained and the host times
out; the per-subscriber `dispatched/position` in the report shows which queue stopped.

## 8. Host API and the settled rule (implemented)

**Java**: `MongooseServer.feedStatus(String... feedNames)` returns a `FeedStatusReport` for the named feeds, or every
registered feed when none is named; an unknown name throws `IllegalArgumentException`. It takes each feed's publish
lock briefly, so it can wait behind a dispatch to a full queue: for polling, not the hot path.

**Admin command**: `feed.status [--json] [feed ...]`, registered by `MongooseServerAdmin` with the other server
commands, so it is reachable through every admin transport (CLI, `svc-admin-rest`, `svc-admin-web`, telnet). It runs
directly on the caller's thread, not through a processor queue.

Request (as an `AdminCommandRequest`):

```
command:   feed.status
arguments: ["--json", "lines"]          // omit feed names for every feed
```

Response (one `output` call; on an unknown feed one `errOutput` call:
`no such feed: <name>; usage: feed.status [--json] [feed ...]`):

```json
{"settled":true,"fallbackFeeds":[],"feeds":[
  {"feed":"lines","state":"CAUGHT_UP","bounded":false,"sequence":3,"version":2,"detail":null,
   "drained":true,"settled":true,
   "subscribers":[{"queue":"g/lines/onEventCallBack","position":3,"dispatched":3,"drained":true}]}]}
```

Fields: top-level `settled` (every *reporting* feed in the report is settled), `fallbackFeeds` (feeds in `UNKNOWN`,
sorted), `feeds` (sorted by name). Per feed: `state`, `bounded`, `sequence` (position of the claim), `version`
(changes whenever the feed reports; for a probed feed it is the sequence number), `detail`, `drained`, `settled`
(`state` is `CAUGHT_UP`/`END_OF_STREAM` and drained), and per subscriber queue the binding name
(`group/feed/callback`), `position`, `dispatched`, `drained`. Without `--json` the same as indented text.

**The settled rule** for a launcher:

1. Poll `feed.status --json` (optionally naming the bundle's input feeds) every few milliseconds.
2. When `settled` is true: for each feed in `fallbackFeeds`, apply the existing quiet-period heuristic (record counts
   unchanged for N seconds) to that feed only. With no fallback feeds, skip it.
3. Then a short backstop quiet period (the launcher's `EXPECT_RECORDS` or a few hundred milliseconds of unchanged sink
   counts) for asynchronous output (section 7).
4. Settled. A tailing feed can later go `BEHIND`; a launcher that runs to completion stops the server here.

`settled` is "as of the source's last look": data appended to a tailed file after the source's last poll is not seen
until its next poll, which then reports `BEHIND`. That is inherent in polling and the same for any heuristic.

**Losses** ([feed-delivery.md](feed-delivery.md) section 7): `settled` says the run is complete, not that it is whole.
The report also carries `lossless` (top level and per feed) and per feed `dropped`, `refused`, `downgraded`, `policy`,
`paused`, `pauses`, `acknowledged`, `pendingAcks`. A launcher that reaches `settled` with `lossless:false` reports the
run as lossy.

**Health**: not wired into `MongooseHealthService`. "Settled" is not health: a feed that is `BEHIND` is healthy.
Proposed follow-up only if an operator dashboard wants a per-feed "lagging" check.

## 9. In-graph delivery, audit and replay (implemented)

**Opt-in, per feed.** `EventFeedConfig.feedStatusEvents` (YAML `feedStatusEvents: true`, builder
`.feedStatusEvents(true)`, or `EventSource.setFeedStatusEvents(true)` on the source). Off by default. Per feed rather
than per processor because the event must travel in the feed's queues to be ordered with its items, and a processor
already chooses its feeds; a processor that does not handle `FeedStatus` simply ignores it, as it ignores any event
type it has no handler for. Off, nothing is written to any queue, so a processor sees no new event and every existing
test, audit log and recording is unchanged (verified: the full suite, and
`FeedStatusRecordReplayTest.offByDefaultTheRecordingHoldsOnlyTheFeedsItems`).

**On**, each reported status (from start completion on) is offered, bare, to every target queue, after the items it
covers:

- never wrapped in `NamedFeedEvent` or passed through the data mapper; it names its feed itself;
- takes no sequence number, is not journalled and is not counted by the feed's publish counter, so record counts and
  journals are unchanged;
- delivered to `onEvent` routes only: a reader on a typed callback route (`CallBackType.forClass`) takes it, counts it
  as dispatched and does not give it to its strategy, which could not take it;
- offered with drop-after-spin semantics, like the cached-event replay: a full queue drops it and counts the drop as
  any other (a `BLOCK_WITH_DEADLINE` feed does not block on it);
- a node handles it as an ordinary event: `@OnEventHandler(filterString = "lines") public boolean onStatus(FeedStatus s)`.

**Audit**: the processor's audit log records the event like any other input.

**Replay**: in RECORD mode the reader records it as an `Inline` entry (seq -1, copied with `InputCopy`; `FeedStatus`
is a `Serializable` record), between the `Indexed` entries of the lines around it. A replay gives it to the processor
in its place, at its recorded instant; the replaying server's own file source is muted for the replayed processor like
any live input. Verified end to end by
`FeedStatusRecordReplayTest.turnedOnAStatusIsRecordedInlineAndReplayedInItsPlace`. A recording made with the option on
therefore has more entries than one made with it off; that is the explicit cost of opting in.

## 10. How other sources map onto the states

| Source | bounded | Mapping | Status |
| --- | --- | --- | --- |
| core `FileEventSource` | `ONCE_*` | section 6 | implemented |
| core `InMemoryEventSource`, `HandlerPipe` | no | probe: caught up when everything accepted is published | implemented |
| admin command queues | no | probe: always caught up | implemented |
| any source that does not report | no | `UNKNOWN` (initial state of every publisher) | implemented (default) |
| plugin `connector-file` `FileEventSource` | `ONCE_*` | copy of the core change (or extend the core class) | proposed |
| `connector-chronicle` `ChronicleEventSource` | no | in `doWork`: `BEHIND` before the first `readOne()` that returns true after a `CAUGHT_UP`; `CAUGHT_UP` when `readOne()` returns false (tailer at the queue end). A few lines. | proposed |
| `connector-kafka` `KafkaMessageConsumer` | no | after each `poll`: `UNKNOWN` while there is no assignment or a rebalance is in progress; `CAUGHT_UP` when `consumer.currentLag(tp)` is 0 for every assigned partition (local, no broker call; fall back to `endOffsets` vs `position`, throttled, if `currentLag` is empty); else `BEHIND`. Report from the agent thread only. | proposed |
| `connector-aeron` `AeronArchiveEventSource` | archive replay to a stop position: yes; `Long.MAX_VALUE` (follows the recording): no | archive mode: `CAUGHT_UP` when the replay image position reaches the recording's current stop position (`archive.getStopPosition` / `getRecordingPosition`), `END_OF_STREAM` for a bounded replay at its end. Live mode: `UNKNOWN`. | proposed |
| `connector-multicast` `MulticastEventSource` | no | `UNKNOWN`: a multicast receiver cannot know what the sender has sent. No change. | default |
| JMS (no connector yet) | no | `UNKNOWN`: a JMS consumer cannot see queue depth portably. A broker-specific browser could report `CAUGHT_UP` on an empty queue, at the risk of in-flight redelivery. | default |
| `svc-loader-feed` and other loaders | per loader | a loader that reads a finite resource reports `END_OF_STREAM`; otherwise `UNKNOWN` | proposed |

A plugin reports through the protected `reportFeedStatus(state, bounded, detail)` it inherits from
`AbstractEventSourceService`, on transitions only, from its agent thread.

## 11. Compatibility

- Default behaviour unchanged: no new event is written to any queue unless a feed sets `feedStatusEvents`; the publish
  path is unchanged (no extra work per publish); the reader adds one ordered store per item; the in-memory source two
  uncontended atomic increments per item.
- `EventSource.setFeedStatusEvents` is a new default method; `EventFeedConfig` gains one boolean (YAML key, builder
  method); `EventToQueuePublisher` gains public methods; nothing is removed or changed in signature.
- New admin command `feed.status`; existing commands unchanged.
- Sources written before this work report `UNKNOWN` and need no change.
- Full core suite: 1224 tests on `main` before the change; see section 12 for after.

## 12. Tests (implemented)

- `connector/file/FileEventSourceFeedStatusTest` (15): every read strategy, with and without `cacheEventLog`; a tailed
  file `CAUGHT_UP` → grows → `BEHIND` → `CAUGHT_UP` at the new position, with the exact in-band order
  `BEHIND@0, b1, CAUGHT_UP@1, BEHIND@1, b2, b3, CAUGHT_UP@3`; idle polls report nothing; `END_OF_STREAM` is final;
  missing file, empty once file, unterminated last line, cached lines counted once replayed; nothing in the queue by
  default; a never-reporting publisher is `UNKNOWN`.
- `service/feedstatus/FeedStatusServerTest` (4), on a booted server:
  - the host reports `CAUGHT_UP` @ 3 but *not* drained while the processor is blocked in the last line (position 3,
    dispatched 2), and settled only after it returns, at which point the processor has handled all 3; the `feed.status
    --json` admin command says the same; the file grows and the feed settles again at 5; an unknown feed is an error;
  - a bounded `ONCE_EARLIEST` feed ends in `END_OF_STREAM`, and a source that never reports is `UNKNOWN`, listed in
    `fallbackFeeds`, without holding `settled` back;
  - an in-memory feed is not settled while offered items are undispatched, and settles once they are;
  - a processor on a feed without `feedStatusEvents` receives no `FeedStatus`; one on a feed with it receives
    `CAUGHT_UP@2` after its two lines.
- `service/feedstatus/FeedStatusRecordReplayTest` (2): RECORD with the option off holds exactly the 2 `Indexed`
  entries; with it on, statuses are `Inline` entries, and a replay into a fresh server reproduces the processor's output
  (lines and statuses) at the same instants.

## 13. Follow-ups

1. Plugin connectors (section 10): `connector-file`, `connector-chronicle`, `connector-kafka`, `connector-aeron`
   archive mode. Multicast and JMS stay `UNKNOWN`.
2. Starter `BundleLifecycle`: replace the record-count quiet period with the settled rule (section 8); keep the
   heuristic for `fallbackFeeds` and the short backstop.
3. Optional: a `feed.await <timeoutMillis> [feed ...]` admin command that blocks until settled, to save a launcher
   polling; and an `InMemoryEventSource.endOfStream()` to let a test declare a finite in-memory feed.
4. Optional: in-band delivery from probed feeds (in-memory), if a processor needs it.
5. Optional: move `FeedStatus` to the Fluxtion runtime if Fluxtion's own runners want the same signal (out of scope).

## 14. Decisions (were open questions)

1. **A launcher names the feeds it waits on.** The admin command and `feedStatus(...)` take feed names; the starter
   passes the bundle's input feed names, so an internal pipe a processor is still writing to never holds it back.
2. **Missing file: `CAUGHT_UP` with detail `file not found: ...`.** Everything available is delivered; reporting
   `BEHIND` would make a launcher wait for a file that may never come. A launcher that needs the file reads `detail`.
3. **`ONCE_LATEST` publishing nothing is intended** (documented in the read-strategy guide: start at end of file, one
   pass). It needs no new signal: `END_OF_STREAM` @ 0 at the first poll is its signal.
4. **Opt-in stays per feed.** Per-processor opt-in would need Fluxtion to expose a processor's handled event types;
   that is a Fluxtion follow-up, not part of this change.
