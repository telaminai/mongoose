/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.dispatch;

import com.fluxtion.agrona.concurrent.Agent;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventSource;
import com.telamin.mongoose.service.EventSourceKey;
import com.telamin.mongoose.service.EventSubscriptionKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class EventFlowManagerConcurrentTest {

    private static class TestEventSource implements EventSource<String> {
        @Override
        public void subscribe(EventSubscriptionKey<String> eventSourceKey) {
        }

        @Override
        public void unSubscribe(EventSubscriptionKey<String> eventSourceKey) {
        }

        @Override
        public void setEventToQueuePublisher(EventToQueuePublisher<String> targetQueue) {
            // nothing needed for this test
        }
    }

    private static class TestAgent implements Agent {
        private final String name;

        TestAgent(String name) {
            this.name = name;
        }

        @Override
        public int doWork() {
            return 0;
        }

        @Override
        public String roleName() {
            return name;
        }

        @Override
        public void onStart() {
        }

        @Override
        public void onClose() {
        }
    }

    @Test
    public void concurrentGetMappingAgentAndAppendQueues() throws Exception {
        EventFlowManager manager = new EventFlowManager();
        String sourceName = "source-A";
        manager.registerEventSource(sourceName, new TestEventSource());
        EventSourceKey<String> key = new EventSourceKey<>(sourceName);


        int threads = 5;
        int iters = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        // concurrent mapping creations
        for (int t = 0; t < threads; t++) {
            int id = t;
            pool.submit(() -> {
                await(startLatch);
                for (int i = 0; i < iters; i++) {
                    Agent subscriber = new TestAgent("agent-" + id + "-" + i);
                    manager.getMappingAgent(key, CallBackType.ON_EVENT_CALL_BACK, subscriber);
                }
                doneLatch.countDown();
            });
        }

        // concurrent appends of queue information
        List<Throwable> errors = new ArrayList<>();
        pool.submit(() -> {
            await(startLatch);
            StringBuilder sb = new StringBuilder(4096);
            while (doneLatch.getCount() > 0) {
                try {
                    sb.setLength(0);
                    manager.appendQueueInformation(sb);
                } catch (Throwable t) {
                    errors.add(t);
                }
            }
        });

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        // ensure no exceptions captured during append
        assertTrue(errors.isEmpty(), () -> "Exceptions captured during concurrent append: " + errors);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
