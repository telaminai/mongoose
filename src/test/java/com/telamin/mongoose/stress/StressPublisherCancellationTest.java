/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.stress;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(5)
class StressPublisherCancellationTest {
    @Test
    void stalledConsumerDoesNotStrandCancelledPublisher() throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var published = new AtomicBoolean();
        var executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> {
                try {
                    StressSupport.awaitCredit(512, () -> {
                        entered.countDown();
                        return 0;
                    }, 512);
                    published.set(true);
                } catch (InterruptedException expected) {
                    interrupted.set(true);
                }
            });
            assertTrue(entered.await(1, TimeUnit.SECONDS), "publisher reached exhausted credit");
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS), "cancelled publisher must terminate");
            assertTrue(interrupted.get());
            assertFalse(published.get(), "cancelled publication must not pass credit check");
        } finally {
            executor.shutdownNow();
        }
    }
}
