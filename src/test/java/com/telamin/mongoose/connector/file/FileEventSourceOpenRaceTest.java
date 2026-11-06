/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.connector.file;

import com.telamin.mongoose.config.ReadStrategy;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import com.telamin.mongoose.service.feedstatus.FeedState;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent delta review of PR #98, finding 1: the path is rotated between opening the input file's channel and
 * reading its identity. The source must never label the open (old) file with the replacement's identity: it would read
 * only the old file, compare the replacement's key with itself at every end-of-file check, report CAUGHT_UP, and never
 * read the new file. The rotation is interleaved exactly there through a test seam, so no timing is involved.
 */
class FileEventSourceOpenRaceTest {

    @TempDir
    Path dir;

    private List<String> run(ReadStrategy strategy) throws Exception {
        Path file = dir.resolve("events");
        Files.writeString(file, "old\n");
        Path replacement = dir.resolve("replacement");
        Files.writeString(replacement, "new\n");
        EventToQueuePublisher<String> publisher = new EventToQueuePublisher<>("feed");
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(64);
        publisher.addTargetQueue(queue, "q");
        FileEventSource source = new FileEventSource();
        source.setFilename(file.toString());
        source.setReadStrategy(strategy);
        source.setOutput(publisher);
        boolean[] rotated = {false};
        source.afterOpenForTest = () -> {
            if (rotated[0]) return;
            rotated[0] = true;
            try {
                Files.move(replacement, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        List<String> seen = new ArrayList<>();
        try {
            source.start();
            source.startComplete();
            for (int n = 0; n < 6; n++) {
                source.doWork();
                for (Object x; (x = queue.poll()) != null; ) seen.add(x.toString());
            }
            assertTrue(rotated[0], "the fixture rotated the file just after the channel was opened");
            assertTrue(seen.contains("new"), "the replacement must be read, observed=" + seen + " status="
                    + publisher.feedPosition().status());
            assertEquals(FeedState.CAUGHT_UP, publisher.feedPosition().status().state());
        } finally {
            source.stop();
        }
        return seen;
    }

    @Test
    void aRotationBetweenTheOpenAndTheIdentityReadIsNotMissedCommitted() throws Exception {
        List<String> seen = run(ReadStrategy.COMMITED);
        assertEquals("new", seen.get(seen.size() - 1), seen.toString());
    }

    @Test
    void aRotationBetweenTheOpenAndTheIdentityReadIsNotMissedEarliest() throws Exception {
        List<String> seen = run(ReadStrategy.EARLIEST);
        assertEquals("new", seen.get(seen.size() - 1), seen.toString());
    }
}
