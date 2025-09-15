# What is Event-Driven Programming?

Event-driven programming is a paradigm where the flow of a program is determined by events - such as user actions,
sensor outputs, or messages from other programs. This approach differs from traditional procedural programming, where
the program follows a predefined sequence of instructions.

## Core Concepts

### Events and Event Handlers

- **Events**: Discrete occurrences or signals that represent something has happened. Examples include a mouse click,
  data arriving from a network, a sensor reading, or a message from another system.
- **Event Handlers**: Functions or methods that execute in response to specific events. They contain the business logic
  that processes the event data.
- **Event Loop**: A programming construct that waits for and dispatches events to appropriate handlers.

### Event Sources and Sinks

- **Event Sources**: Components that generate or publish events. These could be user interfaces, sensors, network
  connections, or other systems.
- **Event Sinks**: Destinations where processed events are sent, such as databases, displays, or other systems.

### Asynchronous Processing

Event-driven systems typically operate asynchronously, meaning that events are processed as they occur without blocking
the main program flow. This enables responsive applications and efficient resource utilization.

## Benefits for Business Applications

### Responsiveness and Scalability

Event-driven architectures excel at handling high volumes of events with low latency. By processing events
asynchronously, systems can remain responsive even under heavy load.

### Loose Coupling

Components in event-driven systems communicate through events rather than direct method calls. This reduces dependencies
between components, making systems more maintainable and adaptable to change.

### Real-time Processing

Event-driven systems can process data as it arrives, enabling real-time analytics, monitoring, and response capabilities
critical for modern business applications.

## Merging Multiple Event Feeds: Why It Matters

In real-world applications, data often comes from multiple sources that need to be processed together:

- Market data from different exchanges
- Sensor readings from various IoT devices
- User interactions across multiple channels
- System metrics from different components

### The Challenge

Traditional approaches to handling multiple event sources often lead to:

1. **Complex Threading Models**: Managing concurrent access to shared resources
2. **Synchronization Overhead**: Ensuring data consistency across threads
3. **Difficult Error Handling**: Tracking and managing failures across multiple processing threads
4. **Code Complexity**: Business logic becomes entangled with concurrency concerns

### The Mongoose Solution

Mongoose Server addresses these challenges by:

1. **Unified Event Processing**: Merging multiple event feeds into a single-threaded processing model
2. **Declarative Subscriptions**: Handlers specify which named feeds they care about
3. **Automatic Thread Management**: Infrastructure handles the threading complexity
4. **Clean Business Logic**: Developers focus on what to do with events, not how to receive them

```java
// Example: Subscribing to specific event feeds
@Override
public void start() {
    // Subscribe only to the feeds this handler cares about
    getContext().subscribeToNamedFeed("prices");
    getContext().subscribeToNamedFeed("news");
    // Orders feed events will be ignored by this handler
}
```

## Pipeline Processing vs. Multi-Input/Multi-Output Event Processing

### Pipeline Processing

Pipeline processing follows a linear sequence where:

1. Data enters the system
2. Passes through a series of transformations
3. Produces a result at the end

```
Input → Transform A → Transform B → Transform C → Output
```

**Characteristics**:

- Linear flow
- Each stage depends on the previous one
- Simple to understand and implement
- Limited flexibility for complex event relationships

### Multi-Input/Multi-Output Event Processing

Multi-input/multi-output event processing allows:

1. Multiple event sources to feed into the system
2. Events to be processed based on type, content, or other criteria
3. Different processing paths for different events
4. Multiple outputs based on processing results

```
Input A ─┐
Input B ─┼→ Event Processor → ┬─ Output X
Input C ─┘                    ├─ Output Y
                              └─ Output Z
```

**Characteristics**:

- Non-linear, dynamic flow
- Greater flexibility
- Can handle complex event relationships
- More powerful for real-world business scenarios

## Why Mongoose Server Excels at Event-Driven Programming

Mongoose Server was designed from the ground up to simplify event-driven programming while maintaining high performance:

### 1. Simplified Threading Model

- **Single-Threaded Processing**: Business logic runs in a single thread, eliminating concurrency issues
- **Agent-Based Architecture**: Background threads handle I/O and services without complicating application code
- **Configurable Idle Strategies**: Optimize for latency or CPU usage without changing business logic

### 2. Declarative Event Handling

- **Named Feed Subscriptions**: Subscribe to events by feed name, not implementation details
- **Type-Based Dispatch**: Process events based on their type with clean handler methods
- **Automatic Wiring**: Infrastructure connects sources, processors, and sinks automatically

### 3. Separation of Concerns

- **Business Logic Focus**: Handlers contain only business rules, not infrastructure code
- **Infrastructure Separation**: Event sources, threading, and lifecycle managed separately
- **Service Injection**: Dependencies provided automatically to handlers

### 4. Performance Without Complexity

- **Zero-GC Processing**: Object pooling for high-throughput without garbage collection pauses
- **Batched Processing**: Efficient handling of high-volume event streams
- **Low Latency**: Sub-microsecond processing times without complex code

### 5. Operational Excellence

- **Dynamic Registration**: Add or remove event handlers at runtime
- **Administrative Controls**: Monitor and manage the system during operation
- **Comprehensive Metrics**: Track performance without instrumenting business code

## Real-World Example

Consider a trading system that needs to:

- Process market data from multiple exchanges
- Handle user orders
- Apply risk checks
- Execute trades
- Generate reports

**Traditional Approach**:

- Separate threads for each data source
- Complex synchronization between components
- Business logic mixed with threading concerns
- Difficult to test and maintain

**With Mongoose Server**:

```java
public class TradingHandler extends ObjectEventHandlerNode {
    @Override
    public void start() {
        // Subscribe only to relevant feeds
        getContext().subscribeToNamedFeed("market-data");
        getContext().subscribeToNamedFeed("orders");
    }

    @Override
    protected boolean handleEvent(Object event) {
        // Simple, single-threaded business logic
        if (event instanceof MarketData data) {
            updatePrices(data);
        } else if (event instanceof Order order) {
            processOrder(order);
        }
        return true;
    }

    // Business methods focus on domain logic, not threading
    private void updatePrices(MarketData data) { /* ... */ }

    private void processOrder(Order order) { /* ... */ }
}
```

## Conclusion

Event-driven programming provides a powerful paradigm for building responsive, scalable, and maintainable applications.
Mongoose Server simplifies this approach by handling the complex infrastructure concerns, allowing developers to focus
on business logic.

By merging multiple event feeds into a unified processing model, Mongoose Server eliminates the threading complexity
typically associated with event-driven systems while maintaining high performance. This makes it an excellent choice for
applications that need to process events from multiple sources with low latency and high throughput.

Whether you're building trading systems, IoT applications, or real-time analytics platforms, Mongoose Server provides
the tools to implement event-driven architectures without the traditional complexity.