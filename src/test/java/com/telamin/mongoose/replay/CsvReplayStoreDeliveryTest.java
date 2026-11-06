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
        Files.write(file, lines.subList(0, lines.size() - 1), StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        try (CsvReplayStore store = open(file)) {
            assertEquals(List.of(), store.entries("p"));
        }
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
