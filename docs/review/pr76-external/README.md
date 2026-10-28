# PR #76 follow-up specs (from the independent review of 4f5851e, 2026-10-25)

Four documents split by timeline and owner. Each item carries acceptance criteria expressed as tests or
documents so it can be reviewed, assigned and verified independently.

| File | Timeline | Purpose |
| --- | --- | --- |
| `76-release-gates.md` | Before #76 merges and the client release | Engine fixes, oracle fixes, capture migration, shutdown runbook, qualification rerun |
| `77-overload-policy-spec.md` | Post-release, issue #77 | Design skeleton for explicit data-path loss policy (blocking/backpressure publish) |
| `fluxtion-issue-generated-processor-wedge.md` | Post-release, Fluxtion repo | Ready-to-paste issue: generated processor wedges on a handler throw |
| `maintainability-plan.md` | Post-release, tracking issue | Eight refactors ordered and sized |
| `77-reviewer-response.md` | Reviewer input requested by issue #77; owner decisions marked OWNER | Matrix additions, the four defaults, terminal-state table, failure taxonomy, proposed split of work |

Next work (after 1.1.1), in `docs/review/next-work/`: `00-work-items.md` (all items, priority and where each is tracked), `01-evidence-set-key-and-analyser-1.1.md` (common key across audit log, graphml and generated code; analyser reads Mongoose 1.1 evidence) and `02-order-path-visibility.md` (publish result and outbound overload state for order-producing processors).

Source material: `author-prompt-combined-requirements.md` (the combined requirements file) and the review
report at the session scratchpad `pr76-review-report.md`. Reproduction tests referenced by name live under
`src/test/java/com/telamin/mongoose/extreview/` on this branch.

Decisions that belong to the product owner, not the implementer, are marked **DECISION** in each file.
