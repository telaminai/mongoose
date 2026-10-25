# Control-plane release notes

The control-plane changes are assigned to **Mongoose 1.0.33-rc1**. See the
[versioned release notes](release-notes-1.0.33-rc1.md) for behaviour changes, compatibility and known limits.
Publication and qualification status is recorded in the [RC release procedure](rc-release-procedure.md).
Client migration requires its own [checklist](client-migration-1.0.33.md).

## After 1.0.33-rc1: issue #77 (failure taxonomy and audit-failure policy)

Target: the next **minor** version. See the [failure-handling design](../design/failure-handling.md).

**Breaking (record shape).** `ControlReply` gained two trailing components, `kind` (`FailureKind`) and `evidence`
(`EvidenceStatus`); `EffectResult` and `ControlEffect.Reply` gained `kind`; `MongooseServer.ControlGroupStatus` gained
`controlAuditDegraded` and `captureWriteFailures`; `StopResult` gained `heldNames` (typed `StopResult.HeldName`). The
previous canonical constructors of all of these remain as secondary constructors, so construction compiles unchanged.
Record deconstruction patterns that list every component no longer compile, and `equals` now compares the new
components. `FailureKind` gained `REFUSED_INVALID` (a malformed request or a foreign handle).

**Containment scope.** A failing processor is quarantined and a dead control plane fences its group's processors;
other groups keep serving. Under `bootServer`, a fatal error that escapes a group no longer exits the JVM by default
(`fatalErrorPolicy: CONTAIN`): that group dies and its `group:<name>/control` health is DOWN. Set
`fatalErrorPolicy: EXIT_PROCESS` to keep the pre-#77 exit. Embedders with their own error handler are unaffected. Under
`CONTAIN` a failed `bootServer` throws to its caller instead of exiting; the server's other agent threads are not
stopped, so an application that lets the exception escape `main` keeps running: catch it and exit (or stop the server),
or opt into `EXIT_PROCESS`.

**Behaviour.**

- `stopProcessor(group, name, waitMillis)` answers `REFUSED` (`REFUSED_STATE`, `no such group` / `no such processor`)
  instead of `null` when nothing was admitted. The admin `server.processors.stop` validates its argument and prints
  the kind.
- Mailbox-full exceptions now read `request refused: mailbox full (capacity N)` (previously `queue is full` /
  `queue stayed full`).
- A control-audit write failure marks the group DEGRADED (`control-audit` health check, `controlAuditDegraded`); replies
  carry `evidence = UNVERIFIED` until the evidence is exported or acknowledged (`acknowledgeControlAuditGap`, admin
  `control.audit.acknowledge`). Requests, including stop and close, are still applied.
- A capture write failure never stops the processor; it writes a `CaptureWriteFailed` control-audit record and raises
  the `audit-capture` health check. `AuditCaptureResources.observeWriteFailures` is a new default SPI method.
- Audit exports carry `kind` on replies, failed outcomes and fallback records, and `leftover` / `retry` on offline
  capture cleanup. `failureKind` keeps its rc1 meaning.
- A group whose control plane dies while its thread lives (a rule threw) stops delivering events and timers to its
  processors (`DataPathFenced` record) and reports `group:<name>/control` DOWN; close the group or stop the server.
- Capture failures can be acknowledged (`acknowledgeCaptureFailures`, admin `control.capture.acknowledge`): the
  audit-capture alert clears, the failures stay listed. `control.group.status <group> --json` prints the status as
  JSON. The admin stop command refuses a malformed argument as `REFUSED_INVALID`.
- Opaque dispatch strategies (`failedPosition() == -1`): the processor whose handler threw is captured at throw time
  (`ProcessorContext.recordFailedProcessor`, called by `AbstractEventToInvocationStrategy`). A handwritten failure is
  retried under the legacy policy and no longer quarantines generated peers or ends the group; a generated thrower is
  quarantined alone. A strategy that records no processor ends the group without quarantining any peer.
  `ProcessorDispatchException` messages include their cause.
- Regenerating with the public `fluxtion-builder` uses the hosted generator by default; see the
  [client migration checklist](client-migration-1.0.33.md#regenerating-through-the-public-builder).
- Verify each control-audit export separately: concatenating an rc1 export with a newer one makes the result a
  taxonomy export, in which rc1 records without a kind fail I20.
