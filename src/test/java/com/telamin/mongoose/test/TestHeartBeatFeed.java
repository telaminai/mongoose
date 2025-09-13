/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.test;

import com.telamin.mongoose.service.extension.AbstractEventSourceService;

public class TestHeartBeatFeed extends AbstractEventSourceService<HeartbeatEvent> {

    public TestHeartBeatFeed(String name) {
        super(name);
    }

    public void fireHeartbeatEvent(HeartbeatEvent heartbeatEvent) {
        output.publish(heartbeatEvent);
    }
}
