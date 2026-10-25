# RC release procedure

Targets: **Mongoose 1.0.33-rc1** (`v1.0.33-rc1`) and **mongoose-plugins 1.0.46-rc1** (`v1.0.46-rc1`).
Target pairing: **Fluxtion runtime 1.1.3** and **compiler/BOM 1.0.80**. Resolve the published artifacts,
regenerate the control processor and freeze new commits before qualification. Earlier runtime 1.1.2 results
are baseline evidence only.
Status: preparation; publication requires successful qualification and the approvals below.

## Existing mechanics and the RC adaptation

The existing `release.yml` runs Maven `release:prepare release:perform` on pushes to `release`, creates version
commits/tags, publishes, then merges release into main and develop automatically. That flow cannot preserve an
already qualified SHA and separate approvals for each merge. Do not push the RC to `release`.

The additional `prerelease.yml` publishes only on an explicitly pushed `v*-rc*` tag. It checks the tag against the
POM version, checks out that exact tag, runs Java 21 tests, and deploys using the existing repository and secret
names. It does not change versions, create tags or merge branches. Mongoose uses the existing `release` Maven
profile (Central, sources, javadoc and signing); plugins use their existing Repsy distribution repository.
Approve this explicit adaptation before pushing the release tags. Never print repository settings or secrets.

## Freeze and qualify

1. Commit all code, tests, version and documentation changes. Record full core and plugin SHAs outside those
   commits. Work only on clean checkouts. Any candidate change invalidates its earlier qualification.
2. On Java 21, run `mvn -o clean test`, then `mvn -o package` on the exact core SHA. Require BUILD SUCCESS,
   zero failures/errors and no added skips. Save each run's Surefire XML separately.
3. Run all opt-in stress tests, 400 fresh scenario seeds, 30-second chaos and replay workloads, 20 shutdown-storm
   cycles and the high-volume workload. Separately run the 600-second soak. Preserve audits and completeness
   manifests. Record commands, seeds, elapsed times, counts and failures without combining soak with regressions.
4. Run strict MkDocs. Build the release profile locally with signing skipped only for the packaging check; actual
   publication must sign. Install the exact core candidate locally for plugin qualification, recording its SHA
   and jar checksum so a preview cannot be mistaken for a published artifact.
5. On the exact plugin SHA run the entire Java 21 reactor with `mvn -o -fae clean verify`. Require every module
   to succeed and no new skips. Include both admin fixes (#40 and #41), the startup cache boundary migration and
   schema checks. Keep the dependency tree proving Mongoose RC and runtime 1.1.3.
6. Publish evidence on a separate evidence branch or as release assets. Do not append an evidence commit and
   then tag it as though it were the previously tested SHA. Preserve full logs and report failures/flakes.

## Merge and publish

Each GitHub PR merge needs its own product-owner approval. First merge Mongoose #69, then retarget #76 to main
and merge it according to repository convention. Plugin integration includes #40 and #41; approve its PR merge
separately. Local integration is for testing and does not imply approval to merge the PRs.

Keep the frozen candidate SHAs reachable from the merged history (use merge commits, not squash/rebase, for
these candidates), then tag the exact qualified SHAs. If merge conflict resolution or any other edit changes the
candidate, freeze and qualify the replacement before tagging. Do not force-push shared branches.

After explicit approval of the publishing adaptation, create annotated tags on the qualified SHAs and push the
Mongoose tag first. Wait for Central publication, then resolve `com.telamin:mongoose:1.0.33-rc1` using a fresh
local Maven repository. Only then push the plugin tag. Wait for Repsy publication and resolve every plugin
artifact using a fresh repository and the configured public repository. Check dependency versions and checksums.
If credentials or portal intervention are missing, stop and hand that step to the owner; do not substitute secrets.

Create GitHub **pre-releases**, not full releases, with versioned notes, exact SHAs, evidence and migration links.
Post the Mongoose tag, SHA, evidence and known limits on PR #76, including:
**released as a pre-release; not cleared for client migration**.

Any test failure, flaky result, unruled review finding or proposal to weaken assertions stops publication until
resolved. Release qualification does not replace the [client migration checklist](client-migration-1.0.33.md).

## Retrying an unchanged qualified tag

If publication fails because of publishing tooling, keep the RC tag fixed. The manually dispatched
`prerelease.yml` accepts an existing `release_tag` and its full `expected_sha`, checks both against
the checkout/POM, and publishes that checkout. It uses checksum-verified Maven 3.9.11 because the
tagged Central publisher 0.10.0 is incompatible with Maven 3.10 staging metadata. The initial
1.0.33-rc1 attempt was rejected with "Bundle has content that does NOT have a .pom file"; its
upload did not publish. See [the publisher compatibility notes](https://central.sonatype.org/publish/publish-portal-maven/).

Merge a workflow-only correction through a PR, then dispatch from main with the original qualified
tag and SHA. No source/POM changes or tag moves are permitted in this retry. The workflow repeats
Java 21 tests, package generation, signing and publication, and retains the generated bundle for inspection.
A successful workflow still requires independent clean-cache artifact resolution before release completion.

## Stable promotion

The owner authorized stable Mongoose 1.0.33 and plugins 1.0.46 after published RC acceptance.
Use the same exact-tag workflow for stable tags, with POM/tag/SHA validation and the compatible
Maven pin. Change release coordinates, current schema metadata and release notes only; compare
runtime Java to the qualified RC and require no changes. Run the full test/package checks with
the final versions and verify fresh-cache public resolution after publication. The RC stress,
mutation and soak evidence remains identified by its original SHA; do not relabel it as a run
on the stable metadata commit. If runtime code changes, requalify that change before publishing.

Publish core first, then plugins after the public core resolves. Create ordinary GitHub releases
for stable versions. Client migration remains independently gated by its checklist.
