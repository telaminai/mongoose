# Response to the second independent review

Reviewed material: [`c184e5b`, second-review.md](https://github.com/telaminai/mongoose/blob/c184e5b/docs/review/pr76-external/second-review.md),
based on the earlier runtime `4f5851e` and review package `72ecb67`. This response distinguishes that review's
findings from the fixes already qualified at `761060947f6087437ccfff2e4dde21f41f7e77ba`.

## Reproductions on the fixed runtime

Both new test files were copied **unchanged** from `c184e5b` into a separate detached checkout of `7610609`,
then run with `mvn -o -Dtest=RetirementOtherNameSettledTest,GeneratedProcessorWedgeTest test` on Java 21.
The six test executions passed, with two very different meanings:

- `RetirementOtherNameSettledTest`: **five repetitions passed**. Waiting for asynchronous retirement no longer
  reveals a stop of the instance still registered under B. The earlier immediate assertion is not used as the
  sole evidence. The standard suite additionally tests pending sibling withdrawals, double physical stop,
  shutdown ownership and retained stop failure in `ServiceAliasRetirementProbeTest`.
- `GeneratedProcessorWedgeTest`: **confirmed the unresolved failure**, not a recovery: after the injected audit
  listener exception, `processing` remains true and the next event produces zero records. This is an isolated
  generated-processor test, outside Mongoose's guarded control driver. It does not test the client's own graph.

The exact test source remains on the reviewer branch; the preserved qualification logs include this invocation.

## Evidence independence and reproducibility

- The checked-in `IndependentEvidenceMutationTest` contains negative exported traces for missing replies,
  premature replies, missing release, renamed rules and engine anomalies. Those fixtures now require rejection.
- I15 derives pre-reply obligations from the sequence of effect requests and replies, not `pendingCleanup` or the
  engine's `cleanup` flag. I17 derives stop obligations from inputs and successful generations, not rule names.
  These still depend on faithful exported records; they cannot independently prove an effect physically occurred.
- The harness checks processor `stop` and `tearDown` callbacks at reply time. Its `Reservations` map belongs to
  the test and is changed through the supplied release callback; it is not the server's live reservation map.
  A direct re-add assertion would provide an additional end-to-end admission check; the map alone is not proof
  of the production server's admission implementation.
- Capture accounting is independently recorded by `CaptureRig.FakeResources`: every open must have exactly one
  close attempt and a recorded success or injected failure, and no resource may remain installed. These checks
  run **before group close** in the pre-close scenarios. Engine permit counts and offline flags are supplementary.
- [run-source-mutations.py](run-source-mutations.py) preserves executable source mutations for missing stop reply,
  APPLIED without release under a renamed rule, missing stale-open compensation, skipped terminal sweep and
  dropped claimed stops. It clones the supplied repository into a new scratch directory at the qualified SHA,
  runs a positive control, applies each mutation separately, requires JUnit assertion failures (not compilation
  failure), restores sources, and retains logs. It does not modify the supplied checkout.
- Safety-net classification still uses reason text alongside structured Anomaly/Dead records. The reviewer is
  right that structured failure kinds would resist wording changes better; this remains an explicit hardening
  item, not a claim that strings form a stable protocol. Intentional death scenarios assert their expected
  diagnostics separately rather than marking them healthy.

The [recorded mutation results](source-mutation-results.json) show a clean 37-test baseline and assertion failures
for every mutant: missing reply 18, missing release 19, stale-open leak 2, skipped sweep 1, dropped claimed stop 7.
Each run executed 37 tests with zero test errors. [Full mutation logs](source-mutation-logs.tar.gz) are preserved.

## Client gates versus general engine qualification

| Area | Disposition |
| --- | --- |
| Capture lifetime leaks, detach diagnostics and phantom directories | Client-critical. Fixed and covered by independent fake-resource checks, real Chronicle fixtures, failed-open cleanup and client-shaped YAML acceptance. Client plugin/deployment acceptance still required. |
| Replay detach/status/start failure and newline outcomes | Fixed for general engine correctness. This client's lack of replay means these are not reasons to block its deployment independently. |
| Dynamic service alias retirement | Fixed for general engine correctness; stronger reviewer reproduction passes. Static client does not exercise the dynamic operation. |
| Generated data-processor exception path | **Open client gate.** Inspect the client's actual compiler/runtime versions and generated sources, inject handler/auditor failures, and establish a safe host response. A passing control-plane soak does not close this gate. |
| Feed classification and capacity/loss policy | **Open client gate.** Identify each feed/subscriber's tolerated loss and application reaction. Current queues can drop and cannot promise lossless orders, fills or session events. |
| Actual plugins, reconnect, retention scripts and shutdown policy | **Open deployment gate.** The core YAML fixture is not a substitute for the client's plugin regression/reconnect campaign. |

On a generated data-processor exception, neither retrying blindly nor checking only queue drainage proves
processing has recovered. The deployment runbook must require application progress/sequence evidence and an
agreed fail-closed response. No automatic trading restart, duplicate-sensitive replay or flattening policy is
inferred here. This candidate is for independent review; it is not cleared for client migration.

## Overload and upstream compiler proposals

The second review's BLOCK objections are accepted as design constraints, not implemented guarantees. No BLOCK
policy is introduced by this candidate. Issue #77 must resolve cancellation on unsubscribe/shutdown, never
parking an agent thread, fan-out atomicity or explicit per-subscriber outcomes, and pooled-item ownership on
refusal before implementation. Per-subscriber policy, conflation, session/control distinctions and priority
also need explicit decisions. Keeping today's spin/drop behaviour does not establish its suitability for the
client's critical feeds.

The proposed compiler change must cover all processing brackets and define dirty-state cleanup, failure audit
closure and ownership of queued callbacks. Simply clearing `processing`, dispatching callbacks in a finally,
or discarding them cannot be assumed safe: callbacks may own open capture resources and partial graph state may
already have changed. Mongoose's control fail-closed/orphan guards remain in place. An upstream recovery contract
and the client's data-processor protection are separate decisions; the latter is not deferred merely because
compiler work is listed as post-release in the original package.
