# How to inject initial config into a processor

This guide shows how to inject configuration into your event processors at server boot when using the fluent MongooseServerConfig
builder.

When to use this:

- Provide per-processor settings (thresholds, file names, feature flags)
- Avoid global/static config and keep processor code portable

Key pieces in this repository:

- MongooseServerConfig/EventProcessorGroupConfig/EventProcessorConfig builder APIs
- [Config map container]({{source_root}}/main/java/com/telamin/mongoose/config/ConfigMap.java)
  for type-safe lookup
- [Config Listener]({{source_root}}/main/java/com/telamin/mongoose/config/ConfigListener.java)
  interface for receiving initial configuration
- Helper
  wrapper: [Config dispatch wrapper]({{source_root}}/main/java/com/telamin/mongoose/internal/ConfigAwareEventProcessor.java)

## 1) How config is delivered at boot

During server boot, each processor is constructed and the server delivers the initial configuration to any processor
that exports ConfigListener.

Implications:

- If your component (or its wrapper) implements/exports ConfigListener, it will receive the initial configuration before
  events start flowing.
- For ObjectEventHandlerNode-based processors, the runtime wraps your node in ConfigAwareEventProcessor, which also
  implements ConfigListener and forwards the config to your node.

## 2) Make your component receive initial config

create a class that extends ObjectEventHandlerNode and implements ConfigListener.

EventProcessorConfig can accept an ObjectEventHandlerNode directly. You can have your handler implement ConfigListener
to receive the config. At runtime this handler is wrapped in a DefaultEventProcessor (via ConfigAwareEventProcessor)
and participates like any other processor.

```java
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.mongoose.config.ConfigListener;
import com.telamin.mongoose.config.ConfigMap;
import com.telamin.mongoose.config.EventProcessorConfig;

class MyConfigAwareHandler 
        extends ObjectEventHandlerNode 
        implements ConfigListener {

    private String greeting;
    private int threshold;

    @Override
    public boolean initialConfig(ConfigMap config) {
        this.greeting = config.getOrDefault(ConfigKey.of("greeting", String.class), "");
        this.threshold = config.getOrDefault(ConfigKey.of("threshold", Integer.class), 0);
        return true;
    }

    @Override
    public boolean handleEvent(Object event) {
        // your event handling using greeting/threshold
        return true;
    }
}
```

## 3) Add config via the fluent builder

Use EventProcessorConfig.Builder.putConfig(key, value) for each entry.

```java
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.EventProcessorConfig;
import com.telamin.mongoose.config.EventProcessorGroupConfig;

MyConfigAwareHandler handler = new MyConfigAwareHandler();

// Register the handler using customHandler on the builder
EventProcessorConfig<?> built = EventProcessorConfig.builder()
        .customHandler(handler)
        .putConfig("greeting", "hello")
        .putConfig("threshold", 10)
        .build();
```

At boot, the server will deliver this map to the handler if it exports ConfigListener.

## 4) End-to-end example (runnable test)

Check this test that boots a server using the fluent API and verifies the initial config is delivered:
[InitialConfigFluentBootTest.java]({{source_root}}/test/java/com/telamin/mongoose/config/InitialConfigFluentBootTest.java)

Complete how-to example: [Injecting Config into a Processor Example](https://github.com/telaminai/mongoose-examples/blob/229e01e2f508bdf084a611677dc93c1174c96bdc/how-to/injecting-config-into-a-processor)

## 5) Tips

- Prefer ConfigKey for type-safe lookups from ConfigMap.
- Keep configuration keys stable and documented near your processor code.
- You can mix MongooseServerConfig-provided config with dynamic config updates later by defining your own config update events and
  handling them in the processor.
