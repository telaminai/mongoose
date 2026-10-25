# Mongoose built with Fluxtion

Mongoose uses Fluxtion for its own processor-group control plane. The same model used for application event
processing handles add, stop, service-change and audit-capture decisions inside the runtime.

## Why use our own technology here?

Lifecycle requests are events with state-dependent outcomes. A capture completion can arrive after a stop; a stop
can target an older generation; a service broadcast can be stale. These transitions benefit from explicit rules
and a record of what each rule decided.

The control graph is authored in Spring XML. Java rule nodes express the behaviour, dependency edges define their
relationship, and Fluxtion generates the dispatcher ahead of time. The committed generated processor runs inside
each group; the group does not need to compile its control plane during startup.

| Benefit | How Mongoose uses it | What still needs separate proof |
| --- | --- | --- |
| Explicit decision graph | `LifecycleRules`, `CaptureRules` and `ServiceRules` consume typed inputs and effect outcomes | The rules implement the intended policy, including exceptional interleavings |
| Decision/effect separation | Rules request effects; `EffectQueue` performs them at `batchEnd()` after the decision record publishes | Callback success, resource ownership and external side effects |
| Inspectable evidence | Input, decision, effect outcome and reply records share causal identities | Export completeness and independent resource ledgers |
| Shared investigation tools | Generated GraphML, source and audit can be opened together in the analyser | Graph/build provenance and the limits of what was instrumented |
| Repeatable checks | Scripted and generated scenarios drive the real control graph | Concurrency stress, failure injection and realistic deployment testing |

## What the graph does not solve

Single-threaded decisions sit inside a concurrent runtime. Java code still handles mailbox admission, name
reservation, request claiming, service publication, thread-death detection and physical resource cleanup.
The generated processor cannot make an arbitrary plugin callback non-blocking or make a failed close succeed.

Control audit is bounded diagnostic evidence, not a durable transaction log. Publishing a decision record before
an effect does not mean the record was fsynced before the effect. The audit is separate from deterministic replay,
which has its own streams, provenance and supported-operation restrictions.

This separation also limits fast-path cost: business events go to their application processor, rather than through
the lifecycle rule graph. Each group still checks control work each duty cycle, and control effects share its thread.

## From evidence to a change

1. State the expected outcome independently of the implementation.
2. Reproduce the scenario and preserve the audit, build identity and resource observations.
3. Locate the request and follow its decisions and final outcomes in the analyser.
4. Check generation, operation and resource identity; compare reported effects with the independent ledger.
5. Add a regression that fails for the wrong behaviour, then change the code and repeat the investigation.

An LLM can navigate and summarise this evidence using analyser verbs. It should not redefine the expected behaviour
merely to make the implementation pass. A missing node log is not proof that the node did not execute, and an
incomplete export must not become a successful invariant check.

The [case study](../case-studies/control-audit-investigation.md) follows this workflow through a real qualification
trace. It demonstrates a late-completion safety path; it is not evidence that every failure mode has been eliminated.

## Source and regeneration

- Authored graph: `src/main/fluxtion/control/control-processor.xml`
- Rules: `src/main/java/com/telamin/mongoose/control/node/`
- Generated dispatcher: `src/main/java/com/telamin/mongoose/control/generated/GroupControlProcessor.java`
- Topology: `src/main/resources/com/telamin/mongoose/control/generated/GroupControlProcessor.graphml`

Run `mvn -o -Pregen process-classes` with Java 21, a warm dependency cache and the builder prerequisites configured
to regenerate. Ordinary builds use the committed generated source. The shape tests check the execution semantics
that the effect boundary relies on; regeneration still needs review and regression validation.
