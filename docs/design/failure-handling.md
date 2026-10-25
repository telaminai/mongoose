# Failure handling: ownership, containment, recovery and evidence

Design for [issue #77](https://github.com/telaminai/mongoose/issues/77). It turns the failure catalogue from PR #76's
reviews into one model, so that later changes are checked against stated rules instead of rediscovered by review.

**Status.** This is a design document on branch `feature/77-failure-model`, based on `feature/control-plane` at
`dfbfdd9` (Mongoose 1.0.33-rc1 candidate, Fluxtion runtime 1.1.2 / compiler 1.0.79). It is meant for review after
PR #76 ships. It does not approve the release and does not widen it.

Every guarantee carries one of three status marks:

| Mark | Meaning |
| --- | --- |
| **[Implemented]** | True at the base commit. The cited test exists at the base commit and passes in the full suite (1013 tests, 0 failures, 14 skipped at `dfbfdd9`). |
| **[Implemented: M2]** | True on this branch after milestone 2. The cited test was added or changed on this branch and passes in its full suite. |
| **[Proposed: Mn]** | Agreed direction, implemented on this branch in milestone *n* (see [Milestones](#milestones)). It is not true until that milestone lands. |
| **[Unsupported]** | A deliberate non-guarantee. Callers must not rely on it. |

Decisions marked **working decision, owner to confirm** are recommendations from the reviewer response to #77. The
product owner has not formally confirmed them.

Related documents: [control-plane contracts](../reference/control-plane-contracts.md),
[capture resource identity](capture-resource-identity.md), [shutdown runbook](../operations/shutdown-runbook.md),
and Fluxtion's
[failed event cycle policy](https://github.com/telaminai/fluxtion/blob/main/docs/runtime/failed-event-cycles.md).
Overload and back-pressure are out of scope here; they moved to
[issue #79](https://github.com/telaminai/mongoose/issues/79).

## Contents

- [Principles](#principles)
- [Facts kept apart](#facts-kept-apart)
- [Failure domains](#failure-domains)
- [Failure matrix](#failure-matrix)
- [Terminal-state contract](#terminal-state-contract)
- [Failure taxonomy](#failure-taxonomy)
- [Retry classification per effect](#retry-classification-per-effect)
- [Bounds](#bounds)
- [Supervision and callback failure](#supervision-and-callback-failure)
- [Audit failure](#audit-failure)
- [Fault-injection test API](#fault-injection-test-api)
- [Fluxtion-level concerns](#fluxtion-level-concerns)
- [Milestones](#milestones)
- [Open questions for the owner](#open-questions-for-the-owner)

## Principles

These are the seven principles from the #78 catalogue, folded into #77, with the reviewer's amendments.

| # | Principle | Status |
| --- | --- | --- |
| P1 | **Every request ends.** It ends with exactly one reply, within its deadline, including across death, close and shutdown. *Amendment:* the reply has an **identified author**: a rule, the driver's safety net, or the shutdown/death path. | One reply: **[Implemented]** (`ControlTerminalAdmissionTest`, `ControlScenarioHarnessTest#terminalSweepAnswersEveryIndependentlyRetainedUnpublishedRequest`, `ControlTerminationAndAdmissionTest#errorHandlerDeathCompletesClaimsWithoutAnotherSenderAndAllowsReentrantCompletion`). Author: a driver-authored reply is marked `replyAuthor: DRIVER` in the audit **[Implemented]** (`IndependentEvidenceMutationTest#structuredDriverFallbackIsRejectedRegardlessOfReasonWording`); every reply's `kind` names its author class: `FAILED_RULE` / `FAILED_DRIVER_FALLBACK` are the driver's safety net, `FAILED_DEAD` / `FAILED_SHUTDOWN` the death or close path, any other kind a rule or the sender **[Implemented: M2]** (`FailureKindFlowTest`, `ControlScenarioHarnessTest`). |
| P2 | **Ownership is explicit and exact.** Every resource, name and service instance has one owner, identified by (group, generation) or by the instance. Ownership passes only on a confirmed release. An unconfirmed release keeps ownership until shutdown, which reports it. | **[Implemented]** for processors, names, captures and services (rows R7, R8, M1, M4 below). |
| P3 | **Physical work outlives logical decisions.** A logical timeout or cancellation never releases what a physical operation holds. Bounds are permits held until the physical operation returns. | **[Implemented]** (row R6). |
| P4 | **One deadline per call.** It is computed at entry and spent on admission, locks and reply together. No unbounded wait on an agent thread, and no wait under a lock the stop path needs. | **[Implemented]** (row R2). |
| P5 | **Honest outcomes.** APPLIED means it happened. Refusal, failure, "not yet" and "cannot verify" are distinct and each carries a reason. *Amendment:* **APPLIED for a stop means the Release effect was observed**, not that a rule said so. | APPLIED-stop: **[Implemented]** by audit invariant I17 and the harness's caller-side ledger (`IndependentEvidenceMutationTest#appliedStopWithoutReleaseIsRejected`, `#stopObligationDoesNotDependOnRuleSpelling`, `ControlScenarioHarnessTest#nameOnlyStopLedgerRejectsAReplyBeforeReleaseWithoutConsultingAudit`). Distinct outcomes: `ReplyOutcome` has four values **[Implemented]**; a structured `FailureKind` beside the reason **[Implemented: M2]** (`FailureKindFlowTest#ruleAuthoredRepliesCarryTheirKindToTheCallerAndTheAudit`). |
| P6 | **Fail closed in replay and evidence.** Anything that cannot be reproduced or verified stops with a reason. Audit gaps are reported, never smoothed over. | Replay: **[Implemented]** (row R9). Evidence: an incomplete audit makes invariants unverifiable **[Implemented]** (`ControlAuditExportTest#sinkFailureSurvivesExportAndFalseCompleteClaimIsRejected`); the runtime reaction to a failed audit sink **[Proposed: M3]** ([Audit failure](#audit-failure)). |
| P7 | **Every failure is evidence.** It has a recorded outcome, attributed to its owner, checkable by an invariant with a mutation test. *Amendment:* **absence is not evidence.** An oracle reports which invariants were vacuous on an export, that is, had zero records to judge. | Recorded outcome and mutation tests: **[Implemented]** (`ControlInvariantsMutationTest`, `IndependentEvidenceMutationTest`, `docs/reports/pr76-third-review-followup-2026-10-25/run-source-mutations.py`). Vacuity reporting: **[Proposed: M6]**. |

## Facts kept apart

These four facts are related but never interchangeable. A test or an operator reads each from its own source.

| Fact | Values | Where it is read |
| --- | --- | --- |
| Command disposition | refused, accepted/pending, applied, failed, detached | `ControlReply.outcome` and `ControlReply.kind`; a `null` from a timed-out wait means detached |
| Runtime availability | `NEW`, `STARTING`, `RUNNING`, `CLOSING`, `CLOSED`, `DEAD` | `ControlDriver.GroupState`, `controlGroupStatus(group).state()` |
| Physical resource status | acquired, installed, closing, confirmed closed, close failed (unknown) | `controlGroupStatus`: `captureResourcesHeld`, `captureResourceSlotsInUse`, `captureCloseFailures`, `captureCleanupFailures` |
| Evidence status | complete, incomplete, unknown | `ControlAuditSink.isComplete()`, `ControlAuditExport.verify(path)` |

Complete evidence is necessary for an invariant PASS, not sufficient: it says the record is whole, not that behaviour
was correct.

## Failure domains

| Domain | Owner of decisions | Fails how | Contained to |
| --- | --- | --- | --- |
| Data processor (handwritten `DataFlow`) | its group's agent | handler, lifecycle or service callback throws | the processor: retried by `RetryPolicy` for dispatch, reported and released for lifecycle |
| Data processor (Fluxtion-generated, `InternalEventProcessor`) | its group's agent | any generated callback throws | the processor: quarantined, never retried or restarted |
| Processor group | its agent thread and control processor | agent thread ends abnormally; control processor throws or wedges | the group: `DEAD`; other groups keep running |
| Control processor | the group's driver | a rule throws, effects do not settle | the group: driver answers and marks it dead |
| Service | the server's registry, under `serviceLock` | `start`/`stop` throws, retirement races re-registration | the service instance: retained, reported, never stopped twice |
| Feed / sink | the event flow manager | full queue, failing source | the queue binding (drop accounting: see #79) |
| Capture I/O | the group's capture permits | open times out or throws, install/uninstall/close throws | the resource: permit and slot held until confirmed |
| Control audit | the group's `ControlAuditSink` | ring overflow, sink conversion failure | the evidence: marked incomplete |
| Replay | the group's `GroupReplayer` | input, stream or service identity cannot be matched; recorded failure | one processor's replay: stopped with a reason |
| Server | the `MongooseServer` lifecycle | startup step blocks, stop exceeds its deadline | the stop: `StopResult` incomplete, resumable |
| Process / JVM / host | none | crash, kill, power loss | nothing in memory survives |

Ordinary exceptions are contained to the narrowest domain above. `VirtualMachineError` is never retried or swallowed:
the agent answers what it claimed, then rethrows, and the group dies
(`RetryOnlyTheFailedProcessorTest#aVirtualMachineErrorFromAHandlerIsNeitherRetriedNorSwallowed`,
`FatalStopReviewTest`) **[Implemented]**. An error thrown by an error-reporting callback ends the agent loop without
`onClose`; the shared liveness checker finds the dead thread, joins it and answers its claimed requests
(`ControlTerminationAndAdmissionTest#errorHandlerDeathCompletesClaimsWithoutAnotherSenderAndAllowsReentrantCompletion`)
**[Implemented]**. Interrupts are not a cancellation signal for control work: an interrupted caller detaches
(`AgentHandoffTest#workAlreadyRunning_whenItsCallerIsInterrupted_isWaitedFor`) **[Implemented]**.

## Failure matrix

Rows R1 to R11 are the matrix in the issue. Rows M1 to M8 are the reviewer's missing rows. Rows T1 to T3 come from the
third review. "Status" refers to the containment and evidence columns together; where they differ, both are given.

### Rows from the issue

| # | Trigger | Containment and ownership | Caller and operator evidence | Recovery boundary | Status |
| --- | --- | --- | --- | --- | --- |
| R1 | Admission refused or mailbox full | Nothing applied; the name reserved for it is released (`onUnapplied`) | `REFUSED`, and a `Refused` fallback audit record | Submit a new request | **[Implemented]** `AgentControlQueueFullTest`, `ReviewRefusedStopTest#aStopRefusedForAFullMailboxDoesNotRunItsCompletedCallback`, `IntegrationReleaseRegressionTest#rejectedEnqueueDoesNotReserveTheProcessorName` |
| R2 | Caller deadline expires after admission | The accepted request keeps ownership and may finish | A void call returns (or throws if it was never admitted); it never claims cancellation | Observe the original outcome; do not resubmit blindly | **[Implemented]** `HuntVoidDeadlineTest#voidControlCallsMustSpendOneDeadlineOnAdmissionAndReply`, `ControlTerminationAndAdmissionTest#admissionTimeIsDeductedFromTheRemainingVoidReplyWait`, `AuditSinkOnAgentThreadTest#aStartThatTimesOut_changesNothing_evenWhenItsInstallRunsLater` |
| R3 | Handler or service callback throws | Handwritten processor: retried, then dropped with a report. Generated processor: quarantined (routes and timers removed, instance and name retained, no retry) | ERROR report, `ProcessorQuarantined` audit record, `controlGroupStatus(..).quarantinedProcessors()` | Operator; no automatic restart (working decision 1) | **[Implemented]** `GeneratedProcessorFailureIsolationTest#generatedAuditThrowIsQuarantinedWithoutRetryAndOtherTargetsContinue`, `#realAgentKeepsHealthyProcessorsRunningAndReportsQuarantinedOwnership`, `#generatedServiceFailureQuarantinesTheProcessorBeforeItsNextDataCycle`, `RetryOnlyTheFailedProcessorTest` |
| R4 | Agent terminates, including outside a wrapper | Admission stops (`DEAD`). Unclaimed requests are swept; claimed ones are answered only by the agent or after its thread is joined. Agent-owned state is reclaimed only after the join | `NOT_RUNNING` / `FAILED`, `Dead` audit record, `CleanupPending` / `CleanedUp` | Post-join backstop, then shutdown | **[Implemented]** `ControlTerminalAdmissionTest#aGroupWhoseThreadDiesIsCleanedUpAndLaterSendersAreAnswered`, `#aSenderThatFindsTheAgentThreadDeadRunsTheDeathPath`, `FollowupConcurrencyReviewTest#deathMustCompleteEarlierClaimedAsyncRequestWithoutAnotherServerCall`, `HuntGroupFailureModesTest#workHandedToADeadGroupMustBeRefusedNotRunOnTheCaller`, `EarlyStartDeathReviewTest#failureBeforeThreadCaptureStillAnswersAcceptedRequest` |
| R5 | Start, stop or teardown throws or never returns | Attempted steps are tracked; a callback already attempted is never repeated; services a still-running group may use are retained | `StopResult` names unfinished groups and services; the server stays `STOPPING` | Call `shutdown()` again to resume; no repeated side-effectful callback | **[Implemented]** `BoundedShutdownTest#aGroupThatCannotCloseInTimeIsReportedServicesKeepRunningAndAResumedStopCompletes`, `FatalStopReviewTest#fatalTeardownMustContinueCleanupWithoutRepeatingCallbacks`, `OrderedServerStopTest#aProcessorFailingToStopIsReportedAndTheOthersStillStop`, `ShutdownAdmissionReviewTest#throwingServiceStopCannotBeReportedAsCompleteOrRetriedBlindly`. Java cannot pre-empt user code: a callback that never returns is reported, not stopped **[Unsupported]** |
| R6 | Capture open times out | The logical request fails; the physical open keeps its permit until it returns; a late resource is closed, never installed | `FAILED` (`open timed out`); the pending physical operation stays visible in `captureResourceSlotsInUse` | Compensation of the late resource | **[Implemented]** `CaptureAsEffectsTest#anOpenThatTimesOutIsAnsweredFailedAndItsLateResourceClosed`, `#lateOpenAfterStopAndReplacementIsCompensatedNeverInstalled`, `CapturePermitRegressionTest#rejectedTimeoutSchedulingDoesNotLeakPhysicalAdmission`, `IndependentReviewControlProbesTest#anOpenWhoseTimerRefusesMustReturnItsPermit` |
| R7 | Physical close throws | Ownership and the resource slot are retained; closure is never inferred | Resource ID, operation, failure, `captureCloseFailures()`, `StopResult` incomplete; invariant I4 reports it unverifiable | Adapter-specific verified recovery; no generic automatic retry | **[Implemented]** `CaptureAsEffectsTest#aCloseThatFailsIsRecordedAndCountedOnce`, `CapturePhysicalShutdownTest#failedPhysicalCloseKeepsShutdownIncompleteWithoutRepeatingCallback`, `ChronicleShutdownFailureEvidenceTest#failedChronicleCloseKeepsExactStopResultIncompleteAndProducesOperatorEvidence`, `ChaosAuditVerdictTest#completeRecordWithFailedPhysicalClosureCannotPassChaos` |
| R8 | Service retirement races re-registration | One physical instance is never stopped by an obsolete owner once it is live again; admission and the stop claim are atomic; callbacks run outside publication locks | Retirement decision; failed physical stop reported by shutdown | Re-registration claims the instance | **[Implemented]** `HuntServerFailureModesTest#deferredRetirementMustNotStopAnInstanceRegisteredAgain`, `RetirementReregistrationRaceTest#reregistrationCannotRacePastAnAlreadyClaimedRetirementStop`, `ServiceAliasRetirementProbeTest#failedPhysicalStopIsStillReportedByShutdown` |
| R9 | Replay diverges or cannot identify an input or service | That processor's replay stops with a reason; no live controller side effect | `replayer.stopped(processor)` with position and expected/observed identity | Fix configuration or create a new verified replay context | Stop with reason: **[Implemented]** `ReplayControlAndProvenanceTest#aReplayedControlRequestThatDiffersIsADivergence`, `ReplayIdentityTest#aLaterIncarnationWithNoRecordedStreamIsStoppedLoudly`, `IndependentReviewReplayProbesTest#aRecordedWithdrawalOfAServiceTheReplayedProcessorNeverHeldMustBeReportedAsDivergence`. Structured stop kind (`GroupReplayer.stopKind`): **[Implemented: M2]** `ReplayIdentityTest#aLaterIncarnationWithNoRecordedStreamIsStoppedLoudly`, `ReplayControlAndProvenanceTest#aReplayedControlRequestThatDiffersIsADivergence`, `ReplayRecordingAcceptanceTest#D4_aFailedDispatchIsMarked_andTheReplayStopsThere` |
| R10 | Audit writer fails or evidence is dropped | Runtime policy per working decision 2 | Incomplete status, `sinkFailures`, manifest `complete: false`; never an invariant PASS | See [Audit failure](#audit-failure) | Evidence side: **[Implemented]** `ControlAuditExportTest#sinkFailureSurvivesExportAndFalseCompleteClaimIsRejected`, `#droppedFallbackWithoutDecisionSequenceGapIsStillIncomplete`. Runtime reaction (DEGRADED, evidence marker): **[Proposed: M3]** |
| R11 | Process, JVM or host dies | In-memory ownership and futures cannot promise recovery | Only what was durably written: Chronicle capture files, exported audit snapshots, replay stores | Restart from configuration; no implied exactly-once external effects | **[Unsupported]**: no durable control state, no recovery of in-flight requests |

### Rows the reviewer added

| # | Trigger | Containment and ownership | Evidence | Status |
| --- | --- | --- | --- | --- |
| M1 | Service retired under name A while the same instance is re-registered under name B | Supersession is by instance, not by name | Retirement record names the instance and the decision | **[Implemented]** `RetirementOtherNameProbeTest#retirementOfOldNameMustNotStopInstanceLiveUnderAnotherName` (extreview) |
| M2 | Processor stopped live while a replay cursor still holds undelivered entries | Detaching a cursor with work left is a stopped outcome with a reason | `complete()` false or `stopped()` non-null | **[Implemented]** `ReplayCursorDetachedMidStreamTest#aLiveStopMidStreamMustNotReadAsACompleteSuccessfulReplay` |
| M3 | `start()` throws for a recorded processor | The failure is a recorded `Failed` entry at the ordinal it consumed | Replay of that stream stops ("the recorded run failed here") | **[Implemented]** `RecordedStartFailureReplaysAsSuccessTest#aRecordedStartFailureMustNotLeaveAStreamThatReplaysAsSuccess`, `RecordedStartupFailureTest#aFailureAfterRecordingAttachesLeavesADurableMarker` |
| M4 | Legacy name-keyed capture stop reaches a scoped sink | Stop by bare name refuses when the only match is a different generation | Refusal names the protected generation | **[Implemented]** `ChronicleCaptureEdgeTest#retiringAnOldGenerationMustNotStopTheNewGenerationsCapture`, `#aLegacyRouterMustNotSilentlyDowngradeRegistrationScopedStart` (direct embedding only; unreachable through `MongooseServer`) |
| M5 | Physical detach retry fails after the logical release | Handle and slot retained; listed by `captureCloseFailures()`/`captureCleanupFailures()`; the control table keeps the row | `CleanupPending.failedPhysicalCloses` and `failedCaptureDetaches` | **[Implemented]** `CaptureAsEffectsTest#failedOfflineUninstallRetainsOwnershipForGroupCleanup`, `ChronicleGroupInlineTest#failedDetachRetainsItsResourceAndReportsCleanupFailure` |
| M6 | A rule performs effects but never replies | The driver's safety net answers `FAILED` | The fallback is classified as a driver reply, distinct from a rule reply | `replyAuthor: DRIVER` marker: **[Implemented]** `IndependentEvidenceMutationTest#structuredDriverFallbackIsRejectedRegardlessOfReasonWording`. Live replies and exports classified by `kind`, never reason text: **[Implemented: M2]** `ControlScenarioHarnessTest` (ledger), `FailureKindEvidenceMutationTest#anUnhealthyKindOnAFallbackReplyIsRejectedWhateverItsWording` |
| M7 | Status API read off the agent thread | Status views are safe from any thread, or documented agent-only | n/a | **[Implemented]** `ReplayStatusApiConcurrencyTest#completeAndStoppedAreSafeToPollWhileProcessorsAttachAndDetach`, `ReplayStatusPublicationTest`; `controlGroupStatus` is documented weakly consistent |
| M8 | Group close reclaims captures the control processor left open | Leftover release is a recovery, recorded as such | Fallback record marked as a leftover; the oracle counts leftovers as violations unless the scenario injected the leak | Reclaim and record: **[Implemented]** `CaptureAsEffectsTest#aDeadControlProcessorsGroupStillClosesItsCaptures`. Leftover marker (`leftover: true`, `kind: LEFTOVER_RECOVERED`) and oracle invariant I19: **[Implemented: M2]** same test, `FailureKindEvidenceMutationTest#aLeftoverRecoveryIsRejected` |

### Rows from the third review

| # | Trigger | Containment | Status |
| --- | --- | --- | --- |
| T1 | A generated data processor throws; the queue agent's retry used to hand the event back to a wedged graph and report recovery | Generated processors are quarantined without retry; the retry applies only to handwritten `DataFlow` | **[Implemented]** `GeneratedProcessorFailureIsolationTest#generatedAuditThrowIsQuarantinedWithoutRetryAndOtherTargetsContinue` |
| T2 | Standalone failed open deletes a concurrent opener's live recording | A failed build removes only the directory it freshly created | **[Implemented]** `StandaloneFailedOpenDeletesConcurrentRecordingTest#failedOpenRemovesTheDirectoryAConcurrentStartIsRecordingInto`, `CaptureOpenFailureCleanupTest#failedStandaloneBuildNeverDeletesExistingHistory` |
| T3 | Name-only stops were not checked by the caller-side ledger | The harness resolves the target when the reply arrives | **[Implemented]** `ControlScenarioHarnessTest#nameOnlyStopLedgerRejectsAReplyBeforeReleaseWithoutConsultingAudit` |

## Terminal-state contract

What each terminal state promises, and who settles it. The settling order is: the agent thread before it exits, then
the post-join backstop, then shutdown.

| State | Outstanding requests | Resources | Names and services | Who settles | Status |
| --- | --- | --- | --- | --- | --- |
| **Dead** (agent thread ended abnormally, or the control processor threw) | Claimed: answered `FAILED` by the agent before it exits, or by the post-join backstop. Unclaimed: swept `NOT_RUNNING`. New: refused at admission | Captures stay owned. `releaseLeftoverCaptures` runs after the join and records each release in the fallback stream. Failures stay listed | Processor names stay reserved until death cleanup releases them. Quarantined processors keep theirs until shutdown, which reports them | Agent, then post-join backstop (`cleanupAfterDeath`), then shutdown | **[Implemented]** rows R4, M5, M8. Names held by a dead group reported in `StopResult`: groups are listed in `groupsRunning`; a per-name listing is **[Proposed: M4]** |
| **Closed** (orderly) | None outstanding by construction (terminal admission). A request still claimed at close is answered `FAILED` ("group ended"). A late completion is handed to the orphan handler exactly once | All closed, or listed as failed (`captureCloseFailures`) | Released, except quarantined processors | The agent thread in `onClose` | **[Implemented]** `ControlTerminalAdmissionTest#requestsQueuedAtCloseAreAnsweredByTheRulesWithoutStartingAnything`, `ControlTerminationAndAdmissionTest#completionRacingCloseIsDispatchedOrOrphanedExactlyOnce`, `CaptureShutdownRetryTest#aClosedGroupRetainsFailedCaptureCleanupAndTheNextStopResumesIt` |
| **Stopped** (one processor) | Replies in flight complete normally | Its captures are uninstalled before release and closed after it, or listed | The name is released on confirmed teardown, never on a refusal or a failed start before its release callback | The agent thread | **[Implemented]** `ProcessorStopReleaseTest`, `FailedStartNameOwnershipTest#failedStartKeepsItsNameThroughTheReleaseCallback`, `IntegrationReleaseRegressionTest#aDropCannotFreeANewerReservationUnderTheSameName` |
| **Quarantined** (one generated processor) | Later stops of it are answered `FAILED` ("processor quarantined") | Existing capture is detached without calling the graph; new installs refused; generated `stop()`/`tearDown()` never invoked | Instance and name retained until shutdown; shutdown stays incomplete and keeps dependent services | Nobody automatically. The operator, per the [shutdown runbook](../operations/shutdown-runbook.md#quarantined-processors) | **[Implemented]** `GeneratedProcessorFailureIsolationTest#generatedStopFailureCannotReenterTearDownOrReleaseItsName`, `#captureAfterQuarantineRefusesInstallAndDetachesWithoutCallingTheGraph`, `#groupReplayFailureCannotReenterGeneratedLifecycleOnShutdown` |

An operator acknowledgement never manufactures successful cleanup. There is no acknowledgement API in this release
**[Unsupported]**.

## Failure taxonomy

One enum, `FailureKind`, shared by `ControlReply`, `StopResult`, replay stop reasons and control-audit records (field
`kind`). Free text stays beside it as human detail; programs and oracles classify by kind. **[Implemented: M2]**

| Kind | Meaning | Typical producers |
| --- | --- | --- |
| `NONE` | Not a failure: applied, or the effect succeeded | Every `APPLIED` reply |
| `REFUSED_ADMISSION` | Not admitted: mailbox full, a sender that cannot wait, a caller that withdrew before the claim | `ControlDriver.trySend` / `send`, `ControlRequest.cancel` |
| `REFUSED_STATE` | Not applied because of the target's state: no such processor, name in use, group or server closing, add cancelled by an earlier stop | Rules (`stopUnknown`, `addCancelled`), terminal admission on a closing or closed group |
| `REFUSED_DEADLINE` | Not admitted within the caller's own deadline | `ControlDriver.send(request, admitBy)`, capture apply timeout |
| `FAILED_RULE` | The control processor itself failed (a rule threw, effects did not settle, wedged); the driver answered | `ControlDriver` safety net. Engine defect |
| `FAILED_EFFECT` | A requested effect failed: start, release, capture open/install/uninstall, delivery | Rules, from the effect result |
| `FAILED_DRIVER_FALLBACK` | The rules settled without replying, or an effect was abandoned; the driver authored the outcome | `ControlDriver` safety net. Engine defect |
| `FAILED_DEAD` | The group died; accepted work was answered by the death path | `markDead`, `failClaimed`, sweeps of a dead group |
| `FAILED_SHUTDOWN` | The group or server ended in order with this work outstanding, or a stop did not complete | Close with a request still claimed; `StopResult` incomplete |
| `QUARANTINED` | A processor was quarantined, or a request addressed one | Isolation, `stopQuarantined` |
| `DETACHED` | The caller stopped waiting; the outcome is not yet known. Not a cancellation | `StopResult` handed off to a stop thread |
| `UNVERIFIED` | Cannot be verified from the evidence (incomplete or failed audit). Never a pass | Reserved for M3 (evidence-incomplete marker); also the kind of a `ControlReply` built with a null outcome |
| `LEFTOVER_RECOVERED` | A resource the control processor should have released was reclaimed by offline cleanup | `releaseLeftoverCaptures` |
| `REPLAY_IDENTITY_MISMATCH` | A replay could not match an input, stream, service version or control request | `GroupReplayer` |
| `REPLAY_STOPPED` | Any other replay stop: recorded failure, delivery timeout, clock divergence | `GroupReplayer` |

Changes from the reviewer's list, with reasons:

- **`APPLIED` became `NONE`.** `APPLIED` is already a `ReplyOutcome`; repeating it in a failure enum would make the
  same word mean two things. `NONE` keeps the kind non-null on every reply, so `kind == NONE` iff `outcome == APPLIED`
  can be checked (invariant I20).
- **`QUARANTINED` added.** PR #76 commit `cc9956c` implemented quarantine of failed generated processors. A stop of a
  quarantined processor is neither an effect failure nor a death; operators must be able to find it by kind.
- **`DETACHED` and `UNVERIFIED` kept although no reply carries them yet.** `DETACHED` classifies a handed-off
  `StopResult`. `UNVERIFIED` is reserved for the evidence-incomplete marker of M3. Defining them now keeps the enum
  stable across milestones.
- **No `NOT_RUNNING` kind.** `ReplyOutcome.NOT_RUNNING` stays the outcome. Its kind says why: `REFUSED_STATE` for an
  orderly close, `FAILED_DEAD` for a dead group.

### Where the kind flows

| Carrier | How | Notes |
| --- | --- | --- |
| `ControlReply.kind()` | New record component, never null | Authored where the outcome is decided: rules (`LifecycleRules`, `CaptureRules`, `ServiceRules`), the driver (`ControlDriver`), the server's own refusals (`MongooseServer`) |
| `ControlEffect.Reply.kind()` | New record component | A rule's reply effect carries it to the driver |
| `EffectResult.kind()` | New record component | An effect's failure kind (`FAILED_EFFECT`, `QUARANTINED`, `FAILED_DRIVER_FALLBACK` for abandoned effects); a rule copies it into its reply, so no rule reads reason text |
| `ControlRequest.cancel(FailureKind, String)` | New overload | `cancel(String)` stays and means `REFUSED_ADMISSION` |
| `ControlDriver.reply(..., FailureKind)`, `failClaimed(FailureKind, String)` | New overloads | The old signatures stay, with `defaultFor(outcome)` and `FAILED_DEAD` |
| `StopResult.failureKind()` | New derived method | `NONE`, `DETACHED` (handed off) or `FAILED_SHUTDOWN` |
| `GroupReplayer.stopKind(processor)` | New method beside `stopped(processor)` | `REPLAY_IDENTITY_MISMATCH` or `REPLAY_STOPPED`; null when not stopped |
| `ReplayRouting.MISMATCH` | New `"mismatch:"` answer prefix beside `"wait:"` | The routing says which service failures are identity failures; the replayer never parses free text |
| Control audit | Field `kind` on `Reply`, failed `EffectOutcome`, and the `Refused`, `NotRunning`, `Failed`, driver `FAILED`, `Dead`, `Anomaly` (rule threw), `ProcessorQuarantined` and leftover fallback records | Driver replies keep `replyAuthor: DRIVER` and repeat the kind as `failureKind` for 1.0.33-rc1 readers |

The oracle (`ControlInvariants`) and the scenario harness key on the kind:

- **I18** rejects `FAILED_RULE`, `FAILED_DRIVER_FALLBACK`, `FAILED_DEAD` and `QUARANTINED` whatever the reason text.
  Reason text is read only for a record with no `kind` and no `failureKind`, that is, an export written before the
  taxonomy.
- **I19** (new) rejects a leftover recovery, one violation per reclaimed resource.
- **I20** (new) requires, in an export that carries kinds, every delivered reply to name a kind, `NONE` exactly when
  `APPLIED`.
- **I1** derives the start of a release from the `Stop` input (a name-only stop resolved to the generation live under
  that name) and requested `Release` effects, never from the rule name `stopStarted`.
- The harness classifies replies by `kind`: a safety-net reply is a failure; a failed stop is explained only by
  `FAILED_EFFECT` **and** a fault the harness injected into that target (or, for a name-only stop, into a processor of
  that name); a refused stop must be `REFUSED_STATE`; a failed capture request must be `FAILED_EFFECT`.

### API changes

All additions are source compatible except two record changes:

- **Breaking (record shape):** `ControlReply` and `EffectResult` gained a trailing `kind` component, and
  `ControlEffect.Reply` likewise. The previous canonical constructors remain as secondary constructors, so `new
  ControlReply(id, outcome, reason, generation, name)` still compiles. Record deconstruction patterns that list every
  component, and `equals`, now include `kind`. Two replies equal before may differ by kind.
- **Behaviour:** `ControlInvariants.isSafetyNetFailure` (test code) now takes a `ControlReply` and reads its kind.
- Everything else is additive: `FailureKind`, the overloads above, `StopResult.failureKind()`,
  `GroupReplayer.stopKind`, `ReplayRouting.WAIT` / `MISMATCH`, and the audit `kind` / `leftover` fields.

## Retry classification per effect

Nothing with unknown side effects is retried automatically. "Retry-safe" means an operator, or a later milestone's
explicit policy, may re-issue the effect; it does not mean the engine re-issues it.

| `ControlEffect` | Class | Why | Does the engine re-attempt it today? |
| --- | --- | --- | --- |
| `BuildAndStart` | Unknown side effects | Runs the supplier and user `start()` | No. A failed start is released and answered `FAILED` |
| `Release` | Unknown side effects | Runs user `stop()`/`tearDown()` | No. A step already attempted is never repeated on resume (`FatalStopReviewTest`) |
| `ReleaseName` | Retry-safe | Idempotent registry removal | No |
| `CancelRegistration` | Retry-safe | Sets a flag | No |
| `DeliverService` | Retry-safe at the host | A delivery already made to a processor is skipped; a callback that threw counts as delivered (`ServiceDeliveredOnceTest#aRegisterThatThrowsCountsAsDelivered`) | No |
| `RunTask` | Unknown side effects | Arbitrary caller code | No |
| `Reply` | Exactly once | Completed by compare-and-set; a second attempt is refused ("not claimed: answered earlier") | No |
| `FailBoot`, `ReportFailure` | Retry-safe | Reporting only; a repeat duplicates a report | No |
| `OpenCapture` | Unknown side effects | Creates physical files and queues; a re-issue creates a new resource | No |
| `InstallCapture` | Retry-safe after a verified failure | Swaps a listener on the agent thread | No. A failed install is compensated (resource closed) |
| `UninstallCapture` | Retry-safe | Restores the configured listener | **Yes, bounded:** a failed live uninstall is retried once after the release, and offline at group close (`CaptureAsEffectsTest#failedUninstallDuringProcessorStopIsRetriedAfterRelease`, `#failedOfflineUninstallRetainsOwnershipForGroupCleanup`) |
| `CloseCapture` | Unknown side effects | Backend partial effects unknown | No. A failed close is retained and listed |

The classification is written here and in the contracts **[Implemented as documentation]**. Recording it on each
`ControlEffect` (for example a `retrySafety()` method) so an oracle can check that no unknown-side-effect operation
runs twice for one opId is **[Proposed: M6]**.

Separately, the data path's `RetryPolicy` retries a throwing handwritten `DataFlow` with backoff on the agent thread.
That is dispatch retry, not control-effect retry, and it is not applied to generated processors (row T1).

## Bounds

| Bound | Default | At the bound | Surfaces as | Status |
| --- | --- | --- | --- | --- |
| Group mailbox | configured capacity | Producer backs off up to the full-mailbox timeout (10 s); agent threads and lock holders are refused at once | `REFUSED` | **[Implemented]** `AgentControlQueueFullTest` |
| Capture opens in flight per group | 8 | Open refused (`unresolved capture open limit reached`) | `FAILED` | **[Implemented]** `CapturePermitRegressionTest#rejectedTimeoutSchedulingDoesNotLeakPhysicalAdmission`, `CaptureAdmissionAndOrphanReviewTest` |
| Capture resource slots per group | 1024 | Open refused (`physical capture resource limit reached`) | `FAILED`, `captureResourceSlotsInUse` | **[Implemented]** `CaptureCloseBacklogProofTest#blockedCloserCannotExceedPhysicalResourceAdmission` |
| Control audit ring | 10 000 records (`mongoose.control.auditCapacity`) | Oldest dropped | `isComplete()` false, manifest `complete: false` | **[Implemented]** `ControlAuditExportTest` |
| Effect rounds per request | 64 | Abandoned with an `Anomaly`; request answered `FAILED` | Audit fallback | **[Implemented]** (no dedicated test; hardening only, second review) |
| Shutdown | `shutdownTimeoutMillis` 30 s, drain 2 s, feed phase 5 s | Stop returns incomplete | `StopResult` | **[Implemented]** `BoundedShutdownTest` |
| Data-path queues | per queue | Spin ~10 ms, then drop and count | Counters, health, `QueueDrop` record | Behaviour **[Implemented]** (`QueueDropObservabilityTest`); policy is [issue #79](https://github.com/telaminai/mongoose/issues/79) |

## Supervision and callback failure

**Working decision 1, owner to confirm: isolate the processor, keep the group, no automatic restart.**

- A generated processor whose callback throws is quarantined: removed from every input reader, timers cancelled,
  instance and name retained, health and ERROR raised. The group keeps running the others. **[Implemented]** (row R3).
- No automatic retry or restart of a failed generated processor. Clearing Fluxtion's processing flag restores an
  execution boundary; it does not roll back node state or external actions. **[Implemented]** (no restart path exists).
- Any future opt-in recovery needs an application contract for idempotency, state restoration and reconciliation of
  external effects. **[Unsupported]** in this release.

**Working decision 3, owner to confirm: a dead group is left for the operator in this release.**

- No supervisor restarts a dead group. **[Implemented]** (no restart path exists).
- Names and services it held stay reserved; `StopResult` lists the group and `controlGroupStatus` reports its state
  and cleanup failures. **[Implemented]** for groups; reporting each reserved name and service per dead group is
  **[Proposed: M4]**.

## Audit failure

**Working decision 2, owner to confirm: fail closed for the lifecycle control audit, fail open for Chronicle data
capture.**

| Audit | Policy | Today | Status |
| --- | --- | --- | --- |
| Control audit (`ControlAuditSink`) | Fail closed. A sink failure marks the group `DEGRADED`; every later control reply carries an evidence-incomplete marker (`UNVERIFIED`); the oracle reports the span unverifiable, never PASS | `sinkFailures` is counted and `isComplete()` turns false; the oracle reports evidence-dependent invariants unverifiable. Group health and reply marker are absent | Evidence side **[Implemented]** (`ControlAuditExportTest#sinkFailureSurvivesExportAndFalseCompleteClaimIsRejected`); DEGRADED and marker **[Proposed: M3]** |
| Chronicle data capture | Fail open. Never stops the processor; raises health and a control audit record | A capture failure fails the capture effect, not the processor. An install/restore failure on a generated graph quarantines that graph (it may be wedged) | A failed capture effect fails the capture request, not the processor **[Implemented]** (`CaptureAsEffectsTest#anOpenThatFailsIsAnsweredFailedAndHoldsNothing`, `#anInstallThatFailsIsCompensated`); quarantine of a generated graph on a routed install/restore failure **[Implemented]** (`GeneratedProcessorFailureIsolationTest#routedChronicleInstallFailureQuarantinesGeneratedGraph`); dedicated health check **[Proposed: M3]** |

Policy execution never depends on the failed sink: fallback records and replies are written by the group, not by the
audit listener **[Implemented]** (`ControlAuditSink` fallback stream).

## Fault-injection test API

The seams exist; they are test-internal today.

| Seam | Where | Status |
| --- | --- | --- |
| `CaptureIo` (open, close, timer) | public interface in `com.telamin.mongoose.control`; set per group with `setCaptureIo` | Public **[Implemented]**; supported-API commitment **[Proposed: M5]** |
| `AuditCaptureResources` fakes (`CaptureRig.FakeResources`, `ManualIo`) | `src/test` only | **[Proposed: M5]**: move to `com.telamin.mongoose.testing`, versioned with the engine |
| `ControlDriver.registerUnpublished` (sender paused before publishing) | package-private test seam | **[Proposed: M5]** |
| Processor fault doubles (throwing start/stop/teardown/handler) | per test class (`LedgerProc`, chaos `Instance.Kind`) | **[Proposed: M5]** |

Every matrix row above has at least one test that injects its fault. M5 makes those injection points a supported API
so downstream code can run the same scenarios.

## Fluxtion-level concerns

The three Fluxtion concerns from #77 are **resolved by Fluxtion runtime 1.1.3 / compiler 1.0.80**, which PR #76 is
adopting on `feature/control-plane` (this branch's base still builds on 1.1.2 / 1.0.79):

| Concern | Resolution |
| --- | --- |
| Generated `processEvent`, `batchEnd` and the service-call bracket had no `finally`, so a throw wedged the processor | 1.1.3 closes the failed cycle: the in-cycle flag is cleared, queued work is discarded, and auditors get `processingComplete`. Requires processors regenerated with 1.0.80. Node state is not rolled back |
| Re-entrant queue behaviour on exceptional exit was undefined | 1.1.3 discards re-entrant events, callbacks and work queued during the close. Nothing from the failed cycle runs later |
| One audit record per node per cycle | The failed cycle's record is closed and the next cycle gets its own. The control rules still write one `Decision` per node per cycle by design |

Mongoose keeps its host-side policy regardless of the runtime fix: quarantine, no retry, because clearing the flag is
not a transaction. The control driver's wedge guards (`controlWedged`) remain until the regenerated control processor
is qualified on 1.1.3.

## Milestones

| Milestone | Scope |
| --- | --- |
| M1 | This document |
| M2 | `FailureKind` on replies, effect results, `StopResult`, replay stops and audit records; oracle and harness keyed on kind; leftover marker |
| M3 | Audit fail-closed: group `DEGRADED` on control-audit failure, `UNVERIFIED` marker on later replies; Chronicle capture health |
| M4 | Terminal-state reporting: names and services held by dead groups and quarantined processors listed in `StopResult` and status |
| M5 | Fault-injection test API under `com.telamin.mongoose.testing` |
| M6 | Oracle vacuity reporting (absence is not evidence); retry classification recorded on `ControlEffect` and checked |

## Open questions for the owner

1. Confirm working decisions 1 to 3 (callback failure, audit failure, dead group).
2. Should a `DEGRADED` group (M3) keep accepting control requests, or refuse lifecycle changes until the operator
   exports the evidence?
3. Is a per-name listing of reserved names in `StopResult` (M4) an additive field, or should it wait for a typed
   `StopResult` replacing the raw-string `groupsRunning`?
4. Should the fault-injection API (M5) ship in the main artifact or a separate `mongoose-testing` artifact?
