/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.benchmark.objectpool;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.audit.LogRecord;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventSourceKey;
import com.telamin.mongoose.service.EventSubscriptionKey;
import com.telamin.mongoose.service.extension.AbstractEventSourceService;
import lombok.Getter;
import lombok.Setter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Performance benchmark for event processing in Fluxtion Server.
 * This benchmark measures throughput and latency for event processing.
 */
public class EventProcessingBenchmark {

    private MongooseServer server;
    private TestEventSource eventSource;
    private TestEventProcessor eventProcessor;
    private TestLogRecordListener logRecordListener;
    private CountDownLatch eventProcessedLatch;

    // Benchmark parameters
    private static final int WARMUP_COUNT = 1_000_000;
    private static final int BENCHMARK_COUNT = 20_000_000;
    private static final int BATCH_SIZE = 100;

    @BeforeEach
    void setUp() {
        // Create a log record listener to capture log events
        logRecordListener = new TestLogRecordListener();

        // Create a minimal app config
        MongooseServerConfig mongooseServerConfig = new MongooseServerConfig();

        // Create a countdown latch to wait for all events to be processed
        eventProcessedLatch = new CountDownLatch(1);

        // Create an event processor
        eventProcessor = new TestEventProcessor(eventProcessedLatch);
        mongooseServerConfig.addProcessor(eventProcessor, "testHandler");

        // Create an event source
        eventSource = new TestEventSource("testSource");
        mongooseServerConfig.addEventSource(eventSource, "testEventSourceFeed", true);

        // Create the server
        server = MongooseServer.bootServer(mongooseServerConfig, logRecordListener);
    }

    @AfterEach
    void tearDown() {
        // Clean up resources
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void benchmarkSingleEventProcessing() throws Exception {
        System.out.println("Running single event processing benchmark");
        TestEvent[] eventCache = new TestEvent[BENCHMARK_COUNT];
        for (int i = 0; i < BENCHMARK_COUNT; i++) {
            TestEvent event = new TestEvent("Benchmark " + i, i);
            eventCache[i] = event;
        }

        // Warm up
        System.out.println("Warming up with " + WARMUP_COUNT + " events");
        for (int i = 0; i < WARMUP_COUNT; i++) {
            TestEvent event = eventCache[i];
            event.setPublishTime();
            eventSource.publishEvent(event);
        }

        eventSource.publishEvent("reset");
        System.out.println("------------------------------");

        // Benchmark
        System.out.println("Running benchmark with " + BENCHMARK_COUNT + " events");

        for (int i = 0; i < BENCHMARK_COUNT; i++) {
            TestEvent event = eventCache[i];
            event.setPublishTime();
            eventSource.publishEvent(event);
        }

//        java.lang.Thread.onSpinWait();

        eventSource.publishEvent("publishResults");
        eventProcessedLatch.await();

        // Verify that the benchmark completed successfully
        assertTrue(eventProcessor.getProcessedCount() >= BENCHMARK_COUNT,
                "Not all events were processed processCount: " + eventProcessor.getProcessedCount() + "");

    }

    /**
     * A simple event class for testing.
     */
    public static class TestEvent {
        @Getter
        private final String message;
        @Getter
        private final long timestamp;
        @Getter
        private long publishTime;
        @Getter
        @Setter
        private long processedTime;
        @Getter
        private final long id;

        public TestEvent(String message, long id) {
            this.message = message;
            this.timestamp = System.nanoTime();
            this.id = id;
        }

        public void setPublishTime() {
            publishTime = System.nanoTime();
        }

        @Override
        public String toString() {
            return "TestEvent_In{message='" + message
                    + "', id=" + id
                    + "', publishTime=" + publishTime
                    + "', processedTime=" + processedTime
                    + "', delay=" + (processedTime - publishTime)
                    + '}';
        }
    }

    /**
     * A test event source that can publish events.
     */
    private static class TestEventSource extends AbstractEventSourceService<Object> {
        public TestEventSource(String name) {
            super(name);
        }

        public void publishEvent(Object event) {
            if (output != null) {
                output.publish(event);
            }
        }
    }

    /**
     * A test event processor that processes TestEvents.
     */
    private static class TestEventProcessor implements DataFlow {
        private final List<TestEvent> processedEvents = new ArrayList<>(BENCHMARK_COUNT);
        private final CountDownLatch eventProcessedLatch;
        private volatile int processedCount = 0;
        private List<EventFeed<Object>> eventFeeds = new ArrayList<>();
        private final List<Long> latencies = new ArrayList<>(BENCHMARK_COUNT);
        private TestEvent previousEvent;

        public TestEventProcessor(CountDownLatch eventProcessedLatch) {
            this.eventProcessedLatch = eventProcessedLatch;
        }

        public void handleTestEvent(TestEvent event) {
            long endTime = System.nanoTime();
            event.setProcessedTime(endTime);

            if (previousEvent != null) {
                long expectedDelta = event.publishTime - previousEvent.publishTime;
                long actualDelta = event.processedTime - previousEvent.processedTime;
                long delta = actualDelta - expectedDelta;
                if (delta <= 0) {
                    latencies.add(1L);
                } else {
                    latencies.add(delta);
                }
                previousEvent = event;
            } else {
                previousEvent = event;
                return;
            }
            processedCount++;

            // Only store a subset of events to avoid memory issues
            if (processedCount % 10000 == 0) {
                processedEvents.add(event);
            }
        }

        public void handleReset(String command) {
            System.out.println("Received reset command: " + command);
            processedEvents.clear();
            processedCount = 0;
            latencies.clear();
        }

        public void printResults() {
            eventProcessedLatch.countDown();

            TestEvent firstEvent = processedEvents.get(0);
            TestEvent lastEvent = processedEvents.get(processedEvents.size() - 1);

            double latencyFull = (lastEvent.processedTime - firstEvent.getPublishTime()) / 1_000.0;
            double count = lastEvent.getId() - firstEvent.getId();
            double avgSendLatencyMicros = latencyFull / count;
            double throughputPerSecondSend = 1_000_000.0 / avgSendLatencyMicros;

            System.out.println("Full send end to end latency: " + latencyFull + " µs");
            System.out.printf("Avg send latency: %.3f µs%n", avgSendLatencyMicros);
            System.out.println("Throughput send: " + String.format("%.2f", throughputPerSecondSend) + " events/second");

            LongSummaryStatistics stats = latencies.stream().collect(Collectors.summarizingLong(Long::longValue));
            double avgLatencyMicros = stats.getAverage() / 1000.0;
            double throughputPerSecond = 1_000_000_000.0 / stats.getAverage();

            System.out.println("Received event processing results:");
            System.out.println("Total events: " + stats.getCount());
            System.out.println("Min latency: " + stats.getMin() / 1000.0 + " µs");
            System.out.println("Max latency: " + stats.getMax() / 1000.0 + " µs");
            System.out.println("Total latency: " + stats.getSum() / 1000.0 + " µs");
            System.out.println("Avg latency: " + avgLatencyMicros + " µs");
            System.out.println("Throughput: " + String.format("%.2f", throughputPerSecond) + " events/second");
        }

        public List<TestEvent> getProcessedEvents() {
            return processedEvents;
        }

        public int getProcessedCount() {
            return processedCount;
        }

        @Override
        public void onEvent(Object event) {
            if (event instanceof TestEvent) {
                handleTestEvent((TestEvent) event);
            } else if (event instanceof String) {
                switch ((String) event) {
                    case "reset" -> handleReset((String) event);
                    case "publishResults" -> printResults();
                }
            }
        }

        @Override
        public void addEventFeed(com.telamin.fluxtion.runtime.input.EventFeed eventFeed) {
            eventFeeds.add(eventFeed);
        }

        @Override
        public void init() {

        }

        @Override
        public void start() {
            EventSubscriptionKey<Object> subscriptionKey = new EventSubscriptionKey<>(
                    new EventSourceKey<>("testEventSourceFeed"),
                    CallBackType.ON_EVENT_CALL_BACK
            );

            eventFeeds.forEach(feed -> {
                feed.subscribe(this, subscriptionKey);
            });
        }

        @Override
        public void tearDown() {
            System.out.println("TestEventProcessor shutting down");
        }
    }

    /**
     * A test log record listener that captures log records.
     */
    private static class TestLogRecordListener implements LogRecordListener {
        private final List<LogRecord> logRecords = new ArrayList<>();

        @Override
        public void processLogRecord(LogRecord logRecord) {
            // Only store a subset of log records to avoid memory issues
            if (logRecords.size() < 1000) {
                logRecords.add(logRecord);
            }
        }

        public List<LogRecord> getLogRecords() {
            return logRecords;
        }
    }
}
