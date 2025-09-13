# How to core‑pin agent threads

This how‑to shows you how to pin Mongoose server agent threads to specific CPU cores for improved determinism and
reduced context switching on supported platforms.

What you’ll do:

- Configure per‑agent core IDs in MongooseServerConfig using the fluent builder
- Understand when and how the pinning is applied at runtime
- Optionally enable OS‑level pinning via OpenHFT Affinity
- Verify and troubleshoot your setup

Prerequisites:

- Mongoose server 0.2.4+ (this repo) with the built‑in best‑effort pinning hook
- Java 17+
- Optional: net.openhft:affinity on your classpath to perform actual OS‑level affinity pinning

References in this repo:

- [Optional test]({{source_root}}/test/java/com/telamin/mongoose/internal/CoreAffinityOptionalTest.java)
- [POM entry for optional Affinity dependency](https://github.com/gregv12/fluxtion-server/blob/main/pom.xml) (artifact
  net.openhft:affinity)

## 1) Configure core IDs with the fluent MongooseServerConfig builder

Pinning is configured per agent group using ThreadConfig.coreId (zero‑based CPU index). The server will apply the pin on
the agent thread during onStart.

```java
import com.fluxtion.agrona.concurrent.BusySpinIdleStrategy;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.ThreadConfig;

MongooseServerConfig mongooseServerConfig = MongooseServerConfig.builder()
    // Processor agent group pinned to core 0
    .addThread(ThreadConfig.builder()
        .agentName("processor-agent")
        .idleStrategy(new BusySpinIdleStrategy())
        .coreId(0) // zero-based index
        .build())
    // Service agent group pinned to core 2
    .addThread(ThreadConfig.builder()
        .agentName("service-agent")
        .coreId(2)
        .build())
    // ... add your processor groups, feeds, sinks, services
    .build();
```

That’s it for configuration. When the server boots and each agent thread starts, it will attempt to pin to the
configured core.

## 2) How it works at runtime

- MongooseServer resolves the configured core ID for each agent by name using resolveCoreIdForAgentName(String).
- Both agent types call the pin helper in their onStart():
    - ComposingEventProcessorAgent
    - ComposingServiceAgent
- Pinning is performed by CoreAffinity.pinCurrentThreadToCore(int), which uses reflection to call OpenHFT Affinity if
  present. If the library is not available, the helper logs an info message and no‑ops.

Key code paths:

- [CoreAffinity.java]({{source_root}}/main/java/com/telamin/mongoose/internal/CoreAffinity.java)
- [MongooseServer.java#resolveCoreIdForAgentName]({{source_root}}/main/java/com/telamin/mongoose/MongooseServer.java#L726)
- [ComposingEventProcessorAgent.java#onStart]({{source_root}}/main/java/com/telamin/mongoose/dutycycle/ComposingEventProcessorAgent.java#L233)
- [ComposingServiceAgent.java#onStart]({{source_root}}/main/java/com/telamin/mongoose/dutycycle/ComposingServiceAgent.java#L89)

## 3) Enable OS‑level pinning (optional)

To actually apply CPU affinity at the OS level, add OpenHFT Affinity to your project. This repository shows it as an
optional, test‑scoped dependency:

```xml
<dependency>
  <groupId>net.openhft</groupId>
  <artifactId>affinity</artifactId>
  <version>3.27ea0</version>
  <scope>test</scope>
  <optional>true</optional>
</dependency>
```

Notes:

- You can also add it with runtime scope in your application if you want pinning in production.
- Version may vary; use a version available in your repository.

## 4) Verifying pinning

- Quick smoke check (optional test): run CoreAffinityOptionalTest. It skips automatically if OpenHFT is not present, and
  passes if reflection succeeds.
- Operational verification: on Linux, you can inspect thread affinity with tools like `taskset -cp <pid>` or via OS perf
  tools, once OpenHFT Affinity is active in your runtime.

## 5) FAQs and troubleshooting

- Q: What happens if I don’t add OpenHFT Affinity?
    - A: The server logs an info message and continues without pinning. No errors are thrown.

- Q: What core IDs can I use?
    - A: Core IDs are zero‑based and OS‑dependent. Ensure the indices exist on your machine. For NUMA/SMT topologies,
      benchmark to find the best placement.

- Q: Does pinning affect all agents?
    - A: Only agents that have a matching ThreadConfig entry with a non‑null coreId and an agentName that matches the
      agent group name.

- Q: Any caveats?
    - A: Pinning may improve tail latency but can reduce the OS scheduler’s flexibility. Always measure your workload.

## 6) Related reading

- [Threading overview and agent lifecycles](../architecture/threading-model.md#optional-core-pinning-for-agent-threads)
- [Fluent MongooseServerConfig examples]({{source_root}}/test/java/com/telamin/mongoose/example/BuilderApiFluentExampleTest.java)
