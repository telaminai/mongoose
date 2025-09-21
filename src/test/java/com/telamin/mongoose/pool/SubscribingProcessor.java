/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.pool;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventSourceKey;
import com.telamin.mongoose.service.EventSubscriptionKey;

/**
 * Processor that subscribes on start to the source using ON_EVENT mapping.
 */
class SubscribingProcessor implements DataFlow, Lifecycle {
    private com.telamin.fluxtion.runtime.input.EventFeed<EventSubscriptionKey<?>> feed;

    @Override
    public void onEvent(Object event) { /* no-op */ }

    @Override
    public void init() {
    }

    @Override
    public void start() {
        // subscribe to our source when started
        EventSubscriptionKey<PooledMessage> key = new EventSubscriptionKey<>(EventSourceKey.of("poolSource"), CallBackType.ON_EVENT_CALL_BACK);
        feed.subscribe(this, key);
    }

    @Override
    public void stop() {
    }

    @Override
    public void tearDown() {
    }

    @Override
    public void startComplete() {
    }

    @Override
    public void registerService(com.telamin.fluxtion.runtime.service.Service<?> service) {
    }

    @Override
    public void addEventFeed(com.telamin.fluxtion.runtime.input.EventFeed eventFeed) {
        this.feed = eventFeed;
    }
}
