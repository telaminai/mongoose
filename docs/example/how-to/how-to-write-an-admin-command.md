# Guide: Writing an Admin Command for Mongoose server

This guide explains how to add operational/admin commands to your Mongoose server application. Admin commands are
lightweight functions that you register at runtime and can invoke via a CLI or programmatically to inspect or control
the system (list services, stop processors, flush caches, custom health checks, etc.).

You’ll learn:

- What the AdminCommand API is and how it works
- How to register commands from processors and services using AdminCommandRegistry
- Command function signature, arguments, and output/error channels
- How commands are dispatched through the event flow (asynchronously) or executed directly
- How a processor's command runs in its event cycle, and audits like an event
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
execution. A command delivered to a processor runs in that processor's event cycle: see
[Admin commands in the event cycle](#admin-commands-in-the-event-cycle).

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
  processor’s agent thread, in the processor's event cycle (next section). Otherwise, it executes immediately in the
  caller thread.

## Admin commands in the event cycle

A command registered by a processor runs on the processor's agent thread as an event cycle of that processor, so it
audits like an event: it has its own record in the audit log, the node's `auditLog` writes land in that record, and
the processor's clock takes the command's instant. Without this, a command's audit writes had no record of their own
and spliced into the next event's record.

There are two ways to register one.

### `registerCommand`: a function, run as its own cycle

```java
@ServiceRegistered
public void admin(AdminCommandRegistry registry, String name) {
    registry.registerCommand("quotes.refresh", (args, out, err) -> {
        // runs in the processor's event cycle; audit writes land in this command's own record
        getContext().getParentDataFlow().onEvent("DEMO-refresh");   // queued: handled AFTER the command returns
        out.accept("refreshed");
    });
}
```

On a processor built with **fluxtion runtime 1.1.0 or later**, the invoker runs the function through
`DataFlow.runInEventCycle(new AdminCommandEvent(name, args), function)`:

- the audit record names the command (`AdminCommandEvent`, with its name and arguments);
- an event the function raises is queued, and is dispatched after the command, as its own cycle;
- nothing downstream reacts to the command itself. A command that needs the graph to react raises an event, as
  above, or is signal-routed (below).

A processor generated before fluxtion runtime 1.1.0 has no `runInEventCycle` implementation, and a processor may
declare `AdminCommandsBracketed` to have its lambda commands bracketed instead. For those, the invoker
brackets the function with the processor's own audit calls. The route is decided before the command is invoked, from
what the processor declares. A processor that implements `runInEventCycle` and does not declare it always gets the
event cycle: if the cycle fails while setting up (its clock, a buffered calculation), the command is refused by name and
never run by the weaker route. An override that refuses without declaring is treated the same way. The command still gets its own audit record, but an event
it raises is dispatched at once, inside the command. Regenerating the processor with a current Fluxtion generator
gives it the event-cycle path.

### `registerSignalCommand`: an event the graph handles

```java
@ServiceRegistered
public void admin(AdminCommandRegistry registry, String name) {
    registry.registerSignalCommand("alarm.reset");
}

@OnEventHandler(filterString = "admin:alarm.reset")
public boolean reset(Signal<AdminCommandRequest> signal) {
    raised = false;
    auditLog.info("reset", true);
    signal.getValue().getOutput().accept("alarm cleared");      // the reply
    return true;                                                // propagates, as any event handler's result does
}
```

The processor receives `onEvent(new Signal<>("admin:alarm.reset", request))`: an ordinary event. So the command is
audited, and the state it changes propagates to downstream nodes as any event's does. A handler replies through the
request's `getOutput()` or `getErrOutput()`. A command that no handler replied to is answered with an error
(`... no handler replied`). Use this form when the command should drive the graph, not just act on one node.

### When a command cannot run, or fails

A command runs at most once, and its caller is answered, within a bound:

- **The wait is bounded.** A caller waits at most `mongoose.admin.completionTimeoutMs` (a system property; default
  10 s) for its command to complete.
- **Not yet started when the wait ends** (the bound passed, or the caller was interrupted): the command is cancelled.
  It never runs later, and the caller is told so (`... was cancelled before its processor started it ...; it will not
  run`).
- **Already started when the wait ends:** it cannot be cancelled. The caller is told it started and may still
  complete, and nothing more from it reaches the caller.
- **A processor replaying:** a live command for a processor muted for a replay is refused at once, by name.
- **The processor cannot run the command** (for example it was left mid-cycle by a node that threw, or its event cycle
  failed while setting up): the caller is answered with an error (`admin command '...' did not run: ...`), and the
  command is not run. The failure is also logged and reported (`ErrorReporting`, WARNING), so an operator sees a
  processor that cannot run commands, not only the person who typed one.
- **The command fails** (a signal handler throws, or a later event in its cycle does, even after the handler replied):
  the caller is told the failure. It is reported as any event's failure is. A recording marks the input failed, and a
  replay stops there rather than run it again. The agent's retry does not run the command again.
- **A command's name is its registered name.** Surrounding whitespace in a request is ignored, and the same handler
  answers.

Limits, stated rather than hidden:

- **A stopped server is not refused at once.** The server does not stop its admin service, so nothing tells a command
  the server has stopped. Its caller is answered at the bound, and the command will not run.
- **An unknown command name is not answered.** A request for a name that is not registered is logged, and its caller
  hears nothing.

Tests that show them:

- [AdminReviewRegressionTest.java]({{source_root}}/test/java/com/telamin/mongoose/replay/AdminReviewRegressionTest.java):
  a failing signal command recorded as failed and not replayed; a cycle that fails while setting up refusing its
  command; the bounded wait, cancellation before and after a command starts, and a replaying processor's refusal; a
  name with surrounding whitespace

- [RunInEventCycleAdminTest.java]({{source_root}}/test/java/com/telamin/mongoose/replay/RunInEventCycleAdminTest.java):
  a function's raised event runs after it, as its own cycle
- [AdminCommandFailureTest.java]({{source_root}}/test/java/com/telamin/mongoose/replay/AdminCommandFailureTest.java):
  a processor that cannot run the cycle answers the caller and does not run the command; a raised event that throws
  does not run the command again
- [SignalAdminCommandTest.java]({{source_root}}/test/java/com/telamin/mongoose/replay/SignalAdminCommandTest.java):
  a signal-routed command, its reply, and the no-reply error
- [GeneratedAdminAuditTest.java]({{source_root}}/test/java/com/telamin/mongoose/replay/generated/GeneratedAdminAuditTest.java)
  with [AlarmNodes.java]({{source_root}}/test/java/com/telamin/mongoose/replay/generated/AlarmNodes.java): on a
  generated processor, each form's own audit record, and propagation for the signal-routed one

Admin commands are also recorded and replayed with the processor's other inputs: see
[Record and replay a processor](how-to-record-and-replay-a-processor.md).

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
  asynchronously to that single‑threaded agent, where it runs as an event cycle and blocks the processor's other
  events while it runs.
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
