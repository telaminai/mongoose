/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.example.replay;

import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.fluxtion.runtime.output.MessageSink;

/**
 * A simple processor that subscribes to a named feed and, for each received event,
 * records the data-driven clock time from getContext().getClock() alongside the event.
 */
public class ReplayCaptureHandler extends ObjectEventHandlerNode {

    private final String feedName;
    private MessageSink<String> sink;

    public ReplayCaptureHandler(String feedName) {
        this.feedName = feedName;
    }

    @ServiceRegistered
    public void wire(MessageSink<String> sink, String name) {
        this.sink = sink;
    }

    @Override
    public void start() {
        // Subscribe to the configured feed by name
        getContext().subscribeToNamedFeed(feedName);
    }

    @Override
    protected boolean handleEvent(Object event) {
        if (sink == null || event == null) {
            return true;
        }
        if( event instanceof String string){
            // Capture the data-driven clock time associated with this event
            long time = getContext().getClock().getWallClockTime();
            sink.accept("event=" + event + ", time=" + time);
        }
        return true;
    }
}
