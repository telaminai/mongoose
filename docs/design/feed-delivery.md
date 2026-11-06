# Feed delivery guarantees: commit after processing, PAUSE, honest status

Status: implemented and committed on `feature/feed-caught-up` (not yet merged), on top of the feed caught-up work
([feed-caught-up.md](feed-caught-up.md)), including the fixes of the second and third reviews and of the independent
review of 9567d6d (R1 to R6, section 11). Scope: `EventToQueuePublisher`, `EventFlowManager`, `FileEventSource`, the overload policy
(`OverloadPolicy`, `OverloadSettings`, `OverloadPolicyConfig`), `FeedStatusReport` / `feed.status`, a new package
`service.delivery`, and the final commit step of the ordered shutdown (`LifecycleManager`). No change to Fluxtion,
generated processors or the plugins. The replay recording gains the delivery of each positioned input (section 8a): a
`delivery` component on `ReplayEntry.Indexed` and `Inline`, and a `DELIVERY` line kind in `CsvReplayStore`. A
delivery-complete hook (section 5b, `DeliveryContext.onComplete`) runs callbacks once a positioned item's delivery,
including the events it queued reentrantly, is complete and before the item counts as dispatched.

Each item is marked **implemented** or **proposed** (follow-up).

## 1. Problem: measured

A kill test on a real server (an example stateful processor on this branch, a `COMMITED` file feed, a file sink, state saved
after every event; `kill -9`, restart, compare alerts with a clean run) showed:

1. Kills between events (the queue empty at the kill): lossless, 4/4 identical.
2. A 900-line burst appended in one write, `kill -9` 0.1 s later: the `.readPointer` was already at the end of the
   file with only ~446 burst lines processed. After the restart the rest were skipped: 386 alerts missing, 235
   divergent. Cause: `FileEventSource.extractLine()` wrote the pointer when a line was **read**, before it was published
   or processed. At-most-once.
3. With no crash: a 40,000-line file, a processor slowed to ~620 lines/s, the 1024-slot queue filled and the default
   `DROP_AFTER_SPIN` (10 ms) dropped 112 lines. The pointer had passed them, so they were gone; only WARNING lines
   recorded it, and `feed.status` still said `settled`. The only other policy, `BLOCK_WITH_DEADLINE`, never waits on an
   agent thread (it falls back to drop), and a file source publishes from its agent thread: a file feed had no lossless
   setting.

4. Re-run with this change (`kill -9` 0.1 s into a 900-line burst): the harness read the alert count (1,869, about
   line 2,465), *then* the pointer (after line 2,509), then killed. Final output diverged (missing 1,014, extra 1,435).
   Diagnosis: not a pointer ahead of processing. The harness samples the alerts file before it reads the pointer, and
   the server keeps processing during the `wc`, the `python3` start-up that reads the pointer and the `kill` (tens of
   ms: ~44 lines at this app's rate), so the two samples are not simultaneous. Read after the kill, both files are
   exact (the pointer page and the unbuffered sink survive SIGKILL). The real kill test below shows the pointer is
   never ahead of durable state, 1 to 5 lines behind it. The divergence is the opposite gap: those few lines are
   re-delivered and applied a second time to state that already contains them (this app saves state per event and is
   not idempotent: joins, payments), and the difference cascades. At-least-once delivery is not enough for such an
   app; section 5a adds the consumer-side offset that makes it correct.

Three defects, one per deliverable: the commit point (section 3-5), the overload policy (section 6), the status
(section 7).

## 2. What a launcher or an app can rely on now

| Feed | Default overload policy | Across a crash (`kill -9`) | Without a crash |
| --- | --- | --- | --- |
| core `FileEventSource`, `COMMITED`, stateless or idempotent processing | `PAUSE` (was `DROP_AFTER_SPIN`) | **at-least-once**: the pointer never runs ahead of processing; read-but-unprocessed lines are re-read; the lines processed in the last commit lag (about one feed duty cycle, a few lines) are delivered again | **no drop**: a full queue pauses the source |
| core `FileEventSource`, `COMMITED`, **stateful** app that saves state per event | `PAUSE` | at-least-once delivery is **not enough on its own**: a re-delivered line is applied twice to state that already contains it. **Effectively-once for state** when the app stores the source position with its state and skips a delivery at or before it (section 5a, consumer-side offset) | no drop |
| core `FileEventSource`, `EARLIEST`/`ONCE_EARLIEST` | `PAUSE` | re-reads from 0 (no pointer), as before | no drop |
| core `FileEventSource`, `LATEST`/`ONCE_LATEST` | `PAUSE` | starts at end of file, as before (by design not a replayable read) | no drop of the lines it publishes |
| any file feed with an explicit `overload.policy: DROP_AFTER_SPIN` | as configured | at-least-once for what was not dropped; a dropped line is lost and committed past | drops counted, `lossless:false` |
| push sources (in-memory, `HandlerPipe`, admin, plugins today) | `DROP_AFTER_SPIN`, unchanged | no commit protocol (nothing to commit) | unchanged; drops now visible in `feed.status` |

Each kind of processing, after a `kill -9`:

| Processing | Without consumer-side offsets | With consumer-side offsets (5a) |
| --- | --- | --- |
| stateless (a filter, a router) | correct; a few duplicate outputs | not needed |
| idempotent state (set, upsert by key) | correct | not needed |
| non-idempotent state saved per event (counters, balances, joins) | **wrong after a crash**: the lines in the commit lag are applied twice | correct: each line applied to state once; at most one duplicate output (the line whose output was written before its state at the kill) unless the sink also de-duplicates |
| state saved asynchronously or in batches | hold commits with a processing checkpoint (section 5) and use consumer-side offsets | correct |

And from `feed.status` (section 7): `settled` = the run is complete; `lossless` = no feed in the report dropped or
refused an item. A launcher stops on `settled` and fails the run when `lossless` is false.

## 3. The general protocol (implemented)

The question a commit answers is the same for every source: *may the source forget this item?* Kafka answers it with a
committed offset, JMS with an acknowledgement, a file with a read pointer, Chronicle with a tailer index. In each the
rule for at-least-once is the same: **the position is committed only after the item it covers has been processed**.
Mongoose is the only party that knows when that is, and the source is the only party that knows how to commit. So the
protocol splits along that line:

```
source thread                         EventToQueuePublisher                      processor group thread
-------------                         ---------------------                      ----------------------
tryPublish(item, position) ──────────▶ seq N := ++sequence
                                       pending[N] := position          offer ───▶ queue q (producer pos P_q)
                                       after dispatch: pending[N].after[q] := P_q
                                                                                  dispatch item, then
                                                                                  dispatched[q] := dispatched[q]+1 (ordered)
pollAcknowledgements() ──────────────▶ while head N has dispatched[q] >= after[q] for every live q:
                                          acked += (N, position); remove head
committer.acknowledged(N, position) ◀─ (outside the publish lock, on the source thread)
committer.commit()  ── write pointer / commitSync / acknowledge
```

1. **A source attaches its own position to each item** it publishes: `EventToQueuePublisher.tryPublish(item,
   position)`, or `cache(item, position)` before the start completes. The position is opaque to Mongoose (`Object`):
   a file's byte offset after the line, a Kafka `(partition, offset)`, a JMS `Message`, a Chronicle tailer index.
2. **Mongoose keeps sequence to position per publisher**, in publish order: `PendingAcknowledgements`, a ring of
   (sequence, position, per-queue producer position after the item). Only the window between the last acknowledged
   item and the last published one is held (positions are monotonic per source).
3. **An item is acknowledged** when each subscriber queue it was offered to has dispatched up to the queue's producer
   position just after it: the existing per-queue `dispatched` counters of [feed-caught-up.md](feed-caught-up.md)
   section 7, the same position comparison as "drained". In-band feed status events and items of the same feed for
   another binding sharing the queue are counted by both sides, so the comparison is exact.
4. **The source commits its own way** (`SourceCommitter<P>`: `acknowledged(seq, position)` in publish order, then one
   `commit()` per run). A source that cannot commit registers no committer and pays nothing.

**Where acks are computed, and why there.** The source calls `pollAcknowledgements()` on its own thread, at the start of
each duty cycle (and at stop). It takes the publish lock briefly, reads the queues' `dispatched` counters (volatile
reads of counters the reader already writes with an ordered store), and calls the committer outside the lock. So:

- no new cross-thread write into a queue or a counter: the processor thread still writes only its own counter, the
  source thread only its own pointer;
- the committer runs on the thread that owns the source's resources (a Kafka consumer is not thread-safe; a JMS session
  is single-threaded; the file pointer is the source's), with no locking against the source's reads;
- the cost is per duty cycle, not per item, and zero for a feed without a committer.

The alternative (the reader on the processor thread acknowledging) would put a second writer on the source's commit
state and need a handoff queue back to the source; rejected.

**Edges (implemented, each tested or argued):**

- *Several subscriber queues*: the commit follows the slowest (`thePointerFollowsTheSlowestOfSeveralSubscriberQueues`).
- *A consumer removed from a positioned feed*, for any reason: a quarantine or deregistration of its processor, a group
  restart (the restarted group subscribes a new queue), or a voluntary unsubscribe. Mongoose cannot tell a deliberate
  unsubscribe from a failure, so the rule is the same for all, and it is the safe one: **what the removed queue was
  given and did not process is never acknowledged in this run**. Its acknowledgement point is *retained* (marked
  explicitly, still reading what its reader counted) for as long as an entry of the window waits for it, then dropped;
  a later removal of another queue never touches it (review 2, findings 2 and 3; before, removing one of several
  consumers acknowledged its backlog on the others' progress, and any later unsubscribe erased a retained point;
  the independent review's R1 found the same with consumers that subscribed after the hold and were never given the
  held items: `FileEventSourceIndependentReviewTest.anUnsubscribeAfterTheLastConsumerLeftKeepsItsHold`).
  A consumer that had processed everything it was given leaves nothing behind and holds nothing.
  - *Other consumers remain*: they go on being fed; the pointer stays before the removed consumer's first unprocessed
    item, the window grows behind it until its cap (below) pauses the source, and `feed.status` reports the feed
    `stalled` with the reason `a removed consumer left unprocessed items` (section 7). A restart re-delivers from the
    pointer to every consumer: duplicates for the consumers that had processed those items, which section 5a absorbs
    (`removingOneOfTwoConsumersDoesNotCommitItsBacklog`, `aRemovedSubscriberHoldsThePointerOnlyForWhatItDidNotProcess`).
  - *It was the last consumer* (review F2): the source also **pauses**: with no live consumer it reads nothing and
    commits nothing (`theLastSubscriberGoneStopsReadingAndCommitting`). A later subscriber resumes reading, but the
    pointer stays before the removed consumer's items (`theHeldPointOfARemovedLastConsumerSurvivesALaterUnsubscribe`,
    `PositionedFeedStallTest`).
  - *Liveness trade-off*: a feed held this way does not complete in this run; it reports `stalled` with its reason and
    is not `settled`, so a launcher times out with the reason instead of declaring an incomplete run complete. There
    is **no operator release yet**: `delivery.release` (open question 1) is a priority follow-up (section 12, item 0)
    before COMMITED feeds are relied on with dynamic subscriptions, where a routine unsubscribe of a consumer with a
    backlog holds the feed for the rest of the run.
- *A queue whose group is fenced (quarantine in progress) or dead* (review F1): **holds** acknowledgement, and a
  positioned feed pauses while any of its consumers is fenced or dead: nothing reaches a fenced queue, nothing of its
  backlog is committed, and reading resumes when the fence lifts (`aTransientFenceHoldsThePointer`). Before this fix a
  momentary fence acknowledged the fenced queue's whole backlog. A **dead** group never lifts its fence, so a positioned
  feed with a dead consumer stalls feed-wide (every consumer of the feed stops receiving) until the group is removed
  (then the rule above applies) or the server restarts: a deliberate liveness trade-off for "never commit what was not
  processed", reported as `stalled: consumer <queue> fenced or dead`. A feed without positions keeps the containment rule
  of #79 (a fenced or dead group does not hold the feed back; its losses are counted).
- *A publish nested in a positioned commit* (a journal codec, a data mapper or an item's `toString` calling into the
  same feed): a positioned feed reserves its commit like a blocking one (`committing`), and a nested `tryPublish` is
  told to retry (false, not counted as a pause); a nested push publish is deferred until the commit completes. Before
  this fix the nested item was numbered and dispatched between the outer item's position and its dispatch, and the
  window marked the outer item done with no condition (`aPublishNestedInAPositionedCommitDoesNotAcknowledgeTheOuterItem`).
  The window itself now never marks an entry done on an out-of-order dispatch: it leaves it holding.
- *A positioned cache* (`cache(item, position)`, and `tryPublish` before the start completes: a file source's cached
  startup) is reserved the same way (independent review R2). The data mapper and the journal codec it runs are inside
  the reservation: a nested `tryPublish` is told to retry, any other producer operation (a push publish, another
  cache, a status) is deferred and run in order once the item is cached, and a deferred positioned cache keeps its
  own position. Positions are passed with each call, never through state a nested call could overwrite. Before this
  fix a `tryPublish` from the mapper of a cached item was cached ahead of it with its own position and took the outer
  item's position away, so consuming only the nested item acknowledged past the outer one (`PositionedCacheNestingTest`).
- *An item dropped for a queue* (explicit `DROP_AFTER_SPIN`): it never enters the queue, so it does not hold that
  queue's condition back. Committing past it is what a drop means; the drop is counted and `lossless` goes false
  (`anExplicitDropPolicyDropsCountsAndCommitsPastTheDrop`).
- *An item filtered by the data mapper*, or cached on a feed without an event log: nothing will ever hold it; it is due
  as soon as the items before it.
- *A failed item*: the reader counts an item skipped after a failure as dispatched (feed-caught-up section 7), so it is
  acknowledged: the failure policy (retry, dead-letter, skip) already decided its fate. Re-reading it after a crash
  would repeat the same failure.
- *Coalescing*: an entry whose condition equals the one before it (a drop, a filtered item) is merged into it, so the
  window is bounded by what the queues hold, not by how many items were dropped. A source is told at least the last
  position of each merged run; for a totally ordered position (file, Chronicle) that is all it needs; for independent
  positions (Kafka partitions) a position it was not told is re-delivered after a crash, never lost.
- *Window cap*: `tryPublish` pauses while 65,536 positioned items await acknowledgement
  (`EventToQueuePublisher.maxPendingAcknowledgements`). With PAUSE queues the window is bounded by queue capacity long
  before that; the cap bounds memory when a DROP queue's reader is wedged.

## 4. FileEventSource (implemented)

- `extractLine()` no longer writes the pointer. Each line is offered with its end offset as position; the pointer is
  written (`putLong` + `force`) by the committer, once per acknowledged run, forward only, under a lock shared with
  `stop()` (which unmaps it; the old code could write to an unmapped buffer).
- The pointer is now the **exact byte offset on disk**. It used to count decoded chars, which a restart skipped as
  bytes (resuming early, inside a line, after any non-ASCII text); a first fix counted the UTF-8 length of the decoded
  text, which is still wrong for bytes that are not valid UTF-8 (a malformed byte decodes to U+FFFD, 3 bytes: review
  F4). The source now reads **bytes**, splits lines on the bytes `\n` and `\r` (which never occur inside a multi-byte
  UTF-8 sequence nor in any ASCII-compatible encoding), advances the position by the bytes consumed, and only then
  decodes the line as UTF-8. Chosen over failing on malformed input (`CodingErrorAction.REPORT`): a log file with one
  stray Latin-1 byte should still be read, with the position exact; the line's text carries U+FFFD where the bytes
  were not UTF-8. Tests: `thePointerCountsBytesSoARestartAfterNonAsciiLinesResumesAtALineStart`,
  `thePointerIsTheByteOffsetEvenForBytesThatAreNotUtf8`.
- **Epoch** (review F5): the pointer file (1,024 bytes) also holds an epoch, the input file's identity and a content
  fingerprint: `[0]` offset, `[8]` epoch, `[16]` file key hash, `[24]` layout version, `[32]` fingerprint length,
  `[40]` fingerprint. A new pointer file gets a new epoch; at each open, an input file that was **replaced** or
  **truncated** resets the pointer to 0 under a new epoch, with a warning (before, a truncated file was read from past
  its end, i.e. not at all). Replaced means other leading bytes than those fingerprinted (an FNV-1a hash of the first
  `min(4096, size)` bytes, kept in the pointer file), or another file key (device and inode on Unix) with no matching
  fingerprint; truncated means shorter than the pointer. The fingerprint is taken at each open and **grown at each
  commit** while it covers less than 4 KB and the file is longer (at most 4 KB read per commit, nothing once full), so a
  file first opened holding only a header is fingerprinted over what was read, and a later rewrite with the same
  header is caught (review 3, item 1). The fingerprint is always of the file being read (its open channel, by
  positional reads), never of whatever the path names now: a commit made after the file was replaced, before the source
  reopened it, used to fingerprint the replacement into the old file's pointer, so the next start took the new file
  for the old one and skipped its first bytes (independent review R4,
  `aCommitAfterAReplacementNeverFingerprintsTheNewFile`). A **file key that changes while the fingerprinted bytes match** and the file is
  not shorter than the pointer (an NFS remount or a FUSE/overlay copy-up changes the device id; a writer that appends
  by writing a temporary file and renaming it changes the inode) keeps the pointer and epoch, with a warning, instead
  of re-delivering the whole file (review 3, item 4). Never detected: a rewrite that keeps the first 4 KB (or, for a
  shorter fingerprint, all of them) and the length past the pointer. **Never a time** (review 2, finding 1, a blocker): the first
  version used `creationTime()`, which on Linux before JDK 21's `statx` support (verified in Docker, Temurin 21.0.1) and
  on file systems without a birth time is the modification time, so every append followed by a restart reset the
  pointer and re-delivered the whole file under a new epoch, defeating `alreadyApplied`. Appending to the file, or
  changing its times, is not a replacement. With no file key (Windows) only the fingerprint and the size decide.
  Strategies without a pointer use the file key as their epoch (an in-place rewrite is not detected for them: they have
  no pointer to keep a fingerprint in, and their items carry no position). See 5a.
- **Replaced or truncated while tailing** (independent review R4). Before, identity was checked only when the file was
  opened, so a file rotated or replaced at the path left the reader on the old file, and a file truncated in place left
  it past the new end: the feed delivered nothing and stayed `CAUGHT_UP`. Now, at the end of the open file and before
  it reports `CAUGHT_UP`, a tailing source checks that the path still names the file it reads (the same file key) and
  that the file is not shorter than what was read. If not, it reports `BEHIND` with the reason (`the input file was
  replaced while being read ...` or `... truncated ...`), closes the file, and opens the one at the path on its next
  duty cycle. That open runs the identity check above, exactly as a restart would: a COMMITED pointer moves to 0 under
  a new epoch (or is kept, for the same leading bytes under another file key). Lines of the old file already published
  are still delivered, but their acknowledgements are of the old epoch and never written into the new file's pointer
  (`anOldFileAcknowledgementIsNotCommittedAfterALiveReplacement`); an unterminated last line of the old file is not
  published; on a rotation, what the writer appended to the old file before the new one appeared is read first
  (`aRotatedFileIsReadToItsEndBeforeTheNewOne`). A path with no file (a rotation in progress) is not a change: the
  open file is read on. Strategies without a pointer read the new file from its start. **Design choice**: moving to the
  new epoch rather than failing closed with a restart-required status, because the reopen goes through the same
  identity check as a restart (reading on is exactly as safe as restarting) and a rotated log keeps being read without
  an operator. Cost: one file-attribute read per idle duty cycle. **Not detected while running**: a file rewritten in
  place to at least the length already read, and, on a platform without file keys (Windows), a replacement at least
  that long; the fingerprint catches both at the next open (except the rewrite above that keeps the fingerprinted
  bytes). A reopen (this one, or after a read error) reads a partial last line again from the position, never appending
  its bytes twice (before: `partpartial`; `aPartialLineIsReadOnceAcrossAReopen`).
  **The identity is the opened file's** (independent delta review of PR #98, finding 1). Java gives no file key for an
  open channel, so the path's identity is read before and after the open; if it changed, the channel is closed and the
  open retried (three times, then the next duty cycle). Before, the identity was read once, after the open: a rotation
  between the two labelled the old file with the replacement's key, so every later end-of-file check compared the
  replacement with itself, the feed read only the old file and reported `CAUGHT_UP`, and the new file never arrived
  (`FileEventSourceOpenRaceTest`, COMMITED and EARLIEST). A rotation after the second read is the ordinary live case
  above. Without file keys (Windows) the two reads cannot differ, as before.
- **Pointer files of earlier layouts** (review 2, findings 7 and 8): every release created a 1,024-byte pointer file;
  v1.1.2 and before wrote only slot 0 (the offset), leaving the epoch slot 0. A pointer file shorter than 1,024 bytes
  (not one Mongoose wrote: hand-made, or truncated by other tooling) is extended in place, keeping its offset; shorter
  than 8 bytes, it restarts at 0. A pointer with no epoch was written by v1.1.2 as a count of decoded chars, which
  after non-ASCII text is less than the byte offset and may point inside a line: if the byte before it is not `\n` or
  `\r` (or it sits between the `\r` and `\n` of one terminator), reading resumes after the next line terminator, and
  that offset is committed at once, **together with the epoch**: a start that stops before opening the file leaves the
  pointer a legacy one, aligned at the next start (review 3, item 2). The char count is never past the true byte offset of
  the processed lines, so this skips only the rest of a line already processed, never an unprocessed line; a line may be
  re-delivered. Checked for an 8-byte and a 1,024-byte legacy pointer (`aPointerFileHoldingOnlyTheOffsetIsUpgraded`,
  `anEightBytePointerAfterNonAsciiTextResumesAtALineStart`, `aV112CharCountPointerAfterNonAsciiTextResumesAtALineStart`);
  a pointer file shorter than 8 bytes, or empty, is read from 0 with a warning
  (`aPointerFileTooShortForAnOffsetReadsFromTheStart`, `anEmptyPointerFileReadsFromTheStart`). **The ambiguity is not
  resolved, only bounded**: a char count cannot be turned back into the byte offset it stood for. After non-ASCII text
  it can be several lines short of the processed bytes, and every whole line between it and the true end is delivered
  again, under the new epoch, so `Delivery.alreadyApplied` does not recognise those lines as applied
  (`aLegacyCharCountPointerCanDeliverAProcessedLineAgain`). This is at-least-once, not a correct migration. The count is
  exact only when everything before it is ASCII; otherwise an app that cannot tolerate the re-delivery must set the
  pointer itself before upgrading (the byte length of the first `count` characters as v1.1.2 decoded them) or de-duplicate those
  lines by content.
- `start()` resets the read buffer and pending line, and registers the committer for `COMMITED` only (other strategies
  have no pointer).
- **Clean stop** (review 2, finding 4): `stop()` polls acknowledgements once (enough for a source stopped on its own).
  On a server, the ordered shutdown stops the feeds *before* the processor groups drain their queues, so a group goes
  on processing after its source stopped (measured before the fix: 1,226 lines processed, pointer after line ~200). The
  shutdown therefore commits again, on the stop thread, once the queues have drained and once more when the processor
  groups have closed (`EventFlowManager.commitProcessedAfterStop`, for committers that declare
  `SourceCommitter.commitsAfterStop()`; the file source re-maps its pointer file for that write). The pointer then
  covers exactly what the slowest group processed (`FeedDeliveryCleanStopTest`, one and two groups). Items a group
  never processed (the drain timed out, the queue was dropped at close) stay uncommitted and are re-delivered at the
  next start. A crash leaves the pointer at the last acknowledged line, re-read on restart. The final commit runs only
  once every source has stopped: if the feed-stop phase runs out of its bound, it is skipped, and made when the late
  stop is joined after the processor groups close (review 3, item 5; `FeedDeliveryStopOrderTest`).
- After a reset in the middle of a run (a reopen that finds the file replaced), an acknowledgement of an item of the
  old file is never committed: the committer forgets the last acknowledged offset on the reset and commits only after
  an acknowledgement of the current epoch (review 2, finding 6; `anAcknowledgementOfTheOldFileIsNotCommittedAfterAReset`
  for a restart, `anAcknowledgementOfTheOldFileIsNotCommittedAfterAMidRunReopen` for a reopen while running).
- **Line terminators at the end of a once read** (review 3, item 6): a tailing read waits for the byte after a `\r`
  (it may be the `\n` of `\r\n`); a once read that ends with a lone `\r` takes it as the terminator of the last line,
  which is published, and reports no `unterminated` bytes.
- With `cacheEventLog`, the cached lines carry their positions; they are acknowledged once dispatched after the start
  completes.

**Commit lag (bounded).** The pointer is written on the source's own thread, at the start of each duty cycle and again
after a cycle that published lines; a cycle that committed reports work, so the agent does not idle-sleep while
acknowledgements flow. A line is therefore committed within one duty cycle of the source's agent after the reader's
ordered `dispatched` store at the end of the line's event cycle: with `SleepingMillisIdleStrategy` about 1 ms plus one
`doWork`. Measured with a real `kill -9` (`FeedDeliveryKillTest`): 1 to 5 lines behind durable state. It cannot be zero
without a second writer: committing inside the processor's cycle would make the processor thread write the source's
pointer (and, for Kafka, call a consumer that is not thread-safe). A zero gap is not needed either: two separate
durable records (the app's state file, the source's pointer) can never be written atomically, so *any* lag, in either
direction, is visible to a non-idempotent app after a crash. The general fix is the consumer-side offset of
section 5a, which makes the app's own state the authority and the source pointer only a bound on re-reading.

What a restart sees after a crash: the lines after the pointer, which includes every line read but not processed and,
for lines processed after the last acknowledgement poll (at most one duty cycle) or under a held checkpoint
(section 5), lines already processed: **duplicates, never a gap**. Measured in-process by
`FeedDeliveryServerTest.aCrashMidBurstLosesNoLine`: the pointer, sampled during a 3,000-line burst, is never ahead of
the lines handled; a "kill" mid-burst (the pointer file snapshotted, the server stopped, the snapshot restored) loses
no line, and the re-delivered lines are exactly those handled after the last commit (0 to 4 in measured runs).

## 5a. The source position of each event, for consumer-side offsets (implemented)

A stateful application cannot rely on a source pointer that is written separately from its state. It needs to know,
for each event it applies, *where in the source* the event came from, so it can store that position **in the same
atomic write as its state** and, after a restart, skip a re-delivered event it has already applied. This is the
Kafka transactional-state pattern (offsets stored with the state, not in the broker), and it makes the effect of each
line on state exactly-once whatever the commit lag.

**Mongoose side (implemented).**

- A feed whose source tracks positions (a `COMMITED` file feed; any source that publishes with
  `tryPublish(item, position)` and a committer) carries each item in its queues as a `PositionedItem(sequence,
  position, item)`. The reader unwraps it: the processor receives the bare item, as before.
- For the item's event cycle (every handler, every `@AfterEvent`, on the group's thread) the reader sets
  `DeliveryContext`; it is cleared when the cycle ends:

```java
Delivery d = DeliveryContext.current();      // null for an event without a source position
d.feed();          // "input"
d.sequence();      // this run's publish sequence (restarts at 1: never use it across a restart)
d.epoch();         // the source's position space (review F5): changes when the source is reset or replaced
d.position();      // the source's position after the item: a FileEventSource gives the Long byte offset after the line
d.positionAsLong();
d.alreadyApplied(storedEpoch, storedPosition);   // same epoch and position <= stored: already in the saved state
DeliveryContext.currentPosition();  // the position only, no allocation
```

- Also a registered service, `DeliveryContext.SERVICE_NAME` (`mongoose.deliveryContext`), for
  `@ServiceRegistered public void delivery(DeliveryContext ctx, String name)`; `ctx.delivery()` is the same as
  `current()`.
- **Epoch.** A position means something only within its source's position space. A `FileEventSource` with `COMMITED`
  keeps an epoch in its pointer file: a new one when the pointer file is created (first run, or the pointer deleted),
  and a new one, with the pointer reset to 0, when the input file is replaced (another file key, or other leading
  bytes; never a time, section 4) or truncated (shorter than the pointer). Appending to the file keeps the epoch. `EARLIEST`/`LATEST`/`ONCE_*` have no pointer; their epoch is the input file's
  identity. `alreadyApplied` is false for any other epoch, so after a reset the app re-applies (it does not silently
  skip new lines whose offsets happen to be at or below the stored one). What the app does with a new epoch is its
  decision: a fresh start should normally start from fresh state too (an app can compare `epoch()` with its stored one
  at the first event and log or clear).
- Positions are stable across restarts and increase within an epoch. A file position is the byte offset just after
  the line, the same value the pointer stores, so `pointer <= saved position` after any crash (verified below).
- Cost: one wrapper object per item of a positioned feed; nothing for other feeds.

**App / helper side (specification for `PersistentNode`; owned by the helper library).**

1. **Store the position with the state, atomically.** Each persistent node keeps, per feed it consumes,
   `lastAppliedEpoch` and `lastAppliedPosition` alongside its state, and saves both in one write of one record (the node's entry in the
   cache). The store's write must be atomic (write to a temporary file and `ATOMIC_MOVE` it into place, or an append
   to a log with a checksum per record): a `kill -9` during an in-place rewrite would otherwise leave a torn file.
   If several nodes share one file, each node's entry carries its own position; a single whole-file write per event is
   simplest.
2. **When to save.** At delivery completion: register the save with `DeliveryContext.onComplete` (section 5b) and,
   in it, read `DeliveryContext.current()` and store `d.epoch()` and `d.position()`, keyed by `Delivery.feed()`. If
   `onComplete` returns false (an event with no source position: a timer, a service callback) save the state at once
   and leave `lastAppliedPosition` unchanged. Saving in the `@AfterEvent` of the first cycle is correct only for a
   processor that never sends itself an event while handling a delivery (section 5b says why).
3. **Skip a re-delivery before applying it.** In each handler that changes state (or in one guard checked by all
   handlers of the node, before any mutation): `Delivery d = DeliveryContext.current(); if (d != null &&
   d.alreadyApplied(lastAppliedEpoch.get(d.feed()), lastAppliedPosition.get(d.feed()))) return false;` (no change, no
   propagation, no output). The
   check must come before *every* mutation and *every* output of the node for that event.
4. **Restore** `lastAppliedEpoch` and `lastAppliedPosition` with the state on start, before the feed delivers (the state load already happens
   then).
5. **Nodes without persistent state** need nothing; a node whose output feeds a persistent node is skipped implicitly
   only if the persistent node is the one that decides. If a non-persistent node emits outputs for a re-delivered
   event, those outputs are duplicated; give it the same guard (it can read the persistent node's
   `lastAppliedPosition`) if its outputs must not repeat.
6. **Outputs (sinks).** The order of a delivery is: outputs, then state save. A kill between them re-applies
   nothing (the state does not contain the line, so the line is applied once, after the restart) but repeats that
   line's outputs: at most the outputs of one line per crash. For exactly-once outputs too, write outputs keyed by
   `(feed, position)` and let the sink drop a key it has already written, or write the outputs into the same atomic
   record as the state (outbox) and publish them from there.
7. **Ordering with the commit.** Nothing else is required: the source pointer only bounds how far back the restart
   re-reads (it is never ahead of the saved position, so nothing is skipped); the stored position decides what is
   applied.

Verified by `FeedDeliveryKillTest` (a separate JVM killed with SIGKILL mid-burst, a processor that saves
`applied count + position` atomically after every line): without the guard the restart applies the lines of the commit
lag twice (state count 5,005 for 5,000 lines in one run); with it, exactly 5,000, every line in the output.

## 5b. Delivery complete: saving state once per delivery (implemented)

**Problem** (mongoose-plugins `PersistentNode`, review finding F1). A handler, or a sink it publishes to, may send the
processor another event (`onEvent`, `EventDispatcher.queueReentrantEvent`) while it handles a positioned item. A
generated processor queues that event and runs it after the current cycle's `@AfterEvent`, as a second cycle under the
same `DeliveryContext`. A node that saves `(state, position)` in the first cycle's `@AfterEvent` has stored the item's
position before the queued event ran: its guard then skips the queued event as already applied; and applying it does
not help, because a crash after the first save makes the restart skip the re-delivered item, so nothing queues the
event again. Neither Fluxtion nor the node knows when the delivery as a whole is done; the reader does.

**API.** `DeliveryContext.onComplete(Runnable)` (and `whenComplete` on the injected instance) registers a callback
for the current positioned delivery. The reader (`EventQueueToEventProcessorAgent`) runs the callbacks after the
dispatch of the item to the queue's processors returns, on the processor group's thread, with `DeliveryContext` still
entered, then advances the queue's `dispatched` counter:

```
dispatch item to the queue's processors (each drains its reentrant events)
  -> completion callbacks, in registration order          (DeliveryContext still entered)
  -> recorder.afterDispatch (RECORD)
  -> DeliveryContext.exit
  -> dispatched[q] += 1                                    (only now can the source acknowledge and commit it)
```

**Ordering guarantee.** Callbacks run after all the reentrantly queued work of the delivery (everything the processors
ran synchronously within their dispatch of the item on this thread), and before the feed can acknowledge or commit the
item. Work the delivery publishes to another feed, queue or processor group is not part of it.

**Rules.**

- *Registration outside a delivery*: when the calling thread is handling no item with a source position
  (`DeliveryContext.current()` null: another feed, a timer, a service callback, a processor driven without a Mongoose
  reader) `onComplete` keeps nothing, runs nothing and returns **false**; the caller does its work itself. Chosen over
  running at once (inside a handler, before `@AfterEvent`, which would save half a cycle) and over throwing (a node
  should not need to know which feed delivered the event).
- *Non-positioned items*: have no completion; nothing changes for them.
- *Re-registration*: the same instance, while pending, is kept once (a node may register on every cycle of the
  delivery); distinct callbacks run in registration order; a callback registered by a running callback runs in the
  same pass. At most 65,536 registrations per delivery (`MAX_COMPLETIONS_PER_DELIVERY`), so a callback that registers
  itself fails instead of spinning.
- *A failed dispatch*: the callbacks the failing processor registered, and any registered with no current processor,
  are discarded (all of them when the failing processor is not known); a retry registers them again. Callbacks of the
  processors that handled the item are kept and run (with #53 they are not given the item again). A processor whose
  retries are exhausted runs none; a generated processor quarantined for the failure runs none. A delivery that ends
  without completing (the group ends, `exit()`) runs none. `DeliveryContext.attempt()` counts the failed attempts of
  the item, so a node can tell a retry of a cycle it completed (the count grew: its effect is in memory) from a further
  cycle of the same attempt (a queued event: new work).
- *A callback that throws*: retried alone under the reader's `RetryPolicy`, the processors never given the item again
  (their cycles completed), the item **not** counted as dispatched meanwhile, so not acknowledged. When the policy gives
  up the failure is reported (ERROR), the callback skipped, the callbacks after it run, and the item counts as
  dispatched: the same rule as any failed item (section 3, *A failed item*). Holding the item instead would stall the
  feed for good on one failing save.
- *Several queues* (a broadcast feed, several groups): each queue's reader completes its own delivery of the item on
  its own thread, with the callbacks registered there; the source acknowledges the item when every queue has counted
  it (section 3), so after every group's callbacks.
- *Record and replay* (R2, R3): the callbacks run before `afterDispatch`, so anything they read through a recording
  clock belongs to the input's entry. `GroupReplayer` runs a replayed input's callbacks inside the input's replay,
  after `replayTo` and before the delivery is cleared, so a replay makes the same saves at the same point; no retry
  in a replay (a live retry is a failure, marked and not reproduced, D4), and a callback that throws stops that
  processor's replay with the reason. An input replayed without a recorded delivery has no completion, as it had none
  live.
- *ProcessingCheckpoint*: a `mark()` taken in a callback does not cover the item being completed (it is not dispatched
  yet), as for a mark taken in a handler (section 5).

**Cost.** Nothing for an item without a position; for a positioned item one thread-local read when nothing is
registered, and a reused `ArrayDeque` with one small record per registration.

**PersistentNode** (mongoose-plugins svc-cache) uses it from 1.1.3, found through method handles: its `@AfterEvent`
registers one callback per delivery and the callback writes state and position once; a queued event is applied; a crash
before the callback leaves nothing of the delivery saved and the item uncommitted, so the restart re-applies the whole
delivery. Tests: `DeliveryCompletionTest` (agent level: ordering against reentrant work and the `dispatched` counter,
order and de-duplication, no position, failure discard and retry, exhausted retries, a throwing callback retried and
skipped, two queues, `exit` discards) and `DeliveryCompletionServerTest` (on a server: after the queued event and before
the feed acknowledges the line; indexed and inline recordings replay the callbacks where they ran live).

## 5. Processing checkpoints: commit never ahead of durable state (implemented)

By default "processed" means the event cycle finished. That is the right commit point for an application that saves
its state synchronously in the cycle (the example app of section 1 saves after every event). An application that persists state
asynchronously or in batches must hold commits back until its state is durable, or a crash after the commit and
before the save loses the effect of items the source will not re-deliver.

```java
@ServiceRegistered
public void checkpoints(ProcessingCheckpointService service, String name) {
    checkpoint = service.hold("lines", "processor-agent");     // feed, processor group (agent name)
}
// on the processor thread, when snapshotting state:
ProcessingCheckpoint.Mark mark = checkpoint.mark();           // what this group's queue has finished dispatching
saveAsync(state).thenRun(() -> checkpoint.durableThrough(mark));  // any thread
```

- A hold is per (feed, processor group): the group's one queue for the feed. While held, that queue's acknowledgement
  point is `min(dispatched, durableThrough)` instead of `dispatched`. `release()` returns to the default.
- A mark belongs to the queue it was taken on (review F3): the group's queue for the feed is a binding with its own
  `dispatched` counter and its own durable mark. A resubscribe (a new queue counting from 0) starts a new binding held
  at nothing durable; the queue going away drops the binding; a mark of an old binding is ignored by `durableThrough`.
  There is **one binding per physical queue**, counted by the subscriptions that read it: a group bound to the feed by
  two callback types shares one queue, so the second subscription joins the binding instead of replacing it, and one
  of them leaving neither unbinds the checkpoint nor drops the queue's `dispatched` counter while the other still
  reads the queue (review 2, finding 5; before, a held checkpoint then wedged forever:
  `ProcessingCheckpointSharedQueueTest`). Before this fix the durable value survived the resubscribe and released the new queue's items
  before its state was durable (`ProcessingCheckpointRebindTest`). `Mark(binding, count)` replaces the plain `long`.
- `mark()` reads the queue's `dispatched` count. Inside a handler it does not count the item being handled: a state
  snapshot taken there includes it, the mark does not, so that item is re-delivered after a crash. Conservative, never
  lossy. Marks only advance (`durableThrough` is a monotonic max).
- Take the hold before the feed delivers (in `init`/`start`); items acknowledged before it stay acknowledged.
- The only new cross-thread write is the app's `durableThrough` into the hold's own `AtomicLong`, read by the source's
  poll. Nothing writes into a queue.
- Also `MongooseServer.processingCheckpoint(feed, group)` for a host.
- Tested: `FeedDeliveryServerTest.aProcessingCheckpointHoldsTheCommitUntilStateIsDurable` (processed 5, pointer 0
  while held; the mark taken after 3 commits 3; the mark after 5 commits 5; released, dispatch commits again) and
  `FeedDeliveryRecordReplayTest` (section 8).
- A group restarted as a new agent subscribes again: a new binding, so marks taken before the restart are ignored and
  the hold starts at nothing durable; take a new mark once the restored state is durable.

## 6. PAUSE: no-drop backpressure for re-readable sources (implemented)

`OverloadPolicy.PAUSE`. When a queue under PAUSE has no room, `tryPublish` commits nothing (no sequence number, no
journal entry, no queue holds it) and returns false. The source keeps the item and its read position, returns from
`doWork`, and offers the same item again on its next duty cycle. Nothing waits, so it is safe on an agent thread: no
deadlock, no head-of-line wait on the source's agent, and the global invariant of
`design-doc/backpressure-and-slow-consumer-handling.md` ("no unbounded blocking wait on a shared duty-cycle agent
thread") holds. It is the opt-in losslessness that design doc asked of `BLOCK`, delivered without blocking: a
re-readable source does not need to wait, because it can simply not read.

- **FileEventSource on a pause** keeps the line (and the unread rest of its buffer), reads nothing further from the
  file, and stays `BEHIND`. `CAUGHT_UP` is reported only once everything to the end of the file has been taken.
- **Mixed policies**: only PAUSE (and, for `tryPublish`, BLOCK) queues are checked for room; a DROP queue of the same
  feed still drops after its spin, so a per-group override can isolate a lossy subscriber
  (`overload.subscribers.<group>.policy: DROP_AFTER_SPIN`) while the others stay lossless.
- **BLOCK_WITH_DEADLINE under `tryPublish`** pauses too, instead of the downgrade to drop on an agent thread. A source
  that can re-read is strictly better off pausing than dropping; the deadline exists to bound a waiting caller, and a
  paused source holds no thread.
- **Fenced or dead group**: on a feed **without positions** (a push source) its queue does not hold the feed back;
  items for it take the drop path, counted (the containment rule of #79). On a **positioned** feed (COMMITED file) it
  holds: the source pauses while the group is fenced or dead and nothing of its backlog is committed (section 3).
- **Cache replay** (`cacheEventLog`): under PAUSE the replay at start completion stops at a full queue and resumes from
  `flushPaused()` (which a source calls at the start of each duty cycle); `tryPublish` replays the rest first, so order
  is kept. Before this, the replay dropped.
- **In-band feed status** (`feedStatusEvents: true`): a status that finds a PAUSE queue full waits for room (the host
  keeps seeing the previous state meanwhile) and is delivered before any later item
  (`anInBandStatusWaitsForRoomInAPausedQueue`). A later report replaces a waiting one. A status never overtakes cached
  items either: the status reported before the start completed waits behind a cache replay that paused, and is
  delivered once the cache is dispatched and there is room; until then the feed is not drained (`cachedPending`).
  Before, it was written into the full queue with drop semantics, so a PAUSE run lost it and counted a drop
  (independent review R6, `aCachedStartupStatusWaitsBehindTheCacheAndIsNotDropped`). The hold on completion is a flag
  of its own (`startupStatusPending`), not a property of whichever status waits: a later report that replaces the
  waiting startup status keeps the feed undrained until that report is delivered and its positions taken (independent
  delta review of PR #98, finding 2, `CachedStartupStatusReplacementTest`; before, the replacement dropped the hold). Only PAUSE queues hold a status: on a
  `DROP_AFTER_SPIN` or `BLOCK_WITH_DEADLINE` queue (even under `tryPublish`, where items pause for it) a status is still
  offered with drop-after-spin semantics ([feed-caught-up.md](feed-caught-up.md) section 9).
- **Push publishes** (`publish`, a source that cannot pause) into a full PAUSE queue fall back to `DROP_AFTER_SPIN`
  with the spin bound; the drop is counted (`dropped`) and the fallback counted as `downgraded`, warned once.
- **Health**: PAUSE is a never-drop policy: a loss on it (only via a fallback) latches like BLOCK
  (`OverloadSettings.neverDrop()`). A pause is not a loss and changes no health state.
- **Cost**: per publish, one room check per PAUSE binding under the lock the publish already takes
  (`remainingCapacity`), a `Long` box and a `long[]` per positioned item. Zero for push feeds.

What PAUSE means for other subscribers of the same feed: a slow PAUSE subscriber holds the whole feed back (Disruptor
gating by the slowest). That is the price of "lose nothing"; the per-group override above is the escape.

**Default and compatibility.** A `FileEventSource` feed that configures no overload policy now uses PAUSE
(`EventToQueuePublisher.setPauseByDefault`, set by the source; an explicit policy always wins, including an explicit
`DROP_AFTER_SPIN`). This is a behaviour change: a file feed that used to drop under a slow consumer now slows to the
consumer's pace and loses nothing; `feed.status` reports `policy: PAUSE` and `pauses`. The boot warning for feeds
without a policy now names both defaults. YAML:

```yaml
eventFeeds:
  - name: lines
    overload:
      policy: PAUSE                 # the file default; DROP_AFTER_SPIN to keep the old behaviour
      spinBoundMillis: 10           # optional: used only by a push publish's fallback
```

`OverloadPolicyConfig.pause()`, `OverloadSettings.PAUSE`. `blockDeadlineMillis` is rejected with PAUSE.

## 7. Status honesty (implemented)

`feed.status` now reports, per feed, a `Delivery` block, and a top-level `lossless`:

```json
{"settled":true,"lossless":false,"fallbackFeeds":[],"feeds":[
  {"feed":"lines","state":"CAUGHT_UP","bounded":false,"sequence":10000,"version":2,"detail":null,
   "drained":true,"settled":true,"lossless":false,"policy":"DROP_AFTER_SPIN","dropped":8571,"refused":0,
   "downgraded":0,"paused":false,"pauses":0,"acknowledged":10000,"pendingAcks":0,
   "subscribers":[{"queue":"g/lines/onEventCallBack","position":1429,"dispatched":1429,"drained":true}]}]}
```

- `dropped`, `refused`, `downgraded`: the publisher's cumulative counters (queue copies dropped; BLOCK publishes
  refused; publishes that could neither wait nor pause). `lossless` per feed = `dropped == 0 && refused == 0`; top-level
  = every feed in the report lossless. `downgraded` alone is not a loss (any loss it caused is in `dropped`).
- `policy` (the feed's effective default), `paused` (now), `pauses` (episodes), `acknowledged` (highest sequence
  committed by the source's committer; 0 for a source that tracks no positions), `pendingAcks` (the window).

- `consumers` (subscriber bindings that can consume now) and `stalled` (review F2): for a positioned feed, why it
  holds its position and cannot complete: `no live consumer` (the last subscriber removed, e.g. its only processor
  quarantined), `consumer <queue> fenced or dead`, or `a removed consumer left unprocessed items` (any removed
  consumer, whether or not others remain; section 3); null otherwise and always null for a feed without positions. **A stalled feed is not `settled`** (per feed and top level). It stays
  `lossless`: nothing is lost, the pointer still holds every unprocessed item and a restart re-delivers them. So a
  launcher waiting for `settled` does not declare a run complete whose consumer is gone; it times out with the reason
  in `stalled`, which is the truth (the run cannot complete without an operator or a restart). Making `lossless` false
  instead would misreport a recoverable stall as a loss.

- `unterminated` (review 2, finding 9): bytes after the last line terminator that a bounded source ended without
  publishing: a once-read file (`ONCE_EARLIEST`) whose last line has no newline. Such a feed is still `settled`
  (`END_OF_STREAM` is final: nothing more will ever be published, so holding `settled` back would only turn a definite
  answer into a launcher timeout) and still `lossless` (the bytes never became an item, so nothing the feed took was
  lost: the file format defines a line by its terminator, and a tailing strategy publishes the line once its
  terminator arrives). Before, only the human-readable `detail` said so; the count makes it machine-checkable, so a
  launcher that requires every byte fails the run on `unterminated > 0`. 0 for every other source.

**Decision: an explicit `lossless` flag, not `settled:false`.** Making `settled` false while a feed has drops would
turn a definite answer into a launcher timeout (drops are cumulative and never go away), hide *why* the run did not
settle, and conflate two questions: *is the run complete* (`settled`) and *is it whole* (`lossless`). With both, a
launcher stops promptly and fails loudly. The settled rule of feed-caught-up.md section 8 gains one step:

5. When `settled`: if `lossless` is false, report the run as lossy (the per-feed `dropped`/`refused` say where) and exit
   non-zero; do not compare its output as a clean run.

No consumer depends on `settled` alone yet (the starter's `BundleLifecycle` still uses the quiet period; follow-up 2
of feed-caught-up.md), so the rule can be set now.

## 8. Semantics: at-least-once, duplicates, exactly-once

**At-least-once (now, for a COMMITED file feed with PAUSE):** every line is processed at least once across any number
of crashes, provided the processor's state is durable when its event cycle ends (or is held by a checkpoint).
**At-least-once is only safe on its own for stateless or idempotent processing**: a stateful app that saves after
every event re-applies the re-delivered lines (double payments, double joins) unless it uses the consumer-side offset
of section 5a, which turns at-least-once delivery into exactly-once effect on state. The crash windows:

| Crash between | Result after restart |
| --- | --- |
| read and publish | re-read (never committed) |
| publish and processing | re-read |
| processing (state saved, sink written) and the next acknowledgement poll | **re-delivered**: a duplicate output; re-applied to state unless the app skips it by its stored position (5a) |
| acknowledgement and the pointer write | re-delivered (the write is the commit) |
| pointer write and the next line | nothing lost, nothing duplicated |

So a re-delivered line can produce output twice (a duplicate alert). Never a loss.

**Still lossy by configuration** (visible as `lossless:false` or explained in section 3): an explicit
`DROP_AFTER_SPIN` queue (including the deprecated `slowConsumerStrategy: BACKOFF`), a fenced or dead group's queue on a
feed *without positions* (on a positioned feed it holds instead, section 3), output produced off the feed threads
(feed-caught-up section 7), and state the app keeps only in memory.

**Exactly-once (out of scope; what it would need).** Either:

1. **Idempotent outputs keyed by source position**: each output carries the position of the input that caused it
   (`DeliveryContext`, section 5a, now implemented), and the sink drops a key it has already written. Needs sinks
   that can de-duplicate.
2. **Atomic commit of state + position + output** (transactional outbox): the processor writes its state, the outputs
   and the source position in one transaction (one Chronicle append, one DB transaction), and on restart the source
   resumes from the position stored *with the state*, not from its own pointer. The checkpoint hook (section 5) is the
   half of this already here: it lets the state store decide when the source may commit; exactly-once additionally
   moves the authoritative position into the state store.

Kafka's exactly-once (transactions: `sendOffsetsToTransaction`) is option 2 with Kafka as the store.

## 8a. Outputs and the audit record across a crash (proposed; review F4-audit)

Found by the audit harness: an alert written to a sink during an event cycle has no audit record until
`EventLogManager.processingComplete()` at the end of the cycle. A `kill -9` between the sink write and the audit
record leaves one alert with no audit entry; after re-delivery the line is processed again and the alert is written a
second time (now audited). Per crash: at most one cycle's outputs unaudited and duplicated. The same window exists
between outputs and the app's state save (5a, item 6). Two ways to close it:

| | Outbox: sink messages held until the cycle commits | Position-keyed idempotent sinks |
| --- | --- | --- |
| Mechanism | Sink publishes made during a cycle are buffered (per group thread) and released only after the cycle's audit record is written and the app's state is saved (an `@AfterEvent` ordering hook, or Mongoose releasing them after `processingComplete`). With a durable outbox, the buffered messages are written in the same atomic record as the state + `(epoch, position)`, and a restart re-publishes any outbox entry not yet acknowledged by the sink. | Every output carries the `(feed, epoch, position)` of the input that caused it (from `DeliveryContext`) plus an index within the cycle; the sink keeps the last key it wrote (durably, with the output) and drops a key at or before it. |
| Guarantees | No output without its audit record and state; exactly-once outputs only with the durable outbox; in-memory buffering only removes the "output before audit/state" order. | Exactly-once outputs per sink, independent of the processor; the audit/output order is unchanged (an output can still precede its audit record by one cycle, but its re-delivery is dropped, so the audit shows one output per key). |
| Cost | Latency: outputs leave at the end of the cycle (already true for most sinks in practice); memory for one cycle's outputs; a durable outbox adds a write per cycle (it can share the state write). | Each sink must store a key and compare; the key must be durable with the output (e.g. a field in the alert line, scanned on open, or a side file). Sinks that cannot (a socket, a third-party API without idempotency keys) gain nothing. |
| Who implements | Mongoose (cycle-scoped sink buffering on the group thread) + the state store for the durable variant. | Each sink (Mongoose's `FileMessageSink`, the helper's `JsonLineSink`) and the processor (tag outputs). |
| Fits | Apps whose state store is already the commit point (5a): put outputs in the same record. | File/log sinks and anything with natural keys; downstream systems that de-duplicate. |

Recommendation: an in-memory outbox by default for sinks published from a processor (release after
`processingComplete` and the `@AfterEvent` state save), which closes the audit gap at no durability cost and leaves
at most one cycle's outputs duplicated; plus position keys on outputs (`DeliveryContext`) so sinks that can
de-duplicate do, for exactly-once outputs. A durable outbox is the follow-up for apps that need exactly-once outputs
to sinks that cannot de-duplicate.

**Replay and audit (verified).** A re-delivered item is an ordinary input of the run that reads it. A recording
(RECORD mode) is per run: the restarted run's capture starts at the re-read position and holds the re-delivered items
as ordinary `Indexed` entries with that run's sequence numbers (the publisher's sequence restarts with the server);
replaying that recording reproduces that run's output. The first run's recording holds its own items, including those
it processed after the last commit. Stitching two runs' recordings into one history would show those items twice; the
audit therefore reads "processed twice, once per run", which is the truth of at-least-once. Verified by
`FeedDeliveryRecordReplayTest` (run 1 processes a..d with the state durable through b; run 2 re-delivers c, d; its
recording holds exactly 2 entries and replays to run 2's output). A dropped item (explicit DROP) is still marked
`Dropped` in the recording, as before.

**Delivery metadata is recorded and replayed (independent review R3).** What a processor reads through
`DeliveryContext` (feed, sequence, epoch, source position) is input to it, so a replay that withholds it is not
equivalent for a processor that reads it, such as one that skips positions its state has already applied. Before this
fix the live cycle read the item's delivery and the replayed cycle read null. Now the recorder takes the delivery of
each input when its dispatch begins and stores it with the input's entry (`ReplayEntry.Indexed.delivery()`,
`ReplayEntry.Inline.delivery()`, null for an input without a position); the replayer enters it for the replayed cycle,
the whole cycle, and clears it in a finally. In `CsvReplayStore` it is a `DELIVERY` line written, in the same write,
just before the input's line (the position through the store's codec). A reader from before this change refuses such a
file (an unknown entry kind) rather than replaying it without the metadata, and a `DELIVERY` line whose input line was
never written adds nothing and marks the recording incomplete, as a torn last line does: the store is read, never
appended to (`append` and `began` refuse), and `holdsRecording()` is true, so it is never reused as an empty
recording. Before, a reopened store gave the orphan delivery to the next input appended for that processor, so an
unpositioned input replayed with the interrupted input's feed, epoch and position (independent delta review of PR
#98, finding 3; `CsvReplayStoreDeliveryTest`). Verified by `FeedDeliveryReplayContextTest`: indexed and inline recordings replay the same
delivery, and a processor that restores `(epoch, position)` state and skips re-delivered lines makes the same
decisions in its replay (live: `skip c, skip d`; replay before the fix: `apply c, apply d`); and by
`CsvReplayStoreDeliveryTest`. **A recording without delivery metadata** replays with none, and says nothing about it:
equivalent for a recording made by v1.1.2 or earlier (no `DeliveryContext` existed, so the live cycles read none too),
**not** equivalent for a recording of a processor that read `DeliveryContext` under a build of this branch before the
fix (unreleased). Re-record such a stream.

## 9. Other sources (proposed; plugins are follow-ups, `mongoose-plugins` not modified)

Every re-readable source follows the same three steps: `output.acknowledgeWith(committer)` at start; publish with
`tryPublish(item, position)` from its agent thread and, on false, keep the item and stop consuming; call
`output.pollAcknowledgements()` and `output.flushPaused()` at the start of each `doWork`, and once more in `stop()`.
And `setPauseByDefault(true)` in `setEventFlowManager` if PAUSE should be its default.

| Source | Position | Commit (`SourceCommitter`) | On pause | Plugin work |
| --- | --- | --- | --- | --- |
| plugin `connector-file` | byte offset after the line | pointer write, as core | keep line, stop reading | port the core change (or extend the core class) |
| `connector-kafka` `KafkaMessageConsumer` | `(TopicPartition, offset)` of the record | `acknowledged`: `max(offset+1)` per partition into a map; `commit`: `commitSync(map)` (or `commitAsync` with a callback that logs failures); disable `enable.auto.commit` | `consumer.pause(assignment)` and keep the unpublished tail of the current `ConsumerRecords` batch; `resume` when `tryPublish` succeeds again. `poll()` must keep being called while paused (heartbeats; it returns nothing for paused partitions) | ~80 lines. On rebalance (`onPartitionsRevoked`): `pollAcknowledgements()` and `commitSync` what is acknowledged, drop pending positions for revoked partitions (their items will be re-delivered to the new owner: at-least-once). |
| JMS (no connector yet) | the `Message` | `acknowledged`: remember the last; `commit`: `message.acknowledge()`. **Caution**: `CLIENT_ACKNOWLEDGE` acknowledges every message the session has delivered, including ones published but not processed. Either use a transacted session and stop receiving while any item is unacknowledged (then `session.commit()` covers exactly the processed messages; one in flight at a time per session, so throughput needs several sessions), or a provider's individual-acknowledge mode (ActiveMQ `INDIVIDUAL_ACKNOWLEDGE`, Artemis `ActiveMQJMSConstants.INDIVIDUAL_ACKNOWLEDGE`) | stop calling `receiveNoWait`; keep the received message | new connector; prefer individual acknowledge where the broker supports it |
| `connector-chronicle` `ChronicleEventSource` | the tailer `index()` after the read | persist the index (a named tailer's own store, Chronicle's `tailer.index()` + a mapped pointer like the file source; or a named tailer with `ExcerptTailer.moveToIndex` on start) | do not `readDocument` (or `tailer.moveToIndex(previous)` to un-read); keep the item | ~40 lines |
| `connector-aeron` `AeronArchiveEventSource` | archive replay position (`image.position()` after the fragment) | persist the position; restart replays from it (`archive.startReplay(recordingId, position, ...)`) | stop polling the image (`image.poll` not called): the archive is the buffer | archive mode only; live mode has no replay: push semantics |
| core `InMemoryEventSource`, `HandlerPipe` | none | none: a pipe has nothing to re-read | push; keeps `DROP_AFTER_SPIN` (or `BLOCK_WITH_DEADLINE` for off-agent producers) | none |
| `connector-multicast` | none | none | push; unchanged | none |

## 10. Compatibility

- **Behaviour change**: a `FileEventSource` feed without an `overload` block pauses instead of dropping (section 6).
  To keep the old behaviour set `overload.policy: DROP_AFTER_SPIN`.
- **Behaviour change**: a `COMMITED` file feed writes its pointer after processing, not after reading: after a crash
  it re-delivers instead of skipping. After a clean server stop everything processed is committed, including what the
  processor groups finished after the feed stopped (section 4); items they never processed are re-delivered.
- **Behaviour change**: the pointer is the exact byte offset on disk. A pointer written by an earlier release after
  non-ASCII text was a char count, which resumed early (inside a line); it now resumes where it says. ASCII files are
  unaffected. The pointer file also holds an epoch, the input file's key and a fingerprint of its first bytes; a
  replaced or truncated input file resets the pointer to 0 (it used to stay past the end); an appended one never does.
  A pointer written before epochs keeps its position (aligned to a line start if it was inside a line, section 4) and
  gets an epoch on first use; a pointer file shorter than the current layout is extended. **Downgrade, then upgrade
  again**: once this release has written an epoch, the pointer is never re-aligned; if v1.1.2 then runs on it and
  writes a char count after non-ASCII lines, the slot-8 epoch survives, so the next upgrade trusts that count as a byte
  offset and may resume inside a line. Delete the pointer (a full re-read under a new epoch) after such a downgrade.
- **Behaviour change**: a positioned feed (COMMITED file) pauses, holding its position, while it has no live consumer
  or a consumer is fenced or dead, holds its position before what a removed consumer did not process, and is reported
  `stalled` (not settled) meanwhile.
- **Behaviour change**: an explicit `slowConsumerStrategy: BACKOFF` (deprecated) is an explicit `DROP_AFTER_SPIN`, as
  its warning says, also on a file feed (it used to resolve to the unconfigured default, which a file source turns
  into PAUSE). An explicit policy also changes the feed's queue-drop health check to the #79 overload states.
- `feed.status` JSON gains fields (`lossless`, per feed `lossless`, `policy`, `dropped`, `refused`, `downgraded`,
  `paused`, `pauses`, `acknowledged`, `pendingAcks`, `consumers`, `stalled`, `unterminated`); `FeedStatusReport` and `FeedStatusReport.Feed` gain components
  (both new on this branch, unreleased).
- New: `OverloadPolicy.PAUSE`, `OverloadSettings.PAUSE`/`pause()`/`pauses()`/`neverDrop()`,
  `OverloadPolicyConfig.pause()`; `EventToQueuePublisher.tryPublish`, `cache(item, position)`, `acknowledgeWith`,
  `pollAcknowledgements`, `pollAcknowledgementsAfterStop`, `flushPaused`, `bindAcknowledgementPoint`,
  `setPauseByDefault`, `setUnterminatedBytes`, counters; `EventFlowManager.commitProcessedAfterStop`; package
  `service.delivery` (`SourceCommitter` with `commitsAfterStop()`, `ProcessingCheckpoint`, `ProcessingCheckpointService`, registered by every
  server as `mongoose.processingCheckpoints`); `MongooseServer.processingCheckpoint`.
- A positioned feed's items travel in their queues as `PositionedItem` (the reader unwraps it; processors see the bare
  item). Code that drains a subscriber queue itself (tests) sees the wrapper; its `toString` is the item's.
- **Behaviour change**: a tailing file source notices a replaced, rotated or truncated input file while running and
  reads the new content, under a new epoch for COMMITED (section 4); it used to go on reading the old file, or nothing.
- Replay format: `ReplayEntry.Indexed` and `Inline` gain a `delivery` component (the earlier constructors remain, with
  no delivery); `CsvReplayStore` writes a `DELIVERY` line before a positioned input, which an earlier reader refuses
  (section 8a). Recordings of feeds without positions are unchanged.
- New `DeliveryContext` / `Delivery` (registered as `mongoose.deliveryContext`), with the delivery-complete hook
  `onComplete` / `whenComplete` / `attempt()` (section 5b).
- Push sources and every existing publish path are unchanged.

## 11. Tests (implemented)

- Review regressions, each failing before its fix: `connector/file/FileEventSourceDeliveryReviewTest` (5): a transient
  fence holds the pointer and pauses the feed (F1); the last subscriber removed stops reading and committing (F2); the
  pointer is the byte offset for Latin-1 bytes (F4); a publish nested in a positioned commit does not acknowledge the
  outer item; the epoch is stable across a restart and changes when the input file is replaced or truncated or the
  pointer deleted, and `alreadyApplied` is false across epochs (F5). `dispatch/ProcessingCheckpointRebindTest` (F3):
  after unsubscribe and resubscribe the new queue's items are not acknowledged until a mark of the new binding is
  durable. `dispatch/PositionedFeedStallTest` (F2 status): the last consumer gone, `consumers:0`, `stalled`, not
  settled, still lossless; a new consumer resumes reading but the pointer stays before the removed consumer's items.

- Second review regressions, each failing before its fix (the failure is in the commit message of the fix):
  `connector/file/FileEventSourcePointerTest` (7): an append that moves the file's times (creation time included, as
  Linux without birth time reports it) keeps the pointer and epoch (finding 1); a new file at the path and a file
  rewritten in place with other leading bytes reset it; an acknowledgement of the old file is never committed after a
  reset (finding 6); an 8-byte pointer file is upgraded (finding 7); a v1.1.2 char-count pointer inside a line resumes
  at the next line start, one at a line start resumes there (finding 8).
  `connector/file/FileEventSourceConsumerRemovalTest` (3): one of two consumers removed with a backlog does not commit
  it, the feed reports `stalled`, and the hold ends once the removed reader's count covers its items (finding 2); a
  consumer removed with nothing unprocessed holds nothing; the retained point of a removed last consumer survives a
  later unsubscribe (finding 3). `dispatch/ProcessingCheckpointSharedQueueTest` (2): a held checkpoint on a queue
  shared by two callback bindings releases after one leaves, and a second binding keeps the durable mark (finding 5).
  `service/delivery/FeedDeliveryCleanStopTest` (2): a clean server stop with one and with two groups commits exactly
  what the slowest group processed (finding 4). `OverloadPolicyConfigTest`: `BACKOFF` is an explicit
  `DROP_AFTER_SPIN`, also on a file feed (finding 9). `FileEventSourceFeedStatusTest.anUnterminatedLastLineOfAOnceReadIsCounted`
  and `FeedStatusServerTest.aBoundedFileEndsAndASilentSourceIsAFallbackFeed`: `unterminated` (finding 9). The Linux
  behaviour of finding 1 was also checked outside the suite, in Docker (Temurin 21.0.1 and 21.0.7): an append and a
  restart re-deliver only the appended line; an in-place rewrite resets the pointer.
- Independent review of 9567d6d (R1 to R6), each failing before its fix on bf73ed9 unless noted:
  `dispatch/PositionedCacheNestingTest` (4, R2): a `tryPublish` from a positioned cache's mapper or journal codec is
  told to retry, a push publish from it follows the outer item, a deferred positioned cache keeps its position.
  `connector/file/FileEventSourceIndependentReviewTest` (13): R1, the reviewer's later-unsubscribe case (already fixed
  by 606db9f); R4, a file replaced, truncated, or rotated while tailed is read under a new epoch, an old-file
  acknowledgement after a live replacement is not committed, a commit after a replacement never fingerprints the new
  file, an `EARLIEST` tail re-reads a truncated file, a partial line is read once across a reopen; R5, 8-byte non-ASCII,
  short and empty pointer files, and the explicit legacy re-delivery (already fixed by 2d0597a, so passing before);
  R6, the cached startup status waits behind the cache and is not dropped.
  R3: `service/delivery/FeedDeliveryReplayContextTest` (3, each failing before) and
  `replay/CsvReplayStoreDeliveryTest`, of whose first three cases only `aDeliveryIsReadBackOntoItsInput` failed
  before: `aDeliveryWithNoInputAfterItAddsNothing` and `aStoreWrittenWithoutDeliveriesReadsNone` are compatibility
  checks that pass on the predecessor too (the delta review of PR #98 ran each removal control and noted this). The
  orphan case now asserts that its fixture holds a `DELIVERY` row before truncating it, and two cases of the delta
  review's finding 3 (append after an orphan row) fail before that fix.
- Independent delta review of PR #98 (bf73ed9..cb97cfd), each failing on fc1778a before its fix:
  `connector/file/FileEventSourceOpenRaceTest` (2, finding 1), `dispatch/CachedStartupStatusReplacementTest` (1 of 2,
  finding 2; the other checks the delivery order and passes before too), `replay/CsvReplayStoreDeliveryTest`
  `anInterruptedDeliveryCannotAttachToALaterUnpositionedInput` and `aStoreWithAnOrphanDeliveryRowIsReadButNeverAppendedTo`
  (finding 3).
- Third review regressions, each failing before its fix: `FileEventSourcePointerTest`
  `aHeaderOnlyFirstOpenThenAnInPlaceRewriteWithTheSameHeaderIsAReplacement` (item 1),
  `aV112PointerIsAlignedEvenIfTheFirstStartStopsBeforeOpeningTheFile` (item 2),
  `theSameContentUnderANewFileKeyKeepsThePointer` (item 4; `aNewFileWithOtherContentAtTheSamePathIsAReplacement` keeps a
  new inode with other content a replacement), `anAcknowledgementOfTheOldFileIsNotCommittedAfterAMidRunReopen` (item 7:
  passes before and after, and fails if the reset path stops forgetting the acknowledged offset),
  `aV112PointerBetweenCrAndLfResumesAfterTheTerminator`; `ProcessingCheckpointSharedQueueTest`
  `aRepeatedDeregistrationDoesNotUnbindTheOtherBindingOfTheQueue` (item 3); `FeedDeliveryStopOrderTest` (item 5);
  `FileEventSourceFeedStatusTest.aOnceReadLastLineEndedByALoneCrIsPublished` (item 6).

- Delivery-complete hook (section 5b): `dutycycle/DeliveryCompletionTest` (9) and
  `service/delivery/DeliveryCompletionServerTest` (3). With the API but not the reader and replayer change, 11 of the 12
  failed on the missing completion; the twelfth failed on an expectation error in the test, since corrected.

- `service/delivery/FeedDeliveryKillTest` (2): a real crash. The server runs in a separate JVM
  (`KillChildServer`: COMMITED file feed, default PAUSE, a processor that appends each line to an unbuffered output and
  saves `applied position` atomically after every line); 2,000 lines are applied, a 3,000-line burst is appended in one
  write, the JVM is killed with SIGKILL 0.1 s later. Asserts the memory-mapped pointer is never ahead of the saved
  position; after a restart every line is in the output; without the guard, state count = 5,000 + duplicates; with
  `DeliveryContext` de-duplication exactly 5,000 and at most one duplicate output.

- `connector/file/FileEventSourceDeliveryTest` (10): read-but-not-dispatched lines are not committed and are
  re-delivered after a crash (with and without `cacheEventLog`); a clean stop commits only processed lines; the pointer
  follows the slowest of three queues, forward only, exactly at the lowest dispatched line; a removed subscriber holds
  it back only for what it did not process; explicit DROP drops, counts and commits past the drop; PAUSE with 10,000 lines into a 1,024 queue
  and a slow consumer thread: zero drops, every line once and in order, the source paused (with and without
  `cacheEventLog`); an in-band status waits for room; the pointer counts UTF-8 bytes.
- `service/delivery/FeedDeliveryServerTest` (4), on a booted server: default config, slow processor, 10,000-line file:
  `settled` never true before the last line is processed, `dropped 0`, `lossless`, `policy PAUSE`, pointer at the end;
  explicit `DROP_AFTER_SPIN`: settles with `dropped > 0`, `lossless:false` (report and JSON), processed + dropped =
  10,000; crash mid-burst: pointer never ahead of processing, no line lost, duplicates counted; processing checkpoint.
- `service/delivery/FeedDeliveryRecordReplayTest` (1): re-delivered items in the restarted run's recording, replayed.
- Updated: `FileEventSourceFeedStatusTest.committedResumesAndIsCaughtUpAtItsPointer` (the first run now processes its
  lines before stopping; unprocessed ones would be re-delivered) and the `feed.status --json` assertion.

## 12. Follow-ups

0. **Priority, before COMMITED feeds are relied on with dynamic subscriptions**: an operator release of a held
   position, `delivery.release <feed> [<queue>]` (open question 1). A removed consumer (including a voluntary
   unsubscribe) or a dead group holds a positioned feed for the rest of the run (section 3), and today only a restart
   ends the hold.
1. Plugins (section 9): `connector-kafka` (commit + pause/resume + rebalance), `connector-chronicle`,
   `connector-file`, `connector-aeron` archive mode; a JMS connector with individual acknowledge.
2. Starter `BundleLifecycle`: the settled rule with the `lossless` step (section 7).
3. (Done: section 5a, `DeliveryContext`.) Exposing the current item's source position to processors and sinks, for
   idempotent sinks (exactly-once option 1), is implemented; position-keyed de-duplication in the core sinks remains
   (section 8a).
4. A `PAUSE` gauge and pause-duration counter in the counters service, and a health check "paused longer than N"
   (a wedged PAUSE subscriber stalls the feed silently today, visible only in `feed.status`).
5. Fold `BLOCK_WITH_DEADLINE`'s agent-thread downgrade into PAUSE for push sources that can buffer (in-memory: keep in
   its staging queue instead of draining).

## 13. Open questions

1. (Resolved by review F1/F2 and review 2: a fenced or dead group holds acknowledgement and pauses a positioned feed;
   a removed consumer holds what it did not process.) Should such a hold be released by an operator command
   (accepting the loss of the backlog), e.g. `overload.clear`-style `delivery.release <feed> <group>`? Until then a
   held feed is reported `stalled`, and a restart re-delivers. Recorded as priority follow-up 0 (section 12).
2. Should PAUSE be the default for every feed whose source declares itself re-readable (an `EventSource` capability
   flag), rather than a per-source call? Today only `FileEventSource` sets it.
3. (Resolved by review F3: marks belong to a binding; a resubscribe starts a new one.)
4. The window cap (65,536) is a static: per-feed configuration, or derive it from the queues' capacity?
