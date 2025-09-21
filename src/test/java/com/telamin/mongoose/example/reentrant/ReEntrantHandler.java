/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.example.reentrant;

import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.mongoose.service.scheduler.SchedulerService;
import lombok.Getter;

/**
 * Demonstrates re-entrant scheduling that publishes a new event cycle from inside the handler.
 * A termination condition is provided to make tests deterministic and avoid infinite scheduling.
 */
public class ReEntrantHandler extends ObjectEventHandlerNode {

    private SchedulerService schedulerService;
    @Getter
    private int count;
    @Getter
    private long republishWaitMillis = 10;

    // Termination controls for tests
    @Getter
    private int maxCount = Integer.MAX_VALUE;
    @Getter
    private boolean throwOnMax = false;

    public ReEntrantHandler setRepublishWaitMillis(long republishWaitMillis) {
        this.republishWaitMillis = republishWaitMillis;
        return this;
    }

    public ReEntrantHandler setMaxCount(int maxCount) {
        this.maxCount = maxCount;
        return this;
    }

    public ReEntrantHandler setThrowOnMax(boolean throwOnMax) {
        this.throwOnMax = throwOnMax;
        return this;
    }

    @ServiceRegistered
    public void schedulerRegistered(SchedulerService schedulerService, String name) {
        this.schedulerService = schedulerService;
    }

    @Override
    public void start() {
        publishReEntrantEvent();
    }

    private void publishReEntrantEvent() {
        // publish event into a new processing cycle
        getContext().processAsNewEventCycle("Re-Entrant Event [" + count + "]");
        count++;

        // Check termination after publishing the event
        if (count >= maxCount) {
            if (throwOnMax) {
                throw new RuntimeException("ReEntrantHandler reached maxCount=" + maxCount);
            }
            // do not reschedule, stop producing further events
            return;
        }

        // schedule next invocation via scheduler service callback
        if (schedulerService != null) {
            schedulerService.scheduleAfterDelay(republishWaitMillis, this::publishReEntrantEvent);
        }
    }

    @Override
    protected boolean handleEvent(Object event) {
        // consume the published event; in real usage, business logic would go here
        return true;
    }
}
