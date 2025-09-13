/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.test;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class HeartbeatEvent {
    private long timestamp;

    public HeartbeatEvent() {
        timestamp = System.nanoTime();
    }

    public HeartbeatEvent(long timestamp) {
        this.timestamp = timestamp;
    }
}
