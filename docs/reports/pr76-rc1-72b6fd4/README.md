# RC qualification: 72b6fd4 / f1f9003

Core candidate: **72b6fd44cf20973924745a6d5a7aafce8a30a75a**, Mongoose **1.0.33-rc1**.
Plugin candidate: **f1f90039909e3e6c1fd8b949bbf4f0712fffa4ab**, plugins **1.0.46-rc1**.
Both use Fluxtion runtime **1.1.3**, compiler/BOM **1.0.80**. This evidence branch adds no runtime changes
and is not the commit to tag. Neither candidate has been published or cleared for client migration.

Java 21 (Corretto 21.0.9), unpinned macOS arm64:

| Check | Result |
|---|---|
| Core `mvn -o clean test` | BUILD SUCCESS: 1,018 tests, 0 failures/errors, 14 unchanged skips |
| Core `mvn -o package` | BUILD SUCCESS: 1,018 tests, 0 failures/errors, 14 unchanged skips |
| Core release-profile packaging | BUILD SUCCESS; signing and tests skipped for this packaging-only check |
| Opt-in stress plus 400 fresh scenario seeds | BUILD SUCCESS: 419 tests, 0 failures/errors/skips |
| Separate 600-second soak | BUILD SUCCESS: 1 test, 0 failures/errors/skips; complete shutdown in 4 ms |
| Plugin full reactor `mvn -o -fae clean verify` | BUILD SUCCESS: 333 tests, 0 failures/errors/skips |
| Plugin dependency tree | BUILD SUCCESS; resolves Mongoose RC1 and runtime 1.1.3 |
| Strict MkDocs | Passed |
| Six source mutations | All compiled and were rejected by assertions; baseline 40/40 passed |

The soak's three exports contain 75,025, 73,577 and 74,098 records, with zero dropped audit records and zero
reported invariant violations. The archive also contains the 30-second stress exports and the real compiled
client graph's failed-then-next audit. Commands, seeds, log digests and counts are in [results.json](results.json).
Maven result excerpts are retained beside it. The full local logs/XML remain under
`/private/tmp/mongoose-rc1-final-72b6fd4`; this public pack does not include private-client dependency output.

The source-mutation failure counts are 5, 18, 19, 2, 1 and 7, with zero compilation/test errors. The name-only
stop mutant must fail the independent callback ledger. The committed runner on the candidate reproduces them:

```sh
JAVA_HOME=/path/to/jdk21 python3 docs/reports/pr76-third-review-followup-2026-10-25/run-source-mutations.py /path/to/mongoose 72b6fd44cf20973924745a6d5a7aafce8a30a75a
```

## Limits and unsuccessful checks

The same-machine throughput rerun did **not** yield a valid comparison. Its `74ad868` baseline, using runtime
1.1.2 and the identical padded-counter benchmark, logged slow/contended-queue drops. The benchmark waits for
all offered events and timed out after 90 seconds. The baseline log is retained. No candidate performance ratio
is claimed; do not reuse older numbers as measurements of this candidate. Pinned-core Linux performance remains
a migration gate. This failed benchmark attempt is separate from the passing correctness workloads.

The downloaded public starters compiled against the locally installed candidates, and the Spring starter
regenerated through the hosted compiler. Live checks exposed two starter compatibility changes: its export helper
must recognize qualified group/name selectors, and its sample file source must wait for initial capture readiness.
The latter follows the documented asynchronous auto-start contract: the original Spring sample processed all five
events before capture installation and captured none of them. Tests must not hide this by changing the input data.
The locally fixed analyser bundle captured all five original rows, exported them and stopped cleanly. Those web
changes are separate from these engine candidates; playground defaults remain on the old stable Mongoose/plugins.

Private client/venue compatibility is unverified here: the available old-server application checkout lacks cached
private dependencies, and the library source checkouts are unavailable. No private repository was modified or pushed.
No external callback-SPI implementer was found in the available Mongoose/plugin/application Java sources; that does
not establish the contents of unavailable client libraries. Clients still need dependency/API migration and graph
regeneration, plus application-specific failure/reconciliation checks.

Local Maven installation is not publication proof. After the approved merges and tags, resolve published core and
plugin artifacts from an empty Maven repository and rerun starter acceptance before changing playground defaults.
The owner must approve each merge and the tag-based pre-release publishing adaptation, per the agreed release
instructions. Known engine limits and the separate migration gate remain in the candidate's release notes.

This is a ten-minute synthetic soak and targeted regression/mutation evidence, not an overnight deployment soak,
an exhaustive concurrency proof, or client migration approval.
