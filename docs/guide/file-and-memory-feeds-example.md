# Example: Wiring File and In‑Memory Event Sources to a File Sink using MongooseServerConfig (Fluent API)

This guide shows how to:

- Extend ObjectEventHandlerNode to build a simple processor that receives all events.
- Configure two event sources: FileEventSource and InMemoryEventSource.
- Configure a FileMessageSink as an output.
- Bind everything together with the fluent MongooseServerConfig builder.

The processor receives events from both sources and writes them to a file sink.

## 1) Create a custom handler

Extend ObjectEventHandlerNode and inject the FileMessageSink using @ServiceRegistered, see
[BuilderApiExampleHandler]({{source_root}}/test/java/com/telamin/mongoose/example/BuilderApiExampleHandler.java).
In handleEvent, forward the incoming event to the sink:

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

Notes:

- FileMessageSink extends AbstractMessageSink and provides accept(value) for publishing.
- The sink is injected automatically by the server when registered as a service.

## 2) Configure sources, processor, and sink via MongooseServerConfig

Use the builder APIs for EventFeedConfig, EventProcessorGroupConfig, and register the sink as a service.

```java
Path inputFile = Paths.get("/path/to/input/events.txt");
Path outputFile = Paths.get("/path/to/output/out.log");

FileMessageSink fileSink = new FileMessageSink();
fileSink.setFilename(outputFile.toString());

FileEventSource fileSource = new FileEventSource();
fileSource.setFilename(inputFile.toString());
fileSource.setCacheEventLog(true);

InMemoryEventSource<String> inMemSource = new InMemoryEventSource<>();
inMemSource.setCacheEventLog(true);

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

MongooseServerConfig mongooseServerConfig = MongooseServerConfig.builder()
        .addProcessorGroup(processorGroup)
        .addEventFeed(fileFeedCfg)
        .addEventFeed(memFeedCfg)
        .addEventSink(sinkCfg)
        .build();
```

## 3) Boot the server and send events

```java
MongooseServer server = MongooseServer.bootServer(mongooseServerConfig, rec -> {});

try {
    // Stimulate sources: write to input file and offer memory events
    Files.writeString(inputFile, "file-1\nfile-2\n", StandardCharsets.UTF_8);

    // Access the in-memory source via registered services
    Map<Service<?>> services = server.registeredServices();
    InMemoryEventSource<String> registeredMem = services.get("inMemFeed").instance();
    registeredMem.offer("mem-1");
    registeredMem.offer("mem-2");

    // Allow agents to process. Spin-wait up to a few seconds for output lines.
    List<String> lines = waitForLines(outputFile, 4, 5, TimeUnit.SECONDS);
    Assertions.assertTrue(
            lines.containsAll(List.of("file-1", "file-2", "mem-1", "mem-2")),
            () -> "Missing expected lines in sink: " + lines);
} finally {
    server.stop();
}
```

The example
test [BuilderApiFluentExampleTest]({{source_root}}/test/java/com/telamin/mongoose/example/BuilderApiFluentExampleTest.java)
demonstrates the complete flow and asserts that the sink contains these
events from both sources:

- file-1, file-2 (from FileEventSource)
- mem-1, mem-2 (from InMemoryEventSource)

## Tips

- FileEventSource supports caching and replay across startComplete. Using setCacheEventLog(true) helps capture pre-start
  data.
- InMemoryEventSource supports offer(item) and respects caching similarly.
- You can register sinks either via EventSinkConfig (when your sink type matches its generic bound) or simply as a
  Service using ServiceConfig.
