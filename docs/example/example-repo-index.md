# Worked Examples

A separate github repo hosting worked examples of using the library is available:

## [Mongoose github example repo](https://github.com/telaminai/mongoose-examples/)

The examples in this repository demonstrate various aspects of Mongoose:

- How to configure and boot a Mongoose server
- Different approaches to event processing
- Integration with various input and output sources
- Performance optimization techniques
- Testing strategies for Mongoose applications

These examples range from simple "getting started" tutorials to more complex case studies showing real-world usage patterns.

## Available Examples

### Getting Started

- [Five Minute Tutorial]({{example_root}}/getting-started/five-minute-tutorial) - A programmatic approach to configuring a Mongoose server with multiple named event feeds and selective event processing.
- [Five Minute YAML Tutorial]({{example_root}}/getting-started/five-minute-yaml-tutorial) - The same functionality as the Five Minute Tutorial, but using YAML configuration instead of programmatic configuration.
- [Stream Programming Tutorial]({{example_root}}/getting-started/stream-programming-tutorial) - Demonstrates native Fluxtion DataFlow stream programming in Mongoose where lifecycle and feed subscriptions are handled automatically.

### Plugins

- [Event Source Example]({{example_root}}/plugins/event-source-example) - Demonstrates how to create a custom event source by extending the AbstractAgentHostedEventSourceService class, allowing you to generate events at regular intervals.
- [Event Source Non-Agent Example]({{example_root}}/plugins/event-source-nonagent-example) - Shows how to create a custom event source that manages its own threading using a ScheduledExecutorService instead of relying on the Mongoose agent infrastructure.
- [Message Sink Example]({{example_root}}/plugins/message-sink-example) - Illustrates how to create a custom message sink by extending the AbstractMessageSink class, with configurable formatting options for console output.
- [Service Plugin Example]({{example_root}}/plugins/service-plugin-example) - Demonstrates how to create custom service plugins that can be registered with Mongoose server, including both simple lifecycle services and worker services that run background tasks.

### How-To Guides

- [Subscribing to Named Event Feeds]({{example_root}}/how-to/subscribing-to-named-event-feeds) - Shows how to subscribe to specific named EventFeeds and ignore others, demonstrating selective event processing.
- [Data Mapping]({{example_root}}/how-to/data-mapping) - Demonstrates how to transform incoming feed events to a different type using value mapping with Function<Input, ?> mappers.
- [Using the Scheduler Service]({{example_root}}/how-to/using-the-scheduler-service) - Shows how to use the built-in SchedulerService for delayed actions, periodic jobs, and scheduled triggers.
- [Injecting Config into a Processor]({{example_root}}/how-to/injecting-config-into-a-processor) - Demonstrates how to inject configuration data into event processors for customizable behavior.
- [Handler Pipe]({{example_root}}/how-to/handler-pipe) - Shows how to use HandlerPipe for in-VM communication between handlers, enabling message passing patterns.
- [Object Pool]({{example_root}}/how-to/object-pool) - Demonstrates zero-GC object pooling techniques for high-performance event processing with minimal garbage collection.
- [Replay]({{example_root}}/how-to/replay) - Shows how to implement deterministic replay with ReplayRecord and the data-driven clock for testing and debugging.
- [Core Pin]({{example_root}}/how-to/core-pin) - Shows how to pin agent threads to specific CPU cores for optimal performance in latency-sensitive applications.
- [Writing a Custom Event to Invoke Strategy]({{example_root}}/how-to/writing-a-custom-event-to-invoke-strategy) - Demonstrates how to create custom EventToInvokeStrategy implementations for specialized event handling patterns.
- [Scheduler processAsNewEventCycle]({{example_root}}/how-to/scheduler-processAsNewEventCycle) - Demonstrates re-entrant publishing with processAsNewEventCycle and SchedulerService.