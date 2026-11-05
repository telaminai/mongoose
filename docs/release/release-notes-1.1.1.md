# Mongoose 1.1.1

This patch removes recurring allocation while clearing stale failure context before queue dispatch. Successful dispatch no longer reads and removes an unset ThreadLocal, which allocated one entry per event. Failure identification, read-and-clear evidence consumers, retry policy, quarantine, overload and recording behaviour are unchanged.

The release retains Fluxtion runtime 1.1.3 and builder/BOM 1.0.80. It is compatible with mongoose-plugins 1.1.0; no new record-shape or configuration migration is introduced beyond [the 1.1.0 migration](client-migration-1.1.0.md).

## Evidence and release gates

An isolated allocation comparison on Java 21 HotSpot measured approximately 32 bytes per successful event in published 1.1.0 and no recurring allocation in the candidate. The permanent regression checks allocated bytes rather than elapsed time and uses the HotSpot thread-allocation management API. Removing stale-state clearing is separately tested to ensure an earlier successful peer cannot be retried under a stale failure identity.

Throughput and independently paced latency comparisons, full-suite results, stress/chaos evidence and independent review must pass before publication; their exact SHAs, commands and results are recorded in the release handoff. No numerical throughput or latency improvement is claimed by this preparation note alone. Performance results are unpinned macOS observations, not a client latency guarantee.

The owner explicitly waived a new soak for this narrowly scoped patch, conditional on performance improvement, all tests passing and clean independent review. The completed two-hour 1.1.0 soak remains evidence for its original commit; it is not represented as a 1.1.1 soak.

Publication uses the existing exact-tag process: merge the qualified preparation through a PR, tag the qualified commit as v1.1.1, run the tag/POM/SHA-validated publication workflow, and verify the published artifact from an empty Maven cache. No force-push or tag rewrite.
