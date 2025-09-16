# Configuration and composition in Mongoose Server

This page explains how configuration brings components together and how the server binds them at runtime. The goal is a
clean separation of concerns so you focus on business logic, while Mongoose provides the infrastructure (wiring,
lifecycle, dispatching, threading).

Key idea: you assemble reusable plugins (event feeds, sinks, services) and your business logic handlers via
configuration (Java builder APIs or YAML). Mongoose boots from that config, creates/starts components, connects them,
and runs them on the configured agent threads.

## Components at a glance

- Event feeds (sources): Produce events (e.g., FileEventSource, InMemoryEventSource). Can run on an agent thread or on
  their own threads, but always publish into the server’s queues.
- Event processors (business logic): Your code (often an ObjectEventHandlerNode) that handles events. Always run on a
  processor agent thread.
- Event sinks (outputs): Consume results (e.g., FileMessageSink). Can be hosted as services or run externally.
- Services: Utility/operational components (e.g., admin registry, schedulers, worker services). Can be agent‑hosted.
- Threads/agents: Named threads hosting cooperative loops for processors and services. Each agent has an idle strategy,
  e.g., BusySpinIdleStrategy.

## How the config composes your app

You declare what you want to run, and Mongoose binds it together:

- Builder API (Java): Use MongooseServerConfig with EventProcessorConfig, EventFeedConfig, EventSinkConfig,
  ThreadConfig.
- YAML: Equivalent declarative model consumed by bootServer(Reader,...).

At boot, Mongoose:

1. Creates instances from your configuration.
2. Registers services and exposes them by name.
3. Spins up agent threads per ThreadConfig and per agent-declared feeds/services.
4. Connects feeds → processors (broadcast or targeted).
5. Applies initial configuration to processors that export config listeners.
6. Transitions components through lifecycle: init → start → startComplete.

You mostly write and test your business logic handler. Feeds, sinks, and many services are reusable plugins.

## Minimal example with builders

```java
import com.fluxtion.agrona.concurrent.BusySpinIdleStrategy;
import com.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.MongooseServer;

public class BuilderApiExample {
    public static void main(String[] args) {
        // 1) Business logic: single-threaded on a processor agent
        var handler = new ObjectEventHandlerNode() {
            @Override
            protected boolean handleEvent(Object event) {
                if (event instanceof String s) {
                    System.out.println("processor:'" + Thread.currentThread().getName() + "' event:" + s);
                }
                return true;
            }
        };

        // 2) Reusable plugin: in-memory feed
        var feed = new InMemoryEventSource<String>();

        // 3) Compose via config
        var processorCfg = EventProcessorConfig.builder()
                .customHandler(handler)
                .build();

        var feedCfg = EventFeedConfig.<String>builder()
                .instance(feed)
                .name("hello-feed")
                .broadcast(true)
                .agent("feed-agent", new BusySpinIdleStrategy())
                .build();

        var processorThread = ThreadConfig.builder()
                .agentName("processor-agent")
                .idleStrategy(new BusySpinIdleStrategy())
                .build();

        var app = MongooseServerConfig.builder()
                .addProcessor("processor-agent", "hello-handler", processorCfg)
                .addEventFeed(feedCfg)
                .addThread(processorThread)
                .build();

        var server = MongooseServer.bootServer(app);

        // 4) Publish events (user thread)
        feed.offer("hi");
        feed.offer("mongoose");

        server.stop();
    }
}

```

What the config did:

- addProcessor binds your handler to the processor-agent thread.
- addEventFeed registers the in-memory feed and hosts it on feed-agent.
- addThread declares the processor agent thread and its idle strategy.
- bootServer reads the config, starts both agents, and wires the broadcast feed to your handler.

## Equivalent YAML sketch

```yaml
# Feeds
eventFeeds:
  - instance: !!com.telamin.mongoose.connector.memory.InMemoryEventSource
    name: hello-feed
    agentName: feed-agent
    broadcast: true
    idleStrategy: !!com.fluxtion.agrona.concurrent.BusySpinIdleStrategy { }

# Handlers (processor on agent thread)
eventHandlers:
  - agentName: processor-agent
    idleStrategy: !!com.fluxtion.agrona.concurrent.BusySpinIdleStrategy { }
    eventHandlers:
      hello-handler:
        customHandler: !!com.telamin.mongoose.example.BuilderApiExampleHandler { }
        logLevel: INFO
```

Boot from YAML:

```java
var server = MongooseServer.bootServer(new java.io.StringReader(yaml), rec -> {});
```

## Separation of concerns in practice

- Write business logic once: Your event handler focuses on domain state and rules. No thread creation, no queue code.
- Reuse plugins: Select or build feeds/sinks/services as libraries. Swap them via config without changing business
  logic.
- Configure execution: Choose agent names, idle strategies, and logging via config. Ops can tune these independently.
- Lifecycle managed: The server owns init/start/stop; your components implement standard lifecycle if needed.