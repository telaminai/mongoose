# Event Subscription Sequence Diagram

## Introduction

This document provides a detailed sequence diagram for the event subscription process in Mongoose server. Event subscription is the process by which event processors register their interest in receiving events from specific event sources.

## Components Involved

The following components are involved in the event subscription process:

1. **EventProcessor** - The component that wants to receive events
2. **ComposingEventProcessorAgent** - Manages event processors and their subscriptions
3. **EventFlowManager** - Coordinates the event flow between sources and processors
4. **EventSource** - The source of events
5. **EventQueueToEventProcessor** - Consumes events from queues and forwards them to processors

## Sequence Diagram

```
┌───────────────┐    ┌───────────────┐    ┌───────────────┐    ┌───────────────┐    ┌───────────────┐
│EventProcessor │    │ComposingEvent │    │EventFlow      │    │EventSource    │    │EventQueueTo   │
│               │    │ProcessorAgent │    │Manager        │    │               │    │EventProcessor │
└───────┬───────┘    └───────┬───────┘    └───────┬───────┘    └───────┬───────┘    └───────┬───────┘
        │                    │                    │                    │                    │
        │  subscribe(key)    │                    │                    │                    │
        │───────────────────>│                    │                    │                    │
        │                    │                    │                    │                    │
        │                    │  getMappingAgent() │                    │                    │
        │                    │───────────────────>│                    │                    │
        │                    │                    │                    │                    │
        │                    │                    │  create queue      │                    │
        │                    │                    │──────────────────┐ │                    │
        │                    │                    │                  │ │                    │
        │                    │                    │<─────────────────┘ │                    │
        │                    │                    │                    │                    │
        │                    │                    │  create            │                    │
        │                    │                    │  EventQueueToEventProcessor             │
        │                    │                    │────────────────────────────────────────>│
        │                    │                    │                    │                    │
        │                    │<───────────────────│                    │                    │
        │                    │                    │                    │                    │
        │                    │  store mapping     │                    │                    │
        │                    │──────────────────┐ │                    │                    │
        │                    │                  │ │                    │                    │
        │                    │<─────────────────┘ │                    │                    │
        │                    │                    │                    │                    │
        │                    │  registerProcessor()                    │                    │
        │                    │────────────────────────────────────────>│                    │
        │                    │                    │                    │                    │
        │                    │  subscribe(key)    │                    │                    │
        │                    │───────────────────>│                    │                    │
        │                    │                    │                    │                    │
        │                    │                    │  subscribe(key)    │                    │
        │                    │                    │───────────────────>│                    │
        │                    │                    │                    │                    │
        │                    │                    │  store subscription│                    │
        │                    │                    │                    │──────────────────┐ │
        │                    │                    │                    │                  │ │
        │                    │                    │                    │<─────────────────┘ │
        │                    │                    │                    │                    │
        │                    │                    │<───────────────────│                    │
        │                    │                    │                    │                    │
        │                    │<───────────────────│                    │                    │
        │                    │                    │                    │                    │
        │<───────────────────│                    │                    │                    │
        │                    │                    │                    │                    │
```

## Sequence Description

1. **EventProcessor initiates subscription**:
   The EventProcessor calls `subscribe(subscriptionKey)` on the ComposingEventProcessorAgent to express interest in events from a specific source.

3. **ComposingEventProcessorAgent gets mapping agent**:
   The ComposingEventProcessorAgent calls `getMappingAgent(subscriptionKey, this)` on the EventFlowManager to get or create an EventQueueToEventProcessor for the subscription.

4. **EventFlowManager creates queue and mapping agent**:
   The EventFlowManager creates a queue for the subscription if one doesn't exist.
   It creates an EventQueueToEventProcessor that will consume events from the queue and forward them to processors.
   It returns the EventQueueToEventProcessor to the ComposingEventProcessorAgent.

4. **ComposingEventProcessorAgent stores mapping**:
   The ComposingEventProcessorAgent stores the mapping between the subscription key and the EventQueueToEventProcessor.

5. **ComposingEventProcessorAgent registers processor**:
   The ComposingEventProcessorAgent calls `registerProcessor(eventProcessor)` on the EventQueueToEventProcessor to register the EventProcessor as a listener.

6. **ComposingEventProcessorAgent subscribes to event source**:
   The ComposingEventProcessorAgent calls `subscribe(subscriptionKey)` on the EventFlowManager to subscribe to the event source.

7. **EventFlowManager subscribes to event source**:
   The EventFlowManager calls `subscribe(subscriptionKey)` on the EventSource to subscribe to events.

8. **EventSource stores subscription**:
   The EventSource stores the subscription information.

9. **Subscription complete**:
   The subscription process is complete, and the EventProcessor will now receive events from the EventSource.

## Conclusion

The event subscription process in Mongoose server involves multiple components working together to establish a connection between event sources and event processors. The sequence diagram illustrates the flow of method calls and the responsibilities of each component in the subscription process.