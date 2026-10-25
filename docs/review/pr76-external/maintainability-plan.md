# Mongoose control-plane maintainability plan (post-release tracking issue)

Premise: the code at 4f5851e is correct under adversarial review, but much of its correctness lives in guards and
review-round comments rather than structure. These eight items lower the cost of the next PR and remove two
classes of bug. Ordered by payoff over risk. Sizes are engineer-days including tests. Each item must leave the
full suite, the scenario harness and the chaos run green; none may weaken a regression assertion.

| # | Item | Size | Depends on | Risk removed |
| --- | --- | --- | --- | --- |
| M1 | Adopt the Fluxtion generator fix (`fluxtion-issue-generated-processor-wedge.md`), regenerate `GroupControlProcessor`, add the never-wedges test, shrink `EffectQueue` and `ControlDriver` compensation | 2 | Fluxtion release | silent wedge; ~150 lines of load-bearing defensive code become optional |
| M2 | Single ownership table for physical capture resources | 4 | none | the agent (`captureHandles`, `captureResourcePermits`), the control state (`resources`) and the Chronicle service (`openResources`) disagreeing (review finding 11) |
| M3 | Ship the oracle as a module | 3 | T1-T3 of the release gates | validator drift; enables analyser and LLM to call it |
| M4 | Extract the capture adapter from `ComposingEventProcessorAgent` | 3 | M2 | 1,556-line class doing four jobs |
| M5 | Extract the service registry and retirement from `MongooseServer` | 3 | none | 2,183-line class; `RemovedService` logic isolated and unit-testable (review finding 6 lives here) |
| M6 | Replace review-round comments with named invariants | 2 | M4, M5 | reasons surviving only in PR threads |
| M7 | Per-server replacements for JVM singletons | 2 | none | one stuck Chronicle close stalling every group in the JVM; test interference |
| M8 | Narrow the public surface and consolidate test rigs | 2 | M4 | accidental API; ~40 overlapping review test classes |

Total about 21 days, parallelisable into two streams (M2→M4→M6→M8 and M1, M3, M5, M7).

## M1 Adopt the generator fix

Regenerate from `src/main/fluxtion/control/control-processor.xml`. Add `ControlProcessorRecoversFromAThrowingRuleTest`:
inject a rule that throws once, assert the request is answered FAILED by the normal outcome path, the next request
is applied, the audit record for the failed cycle exists, and `ControlDriver.state()` is not DEAD. Then remove: the
`controlWedged` short-circuit in `drain` (keep the flag for diagnostics), the abandoned-batch re-dispatch loop in
`settle` if the generator now dispatches or discards deterministically, and the duplicated `markDead` paths in
`dispatch`/`settle`. Keep `EffectQueue.perform`'s catch-all: an effect is user code.

## M2 Single ownership table for physical capture resources

One `CaptureResourceTable` owned by the group: id → {handle, permit, owner generation, state (OPEN, INSTALLED,
CLOSING, CLOSE_FAILED, DETACH_FAILED), opId chain}. The control state's `resources` becomes a view fed by the same
events; `ChronicleAuditCaptureService.openResources` keeps only the exactly-once close guard. Invariants enforced
in one place: a slot is released exactly when a row leaves the table in state CLOSED; a row in CLOSE_FAILED or
DETACH_FAILED is listed by `captureCloseFailures()`; `awaitCaptureResourceCleanup` reads the table. Port
`CapturePermitRegressionTest`, `CapturePhysicalShutdownTest`, `CaptureCloseBacklogProofTest` unchanged; they must
pass before and after.

## M3 Ship the oracle as a module

Move `ControlInvariants`, `ControlEvidence` and the export reader into `src/main` (package
`com.telamin.mongoose.control.verify`) behind a CLI entry point and a service method:
`ControlAuditVerifier.verify(Path export) -> Result{violations, unverifiable, coverage}`. `coverage` lists which
invariants had at least one record to judge, so a benign export reports which invariants were vacuous (review
finding 9). The MCP/analyser surface can call it. `ControlInvariantsMutationTest` moves with it and gains the
mutation cases from the review (M1-M5 of the evidence report) as fixtures.

## M4 Extract the capture adapter

`CaptureEffectAdapter` implements the capture subset of `EffectAdapter`: `openCapture`, `installCapture`,
`uninstallCapture`, `closeCapture`, `closeResource`, `orphanCompletion`, `releaseLeftoverCaptures`, the limits and
`CaptureIo`. `ComposingEventProcessorAgent` delegates. Unit-test the adapter with `CaptureRig`'s fake resources
without a group. Target: agent under 1,000 lines.

## M5 Extract the service registry

`ServiceRegistry` owns `registeredServices`, `serviceVersions`, `removedServices`, `retirementStops`, `serviceLock`,
`RemovedService`, `reserveServiceName`, `sendBroadcast`, `awaitBroadcast`, `includeFinalStartupCleanup`.
`MongooseServer` keeps admission (`admitServiceChange`) and delegates. Unit tests for the retirement state machine
cover: same-name re-registration before and after claim, different-name re-registration (review finding 6),
superseded stop, failed stop retained, shutdown stop of retained incarnations.

## M6 Named invariants instead of review citations

Grep for `review of`, `finding`, `FM-`, `mongoose#` in `src/main`. For each guard: either a named predicate method
(`assertAgentThread()`, `requireUnclaimed(request)`), or a one-line statement of the invariant protected and the
test that proves it, replacing the citation. Keep a short `docs/design/control-plane-invariants.md` listing each
invariant, where it is enforced and which test covers it; the analyser's invariant ids (I1-I16) should map onto it.

## M7 Per-server singletons

`CaptureIo.shared()` becomes the default for a server-owned `CaptureIo` built in the `MongooseServer` constructor
(opener pool, closer, timer per server), with the JVM-shared instance available as an explicit opt-in. The
`ControlDriver.LIVE` liveness checker becomes a per-server `LivenessChecker` passed to groups. The static
`logRecordListener` in `MongooseServer` becomes an instance field captured at boot. Tests: two servers in one JVM,
one with a blocked closer, the other shuts down complete.

## M8 Public surface and test consolidation

Mark `ComposingEventProcessorAgent` and its setters `@Experimental` or move to an `internal` package with a
documented stable facade on `MongooseServer`/`MongooseServerConfig` for the knobs that must be public (capture
limits, drain budget). Consolidate review test classes onto `CaptureRig`, the scenario harness and one server rig;
retire duplicates once their assertions are provably covered (map each retired test to the surviving one in the
PR description).

## Acceptance for the plan

- Full suite, harness seed sweep, 30 s chaos and 20-cycle storm green after each item.
- `ComposingEventProcessorAgent` < 1,000 lines, `MongooseServer` < 1,500 lines after M4/M5.
- Zero `review of` / `FM-` citations remaining as the sole justification of a guard after M6.
- Oracle coverage report produced for every qualification export after M3.
