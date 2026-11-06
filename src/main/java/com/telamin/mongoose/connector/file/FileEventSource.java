/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.connector.file;

import com.telamin.fluxtion.runtime.event.NamedFeedEvent;
import com.telamin.mongoose.config.ReadStrategy;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import com.telamin.mongoose.service.extension.AbstractAgentHostedEventSourceService;
import com.telamin.mongoose.service.feedstatus.FeedState;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.java.Log;
import org.agrona.IoUtil;

import java.io.*;
import java.nio.MappedByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

@Log
@SuppressWarnings("all")
public class FileEventSource extends AbstractAgentHostedEventSourceService<String> {

    @Getter
    @Setter
    private String filename;
    private InputStream stream;
    /**
     * Bytes read from the file and not yet taken as lines. Lines are split on the bytes {@code \n} and {@code \r}
     * (never part of a multi-byte UTF-8 sequence, nor of any ASCII-compatible encoding) and only then decoded, so the
     * read position is the exact byte offset on disk whatever the bytes decode to (review F4).
     */
    private byte[] buffer;
    private int offset = 0;
    @Getter
    @Setter
    private boolean cacheEventLog = false;
    @Getter
    @Setter
    private ReadStrategy readStrategy = ReadStrategy.COMMITED;
    private boolean tail = true;
    private boolean commitRead = true;
    private boolean latestRead = false;
    private final AtomicBoolean startComplete = new AtomicBoolean(false);

    private long streamOffset;
    private MappedByteBuffer commitPointer;
    private boolean once;
    private boolean publishToQueue = false;
    /** Set by stop, cleared by start: a stopped source reads nothing, so it never reopens the file it released. */
    private volatile boolean stopped = false;
    /** The feed state last reported (docs/design/feed-caught-up.md), so only transitions are reported, never polls. */
    private FeedState reportedState;
    private String reportedDetail;

    // ---- delivery (docs/design/feed-delivery.md) ----
    /** A line read from the file that the publisher could not take (a full PAUSE queue): offered first next time. */
    private String pendingLine;
    /** The stream offset just after {@link #pendingLine}. */
    private long pendingLineEnd;
    /** A once strategy has read data: it ends once it has published everything to the end of the file. */
    private boolean onceReadData;
    /** Guards {@link #commitPointer} between the committer (the agent's duty cycle) and {@link #stop()}. */
    private final Object commitLock = new Object();
    /** The offset last written to the read pointer. */
    private long committedOffset;
    /**
     * COMMITED: the read pointer is written only for lines every subscriber has processed, never for a line merely read
     * (at-least-once across a crash: a restart re-reads what was read but not processed).
     */
    private final com.telamin.mongoose.service.delivery.SourceCommitter<Long> committer =
            new com.telamin.mongoose.service.delivery.SourceCommitter<>() {
                private long acknowledged = -1;

                @Override
                public void acknowledged(long sequence, Long position) {
                    if (sequence > resetAtSequence) acknowledged = position;
                }

                @Override
                public void commit() {
                    writePointer(acknowledged);
                }
            };

    // Cached JUL log guards to minimize per-call allocations
    private final boolean infoEnabled;
    private final boolean fineEnabled;
    private final boolean finestEnabled;
    private final boolean warningEnabled;
    private final boolean severeEnabled;

    public FileEventSource() {
        this(1024);
    }

    /* visible for testing */
    public FileEventSource(int initialBufferSize) {
        super("fileEventFeed");
        buffer = new byte[initialBufferSize];
        infoEnabled = log.isLoggable(Level.INFO);
        fineEnabled = log.isLoggable(Level.FINE);
        finestEnabled = log.isLoggable(Level.FINEST);
        warningEnabled = log.isLoggable(Level.WARNING);
        severeEnabled = log.isLoggable(Level.SEVERE);
    }

    @Override
    public void start() {
        if (infoEnabled) {
            log.log(Level.INFO, "start FileEventSource " + serviceName + " file:" + filename);
        }
        stopped = false;
        if (filename == null || filename.isEmpty()) {
            throw new IllegalStateException(
                    "FileEventSource " + serviceName + " has no filename configured");
        }
        tail = readStrategy == ReadStrategy.COMMITED | readStrategy == ReadStrategy.EARLIEST | readStrategy == ReadStrategy.LATEST;
        once = !tail;
        commitRead = readStrategy == ReadStrategy.COMMITED;
        latestRead = readStrategy == ReadStrategy.LATEST | readStrategy == ReadStrategy.ONCE_LATEST;
        if (infoEnabled) {
            log.log(Level.INFO, "tail:" + tail + " once:" + once + ", commitRead:" + commitRead + " latestRead:" + latestRead + " readStrategy:" + readStrategy);
        }
        // a (re)started source has read nothing yet; it reports from doWork once the start is complete
        reportedState = null;
        report(FeedState.BEHIND, "starting");
        // reading restarts at the pointer (or the strategy's start): nothing read before is pending
        offset = 0;
        pendingLine = null;
        onceReadData = false;
        if (commitRead && output != null) {
            output.acknowledgeWith(committer);
        }

        File committedReadFile = new File(filename + ".readPointer");
        File committedReadFileParent = committedReadFile.getParentFile();
        if (committedReadFileParent != null && !committedReadFileParent.exists()) {
            if (!committedReadFileParent.mkdirs() && !committedReadFileParent.exists() && warningEnabled) {
                log.log(Level.WARNING, "could not create parent directory for "
                        + committedReadFile.getAbsolutePath()
                        + " — subsequent mapping may fail");
            }
        }
        if (readStrategy == ReadStrategy.ONCE_EARLIEST | readStrategy == ReadStrategy.EARLIEST) {
            streamOffset = 0;
        } else if (committedReadFile.exists()) {
            commitPointer = IoUtil.mapExistingFile(committedReadFile, "committedReadFile_" + filename);
            streamOffset = commitPointer.getLong(0);
            committedOffset = streamOffset;
            epoch = commitPointer.getLong(EPOCH_SLOT);
            if (epoch == 0) {
                // a pointer written before epochs: its positions are kept, under a new epoch from now on
                epoch = newEpoch();
                commitPointer.putLong(EPOCH_SLOT, epoch);
            }
            if (infoEnabled) {
                log.log(Level.INFO, serviceName + " reading committedReadFile:" + committedReadFile.getAbsolutePath() + ", streamOffset:" + streamOffset);
            }
        } else if (commitRead) {
            commitPointer = IoUtil.mapNewFile(committedReadFile, 1024);
            streamOffset = 0;
            committedOffset = 0;
            // a new pointer, a new position space: a deleted pointer never resumes an application's old positions
            epoch = newEpoch();
            commitPointer.putLong(EPOCH_SLOT, epoch);
            if (infoEnabled) {
                log.log(Level.INFO, serviceName + " creating committedReadFile:" + committedReadFile.getAbsolutePath() + ", streamOffset:" + streamOffset);
            }
        }

        // If starting strategy is LATEST or ONCE_LATEST and no commit pointer dictates otherwise,
        // start reading from end-of-file so we only emit new lines.
        if (latestRead && !commitRead) {
            try {
                File f = new File(filename);
                if (f.exists()) {
                    streamOffset = f.length();
                    if (infoEnabled) {
                        log.log(Level.INFO, "initialising streamOffset to EOF for LATEST: " + streamOffset);
                    }
                }
            } catch (Throwable t) {
                if (warningEnabled) {
                    log.log(Level.WARNING, "Failed to determine EOF for LATEST, defaulting to 0: " + t);
                }
            }
        }

        connectReader();
        // preserve once/tail semantics as derived above; do not unconditionally force tailing

        output.setCacheEventLog(cacheEventLog);
        tail |= readStrategy == ReadStrategy.ONCE_EARLIEST;
        if (cacheEventLog) {
            if (infoEnabled) {
                log.log(Level.INFO, "cacheEventLog: " + cacheEventLog);
            }
            startComplete.set(true);
            publishToQueue = false;
//            boolean oldTail = tail;
            doWork();
            startComplete.set(false);
//            tail = oldTail;
        }
    }

    @Override
    public void onStart() {
        if (infoEnabled) {
            log.log(Level.INFO, "agent onStart FileEventSource " + serviceName + " file:" + filename);
        }
    }

    @Override
    public void startComplete() {
        if (infoEnabled) {
            log.log(Level.INFO, "startComplete FileEventSource " + serviceName + " file:" + filename);
        }
        startComplete.set(true);
        publishToQueue = true;
        output.startComplete();
    }

    @Override
    public NamedFeedEvent<String>[] eventLog() {
        List<NamedFeedEvent> eventLog = (List) output.getEventLog();
        return eventLog.toArray(new NamedFeedEvent[0]);
    }

    @Override
    public String getFeedName() {
        return getName();
    }

    /**
     * One duty cycle: commit what the subscribers have processed since the last cycle, then read. The commit lag is
     * bounded by one duty cycle of this source's agent after a line's event cycle completes (docs/design/feed-delivery.md
     * section 4): a cycle that committed reports work, so the agent does not idle-sleep while acknowledgements flow.
     */
    @Override
    public int doWork() {
        if (stopped) {
            return 0;
        }
        int acknowledged = publishToQueue ? output.pollAcknowledgements() : 0;
        int read = read();
        if (read > 0 && publishToQueue) {
            // lines published this cycle may already be processed by a fast subscriber: commit them now
            acknowledged += output.pollAcknowledgements();
        }
        return read + (acknowledged > 0 ? 1 : 0);
    }

    @SuppressWarnings("all")
    private int read() {
        if (stopped) {
            return 0;
        }
        if (publishToQueue) {
            // finish a paused cache replay (or a waiting status) before reading on
            if (!output.flushPaused()) {
                return 0;
            }
        }
        if (!tail) {
            // a once strategy has read the file: nothing more will ever be published
            if (publishToQueue) report(FeedState.END_OF_STREAM, null);
            return 0;
        }
        try {
            if (connectReader() == null) {
                // nothing to read yet: everything available (nothing) is delivered; BEHIND once the file appears
                if (publishToQueue) report(FeedState.CAUGHT_UP, "file not found: " + filename);
                return 0;
            }
            if (fineEnabled) {
                log.log(Level.FINE, "doWork FileEventFeed");
            }
            int readCount = 0;

            // what was read but not taken last time goes first: the line the publisher paused on, then whole lines
            // left in the buffer. Nothing more is read from the file while the feed is paused
            if (pendingLine != null) {
                if (!offer(pendingLine, pendingLineEnd)) {
                    return readCount;
                }
                pendingLine = null;
                readCount++;
            }
            int drained = drainBufferedLines();
            if (drained < 0) {
                return readCount - drained - 1;
            }
            readCount += drained;

            int nread;
            while (stream.available() > 0) {
                // more is available: reported before the first line it publishes, so a host never sees CAUGHT_UP at a
                // position these lines are past
                if (publishToQueue) report(FeedState.BEHIND, null);
                onceReadData |= once;
                nread = stream.read(buffer, offset, buffer.length - offset);
                if (finestEnabled) {
                    log.log(Level.FINEST, "Read " + nread + " bytes from " + getFilename());
                }

                if (nread > 0) {
                    offset += nread;
                    drained = drainBufferedLines();
                    if (drained < 0) {
                        // paused: the line and the rest of the buffer are kept, and the file position with them
                        return readCount - drained - 1;
                    }
                    readCount += drained;

                    if (offset == buffer.length) {
                        byte[] newbuf = new byte[buffer.length * 2];
                        System.arraycopy(buffer, 0, newbuf, 0, buffer.length);
                        if (infoEnabled) {
                            log.log(Level.INFO, "Increased buffer from " + buffer.length + " to " + newbuf.length);
                        }
                        buffer = newbuf;
                    }
                }
            }
            if (onceReadData) {
                // a once strategy has published everything to the end of the file as it was: it ends here
                tail = false;
            }
            if (publishToQueue) {
                if (!tail) {
                    report(FeedState.END_OF_STREAM, offset > 0 ? "unterminated last line not published" : null);
                } else {
                    // at the end of the file as it is now; an unterminated last line is published once it ends
                    report(FeedState.CAUGHT_UP, offset > 0 ? "partial line pending" : null);
                }
            }

            return readCount;

        } catch (IOException e) {
            if (publishToQueue) report(FeedState.UNKNOWN, "read failed, reopening: " + e);
            try {
                stream.close();
            } catch (IOException ex) {

            }
            stream = null;
        }
        return 0;
    }

    @Override
    public void stop() {
        stopped = true;
        if (infoEnabled) {
            log.log(Level.INFO, "Stopping");
        }
        if (commitRead && output != null) {
            try {
                // a clean stop commits everything processed so far; what is not is re-read at the next start
                output.pollAcknowledgements();
            } catch (Throwable t) {
                if (warningEnabled) {
                    log.log(Level.WARNING, serviceName + " final commit failed: " + t);
                }
            }
        }
        try {
            if (stream != null) {
                stream.close();
                if (infoEnabled) {
                    log.log(Level.INFO, "Closed input stream");
                }
            }
        } catch (IOException e) {
            if (severeEnabled) {
                log.log(Level.SEVERE, "Failed to close FileStreamSourceTask stream", e);
            }
        } finally {
            // mongoose#56: drop the references once released, so a second stop neither closes nor unmaps them again
            stream = null;
            synchronized (commitLock) {
                if (commitPointer != null) {
                    commitPointer.force();
                    IoUtil.unmap(commitPointer);
                    commitPointer = null;
                }
            }
        }
    }

    @Override
    public void tearDown() {
        super.tearDown();
    }

    private InputStream connectReader() {
        if (startComplete.get() & stream == null && filename != null && !filename.isEmpty()) {
            try {
                stream = Files.newInputStream(Paths.get(filename));
                checkSourceIdentity();
                if (infoEnabled) {
                    log.log(Level.INFO, "Found previous offset, trying to skip to file offset " + streamOffset);
                }
                long skipLeft = streamOffset;
                while (skipLeft > 0) {
                    try {
                        long skipped = stream.skip(skipLeft);
                        skipLeft -= skipped;
                    } catch (IOException e) {
                        if (severeEnabled) {
                            log.log(Level.SEVERE, "Error while trying to seek to previous offset in file " + filename + ": ", e);
                        }
                        //TODO log error and stop
                    }
                }
                if (infoEnabled) {
                    log.log(Level.INFO, "Skipped to offset " + streamOffset);
                }
                if (infoEnabled) {
                    log.log(Level.INFO, "Opened " + getFilename() + " for reading");
                }
            } catch (NoSuchFileException e) {
                if (warningEnabled) {
                    log.log(Level.WARNING, "Couldn't find file " + getFilename() + " for FileStreamSourceTask, sleeping to wait for it to be created");
                }
            } catch (IOException e) {
                if (severeEnabled) {
                    log.log(Level.SEVERE, "Error while trying to open file " + filename + ": ", e);
                }
                throw new RuntimeException(e);
            }
        }
        return stream;
    }

    // ---- source epoch (docs/design/feed-delivery.md 5a, review F5) ----
    // The pointer file holds: [0] the committed byte offset, [8] the epoch, [16] the input file's identity

    private static final int EPOCH_SLOT = 8;
    private static final int IDENTITY_SLOT = 16;
    /** The epoch of this source's positions; a new one whenever the position space is reset. */
    private volatile long epoch;
    /** Acknowledgements of items published before the last reset are of the old epoch: ignored. */
    private long resetAtSequence;

    private static long newEpoch() {
        long e;
        do {
            e = java.util.concurrent.ThreadLocalRandom.current().nextLong();
        } while (e == 0);
        return e;
    }

    /** The input file's identity: its file key (inode on Unix) and creation time; 0 when unknown. */
    private long fileIdentity() {
        try {
            var attrs = Files.readAttributes(Paths.get(filename), java.nio.file.attribute.BasicFileAttributes.class);
            long id = 31L * String.valueOf(attrs.fileKey()).hashCode() + attrs.creationTime().toMillis();
            return id == 0 ? 1 : id;
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * At each open: a COMMITED pointer is only meaningful for the file it was written for. When the input file was
     * replaced (another identity) or truncated (shorter than the pointer), the pointer restarts at 0 with a new epoch,
     * so an application that keeps (epoch, position) with its state never takes a new line for an applied one. Other
     * strategies have no pointer: their epoch is the file's identity, stable for as long as the file is the same.
     */
    private void checkSourceIdentity() throws IOException {
        long identity = fileIdentity();
        synchronized (commitLock) {
            if (commitPointer == null || !commitRead) {
                epoch = identity == 0 ? 1 : identity;
            } else {
                long recorded = commitPointer.getLong(IDENTITY_SLOT);
                long size = Files.size(Paths.get(filename));
                String reset = recorded != 0 && identity != 0 && recorded != identity ? "the input file was replaced"
                        : size < streamOffset ? "the input file was truncated (" + size + " < pointer " + streamOffset + ")"
                        : null;
                if (reset != null) {
                    if (warningEnabled) {
                        log.log(Level.WARNING, serviceName + ": " + reset + "; the read pointer restarts at 0 with a "
                                + "new epoch: " + filename);
                    }
                    streamOffset = 0;
                    committedOffset = 0;
                    offset = 0;
                    pendingLine = null;
                    commitPointer.putLong(0, 0);
                    epoch = newEpoch();
                    commitPointer.putLong(EPOCH_SLOT, epoch);
                    resetAtSequence = output == null ? 0 : output.getSequenceNumber();
                }
                if (identity != 0) commitPointer.putLong(IDENTITY_SLOT, identity);
                commitPointer.force();
            }
        }
        if (output != null) output.setSourceEpoch(epoch);
    }

    /** The epoch of this source's positions (docs/design/feed-delivery.md 5a). */
    public long epoch() {
        return epoch;
    }

    /**
     * Report a feed state change once: a repeat of the last state and detail is not reported again, nor BEHIND while
     * already BEHIND (reading on from the start is not a new transition).
     */
    private void report(FeedState state, String detail) {
        if (state == reportedState && (state == FeedState.BEHIND || java.util.Objects.equals(detail, reportedDetail))) {
            return;
        }
        reportedState = state;
        reportedDetail = detail;
        // bounded: a once strategy ends; a tailing strategy reads for as long as it runs
        reportFeedStatus(state, once, detail);
    }

    /**
     * Offer every whole line in the buffer, in order. Returns how many were taken, or, when the publisher paused,
     * {@code -(taken + 1)}: the paused line is kept in {@link #pendingLine} and the rest stays in the buffer.
     */
    private int drainBufferedLines() {
        int taken = 0;
        String lastReadLine = null;
        long lastReadEnd = streamOffset;
        String line;
        while ((line = extractLine()) != null) {
            if (finestEnabled) {
                log.log(Level.FINEST, "Read a line from " + getFilename() + " line:" + line);
            }
            if (latestRead) {
                // LATEST publishes the last line of each read only
                lastReadLine = line;
                lastReadEnd = streamOffset;
            } else if (offer(line, streamOffset)) {
                taken++;
            } else {
                return -(taken + 1);
            }
        }
        if (latestRead & lastReadLine != null & !once) {
            if (finestEnabled) {
                log.log(Level.FINEST, "publish latest:" + lastReadLine);
            }
            if (!offer(lastReadLine, lastReadEnd)) {
                return -(taken + 1);
            }
            taken++;
        }
        return taken;
    }

    /**
     * Publish (after start) or cache (before it) one line read up to {@code endOffset}. False when the publisher paused:
     * the line is kept and offered again on the next duty cycle, and the file is not read further meanwhile.
     */
    private boolean offer(String line, long endOffset) {
        Long position = commitRead ? Long.valueOf(endOffset) : null;
        if (publishToQueue) {
            if (fineEnabled) {
                log.log(Level.FINE, "publish record:" + line);
            }
            if (output.tryPublish(line, position)) {
                return true;
            }
            pendingLine = line;
            pendingLineEnd = endOffset;
            return false;
        }
        if (fineEnabled) {
            log.log(Level.FINE, "cache record:" + line);
        }
        output.cache(line, position);
        return true;
    }

    /** Write the read pointer for a processed position: forward only, and never after stop released the mapping. */
    private void writePointer(long acknowledgedOffset) {
        synchronized (commitLock) {
            if (commitPointer == null || acknowledgedOffset <= committedOffset) {
                return;
            }
            commitPointer.putLong(0, acknowledgedOffset);
            commitPointer.force();
            committedOffset = acknowledgedOffset;
        }
    }

    /** The offset the read pointer holds: every line before it has been processed. */
    public long committedOffset() {
        synchronized (commitLock) {
            return committedOffset;
        }
    }

    private String extractLine() {
        int until = -1, newStart = -1;
        for (int i = 0; i < offset; i++) {
            if (buffer[i] == '\n') {
                until = i;
                newStart = i + 1;
                break;
            } else if (buffer[i] == '\r') {
                // We need to check for \r\n, so we must skip this if we can't check the next char
                if (i + 1 >= offset)
                    return null;

                until = i;
                newStart = (buffer[i + 1] == '\n') ? i + 2 : i + 1;
                break;
            }
        }

        if (until != -1) {
            // decoded as UTF-8 (malformed bytes become U+FFFD in the line); the position is the exact byte count, which
            // a restart skips; the read pointer is written only once the line is processed
            String result = new String(buffer, 0, until, StandardCharsets.UTF_8);
            streamOffset += newStart;
            System.arraycopy(buffer, newStart, buffer, 0, buffer.length - newStart);
            offset = offset - newStart;
            return result;
        } else {
            return null;
        }
    }

    //for testing
    void setOutput(EventToQueuePublisher<String> output) {
        this.output = output;
        output.setPauseByDefault(true);
    }

    /** A file can be re-read from its pointer: a feed with no overload policy pauses instead of dropping. */
    @Override
    public void setEventFlowManager(com.telamin.mongoose.dispatch.EventFlowManager eventFlowManager, String serviceName) {
        super.setEventFlowManager(eventFlowManager, serviceName);
        output.setPauseByDefault(true);
    }
}