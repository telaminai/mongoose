# Event flow

A source publishes through `EventToQueuePublisher`. `EventFlowManager` establishes the routes, then the publisher
writes directly to the queues read by processor groups. See the
[event delivery sequence](sequence-diagrams/event-processing.md) for the thread boundary.

## Subscriptions and queues

A subscription identifies a feed and callback type. The group creates or reuses a reader and registers the processor
with its invocation strategy. The dispatch queue is keyed by feed and subscribing group, not by individual processor.
Several processors can therefore consume the same queued item in one group.

Feed delivery uses `OneToOneConcurrentArrayQueue`. Multiple publication callers are safe because the publisher
serialises writes. The control mailbox uses `ManyToOneConcurrentArrayQueue` and has a separate completion queue.
Sink registrations also use many-to-one queues. These queues serve different purposes; they do not establish one
server-wide event order.

When a processor leaves, its reader registration is removed. An empty reader is retired and its queued pooled
references are released. Source unsubscription occurs only when no group reads that subscription key.

## Wrapping and ownership

`EventWrapStrategy` selects subscription or broadcast routing, with either raw inputs or `NamedFeedEvent` wrappers.
The invocation strategy selects `onEvent` or a configured callback route.

For pooled inputs, publication transfers the origin reference to the publisher. Each offered queue obtains a
reference; a dropped offer releases it. The publisher releases the origin after offering to all targets, and a
reader releases its queue reference after dispatch. The final release returns the object. Do not retain an event
without acquiring ownership or copying it. See [object pooling](object_pooling.md).

## Publisher serialisation and slow subscribers

A publisher lock covers publication, cache replay and target changes. It remains held while a full target queue is
retried for about 10 ms; after that, the copy for that queue is dropped, reported and counted in `droppedCount()`.
Other queues can still receive their copies. A queue copy may serve several processors.

A slow target delays other targets and callers using the same publisher. The bounded queue-offer retry does
**not** establish a hard end-to-end bound on lock acquisition, mapping, journalling or the whole publish call.
In RECORD mode, encoding and journal append also happen under this lock to preserve journal sequence order.

`SlowConsumerStrategy` exposes `DISCONNECT`, `EXIT_PROCESS` and `BACKOFF`, but this publisher does not consult the
configured value. Do not treat those names as implemented overload policies. Current behaviour is bounded offer
retry followed by a counted drop; a lossless application must supply its own suitable ingestion policy.

## Processing failures

The reader's default `RetryPolicy` permits three attempts for `RuntimeException`, with backoff starting at 5 ms.
When a strategy identifies the failing processor, retries resume there and skip that processor after exhaustion,
without replaying already successful processors. A legacy strategy that cannot identify the failure retries the
whole dispatch. Retried handlers must account for effects they may already have performed.

Backoff runs on the group thread and delays its other work. Exhausted failures are reported through `ErrorReporting`.
This is a specific dispatch retry mechanism, not a transactional rollback or a universal recovery policy.

## Recording and replay

Recording captures each processor's supported deliveries and clock observations; replay mutes its live inputs and
captures `MessageSink` output. Other injected services can still be live. Stream provenance, build ordinals,
service identity and intercepted control calls constrain what can be reproduced. Unsupported operations or
mismatches stop the affected replay with a reason.

See [record and replay contracts](../reference/control-plane-contracts.md#record-and-replay). Neither a successful
control audit investigation nor an empty input queue proves deterministic replay or completed external output.
