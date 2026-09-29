# Spike: record replay inputs at Mongoose's dispatch point

**Status**: spike (branch `spike/replay-at-dispatch`, test code only, nothing in `src/main` changed). 2026-09-29.

## The question

Can a Mongoose deployment record every input a processor receives, so the run can be replayed later into the same build
and do exactly what it did, by recording where Mongoose hands an event to the processor rather than inside the
processor?

Inside the processor, the recorder is an auditor that sees every event, including the ones the graph raises itself, so
the caller has to name each external input first (`writer.expect(e)`) and the recorder compares identities. At the
dispatch point that problem does not exist: an event the graph raises re-enters its own processor's `onEvent` and never
passes Mongoose's dispatch, so everything recorded there is an input by construction.

## What the source says (origin/main, `ab44617`)

| fact | where |
|---|---|
| one `EventQueueToEventProcessorAgent` per (event source, subscribing agent group); it polls one `OneToOneConcurrentArrayQueue` | `dispatch/EventFlowManager.java` `getMappingAgent`; `dutycycle/EventQueueToEventProcessorAgent.java` |
| a processor group (`ComposingEventProcessorAgent`) may subscribe to several sources, so it polls several queues, all on its own thread | `dutycycle/ComposingEventProcessorAgent.java` |
| each queue's strategy fans one event out to every processor registered on it | `dispatch/AbstractEventToInvocationStrategy.java` `processEvent` |
| the strategy is pluggable per callback type, from config: `MongooseServerConfig.builder().onEventInvokeStrategy(Supplier)` | `config/MongooseServerConfig.java:575` |
| **replay input already exists**: a `ReplayRecord` on a queue is dispatched as `processEvent(event, wallClockTime)`, which drives a per-processor synthetic clock | `EventQueueToEventProcessorAgent.java:70-71`; `AbstractEventToInvocationStrategy.processEvent(Object,long)`; [how-to: replay](https://telaminai.github.io/mongoose/example/how-to/how-to-replay/) |
| a failed dispatch is retried by `RetryPolicy`, so the same event can be dispatched more than once | `EventQueueToEventProcessorAgent.java:65-106` |

So the missing half is only the recorder, and it fits the existing SPI.

## The spike

`src/test/java/com/telamin/mongoose/spike/replay/`:

- `RecordingEventToInvokeStrategy` extends `EventToOnEventInvokeStrategy`, installed with `onEventInvokeStrategy(...)`.
  For each event it picks the instant, records `ReplayRecord{event, wallClockTime}`, and dispatches through the
  existing `processEvent(event, time)`, so the processor's clock reads exactly the recorded instant. A replay drives
  the same clock the same way.
- `BreachHandler`: counts orders, and on the second raises a `Breach` on its own processor
  (`getContext().getParentDataFlow().onEvent(...)`), the same type an external source also sends.
- `RecordAtDispatchSpikeTest`: inputs `ord-1`, `ord-2`, an external `Breach`, `ord-3`.

| check | result (RAN, 1/0/0/0) |
|---|---|
| the processor handled 5 events: 3 orders, the graph's breach, the external breach | yes |
| recorded at dispatch: exactly the 4 inputs, the external breach among them, never the graph's; at T0+10..T0+40 | yes |
| the recorded `ReplayRecord`s through an ordinary source into a fresh server (no recorder) emit exactly what the run emitted, at the same instants | yes |
| witness: the same events without their instants do not reproduce the run | yes (differs) |
| control: `recorded.add(r)` removed, from a byte copy | caught by the named assertion; restored byte-identically |

Found on the way: the base class's `processEvent(event, time)` calls the overridable `processEvent(event)`, so a
subclass that overrides both must guard re-entry, or it recurses until the stack overflows and the retry policy drops
every event. A production recorder should hook the dispatch loop directly (or the base class should offer a
non-virtual inner dispatch).

## What the spike shows, and does not

Shown: recording at dispatch needs no filter, no identity check, no matcher and nothing compiled into the processor;
Mongoose's existing replay input reproduces the run.

Not shown, and each needs a decision before this is more than a spike:

1. **The clock semantics change while recording.** Dispatching with a time uses the synthetic clock, which then holds
   that instant until the next event: a `getWallClockTime()` read later in the same cycle, or between cycles (a timer,
   a service thread), no longer reads the live clock. The replay is exact because the live run was pinned the same way.
   Whether production may run pinned is an owner decision.
2. **Order across queues.** A processor fed by several queues sees them interleaved by its group agent's duty cycle.
   Recording per strategy gives each queue's order; the processor's input order across queues needs one sequence per
   processor (the group runs on one thread, so a per-processor recorder shared by its strategies gives it).
3. **Fan-out.** One event dispatched to several processors is one record per strategy call; a replay must deliver it to
   the same set.
4. **Retries.** A retried dispatch is recorded once per attempt. The processor did see it twice; whether a replay
   should, is open.
5. **Serialisation.** The spike keeps `ReplayRecord` objects in memory. Writing them needs the codec: the processor's
   handled event types (the analyser's DEMO writer has one, restricted to records of simple components).
6. **Other ways into a processor** that do not pass this strategy: service calls, timers, lifecycle and control
   (below). Admin commands owned by a processor do pass it, but not as an `onEvent`.

## Admin commands (reviewed: origin/main and `origin/proposal/admin-commands-in-event-cycle`)

On main, an admin command registered by a processor is published to its own `adminCommand.<name>` queue and runs on
the processor's agent thread through `processEvent` → `AdminCommandInvoker.dispatchEvent`, which calls
`executeCommand()`, **not** `onEvent`: it is outside any Fluxtion event cycle, so it leaves no audit record, and state
it changes marks nothing dirty. A dispatch recorder hooked at `processEvent` does see it, as an `AdminCommand`, but that
object carries the reply consumers, the user lambda, the target queue and a semaphore: only `args` (the name first) is
recordable. The proposal branch is a design doc only (no code); its recommended option B,
`DataFlow.runInEventCycle(description, action)`, gives each command its own audit record naming the command and args,
modelled on the exported-service-call boundary. For replay: record `{processor, command name, args}` at the admin
dispatch, in the processor's input sequence; at replay, look the command up again by name (the same build registers it)
and run it with stub reply consumers. Built-in commands registered with no current processor run on the transport
thread and never reach a processor.

## Service calls, timers and the other ways in (reviewed: origin/main, runtime 1.0.15 sources)

**Mongoose has no marshalling for calls into a processor.** `DataFlow.getExportedService()` returns the processor
itself (runtime `DataFlow.java:451-453`), so an exported-service call is a plain method call on the generated processor,
on the caller's thread. The generated method wraps it in `beforeServiceCall(signature)` → node method →
`afterServiceCall()`; it audits an `ExportFunctionAuditEvent` whose only field is the **method signature, no argument
values** (runtime `ExportFunctionAuditEvent.java:10-22`, `DefaultEventProcessor.java:299-312`). None of it passes the
dispatch strategy. So:

| path | through the dispatch strategy? | a dispatch recorder captures it? |
|---|---|---|
| feed event, `BroadcastEvent`, `ReplayRecord` | yes | yes |
| processor-owned admin command | `processEvent` yes, `onEvent` no | yes, as `AdminCommand` (record only its args) |
| custom strategy calling an exported method (e.g. `PublishingServiceTyped`) | `processEvent` yes, `onEvent` no | yes, if the recorder is at `processEvent` |
| exported-service call from another component | **no**, a direct call on the caller's thread | **no** |
| `registerService` / `deRegisterService` into processors (join, and dynamic via the group's broadcast queues) | no | no |
| `ConfigListener.initialConfig(configMap)` at start (`internal/ServerConfigurator.java:130`) | no | no |
| scheduler expiry (`DeadWheelScheduler.onTimerExpiry` runs the node's `Runnable`; `ScheduledTriggerNode` then calls `onEvent` itself) | no | no |
| lifecycle (`init`, `start`, `startComplete`, `stop`, `tearDown`) | no | no |
| `setAuditLogProcessor` / `setAuditLogLevel` / `setClockStrategy` (default methods calling `onEvent` directly) | no | no; `audit.start`/`audit.stop` make the call from the ADMIN thread, unsynchronised with the agent (INFERRED race) |
| server-level admin commands (no current processor) | no, run on the transport's thread | no |
| `BatchDtoHandler` redispatch | the `BatchDto` yes; its inner events are re-entrant | the `BatchDto` is enough |

**Reads a replay must supply** (non-void calls a node may make on injected services): the processor's wall clock
outside a pinned dispatch; `SchedulerService` times and timer ids; `ObjectPool.acquire()`; controller, error and
counter snapshots. Whether a given graph reads them is the graph's own business; the Mongoose `ScheduledTriggerNode`
only schedules and ignores the id.

So the service-call half of R-D5 ("a serialised method call") has a natural home: record at the exported-service
boundary, the same `beforeServiceCall` where the audit event is made, the method AND its arguments, in the processor's
input sequence. That is a Fluxtion change (the boundary is private; the admin-commands proposal's option B asks to
expose it too), not a Mongoose one. Timers are recordable as their firing instant plus which timer, at
`DeadWheelScheduler.onTimerExpiry`.

## Recommendation

1. **Record feed events at dispatch** (this spike): a recording `EventToInvokeStrategy` from config, one sequence per
   processor across its queues, the processor's clock pinned to the recorded instant (decision 1 above).
2. **Admin commands**: record `{command, args}` at `AdminCommandInvoker`; with the proposal's option B they also get an
   audit record to check a replay against.
3. **Service calls and timers**: record at their own boundaries into the same per-processor sequence. Service calls
   need the exported-service boundary exposed in Fluxtion, carrying the arguments.
4. The recorder's codec is the processor's handled event types plus the recorded service signatures.
5. Configuration events (audit level/processor, clock, service registration, initial config) are the deployment's
   set-up: recorded once as configuration, or re-created by booting the same config, never replayed as inputs.
