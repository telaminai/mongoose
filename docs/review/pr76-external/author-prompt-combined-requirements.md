# Prompt for the authoring LLM: audit-capture migration requirements for PR #76

Context. PR #76 (`feature/control-plane`, candidate `4f5851e04a6b131a54b1741ee063fb0cc25e786a`) is going to a
production client that has run a crypto market maker on Mongoose for two years. Their deployment is static: YAML
boot, compiled event processors in a fat jar, no dynamic processor replacement, no record/replay. Audit capture IS
critical for them: the Chronicle captures, the Fluxtion audit-log analyser, an LLM driving the analyser and a shared
canvas are a core part of their support cycle. Treat every capture-facing change in this PR as a migration for that
client. The independent review report is at
`/private/tmp/claude-501/-private-tmp-mongoose-pr76-external-review-s0IjtI-mongoose/94e627f1-71d3-41bf-a7e5-8b8c231d75f8/scratchpad/pr76-review-report.md`
and its reproductions are under `src/test/java/com/telamin/mongoose/extreview/` in the scratch checkout
`/private/tmp/mongoose-pr76-external-review-s0IjtI/mongoose` (untracked; copy what you need, do not depend on that
checkout).

Do the following. For each item, cite the file:line you changed and the test that proves it. Where an item is a
decision rather than code, write the decision into the docs named below and say why.

## A. Make the capture layout change explicit and tooling-safe

1. Every routed capture session now lives in a fresh `capture-*` directory under the capture root with a
   `.mongoose-capture.properties` sidecar (`ChronicleAuditCaptureService.ProcessorSink.openQueue`, ~l.735-765),
   replacing the per-processor directory. Document the new layout, the sidecar schema and the old layout's
   read-only status in `docs/design/capture-resource-identity.md`, `docs/reference/control-plane-contracts.md`
   (Audit capture section) and `docs/release/release-notes-unreleased.md`, with a migration paragraph aimed at
   external tooling that found captures by processor-name path (import scripts, analyser file conventions,
   LLM tooling that globs the root).
2. Provide one stable programmatic way to resolve "latest capture directory for processor X in group G" and
   "all historical capture directories for X", through `MongooseAuditIntrospectionService`, and document it.
   The admin `audit.list` / `audit.status` output must show the capture id (directory name), the qualified
   selector and the path.
3. Fix the phantom-listing defect: if `SingleChronicleQueueBuilder.build()` throws after the directory and sidecar
   were created, delete them, so the introspection listing never shows a capture that never recorded. Add a test
   that forces the build failure and asserts the directory is gone and `listAvailable` does not show it.

## B. Keys, selectors and metric names

4. `currentSinks` keys, `liveSinks` keys and the per-processor record counter name (`audit.<selector>.records`)
   changed from the bare processor name to the percent-encoded `group/name` selector, and counters and timestamps
   now restart per session. Document each rename in the release notes as a breaking change for dashboards and REST
   consumers (svc-admin-web), and say exactly what the key looks like for a processor `p` in group `g`.
5. Confirm and test that the bare name still works everywhere for the common case of a unique name, including
   `audit.start p`, `audit.stop p`, `audit.status p`, `isRecording("p")` and `currentSink("p")`, and that the
   error text for an ambiguous bare name tells the operator the exact selector to type.
6. The selector and ambiguity semantics in `control-plane-contracts.md` (~l.562-567) are stated for all
   `MongooseAuditCaptureService` implementations but only `ChronicleAuditCaptureService.selectedSink` implements
   them. Qualify the text to the built-in service and state in the interface javadoc that selector support is the
   implementor's responsibility.

## C. Shutdown with capture enabled

7. A failed physical Chronicle close now leaves `shutdown()` incomplete permanently (failed closes are retained,
   not retried). For this client that is a deploy that never reports STOPPED until the orchestrator kills it. Decide
   and document the operational story: either (a) a bounded retry of the physical close during a resumed stop, or
   (b) an explicit runbook entry in the release notes and contracts saying what an operator does with
   `StopResult.groupsRunning` naming unresolved capture resources, including how to read
   `captureCloseFailures()`. Add a test that forces one Chronicle close failure at shutdown and asserts the exact
   StopResult content and the exact log/audit records produced.
8. A failed post-release detach (the retry in `ComposingEventProcessorAgent.closeCapture`, ~l.988-1010) keeps the
   slot but is not listed in `captureCloseFailures()`, so the shutdown message says `failed closes={}` while a
   slot is held, and `CaptureRules` removes the resource from the control table while the agent still holds it.
   Record the detach failure on the permit so every unresolved physical resource is listed, and keep the control
   table in step with the agent. Test: `ChronicleGroupInlineTest.failedDetachRetainsSlotButIsNotListedAsCloseFailure`
   in the review reproductions (currently passes = defect present; invert its assertion once fixed).
9. Document, in the Shutdown section, the order in which capture is uninstalled relative to processor `stop()`
   and `tearDown()` (uninstall before, physical close after), so support staff know that records emitted during
   `stop()` go to the restored listener and not to the capture.

## D. Boot and autoStart

10. YAML `auditCapture.autoStart` is now a request applied on the group thread after the processor add. Test the
    real boot path: a YAML configuration with capture enabled and autoStart for two processors, assert both are
    recording before `start()` returns to the caller (or document precisely when they are), and assert the
    `audit.status` output. Document that `audit.start` issued from an agent thread means "requested" and that
    `audit.status` is the truth.
11. Confirm capture is not started on a processor whose boot `start()` failed under fail-fast, and that no
    `capture-*` directory is left behind for it.

## E. Retention and disk

12. One directory per session instead of one per processor changes accumulation on disk. State the retention
    responsibility (Mongoose does not prune) in the docs, and check that whatever this client uses to prune the
    capture root still works with the new names and the sidecar.

## F. Direct-embedding defect (fix even though the client goes through MongooseServer)

13. Unrouted `processorStopped(group, name, flow)` (`ChronicleAuditCaptureService.java:506-510`) falls through to
    the legacy `processorStopped(name, flow)` (~l.536-543), which calls `stop(name)` by bare name and can end a
    newer generation's capture. Only fall through when a standalone binding exists for that exact flow, and make
    the legacy path end only `sinks.get(name)`. Tests: `ChronicleCaptureEdgeTest.retiringAnOldGenerationMustNotStopTheNewGenerationsCapture`
    and `ChronicleGroupInlineTest.replacementGenerationWithoutServerLeavesRetiredBindingAttached` (currently fail).
14. `attachScoped` refuses a duplicate identity but not a second identity on the same `DataFlow`; mirror the
    legacy same-flow check.

## G. The new control audit as a support artefact

15. The control audit export (`ControlAuditSink.export`, `ControlAuditExport.verify`) plus
    `GroupControlProcessor.graphml` and `control-processor.xml` are new inputs for the analyser. Write a short
    how-to under `docs/example/how-to/` showing: export a group's control audit from a running server through the
    admin surface (add the admin command if it does not exist), verify it, open it in the analyser with the graph,
    and follow one request by `rootId`. Note that `COMPLETE` means the export lost nothing, not that behaviour was
    correct.

## H. Acceptance for this client's migration

16. Add one end-to-end test that mirrors their shape: YAML boot, several compiled processors in two groups with
    the same processor name in each group, capture autoStart on, run events, start and stop one capture through
    the admin commands, list captures, shut down, and assert: the StopResult is complete, every `capture-*`
    directory has a sidecar, the listing joins live and historical captures by capture id, and the exact
    configured listener is restored on each processor after each stop.
17. Update the release notes "New API" list to include `setCaptureResourceLimit`, `captureResourceSlotsInUse`,
    `captureCloseFailures`, `setCaptureIo` and `setAuditCaptureResources`.

## I. Overload policy (issue #77): make the data-path loss contract explicit before this ships

The lifecycle contract is now explicit; the data-path loss contract is not. For a market maker, dropped order,
credit or hedge events are a correctness problem, not a throughput statistic. This PR does not change the policy,
but it must not ship a stronger control-plane contract while the overload behaviour stays implicit.

18. Document the current behaviour exactly, in `docs/reference/control-plane-contracts.md` (new section "Overload
    and loss") and in the release notes "Known limits": a publisher spins at most 10 ms on a full target queue
    (`EventToQueuePublisher.java:462`, `maxSpinNs`), then drops the item for that queue only, increments
    `droppedCount` (`l.478`) and logs a warning when enabled (`l.483-485`); other target queues still receive it;
    the dropped item's pool reference is released. State what the operator sees (the exact log line, the counter
    name and where it is exposed) and that there is no backpressure to the source.
19. `EventSource.SlowConsumerStrategy` (`EventSource.java:24`: DISCONNECT, EXIT_PROCESS, BACKOFF) is configurable
    from `EventFeedConfig` and `HandlerPipeConfig` but not honoured by the publish path. Either implement it or
    mark it deprecated with the documentation saying it has no effect. Silently accepting a setting that does
    nothing is the worst of the three options.
20. Blocking publish ships with the failure design, issue #77 (#78 was consolidated into it), not in this PR. In
    this PR: document the per-feed policy choice that #77 will deliver, (a) drop after bounded spin (today) or
    (b) block the publisher until space is available (no loss, latency propagates to the source), with the
    recommendation per event class (market data: drop acceptable; orders, fills, credit, hedge instructions: never
    drop), and link #77 from the contracts and release notes. The per-feed classification for this client is a
    release gate, not a #76 merge gate: if #77 is not delivered before the client's release, provide a written
    sizing argument per never-drop feed showing the 10 ms spin bound cannot be reached at peak observed rates.
21. Expose drops as a first-class health signal: the drop counter per queue through `MongooseCountersService`
    and the health service, and a control-audit or fallback record when a queue first drops so the analyser can
    correlate loss with lifecycle events. Add a test that fills a processor queue, asserts the drop is counted and
    surfaced, and asserts no reordering or duplication of the events that were delivered.
22. State in the contracts what the shutdown drain phase does with items still queued when `shutdownDrainTimeoutMillis`
    expires (they are dropped; `LifecycleManager.stopPhases` logs the pending count) and how an operator sizes the
    drain window for a feed that must flush.

## J. Shutdown runbook for operators

Write `docs/operations/shutdown-runbook.md` (link it from the contracts Shutdown section and the release notes).
It must be usable by a support engineer at 3 a.m. with only logs, the admin surface and the analyser.

23. The phases, in order, with their individual bounds and what each one does to the client's components:
    startup fence; feeds (event-source-only groups closed, sources removed from shared groups, sources stopped,
    5 s cap); drain (`shutdownDrainTimeoutMillis`, default 2 s, remaining queued events are dropped); processor
    groups closed on helper threads (Agrona interrupts the group thread after `RETRY_CLOSE_TIMEOUT_MS`; a processor
    `stop()` doing I/O will be interrupted); service agent groups; registered and retained services; capture
    `stopAll`. Say explicitly that order connectors lose their feeds before processors stop, so any
    cancel-on-shutdown or flatten logic must not depend on inbound market data.
24. How to read a `StopResult`: `complete`, `handedOff` (call `awaitStopped` from another thread), `blockedStartup`,
    every prefix and bracketed note that can appear in `groupsRunning` (`processor:`, `service:`,
    `<event sources stopping>`, `<audit capture stopping>`, `<audit capture stop failed: ...>`, `<stop failed: ...>`),
    and `servicesLeftRunning`. For each, the likely cause and the next action.
25. Resuming: call `shutdown()` again; what a resumed stop retries (groups, failed group helpers) and what it never
    retries (a service `stop()` that threw, a failed physical capture close) and therefore what stays incomplete
    forever. Give the operator the decision rule for when to stop retrying and let the process manager terminate
    the JVM, and what state on disk (capture directories without a clean close, Chronicle files) to expect after a
    kill, and how the analyser treats a capture that was not closed cleanly.
26. Capture-specific: how to list unresolved physical resources (`captureResourceSlotsInUse`,
    `captureCloseFailures`) when a group is reported running for them, what the `CleanupPending` fallback record
    looks like, and the exact log lines to grep.
27. Timeouts to tune per deployment and their defaults: `shutdownTimeoutMillis` 30 s, `shutdownDrainTimeoutMillis`
    2 s, feed cap 5 s, Agrona close retry 5 s, `controlWaitMillis` 10 s, capture open timeout 30 s. Recommend values
    for a trading deployment that must flatten positions on the way down, and say which of these the process
    manager's grace period must exceed.
28. A worked example: one full shutdown log from the end-to-end test in item 16, annotated phase by phase, and one
    incomplete shutdown (forced capture close failure from item 7) annotated with the operator's actions.
29. Make the runbook testable: the end-to-end test asserts the log lines and StopResult shapes the runbook quotes,
    so the document cannot drift from the code without a test failing.

## K. The full independent review findings (candidate 4f5851e), to be addressed alongside A to J

Severity order. Items marked "engine" are code defects; items marked "test-evidence" are oracle weaknesses that let
engine regressions pass; fix the oracles before rerunning qualification, or the rerun proves little. Reproductions
named below are under `src/test/java/com/telamin/mongoose/extreview/` in the review checkout; copy them into the
branch's test tree (they fail today where stated) and make them pass without weakening any existing assertion.

1. HIGH, test-evidence. A rule that performs its effects but never replies is invisible to the harness, the
   invariant validator and the chaos oracle. ControlDriver's safety net answers FAILED "no reply after its effects
   settled" and writes a fallback FAILED record; `ControlInvariants.java:24, 84-93` accepts any FALLBACK_REPLIES record
   as "the reply" without reading its reason; `ChaosWorkload.java:873-895` only counts FAILED/REFUSED/NOT_RUNNING
   stops; `ControlScenarioHarnessTest.java:219, 232` discards stop ControlRequests. Mutation (LifecycleRules.java:260-265
   stopComplete without Reply): harness 205/205 pass, invariants verified, 10 s chaos pass with 113 FAILED stops.
   Fix: treat driver safety-net fallbacks (reasons "no reply after its effects settled", "effects did not settle",
   "control processor threw", "abandoned") and Anomaly/Dead records as violations; harness records every request it
   sends and asserts each reply is APPLIED or a by-design refusal.
2. HIGH, test-evidence. I15 and I8 cannot see "reply before its effects' outcomes". `ControlInvariants.java:66` skips
   records with cleanup:true, but that flag is set by the engine (`EffectOutcomes.java:68`) whenever the chain is already
   closed, and the Reply closes the chain. I8 (`ControlInvariants.java:175`) only flags failures with seq below the
   reply. Mutation (released() requests Reply(APPLIED) before ReleaseName): harness 204/204, regression 3/3, invariants
   silent; only the golden rule-sequence assertion caught it. Probe
   `InvariantVacuityProbesTest.replyBeforeItsEffectsOutcomeVerifies`. Fix: exempt only effects whose requesting
   Decision seq is after the reply; flag any failed non-cleanup effect of an APPLIED request.
3. HIGH, test-evidence. An APPLIED stop that never releases is not caught by the harness ledger
   (`ControlScenarioHarnessTest.java:311-326` only bounds callback counts and start implies teardown, which the
   eventual group close satisfies). Mutation (onStop STARTED replies APPLIED, no Release): 177/205 seeds pass; the 28
   failures came only from I1 keying on the Decision rule name "stopStarted" (`ControlInvariants.java:210`). Probe
   `appliedStopWithoutReleaseVerifies`. Fix: ledger asserts every bound stop answered APPLIED has its processor
   stopped and torn down and its name released before group close; add an invariant that a stopStarted/closeGroup
   Decision requests Release and the APPLIED reply follows Release and ReleaseName outcomes.
4. MEDIUM, engine (replay). A replayed processor stopped live mid-stream makes complete() true and stopped() null.
   `GroupReplayer.detach` (`replay/GroupReplayer.java:164-186`) records an ended reason only for a stopped cursor,
   pending expected calls or stop reads; a cursor with undelivered entries is dropped by cursors.removeIf. Contract:
   "complete() with a null stopped(...) means every entry was delivered". Repro `ReplayCursorDetachedMidStreamTest`
   fails. Fix: when !c.done(), record an ended reason such as "left the group at entry N of M before its replay
   completed".
5. MEDIUM, engine (replay). The documented status API (replayers().get(g).complete()/stopped()) is not safe off the
   agent thread: cursors is a plain ArrayList (`GroupReplayer.java:33`) iterated by complete/stopped/replays
   (l.351-356, 398-412) while the agent thread adds/removes cursors; Cursor fields are non-volatile. Repro
   `ReplayStatusApiConcurrencyTest` fails (ConcurrentModificationException/NPE within 1.5 s).
   `ChaosReplayStressTest.java:154-160` polls without a guard. Fix: CopyOnWriteArrayList plus volatile
   next/stopped/ended, or publish an immutable status snapshot per doWork.
6. MEDIUM, engine (services). RemovedService.stop() checks supersession only under the OLD name
   (`MongooseServer.java:988-989`). Interleaving: register A=X; removeService(A) waits for a group whose
   @ServiceDeregistered handler is slow; meanwhile registerService(B=X) succeeds (retirement not yet claimed) and the
   group is handed X under B; the handler returns, the withdrawal is confirmed, and X.stop() runs while X is live under
   B. Violates "A service is never stopped while a processor that was given it may still hold it". Repro
   `RetirementOtherNameProbeTest` fails (expected 0 stops, was 1). PR-introduced (base stopped synchronously at
   removal). Fix: supersession = any live registration whose instance() is the same object; or refuse re-registration
   of an instance with an unconfirmed retirement.
7. MEDIUM, engine (capture, unrouted/direct embedding only; not reachable through MongooseServer because the router
   is set). `processorStopped(group,name,flow)` (`ChronicleAuditCaptureService.java:506-510`) falls through to the
   legacy `processorStopped(name,flow)` (l.536-543) which calls stop(name); stop resolves by bare name and, after gen1's
   binding was removed, uniquely finds gen2's scoped sink, ends its recording, restores the listener and closes a queue
   the group still owns. Repros `ChronicleCaptureEdgeTest.retiringAnOldGenerationMustNotStopTheNewGenerationsCapture`
   and `ChronicleGroupInlineTest.replacementGenerationWithoutServerLeavesRetiredBindingAttached` fail. Fix: only fall
   through when a standalone binding exists for that exact flow; make legacy processorStopped end only
   sinks.get(name) via endRecordingIfGone. (Same as item 13 above.)
8. MEDIUM, test-evidence (independence). The harness relies on the engine's own outstanding()
   (`ControlScenarioHarnessTest.java:342`) and the audit, so a request silently dropped after claim is invisible
   unless incidental (mutation dropping 1/7 stops: 198/200 pass). `ControlDriver.sweep` had zero deterministic
   invocations across harness and regression runs. Anomaly, Dead, CaptureOrphan and abandoned-effect records are
   never violations (probe `anomalyAndDeadFallbackRecordsVerify`: a dead group's export verifies). Fix: harness keeps
   every ControlRequest and asserts reply().isDone(); add a sweep-exercising scenario; make engine-declared
   anomalies violations.
9. MEDIUM, test-evidence (coverage). Lifetime capture leaks are masked: a stale open never closed by the control
   processor is closed by releaseLeftoverCaptures at group close (`ComposingEventProcessorAgent.java:1076-1110`),
   whose offline fallback records satisfy I4 (mutation: 9 stale opens, 205/205 pass). The bundled chaos export (seed
   19796) contains no failed closes/uninstalls, timeouts, NotRunning, Dead or Anomaly records and exactly one Release
   per closeGroup, so I1, I4-failed-close, I7, I11, I12-offline, I14, I16 are vacuous on it; the harness injects only
   failCloses/rejectNextOpen/rejectNextClose (l.268-270), never failed uninstall/open/install. Fix: assert
   captureResourcesHeld()==0 and zero offline records before close in harness/chaos; extend fault injection.
10. LOW-MEDIUM, engine (replay). A recorded start() failure consumes a stream ordinal
    (`ComposingEventProcessorAgent.java:768`) but records nothing (catch at 787-790 never tells the recorder); the
    stream holds provenance only. In a replay where start() succeeds, cursor p completes at once, the re-add becomes
    p#2 and is never attached, the processor receives a live service, and complete()=true with stopped()=null. Repro
    `RecordedStartFailureReplaysAsSuccessTest` fails. Fix: write a Failed marker for a recorded flow whose start
    threw, so replay stops at "the recorded run failed here".
11. LOW, engine (capture reporting). A failed post-release detach retry in the CloseCapture effect
    (`ComposingEventProcessorAgent.java:988-1010`) correctly retains the handle and slot, but captureCloseFailures()
    (l.410-416) stays empty, CleanupPending reports failedPhysicalCloses 0 with captureResourcesLeft 1, and
    CaptureRules removes the resource from the control table while the agent still holds it. Repro
    `ChronicleGroupInlineTest.failedDetachRetainsSlotButIsNotListedAsCloseFailure` (passes = confirmed). Fix: record
    the detach failure on the permit; do not removeResource while the handle is held. (Same as item 8 above.)
12. LOW, compatibility/doc. ReplayEntry is sealed (`replay/ReplayEntry.java:21`) and gained
    Stopped/ServiceChanged/ControlCall; CsvReplayStore gained kinds STOPPED/SERVICE/CONTROL and a BEGAN line, so
    exhaustive switches in third-party code stop compiling and older builds cannot read new recordings. Release notes
    claim only backward read. ComposingEventProcessorAgent changed four public methods from void to ControlRequest
    (binary incompatible, not noted). Fix: document; note @Experimental.
13. LOW, doc vs code (plugins). `control-plane-contracts.md:562-567` states selector and ambiguity semantics for
    start/stop/isRecording unconditionally, but only ChronicleAuditCaptureService.selectedSink implements them; a
    name-keyed plugin inheriting the scoped defaults (`MongooseAuditCaptureService.java:98-109`) aliases across groups
    and receives "g%2Fa/p" selectors it cannot resolve. A custom CaptureRouter without sendScoped silently loses
    auditCapture.autoStart (startScoped at `ChronicleAuditCaptureService.java:237-243` calls the throwing default;
    the server logs a warning). Fix: qualify the contract to the built-in service; document autoStart behaviour.
14. LOW, hardening and doc nits. attachScoped (`ChronicleAuditCaptureService.java:245-254`) refuses duplicate
    identity but not a second identity on the same DataFlow (repro `twoScopedIdentitiesOnOneFlowClobberEachOther`);
    openQueue (l.735-765) leaves a capture dir and sidecar if the Chronicle build throws, contrary to "openCapture
    must close anything created before throwing"; processorDropped (`MongooseServer.java:457`) frees the name
    reservation during Release, before the FAILED reply, while the contract says only the control processor frees
    it; a suppressed replay withdrawal is always retained until shutdown though the contract implies release when
    the stream withdraws; MongooseServerController javadoc for registerService/startService/stopService lacks the new
    IllegalStateException while stopping; CsvReplayStore (l.212, 214) fails a recording closed on a newline in a
    ControlCall outcome.

Verified sound by the review (do not re-litigate; keep the regressions that protect them): terminal admission
(outstanding-add vs state-set/sweep), beginClosing plus the sender's second ended() check, orphan completions at
CLOSED/DEAD, claimed-request ownership after join, Agrona 2.3.0 onStart Error path covered by the liveness checker,
re-entrancy via batchEnd queuing, capture permit accounting, shutdown phases/resume/retry of failed group helpers,
pool fix 2554fe2, 7a591c1 semantically identical to its predecessor, 0de46da an oracle fix only, replay version
identity fail-closed with a wall-clock-bounded wait, InMemoryReplayStore snapshot immutable and append linear,
stream-key escaping, selector encode/decode round trips, DirAuditIntrospectionService invalidation CAS.

Review evidence on 4f5851e: `mvn test` BUILD SUCCESS, 931 tests, 0 failures, 14 skipped; ControlScenarioHarnessTest
seeds 7001-7400 405 pass; ControlPlaneChaosStressTest 20 s seed 31337 pass with complete, violation-free audits;
ShutdownStormStressTest 6 cycles pass. Remaining uncertainty: no soak on the final commit; pinned-core Linux
performance open; replay status stale-read half argued from the memory model only; finding 7 reachable only without
the server; incomplete-stop-then-resume with a live Chronicle queue untested; third-party plugin compatibility judged
from signatures.

Suggested order of work: fix engine items 4, 5, 6, 7, 10, 11, 14 first (small and localised); then the oracle items
1, 2, 3, 8, 9; then the docs items 12, 13 and sections A to J; then freeze one commit and rerun the whole
qualification campaign (full suite, harness seed sweep, chaos, soak, replay, shutdown storm) on that exact SHA,
keeping the exports.

## L. Fluxtion issue: the generated processor can wedge on a throw (file in the Fluxtion repo, then adopt here)

Observed in the generated `src/main/java/com/telamin/mongoose/control/generated/GroupControlProcessor.java`
(Fluxtion runtime 1.1.2), and true of every generated processor:

- `processEvent` (~l.299-313) does `processing = true; onEventInternal(event); if (callbacksPending)
  dispatchQueuedCallbacks(); processing = false;` with no try/finally.
- `batchEnd` (~l.690-696) does `processing = true; <@OnBatchEnd nodes>; afterEvent(); dispatchQueuedCallbacks();
  processing = false;` with no try/finally.
- `runInEventCycle` (~l.715-741) already has the correct shape: an outer finally closes the cycle (`afterEvent`,
  `dispatchQueuedCallbacks`) and an inner finally clears `processing` innermost.

Failure: a node handler throws inside a cycle; `processing` stays true; every later `onEvent` takes the re-entrant
branch `callbackDispatcher.queueReentrantEvent(event)` and the event is queued for a dispatch that never happens.
No exception, no log, every subsequent event silently swallowed. Mongoose compensates in `EffectQueue`
(catch-all around every effect and around `processAsNewEventCycle`), `ControlDriver.dispatch`/`settle`
(`controlWedged`, `markDead`, FAILED reply) and `ControlDriver.drain` (refuses a wedged processor). Those guards are
load-bearing today and should become belt-and-braces.

Issue content:

30. Generate `processEvent`, `batchEnd` and the service-call bracket (`beforeServiceCall`/`afterServiceCall`) with the
    same try/finally shape as `runInEventCycle`: outer finally runs `afterEvent()` and `dispatchQueuedCallbacks()`,
    inner finally clears `processing`. A throw from a handler must leave the processor able to dispatch the next
    event.
31. Define the re-entrant queue's behaviour on an exceptional exit. Today, if a queued re-entrant event's handler
    throws during `dispatchQueuedCallbacks`, confirm whether the remaining queued events stay in the dispatcher and
    are dispatched at the end of an unrelated later cycle (stale, out of order). Choose and document: drain-and-discard
    on exceptional exit, with one audit record naming the discarded count, or dispatch them in the finally. Do not
    leave it implicit.
32. Close the audit record on the throw path. `afterEvent()` is what completes the cycle's `EventLogger` record;
    on a throw today the record is left open and the analyser shows a gap. The finally must emit the record with the
    failure visible (node, handler, exception text) so a failed cycle is evidence, not absence.
33. Keep `runInEventCycle`'s existing not-re-entrant check, and add a generated test (next to
    `GeneratedSourceOnOldRuntimeTest`) that throws from a handler, asserts the next `onEvent` is dispatched, asserts
    the re-entrant queue is in the documented state, and asserts the audit record for the failed cycle exists.
34. One record per node per cycle: `CaptureRules` works around this by folding notes into fields of a single
    Decision. Consider whether `EventLogNode` should support more than one record per cycle, or document the
    constraint explicitly in the audit docs so other control-style processors do not rediscover it.
35. Once the runtime with 30-33 is adopted in Mongoose: regenerate `GroupControlProcessor`, keep the Mongoose guards
    but add a test asserting the "control processor threw" dead path is never reached by a throwing rule (the
    processor now recovers), and simplify `EffectQueue`/`ControlDriver` where the generator's guarantee makes
    compensation redundant. Cite the lines removed.

When done, list what you changed, what you decided not to change and why, and which of the review reproductions
now pass. Do not weaken any existing regression assertion.
