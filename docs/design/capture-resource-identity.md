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
Each handle's record count and timestamps restart for a new routed physical capture. The named Agrona metric
`audit.<selector>.records` reuses its counter and is cumulative for that label within the counters service.

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

## Migration for capture importers and support tooling

For server-managed captures, stop constructing `<root>/<processor-name>` paths. Each start creates a new
`<root>/capture-<unique-suffix>/` directory containing Chronicle files and `.mongoose-capture.properties`.
Keep the directory name as the capture id, including when copying evidence to another machine. Use the
introspection API's returned `path`, not the encoded selector as a filesystem path.

The sidecar is a Java properties file (schema `1`):

| Key | Meaning |
| --- | --- |
| `schema` | `1`; reject unsupported versions in external importers |
| `processor` | Display selector, for example `g/p`; legacy unscoped opens may contain only a bare name |
| `group`, `name` | Original, unencoded identity components, present for scoped opens |
| `generation` | Decimal registration generation within the owning group; not a global deployment id |
| `openedAt` | Optional ISO-8601 UTC `Instant` for this session; older schema-1 files may omit it |

Use `MongooseAuditIntrospectionService.capturesFor(group, name)` for current and historical session handles, or
`latestCapture(group, name)` for the preferred latest handle (possibly `null`). Selector overloads also exist.
Returned lists are immutable, active sessions first, then session-open time newest first, with id as a tie-breaker.
Old metadata without `openedAt` falls back to last-write time. A group-qualified lookup never guesses which group
an old bare-name directory belonged to; `listAvailable()` still exposes those directories for explicit migration.

Discovery continues to read historical name-based directories. The routed server does not append new sessions
to them or rename them. Unrouted legacy embedding retains its old name-directory behaviour; this compatibility
does not make those old paths authoritative for new server captures. Back up old captures before changing import
scripts. Preserve the sidecar alongside all Chronicle files when archiving a new capture.

`currentSinks()` and `liveSinks()` now use `g/p` rather than `p`, and the built-in record counter is
`audit.g/p.records`. Components containing `/` or `%` are percent-encoded individually. Handle counts, start times
and last-write times restart for each session; the Agrona named metric is cumulative. Dashboards must distinguish
session identity from processor identity.

There is no core retention janitor. `retainHours` is a configuration hint for external tooling, not an enforced
expiry. Repeated starts accumulate directories. Update pruning jobs to discover metadata rather than glob a
processor-name directory, exclude live and unresolved resources, preserve required incident evidence and delete
whole closed sessions only under the deployment's retention policy. The client's existing pruning scripts have
not been supplied or validated; checking them is a deployment acceptance item.
