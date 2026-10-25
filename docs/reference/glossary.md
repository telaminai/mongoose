# Glossary

**What this page covers.** Each Mongoose term is defined here once. Other pages link here instead of defining terms
again. Control-plane terms come second. Each has its own heading, so you can link to it directly (for example
`glossary.md#generation`).

## Core terms

- **Agent**: A dedicated thread that runs a group of components (for example processors or services), calling their
  `doWork()` in a loop governed by an idle strategy. Each processor group runs on its own agent thread.
- **Event processor group** (or **processor group**, or **group**): A named group that hosts one or more event
  processors on a single agent thread. See [Group](#group).
- **StaticEventProcessor** / **DataFlow**: A processor abstraction that receives events through `onEvent(Object)` or
  strongly typed callbacks, depending on the dispatch strategy.
- **ObjectEventHandlerNode**: A simple, business-logic-focused handler base. Extend it to process events without deep
  runtime integration.
- **DefaultEventProcessor**: A deeper integration point that takes part in the lifecycle and can expose typed
  interfaces for dispatch.
- **Service**: A container-managed component (worker, cache, connector) that can be injected into handlers with
  `@ServiceRegistered`.
- **Event feed** (source): A publisher of events into the system (for example a file tailer or an in-memory source).
  It can be normal or agent-hosted.
- **Event sink**: A consumer of processed events (for example a database writer, an in-memory sink or a network sink).
- **IdleStrategy**: How an agent waits during idle duty cycles (busy spin, yield, sleep, backoff, and so on).
- **Broadcast feed**: A feed whose events are delivered to every processor in the target group without an explicit
  subscription.
- **ProcessorContext**: A runtime helper that exposes the current target processor during dispatch. Available in
  deeper integrations.

## Control-plane terms

These terms are used by the [control-plane contracts](control-plane-contracts.md).

### Group

A processor group: a named set of processors that share one agent thread and one control mailbox. A group is created
when its first processor is added. Its name is the `groupName` argument of `addEventProcessor`, `addProcessor` and
`stopProcessor`, and the `agentName` of a configured processor.

### Agent thread

A thread that runs one of a server's processor groups or service agent groups. Processor handlers, a processor's
`start()` and `stop()`, and `@ServiceRegistered` callbacks all run on the processor's group's agent thread. **An agent
thread never waits for a control reply.** Mongoose marks the thread when its group starts and clears the mark after
the group closes, so code that runs while a group closes still counts as agent code.

### Control request

A request to change a group: add or stop a processor, deliver or withdraw a service, start or stop an audit capture,
or close the group. Every control request gets exactly one final [reply](#reply).

### Mailbox

The group's single, ordered queue of control requests. It holds 4096 requests. The group drains it at the start of
each duty cycle, before it dispatches events.

### Control processor

A generated Fluxtion event processor, one per group (`GroupControlProcessor`), that decides every control request in
order and requests the effects (start, stop, release, open a capture, and so on) that carry it out. It writes the
[control audit](#control-audit). It is not a data processor: it does not appear in `registeredProcessors()` and is
never subscribed to feeds.

### Reply

The one final answer to a control request: a `ControlReply` with an outcome of `APPLIED`, `REFUSED`, `FAILED` or
`NOT_RUNNING`. See [Replies and outcomes](control-plane-contracts.md#replies-and-outcomes).

### Admission

The moment a processor name is reserved in its group, before the add is sent. A second add of the same name is refused
from admission until the stop that frees the name has been applied.

### Registration

One accepted add of a processor: its group, name and [generation](#generation). A stop is always bound to a
registration, so it can only stop the processor of that registration.

### Generation

A group-local number, increasing by one for each admitted add in the group. It identifies one registration while the
server runs. Adds of different names made at the same time race for generations, so the same program can give
different generations in different runs. `ProcessorHandle.generation()` returns it.

### Handle

A `ProcessorHandle`: group, name and generation of one registration, owned by the server that made it.
`stopProcessor(handle)` stops only that registration, and a handle from another server is refused.

### Incarnation

The per-name count of admitted adds in a group (1, 2, ...). It is carried in control-audit `Input` records
(`incarnation`). It is not used for replay stream keys; those use the [build ordinal](#build-ordinal).

### Build ordinal

The per-name count of processors actually **built** in a group (1, 2, ...). An add that was refused, cancelled or never
built does not consume one. Replay stream keys use it: the first build keeps the bare name, and later builds are
`name#2`, `name#3`. The replay API calls this number `incarnation` (for example `ReplayStreams.key(name,
incarnation)`).

### Stream

In record and replay, the ordered entries of one processor build, keyed by name and build ordinal.

### Provenance

What a replay stream records about the processor that produced it: the processor class, the handler class it wraps,
and its build ordinal. A replay into a processor whose classes differ stops.

### Control audit

The analyser-readable record of a group's control decisions, kept in a bounded in-memory ring per group. See
[Control audit](control-plane-contracts.md#control-audit).

### Fallback stream

Plain records written into the control audit by the group itself, not by the control processor, for events that must
stay observable when the control processor cannot write: an anomaly, a dead group, a request swept at close.
