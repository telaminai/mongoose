/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.connector.file;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fluxtion.agrona.concurrent.OneToOneConcurrentArrayQueue;
import com.telamin.mongoose.config.ReadStrategy;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused tests for FileEventSource read strategies.
 */
public class FileEventSourceReadStrategyTest {

    @TempDir
    Path tempDir;

    Path dataFile;

    private static class CapturingPublisher extends EventToQueuePublisher<String> {
        final OneToOneConcurrentArrayQueue<Object> q = new OneToOneConcurrentArrayQueue<>(256);
        CapturingPublisher(String name){ super(name); addTargetQueue(q, "out"); }
    }

    private FileEventSource newSource(ReadStrategy strategy, boolean cache) {
        FileEventSource src = new FileEventSource(256);
        src.setFilename(dataFile.toString());
        src.setReadStrategy(strategy);
        src.setCacheEventLog(cache); // configurable cache behavior
        CapturingPublisher pub = new CapturingPublisher("fileEventFeed");
        src.setOutput(pub);
        return src;
    }

    private static List<String> drainStrings(CapturingPublisher pub) {
        ArrayList<Object> out = new ArrayList<>();
        pub.q.drainTo(out, 1024);
        return out.stream().map(Object::toString).collect(Collectors.toList());
    }

    @BeforeEach
    void setUp() throws IOException {
        dataFile = tempDir.resolve("events.txt");
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(Path.of(dataFile.toString()+".readPointer"));
        Files.deleteIfExists(dataFile);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void earliest_reads_from_start_and_tails(boolean cache) throws Exception {
        Files.writeString(dataFile, "a1\na2\n", StandardCharsets.UTF_8);
        FileEventSource src = newSource(ReadStrategy.EARLIEST, cache);
        CapturingPublisher pub = new CapturingPublisher("fileEventFeed");
        src.setOutput(pub);

        src.onStart();
        src.start();
        src.startComplete();
        src.doWork();
        assertEquals(List.of("a1","a2"), drainStrings(pub));

        Files.writeString(dataFile, "a3\na4\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        src.doWork();
        assertEquals(List.of("a3","a4"), drainStrings(pub));
        src.stop();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void committed_persists_pointer_between_runs(boolean cache) throws Exception {
        // run1
        Files.writeString(dataFile, "c1\nc2\n", StandardCharsets.UTF_8);
        FileEventSource src1 = newSource(ReadStrategy.COMMITED, cache);
        CapturingPublisher pub1 = new CapturingPublisher("fileEventFeed");
        src1.setOutput(pub1);
        src1.onStart(); src1.start(); src1.startComplete(); src1.doWork();
        assertEquals(List.of("c1","c2"), drainStrings(pub1));
        Files.writeString(dataFile, "c3\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        src1.doWork();
        assertEquals(List.of("c3"), drainStrings(pub1));
        src1.stop();

        // run2 resumes at pointer, only new
        FileEventSource src2 = newSource(ReadStrategy.COMMITED, cache);
        CapturingPublisher pub2 = new CapturingPublisher("fileEventFeed");
        src2.setOutput(pub2);
        src2.onStart(); src2.start(); src2.startComplete(); src2.doWork();
        assertEquals(List.of(), drainStrings(pub2));
        Files.writeString(dataFile, "c4\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        src2.doWork();
        assertEquals(List.of("c4"), drainStrings(pub2));
        src2.stop();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void latest_starts_at_eof_and_only_emits_new_lines(boolean cache) throws Exception {
        Files.writeString(dataFile, "l1\nl2\n", StandardCharsets.UTF_8);
        FileEventSource src = newSource(ReadStrategy.LATEST, cache);
        CapturingPublisher pub = new CapturingPublisher("fileEventFeed");
        src.setOutput(pub);
        src.onStart(); src.start(); src.startComplete(); src.doWork();
        // Should not replay existing
        assertEquals(List.of(), drainStrings(pub));
        // Append new
        Files.writeString(dataFile, "l3\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        src.doWork();
        assertEquals(List.of("l3"), drainStrings(pub));
        src.stop();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void once_earliest_reads_existing_then_stops(boolean cache) throws Exception {
        Files.writeString(dataFile, "o1\no2\n", StandardCharsets.UTF_8);
        FileEventSource src = newSource(ReadStrategy.ONCE_EARLIEST, cache);
        CapturingPublisher pub = new CapturingPublisher("fileEventFeed");
        src.setOutput(pub);
        src.onStart(); src.start(); src.startComplete(); src.doWork();
        assertEquals(List.of("o1","o2"), drainStrings(pub));
        // Append should not be read because once
        Files.writeString(dataFile, "o3\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        src.doWork();
        assertEquals(List.of(), drainStrings(pub));
        src.stop();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void once_latest_emits_only_new_if_any_then_stops(boolean cache) throws Exception {
        Files.writeString(dataFile, "x1\nx2\n", StandardCharsets.UTF_8);
        FileEventSource src = newSource(ReadStrategy.ONCE_LATEST, cache);
        CapturingPublisher pub = new CapturingPublisher("fileEventFeed");
        src.setOutput(pub);
        src.onStart(); src.start(); src.startComplete(); src.doWork();
        // No emission at start since start at EOF
        assertEquals(List.of(), drainStrings(pub));
        Files.writeString(dataFile, "x3\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        src.doWork();
        // ONCE_LATEST should not publish the new appended line exactly once
        assertEquals(List.of(), drainStrings(pub));
        // Further appends should not be tailed due to once
        Files.writeString(dataFile, "x4\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        src.doWork();
        assertEquals(List.of(), drainStrings(pub));
        src.stop();
    }
}
