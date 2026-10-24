# Integration review response — PR #69

Implementation base: `f54a768ffefab556441e5c5a9bf9a0cef7a8010a` (`integration/next-release`).
The independent review originally covered `68f7c065`; all four review scenarios still reproduced on the updated base.
This is an implementation response, not independent approval or release authorisation.

## Corrections

| Finding | Correction | Maintained wrong-result witness in `IntegrationReleaseRegressionTest` |
|---|---|---|
| F1: startup could reactivate a source after stop completed | Admit each synchronous startup effect under a lifecycle fence; signal cancellation of an in-progress callback, await its return, then perform final ordered cleanup. Later startup steps are refused. Startup/shutdown callbacks may request stop without waiting for themselves. | `stoppedStartupCannotStartAnEventSourceLater`: stop must remain incomplete while the callback can still acquire resources; the next source must never start and the callback's late resource must be stopped. |
| F2: abandoning a reader leaked pooled backlog | Close drains and releases discarded references without dispatch. A reader removed before joining the composite is closed explicitly. | `removingAReaderReleasesItsQueuedPooledEvents`: reference count must become zero and the object return to its pool. Eight cases cover pending/active readers and all four wrap strategies. `closingAReaderReleasesWrappedBacklogWithoutDispatch` adds plain, named, journal, replay and broadcast backlog cases. |
| F3: rejected enqueue retained its name | Roll back the accepted registration if enqueue fails, independently of supplier failure cleanup. | `rejectedEnqueueDoesNotReserveTheProcessorName`: fill a held real group's start queue; rejected supplier runs zero times; after draining, the same name is accepted and starts. |
| F4: pre-start stop freed a name before consuming its stop | Associate accepted starts and stops with registration identity. Release only that registration's name when its stop is consumed; a delayed old stop cannot target a newer registration. | `stopBeforeGroupStartsKeepsItsNameUntilThatStopIsConsumed`: re-add is refused while the old stop is pending; after consumption the original is stopped and torn down and its replacement stays running. |

Additional controls: `aStartupCallbackCanRequestStopWithoutWaitingForItself` covers startup and shutdown reentrancy;
`aStopForAFailedRegistrationCannotStopItsReplacement` covers a replacement started before the old stop is consumed.

The lifecycle fence does not run during event delivery. A synchronous callback already in progress may receive
`stop()` first as a cancellation signal and again during final cleanup: it may have acquired resources after the
cancellation signal. As with a stuck agent callback, shutdown cannot honestly complete while a synchronous startup
callback never returns. Such callbacks must cooperate with cancellation. Normally started services retain the
existing ordered stop path.

The name remains reserved until the accepted stop is consumed, including before a group starts. This deliberately
refuses an immediate pre-start replacement; it does not silently replace or discard either registration. Existing
group add/remove entry points remain available, and the server uses the identity-bearing overloads.

## Verification

- Four original review probes on the updated base: **4 assertion failures, 0 errors**.
- Maintained tests with the three original production implementations restored temporarily: **16 tests,
  16 named assertion failures, 0 errors, 0 skips**. Restoring the fixed source from byte copies was byte-identical
  (SHA-256 checked). This was a targeted regression demonstration, not a full mutation campaign.
- Restored focused suites: **84 tests, 0 failures, 0 errors, 0 skips**, including **18 new regression cases**.
- Clean `mvn -o -q clean test`: **491 tests, 0 failures, 0 errors, 9 skips**, across **110 XML reports**.
  Exact-head CI is reported with the pushed response.
- An initial scratch fixture and an initial replay-wrapper fixture had setup/compile errors; these were corrected
  and are not counted as wrong-result witnesses. The new replay case uses the runtime's actual `ReplayRecord`.

Focused suites: `IntegrationReleaseRegressionTest`, `LifecycleRacesTest`, `OrderedServerStopTest`,
`ProcessorStopReleaseTest`, `ProcessorStartFailureTest`, `AgentControlQueueFullTest`, `PooledEventReleasePathsTest`,
`RetryOnlyTheFailedProcessorTest`, `AuditControlOnAgentThreadTest`.

## Single-publisher lock measurement

JMH 1.37, Corretto JDK 21.0.9 on ARM64 macOS; one thread, two forks, three 1-second warmups and five 1-second
measurements per fork; a fixed 512 MB heap and GC profiler. Payload and queues were preallocated, NOWRAP, one
subscriber, no journal/cache/counters. The unsafe benchmark-only publisher copy differed only by removing the
`synchronized (publishLock)` blocks; it was never installed in production. Both paths retained the subscriber-change
check. The benchmark returned the polled object to keep the work observable.

| Operation | ns/op (JMH reported error) |
|---|---:|
| Volatile increment | 1.798 ± 0.069 |
| Monitor plus volatile increment | 3.266 ± 0.018 |
| Publish and queue poll, monitor enabled | 8.619 ± 0.454 |
| Publish and queue poll, monitor removed | 8.027 ± 0.290 |

These figures prevent benchmark-method inlining to avoid coarsening locks across successive benchmark calls.
An earlier normally inlined run gave 9.498 versus 7.953 ns/op for publish/poll. The differences are small and
machine-specific; this is not a contention, cross-core handoff, end-to-end latency or capacity benchmark.
All four measured cases had zero GC collections; normalised allocation was approximately 0.00001–0.0001 B/op,
at the profiler/background noise floor. This supports negligible per-operation allocation in this scenario,
not a claim that every Mongoose path is allocation-free. The initial benchmark lacked Agrona's module-access flag
and produced no usable measurement; the reported runs include it.

The production publisher's event path is unchanged by this response. New registration objects and lifecycle work
are confined to adds, stops and startup/shutdown. No hosted generation or plugin reactor was run.
