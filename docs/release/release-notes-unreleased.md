# Unreleased changes

The dispatch allocation patch is assigned to [Mongoose 1.1.1](release-notes-1.1.1.md).
Qualification and publication status must be read from its release evidence; this pointer does not claim it has shipped.

## Audit stream header: which run and which graph wrote a log

Requires Fluxtion runtime 1.1.4 (the stream header and FLXA format version 2).

- **Every audit stream now begins with an `auditStreamHeader` document**, written by the runtime before the first
  record of each stream. Mongoose fills its `host` map with `serverId`, `runId`, `group`, `name`, `generation` and
  `mongooseVersion`. The run key is `serverId + runId + group/name#generation`; the graph key is the processor's
  `sourceFingerprint`, read from its own compiled descriptor, so processors compiled by earlier builders carry it
  too. A fingerprint match means the same graph **shape**, never the same build.
- **`runId`** is a random id created when a `MongooseServer` is constructed. Generation restarts at 1 with the
  process, so a static deployment restarted twice wrote three indistinguishable runs before; the run id separates
  them. `MongooseServer.serverId()` and `runId()` expose both.
- **`serverId`** is new, optional configuration (`serverId: pricing-1` in YAML, `MongooseServerConfig.builder()
  .serverId(...)`); absent, the host name is used.
- **Audit capture**: the header is the first document of every capture, is repeated at the start of each daily roll
  cycle so each cycle file can be attributed on its own, and is not counted in the capture's record count. The
  sidecar (`.mongoose-capture.properties`, routed captures) gains `sourceFingerprint`, `serverId` and `runId`;
  keys are additive and `schema` stays `1`.
- **Behaviour change for listeners**: a text `LogRecordListener` receives the header as an ordinary record
  (`instanceof StreamHeaderRecord` identifies it). A listener that counts records should skip it or override
  `processStreamHeader`. A binary listener does not receive it unless it overrides `processStreamHeader`.
