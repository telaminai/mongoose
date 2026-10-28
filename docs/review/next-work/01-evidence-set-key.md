# Spec 01: evidence-set key — the analyser opens a log with the graph and source that produced it

Status: proposed for design review, 2026-10-28. Owner decisions recorded (section 8).
Repos: fluxtion (runtime), mongoose, fluxtionauditlog-analyser. No builder change.

## 1. Why this first

The analyser is the front end users see. Its main flow is: open a log, see the graph, walk what happened. That flow
is only trustworthy if the graph shown is the graph that ran. Today the analyser cannot know that; it guesses. A
correct main flow makes every later view, including edge cases and failure evidence, easier to understand and to
trust. This is small, and it removes a whole class of "the analyser is wrong" support conversations that are really
"the analyser was shown the wrong graph".

## 2. Today (verified at the commits below)

| Artifact | Build key | Where |
| --- | --- | --- |
| graphml | `fluxtion.sourceFingerprint` graph attribute | builder `GraphMlFacts.java:144-145`, `JgraphGraphMLExporter.java:607` (fluxtion-compiler 2fa0fc7e) |
| generated source | `ProcessorDescriptor.sourceFingerprint()`, `toolchainVersion` | generator `JavaSourceGenerator.java:2147-2152`; runtime `describe/ProcessorDescriptor.java:56-59` (fluxtion 3871bc4) |
| audit log | **none** | runtime `audit/LogRecord.java`: eventTime, logTime, groupingId, event, nodeLogs, endTime |
| Mongoose capture sidecar | **none**; has group, name, generation | `ChronicleAuditCaptureService.java` sidecar write (mongoose 776bedc) |

Analyser (eea52328): `topology/GraphmlDiscovery.java` already reads the graphml fingerprint (`:73`, `:321`) but pairs
a graph with the open log by comparing logged node names with declared ones (`GraphPairing`: applies, matched,
loggedButNotDeclared). Two builds of the same graph with the same node names pair as a match even when their logic
differs; a log can pair with a stale graph from the same folder.

The fingerprint is `ModelFingerprint.of(graph, dto)` (`EventProcessorGenerator.java:166`), stable across local,
remote and interpreted generation by design.

## 3. Design

### 3.1 Runtime: one header record per audit stream

At the start of every audit stream the runtime writes one document before the first `eventLogRecord`:

```yaml
auditStreamHeader:
  schema: 1
  processor: <generated class name>
  sourceFingerprint: <descriptor value, or null>
  toolchainVersion: <descriptor value>
  runtimeVersion: <runtime version>
  startedAt: <ISO-8601>
  host: { }                 # optional fields from the host (3.2)
```

- Written for every processor (owner decision D1). Once per stream, never per event: no hot-path cost.
- A stream starts at `init` and whenever a `LogRecordListener` is attached or replaced.
- Text and binary encodings both carry it.
- `sourceFingerprint: null` is written explicitly for processors generated before fingerprints existed.
- API for hosts: `EventLogManager.setHeaderContext(Map<String,String>)`, applied to the next header.

### 3.2 Mongoose: name the run

- On capture attach, Mongoose sets the host context to `{group, name, generation, mongooseVersion}`.
- The capture sidecar gains `sourceFingerprint` and `toolchainVersion` from the processor's descriptor. Keys are
  additive; sidecar `schema` stays 1.
- A new generation already starts a new capture stream, so it gets its own header.

### 3.3 Analyser: pair by key, warn on mismatch

- **Pair by fingerprint first.** When a log has a header, `GraphmlDiscovery` pairs it with the graphml (and source)
  whose `fluxtion.sourceFingerprint` equals the header's. Name matching remains only as the fallback for logs without
  a header or with a null fingerprint.
- **Show it.** The open log shows one line: processor, fingerprint (short), and "graph matches" / "graph does not
  match" / "graph not verified (log has no build key)".
- **Warn, do not refuse** (owner decision D2). On mismatch, a persistent warning in the UI; every MCP result over that
  pair carries `evidenceSet: MATCH | MISMATCH | UNVERIFIED` with both fingerprints, so the onboard assistant says so in
  its answer.
- **Segments.** A file with more than one header is shown as segments, labelled `group/name#generation` when
  present, never merged. Each segment pairs independently.
- **Bundles.** An evidence bundle records the key of every file it references.

## 4. Compatibility

- Old analysers: `YamlAuditReader` detects a file by `eventLogRecord:` within the head (`:35`, `:58`); whether its
  parser skips an unknown top-level document must be tested. Ship the analyser's tolerance first, then the runtime.
- Runtime `AuditLogTool`, Mongoose capture conversion in svc-admin-web: same tolerance test.
- Logs without a header keep today's behaviour exactly, labelled "graph not verified".

## 5. Out of scope

Reading Mongoose 1.1 control-plane evidence (completeness, fallback records, FailureKind, overload): see
`03-analyser-reads-mongoose-1.1-evidence.md`, tracked as a separate item.

## 6. Acceptance

1. A generated processor's audit stream begins with a header whose fingerprint equals its graphml's.
2. A folder with two graphml builds of the same graph: the analyser pairs the log with the matching one, not by name.
3. Forced pairing with the wrong graph: UI warning; MCP results carry `evidenceSet: MISMATCH`.
4. A log with no header opens exactly as today, labelled "graph not verified".
5. A Mongoose capture across a restart shows two segments, labelled by generation.
6. Old analyser opening a new log: no crash, same view as today (tolerance test).

## 7. Work and size

| Repo | Work | Days |
| --- | --- | --- |
| fluxtionauditlog-analyser | reader tolerance test and fix (ship first) | 0.5 |
| fluxtion runtime | header record, text and binary, host context API, tests | 2 |
| mongoose | host context on attach, sidecar keys, test | 1 |
| fluxtionauditlog-analyser | pair by fingerprint, status line, MCP `evidenceSet`, segments, bundle key, tests | 3 |

About 6.5 days.

## 8. Owner decisions (2026-10-28)

- D1 **Decided:** the runtime writes the header for every processor, once per stream.
- D2 **Decided:** warn on mismatch; never refuse.
- D3 **Decided:** the analyser's tracker order stays as is; this runs in parallel.
