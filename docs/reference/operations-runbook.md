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
