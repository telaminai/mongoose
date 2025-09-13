/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.example.datamapper;

import com.fluxtion.runtime.node.ObjectEventHandlerNode;

import java.util.concurrent.CountDownLatch;

// --- Helper processor for the example ---
public class TestEventProcessor extends ObjectEventHandlerNode {
    private final CountDownLatch latch;
    private volatile TestEvent_Out last;

    public TestEventProcessor(CountDownLatch latch) {
        this.latch = latch;
    }

    public TestEvent_Out getLastProcessedEvent() {
        return last;
    }

    @Override
    protected boolean handleEvent(Object event) {
        if (event instanceof TestEvent_Out te) {
            last = te;
            latch.countDown();
        }
        // continue processing chain
        return true;
    }
}
