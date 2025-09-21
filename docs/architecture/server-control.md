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
that queue and executed on the correct processor thread. Implementation reference:

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

## Server controller (optional plugin)

For broader operational control (adding/stopping processors, starting/stopping services, etc.), use the server
controller API. This is provided by the MongooseServerController interface. Important: the controller is an optional
plugin — it is not available by default in the runtime. You must include and register the plugin if you want
programmatic
control.

Security note:
- Do not expose admin command adapters (CLI/HTTP/gRPC) publicly without authentication and authorization.
- Consider whitelisting safe commands for external access.
- The MongooseServerController plugin should be enabled only when you require runtime control; keep it disabled otherwise.

- MongooseServerController interface
  Source: [MongooseServerController.java]({{source_root}}/main/java/com/telamin/mongoose/service/servercontrol/MongooseServerController.java)

Capabilities provided:

- Add new event processors into a named group with a chosen IdleStrategy:
  addEventProcessor(processorName, groupName, idleStrategy, Supplier<StaticEventProcessor>)
- Start/stop named services at runtime:
  startService(serviceName), stopService(serviceName)
- Inspect registered services:
  registeredServices() → Map<String, Service<?>>
- Inspect registered processors grouped by group name:
  registeredProcessors() → Map<String, Collection<NamedEventProcessor>>
- Stop a specific processor within a group:
  stopProcessor(groupName, processorName)

Because it is optional, production deployments that do not require runtime control can omit the plugin entirely. If you
do
include it, you can also surface safe subsets of its capabilities via admin commands (e.g., wrap a stopProcessor action
behind an authenticated admin command).

## End-to-end example (CLI)

The CLI admin component demonstrates wiring stdin to admin commands:

- Parses a line into command + args
- Creates an AdminCommandRequest (with output and error consumers)
- Calls AdminCommandRegistry.processAdminCommandRequest(request)

Source: [CliAdminCommandProcessor.java]({{source_root}}/main/java/com/telamin/mongoose/service/admin/impl/CliAdminCommandProcessor.java)
