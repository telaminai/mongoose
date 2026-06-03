# Backpressure & slow-consumer handling

**Status**: Draft / problem-framing — rev 3 (two review rounds + consumer-side-replay reframe incorporated). No code yet — this doc exists to settle the design before touching the dispatch hot path.
**Headline reframe (rev 3)**: deterministic replay is **consumer-side event sourcing** (a Fluxtion `Auditor` records the realised post-merge order + data-driven clock; replay re-drives it, bypassing the bus). So **backpressure is NOT the determinism lever** — it governs the separate, opt-in *input-completeness / chain-of-custody* guarantee. Determinism is structural and policy-independent; losslessness (`BLOCK`) is the add-on that makes the audit record complete.
**Owners**: Greg
**Drives**: deterministic startup replay of large sources, an off-heap (Chronicle-backed) replay log with `durableReplay` / `maxMessageHistorySize` config, a *lossless* mode for regulated/audit deployments, making `SlowConsumerStrategy` a real knob instead of a dead field, and a clear split between the error-retry axis (`RetryPolicy`) and the flow-control axis (`SlowConsumerStrategy`).
**Scope**: **source-to-processor dispatch backpressure** only — where a slow *processor* backs up its read queue. **Processor-to-external-sink backpressure** (a slow external sink — Kafka/file/socket IO that can't keep up, on the `ManyToOne` sink path) is a different surface and is **explicitly out of scope** here; it gets its own doc. (Avoiding "inbound", which externally reads as "events entering Mongoose".)
**Global invariant (governs the whole design)**: *no unbounded blocking wait on a shared duty-cycle agent thread.* Every wait on a shared agent loop must be bounded and re-check liveness. This single rule is the root of three otherwise-separate hazards — the `BLOCK` same-thread deadlock (Q1), the pool-exhaustion deadlock (Q1a), and the existing `RetryPolicy.backoff()` `Thread.sleep` head-of-line stall (Q5, a live bug today). Treat any blocking primitive that can land on a shared agent thread as guilty until proven bounded.

---

## Motivation

Mongoose's pitch leads with **deterministic replay and audit**. The crucial reframe (see "Replay is consumer-side event sourcing" below): **deterministic replay does NOT depend on backpressure at all** — it is captured at the consumer by a Fluxtion `Auditor` and is policy-independent. So the common framing "a dropped event means production diverges from replay" is **false**: a dropped event never reaches the processor, is never audited, and replay omits it identically — replay still equals production, drops and all. Backpressure is therefore *not* the determinism lever.

What backpressure actually governs is a **different, weaker-but-real guarantee: input completeness / chain-of-custody** — closing the gap between "arrived at the boundary" and "delivered to the processor (and therefore audited)." That is the regulated-buyer concern worth solving, and the honest pitch is:

> **Determinism is structural and policy-independent (the consumer-side auditor). Losslessness is an opt-in completeness guarantee on top (BLOCK).** Without BLOCK, the audit log is internally consistent and perfectly replayable but *incomplete* — it cannot witness what was dropped before the processor saw it (only the drop counter can). With BLOCK, "arrived" == "audited", and the audit log becomes a complete chain of custody.

With that cut, the things this doc must fix are narrower and clearer:

- Today, when a consumer can't keep up, the publisher **drops the event after ~10 ms** and logs a `WARNING` — a *completeness* gap (the event never reaches the processor/audit log), not a determinism bug. `SlowConsumerStrategy` is supposed to govern this but is dead.
- The dedicated feed-replay path (`publishReplay`) drops **silently** — it doesn't even check the `offer()` result. This matters for **late-subscriber catch-up** (priming a new subscriber's backlog through the bus), which is the one place feed-side replay is real (see C/E) — *not* for deterministic recovery, which goes through the auditor and bypasses the bus entirely.
- We ship two config surfaces that *look* like backpressure control (`SlowConsumerStrategy`, `RetryPolicy`) but neither does what an operator would assume.

This doc inventories what exists, separates the two guarantees cleanly, and proposes a design to decide on.

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
- **Replay-equivalence is structural — but it lives in the *consumer-side `Auditor`*, not this feed cache.** *(Corrected:)* the determinism differentiator is the per-processor audit-record + data-driven clock (see "Replay is consumer-side event sourcing"), which captures the realised order independent of the bus. The publisher's `eventLog`/`publishReplay` is a *different* thing — late-subscriber catch-up through the queues — and must **not** be sold as the determinism mechanism. Protect the auditor story; re-scope the feed cache to catch-up.
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

## Replay is consumer-side event sourcing (merge order is *captured*, not reconstructed)

The instinct is to worry about cross-feed merge: a processor draining N feeds sees an *interleaving*, `BLOCK` changes that interleaving, so two replays might present different merge orders → different output. That worry would force an expensive fix — a global ingress order key on *every* event plus a merge step in the drain loop. **The worry is moot**, because Fluxtion records replay at the **consumer**, after the merge has happened, so merge order is captured rather than reconstructed. The rest of this section is why, and what it changes.

Fluxtion already has the right mechanism, and it makes most of the above moot. It is an **event-sourcing pattern with data-driven time** (ref: `fluxtion/docs/how-to/replay-functionality.md`; a commercial AOT-compiler feature). Every event processor supports an **`Auditor`** that sees *every event the processor processes, in the exact order it processes them* — auditors fire **before any business-logic node**. `YamlReplayRecordWriter` is exactly this: an `Auditor` whose `eventReceived(Object)` captures a `ReplayRecord{event, eventTime}` for each event in observed order. `YamlReplayRunner` then re-drives those records straight into `eventProcessor.onEvent(...)` — and crucially **sets the processor `Clock` to each record's recorded time** so time-dependent logic (timeouts, scheduling, windows) replays deterministically — **bypassing feeds, queues, and the dispatch layer entirely**. The documented guarantee is that *"audit logs from replay exactly match production"* — faithful reconstruction, full stop.

The consequence is large:

- **Replay order is captured *after* the cross-feed merge, at the consumer.** So replay-equivalence does **not** depend on reproducing the live interleaving of feeds, and **no global ingress order key across feeds is required.** The expensive fix (an order key on every event + a merge step in the drain loop) is avoided; the merge order is simply *recorded* as it happened.
- **This cleanly separates two properties that the doc had been conflating:**
  - **Faithful reconstruction / replay-equivalence** — "replay reproduces exactly what the processor did." Guaranteed by the consumer-side audit record, **independent of backpressure policy**. Even if events were dropped live, replay reproduces the same run, because the record contains exactly what the processor saw. This is the property the regulated / incident-reconstruction / audit buyer actually wants.
  - **Losslessness** — "no input event is ever dropped." A *separate*, stronger property, and the only thing `BLOCK`/`DISCONNECT` exist for. It matters only when the business requires every input be processed; it is **not** a precondition for deterministic replay.
- **It composes across processors — the distributed-merge problem collapses.** Each processor's audit log is a *self-contained, complete, ordered record of its own inputs*, including events another processor published to it. Replaying P2 from P2's log reproduces P2 exactly **without needing P1 at all**. There is no global, cross-processor ordering to maintain; capture+replay is independent per processor. This is the elegant core, and it's why no global order key is needed anywhere.
- **Startup replay of a large source sidesteps the dispatch layer.** Replaying from the audit record drives `onEvent` directly into the processor in recorded order — a single ordered stream on one thread. There is no cross-feed merge to reconstruct and no `writeToQueue`/`publishReplay` backpressure to fight (in a mongoose deployment, feed the recorded stream into the processor's own agent loop as a single ordered source). The original "how do we replay a huge feed through 1024-slot queues without dropping" worry is largely answered by *not replaying through the feeds at all* — this is the *recovery* path (C), distinct from late-subscriber catch-up.

**So the phase-0 contract has a recommended answer:** *deterministic replay = re-drive the consumer-side audit record (the `Auditor`-captured order); it does not require deterministic live cross-feed merge nor a global order key.* What backpressure must still get right is the **separate** losslessness question for feeds that require it — and even there, a dropped event is faithfully reconstructed by replay; it's just (correctly) recorded as having been dropped.

**Implications threaded through the rest of this doc:**
- The durable replay log (section E) belongs at the **consumer/auditor** level — an "audit-replay-writer" auditor writing `ReplayRecord`s to Chronicle — **not** as the per-feed `eventLog` cache inside `EventToQueuePublisher`. One record per processor, already in processing order, nothing to merge. This is cleaner than the section-E framing and supersedes it (see E).
- The auto-decouple ↔ merge-order worry (Q8) evaporates for *replay* purposes: staging hops change live timing but replay is consumer-recorded, so it reproduces faithfully regardless.
- `EventToQueuePublisher.eventLog` is still useful for its *original* job (caching for late-joining subscribers / dispatch), but it is **not** the replay-of-record mechanism and should not be grown into one.

**Two constraints this puts on mongoose (must state, easy to violate):**
- **All processor inputs — and time — must flow through the audited event path.** Replay fidelity requires that everything the processor reacts to arrives as an event the auditor sees, and that time comes from the data-driven clock. Mongoose's service-injection model makes it easy to violate: a processor driven by a **direct injected-service method call** or a **scheduler tick** *outside* the event path is an "external dependency" (the exact failure mode the Fluxtion troubleshooting docs name) — invisible to the auditor, so replay diverges. Constraint: route all processor inputs and time through the event/clock path. Worth a lint/validation check.
- **Where is the completeness boundary sold?** The auditor sits at **processor input — *after* backpressure has dropped.** So the audit log faithfully records *what the processor saw*, i.e. "what survived." If a regulated buyer needs "everything that arrived at the **system boundary**," a queue-dropped event is invisible to the processor auditor — only the drop counter witnesses it. For true ingress chain-of-custody you therefore need either `BLOCK` (nothing dropped before the processor audits it, so "arrived" == "audited") **or** a second auditor/record at ingress. State explicitly which boundary the guarantee is sold at — this is precisely the `BLOCK`/completeness justification, now correctly located.

**Pooled events into the audit log (first-order requirement, not a footnote).** `eventReceived` serialises the live event object; for `PoolAware` events the audit-writer must serialise a **detached copy**, or a recycled instance corrupts the record (same hazard as the heap cache's `String.valueOf` workaround, Q7). For a durable Chronicle audit-writer this is mandatory, not optional.

---

## Proposed direction (to decide)

### A. Make `SlowConsumerStrategy` real, and extend it

Wire `writeToQueue` to consult the source's configured strategy instead of the hardcoded drop. Proposed semantics:

The per-queue flow-control policy answers one question — *"what does this queue's producer do on overflow?"* — and has exactly three peer answers:

- **`BEST_EFFORT_DROP`** (default; **legacy name `BACKOFF`**) — spin up to a *configurable* bound, then drop + `WARNING`. Keeps the low-latency steady-state contract. ⚠️ **Naming:** the existing enum calls this `BACKOFF`, which misleads — it does *not* slow the producer to preserve data, it eventually **drops**. Rename to `BEST_EFFORT_DROP` (or `SPIN_THEN_DROP`); keep `BACKOFF` as a deprecated alias documented as "bounded spin then drop — not lossless." Operators must not read `BACKOFF` as lossless.
- **`DISCONNECT`** — on sustained overflow, unsubscribe this consumer's queue and report an error event, rather than dropping individual events forever. Protects the rest of the system from one stuck consumer.
- **`BLOCK` (new)** — the producer parks (bounded slices, never indefinitely) until the queue drains. **In strict mode, never drops** (see safety modes below). Needs care (see C and Q1 — only safe when producer and consumer are on different threads *and* the pool can't be exhausted by parked producers).

**Deployment safety mode — resolves the strict-vs-degrade contradiction.** A `BLOCK` strategy that *degrades to DROP* on an unsafe topology (the API sketch's `drainsOnCurrentThread`/`isLive`/pool-cap fallbacks) is convenient for liveness but **breaks the lossless contract** — and therefore breaks the config-based attestation in hook D. The two cannot both hold. Resolve with an explicit deployment posture:

| Safety mode | `BLOCK` on unsafe topology | Lossless? | Attestation |
|-------------|----------------------------|-----------|-------------|
| **`STRICT_LOSSLESS`** | **reject** — fail the boot config or the dynamic subscription; never drop | **Yes, provable from config** | passes |
| **`SAFE_DEGRADE`** | degrade to DROP/DISCONNECT, emit an error event, mark feed no-longer-lossless | No (conditional) | fails (or "lossless-until-degrade") |
| **`BEST_EFFORT`** | n/a — drop is expected | No | n/a |

**Recommendation:** default attested/lossless (`BLOCK`) feeds to **`STRICT_LOSSLESS` = frozen-at-boot** (reject any runtime subscription that would create an unsafe `BLOCK` edge; size pools for max blocked producers up front). A regulated deployment happily trades hot-deploy flexibility on its lossless feeds for "provably lossless from configuration alone." Offer `SAFE_DEGRADE` as opt-in for non-attested deployments that want hot deploy more than they want the guarantee. This makes hook D's strong claim true again, and makes the dynamic-deployment section's posture (b) an explicit opt-out rather than the silent default.

**`EXIT_PROCESS` is NOT a peer of these — it's an escalation tier.** Killing the JVM is a *supervision* policy, orthogonal to per-queue flow control; bundling it into `SlowConsumerStrategy` means per-subscriber config (Q2) could let one subscriber's overflow take down the whole process, which is a strange capability to expose per-subscriber. Model it instead as an **escalation ladder** on top of the per-queue policy: e.g. `on unrecoverable overflow: DISCONNECT → (if N disconnects / a critical feed) EXIT_PROCESS`. The per-queue strategy stays `{BACKOFF, DISCONNECT, BLOCK}`; process-exit is a separate, deployment-level escalation rule. (Migration note: the existing `SlowConsumerStrategy` enum still lists `EXIT_PROCESS` — keep it accepted but route it to the escalation tier, or deprecate it on the per-feed surface.)

### B. Make capacity and spin-bound configurable

Lift `1024` and `maxSpinNs = 10ms` into config (`EventFeedConfig` / `HandlerPipeConfig`), with the current values as defaults. A catch-up-heavy feed may want a much larger queue and/or `BLOCK`.

**Capacity config scope (specify precisely):** `server.defaultSubscriberQueueCapacity = 1024` → optional `feed`-level override → optional most-specific `subscription`-level override. Capacity is **immutable after queue creation** (Agrona arrays are fixed-size; resizing = re-subscribe). Constrain to **power-of-two** (Agrona requirement). The outbound `sink` queue capacity is a *separate* setting on the out-of-scope sink path — do **not** fold it into the subscriber-queue knob.

### C. Startup replay — split into recovery vs late-subscriber catch-up

> **Re-scoped by the consumer-side-replay resolution.** "Startup replay" was two different things:
> - **Deterministic recovery** (rebuild a processor's prior run) — uses the **consumer-side audit log**, drives `onEvent` directly, **bypasses the bus and backpressure entirely**. This is the case Greg was most worried about ("replaying a large source at startup"), and it is **already solved** by the auditor + data-driven clock — no queues, no `BLOCK`, no drop risk. Nothing for backpressure to do here.
> - **Late-subscriber catch-up** (prime a *new* subscriber that has no audit log of its own) — goes **through the bus**, and is the one place the regime below (and `BLOCK`) genuinely applies.
>
> The rest of this section is about the **catch-up** case only.

Steady-state and catch-up are different regimes and should not share a policy:

- At startup there is **no real-time deadline**, so **drop is never the right answer** — the whole point of replay is faithful reconstruction.
- A fast producer re-driving a large cached source into a 1024 queue will always outrun a per-event processor. Spin-then-drop guarantees loss; the current `publishReplay` guarantees *silent* loss.

Options for the replay regime (pick one or make it configurable):
1. **Bounded-blocking replay** — replay publishes with `BLOCK`: `offer()` in a loop with `Thread.onSpinWait()` / park, never dropping. Simplest; correctness-complete; replay just takes as long as the consumer needs. Requires producer thread ≠ consumer agent thread (true for agent-hosted sources draining on a different agent — verify per source).
2. **Pull / credit-based replay** — the source only reads & publishes the next batch when the consumer signals queue headroom (e.g. drains below a low-water mark). Cleanest backpressure; more plumbing (a readiness signal back from the agent). **Note: this is the *same mechanism* as the depth-driven policy in Q6.** A low-water-mark readiness signal *is* a credit signal, and `EventFlowManager.sampleQueueDepths()` already supplies the depth. So C.2 and Q6 are not two designs — they're one: **depth-driven credit**, viable for both steady-state flow control and startup replay. If we build it, build it once and use it in both regimes.
3. **Direct-drain replay** — at startup, bypass the cross-thread queue entirely and feed the processor inline until caught up, then switch to the live queue. Fastest, but changes threading/ordering semantics — risky for the determinism story; needs scrutiny.

**Minimum viable fix regardless of which we choose: `publishReplay` must stop ignoring `offer()`'s return value.** But the first step must be **bounded-spin + drop-counter only — NOT block.** A blocking `publishReplay` needs the same-thread guard (Q1) and pool-slot cap (Q1a), which don't land until later phases; blocking on the source's own drain thread before the guard exists deadlocks with *no* 10 ms escape hatch — strictly worse than today. So phase 1 makes the loss *visible and bounded*; lossless/blocking replay waits for the guards. (See phasing.)

### D. Determinism / governance hook

For the regulated pitch we want a deployment-level assertion: **"this server is configured lossless."** The provable property is **static config** — every feed is `BLOCK` (or `DISCONNECT` with replay-on-reconnect), none is `BACKOFF`-drop, and every durable feed is unbounded-or-snapshotted (section E). That is what attestation must key on.

**Do not key attestation on the drop counter.** "0 drops since start" is *not* the same as "configured lossless": a `BEST_EFFORT_DROP` feed that simply never happened to overflow reports zero drops while remaining lossy by configuration. So lead with **config attestation** (the static, boot-evaluable property); the drop counter is *corroborating telemetry* ("and indeed nothing has been dropped"), not the assertion itself.

**This only holds under `STRICT_LOSSLESS` (section A).** If the deployment runs `SAFE_DEGRADE`, a `BLOCK` feed *can* silently drop at runtime (same-thread, undeploy-mid-park, pool-cap), so config alone no longer proves losslessness — the attestable property weakens to "config + every degrade counter == 0 since start" (`recordSameThreadDegrade`, undeploy-drop, pool-cap-drop). So: attestation = **`STRICT_LOSSLESS` mode + lossless config** (the strong, config-only claim), or under `SAFE_DEGRADE`, **config + zero degrade events** (the degrade counters become part of the provable surface, not mere telemetry). A correct strict attestation reads: "STRICT_LOSSLESS; N feeds BLOCK/DISCONNECT, 0 BEST_EFFORT_DROP; durable history unbounded/snapshotted." Pick `STRICT_LOSSLESS` for the regulated pitch — it's the only one provable from configuration alone.

### E. Replay log: off-heap, Chronicle-backed, hidden behind config

> **Reframed by the consumer-side-replay resolution (see "Replay is consumer-side event sourcing").** There are **two distinct things both called "replay"**, at two different pipeline points; the doc had conflated them. Separating them resolves almost everything:

| | **Consumer-side audit replay** | **Feed-side catch-up replay** |
|---|---|---|
| **Source** | per-processor `Auditor` log (`ReplayRecord`) | `EventToQueuePublisher.eventLog` / `publishReplay` |
| **Path** | `onEvent()` direct into a rebuilt processor — **bypasses the bus** | through the queues, fan-out, **backpressure** |
| **Purpose** | debug / test / deterministic recovery of an existing processor's run | prime a **new/late subscriber** that has no audit log of its own |
| **Backpressure relevance** | **none** — no queues involved | **this is the only place `BLOCK`/lossless matters** |
| **Determinism** | guaranteed by capture + data-driven clock | not a determinism mechanism; catch-up priming only |

So *"replay from event feeds is not important"* is right **for the determinism story** — but the feed-side path doesn't vanish; it is re-scoped to **late-subscriber catch-up**, which is exactly where `BLOCK` is justified (don't drop a late subscriber's backlog while priming its state). Once that subscriber is live, its own audit log takes over, so determinism never rides on the feed path.

**Therefore `durableReplay` means: persist the per-processor *audit* stream** — build a Chronicle-backed `ReplayRecordWriter` (a durable `Auditor` sink; the Fluxtion docs explicitly invite custom backends — binary/db/streaming). Restart-recovery = rebuild processor + `YamlReplayRunner`-equivalent over the Chronicle log (+ snapshot, below). This is a *much smaller, better-aligned* change than re-architecting the feed-side cache. The `durableReplay` / `maxMessageHistorySize` design below applies to **that audit stream**. The unbounded-`ArrayList` fix still applies to the publisher's `eventLog`, but for its own smaller catch-up job — bound it, don't grow it into a replay store.

The replay cache today is an **unbounded `ArrayList<NamedFeedEvent>` on heap** inside `EventToQueuePublisher` (smell #5). For the startup-large-source scenario that is a memory bomb: caching millions of events to enable replay grows the heap without bound and adds GC pressure to the exact path we want deterministic. The fix is to move the replay log **off heap and behind an interface**, with Chronicle Queue as the default backend — *hidden from users*, exposed only as a couple of config knobs.

**Shape:**
- Extract a `ReplayLog` interface (append + replay-from-position + truncate/bound) from the publisher — this is the "extract caching" layering principle made concrete. `EventToQueuePublisher` calls `replayLog.append(...)` instead of `eventLog.add(...)`; `publishReplay`/`dispatchCachedEventLog` read from it.
- Default implementation = **Chronicle Queue**, memory-mapped off-heap, replay-by-index. Users never see Chronicle in their config or API — it's an implementation detail of the runtime, the same way Agrona queues are. (We already depend on Chronicle via the mongoose-plugins connector, so no new external surface.)
- A trivial in-heap/no-op implementation stays available for tiny/ephemeral feeds and tests (monomorphic call site, JIT inlines it away when replay is off).

**Config knobs (the only user-visible surface):**
- **`durableReplay: true|false`** (default `false`). When `true`, the Chronicle log persists across restarts → restart-and-replay reconstructs full state from the on-disk log (Kafka-by-offset semantics, in-process). When `false`, the log is a roll-on-shutdown scratch file (off-heap, but not survivor of a restart) — solves the heap-growth problem without promising durability.
- **`maxMessageHistorySize`** (count or bytes; default bounded, *not* unbounded). Caps retained history; Chronicle roll-cycle / truncation enforces it. Past the bound, oldest records age out. This makes "replay the last N" cheap and bounds disk the way the queue bounds memory.
- These are likely **per-feed** (some feeds need full durable history, others want a small ring) but may also have a server-level default. Mirrors the per-subscriber-vs-global question for `SlowConsumerStrategy` (Q2).

**These two knobs conflict, and the conflict must be designed out, not left implicit.** `durableReplay: true` promises "reconstruct state on restart"; a bounded, aging-out `maxMessageHistorySize` means the oldest records are gone — so restart-replay reconstructs **last-N**, not full state. For an audit/ledger buyer, last-N is exactly the thing they can't accept. The two are only compatible via one of:
- **Unbounded-durable** — `durableReplay: true` forces `maxMessageHistorySize = unbounded` (the regulated posture; accept the disk cost). Reject the contradictory combination at config-validation time rather than silently truncating an "audit" log.
- **Durable-bounded + snapshots (compaction)** — pair the bounded tail log with periodic **state snapshots**, so recovery = `snapshot + replay(tail since snapshot)`. This is the only way to get bounded storage *and* full-state recovery, and it's the standard event-sourcing answer (Kafka log compaction, Chronicle + periodic dump). It also caps the **restart cost**: without snapshots, `durableReplay` means replaying *millions* of events on every boot — an obvious operational tax this doc otherwise ignores.

**Snapshots already exist in Fluxtion — reference, don't reinvent.** Processor-state checkpointing is a Fluxtion concern (`fluxtion/docs/.../state-and-recovery.md`); recovery = snapshot + replay-tail, where the tail is the audit log since the snapshot's position (i.e. `YamlReplayRunner.betweenTimes(...)` extended with a starting state). So this doc should **reference** that mechanism for the durable-bounded resolution, not specify a new one. The mongoose-side work is only: persist the per-processor audit stream durably (below) and key snapshots to positions in it.

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

    // A PURE decision — the strategy does NOT block. It returns how long
    // writeToQueue should wait before retrying. Sentinels keep it allocation-free:
    long SPIN  = 0L;    // retry immediately (Thread.onSpinWait)
    long DROP  = -1L;   // abandon the item
    // any value > 0  => park that many nanos, then retry (LockSupport.parkNanos)

    /** @return SPIN, DROP, or a positive park-nanos. Must not block or touch
     *  threads — keeps the policy a trivially-testable pure function, and keeps
     *  ALL blocking in writeToQueue, the one place the global invariant polices. */
    long onQueueFull(QueueBinding binding, int attempt, long firstFailNanos);

    default String id() { return getClass().getSimpleName(); }
}
```

> **Layering note (review):** an earlier sketch had the strategy `parkNanos()` internally and return `PARKED_RETRY`. That violates the doc's own mechanism/policy split (smell #1) — parking is a *mechanism* action. The pure-function form above keeps the strategy testable with no thread games and concentrates every blocking call in `writeToQueue`, which is exactly where the **global invariant** ("no unbounded wait on a shared agent loop") must be enforced and audited.

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
    if (tracker != null) tracker.acquireReference();                  // base ref — hoisted OUT of the loop
    try {
        while (true) {
            if (targetQueue.offer(itemToPublish)) return;             // FAST PATH — unchanged
            attempt++;
            if (firstFailNanos < 0) firstFailNanos = System.nanoTime();
            long wait = strategy.onQueueFull(binding, attempt, firstFailNanos);   // PURE decision
            if (wait == BackpressureStrategy.DROP) { binding.recordDrop(sequenceNumber); return; }
            else if (wait == BackpressureStrategy.SPIN) Thread.onSpinWait();
            else LockSupport.parkNanos(wait);                          // BLOCKING lives here, not in the strategy
        }
    } catch (Throwable t) {
        // ... existing CRITICAL error-report + QueuePublishException path ...
    } finally {
        if (tracker != null) tracker.releaseReference();              // single release, success or abandon
    }
}
```

Notes:
- **`publishReplay` routes through the same `writeToQueue`** (fixing its current bare `offer()`), so replay inherits the configured strategy automatically.
- **Pool-ref churn fixed (review):** the per-attempt `acquireReference()`/`releaseReference()` is gone — the base ownership ref is taken once before the loop and released once in `finally`. Under a long `BLOCK` of many park slices the old form did acquire/release per slice for no benefit (the base ref is what pins the slot; Q1a). This also makes the pool-slot accounting honest: exactly one slot pinned per in-flight item, for its whole lifetime.
- **Queue-depth caution (review):** `QueueBinding.size()` may be O(n)/approximate on Agrona queues — it is for telemetry and *coarse* strategy decisions, **not** to be called on every failed spin. Strategies key off `attempt`/`firstFailNanos`, not per-iteration `size()`.

### The strategies

- **`BestEffortDropStrategy` (default singleton, == today's behaviour made explicit):** return `SPIN` while `now - firstFailNanos <= maxSpinNs`; else `recordDrop` + `WARNING` and return `DROP`. `maxSpinNs` is now config (section B), defaulting to 10 ms.
- **`BlockStrategy` (pure function; the unsafe-topology handling depends on safety mode, section A):**
  ```java
  public long onQueueFull(QueueBinding b, int attempt, long firstFailNanos) {
      if (b.drainsOnCurrentThread()) {        // would deadlock (boot check missed it, or a dynamic edge created it)
          if (STRICT) throw new UnsafeBlockTopologyException(b);   // STRICT_LOSSLESS: fail, never drop
          b.recordSameThreadDegrade();        // SAFE_DEGRADE: counted (part of attestation surface), logged loudly
          return DROP;
      }
      if (!b.isLive()) { b.recordUndeployDrop(); return DROP; }    // undeploy mid-park — counted, NOT silent
      if (b.parkedProducersForPool() >= b.poolParkCap()) {         // Q1a pool-slot cap
          if (STRICT) throw new PoolCapExceededException(b);
          b.recordPoolCapDrop(); return DROP;
      }
      return parkSliceNs;                     // pure: return the wait; writeToQueue does the actual park
  }
  ```
  The three guards — `drainsOnCurrentThread`, `isLive`, pool-slot-cap (Q1a) — are what make `BLOCK` safe under dynamic deployment. **Under `STRICT_LOSSLESS` they reject (throw / fail the subscription) rather than drop**, preserving the lossless contract; under `SAFE_DEGRADE` they drop but **every degrade path is counted** (`recordSameThreadDegrade` / `recordUndeployDrop` / `recordPoolCapDrop`) so the drops are visible and feed attestation (section D) — none is a silent drop on a "lossless" feed.
- **`DisconnectStrategy`:** on sustained overflow (Q3 trigger — *not* a single failed offer), unsubscribe the queue (remove the `NamedQueue` via the existing `removeTargetQueueByName`) + error event.
- **`EXIT_PROCESS` is not a strategy here** — it's the escalation tier (section A). The per-queue strategy set is `{BEST_EFFORT_DROP, DISCONNECT, BLOCK}`; process-exit is a separate deployment-level rule layered above `DISCONNECT`.

### How dynamic deployment is handled (the load-bearing part)

1. **Strategy + binding are resolved at *attach*, not at boot.** `EventToQueuePublisher.addTargetQueue(...)` gains the source's configured `SlowConsumerStrategy` and a `QueueBinding`. The single caller that wires a subscriber queue to both a publisher and a draining group is **`EventFlowManager`** — and *runtime* subscriptions flow through the same path as boot ones. So a processor deployed into a running container gets its strategy + same-thread context wired identically; there is no separate dynamic code path to keep in sync.
2. **Draining thread is a supplier, not a snapshot.** `QueueBinding.drainsOnCurrentThread()` reads the draining `ComposingEventProcessorAgentRunner.thread()` lazily. **`groupRunner().thread()` returns `null` until the group starts** (confirmed: `addEventProcessor` on a started server starts it on demand, `MongooseServer` ~:782), so the guard must treat *null draining thread* as a distinct case — "not yet started, cannot be the current thread" → safe to proceed — rather than NPE. This is *why* lazy-supplier is mandatory, not optional. The single seam that wires the binding for both boot and runtime subscription is **`EventFlowManager.getMappingAgent()`** (it calls `addTargetQueue`) — name it explicitly so the implementer touches the right method, not a generic "EventFlowManager wires it".
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
- **Re-entrant *self*-republication is NOT a `writeToQueue`/`BLOCK` concern.** When a handler re-publishes during its own cycle it calls `getContext().processAsNewEventCycle(...)` — this lands in the **generated event processor's own internal queue** (`DataFlowContext`), drained after the current cycle on the same thread. It never traverses mongoose's dispatch layer. The generated processor *carries its own queue*, so same-processor re-entrancy is already solved one layer down (exercised by `ReEntrantHandler`/`ReEntrantTest`). This is the key correction: the self-loop case is handled by the processor, not the bus. **Caveat:** this rests on Fluxtion-internal `processAsNewEventCycle` behaviour in *generated* code, which is not in this repo's source — it's consistent with the `ReEntrantHandler`/`ReEntrantTest` evidence but is an assumption about generated code, not locally verifiable here. Confirm against the Fluxtion runtime before relying on it as a hard guarantee.
- **The remaining `BLOCK` deadlock case is cross-processor on a shared agent thread**: publisher processor P1 and subscriber processor P2 sit in the **same agent group** (one thread). P1 blocking inside `writeToQueue` starves the shared `ComposingEventProcessorAgent` loop, so P2 never drains the queue P1 is waiting on → deadlock. The generated processor's internal queue does **not** help here — it's a *different* processor. (Plus any future "publish inline on the consumer's agent thread" path.)
- **No explicit `Thread.currentThread()` same-thread check exists in the dispatch layer today.** The de-facto defence for the cross-thread case is structural (Agrona SPSC: one writer thread + one reader thread), and the spin-then-drop timeout is what currently prevents the same-thread case from hanging — it drops instead of deadlocking.

**So a same-thread guard is needed on the `BLOCK` path, but only for the cross-processor-same-group case** — self-loops are already absorbed by the generated processor. Recommended: before parking, compare the current thread against the target queue's draining-agent thread; if they match, do **not** block — fall back to one of: (i) stage into the source's `pending` queue and let `doWork` drain it next cycle (the existing decoupling mechanism), (ii) drop + count + `WARNING` (degrade to `BACKOFF` rather than deadlock). This requires the target queue to expose its draining-agent identity to the publisher (a small wiring addition in `EventFlowManager`). An alternative to a runtime guard: a **boot-time topology rule** — reject (or auto-decouple onto its own agent) any `BLOCK` feed whose subscriber shares an agent group with the publisher, so the unsafe case can't be configured in the first place.

### Q1a — `BLOCK` + bounded pool = a second, *cross-thread* deadlock vector

The thread-sharing deadlock above is not the only one. Events are `PoolAware`, ref-counted from a **bounded** object pool. A producer parked in `BLOCK` is holding an in-flight event — it owns a pool slot for the entire (unbounded) duration of the park. Now consider many producers parked in `BLOCK` across *different* threads (so the same-thread guard never fires): collectively they pin `maxBlockedProducers` pool slots. If a **consumer** needs to acquire/recycle a pooled instance to make forward progress (e.g. to construct its own output event) and the pool is drained by parked producers, the consumer stalls — and the stalled consumer is exactly what would have drained the queues the producers are blocked on. **Deadlock by resource exhaustion, with no thread sharing at all.** The ref-churn smell (#3) noticed the per-attempt ref traffic; this is the stronger interaction it points at.

Requirements this imposes on the `BLOCK` design:
- **Does `BLOCK` hold the pooled ref while parked?** As sketched, the per-*attempt* ref is released after each failed `offer()`, but the *item itself* is still owned by the producer (not yet published, not returned to pool) for the whole park — so yes, it pins a slot. State this explicitly.
- **Pool sizing must tolerate `capacity + maxBlockedProducers`** per pool, or `BLOCK` can self-deadlock under load. Either size the pool accordingly, or cap the number of concurrently-parked producers per pool and degrade the overflow (drop/disconnect) past the cap.
- This is another instance of the **global invariant** (no unbounded wait on a shared resource): bound the park *and* bound the pinned-slot count.

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

**The clean crystallisation:** *a `BLOCK` feed must publish from a thread that none of its subscribers drain on.* Agent-hosted sources already satisfy this unless a subscriber is wrongly colocated in the source's own group (a trivial set-intersection to check). The only structurally exposed case is the **inline pipe** — so the targeted fix is: when a pipe's strategy is `BLOCK`, route `HandlerPipe.forward` through `offer()`/`doWork` (staged, on the pipe's own agent thread) instead of `publishNow` (inline on the publisher's thread). That moves the publish off both the publisher's and subscriber's threads, after which the residual boot check is just "no subscriber shares the pipe's own agent group."

**Important — boot validation is necessary but NOT sufficient.** The codebase supports adding processors and subscriptions *after* boot (`addEventProcessor` on a started server, the 128-slot lifecycle queues, `queueReadersToAdd`). A processor subscribing at runtime can colocate with a `BLOCK` publisher's thread *after* the boot check has passed. So the static check is an **optimisation** (catch the common case early, give a clean error or auto-decouple), while the **guarantee** lives at runtime: the `BlockStrategy` same-thread guard (Q1) plus the pool-slot cap (Q1a). Pick the posture explicitly — either (a) BLOCK-feed topology is *frozen at boot* (reject any runtime subscription that would touch a BLOCK feed), or (b) runtime subscriptions are allowed and the runtime guards carry the safety. This doc assumes (b); the dynamic-deployment section below makes it concrete. Do **not** describe the boot check alone as making this "provable" — it doesn't, once hot deploy is in play.
- **Q2** — Should `SlowConsumerStrategy` be per-feed only, or also per-subscriber (one slow processor shouldn't force drop policy on a fast one sharing the feed)? The queues are already per-subscriber (`subscriberKeyToQueueMap`), so per-subscriber is feasible.
- **Q3** — `DISCONNECT` trigger (must not fire on a single failed `offer()` — transient contention would disconnect healthy consumers). Proposed: trigger on **sustained** overflow — queue-full duration `> slowConsumerDisconnectAfter` (e.g. 1s) **or** depth above `slowConsumerHighWaterMark` (e.g. 0.9) for `slowConsumerWindow` consecutive samples (e.g. 10). All three configurable. `EXIT_PROCESS` is the escalation tier above this, not a peer (section A).
- **Q4** — Do we reuse `RetryPolicy`'s backoff schedule (initial/max/multiplier) as the shape of a `BACKOFF` wait, or keep them fully separate? Reusing the *math* (not the semantics) avoids a second backoff config.
- **Q5 — this is a *live bug today*, not just adjacent.** `RetryPolicy.backoff()` does `Thread.sleep` on the shared agent loop, so a single retrying processor already head-of-line-blocks every co-hosted feed/processor — independent of any backpressure work. It is the *same root cause* as the `BLOCK` same-thread hazard: an unbounded blocking wait on a shared duty-cycle thread (the **global invariant** in the header). Fix direction: move processing-retry off the hot loop (defer + requeue with backoff as a scheduled wake, not a sleep). Promote from "out of scope" to "must fix; same family as BLOCK."
- **Q6 — unified with C.2 (see Startup replay).** A depth-driven (high/low-water) policy and credit-based replay are the same mechanism reading the same `sampleQueueDepths()` signal. Decision is not "should we do Q6" vs "C.2" but "do we build depth-driven credit at all" — if yes, it serves steady-state and replay together.
- **Q7** — Replay log (section E): the **named conflict** is `durableReplay: true` (full-state recovery) vs a bounded aging-out `maxMessageHistorySize` (last-N only) — resolve via *unbounded-durable* or *durable-bounded + snapshots/compaction* (section E). Open sub-questions: snapshot cadence + format (Fluxtion processor-state dump keyed to a log position?); is `maxMessageHistorySize` messages or bytes; per-feed or server-default; on restart, replay *all* before live or interleave; and the `PoolAware` serialisation detail (Chronicle write needs a detached copy, as the heap cache does via `String.valueOf`).
- **Q8** — Dynamic deployment policy: when a *runtime* subscription would create an unsafe `BLOCK` edge, the response is now governed by the **safety mode** (section A): `STRICT_LOSSLESS` → **refuse** the subscription; `SAFE_DEGRADE` → auto-decouple or accept-and-degrade (counted). Note auto-decouple no longer threatens replay determinism — that's consumer-side now, so a staging hop changes only live timing, not the replayed record. Remaining sub-question: under `SAFE_DEGRADE`, prefer auto-decouple (preserves losslessness by moving the publish to its own thread) over accept-and-degrade (drops) — auto-decouple should be the `SAFE_DEGRADE` default, with an audit signal.

---

## Proposed phasing (straw man)

0. **Replay contract — RESOLVED (consumer-side audit-replay).** Deterministic replay is captured at the **consumer** via an `Auditor` (`YamlReplayRecordWriter` pattern) recording the post-merge `ReplayRecord` stream + data-driven time, and re-driven via `YamlReplayRunner`. **No global cross-feed order key is needed**; replay does not go through feeds/queues/dispatch. This decouples *faithful reconstruction* (guaranteed by the audit record, policy-independent) from *losslessness* (the separate thing `BLOCK` provides). Remaining task here is not a data-model decision but plumbing: provide a mongoose **audit-replay-writer** (Chronicle-backed, section E) and a replay-drive path that feeds a processor's recorded stream into a fresh instance.
1. **Stop the silent bleed** — `publishReplay` honours `offer()` with **bounded-spin + drop-counter ONLY (no block)**. Smallest correctness win; makes loss visible and bounded. ⚠️ Do *not* introduce blocking here — blocking needs the same-thread guard (phase 3/5) and pool cap (Q1a); a blocking replay on the source's own drain thread deadlocks with no escape. Lossless replay is phase 5, after the guards exist.
2. **Extract policy + cache as injectables** — pull a `BackpressureStrategy` and a `ReplayLog` interface out of `EventToQueuePublisher` (layering principles). No behaviour change; this is the refactor that makes 3–6 clean instead of more enums threaded through the hot path.
3. **Wire `SlowConsumerStrategy`** — `writeToQueue` consults the injected strategy; implement `BACKOFF` (= today) + `BLOCK`. Make capacity + spin-bound configurable. Default unchanged → no behaviour change unless opted in.
4. **Durable consumer-side audit replay** — Chronicle-backed `ReplayRecordWriter` (a durable `Auditor` sink, section E); `durableReplay` = persist the per-processor audit stream; reference Fluxtion snapshots for bounded+full recovery. Serialise detached copies of `PoolAware` events. *(This — not the feed cache — is the determinism/recovery artifact.)* Separately: bound `EventToQueuePublisher.eventLog` for its catch-up job.
5. **Late-subscriber catch-up over the bus** — the only place feed-side replay + `BLOCK` apply; implement the chosen catch-up regime over the (bounded) `eventLog`/`publishReplay`; resolve Q1 thread-safety per source. (Deterministic *recovery* needs nothing here — it's the audit path from phase 4.)
6. **`DISCONNECT` / `EXIT_PROCESS`** — implement the trigger condition (Q3) and the disconnect/exit actions.
7. **Lossless attestation** — boot-time **config** validation (every feed BLOCK/DISCONNECT, durable history unbounded/snapshotted) + admin/audit signal. Key on config, not the drop counter (hook D); the drop count is corroborating telemetry only.

---

## Appendix — implementation contracts (from review)

**Required counters** (telemetry + attestation surface): `publish.offer.success`, `publish.offer.retry`, `publish.drop.live`, `publish.drop.replay` (*distinct from live — a replay/catch-up drop is more severe*), `publish.block.park.count`, `publish.block.park.nanos`, `publish.block.sameThreadUnsafe`, `publish.block.undeployDrop`, `publish.block.poolSlotCapExceeded`, `subscriber.disconnect.slowConsumer`, `queue.depth.current`, `queue.depth.highWaterMark`. The `block.*Unsafe`/`*Drop` counters are part of the **attestation** surface under `SAFE_DEGRADE` (section D), not mere telemetry.

**Replay/catch-up drops are first-class failures, not warnings.** On a catch-up `publishReplay` drop: increment `publish.drop.replay`, **emit an `ErrorEvent`** (feed, queue, sequence/index, depth), and **mark the catch-up incomplete** — optionally abort. "Drop and continue" is usually worse than "fail loudly" for priming. (Recovery via the audit path can't suffer this — it bypasses queues.)

**Each phase needs explicit "done means" acceptance criteria + a test.** Minimum test set: (1) `publishReplay` full-queue → drop is *visible* (counter + error event); (2) default strategy still bounded-spins then drops; (3) `SlowConsumerStrategy` wiring reaches the publisher; (4) `BLOCK` cross-thread success — full queue eventually drains, zero drops; (5) `BLOCK` same-thread — `STRICT` rejects / `SAFE_DEGRADE` counts-and-degrades; (6) dynamic subscription post-start is validated/guarded; (7) pool-exhaustion — parked-producer cap prevents total drain (Q1a); (8) consumer-side audit replay reproduces a run bit-identically incl. data-driven time; (9) `DISCONNECT` sustained-overflow removes only the slow subscriber; (10) attestation — lossless config passes, any lossy strategy fails, runtime degrade invalidates.

**`EXIT_PROCESS` migration:** keep accepted on the existing enum for v1 source-compat; when configured, map to `DISCONNECT` + an `EXIT_PROCESS` escalation rule; mark **deprecated** on the per-feed surface; a future version moves process-exit to a separate `SlowConsumerEscalationPolicy`.

**This doc is large — recommend splitting at implementation time** into: (1) *backpressure-and-slow-consumer-handling* (SlowConsumerStrategy, queue-full policy, publishReplay fix, configurable capacity/spin); (2) *replay-and-durable-history* (consumer-side audit-writer, Chronicle, `durableReplay`/`maxMessageHistorySize`, snapshot reference); (3) *deterministic-replay-contract* (consumer-side event sourcing, completeness boundary, integration constraint); (4) *agent-thread-blocking-and-retry* (the global invariant, `RetryPolicy.backoff` Q5, deferred retry). Keep this doc as the unifying problem-framing; spin the four out as each phase starts.

## Files in scope

- `dispatch/EventToQueuePublisher.java` — `writeToQueue` (spin/drop), `publishReplay` (silent drop), `eventLog` (unbounded heap cache); target of the `BackpressureStrategy` + `ReplayLog` extraction.
- *(new)* `dispatch/BackpressureStrategy` — injected flow-control policy (extracted from `writeToQueue`).
- *(new)* **Chronicle-backed `ReplayRecordWriter`** — a durable `Auditor` sink for the **consumer-side** audit stream (the determinism/recovery artifact); `durableReplay` / `maxMessageHistorySize`; detached-copy serialisation of `PoolAware` events. Refs: `fluxtion/.../replay/YamlReplayRecordWriter.java`, `YamlReplayRunner.java`, `docs/how-to/replay-functionality.md`, `docs/.../state-and-recovery.md`.
- `dispatch/EventToQueuePublisher.eventLog` — re-scoped to **late-subscriber catch-up**; bound it (don't grow into a replay store).
- `dispatch/EventFlowManager.java` — queue construction / capacity (1024).
- `dispatch/RetryPolicy.java` — error-retry policy (consumer side); possible backoff-math reuse.
- `dutycycle/EventQueueToEventProcessorAgent.java` — where `RetryPolicy` is applied; agent-thread `backoff` sleep.
- `service/EventSource.java` — `SlowConsumerStrategy` enum + `setSlowConsumerStrategy`.
- `service/extension/AbstractEventSourceService.java` — stores the (currently dead) strategy field.
- `config/EventFeedConfig.java`, `config/HandlerPipeConfig.java` — config surfaces that plumb the strategy.
- Source implementations to audit for thread-safety of `BLOCK` (Q1): `connector/memory/InMemoryEventSource.java`, `connector/file/FileEventSource.java`, Chronicle/Kafka/Aeron connectors in mongoose-plugins.
