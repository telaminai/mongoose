# How Mongoose Server compares to similar Java projects

This page offers a neutral, concise comparison between Mongoose Server and popular adjacent technologies in the Java ecosystem. Use it to decide fit and positioning.

## TL;DR
- Mongoose is an embeddable, in‑JVM event processing server focused on ultra‑low latency and developer‑controlled pipelines. It is not a distributed stream processor or a workflow/orchestration engine.
- Strengths: simplicity for single‑process pipelines, zero‑GC hot paths, agent threads with configurable idle strategies, plugin model for feeds/sinks/services.
- Consider other tools if you need cluster-level resource management, SQL over streams, or long‑running workflow semantics.

## Project-by-project view

### Akka (Typed) and Vert.x (Reactive Toolkit)
- What they are: Actor model (Akka) and reactive toolkit (Vert.x) for building concurrent, distributed apps.
- Contrast: Mongoose centers on event pipelines with explicit feeds/handlers/sinks and agent threads; less abstraction overhead than general actor systems for dataflow pipelines.
- When to pick them: You need distributed actors, remoting, supervision trees, backpressure via reactive streams across services.
- When to pick Mongoose: You want single‑process, ultra‑low‑latency event handling with explicit control over threads, batching, and zero‑GC design.

### Apache Flink / Apache Beam
- What they are: Distributed stream/data processing with stateful operators, windows, watermarks, connectors, SQL.
- Contrast: Mongoose is an embeddable, single‑process runtime; no cluster manager, no distributed state or SQL layer.
- When to pick them: You require horizontal scaling, exactly‑once semantics at cluster level, SQL/DSL, massive connector catalog.
- When to pick Mongoose: You need microsecond‑class latency inside a JVM service and prefer composing handlers/services directly.

### Aeron / LMAX Disruptor / Chronicle Queue
- What they are: High‑performance messaging/buffer libraries and IPC queues.
- Contrast: Mongoose can integrate with these as feeds/sinks but provides a higher‑level application server model (services, handlers, lifecycle, admin, scheduling).
- When to pick them: You only need the transport/queue primitive and will build the rest yourself.
- When to pick Mongoose: You want a cohesive runtime that wires threading, lifecycle, injection, and admin around your business handlers.

### Netflix Conductor / Cadence/Temporal (Workflow)
- What they are: Workflow orchestration for long‑running tasks and retries across services.
- Contrast: Mongoose targets high‑rate event processing, not human/long‑lived workflows and compensations.
- When to pick them: You need durable workflows with retries, timers, and activity workers.
- When to pick Mongoose: You need low‑latency event pipelines and per‑message processing throughput.

## Feature comparison snapshot
- Execution model: single‑process agents (threads) with configurable idle strategies; batching optional.
- Extensibility: plugins for feeds, sinks, services; dynamic handler registration.
- Performance: sub‑microsecond mean latency at 1M mps; ~10M mps throughput in benchmarks.
- Operations: admin commands, metrics guidance, runbook; no built‑in cluster manager.
- Distribution: embed multiple servers per JVM; scale out by running more instances behind LB.

## Choosing guide
- Choose Mongoose if:
  - You need predictable low latency and zero‑GC hot paths inside a JVM process.
  - You prefer explicit control over threading, batching, and lifecycle without a distributed framework overhead.
  - You want to reuse integrations via plugins (Kafka, Aeron, Chronicle, etc.).
- Consider a distributed stream processor (Flink/Beam) if you require SQL/DSL, checkpointing, and cluster‑level semantics.
- Consider actor toolkits (Akka/Vert.x) if you need distributed actor patterns and reactive backpressure across services.
- Consider low‑level IPC/messaging libs (Aeron/Disruptor/Chronicle) if you want to compose the stack yourself from primitives.
