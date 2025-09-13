# Glossary

- Agent: A dedicated thread that runs a group of components (e.g., processors or services). Each Event Processor Group runs on its own agent thread.
- Event Processor Group: A named group that hosts one or more StaticEventProcessor instances on a single agent thread.
- StaticEventProcessor: A processor abstraction that receives events via onEvent(Object) or strongly-typed callbacks depending on dispatch strategy.
- ObjectEventHandlerNode: A simple, business-logic-focused handler base; extend this to process events without deep runtime integration.
- DefaultEventProcessor: A deeper integration point that participates in lifecycle and can expose typed interfaces for dispatch.
- Service: A container-managed component (worker, cache, connector) that can be injected into handlers via @ServiceRegistered.
- Event Feed (Source): A publisher of events into the system (e.g., file tailer, in-memory source). Can be normal or agent-hosted.
- Event Sink: A consumer/target for processed events (e.g., database writer, in-memory sink, network sink).
- IdleStrategy: Strategy used by an agent to handle idle duty cycles (busy spin, yield, sleep, backoff, etc.).
- Broadcast feed: A feed whose events are delivered to all handlers/processors on the target processor without explicit subscription.
- ProcessorContext: Runtime helper exposing the current target processor during dispatch; available in deeper integrations.
