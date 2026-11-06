/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.replay;

import com.telamin.mongoose.service.delivery.Delivery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent review R3: an input's delivery metadata is stored with it in a CSV replay store (a DELIVERY line just
 * before the input's line), read back onto that input, and absent for an input that had none or for a file written
 * before it was recorded.
 */
class CsvReplayStoreDeliveryTest {

    @TempDir
    Path dir;

    private CsvReplayStore open(Path file) {
        return new CsvReplayStore(file, new JavaSerializationCodec());
    }

    @Test
    void aDeliveryIsReadBackOntoItsInput() {
        Path file = dir.resolve("store.csv");
        Delivery d1 = new Delivery("lines", 1, -42L, 6L);
        Delivery d2 = new Delivery("lines", 2, -42L, 8L);
        List<ReplayEntry> written = List.of(
                new ReplayEntry.Indexed("lines", "", 1, 100, List.of(100L), d1),
                new ReplayEntry.Inline("lines", "", "d", -1, 101, List.of(101L), d2),
                new ReplayEntry.Inline("other", "", "x", -1, 102, List.of(102L)),
                new ReplayEntry.TimerFired(1, 103, List.of(103L)));
        try (CsvReplayStore store = open(file)) {
            written.forEach(e -> store.append("p", e));
        }
        try (CsvReplayStore store = open(file)) {
            assertEquals(written, store.entries("p"));
            assertNull(((ReplayEntry.Inline) store.entries("p").get(2)).delivery());
        }
    }

    /** A DELIVERY line whose input line was never written (the write did not complete) adds nothing. */
    @Test
    void aDeliveryWithNoInputAfterItAddsNothing() throws Exception {
        Path file = dir.resolve("store.csv");
        try (CsvReplayStore store = open(file)) {
            store.append("p", new ReplayEntry.Indexed("lines", "", 1, 100, List.of(100L), new Delivery("lines", 1, 7, 2L)));
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertTrue(lines.get(lines.size() - 2).startsWith("p,DELIVERY,"), "the fixture holds a DELIVERY row: " + lines);
        Files.write(file, lines.subList(0, lines.size() - 1), StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        try (CsvReplayStore store = open(file)) {
            assertEquals(List.of(), store.entries("p"));
        }
    }

    /** A store whose last write was a DELIVERY row without its input: a crash between the two lines of one append. */
    private Path interruptedAfterTheDeliveryRow() throws Exception {
        Path file = dir.resolve("replay.csv");
        try (CsvReplayStore store = open(file)) {
            store.append("p", new ReplayEntry.Indexed("old", "", 1, 100, List.of(), new Delivery("old", 1, 7, 2L)));
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertTrue(lines.get(lines.size() - 2).startsWith("p,DELIVERY,"), "the fixture holds a DELIVERY row: " + lines);
        Files.write(file, lines.subList(0, lines.size() - 1), StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        return file;
    }

    /**
     * Independent delta review of PR #98, finding 3 (the reviewer's IndependentDeltaCsvTest): reopened for append, a
     * store with an orphan DELIVERY row gave it to the next input appended for that processor, so an unpositioned input
     * replayed with the interrupted input's feed, epoch and position. Either refused or never inherited.
     */
    @Test
    void anInterruptedDeliveryCannotAttachToALaterUnpositionedInput() throws Exception {
        Path file = interruptedAfterTheDeliveryRow();
        try (CsvReplayStore store = open(file)) {
            assertTrue(store.entries("p").isEmpty());
            try {
                store.append("p", new ReplayEntry.Inline("new", "", "later", -1, 200, List.of()));
            } catch (IllegalStateException safeRefusal) {
                return;
            }
        }
        try (CsvReplayStore store = open(file)) {
            ReplayEntry.Inline input = (ReplayEntry.Inline) store.entries("p").getFirst();
            assertNull(input.delivery(), "a later input must never inherit the interrupted input's delivery");
        }
    }

    /** The fix: an orphan DELIVERY row is an incomplete recording, as a torn line is: read, never appended to. */
    @Test
    void aStoreWithAnOrphanDeliveryRowIsReadButNeverAppendedTo() throws Exception {
        Path file = interruptedAfterTheDeliveryRow();
        long size = Files.size(file);
        try (CsvReplayStore store = open(file)) {
            assertTrue(store.holdsRecording(), "an interrupted recording is a recording: never reused as empty");
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> store.append("p", new ReplayEntry.Inline("new", "", "later", -1, 200, List.of())));
            assertTrue(refused.getMessage().contains("DELIVERY"), refused.getMessage());
            assertThrows(IllegalStateException.class, () -> store.append("q",
                    new ReplayEntry.Indexed("lines", "", 1, 100, List.of(), new Delivery("lines", 1, 7, 2L))),
                    "nor for another processor");
            assertThrows(IllegalStateException.class,
                    () -> store.began("q", new Provenance("C", "H", 1, 300)));
        }
        assertEquals(size, Files.size(file), "nothing written");
    }

    /** A recording written before delivery metadata was recorded holds none: replayed with no DeliveryContext. */
    @Test
    void aStoreWrittenWithoutDeliveriesReadsNone() throws Exception {
        Path file = dir.resolve("store.csv");
        Files.writeString(file, CsvReplayStore.HEADER + "\np,INDEXED,lines,,1,100,,100\n", StandardCharsets.UTF_8);
        try (CsvReplayStore store = open(file)) {
            ReplayEntry.Indexed entry = (ReplayEntry.Indexed) store.entries("p").get(0);
            assertNull(entry.delivery());
        }
    }
}
