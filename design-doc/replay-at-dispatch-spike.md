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
6. **Other ways into a processor**: typed service calls and processor-owned admin commands pass this point (not as
   `onEvent`); timers, lifecycle and control do not (below).

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

## Which thread runs processor code (checked in source)

Every path Mongoose drives into a processor runs on the processor group's agent thread, **after the queue**:
- feed events;
- processor-owned admin commands, which are queued onto the processor;
- service calls through a typed invoke strategy (below);
- timer expiries (the scheduler is a composed agent of the group);
- lifecycle calls, `@ServiceRegistered` callbacks (including dynamic registration, through the group's broadcast
  queues) and `ConfigListener.initialConfig`.

So a processor's inputs arrive in one sequence, on one thread. One recorder per processor, fed from the points where
they leave their queues, captures them in their true order with no locking.

**The one exception is filed as [mongoose#46](https://github.com/telaminai/mongoose/issues/46).**
`audit.start` / `audit.stop` are server-level admin commands. They run on the transport's thread and call
`dataFlow.setAuditLogProcessor(...)`, whose default method calls `onEvent` from that thread, racing the agent. This is
inferred from source and not yet reproduced.

**Not a Mongoose path:** `MongooseServerController.registeredProcessors()` hands out the live processors. Code holding
one could call it, or its exported services, from any thread. Nothing in Mongoose `src/main` does; `mongoose-plugins`
was not checked.

## Service calls: after the queue, so recordable at dispatch

A processor's exported service is the processor object itself (`DataFlow.getExportedService()` is
`return (T) this;`). A generated exported method has no thread hand-off: it is `beforeServiceCall(signature)` → node
method → `afterServiceCall()`. It audits an `ExportFunctionAuditEvent` carrying the signature only, never the
argument values.

**In a managed Mongoose a service call does not arrive that way.** It arrives through a
[typed invoke strategy](https://telaminai.github.io/mongoose/example/plugin/writing-a-typed-invoke-publishing-service-plugin/),
in three steps:
1. The service publishes a value to its queue.
2. `EventQueueToEventProcessorAgent.doWork` polls it.
3. The strategy's `dispatchEvent` calls the processor's typed method (`listener.onServiceEvent(s)`) on the agent
   thread.

That is after the queue and through `processEvent`, so the recorder in this spike sees it. The record needs the
**source** it came from, not the callback type. The callback is configuration: the source defines it, and a replay boots
the same config. So a replay publishes each record back through its own source (`publishReplay`), and the configured
strategy makes the same call. The record is `{source, event, wallClockTime}`. The strategy does not know its source
name; the queue's agent does (it is named `group/source/callback`), so a production recorder takes it from there. The
recorded value also carries what the exported method's audit event leaves out, the arguments.

**Spiked** (`TypedServiceCallReplaySpikeTest`, 1/0/0/0). A typed service (`QuoteControlService`) sends three commands;
the processor (`QuoteControlProcessor`, exporting `QuoteControl`) receives them as service calls at T0+10..30. They
are recorded after the queue with their source. Replayed through the same service's queue into a fresh server, the
configured strategy makes the same three calls at the same instants. Witness: the same records through a plain feed
arrive as events, not calls, and do not reproduce the run.

The other ways in, and whether a dispatch recorder sees them:

| path | after a queue, through `processEvent`? | a dispatch recorder captures it? |
|---|---|---|
| feed event, `BroadcastEvent`, `ReplayRecord` | yes | yes |
| service call through a typed invoke strategy | yes (`onEvent` no) | yes, with its callback type |
| processor-owned admin command | yes (`onEvent` no) | yes, as `AdminCommand`; only its args are recordable |
| timer expiry (`DeadWheelScheduler.onTimerExpiry` runs the node's `Runnable`) | no, a direct callback on the agent thread | no: see *Timers* below |
| `registerService` / `deRegisterService`, `initialConfig`, lifecycle | no | no, and not needed: these are set-up, re-created by booting the same config |
| `setAuditLogProcessor` / `setAuditLogLevel` / `setClockStrategy` | no (default methods call `onEvent` directly) | no, and not needed: set-up; see mongoose#46 for `audit.*` |
| server-level admin commands | no, on the transport thread | not processor inputs |
| `BatchDtoHandler` redispatch | the `BatchDto` yes; its inner events are re-entrant | the `BatchDto` is enough |

**Reads a replay must supply** (non-void calls a node may make on injected services): the processor's wall clock
outside a pinned dispatch, `SchedulerService` times and timer ids, `ObjectPool.acquire()`, and controller, error and
counter snapshots. Whether a given graph reads them is the graph's own business. The Mongoose `ScheduledTriggerNode`
only schedules, and ignores the returned id.

A direct exported-service call from user code that holds the processor would still bypass all of this. The
exported-service boundary (`beforeServiceCall`) is where to record such a call, with its arguments. That is a Fluxtion
change, and it matches the admin-commands proposal's option B.

## Timers

A listener on expiry records WHEN a timer fired, but a replay cannot use that alone. A timer's action is a closure the
node made when it scheduled it, so it cannot be recorded. And during a replay the same build re-arms the same timers,
while the live scheduler fires them from its own clock (`DeadWheelScheduler` reads an `OffsetEpochNanoClock`, real
time), at the wrong points or not at all. The old Fluxtion `YamlReplayRunner` (`com.fluxtion.compiler.replay`) has no
timer support either: it pins the clock per record and calls `onEvent`. A timer could replay there only because the
runner has no live scheduler and the writer, an auditor, sees the event a fired timer's cycle dispatches.

The design (`TimerReplay`):
- **Recording**: a scheduler decorator gives each schedule call a sequence number per processor. The number is
  deterministic: a replay makes the same calls in the same order. On expiry it reports `Fired{seq, instant}` into the
  processor's input sequence, pins the processor's clock to the deadline, then runs the action.
- **Replay**: a scheduler that never fires by itself. When the replay stream reaches `Fired{seq, t}`, it pins the
  clock to `t` and fires the action the replayed node registered under `seq`. It refuses a `seq` the replayed run
  never scheduled.
- Both schedulers keep `milliTime()` on the processor's time, so a delay computed from it is the same in both.

**Spiked** (`TimerReplaySpikeTest`, 1/0/0/0), driving a processor directly with the duty cycle's order (due timers
fire before the next input). Each order arms a 50 ms timeout, and one timeout fires between two inputs. The recorded
stream is the inputs with the three firings where they happened. The replay reproduces the run exactly. Witness: the
inputs alone, without the firings, do not. Control: the recorder reporting the poll time instead of the deadline is
caught; restored byte-identically.

**Needed in Mongoose:** a scheduler factory in config. `MongooseServer.java:740` hard-wires
`new DeadWheelScheduler()` into each processor group, so the recording and replay schedulers cannot be installed today.
A timer's firing is then recorded into the same per-processor sequence as the dispatched inputs.

## Recommendation

1. **Record feed events at dispatch** (this spike): a recording `EventToInvokeStrategy` from config, one sequence per
   processor across its queues, the processor's clock pinned to the recorded instant (decision 1 above).
2. **Admin commands**: record `{command, args}` at `AdminCommandInvoker`; with the proposal's option B they also get an
   audit record to check a replay against.
3. **Service calls** through a typed invoke strategy: recorded at dispatch like events, with their callback type, and
   replayed back through the same source. **Timers**: a numbering scheduler records each firing into the same
   per-processor sequence, and a replay scheduler fires only when the replay reaches it (needs a scheduler factory
   in config). A direct exported-service call is outside managed Mongoose; recording it needs the Fluxtion
   service boundary, with its arguments.
4. The recorder's codec is the processor's handled event types plus the recorded service signatures.
5. Configuration events (audit level/processor, clock, service registration, initial config) are the deployment's
   set-up: recorded once as configuration, or re-created by booting the same config, never replayed as inputs.
