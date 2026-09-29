# Spec: record and replay a processor's inputs in Mongoose

**Status**: r3, 2026-09-29. Implemented and tested on `spike/replay-at-dispatch` (§3a); not reviewed, not for merge as is. Background, and the evidence each
decision rests on: [`replay-at-dispatch-spike.md`](replay-at-dispatch-spike.md).

## 1. Goal, and the boundary of the claim

Record every input Mongoose delivers to a processor, so that the run can be replayed into the same build and processor,
with the same config, and the processor does exactly what it did, at the same instants.

**The claim is bounded to what crosses Mongoose's boundary into a processor, and the processor's clock.** An input a
node obtains itself is not supplied by a replay: a random value, the iteration order of an unordered
collection, a file, database or network read, or a value read back from an injected service (scheduler time, pooled
objects, controller snapshots). A replay detects such a read; it does not reproduce it: the replayed audit log diverges
at the first one. Also out of scope: a retried dispatch (a failure: determinism is off from that point, and it is marked,
not reproduced) and calls made from outside Mongoose's paths (code holding a processor from `registeredProcessors()`).

## 2. Model

- **One ordered stream of entries per processor.** Every Mongoose path into a processor runs on its group's agent
  thread, after a queue (the one exception, `audit.start`/`audit.stop`, is fixed by R7). So recording at those points
  gives the processor's input order across all its sources, with no locking.
- **An entry is an index or an event:**
  - `Indexed{source, seq, instant}`: an input from a journalled feed; the event is in the feed's journal.
  - `Inline{source, event, instant}`: an input from a feed with no journal.
  - `TimerFired{seq, instant}`: a timer the processor scheduled fired.
  - `AdminInvoked{command, args, instant}`: a processor-owned admin command ran.
  - `Failed{source, description}`: a dispatch threw. The stream is not reproducible past it.
- **The callback is configuration.** An entry names its source; a replay boots the same config, and delivers the entry
  through the strategy that config gives that source for this processor's group (an `onEvent`, a typed service call, an
  admin invoker).
- **The instants are the processor's own.** The processor's `Clock` auditor reads its clock strategy when an input
  arrives, before any node runs, and again for any event the graph raises during the cycle; a node may read it too. A
  recording clock stays live, is armed just before a dispatch, and records every read until the dispatch returns.
  Production reads are unchanged. A replay plays each entry's reads back in order.
- **Serialisation is configuration.** A journalled feed has a codec; the journal stores each published item once,
  encoded, after the publisher's data mapper, before its pooled object is returned.

## 3. Work items

Each item has an acceptance test through a real `MongooseServer` unless stated, and a witness that fails when the
behaviour is removed.

| id | item | touches | acceptance |
|---|---|---|---|
| R1 | **Config**: `MongooseServerConfig.replay(ReplayConfig)`: mode `OFF` / `RECORD` / `REPLAY`, the recorded processors, the journalled feeds and their codecs, the journal, the entry store | `config`, new `replay` package | OFF is the default and changes nothing: the existing suite stays green |
| R2 | **Record at dispatch**: `EventQueueToEventProcessorAgent` knows its source; in RECORD it arms each target's recording clock, dispatches, and appends one entry per recorded target; a throwing dispatch appends `Failed` | `dutycycle`, `dispatch/EventFlowManager` | inputs from two sources recorded in the processor's order, each at the `processTime` the processor read; a graph-raised event of an input's type is not recorded |
| R3 | **Ids on every queue item**: a journalled feed's publisher encodes each item into the journal and carries its sequence number to the queue (a `NamedFeedEvent` already does; a `*_NOWRAP` feed wraps it in a `JournalledItem` the agent unwraps, so the processor still receives the bare item) | `dispatch/EventToQueuePublisher`, `EventQueueToEventProcessorAgent` | a journalled NOWRAP feed is recorded as indexes; the processor receives exactly what it received unjournalled |
| R4 | **Timers**: a scheduler factory; RECORD numbers each schedule call per processor and appends `TimerFired` when one fires; REPLAY never fires by itself | `MongooseServer.addEventProcessor`, `service/scheduler` | a timeout firing between two inputs is recorded between them, and replays there |
| R5 | **Replay driver**: in REPLAY each group drains one ordered stream per processor, delivering each entry to that processor alone, through its source's configured strategy, at its instant; indexes are joined with the journal | `dutycycle/ComposingEventProcessorAgent`, `dispatch` (`processEventFor`) | feed events, typed service calls, timers and admin commands replay in the processor's order and the processor does what it did; a stream replayed per source instead does not |
| R6 | **Admin commands**: a processor-owned command is recorded as its name and args; replayed by rebuilding it from the command the replayed processor registered, with stub replies | `service/admin/impl` | a command changing processor state between two inputs replays at that point |
| R7 | **mongoose#46**: `audit.start`/`audit.stop` change the audit sink on the processor's agent thread, and reply once it has | `internal/ChronicleAuditCaptureService`, `ComposingEventProcessorAgent` | the sink is installed on the group's thread, not the caller's |

## 3a. Results (spiked 2026-09-29, `spike/replay-at-dispatch`)

All seven items are implemented, in `src/main`, behind `ReplayConfig` (off by default). Full suite: **232 / 0 / 0 / 9**
(total / failures / errors / skips); the baseline before this work was 223 / 0 / 0 / 9, and the nine skips are the
same. Controls: `python3 design-doc/replay_controls.py`. It runs each of the nine named tests unmutated first (all green),
then **15 of 15 controls caught**, each by a named assertion, with every file restored byte-identically.

| id | named test | controls |
|---|---|---|
| R1 | `ReplayRecordingAcceptanceTest#R1_offChangesNothing`, and the existing suite unchanged | — |
| R2 | `#R2_R3_R5_twoSourcesAndTheGraphsOwnEvent_replayInTheProcessorsOrder`: two sources, a graph-raised event never recorded, each instant the processor's own read | `R2-arms-the-processors-clock`, `R2-graph-raised-events-never-pass-dispatch` |
| R3 | `JournalSequenceTest` (both), and the index entries above | `R3-journalled-nowrap-carries-its-seq`, `R3-cached-items-carry-their-own-seq`, `R3-journalled-once` |
| R4 | `#R4_aTimeoutFiringBetweenInputs_replaysThere`, through a real server's scheduler | `R4-records-timer-firings`, `R4-replay-never-fires-by-itself` |
| R5 | the R2 test's replay (on a clock that ticks on every read) and its per-source witness; `TypedCallReplayAcceptanceTest` | `R5-pins-the-entry-instant`, `R5-plays-every-read-of-the-cycle`, `R5-delivers-to-the-processor-alone`, `R5-a-typed-call-replays-through-the-configured-strategy` |
| R6 | `#R6_anAdminCommandBetweenInputs_replaysThere`: the command rebuilt from the replayed processor's registration, its reply collected | `R6-records-an-admin-command-by-its-args` |
| D4 | `#D4_aFailedDispatchIsMarked_andTheReplayStopsThere` | `D4-marks-a-failed-dispatch` |
| R7 | `AuditSinkOnAgentThreadTest` (both): the sink installed and restored on the group's thread, never the caller's | `R7-the-server-passes-the-groups-thread`, `R7-the-sink-changes-on-the-agent-thread` |

**Found on the way:**
1. **A late subscriber's cached catch-up carried the wrong sequence number.** `dispatchCachedEventLog` sent every cached
   item with the publisher's current number, not its own. Fixed: each item carries its own number
   (`R3-cached-items-carry-their-own-seq`).
2. **One exception wedges a processor.** `DefaultEventProcessor.onEvent`, and generated processors (runtime 1.0.16),
   set `processing = true` and clear it with no `try`/`finally`. A node that throws leaves it set, and every later
   event is queued as re-entrant and never processed. Mongoose's retry then "succeeds" by queuing. So after a failure
   the live processor has stopped, not just lost determinism. Recorded, not fixed: it is a Fluxtion change.
3. **Every clock read of an input's cycle is an input, not only the first** (found by Mongoose CI). A graph-raised
   event is dispatched re-entrantly inside its input's `onEvent`, and the processor's `Clock` reads the live clock again
   for it (`DefaultEventProcessor` does not set `shareReading`). On CI that second read fell a millisecond after the
   first, and a replay pinned to one instant differed. So the recording clock records every read between arming and
   capture, and the replay clock plays them back in order. That also reproduces a node's own mid-cycle
   `getWallClockTime()`, which §1 had listed as out of reach. The regression ticks the live clock on every read, so it
   does not depend on timing (`R5-plays-every-read-of-the-cycle`).
4. **A replay that cannot deliver an entry must not take the server down.** An exception in the group agent ended the
   test JVM. The driver now stops that processor's replay and says why (`GroupReplayer.stopped`).

Still open: outputs muted during a group replay; a durable journal and store; direct exported-service calls (§5).

## 4. Decisions

| id | decision | by |
|---|---|---|
| D1 | record at dispatch, not inside the processor: graph-raised events never pass it | owner, 2026-09-29 |
| D2 | the entry names its source; the callback comes from config | owner, 2026-09-29 |
| D3 | the clock stays live in production; the recorded instant is the processor's own read | spike, 2026-09-29 |
| D4 | a retry is a failure: marked, not reproduced | owner, 2026-09-29 |
| D5 | serialisation is configuration, at the feeds | owner, 2026-09-29 |
| D6 | replay is one ordered stream per processor, delivered to that processor alone | owner, 2026-09-29 |

## 5. Open

- **Outputs during a replay.** A replayed processor's publications must not reach another processor, whose own
  recording already holds them. Replaying one processor at a time avoids it; a group replay needs outputs muted.
- **Durable journal and store.** The spike's are in memory. A Chronicle journal and store, retention, and the
  evidence bundle's reference into a remote journal (pinned by digest) are the next step.
- **Direct exported-service calls** from code holding a processor: recordable only at Fluxtion's `beforeServiceCall`,
  with the arguments (a Fluxtion change).
