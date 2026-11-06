/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.connector.file;

import com.telamin.mongoose.config.ReadStrategy;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import com.telamin.mongoose.service.feedstatus.FeedState;
import com.telamin.mongoose.service.feedstatus.FeedStatus;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FileEventSource feed status transitions per read strategy (docs/design/feed-caught-up.md): the state reported, the
 * sequence it is reported at, and the queue position the host compares a reader's dispatched count with.
 */
class FileEventSourceFeedStatusTest {

    @TempDir
    Path tempDir;
    Path dataFile;
    OneToOneConcurrentArrayQueue<Object> queue;
    EventToQueuePublisher<String> publisher;

    @BeforeEach
    void setUp() {
        dataFile = tempDir.resolve("events.txt");
        queue = new OneToOneConcurrentArrayQueue<>(256);
        publisher = new EventToQueuePublisher<>("fileFeed");
        publisher.addTargetQueue(queue, "out");
    }

    private FileEventSource source(ReadStrategy strategy, boolean cache) {
        FileEventSource src = new FileEventSource(256);
        src.setFilename(dataFile.toString());
        src.setReadStrategy(strategy);
        src.setCacheEventLog(cache);
        src.setOutput(publisher);
        return src;
    }

    private FeedStatus status() {
        return publisher.feedPosition().status();
    }

    private long position() {
        return publisher.feedPosition().positionOf(queue);
    }

    private List<Object> drain() {
        List<Object> out = new ArrayList<>();
        queue.drainTo(out, 1024);
        // a COMMITED feed's items carry their source position in the queue (docs/design/feed-delivery.md, 5a)
        out.replaceAll(o -> o instanceof com.telamin.mongoose.dispatch.PositionedItem p ? p.item() : o);
        return out;
    }

    private void append(String text) throws Exception {
        Files.writeString(dataFile, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void assertStatus(FeedStatus s, FeedState state, long sequence, boolean bounded) {
        assertEquals(state, s.state(), "state: " + s);
        assertEquals(sequence, s.sequence(), "sequence: " + s);
        assertEquals(bounded, s.bounded(), "bounded: " + s);
        assertEquals("fileFeed", s.feedName());
        assertEquals("fileFeed", s.filterString(), "a node filters on the feed name");
    }

    @Test
    void aSourceThatNeverReportsIsUnknown() {
        assertStatus(status(), FeedState.UNKNOWN, 0, false);
    }

    /** The tail case: CAUGHT_UP, then BEHIND when the file grows, then CAUGHT_UP at the new end. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void tailedFileGoesCaughtUpBehindCaughtUp(boolean cache) throws Exception {
        append("a1\na2\n");
        FileEventSource src = source(ReadStrategy.EARLIEST, cache);
        src.onStart();
        src.start();
        assertEquals(FeedState.BEHIND, status().state(), "nothing is caught up before the start completes");
        src.startComplete();
        src.doWork();
        assertStatus(status(), FeedState.CAUGHT_UP, 2, false);
        assertEquals(2, position(), "the queue had been given both lines when CAUGHT_UP was reported");
        long version = publisher.feedPosition().version();

        src.doWork();
        src.doWork();
        assertEquals(version, publisher.feedPosition().version(), "an idle poll reports nothing");

        append("a3\n");
        src.doWork();
        assertStatus(status(), FeedState.CAUGHT_UP, 3, false);
        assertEquals(3, position());
        assertTrue(publisher.feedPosition().version() >= version + 2, "BEHIND then CAUGHT_UP were reported");
        assertEquals(List.of("a1", "a2", "a3"), drain(), "no feed status in the queue: delivery is off by default");
        src.stop();
    }

    @Test
    void tailedGrowthReportsBehindInBetween() throws Exception {
        append("b1\n");
        publisher.setDeliverFeedStatus(true);
        FileEventSource src = source(ReadStrategy.EARLIEST, false);
        src.onStart();
        src.start();
        src.startComplete();
        src.doWork();
        append("b2\nb3\n");
        src.doWork();
        List<String> shape = new ArrayList<>();
        for (Object o : drain()) {
            shape.add(o instanceof FeedStatus s ? s.state() + "@" + s.sequence() : String.valueOf(o));
        }
        assertEquals(List.of("BEHIND@0", "b1", "CAUGHT_UP@1", "BEHIND@1", "b2", "b3", "CAUGHT_UP@3"), shape,
                "delivered in order with the lines: the status at start completion first");
        src.stop();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void committedResumesAndIsCaughtUpAtItsPointer(boolean cache) throws Exception {
        append("c1\nc2\n");
        FileEventSource first = source(ReadStrategy.COMMITED, cache);
        first.onStart(); first.start(); first.startComplete(); first.doWork();
        assertStatus(status(), FeedState.CAUGHT_UP, 2, false);
        // both lines processed (taken from the queue): the stop commits them (docs/design/feed-delivery.md)
        assertEquals(List.of("c1", "c2"), drain());
        first.stop();

        setUp();
        FileEventSource second = source(ReadStrategy.COMMITED, cache);
        second.onStart(); second.start(); second.startComplete(); second.doWork();
        assertStatus(status(), FeedState.CAUGHT_UP, 0, false);
        append("c3\n");
        second.doWork();
        assertStatus(status(), FeedState.CAUGHT_UP, 1, false);
        assertEquals(List.of("c3"), drain());
        second.stop();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void latestIsCaughtUpAtTheEndItStartedFrom(boolean cache) throws Exception {
        append("l1\nl2\n");
        FileEventSource src = source(ReadStrategy.LATEST, cache);
        src.onStart(); src.start(); src.startComplete(); src.doWork();
        assertStatus(status(), FeedState.CAUGHT_UP, 0, false);
        append("l3\n");
        src.doWork();
        assertStatus(status(), FeedState.CAUGHT_UP, 1, false);
        src.stop();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void onceEarliestEndsAfterTheLastLine(boolean cache) throws Exception {
        append("o1\no2\n");
        FileEventSource src = source(ReadStrategy.ONCE_EARLIEST, cache);
        src.onStart(); src.start(); src.startComplete(); src.doWork();
        assertStatus(status(), FeedState.END_OF_STREAM, 2, true);
        assertEquals(2, position(), "with the cache, the position counts the replayed lines");
        assertFalse(publisher.feedPosition().cachedPending());
        long version = publisher.feedPosition().version();
        append("o3\n");
        src.doWork();
        assertStatus(status(), FeedState.END_OF_STREAM, 2, true);
        assertEquals(version, publisher.feedPosition().version(), "END_OF_STREAM is final");
        assertEquals(List.of("o1", "o2"), drain());
        src.stop();
    }

    @Test
    void onceEarliestOnAnEmptyFileIsCaughtUpUntilItHasData() throws Exception {
        append("");
        FileEventSource src = source(ReadStrategy.ONCE_EARLIEST, false);
        src.onStart(); src.start(); src.startComplete(); src.doWork();
        assertStatus(status(), FeedState.CAUGHT_UP, 0, true);
        append("e1\n");
        src.doWork();
        assertStatus(status(), FeedState.END_OF_STREAM, 1, true);
        src.stop();
    }

    @Test
    void onceLatestEndsAtOnce() throws Exception {
        append("x1\n");
        FileEventSource src = source(ReadStrategy.ONCE_LATEST, false);
        src.onStart(); src.start(); src.startComplete(); src.doWork();
        assertStatus(status(), FeedState.END_OF_STREAM, 0, true);
        src.stop();
    }

    @Test
    void aMissingFileIsCaughtUpWithAReasonThenReadWhenItAppears() throws Exception {
        FileEventSource src = source(ReadStrategy.EARLIEST, false);
        src.onStart(); src.start(); src.startComplete(); src.doWork();
        assertStatus(status(), FeedState.CAUGHT_UP, 0, false);
        assertTrue(status().detail().startsWith("file not found"), status().detail());
        append("m1\n");
        src.doWork();
        assertStatus(status(), FeedState.CAUGHT_UP, 1, false);
        assertNull(status().detail());
        src.stop();
    }

    @Test
    void anUnterminatedLastLineIsNamed() throws Exception {
        append("p1\np2");
        FileEventSource src = source(ReadStrategy.EARLIEST, false);
        src.onStart(); src.start(); src.startComplete(); src.doWork();
        assertStatus(status(), FeedState.CAUGHT_UP, 1, false);
        assertEquals("partial line pending", status().detail());
        append("\n");
        src.doWork();
        assertStatus(status(), FeedState.CAUGHT_UP, 2, false);
        assertNull(status().detail());
        src.stop();
    }

    @Test
    void cachedLinesAreCountedOnceReplayed() throws Exception {
        append("k1\nk2\nk3\n");
        FileEventSource src = source(ReadStrategy.EARLIEST, true);
        src.onStart();
        src.start();
        assertTrue(publisher.feedPosition().cachedPending(), "cached before start: not drained");
        src.startComplete();
        src.doWork();
        assertStatus(status(), FeedState.CAUGHT_UP, 3, false);
        assertFalse(publisher.feedPosition().cachedPending());
        assertEquals(3, position());
        src.stop();
    }
}
