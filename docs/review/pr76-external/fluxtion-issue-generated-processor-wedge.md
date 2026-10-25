# Fluxtion issue (ready to paste): generated processor wedges silently when a handler throws

**Runtime observed:** Fluxtion runtime 1.1.2, generated processor
`com.telamin.mongoose.control.generated.GroupControlProcessor` (Mongoose PR #76, commit 4f5851e). The shape is the
generator's, so it applies to every generated processor.

## Observed generated code

- `processEvent` (~l.299-313):
  `if (processing) { callbackDispatcher.queueReentrantEvent(event); } else { processing = true; onEventInternal(event);
  if (callbacksPending) { ... dispatchQueuedCallbacks(); ... } processing = false; }` with no try/finally.
- `batchEnd` (~l.690-696): `processing = true; <@OnBatchEnd nodes>; afterEvent(); callbackDispatcher.dispatchQueuedCallbacks();
  processing = false;` with no try/finally.
- `beforeServiceCall` / `afterServiceCall` (~l.680-688): same shape, no finally.
- `runInEventCycle` (~l.715-741) already has the correct shape: an outer finally runs `afterEvent()` and
  `dispatchQueuedCallbacks()`, an inner finally clears `processing` innermost. The generator knows the pattern; it
  is not applied to the event, batch and service-call paths.

## Failure

1. A node handler throws inside a cycle. The exception propagates out of `onEvent` or `batchEnd`.
2. `processing` is left `true`.
3. Every later `onEvent` takes the re-entrant branch and queues the event for a dispatch that never happens.
4. No exception, no log, no audit record: the processor accepts and silently swallows every subsequent event.

Hosts have to compensate. Mongoose does so in `EffectQueue` (catch-all around every effect and around
`processAsNewEventCycle`), `ControlDriver.dispatch`/`settle` (detect the throw, mark the group dead, answer
FAILED) and `ControlDriver.drain` (refuse a wedged processor). Those guards are load-bearing today.

## Requested change

1. **Generate `processEvent`, `batchEnd` and the service-call bracket with the `runInEventCycle` shape.** Outer
   finally: `afterEvent()` then `dispatchQueuedCallbacks()`. Inner finally: `processing = false`. A throw from a
   handler must leave the processor able to dispatch the next event, and the original exception must still
   propagate to the caller.
2. **Define the re-entrant queue's behaviour on an exceptional exit.** If a queued re-entrant event's handler
   throws during `dispatchQueuedCallbacks`, state whether the remaining queued events stay in the dispatcher and
   are dispatched at the end of an unrelated later cycle (stale, out of order). Choose one: drain-and-discard on
   exceptional exit with one audit record naming the discarded count, or dispatch the remainder in the finally.
   Do not leave it implicit.
3. **Close the audit record on the throw path.** `afterEvent()` completes the cycle's `EventLogger` record. On a
   throw today the record is left open and the analyser shows a gap. The finally must emit the record with the
   failure visible (node id, handler, exception text) so a failed cycle is evidence, not absence.
4. **Keep `runInEventCycle`'s not-re-entrant check.** It is correct as is.
5. **Generated regression test** (next to `GeneratedSourceOnOldRuntimeTest`): a handler throws; assert the
   exception reached the caller; assert the next `onEvent` is dispatched normally; assert the re-entrant queue is
   in the documented state; assert the audit record for the failed cycle exists and names the node.
6. **Document the one-record-per-node-per-cycle constraint** of `EventLogNode`, or support more than one record
   per cycle. Mongoose's `CaptureRules` folds notes into fields of a single Decision record to work around it;
   other control-style processors will rediscover the constraint.

## Compatibility

Pure generator change; generated source must still compile against the oldest supported runtime
(`GeneratedSourceOnOldRuntimeTest`). No API change for hosts. Behaviour change only on a path that today ends in
a silent wedge.

## Follow-up in Mongoose once the runtime ships

Regenerate `GroupControlProcessor`; add a test asserting the "control processor threw" dead path is never reached
by a throwing rule (the processor now recovers and the request is answered FAILED by the rule's own outcome
handling); keep the `EffectQueue`/`ControlDriver` guards as belt-and-braces but remove compensation the generator's
guarantee makes redundant, citing the lines removed.
