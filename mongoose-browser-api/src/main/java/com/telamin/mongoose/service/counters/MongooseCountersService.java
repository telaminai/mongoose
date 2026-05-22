/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.counters;

/**
 * Signature-only Java 8 stub of {@code com.telamin.mongoose.service.counters.MongooseCountersService}.
 * <p>
 * Scoped to the playground examples' usage — {@link #counter(String)} only.
 * The real interface also exposes typed accessors (feedPublishCounter,
 * agentEventsCounter, etc.), {@link #forEachCounter}, and {@link #isOperational};
 * add them here only if an example starts using them.
 */
public interface MongooseCountersService {

    MongooseCounter counter(String label);
}
