# Runtime sequences

These diagrams distinguish the thread that decides a transition from the thread that performs I/O or receives a
reply. Each depicts the named scenario, not every error branch.

| Sequence | Question it answers |
| --- | --- |
| [Subscribe to a feed](event-subscription.md) | How does a processor join a reader, and who owns unsubscription? |
| [Deliver an event](event-processing.md) | Where does the thread handoff happen, and when is ownership released? |
| [Apply a control request](control-request.md) | How are decisions, effects and replies connected? |
| [Open and cancel a capture](audit-capture.md) | What happens when I/O finishes after its processor has gone? |
| [Shut down the server](shutdown.md) | What does the deadline bound, and what can remain running? |

For a recorded example, follow the [late capture completion case study](../../case-studies/control-audit-investigation.md).
