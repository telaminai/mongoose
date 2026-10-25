# Second independent review of the PR #76 design and release-gate package (72ecb67)

## Verdict

The package is mostly sound. It is **fit to drive implementation after design review, but four things must change first**:

1. **E3's proof is flaky.** `RetirementOtherNameProbeTest` passed on the unfixed code in 1 of 3 runs, so it cannot be relied on to catch the defect.
2. **Several T-gate proofs are the engine judging itself.** They rely on `pendingCleanup`, the `cleanup` and `offline` flags, `Reservations.table` and `captureResourcesHeld()`. The mutations behind them ("Mutation Mx caught") are not committed, so nobody can rerun those proofs from this branch.
3. **The client ranking is wrong in both directions.** The replay findings (4, 5, 10) should not gate a client that does not replay. Capture-leak masking (9), detach reporting (11) and the generated-processor wedge on the client's own processors should gate it.
4. **`77-overload-policy-spec.md` is not yet a sound design.** BLOCK as written deadlocks against unsubscribe (it holds `publishLock`), hangs shutdown, and makes a refused publish non-atomic across subscribers. The policy table also has no conflation and no per-subscriber policy.

The Fluxtion issue is correct in substance. Its line numbers are wrong and it misses some sites.

## 1. Are the findings real?

1. **Finding 4 (replay detach): real, fair test.** `GroupReplayer.java:164-186` records an `ended` reason only when the cursor is stopped (l.169), has pending expected calls (l.170-174) or has stop reads (l.175-182). A cursor with `!done()` is then dropped by `cursors.removeIf` (l.183). `complete()` (l.398-399) checks only the cursors that remain, so it reads true. `ReplayCursorDetachedMidStreamTest` (real `removeEventProcessorByName`) fails. **Client impact: none (no replay).**
2. **Finding 5 (replay status off-thread): real, fair test.** `cursors` is a plain `ArrayList` (l.33). `complete()` and `stopped()` iterate it (l.398, 406-409) and the fields are not volatile (l.47-49). Attach/detach on a plain thread models agent-thread mutation fairly. `ReplayStatusApiConcurrencyTest` fails with a CME. **Client impact: none.**
3. **Finding 6 (retirement under another name): engine defect real, repro flaky.**
   - The supersession check looks only at the old name (`MongooseServer.java:988-989`). `reserveServiceName` refuses an instance only once its retirement stop has been claimed (l.707). So `register(B=X)` succeeds while A's withdrawal is unconfirmed.
   - The stop then runs asynchronously on `replyExecutor` (l.880-883). The original probe asserts `stops==0` as soon as B lands (l.82-83), which can happen before that stop runs: it passed on my first run and failed on the next two.
   - `extreview2/RetirementOtherNameSettledTest` waits up to 2 s for the asynchronous stop before judging, and failed 5/5 (`expected 0, was 1`).
   - **Client impact: none.** A static deployment does not call `removeService` at runtime.
4. **Finding 7 (unrouted `processorStopped`): real, documented as unreachable through the server.** `ChronicleAuditCaptureService.java:506-510` falls through to l.536-543, which calls `stop(name)` → `selectedSink` resolves by bare name (l.97-112). `MongooseServer.java:266-271` always sets the router when capture is enabled. Both repros fail. The tests use the service directly, which fairly models direct embedding but not the server. **Downgrade to LOW.**
5. **Finding 2 (HIGH, I15/I8 trust the engine's `cleanup` flag): real.**
   - `EffectOutcomes.java:68-69` sets `cleanup:true` whenever `state.chain(requestId)==null`. The Reply closes the chain, so every outcome after the reply is flagged as cleanup.
   - `ControlInvariants.java:66` skips those records. I8 (l.175-182) looks only at `o.seq() < reply.seq()`.
   - The probe `replyBeforeItsEffectsOutcomeVerifies` passes, which confirms the hole. A hand-written trace is a fair model: the oracle's input is an export.
6. **Package hygiene.** The extreview package has **21** tests (1 skipped), not 23. It fails **5 or 6** depending on the flaky probe. `ExportedChaosAuditCoverageTest` (finding 9's evidence) is skipped unless an archive is extracted by hand (l.26-30), so finding 9's "vacuous on seed 19796" claim cannot be rerun from the branch.

## 2. Severities for this client (static, no replay, capture critical)

| # | Report | Re-rank | Why |
|---|---|---|---|
| 1, 2, 3 | HIGH | Fix before #76 merges | No engine effect, but the Q rerun that gates the client depends on them. Finding 3 covers shutdown `closeGroup` stops, which is the client's main control path. |
| 8 | MED | Fix before merge | Cheap (T4); same reason. |
| 9 | MED | **Gates client** | In a static deployment groups close only at shutdown. A capture leak masked by `releaseLeftoverCaptures` (`ComposingEventProcessorAgent.java:1077-1111`) builds up toward `captureResourceLimit` 1024 (l.196) during production. When the limit is reached, `audit.start` fails with "physical capture resource limit reached" (l.875). |
| 11 | LOW | **Gates client (MED)** | The capture accounting and `CleanupPending`/`StopResult` story is wrong in exactly the area the client's support depends on. |
| 14a (`openQueue` leftover dir) | LOW | **Gates client** | Phantom captures in `listAvailable` and the analyser. Author prompt item A3 already says so. |
| 6 | MED | Fix before merge, not client | Regression introduced by the PR for dynamic users. The test must be fixed first (see Q3). |
| 12, 13, 14 docs (D3-D5) | LOW | Before merge (docs) | Q6 covers the client's plugin rebuild. |
| 4, 5, 10, 14 (`CsvReplayStore` newline) | MED / LOW | Schedule later | Replay is `@Experimental` and unused by the client. Fix before replay is declared supported; list in Known limits. |
| 7, 14 (`attachScoped`) | MED / LOW | Schedule later | Not reachable through `MongooseServer`. |
| NEW: generated-processor wedge on data processors | not in report | **Gates client** | See Q7. |

## 3. Do the release gates close the findings?

1. **E3, HIGH: the proof does not reliably fail on the defect** (shown above). It also fails under the gate's own alternative fix: refusing re-registration makes `registerService(B)` throw, so the probe errors. Replace it with a settled assertion that accepts either `ServiceRegistrationException` at B, or `stops==0` after the async stop has run.
2. **T1-T5: the proofs are not reproducible.** "Mutation M1-M5 caught" refers to `src/main` edits made in a scratch checkout. Commit them as test-side fixtures, either as negative golden exports under `src/test/resources` or behind a rule-injection seam. Otherwise a reviewer cannot check that the gate fails on the defect.
3. **T2: engine judging itself.** "Use `pendingCleanup` lists": that list is produced by the engine on the `stopStarted` Decision (`LifecycleRules.java:107-109`, `CaptureRules.java:171,180`). A rule that wrongly lists Release as cleanup would pass. Instead, derive the effect kinds allowed after a reply from the contract: only a `CloseCapture` whose uninstall succeeded, never `Release` or `ReleaseName`.
4. **T3: engine judging itself.** It checks for names "gone from `Reservations.table`" (engine state), and its new invariant keys on rule names (`stopStarted`/`closeGroup`), the same weakness the report found in I1. Key it on the **Stop Input → APPLIED Reply**: a succeeded `Release` and `ReleaseName` for that generation must come before the reply's seq. Back it with test-owned processor `stop`/`tearDown` callbacks, and with a re-add of the same name succeeding.
5. **T6: engine judging itself.** `captureResourcesHeld()` and the `offline:true` flag are engine reports. Count opens and closes in a test-owned fake `CaptureIo`/resources (the `CaptureRig` style) and assert opens == closes before `onClose`.
6. **T1: fragile.** It matches on reason-string prefixes. Have the engine add a structured `kind` field to fallbacks and match on that. Also allow expected deaths in fault-injection runs, or T7's bad seeds will trip T1.
7. **E9 has no finding behind it**, and its proof is an already-green test that cannot fail on any defect. Fold it into E8.
8. **Findings and requirements with no gate:**
   - Finding 14's `CsvReplayStore` newline (l.212, 214).
   - Author prompt item 21's test (fill a queue, drop counted and surfaced, no reorder or duplication of delivered events). D6 has no proof column.
   - Author prompt item 20's per-feed classification and sizing argument, which is a *client* release gate. It appears only in 77 §5.
   - The data-processor wedge (Q7).
9. **Sound proofs:** E1, E2 (CME half only), E4, E5, E7, E8, T4, T5 (`registerUnpublished` seam, `ControlDriver.java:603-606`), C11 (filesystem sidecars). C, D and S gates trace to author prompt items A-J.

## 4. Is the overload spec sound?

1. **Current behaviour: accurate.** `maxSpinNs` l.462, `droppedCount++` l.478, pool release l.479-481, warning l.483-485, continue to the next queue. `SlowConsumerStrategy` is written (`AbstractEventSourceService.java:217`) and never read. Drain is at `LifecycleManager.java:400-414`. The spec omits four things:
   - one full subscriber throttles the whole feed and delays its other subscribers, because offers are sequential under `publishLock` (`EventToQueuePublisher.java:104-118, 175`);
   - an item is journalled and its sequence number consumed before dispatch (l.188-190), so a drop leaves a journal entry that was never delivered;
   - `droppedCount` is an aggregate over all target queues, not per queue (l.426-428);
   - a write failure already throws after partial fan-out (l.489-501).
2. **7.2 BLOCK, HIGH: deadlock.** A BLOCK publisher parks while holding `publishLock` (l.175), and it has to: it is the single producer for an SPSC queue. If the consumer group's thread then unsubscribes (`EventFlowManager.java:382` → `removeTargetQueueByName`, which waits on `publishLock` at l.544), that thread stops draining the queue the publisher is waiting on, so neither can proceed. Today the 10 ms bound breaks the cycle. Required changes:
   - Unsubscribe or queue removal must cancel a parked publisher, turning the block into a refusal.
   - The guard must be "never park any Agrona agent thread", using `AgentThreads.isAgentThread()` as `ControlDriver.java:183` already does. A service agent group hosting an event source is an agent thread too, and parking it stalls every co-hosted service.
3. **7.2 and 7.5, HIGH: shutdown hang.** Blocked publishers live in phase 2 (feeds), not phase 3 (drain). Processor groups close in phase 4. A publisher still parked then never wakes, the `feeds:stop` helper never returns, and every resumed stop reports `<event sources stopping>` (`LifecycleManager.java:373-381, 433-441`). The spec must cancel BLOCK publishers at the feed bound or the deadline, and report the refusal. "Never silently dropped" is unachievable once phase 4 closes the consumer, because queue contents die with the group. Per-feed "N queued" also needs per-subscriber-key accounting; `pendingQueuedEvents()` is global (`EventFlowManager.java:199-203`).
4. **7.3, HIGH: a refusal after partial fan-out is not "none silently".** Queues 0..i-1 already hold the item, so a caller that retries duplicates it. While the lock is held, an SPSC queue's free capacity can only grow. Specify an all-or-nothing pre-check of every target's free capacity before the first offer, or else per-queue refusal semantics.
5. **7.6: the PoolTracker claim holds structurally, but one rule is missing.** Exactly-once return comes from the refcount CAS plus the `returned` CAS (`PoolTracker.java:93-135`). The missing rule: the publisher's reference is released in `finally` (`EventToQueuePublisher.java:218-221`). After a refusal no queue holds the item, so it has already gone back to the pool. A caller that retries with the same instance then hits "already returned" (`PoolTracker.java:72-73`), or the ABA case l.87-91 if the pool has reissued it. The spec must say a refused pooled item must not be republished, or hand ownership back to the caller.
6. **Policy table omissions:**
   - **Conflation (latest-wins per instrument):** `DROP_AFTER_SPIN` drops the *newest* tick and keeps the stale ones queued, so the maker prices on old data.
   - **Policy per (feed, subscriber):** a telemetry consumer must not stall fills to the risk group.
   - **Venue session and health events** (logon/logout, disconnect, kill-switch): never drop.
   - **Cancels need priority over new orders.**
   - **Outbound orders from processors:** agent-thread publishes to connectors get downgraded, and the processor needs a defined reaction to a refusal.
   - **Cross-group fill→hedge cycles** under BLOCK.
   - **A wedged consumer defeats BLOCK** (it keeps polling and buffers into its re-entrant queue, Q5).
   - **The "Admin and control" row is misleading:** control has its own mailbox (`ControlDriver.java:147-200`).

## 5. Is the Fluxtion issue correct and complete?

1. **Wedge confirmed.** `processEvent` (`GroupControlProcessor.java:298-314`) has no try/finally. `extreview2/GeneratedProcessorWedgeTest` throws inside a cycle (through the audit listener on the `afterEvent` path; no `src/main` change). Afterwards `processing==true`, the next `onEvent` returns normally, and it produces zero records: it was silently queued (l.302-303). Test passes, which confirms the wedge.
2. **Wrong line numbers.** `batchEnd` is at l.762-770, not 690-696. The service-call bracket is at l.684-697, not 680-688. `runInEventCycle` at l.714-742 is correct.
3. **Missing sites.** `start`, `startComplete`, `stop` (l.231-268) and `batchPause` (l.753-760) have the same shape with no finally.
4. **Missing consequence.** On a throw, `afterEvent` (l.744) never clears `isDirty_*`, so the next dispatched cycle propagates spuriously. `afterEvent()` in finally covers it; say so.
5. **Re-entrant queue default.** Neither option is right as worded:
   - **Dispatch-in-finally** runs more handlers on a graph that has just thrown mid-propagation, and a second throw masks the first.
   - **Plain drain-and-discard** loses events. For the control processor those are completions; losing an OPENED completion leaks a capture.

   Recommend discard-from-the-graph as the default, with the discarded events handed back to the host (attached to the propagated exception or passed to a hook), so the host can orphan-handle them as `ControlDriver.orphan` does. Never dispatch them in a later, unrelated cycle.

## 6. Is the maintainability plan safe?

1. **M2 (single ownership table):**
   - **Tests that must stay green.** The plan lists only 3. Also required: `CaptureAdmissionAndOrphanReviewTest`, `CaptureAsEffectsTest`, `ControlScenarioHarnessTest`, the extreview capture tests and the E6 inverted test.
   - **Invariants it could silently drop.**
     - Slots are held from admission (`ComposingEventProcessorAgent.java:874`), before any resource id exists; ids are assigned at l.926. An id-keyed table has no row for a queued open, or for the second permit of a duplicate helper execution (l.907-918).
     - `opensInFlight` is a separate limit.
     - The retry rule for `captureDetachFailures` depends on `processorsByGeneration` (l.991-996).
     - Exactly-once close relies on "whoever removes from `captureHandles` closes" (l.999, 1036, 1100).
   - **Size: 4 days is low.** Making the control state's `resources` "a view" changes the control processor graph, which needs a regenerated `GroupControlProcessor` and may change audit records (invariants, analyser). Estimate 6-8 days.
2. **M5 (service registry extraction):**
   - **Tests that must stay green.** The plan names none. Required: `RetirementReregistrationRaceTest`, `HuntServerFailureModesTest`, `FailedStartupCancellationReviewTest`, `BoundedShutdownReviewTest`, `RepliesAndServiceLockReviewTest`, `DynamicServiceRegistrationTest`, `ServiceDeliveredOnceTest`, `IndependentServiceReviewTest`, `ServiceLifecycleTest`, `ReplayRegistryIsolationFollowupTest`.
   - **Invariants at risk.**
     - One `serviceLock` orders the registry mutation together with broadcast publication (l.668-683, 858-870).
     - The admission read lock is held across reserve+publish (l.661-686); a registry callable without admission bypasses the stop fence.
     - `awaitBroadcast` never runs under a lock.
     - `includeFinalStartupCleanup` detects retirements with `instanceof RemovedService` (l.921).
     - The `removed[id]:` naming the LifecycleManager tracks (l.952).
     - The `flipServiceUp` guard (l.1024).
   - **Size: 3 days is plausible for the move; 4-5 with the tests.**
3. **M1** interacts with T1: removing the wedge guards changes which Anomaly/Dead records appear.

## 7. What did the review miss?

1. **Client gate: generated-processor wedge on the client's own processors.** The client's market-maker processors are Fluxtion-generated with the same shape. A handler throw propagates (`AbstractEventToInvocationStrategy.java:117-121`). The agent's retry (mongoose#53) then hands the event back to a processor whose `processing` flag is stuck, and it silently queues every later event: pricing or hedging stops with no record. The package treats this as a post-release issue for the control processor only. Add a client gate: check the client's generated sources and Fluxtion version, and add either a runbook detection rule or a host-side guard.
2. **Four areas the report rated sound.** Within budget I found no engine defect I could anchor to a concrete interleaving:
   - **`ControlDriver`:** admission rechecks `ended()` after the offer (l.148-152, 193-196). `close` sets CLOSING before polling the mailbox (l.491-493), and `orphanLeftovers` runs after CLOSED.
   - **`LifecycleManager`:** a resumed phase 2 can start a second `flowManager::stop` helper, but `EventFlowManager.stop` is CAS-idempotent (l.164).
   - **Capture permits:** the open helper racing `releaseLeftoverCaptures` resolves through `captureHandles.remove(id, handle)`.
   - **`PoolTracker`:** the non-atomic pair `refCount.set` / `returned.set` in `init` (l.60-61) is benign, because a late release sees `returned==true`.

   Hardening only: exceeding `MAX_EFFECT_ROUNDS` (`ControlDriver.java:400-407`) abandons effects without the per-effect `EffectOutcome` fallbacks of the fatal path (l.430-433); not shown reachable.

## DECISION recommendations

- **77-D1:** ship `BLOCK_WITH_DEADLINE` first. Infinite `BLOCK` only from non-agent threads, and only once it can be cancelled by unsubscribe and shutdown.
- **77-D2:** keep `DROP_AFTER_SPIN` as the default, with the boot warning, and make the client's configuration explicit for every feed. Agree.
- **77-D3:** do not reuse `SlowConsumerStrategy`. Add a new `OverloadPolicy`, map `BACKOFF`→`DROP_AFTER_SPIN`, and fail boot on `DISCONNECT`/`EXIT_PROCESS` until they are implemented.
- **77-D4:** never-drop feeds stay DEGRADED until an operator clears them; a quiet-period recovery only for drop-tolerant feeds.
- **77-D5:** a kill-switch hook, configurable, off by default.
- **C6:** (b) runbook plus an exact test for #76. Bounded retry of a Chronicle close goes into the maintainability plan.
- **C10:** Mongoose does not prune; the client owns retention, and its tooling is checked against the `capture-*` names.
- **S5:** `shutdownTimeoutMillis` ≥ flatten time + drain + 5 s feed cap. The process-manager grace period must exceed `shutdownTimeoutMillis` + 5 s Agrona close retry.

## Tests run (offline, Java 21, 72ecb67)

- **extreview package:** BUILD FAILURE as designed. 21 run, 5 failures (the capture ×2 and replay ×3 repros), 1 skipped. `RetirementOtherNameProbeTest` passed in that run.
- **`RetirementOtherNameProbeTest` alone:** FAILED (expected 0, was 1). It failed again in the full run, so 1 pass in 3 runs.
- **`extreview2/RetirementOtherNameSettledTest`** (new): 5/5 FAILED, which confirms finding 6 deterministically.
- **`extreview2/GeneratedProcessorWedgeTest`** (new): PASSED, which confirms the wedge.
- **Full `mvn -o test`:** BUILD FAILURE only because of the review packages. 958 run, 11 failures (5 extreview + 1 flaky probe + 5 extreview2), 0 errors, 15 skipped. No failure outside `extreview`/`extreview2`, so the 931 base tests are green.

**Remaining uncertainty:**
- I did not rerun the report's mutations; they are not on the branch.
- Finding 9's archive evidence was not reproduced.
- The claim that finding 6 was introduced by the PR is unverified against the base.
- The wedge was demonstrated through the audit-listener throw, not a node handler (same un-finally'd path).
- The client's own Fluxtion version and generated sources are unchecked.
- The BLOCK deadlock is argued from code, since BLOCK does not exist yet.
