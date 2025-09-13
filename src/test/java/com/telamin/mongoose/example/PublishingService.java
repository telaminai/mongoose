/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.example;

import com.telamin.mongoose.service.extension.AbstractEventSourceService;

/**
 * A minimal service that can publish String events into the Fluxtion event flow.
 * Processors can call {@link #subscribe()} (typically from start()) to receive events.
 */
public class PublishingService extends AbstractEventSourceService<String> {

    public PublishingService(String name) {
        super(name);
    }

    /**
     * Publish an event to all current subscribers.
     */
    public void publish(String event) {
        if (output != null) {
            output.publish(event);
        }
    }
}
