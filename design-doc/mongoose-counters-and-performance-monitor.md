# Mongoose counters service + performance-monitor auditor

**Status**: Phases 1–4 shipped on `develop` (counters live, hot-path sites instrumented, auditor available, admin Dashboard card visible). Phase 4.5 (health verdict) and Phase 5 (admin commands) remain. See per-phase checklist below for granular state.
**Owners**: Greg
**Drives**: live throughput on svc-admin-web (✓ visible), future Prometheus / OTLP exporters, per-node fire counts for the topology view.

**Shipped commits** (mongoose `develop` unless noted):
- `7c15d23` — Phase 1: `MongooseCountersService` + `MongooseCounter` + No-op/Agrona impls + YAML toggle + EFM wiring.
- `e95b58b` — Phase 2: feed-publish counter on `EventToQueuePublisher`, agent processed/idle counters on `ComposingEventProcessorAgent`.
- `3d3f1c5` — Phase 3: `PerformanceMonitorAudit` + 5 SPI tests.
- `8dce91f` — `EventFlowManager.sampleQueueDepths()` for the Phase 4 queue-depth gauge.
- `cc916c3` (mongoose-plugins `develop`) — Phase 4: svc-admin-web sampler + `/ws/monitor` payload extension + Dashboard "Throughput" card + 5 sampler tests.

**Test totals**: mongoose-core 164/164 green · svc-admin-web 79/79 green.

---

## Motivation

Right now Mongoose's admin surface (`svc-admin-web`) shows JVM health and a static dispatch topology, but nothing about **whether the pipeline is actually doing work**. An operator sees the graph and the heap but can't answer "is the FX feed firing? is anything backed up?". Adding this matters for three independent reasons:

1. **Operations** — a heartbeat signal is table-stakes for any pipeline runtime.
2. **Demo / sales** — `svc-admin-web`'s Topology view rendering live event-flow against a running server is the screenshot that lands the "same artefact in playground → production" story.
3. **Moat exhibit** — per-node fire counts via Fluxtion's audit listener is something **no competitor's admin surface can show**, because no competitor compiles to a per-node-introspectable artefact. It's the deterministic-replay narrative made tangible.

This work introduces:

- `MongooseCountersService` — published interface for fast-path counter writes + sampling reads.
- Two implementations: a **no-op** (default) and an **Agrona-backed real** one. Switched at JVM boot — call sites stay monomorphic so the JIT can inline the no-op away.
- `PerformanceMonitorAudit` — a Fluxtion `Auditor` that, when bound at processor build time, writes per-event and per-node counts into `MongooseCountersService`.
- Two layers of opt-in: **build-time** (does the auditor exist in the generated processor?) and **runtime** (is the service real, and is each auditor writing?).

---

## Architecture

### Service interface — single hot-path surface

```java
package com.telamin.mongoose.service.counters;

public interface MongooseCountersService {

    String SERVICE_NAME = "com.telamin.mongoose.service.counters.MongooseCountersService";

    // Allocate-once handles. Callers cache the reference for the
    // lifetime of the feed / group / processor / node.
    MongooseCounter feedPublishCounter(String feed);
    MongooseCounter agentEventsCounter(String group);
    MongooseCounter agentIdleCyclesCounter(String group);
    MongooseCounter queueDepthGauge(String path);
    MongooseCounter processorEventsCounter(String processor);
    MongooseCounter nodeInvocationCounter(String processor, String node);

    // Sampler-side walk. Called ~1 Hz from svc-admin-web /ws/monitor
    // and any other observability plugin. No allocation per visit.
    // No-op impl walks nothing — it tracks nothing to visit.
    void forEachCounter(CounterVisitor visitor);

    // True when the impl is real (Agrona-backed); false for the no-op.
    // Health-service checks consult this to decide whether to emit
    // UNKNOWN (counters disabled) vs evaluate against counter data.
    boolean isOperational();

    @FunctionalInterface
    interface CounterVisitor {
        void visit(int id, String label, long value);
    }
}

public interface MongooseCounter {
    long increment();         // monotonic
    long incrementRelease();  // release semantics
    void setOrdered(long v);  // gauge write
    long get();               // read
}
```

**Counter abstraction — own type, not Agrona's.** The interface returns a mongoose-owned `MongooseCounter`, not `org.agrona.concurrent.status.AtomicCounter`. Two reasons: (1) the JIT-monomorphism claim is what carries the no-op-is-free argument, and it applies at the `MongooseCounter` callsite level — `NoOpCounter.increment()` is `{}`, the JIT inlines it to nothing; (2) decouples the public service contract from Agrona's API so we can swap the backing store later (off-heap, JFR, OTLP shim) without breaking callers. The real impl wraps Agrona's `AtomicCounter`; the no-op is a singleton final class with empty methods.

Counter labels are flat strings of the form `feed.fx-market-data.published`, `group.priceCalculator.processed`, `queue./agent/priceCalculator/eventQueue.depth`, `processor.priceCalc.events`, `node.priceCalc.FxLineHandler.invocations`. Categorisable from the label prefix — no parallel enum.

### Two implementations

Both impls live in `com.telamin.mongoose.internal` — only the interface in `com.telamin.mongoose.service.counters` is part of the published API surface.

**`NoOpCountersService`** — every method returns a singleton `NoOpCounter` (a final class) whose `increment()` / `setOrdered()` / `get()` are empty/zero. Singleton service instance, registered when performance monitoring is disabled. Because the implementation class is uniquely loaded across the JVM and **call sites are monomorphic**, the JIT inlines the no-op increment to nothing. C2's inlining heuristic + dead-store elimination give us zero residual cost. `forEachCounter` visits nothing — there's nothing to visit.

**`AgronaCountersService`** — wraps `org.agrona.concurrent.status.CountersManager` over an on-heap `UnsafeBuffer` (~256 KB; sizes thousands of counters). Each public method either allocates a new Agrona `AtomicCounter`, wraps it in an `AgronaCounter` adapter, caches the wrapper in a `ConcurrentHashMap<String, MongooseCounter>` keyed by label, or returns the cached handle. **Lookup happens once at registration, never on the fast path.** `forEachCounter` delegates to `CountersReader.forEach`, walking the buffer directly. Note: `CountersManager.allocate(label)` is **not thread-safe** — `ConcurrentHashMap.computeIfAbsent`'s callback must serialise via `synchronized` on the manager, or registration must be confined to a single thread (EFM-only, never user-code threads). Phase 1 takes the latter approach for simplicity; revisit if a plugin ever needs to register counters from a worker thread.

### Global toggle

One JVM-wide switch chooses the impl at boot:

```yaml
mongoose:
  performanceMonitoring:
    enabled: true        # default: false
    counterBufferKb: 256 # default: 256
```

The switch is also exposable as an admin command (`mongoose.perf.enable` / `disable`) — but **the switch only flips between the two singleton instances at startup**. Mid-life swap is a separate problem we're not solving today (would re-introduce bimorphism at every callsite). Mid-life toggling happens **per auditor** instead — see below.

### Wire-up in EventFlowManager

`EventFlowManager` owns the singleton implementation, registers it into the service registry at boot, and hands counter references to the hot-path components it already owns:

- `EventToQueuePublisher` — cache `feedPublishCounter(feedName)` at construction; `.increment()` in `publish(...)`.
- Agent main loop — cache `agentEventsCounter(group)` + `agentIdleCyclesCounter(group)` at agent start.
- Queue reader — gauge `queueDepthGauge(path)` set via `setOrdered` at the read site, or sampled by the sampler poking the underlying `RingBuffer`'s `producerPosition - consumerPosition`.

**Construction ordering — load-bearing.** The counters service is the **first** service constructed and registered by `MongooseServer` / `EventFlowManager`, before any feed / sink / processor / agent wiring. Hot-path components receive the counters-service reference at **construction time**, not via deferred `@ServiceRegistered` injection — that way `EventToQueuePublisher.publish(...)` can never fire before its counter handle is allocated. No nullable counter field, no inject-later path, no `if (counter != null)` checks in the publish loop. Plugins that aren't hot-path participants (admin UIs, exporters) continue to receive the service via `@ServiceRegistered` as normal.

Any other plugin / service that wants counters injects `MongooseCountersService` via `@ServiceRegistered` — same pattern as `AdminCommandRegistry`.

### PerformanceMonitorAudit

A standard Fluxtion `Auditor`, bound at processor build time (opt-in). Implements:

```java
public final class PerformanceMonitorAudit implements Auditor {

    private MongooseCountersService counters; // injected via @ServiceRegistered
    private AtomicCounter eventCounter;       // for this processor
    private final Map<String, AtomicCounter> nodeCounters = new HashMap<>();
    private volatile boolean writeEnabled = true; // per-auditor toggle
    private final String processorName;

    public PerformanceMonitorAudit(String processorName) {
        this.processorName = processorName;
    }

    @ServiceRegistered
    public void countersService(MongooseCountersService svc, String name) {
        this.counters = svc;
    }

    @Override
    public void init() {
        eventCounter = counters.processorEventsCounter(processorName);
    }

    @Override
    public void nodeRegistered(Object node, String nodeName) {
        nodeCounters.put(nodeName, counters.nodeInvocationCounter(processorName, nodeName));
    }

    @Override
    public void eventReceived(Object event) {
        if (writeEnabled) eventCounter.increment();
    }

    @Override
    public void nodeInvoked(Object node, String nodeName, String methodName, Object event) {
        if (writeEnabled) {
            AtomicCounter c = nodeCounters.get(nodeName);
            if (c != null) c.increment();
        }
    }

    @Override
    public boolean auditInvocations() { return true; } // need per-node callbacks

    public void setWriteEnabled(boolean enabled) { this.writeEnabled = enabled; }
}
```

**Build-time opt-in** — added to the processor builder:

```java
Fluxtion.compile(c -> {
    c.addNode(...);
    c.addAuditor(new PerformanceMonitorAudit("priceCalculator"), "perfMon");
});
```

No auditor → no extra bytecode in the generated SEP → zero overhead, ever.

**Runtime opt-in** — `setWriteEnabled(false)` short-circuits the increments. The `if (writeEnabled)` branch is a single volatile read + predictable branch (always-true or always-false in steady state), so the impact is minimal — and when the global service is the no-op anyway, the JIT eliminates the call regardless.

### Why the JIT inlines the no-op (the load-bearing claim)

C2 inlines virtual calls when the call site is monomorphic — i.e., the JVM has only ever seen one concrete implementation through that callsite. If `MongooseCountersService` resolves to **one and only one** impl per JVM lifetime, every `.feedPublishCounter().increment()` site is monomorphic. C2:

1. Inlines `feedPublishCounter` → returns a cached `AtomicCounter` field.
2. Inlines `.increment()`.
3. If the impl is `NoOpCountersService`, both bodies are empty / trivially side-effect-free → dead-store elimination drops the whole sequence.

The constraint this imposes on us: **don't load both impls into the same JVM**. The toggle picks one at boot and that's the only impl seen. Don't try to mid-life swap; don't try to make a "decorating" wrapper. One impl, one classloader, one JIT compilation per callsite.

This is the same technique SLF4J's NOP-binding uses to make `log.debug(...)` free when debug is off.

### Sampling path (svc-admin-web side)

`MonitoringSampler` gains a tick (existing 1 s timer): call `countersService.forEachCounter(...)` into a `Long2LongHashMap` snapshot. Diff against previous snapshot for rates. Push as JSON over the existing `/ws/monitor` payload:

```json
{
  "jvm": {...},
  "throughput": {
    "feeds":      [{"name":"fx-market-data", "rate": 12345, "total": 9876543}],
    "groups":     [{"name":"priceCalculator", "rate": 12000, "idle": 200}],
    "queues":     [{"path":"/agent/priceCalculator/eventQueue", "depth": 0}],
    "processors": [{"name":"priceCalc", "rate": 12000}],
    "nodes":      [{"processor":"priceCalc", "node":"FxLineHandler", "rate": 8000}]
  }
}
```

UI consumes via the existing Alpine WebSocket subscriber, surfaces:

- New "Throughput" card on Dashboard (sparkline per agent group).
- Numeric badges on Services (feed rows) and Agent cards.
- Pulse animation on Topology nodes where rate ticked.
- Per-node fire-count badges in the inline graphml panel (when `PerformanceMonitorAudit` is bound).

### Health service — thin layer on top of counters

Counters give the **numeric signal**; an operator wants the **verdict**: "is this service UP?". A separate small service exposes that verdict and reuses 80% of the counters infrastructure for the data.

```java
package com.telamin.mongoose.service.health;

public interface MongooseHealthService {

    // Many checks per service is normal — a Kafka feed might register
    // "connected", "lag", "deserialize-errors". Check name is free-form;
    // serviceName is the registry key for aggregation. registerCheck
    // returns the handle so domain code can opt the check in/out at
    // runtime — or fully remove it via close() when the dimension no
    // longer applies (e.g. a feed has been disconnected permanently).
    HealthCheckHandle registerCheck(String serviceName, String checkName, HealthCheck check);

    // Opt-in to the built-in liveness check. Services that tick (feeds,
    // periodic workers) call this once at registration so the liveness
    // check evaluates `lastTickEpoch` delta against `livenessWindowMs`.
    // Pure RPC services don't call it; the liveness check is skipped
    // for them rather than emitting a permanent UNKNOWN.
    void markTicking(String serviceName);

    // Per-check status (drill-down view).
    HealthStatus statusOfCheck(String serviceName, String checkName);

    // Aggregated per-service verdict — worst-of across every check
    // registered for that service. This is what the admin UI badge and
    // /api/health surface; statusOfCheck is for the detail drill-down.
    // Aggregation order: DOWN > DEGRADED > UNKNOWN > UP. Operator-
    // silenced checks (handle.setEnabled(false)) are excluded from the
    // rollup — see "Silenced checks" below.
    HealthStatus aggregatedVerdict(String serviceName);

    // Walk every check (drill-down) — visitor matches the counters idiom
    // so admin sampler code reads symmetrically across the two services.
    void forEachStatus(StatusVisitor visitor);

    // Per-service error sink — same handle-cached pattern counters use.
    // Service caches the returned reference at registration; the bounded
    // ring (~16 entries) holds the last-N errors as {message, class,
    // epochMs}. Throwable's stack is dropped; class + message only.
    // Backed by a MPSC ring (Agrona ManyToOneRingBuffer or equivalent)
    // since errors can arrive from any thread.
    ErrorSink errorSink(String serviceName);

    @FunctionalInterface
    interface HealthCheck {
        HealthStatus evaluate(HealthContext ctx);
    }

    @FunctionalInterface
    interface StatusVisitor {
        void visit(String serviceName, String checkName, HealthStatus status);
    }

    interface HealthCheckHandle extends AutoCloseable {
        void setEnabled(boolean enabled); // silence/unsilence; rollup excludes silenced
        boolean isEnabled();
        @Override void close();           // fully unregister; verdict drops this dimension
    }
    // NB: close() is the explicit-unregister method, not intended for
    // try-with-resources usage. The normal pattern is "registerCheck at
    // service start, hold the handle for the service's lifetime, never
    // close it". Use close() only when a dimension genuinely stops
    // applying (e.g. a feed has been permanently disconnected).

    /** Read-only view passed to a HealthCheck. Counters + last-errors for
     *  the service the check belongs to. The check shouldn't reach beyond
     *  its own service (no global counter walk) — keeps checks composable. */
    interface HealthContext {
        long counter(String label);              // 0 if absent
        long counterDelta(String label, long windowMs); // value(now) − value(now−windowMs)
        Iterable<ErrorRecord> recentErrors();    // newest-first, up to ring capacity
        long nowEpochMs();
    }

    interface ErrorSink {
        void record(String message, Throwable cause);
    }

    record ErrorRecord(long epochMs, String errorClass, String message) {}
}

public record HealthStatus(Verdict verdict, String reason, long asOfEpochMs) {
    public enum Verdict { UP, DEGRADED, DOWN, UNKNOWN }
    public static HealthStatus up()              { return new HealthStatus(Verdict.UP, null, System.currentTimeMillis()); }
    public static HealthStatus down(String why)  { return new HealthStatus(Verdict.DOWN, why, System.currentTimeMillis()); }
    public static HealthStatus degraded(String why) { return new HealthStatus(Verdict.DEGRADED, why, System.currentTimeMillis()); }
    public static HealthStatus unknown(String why)  { return new HealthStatus(Verdict.UNKNOWN, why, System.currentTimeMillis()); }
}
```

**Per-check vs per-service.** Each service can register multiple checks (e.g. `connected`, `lag`, `error-rate`). `statusOfCheck` returns one check's result; `aggregatedVerdict` returns the worst result for that service — `DOWN > DEGRADED > UNKNOWN > UP`. Admin UI badges and `/api/health` surface the aggregated verdict; the detail drill-down shows every individual check.

**Silenced checks.** When an operator silences a check via `handle.setEnabled(false)`, that check is **excluded from `aggregatedVerdict`** rather than reported as UNKNOWN. Rationale: silencing is an affirmative "ignore this dimension" decision — a k8s probe reading `/api/health` shouldn't see a non-healthy verdict because of a check the operator has chosen to suppress. The UI still surfaces silenced checks in the per-check drill-down with a distinct "silenced" badge, so operators can see exactly what's been muted. If every check on a service is silenced, the aggregated verdict falls back to UNKNOWN with reason "all checks silenced" — that's a real signal worth surfacing.

**Evaluation cadence.** `HealthCheck.evaluate(ctx)` is **lazy with a per-check cache** (~1 s by default, configurable per check). On a call to `statusOfCheck` or during a `forEachStatus` walk, the registry returns the cached `HealthStatus` if its `asOfEpochMs` is within the cache window; otherwise it re-evaluates and updates the cache. This keeps the admin UI cheap under rapid polling — `/ws/monitor` push at 1 Hz triggers at most one evaluation per check per second, regardless of how many subscribers are watching. Checks that need fresher data can request `cacheMs=0` (always re-evaluate) at registration; checks that are expensive to compute can request longer windows.

**Counter history for `counterDelta`.** `HealthContext.counterDelta(label, windowMs)` is backed by a **1-Hz ring of counter snapshots over the last 60 seconds**, maintained by the same sampler that pushes `/ws/monitor`. The ring is a small fixed structure (≈ counters × 60 × 8 bytes ≈ 2 MB at 4 K counters) co-owned with the counters service. Requests for `windowMs > 60_000` return `Long.MIN_VALUE`; callers should treat that as "history insufficient" and emit `UNKNOWN`, not synthesise a value. Requests for `windowMs ≤ 0` return `0`.

**Health when counters service is the no-op impl.** If the global toggle leaves `MongooseCountersService` as the no-op, every counter read returns 0 and every delta returns 0 — naive built-in checks would flip everything DOWN. The health service detects this at boot (single `isOperational()` accessor on `MongooseCountersService`, or impl-identity check) and **all counter-derived built-in checks degrade to `UNKNOWN`** with reason "counters disabled". Custom checks that depend on counter data should follow the same pattern via `ctx.countersOperational()`. User code that wants to assert "this deployment must have counters on" can fail fast at boot rather than at first check.

**What's free from the counters layer:**

- "Service ticked in last N seconds?" → counter delta over a window.
- Error / disconnect / retry counts → existing counters.
- Connected state → `0/1` gauge.
- Throughput / lag → already exposed.
- Auto-allocated **per-service counters at registration**: when a service is registered via `MongooseServer.registerService` (the universal registration path — covers event-flow services *and* pure RPC services like JDBC pools and caches), the server mints `service.{name}.lastTickEpoch`, `.eventsProcessed`, `.errors`, `.up`. `MongooseServer` flips `.up` to `1` in `startService` and `0` in `stopService` — services don't write to `.up` directly. **`up == 1` from the moment `startService(name)` returns to the moment `stopService(name)` is called; `0` before start and after stop.** Health checks read all four. Standard surface for every service for free, whether it's event-flow or RPC.

**What's new in the health layer:**

- The `HealthCheck` predicate registry.
- A small bounded **last-error ring** per service (8–16 entries; `Throwable` truncated to message + class). Counters can't hold strings; this is the only structural addition.
- A structured `HealthStatus` returned to consumers — admin UI badge, future Slack alerter, future `/healthz/detailed` endpoint.

**Built-in checks** (registered by EFM at boot, applied to every service):

- **Liveness** — DOWN if `lastTickEpoch` delta over the last `livenessWindowMs` is zero AND the service called `markTicking(name)` at registration. Non-ticking services skip this check (RPC-style services don't have a heartbeat — they respond when asked). The built-in liveness check uses a single **global** `livenessWindowMs` (default 30 s) — it's deliberately uniform and dumb. Services that need a service-specific window register their own staleness check via `registerCheck` (see the `staleness` example below); the built-in is the baseline, not the override path. `markTicking` is permanent for the JVM's lifetime; services that go quiet are diagnosed by liveness going DOWN, not by reverting the registration.
- **Error spike** — DEGRADED if `errors` delta over the window exceeds a threshold; DOWN if `up == 0`.
- **Connected** — convention-driven: if a service publishes `service.{name}.connected` (an explicit 0/1 gauge), this check reads it. `0` → DOWN, `1` → UP. Absent gauge → check skipped entirely (not even UNKNOWN — the service isn't claiming this dimension). The built-in is intentionally a single-gauge baseline. Services with multi-component connectivity (multi-broker Kafka, multi-replica DBs, multi-leg crosses) should leave the built-in to summarise via the rollup gauge and register their own custom check with the structured per-component breakdown.

User services add domain-specific checks via the same registry:

```java
@ServiceRegistered
public void healthService(MongooseHealthService h, String name) {
    h.registerCheck(name, "staleness",
        ctx -> ctx.counterDelta("feed." + name + ".published", 30_000) == 0
                ? HealthStatus.degraded("no events in 30s")
                : HealthStatus.up());
}
```

**Why this is a sibling service, not bolted onto counters:**

- Different read pattern — health is request/response ("give me current status"), counters are streaming/sampling.
- Different consumers — `svc-prometheus` wants raw counters, a future Slack alerter wants the structured verdict stream.
- User-defined checks are domain code, which doesn't belong in the counters interface.

**svc-admin-web surface (Phase 4.5):**

- Status badge per row in the Services list (`UP` / `DEGRADED` / `DOWN` / `UNKNOWN`) — shows the aggregated verdict.
- Service detail view expands to a per-check breakdown ("`connected`: UP · `lag`: DEGRADED — backlog 2.4k events"), sourced from `statusOfCheck`.
- "Last errors" panel in the service detail view, sourced from the per-service error ring.
- New navigation pill in the topbar — `12 UP · 1 DEGRADED · 0 DOWN` — clickable to filter Services view.
- `/api/health` — `200` when every aggregated verdict is UP or UNKNOWN, `503` if any is DOWN, `200` with `degraded: true` flag if any DEGRADED. JSON body lists per-service aggregated verdict + per-check breakdown. Shape is intentionally k8s-readiness-probe compatible: a probe configured with `failureThreshold: 1, path: /api/health` flips the pod NotReady the moment any service goes DOWN. Concrete shape:

```json
{
  "verdict": "DOWN",
  "asOfEpochMs": 1747923456789,
  "services": [
    {
      "name": "fx-feed",
      "verdict": "DOWN",
      "reason": "no events in 30s",
      "checks": [
        {"name": "liveness",  "verdict": "DOWN", "reason": "no events in 30s"},
        {"name": "connected", "verdict": "UP"},
        {"name": "staleness", "verdict": "DOWN", "reason": "no events in 30s", "silenced": false}
      ]
    },
    {
      "name": "pnl-cache",
      "verdict": "UP",
      "checks": [
        {"name": "size", "verdict": "UP"}
      ]
    }
  ]
}
```

---

## Phasing

Each phase is independently shippable and independently demoable. Stop after any phase if the next one slips.

### Phase 1 — Service + no-op + agrona impl (no consumers yet)

**Goal**: published `MongooseCountersService` interface, both impls, EFM wires the no-op by default and the real impl when the YAML flag is set. **No counters are incremented anywhere yet.** Pure plumbing.

- New package `com.telamin.mongoose.service.counters`.
- `MongooseCountersService` interface.
- `NoOpCountersService` impl.
- `AgronaCountersService` impl (with config-driven buffer size).
- `EventFlowManager` instantiates the chosen impl at boot, registers as a Service.
- YAML toggle in the main config.
- Unit tests: forEach over a small set of counters; no-op returns the same handle every call; real impl returns distinct handles per label and the same handle for repeated label lookups.

**Exit criterion**: a test that registers the service, fetches handles, increments them, and reads them back via `forEachCounter` — for both impls.

### Phase 2 — Built-in counter sites

**Goal**: the four built-in counter sites incrementing in hot paths. Still no UI.

- `EventToQueuePublisher` — `feedPublishCounter` per feed; increment in `publish`.
- Agent loop — `agentEventsCounter` + `agentIdleCyclesCounter` per group.
- Queue read site or sampler — `queueDepthGauge` per queue path.
- Unit tests: drive a small Mongoose harness, drain N events through a stub processor, assert counters land at N (real impl) and zero (no-op).

**Exit criterion**: an integration test that boots a real Mongoose with the FX P&L example, runs 1k events through, and `forEachCounter` returns sane values.

### Phase 3 — PerformanceMonitorAudit + builder integration

**Goal**: opt-in auditor that records per-processor / per-node counts.

- **Prerequisite**: verify the pinned Fluxtion `Auditor` SPI exposes `nodeRegistered(Object, String)`, `eventReceived(Object)`, and `nodeInvoked(Object, String, String, Object)` with these exact signatures. If anything diverges, file an upstream ticket and gate Phase 3 on it — don't paper over a signature drift in mongoose code.
- `PerformanceMonitorAudit` class.
- Convenience helper on the Mongoose builder side: `withPerformanceMonitor(processorName)`. This is a thin mongoose-side wrapper around Fluxtion's `c.addAuditor(new PerformanceMonitorAudit(name), "perfMon")` — the underlying Fluxtion API is what bakes the auditor into the generated SEP. The helper exists so mongoose users don't have to reach across into Fluxtion's builder API for the common case.
- Per-auditor `setWriteEnabled` toggle.
- Unit tests: build a tiny processor with the auditor bound, fire N events, assert per-node counters are populated. Re-run with `writeEnabled=false` and assert counters don't move.

**Exit criterion**: the existing fx-pnl-mongoose example documented in its README to optionally add `withPerformanceMonitor` and see per-node counts.

### Phase 4 — svc-admin-web throughput consumer

**Goal**: the operator-facing payoff. svc-admin-web shows live throughput.

- Extend `MonitoringSampler` to read counters + diff against prev snapshot.
- Extend `/ws/monitor` payload with the `throughput` block.
- New Dashboard "Throughput" card.
- Badges on Services / Agents rows.
- Pulse animation on Topology nodes.
- Per-node fire-count overlay on the inline graphml panel (only visible when auditor counters are present for that processor).

**Exit criterion**: load the FX P&L example, browse to admin, see live counters incrementing on dashboard and topology.

### Phase 4.5 — Health service + admin UI verdict

**Goal**: turn the raw counter signal into an operator-facing UP / DEGRADED / DOWN verdict per service.

- New package `com.telamin.mongoose.service.health`.
- `MongooseHealthService` interface + `HealthStatus` record + `HealthCheck` functional interface.
- Default impl owned by EFM; registered into the service registry alongside `MongooseCountersService`.
- EFM auto-allocates `service.{name}.lastTickEpoch / .eventsProcessed / .errors / .up` counters at service registration time.
- Built-in checks (liveness, error spike, connected) registered at boot.
- Last-error ring per service (bounded, ~16 entries, **MPSC** ingest — errors arrive from any thread; Agrona `ManyToOneRingBuffer` or equivalent).
- `/api/health` Javalin endpoint in `svc-admin-web` returning the status table.
- Services view in svc-admin-web: status badge per row + topbar counter pill (`12 UP · 1 DEGRADED · 0 DOWN`).
- "Last errors" panel in the service detail view.
- Unit tests: register a stub service, advance time, assert liveness flips DOWN after the window; record an error, assert it shows up in the ring; build the verdict, assert reason string is correct.
- Integration test against the FX P&L example: kill the FX feed mid-run, watch the badge flip to DOWN within `livenessWindowMs`.

**Exit criterion**: in svc-admin-web, the Services list shows live status badges; the topbar pill aggregates the count; the service detail view shows the last 16 errors with timestamps; `/api/health` returns sane JSON that a k8s probe could consume.

**Cost estimate**: a full phase, no longer trivial. The data is mostly flowing through counters already, but the registry has real semantics: silencing-vs-UNKNOWN rules, lazy 1-s cache, all-silenced fallback, AutoCloseable handle semantics, the 60-s × 1-Hz counter-snapshot ring backing `counterDelta`, MPSC error ring, no-op-counters degradation handling, `markTicking` opt-in, and the `/api/health` JSON shape. None individually expensive; collectively a full phase. No new hot-path code, but the cold-path correctness surface is real and warrants careful unit tests around each semantic edge (silenced → excluded; all-silenced → UNKNOWN; counters-no-op → UNKNOWN; cache window honoured; window > 60 s → MIN_VALUE).

### Phase 5 — Admin command + per-auditor runtime toggle

**Goal**: ops surface.

- `mongoose.perf.list` — list known counters + current values.
- `mongoose.perf.auditor.enable {processor}` / `disable {processor}` — flip a specific auditor's `writeEnabled`.
- Surface these in svc-admin-web's Commands view (automatic — they appear because they're registered).

**Exit criterion**: from the admin UI, can pause per-node tracking for a noisy processor while keeping global feed/group counts live.

### Phase 6 (future, not in scope for this design)

- Latency histograms via HdrHistogram (separate allocation + contention story).
- Prometheus exporter plugin (`svc-prometheus`) consuming `MongooseCountersService`.
- OTLP exporter plugin.
- Persistent counter snapshots for crash diagnostics.

---

## README + example documentation plan

When Phase 3 lands, three README updates:

### `mongoose-core/README.md` — new "Performance monitoring" section

Short intro: counters service, two impls, JIT-inlines-when-disabled, YAML toggle. Code snippet showing the YAML enable + the builder `withPerformanceMonitor`.

### `mongoose-plugins/service/svc-admin-web/README.md` — extension to existing "What you get"

After the Dashboard / Commands / Console / Logs bullets, a new bullet:

> **Throughput** (when `mongoose.performanceMonitoring.enabled: true`) — live event rates per feed, agent group, processor, and (with `PerformanceMonitorAudit` bound) per node. Topology view pulses nodes as they fire.

Screenshot: `docs/screenshots/throughput.png`.

### `mongoose-examples/fx-pnl-mongoose/README.md` — new "Observing the pipeline" section

Walk-through of enabling perf monitoring on the existing example. Two-line YAML change + one-line builder change. Screenshot of the resulting svc-admin-web view with rates ticking.

### When Phase 4.5 lands — `mongoose-plugins/service/svc-admin-web/README.md` extension

After the Throughput bullet, add:

> **Health** — aggregated UP / DEGRADED / DOWN verdict per service, sourced from `MongooseHealthService`. Topbar pill summarises the fleet; Services view shows per-row badges; service detail expands to per-check breakdown + last 16 errors. `/api/health` mirrors the same data in k8s-readiness-probe shape (`200` healthy, `503` any DOWN).

Screenshot: `docs/screenshots/health.png`. Update the HTTP/WebSocket surface table with the `/api/health` row.

---

## Open questions

1. **Counter buffer location** — on-heap UnsafeBuffer or off-heap DirectByteBuffer? On-heap is simpler and adequate for this scope; off-heap matters only if we ever want a separate process reading the same buffer (Aeron's pattern). **Default: on-heap. Revisit if/when we want out-of-process monitoring.**
2. **Counter cardinality limit** — what happens if user code registers 10k labels via repeated unique strings (a label-explosion bug)? `CountersManager` has a maxCounters parameter; we should set a sane default (~4096) and log a warning before the cap hits.
3. **Counter persistence across restarts** — currently counters reset to zero on restart. Acceptable for v1. Persistent counters are a Phase 6 concern.
4. **Should the auditor's `writeEnabled` flag also be a no-op-impl swap?** No. Per-auditor swap re-introduces bimorphism (multiple processors holding different impls). The branch is fine.
5. **Sampler rate** — 1 Hz default for `/ws/monitor`. Operators may want 100 ms for debugging or 10 s for cluster summaries. Already configurable via `metricsIntervalMs`; counter pulls inherit it.
6. **Naming** — `MongooseCountersService` vs `MongooseMetricsService` vs `MongooseTelemetryService`. The Agrona world calls these "counters" (matching `CountersManager`); telemetry/metrics often imply latency + traces. **Default: counters. We can add a separate `MongooseLatencyService` later if/when histograms land.**
7. **Health window default** — `livenessWindowMs` default (5 s? 30 s?) depends on the slowest legitimate tick interval across services. Need a sane default that doesn't false-alarm idle services. **Default: 30 s, global to the built-in liveness check. The built-in is deliberately uniform — services that need a different window register their own staleness check via `registerCheck` rather than overriding the built-in.** Keeps the built-in dumb and predictable; pushes the policy decision to domain code where it belongs.
8. **What counts as "should tick"?** — pure RPC services don't tick, they respond. The liveness check should opt-in via a `ticking=true` flag at service registration, otherwise it returns UNKNOWN rather than DOWN. **Default: opt-in; non-ticking services emit UNKNOWN for liveness unless they register a custom check.**
9. **Per-check enable/disable parallel to `setWriteEnabled`?** — yes. `registerCheck` returns a `HealthCheckHandle` with `setEnabled(false)` (silence — UI still shows it with a "silenced" badge but the rollup excludes it) and `close()` (full unregister — verdict drops the dimension entirely). Silenced checks are excluded from `aggregatedVerdict` rather than reported as UNKNOWN — silencing is affirmative "ignore" intent, and a k8s probe shouldn't fail on a muted dimension. Surfaced as admin commands `mongoose.health.check.{enable,disable} {service} {check}` in Phase 5.
10. **Should `aggregatedVerdict` treat UNKNOWN as worse than UP?** Yes, UNKNOWN ranks between UP and DEGRADED in the *active* rollup. Rationale: an UNKNOWN says "we tried, but couldn't establish state" — a real signal that's masked if it gets rolled up as UP. The /api/health response treats UNKNOWN as 200-with-warning (not 503) so probes don't false-alarm during startup. Silenced checks don't participate in the rollup at all (see #9).
11. **Check evaluation cadence** — lazy with a ~1 s per-check cache (configurable via the registration call). Re-evaluation happens on access only, not on a background timer. Keeps cost predictable: a 100 Hz admin-UI poll still triggers at most 1 Hz of `evaluate()` calls per check. Checks needing fresher data can request `cacheMs=0`.

---

## Acceptance checklist (grouped by phase)

### Phase 1 — Service + impls + boot toggle (shipped — `7c15d23`)
- [x] `MongooseCountersService` interface published from `com.telamin.mongoose.service.counters` with `SERVICE_NAME` constant + `isOperational()` accessor.
- [x] `MongooseCounter` interface (mongoose-owned, NOT `org.agrona.concurrent.status.AtomicCounter`).
- [x] `NoOpCountersService` + `NoOpCounter` in `com.telamin.mongoose.internal`. `forEachCounter` visits nothing. `isOperational() == false`.
- [x] `AgronaCountersService` + `AgronaCounter` adapter in `com.telamin.mongoose.internal`. Wraps `CountersManager`, label-keyed cache, single-threaded registration, `isOperational() == true`.
- [x] YAML toggle (`mongoose.performanceMonitoring.{enabled, counterBufferKb}`) wired into `MongooseServerConfig`.
- [x] `EventFlowManager` / `MongooseServer` selects + registers the impl at boot, **before** any feed/sink/processor wiring.
- [x] Both-impl unit tests: handle identity (no-op shared / agrona distinct-per-label / agrona same-on-repeat), forEach roundtrip, isOperational signal. *(14 tests in `NoOpCountersServiceTest`, `AgronaCountersServiceTest`, `CountersServiceWiringTest`)*
- [ ] JMH micro: < 1 ns/op overhead in no-op mode, < 10 ns/op in real-impl mode for a single increment. *(deferred — best authored alongside Phase 2 hot-path sites so the bench reflects a realistic callsite shape; not blocking)*

### Phase 2 — Built-in counter sites (shipped — `e95b58b` + `8dce91f`)
- [x] `EventToQueuePublisher` — `feedPublishCounter` cached at construction, incremented in `publish`.
- [x] Agent main loop — `agentEventsCounter` + `agentIdleCyclesCounter` per group.
- [x] Queue read path / sampler — `queueDepthGauge` per queue path. *(via `EventFlowManager.sampleQueueDepths()` called from svc-admin-web's pre-tick hook; the design's "sampler path" branch — `8dce91f`)*
- [x] Integration test through real server. *(`CountersHotPathIntegrationTest`, `AgentLoopCountersTest` — 4 tests)*

### Phase 3 — PerformanceMonitorAudit + builder integration (shipped — `3d3f1c5`)
- [x] Prerequisite check: Fluxtion `Auditor` SPI exposes `nodeRegistered(Object, String)`, `eventReceived(Object)`, `nodeInvoked(Object, String, String, Object)` against the pinned Fluxtion 0.9.34. *Confirmed.*
- [x] `PerformanceMonitorAudit` class — `com.telamin.mongoose.service.counters.PerformanceMonitorAudit`.
- [ ] ~~`withPerformanceMonitor(processorName)` mongoose-builder helper wrapping `c.addAuditor(...)`~~ *(intentionally dropped — mongoose-core deliberately doesn't depend on fluxtion-builder which is a build-time-only heavyweight. Users call the canonical `cfg.addAuditor(new PerformanceMonitorAudit("name"), "perfMon")` directly. The mongoose-builder-helpers plugin can host the sugar later if it proves useful.)*
- [x] Per-auditor `setWriteEnabled` toggle.
- [x] Per-node / per-event counters populated; `writeEnabled=false` zero-delta tests. *(5 SPI-level tests in `PerformanceMonitorAuditTest`. End-to-end Fluxtion-compile binding deferred to Phase 4 work against fx-pnl-mongoose — Fluxtion's interpret-path doesn't propagate `registerService` to `@ServiceRegistered` on auditors, so it'll only exercise meaningfully under the compiled SEP path which mongoose's normal service injection will hit.)*

### Phase 4 — svc-admin-web throughput consumer (shipped — `cc916c3` in mongoose-plugins)
- [x] Sampler diff loop reading `forEachCounter`. *(`MonitoringSampler.tickSnapshot()` computes deltas vs previous tick; first tick = 0 by contract.)*
- [x] `/ws/monitor` payload extended with `throughput` block. *(JvmSnapshot record gains a `Throughput throughput` field; null when counters service is the no-op.)*
- [x] Dashboard "Throughput" card. *(Visible only when `throughput` is non-null; falls back to honest "monitoring is off" hint when disabled. Shows Feeds / Agent groups / Processors / Queue depth as live tables.)*
- [ ] Per-row badges on Services / Agents views *(polish — data is already in `/ws/monitor` payload; deferred to a follow-on slice.)*
- [ ] Topology pulse animation on Cytoscape nodes when rate ticked *(polish — strategic demo piece; deferred to a follow-on slice.)*
- [ ] Per-node fire-count overlay on the inline graphml panel *(polish — `throughput.nodes` already carries the data; deferred to a follow-on slice.)*

### Phase 4.5 — Health service + admin UI verdict
- [ ] `MongooseHealthService` interface (with `HealthCheckHandle` extending `AutoCloseable`, `HealthContext`, `ErrorSink`, `StatusVisitor`, `markTicking`) + default impl + auto-allocated per-service counters.
- [ ] Verdict-rollup logic: aggregatedVerdict = worst-of across a service's *active* (non-silenced) checks; UNKNOWN ranked between UP and DEGRADED; all-silenced → UNKNOWN with reason.
- [ ] Per-check lazy evaluation with ~1 s cache (configurable, including `cacheMs=0` for always-fresh checks).
- [ ] Test: rapid `statusOfCheck` calls within the cache window invoke `evaluate()` once; after expiry, re-evaluates exactly once.
- [ ] `counterDelta` backed by a 1-Hz × 60-s snapshot ring; `windowMs > 60_000` returns `Long.MIN_VALUE`; tests for both in-window and over-window.
- [ ] Counter-no-op detection: when `MongooseCountersService` is the no-op impl, counter-derived built-in checks return UNKNOWN with reason "counters disabled" rather than synthesising DOWN.
- [ ] Per-service counters (`lastTickEpoch / eventsProcessed / errors / up`) auto-allocated by `MongooseServer.registerService` for every service (event-flow *and* RPC). `up` flipped by `startService` / `stopService`, not by service code.
- [ ] Last-error ring per service is **MPSC**-safe (Agrona `ManyToOneRingBuffer` or equivalent; never a single-producer structure).
- [ ] Built-in liveness / error-spike / connected checks registered at boot. Liveness only evaluates for services that called `markTicking`; absent → check skipped. `connected` reads `service.{name}.connected` by convention; missing gauge → check skipped.
- [ ] svc-admin-web `/api/health` endpoint (k8s-probe shape — `200` healthy, `503` DOWN-present, JSON body with per-service + per-check breakdown) + Services-view status badges + topbar verdict pill + per-check detail panel + last-errors panel.
- [ ] `svc-admin-web` README extended with the Health bullet (per Phase 4.5 plan above).

### Phase 5 — Admin commands
- [ ] `mongoose.perf.list` + `mongoose.perf.auditor.{enable,disable}` + `mongoose.health.check.{enable,disable}` registered.

### Documentation
- [ ] Three READMEs updated as per "documentation plan" above (mongoose-core, svc-admin-web, fx-pnl-mongoose).

---

## Notes for future conversations

- The JIT no-op claim is **the design's load-bearing performance argument**. If Phase 1 JMH shows it's not actually free (e.g., the indirection through the service registry breaks monomorphism), revisit before Phase 2. Without it, the case for default-disabled gets weaker and we should consider default-enabled with a smaller built-in counter set.
- The Auditor pattern (Phase 3) is **independent** of Phases 1–2. Phases 1–2 give us the operator dashboard. Phase 3 gives us the Fluxtion-specific moat exhibit. Either is shippable alone.
- Don't extend `MongooseIntrospectionService` for this — different lifecycle (continuous-write vs request-response), different freshness semantics. They're peers, not parent/child.
- Phase 4.5 (Health) is **also independent** of Phase 3 (Auditor) — health derives from Phase 1–2 counters and doesn't need per-node tracing. You can ship 1 → 2 → 4 → 4.5 and skip Phase 3 if the moat-demo work is deprioritised.
- `/api/health` in Phase 4.5 is intentionally shaped to be **k8s-readiness-probe compatible** (200 = healthy, 503 = unhealthy, JSON body with details). This makes the same surface usable for ops automation, not just the human UI.
