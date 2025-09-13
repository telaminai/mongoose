# Guide: Using the Scheduler Service in Mongoose server

This guide explains how to use the built‑in scheduler to run actions in the future or after a delay. The scheduler is
exposed to processors and services as `SchedulerService` and implemented by `DeadWheelScheduler` under the hood.

You’ll learn:

- What the SchedulerService is and when to use it
- How the scheduler is wired into processors and services
- API overview: scheduleAtTime, scheduleAfterDelay, time helpers
- Patterns: one‑shot timers, periodic jobs (by rescheduling), triggering event cycles
- Threading and safety considerations
- References to examples and source code

## What is the SchedulerService?

`SchedulerService` provides simple, low‑overhead scheduling primitives backed by an Agrona deadline timer wheel. It
offers:

- scheduleAtTime(epochMillis, action)
- scheduleAfterDelay(delayMillis, action)
- milliTime()/microTime()/nanoTime() helpers

Interface:

```java
public interface SchedulerService {
    long scheduleAtTime(long expireTimeMillis, Runnable expiryAction);
    long scheduleAfterDelay(long waitTimeMillis, Runnable expiryAction);
    long milliTime();
    long microTime();
    long nanoTime();
}
```

The default implementation is `com.telamin.mongoose.service.scheduler.DeadWheelScheduler`, which also implements
`com.fluxtion.agrona.concurrent.Agent` and polls timers on an agent thread.

## How the scheduler is wired in

You do not need to manually register a scheduler. Mongoose server provides one and injects it into:

- Each StaticEventProcessor hosted in an agent group
- Each worker service (agent‑hosted service)

Injection happens via `@ServiceRegistered` methods. Two examples in this repository:

- Event sources using `AbstractEventSourceService` receive the scheduler through:
  ```java
  @ServiceRegistered
  public void scheduler(SchedulerService scheduler) { this.scheduler = scheduler; }
  ```
- Any processor or service can declare a similar injection point.

Under the covers, the composing agents (`ComposingEventProcessorAgent` and `ComposingServiceAgent`) create a
`DeadWheelScheduler` and register it as a `Service<SchedulerService>` that is injected into components in that group.

## Basic usage

### One‑shot delay

```java
@ServiceRegistered
public void scheduler(SchedulerService scheduler) {
    this.scheduler = scheduler;
}

public void doSomethingLater() {
    scheduler.scheduleAfterDelay(250, () -> {
        // code to run ~250ms later
        publish("tick");
    });
}
```

### Schedule at a wall‑clock time (epoch millis)

```java
long runAt = System.currentTimeMillis() + 1_000; // ~1s from now
scheduler.scheduleAtTime(runAt, this::rollover);
```

### Periodic job (rescheduling pattern)

The API is one‑shot; to run periodically, reschedule from inside the task.

```java
private void scheduleHeartbeat(long periodMs) {
    scheduler.scheduleAfterDelay(periodMs, () -> {
        emitHeartbeat();
        scheduleHeartbeat(periodMs); // reschedule
    });
}
```

A similar pattern is used in the example `HeartBeatEventFeed` (see
src/test/java/com/telamin/mongoose/example/HeartBeatEventFeed.java), which publishes a heartbeat at a configurable
interval (that example also shows an Agent‑based doWork loop alternative).

### Trigger an event cycle after a delay

Use `ScheduledTriggerNode` when you want to schedule a new event cycle (callback) into the event processor:

```java
public class MyTrigger extends com.telamin.mongoose.service.scheduler.ScheduledTriggerNode {
    public void startLater() {
        triggerAfterDelay(100); // fires a new event cycle in ~100ms
    }
}
```

`ScheduledTriggerNode` injects the scheduler via `@ServiceRegistered` and calls `fireNewEventCycle()` when the delay
expires.

## Threading model and safety

- DeadWheelScheduler runs on an agent thread (it is an Agrona `Agent`). Expiry actions (`Runnable`) execute on the
  scheduler’s agent thread, not on your processor’s event thread.
- If your action needs to interact with a processor’s single‑threaded state, prefer to publish an event or use a
  callback node to marshal back into the processor’s context.
- Keep expiry actions non‑blocking and lightweight.
- Time helpers (`milliTime/microTime/nanoTime`) return times based on the scheduler’s clock.

## Cancellation and IDs

Both scheduling methods return a long ID, but the current API does not expose a cancellation call. Treat timers as
one‑shot. For periodic work, use the rescheduling pattern shown above. If you need explicit cancellation, guard your
actions with a volatile flag you check at the start of the runnable.

Example:

```java
private volatile boolean running = true;

public void startPeriodic() {
    scheduleHeartbeat(500);
}

public void stopPeriodic() {
    running = false;
}

private void scheduleHeartbeat(long periodMs) {
    scheduler.scheduleAfterDelay(periodMs, () -> {
        if (!running) return; // simple cancel guard
        emitHeartbeat();
        scheduleHeartbeat(periodMs);
    });
}
```

## End‑to‑end examples in this repository

- Heartbeat publisher (uses agent loop, shows timing/rate):
  `src/test/java/com/telamin/mongoose/example/HeartBeatEventFeed.java`
- Simple scheduled trigger node: `src/main/java/com/telamin/mongoose/service/scheduler/ScheduledTriggerNode.java`
- Injection sites:
    - `AbstractEventSourceService.scheduler(SchedulerService)`
    - `ComposingEventProcessorAgent` and `ComposingServiceAgent` where the scheduler is created and injected

## Tips and pitfalls

- Prefer rescheduling over loops/sleeps—expiry actions should be quick.
- Don’t perform blocking IO in expiry actions; dispatch work to appropriate components or queues.
- For processor state mutations, marshal via events/callback nodes to stay within the processor’s single thread.
- Use `milliTime()` instead of `System.currentTimeMillis()` when aligning to the scheduler’s notion of time.

## References

- Scheduler
  interface  [SchedulerService.java](https://github.com/gregv12/fluxtion-server/blob/main/src/main/java/com/telamin/mongoose/service/scheduler/SchedulerService.java)
- Default
  implementation: [DeadWheelScheduler.java](https://github.com/gregv12/fluxtion-server/blob/main/src/main/java/com/telamin/mongoose/service/scheduler/DeadWheelScheduler.java)
- Trigger
  helper: [ScheduledTriggerNode.java](https://github.com/gregv12/fluxtion-server/blob/main/src/main/java/com/telamin/mongoose/service/scheduler/ScheduledTriggerNode.java)
- Wiring an event
  processor: [ComposingEventProcessorAgent.java](https://github.com/gregv12/fluxtion-server/blob/main/src/main/java/com/telamin/mongoose/dutycycle/ComposingEventProcessorAgent.java)
- Wiring a
  services: [ComposingServiceAgent.java](https://github.com/gregv12/fluxtion-server/blob/main/src/main/java/com/telamin/mongoose/dutycycle/ComposingServiceAgent.java)
