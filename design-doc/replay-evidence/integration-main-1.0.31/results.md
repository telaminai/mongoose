# #48 integrated with released main (1.0.31): results

Merge `1c18def` (parents: `621045b`, the branch after the review nits; `59b9d8f`, main at 1.0.31). JDK 21.0.9, Maven run serially.
Predictions: [`predictions.md`](predictions.md), committed first (`1195dd4`).

## Predictions against outcomes

| prediction | outcome |
|---|---|
| conflicts in the controls harness and the spec; the other files merge clean | as predicted, but `GroupRecorder`, `AbstractEventToInvocationStrategy` and `pom.xml` auto-merged and were read, not trusted |
| the `ran()` check must sit in main's `afterDispatch` after the clock capture, before `AdminInvoked` | it merged there textually; read and confirmed |
| `processEventRecording` bypasses the invoker's muted refusal, harmlessly | confirmed: one `ReplayConfig.Mode` per server, so a recorder and muting never coexist |
| 99 distinct controls, 8 differing | 99 and 8. Main changed 7 to follow its own code, and the branch changed R6 |
| the refused-case regression records no `AdminInvoked`, and a control removing `ran()` catches it | as predicted: caught with two `AdminInvoked` for one command that ran |
| **not predicted** | main's f6 pair (`ReplayIndependentReviewTest`) failed. A lambda admin command now runs as the processor's own event cycle, and fluxtion 1.1.0's `runInEventCycle` reads the clock once on receipt. Both tests were moved to a timer firing that reads no clock; main's two ir-6 controls still catch them |

## Runs

| run | total / failures / errors / skips | reports |
|---|---|---|
| focused suites, merged tree, before the f6 change | 112 / 2 / 0 / 0 (the two f6 tests) | 15 |
| `mvn -q clean test`, before the gate | 349 / 0 / 0 / 9 | 92, no orphans |
| gate `python3 design-doc/replay_controls.py` | 101 of 101 detected: 91 named assertions, 6 expected-message timeouts, 4 expected-message errors; exit 0 | [`controls-gate.txt`](controls-gate.txt) |
| restored-green `mvn -q clean test` | 349 / 0 / 0 / 9 | 92, no orphans |

Requested and detected names match: 101 each, none missing, none extra, no duplicates. Before the full gate, targeted runs
caught `ir-6-a-cycle-with-no-reads-records-none`, `ir-6-the-read-count-must-match-exactly`,
`int-refused-work-is-no-invocation` and `nit1-the-timeout-message-promises-no-retraction`, each at a named assertion.

The expected-message detections: `R4-replay-never-fires-by-itself`, `R5-pins-the-entry-instant`,
`R5-delivers-to-the-processor-alone`, `csv-the-journal-reads-back-its-file`, `csv-the-store-reads-back-its-file` and
`review-2-an-undeliverable-entry-stops-the-replay` (timeouts); `B1-F2-a-refused-caller-is-answered`,
`F2-a-command-failing-before-it-runs-is-answered`, `review-5-a-torn-last-line-is-dropped` and
`review-reB-a-journal-failure-never-escapes-publish` (errors).

Historical figures, by revision: at 8d224fb, 291 / 0 / 0 / 9, 87 reports, and a gate of 59 of 59 (49 / 6 / 4); at main
59b9d8f (1.0.31), 312 / 0 / 0 / 9, 83 reports, and 72 controls registered.
