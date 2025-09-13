/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.example.reentrant;

import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ReEntrantTest {

    @Test
    public void testReEntrant_countAndExit() throws InterruptedException {
        // Configure handler to emit a finite number of re-entrant events
        ReEntrantHandler handler = new ReEntrantHandler()
                .setRepublishWaitMillis(5)
                .setMaxCount(20)      // emit 20 events then stop
                .setThrowOnMax(false);// do not throw, just stop scheduling

        MongooseServerConfig mongooseServerConfig = new MongooseServerConfig()
                .addProcessor("handlerThread", handler, "reEntrantHandler");

        MongooseServer server = MongooseServer.bootServer(mongooseServerConfig, logRecord -> {});

        // Wait until the handler has published the expected number of events, or timeout
        long timeoutMs = 5_000;
        long start = System.currentTimeMillis();
        while (handler.getCount() < handler.getMaxCount()
                && (System.currentTimeMillis() - start) < timeoutMs) {
            Thread.sleep(10);
        }

        // Stop the server to exit cleanly
        server.stop();

        // Verify we reached the expected count
        Assertions.assertEquals(handler.getMaxCount(), handler.getCount(),
                "Re-entrant handler did not reach expected count before timeout");
    }
}
