# Spec 02: order-path visibility for processors that publish orders

Status: owner decisions recorded 2026-10-28 (D1 open); ready for design review. Baseline Mongoose 1.1.1. Builds on `docs/design/overload-policy.md`
(#79), section 11 "Proposed: a processor's defined reaction to a downgraded or refused outbound publish".

## 1. Problem

The client's strategies produce orders inside processors. A processor publishes through an injected
`MessageSink` (a `HandlerPipe` sink) on its group's agent thread. Mongoose 1.1 rules that agent threads never
wait (`overload-policy.md` section 6), so an outbound publish from a processor into a full queue is **downgraded to
`DROP_AFTER_SPIN`** and counted in `downgradedCount()`. The publish call returns `void`
(`EventToQueuePublisher.publish`, `HandlerPipe` sink), so the strategy is not told.

Not producing an order because something failed is safe. Believing an order was sent when it was dropped is not:
the strategy's working-order and position view diverges from the venue's. The latch, health and kill switch report
the loss to operators but do not tell the strategy that made the decision.

## 1a. Infrastructure or application code?

Both, with a clear line:

| Part | Owner | Why |
| --- | --- | --- |
| Knowing an item was dropped (`publishChecked` result) | **Mongoose infrastructure** | Only the publisher knows which queues refused the item; application code cannot observe it today |
| Delivering outbound overload state into the processor (`OutboundOverloadState` event) | **Mongoose infrastructure** | The state machine lives in `EventToQueuePublisher`; the event must arrive on the processor's own agent thread inside an audited cycle |
| Labels, audit join, admin status | **Mongoose infrastructure** | Evidence must be uniform across all processors |
| What a strategy does about it (pull quotes, block new orders, reconcile, alarm) | **Application code** (the strategy) | Trading policy differs per strategy; Mongoose must not decide it |
| A reusable "stop quoting on LATCHED" helper node | **Optional shared library** (mongoose-plugins or the client's common module) | Saves every strategy writing the same handler; not core |

So Mongoose provides the facts and delivers them; the strategy decides the reaction. Without the infrastructure
part, no amount of application code can see a downgraded drop.

## 2. Goals

- G1 A processor can know, in the same cycle, that an outbound item was not delivered to every target queue.
- G2 A strategy receives overload state of its outbound queues as an event in its graph, so it can stop quoting.
- G3 Per-strategy monitoring and diagnostics: counters and audit evidence labelled with the strategy.
- G4 No allocation and one branch on the default path when the queue has room.
- G5 No waiting on an agent thread (keep the 1.1 rule).

## 3. Design

### 3.1 Publish result

Add to the sink a result-returning publish, keeping `void publish` unchanged:

```java
PublishResult publishChecked(T item);   // DELIVERED, DROPPED(queues), REFUSED(reason)
```

- `PublishResult` is a preallocated per-sink object (no allocation), valid until the next call on that sink in the
  same cycle.
- `DROPPED` lists the queues that did not receive the item and whether the drop was a downgrade.
- The default `publish` delegates and ignores the result; existing code is unchanged.

### 3.2 Outbound overload state as an input event

Every processor that publishes to feed F receives the event; one that declares a handler reacts to it:

```java
@OnEventHandler
public boolean onOutboundState(OutboundOverloadState state) { ... } // feed, queue, UP|DEGRADED|LATCHED, lossCount
```

- Mongoose delivers `OutboundOverloadState` to **every** processor that publishes to the queue (owner decision D2),
  on each state change of any queue its sink publishes to, on the processor's own agent thread, as a normal event cycle (so it is audited).
- The state comes from the existing per-queue `OverloadState` machine (`EventToQueuePublisher.changeState`), so
  there is one source of truth.
- A strategy's expected reaction (pull quotes, block new orders, alarm) is strategy code, documented with an
  example.

### 3.3 Sizing guard for never-drop outbound feeds

For an outbound feed configured `BLOCK_WITH_DEADLINE` but published from agent threads (which can never block):

- Boot warns once: `feed 'orders' is never-drop but published from agent threads; drops are possible under load`.
- A config key `outboundCapacityCheck: <events/sec>` lets the operator state peak rate; boot **warns** (owner
  decision D3) if `capacity / peakRate` is below the spin bound's equivalent with a stated margin, naming the feed,
  capacity, rate and margin. It never fails boot. This is the written sizing
  argument from the earlier #77 spec, made executable.

### 3.4 Monitoring and diagnostics per strategy

- Counters gain a `processor` label for outbound drops and downgrades (`MongooseCountersService`).
- The loss audit record (`OverloadState`, `Dropped`) gains the publishing `group/name#generation`, so the analyser
  can place the dropped order beside the decision that produced it (needs spec 01's key).
- The admin status for a processor lists its outbound feeds and their state.

## 4. Alternatives considered

- Waiting on the agent thread for order feeds: rejected; it reintroduces cross-group stalls and deadlock risk that
  1.1 removed.
- Handing order publishes to a dedicated non-agent publisher thread that may block: workable as an opt-in later.
  It moves the problem (the strategy still needs to know the order is late), so 3.1 and 3.2 are needed anyway.

## 5. Tests

1. Capacity-1 outbound queue, stalled consumer: `publishChecked` returns `DROPPED` with the queue and downgrade flag;
   `publish` behaviour unchanged.
2. The publishing processor receives `OutboundOverloadState(LATCHED)` in a later cycle; the cycle is audited.
3. No allocation on the default path (allocation test as in 1.1.1's dispatch check).
4. Boot warnings for a never-drop feed published from agent threads, and for a stated peak rate the capacity cannot
   absorb; boot still succeeds.
5. Synthetic trading system: injected venue stall; the strategy pulls quotes on `LATCHED`; no order is believed
   sent that was dropped (reconcile strategy view against the venue simulator).

## 6. Sizes

Mongoose: publish result 2 days, outbound state event 3 days, sizing guard 1 day, labels and admin 2 days. About
8 days plus the synthetic-system scenario.

## 7. Owner decisions (2026-10-28)

- D1 **Open:** result-returning publish as a new method (recommended: no source break for existing plugins) or a
  change to `publish`'s return type. Infrastructure either way (section 1a).
- D2 **Decided:** outbound state is delivered to every publisher of the queue.
- D3 **Decided:** the capacity check warns; it never fails boot.
