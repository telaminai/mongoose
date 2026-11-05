# Subscribing to a feed

<div class="architecture-diagram">
--8<-- "docs/assets/architecture/subscription.svg"
</div>

The processor is wired to its group's event feed. A subscription callback asks the group for the feed/callback
reader. `EventFlowManager.getMappingAgent` creates that reader, creates or reuses the feed/group queue and adds it
to the publisher. The group registers the processor with the reader and asks the source to subscribe.

Another processor in the same group may join the existing reader. On removal, the group removes that processor's
registration first. Only an empty reader is retired. The source is unsubscribed when the reader count for that
subscription reaches zero **across groups**.

Subscription callbacks and reader mutations must respect group ownership. During normal runtime these occur on
the group thread; calling a running processor directly from another thread bypasses this protection.
