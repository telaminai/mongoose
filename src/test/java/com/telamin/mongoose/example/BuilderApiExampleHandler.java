/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.example;

import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.fluxtion.runtime.output.MessageSink;

/**
 * Example processor that handles all incoming events (Object) and publishes
 * them to a FileMessageSink. This demonstrates how to extend
 * ObjectEventHandlerNode and use service injection to access a sink.
 */
public class BuilderApiExampleHandler extends ObjectEventHandlerNode {

    private MessageSink fileSink;

    @ServiceRegistered
    public void wire(MessageSink fileSink, String name) {
        this.fileSink = fileSink;
    }

    @Override
    protected boolean handleEvent(Object event) {
        if (fileSink != null && event != null) {
            // publish string form of event to the file sink
            fileSink.accept(event.toString());
        }
        // continue default processing chain if any
        return true;
    }
}
