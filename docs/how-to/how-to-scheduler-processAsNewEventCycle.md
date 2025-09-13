# How-to: Re-entrant publishing with processAsNewEventCycle and SchedulerService

This guide explains how to publish events back into Mongoose's event processing cycle from inside your handler using `processAsNewEventCycle`, and how to combine it with `SchedulerService` callbacks for periodic or delayed re-entry.

## Key concepts
- `NodeContext.processAsNewEventCycle(Object event)`: Injects an event into the event processor as a brand new event cycle. The event will be mapped and dispatched through the configured event graph just like an external input.
- `SchedulerService`: Schedules a `Runnable` to be invoked at a later time (after a delay or at an absolute time). When the scheduled callback runs inside the processor thread, invoking `processAsNewEventCycle` will post a new event for normal on-handler processing.

Together, these two enable re-entrant event generation: handlers can schedule another invocation of themselves and publish another event cycle on each visit.

## Sample code

- Processor
  source: [ReEntrantHandler.java]({{source_root}}/test/java/com/telamin/mongoose/example/reentrant/ReEntrantHandler.java)
- Test
  node: [ReEntrantTest.java]({{source_root}}/test/java/com/telamin/mongoose/example/reentrant/ReEntrantTest.java)

## Minimal example
```java
public class ReEntrantHandler extends ObjectEventHandlerNode {
    private SchedulerService schedulerService;
    private int count;
    private long republishWaitMillis = 10;

    @ServiceRegistered
    public void schedulerRegistered(SchedulerService schedulerService, String name) {
        this.schedulerService = schedulerService;
    }

    @Override
    public void start() {
        publishReEntrantEvent();
    }

    private void publishReEntrantEvent() {
        // 1) Publish a new event into the processing cycle
        getContext().processAsNewEventCycle("Re-Entrant Event [" + count + "]");
        count++;

        // 2) Schedule the next callback that will again re-enter the cycle
        schedulerService.scheduleAfterDelay(republishWaitMillis, this::publishReEntrantEvent);
    }

    @Override
    protected boolean handleEvent(Object event) {
        // event is observed as if it came from outside
        return true;
    }
}
```

What happens:

1. `start()` triggers `publishReEntrantEvent()`.
2. The handler posts a new event using `processAsNewEventCycle(...)`.
3. The scheduler arranges for `publishReEntrantEvent()` to be called again after a delay.
4. Each scheduled callback runs on the event processing thread, and posting via `processAsNewEventCycle` causes a normal handler dispatch on the next cycle.

## Making tests deterministic
A pure re-entrant publisher will run forever. For tests and controlled demos, add a termination condition. For instance, stop scheduling after N events, or throw once N is reached.

```java
@Getter private int maxCount = 20; // test-configurable
@Getter private boolean throwOnMax = false;

private void publishReEntrantEvent() {
    getContext().processAsNewEventCycle("Re-Entrant Event [" + count + "]");
    count++;

    if (count >= maxCount) {
        if (throwOnMax) {
            throw new RuntimeException("Reached maxCount=" + maxCount);
        }
        return; // stop scheduling further events
    }
    schedulerService.scheduleAfterDelay(
            republishWaitMillis, 
            this::publishReEntrantEvent);
}
```

In a test, configure the handler, boot the server, wait for `count` to reach `maxCount`, assert, and then stop the server to exit cleanly.

```java
@Test
public void testReEntrant_countAndExit() throws InterruptedException {
    ReEntrantHandler handler = new ReEntrantHandler()
            .setRepublishWaitMillis(5)
            .setMaxCount(20)
            .setThrowOnMax(false);

    MongooseServerConfig mongooseServerConfig = new MongooseServerConfig().addProcessor("handlerThread", handler, "reEntrantHandler");
    MongooseServer server = MongooseServer.bootServer(mongooseServerConfig, lr -> {});

    long timeoutMs = 5_000;
    long start = System.currentTimeMillis();
    while (handler.getCount() < handler.getMaxCount() && (System.currentTimeMillis() - start) < timeoutMs) {
        Thread.sleep(10);
    }
    server.stop();
    Assertions.assertEquals(handler.getMaxCount(), handler.getCount());
}
```

Notes:

- `processAsNewEventCycle` ensures the posted event flows through the normal mapping/dispatching path, so your handler’s `handleEvent(...)` observes it just like external input.
- Using a scheduler to invoke a handler callback keeps all work on the processor thread, avoiding concurrency pitfalls while enabling periodic re-entry.
- For demonstrations, keep the delay small and the max count modest to keep tests fast and reliable.
