# Third-review follow-up — implementation checkpoint

This addresses the independent review of `44190ab` on branch `review/pr76-third-review` (`6fb26aa`).
It is an implementation checkpoint, not the frozen release qualification. The previous 600-second soak and plugin
preview results do not qualify these later runtime changes. Publication remains separate from client migration.

| Finding | Disposition and proof |
| --- | --- |
| F1: generated retry masks failure | Quarantine known generated/interpreted processors after hosted callback failure; remove all data routes and timers; preserve healthy peers, names and unresolved resources; never re-enter damaged lifecycle; report ERROR, `ProcessorQuarantined` and incomplete shutdown. `GeneratedProcessorFailureIsolationTest` uses the actual generated `GroupControlProcessor`, including real Chronicle listener installation/restoration, multiple queues, fanout, pooled references, startup/stop failures, replay and opaque dispatch. |
| F2: standalone failed open deletes another recording | A legacy shared directory is removed only atomically when empty. Nonempty partial/live files survive. Unique routed directories retain operation-owned cleanup. `StandaloneFailedOpenDeletesConcurrentRecordingTest` adapts the review's concurrent reproduction and requires the live recording to survive. |
| F3: name-only stop lacks independent release proof | At the reply, the test-owned ledger checks stop/teardown counts and capture installation for all created instances of that name. Late cleanup cannot erase the observation. The new name-only source mutant must fail specifically with the independent callback-ledger diagnostic. |
| F4: evidence overclaims | Historical README corrected: name-only coverage caveat, 18/37 and 19/37 committed mutation counts, shared-directory cleanup caveat, and the former host retry masking. Historical results are not relabelled as results of the new runtime. |
| F5: drop evidence limits | Contract states first-ever per binding, bounded shared audit capacity and churn-driven incompleteness; counters-disabled health remains UNKNOWN despite publisher counts. |
| F6: standalone group-owned capture | Processor stop no longer closes a queue owned by a capture handle. The proxy around a real Chronicle queue independently counts one physical close. Javadoc no longer promises capture continuation into replacement. |

Owner decision 5 is applied as a structured driver-fallback marker only:
`replyAuthor: DRIVER`, `failureKind: FAILED_DRIVER_FALLBACK`. The validator rejects it without relying on prose;
legacy exports retain reason matching. Broader failure policy and overload design remain outside this change.

Independent follow-up inspection found additional containment edges, now covered: capture operations can
originate the graph failure; generated listener-restore errors must not be swallowed; replay/start/stop failures
must retain ownership; an opaque dispatcher must retain every possibly damaged generated target before ending
the group. An unowned generated subscriber fails the group instead of silently retrying indefinitely.

The original standalone scheduler reporting contract is retained: cancel the failed owner's remaining timers
and report ERROR; hosted groups install their isolation handler. Existing 1.0.33 regression assertions are unchanged.
The first broad run exposed the standalone scheduler API regression; it was fixed without changing the existing
replay assertion. A subsequent broad run exposed admin reply timeouts and a retained replay processor appearing
running. Route refusal/queued-command rejection, running-list filtering and replay detachment address those failures;
the original failing admin/replay tests pass unchanged. Follow-up review found that an empty reader could consume
an already queued second admin request after the first caused quarantine; a batch-level drain fence now leaves
that backlog for explicit close-time refusal. A speculative expansion to quarantine every pre-action admin-cycle refusal was rejected by the unchanged
`AdminIntegrationRegressionTest`. Inspection of runtime 1.1.2 and the actual generated processor confirms that
`runInEventCycle` already clears its processing flag in `finally`; unlike raw event dispatch, an intentional refusal
before the action is supported. That expansion and its synthetic override fixture were removed. Existing refusal
semantics remain intact; custom overrides making additional unsafe graph calls need their own failure contract. These development failures are not release qualification.

No compiler flag reset, automatic restart or transactional rollback is claimed.

The committed mutation runner requires an explicit SHA and uses a fresh scratch clone. Run it with Java 21 and
a warm Maven cache. Its six mutants must compile and fail assertions; the name-only mutant must be rejected by
the independent callback ledger. Qualification outputs will be recorded separately against a named commit.
