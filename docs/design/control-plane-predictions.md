# Control plane build: predictions, then actuals

Written 2026-10-25, before the first generated control processor ran. The actuals are filled in at the end of the
build and compared against each prediction, without editing the predictions.

## Predictions

| # | Prediction | Measure |
| --- | --- | --- |
| P1 | Spring authoring is productive for the topology: the XML will change about 5–10 times, mostly to add nodes, with at most 2 regeneration failures from Fluxtion's field-mapping rules | XML commits; regen failures |
| P2 | The control audit will expose at least 2 ordering or logic bugs in the rules before the generated processor has to be read; the generated processor will be opened to explain behaviour at most 2 times | Bugs found from audit; times the generated source was needed |
| P3 | The independent invariant checker will catch at least 1 defect that the example-based tests missed | Invariant violations found that no unit test caught first |
| P4 | Rewiring the group onto the control processor will first break 10–30 of the existing 499 tests, mostly ones coupled to the old queues, and the fixes will land mostly in the group's effect adapter, not in the rules | First full-suite run after rewiring; where fixes landed |
| P5 | The control rules themselves will be under 25% of the effort; integration, terminal admission, close and shutdown will be the majority | Rough share of commits and time |
| P6 | The hardest parts will be close, agent death and bounded shutdown; most review findings will land there | Review findings by area |
| P7 | No measurable change on the event path: the duty cycle checks one more queue at most | Empty-mailbox duty-cycle comparison |

## Actuals

To be completed at the end of the build.
