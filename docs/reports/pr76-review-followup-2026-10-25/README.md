# PR #76 external-review follow-up and migration qualification

This work follows the independent review of `4f5851e`. It addresses engine defects, strengthens the test oracles,
and makes capture migration, overload and shutdown behaviour operationally visible. It is not a production
certification. PR #76 remains unmerged for independent review.

Qualification results and the exact runtime commit are recorded in `results.json` beside this report after the
campaign completes. No source changes are made during that campaign; the report/artifact commit is documentary.

## Review dispositions

| Finding | Change | Regression/evidence |
| --- | --- | --- |
| K1, K8: missing replies and invisible claimed requests | Independent request ledger checks every caller reply; safety-net failure, Anomaly and Dead are not healthy PASS; explicit unpublished sweep scenario | `ControlScenarioHarnessTest`, `IndependentEvidenceMutationTest`; no-Reply mutant now fails |
| K2: premature reply hidden by cleanup flag | Effect request sequence determines whether work preceded the reply; cleanup flag alone cannot excuse it | `IndependentEvidenceMutationTest` premature-reply probes |
| K3: APPLIED stop without release | At-reply callback/name ledger and semantic start/stop obligations, independent of rule spelling | Renamed-rule immediate-APPLIED mutant now fails |
| K9: final cleanup masks lifetime leak | Pre-close ownership checks, delayed-open compensation checks, open/install/uninstall fault injection | Stale-open compensation mutant fails before group close; 200-seed harness plus focused cases |
| K4, K5: detached replay and unsafe status | Retain incomplete-detach reason; safely publish cursor membership/status; copy captured outputs on query, not per event | Original `ReplayCursorDetachedMidStreamTest`, `ReplayStatusApiConcurrencyTest`, added `ReplayStatusPublicationTest` |
| K6: service stopped while live under another name | Physical-instance ownership across live aliases and all pending withdrawals; shared stop/failure owner | Original `RetirementOtherNameProbeTest`; new `ServiceAliasRetirementProbeTest` also catches pending sibling/double stop |
| K7: embedding retires replacement capture | Exact-flow/identity retirement and direct-group release hook; physical closure stays group-owned | `ChronicleCaptureEdgeTest`, `ChronicleGroupInlineTest` |
| K10: failed startup missing from replay | Persist startup failure marker, including best-effort fatal-path recording | Original `RecordedStartFailureReplaysAsSuccessTest`, `RecordedStartupFailureTest` |
| K11: retained detach failure not reported | Retain `CLOSE_FAILED` ownership, combined cleanup diagnostics, explicit retry outcomes | `ChronicleGroupInlineTest`, `CaptureAsEffectsTest`, forced Chronicle failure integration |
| K12, K13: compatibility/plugin scope | Document sealed variants, binary method changes, CSV forward incompatibility and built-in selector boundary | Release notes and interface contracts; custom router refuses unsupported scoped dispatch |
| K14: remaining hardening | Same-flow attachment guard; failed-open directory cleanup and cache invalidation; ReleaseName owns failed-start reservation; multiline CONTROL64; stopping javadoc | `CaptureOpenFailureCleanupTest`, `FailedStartNameOwnershipTest`, `CsvControlOutcomeTest`; release notes document replay-retained withdrawals |

The original replay reproductions failed before their fixes and pass unchanged. The original capture reproductions
that expected unsafe outcomes were converted into assertions of the corrected behaviour, retaining their
interleavings. Probe tests whose old purpose was to confirm a validator hole now assert rejection. Existing
regression assertions were not relaxed to accept the reviewed defects.

Mutation checks matter here: the old checker accepted the unsafe traces; the strengthened checker rejects them.
Removing the stop reply now fails 20 of 31 focused/seeded tests; replacing release with an immediate APPLIED reply
under a renamed rule fails 21 of 31. Omitting stale-open compensation fails its independent pre-close resource
ledger. Temporary mutants were restored before committing.

## Capture and operations migration checklist

| Requested items | Implementation / decision | Proof or limit |
| --- | --- | --- |
| 1–3: layout, discovery, failed open | Session directories/sidecar schema documented; `capturesFor` / `latestCapture`; failed opens remove only their newly created directory and invalidate cached discovery | `CaptureHistoryApiTest`, `CaptureOpenFailureCleanupTest` |
| 4–6: selectors and metrics | Built-in group-qualified keys and exact ambiguity choices; interface plugin boundary documented | YAML acceptance and history/admin tests. **Correction to review premise:** handle counts reset per session; the named Agrona metric is cumulative |
| 7–9: failed close and stop ordering | Keep uncertain closes unretried; expose physical and detach failures; restore listener before processor lifecycle callbacks on successful uninstall | `ChronicleShutdownFailureEvidenceTest`, embedding regressions; shutdown runbook |
| 10–11: boot | Auto-start is asynchronous; observe status. Fail-fast startup does not open its pending capture | `CaptureMigrationAcceptanceTest`, including a child JVM using the actual process-exiting boot policy |
| 12: retention | Core does not prune; `retainHours` is not enforced; migrate external discovery/pruning by metadata | Client scripts have not been supplied; deployment check remains open |
| 13–14: direct embedding | Exact scoped retirement and same-flow guard | External capture regressions |
| 15: control investigation | Bounded asynchronous admin export, paired verification, deployed graph, non-blocking resource status and analyser how-to | `ControlAuditAdminExportTest`; portable case-study bundle |
| 16–17: acceptance/API | YAML, three generated processors/two groups, shared name, feed input, admin capture cycle, history and listener checks; API migration list | `CaptureMigrationAcceptanceTest` |
| 18–22: overload | Preserve existing bounded-spin/drop and immediate replay-offer policies; deprecate ignored strategy; expose per-queue loss and first-drop evidence | `QueueDropObservabilityTest`. Blocking/lossless policy remains issue #77; no guaranteed order/fill delivery claim |
| 23–29: shutdown runbook | Phases, actual result names, retry limits, capture diagnostics, budget guidance and asserted log examples | Successful YAML test and real-Chronicle failure fixture preserve logs; application flattening is a separate deployment concern |

The overload health-check lifetime was independently reviewed: multiple callback bindings sharing a queue now
retain the check until the last binding is removed. A removed service alias now reports its own `.up` gauge down
while another alias keeps the physical service alive. Metric updates remain best-effort under concurrent
re-registration; they are not an atomic lifecycle ledger.

## Evidence boundaries and remaining deployment work

- A complete export and a strong validator still do not prove arbitrary plugin effects or every interleaving.
- Chaos exercises injected lifecycle/capture failures; the deterministic fixtures cover failed physical close,
  failed detach, death, sweep and late-open paths that a particular random trace may not contain.
- Replay qualification covers recorded boot anchors; it does not establish arbitrary dynamic service-history
  reconstruction or unsupported operations.
- Linux pinned-core latency/throughput/GC qualification remains open. These functional tests are not benchmarks.
- External capture plugins, REST consumers, import/pruning scripts and the client's application-specific shutdown
  policy need deployment acceptance. Interface compatibility is not proof of their behaviour.
- The present queue policy can lose inputs. Issue #77 owns the further failure/backpressure design; do not claim
  this release makes order, credit or hedge delivery lossless.

See the [capture migration](../../design/capture-resource-identity.md),
[release notes](../../release/release-notes-unreleased.md),
[shutdown runbook](../../operations/shutdown-runbook.md) and
[analyser case study](../../case-studies/control-audit-investigation.md).
