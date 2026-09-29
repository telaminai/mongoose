# Spec: record and replay a processor's inputs in Mongoose

**Status**: r6, 2026-09-29. Implemented and tested on `feat/replay-at-dispatch` (PR #47), reviewed, re-reviewed and
independently reviewed; every finding is dispositioned in §3f, each fix with a regression that failed first. Background, and the evidence each
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
  - `Indexed{source, route, seq, instant, reads}`: an input from a journalled feed; the event is in the feed's journal.
  - `Inline{source, route, event, instant, reads}`: an input from a feed with no journal, as a copy taken just before
    the processor handled it (Java serialisation; a value that cannot change is kept as it is). An input that cannot
    be copied is recorded `Failed`, never by reference. A delivered `NamedFeedEvent` is kept field by field and rebuilt.
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
then **17 of 17 controls caught**, each by a named assertion, with every file restored byte-identically.

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
| 4 | the replay route is the first queue for the source | live went by `onEvent`; the replay sent every entry through the typed route, in both subscription orders | fixed: the queue carries its configured route, entries name it, routing matches source and route, an entry naming none is refused when two routes could deliver it |
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
