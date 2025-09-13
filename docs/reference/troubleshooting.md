# Troubleshooting and FAQ

Common issues and remedies when building with Mongoose Server.

- My handler doesn’t receive events
- 
  - Verify the feed name matches your subscription (subscribeToNamedFeed("..."))
  - If using service injection, ensure you subscribe in start() after wiring
  - Confirm the handler is wired into an Event Processor Group and the feed is registered

- Latency spikes
- 
  - Check idle strategies: busy spin is lowest latency; yielding/sleeping saves CPU but adds latency
  - Ensure CPU throttling isn’t active (containers): consider core pinning for critical agents

- Backpressure and queues
- 
  - Use admin command “eventSources” to inspect queues
  - Tune publisher/consumer rates and batching; consider lowering log output on hot paths

- Lifecycle gotchas
- 
  - Do not subscribe in constructors; use start() and tearDown() appropriately
  - If you need config, implement ConfigListener and use initialConfig(ConfigMap)

- How to test a handler
- 
  - For ObjectEventHandlerNode: instantiate directly and call event methods
  - For DefaultEventProcessor: build with minimal context and simulate lifecycle before feeding events
