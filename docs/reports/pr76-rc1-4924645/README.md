# RC qualification: 4924645 / f1f9003

Exact core candidate: **49246452e5eb9879529880f264150c094547ad24**, Mongoose **1.0.33-rc1**.
Exact plugin candidate: **f1f90039909e3e6c1fd8b949bbf4f0712fffa4ab**, plugins **1.0.46-rc1**.
Fluxtion runtime **1.1.3**, compiler/BOM **1.0.80**. This evidence commit is not the commit to tag.
The owner approved the Mongoose and plugin release merges. Publication and clean-cache verification follow;
qualification does not clear client migration.

Java 21 (Corretto 21.0.9), unpinned macOS arm64:

| Check | Result |
|---|---|
| Core `mvn -o clean test` | BUILD SUCCESS: 1,019 tests, zero failures/errors, 14 unchanged skips |
| Core `mvn -o package` | BUILD SUCCESS: 1,019 tests, zero failures/errors, 14 unchanged skips |
| Release-profile packaging | BUILD SUCCESS; signing/tests skipped for this packaging-only check |
| Stress, 400 fresh scenario seeds, chaos, replay, shutdown storm, high volume | BUILD SUCCESS: 419 tests, zero failures/errors/skips |
| Separate 600-second soak | BUILD SUCCESS: 1 test, zero failures/errors/skips |
| Plugin full reactor `mvn -o -fae clean verify` | BUILD SUCCESS: 333 tests, zero failures/errors/skips |
| Plugin dependency tree | BUILD SUCCESS; Mongoose RC and runtime 1.1.3 |
| Strict MkDocs | Passed with network access; initial sandbox attempt could not resolve the Mermaid CDN |
| Core GitHub Java 21 CI | Both runs passed: 40234074187 and 40234074200 |

Commands, seeds, exact commits, elapsed times, log hashes and Surefire counts are in [results.json](results.json).
[qualification-evidence.tar.gz](qualification-evidence.tar.gz) contains the full regression/stress/soak/plugin logs,
Surefire XML, audit exports and manifests. SHA-256:
`8d196998829e97738c458bea485dc3df3b72e3b96f31f43adfd84243cf14ba6a`.

The soak's three exports retained 63,557, 76,462 and 76,105 records; each manifest reports complete=true,
zero dropped records and zero sink failures. Regression tests and the synthetic ten-minute soak are separate
checks; neither is a client deployment soak or proof of all possible interleavings.

## Failures addressed and remaining limits

The previous candidate 72b6fd4 passed locally but failed GitHub CI. This replacement fixes constructor-time
admin registration in processor context, cleans leaked test context and removes a scheduler assumption from
the full-mailbox regression. The new constructor regression failed before the fix and passed afterward.
Earlier evidence is retained, explicitly marked superseded, under `../pr76-rc1-72b6fd4`.

Plugin CI before publication cannot resolve `com.telamin:mongoose:1.0.33-rc1`. The local reactor uses the
exact installed core candidate; it is not proof that published artifacts resolve. Re-run CI and use an empty
Maven repository after core publication, before publishing plugins.

The previous same-machine throughput comparison timed out in its overloaded baseline and produced no valid
comparison. No new throughput ratio is claimed. Pinned-core Linux performance remains a client migration gate.
The six source mutations were verified on parent 72b6fd4, not rerun on this constructor/test-only follow-up;
those historical results are not presented as fresh results on 4924645.

Starter compatibility fixes are separate in fluxtion-web PR #20: qualified capture identity mapping and bounded
initial capture readiness for the sample file feed. Focused tests passed (132); both live starter checks captured
all five original rows against the previous locally installed core preview. Repeat against published artifacts
before updating playground defaults. Whole-web type checking is not green: four errors remain in unchanged
SplitPane bindings outside the starter changes.

Private client/venue dependencies remain unverified: required private artifacts and some library source checkouts
were unavailable. No private repository was modified. See the candidate's release notes and separate migration
checklist for replay, overload, capture ownership, failure containment and application reconciliation limits.

**Not cleared for client migration.**
