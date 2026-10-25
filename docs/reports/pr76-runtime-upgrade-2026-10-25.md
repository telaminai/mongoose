# Fluxtion 1.1.3 / 1.0.80 upgrade checkpoint

This is implementation evidence, not final release qualification. It follows the runtime 1.1.2 checkpoint
at `dfbfdd9025c28f2488784abf57da473858ff5c01`. Core and plugins now select runtime **1.1.3** and
the **1.0.80** compiler/BOM pairing. No client migration clearance is implied.

## Generated code review

`mvn -o -Pregen process-test-classes` regenerates the control graph and a small client-style recovery graph.
The generator provider was built locally from compiler release tag `v1.0.80`, commit
`4b7d35912cd7a38e2597eaf5adb60f672aa421e8`; the public runtime and builder are the released artifacts.
The ordinary build uses committed generated sources and does not need the generator provider.

The control processor's authored wiring and graph fingerprint are unchanged:
`b954fa5595eb09cdce40daf8f7572c566b4e95b28ca88bfd41753f06f2de5343`.
The generated diff contains:

- Runtime/compiler version stamps and the GraphML toolchain version update. GraphML has no other change.
- Failed-cycle cleanup and `try`/`catch`/`finally` boundaries in event dispatch, lifecycle and service dispatch.
  The clock's shared-reading state is restored in `finally` around queued callbacks.
- Buffered input and calculation use `try`/`catch` cleanup and reset buffering on failure. They do not set the
  processing flag, so these boundaries are not literally `try`/`finally` blocks.
- `runInEventCycle` closes and then discards failed work. `failedCycle` resets dirty state, closes auditors,
  preserves the original exception and discards queued work last. There are exactly three discard call sites.
- Formatting changes inside the newly wrapped dispatch bodies. No rule, node wiring or decision order change.

## Focused acceptance

Java 21, `mvn -o -Dtest=CompiledClientRecoveryTest,GeneratedRecoveryContainmentTest,GeneratedProcessorFailureIsolationTest,WedgedProcessorRetryMasksFailureTest,ReplayRecordingAcceptanceTest test`:
**BUILD SUCCESS — 25 tests, 0 failures, 0 errors, 0 skipped**.

`CompiledClientRecoveryTest` uses an actual Spring-authored, compiled application node. It performs an effect,
queues input 99 and throws on input 1. A direct call with input 2 then produces effects `[1, 2]`, never 99.
There is exactly one well-formed audit record for each input. The separately checked listener-installation
configuration record is excluded before sending those two inputs. The analyser MCP independently reads both
records, returns total 2 and values 1 and 2, and verifies the logged node belongs to the generated graph.
This proves parser compatibility and these particular observations, not complete evidence of every runtime path.

When hosted, the same graph reports exactly one ERROR carrying the original exception, is removed from dispatch
without retry, and its healthy peer receives both inputs. The approved Mongoose isolation policy is unchanged:
framework cleanup does not undo application state or external effects. D4 now waits for explicit quarantine and
checks one failed attempt, preserving the recording's Failed marker and the replay's stop assertion.

The legacy regression uses a real older generated processor: direct calls demonstrate its wedge, while hosting
it refuses a configured three-retry policy after the first failure, emits one ERROR and never claims recovery.
The generated recovery negative control failed before regeneration even with runtime 1.1.3 installed.

Three existing tests expected one invocation of a throwing auditor. The new failed-cycle close makes a second
attempt, so they now assert two auditor failures while independently checking business-handler counts, healthy
delivery and pooled references. A timer's audit prologue can fail before its handler runs; that test checks zero
handler entries. These are cleanup calls, not business retries.

Development failures were corrected, not counted as qualification: a missing setter prevented the generated
test fixture's context injection; the audit assertion initially counted its setup event; and the timer assertion
initially expected a handler entry before the failing audit prologue. A sandboxed generator JVM exited 134;
the complete generation succeeded outside that sandbox. Full regression, stress, soak, downstream and starter
results must be recorded separately against the final named commits.

## Downstream boundary

Starters must be tested with explicit RC overrides before changing playground defaults. Published artifacts must
then resolve from an empty Maven cache; locally installed previews do not prove publication. Startup, event flow,
capture/export and clean shutdown are required alongside compilation. Deployment of RC starter pins must identify
the pre-release status and does not clear an existing client to migrate.

See the [migration gate](../release/client-migration-1.0.33.md) and
[release procedure](../release/rc-release-procedure.md).
