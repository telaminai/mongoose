# Bounded, resumable shutdown

<div class="architecture-diagram">
--8<-- "docs/assets/architecture/shutdown.svg"
</div>

`MongooseServer.shutdown()` fences new admission and snapshots the groups. `LifecycleManager` advances through
ordered phases using one deadline. A potentially blocking `AgentRunner.close()` or service stop runs on a tracked
helper; the caller joins it only for the remaining time.

Feeds are stopped first, including taking feeds off mixed service groups. Remaining non-feed service dependencies
are retained while any processor group is running. The queue-drain phase has its own bound capped by the overall
deadline; on expiry it may drop the remaining queued events. An empty queue does not prove that an already-polled
handler has finished, so closing and joining the processor groups remains essential.

If a callback is stuck, shutdown returns an incomplete `StopResult` and the server remains `STOPPING`. Calling
shutdown again rejoins existing helpers and resumes unfinished phases. It does not forcibly kill callback threads
or repeat a completed close. Failed service stops and unresolved physical captures remain visible; a second call
is not a guarantee they can recover.

For a never-started server, shutdown closes admitted groups and answers queued adds without running their suppliers.
Calls from agent/startup contexts hand the stop to a separate thread. See
[shutdown contracts](../../reference/control-plane-contracts.md#shutdown) for exact results and timeout settings.
