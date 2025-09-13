/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.benchmark.objectpool;

import com.fluxtion.agrona.concurrent.BusySpinIdleStrategy;
import com.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.ThreadConfig;
import com.telamin.mongoose.service.extension.AbstractEventSourceService;
import com.telamin.mongoose.service.pool.ObjectPool;
import com.telamin.mongoose.service.pool.ObjectPoolsRegistry;
import com.telamin.mongoose.service.pool.impl.BasePoolAware;
import org.HdrHistogram.Histogram;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * End-to-end example that boots a MongooseServer and uses an EventSource that
 * acquires messages from the global ObjectPool using try-with-resources. The
 * default PoolAware.close() releases the caller's reference and attempts to
 * return to the pool, while the pipeline (queues/consumers) holds and releases
 * its own references.
 * <p>
 * Run from your IDE by executing main().
 */
public class BenchmarkObjectPoolDistribution {

    public static final int PUBLISH_FREQUENCY_NANOS = 1_00;

    /**
     * A simple pooled message type.
     */
    public static class PooledMessage extends BasePoolAware {
        public long value;
        public long offSet;

        @Override
        public String toString() {
            return "PooledMessage{" + value + '}';
        }
    }

    /**
     * EventSource that publishes pooled messages. It uses try-with-resources to
     * ensure the origin reference is dropped on scope exit.
     */
    public static class PooledEventSource extends AbstractEventSourceService<PooledMessage> {
        private ObjectPool<PooledMessage> pool;

        public PooledEventSource() {
            super("pooledSource");
        }

        @ServiceRegistered
        public void setObjectPoolsRegistry(ObjectPoolsRegistry objectPoolsRegistry, String name) {
            this.pool = objectPoolsRegistry.getOrCreate(
                    PooledMessage.class,
                    PooledMessage::new,
                    pm -> {
                    },
                    2048
            );
        }

        /**
         * Publish a message value using try-with-resources. The PoolAware.close()
         * drops the origin reference and attempts to return to pool; queued/consumer
         * references keep the object alive until fully processed.
         */
        public void publish(long value) {
            PooledMessage msg = pool.acquire();
            msg.value = value;
            output.publish(msg);
        }
    }

    public static class MyHandler extends ObjectEventHandlerNode {

        private long count;
        private final Histogram histogram = new Histogram(300_000_000L, 2);
        private volatile boolean collecting = true;
        private volatile boolean periodicConsole = true;
        private volatile boolean skipInitialCount = true;

        public MyHandler() {
            histogram.setAutoResize(true);
        }

        public void setCollecting(boolean collecting) {
            this.collecting = collecting;
        }

        public void setPeriodicConsole(boolean periodicConsole) {
            this.periodicConsole = periodicConsole;
        }

        public void setSkipInitialCount(boolean skipInitialCount) {
            this.skipInitialCount = skipInitialCount;
        }

        public Histogram histogram() {
            return histogram;
        }

        public void writeHgrm(Path outputPath) throws Exception {
            try (PrintStream ps = new PrintStream(new FileOutputStream(outputPath.toFile()))) {
                // Write percentile distribution in nanoseconds suitable for HDR Histogram plotter (.hgrm)
                histogram.outputPercentileDistribution(ps, 1.0);
            }
        }

        @Override
        protected boolean handleEvent(Object event) {
            if (event instanceof PooledMessage pooledMessage && pooledMessage.value == -1) {
                System.out.println("this is a null message");
            }

            if (event instanceof PooledMessage pooledMessage) {
                count++;
                if (skipInitialCount && count < 1_000_000) {
                    return true;
                }

                long now = System.nanoTime();
                long simpleLatency = now - pooledMessage.value;

                if (collecting) {
                    histogram.recordValue(simpleLatency);
                }
                if (periodicConsole && count % 5_000_000 == 0) {
                    System.out.println("HDR Histogram latency (ns): p50=" + histogram.getValueAtPercentile(50)
                            + ", p90=" + histogram.getValueAtPercentile(90)
                            + ", p99=" + histogram.getValueAtPercentile(99)
                            + ", p99.9=" + histogram.getValueAtPercentile(99.9)
                            + ", p99.99=" + histogram.getValueAtPercentile(99.99)
                            + ", p99.999=" + histogram.getValueAtPercentile(99.999)
                            + ", max=" + histogram.getMaxValue()
                            + ", count=" + histogram.getTotalCount()
                            + ", avg(ns)=" + (histogram.getMean()));
                    histogram.reset();
                    count = 0;
                }

            }
            return true;
        }
    }

    public static void main(String[] args) throws Exception {
        PooledEventSource source = new PooledEventSource();

        // Note: On macOS (Apple Silicon/M-series), true OS-level CPU affinity is not supported like on Linux.
        // Fluxtion will attempt best-effort pinning via com.telamin.mongoose.internal.CoreAffinity which uses
        // OpenHFT Affinity if available; on macOS it usually no-ops. BusySpinIdleStrategy helps reduce jitter,
        // but you cannot guarantee exclusive core isolation on macOS. This config still sets the desired coreId.
        ThreadConfig threadConfig = ThreadConfig.builder()
                .agentName("pinned-agent-thread")
                .idleStrategy(new BusySpinIdleStrategy())
                .coreId(0) // desired core index (best-effort on macOS)
                .build();

        MyHandler handler = new MyHandler();
        MongooseServerConfig cfg = new MongooseServerConfig()
                .addProcessor("pinned-agent-thread", handler, "processor")
                .addEventSource(source, "pooledSource", true);

        cfg.setAgentThreads(List.of(threadConfig));

        MongooseServer server = MongooseServer.bootServer(cfg, rec -> {
        });

        AtomicBoolean running = new AtomicBoolean(true);
        Thread publisher = new Thread(() -> {
            long nextPublishTime = System.nanoTime();
            long now = System.nanoTime();
            while (running.get()) {
                if (now >= nextPublishTime) {
                    source.publish(now);
                    nextPublishTime = now + PUBLISH_FREQUENCY_NANOS;
                }
                now = System.nanoTime();
            }
        }, "pooled-publisher");
        publisher.setDaemon(true);
        publisher.start();

        // Optional measured mode: --warmupMillis=... --runMillis=... --output=path/to/report.hgrm
        Long warmupMillis = null;
        Long runMillis = null;
        Path outputPath = null;
        for (String arg : args) {
            if (arg.startsWith("--warmupMillis=")) {
                warmupMillis = Long.parseLong(arg.substring("--warmupMillis=".length()));
            } else if (arg.startsWith("--runMillis=")) {
                runMillis = Long.parseLong(arg.substring("--runMillis=".length()));
            } else if (arg.startsWith("--output=")) {
                outputPath = Path.of(arg.substring("--output=".length()));
            }
        }

        if (warmupMillis != null && runMillis != null && outputPath != null) {
            // Configure handler for measured run: no periodic console, no initial-count skip, control collecting
            handler.setPeriodicConsole(false);
            handler.setSkipInitialCount(false);
            handler.setCollecting(false); // warmup without collecting

            System.out.println("Starting warmup for " + warmupMillis + " ms...");
            Thread.sleep(warmupMillis);

            System.out.println("Starting measured run for " + runMillis + " ms...");
            handler.setCollecting(true);
            Thread.sleep(runMillis);

            running.set(false);
            publisher.join(1000);

            // Write .hgrm file
            handler.writeHgrm(outputPath);
            System.out.println("Wrote HDR Histogram percentile distribution to: " + outputPath.toAbsolutePath());
            System.out.println("How to view: Open https://hdrhistogram.github.io/HdrHistogram/plotFiles.html and drop the .hgrm file,\n" +
                    "or use hdrplot.py locally. The values are in nanoseconds.");

            // Keep server alive only if not measured mode; here we exit after writing
            System.exit(0);
        } else {
            System.out.println("Running in continuous console mode. To run measured mode, use:\n" +
                    "  --warmupMillis=5000 --runMillis=10000 --output=/tmp/latency.hgrm\n" +
                    "Then open https://hdrhistogram.github.io/HdrHistogram/plotFiles.html and load the file.");
        }
    }
}
