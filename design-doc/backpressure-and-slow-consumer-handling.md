# Backpressure & slow-consumer handling

**Status**: Draft / problem-framing. No code yet — this doc exists to settle the design before touching the dispatch hot path.
**Owners**: Greg
**Drives**: deterministic startup replay of large sources, an off-heap (Chronicle-backed) replay log with `durableReplay` / `maxMessageHistorySize` config, a *lossless* mode for regulated/audit deployments, making `SlowConsumerStrategy` a real knob instead of a dead field, and a clear split between the error-retry axis (`RetryPolicy`) and the flow-control axis (`SlowConsumerStrategy`).

---

## Motivation

Mongoose's pitch leads with **deterministic replay and audit** — "replay-equivalence proven in simulation is replay-equivalence in production." Backpressure is where that promise is currently most exposed:

- Today, when a consumer can't keep up, the publisher **drops the event after ~10 ms** and logs a `WARNING`. A dropped event means production diverges from replay. For the telemetry/metrics use cases that's fine; for the regulated / agentic-governance / chain-of-custody buyers it quietly breaks the headline guarantee.
- The most acute case is **startup replay of a large source**: a file/Chronicle/in-memory feed re-driving millions of cached events into a 1024-slot queue, far faster than the processor agent drains it. The current path drops under that load, and the dedicated replay path (`publishReplay`) drops **silently** — it doesn't even check the `offer()` result.
- We already ship two config surfaces that *look* like backpressure control (`SlowConsumerStrategy`, `RetryPolicy`) but neither does what an operator would assume. We should decide what they mean and wire them honestly.

This doc inventories what exists, names the core tension, and proposes a design to decide on.

---

## Current state (ground truth)

### 1. Publish-side: bounded queue + spin-then-drop

`com.telamin.mongoose.dispatch.EventToQueuePublisher#writeToQueue` is the only place backpressure is actually applied:

```java
final long maxSpinNs = TimeUnit.MILLISECONDS.toNanos(10); // bound spin to avoid publisher timeouts under contention
while (!offered) {
    offered = targetQueue.offer(itemToPublish);
    if (!offered) {
        if (startNs < 0) {
            startNs = System.nanoTime();
        } else if (System.nanoTime() - startNs > maxSpinNs) {
            log.warning("dropping publish to slow/contended queue: " + namedQueue.name() + " ...");
            return;                       // <-- DROP
        }
        Thread.onSpinWait();
    }
}
```

Properties:
- **Bound = 10 ms, hardcoded** as a local. Not configurable.
- **Policy = drop + `WARNING`.** Always. Sequence number and queue size are logged; the event is gone.
- **`SlowConsumerStrategy` is never consulted here.** The de-facto behaviour is a fixed "spin-briefly-then-drop", regardless of configured strategy.

### 2. Replay-side: silent drop

`EventToQueuePublisher#publishReplay` is worse than the normal path:

```java
OneToOneConcurrentArrayQueue<Object> targetQueue = namedQueue.targetQueue();
targetQueue.offer(record);   // return value ignored — no spin, no retry, no log
```

A full queue on the replay path **loses the record with no spin and no log line**. This is exactly the startup-large-source path. It is the single most determinism-hostile line in the dispatch layer.

### 3. Queue sizing — fixed at 1024

`com.telamin.mongoose.dispatch.EventFlowManager`:
- Subscriber queues: `new OneToOneConcurrentArrayQueue<>(1024)`
- Sink queues: `new ManyToOneConcurrentArrayQueue<>(1024)`
- Internal lifecycle queues (`ComposingEventProcessorAgent`): 128

All hardcoded. No per-feed / per-subscriber override.

### 4. `SlowConsumerStrategy` — plumbed but dead

`com.telamin.mongoose.service.EventSource`:

```java
enum SlowConsumerStrategy {DISCONNECT, EXIT_PROCESS, BACKOFF}
default void setSlowConsumerStrategy(SlowConsumerStrategy s) {}   // default no-op
```

- `EventFeedConfig` (default `BACKOFF`) and `HandlerPipeConfig` both expose `slowConsumerStrategy(...)` and call `setSlowConsumerStrategy(...)`.
- `AbstractEventSourceService` stores it in a field.
- **Nothing reads it.** No `getSlowConsumerStrategy()`, no `switch`, no branch on `DISCONNECT`/`EXIT_PROCESS`/`BACKOFF` anywhere. It is a config knob wired to `/dev/null`.

### 5. `RetryPolicy` — a *different* axis, consumer-side only

`com.telamin.mongoose.dispatch.RetryPolicy` (maxAttempts, initial/max backoff, multiplier, `retryOn` set; default `3 / 5ms / 100ms / x2 / RuntimeException`) is used in exactly one place:

`com.telamin.mongoose.dutycycle.EventQueueToEventProcessorAgent#doWork`:

```java
while (!done) {
    try { /* dispatch event to processor */ done = true; }
    catch (Throwable t) {
        attempt++;
        if (!retryPolicy.shouldRetry(t, attempt)) { /* give up, report */ break; }
        retryPolicy.backoff(attempt);   // TimeUnit.MILLISECONDS.sleep(...) ON THE AGENT THREAD
    }
}
```

So `RetryPolicy` today means **"the processor threw while handling an event — retry the handler N times"**. That is error handling, *not* flow control. Two caveats:
- It blocks the **agent thread** during `backoff()` (`Thread.sleep`). Every other feed/processor co-hosted on that agent group stalls for the backoff duration — head-of-line blocking.
- It has nothing to do with a full queue.

---

## Architecture assessment — `EventToQueuePublisher` in context

Before deciding *how* to wire the policies, it's worth stating what the dispatch design **is**, because the policy choice should respect the primitive rather than fight it.

### The shape

One source → one `EventToQueuePublisher` → **fan-out copy into N per-subscriber bounded SPSC queues** (Agrona `OneToOneConcurrentArrayQueue(1024)`), each drained by a duty-cycle agent. Layered onto that single primitive: zero-allocation pooling (`PoolAware`/`PoolTracker` ref-counting), an optional in-memory replay cache (`cacheEventLog` + `eventLog` + `publishReplay`), wrap strategies (`NOWRAP`/`NAMED_EVENT`, subscription/broadcast), and the flow-control we're now adding.

Load-bearing invariant: **one source publishes from one thread**, so each target queue keeps its single-writer (SPSC) property. `InMemoryEventSource` honours this by staging into a `ConcurrentLinkedQueue` and draining on the agent's `doWork` thread. This is the Disruptor single-writer principle applied per queue — and any new `BLOCK`/credit path must preserve it.

### What's strong

- **Right primitives.** Bounded Agrona array queues = mechanical sympathy: pre-sized, no per-event node allocation, cache-friendly, lock-free SPSC. Correct floor for an in-process low-latency bus.
- **Per-subscriber queue isolation is a deliberate divergence from the single-ring model.** A slow subscriber backs up its *own* queue without gating fast ones. This is precisely what makes a **per-subscriber** `SlowConsumerStrategy` meaningful (vs a global one).
- **Replay lives in the dispatch primitive.** Most buses treat replay as external (re-read the log / re-consume the topic). Baking an event log + `publishReplay` into the publisher is what makes replay-equivalence *structural* rather than bolted on. This is the differentiator; protect it.
- **True zero-allocation steady state** via pooled, ref-counted events — something the Reactor/Akka world can't claim.

### Design smells (layering, not capability)

1. **Policy is hardcoded into the lowest-level write.** `writeToQueue`'s spin-10ms-then-drop fuses a flow-control *decision* into the *mechanism*. See the layering principles below — inject the strategy, don't `switch` inline.
2. **The producer spins on the consumer's queue inside the publish call.** Fine across threads; a **priority-inversion / head-of-line hazard the instant producer and consumer share an agent thread** (Q1). `BLOCK` sharpens this into a potential deadlock.
3. **Per-attempt pool ref churn in the spin loop** — `acquireReference()`/`releaseReference()` on every failed `offer()` is a lot of work per contended iteration.
4. **`EventToQueuePublisher` is becoming a god-object of the hot path** — mapping + pooling + caching + wrapping + fan-out + flow-control in one class.
5. **`eventLog` is an unbounded `ArrayList`.** Caching a large source at startup is unbounded heap growth — the same startup-large-source scenario viewed from the *memory* axis instead of the *drop* axis. This motivates the off-heap replay-log design (section E).
6. **`NAMED_EVENT` fan-out allocates per subscriber per event** (`new NamedFeedEventImpl<>` in the dispatch loop — existing `//TODO`), quietly defeating the pooling done elsewhere.

### Comparison once retry + slow-consumer are integrated

| System | Backpressure model | Where Mongoose sits |
|--------|--------------------|---------------------|
| **LMAX Disruptor** | Single ring; producer claim **gated by the slowest consumer's sequence** (global, lossless by construction); pluggable `WaitStrategy` | Mongoose deliberately rejects single-ring/slowest-gates-all in favour of **per-subscriber queues + per-subscriber policy**. `BLOCK` lets you opt *into* Disruptor-style gating per consumer; drop/`DISCONNECT` lets you *isolate* a slow consumer instead of letting it gate the world — which Disruptor can't do without a multi-ring rework. |
| **Aeron** | Credit-based flow control + congestion control + NAK loss recovery, on the wire | The credit/pull replay option (C.2) is Aeron-flavoured. In-process we skip congestion control, but the **credit signal** is the principled answer to startup replay. |
| **Kafka** | Pull-based; durable log; consumer offsets; never drops; replay by offset | Our `eventLog` cache is **in-memory Kafka-lite** — replay-by-position without durability or a memory bound. Section E (Chronicle-backed log) closes exactly that gap. |
| **Reactive Streams (Reactor/Akka)** | Demand signalling `request(n)` propagated upstream; async, composable | Mongoose has **no upstream demand signal today**; drop/spin is the substitute. RS is more general but pays per-operator overhead with no allocation/latency guarantees. Mongoose trades composability for a flat, pooled hot path. |
| **Chronicle Queue** | Persisted, mmap, replay-by-index, reader-lag backpressure | The durable counterpart to our in-memory cache — and the proposed off-heap replay backend (section E). Already integrated as a *source* in mongoose-plugins. |

### Verdict

Once the two strategies are wired, the design occupies a coherent, slightly distinctive niche: **Disruptor-grade mechanical sympathy, but with per-consumer queue isolation and pluggable per-source policy, plus a built-in replay cache, on a duty-cycle agent model** — in one line, *"Disruptor's hot path + per-consumer policy isolation + Kafka-lite replay."* The differentiator is not the queueing (Agrona/JCTools give everyone that) but that **replay lives in the dispatch primitive**. The honest weakness is **layering, not capability** — policy hardcoded in the mechanism, caching fused into the publisher, an unbounded replay log. The wiring work is the moment to fix the first; section E fixes the third.

### Layering principles (adopt before writing code)

- **Inject flow-control as a strategy, don't branch inline.** `writeToQueue` stays a mechanism; a `BackpressureStrategy` (or `SlowConsumerHandler`) owns the decision. Prefer a **monomorphic per-queue handle** so the JIT inlines the common case — the same trick used for the counters service (no-op call site inlined away). This also gives one place to assert "this deployment is configured lossless" (governance hook D).
- **Extract caching from the live publisher.** A `CachingEventToQueuePublisher` decorator (or a separate replay-log component, section E) keeps `EventToQueuePublisher` single-responsibility and lets the replay store evolve (heap → off-heap → durable) without touching the hot dispatch path.
- **Keep the SPSC single-writer invariant explicit.** Whatever `BLOCK`/credit mechanism we add must not introduce a second writer to a target queue. Document it as a precondition, not folklore.

---

## The core tension

There are **two independent axes** that the current code half-conflates:

| Axis | Question | Today | Belongs to |
|------|----------|-------|-----------|
| **Error retry** | "the consumer *threw* — try again?" | `RetryPolicy` in `EventQueueToEventProcessorAgent` | consumer / drain side |
| **Flow control** | "the consumer is *slow* — what does the producer do?" | hardcoded spin-then-drop in `writeToQueue`; `SlowConsumerStrategy` ignored | producer / publish side |

**Decision 0 (foundational): keep these two axes separate.** `RetryPolicy` stays an error-handling concept on the drain side. `SlowConsumerStrategy` becomes the real flow-control concept on the publish side. Do not overload one to do the other's job. (Open question Q4 asks whether `RetryPolicy`'s *backoff schedule* is worth reusing as the shape of a `BACKOFF` wait — reuse the math, not the semantics.)

The second tension is **latency-loss vs. correctness-lossless**:
- Steady-state low-latency feeds (market data, telemetry) *want* drop-on-overflow — a stale tick is worthless, blocking the agent is worse.
- Replay, audit, and any "the ledger must be complete" feed *cannot* drop — correctness dominates latency, especially at startup when there is no real-time deadline at all.

A single global policy can't serve both. The design must make this **per-source (and per-phase: startup-replay vs steady-state)**.

---

## Proposed direction (to decide)

### A. Make `SlowConsumerStrategy` real, and extend it

Wire `writeToQueue` to consult the source's configured strategy instead of the hardcoded drop. Proposed semantics:

- **`BACKOFF`** (default, current behaviour made explicit) — spin up to a *configurable* bound, then drop + `WARNING`. Keeps the low-latency steady-state contract.
- **`DISCONNECT`** — on sustained overflow, unsubscribe this consumer's queue and report an error event, rather than dropping individual events forever. Protects the rest of the system from one stuck consumer.
- **`EXIT_PROCESS`** — fail-stop: log `CRITICAL` and exit. For deployments where silent divergence is unacceptable and a supervisor will restart + replay.
- **`BLOCK` (new)** — bounded-wait or unbounded-wait: the producer parks until the queue drains. Never drops. This is the **lossless** mode required for replay/audit. Needs care (see C — only safe when producer and consumer are on different threads).

### B. Make capacity and spin-bound configurable

Lift `1024` and `maxSpinNs = 10ms` into config (`EventFeedConfig` / `HandlerPipeConfig`), with the current values as defaults. A replay-heavy feed may want a much larger queue and/or `BLOCK`.

### C. Startup replay = its own mode (the part Greg is most concerned about)

Steady-state and startup replay are different regimes and should not share a policy:

- At startup there is **no real-time deadline**, so **drop is never the right answer** — the whole point of replay is faithful reconstruction.
- A fast producer re-driving a large cached source into a 1024 queue will always outrun a per-event processor. Spin-then-drop guarantees loss; the current `publishReplay` guarantees *silent* loss.

Options for the replay regime (pick one or make it configurable):
1. **Bounded-blocking replay** — replay publishes with `BLOCK`: `offer()` in a loop with `Thread.onSpinWait()` / park, never dropping. Simplest; correctness-complete; replay just takes as long as the consumer needs. Requires producer thread ≠ consumer agent thread (true for agent-hosted sources draining on a different agent — verify per source).
2. **Pull / credit-based replay** — the source only reads & publishes the next batch when the consumer signals queue headroom (e.g. drains below a low-water mark). Cleanest backpressure; more plumbing (a readiness signal back from the agent).
3. **Direct-drain replay** — at startup, bypass the cross-thread queue entirely and feed the processor inline until caught up, then switch to the live queue. Fastest, but changes threading/ordering semantics — risky for the determinism story; needs scrutiny.

**Minimum viable fix regardless of which we choose: `publishReplay` must stop ignoring `offer()`'s return value.** Even before the full design lands, the silent-drop line should become at least a spin-or-block.

### D. Determinism / governance hook

For the regulated pitch we likely want a deployment-level assertion: **"this server is configured lossless"** — i.e. every feed is `BLOCK` (or `DISCONNECT` with replay-on-reconnect), none is `BACKOFF`-drop. A boot-time validation + an audit/admin signal ("0 drops since start; lossless mode") turns the backpressure policy into a *provable* property, which is exactly the moat-shaped claim.

### E. Replay log: off-heap, Chronicle-backed, hidden behind config

The replay cache today is an **unbounded `ArrayList<NamedFeedEvent>` on heap** inside `EventToQueuePublisher` (smell #5). For the startup-large-source scenario that is a memory bomb: caching millions of events to enable replay grows the heap without bound and adds GC pressure to the exact path we want deterministic. The fix is to move the replay log **off heap and behind an interface**, with Chronicle Queue as the default backend — *hidden from users*, exposed only as a couple of config knobs.

**Shape:**
- Extract a `ReplayLog` interface (append + replay-from-position + truncate/bound) from the publisher — this is the "extract caching" layering principle made concrete. `EventToQueuePublisher` calls `replayLog.append(...)` instead of `eventLog.add(...)`; `publishReplay`/`dispatchCachedEventLog` read from it.
- Default implementation = **Chronicle Queue**, memory-mapped off-heap, replay-by-index. Users never see Chronicle in their config or API — it's an implementation detail of the runtime, the same way Agrona queues are. (We already depend on Chronicle via the mongoose-plugins connector, so no new external surface.)
- A trivial in-heap/no-op implementation stays available for tiny/ephemeral feeds and tests (monomorphic call site, JIT inlines it away when replay is off).

**Config knobs (the only user-visible surface):**
- **`durableReplay: true|false`** (default `false`). When `true`, the Chronicle log persists across restarts → restart-and-replay reconstructs full state from the on-disk log (Kafka-by-offset semantics, in-process). When `false`, the log is a roll-on-shutdown scratch file (off-heap, but not survivor of a restart) — solves the heap-growth problem without promising durability.
- **`maxMessageHistorySize`** (count or bytes; default bounded, *not* unbounded). Caps retained history; Chronicle roll-cycle / truncation enforces it. Past the bound, oldest records age out. This makes "replay the last N" cheap and bounds disk the way the queue bounds memory.
- These are likely **per-feed** (some feeds need full durable history, others want a small ring) but may also have a server-level default. Mirrors the per-subscriber-vs-global question for `SlowConsumerStrategy` (Q2).

**Why this is the right move:**
- It removes the unbounded-heap failure mode from the determinism-critical startup path.
- It upgrades the replay story from "in-memory Kafka-lite" to **"actual durable replay log, optional"** — closing the one capability gap vs Kafka/Chronicle in the comparison table, while keeping the in-process zero-copy hot path.
- `durableReplay` + a bounded `maxMessageHistorySize` is the operator-facing expression of the lossless/governance posture: a regulated deployment sets `durableReplay: true` and gets restart-survivable, position-addressable audit history for free.

**Interaction with backpressure:** the replay log is the *source* of a startup replay; the slow-consumer strategy (section A/C) governs the *publish* of those replayed records into the subscriber queues. A durable Chronicle log + `BLOCK`/credit replay = fully lossless startup: read from disk only as fast as the consumer drains. The two designs compose — that's the point.

---

## Open questions

- **Q1** — Is `BLOCK` safe for every source, or only agent-hosted sources whose producer thread differs from the consumer agent thread? If any source publishes *on* the consuming agent thread, `BLOCK` deadlocks. Need a per-source audit of who calls `publish`/`publishReplay` and on which thread.
- **Q2** — Should `SlowConsumerStrategy` be per-feed only, or also per-subscriber (one slow processor shouldn't force drop policy on a fast one sharing the feed)? The queues are already per-subscriber (`subscriberKeyToQueueMap`), so per-subscriber is feasible.
- **Q3** — `DISCONNECT`/`EXIT_PROCESS`: what's the trigger? Sustained overflow over a window, a drop-count threshold, or a max-queue-full-duration? Define the condition precisely.
- **Q4** — Do we reuse `RetryPolicy`'s backoff schedule (initial/max/multiplier) as the shape of a `BACKOFF` wait, or keep them fully separate? Reusing the *math* (not the semantics) avoids a second backoff config.
- **Q5** — `RetryPolicy.backoff()` sleeps on the agent thread, stalling co-hosted feeds. Is that acceptable, or should processing-retry move off the hot agent loop (defer + requeue)? Out of scope for backpressure proper, but adjacent and worth noting.
- **Q6** — Queue-depth metrics already exist (`EventFlowManager.sampleQueueDepths()`, counters service). Should the backpressure policy *drive* off live depth (high-water/low-water marks) rather than just the per-publish spin timeout?
- **Q7** — Replay log (section E): is `maxMessageHistorySize` counted in messages or bytes, and is retention per-feed or server-default? When `durableReplay: true`, what is the recovery contract on restart — replay *all* persisted records before accepting live events, or interleave? And how does a persisted log reconcile with pooled/`PoolAware` events (the Chronicle write must serialise a detached copy, as the heap cache already does via `String.valueOf` for `PoolAware`)?

---

## Proposed phasing (straw man)

1. **Stop the silent bleed** — `publishReplay` honours `offer()` (spin/block instead of ignore). Smallest correctness win; unblocks faithful startup replay. Add a drop counter so loss is *visible* even where it still occurs.
2. **Extract policy + cache as injectables** — pull a `BackpressureStrategy` and a `ReplayLog` interface out of `EventToQueuePublisher` (layering principles). No behaviour change; this is the refactor that makes 3–6 clean instead of more enums threaded through the hot path.
3. **Wire `SlowConsumerStrategy`** — `writeToQueue` consults the injected strategy; implement `BACKOFF` (= today) + `BLOCK`. Make capacity + spin-bound configurable. Default unchanged → no behaviour change unless opted in.
4. **Off-heap replay log** — Chronicle-backed `ReplayLog` default (hidden); add `durableReplay` + `maxMessageHistorySize` config (section E). Removes the unbounded-heap failure mode.
5. **Startup replay mode** — pick C.1/C.2/C.3; implement the chosen lossless replay regime over the `ReplayLog`; resolve Q1 thread-safety per source.
6. **`DISCONNECT` / `EXIT_PROCESS`** — implement the trigger condition (Q3) and the disconnect/exit actions.
7. **Lossless attestation** — boot-time validation + admin/audit signal for "configured lossless, 0 drops since start" (governance hook D).

---

## Files in scope

- `dispatch/EventToQueuePublisher.java` — `writeToQueue` (spin/drop), `publishReplay` (silent drop), `eventLog` (unbounded heap cache); target of the `BackpressureStrategy` + `ReplayLog` extraction.
- *(new)* `dispatch/BackpressureStrategy` — injected flow-control policy (extracted from `writeToQueue`).
- *(new)* `dispatch/ReplayLog` + Chronicle-backed default — off-heap replay store (extracted from `eventLog`); `durableReplay` / `maxMessageHistorySize`.
- `dispatch/EventFlowManager.java` — queue construction / capacity (1024).
- `dispatch/RetryPolicy.java` — error-retry policy (consumer side); possible backoff-math reuse.
- `dutycycle/EventQueueToEventProcessorAgent.java` — where `RetryPolicy` is applied; agent-thread `backoff` sleep.
- `service/EventSource.java` — `SlowConsumerStrategy` enum + `setSlowConsumerStrategy`.
- `service/extension/AbstractEventSourceService.java` — stores the (currently dead) strategy field.
- `config/EventFeedConfig.java`, `config/HandlerPipeConfig.java` — config surfaces that plumb the strategy.
- Source implementations to audit for thread-safety of `BLOCK` (Q1): `connector/memory/InMemoryEventSource.java`, `connector/file/FileEventSource.java`, Chronicle/Kafka/Aeron connectors in mongoose-plugins.
