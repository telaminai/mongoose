/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.dispatch;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Simple retry policy for event processing.
 */
public final class RetryPolicy {
    private final int maxAttempts;
    private final long initialBackoffMillis;
    private final long maxBackoffMillis;
    private final double multiplier;
    private final Set<Class<? extends Throwable>> retryOn;

    public RetryPolicy(int maxAttempts, long initialBackoffMillis, long maxBackoffMillis, double multiplier,
                       Set<Class<? extends Throwable>> retryOn) {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >=1");
        if (initialBackoffMillis < 0 || maxBackoffMillis < 0) throw new IllegalArgumentException("backoff must be >=0");
        if (multiplier < 1.0) throw new IllegalArgumentException("multiplier must be >=1.0");
        this.maxAttempts = maxAttempts;
        this.initialBackoffMillis = initialBackoffMillis;
        this.maxBackoffMillis = Math.max(maxBackoffMillis, initialBackoffMillis);
        this.multiplier = multiplier;
        this.retryOn = retryOn;
    }

    public static RetryPolicy defaultProcessingPolicy() {
        // Default to 3 attempts, starting at 5ms, up to 100ms, x2 multiplier, retry on all RuntimeExceptions
        return new RetryPolicy(3, 5, 100, 2.0, Set.of(RuntimeException.class));
    }

    public boolean shouldRetry(Throwable t, int attempt) {
        if (attempt >= maxAttempts) {
            return false;
        }
        if (retryOn == null || retryOn.isEmpty()) {
            return true;
        }
        for (Class<? extends Throwable> c : retryOn) {
            if (c.isInstance(t)) return true;
        }
        return false;
    }

    public void backoff(int attempt) {
        if (initialBackoffMillis <= 0) return;
        long delay = (long) Math.min(maxBackoffMillis, initialBackoffMillis * Math.pow(multiplier, Math.max(0, attempt - 1)));
        try {
            TimeUnit.MILLISECONDS.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public long getInitialBackoffMillis() {
        return initialBackoffMillis;
    }

    public long getMaxBackoffMillis() {
        return maxBackoffMillis;
    }

    public double getMultiplier() {
        return multiplier;
    }
}
