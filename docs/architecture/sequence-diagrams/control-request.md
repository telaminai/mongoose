# Applying a control request

<div class="architecture-diagram">
--8<-- "docs/assets/architecture/control-request.svg"
</div>

The diagram shows an admitted request with synchronous effects. `ControlDriver` claims it and invokes the
Spring-authored, generated `GroupControlProcessor`. Rule nodes decide and log requested effects. Once the decision
cycle publishes, the driver calls `batchEnd()` to run those effects through the group's adapter.

`processAsNewEventCycle` queues effect results while the generated processor is processing; each result then
becomes an audited event cycle. A result may request another effect. The driver bounds settling at 64 rounds
and records failures rather than silently claiming success. Capture I/O instead returns a pending result and
later posts a completion through the separate completion queue.

## Ordering and replies

- Commands are decided in mailbox publication order within one group. Concurrent callers have no prior ordering.
- Completions are drained separately before commands. A later command may finish before an earlier open waiting on I/O.
- A terminal sweep answers outstanding requests on close or death. Cleanup of a dead group's processor state
  requires ownership handoff after its thread has terminated and been joined.
- Public stages for admitted requests complete through the reply executor. A synchronous continuation attached
  after completion runs on the attaching thread; use `thenAcceptAsync` for blocking work.
- Timing out after admission detaches the caller; it does not cancel the request. Full-mailbox admission expiry
  is different: that request is refused and cannot later apply.

Calls from agents, startup and stop contexts skip reply waiting. Never call `join()` or `get()` on a control stage
from an agent. See [threading rules](../../reference/control-plane-contracts.md#threading-rules).

The generated decision graph does not replace admission, request claiming or death detection. Those remain
concurrent Java protocols around the single-threaded decision processor.
