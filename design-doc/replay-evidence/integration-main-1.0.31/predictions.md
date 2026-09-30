# Integration of #48 with released main (1.0.31): predictions

Written and committed BEFORE any change, merge or regression run. PR head 8d224fb, main 59b9d8f (1.0.31 released,
1.0.32-SNAPSHOT), merge base 90f0d9b.

## The two nits (run on 8d224fb before their change)

- Nit 1: a new regression holds an output delivery that has begun, lets the caller expire, then releases the delivery.
  Predicted on 8d224fb: the ORDERING holds (the caller's timeout message first, the in-flight reply after it), and the
  WORDING assertion FAILS: the message says "nothing more from it will reach this caller".
- Nit 2: documentation only (List overload admission and busy; the request overload admits each invocation alone;
  direct, no-queue execution is synchronous). No behaviour change, so no regression; the existing busy test stays green.

## Textual conflicts expected from the merge (files changed on both sides since 90f0d9b)

- design-doc/replay_controls.py: both sides appended controls and changed the harness. Expected conflict.
- design-doc/spec-replay-recording.md: both sides edited §3d and §3f. Expected conflict.
- src/main/java/com/telamin/mongoose/replay/GroupRecorder.java: main reworked capture (per-target `received`, copies,
  route, instant, codec fallback, setup containment); the PR added the `ran()` check in `afterDispatch`. Expected
  conflict in `afterDispatch`.
- src/main/java/com/telamin/mongoose/dispatch/AbstractEventToInvocationStrategy.java: main added
  `processEventRecording`; the PR added the `mutedForReplay` accessor. Possible adjacent-hunk conflict.
- pom.xml: main moved the project version to 1.0.32-SNAPSHOT; the PR moved fluxtion to 1.1.0. Different lines: predicted
  clean, to be kept as both.

## Semantic interactions predicted even where the merge is textually clean

1. main's `GroupRecorder.received` (the per-target hook) runs BEFORE `AdminCommandInvoker.dispatchEvent` claims the
   command. For an AdminCommand it holds the reference, not a copy. So main's `afterDispatch` would record a cancelled
   or refused command as AdminInvoked unless the PR's `ran()` check is carried into it. The check must be kept, placed
   before AdminInvoked is built, and after the clock reads are captured.
2. main's `processEventRecording` (in AbstractEventToInvocationStrategy) dispatches through `dispatchEvent` directly. So
   in RECORD, `AdminCommandInvoker.processEvent`'s muted-refusal override is not the route. This is harmless: nothing is
   muted in RECORD. But the claim in `dispatchEvent` must still run on that route, and it does, because `dispatchEvent`
   claims first.
3. main's ReplayEntry.AdminInvoked carries an explicit instant. The PR's tests build `AdminInvoked(command, args, reads)`
   through a compatibility constructor (instant = reads[0]); recorded entries must come from main's recorder, with the
   instant `r.clock.instant(reads)`.
4. main's GroupReplayer rebuilds a replayed admin command with `new AdminCommand(template, request)`: under the PR, that
   copy binds the registered name and has fresh invocation state. Predicted compatible.
5. fluxtion 1.1.0 (the PR) under main's replay code: main's NamedFeedEventImpl uses (copyFrom, setEventTime, filterId)
   must behave on 1.1.0 as on 1.0.15. To be checked by main's F2 regressions on the merged tree, not assumed.
6. The PR's test fixture events (Block, Downstream, Marker) are Serializable records with no transient field, so main's
   InputCopy copies them in RECORD. Predicted green.

## Controls

main 72, PR 59: 99 distinct names, 32 shared, 8 shared with differing definitions:
R2-arms-the-processors-clock, R2-graph-raised-events-never-pass-dispatch, R5-pins-the-entry-instant,
R5-plays-every-read-of-the-cycle, R6-records-an-admin-command-by-its-args, csv-the-store-reads-back-its-file,
review-3-a-named-input-is-recorded-as-its-item, review-5-a-torn-last-line-is-dropped.
Each is re-derived from the merged source by meaning. None is dropped. Predicted gate: 99 + the new integration and
nit controls, all detected, with named-assertion and expected-message detections reported separately.

## Regressions on the merged tree

- Predicted green: AdminCorrectionRegressionTest, AdminCommandLifetimeTest, AdminReviewRegressionTest,
  AdminCommandFailureTest, GeneratedCycleAdminAuditTest, SignalAdminCommandTest, ReplayRecordingAcceptanceTest, and
  main's ReplayIndependentReviewTest, ReplayReReviewTest, ReplayRound3ReviewTest, ReplayRound4ReviewTest.
- AdminCorrectionRegressionTest.n1 (cancelled, RECORD then REPLAY, real queue, recorder and replay) is the integration
  regression for the cancelled case once it runs through main's per-target path.
- A new integration regression for the REFUSED case: RECORD with a processor whose undeclared override refuses the
  cycle. Predicted: no AdminInvoked is recorded. Its witness is a control that removes the carried `ran()` check.
