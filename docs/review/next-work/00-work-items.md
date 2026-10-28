# Next work after Mongoose 1.1.1: item list and where each is tracked

Focus (owner, 2026-10-28): first-use simplicity and a trustworthy main flow in the analyser. Item 1 is raised as an
issue and specced for review. All other items are tracker items: kept in this list and the repos' trackers, raised as
issues when they are scheduled.

Checked against open GitHub issues on 2026-10-28 (mongoose, fluxtion, fluxtion-compiler, fluxtionauditlog-analyser,
mongoose-plugins). "Not tracked" means no open issue and no tracker line found.

| # | Item | Priority | Tracked today | Spec |
| --- | --- | --- | --- | --- |
| 1 | Evidence-set key: runtime header record, Mongoose host context and sidecar, analyser pairs log and graph by fingerprint | 1 | **Issue raised** (see README) | `01-evidence-set-key.md` |
| 2 | Analyser reads Mongoose 1.1 evidence (completeness, fallback, FailureKind, degraded spans, overload/loss, capture identity) | 1 | Partly: analyser `docs/specs/tracker.md` item 5 "Mongoose 1.1 follow-through" (no issue, no PR) | `03-...` section 3.5 |
| 3 | Analyser evidence-bundle bugs that break support handoffs | 1 | analyser #178, #179, #109 (open) | `03-...` section 3.6 |
| 4 | Analyser pins: runtime 1.1.3, builder 1.0.83, Mongoose 1.1.1 fixtures (today 1.1.0 / 1.0.76 / Mongoose 1.0.32) | 1 | Partly: tracker item 5 | `03-...` section 6 |
| 5 | Order-path visibility: checked publish, outbound overload state event, sizing warning, per-processor labels | 1 | Partly: mongoose#79 design section 11 lists it as "proposed" | `02-order-path-visibility.md` |
| 6 | Synthetic trading system as soak and acceptance test (client topology, overload and failure injection, shutdown runbook, capture to analyser loop) | 1 | **Not tracked** | to write (spec 03) |
| 7 | Fluxtion failed cycle evidence: audit the discarded queued count, name the failing node, handler and exception in the closed record | 2 | **Not tracked** (fluxtion#47 shipped the fix without these) | `fluxtion-issue-generated-processor-wedge.md` items 2-3 |
| 8 | Fluxtion: `@AfterEvent` runs on a failed `runInEventCycle` but not on a failed `processEvent`; make consistent or document | 2 | **Not tracked** | — |
| 9 | Fluxtion: merged duplicate-key node-log entries (the "one record per node per cycle" question); optionally tag each entry with its callback type | 2 | fluxtion#46 (open, docs question) | — |
| 10 | Mongoose: regenerate stale test fixtures `replay/generated/AlarmProcessor`, `CycleAlarmProcessor` with runtime 1.1.3 | 2 | **Not tracked** | — |
| 11 | Remote generation disclosure: Mongoose pins builder 1.0.80, before the 1.0.81 clear-text disclosure; decide pin and client generation route | 2 | **Not tracked** | — |
| 12 | Maintainability M2-M8 (ownership table, capture adapter, service registry, named invariants, per-server singletons, public surface) | 3 | **Not tracked** | `../pr76-external/maintainability-plan.md` |
| 13 | Oracle as a module with vacuity report (maintainability M3, failure-handling M6) | 3 | Partly: `docs/design/failure-handling.md` milestone M6 | maintainability plan M3 |
| 14 | `mongoose-testing` fault-injection artifact | 3 | Partly: failure-handling milestone M5 "not started" | — |
| 15 | Deferred overload: unbounded BLOCK, DISCONNECT, EXIT_PROCESS, conflation, per-feed drain reporting | 3 | mongoose#79 (open), design section 11 | — |
| 16 | Shared canvas independent review | 3 | analyser tracker M48.17 | — |
| 17 | Harden prerelease workflow | — | mongoose#84 (open) | — |

Only items 3, 9, 15 and 17 have an open issue of their own.
