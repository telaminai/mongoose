/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.delivery;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.ReadStrategy;
import com.telamin.mongoose.connector.file.FileEventSource;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventSourceKey;
import com.telamin.mongoose.service.EventSubscriptionKey;
import org.agrona.concurrent.SleepingMillisIdleStrategy;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * The server a {@link FeedDeliveryKillTest} kills with {@code kill -9}: a COMMITED file feed and a stateful processor
 * that, like an application saving its state after every event, writes its state (lines applied, last source position)
 * atomically after each line and appends the line to an unbuffered output file first.
 * <p>
 * args: data file, state file, output file, dedupe (true: skip a re-delivered line whose position is at or before the
 * stored one, the consumer-side offset of docs/design/feed-delivery.md 5a), park micros per line.
 */
public final class KillChildServer {

    static final String FEED = "lines";

    public static final class StatefulProcessor implements DataFlow {
        final Path stateFile;
        final OutputStream out;
        final boolean dedupe;
        final long parkNanos;
        long applied;
        long epoch;
        long position = -1;
        final List<EventFeed> feeds = new ArrayList<>();

        StatefulProcessor(Path stateFile, Path outFile, boolean dedupe, long parkNanos) throws Exception {
            this.stateFile = stateFile;
            this.dedupe = dedupe;
            this.parkNanos = parkNanos;
            this.out = new FileOutputStream(outFile.toFile(), true);
            if (Files.exists(stateFile)) {
                String[] s = Files.readString(stateFile).trim().split(" ");
                applied = Long.parseLong(s[0]);
                position = Long.parseLong(s[1]);
                epoch = Long.parseLong(s[2]);
            }
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
            if (!(e instanceof String line)) return;
            Delivery delivery = DeliveryContext.current();
            if (delivery == null) throw new IllegalStateException("a COMMITED file line has a source position");
            if (dedupe && delivery.alreadyApplied(epoch, position)) {
                return;   // re-delivered after a crash: its effect is already in the saved state
            }
            if (parkNanos > 0) LockSupport.parkNanos(parkNanos);
            try {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));          // the "alert"
                applied++;
                position = delivery.positionAsLong();
                epoch = delivery.epoch();
                // the state and its position in one atomic write, as an application's state store must
                Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
                Files.writeString(tmp, applied + " " + position + " " + epoch);
                Files.move(tmp, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Path data = Path.of(args[0]);
        StatefulProcessor processor = new StatefulProcessor(Path.of(args[1]), Path.of(args[2]),
                Boolean.parseBoolean(args[3]), Long.parseLong(args[4]) * 1000);
        FileEventSource src = new FileEventSource();
        src.setFilename(data.toString());
        src.setReadStrategy(ReadStrategy.COMMITED);
        MongooseServerConfig cfg = new MongooseServerConfig();
        cfg.addProcessor("g", processor, "stateful");
        cfg.addEventSourceWorker(src, FEED, true, "feed-agent", new SleepingMillisIdleStrategy(1));
        MongooseServer.bootServer(cfg);
        System.out.println("READY");
        Thread.currentThread().join();
    }
}
