# Review — mongoose core's unreleased audit-capture changes, before 1.0.30

**Subject:** `origin/develop` at `2c4192e`, four commits ahead of `main` (1.0.29): `git log origin/main..origin/develop`.
Reviewed in an isolated worktree on `review/audit-capture-1-0-30`. Nothing was released, merged, pushed to `develop`
or `main`, or force-pushed. No keys or hosted providers were used.

**Not an independent review.** These commits were written in the same workstream that asked for this review (the
analyser branch's tracker records phase-1 implementation, core's MA-5 included, as its own). The checks below are
mutation controls and probes that the reviewer ran, not agreement with the commits' messages — but a reviewer with no
hand in the code should still look before release.

**Labels:** **RUN** — executed here; **READ** — code or history inspected, not executed.

## Verdict

**Releasable as 1.0.30 with release-note items.** No blocker. The three changes do what their commits say, and each
main assertion fails when its fix is reverted (five controls, all holding). Two Low findings concern re-registration
while recording (1, 2); neither loses a captured record in the server's own use. The release notes should state the
`attach` default overload's reach (4) and the new per-failure warning (5).

## The commits (READ)

| Commit | Author | What it changes |
|---|---|---|
| `4e3dba7` | employer-domain address | MA-5: capture fans out to the server's configured listener and restores it on stop; `attach` gains a three-argument form (`MongooseServer`, `ChronicleAuditCaptureService`, `MongooseAuditCaptureService`; test `AuditCaptureFanOutTest`) |
| `35f9a13` | employer-domain address | MA-5.4: a processor re-registered while recording is adopted (`ChronicleAuditCaptureService`; `AuditCaptureFanOutTest`) |
| `2c4192e` | employer-domain address | the audit listing froze while the file grew: the listing's cache is now invalidated on live-sink mutation, and live counters are overlaid on read (`ChronicleAuditCaptureService`, `DirAuditIntrospectionService`; test `AuditListingFreshnessTest`) |
| `17a03b4` | `github-actions[bot]` | a **merge** commit (parents `47635c4`, `130f18f`) with an empty message; its only change is `pom.xml` `1.0.29-SNAPSHOT` → `1.0.30-SNAPSHOT`. It is not on `main` |

## 1 · Fan-out and restore (MA-5)

**Delivery (RUN).** While recording, `onRecordFanOut` (`ChronicleAuditCaptureService.java:309–327`) writes to the queue
and then calls the configured listener, each isolated from the other. `recordsReachBothDestinationsAndTheListenerIsRestoredOnStop`
asserts the listener received the record; **control:** skipping the delegate
(`:317` → `delegate = null`) fails it at "MA-5.1: a record must reach the configured listener as well as the queue".

**Restore (RUN, READ).**
- On stop: `stopRecording` installs `previousListener` (`:288–289`). **Control:** installing the no-op again fails the
  same test at "MA-5.2: stopping capture restores the configured listener, not a no-op".
- Stop during capture: `stop` is `synchronized` on the service and `stopRecording` on the sink; `onRecord` tolerates a
  null appender, so a record in flight on the agent thread is dropped from the queue rather than faulting (READ).
- Capture started twice: `start` returns early on `isRecording()` (`:130`), so the sink is not rolled and the listener
  is not wrapped twice. `startStopStartDeliversExactlyOncePerDestination` covers start–stop–start (RUN, in the suite).
- Stop without a start: `stop` returns when the sink is absent or not recording (`:142`) — nothing is installed (READ).
- An exception during capture: the queue write and the delegate are each caught, counted and logged at WARNING;
  `aThrowingListenerDoesNotStopTheQueueReceivingTheRecord` covers a throwing listener (RUN, in the suite). An exception
  in `startRecording` itself (directory or queue creation) throws before the listener is swapped (READ).

**Lost, duplicated or leaked.** In the server's own use, none found: the delegate is called once per record, and
`stopAll` at tear-down restores every recording sink. See findings 1–2 for re-registration.

## 2 · The `attach` default overload

**Confirmed, and it is not a regression (READ).** `MongooseAuditCaptureService.java:79` defines
`attach(DataFlow, String, LogRecordListener)` as a default that delegates to the two-argument `attach`. The server now
calls the three-argument form (`MongooseServer.java:773`). A third-party implementation that does not override it
receives **exactly the call it received in 1.0.29** — the two-argument `attach`, with no listener — so its behaviour is
unchanged. What it does not get is the new capability: it cannot fan out to, or restore, the server's listener unless it
overrides the new method. In this repository only `NoOpAuditCaptureService` and `ChronicleAuditCaptureService` implement
the interface.

**Recommendation (not decided here):** document it in the release notes (see below). A fix would mean changing the
interface's contract for existing implementations, which is heavier than the gap it closes.

## 3 · Re-registration while recording (MA-5.4)

**Adoption (RUN, READ).** On re-attach, `adoptWhileRecording` installs the capture listener on the new `DataFlow`
(`:278–282`). `recordsFromAReAttachedProcessorReachTheCaptureQueue` reads the queue file back and requires the new
instance's record in it. **Control:** disabling the adoption fails it at "MA-5.4: NOTHING was installed on the new
DataFlow".

**No records lost or duplicated at the switch (READ).** The server builds the new processor inside the supplier passed to
`addNamedEventProcessor` (`MongooseServer.java:756–783`): it installs the configured listener, then `attach` adopts it,
and only then is the processor returned to the group. So the new instance dispatches nothing before adoption. The server
refuses to register a name that is still registered (`:752`), so an old and a new instance of one name do not dispatch
together.

**Threads (READ).** `attach` runs on the processor group's agent thread (the supplier is drained from `toStartList` in
`ComposingEventProcessorAgent.doWork`). `start` and `stop` run on the caller's thread — the admin surface. `attach` holds
the map's bin lock inside `ConcurrentHashMap.compute` and then the sink's monitor; `stop` holds the service's monitor and
then the sink's. No path takes them in the opposite order, and the listing callback run inside `compute` only resets an
`AtomicReference`. No deadlock found.

**Twice quickly, after stop, deregistered (RUN, `evidence-audit-capture-1-0-30/probe-output.txt`):**
- Twice quickly while recording: each new flow is adopted; see finding 2 for the earlier ones.
- After stop: re-attach installs nothing (PROBE-B), and the next `start` installs capture and fans out to the listener
  handed over at the re-attach. Correct.
- Deregistered: `stopProcessor` (`MongooseServer.java:852`) does not tell the capture service, so a deregistered
  processor's sink keeps reporting `recording: true` with its queue open until `stop` or tear-down. **This predates the
  delta** (READ) — out of scope, noted.

## 4 · The listing freeze (`2c4192e`)

**The fix addresses both diagnosed causes (READ).** The constructor now registers `invalidate` as the capture service's
live-sink mutation callback (`DirAuditIntrospectionService.java:47`), which is what the cache's comment already claimed;
and `withLiveCounters` overlays each recording sink's live handle on the cached walk, so `recordCount` and `lastWriteAt`
move without giving up the cached directory walk.

**The test fails without the fix (RUN, strict: byte copy → mutate → run → restore from the copy → `cmp` → green):**
- hook removed → `aSinkThatStartsAfterTheFirstListingAppears` fails, "a sink that began after the first listing must
  appear";
- overlay removed (`return cached;`) → `theListedRecordCountAdvancesWithTheFile` fails, "the listing FROZE here before
  the fix".

**Under a real writer (RUN, PROBE-L).** With the real Chronicle appender, a writer thread appended 2000 records while
the listing was read 260 times: the listed count and size never went backwards, and the final listed count was 2000.

## 5 · Tests

Every commit's main assertion fails when its fix is reverted — five controls, all holding, each a `<failure>` at the
named test's own assertion, restored `cmp`-identical and green again (`evidence-audit-capture-1-0-30/mutations-output.txt`,
runner `mg_mutations.py`). They assert behaviour, not only a pass: the MA-5.4 test reads the queue file back rather than a
counter.

## Findings

1. **Low — a re-attach while recording updates the configured listener but not the one fanned to and restored.**
   `ChronicleAuditCaptureService.java:111` takes the new listener on re-attach (its comment: "the server may have
   replaced it"), but `previousListener`, which `onRecordFanOut` and `stopRecording` use, is set only in `startRecording`
   (`:266`). **Sequence (PROBE-A):** attach with `L1`; start; re-attach with `L2` while recording; a record → `L1` gets it,
   `L2` does not; stop → `L1` is installed on the new flow; stop + start → it switches to `L2`. Within one server the
   listener is a single static, so the two are the same object and nothing is lost. They differ only when a second
   `bootServer` in the JVM has overwritten the static (`MongooseServer.java:116`). Then the re-attach reads the OTHER
   server's listener (the "reading it later" the attach comment warns of), and the stale `previousListener` is
   accidentally the right one. **Recommendation:** make the server's listener per-instance, then set `previousListener`
   from `configuredListener` in `adoptWhileRecording`. Not a release blocker.
2. **Low — earlier `DataFlow` instances keep the capture listener after a re-attach.** **Sequence (PROBE-C):** attach
   `a`; start; re-attach `b`, then `c`; stop → only `c` has the configured listener back; `a` and `b` still hold the
   capture listener. They are dead instances in the server's own use (a name cannot be registered twice), so no record
   is duplicated; it matters only if an old instance still dispatches.
3. **Out of scope, pre-existing — deregistration does not stop capture** (see 3).
4. **Release note — the default `attach` overload** (see 2).
5. **Release note — a failure is logged at WARNING, with its stack trace, on every record.** `:314` and `:323` log per
   failing record. A configured listener that throws on every record now produces one WARNING and stack trace per
   record, where 1.0.29 (which silenced the listener under capture) produced nothing. Consider rate-limiting; at least
   state it.

## Compatibility and draft release notes for 1.0.30

A downstream user could notice:
- While audit capture records a processor, the server's configured audit listener (the console/SLF4J sink by default)
  **keeps receiving** its records. In 1.0.29 it went silent when capture started.
- **Stopping capture restores** the configured listener. In 1.0.29 audit went nowhere after stop until restart.
- A processor **re-registered while recording keeps being captured**. In 1.0.29 its records silently stopped reaching
  the capture file while capture still reported `recording: true`.
- The audit file listing (`/api/audit/files`) reports a live `recordCount` and `lastWriteAt`, and shows sinks started
  after the first listing. In 1.0.29 they froze, or were missing.
- A failing capture write or configured listener is isolated, counted and **logged at WARNING per record**.
- **SPI:** `MongooseAuditCaptureService` gains a default `attach(DataFlow, String, LogRecordListener)`. An implementation
  that does not override it behaves exactly as in 1.0.29 and does not get fan-out or restore.

Draft lines:

> - Audit capture no longer silences the server's configured audit listener: records reach both the capture file and
>   the listener, and stopping capture restores the listener.
> - A processor re-registered while audit capture is recording is now captured; before, its records were silently not
>   written.
> - The audit file listing now shows live record counts and last-write times, and includes sinks started after it was
>   first read.
> - A failing capture write or configured listener no longer affects the other; each failure is logged at WARNING.
> - `MongooseAuditCaptureService.attach(DataFlow, String, LogRecordListener)` is new, with a default that calls the
>   two-argument `attach`. A custom capture service must override it to fan out to, and restore, the server's listener.

## Author identity (note only)

`4e3dba7`, `35f9a13` and `2c4192e` carry an employer-domain author address in this public repository; history is not
rewritten. Separately, in the local clone this review ran from, `git config user.email` resolves to that same employer
address (from the global config), so a commit made there without an explicit identity would add another. This review's
commit sets the personal address explicitly.

## Gates (RUN)

| Check | Result |
|---|---|
| `mvn -B -q package` on JDK 21 (Corretto 21.0.11; CI's matrix is Java 21) | exit 0; **218 tests / 0 failures / 0 errors / 9 skipped** over 66 surefire XML reports mapped to `src/test/java`, no orphans — matching the commit's own "218 tests, 0 failures, 9 skipped" |
| CI (`MavenCI`) on `2c4192e` | **success**, for the push to `develop` and for `feat/mongoose-audit-production` (READ, `gh run list`) |
| Mutation controls | 5 / 5 hold |
| Probes | re-registration (A, B, C) and the listing under a writer (L), outputs in `evidence-audit-capture-1-0-30/probe-output.txt` |

The probe test files were placed in `src/test` only while they ran and then removed; `git status -- src` is clean. Their
sources are kept in the evidence folder.

## Ran vs read

- **RUN:** the full suite on JDK 21; the five mutation controls; the four probes; the CI status query.
- **READ:** the diff of all four commits; `MongooseServer`'s registration and `stopProcessor`;
  `ComposingEventProcessorAgent`'s start queue; the interface and its two in-repo implementations.
- **Not verified:** behaviour against a live booted server or the admin REST surface (the probes drive the service
  directly with a stub `DataFlow`); any third-party capture-service implementation.
