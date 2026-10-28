# Spec 01: one key joins audit log, graphml and generated code; analyser reads Mongoose 1.1 evidence

Status: proposed for design review, 2026-10-28. Baseline: Mongoose 1.1.1 (main 776bedc), Fluxtion runtime 1.1.3,
builder 1.0.83, analyser 1.40.0. Repos: fluxtion (runtime), fluxtion-compiler (builder), mongoose,
fluxtionauditlog-analyser.

## 1. Problem

The analyser loads three kinds of file for one processor: the audit log, the graphml and the generated source.
Nothing in the audit log says which build produced it, so the analyser cannot tell whether the three files belong
together. A redeploy, a restart into a new generation, or a support bundle assembled by hand can pair a log with
the wrong graph. The analyser then explains behaviour against a graph that did not run.

Separately, the analyser reads none of the evidence Mongoose 1.1 added: the control-audit export and its
completeness claim, driver fallback records, capture identity (group, name, generation), `FailureKind`, and
overload/loss records. The client's support cycle depends on exactly that evidence.

## 2. What exists today (verified)

| Artifact | Carries a build key? | Where |
| --- | --- | --- |
| graphml | yes: graph attribute `fluxtion.sourceFingerprint` | builder `GraphMlFacts.java:144-145`, `JgraphGraphMLExporter.java:607`; e.g. Mongoose `GroupControlProcessor.graphml:26` |
| generated source | yes: `ProcessorDescriptor.sourceFingerprint()` and `toolchainVersion` | generator `JavaSourceGenerator.java:2147-2152`; runtime `describe/ProcessorDescriptor.java:56-59`, `DescriptorSupport.java:80-103` |
| audit log record | **no** | runtime `audit/LogRecord.java` record shape: eventTime, logTime, groupingId, event, nodeLogs, endTime |
| Mongoose capture sidecar `.mongoose-capture.properties` | **no** fingerprint; has schema, processor selector, openedAt, group, name, generation | `ChronicleAuditCaptureService.java:~878-887` |
| Mongoose control-audit export manifest | no processor fingerprint | `ControlAuditExport` |

The fingerprint is computed by `ModelFingerprint.of(graph, dto)` (`EventProcessorGenerator.java:166`) and is stable
across generation routes (local, remote, interpreted).

## 3. Design

### 3.1 The evidence-set key

`EvidenceKey = (sourceFingerprint, toolchainVersion)`, plus, under Mongoose, `(group, name, generation)`.
`sourceFingerprint` joins log to graph and source. Generation separates two runs of the same build.

### 3.2 Fluxtion runtime: a stream header record

At the start of every audit stream (first record after `init`, and again whenever a `LogRecordListener` is
attached or replaced), the runtime emits one header document before any `eventLogRecord`:

```yaml
auditStreamHeader:
  schema: 1
  processor: <generated class simple name>
  sourceFingerprint: <descriptor.sourceFingerprint() or null>
  toolchainVersion: <descriptor.toolchainVersion()>
  runtimeVersion: <BuildVersion.runtime()>
  startedAt: <ISO-8601>
  hostContext: { }          # filled by the host, see 3.3
```

- Text and binary encodings both carry it (`LogRecord`, `BinaryLogWriter`).
- Null fingerprint is written as null, never omitted: absence must be distinguishable from "not supported".
- A processor generated before the fingerprint existed writes `sourceFingerprint: null` and the analyser treats the
  set as unverified, not as mismatched.
- Cost: one record per stream start. No hot-path change.

API: `EventLogManager.setHeaderContext(Map<String,String>)` lets a host add fields before the header is written.

### 3.3 Mongoose: fill the host context and the sidecar

- When a capture attaches (`attachScoped`), Mongoose sets `hostContext` to `{group, name, generation, server,
  mongooseVersion}` before the first record.
- The capture sidecar gains `sourceFingerprint` and `toolchainVersion`, read from the processor's descriptor.
- The control-audit export manifest gains, per processor registration recorded in the export, `(group, name,
  generation, sourceFingerprint)`, so a control-plane event can be joined to the capture of the same generation.
- A new generation always starts a new capture stream (already true) and therefore a new header.

### 3.4 Analyser: load a consistent set

- **Index by key.** On import, read the header (or sidecar) of every log, and the `fluxtion.sourceFingerprint` of
  every graphml and descriptor. Group files by fingerprint.
- **Load rule.** Opening a log selects the graphml and source with the same fingerprint. If none matches, open the
  log with a visible "graph unknown" banner and refuse graph-dependent verbs (graph, walk, coverage) unless the
  user picks a graph explicitly; record that choice in the evidence bundle.
- **Mismatch.** A log whose header fingerprint differs from the loaded graph is a hard warning in the UI and an
  error result for MCP verbs (`EVIDENCE_SET_MISMATCH`), never a silent load.
- **Generations.** A file containing more than one header splits into segments; each segment is labelled
  `group/name#generation` and never merged with another.
- **Evidence bundles** store the key of every file they reference.

### 3.5 Analyser: read Mongoose 1.1 evidence

| Evidence | Analyser behaviour |
| --- | --- |
| Control-audit export manifest `complete`, `verify` | Show completeness on open; an incomplete export is labelled incomplete in every view and report |
| Driver fallback records (`replyAuthor: DRIVER`, fallback documents) | Shown distinctly from rule-authored records; filterable |
| `FailureKind` on replies, effect outcomes, StopResult, replay stops | A first-class field: filter, aggregate, series by kind |
| `EvidenceDegraded` / `UNVERIFIED` | Spans after degradation are shaded unverifiable |
| `OverloadState` and loss records (`OVERLOAD_LOSS`, `Dropped`) | Timeline lane per queue; correlate with orders and lifecycle |
| `LEFTOVER_RECOVERED`, `QUARANTINED`, held names | Listed in a failures panel |
| Capture identity | Segment label and filter, see 3.4 |

MCP: no new verb is needed. `open` reports the evidence-set status; `filter`/`aggregate` accept `failureKind`,
`group`, `generation`; `report` includes completeness and the evidence key. Optionally, once the Mongoose oracle
ships as a module (maintainability M3), `verify` returns violations and vacuous invariants.

### 3.6 Fix the evidence-bundle bugs that break support handoffs

#178 (flag text dropped), #179 (saved walk goes historical on reopen of the identical file), #109 (working copy not
read-only). #179 should be keyed on the evidence key, which fixes it by construction.

## 4. Compatibility

- Old readers ignore an unknown top-level document only if they skip non-`eventLogRecord` documents; check the
  analyser's `YamlAuditReader` and `BinaryAuditReader`, and `AuditLogTool`, before shipping. Ship the reader change
  first.
- Mongoose's capture sidecar gains keys; `schema` stays 1 because keys are additive. Bump to 2 only if a key changes
  meaning.

## 5. Acceptance

1. A generated processor's audit stream starts with a header whose fingerprint equals its graphml's.
2. Restart into a new generation: the capture shows two segments, labelled by generation, never merged.
3. Analyser opens a log with the wrong graphml in the same folder: refuses the pair and picks the matching one.
4. A truncated control-audit export is shown incomplete everywhere it is used, including LLM answers.
5. Injected overload on an order feed: the loss appears on the timeline beside the order records.
6. All of the above exercised by the synthetic trading system (spec 03, to come) and in the analyser's tests against
   real Mongoose 1.1.1 output, replacing the 1.0.32 fixtures.

## 6. Work split and sizes

| Repo | Work | Days |
| --- | --- | --- |
| fluxtion runtime | header record, text and binary, host context API, tests | 2 |
| mongoose | host context, sidecar keys, export manifest join, tests | 2 |
| analyser | reader tolerance (first), index and load rule, segments, 1.1 evidence table, bundle bugs, fixtures from 1.1.1 | 8-10 |
| analyser | bump pins to runtime 1.1.3 / builder 1.0.83 and Mongoose 1.1.1 | 1 |

## 7. Decisions for the owner

- D1: header emitted by the runtime for every processor (recommended), or only when a host asks for it.
- D2: mismatch refuses graph verbs (recommended) or only warns.
- D3: whether the analyser's Mongoose 1.1 work moves ahead of the current onboarding and account items on its
  tracker.
