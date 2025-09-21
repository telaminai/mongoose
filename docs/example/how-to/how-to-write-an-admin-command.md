# Guide: Writing an Admin Command for Mongoose server

This guide explains how to add operational/admin commands to your Mongoose server application. Admin commands are
lightweight functions that you register at runtime and can invoke via a CLI or programmatically to inspect or control
the system (list services, stop processors, flush caches, custom health checks, etc.).

You’ll learn:

- What the AdminCommand API is and how it works
- How to register commands from processors and services using AdminCommandRegistry
- Command function signature, arguments, and output/error channels
- How commands are dispatched through the event flow (asynchronously) or executed directly
- How to invoke commands from a CLI or programmatically
- Tips, patterns, and references in this repository

## Sample code

For a complete, runnable example that demonstrates all the concepts covered in this guide, see:

- [Writing an Admin Command Example](https://github.com/telaminai/mongoose-examples/tree/main/how-to/writing-an-admin-command) A comprehensive Maven project showing how to register admin commands from processors and services, wire admin infrastructure, and invoke commands programmatically.


## Key types

- AdminCommandRegistry — central registry to add and invoke commands
    - package: `com.telamin.mongoose.service.admin`
- AdminFunction<OUT, ERR> — your command function signature
    - `void processAdminCommand(List<String> args, Consumer<OUT> out, Consumer<ERR> err)`
- AdminCommandRequest — DTO for programmatic invocation (name, args, output consumers)
- AdminCommandProcessor — service that routes commands through the event flow and hosts built-ins (
  help/commands/eventSources)
- CliAdminCommandProcessor — optional interactive console to type commands
- MongooseServerAdmin — sample “server control” commands (list services/processors, stop processors)

References:

- [AdminCommandRegistry.java]({{source_root}}/main/java/com/telamin/mongoose/service/admin/AdminCommandRegistry.java)
- [AdminFunction.java]({{source_root}}/main/java/com/telamin/mongoose/service/admin/AdminFunction.java)
- [AdminCommandRequest.java]({{source_root}}/main/java/com/telamin/mongoose/service/admin/AdminCommandRequest.java)
- [AdminCommandProcessor.java]({{source_root}}/main/java/com/telamin/mongoose/service/admin/impl/AdminCommandProcessor.java)
- [CliAdminCommandProcessor.java]({{source_root}}/main/java/com/telamin/mongoose/service/admin/impl/CliAdminCommandProcessor.java)
- [MongooseServerAdmin.java]({{source_root}}/main/java/com/telamin/mongoose/service/servercontrol/MongooseServerAdmin.java)

## How it works

At startup you register an AdminCommandProcessor as a service. Other services and processors can inject the
AdminCommandRegistry (via @ServiceRegistered) and register commands. When a command is invoked:

- If the registering component was an event processor (i.e., currentProcessor present during registration), the command
  is wired to publish into that processor’s input queue as an AdminCommand event, which is executed by
  AdminCommandInvoker on the processor’s agent thread (asynchronous, back‑pressure aware).
- If registered outside a processor context (e.g., a plain service at init/start time), the command executes directly in
  the caller thread.

This lets you choose between async delivery into a processor’s single-threaded context, or immediate synchronous
execution.

## Registering a command

Register from a processor or a service using @ServiceRegistered to obtain the registry.

Example (from a processor):

```java
package com.mycompany.ops;

import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;

import java.util.List;
import java.util.function.Consumer;

public class OpsHandler extends ObjectEventHandlerNode {

    @ServiceRegistered
    public void registerAdmin(AdminCommandRegistry admin, String name) {
        // name is the AdminCommandRegistry service name
        admin.registerCommand("ops.ping", this::ping);
        admin.registerCommand("ops.echo", this::echo);
    }

    private void ping(List<String> args, Consumer<Object> out, Consumer<Object> err) {
        out.accept("pong");
    }

    private void echo(List<String> args, Consumer<Object> out, Consumer<Object> err) {
        // args[0] is the command name, args[1..] are user args
        out.accept(String.join(" ", args));
    }
}
```

Example (from a service):

```java
public class OpsService implements com.telamin.fluxtion.runtime.lifecycle.Lifecycle {
    private com.telamin.mongoose.service.admin.AdminCommandRegistry registry;

    @com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered
    public void admin(AdminCommandRegistry registry) { this.registry = registry; }

    @Override public void start() {
        registry.registerCommand("ops.time", (args, out, err) -> out.accept(java.time.Instant.now().toString()));
    }

    @Override public void init() {}
    @Override public void stop() {}
    @Override public void tearDown() {}
}
```

## Wiring admin infrastructure in MongooseServerConfig

You need to register the admin services in your application.

```java
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.ServiceConfig;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.admin.impl.AdminCommandProcessor;
import com.telamin.mongoose.service.admin.impl.CliAdminCommandProcessor;

// Admin registry/dispatcher service
ServiceConfig<AdminCommandRegistry> adminSvc = ServiceConfig.<AdminCommandRegistry>builder()
        .service(new AdminCommandProcessor())
        .serviceClass(AdminCommandRegistry.class)
        .name("adminService")
        .build();

// Optional: interactive CLI running on the JVM stdin/stdout
ServiceConfig<?> cliSvc = ServiceConfig.builder()
        .service(new CliAdminCommandProcessor())
        .name("adminCli")
        .build();

MongooseServerConfig app = MongooseServerConfig.builder()
        // add your processors and other services...
        .addService(adminSvc)
        .addService(cliSvc)  // optional
        .build();
```

Notes:

- AdminCommandProcessor exposes default commands: `help`, `?`, `commands`, `eventSources`.
- MongooseServerAdmin registers higher-level server operations (list services/processors, stop processors). To use it:

```java
ServiceConfig<?> serverAdmin = ServiceConfig.builder()
        .service(new MongooseServerAdmin())
        .name("serverAdmin")
        .build();

app = MongooseServerConfig.builder()
        // ...
        .addService(adminSvc)
        .addService(serverAdmin)
        .build();
```

## Invoking a command

There are two common ways:

1) From the CLI (if CliAdminCommandProcessor is registered)

- At runtime type: `commands` to list available commands
- Example: `server.processors.list`
- Example: `ops.echo hello world`

2) Programmatically using AdminCommandRegistry

```java
import com.telamin.mongoose.service.admin.AdminCommandRequest;

var request = new AdminCommandRequest();
request.setCommand("ops.echo");
request.setArguments(java.util.List.of("hello", "world"));
request.setOutput(System.out::println);
request.setErrOutput(System.err::println);

// obtain the registry from MongooseServer.registeredServices()
Service<?> svc = server.registeredServices().get("adminService");
AdminCommandRegistry registry = (AdminCommandRegistry) svc.instance();

registry.processAdminCommandRequest(request);
```

What happens under the hood:

- The AdminCommandProcessor looks up your registered command. If it was registered inside a processor context, it
  publishes an AdminCommand event into that processor’s input queue and the AdminCommandInvoker executes it on the
  processor’s agent thread. Otherwise, it executes immediately in the caller thread.

## Command function signature and args

Your command implements:

```java
void processAdminCommand(List<String> args, Consumer<OUT> out, Consumer<ERR> err)
```

- args contains the full tokenized input including the command name at index 0 (e.g., ["ops.echo", "hello", "world"]).
- Use `out.accept(...)` for normal output, `err.accept(...)` for warnings/errors.
- Prefer short, dash‑separated names (e.g., `cache.clear`, `server.service.list`).

## Tips and patterns

- Keep commands small and fast. If you need to run in a processor context, the infrastructure will deliver your command
  asynchronously to that single‑threaded agent.
- Validate args and produce helpful `err` messages; don’t throw unless exceptional.
- For long operations, consider returning a quick acknowledgement and performing the work asynchronously; stream
  progress to `out` if appropriate.
- Use `commands` and `help` to explore what’s registered at runtime.
- Combine with MongooseServerAdmin for common operational tasks.

## Example end‑to‑end

For a complete, runnable example that demonstrates all the concepts covered in this guide, see:

- [Writing an Admin Command Example](https://github.com/telaminai/mongoose-examples/tree/main/how-to/writing-an-admin-command) - A comprehensive Maven project showing how to register admin commands from processors and services, wire admin infrastructure, and invoke commands programmatically.

Additional references:

- [BroadcastCallbackTest.java]({{source_root}}/test/java/com/telamin/mongoose/dispatch/BroadcastCallbackTest.java)
- [MongooseServerAdmin.java]({{source_root}}/main/java/com/telamin/mongoose/service/servercontrol/MongooseServerAdmin.java)
  These show wiring the admin registry, adding commands, and optional CLI usage.
