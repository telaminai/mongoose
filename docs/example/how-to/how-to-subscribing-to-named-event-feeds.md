# How to subscribe to specific named EventFeeds (and ignore others)

This guide shows how to subscribe to multiple EventFeeds by name using several named InMemoryEventSource inputs, and
ignore any feeds whose names don’t match. We will:

- Name each EventFeed via EventFeedConfig.name("...")
- Subscribe to selected feed names from a processor using getContext().subscribeToNamedFeed("...")
- Forward only events from the selected feeds to a sink

Below we create three in-memory feeds: prices, orders, news. Our processor forwards only prices and news items to a
sink, ignoring orders entirely.

## Sample code

- Test
  source: [NamedFeedsSubscriptionExampleTest.java]({{source_root}}/test/java/com/telamin/mongoose/example/NamedFeedsSubscriptionExampleTest.java)
- Processor
  node: [NamedFeedsFilterHandler.java]({{source_root}}/test/java/com/telamin/mongoose/example/NamedFeedsFilterHandler.java)
- Complete how-to example: [Subscribing to Named Event Feeds Example](https://github.com/telaminai/mongoose-examples/blob/229e01e2f508bdf084a611677dc93c1174c96bdc/how-to/subscribing-to-named-event-feeds)

### Processor handler that subscribes to specific feed names

```java
package com.telamin.mongoose.example;

import com.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.fluxtion.runtime.output.MessageSink;

import java.util.Set;

/**
 * Example processor that only subscribes and forwards events from specific named EventFeeds.
 */
public class NamedFeedsFilterHandler extends ObjectEventHandlerNode {

    private final Set<String> acceptedFeedNames;
    private MessageSink<String> sink;

    public NamedFeedsFilterHandler(Set<String> acceptedFeedNames) {
        this.acceptedFeedNames = acceptedFeedNames;
    }

    @ServiceRegistered
    public void wire(MessageSink<String> sink, String name) {
        this.sink = sink;
    }

    @Override
    public void start() {
        acceptedFeedNames.forEach(feedName -> getContext().subscribeToNamedFeed(feedName));
    }

    @Override
    protected boolean handleEvent(Object event) {
        if (sink == null || event == null) {
            return true;
        }
        // In this example, the feed payload is a String, so just forward it
        if (event instanceof String payload) {
            sink.accept(payload);
        }
        return true;
    }
}
```

Notes:

- The example above subscribes at start() time to the selected feed names.
- The payload type is String in our example; adjust handling if your payload type differs.

### Wiring several named InMemoryEventSource feeds

- Build an in-memory sink (MessageSink<String>) that collects messages
- Create three InMemoryEventSource<String> instances
- Register three EventFeedConfig entries with names: prices, orders, news
- Add the filter processor and the sink to MongooseServerConfig
- Boot the server and send events

Snippet from the test setup:

```java
import com.fluxtion.agrona.concurrent.BusySpinIdleStrategy;
import com.fluxtion.runtime.output.MessageSink;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.*;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.connector.memory.InMemoryMessageSink;

import java.util.List;
import java.util.Set;

// In-memory sink
InMemoryMessageSink memSink = new InMemoryMessageSink();

// Feeds: three named in-memory sources
InMemoryEventSource<String> prices = new InMemoryEventSource<>();
prices.setCacheEventLog(true);
InMemoryEventSource<String> orders = new InMemoryEventSource<>();
orders.setCacheEventLog(true);
InMemoryEventSource<String> news = new InMemoryEventSource<>();
news.setCacheEventLog(true);

// Processor that only forwards events from feeds: prices, news
NamedFeedsFilterHandler filterHandler = new NamedFeedsFilterHandler(Set.of("prices", "news"));

EventProcessorGroupConfig processorGroup = EventProcessorGroupConfig.builder()
        .agentName("processor-agent")
        .put("filter-processor", new EventProcessorConfig(filterHandler))
        .build();

// Build EventFeed configs with names
EventFeedConfig<?> pricesFeed = EventFeedConfig.builder()
        .instance(prices)
        .name("prices")
        .agent("prices-agent", new BusySpinIdleStrategy())
        .build();

EventFeedConfig<?> ordersFeed = EventFeedConfig.builder()
        .instance(orders)
        .name("orders")
        .agent("orders-agent", new BusySpinIdleStrategy())
        .build();

EventFeedConfig<?> newsFeed = EventFeedConfig.builder()
        .instance(news)
        .name("news")
        .agent("news-agent", new BusySpinIdleStrategy())
        .build();

EventSinkConfig<MessageSink<?>> sinkCfg = EventSinkConfig.<MessageSink<?>>builder()
        .instance(memSink)
        .name("memSink")
        .build();

MongooseServerConfig mongooseServerConfig = MongooseServerConfig.builder()
        .addProcessorGroup(processorGroup)
        .addEventFeed(pricesFeed)
        .addEventFeed(ordersFeed)
        .addEventFeed(newsFeed)
        .addEventSink(sinkCfg)
        .build();

MongooseServer server = MongooseServer.bootServer(mongooseServerConfig, rec -> {});
```

Publish some events and observe only selected feeds are forwarded:

```java
prices.offer("p1");
prices.offer("p2");
orders.offer("o1");
orders.offer("o2");
news.offer("n1");
news.offer("n2");

// Expect only: p1, p2, n1, n2 in the sink
```

Only p1, p2 and n1, n2 will be forwarded by the processor; o1 and o2 are ignored because their feed name is not in the
accepted set.
