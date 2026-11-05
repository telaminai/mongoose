# Delivering an event

<div class="architecture-diagram">
--8<-- "docs/assets/architecture/event-delivery.svg"
</div>

The publisher runs on the source's calling thread. It maps/wraps the input and offers it to target queues under
its publication lock. The processor group's readers poll their queues and invoke the subscribed processors on the
group thread. `EventFlowManager` is involved in wiring, not called as a central router for each input.

Readers currently process up to 64 queued items per `doWork()` call. Before the readers run, the composing group
checks control work. This does not imply a lifecycle request can interrupt a handler already executing.

The diagram shows successful delivery. For a full queue, the publisher eventually drops that queue's copy. For a
handler failure, the reader applies its retry policy. A pooled reference is released in cleanup even if dispatch
fails. See [overload and retries](../event-flow.md).

Timers and processor-bound admin commands also reach processor event cycles, but use their own delivery routes.
Optional recording, audit listeners and sinks add work; they are not represented as free operations here.
