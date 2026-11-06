# How-to: Wait until a feed has caught up

A launcher, a test or a batch job often needs to know when a run has *processed everything*: every feed has
delivered what is available to it, and the processors have handled it. Sleeping for a quiet period ("no new
records for N seconds") is slow, and it can cut a slow feed short. From Mongoose 1.1.3 you can ask the server
instead.

You'll learn how to:

- read a feed's state from Java with `MongooseServer.feedStatus(...)`;
- read the same report with the `feed.status` admin command, from the CLI or any admin transport;
- script a "processed everything" check: the settled rule;
- receive the feed's status inside the graph as a `FeedStatus` event.

The design and its guarantees are in [Feed caught-up signal (design)](../../design/feed-caught-up.md).

## Sample code

- Complete how-to example: [Wait for feed caught up]({{example_root}}/how-to/wait-for-feed-caught-up). It reads a
  20,000-line file into a slow processor, waits for `settled`, prints `feed.status --json`, then appends more lines
  and waits again. It needs Mongoose 1.1.3.
- Tests in the Mongoose repository: `FeedStatusServerTest` (a booted server, the admin command, the in-graph event)
  and `FileEventSourceFeedStatusTest` (every read strategy).

## The states

Each feed's source reports one of four states, `com.telamin.mongoose.service.feedstatus.FeedState`:

| State | Meaning | What a host does |
| --- | --- | --- |
| `UNKNOWN` | The source cannot tell, or does not report. Every source written before 1.1.3 (today, every plugin connector) reports this. | Fall back to a quiet period, for this feed only. |
| `BEHIND` | More is available than the source has published: it is starting, reading, or has seen more arrive. | Wait. |
| `CAUGHT_UP` | Everything available now has been published. More may arrive; the source then reports `BEHIND` before it publishes it. | Settled once drained. |
| `END_OF_STREAM` | A bounded source has published everything it ever will. Final. | Settled once drained. |

A state only says what the *source* has published. A feed is **drained** when every subscriber queue has dispatched
up to the position at which that state was reported, so the processors have finished handling those items, not just
received them. A feed is **settled** when it is `CAUGHT_UP` or `END_OF_STREAM`, drained, and not stalled.

Which sources report:

| Source | Reports |
| --- | --- |
| `FileEventSource`, tailing (`COMMITED`, `EARLIEST`, `LATEST`) | `BEHIND` while reading, `CAUGHT_UP` at the end of the file, `BEHIND` again when it grows. Unbounded. |
| `FileEventSource`, once (`ONCE_EARLIEST`, `ONCE_LATEST`) | `END_OF_STREAM` once the file has been read. Bounded. |
| `InMemoryEventSource`, `HandlerPipe` | `CAUGHT_UP` when every item offered has been published. |
| Admin command queues | Always `CAUGHT_UP`. |
| Any other source, including plugin connectors | `UNKNOWN`. |

A missing file is `CAUGHT_UP` with the detail `file not found: <path>`: there is nothing available to deliver. If
your launcher needs the file to exist, check that yourself, or read `detail`.

## From Java: `feedStatus`

`MongooseServer.feedStatus(String... feedNames)` returns a `FeedStatusReport` for the named feeds, or for every feed
when you name none. An unknown name throws `IllegalArgumentException`. Name your input feeds, so that an internal pipe
a processor is still writing to does not hold the check back.

This loop is from the example's `WaitForCaughtUpExample`:

```java
FeedStatusReport report = server.feedStatus("orders");
FeedStatusReport.Feed feed = report.feed("orders");
if (report.settled() && feed.sequence() >= minSequence) {
    if (!report.lossless()) {
        throw new IllegalStateException("settled, but lossy: " + report);
    }
    if (!report.fallbackFeeds().isEmpty()) {
        // these feeds cannot report: apply a quiet period to them only
        System.out.println("feeds without a status: " + report.fallbackFeeds());
    }
    return;
}
```

`feedStatus` takes each feed's publish lock briefly. Call it to poll, every few milliseconds, not on a hot path.

## From the admin interface: `feed.status`

```text
feed.status [--json] [feed ...]
```

`feed.status` is one of the server commands that `MongooseServerAdmin` registers, so you need the admin registry and
the server admin service:

```java
AdminCommandProcessor admin = new AdminCommandProcessor();
MongooseServerConfig.Builder builder = MongooseServerConfig.builder()
        // ... feeds and processors ...
        .addService(ServiceConfig.<AdminCommandRegistry>builder()
                .service(admin).serviceClass(AdminCommandRegistry.class).name("adminService").build())
        .addService(ServiceConfig.builder().service(new MongooseServerAdmin()).name("serverAdmin").build());
```

Add a `CliAdminCommandProcessor` service to type commands on the console (see
[Add an admin command](how-to-write-an-admin-command.md)). The command runs on the caller's thread, not through a
processor queue, and it is reachable through every admin transport. An unknown feed is an error:
`no such feed: <name>; usage: feed.status [--json] [feed ...]`.

Without `--json` it prints indented text. This is the example's output after the file has grown to 25,000 lines:

```text
feeds: settled=true lossless=true
	orders: CAUGHT_UP unbounded seq=25000 drained=true policy=PAUSE dropped=0 refused=0 acknowledged=0
		orders-agent/orders/onEventCallBack: 25004/25004
```

With `--json`, for a launcher:

```json
{"settled":true,"lossless":true,"fallbackFeeds":[],"feeds":[{"feed":"orders","state":"CAUGHT_UP",
 "bounded":false,"sequence":20000,"version":2,"detail":null,"drained":true,"settled":true,"lossless":true,
 "policy":"PAUSE","dropped":0,"refused":0,"downgraded":0,"paused":false,"pauses":1085,"acknowledged":0,
 "pendingAcks":0,"consumers":1,"stalled":null,"unterminated":0,"completionFailures":0,
 "subscribers":[{"queue":"orders-agent/orders/onEventCallBack","position":20002,"dispatched":20002,"drained":true}]}]}
```

(Line breaks added; the command prints one line.)

### The fields

| Field | Meaning |
| --- | --- |
| `settled` (top level) | Every *reporting* feed in the report is settled. Feeds in `UNKNOWN` do not hold it back. |
| `lossless` (top level) | No feed in the report has dropped or refused an item or given up on a delivery completion. |
| `fallbackFeeds` | Feeds in `UNKNOWN`, sorted. |
| `state`, `bounded`, `detail` | The state the source last reported, whether the source is finite, and its reason for humans (`starting`, `file not found: ...`, `partial line pending`, ...). |
| `sequence` | How many items the feed had published **when it reported the state**. It is the position of the claim, not a live counter: while a feed reads, it can stay at the last report's value. |
| `version` | Changes whenever the feed reports. A different value between two reads means the feed moved. |
| `drained`, `settled` (per feed) | As above. |
| `lossless`, `dropped`, `refused`, `downgraded`, `completionFailures` | Losses. `lossless` is `dropped == 0 && refused == 0 && completionFailures == 0`. `completionFailures` counts `DeliveryContext.onComplete` callbacks (typically a state save) given up on after the processor group's retry policy: the item was committed without them. A callback that succeeds on a retry is not counted. `downgraded` counts publishes that met a full never-drop queue and could neither wait nor pause; any loss they caused is in `dropped`. |
| `policy`, `paused`, `pauses` | The feed's overload policy, whether the source is paused now, and how many times it has paused. See [Configuring overload and loss](../../guide/overload-configuration.md). |
| `acknowledged`, `pendingAcks` | For a feed that commits positions (a `COMMITED` file feed): the highest sequence committed, and how many published items are not yet. 0 for other feeds. |
| `consumers` | Subscriber queues that can consume now. |
| `stalled` | Why a feed that commits positions cannot complete: `no live consumer`, `consumer <queue> fenced or dead`, or `a removed consumer left unprocessed items ...`. Null otherwise. A stalled feed is not settled. |
| `unterminated` | Bytes after the last newline that a once-read file ended without publishing. |
| `subscribers` | Per queue (`group/feed/callback`): `position` (items it had been given at the report), `dispatched` (items its reader has finished), `drained`. |

With `feedStatusEvents` on (below), the status events travel through the queues too, so a queue's `position` and
`dispatched` count them as well as the lines: 20,002 for 20,000 lines in the output above.

## Script a "processed everything" check: the settled rule

1. Poll `feed.status --json <your input feeds>` (or `feedStatus(...)`) every few milliseconds.
2. When `settled` is true, apply your old quiet-period heuristic to each feed in `fallbackFeeds`, and to those feeds
   only. With no fallback feeds, skip this step.
3. Wait a short backstop: a few hundred milliseconds of unchanged output. Drained covers only work dispatched from the
   feed queues. A service on its own thread, a timer, a vendor callback, or a processor that hands work to an
   executor can still produce output after its feed is drained.
4. If `lossless` is false, report the run as lossy and exit non-zero: the per-feed `dropped` and `refused` say where.
   `settled` says the run is complete; `lossless` says whether it is whole.
5. A feed that stays unsettled with `stalled` set will not complete in this run. Time out and report the reason.

Two things to know about `settled`:

- **It is as of the source's last look.** Lines appended to a tailed file after the source last polled are not seen
  until its next poll, which reports `BEHIND`. If you have just appended, wait for `sequence` to cover what you
  appended, as the example does.
- **A tailing feed can go `BEHIND` again.** A launcher that runs to completion stops the server once settled.

## In the graph: the `FeedStatus` event

A processor that wants to react to the end of its input (flush a batch at the end of a file, emit a summary) can
receive the status as an ordinary event, in order with the feed's items. It is off by default; turn it on per feed:

```java
EventFeedConfig.builder()
        .instance(source)
        .name("orders")
        .feedStatusEvents(true)                  // also deliver FeedStatus events into the graph
        .agent("orders-feed-agent", new SleepingMillisIdleStrategy(1))
        .build();
```

In YAML, `feedStatusEvents: true` on the feed. Then handle `FeedStatus` like any other event. From the example's
`OrderCounter`:

```java
@Override
protected boolean handleEvent(Object event) {
    if (event instanceof FeedStatus status) {
        if (status.state() == FeedState.CAUGHT_UP || status.state() == FeedState.END_OF_STREAM) {
            System.out.printf("  in-graph: %s %s@%d, %d lines handled so far%n",
                    status.feedName(), status.state(), status.sequence(), lines.get());
        }
        return true;
    }
    // ... the feed's items
    return true;
}
```

`FeedStatus(feedName, state, sequence, bounded, detail)` is a record, and its `filterString()` is the feed name.

What to expect:

- Each status is delivered after the items it covers: `CAUGHT_UP@20000` arrives after line 20,000.
- It is not wrapped in a `NamedFeedEvent`, does not go through the feed's data mapper, takes no sequence number, and
  is not journalled or counted as a record.
- It is delivered to `onEvent` routes only. A typed callback route skips it.
- On a `PAUSE` queue (the default for a file feed) a status is never dropped: it waits for room. On other queues it is
  dropped like any item when the queue is full.
- It is audited, recorded and replayed like any other input, so a recording made with it on has more entries than one
  made with it off.
- With it off, nothing new enters any queue, so existing audit logs and recordings are unchanged.
- A probed feed (`InMemoryEventSource`, `HandlerPipe`) reports no transitions, so it delivers no `FeedStatus` events.
  You can still query it.

## Run the example

The example needs Mongoose 1.1.3. Until 1.1.3 is published, install a 1.1.3-SNAPSHOT build of Mongoose locally,
then:

```bash
cd how-to/wait-for-feed-caught-up
mvn compile exec:exec
# keep the server running with an admin prompt, and type: feed.status --json orders
mvn compile exec:exec -Dexample.args=cli
```

## See also

- [Process a file feed at least once](how-to-file-feed-at-least-once.md): the `acknowledged`, `pendingAcks` and
  `stalled` fields in use.
- [Configuring overload and loss](../../guide/overload-configuration.md): `policy`, `paused` and `dropped`.
- [Feed caught-up signal (design)](../../design/feed-caught-up.md).
