# Issue #77: independent reviewer response (draft for the owner to post)

Status: reviewer-side input requested by the "Coordination request" section of #77. Written against candidate
4f5851e and the external review on branch `review/pr76-external-review` (commit 72ecb67). Every default below is a
**recommendation**; items marked **OWNER** need a decision from the product owner before the matrix is agreed.
Nothing here is implemented.

Client context the recommendations assume: static deployment (YAML boot, compiled processors in a fat jar), no
dynamic replacement, no record/replay, audit capture and the analyser critical to support.

## 1. Where we agree

The seven principles in the consolidated #78 catalogue are the right rules. The review found no defect in the
candidate that is not an instance of one of them. The release boundary stated in #77 is the right boundary for
#76. Of its seven items, five hold at 4f5851e (no false success, no lost outcomes after terminal failure, safe
ownership hand-off, bounded admission and retention, replay isolation). Two do not yet hold as *verified* claims:
truthful incomplete shutdown and honest audit-completeness reporting. The engine behaves correctly in every run we
made; the proofs offered for those two items are the engine judging itself (review findings 1, 2, 3, 8, 9). The
boundary is met by the code and not yet by the evidence.

## 2. Rows missing from the failure matrix

Each row below is anchored to a reproduction on the review branch.

| Trigger | Required containment / ownership | Caller and operator evidence | Recovery boundary | Source |
| --- | --- | --- | --- | --- |
| Service retired under name A while the same instance is re-registered under name B | Supersession is by **instance**, not by the retired name; an obsolete retirement never stops a live instance whatever name it now holds | Retirement record names the instance and the decision (stopped / superseded) | Re-registration under any name claims the instance | finding 6, `RetirementOtherNameProbeTest` |
| Processor stopped live while a replay cursor still holds undelivered entries | Detaching a cursor with work left is a **stopped** outcome with a reason, never a silent completion | `complete()` false or `stopped()` non-null; never `complete()=true, stopped()=null` with entries left | New replay context | finding 4, `ReplayCursorDetachedMidStreamTest` |
| `start()` throws for a recorded processor | The failure is a recorded fact (`Failed` entry) at the ordinal it consumed; the stream never holds provenance alone | Replay of a stream whose recording failed at start stops with "recorded start failed" | Fix and re-record | finding 10, `RecordedStartFailureReplaysAsSuccessTest` |
| Legacy name-keyed capture stop reaches a scoped sink | Stop by bare name must refuse when the only match is a different generation; never fall through to it | Refusal names the generation that was protected | None needed | finding 7, `ChronicleCaptureEdgeTest` (direct embedding only) |
| Physical detach retry fails after the logical release | Handle and slot retained (already true); the failure is **listed** by `captureCloseFailures()` and the control table keeps the row | `CleanupPending.failedPhysicalCloses` counts it; `captureResourcesLeft` and failed closes agree | Operator verify/replace | finding 11 |
| Rule performs effects but never replies | Driver safety net answers FAILED (already true) | The fallback record is classified as a **driver** reply, distinct from a rule reply, so an oracle can tell the two apart | None | finding 1 |
| Status API read off the agent thread | Status views are safe to read from any thread or documented as agent-thread-only | n/a | n/a | finding 5, `ReplayStatusApiConcurrencyTest` |
| Group close reclaims captures the control processor left open | Leftover release is a **recovery**, recorded as such, never silently satisfying the "every open is closed" invariant | Offline fallback record carries `leftover=true`; oracle counts leftovers as violations unless the scenario injected the leak | Fix the rule that leaked | finding 9 |

## 3. The four defaults the issue asks for

| Question | Recommendation | Reason | Owner |
| --- | --- | --- | --- |
| Partial callback failure (handler or service callback throws after partial side effects) | **Isolate the processor, keep the group.** The processor moves to FAILED, its subscriptions are released, its services are released, its name stays reserved until the operator acknowledges or shutdown. The group keeps running the others. No automatic restart. | A market maker's pricing and risk processors share groups; stopping the group over one bad handler turns a contained fault into an outage. Restart without an idempotency contract is the "repeated side-effectful callback" the matrix forbids. | **OWNER**: confirm no-restart default |
| Audit writer fails or evidence is dropped | **Fail closed for lifecycle audit, fail open for data-path capture, both loudly.** Control audit sink failure marks the group DEGRADED, every later control reply carries `evidence=INCOMPLETE`, and the oracle reports the span as unverifiable, never PASS. Chronicle capture failure never stops the processor; it raises health and a control audit record. | The client's support cycle depends on the lifecycle record being trustworthy; a gap must be visible to the analyser. Stopping trading because an audit file could not be written is the wrong trade. | **OWNER**: confirm the split |
| Retry eligibility | **Nothing retries automatically.** Retry-safe (idempotent, may be re-issued by an operator): capture install/uninstall, service broadcast, replay detach. Unknown side effects (never re-issued without an adapter-specific verify): `stop()`/`tearDown()` callbacks, physical close, `start()`. The spec records this classification per effect in `ControlEffect`. | Matches principle 3; every retry defect in #76 was an implicit re-issue. | none |
| Unresolved physical cleanup | **The group owns it until confirmed; shutdown reports it; the operator resolves it.** A CLOSE_FAILED or DETACH_FAILED resource keeps its permit and slot, is listed by `captureCloseFailures()`, appears in `StopResult` as incomplete, and is never freed by acknowledgement. The only automatic path is `releaseLeftoverCaptures` at group close, recorded as a leftover (row above). | Matches principle 2; the M2 ownership table in the maintainability plan makes this one place. | none |

## 4. Challenges to the principles

- **Principle 1, "every request ends".** Agreed, with one addition: it ends with exactly one reply **from an identified
  author** (rule, driver safety net, or shutdown). Today the fallback record is distinguishable only by its reason
  text (finding 1). The taxonomy below fixes this.
- **Principle 5, "honest outcomes".** Add: APPLIED for a *stop* means the Release effect was observed, not that the
  rule said so. The harness at 4f5851e accepts an APPLIED stop that never releases (finding 3).
- **Principle 7, "every failure is evidence".** Add the converse: **absence is not evidence**. An oracle must report
  which invariants were vacuous on a given export (zero records to judge). The bundled chaos export contains no
  failed closes, timeouts or sweeps, so I4, I9 and I13 passed on nothing (finding 9). Release-gate rows T1 to T8
  cover this.

## 5. Answers to the open questions

**Terminal-state contract.** Proposed promises, in the order the engine settles them:

| State | Outstanding requests | Resources | Names | Who settles |
| --- | --- | --- | --- | --- |
| Dead (agent thread exited abnormally) | Claimed: answered FAILED by the post-join backstop. Unclaimed: swept FAILED "group dead". New: refused at admission. | Captures held by the group stay owned; listed by `StopResult` and `captureCloseFailures()`; released by `releaseLeftoverCaptures` only if the backstop can run it, recorded as leftover | Processor names stay reserved until server shutdown | post-join backstop, then shutdown |
| Closed (orderly) | None outstanding by construction (terminal admission); any late reply is an orphan completion and is recorded | All closed or listed as failed | Released | agent thread before exit |
| Stopped (processor) | Replies in flight complete normally | Processor's own resources released or listed | Name released on confirmed teardown, not on refusal (finding 14: `processorDropped` frees it early) | agent thread |

**Single failure taxonomy.** One enum, used by `ReplyOutcome.reason`, `StopResult`, replay stop reasons and the
audit `reason` field, with free text demoted to a `detail` field:
`REFUSED_ADMISSION, REFUSED_STATE, REFUSED_DEADLINE, APPLIED, FAILED_RULE, FAILED_EFFECT, FAILED_DRIVER_FALLBACK,
FAILED_DEAD, FAILED_SHUTDOWN, DETACHED, UNVERIFIED, LEFTOVER_RECOVERED, REPLAY_IDENTITY_MISMATCH, REPLAY_STOPPED`.
The oracle keys on the enum, never on rule names (finding 3's I1 keys on "stopStarted" today).

**Bounds and back-pressure.** Answered in full by `77-overload-policy-spec.md` on the review branch. Recommendation
below (section 7) is to move it to its own issue.

**Supervision.** Leave the dead group for the operator in this release. Names and services it held stay reserved;
`StopResult` and health report it. Restart and quarantine are follow-on features gated on the idempotency
contract in the retry row above. **OWNER**: confirm.

**Fault-injection surface.** Make `CaptureIo` and the `CaptureRig` fakes a supported test API under
`com.telamin.mongoose.testing`, versioned with the engine. Every row in sections 2 and 5 needs an injection point;
today they exist but as test-internal seams.

**Fluxtion-level concerns.** Three, all in `fluxtion-issue-generated-processor-wedge.md`: generated `processEvent`,
`batchEnd` and the service-call bracket lack try/finally so a throwing handler wedges the processor silently;
re-entrant queue behaviour on exceptional exit is undefined; `EventLogNode` allows one record per node per cycle.
None blocks #76; all three remove compensation code once fixed (maintainability item M1).

## 6. Deliverables already covered by the review package

| #77 deliverable | Covered by |
| --- | --- |
| Challenge the matrix, add missing modes, identify disagreements | this document, sections 2 and 4 |
| Written transition/effect/outcome table | section 5 (terminal-state table) plus `docs/reference/control-plane-contracts.md`; the taxonomy is new |
| Mark every guarantee implemented/tested, proposed, unsupported | `76-release-gates.md` columns; the seven boundary items in section 1 |
| Retry-safe vs unknown-side-effect classification | section 3, row 3 |
| Independent oracle assertions and forbidden-trace mutations | release gates T1-T8; `InvariantVacuityProbesTest`, `ExportedChaosAuditCoverageTest` |
| Deterministic interleaving tests and real-thread races | the `extreview` package (23 tests, 5 failing by design on 4f5851e) |
| Fixed-commit soak plan | release gates Q1-Q8 |
| Evidence bundle | `docs/reports/pr76-qualification-2026-10-25/` exists; Q-rows require it to be rerun on the frozen SHA with the oracle fixes applied |
| Which items gate this release; separate issues for the remainder | section 7 |

## 7. Proposed structure for the work

1. **#76 release** gates: review findings 4, 5, 6, 7, 10 (engine) and the oracle fixes behind findings 1, 2, 3, 8, 9,
   then a qualification rerun on the frozen SHA. Tracked in `76-release-gates.md`.
2. **New issue: overload and loss policy** from `77-overload-policy-spec.md`. Implementable on its own; it should not
   wait for the failure-model agreement. **OWNER**: agree to split it out of #77.
3. **#77 stays the parent design**: taxonomy, terminal-state contract, supervision decision, fault-injection API,
   `docs/design/failure-handling.md`. Implementation after #76 ships.
4. **Fluxtion issue** filed from `fluxtion-issue-generated-processor-wedge.md`; Mongoose adopts in maintainability M1.
5. **Maintainability plan** M2 to M8 as a tracking issue after release.

## 8. Decisions requested from the owner

1. Callback failure default: isolate processor, no automatic restart (section 3, row 1).
2. Audit failure default: fail closed for lifecycle audit, fail open for capture (section 3, row 2).
3. Dead group: operator-resolved in this release, no supervision (section 5).
4. Split overload policy into its own issue (section 7).
5. Whether the failure taxonomy enum is in scope for #76 (it changes `ReplyOutcome` and `StopResult` text fields;
   recommendation: define it in #77, adopt in #76 only for the driver-fallback marker that finding 1 needs).
