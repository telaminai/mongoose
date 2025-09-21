# Hello Mongoose: your first Mongoose app

This is the first on‑ramp tutorial to get a developer up and running with Mongoose Server in minutes. You will:

- Run a minimal app that boots a Mongoose server
- Publish a couple of events from an in‑memory event source
- Handle those events in a simple handler and print them

References:

- Stand‑alone quickstart github project: [hellomongoose](https://github.com/telaminai/hellomongoose)  
- Example code in this repository: https://github.com/telaminai/hellomongoose/blob/main/src/main/java/com/telamin/mongoose/example/hellomongoose/HelloMongoose.java

## Prerequisites

- Java 17+
- Maven 3.8+
- Access to the dependency `com.telamin:mongoose` from maven central

## Option A: Use the quickstart repository (fastest)

Clone and run the ready‑to‑use example:

```
git clone https://github.com/telaminai/hellomongoose
cd hellomongoose
mvn -q package
java -jar target/hellomongoose-1.0.0-SNAPSHOT-shaded.jar
```

Expected output:

```
thread:'main' publishing events

thread:'processor-agent' Got event: hi
thread:'processor-agent' Got event: mongoose
```

Open the main class in your IDE for a walkthrough:

- Main class FQN: `com.telamin.mongoose.example.hellomongoose.HelloMongoose`

## Code explained (events and threads)

Below is the minimal main class used by the quickstart. You don’t need to paste it into a new project to follow this guide; run the cloned repo, then read this to understand what’s happening with events and threads.

```java
package com.example.hellomongoose;

import com.fluxtion.agrona.concurrent.BusySpinIdleStrategy;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.mongoose.config.EventFeedConfig;
import com.telamin.mongoose.config.EventProcessorConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.ThreadConfig;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;

import static com.telamin.mongoose.MongooseServer.bootServer;

public final class HelloMongoose {
    public static void main(String[] args) {
        // 1) Business logic handler running on the processor agent thread
        var handler = new ObjectEventHandlerNode() {
            @Override
            protected boolean handleEvent(Object event) {
                if (event instanceof String s) {
                    System.out.println("thread:'" + Thread.currentThread().getName() + "' Got event: " + s);
                }
                return true;
            }
        };

        // 2) Build in‑memory event feed
        var feed = new InMemoryEventSource<String>();

        // 3) Build server configuration with builder APIs
        var eventProcessorConfig = EventProcessorConfig.builder()
                .customHandler(handler)
                .build();

        var feedConfig = EventFeedConfig.<String>builder()
                .instance(feed)
                .name("hello-feed")
                .broadcast(true)
                .agent("feed-agent", new BusySpinIdleStrategy())
                .build();

        var threadConfig = ThreadConfig.builder()
                .agentName("processor-agent")
                .idleStrategy(new BusySpinIdleStrategy())
                .build();

        var app = MongooseServerConfig.builder()
                .addProcessor("processor-agent", "hello-handler", eventProcessorConfig)
                .addEventFeed(feedConfig)
                .addThread(threadConfig)
                .build();

        var server = bootServer(app, rec -> { /* optional log listener */ });

        // 4) Publish a few events
        System.out.println("thread:'" + Thread.currentThread().getName() + "' publishing events\n");
        feed.offer("hi");
        feed.offer("mongoose");

        // 5) Cleanup (in a real app keep running)
        server.stop();
    }
}
```

What’s happening with events and threads:
- main thread prints "publishing events" and calls feed.offer(...). Those calls happen on the main (user) thread.
- feed-agent thread hosts the InMemoryEventSource because the feedConfig sets agent("feed-agent", ...). That agent is a cooperative loop using BusySpinIdleStrategy.
- processor-agent thread executes your ObjectEventHandlerNode. The handler’s handleEvent runs only on this agent thread.
- Cross-thread delivery uses Mongoose queues: events published by the feed are enqueued and dispatched to the processor.
- broadcast(true) means all processors receive the events without explicit subscriptions. In this example there’s just one processor (hello-handler).
- BusySpinIdleStrategy yields very low latency; you’ll see thread names like 'processor-agent' in the output.



## What’s next?

- Five‑minute handler tutorial: guide/five-minute-event-handler-tutorial.md
- Fluent API example (file and memory feeds): guide/file-and-memory-feeds-example.md
- YAML configuration example: guide/file-and-memory-feeds-yaml-example.md
- Architecture overview: overview/engineers-overview.md
