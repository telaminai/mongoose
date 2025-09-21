/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service.scheduler;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;

/**
 * Provides a scheduling service for executing actions at specified times or after delays. The
 * {@code SchedulerService} interface includes methods for scheduling tasks based on different
 * units of time (milliseconds, microseconds, or nanoseconds).
 * <p>
 * This interface is marked as experimental and may be subject to changes in future releases.
 */
@Experimental
public interface SchedulerService {

    /**
     * Schedules a task to execute at a specific point in time.
     *
     * @param expireTime   the absolute time in milliseconds at which the task should be executed
     * @param expiryAction the action to be executed once the scheduled time is reached
     * @return a unique identifier for the scheduled task
     */
    long scheduleAtTime(long expireTime, Runnable expiryAction);

    /**
     * Schedules a task to execute after a specified delay.
     *
     * @param waitTime     the delay duration in milliseconds before the task is executed
     * @param expiryAction the action to be executed after the delay
     * @return a unique identifier for the scheduled task
     */
    long scheduleAfterDelay(long waitTime, Runnable expiryAction);

    long milliTime();

    long microTime();

    long nanoTime();
}
