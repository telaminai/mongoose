# Example: Wiring File and In‑Memory Event Sources to a File Sink using YAML configuration

This guide shows the same scenario as the fluent builder example, but using a YAML configuration loaded by
`MongooseServer.bootServer(Reader, ...)`.

What you will do:

- Create a custom processor by extending `ObjectEventHandlerNode` that publishes to a file sink.
- Configure two event sources: `FileEventSource` and `InMemoryEventSource`.
- Configure a `FileMessageSink` as an output.
- Bind everything together using YAML instead of Java builder APIs.

The processor receives events from both sources and writes them to a file sink.

## 1) Custom handler

We reuse the same handler as the fluent example: `BuilderApiExampleHandler` — it accepts any event and publishes it to
the injected sink.
Source: `src/test/java/com/telamin/mongoose/example/BuilderApiExampleHandler.java`

```java
public class BuilderApiExampleHandler extends ObjectEventHandlerNode {

    private MessageSink fileSink;

    @ServiceRegistered
    public void wire(MessageSink fileSink, String name) {
        this.fileSink = fileSink;
    }

    @Override
    protected boolean handleEvent(Object event) {
        if (fileSink != null && event != null) {
            fileSink.accept(event.toString());
        }
        return true;
    }
}
```

## 2) YAML configuration

The YAML wires two event feeds and one sink, and registers the handler in a processor group. Replace the file paths
with your desired locations.

```yaml
# --------- EVENT INPUT FEEDS BEGIN CONFIG ---------
eventFeeds:
  - instance: !!com.telamin.mongoose.connector.file.FileEventSource
      filename: /path/to/input/events.txt
      cacheEventLog: true
    name: fileFeed
    agentName: file-source-agent
    broadcast: true
    idleStrategy: !!com.fluxtion.agrona.concurrent.BusySpinIdleStrategy { }
  - instance: !!com.telamin.mongoose.connector.memory.InMemoryEventSource { cacheEventLog: true }
    name: inMemFeed
    agentName: memory-source-agent
    broadcast: true
    idleStrategy: !!com.fluxtion.agrona.concurrent.BusySpinIdleStrategy { }
# --------- EVENT INPUT FEEDS END CONFIG ---------

# --------- EVENT SINKS BEGIN CONFIG ---------
eventSinks:
  - instance: !!com.telamin.mongoose.connector.file.FileMessageSink
      filename: /path/to/output/out.log
    name: fileSink
# --------- EVENT SINKS END CONFIG ---------

# --------- EVENT HANDLERS BEGIN CONFIG ---------
eventHandlers:
  - agentName: processor-agent
    idleStrategy: !!com.fluxtion.agrona.concurrent.BusySpinIdleStrategy { }
    eventHandlers:
      example-processor:
        customHandler: !!com.telamin.mongoose.example.BuilderApiExampleHandler { }
        logLevel: INFO
# --------- EVENT HANDLERS END CONFIG ---------
```

Notes:

- `eventFeeds[].instance` holds the actual event-source object; we set properties such as `filename` and `cacheEventLog`
  directly.
- `broadcast: true` ensures the feed is broadcast to all processors.
- The sink is registered via `eventSinks` with an instance of `FileMessageSink` and its `filename`.
- The processor group registers `BuilderApiExampleHandler` as a `customHandler` under the name `example-processor`.

## 3) Boot the server and send events

```java
String yaml = Files.readString(Path.of("/path/to/config.yaml"));
MongooseServer server = MongooseServer.bootServer(new StringReader(yaml), rec -> {});

try {
    // Write file events
    Files.writeString(Path.of("/path/to/input/events.txt"), "file-1\nfile-2\n", StandardCharsets.UTF_8);

    // Offer in-memory events via the registered service
    @SuppressWarnings("unchecked")
    var inMem = server.registeredServices().get("inMemFeed").instance();
    inMem.offer("mem-1");
    inMem.offer("mem-2");

    // Read output lines from /path/to/output/out.log and verify they include all four events
} finally {
    server.stop();
}
```

## 4) Complete, runnable test

See [YamlConfigFeedsExampleTest](https://github.com/gregv12/fluxtion-server/blob/main/src/test/java/com/telamin/mongoose/example/YamlConfigFeedsExampleTest.java)
for a full end-to-end test that builds the YAML string programmatically with temporary file paths, boots the server,
stimulates both sources, and asserts the sink content.

## Tips

- `FileEventSource` and `InMemoryEventSource` support pre-start caching via `cacheEventLog: true` so that data produced
  before `startComplete` is not lost.
- You can declare additional services using `services:` in YAML if your handler needs more dependencies.
- If you want to run a sink or source on its own agent thread, ensure the instance implements
- `com.fluxtion.agrona.concurrent.Agent` and add `agentName` and `idleStrategy` to the respective config section.
