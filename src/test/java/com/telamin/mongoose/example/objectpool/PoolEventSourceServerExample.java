/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.example.objectpool;

import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.service.extension.AbstractEventSourceService;
import com.telamin.mongoose.service.pool.ObjectPool;
import com.telamin.mongoose.service.pool.ObjectPoolsRegistry;
import com.telamin.mongoose.service.pool.impl.BasePoolAware;

/**
 * End-to-end example that boots a MongooseServer and uses an EventSource that
 * acquires messages from the global ObjectPool using try-with-resources. The
 * default PoolAware.close() releases the caller's reference and attempts to
 * return to the pool, while the pipeline (queues/consumers) holds and releases
 * its own references.
 * <p>
 * Run from your IDE by executing main().
 */
public class PoolEventSourceServerExample {

    /**
     * A simple pooled message type.
     */
    public static class PooledMessage extends BasePoolAware {
        public long value;

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
                    pm -> pm.value = -1,
                    1024
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
        private long startTime;
        private final StringBuilder sb = new StringBuilder(256);

        @Override
        protected boolean handleEvent(Object event) {
            if (event instanceof PooledMessage pooledMessage && pooledMessage.value == -1) {
                System.out.println("this is a null message");
            }

            if (count == 0) {
                startTime = System.currentTimeMillis();
            }
            count++;
            if (count % 1_000_000 == 0) {
                long duration = System.currentTimeMillis() - startTime;
                long heapSize = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                long gcCount = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()
                        .stream()
                        .mapToLong(java.lang.management.GarbageCollectorMXBean::getCollectionCount)
                        .sum();

                sb.append("Processed ");
                sb.append(count);
                sb.append(" messages in ");
                sb.append(duration);
                sb.append(" ms, heap used: ");
                sb.append(heapSize / (1024 * 1024));
                sb.append(" MB, GC count: ");
                sb.append(gcCount);
                System.out.println(sb);

                sb.setLength(0);
                startTime = System.currentTimeMillis();
            }
            return true;
        }
    }

    public static void main(String[] args) throws Exception {
        PooledEventSource source = new PooledEventSource();

        MongooseServerConfig cfg = new MongooseServerConfig()
                .addProcessor("thread-p1", new MyHandler(), "processor")
                .addEventSource(source, "pooledSource", true);

        MongooseServer server = MongooseServer.bootServer(cfg, rec -> {});

        boolean running = true;
        Thread publisher = new Thread(() -> {
            long nextPublishTime = System.nanoTime();
            long now = System.nanoTime();
            long counter = 0;
            while (running) {
                if (now >= nextPublishTime) {
                    counter++;
                    source.publish(counter);
                    nextPublishTime = now + 250; // 1 microsecond = 1000 nanoseconds - 250 = 4 million events per second
                }
                now = System.nanoTime();
            }
        });
        publisher.start();
    }
}
