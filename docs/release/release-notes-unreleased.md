# Release notes — unreleased

!!! warning "Unreleased"
    These changes are on `feature/control-plane` (PR #76, which stacks on #69, `integration/next-release`). They are
    **not** in Mongoose 1.0.33 or any earlier release, and may change before they ship. No version number is
    assigned yet.

**What this page covers.** The caller-visible behaviour changes of the control plane: what code calling Mongoose will
notice. Each item links to its contract in [Control-plane contracts](../reference/control-plane-contracts.md). Where
an item replaces a 1.0.33 release note, it says so.

## Behaviour changes

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
- `ReplayConfig.withControlInterception`, `withProvenance`; `ReplayEntry.Stopped`, `ServiceChanged`, `ControlCall`;
  `Provenance`; `ReplayStreams`.
- `ComposingEventProcessorAgent.setCaptureOpenLimit`, `setCaptureOpenTimeout`, `setControlDrainBudget`.

## Known limits at this point

Review findings on PR #76 that are still open are listed in the PR. Those that affect callers:

- Replay refuses unavailable or unidentified positive service versions and failed service callbacks. New recordings
  omit skipped duplicates. Unversioned legacy entries and arbitrary hidden inputs still lack identity/equivalence
  guarantees. Registry mutations require a separate replay registry and currently stop replay.
  [Details](../reference/control-plane-contracts.md#when-a-replay-stops)
- End-to-end throughput measured about 12% lower than the base on one machine (prediction P7 in
  `docs/design/control-plane-predictions.md`). The cause is not established, and it is to be rerun on Linux with
  pinned cores before release.
