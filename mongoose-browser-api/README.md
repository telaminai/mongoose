# mongoose-browser-api

A **signature-only, compile-only stub** of the small slice of the Mongoose API
that the Fluxtion playground's `mongoose-hosted` example wires against.

It exists for one job: let that example's `MongooseMain.java` **type-check in
the browser**, inside CheerpJ's Java 8 `javac`. Nothing in this artifact runs —
every method body throws `UnsupportedOperationException`.

## Why it exists

The real `com.telamin:mongoose` artifact is compiled to class file v65
(Java 21). CheerpJ runs Java 8 (v52) and its `javac` cannot read v65 class
files for symbol resolution. So the playground cannot compile code that
imports the real Mongoose JAR.

But symbol resolution only needs **method signatures**, not behaviour. The
language features that make Mongoose Java-21-only (`var`, switch expressions,
pattern `instanceof`) live in method *bodies* and never appear in public
signatures. So a hand-written Java 8 stub of just the API surface — bodies
replaced with `throw` — compiles cleanly to v52 and gives the browser exactly
the symbols it needs.

Result: `MongooseMain.java` compiles **green** in the playground instead of
being excluded from the compile pass. The reader sees the Mongoose wiring code
type-check — proof it is real code, not a sketch. It is never *run* in the
browser (booting agent threads under CheerpJ is not viable); the example's
processor test stays the runnable green tick.

See the full design: `fluxtion-web/docs/implementation/fluxtion-web/mongoose-hosted-example-spec.md`
(Option D).

## Scope

Deliberately tight — only what `MongooseMain.java` references today:

| Stub | Surface |
|---|---|
| `MongooseServer` | `bootServer(MongooseServerConfig)`, `stop()` |
| `MongooseServerConfig` (+ `Builder`) | `builder`; builder `addEventFeed/addProcessorGroup/addEventSink/build` |
| `EventFeedConfig<IN>` (+ `Builder`) | `builder`, `instance/name/broadcast/valueMapper/agent/build` |
| `EventSinkConfig<S>` (+ `Builder`) | `builder`, `instance/name/valueMapper/agent/build` |
| `EventProcessorConfig<T>` (+ `Builder`) | `builder`, `name/handler/build` |
| `EventProcessorGroupConfig` (+ `Builder`) | `builder`, `agentName/idleStrategy/add/build` |
| `ReadStrategy` | enum — faithful copy of the 5 constants |
| `FileEventSource` | no-arg ctor, `setFilename`, `setReadStrategy` (core `connector.file`) |
| `FileMessageSink` | no-arg ctor, `setFilename`, `accept` (implements `MessageSink`) |
| `com.telamin.mongoose.browser.Stub` | shared `UnsupportedOperationException` — **not** part of real Mongoose |

Connectors are stubbed at the **core** `com.telamin.mongoose.connector.file`
package, matching this example's pom (core `mongoose` dependency only). Richer
examples that pull the connector *plugin* artifacts are a separate, later
concern — this stub stays deliberately simple.

`DataFlow` and `MessageSink` are *not* stubbed — they are real
`fluxtion-runtime` types (already v52) and come in as `provided` dependencies.
The stub grows only as the example grows; the drift test (below) is what keeps
it honest.

## Build

The mongoose repo is single-module, so this builds standalone — not via a
reactor:

```bash
mvn -f mongoose-browser-api/pom.xml clean install
```

Output: `mongoose-browser-api-<version>.jar`, every class at class file v52.
Compiled with `--release 8`; depends on `fluxtion-runtime` and `agrona 1.21.2`
at `provided` scope (the playground supplies those separately in the browser).

## How the playground uses it

The JAR is published to `fluxtion-playground-libs` and added to the in-browser
`javac` classpath for the `fluxtion-mongoose-hosted` example. It is inert for
any example that does not import `com.telamin.mongoose.*`.

## Contract

- **Compile-only.** Every method throws. If a stub method is ever invoked, the
  exception message explains the artifact is a stub and points at the real
  `com.telamin:mongoose` build.
- **Mirror, do not invent.** Public signatures must match the real Mongoose API
  exactly, or the browser will compile code that fails the local Maven build.

## TODO — drift guard (next step)

A test must compile the example's `MongooseMain.java` against **both** this stub
and the real `com.telamin:mongoose` JAR. If the real API moves and the stub is
not updated, that test fails — so the browser can never silently compile code
that will not build locally. The actual example `MongooseMain.java` already
type-checks against the stub with `javac --release 8`; wiring that into an
automated test (e.g. via `StringCompilation`) is outstanding.
