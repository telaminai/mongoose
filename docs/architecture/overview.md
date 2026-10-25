# Architecture overview

Mongoose is a Java runtime for event-driven applications. Feeds publish inputs, processor groups execute business
logic, and services and sinks connect that logic to the outside world. An Agrona `AgentRunner` drives each group
with a configured idle strategy.

## Runtime structure

<div class="architecture-diagram">
--8<-- "docs/assets/architecture/runtime.svg"
</div>

A processor group has one agent thread. Its application processors share that thread, so a slow callback delays
other processors in the group. Different groups run independently. The server also hosts service agents and a
scheduler; external libraries may publish from their own threads.

`EventFlowManager` establishes routes and queues. It is not a central worker that receives every event.
Once wired, a feed's `EventToQueuePublisher` writes directly to its target queues; group readers invoke the
subscribed processors through an `EventToInvokeStrategy`.

## Two event paths

| Path | Inputs | Execution and evidence |
| --- | --- | --- |
| Application delivery | Feed items, timers, processor-bound admin commands | Business processors on their group thread; optional processor audit and record/replay |
| Group control | Add, stop, service broadcasts, capture requests and effect completions | Generated `GroupControlProcessor` on the same group thread; a bounded per-group control audit |

The group checks control queues before running its queue readers. Completions are drained separately from the
bounded command drain. **The control mailbox is not on every business event's dispatch path**, although control
work and callbacks consume time on the same thread. There is no total order across different groups or across
commands and asynchronous helper completions.

## Lifecycle ownership

The server serialises admission against shutdown and publishes versioned service changes. Each group's control
processor decides the transitions of its registrations. Effects build/start/release processors, deliver services
and install captures. Capture I/O runs on helpers; its outcomes return to the group as events.

A processor name is unique within a group while admitted. A `ProcessorHandle` binds to a particular generation,
so a delayed stop cannot target a replacement with the same name. Logical processor release and physical capture
closure are separate: a stop reply can arrive while capture cleanup is still pending.

[Components and ownership](components.md) explains these boundaries; the
[control request sequence](sequence-diagrams/control-request.md) shows the decision/effect cycle.

## Evidence and recovery

The control audit records lifecycle decisions and reported effect outcomes. Application audit records describe
business processors. Replay stores record supported per-processor inputs, clocks and control interactions.
These are separate facilities: a control audit alone cannot reconstruct the whole server or prove an external
side effect happened.

Mongoose does not provide clustering, replicated processor state or automatic failover. See
[deployment](deployment.md) and the [operations runbook](../reference/operations-runbook.md).
