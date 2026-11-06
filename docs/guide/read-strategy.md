# ReadStrategy guide

This guide explains ReadStrategy and how it is used with event sources in Mongoose Server. It also provides a runnable
example using FileEventSource with multiple input files, each configured with a different ReadStrategy.

## What is ReadStrategy?

The ReadStrategy enum configures how an event source should position itself in a stream when starting and whether it
should continue tailing new data or stop after an initial pass.

Available strategies (com.telamin.mongoose.config.ReadStrategy):

- COMMITED
- EARLIEST
- LATEST
- ONCE_EARLIEST
- ONCE_LATEST

## FileEventSource behavior

FileEventSource reads lines from a text file and publishes each line as a NamedFeedEvent. It supports different starting
positions and persistence of a read offset via a “commit pointer” file.

Key concepts:

- Tail vs Once:
    - Tail means the source will continue reading as new data arrives (follow the end of file).
    - Once means the source will make a single pass and then stop (typically used for batch imports at startup).
- Commit pointer file:
    - When COMMITED is used, FileEventSource persists a byte offset into a side-car file named
      `<filename>.readPointer`. From 1.1.3 the offset is that of the last line **every subscriber has processed**, not
      the last line read, so the feed is at-least-once (below).
    - On restart, the source resumes from the committed offset if the file exists.

### Strategy details

- COMMITED (the default)
    - Start position: If `<filename>.readPointer` exists, resume from the stored offset; otherwise start at offset 0.
    - Mode: Tail (keeps reading as file grows).
    - Persistence: Writes updated offsets to `<filename>.readPointer`, after the lines are processed.
    - Each line carries its source position to the processor (`DeliveryContext`), for de-duplication.

- EARLIEST
    - Start position: Beginning of file (offset 0).
    - Mode: Tail (keeps reading as file grows).
    - Persistence: No read pointer file required.

- LATEST
    - Start position: End of file (start tailing only new lines appended after startup).
    - Mode: Tail.
    - Persistence: No read pointer file required.

- ONCE_EARLIEST
    - Start position: Beginning of file (offset 0).
    - Mode: Once (reads the file content at startup and stops).

- ONCE_LATEST
    - Start position: End of file.
    - Mode: Once (does not read existing content, completes immediately unless new content appears during startup
      window).

Every strategy reports its progress to `feed.status` and `MongooseServer.feedStatus`: the tailing strategies report
`CAUGHT_UP` at the end of the file, the `ONCE_*` strategies `END_OF_STREAM`. See
[Wait until a feed has caught up](../example/how-to/how-to-wait-for-feed-caught-up.md).

## COMMITED semantics (1.1.3 and later)

| | Before 1.1.3 | 1.1.3 and later |
| --- | --- | --- |
| When the pointer moves | When a line was **read** | When **every subscriber** has processed the line (the slowest, with several groups) |
| After a crash | Lines read but not processed were skipped: at-most-once | They are re-delivered, with the few lines processed since the last commit: **at-least-once**, duplicates but never a gap |
| After a clean stop | As after a crash | Everything the processor groups finished is committed, including what they finished after the feed stopped |
| What the pointer counts | Decoded characters (wrong after non-ASCII text) | The exact byte offset |
| A full subscriber queue | The line was dropped after 10 ms, and committed past | The feed pauses (`PAUSE`, the new default for file feeds) and loses nothing |
| The input file replaced or truncated | Not detected: a truncated file was read from past its end (nothing delivered), a replaced one was not read while tailing, and was read from the old offset after a restart | Detected at start and while tailing: the pointer restarts at 0 under a new epoch, with a warning (a new inode with the same first 4 KB is the same file, and keeps it) |
| A consumer removed with unprocessed lines | Not tracked | The pointer holds before them for the rest of the run, and `feed.status` reports the feed `stalled` |

The pointer file also holds an epoch, the input file's identity and a fingerprint of its first 4 KB. Appending to the
file never resets it. A pointer written by 1.1.2 or earlier keeps its position and gets an epoch on first use.

How to use all this, and what exactly is re-delivered:
[Process a file feed at least once](../example/how-to/how-to-file-feed-at-least-once.md). For a stateful processor
that must not apply a line twice:
[De-duplicate re-delivered events with DeliveryContext](../example/how-to/how-to-deduplicate-with-delivery-context.md).

## Example FileEventSource with different ReadStrategy

The example class is provided under:
[FileReadStrategyExample]({{source_root}}/test/java/com/telamin/mongoose/example/readstrategy/FileReadStrategyExample.java).

It demonstrates creating three FileEventSource instances reading three files with strategies: EARLIEST, LATEST, and
COMMITED. You can easily extend it to ONCE_*.

### How to run

- Build the project: `mvn -q -DskipTests package`
- Run the example with your IDE or via `java` after building. The example writes demo files into the system temp
  directory so it can run out-of-the-box.

### What to expect

- For EARLIEST: existing lines in the file will be published, and new appended lines will also be published.
- For LATEST: only lines appended after startup will be published.
- For COMMITED: on first run behaves like EARLIEST with tailing; on subsequent runs, resumes from last committed
  position stored in `.readPointer`. Lines read but not yet processed when the previous run ended are delivered
  again.

See the example source for full details and logging output.
