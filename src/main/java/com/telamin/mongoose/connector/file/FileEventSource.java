/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.connector.file;

import com.fluxtion.agrona.IoUtil;
import com.telamin.fluxtion.runtime.event.NamedFeedEvent;
import com.telamin.mongoose.config.ReadStrategy;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import com.telamin.mongoose.service.extension.AbstractAgentHostedEventSourceService;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.java.Log;

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
    private BufferedReader reader = null;
    private char[] buffer;
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
        buffer = new char[initialBufferSize];
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
        tail = readStrategy == ReadStrategy.COMMITED | readStrategy == ReadStrategy.EARLIEST | readStrategy == ReadStrategy.LATEST;
        once = !tail;
        commitRead = readStrategy == ReadStrategy.COMMITED;
        latestRead = readStrategy == ReadStrategy.LATEST | readStrategy == ReadStrategy.ONCE_LATEST;
        if (infoEnabled) {
            log.log(Level.INFO, "tail:" + tail + " once:" + once + ", commitRead:" + commitRead + " latestRead:" + latestRead + " readStrategy:" + readStrategy);
        }

        File committedReadFile = new File(filename + ".readPointer");
        if (readStrategy == ReadStrategy.ONCE_EARLIEST | readStrategy == ReadStrategy.EARLIEST) {
            streamOffset = 0;
        } else if (committedReadFile.exists()) {
            commitPointer = IoUtil.mapExistingFile(committedReadFile, "committedReadFile_" + filename);
            streamOffset = commitPointer.getLong(0);
            if (infoEnabled) {
                log.log(Level.INFO, serviceName + " reading committedReadFile:" + committedReadFile.getAbsolutePath() + ", streamOffset:" + streamOffset);
            }
        } else if (commitRead) {
            commitPointer = IoUtil.mapNewFile(committedReadFile, 1024);
            streamOffset = 0;
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

        if (filename == null || filename.isEmpty()) {
            //throw an  error
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
        output.dispatchCachedEventLog();
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

    @SuppressWarnings("all")
    @Override
    public int doWork() {
//        if (!tail && !cacheEventLog) {
        if (!tail) {
            return 0;
        }
        try {
            if (connectReader() == null) {
                return 0;
            }
            if (fineEnabled) {
                log.log(Level.FINE, "doWork FileEventFeed");
            }
            String lastReadLine = null;
            int readCount = 0;
            int nread;

            while (reader.ready()) {
                tail = !once;
                nread = reader.read(buffer, offset, buffer.length - offset);
                if (finestEnabled) {
                    log.log(Level.FINEST, "Read " + nread + " bytes from " + getFilename());
                }

                if (nread > 0) {
                    offset += nread;
                    String line;
                    do {
                        line = extractLine();
                        if (line != null) {
                            readCount++;
                            if (finestEnabled) {
                                log.log(Level.FINEST, "Read a line from " + getFilename() + " count:" + readCount + " line:" + line);
                            }
                            if (latestRead) {
                                lastReadLine = line;
                            } else {
                                publish(line);
                            }
                        }
                    } while (line != null);

                    if (latestRead & lastReadLine != null & !once) {
                        if (finestEnabled) {
                            log.log(Level.FINEST, "publish latest:" + lastReadLine);
                        }
                        publish(lastReadLine);
                    }

                    if (lastReadLine == null && offset == buffer.length) {
                        char[] newbuf = new char[buffer.length * 2];
                        System.arraycopy(buffer, 0, newbuf, 0, buffer.length);
                        if (infoEnabled) {
                            log.log(Level.INFO, "Increased buffer from " + buffer.length + " to " + newbuf.length);
                        }
                        buffer = newbuf;
                    }
                }
            }
            tail |= readStrategy != ReadStrategy.ONCE_EARLIEST;

            return readCount;

        } catch (IOException e) {
            try {
                reader.close();
            } catch (IOException ex) {

            }
            try {
                stream.close();
            } catch (IOException ex) {

            }
            reader = null;
            stream = null;
        }
        return 0;
    }

    @Override
    public void stop() {
        if (infoEnabled) {
            log.log(Level.INFO, "Stopping");
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
            if (commitPointer != null) {
                commitPointer.force();
                IoUtil.unmap(commitPointer);
            }
        }
    }

    @Override
    public void tearDown() {
        super.tearDown();
    }

    private Reader connectReader() {
        if (startComplete.get() & stream == null && filename != null && !filename.isEmpty()) {
            try {
                stream = Files.newInputStream(Paths.get(filename));
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
                reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
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
        return reader;
    }

    private void publish(String line) {
        if (publishToQueue) {
            if (fineEnabled) {
                log.log(Level.FINE, "publish record:" + line);
            }
            output.publish(line);
        } else {
            if (fineEnabled) {
                log.log(Level.FINE, "cache record:" + line);
            }
            output.cache(line);
        }
        if (commitRead) {
            commitPointer.force();
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
            String result = new String(buffer, 0, until);
            System.arraycopy(buffer, newStart, buffer, 0, buffer.length - newStart);
            offset = offset - newStart;
            streamOffset += newStart;
            if (commitRead) {
                commitPointer.putLong(0, streamOffset);
            }
            return result;
        } else {
            return null;
        }
    }

    //for testing
    void setOutput(EventToQueuePublisher<String> output) {
        this.output = output;
    }
}