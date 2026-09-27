# Admin commands in an event cycle

**Status**: Proposal — no code. Written 2026-09-27 from a live failure in a Spring-authored bundle.
**Baseline read**: `origin/main` `ab44617` (1.0.30-SNAPSHOT); observed on released Mongoose **1.0.29** with
fluxtion-runtime **1.0.16** and svc-admin-web **1.0.44**.
**Drives**: admin commands registered by a processor node that can safely audit-log and change node state; an audit
record for every operator action; the documentation claim that a processor's command runs "as an AdminCommand event".
**Cross-repo**: option B needs a small Fluxtion runtime/generator addition (flagged below).

---

## Problem

A node registers an operator command through the documented route (`how-to-write-an-admin-command.md`):

```java
@ServiceRegistered("adminCommandRegistry")
public void admin(AdminCommandRegistry registry) {
    registry.registerCommand("alarm.reset", (args, out, err) -> {
        raised = false;                                  // change node state
        auditLog.info("reset", true).info("resetVia", "admin");   // record why
        out.accept("alarm cleared");
    });
}
```

It is invoked over REST (`POST /api/commands/alarm.reset`). The command runs on the processor's own agent thread, as
designed, and it clears the alarm. **The processor's audit log is corrupted.** In the exported YAML, the node's log line
lands between two records, and the next record's header is spliced onto its end:

```
        - feedAlarm: { reset: true, resetVia: admin, wasRaised: true, countAtReset: 3eventLogRecord:
    eventTime: 1790521786303
    ...
    nodeLogs: }
```

The Fluxtion Audit Log Analyser flags it on load as a producer finding: *"Record 37 does not open with the
'eventLogRecord:' key — its first line is '- feedAlarm: { reset: true, resetVia: admin…'"*. A reader can no longer
trust the framing of that record or the one after it.

## Cause (read from `ab44617`)

Mongoose gets the command onto the right thread, but never into an event cycle:

1. `AdminCommandProcessor.registerCommand` (`service/admin/impl/AdminCommandProcessor.java:105–112`): when a processor is
   current at registration, it creates a queue `adminCommand.<name>`, registers it as an event source, and subscribes
   the processor with `AdminCallbackType`.
2. `AdminCommand.publishCommand` (`AdminCommand.java:94–111`) publishes the command onto that queue and waits on a
   semaphore for completion (a bounded 1 s `tryAcquire`, then an unbounded `acquire` at line 102).
3. On the processor's agent, `AdminCommandInvoker.dispatchEvent` (`AdminCommandInvoker.java:29`) calls
   **`adminCommand.executeCommand()` directly**. That method (`AdminCommand.java:116–118`) invokes the user lambda.
   Unlike every other `*Invoker`, it never calls `eventProcessor.onEvent(...)`.

So the lambda runs **outside** a Fluxtion event cycle:

- no `auditEvent(...)` opened a record, so node `auditLog` writes have no record to go into;
- no `afterEvent()` flushes, so the orphaned lines are emitted when the next cycle's record is written;
- state changes made in the lambda do not propagate: no dirty flags, no triggers downstream;
- the operator action itself leaves no record in the audit log, although it changed application state.

The guide says the command is delivered "into that processor's input queue as an AdminCommand event". That is true
of the Mongoose queue, not of the processor, which is where the difference matters.

## Workaround in use today

Capture the processor at registration and turn the command into a real event:

```java
DataFlow flow = ProcessorContext.currentProcessor();          // valid inside @ServiceRegistered on the agent
registry.registerCommand("alarm.reset", (args, out, err) -> {
    flow.publishSignal("resetAlarm", "admin");                // a normal, audited event cycle
    out.accept("alarm cleared");
});
// and a signal handler (declared in the graph / Spring XML signalHandlers) does the work and logs
```

This works: the export then has its own record (`event: Signal`, `eventToString: Signal: {filterString: resetAlarm,
value: admin}`) and the node's values in it. But every author has to know the trap and repeat the plumbing, and
nothing warns them when they don't.

## Options

### A. Declarative, signal-routed commands (Mongoose only)

Add a registration form that routes a command to the processor as an event, instead of running a lambda:

```java
registry.registerSignalCommand("alarm.reset");                 // or registerCommand(name, AdminRouting.SIGNAL)
```

`AdminCommandInvoker.dispatchEvent` then calls `eventProcessor.onEvent(new Signal<>("admin:alarm.reset", request))`,
where `request` carries the args and the `out`/`err` consumers. The node handles it with an ordinary filtered signal
handler (`@OnEventHandler(filterString = "admin:alarm.reset")`, or `signalHandlers` in Spring XML) and replies through
`request.out()`.

- **Pro:** no runtime change. The command becomes part of the declared graph: visible in the XML, the generated
  processor and the analyser's topology. It is a full cycle: audited, dirty-tracked, triggers propagate.
- **Con:** a new API shape; existing lambda commands are not fixed; unhandled signal names need a clear error back to
  the caller (not just the processor's unknown-event handler).

### B. Run lambda commands inside an audited boundary (Mongoose + Fluxtion runtime) — recommended

Generated processors already have exactly this boundary for exported service calls: `beforeServiceCall(description)`
→ `auditEvent(functionAudit)` → work → `afterServiceCall()` → `afterEvent()` + `dispatchQueuedCallbacks()`. It is
private. Expose it:

- **Fluxtion runtime/generator** (cross-repo ask): `DataFlow.runInEventCycle(String description, Runnable action)`.
  The default implementation just runs the action, for processors that predate it; generated processors implement it
  with the existing `beforeServiceCall`/`afterServiceCall`, using an audit event that names the action (e.g.
  `AdminCommandAuditEvent{command, args}` or `ExportFunctionAuditEvent` with a description).
- **Mongoose**: `AdminCommandInvoker.dispatchEvent` becomes
  `eventProcessor.runInEventCycle("admin " + adminCommand.getArgs(), adminCommand::executeCommand)`.

- **Pro:** every existing processor command becomes safe with no author change. Each operator action gets its own
  audit record naming the command and args. Node logging inside the lambda lands in that record.
- **Con:** needs a runtime release and regenerated processors for full effect. State changed in the lambda still does
  not mark nodes dirty, so document "change state, then publish an event if dependents must react", or pair it with A.

### C. Runtime safety net (Fluxtion runtime) — recommended regardless

A node `auditLog` write with no open record must never splice into the stream. Either emit it as a standalone,
well-formed record (e.g. `event: OutOfCycleLog`), or drop it and count the drops in the next record. Any other host
code that logs outside a cycle (scheduler callbacks, service threads) is protected too.

## Recommendation

1. **Now:** correct `how-to-write-an-admin-command.md`. The command runs on the processor's thread but not in an event
   cycle; do not audit-log or rely on propagation inside it. Show the publish-an-event pattern.
2. **Next:** C (runtime), then B (runtime + Mongoose `AdminCommandInvoker`). A is optional sugar on top of B for
   commands that should be first-class graph events.

## Acceptance / regression checks

- **Mongoose integration test:** a processor with audit capture on registers a command whose lambda audit-logs.
  Invoke it over the admin API between two ordinary events. Export the capture and assert that every document begins
  with `eventLogRecord:`, and (after B) that exactly one record names the command and carries the node's values.
- **Mutation control:** revert `AdminCommandInvoker.dispatchEvent` to the bare `executeCommand()`; the test must fail
  at the framing assertion.
- **Existing behaviour kept:** a command registered outside a processor (no `currentProcessor()`) still executes
  directly on the caller thread; the REST/CLI reply and the busy/semaphore behaviour are unchanged.

## Evidence

A Spring-authored Mongoose bundle (starter 1.0.75, Mongoose 1.0.29, mongoose-plugins/svc-admin-web 1.0.44) registered
`alarm.reset` from a `@ServiceRegistered("adminCommandRegistry")` node and invoked it with
`POST /api/commands/alarm.reset` (reply `{"output":["alarm cleared; re-armed at rejectedCount 3"]}`). The exported
audit YAML showed the splice above, and the analyser (1.24.0) raised the producer finding. After switching to the
publish-a-signal workaround, the same scenario exported cleanly: the reset is its own `Signal` record, and the analyser
reports no producer findings.
