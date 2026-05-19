/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service.scheduler;

/**
 * Signature-only Java 8 stub of {@code com.telamin.mongoose.service.scheduler.SchedulerService}.
 * <p>
 * Lets the Fluxtion playground's data-generator node ({@code DataGenerator})
 * type-check in CheerpJ's Java 8 javac, and lets {@code DataGenBuilder}
 * generate its processor in the browser builder phase. An interface — there
 * are no method bodies to stub; the real service is supplied by Mongoose at
 * run time, which the browser never reaches.
 */
public interface SchedulerService {

    long scheduleAtTime(long expireTime, Runnable expiryAction);

    long scheduleAfterDelay(long waitTime, Runnable expiryAction);

    long milliTime();

    long microTime();

    long nanoTime();
}
