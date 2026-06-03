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

## API sketch — `BackpressureStrategy` + `writeToQueue`, dynamic-deployment-aware

This is the concrete shape for phases 2–3. The governing constraint is **dynamic deployment**: event processors, feeds, and sinks can be added to (and removed from) a *running* container — `MongooseServer.addEventProcessor` already handles "server started, group thread not yet running → start it", services broadcast onto the agent thread via the dynamic-registration queues, and subscriptions wire through `queueReadersToAdd` at runtime. So the publisher↔subscriber↔thread topology is **mutable at runtime**, and the boot-time check (previous section) is necessary but not sufficient. The design must degrade safely when a dynamically-added edge violates a static guarantee.

### The interface

```java
package com.telamin.mongoose.dispatch;

/**
 * Policy applied when a publish cannot immediately enqueue into ONE target
 * queue. One instance per (publisher, target-queue) binding, resolved when the
 * queue is attached — at boot OR on a dynamic subscription — and never mutated
 * thereafter (re-subscription = re-attach with a fresh binding).
 *
 * Hot-path contract: allocation-free. onQueueFull is only ever called AFTER an
 * offer() has already failed, so it is off the steady-state fast path. The
 * default BACKOFF/DROP strategy is a stateless singleton so the call site stays
 * monomorphic and the JIT inlines it (same discipline as the counters no-op).
 */
public interface BackpressureStrategy {

    enum Decision { RETRY, PARKED_RETRY, DROP }   // RETRY: spin now; PARKED_RETRY: already parked/yielded; DROP: abandon item

    Decision onQueueFull(QueueBinding binding, int attempt, long firstFailNanos);

    default String id() { return getClass().getSimpleName(); }
}
```

`QueueBinding` carries the per-binding context — created once at attach, held by the `NamedQueue`, never allocated on the hot path:

```java
public interface QueueBinding {
    String name();
    int size();                       // targetQueue.size()
    int capacity();

    /** True iff the thread that DRAINS this queue is the current (publishing)
     *  thread — the BLOCK deadlock condition. Lazy: reads the draining agent's
     *  thread via a supplier, because the agent may not have started yet, or
     *  may have restarted, since attach. */
    boolean drainsOnCurrentThread();

    /** False once the subscriber has been dynamically undeployed / the queue
     *  removed. A parked BLOCK must re-check this so undeploying a stuck
     *  consumer wakes the producer instead of hanging it forever. */
    boolean isLive();

    void recordDrop(long sequenceNumber);          // MongooseCounter — visibility
    void recordSameThreadDegrade();                // distinct counter — "BLOCK degraded to avoid deadlock"
}
```

### Integration into `writeToQueue`

The hardcoded spin-10 ms-then-drop becomes a strategy dispatch. The fast path (successful `offer`) is unchanged; only the contended branch changes:

```java
private void writeToQueue(NamedQueue namedQueue, Object itemToPublish) {
    final OneToOneConcurrentArrayQueue<Object> targetQueue = namedQueue.targetQueue();
    final BackpressureStrategy strategy = namedQueue.backpressure();   // never null; default = BACKOFF singleton
    final QueueBinding binding = namedQueue.binding();
    final PoolTracker<?> tracker = trackerOf(itemToPublish);
    int attempt = 0;
    long firstFailNanos = -1;
    try {
        while (true) {
            if (tracker != null) tracker.acquireReference();
            if (targetQueue.offer(itemToPublish)) return;             // FAST PATH — unchanged
            if (tracker != null) tracker.releaseReference();          // release per-attempt ref
            attempt++;
            if (firstFailNanos < 0) firstFailNanos = System.nanoTime();
            switch (strategy.onQueueFull(binding, attempt, firstFailNanos)) {
                case RETRY        -> Thread.onSpinWait();
                case PARKED_RETRY -> { /* strategy already parked; loop and retry */ }
                case DROP         -> { binding.recordDrop(sequenceNumber); return; }
            }
        }
    } catch (Throwable t) {
        if (tracker != null) tracker.returnToPool();
        // ... existing CRITICAL error-report + QueuePublishException path ...
    }
}
```

`publishReplay` routes through the **same** `writeToQueue` (fixing its current bare `offer()`), so replay inherits the configured strategy automatically.

### The strategies

- **`BackoffDropStrategy` (default singleton, == today's behaviour made explicit):** `RETRY` while `now - firstFailNanos <= maxSpinNs`; else `recordDrop` + `WARNING` and `DROP`. `maxSpinNs` is now config (section B), defaulting to 10 ms.
- **`BlockStrategy` (lossless):**
  ```java
  public Decision onQueueFull(QueueBinding b, int attempt, long firstFailNanos) {
      if (b.drainsOnCurrentThread()) {      // would deadlock (boot check missed it, or a dynamic edge created it)
          b.recordSameThreadDegrade();
          return Decision.DROP;             // degrade rather than hang; logged loudly
      }
      if (!b.isLive()) return Decision.DROP; // subscriber undeployed mid-park → abandon, don't hang
      parkNanos(parkSliceNs);                // bounded park (LockSupport / idle strategy), NOT indefinite
      return Decision.PARKED_RETRY;
  }
  ```
  The two guards — `drainsOnCurrentThread()` and bounded-park-+-`isLive()` — are precisely what make `BLOCK` safe under dynamic deployment: a runtime-added colocated subscriber degrades instead of deadlocking, and a runtime-removed subscriber wakes the producer.
- **`DisconnectStrategy` / `ExitProcessStrategy`:** on sustained overflow (Q3 trigger), unsubscribe the queue (remove the `NamedQueue` via the existing `removeTargetQueueByName`) + error event, or fail-stop.

### How dynamic deployment is handled (the load-bearing part)

1. **Strategy + binding are resolved at *attach*, not at boot.** `EventToQueuePublisher.addTargetQueue(...)` gains the source's configured `SlowConsumerStrategy` and a `QueueBinding`. The single caller that wires a subscriber queue to both a publisher and a draining group is **`EventFlowManager`** — and *runtime* subscriptions flow through the same path as boot ones. So a processor deployed into a running container gets its strategy + same-thread context wired identically; there is no separate dynamic code path to keep in sync.
2. **Draining thread is a supplier, not a snapshot.** `QueueBinding.drainsOnCurrentThread()` reads the draining `ComposingEventProcessorAgentRunner.thread()` lazily, tolerating "group created but not yet started", stop/restart, and rebind.
3. **`isLive()` + bounded park** make undeploy safe: removing a subscriber flips the binding dead; the next park slice abandons. Indefinite parking is never used.
4. **`targetQueues` is already a `CopyOnWriteArrayList`** — add/remove of subscriber queues during concurrent publish is already safe; the per-binding strategy/context is immutable, so no extra synchronisation on the hot path.
5. **Validation runs on every topology change, not just boot.** `validateBackpressureTopology()` (previous section) becomes a function invoked from the dynamic-registration/subscription path too. For a dynamic add it can't "reject the boot", so its options are: (a) refuse the subscription with an error event, (b) auto-decouple the feed onto its own thread, or (c) accept and rely on the runtime `BlockStrategy` guard to degrade. The runtime guard is the backstop that keeps the system **safe-by-default even if validation is bypassed or races a concurrent deploy** — which is exactly the property a hot-deployable container needs.

### Call sites that change

- `EventToQueuePublisher.NamedQueue` record gains `backpressure()` + `binding()` (or a small holder); `addTargetQueue` takes them.
- `EventToQueuePublisher.writeToQueue` — as above; `publishReplay` delegates to it.
- `EventFlowManager` — constructs the `QueueBinding` (with the draining-agent thread supplier + liveness flag) at subscribe time; passes the feed's `SlowConsumerStrategy` through. This is the one seam shared by boot and dynamic subscription.
- `MongooseServer` / dynamic-registration path — invoke `validateBackpressureTopology()` on processor/feed/subscription add; flip binding liveness on remove.

---

## Open questions

- **Q1 — RESOLVED by audit (see below).** `BLOCK` is *not* universally safe: it deadlocks whenever the thread doing the blocking publish is also the thread that drains the target (subscriber) queue. Today's spin-then-drop is deadlock-proof only because it gives up after 10 ms. `BLOCK` removes that escape hatch and therefore needs a same-thread guard.

### Q1 audit — who calls `publish` / `publishReplay`, and on which thread

`writeToQueue` runs on **whatever thread calls `output.publish(...)`** — it is not intrinsically the source's agent thread. There are two distinct entry points, with different threading:

| Path | Entry | Thread `writeToQueue` runs on | Deadlock-safe for `BLOCK`? |
|------|-------|-------------------------------|----------------------------|
| **Staged** | `InMemoryEventSource.offer()` → `pending` (`ConcurrentLinkedQueue`) → drained by the source's own `doWork()` | the **source's** agent thread (when agent-hosted, i.e. its own `agentName` group) | **Yes**, as long as the source's agent group ≠ the subscriber's agent group — the subscriber keeps draining while the source thread blocks. |
| **Inline** | `HandlerPipe.sink().accept()` → `forward()` → `InMemoryEventSource.publishNow()` → `output.publish()` **directly** | the **caller's** thread (the publishing processor's agent thread) | **Only if** the publishing processor's group ≠ the subscriber's group. |

Key findings:
- **`HandlerPipe` publishes *inline* via `publishNow`, bypassing the `pending` staging queue.** So the cross-thread-safety claim in `HandlerPipeConfig`'s javadoc ("publishers enqueue and a dedicated agent thread drains") holds via the Agrona **SPSC** handoff into the subscriber's read queue, *not* via a staging hop — the publish itself executes on the caller's thread. Safe today because the SPSC queue tolerates one writer + one reader on different threads; the single-writer invariant holds as long as one source is published from one thread.
- **Re-entrant *self*-republication is NOT a `writeToQueue`/`BLOCK` concern.** When a handler re-publishes during its own cycle it calls `getContext().processAsNewEventCycle(...)` — this lands in the **generated event processor's own internal queue** (`DataFlowContext`), drained after the current cycle on the same thread. It never traverses mongoose's dispatch layer. The generated processor *carries its own queue*, so same-processor re-entrancy is already solved one layer down (exercised by `ReEntrantHandler`/`ReEntrantTest`). This is the key correction: the self-loop case is handled by the processor, not the bus.
- **The remaining `BLOCK` deadlock case is cross-processor on a shared agent thread**: publisher processor P1 and subscriber processor P2 sit in the **same agent group** (one thread). P1 blocking inside `writeToQueue` starves the shared `ComposingEventProcessorAgent` loop, so P2 never drains the queue P1 is waiting on → deadlock. The generated processor's internal queue does **not** help here — it's a *different* processor. (Plus any future "publish inline on the consumer's agent thread" path.)
- **No explicit `Thread.currentThread()` same-thread check exists in the dispatch layer today.** The de-facto defence for the cross-thread case is structural (Agrona SPSC: one writer thread + one reader thread), and the spin-then-drop timeout is what currently prevents the same-thread case from hanging — it drops instead of deadlocking.

**So a same-thread guard is needed on the `BLOCK` path, but only for the cross-processor-same-group case** — self-loops are already absorbed by the generated processor. Recommended: before parking, compare the current thread against the target queue's draining-agent thread; if they match, do **not** block — fall back to one of: (i) stage into the source's `pending` queue and let `doWork` drain it next cycle (the existing decoupling mechanism), (ii) drop + count + `WARNING` (degrade to `BACKOFF` rather than deadlock). This requires the target queue to expose its draining-agent identity to the publisher (a small wiring addition in `EventFlowManager`). An alternative to a runtime guard: a **boot-time topology rule** — reject (or auto-decouple onto its own agent) any `BLOCK` feed whose subscriber shares an agent group with the publisher, so the unsafe case can't be configured in the first place.

### Q1 follow-up — where the agent-group mapping is known at boot

The topology needed to validate `BLOCK` safety statically is fully materialised by the end of boot. The pieces and their homes:

| Edge needed | Where it lives at boot |
|-------------|------------------------|
| **processor → agent group (thread)** | `MongooseServer.composingEventProcessorAgents` — a `ConcurrentHashMap<String groupName, ComposingEventProcessorAgentRunner>`. One group = one `ComposingEventProcessorAgent` = one `AgentRunner` = one thread. Populated by `addEventProcessor(name, groupName, …)`, which `ServerConfigurator` calls with `groupName = cfg.getAgentName()` from `EventProcessorGroupConfig`. |
| **agent-hosted feed → its own group (thread)** | `MongooseServer.composingServiceAgents` (worker/feed agents). An agent-hosted source registered via `registerEventFeedWorker(toServiceAgent())` drains on `service.agentGroup()`'s thread. |
| **subscriber → source edge (which group drains a feed's queue)** | `EventFlowManager` (the `subscriberKeyToQueueMap`) + each group's `ComposingEventProcessorAgent.queueProcessorMap` (`EventSubscriptionKey → EventQueueToEventProcessor`). Scanning every group's `queueProcessorMap` for keys referencing source `S` yields exactly *which groups (threads) drain S*. Established during `init()`/`start()` when a processor's handler calls `getContext().subscribeToNamedFeed(...)`. |
| **feed → backpressure policy** | `EventFeedConfig.slowConsumerStrategy` / `HandlerPipeConfig.slowConsumerStrategy` (today set onto the source, ignored — once wired, readable per feed). |
| **pipe → publisher group(s)** | Pipes register a `PipeRegistration(feedName, sinkName, agentName, …)` in `MongooseServer`. The sink is offered to processors via `@ServiceRegistered` broadcast (`ComposingEventProcessorAgent.broadcastServiceRegistered`); the *potential* publishers are the processors with an `@ServiceRegistered` binding for that `MessageSink` — and each such processor's group is known from `composingEventProcessorAgents`. (Exact "does it actually call `accept`" is not statically decidable; treat all injected processors as potential publishers — sound, slightly conservative.) |

**Hook point.** Add a `validateBackpressureTopology()` step in `ServerConfigurator.bootFromConfig` **after `mongooseServer.init()` and before `mongooseServer.start()`** — at that moment subscriptions and service injection are wired but no agent thread has started, so rejecting is clean. It reads the maps above off `MongooseServer` (likely via a small package-private accessor, mirroring how `sampleQueueDepths()` reaches into `EventFlowManager`).

**Algorithm (per feed configured `BLOCK`):**
1. Compute `drainGroups(S)` = set of groups whose `queueProcessorMap` subscribes to S.
2. Compute `publishGroup(S)` = the thread `writeToQueue` will run on:
   - agent-hosted source → its own `agentGroup`;
   - inline pipe (`publishNow`) → the group(s) of injected publisher processors.
3. If `publishGroup(S) ∩ drainGroups(S) ≠ ∅` → **unsafe**. Either reject at boot with a precise message, or auto-decouple (force S onto its own agent group / route the pipe sink through staged `offer()` so its publish runs on a dedicated thread).

**The clean crystallisation:** *a `BLOCK` feed must publish from a thread that none of its subscribers drain on.* Agent-hosted sources already satisfy this unless a subscriber is wrongly colocated in the source's own group (a trivial set-intersection to check). The only structurally exposed case is the **inline pipe** — so the targeted fix is: when a pipe's strategy is `BLOCK`, route `HandlerPipe.forward` through `offer()`/`doWork` (staged, on the pipe's own agent thread) instead of `publishNow` (inline on the publisher's thread). That moves the publish off both the publisher's and subscriber's threads, after which the residual boot check is just "no subscriber shares the pipe's own agent group." This converts the deadlock question from a runtime race into a **static, boot-time-provable property** — which is the form the governance pitch wants.
- **Q2** — Should `SlowConsumerStrategy` be per-feed only, or also per-subscriber (one slow processor shouldn't force drop policy on a fast one sharing the feed)? The queues are already per-subscriber (`subscriberKeyToQueueMap`), so per-subscriber is feasible.
- **Q3** — `DISCONNECT`/`EXIT_PROCESS`: what's the trigger? Sustained overflow over a window, a drop-count threshold, or a max-queue-full-duration? Define the condition precisely.
- **Q4** — Do we reuse `RetryPolicy`'s backoff schedule (initial/max/multiplier) as the shape of a `BACKOFF` wait, or keep them fully separate? Reusing the *math* (not the semantics) avoids a second backoff config.
- **Q5** — `RetryPolicy.backoff()` sleeps on the agent thread, stalling co-hosted feeds. Is that acceptable, or should processing-retry move off the hot agent loop (defer + requeue)? Out of scope for backpressure proper, but adjacent and worth noting.
- **Q6** — Queue-depth metrics already exist (`EventFlowManager.sampleQueueDepths()`, counters service). Should the backpressure policy *drive* off live depth (high-water/low-water marks) rather than just the per-publish spin timeout?
- **Q7** — Replay log (section E): is `maxMessageHistorySize` counted in messages or bytes, and is retention per-feed or server-default? When `durableReplay: true`, what is the recovery contract on restart — replay *all* persisted records before accepting live events, or interleave? And how does a persisted log reconcile with pooled/`PoolAware` events (the Chronicle write must serialise a detached copy, as the heap cache already does via `String.valueOf` for `PoolAware`)?
- **Q8** — Dynamic deployment policy: when a *runtime* subscription would create an unsafe `BLOCK` edge (new subscriber colocated with the publisher's thread), which of the three responses is the default — refuse the subscription (error event), auto-decouple the feed onto its own agent thread, or accept-and-degrade via the runtime guard? Refuse is safest for determinism but can fail a hot deploy; auto-decouple is most operator-friendly but silently changes threading. Likely a per-deployment policy with a conservative default (auto-decouple) + an audit signal.

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
