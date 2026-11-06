/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.delivery;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.OverloadPolicyConfig;
import com.telamin.mongoose.config.ReadStrategy;
import com.telamin.mongoose.connector.file.FileEventSource;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventSourceKey;
import com.telamin.mongoose.service.EventSubscriptionKey;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.admin.AdminCommandRequest;
import com.telamin.mongoose.service.admin.impl.AdminCommandProcessor;
import com.telamin.mongoose.service.feedstatus.FeedStatusReport;
import com.telamin.mongoose.service.servercontrol.MongooseServerAdmin;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Delivery guarantees on a running server (docs/design/feed-delivery.md): a slow processor and a file far larger than
 * its queue lose nothing by default (the file feed pauses), and {@code feed.status} settles only after every line; an
 * explicit DROP_AFTER_SPIN policy drops and says so ({@code lossless:false}); a crash mid-burst loses no line (the
 * pointer never runs ahead of processing; re-delivered lines are counted); a processing checkpoint holds the commit.
 */
class FeedDeliveryServerTest {

    static final String FEED = "lines";
    static final String GROUP = "g";

    @TempDir
    Path tempDir;
    MongooseServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }

    /** Handles each line slowly: parks {@code parkNanos} per line. */
    public static class SlowProcessor implements DataFlow {
        final long parkNanos;
        final List<String> lines = new CopyOnWriteArrayList<>();
        final List<EventFeed> feeds = new ArrayList<>();

        SlowProcessor(long parkNanos) {
            this.parkNanos = parkNanos;
        }

        @Override public void init() {}

        @Override
        public void start() {
            for (EventFeed f : feeds) {
                f.subscribe(this, new EventSubscriptionKey<>(new EventSourceKey<>(FEED), CallBackType.ON_EVENT_CALL_BACK));
            }
        }

        @Override public void tearDown() {}

        @Override public void addEventFeed(EventFeed f) { feeds.add(f); }

        @Override
        public void onEvent(Object e) {
            if (e instanceof String line) {
                if (parkNanos > 0) LockSupport.parkNanos(parkNanos);
                lines.add(line);
            }
        }
    }

    private Path data() {
        return tempDir.resolve("lines.txt");
    }

    private void write(int from, int to) throws Exception {
        StringBuilder b = new StringBuilder();
        for (int i = from; i <= to; i++) b.append("line-").append(i).append('\n');
        Files.writeString(data(), b, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static long offsetAfter(int n) {
        long off = 0;
        for (int i = 1; i <= n; i++) off += ("line-" + i + "\n").length();
        return off;
    }

    private long pointerOnDisk() throws Exception {
        Path pointer = Path.of(data() + ".readPointer");
        return Files.exists(pointer) ? ByteBuffer.wrap(Files.readAllBytes(pointer), 0, 8).getLong() : 0;
    }

    private MongooseServer boot(SlowProcessor processor, OverloadPolicyConfig overload, AdminCommandProcessor admin) {
        FileEventSource src = new FileEventSource();
        src.setFilename(data().toString());
        src.setReadStrategy(ReadStrategy.COMMITED);
        MongooseServerConfig cfg = new MongooseServerConfig();
        if (admin != null) {
            cfg.addService(admin, AdminCommandRegistry.class, "adminRegistry");
            cfg.addService(new MongooseServerAdmin(), "serverAdmin");
        }
        cfg.addProcessor(GROUP, processor, "slow");
        cfg.addEventSourceWorker(src, FEED, true, "feed-agent", new SleepingMillisIdleStrategy(1));
        if (overload != null) cfg.getEventFeeds().get(0).setOverload(overload);
        return MongooseServer.bootServer(cfg);
    }

    static void await(BooleanSupplier c, String what, long seconds) throws Exception {
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        while (System.nanoTime() < end && !c.getAsBoolean()) Thread.sleep(2);
        assertTrue(c.getAsBoolean(), "timed out waiting for " + what);
    }

    static String command(AdminCommandRegistry admin, String name, String... args) {
        List<String> replies = new CopyOnWriteArrayList<>();
        AdminCommandRequest request = new AdminCommandRequest();
        request.setCommand(name);
        request.setArguments(List.of(args));
        request.setOutput(o -> replies.add(String.valueOf(o)));
        request.setErrOutput(e -> replies.add("ERROR: " + e));
        admin.processAdminCommandRequest(request);
        return String.join("\n", replies);
    }

    /** Default configuration: the file feed pauses, nothing is dropped, settled only after every line. */
    @Test
    void byDefaultASlowProcessorAndALargeFileLoseNothingAndSettleOnlyAtTheEnd() throws Exception {
        int total = 10_000;
        write(1, total);
        SlowProcessor processor = new SlowProcessor(20_000);
        AdminCommandProcessor admin = new AdminCommandProcessor();
        server = boot(processor, null, admin);

        long end = System.nanoTime() + 60_000_000_000L;
        boolean sawPause = false;
        while (System.nanoTime() < end) {
            FeedStatusReport report = server.feedStatus(FEED);
            FeedStatusReport.Feed feed = report.feed(FEED);
            sawPause |= feed.delivery().pauses() > 0;
            if (report.settled()) {
                assertEquals(total, processor.lines.size(), "settled only once every line was processed: " + report);
                break;
            }
            Thread.sleep(5);
        }
        FeedStatusReport report = server.feedStatus(FEED);
        assertTrue(report.settled(), report.toString());
        assertTrue(report.lossless(), report.toString());
        FeedStatusReport.Feed feed = report.feed(FEED);
        assertEquals("PAUSE", feed.delivery().policy(), "a file feed without a policy pauses");
        assertEquals(0, feed.delivery().dropped(), report.toString());
        assertTrue(sawPause || feed.delivery().pauses() > 0, "the 1024-slot queue filled and the source paused");
        for (int i = 0; i < total; i++) assertEquals("line-" + (i + 1), processor.lines.get(i));
        await(() -> server.feedStatus(FEED).feed(FEED).delivery().acknowledged() == total, "all acknowledged", 10);
        await(() -> {
            try {
                return pointerOnDisk() == offsetAfter(total);
            } catch (Exception e) {
                return false;
            }
        }, "the pointer at the end of the file", 10);
        String json = command(admin, "feed.status", "--json", FEED);
        assertTrue(json.contains("\"lossless\":true") && json.contains("\"dropped\":0") && json.contains("\"policy\":\"PAUSE\""), json);
    }

    /** Explicit DROP_AFTER_SPIN: the feed drops, and the report says the run is not lossless although it settles. */
    @Test
    void anExplicitDropPolicyDropsAndTheReportSaysSo() throws Exception {
        int total = 10_000;
        write(1, total);
        SlowProcessor processor = new SlowProcessor(20_000);
        AdminCommandProcessor admin = new AdminCommandProcessor();
        server = boot(processor, OverloadPolicyConfig.dropAfterSpin(Duration.ZERO), admin);

        await(() -> server.feedStatus(FEED).settled(), "settled (caught up and drained)", 60);
        FeedStatusReport report = server.feedStatus(FEED);
        FeedStatusReport.Feed feed = report.feed(FEED);
        assertEquals("DROP_AFTER_SPIN", feed.delivery().policy());
        assertTrue(feed.delivery().dropped() > 0, report.toString());
        assertFalse(feed.lossless(), report.toString());
        assertFalse(report.lossless(), "the run is not whole: " + report);
        assertEquals(total, processor.lines.size() + feed.delivery().dropped(), "every line processed or counted dropped");
        String json = command(admin, "feed.status", "--json", FEED);
        assertTrue(json.startsWith("{\"settled\":true,\"lossless\":false,"), json);
        assertTrue(json.contains("\"dropped\":" + feed.delivery().dropped()), json);
    }

    /**
     * A crash mid-burst: the read pointer, sampled while the processor works through a burst, never covers a line the
     * processor has not handled. At a "crash" (the pointer file is all that survives: it is snapshotted, the server
     * stopped, and the snapshot put back) a restart resumes at the snapshot: no line is lost; the lines handled after the
     * last commit are delivered again, and counted.
     */
    @Test
    void aCrashMidBurstLosesNoLine() throws Exception {
        int total = 3_000;
        write(1, 1);
        SlowProcessor first = new SlowProcessor(100_000);
        server = boot(first, null, null);
        await(() -> first.lines.size() == 1, "the first line", 10);
        write(2, total);   // one burst, appended in one write

        byte[] crashPointer = null;
        List<String> handledAtCrash = null;
        long end = System.nanoTime() + 60_000_000_000L;
        int samples = 0;
        while (System.nanoTime() < end) {
            // the pointer first, then what has been handled: handled only grows, so this order is the strict check
            byte[] pointerBytes = Files.readAllBytes(Path.of(data() + ".readPointer"));
            long pointer = ByteBuffer.wrap(pointerBytes, 0, 8).getLong();
            List<String> handled = List.copyOf(first.lines);
            assertTrue(pointer <= offsetAfter(handled.size()), "the pointer (" + pointer + ") is never ahead of the "
                    + handled.size() + " handled lines (" + offsetAfter(handled.size()) + ")");
            samples++;
            if (handled.size() >= total / 2) {
                crashPointer = pointerBytes;
                handledAtCrash = handled;
                break;
            }
            Thread.sleep(3);
        }
        assertNotNull(crashPointer, "the processor reached the middle of the burst");
        assertTrue(samples > 5, "sampled during the burst");
        server.stop();
        server = null;
        Files.write(Path.of(data() + ".readPointer"), crashPointer);   // what a kill -9 at the snapshot leaves
        long resumeAt = ByteBuffer.wrap(crashPointer, 0, 8).getLong();

        SlowProcessor second = new SlowProcessor(0);
        server = boot(second, null, null);
        await(() -> server.feedStatus(FEED).settled() && !second.lines.isEmpty(), "the restart settles", 30);

        Set<String> union = new HashSet<>(handledAtCrash);
        union.addAll(second.lines);
        for (int i = 1; i <= total; i++) assertTrue(union.contains("line-" + i), "line-" + i + " was lost");
        int firstRedelivered = 1;
        while (offsetAfter(firstRedelivered - 1) < resumeAt) firstRedelivered++;
        assertEquals("line-" + firstRedelivered, second.lines.get(0), "the restart resumes at the committed pointer");
        Set<String> duplicates = new HashSet<>(handledAtCrash);
        duplicates.retainAll(second.lines);
        assertEquals(handledAtCrash.size() - (firstRedelivered - 1), duplicates.size(),
                "re-delivered exactly the lines handled after the last commit");
        assertEquals(total - (firstRedelivered - 1), second.lines.size());
        assertTrue(server.feedStatus(FEED).lossless());
        System.out.println("crash test: handled at crash " + handledAtCrash.size() + ", committed through line "
                + (firstRedelivered - 1) + ", re-delivered (duplicates) " + duplicates.size() + ", lost 0");
    }

    /** A processing checkpoint holds the commit until the processor says its state is durable. */
    @Test
    void aProcessingCheckpointHoldsTheCommitUntilStateIsDurable() throws Exception {
        Files.writeString(data(), "", StandardCharsets.UTF_8);
        SlowProcessor processor = new SlowProcessor(0);
        server = boot(processor, null, null);
        ProcessingCheckpoint checkpoint = server.processingCheckpoint(FEED, GROUP);
        write(1, 3);
        await(() -> processor.lines.size() == 3, "3 processed", 10);
        Thread.sleep(20);   // the reader's dispatched store follows the cycle
        ProcessingCheckpoint.Mark mark3 = checkpoint.mark();
        assertEquals(3, mark3.count());
        write(4, 5);
        await(() -> server.feedStatus(FEED).settled() && processor.lines.size() == 5, "processed", 10);
        Thread.sleep(50);   // several duty cycles: nothing may be committed while held
        assertEquals(0, pointerOnDisk(), "held: processed but not durable, so not committed");
        assertEquals(0, server.feedStatus(FEED).feed(FEED).delivery().acknowledged());
        assertEquals(5, server.feedStatus(FEED).feed(FEED).delivery().pendingAcks());

        ProcessingCheckpoint.Mark mark = checkpoint.mark();
        assertEquals(5, mark.count(), "the mark covers the 5 dispatched lines");
        checkpoint.durableThrough(mark3);
        await(() -> server.feedStatus(FEED).feed(FEED).delivery().acknowledged() == 3, "3 acknowledged", 10);
        assertEquals(offsetAfter(3), pointerOnDisk());
        checkpoint.durableThrough(mark);
        await(() -> server.feedStatus(FEED).feed(FEED).delivery().acknowledged() == 5, "5 acknowledged", 10);
        assertEquals(offsetAfter(5), pointerOnDisk());

        checkpoint.release();
        write(6, 7);
        await(() -> server.feedStatus(FEED).feed(FEED).delivery().acknowledged() == 7, "released: dispatch acks", 10);
        assertEquals(offsetAfter(7), pointerOnDisk());
    }
}
