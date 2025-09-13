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
    - When COMMITED is used, FileEventSource persists the last read byte offset into a side-car file named
      `<filename>.readPointer`.
    - On restart, the source resumes from the committed offset if the file exists.

### Strategy details

- COMMITED
    - Start position: If `<filename>.readPointer` exists, resume from the stored offset; otherwise start at offset 0.
    - Mode: Tail (keeps reading as file grows).
    - Persistence: Writes updated offsets to `<filename>.readPointer`.

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
  position stored in `.readPointer`.

See the example source for full details and logging output.
