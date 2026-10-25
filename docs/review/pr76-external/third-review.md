# PR #76 third independent review: `feature/control-plane` @ 44190ab (runtime 7610609)

## 1. Verdict

44190ab is a credible release candidate for the **engine's control plane**. I found no new control-plane safety defect on the production (routed) paths I checked:
- capture permits/ownership in `ComposingEventProcessorAgent`
- alias retirement in `MongooseServer`
- drop accounting under `publishLock`
- the export worker

The author's five source mutants reproduce exactly. Four of them are caught by checks the test owns. The independence claim does have one real hole: **stops addressed only by name are never checked by the reply-time ledger**. A mutant that answers APPLIED to a name-only stop without stopping anything is caught only by I17, which reads records the engine exported itself.

Two defects with runnable reproductions remain:
- **Host side, data path (Medium–High):** the queue agent's retry turns a wedged generated data processor into a silent, unbounded black hole that reports itself as recovered. This is the client gate the author left open. My point is that Mongoose currently *hides* it, not just fails to fix it.
- **Standalone capture (Low):** the new failed-open cleanup can delete another opener's live recording directory.

Neither blocks an engine release if the data-path item is documented as a known limitation and monitored. The data-path item does block any deployment that relies on the default retry to signal health.

## 2. Findings

### F1. Retry masks a wedged generated data processor; every later event queues without bound and is never processed (engine defect, host side; Medium–High)
- **Where:**
  - `EventQueueToEventProcessorAgent.java:137-237`: the retry loop sets `done = true` on any normal return.
  - Template: `control/generated/GroupControlProcessor.java:298-313`. `processEvent` has no finally. When `processing` is true, the event goes to `queueReentrantEvent` and the call returns normally.
- **Input:** a handler or auditor in a generated data processor throws once.
- **What happens:**
  - Attempt 1 throws and is logged as a WARNING.
  - Retry 1 calls `onEvent` again. The processor sees `processing == true`, queues the event and returns. The agent treats this as success, so no ERROR and no "dropping event" report.
  - Every later event on every queue feeding that processor is queued the same way. Nothing reports anything. The internal deque grows with input rate until the heap runs out.
  - `failedPosition()` and per-processor retry isolation (#53) make this worse: the retry targets exactly the wedged processor, and it always "succeeds".
- **Evidence:** `review3/WedgedProcessorRetryMasksFailureTest` (passes, which confirms the defect). After 20 events:
  - handler ran 0 times
  - 20 events sit in the re-entrant queue
  - exactly 1 WARNING was reported, and no ERROR
- **Documentation gap:**
  - `docs/release/release-plan-1.0.33.md:125` and `components.md` still present the retry as redelivery to the failed processor.
  - No runbook says the retry becomes a no-op against Fluxtion-generated processors.
  - `second-review-response.md` describes the wedge as not recovering. It does not say the host signals recovery.
- **Suggested fix (no compiler change needed):**
  - After a throw from a processor whose retry then returns normally, ask the processor whether it handled the event. Use a cheap processed-cycle counter or an `isProcessing()` probe; Fluxtion exposes enough via the generated class or a marker interface.
  - Failing that, treat a second-attempt success after a generated-processor throw as unverified, and report ERROR or quarantine.
  - At minimum, cap retries at 1 for generated processors and raise an ERROR-severity "processor may be wedged" event.
  - Separately, `RetryPolicy.backoff` uses `Thread.sleep` on the group's agent thread, which also stalls control drain. This predates the PR.

### F2. Standalone failed-open cleanup deletes a concurrently opened live recording (engine defect, standalone/unrouted capture only; Low)
- **Where:** `ChronicleAuditCaptureService.java` `ProcessorSink.openQueue`. The non-unique branch `Files.createDirectory(root/name)` sets `createdDirectory = true`. On failure, `removeFailedOpenDirectory` walks and deletes the whole tree (around lines 793-847).
- **Interleaving:**
  1. Standalone `start("p")` A creates `root/p`, then its builder fails, here delayed.
  2. Meanwhile `start("p")` B finds the directory already present, builds a real queue in it, and begins recording.
  3. A's cleanup deletes the directory, including B's `.cq4` files.
  4. B still reports RECORDING and writes into unlinked files. The data is lost, and discovery shows nothing.
- **Evidence:** `review3/StandaloneFailedOpenDeletesConcurrentRecordingTest` (passes, which confirms the defect) uses the package-private builder hook via reflection. `MongooseServer` always calls `routeThrough`, and routed opens use `createTempDirectory`, so production YAML servers are not exposed. This affects embedders and tests only.
- **Suggested fix:** delete only the files this attempt created. Alternatively, hold a per-name open lock across create and build. Or apply the unique-directory scheme to standalone sinks too.
- **Overclaim (K14):** "failed opens remove only their newly created directory" is true per call, but not across concurrent calls.

### F3. Reply-time ledger does not cover name-only stops; I17 is their only guard (test-model defect; Medium for the evidence claim)
- **Where:** `ControlScenarioHarnessTest.java:291-297`. `requests.track(group.removeEventProcessorByName(name, null, null), null, null)`. The STOP branch at `:174-186` requires `target != null`, so APPLIED name-only stops (25% of generated stops) are never checked for:
  - processor stop/tearDown
  - capture still installed
  - name released
- The end-of-run verdict passes too, because the later group close releases everything.
- **Evidence:** a mutant in my scratch clone of 7610609. `LifecycleRules.onStop`, STARTED case: `if (!intent.bound())`, reply APPLIED "stopped" and return without releasing.
  - Command: `mvn -o -Dtest=ControlScenarioHarnessTest -Dmongoose.harness.seedStart=1 -Dmongoose.harness.seedCount=200 test` gives BUILD FAILURE, 211 run, 64 failures.
  - **All 64 fail only at `:466`** ("broke audit invariants: I17 … replied APPLIED before generation N had successful Release and ReleaseName").
  - Zero failures came from the reply-time ledger or `requests.verify`.
  - Source restored afterwards (`git status` clean).
- **Consequence:** for name-only stops, detection depends on the engine faithfully exporting the `Stop` Input with the right name and the Release/ReleaseName outcomes. That is exactly the dependence the follow-up says it removed.
- **Suggested fix:** in the harness, resolve the target when the reply arrives. Name-only stops resolve to whichever `LedgerProc` of that name was running when the request was applied. The harness owns enough state to know this; for example, record `running()` procs by name at send time and require each one stopped and torn down, with the name absent from `Reservations`, on APPLIED. Add this mutant to `run-source-mutations.py`.

### F4. Mutant detection: per-mutant judgement (test model; informational)
I reran `run-source-mutations.py`. It clones into `mkdtemp` (pointed at the scratch dir via TMPDIR), never edits the supplied repo, and restores sources.

| Mutant | Result (tests/failures/errors) | Caught by |
| --- | --- | --- |
| baseline | 37/0/0 | (no failures, as expected) |
| missing-stop-reply | 37/18/0 | Test-owned reply callbacks (`:399` "reply-time ledger", `:413` verify) plus I18 in the export test |
| applied-without-release-renamed-rule | 37/19/0 | Test-owned stop/teardown/reservation checks plus I17. Genuinely rule-name independent |
| missing-stale-open-close | 37/2/0 | `FakeResources` pre-close accounting (`res#1 attempts=0`). Test-owned |
| skip-terminal-sweep | 37/1/0 | Caller-awaits check in the dedicated sweep test. Test-owned, but only one test catches it (the random harness never does) |
| drop-claimed-stop | 37/7/0 | `verify`: "caller still awaits". Test-owned |

Caveats:
- `missing-stop-reply` is caught partly because the safety net answers with a reason string that `isSafetyNetFailure` matches. The harness's "unexplained failed stop" (any FAILED stop without "simulated stop failure") would still catch it if the wording changed. The author acknowledges the string coupling.
- `IndependentEvidenceMutationTest` (fixtures) caught none of the source mutants. That is by design, but it shows the fixture tests are not mutation evidence.

### F5. QueueDrop records share the group's bounded control audit (documentation gap / product decision; Low)
- **Where:** `EventToQueuePublisher.recordDrop` writes `groupAudit.fallback("QueueDrop", …)` from the publisher thread, under `publishLock`, into the group's 10,000-entry `ControlAuditSink`.
- It is bounded at one record per binding (`++drops.count != 1`), so it cannot flood. However:
  - It consumes control-audit capacity, so it can make `isComplete()` false in a long-lived group with churned subscriptions.
  - First-loss evidence is first-ever per binding, not per incident.
  - With counters disabled, health reports UNKNOWN even though the exact per-binding `count` exists, and `droppedCount(queueName)` exposes it.
- Lock order is publishLock → audit monitor. I found no reverse path, because `ControlAuditSink` makes no callouts under its monitor.
- **Suggestion:** document the first-ever semantics. Consider a per-incident marker, for example after a successful offer following drops.

### F6. Minor capture observations (no reproduction; Low)
- **Standalone `processorStopped(name, flow)`** (`ChronicleAuditCaptureService.java:592-601`) closes the sink's queue directly, without removing its `CaptureHandle` from `openResources`. With an unrouted embedded group, the group's later `closeCapture` closes the same queue a second time. That breaks the class's own "each is closed exactly once" invariant. Production is routed. Chronicle's close is idempotent in practice.
- **Same method's javadoc** still says a recording "carries on into a processor re-added under the name (MA-5.4)". The new code ends the recording at stop. This is a documentation gap.
- **A failed `closeCapture`** removes the handle from `openResources` before `queue.close()` throws. The agent retains the permit (by design), but Chronicle can no longer retry. Repeated failures permanently consume up to `captureResourceLimit` slots. This is documented as intentional and is a product decision; it is worth an operator alarm on `captureResourceSlotsInUse`.

### Checked and found sound
- **Agent capture permits:** exactly-once via `remove`/CAS between orphan, leftover and close paths. The stale-open close and the detach-failure retry are covered by the earlier review.
- **Alias retirement:** I walked through these sequences:
  - pending A then confirmed B
  - re-register during pending
  - re-register under a third name
  - failed withdrawal retained until shutdown
  - shutdown dedup by physical instance

  Each gives one physical stop and never stops a live instance. The retirement monitor is never taken under `serviceLock`. A sticky `stopFailure` on a re-registered instance is retained by design.
- **Export worker:** single job via CAS. Error paths end in FAILED. INCOMPLETE is reported honestly.
- **`controlGroupStatus`:** documented as weakly consistent, with no torn reads beyond that.

## 3. Evidence overclaims
1. **README K1/K3, "Independent request ledger checks every caller reply" and "at-reply callback/name ledger":** for name-only stops, only outcome categories are checked. Callback and name state is not (F3).
2. **README "Removing the stop reply now fails 20 of 31 … 21 of 31":** the preserved script and results show 18/37 and 19/37. That is a different test set; the two documents should agree or name their sets.
3. **K14 failed-open cleanup "only their newly created directory":** not true under concurrent standalone opens (F2).
4. **"Mongoose's control guards remain intact" without stating that the data-path retry reports recovery from a wedge:** this understates the client gate (F1).

## 4. Tests run
- **Mutation script:**

  ```
  TMPDIR=$SCRATCH/muttmp python3 -I docs/reports/pr76-review-followup-2026-10-25/run-source-mutations.py $SCRATCH/wt-review-44190ab
  ```

  Exit 0. All six results match `source-mutation-results.json` exactly. Logs are in `$SCRATCH/muttmp/mongoose-source-mutations-4ieymojz/`.
- **Name-only-stop mutant** (scratch clone, restored): `mvn -o -Dtest=ControlScenarioHarnessTest -Dmongoose.harness.seedStart=1 -Dmongoose.harness.seedCount=200 test` gives **BUILD FAILURE, 211 tests, 64 failures, 0 errors**, all at I17.
- **Reproductions:** `mvn -o -Dtest='com.telamin.mongoose.review3.*Test' test` gives **BUILD SUCCESS, 2 tests, 0 failures**. Both passes confirm the defects:
  - `src/test/java/com/telamin/mongoose/review3/WedgedProcessorRetryMasksFailureTest.java`
  - `src/test/java/com/telamin/mongoose/review3/StandaloneFailedOpenDeletesConcurrentRecordingTest.java`
- **Full suite:** `mvn -o test` at 44190ab plus the two review3 tests gives **BUILD SUCCESS, 995 tests, 0 failures, 0 errors, 14 skipped**. That is the author's 993 plus my 2.

**Remaining uncertainty:**
- F1 uses a hand-written processor that copies the generated template, not a compiled client graph. The author's `GeneratedProcessorWedgeTest` already shows the real generated processor keeps `processing` stuck.
- I did not run chaos or soak.
- I did not exhaustively model-check alias retirement.
- I did not review replay changes (K4/K5/K10) beyond the diff skim.
