# Mongoose server Event Flow Architecture

## Introduction

The event flow architecture is a core aspect of the Mongoose server framework. It defines how events are generated, routed, and processed throughout the system. This document provides a detailed explanation of the event flow architecture, including the components involved and their interactions.

## Event Flow Components

The event flow architecture consists of the following key components:

1. **EventSource** - Generates events from external or internal sources
2. **EventFlowManager** - Routes events between sources and processors
3. **EventToQueuePublisher** - Publishes events to queues
4. **EventQueueToEventProcessor** - Consumes events from queues and forwards them to processors
5. **EventProcessor** - Processes events according to business logic

### Component Interaction Diagram

```
┌───────────────┐     ┌───────────────────────────────────────────┐
│               │     │             EventFlowManager              │
│  EventSource  │────▶│                                           │
│               │     │  ┌───────────────┐    ┌───────────────┐   │
└───────────────┘     │  │EventToQueue   │───▶│EventQueue     │   │
                      │  │Publisher      │    │               │   │
                      │  └───────────────┘    └───────────────┘   │
                      │                              │            │
                      └──────────────────────────────│────────────┘
                                                     │
                                                     ▼
                      ┌──────────────────────────────────────────┐
                      │      EventQueueToEventProcessor          │
                      │                                          │
                      │  ┌───────────────┐    ┌───────────────┐  │
                      │  │EventToInvoke  │───▶│EventProcessor │  │
                      │  │Strategy       │    │               │  │
                      │  └───────────────┘    └───────────────┘  │
                      │                                          │
                      └──────────────────────────────────────────┘
```

## Event Flow Process

The event flow process in Mongoose server follows these steps:

1. **Event Generation**: An EventSource generates an event, which could be from an external system, a timer, or another internal component.

2. **Event Publication**: The EventSource publishes the event to the EventFlowManager through the EventToQueuePublisher.

3. **Event Routing**: The EventFlowManager routes the event to the appropriate event queues based on subscriptions.

4. **Event Consumption**: The EventQueueToEventProcessor consumes events from the queue.

5. **Event Processing**: The EventToInvokeStrategy determines how to invoke the EventProcessor with the event, and the EventProcessor processes the event.

6. **Result Handling**: The result of the event processing may generate new events, which can be fed back into the system.

## Subscription Model

Mongoose server uses a subscription model to connect event sources with event processors:

1. **EventSubscriptionKey**: Identifies a subscription between an event source and a processor.

2. **Subscribe/Unsubscribe**: Processors can subscribe to or unsubscribe from event sources.

3. **Callback Types**: Different callback types determine how events are processed (e.g., OnEvent, custom callbacks).

### Subscription Diagram

```
┌───────────────┐                          ┌───────────────┐
│               │  1. Register             │               │
│  EventSource  │◀─────────────────────────│ MongooseServer│
│               │                          │               │
└───────┬───────┘                          └───────────────┘
        │                                          ▲
        │                                          │
        │                                          │
        │                                          │
        │                                  ┌───────┴───────┐
        │                                  │               │
        │  2. Subscribe                    │ EventProcessor│
        └─────────────────────────────────▶│               │
                                           └───────────────┘
```

## Event Types and Wrapping

Mongoose server supports different event wrapping strategies:

1. **SUBSCRIPTION_NOWRAP**: Events are passed directly to subscribers without wrapping.
2. **SUBSCRIPTION_NAMED_EVENT**: Events are wrapped with source information before passing to subscribers.
3. **BROADCAST_NOWRAP**: Events are broadcast to all subscribers without wrapping.
4. **BROADCAST_NAMED_EVENT**: Events are wrapped and broadcast to all subscribers.

## Slow Consumer Handling

Mongoose server provides strategies for handling slow consumers:

1. **DISCONNECT**: Disconnect slow consumers to prevent system slowdown.
2. **EXIT_PROCESS**: Exit the process if a consumer is too slow.
3. **BACKOFF**: Implement backoff strategies to give slow consumers time to catch up.

## Queue Implementation

The event queues in Mongoose server are implemented using:

1. **ManyToOneConcurrentArrayQueue**: For multiple producers and a single consumer.
2. **OneToOneConcurrentArrayQueue**: For a single producer and a single consumer.

These queue implementations provide thread-safe communication between components running in different threads.

## Error Handling

Error handling in the event flow follows these patterns:

1. **Global Error Handler**: Catches and handles errors at the system level.
2. **Retry Mechanisms**: Implements exponential backoff for retrying failed operations.
3. **Error Events**: Generates error events that can be processed by error handlers.

## Conclusion

The event flow architecture of Mongoose server provides a flexible and efficient mechanism for routing events between sources and processors. Its subscription-based model allows for dynamic configuration of event flows, while its queue-based implementation ensures thread-safe communication between components.