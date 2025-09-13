/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dispatch;

import com.fluxtion.runtime.StaticEventProcessor;
import com.fluxtion.runtime.annotations.feature.Experimental;

/**
 * A concrete implementation of {@link AbstractEventToInvocationStrategy} that dispatches events
 * directly to the {@code onEvent} method of {@link StaticEventProcessor}.
 * <p>
 * This strategy ensures that all registered {@link StaticEventProcessor} targets will handle
 * the incoming events in their `onEvent` callback without any additional validation logic.
 * The default behavior deems all {@link StaticEventProcessor} instances as valid targets for event processing.
 * <p>
 * Key behaviors:
 * - The `dispatchEvent` method directly invokes the `onEvent` callback on the target processor for the provided event.
 * - The `isValidTarget` method always returns {@code true}, meaning any processor can be opted in without specific filtering criteria.
 * <p>
 * As an {@code @Experimental} feature, this implementation may be subject to future changes.
 */
@Experimental
public class EventToOnEventInvokeStrategy extends AbstractEventToInvocationStrategy {
    @Override
    protected void dispatchEvent(Object event, StaticEventProcessor eventProcessor) {
        eventProcessor.onEvent(event);
    }

    @Override
    protected boolean isValidTarget(StaticEventProcessor eventProcessor) {
        return true;
    }
}
