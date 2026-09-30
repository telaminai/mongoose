# Spec: record and replay a processor's inputs in Mongoose

**Status**: r10, 2026-09-30. Implemented and tested on `feat/replay-at-dispatch` (PR #47, released in mongoose 1.0.31),
reviewed, re-reviewed, independently reviewed and independently re-reviewed; every finding is dispositioned in §3f, each
fix with a regression that failed first. Admin commands in the event cycle (§3d) are on `feat/admin-commands-in-cycle`
(PR #48), reviewed and corrected (§3d.1, §3d.2), and integrated with released main (§3d.3). Background, and the evidence each
decision rests on: [`replay-at-dispatch-spike.md`](replay-at-dispatch-spike.md).

## 1. Goal, and the boundary of the claim

Record every input Mongoose delivers to a processor, so that the run can be replayed into the same build and processor,
with the same config, and the processor does exactly what it did, at the same instants.

**The claim is bounded to what crosses Mongoose's boundary into a processor, and the processor's clock.** An input a
node obtains itself is not supplied by a replay: a random value, the iteration order of an unordered
collection, a file, database or network read, or a value read back from an injected service (scheduler time, pooled
objects, controller snapshots). A replay does not supply such a read, **and does not detect it**: it stops only when an
entry cannot be delivered, a journal lacks an item, or the processor reads its clock a different number of times than
the recorded cycle did. A hidden input that changes what the processor does without changing its clock reads replays
to a different result with `complete()` true and `stopped()` null. **Reproduction is shown only by comparison**: the
replay's captured outputs (`outputs(processor)`) or its audit log against the recorded run's. `complete()` with no stop
reason means every entry was delivered, not that the run was reproduced. Also out of scope: a retried dispatch (a failure: determinism is off from that point, and it is marked,
not reproduced) and calls made from outside Mongoose's paths (code holding a processor from `registeredProcessors()`).

## 2. Model

- **One ordered stream of entries per processor.** Every Mongoose path into a processor runs on its group's agent
  thread, after a queue (the one exception, `audit.start`/`audit.stop`, is fixed by R7). So recording at those points
  gives the processor's input order across all its sources, with no locking.
- **An entry is an index or an event:**
  - `Indexed{source, route, seq, instant, reads}`: an input from a journalled feed that this processor received as the
    journal holds it (compared through the feed's codec); the event is in the feed's journal.
  - `Inline{source, route, event, instant, reads}`: an input from a feed with no journal, as a copy taken just before
    the processor handled it; or a journalled input held in its feed's own codec (`EncodedInput`, when what this
    processor received is not what the journal holds), or a journalled named wrapper with its own fields and a
    `JournalRef` payload. What can be held is the contract in §3f ("What a recording can hold"); anything else is
    recorded `Failed`, never by reference.
  - `TimerFired{seq, instant, reads}`: a timer the processor scheduled fired.
  - `AdminInvoked{command, args, instant, reads}`: a processor-owned admin command ran.
  - `Failed{source, description}`: a dispatch threw. The stream is not reproducible past it.
- **The callback is configuration, and the entry names which one.** An entry names its source and its route, the
  callback type that delivered it; a replay boots the same config, and delivers the entry through the strategy that
  config gives that source and route for this processor's group (an `onEvent`, a typed service call, an admin
  invoker). One source can reach a processor by more than one route. An entry that names no route is refused when more
  than one could deliver it.
- **The instants are the processor's own.** The processor's `Clock` auditor reads its clock strategy when an input
  arrives, before any node runs, and again for any event the graph raises during the cycle; a node may read it too. A
  recording clock stays live, is armed just before a dispatch, and records every read until the dispatch returns:
  exactly those, none for a cycle that read no clock, with the input's instant kept beside them. Production reads are
  unchanged. A replay plays each entry's reads back in order and requires the same count.
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
  difference is within the runs' noise. *Superseded (§3f, the independent review): longer alternating runs resolve no
  stable difference on the default feed, and a consistent ~0.4 ns on the named-event feed. Allocation is confirmed.*
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

**Status (PR #48, fluxtion runtime 1.1.0): they do.** A lambda command runs through `DataFlow.runInEventCycle` as its own
cycle (`AdminCommandAuditTest`, inverted), and a signal-routed one as an ordinary event. What follows is how it stood
before, and why. **Before:** `AdminCommandAuditTest`, through a real server, showed that a processor-owned
command ran on the processor's thread, changed node state, and opened **no** event cycle: the processor's `onEvent`
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
`DataFlow.runInEventCycle(Object auditEvent, Runnable action)`. Released in fluxtion runtime 1.1.0, whose interface
default throws `UnsupportedOperationException` (the spike's default ran the action with no cycle, which a caller could
not tell apart; the release made it refuse). A generated processor implements it with the boundary it already has for
exported service calls:
1. `auditEvent(auditEvent)`: every auditor sees it.
2. `processing = true`.
3. The action runs.
4. `afterEvent()`, then `dispatchQueuedCallbacks()`, so anything the action redispatched runs after it, in order.
5. `processing = false`, in a `finally`.

The event is not dispatched to any node, and nothing is marked dirty. Mongoose's invoker becomes
`processor.runInEventCycle(new AdminCommandEvent(name, args), command::executeCommand)`: the trigger is the command,
carrying all its state.

**Spiked 2026-09-29, both Fluxtion repos, then released: fluxtion runtime 1.1.0 and compiler 1.0.76, hosted
generator deployed.** The released form closes the cycle in a `finally`, orders the buffered calculation first, and gives
an `Event` context its own event time; generated source has no `@Override`, so it also compiles on runtime 1.0.16. The
spike's record:
- Runtime (`b8e97b4`): the `DataFlow` default plus the `DefaultEventProcessor` override. `RunInEventCycleTest` 5/0/0/0,
  suite 274/0/0/1, three controls caught.
- Compiler (`632d99a1`): the method is added in `javaTemplate.vsl` and in `InMemoryEventProcessor`, with **no change to the
  generator model**. `RunInEventCycleTest` 15/0/0/0 across the five targets (three compiled, serialised, interpreted).
  Removing the template override fails the compiled targets, and removing the interpreter's fails the interpreted one.
  The only change to the pre-split goldens is the added method.
- The audit log it produces: the command's record carries the node's line and nothing from downstream. The event it
  raised follows as its own record, with the publisher firing.
- Mongoose (PR #48, on 1.1.0): the invoker calls `runInEventCycle` when the processor's class implements it (the method
  is not the inherited default, `Method.isDefault`, decided once per class); a processor that inherits the refusing
  default, or whose override refuses, has the command bracketed by its audit calls. Anything that fails before the
  command runs answers the caller with an error and is reported; a command runs at most once (`AdminCommandFailureTest`).
  `RunInEventCycleAdminTest` runs, no longer skipping.

**Why a default method cannot run the cycle:** it sees only `DataFlow`'s public API. The auditor fan-out, the
`processing` flag, `afterEvent()` and the callback queue are private to each implementation. Exposing them as public
begin and end calls would let any caller leave a cycle half-open. So the default refuses (runtime 1.1.0), and each
implementation overrides it. A template that wants the path closed makes the override throw; Mongoose then brackets.

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

### 3d.1 The independent review of #48 at f8deed60 (review 5910486472)

Regressions (`AdminReviewRegressionTest`, real servers) were committed with predictions before they ran (`2d45bde`),
then run on f8deed60's code; results in the commit messages and `replay-evidence/admin-review-f8deed60/`.

| # | finding | pre-fix result on f8deed60 | disposition |
|---|---|---|---|
| 1 | a throwing signal command is recorded and replayed as a success | RECORD stored `AdminInvoked` (a handler that throws, and a cycle that fails after the handler replied); the replay ran the handler again, `complete` with no stop | fixed (`ee6254c`): the caller is answered and released once, then the failure is rethrown to the dispatch: reported, recorded `Failed` (D4), the replay stops before running it again, the retry is a no-op |
| 2 | an internal cycle failure is taken for a disabled cycle | an unmodified `DefaultEventProcessor` whose clock failed inside `runInEventCycle`'s setup ran the command by the bracket and answered success | fixed (`7d92726`): the route is decided before invoking, from a declaration (`AdminCommandsBracketed`) or an inherited default; anything failing before the command runs refuses it by name |
| 3 | the caller is not always answered; an interrupted caller's command still runs | caller left waiting (stopped server; replay-muted processor; a command never claimed); an interrupted caller's queued command ran later and replied to it; a started command's late reply reached its departed caller | fixed (`ee6254c`, `e1ae20f`): per request QUEUED / CLAIMED / CANCELLED, a bounded wait (`mongoose.admin.completionTimeoutMs`, 10 s), cancellation only of unclaimed work (never run later), an honest "started" answer otherwise, the reply channel closed once, a muted processor refused at once |
| 4 | lookup trims the name, the signal does not | `" DEMO.ok "` answered "no handler replied" | fixed (`ee6254c`): a command's registered name is bound once and used for routing and identity |
| nit | head-specific counts stale | the PR body said 266 tests and 42 controls; f8deed60 ran 270 / 0 / 0 / 9 across 84 reports, and its gate held 44 controls (34 named assertions, 10 expected-message timeout or error) | corrected in the PR body, labelled by revision |

**Owner decisions** raised, not taken:
- **An override that refuses the cycle without declaring `AdminCommandsBracketed`** was bracketed at f8deed60 and is
  refused now. Fluxtion 1.1.0 has no capability query, so the existing API cannot tell a deliberate disable from a
  failure.
- **An immediate "the server is stopped" refusal** needs `LifecycleManager.stop` to stop `LifeCycleEventSource`
  services (it skips them). Today a stopped server's command is cancelled at the bound.
- **The completion bound's default (10 s).** It caps what used to be an unbounded wait, so a legitimately longer
  command's caller now gets the "started" answer.

Misses, recorded:
- Finding 2's first fixture set the failing clock in a constructor; the server replaces the clock at boot, so it never
  failed. Its pre-fix FAILURE (the command ran) was the command running normally. Corrected (`48edb46`) and re-run: the
  clock fails inside the setup, and the command still ran pre-fix.
- `ee6254c` claimed a stopped server refuses at once (its message was amended before push, so `e1ae20f`, which corrects it, cites its earlier hash `7e78802`). The flag it relied on is never set on server stop, and tightening
  the test to require the named reason exposed it. The dead flag was removed (`e1ae20f`).
- Two finding-3 tests used a marker on another feed as a barrier. Nothing orders two queues, and a control that let a
  cancelled command run survived. They now use a second request queued behind the first on its own queue (`97a3c22`),
  and still fail on f8deed60's code.
- An unknown command name is still not answered (older than #48, stated in the how-to).

**Stack integration with #47.** #48 is stacked on #47 at the merge that f8deed60 carries, NOT on #47's current head
(`cd52628`), which has moved twice since: entries with route and instant, `ReplayRouting.routeFor(source, route, flow)`,
the N1-N7 round (`GroupReplayer.attach` order, `InputCopy`, `RecordedNamedEvent`, the default
`processEventRecording`), and a controls list of 59. Integration must keep both sides' recorder and replayer changes,
merge the two controls lists by name rather than take either wholesale (anchors move on both sides), and re-run both
sides' regressions and the gate on the combined tree.

**Controls.** Nine were added (`adm1` x2, `adm2`, `adm3` x4, `adm4`, `F6`), one per protection. Two earlier ones
moved with the code: B2's retry guard is now the claim; F3 protected the undeclared fallback finding 2 removed and now
protects the declared route. The first runs caught 9 of 11 (the misses above), then 3 of 3 re-run. The full gate at
this head: **53 of 53 detected, 43 by a named assertion and 10 by an await running out or an error carrying the
mutation's expected message** (the ten the review listed; all nine new controls are named-assertion detections).
Requested and detected names match (53, no duplicates). The harness now restores each file with `cat f.orig > f`,
checks its SHA-256, and recompiles from clean. `mvn -q test`: 282 / 0 / 0 / 9 across 85 reports, no orphans; after the
controls, `mvn -q clean test`: the same. The ordinary dispatch path did not change (a new protected accessor on
`AbstractEventToInvocationStrategy`, and an override in the admin invoker only), so `DispatchPathJmh` was not re-run.

### 3d.2 The correction-round review of #48 at b4e80c1 (review 5912663051)

It resolved findings 1, 2 and 4 and the counts nit, found finding 3 partly resolved, and raised N1-N3. Regressions
(`AdminCorrectionRegressionTest`, real servers, through `AdminReviewRegressionTest`'s fixture) were committed with
predictions before they ran (`a7f02ec`), then run on b4e80c1's code; results in the commit messages and
`replay-evidence/admin-correction-b4e80c1/`.

| # | finding | pre-fix result on b4e80c1 | disposition |
|---|---|---|---|
| N1 | a cancelled command is recorded as an invocation | 2 `AdminInvoked` entries for 1 command that ran; the replay ran both | fixed (`bdf68d2`): an invocation's outcome, `ran()`, is carried into the recording; a command that did not run records nothing; a command that ran and threw is still `Failed` |
| N2 | a template reused after a timeout mixes two requests | the second caller returned on the FIRST command's completion; a cancelled slot, revived by the next publish, ran first with its arguments (`[2, X]` for `[X, 2]`) | fixed (`3b6b97c`): every publish is its own invocation (arguments, claim, latch, reply lifetime; never reset); the template keeps only admission, for `publishCommand(List)` ("busy", retained and tested); the `AdminCommandRequest` overload admits each request alone, and a no-queue command runs synchronously (review of 8d224fb, nit 2) |
| N3 | a reply consumer defeats the bound | the caller was BLOCKED behind a blocked output consumer, and a blocked error consumer | fixed (`3b6b97c`, `210b8a4`): one atomic phase (QUEUED, CLAIMED, COMPLETED, CANCELLED, ABANDONED); no lock while a reply is delivered; the caller's final message on its own thread |

**Delivery, stated:** a reply that has not begun when the channel closes is dropped. A delivery already executing is not
retracted, and can finish after the caller's final message. The bound covers the command's completion, not
end-to-end transport of its replies.

Written with the fix, for behaviour it added (`AdminCommandLifetimeTest`, a real publisher, queue and invoker):
- completion winning the race with the expiry, through a seam (`beforeExpiry`, a no-op in the product) that b4e80c1
  lacks;
- a reply begun after the caller gave up being suppressed.

Retained, passing on both trees: busy admission; F6's completed reuse; a late asynchronous reply gated.

Misses, recorded: the cancelled-slot test as first designed would have passed on b4e80c1. The revived object ran
once with the second publish's arguments, so only the queue position showed the revival. A request in between made it
visible. The race control was first scored a timeout, because its assertion printed replies carrying the harness's marker.

**Stack integration with #47** (as planned at 8d224fb; done in §3d.3):
- **The recorder check:** #47's per-target hook (`recorder.received` before `dispatchEvent`) sees a command before its
  claim, so inheriting it does not carry N1. #47's `afterDispatch` needs the same `ran()` check before it builds
  `AdminInvoked`.
- **The controls:** the two branches' lists have 80 distinct names (at the reviewed heads), with seven shared names
  whose definitions differ. They must be merged by name, with each shared control's anchor re-derived from the
  combined source, and both harness copies merged.
- **The recorder and replayer:** the entry signatures (route, instant, `routeFor(source, route, flow)`) must be
  reconciled.
- **Gates:** both sides' regressions and the gate must pass on the combined tree before release.

**Controls.** Six were added (`cr-*`). Four anchors moved with this round's code and still remove the same protection:
adm3-cancelled (the phase claim), adm3-a-late-reply (the phase gate), F6 (now: reuse the template without reset) and R6
(an else-if after N1's check). The targeted run requested 17 (the six new, plus the affected adm1 x2, adm2, adm3 x4,
adm4, B2, F6, R6). All 17 were detected at named assertions, after the race control's assertion was changed to stop
printing replies. The full gate: **59 of 59 detected, 49 by a named assertion and 10 by an await running out or an error
carrying the mutation's expected message** (the ten of b4e80c1). Requested and detected names match (59, no duplicates);
restored with `cat f.orig > f`, SHA-256 checked, recompiled from clean. `mvn -q test`: 291 / 0 / 0 / 9 across 87
reports, no orphans; after the controls, `mvn -q clean test`: the same. Ordinary event dispatch is unchanged, so
`DispatchPathJmh` was not re-run.

### 3d.3 Integration with released main (1.0.31), and the review of 8d224fb

The review of 8d224fb (comment 5919428341) approved N1-N3 on the branch and raised two non-blocking nits. Released main
(`59b9d8f`, mongoose 1.0.31 with #47) was merged into the branch (`1c18def`), not rebased. Predictions were committed
first (`1195dd4`, `replay-evidence/integration-main-1.0.31/predictions.md`).

**The nits.**
- **Nit 1, the timeout wording.** An abandoned command's message promised "nothing more from it will reach this caller",
  but a delivery already begun may finish after it. The message now says "no new reply delivery will begin, and a
  delivery already in progress may still finish" (`621045b`). The delivery policy is unchanged. Regression
  `n3_aDeliveryInProgressAtTheBound_mayFinishAfterward_andTheFinalMessageSaysSo`: it holds an output consumer, lets the caller
  expire, releases the consumer, and asserts the order (the final message, then `DEMO-ok`) and the wording. On 8d224fb it
  failed at the wording assertion; the order already held. Control `nit1-the-timeout-message-promises-no-retraction`.
- **Nit 2, admission.** Documentation only (§3d.2's N2 row, the how-to). Shared template admission and "busy" apply to
  `publishCommand(List)`. The `AdminCommandRequest` overload admits each request alone. A no-queue command runs
  synchronously, outside the bound.

**Conflicts, and how each was resolved.**
- `design-doc/replay_controls.py`: the union of both lists, 99 names (main 72, the branch 59, 32 in the merge base, none
  dropped by either side). Eight shared names had different definitions. Seven were changed by main to follow its own
  code, while the branch kept the base: R2-arms-the-processors-clock, R2-graph-raised-events-never-pass-dispatch,
  R5-pins-the-entry-instant, R5-plays-every-read-of-the-cycle, csv-the-store-reads-back-its-file,
  review-3-a-named-input-is-recorded-as-its-item and review-5-a-torn-last-line-is-dropped. These take main's definitions.
  R6-records-an-admin-command-by-its-args was changed by the branch (an `else if` after the `ran()` check) and takes the
  branch's. Every anchor occurs exactly once in the merged source.
  Harness: the branch's body is a superset of main's. It has main's `.orig` byte copy, `cat` restore and recompile from
  clean, plus lookup of a report by simple class name in any package, with ambiguity refused.
- The spec's status line: combined (r10).

**Merged textually, checked by reading.**
- `GroupRecorder.afterDispatch`: the `ran()` check sits after the clock capture and `if (!r.received) continue;`, and
  before `AdminInvoked` is built. Main's `received` hook runs before the invoker's claim, so receipt is not proof that a
  command ran.
- `AbstractEventToInvocationStrategy`: it keeps both main's `processEventRecording` and the branch's `mutedForReplay`
  accessor. `processEventRecording` bypasses the invoker's `processEvent` override, so a muted processor is skipped
  there rather than refused. A server has one `ReplayConfig.Mode`, so a recorder and a muted processor never coexist, and
  the difference cannot be observed.
- `pom.xml`: 1.0.32-SNAPSHOT (main), fluxtion 1.1.0 (the branch; main was on 1.0.15).
- `ReplayEntry` and routing: main's route, explicit instants and `routeFor(source, route, flow)` stand.
  - The recorder builds `AdminInvoked` with the instant it captured.
  - The replayer pins `a.instant()` and routes by `adminCommand.<name>`.
  - The branch's only use of a compatibility constructor is a test's never-deliverable `TimerFired`.

**Semantic interaction despite a clean merge: an admin command now takes a clock reading.** The branch runs a lambda
command as its processor's own event cycle. In fluxtion 1.1.0, `DefaultEventProcessor.runInEventCycle` opens the cycle
with `auditEvent`, and `Clock.eventReceived` reads the clock, so the command's entry records one reading. That is
faithful, and a replay takes it again. Main's f6 pair in `ReplayIndependentReviewTest` (finding 6 of §3f: zero reads
recorded as zero, and a read the replay does not take is a divergence) used a lambda command as its "reads no clock"
vehicle. On the merged tree both failed at their named assertions (`[<a reading>]` for `[]`; no divergence).
Their meaning is kept on a vehicle that still reads nothing: a timer firing whose action reads no clock. The first test
also asserts the new fact: an admin command's cycle takes exactly one reading. Main's controls
ir-6-a-cycle-with-no-reads-records-none and ir-6-the-read-count-must-match-exactly are both caught at their named
assertions.

**New regression, the refused case through main's capture.** `AdminIntegrationRegressionTest`, a real server:
- RECORD, where a processor's declared event cycle fails while it sets up;
- the caller is answered with an error;
- the next command runs;
- the store holds exactly one `AdminInvoked`;
- the replay completes, running one command.

The cancelled case is N1's regression, and it now runs through main's recorder. Control
`int-refused-work-is-no-invocation` removes the `ran()` check, and the refused command is recorded as a second
`AdminInvoked`.

**Results on the integrated tree** (JDK 21.0.9, Maven serially):
- Focused suites (the branch's admin suites and main's replay suites) before the f6 change: 112 / 2 / 0 / 0, the two
  f6 tests; after it, green.
- `mvn -q clean test`: 349 / 0 / 0 / 9 across 92 reports, no orphans.
- Gate: 101 registered (99 merged plus the two new), **101 of 101 detected**: 91 at a named assertion, 6 by an
  expected-message timeout and 4 by an expected-message error. Requested and detected names match (101 each, no
  duplicates). Restored with `cat`, SHA-256 checked, recompiled from clean.
- After the gate, `mvn -q clean test`: 349 / 0 / 0 / 9 across 92 reports, no orphans; `src/` identical to HEAD.

*Historical, by revision:* 8d224fb 291 / 0 / 0 / 9, 87 reports, gate 59 of 59; main 59b9d8f (1.0.31) 312 / 0 / 0 / 9,
83 reports, gate 72 registered.

## 3e. One processor, several in one agent, several agents: what holds, and what is needed

**One processor: supported (R1-R7).** Everything Mongoose delivers to it (feeds, typed service calls, timers, admin
commands) is recorded in its order at the instants it read, and replayed.

**The limits, for every case** (not solved by more processors):
- Hidden inputs inside nodes: randomness, iteration order, file, database or network reads, values read back from
  injected services. They are not supplied, and a replay does not detect them: comparing its captured outputs or its
  audit log with the recorded run's does.
- Direct calls from code holding a processor (`registeredProcessors()`), outside Mongoose's paths.
- After a failure the processor has stopped (the `processing` wedge, §3a finding 2); the stream ends there.

**Needed even for one processor, before a replay runs anywhere real (both implemented, §3f):**
- **L1: disconnect live inputs.** A replayed processor's queues mute live dispatch to it (`muteLive`); only the replay
  reaches it (`processEventFor`). Other processors in the group keep their live inputs.
- **L2: mute outputs.** A replayed processor's `MessageSink` services are replaced by a capture: what it sends is kept
  for comparison (`GroupReplayer.outputs`) and never delivered. Only those: anything a replayed processor sends through
  any other service it was given (an injected gateway, a publisher, another processor's exported typed service) is the
  live instance, and is delivered. That is outside L2, and a replay's side effects through it are the operator's to
  isolate.

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

**The re-review (review 5357040107)** verified every disposition above and found:

| # | finding | disposition |
|---|---|---|
| A | a live `ReplayRecord` on a replayed processor's queue is muted, but its synthetic clock still replaced the processor's `ReplayClock`: every later entry replayed on the live instant, silently | fixed: `setSyntheticTime` refuses a processor muted for a replay. Shown first: the last entry replayed at `time=42`, not its recorded 999999 |
| B | a journal append or encoding failure escaped `publish()`, reaching the default error handler, which exits | fixed: logged and reported once; the feed stops journalling; its items still carry their numbers, so a replay stops at the first gap by name (`reB_aRecordingWithAJournalGap_replaysToItAndStopsThere`) |
| C | the PR body and how-to overstated L2 | corrected: only registered `MessageSink` services are captured; anything sent through another given service is live |
| D | `@Experimental` on 7 of 20 replay types; a method between fields; import order | all replay types a user names are marked; moved; sorted |
| E | `runOnAgentThread` re-check read as a delivery guarantee | its javadoc and comment say best effort, and why a lost handoff is safe |

The controls harness is now a gate: it exits non-zero on any control not detected and on any file not restored; a
detection by an `await` running out or by an error counts only when its message carries the fragment the mutation is
expected to produce. 32 of 32 detected; a no-op control, added for the check, fails the gate.

**The independent review of 90f0d9b** (review 5897482047) found five blockers and three should-fix findings. Each
regression was committed with its prediction before it ran (`5cf34c3`), then run on 90f0d9b; the pre-fix results are in
the commit messages and below. Regressions: `ReplayIndependentReviewTest`, two in `AgentHandoffTest`, two in
`AuditSinkOnAgentThreadTest`; finding 1's run a real server in a child JVM (`ReplayFailureChildMain`).

| # | finding | pre-fix result on 90f0d9b | disposition |
|---|---|---|---|
| 1 | a replay failure ends the server | both child JVMs exited 255 (store `entries` throwing at attach; a decoder's `AssertionError`) | fixed: contained (any `Throwable` but a `VirtualMachineError`) and published as `stopped()`; a processor whose store could not be read keeps its cursor, so its live inputs stay muted |
| 2 | an inline input is not what was received | replayed `value=1` for `value=0` (memory and CSV); `[51, 52]` for a reused `[10, 50]`; fan-out `[2, 3]` for `[0, 1]`; a non-`Serializable` input held by reference | fixed: each processor's input is copied just before it is given it (`processEventRecording`), committed only after a first-attempt success; an input that cannot be copied is recorded `Failed`; a replay copies again |
| 3 | an application's `NamedFeedEvent` on a NOWRAP feed is stripped | replayed `bare=DEMO-item` (both stores) | fixed: recorded field by field (`RecordedNamedEvent`) and rebuilt exactly |
| 4 | the replay route is the first queue for the source | live went by `onEvent`; the replay sent every entry through the typed route, in both subscription orders (here; the re-review's tree passed both: see *Corrected evidence* below) | fixed: the queue carries its configured route, entries name it, routing matches source and route, an entry naming none is refused when two routes could deliver it |
| 5 | an interrupted audit handoff runs after its refusal | the held install ran after the refusal; a running install had its sink closed under it | fixed: an interrupt cancels unclaimed work, and waits (uninterruptibly, keeping the interrupt) for work already running |
| 6 | the clock-read count is padded | zero reads recorded as one; one recorded read taken zero times accepted | fixed: the instant is kept beside the actual reads; the count must match exactly |
| 7 | a torn tail corrupts the next append | a store or journal whose only record was torn read as empty; RECORD accepted it | fixed: the torn line is set aside; the file reads, but RECORD refuses it and `append` refuses by name |
| 8 | the docs claim automatic divergence detection | (wording) | corrected in §1, §3e, the how-to and the PR body: a hidden input can change the result undetected; reproduction is shown by comparing outputs or the audit log; `complete()` with a null `stopped()` does not prove it |

Misses, recorded rather than dropped:
- Finding 3's first pre-fix run failed on its own fixture: `NamedFeedEventImpl(String, long, T)` discards the number.
  Corrected (`a299f6e`); re-run, both stores failed as predicted.
- Finding 4's live run showed only the `onEvent` route delivering, and four lines for two offers (observed). The likely
  cause, read from the code and not tested: `EventFlowManager` keys the queue by source and subscriber, not by callback
  type, so both routes' agents drain one queue that the source targets once per route. That is Mongoose's existing
  delivery, outside replay, and not changed here. Because live cannot show both routes, three
  more tests drive entries naming each route directly.
- Two finding-2 tests (a replay copies again; an uncopyable input) were written after the fix, for behaviour it added,
  then run on 90f0d9b's code in a scratch worktree: both failed.
- The inline copy is Java serialisation: a feed with non-`Serializable` items should be journalled with its own codec.
- CI at `1fd693d` failed once (the push run; the pull-request run at the same head passed):
  `R4_aTimeoutFiringBetweenInputs_replaysThere`. It was a race in the test, present since the test was written: the
  recorded run's lines were read after 4, between the second order's line and the breach the graph raises in the same
  cycle, so the expected list lacked the breach that the replay correctly produced. It now waits for all 5. Reasoned
  from the CI log and the handler, not reproduced locally. The two controls on that test are still detected (R4
  records-timer-firings by its named assertion, R4 never-fires by its expected message).

**Controls.** 18 controls were added (`ir-*`), one per mechanism these fixes introduced, each required to fail a named
assertion. The first full run detected 49 of 50: `review-F4-only-a-first-attempt-is-recorded` **survived**. Finding 2's
capture hook had been used on the first attempt only, so a retry was kept out of the recording twice over, and removing
the documented guard was an equivalent mutant. A single guard now decides (`317a80c`), and the control is caught by its
named assertion. Final run: **50 of 50 detected**, 42 by a named assertion and 8 by an `await` running out or an error
that carries the mutation's expected message (the same 8 as before: R4 never-fires, R5 pins, R5 alone, both CSV
read-backs, review-2, review-5, review-reB). All 18 new controls are named-assertion detections. Each mutated file is
restored with `cat f.orig > f` and its SHA-256 checked, and the sources are recompiled from clean after the run. The suite
after it: 279 / 0 / 0 / 9 across 80 reports, no orphans. Evidence: `replay-evidence/independent-review-90f0d9b/`.

**Replay off, re-measured** (the dispatch loop changed: RECORD takes a second branch). The same benchmark file
(SHA-256 `fe2f102c18e46623…`) on `origin/main` (`ab44617`) and this PR (`6e409a5`), alternating three times,
`-f 3 -wi 3 -i 5 -prof gc`, every run kept in the evidence directory:

| path | `main` ns/op | PR #47 ns/op | allocated / op (both) |
|---|---|---|---|
| NOWRAP (default feed) | 24.44 ± 0.11, 24.62 ± 0.16, 24.99 ± 0.58 | 24.79 ± 0.08, 24.82 ± 0.09, 24.70 ± 0.04 | 0.0002 B, 0 collections |
| NAMED_EVENT | 42.26 ± 0.11, 42.30 ± 0.10, 42.27 ± 0.16 | 42.59 ± 0.24, 42.70 ± 0.10, 42.75 ± 0.13 | 48 B, the existing wrapper |

- **Allocation is unchanged and reproduced:** about 0 B/op on the default feed, 48 B/op on a named-event feed.
- **On the default feed no stable timing difference is resolved.** The round-by-round differences are +0.35, +0.20
  and −0.29 ns. The earlier "+0.4 ns" is not confirmed, and is no longer stated as a result.
- **On a named-event feed the PR is 0.33 to 0.48 ns slower in all three rounds.** The earlier short runs called this
  noise; these do not. Where it comes from is not measured.

**The re-review of 4a18003** (review 5909729808) confirmed findings 4-8 resolved and 1-3 partly, and found N1-N7.
Regressions were committed with predictions before they ran (`770b494`), then run on 4a18003; results are in the commit
messages and below. Regressions: `ReplayReReviewTest` (N1 through a real server in a child JVM,
`ReplaySetupFailureChildMain`) and two in `ReplayIndependentReviewTest` (N7).

| # | finding | pre-fix result on 4a18003 | disposition |
|---|---|---|---|
| N1 | the replay clock install escapes attach | direct attach threw (not muted, no stop); the child JVM exited 255 | fixed (`538b6f3`): the cursor is registered first, then the clock install and the store read are each contained; a failure stops the processor's replay by name and it stays muted, its sinks captured |
| N2 | a named event loses its time and its type | `time=17` replayed as the wall clock (memory, CSV); a subclass replayed as `NamedFeedEventImpl`, `extra=17` gone (memory, CSV); all completed with no stop | fixed (`04c13e0`): the event time is recorded and set back; only `NamedFeedEventImpl` itself is captured, anything else is refused by name |
| N3 | journalled fan-out bypasses the per-recipient copy | `[0, 0]` for live `[0, 1]`, through a real journalled publisher | fixed (`9c1731a`): each recipient's input is compared with the journal through the feed's codec; different, it is recorded inline as that recipient received it |
| N4 | the custom-strategy default records silently | `[0, 0]` for live `[0, 1]` through a direct SPI implementation, no stop | fixed (`fe0cb89`): the default fails closed with more than one processor (`UncapturedInput`, recorded `Failed` naming the strategy); live dispatch unchanged |
| N5 | a successful serialisation is not a faithful copy | `transient=0` replayed for live `transient=17`, no stop | fixed as far as it can be detected (`a094e11`): a declared transient field is refused by name; the rest is the stated contract below |
| N6 | a header-only six-field store is corrupted by an append | append and RECORD succeeded; the reopen threw "line 2 has 8 fields, not 6" | fixed (`3defaad`): an earlier-format store is read, never appended to; RECORD refuses it; the file is left byte for byte |
| N7 | the reused-payload test's barrier was an output, not dispatch completion | not reproduced (PASS on both trees, as predicted) | fixed in the test: it waits for the handler to be done with the object; `n7_anOutputIsNotTheEndOfTheHandlersUse` shows, with latches, that the old barrier is met while the handler still owns it |

**What a recording can hold** (the contract N2-N5 settle on; each refusal is a `Failed` entry naming why, so a replay stops
there, and live delivery is never affected):
- a value that cannot change (strings, boxed primitives, `BigInteger`/`BigDecimal`, enums), kept as it is;
- a `Serializable` input whose class and superclasses outside `java.*` declare no transient field, copied by Java
  serialisation. Its serial form must carry every part of its state a handler reads: nested state, custom
  `writeObject`/`writeReplace`/`Externalizable` forms and anything else the serial form omits are NOT checked, and no
  automatic check could establish equivalence for the handler that reads it;
- a `NamedFeedEventImpl` (feed name, topic, number, delete flag, event time, both filters, and a payload under these
  rules), whether its payload is inline or journalled; any other `NamedFeedEvent` implementation is refused;
- a journalled input through the feed's own codec ONLY, which the configuration owns and must make faithful for its
  items: by index while the processor receives what the journal holds, otherwise as the codec's own bytes, read back by
  the same codec. Never re-copied by Java serialisation. The recording owns those bytes (copied at once) and every
  replay decodes from a copy, so a codec may reuse its buffers and a decoder may consume its input;
- through `AbstractEventToInvocationStrategy`, or any strategy that overrides `processEventRecording`, for any number of
  processors; through the interface's default, for one. A strategy that names no processor
  (`registeredProcessors()` empty) cannot be recorded: each processor its queue registered has its recording failed;
- a processor whose recording clock can be installed. One that refuses it has its recording failed at setup, by name,
  and goes on running live, unrecorded, with its live time as replay OFF gives it (a live `ReplayRecord` still sets its
  time). Its timers are still numbered and wrapped by the recording scheduler, which records nothing for it. A clock
  install that throws AFTER installing is treated as not installed: that processor runs on the installed recording
  clock, which reads the live clock, until a live `ReplayRecord` gives it the strategy's synthetic clock.

**Owner decisions** raised, not taken: whether to offer a per-type snapshot codec for inline inputs that cannot meet the
serialisation contract; whether a journalled Java codec should get the transient check the inline path has.

**Corrected evidence.** The response to the independent review said every regression failed on the unfixed code. The two
live-recorded route tests (`f4_twoRoutesFromOneSource_*`) failed here 10 times in 10 at `c32fdf6` (90f0d9b plus the
tests; JDK 21.0.9) and passed on the re-reviewer's tree (Corretto 21.0.8). Pre-fix, live went through whichever of two
agents sharing one queue drained first, and the replay took the first matching agent in a hash map, whose order follows
key hashes; whether the two coincide is not something the test controls (reasoned from the code, not verified across
JVMs). They are not witnesses for finding 4. The explicit-route tests are: they fail on the unfixed code by construction.

Misses, recorded:
- `n2_..._csvStore` first opened the replay store before the recording was written, so it replayed an empty file (no stop,
  nothing replayed): the fixture's error. Corrected (`6cd2199`); re-run, it failed as predicted.
- N1's direct-attach and N6's reopen regressions first failed by ERROR (an exception), not at an assertion; they now assert
  `assertDoesNotThrow`, so their controls fail at a named assertion.
- Found while fixing N1, not fixed (outside this round): `GroupRecorder.attach` installs the `RecordingClock` uncontained,
  so in RECORD a processor that refuses it would reach the agent's error handler. And a direct SPI that leaves
  `registeredProcessors()` at its empty default names no processor, so RECORD records nothing for it.
- The OFF dispatch path is unchanged by this round (every change is RECORD-only or replay setup), so the benchmark was not
  re-run; the re-reviewer's runs on a shared machine did not reproduce a timing difference, and none is claimed.

**Controls.** Nine were added (`rr-*`), one per protection N1-N6 added, and two earlier anchors moved with this round's
code (ir-1's store read, now inside the whole setup; ir-7's `holdsRecording`, now with the earlier format) and still
remove the same protection. N7 is a test's barrier, not product code: its witness is the latch test, not a control. The
first run of the eleven: 11 of 11 caught at a named assertion. The full gate: **59 of 59 detected, 51 by a named
assertion and 8 by an await running out or an error carrying the mutation's expected message** (the same eight as
before; all nine new controls are named-assertion detections). Each file restored with `cat f.orig > f`, SHA-256 checked,
and the sources recompiled from clean. `mvn -q test`: 291 / 0 / 0 / 9 across 81 reports, no orphans; after the controls,
`mvn -q clean test`: 291 / 0 / 0 / 9 across 81 reports, no orphans. Evidence: `replay-evidence/rereview-4a18003/`.

**The re-review of cd52628** (review 5911786834) accepted N1's REPLAY fix, N5's inline contract, N6 and N7, and found F1-F4.
Regressions were committed with predictions before they ran (`5dfd9ab`), then run on cd52628; results in the commit
messages and `replay-evidence/rereview-cd52628/`. Regressions: `ReplayRound3ReviewTest` (F3 through a real server in a
child JVM, `RecordSetupFailureChildMain`).

| # | finding | pre-fix result on cd52628 | disposition |
|---|---|---|---|
| F1 | N3's fallback swaps the feed codec for Java serialisation | `[codec=0]` for live `[codec=17]` (memory, CSV); a codec-only item refused as not Serializable; fan-out `[0, 0]` for `[0, 1]`; all but the refusal with no stop | fixed (`61e2517`): the fallback records the codec's own bytes (`EncodedInput`), read back by the same codec; journal gaps still stop the replay |
| F2 | named metadata lost on an indexed wrapper, and the integer filter | a journalled wrapper replayed with another event time (memory, CSV); one with its own fields recorded as `Indexed`; filter 17 replayed as 2147483647 (memory, CSV) | fixed (`0272736`, `61e2517`): a journalled wrapper is recorded with all its fields around a `JournalRef` or the codec's bytes; the integer filter is recorded and set back; the subclass refusal is unchanged |
| F3 | RECORD's clock install escapes | direct attach threw; the child JVM exited 255 | fixed (`f65c75f`): contained; the recording is failed by name and durably, and the processor runs on live, unrecorded (the stated policy) |
| F4 | a strategy naming no processor records nothing | entries `[]` for a delivered input; the replay completed empty | fixed (`8c041d9`): the queue knows the processors it registered; each recorded one's recording is failed by name, and a replay stops there |
| nits | the transient wording; two unbounded test waits; the PR body's spec revision | (text and test code) | fixed (`f3051e9`, `5dfd9ab`, the PR body) |

The OFF dispatch path did not change (the queue's registered set is written at registration and read only in RECORD), so
the benchmark was not re-run. The two owner decisions under N5 stand.

**Controls.** Six were added (`r3-*`), one per protection F1-F4 added. Three earlier anchors moved with this round's
code and still remove the same protection: ir-2's replay copy, now inside `materialised`; ir-3's rebuild, now around the
resolved payload; rr-N3's comparison, now choosing `EncodedInput`. The first run caught 7 of 9. Both misses were the
controls': `r3-F3`'s mutation did not compile, and `r3-F1`'s record-side mutation was an equivalent mutant (storing the
decoded object made the entry an index, which replays the unchanged value correctly). Both were corrected, and 2 of 2
re-run were caught at named assertions. The full gate: **65 of 65 detected, 57 by a named assertion and 8 by an await
running out or an error carrying the mutation's expected message** (the same eight as before). Requested and detected
names match (65, no duplicates); each file is restored with `cat f.orig > f`, SHA-256 checked, and recompiled from clean.
`mvn -q test`: 304 / 0 / 0 / 9 across 82 reports, no orphans; after the controls, `mvn -q clean test`: the same.

**The targeted re-review of 8211858** (review 5914820616) accepted F2, F4 and the nits, found F1 and F3 partly resolved, and
raised G1 and G2. Regressions (`ReplayRound4ReviewTest`, adapted from the reviewer's reproductions) were committed with
predictions before they ran (`5690172`; the Indexed case `4b411d2`), then run on 8211858; evidence in
`replay-evidence/rereview-8211858/`.

| # | finding | pre-fix result on 8211858 | disposition |
|---|---|---|---|
| G1 | the new `EncodedInput` does not own its bytes | a codec reusing its buffer replayed 23 for a recorded 17; a decoder clearing its input made the second replay 0 | fixed (`d09e536`): copied on construction and on every read; `EventCodec` states the ownership contract |
| G1+ | found while fixing G1: an `Indexed` entry decoded the journal's own array | the second replay 0 for 17 | fixed (`d50c857`): decoded from a copy, as the contract says |
| G2 | a failed RECORD setup changes a live `ReplayRecord`'s time | 99 for 42 (OFF gives 42) | fixed (`4417de7`): only an installed recording clock is pinned; the recording stays failed by name and durably |

**The local independent review of d2c6428** (an agent, before release) confirmed G1, G1+ and G2 and found two more: the
publisher journalled the codec's array as returned, so the contract's "a codec may reuse its buffers" was false on the
real journal path (23 for 17, through a real `EventToQueuePublisher`; fixed, `99125f6`), and the `JournalRef` copy had no
test that noticed its removal (a witness added; its control now catches the removal).

Controls: six added (`r4-*`), each caught at its named assertion on its first run. Stated, not fixed: a strategy that names only some of the processors it delivers to
still omits the others (the trusted-SPI limit, not detected), and a codec shared by the publisher and recipient agents
must be thread-safe (the configuration's). The OFF dispatch path did not change; no benchmark.

**Results.** The full gate: **71 of 71 detected, 63 by a named assertion and 8 by an await running out or an error
carrying the mutation's expected message** (the same eight). Requested and detected names match (71, no duplicates);
restored with `cat f.orig > f`, SHA-256 checked, recompiled from clean. `mvn -q test`: 312 / 0 / 0 / 9 across 83
reports, no orphans; after the controls, `mvn -q clean test`: the same. The local independent review then APPROVED 8963a56 with two
nits, both closed: its surviving mutation "copy only the first journalled item" is now a registered control
(`r4-G1-every-journalled-item-is-copied`), caught at the strengthened publisher test (which journals three items and
replays two); and the throw-after-install caveat is stated. Those two publisher controls re-ran caught; the full gate's
last run is 8963a56's 71/71, now 72 registered.

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
