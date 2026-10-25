# Capture resource identity

Server-managed Chronicle capture is scoped to an immutable `AuditCaptureIdentity`
(group, processor name, registration generation). The server attaches each
registration with the listener configured on that instance. Group effects open
against the same identity. An opened resource pins that registration's sink;
install rejects another instance, and cleanup uses the pinned resource rather
than resolving a name again. Retired registration bindings are removed from the
in-memory index. Outstanding resources retain only their own sink until closed.
A bounded set of the last 512 retired selector strings preserves useful stopped-
processor diagnostics without retaining processors, listeners or every generation.

Each routed physical open creates a fresh directory under the configured capture
root with `Files.createTempDirectory`. Names and generations never form paths,
and an open queue is never moved. This separates groups, replacements, repeated
sessions, late closes and simultaneous server instances sharing the same root.
`.mongoose-capture.properties` records schema `1`, the encoded display selector,
and (when known) the group, processor name and generation. Capture file ids are
the unique directory names. The sidecar is identity metadata, not an audit
completeness assertion or cryptographic attestation.

Commands and `isRecording`/`currentSink` accept a bare name only if it identifies
one attached registration. Qualified selectors percent-encode each UTF-8
component, joined by `/`: group `g/a`, name `p%` becomes `g%2Fa/p%25`. Empty group
names are represented by a leading slash. A selector containing `/` selects a
group and name, rather than also matching a literal bare name. The server's
built-in router binds scoped auto-start to the exact admitted generation.
`currentSinks` keys are qualified selectors; per-processor capture metric names
also use that selector. Introspection joins live and historical captures by
capture id, so successive recordings of the same registration remain distinct.
Counters and timestamps restart for each new routed physical capture.

Existing capture plugins keep their original methods. New `attachScoped`,
`openCaptureScoped` and `startScoped` default methods delegate to their old
bare-name APIs; these defaults preserve source compatibility and do not claim
that a third-party name-keyed backend acquires registration isolation. Existing
custom Chronicle routers may decline generation-scoped requests through the
new default `sendScoped` method, rather than accidentally target a replacement.

Legacy routed callers using `attach(flow, name, listener)` retain one binding per
flow. Since `openCapture(name)` cannot know which flow owns an ambiguous name,
it creates a unique, unbound resource; install binds it once to the exact attached
flow. Identical repeat attachments are idempotent; conflicting attachment of the
same flow is refused. Direct group embeddings using the old attach API follow
the same deferred-binding path when the group requests a scoped open. Metadata
for an unscoped open necessarily has only its bare name. These callers can use
`attachScoped` to supply full ownership metadata.

Standalone, unrouted attachment and name-directory discovery remain compatible,
including the previous reattachment behavior. A standalone capture cannot claim
a directory marked as a routed capture. New scoped standalone bindings also
support start, stop and retirement. Historical discovery reads both layouts.
Per-event record writing is unchanged; identity, filesystem and index work occurs
at capture lifecycle boundaries and introspection queries.
