/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service.scheduler;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.callback.AbstractCallbackNode;

/**
 * A node that schedules and triggers event cycles with a specified delay using a {@link SchedulerService}.
 * This class extends {@link AbstractCallbackNode} to integrate with the event processing model
 * and provides scheduling functionality via an injected SchedulerService.
 * <p>
 * This class is marked as experimental and may be subject to changes in future releases.
 */
@Experimental
public class ScheduledTriggerNode extends AbstractCallbackNode<Object> {

    private SchedulerService schedulerService;

    public ScheduledTriggerNode() {
        super();
    }

    public ScheduledTriggerNode(int filterId) {
        super(filterId);
    }

    @ServiceRegistered
    public void scheduler(SchedulerService scheduler) {
        this.schedulerService = scheduler;
    }

    public void triggerAfterDelay(long millis) {
        if (schedulerService != null) {
            schedulerService.scheduleAfterDelay(millis, this::fireNewEventCycle);
        }
    }
}
