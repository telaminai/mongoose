# Server control and admin commands

This document describes how runtime control is exposed in Mongoose server via admin commands and how you can add your
own commands on the fly. It also lists the default commands that are available out of the box.

Related reading:

- How-to: Add an admin command → [write an admin command](../example/how-to/how-to-write-an-admin-command.md)
- Programmatic control
  API → [MongooseServerController.java]({{source_root}}/main/java/com/telamin/mongoose/service/servercontrol/MongooseServerController.java)

## Overview

Mongoose server exposes an administrative command plane that lets you:

- Inspect and operate on the running system (e.g., list queues/event sources)
- Register custom commands from your processors and services
- Route command execution through the event flow so results are produced on the correct processor thread

Exposing the registry externally:

- The AdminCommandRegistry is exposed to the outside world via plugins. A concrete example is the CLI plugin
  that reads from stdin and forwards requests to the registry:
  [CliAdminCommandProcessor.java]({{source_root}}/main/java/com/telamin/mongoose/service/admin/impl/CliAdminCommandProcessor.java).
  In the same way, you can expose HTTP, gRPC, or other transports by writing a small adapter that translates incoming
  requests into AdminCommandRequest objects and passes them to the registry.

The core types are:

- AdminCommandRegistry — a registry where commands can be registered and invoked at runtime.
  Source: [AdminCommandRegistry.java]({{source_root}}/main/java/com/telamin/mongoose/service/admin/AdminCommandRegistry.java)
- AdminFunction — the functional interface you implement for a command handler.
  Source: [AdminFunction.java]({{source_root}}/main/java/com/telamin/mongoose/service/admin/AdminFunction.java)
- AdminCommandProcessor — the default registry implementation and dispatcher that wires into the event flow.
  Source: [AdminCommandProcessor.java]({{source_root}}/main/java/com/telamin/mongoose/service/admin/impl/AdminCommandProcessor.java)
- Optional CLI driver (example) that reads commands and sends them to the registry.
  Source: [CliAdminCommandProcessor.java]({{source_root}}/main/java/com/telamin/mongoose/service/admin/impl/CliAdminCommandProcessor.java)

## Registering commands on the fly

Any service or processor can register admin commands at startup (or later) using AdminCommandRegistry. Registration can
occur either inside or outside of a processor thread:

- Outside a processor thread: the command is registered directly in-memory and executed inline when invoked.
- Inside a processor thread: the command is bound to that processor via an internal queue so that when the command is
  invoked, the work happens on the owning processor’s single-threaded event loop. This preserves thread-affinity and
  avoids locking in your handler code.

Code sketch (service or handler):

```java
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;

public class MyHandler /* extends ObjectEventHandlerNode, etc. */ {
    private AdminCommandRegistry admin;

    @ServiceRegistered
    public void admin(AdminCommandRegistry admin, String name) {
        this.admin = admin;
        // Register at lifecycle start or here
        admin.registerCommand("echo", (args, out, err) -> {
            out.accept(String.join(" ", args));
        });
    }
}
```

Notes:

- AdminFunction’s signature is: void processAdminCommand(List<String> args, Consumer<OUT> out, Consumer<ERR> err)
  See: [AdminFunction.java]({{source_root}}/main/java/com/telamin/mongoose/service/admin/AdminFunction.java)
- To invoke a command you typically create an AdminCommandRequest and call
  AdminCommandRegistry.processAdminCommandRequest(request). The CLI example shows how to parse user input and route it
  to the registry.
  See: [CliAdminCommandProcessor.java]({{source_root}}/main/java/com/telamin/mongoose/service/admin/impl/CliAdminCommandProcessor.java)

## Dispatching and threading model for commands

When your command is registered from within a processor context, AdminCommandProcessor wires an event queue per
command (keyed as "adminCommand.<name>") and subscribes the owning processor. When invoked, the command is delivered via
that queue and executed on the correct processor thread, as an event cycle of that processor: it audits like an
event ([details](../example/how-to/how-to-write-an-admin-command.md#admin-commands-in-the-event-cycle)).
Implementation reference:

- queue registration and subscription: AdminCommandProcessor.addCommand(...)
- registration behavior based on ProcessorContext: AdminCommandProcessor.registerCommand(...)

Source: [AdminCommandProcessor.java]({{source_root}}/main/java/com/telamin/mongoose/service/admin/impl/AdminCommandProcessor.java)

## Built-in commands (default)

AdminCommandProcessor registers several default commands during start():

- help, ?
    - Prints the help message including the default commands.
- commands
    - Lists all registered command names (including user-registered ones).
- eventSources
    - Prints information about queues/event sources known to the EventFlowManager.

Source: [AdminCommandProcessor.java]({{source_root}}/main/java/com/telamin/mongoose/service/admin/impl/AdminCommandProcessor.java)

## Server controller

For broader operational control (adding and stopping processors, registering, removing, starting and stopping
services), use the server controller API, `MongooseServerController`. A server booted with `bootServer` registers
itself as this service, under `MongooseServerController.SERVICE_NAME`, so a processor or service can receive it with
`@ServiceRegistered`.

- MongooseServerController interface
  Source: [MongooseServerController.java]({{source_root}}/main/java/com/telamin/mongoose/service/servercontrol/MongooseServerController.java)

Capabilities provided:

- Add an event processor to a named group with a chosen `IdleStrategy`:
  `addEventProcessor(processorName, groupName, idleStrategy, Supplier<DataFlow>)`
- Stop a specific processor within a group: `stopProcessor(groupName, processorName)`
- Register and remove services: `registerService(service)`, `registerEventSource(name, source)`,
  `removeService(serviceName)`
- Start and stop named services: `startService(serviceName)`, `stopService(serviceName)`
- Inspect registered services: `registeredServices()` returns `Map<String, Service<?>>`
- Inspect registered processors by group: `registeredProcessors()` returns `Map<String, Collection<NamedEventProcessor>>`

**When these calls return, and what they guarantee**, is specified in the
[control-plane contracts](../reference/control-plane-contracts.md). In short: called from an ordinary thread, the
void methods wait up to `controlWaitMillis` for the change to be applied; called from a processor's handler, its
`start()` or `stop()`, or any other agent thread, they queue the change and return at once. `MongooseServer` adds
methods that return the outcome: `addProcessor`, `stopProcessor(ProcessorHandle)` and
`stopProcessor(group, name, waitMillis)`.

A processor that has been recorded or replayed sees this controller through an interceptor: its requests are
recorded, and in a replay they are matched against the recording instead of executed. See
[Record and replay](../reference/control-plane-contracts.md#record-and-replay).

Security note:

- Do not expose admin command adapters (CLI/HTTP/gRPC) publicly without authentication and authorization.
- Consider whitelisting safe commands for external access.
- Any processor or service can be injected with the controller. Review which components receive it.

## Server admin commands

`MongooseServerAdmin` is not installed by default. Register it as a service, and when it starts it adds these
server-level commands to the admin registry:

| Command | What it does |
| --- | --- |
| `server.service.list` | Lists registered services |
| `server.processors.list` | Lists processors by group |
| `server.processors.stop <group>/<name>` | Stops a processor and prints `stop <OUTCOME>: <reason>`. It waits at most one second; if no reply comes in time it prints `stop pending (still applies)`, and the stop still happens ([contract](../reference/control-plane-contracts.md#the-admin-stop-command)) |
| `audit.start <name>`, `audit.stop <name>`, `audit.status`, `audit.list` | Audit capture ([contract](../reference/control-plane-contracts.md#audit-capture)) |
| `latency.status`, `latency.enable`, `latency.disable`, `latency.toggle`, `latency.reset` | Latency monitoring |

Source: [MongooseServerAdmin.java]({{source_root}}/main/java/com/telamin/mongoose/service/servercontrol/MongooseServerAdmin.java)

## End-to-end example (CLI)

The CLI admin component demonstrates wiring stdin to admin commands:

- Parses a line into command + args
- Creates an AdminCommandRequest (with output and error consumers)
- Calls AdminCommandRegistry.processAdminCommandRequest(request)

Source: [CliAdminCommandProcessor.java]({{source_root}}/main/java/com/telamin/mongoose/service/admin/impl/CliAdminCommandProcessor.java)
