# Architecture and design

Mongoose hosts event processors on Agrona agent threads. Each processor group also runs a generated Fluxtion
control processor that decides its lifecycle changes. These pages describe the **unreleased control-plane candidate**;
see the [release notes](../release/release-notes-unreleased.md) for caller-visible changes.

| Start here | What it explains |
| --- | --- |
| [Architecture overview](overview.md) | The runtime, its thread boundaries and the two event paths |
| [Components and ownership](components.md) | Who owns registrations, queues, services and capture resources |
| [Mongoose built with Fluxtion](built-with-fluxtion.md) | Why the runtime uses its own event-processing technology |
| [Event flow](event-flow.md) | Publication, subscriptions, delivery, retries and overload |
| [Sequence diagrams](sequence-diagrams/index.md) | Subscription, event delivery, control, capture and shutdown |
| [Agent execution](agent-execution.md) | Choosing and hosting cooperative workers |
| [Threading model](threading-model.md) | Callback contexts, waiting rules, core pinning and idle strategies |
| [Object pooling](object_pooling.md) | Reference ownership across fan-out and release |
| [Server control](server-control.md) | Admin commands and the programmatic controller |
| [Deployment](deployment.md) | JVM requirements, shutdown and recovery limits |
| [Case study: a late capture completion](../case-studies/control-audit-investigation.md) | Following real qualification evidence through the analyser |

The diagrams are editable SVGs, scale without losing detail and follow the site's light/dark theme. Solid arrows
show the flow labelled on each diagram; they do not imply that the entire server has one global event order.
The [control-plane contracts](../reference/control-plane-contracts.md) give the exact API guarantees and failure outcomes.
