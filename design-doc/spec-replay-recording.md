# Spec: record and replay a processor's inputs in Mongoose

**Status**: r5, 2026-09-29. Implemented and tested on `spike/replay-at-dispatch` (§3a); not reviewed, not for merge as is. Background, and the evidence each
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
same. Controls: `python3 design-doc/replay_controls.py`. It runs each named test unmutated first (all green),
then **20 of 20 controls caught**, each by a named assertion, with every file restored byte-identically.

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

**A correction to the record.** Commit `b6565b5` says "full suite 232/0/0/9". The full run made just before that
commit had **one failure**: `ObjectPoolServerIntegrationTest#testServerNamedEvent_tryWithResources`, a pool
`availableCount` of 2 where 1 was expected (one message returned to its pool twice). It was committed anyway, which was
a mistake. The test then passed six runs out of six on its own, the next full run was 232/0/0/9, and Mongoose CI at
`b6565b5` succeeded. None of the replay changes runs in that test (replay is off: no journal, no recorder), so it looks
like a pre-existing race in pooled named-event dispatch. That is unproven: it is intermittent and was not reproduced.

Still open: outputs muted during a group replay; a durable journal and store; direct exported-service calls (§5).

## 3b. Performance with replay off (2026-09-29)

`src/test/java/.../benchmark/dispatch/DispatchPathJmh`: a publish through `EventToQueuePublisher` onto a queue, then
`EventQueueToEventProcessorAgent.doWork` into a processor through the onEvent strategy. It runs single-threaded, with
a pre-allocated payload and a handler that does nothing. JMH 1.37, JDK 21.0.9, 3 forks × 10 × 1 s, `-prof gc`. The
same benchmark file runs on `main` (`3821b62`, whose `src/main` is `origin/main`) and on this branch, in alternating
rounds.

| path | `main` | this branch | allocated / op (both) |
|---|---|---|---|
| NOWRAP (default feed) | 23.6-24.1 ns | 24.8-24.9 ns | **0.0002 B**: zero GC, 0 collections |
| NAMED_EVENT | 40.8-41.8 ns | 42.0-42.1 ns | 48 B: the existing `NamedFeedEventImpl` per publish, not new |

Attribution (NOWRAP, a clean round):

| build | ns/op | added |
|---|---|---|
| `main` | 24.14 | — |
| only the publisher change (sequence numbers carried, the journal hook, the cached-seq fix) | 24.26 | **+0.12** |
| only the queue agent change (the recording hook) | 24.67 | +0.5 |
| both | 24.85 | +0.7 |

- **Sequence numbers cost about 0.1 ns and allocate nothing.**
- **The recording hook costs about 0.5 ns on the replay-off path**, even with every use behind one
  `recorder != null` check: the loop's shape changed. If that matters, a separate recording agent class, made only in
  RECORD mode, would leave the off path as it was, at the cost of a second copy of the dispatch loop (an owner
  decision).
- **Zero GC holds** for the no-replay path.

## 3c. The sample durable journal and store (CSV)

`CsvEventJournal` is one line per journalled item, `source,seq,base64(item)`. `CsvReplayStore` is one line per entry,
`processor,kind,source,seq,payload,reads`. Both are appended and flushed per line, and read back whole when opened
again. `CsvDurableReplayTest` records a run to the two files, closes them, reopens them as new instances from disk (as
another process would), and replays: the processor does what it did. They are samples: the index is in memory, there
is no fsync, and there is one file for every source. A production journal (Chronicle) indexes by offset, rolls, and
retains by policy.

## 3d. Admin commands and the event cycle (validated 2026-09-29)

**They do not run in an event cycle yet.** `AdminCommandAuditTest`, through a real server, shows that a processor-owned
command runs on the processor's thread, changes node state, and opens **no** event cycle: the processor's `onEvent`
count is unchanged, and the command's code runs outside any cycle. That is the cause the proposal
(`origin/proposal/admin-commands-in-event-cycle`) describes: `AdminCommandInvoker` calls the lambda directly. The
consequences (no audit record, `auditLog` lines spliced into the next record in a generated processor, no dirty
flags, nothing downstream triggered) are the proposal's live evidence. They are not reproduced here: a hand-written
`DefaultEventProcessor` has no `EventLogManager`, and Mongoose's tests have no generated processor.

The proposal covers delivery in an event cycle, and what each option gives:

| option | where | audit record | `auditLog` lands in it | dirty flags, downstream triggers |
|---|---|---|---|---|
| A: signal-routed command, `onEvent(Signal("admin:<name>", request))` | Mongoose only | yes | yes | **yes**: a full cycle |
| B: `DataFlow.runInEventCycle(description, lambda)` | Fluxtion runtime + Mongoose | yes | yes | **no** (the proposal says so) |
| C: an out-of-cycle `auditLog` becomes its own record | Fluxtion runtime | a safety net | — | — |

**Only A puts a command fully in the event cycle, and it needs no Fluxtion change.** It is a new registration API; an
existing lambda command is not converted. The proposal does not cover replay; under A the recorder still records
`{name, args}` at the admin queue, and the replay rebuilds the command, which becomes the same `Signal`.

**The requirement (owner, 2026-09-29):** a processor's admin command runs in its event cycle and audit-logs like any
node. Admin commands are a Mongoose concept; Fluxtion is not given an admin API (improving Fluxtion's general event
cycle is allowed). Nothing found argues against the requirement:
- a command already blocks its processor's thread;
- a queued command never lands mid-cycle;
- a read-only command's audit record is part of the operator trail;
- replay gains an ordinary input;
- server-level commands touch no processor, so they are out of scope.

The one care point is a handler that throws: it is answered as an error, but it leaves the processor with its
`processing` flag set (finding 2), which is Fluxtion's to fix generally.

**Option A, spiked** (`registerSignalCommand(name)`):
- `AdminCommandInvoker` delivers a signal-routed command as `processor.onEvent(new Signal<>("admin:" + name, request))`;
  the request carries the arguments and the reply channel.
- A node handles it with a filtered signal handler and replies through `request.getOutput()`.
- A command no handler replies to is answered with an error. A throwing handler is answered with the exception.
- Registering one outside a processor is refused.

`SignalAdminCommandTest`, 3/0/0/0, through a real server:
- the command opens exactly one event cycle, and its handler runs inside it and replies;
- an unanswered command is an error;
- it is recorded as `AdminInvoked`, and replays as the same signal cycle at the same point and instant.

Controls: `A-a-signal-command-runs-in-an-event-cycle`, `A-an-unanswered-command-is-an-error`,
`A-each-invocation-keeps-its-routing`.

**Not shown, and why:** the audit record and propagation in a *generated* processor. Every Fluxtion graph build, the
interpreter included, runs in the generator: the cloud service with an API key (Fluxtion `claude.txt`, "There is no
generate-without-a-generator path"). A hand-written `DefaultEventProcessor` has no `EventLogManager`. Both follow from
`onEvent` by construction, but they are unmeasured. Showing them needs a generated processor with a
`@OnEventHandler(filterString = "admin:<name>")` node, built with the owner's generator.

## 3e. One processor, several in one agent, several agents: what holds, and what is needed

**One processor: supported (R1-R7).** Everything Mongoose delivers to it (feeds, typed service calls, timers, admin
commands) is recorded in its order at the instants it read, and replayed.

**The limits, for every case** (not solved by more processors):
- Hidden inputs inside nodes: randomness, iteration order, file, database or network reads, values read back from
  injected services. They are detected by divergence, not supplied.
- Direct calls from code holding a processor (`registeredProcessors()`), outside Mongoose's paths.
- After a failure the processor has stopped (the `processing` wedge, §3a finding 2); the stream ends there.

**Needed even for one processor, before a replay runs anywhere real:**
- **L1: disconnect live inputs.** In REPLAY mode, a replayed processor's feeds are still subscribed. The tests publish
  nothing, but a deployment's sources (Kafka, files) would. The group must not deliver live queue items to a processor
  it is replaying.
- **L2: mute outputs.** A replayed processor's sinks and publications still go out. In a real deployment that is
  a second copy of real side effects (orders, messages). They must be captured for comparison, never delivered.

**Several processors in one agent group:**
- Recording is already right. The group runs them on one thread; each has its own stream; one queue item fanned out
  to several is one entry in each (an index to the same journal item).
- They interact only through queues (a publication that reaches another's feed is recorded as that one's input),
  **or through a shared mutable service or static state**, which is a hidden channel: a read of it is an input no
  replay supplies.
- Replaying one of them: supported, given L1 and L2.
- Replaying several together, each from its own stream: needs **L2**, because one processor's replayed output
  would otherwise reach another, whose stream already holds it, and it would be handled twice. With L2, their
  streams are independent: no order across processors is needed.

**Several agent groups:**
- Each group records its own processors on its own thread; nothing orders two groups, and nothing needs to. A
  cross-group publication is a queued input of the receiver, recorded where the receiver handled it.
- Replaying across groups is the same as within one: independent per-processor replays, with L1 and L2.
- **Not supported, and not proposed:** a chained re-simulation, where one replayed processor's live output drives
  another live one. It would need a deterministic order across threads that Mongoose does not have, and it adds
  nothing a per-processor replay does not already check.

**Decisions needed:**
- D7: a replay is per processor, against its own recorded inputs; a multi-processor replay is N of them, never a
  chained re-simulation.
- D8: in REPLAY mode a replayed processor gets no live inputs (L1) and delivers no outputs (L2); its outputs are
  captured for comparison.
- D9: shared mutable services and static state between processors are outside the claim, like any hidden input.
- D10: admin commands in the event cycle through option A (Mongoose only), with B and C in Fluxtion as the safety net
  for lambda commands.

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
