# Control plane build: predictions, then actuals

Written 2026-10-25, before the first generated control processor ran. The actuals are filled in at the end of the
build and compared against each prediction, without editing the predictions.

## Predictions

| # | Prediction | Measure |
| --- | --- | --- |
| P1 | Spring authoring is productive for the topology: the XML will change about 5–10 times, mostly to add nodes, with at most 2 regeneration failures from Fluxtion's field-mapping rules | XML commits; regen failures |
| P2 | The control audit will expose at least 2 ordering or logic bugs in the rules before the generated processor has to be read; the generated processor will be opened to explain behaviour at most 2 times | Bugs found from audit; times the generated source was needed |
| P3 | The independent invariant checker will catch at least 1 defect that the example-based tests missed | Invariant violations found that no unit test caught first |
| P4 | Rewiring the group onto the control processor will first break 10–30 of the existing 499 tests, mostly ones coupled to the old queues, and the fixes will land mostly in the group's effect adapter, not in the rules | First full-suite run after rewiring; where fixes landed |
| P5 | The control rules themselves will be under 25% of the effort; integration, terminal admission, close and shutdown will be the majority | Rough share of commits and time |
| P6 | The hardest parts will be close, agent death and bounded shutdown; most review findings will land there | Review findings by area |
| P7 | No measurable change on the event path: the duty cycle checks one more queue at most | Empty-mailbox duty-cycle comparison |

## Actuals

Filled in 2026-10-25 at the end of the build (PR 0–6 on `feature/control-plane`), against the predictions above,
which are unedited.

| # | Verdict | What happened |
| --- | --- | --- |
| P1 | **Right, with better numbers.** | The Spring XML changed in 2 commits (PR 1 created the topology; PR 5 added `captureRules` and made `EffectOutcomes` depend on `ControlInputs`), about 4 edits in all. **0** regenerations failed on Fluxtion's field-mapping rules. Twice a regeneration needed the previously generated source patched by hand first: the generator runs after compilation, so a changed node constructor breaks the old generated file. That is build plumbing, not authoring. |
| P2 | **Right on bugs; better on reading generated code.** | The audit and its invariants exposed 4 defects before any generated source was read: the drain time budget that starved a stop (PR 1, from the audit); a second record from one node in one cycle overwriting the first, seen as `seq` gaps (I10; Fluxtion logs one entry per node per cycle); and two op-id collisions in the capture rules (I3, I9). The generated processor was opened **0** times to explain behaviour. It was grepped 3 times to confirm a predicted dispatch order, and each prediction held. |
| P3 | **Right, though not quite as predicted.** | The invariants caught defects the example tests missed: a reply requested twice (I13, PR 1 mutation M2b), and an op-id collision on a duplicate completion after its root's work had ended, found by the seeded harness while every unit test and the resource ledger passed (PR 5). However, generic invariants alone **missed** a mutation (a cancelled add started anyway). Catching it took per-scenario expectations in the harness, so both are needed. |
| P4 | **Better than predicted.** | Rewiring broke **6** of 499 tests, not 10–30. The fixes landed in the group and driver code, never in the rules. Later PRs changed waiting semantics, which changed a further 3–7 tests each, all of them tests that filled a held group from the thread that now waits. |
| P5 | **Right.** | The rule nodes are 862 of about 4,950 added lines of main source (about 17%). Integration took most of the work: terminal admission, close and death, bounded shutdown, threading and replies, the service lock, and capture's asynchronous plumbing. |
| P6 | **Mostly right; threading APIs were missed.** | Milestone reviews found: PR 2, 6 defects, all in shutdown (lost agent-thread stop, unbounded feeds, a dead group hidden by closing); PR 3/4, 10 findings in replies, the service lock and handles (a reply-executor self-deadlock, waits under the lock). None was in the generated dispatch, and almost none in rule logic. Close, death and shutdown were as hard as predicted; the threading API around them was harder than predicted. |
| P7 | **Right on the duty cycle. An unexplained end-to-end gap.** | Measured 2026-10-25 with `ControlPlaneCostBenchmark` against the base (#69), both on fluxtion-runtime 1.1.2, running each build alternately (Apple M4, JDK 25). **Idle duty cycle** (`doWork()` with an empty mailbox): 12.6–13.3 ns on both, so no difference. **End-to-end throughput** (feed → processor through a real server): base 10.2–10.8 M events/s, this branch 9.0–9.5, about 12% lower, reproducible. It is not the control plane on the event path: removing the mailbox check from `doWork()` left throughput unchanged (9.3–9.5). A bisect shows PR 1 complete (the generated control processor, 6fe9687) at base speed (10.3–10.7). The drop appears at e42e2cb (bounded shutdown), which changes no per-event code. JFR profiles of the two show the same work and allocation, one young GC each, and no extra busy thread; a padded counter and reordered phases did not move it. The likeliest causes are JIT or code-layout decisions, or performance- versus efficiency-core placement on the M4. Neither is confirmed, so it stays an open finding: rerun on Linux with pinned cores (or under JMH) before release. |

The P7 figures above are the original author’s measurements, not a new qualification of the integrated candidate. The benchmark counter padding was corrected during review. The result remains unresolved until repeated with the corrected harness on a quiet machine; pinned-core Linux validation is still outstanding.

### What the predictions missed

- **Tests that pass for the wrong reason.** A test-compile failure hid behind a filtered log once, and a leaked agent
  mark made tests order-dependent until a JUnit extension cleared it after every test. The audit cannot catch this;
  build hygiene can.
- **The framework's own rules surfaced through the audit.** The one-record-per-node-per-cycle rule was never read
  about. It was seen as `seq` gaps in an exported record and fixed in the authoring.
