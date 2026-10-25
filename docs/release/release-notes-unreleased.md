# Control-plane release notes

The control-plane changes are assigned to **Mongoose 1.0.33-rc1**. See the
[versioned release notes](release-notes-1.0.33-rc1.md) for behaviour changes, compatibility and known limits.
Publication and qualification status is recorded in the [RC release procedure](rc-release-procedure.md).
Client migration requires its own [checklist](client-migration-1.0.33.md).

## After 1.0.33-rc1: issue #77 (failure taxonomy and audit-failure policy)

Target: the next **minor** version. See the [failure-handling design](../design/failure-handling.md).

**Breaking (record shape).** `ControlReply` gained two trailing components, `kind` and `evidence`
(`FailureKind`); `EffectResult` and `ControlEffect.Reply` gained `kind`; `MongooseServer.ControlGroupStatus` gained
`controlAuditDegraded` and `captureWriteFailures`. The previous constructors remain, so construction compiles
unchanged. Record deconstruction patterns that list every component no longer compile, and `equals` now compares the
new components. A typed `StopResult` replacement (held names and services per dead group) is planned for the same
release so the shape changes once.

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
