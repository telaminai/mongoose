/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.counters;

/**
 * Signature-only Java 8 stub of {@code com.telamin.mongoose.service.counters.MongooseCounter}.
 * <p>
 * Scoped to the playground examples' usage — {@link #increment()} and
 * {@link #setOrdered(long)} only. The real interface also exposes
 * {@link #incrementRelease()} and {@link #get()}; add them here only if
 * an example starts using them.
 */
public interface MongooseCounter {

    long increment();

    void setOrdered(long value);
}
