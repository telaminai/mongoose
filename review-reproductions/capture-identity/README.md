# Capture identity reproduction for PR #76

Tested against author commit `5bb22cbfb3f3dbec25577b59eb6486fef364f32c` with Java 21.
Both tests fail deterministically: **2 tests, 2 failures, 0 errors, 0 skipped**.
These review probes intentionally live outside the default test source tree. This
commit changes no production code and makes no decision about the eventual
capture identity policy.

## Reproduce

Use a disposable checkout of this reproduction branch, with Java 21 selected by
`JAVA_HOME`. From the repository root:

```sh
cp review-reproductions/capture-identity/AuditCaptureNamesakeReviewTest.java src/test/java/com/telamin/mongoose/internal/
mvn -Dtest=AuditCaptureNamesakeReviewTest test
```

Maven is expected to exit nonzero on the tested author commit. The original run
used `mvn -o -q -Dtest=AuditCaptureNamesakeReviewTest test` with cached dependencies.
Read `target/surefire-reports/com.telamin.mongoose.internal.AuditCaptureNamesakeReviewTest.txt`
for the assertion results. The copied test remains untracked in the disposable
checkout; it is not part of the ordinary test suite on this branch.

## Expected versus actual

1. `captureOfEarlierNamesakeUsesItsOwnConfiguredListener`: attach two DataFlows
   under the same bare name `p`, with distinct configured listeners L1 and L2,
   while capture is off. Install capture on the first flow, emit one record, then
   uninstall. Expected: L1 receives one record, L2 receives none, and L1 is restored
   on the first flow. Actual: L1 receives **zero**, L2 receives **one**, and **L2 is
   restored on the first flow**.
2. `twoGroupsCanCaptureSameNamedProcessorsIndependently`: attach two same-name
   flows using the same listener, then install both captures. Expected: independent
   captures for the separately admitted processors. Actual: installing the second
   capture throws `IllegalStateException: audit capture of 'p' is already recording`.

## Scope and code path

These are resource-service probes with proxy DataFlows and real Chronicle queues,
not full server integration tests. A non-null capture router selects routed
semantics; the tests invoke the resource API directly, as group effects do.
The distinct-listener case exercises the supported `attach` API. Ordinary
single-server startup normally supplies the same listener to both processors;
the second test does not require distinct listeners.

On the tested commit, `MongooseServer.captureRouter()` selects `group/name` but
passes the bare name into the group command (`MongooseServer.java:1454`).
`ComposingEventProcessorAgent.java:841,875` passes that bare name into capture
resources. `ChronicleAuditCaptureService.java:135,303` indexes both attachment and
allocation by bare name. `reattach` replaces the configured listener (`:638`),
whereas `adopt` changes only the DataFlow (`:648`). Recording saves that configured
listener (`:583`) and uninstall restores it (`:615`). A second capture shares the
sink and is refused (`:312`); the queue directory also uses the bare name (`:551`).

The backend therefore cannot preserve group identity despite qualified command
routing. A fix needs an explicit policy carried through resource identity,
listener ownership, storage paths and introspection. These probes document the
remaining gap without silently changing that policy in the current fix batch.
