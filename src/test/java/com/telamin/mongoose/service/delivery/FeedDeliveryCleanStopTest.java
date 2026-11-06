/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.delivery;

import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.ReadStrategy;
import com.telamin.mongoose.connector.file.FileEventSource;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Finding 4 (docs/design/feed-delivery.md section 4): a clean server stop commits everything processed. The ordered
 * shutdown stops the feeds first and then lets the processor groups drain what is queued, so the pointer must be
 * written once the groups have drained and closed, not only when the source stops.
 */
class FeedDeliveryCleanStopTest {

    @TempDir
    Path tempDir;

    private Path data() {
        return tempDir.resolve("lines.txt");
    }

    private static long offsetAfter(int n) {
        long off = 0;
        for (int i = 1; i <= n; i++) off += ("line-" + i + "\n").length();
        return off;
    }

    @ParameterizedTest(name = "two groups: {0}")
    @ValueSource(booleans = {false, true})
    void aCleanStopCommitsEverythingTheSlowestGroupProcessed(boolean twoGroups) throws Exception {
        StringBuilder b = new StringBuilder();
        for (int i = 1; i <= 3000; i++) b.append("line-").append(i).append('\n');
        Files.writeString(data(), b, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        var slow = new FeedDeliveryServerTest.SlowProcessor(1_000_000);
        var fast = new FeedDeliveryServerTest.SlowProcessor(0);
        FileEventSource src = new FileEventSource();
        src.setFilename(data().toString());
        src.setReadStrategy(ReadStrategy.COMMITED);
        MongooseServerConfig cfg = new MongooseServerConfig();
        cfg.addProcessor("a", slow, "slow");
        if (twoGroups) cfg.addProcessor("b", fast, "fast");
        cfg.addEventSourceWorker(src, FeedDeliveryServerTest.FEED, true, "feed-agent", new SleepingMillisIdleStrategy(1));
        MongooseServer server = MongooseServer.bootServer(cfg);
        try {
            FeedDeliveryServerTest.await(() -> slow.lines.size() >= 200, "200 lines processed", 20);
        } finally {
            server.stop();
        }
        int slowDone = slow.lines.size();
        assertTrue(slowDone < 3000, "the stop came before the end of the file: " + slowDone);
        long pointer = ByteBuffer.wrap(Files.readAllBytes(Path.of(data() + ".readPointer")), 0, 8).getLong();
        assertTrue(pointer <= offsetAfter(slowDone), "never ahead of processing: pointer " + pointer
                + ", slow group processed " + slowDone);
        assertEquals(offsetAfter(slowDone), pointer, "a clean stop commits everything processed: the slow group "
                + "processed " + slowDone + " lines, the pointer is at byte " + pointer);
    }
}
