/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.feedstatus;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.EventFeedConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.ReadStrategy;
import com.telamin.mongoose.connector.file.FileEventSource;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventSourceKey;
import com.telamin.mongoose.service.EventSubscriptionKey;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.admin.AdminCommandRequest;
import com.telamin.mongoose.service.admin.impl.AdminCommandProcessor;
import com.telamin.mongoose.service.extension.AbstractAgentHostedEventSourceService;
import com.telamin.mongoose.service.servercontrol.MongooseServerAdmin;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The host's feed status query on a running server (docs/design/feed-caught-up.md): settled only once the processor has
 * handled the last line, a tail that grows settles again, a source that cannot tell is a fallback feed, and a processor
 * whose feed does not ask for feed status events receives none.
 */
class FeedStatusServerTest {

    @TempDir
    Path tempDir;
    MongooseServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }

    /** Records what it receives; optionally blocks on one line until its gate opens. */
    public static class Probe implements DataFlow {
        final String feed;
        final String blockOn;
        final CountDownLatch gate = new CountDownLatch(1);
        final CountDownLatch blocked = new CountDownLatch(1);
        final AtomicInteger lines = new AtomicInteger();
        final List<Object> received = new CopyOnWriteArrayList<>();
        final List<EventFeed> feeds = new ArrayList<>();

        Probe(String feed, String blockOn) {
            this.feed = feed;
            this.blockOn = blockOn;
        }

        @Override public void init() {}

        @Override
        public void start() {
            for (EventFeed f : feeds) {
                f.subscribe(this, new EventSubscriptionKey<>(new EventSourceKey<>(feed), CallBackType.ON_EVENT_CALL_BACK));
            }
        }

        @Override public void tearDown() {}

        @Override public void addEventFeed(EventFeed f) { feeds.add(f); }

        @Override
        public void onEvent(Object e) {
            if (e instanceof String line) {
                if (line.equals(blockOn)) {
                    blocked.countDown();
                    try {
                        if (!gate.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("gate never opened");
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
                received.add(e);
                lines.incrementAndGet();
            } else if (e instanceof FeedStatus) {
                received.add(e);
            } else {
                received.add(e);   // the server's own events (log control): kept, to prove no FeedStatus is among them
            }
        }

        List<String> shape() {
            List<String> out = new ArrayList<>();
            for (Object o : received) {
                if (o instanceof String s) out.add(s);
                else if (o instanceof FeedStatus s) out.add(s.state() + "@" + s.sequence());
            }
            return out;
        }
    }

    /** A source that never reports feed status, as every connector written before it. */
    public static class SilentSource extends AbstractAgentHostedEventSourceService<String> {
        public SilentSource() { super("silent"); }
        @Override public int doWork() { return 0; }
    }

    private Path file(String name, String text) throws Exception {
        Path p = tempDir.resolve(name);
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p;
    }

    private static FileEventSource fileSource(Path p, ReadStrategy strategy) {
        FileEventSource src = new FileEventSource();
        src.setFilename(p.toString());
        src.setReadStrategy(strategy);
        return src;
    }

    private static AdminCommandProcessor withAdmin(MongooseServerConfig cfg) {
        AdminCommandProcessor admin = new AdminCommandProcessor();
        cfg.addService(admin, AdminCommandRegistry.class, "adminRegistry");
        cfg.addService(new MongooseServerAdmin(), "serverAdmin");
        return admin;
    }

    static void await(BooleanSupplier c, String what) throws Exception {
        long end = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < end && !c.getAsBoolean()) Thread.sleep(5);
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

    @Test
    void settledOnlyAfterTheProcessorHandledTheLastLineAndAgainAfterTheTailGrows() throws Exception {
        Path data = file("lines.txt", "l1\nl2\nl3\n");
        Probe processor = new Probe("lines", "l3");
        MongooseServerConfig cfg = new MongooseServerConfig();
        AdminCommandProcessor admin = withAdmin(cfg);
        cfg.addProcessor("g", processor, "probe");
        cfg.addEventSourceWorker(fileSource(data, ReadStrategy.EARLIEST), "lines", true, "feed-agent",
                new SleepingMillisIdleStrategy(1));
        server = MongooseServer.bootServer(cfg);

        assertTrue(processor.blocked.await(10, TimeUnit.SECONDS), "the processor took the last line and blocks");
        await(() -> server.feedStatus("lines").feed("lines").state() == FeedState.CAUGHT_UP, "CAUGHT_UP");
        FeedStatusReport.Feed lines = server.feedStatus("lines").feed("lines");
        assertEquals(3, lines.sequence(), "caught up at the last line");
        assertFalse(lines.bounded(), "a tailed file is unbounded");
        assertFalse(lines.drained(), "the last line is still being dispatched: " + lines);
        assertFalse(server.feedStatus("lines").settled());
        assertEquals(1, lines.subscribers().size(), lines.toString());
        assertEquals(3, lines.subscribers().get(0).position());
        assertEquals(2, lines.subscribers().get(0).dispatched(), "two lines handled, the third in flight");
        assertTrue(command(admin, "feed.status", "--json", "lines").contains("\"settled\":false"));

        processor.gate.countDown();
        await(() -> server.feedStatus("lines").settled(), "settled");
        assertEquals(3, processor.lines.get(), "settled implies the processor handled every line");
        String json = command(admin, "feed.status", "--json", "lines");
        assertTrue(json.startsWith("{\"settled\":true,\"lossless\":true,\"fallbackFeeds\":[],\"feeds\":[{\"feed\":"
                + "\"lines\",\"state\":\"CAUGHT_UP\",\"bounded\":false,\"sequence\":3,\"version\":2,\"detail\":null,"
                + "\"drained\":true,\"settled\":true,\"lossless\":true,\"policy\":\"PAUSE\",\"dropped\":0,\"refused\":0,"
                + "\"downgraded\":0,\"paused\":false,\"pauses\":0,\"acknowledged\":0,\"pendingAcks\":0,"
                + "\"consumers\":1,\"stalled\":null,\"unterminated\":0,\"subscribers\":[{\"queue\":\"g/lines/onEventCallBack\",\"position\":3,\"dispatched\":3,"
                + "\"drained\":true}]}"), json);
        assertTrue(server.feedStatus().settled(), "every feed of the server settled: " + server.feedStatus());

        Files.writeString(data, "l4\nl5\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        await(() -> {
            FeedStatusReport.Feed f = server.feedStatus("lines").feed("lines");
            return f.sequence() == 5 && f.settled();
        }, "settled again at the new end");
        assertEquals(5, processor.lines.get());
        assertTrue(command(admin, "feed.status", "nosuchfeed").startsWith("ERROR: no such feed: nosuchfeed"));
    }

    @Test
    void aBoundedFileEndsAndASilentSourceIsAFallbackFeed() throws Exception {
        Path data = file("once.txt", "o1\no2\nend");
        Probe processor = new Probe("once", null);
        MongooseServerConfig cfg = new MongooseServerConfig();
        cfg.addProcessor("g", processor, "probe");
        cfg.addEventSourceWorker(fileSource(data, ReadStrategy.ONCE_EARLIEST), "once", true, "feed-agent",
                new SleepingMillisIdleStrategy(1));
        cfg.addEventSourceWorker(new SilentSource(), "silent", true, "silent-agent", new SleepingMillisIdleStrategy(1));
        server = MongooseServer.bootServer(cfg);

        await(() -> server.feedStatus("once", "silent").settled(), "settled");
        FeedStatusReport report = server.feedStatus("once", "silent");
        assertEquals(FeedState.END_OF_STREAM, report.feed("once").state());
        assertTrue(report.feed("once").bounded());
        assertEquals(2, report.feed("once").sequence());
        assertEquals(2, processor.lines.get());
        // the unterminated "end" is not an item: settled and lossless, but counted (review 2, finding 9)
        assertEquals(3, report.feed("once").delivery().unterminated());
        assertTrue(report.lossless());
        assertTrue(report.toJson().contains("\"unterminated\":3"), report.toJson());
        assertEquals(FeedState.UNKNOWN, report.feed("silent").state());
        assertEquals(List.of("silent"), report.fallbackFeeds(), "the caller falls back to its heuristic for this feed only");
    }

    @Test
    void anInMemoryFeedIsSettledOnceWhatWasOfferedIsDispatched() throws Exception {
        InMemoryEventSource<String> feed = new InMemoryEventSource<>();
        Probe processor = new Probe("mem", "m3");
        MongooseServerConfig cfg = new MongooseServerConfig();
        cfg.addProcessor("g", processor, "probe");
        cfg.addEventSourceWorker(feed, "mem", true, "feed-agent", new SleepingMillisIdleStrategy(1));
        server = MongooseServer.bootServer(cfg);
        await(() -> server.feedStatus("mem").settled(), "settled with nothing offered");

        feed.offer("m1");
        feed.offer("m2");
        feed.offer("m3");
        assertFalse(server.feedStatus("mem").settled(), "offered and not yet dispatched");
        assertTrue(processor.blocked.await(10, TimeUnit.SECONDS));
        FeedStatusReport.Feed mem = server.feedStatus("mem").feed("mem");
        assertEquals(FeedState.CAUGHT_UP, mem.state(), "everything offered is published");
        assertFalse(mem.drained(), "m3 is still being dispatched");
        processor.gate.countDown();
        await(() -> server.feedStatus("mem").settled(), "settled");
        assertEquals(3, processor.lines.get());
    }

    @Test
    void onlyAFeedThatAsksDeliversFeedStatusEvents() throws Exception {
        Path plainData = file("plain.txt", "p1\np2\n");
        Path statusData = file("status.txt", "s1\ns2\n");
        Probe plain = new Probe("plain", null);
        Probe withStatus = new Probe("status", null);
        MongooseServerConfig cfg = MongooseServerConfig.builder()
                .addEventFeed(EventFeedConfig.<String>builder().instance(fileSource(plainData, ReadStrategy.EARLIEST))
                        .name("plain").broadcast(true).agent("plain-agent", new SleepingMillisIdleStrategy(1)).build())
                .addEventFeed(EventFeedConfig.<String>builder().instance(fileSource(statusData, ReadStrategy.EARLIEST))
                        .name("status").broadcast(true).feedStatusEvents(true)
                        .agent("status-agent", new SleepingMillisIdleStrategy(1)).build())
                .build();
        cfg.addProcessor("g1", plain, "plain");
        cfg.addProcessor("g2", withStatus, "withStatus");
        server = MongooseServer.bootServer(cfg);

        await(() -> server.feedStatus("plain", "status").settled(), "settled");
        assertEquals(2, plain.lines.get());
        assertTrue(plain.received.stream().noneMatch(FeedStatus.class::isInstance),
                "a processor whose feed does not ask receives no feed status: " + plain.received);
        List<String> shape = withStatus.shape();
        assertEquals(List.of("s1", "s2", "CAUGHT_UP@2"), shape.subList(shape.size() - 3, shape.size()),
                "delivered after the lines it covers: " + shape);
        assertTrue(shape.stream().filter(s -> s.contains("@")).allMatch(s -> s.startsWith("BEHIND@") || s.equals("CAUGHT_UP@2")),
                shape.toString());
    }
}
