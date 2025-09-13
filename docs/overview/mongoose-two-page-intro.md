# Mongoose Server in 2 Pages: Why Architects and Engineers Choose It

This short document introduces Mongoose Server and explains why teams adopt it for low‑latency, event‑driven systems. It
focuses on outcomes, architecture fit, and how to get started quickly.

Audience: software architects, platform leads, senior engineers evaluating event processing options.

## What is Mongoose Server?

- A high‑performance, embeddable event‑driven server framework for Java.
- Composes event sources (feeds), your business logic (handlers/processors), services, and event sinks (outputs) with
  lifecycle, dispatch, and threading handled by the runtime.
- Designed for predictable latency: single‑threaded agent loops, configurable idle strategies, and zero‑GC patterns on
  hot paths.
- Composes applications from reusable third party plugins and services.
- Runs embedded within your JVM process from a single pipeline to many isolated servers
- Run as a simple standalone app.

In short: Mongoose helps you build fast, deterministic, maintainable event pipelines with clear separation between
business logic and I/O infrastructure.

## Why Mongoose? Business and technical outcomes

- Throughput per core: Engineered for multi‑million messages per second with consistent tail latencies. Fewer
  cores/instances for the same workload means lower cloud and licensing costs.
- Deterministic logic: Handlers run in agent‑owned single‑threaded loops, eliminating most locking and concurrency
  hazards while preserving ordering guarantees.
- Developer velocity: A clean plugin model for sources, sinks, and services lets teams reuse standard connectors and
  focus on domain logic.
- Plugin ecosystem: Reusable plugins for common systems and patterns ease integration and lower costs; see
  [Plugin extension mechanism](plugin_extension_architecture.md).
- Operational control: Built‑in lifecycle, admin commands, scheduling, and dispatch strategy selection simplify
  operations and tuning.
- Incremental adoption: Start with one pipeline embedded in an existing service, expand over time without a platform
  rewrite.

Evidence: see [Benchmarks and performance](../reports/server-benchmarks-and-performance.md) for methodology and
reproducibility.

## Architecture at a glance

- Event sources (feeds) ingest from files, sockets/HTTP, brokers, or in‑memory streams and publish into the dispatcher.
- The dispatcher routes events to handler agents according to subscription rules and dispatch strategy.
- Your handlers/processors implement decisions and state transitions; they can call services and publish to sinks.
- Event sinks serialize, batch, and deliver outbound messages to external systems.

See: [Event handling and business logic](event-processing-architecture.md) for how handlers
run; [Event source feeds](event-sources-overview.md) and [Event sink outputs](event-sinks-overview.md) for plugin roles.

## What problems does it solve?

- Low‑latency streaming: trading, telemetry, IoT, control loops, in‑memory analytics.
- Near‑real‑time enrichment/aggregation: transform and combine live feeds with cached/service data.
- Edge or co‑located processing: keep data close to compute without distributed system overheads.
- Cost‑constrained workloads: achieve performance targets with fewer cores/instances.

## Core capabilities

- Plugin extension mechanism: sources, services, and sinks as reusable modules with configuration.
- Agent execution model: predictable single‑threaded loops per agent, configurable IdleStrategy.
- Dispatching: pluggable strategies, named feed subscriptions, broadcast support when needed.
- Scheduling: timers and deferred work integrated via the Scheduler service.
- Zero‑GC object pooling: reusable objects and buffers for hot paths to reduce jitter and GC pauses.
- Observability and control: admin commands, audit/logging hooks, service lifecycle, and safe shutdown.

## How it compares (qualitatively)

- Versus distributed stream processors (Flink/Beam): Mongoose is in‑process and embeddable, optimized for ultra‑low
  latency and direct developer control, not a cluster runtime.
- Versus actor toolkits (Akka, Vert.x): Mongoose provides a focused event‑pipeline model with clear roles (
  source/handler/sink) and agent threads; less generalized actor semantics.
- Versus workflow/orchestrators: Built for high‑rate event handling, not long‑running workflows.

## Performance and efficiency

- Benchmarks demonstrate multi‑million messages per second on commodity hardware.
- Zero‑GC patterns and pooling stabilize latencies; busy‑spin/yielding/sleeping idle strategies allow budget‑driven
  tuning.
- Deterministic per‑agent ordering keeps stateful logic simple and fast.

See: [Server benchmarks and performance](../reports/server-benchmarks-and-performance.md)
and [Object pooling](../architecture/object_pooling.md).

## Operability and safety

- Strong lifecycle: init/start/startComplete/stop/tearDown across plugins and processors.
- Backpressure and slow‑consumer guidance at the source/sink edges (batching, coalescing, backoff).
- Threading model that avoids accidental cross‑thread mutations; clear ownership and handoff through queues.
- Admin hooks to inspect state, adjust behavior, and trigger maintenance tasks.

## Typical adoption path

1. Prototype a single pipeline using the 5‑minute handler tutorial.
2. Add one event source (e.g., file tail, in‑memory, broker client) and a sink (e.g., log, file, broker).
3. Measure latency/throughput on target hardware; tune idle strategy and batching.
4. Integrate services (caches, schedulers, request/response wrappers) as needed.
5. Expand to multiple pipelines or embed multiple servers in the same JVM.

## Common use cases

- Market data processing and signal generation
- Device telemetry normalization and alerting
- Real‑time scoring or rules evaluation
- At the edge processing of streaming data
- Pre/post‑processing around Kafka or Aeron workloads
- High‑rate logging/metrics routing with backpressure control

## Key design choices you control

- Agent vs non‑agent for sources/sinks/services (cooperative loops vs externally driven callbacks)
- Subscription and broadcast: targeted delivery vs fan‑out
- Idle strategy: busy spin for ultra‑low latency vs yielding/sleeping for efficiency
- Mapping/serialization strategies and object pooling for zero‑GC hot paths
- Dispatch strategy selection to match your handler callback style

## Risks and mitigations

- Learning curve: Event/agent mental model. Mitigation: start with the 5‑minute tutorial and examples.
- Misplaced blocking I/O: Avoid in agent loops. Mitigation: use non‑agent mode or external threads; see Threading model.
- Ownership of mutable objects: Define clear pooling ownership. Mitigation: see Object pooling guidance.

## Quick links (next steps)

- [Event handling and business logic](event-processing-architecture.md)
- [Event source feeds](event-sources-overview.md)
- [Event sink outputs](event-sinks-overview.md)
- [Service functions](services-overview.md)
- [Plugin extension mechanism](plugin_extension_architecture.md)
- [Threading model](../architecture/threading-model.md)
- [Object pooling](../architecture/object_pooling.md)
- [Server benchmarks and performance](../reports/server-benchmarks-and-performance.md)

See the site sidebar navigation or the Overview section for these pages.
