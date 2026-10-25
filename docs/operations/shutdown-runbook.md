# Shutdown runbook

Use this for the unreleased control-plane candidate. A returned `stop()` call is not evidence that everything
stopped: use `shutdown()` and preserve its `StopResult`. The server refuses new lifecycle admission once stopping
begins. Do not repeatedly restart or delete capture files to hide an incomplete result.

## Before stopping a trading deployment

Complete application-specific risk reduction while its required connectors and acknowledgements are still
available. Mongoose stops inbound feeds **before** processor `stop()` callbacks. Cancel-on-shutdown or flattening
logic placed only in `stop()` must not assume new market data, fills or acknowledgements will arrive there.
Container shutdown does not establish that positions are flat or that an external venue accepted a cancellation.

Give the application a separate, monitored quiesce/flatten stage, then invoke Mongoose shutdown. If that stage
cannot establish its own completion, follow the application's incident procedure rather than declaring success
from a container lifecycle result.

## Phases and budgets

There is one end-to-end deadline. Phase bounds do not add extra time to it.

| Phase | Action and bound | What to look for |
| --- | --- | --- |
| Admission/startup fence | Fence new changes; ask an in-flight startup callback to return; wait within remaining deadline | `blockedStartup` if coordination or startup is still blocked |
| Feeds | Close source-only agent groups; remove sources from shared groups; stop sources. At most 5 s, capped by remaining deadline | `stopping feeds`; `removing the event sources from shared service agent ...` |
| Drain | Let existing processor queues drain for `shutdownDrainTimeoutMillis`, capped by remaining deadline | `draining queued events, at most ...ms`; expiry logs `shutdown drain timed out after ...ms, dropping ... queued events` |
| Processor groups | Close each on a tracked helper. Uninstall capture before processor `stop()` / `tearDown()`; close physical captures asynchronously | `stopping event processor agents`; `stopping composing event processor agent ...` |
| Service agent groups | Only after processor groups close, close remaining workers | `stopping agent hosted services` |
| Services | Only after groups close, stop registered and retained removed service instances on helpers | `stopping registered services` |
| Final capture cleanup | Run capture `stopAll()` on a tracked helper; unresolved group capture ownership prevents reaching this phase | `<audit capture stopping>` or a failure note if the final helper cannot finish |

Agrona's group-close retry interval is 5 s. Its closer can interrupt the group thread while retrying. A processor
doing I/O in `stop()` must handle interruption correctly. The caller's deadline bounds waiting; it cannot force
an arbitrary callback to terminate. If feeds fail to stop within their cap, shutdown proceeds and reports them;
the drain is not proof that a misbehaving source can no longer publish.

When capture uninstall succeeds, audit emitted during `stop()` and `tearDown()` reaches the **exact restored
configured listener**, not the ended Chronicle capture. An uninstall failure does not establish that restoration.
Look at the control audit and cleanup diagnostics instead of expecting lifecycle records in that capture.

## Interpret the result

| Result or diagnostic | Meaning | Next action |
| --- | --- | --- |
| `complete=true` | All tracked shutdown work confirmed complete; state is `STOPPED` | Preserve the result and proceed with deployment |
| `handedOff=true` | Called from a server-owned thread; another thread performs the stop | Call `awaitStopped` from an ordinary thread, then read `lastStopResult`; timeout is not success |
| `blockedStartup` | Admission, another shutdown attempt, startup cancellation or synchronous startup did not return | Inspect the named callback/thread; resolve its dependency before resuming |
| A group name in `groupsRunning` | Group close, post-death cleanup or physical capture ownership is unresolved | Inspect helper warnings, thread state and capture diagnostics |
| `processor:` / `service:` | Internal helper-key prefixes distinguish group types | Current public result entries normally contain **raw group names**, not these prefixes; consult configuration and logs |
| `<event sources stopping>` | Source stop helper still running or unconfirmed | Inspect source callback and connector; do not assume inbound traffic ceased |
| `<audit capture stopping>` | Final capture helper still running | Wait within the operational budget, inspect storage/helper state, then resume |
| `<audit capture stop failed: ...>` | Final capture stop threw | Preserve error and files; automatic replay of that callback is not attempted |
| `<stop failed: ...>` | Unexpected orchestration exception | Preserve stack trace and control export; escalate before retrying blindly |
| `servicesLeftRunning` | Dependencies retained for groups, or service stop pending/failed | First resolve group blockers; a thrown service stop remains failed on later attempts |

Group names alone do not mean the agent thread is alive: a terminated group can still own a failed capture close.
Do not parse `StopResult.toString()` as a versioned machine protocol; consume the record fields.

## Resume or escalate

Call `shutdown()` again after the blocker can make progress. It rejoins existing helpers and retries failed group
helpers / unfinished cleanup without repeating processor callbacks already attempted. A pending asynchronous
close may finish. A failed detach may be retried after the processor has been released.

It does **not** blindly retry a physical capture close that threw or an ordinary service `stop()` that threw.
Partial side effects are unknown. Those failures keep the stop incomplete; repeating the same call with no
state change will not repair them. Startup cancellation has a separate final cleanup after startup returns,
because startup may have acquired new resources after cancellation.

Stop retrying when diagnostics show a permanent failed close/stop, or the deployment's finite shutdown budget is
exhausted. Export evidence, retain the error and affected capture directories, and escalate to the process manager
under the deployment's incident policy. Terminating the JVM is an operational escalation, not a successful
`StopResult`, nor evidence that external trading actions completed.

After a kill, Chronicle directories may contain an incomplete tail and no confirmed clean closure. Preserve an
unaltered copy before recovery/export. Do not edit the sidecar to imply completion. The sidecar identifies a
session; it is not a clean-close marker. The analyser can inspect records that the importer successfully recovers,
but readable records do not prove the last write survived or the whole capture is complete. Keep importer errors
and missing-tail uncertainty with the report.

## Capture diagnostics

The group exposes thread-safe observations:

```java
group.captureResourceSlotsInUse(); // unresolved opens, installed resources and pending/failed cleanup
group.captureCloseFailures();     // resource id -> physical close exception
group.captureCleanupFailures();   // resource id -> detach OR physical close exception
```

On the admin surface, `control.group.status <group>` returns these resource diagnostics without waiting for the
agent thread. The fields are separate observations, not an atomic lifecycle snapshot; repeat after quiescence if
they change while cleanup runs.

Correlate resource ids with `OpenCapture`, `InstallCapture`, `UninstallCapture`, `CloseCapture` and their outcomes.
A post-release retry now has explicit requested/outcome records. A failed resource remains in the control table
as `CLOSE_FAILED`; a successful reply to a logical stop alone does not prove physical closure.

The fallback record after incomplete cleanup contains these fields (values depend on the run):

```yaml
controlFallback:
    record: CleanupPending
    fallbackSeq: 1
    group: g1
    processorsLeft: 0
    captureResourcesLeft: 1
    failedPhysicalCloses: 1
    failedCaptureDetaches: 0
```

Search logs for these exact message fragments:

```text
unresolved physical capture resources; cleanup failures=
failed its post-death cleanup
stop incomplete: processor group(s)
server stop incomplete:
audit capture could not be stopped at server stop
shutdown drain timed out after
```

`audit.list` and `audit.status` identify business captures by id, selector and path. They are not the ledger of
failed physical closes. Export the **group control audit** for cleanup decisions; preserve the paired completeness
manifest. `COMPLETE` means the bounded export lost no records, not that the lifecycle was correct.

## Timeout configuration

| Setting | Default | Purpose |
| --- | --- | --- |
| `shutdownTimeoutMillis` | 30,000 ms | One whole shutdown attempt |
| `shutdownDrainTimeoutMillis` | 2,000 ms | Existing queued-event drain, capped by remaining shutdown time |
| Feed stop phase cap | 5,000 ms | Internal phase cap, not an extra budget |
| Agrona close retry interval | 5,000 ms | Retry/interrupt interval, not a guarantee callbacks terminate |
| `controlWaitMillis` | 10,000 ms | Ordinary control caller wait; timing out does not cancel admitted work |
| Capture open timeout | 30,000 ms | Group setting via `setCaptureOpenTimeout`; late opens still need cleanup |

For a trading deployment, measure quiesce/acknowledgement time separately from queue drain and physical capture
close time. Size the drain for observed worst-case backlog at conservative consumption throughput; a longer
window cannot fix a permanently stalled consumer. Keep flattening outside processor shutdown. As a planning
example only, a 30 s application quiesce budget plus a 30 s Mongoose budget requires a process-manager grace
period **greater than 60 s**, with explicit scheduling/storage margin. Validate actual values in the deployment;
the defaults are not a flattening guarantee. `controlWaitMillis` and capture-open timeout are not added to the
shutdown deadline.

## Regression evidence and worked examples

`CaptureMigrationAcceptanceTest.yamlCaptureSupportCycleAndShutdown` boots YAML with generated processors in two
groups, runs input, changes captures through admin commands and checks the final listener identities. It asserts
the ordered phase log fragments above and `StopResult.completed()`. Its full log is written to
`target/migration-evidence/complete-shutdown.log`.

Read the successful phases in order: feeds stop, queued events drain, processor groups close (including capture
cleanup), service groups close, registered services stop, then the recorded `StopResult` confirms completion.
The qualification report links the preserved full log and the forced-close-failure example. For that failure,
the operator preserves the failed resource, exports the control audit and escalates; repeated shutdown does not
retry an uncertain physical close.
