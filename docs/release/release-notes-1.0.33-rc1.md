# Release notes — Mongoose 1.0.33-rc1

!!! warning "Pre-release candidate"
    Prepared from PR #76, including PR #69. Publication is subject to qualification and merge approval.
    This candidate targets Fluxtion runtime **1.1.3** and compiler **1.0.80**; the dependency upgrade and
    regenerated control processor require fresh qualification before publication.
    **A pre-release is not clearance for client migration.** Complete the separate
    [client migration checklist](client-migration-1.0.33.md) before deployment.

## What is new

- A Spring-authored, generated Fluxtion control processor makes group lifecycle decisions and emits an inspectable audit.
- Routed audit capture has group/name/generation identity, unique session directories and explicit resource ownership.
- Shutdown has one deadline and reports incomplete cleanup. Failed generated processors are quarantined.
- Queue drops and group health are observable. `addFailurePolicy` and `controlWaitMillis` make caller behaviour explicit.
- Recording and replay are experimental; replay does not reconstruct dynamic service history.

**What this page covers.** The caller-visible behaviour changes of the control plane: what code calling Mongoose will
notice. Each item links to its contract in [Control-plane contracts](../reference/control-plane-contracts.md). Where
an item replaces a 1.0.33 release note, it says so.

## Behaviour changes

### Admin registry construction preserves processor context

The admin registry's built-in commands can now be constructed inside a processor callback before the registry's
event-flow service is injected. Construction preserves the caller's processor context and keeps these built-ins
direct; processor-owned commands registered after injection keep their existing dispatch semantics.

### Generated processor failures are quarantined

A failed generated graph is isolated without retry or automatic restart; healthy processors in its group continue.
The failed name and instance remain owned, generated lifecycle callbacks are not re-entered, and shutdown reports
incomplete cleanup. `ControlGroupStatus.quarantinedProcessors()` and the `ProcessorQuarantined` control record
expose the failure. Opaque dispatchers may require ending the group. Ordinary handwritten processor retry behaviour
is unchanged. See [containment](../reference/control-plane-contracts.md#generated-processor-failure-containment)
and the [shutdown runbook](../operations/shutdown-runbook.md).


### Void control methods wait for their outcome

`addEventProcessor`, `stopProcessor(group, name)`, `registerService` and `removeService` now wait, off agent threads,
for the change to be applied, up to `controlWaitMillis` (default 10 s). After a timeout they log and return, and the
request still applies. On agent threads, and on groups that have not started, they queue the request and return.
[Contract](../reference/control-plane-contracts.md#timeouts-detach)

### A failed add can throw, if you ask it to

New `MongooseServerConfig.addFailurePolicy`. `REPORT`, the default, keeps the 1.0.33 behaviour: a failed `start()` is
reported and the void `addEventProcessor` returns. `THROW` makes it throw `ProcessorStartException`, or
`IllegalStateException` for a refused add. [Contract](../reference/control-plane-contracts.md#addfailurepolicy)

### Adding a processor while the server stops throws

`addEventProcessor` throws `IllegalStateException` ("server is stopping") once `shutdown()` has begun.

### `shutdown()` is bounded, truthful and resumable

New `StopResult shutdown()`. `stop()` still exists and now delegates to it. The whole stop is bounded by
`shutdownTimeoutMillis` (default 30 s). A stop that runs out returns an incomplete `StopResult` naming what is still
running, leaves the server `STOPPING`, and stops no service while any processor group is still running. **Calling
`shutdown()` again resumes it.** `awaitStopped` returns `true` only once a stop has completed.
[Contract](../reference/control-plane-contracts.md#shutdown)

*Replaces the 1.0.33 note* "stop() ... is idempotent; concurrent callers wait": a stop is now resumable rather than
run once, and a concurrent caller waits only within its own deadline. A stop called on an agent thread is still
handed to a `mongoose-server-stop` thread, and `StopResult.handedOff()` now says so.

### Stopping a server that never started closes its groups

`shutdown()` on a server that was never started now closes every group that exists. Their queued adds are answered
`NOT_RUNNING`, and their suppliers never run. Before, it returned at once.

### Service changes are refused while the server stops

Once `shutdown()` has begun, `registerService`, `startService` and `stopService` throw `IllegalStateException`.
`removeService` is ignored (logged), and the shutdown stops the service itself.
[Contract](../reference/control-plane-contracts.md#refusal-while-stopping)

### A removed service is stopped only once every group has let go of it

`removeService` now withdraws the service from every group first, and calls `service.stop()` only when every group
has confirmed the withdrawal. If any group did not confirm (it missed the broadcast, or a `@ServiceDeregistered`
handler threw), the server keeps owning the service and `shutdown()` stops it.
[Contract](../reference/control-plane-contracts.md#removeservice)

*Replaces the 1.0.33 note* "Services across groups: ... the service stays registered".

### Audit capture opens off the processor's thread

`MongooseAuditCaptureService.start` (and `audit.start`) now opens the Chronicle queue on an I/O helper and installs it
on the processor's thread. It returns once the capture is recording. An open has its own 30 s timeout, and each group
allows at most 8 opens in flight and 1024 unresolved physical resource slots; beyond either limit, starts fail.
Failed closes retain their resource slots and leave shutdown incomplete. `stop` returns once the capture is uninstalled, and the
queue closes afterwards. [Contract](../reference/control-plane-contracts.md#audit-capture)

*Replaces the 1.0.33 note* "Audit start/stop (#74): applied on the processor's agent thread ... (bounded 5 s)". The
5 s bound now covers only the wait for the group to begin applying the request.

### Capture storage and monitoring migration

Every server-managed session now gets `<capture-root>/capture-<unique-suffix>/` and an identity sidecar
`.mongoose-capture.properties`. Historical name directories remain discoverable but routed captures no longer
write there. Importers, analyser discovery scripts and retention jobs must use returned capture ids/paths and
metadata instead of deriving a directory from a processor name. See the [layout/schema migration](../design/capture-resource-identity.md#migration-for-capture-importers-and-support-tooling).

For processor `p` in group `g`, `currentSinks` and `liveSinks` use key **`g/p`** and the record counter is
**`audit.g/p.records`**, replacing bare-name keys. Special characters are encoded by `AuditCaptureIdentity.selector`.
These are breaking key changes for dashboards and REST consumers, including integrations with `svc-admin-web`.
Handle record counts and timestamps restart for each session; the named Agrona metric is cumulative for the label.
Unique bare names still work for operator start/stop/status
and current-sink lookup; ambiguous names require the exact qualified selector printed by the error.

YAML `autoStart` is asynchronous. Boot returning confirms processor startup, not completion of capture I/O.
Poll `audit.status` / `isRecording` before depending on recording, and investigate failures rather than waiting
indefinitely. From an agent thread, `audit.start` means requested; status is the observation of recording state.

The core does not prune captures, including when `retainHours` is set. Sessions accumulate; deployment tooling owns
retention. Existing client pruning/import scripts need a migration check. A failed physical close remains owned
and leaves shutdown incomplete; it is not blindly retried. The [shutdown runbook](../operations/shutdown-runbook.md)
describes diagnostics, evidence preservation and escalation to the process manager.

### Deprecated: `attach(..., Executor)` and `stopAll()`

`MongooseAuditCaptureService.attach(DataFlow, String, LogRecordListener, Executor)` and `stopAll()` are deprecated.
Each group installs, uninstalls and closes its captures itself. Both still work.

### Replay keys, provenance and control interception

- Replay stream keys use the per-name build ordinal (`name`, `name#2`, ...), and a `#` in a name is escaped as `##`.
  A recording made before keyed incarnations still reads. A legacy key that is ambiguous because of a `#` stops the
  replay and must be migrated.
- RECORD now records `Stopped`, `ServiceChanged` (with versions), `ControlCall` entries and each stream's provenance.
- REPLAY intercepts control requests from replayed processors: they are matched and answered with the recorded
  outcome without executing the live action. Registry mutations (`registerService`, `registerEventSource`,
  `removeService`) are refused because they require an isolated replay registry. A replayed `addEventProcessor`, a
  recorded failed control call, or a mismatch also stops that processor's replay.
- New `ReplayConfig.withControlInterception(boolean)` and `withProvenance(boolean)`, both on by default.

[Contract](../reference/control-plane-contracts.md#record-and-replay)

## Source, binary and recording compatibility

Recompile applications and plugins against this candidate before deploying it. In particular:

- The sealed `ReplayEntry` hierarchy adds `Stopped`, `ServiceChanged` and `ControlCall`. Exhaustive switches over
  its variants need new cases; source compatibility is not unconditional.
- CSV recordings can contain new entry kinds. Older readers cannot read those kinds. Multiline control-call
  outcomes use `CONTROL64` with an encoded value; ordinary `CONTROL` rows retain their existing layout. Old readers
  reject `CONTROL64` rather than silently interpreting an encoded failure as a successful empty outcome.
- `ComposingEventProcessorAgent.addNamedEventProcessor`, `removeEventProcessorByName`,
  `broadcastServiceRegistered` and `broadcastServiceDeregistered` now return `ControlRequest` instead of `void`.
  Calls that discard the result usually recompile unchanged, but previously compiled callers have a different JVM
  method descriptor and must be rebuilt. Overrides and method references need review.
- The built-in Chronicle capture service supports group-qualified selectors and registration identity. Legacy
  capture plugins inheriting the scoped interface defaults still receive bare names; those defaults do not make
  their storage safe for duplicate names across groups. Custom routers must implement scoped dispatch for scoped
  auto-start. [Plugin boundary](../reference/control-plane-contracts.md#naming-the-processor-to-capture)

Replay-suppressed live service withdrawals do not confirm physical withdrawal. The server retains those removed
services until shutdown has stopped all processor groups. Repeated service churn during replay can therefore
retain instances for the server's remaining lifetime; plan that workload explicitly.

## Further behaviour changes

### Admin stop reports its outcome

`server.processors.stop <group>/<name>` now waits at most one second and prints the outcome, or `stop pending (still
applies)`.

## New API

- `MongooseServer.addProcessor(...)` returning `AddedProcessor {handle, reply}`; `ProcessorHandle`;
  `stopProcessor(ProcessorHandle)`; `stopProcessor(group, name, waitMillis)`; `processorHandle(group, name)`.
- `MongooseServer.shutdown()` returning `StopResult`; `lastStopResult()`.
- `ControlReply`, `ReplyOutcome` (`APPLIED`, `REFUSED`, `FAILED`, `NOT_RUNNING`).
- `MongooseServerConfig`: `controlWaitMillis`, `shutdownTimeoutMillis` (also on the builder), `addFailurePolicy`.
- `AuditCaptureResources`.
- `ControlAuditSink`, `ControlAuditExport` (completeness manifest and `verify`).
- `MongooseServer.exportControlAudit`, `controlGroupStatus` and immutable `ControlGroupStatus`; admin
  `control.audit.export`, `control.audit.export.status`, `control.group.status`.
- `MongooseAuditIntrospectionService.capturesFor` and `latestCapture`, with selector and group/name overloads.
- `MongooseCountersService.queueDropCounter`, `EventToQueuePublisher.droppedCount(queueName)` and `getDropAudit`.
- `ReplayConfig.withControlInterception`, `withProvenance`; `ReplayEntry.Stopped`, `ServiceChanged`, `ControlCall`;
  `Provenance`; `ReplayStreams`.
- `ComposingEventProcessorAgent.setCaptureOpenLimit`, `setCaptureOpenTimeout`, `setControlDrainBudget`.
- `ComposingEventProcessorAgent.setCaptureResourceLimit`, `captureResourceSlotsInUse`, `captureCloseFailures`,
  `captureCleanupFailures`, `setCaptureIo`, `setAuditCaptureResources`.

## Known limits

Failure cleanup comes from [runtime #47](https://github.com/telaminai/fluxtion/pull/47) and
[compiler #118](https://github.com/telaminai/fluxtion-compiler/pull/118). The
[upstream policy](https://github.com/telaminai/fluxtion/blob/v1.1.3/docs/runtime/failed-event-cycles.md)
defines exception propagation, audit closure and queued-work/payload discard; the host chooses what happens next.

- Runtime 1.1.3 and newly generated processors close failed cycles and discard queued work. They do not roll back
  node state or external effects. Existing generated client classes remain wedge-prone until regenerated with
  compiler 1.0.80 targeting runtime 1.1.3. Mongoose continues to quarantine failed generated processors without
  retry or automatic restart; direct calls and wrappers hiding the runtime interface need their own contract.
- The new runtime callback SPI adds abstract `discardQueuedCallbacks()`. Custom implementers must implement the
  cleanup contract and rebuild; existing binaries can fail with `AbstractMethodError` when the new path calls it.
- Failed buffered batches can have already applied earlier effects; resubmitting them is not safe by default.
  A buffered-flush failure is not distinguished from failure of the triggering event. C++ recovery and runtime
  targets older than 1.1.3 are outside the upstream recovery guarantee.
- An auditor that throws during successful-cycle closure can be called again during failed-cycle cleanup.
  Custom audit listeners must tolerate that cleanup attempt; it is not a second business-handler invocation.
- Standalone legacy capture uses a shared name directory. Failed opens preserve nonempty directories rather than
  risking deletion of another live recording; partial files can remain for inspection. Server-routed unique session
  directories have operation-owned failed-open cleanup.
- New driver safety-net failures have a structured marker; validation of historical exports still partly matches
  reason text. Custom capture plugins must implement identity and safe offline resource ownership themselves.


Additional limits carried into this pre-release:

- Full queues use bounded spin then per-queue loss. There is no implemented lossless blocking feed policy;
  `SlowConsumerStrategy` does not select another policy. Do not treat this path as guaranteed delivery for orders,
  fills, credit or hedge instructions. [Overload and loss](../reference/control-plane-contracts.md#overload-and-loss)
  and [issue #77](https://github.com/telaminai/mongoose/issues/77) describe the current boundary and further design.

- Replay is `@Experimental` and does not reconstruct dynamic service history. It refuses unavailable or unidentified positive service versions and failed service callbacks. New recordings
  omit skipped duplicates. Unversioned legacy entries and arbitrary hidden inputs still lack identity/equivalence
  guarantees. Registry mutations require a separate replay registry and currently stop replay.
  [Details](../reference/control-plane-contracts.md#when-a-replay-stops)
- One earlier throughput run was about 12% below the base. A later alternating local comparison did not reproduce
  that result. These unpinned measurements do not establish a performance change; Linux testing with pinned cores
  remains open. See the [qualification record](../reports/pr76-qualification-2026-10-25/results.json).
