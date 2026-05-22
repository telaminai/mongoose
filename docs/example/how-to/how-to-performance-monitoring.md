# How to enable performance monitoring

Mongoose ships an opt-in performance-monitoring layer that exposes live counters for every feed, agent group, processor,
and (with one extra annotation) every node inside a processor. The counters are published through
`MongooseCountersService` and visible in real time on the **`svc-admin-web`** browser console — no extra plugin, no
external metrics backend required.

This guide covers the three layers, in increasing order of detail:

1. **Enable counters** (YAML one-liner). Surfaces feed / agent-group / queue depth rates.
2. **Bind a `PerformanceMonitorAudit`** to a processor (one line of builder code). Adds per-processor + per-node fire counts.
3. **Read counters programmatically** for export to Prometheus / OTLP / your own dashboard.

A working end-to-end example lives at
[`mongoose-examples/how-to/performance-monitoring`](https://github.com/telaminai/mongoose-examples/tree/main/how-to/performance-monitoring).

---

## Why this exists

Counters live behind a published service interface so other plugins (`svc-admin-web` today,
future `svc-prometheus` / `svc-otlp`) all read the same surface. Two implementations switch at JVM boot:

- **No-op** (default) — every method returns a shared sentinel with empty bodies. Call sites are monomorphic,
  the JIT inlines the `increment()` to nothing. Zero residual cost. This is what runs when you haven't asked for
  performance monitoring.
- **Agrona-backed** — counters live in an on-heap `UnsafeBuffer` managed by Agrona's `CountersManager`. Allocation
  happens once at registration, the fast path is a single volatile add. Sub-nanosecond per increment.

The toggle is YAML-level, JVM-wide, picked at boot. Both impls share a callsite per JVM lifetime so monomorphism
(and the no-op-inlines-to-nothing argument) is preserved.

---

## 1. Enable counters via YAML

Add this top-level block to your Mongoose server config:

```yaml
performanceMonitoring:
  enabled: true
  counterBufferKb: 256   # optional; default 256 → ~2048 counters
```

That's it. No tag is needed — SnakeYAML auto-binds against the typed field on `MongooseServerConfig`.

`counterBufferKb` sizes the on-heap values buffer; each counter occupies 128 bytes, so the default 256 KB yields
~2048 counters. The minimum is clamped to 16 KB at the impl.

### What gets counted automatically

Built-in counter sites, all populated by `EventFlowManager`'s hot paths the moment you flip the YAML key:

| Label | Where it's written | What it means |
| --- | --- | --- |
| `feed.{name}.published` | `EventToQueuePublisher.publish(...)` | Every event a feed pushes into the dispatch pipeline. |
| `group.{name}.processed` | `ComposingEventProcessorAgent.doWork()` when work > 0 | Each agent-loop iteration that found events to dispatch. |
| `group.{name}.idleCycles` | same loop when work == 0 | Idle iterations; inverse of throughput, useful for backpressure visibility. |
| `queue.{path}.depth` | sampler-side, once per tick | Per-subscriber dispatch queue depth (`producerPosition − consumerPosition`). |

### Viewing the result

Boot the server, then point a browser at the `svc-admin-web` console (default `http://127.0.0.1:8181/`).

- **Dashboard** — new "Throughput" card with Feeds / Agent groups / Processors / Queue depth tables. Names link
  to the per-entity detail pages.
- **Services view** — new `Rate` column on the row table. Live values for feed-type services.
- **Agents view** — rate tag on each agent-card head, beside the thread-state pill.
- **Topology view** — feed and group nodes pulse green when their rate ticks above zero in the latest sample window.

When `performanceMonitoring.enabled` is `false` (or absent), the console surfaces an honest "monitoring is off" hint
on the Dashboard with the YAML key to flip, rather than silently hiding the card.

---

## 2. Bind a `PerformanceMonitorAudit` for per-processor + per-node counters

The built-in counters answer "is the pipeline alive?". Per-node fire counts answer **"which Fluxtion node fired
how often?"** — a level of detail no other JVM streaming framework's admin UI can show, because nobody else
compiles to a per-node-introspectable artefact.

It's a single line in your Fluxtion builder lambda:

```java
import com.telamin.fluxtion.Fluxtion;
import com.telamin.mongoose.service.counters.PerformanceMonitorAudit;

DataFlow flow = Fluxtion.compile(cfg -> {
    cfg.addNode(new PriceHandler(),    "priceHandler");
    cfg.addNode(new PnlAggregator(),   "pnlAggregator");
    cfg.addAuditor(new PerformanceMonitorAudit("priceCalc"), "perfMon");
});
```

No binding → no extra bytecode in the generated SEP → zero overhead, ever. With binding, two more counter
groups appear:

| Label | What it means |
| --- | --- |
| `processor.{processorName}.events` | Bumped on each `eventReceived` callback into the SEP. |
| `node.{processorName}.{nodeName}.invocations` | Bumped on each per-node dispatch. |

The processor name (`"priceCalc"` above) is the label prefix — choose it carefully; once a deployment is in
production the dashboard groups results by this name.

### Runtime toggle

The auditor exposes `setWriteEnabled(boolean)` for runtime suppression:

```java
PerformanceMonitorAudit audit = new PerformanceMonitorAudit("priceCalc");
cfg.addAuditor(audit, "perfMon");
// ...
// elsewhere, at runtime, after some condition:
audit.setWriteEnabled(false);   // counter writes stop; SPI callbacks still fire
```

The toggle short-circuits the counter writes with a predictable branch. When the global counters service is the
no-op anyway, the JIT eliminates the call regardless.

### Viewing per-node counts

Per-node counts show up in two places once the auditor is bound:

- **Dashboard "Throughput" card** — a "Per-node fire counts" hint linking to the Topology view.
- **Agents view → group → processor sub-detail** — a "Per-node invocations" table with rate + total per node.
  This is the moat-exhibit screen: every node in the generated SEP, named, with its live invocation count.

---

## 3. Reading counters programmatically

For custom dashboards, exporters, or tests, inject `MongooseCountersService` via `@ServiceRegistered`:

```java
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.mongoose.service.counters.MongooseCountersService;

public class MyExporter {
    private MongooseCountersService counters;

    @ServiceRegistered
    public void countersService(MongooseCountersService svc, String name) {
        this.counters = svc;
    }

    public void dump() {
        if (!counters.isOperational()) {
            System.out.println("performance monitoring disabled");
            return;
        }
        counters.forEachCounter((id, label, value) ->
                System.out.printf("%-50s %d%n", label, value));
    }
}
```

Key points:

- `isOperational()` distinguishes the no-op impl from the Agrona-backed one. Plugins that depend on real counter
  values should consult this before treating a zero read as meaningful.
- `forEachCounter` is allocation-free per visit. The reader walks the counters buffer directly — safe to call
  on a 1 Hz sampler tick without GC concerns.
- Counter labels follow flat dotted conventions documented in section 1's table. Parse by prefix to bucket
  feeds vs groups vs processors vs nodes vs queues.

For hot-path counter writes from your own code (e.g. a custom EventSource), grab the handle once and cache it:

```java
private MongooseCounter publishCounter;

@ServiceRegistered
public void countersService(MongooseCountersService svc, String name) {
    this.publishCounter = svc.feedPublishCounter("my-custom-feed");
}

public void onMessage(MyEvent e) {
    publishCounter.increment();   // sub-nanosecond when no-op; ~10ns when real
    // ...rest of publish path
}
```

The pattern is **allocate once, cache the reference, increment forever** — never look up the counter on the
hot path.

---

## Configuration reference

The full block on `MongooseServerConfig`:

| Field | Default | Notes |
| --- | --- | --- |
| `performanceMonitoring.enabled` | `false` | When `false` (or block absent), the no-op service is installed — JIT inlines counter writes to nothing. |
| `performanceMonitoring.counterBufferKb` | `256` | Values-buffer size in KB. ~2048 counters at default. Clamped to a minimum of 16 KB. |

To toggle at runtime, the JVM has to restart — the boot-time selection is load-bearing for the JIT-inlines-no-op
argument. Mid-life swap would re-introduce bimorphism at every call site and defeat the zero-overhead guarantee.
Per-auditor `setWriteEnabled` is the runtime knob for fine-grained control.

---

## Related

- [`PerformanceMonitorAudit`](https://javadoc.io/doc/com.telamin/mongoose/latest/com/telamin/mongoose/service/counters/PerformanceMonitorAudit.html) — API reference.
- [`MongooseCountersService`](https://javadoc.io/doc/com.telamin/mongoose/latest/com/telamin/mongoose/service/counters/MongooseCountersService.html) — counters surface.
- [`svc-admin-web`](https://github.com/telaminai/mongoose-plugins/tree/main/service/svc-admin-web) — the admin UI that reads the throughput payload.
- Design doc: `mongoose/design-doc/mongoose-counters-and-performance-monitor.md` — full motivation, phasing, and acceptance checklist.
