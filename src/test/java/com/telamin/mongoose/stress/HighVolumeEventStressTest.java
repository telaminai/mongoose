/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.stress;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stress tests for high-volume event scenarios in Fluxtion Server.
 * These tests verify system behavior under extreme load conditions.
 */
public class HighVolumeEventStressTest {

    private MongooseServer server;
    private List<TestEventSource> eventSources;
    private List<TestEventProcessor> eventProcessors;
    private TestLogRecordListener logRecordListener;
    private ExecutorService executorService;
    private ScheduledExecutorService monitoringService;
    private MongooseServerConfig mongooseServerConfig;

    // Stress test parameters
    private static final int SOURCE_COUNT = 5;
    private static final int PROCESSOR_COUNT = 3;
    private static final int EVENTS_PER_SOURCE = 1_000_000;
    private static final int TOTAL_EVENTS = SOURCE_COUNT * EVENTS_PER_SOURCE;
    private static final int MONITORING_INTERVAL_MS = 1000;
    private static final int MAX_TEST_DURATION_SECONDS = 300; // 5 minutes max

    // Metrics
    private final AtomicLong totalEventsProcessed = new AtomicLong(0);
    private final AtomicLong totalEventsPublished = new AtomicLong(0);
    private final List<MetricSnapshot> metricSnapshots = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Deque<Long>> latencyHistograms = new ConcurrentHashMap<>();
    private final AtomicInteger errorCount = new AtomicInteger(0);

    @BeforeEach
    void setUp() {
        // Create a log record listener to capture log events
        logRecordListener = new TestLogRecordListener();

        // Create a minimal app config
        mongooseServerConfig = new MongooseServerConfig();

        // Create event processors
        eventProcessors = new ArrayList<>();
        for (int i = 0; i < PROCESSOR_COUNT; i++) {
            TestEventProcessor processor = new TestEventProcessor("processor-" + i);
            eventProcessors.add(processor);
            mongooseServerConfig.addProcessor(processor, "testHandler-" + i);
        }

        // Create event sources
        eventSources = new ArrayList<>();
        for (int i = 0; i < SOURCE_COUNT; i++) {
            TestEventSource source = new TestEventSource("source-" + i);
            eventSources.add(source);
            mongooseServerConfig.addEventSource(source, "testEventSourceFeed-" + i, true);
        }


        // Create thread pools
        executorService = Executors.newFixedThreadPool(SOURCE_COUNT);
        monitoringService = Executors.newSingleThreadScheduledExecutor();

        // Initialize latency histograms
        for (TestEventSource source : eventSources) {
            latencyHistograms.put(source.getName(), new ConcurrentLinkedDeque<>());
        }
    }

    @AfterEach
    void tearDown() {
        // Shutdown thread pools
        if (executorService != null) {
            executorService.shutdownNow();
        }
        if (monitoringService != null) {
            monitoringService.shutdownNow();
        }

        // Clean up server resources
        if (server != null) {
            server.stop();
        }

        // Print final metrics
        printFinalMetrics();
    }

    /**
     * Stress test with sustained high event rate.
     * This test publishes events at a constant high rate from multiple sources.
     */
    @Test
    @Disabled
    void testSustainedHighEventRate() throws Exception {
        System.out.println("Running sustained high event rate stress test");
        System.out.println("Total events to process: " + TOTAL_EVENTS);


        // Start monitoring
        startMonitoring();

        // Start the server
        server = MongooseServer.bootServer(mongooseServerConfig, logRecordListener);
        // Create the server
//        server.init();
//        server.start();

        // Create a countdown latch to wait for all events to be processed
        CountDownLatch completionLatch = new CountDownLatch(TOTAL_EVENTS);

        // Set up event processors to count down the latch
        for (TestEventProcessor processor : eventProcessors) {
            processor.setCompletionLatch(completionLatch);
        }

        // Start publishing events from all sources
        Instant startTime = Instant.now();
        List<Future<?>> futures = new ArrayList<>();

        for (TestEventSource source : eventSources) {
            futures.add(executorService.submit(() -> {
                try {
                    for (int i = 0; i < EVENTS_PER_SOURCE; i++) {
                        TestEvent event = new TestEvent("Event-" + i + "-from-" + source.getName());
                        long startNanos = System.nanoTime();
                        source.publishEvent(event);
                        long endNanos = System.nanoTime();

                        // Record latency
                        latencyHistograms.get(source.getName()).add(endNanos - startNanos);

                        // Update published count
                        totalEventsPublished.incrementAndGet();

                        // Small delay to avoid overwhelming the system
                        if (i % 10000 == 0) {
                            Thread.sleep(1);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Error publishing events from " + source.getName() + ": " + e.getMessage());
                    errorCount.incrementAndGet();
                }
                return null;
            }));
        }

        // Wait for all events to be processed or timeout
        boolean completed = completionLatch.await(MAX_TEST_DURATION_SECONDS, TimeUnit.SECONDS);
        Instant endTime = Instant.now();

        // Wait for all publishers to complete
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }

        // Stop monitoring
        stopMonitoring();

        // Calculate results
        Duration duration = Duration.between(startTime, endTime);
        double eventsPerSecond = totalEventsProcessed.get() / (duration.toMillis() / 1000.0);

        System.out.println("Test completed: " + (completed ? "All events processed" : "Timed out"));
        System.out.println("Events published: " + totalEventsPublished.get());
        System.out.println("Events processed: " + totalEventsProcessed.get());
        System.out.println("Duration: " + duration.toSeconds() + " seconds");
        System.out.println("Throughput: " + String.format("%.2f", eventsPerSecond) + " events/second");
        System.out.println("Error count: " + errorCount.get());

        // Assert that all events were processed
        assertTrue(completed, "Not all events were processed within the time limit");
        assertTrue(totalEventsProcessed.get() >= TOTAL_EVENTS * 0.99,
                "At least 99% of events should be processed");
    }

    /**
     * Stress test with burst patterns of events.
     * This test publishes events in bursts with pauses between bursts.
     */
    @Test
    @Disabled
    void testBurstEventPatterns() throws Exception {
        System.out.println("Running burst event patterns stress test");

        // Start monitoring
        startMonitoring();

        // Start the server
        server.init();
        server.start();

        // Create a countdown latch to wait for all events to be processed
        CountDownLatch completionLatch = new CountDownLatch(TOTAL_EVENTS);

        // Set up event processors to count down the latch
        for (TestEventProcessor processor : eventProcessors) {
            processor.setCompletionLatch(completionLatch);
        }

        // Start publishing events in bursts
        Instant startTime = Instant.now();
        List<Future<?>> futures = new ArrayList<>();

        int burstSize = EVENTS_PER_SOURCE / 10; // 10 bursts per source

        for (TestEventSource source : eventSources) {
            futures.add(executorService.submit(() -> {
                try {
                    for (int burst = 0; burst < 10; burst++) {
                        System.out.println("Source " + source.getName() + " starting burst " + (burst + 1));

                        // Publish a burst of events
                        for (int i = 0; i < burstSize; i++) {
                            TestEvent event = new TestEvent("Burst-" + burst + "-Event-" + i + "-from-" + source.getName());
                            long startNanos = System.nanoTime();
                            source.publishEvent(event);
                            long endNanos = System.nanoTime();

                            // Record latency
                            latencyHistograms.get(source.getName()).add(endNanos - startNanos);

                            // Update published count
                            totalEventsPublished.incrementAndGet();
                        }

                        // Pause between bursts
                        if (burst < 9) { // Don't pause after the last burst
                            Thread.sleep(1000);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Error publishing events from " + source.getName() + ": " + e.getMessage());
                    errorCount.incrementAndGet();
                }
                return null;
            }));
        }

        // Wait for all events to be processed or timeout
        boolean completed = completionLatch.await(MAX_TEST_DURATION_SECONDS, TimeUnit.SECONDS);
        Instant endTime = Instant.now();

        // Wait for all publishers to complete
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }

        // Stop monitoring
        stopMonitoring();

        // Calculate results
        Duration duration = Duration.between(startTime, endTime);
        double eventsPerSecond = totalEventsProcessed.get() / (duration.toMillis() / 1000.0);

        System.out.println("Test completed: " + (completed ? "All events processed" : "Timed out"));
        System.out.println("Events published: " + totalEventsPublished.get());
        System.out.println("Events processed: " + totalEventsProcessed.get());
        System.out.println("Duration: " + duration.toSeconds() + " seconds");
        System.out.println("Throughput: " + String.format("%.2f", eventsPerSecond) + " events/second");
        System.out.println("Error count: " + errorCount.get());

        // Assert that all events were processed
        assertTrue(completed, "Not all events were processed within the time limit");
        assertTrue(totalEventsProcessed.get() >= TOTAL_EVENTS * 0.99,
                "At least 99% of events should be processed");
    }

    /**
     * Stress test with increasing event rate until system degradation.
     * This test gradually increases the event rate until the system shows signs of degradation.
     */
    @Test
    @Disabled
    void testIncreasingEventRate() throws Exception {
        System.out.println("Running increasing event rate stress test");

        // Start monitoring
        startMonitoring();

        // Start the server
        server.init();
        server.start();

        // Create a countdown latch to wait for all events to be processed
        CountDownLatch completionLatch = new CountDownLatch(TOTAL_EVENTS);

        // Set up event processors to count down the latch
        for (TestEventProcessor processor : eventProcessors) {
            processor.setCompletionLatch(completionLatch);
        }

        // Start publishing events with increasing rate
        Instant startTime = Instant.now();
        List<Future<?>> futures = new ArrayList<>();

        int phases = 10;
        int eventsPerPhase = EVENTS_PER_SOURCE / phases;

        for (TestEventSource source : eventSources) {
            futures.add(executorService.submit(() -> {
                try {
                    for (int phase = 0; phase < phases; phase++) {
                        System.out.println("Source " + source.getName() + " starting phase " + (phase + 1) +
                                " with rate multiplier " + (phase + 1));

                        // Calculate delay between events (decreasing with each phase)
                        long delayNanos = 1_000_000 / (phase + 1); // Start with 1ms, decrease with each phase

                        // Publish events for this phase
                        for (int i = 0; i < eventsPerPhase; i++) {
                            TestEvent event = new TestEvent("Phase-" + phase + "-Event-" + i + "-from-" + source.getName());
                            long startNanos = System.nanoTime();
                            source.publishEvent(event);
                            long endNanos = System.nanoTime();

                            // Record latency
                            latencyHistograms.get(source.getName()).add(endNanos - startNanos);

                            // Update published count
                            totalEventsPublished.incrementAndGet();

                            // Delay between events (decreasing with each phase)
                            if (delayNanos > 0) {
                                long sleepNanos = delayNanos - (System.nanoTime() - endNanos);
                                if (sleepNanos > 0) {
                                    LockSupport.parkNanos(sleepNanos);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Error publishing events from " + source.getName() + ": " + e.getMessage());
                    errorCount.incrementAndGet();
                }
                return null;
            }));
        }

        // Wait for all events to be processed or timeout
        boolean completed = completionLatch.await(MAX_TEST_DURATION_SECONDS, TimeUnit.SECONDS);
        Instant endTime = Instant.now();

        // Wait for all publishers to complete
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }

        // Stop monitoring
        stopMonitoring();

        // Calculate results
        Duration duration = Duration.between(startTime, endTime);
        double eventsPerSecond = totalEventsProcessed.get() / (duration.toMillis() / 1000.0);

        System.out.println("Test completed: " + (completed ? "All events processed" : "Timed out"));
        System.out.println("Events published: " + totalEventsPublished.get());
        System.out.println("Events processed: " + totalEventsProcessed.get());
        System.out.println("Duration: " + duration.toSeconds() + " seconds");
        System.out.println("Throughput: " + String.format("%.2f", eventsPerSecond) + " events/second");
        System.out.println("Error count: " + errorCount.get());

        // Assert that a significant portion of events were processed
        // Note: This test may not process all events due to intentional system overload
        assertTrue(totalEventsProcessed.get() >= TOTAL_EVENTS * 0.8,
                "At least 80% of events should be processed");
    }

    /**
     * Starts the monitoring service to collect metrics at regular intervals.
     */
    private void startMonitoring() {
        monitoringService.scheduleAtFixedRate(this::collectMetrics,
                0, MONITORING_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Stops the monitoring service.
     */
    private void stopMonitoring() {
        monitoringService.shutdown();
        try {
            monitoringService.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Collects metrics about the system state.
     */
    private void collectMetrics() {
        try {
            // Get memory usage
            MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
            MemoryUsage heapMemory = memoryBean.getHeapMemoryUsage();
            MemoryUsage nonHeapMemory = memoryBean.getNonHeapMemoryUsage();

            // Get CPU usage
            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            int threadCount = threadBean.getThreadCount();

            // Create a snapshot
            MetricSnapshot snapshot = new MetricSnapshot(
                    Instant.now(),
                    totalEventsPublished.get(),
                    totalEventsProcessed.get(),
                    heapMemory.getUsed(),
                    nonHeapMemory.getUsed(),
                    threadCount,
                    errorCount.get()
            );

            // Add to the list
            metricSnapshots.add(snapshot);

            // Print current metrics
            System.out.println(String.format(
                    "[%s] Published: %d, Processed: %d, Heap: %.2f MB, Threads: %d, Errors: %d",
                    snapshot.timestamp,
                    snapshot.eventsPublished,
                    snapshot.eventsProcessed,
                    snapshot.heapMemoryUsed / (1024.0 * 1024.0),
                    snapshot.threadCount,
                    snapshot.errorCount
            ));
        } catch (Exception e) {
            System.err.println("Error collecting metrics: " + e.getMessage());
        }
    }

    /**
     * Prints final metrics and analysis.
     */
    private void printFinalMetrics() {
        if (metricSnapshots.isEmpty()) {
            System.out.println("No metrics collected");
            return;
        }

        System.out.println("\n===== FINAL METRICS =====");

        // Calculate throughput over time
        List<Double> throughputs = new ArrayList<>();
        for (int i = 1; i < metricSnapshots.size(); i++) {
            MetricSnapshot prev = metricSnapshots.get(i - 1);
            MetricSnapshot curr = metricSnapshots.get(i);

            long eventsDelta = curr.eventsProcessed - prev.eventsProcessed;
            double timeDeltaSeconds = Duration.between(prev.timestamp, curr.timestamp).toMillis() / 1000.0;

            if (timeDeltaSeconds > 0) {
                throughputs.add(eventsDelta / timeDeltaSeconds);
            }
        }

        // Calculate latency statistics
        Map<String, LongSummaryStatistics> latencyStats = new HashMap<>();
        for (Map.Entry<String, Deque<Long>> entry : latencyHistograms.entrySet()) {
            latencyStats.put(entry.getKey(),
                    entry.getValue().stream().collect(Collectors.summarizingLong(Long::longValue)));
        }

        // Print throughput statistics
        if (!throughputs.isEmpty()) {
            DoubleSummaryStatistics throughputStats =
                    throughputs.stream().collect(Collectors.summarizingDouble(Double::doubleValue));

            System.out.println("\nThroughput Statistics (events/second):");
            System.out.println("  Min: " + String.format("%.2f", throughputStats.getMin()));
            System.out.println("  Max: " + String.format("%.2f", throughputStats.getMax()));
            System.out.println("  Avg: " + String.format("%.2f", throughputStats.getAverage()));
        }

        // Print latency statistics
        System.out.println("\nLatency Statistics (nanoseconds):");
        for (Map.Entry<String, LongSummaryStatistics> entry : latencyStats.entrySet()) {
            LongSummaryStatistics stats = entry.getValue();
            System.out.println("  " + entry.getKey() + ":");
            System.out.println("    Min: " + stats.getMin() + " ns (" + (stats.getMin() / 1000) + " µs)");
            System.out.println("    Max: " + stats.getMax() + " ns (" + (stats.getMax() / 1000) + " µs)");
            System.out.println("    Avg: " + String.format("%.2f", stats.getAverage()) +
                    " ns (" + String.format("%.2f", stats.getAverage() / 1000) + " µs)");
        }

        // Print memory usage trend
        if (metricSnapshots.size() >= 2) {
            MetricSnapshot first = metricSnapshots.get(0);
            MetricSnapshot last = metricSnapshots.get(metricSnapshots.size() - 1);

            double initialHeapMB = first.heapMemoryUsed / (1024.0 * 1024.0);
            double finalHeapMB = last.heapMemoryUsed / (1024.0 * 1024.0);
            double heapDeltaMB = finalHeapMB - initialHeapMB;

            System.out.println("\nMemory Usage:");
            System.out.println("  Initial Heap: " + String.format("%.2f", initialHeapMB) + " MB");
            System.out.println("  Final Heap: " + String.format("%.2f", finalHeapMB) + " MB");
            System.out.println("  Delta: " + String.format("%.2f", heapDeltaMB) + " MB");
        }

        System.out.println("\nError Count: " + errorCount.get());
        System.out.println("===== END METRICS =====\n");
    }

    /**
     * A simple event class for testing.
     */
    public static class TestEvent {
        private final String message;
        private final long timestamp;

        public TestEvent(String message) {
            this.message = message;
            this.timestamp = System.nanoTime();
        }

        public String getMessage() {
            return message;
        }

        public long getTimestamp() {
            return timestamp;
        }

        @Override
        public String toString() {
            return "TestEvent_In{message='" + message + "', timestamp=" + timestamp + '}';
        }
    }

    /**
     * A test event source that can publish events.
     */
    private static class TestEventSource extends AbstractEventSourceService<TestEvent> {
        public TestEventSource(String name) {
            super(name);
        }

        public void publishEvent(TestEvent event) {
            if (output != null) {
                output.publish(event);
            }
        }
    }

    /**
     * A test event processor that processes TestEvents.
     */
    private static class TestEventProcessor implements DataFlow {
        private final String name;
        private final List<TestEvent> processedEvents = new ArrayList<>();
        private volatile CountDownLatch completionLatch;
        private List<EventFeed> eventFeeds = new ArrayList<>();

        public TestEventProcessor(String name) {
            this.name = name;
        }

        public void setCompletionLatch(CountDownLatch completionLatch) {
            this.completionLatch = completionLatch;
        }

        public void handleTestEvent(TestEvent event) {
            // Only store a subset of events to avoid memory issues
            if (processedEvents.size() < 1000) {
                processedEvents.add(event);
            }

            // Count down the latch if provided
            if (completionLatch != null) {
                completionLatch.countDown();
            }
        }

        @Override
        public void onEvent(Object event) {
            if (event instanceof TestEvent) {
                handleTestEvent((TestEvent) event);
            }
        }

        @Override
        public void addEventFeed(EventFeed eventFeed) {
            eventFeeds.add(eventFeed);
        }

        @Override
        public void init() {
        }

        @Override
        public void start() {
            EventSubscriptionKey<Object> subscriptionKey = new EventSubscriptionKey<>(
                    new EventSourceKey<>("testEventSourceFeed-0"),
                    CallBackType.ON_EVENT_CALL_BACK
            );

            eventFeeds.forEach(feed -> {
                feed.subscribe(this, subscriptionKey);
            });

            // Subscribe to all event sources
            for (int i = 1; i < SOURCE_COUNT; i++) {
                EventSubscriptionKey<Object> key = new EventSubscriptionKey<>(
                        new EventSourceKey<>("testEventSourceFeed-" + i),
                        CallBackType.ON_EVENT_CALL_BACK
                );

                eventFeeds.forEach(feed -> {
                    feed.subscribe(this, key);
                });
            }
        }

        @Override
        public void tearDown() {
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

    /**
     * A snapshot of system metrics at a point in time.
     */
    private static class MetricSnapshot {
        private final Instant timestamp;
        private final long eventsPublished;
        private final long eventsProcessed;
        private final long heapMemoryUsed;
        private final long nonHeapMemoryUsed;
        private final int threadCount;
        private final int errorCount;

        public MetricSnapshot(
                Instant timestamp,
                long eventsPublished,
                long eventsProcessed,
                long heapMemoryUsed,
                long nonHeapMemoryUsed,
                int threadCount,
                int errorCount) {
            this.timestamp = timestamp;
            this.eventsPublished = eventsPublished;
            this.eventsProcessed = eventsProcessed;
            this.heapMemoryUsed = heapMemoryUsed;
            this.nonHeapMemoryUsed = nonHeapMemoryUsed;
            this.threadCount = threadCount;
            this.errorCount = errorCount;
        }
    }
}
