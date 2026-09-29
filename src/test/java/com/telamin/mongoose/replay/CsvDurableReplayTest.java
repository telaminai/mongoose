package com.telamin.mongoose.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sample durable journal and store (CSV): a run recorded into two files, both closed, then opened again from disk
 * as new instances, as another process would, and replayed. The processor does what it did.
 */
class CsvDurableReplayTest {

    @Test
    void aRunRecordedToCsv_isReplayedFromTheFilesAlone(@TempDir Path dir) throws Exception {
        Path journalFile = dir.resolve("journal.csv"), storeFile = dir.resolve("entries.csv");
        JavaSerializationCodec codec = new JavaSerializationCodec();
        List<String> live;
        try (CsvEventJournal journal = new CsvEventJournal(journalFile); CsvReplayStore store = new CsvReplayStore(storeFile, codec)) {
            ReplayConfig record = ReplayConfig.record(Set.of(ReplayRecordingAcceptanceTest.PROCESSOR),
                    Map.of(ReplayRecordingAcceptanceTest.ORDERS, codec), journal, store);
            try (var s = ReplayRecordingAcceptanceTest.boot(record)) {
                s.orders().offer("ord-1");     s.await(1);
                s.controls().offer("suspend"); s.await(2);
                s.orders().offer("ord-2");     s.await(4);
                s.controls().offer("arm");     s.await(5);
                s.await(6);                     // the timeout
                live = s.lines();
            }
        }
        // the files: a header and one line per journalled order; a header and one line per entry
        List<String> journalLines = Files.readAllLines(journalFile, StandardCharsets.UTF_8);
        assertEquals(CsvEventJournal.HEADER, journalLines.get(0));
        assertEquals(3, journalLines.size(), "two orders journalled: " + journalLines);
        assertTrue(journalLines.get(1).startsWith("orders,1,"), journalLines.toString());
        List<String> storeLines = Files.readAllLines(storeFile, StandardCharsets.UTF_8);
        assertEquals(CsvReplayStore.HEADER, storeLines.get(0));
        assertEquals(List.of("INDEXED", "INLINE", "INDEXED", "INLINE", "TIMER"),
                storeLines.subList(1, storeLines.size()).stream().map(l -> l.split(",")[1]).toList(), storeLines.toString());

        // another process: new instances, from the files alone
        try (CsvEventJournal journal = new CsvEventJournal(journalFile); CsvReplayStore store = new CsvReplayStore(storeFile, codec)) {
            ReplayConfig replay = ReplayConfig.replay(Set.of(ReplayRecordingAcceptanceTest.PROCESSOR),
                    Map.of(ReplayRecordingAcceptanceTest.ORDERS, codec), journal, store);
            try (var r = ReplayRecordingAcceptanceTest.boot(replay)) {
                r.awaitReplay(live.size());
                assertEquals(live, r.lines(), "replayed from the CSV files, the processor does what it did");
            }
        }
    }
}
