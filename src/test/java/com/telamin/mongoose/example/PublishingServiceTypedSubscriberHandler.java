/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.example;

import com.telamin.fluxtion.runtime.DefaultEventProcessor;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.fluxtion.runtime.output.MessageSink;

/**
 * Processor that implements a strongly-typed listener interface and subscribes to
 * a PublishingServiceTyped via @ServiceRegistered injection and service.subscribe().
 */
public class PublishingServiceTypedSubscriberHandler extends DefaultEventProcessor
        implements PublishingServiceListener {


    private final TypedHandler typedHandler;

    public PublishingServiceTypedSubscriberHandler(TypedHandler typedHandler) {
        super(typedHandler);
        this.typedHandler = typedHandler;
    }

    @Override
    public void onServiceEvent(String event) {
        typedHandler.onServiceEvent(event);
    }


    public static class TypedHandler extends ObjectEventHandlerNode implements PublishingServiceListener {

        private PublishingServiceTyped service;
        private MessageSink<String> sink;

        @ServiceRegistered
        public void wire(PublishingServiceTyped service, String name) {
            this.service = service;
        }

        @ServiceRegistered
        public void sink(MessageSink<String> sink, String name) {
            this.sink = sink;
        }

        @Override
        public void start() {
            if (service != null) {
                service.subscribe();
            }
        }

        @Override
        public void tearDown() {
            // No-op
        }

        @Override
        public void onServiceEvent(String event) {
            if (sink != null) {
                sink.accept(event);
            }
        }
    }
}
