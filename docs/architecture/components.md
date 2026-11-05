# Components and ownership

The important boundary is who may decide a change and who owns the resource it affects. The
[overview diagram](overview.md#runtime-structure) shows the runtime connections.

## Server and group control

| Component | Responsibility | Ownership boundary |
| --- | --- | --- |
| `MongooseServer` | Configuration, group admission, service registry and public APIs | Admission is fenced against the shutdown snapshot; registry changes are versioned |
| `LifecycleManager` | Startup and phased, resumable shutdown | Tracks helpers and joins within the caller's deadline; does not forcibly stop arbitrary callbacks |
| `ComposingEventProcessorAgent` | Hosts business processors and implements control effects | Running processors and group bookkeeping are accessed on the group thread; cleanup after thread death requires joining it first |
| `ControlDriver` | Mailbox, separate completion queue, request claiming, terminal replies and effect settling | Concurrent senders may admit requests; only the owning group drives the generated processor |
| `GroupControlProcessor` | Generated dispatch for lifecycle, capture and service rules | Decides group transitions; does not own the server registry or replace the transport protocol |
| `ControlState` and rule nodes | Registrations, pending operations and decision records | Agent-owned state; generation and operation identity distinguish replacements and late completions |
| `EffectQueue` | Runs requested effects at `batchEnd()` | Decision records publish first; results return as new event cycles |

The Spring-authored graph is in `src/main/fluxtion/control/control-processor.xml`; its generated Java is committed
under `control/generated`. See [Mongoose built with Fluxtion](built-with-fluxtion.md).

## Data delivery

| Component | Responsibility | Ownership boundary |
| --- | --- | --- |
| `EventFlowManager` | Creates/reuses queues and routes; tracks readers across groups | Wiring and subscription management, rather than a per-event central dispatcher |
| `EventToQueuePublisher` | Maps/wraps input and offers it to target queues | A publisher lock serialises writes, including concurrent callers and cache replay |
| `EventQueueToEventProcessorAgent` | Polls a queue and invokes its subscribers | Runs in the processor group; holds pooled queue ownership until dispatch finishes |
| `EventToInvokeStrategy` | Selects the callback route and invokes processors | Custom strategies must supply the identity hooks needed for retry isolation and recording |
| `DataFlow` / application processor | Business state and event handling | One group thread; external callers must publish work instead of mutating it directly |

Several processors in one group can share a feed reader. Releasing one processor must not unsubscribe the
remaining processors or another group's reader. See [subscription](sequence-diagrams/event-subscription.md).

## Services and admin

`ComposingServiceAgent` hosts cooperative worker services. Other services may expose synchronous methods or manage
their own threads. A sink method can run on the calling processor thread unless its implementation explicitly
hands work off; injecting a service does not give its methods a separate execution thread.

The server's service lock covers registry publication and non-blocking versioned broadcasts. Service callbacks
and reply waits happen outside it. `removeService` waits for groups to acknowledge withdrawal before stopping the
removed instance; if withdrawal is not confirmed, the server retains ownership until shutdown.

Processor-bound admin commands are delivered through the application event path. Other commands run on their
invoking thread. Either may call the server controller, which then uses the group control protocol. Admin transport
and lifecycle dispatch are distinct layers; see [server control](server-control.md).

## Capture, audit and replay

| Component | Responsibility | Important distinction |
| --- | --- | --- |
| `ControlAuditSink` | Bounded per-group control history and export manifest | Completeness permits validation; it does not prove correct behaviour or authenticate the export |
| `ChronicleAuditCaptureService` | Stores a business processor's Fluxtion audit | Each built-in capture pins group, name, generation and session identity |
| `AuditCaptureResources` | Backend open/install/uninstall/close effects | Open/close on helpers, install/uninstall on the group thread; unresolved closes retain ownership |
| `GroupRecorder` / `GroupReplayer` | Record and reproduce supported per-processor execution | Replay identity and service provenance must match; unsupported or divergent behaviour stops replay |
| `ReplayStore` | Streams, provenance and journal data | Separate from the control audit and its ring retention |

Capture display selectors are not storage paths. New sessions use fresh `capture-*` directories with identity
metadata. Third-party capture backends that inherit the bare-name compatibility defaults must implement scoped
ownership before claiming isolation between namesakes. See [capture identity](../design/capture-resource-identity.md).

The [capture sequence](sequence-diagrams/audit-capture.md) and
[shutdown sequence](sequence-diagrams/shutdown.md) show why a logical reply and resource closure are separate facts.
