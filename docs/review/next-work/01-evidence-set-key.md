# Spec 01: evidence-set key — the analyser opens a log with the graph and source that produced it

Status: revision 2, 2026-10-28, after independent review (findings referenced as R-x.y). Owner decisions in section 9.
Repos: fluxtion (runtime), fluxtion-compiler (generator, one small change), mongoose, fluxtionauditlog-analyser.

## 1. Why this first

The analyser is the front end users see. Its main flow is: open a log, see the graph, walk what happened. That flow
is only trustworthy if the graph shown is the graph that ran, and if one run is not mixed with another. Today the
analyser guesses both. A correct main flow makes every later view, including failures, easier to understand.

## 2. Today (verified)

| Artifact | Build key | Where |
| --- | --- | --- |
| graphml | `fluxtion.sourceFingerprint` | compiler `GraphMlFacts.java:144-145`, `JgraphGraphMLExporter.java:607` |
| generated source | `ProcessorDescriptor.sourceFingerprint()` | `JavaSourceGenerator.java:2149-2152`; runtime `ProcessorDescriptor.java:56-59` |
| interpreted processor | **null**: descriptor built without `Meta` (R-1.2) | compiler `InMemoryEventProcessor.java:140-148`; runtime `DescriptorSupport.java:36-51` |
| remote generation | client-computed, copied by the server, never rechecked (R-1.2) | `RemoteHttpCombinedGenerator.java:169-171`; generator-http `Main.java:618-619` |
| audit log | none | runtime `LogRecord.java`; binary file header `BinaryLogWriter.java:86-90` |
| Mongoose capture sidecar | none; group, name, generation; written for routed captures only (R-2.6) | `ChronicleAuditCaptureService.java:877-888` |

Analyser: `GraphmlDiscovery` reads the graphml fingerprint (`:73`, `:321`) but pairs a log with a graph by comparing
logged node names with declared ones.

### 2.1 What a fingerprint does and does not prove (R-1.5)

The canonical form covers node names, classes, callbacks and edges. It does not cover configured values or node
method bodies. Two builds with the same shape and different code or parameters share a fingerprint (example:
Mongoose test fixtures `AlarmProcessor` and `CycleAlarmProcessor`). So a match means **"same graph shape"**, never
"same build". The analyser must word it that way.

### 2.2 Known instability (R-1.3)

A filter id held in a field whose type is neither Number nor String is hashed through `toString()`
(`TopologicallySortedDependencyGraphDtoBuilder.java:648`, `:681-683`). A type without its own `toString()` changes
the fingerprint on every build. Prerequisite compiler fix: canonical value, or refuse at build with a named error.
Until fixed, such a processor shows "shape not verified".

### 2.3 Generation is not a run (R-2.5)

Mongoose's generation is an in-memory counter per agent (`ComposingEventProcessorAgent.java:128,541`). A static
deployment restarts as `group/name#1` every time. Generation separates replacements inside one process, not restarts.

## 3. The key

```
graph key  = sourceFingerprint                        (joins log to graphml and source; may be null)
run key    = serverId + runId + group/name#generation (separates runs; Mongoose fills it)
```

- `toolchainVersion` and `runtimeVersion` are information only, not key parts: the graphml and descriptor derive the
  version differently and may disagree (R-1.4).
- `runId` is a per-process UUID created at server start, with `startedAt`. `serverId` is the configured server name,
  else host name.

## 4. Design

### 4.1 Generator: hand the descriptor to the audit manager

Generated code calls `EventLogManager.init()` from the processor constructor, before any sink exists
(`EventLogManager.java:83-96`), and the manager holds no descriptor (R-2.3). The generator adds one call in the
constructor: `eventLogManager.setDescriptor(DESCRIPTOR)`. The interpreter does the same with its (null-fingerprint)
descriptor. No runtime behaviour changes until a header is written.

### 4.2 Runtime, text format: a lazy header document

- Written **on the first record published after any sink change**: the constructor's default sink, `setLogSink`, and
  a format swap through `EventLogControlEvent` (`EventLogManager.java:248-262`, `:295`). Never at `init`.
- Once per stream, never per event; written for every processor (D1).
- Shape:

```yaml
auditStreamHeader:
  schema: 1
  processor: <generated class name>
  sourceFingerprint: <value or null>
  toolchainVersion: <info>
  runtimeVersion: <info>
  startedAt: <ISO-8601>
  host: { serverId, runId, group, name, generation, mongooseVersion }   # from the host, may be empty
```

- Host fields come from `EventLogManager.setHeaderContext(Map)`, applied to the next header.

### 4.3 Runtime, binary format: key in the existing file header (R-2.2)

The runtime `BinaryLogReader` throws on any unknown frame type (`BinaryLogReader.java:291`), and the analyser and
`AuditLogTool` delegate to it. So no new frame. Instead:

- Bump `FORMAT_VERSION` and add the header fields to the existing file header (`BinaryLogWriter.java:86-90`).
- A sink change in binary mode starts a new file, so one file is one stream.
- Readers of the new version read the fields; old readers refuse the new version by name, as today for any version
  bump. Ship the reader first.

### 4.4 Mongoose: name the run

- At server start: create `runId`; resolve `serverId`.
- On capture attach: set header context `{serverId, runId, group, name, generation, mongooseVersion}`.
- Sidecar (routed captures) gains `sourceFingerprint`, `serverId`, `runId`. Keys are additive; `schema` stays 1.
- Rotation (R-2.4): capture rolls daily (`ChronicleAuditCaptureService.java:184`) and core never prunes. Each roll
  file must be attributable on its own: the capture writes the header again at the start of each roll cycle, and
  the analyser carries one run across a rolled set when the run key is equal.

### 4.5 Analyser: pair by key, warn, segment

- **Tolerance first (R-2.1, verified by running 1.40.0):** today a header document is counted as a record and raises
  `NO_RECORD_KEY`, and a header-only file is refused as unrecognised (`YamlAuditReader.canOpen` needs
  `eventLogRecord:` in the first 4 KB). Change: recognise `auditStreamHeader` as a header, not a record; a header-only
  file opens as an empty log of that processor.
- **Pair** by `sourceFingerprint` first; name matching only when the fingerprint is null or absent.
- **Status line** on the open log, one of:
  - "graph shape matches" (fingerprints equal)
  - "graph shape does not match" (fingerprints differ) — persistent warning, never a refusal (D2)
  - "graph not verified" (no header, or null fingerprint, or known-unstable fingerprint)
- **MCP:** results over a pair carry `evidenceSet: MATCH | MISMATCH | UNVERIFIED` with both fingerprints, worded as
  graph shape, so the assistant says so.
- **Segments:** a new segment starts when the **run key changes**, not on every header (a re-sent header in the same
  run does not split it). Segments are labelled `serverId · runId(short) · group/name#generation`.
- **Bundles** record the graph key and run key of every file.

## 5. Compatibility and ship order

1. Analyser: text tolerance (header recognised, header-only file opens) and binary reader for the new version.
2. svc-admin-web: its Chronicle export pages by document count (`WebAdminService.java:1185-1195`); exclude the
   header from `from`/`limit` counting and render it as metadata.
3. Fluxtion runtime and generator: text header, binary file header, `setDescriptor`, `setHeaderContext`.
4. Mongoose: run identity, header context, sidecar.
5. Analyser: pairing, status line, MCP field, segments, bundles.

Logs without a header behave exactly as today, labelled "graph not verified".

## 6. Out of scope

- Reading Mongoose 1.1 control-plane evidence: tracker item, `03-analyser-reads-mongoose-1.1-evidence.md`.
- Proving "same build" (code bodies, parameters): would need a second digest over the generated source; not in this
  spec.

## 7. Acceptance

1. A generated processor's text stream begins with a header whose fingerprint equals its graphml's; an interpreted
   processor writes `sourceFingerprint: null`.
2. A binary stream's file header carries the same fields; an old reader refuses it by version name.
3. A folder with two graphml builds of different shape: the log pairs with the matching one.
4. Forced pairing with a different shape: UI warning; MCP results carry `evidenceSet: MISMATCH`.
5. A log with no header opens exactly as today, labelled "graph not verified".
6. A static Mongoose deployment restarted twice: three segments, distinguished by `runId`, each `#1`.
7. A capture across a daily roll: one run, one segment, across both files.
8. A header-only file (idle processor) opens as an empty log of that processor.
9. A processor whose filter id has an unstable `toString()`: shown "graph not verified" until the compiler fix.

## 8. Work and size

| Repo | Work | Days |
| --- | --- | --- |
| fluxtionauditlog-analyser | text tolerance, header-only open, binary reader for new version | 1.5 |
| mongoose-plugins (svc-admin-web) | header excluded from paging, shown as metadata | 0.5 |
| fluxtion runtime | lazy text header, binary file header and version bump, `setDescriptor`, `setHeaderContext`, tests | 3 |
| fluxtion-compiler | generated `setDescriptor` call, interpreter equivalent; filter-id canonicalisation (R-1.3) | 1.5 |
| mongoose | run identity, header context, sidecar keys, header per roll cycle, tests | 1.5 |
| fluxtionauditlog-analyser | pairing, status line, MCP field, segments by run key, bundles, tests | 3.5 |

About 11.5 days, up from 6.5: the review found the binary format, the init timing, restarts and rotation.

## 9. Owner decisions

- D1 **Decided:** the header is written for every processor, once per stream.
- D2 **Decided:** warn on mismatch; never refuse.
- D3 **Decided:** the analyser tracker order stays as is; this runs in parallel.
- D4 **Proposed:** a match is worded "graph shape matches", never "same build" (R-1.5).
- D5 **Proposed:** add `runId` and `serverId` to the key, so restarts of a static deployment are distinguishable (R-2.5).
