/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.test.util;

import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import org.junit.jupiter.api.AfterEach;

/**
 * Base class for tests that boot a MongooseServer. Ensures the server is
 * stopped after each test and provides a convenience boot method.
 */
public abstract class WithServer {
    protected MongooseServer server;

    protected MongooseServer boot(MongooseServerConfig cfg, LogRecordListener listener) {
        this.server = MongooseServer.bootServer(cfg, listener);
        return this.server;
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }
}
