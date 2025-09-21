/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.example;

import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * YAML configuration example mirroring the fluent builder example:
 * - Two event sources (FileEventSource and InMemoryEventSource)
 * - One processor (BuilderApiExampleHandler) that forwards to a sink
 * - One sink (FileMessageSink)
 *
 * The server is booted from a YAML string via MongooseServer.bootServer(Reader,...).
 */
public class YamlConfigFeedsExampleTest {

    @TempDir
    Path tempDir;

    @Test
    public void yaml_wires_file_and_memory_feeds_to_file_sink() throws Exception {
        Path inputFile = tempDir.resolve("input").resolve("events.txt");
        Path outputFile = tempDir.resolve("output").resolve("out.log");
        Files.createDirectories(inputFile.getParent());
        Files.createDirectories(outputFile.getParent());

        String yaml = """
                # --------- EVENT INPUT FEEDS BEGIN CONFIG ---------
                eventFeeds:
                  - instance: !!com.telamin.mongoose.connector.file.FileEventSource
                      filename: %s
                      cacheEventLog: true
                    name: fileFeed
                    agentName: file-source-agent
                    broadcast: true
                    idleStrategy: !!com.fluxtion.agrona.concurrent.BusySpinIdleStrategy { }
                  - instance: !!com.telamin.mongoose.connector.memory.InMemoryEventSource { cacheEventLog: true }
                    name: inMemFeed
                    agentName: memory-source-agent
                    broadcast: true
                    idleStrategy: !!com.fluxtion.agrona.concurrent.BusySpinIdleStrategy { }
                # --------- EVENT INPUT FEEDS END CONFIG ---------

                # --------- EVENT SINKS BEGIN CONFIG ---------
                eventSinks:
                  - instance: !!com.telamin.mongoose.connector.file.FileMessageSink
                      filename: %s
                    name: fileSink
                # --------- EVENT SINKS END CONFIG ---------

                # --------- EVENT HANDLERS BEGIN CONFIG ---------
                eventHandlers:
                  - agentName: processor-agent
                    idleStrategy: !!com.fluxtion.agrona.concurrent.BusySpinIdleStrategy { }
                    eventHandlers:
                      example-processor:
                        customHandler: !!com.telamin.mongoose.example.BuilderApiExampleHandler { }
                        logLevel: INFO
                # --------- EVENT HANDLERS END CONFIG ---------
                """.formatted(escapeYaml(inputFile.toString()), escapeYaml(outputFile.toString()));

        LogRecordListener logListener = rec -> {};
        MongooseServer server = MongooseServer.bootServer(new StringReader(yaml), logListener);

        try {
            // Stimulate sources: write to input file and offer memory events
            Files.writeString(inputFile, "file-1\nfile-2\n", StandardCharsets.UTF_8);

            // Access the in-memory source via registered services (by name set in YAML: inMemFeed)
            Map<String, com.telamin.fluxtion.runtime.service.Service<?>> services = server.registeredServices();
            @SuppressWarnings("unchecked")
            InMemoryEventSource<String> registeredMem = (InMemoryEventSource<String>) services.get("inMemFeed").instance();
            registeredMem.offer("mem-1");
            registeredMem.offer("mem-2");

            // Spin-wait for sink output to contain 4 lines
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

    // Basic escaping for backslashes in Windows paths to keep YAML happy
    private static String escapeYaml(String s) {
        return s.replace("\\", "\\\\");
    }
}
