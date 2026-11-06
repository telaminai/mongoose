/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.delivery;

import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.service.extension.AbstractAgentHostedEventSourceService;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review 3, item 5 (docs/design/feed-delivery.md section 4): the final commit of a clean stop calls a committer only
 * once its source has stopped ({@link SourceCommitter#commitsAfterStop()}). A feed whose stop is still running when
 * the stop phase runs out of time is skipped, not committed under its own stop.
 */
class FeedDeliveryStopOrderTest {

    /** A positioned source whose stop blocks until released; records acknowledgements made while it is stopping. */
    static final class StuckSource extends AbstractAgentHostedEventSourceService<String> {
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean stopping = new AtomicBoolean();
        final List<Object> acknowledgedWhileStopping = new CopyOnWriteArrayList<>();
        volatile boolean published;

        StuckSource() {
            super("stuckSource");
        }

        @Override
        public void start() {
            output.acknowledgeWith(new SourceCommitter<Object>() {
                @Override
                public void acknowledged(long sequence, Object position) {
                    if (stopping.get()) acknowledgedWhileStopping.add(position);
                }

                @Override
                public boolean commitsAfterStop() {
                    return true;
                }
            });
        }

        volatile boolean started;

        @Override
        public void startComplete() {
            output.startComplete();
            started = true;
        }

        @Override
        public int doWork() {
            // never polls acknowledgements itself: what is processed stays pending until the shutdown's final commit
            if (started && !published && output.tryPublish("x", 1L)) published = true;
            return 0;
        }

        @Override
        public void stop() {
            stopping.set(true);
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                stopping.set(false);
            }
        }
    }

    @Test
    void aFeedStillStoppingIsNotCommittedUnderItsOwnStop() throws Exception {
        StuckSource src = new StuckSource();
        var processor = new FeedDeliveryServerTest.SlowProcessor(0);
        MongooseServerConfig cfg = new MongooseServerConfig();
        cfg.setShutdownTimeoutMillis(1500);
        cfg.addProcessor("g", processor, "p");
        cfg.addEventSourceWorker(src, FeedDeliveryServerTest.FEED, true, "feed-agent", new SleepingMillisIdleStrategy(1));
        MongooseServer server = MongooseServer.bootServer(cfg);
        try {
            FeedDeliveryServerTest.await(() -> processor.lines.size() == 1, "the item processed", 10);
            server.stop();                       // the source's stop does not return within the bound
            assertEquals(List.of(), src.acknowledgedWhileStopping,
                    "no acknowledgement reaches a committer whose source is still stopping");
        } finally {
            src.release.countDown();
            server.stop();
        }
    }
}
