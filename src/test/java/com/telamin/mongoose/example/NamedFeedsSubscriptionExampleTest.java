/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.example;

import com.fluxtion.agrona.concurrent.BusySpinIdleStrategy;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.*;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.connector.memory.InMemoryMessageSink;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Sample showing how to subscribe to EventFeeds with different names and ignore
 * those that do not match.
 */
public class NamedFeedsSubscriptionExampleTest {

    @Test
    public void subscribe_to_selected_named_feeds_only() throws Exception {
        // In-memory sink
        InMemoryMessageSink memSink = new InMemoryMessageSink();

        // Feeds: three named in-memory sources
        InMemoryEventSource<String> prices = new InMemoryEventSource<>();
        prices.setCacheEventLog(true);
        InMemoryEventSource<String> orders = new InMemoryEventSource<>();
        orders.setCacheEventLog(true);
        InMemoryEventSource<String> news = new InMemoryEventSource<>();
        news.setCacheEventLog(true);

        // Processor that only forwards events from feeds: prices, news
        NamedFeedsFilterHandler filterHandler = new NamedFeedsFilterHandler(Set.of("prices", "news"));

        EventProcessorGroupConfig processorGroup = EventProcessorGroupConfig.builder()
                .agentName("processor-agent")
                .put("filter-processor", new EventProcessorConfig(filterHandler))
                .build();

        // Build EventFeed configs with names
        EventFeedConfig<?> pricesFeed = EventFeedConfig.builder()
                .instance(prices)
                .name("prices")
                .agent("prices-agent", new BusySpinIdleStrategy())
                .build();

        EventFeedConfig<?> ordersFeed = EventFeedConfig.builder()
                .instance(orders)
                .name("orders")
                .agent("orders-agent", new BusySpinIdleStrategy())
                .build();

        EventFeedConfig<?> newsFeed = EventFeedConfig.builder()
                .instance(news)
                .name("news")
                .agent("news-agent", new BusySpinIdleStrategy())
                .build();

        EventSinkConfig<MessageSink<?>> sinkCfg = EventSinkConfig.<MessageSink<?>>builder()
                .instance(memSink)
                .name("memSink")
                .build();

        MongooseServerConfig mongooseServerConfig = MongooseServerConfig.builder()
                .addProcessorGroup(processorGroup)
                .addEventFeed(pricesFeed)
                .addEventFeed(ordersFeed)
                .addEventFeed(newsFeed)
                .addEventSink(sinkCfg)
                .build();

        LogRecordListener logListener = rec -> {};
        MongooseServer server = MongooseServer.bootServer(mongooseServerConfig, logListener);
        try {

            prices.offer("p1");
            prices.offer("p2");
            orders.offer("o1");
            orders.offer("o2");
            news.offer("n1");
            news.offer("n2");

            // Wait for sink messages; should include only p1,p2,n1,n2
            List<Object> lines = waitForMessages(memSink, 4, 5, TimeUnit.SECONDS);
            Assertions.assertTrue(lines.containsAll(List.of("p1", "p2", "n1", "n2")),
                    () -> "Missing expected lines in sink: " + lines);
            // Ensure orders were ignored
            Assertions.assertFalse(lines.contains("o1") || lines.contains("o2"),
                    () -> "Unexpected order lines in sink: " + lines);
        } finally {
            server.stop();
        }
    }

    private static List<Object> waitForMessages(InMemoryMessageSink sink, int minCount, long timeout, TimeUnit unit) throws Exception {
        long end = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < end) {
            List<Object> lines = sink.getMessages();
            if (lines.size() >= minCount) {
                return lines;
            }
            Thread.sleep(50);
        }
        return sink.getMessages();
    }
}
