/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.example.readstrategy;

import com.fluxtion.agrona.concurrent.BusySpinIdleStrategy;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.*;
import com.telamin.mongoose.connector.file.FileEventSource;
import com.telamin.mongoose.connector.memory.InMemoryMessageSink;
import com.telamin.mongoose.example.NamedFeedsFilterHandler;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Example that boots a Fluxtion server and wires three FileEventSource services
 * using different ReadStrategy values. It appends to the files for a few
 * seconds to demonstrate tailing and then shuts the server down.
 */
public class FileReadStrategyExample {

    @Test
    public void boot_server_and_run_file_sources() throws Exception {
        Path tempDir = Files.createTempDirectory("fluxtion-readstrategy-");
        System.out.println("Demo directory: " + tempDir);

        File earliestFile = tempDir.resolve("earliest.txt").toFile();
        File latestFile = tempDir.resolve("latest.txt").toFile();
        File committedFile = tempDir.resolve("committed.txt").toFile();

        writeInitial(earliestFile, Arrays.asList("e-1", "e-2"));
        writeInitial(latestFile, Arrays.asList("l-1", "l-2"));
        writeInitial(committedFile, Arrays.asList("c-1", "c-2"));

        // ---------- Run 1: boot server, append first wave, validate ----------
        InMemoryMessageSink sinkRun1 = new InMemoryMessageSink();
        Thread.sleep(1000);
        MongooseServer server = bootServerWithFeeds(earliestFile, latestFile, committedFile, sinkRun1);
        try {
            // Append one line to each file
            appendLine(earliestFile, "e-first");
            appendLine(latestFile, "l-first");
            appendLine(committedFile, "c-first");

            // Wait for expected minimum messages: earliest(2 init + 1), committed(2 init + 1), latest(1) = 7
            List<Object> run1 = waitForMessages(sinkRun1, 7, 5, TimeUnit.SECONDS);

            // Validate run1 expectations
            assertTrue(run1.stream().anyMatch(s -> s.toString().equals("e-1")));
            assertTrue(run1.stream().anyMatch(s -> s.toString().equals("e-2")));
            assertTrue(run1.stream().anyMatch(s -> s.toString().equals("e-first")));

            // LATEST should not replay initial lines
            assertFalse(run1.stream().anyMatch(s -> s.toString().equals("l-1")));
            assertFalse(run1.stream().anyMatch(s -> s.toString().equals("l-2")));
            assertTrue(run1.stream().anyMatch(s -> s.toString().equals("l-first")));

            // COMMITED acts like earliest on first run
            assertTrue(run1.stream().anyMatch(s -> s.toString().equals("c-1")));
            assertTrue(run1.stream().anyMatch(s -> s.toString().equals("c-2")));
            assertTrue(run1.stream().anyMatch(s -> s.toString().equals("c-first")));
        } finally {
            server.stop();
        }

        // ---------- Run 2: restart server, append second wave, validate ----------
        InMemoryMessageSink sinkRun2 = new InMemoryMessageSink();
        server = bootServerWithFeeds(earliestFile, latestFile, committedFile, sinkRun2);
        try {
            appendLine(earliestFile, "e-restart");
            appendLine(latestFile, "l-restart");
            appendLine(committedFile, "c-restart");

            // For run2, earliest will replay entire file (>= 4 earliest lines now), latest and committed only new lines
            List<Object> run2 = waitForMessages(sinkRun2, 1 + 1 + 4, 5, TimeUnit.SECONDS);

            // EARLIEST replays from start, should include all
            assertTrue(run2.stream().anyMatch(s -> s.toString().equals("e-1")));
            assertTrue(run2.stream().anyMatch(s -> s.toString().equals("e-2")));
            assertTrue(run2.stream().anyMatch(s -> s.toString().equals("e-first")));
            assertTrue(run2.stream().anyMatch(s -> s.toString().equals("e-restart")));

            // LATEST only new after restart
            assertFalse(run2.stream().anyMatch(s -> s.toString().equals("l-1")));
            assertFalse(run2.stream().anyMatch(s -> s.toString().equals("l-2")));
            assertFalse(run2.stream().anyMatch(s -> s.toString().equals("l-first")));
            assertTrue(run2.stream().anyMatch(s -> s.toString().equals("l-restart")));

            // COMMITED resumes from pointer; should NOT include initial nor first, only restart
            assertFalse(run2.stream().anyMatch(s -> s.toString().equals("c-1")));
            assertFalse(run2.stream().anyMatch(s -> s.toString().equals("c-2")));
            assertFalse(run2.stream().anyMatch(s -> s.toString().equals("c-first")));
            assertTrue(run2.stream().anyMatch(s -> s.toString().equals("c-restart")));
        } finally {
            server.stop();
        }
    }

    private static MongooseServer bootServerWithFeeds(File earliestFile, File latestFile, File committedFile, InMemoryMessageSink sink) {
        // Create file sources
        FileEventSource earliestSrc = new FileEventSource();
        earliestSrc.setName("earliestSrc");
        earliestSrc.setFilename(earliestFile.getAbsolutePath());
        earliestSrc.setReadStrategy(ReadStrategy.EARLIEST);
        earliestSrc.setCacheEventLog(true);

        FileEventSource latestSrc = new FileEventSource();
        latestSrc.setName("latestSrc");
        latestSrc.setFilename(latestFile.getAbsolutePath());
        latestSrc.setReadStrategy(ReadStrategy.LATEST);
        latestSrc.setCacheEventLog(true);

        FileEventSource committedSrc = new FileEventSource();
        committedSrc.setName("committedSrc");
        committedSrc.setFilename(committedFile.getAbsolutePath());
        committedSrc.setReadStrategy(ReadStrategy.COMMITED);
        committedSrc.setCacheEventLog(true);

        // Processor that subscribes to all three feeds
        NamedFeedsFilterHandler handler = new NamedFeedsFilterHandler(List.of(
                earliestSrc.getName(), latestSrc.getName(), committedSrc.getName()
        ).stream().collect(java.util.stream.Collectors.toSet()));

        EventProcessorGroupConfig processors = EventProcessorGroupConfig.builder()
                .agentName("processor-agent")
                .put("printer", new EventProcessorConfig(handler))
                .build();

        EventFeedConfig<?> earliestFeed = EventFeedConfig.builder()
                .instance(earliestSrc)
                .name(earliestSrc.getName())
                .broadcast(true)
                .agent("earliest-agent", new BusySpinIdleStrategy())
                .build();
        EventFeedConfig<?> latestFeed = EventFeedConfig.builder()
                .instance(latestSrc)
                .name(latestSrc.getName())
                .broadcast(true)
                .agent("latest-agent", new BusySpinIdleStrategy())
                .build();
        EventFeedConfig<?> committedFeed = EventFeedConfig.builder()
                .instance(committedSrc)
                .name(committedSrc.getName())
                .broadcast(true)
                .agent("committed-agent", new BusySpinIdleStrategy())
                .build();

        EventSinkConfig<MessageSink<?>> sinkCfg = EventSinkConfig.<MessageSink<?>>builder()
                .instance(sink)
                .name("memSink")
                .build();

        MongooseServerConfig mongooseServerConfig = MongooseServerConfig.builder()
                .addProcessorGroup(processors)
                .addEventFeed(earliestFeed)
                .addEventFeed(latestFeed)
                .addEventFeed(committedFeed)
                .addEventSink(sinkCfg)
                .build();

        LogRecordListener logs = rec -> {
        };
        return MongooseServer.bootServer(mongooseServerConfig, logs);
    }

    private static List<Object> waitForMessages(InMemoryMessageSink sink, int minCount, long timeout, TimeUnit unit) throws Exception {
        long end = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < end) {
            List<Object> lines = sink.getMessages();
            if (lines.size() >= minCount) {
                return lines;
            }
            Thread.sleep(50);
        }
        return sink.getMessages();
    }

    private static void writeInitial(File file, List<String> lines) throws IOException {
        file.getParentFile().mkdirs();
        try (FileWriter fw = new FileWriter(file)) {
            for (String l : lines) {
                fw.write(l);
                fw.write(System.lineSeparator());
                fw.flush();
            }
        }
    }

    private static void appendLine(File file, String line) throws IOException {
        try (FileWriter fw = new FileWriter(file, true)) {
            fw.write(line);
            fw.write(System.lineSeparator());
            fw.flush();
        }
    }
}
