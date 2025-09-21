/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.example;

import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.fluxtion.runtime.output.MessageSink;

/**
 * Processor that injects {@link PublishingService}, subscribes to it, and forwards received
 * String events to an injected {@link MessageSink}.
 */
public class PublishingServiceSubscriberHandler extends ObjectEventHandlerNode {

    private PublishingService publishingService;
    private MessageSink<String> sink;

    @ServiceRegistered
    public void wire(PublishingService service, String name) {
        this.publishingService = service;
    }

    @ServiceRegistered
    public void sink(MessageSink<String> sink, String name) {
        this.sink = sink;
    }

    @Override
    public void start() {
        if (publishingService != null) {
            // subscribe the current processor to the service's event stream
            publishingService.subscribe();
        }
    }

    @Override
    protected boolean handleEvent(Object event) {
        if (event instanceof String s && sink != null) {
            sink.accept(s);
        }
        return true;
    }
}
