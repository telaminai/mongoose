# Export and investigate a processor group's control audit

Use the control audit to investigate lifecycle decisions such as an add, stop, service delivery or capture
cancellation. It is separate from processor event capture (`audit.start` / `audit.stop`): control auditing is
already active for each group and uses a bounded in-memory ring.

## Export from a running server

Install `MongooseServerAdmin` with an `AdminCommandRegistry`, as for the existing `server.processors.list`
command. Through that admin surface, run:

```text
server.processors.list
control.audit.export trading /var/lib/mongoose/investigations/trading-20261025-01
```

`trading` is the exact **processor group** name. The parent directory must already exist; the final directory must
not exist. Paths refer to the **server's filesystem**, not the operator's machine. The command is a privileged
administrative filesystem write under the server's OS identity. Restrict admin access using your transport's
access controls and restrict OS permissions on the parent directory. This command adds neither authentication
nor a filesystem sandbox; do not expose it to untrusted users. Audit content can contain operational identifiers.

The command immediately returns a job ID. Poll it, replacing the placeholder with that ID:

```text
control.audit.export.status <job-id>
```

The status is `PENDING`, `EXPORTED completeness=COMPLETE`, `EXPORTED completeness=INCOMPLETE`, or `FAILED` with a
reason. A successful export writes these files:

```text
trading-20261025-01/
  control-audit.yaml
  control-audit.yaml.manifest.yaml
  GroupControlProcessor.graphml
```

The graph is copied from the deployed Mongoose artifact, not from the application's business processor. The
snapshot and counters are captured together; disk writes and verification run on a daemon worker, off all agent
threads. Export does not wait for control commands to finish or pause the group. It can therefore end mid-request.
Only one export may be pending per admin instance, and only its latest job/status is retained. Save that status
before starting another export. There is no timeout/cancellation guarantee for stalled filesystem I/O: it remains
`PENDING`, consumes the one export slot, and is not awaited by server shutdown. Do not shut down before exporting
finishes. A failed export may leave partial files; retain them as failed evidence and retry in a **new directory**.

For embedded callers, `server.exportControlAudit(groupName, newDirectory)` performs the same snapshot and graph
export synchronously and returns the YAML path. Call it on an operator/background thread; agent and lifecycle
callback threads are refused. Verify the returned file as below.

## Verify the files after copying them

Copy the complete directory to the investigation machine. With the deployed Mongoose jar and its runtime
dependencies on the classpath, use the public file-only API:

```java
import com.telamin.mongoose.control.ControlAuditExport;
import java.nio.file.Path;

var file = Path.of("/investigations/trading-20261025-01/control-audit.yaml");
var verification = ControlAuditExport.verify(file);
System.out.println(verification.completeness() + ": " + verification.reason());
```

`COMPLETE` means this sink reported no dropped records or write failures and the exported documents match the
manifest. It does **not** mean the recorded behaviour was correct, all requests had finished, all physical effects
succeeded, or every group was exported. `INCOMPLETE` means recorded loss; `UNKNOWN` includes missing, inconsistent
or altered files. Neither permits an invariant PASS. The hash detects accidental alteration, not an adversarially
rewritten log/manifest pair. Keep the original pair intact when making filtered excerpts.

## Open the control graph and follow one request

Open `control-audit.yaml` with its exported `GroupControlProcessor.graphml` in the Fluxtion audit-log analyser.
For example, using its MCP interface (paths are on the analyser machine):

```json
{"tool":"analyser_open","arguments":{"log":"/investigations/trading-20261025-01/control-audit.yaml","graphml":"/investigations/trading-20261025-01/GroupControlProcessor.graphml","processor":"com.telamin.mongoose.control.generated.GroupControlProcessor"}}
```

Retain the deployed build's source revision. From the matching checkout, inspect the authored
[`control-processor.xml`](https://github.com/telaminai/mongoose/blob/feature/control-plane/src/main/fluxtion/control/control-processor.xml)
and generated Java, and add `src/main/java` as an analyser source root. That link locates the file; pin it to the
**deployed revision** before treating it as provenance. The exported graph alone does not attest source or build
identity. The XML authors the control graph; it is not the application's trading graph or a runtime capture.

Choose a request's actual `rootId` in the log (472 below is only an example), then filter:

```json
{"tool":"analyser_filter","arguments":{"text":"rootId: 472"}}
```

Follow Input → Decision (its `effects` list) → pending/final EffectOutcome → Reply. Separate `EffectRequested`
records are used on fallback cleanup paths. Check expected effects and
outcomes against the agreed lifecycle contract, not only the rule's own label. Follow `opId`, `causeOp`,
`resourceId` and generation too. Cancellation can have a different root; fallback cleanup may omit the original
root altogether. Clear the filter to find these related records:

```json
{"tool":"analyser_filter","arguments":{"text":""}}
```

Use topology/source to inspect the responsible rule and keep any external lifecycle counters and resource
ledgers with the investigation. The graph shows dependencies; an unshaded node is not proof that it did not run.
A complete log plus no observed violations is still weaker than a complete, independently checked investigation.
For a worked request with exact record indexes, graph, saved focus and a shareable analyser bundle, follow the
[late capture completion case study](../../case-studies/control-audit-investigation.md). The
[control-plane contracts](../../reference/control-plane-contracts.md) describe identity and evidence limits.

## Inspect retained capture resources without waiting for the group

```text
control.group.status trading
```

This returns the control state, outstanding request count, `captureResourcesHeld`, `captureResourceSlotsInUse`,
`captureCloseFailures` and `captureCleanupFailures`. It does not use the group mailbox or wait for the group to
make progress, so it can help when shutdown is incomplete. For embedded callers use
`server.controlGroupStatus("trading")`.

Held resources have not been handed off to the closer. Slots also include admitted opens, queued closes and
failed physical closes. Failure maps are keyed by resource ID: `captureCloseFailures` contains physical close
failures only; `captureCleanupFailures` also includes detach failures and labels the stage. Link these IDs to
`CleanupPending` and the exported control records. A failed physical close is retained and is not automatically
retried. Counts and maps are individually thread-safe but are not an atomic cross-field snapshot; re-read after
progress or quiescence before drawing a leak conclusion. Zero ownership counters are not proof of external
resource release by arbitrary plugin code. See the [shutdown runbook](../../operations/shutdown-runbook.md).
