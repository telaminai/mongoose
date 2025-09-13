/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dispatch;

import java.util.Random;

public class ExponentialBackoff {

    private final long baseDelay;
    private final long maxDelay;
    private final Random random = new Random();
    private int attempts = 0;
    private long currentDelay = 0;

    public ExponentialBackoff(long baseDelay, long maxDelay) {
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
    }

    public long getWaitTime() {
        if (currentDelay < maxDelay) {
            long delay = baseDelay * (long) Math.pow(2, attempts++);
            System.out.println(delay);
            long randomizedDelay = delay + (random.nextInt((int) (Math.max(10, delay) / 10)));
            currentDelay = randomizedDelay;
            return Math.min(randomizedDelay, maxDelay);
        }
        return maxDelay;
    }

    public static void main(String[] args) throws InterruptedException {
        ExponentialBackoff backoff = new ExponentialBackoff(4, 100); // 1 second base, 10 seconds max

        for (int i = 10; i < 50; i++) {
            long delay = backoff.getWaitTime();
            System.out.println("Attempt " + (i + 1) + ", waiting " + delay + " ms");
            Thread.sleep(delay);
        }
    }
}

