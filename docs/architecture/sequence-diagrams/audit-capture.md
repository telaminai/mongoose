# Capture ownership and late completion

<div class="architecture-diagram">
--8<-- "docs/assets/architecture/capture.svg"
</div>

The processor's capture state is `Off`, `Opening`, `Recording` or `Closing`. Physical resource ownership is tracked
separately: an old open or close can remain outstanding after the processor has stopped or a new session has begun.

Open and close run on helpers. Install and uninstall run on the group thread between event cycles. A completion
is checked by operation and generation, not by display name alone. If the intended processor has gone, the returned
resource is closed instead of installed on a namesake replacement.

`AuditStop` during `Opening` cancels logical adoption, not arbitrary I/O already running. A new start can begin
while a previous resource is closing; the old resource retains its own identity and cleanup tracking. Failed
closes remain unresolved, hold their resource slots and prevent shutdown from claiming completion. They are not
blindly retried.

The built-in backend gives every session a fresh directory and metadata binding group, name, generation and
capture id. Plugins must preserve scoped identity too. See the [capture contract](../../reference/control-plane-contracts.md#audit-capture)
and the [real late-completion investigation](../../case-studies/control-audit-investigation.md).
