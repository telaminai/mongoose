/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.example;

import com.fluxtion.agrona.concurrent.BusySpinIdleStrategy;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.*;
import com.telamin.mongoose.connector.file.FileEventSource;
import com.telamin.mongoose.connector.file.FileMessageSink;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Example demonstrating the fluent MongooseServerConfig builder to:
 * - add two event sources (FileEventSource and InMemoryEventSource)
 * - add an ObjectEventHandlerNode-based processor
 * - add a FileMessageSink output
 * The processor receives events from both sources and writes them to the sink file.
 */
public class BuilderApiFluentExampleTest {

    @TempDir
    Path tempDir;

    @Test
    public void fluentBuilder_wires_file_and_memory_feeds_to_file_sink() throws Exception {
        // Prepare temp input and output files
        Path inputFile = tempDir.resolve("input").resolve("events.txt");
        Path outputFile = tempDir.resolve("output").resolve("out.log");
        Files.createDirectories(inputFile.getParent());
        Files.createDirectories(outputFile.getParent());

        // Build the sink
        FileMessageSink fileSink = new FileMessageSink();
        fileSink.setFilename(outputFile.toString());

        // Build feeds
        FileEventSource fileSource = new FileEventSource();
        fileSource.setFilename(inputFile.toString());
        fileSource.setCacheEventLog(true);

        InMemoryEventSource<String> inMemSource = new InMemoryEventSource<>();
        inMemSource.setCacheEventLog(true);

        // Processor group with a custom ObjectEventHandlerNode
        EventProcessorGroupConfig processorGroup = EventProcessorGroupConfig.builder()
                .agentName("processor-agent")
                .put("example-processor", new EventProcessorConfig(new BuilderApiExampleHandler()))
                .build();

        // Event feeds via builder API
        EventFeedConfig<?> fileFeedCfg = EventFeedConfig.builder()
                .instance(fileSource)
                .name("fileFeed")
                .broadcast(true)
                .agent("file-source-agent", new BusySpinIdleStrategy())
                .build();

        EventFeedConfig<?> memFeedCfg = EventFeedConfig.builder()
                .instance(inMemSource)
                .name("inMemFeed")
                .broadcast(true)
                .agent("memory-source-agent", new BusySpinIdleStrategy())
                .build();

        // Register sink using EventSinkConfig and add via MongooseServerConfig builder
        EventSinkConfig<FileMessageSink> sinkCfg = EventSinkConfig.<FileMessageSink>builder()
                .instance(fileSink)
                .name("fileSink")
                .build();

        // Build full MongooseServerConfig
        MongooseServerConfig mongooseServerConfig = MongooseServerConfig.builder()
                .addProcessorGroup(processorGroup)
                .addEventFeed(fileFeedCfg)
                .addEventFeed(memFeedCfg)
                .addEventSink(sinkCfg)
                .build();

        LogRecordListener logListener = rec -> {};
        MongooseServer server = MongooseServer.bootServer(mongooseServerConfig, logListener);

        try {
            // Stimulate sources: write to input file and offer memory events
            Files.writeString(inputFile, "file-1\nfile-2\n", StandardCharsets.UTF_8);

            // Access the in-memory source via registered services
            Map<String, com.telamin.fluxtion.runtime.service.Service<?>> services = server.registeredServices();
            @SuppressWarnings("unchecked")
            InMemoryEventSource<String> registeredMem = (InMemoryEventSource<String>) services.get("inMemFeed").instance();
            registeredMem.offer("mem-1");
            registeredMem.offer("mem-2");

            // Allow agents to process. Spin-wait up to a few seconds for output lines.
            List<String> lines = waitForLines(outputFile, 4, 5, TimeUnit.SECONDS);
            Assertions.assertTrue(lines.containsAll(List.of("file-1", "file-2", "mem-1", "mem-2")),
                    () -> "Missing expected lines in sink: " + lines);
        } finally {
            server.stop();
        }
    }

    private static List<String> waitForLines(Path file, int minLines, long timeout, TimeUnit unit) throws Exception {
        long end = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < end) {
            if (Files.exists(file)) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                if (lines.size() >= minLines) {
                    return lines;
                }
            }
            Thread.sleep(50);
        }
        return Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
    }
}
