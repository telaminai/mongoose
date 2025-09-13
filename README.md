# Fluxtion Server

# [Main Documentation](https://gregv12.github.io/fluxtion-server/)

Fluxtion Server is a high‑performance, event‑driven server framework for building scalable, composable event processing
applications. It wires event sources, processors, sinks, and services, and manages their lifecycle and threading so you
can focus on business logic.

## Why Fluxtion Server?

- Performance: Agent‑based concurrency with configurable idle strategies enables very high throughput and predictable
  latency.
- Ease of development: Compose processors and services, configure via YAML or Java, and get built‑in lifecycle and
  service injection.
- Plugin architecture: Clean extension points for event feeds, sinks, services, and dispatch strategies so you can
  tailor the runtime.
- Operational control: Admin commands, scheduling, logging/audit support, and dynamic registration make operations
  simpler.

## Minimal bootstrap from code:

```java
EventProcessorGroupConfig processorGroup = EventProcessorGroupConfig.builder()
        .agentName("processor-agent")
        .put("example-processor", new EventProcessorConfig(new BuilderApiExampleHandler()))
        .build();

EventFeedConfig<?> fileFeedCfg = EventFeedConfig.builder()
        .instance(fileSource)
        .name("fileFeed")
        .broadcast(true)
        .agent("file-source-agent", new BusySpinIdleStrategy())
        .build();

EventFeedConfig<?> memFeedCfg = EventFeedConfig.builder()
        .instance(inMemSource)
        .name("inMemFeed")
        .broadcast(true)
        .agent("memory-source-agent", new BusySpinIdleStrategy())
        .build();

EventSinkConfig<FileMessageSink> sinkCfg = EventSinkConfig.<FileMessageSink>builder()
        .instance(fileSink)
        .name("fileSink")
        .build();

AppConfig mongooseServerConfig = AppConfig.builder()
        .addProcessorGroup(processorGroup)
        .addEventFeed(fileFeedCfg)
        .addEventFeed(memFeedCfg)
        .addEventSink(sinkCfg)
        .build();

FluxtionServer server = FluxtionServer.bootServer(mongooseServerConfig, logRecordListener);
```

## License

AGPL-3.0-only. See LICENSE for details.

