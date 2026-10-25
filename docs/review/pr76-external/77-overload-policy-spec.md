# Issue #77: explicit overload and loss policy for the data path (design skeleton)

Status: skeleton for design review. Not contingent on the Fluxtion generator change; the two are independent.
Scope: Mongoose dispatch layer only. No generated-processor change.

## 1. Problem

The lifecycle contract is now explicit (PR #76). The loss contract on the data path is implicit. Today, in
`src/main/java/com/telamin/mongoose/dispatch/EventToQueuePublisher.java`:

- a publisher offers an item to each target queue; on a full queue it spins (`Thread.onSpinWait`) for at most
  10 ms (`maxSpinNs`, l.462);
- past the bound it drops the item **for that queue only**, increments `droppedCount` (l.478), releases the
  queue's pool reference, logs a warning when `logWarning` is set (l.483-485), and continues to the next queue;
- nothing propagates to the source; there is no backpressure;
- `EventSource.SlowConsumerStrategy` (`service/EventSource.java:24`: `DISCONNECT`, `EXIT_PROCESS`, `BACKOFF`) is
  settable from `EventFeedConfig` and `HandlerPipeConfig` and reaches `AbstractEventSourceService`, but the publish
  path never consults it; the default `BACKOFF` is a name, not a behaviour;
- at shutdown, the drain phase waits `shutdownDrainTimeoutMillis` (2 s) then drops whatever is still queued
  (`LifecycleManager.stopPhases`, logs the pending count).

The volume qualification deliberately exercised this: 341 queue copies / 682 processor deliveries dropped under
overload, all accounted for. For market data that is tolerable. For orders, fills, credit and hedge instructions
it is a correctness defect that no amount of lifecycle rigour compensates for.

## 2. Goals

- G1 Every feed and pipe has an explicit, configured loss policy, and the default is stated.
- G2 A "never drop" policy exists and is enforceable for order-class events.
- G3 Every drop or block is observable: counters, health, an audit record the analyser can correlate with
  lifecycle events.
- G4 No new latency on the hot path when the queue is not full (one branch, no allocation).
- G5 Shutdown drain semantics are consistent with the per-feed policy.
- G6 Pooled events are released exactly once on every path (drop, block then publish, block then abort).

## 3. Non-goals

- Cross-process backpressure to an external venue or client. The policy ends at the source's own publish call.
- Changing queue implementations or capacities by default.
- Replay semantics for dropped events (a drop is recorded as a `Failed`-class fact if the processor is recorded;
  see 7.4).

## 4. Policy options (per feed or pipe)

| Policy | Behaviour on full queue | Loss | Latency effect | Use |
| --- | --- | --- | --- | --- |
| `DROP_AFTER_SPIN` (today) | spin ≤ bound, then drop for that queue | yes, per queue | bounded publisher stall | market data, telemetry |
| `BLOCK` | spin then park/yield until space; never drop | none | unbounded publisher stall; propagates to the source thread | orders, fills, credit, hedge instructions |
| `BLOCK_WITH_DEADLINE` | block up to a configured bound, then **fail the publish** (exception to the source, not a silent drop) | none silently | bounded | order paths whose source can handle a refused publish |
| `DISCONNECT` / `EXIT_PROCESS` | honour the existing enum names: close the source, or exit, on first overflow | none after the event | terminal | kill-switch deployments |

**DECISION 1**: which of `BLOCK` and `BLOCK_WITH_DEADLINE` ships first. Recommendation: both, since `BLOCK` is
`BLOCK_WITH_DEADLINE` with an infinite bound, and the deadline variant is what an order connector actually wants.

**DECISION 2**: the default. Recommendation: keep `DROP_AFTER_SPIN` as the default for compatibility, log at boot
every feed/pipe that uses the default without an explicit setting, and make the client configuration explicit for
every feed.

**DECISION 3**: fate of `SlowConsumerStrategy`. Recommendation: reuse the enum, add the two new values, and make
the publisher honour it; `BACKOFF` becomes an alias of `DROP_AFTER_SPIN` with a deprecation note, since that is
what it has always done.

## 5. Per-event-class policy for the client

Written as a table in the client's configuration review, not in Mongoose docs:

| Event class | Policy | Rationale |
| --- | --- | --- |
| Market data (quotes, trades, book updates) | `DROP_AFTER_SPIN` | superseded by the next tick; loss costs staleness, not money |
| Reference data | `BLOCK` | low rate; must land |
| Orders, order acks, fills, cancels | `BLOCK_WITH_DEADLINE` or `BLOCK` | never drop; a refused publish must be surfaced to the connector |
| Credit and limit updates | `BLOCK` | never drop |
| Hedge instructions | `BLOCK` | never drop |
| Admin and control commands | `BLOCK` | already bounded by the control plane |

Fallback if #77 is not delivered before the client release: a written sizing argument per never-drop feed
showing the 10 ms spin bound cannot be reached at peak observed rates with the configured queue capacity.

## 6. Configuration shape

```yaml
eventFeeds:
  - name: orders
    slowConsumerStrategy: BLOCK_WITH_DEADLINE
    blockDeadlineMillis: 250          # only for BLOCK_WITH_DEADLINE
    spinBoundMillis: 10               # only for DROP_AFTER_SPIN; default 10
```

Same keys on `HandlerPipeConfig`. Programmatic: `EventFeedConfig.Builder.slowConsumerStrategy(...)`,
`.blockDeadline(Duration)`, `.spinBound(Duration)`. Unknown or missing values fail boot with the feed named.

## 7. Behaviour details

7.1 **Hot path.** `offer` succeeds: no change. Only the full-queue branch consults the policy. The policy is a
final field on the publisher, resolved at construction.

7.2 **BLOCK.** Spin for the spin bound, then `LockSupport.parkNanos` with a small step, re-offering each wake. The
publishing thread is the source's thread; blocking it is the intended backpressure. Never block an agent thread
of a processor group: if the publisher runs on one (a processor publishing to a pipe), fall back to
`BLOCK_WITH_DEADLINE` with the group's configured deadline and record the downgrade once.

7.3 **BLOCK_WITH_DEADLINE.** As BLOCK up to the deadline, then throw `PublishRefusedException(feed, queue, waited)`
to the caller. The pooled reference is released. The caller decides (retry, reject the order upstream, alarm).

7.4 **Observability.** Per queue: `drops`, `blocks`, `blockedNanosTotal`, `blockedNanosMax`, `refusals` on
`MongooseCountersService` with the existing label conventions; health service marks a feed `DEGRADED` on the
first drop or refusal and `UP` again when the operator clears it or after a configurable quiet period
(**DECISION 4**). One structured log line per state change, not per event. One fallback-style audit record on
the owning group's control audit sink on first drop/refusal per queue per minute, so the analyser can place loss
beside lifecycle events. For a recorded processor, a drop on its input queue appends a `Failed` entry naming the
feed and sequence so replay stops at the loss rather than diverging silently.

7.5 **Shutdown drain.** The drain phase respects the policy: `DROP_AFTER_SPIN` feeds may be cut at the drain
deadline as today; `BLOCK*` feeds extend the drain up to the per-feed deadline and are reported in
`StopResult.groupsRunning` as `<feed F draining: N queued>` if still non-empty, never silently dropped.
Document in the shutdown runbook.

7.6 **Pooled events.** The existing invariant holds: the queue's reference is acquired before the offer and
released on drop or refusal; the publisher's own reference is released by the publisher. Add a test per policy
that a pooled item is returned exactly once under drop, under refusal, and under block-then-publish.

## 8. Tests

- Unit: each policy on a capacity-1 queue with a stalled consumer: drop counted and logged once; block waits and
  publishes; deadline refuses with the exact exception and releases the pool reference.
- Agent-thread guard: a processor publishing to a full pipe under `BLOCK` is downgraded and the downgrade recorded.
- Ordering and exactly-once for delivered events under every policy (extend `HighVolumeEventStressTest`'s
  `assertExactlyOnce`).
- Drain: a `BLOCK` feed with queued items at shutdown is reported, not dropped.
- Observability: counters, health transition, audit record presence asserted.
- Config: YAML round trip for every key; boot fails on an unknown value naming the feed.

## 9. Rollout

1. Land the enum extension and publisher policy behind the existing default (no behaviour change for existing
   configurations).
2. Land observability.
3. Client configuration review using the section 5 table; set explicit policies for every feed.
4. Run the volume stress with orders on `BLOCK` and market data on `DROP_AFTER_SPIN`; assert zero order drops.
5. Update contracts "Overload and loss" section and the shutdown runbook.

## 10. Open decisions (owner: product)

- D1 ship `BLOCK` and `BLOCK_WITH_DEADLINE` together.
- D2 default remains `DROP_AFTER_SPIN`; boot-time warning for implicit defaults.
- D3 reuse `SlowConsumerStrategy`.
- D4 health recovery rule after a drop.
- D5 whether a refused publish on an order feed should also trigger a configurable kill-switch (`DISCONNECT`).
