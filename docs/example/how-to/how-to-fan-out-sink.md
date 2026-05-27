# How-to: Fan out one sink write to many targets with FanOutSink

`FanOutSink` is a policy layer over N downstream sinks. A processor
writes once to the fan-out; the fan-out then forwards that write to
every configured target, governed by per-target failure / retry /
circuit-breaker policy.

It lives in `mongoose-plugins/library/lib-fanout` (artifact
`com.telamin:lib-fanout`).

## When to use it

- **Compliance**: every business write must also land in an audit
  sink. Operator-controlled, not application-controlled.
- **Cross-tier deployment**: send to a fast in-memory sink AND a
  durable file sink for replay-on-restart.
- **Migration**: send to old + new sinks in parallel, compare,
  cut over when confidence is high.
- **Best-effort fan-out**: one sink is critical, the rest are
  observability. CONTINUE policy + circuit-breaker isolates the
  noisy targets from the critical path.

## Quick start

```java
// Three downstream sinks named in server config — could be file,
// kafka, multicast, anything that registers a Service<MessageSink>.
FanOutSink fan = new FanOutSink();
fan.setTargetSinkNames(List.of("audit-file", "kafka-out", "metrics-sink"));
fan.setFailurePolicy(FanOutSink.FailurePolicy.CONTINUE);
fan.setCircuitOpenThreshold(5);
fan.setCircuitOpenMillis(30_000);

EventSinkConfig<?> fanCfg = EventSinkConfig.builder()
        .instance(fan)
        .name("trade-fanout")          // processors write to this name
        .build();

MongooseServerConfig.builder()
        .addEventSink(fanCfg)
        // … target sinks declared separately, by their own names …
        .build();
```

The processor consumes the fan-out sink like any other sink — no
awareness that it's a fan-out:

```java
@ServiceRegistered
public void wire(MessageSink<Trade> sink, String name) {
    if ("trade-fanout".equals(name)) this.sink = sink;
}

@OnEventHandler
public boolean onTrade(Trade t) {
    sink.accept(t);   // FanOutSink delivers this to audit-file +
                      // kafka-out + metrics-sink behind the scenes
    return true;
}
```

## Failure policies

`FanOutSink.FailurePolicy` controls how the fan-out reacts when one
target's `accept` throws:

| Policy | Behaviour |
|---|---|
| `CONTINUE` *(default)* | Log + continue with remaining targets. The fan-out itself never throws. Right for "one of these targets is critical, the rest are observability." |
| `FAIL_FAST` | Propagate the first exception. Right for "every target must succeed for this write to count." |
| `RETRY_THEN_DROP` | Retry the failing target N times (configurable via `retryAttempts`), then drop + log. Other targets receive normally. |

## Circuit-breaker

After `circuitOpenThreshold` consecutive failures on a target, the
fan-out stops attempting that target for `circuitOpenMillis`. Once
the window elapses, the next write attempts it again — success
resets the counter, failure refreshes the open window. Set
`circuitOpenThreshold = 0` to disable (target always tried).

This isolates one degraded target from slowing the fan-out hot path.

## Discovery + binding

Target sinks are discovered by name via
`@ServiceRegistered MessageSink onTargetSink(MessageSink, String name)` —
the standard Mongoose injection pattern. Order doesn't matter; bind
state is per-target. A target registered after boot (via the 1.0.18
runtime-add broadcast) binds automatically on first accept.

Unbound targets (configured in `targetSinkNames` but no service yet
registered with that name) are skipped silently — the fan-out
doesn't throw. The admin can introspect via
`FanOutSink.targetHealthSnapshot()` to see which targets are bound +
each one's bind state, delivery count, and circuit state.

## YAML equivalent

```yaml
eventSinks:
  - name: trade-fanout
    instance: !!com.telamin.mongoose.plugin.lib.fanout.FanOutSink
      targetSinkNames: [ audit-file, kafka-out, metrics-sink ]
      failurePolicy: CONTINUE
      circuitOpenThreshold: 5
      circuitOpenMillis: 30000

  - name: audit-file
    instance: !!com.telamin.mongoose.connector.file.FileMessageSink
      filename: trades-audit.log

  - name: kafka-out
    instance: !!com.example.KafkaTradeSink { brokers: ... }

  - name: metrics-sink
    instance: !!com.example.MetricsSink {}
```

## What FanOutSink isn't

- **Not a router** — it sends every value to every target. For
  conditional routing (this trade to A, that trade to B) use a
  processor with multiple sink injections.
- **Not a load-balancer** — every target receives every value. Use
  Aeron MDC or a transport-level balancer for that.
- **Not a multiplexer onto one wire** — that's a sink implementation
  decision (e.g. multicast does it natively).

## Tests + reference

- Unit tests: `mongoose-plugins/library/lib-fanout/src/test/java/.../FanOutSinkTest.java`
  (9 cases — round-trip, CONTINUE policy, FAIL_FAST, circuit-breaker
  open + skip, retry-then-drop, late-arriving target, health
  snapshot, unconfigured-name filter)