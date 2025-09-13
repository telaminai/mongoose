# How-to: Boot a MongooseServer using the fluent builder APIs

This guide shows how to boot a MongooseServer using the fluent `MongooseServerConfig` builder APIs, wire an event source and a simple processor, and publish an event end-to-end.

Key steps:

1. Create an event processor implementing StaticEventProcessor and EventProcessor, and subscribe to an EventFeed by name using EventSubscriptionKey.onEvent("feedName").
2. Create an event source by extending AbstractEventSourceService and expose a publish method.
3. Build an EventProcessorConfig via its builder with your processor instance (or handler supplier).
4. Build an EventProcessorGroupConfig and register the named processor within the group.
5. Build an EventFeedConfig for your event source and assign it a feed name.
6. Build the MongooseServerConfig with .addProcessorGroup(...) and .addEventFeed(...).
7. Boot the server using MongooseServer.bootServer(config, logRecordListener).
8. Publish events via the event source; the processor will receive them.

See ExampleFluentBoot.java in this package for a complete working example.
