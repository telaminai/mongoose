# PR #76 release gates

Candidate reviewed: `4f5851e04a6b131a54b1741ee063fb0cc25e786a`. Every gate below must be closed, on one frozen
commit, before #76 merges and before the client migration. Each gate names the change, the proof, and the owner
type (engine / test / docs / decision). Line numbers are at 4f5851e.

Definition of done for the whole list: all named reproductions pass or are inverted as stated; `mvn test` BUILD
SUCCESS; the qualification campaign (gate Q) rerun on the frozen SHA with exports kept; no existing regression
assertion weakened.

## E. Engine fixes

| # | Gate | Where | Proof |
| --- | --- | --- | --- |
| E1 | A replayed processor stopped live mid-stream records an ended reason; `complete()` is not true while entries are undelivered | `replay/GroupReplayer.java:164-186` (`detach`): when `!c.done()` put an ended reason ("left the group at entry N of M before its replay completed") | `ReplayCursorDetachedMidStreamTest` passes |
| E2 | Replay status API safe off the agent thread | `GroupReplayer.java:33` cursors to `CopyOnWriteArrayList`; `Cursor.next/stopped/ended` volatile, or publish an immutable status snapshot per `doWork` | `ReplayStatusApiConcurrencyTest` passes; `ChaosReplayStressTest` poll loop (l.154-160) unchanged and green |
| E3 | Retirement supersession by instance, not old name | `MongooseServer.java:988-989` (`RemovedService.stop`): superseded if any live registration's `instance()` is this instance; or `reserveServiceName` refuses an instance with an unconfirmed retirement in `removedServices` | `RetirementOtherNameProbeTest` passes; `HuntServerFailureModesTest.deferredRetirementMustNotStopAnInstanceRegisteredAgain` and `RetirementReregistrationRaceTest` still pass |
| E4 | Unrouted `processorStopped` never ends a newer generation's capture | `internal/ChronicleAuditCaptureService.java:506-510`: fall through only when `sinks.get(name)` exists with `dataFlow == flow`; legacy `processorStopped(name, flow)` (l.536-543) ends only `sinks.get(name)` via `endRecordingIfGone`, never `stop(name)` | `ChronicleCaptureEdgeTest.retiringAnOldGenerationMustNotStopTheNewGenerationsCapture` and `ChronicleGroupInlineTest.replacementGenerationWithoutServerLeavesRetiredBindingAttached` pass |
| E5 | Recorded `start()` failure produces a Failed marker so replay stops | `dutycycle/ComposingEventProcessorAgent.java:787-790`: for a recorded flow, `recorder` appends `Failed("start", ...)` (new recorder method) before the FAILED result | `RecordedStartFailureReplaysAsSuccessTest` passes (replay stops with "the recorded run failed here") |
| E6 | Failed post-release detach is listed as unresolved and control table stays in step | `ComposingEventProcessorAgent.java:988-1010`: on detach-retry failure set `permit.closeFailure` (or a `detachFailure`) so `captureCloseFailures()` lists it; `CaptureRules.onResult` "CloseCapture" FAILED branch must not `removeResource` while the agent holds the handle | Invert `ChronicleGroupInlineTest.failedDetachRetainsSlotButIsNotListedAsCloseFailure` to assert the failure is listed; `closeGroup` exception text names it |
| E7 | `attachScoped` refuses a second identity on the same `DataFlow` | `ChronicleAuditCaptureService.java:245-254` | Invert `ChronicleCaptureEdgeTest.twoScopedIdentitiesOnOneFlowClobberEachOther` to expect `IllegalStateException` |
| E8 | `openQueue` leaves nothing on disk when the Chronicle build throws | `ChronicleAuditCaptureService.java:735-765`: try/catch around `build()`, delete the temp dir and sidecar | New test forces a build failure; asserts directory gone and `listAvailable` does not show it |
| E9 | Phantom and stale listings cannot appear | follows from E8 plus existing c7c92da invalidation | `AuditIntrospectionCacheRaceTest` still green |

## T. Test-oracle independence (fix before rerunning qualification)

| # | Gate | Where | Proof |
| --- | --- | --- | --- |
| T1 | Driver safety-net replies and engine-declared anomalies are violations | `src/test/.../validation/ControlInvariants.java:24` (FALLBACK_REPLIES) and l.84-93: a fallback `FAILED` whose reason starts with "no reply after its effects settled", "effects did not settle", "control processor threw", "abandoned", and any `Anomaly`/`Dead`/`CaptureOrphan`/abandoned `EffectOutcome`, is a violation | Mutation M3 (stopComplete without Reply) caught by invariants and harness; `InvariantVacuityProbesTest.driverSafetyNetFailedReplySatisfiesI2` and `anomalyAndDeadFallbackRecordsVerify` inverted to expect violations |
| T2 | I15/I8 do not trust the engine's `cleanup` flag | `ControlInvariants.java:66, 175`: exempt only effects whose requesting Decision seq is after the Reply seq (use `pendingCleanup` lists); I8 flags any failed non-cleanup effect of an APPLIED request regardless of seq | Mutation M2b (Reply(APPLIED) before ReleaseName outcome) caught; `replyBeforeItsEffectsOutcomeVerifies` inverted |
| T3 | APPLIED stop implies release | Harness ledger (`ControlScenarioHarnessTest.java:311-326`): every bound stop answered APPLIED has stop+tearDown recorded and its name gone from `Reservations.table` before group close; new invariant: a `stopStarted`/`closeGroup` Decision requests `Release` for the generation and the APPLIED Reply follows the `Release` and `ReleaseName` outcomes | Mutation M2 (APPLIED without Release) caught by ledger on every seed, not only via I1 rule name; `appliedStopWithoutReleaseVerifies` inverted |
| T4 | Harness observes requests independently | `ControlScenarioHarnessTest.java:219, 232, 342`: keep every `ControlRequest` sent; assert `reply().isDone()` and the reply is APPLIED or a by-design refusal; stop using `group.control().outstanding()` as the oracle | Mutation M1 (drop 1/7 stops after claim) caught on every seed |
| T5 | `ControlDriver.sweep` exercised deterministically | New harness scenario: senders paused between reserve and publish at close/death (`registerUnpublished` seam) | Mutation M5 (sweep skips 1/5) caught |
| T6 | Lifetime capture leaks are findings, not shutdown cleanup | Harness and chaos assert `captureResourcesHeld()==0` and zero `offline:true` fallback records before `onClose`/shutdown (harness already drains IO at l.306); invariant: a resource whose only close is `offline:true` while the group was not dead is a violation | Mutation M4 (captureOpenedStale requests no close) caught |
| T7 | Fault injection covers failed uninstall/open/install | `ControlScenarioHarnessTest.java:268-270` add `failUninstalls`, `failOpens`, `failInstalls`, `timeoutOpens` | Exported harness/chaos audits contain `captureUninstallFailed`, `captureOpenFailed`, `captureInstallFailed`, `captureOpenTimedOut`, `captureCloseFailed` records; I1, I4-failed-close, I7, I11, I12, I14, I16 each fire at least once on an intentionally bad seed |
| T8 | Chaos oracle tolerances made explicit | `ChaosWorkload.java:712-731, 836-843, 873-895`: a stop by handle of a running instance must be APPLIED or REFUSED-with-mailbox-reason; `floodThrew` asserted or documented; sync stop timeouts bounded and reported | `ChaosAuditVerdictTest` extended |

## C. Capture migration (client-critical)

| # | Gate | Where | Proof |
| --- | --- | --- | --- |
| C1 | New layout documented with migration note for external tooling | `docs/design/capture-resource-identity.md`, `docs/reference/control-plane-contracts.md` (Audit capture), `docs/release/release-notes-unreleased.md`: `capture-*` directories, `.mongoose-capture.properties` schema, old layout read-only | Docs reviewed; `audit.list`/`audit.status` show capture id, selector and path |
| C2 | Programmatic "latest capture for G/X" and "all historical captures for X" | `MongooseAuditIntrospectionService` | Unit test on a root with both layouts |
| C3 | Key and metric renames documented as breaking | Release notes: `currentSinks`/`liveSinks` keys and `audit.<selector>.records` now percent-encoded `group/name`; counters restart per session; exact example for `g`/`p` | Docs reviewed |
| C4 | Bare name still works for a unique name on every surface | `audit.start p`, `audit.stop p`, `audit.status p`, `isRecording("p")`, `currentSink("p")`; ambiguity error names the exact selector to type | Test with same name in two groups and with a unique name |
| C5 | Selector contract qualified to the built-in service | `control-plane-contracts.md:562-567`; `MongooseAuditCaptureService` javadoc says selector support is the implementor's job; autoStart with a custom router that declines `sendScoped` is documented as dropped with a WARNING | Docs reviewed |
| C6 | Shutdown with a failed Chronicle close has an operational story | **DECISION**: (a) bounded retry of physical close on a resumed stop, or (b) runbook entry only. Either way: test forces one close failure at shutdown and asserts exact `StopResult`, log lines and audit records | New test; runbook section S3 |
| C7 | Uninstall/stop/tearDown/close order documented | Contracts Shutdown section: uninstall before `stop()`, physical close after release; records emitted in `stop()` reach the restored listener | Docs reviewed |
| C8 | YAML autoStart boot path tested | Config with capture enabled and autoStart for two processors in two groups; both recording before `start()` returns (or the exact moment documented); `audit.status` output asserted; `audit.start` from an agent thread documented as "requested" | New test |
| C9 | No capture or directory for a fail-fast boot failure | | New test |
| C10 | Retention responsibility stated | **DECISION**: who prunes the capture root; Mongoose does not | Docs reviewed; client's pruning tooling checked against new names |
| C11 | Client-shaped end-to-end test | YAML boot, two groups with the same processor name, autoStart on, events, admin start/stop/list, shutdown: StopResult complete, every `capture-*` has a sidecar, listing joins live and historical by capture id, exact listener restored after each stop | New test, also the source of the runbook's worked example |
| C12 | Release notes "New API" complete | add `setCaptureResourceLimit`, `captureResourceSlotsInUse`, `captureCloseFailures`, `setCaptureIo`, `setAuditCaptureResources` | Docs reviewed |

## D. Documentation and compatibility

| # | Gate | Where |
| --- | --- | --- |
| D1 | Sealed `ReplayEntry` growth and new CSV kinds documented as source/format breaks; @Experimental stated | Release notes |
| D2 | `ComposingEventProcessorAgent` void→`ControlRequest` signature changes and `LifecycleManager.stop` return type noted as binary-incompatible | Release notes |
| D3 | `MongooseServerController` javadoc for `registerService`/`startService`/`stopService` mentions `IllegalStateException` while stopping | `service/servercontrol/MongooseServerController.java` |
| D4 | Duplicate-names paragraph says a failed start frees the reservation during Release (`MongooseServer.java:457`), before the FAILED reply | Contracts |
| D5 | Replay withdrawal retention paragraph says "until shutdown" unconditionally, matching `ComposingEventProcessorAgent.java:845-846` | Contracts |
| D6 | Loss contract documented (see `77-overload-policy-spec.md` for the policy itself): 10 ms spin then drop per target queue (`EventToQueuePublisher.java:462, 478, 483-485`), `droppedCount`, no backpressure; `SlowConsumerStrategy` marked not honoured (or implemented); drain-phase drop at shutdown stated; drops surfaced via counters and health; link #77 | Contracts new section "Overload and loss"; release notes Known limits; health service |
| D7 | Control-audit how-to for the analyser: export from a running server through the admin surface (add the command if missing), verify, open with the graph, follow one `rootId`; `COMPLETE` means nothing lost, not behaviour correct | `docs/example/how-to/` |

## S. Shutdown runbook (`docs/operations/shutdown-runbook.md`, linked from contracts and release notes)

| # | Content |
| --- | --- |
| S1 | Phases with bounds and effect on client components: startup fence; feeds (feed-only groups closed, sources removed from shared groups, sources stopped, 5 s cap); drain (`shutdownDrainTimeoutMillis` 2 s, remaining queued events dropped); processor groups on helpers (Agrona interrupts the group thread after `RETRY_CLOSE_TIMEOUT_MS`; a `stop()` doing I/O is interrupted); service agent groups; registered and retained services; capture `stopAll`. State that order connectors lose feeds before processors stop |
| S2 | Reading `StopResult`: `complete`, `handedOff` (use `awaitStopped` elsewhere), `blockedStartup`, every `groupsRunning` prefix and note (`processor:`, `service:`, `<event sources stopping>`, `<audit capture stopping>`, `<audit capture stop failed: ...>`, `<stop failed: ...>`), `servicesLeftRunning`; cause and next action for each |
| S3 | Resuming: what a second `shutdown()` retries (groups, failed group helpers) and never retries (a service `stop()` that threw, a failed physical capture close); decision rule for letting the process manager kill the JVM; disk state after a kill; how the analyser treats an uncleanly closed capture |
| S4 | Capture diagnostics: `captureResourceSlotsInUse`, `captureCloseFailures`, the `CleanupPending` record, exact log lines to grep |
| S5 | Timeouts and defaults: `shutdownTimeoutMillis` 30 s, `shutdownDrainTimeoutMillis` 2 s, feed cap 5 s, Agrona close retry 5 s, `controlWaitMillis` 10 s, capture open timeout 30 s. **DECISION**: recommended values for a trading deployment that flattens positions on the way down; which the process manager's grace period must exceed |
| S6 | Worked examples: one clean shutdown log from C11 annotated per phase; one incomplete shutdown (C6) annotated with operator actions |
| S7 | Testable: C11 asserts the log lines and StopResult shapes the runbook quotes |

## Q. Qualification rerun on the frozen SHA

| # | Gate |
| --- | --- |
| Q1 | `mvn -o package`: BUILD SUCCESS, zero failures/errors; skipped count explained |
| Q2 | `ControlScenarioHarnessTest` on at least two seed ranges not previously used, with T7 fault injection on |
| Q3 | `ControlPlaneChaosStressTest` 30 s, two seeds; exports kept; invariants fire on an intentionally bad seed (T7) and are clean on the real runs |
| Q4 | `ControlPlaneSoakTest` 600 s on the frozen SHA (the current soak ran on an earlier runtime) |
| Q5 | `ShutdownStormStressTest` 20 cycles; `ChaosReplayStressTest` 30 s |
| Q6 | Client plugin set rebuilt against the SHA; their regression suite green; C11 green; one forced connector reconnect and one forced capture close failure exercised |
| Q7 | `results.json` updated with the frozen SHA, commands, counts, archive hashes; the review reproductions listed as passing |
| Q8 | Pinned-core Linux throughput comparison remains an open item and is stated as such in the release notes |
