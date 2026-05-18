/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose;

import com.telamin.mongoose.browser.Stub;
import com.telamin.mongoose.config.MongooseServerConfig;

/**
 * Compile-only stub of {@code com.telamin.mongoose.MongooseServer}.
 * <p>
 * Scoped to the {@code mongoose-hosted} playground example's usage:
 * {@link #bootServer(MongooseServerConfig)} and {@link #stop()}. The real
 * class has further {@code bootServer} overloads (Reader / LogRecordListener /
 * system-property) — add them here only if the example starts using them, and
 * keep the drift test green.
 */
public class MongooseServer {

    /**
     * Stub of {@code MongooseServer.bootServer(MongooseServerConfig)}.
     */
    public static MongooseServer bootServer(MongooseServerConfig mongooseServerConfig) {
        throw Stub.notRunnable();
    }

    /**
     * Stub of {@code MongooseServer.stop()}.
     */
    public void stop() {
        throw Stub.notRunnable();
    }
}
