# Spec: record and replay a processor's inputs in Mongoose

**Status**: r7, 2026-09-29. Implemented and tested on `feat/replay-at-dispatch` (PR #47) and, for admin commands in the
event cycle (§3d), `feat/admin-commands-in-cycle` (PR #48); each reviewed. #47's findings are dispositioned in §3f, each
fix with a regression that failed first. Background, and the evidence each
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
then **24 of 24 controls caught**, each by a named assertion, with every file restored byte-identically.

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

**Re-measured after the #47 review fixes** (2026-09-29, the same benchmark file on `origin/main` and on the PR, alternating,
`-f 1 -wi 3 -i 5 -prof gc`, short runs):

| path | `main` | PR #47 | allocated / op (both) |
|---|---|---|---|
| NOWRAP (default feed) | 24.54, 24.49 ns | 24.90, 24.95 ns | **0 B** |
| NAMED_EVENT | 41.44, 41.51 ns | 42.07, 41.28 ns | 48 B, the existing wrapper |

- **Replay off costs about 0.4 ns per item on the default feed, and allocates nothing.** On the named-event feed the
  difference is within the runs' noise.
- An earlier split of the cost between the publisher (+0.12 ns) and the agent (+0.5 ns) is withdrawn: it came from
  partial builds that are not committed, and the review could not reproduce it; the difference is below a short run's
  error. The benchmark is reproducible: copy `DispatchPathJmh.java` onto `main`, `mvn test-compile`, run
  `org.openjdk.jmh.Main DispatchPathJmh -prof gc` on the test classpath.
- Recording itself allocates (entries, reads); only replay OFF is zero-allocation.
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

**Proved on a generated processor** (owner approved the generator, 2026-09-29). `AlarmProcessor` was generated by
the hosted generator (builder 1.0.71, runtime 1.0.16; `design-doc/admin-gen`) from `AlarmNodes`, with audit logging
on. `AlarmMonitor` raises an alarm, handles `admin:alarm.reset` with an ordinary filtered signal handler, and also
registers a lambda command. `AlarmPublisher` is downstream of it. The source is committed without the generator's
copyright header, and a test fails if it returns.

`GeneratedAdminAuditTest`, through a real server capturing the audit log:
- **The signal command has its own record**:
  ```
  event: Signal
  eventFilter: admin:alarm.reset
  nodeLogs:
      - alarmMonitor: { reset: true, resetBy: [DEMO-operator]}
      - alarmPublisher: { published: false, changes: 2}
  ```
  The node's `auditLog` writes are in it, and **the change propagated**: the downstream node fired in the same cycle.
  Exactly one record names it, and every record is well formed.
- **A lambda command still works** (it replies). The invoker now brackets it with the processor's own audit calls, found
  by name: `clock.eventReceived` / `eventLogger.eventReceived(AdminCommandEvent)` before it, and
  `processingComplete()` on both after it. So it has a record of its own, `event: AdminCommandEvent`,
  `eventToString: AdminCommandEvent[command=alarm.lambda, args=[DEMO-operator]]`. Its `auditLog` line is in it, at
  the command's own instant, and the next record is clean: the mangled log is gone. Its change does not propagate,
  which is wanted (owner: an admin command redispatches if it needs a reaction).

Controls: `A-the-generated-processor-audits-the-command`, `A-the-generated-source-stays-publishable`,
`A-a-lambda-command-is-bracketed-by-an-audit-record`, `A-a-lambda-record-carries-the-commands-instant`.

**The bracket is an interim, and unsafe for a command that redispatches** (owner, 2026-09-29: "we don't get the
queued dispatch or any other event mechanics"). The bracket does not set the processor's private `processing` flag.
So an event the command raises is dispatched at once, as a nested cycle, while the command's record is still open.
That nested record corrupts the log, and the queued-callback dispatch never runs.

**The proper form is a general Fluxtion trigger, not an admin API.** It runs a supplied action as an event cycle of the
processor, with a supplied event as its audit context:
`DataFlow.runInEventCycle(Object auditEvent, Runnable action)`, with a default of `action.run()` for older
processors. A generated processor implements it with the boundary it already has for exported service calls:
1. `auditEvent(auditEvent)`: every auditor sees it.
2. `processing = true`.
3. The action runs.
4. `afterEvent()`, then `dispatchQueuedCallbacks()`, so anything the action redispatched runs after it, in order.
5. `processing = false`, in a `finally`.

The event is not dispatched to any node, and nothing is marked dirty. Mongoose's invoker becomes
`processor.runInEventCycle(new AdminCommandEvent(name, args), command::executeCommand)`: the trigger is the command,
carrying all its state.

**Spiked 2026-09-29, both Fluxtion repos, branch `spike/run-in-event-cycle`, not pushed.**
- Runtime (`b8e97b4`): the `DataFlow` default plus the `DefaultEventProcessor` override. `RunInEventCycleTest` 5/0/0/0,
  suite 274/0/0/1, three controls caught.
- Compiler (`632d99a1`): the method is added in `javaTemplate.vsl` and in `InMemoryEventProcessor`, with **no change to the
  generator model**. `RunInEventCycleTest` 15/0/0/0 across the five targets (three compiled, serialised, interpreted).
  Removing the template override fails the compiled targets, and removing the interpreter's fails the interpreted one.
  The only change to the pre-split goldens is the added method.
- The audit log it produces: the command's record carries the node's line and nothing from downstream. The event it
  raised follows as its own record, with the publisher firing.
- Mongoose (`a91a2fd`): the invoker uses `runInEventCycle` when the processor's class overrides it, and keeps the
  bracket otherwise. `RunInEventCycleAdminTest` passes on 1.0.17-SNAPSHOT and skips on 1.0.15.

**Why a default method cannot run the cycle:** it sees only `DataFlow`'s public API. The auditor fan-out, the
`processing` flag, `afterEvent()` and the callback queue are private to each implementation. Exposing them as public
begin and end calls would let any caller leave a cycle half-open. So the default runs the action without a cycle, and
each implementation overrides it. A template that wants the path closed makes the override throw.

Security of such a trigger:
1. It grants no new privilege in-process. Its caller holds the `DataFlow`, and can already call `onEvent`, exported
   services and nodes. The boundary that matters is Mongoose's: the admin transport sends a registered command name
   and arguments, never code, and must go on doing so.
2. Re-entrancy: called inside an open cycle, it must queue, as a re-entrant event does, or refuse. The spike refuses.
3. Thread: it must run on the processor's thread (the #46 class of race). Mongoose guarantees that through the admin
   queue; Fluxtion could assert it.
4. Exceptions: the `finally` must clear `processing` and close the record (finding 2).
5. Audit spoofing: the caller chooses the audit event. Such records should be marked as actions, distinct from inputs.
6. Secrets: the audit event's `toString` is written to the log, so a command's arguments need redaction where they are
   sensitive.

**Who writes the handler:** the processor's author, the same person who writes a lambda today. They register the name
(`registerSignalCommand`) and add a filtered signal handler to a node (or a Spring XML `signalHandlers` binding). It
is a real graph node, which is why it audits and propagates, and why the generator must see it. That is option A's
cost: each lambda command must be rewritten this way to gain the cycle.

**One detail:** a `Signal` record's `eventTime` is `-1`, because `Signal` is a Fluxtion `Event` with no producer time
(`logTime` is correct). The analyser's time-order checks read `eventTime`.

## 3e. One processor, several in one agent, several agents: what holds, and what is needed

**One processor: supported (R1-R7).** Everything Mongoose delivers to it (feeds, typed service calls, timers, admin
commands) is recorded in its order at the instants it read, and replayed.

**The limits, for every case** (not solved by more processors):
- Hidden inputs inside nodes: randomness, iteration order, file, database or network reads, values read back from
  injected services. They are detected by divergence, not supplied.
- Direct calls from code holding a processor (`registeredProcessors()`), outside Mongoose's paths.
- After a failure the processor has stopped (the `processing` wedge, §3a finding 2); the stream ends there.

**Needed even for one processor, before a replay runs anywhere real (both implemented, §3f):**
- **L1: disconnect live inputs.** A replayed processor's queues mute live dispatch to it (`muteLive`); only the replay
  reaches it (`processEventFor`). Other processors in the group keep their live inputs.
- **L2: mute outputs.** A replayed processor's `MessageSink` services are replaced by a capture: what it sends is kept
  for comparison (`GroupReplayer.outputs`) and never delivered, so a replay repeats no side effect. Output a processor
  sends other than through a registered sink service is outside this (a hidden channel, like any other).

**Several processors in one agent group:**
- Recording is already right. The group runs them on one thread; each has its own stream; one queue item fanned out
  to several is one entry in each (an index to the same journal item).
- They interact only through queues (a publication that reaches another's feed is recorded as that one's input),
  **or through a shared mutable service or static state**, which is a hidden channel: a read of it is an input no
  replay supplies.
- Replaying one of them: supported (L1, L2). The rest of the group runs live beside it, on the live scheduler.
- Replaying several together, each from its own stream: supported. With L2 one processor's replayed output does not
  reach another, whose stream already holds it, so their streams are independent: no order across processors is needed.

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

## 3f. The review of #47 (2026-09-29): findings and dispositions

Each fix is behind a regression committed before it (`ReplayReviewRegressionTest`, `AgentHandoffTest`,
`AuditSinkOnAgentThreadTest`), run on the reviewed code first; the commit messages record each pre-fix result.

| # | finding | disposition |
|---|---|---|
| 1 | a `ReplayRecord` input recorded wrapped; the recording clock dropped; a silent divergence | fixed: recorded as the event given; a recorded processor's clock is pinned to the record's instant, still recording |
| 2 | a replay stalls forever on a missing route or admin command | fixed: stops after `deliveryTimeout` (5 s), saying why |
| 3 | a store failure recorded as a dispatch failure and retried | fixed, and worse than reviewed: it exited the JVM. Recording is after the dispatch, never throws; a named-event input is recorded as item + number |
| 4 | a second recording into the same CSV files corrupts both | fixed: RECORD refuses a journal or store that holds a recording |
| 5 | a torn last CSV line makes the file unreadable | fixed: dropped with a warning; line breaks in names refused |
| 6 | REPLAY delivers live inputs (L1); outputs not muted (L2) | fixed: both, above |
| 7 | the replay scheduler replaces the whole group's | fixed: replay time and timers for replayed processors only |
| 8 | a clock-read count mismatch is not reported | fixed: stops as a clock divergence |
| 9 | a timer that throws is recorded as fired | fixed: recorded as `Failed`. Through a server it still ends the process, because the default error handler exits on any agent error, with or without replay: an existing behaviour, not changed here |
| 10 | a pooled item's catch-up snapshot replayed as the original (INFERRED) | not a defect: a published item's snapshot is never dispatched again; a test holds that |
| 11 | `audit.start` timeout still installs later; a lost handoff (INFERRED) | fixed and shown: `AgentHandoff` makes handed-over work run at most once, never after its caller gave up |

Also: the javadocs displaced by inserted members are back on their owners; `ReplayRouting` returns a one-method
`ReplayRoute` rather than the internal agent; the replay API is `@Experimental`; the weak tests are strengthened
(`awaitReplay` fails on timeout, witnesses check a full replay first, R1 checks the live clock, a typed-call witness).
Remaining limit, stated not fixed: clock reads outside an input's cycle (`start()`, `@Initialise`) are not recorded.

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

- **Durable journal and store.** The spike's are in memory. A Chronicle journal and store, retention, and the
  evidence bundle's reference into a remote journal (pinned by digest) are the next step.
- **Direct exported-service calls** from code holding a processor: recordable only at Fluxtion's `beforeServiceCall`,
  with the arguments (a Fluxtion change).
