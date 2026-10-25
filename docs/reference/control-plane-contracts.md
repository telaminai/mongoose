# Control-plane contracts

**What this page covers.** This page lists every public contract of the Mongoose control plane: adding and stopping
processors, registering and removing services, shutting the server down, audit capture, record and replay, and the
control audit. For each contract it gives the exact API, what is guaranteed, what is not guaranteed, how it fails, and
an example.

**Status: unreleased.** These contracts arrive with PR #76 (branch `feature/control-plane`). They are not in Mongoose
1.0.33 or earlier. See the [unreleased release notes](../release/release-notes-unreleased.md) for what changed for
callers.

**How to read it.** Each section stands alone. Terms such as *group*, *agent thread*, *control processor*,
*generation* and *incarnation* have one definition each, in the [glossary](glossary.md#control-plane-terms). When this
page and the code disagree, the code is right. Please report the difference.

The design behind these contracts is `docs/design/control-plane-as-events.md` on the branch
[`design/control-plane-as-events`](https://github.com/telaminai/mongoose/blob/design/control-plane-as-events/docs/design/control-plane-as-events.md)
(PR #75). Its "Implementation notes" section lists where the build differs from the design. This page describes the
build.

## Contents

| Contract | Section |
| --- | --- |
| The one reply every control request gets | [Replies and outcomes](#replies-and-outcomes) |
| Which threads may wait, and where replies complete | [Threading rules](#threading-rules) |
| `addEventProcessor`, `addProcessor`, duplicate names, `addFailurePolicy` | [Adding a processor](#adding-a-processor) |
| `ProcessorHandle`, `stopProcessor` (three forms), the admin stop command | [Stopping a processor](#stopping-a-processor) |
| `registerService`, `removeService`, versions, the service lock | [Services](#services) |
| `shutdown()`, `StopResult`, resuming, `awaitStopped` | [Shutdown](#shutdown) |
| `MongooseAuditCaptureService`, open timeout and limit, `AuditCaptureResources` | [Audit capture](#audit-capture) |
| `ReplayConfig`, stream keys, what is recorded, when a replay stops | [Record and replay](#record-and-replay) |
| What the control audit records, exporting and reading it | [Control audit](#control-audit) |
| `controlWaitMillis`, `shutdownTimeoutMillis`, `addFailurePolicy`, capture settings | [Configuration keys](#configuration-keys) |
| What none of the above promises | [Not guaranteed](#not-guaranteed) |

## Replies and outcomes

Every lifecycle change to a processor group is a **control request**. The group's
[control processor](glossary.md#control-processor) decides each request in the order it was accepted, and answers it
with one `ControlReply`.

```java
package com.telamin.mongoose.control;
public record ControlReply(long requestId, ReplyOutcome outcome, String reason, long generation, String name) {
    public boolean applied();   // outcome == APPLIED
}

package com.telamin.mongoose.control.event;
public enum ReplyOutcome { APPLIED, REFUSED, FAILED, NOT_RUNNING }
```

| Outcome | Meaning | Typical `reason` values (from the code) |
| --- | --- | --- |
| `APPLIED` | The requested transition has finished, not just been accepted. For a stop, release was attempted and the name freed; reported lifecycle callback failures do not imply callback success. A processor already gone is also `APPLIED`. | `started`, `stopped`, `alreadyStopped`, `releasing`, `cancelled` (a stop overtook its add), `recording`, `not recording`, `delivered`, `ignored: stale version N` |
| `REFUSED` | Nothing changed. The request was turned down before any effect ran. | `name in use`, `cancelled` (the add was cancelled by an earlier stop), `no such processor`, `not started yet`, `mailbox full`, `not admitted within its deadline: mailbox full`, `handle belongs to another server`, `no such group` |
| `FAILED` | The request was accepted and acted on, but its effect did not complete. | the start failure's text; `open timed out`; `open failed: ...`; `delivered with failures: ...`; `group died: ...`; `control processor threw: ...` |
| `NOT_RUNNING` | The group, or the server, is closing, closed or dead. The request was not applied. | `group closed`, `server is stopping` |

`requestId` is `-1` for a request refused before it reached a group. `generation` is `-1` when the reply is not about
one registration. `name` is the processor name, or `null`.

**Guaranteed**

- Every request that reaches a group gets exactly **one** final reply. This includes a group that closes or dies while
  the request is queued: the request is answered `NOT_RUNNING` or `FAILED`, never left pending.
- Mailbox requests to one group are decided in queue publication order. Concurrent callers have no ordering before
  publication; asynchronous effect completions use a separate queue. A later request can finish before an earlier
  request waiting for I/O.
- For an add, `APPLIED` means the processor started. For a stop it means its release attempt finished and its name
  is free. The ordinary stop/teardown failure policy reports callback failures and continues release; this reply
  does not prove every callback succeeded. Physical capture closure can still be pending.

A shared daemon checker detects a captured agent thread that terminated without closing, even when no later
sender or shutdown call arrives. It joins the thread before completing claimed requests; unstarted groups are not
judged dead. Legacy low-level reply callbacks must not block.

**Not guaranteed**

- A reply says what happened to *this* request. It does not say what other requests did meanwhile.
- A void method that times out does not cancel its request (see [timeouts detach](#timeouts-detach)). The reply
  still arrives, but nobody is waiting for it.

## Threading rules

### Agent threads never wait

An [agent thread](glossary.md#agent-thread) is a thread that runs one of the server's processor groups or service
agent groups. Mongoose marks it when the group starts and clears the mark after the group closes.

**Guaranteed**

- Processor mailbox admission and reply waiting never sleep or back off on an agent thread. A method that would
  await a reply queues its request and returns instead. This is not a promise that arbitrary service preparation or
  lifecycle callbacks cannot block: those may run synchronously, and registry publication still takes a short lock.
- A full mailbox on an agent thread is refused at once. Stage APIs return `REFUSED`; void add follows its failure
  policy. Lower-level send methods can throw `IllegalStateException`. There is no capacity backoff.
- `ControlReplies.await` throws `IllegalStateException` if called on an agent thread.

Waiting is also skipped, for the same reason, on the thread running `MongooseServer.start()`, on the server's stop
thread and its helpers, and when the target group has not started (it cannot answer yet).

### Timeouts detach

A void processor control call shares one [`controlWaitMillis`](#configuration-keys) deadline (default 10 000 ms)
between admission and waiting for its reply. Service-change reply waits use the same configuration setting. If an
admitted request has no reply in time, the caller logs a warning and returns. **Timing out after admission does not
cancel the request.** A full-mailbox admission that expires is instead `REFUSED` and will never apply. Outcomes are
recorded in the [control audit](#control-audit).

### Where reply stages complete

`addProcessor(...).reply()` and `stopProcessor(handle)` return a `CompletionStage<ControlReply>`.

**Guaranteed**

- A stage for a request that reached a group is completed on the server's reply executor: an elastic pool of daemon
  threads named `mongoose-control-replies-N`. It is never completed on an agent thread. So a dependent attached
  *before* completion never runs on, blocks or re-enters an agent thread.
- After the server has stopped, its reply executor is shut down. A completion that arrives later runs on a fresh
  daemon thread named `mongoose-control-replies-late`.

**Not guaranteed**

- A **synchronous dependent attached after completion runs on the attaching thread**, as `CompletionStage` specifies.
  A request refused before it reached a group (for example `addProcessor` with a duplicate name, or a handle from
  another server) returns a stage that is already complete, so `thenAccept(...)` runs at once, on the caller's thread.
  If that thread is an agent thread, so is the dependent.
- Use the `...Async` forms (`thenAcceptAsync`, `whenCompleteAsync`, and so on) for blocking work, or for any dependent
  that must not run on the calling thread.

### What is safe to call, and from where

| Called from | Void methods (`addEventProcessor`, `stopProcessor(group, name)`, `registerService`, `removeService`) | Stage methods (`addProcessor`, `stopProcessor(handle)`) | `shutdown()` |
| --- | --- | --- | --- |
| An ordinary thread | Admit, then wait up to `controlWaitMillis` for the reply | Return at once with a stage | Runs the stop and returns its `StopResult` |
| A processor's event handler, `start()` or `stop()` (agent thread) | Admit and queue; return without waiting. A request to the processor's own group is applied at the group's next drain | Return at once | Hands the stop to a `mongoose-server-stop` thread and returns `handedOff = true` |
| A `@ServiceRegistered` / `@ServiceDeregistered` callback in a processor (agent thread) | As the row above | As the row above | As the row above |
| A service agent's `doWork()` (agent thread) | As the row above | As the row above | As the row above |
| A service's `start()` during `MongooseServer.start()` | Admit and queue; return without waiting | Return at once | Hands off, as on an agent thread |

Two rules follow:

- **Never block an agent thread on a reply.** Do not call `join()`, `get()` or `ControlReplies.await` on a stage from
  a handler, `start()`, `stop()` or a service callback. The group that would answer may be the one you are blocking.
- **A processor may stop itself.** `stopProcessor(group, ownName)` from its own handler is queued and applied at the
  next drain. Called from its own `stop()`, it joins the release already in progress (`APPLIED`, reason `releasing`
  or `alreadyStopped`). It never reaches a later processor with the same name, because the stop is bound to the
  registration at call time.

## Adding a processor

### addEventProcessor (void)

```java
public void addEventProcessor(String processorName, String groupName, IdleStrategy idleStrategy,
                              Supplier<DataFlow> feedConsumer) throws IllegalArgumentException
```

Adds a processor to a group, creating the group (and starting its thread, if the server has started) on demand.
`idleStrategy` may be `null`; the group's configured or default strategy is used.

**Guaranteed**

- An admitted add reserves the name before the call returns. A second add of the same name in the same group
  throws `IllegalArgumentException`, whether or not the group has started. Refusal releases any temporary reservation.
- Off an agent thread, on a running group, the call waits up to `controlWaitMillis` for the add's reply.
- With the default `addFailurePolicy: REPORT`, an add that is refused or whose `start()` fails is logged and
  reported, and the call returns normally. With `THROW`, see [addFailurePolicy](#addfailurepolicy).
- The supplier runs on the group's agent thread, never on the caller's thread.

**Failure modes**

| Situation | Result |
| --- | --- |
| Name already admitted in the group | `IllegalArgumentException`, at once |
| Server is stopping or stopped | `IllegalStateException` ("server is stopping") |
| Mailbox full, on an agent thread | `IllegalStateException`, at once; the name is released |
| Mailbox full, off an agent thread | Backs off until `controlWaitMillis` from the call (or the group's 10 s full-mailbox timeout, if sooner), then `IllegalStateException`; the name is released. One deadline covers admission and the reply |
| Mailbox full when a server stop begins | A sender still backing off leaves at once: the request is answered `NOT_RUNNING` ("server is stopping") and its name released, so it never holds up the stop |
| `start()` throws, `REPORT` | Reported through `ErrorReporting`; processor dropped; call returns |
| `start()` throws, `THROW` | `ProcessorStartException` |
| No reply within `controlWaitMillis` | Warning logged; call returns; the add still applies |
| On an agent thread, or the group not started | Call returns once queued; the outcome is only logged |

### addProcessor

```java
public AddedProcessor addProcessor(String processorName, String groupName, IdleStrategy idleStrategy,
                                   Supplier<DataFlow> feedConsumer)

public record AddedProcessor(ProcessorHandle handle, CompletionStage<ControlReply> reply) { }
```

Adds a processor and returns its outcome as a stage, without waiting for it.

**Guaranteed**

- Never throws for a duplicate name or a stopping server. Those come back as a completed `reply`: `REFUSED` (`name in
  use`) or `NOT_RUNNING` (`server is stopping`).
- `handle` names exactly the registration this add admitted, so a stop through it
  can never reach a later processor with the same name.
- `handle` is `null` when the add was refused before admission.
- `reply` completes `APPLIED` (`started`) once the processor has started, or `FAILED` once a failed processor has been
  released and its name freed.

**Not guaranteed**

- Admission does not back off: a full mailbox is refused immediately (`REFUSED`, null handle). A server admission
  lock unavailable at the attempt is answered `NOT_RUNNING`. Creating a new group still performs synchronous setup;
  this is not a hard execution-time guarantee.

**Example**

```java
AddedProcessor added = server.addProcessor("pricer", "pricing", null, PricerProcessor::new);
added.reply().thenAcceptAsync(reply -> {
    if (!reply.applied()) {
        log.warning("pricer not added: " + reply.outcome() + " " + reply.reason());
    }
});
ProcessorHandle pricer = added.handle();   // null if the add was refused before admission
```

### Duplicate names

A processor name is unique within its group, from admission until the stop that frees it has been applied.

- The name is reserved when an add is admitted, not when the processor starts.
- It is freed only by the control processor, once a stop, a cancelled add or a failed start has been fully applied.
  A stop that was refused (for example for a full mailbox) never frees the name.
- So an immediate re-add after a stop that has not yet been applied is refused (`IllegalArgumentException` or
  `REFUSED: name in use`). Wait for the stop's reply first.
- A configuration that lists the same name twice in one group fails boot.

### addFailurePolicy

`MongooseServerConfig.addFailurePolicy` controls only the **void** `addEventProcessor`, and only when it waited for
a reply that was not `APPLIED`:

| Policy | `FAILED` reply (start threw) | `REFUSED` or `NOT_RUNNING` reply |
| --- | --- | --- |
| `REPORT` (default) | Logged and reported; returns | Logged; returns |
| `THROW` | `ProcessorStartException(group, name, cause)` | `IllegalStateException` naming the outcome and reason |

`addProcessor`'s reply carries the same outcome under either policy. A timeout returns normally under either policy.

## Stopping a processor

### ProcessorHandle and stopProcessor(handle)

```java
public final class ProcessorHandle {
    public String group();
    public String name();
    public long generation();
}

public CompletionStage<ControlReply> stopProcessor(ProcessorHandle handle)
public ProcessorHandle processorHandle(String groupName, String processorName)   // what is admitted now, or null
```

**Guaranteed**

- A stop through a handle stops exactly that registration ([generation](glossary.md#generation)). It **never** stops a
  replacement added later under the same name.
- Handles are **server-scoped**. A handle from another `MongooseServer` is refused (`REFUSED`, `handle belongs to
  another server`) and stops nothing.
- A handle whose processor has already gone is answered `APPLIED` (`alreadyStopped`).
- A handle naming a group that does not exist is answered `REFUSED` (`no such group`).
- The returned stage does not wait for the outcome. It completes as described in
  [where reply stages complete](#where-reply-stages-complete).

**Failure modes**

- The handle's group mailbox is full: admission is refused immediately with a `REFUSED` reply. The processor keeps
  running and its name stays reserved.

`processorHandle(group, name)` returns a handle for whatever is admitted under the name at the moment you ask. Prefer
the handle from `addProcessor`, which cannot name a replacement.

**Example**

```java
server.stopProcessor(pricer).thenAcceptAsync(reply ->
        log.info("pricer gen " + pricer.generation() + ": " + reply.outcome() + " (" + reply.reason() + ")"));
```

### stopProcessor(group, name)

```java
public void stopProcessor(String groupName, String processorName)
```

Stops the processor admitted under the name **at call time**. The stop is bound to that registration then, so if
the processor is replaced before the stop is applied, the replacement keeps running.

**Guaranteed**

- No group, or nothing admitted under the name: returns at once and does nothing.
- Off an agent thread, on a running group: waits up to `controlWaitMillis` for the reply; a timeout logs and returns,
  and the stop still applies.
- On an agent thread, or before the group has started: queues the stop and returns.

**Failure modes**

- Mailbox full: `IllegalStateException`, at once on an agent thread, or once `controlWaitMillis` from the call (or the
  10 s full-mailbox timeout, if sooner) has passed
  elsewhere. Nothing is stopped and the name stays reserved.
- The method does not report the outcome. Use the `waitMillis` form, or a handle, if you need it.

### stopProcessor(group, name, waitMillis)

```java
public ControlReply stopProcessor(String groupName, String processorName, long waitMillis)
```

As `stopProcessor(group, name)`, but returns the reply. **One deadline**, `waitMillis` from the call, covers both
admission into the mailbox and waiting for the reply.

| Result | When |
| --- | --- |
| A `ControlReply` | The stop was answered in time, or refused at admission |
| `REFUSED`, reason `not admitted within its deadline: mailbox full` | The mailbox stayed full past the deadline |
| `null` | No such group, nothing admitted under the name, the caller may not wait (agent thread, group not started), or no reply by the deadline. In the last two cases the stop still applies |

On an agent thread with a full mailbox it throws `IllegalStateException`, as the void form does.

### The admin stop command

`server.processors.stop <group>/<name>` calls `stopProcessor(group, name, 1000)`. It prints `stop <OUTCOME>:
<reason>`, or `stop pending (still applies): <group>/<name>` when no reply came within one second. On a
`MongooseServerController` that is not a `MongooseServer`, it calls the void form.

## Services

### registerService

```java
public void registerService(Service<?> service)
public void registerService(Service<?>... services)
```

**Ordering**, per service:

1. Reserve the name, under the service lock. A name in use throws `ServiceRegistrationException`.
2. Inject: other services into the new one, and the new one into existing services. This runs **outside** the lock.
3. Publish a versioned registration broadcast to every processor group, under the lock. Publication never waits: a
   group whose mailbox is full misses the broadcast, and the miss is reported once through `ErrorReporting`.
4. Off an agent thread, wait up to `controlWaitMillis` for every group's reply, under one deadline, outside every
   lock.

**Guaranteed**

- Processors already running receive `@ServiceRegistered` on their own agent thread. Each processor receives each
  service once.
- A processor added later is given the registry's services when it is built.
- If the service is removed between steps 1 and 3, its registration is not published, so a removed service is never
  resurrected.

**Failure modes**

- Server stopping: `IllegalStateException`; nothing is registered.
- The same physical instance is currently being stopped by an earlier removal: `ServiceRegistrationException`.
  Registration and claiming that stop are serialized; the stop callback itself runs outside the publication lock.
- A `@ServiceRegistered` handler that throws is reported; the processor keeps running; the other processors still
  receive the service.

### removeService

```java
public void removeService(String serviceName)
```

**Ordering**:

1. Under the service lock: remove the service from the registry and publish a versioned deregistration broadcast to
   every group.
2. Outside the lock, off an agent thread: wait up to `controlWaitMillis` for every group's reply.
3. **Unbind before stop.** Only when every group has confirmed the withdrawal (`APPLIED`) is `service.stop()` called.
   If the replies arrive after the wait, the stop runs then, on a reply-executor thread.

**Retained ownership.** If any group did not confirm the withdrawal (it missed the broadcast, a
`@ServiceDeregistered` handler threw, or a group failed), the service is **not** stopped. The server keeps owning it,
and `shutdown()` stops it after every processor group has closed. A warning says
`service '<name>' remains owned until shutdown`.

**Guaranteed**

- A service is never stopped while a processor that was given it may still hold it.
- A later service registered under the same name is unaffected by the removed one's deferred stop: it is not marked
  down, and it never receives the old one's stop.
- Unknown name: no-op.

**Failure modes**

- Server stopping: the call is **ignored** (logged at `INFO`), not refused with an exception. The shutdown stops the
  service once, itself.

### Versions and the service lock

- Every registration or removal of a service name gets the next **version** for that name.
- A group applies a broadcast only if its version is newer than the last it applied for that service. An older one is
  answered `APPLIED` with reason `ignored: stale version N` and changes nothing. So concurrent register and remove of
  one service cannot make an older delivered broadcast overwrite a newer delivered one. A group that missed a
  broadcast because its mailbox was full is reported; version checks alone do not repair that missed delivery.
- The **service lock** covers only the name reservation, the registry change and the non-blocking broadcast
  publication. Injection, `start()`, `stop()`, waiting, and reporting to `ErrorReporting` all run outside it. A slow
  service callback therefore never stalls other service changes.

### Refusal while stopping

Once `shutdown()` has begun:

| Call | Result |
| --- | --- |
| `registerService`, `registerWorkerService`, `registerAgentService` | `IllegalStateException` |
| `startService`, `stopService` | `IllegalStateException` |
| `removeService` | Ignored (logged); the stop stops the service |
| `addEventProcessor` | `IllegalStateException` |
| `addProcessor` | `NOT_RUNNING` reply |

## Shutdown

### shutdown() and StopResult

```java
public StopResult shutdown()
public void stop()                    // shutdown(), result discarded
public StopResult lastStopResult()    // the last attempt's result, or null
public boolean awaitStopped(long timeout, TimeUnit unit) throws InterruptedException

public record StopResult(boolean complete, boolean handedOff, String blockedStartup,
                         List<String> groupsRunning, List<String> servicesLeftRunning) { }
```

| Field | Meaning |
| --- | --- |
| `complete` | Everything stopped. The server is `STOPPED`. |
| `handedOff` | Called on one of the server's own threads. The stop runs on a `mongoose-server-stop` thread and its outcome is not known yet. Use `awaitStopped` from another thread. |
| `blockedStartup` | The startup, admission or coordination step that blocked this attempt, or `null`. |
| `groupsRunning` | Groups or cleanup tasks not confirmed finished. Entries are prefixed `processor:` or `service:`, or are bracketed notes such as `<event sources stopping>` or `<audit capture stopping>`. |
| `servicesLeftRunning` | Services not confirmed stopped: a group may still use them, their stop is still running, or their stop failed. |

### Phases and the deadline

One deadline, `shutdownTimeoutMillis` (default 30 000 ms), covers the whole stop. Each phase gets what is left:

1. Fence startup: ask an in-progress startup step to return, and wait for it.
2. Stop feeds: close agent groups that host only event sources; take event sources off shared groups; stop every
   event source. This phase is also capped at 5 s.
3. Drain events already queued to processors, at most `shutdownDrainTimeoutMillis` (default 2000 ms).
4. Close every processor group, each from its own tracked helper thread.
5. **Only if every processor group closed**: close the service agent groups, then stop the registered services
   (including removed services the server still owns), each on a helper thread.
6. Run the capture service's final `stopAll()` cleanup. A group with unresolved physical capture resource permits
   remains incomplete before this phase; a failed physical close is not silently counted as closed.

**Guaranteed**

- `shutdown()` returns by the deadline (plus scheduling slack) with a truthful `StopResult`. It never reports
  `complete` while something it started is still running.
- If any processor group did not close, **no service is stopped**. Any running processor may hold any service.
- Groups closing do not run queued adds: their suppliers never run and they are answered `NOT_RUNNING`.

### Resuming an incomplete stop

When a stop runs out of time, the server stays `STOPPING` and `complete` is `false`. **Call `shutdown()` again to
resume.** A resumed stop:

- starts from the first unfinished phase;
- re-joins helpers still running; retries unfinished group cleanup after a failed helper exits without repeating
  processor lifecycle callbacks already attempted;
- does not call a service `stop()` that already failed again. Its side effects are unknown, so it stays in
  `servicesLeftRunning`.

Concurrent callers on ordinary threads are serialised: a second caller waits for the attempt in progress (within its
own deadline), then resumes it if it ran out.

### awaitStopped

- Returns `true` only once a stop has **completed**. An incomplete stop leaves it `false` until a later attempt
  completes.
- Use it after a stop that was handed off (called on an agent thread). Do not call it on an agent thread.

### Stopping a server that never started

`shutdown()` on a server that was never started closes every group that exists. Their queued adds are answered
`NOT_RUNNING`, and their suppliers never run. Earlier releases returned at once in this state.

**Example**

```java
StopResult result = server.shutdown();
if (result.handedOff()) {
    server.awaitStopped(60, TimeUnit.SECONDS);       // from a thread that is not an agent thread
} else if (!result.complete()) {
    log.warning("stop incomplete: " + result);
    result = server.shutdown();                      // resumes where the first attempt stopped
}
```

## Audit capture

Audit capture records a processor's Fluxtion audit log to a Chronicle queue. It is installed when
`performanceMonitoring.auditCapture.enabled: true`. Otherwise the server uses a no-op service. Both are registered
under `MongooseAuditCaptureService.SERVICE_NAME`.

### start and stop

```java
void start(String processorName);
void stop(String processorName);
boolean isRecording(String processorName);
```

With capture enabled, `start` and `stop` are requests to the processor's group. The group decides and acts on its own
agent thread.

**start, guaranteed**

- The Chronicle queue is opened on an I/O helper thread, **never on the processor's thread**. The capture is
  installed on the processor's thread, between event cycles.
- Off an agent thread, on a running group, `start` returns once the capture is recording. `isRecording(name)` is
  then `true`.
- Calling `start` on a processor that is already recording is a no-op (`APPLIED`, `already recording`). Calling it
  while an open is in progress joins that open and is answered with it.
- The request is bound to the processor admitted under the name when it is sent. A late open never installs on a
  replacement processor. It is closed instead.

**stop, guaranteed**

- On a thread allowed to wait, returns once the capture is uninstalled from the processor. The queue is handed to
  the closer afterwards for one close attempt; failures are recorded as unresolved resources.
- Stopping a processor that is not recording is a no-op.
- Stopping or removing the processor stops its capture as part of the release.

**Waits and timeouts**

| Step | Bound | On expiry |
| --- | --- | --- |
| Group begins applying the request | 5 s | Request cancelled; `IllegalStateException` ("cancelled, and nothing changed") |
| The open, once begun | group open timeout, 30 s by default | Reply `FAILED: open timed out`; `IllegalStateException`. A later open completion is closed, never installed |
| Caller's wait for an open in progress | 35 s | `IllegalStateException` ("still being applied"); the request may still complete |

**Failure modes**

| Situation | Result |
| --- | --- |
| No processor admitted under the name | `IllegalArgumentException` |
| Processor has left its group | `IllegalArgumentException` |
| Group closed (`NOT_RUNNING`) | `start`: `IllegalStateException`; `stop`: no-op |
| Processor not started yet, capture closing, open limit reached, open failed or timed out | `IllegalStateException` with the reason |
| Called on an agent thread, or the group has not started | Returns at once without waiting. The request applies later, and only a failure is logged |
| Interrupted while waiting | `IllegalStateException`; the request still applies |

The admin commands `audit.start <name>` and `audit.stop <name>` call these methods and print the exception message
on failure. `audit.start` prints `audit: RECORDING <name>` once `start` returns. If the admin command runs on an agent
thread, `start` returns before the capture is installed, so that line means "requested", not "recording". Check with
`audit.status`.

### Naming the processor to capture

`start(name)` and `stop(name)` (and the admin `audit.start` / `audit.stop`) take a processor name. A name admitted in
one group addresses that processor. A name admitted in **several** groups is ambiguous: the call throws
`IllegalArgumentException` naming the groups, and you address one as `group/name`. A processor never inherits a capture
recording another processor of the same name in a different group.

Encode each component with `AuditCaptureIdentity.selector(group, name)` when it contains `/`, `%` or other special
characters. The same selection rules apply to `isRecording` and `currentSink`; `currentSinks` uses qualified keys.
The built-in Chronicle service pins each resource to its group, name and registration generation. Each recording
session has a fresh `capture-*` directory and capture id, with a `.mongoose-capture.properties` identity sidecar;
the display selector is not a filesystem path. Replacement generations and late closes cannot share a queue.
Historical discovery still reads the old standalone layout. See [capture resource identity](../design/capture-resource-identity.md).

### Open and resource limits

Each group allows at most **8** physical opens in flight by default. An open that timed out still holds its permit
until its helper returns. While the limit is held, further audit starts fail (`FAILED`, reason
`unresolved capture open limit reached`). Every resource that a late helper returns is scheduled for one close attempt. A failed close is reported and
retained as unresolved ownership, not claimed to be closed.

Separately, each group reserves at most **1024** physical capture resource slots by default, including opens,
installed captures, queued closes and failed closes. A slot is returned after confirmed closure or an open that
produced no resource. Failed closes retain their slots and are not blindly retried. This bounds resource retention
when the closer stalls; it is not just an open-concurrency limit. `captureResourceSlotsInUse()` and
`captureCloseFailures()` expose unresolved physical ownership. Shutdown remains incomplete while it exists.

Set the limits with `ComposingEventProcessorAgent.setCaptureOpenLimit(int)` and `setCaptureResourceLimit(int)`,
and the open timeout with `setCaptureOpenTimeout(Duration)`. `MongooseServer` does not expose its groups, and neither setting is a server
configuration key. See [configuration keys](#configuration-keys).

### Deprecated: attach(..., Executor) and stopAll()

- `attach(DataFlow, String, LogRecordListener, Executor)` is deprecated. The server now calls `attachScoped`, whose
  compatibility default delegates to the old method. With routing
  in place the group installs and uninstalls captures itself. The executor is used only by a capture service that no
  server routes.
- `stopAll()` is deprecated. Each group closes its captures as it closes. The server calls `stopAll()` only after
  group capture operations have completed successfully, for any residual service-owned resources. It does not race
  a blocked group closer or treat a failed physical close as complete.

### AuditCaptureResources, for implementers

```java
public interface AuditCaptureResources {
    Object openCapture(String processorName) throws Exception;                  // on an I/O helper
    default Object openCaptureScoped(AuditCaptureIdentity identity) throws Exception; // delegates to old API
    void installCapture(Object resource, String processorName, DataFlow flow);   // on the group's agent thread
    void uninstallCapture(Object resource, String processorName, DataFlow flow); // on the group's agent thread
    void closeCapture(Object resource) throws Exception;                         // on the closer, once per resource
}
```

The group decides and an `AuditCaptureResources` only acts. The contract for an implementation:

- `openCapture` / `openCaptureScoped` must close anything created before throwing. The group calls the scoped form;
  implementations that inherit the compatibility default still receive bare names. A custom backend must implement
  scoped ownership before claiming isolation between same-named processors in different groups.
- `installCapture` puts the capture in front of the listener the server configured on the processor.
- `uninstallCapture` restores that listener. `flow` is `null` once the processor has gone. The resource must stop
  receiving records before this returns, because the group may close it straight after.
- `closeCapture` flushes and closes. The group calls it exactly once per resource. A failure is recorded and counted,
  and never blocks a processor's release.
- A resource is opaque to the group. It is held by id and handed back.

The server wires a group to a capture service only when the installed `MongooseAuditCaptureService` also implements
`AuditCaptureResources`. In this build that is the built-in Chronicle service. A group used directly can be given one
with `ComposingEventProcessorAgent.setAuditCaptureResources`.

## Record and replay

Record and replay is `@Experimental`. The how-to [Record and replay a processor](../example/how-to/how-to-record-and-replay-a-processor.md)
shows it end to end. This section states its contracts.

### ReplayConfig

```java
ReplayConfig.record(Set<String> processors, Map<String, EventCodec> journalledFeeds, EventJournal journal, ReplayStore store)
ReplayConfig.replay(Set<String> processors, Map<String, EventCodec> journalledFeeds, EventJournal journal, ReplayStore store)
    .withClock(LongSupplier liveClock)          // RECORD: the live clock recorded processors read
    .withDeliveryTimeout(Duration timeout)      // REPLAY: default 5 s
    .withControlInterception(boolean on)        // default true
    .withProvenance(boolean on)                 // default true
```

- `processors` empty means every processor.
- `withControlInterception(true)`: control requests a covered processor makes through `MongooseServerController` are
  recorded with their outcome (RECORD), and intercepted (REPLAY). See [what a replay does](#what-a-replay-does-with-each-entry).
- `withProvenance(true)`: each stream's provenance is recorded when it begins (RECORD) and checked (REPLAY).
- `RECORD` refuses a journal or store that already holds a recording (`IllegalStateException` at boot).

### Stream keys

Each processor's entries form one **stream**, keyed by name and per-name
[build ordinal](glossary.md#build-ordinal):

- the first processor of a name built in the group records under the bare name, for example `pricer`;
- later ones record under `name#2`, `name#3`, and so on;
- a `#` in a processor name is doubled first, so the first processor named `p#2` records under `p##2`, and never
  aliases `p`'s second build (`p#2`).

`ReplayStreams.key(name, ordinal)` computes a key. An add that was refused, cancelled or never built uses no number,
so keys do not depend on reproducing refusals. They are also independent of adds of other names.

**Replay failures from keys**: a later stream (`#2` and up) that was never recorded stops that processor's replay
(`no stream was recorded for ...`); it is not replayed as empty. A legacy key that is ambiguous because of an
unescaped `#` stops it too (`ambiguous legacy stream key ...`).

### What is recorded

| Entry | Recorded when |
| --- | --- |
| `Indexed` | An input from a journalled feed, received as the journal holds it: the feed, sequence number and route |
| `Inline` | Any other input, or a typed service call: a copy taken just before the processor handled it |
| `TimerFired` | A timer the processor scheduled fired |
| `AdminInvoked` | A processor-owned admin command ran: its name and arguments |
| `Failed` | A dispatch threw; an input could not be copied; or a control request was made off the processor's thread |
| `Stopped` | The processor was stopped by a request (not by its group closing), with the clock readings its `stop()` took |
| `ServiceChanged` | A service was delivered to or withdrawn from the running processor after the server started, with its registry version and clock readings |
| `ControlCall` | The processor made a control request through `MongooseServerController` (`stopService`, `startService`, `stopProcessor`, `registerService`, `registerEventSource`, `removeService`, `addEventProcessor`), with its arguments as text and its outcome (`ok` or `failed: <exception text>`) |
| Provenance | Beside the stream, not as an entry: the processor class, the handler class it wraps (empty for a generated processor) and its build ordinal |

Every timed entry carries every clock reading the processor took while handling it. Services registered while the
server boots are configuration: they are not recorded.

### What a replay does with each entry

| Entry | In a replay |
| --- | --- |
| `Indexed`, `Inline` | Delivered to the processor alone, through the route it was recorded on, with its clock pinned to the recorded readings |
| `TimerFired` | Fires the processor's timer with that schedule number |
| `AdminInvoked` | Rebuilt from the command the replayed processor registered; replies are collected in `adminReplies()` |
| `Failed` | Stops the processor's replay |
| `Stopped` | Stops the processor through its group's control processor, at this point, with the recorded clock readings |
| `ServiceChanged` | Delivers or withdraws the service at this point. Live deliveries of services the stream records are suppressed for this processor. A service the stream registers later is not given at build |
| `ControlCall` | The processor must make the same supported request in position; its recorded outcome is returned without executing the live control action. Registry mutations (`registerService`, `registerEventSource`, `removeService`) and supplier-based adds stop replay instead, even if matched. Calls made in `start()` are expected before the first input |

A replayed processor's live inputs are muted, and what it sends to `MessageSink` services is captured
(`outputs(processor)`), not delivered. Other services it was given are live.

### When a replay stops

A replay stops **one processor's** replay, never the group or the server, and records why.
`replayers().get(group).stopped(processor)` returns the reason, or `null`. A stopped replay counts as done for
`complete()`.

| Situation | Reason starts with | Why it stops |
| --- | --- | --- |
| The replayed processor's class or handler class differs from the recorded provenance | `the replayed processor is not the recorded one` | Replaying a stream into another program would be meaningless |
| The processor refused the replay clock | `the replay clock could not be installed` | Its time could not be reproduced |
| The store or the provenance could not be read | `the replay store could not be read` / `the stream's provenance could not be read` | No trustworthy stream |
| A later stream was never recorded; an ambiguous legacy key | `no stream was recorded for` / `ambiguous legacy stream key` | See [stream keys](#stream-keys) |
| The recorded run failed here | `the recorded run failed here` | Determinism is not claimed after a failure |
| An entry cannot be delivered within `deliveryTimeout` | `entry N could not be delivered within` | A route or admin command the replayed configuration never makes would otherwise stall the replay silently |
| An entry throws while being replayed; the journal lacks an item | `entry N could not be replayed` / `the journal holds no` | The input cannot be reproduced |
| A cycle read the clock a different number of times from the recorded one, including in a replayed `stop()` | `clock divergence` | The processor took a different path |
| A control request the recorded run did not make here, or made with different arguments | `control request ... was not made by the recorded run here` / `control request differs` | Divergence |
| The recorded run made a control request the replay did not | `the recorded run made control request` | Divergence |
| The replayed processor calls `addEventProcessor` | `addEventProcessor supplier cannot be verified from the recording` | The supplier is executable code that the recording cannot identify. It is never run against the live server. The call also throws `UnsupportedOperationException` into the processor |
| The replayed processor calls `registerService`, `registerEventSource` or `removeService` | `registry mutation` | Stops and throws `UnsupportedOperationException`; a separate replay registry is required to avoid changing resources shared with unreplayed processors |
| The recorded control call failed | `recorded control exception cannot be reconstructed` | Only the exception's text was recorded; its type and payload cannot be rebuilt, and a processor may branch on them. The call also throws `IllegalStateException` into the processor |
| A control request made off the processor's agent thread | `control request ... made off the processor's thread` | Outside the input order, so not reproducible |
| The recorded stop could not be replayed | `the recorded stop could not be replayed` | The processor is no longer in the group |

**Service changes** require the recorded positive version to match the currently published live binding. A retained
historical object may already have been stopped and cannot be resurrected from a cache:

| Situation | Reason starts with | What happens |
| --- | --- | --- |
| The recorded service version is not registered in this run yet | (waits) | The entry waits for the broadcast of that version, then stops after `deliveryTimeout` with `entry N could not be delivered within` |
| This run's registry has moved past the recorded version or the binding is no longer live | `recorded service S at version V is unavailable` | Stops. Neither the latest instance nor a historical stopped object is substituted |
| A configured object has no published identity for a positive recorded version | `no registry binding identifies recorded service` | Stops; logging a warning is insufficient to establish equivalence |
| A replayed `@ServiceRegistered` or `@ServiceDeregistered` callback throws | `recorded service callback for S was not delivered` | Stops. A failed callback is never counted as delivered |
| The processor already held the service when the entry is replayed | `recorded service callback for S was not delivered` | Stops: it reached the processor before its recorded position |
| The recorded run withdrew a service the replayed processor never held | `recorded withdrawal of service S has no delivered incarnation` | Stops: the withdrawal cannot be reproduced |

Only deliveries that reached the processor are recorded. A broadcast it already held (skipped live) records nothing, and
a delivery whose callback failed records a `Failed` entry, past which the stream is not reproducible.

**Limitations:** unversioned legacy entries still use configuration-based lookup; they cannot prove incarnation
equivalence. Positive recorded versions fail closed when publication identity is missing. Version numbers identify
bindings within the configured run, not a build, service state or cross-machine identity proof.

In a replay, a withdrawal the stream records is not confirmed to its sender until the stream delivers it, so
`removeService` keeps the instance meanwhile (until shutdown if the stream never withdraws it). A replayed processor's
registry requests (`registerService`, `registerEventSource`, `removeService`) are never executed against the live
server: the replay stops, because they need an isolated replay registry.

`complete()` with a `null` `stopped(...)` means every entry was delivered. It does not prove the run was reproduced.
Compare `outputs(processor)` or the audit logs.

## Control audit

### What it records

Each group's control processor writes an analyser-readable record of every decision, through Fluxtion's audit log.
Records are kept in memory, per group, in a bounded ring (`ControlAuditSink`, 10 000 records by default).
Set the JVM property `-Dmongoose.control.auditCapacity=400000` before groups are constructed to size each ring
for a longer investigation. Missing, malformed or nonpositive values use the default. Larger rings retain more
heap; overflow still marks the evidence incomplete and cannot authorize an invariant PASS. This property changes
control-history retention, not data-processor audit capture or the event queue capacity.

| Record | Written | Key fields |
| --- | --- | --- |
| `Input` | For each request or completion the control processor receives | `fact` (`Add`, `Stop`, `Register`, `Deregister`, `AuditStart`, `AuditStop`, `Close`, `Run`, or a completion), `generation`, `name`; `opId` and `completionId` for completions |
| `Decision` | For each rule that fires, before any effect it requests is performed | `rule` (for example `stopStarted`, `captureOpenedStale`), `from` and `to` states, `effects` (each as `opId Effect(generation)`), notes such as `pendingCleanup`, `stale`, `duplicate` |
| `EffectOutcome` | For each effect's result, pending or final | `opId` (`requestId.n`), `effect`, `outcome` (`pending`, `succeeded`, `failed`), `reason`, `cleanup` when it arrives after the request was answered |
| `Reply` | For each final reply | `opId`, `outcome`, `generation`, `name` |
| Fallback records (`controlFallback:`) | Written by the group itself, when the control processor cannot write: `Anomaly`, `Dead`, `NotRunning`, `Refused`, `Failed`, `EffectRequested`, `EffectOutcome`, `CaptureOrphan`, `CaptureCompensated`, `CleanupPending`, `CleanedUp` | coarser, not framed by Fluxtion |

Every control-processor record carries `record`, `seq` (group-local, gap-free while the sink works), `requestId` and
`rootId`. Records caused by another record also carry `causeSeq`; records caused by an effect carry `causeOp`.
**Control-processor records for one request share its `rootId`**: input, decisions, effects, pending and final
outcomes, compensation, late completions and the reply. Fallback cleanup uses its available resource and operation
identities; see [reading it](#reading-it).

Data processors keep their own Fluxtion audit. The control audit does not record what happens inside them.

### Getting the records

```java
ControlAuditSink audit = group.control().audit();   // group: a ComposingEventProcessorAgent
List<String> records = audit.records();
boolean complete = audit.isComplete();               // false once anything was dropped or failed to write
long total = audit.total(), dropped = audit.dropped(), failures = audit.sinkFailures();
```

`MongooseServer` has no public accessor for its groups or their control audits in this build. The control audit is
reachable from a `ComposingEventProcessorAgent` you hold, as the tests do.

### Exporting it, with a completeness manifest

```java
Path yaml = audit.export(Path.of("target/control-audit/pricing.yaml"));
ControlAuditExport.Verification v = ControlAuditExport.verify(yaml);   // reads only the two files
if (v.permitsInvariantPass()) { /* COMPLETE */ }
```

- `export(path)` writes the YAML to `path`, and a sidecar to `path.manifest.yaml`, from one locked snapshot.
- The sidecar holds `format: mongoose-control-audit`, `version: 1`, the `sha256` of the exact exported bytes,
  `total`, `retained`, `dropped`, `sinkFailures` and `complete`.
- `verify(path)` returns `COMPLETE`, `INCOMPLETE` or `UNKNOWN`. A missing, malformed, inconsistent or mismatched
  sidecar is `UNKNOWN`. `COMPLETE` is necessary, not sufficient, for an invariant pass: it establishes export
  completeness, not correct behaviour. The test validator requires `ControlInvariants.Result.verified()`, which
  rejects both violations and unverifiable evidence; `ok()` alone is insufficient. Failed physical closes remain
  unverifiable even in a complete export and must agree with the resource ledger.
- `exportText()` returns the raw YAML with no manifest.
- Copy both files together. The hash detects accidental change or a mismatched pair. It is **not** a signature and does
  not authenticate a pair rewritten on purpose.
- An export started again on the same path deletes the old sidecar first, so an interrupted export never claims to be
  complete.

### Reading it

The export is a sequence of YAML documents, one per record, each preceded by `---`. To follow one request, select the
records with its `rootId`. Control records have `seq`, fallback records have a separate `fallbackSeq`; preserve
export order when comparing them. Fallback cleanup can instead link by `opId`, `resourceId` and generation, and may
not carry the original request's `rootId`. Include these resource-linked records when checking physical cleanup.

To investigate in the Fluxtion audit-log analyser, the design ("Investigating through the analyser") pairs three
artefacts:

- the exported YAML, opened as the audit log;
- the control processor's graph, `src/main/resources/com/telamin/mongoose/control/generated/GroupControlProcessor.graphml`;
- its authored source, `src/main/fluxtion/control/control-processor.xml`, and the generated
  `GroupControlProcessor.java`.

With those loaded, `read` selects one `rootId` or `generation`, `aggregate` counts effects requested against final
outcomes per `opId`, and `topology` and `source` name the rule node that decided.

### Limits

- The ring is bounded. When it drops records, `isComplete()` is `false` and the manifest says so. This build writes
  no separate `Gap` record.
- The audit covers the control processor's inputs, decisions, effects and replies. It does not cover the inside of
  data processors, or what a sender did before its request reached the mailbox.
- A node that writes no record is not proof that it did not run.

## Configuration keys

| Key | Default | Applies to | How to set |
| --- | --- | --- | --- |
| `controlWaitMillis` | `10000` | How long void `addEventProcessor`, `stopProcessor(group, name)`, `registerService` and `removeService` wait, off agent threads | YAML top-level key, or `MongooseServerConfig.setControlWaitMillis(long)` |
| `shutdownTimeoutMillis` | `30000` | The end-to-end deadline of `shutdown()` / `stop()` | YAML, `setShutdownTimeoutMillis(long)`, or `MongooseServerConfig.builder().shutdownTimeoutMillis(long)` |
| `addFailurePolicy` | `REPORT` | What void `addEventProcessor` does with a reply that is not `APPLIED` | YAML (`REPORT` or `THROW`), or `setAddFailurePolicy(AddFailurePolicy)` |
| `shutdownDrainTimeoutMillis` | `2000` | The drain phase of `shutdown()`, within `shutdownTimeoutMillis` | YAML, setter or builder (unchanged) |
| Capture open timeout | 30 s | How long one audit-capture open may take | Per group only: `ComposingEventProcessorAgent.setCaptureOpenTimeout(Duration)`. Not a server key |
| Capture open limit | 8 | Physical capture opens in flight per group | Per group only: `ComposingEventProcessorAgent.setCaptureOpenLimit(int)`. Not a server key |
| Capture resource limit | 1024 | Open, installed, closing or failed-close resource slots per group | Per group only: `ComposingEventProcessorAgent.setCaptureResourceLimit(int)`. Not a server key |
| Full-mailbox timeout | 10 s | How long a producer backs off on a full group mailbox, off agent threads | Per group only: `ComposingEventProcessorAgent.setControlQueueFullTimeout(Duration)` |
| `mongoose.control.auditCapacity` | `10000` records | Retained control audit records per group, read at construction | JVM system property (`-D`), not a YAML key |

`controlWaitMillis` and `addFailurePolicy` have no builder method in this build. Set them on the config object or in
YAML.

```yaml
controlWaitMillis: 5000        # void control methods wait at most 5 s
shutdownTimeoutMillis: 20000   # stop() returns, complete or not, within about 20 s
addFailurePolicy: THROW        # void addEventProcessor throws when the add does not apply
```

## Not guaranteed

These are deliberate non-guarantees in this build:

- A void control method that times out has **not** failed. Its request still applies.
- A stage dependent attached after completion runs on the attaching thread. That thread can be an agent thread.
- `addProcessor` and `stopProcessor(handle)` never back off for mailbox capacity, but synchronous setup and JVM
  scheduling do not have a hard real-time bound.
- `stopProcessor(group, name)` stops what was admitted under the name **at call time**, not at the time it is applied.
- A removed service whose withdrawal no group confirmed is stopped only by `shutdown()`.
- `removeService` during shutdown is silently ignored. It does not throw.
- A `StopResult` that is not complete leaves the server `STOPPING`. Nothing resumes it unless `shutdown()` is called
  again.
- Replay does not detect arbitrary hidden inputs or prove service state/build equivalence. It refuses unavailable
  positive service versions and failed callbacks; new recordings omit skipped duplicate deliveries. See
  [when a replay stops](#when-a-replay-stops).
- Provenance compares class names. It does not identify a build, a configuration or a generated-graph fingerprint.
- The control-audit manifest detects accidental change. It is not a signature.
