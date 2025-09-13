/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.test;

import com.fluxtion.runtime.DefaultEventProcessor;
import com.fluxtion.runtime.node.ObjectEventHandlerNode;

public class TestEventProcessor extends DefaultEventProcessor {

    public TestEventProcessor() {
        this(new SubscriptionEventHandler());
    }

    public TestEventProcessor(ObjectEventHandlerNode allEventHandler) {
        super(allEventHandler);
    }
}
