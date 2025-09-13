/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.example;

import com.telamin.mongoose.dispatch.AbstractEventToInvocationStrategy;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.extension.AbstractEventSourceService;
import com.fluxtion.runtime.StaticEventProcessor;

/**
 * A publishing service variant that delivers events via a strongly-typed
 * interface on the processor (PublishingServiceListener) using a custom
 * EventToInvokeStrategy registered for the listener's CallBackType.
 */
public class PublishingServiceTyped extends AbstractEventSourceService<String> {

    public PublishingServiceTyped(String name) {
        super(name,
                CallBackType.forClass(PublishingServiceListener.class),
                TypedInvokeStrategy::new);
    }

    public void publish(String event) {
        if (output != null) {
            output.publish(event);
        }
    }

    /**
     * Strategy that only targets processors implementing PublishingServiceListener
     * and invokes their onServiceEvent callback; otherwise falls back to onEvent.
     */
    static class TypedInvokeStrategy extends AbstractEventToInvocationStrategy {
        @Override
        protected void dispatchEvent(Object event, StaticEventProcessor eventProcessor) {
            if (eventProcessor instanceof PublishingServiceListener listener && event instanceof String s) {
                listener.onServiceEvent(s);
            } else {
                eventProcessor.onEvent(event);
            }
        }

        @Override
        protected boolean isValidTarget(StaticEventProcessor eventProcessor) {
            return eventProcessor instanceof PublishingServiceListener;
        }
    }
}
