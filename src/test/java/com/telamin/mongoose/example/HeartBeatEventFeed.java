/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.example;

import com.telamin.fluxtion.runtime.annotations.Start;
import com.telamin.mongoose.service.extension.AbstractAgentHostedEventSourceService;
import com.telamin.mongoose.test.HeartbeatEvent;
import lombok.Getter;
import lombok.Setter;

public class HeartBeatEventFeed extends AbstractAgentHostedEventSourceService<HeartbeatEvent> {
    //    public static final int PUBLISH_INTERVAL = 750;
    @Getter
    @Setter
    private int publishIntervalNanos = 500_000_000;
    private final HeartbeatEvent heartbeatEvent = new HeartbeatEvent();
    private long publishTime = -1;

    // Add counters for message rate tracking
    private int messageCount = 0;
    private long lastPrintTime = System.currentTimeMillis();

    public HeartBeatEventFeed() {
        super("HeartBeatService2");
    }

    @Start
    public void start() {
//        heartbeat();
    }

    private void heartbeat() {
        scheduler.scheduleAfterDelay(1, this::heartbeat);
        heartbeatEvent.setTimestamp(System.nanoTime());
        System.out.println("publish");
        output.publish(heartbeatEvent);
    }

    @Override
    public int doWork() throws Exception {
        long currentNanoTime = System.nanoTime();
        if (currentNanoTime - publishTime > publishIntervalNanos) {
            publishTime = currentNanoTime;
            heartbeatEvent.setTimestamp(System.nanoTime());
            output.publish(heartbeatEvent);

            // Increment message counter
            messageCount++;

            // Print rate every second
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastPrintTime >= 1000) {  // Check if 1 second has passed
                System.out.printf("Heartbeat messages per second: %d%n", messageCount);
                messageCount = 0;
                lastPrintTime = currentTime;
            }
            return 1;
        }
        return 0;
    }
}