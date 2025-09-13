# Guide: Writing a Service Plugin for Mongoose server

This guide explains how to implement and integrate a custom Service into Mongoose server. A service is a component that
is registered with the server and can participate in lifecycle management and dependency injection. Services can be
simple (Lifecycle only) or worker/agent-hosted (with their own thread). They may also interact with the event flow when
they implement specific interfaces, but a “service plugin” here focuses on generic, reusable capabilities (admin,
metrics, adaptors, utilities, HTTP clients/servers, etc.).

You’ll learn:

- When to write a service
- Implementing a simple Lifecycle service
- Implementing a worker (agent-hosted) service
- Dependency injection via `@ServiceRegistered`
- Registering services via `ServiceConfig` and `MongooseServerConfig` (builder and programmatic)
- Naming, lookup, and interaction with other services
- Testing patterns and common pitfalls

References in this repository:

- [ServiceConfig.java]({{source_root}}/main/java/com/telamin/mongoose/config/ServiceConfig.java)
- [ServerConfigurator.java]({{source_root}}/main/java/com/telamin/mongoose/internal/ServerConfigurator.java)
- [MongooseServer.java]({{source_root}}/main/java/com/telamin/mongoose/MongooseServer.java)

## When to write a service

Create a service when you need reusable functionality that should be lifecycle-managed and injectable across processors
and other services. Typical use cases:

- Provide an admin or control API (e.g., register commands)
- Maintain shared state or caches available to multiple components
- Expose a client to external systems (HTTP/DB/KV) for processors to use
- Run background maintenance tasks or telemetry collection

If the service needs to run continuously and perform periodic work, consider implementing it as a worker service (
agent-hosted). If it just exposes methods and reacts to calls, a simple `Lifecycle` service is sufficient.

## Simple Lifecycle-based service (no agent)

A basic service implements `com.fluxtion.runtime.lifecycle.Lifecycle` (optional but recommended). The server will call
its lifecycle methods in order.

```java
package com.mycompany.service;

import com.fluxtion.runtime.lifecycle.Lifecycle;
import lombok.extern.java.Log;

@Log
public class MySimpleService implements Lifecycle {

    private String configValue;

    public MySimpleService(String configValue) {
        this.configValue = configValue;
    }

    @Override
    public void init() {
        log.info("MySimpleService.init");
        // allocate light resources
    }

    @Override
    public void start() {
        log.info("MySimpleService.start");
        // open connections, prepare state
    }

    @Override
    public void stop() {
        log.info("MySimpleService.stop");
        // flush/close resources (idempotent)
    }

    @Override
    public void tearDown() {
        stop(); // ensure clean release
    }

    // Business methods other components can use
    public String greet(String name) {
        return "Hello " + name + " with " + configValue;
    }
}
```

## Worker (agent-hosted) service

If your service needs its own thread to perform a periodic loop, implement `com.fluxtion.agrona.concurrent.Agent` (and
optionally `Lifecycle`). Worker services are registered in an agent group with a chosen idle strategy.

```java
package com.mycompany.service;

import com.fluxtion.agrona.concurrent.Agent;
import com.fluxtion.runtime.lifecycle.Lifecycle;
import lombok.extern.java.Log;

@Log
public class MyWorkerService implements Agent, Lifecycle {

    private volatile boolean running;
    private long lastRunNs;
    private long intervalNs = 50_000_000L; // 50ms

    @Override
    public void init() {
        log.info("MyWorkerService.init");
    }

    @Override
    public void start() {
        running = true;
        lastRunNs = System.nanoTime();
    }

    @Override
    public int doWork() {
        // Non-blocking loop; return >0 when work done to cooperate with idle strategy
        long now = System.nanoTime();
        if (now - lastRunNs >= intervalNs) {
            lastRunNs = now;
            // perform periodic maintenance / flush / poll
            return 1;
        }
        return 0;
    }

    @Override
    public String roleName() {
        return "MyWorkerService"; // used for diagnostics
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public void tearDown() {
        stop();
    }
}
```

Notes:

- Keep `doWork()` non-blocking; rely on the idle strategy to handle waiting.
- If you need IO, prefer time-bounded operations or non-blocking APIs.

## Dependency injection with @ServiceRegistered

Services can both inject and be injected into each other (and into processors) using the `@ServiceRegistered`
annotation. The server performs reflective injection during `registerService`.

```java
package com.mycompany.processor;

import com.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.mycompany.service.MySimpleService;

public class MyHandler extends ObjectEventHandlerNode {

    private MySimpleService myService;

    @ServiceRegistered
    public void wire(MySimpleService myService, String name) {
        // name is the registered service name
        this.myService = myService;
    }

    @Override
    protected boolean handleEvent(Object event) {
        if (myService != null) {
            String reply = myService.greet(String.valueOf(event));
            System.out.println("Processed: " + reply);
        }
        return true;
    }
}
```

Key points:

- The `@ServiceRegistered` method can accept the service instance and optionally the service name.
- Server injects newly registered services into all existing services and vice versa (single-target injection step),
  enabling loose coupling.

## Registering services with MongooseServerConfig (fluent builder)

Register services using `ServiceConfig`. For worker services, specify an agent group and an idle strategy.

```java
import com.fluxtion.agrona.concurrent.BusySpinIdleStrategy;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.ServiceConfig;

MySimpleService simple = new MySimpleService("cfgA");
MyWorkerService worker = new MyWorkerService();

ServiceConfig<MySimpleService> simpleCfg = ServiceConfig.<MySimpleService>builder()
        .service(simple)
        .serviceClass(MySimpleService.class)
        .name("simpleService")
        .build();

ServiceConfig<MyWorkerService> workerCfg = ServiceConfig.<MyWorkerService>builder()
        .service(worker)
        .serviceClass(MyWorkerService.class)
        .name("workerService")
        .agent("worker-agent-group", new BusySpinIdleStrategy())
        .build();

MongooseServerConfig app = MongooseServerConfig.builder()
        .addService(simpleCfg)
        .addService(workerCfg)
        .build();
```

When booting the server with this `MongooseServerConfig`, `ServerConfigurator` will:

- Register the simple service (as a regular `Service`)
- Register the worker service (as a `ServiceAgent` with the given agent group and idle strategy)
- Perform dependency injection between all registered services and processors

## Programmatic boot and lookup

You can also boot programmatically and inspect the registered services map.

```java
import com.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.mongoose.MongooseServer;

LogRecordListener logs = rec -> {};
MongooseServer server = MongooseServer.bootServer(app, logs);

try {
    var registry = server.registeredServices();
    var simpleSvc = registry.get("simpleService");
    Object instance = simpleSvc.instance();
    // cast and use
} finally {
    server.stop();
}
```

You may also boot from YAML using `MongooseServer.bootServer(Reader, LogRecordListener)`; YAML maps directly to
`MongooseServerConfig` and `ServiceConfig` fields.

## Worker services and agent threads

- A worker service must implement `com.fluxtion.agrona.concurrent.Agent`.
- In `ServiceConfig`, set `agent(groupName, idleStrategy)` to host it in a runner thread.
- The server selects an idle strategy using `MongooseServerConfig.getIdleStrategyOrDefault(...)` when necessary; per-group
  overrides are supported via `MongooseServerConfig.agentThreads`.

## Testing your service

- Unit-test simple services by constructing them directly and calling lifecycle methods in order (`init`, `start`,
  `stop`, `tearDown`).
- For worker services, test the `doWork()` loop logic in isolation; avoid real sleeping; simulate time or use small
  intervals.
- For integration tests with the server: build a small `MongooseServerConfig`, boot the server, and verify injection and
  interactions work.

Example test snippet for a simple service:

```java
MySimpleService svc = new MySimpleService("cfg");
svc.init();
svc.start();
String out = svc.greet("Alice");
org.junit.jupiter.api.Assertions.assertTrue(out.contains("Alice"));
svc.stop();
svc.tearDown();
```

## Common pitfalls and tips

- Make `stop()` idempotent; always close resources.
- Avoid long blocking in `Agent#doWork()`; keep it non-blocking with bounded work per call.
- Guard logs in hot paths using `log.isLoggable(...)` to reduce overhead.
- Provide clear service names in `ServiceConfig` for easier injection and debugging.
- If your service also participates in event flow (implements `EventFlowService`), the server will wire it to the
  `EventFlowManager` automatically during registration.

## See also

- Message sinks: [Writing a Message Sink Plugin](writing-a-message-sink-plugin.md)
- Event sources: [Writing an Event Source Plugin](writing-an-event-source-plugin.md)
- Configuration
  API: [ServiceConfig.java]({{source_root}}/main/java/com/telamin/mongoose/config/ServiceConfig.java),
  `MongooseServerConfig.Builder`
-

Bootstrapping: [ServerConfigurator.java]({{source_root}}/main/java/com/telamin/mongoose/internal/ServerConfigurator.java),
`MongooseServer`
