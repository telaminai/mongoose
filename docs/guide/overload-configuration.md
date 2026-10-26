# Configuring overload and loss per feed

This guide is for teams that run a market maker, or any trading application, on Mongoose. It shows how to choose
and configure what each feed does when a subscriber falls behind. The design and its guarantees are in
[Overload and loss policy (design)](../design/overload-policy.md); this page is the practical how-to.

## 1. The choice per feed

Every feed and pipe has an overload policy. You make the choice once per feed, and optionally override it for
individual subscriber groups (processor group names).

| Policy | What happens when a subscriber queue is full | Use for |
| --- | --- | --- |
| `DROP_AFTER_SPIN` (default) | Spin for the spin bound (10 ms by default), then drop the item for that queue only. Counted, and the queue's health turns DEGRADED. | Replaceable data, where the next update fully supersedes the last: top-of-book and quote snapshots, telemetry. |
| `BLOCK_WITH_DEADLINE` | Wait up to the deadline for room, without holding any lock. Then deliver to every **blocking** subscriber, or to none, and throw `PublishRefusedException` to the publisher. Subscribers given a drop-tolerant override may still drop their copy, by design. A loss latches the queue until an operator clears it. | Data that must not be lost silently: venue session and health events, fills, order acknowledgements, credit and limit updates, hedge instructions. |

If you set nothing, a feed keeps the behaviour of earlier releases and logs at boot:
`feed 'X' has no explicit overload policy`. Set a policy on every feed so the choice is explicit.

## 2. Recommended policies for a market maker

| Feed | Policy | Deadline | Notes |
| --- | --- | --- | --- |
| Venue session and health (logon, logout, disconnect, kill switch) | `BLOCK_WITH_DEADLINE` | e.g. 250–1000 ms | Never drop. Publish from the venue connector's own thread, not an agent thread. |
| Fills and execution reports | `BLOCK_WITH_DEADLINE` | e.g. 250 ms | Never drop. A refusal must reach the connector, which rejects or re-requests upstream. Turn on `killSwitch`. |
| Order acknowledgements and cancels | `BLOCK_WITH_DEADLINE` | e.g. 100–250 ms | As fills. Cancel priority is not implemented yet. |
| Credit and limit updates, hedge instructions | `BLOCK_WITH_DEADLINE` | e.g. 250 ms | Never drop. |
| Replaceable market data (top-of-book, quote snapshots) | `DROP_AFTER_SPIN` | none (spin 0–10 ms) | A loss costs staleness: the next snapshot repairs it. Conflation is not implemented yet; a stale queue keeps old snapshots and drops the newest. |
| Incremental market data (order-book deltas, trades) | `BLOCK_WITH_DEADLINE`, or `DROP_AFTER_SPIN` **only** with sequence-gap detection and resynchronisation | e.g. 50–250 ms | A missed delta or trade can permanently corrupt derived state; the next update does not repair it. Either refuse explicitly and handle the refusal, or detect the gap from the venue's sequence numbers and resynchronise (snapshot and replay). |
| Telemetry and analytics subscribers on any feed | `DROP_AFTER_SPIN`, as a per-group override, with `spinBoundMillis: 0` | none | Stops a slow telemetry consumer from slowing, or refusing, the fills that a risk group needs. |

The deadlines above are illustrations, not recommendations: choose them per deployment from your queue sizes,
consumer rates and how long a connector can safely be held. Keep them short. A deadline is how long a publisher
may be held waiting for queue capacity by one wedged subscriber. It bounds **only that wait**: the time to run the
data mapper or the journal codec, or to acquire the publish lock behind another publisher, is not included.
Deadlines above 60 s are warned at boot.

## 3. Configuration

### YAML

```yaml
eventFeeds:
  - name: venue-session
    instance: !!com.example.VenueSessionSource { }
    overload:
      policy: BLOCK_WITH_DEADLINE
      blockDeadlineMillis: 500
      killSwitch: true

  - name: fills
    instance: !!com.example.FillSource { }
    overload:
      policy: BLOCK_WITH_DEADLINE
      blockDeadlineMillis: 250
      killSwitch: true
      subscribers:
        telemetryGroup:              # processor group (agentName) of the telemetry processors
          policy: DROP_AFTER_SPIN
          spinBoundMillis: 0

  - name: prices
    instance: !!com.example.PriceSource { }
    overload:
      policy: DROP_AFTER_SPIN
      spinBoundMillis: 1
      quietPeriodMillis: 30000       # DEGRADED clears after 30 s without loss (minimum 1000)
```

### Keys

| Key | Where | Default | Rule |
| --- | --- | --- | --- |
| `policy` | feed, subscriber | required in a block | `DROP_AFTER_SPIN` or `BLOCK_WITH_DEADLINE` |
| `blockDeadlineMillis` | feed, subscriber | none | Required for, and only for, `BLOCK_WITH_DEADLINE`; > 0; warned above 60000 |
| `spinBoundMillis` | feed, subscriber | 10 | ≥ 0 |
| `subscribers` | feed | none | Keys are processor group names; an unknown name is warned at boot and after start |
| `quietPeriodMillis` | feed | 60000 | ≥ 1000; recovery of drop-tolerant queues |
| `killSwitch` | feed | false | Calls the server's kill-switch hook when a never-drop queue latches |

`slowConsumerStrategy` is deprecated. `BACKOFF` means `DROP_AFTER_SPIN`, and `DISCONNECT` and `EXIT_PROCESS` fail
boot.

### Programmatic

```java
EventFeedConfig.builder().name("fills").instance(fillSource)
    .overload(OverloadPolicyConfig.blockWithDeadline(Duration.ofMillis(250))
        .killSwitch(true)
        .subscriber("telemetryGroup", OverloadPolicyConfig.dropAfterSpin(Duration.ZERO)))
    .build();
```

## 4. Publishing into a never-drop feed

- **Publish from a non-agent thread.** A publish waits for room only on a connector's or session's own thread.
  On an agent thread (any processor group or service agent group), or nested inside another publish, a full
  queue falls back to drop-after-spin. That publish is counted in `downgradedCount()`, and the queue latches.
  This is why a `HandlerPipe` on `BLOCK_WITH_DEADLINE` protects only its non-agent publishers, and it is warned at
  boot.
- **Handle `PublishRefusedException`.** It means no subscriber received the item and no sequence number was used,
  so a retry does not duplicate. Reject the order upstream, alarm, or retry.
- **Pooled items:** copy the payload before you publish if you may retry. The refused instance is back in its pool
  and has been reset by the time you see the exception. Acquire a fresh one and fill it from your copy.
- **Pre-start caches are not protected.** A feed that caches events before start (`cacheEventLog`) replays them
  with drop semantics. This is warned.

## 5. Wiring the kill switch

The hook is off by default. To use it, set `killSwitch: true` on the feeds that should trigger it, and register a
hook once:

```java
server.setOverloadKillSwitch(loss -> {
    // runs on mongoose-overload-kill-switch-<feed>, never under a publish lock: it may block or call the server
    log.error("never-drop loss on {}/{} ({}): {}", loss.feed(), loss.queue(), loss.kind(), loss.reason());
    riskControls.haltQuoting("overload loss on " + loss.feed());
});
```

- It fires once per latch, the first loss since the queue was last cleared.
- Once the server begins to stop, no hook is started, including one already queued. A hook that is already
  running is not interrupted: write hooks that return promptly.
- It does fire for a queue that latched before the hook was set.
- A hook that runs longer than 5 s is warned. Each feed has its own hook thread, so a slow hook never delays
  another feed's.

## 6. Operator runbook: LATCHED and clear

| You see | Meaning | Do |
| --- | --- | --- |
| Health `queue-drops:<path>` DEGRADED, "N item(s) dropped by queue" | A drop-tolerant queue lost items | Check consumer capacity. It returns to UP after the feed's quiet period with no further loss. A feed with no configured policy stays DEGRADED, as in earlier releases. |
| Health DEGRADED, "never-drop queue … latched until cleared" | A never-drop queue refused or dropped an item | Treat as an incident: find what was lost (below), reconcile with the venue, then clear. |
| Log `overload state: … to=LATCHED` | The latch happened. The reason says refused, dropped or downgraded. | As above. |
| Log or audit `from=LATCHED to=REMOVED` | The latched queue was unsubscribed. The latch is **kept**. | Still clear it. The health check stays until you do. |

Commands, on the admin interface:

```text
overload.status              # every feed, queue and state; a queue removed while latched shows as "<name> [removed]: LATCHED"
overload.clear <feed>        # operator clear: LATCHED and DEGRADED queues of the feed return to UP
```

The same are available on the server as `MongooseServer.overloadStates()` and `clearOverloadState(feed)`. A clear
writes one record per queue and does not reset counters.

**What was lost.** Look at these, by feed and queue:

- the `OverloadState` records (publisher state audit, and the group control audit for never-drop queues);
- the first-loss `QueueDrop` record;
- the counters `queue.<path>.refused`, `.dropped`, `.blocked`, `.blockedNanos` and `.blockedMaxNanos`, and
  `feed.<name>.downgraded`.

In RECORD mode the processor's recording holds a `Dropped` entry at the point of loss. Drops past the 1024 pending
marks are not placed individually; they are counted into the first entry after the overflow. A replay reports it
(`GroupReplayer.losses`, `lossless()`) and carries on.

## 7. Checklist

- [ ] Every feed and pipe has an explicit `overload.policy`, and no boot warning says otherwise.
- [ ] Every never-drop feed is published from non-agent threads, and its publishers handle `PublishRefusedException`.
- [ ] Telemetry and other slow subscribers on never-drop feeds have a `DROP_AFTER_SPIN` override.
- [ ] Every subscriber override names a real processor group: no warning at boot or after start.
- [ ] Never-drop feeds that should halt trading have `killSwitch: true`, and a hook is registered.
- [ ] Alerting covers DEGRADED health for `queue-drops:*` and the `overload state` log lines.
- [ ] The operator runbook includes `overload.status` and `overload.clear`.


## Combined 1.1.0 failure and overload contract

See the [1.1.0 release notes](../release/release-notes-1.1.0.md) and
[client migration checklist](../release/client-migration-1.1.0.md) for the complete breaking-change and operator
acceptance checklist. `PublishRefusedException` carries `FailureKind` and `EvidenceStatus`; overload transition
records carry `OVERLOAD_LOSS` (or `NONE` on recovery/clear) and the evidence status at observation. Group status
JSON includes overload states, including retained `REMOVED` latches; the group's `overload` health check reports loss.

A blocked publish retains its intended bindings while waiting. Group death or quarantine cancels that admission
before commit, even if its target queue has since been removed; ordinary unsubscribe re-evaluates remaining targets.
The refusal is all-or-nothing and consumes the pooled reference as documented in the overload guide. Cancellation
is `TARGET_UNAVAILABLE` / `REFUSED_STATE`. The six cases in `FailureOverloadSeamTest` check metadata, group health/JSON,
removed latches, dead control, fatal CONTAIN, and quarantine of a generated processor. Direct calls from external
threads are outside this fence. `NOT_DEGRADED` never proves an export complete.
