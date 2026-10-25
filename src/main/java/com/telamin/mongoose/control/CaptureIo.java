/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Where a group performs its asynchronous capture effects (design "Audit capture"): opens on an I/O helper, closes on
 * the closer, and open timeouts on a timer, never on the group's thread. Replaceable, so a test can run each step at a
 * moment it chooses and drive late, duplicate and timed-out completions deterministically.
 */
public interface CaptureIo {

    /** Run an open, off the group's thread. May throw if it cannot be accepted. */
    void open(Runnable task);

    /** Run a close, off the group's thread. May throw if it cannot be accepted. */
    void close(Runnable task);

    /** Run {@code task} after {@code millis}, unless cancelled first. */
    Cancellable schedule(Runnable task, long millis);

    interface Cancellable {
        void cancel();
    }

    /** The shared default: daemon helper threads, named so a thread dump says what they are. */
    static CaptureIo shared() {
        return Shared.INSTANCE;
    }

    final class Shared implements CaptureIo {
        static final Shared INSTANCE = new Shared();

        /** Shared across servers: blocked opens cannot create unlimited helper threads or queued tasks. */
        private final java.util.concurrent.ExecutorService openers = newOpenExecutor(8, 64);

        static java.util.concurrent.ThreadPoolExecutor newOpenExecutor(int workers, int queueCapacity) {
            var executor = new java.util.concurrent.ThreadPoolExecutor(workers, workers, 60, TimeUnit.SECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(queueCapacity), daemon("mongoose-capture-open"),
                    new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
            executor.allowCoreThreadTimeOut(true);
            return executor;
        }
        private final java.util.concurrent.ExecutorService closer = Executors.newSingleThreadExecutor(daemon("mongoose-capture-closer"));
        private final ScheduledThreadPoolExecutor timer = newTimer();

        static ScheduledThreadPoolExecutor newTimer() {
            var timer = new ScheduledThreadPoolExecutor(1, daemon("mongoose-capture-timer"));
            timer.setRemoveOnCancelPolicy(true);
            return timer;
        }

        private Shared() {
        }

        @Override
        public void open(Runnable task) {
            openers.execute(task);
        }

        @Override
        public void close(Runnable task) {
            closer.execute(task);
        }

        @Override
        public Cancellable schedule(Runnable task, long millis) {
            var future = timer.schedule(task, millis, TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }

        private static ThreadFactory daemon(String name) {
            AtomicInteger n = new AtomicInteger();
            return r -> {
                Thread t = new Thread(r, name + "-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            };
        }
    }
}
