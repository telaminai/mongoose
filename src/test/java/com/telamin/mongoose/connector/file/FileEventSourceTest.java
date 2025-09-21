/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.connector.file;

import com.fluxtion.agrona.concurrent.OneToOneConcurrentArrayQueue;
import com.telamin.fluxtion.runtime.event.NamedFeedEvent;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

class FileEventSourceTest {

    private static final boolean TEST_KEEP_FILES = Boolean.getBoolean("TEST_KEEP_FILES");

    @TempDir
    Path tempDir;

    private Path dataFile;
    private Path readPointerFile;

    @Test
    void testReadEvents_tailCommitted_withCacheThenPublish() throws IOException {
        // Arrange temp files
        dataFile = tempDir.resolve("events.txt");
        readPointerFile = Paths.get(dataFile.toString() + ".readPointer");

        // initial content
        Files.writeString(
                dataFile,
                "item 1\nitem 2\n",
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
        );

        // Set up event source
        FileEventSource fileEventSource = new FileEventSource();
        fileEventSource.setFilename(dataFile.toString());
        fileEventSource.setCacheEventLog(true); // cache pre-start, then publish on startComplete

        // Inject target queue
        EventToQueuePublisher<String> eventToQueue = new EventToQueuePublisher<>("fileEventFeed");
        OneToOneConcurrentArrayQueue<Object> targetQueue = new OneToOneConcurrentArrayQueue<>(128);
        eventToQueue.addTargetQueue(targetQueue, "outputQueue");
        fileEventSource.setOutput(eventToQueue);

        // Act: run lifecycle and consume
        fileEventSource.onStart();
        fileEventSource.start();          // pre-caches if cacheEventLog = true
        fileEventSource.startComplete();  // switches to publish mode and replays cache

        // Perform a work cycle
        fileEventSource.doWork();

        // Assert: initial lines published
        ArrayList<Object> actual = new ArrayList<>();
        targetQueue.drainTo(actual, 100);
        Assertions.assertIterableEquals(List.of("item 1", "item 2"), actual.stream().map(Object::toString).collect(Collectors.toList()));

        // Append more lines, ensure they publish on next doWork
        Files.writeString(
                dataFile,
                "item 3\nitem 4\n",
                StandardOpenOption.APPEND
        );

        // No implicit drain until we do another doWork
        actual.clear();
        targetQueue.drainTo(actual, 100);
        Assertions.assertTrue(actual.isEmpty(), "No new items should be in queue before doWork");

        fileEventSource.doWork();
        targetQueue.drainTo(actual, 100);
        Assertions.assertIterableEquals(List.of("item 3", "item 4"), actual.stream().map(Object::toString).collect(Collectors.toList()));

        // Verify event log contains all items when cache was enabled
        List<String> eventLogData = eventToQueue.getEventLog()
                .stream().map(NamedFeedEvent::data).map(Object::toString).collect(Collectors.toList());
        Assertions.assertIterableEquals(List.of("item 1", "item 2", "item 3", "item 4"), eventLogData);

        // Teardown lifecycle
        fileEventSource.stop();
        fileEventSource.tearDown();

        // Cleanup files (unless user asked to keep them)
        cleanupIfNeeded();
    }

    private void cleanupIfNeeded() {
        if (TEST_KEEP_FILES) {
            System.out.println("TEST_KEEP_FILES=true; keeping artifacts at: " + tempDir);
            return;
        }
        deleteQuietly(readPointerFile);
        deleteQuietly(dataFile);
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }
}
