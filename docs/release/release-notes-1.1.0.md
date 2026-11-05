# Mongoose 1.1.0

This release combines explicit failure handling (#77) and overload/loss policy (#79). It is a minor-version
upgrade from 1.0.33 with source and behavioural breaking changes. Publication does not approve a client migration:
use the [migration checklist](client-migration-1.1.0.md), [overload guide](../guide/overload-configuration.md),
[operations runbook](../reference/operations-runbook.md), and [shutdown runbook](../operations/shutdown-runbook.md).

## Failure handling and containment

See the [failure-handling design](../design/failure-handling.md).

**Breaking (record shape).** `ControlReply` gained two trailing components, `kind` (`FailureKind`) and `evidence`
(`EvidenceStatus`); `EffectResult` and `ControlEffect.Reply` gained `kind`; `MongooseServer.ControlGroupStatus` gained
`controlAuditDegraded` and `captureWriteFailures`; `StopResult` gained `heldNames` (typed `StopResult.HeldName`). The
previous canonical constructors of all of these remain as secondary constructors, so construction compiles unchanged.
Record deconstruction patterns that list every component no longer compile, and `equals` now compares the new
components. `FailureKind` gained `REFUSED_INVALID` (a malformed request or a foreign handle).

**Breaking (behaviour): a fatal agent error no longer exits the JVM under `bootServer`.** The default is now
`fatalErrorPolicy: CONTAIN`. A deployment that relied on a supervisor restarting the process after an exit will now
keep running with the failed part reported instead: monitor the health checks below, or set
`fatalErrorPolicy: EXIT_PROCESS` to keep the pre-#77 exit. Under `EXIT_PROCESS` the exit happens on the failing agent's
thread and can race whatever the caller is doing. Embedders with their own error handler are unaffected.

**Containment scope.** What reports a contained failure depends on the agent:

- **Processor group:** a failing generated/interpreted processor is quarantined. A dead control plane fences its group's processors, and
  its `group:<name>/control` health is DOWN. An `Error` that ends the group's thread also leaves it DEAD and DOWN.
- **Agent-hosted service group** (feeds, workers, pipes on their own agent): an `Error` ends its thread. Its
  `agent-group:<name>/agent` health is DOWN and the `up` gauges of its services drop to 0.
- **Any other throwable** leaves the agent running (Agrona continues its loop). It is logged at SEVERE, with a
  repeating failure rate-limited to one report per 10 s plus a count of the repeats suppressed.

Other groups keep serving.

**A failed `bootServer` stops what it started, then throws.**

- If that cleanup completes, the original failure is rethrown with the cleanup's `StopResult` as a suppressed
  exception, so under CONTAIN nothing is left running once the caller's `main` returns.
- If it does not complete (for example, a quarantined processor or a blocked stop), `bootServer` throws
  `BootFailedException`. Its cause is the original failure, and it carries `stopResult()` and `server()`. The caller
  then owns what is still running: call `server().shutdown()` again or exit.

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
  JSON, including `captureUnobservable` and `unacknowledgedCaptureFailures`. The admin stop command refuses a
  malformed argument as `REFUSED_INVALID`.
- The dead-control-plane fence covers inputs and timers delivered by the group. It cannot stop other threads calling
  a processor's exported services directly. Fenced readers keep their queued backlog until the group closes.
- Opaque dispatch strategies (`failedPosition() == -1`): the processor whose handler threw is captured at throw time
  with its dispatch position (`ProcessorContext.recordFailedProcessor`, called by `AbstractEventToInvocationStrategy`).
  - A handwritten thrower is retried alone, resumed at its position.
  - A generated thrower is quarantined alone and dispatch resumes after it.
  - A subscriber that already handled the event never receives it again.
  - A strategy that records the thrower but not its position: a generated thrower is quarantined, a handwritten one is
    retried alone when the strategy implements `processEventFor` (otherwise the event is skipped and reported, never
    redispatched and never ending the group), and the event is skipped for the remaining subscribers.
  - A strategy that records no processor ends the group without quarantining any peer when a generated graph may
    have failed; handwritten-only readers skip the input with an ERROR, without whole-dispatch retry.
- A worker or feed agent group's services drop their `up` gauges to 0 as soon as its loop ends abnormally, so a
  service's own `up` check reads DOWN without any other check being evaluated first.
  - `ProcessorDispatchException` messages include their cause.
- Regenerating with the public `fluxtion-builder` uses the hosted generator by default; see the
  [client migration checklist](client-migration-1.1.0.md#regenerating-through-the-public-builder).
- Verify each control-audit export separately: concatenating an rc1 export with a newer one makes the result a
  taxonomy export, in which rc1 records without a kind fail I20.

### Custom dispatch recovery compatibility

An uninstrumented handwritten fan-out now skips a failed input and reports ERROR instead of retrying the whole
fan-out. This prevents redelivery to peers that may already have produced external effects. A captured position
requires the strategy's resume capability; otherwise identity-only handling applies. A single-target retry retains
the thrower's identity even when the custom strategy does not record it again.

## Overload and loss policy

- **New:** an `overload` block on `eventFeeds` and `pipes`.
  - Policy `DROP_AFTER_SPIN` (today's behaviour) or `BLOCK_WITH_DEADLINE`, with overrides per subscriber group.
  - Settings `quietPeriodMillis` and `killSwitch`.
  - Programmatic: `OverloadPolicyConfig`, `MongooseServer.setFeedOverload`.
  - Guide: [Configuring overload and loss](../guide/overload-configuration.md).
- **Default unchanged:** a feed without an `overload` block behaves as before, including health that stays DEGRADED
  after a loss. Boot logs `feed 'X' has no explicit overload policy`.
- **Behaviour change:** `slowConsumerStrategy: DISCONNECT` or `EXIT_PROCESS` now **fails boot**. `BACKOFF` maps to
  `DROP_AFTER_SPIN`, with a warning.
- **New API:**
  - `PublishRefusedException`, thrown by a refused blocking publish.
  - `OverloadState`, `OverloadKillSwitch` and `OverloadLoss`.
  - `MongooseServer.setOverloadKillSwitch`, `clearOverloadState` and `overloadStates`.
  - Admin commands `overload.status` and `overload.clear <feed>`.
  - Publisher accessors `refusedCount()`, `downgradedCount()` and `getStateAudit()`.
  - `GroupReplayer.losses()` and `lossless()`.
- **New counters:** `queue.<path>.refused`, `.blocked`, `.blockedNanos`, `.blockedMaxNanos`, and
  `feed.<name>.downgraded`.
- **Recording:** a new replay entry `Dropped` (CSV `DROPPED`) marks where a queue dropped items. Readers older than
  this change reject such a store rather than misread it.
- **Compatibility:** the pre-#79 all-arguments constructors of `EventFeedConfig` and `HandlerPipeConfig` are kept.

## Integrated failure and overload diagnostics

- `PublishRefusedException.kind()` maps deadline, shutdown/terminal subscriber, and interrupt to
  `REFUSED_DEADLINE`, `REFUSED_STATE`, and `REFUSED_ADMISSION`. `evidence()` carries the audit status at refusal.
- `OverloadState` audit records carry `kind` (`OVERLOAD_LOSS`, or `NONE` on clear/recovery) and `evidence`.
  `NOT_DEGRADED` is a point-in-time status, never proof that an export is complete.
- `ControlGroupStatus` additionally contains immutable `overloadStates`, by feed and queue; JSON includes
  `DEGRADED`, `LATCHED` and retained `REMOVED` latches. The group's `overload` health check reports their loss state.
- A blocking publish whose intended target dies or is quarantined is refused without a sequence number, journal
  entry or partial fan-out (`TARGET_UNAVAILABLE`, `REFUSED_STATE`). Ordinary unsubscribe still re-evaluates the
  remaining targets. Healthy groups keep serving. Pooled refusals release the handed-over reference; never reuse
  the refused instance—copy the payload before publishing and acquire a fresh instance for an explicit retry.

## Breaking-change checklist

Recompile record patterns and equality-dependent code for `ControlReply`, `EffectResult`, `ControlEffect.Reply`,
`StopResult` (`heldNames`), and every new `ControlGroupStatus` component (`controlAuditDegraded`,
`captureWriteFailures`, `captureUnobservable`, `unacknowledgedCaptureFailures`, `overloadStates`). Secondary
constructors preserve earlier construction forms; they do not preserve record deconstruction or equality semantics.
Handle new `FailureKind` values, including `REFUSED_INVALID` and `OVERLOAD_LOSS`, and the new
`PublishRefusedException.Reason.TARGET_UNAVAILABLE` in exhaustive switches. `ReplayEntry.Dropped` changes the
sealed hierarchy and CSV format: update exhaustive switches and use a reader supporting `DROPPED` records.
`lossless()` must be checked alongside replay completion. Older readers reject new loss records.

The behavioural changes include `REFUSED` instead of null for unknown stop targets; default `CONTAIN` instead
of process exit; the dead-group data fence; generated processor quarantine; no whole-dispatch retry for an
uninstrumented handwritten fan-out; and boot refusal for obsolete `DISCONNECT`/`EXIT_PROCESS` slow-consumer policy.

## Known limits

- Deferred: unbounded BLOCK, conflation, priority, spill to disk, pooled hand-back, a `mongoose-testing` artifact,
  a reply author on every record, and a typed replay-routing answer.
- A running kill-switch hook can block its feed's hook thread. Other feeds have separate hook threads.
- Same-feed publishes made during a commit are deferred and may be downgraded.
- A custom dispatcher recording neither identity nor progress cannot establish which subscriber failed. If any
  generated subscriber may have failed, the group is contained without blaming a peer—even if the actual thrower
  was handwritten. The recoverable-handwritten guarantee requires an identified thrower.
- Direct external calls into a processor's exported services are outside the group's data-path fence.
- Node state and external side effects are not rolled back. Regenerate client graphs with compiler 1.0.80 or later
  targeting runtime 1.1.3 or later, and validate the client's own retry/idempotency policy.
- No pinned-core performance figures. Qualification is regression, stress and soak evidence, not an exhaustive
  interleaving proof or approval for any specific client's latency budget.

The integrated soak exercises lifecycle/service/capture churn with explicit drop and agent-hosted blocking feeds
(the latter exercise downgrade/latch, because agent threads never park). Parked-publisher death/quarantine seams
are tested separately in regression/stress. Retained audit history and capture files intentionally grow during
that soak; heap samples alone are not a leak proof.

Group overload diagnostics are nonblocking, weakly consistent views. They derive a drop-tolerant queue's quiet
recovery from its latest loss timestamp without taking the publisher lock. The ordered recovery audit is written
when publisher state is next observed under that lock, retaining the true quiet-period end timestamp.
