# Client migration to Mongoose 1.1.0

Publishing a release does not clear a client to migrate from the old Fluxtion server. The client delivery lead
must assign a named person to each owner role below and retain the linked evidence before approving go-live.
These are deployment gates, not reasons to claim that an unpublished candidate has already qualified.

| Check | Owner | Required proof |
|---|---|---|
| Generated processor exceptions and retries | Application lead | Run the actual compiled client graphs through handler, callback, timer, service and lifecycle failures. Show isolation and detection, no duplicate external actions, and an operator recovery procedure. Regenerate client processors with compiler 1.0.80 targeting runtime 1.1.3; upgrading the runtime alone does not repair old generated code (see [regenerating through the public builder](#regenerating-through-the-public-builder) first). Failed-cycle cleanup does not roll back partial state or external effects. |
| Overload for every feed and subscriber | Application lead and operations | Record rates, burst assumptions, capacity and acceptable loss. Demonstrate behaviour at the limit. Select DROP_AFTER_SPIN or BLOCK_WITH_DEADLINE per feed and subscriber, including deadlines, refusal ownership and latch clearing. Follow the [overload guide](../guide/overload-configuration.md); unbounded BLOCK remains unsupported. |
| Plugins, reconnect and capture retention | Integration lead | Build client plugins against Mongoose 1.1.0 and its published matching plugin version. Exercise disconnect/reconnect. Verify importers, dashboards and pruning understand qualified selectors, `capture-*` directories and identity sidecars. Custom capture plugins must prove ownership and safe offline detach. |
| Deployment soak | Test lead | A run longer than ten minutes using the client's topology, configuration and failure injection. Preserve resource ledgers, complete audit exports and shutdown results. |
| Performance budget | Performance lead | Pinned-core Linux throughput, latency distribution and GC/allocation measurements with the intended audit profile, hardware and offered load. Compare with the client's budget. Local unpinned results are insufficient. |
| Old server configuration and APIs | Integration lead | Map each old Fluxtion server setting, dependency and API to 1.1.0. Compile applications, review changed signatures and run acceptance tests. Consult the migration disposition below. |
| Control reply records and kinds (1.1.0) | Integration lead | Recompile code that deconstructs `ControlReply` (now `kind` and `evidence` components), `EffectResult` or `ControlGroupStatus` with record patterns, or compares replies with `equals`. Classify replies by `outcome` and `kind`, never `reason` text. Handle `REFUSED`/`REFUSED_STATE` (not `null`) from `stopProcessor(group, name, wait)` for unknown names, and `evidence = UNVERIFIED` while a group's control audit is degraded. See the [1.1.0 notes](release-notes-1.1.0.md). |
| Fatal-error policy (1.1.0) | Operations | Decide `fatalErrorPolicy`. The default `CONTAIN` keeps the process up when an agent dies and reports it through health (`group:<name>/control`, `agent-group:<name>/agent`). Alert on those checks instead of relying on a supervisor restart after exit. Choose `EXIT_PROCESS` only if the deployment depends on exit-and-restart. A failed `bootServer` stops what it started and throws. |
| Shutdown and recovery runbook | Operations | Set `shutdownTimeoutMillis` within the process manager's grace period. Rehearse incomplete shutdown, quarantine, external action reconciliation and operator escalation without automatically re-entering a failed graph. |

## Regenerating through the public builder

Read this before regenerating with the public `fluxtion-builder` 1.0.80.

- **The hosted generator is the default.** The builder picks its model generator with `ServiceLoader`, in this order:
  the provider named by `-Dfluxtion.modelGeneratorId`; otherwise a provider with id `local`; otherwise `remote-http`.
  The public builder jar ships only `remote-http` (`META-INF/services/...SimpleEventProcessorModelGenerator` lists
  `RemoteHttpModelGenerator`). Without a local generator on the classpath (the private `fluxtion-generator-core`),
  every regeneration posts the graph to the hosted generator. Its default host is
  `https://fluxtion-source-gen.p.rapidapi.com` (override with `fluxtion.remote.host`). Checked against the
  `fluxtion-builder-1.0.80.jar` classes.
- **What is sent.** The serialized graph model carries node field values as captured by builder-api 1.1.3
  `FieldValueCapture`. **Private** field values are captured. Static, transient, synthetic and author-excluded fields
  are not. Values can still reach the model by other routes, such as constructor arguments rendered as strings or
  custom serialization, so exclusion is not a guarantee of secrecy. Everything sent is readable by the hosted service.
  HTTPS protects it in transit to the default host; nothing else encrypts it (`BuilderDisclosureTest` checks the
  capture rules).
- **Source generation is a separate step.** After the model, the builder chooses a source generator and backend
  (`fluxtion.sourceGeneratorId`, or `ServiceLoader` discovery). The model-provider order above does not by itself
  describe the whole route.
- **Keep secrets out of node fields.** Supply credentials and other secrets at runtime instead:
  `registerService(new Service<>(...))` received by an `@ServiceRegistered` method, or `injectInstance`.
- **The API key.** `fluxtion.apiKey` travels only in the `X-RapidAPI-Key` request header.
- See [cloud generation and your API key](https://github.com/telaminai/fluxtion#cloud-generation-and-your-api-key).


References: [migration disposition](../reports/pr76-review-followup-2026-10-25/migration-disposition.md),
[control contracts](../reference/control-plane-contracts.md), [capture identity](../design/capture-resource-identity.md),
[shutdown runbook](../operations/shutdown-runbook.md), [release notes](release-notes-1.1.0.md),
[failure design #77](https://github.com/telaminai/mongoose/issues/77).

The upstream fixes are [runtime #47](https://github.com/telaminai/fluxtion/pull/47) and
[compiler #118](https://github.com/telaminai/fluxtion-compiler/pull/118). Follow the
[failed-event-cycle contract](https://github.com/telaminai/fluxtion/blob/v1.1.3/docs/runtime/failed-event-cycles.md):
the exception propagates, the failed cycle closes, and queued work and callback payloads are discarded.
Node state and external effects are **not rolled back**. Mongoose isolates the processor; a direct caller
chooses its own next action. Regenerated code targeting older runtimes omits discard; a throwing queued item
can therefore be dispatched again by later cycles. Such a pairing does not meet this migration gate.

## Upgrade acceptance from 1.0.33

The integration lead must account for every [breaking change](release-notes-1.1.0.md#breaking-change-checklist):
record components and equality, typed reply kind/evidence, `StopResult.heldNames`, all JSON group-status fields,
unknown-stop REFUSED results, default CONTAIN, the dead-group fence, quarantine, custom-dispatch retry changes,
new enum values, and `ReplayEntry.Dropped`/CSV compatibility. Preserve both `complete()` and `lossless()` checks.

Operations owns alerts for group control DOWN, service-agent DOWN, audit UNVERIFIED, and overload
DEGRADED/LATCHED/REMOVED. Rehearse `overload.status`, `overload.clear`, capture acknowledgement and incomplete
shutdown using the [operator runbook](../reference/operations-runbook.md). Acknowledgement never repairs incomplete
recordings. Review the [known limits](release-notes-1.1.0.md#known-limits), including the unknown-origin custom
strategy containment case. A release qualification cannot substitute for a client's own deployment soak.
