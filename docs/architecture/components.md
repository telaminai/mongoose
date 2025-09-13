# Mongoose server Component Architecture

## Introduction

This document provides a detailed description of the key components in the Mongoose server architecture and their
relationships. Understanding these components is essential for developing, extending, and maintaining applications built
on the Mongoose server framework.

## Core Components

### MongooseServer

The MongooseServer class is the central component that bootstraps and manages the entire system. It serves as the main
controller and coordinates the interactions between all other components.

#### Responsibilities:

- Loading and applying configuration
- Managing the lifecycle of event processors and services
- Registering event sources, event processors, and services
- Coordinating the event flow through the EventFlowManager

#### Class Diagram:

```
┌───────────────────────────────────────────────┐
│            MongooseServer                     │
├───────────────────────────────────────────────┤
│ - flowManager: EventFlowManager               │
│ - mongooseServerConfig: MongooseServerConfig  │ 
│ - composingEventProcessorAgents: Map          │
│ - composingServiceAgents: Map                 │
│ - registeredServices: Map                     │
├───────────────────────────────────────────────│
│ + bootServer()                                │
│ + registerEventSource()                       │
│ + registerService()                           │
│ + registerWorkerService()                     │
│ + addEventProcessor()                         │
│ + init()                                      │
│ + start()                                     │
└───────────────────────────────────────────────┘
```

### EventFlowManager

The EventFlowManager is responsible for managing the flow of events between sources and processors. It acts as the
central hub for event routing.

#### Responsibilities:

- Registering event sources and their associated queues
- Managing subscriptions between event sources and processors
- Routing events from sources to appropriate processors
- Providing the infrastructure for event flow

#### Class Diagram:

```
┌───────────────────────────────────────┐
│           EventFlowManager            │
├───────────────────────────────────────┤
│ - eventSourceToQueueMap: Map          │
│ - eventSinkToQueueMap: Map            │
│ - eventToInvokerFactoryMap: Map       │
│ - subscriberKeyToQueueMap: Map        │
├───────────────────────────────────────┤
│ + registerEventSource()               │
│ + registerEventSink()                 │
│ + subscribe()                         │
│ + unSubscribe()                       │
│ + registerEventMapperFactory()        │
│ + getMappingAgent()                   │
└───────────────────────────────────────┘
```

### EventSource

The EventSource interface defines components that generate events. These can be external systems, internal timers, or
other event generators.

#### Responsibilities:

- Generating events from various sources
- Publishing events to the event flow system
- Managing subscriptions from event processors

#### Interface Diagram:

```
┌───────────────────────────────────────┐
│            EventSource<T>             │
├───────────────────────────────────────┤
│ + subscribe()                         │
│ + unSubscribe()                       │
│ + setEventToQueuePublisher()          │
│ + setEventWrapStrategy()              │
│ + setSlowConsumerStrategy()           │
│ + setDataMapper()                     │
└───────────────────────────────────────┘
```

### EventProcessor

Event processors consume and process events. They are managed by the ComposingEventProcessorAgent and implement business
logic for handling events.

#### Responsibilities:

- Subscribing to event sources
- Processing events according to business logic
- Generating output or side effects

#### Component Diagram:

```
┌───────────────────────────────────────┐
│          StaticEventProcessor         │
├───────────────────────────────────────┤
│ + onEvent()                           │
│ + init()                              │
│ + tearDown()                          │
│ + addEventFeed()                      │
│ + registerService()                   │
└───────────────────────────────────────┘
```

### ComposingEventProcessorAgent

The ComposingEventProcessorAgent manages a group of event processors. It handles the lifecycle of processors and routes
events to them.

#### Responsibilities:

- Managing the lifecycle of event processors
- Routing events to appropriate processors
- Handling subscriptions to event sources

#### Class Diagram:

```
┌───────────────────────────────────────┐
│     ComposingEventProcessorAgent      │
├───────────────────────────────────────┤
│ - eventFlowManager: EventFlowManager  │
│ - registeredEventProcessors: Map      │
│ - queueProcessorMap: Map              │
├───────────────────────────────────────┤
│ + addNamedEventProcessor()            │
│ + removeEventProcessorByName()        │
│ + registerSubscriber()                │
│ + subscribe()                         │
│ + unSubscribe()                       │
└───────────────────────────────────────┘
```

### AbstractEventSourceService

The AbstractEventSourceService is a base class for services that act as event sources. It provides common functionality
for event source services.

#### Responsibilities:

- Implementing the EventSource interface
- Managing the lifecycle of the service
- Publishing events to the event flow system

#### Class Diagram:

```
┌───────────────────────────────────────┐
│     AbstractEventSourceService<T>     │
├───────────────────────────────────────┤
│ - name: String                        │
│ - output: EventToQueuePublisher<T>    │
│ - subscriptionKey: EventSubscriptionKey│
├───────────────────────────────────────┤
│ + setEventFlowManager()               │
│ + init()                              │
│ + subscribe()                         │
│ + tearDown()                          │
└───────────────────────────────────────┘
```

## Component Relationships

### Component Interaction Diagram

The following diagram illustrates the relationships between the key components:

```
┌───────────────┐     ┌───────────────┐     ┌───────────────┐
│               │     │               │     │               │
│MongooseServer │────▶│EventFlowManager────▶│EventSource    │
│               │     │               │     │               │
└───────┬───────┘     └───────────────┘     └───────┬───────┘
        │                                           │
        │                                           │
        ▼                                           ▼
┌───────────────┐                          ┌───────────────┐
│               │                          │               │
│ComposingEvent │◀─────────────────────────│EventToQueue   │
│ProcessorAgent │                          │Publisher      │
└───────┬───────┘                          └───────────────┘
        │
        │
        ▼
┌───────────────┐
│               │
│EventProcessor │
│               │
└───────────────┘
```

## Configuration Components

### MongooseServerConfig

The MongooseServerConfig class holds the configuration for the entire system. It is loaded from a YAML file and used to
configure all components.

#### Responsibilities:

- Storing configuration for event sources, processors, and services
- Providing access to configuration parameters
- Supporting configuration updates

#### Class Diagram:

```
┌───────────────────────────────────────┐
│         MongooseServerConfig          │
├───────────────────────────────────────┤
│ - eventFeeds: List<EventFeedConfig>   │
│ - eventSinks: List<EventSinkConfig>   │
│ - services: List<ServiceConfig>       │
│ - eventHandlers: List<EventProcessorGroupConfig>│
├───────────────────────────────────────┤
│ + lookupIdleStrategy()                │
│ + getIdleStrategyOrDefault()          │
└───────────────────────────────────────┘
```

### EventProcessorConfig

The EventProcessorConfig class configures individual event processors. It specifies how events are processed and what
handlers are used.

#### Responsibilities:

- Configuring event processors
- Specifying event handlers
- Setting logging levels

#### Class Diagram:

```
┌───────────────────────────────────────┐
│        EventProcessorConfig<T>        │
├───────────────────────────────────────┤
│ - eventHandler: T                     │
│ - customHandler: ObjectEventHandlerNode│
│ - eventHandlerBuilder: Supplier<T>    │
│ - logLevel: LogLevel                  │
│ - configMap: Map<String, Object>      │
├───────────────────────────────────────┤
│ + getEventHandler()                   │
│ + getConfigMap()                      │
│ + getConfig()                         │
└───────────────────────────────────────┘
```

## Service Components

### Service

The Service class represents a service in the system. Services can provide various functionalities and can be event
sources, event processors, or standalone services.

#### Responsibilities:

- Providing functionality to the system
- Managing the lifecycle of the service
- Interacting with other components

#### Class Diagram:

```
┌───────────────────────────────────────┐
│              Service<T>               │
├───────────────────────────────────────┤
│ - instance: T                         │
│ - serviceClass: Class<?>              │
│ - serviceName: String                 │
├───────────────────────────────────────┤
│ + init()                              │
│ + start()                             │
│ + stop()                              │
│ + tearDown()                          │
└───────────────────────────────────────┘
```

### ServiceAgent

The ServiceAgent interface represents a service that runs in its own thread as an agent. It provides a way to run
services in a dedicated thread.

#### Responsibilities:

- Running a service in a dedicated thread
- Managing the lifecycle of the service
- Interacting with other components

#### Interface Diagram:

```
┌───────────────────────────────────────┐
│             ServiceAgent<T>           │
├───────────────────────────────────────┤
│ + getAgentGroup()                     │
│ + getIdleStrategy()                   │
│ + getService()                        │
└───────────────────────────────────────┘
```

## Conclusion

The component architecture of Mongoose server provides a flexible and extensible framework for building event-driven
applications. The clear separation of concerns between components allows for easy customization and extension, while the
well-defined interfaces ensure consistent behavior across the system.

Understanding these components and their relationships is essential for effectively using and extending the Mongoose
server framework. This document serves as a reference for developers working with the framework, providing insights into
the design and implementation of the system.