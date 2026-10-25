# Operations Runbook (SRE/DevOps)

Operational guidance for running Mongoose Server in production. Tailor values to your environment.

## Process model
- Single JVM hosting one or more Mongoose Server instances.
- Agents (threads) per event source, handler group, and services as configured.
- Embeddable or standalone modes both supported.

## Health and readiness
- Health checks:
  - Liveness: process is up; agent groups responsive.
  - Readiness: all configured services started; critical feeds connected; backlogs under thresholds.
- Expose via HTTP or JMX depending on your platform conventions.

## Observability
- Logs:
  - Set INFO for lifecycle (boot, start, stop) and WARN/ERROR for issues.
  - Reduce logging on hot paths (handlers) to avoid perturbing latency.
- Metrics:
  - Queue depths per event source and per handler group.
  - Publish/consume rates, batch sizes, and idle strategy state.
  - GC metrics, heap usage, allocation rate (should be near‑zero in hot path).
- Tracing:
  - For cross‑service tracing, tag publish/receive boundaries if leaving the process.

## Capacity and performance
- Tuning knobs:
  - Idle strategies (BusySpin for lowest latency, Yielding/Sleeping for CPU efficiency).
  - Batching levels per pipeline stage.
  - Core pinning of critical agents on Linux with CPU isolation (cset/taskset).
- Benchmarks:
  - Reproduce benchmark methodology on production‑like hardware; establish latency SLOs.
  - Validate with HdrHistogram distributions; track p50/p90/p99/p99.9 in dashboards.

## Backpressure and overload
- Monitor inbound queue depths; trigger alerts when over thresholds.
- Apply backpressure via publishers or drop/shape non‑critical traffic where acceptable.
- Consider lowering batch sizes to reduce latency tails during incidents.

**Current publisher limit:** `EventToQueuePublisher` retries a full destination queue for about 10 ms, then drops
that queue's copy and increments its drop count. This is a delivery loss, not reliable blocking back-pressure.
One queued copy can feed several processors, so missed processor deliveries can exceed the queue-copy drop count.
The wait holds the feed's publication lock; one stalled subscriber can therefore delay other subscribers.
Monitor drops and pace sources within consumer capacity when loss is unacceptable. The explicit overflow-policy
design is tracked in [#77](https://github.com/telaminai/mongoose/issues/77); the control-plane changes do not change
this existing policy. A successful bounded-load delivery test does not establish losslessness under overload.

## Failure handling
- Startup failures: fail fast if critical services or feeds do not initialize.
- Runtime errors in handlers: log with context; consider circuit breakers for flaky integrations.
- Poison events: capture and route to quarantine sink with metadata.

## Startup
`MongooseServer.start()` waits for every agent group to become active. The wait is bounded by
`startupTimeoutMillis` (default **60000 ms**). When the bound runs out, start fails with an `IllegalStateException`
that names every group not yet active. A group whose thread ends before it becomes active (because its `onStart`
failed) fails start straight away, and the failure names that group.

```yaml
startupTimeoutMillis: 30000   # default 60000
```

or `MongooseServerConfig.builder().startupTimeoutMillis(30000)`.

A server starts once. If `stop()` runs while `start()` is still in progress, start fails with
"the server was stopped during start", and the server is never marked started. `stop()` also cleans up after a
failed start. Calling `start()` again after a stop throws `IllegalStateException`.

Processor names must be unique within a group. Configuring the same processor name twice in one group (for
example, two `eventHandlers` entries with the same `agentName` and the same handler name) fails boot with an error
that names the group and the processor. Before this, boot went ahead and the duplicate was at most logged.

## Shutdown

`MongooseServer.shutdown()` runs an ordered, **bounded** shutdown and returns a `StopResult` saying what it achieved.
`stop()` delegates to it and discards the result; inspect `lastStopResult()` or call `shutdown()` when you need it. The full contract is in
[Control-plane contracts: Shutdown](control-plane-contracts.md#shutdown). In operational terms:

1. **Feeds stop.** Agent groups that host only event sources close. Event sources that share a group with other
   services are taken off that group, and the other services keep running. Every event source is then stopped and
   torn down.
2. **Drain.** Events already queued to processors are delivered while the processor groups still run. This wait is
   bounded by `shutdownDrainTimeoutMillis` (default **2000 ms**). Events still queued when it runs out are dropped,
   and a warning gives the count.
3. **Processors stop.** Each processor group runs `@Stop` and then `@TearDown` for its processors, on the group's own
   thread.
4. **Services stop**, but only if every processor group closed. The remaining service agent groups close, and then
   the registered services stop.

The whole stop is bounded by `shutdownTimeoutMillis` (default **30000 ms**):

```yaml
shutdownTimeoutMillis: 20000      # default 30000: stop() returns, complete or not, within about this long
shutdownDrainTimeoutMillis: 500   # default 2000; 0 or less does not wait
```

or `MongooseServerConfig.builder().shutdownTimeoutMillis(20000).shutdownDrainTimeoutMillis(500)`.

**When a stop does not complete.** If something does not stop in time (a processor's `stop()` that blocks, a
service that never returns), `shutdown()` returns a `StopResult` with `complete() == false`, naming the groups
(`groupsRunning`) and services (`servicesLeftRunning`) still running. The server stays `STOPPING`, and no service is
stopped while any processor group is still running. **Call `shutdown()` again to resume**: it carries on from the
first unfinished phase. A service whose `stop()` threw is not called again, and stays listed.

**Called from an agent thread.** When `stop()` or `shutdown()` is called on one of the server's own agent threads (a
processor's event handler, an admin command, an agent-hosted service), it cannot wait for that thread to close. It
starts the shutdown on a new `mongoose-server-stop` thread and returns at once. `shutdown()` returns a result with
`handedOff() == true`; the void `stop()` does not return that result. To wait
for the shutdown, call `server.awaitStopped(timeout, unit)` from another thread. `awaitStopped` returns `true` only
once a stop has completed.

Shutdown latency: the drain adds up to `shutdownDrainTimeoutMillis` whenever events are still queued (no wait when
the queues are already empty). Lower it, or set it to `0` to skip the drain, if fast shutdown matters more than
delivering queued events.

## Deployment checklist
- JVM flags: consistent flags across environments; enable GC, heap, and JIT logging for diagnostics.
- CPU isolation (low‑latency tiers): pin agents; isolate cores; disable frequency scaling/turbo if necessary.
- Containerization: request guaranteed CPU; avoid noisy neighbors for latency‑sensitive agents.
- Configuration:
  - Verify YAML or programmatic config in CI; keep configs version‑controlled.
  - Keep a compatibility matrix between Mongoose version and plugin versions.

## Upgrades and compatibility
- Versioning: follow semantic versioning; read release notes for breaking changes.
- Rolling deploy:
  - Standalone: run blue/green or canary instances; drain traffic before shutdown.
  - Embedded: support feature flags to toggle new handlers or plugins.
- Smoke tests: include functional and latency sanity checks post‑deploy.

## Security and compliance (overview)
- Secrets: inject via env/secret manager; avoid hardcoding in config.
- Transport: use TLS for external I/O; validate certificates.
- Access: restrict admin command surface; audit usage.

## Troubleshooting quick reference
- "Handlers not receiving events": confirm feed names and subscription timing; see Troubleshooting and FAQ.
- "Latency spikes": check idle strategies, CPU isolation, GC logs, logging levels on hot paths.
- "Backlogs growing": observe queue depths; scale out, adjust batching, or shed load.

## References
- Control-plane contracts: [control-plane-contracts.md](control-plane-contracts.md)
- Troubleshooting and FAQ: [troubleshooting.md](troubleshooting.md)
- Benchmarks and methodology: [server-benchmarks-and-performance.md](../reports/server-benchmarks-and-performance.md)
- Threading model: [threading-model.md](../architecture/threading-model.md)
