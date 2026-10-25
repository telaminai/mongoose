# Overload and loss policy for the data path

Issue: [telaminai/mongoose#79](https://github.com/telaminai/mongoose/issues/79) (split from #77).
Status: milestones 1, 2 and 3 implemented on `feature/79-overload-policy`; milestone 4 (client configuration guide) proposed.
Scope: the Mongoose dispatch layer (`EventToQueuePublisher`, `EventFlowManager`) and feed configuration. No change
to generated processors, the control plane or the lifecycle phases.

Each item below is marked **implemented** or **proposed**.

## 1. Working decisions (owner to confirm)

These are the recommendations of the second review of PR #76 (`second-review.md`, "DECISION recommendations"). The
implementation follows them. The product owner has not yet confirmed them.

| # | Decision | Status |
| --- | --- | --- |
| D1 | Ship `BLOCK_WITH_DEADLINE` first. Unbounded `BLOCK` is not in this implementation. | implemented |
| D2 | The default stays `DROP_AFTER_SPIN`, byte for byte today's behaviour. A configured feed with no explicit policy gets a one-time boot warning. | implemented |
| D3 | A new `OverloadPolicy` type. `SlowConsumerStrategy.BACKOFF` maps to `DROP_AFTER_SPIN` with a warning. `DISCONNECT` and `EXIT_PROCESS` fail boot until they are implemented. | implemented |
| D4 | Never-drop feeds latch DEGRADED until an operator clears them. Drop-tolerant feeds recover after a quiet period. | implemented (milestone 3) |
| D5 | A kill-switch hook on loss from a never-drop feed: configurable, off by default. | implemented (milestone 3) |
| — | Dropped items keep their journal entry and sequence number. A recording marks the loss with a `Dropped` entry, so a replay names it. | implemented (milestone 3) |

## 2. Current behaviour (before #79)

Verified against `dfbfdd9` (`feature/control-plane`). Paths are under `src/main/java/com/telamin/mongoose/`.

- **One lock for the whole dispatch.** `publish` takes `publishLock` (`dispatch/EventToQueuePublisher.java:129`,
  `:188`) and offers the item to each target queue in turn (`dispatch`, `:450-470`). Every target queue is a
  single-producer `OneToOneConcurrentArrayQueue`, and the lock is what keeps one producer per queue.
- **Committed before dispatch.** The sequence number is consumed and the journal appended before the first offer
  (`:202-203`). An item dropped later has a journal entry and a number but was never delivered.
- **Spin, then drop for that queue.** On a full queue, `writeToQueue` spins for at most 10 ms (`maxSpinNs`, `:476`).
  Past the bound it counts a drop (`recordDrop`, `:492`), releases the queue's pooled reference (`:493-496`), logs a
  warning (`:497-501`) and goes on to the next queue. A write failure throws after a partial fan-out (`:507-521`).
- **One slow subscriber throttles the feed.** Offers are sequential under the lock, so every publish to a feed
  with one full subscriber takes up to 10 ms per full queue, for every publisher and every other subscriber.
- **The newest item is dropped**, and the stale ones stay queued.
- **Loss evidence (PR #76).** Drops are counted per binding (`:546-564`): there is a per-queue drop counter, a
  health check per (feed, subscriber) that reports DEGRADED on the first drop and never recovers
  (`dispatch/EventFlowManager.java:330-342`), and a first-drop `QueueDrop` audit record on the publisher and on the
  subscribing group's control audit (`:560-563`). `droppedCount()` sums over all queues (`:139`, `:441`).
- **`SlowConsumerStrategy` is a name, not a behaviour.** It is deprecated in `service/EventSource.java:24-26`. It is
  settable from `config/EventFeedConfig.java:65,110` and `config/HandlerPipeConfig.java:100,176`, and stored in
  `service/extension/AbstractEventSourceService.java:83,220-222`. No code reads it.
- **Unsubscribe waits for the lock.** The unsubscribe action (`dispatch/EventFlowManager.java:405-416`) calls
  `removeTargetQueueByName`, which takes `publishLock` (`dispatch/EventToQueuePublisher.java:580-597`, lock at `:590`).
  It runs on the subscribing group's thread.
- **Shutdown phases.** Phase 2 closes the feed agent groups and then runs the `feeds:stop` helper, which calls
  `EventFlowManager.stop` (`internal/LifecycleManager.java:361-398`, helper at `:390`). Phase 3 drains, with a 2 s
  default bound, and drops whatever is still queued (`:400-414`). Phase 4 closes the processor groups (`:416`).
  `pendingQueuedEvents()` is global, not per feed (`dispatch/EventFlowManager.java:207-213`).
- **Pooled items.** The publisher's own reference is released in `finally`, after the dispatch
  (`dispatch/EventToQueuePublisher.java:212-236`). A release that takes the count to zero returns the item. A later
  `acquireReference` then fails with "already returned" (`service/pool/impl/PoolTracker.java:72-73`). Once the pool
  reissues the item, a late release corrupts the new holder's count (ABA, `:87-91`).
- **Agent threads** are marked by both group types (`control/AgentThreads.java:31`). `ControlDriver` already refuses
  to wait on one (`control/ControlDriver.java:174`).

## 3. Policies

| Policy | On a full queue | Loss | Who may wait | Status |
| --- | --- | --- | --- | --- |
| `DROP_AFTER_SPIN` (default) | Spin for the spin bound (10 ms by default, configurable, may be 0) while holding the lock, then drop for that queue only, count it and carry on. | Yes, per queue, counted. | Nobody waits past the spin. | implemented |
| `BLOCK_WITH_DEADLINE` | Wait up to the deadline, **without the lock**, until every blocking queue has room. Then deliver to all of them, or refuse the publish with `PublishRefusedException` and deliver to none. | None silent for a live publish from a non-agent thread: a refusal is thrown to the caller and counted. **Counted drops** in three cases: a publish from an agent thread, or nested in a dispatch, when a queue is full (section 6); and the replay of a pre-start cached event log at `startComplete`, which never waits (warned at start, and at boot for a pipe with `cacheEventLog`). | Non-agent threads only; see section 6. | implemented |
| `BLOCK` (unbounded) | As `BLOCK_WITH_DEADLINE`, with no deadline. | None. | Non-agent threads only. | proposed. Cancellation is already in place (section 7); what remains is the owner's decision. |
| `DISCONNECT` / `EXIT_PROCESS` | Kill switch on the first overflow. | n/a | n/a | Boot fails if configured (D3). Proposed as the D5 hook. |
| Conflation (latest wins per key) | Replace the queued item with the same key. | Superseded items only. | n/a | deferred |
| Event-class priority (cancels before new orders) | A separate lane, or reordering. | n/a | n/a | deferred |
| Spill to disk | Append to a local spill journal and replay it when room returns. | None. | n/a | deferred |

Never-drop venue session and health events (logon, logout, disconnect, kill switch) are a configuration choice:
put them on a `BLOCK_WITH_DEADLINE` feed, published from a non-agent thread. A dedicated session-event lane is
proposed alongside priority.

## 4. Fan-out semantics: all-or-nothing, waiting outside the lock

**Decision (implemented).** A `BLOCK_WITH_DEADLINE` publish is all-or-nothing across the blocking queues of the
feed:

1. Under `publishLock`, before anything is committed, check that every blocking binding has room. "Room" means one
   slot per binding that shares the physical queue (several callback types of one group share it), plus one slot per
   cached event-log item the publish dispatches first.
2. If every blocking binding has room, commit and dispatch as today: sequence number, journal, event log, offers.
   While the lock is held this publisher is the only producer of each queue, so free capacity can only grow and
   every blocking offer succeeds. Drop-policy bindings of the same feed still spin and drop independently.
3. If any blocking binding lacks room, **release the lock** and wait (spin for the spin bound, then park in steps of
   at most 100 µs). The wait ends early when the awaited queue gains room, when a subscriber or policy change wakes
   the waiter, or when the publisher closes. On each of those, retake the lock and go back to step 1.
4. At the deadline, make one last check under the lock, then refuse.

**Why not per-queue refusal.** Per-queue refusal leaves queues 0..i-1 holding the item. A caller that retries
duplicates it, which the second review rated HIGH (§4.4). Per-queue refusal would also need the lock held while
waiting, because the earlier offers are already committed.

**Why the wait is outside the lock.** It removes the review's deadlock (§4.2) by construction. A waiting publisher
holds no lock, so `removeTargetQueueByName`, `addTargetQueue`, `setOverload`, another publisher, and
`getEventLog` never wait for it. Nothing is committed until there is room, so releasing the lock cannot reorder a
partly delivered item. Publishes from one thread stay in order. Order between publishing threads was never defined.

**Consequences.**

- A refusal consumes no sequence number, writes no journal entry and leaves no queue holding the item. This fixes
  the "journalled but never delivered" gap for blocking feeds, but not for drop feeds.
- A waiting publisher can lose a freed slot to another publisher. It is bounded by its deadline.
- On a feed whose bindings are all `DROP_AFTER_SPIN`, nothing changes (section 9).

## 5. Pooled-item ownership on refusal

**Rule (implemented).** The publisher owns the reference that was handed over with the item for the whole wait.
That reference is released exactly once: by the dispatch's existing `finally` on success, or by the refusal path,
just before `PublishRefusedException` is thrown. On refusal no queue holds the item, so it goes back to its pool.

**The pool has reset the instance before the caller sees the exception**, so its payload is gone. A caller that
may retry must **copy the payload before publishing**. On refusal it acquires a fresh instance and fills it from
that copy. It must never publish the refused instance again: its tracker would report "already returned", or, once
the pool has reissued it, the retry would corrupt the new holder's item (ABA). `PublishRefusedException.item()` is a
`String.valueOf` taken before the release. It is never the instance.

Releasing to the pool is the owner's decision. It cannot leak: a caller that ignores the exception would otherwise
leak a pool slot on every refusal, and the pool would run dry. An opt-in mode that hands ownership back to the
caller is a **proposed** follow-up for order paths that want to retry the same instance.

## 6. Agent threads never wait

**Rule (implemented).** A publish waits only on a non-agent thread. Two cases use the binding's drop policy
(`DROP_AFTER_SPIN` with the binding's spin bound) for that publish, and are counted in `downgradedCount()` with a
warning once per publisher:

- An agent thread of any processor group or service agent group (`AgentThreads.isAgentThread()`).
- A publish nested inside **any** publisher's dispatch, through user code that dispatch runs under its lock: a data
  mapper, a journal codec, an item's `toString`, or an error listener. A static per-thread depth counter is raised
  around every locked publish path that can run user code. A nested publish cannot release the outer lock to wait,
  and waiting would hold it for the deadline. `Thread.holdsLock(publishLock)` only saw the same publisher (review of
  35bdeb2, finding 1).

The publish is counted as downgraded only when it would actually have waited, that is, when a blocking queue lacks
room. Agent-thread publishes that find room are not counted. The resulting drop is an ordinary counted drop. Its first-loss reason names the downgrade, for example
`queue full after 10 ms spin; BLOCK_WITH_DEADLINE not waited: agent thread <name>`. Cached event-log replays and
`publishReplay` keep their existing drop behaviour.

Lifecycle threads are not agent threads: the startup thread and the stop helpers may wait up to the deadline (a
stop cancels the wait in phase 2, section 7). This is bounded by the deadline but not excluded, unlike
`MongooseServer.controlCallerMayWait()`.

Consequence: a processor publishing into a `HandlerPipe` runs on its group's agent thread, so `BLOCK_WITH_DEADLINE`
on a pipe protects only its non-agent publishers. The defined reaction of a processor to a refusal is proposed in
§11.

## 7. Cancellation

| Event | Effect on a waiting publish | Status |
| --- | --- | --- |
| Unsubscribe or queue removal (`removeTargetQueueByName`) | The remove never waits for it. It wakes the waiter, which re-evaluates against the remaining targets. If they have room the item is delivered to them, as if published just after the unsubscribe. Otherwise it keeps waiting until the deadline. | implemented |
| Queue added, or policy changed | Wakes the waiter, which re-evaluates. | implemented |
| Shutdown | `EventFlowManager.stop()` (phase 2, before any source's `stop`) calls `cancelBlockedPublishes()` on every publisher. A waiting publish is refused at once with reason `SHUTDOWN`, and so is any later blocking publish that finds a full queue. A source whose `stop` joins its publishing thread therefore returns. `cancelBlockedPublishes()` never takes the lock. | implemented |
| Thread interrupt | Refused with reason `INTERRUPTED`; the interrupt status stays set. | implemented |
| Deadline | One last check under the lock, then refused with reason `DEADLINE`. | implemented |

**Departure from the issue text (owner confirmed):** on unsubscribe, the issue text asks for a refusal, and this implementation re-evaluates instead. A refusal there would protect nothing: the
subscriber has gone, so a retry would deliver to the same remaining subscribers. This is listed as an owner question.

The shutdown drain is unchanged. Blocking feeds do not extend it, because queue contents die with their group in
phase 4 (second review, §4.3). Reporting "feed F draining: N queued" needs per-feed queue accounting, which is
proposed.

## 8. Health, latch, counters and audit

| Signal | Status |
| --- | --- |
| Drops under any policy, including downgraded ones: the PR #76 per-queue counter, health check and first-drop `QueueDrop` audit | implemented (unchanged) |
| `refusedCount()`, `refusedCount(queue)`, `downgradedCount()` on the publisher; a warning on the first refusal per binding and the first downgrade per publisher | implemented |
| `MongooseCountersService` counters per queue, `queue.{path}.refused`, `.blocked` (waits, delivered or refused), `.blockedNanos` (total) and `.blockedMaxNanos`; per feed, `feed.{name}.downgraded` (publishes that would have waited) | implemented |
| Loss state per queue (`OverloadState`). A **never-drop** queue (`BLOCK_WITH_DEADLINE` binding) goes to `LATCHED` on a refusal, a downgraded drop or a dropped cache replay, and stays there until an operator clears it. A **drop-tolerant** queue goes to `DEGRADED` on a drop and returns to `UP` once the feed's `quietPeriodMillis` (default 60 s) passes with no further loss. The quiet-period recovery is applied when the state is read (by the health check, `overload.status`, or the API), so its record appears then. A refusal at shutdown changes no state. | implemented |
| Health: the per-queue check (`queue-drops:{path}`) reports the state. `LATCHED` is DEGRADED with "latched until cleared". As before, it is UNKNOWN while counters are disabled, and the health service caches a status for 1 s. | implemented |
| Operator clear: admin `overload.clear <feed>`, `MongooseServer.clearOverloadState(feed)`. Status: admin `overload.status`, `MongooseServer.overloadStates()`. Counters are not reset. | implemented |
| One structured log line (`overload state: feed=… queue=… group=… from=… to=… reason=…`; WARNING, or INFO back to UP) and one `OverloadState` audit record per state change, never per event. Records go to the publisher's `getStateAudit()` and to the subscribing group's control audit. PR #76's `QueueDrop` first-loss records are unchanged. | implemented |
| Kill switch (D5): a feed with `killSwitch: true` calls the hook set with `MongooseServer.setOverloadKillSwitch` when a never-drop queue enters `LATCHED`, once per latch. The hook gets an `OverloadLoss` and runs on the daemon thread `mongoose-overload-kill-switch`, never under a publish lock. Off by default. A feed that enables it with no hook set is warned after start. | implemented |
| Recording (owner decision): in RECORD mode, a drop keeps its journal entry and sequence number. The publisher marks the queue's producer position (`DropMarks`). The reader, on its group thread, appends one `ReplayEntry.Dropped` to each recorded processor once every item offered before the drop has been dispatched. The entry names the count, the sequence range and the reason, and consecutive drops are coalesced; it is also stored in CSV as `DROPPED`. A replay reports the loss (`GroupReplayer.losses(processor)`) and **carries on**. The processor never received those items, so its recorded stream is still reproducible. A terminal `Failed` entry was tried first: it stopped the chaos replay stress test (`ChaosReplayStressTest`) at the first drop, for no gain in fidelity. CSV readers older than this change reject a `DROPPED` row rather than misread it. | implemented |

## 9. The default path is unchanged

On a feed with no blocking binding, `publish` runs the same statements in the same order as before #79, with two
additions:

- one plain `int` compare (`blockingTargets == 0`) under the lock it already holds;
- the body of `publishSerialised` moved into `publishMapped`, one extra call frame;
- a `ThreadLocal` read, plus an increment and a decrement of the per-thread dispatch depth (section 6), so that a
  publish nested in any dispatch is recognised. In the single-threaded micro-benchmark this costs about 0.6 ns per
  publish, roughly 16.6 ns before and 17.2 ns after. It is the only measurable cost, and it buys the cross-publisher
  nesting guard.

`publishOrCache` gains the same compare. `writeToQueue` is identical on a successful offer. On a full queue it reads
the binding's spin bound from the per-binding map once per full queue per publish (10 ms by default), and builds
the same reason text, `queue full after 10 ms spin`. Evidence: `OverloadDefaultParityTest`, plus every existing
drop and volume test unchanged (`QueueDropObservabilityTest`, `PooledEventReleasePathsTest`,
`HighVolumeEventStressTest`), and a micro-benchmark in the milestone report.

## 10. Configuration

**Implemented.** The policy is set per feed or pipe, with overrides per subscriber group. The group is the
processor group (agent) name of the subscribing queue.

```yaml
eventFeeds:
  - name: orders
    instance: !!com.example.OrderGatewaySource { }
    overload:
      policy: BLOCK_WITH_DEADLINE      # or DROP_AFTER_SPIN
      blockDeadlineMillis: 250         # required for, and only for, BLOCK_WITH_DEADLINE
      spinBoundMillis: 10              # optional; default 10; may be 0
      quietPeriodMillis: 60000         # feed level; drop-tolerant queues recover after this
      killSwitch: false                # feed level; true calls MongooseServer.setOverloadKillSwitch's hook on a latch
      subscribers:                     # optional overrides, keyed by processor group name
        telemetryGroup:
          policy: DROP_AFTER_SPIN
          spinBoundMillis: 0
pipes:
  - name: fills
    overload:
      policy: DROP_AFTER_SPIN
```

Programmatic:

```java
EventFeedConfig.builder().name("orders").instance(src)
    .overload(OverloadPolicyConfig.blockWithDeadline(Duration.ofMillis(250))
        .subscriber("telemetryGroup", OverloadPolicyConfig.dropAfterSpin(Duration.ZERO)))
    .build();
// without configuration objects:
server.setFeedOverload("orders", FeedOverloadSettings.of(OverloadSettings.blockWithDeadline(Duration.ofMillis(250)))
    .withSubscriberGroup("telemetryGroup", OverloadSettings.dropAfterSpin(Duration.ZERO)));
```

Rules:

- A boot from configuration resolves each feed and pipe before registering it (`resolveOverload()`).
- An `overload` block wins over `slowConsumerStrategy`, with a warning.
- An explicit `slowConsumerStrategy: BACKOFF` means `DROP_AFTER_SPIN`, with a warning.
- `DISCONNECT` and `EXIT_PROCESS` throw `ConfigurationException` naming the feed.
- No setting at all gives the default, with the warning `feed 'X' has no explicit overload policy`.
- Bad values fail boot naming the feed and the key: a missing policy, a missing or non-positive deadline, a deadline
  on `DROP_AFTER_SPIN`, a negative spin bound, or nested `subscribers`.
- A deadline above 60 s (`MAX_RECOMMENDED_DEADLINE_MILLIS`) is warned at boot but not capped. It behaves like the
  deferred unbounded `BLOCK`: unsubscribe only re-evaluates, so a wedged subscriber holds the publisher that long.
- An override keyed by a group that no configured processor group matches is warned at boot. After start, an
  override that has bound no queue and matches no processor group of the server is warned once.
- A pipe with a blocking policy is warned at boot: processor publishes into it are downgraded. With `cacheEventLog`
  too, it is warned that the start replay drops.
- The pre-#79 all-arguments constructors of `EventFeedConfig` and `HandlerPipeConfig` are kept.
- Programmatic sources registered without configuration get the default silently. Only configured feeds are warned.

## 11. Deferred and open

- **Deferred:** conflation, priority, spill to disk, unbounded `BLOCK`, `DISCONNECT`/`EXIT_PROCESS`.
- **Proposed:** a processor's defined reaction to a downgraded or refused outbound publish. This is the order-path
  case from the second review.
- **Proposed:** cross-group fill→hedge cycles. Agent threads never wait, so a cycle among processor groups cannot
  deadlock. A cycle through a non-agent bridge thread can still wait up to its deadline.
- **Proposed:** a wedged consumer that keeps polling into its re-entrant queue defeats blocking. The health latch is
  the detection.
- **Proposed:** per-feed drain accounting and reporting at shutdown.

## 12. Tests

| Behaviour | Test |
| --- | --- |
| Default parity, configurable spin bound | `dispatch/OverloadDefaultParityTest` |
| YAML and builders, validation, `SlowConsumerStrategy` mapping, boot failure, boot wiring, compatible constructors | `config/OverloadPolicyConfigTest` |
| Deadline expiry, room freed mid-wait, unsubscribe while waiting, shutdown while waiting, agent-thread downgrade, no duplicate on retry, pooled refusal, slow subscriber under an override | `dispatch/BlockWithDeadlineTest` (real threads) |
| Review of 35bdeb2: nesting across publishers and within one, pooled reset contract, downgrade counting, cache replay, deadline re-read, wake on removal, interrupt, policy change while waiting | `dispatch/OverloadReviewRegressionTest` |
| Latch and quiet period, health, counters, state-change log and audit, operator clear, kill switch (on, off by default, not at shutdown), the recording `Failed` marker | `dispatch/OverloadHealthAndKillSwitchTest` |
