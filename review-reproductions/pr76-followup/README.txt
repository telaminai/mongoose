PR #76 follow-up review reproductions

Reviewed runtime: fb75795cf6f85aabd3bd1b4b81d05a6a3cf59fbb.
Design: design/control-plane-as-events at 3b8290a.

These tests demonstrate OPEN findings; they are not fixes. They are outside Maven's default test tree so this evidence branch does not silently change the standard suite. Use a disposable checkout to run them:

  cp review-reproductions/pr76-followup/control/*.java src/test/java/com/telamin/mongoose/control/
  cp review-reproductions/pr76-followup/replay/*.java src/test/java/com/telamin/mongoose/replay/
  mvn -o test -Dtest=FollowupConcurrencyReviewTest,IndependentEvidenceFollowupTest,ReplayServiceFollowupReviewTest

Use Java 21. Omit -o if dependencies are not cached. At the reviewed head the combined run executes 9 tests: 8 assertion failures, 0 errors, 0 skipped. This is an intentional BUILD FAILURE from executed tests, not a compilation failure. The passing ninth test demonstrates 128 retained unresolved opens after timeouts. Its current assertions document growth rather than prescribe an arbitrary future capacity limit.

Independent reviewers reproduced the same failures against the proposed fix tip 34297f1. The previously published fixes do not resolve these additional findings. Service-version replay may either resolve the recorded version correctly or explicitly fail; silent substitution is invalid.

Separate verification:
- Unmodified PR head: 769 tests, 0 failures/errors, 9 skipped; BUILD SUCCESS.
- Existing proposed fix34297f1: five fresh-JVM runs of 65 selected tests each; all325 executions passed, no skips. Classes: LifecycleRacesTest, ControlTerminalAdmissionTest, RepliesAndServiceLockReviewTest, BoundedShutdownReviewTest, CaptureAsEffectsTest, CaptureShutdownRetryTest, FatalStopReviewTest, IndependentServiceReviewTest.
- Those repetitions took about40 seconds total. They are repeated concurrency/failure regressions, not a sustained production load soak or a performance benchmark.

IndependentEvidenceFollowupTest has two mutation-oracle checks. They establish that the validator accepts forbidden traces, not that the runtime itself generated those traces. Thread growth under blocked physical opens follows from the cached executor configuration; the reproducer uses a manual I/O adapter and measures retained operations/roots, not actual blocked thread count.
