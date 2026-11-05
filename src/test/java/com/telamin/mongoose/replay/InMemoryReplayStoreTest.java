package com.telamin.mongoose.replay;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class InMemoryReplayStoreTest {
    @Test
    void snapshotsRemainOrderedAndImmutableDuringConcurrentAppends() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        var halfWritten = new CountDownLatch(2);
        var continueWriting = new CountDownLatch(1);
        var writers = Executors.newFixedThreadPool(2);
        try {
            var first = writers.submit(() -> append(store, "first", halfWritten, continueWriting));
            var second = writers.submit(() -> append(store, "second", halfWritten, continueWriting));
            assertTrue(halfWritten.await(5, TimeUnit.SECONDS));
            List<ReplayEntry> before = store.entries("p");
            assertEquals(2000, before.size());
            assertThrows(UnsupportedOperationException.class, () -> before.add(new ReplayEntry.Stopped(0, List.of())));
            assertOrderedPrefix(before);
            continueWriting.countDown();
            do {
                assertOrderedPrefix(store.entries("p"));
            } while (!first.isDone() || !second.isDone());
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertEquals(2000, before.size(), "a snapshot must not grow with its source");
            assertOrderedPrefix(before);
            List<ReplayEntry> complete = store.entries("p");
            assertEquals(4000, complete.size());
            assertOrderedPrefix(complete);
            assertTrue(store.has("p"));
            assertTrue(store.holdsRecording());
            assertEquals(List.of(), store.entries("missing"));
        } finally {
            continueWriting.countDown();
            writers.shutdownNow();
            assertTrue(writers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void append(InMemoryReplayStore store, String source, CountDownLatch half, CountDownLatch resume) {
        for (int seq = 0; seq < 2000; seq++) {
            if (seq == 1000) {
                half.countDown();
                try { resume.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
            store.append("p", new ReplayEntry.Indexed(source, "", seq, seq, List.of()));
        }
    }

    private static void assertOrderedPrefix(List<ReplayEntry> snapshot) {
        long first = 0, second = 0;
        for (ReplayEntry entry : snapshot) {
            ReplayEntry.Indexed indexed = (ReplayEntry.Indexed) entry;
            long expected = indexed.source().equals("first") ? first++ : second++;
            assertEquals(expected, indexed.seq(), "each concurrent writer's entries remain ordered without gaps/duplicates");
        }
    }

    @Test
    void appendAllocationDoesNotCopyTheExistingHistory() {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertTrue(bean.isThreadAllocatedMemorySupported(), "JVM must support allocation measurement");
        bean.setThreadAllocatedMemoryEnabled(true);
        ReplayEntry entry = new ReplayEntry.Stopped(0, List.of());
        InMemoryReplayStore warmup = new InMemoryReplayStore();
        for (int i = 0; i < 5000; i++) warmup.append("warmup", entry);
        InMemoryReplayStore store = new InMemoryReplayStore();
        int count = 10000;
        long thread = Thread.currentThread().threadId();
        long before = bean.getThreadAllocatedBytes(thread);
        for (int i = 0; i < count; i++) store.append("p", entry);
        long allocated = bean.getThreadAllocatedBytes(thread) - before;
        System.out.println("in-memory replay append: entries=" + count + " allocatedBytes=" + allocated);
        assertEquals(count, store.entries("p").size());
        // Generous linear budget for list growth and bookkeeping. Copying history on every append uses about 200 MB
        // for this input on compressed-oop JVMs; this checks allocation rather than machine-dependent elapsed time.
        assertTrue(allocated < count * 128L, "append copied its history: " + allocated + " bytes for " + count + " entries");
    }
}
