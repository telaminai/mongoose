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
`MongooseServer.stop()` runs an ordered shutdown, once:

1. **Feeds stop.** Agent groups that host only event sources close. Event sources that share a group with other
   services are taken off that group, and the other services keep running. Every event source is then stopped and
   torn down.
2. **Drain.** Events already queued to processors are delivered while the processor groups still run. This wait is
   bounded by `shutdownDrainTimeoutMillis` (default **2000 ms**). Events still queued when it runs out are dropped,
   and a warning gives the count.
3. **Processors stop.** Each processor group runs `@Stop` and then `@TearDown` for its processors, on the group's own
   thread.
4. **Services stop.** The remaining service agent groups close, and then the registered services stop.

Shutdown latency: the drain adds up to `shutdownDrainTimeoutMillis` to `stop()` whenever events are still queued
(no wait when the queues are already empty). Lower it, or set it to `0` to skip the drain, if fast shutdown matters
more than delivering queued events:

```yaml
shutdownDrainTimeoutMillis: 500   # default 2000; 0 or less does not wait
```

or `MongooseServerConfig.builder().shutdownDrainTimeoutMillis(500)`.

When `stop()` is called on an ordinary thread, it returns once the server has stopped. A call that races a stop
already in progress also waits for it to finish. When it is called on one of the server's own agent threads (a
processor's event handler, an admin command, an agent-hosted service), it can't wait for that thread to close.
Instead it starts the shutdown on a new `mongoose-server-stop` thread and returns at once. To wait for the shutdown
to finish, call `server.awaitStopped(timeout, unit)` from another thread.

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
- Troubleshooting and FAQ: guide/troubleshooting.md
- Benchmarks and methodology: reports/server-benchmarks-and-performance.md
- Threading model: architecture/threading-model.md
